package com.dimitri.potscript.script;

import java.util.ArrayList;

/** Helpers for PotScript runtime values (Double, String, Boolean, null, ArrayList). */
public final class Values {

	private Values() {
	}

	public static String typeName(Object value) {
		return switch (value) {
			case null -> "nil";
			case Double ignored -> "number";
			case String ignored -> "string";
			case Boolean ignored -> "bool";
			case ArrayList<?> ignored -> "list";
			case ScriptFunction ignored -> "function";
			case Vm.Native ignored -> "function";
			default -> value.getClass().getSimpleName();
		};
	}

	public static String stringify(Object value) {
		return switch (value) {
			case null -> "nil";
			case Double d -> {
				if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
					yield String.valueOf((long) (double) d);
				}
				yield String.valueOf(d);
			}
			case String s -> s;
			case ArrayList<?> list -> {
				StringBuilder sb = new StringBuilder("[");
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) sb.append(", ");
					Object e = list.get(i);
					if (e instanceof String s) sb.append('"').append(s).append('"');
					else sb.append(stringify(e));
					if (sb.length() > Vm.MAX_STRING) yield sb.append("...]").toString();
				}
				yield sb.append(']').toString();
			}
			default -> value.toString();
		};
	}
}
