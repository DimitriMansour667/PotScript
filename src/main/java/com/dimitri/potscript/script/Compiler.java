package com.dimitri.potscript.script;

import com.dimitri.potscript.script.Lexer.Token;
import com.dimitri.potscript.script.Lexer.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-pass compiler for PotScript: Pratt-parses the token stream and emits
 * bytecode directly. Functions may only be declared at the top level and do not
 * capture enclosing locals (they can use globals and their own locals).
 */
public final class Compiler {

	private static final int MAX_LOCALS = 256;
	private static final int MAX_PARAMS = 16;

	// Precedence levels, low to high.
	private static final int PREC_NONE = 0;
	private static final int PREC_ASSIGNMENT = 1;
	private static final int PREC_OR = 2;
	private static final int PREC_AND = 3;
	private static final int PREC_EQUALITY = 4;
	private static final int PREC_COMPARISON = 5;
	private static final int PREC_TERM = 6;
	private static final int PREC_FACTOR = 7;
	private static final int PREC_UNARY = 8;
	private static final int PREC_CALL = 9;

	private record Local(String name, int depth) {
	}

	private static final class LoopCtx {
		final int start;
		final int scopeDepth;
		final List<Integer> breakJumps = new ArrayList<>();

		LoopCtx(int start, int scopeDepth) {
			this.start = start;
			this.scopeDepth = scopeDepth;
		}
	}

	private static final class FuncCtx {
		final String name;
		final Chunk chunk = new Chunk();
		final List<Local> locals = new ArrayList<>();
		final List<LoopCtx> loops = new ArrayList<>();
		int scopeDepth;

		FuncCtx(String name, boolean isScript) {
			this.name = name;
			// Slot 0 holds the function itself in every call frame.
			locals.add(new Local("", isScript ? 0 : 1));
			this.scopeDepth = isScript ? 0 : 1;
		}
	}

	private final List<Token> tokens;
	private int current;
	private FuncCtx func;
	private final FuncCtx script;

	private Compiler(List<Token> tokens) {
		this.tokens = tokens;
		this.script = new FuncCtx("main", true);
		this.func = script;
	}

	public static ScriptFunction compile(String source) {
		Compiler compiler = new Compiler(new Lexer(source).tokenize());
		compiler.program();
		return new ScriptFunction("main", 0, compiler.script.chunk);
	}

	private void program() {
		skipNewlines();
		while (!check(Type.EOF)) {
			statement();
			skipNewlines();
		}
		emit(Op.NIL);
		emit(Op.RETURN);
	}

	// ---------------------------------------------------------------- statements

	private void statement() {
		if (match(Type.LET)) letStatement();
		else if (match(Type.FN)) fnStatement();
		else if (match(Type.IF)) ifStatement();
		else if (match(Type.WHILE)) whileStatement();
		else if (match(Type.FOR)) forStatement();
		else if (match(Type.RETURN)) returnStatement();
		else if (match(Type.BREAK)) breakStatement();
		else if (match(Type.CONTINUE)) continueStatement();
		else if (match(Type.LBRACE)) {
			beginScope();
			blockBody();
			endScope();
		} else expressionStatement();
	}

	private void letStatement() {
		Token name = consume(Type.IDENT, "expected variable name after 'let'");
		if (match(Type.EQ)) {
			expression();
		} else {
			emit(Op.NIL);
		}
		defineVariable(name);
		terminator();
	}

	private void defineVariable(Token name) {
		if (func.scopeDepth == 0) {
			emit(Op.DEFINE_GLOBAL);
			emit(constant(name.text()));
		} else {
			// Declare a local: value already sits on the stack in its slot.
			for (int i = func.locals.size() - 1; i >= 0; i--) {
				Local local = func.locals.get(i);
				if (local.depth() < func.scopeDepth) break;
				if (local.name().equals(name.text())) {
					throw error(name, "variable '" + name.text() + "' already declared in this scope");
				}
			}
			if (func.locals.size() >= MAX_LOCALS) throw error(name, "too many local variables");
			func.locals.add(new Local(name.text(), func.scopeDepth));
		}
	}

