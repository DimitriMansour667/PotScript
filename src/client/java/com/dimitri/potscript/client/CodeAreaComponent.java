package com.dimitri.potscript.client;

import com.dimitri.potscript.script.BuiltinDocs;
import com.dimitri.potscript.script.Lexer;
import com.dimitri.potscript.script.Lexer.Token;
import io.wispforest.owo.ui.component.TextAreaComponent;
import io.wispforest.owo.ui.core.Sizing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.TextCursorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * owo's text area with PotScript syntax colouring.
 *
 * <p>Only the drawing of the contents is replaced. Everything that makes a
 * text field work — the cursor, selection, clipboard, word wrapping, scrolling
 * — stays in {@link MultilineTextField}, which this never touches beyond
 * reading. If the renderer ever throws it disables itself and falls back to
 * the stock one for the rest of the session; a colour scheme is not worth a
 * broken screen.
 */
public class CodeAreaComponent extends TextAreaComponent {

	private static final Logger LOGGER = LoggerFactory.getLogger("potscript/editor");

	private static final int LINE_HEIGHT = 9;
	private static final boolean TEXT_SHADOW = false;

	// Colours, roughly Material-palette-ish so they read on the dark panel.
	private static final int COLOR_TEXT = 0xFFE0E0E0;
	private static final int COLOR_KEYWORD = 0xFFC792EA;
	private static final int COLOR_LITERAL = 0xFFF78C6C;
	private static final int COLOR_STRING = 0xFFC3E88D;
	private static final int COLOR_COMMENT = 0xFF7A8290;
	private static final int COLOR_BUILTIN = 0xFF82AAFF;
	private static final int COLOR_PUNCTUATION = 0xFF89DDFF;
	private static final int COLOR_ERROR = 0xFFFF5370;
	private static final int COLOR_CURSOR = 0xFFD0D0D0;

	private long focusedTime = System.currentTimeMillis();
	private boolean highlighting = true;

	private Runnable cursorListener = () -> {
	};
	private int lastSeenCursor = -1;

	/** Per-character colours for {@link #colouredSource}, rebuilt when the text changes. */
	private String colouredSource;
	private int[] colours = new int[0];

	public CodeAreaComponent(Sizing horizontalSizing, Sizing verticalSizing) {
		super(horizontalSizing, verticalSizing);
	}

	// ------------------------------------------------------------------ editing state

	/** Offset of the caret in {@link #getValue()}. */
	public int cursor() {
		return editBox.cursor();
	}

	/** Types text at the caret, replacing the selection, exactly as the keyboard would. */
	public void insertText(String text) {
		editBox.insertText(text);
	}

	/** Moves the caret without selecting, clamped into the buffer. */
	public void moveCursorTo(int offset) {
		editBox.setSelecting(false);
		editBox.seekCursor(net.minecraft.client.gui.components.Whence.ABSOLUTE,
				Math.clamp(offset, 0, getValue().length()));
	}

	/** Zero-based display line the caret sits on, for anchoring popups. */
	public int cursorLine() {
		return editBox.getLineAtCursor();
	}

	/**
	 * Pixel offset of the caret from the left edge of its line.
	 *
	 * <p>{@code StringView} is a protected nested type, so it can be held in a
	 * {@code var} but never named — the same dance recurs below.
	 */
	public int cursorColumnWidth() {
		var line = editBox.getLineView(cursorLine());
		return font().width(getValue().substring(line.beginIndex(), Math.min(cursor(), line.endIndex())));
	}

	/** X of the caret, relative to this component's own top-left corner. */
	public int caretX() {
		return (getInnerLeft() - getX()) + cursorColumnWidth();
	}

	/** Y just below the caret's line, relative to this component's own top-left corner. */
	public int caretBottomY() {
		return (getInnerTop() - getY()) + (cursorLine() + 1) * LINE_HEIGHT - (int) scrollAmount();
	}

	public int lineHeight() {
		return LINE_HEIGHT;
	}

	/**
	 * Runs whenever the caret moves, for whatever reason — typing, arrow keys,
	 * a click. The text field's own cursor listener is already taken by the
	 * widget's scroll-to-cursor, so this watches the offset instead of
	 * replacing that.
	 */
	public CodeAreaComponent onCursorMoved(Runnable listener) {
		this.cursorListener = listener;
		return this;
	}

	@Override
	public void update(float delta, int mouseX, int mouseY) {
		super.update(delta, mouseX, mouseY);
		int cursor = editBox.cursor();
		if (cursor != lastSeenCursor) {
			lastSeenCursor = cursor;
			cursorListener.run();
		}
	}

	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		// The blink phase is measured from the moment focus was gained; the
		// field the vanilla widget uses for that is private, so keep our own.
		if (focused) focusedTime = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------ rendering

	@Override
	protected void extractContents(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		if (!highlighting) {
			super.extractContents(extractor, mouseX, mouseY, partialTick);
			return;
		}
		try {
			extractHighlighted(extractor);
		} catch (Exception e) {
			LOGGER.error("PotScript syntax highlighting failed; falling back to plain text", e);
			highlighting = false;
			super.extractContents(extractor, mouseX, mouseY, partialTick);
		}
	}

