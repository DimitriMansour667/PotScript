package com.example.termnet.script;

/** A compiled PotScript function. The top-level program is itself a function with arity 0. */
public final class ScriptFunction {
	public final String name;
	public final int arity;
	public final Chunk chunk;

	public ScriptFunction(String name, int arity, Chunk chunk) {
		this.name = name;
		this.arity = arity;
		this.chunk = chunk;
	}

	@Override
	public String toString() {
		return "<fn " + name + ">";
	}
}
