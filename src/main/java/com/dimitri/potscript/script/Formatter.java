package com.dimitri.potscript.script;

import com.dimitri.potscript.script.Lexer.Token;
import com.dimitri.potscript.script.Lexer.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reformats PotScript source.
 *
 * <p>Works off the token stream rather than an AST, so it also tidies code
 * that does not compile yet — which is most code, most of the time, in a
 * terminal you are typing into. Source that does not even lex is returned
 * unchanged.
 */
public final class Formatter {

	private static final String INDENT = "    ";

	/** Tokens after which a '-' is a negation rather than a subtraction. */
	private static final Set<Type> VALUE_ENDINGS = Set.of(
			Type.IDENT, Type.NUMBER, Type.STRING,
			Type.RPAREN, Type.RBRACKET,
			Type.TRUE, Type.FALSE, Type.NIL
	);

	private Formatter() {
	}

	public static String format(String source) {
		List<Token> tokens;
		try {
			tokens = new Lexer(source).keepTrivia().tokenize();
		} catch (ScriptError e) {
			return source;
		}

		List<String> out = new ArrayList<>();
		int braceDepth = 0;
		int groupDepth = 0;

		for (List<Token> line : splitLines(tokens)) {
			if (line.isEmpty()) {
				// Collapse runs of blank lines to one, and drop leading ones.
				if (!out.isEmpty() && !out.getLast().isEmpty()) out.add("");
				continue;
			}

			int indent = Math.max(0, braceDepth - leading(line, Type.RBRACE))
					+ (groupDepth - leadingClosers(line) > 0 ? 1 : 0);
			out.add(INDENT.repeat(indent) + render(line));

			for (Token token : line) {
				switch (token.type()) {
					case LBRACE -> braceDepth++;
					case RBRACE -> braceDepth = Math.max(0, braceDepth - 1);
					case LPAREN, LBRACKET -> groupDepth++;
					case RPAREN, RBRACKET -> groupDepth = Math.max(0, groupDepth - 1);
					default -> {
					}
				}
			}
		}

		while (!out.isEmpty() && out.getLast().isEmpty()) out.removeLast();
		return String.join("\n", out);
	}

	/**
	 * Physical lines, in author order. Both '\n' and ';' lex as NEWLINE, which
	 * is exactly the "one statement per line" rule we want.
	 */
	private static List<List<Token>> splitLines(List<Token> tokens) {
		List<List<Token>> lines = new ArrayList<>();
		List<Token> line = new ArrayList<>();
		for (Token token : tokens) {
			if (token.type() == Type.EOF) break;
			if (token.type() == Type.NEWLINE) {
				// The terminator the lexer appends has no source text, so it
				// ends nothing: the flush below picks up any final line.
				if (token.isReal()) {
					lines.add(line);
					line = new ArrayList<>();
				}
			} else {
				line.add(token);
			}
		}
		if (!line.isEmpty()) lines.add(line);
		return lines;
	}

	private static int leading(List<Token> line, Type type) {
		int n = 0;
		while (n < line.size() && line.get(n).type() == type) n++;
		return n;
	}

	/** Closers at the start of a line pop the continuation indent back off. */
	private static int leadingClosers(List<Token> line) {
		int n = 0;
		while (n < line.size()
				&& (line.get(n).type() == Type.RPAREN || line.get(n).type() == Type.RBRACKET)) {
			n++;
		}
		return n;
	}

	private static String render(List<Token> line) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < line.size(); i++) {
			Token token = line.get(i);
			Token prev = i > 0 ? line.get(i - 1) : null;
			if (prev != null) {
				// A trailing comment is set off from the code it annotates.
				sb.append(token.type() == Type.COMMENT ? "  " : spaceBetween(line, i) ? " " : "");
			}
			sb.append(token.text());
		}
		return sb.toString();
	}

	private static boolean spaceBetween(List<Token> line, int i) {
		Type prev = line.get(i - 1).type();
		Type cur = line.get(i).type();

		if (cur == Type.COMMA || cur == Type.RPAREN || cur == Type.RBRACKET) return false;
		if (prev == Type.LPAREN || prev == Type.LBRACKET) return false;

		// A call's arguments and an index hug their opener; a list literal or a
		// parenthesised group after an operator does not.
		if ((cur == Type.LPAREN || cur == Type.LBRACKET) && VALUE_ENDINGS.contains(prev)) return false;

		if (prev == Type.MINUS && isUnaryMinus(line, i - 1)) return false;
		return true;
	}

	/** A '-' negates unless something that can end a value sits in front of it. */
	private static boolean isUnaryMinus(List<Token> line, int i) {
		if (i == 0) return true;
		return !VALUE_ENDINGS.contains(line.get(i - 1).type());
	}
}
