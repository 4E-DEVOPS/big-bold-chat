package com.bigboldchat.chatbox;

import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.layout.ChatboxBounds;
import com.bigboldchat.layout.SideContainerLayout;

import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * Owns resizable-layout chatbox geometry and presentation.
 */
public final class ChatboxResizeService {
	/*
	 * Native chat view values.
	 */
	private static final int CHAT_VIEW_ALL = 0;
	private static final int CHAT_VIEW_HIDDEN = 1337;

	/*
	 * Native chat visibility lifecycle.
	 */
	static final int CHAT_VISIBILITY = 923;

	/*
	 * Native top-level relayout helper.
	 */
	private static final int TOPLEVEL_RELAYOUT = 1972;

	private static final int[] CHAT_CONTROL_IDS = {
			InterfaceID.Chatbox.CHAT_ALL,
			InterfaceID.Chatbox.CHAT_GAME,
			InterfaceID.Chatbox.CHAT_PUBLIC,
			InterfaceID.Chatbox.CHAT_PRIVATE,
			InterfaceID.Chatbox.CHAT_FRIENDSCHAT,
			InterfaceID.Chatbox.CHAT_CLAN,
			InterfaceID.Chatbox.CHAT_TRADE
	};

	private static final int[] CHAT_CONTROL_GRAPHIC_IDS = {
			InterfaceID.Chatbox.CHAT_ALL_GRAPHIC,
			InterfaceID.Chatbox.CHAT_GAME_GRAPHIC,
			InterfaceID.Chatbox.CHAT_PUBLIC_GRAPHIC,
			InterfaceID.Chatbox.CHAT_PRIVATE_GRAPHIC,
			InterfaceID.Chatbox.CHAT_FRIENDSCHAT_GRAPHIC,
			InterfaceID.Chatbox.CHAT_CLAN_GRAPHIC,
			InterfaceID.Chatbox.CHAT_TRADE_GRAPHIC
	};

	private final Client client;
	private final PerformanceMetrics performanceMetrics;
	private final ChatboxControlsLayout controlsLayout;
	private final ChatboxBackgroundService backgroundService;
	private final SideContainerLayout sideContainerLayout;

	private boolean resizedLayoutApplied;
	private boolean foregroundSuppressionActive;
	private boolean foregroundSuppressionOverridden;
	private boolean manualSuppressionActive;
	private boolean suppressionOwnsHiddenView;
	private boolean chatControlClickPending;
	private boolean chatboxButtonsHidden;
	private boolean scrollBaselineBottom;
	private boolean scrollBaselineAvailable;

	private int scrollBaselineDepth;
	private int lastVisibleChatView = CHAT_VIEW_ALL;
	private int lastVisibleChatGraphic = -1;

	public ChatboxResizeService(Client client, PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.performanceMetrics = performanceMetrics;
		this.controlsLayout = new ChatboxControlsLayout(client);
		this.backgroundService = new ChatboxBackgroundService(client);
		this.sideContainerLayout = new SideContainerLayout(client);
	}

