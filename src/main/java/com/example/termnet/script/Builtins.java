package com.example.termnet.script;

import com.example.termnet.block.ServerPotBlockEntity;
import com.example.termnet.script.Vm.Native;
import com.example.termnet.script.Vm.WaitKind;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PotScript's standard library. Every native closes over the block entity that
 * owns the VM, which is how scripts reach the world: redstone, the wifi
 * network, players, sounds and persistent storage.
 */
public final class Builtins {

	private Builtins() {
	}

	public static void install(Vm vm, ServerPotBlockEntity be) {
		// ---- console ----
		def(vm, "print", 0, 16, (v, args) -> {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < args.length; i++) {
				if (i > 0) sb.append(' ');
				sb.append(Values.stringify(args[i]));
			}
			be.consolePrint(sb.toString());
			return null;
		});
		def(vm, "clear", 0, 0, (v, args) -> {
			be.consoleClear();
			return null;
		});
		def(vm, "read", 0, 0, (v, args) -> {
			String line = be.pollInput();
			if (line != null) return line;
			v.park(WaitKind.INPUT, -1);
			return Vm.BLOCK;
		});

		// ---- timing ----
		def(vm, "sleep", 1, 1, (v, args) -> {
			long ticks = (long) toNumber(args[0], "sleep");
			if (ticks <= 0) return null;
			v.park(WaitKind.SLEEP, be.gameTime() + ticks);
			return Vm.BLOCK;
		});
		def(vm, "gametime", 0, 0, (v, args) -> (double) be.gameTime());
		def(vm, "daytime", 0, 0, (v, args) -> (double) be.dayTime());
		def(vm, "day", 0, 0, (v, args) -> Math.floor(be.dayTime() / 24000.0));
		def(vm, "uptime", 0, 0, (v, args) -> (double) be.programUptime());

		// ---- wifi networking ----
		def(vm, "hostname", 0, 0, (v, args) -> be.hostname());
		def(vm, "sethost", 1, 1, (v, args) -> be.trySetHostname(asString(args[0], "sethost")));
		def(vm, "send", 2, 2, (v, args) -> be.netSend(asString(args[0], "send"), args[1]));
		def(vm, "broadcast", 1, 1, (v, args) -> (double) be.netBroadcast(args[0]));
		def(vm, "peers", 0, 0, (v, args) -> new ArrayList<Object>(be.netPeers()));
		def(vm, "has_msg", 0, 0, (v, args) -> be.hasMessage());
		def(vm, "recv", 0, 1, (v, args) -> {
			Object message = be.pollMessage();
			if (message != null) return message;
			long deadline = -1;
			if (args.length == 1) deadline = be.gameTime() + (long) toNumber(args[0], "recv");
			v.park(WaitKind.MESSAGE, deadline);
			return Vm.BLOCK;
		});

		// ---- redstone ----
		def(vm, "rs_set", 2, 2, (v, args) -> {
			int level = (int) toNumber(args[1], "rs_set");
			be.rsSet(asString(args[0], "rs_set"), Math.clamp(level, 0, 15));
			return null;
		});
		def(vm, "rs_get", 1, 1, (v, args) -> (double) be.rsGet(asString(args[0], "rs_get")));
		def(vm, "rs_reset", 0, 0, (v, args) -> {
			be.rsReset();
			return null;
		});

		// ---- world sensors ----
		def(vm, "pos", 0, 0, (v, args) -> be.worldPos());
		def(vm, "dim", 0, 0, (v, args) -> be.dimensionId());
		def(vm, "biome", 0, 0, (v, args) -> be.biomeId());
		def(vm, "weather", 0, 0, (v, args) -> be.weatherName());
		def(vm, "light", 0, 0, (v, args) -> (double) be.lightLevel());

		// ---- players & sound ----
		def(vm, "players", 0, 1, (v, args) -> {
			double range = args.length > 0 ? toNumber(args[0], "players") : 16;
			return be.nearbyPlayerNames(Math.clamp(range, 0, 64));
		});
		def(vm, "say", 1, 2, (v, args) -> {
			double range = args.length > 1 ? toNumber(args[1], "say") : 16;
			return (double) be.sayToPlayers(Values.stringify(args[0]), Math.clamp(range, 0, 64));
		});
		def(vm, "beep", 0, 1, (v, args) -> {
			double pitch = args.length > 0 ? toNumber(args[0], "beep") : 12;
			be.beep((int) Math.clamp(pitch, 0, 24));
			return null;
		});

