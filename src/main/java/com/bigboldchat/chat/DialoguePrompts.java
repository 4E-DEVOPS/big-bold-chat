package com.bigboldchat.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.bigboldchat.chatbox.ChatboxGeometry;

import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;

/**
 * Adapts mounted dialogue and prompt geometry to ChatXL's effective size.
 */
public final class DialoguePrompts {
	private static final int MIN_SIDE_TEXT_WIDTH = 160;
	private static final int OPTION_ROW_GAP = 4;
	private static final int STACK_PADDING = 10;
	private static final int STACK_GAP = 12;

	private final Client client;

	private OptionsState optionsState;
	private PortraitState npcState;
	private PortraitState playerState;

	public DialoguePrompts(Client client) {
		this.client = client;
	}

	public Result apply(int effectiveWidth) {
		if (effectiveWidth <= 0) {
			return reset();
		}

		final MutableResult result = new MutableResult();
		applyOptions(effectiveWidth, result);
		npcState = applyPortrait(effectiveWidth, false, npcState, result,
				InterfaceID.ChatLeft.UNIVERSE,
				InterfaceID.ChatLeft.SAFEZONE,
				InterfaceID.ChatLeft.HEAD,
				InterfaceID.ChatLeft.CONTENT,
				InterfaceID.ChatLeft.NAME,
				InterfaceID.ChatLeft.CONTINUE,
				InterfaceID.ChatLeft.TEXT);
		playerState = applyPortrait(effectiveWidth, true, playerState, result,
				InterfaceID.ChatRight.UNIVERSE,
				InterfaceID.ChatRight.SAFEZONE,
				InterfaceID.ChatRight.HEAD,
				InterfaceID.ChatRight.CONTENT,
				InterfaceID.ChatRight.NAME,
				InterfaceID.ChatRight.CONTINUE,
				InterfaceID.ChatRight.TEXT);
		return result.build();
	}

	public Result reset() {
		final MutableResult result = new MutableResult();
		restoreOptions(result);
		npcState = restorePortrait(npcState, result);
		playerState = restorePortrait(playerState, result);
		return result.build();
	}

	public Widget messageLayer() {
		return client.getWidget(InterfaceID.Chatbox.MES_LAYER);
	}

	public Widget options() {
		return client.getWidget(InterfaceID.Chatmenu.OPTIONS);
	}

	public Widget npcText() {
		return client.getWidget(InterfaceID.ChatLeft.TEXT);
	}

	public Widget playerText() {
		return client.getWidget(InterfaceID.ChatRight.TEXT);
	}

	public boolean isActive() {
		return isMounted(options())
				|| isMounted(client.getWidget(InterfaceID.ChatLeft.UNIVERSE))
				|| isMounted(client.getWidget(InterfaceID.ChatRight.UNIVERSE));
	}

	private void applyOptions(int effectiveWidth, MutableResult result) {
		final Widget options = options();
		if (!isMounted(options)) {
			restoreOptions(result);
			return;
		}

		if (optionsState == null || optionsState.root != options) {
			restoreOptions(result);
			optionsState = new OptionsState(options);
		}

		optionsState.captureChildren(options.getDynamicChildren());

		final int inset = Math.max(0, (ChatboxGeometry.NATIVE_WIDTH - optionsState.rootGeometry.width) / 2);
		final int width = Math.max(1, effectiveWidth - inset * 2);
		final List<OptionRow> rows = optionRows(optionsState, width);
		final int height = optionHeight(optionsState, rows);
		final Widget messageLayer = messageLayer();
		final int parentHeight = messageLayer != null && messageLayer.getHeight() > 0
				? messageLayer.getHeight()
				: options.getParent() != null ? options.getParent().getHeight() : optionsState.rootGeometry.height;
		final int y = Math.max(0, (parentHeight - height) / 2);

		applyGeometry(options, optionsState.rootGeometry.x, y, width, height, result);
		applyOptionRows(rows, width, result);
		applyOptionGraphics(optionsState, width, result);
	}

