package com.dimitri.potscript.client;

import com.dimitri.potscript.net.Packets;
import com.dimitri.potscript.net.PotScriptNetworking;
import com.dimitri.potscript.script.BuiltinDocs;
import com.dimitri.potscript.script.EditorSupport;
import com.dimitri.potscript.script.Formatter;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Positioning;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The terminal for a server pot: a console with a command line, plus a code
 * editor view. All state changes flow through the server; this screen only
 * renders what the pot sends back.
 */
public class TerminalScreen extends BaseOwoScreen<FlowLayout> {

	private static final int TERM_WIDTH = 340;
	private static final int TERM_HEIGHT = 150;
	private static final int EDITOR_HEIGHT = TERM_HEIGHT + 20;
	/** Two lines of text: enough for the longest signature hint. */
	private static final int HINT_HEIGHT = 20;
	private static final int GLFW_KEY_ENTER = 257;
	private static final int GLFW_KEY_KP_ENTER = 335;
	private static final int GLFW_KEY_TAB = 258;
	private static final int GLFW_KEY_ESCAPE = 256;
	private static final int GLFW_KEY_UP = 265;
	private static final int GLFW_KEY_DOWN = 264;
	private static final int GLFW_KEY_SPACE = 32;
	private static final int GLFW_KEY_F = 70;
	private static final int GLFW_KEY_Q = 81;
	private static final int GLFW_KEY_S = 83;
	private static final int MAX_CONSOLE_LABELS = 200;

	private static final String EDITOR_KEY_HELP =
			"Ctrl+Space complete · Ctrl+Shift+F format · Ctrl+S save · Ctrl+Q console";

	private static TerminalScreen openScreen;

	private final BlockPos pos;
	private String hostname;
	private boolean running;
	private String code;
	/** Client-side scrollback; survives UI rebuilds on window resize. */
	private final java.util.ArrayList<String> lines = new java.util.ArrayList<>();

	private FlowLayout root;
	private FlowLayout consoleView;
	private FlowLayout editorView;
	private FlowLayout consoleLines;
	private ScrollContainer<FlowLayout> consoleScroll;
	private TextBoxComponent input;
	private CodeAreaComponent editor;
	private FlowLayout editorStack;
	private LabelComponent hintLabel;
	private LabelComponent titleLabel;
	private boolean editorOpen;

	/** Rebuilt with the UI on resize, so it holds no state worth preserving. */
	private CompletionPopup completions;
	/** Set by Escape; cleared by the next edit, so dismissing sticks for a moment. */
	private boolean completionsDismissed;
	/**
	 * Set when the caret or the text moved. The refresh adds and removes the
	 * popup, which must not happen while owo is walking the component tree, so
	 * it is applied from {@link #tick()} instead of straight from the listener.
	 */
	private boolean intelDirty;

	public TerminalScreen(BlockPos pos, String hostname, String code, List<String> lines, boolean running) {
		super(Component.literal("PotScript Terminal"));
		this.pos = pos;
		this.hostname = hostname;
		this.code = code;
		this.lines.addAll(lines);
		this.running = running;
		openScreen = this;
	}

	/** The screen currently open for the pot at {@code pos}, or null. */
	public static TerminalScreen current(BlockPos pos) {
		TerminalScreen screen = openScreen;
		return screen != null && screen.pos.equals(pos) ? screen : null;
	}

	@Override
	protected @NotNull OwoUIAdapter<FlowLayout> createAdapter() {
		return OwoUIAdapter.create(this, UIContainers::verticalFlow);
	}

	@Override
	public boolean isPauseScreen() {
		// The pot keeps ticking (and other players keep playing) while the
		// terminal is open, so this must not pause the single-player world.
		return false;
	}

