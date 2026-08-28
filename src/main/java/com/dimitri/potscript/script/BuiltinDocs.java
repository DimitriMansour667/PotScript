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

	private BuiltinDocs() {
	}

	private static void doc(String name, String paramSpec, int minArgs, int maxArgs, String returns, String description) {
		BY_NAME.put(name, new Builtin(name, paramSpec, minArgs, maxArgs, returns, description));
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

		// ---- redstone ----
		doc("rs_set", "side, level", 2, 2, "nil", "Emit level (clamped 0-15) out of that side, weak and strong.");
		doc("rs_pulse", "side, level, [ticks]", 2, 3, "nil", "Emit level out of that side for ticks game ticks (default 2), then drop to 0.");
		doc("rs_get", "side", 1, 1, "number", "The redstone signal the neighbour on that side feeds into the pot.");
		doc("rs_reset", "", 0, 0, "nil", "Set all six outputs to 0.");

		// ---- neighbor inventories ----
		doc("block", "side", 1, 1, "string", "Registry id of the block on that side, e.g. \"minecraft:chest\".");
		doc("inv_size", "side", 1, 1, "number", "Slot count, or nil if the neighbour has no inventory.");
		doc("inv_get", "side, slot", 2, 2, "list", "[item_id, count], or nil for an empty or out-of-range slot.");
		doc("inv_count", "side, item", 2, 2, "number", "Total of item across every slot. 0 if there's no inventory.");
		doc("inv_find", "side, item", 2, 2, "number", "First slot holding item, or -1 if not found.");
		doc("inv_move", "from, to, [item], [max]", 2, 4, "number", "Moves items between neighbours, hopper-style; returns how many moved.");

		// ---- signs ----
		doc("sign_read", "side", 1, 1, "list", "The 4 front-text lines of the sign on that side, or nil if there is no sign.");
		doc("sign_write", "side, lines, [color]", 2, 3, "bool", "Writes lines (a list of up to 4, or one string) to the sign's front; optional dye color. False if there is no sign.");

		// ---- world sensors ----
		doc("pos", "", 0, 0, "list", "[x, y, z] of the pot.");
		doc("dim", "", 0, 0, "string", "Dimension id, e.g. \"minecraft:overworld\".");
		doc("biome", "", 0, 0, "string", "Biome id, e.g. \"minecraft:plains\".");
		doc("weather", "", 0, 0, "string", "\"clear\", \"rain\" or \"thunder\".");
		doc("light", "", 0, 0, "number", "Light level (0-15) of the block directly above the pot.");

		// ---- players & sound ----
		doc("players", "[range]", 0, 1, "list", "Names of players within range blocks (default 16, clamped 0-64).");
		doc("entities", "[range]", 0, 1, "list", "[type, name, distance] per living entity within range blocks (default 8, clamped 0-32), nearest first, max 32.");
		doc("say", "text, [range]", 1, 2, "number", "Sends \"<hostname> text\" to nearby chat; returns how many were reached.");
		doc("beep", "[pitch]", 0, 1, "nil", "Plays a note block \"bit\" sound. pitch is a semitone 0-24, default 12.");

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

		// ---- values & conversion ----
		doc("len", "x", 1, 1, "number", "Length of a string or list. Errors on anything else.");
		doc("str", "x", 1, 1, "string", "The printed form of any value.");
		doc("num", "x", 1, 1, "number", "Parses a string; passes numbers through; nil if unparseable.");
		doc("type", "x", 1, 1, "string", "\"nil\", \"bool\", \"number\", \"string\", \"list\" or \"function\".");

		// ---- strings ----
		doc("upper", "s", 1, 1, "string", "Upper-cases a string.");
		doc("lower", "s", 1, 1, "string", "Lower-cases a string.");
		doc("trim", "s", 1, 1, "string", "Strips leading and trailing whitespace.");
		doc("split", "s, sep", 2, 2, "list", "Splits on a literal separator. An empty sep splits into characters.");
		doc("join", "list, sep", 2, 2, "string", "Joins stringified elements with sep.");
		doc("sub", "s, from, [to]", 2, 3, "string", "Substring; both bounds are clamped, so it never errors.");
		doc("find", "haystack, needle", 2, 2, "number", "Index of the first match in a list or string, or -1.");
		doc("chr", "n", 1, 1, "string", "The character with code n.");
		doc("ord", "s", 1, 1, "number", "Code of the first character. Errors on an empty string.");

		// ---- lists ----
		doc("push", "list, value", 2, 2, "list", "Appends and returns the same list, so calls chain.");
		doc("pop", "list", 1, 1, "value", "Removes and returns the last element. Errors when empty.");
		doc("remove", "list, i", 2, 2, "value", "Removes and returns index i; negative counts from the end.");
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