	private void extractHighlighted(GuiGraphicsExtractor extractor) {
		Font font = font();
		String value = getValue();
		refreshColours(value);

		int left = getInnerLeft();
		int y = getInnerTop();
		int cursor = editBox.cursor();
		int cursorX = left;
		int cursorY = y;
		boolean cursorPlaced = false;

		for (var line : editBox.iterateLines()) {
			if (withinContentAreaTopBottom(y, y + LINE_HEIGHT)) {
				extractLine(extractor, font, value, line.beginIndex(), line.endIndex(), left, y);
			}
			if (!cursorPlaced && cursor >= line.beginIndex() && cursor <= line.endIndex()) {
				cursorX = left + font.width(value.substring(line.beginIndex(), cursor));
				cursorY = y;
				cursorPlaced = true;
			}
			y += LINE_HEIGHT;
		}

		if (isFocused() && TextCursorUtils.isCursorVisible(System.currentTimeMillis() - focusedTime)
				&& withinContentAreaTopBottom(cursorY, cursorY + LINE_HEIGHT)) {
			if (cursor < value.length()) {
				TextCursorUtils.extractInsertCursor(extractor, cursorX, cursorY, COLOR_CURSOR, LINE_HEIGHT);
			} else {
				TextCursorUtils.extractAppendCursor(extractor, font, cursorX, cursorY, COLOR_CURSOR, TEXT_SHADOW);
			}
		}

		extractSelection(extractor, font, value, left);
	}

	/** Draws one display line as runs of same-coloured characters. */
	private void extractLine(GuiGraphicsExtractor extractor, Font font, String value,
	                         int begin, int end, int left, int y) {
		int x = left;
		int from = begin;
		while (from < end) {
			int colour = colourAt(from);
			int to = from + 1;
			while (to < end && colourAt(to) == colour) to++;

			String run = value.substring(from, to);
			extractor.text(font, run, x, y, colour, TEXT_SHADOW);
			x += font.width(run);
			from = to;
		}
	}

	/**
	 * The selection band, drawn after the text so it tints it. A selection that
	 * runs past the end of a line is widened to the edge, which is how the
	 * vanilla widget shows that the line break itself is selected.
	 */
	private void extractSelection(GuiGraphicsExtractor extractor, Font font, String value, int left) {
		if (!editBox.hasSelection()) return;

		var selection = editBox.getSelected();
		int y = getInnerTop();
		for (var line : editBox.iterateLines()) {
			if (selection.beginIndex() > line.endIndex()) {
				y += LINE_HEIGHT;
				continue;
			}
			if (line.beginIndex() > selection.endIndex()) break;

			if (withinContentAreaTopBottom(y, y + LINE_HEIGHT)) {
				int start = font.width(value.substring(line.beginIndex(),
						Math.max(selection.beginIndex(), line.beginIndex())));
				int end = selection.endIndex() > line.endIndex()
						? getWidth() - innerPadding()
						: font.width(value.substring(line.beginIndex(), selection.endIndex()));
				extractor.textHighlight(left + start, y, left + end, y + LINE_HEIGHT, true);
			}
			y += LINE_HEIGHT;
		}
	}

	// ------------------------------------------------------------------ colouring

	private int colourAt(int index) {
		return index < colours.length ? colours[index] : COLOR_TEXT;
	}

	/** Retokenizes only when the buffer actually changed — this runs every frame. */
	private void refreshColours(String value) {
		if (value.equals(colouredSource)) return;
		colouredSource = value;

		int[] painted = new int[value.length()];
		java.util.Arrays.fill(painted, COLOR_TEXT);

		List<Token> tokens = Lexer.tokenizeForEditor(value);
		for (int t = 0; t < tokens.size(); t++) {
			Token token = tokens.get(t);
			int colour = colourOf(token, t > 0 && tokens.get(t - 1).type() == Lexer.Type.DOT);
			if (colour == COLOR_TEXT) continue;
			for (int i = token.start(); i < Math.min(token.end(), painted.length); i++) {
				painted[i] = colour;
			}
		}
		colours = painted;
	}

	private static int colourOf(Token token, boolean afterDot) {
		return switch (token.type()) {
			case LET, FN, IF, ELSE, WHILE, FOR, IN, RETURN, BREAK, CONTINUE, AND, OR, NOT -> COLOR_KEYWORD;
			case TRUE, FALSE, NIL, NUMBER -> COLOR_LITERAL;
			case STRING -> COLOR_STRING;
			case COMMENT -> COLOR_COMMENT;
			case ERROR -> COLOR_ERROR;
			case IDENT -> (afterDot ? BuiltinDocs.isMethod(token.text()) : BuiltinDocs.isBuiltin(token.text()))
					? COLOR_BUILTIN : COLOR_TEXT;
			case NEWLINE, EOF -> COLOR_TEXT;
			default -> COLOR_PUNCTUATION;
		};
	}

	private static Font font() {
		return Minecraft.getInstance().font;
	}
}
