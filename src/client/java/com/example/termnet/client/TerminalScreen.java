package com.example.termnet.client;

import com.example.termnet.net.Packets;
import com.example.termnet.net.TermNetNetworking;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextAreaComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.ScrollContainer;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.HorizontalAlignment;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
	private static final int GLFW_KEY_ENTER = 257;
	private static final int GLFW_KEY_KP_ENTER = 335;
	private static final int MAX_CONSOLE_LABELS = 200;

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
	private TextAreaComponent editor;
	private LabelComponent titleLabel;
	private boolean editorOpen;

	public TerminalScreen(BlockPos pos, String hostname, String code, List<String> lines, boolean running) {
		super(Component.literal("TermNet Terminal"));
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
		TermNetNetworking.CHANNEL.clientHandle().send(new Packets.TerminalInput(pos, line));
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

		editor = UIComponents.textArea(Sizing.fixed(TERM_WIDTH), Sizing.fixed(TERM_HEIGHT + 20), code);
		// Track edits so nothing is lost if the UI rebuilds (window resize).
		editor.onChanged().subscribe(value -> this.code = value);
		editorView.child(editor);

		var buttonRow = UIContainers.horizontalFlow(Sizing.content(), Sizing.content());
		buttonRow.gap(4);
		buttonRow.child(UIComponents.button(Component.literal("Save"), button -> saveCode(false)));
		buttonRow.child(UIComponents.button(Component.literal("Save & Run"), button -> saveCode(true)));
		buttonRow.child(UIComponents.button(Component.literal("Console"), button -> showConsole()));
		editorView.child(buttonRow);
	}

	private void saveCode(boolean andRun) {
		code = editor.getValue();
		TermNetNetworking.CHANNEL.clientHandle().send(new Packets.SetCode(pos, code, andRun));
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
		TermNetNetworking.CHANNEL.clientHandle().send(new Packets.TerminalClosed(pos));
	}
}
