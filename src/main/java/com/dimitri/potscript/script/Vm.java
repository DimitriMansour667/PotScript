package com.dimitri.potscript.script;

import java.util.ArrayList;
import java.util.List;

/**
 * Stack VM for PotScript. Execution is metered: {@link #run(int)} executes at
 * most the given number of instructions and then yields, so a busy program can
 * never stall the server tick. Native functions may block the VM (sleep, recv,
 * read); the scheduler resumes it later via {@link #resume(Object)}.
 */
public final class Vm {

	/** Sentinel returned by natives that have parked the VM instead of producing a value. */
	public static final Object BLOCK = new Object();

	public enum Status {
		RUNNING, BLOCKED, DONE, ERROR
	}

	public enum WaitKind {
		NONE, SLEEP, MESSAGE, INPUT
	}

	/** A native (built-in) function. May return {@link #BLOCK} after calling {@link #park}. */
	public interface NativeFn {
		Object call(Vm vm, Object[] args);
	}

	public record Native(String name, int minArgs, int maxArgs, NativeFn fn) {
		@Override
		public String toString() {
			return "<builtin " + name + ">";
		}
	}

	private static final int STACK_MAX = 1024;
	private static final int FRAMES_MAX = 64;
	public static final int MAX_STRING = 50_000;
	public static final int MAX_LIST = 10_000;

	private static final class Frame {
		final ScriptFunction fn;
		int ip;
		final int base;

		Frame(ScriptFunction fn, int base) {
			this.fn = fn;
			this.base = base;
		}
	}

	private final Object[] stack = new Object[STACK_MAX];
	private int sp;
	private final List<Frame> frames = new ArrayList<>();
	public final java.util.HashMap<String, Object> globals = new java.util.HashMap<>();

	private Status status = Status.RUNNING;
	private WaitKind waitKind = WaitKind.NONE;
	/** Game-time deadline for SLEEP, or for MESSAGE waits with a timeout (-1 = wait forever). */
	private long waitDeadline = -1;
	private String errorMessage;

	public Vm(ScriptFunction main) {
		push(main);
		frames.add(new Frame(main, 0));
	}

	public Status status() {
		return status;
	}

	public WaitKind waitKind() {
		return waitKind;
	}

	public long waitDeadline() {
		return waitDeadline;
	}

	public String errorMessage() {
		return errorMessage;
	}

	/** Parks the VM; the calling native must return {@link #BLOCK}. */
	public void park(WaitKind kind, long deadline) {
		this.waitKind = kind;
		this.waitDeadline = deadline;
	}

	/** Resumes a blocked VM, pushing the given value as the blocking call's result. */
	public void resume(Object value) {
		if (status != Status.BLOCKED) return;
		push(value);
		status = Status.RUNNING;
		waitKind = WaitKind.NONE;
		waitDeadline = -1;
	}

	/**
	 * Executes up to {@code gas} instructions. Returns the VM status afterwards;
	 * on ERROR, {@link #errorMessage()} describes the failure.
	 */
	public Status run(int gas) {
		if (status != Status.RUNNING) return status;
		try {
			return execute(gas);
		} catch (ScriptError e) {
			status = Status.ERROR;
			errorMessage = e.display();
			return status;
		} catch (StackOverflowError | ArrayIndexOutOfBoundsException e) {
			status = Status.ERROR;
			errorMessage = "internal error: " + e;
			return status;
		}
	}

