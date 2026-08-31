package com.dimitri.potscript.script;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable descriptions of the standard library, for the terminal's
 * completion list and signature hints.
 *
 * <p>Minecraft-free on purpose: {@link Builtins} is where the world gets
 * touched, this is only names and prose. The two are kept honest by
 * {@link #verifyRegistration} and {@link #verifyAllRegistered}, which
 * {@code Builtins.install} calls as it registers.
 */
public final class BuiltinDocs {

	/**
	 * One standard library function.
	 *
	 * @param paramSpec the parameter list as it should read to a human, with
	 *                  {@code [brackets]} around optional parts — the source of
	 *                  truth for both {@link #signature()} and {@link #params()}
	 * @param returns   the return type as the wiki names it
	 */
	public record Builtin(String name, String paramSpec, int minArgs, int maxArgs, String returns, String doc) {

		/** True for {@code print}, whose parameters cannot be named individually. */
		public boolean isVariadic() {
			return paramSpec.equals("...");
		}

		/** Bare parameter names, brackets stripped, in order. */
		public List<String> params() {
			List<String> names = new ArrayList<>();
			for (String part : paramSpec.split(",")) {
				String name = part.replace("[", "").replace("]", "").strip();
				if (!name.isEmpty()) names.add(name);
			}
			return names;
		}

		/** e.g. {@code sub(s, from, [to])} */
		public String signature() {
			return name + "(" + paramSpec + ")";
		}

		/** e.g. {@code sub(s, from, [to]) -> string} */
		public String display() {
			return signature() + " -> " + returns;
		}
	}

	private static final Map<String, Builtin> BY_NAME = new LinkedHashMap<>();
	private static final Map<String, Builtin> METHODS = new LinkedHashMap<>();

	private BuiltinDocs() {
	}

	private static void doc(String name, String paramSpec, int minArgs, int maxArgs, String returns, String description) {
		BY_NAME.put(name, new Builtin(name, paramSpec, minArgs, maxArgs, returns, description));
	}

	private static void method(String name, String paramSpec, int minArgs, int maxArgs, String returns, String description) {
		METHODS.put(name, new Builtin(name, paramSpec, minArgs, maxArgs, returns, description));
	}

	static {
		// ---- console ----
		doc("print", "...", 0, 16, "nil", "Prints up to 16 values, space-separated, to the console.");
		doc("clear", "", 0, 0, "nil", "Clears the console for every viewer.");
		doc("read", "", 0, 0, "string", "Blocks until a line is typed into the terminal, then returns it.");

		// ---- timing ----
		doc("sleep", "ticks", 1, 1, "nil", "Blocks for ticks game ticks (20 = 1 second).");
		doc("gametime", "", 0, 0, "number", "Total ticks the world has existed. Monotonic.");
		doc("realtime", "", 0, 0, "number", "Real-world Unix time in whole seconds.");
		doc("daytime", "", 0, 0, "number", "Overworld clock time, 0-23999 per day cycle.");
		doc("day", "", 0, 0, "number", "The current day number, floor(daytime() / 24000).");
		doc("uptime", "", 0, 0, "number", "Ticks since this program started; 0 when not running.");

		// ---- wifi networking ----
		doc("hostname", "", 0, 0, "string", "This pot's name.");
		doc("sethost", "name", 1, 1, "bool", "Renames the pot. False if invalid or already taken.");
		doc("send", "host, value", 2, 2, "bool", "Sends to one host. False if unknown, unloaded or its mailbox is full.");
		doc("broadcast", "value", 1, 1, "number", "Sends to every other host; returns the delivery count.");
		doc("peers", "", 0, 0, "list", "Sorted hostnames of all reachable pots, including this one.");
		doc("has_msg", "", 0, 0, "bool", "Whether a message is waiting.");
		doc("recv", "[timeout]", 0, 1, "list", "Blocks for a message, up to timeout ticks; nil on timeout. Yields [sender, payload].");
		doc("ping", "host", 1, 1, "bool", "Whether that hostname is reachable on the wifi network right now.");

		// ---- neighbouring blocks ----
		doc("getBlock", "side, [kind]", 1, 2, "block", "A handle to the neighbour on that side (up/down/north/south/east/west). With kind (\"chest\", \"sign\" or a block id), nil unless the block matches.");
		doc("rs_reset", "", 0, 0, "nil", "Set all six redstone outputs to 0.");
		doc("rs_wait", "[timeout]", 0, 1, "list", "Blocks until any incoming signal changes, up to timeout ticks. Yields [side, level]; nil on timeout.");

		// ---- block methods: every block ----
		method("id", "", 0, 0, "string", "Registry id of the block, e.g. \"minecraft:chest\".");
		method("side", "", 0, 0, "string", "The side this handle looks at, e.g. \"up\".");
		method("is_air", "", 0, 0, "bool", "Whether the space is currently air.");
		method("has_inv", "", 0, 0, "bool", "Whether the block currently has an inventory.");
		method("is_sign", "", 0, 0, "bool", "Whether the block is currently a sign.");
		method("rs_get", "", 0, 0, "number", "The redstone signal this block feeds into the pot.");
		method("rs_set", "level", 1, 1, "nil", "Emit level (clamped 0-15) toward this block, weak and strong.");
		method("rs_pulse", "level, [ticks]", 1, 2, "nil", "Emit level toward this block for ticks game ticks (default 2), then drop to 0.");

		// ---- block methods: chests (anything with an inventory) ----
		method("size", "", 0, 0, "number", "Slot count. Errors if the block has no inventory.");
		method("get", "slot", 1, 1, "list", "[item_id, count], or nil for an empty or out-of-range slot.");
		method("count", "item", 1, 1, "number", "Total of item across every slot.");
		method("find", "item", 1, 1, "number", "First slot holding item, or -1 if not found.");
		method("move_inv", "to, [item], [max]", 1, 3, "number", "Moves items into another block (a handle or a side name), hopper-style; returns how many moved.");

		// ---- block methods: signs ----
		method("read", "", 0, 0, "list", "The 4 front-text lines of the sign. Errors if the block is not a sign.");
		method("write", "lines, [color]", 1, 2, "nil", "Writes lines (a list of up to 4, or one string) to the sign's front; optional dye color. Errors if the block is not a sign.");

		// ---- world sensors ----
		doc("pos", "", 0, 0, "list", "[x, y, z] of the pot.");
		doc("dim", "", 0, 0, "string", "Dimension id, e.g. \"minecraft:overworld\".");
		doc("biome", "", 0, 0, "string", "Biome id, e.g. \"minecraft:plains\".");
		doc("weather", "", 0, 0, "string", "\"clear\", \"rain\" or \"thunder\".");
		doc("light", "", 0, 0, "number", "Light level (0-15) of the block directly above the pot.");
		doc("moon", "", 0, 0, "number", "Moon phase 0-7; 0 is the full moon.");

		// ---- players & sound ----
		doc("players", "[range]", 0, 1, "list", "Names of players within range blocks (default 16, clamped 0-64).");
		doc("entities", "[range]", 0, 1, "list", "[type, name, distance] per living entity within range blocks (default 8, clamped 0-32), nearest first, max 32.");
		doc("say", "text, [range]", 1, 2, "number", "Sends \"<hostname> text\" to nearby chat; returns how many were reached.");
		doc("beep", "[pitch]", 0, 1, "nil", "Plays a note block \"bit\" sound. pitch is a semitone 0-24, default 12.");
		doc("tone", "instrument, note", 2, 2, "nil", "Plays any note-block instrument (see instruments()) at semitone note 0-24.");
		doc("instruments", "", 0, 0, "list", "Every instrument name tone() accepts.");
		doc("hear", "[timeout]", 0, 1, "list", "Blocks until a player chats within 16 blocks, up to timeout ticks. Yields [player, text]; nil on timeout.");
		doc("display", "[text]", 0, 1, "nil", "Floats text above the pot as a hologram. Empty or no text takes it down.");

		// ---- persistent storage ----
		doc("store", "key, value", 2, 2, "nil", "Writes to the disk. Values are stringified.");
		doc("load", "key", 1, 1, "string", "Reads from the disk; nil if the key is absent.");
		doc("delkey", "key", 1, 1, "bool", "Deletes a key; false if it was absent.");
		doc("keys", "", 0, 0, "list", "All disk keys, in insertion order.");

		// ---- math ----
		doc("random", "", 0, 0, "number", "Uniform in [0, 1).");
		doc("randint", "a, b", 2, 2, "number", "Uniform integer, inclusive of both ends. Errors if b < a.");
		doc("floor", "x", 1, 1, "number", "Rounds down.");
		doc("ceil", "x", 1, 1, "number", "Rounds up.");
		doc("round", "x", 1, 1, "number", "Nearest integer; halves go up.");
		doc("abs", "x", 1, 1, "number", "Absolute value.");
		doc("sqrt", "x", 1, 1, "number", "Square root.");
		doc("pow", "a, b", 2, 2, "number", "a to the power b.");
		doc("min", "a, b", 2, 2, "number", "The smaller of two numbers.");
		doc("max", "a, b", 2, 2, "number", "The larger of two numbers.");
		doc("clamp", "x, lo, hi", 3, 3, "number", "x limited to [lo, hi]. Errors if hi < lo.");
		doc("sin", "x", 1, 1, "number", "Sine, in radians.");
		doc("cos", "x", 1, 1, "number", "Cosine, in radians.");
		doc("tan", "x", 1, 1, "number", "Tangent, in radians.");
		doc("atan2", "y, x", 2, 2, "number", "The angle of the vector (x, y), in radians.");
		doc("pi", "", 0, 0, "number", "3.14159..., for the trig functions.");

		// ---- values & conversion ----
		doc("len", "x", 1, 1, "number", "Length of a string or list. Errors on anything else.");
		doc("str", "x", 1, 1, "string", "The printed form of any value.");
		doc("num", "x", 1, 1, "number", "Parses a string; passes numbers through; nil if unparseable.");
		doc("type", "x", 1, 1, "string", "\"nil\", \"bool\", \"number\", \"string\", \"list\", \"function\" or \"block\".");

		// ---- strings ----
		doc("upper", "s", 1, 1, "string", "Upper-cases a string.");
		doc("lower", "s", 1, 1, "string", "Lower-cases a string.");
		doc("trim", "s", 1, 1, "string", "Strips leading and trailing whitespace.");
		doc("split", "s, sep", 2, 2, "list", "Splits on a literal separator. An empty sep splits into characters.");
		doc("join", "list, sep", 2, 2, "string", "Joins stringified elements with sep.");
		doc("sub", "s, from, [to]", 2, 3, "string", "Substring; both bounds are clamped, so it never errors.");
		doc("find", "haystack, needle", 2, 2, "number", "Index of the first match in a list or string, or -1.");
		doc("replace", "s, old, new", 3, 3, "string", "Every occurrence of old swapped for new. Errors if old is empty.");
		doc("starts", "s, prefix", 2, 2, "bool", "Whether s begins with prefix.");
		doc("ends", "s, suffix", 2, 2, "bool", "Whether s ends with suffix.");
		doc("repeat", "s, n", 2, 2, "string", "s concatenated n times.");
		doc("chr", "n", 1, 1, "string", "The character with code n.");
		doc("ord", "s", 1, 1, "number", "Code of the first character. Errors on an empty string.");

		// ---- lists ----
		doc("push", "list, value", 2, 2, "list", "Appends and returns the same list, so calls chain.");
		doc("pop", "list", 1, 1, "value", "Removes and returns the last element. Errors when empty.");
		doc("remove", "list, i", 2, 2, "value", "Removes and returns index i; negative counts from the end.");
		doc("insert", "list, i, value", 3, 3, "list", "Inserts before index i (negative counts from the end); returns the same list.");
		doc("sort", "list", 1, 1, "list", "Sorts in place, ascending; all numbers or all strings. Returns the same list.");
		doc("reverse", "list", 1, 1, "list", "Reverses in place and returns the same list.");
		doc("slice", "list, from, [to]", 2, 3, "list", "A new list of [from, to); both bounds are clamped, so it never errors.");
		doc("contains", "x, value", 2, 2, "bool", "Whether a list has an equal element, or a string a substring.");
		doc("range", "[from,] to", 1, 2, "list", "[from, from+1, ..., to-1]; from defaults to 0, to is exclusive.");
	}

	/** Every documented builtin, in the order the wiki presents them. */
	public static Collection<Builtin> all() {
		return BY_NAME.values();
	}

	public static Builtin get(String name) {
		return BY_NAME.get(name);
	}

	public static boolean isBuiltin(String name) {
		return BY_NAME.containsKey(name);
	}

	/** Every documented block method, in the order the wiki presents them. */
	public static Collection<Builtin> methods() {
		return METHODS.values();
	}

	/**
	 * The doc entry for a block method — also the source of truth for its
	 * arity: {@code BlockHandle} builds each bound native from this table, so
	 * the editor's hints can never disagree with the runtime.
	 */
	public static Builtin method(String name) {
		return METHODS.get(name);
	}

	public static boolean isMethod(String name) {
		return METHODS.containsKey(name);
	}

	/**
	 * Called from {@code Builtins.def} for each registration, so a builtin that
	 * is added, renamed or has its arity changed without touching this table
	 * fails loudly the first time a pot boots rather than quietly handing the
	 * editor a wrong signature.
	 */
	public static void verifyRegistration(String name, int minArgs, int maxArgs) {
		Builtin builtin = BY_NAME.get(name);
		if (builtin == null) {
			throw new IllegalStateException("BuiltinDocs has no entry for builtin '" + name + "'");
		}
		if (builtin.minArgs() != minArgs || builtin.maxArgs() != maxArgs) {
			throw new IllegalStateException("BuiltinDocs arity for '" + name + "' is "
					+ builtin.minArgs() + ".." + builtin.maxArgs() + " but it is registered as "
					+ minArgs + ".." + maxArgs);
		}
	}

	/** The other direction: catch table entries that no longer exist. */
	public static void verifyAllRegistered(Collection<String> registeredNames) {
		List<String> missing = BY_NAME.keySet().stream()
				.filter(name -> !registeredNames.contains(name))
				.toList();
		if (!missing.isEmpty()) {
			throw new IllegalStateException("BuiltinDocs documents builtins that are not registered: " + missing);
		}
	}
}
