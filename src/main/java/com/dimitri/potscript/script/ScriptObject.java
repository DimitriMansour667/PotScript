package com.dimitri.potscript.script;

/**
 * A script value with members, reached with the {@code .} operator. The world
 * side of the mod implements this (blocks, and whatever comes next); the
 * interpreter only knows how to ask one for a member.
 */
public interface ScriptObject {

	/** The name {@code type()} reports, e.g. "block". */
	String typeName();

	/**
	 * The member's value — for a method, a bound {@link Vm.Native} closed over
	 * this object. Throws {@link ScriptError} for a name the object lacks.
	 */
	Object member(String name);

	/** The printed form, e.g. {@code <block up>}. */
	String describe();
}