	private static List<OptionRow> optionRows(OptionsState state, int width) {
		final List<OptionRow> rows = new ArrayList<>();
		for (Map.Entry<Widget, WidgetState> entry : state.children.entrySet()) {
			final Widget widget = entry.getKey();
			final WidgetState baseline = entry.getValue();
			if (widget != null && baseline != null && baseline.geometry.hasSize() && widget.getType() == WidgetType.TEXT) {
				rows.add(new OptionRow(widget, baseline, wrapText(widget, baseline.sourceText, width)));
			}
		}

		rows.sort(Comparator.comparingInt(row -> row.baseline.geometry.y));

		int y = rows.isEmpty() ? 0 : rows.get(0).baseline.geometry.y;
		int previousBottom = y;
		for (OptionRow row : rows) {
			final Geometry baseline = row.baseline.geometry;
			if (row != rows.get(0)) {
				final int nativeGap = baseline.y - previousBottom;
				y += Math.max(OPTION_ROW_GAP, nativeGap);
			}

			row.y = y;
			row.height = wrappedHeight(row.widget, baseline.height, row.text);
			y += row.height;
			previousBottom = baseline.y + baseline.height;
		}
		return rows;
	}

	private static int optionHeight(OptionsState state, List<OptionRow> rows) {
		if (rows.isEmpty()) {
			return state.rootGeometry.height;
		}

		final OptionRow last = rows.get(rows.size() - 1);
		final Geometry lastBaseline = last.baseline.geometry;
		final int bottomMargin = Math.max(0, state.rootGeometry.height - lastBaseline.y - lastBaseline.height);
		return Math.max(state.rootGeometry.height, last.y + last.height + bottomMargin);
	}

	private static void applyOptionRows(List<OptionRow> rows, int width, MutableResult result) {
		for (OptionRow row : rows) {
			applyTextGeometry(row.widget, 0, row.y, width, row.height, row.text, result);
			row.baseline.renderedText = row.text;
		}
	}

	private static void applyOptionGraphics(OptionsState state, int width, MutableResult result) {
		final List<GraphicLayout> graphics = new ArrayList<>();
		for (Map.Entry<Widget, WidgetState> entry : state.children.entrySet()) {
			final Widget widget = entry.getKey();
			final WidgetState baseline = entry.getValue();
			if (widget == null || baseline == null || !baseline.geometry.hasSize() || widget.getType() != WidgetType.GRAPHIC) {
				continue;
			}

			final Geometry geometry = baseline.geometry;
			graphics.add(new GraphicLayout(widget, baseline,
					anchoredX(geometry, state.rootGeometry.width, width)));
		}

		boolean overlap = false;
		for (int i = 0; i < graphics.size() && !overlap; i++) {
			for (int j = i + 1; j < graphics.size(); j++) {
				final GraphicLayout a = graphics.get(i);
				final GraphicLayout b = graphics.get(j);
				if (a.x < b.x + b.baseline.geometry.width && b.x < a.x + a.baseline.geometry.width) {
					overlap = true;
					break;
				}
			}
		}

		for (GraphicLayout graphic : graphics) {
			final Geometry geometry = graphic.baseline.geometry;
			applyGeometry(graphic.widget, graphic.x, geometry.y, geometry.width, geometry.height, result);
			applyHidden(graphic.widget, overlap || graphic.baseline.hidden, result);
		}
	}

	private PortraitState applyPortrait(
			int effectiveWidth,
			boolean rightSide,
			PortraitState state,
			MutableResult result,
			int rootId,
			int safeId,
			int headId,
			int contentId,
			int nameId,
			int continueId,
			int textId) {
		final Widget root = client.getWidget(rootId);
		if (!isMounted(root)) {
			return restorePortrait(state, result);
		}

		if (state == null || state.root != root) {
			state = restorePortrait(state, result);
			state = new PortraitState(
					root,
					client.getWidget(safeId),
					client.getWidget(headId),
					client.getWidget(contentId),
					client.getWidget(nameId),
					client.getWidget(continueId),
					client.getWidget(textId));
		}

		if (!state.isComplete()) {
			return state;
		}

		final int inset = Math.max(0, ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width);
		final int width = Math.max(1, effectiveWidth - inset);
		final int sideTextWidth = fittedWidth(state.textGeometry, state.contentGeometry.width,
				fittedWidth(state.contentGeometry, state.safeGeometry.width,
						fittedWidth(state.safeGeometry, state.rootGeometry.width, width)));

		if (sideTextWidth < MIN_SIDE_TEXT_WIDTH) {
			applyStacked(state, width, result);
		} else {
			applySideBySide(state, width, rightSide, result);
		}

		return state;
	}

