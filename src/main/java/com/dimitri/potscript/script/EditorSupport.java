package com.dimitri.potscript.script;

import com.dimitri.potscript.script.Lexer.Token;
import com.dimitri.potscript.script.Lexer.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The editor smarts behind the terminal's code view: what call the cursor is
 * sitting in, and what could be typed next.
 *
 * <p>Deliberately Minecraft-free and stateless — everything is derived from
 * the buffer and a cursor offset, so it stays correct while the program is
 * half-written and can be unit tested on a plain JVM.
 */
public final class EditorSupport {

	/** Words the lexer treats as keywords, offered by completion. */
	public static final List<String> KEYWORDS = List.of(
			"let", "fn", "if", "else", "while", "for", "in", "return", "break", "continue",
			"and", "or", "not", "true", "false", "nil"
	);

	/** Tokens that carry no meaning for a backwards scan. */
	private static final Set<Type> SKIPPED = Set.of(Type.COMMENT, Type.NEWLINE, Type.EOF, Type.ERROR);

	public enum Kind {
		/** A standard library function. */
		BUILTIN,
		/** A language keyword. */
		KEYWORD,
		/** An {@code fn} declared in this buffer. */
		FUNCTION,
		/** A {@code let} declared in this buffer. */
		VARIABLE
	}

	/**
	 * The call the cursor is inside.
	 *
	 * @param activeParam index of the argument being typed; may run past the
	 *                    last parameter when too many are supplied
	 */
	public record Signature(BuiltinDocs.Builtin builtin, int activeParam) {
	}

	public record Completion(String name, Kind kind, String detail) {
	}

	private EditorSupport() {
	}

