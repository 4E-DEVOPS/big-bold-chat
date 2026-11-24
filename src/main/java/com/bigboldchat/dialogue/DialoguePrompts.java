package com.bigboldchat.dialogue;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.bigboldchat.chat.FontGlyphCorrections;
import com.bigboldchat.chatbox.ChatboxGeometry;
import com.bigboldchat.config.ChatFont;

import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;

/**
 * Measures and applies dialogue and prompt layouts within the effective
 * viewport while retaining the original widget state for restoration.
 */
public final class DialoguePrompts {
	private static final int MIN_SIDE_TEXT_WIDTH = 160;
	private static final int MIN_OPTION_ROW_GAP = 4;
	private static final int MIN_DIALOGUE_GAP = 6;
	private static final int MIN_CONTENT_PADDING = 4;

	private static final int MESSAGE_CONTENT_INSET = 1;
	private static final int MESSAGE_TEXT_PADDING = 4;

	private static final int OPTION_OUTER_PADDING = 10;
	private static final int CONTINUE_GLYPH_PADDING = 4;
	private static final int OPTION_GAP_DIVISOR = 4;

	private static final int DIALOGUE_LINE_GAP = 0;
	private static final int STACK_PADDING = 10;
	private static final int STACK_GAP = 12;
	private static final int PORTRAIT_RENDER_PADDING = 12;
	private static final int PORTRAIT_ROOT_POSITION_TOLERANCE = 16;

	private static final int SPRITE_SIDE_TEXT_X = 96;
	private static final int SPRITE_ITEM_PADDING = 16;
	private static final int SPRITE_STACK_PADDING = 10;
	private static final int SPRITE_STACK_GAP = 12;

	private final Client client;
	private final Map<Integer, FontTypeFace> fontCache = new HashMap<>();

	private OptionsState optionsState;
	private PortraitState npcState;
	private PortraitState playerState;
	private MessageState messageState;
	private SpriteState spriteState;

	private int viewportOffsetX;
	private int viewportOffsetY;
	private boolean clipTopOverflow;

	public DialoguePrompts(Client client) {
		this.client = client;
	}

	/**
	 * ================================================================
	 * DIALOGUE APPLICATION & RESTORATION
	 * ================================================================
	 */
	public Result apply(int effectiveWidth, int effectiveBodyHeight) {
		return apply(effectiveWidth, effectiveBodyHeight, false);
	}

	public Result apply(int effectiveWidth, int effectiveBodyHeight, boolean constrainedWrap) {
		return apply(effectiveWidth, effectiveBodyHeight, constrainedWrap, false);
	}

	public Result apply(int effectiveWidth, int effectiveBodyHeight, boolean constrainedWrap, boolean clipTopOverflow) {
		return applyViewport(effectiveWidth, effectiveBodyHeight, effectiveWidth, effectiveBodyHeight, 0, 0, constrainedWrap, clipTopOverflow);
	}

	public Result applyViewport(int layoutWidth, int layoutBodyHeight, int viewportWidth, int viewportBodyHeight, int clipX, int clipY, boolean constrainedWrap) {
		return applyViewport(layoutWidth, layoutBodyHeight, viewportWidth, viewportBodyHeight, clipX, clipY, constrainedWrap, false);
	}

	private Result applyViewport(int layoutWidth, int layoutBodyHeight, int viewportWidth, int viewportBodyHeight, int clipX, int clipY, boolean constrainedWrap, boolean clipTopOverflow) {
		if (layoutWidth <= 0 || layoutBodyHeight <= 0 || viewportWidth <= 0 || viewportBodyHeight <= 0) {
			return reset();
		}

		viewportOffsetX = Math.max(0, clipX);
		viewportOffsetY = Math.max(0, clipY);
		this.clipTopOverflow = clipTopOverflow;

		final MutableResult result = new MutableResult();
		try {
			applyOptions(layoutWidth, layoutBodyHeight, result);
			npcState = applyPortrait(layoutWidth, layoutBodyHeight, false, constrainedWrap, npcState, result, InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatLeft.SAFEZONE, InterfaceID.ChatLeft.HEAD, InterfaceID.ChatLeft.CONTENT, InterfaceID.ChatLeft.NAME, InterfaceID.ChatLeft.CONTINUE, InterfaceID.ChatLeft.TEXT);
			playerState = applyPortrait(layoutWidth, layoutBodyHeight, true, constrainedWrap, playerState, result, InterfaceID.ChatRight.UNIVERSE, InterfaceID.ChatRight.SAFEZONE, InterfaceID.ChatRight.HEAD, InterfaceID.ChatRight.CONTENT, InterfaceID.ChatRight.NAME, InterfaceID.ChatRight.CONTINUE, InterfaceID.ChatRight.TEXT);
			messageState = applyMessage(layoutWidth, layoutBodyHeight, constrainedWrap, messageState, result);
			spriteState = applySprite(layoutWidth, layoutBodyHeight, constrainedWrap, spriteState, result);
		} finally {
			viewportOffsetX = 0;
			viewportOffsetY = 0;
			this.clipTopOverflow = false;
		}
		return result.build();
	}

	public Result reset() {
		final MutableResult result = new MutableResult();
		restoreOptions(result);
		npcState = restorePortrait(npcState, result);
		playerState = restorePortrait(playerState, result);
		messageState = restoreMessage(messageState, result);
		spriteState = restoreSprite(spriteState, result);
		return result.build();
	}

	/**
	 * ================================================================
	 * ACTIVE DIALOGUE SURFACES
	 * ================================================================
	 */
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

	// Expose mounted item widgets for read-only clipping and fit-outline checks.
	Widget itemTitleWidget() {
		return activeItemLeaves() ? spriteState.titleWidget : null;
	}

	Widget itemContinueWidget() {
		return activeItemLeaves() ? spriteState.continueWidget : null;
	}

	private boolean activeItemLeaves() {
		return spriteState != null && isMounted(spriteState.root) && (spriteState.item != null && spriteState.item == client.getWidget(InterfaceID.Objectbox.ITEM) || spriteState.text != null && spriteState.text == client.getWidget(InterfaceID.Objectbox.TEXT));
	}

	// Identifies visible dialogue surfaces independently of fitting needs.
	public enum Surface {
		NONE,
		OPTIONS,
		NPC,
		PLAYER,
		MESSAGEBOX,
		ITEM
	}

	/*
	 * Identify the active dialogue surface without changing lifecycle state.
	 * Options, portraits, messageboxes and items share the temporary dialogue
	 * center. A temporarily unmounted root is not itself a close event.
	 */
	public Surface activeSurface() {
		if (isVisibleMounted(options())) {
			return Surface.OPTIONS;
		}
		if (isVisibleMounted(client.getWidget(InterfaceID.ChatLeft.UNIVERSE))) {
			return Surface.NPC;
		}
		if (isVisibleMounted(client.getWidget(InterfaceID.ChatRight.UNIVERSE))) {
			return Surface.PLAYER;
		}
		if (isVisibleMounted(client.getWidget(InterfaceID.Messagebox.UNIVERSE))) {
			return Surface.MESSAGEBOX;
		}
		if (isVisibleMounted(client.getWidget(InterfaceID.Objectbox.ITEM)) || isVisibleMounted(client.getWidget(InterfaceID.Objectbox.TEXT))) {
			return Surface.ITEM;
		}
		return Surface.NONE;
	}

	public boolean isActive() {
		return activeSurface() != Surface.NONE;
	}

