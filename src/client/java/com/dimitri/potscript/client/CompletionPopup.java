package com.dimitri.potscript.client;

import com.dimitri.potscript.script.EditorSupport.Completion;
import com.dimitri.potscript.script.EditorSupport.Kind;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.UIComponents;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.container.UIContainers;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The candidate list that floats under the caret in the code editor.
 *
 * <p>Composes a {@link FlowLayout} rather than extending one, so the screen
 * can drop its component into any parent and take it back out again — the
 * terminal's UI is rebuilt from scratch on every window resize.
 */
final class CompletionPopup {

	static final int WIDTH = 190;
	private static final int ROW_HEIGHT = 10;
	private static final int PADDING = 2;
	private static final int MAX_ROWS = 8;
	private static final int SELECTED_ROW = 0x883C5A8C;

	private final FlowLayout root = UIContainers.verticalFlow(Sizing.fixed(WIDTH), Sizing.content());

	private List<Completion> candidates = List.of();
	private int selected;

	CompletionPopup() {
		root.surface(Surface.flat(0xF0101418).and(Surface.outline(0xFF3C5A8C)));
		root.padding(Insets.of(PADDING));
	}

	FlowLayout component() {
		return root;
	}

	boolean isOpen() {
		return !candidates.isEmpty();
	}

	Completion selection() {
		return candidates.isEmpty() ? null : candidates.get(selected);
	}

	int height() {
		return candidates.size() * ROW_HEIGHT + PADDING * 2;
	}

	/** Shows the first {@value #MAX_ROWS} candidates, selecting the first. */
	void show(List<Completion> newCandidates) {
		this.candidates = newCandidates.size() > MAX_ROWS
				? List.copyOf(newCandidates.subList(0, MAX_ROWS))
				: List.copyOf(newCandidates);
		this.selected = 0;
		rebuild();
	}

	void hide() {
		this.candidates = List.of();
		this.selected = 0;
		root.clearChildren();
	}

	/** Moves the highlight, wrapping around at both ends. */
	void moveSelection(int delta) {
		if (candidates.isEmpty()) return;
		selected = Math.floorMod(selected + delta, candidates.size());
		rebuild();
	}

	private void rebuild() {
		root.clearChildren();
		for (int i = 0; i < candidates.size(); i++) {
			root.child(row(candidates.get(i), i == selected));
		}
	}

	private FlowLayout row(Completion completion, boolean isSelected) {
		LabelComponent label = UIComponents.label(
				Component.literal(completion.name()).withStyle(colourOf(completion.kind()))
						.append(Component.literal("  " + completion.detail())
								.withStyle(ChatFormatting.DARK_GRAY)));

		FlowLayout row = UIContainers.horizontalFlow(Sizing.fixed(WIDTH - PADDING * 2), Sizing.fixed(ROW_HEIGHT));
		row.verticalAlignment(VerticalAlignment.CENTER);
		row.padding(Insets.left(2));
		row.surface(isSelected ? Surface.flat(SELECTED_ROW) : Surface.BLANK);
		row.allowOverflow(false);
		row.child(label);
		return row;
	}

	private static ChatFormatting colourOf(Kind kind) {
		return switch (kind) {
			case BUILTIN -> ChatFormatting.AQUA;
			case KEYWORD -> ChatFormatting.LIGHT_PURPLE;
			case FUNCTION -> ChatFormatting.YELLOW;
			case VARIABLE -> ChatFormatting.WHITE;
		};
	}
}