	private Status execute(int gas) {
		Frame frame = frames.getLast();
		Chunk chunk = frame.fn.chunk;

		for (int steps = 0; steps < gas; steps++) {
			int op = chunk.get(frame.ip++);
			switch (op) {
				case Op.CONST -> push(chunk.constants.get(chunk.get(frame.ip++)));
				case Op.NIL -> push(null);
				case Op.TRUE -> push(Boolean.TRUE);
				case Op.FALSE -> push(Boolean.FALSE);
				case Op.POP -> pop();
				case Op.GET_LOCAL -> push(stack[frame.base + chunk.get(frame.ip++)]);
				case Op.SET_LOCAL -> stack[frame.base + chunk.get(frame.ip++)] = peek(0);
				case Op.GET_GLOBAL -> {
					String name = (String) chunk.constants.get(chunk.get(frame.ip++));
					if (!globals.containsKey(name)) {
						throw runtimeError(frame, "undefined variable '" + name + "'");
					}
					push(globals.get(name));
				}
				case Op.DEFINE_GLOBAL -> globals.put((String) chunk.constants.get(chunk.get(frame.ip++)), pop());
				case Op.SET_GLOBAL -> {
					String name = (String) chunk.constants.get(chunk.get(frame.ip++));
					if (!globals.containsKey(name)) {
						throw runtimeError(frame, "undefined variable '" + name + "'");
					}
					globals.put(name, peek(0));
				}
				case Op.EQUAL -> {
					Object b = pop(), a = pop();
					push(valuesEqual(a, b));
				}
				case Op.GREATER -> {
					double b = number(frame, pop()), a = number(frame, pop());
					push(a > b);
				}
				case Op.LESS -> {
					double b = number(frame, pop()), a = number(frame, pop());
					push(a < b);
				}
				case Op.ADD -> {
					Object b = pop(), a = pop();
					if (a instanceof String || b instanceof String) {
						String result = Values.stringify(a) + Values.stringify(b);
						if (result.length() > MAX_STRING) throw runtimeError(frame, "string too long");
						push(result);
					} else if (a instanceof Double da && b instanceof Double db) {
						push(da + db);
					} else if (a instanceof ArrayList<?> la && b instanceof ArrayList<?> lb) {
						if (la.size() + lb.size() > MAX_LIST) throw runtimeError(frame, "list too long");
						ArrayList<Object> joined = new ArrayList<>(la);
						joined.addAll(lb);
						push(joined);
					} else {
						throw runtimeError(frame, "cannot add " + Values.typeName(a) + " and " + Values.typeName(b));
					}
				}
				case Op.SUB -> {
					double b = number(frame, pop()), a = number(frame, pop());
					push(a - b);
				}
				case Op.MUL -> {
					double b = number(frame, pop()), a = number(frame, pop());
					push(a * b);
				}
				case Op.DIV -> {
					double b = number(frame, pop()), a = number(frame, pop());
					if (b == 0) throw runtimeError(frame, "division by zero");
					push(a / b);
				}
				case Op.MOD -> {
					double b = number(frame, pop()), a = number(frame, pop());
					if (b == 0) throw runtimeError(frame, "modulo by zero");
					push(a % b);
				}
				case Op.NEGATE -> push(-number(frame, pop()));
				case Op.NOT -> push(!truthy(pop()));
				case Op.JUMP -> frame.ip = chunk.get(frame.ip);
				case Op.JUMP_IF_FALSE -> {
					int target = chunk.get(frame.ip++);
					if (!truthy(pop())) frame.ip = target;
				}
				case Op.JUMP_IF_FALSE_KEEP -> {
					int target = chunk.get(frame.ip++);
					if (!truthy(peek(0))) frame.ip = target;
				}
				case Op.JUMP_IF_TRUE_KEEP -> {
					int target = chunk.get(frame.ip++);
					if (truthy(peek(0))) frame.ip = target;
				}
				case Op.LIST -> {
					int count = chunk.get(frame.ip++);
					ArrayList<Object> list = new ArrayList<>(count);
					for (int i = 0; i < count; i++) list.add(peek(count - 1 - i));
					for (int i = 0; i < count; i++) pop();
					push(list);
				}
				case Op.INDEX_GET -> {
					Object index = pop(), target = pop();
					push(indexGet(frame, target, index));
				}
				case Op.INDEX_SET -> {
					Object value = pop(), index = pop(), target = pop();
					if (!(target instanceof ArrayList<?>)) {
						throw runtimeError(frame, "can only assign into a list, not " + Values.typeName(target));
					}
					@SuppressWarnings("unchecked")
					ArrayList<Object> list = (ArrayList<Object>) target;
					int i = listIndex(frame, list.size(), index);
					list.set(i, value);
					push(value);
				}
				case Op.CALL -> {
					int argc = chunk.get(frame.ip++);
					Object callee = peek(argc);
					if (callee instanceof ScriptFunction fn) {
						if (argc != fn.arity) {
							throw runtimeError(frame, fn.name + " expects " + fn.arity + " argument(s), got " + argc);
						}
						if (frames.size() >= FRAMES_MAX) throw runtimeError(frame, "call stack overflow");
						Frame callFrame = new Frame(fn, sp - argc - 1);
						frames.add(callFrame);
						frame = callFrame;
						chunk = fn.chunk;
					} else if (callee instanceof Native nat) {
						if (argc < nat.minArgs() || argc > nat.maxArgs()) {
							throw runtimeError(frame, nat.name() + " expects " + (nat.minArgs() == nat.maxArgs()
									? nat.minArgs() + " argument(s)"
									: nat.minArgs() + " to " + nat.maxArgs() + " argument(s)") + ", got " + argc);
						}
						Object[] args = new Object[argc];
						for (int i = argc - 1; i >= 0; i--) args[i] = pop();
						pop(); // the callee
						Object result = nat.fn().call(this, args);
						if (result == BLOCK) {
							status = Status.BLOCKED;
							return status;
						}
						push(result);
					} else {
						throw runtimeError(frame, Values.typeName(callee) + " is not callable");
					}
				}
				case Op.RETURN -> {
					Object result = pop();
					Frame finished = frames.removeLast();
					sp = finished.base;
					if (frames.isEmpty()) {
						status = Status.DONE;
						return status;
					}
					push(result);
					frame = frames.getLast();
					chunk = frame.fn.chunk;
				}
				default -> throw new ScriptError(0, "internal error: bad opcode " + op);
			}
		}
		return status; // gas exhausted, still RUNNING
	}