	public boolean needsPortraitReconcile() {
		return npcState != null || playerState != null || messageState != null || spriteState != null || isMounted(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)) || isMounted(client.getWidget(InterfaceID.ChatRight.UNIVERSE)) || isMounted(client.getWidget(InterfaceID.Messagebox.UNIVERSE)) || isMounted(client.getWidget(InterfaceID.Objectbox.ITEM)) || isMounted(client.getWidget(InterfaceID.Objectbox.TEXT));
	}

	public boolean hasContinuePrompt() {
		return hasContinueText(client.getWidget(InterfaceID.ChatLeft.CONTINUE)) || hasContinueText(client.getWidget(InterfaceID.ChatRight.CONTINUE)) || hasContinueText(client.getWidget(InterfaceID.Messagebox.CONTINUE)) || spriteState != null && hasContinueText(spriteState.continueWidget);
	}

	private static boolean hasContinueText(Widget widget) {
		final String text = widget != null ? normalizeDialogueText(widget.getText()) : null;
		return isMounted(widget) && text != null && !text.isEmpty();
	}

	/**
	 * ================================================================
	 * FIT MEASUREMENT
	 * ================================================================
	 */
	public int fitFingerprint() {
		int fingerprint = 1;
		if (optionsState != null && isMounted(optionsState.root)) {
			fingerprint = 31 * fingerprint + 1;
			final List<Map.Entry<Widget, WidgetState>> rows = new ArrayList<>(optionsState.children.entrySet());
			rows.sort(Comparator.comparingInt(row -> row.getValue().geometry.y));
			for (Map.Entry<Widget, WidgetState> row : rows) {
				fingerprint = 31 * fingerprint + widgetFontId(row.getKey());
				fingerprint = 31 * fingerprint + normalizedHash(row.getValue().sourceText);
			}
		}
		if (npcState != null && isMounted(npcState.root)) {
			fingerprint = 31 * fingerprint + 2;
			fingerprint = 31 * fingerprint + widgetFontId(npcState.name);
			fingerprint = 31 * fingerprint + widgetFontId(npcState.text);
			fingerprint = 31 * fingerprint + widgetFontId(npcState.continueWidget);
			fingerprint = 31 * fingerprint + normalizedHash(npcState.nameSourceText);
			fingerprint = 31 * fingerprint + normalizedHash(npcState.sourceText);
			fingerprint = 31 * fingerprint + normalizedHash(npcState.continueSourceText);
		}
		if (playerState != null && isMounted(playerState.root)) {
			fingerprint = 31 * fingerprint + 3;
			fingerprint = 31 * fingerprint + widgetFontId(playerState.name);
			fingerprint = 31 * fingerprint + widgetFontId(playerState.text);
			fingerprint = 31 * fingerprint + widgetFontId(playerState.continueWidget);
			fingerprint = 31 * fingerprint + normalizedHash(playerState.nameSourceText);
			fingerprint = 31 * fingerprint + normalizedHash(playerState.sourceText);
			fingerprint = 31 * fingerprint + normalizedHash(playerState.continueSourceText);
		}
		if (messageState != null && isMounted(messageState.root)) {
			fingerprint = 31 * fingerprint + 4;
			fingerprint = 31 * fingerprint + widgetFontId(messageState.text);
			fingerprint = 31 * fingerprint + widgetFontId(messageState.continueWidget);
			fingerprint = 31 * fingerprint + normalizedHash(messageState.sourceText);
			fingerprint = 31 * fingerprint + normalizedHash(messageState.continueSourceText);
		}
		if (spriteState != null && isMounted(spriteState.root)) {
			fingerprint = 31 * fingerprint + 5;
			fingerprint = 31 * fingerprint + widgetFontId(spriteState.titleWidget);
			fingerprint = 31 * fingerprint + widgetFontId(spriteState.text);
			fingerprint = 31 * fingerprint + widgetFontId(spriteState.continueWidget);
			fingerprint = 31 * fingerprint + normalizedHash(spriteState.titleSourceText);
			fingerprint = 31 * fingerprint + normalizedHash(spriteState.sourceText);
			fingerprint = 31 * fingerprint + normalizedHash(spriteState.continueSourceText);
		}
		return fingerprint;
	}

	private static int normalizedHash(String text) {
		final String normalized = normalizeDialogueText(text);
		return normalized != null ? normalized.hashCode() : 0;
	}

	private static int widgetFontId(Widget widget) {
		return widget != null ? widget.getFontId() : -1;
	}

	public FitRequirement measureFit(int effectiveWidth) {
		return measureFit(effectiveWidth, false);
	}

	public FitRequirement measureFit(int effectiveWidth, boolean constrainedWrap) {
		if (effectiveWidth <= 0) {
			return FitRequirement.NONE;
		}

		FitRequirement requirement = FitRequirement.NONE;

		if (optionsState != null && isMounted(optionsState.root)) {
			optionsState.captureChildren(optionsState.root.getDynamicChildren());
			requirement = requirement.combine(optionFitRequirement(optionsState, effectiveWidth));
		}

		if (npcState != null && isMounted(npcState.root)) {
			npcState.captureText();
			requirement = requirement.combine(portraitFitRequirement(npcState, effectiveWidth, constrainedWrap));
		}

		if (playerState != null && isMounted(playerState.root)) {
			playerState.captureText();
			requirement = requirement.combine(portraitFitRequirement(playerState, effectiveWidth, constrainedWrap));
		}

		if (messageState != null && isMounted(messageState.root)) {
			messageState.captureText();
			requirement = requirement.combine(messageFitRequirement(messageState, effectiveWidth, constrainedWrap));
		}

		if (spriteState != null && isMounted(spriteState.root)) {
			spriteState.captureText();
			requirement = requirement.combine(spriteFitRequirement(spriteState, effectiveWidth, constrainedWrap));
		}

		return requirement;
	}

	private static FitRequirement optionFitRequirement(OptionsState state, int effectiveWidth) {
		final int inset = Math.max(0, (ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width) / 2);
		final int width = Math.max(1, effectiveWidth - inset * 2);
		final List<OptionRow> rows = collectOptionRows(state, width);
		if (rows.isEmpty()) {
			return FitRequirement.NONE;
		}

		final OptionSpacing preferred = optionSpacing(state, rows, false);
		final OptionSpacing minimum = optionSpacing(state, rows, true);
		final int preferredHeight = optionRequiredHeight(state, rows, width, preferred);
		final int minimumHeight = optionRequiredHeight(state, rows, width, minimum);
		return new FitRequirement(true, 0, minimumHeight, preferredHeight);
	}

	private FitRequirement portraitFitRequirement(PortraitState state, int effectiveWidth, boolean constrainedWrap) {
		if (!state.isComplete()) {
			return FitRequirement.NONE;
		}

		final int horizontalInset = Math.max(0, ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width);
		final int verticalInset = Math.max(0, ChatboxGeometry.NATIVE_BODY_HEIGHT - state.rootGeometry.height);
		final int width = Math.max(1, effectiveWidth - horizontalInset);
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int contentWidth = fittedWidth(state.contentGeometry, state.safeGeometry.width, safeWidth);
		final int sideTextWidth = fittedWidth(state.textGeometry, state.contentGeometry.width, contentWidth);
		final int contentPadding = Math.min(STACK_PADDING, Math.max(0, (safeWidth - 1) / 2));
		final int fullTextWidth = Math.max(1, safeWidth - contentPadding * 2);
		final boolean stacked = requiresStackedPortrait(state, safeWidth, sideTextWidth);
		/* Names and continue labels wrap within their available column. They must
		 * contribute additional height, not an impossible strict minimum width,
		 * when an obstacle narrows the physical dialogue after fit selection. */
		final int minimumWidth = 0;
		final int preferredGap = portraitSectionGap(state);
		final int safeTop = Math.max(MIN_CONTENT_PADDING, state.safeGeometry.y);
		final int safeBottom = Math.max(MIN_CONTENT_PADDING, state.rootGeometry.height - state.safeGeometry.y - state.safeGeometry.height);

		/*
		 * Readable text is the minimum presentation. A portrait is optional and must
		 * never force temporary chatbox growth when the full-width text fits without it.
		 */
		final int minimumContent = portraitContentRequiredHeight(state, fullTextWidth, MIN_DIALOGUE_GAP, constrainedWrap);
		final int minimumRootHeight = safeTop + minimumContent + safeBottom;

		final int preferredRootHeight;
		if (stacked) {
			final int preferredContent = portraitContentRequiredHeight(state, fullTextWidth, preferredGap, constrainedWrap);
			preferredRootHeight = safeTop + portraitHeadHeight(state) + PORTRAIT_RENDER_PADDING * 2 + STACK_GAP + preferredContent + safeBottom;
		} else {
			final int preferredContent = portraitContentRequiredHeight(state, sideTextWidth, preferredGap, constrainedWrap);
			preferredRootHeight = safeTop + Math.max(portraitHeadHeight(state), preferredContent) + safeBottom;
		}

		return new FitRequirement(true, minimumWidth, minimumRootHeight + verticalInset, preferredRootHeight + verticalInset);
	}

	private int portraitContentRequiredHeight(PortraitState state, int textWidth, int sectionGap, boolean constrainedWrap) {
		final int width = Math.max(1, textWidth);
		final String name = portraitFixedText(state.name, state.nameSourceText, width);
		final String text = wrapText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), Math.max(1, textWidth));
		final String continueText = portraitFixedText(state.continueWidget, state.continueSourceText, width);
		final int nameHeight = portraitNameHeight(state, name);
		final int textHeight = dialogueTextHeight(state.text, text);
		final int continueHeight = portraitContinueHeight(state, continueText);
		return state.nameGeometry.y + nameHeight + sectionGap + textHeight + sectionGap + continueHeight;
	}

	private FitRequirement messageFitRequirement(MessageState state, int effectiveWidth, boolean constrainedWrap) {
		if (!state.isComplete()) {
			return FitRequirement.NONE;
		}

		final int rootWidth = messageRootWidth(state, effectiveWidth);
		final int textWidth = messageTextWidth(rootWidth);
		/* The continue label reflows even in strict mode, so it cannot impose
		 * a single-line minimum width on live collision geometry. */
		final int minimumWidth = 0;
		final int rootHeightOffset = state.rootHeightOffset;
		final int minimumRootHeight = messageRequiredRootHeight(state, textWidth, MIN_DIALOGUE_GAP, constrainedWrap);
		final int preferredRootHeight = messageRequiredRootHeight(state, textWidth, MIN_DIALOGUE_GAP, constrainedWrap);
		final int minimumHeight = Math.max(1, minimumRootHeight - rootHeightOffset);
		final int preferredHeight = Math.max(minimumHeight, preferredRootHeight - rootHeightOffset);
		return new FitRequirement(true, minimumWidth, minimumHeight, preferredHeight);
	}

	private int messageRequiredRootHeight(MessageState state, int textWidth, int sectionGap, boolean constrainedWrap) {
		final String text = wrapResolvedText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), Math.max(1, textWidth));
		final String continueText = messageContinueText(state, textWidth);
		return (MIN_CONTENT_PADDING + MESSAGE_CONTENT_INSET + MESSAGE_TEXT_PADDING) * 2 + dialogueTextHeight(state.text, text) + sectionGap + messageContinueHeight(state, continueText);
	}

	private static int messageRootWidth(MessageState state, int effectiveWidth) {
		return Math.max(1, Math.min(effectiveWidth, effectiveWidth + state.rootWidthOffset));
	}

	private static int messageRootHeight(MessageState state, int effectiveBodyHeight) {
		return Math.max(1, effectiveBodyHeight + state.rootHeightOffset);
	}

	/* Center each dialogue surface while it fits. Once the viewport is shorter
	 * than its content, discard centering space and clip from the obstructed
	 * edge instead of retaining a fixed margin around invisible content. */
	private static int centeredRootY(int availableHeight, int rootHeight, boolean clipFromTop) {
		if (clipFromTop && rootHeight > availableHeight) {
			return availableHeight - rootHeight;
		}
		return Math.max(0, (availableHeight - rootHeight) / 2);
	}

	/* Saved/native root offsets describe preferred spacing, not a minimum.
	 * Reclaim that spacing symmetrically before reducing readable content. */
	private static int fittedCenteredRootHeight(int availableHeight, int preferredHeight, int requiredHeight) {
		final int available = Math.max(1, availableHeight);
		return Math.min(available, Math.max(Math.max(1, preferredHeight), Math.min(available, Math.max(1, requiredHeight))));
	}

	private static int messageContentWidth(int rootWidth) {
		return Math.max(1, rootWidth - MIN_CONTENT_PADDING * 2 - MESSAGE_CONTENT_INSET * 2);
	}

	private static int messageTextWidth(int rootWidth) {
		return Math.max(1, messageContentWidth(rootWidth) - MESSAGE_TEXT_PADDING * 2);
	}

	private FitRequirement spriteFitRequirement(SpriteState state, int effectiveWidth, boolean constrainedWrap) {
		if (!state.isComplete()) {
			return FitRequirement.NONE;
		}

		final int horizontalInset = Math.max(0, ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width);
		final int width = Math.max(1, effectiveWidth - horizontalInset);
		final int sideTextWidth = Math.max(1, width - SPRITE_SIDE_TEXT_X - Math.max(0, state.rootGeometry.width - state.textGeometry.x - state.textGeometry.width));
		final int minimumWidth = spriteMinimumWidth(state, effectiveWidth, width, sideTextWidth, constrainedWrap);
		final int preferredGap = spriteSectionGap(state);
		final int minimumHeight = spriteRequiredHeight(state, width, sideTextWidth, MIN_DIALOGUE_GAP, constrainedWrap);
		final int preferredHeight = spriteRequiredHeight(state, width, sideTextWidth, preferredGap, constrainedWrap);
		return new FitRequirement(true, minimumWidth, minimumHeight, preferredHeight);
	}

	private static int spriteMinimumWidth(SpriteState state, int effectiveWidth, int width, int sideTextWidth, boolean constrainedWrap) {
		final int structuralWidth = state.itemGeometry.width + SPRITE_STACK_PADDING * 2;
		int minimumWidth = structuralWidth > width ? effectiveWidth + structuralWidth - width : 0;
		if (constrainedWrap) {
			return minimumWidth;
		}

		/* Sprite continue text can wrap; only structural and title geometry
		 * are candidates for an intrinsic minimum width. */
		if (state.titleWidget != null && state.titleGeometry != null) {
			final int availableTitleWidth = fittedWidth(state.titleGeometry, state.rootGeometry.width, width);
			final int titleTextWidth = dialogueTextWidth(state.titleWidget, correctedDialogueText(state.titleWidget, state.titleSourceText));
			if (titleTextWidth > availableTitleWidth) {
				minimumWidth = Math.max(minimumWidth, effectiveWidth + titleTextWidth - availableTitleWidth);
			}
		}
		return minimumWidth;
	}

	private int spriteRequiredHeight(SpriteState state, int width, int sideTextWidth, int sectionGap, boolean constrainedWrap) {
		final int continueWidth = sideTextWidth >= MIN_SIDE_TEXT_WIDTH ? sideTextWidth : Math.max(1, width - SPRITE_STACK_PADDING * 2);
		final String continueText = spriteContinueText(state, continueWidth);
		final int continueHeight = spriteContinueHeight(state, continueText);
		final int titleGrowth = spriteTitleGrowth(state, width, constrainedWrap);
		if (sideTextWidth >= MIN_SIDE_TEXT_WIDTH) {
			final int rightMargin = Math.max(0, state.rootGeometry.width - state.textGeometry.x - state.textGeometry.width);
			final int textWidth = Math.max(1, width - SPRITE_SIDE_TEXT_X - rightMargin);
			final String text = wrapText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), textWidth);
			final int textHeight = dialogueTextHeight(state.text, text);
			final int blockHeight = textHeight + sectionGap + continueHeight;
			return Math.max(state.rootGeometry.height + titleGrowth, Math.max(state.itemGeometry.height + SPRITE_ITEM_PADDING * 2, blockHeight + SPRITE_ITEM_PADDING * 2) + titleGrowth);
		}

		final int contentWidth = Math.max(1, width - SPRITE_STACK_PADDING * 2);
		final String text = wrapText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), contentWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		return SPRITE_STACK_PADDING + state.itemGeometry.height + SPRITE_STACK_GAP + textHeight + sectionGap + continueHeight + SPRITE_STACK_PADDING + titleGrowth;
	}

	/**
	 * ================================================================
	 * OPTIONS LAYOUT
	 * ================================================================
	 */
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
		final List<OptionRow> rows = collectOptionRows(optionsState, width);
		final OptionLayout layout = optionLayout(optionsState, rows, width, effectiveBodyHeight);
		final int x = Math.max(0, (effectiveWidth - width) / 2);
		final int y = centeredRootY(effectiveBodyHeight, layout.height, clipTopOverflow);
		final Position rootPosition = rootPosition(options, x, y, width, layout.height);

		applyGeometry(options, rootPosition.x, rootPosition.y, width, layout.height, result);
		applyOptionRows(rows, width, result);
		applyOptionGraphics(optionsState, width, result);
	}

	private static List<OptionRow> collectOptionRows(OptionsState state, int width) {
		final List<OptionRow> rows = new ArrayList<>();
		for (Map.Entry<Widget, WidgetState> entry : state.children.entrySet()) {
			final Widget widget = entry.getKey();
			final WidgetState baseline = entry.getValue();
			if (widget != null && baseline != null && baseline.geometry.hasSize() && widget.getType() == WidgetType.TEXT) {
				final OptionRow row = new OptionRow(widget, baseline, wrapText(widget, correctedDialogueText(widget, baseline.sourceText), width));
				row.height = wrappedHeight(widget, baseline.geometry.height, row.text);
				rows.add(row);
			}
		}

		rows.sort(Comparator.comparingInt(row -> row.baseline.geometry.y));
		return rows;
	}

	private static OptionLayout optionLayout(OptionsState state, List<OptionRow> rows, int width, int availableHeight) {
		if (rows.isEmpty()) {
			return new OptionLayout(state.rootGeometry.height, 0, 0, MIN_OPTION_ROW_GAP);
		}

		final OptionSpacing preferred = optionSpacing(state, rows, false);
		final OptionSpacing minimum = optionSpacing(state, rows, true);
		final OptionSpacing selected = availableHeight > 0 ? compressOptionSpacing(state, preferred, minimum, width, availableHeight, rows) : preferred;

		int y = selected.top + optionTitleOffset(state, rows, width, selected.top);
		for (int i = 0; i < rows.size(); i++) {
			final OptionRow row = rows.get(i);
			row.y = y;
			y += row.height;
			if (i + 1 < rows.size()) {
				y += selected.gap;
			}
		}

		return new OptionLayout(y + selected.bottom, selected.top, selected.bottom, selected.gap);
	}

	private static OptionSpacing optionSpacing(OptionsState state, List<OptionRow> rows, boolean minimum) {
		final OptionRow first = rows.get(0);
		final OptionRow last = rows.get(rows.size() - 1);
		final int preferredTop = Math.max(OPTION_OUTER_PADDING, scaledOptionSpacing(first.baseline.geometry.y, first, first));
		final int nativeBottom = Math.max(0, state.rootGeometry.height - last.baseline.geometry.y - last.baseline.geometry.height);
		final int preferredBottom = Math.max(OPTION_OUTER_PADDING, scaledOptionSpacing(nativeBottom, last, last));
		final int preferredGap = preferredOptionGap(rows);

		return minimum ? new OptionSpacing(OPTION_OUTER_PADDING, OPTION_OUTER_PADDING, MIN_OPTION_ROW_GAP) : new OptionSpacing(preferredTop, preferredBottom, preferredGap);
	}

	private static OptionSpacing compressOptionSpacing(OptionsState state, OptionSpacing preferred, OptionSpacing minimum, int width, int availableHeight, List<OptionRow> rows) {
		final int gapCount = Math.max(0, rows.size() - 1);
		final int preferredHeight = optionRequiredHeight(state, rows, width, preferred);
		if (preferredHeight <= availableHeight) {
			return preferred;
		}

		final int minimumHeight = optionRequiredHeight(state, rows, width, minimum);
		if (minimumHeight >= availableHeight) {
			return minimum;
		}

		int remaining = availableHeight - minimumHeight;
		int top = minimum.top;
		int bottom = minimum.bottom;
		int gap = minimum.gap;

		if (gapCount > 0) {
			final int gapIncrease = Math.min(preferred.gap - minimum.gap, remaining / gapCount);
			gap += gapIncrease;
			remaining -= gapIncrease * gapCount;
		}

		final int topIncrease = Math.min(preferred.top - minimum.top, (remaining + 1) / 2);
		top += topIncrease;
		remaining -= topIncrease;

		final int bottomIncrease = Math.min(preferred.bottom - minimum.bottom, remaining);
		bottom += bottomIncrease;

		return new OptionSpacing(top, bottom, gap);
	}

	private static int optionRequiredHeight(OptionsState state, List<OptionRow> rows, int width, OptionSpacing spacing) {
		final int gapCount = Math.max(0, rows.size() - 1);
		final int titleOffset = optionTitleOffset(state, rows, width, spacing.top);
		return optionRowsHeight(rows) + spacing.top + titleOffset + spacing.bottom + gapCount * spacing.gap;
	}

	private static int optionTitleOffset(OptionsState state, List<OptionRow> rows, int width, int titleY) {
		if (rows.isEmpty()) {
			return 0;
		}

		final List<GraphicLayout> graphics = collectOptionGraphics(state, width);
		if (graphics.isEmpty() || optionGraphicsOverlap(graphics)) {
			return 0;
		}

		int graphicBottom = 0;
		for (GraphicLayout graphic : graphics) {
			graphicBottom = Math.max(graphicBottom, graphic.baseline.geometry.y + graphic.baseline.geometry.height);
		}

		final OptionRow title = rows.get(0);
		if (title.baseline.geometry.y >= graphicBottom) {
			return 0;
		}

		final int textWidth = optionTextWidth(title.widget, title.text);
		final int textX = Math.max(0, (width - textWidth) / 2);
		final Rectangle textBounds = new Rectangle(textX, titleY, textWidth, title.height);
		int overlapBottom = 0;
		for (GraphicLayout graphic : graphics) {
			final Geometry geometry = graphic.baseline.geometry;
			final Rectangle graphicBounds = new Rectangle(graphic.x, geometry.y, geometry.width, geometry.height);
			if (textBounds.intersects(graphicBounds)) {
				overlapBottom = Math.max(overlapBottom, geometry.y + geometry.height);
			}
		}

		return overlapBottom > 0 ? Math.max(0, overlapBottom + MIN_OPTION_ROW_GAP - titleY) : 0;
	}

	private static int optionTextWidth(Widget widget, String text) {
		final FontTypeFace font = widget != null ? widget.getFont() : null;
		if (font == null || text == null || text.isEmpty()) {
			return 0;
		}

		int width = 0;
		for (String line : text.split("<br>", -1)) {
			width = Math.max(width, font.getTextWidth(line));
		}
		return width;
	}

	private static int preferredOptionGap(List<OptionRow> rows) {
		int nativeGapTotal = 0;
		int nativeGapCount = 0;
		for (int i = 0; i + 1 < rows.size(); i++) {
			final Geometry current = rows.get(i).baseline.geometry;
			final Geometry next = rows.get(i + 1).baseline.geometry;
			final int nativeGap = next.y - current.y - current.height;
			if (nativeGap >= 0) {
				nativeGapTotal += scaledOptionSpacing(nativeGap, rows.get(i), rows.get(i + 1));
				nativeGapCount++;
			}
		}

		if (nativeGapCount > 0) {
			return Math.max(MIN_OPTION_ROW_GAP, nativeGapTotal / nativeGapCount);
		}

		int lineHeight = 1;
		for (OptionRow row : rows) {
			lineHeight = Math.max(lineHeight, optionLineHeight(row.widget, row.baseline.geometry.height));
		}
		return Math.max(MIN_OPTION_ROW_GAP, lineHeight / OPTION_GAP_DIVISOR);
	}

	private static int scaledOptionSpacing(int nativeSpacing, OptionRow first, OptionRow second) {
		if (nativeSpacing <= 0 || first == null || second == null || first.widget.getFontId() == ChatFont.QUILL_8.getFontId() && second.widget.getFontId() == ChatFont.QUILL_8.getFontId()) {
			return Math.max(0, nativeSpacing);
		}

		final int nativeHeight = Math.max(1, (first.baseline.geometry.height + second.baseline.geometry.height) / 2);
		final int selectedHeight = Math.max(1, (optionLineHeight(first.widget, first.baseline.geometry.height) + optionLineHeight(second.widget, second.baseline.geometry.height)) / 2);
		return Math.max(0, Math.round((float) nativeSpacing * selectedHeight / nativeHeight));
	}

	private static int optionRowsHeight(List<OptionRow> rows) {
		int height = 0;
		for (OptionRow row : rows) {
			height += row.height;
		}
		return height;
	}

	private static void applyOptionRows(List<OptionRow> rows, int width, MutableResult result) {
		for (OptionRow row : rows) {
			applyTextGeometry(row.widget, 0, row.y, width, row.height, row.text, result);
			row.baseline.renderedText = row.text;
		}
	}

	private static void applyOptionGraphics(OptionsState state, int width, MutableResult result) {
		final List<GraphicLayout> graphics = collectOptionGraphics(state, width);
		final boolean overlap = optionGraphicsOverlap(graphics);

		for (GraphicLayout graphic : graphics) {
			final Geometry geometry = graphic.baseline.geometry;
			applyGeometry(graphic.widget, graphic.x, geometry.y, geometry.width, geometry.height, result);
			applyHidden(graphic.widget, overlap || graphic.baseline.hidden, result);
		}
	}

	private static List<GraphicLayout> collectOptionGraphics(OptionsState state, int width) {
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
		return graphics;
	}

	private static boolean optionGraphicsOverlap(List<GraphicLayout> graphics) {
		for (int i = 0; i < graphics.size(); i++) {
			for (int j = i + 1; j < graphics.size(); j++) {
				final GraphicLayout a = graphics.get(i);
				final GraphicLayout b = graphics.get(j);
				if (a.x < b.x + b.baseline.geometry.width && b.x < a.x + a.baseline.geometry.width) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * ================================================================
	 * PORTRAIT LAYOUT
	 * ================================================================
	 */
	private PortraitState applyPortrait(int effectiveWidth, int effectiveBodyHeight, boolean rightSide, boolean constrainedWrap, PortraitState state, MutableResult result, int rootId, int safeId, int headId, int contentId, int nameId, int continueId, int textId) {
		final Widget root = client.getWidget(rootId);
		if (!isMounted(root)) {
			return restorePortrait(state, result);
		}

		if (state == null || state.root != root) {
			state = restorePortrait(state, result);
			state = new PortraitState(root, client.getWidget(safeId), client.getWidget(headId), client.getWidget(contentId), client.getWidget(nameId), client.getWidget(continueId), client.getWidget(textId));
		}

		if (!state.isComplete()) {
			return state;
		}

		state.captureText();

		final int horizontalInset = Math.max(0, ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width);
		final int verticalInset = Math.max(0, ChatboxGeometry.NATIVE_BODY_HEIGHT - state.rootGeometry.height);
		final int width = Math.max(1, effectiveWidth - horizontalInset);
		final int preferredHeight = Math.max(1, effectiveBodyHeight - verticalInset);
		final int requiredHeight = Math.max(1, portraitFitRequirement(state, effectiveWidth, constrainedWrap).getMinimumBodyHeight() - verticalInset);
		final int height = fittedCenteredRootHeight(effectiveBodyHeight, preferredHeight, requiredHeight);
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int contentWidth = fittedWidth(state.contentGeometry, state.safeGeometry.width, safeWidth);
		final int sideTextWidth = fittedWidth(state.textGeometry, state.contentGeometry.width, contentWidth);
		final int x = Math.max(0, (effectiveWidth - width) / 2);
		final int y = centeredRootY(effectiveBodyHeight, height, clipTopOverflow);
		final Position rootPosition = rootPosition(state.root, x, y, width, height);

		if (requiresStackedPortrait(state, safeWidth, sideTextWidth)) {
			applyStacked(state, rootPosition, width, height, constrainedWrap, result);
		} else {
			applySideBySide(state, x, width, height, effectiveBodyHeight, rightSide, constrainedWrap, result);
		}

		return state;
	}

	private void applySideBySide(PortraitState state, int rootX, int width, int baseHeight, int effectiveBodyHeight, boolean rightSide, boolean constrainedWrap, MutableResult result) {
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int contentWidth = fittedWidth(state.contentGeometry, state.safeGeometry.width, safeWidth);
		final int nameWidth = fittedWidth(state.nameGeometry, state.contentGeometry.width, contentWidth);
		final int textWidth = fittedWidth(state.textGeometry, state.contentGeometry.width, contentWidth);
		final int continueWidth = fittedWidth(state.continueGeometry, state.contentGeometry.width, contentWidth);
		PortraitContentLayout content = portraitContentLayout(state, state.nameGeometry.x, state.textGeometry.x, state.continueGeometry.x, nameWidth, textWidth, continueWidth, 0, constrainedWrap);

		final int safeTop = Math.max(0, state.safeGeometry.y);
		final int safeBottom = Math.max(0, state.rootGeometry.height - state.safeGeometry.y - state.safeGeometry.height);
		final int minimumSafeHeight = Math.max(portraitHeadHeight(state), content.requiredHeight);
		final int minimumRootHeight = safeTop + minimumSafeHeight + safeBottom;
		final int height = Math.min(effectiveBodyHeight, Math.max(baseHeight, minimumRootHeight));
		final int safeHeight = fittedHeight(state.safeGeometry, state.rootGeometry.height, height);

		final int contentPadding = Math.min(STACK_PADDING, Math.max(0, (safeWidth - 1) / 2));
		final int fullTextWidth = Math.max(1, safeWidth - contentPadding * 2);
		final PortraitContentLayout headless = portraitContentLayout(state, contentPadding, contentPadding, contentPadding, fullTextWidth, fullTextWidth, fullTextWidth, safeHeight, constrainedWrap);
		final int spareSideHeight = safeHeight - content.requiredHeight;
		final boolean preferHeadless = state.headHidden || content.requiredHeight > safeHeight || content.requiredHeight > headless.requiredHeight && spareSideHeight < dialogueLineHeight(state.text);
		if (preferHeadless) {
			final int rootY = centeredRootY(effectiveBodyHeight, height, clipTopOverflow);
			final Position adjustedRootPosition = rootPosition(state.root, rootX, rootY, width, height);
			applyHeadless(state, adjustedRootPosition, width, height, constrainedWrap, result);
			return;
		}

		if (content.requiredHeight > safeHeight) {
			content = portraitContentLayout(state, state.nameGeometry.x, state.textGeometry.x, state.continueGeometry.x, nameWidth, textWidth, continueWidth, safeHeight, constrainedWrap);
		}

		final int contentHeight = Math.min(safeHeight, Math.max(Math.min(state.contentGeometry.height, safeHeight), content.requiredHeight));
		final int contentY = Math.max(0, (safeHeight - contentHeight) / 2);
		final int nativeHeadX = rightSide ? anchoredRightX(state.headGeometry, state.safeGeometry.width, safeWidth) : state.headGeometry.x;
		final int contentBlockHeight = Math.max(1, content.requiredHeight - content.nameY);
		final int nativeHeadY = clamp(contentY + content.nameY + (contentBlockHeight - state.headGeometry.height) / 2, 0, Math.max(0, safeHeight - state.headGeometry.height));
		final int headX = portraitInsetX(nativeHeadX, state.headGeometry.width, safeWidth);
		final int headY = portraitInsetY(nativeHeadY, state.headGeometry.height, safeHeight);
		final int contentOriginalY = positionY(state.content, contentY, safeHeight, contentHeight);
		final int rootY = centeredRootY(effectiveBodyHeight, height, clipTopOverflow);
		final Position adjustedRootPosition = rootPosition(state.root, rootX, rootY, width, height);

		applyPortraitRoot(state, adjustedRootPosition, width, height, result);
		applyGeometry(state.safe, state.safeGeometry.x, state.safeGeometry.y, safeWidth, safeHeight, result);
		applyGeometry(state.head, headX, headY, state.headGeometry.width, state.headGeometry.height, result);
		applyHidden(state.head, state.headHidden, result);
		applyGeometry(state.content, state.contentGeometry.x, contentOriginalY, contentWidth, contentHeight, result);
		applyPortraitContent(state, content, contentWidth, result);
	}

	private void applyHeadless(PortraitState state, Position rootPosition, int width, int height, boolean constrainedWrap, MutableResult result) {
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int safeHeight = fittedHeight(state.safeGeometry, state.rootGeometry.height, height);
		final int contentPadding = Math.min(STACK_PADDING, Math.max(0, (safeWidth - 1) / 2));
		final int childWidth = Math.max(1, safeWidth - contentPadding * 2);
		final PortraitContentLayout content = portraitContentLayout(state, contentPadding, contentPadding, contentPadding, childWidth, childWidth, childWidth, safeHeight, constrainedWrap);
		final boolean clipTop = clipTopOverflow && content.requiredHeight > safeHeight;
		final int contentHeight = clipTop ? content.requiredHeight : Math.min(safeHeight, content.requiredHeight);
		final int contentY = centeredRootY(safeHeight, contentHeight, clipTop);
		final int contentOriginalY = positionY(state.content, contentY, safeHeight, contentHeight);

		applyPortraitRoot(state, rootPosition, width, height, result);
		applyGeometry(state.safe, state.safeGeometry.x, state.safeGeometry.y, safeWidth, safeHeight, result);
		applyHidden(state.head, true, result);
		applyGeometry(state.content, 0, contentOriginalY, safeWidth, contentHeight, result);
		applyPortraitContent(state, content, safeWidth, result);
	}

	private void applyStacked(PortraitState state, Position rootPosition, int width, int height, boolean constrainedWrap, MutableResult result) {
		final int safeWidth = fittedWidth(state.safeGeometry, state.rootGeometry.width, width);
		final int safeHeight = fittedHeight(state.safeGeometry, state.rootGeometry.height, height);
		final int contentWidth = Math.max(1, safeWidth);
		final int contentPadding = Math.min(STACK_PADDING, Math.max(0, (contentWidth - 1) / 2));
		final int childWidth = Math.max(1, contentWidth - contentPadding * 2);
		PortraitContentLayout content = portraitContentLayout(state, contentPadding, contentPadding, contentPadding, childWidth, childWidth, childWidth, 0, constrainedWrap);
		if (content.requiredHeight > safeHeight) {
			content = portraitContentLayout(state, contentPadding, contentPadding, contentPadding, childWidth, childWidth, childWidth, safeHeight, constrainedWrap);
		}

		final int naturalContentHeight = Math.min(safeHeight, content.requiredHeight);
		final int headHeight = portraitHeadHeight(state);
		final int paddedHeadHeight = headHeight + PORTRAIT_RENDER_PADDING * 2;
		final boolean showHead = !state.headHidden && state.headGeometry.width + PORTRAIT_RENDER_PADDING * 2 <= safeWidth && paddedHeadHeight + STACK_GAP + naturalContentHeight + MIN_CONTENT_PADDING * 2 <= safeHeight;
		final int availableContentHeight = showHead ? Math.max(1, safeHeight - paddedHeadHeight - STACK_GAP - MIN_CONTENT_PADDING * 2) : safeHeight;
		if (content.requiredHeight > availableContentHeight) {
			content = portraitContentLayout(state, contentPadding, contentPadding, contentPadding, childWidth, childWidth, childWidth, availableContentHeight, constrainedWrap);
		}

		final boolean clipTop = clipTopOverflow && !showHead && content.requiredHeight > safeHeight;
		final int contentHeight = clipTop ? content.requiredHeight : Math.min(availableContentHeight, content.requiredHeight);
		final int groupHeight = showHead ? paddedHeadHeight + STACK_GAP + contentHeight : contentHeight;
		final int groupY = centeredRootY(safeHeight, groupHeight, clipTop);
		final int headX = Math.max(0, (safeWidth - state.headGeometry.width) / 2);
		final int headY = groupY + PORTRAIT_RENDER_PADDING + Math.max(0, headHeight - state.headGeometry.height);
		final int contentY = showHead ? groupY + paddedHeadHeight + STACK_GAP : groupY;
		final int contentOriginalY = positionY(state.content, contentY, safeHeight, contentHeight);

		applyPortraitRoot(state, rootPosition, width, height, result);
		applyGeometry(state.safe, state.safeGeometry.x, state.safeGeometry.y, safeWidth, safeHeight, result);
		applyHidden(state.head, !showHead, result);
		if (showHead) {
			applyGeometry(state.head, headX, headY, state.headGeometry.width, state.headGeometry.height, result);
		}
		applyGeometry(state.content, 0, contentOriginalY, contentWidth, contentHeight, result);
		applyPortraitContent(state, content, contentWidth, result);
	}

	private static int portraitInsetX(int x, int width, int parentWidth) {
		final int maximumX = Math.max(0, parentWidth - width);
		final int center = x + width / 2;
		final int parentCenter = parentWidth / 2;
		if (center < parentCenter) {
			return clamp(x + PORTRAIT_RENDER_PADDING, 0, maximumX);
		}
		if (center > parentCenter) {
			return clamp(x - PORTRAIT_RENDER_PADDING, 0, maximumX);
		}

		return clamp(x, 0, maximumX);
	}

	private static int portraitInsetY(int y, int height, int parentHeight) {
		final int maximumY = Math.max(0, parentHeight - height);
		final int center = y + height / 2;
		final int parentCenter = parentHeight / 2;
		if (center < parentCenter) {
			return clamp(y + PORTRAIT_RENDER_PADDING, 0, maximumY);
		}
		if (center > parentCenter) {
			return clamp(y - PORTRAIT_RENDER_PADDING, 0, maximumY);
		}

		return clamp(y, 0, maximumY);
	}

	private static int portraitNameHeight(PortraitState state) {
		return portraitNameHeight(state, correctedDialogueText(state.name, state.nameSourceText));
	}

	private static int portraitNameHeight(PortraitState state, String text) {
		return Math.max(state.nameGeometry.height, dialogueTextHeight(state.name, text));
	}

	private static int portraitContinueHeight(PortraitState state) {
		return portraitContinueHeight(state, correctedDialogueText(state.continueWidget, state.continueSourceText));
	}

	private static int portraitContinueHeight(PortraitState state, String text) {
		return Math.max(state.continueGeometry.height, dialogueTextHeight(state.continueWidget, text) + CONTINUE_GLYPH_PADDING * 2);
	}

	private String portraitFixedText(Widget widget, String sourceText, int width) {
		final String text = correctedDialogueText(widget, sourceText);
		/* The content body is allowed to use strict width selection, but names
		 * and continue prompts must never be single-line-only. Measurement and
		 * rendering both call this method so they agree on multiline height. */
		return wrapResolvedText(widget, normalizeDialogueText(text), Math.max(1, width));
	}

	private static boolean requiresStackedPortrait(PortraitState state, int safeWidth, int sideTextWidth) {
		return sideTextWidth < MIN_SIDE_TEXT_WIDTH || safeWidth < state.headGeometry.width + STACK_GAP + MIN_SIDE_TEXT_WIDTH;
	}

	private static int portraitHeadHeight(PortraitState state) {
		return Math.max(state.headGeometry.height, state.headGeometry.y + state.headGeometry.height);
	}

	private PortraitContentLayout portraitContentLayout(PortraitState state, int nameX, int textX, int continueX, int nameWidth, int textWidth, int continueWidth, int maxHeight, boolean constrainedWrap) {
		final int fittedNameWidth = Math.max(1, nameWidth);
		final int fittedTextWidth = Math.max(1, textWidth);
		final int fittedContinueWidth = Math.max(1, continueWidth);
		final String nameText = portraitFixedText(state.name, state.nameSourceText, fittedNameWidth);
		final String text = wrapText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), fittedTextWidth);
		final String continueText = portraitFixedText(state.continueWidget, state.continueSourceText, fittedContinueWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		final int naturalGap = portraitSectionGap(state);
		final int nameY = state.nameGeometry.y;
		final int nameHeight = portraitNameHeight(state, nameText);
		final int continueHeight = portraitContinueHeight(state, continueText);
		final int fixedHeight = nameY + nameHeight + textHeight + continueHeight;
		final int sectionGap = maxHeight > 0 ? Math.max(MIN_DIALOGUE_GAP, Math.min(naturalGap, Math.max(0, (maxHeight - fixedHeight) / 2))) : naturalGap;
		final int textY = nameY + nameHeight + sectionGap;
		final int continueY = textY + textHeight + sectionGap;
		final int requiredHeight = Math.max(nameY + nameHeight, continueY + continueHeight);
		return new PortraitContentLayout(nameX, textX, continueX, fittedNameWidth, fittedTextWidth, fittedContinueWidth, nameY, nameText, nameHeight, textY, text, textHeight, continueY, continueText, continueHeight, requiredHeight);
	}

	private static void applyPortraitContent(PortraitState state, PortraitContentLayout layout, int contentWidth, MutableResult result) {
		final int nameWidth = Math.min(layout.nameWidth, Math.max(1, contentWidth - layout.nameX));
		final int textWidth = Math.min(layout.textWidth, Math.max(1, contentWidth - layout.textX));
		final int continueWidth = Math.min(layout.continueWidth, Math.max(1, contentWidth - layout.continueX));

		applyTextGeometry(state.name, layout.nameX, layout.nameY, nameWidth, layout.nameHeight, layout.nameText, result);
		applyTextGeometry(state.text, layout.textX, layout.textY, textWidth, layout.textHeight, layout.text, result);
		applyTextGeometry(state.continueWidget, layout.continueX, layout.continueY, continueWidth, layout.continueHeight, layout.continueText, result);
		state.renderedText = layout.text;
		state.nameRenderedText = layout.nameText;
		state.continueRenderedText = layout.continueText;
	}

	/**
	 * ================================================================
	 * MESSAGEBOX & ITEM LAYOUT
	 * ================================================================
	 */
	private MessageState applyMessage(int effectiveWidth, int effectiveBodyHeight, boolean constrainedWrap, MessageState state, MutableResult result) {
		final Widget root = client.getWidget(InterfaceID.Messagebox.UNIVERSE);
		if (!isMounted(root)) {
			return restoreMessage(state, result);
		}

		if (state == null || state.root != root) {
			state = restoreMessage(state, result);
			state = new MessageState(root, client.getWidget(InterfaceID.Messagebox.SAFEZONE), client.getWidget(InterfaceID.Messagebox.CONTENT), client.getWidget(InterfaceID.Messagebox.TEXT), client.getWidget(InterfaceID.Messagebox.CONTINUE), effectiveWidth, effectiveBodyHeight);
		}

		if (!state.isComplete()) {
			return state;
		}

		state.captureText();
		final int width = messageRootWidth(state, effectiveWidth);
		final int preferredHeight = messageRootHeight(state, effectiveBodyHeight);
		final int requiredHeight = messageRequiredRootHeight(state, messageTextWidth(width), MIN_DIALOGUE_GAP, constrainedWrap);
		final int height = fittedCenteredRootHeight(effectiveBodyHeight, preferredHeight, requiredHeight);
		final int safeWidth = Math.max(1, width - MIN_CONTENT_PADDING * 2);
		final int safeHeight = Math.max(1, height - MIN_CONTENT_PADDING * 2);
		final int contentWidth = Math.max(1, safeWidth - MESSAGE_CONTENT_INSET * 2);
		final int visibleContentHeight = Math.max(1, safeHeight - MESSAGE_CONTENT_INSET * 2);
		final int textWidth = Math.max(1, contentWidth - MESSAGE_TEXT_PADDING * 2);
		final String text = wrapResolvedText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), textWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		final String continueText = messageContinueText(state, textWidth);
		final int continueHeight = messageContinueHeight(state, continueText);
		final boolean clipTop = clipTopOverflow && textHeight + continueHeight + MIN_DIALOGUE_GAP + MESSAGE_TEXT_PADDING * 2 > visibleContentHeight;
		final int contentHeight = clipTop ? Math.max(visibleContentHeight, textHeight + continueHeight + MIN_DIALOGUE_GAP + MESSAGE_TEXT_PADDING * 2) : visibleContentHeight;
		final int textAreaHeight = Math.max(1, contentHeight - MESSAGE_TEXT_PADDING * 2);
		final int availableGap = textAreaHeight - textHeight - continueHeight;
		final int sectionGap = fittedDialogueGap(MIN_DIALOGUE_GAP, availableGap);
		final int blockHeight = textHeight + sectionGap + continueHeight;
		final int textY = MESSAGE_TEXT_PADDING + Math.max(0, (textAreaHeight - blockHeight) / 2);
		final int continueY = textY + textHeight + sectionGap;
		final int contentY = MESSAGE_CONTENT_INSET + (clipTop ? visibleContentHeight - contentHeight : 0);
		final int x = Math.max(0, (effectiveWidth - width) / 2);
		final int y = centeredRootY(effectiveBodyHeight, height, clipTopOverflow);
		final Position rootPosition = messageRootPosition(state.root, x, y, width, height);

		applyMessageRoot(state, rootPosition, width, height, result);
		applyAbsoluteGeometry(state.safe, MIN_CONTENT_PADDING, MIN_CONTENT_PADDING, safeWidth, safeHeight, result);
		applyAbsoluteGeometry(state.content, MESSAGE_CONTENT_INSET, contentY, contentWidth, contentHeight, result);
		applyAbsoluteTextGeometry(state.text, MESSAGE_TEXT_PADDING, textY, textWidth, textHeight, text, result);
		applyAbsoluteTextGeometry(state.continueWidget, MESSAGE_TEXT_PADDING, continueY, textWidth, continueHeight, continueText, result);
		state.safeModesForced = true;
		state.contentModesForced = true;
		state.textModesForced = true;
		state.continueModesForced = true;
		state.renderedText = text;
		state.continueRenderedText = continueText;
		return state;
	}

	private String messageContinueText(MessageState state, int width) {
		final String text = correctedDialogueText(state.continueWidget, state.continueSourceText);
		/* Wrap against the current widget width regardless of the strict-fit
		 * decision. This exact representation is also used to measure height. */
		return wrapResolvedText(state.continueWidget, normalizeDialogueText(text), Math.max(1, width));
	}

	private static int messageContinueHeight(MessageState state, String text) {
		return Math.max(state.continueNativeHeight, dialogueTextHeight(state.continueWidget, text) + CONTINUE_GLYPH_PADDING * 2);
	}

	private static void applyMessageRoot(MessageState state, Position position, int width, int height, MutableResult result) {
		boolean changed = applyGeometryFields(state.root, position.x, position.y, width, height);
		if (position.force && (!state.rootForced || state.root.getRelativeX() != position.relativeX || state.root.getRelativeY() != position.relativeY)) {
			state.root.setForcedPosition(position.relativeX, position.relativeY);
			state.rootForced = true;
			changed = true;
		}
		if (!changed) {
			return;
		}
		state.root.revalidate();
		result.mutations++;
		result.revalidates++;
	}

	private SpriteState applySprite(int effectiveWidth, int effectiveBodyHeight, boolean constrainedWrap, SpriteState state, MutableResult result) {
		final Widget item = client.getWidget(InterfaceID.Objectbox.ITEM);
		final Widget text = client.getWidget(InterfaceID.Objectbox.TEXT);
		if (!isMounted(item) && !isMounted(text)) {
			return restoreSprite(state, result);
		}

		final Widget root = item != null && item.getParent() != null ? item.getParent() : text != null ? text.getParent() : null;
		if (!isMounted(root)) {
			return restoreSprite(state, result);
		}

		final Widget host = root.getParent();
		final Widget continueWidget = findSpriteContinue(root, text);
		final Widget titleWidget = findSpriteTitle(root, text, continueWidget);
		if (state == null || state.host != host || state.root != root || state.item != item || state.titleWidget != titleWidget || state.text != text || state.continueWidget != continueWidget) {
			state = restoreSprite(state, result);
			state = new SpriteState(host, root, item, titleWidget, text, continueWidget);
		}

		if (!state.isComplete()) {
			return state;
		}

		state.captureText();

		final int horizontalInset = Math.max(0, ChatboxGeometry.NATIVE_WIDTH - state.rootGeometry.width);
		final int width = Math.max(1, effectiveWidth - horizontalInset);
		final int sideTextWidth = Math.max(1, width - SPRITE_SIDE_TEXT_X - Math.max(0, state.rootGeometry.width - state.textGeometry.x - state.textGeometry.width));

		SpriteLayout layout = sideTextWidth >= MIN_SIDE_TEXT_WIDTH ? spriteSideBySideLayout(state, width, effectiveBodyHeight, constrainedWrap) : spriteStackedLayout(state, width, effectiveBodyHeight, constrainedWrap);
		final int titleGrowth = spriteTitleGrowth(state, width, constrainedWrap);
		if (titleGrowth > 0) {
			layout = layout.withVerticalShift(titleGrowth);
		}
		final int x = Math.max(0, (effectiveWidth - width) / 2);
		final int y = centeredRootY(effectiveBodyHeight, layout.height, clipTopOverflow);
		final Position hostPosition = rootPosition(state.host, x, y, width, layout.height);

		applySpriteHost(state, hostPosition, width, layout.height, result);
		applySpriteRoot(state, width, layout.height, result);
		applyGeometry(state.item, layout.itemX, layout.itemY, state.itemGeometry.width, state.itemGeometry.height, result);
		if (state.titleWidget != null && state.titleGeometry != null) {
			final int titleWidth = fittedWidth(state.titleGeometry, state.rootGeometry.width, width);
			final String titleText = spriteTitleText(state, titleWidth, constrainedWrap);
			final int titleHeight = Math.max(state.titleGeometry.height, dialogueTextHeight(state.titleWidget, titleText));
			applyTextGeometry(state.titleWidget, state.titleGeometry.x, state.titleGeometry.y, titleWidth, titleHeight, titleText, result);
			state.titleRenderedText = titleText;
		}
		applyTextGeometry(state.text, layout.textX, layout.textY, layout.textWidth, layout.textHeight, layout.text, result);
		applyAbsoluteTextGeometry(state.continueWidget, layout.continueX, layout.continueY, layout.continueWidth, layout.continueHeight, layout.continueText, result);
		state.continueModesForced = true;
		state.renderedText = layout.text;
		state.continueRenderedText = layout.continueText;
		return state;
	}

	private SpriteLayout spriteSideBySideLayout(SpriteState state, int width, int availableHeight, boolean constrainedWrap) {
		final int rightMargin = Math.max(0, state.rootGeometry.width - state.textGeometry.x - state.textGeometry.width);
		final int textWidth = Math.max(1, width - SPRITE_SIDE_TEXT_X - rightMargin);
		final String text = wrapText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), textWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		final String continueText = spriteContinueText(state, textWidth);
		final int continueHeight = spriteContinueHeight(state, continueText);
		final int preferredGap = spriteSectionGap(state);
		final int availableGap = availableHeight - SPRITE_ITEM_PADDING * 2 - textHeight - continueHeight;
		final int sectionGap = fittedDialogueGap(preferredGap, availableGap);
		final int blockHeight = textHeight + sectionGap + continueHeight;
		final int height = Math.max(state.rootGeometry.height, Math.max(state.itemGeometry.height + SPRITE_ITEM_PADDING * 2, blockHeight + SPRITE_ITEM_PADDING * 2));
		final int itemY = Math.max(0, (height - state.itemGeometry.height) / 2);
		final int textY = Math.max(0, (height - blockHeight) / 2);
		final int continueY = textY + textHeight + sectionGap;
		return new SpriteLayout(state.itemGeometry.x, itemY, SPRITE_SIDE_TEXT_X, textY, textWidth, textHeight, text, SPRITE_SIDE_TEXT_X, continueY, textWidth, continueHeight, continueText, height);
	}

	private SpriteLayout spriteStackedLayout(SpriteState state, int width, int availableHeight, boolean constrainedWrap) {
		final int contentWidth = Math.max(1, width - SPRITE_STACK_PADDING * 2);
		final String text = wrapText(state.text, normalizeDialogueText(correctedDialogueText(state.text, state.sourceText)), contentWidth);
		final int textHeight = dialogueTextHeight(state.text, text);
		final int itemX = Math.max(0, (width - state.itemGeometry.width) / 2);
		final int itemY = SPRITE_STACK_PADDING;
		final int textY = itemY + state.itemGeometry.height + SPRITE_STACK_GAP;
		final String continueText = spriteContinueText(state, contentWidth);
		final int continueHeight = spriteContinueHeight(state, continueText);
		final int preferredGap = spriteSectionGap(state);
		final int fixedHeight = textY + textHeight + continueHeight + SPRITE_STACK_PADDING;
		final int sectionGap = fittedDialogueGap(preferredGap, availableHeight - fixedHeight);
		final int continueY = textY + textHeight + sectionGap;
		final int height = continueY + continueHeight + SPRITE_STACK_PADDING;
		return new SpriteLayout(itemX, itemY, SPRITE_STACK_PADDING, textY, contentWidth, textHeight, text, SPRITE_STACK_PADDING, continueY, contentWidth, continueHeight, continueText, height);
	}

	private String spriteContinueText(SpriteState state, int width) {
		final String text = correctedDialogueText(state.continueWidget, state.continueSourceText);
		return wrapResolvedText(state.continueWidget, normalizeDialogueText(text), Math.max(1, width));
	}

	private static int spriteContinueHeight(SpriteState state, String text) {
		return Math.max(state.continueNativeHeight, dialogueTextHeight(state.continueWidget, text) + CONTINUE_GLYPH_PADDING * 2);
	}

	private static String spriteTitleText(SpriteState state, int width, boolean constrainedWrap) {
		final String text = correctedDialogueText(state.titleWidget, state.titleSourceText);
		return constrainedWrap ? wrapText(state.titleWidget, normalizeDialogueText(text), Math.max(1, width)) : text;
	}

	private static int spriteTitleGrowth(SpriteState state, int width, boolean constrainedWrap) {
		if (!constrainedWrap || state.titleWidget == null || state.titleGeometry == null) {
			return 0;
		}

		final int titleWidth = fittedWidth(state.titleGeometry, state.rootGeometry.width, width);
		final String titleText = spriteTitleText(state, titleWidth, true);
		return Math.max(0, dialogueTextHeight(state.titleWidget, titleText) - state.titleGeometry.height);
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

	private static Widget findSpriteTitle(Widget root, Widget messageText, Widget continueWidget) {
		if (root == null) {
			return null;
		}

		Widget title = findSpriteTitle(root.getDynamicChildren(), messageText, continueWidget);
		return title != null ? title : findSpriteTitle(root.getStaticChildren(), messageText, continueWidget);
	}

	private static Widget findSpriteTitle(Widget[] children, Widget messageText, Widget continueWidget) {
		if (children == null) {
			return null;
		}

		Widget title = null;
		for (Widget child : children) {
			if (child == null || child == messageText || child == continueWidget || child.getType() != WidgetType.TEXT || child.getOriginalWidth() <= 0 || child.getOriginalHeight() <= 0 || child.getText() == null || child.getText().isEmpty()) {
				continue;
			}

			if (title == null || child.getOriginalY() < title.getOriginalY()) {
				title = child;
			}
		}
		return title;
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

		if (position.force && (!state.hostForced || host.getRelativeX() != position.relativeX || host.getRelativeY() != position.relativeY)) {
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
		final Position mountedPosition = mountedRootPosition(widget, parent, universe, x, y, width, height);
		/* A remounted portrait parent can retain stale relative ancestor offsets.
		 * Do not force its root beyond a parent that can fully contain it. This
		 * guards only portrait roots; messageboxes and clipped layouts retain
		 * their established positioning paths. */
		final boolean invalidMountedPortrait = mountedPosition != null && !validPortraitRootPosition(widget, parent, mountedPosition, width, height);
		if (mountedPosition != null && !invalidMountedPortrait) {
			return mountedPosition;
		}
		final Rectangle universeBounds = universe != null ? universe.getBounds() : null;
		if (!usableBounds(universeBounds) || parent == null) {
			return new Position(widget.getOriginalX(), widget.getOriginalY(), widget.getRelativeX(), widget.getRelativeY(), false);
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
			return new Position(widget.getOriginalX(), widget.getOriginalY(), widget.getRelativeX(), widget.getRelativeY(), false);
		}

		final int parentWidth = Math.max(1, parent.getWidth());
		final int parentHeight = Math.max(1, parent.getHeight());
		final int relativeX = universeBounds.x - parentX + x - viewportOffsetX;
		final int relativeY = universeBounds.y - parentY + y - viewportOffsetY;
		final Position absolutePosition = new Position(positionX(widget, relativeX, parentWidth, width), positionY(widget, relativeY, parentHeight, height), relativeX, relativeY, true);
		/* The mounted lookup may be unavailable during a remount. The
		 * absolute fallback must be validated independently: a stale parent
		 * getBounds() can otherwise place a portrait entirely below its body. */
		if (!validPortraitRootPosition(widget, parent, absolutePosition, width, height)) {
			/* The parent and its mounted ancestors disagree about the screen
			 * origin. An in-parent position is safer than retaining the previous
			 * forced position, which may be completely outside the chat body. */
			final Position localPosition = localRootPosition(widget, x - viewportOffsetX, y - viewportOffsetY, width, height, parentWidth, parentHeight);
			if (validPortraitRootPosition(widget, parent, localPosition, width, height)) {
				return localPosition;
			}
			/* Neither placement can be validated. Restore the captured native
			 * root placement instead of leaving a stale forced coordinate. */
			return new Position(widget.getOriginalX(), widget.getOriginalY(), widget.getRelativeX(), widget.getRelativeY(), false);
		}
		return absolutePosition;
	}

	private boolean validPortraitRootPosition(Widget widget, Widget parent, Position position, int width, int height) {
		if (widget == null || parent == null || position == null) {
			return true;
		}
		if (widget.getId() != InterfaceID.ChatLeft.UNIVERSE && widget.getId() != InterfaceID.ChatRight.UNIVERSE) {
			return true;
		}

		final int parentWidth = Math.max(1, parent.getWidth());
		final int parentHeight = Math.max(1, parent.getHeight());
		/* Even a deliberately clipped portrait must intersect its parent.
		 * A root starting entirely beyond the parent's bottom or right edge
		 * cannot render dialogue text; remount offsets can cause exactly this. */
		if (position.relativeX >= parentWidth + PORTRAIT_ROOT_POSITION_TOLERANCE || position.relativeX + width <= -PORTRAIT_ROOT_POSITION_TOLERANCE || position.relativeY >= parentHeight + PORTRAIT_ROOT_POSITION_TOLERANCE || position.relativeY + height <= -PORTRAIT_ROOT_POSITION_TOLERANCE) {
			return false;
		}
		/* A clipped portrait may legitimately extend partially outside its
		 * viewport. Require full containment only for unclipped layouts. */
		if (clipTopOverflow || viewportOffsetX != 0 || viewportOffsetY != 0 || width > parentWidth || height > parentHeight) {
			return true;
		}

		return position.relativeX >= -PORTRAIT_ROOT_POSITION_TOLERANCE && position.relativeX <= parentWidth - width + PORTRAIT_ROOT_POSITION_TOLERANCE && position.relativeY >= -PORTRAIT_ROOT_POSITION_TOLERANCE && position.relativeY <= parentHeight - height + PORTRAIT_ROOT_POSITION_TOLERANCE;
	}

	private Position messageRootPosition(Widget widget, int x, int y, int width, int height) {
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget parent = widget != null ? widget.getParent() : null;
		/* Messagebox mount offsets may survive a sprite/item transition even
		 * when the parent absolute bounds have already settled. In that case,
		 * adding the stale relative ancestor offsets places the root outside
		 * the visible host. Use the established parent-absolute positioning
		 * path for messageboxes; preserve mountedRootPosition for portraits. */
		if (parent == null) {
			return new Position(widget.getOriginalX(), widget.getOriginalY(), widget.getRelativeX(), widget.getRelativeY(), false);
		}

		final int parentWidth = Math.max(1, parent.getWidth());
		final int parentHeight = Math.max(1, parent.getHeight());
		final Rectangle universeBounds = universe != null ? universe.getBounds() : null;
		if (!usableBounds(universeBounds)) {
			return localRootPosition(widget, x - viewportOffsetX, y - viewportOffsetY, width, height, parentWidth, parentHeight);
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
			return localRootPosition(widget, x, y, width, height, parentWidth, parentHeight);
		}

		final int relativeX = universeBounds.x - parentX + x - viewportOffsetX;
		final int relativeY = universeBounds.y - parentY + y - viewportOffsetY;
		return new Position(positionX(widget, relativeX, parentWidth, width), positionY(widget, relativeY, parentHeight, height), relativeX, relativeY, true);
	}

	/*
	 * Mounted dialogue subinterfaces are positioned within CHATBOX.UNIVERSE.
	 * During RuneLite overlay dragging, a parent's absolute getBounds() may still
	 * describe its old screen origin even after the chatbox has moved. Resolving
	 * the parent through mounted relative coordinates avoids feeding that stale
	 * screen-space origin into a forced dialogue-root position.
	 */
	private Position mountedRootPosition(Widget widget, Widget parent, Widget universe, int x, int y, int width, int height) {
		if (widget == null || parent == null || universe == null) {
			return null;
		}

		int offsetX = 0;
		int offsetY = 0;
		Widget ancestor = parent;
		for (int depth = 0; depth < 16 && ancestor != null && ancestor != universe; depth++) {
			offsetX += ancestor.getRelativeX();
			offsetY += ancestor.getRelativeY();
			ancestor = ancestor.getParent();
		}
		if (ancestor != universe) {
			return null;
		}

		final int relativeX = x - offsetX - viewportOffsetX;
		final int relativeY = y - offsetY - viewportOffsetY;
		final int parentWidth = Math.max(1, parent.getWidth());
		final int parentHeight = Math.max(1, parent.getHeight());
		return new Position(positionX(widget, relativeX, parentWidth, width), positionY(widget, relativeY, parentHeight, height), relativeX, relativeY, true);
	}

	private static Position localRootPosition(Widget widget, int x, int y, int width, int height, int parentWidth, int parentHeight) {
		return new Position(positionX(widget, x, parentWidth, width), positionY(widget, y, parentHeight, height), x, y, true);
	}

	private static boolean usableBounds(Rectangle bounds) {
		return usableOrigin(bounds) && bounds.width > 0 && bounds.height > 0;
	}

	private static boolean usableOrigin(Rectangle bounds) {
		/*
		 * Top-mounted dialogue parents can legitimately start above the canvas
		 * (for example, Y=-24). Reject only RuneLite's unresolved (-1,-1)
		 * sentinel rather than all negative coordinates.
		 */
		return bounds != null && (bounds.x != -1 || bounds.y != -1);
	}

	private static void applyPortraitRoot(PortraitState state, Position position, int width, int height, MutableResult result) {
		final Widget root = state.root;
		/* A failed mount validation must never preserve the old forced Y.
		 * In the unforced case, use the pre-fit native geometry rather than
		 * the last original position written by a preceding fit pass. */
		final int x = position.force ? position.x : state.rootGeometry.x;
		final int y = position.force ? position.y : state.rootGeometry.y;
		boolean changed = applyGeometryFields(root, x, y, width, height);

		if (position.force) {
			if (!state.rootForced || root.getRelativeX() != position.relativeX || root.getRelativeY() != position.relativeY) {
				root.setForcedPosition(position.relativeX, position.relativeY);
				state.rootForced = true;
				changed = true;
			}
		} else if (state.rootForced) {
			root.setForcedPosition(-1, -1);
			state.rootForced = false;
			changed = true;
		}

		if (!changed) {
			return;
		}

		root.revalidate();
		result.mutations++;
		result.revalidates++;
	}

	/**
	 * ================================================================
	 * NATIVE WIDGET RESTORATION
	 * ================================================================
	 */
	private MessageState restoreMessage(MessageState state, MutableResult result) {
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
		restoreMessageWidget(state.safe, state.safeGeometry, state.safeModes, null, state.safeModesForced, result);
		restoreMessageWidget(state.content, state.contentGeometry, state.contentModes, null, state.contentModesForced, result);
		restoreMessageWidget(state.text, state.textGeometry, state.textModes, state.sourceText, state.textModesForced, result);
		restoreMessageWidget(state.continueWidget, state.continueGeometry, state.continueModes, state.continueSourceText, state.continueModesForced, result);
		restoreNativeRootPosition(state.root, state.rootGeometry, ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_BODY_HEIGHT, result);
		return null;
	}

	private static void restoreMessageWidget(Widget widget, Geometry geometry, WidgetModes modes, String text, boolean restoreModes, MutableResult result) {
		if (widget == null || geometry == null || modes == null) {
			return;
		}

		boolean changed = restoreModes && modes.restore(widget);
		changed |= applyGeometryFields(widget, geometry.x, geometry.y, geometry.width, geometry.height);
		if (text != null && !text.equals(widget.getText())) {
			widget.setText(text);
			changed = true;
		}
		finishGeometry(widget, changed, geometry.x, geometry.y, geometry.width, geometry.height, result);
	}

	private SpriteState restoreSprite(SpriteState state, MutableResult result) {
		if (state == null) {
			return null;
		}

		final boolean restoreHostGeometry = isMounted(state.root) && state.root.getParent() == state.host;

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

		if (restoreHostGeometry) {
			restoreGeometry(state.host, state.hostGeometry, result);
		}
		restoreGeometry(state.root, state.rootGeometry, result);
		restoreGeometry(state.item, state.itemGeometry, result);
		if (state.titleWidget != null && state.titleGeometry != null) {
			applyTextGeometry(state.titleWidget, state.titleGeometry.x, state.titleGeometry.y, state.titleGeometry.width, state.titleGeometry.height, state.titleSourceText, result);
		}
		applyTextGeometry(state.text, state.textGeometry.x, state.textGeometry.y, state.textGeometry.width, state.textGeometry.height, state.sourceText, result);
		restoreSpriteContinue(state, result);
		restoreNativeRootPosition(state.root, state.rootGeometry, state.hostGeometry.width, state.hostGeometry.height, result);
		return null;
	}

	private static void restoreSpriteContinue(SpriteState state, MutableResult result) {
		final Widget widget = state.continueWidget;
		if (widget == null) {
			return;
		}

		boolean changed = false;
		if (state.continueModesForced) {
			if (widget.getXPositionMode() != state.continueXPositionMode) {
				widget.setXPositionMode(state.continueXPositionMode);
				changed = true;
			}
			if (widget.getYPositionMode() != state.continueYPositionMode) {
				widget.setYPositionMode(state.continueYPositionMode);
				changed = true;
			}
			if (widget.getWidthMode() != state.continueWidthMode) {
				widget.setWidthMode(state.continueWidthMode);
				changed = true;
			}
			if (widget.getHeightMode() != state.continueHeightMode) {
				widget.setHeightMode(state.continueHeightMode);
				changed = true;
			}
			state.continueModesForced = false;
		}

		changed |= applyGeometryFields(widget, state.continueGeometry.x, state.continueGeometry.y, state.continueGeometry.width, state.continueGeometry.height);
		if (!changed) {
			return;
		}

		widget.revalidate();
		result.mutations++;
		result.revalidates++;
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
		applyHidden(state.head, state.headHidden, result);
		restoreGeometry(state.content, state.contentGeometry, result);
		restoreGeometry(state.name, state.nameGeometry, result);
		restoreGeometry(state.continueWidget, state.continueGeometry, result);
		applyTextGeometry(state.text, state.textGeometry.x, state.textGeometry.y, state.textGeometry.width, state.textGeometry.height, state.sourceText, result);
		restoreNativeRootPosition(state.root, state.rootGeometry, ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_BODY_HEIGHT, result);
		return null;
	}

	/**
	 * ================================================================
	 * TEXT WRAPPING & FONT MEASUREMENT
	 * ================================================================
	 */
	private static String normalizeDialogueText(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		return text.replace("<br>", " ").replaceAll("\\s+", " ").trim();
	}

	private static boolean sameDialogueText(String first, String second) {
		final String normalizedFirst = normalizeDialogueText(first);
		final String normalizedSecond = normalizeDialogueText(second);
		if (normalizedFirst == null) {
			return normalizedSecond == null;
		}

		return normalizedFirst.equals(normalizedSecond);
	}

	private String wrapResolvedText(Widget widget, String text, int maxWidth) {
		return wrapText(resolveFont(widget), text, maxWidth);
	}

	private FontTypeFace resolveFont(Widget widget) {
		if (widget == null) {
			return null;
		}

		final int fontId = widget.getFontId();
		final FontTypeFace direct = widget.getFont();
		if (direct != null) {
			fontCache.put(fontId, direct);
			return direct;
		}

		final FontTypeFace cached = fontCache.get(fontId);
		if (cached != null) {
			return cached;
		}

		final Widget probe = client.getWidget(InterfaceID.Chatbox.INPUT);
		if (probe == null) {
			return null;
		}

		final int originalFontId = probe.getFontId();
		try {
			probe.setFontId(fontId);
			final FontTypeFace resolved = probe.getFont();
			if (resolved != null) {
				fontCache.put(fontId, resolved);
			}
			return resolved;
		} finally {
			probe.setFontId(originalFontId);
		}
	}

	private static String wrapText(Widget widget, String text, int maxWidth) {
		return wrapText(widget != null ? widget.getFont() : null, text, maxWidth);
	}

	private static String wrapText(FontTypeFace font, String text, int maxWidth) {
		if (text == null || text.isEmpty() || maxWidth <= 0 || font == null || font.getTextWidth(text) <= maxWidth) {
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
		final int lineHeight = optionLineHeight(widget, nativeHeight);
		if (widget != null && widget.getFontId() == ChatFont.QUILL_8.getFontId()) {
			return lines <= 1 ? nativeHeight : Math.max(nativeHeight, lines * lineHeight);
		}

		return Math.max(1, lines * lineHeight);
	}

	private static int optionLineHeight(Widget widget, int nativeHeight) {
		if (widget != null && widget.getLineHeight() > 0) {
			return widget.getLineHeight();
		}

		final FontTypeFace font = widget != null ? widget.getFont() : null;
		return font != null ? Math.max(1, font.getBaseline()) : Math.max(1, nativeHeight);
	}

	private static int dialogueTextWidth(Widget widget, String text) {
		if (widget == null || text == null || text.isEmpty()) {
			return 0;
		}

		final FontTypeFace font = widget.getFont();
		return font != null ? font.getTextWidth(text) : 0;
	}

	private static int dialogueTextHeight(Widget widget, String text) {
		return Math.max(1, lineCount(text) * dialogueLineHeight(widget));
	}

	private static int dialogueLineHeight(Widget widget) {
		if (widget != null && widget.getLineHeight() > 0) {
			return widget.getLineHeight();
		}

		final FontTypeFace font = widget != null ? widget.getFont() : null;
		return font != null ? Math.max(1, font.getBaseline() + DIALOGUE_LINE_GAP) : 16;
	}

	private static int portraitSectionGap(PortraitState state) {
		final int nameGap = Math.max(0, state.textGeometry.y - state.nameGeometry.y - state.nameGeometry.height);
		final int continueGap = Math.max(0, state.continueGeometry.y - state.textGeometry.y - state.textGeometry.height);
		return Math.max(MIN_DIALOGUE_GAP, Math.max(scaledDialogueSpacing(nameGap, state.name, state.nameGeometry.height, state.text, state.textGeometry.height), scaledDialogueSpacing(continueGap, state.text, state.textGeometry.height, state.continueWidget, state.continueGeometry.height)));
	}

	private static int spriteSectionGap(SpriteState state) {
		final int nativeGap = Math.max(0, state.continueGeometry.y - state.textGeometry.y - state.textGeometry.height);
		return Math.max(MIN_DIALOGUE_GAP, scaledDialogueSpacing(nativeGap, state.text, state.textGeometry.height, state.continueWidget, state.continueGeometry.height));
	}

	private static int scaledDialogueSpacing(int nativeSpacing, Widget first, int firstNativeHeight, Widget second, int secondNativeHeight) {
		if (nativeSpacing <= 0 || first == null || second == null || first.getFontId() == ChatFont.QUILL_8.getFontId() && second.getFontId() == ChatFont.QUILL_8.getFontId()) {
			return Math.max(0, nativeSpacing);
		}

		final int nativeHeight = Math.max(1, (firstNativeHeight + secondNativeHeight) / 2);
		final int selectedHeight = Math.max(1, (dialogueLineHeight(first) + dialogueLineHeight(second)) / 2);
		return Math.max(0, Math.round((float) nativeSpacing * selectedHeight / nativeHeight));
	}

	private static int fittedDialogueGap(int preferredGap, int availableGap) {
		return Math.max(MIN_DIALOGUE_GAP, Math.min(preferredGap, Math.max(MIN_DIALOGUE_GAP, availableGap)));
	}

	private static String correctedDialogueText(Widget widget, String text) {
		if (widget == null || text == null || text.isEmpty()) {
			return text;
		}

		final int fontId = widget.getFontId();
		for (ChatFont font : ChatFont.values()) {
			if (font.getFontId() == fontId) {
				return FontGlyphCorrections.apply(font, text);
			}
		}
		return text;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
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

	private static boolean isVisibleMounted(Widget widget) {
		return isMounted(widget) && !widget.isHidden();
	}

	private static boolean isMounted(Widget widget) {
		return widget != null && widget.getParent() != null && !widget.isSelfHidden();
	}

	private static void restoreWidget(Widget widget, WidgetState baseline, MutableResult result) {
		if (widget == null || baseline == null) {
			return;
		}

		applyTextGeometry(widget, baseline.geometry.x, baseline.geometry.y, baseline.geometry.width, baseline.geometry.height, baseline.sourceText, result);
		applyHidden(widget, baseline.hidden, result);
	}

	private static void restoreGeometry(Widget widget, Geometry geometry, MutableResult result) {
		if (widget != null && geometry != null) {
			applyGeometry(widget, geometry.x, geometry.y, geometry.width, geometry.height, result);
		}
	}

	private static void restoreNativeRootPosition(Widget widget, Geometry geometry, int parentWidth, int parentHeight, MutableResult result) {
		if (widget == null || geometry == null) {
			return;
		}

		final int relativeX = resolvedPosition(geometry.x, widget.getXPositionMode(), parentWidth, geometry.width);
		final int relativeY = resolvedPosition(geometry.y, widget.getYPositionMode(), parentHeight, geometry.height);
		/* The baseline original X/Y have already been restored by
		 * restoreGeometry(). Revalidate an unresolved root with the
		 * supported API rather than directly mutating RuneLite's derived
		 * relative coordinates (deprecated setRelativeX/Y). */
		if (relativeX != Integer.MIN_VALUE && widget.getRelativeX() != relativeX || relativeY != Integer.MIN_VALUE && widget.getRelativeY() != relativeY) {
			widget.revalidate();
			result.revalidates++;
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

	private static void applyAbsoluteGeometry(Widget widget, int x, int y, int width, int height, MutableResult result) {
		if (widget == null) {
			return;
		}

		boolean changed = false;
		if (widget.getXPositionMode() != WidgetPositionMode.ABSOLUTE_LEFT) {
			widget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			changed = true;
		}
		if (widget.getYPositionMode() != WidgetPositionMode.ABSOLUTE_LEFT) {
			widget.setYPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			changed = true;
		}
		if (widget.getWidthMode() != WidgetSizeMode.ABSOLUTE) {
			widget.setWidthMode(WidgetSizeMode.ABSOLUTE);
			changed = true;
		}
		if (widget.getHeightMode() != WidgetSizeMode.ABSOLUTE) {
			widget.setHeightMode(WidgetSizeMode.ABSOLUTE);
			changed = true;
		}
		changed |= applyGeometryFields(widget, x, y, width, height);
		finishGeometry(widget, changed, x, y, width, height, result);
	}

	private static void applyAbsoluteTextGeometry(Widget widget, int x, int y, int width, int height, String text, MutableResult result) {
		if (widget == null) {
			return;
		}

		boolean changed = false;
		if (widget.getXPositionMode() != WidgetPositionMode.ABSOLUTE_LEFT) {
			widget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			changed = true;
		}
		if (widget.getYPositionMode() != WidgetPositionMode.ABSOLUTE_LEFT) {
			widget.setYPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			changed = true;
		}
		if (widget.getWidthMode() != WidgetSizeMode.ABSOLUTE) {
			widget.setWidthMode(WidgetSizeMode.ABSOLUTE);
			changed = true;
		}
		if (widget.getHeightMode() != WidgetSizeMode.ABSOLUTE) {
			widget.setHeightMode(WidgetSizeMode.ABSOLUTE);
			changed = true;
		}
		changed |= applyGeometryFields(widget, x, y, width, height);
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

	private static void finishGeometry(Widget widget, boolean changed, int x, int y, int width, int height, MutableResult result) {
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
		return expectedX != Integer.MIN_VALUE && widget.getRelativeX() != expectedX || expectedY != Integer.MIN_VALUE && widget.getRelativeY() != expectedY;
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

	/**
	 * ================================================================
	 * CAPTURED WIDGET STATE & RESULTS
	 * ================================================================
	 */
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
				if (current != null && current.renderedText != null && !sameDialogueText(current.renderedText, widget.getText())) {
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

	private static final class OptionLayout {
		private final int height;
		private final int top;
		private final int bottom;
		private final int gap;

		private OptionLayout(int height, int top, int bottom, int gap) {
			this.height = height;
			this.top = top;
			this.bottom = bottom;
			this.gap = gap;
		}
	}

	private static final class OptionSpacing {
		private final int top;
		private final int bottom;
		private final int gap;

		private OptionSpacing(int top, int bottom, int gap) {
			this.top = top;
			this.bottom = bottom;
			this.gap = gap;
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
		private final int continueHeight;
		private final String continueText;
		private final int height;

		private SpriteLayout(int itemX, int itemY, int textX, int textY, int textWidth, int textHeight, String text, int continueX, int continueY, int continueWidth, int continueHeight, String continueText, int height) {
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
			this.continueHeight = continueHeight;
			this.continueText = continueText;
			this.height = height;
		}

		private SpriteLayout withVerticalShift(int shift) {
			return new SpriteLayout(itemX, itemY + shift, textX, textY + shift, textWidth, textHeight, text, continueX, continueY + shift, continueWidth, continueHeight, continueText, height + shift);
		}
	}

	private static final class MessageState {
		private final Widget root;
		private final Widget safe;
		private final Widget content;
		private final Widget text;
		private final Widget continueWidget;
		private final Geometry rootGeometry;
		private final Geometry safeGeometry;
		private final Geometry contentGeometry;
		private final Geometry textGeometry;
		private final Geometry continueGeometry;
		private final Geometry rootLayout;
		private final Geometry safeLayout;
		private final Geometry contentLayout;
		private final Geometry textLayout;
		private final Geometry continueLayout;
		private final WidgetModes safeModes;
		private final WidgetModes contentModes;
		private final WidgetModes textModes;
		private final WidgetModes continueModes;
		private final int rootWidthOffset;
		private final int rootHeightOffset;
		private final int continueNativeHeight;
		private String sourceText;
		private String renderedText;
		private String continueSourceText;
		private String continueRenderedText;
		private boolean rootForced;
		private boolean safeModesForced;
		private boolean contentModesForced;
		private boolean textModesForced;
		private boolean continueModesForced;

		private MessageState(Widget root, Widget safe, Widget content, Widget text, Widget continueWidget, int effectiveWidth, int effectiveBodyHeight) {
			this.root = root;
			this.safe = safe;
			this.content = content;
			this.text = text;
			this.continueWidget = continueWidget;
			this.rootGeometry = Geometry.of(root);
			this.safeGeometry = Geometry.of(safe);
			this.contentGeometry = Geometry.of(content);
			this.textGeometry = Geometry.of(text);
			this.continueGeometry = Geometry.of(continueWidget);
			this.rootLayout = Geometry.resolved(root);
			this.safeLayout = Geometry.resolved(safe);
			this.contentLayout = Geometry.resolved(content);
			this.textLayout = Geometry.resolved(text);
			this.continueLayout = Geometry.resolved(continueWidget);
			this.safeModes = WidgetModes.of(safe);
			this.contentModes = WidgetModes.of(content);
			this.textModes = WidgetModes.of(text);
			this.continueModes = WidgetModes.of(continueWidget);
			this.rootWidthOffset = rootLayout != null ? rootLayout.width - effectiveWidth : 0;
			this.rootHeightOffset = rootLayout != null ? rootLayout.height - effectiveBodyHeight : 0;
			this.continueNativeHeight = continueWidget != null ? Math.max(1, continueWidget.getHeight()) : 1;
			this.sourceText = text != null ? text.getText() : null;
			this.renderedText = sourceText;
			this.continueSourceText = continueWidget != null ? continueWidget.getText() : null;
			this.continueRenderedText = continueSourceText;
		}

		private void captureText() {
			if (text != null) {
				final String current = text.getText();
				if (renderedText == null || !sameDialogueText(renderedText, current)) {
					sourceText = current;
					renderedText = current;
				}
			}
			if (continueWidget != null) {
				final String current = continueWidget.getText();
				if (continueRenderedText == null || !sameDialogueText(continueRenderedText, current)) {
					continueSourceText = current;
					continueRenderedText = current;
				}
			}
		}

		private boolean isComplete() {
			return rootGeometry != null && safeGeometry != null && contentGeometry != null && textGeometry != null && continueGeometry != null && rootLayout != null && rootLayout.hasSize() && safeLayout != null && safeLayout.hasSize() && contentLayout != null && contentLayout.hasSize() && textLayout != null && textLayout.hasSize() && continueLayout != null && continueLayout.hasSize() && safeModes != null && contentModes != null && textModes != null && continueModes != null;
		}
	}

	private static final class SpriteState {
		private final Widget host;
		private final Widget root;
		private final Widget item;
		private final Widget titleWidget;
		private final Widget text;
		private final Widget continueWidget;
		private final Geometry hostGeometry;
		private final Geometry rootGeometry;
		private final Geometry itemGeometry;
		private final Geometry titleGeometry;
		private final Geometry textGeometry;
		private final Geometry continueGeometry;
		private final int continueXPositionMode;
		private final int continueYPositionMode;
		private final int continueWidthMode;
		private final int continueHeightMode;
		private final int continueNativeHeight;
		private String titleSourceText;
		private String titleRenderedText;
		private String sourceText;
		private String renderedText;
		private String continueSourceText;
		private String continueRenderedText;
		private boolean rootForced;
		private boolean hostForced;
		private boolean continueModesForced;

		private SpriteState(Widget host, Widget root, Widget item, Widget titleWidget, Widget text, Widget continueWidget) {
			this.host = host;
			this.root = root;
			this.item = item;
			this.titleWidget = titleWidget;
			this.text = text;
			this.continueWidget = continueWidget;
			this.hostGeometry = Geometry.of(host);
			this.rootGeometry = Geometry.of(root);
			this.itemGeometry = Geometry.of(item);
			this.titleGeometry = Geometry.of(titleWidget);
			this.textGeometry = Geometry.of(text);
			this.continueGeometry = Geometry.of(continueWidget);
			this.continueXPositionMode = continueWidget != null ? continueWidget.getXPositionMode() : WidgetPositionMode.ABSOLUTE_LEFT;
			this.continueYPositionMode = continueWidget != null ? continueWidget.getYPositionMode() : WidgetPositionMode.ABSOLUTE_LEFT;
			this.continueWidthMode = continueWidget != null ? continueWidget.getWidthMode() : WidgetSizeMode.ABSOLUTE;
			this.continueHeightMode = continueWidget != null ? continueWidget.getHeightMode() : WidgetSizeMode.ABSOLUTE;
			this.continueNativeHeight = continueWidget != null ? Math.max(1, continueWidget.getHeight()) : 1;
			this.titleSourceText = titleWidget != null ? titleWidget.getText() : null;
			this.titleRenderedText = titleSourceText;
			this.sourceText = text != null ? text.getText() : null;
			this.renderedText = sourceText;
			this.continueSourceText = continueWidget != null ? continueWidget.getText() : null;
			this.continueRenderedText = continueSourceText;
		}

		private void captureText() {
			if (titleWidget != null) {
				final String current = titleWidget.getText();
				if (titleRenderedText == null || !sameDialogueText(titleRenderedText, current)) {
					titleSourceText = current;
					titleRenderedText = current;
				}
			}

			if (text != null) {
				final String current = text.getText();
				if (renderedText == null || !sameDialogueText(renderedText, current)) {
					sourceText = current;
					renderedText = current;
				}
			}

			if (continueWidget != null) {
				final String current = continueWidget.getText();
				if (continueRenderedText == null || !sameDialogueText(continueRenderedText, current)) {
					continueSourceText = current;
					continueRenderedText = current;
				}
			}
		}

		private boolean isComplete() {
			return hostGeometry != null && rootGeometry != null && itemGeometry != null && textGeometry != null && continueGeometry != null;
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
		private final String nameText;
		private final int nameHeight;
		private final int textY;
		private final String text;
		private final int textHeight;
		private final int continueY;
		private final String continueText;
		private final int continueHeight;
		private final int requiredHeight;

		private PortraitContentLayout(int nameX, int textX, int continueX, int nameWidth, int textWidth, int continueWidth, int nameY, String nameText, int nameHeight, int textY, String text, int textHeight, int continueY, String continueText, int continueHeight, int requiredHeight) {
			this.nameX = nameX;
			this.textX = textX;
			this.continueX = continueX;
			this.nameWidth = nameWidth;
			this.textWidth = textWidth;
			this.continueWidth = continueWidth;
			this.nameY = nameY;
			this.nameText = nameText;
			this.nameHeight = nameHeight;
			this.textY = textY;
			this.text = text;
			this.textHeight = textHeight;
			this.continueY = continueY;
			this.continueText = continueText;
			this.continueHeight = continueHeight;
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
		private final boolean headHidden;
		private String nameSourceText;
		private String nameRenderedText;
		private String sourceText;
		private String renderedText;
		private String continueSourceText;
		private String continueRenderedText;
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
			this.headHidden = head != null && head.isSelfHidden();
			this.nameSourceText = name != null ? name.getText() : null;
			this.nameRenderedText = nameSourceText;
			this.sourceText = text != null ? text.getText() : null;
			this.renderedText = sourceText;
			this.continueSourceText = continueWidget != null ? continueWidget.getText() : null;
			this.continueRenderedText = continueSourceText;
		}

		private void captureText() {
			if (name != null) {
				final String current = name.getText();
				if (nameRenderedText == null || !sameDialogueText(nameRenderedText, current)) {
					nameSourceText = current;
					nameRenderedText = current;
				}
			}

			if (text != null) {
				final String current = text.getText();
				if (renderedText == null || !sameDialogueText(renderedText, current)) {
					sourceText = current;
					renderedText = current;
				}
			}

			if (continueWidget != null) {
				final String current = continueWidget.getText();
				if (continueRenderedText == null || !sameDialogueText(continueRenderedText, current)) {
					continueSourceText = current;
					continueRenderedText = current;
				}
			}
		}

		private boolean isComplete() {
			return rootGeometry != null && safeGeometry != null && headGeometry != null && contentGeometry != null && nameGeometry != null && continueGeometry != null && textGeometry != null;
		}
	}

	private static final class WidgetModes {
		private final int x;
		private final int y;
		private final int width;
		private final int height;

		private WidgetModes(Widget widget) {
			x = widget.getXPositionMode();
			y = widget.getYPositionMode();
			width = widget.getWidthMode();
			height = widget.getHeightMode();
		}

		private boolean restore(Widget widget) {
			boolean changed = false;
			if (widget.getXPositionMode() != x) {
				widget.setXPositionMode(x);
				changed = true;
			}
			if (widget.getYPositionMode() != y) {
				widget.setYPositionMode(y);
				changed = true;
			}
			if (widget.getWidthMode() != width) {
				widget.setWidthMode(width);
				changed = true;
			}
			if (widget.getHeightMode() != height) {
				widget.setHeightMode(height);
				changed = true;
			}
			return changed;
		}

		private static WidgetModes of(Widget widget) {
			return widget == null ? null : new WidgetModes(widget);
		}
	}

	private static final class Geometry {
		private final int x;
		private final int y;
		private final int width;
		private final int height;

		private Geometry(Widget widget) {
			this(widget.getOriginalX(), widget.getOriginalY(), widget.getOriginalWidth(), widget.getOriginalHeight());
		}

		private Geometry(int x, int y, int width, int height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		private boolean hasSize() {
			return width > 0 && height > 0;
		}

		private static Geometry of(Widget widget) {
			return widget == null ? null : new Geometry(widget);
		}

		private static Geometry resolved(Widget widget) {
			return widget == null ? null : new Geometry(widget.getRelativeX(), widget.getRelativeY(), widget.getWidth(), widget.getHeight());
		}
	}

	public static final class FitRequirement {
		private static final FitRequirement NONE = new FitRequirement(false, 0, 0, 0);

		private final boolean active;
		private final int minimumWidth;
		private final int minimumBodyHeight;
		private final int preferredBodyHeight;

		private FitRequirement(boolean active, int minimumWidth, int minimumBodyHeight, int preferredBodyHeight) {
			this.active = active;
			this.minimumWidth = Math.max(0, minimumWidth);
			this.minimumBodyHeight = Math.max(0, minimumBodyHeight);
			this.preferredBodyHeight = Math.max(this.minimumBodyHeight, preferredBodyHeight);
		}

		private FitRequirement combine(FitRequirement other) {
			if (other == null || !other.active) {
				return this;
			}
			if (!active) {
				return other;
			}

			return new FitRequirement(true, Math.max(minimumWidth, other.minimumWidth), Math.max(minimumBodyHeight, other.minimumBodyHeight), Math.max(preferredBodyHeight, other.preferredBodyHeight));
		}

		public boolean isActive() {
			return active;
		}

		public int getMinimumWidth() {
			return minimumWidth;
		}

		public int getMinimumBodyHeight() {
			return minimumBodyHeight;
		}

		public int getPreferredBodyHeight() {
			return preferredBodyHeight;
		}

		public boolean fitsMinimum(int width, int bodyHeight) {
			return !active || width >= minimumWidth && bodyHeight >= minimumBodyHeight;
		}

		public boolean fitsPreferred(int width, int bodyHeight) {
			return !active || width >= minimumWidth && bodyHeight >= preferredBodyHeight;
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
