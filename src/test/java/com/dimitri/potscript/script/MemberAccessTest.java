package com.dimitri.potscript.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The '.' operator end to end through the compiler and VM, on a stub object. */
class MemberAccessTest {

	/** A Minecraft-free ScriptObject: enough surface to exercise the VM. */
	private static final class StubObject implements ScriptObject {
		@Override
		public String typeName() {
			return "stub";
		}

		@Override
		public Object member(String name) {
			return switch (name) {
				case "value" -> 42.0;
				case "twice" -> new Vm.Native("stub.twice", 1, 1, (v, a) -> (Double) a[0] * 2);
				default -> throw new ScriptError(0, "a stub has no method '" + name + "'");
			};
		}

		@Override
		public String describe() {
			return "<stub>";
		}
	}

	private static Vm run(String source) {
		Vm vm = new Vm(Compiler.compile(source));
		// A null pot is enough: type() and str() never touch the world.
		Builtins.install(vm, null);
		vm.globals.put("obj", new StubObject());
		Vm.Status status = vm.run(100_000);
		assertEquals(Vm.Status.DONE, status, () -> "program did not finish: " + vm.errorMessage());
		return vm;
	}

	private static String runToError(String source) {
		Vm vm = new Vm(Compiler.compile(source));
		vm.globals.put("obj", new StubObject());
		assertEquals(Vm.Status.ERROR, vm.run(100_000));
		return vm.errorMessage();
	}

	@Test
	void readsAMember() {
		assertEquals(42.0, run("let x = obj.value").globals.get("x"));
	}

	@Test
	void callsAMethod() {
		assertEquals(42.0, run("let x = obj.twice(21)").globals.get("x"));
	}

	@Test
	void methodCallsChainWithExpressions() {
		assertEquals(85.0, run("let x = obj.twice(obj.twice(21)) + 1").globals.get("x"));
	}

	@Test
	void aBoundMethodIsAValue() {
		assertEquals(10.0, run("let f = obj.twice\nlet x = f(5)").globals.get("x"));
	}

	@Test
	void typeAndStringifySeeTheObject() {
		Vm vm = run("let t = type(obj)\nlet s = str(obj)");
		assertEquals("stub", vm.globals.get("t"));
		assertEquals("<stub>", vm.globals.get("s"));
	}

	@Test
	void unknownMemberErrors() {
		assertTrue(runToError("obj.nope").contains("no method 'nope'"));
	}

	@Test
	void memberAccessOnANonObjectErrors() {
		assertTrue(runToError("let x = 1\nx.foo").contains("'.foo'"));
		assertTrue(runToError("\"s\".foo").contains("string"));
	}

	@Test
	void wrongArityIsCaughtByTheVm() {
		assertTrue(runToError("obj.twice()").contains("stub.twice expects 1"));
	}

	@Test
	void dotsInNumbersStillLex() {
		assertEquals(3.0, run("let x = 1.5 + 1.5").globals.get("x"));
	}

	@Test
	void memberIsNotAnAssignmentTarget() {
		try {
			Compiler.compile("obj.value = 1");
		} catch (ScriptError e) {
			assertTrue(e.getMessage().contains("invalid assignment target"));
			return;
		}
		throw new AssertionError("assigning to a member should not compile");
	}
}
