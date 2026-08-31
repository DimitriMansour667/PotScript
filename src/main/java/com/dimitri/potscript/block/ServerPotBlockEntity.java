package com.dimitri.potscript.block;

import com.dimitri.potscript.PotScript;
import com.dimitri.potscript.net.Packets;
import com.dimitri.potscript.net.PotScriptNetwork;
import com.dimitri.potscript.net.PotScriptNetworking;
import com.dimitri.potscript.script.Builtins;
import com.dimitri.potscript.script.Compiler;
import com.dimitri.potscript.script.ScriptError;
import com.dimitri.potscript.script.ScriptFunction;
import com.dimitri.potscript.script.Values;
import com.dimitri.potscript.script.Vm;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A pot-sized server. Holds the PotScript program, the terminal console, the
 * persistent key/value disk, redstone output levels and the wifi mailbox, and
 * pumps the VM once per tick with a bounded instruction budget.
 */
public class ServerPotBlockEntity extends BlockEntity {

	private static final int GAS_PER_TICK = 5000;
	private static final int CONSOLE_MAX_LINES = 200;
	private static final int CONSOLE_MAX_COLS = 256;
	private static final int MAILBOX_MAX = 64;
	private static final int INPUT_QUEUE_MAX = 16;
	private static final int MAX_CODE_LENGTH = 100_000;
	private static final int MAX_DISK_ENTRIES = 256;
	private static final int VIEW_RANGE = 16;
	private static final int HEAR_RANGE = 16;
	private static final int CHAT_QUEUE_MAX = 16;
	private static final int DISPLAY_MAX_CHARS = 128;

	private String hostname = "pot-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));
	private String code = "";
	private final ArrayDeque<String> console = new ArrayDeque<>();
	private final LinkedHashMap<String, String> disk = new LinkedHashMap<>();
	private final int[] outputs = new int[6];
	private final int[] pulseTicksLeft = new int[6];

	private final ArrayDeque<ArrayList<Object>> mailbox = new ArrayDeque<>();
	private final ArrayDeque<String> inputQueue = new ArrayDeque<>();
	private final ArrayDeque<ArrayList<Object>> chatQueue = new ArrayDeque<>();
	/** Incoming signal levels snapshotted when rs_wait parks, compared against each tick. */
	private int[] rsWaitBaseline;
	private final Set<UUID> viewers = new HashSet<>();
	private final List<String> pendingLines = new ArrayList<>();

	private UUID hologramId;
	private String displayText = "";

	private Vm vm;
	private boolean running;
	private boolean autorun;
	private long programStart;
	private boolean networkRegistered;

	public ServerPotBlockEntity(BlockPos pos, BlockState state) {
		super(PotScript.SERVER_POT_BLOCK_ENTITY, pos, state);
	}

