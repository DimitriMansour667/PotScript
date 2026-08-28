package com.example.termnet.script;

/** Thrown for both compile-time and runtime errors in PotScript. */
public class ScriptError extends RuntimeException {
	public final int line;

	public ScriptError(int line, String message) {
		super(message);
		this.line = line;
	}

	public String display() {
		return (line > 0 ? "line " + line + ": " : "") + getMessage();
	}
}