	private void fnStatement() {
		if (func != script || func.scopeDepth != 0) {
			throw error(peek(), "functions must be declared at top level");
		}
		Token name = consume(Type.IDENT, "expected function name after 'fn'");
		consume(Type.LPAREN, "expected '(' after function name");

		FuncCtx enclosing = func;
		func = new FuncCtx(name.text(), false);

		List<String> params = new ArrayList<>();
		if (!check(Type.RPAREN)) {
			do {
				Token param = consume(Type.IDENT, "expected parameter name");
				if (params.size() >= MAX_PARAMS) throw error(param, "too many parameters (max " + MAX_PARAMS + ")");
				params.add(param.text());
				func.locals.add(new Local(param.text(), 1));
			} while (match(Type.COMMA));
		}
		consume(Type.RPAREN, "expected ')' after parameters");
		consume(Type.LBRACE, "expected '{' before function body");
		blockBody();
		emit(Op.NIL);
		emit(Op.RETURN);

		ScriptFunction compiled = new ScriptFunction(name.text(), params.size(), func.chunk);
		func = enclosing;

		emit(Op.CONST);
		emit(constant(compiled));
		emit(Op.DEFINE_GLOBAL);
		emit(constant(name.text()));
		terminator();
	}

	private void ifStatement() {
		expression();
		int elseJump = emitJump(Op.JUMP_IF_FALSE);
		consume(Type.LBRACE, "expected '{' after if condition");
		beginScope();
		blockBody();
		endScope();

		// Allow 'else' on the next line: look ahead across newlines.
		int lookahead = current;
		while (tokens.get(lookahead).type() == Type.NEWLINE) lookahead++;
		if (tokens.get(lookahead).type() == Type.ELSE) {
			current = lookahead + 1;
			int endJump = emitJump(Op.JUMP);
			patchJump(elseJump);
			if (match(Type.IF)) {
				ifStatement();
			} else {
				consume(Type.LBRACE, "expected '{' or 'if' after 'else'");
				beginScope();
				blockBody();
				endScope();
			}
			patchJump(endJump);
		} else {
			patchJump(elseJump);
		}
	}

	private void whileStatement() {
		int loopStart = chunk().size();
		LoopCtx loop = new LoopCtx(loopStart, func.scopeDepth);
		func.loops.add(loop);

		expression();
		int exitJump = emitJump(Op.JUMP_IF_FALSE);
		consume(Type.LBRACE, "expected '{' after while condition");
		beginScope();
		blockBody();
		endScope();
		emit(Op.JUMP);
		emit(loopStart);
		patchJump(exitJump);

		for (int jump : loop.breakJumps) patchJump(jump);
		func.loops.removeLast();
	}

	/**
	 * {@code for x in iterable { body }} desugars to an index walk over two
	 * hidden locals (the iterable and a counter). The counter increments at the
	 * loop start so 'continue' lands on it; length is re-checked every pass, so
	 * a body that shrinks the list never reads out of range.
	 */
	private void forStatement() {
		Token name = consume(Type.IDENT, "expected a loop variable after 'for'");
		consume(Type.IN, "expected 'in' after the loop variable");

		beginScope();
		expression(); // the iterable, living in a hidden slot
		int iterSlot = hiddenLocal(name, " for-iter");
		emit(Op.CONST);
		emit(constant(-1.0)); // counter, pre-incremented each pass
		int indexSlot = hiddenLocal(name, " for-index");

		int loopStart = chunk().size();
		LoopCtx loop = new LoopCtx(loopStart, func.scopeDepth);
		func.loops.add(loop);

		// index = index + 1
		emit(Op.GET_LOCAL);
		emit(indexSlot);
		emit(Op.CONST);
		emit(constant(1.0));
		emit(Op.ADD);
		emit(Op.SET_LOCAL);
		emit(indexSlot);
		emit(Op.POP);

		// while index < len(iterable)
		emit(Op.GET_LOCAL);
		emit(indexSlot);
		emit(Op.GET_LOCAL);
		emit(iterSlot);
		emit(Op.LEN);
		emit(Op.LESS);
		int exitJump = emitJump(Op.JUMP_IF_FALSE);

		consume(Type.LBRACE, "expected '{' after the for header");
		beginScope();
		// let x = iterable[index]
		emit(Op.GET_LOCAL);
		emit(iterSlot);
		emit(Op.GET_LOCAL);
		emit(indexSlot);
		emit(Op.INDEX_GET);
		defineVariable(name);
		blockBody();
		endScope();
		emit(Op.JUMP);
		emit(loopStart);
		patchJump(exitJump);

		for (int jump : loop.breakJumps) patchJump(jump);
		func.loops.removeLast();
		endScope();
	}

