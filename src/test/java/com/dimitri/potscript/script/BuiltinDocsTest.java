package com.dimitri.potscript.script;

import com.dimitri.potscript.script.BuiltinDocs.Builtin;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinDocsTest {

	@Test
	void documentsEveryBuiltin() {
		assertEquals(68, BuiltinDocs.all().size());
	}

	@Test
	void parameterNamesMatchTheDeclaredArity() {
		for (Builtin builtin : BuiltinDocs.all()) {
			if (builtin.isVariadic()) continue;
			assertEquals(builtin.maxArgs(), builtin.params().size(),
					builtin.name() + " names " + builtin.params() + " but takes up to " + builtin.maxArgs());
		}
	}

	@Test
	void bracketsMarkExactlyTheOptionalParameters() {
		for (Builtin builtin : BuiltinDocs.all()) {
			if (builtin.isVariadic()) continue;
			boolean hasOptional = builtin.maxArgs() > builtin.minArgs();
			assertEquals(hasOptional, builtin.paramSpec().contains("["),
					builtin.name() + " brackets disagree with its min/max args: " + builtin.signature());
		}
	}

	@Test
	void everyEntryIsUsableAsAHint() {
		Set<String> seen = new HashSet<>();
		for (Builtin builtin : BuiltinDocs.all()) {
			assertTrue(seen.add(builtin.name()), "duplicate entry for " + builtin.name());
			assertFalse(builtin.returns().isBlank(), builtin.name() + " has no return type");
			assertFalse(builtin.doc().isBlank(), builtin.name() + " has no description");
			assertTrue(builtin.doc().endsWith("."), builtin.name() + " description is not a sentence");
			assertNotNull(BuiltinDocs.get(builtin.name()));
			assertTrue(BuiltinDocs.isBuiltin(builtin.name()));
		}
	}

	/**
	 * The real registrations, checked against the table. Registering closes
	 * over the block entity but never calls it, so a null pot is enough to get
	 * all 68 natives installed without a world.
	 */
	@Test
	void tableAgreesWithTheActualRegistrations() {
		Vm vm = new Vm(null);
		Builtins.install(vm, null);

		assertEquals(BuiltinDocs.all().size(), vm.globals.size());
		for (Builtin builtin : BuiltinDocs.all()) {
			Object global = vm.globals.get(builtin.name());
			assertTrue(global instanceof Vm.Native, builtin.name() + " is not registered");
			Vm.Native native_ = (Vm.Native) global;
			assertEquals(builtin.minArgs(), native_.minArgs(), builtin.name() + " min args");
			assertEquals(builtin.maxArgs(), native_.maxArgs(), builtin.name() + " max args");
		}
	}

	@Test
	void signaturesReadTheWayTheWikiWritesThem() {
		assertEquals("sub(s, from, [to]) -> string", BuiltinDocs.get("sub").display());
		assertEquals("range([from,] to) -> list", BuiltinDocs.get("range").display());
		assertEquals("rs_reset() -> nil", BuiltinDocs.get("rs_reset").display());
		assertEquals("print(...) -> nil", BuiltinDocs.get("print").display());
		assertEquals(java.util.List.of("from", "to"), BuiltinDocs.get("range").params());
		assertEquals(java.util.List.of("from", "to", "item", "max"), BuiltinDocs.get("inv_move").params());
	}
}