		// ---- persistent storage ----
		def(vm, "store", 2, 2, (v, args) -> {
			be.diskStore(asString(args[0], "store"), Values.stringify(args[1]));
			return null;
		});
		def(vm, "load", 1, 1, (v, args) -> be.diskLoad(asString(args[0], "load")));
		def(vm, "delkey", 1, 1, (v, args) -> be.diskDelete(asString(args[0], "delkey")));
		def(vm, "keys", 0, 0, (v, args) -> new ArrayList<Object>(be.diskKeys()));

		// ---- math ----
		def(vm, "random", 0, 0, (v, args) -> ThreadLocalRandom.current().nextDouble());
		def(vm, "randint", 2, 2, (v, args) -> {
			long a = (long) toNumber(args[0], "randint");
			long b = (long) toNumber(args[1], "randint");
			if (b < a) throw new ScriptError(0, "randint: max is below min");
			return (double) ThreadLocalRandom.current().nextLong(a, b + 1);
		});
		def(vm, "floor", 1, 1, (v, args) -> Math.floor(toNumber(args[0], "floor")));
		def(vm, "ceil", 1, 1, (v, args) -> Math.ceil(toNumber(args[0], "ceil")));
		def(vm, "round", 1, 1, (v, args) -> (double) Math.round(toNumber(args[0], "round")));
		def(vm, "abs", 1, 1, (v, args) -> Math.abs(toNumber(args[0], "abs")));
		def(vm, "sqrt", 1, 1, (v, args) -> Math.sqrt(toNumber(args[0], "sqrt")));
		def(vm, "pow", 2, 2, (v, args) -> Math.pow(toNumber(args[0], "pow"), toNumber(args[1], "pow")));
		def(vm, "min", 2, 2, (v, args) -> Math.min(toNumber(args[0], "min"), toNumber(args[1], "min")));
		def(vm, "max", 2, 2, (v, args) -> Math.max(toNumber(args[0], "max"), toNumber(args[1], "max")));

