package com.example.termnet.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tokenizer for PotScript. Newlines act as statement terminators, but only
 * outside parentheses/brackets so expressions can span lines inside calls
 * and list literals.
 */
public final class Lexer {

	public enum Type {
		LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
		COMMA, PLUS, MINUS, STAR, SLASH, PERCENT,
		EQ, EQEQ, BANGEQ, LT, LTEQ, GT, GTEQ,
		IDENT, NUMBER, STRING,
		LET, FN, IF, ELSE, WHILE, RETURN, BREAK, CONTINUE,
		AND, OR, NOT, TRUE, FALSE, NIL,
		NEWLINE, EOF
	}

	public record Token(Type type, String text, Object literal, int line) {
	}

	private static final Map<String, Type> KEYWORDS = Map.ofEntries(
			Map.entry("let", Type.LET),
			Map.entry("fn", Type.FN),
			Map.entry("if", Type.IF),
			Map.entry("else", Type.ELSE),
			Map.entry("while", Type.WHILE),
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
	private int groupDepth;

	public Lexer(String source) {
		this.source = source;
	}

	public List<Token> tokenize() {
		while (!atEnd()) {
			start = current;
			scanToken();
		}
		tokens.add(new Token(Type.NEWLINE, "\n", null, line));
		tokens.add(new Token(Type.EOF, "", null, line));
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
			}
			case ' ', '\r', '\t' -> {
			}
			case '\n' -> {
				if (groupDepth <= 0) add(Type.NEWLINE);
				line++;
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
		tokens.add(new Token(Type.STRING, sb.toString(), sb.toString(), line));
	}

	private void number() {
		while (isDigit(peek())) advance();
		if (peek() == '.' && isDigit(peekNext())) {
			advance();
			while (isDigit(peek())) advance();
		}
		String text = source.substring(start, current);
		tokens.add(new Token(Type.NUMBER, text, Double.parseDouble(text), line));
	}

	private void identifier() {
		while (isAlpha(peek()) || isDigit(peek())) advance();
		String text = source.substring(start, current);
		Type type = KEYWORDS.getOrDefault(text, Type.IDENT);
		add(type);
	}

	private void add(Type type) {
		tokens.add(new Token(type, source.substring(start, current), null, line));
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