	private void applySideBySide(PortraitState state, int width, boolean rightSide, MutableResult result) {
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int contentWidth = fittedWidth(state.contentGeometry, state.safeGeometry.width, safeWidth);

		applyGeometry(state.root, state.rootGeometry.x, state.rootGeometry.y, width, state.rootGeometry.height, result);
		applyGeometry(state.safe, state.safeGeometry.x, state.safeGeometry.y, safeWidth, state.safeGeometry.height, result);

		final int headX = rightSide
				? anchoredRightX(state.headGeometry, state.safeGeometry.width, safeWidth)
				: state.headGeometry.x;
		applyGeometry(state.head, headX, state.headGeometry.y,
				state.headGeometry.width, state.headGeometry.height, result);

		applyGeometry(state.content, state.contentGeometry.x, state.contentGeometry.y,
				contentWidth, state.contentGeometry.height, result);
		applyFittedChild(state.name, state.nameGeometry, state.contentGeometry.width, contentWidth, result);
		applyFittedChild(state.continueWidget, state.continueGeometry, state.contentGeometry.width, contentWidth, result);
		applyFittedChild(state.text, state.textGeometry, state.contentGeometry.width, contentWidth, result);
	}

	private void applyStacked(PortraitState state, int width, MutableResult result) {
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int contentWidth = Math.max(1, safeWidth);
		final int headX = Math.max(0, (safeWidth - state.headGeometry.width) / 2);
		final int headY = state.headGeometry.y;
		final int portraitHeight = Math.max(state.safeGeometry.height, headY + state.headGeometry.height);
		final int contentPadding = Math.min(STACK_PADDING, Math.max(0, (contentWidth - 1) / 2));
		final int childWidth = Math.max(1, contentWidth - contentPadding * 2);
		final int minY = minY(state.nameGeometry, state.continueGeometry, state.textGeometry);
		final int maxBottom = maxBottom(state.nameGeometry, state.continueGeometry, state.textGeometry);
		final int contentY = portraitHeight + STACK_GAP - minY;
		final int contentHeight = Math.max(state.contentGeometry.height, maxBottom + STACK_PADDING);
		final int height = Math.max(state.rootGeometry.height, contentY + contentHeight + STACK_PADDING);
		final int contentOriginalY = positionY(state.content, contentY, height, contentHeight);

		applyGeometry(state.root, state.rootGeometry.x, state.rootGeometry.y, width, height, result);
		applyGeometry(state.safe, state.safeGeometry.x, state.safeGeometry.y, safeWidth, height, result);
		applyGeometry(state.head, headX, headY, state.headGeometry.width, state.headGeometry.height, result);
		applyGeometry(state.content, 0, contentOriginalY, contentWidth, contentHeight, result);
		applyStackedChild(state.name, state.nameGeometry, contentPadding, childWidth, result);
		applyStackedChild(state.continueWidget, state.continueGeometry, contentPadding, childWidth, result);
		applyStackedChild(state.text, state.textGeometry, contentPadding, childWidth, result);
	}

	private void applyFittedChild(Widget widget, Geometry baseline, int parentWidth, int targetParentWidth, MutableResult result) {
		if (widget == null || baseline == null) {
			return;
		}

		applyGeometry(widget, baseline.x, baseline.y,
				fittedWidth(baseline, parentWidth, targetParentWidth), baseline.height, result);
	}

	private void applyStackedChild(Widget widget, Geometry baseline, int x, int width, MutableResult result) {
		if (widget != null && baseline != null) {
			applyGeometry(widget, x, baseline.y, width, baseline.height, result);
		}
	}

