package com.example.termnet.script;

/** Bytecode opcodes. Each instruction is one int, operands follow as extra ints. */
public final class Op {
	public static final int CONST = 0;        // operand: constant index
	public static final int NIL = 1;
	public static final int TRUE = 2;
	public static final int FALSE = 3;
	public static final int POP = 4;
	public static final int GET_LOCAL = 5;    // operand: stack slot
	public static final int SET_LOCAL = 6;    // operand: stack slot
	public static final int GET_GLOBAL = 7;   // operand: constant index (name)
	public static final int DEFINE_GLOBAL = 8;// operand: constant index (name)
	public static final int SET_GLOBAL = 9;   // operand: constant index (name)
	public static final int EQUAL = 10;
	public static final int GREATER = 11;
	public static final int LESS = 12;
	public static final int ADD = 13;
	public static final int SUB = 14;
	public static final int MUL = 15;
	public static final int DIV = 16;
	public static final int MOD = 17;
	public static final int NEGATE = 18;
	public static final int NOT = 19;
	public static final int JUMP = 20;         // operand: absolute target
	public static final int JUMP_IF_FALSE = 21;// operand: absolute target (pops condition)
	public static final int JUMP_IF_FALSE_KEEP = 22; // operand: target (keeps value, for 'and')
	public static final int JUMP_IF_TRUE_KEEP = 23;  // operand: target (keeps value, for 'or')
	public static final int CALL = 24;         // operand: arg count
	public static final int RETURN = 25;
	public static final int LIST = 26;         // operand: element count
	public static final int INDEX_GET = 27;
	public static final int INDEX_SET = 28;

	private Op() {
	}
}
