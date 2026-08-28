package com.example.termnet.script;

import java.util.ArrayList;
import java.util.List;

/** A growable array of bytecode with per-instruction line info and a constant pool. */
public final class Chunk {
	private int[] code = new int[64];
	private int[] lines = new int[64];
	private int size;
	public final List<Object> constants = new ArrayList<>();

	public int emit(int value, int line) {
		if (size == code.length) {
			int[] newCode = new int[size * 2];
			int[] newLines = new int[size * 2];
			System.arraycopy(code, 0, newCode, 0, size);
			System.arraycopy(lines, 0, newLines, 0, size);
			code = newCode;
			lines = newLines;
		}
		code[size] = value;
		lines[size] = line;
		return size++;
	}

	public int get(int index) {
		return code[index];
	}

	public void patch(int index, int value) {
		code[index] = value;
	}

	public int line(int index) {
		return lines[index];
	}

	public int size() {
		return size;
	}

	public int addConstant(Object value) {
		// Reuse identical constants to keep the pool small.
		for (int i = 0; i < constants.size(); i++) {
			Object existing = constants.get(i);
			if (existing != null && existing.equals(value)) return i;
		}
		constants.add(value);
		return constants.size() - 1;
	}
}