	private void restoreOptions(MutableResult result) {
		if (optionsState == null) {
			return;
		}

		restoreGeometry(optionsState.root, optionsState.rootGeometry, result);
		final Widget[] children = optionsState.root.getDynamicChildren();
		if (children != null) {
			for (Widget child : children) {
				final WidgetState baseline = optionsState.children.get(child);
				if (baseline != null) {
					restoreWidget(child, baseline, result);
				}
			}
		}
		optionsState = null;
	}

	private PortraitState restorePortrait(PortraitState state, MutableResult result) {
		if (state == null) {
			return null;
		}

		restoreGeometry(state.root, state.rootGeometry, result);
		restoreGeometry(state.safe, state.safeGeometry, result);
		restoreGeometry(state.head, state.headGeometry, result);
		restoreGeometry(state.content, state.contentGeometry, result);
		restoreGeometry(state.name, state.nameGeometry, result);
		restoreGeometry(state.continueWidget, state.continueGeometry, result);
		restoreGeometry(state.text, state.textGeometry, result);
		return null;
	}

	private static String wrapText(Widget widget, String text, int maxWidth) {
		if (widget == null || text == null || text.isEmpty() || maxWidth <= 0) {
			return text;
		}

		final FontTypeFace font = widget.getFont();
		if (font == null || font.getTextWidth(text) <= maxWidth) {
			return text;
		}

		final StringBuilder output = new StringBuilder(text.length() + 16);
		final String[] paragraphs = text.replace("<br>", "\n").split("\n", -1);
		for (int i = 0; i < paragraphs.length; i++) {
			if (i > 0) {
				output.append("<br>");
			}
			wrapParagraph(font, paragraphs[i], maxWidth, output);
		}
		return output.toString();
	}

	private static void wrapParagraph(FontTypeFace font, String paragraph, int maxWidth, StringBuilder output) {
		if (paragraph.isEmpty()) {
			return;
		}

		final String[] words = paragraph.trim().split("\\s+");
		String line = "";
		for (String word : words) {
			final String candidate = line.isEmpty() ? word : line + " " + word;
			if (font.getTextWidth(candidate) <= maxWidth) {
				line = candidate;
				continue;
			}

			if (!line.isEmpty()) {
				appendWrappedLine(output, line);
				line = "";
			}

			if (font.getTextWidth(word) <= maxWidth) {
				line = word;
			} else {
				line = wrapWord(font, word, maxWidth, output);
			}
		}

		if (!line.isEmpty()) {
			appendWrappedLine(output, line);
		}
	}

	private static String wrapWord(FontTypeFace font, String word, int maxWidth, StringBuilder output) {
		String segment = "";
		for (int i = 0; i < word.length(); i++) {
			final String candidate = segment + word.charAt(i);
			if (!segment.isEmpty() && font.getTextWidth(candidate) > maxWidth) {
				appendWrappedLine(output, segment);
				segment = String.valueOf(word.charAt(i));
			} else {
				segment = candidate;
			}
		}
		return segment;
	}

	private static void appendWrappedLine(StringBuilder output, String line) {
		if (output.length() > 0 && !endsWithBreak(output)) {
			output.append("<br>");
		}
		output.append(line);
	}

	private static boolean endsWithBreak(StringBuilder output) {
		final int length = output.length();
		return length >= 4 && output.substring(length - 4).equals("<br>");
	}

	private static int wrappedHeight(Widget widget, int nativeHeight, String text) {
		final int lines = lineCount(text);
		final FontTypeFace font = widget != null ? widget.getFont() : null;
		final int lineHeight = font != null ? Math.max(nativeHeight, font.getBaseline() + OPTION_ROW_GAP) : nativeHeight;
		return Math.max(nativeHeight, lines * lineHeight);
	}

	private static int lineCount(String text) {
		if (text == null || text.isEmpty()) {
			return 1;
		}

		int lines = 1;
		int index = 0;
		while ((index = text.indexOf("<br>", index)) >= 0) {
			lines++;
			index += 4;
		}
		return lines;
	}