	/** Declares a local the program cannot name (the space keeps it unspeakable). */
	private int hiddenLocal(Token at, String name) {
		if (func.locals.size() >= MAX_LOCALS) throw error(at, "too many local variables");
		func.locals.add(new Local(name, func.scopeDepth));
		return func.locals.size() - 1;
	}

	private void returnStatement() {
		if (check(Type.NEWLINE) || check(Type.RBRACE) || check(Type.EOF)) {
			emit(Op.NIL);
		} else {
			expression();
		}
		emit(Op.RETURN);
		terminator();
	}

	private void breakStatement() {
		if (func.loops.isEmpty()) throw error(previous(), "'break' outside of a loop");
		LoopCtx loop = func.loops.getLast();
		discardLocalsAbove(loop.scopeDepth);
		loop.breakJumps.add(emitJump(Op.JUMP));
		terminator();
	}

	private void continueStatement() {
		if (func.loops.isEmpty()) throw error(previous(), "'continue' outside of a loop");
		LoopCtx loop = func.loops.getLast();
		discardLocalsAbove(loop.scopeDepth);
		emit(Op.JUMP);
		emit(loop.start);
		terminator();
	}

	private void expressionStatement() {
		expression();
		emit(Op.POP);
		terminator();
	}

	private void blockBody() {
		skipNewlines();
		while (!check(Type.RBRACE) && !check(Type.EOF)) {
			statement();
			skipNewlines();
		}
		consume(Type.RBRACE, "expected '}'");
	}

	private void beginScope() {
		func.scopeDepth++;
	}

	private void endScope() {
		func.scopeDepth--;
		while (!func.locals.isEmpty() && func.locals.getLast().depth() > func.scopeDepth) {
			func.locals.removeLast();
			emit(Op.POP);
		}
	}

	/** Emits POPs for locals deeper than the given depth without forgetting them (for break/continue). */
	private void discardLocalsAbove(int depth) {
		for (int i = func.locals.size() - 1; i >= 0 && func.locals.get(i).depth() > depth; i--) {
			emit(Op.POP);
		}
	}

	// ---------------------------------------------------------------- expressions

	private void expression() {
		parsePrecedence(PREC_ASSIGNMENT);
	}

	private void parsePrecedence(int precedence) {
		boolean canAssign = precedence <= PREC_ASSIGNMENT;
		prefix(advance(), canAssign);

		while (precedence <= precedenceOf(peek().type())) {
			infix(advance(), canAssign);
		}

		if (canAssign && check(Type.EQ)) {
			throw error(peek(), "invalid assignment target");
		}
	}

	private void prefix(Token token, boolean canAssign) {
		switch (token.type()) {
			case NUMBER, STRING -> {
				emit(Op.CONST);
				emit(constant(token.literal()));
			}
			case TRUE -> emit(Op.TRUE);
			case FALSE -> emit(Op.FALSE);
			case NIL -> emit(Op.NIL);
			case IDENT -> variable(token, canAssign);
			case LPAREN -> {
				expression();
				consume(Type.RPAREN, "expected ')' after expression");
			}
			case LBRACKET -> listLiteral();
			case MINUS -> {
				parsePrecedence(PREC_UNARY);
				emit(Op.NEGATE);
			}
			case NOT -> {
				parsePrecedence(PREC_UNARY);
				emit(Op.NOT);
			}
			default -> throw error(token, "expected an expression");
		}
	}

	private void infix(Token token, boolean canAssign) {
		switch (token.type()) {
			case PLUS -> { parsePrecedence(PREC_FACTOR); emit(Op.ADD); }
			case MINUS -> { parsePrecedence(PREC_FACTOR); emit(Op.SUB); }
			case STAR -> { parsePrecedence(PREC_UNARY); emit(Op.MUL); }
			case SLASH -> { parsePrecedence(PREC_UNARY); emit(Op.DIV); }
			case PERCENT -> { parsePrecedence(PREC_UNARY); emit(Op.MOD); }
			case EQEQ -> { parsePrecedence(PREC_COMPARISON); emit(Op.EQUAL); }
			case BANGEQ -> { parsePrecedence(PREC_COMPARISON); emit(Op.EQUAL); emit(Op.NOT); }
			case GT -> { parsePrecedence(PREC_TERM); emit(Op.GREATER); }
			case LT -> { parsePrecedence(PREC_TERM); emit(Op.LESS); }
			case GTEQ -> { parsePrecedence(PREC_TERM); emit(Op.LESS); emit(Op.NOT); }
			case LTEQ -> { parsePrecedence(PREC_TERM); emit(Op.GREATER); emit(Op.NOT); }
			case AND -> {
				int end = emitJump(Op.JUMP_IF_FALSE_KEEP);
				emit(Op.POP);
				parsePrecedence(PREC_AND + 1);
				patchJump(end);
			}
			case OR -> {
				int end = emitJump(Op.JUMP_IF_TRUE_KEEP);
				emit(Op.POP);
				parsePrecedence(PREC_OR + 1);
				patchJump(end);
			}
			case LPAREN -> callArguments();
			case LBRACKET -> {
				expression();
				consume(Type.RBRACKET, "expected ']' after index");
				if (canAssign && match(Type.EQ)) {
					expression();
					emit(Op.INDEX_SET);
				} else {
					emit(Op.INDEX_GET);
				}
			}
			default -> throw error(token, "unexpected token '" + token.text() + "'");
		}
	}