	private Object indexGet(Frame frame, Object target, Object index) {
		if (target instanceof ArrayList<?> list) {
			return list.get(listIndex(frame, list.size(), index));
		}
		if (target instanceof String s) {
			int i = listIndex(frame, s.length(), index);
			return String.valueOf(s.charAt(i));
		}
		throw runtimeError(frame, "cannot index " + Values.typeName(target));
	}

	private int listIndex(Frame frame, int size, Object index) {
		if (!(index instanceof Double d)) {
			throw runtimeError(frame, "index must be a number, not " + Values.typeName(index));
		}
		int i = (int) Math.floor(d);
		if (i < 0) i += size; // negative indices count from the end
		if (i < 0 || i >= size) {
			throw runtimeError(frame, "index " + (int) Math.floor(d) + " out of range (length " + size + ")");
		}
		return i;
	}

	public static boolean truthy(Object value) {
		return value != null && value != Boolean.FALSE;
	}

	private static boolean valuesEqual(Object a, Object b) {
		if (a == null) return b == null;
		return a.equals(b);
	}

	private double number(Frame frame, Object value) {
		if (value instanceof Double d) return d;
		throw runtimeError(frame, "expected a number, got " + Values.typeName(value));
	}

	private ScriptError runtimeError(Frame frame, String message) {
		int index = Math.max(0, Math.min(frame.ip - 1, frame.fn.chunk.size() - 1));
		return new ScriptError(frame.fn.chunk.line(index), message);
	}

	private void push(Object value) {
		if (sp >= STACK_MAX) throw new ScriptError(0, "stack overflow");
		stack[sp++] = value;
	}

	private Object pop() {
		Object value = stack[--sp];
		stack[sp] = null;
		return value;
	}

	private Object peek(int distance) {
		return stack[sp - 1 - distance];
	}
}