		// ---- values, strings, lists ----
		def(vm, "len", 1, 1, (v, args) -> switch (args[0]) {
			case String s -> (double) s.length();
			case ArrayList<?> l -> (double) l.size();
			case null, default -> throw new ScriptError(0, "len: expected string or list, got " + Values.typeName(args[0]));
		});
		def(vm, "str", 1, 1, (v, args) -> Values.stringify(args[0]));
		def(vm, "num", 1, 1, (v, args) -> {
			if (args[0] instanceof Double d) return d;
			try {
				return Double.parseDouble(asString(args[0], "num").trim());
			} catch (NumberFormatException e) {
				return null;
			}
		});
		def(vm, "type", 1, 1, (v, args) -> Values.typeName(args[0]));
		def(vm, "upper", 1, 1, (v, args) -> asString(args[0], "upper").toUpperCase());
		def(vm, "lower", 1, 1, (v, args) -> asString(args[0], "lower").toLowerCase());
		def(vm, "trim", 1, 1, (v, args) -> asString(args[0], "trim").trim());
		def(vm, "split", 2, 2, (v, args) -> {
			String s = asString(args[0], "split");
			String sep = asString(args[1], "split");
			ArrayList<Object> parts = new ArrayList<>();
			if (sep.isEmpty()) {
				for (int i = 0; i < s.length() && i < Vm.MAX_LIST; i++) parts.add(String.valueOf(s.charAt(i)));
			} else {
				int from = 0, at;
				while ((at = s.indexOf(sep, from)) >= 0 && parts.size() < Vm.MAX_LIST) {
					parts.add(s.substring(from, at));
					from = at + sep.length();
				}
				parts.add(s.substring(from));
			}
			return parts;
		});
		def(vm, "join", 2, 2, (v, args) -> {
			if (!(args[0] instanceof ArrayList<?> list)) throw new ScriptError(0, "join: expected a list");
			String sep = asString(args[1], "join");
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) sb.append(sep);
				sb.append(Values.stringify(list.get(i)));
				if (sb.length() > Vm.MAX_STRING) throw new ScriptError(0, "join: string too long");
			}
			return sb.toString();
		});
		def(vm, "sub", 2, 3, (v, args) -> {
			String s = asString(args[0], "sub");
			int from = Math.clamp((int) toNumber(args[1], "sub"), 0, s.length());
			int to = args.length > 2 ? Math.clamp((int) toNumber(args[2], "sub"), from, s.length()) : s.length();
			return s.substring(from, to);
		});
		def(vm, "find", 2, 2, (v, args) -> {
			if (args[0] instanceof ArrayList<?> list) {
				for (int i = 0; i < list.size(); i++) {
					Object e = list.get(i);
					if (e == null ? args[1] == null : e.equals(args[1])) return (double) i;
				}
				return -1.0;
			}
			return (double) asString(args[0], "find").indexOf(asString(args[1], "find"));
		});
		def(vm, "chr", 1, 1, (v, args) -> String.valueOf((char) (int) toNumber(args[0], "chr")));
		def(vm, "ord", 1, 1, (v, args) -> {
			String s = asString(args[0], "ord");
			if (s.isEmpty()) throw new ScriptError(0, "ord: empty string");
			return (double) s.charAt(0);
		});
		def(vm, "push", 2, 2, (v, args) -> {
			ArrayList<Object> list = asList(args[0], "push");
			if (list.size() >= Vm.MAX_LIST) throw new ScriptError(0, "push: list too long");
			list.add(args[1]);
			return args[0];
		});
		def(vm, "pop", 1, 1, (v, args) -> {
			ArrayList<Object> list = asList(args[0], "pop");
			if (list.isEmpty()) throw new ScriptError(0, "pop: list is empty");
			return list.removeLast();
		});
		def(vm, "remove", 2, 2, (v, args) -> {
			ArrayList<Object> list = asList(args[0], "remove");
			int i = (int) toNumber(args[1], "remove");
			if (i < 0) i += list.size();
			if (i < 0 || i >= list.size()) throw new ScriptError(0, "remove: index out of range");
			return list.remove(i);
		});
		def(vm, "range", 1, 2, (v, args) -> {
			double from = args.length > 1 ? toNumber(args[0], "range") : 0;
			double to = args.length > 1 ? toNumber(args[1], "range") : toNumber(args[0], "range");
			ArrayList<Object> list = new ArrayList<>();
			for (double i = from; i < to; i++) {
				if (list.size() >= Vm.MAX_LIST) throw new ScriptError(0, "range: list too long");
				list.add(i);
			}
			return list;
		});
	}

	private static void def(Vm vm, String name, int minArgs, int maxArgs, Vm.NativeFn fn) {
		vm.globals.put(name, new Native(name, minArgs, maxArgs, fn));
	}

	private static double toNumber(Object value, String fnName) {
		if (value instanceof Double d) return d;
		throw new ScriptError(0, fnName + ": expected a number, got " + Values.typeName(value));
	}

	private static String asString(Object value, String fnName) {
		if (value instanceof String s) return s;
		throw new ScriptError(0, fnName + ": expected a string, got " + Values.typeName(value));
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<Object> asList(Object value, String fnName) {
		if (value instanceof ArrayList<?> l) return (ArrayList<Object>) l;
		throw new ScriptError(0, fnName + ": expected a list, got " + Values.typeName(value));
	}

	/** One line per builtin group, printed by the terminal's `help lang` command. */
	public static List<String> cheatsheet() {
		return List.of(
				"PotScript quick reference:",
				"  let x = 1   x = x + 1   # comments",
				"  if x > 2 { } else if x > 1 { } else { }",
				"  while true { break / continue }",
				"  fn add(a, b) { return a + b }   (top level only)",
				"  lists: let l = [1, 2]  l[0]  push(l, 3)  pop(l)  len(l)",
				"  ops: + - * / %  == != < <= > >=  and or not",
				"console: print(...) clear() read()",
				"timing: sleep(ticks) gametime() daytime() day() uptime()",
				"wifi: hostname() sethost(h) send(host, v) broadcast(v)",
				"      recv([timeout]) has_msg() peers()",
				"redstone: rs_set(side, 0..15) rs_get(side) rs_reset()",
				"      sides: up down north south east west",
				"world: pos() dim() biome() weather() light()",
				"players: players([range]) say(text, [range]) beep([pitch])",
				"disk: store(k, v) load(k) delkey(k) keys()",
				"math: random() randint(a,b) floor ceil round abs sqrt pow min max",
				"text: str num type len upper lower trim split join sub find chr ord",
				"lists: push pop remove(l, i) range([from,] to)"
		);
	}
}