	/**
	 * The builtin call enclosing {@code cursor}, or null. Scans backwards for
	 * an unclosed {@code (} with an identifier in front of it, counting the
	 * commas in between to find the active argument.
	 */
	public static Signature signatureAt(String source, int cursor) {
		List<Token> before = tokensBefore(source, cursor);

		int depth = 0;
		int commas = 0;
		for (int i = before.size() - 1; i >= 0; i--) {
			Type type = before.get(i).type();
			if (type == Type.RPAREN || type == Type.RBRACKET) {
				depth++;
			} else if (type == Type.LBRACKET) {
				// An unclosed '[' is a list literal being typed. It may still be
				// an argument, so keep looking outwards — but its commas
				// separate elements, not arguments.
				if (depth == 0) commas = 0;
				else depth--;
			} else if (type == Type.LBRACE || type == Type.RBRACE) {
				// A block boundary: whatever call we were in has been left.
				if (depth == 0) return null;
			} else if (type == Type.COMMA) {
				if (depth == 0) commas++;
			} else if (type == Type.LPAREN) {
				if (depth > 0) {
					depth--;
				} else if (i > 0 && before.get(i - 1).type() == Type.IDENT) {
					BuiltinDocs.Builtin builtin = BuiltinDocs.get(before.get(i - 1).text());
					return builtin == null ? null : new Signature(builtin, commas);
				} else {
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * True when the caret is inside a string, a comment, or the unlexable tail
	 * of a half-typed one. Prose is not code: the words in there should not be
	 * completed against the standard library.
	 */
	public static boolean inLiteralOrComment(String source, int cursor) {
		for (Token token : Lexer.tokenizeForEditor(source)) {
			if (token.start() >= cursor) break;
			switch (token.type()) {
				// A closed string ends at its quote, so its end is outside it.
				case STRING -> {
					if (cursor < token.end()) return true;
				}
				// A comment runs to the end of its line.
				case COMMENT -> {
					if (cursor <= token.end()) return true;
				}
				// A lexer error swallows everything after it — usually an
				// unterminated string. Only the line that opened it is really
				// prose; the lines below are code the author still wants help
				// with, typo or not.
				case ERROR -> {
					if (cursor <= token.end()
							&& source.lastIndexOf('\n', cursor - 1) < token.start()) {
						return true;
					}
				}
				default -> {
				}
			}
		}
		return false;
	}

	/** The identifier being typed immediately before {@code cursor}, possibly empty. */
	public static String prefixAt(String source, int cursor) {
		int end = Math.clamp(cursor, 0, source.length());
		int start = end;
		while (start > 0 && isIdentChar(source.charAt(start - 1))) start--;
		// A prefix cannot start with a digit; that would be a number literal.
		if (start < end && Character.isDigit(source.charAt(start))) return "";
		return source.substring(start, end);
	}

	/**
	 * Candidates for the identifier prefix at {@code cursor}: the standard
	 * library, the keywords, and the names this buffer declares. Sorted so
	 * exact prefix matches on the buffer's own names come first.
	 */
	public static List<Completion> completionsAt(String source, int cursor) {
		if (inLiteralOrComment(source, cursor)) return List.of();

		String prefix = prefixAt(source, cursor);
		List<Completion> matches = new ArrayList<>();

		for (Completion candidate : declarations(source, cursor).values()) {
			if (matchesPrefix(candidate.name(), prefix)) matches.add(candidate);
		}
		for (BuiltinDocs.Builtin builtin : BuiltinDocs.all()) {
			if (matchesPrefix(builtin.name(), prefix)) {
				matches.add(new Completion(builtin.name(), Kind.BUILTIN, builtin.display()));
			}
		}
		for (String keyword : KEYWORDS) {
			if (matchesPrefix(keyword, prefix)) {
				matches.add(new Completion(keyword, Kind.KEYWORD, "keyword"));
			}
		}

		matches.sort(Comparator
				.comparingInt((Completion c) -> c.kind() == Kind.KEYWORD ? 1 : 0)
				.thenComparing(Completion::name));
		return matches;
	}

	/**
	 * Names declared by this buffer, keyed by name. Declarations are collected
	 * from the token stream rather than the compiler, so they keep working
	 * while the file does not parse.
	 */
	private static Map<String, Completion> declarations(String source, int cursor) {
		Map<String, Completion> found = new LinkedHashMap<>();
		List<Token> tokens = Lexer.tokenizeForEditor(source);

		for (int i = 0; i < tokens.size() - 1; i++) {
			Type type = tokens.get(i).type();
			if (type != Type.FN && type != Type.LET) continue;

			Token name = tokens.get(i + 1);
			if (name.type() != Type.IDENT) continue;
			// Don't offer the very identifier being typed as its own completion.
			if (cursor > name.start() && cursor <= name.end()) continue;

			if (type == Type.FN) {
				found.put(name.text(), new Completion(name.text(), Kind.FUNCTION,
						name.text() + "(" + String.join(", ", parameters(tokens, i + 2)) + ")"));
			} else {
				found.putIfAbsent(name.text(), new Completion(name.text(), Kind.VARIABLE, "local"));
			}
		}
		return found;
	}

	/** Parameter names of an {@code fn}, given the index of its '('. */
	private static List<String> parameters(List<Token> tokens, int open) {
		List<String> params = new ArrayList<>();
		if (open >= tokens.size() || tokens.get(open).type() != Type.LPAREN) return params;
		for (int i = open + 1; i < tokens.size(); i++) {
			Type type = tokens.get(i).type();
			if (type == Type.RPAREN || type == Type.NEWLINE || type == Type.EOF) break;
			if (type == Type.IDENT) params.add(tokens.get(i).text());
		}
		return params;
	}

	private static boolean matchesPrefix(String name, String prefix) {
		return prefix.isEmpty() || (name.startsWith(prefix) && !name.equals(prefix));
	}

	/** Meaningful tokens that end at or before the cursor. */
	private static List<Token> tokensBefore(String source, int cursor) {
		List<Token> before = new ArrayList<>();
		for (Token token : Lexer.tokenizeForEditor(source)) {
			if (token.end() > cursor) break;
			if (!SKIPPED.contains(token.type())) before.add(token);
		}
		return before;
	}

	private static boolean isIdentChar(char c) {
		return c == '_' || Character.isLetterOrDigit(c);
	}
}