	private static int positionY(Widget widget, int relativeY, int parentHeight, int height) {
		if (widget != null && widget.getYPositionMode() == WidgetPositionMode.ABSOLUTE_CENTER) {
			return relativeY - (parentHeight - height) / 2;
		}

		return relativeY;
	}

	private static int fittedWidth(Geometry child, int parentWidth, int targetParentWidth) {
		if (child == null) {
			return 0;
		}

		final int rightMargin = Math.max(0, parentWidth - child.x - child.width);
		return Math.max(1, targetParentWidth - child.x - rightMargin);
	}

	private static int anchoredX(Geometry child, int parentWidth, int targetParentWidth) {
		if (child.x + child.width / 2 < parentWidth / 2) {
			return child.x;
		}

		return anchoredRightX(child, parentWidth, targetParentWidth);
	}

	private static int anchoredRightX(Geometry child, int parentWidth, int targetParentWidth) {
		final int rightMargin = Math.max(0, parentWidth - child.x - child.width);
		return Math.max(0, targetParentWidth - rightMargin - child.width);
	}

	private static int minY(Geometry... geometries) {
		int value = Integer.MAX_VALUE;
		for (Geometry geometry : geometries) {
			if (geometry != null) {
				value = Math.min(value, geometry.y);
			}
		}
		return value == Integer.MAX_VALUE ? 0 : value;
	}

	private static int maxBottom(Geometry... geometries) {
		int value = 0;
		for (Geometry geometry : geometries) {
			if (geometry != null) {
				value = Math.max(value, geometry.y + geometry.height);
			}
		}
		return value;
	}

	private static boolean isMounted(Widget widget) {
		return widget != null && widget.getParent() != null && !widget.isSelfHidden();
	}

	private static void restoreWidget(Widget widget, WidgetState baseline, MutableResult result) {
		if (widget == null || baseline == null) {
			return;
		}

		applyTextGeometry(widget, baseline.geometry.x, baseline.geometry.y,
				baseline.geometry.width, baseline.geometry.height, baseline.sourceText, result);
		applyHidden(widget, baseline.hidden, result);
	}

	private static void restoreGeometry(Widget widget, Geometry geometry, MutableResult result) {
		if (widget != null && geometry != null) {
			applyGeometry(widget, geometry.x, geometry.y, geometry.width, geometry.height, result);
		}
	}

	private static void applyTextGeometry(Widget widget, int x, int y, int width, int height, String text, MutableResult result) {
		if (widget == null) {
			return;
		}

		boolean changed = applyGeometryFields(widget, x, y, width, height);
		if (text != null && !text.equals(widget.getText())) {
			widget.setText(text);
			changed = true;
		}
		finishMutation(widget, changed, result);
	}

	private static void applyGeometry(Widget widget, int x, int y, int width, int height, MutableResult result) {
		if (widget == null) {
			return;
		}

		finishMutation(widget, applyGeometryFields(widget, x, y, width, height), result);
	}

	private static boolean applyGeometryFields(Widget widget, int x, int y, int width, int height) {
		boolean changed = false;
		if (widget.getOriginalX() != x) {
			widget.setOriginalX(x);
			changed = true;
		}
		if (widget.getOriginalY() != y) {
			widget.setOriginalY(y);
			changed = true;
		}
		if (widget.getOriginalWidth() != width) {
			widget.setOriginalWidth(width);
			changed = true;
		}
		if (widget.getOriginalHeight() != height) {
			widget.setOriginalHeight(height);
			changed = true;
		}
		return changed;
	}

	private static void applyHidden(Widget widget, boolean hidden, MutableResult result) {
		if (widget == null || widget.isSelfHidden() == hidden) {
			return;
		}

		widget.setHidden(hidden);
		finishMutation(widget, true, result);
	}

	private static void finishMutation(Widget widget, boolean changed, MutableResult result) {
		if (!changed) {
			return;
		}

		widget.revalidate();
		result.mutations++;
		result.revalidates++;
	}

	private static final class OptionsState {
		private final Widget root;
		private final Geometry rootGeometry;
		private final Map<Widget, WidgetState> children = new IdentityHashMap<>();

