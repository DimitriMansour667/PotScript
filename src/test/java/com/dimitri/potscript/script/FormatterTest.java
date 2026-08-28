package com.dimitri.potscript.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FormatterTest {

	/** Formatting is only useful if you can lean on the button twice. */
	private static void assertIdempotent(String source) {
		String once = Formatter.format(source);
		assertEquals(once, Formatter.format(once), "format is not idempotent for:\n" + source);
	}

	private static void assertFormats(String source, String expected) {
		assertEquals(expected, Formatter.format(source));
		assertIdempotent(source);
	}

	@Test
	void spacesOutOperatorsAndCommas() {
		assertFormats("let x=1+2*3", "let x = 1 + 2 * 3");
		assertFormats("print( \"a\" ,\"b\" )", "print(\"a\", \"b\")");
		assertFormats("if x>=2 and y!=3 { }", "if x >= 2 and y != 3 { }");
	}

	@Test
	void hugsCallParensAndIndexBrackets() {
		assertFormats("let v = inv_get (\"up\", 0)", "let v = inv_get(\"up\", 0)");
		assertFormats("let a = l [0]", "let a = l[0]");
		assertFormats("let l = [1,2]", "let l = [1, 2]");
	}

	@Test
	void distinguishesUnaryFromBinaryMinus() {
		assertFormats("let x = - 1", "let x = -1");
		assertFormats("let x = a-b", "let x = a - b");
		assertFormats("print(-1, a - 1)", "print(-1, a - 1)");
		assertFormats("let x = (-1) - -2", "let x = (-1) - -2");
		assertFormats("return -x", "return -x");
	}

	@Test
	void semicolonsBecomeLineBreaks() {
		assertFormats("let a = 1; let b = 2", "let a = 1\nlet b = 2");
	}

	@Test
	void indentsBlocksByFourSpaces() {
		assertFormats("""
				fn add(a,b){
				return a+b
				}""", """
				fn add(a, b) {
				    return a + b
				}""");
	}

	@Test
	void indentsNestedBlocks() {
		assertFormats("""
				while true {
				if x > 1 {
				print(x)
				} else {
				break
				}
				}""", """
				while true {
				    if x > 1 {
				        print(x)
				    } else {
				        break
				    }
				}""");
	}

	@Test
	void keepsOneLinerBlocksOnOneLine() {
		assertFormats("if x { print(1) }", "if x { print(1) }");
	}

	@Test
	void indentsContinuationLinesOneExtraLevel() {
		assertFormats("""
				let l = [
				1,
				2
				]""", """
				let l = [
				    1,
				    2
				]""");
	}

	@Test
	void indentsContinuationLinesInsideBlocks() {
		assertFormats("""
				fn f() {
				print(
				"a",
				"b"
				)
				}""", """
				fn f() {
				    print(
				        "a",
				        "b"
				    )
				}""");
	}

	@Test
	void keepsCommentsOnTheirLine() {
		assertFormats("""
				# a leading note
				let x = 1""", """
				# a leading note
				let x = 1""");
	}

	@Test
	void setsOffTrailingCommentsByTwoSpaces() {
		assertFormats("let x = 1 # why", "let x = 1  # why");
		assertFormats("let x = 1     # why", "let x = 1  # why");
	}

	@Test
	void indentsCommentOnlyLinesWithTheirBlock() {
		assertFormats("""
				fn f() {
				# inside
				return 1
				}""", """
				fn f() {
				    # inside
				    return 1
				}""");
	}

	@Test
	void collapsesBlankLinesAndTrimsTheEdges() {
		assertFormats("\n\n\nlet a = 1\n\n\n\nlet b = 2\n\n\n", "let a = 1\n\nlet b = 2");
	}

	@Test
	void preservesStringSpellingIncludingEscapes() {
		assertFormats("print(\"a\\nb\\\"c\")", "print(\"a\\nb\\\"c\")");
		assertFormats("print( \"  spaced  \" )", "print(\"  spaced  \")");
	}

	@Test
	void formatsCodeThatDoesNotCompile() {
		// Lexes fine, parses to nothing useful; the formatter must not care.
		assertFormats("let = = ", "let = =");
		// Only a call hugs its paren, so the keyword keeps its space.
		assertFormats("fn(", "fn (");
		assertIdempotent("if { } else else {");
	}

	@Test
	void returnsSourceUnchangedWhenItDoesNotLex() {
		String unterminated = "print(\"oops)";
		assertSame(unterminated, Formatter.format(unterminated));

		String badChar = "let x = 1 @ 2";
		assertSame(badChar, Formatter.format(badChar));

		String badEscape = "print(\"a\\qb\")";
		assertSame(badEscape, Formatter.format(badEscape));
	}

	@Test
	void handlesEmptyAndBlankSource() {
		assertFormats("", "");
		assertFormats("   \n\t\n  ", "");
	}

	@Test
	void isIdempotentOnAMessyRealisticProgram() {
		assertIdempotent("""
				sethost( "door" );;
				# main loop
				while true{
				let msg=recv()
				  let from=msg[ 0 ]
				let body = msg[1]
				    if body=="open"{rs_set("up",15);sleep( 40 )
				rs_set( "up" ,0 )}else if body == "ping" { send(from,[ "pong",gametime() ]) }
				}
				""");
	}
}