	private int precedenceOf(Type type) {
		return switch (type) {
			case OR -> PREC_OR;
			case AND -> PREC_AND;
			case EQEQ, BANGEQ -> PREC_EQUALITY;
			case GT, GTEQ, LT, LTEQ -> PREC_COMPARISON;
			case PLUS, MINUS -> PREC_TERM;
			case STAR, SLASH, PERCENT -> PREC_FACTOR;
			case LPAREN, LBRACKET -> PREC_CALL;
			default -> PREC_NONE;
		};
	}

	private void variable(Token name, boolean canAssign) {
		int slot = resolveLocal(name.text());
		if (canAssign && match(Type.EQ)) {
			expression();
			if (slot >= 0) {
				emit(Op.SET_LOCAL);
				emit(slot);
			} else {
				emit(Op.SET_GLOBAL);
				emit(constant(name.text()));
			}
		} else {
			if (slot >= 0) {
				emit(Op.GET_LOCAL);
				emit(slot);
			} else {
				emit(Op.GET_GLOBAL);
				emit(constant(name.text()));
			}
		}
	}

	private int resolveLocal(String name) {
		for (int i = func.locals.size() - 1; i >= 0; i--) {
			if (func.locals.get(i).name().equals(name)) return i;
		}
		return -1;
	}

	private void callArguments() {
		int argc = 0;
		if (!check(Type.RPAREN)) {
			do {
				expression();
				if (++argc > MAX_PARAMS) throw error(peek(), "too many arguments (max " + MAX_PARAMS + ")");
			} while (match(Type.COMMA));
		}
		consume(Type.RPAREN, "expected ')' after arguments");
		emit(Op.CALL);
		emit(argc);
	}

	private void listLiteral() {
		int count = 0;
		if (!check(Type.RBRACKET)) {
			do {
				expression();
				if (++count > 1024) throw error(peek(), "list literal too large");
			} while (match(Type.COMMA));
		}
		consume(Type.RBRACKET, "expected ']' after list");
		emit(Op.LIST);
		emit(count);
	}

	// ---------------------------------------------------------------- plumbing

	private Chunk chunk() {
		return func.chunk;
	}

	private void emit(int value) {
		chunk().emit(value, previous().line());
	}

	private int emitJump(int op) {
		emit(op);
		emit(0xFFFF);
		return chunk().size() - 1;
	}

	private void patchJump(int operandIndex) {
		chunk().patch(operandIndex, chunk().size());
	}

	private int constant(Object value) {
		return chunk().addConstant(value);
	}

	private void terminator() {
		if (match(Type.NEWLINE)) return;
		if (check(Type.RBRACE) || check(Type.EOF)) return;
		throw error(peek(), "expected end of statement before '" + peek().text() + "'");
	}

	private void skipNewlines() {
		while (match(Type.NEWLINE)) {
		}
	}

	private boolean match(Type type) {
		if (!check(type)) return false;
		current++;
		return true;
	}

	private boolean check(Type type) {
		return peek().type() == type;
	}

	private Token advance() {
		Token token = tokens.get(current);
		if (token.type() != Type.EOF) current++;
		return token;
	}

	private Token consume(Type type, String message) {
		if (check(type)) return advance();
		throw error(peek(), message);
	}

	private Token peek() {
		return tokens.get(current);
	}

	private Token previous() {
		return tokens.get(Math.max(0, current - 1));
	}

	private ScriptError error(Token token, String message) {
		return new ScriptError(token.line(), message);
	}
}
