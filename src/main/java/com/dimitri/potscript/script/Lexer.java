package com.dimitri.potscript.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tokenizer for PotScript. Newlines act as statement terminators, but only
 * outside parentheses/brackets so expressions can span lines inside calls
 * and list literals.
 *
 * <p>Editor tooling needs things the compiler does not: comments, every
 * physical line break, and the source spelling of each token. Those are
 * available through {@link #keepTrivia()}, which leaves the compiler's token
 * stream untouched.
 */
public final class Lexer {

	public enum Type {
		LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
		COMMA, DOT, PLUS, MINUS, STAR, SLASH, PERCENT,
		EQ, EQEQ, BANGEQ, LT, LTEQ, GT, GTEQ,
		IDENT, NUMBER, STRING,
		LET, FN, IF, ELSE, WHILE, FOR, IN, RETURN, BREAK, CONTINUE,
		AND, OR, NOT, TRUE, FALSE, NIL,
		COMMENT, NEWLINE, EOF,
		/** Only produced by {@link #tokenizeForEditor}; never reaches the compiler. */
		ERROR
	}

	/**
	 * A token and where it came from. {@code text} is always the raw source
	 * spelling — for strings that includes the quotes and the escapes as
	 * written, so a formatter can reproduce them; the unescaped value is in
	 * {@code literal}.
	 *
	 * <p>{@code start} and {@code end} are offsets into the source. Synthetic
	 * tokens (the terminator and {@code EOF} appended by {@link #tokenize()})
	 * are empty, so {@code start == end}.
	 */
	public record Token(Type type, String text, Object literal, int line, int col, int start, int end) {

		public int length() {
			return end - start;
		}

		/** False for the synthetic terminator and EOF, which have no source text. */
		public boolean isReal() {
			return end > start;
		}
	}

	private static final Map<String, Type> KEYWORDS = Map.ofEntries(
			Map.entry("let", Type.LET),
			Map.entry("fn", Type.FN),
			Map.entry("if", Type.IF),
			Map.entry("else", Type.ELSE),
			Map.entry("while", Type.WHILE),
			Map.entry("for", Type.FOR),
			Map.entry("in", Type.IN),
			Map.entry("return", Type.RETURN),
			Map.entry("break", Type.BREAK),
			Map.entry("continue", Type.CONTINUE),
			Map.entry("and", Type.AND),
			Map.entry("or", Type.OR),
			Map.entry("not", Type.NOT),
			Map.entry("true", Type.TRUE),
			Map.entry("false", Type.FALSE),
			Map.entry("nil", Type.NIL)
	);

	private final String source;
	private final List<Token> tokens = new ArrayList<>();
	private int start;
	private int current;
	private int line = 1;
	private int lineStart;
	private int groupDepth;
	private boolean keepTrivia;

	public Lexer(String source) {
		this.source = source;
	}

	/**
	 * Emit comments and every physical newline, including the ones inside
	 * parentheses and brackets that the compiler does not want to see.
	 */
	public Lexer keepTrivia() {
		this.keepTrivia = true;
		return this;
	}

	/**
	 * Tokenize for editor tooling: trivia is kept and nothing is thrown. Half
	 * a string literal is the normal state of a line you are still typing, so
	 * a lexical error simply ends the stream with one {@link Type#ERROR} token
	 * covering the rest of the source.
	 */
	public static List<Token> tokenizeForEditor(String source) {
		Lexer lexer = new Lexer(source).keepTrivia();
		try {
			return lexer.tokenize();
		} catch (ScriptError e) {
			lexer.current = source.length();
			lexer.add(Type.ERROR);
			lexer.start = source.length();
			lexer.add(Type.NEWLINE);
			lexer.add(Type.EOF);
			return lexer.tokens;
		}
	}

	public List<Token> tokenize() {
		while (!atEnd()) {
			start = current;
			scanToken();
		}
		start = current;
		add(Type.NEWLINE);
		add(Type.EOF);
		return tokens;
	}

	private void scanToken() {
		char c = advance();
		switch (c) {
			case '(' -> { groupDepth++; add(Type.LPAREN); }
			case ')' -> { groupDepth--; add(Type.RPAREN); }
			case '[' -> { groupDepth++; add(Type.LBRACKET); }
			case ']' -> { groupDepth--; add(Type.RBRACKET); }
			case '{' -> add(Type.LBRACE);
			case '}' -> add(Type.RBRACE);
			case ',' -> add(Type.COMMA);
			case '.' -> add(Type.DOT);
			case '+' -> add(Type.PLUS);
			case '-' -> add(Type.MINUS);
			case '*' -> add(Type.STAR);
			case '/' -> add(Type.SLASH);
			case '%' -> add(Type.PERCENT);
			case ';' -> add(Type.NEWLINE);
			case '=' -> add(match('=') ? Type.EQEQ : Type.EQ);
			case '<' -> add(match('=') ? Type.LTEQ : Type.LT);
			case '>' -> add(match('=') ? Type.GTEQ : Type.GT);
			case '!' -> {
				if (match('=')) add(Type.BANGEQ);
				else throw new ScriptError(line, "unexpected '!' (use 'not' or '!=')");
			}
			case '#' -> {
				while (!atEnd() && peek() != '\n') advance();
				if (keepTrivia) add(Type.COMMENT);
			}
			case ' ', '\r', '\t' -> {
			}
			case '\n' -> {
				if (keepTrivia || groupDepth <= 0) add(Type.NEWLINE);
				line++;
				lineStart = current;
			}
			case '"' -> string();
			default -> {
				if (isDigit(c)) number();
				else if (isAlpha(c)) identifier();
				else throw new ScriptError(line, "unexpected character '" + c + "'");
			}
		}
	}

	private void string() {
		StringBuilder sb = new StringBuilder();
		while (!atEnd() && peek() != '"') {
			char c = advance();
			if (c == '\n') {
				throw new ScriptError(line, "unterminated string");
			}
			if (c == '\\' && !atEnd()) {
				char e = advance();
				switch (e) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case '"' -> sb.append('"');
					case '\\' -> sb.append('\\');
					default -> throw new ScriptError(line, "unknown escape '\\" + e + "'");
				}
			} else {
				sb.append(c);
			}
		}
		if (atEnd()) throw new ScriptError(line, "unterminated string");
		advance(); // closing quote
		add(Type.STRING, sb.toString());
	}

	private void number() {
		while (isDigit(peek())) advance();
		if (peek() == '.' && isDigit(peekNext())) {
			advance();
			while (isDigit(peek())) advance();
		}
		add(Type.NUMBER, Double.parseDouble(source.substring(start, current)));
	}

	private void identifier() {
		while (isAlpha(peek()) || isDigit(peek())) advance();
		add(KEYWORDS.getOrDefault(source.substring(start, current), Type.IDENT));
	}

	private void add(Type type) {
		add(type, null);
	}

	private void add(Type type, Object literal) {
		tokens.add(new Token(type, source.substring(start, current), literal,
				line, start - lineStart + 1, start, current));
	}

	private boolean match(char expected) {
		if (atEnd() || source.charAt(current) != expected) return false;
		current++;
		return true;
	}

	private char advance() {
		return source.charAt(current++);
	}

	private char peek() {
		return atEnd() ? '\0' : source.charAt(current);
	}

	private char peekNext() {
		return current + 1 >= source.length() ? '\0' : source.charAt(current + 1);
	}

	private boolean atEnd() {
		return current >= source.length();
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isAlpha(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
	}
}