	/*
	 * ================================================================
	 * LAYOUT
	 * ================================================================
	 */
	ChatboxLayout getLayout() {
		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL) {
			return ChatboxLayout.FIXED;
		}

		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			return ChatboxLayout.RESIZABLE_CLASSIC;
		}

		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			return ChatboxLayout.RESIZABLE_MODERN;
		}

		return ChatboxLayout.UNKNOWN;
	}

	private Widget getSlot(ChatboxLayout layout) {
		switch (layout) {
			case RESIZABLE_CLASSIC:
				return client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
			case RESIZABLE_MODERN:
				return client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);
			case FIXED:
				return client.getWidget(InterfaceID.Toplevel.CHAT_CONTAINER);
			default:
				return null;
		}
	}

	/*
	 * ================================================================
	 * SCRIPT LIFECYCLE
	 * ================================================================
	 */
	public ResizeResult onScriptPreFired(ScriptPreFired event, int width, int height) {
		if (event == null) {
			return ResizeResult.NOT_APPLIED;
		}

		final int scriptId = event.getScriptId();
		if (tracksScrollBaseline(scriptId)) {
			beginScrollBaseline();
		}

		if (scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE) {
			sideContainerLayout.beginNativeLayout();
		}

		if (scriptId == ScriptID.BUILD_CHATBOX || scriptId == ScriptID.SPLITPM_CHANGED || scriptId == TOPLEVEL_RELAYOUT) {
			return applySize(width, height);
		}

		return ResizeResult.NOT_APPLIED;
	}

	public ResizeResult onScriptPostFired(ScriptPostFired event, int width, int height) {
		if (event == null) {
			return ResizeResult.NOT_APPLIED;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == CHAT_VISIBILITY) {
			rememberVisibleChatView();
			syncChatAreaVisibility();
			recordMutations(controlsLayout.syncHidden(chatboxButtonsHidden));
		}

		if (scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE) {
			sideContainerLayout.endNativeLayout();
		}

		ResizeResult result = ResizeResult.NOT_APPLIED;
		if (scriptId == ScriptID.TOPLEVEL_REDRAW
				|| scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE
				|| scriptId == ScriptID.MESSAGE_LAYER_OPEN) {
			result = applySize(width, height);
		}

		if (tracksScrollBaseline(scriptId)) {
			endScrollBaseline();
		}

		return result;
	}

	private boolean tracksScrollBaseline(int scriptId) {
		return scriptId == ScriptID.BUILD_CHATBOX
				|| scriptId == ScriptID.SPLITPM_CHANGED
				|| scriptId == TOPLEVEL_RELAYOUT
				|| scriptId == ScriptID.TOPLEVEL_REDRAW
				|| scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE
				|| scriptId == ScriptID.MESSAGE_LAYER_OPEN;
	}

	/*
	 * ================================================================
	 * CHATBOX GEOMETRY
	 * ================================================================
	 */
	public ResizeResult applySize(int width, int height) {
		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime()
				: 0L;
		final ChatboxLayout layout = getLayout();
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);

		recordMutations(controlsLayout.syncHidden(chatboxButtonsHidden));

		if (layout == ChatboxLayout.FIXED || layout == ChatboxLayout.UNKNOWN) {
			syncChatPresentation(false);
			sideContainerLayout.reset();
			resizedLayoutApplied = false;
			return ResizeResult.NOT_APPLIED;
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (slot == null || universe == null || chatArea == null) {
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		/*
		 * Ignore transient layout swaps before UNIVERSE mounts under the active chat container.
		 */
		final Widget parent = universe.getParent();
		if (parent == null || parent.getId() != slot.getId()) {
			return ResizeResult.NOT_APPLIED;
		}

		if (layout == ChatboxLayout.RESIZABLE_MODERN) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.apply(slot, width, height);

			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		} else {
			sideContainerLayout.reset();
		}

		/*
		 * Clamp live geometry without changing configured dimensions.
		 */
		final ChatboxBounds.Result effective = ChatboxBounds.resolve(client, slot, width, height, chatboxButtonsHidden);
		final int effectiveWidth = effective.getWidth();
		final int effectiveHeight = effective.getHeight();
		final int effectiveBodyHeight = ChatboxGeometry.bodyHeight(effectiveHeight, chatboxButtonsHidden);
		final boolean widthChanged = slot.getWidth() != effectiveWidth
				|| universe.getWidth() != effectiveWidth || chatArea.getWidth() != effectiveWidth;
		final boolean heightChanged = slot.getHeight() != effectiveHeight
				|| universe.getHeight() != effectiveHeight || chatArea.getHeight() != effectiveBodyHeight;
		final boolean controlsChanged = !controlsLayout.matches(effectiveWidth);

		if (widthChanged || heightChanged || controlsChanged) {
			applyGeometry(
					slot, universe, chatArea, effectiveWidth, effectiveHeight, effectiveBodyHeight, controlsChanged);
		}

		final ChatboxBackgroundService.Result backgroundResult =
				backgroundService.apply(chatArea, effectiveWidth, effectiveBodyHeight);

		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());

		syncChatPresentation(backgroundService.isOpaque() && effective.isForegroundOverlap());

		resizedLayoutApplied = true;

		recordApply(started, widthChanged, heightChanged);

		return new ResizeResult(true, widthChanged, heightChanged);
	}

	private void applyGeometry(
			Widget slot,
			Widget universe,
			Widget chatArea,
			int width,
			int height,
			int bodyHeight,
			boolean controlsChanged) {
		if (slot.getWidth() != width || slot.getHeight() != height) {
			slot.setSize(width, height);
			recordMutation();

			slot.revalidate();
			recordRevalidate();
		}

		if (universe.getWidth() != width || universe.getHeight() != height) {
			universe.setSize(width, height, WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
			universe.setForcedPosition(0, 0);
			recordMutation();

			universe.revalidate();
			recordRevalidate();
		}

		final int bodyMargin = Math.max(0, height - bodyHeight);
		if (chatArea.getOriginalWidth() != width
				|| chatArea.getOriginalHeight() != bodyMargin || chatArea.getHeightMode() != WidgetSizeMode.MINUS) {
			chatArea.setSize(width, bodyMargin, chatArea.getWidthMode(), WidgetSizeMode.MINUS);
			recordMutation();
		}

		if (controlsChanged) {
			recordMutations(controlsLayout.apply(width));
		}

		recordRevalidates(ChatboxWidgets.revalidateChildren(universe));
	}

	/*
	 * ================================================================
	 * SCROLL BASELINE
	 * ================================================================
	 */
	public void beginScrollBaseline() {
		if (scrollBaselineDepth++ != 0) {
			return;
		}

		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		if (scrollArea == null) {
			scrollBaselineAvailable = false;
			return;
		}

		final int bottom = Math.max(0, scrollArea.getScrollHeight() - scrollArea.getHeight());
		scrollBaselineBottom = scrollArea.getScrollY() >= bottom;
		scrollBaselineAvailable = true;
	}

	public void endScrollBaseline() {
		if (scrollBaselineDepth <= 0) {
			return;
		}

		scrollBaselineDepth--;
		if (scrollBaselineDepth != 0) {
			return;
		}

		final boolean restoreBottom = scrollBaselineAvailable && scrollBaselineBottom;
		scrollBaselineAvailable = false;
		scrollBaselineBottom = false;
		if (!restoreBottom) {
			return;
		}

		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		if (scrollArea == null) {
			return;
		}

		final int bottom = Math.max(0, scrollArea.getScrollHeight() - scrollArea.getHeight());
		if (scrollArea.getScrollY() != bottom) {
			scrollArea.setScrollY(bottom);
		}
	}

	public void resetScrollBaseline() {
		scrollBaselineDepth = 0;
		scrollBaselineAvailable = false;
		scrollBaselineBottom = false;
	}

	/*
	 * ================================================================
	 * PRESENTATION
	 * ================================================================
	 */
	private void syncChatPresentation(boolean foregroundSuppression) {
		rememberVisibleChatView();

		if (foregroundSuppression != foregroundSuppressionActive) {
			foregroundSuppressionActive = foregroundSuppression;
			foregroundSuppressionOverridden = false;
		}

		/*
		 * Defer presentation sync during native chat-control processing.
		 */
		if (chatControlClickPending) {
			syncChatAreaVisibility();
			return;
		}

		if (isSuppressionRequested()) {
			hideNativeChatView();
			return;
		}

		restoreOwnedChatView();
		syncChatAreaVisibility();
	}

	public void setChatboxButtonsHidden(boolean hidden) {
		chatboxButtonsHidden = hidden;
		recordMutations(controlsLayout.syncHidden(hidden));
	}

	public void toggleChatPresentation() {
		if (isChatViewHidden()) {
			showChatPresentation();
			return;
		}

		rememberVisibleChatView();

		manualSuppressionActive = true;
		hideNativeChatView();
	}

	public void showChatPresentation() {
		manualSuppressionActive = false;

		if (foregroundSuppressionActive) {
			foregroundSuppressionOverridden = true;
		}

		if (!isChatViewHidden()) {
			suppressionOwnsHiddenView = false;
			rememberVisibleChatView();
			syncChatAreaVisibility();
			return;
		}

		suppressionOwnsHiddenView = false;
		setNativeChatView(lastVisibleChatView);
	}

	public boolean onChatControlClicked(Widget widget) {
		if (findChatControl(widget) == -1) {
			return false;
		}

		chatControlClickPending = true;
		return true;
	}

	public void finishChatControlClick() {
		if (!chatControlClickPending) {
			return;
		}

		chatControlClickPending = false;
		manualSuppressionActive = false;
		suppressionOwnsHiddenView = false;

		if (foregroundSuppressionActive) {
			foregroundSuppressionOverridden = !isChatViewHidden();
		}

		rememberVisibleChatView();

		if (!isChatViewHidden()) {
			rememberVisibleChatGraphic();
		}

		syncChatAreaVisibility();
	}

	private int findChatControl(Widget widget) {
		for (Widget current = widget; current != null; current = current.getParent()) {
			final int id = current.getId();

			for (int controlId : CHAT_CONTROL_IDS) {
				if (id == controlId) {
					return controlId;
				}
			}
		}

		return -1;
	}

	private void hideNativeChatView() {
		final int chatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (chatView == CHAT_VIEW_HIDDEN) {
			syncChatAreaVisibility();
			return;
		}

		lastVisibleChatView = chatView;
		suppressionOwnsHiddenView = true;

		setNativeChatView(CHAT_VIEW_HIDDEN);
	}

	private void restoreOwnedChatView() {
		if (!suppressionOwnsHiddenView) {
			return;
		}

		suppressionOwnsHiddenView = false;
		setNativeChatView(lastVisibleChatView);
	}

	private void rememberVisibleChatView() {
		final int chatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (chatView != CHAT_VIEW_HIDDEN) {
			lastVisibleChatView = chatView;
		}
	}

	private void rememberVisibleChatGraphic() {
		for (int graphicId : CHAT_CONTROL_GRAPHIC_IDS) {
			final Widget graphic = client.getWidget(graphicId);
			if (graphic == null) {
				continue;
			}

			final int spriteId = graphic.getSpriteId();
			if (spriteId == SpriteID.ChatTabButton.SELECTED || spriteId == SpriteID.ChatTabButton.SELECTED_HOVERED) {
				lastVisibleChatGraphic = graphicId;
				return;
			}
		}
	}

	private void syncStoredChatGraphic(boolean selected) {
		if (lastVisibleChatGraphic == -1) {
			return;
		}

		final Widget graphic = client.getWidget(lastVisibleChatGraphic);
		if (graphic == null) {
			return;
		}

		final int currentSprite = graphic.getSpriteId();
		final int targetSprite;

		if (selected) {
			targetSprite = currentSprite == SpriteID.ChatTabButton.HOVERED
					? SpriteID.ChatTabButton.SELECTED_HOVERED
					: SpriteID.ChatTabButton.SELECTED;
		} else {
			targetSprite = currentSprite == SpriteID.ChatTabButton.SELECTED_HOVERED
					? SpriteID.ChatTabButton.HOVERED
					: SpriteID.ChatTabButton.BUTTON;
		}

		if (currentSprite == targetSprite) {
			return;
		}

		graphic.setSpriteId(targetSprite);
		recordMutation();
	}

	boolean isChatViewHidden() {
		return client.getVarcIntValue(VarClientID.CHAT_VIEW) == CHAT_VIEW_HIDDEN;
	}

	private void setNativeChatView(int chatView) {
		final int currentChatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (currentChatView == chatView) {
			syncChatAreaVisibility();
			return;
		}

		if (chatView == CHAT_VIEW_HIDDEN) {
			rememberVisibleChatGraphic();
		}

		beginScrollBaseline();
		try {
			client.setVarcIntValue(VarClientID.CHAT_VIEW, chatView);

			if (performanceMetrics != null) {
				performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.OTHER);
			}

			client.refreshChat();
		} finally {
			endScrollBaseline();
		}

		syncChatAreaVisibility();
		syncStoredChatGraphic(chatView != CHAT_VIEW_HIDDEN);
	}

	private void syncChatAreaVisibility() {
		setChatAreaHidden(isChatViewHidden());
	}

	private void setChatAreaHidden(boolean hidden) {
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chatArea == null || chatArea.isSelfHidden() == hidden) {
			return;
		}

		chatArea.setHidden(hidden);
		recordMutation();
	}

	private boolean isSuppressionRequested() {
		return manualSuppressionActive || foregroundSuppressionActive && !foregroundSuppressionOverridden;
	}

	private void resetChatPresentation() {
		chatControlClickPending = false;
		manualSuppressionActive = false;
		foregroundSuppressionActive = false;
		foregroundSuppressionOverridden = false;

		restoreOwnedChatView();
		syncChatAreaVisibility();
	}

	/*
	 * ================================================================
	 * RESTORATION
	 * ================================================================
	 */
	public void restoreNativeSize() {
		resetChatPresentation();
		recordMutations(controlsLayout.restoreHidden());

		final boolean wasApplied = resizedLayoutApplied;
		final ChatboxLayout layout = getLayout();

		if (layout == ChatboxLayout.FIXED || layout == ChatboxLayout.UNKNOWN) {
			sideContainerLayout.reset();
			resizedLayoutApplied = false;
			return;
		}

		if (layout == ChatboxLayout.RESIZABLE_MODERN) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.restoreNative();

			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		} else {
			sideContainerLayout.reset();
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (slot == null || universe == null) {
			resizedLayoutApplied = false;
			return;
		}

		slot.setSize(ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_SLOT_HEIGHT);
		slot.setForcedPosition(-1, -1);
		recordMutation();

		slot.revalidate();
		recordRevalidate();

		restoreNativeUniverse(universe);

		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chatArea != null) {
			chatArea.setSize(
					ChatboxGeometry.NATIVE_WIDTH,
					ChatboxGeometry.NATIVE_TAB_HEIGHT,
					chatArea.getWidthMode(),
					WidgetSizeMode.MINUS);
			recordMutation();
		}

		recordMutations(controlsLayout.restoreNative());
		recordRevalidates(ChatboxWidgets.revalidateChildren(universe));

		final ChatboxBackgroundService.Result backgroundResult = backgroundService.restore(chatArea);

		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());

		resizedLayoutApplied = false;

		if (wasApplied && performanceMetrics != null) {
			performanceMetrics.recordResizeRestore();
		}
	}

	@SuppressWarnings("deprecation")
	private void restoreNativeUniverse(Widget universe) {
		universe.setSize(0, 0, WidgetSizeMode.MINUS, WidgetSizeMode.MINUS);
		universe.setForcedPosition(-1, -1);
		recordMutation();

		universe.revalidate();
		recordRevalidate();

		universe.setWidth(ChatboxGeometry.NATIVE_WIDTH);
		universe.setHeight(ChatboxGeometry.NATIVE_SLOT_HEIGHT);
		recordMutation();
	}

	/*
	 * ================================================================
	 * PERFORMANCE HELPERS
	 * ================================================================
	 */
	private void recordApply(long started, boolean widthChanged, boolean heightChanged) {
		if (performanceMetrics == null || !performanceMetrics.isEnabled()) {
			return;
		}

		performanceMetrics.recordResizeApply(System.nanoTime() - started, widthChanged, heightChanged);
	}

	private void recordMissingWidgets() {
		if (performanceMetrics != null) {
			performanceMetrics.recordResizeMissingWidgets();
		}
	}

	private void recordMutation() {
		if (performanceMetrics != null) {
			performanceMetrics.recordWidgetMutation();
		}
	}

	private void recordMutations(int count) {
		if (performanceMetrics == null || count <= 0) {
			return;
		}

		for (int i = 0; i < count; i++) {
			performanceMetrics.recordWidgetMutation();
		}
	}

	private void recordRevalidate() {
		if (performanceMetrics != null) {
			performanceMetrics.recordRevalidate();
		}
	}

	private void recordRevalidates(int count) {
		if (performanceMetrics == null || count <= 0) {
			return;
		}

		for (int i = 0; i < count; i++) {
			performanceMetrics.recordRevalidate();
		}
	}

	public static final class ResizeResult {
		private static final ResizeResult NOT_APPLIED =
				new ResizeResult(false, false, false);

		private final boolean applied;
		private final boolean widthChanged;
		private final boolean heightChanged;

		private ResizeResult(boolean applied, boolean widthChanged, boolean heightChanged) {
			this.applied = applied;
			this.widthChanged = widthChanged;
			this.heightChanged = heightChanged;
		}

		public boolean isApplied() {
			return applied;
		}

		public boolean isWidthChanged() {
			return widthChanged;
		}

		public boolean isHeightChanged() {
			return heightChanged;
		}
	}
}