		private OptionsState(Widget root) {
			this.root = root;
			this.rootGeometry = new Geometry(root);
		}

		private void captureChildren(Widget[] widgets) {
			if (widgets == null) {
				return;
			}

			for (Widget widget : widgets) {
				if (widget == null) {
					continue;
				}

				final WidgetState current = children.get(widget);
				if (current != null && current.renderedText != null && !current.renderedText.equals(widget.getText())) {
					children.remove(widget);
				}

				if (!children.containsKey(widget)) {
					final WidgetState state = new WidgetState(widget);
					if (state.geometry.hasSize()) {
						children.put(widget, state);
					}
				}
			}
		}
	}

	private static final class WidgetState {
		private final Geometry geometry;
		private final String sourceText;
		private final boolean hidden;
		private String renderedText;

		private WidgetState(Widget widget) {
			geometry = new Geometry(widget);
			sourceText = widget.getText();
			hidden = widget.isSelfHidden();
			renderedText = sourceText;
		}
	}

	private static final class OptionRow {
		private final Widget widget;
		private final WidgetState baseline;
		private final String text;
		private int y;
		private int height;

		private OptionRow(Widget widget, WidgetState baseline, String text) {
			this.widget = widget;
			this.baseline = baseline;
			this.text = text;
		}
	}

	private static final class GraphicLayout {
		private final Widget widget;
		private final WidgetState baseline;
		private final int x;

		private GraphicLayout(Widget widget, WidgetState baseline, int x) {
			this.widget = widget;
			this.baseline = baseline;
			this.x = x;
		}
	}

	private static final class PortraitState {
		private final Widget root;
		private final Widget safe;
		private final Widget head;
		private final Widget content;
		private final Widget name;
		private final Widget continueWidget;
		private final Widget text;
		private final Geometry rootGeometry;
		private final Geometry safeGeometry;
		private final Geometry headGeometry;
		private final Geometry contentGeometry;
		private final Geometry nameGeometry;
		private final Geometry continueGeometry;
		private final Geometry textGeometry;

		private PortraitState(
				Widget root,
				Widget safe,
				Widget head,
				Widget content,
				Widget name,
				Widget continueWidget,
				Widget text) {
			this.root = root;
			this.safe = safe;
			this.head = head;
			this.content = content;
			this.name = name;
			this.continueWidget = continueWidget;
			this.text = text;
			this.rootGeometry = Geometry.of(root);
			this.safeGeometry = Geometry.of(safe);
			this.headGeometry = Geometry.of(head);
			this.contentGeometry = Geometry.of(content);
			this.nameGeometry = Geometry.of(name);
			this.continueGeometry = Geometry.of(continueWidget);
			this.textGeometry = Geometry.of(text);
		}

		private boolean isComplete() {
			return rootGeometry != null
					&& safeGeometry != null
					&& headGeometry != null
					&& contentGeometry != null
					&& nameGeometry != null
					&& continueGeometry != null
					&& textGeometry != null;
		}
	}

	private static final class Geometry {
		private final int x;
		private final int y;
		private final int width;
		private final int height;

		private Geometry(Widget widget) {
			x = widget.getOriginalX();
			y = widget.getOriginalY();
			width = widget.getOriginalWidth();
			height = widget.getOriginalHeight();
		}

		private boolean hasSize() {
			return width > 0 && height > 0;
		}

		private static Geometry of(Widget widget) {
			return widget == null ? null : new Geometry(widget);
		}
	}

	private static final class MutableResult {
		private int mutations;
		private int revalidates;

		private Result build() {
			return mutations == 0 && revalidates == 0 ? Result.NONE : new Result(mutations, revalidates);
		}
	}

	public static final class Result {
		private static final Result NONE = new Result(0, 0);

		private final int mutations;
		private final int revalidates;

		private Result(int mutations, int revalidates) {
			this.mutations = mutations;
			this.revalidates = revalidates;
		}

		public int getMutations() {
			return mutations;
		}

		public int getRevalidates() {
			return revalidates;
		}
	}
}
