package com.dimitri.potscript.script;

import com.dimitri.potscript.script.EditorSupport.Completion;
import com.dimitri.potscript.script.EditorSupport.Kind;
import com.dimitri.potscript.script.EditorSupport.Signature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorSupportTest {

	/** Cursor marked with '|' in the source, for readability. */
	private static Signature signatureAt(String marked) {
		return EditorSupport.signatureAt(marked.replace("|", ""), marked.indexOf('|'));
	}

	private static List<String> completionNamesAt(String marked) {
		return EditorSupport.completionsAt(marked.replace("|", ""), marked.indexOf('|'))
				.stream().map(Completion::name).toList();
	}

	// ------------------------------------------------------------- signatures

	@Test
	void findsTheEnclosingBuiltinCall() {
		Signature sig = signatureAt("let x = sub(|");
		assertNotNull(sig);
		assertEquals("sub", sig.builtin().name());
		assertEquals(0, sig.activeParam());
	}

	@Test
	void countsCommasForTheActiveParameter() {
		assertEquals(0, signatureAt("sub(\"abc\"|").activeParam());
		assertEquals(1, signatureAt("sub(\"abc\", |").activeParam());
		assertEquals(2, signatureAt("sub(\"abc\", 1, |").activeParam());
		// Too many arguments still reports a position rather than giving up.
		assertEquals(3, signatureAt("sub(\"abc\", 1, 2, |").activeParam());
	}

	@Test
	void picksTheInnermostCall() {
		Signature sig = signatureAt("print(join(l, |");
		assertEquals("join", sig.builtin().name());
		assertEquals(1, sig.activeParam());
	}

	@Test
	void returnsToTheOuterCallOnceTheInnerOneCloses() {
		Signature sig = signatureAt("send(host, join(l, \",\")|");
		assertEquals("send", sig.builtin().name());
		assertEquals(1, sig.activeParam());
	}

	@Test
	void ignoresCommasNestedInsideBracketsAndParens() {
		assertEquals(1, signatureAt("say(text, max(1, 2)|").activeParam());
	}

	@Test
	void keepsHintingTheCallWhileAListArgumentIsTypedIntoIt() {
		Signature sig = signatureAt("send(host, [1, 2, 3|");
		assertEquals("send", sig.builtin().name());
		assertEquals(1, sig.activeParam(), "the list's own commas must not advance the argument");
	}

	@Test
	void hasNoSignatureOutsideACall() {
		assertNull(signatureAt("let x = 1|"));
		assertNull(signatureAt("|"));
		assertNull(signatureAt("print(\"a\")|"));
	}

	@Test
	void hasNoSignatureInsideAListLiteral() {
		assertNull(signatureAt("let l = [1, |"));
	}

	@Test
	void hasNoSignatureAcrossABlockBoundary() {
		assertNull(signatureAt("if x {|"));
		assertNull(signatureAt("fn f() {\n    |"));
	}

	@Test
	void hasNoSignatureForANonBuiltinCall() {
		assertNull(signatureAt("myfunc(|"));
		assertNull(signatureAt("(|"));
	}

	@Test
	void survivesAnUnterminatedStringEarlierInTheLine() {
		// The lexer would throw here; editor tooling must not.
		Signature sig = signatureAt("print(\"a\")\nsub(|");
		assertEquals("sub", sig.builtin().name());
	}

	@Test
	void survivesAnUnterminatedStringBeingTyped() {
		Signature sig = signatureAt("say(\"hello|");
		assertEquals("say", sig.builtin().name());
		assertEquals(0, sig.activeParam());
	}

	// ------------------------------------------------------------- prefixes

	@Test
	void readsTheIdentifierPrefixAtTheCursor() {
		assertEquals("inv_", EditorSupport.prefixAt("let x = inv_", 12));
		assertEquals("", EditorSupport.prefixAt("let x = ", 8));
		assertEquals("x", EditorSupport.prefixAt("let x = 1", 5));
		// Mid-identifier: only what is behind the cursor counts.
		assertEquals("in", EditorSupport.prefixAt("inv_get", 2));
	}

	@Test
	void doesNotTreatNumbersAsPrefixes() {
		assertEquals("", EditorSupport.prefixAt("let x = 12", 10));
		assertEquals("x12", EditorSupport.prefixAt("let x12 = 1", 7));
	}

	// ------------------------------------------------------------- completion

	@Test
	void completesBuiltinsByPrefix() {
		List<String> names = completionNamesAt("let x = inv_|");
		assertEquals(List.of("inv_count", "inv_find", "inv_get", "inv_move", "inv_size"), names);
	}

	@Test
	void completesKeywordsAfterTheBuiltins() {
		List<String> names = completionNamesAt("re|");
		assertTrue(names.containsAll(List.of("read", "recv", "remove", "return")), names.toString());
		assertTrue(names.indexOf("return") > names.indexOf("read"), "keywords should sort last: " + names);
	}

	@Test
	void completesNamesDeclaredInTheBuffer() {
		String source = "fn helper(a, b) {\n    return a\n}\nlet helpful = 1\nlet x = help|";
		List<Completion> found = EditorSupport.completionsAt(source.replace("|", ""), source.indexOf('|'));

		assertEquals(List.of("helper", "helpful"), found.stream().map(Completion::name).toList());
		assertEquals(Kind.FUNCTION, found.getFirst().kind());
		assertEquals("helper(a, b)", found.getFirst().detail());
		assertEquals(Kind.VARIABLE, found.get(1).kind());
	}

	@Test
	void doesNotOfferTheDeclarationBeingTyped() {
		assertTrue(completionNamesAt("let counter|").isEmpty());
		assertTrue(completionNamesAt("fn counter|").isEmpty());
	}

	@Test
	void doesNotOfferAnExactMatchOfWhatIsAlreadyTyped() {
		assertTrue(completionNamesAt("print|").stream().noneMatch("print"::equals));
	}

	@Test
	void doesNotCompleteInsideStringsOrComments() {
		assertTrue(completionNamesAt("say(\"hello re|").isEmpty(), "inside an unterminated string");
		assertTrue(completionNamesAt("let s = \"a re|b\"").isEmpty(), "inside a closed string");
		assertTrue(completionNamesAt("let x = 1  # re|").isEmpty(), "inside a comment");
		assertFalse(completionNamesAt("# a note\nre|").isEmpty(), "the line after a comment is code");
	}

	@Test
	void resumesCompletingOnTheLinesBelowAnUnterminatedString() {
		// The lexer cannot see past the stray quote, but the lines under it are
		// still code the author is writing.
		assertTrue(completionNamesAt("let s = \"oops re|").isEmpty());
		assertFalse(completionNamesAt("let s = \"oops\nre|").isEmpty());
	}

	@Test
	void knowsWhereLiteralsAndCommentsEnd() {
		// The caret just past a closing quote is back in code.
		assertFalse(EditorSupport.inLiteralOrComment("let s = \"ab\"", 12));
		assertTrue(EditorSupport.inLiteralOrComment("let s = \"ab\"", 10));
		// A comment owns the rest of its line, but not the next one.
		assertTrue(EditorSupport.inLiteralOrComment("# note\nx", 6));
		assertFalse(EditorSupport.inLiteralOrComment("# note\nx", 8));
	}

	@Test
	void stillHintsSignaturesWhileAStringArgumentIsTyped() {
		// Completion is suppressed in there, but the call's shape still helps.
		assertEquals("say", signatureAt("say(\"hello re|").builtin().name());
	}

	@Test
	void completesInsideAHalfWrittenProgram() {
		List<String> names = completionNamesAt("while true {\n    let s = \"unterminated\n    rs_|");
		assertEquals(List.of("rs_get", "rs_pulse", "rs_reset", "rs_set", "rs_wait"), names);
	}
}
