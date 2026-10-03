package com.bigboldchat.chat;

import java.awt.Rectangle;
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
	private static final int DIALOGUE_LINE_GAP = 0;
	private static final int DIALOGUE_SECTION_GAP = 16;
	private static final int SPRITE_SECTION_GAP = 8;
	private static final int STACK_PADDING = 10;
	private static final int STACK_GAP = 12;
	private static final int SPRITE_SIDE_TEXT_X = 96;
	private static final int SPRITE_ITEM_PADDING = 16;
	private static final int SPRITE_STACK_PADDING = 10;
	private static final int SPRITE_STACK_GAP = 12;

	private final Client client;

	private OptionsState optionsState;
	private PortraitState npcState;
	private PortraitState playerState;
	private SpriteState spriteState;

	public DialoguePrompts(Client client) {
		this.client = client;
	}

	public Result apply(int effectiveWidth, int effectiveBodyHeight) {
		if (effectiveWidth <= 0 || effectiveBodyHeight <= 0) {
			return reset();
		}

		final MutableResult result = new MutableResult();
		applyOptions(effectiveWidth, effectiveBodyHeight, result);
		npcState = applyPortrait(effectiveWidth, effectiveBodyHeight, false, npcState, result,
				InterfaceID.ChatLeft.UNIVERSE,
				InterfaceID.ChatLeft.SAFEZONE,
				InterfaceID.ChatLeft.HEAD,
				InterfaceID.ChatLeft.CONTENT,
				InterfaceID.ChatLeft.NAME,
				InterfaceID.ChatLeft.CONTINUE,
				InterfaceID.ChatLeft.TEXT);
		playerState = applyPortrait(effectiveWidth, effectiveBodyHeight, true, playerState, result,
				InterfaceID.ChatRight.UNIVERSE,
				InterfaceID.ChatRight.SAFEZONE,
				InterfaceID.ChatRight.HEAD,
				InterfaceID.ChatRight.CONTENT,
				InterfaceID.ChatRight.NAME,
				InterfaceID.ChatRight.CONTINUE,
				InterfaceID.ChatRight.TEXT);
		spriteState = applySprite(effectiveWidth, effectiveBodyHeight, spriteState, result);
		return result.build();
	}

	public Result reset() {
		final MutableResult result = new MutableResult();
		restoreOptions(result);
		npcState = restorePortrait(npcState, result);
		playerState = restorePortrait(playerState, result);
		spriteState = restoreSprite(spriteState, result);
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
				|| isMounted(client.getWidget(InterfaceID.ChatRight.UNIVERSE))
				|| isMounted(client.getWidget(InterfaceID.Objectbox.ITEM))
				|| isMounted(client.getWidget(InterfaceID.Objectbox.TEXT));
	}

	public boolean needsPortraitReconcile() {
		return npcState != null || playerState != null || spriteState != null
				|| isMounted(client.getWidget(InterfaceID.ChatLeft.UNIVERSE))
				|| isMounted(client.getWidget(InterfaceID.ChatRight.UNIVERSE))
				|| isMounted(client.getWidget(InterfaceID.Objectbox.ITEM))
				|| isMounted(client.getWidget(InterfaceID.Objectbox.TEXT));
	}

	private void applyOptions(int effectiveWidth, int effectiveBodyHeight, MutableResult result) {
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
		final int x = Math.max(0, (effectiveWidth - width) / 2);
		final int y = Math.max(0, (effectiveBodyHeight - height) / 2);
		final Position rootPosition = rootPosition(options, x, y, width, height);

		applyGeometry(options, rootPosition.x, rootPosition.y, width, height, result);
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
			graphics.add(new GraphicLayout(widget, baseline, anchoredX(geometry, state.rootGeometry.width, width)));
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

	private PortraitState applyPortrait(int effectiveWidth, int effectiveBodyHeight, boolean rightSide, PortraitState state,
			MutableResult result, int rootId, int safeId, int headId, int contentId, int nameId, int continueId, int textId) {
		final Widget root = client.getWidget(rootId);
		if (!isMounted(root)) {
			return restorePortrait(state, result);
		}

		if (state == null || state.root != root) {
			state = restorePortrait(state, result);
			state = new PortraitState(root, client.getWidget(safeId), client.getWidget(headId), client.getWidget(contentId),
					client.getWidget(nameId), client.getWidget(continueId), client.getWidget(textId));
		}

		if (!state.isComplete()) {
			return state;
		}

		state.captureText();

		final int horizontalInset = Math.max(0, ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width);
		final int verticalInset = Math.max(0, ChatboxGeometry.NATIVE_BODY_HEIGHT - state.rootGeometry.height);
		final int width = Math.max(1, effectiveWidth - horizontalInset);
		final int height = Math.max(1, effectiveBodyHeight - verticalInset);
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int contentWidth = fittedWidth(state.contentGeometry, state.safeGeometry.width, safeWidth);
		final int sideTextWidth = fittedWidth(state.textGeometry, state.contentGeometry.width, contentWidth);
		final int x = Math.max(0, (effectiveWidth - width) / 2);
		final int y = Math.max(0, (effectiveBodyHeight - height) / 2);
		final Position rootPosition = rootPosition(state.root, x, y, width, height);

		if (sideTextWidth < MIN_SIDE_TEXT_WIDTH) {
			applyStacked(state, rootPosition, width, height, result);
		} else {
			applySideBySide(state, rootPosition, width, height, rightSide, result);
		}

		return state;
	}

	private void applySideBySide(PortraitState state, Position rootPosition, int width, int height, boolean rightSide, MutableResult result) {
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int safeHeight = fittedHeight(state.safeGeometry, state.rootGeometry.height, height);
		final int contentWidth = fittedWidth(state.contentGeometry, state.safeGeometry.width, safeWidth);
		final int nameWidth = fittedWidth(state.nameGeometry, state.contentGeometry.width, contentWidth);
		final int textWidth = fittedWidth(state.textGeometry, state.contentGeometry.width, contentWidth);
		final int continueWidth = fittedWidth(state.continueGeometry, state.contentGeometry.width, contentWidth);
		final PortraitContentLayout content = portraitContentLayout(state, state.nameGeometry.x, state.textGeometry.x,
				state.continueGeometry.x, nameWidth, textWidth, continueWidth);
		final int contentHeight = Math.min(safeHeight, Math.max(state.contentGeometry.height, content.requiredHeight));

		applyPortraitRoot(state, rootPosition, width, height, result);
		applyGeometry(state.safe, state.safeGeometry.x, state.safeGeometry.y, safeWidth, safeHeight, result);

		final int headX = rightSide
				? anchoredRightX(state.headGeometry, state.safeGeometry.width, safeWidth)
				: state.headGeometry.x;
		final int headY = centeredY(state.headGeometry, state.safeGeometry.height, safeHeight);
		applyGeometry(state.head, headX, headY, state.headGeometry.width, state.headGeometry.height, result);

		final int contentOriginalY = positionY(state.content, Math.max(0, (safeHeight - contentHeight) / 2), safeHeight, contentHeight);
		applyGeometry(state.content, state.contentGeometry.x, contentOriginalY, contentWidth, contentHeight, result);
		applyPortraitContent(state, content, contentWidth, result);
	}

	private void applyStacked(PortraitState state, Position rootPosition, int width, int height, MutableResult result) {
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int safeHeight = fittedHeight(state.safeGeometry, state.rootGeometry.height, height);
		final int contentWidth = Math.max(1, safeWidth);
		final int contentPadding = Math.min(STACK_PADDING, Math.max(0, (contentWidth - 1) / 2));
		final int childWidth = Math.max(1, contentWidth - contentPadding * 2);
		final PortraitContentLayout content = portraitContentLayout(state, contentPadding, contentPadding, contentPadding,
				childWidth, childWidth, childWidth);
		final int contentHeight = Math.max(state.contentGeometry.height, content.requiredHeight);
		final int portraitHeight = Math.max(state.safeGeometry.height, state.headGeometry.y + state.headGeometry.height);
		final int groupHeight = portraitHeight + STACK_GAP + contentHeight;
		final int groupY = Math.max(0, (safeHeight - groupHeight) / 2);
		final int headX = Math.max(0, (safeWidth - state.headGeometry.width) / 2);
		final int headY = groupY + state.headGeometry.y;
		final int contentY = Math.min(Math.max(0, safeHeight - contentHeight), groupY + portraitHeight + STACK_GAP);
		final int contentOriginalY = positionY(state.content, contentY, safeHeight, contentHeight);

		applyPortraitRoot(state, rootPosition, width, height, result);
		applyGeometry(state.safe, state.safeGeometry.x, state.safeGeometry.y, safeWidth, safeHeight, result);
		applyGeometry(state.head, headX, headY, state.headGeometry.width, state.headGeometry.height, result);
		applyGeometry(state.content, 0, contentOriginalY, contentWidth, contentHeight, result);
		applyPortraitContent(state, content, contentWidth, result);
	}

	private static PortraitContentLayout portraitContentLayout(PortraitState state,
			int nameX, int textX, int continueX, int nameWidth, int textWidth, int continueWidth) {
		final int fittedTextWidth = Math.max(1, textWidth);
		final String text = wrapText(state.text, normalizeDialogueText(state.sourceText), fittedTextWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		final int nameY = state.nameGeometry.y;
		final int textY = nameY + state.nameGeometry.height + DIALOGUE_SECTION_GAP;
		final int continueY = textY + textHeight + DIALOGUE_SECTION_GAP;
		final int requiredHeight = Math.max(nameY + state.nameGeometry.height, continueY + state.continueGeometry.height);
		return new PortraitContentLayout(nameX, textX, continueX, Math.max(1, nameWidth), fittedTextWidth,
				Math.max(1, continueWidth), nameY, textY, text, textHeight, continueY, requiredHeight);
	}

	private static void applyPortraitContent(PortraitState state, PortraitContentLayout layout, int contentWidth, MutableResult result) {
		final int nameWidth = Math.min(layout.nameWidth, Math.max(1, contentWidth - layout.nameX));
		final int textWidth = Math.min(layout.textWidth, Math.max(1, contentWidth - layout.textX));
		final int continueWidth = Math.min(layout.continueWidth, Math.max(1, contentWidth - layout.continueX));

		applyGeometry(state.name, layout.nameX, layout.nameY, nameWidth, state.nameGeometry.height, result);
		applyTextGeometry(state.text, layout.textX, layout.textY, textWidth, layout.textHeight, layout.text, result);
		applyGeometry(state.continueWidget, layout.continueX, layout.continueY, continueWidth, state.continueGeometry.height, result);
		state.renderedText = layout.text;
	}

	private SpriteState applySprite(int effectiveWidth, int effectiveBodyHeight, SpriteState state, MutableResult result) {
		final Widget item = client.getWidget(InterfaceID.Objectbox.ITEM);
		final Widget text = client.getWidget(InterfaceID.Objectbox.TEXT);
		if (!isMounted(item) && !isMounted(text)) {
			return restoreSprite(state, result);
		}

		final Widget root = item != null && item.getParent() != null
				? item.getParent()
				: text != null ? text.getParent() : null;
		if (!isMounted(root)) {
			return restoreSprite(state, result);
		}

		final Widget host = root.getParent();
		final Widget continueWidget = findSpriteContinue(root, text);
		if (state == null || state.host != host || state.root != root || state.item != item || state.text != text
				|| state.continueWidget != continueWidget) {
			state = restoreSprite(state, result);
			state = new SpriteState(host, root, item, text, continueWidget);
		}

		if (!state.isComplete()) {
			return state;
		}

		state.captureText();

		final int horizontalInset = Math.max(0, ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width);
		final int width = Math.max(1, effectiveWidth - horizontalInset);
		final int sideTextWidth = Math.max(1, width - SPRITE_SIDE_TEXT_X
				- Math.max(0, state.rootGeometry.width - state.textGeometry.x - state.textGeometry.width));

		final SpriteLayout layout = sideTextWidth >= MIN_SIDE_TEXT_WIDTH
				? spriteSideBySideLayout(state, width)
				: spriteStackedLayout(state, width);
		final int x = Math.max(0, (effectiveWidth - width) / 2);
		final int y = Math.max(0, (effectiveBodyHeight - layout.height) / 2);
		final Position hostPosition = rootPosition(state.host, x, y, width, layout.height);

		applySpriteHost(state, hostPosition, width, layout.height, result);
		applySpriteRoot(state, width, layout.height, result);
		applyGeometry(state.item, layout.itemX, layout.itemY,
				state.itemGeometry.width, state.itemGeometry.height, result);
		applyTextGeometry(state.text, layout.textX, layout.textY,
				layout.textWidth, layout.textHeight, layout.text, result);
		applyGeometry(state.continueWidget, layout.continueX, layout.continueY,
				layout.continueWidth, state.continueGeometry.height, result);
		state.renderedText = layout.text;
		return state;
	}

	private static SpriteLayout spriteSideBySideLayout(SpriteState state, int width) {
		final int rightMargin = Math.max(0,
				state.rootGeometry.width - state.textGeometry.x - state.textGeometry.width);
		final int textWidth = Math.max(1, width - SPRITE_SIDE_TEXT_X - rightMargin);
		final String text = wrapText(state.text, normalizeDialogueText(state.sourceText), textWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		final int blockHeight = textHeight + SPRITE_SECTION_GAP + state.continueGeometry.height;
		final int height = Math.max(state.rootGeometry.height,
				Math.max(state.itemGeometry.height + SPRITE_ITEM_PADDING * 2, blockHeight + SPRITE_ITEM_PADDING * 2));
		final int itemY = Math.max(0, (height - state.itemGeometry.height) / 2);
		final int textY = Math.max(0, (height - blockHeight) / 2);
		final int continueY = textY + textHeight + SPRITE_SECTION_GAP;
		return new SpriteLayout(state.itemGeometry.x, itemY,
				SPRITE_SIDE_TEXT_X, textY, textWidth, textHeight, text,
				SPRITE_SIDE_TEXT_X, continueY, textWidth, height);
	}

	private static SpriteLayout spriteStackedLayout(SpriteState state, int width) {
		final int contentWidth = Math.max(1, width - SPRITE_STACK_PADDING * 2);
		final String text = wrapText(state.text, normalizeDialogueText(state.sourceText), contentWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		final int itemX = Math.max(0, (width - state.itemGeometry.width) / 2);
		final int itemY = SPRITE_STACK_PADDING;
		final int textY = itemY + state.itemGeometry.height + SPRITE_STACK_GAP;
		final int continueY = textY + textHeight + SPRITE_SECTION_GAP;
		final int height = continueY + state.continueGeometry.height + SPRITE_STACK_PADDING;
		return new SpriteLayout(itemX, itemY,
				SPRITE_STACK_PADDING, textY, contentWidth, textHeight, text,
				SPRITE_STACK_PADDING, continueY, contentWidth, height);
	}

	private static Widget findSpriteContinue(Widget root, Widget messageText) {
		if (root == null) {
			return null;
		}

		Widget fallback = null;
		final Widget[] dynamic = root.getDynamicChildren();
		if (dynamic != null) {
			for (Widget child : dynamic) {
				if (child == null || child == messageText || child.getType() != WidgetType.TEXT) {
					continue;
				}

				final String text = child.getText();
				if (text != null && text.toLowerCase().contains("click here to continue")) {
					return child;
				}

				if (fallback == null && child.getOriginalHeight() > 0) {
					fallback = child;
				}
			}
		}
		return fallback;
	}

	private static void applySpriteRoot(SpriteState state, int width, int height, MutableResult result) {
		final Widget root = state.root;
		boolean changed = applyGeometryFields(root, 0, 0, width, height);

		if (!state.rootForced || root.getRelativeX() != 0 || root.getRelativeY() != 0) {
			root.setForcedPosition(0, 0);
			state.rootForced = true;
			changed = true;
		}

		if (!changed) {
			return;
		}

		root.revalidate();
		result.mutations++;
		result.revalidates++;
	}

	private static void applySpriteHost(SpriteState state, Position position, int width, int height, MutableResult result) {
		final Widget host = state.host;
		boolean changed = applyGeometryFields(host, position.x, position.y, width, height);

		if (position.force
				&& (!state.hostForced || host.getRelativeX() != position.relativeX || host.getRelativeY() != position.relativeY)) {
			host.setForcedPosition(position.relativeX, position.relativeY);
			state.hostForced = true;
			changed = true;
		}

		if (!changed) {
			return;
		}

		host.revalidate();
		result.mutations++;
		result.revalidates++;
	}

	private Position rootPosition(Widget widget, int x, int y, int width, int height) {
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget parent = widget != null ? widget.getParent() : null;
		final Rectangle universeBounds = universe != null ? universe.getBounds() : null;
		if (!usableBounds(universeBounds) || parent == null) {
			return new Position(widget.getOriginalX(), widget.getOriginalY(),
					widget.getRelativeX(), widget.getRelativeY(), false);
		}

		final Rectangle parentBounds = parent.getBounds();
		final Rectangle widgetBounds = widget.getBounds();
		final int parentX;
		final int parentY;
		if (usableOrigin(parentBounds)) {
			parentX = parentBounds.x;
			parentY = parentBounds.y;
		} else if (usableOrigin(widgetBounds)) {
			parentX = widgetBounds.x - widget.getRelativeX();
			parentY = widgetBounds.y - widget.getRelativeY();
		} else {
			return new Position(widget.getOriginalX(), widget.getOriginalY(),
					widget.getRelativeX(), widget.getRelativeY(), false);
		}

		final int parentWidth = Math.max(1, parent.getWidth());
		final int parentHeight = Math.max(1, parent.getHeight());
		final int relativeX = universeBounds.x - parentX + x;
		final int relativeY = universeBounds.y - parentY + y;
		return new Position(
				positionX(widget, relativeX, parentWidth, width),
				positionY(widget, relativeY, parentHeight, height),
				relativeX, relativeY, true);
	}

	private static boolean usableBounds(Rectangle bounds) {
		return usableOrigin(bounds) && bounds.width > 0 && bounds.height > 0;
	}

	private static boolean usableOrigin(Rectangle bounds) {
		return bounds != null && bounds.x >= 0 && bounds.y >= 0;
	}


	private static void applyPortraitRoot(PortraitState state, Position position, int width, int height, MutableResult result) {
		final Widget root = state.root;
		boolean changed = applyGeometryFields(root, position.x, position.y, width, height);

		if (position.force
				&& (!state.rootForced || root.getRelativeX() != position.relativeX || root.getRelativeY() != position.relativeY)) {
			root.setForcedPosition(position.relativeX, position.relativeY);
			state.rootForced = true;
			changed = true;
		}

		if (!changed) {
			return;
		}

		root.revalidate();
		result.mutations++;
		result.revalidates++;
	}

	private SpriteState restoreSprite(SpriteState state, MutableResult result) {
		if (state == null) {
			return null;
		}

		if (state.rootForced) {
			state.root.setForcedPosition(-1, -1);
			state.rootForced = false;
			state.root.revalidate();
			result.mutations++;
			result.revalidates++;
		}

		if (state.hostForced) {
			state.host.setForcedPosition(-1, -1);
			state.hostForced = false;
			state.host.revalidate();
			result.mutations++;
			result.revalidates++;
		}

		restoreGeometry(state.host, state.hostGeometry, result);
		restoreGeometry(state.root, state.rootGeometry, result);
		restoreGeometry(state.item, state.itemGeometry, result);
		applyTextGeometry(state.text, state.textGeometry.x, state.textGeometry.y,
				state.textGeometry.width, state.textGeometry.height, state.sourceText, result);
		restoreGeometry(state.continueWidget, state.continueGeometry, result);
		return null;
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

		if (state.rootForced) {
			state.root.setForcedPosition(-1, -1);
			state.rootForced = false;
			state.root.revalidate();
			result.mutations++;
			result.revalidates++;
		}

		restoreGeometry(state.root, state.rootGeometry, result);
		restoreGeometry(state.safe, state.safeGeometry, result);
		restoreGeometry(state.head, state.headGeometry, result);
		restoreGeometry(state.content, state.contentGeometry, result);
		restoreGeometry(state.name, state.nameGeometry, result);
		restoreGeometry(state.continueWidget, state.continueGeometry, result);
		applyTextGeometry(state.text, state.textGeometry.x, state.textGeometry.y, state.textGeometry.width, state.textGeometry.height, state.sourceText, result);
		return null;
	}

	private static String normalizeDialogueText(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		return text.replace("<br>", " ")
				.replaceAll("\\s+", " ")
				.trim();
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

	private static int dialogueTextHeight(Widget widget, String text) {
		final FontTypeFace font = widget != null ? widget.getFont() : null;
		final int lineHeight = font != null
				? Math.max(1, font.getBaseline() + DIALOGUE_LINE_GAP)
				: 16;
		return Math.max(1, lineCount(text) * lineHeight);
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

	private static int positionX(Widget widget, int relativeX, int parentWidth, int width) {
		if (widget != null && widget.getXPositionMode() == WidgetPositionMode.ABSOLUTE_CENTER) {
			return relativeX - (parentWidth - width) / 2;
		}

		return relativeX;
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

	private static int fittedHeight(Geometry child, int parentHeight, int targetParentHeight) {
		if (child == null) {
			return 0;
		}

		final int bottomMargin = Math.max(0, parentHeight - child.y - child.height);
		return Math.max(1, targetParentHeight - child.y - bottomMargin);
	}

	private static int centeredY(Geometry child, int parentHeight, int targetParentHeight) {
		final int nativeCenteredY = Math.max(0, (parentHeight - child.height) / 2);
		final int centerOffset = child.y - nativeCenteredY;
		return Math.max(0, (targetParentHeight - child.height) / 2 + centerOffset);
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
		finishGeometry(widget, changed, x, y, width, height, result);
	}

	private static void applyGeometry(Widget widget, int x, int y, int width, int height, MutableResult result) {
		if (widget == null) {
			return;
		}

		finishGeometry(widget, applyGeometryFields(widget, x, y, width, height), x, y, width, height, result);
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

	private static void finishGeometry(Widget widget, boolean changed, int x, int y, int width, int height,
			MutableResult result) {
		final boolean stale = !widget.isHidden() && resolvedGeometryStale(widget, x, y, width, height);
		if (!changed && !stale) {
			return;
		}

		widget.revalidate();
		if (changed) {
			result.mutations++;
		}
		result.revalidates++;
	}

	private static boolean resolvedGeometryStale(Widget widget, int x, int y, int width, int height) {
		if (widget.getWidth() != width || widget.getHeight() != height) {
			return true;
		}

		final Widget parent = widget.getParent();
		if (parent == null) {
			return false;
		}

		final int expectedX = resolvedPosition(x, widget.getXPositionMode(), parent.getWidth(), width);
		final int expectedY = resolvedPosition(y, widget.getYPositionMode(), parent.getHeight(), height);
		return expectedX != Integer.MIN_VALUE && widget.getRelativeX() != expectedX
				|| expectedY != Integer.MIN_VALUE && widget.getRelativeY() != expectedY;
	}

	private static int resolvedPosition(int original, int mode, int parentSize, int size) {
		switch (mode) {
			case WidgetPositionMode.ABSOLUTE_LEFT:
				return original;
			case WidgetPositionMode.ABSOLUTE_CENTER:
				return original + (parentSize - size) / 2;
			case WidgetPositionMode.ABSOLUTE_RIGHT:
				return parentSize - size - original;
			case WidgetPositionMode.LEFT_16384THS:
				return (int) ((long) original * parentSize >> 14);
			case WidgetPositionMode.CENTER_16384THS:
				return (int) ((long) original * parentSize >> 14) + (parentSize - size) / 2;
			case WidgetPositionMode.RIGHT_16384THS:
				return parentSize - size - (int) ((long) original * parentSize >> 14);
			default:
				return Integer.MIN_VALUE;
		}
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

	private static final class SpriteLayout {
		private final int itemX;
		private final int itemY;
		private final int textX;
		private final int textY;
		private final int textWidth;
		private final int textHeight;
		private final String text;
		private final int continueX;
		private final int continueY;
		private final int continueWidth;
		private final int height;

		private SpriteLayout(int itemX, int itemY, int textX, int textY, int textWidth, int textHeight,
				String text, int continueX, int continueY, int continueWidth, int height) {
			this.itemX = itemX;
			this.itemY = itemY;
			this.textX = textX;
			this.textY = textY;
			this.textWidth = textWidth;
			this.textHeight = textHeight;
			this.text = text;
			this.continueX = continueX;
			this.continueY = continueY;
			this.continueWidth = continueWidth;
			this.height = height;
		}
	}

	private static final class SpriteState {
		private final Widget host;
		private final Widget root;
		private final Widget item;
		private final Widget text;
		private final Widget continueWidget;
		private final Geometry hostGeometry;
		private final Geometry rootGeometry;
		private final Geometry itemGeometry;
		private final Geometry textGeometry;
		private final Geometry continueGeometry;
		private String sourceText;
		private String renderedText;
		private boolean rootForced;
		private boolean hostForced;

		private SpriteState(Widget host, Widget root, Widget item, Widget text, Widget continueWidget) {
			this.host = host;
			this.root = root;
			this.item = item;
			this.text = text;
			this.continueWidget = continueWidget;
			this.hostGeometry = Geometry.of(host);
			this.rootGeometry = Geometry.of(root);
			this.itemGeometry = Geometry.of(item);
			this.textGeometry = Geometry.of(text);
			this.continueGeometry = Geometry.of(continueWidget);
			this.sourceText = text != null ? text.getText() : null;
			this.renderedText = sourceText;
		}

		private void captureText() {
			if (text == null) {
				return;
			}

			final String current = text.getText();
			if (renderedText == null || !renderedText.equals(current)) {
				sourceText = current;
				renderedText = current;
			}
		}

		private boolean isComplete() {
			return hostGeometry != null && rootGeometry != null && itemGeometry != null
					&& textGeometry != null && continueGeometry != null;
		}
	}

	private static final class PortraitContentLayout {
		private final int nameX;
		private final int textX;
		private final int continueX;
		private final int nameWidth;
		private final int textWidth;
		private final int continueWidth;
		private final int nameY;
		private final int textY;
		private final String text;
		private final int textHeight;
		private final int continueY;
		private final int requiredHeight;

		private PortraitContentLayout(int nameX, int textX, int continueX, int nameWidth, int textWidth, int continueWidth,
				int nameY, int textY, String text, int textHeight, int continueY, int requiredHeight) {
			this.nameX = nameX;
			this.textX = textX;
			this.continueX = continueX;
			this.nameWidth = nameWidth;
			this.textWidth = textWidth;
			this.continueWidth = continueWidth;
			this.nameY = nameY;
			this.textY = textY;
			this.text = text;
			this.textHeight = textHeight;
			this.continueY = continueY;
			this.requiredHeight = requiredHeight;
		}
	}

	private static final class Position {
		private final int x;
		private final int y;
		private final int relativeX;
		private final int relativeY;
		private final boolean force;

		private Position(int x, int y, int relativeX, int relativeY, boolean force) {
			this.x = x;
			this.y = y;
			this.relativeX = relativeX;
			this.relativeY = relativeY;
			this.force = force;
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
		private String sourceText;
		private String renderedText;
		private boolean rootForced;

		private PortraitState(Widget root, Widget safe, Widget head, Widget content, Widget name, Widget continueWidget, Widget text) {
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
			this.sourceText = text != null ? text.getText() : null;
			this.renderedText = sourceText;
		}

		private void captureText() {
			if (text == null) {
				return;
			}

			final String current = text.getText();
			if (renderedText == null || !renderedText.equals(current)) {
				sourceText = current;
				renderedText = current;
			}
		}

		private boolean isComplete() {
			return rootGeometry != null && safeGeometry != null && headGeometry != null && contentGeometry != null
					&& nameGeometry != null && continueGeometry != null && textGeometry != null;
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