	@Override
	protected void build(FlowLayout rootComponent) {
		rootComponent
				.surface(Surface.VANILLA_TRANSLUCENT)
				.horizontalAlignment(HorizontalAlignment.CENTER)
				.verticalAlignment(VerticalAlignment.CENTER);

		root = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
		root.gap(6)
				.surface(Surface.DARK_PANEL)
				.padding(Insets.of(10));

		titleLabel = UIComponents.label(titleText());
		titleLabel.shadow(true);
		root.child(titleLabel);

		buildConsoleView();
		buildEditorView();
		root.child(editorOpen ? editorView : consoleView);

		rootComponent.child(root);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (editorOpen && handleEditorKey(event)) return true;
		return super.keyPressed(event);
	}

	@Override
	public void tick() {
		super.tick();
		if (intelDirty) {
			intelDirty = false;
			refreshEditorIntel();
		}
	}

	private Component titleText() {
		return Component.literal(hostname)
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
				.append(Component.literal(running ? "  [running]" : "  [idle]")
						.withStyle(running ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
	}

	// ------------------------------------------------------------------ console view

	private void buildConsoleView() {
		consoleView = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
		consoleView.gap(6);

		consoleLines = UIContainers.verticalFlow(Sizing.fixed(TERM_WIDTH), Sizing.content());
		consoleLines.padding(Insets.of(4));

		consoleScroll = UIContainers.verticalScroll(Sizing.fixed(TERM_WIDTH), Sizing.fixed(TERM_HEIGHT), consoleLines);
		consoleScroll.surface(Surface.flat(0xFF0A0A0A));
		consoleView.child(consoleScroll);

		var inputRow = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
		inputRow.gap(4).verticalAlignment(VerticalAlignment.CENTER);

		input = UIComponents.textBox(Sizing.fixed(TERM_WIDTH - 50));
		input.setMaxLength(256);
		input.keyPress().subscribe(event -> {
			if (event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER) {
				submitInput();
				return true;
			}
			return false;
		});
		inputRow.child(input);

		inputRow.child(UIComponents.button(Component.literal("Edit"), button -> showEditor()));
		consoleView.child(inputRow);

		for (String line : lines) addConsoleLabel(line);
		scrollToBottom();
	}

	private void submitInput() {
		String line = input.getValue().strip();
		input.text("");
		if (line.isEmpty()) return;
		if (line.equalsIgnoreCase("edit")) {
			showEditor();
			return;
		}
		PotScriptNetworking.CHANNEL.clientHandle().send(new Packets.TerminalInput(pos, line));
	}

	private void addConsoleLabel(String line) {
		ChatFormatting color = ChatFormatting.GREEN;
		if (line.startsWith("> ")) color = ChatFormatting.WHITE;
		else if (line.startsWith("[error]") || line.startsWith("[compile error]")) color = ChatFormatting.RED;
		else if (line.startsWith("[")) color = ChatFormatting.YELLOW;

		LabelComponent label = UIComponents.label(Component.literal(line.isEmpty() ? " " : line).withStyle(color));
		label.maxWidth(TERM_WIDTH - 16);
		consoleLines.child(label);

		while (consoleLines.children().size() > MAX_CONSOLE_LABELS) {
			consoleLines.removeChild(consoleLines.children().getFirst());
		}
	}

	private void scrollToBottom() {
		if (!consoleLines.children().isEmpty()) {
			consoleScroll.scrollTo(consoleLines.children().getLast());
		}
	}

	// ------------------------------------------------------------------ editor view

	private void buildEditorView() {
		editorView = UIContainers.verticalFlow(Sizing.content(), Sizing.content());
		editorView.gap(6);

		editor = new CodeAreaComponent(Sizing.fixed(TERM_WIDTH), Sizing.fixed(EDITOR_HEIGHT));
		editor.text(code);
		// Track edits so nothing is lost if the UI rebuilds (window resize).
		editor.onChanged().subscribe(value -> {
			this.code = value;
			this.completionsDismissed = false;
			this.intelDirty = true;
		});
		// Typing and plain caret movement both change what should be suggested.
		editor.onCursorMoved(() -> this.intelDirty = true);

		completions = new CompletionPopup();

		// The popup is a floating child of this stack, so it may hang over the
		// editor's bottom edge without being clipped away.
		editorStack = UIContainers.verticalFlow(Sizing.fixed(TERM_WIDTH), Sizing.fixed(EDITOR_HEIGHT));
		editorStack.allowOverflow(true);
		editorStack.child(editor);
		editorView.child(editorStack);

		hintLabel = UIComponents.label(Component.literal(EDITOR_KEY_HELP).withStyle(ChatFormatting.DARK_GRAY));
		hintLabel.maxWidth(TERM_WIDTH);

		// Fixed height: a long signature wraps to two lines, and the button row
		// below it should not shuffle up and down as you type.
		var hintRow = UIContainers.verticalFlow(Sizing.fixed(TERM_WIDTH), Sizing.fixed(HINT_HEIGHT));
		hintRow.allowOverflow(false);
		hintRow.child(hintLabel);
		editorView.child(hintRow);

		var buttonRow = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
		buttonRow.gap(4);
		buttonRow.child(UIComponents.button(Component.literal("Save"), button -> saveCode(false)));
		buttonRow.child(UIComponents.button(Component.literal("Save & Run"), button -> saveCode(true)));
		buttonRow.child(UIComponents.button(Component.literal("Format"), button -> formatCode()));
		buttonRow.child(UIComponents.button(Component.literal("Console"), button -> showConsole()));
		editorView.child(buttonRow);
	}

	// ------------------------------------------------------------------ editor intelligence

	/** Reformats the buffer, keeping the caret on the same line. */
	private void formatCode() {
		String before = editor.getValue();
		String after = Formatter.format(before);
		if (after.equals(before)) return;

		int line = lineOf(before, editor.cursor());
		editor.text(after);
		editor.moveCursorTo(endOfLine(after, line));
		code = after;
		closeCompletions();
		refreshEditorIntel();
	}

	private static int lineOf(String text, int offset) {
		int line = 0;
		for (int i = 0; i < Math.min(offset, text.length()); i++) {
			if (text.charAt(i) == '\n') line++;
		}
		return line;
	}

	/** Offset of the end of {@code line}, or of the text if it has fewer lines. */
	private static int endOfLine(String text, int line) {
		int at = 0;
		for (int i = 0; i < line; i++) {
			int next = text.indexOf('\n', at);
			if (next < 0) return text.length();
			at = next + 1;
		}
		int end = text.indexOf('\n', at);
		return end < 0 ? text.length() : end;
	}

	/** Recomputes the signature hint and the completion list for the caret. */
	private void refreshEditorIntel() {
		if (!editorOpen || editor == null) return;

		String source = editor.getValue();
		int cursor = editor.cursor();

		EditorSupport.Signature signature = EditorSupport.signatureAt(source, cursor);
		hintLabel.text(signature != null ? signatureText(signature)
				: Component.literal(EDITOR_KEY_HELP).withStyle(ChatFormatting.DARK_GRAY));

		if (completionsDismissed || EditorSupport.prefixAt(source, cursor).isEmpty()) {
			closeCompletions();
		} else {
			openCompletions(EditorSupport.completionsAt(source, cursor));
		}
	}

	/** {@code sub(s, from, [to]) -> string}, with the argument being typed picked out. */
	private static Component signatureText(EditorSupport.Signature signature) {
		BuiltinDocs.Builtin builtin = signature.builtin();
		MutableComponent text = Component.literal(builtin.name() + "(").withStyle(ChatFormatting.AQUA);

		if (builtin.isVariadic()) {
			text.append(Component.literal("...").withStyle(ChatFormatting.WHITE));
		} else {
			List<String> params = builtin.params();
			for (int i = 0; i < params.size(); i++) {
				if (i > 0) text.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
				boolean optional = i >= builtin.minArgs();
				String name = optional ? "[" + params.get(i) + "]" : params.get(i);
				text.append(Component.literal(name).withStyle(i == signature.activeParam()
						? ChatFormatting.WHITE
						: ChatFormatting.DARK_GRAY));
			}
		}

		return text.append(Component.literal(") -> " + builtin.returns()).withStyle(ChatFormatting.AQUA))
				.append(Component.literal("   " + builtin.doc()).withStyle(ChatFormatting.DARK_GRAY));
	}

	private void openCompletions(List<EditorSupport.Completion> candidates) {
		if (candidates.isEmpty()) {
			closeCompletions();
			return;
		}

		boolean wasOpen = completions.isOpen();
		completions.show(candidates);
		if (!wasOpen) editorStack.child(completions.component());
		positionCompletions();
	}

	/** Anchors the popup under the caret, flipping or nudging it to stay on screen. */
	private void positionCompletions() {
		int x = Math.clamp(editor.caretX(), 0, Math.max(0, TERM_WIDTH - CompletionPopup.WIDTH));

		int below = editor.caretBottomY() + 1;
		int height = completions.height();
		int y = below + height > EDITOR_HEIGHT
				? below - height - editor.lineHeight() - 2
				: below;

		completions.component().positioning(Positioning.absolute(x, Math.max(0, y)));
	}

	private void closeCompletions() {
		if (!completions.isOpen()) return;
		editorStack.removeChild(completions.component());
		completions.hide();
	}

	/** Replaces the prefix at the caret with the highlighted candidate. */
	private void acceptCompletion() {
		EditorSupport.Completion completion = completions.selection();
		closeCompletions();
		if (completion == null) return;

		// The prefix is read backwards from the caret, so the caret already sits
		// at its end: only the remainder of the name has to be typed in.
		String prefix = EditorSupport.prefixAt(editor.getValue(), editor.cursor());
		editor.insertText(completion.name().substring(prefix.length()));
		code = editor.getValue();
		refreshEditorIntel();
	}

	/** Editor-only keys, taken before the text area sees them. */
	private boolean handleEditorKey(KeyEvent event) {
		if (completions.isOpen()) {
			switch (event.key()) {
				case GLFW_KEY_ESCAPE -> {
					closeCompletions();
					completionsDismissed = true;
					return true;
				}
				case GLFW_KEY_UP -> {
					completions.moveSelection(-1);
					return true;
				}
				case GLFW_KEY_DOWN -> {
					completions.moveSelection(1);
					return true;
				}
				case GLFW_KEY_TAB, GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
					acceptCompletion();
					return true;
				}
				default -> {
				}
			}
		}

		if (!event.hasControlDown()) return false;
		switch (event.key()) {
			case GLFW_KEY_S -> {
				saveCode(false);
				return true;
			}
			case GLFW_KEY_Q -> {
				showConsole();
				return true;
			}
			case GLFW_KEY_F -> {
				if (!event.hasShiftDown()) return false;
				formatCode();
				return true;
			}
			case GLFW_KEY_SPACE -> {
				completionsDismissed = false;
				openCompletions(EditorSupport.completionsAt(editor.getValue(), editor.cursor()));
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private void saveCode(boolean andRun) {
		code = editor.getValue();
		PotScriptNetworking.CHANNEL.clientHandle().send(new Packets.SetCode(pos, code, andRun));
		if (andRun) showConsole();
	}

	private void showEditor() {
		if (editorOpen) return;
		editorOpen = true;
		root.removeChild(consoleView);
		root.child(editorView);
	}

	private void showConsole() {
		if (!editorOpen) return;
		closeCompletions();
		editorOpen = false;
		code = editor.getValue();
		root.removeChild(editorView);
		root.child(consoleView);
		scrollToBottom();
	}

	// ------------------------------------------------------------------ server pushes

	public void appendLines(List<String> newLines) {
		for (String line : newLines) {
			lines.add(line);
			addConsoleLabel(line);
		}
		while (lines.size() > MAX_CONSOLE_LABELS) lines.removeFirst();
		scrollToBottom();
	}

	public void clearConsole() {
		lines.clear();
		consoleLines.clearChildren();
	}

	public void updateState(boolean running, String hostname) {
		this.running = running;
		this.hostname = hostname;
		titleLabel.text(titleText());
	}

	@Override
	public void removed() {
		super.removed();
		if (openScreen == this) openScreen = null;
		PotScriptNetworking.CHANNEL.clientHandle().send(new Packets.TerminalClosed(pos));
	}
}