	// ------------------------------------------------------------------ ticking

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, ServerPotBlockEntity be) {
		be.ensureRegistered(level.getServer());
		be.tickPulses();

		if (be.running && be.vm != null) {
			be.pumpVm();
		} else if (be.autorun && be.vm == null) {
			// The chunk was reloaded while a program was running: restart it.
			be.startProgram();
		}
		be.flushConsole();
	}

	private void pumpVm() {
		if (vm.status() == Vm.Status.BLOCKED) {
			switch (vm.waitKind()) {
				case SLEEP -> {
					if (gameTime() >= vm.waitDeadline()) vm.resume(null);
				}
				case MESSAGE -> {
					if (!mailbox.isEmpty()) vm.resume(mailbox.poll());
					else if (vm.waitDeadline() >= 0 && gameTime() >= vm.waitDeadline()) vm.resume(null);
				}
				case INPUT -> {
					if (!inputQueue.isEmpty()) vm.resume(inputQueue.poll());
				}
				case REDSTONE -> {
					ArrayList<Object> change = rsWaitPoll();
					if (change != null) vm.resume(change);
					else if (vm.waitDeadline() >= 0 && gameTime() >= vm.waitDeadline()) vm.resume(null);
				}
				case CHAT -> {
					if (!chatQueue.isEmpty()) vm.resume(chatQueue.poll());
					else if (vm.waitDeadline() >= 0 && gameTime() >= vm.waitDeadline()) vm.resume(null);
				}
				default -> {
				}
			}
		}
		if (vm.status() != Vm.Status.RUNNING) return;

		Vm.Status status = vm.run(GAS_PER_TICK);
		if (status == Vm.Status.DONE) {
			consolePrint("[program finished]");
			finishProgram();
		} else if (status == Vm.Status.ERROR) {
			consolePrint("[error] " + vm.errorMessage());
			finishProgram();
		}
	}

	private void ensureRegistered(MinecraftServer server) {
		if (networkRegistered) return;
		if (!PotScriptNetwork.register(server, hostname, this)) {
			String fallback = hostname + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100, 0xFFF));
			consolePrint("[net] hostname '" + hostname + "' in use, now '" + fallback + "'");
			hostname = fallback;
			PotScriptNetwork.register(server, hostname, this);
			setChanged();
			sendState();
		}
		networkRegistered = true;
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		displayClear();
		unregisterFromNetwork();
	}

	/** Also called from the chunk-unload event: unloaded pots must leave the wifi network. */
	public void unregisterFromNetwork() {
		if (level != null && !level.isClientSide() && level.getServer() != null) {
			PotScriptNetwork.unregister(level.getServer(), hostname, this);
		}
		networkRegistered = false;
	}

	// ------------------------------------------------------------------ program control

	public void startProgram() {
		if (code.isBlank()) {
			autorun = false;
			consolePrint("[error] no code - type 'edit' to write a program");
			flushConsole();
			return;
		}
		ScriptFunction main;
		try {
			main = Compiler.compile(code);
		} catch (ScriptError e) {
			autorun = false;
			consolePrint("[compile error] " + e.display());
			flushConsole();
			return;
		}
		vm = new Vm(main);
		Builtins.install(vm, this);
		running = true;
		autorun = true;
		programStart = gameTime();
		setChanged();
		consolePrint("[running]");
		sendState();
	}

	public void stopProgram() {
		if (!running) return;
		consolePrint("[stopped]");
		finishProgram();
	}

	private void finishProgram() {
		vm = null;
		running = false;
		autorun = false;
		chatQueue.clear();
		rsWaitBaseline = null;
		setChanged();
		sendState();
	}

	public boolean isRunning() {
		return running;
	}

	public long programUptime() {
		return running ? gameTime() - programStart : 0;
	}

	// ------------------------------------------------------------------ terminal

	public void openTerminal(ServerPlayer player) {
		viewers.add(player.getUUID());
		PotScriptNetworking.CHANNEL.serverHandle(player).send(
				new Packets.OpenTerminal(worldPosition, hostname, code, List.copyOf(console), running));
	}

	public void closeTerminal(ServerPlayer player) {
		viewers.remove(player.getUUID());
	}

	public void handleInput(ServerPlayer player, String line) {
		line = line.strip();
		if (line.length() > CONSOLE_MAX_COLS) line = line.substring(0, CONSOLE_MAX_COLS);
		if (line.isEmpty()) return;

		consolePrint("> " + line);
		if (running) {
			if (line.equals("stop")) {
				stopProgram();
			} else if (inputQueue.size() < INPUT_QUEUE_MAX) {
				inputQueue.add(line);
			} else {
				consolePrint("[error] input queue full");
			}
		} else {
			shellCommand(line);
		}
		flushConsole();
	}

	public void setCode(String newCode, boolean andRun) {
		if (newCode.length() > MAX_CODE_LENGTH) {
			consolePrint("[error] code too long");
			flushConsole();
			return;
		}
		this.code = newCode;
		setChanged();
		if (andRun) {
			if (running) stopProgram();
			startProgram();
		} else {
			consolePrint("[saved " + newCode.length() + " chars]");
		}
		flushConsole();
	}

	private void shellCommand(String line) {
		String[] parts = line.split("\\s+", 2);
		String cmd = parts[0].toLowerCase();
		String arg = parts.length > 1 ? parts[1].strip() : "";

		switch (cmd) {
			case "help" -> {
				if (arg.equals("lang")) {
					Builtins.cheatsheet().forEach(this::consolePrint);
				} else {
					consolePrint("commands:");
					consolePrint("  run           compile & run the program");
					consolePrint("  stop          stop the running program");
					consolePrint("  edit          open the code editor");
					consolePrint("  cat           print the program code");
					consolePrint("  hostname [h]  show or set this pot's hostname");
					consolePrint("  scan          list hosts on the wifi network");
					consolePrint("  ping <host>   check whether a host is online");
					consolePrint("  ls            list stored disk keys");
					consolePrint("  rm <key>      delete a disk key");
					consolePrint("  df            disk usage");
					consolePrint("  clear         clear the console");
					consolePrint("  reboot        stop, clear console, redstone & display");
					consolePrint("  help lang     PotScript language reference");
					consolePrint("while running, typed lines are fed to read(); 'stop' halts");
				}
			}
			case "run" -> startProgram();
			case "stop" -> consolePrint("no program is running");
			case "cat" -> {
				if (code.isBlank()) consolePrint("(no code)");
				else code.lines().limit(CONSOLE_MAX_LINES).forEach(this::consolePrint);
			}
			case "hostname" -> {
				if (arg.isEmpty()) {
					consolePrint(hostname);
				} else if (trySetHostname(arg)) {
					consolePrint("hostname set to '" + hostname + "'");
				} else {
					consolePrint("[error] invalid or taken hostname (a-z, 0-9, '-', '_', max 16 chars)");
				}
			}
			case "scan" -> {
				List<String> hosts = netPeers();
				consolePrint(hosts.size() + " host(s) online:");
				for (String host : hosts) {
					consolePrint("  " + host + (host.equals(hostname) ? " (this pot)" : ""));
				}
			}
			case "ls" -> {
				if (disk.isEmpty()) consolePrint("(disk is empty)");
				else disk.forEach((k, v) -> consolePrint("  " + k + " = " + abbreviate(v)));
			}
			case "rm" -> {
				if (disk.remove(arg) != null) {
					setChanged();
					consolePrint("deleted '" + arg + "'");
				} else {
					consolePrint("[error] no such key '" + arg + "'");
				}
			}
			case "ping" -> {
				if (arg.isEmpty()) consolePrint("[error] usage: ping <host>");
				else if (netOnline(arg)) consolePrint(arg + " is online");
				else consolePrint(arg + " is unreachable");
			}
			case "df" -> consolePrint("disk: " + disk.size() + "/" + MAX_DISK_ENTRIES + " keys used");
			case "clear" -> consoleClear();
			case "reboot" -> {
				if (running) stopProgram();
				rsReset();
				displayClear();
				consoleClear();
				consolePrint("PotScript OS - '" + hostname + "' ready. Type 'help'.");
			}
			default -> consolePrint("[error] unknown command '" + cmd + "' - type 'help'");
		}
	}

	// ------------------------------------------------------------------ memory card

	public String codeForCard() {
		return code;
	}

	/** A memory card was tapped on the pot: overwrite the program with the card's copy. */
	public void installProgram(String newCode, String fromHostname) {
		if (running) stopProgram();
		code = newCode.length() > MAX_CODE_LENGTH ? newCode.substring(0, MAX_CODE_LENGTH) : newCode;
		setChanged();
		consolePrint("[card] installed program from '" + fromHostname + "' (" + code.length() + " chars)");
		flushConsole();
	}

	private static String abbreviate(String value) {
		return value.length() > 40 ? value.substring(0, 40) + "..." : value;
	}

	// ------------------------------------------------------------------ console

	public void consolePrint(String text) {
		for (String raw : text.split("\n", -1)) {
			while (raw.length() > CONSOLE_MAX_COLS) {
				addConsoleLine(raw.substring(0, CONSOLE_MAX_COLS));
				raw = raw.substring(CONSOLE_MAX_COLS);
			}
			addConsoleLine(raw);
		}
	}

	private void addConsoleLine(String line) {
		console.add(line);
		while (console.size() > CONSOLE_MAX_LINES) console.poll();
		pendingLines.add(line);
	}

	public void consoleClear() {
		console.clear();
		pendingLines.clear();
		forEachViewer(player -> PotScriptNetworking.CHANNEL.serverHandle(player).send(new Packets.ConsoleClear(worldPosition)));
	}

	private void flushConsole() {
		if (pendingLines.isEmpty()) return;
		List<String> lines = List.copyOf(pendingLines);
		pendingLines.clear();
		forEachViewer(player -> PotScriptNetworking.CHANNEL.serverHandle(player).send(new Packets.ConsoleAppend(worldPosition, lines)));
	}

	private void sendState() {
		forEachViewer(player -> PotScriptNetworking.CHANNEL.serverHandle(player).send(new Packets.TerminalState(worldPosition, running, hostname)));
	}

	private void forEachViewer(java.util.function.Consumer<ServerPlayer> action) {
		if (viewers.isEmpty() || level == null || level.isClientSide() || level.getServer() == null) return;
		viewers.removeIf(uuid -> {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
			if (player == null || player.level() != level
					|| player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) > VIEW_RANGE * VIEW_RANGE) {
				return true;
			}
			action.accept(player);
			return false;
		});
	}

	// ------------------------------------------------------------------ script I/O: wifi

	public String hostname() {
		return hostname;
	}

	public boolean trySetHostname(String name) {
		name = name.toLowerCase();
		if (!name.matches("[a-z0-9_-]{1,16}")) return false;
		if (name.equals(hostname)) return true;
		if (level == null || level.getServer() == null) return false;
		ServerPotBlockEntity taken = PotScriptNetwork.lookup(level.getServer(), name);
		if (taken != null && taken != this) return false;
		PotScriptNetwork.unregister(level.getServer(), hostname, this);
		hostname = name;
		PotScriptNetwork.register(level.getServer(), hostname, this);
		setChanged();
		sendState();
		return true;
	}

	public boolean netSend(String host, Object payload) {
		if (level == null || level.getServer() == null) return false;
		ServerPotBlockEntity target = PotScriptNetwork.lookup(level.getServer(), host);
		if (target == null) return false;
		return target.deliver(hostname, payload);
	}

	public int netBroadcast(Object payload) {
		if (level == null || level.getServer() == null) return 0;
		int delivered = 0;
		for (String host : PotScriptNetwork.hostnames(level.getServer())) {
			if (host.equals(hostname)) continue;
			ServerPotBlockEntity target = PotScriptNetwork.lookup(level.getServer(), host);
			if (target != null && target.deliver(hostname, payload)) delivered++;
		}
		return delivered;
	}

	public List<String> netPeers() {
		if (level == null || level.getServer() == null) return List.of();
		return PotScriptNetwork.hostnames(level.getServer());
	}

	public boolean netOnline(String host) {
		if (level == null || level.getServer() == null) return false;
		return host.equals(hostname) || PotScriptNetwork.lookup(level.getServer(), host) != null;
	}

	private boolean deliver(String from, Object payload) {
		if (mailbox.size() >= MAILBOX_MAX) return false;
		ArrayList<Object> message = new ArrayList<>(2);
		message.add(from);
		message.add(copyPayload(payload, 0));
		mailbox.add(message);
		return true;
	}

	/** Deep-copies a message payload so sender and receiver never share mutable state. */
	private static Object copyPayload(Object value, int depth) {
		if (depth > 8) throw new ScriptError(0, "send: message nested too deep");
		return switch (value) {
			case null -> null;
			case Double d -> d;
			case String s -> s;
			case Boolean b -> b;
			case ArrayList<?> list -> {
				ArrayList<Object> copy = new ArrayList<>(list.size());
				for (Object element : list) copy.add(copyPayload(element, depth + 1));
				yield copy;
			}
			default -> throw new ScriptError(0, "send: cannot send a " + Values.typeName(value));
		};
	}

	public boolean hasMessage() {
		return !mailbox.isEmpty();
	}

	public ArrayList<Object> pollMessage() {
		return mailbox.poll();
	}

	public String pollInput() {
		return inputQueue.poll();
	}

	// ------------------------------------------------------------------ script I/O: redstone

	private static Direction sideToDirection(String side, String fnName) {
		Direction direction = Direction.byName(side.toLowerCase());
		if (direction == null) {
			throw new ScriptError(0, fnName + ": unknown side '" + side + "' (use up/down/north/south/east/west)");
		}
		return direction;
	}

	public void rsSet(String side, int emittedLevel) {
		Direction direction = sideToDirection(side, "rs_set");
		pulseTicksLeft[direction.get3DDataValue()] = 0;
		if (outputs[direction.get3DDataValue()] == emittedLevel) return;
		outputs[direction.get3DDataValue()] = emittedLevel;
		setChanged();
		notifyRedstone();
	}

	public void rsPulse(String side, int emittedLevel, int ticks) {
		Direction direction = sideToDirection(side, "rs_pulse");
		if (ticks <= 0) throw new ScriptError(0, "rs_pulse: ticks must be positive");
		int i = direction.get3DDataValue();
		pulseTicksLeft[i] = ticks;
		setChanged();
		if (outputs[i] != emittedLevel) {
			outputs[i] = emittedLevel;
			notifyRedstone();
		}
	}

	/** Counts down rs_pulse timers; a side whose timer hits zero drops back to 0. */
	private void tickPulses() {
		for (int i = 0; i < pulseTicksLeft.length; i++) {
			if (pulseTicksLeft[i] > 0 && --pulseTicksLeft[i] == 0 && outputs[i] != 0) {
				outputs[i] = 0;
				setChanged();
				notifyRedstone();
			}
		}
	}

	public void rsReset() {
		boolean changed = false;
		for (int i = 0; i < outputs.length; i++) {
			pulseTicksLeft[i] = 0;
			if (outputs[i] != 0) {
				outputs[i] = 0;
				changed = true;
			}
		}
		if (changed) {
			setChanged();
			notifyRedstone();
		}
	}

	private void notifyRedstone() {
		if (level == null) return;
		level.updateNeighborsAt(worldPosition, getBlockState().getBlock(), null);
		for (Direction direction : Direction.values()) {
			level.updateNeighborsAt(worldPosition.relative(direction), getBlockState().getBlock(), null);
		}
	}

	public int outputSignal(Direction towardNeighbor) {
		return outputs[towardNeighbor.get3DDataValue()];
	}

	public int rsGet(String side) {
		Direction direction = sideToDirection(side, "rs_get");
		if (level == null) return 0;
		return level.getSignal(worldPosition.relative(direction), direction);
	}

	/** rs_wait is parking: remember what the six inputs look like right now. */
	public void rsWaitBegin() {
		rsWaitBaseline = currentInputs();
	}

	/** The first input that differs from the rs_wait baseline, as [side, level], or null. */
	private ArrayList<Object> rsWaitPoll() {
		if (rsWaitBaseline == null) return null;
		int[] now = currentInputs();
		for (Direction direction : Direction.values()) {
			int i = direction.get3DDataValue();
			if (now[i] != rsWaitBaseline[i]) {
				rsWaitBaseline = null;
				ArrayList<Object> change = new ArrayList<>(2);
				change.add(direction.getName());
				change.add((double) now[i]);
				return change;
			}
		}
		return null;
	}

	private int[] currentInputs() {
		int[] inputs = new int[6];
		if (level == null) return inputs;
		for (Direction direction : Direction.values()) {
			inputs[direction.get3DDataValue()] = level.getSignal(worldPosition.relative(direction), direction);
		}
		return inputs;
	}

	// ------------------------------------------------------------------ script I/O: chat hearing

	/** Called for every chat message on the server; keep it only if we can hear it. */
	public void onChatHeard(ServerPlayer sender, String text) {
		if (!running || level == null || sender.level() != level) return;
		if (sender.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5)
				> (double) HEAR_RANGE * HEAR_RANGE) {
			return;
		}
		if (chatQueue.size() >= CHAT_QUEUE_MAX) chatQueue.poll();
		ArrayList<Object> heard = new ArrayList<>(2);
		heard.add(sender.getGameProfile().name());
		heard.add(text);
		chatQueue.add(heard);
	}

	public ArrayList<Object> pollChat() {
		return chatQueue.poll();
	}

	// ------------------------------------------------------------------ script I/O: hologram

	/** Puts a floating text display above the pot; blank text takes it down. */
	public void displaySet(String text) {
		if (text.length() > DISPLAY_MAX_CHARS) text = text.substring(0, DISPLAY_MAX_CHARS);
		if (text.isBlank()) {
			displayClear();
			return;
		}
		displayText = text;
		setChanged();
		Display.TextDisplay hologram = hologram(true);
		if (hologram != null) hologram.setText(Component.literal(text));
	}

	public void displayClear() {
		displayText = "";
		Display.TextDisplay hologram = hologram(false);
		if (hologram != null) hologram.discard();
		hologramId = null;
		setChanged();
	}

	/** The pot's text display entity, resolved by UUID; spawned fresh if asked to. */
	private Display.TextDisplay hologram(boolean createIfMissing) {
		if (!(level instanceof ServerLevel serverLevel)) return null;
		if (hologramId != null
				&& serverLevel.getEntityInAnyDimension(hologramId) instanceof Display.TextDisplay existing
				&& existing.isAlive()) {
			return existing;
		}
		if (!createIfMissing) return null;
		Display.TextDisplay hologram = EntityTypes.TEXT_DISPLAY.create(serverLevel, EntitySpawnReason.TRIGGERED);
		if (hologram == null) return null;
		hologram.setPos(worldPosition.getX() + 0.5, worldPosition.getY() + 0.75, worldPosition.getZ() + 0.5);
		hologram.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		serverLevel.addFreshEntity(hologram);
		hologramId = hologram.getUUID();
		setChanged();
		return hologram;
	}

	// ------------------------------------------------------------------ script I/O: inventories

	/**
	 * Reads go through the plain {@link Container} view (whole inventory, double
	 * chests merged by {@link HopperBlockEntity#getContainerAt}); moves go through
	 * the Fabric Transfer API so sided rules (furnace fuel/input/output) and
	 * modded machines behave exactly like a hopper would.
	 */
	private Container containerAt(String side, String fnName) {
		Direction direction = sideToDirection(side, fnName);
		if (level == null) return null;
		return HopperBlockEntity.getContainerAt(level, worldPosition.relative(direction));
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static Item resolveItem(String itemArg, String fnName) {
		Identifier id = Identifier.tryParse(itemArg.toLowerCase());
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
			throw new ScriptError(0, fnName + ": unknown item '" + itemArg + "'");
		}
		return BuiltInRegistries.ITEM.getValue(id);
	}

	public String blockId(String side) {
		Direction direction = sideToDirection(side, "block");
		if (level == null) return "minecraft:air";
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(worldPosition.relative(direction)).getBlock()).toString();
	}

	public Object invSize(String side) {
		Container container = containerAt(side, "inv_size");
		return container == null ? null : (double) container.getContainerSize();
	}

	public Object invGet(String side, int slot) {
		Container container = containerAt(side, "inv_get");
		if (container == null || slot < 0 || slot >= container.getContainerSize()) return null;
		ItemStack stack = container.getItem(slot);
		if (stack.isEmpty()) return null;
		ArrayList<Object> entry = new ArrayList<>(2);
		entry.add(itemId(stack));
		entry.add((double) stack.getCount());
		return entry;
	}

	public double invCount(String side, String itemArg) {
		Item item = resolveItem(itemArg, "inv_count");
		Container container = containerAt(side, "inv_count");
		return container == null ? 0 : container.countItem(item);
	}

	public double invFind(String side, String itemArg) {
		Item item = resolveItem(itemArg, "inv_find");
		Container container = containerAt(side, "inv_find");
		if (container == null) return -1;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).is(item)) return slot;
		}
		return -1;
	}

	public double invMove(String fromSide, String toSide, String itemArg, long max) {
		Direction from = sideToDirection(fromSide, "inv_move");
		Direction to = sideToDirection(toSide, "inv_move");
		if (from == to) throw new ScriptError(0, "inv_move: source and target are the same side");
		Item item = itemArg != null ? resolveItem(itemArg, "inv_move") : null;
		if (level == null || max <= 0) return 0;
		Storage<ItemVariant> source = ItemStorage.SIDED.find(level, worldPosition.relative(from), from.getOpposite());
		Storage<ItemVariant> target = ItemStorage.SIDED.find(level, worldPosition.relative(to), to.getOpposite());
		if (source == null || target == null) return 0;
		try (Transaction tx = Transaction.openOuter()) {
			long moved = StorageUtil.move(source, target,
					variant -> item == null || variant.getItem() == item, max, tx);
			tx.commit();
			return moved;
		}
	}

	// ------------------------------------------------------------------ script I/O: signs

	private static final int SIGN_LINES = 4;
	private static final int SIGN_MAX_COLS = 48;

	private SignBlockEntity signAt(String side, String fnName) {
		Direction direction = sideToDirection(side, fnName);
		if (level == null) return null;
		return level.getBlockEntity(worldPosition.relative(direction)) instanceof SignBlockEntity sign ? sign : null;
	}

	public Object signRead(String side) {
		SignBlockEntity sign = signAt(side, "sign_read");
		if (sign == null) return null;
		ArrayList<Object> lines = new ArrayList<>(SIGN_LINES);
		for (int i = 0; i < SIGN_LINES; i++) {
			lines.add(sign.getFrontText().getMessage(i, false).getString());
		}
		return lines;
	}

	public boolean signWrite(String side, List<String> lines, String colorName) {
		if (lines.size() > SIGN_LINES) throw new ScriptError(0, "sign_write: a sign has " + SIGN_LINES + " lines");
		SignBlockEntity sign = signAt(side, "sign_write");
		if (sign == null) return false;
		SignText text = sign.getFrontText();
		if (colorName != null) {
			DyeColor color = DyeColor.byName(colorName.toLowerCase(), null);
			if (color == null) throw new ScriptError(0, "sign_write: unknown dye color '" + colorName + "'");
			text = text.setColor(color);
		}
		for (int i = 0; i < SIGN_LINES; i++) {
			String line = i < lines.size() ? lines.get(i) : "";
			if (line.length() > SIGN_MAX_COLS) line = line.substring(0, SIGN_MAX_COLS);
			text = text.setMessage(i, Component.literal(line));
		}
		sign.setText(text, true);
		return true;
	}

	// ------------------------------------------------------------------ script I/O: world & players

	public long gameTime() {
		return level != null ? level.getGameTime() : 0;
	}

	public long dayTime() {
		return level != null ? level.getOverworldClockTime() : 0;
	}

	public ArrayList<Object> worldPos() {
		ArrayList<Object> position = new ArrayList<>(3);
		position.add((double) worldPosition.getX());
		position.add((double) worldPosition.getY());
		position.add((double) worldPosition.getZ());
		return position;
	}

	public String dimensionId() {
		return level != null ? level.dimension().identifier().toString() : "unknown";
	}

	public String biomeId() {
		if (level == null) return "unknown";
		return level.getBiome(worldPosition).unwrapKey()
				.map(key -> key.identifier().toString())
				.orElse("unknown");
	}

	public String weatherName() {
		if (level == null) return "clear";
		if (level.isThundering()) return "thunder";
		if (level.isRaining()) return "rain";
		return "clear";
	}

	public int lightLevel() {
		return level != null ? level.getRawBrightness(worldPosition.above(), 0) : 0;
	}

	/** Moon phase 0-7, 0 = full moon, computed the same way vanilla does. */
	public int moonPhase() {
		return (int) (dayTime() / 24000L % 8L + 8L) % 8;
	}

	private static final int MAX_ENTITY_RESULTS = 32;

	/** [type_id, display_name, distance] per living entity, nearest first. */
	public ArrayList<Object> nearbyEntities(double range) {
		ArrayList<Object> result = new ArrayList<>();
		if (!(level instanceof ServerLevel serverLevel)) return result;
		Vec3 center = Vec3.atCenterOf(worldPosition);
		List<LivingEntity> found = serverLevel.getEntitiesOfClass(LivingEntity.class,
				new AABB(worldPosition).inflate(range), LivingEntity::isAlive);
		found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(center)));
		for (LivingEntity entity : found) {
			if (result.size() >= MAX_ENTITY_RESULTS) break;
			double distance = Math.sqrt(entity.distanceToSqr(center));
			if (distance > range) continue;
			ArrayList<Object> entry = new ArrayList<>(3);
			entry.add(EntityType.getKey(entity.getType()).toString());
			entry.add(entity.getName().getString());
			entry.add(Math.round(distance * 10.0) / 10.0);
			result.add(entry);
		}
		return result;
	}

	public ArrayList<Object> nearbyPlayerNames(double range) {
		ArrayList<Object> names = new ArrayList<>();
		for (ServerPlayer player : playersWithin(range)) {
			names.add(player.getGameProfile().name());
		}
		return names;
	}

	public int sayToPlayers(String text, double range) {
		if (text.length() > CONSOLE_MAX_COLS) text = text.substring(0, CONSOLE_MAX_COLS);
		int count = 0;
		for (ServerPlayer player : playersWithin(range)) {
			player.sendSystemMessage(Component.literal("<" + hostname + "> " + text));
			count++;
		}
		return count;
	}

	private List<ServerPlayer> playersWithin(double range) {
		List<ServerPlayer> result = new ArrayList<>();
		if (!(level instanceof ServerLevel serverLevel)) return result;
		double rangeSq = range * range;
		for (ServerPlayer player : serverLevel.players()) {
			if (player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= rangeSq) {
				result.add(player);
			}
		}
		return result;
	}

	public void beep(int note) {
		if (level == null) return;
		float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
		level.playSound(null, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
				SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 0.8f, pitch);
	}

	/** Plays any note-block instrument at a semitone 0-24, like a note block would. */
	public void playTone(String instrumentName, int note) {
		NoteBlockInstrument instrument = null;
		for (NoteBlockInstrument candidate : NoteBlockInstrument.values()) {
			if (candidate.getSerializedName().equals(instrumentName.toLowerCase())) {
				instrument = candidate;
				break;
			}
		}
		if (instrument == null || instrument == NoteBlockInstrument.CUSTOM_HEAD) {
			throw new ScriptError(0, "tone: unknown instrument '" + instrumentName + "'");
		}
		if (level == null) return;
		float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
		level.playSound(null, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5,
				instrument.getSoundEvent().value(), SoundSource.BLOCKS, 0.9f, pitch);
	}

	/** Instrument names for tone(), in note-block order. */
	public static ArrayList<Object> toneInstruments() {
		ArrayList<Object> names = new ArrayList<>();
		for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
			if (instrument != NoteBlockInstrument.CUSTOM_HEAD) names.add(instrument.getSerializedName());
		}
		return names;
	}

	// ------------------------------------------------------------------ script I/O: disk

	public void diskStore(String key, String value) {
		if (key.length() > 64) throw new ScriptError(0, "store: key too long (max 64)");
		if (value.length() > 4096) throw new ScriptError(0, "store: value too long (max 4096)");
		if (!disk.containsKey(key) && disk.size() >= MAX_DISK_ENTRIES) {
			throw new ScriptError(0, "store: disk full (max " + MAX_DISK_ENTRIES + " keys)");
		}
		disk.put(key, value);
		setChanged();
	}

	public String diskLoad(String key) {
		return disk.get(key);
	}

	public boolean diskDelete(String key) {
		boolean removed = disk.remove(key) != null;
		if (removed) setChanged();
		return removed;
	}

	public List<String> diskKeys() {
		return List.copyOf(disk.keySet());
	}

	// ------------------------------------------------------------------ persistence

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putString("hostname", hostname);
		out.putString("code", code);
		out.putBoolean("autorun", autorun || running);
		out.putIntArray("outputs", outputs.clone());
		out.putIntArray("pulses", pulseTicksLeft.clone());
		out.putString("display", displayText);
		if (hologramId != null) out.store("hologram", UUIDUtil.CODEC, hologramId);
		out.store("console", Codec.STRING.listOf(), List.copyOf(console));
		out.store("disk_keys", Codec.STRING.listOf(), List.copyOf(disk.keySet()));
		out.store("disk_values", Codec.STRING.listOf(), List.copyOf(disk.values()));
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		hostname = in.getStringOr("hostname", hostname);
		code = in.getStringOr("code", "");
		autorun = in.getBooleanOr("autorun", false);
		int[] savedOutputs = in.getIntArray("outputs").orElse(null);
		if (savedOutputs != null && savedOutputs.length == 6) {
			System.arraycopy(savedOutputs, 0, outputs, 0, 6);
		}
		int[] savedPulses = in.getIntArray("pulses").orElse(null);
		if (savedPulses != null && savedPulses.length == 6) {
			System.arraycopy(savedPulses, 0, pulseTicksLeft, 0, 6);
		}
		displayText = in.getStringOr("display", "");
		hologramId = in.read("hologram", UUIDUtil.CODEC).orElse(null);
		console.clear();
		console.addAll(in.read("console", Codec.STRING.listOf()).orElse(List.of()));
		disk.clear();
		List<String> keys = in.read("disk_keys", Codec.STRING.listOf()).orElse(List.of());
		List<String> values = in.read("disk_values", Codec.STRING.listOf()).orElse(List.of());
		for (int i = 0; i < Math.min(keys.size(), values.size()); i++) {
			disk.put(keys.get(i), values.get(i));
		}
	}
}
