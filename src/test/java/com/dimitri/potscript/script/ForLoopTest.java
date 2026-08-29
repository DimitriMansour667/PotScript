package com.dimitri.potscript.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 'for x in iterable' loop, end to end through the compiler and VM. */
class ForLoopTest {

	private static Vm run(String source) {
		Vm vm = new Vm(Compiler.compile(source));
		Vm.Status status = vm.run(100_000);
		assertEquals(Vm.Status.DONE, status, () -> "program did not finish: " + vm.errorMessage());
		return vm;
	}

	@Test
	void sumsAList() {
		Vm vm = run("""
				let total = 0
				for x in [1, 2, 3] { total = total + x }
				""");
		assertEquals(6.0, vm.globals.get("total"));
	}

	@Test
	void iteratesAStringByCharacter() {
		Vm vm = run("""
				let out = ""
				for c in "abc" { out = out + c + "." }
				""");
		assertEquals("a.b.c.", vm.globals.get("out"));
	}

	@Test
	void emptyListBodyNeverRuns() {
		Vm vm = run("""
				let ran = false
				for x in [] { ran = true }
				""");
		assertEquals(Boolean.FALSE, vm.globals.get("ran"));
	}

	@Test
	void breakAndContinue() {
		Vm vm = run("""
				let total = 0
				for x in [1, 2, 3, 4, 5] {
				    if x == 2 { continue }
				    if x == 5 { break }
				    total = total + x
				}
				""");
		assertEquals(8.0, vm.globals.get("total"));
	}

	@Test
	void nestedLoops() {
		Vm vm = run("""
				let n = 0
				for a in [1, 2] {
				    for b in [10, 20] { n = n + a * b }
				}
				""");
		assertEquals(90.0, vm.globals.get("n"));
	}

	@Test
	void worksInsideAFunction() {
		Vm vm = run("""
				fn total(items) {
				    let sum = 0
				    for x in items { sum = sum + x }
				    return sum
				}
				let result = total([4, 5, 6])
				""");
		assertEquals(15.0, vm.globals.get("result"));
	}

	@Test
	void loopVariableShadowsNothingOutside() {
		Vm vm = run("""
				let x = "outer"
				for x in [1] { }
				let after = x
				""");
		assertEquals("outer", vm.globals.get("after"));
	}

	@Test
	void lengthIsRecheckedWhenTheBodyShrinksTheList() {
		Vm vm = new Vm(Compiler.compile("""
				let l = [1, 2, 3, 4]
				let seen = 0
				for x in l {
				    seen = seen + 1
				    pop(l)
				}
				"""));
		vm.globals.put("pop", new Vm.Native("pop", 1, 1,
				(v, args) -> ((java.util.ArrayList<?>) args[0]).removeLast()));
		assertEquals(Vm.Status.DONE, vm.run(100_000), () -> "program did not finish: " + vm.errorMessage());
		// 4 elements to start; each pass removes one from the tail, so the
		// index meets the shrinking length after two passes.
		assertEquals(2.0, vm.globals.get("seen"));
	}

	@Test
	void loopingOverANumberIsARuntimeError() {
		Vm vm = new Vm(Compiler.compile("for x in 5 { }"));
		assertEquals(Vm.Status.ERROR, vm.run(100_000));
		assertTrue(vm.errorMessage().contains("list or string"), vm.errorMessage());
	}
}
