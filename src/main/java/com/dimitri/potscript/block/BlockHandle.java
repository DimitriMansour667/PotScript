package com.dimitri.potscript.block;

import com.dimitri.potscript.script.BuiltinDocs;
import com.dimitri.potscript.script.ScriptError;
import com.dimitri.potscript.script.ScriptObject;
import com.dimitri.potscript.script.Values;
import com.dimitri.potscript.script.Vm;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * The script-side view of a neighbouring block: what {@code getBlock(side)}
 * returns. A handle is live — it wraps the side, not the block that happened
 * to be there, and every method resolves the neighbour again at call time.
 * Break the chest and {@code chest.get(0)} errors instead of going stale.
 *
 * <p>Method arities come from {@link BuiltinDocs#method}, the same table the
 * editor's completion and signature hints read, so the two cannot disagree.
 */
public final class BlockHandle implements ScriptObject {

	private final ServerPotBlockEntity be;
	private final String side;

	private BlockHandle(ServerPotBlockEntity be, String side) {
		this.be = be;
		this.side = side;
	}

	public static BlockHandle of(ServerPotBlockEntity be, String side) {
		String canonical = side.toLowerCase();
		if (Direction.byName(canonical) == null) {
			throw new ScriptError(0, "getBlock: unknown side '" + side + "' (use up/down/north/south/east/west)");
		}
		return new BlockHandle(be, canonical);
	}

	/** Whether this block matches a {@code getBlock(side, kind)} filter. */
	public boolean matches(String kind) {
		String k = kind.toLowerCase();
		return switch (k) {
			case "chest", "inv", "container" -> be.hasInventory(side);
			case "sign" -> be.isSign(side);
			default -> be.blockId(side).equals(k.contains(":") ? k : "minecraft:" + k);
		};
	}

	public String side() {
		return side;
	}

	@Override
	public String typeName() {
		return "block";
	}

	@Override
	public String describe() {
		return "<block " + side + ">";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof BlockHandle handle && handle.be == be && handle.side.equals(side);
	}

	@Override
	public int hashCode() {
		return side.hashCode();
	}

	@Override
	public Object member(String name) {
		BuiltinDocs.Builtin doc = BuiltinDocs.method(name);
		if (doc == null) {
			throw new ScriptError(0, "a block has no method '" + name + "'");
		}
		Vm.NativeFn fn = switch (name) {
			// ---- every block ----
			case "id" -> (v, a) -> be.blockId(side);
			case "side" -> (v, a) -> side;
			case "is_air" -> (v, a) -> be.isAir(side);
			case "has_inv" -> (v, a) -> be.hasInventory(side);
			case "is_sign" -> (v, a) -> be.isSign(side);
			case "rs_get" -> (v, a) -> (double) be.rsGet(side);
			case "rs_set" -> (v, a) -> {
				be.rsSet(side, Math.clamp((int) toNumber(a[0], "rs_set"), 0, 15));
				return null;
			};
			case "rs_pulse" -> (v, a) -> {
				int ticks = a.length > 1 ? (int) toNumber(a[1], "rs_pulse") : 2;
				be.rsPulse(side, Math.clamp((int) toNumber(a[0], "rs_pulse"), 0, 15), ticks);
				return null;
			};

			// ---- chests: anything with an inventory ----
			case "size" -> (v, a) -> {
				needInventory("size");
				return be.invSize(side);
			};
			case "get" -> (v, a) -> {
				needInventory("get");
				return be.invGet(side, (int) toNumber(a[0], "get"));
			};
			case "count" -> (v, a) -> {
				needInventory("count");
				return be.invCount(side, asString(a[0], "count"));
			};
			case "find" -> (v, a) -> {
				needInventory("find");
				return be.invFind(side, asString(a[0], "find"));
			};
			case "move_inv" -> (v, a) -> {
				String to = switch (a[0]) {
					case BlockHandle handle -> handle.side;
					case String s -> of(be, s).side;
					case null, default ->
							throw new ScriptError(0, "move_inv: expected a block or a side name, got " + Values.typeName(a[0]));
				};
				String item = a.length > 1 && a[1] != null ? asString(a[1], "move_inv") : null;
				long max = a.length > 2 ? (long) toNumber(a[2], "move_inv") : Long.MAX_VALUE;
				return be.invMove(side, to, item, max);
			};

			// ---- signs ----
			case "read" -> (v, a) -> {
				needSign("read");
				return be.signRead(side);
			};
			case "write" -> (v, a) -> {
				needSign("write");
				List<String> lines = new ArrayList<>();
				if (a[0] instanceof ArrayList<?> list) {
					for (Object element : list) lines.add(Values.stringify(element));
				} else {
					lines.add(Values.stringify(a[0]));
				}
				String color = a.length > 1 && a[1] != null ? asString(a[1], "write") : null;
				be.signWrite(side, lines, color);
				return null;
			};

			default -> throw new IllegalStateException("block method documented but not implemented: " + name);
		};
		return new Vm.Native("block." + name, doc.minArgs(), doc.maxArgs(), fn);
	}

	private void needInventory(String fnName) {
		if (!be.hasInventory(side)) {
			throw new ScriptError(0, fnName + ": the block " + side + " (" + be.blockId(side)
					+ ") has no inventory — check with has_inv()");
		}
	}

	private void needSign(String fnName) {
		if (!be.isSign(side)) {
			throw new ScriptError(0, fnName + ": the block " + side + " (" + be.blockId(side)
					+ ") is not a sign — check with is_sign()");
		}
	}

	private static double toNumber(Object value, String fnName) {
		if (value instanceof Double d) return d;
		throw new ScriptError(0, fnName + ": expected a number, got " + Values.typeName(value));
	}

	private static String asString(Object value, String fnName) {
		if (value instanceof String s) return s;
		throw new ScriptError(0, fnName + ": expected a string, got " + Values.typeName(value));
	}
}
