package com.bigboldchat.chatbox;

import java.awt.Rectangle;

import com.bigboldchat.chat.DialoguePrompts;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.layout.ChatboxBounds;
import com.bigboldchat.layout.InterfaceBounds;
import com.bigboldchat.layout.SideContainerLayout;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.OverlayManager;

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
	 * Native chat and top-level relayout helpers.
	 */
	private static final int CHAT_ONCHATTRANSMIT = 663;
	private static final int TOPLEVEL_RELAYOUT = 1972;
	private static final int SIDE_CONTAINER_UPDATE = 9336;
	private static final String RUNELITE_CONFIG_GROUP = "runelite";
	private static final String INVENTORY_LOCATION_KEY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_PARENT_preferredLocation";

	/*
	 * Requires stable post-login placement before persistent collision tracking begins.
	 */
	private static final int LOGIN_STABLE_SAMPLES = 3;
	private static final int RESIZE_STABLE_SAMPLES = 2;
	private static final int DIALOGUE_FIT_STEP = 4;
	private static final int MAX_DIALOGUE_FIT_EXPANSION = 160;

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
	private final DialoguePrompts dialoguePrompts;
	private final ChatboxBackgroundService backgroundService;
	private final SideContainerLayout sideContainerLayout;
	private final ChatboxPlacement chatboxPlacement;
	private final ChatboxBounds.Tracker chatboxBoundsTracker;

	private boolean resizedLayoutApplied;
	private boolean manualSuppressionActive;
	private boolean nativeRevealActive;
	private boolean nativeRefreshActive;
	private boolean ownsHiddenView;
	private boolean controlClickPending;
	private boolean chatboxButtonsHidden;
	private boolean liveWidthChanged;
	private boolean liveHeightChanged;
	private boolean widthRefreshPending;
	private boolean heightRefreshPending;
	private boolean loginGeometryPending;
	private boolean canvasResizePending;
	private boolean dialogueFitActive;
	private boolean dialogueFitResolving;

	private int dialogueFitConfiguredWidth = -1;
	private int dialogueFitConfiguredHeight = -1;
	private int dialogueFitRequestedWidth = -1;
	private int dialogueFitRequestedHeight = -1;
	private int dialogueFitRequirementWidth = -1;
	private int dialogueFitMinimumBodyHeight = -1;
	private int dialogueFitPreferredBodyHeight = -1;
	private int dialogueFitFingerprint;

	private int liveDepth;
	private int sideLayoutDepth;
	private int loginStableSamples;
	private int resizeStableSamples;
	private int observedCanvasWidth = -1;
	private int observedCanvasHeight = -1;
	private long loginGeometryFingerprint = Long.MIN_VALUE;

	private Rectangle loginDesiredBounds;
	private int lastChatView = CHAT_VIEW_ALL;
	private int lastChatGraphic = -1;

	private ScrollBaseline liveBaseline;
	private GeometryState geometryState;

	public ChatboxResizeService(Client client, PerformanceMetrics performanceMetrics) {
		this(client, null, null, performanceMetrics);
	}

	public ChatboxResizeService(Client client, ConfigManager configManager, PerformanceMetrics performanceMetrics) {
		this(client, configManager, null, performanceMetrics);
	}

	public ChatboxResizeService(Client client, ConfigManager configManager, OverlayManager overlayManager, PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.performanceMetrics = performanceMetrics;
		this.controlsLayout = new ChatboxControlsLayout(client);
		this.dialoguePrompts = new DialoguePrompts(client);
		this.backgroundService = new ChatboxBackgroundService(client);
		this.sideContainerLayout = new SideContainerLayout(client, overlayManager);
		this.chatboxPlacement = new ChatboxPlacement(client, configManager, overlayManager);
		this.chatboxBoundsTracker = new ChatboxBounds.Tracker();
	}

	/**
	 * ================================================================
	 * LAYOUT
	 * ================================================================
	 */
	ChatboxLayout getLayout() {
		/*
		 * Classify gameplay from the mounted GAMEFRAME; the welcome screen remains UNKNOWN.
		 */
		if (isVisible(InterfaceID.WelcomeScreen.UNIVERSE)) {
			return ChatboxLayout.UNKNOWN;
		}

		if (isVisible(InterfaceID.ToplevelOsrsStretch.GAMEFRAME)) {
			return ChatboxLayout.RESIZABLE_CLASSIC;
		}

		if (isVisible(InterfaceID.ToplevelPreEoc.GAMEFRAME)) {
			return ChatboxLayout.RESIZABLE_MODERN;
		}

		if (isVisible(InterfaceID.Toplevel.GAMEFRAME)) {
			return ChatboxLayout.FIXED;
		}

		return ChatboxLayout.UNKNOWN;
	}

	private boolean isVisible(int widgetId) {
		final Widget widget = client.getWidget(widgetId);
		return widget != null && !widget.isHidden();
	}

	private static boolean isResizableLayout(ChatboxLayout layout) {
		return layout == ChatboxLayout.RESIZABLE_CLASSIC || layout == ChatboxLayout.RESIZABLE_MODERN;
	}

	public void onGameStateChanged(GameState gameState) {
		if (gameState == null) {
			return;
		}

		/*
		 * Clear transient collision and side-row state while preserving saved chatbox placement.
		 */
		geometryState = null;
		clearDialogueFitState();
		dialogueFitResolving = false;
		chatboxBoundsTracker.reset();
		resetDialoguePrompts();
		nativeRevealActive = false;
		clearCanvasResize();

		if (gameState == GameState.LOGGED_IN) {
			sideContainerLayout.resumeForGameState();

			beginLoginStabilization();
		} else {
			cancelLoginStabilization();
			sideContainerLayout.suspendForGameState();
		}
	}

	private void beginLoginStabilization() {
		loginGeometryPending = true;
		resetLoginSamples();
	}

	private void cancelLoginStabilization() {
		loginGeometryPending = false;
		resetLoginSamples();
	}

	private void resetLoginSamples() {
		loginStableSamples = 0;
		loginGeometryFingerprint = Long.MIN_VALUE;
		loginDesiredBounds = null;
	}

	private boolean isUsablePlacement(Rectangle desired) {
		if (desired == null || desired.isEmpty()) {
			return false;
		}

		final int canvasWidth = Math.max(0, client.getCanvasWidth());
		final int canvasHeight = Math.max(0, client.getCanvasHeight());
		if (canvasWidth <= 0 || canvasHeight <= 0) {
			return false;
		}

		/*
		 * A settled host must fit each canvas axis when its desired size fits that axis.
		 */
		final boolean horizontalReady = desired.width > canvasWidth || desired.x >= 0 && desired.x + desired.width <= canvasWidth;
		final boolean verticalReady = desired.height > canvasHeight || desired.y >= 0 && desired.y + desired.height <= canvasHeight;

		return horizontalReady && verticalReady;
	}

	/*
	 * Waits for stable post-login placement before enabling persistent collision tracking.
	 */
	public void reconcilePostLoginGeometry(int width, int height) {
		if (!loginGeometryPending || client.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		final ChatboxLayout layout = getLayout();
		if (layout == ChatboxLayout.UNKNOWN) {
			resetLoginSamples();
			return;
		}

		if (layout == ChatboxLayout.FIXED) {
			cancelLoginStabilization();
			chatboxBoundsTracker.reset();
			return;
		}

		if (!isResizableLayout(layout)) {
			resetLoginSamples();
			return;
		}

		final ResizeResult provisional = applySize(width, height);
		if (!provisional.isApplied() || geometryState == null) {
			resetLoginSamples();
			return;
		}

		final Rectangle desired = geometryState.desiredBounds;
		if (!isUsablePlacement(desired)) {
			resetLoginSamples();
			return;
		}

		final long fingerprint = InterfaceBounds.geometryFingerprint(client);
		if (fingerprint == loginGeometryFingerprint && desired.equals(loginDesiredBounds)) {
			loginStableSamples++;
		} else {
			loginGeometryFingerprint = fingerprint;
			loginDesiredBounds = new Rectangle(desired);
			loginStableSamples = 1;
		}

		if (loginStableSamples < LOGIN_STABLE_SAMPLES) {
			return;
		}

		/*
		 * Restart persistent collision tracking from the settled geometry.
		 */
		cancelLoginStabilization();
		chatboxBoundsTracker.reset();

		final ResizeResult settled = applySize(width, height);
		if (settled.isApplied()) {
			widthRefreshPending |= settled.isWidthChanged();
			heightRefreshPending |= settled.isHeightChanged();
		}
	}

	private Widget getSlot(ChatboxLayout layout) {
		switch (layout) {
			case RESIZABLE_CLASSIC:
				return client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
			case RESIZABLE_MODERN:
				return client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);
			default:
				return null;
		}
	}

	private void handleInactiveLayout(ChatboxLayout layout) {
		/*
		 * Fixed restores ChatXL-owned shared geometry; UNKNOWN clears transient state only.
		 */
		if (layout == ChatboxLayout.FIXED && resizedLayoutApplied) {
			restoreSharedGeometry();
		}

		geometryState = null;
		clearDialogueFitState();
		dialogueFitResolving = false;
		chatboxBoundsTracker.reset();
		resetDialoguePrompts();
		sideContainerLayout.reset();

		controlClickPending = false;
		manualSuppressionActive = false;
		nativeRevealActive = false;
		ownsHiddenView = false;

		liveDepth = 0;
		sideLayoutDepth = 0;
		liveBaseline = null;
		liveWidthChanged = false;
		liveHeightChanged = false;
		widthRefreshPending = false;
		heightRefreshPending = false;
		clearCanvasResize();
	}

	private void clearCanvasResize() {
		canvasResizePending = false;
		resizeStableSamples = 0;
		observedCanvasWidth = -1;
		observedCanvasHeight = -1;
		sideContainerLayout.endCanvasResize();
	}

	/*
	 * Begins the Modern canvas-resize hold and translates captured side-row geometry.
	 */
	public void onCanvasSizeChanged() {
		if (getLayout() != ChatboxLayout.RESIZABLE_MODERN) {
			clearCanvasResize();
			return;
		}

		canvasResizePending = true;
		resizeStableSamples = 0;
		observedCanvasWidth = Math.max(0, client.getCanvasWidth());
		observedCanvasHeight = Math.max(0, client.getCanvasHeight());

		final SideContainerLayout.Result sideResult = sideContainerLayout.onCanvasSizeChanged();
		recordMutations(sideResult.getMutations());
		recordRevalidates(sideResult.getRevalidates());
	}

	/*
	 * Identifies RuneLite's movable inventory-overlay reset.
	 */
	public boolean isInventoryOverlayReset(ConfigChanged event) {
		return event != null
				&& RUNELITE_CONFIG_GROUP.equals(event.getGroup())
				&& INVENTORY_LOCATION_KEY.equals(event.getKey())
				&& event.getNewValue() == null;
	}

	/*
	 * Preserves the owned inventory overlay during its reset transaction.
	 */
	public void guardInventoryOverlayReset() {
		sideContainerLayout.guardInventoryOverlayReset();
	}

	/*
	 * Reconciles an inventory-overlay reset with the owned Modern row layout.
	 */
	public void reconcileInventoryOverlayReset(int width, int height) {
		if (getLayout() != ChatboxLayout.RESIZABLE_MODERN) {
			return;
		}

		final SideContainerLayout.Result sideResult = sideContainerLayout.reconcileInventoryReset();
		recordMutations(sideResult.getMutations());
		recordRevalidates(sideResult.getRevalidates());
		if (sideResult.getMutations() <= 0 && sideResult.getRevalidates() <= 0) {
			return;
		}

		final ResizeResult result = applySizeInternal(width, height, false, sideResult.getInterfaceOverrides());
		if (result.isApplied()) {
			widthRefreshPending |= result.isWidthChanged();
			heightRefreshPending |= result.isHeightChanged();
		}
	}

	/**
	 * ================================================================
	 * SCRIPT LIFECYCLE
	 * ================================================================
	 */
	public ResizeResult onScriptPreFired(ScriptPreFired event, int width, int height) {
		if (event == null) {
			return ResizeResult.NOT_APPLIED;
		}

		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			handleInactiveLayout(layout);
			return ResizeResult.NOT_APPLIED;
		}

		final int scriptId = event.getScriptId();

		if (scriptId == ScriptID.MESSAGE_LAYER_OPEN || scriptId == CHAT_VISIBILITY && !controlClickPending && !nativeRefreshActive) {
			beginNativeReveal();
		}

		if (scriptId == SIDE_CONTAINER_UPDATE && layout == ChatboxLayout.RESIZABLE_MODERN) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.reconcileInventoryReveal();
			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		}

		/*
		 * Clear pending plugin refreshes before native chat reconstruction.
		 */
		if (scriptId == CHAT_ONCHATTRANSMIT || scriptId == ScriptID.SPLITPM_CHANGED) {
			widthRefreshPending = false;
			heightRefreshPending = false;
		}

		if (scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE && !controlClickPending) {
			if (sideLayoutDepth++ == 0) {
				sideContainerLayout.beginNativeLayout();
			}
		}

		if (scriptId == TOPLEVEL_RELAYOUT) {
			beginLiveScroll();

			/*
			 * keeps committed geometry only while RuneLite is exposing transient
			 * control, canvas-resize, or native side-layout geometry.
			 */
			final ResizeResult result = shouldUseCommittedRelayout()
					? applyCommittedGeometry(width, height)
					: applySize(width, height);
			if (result.isApplied()) {
				liveWidthChanged |= result.isWidthChanged();
				liveHeightChanged |= result.isHeightChanged();
			}

			return result;
		}

		if (scriptId == ScriptID.SPLITPM_CHANGED) {
			return applySize(width, height);
		}

		if (scriptId == ScriptID.BUILD_CHATBOX) {
			return applySize(width, height);
		}

		return ResizeResult.NOT_APPLIED;
	}

	private boolean shouldUseCommittedRelayout() {
		return controlClickPending || canvasResizePending || sideLayoutDepth > 0;
	}

	public ResizeResult onScriptPostFired(ScriptPostFired event, int width, int height) {
		if (event == null) {
			return ResizeResult.NOT_APPLIED;
		}

		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			handleInactiveLayout(layout);
			return ResizeResult.NOT_APPLIED;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == CHAT_VISIBILITY) {
			final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
			if (nativeRevealActive && (chatArea == null || chatArea.isSelfHidden())) {
				nativeRevealActive = false;
			}

			rememberVisibleView();
			if (!nativeRevealActive) {
				syncChatVisibility();
			}
			recordMutations(controlsLayout.syncHidden(chatboxButtonsHidden));
		}

		if (scriptId == ScriptID.MESSAGE_LAYER_CLOSE) {
			finishNativeReveal();
		}

		if (scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE && sideLayoutDepth > 0 && --sideLayoutDepth == 0) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.endNativeLayout();
			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		}

		if (scriptId == TOPLEVEL_RELAYOUT) {
			finishLiveScroll();

			/*
			 * Reapply existing side-row ownership without reevaluating row policy.
			 */
			if (layout == ChatboxLayout.RESIZABLE_MODERN) {
				final SideContainerLayout.Result sideResult = sideContainerLayout.reassertOwnedLayout();
				recordMutations(sideResult.getMutations());
				recordRevalidates(sideResult.getRevalidates());
			}

			syncDialoguePrompts();
			return ResizeResult.NOT_APPLIED;
		}

		if (scriptId == ScriptID.TOPLEVEL_REDRAW || scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE || scriptId == ScriptID.MESSAGE_LAYER_OPEN) {
			final ResizeResult result = applySize(width, height);
			syncDialoguePrompts();
			return result;
		}

		syncDialoguePrompts();
		return ResizeResult.NOT_APPLIED;
	}

	/**
	 * ================================================================
	 * CHATBOX GEOMETRY
	 * ================================================================
	 */
	private ResizeResult applyCommittedGeometry(int width, int height) {
		/*
		 * Use normal solving when committed geometry is unavailable, stale, or still stabilizing.
		 */
		final GeometryState committed = geometryState;
		if (committed == null || committed.configuredWidth != width || committed.configuredHeight != height || loginGeometryPending) {
			return applySize(width, height);
		}

		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime()
				: 0L;
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			handleInactiveLayout(layout);
			return ResizeResult.NOT_APPLIED;
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (slot == null || universe == null || chatArea == null) {
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		final Widget parent = universe.getParent();
		if (parent == null || parent.getId() != slot.getId()) {
			return ResizeResult.NOT_APPLIED;
		}

		final Rectangle desiredBounds = committed.desiredBounds;

		/*
		 * Apply committed chatbox geometry without reevaluating side-row policy.
		 */
		final Rectangle effectiveBounds = committed.effectiveBounds;
		final int hostWidth = desiredBounds.width;
		final int hostHeight = desiredBounds.height;
		final int effectiveWidth = effectiveBounds.width;
		final int effectiveHeight = effectiveBounds.height;
		final int effectiveX = Math.max(0, effectiveBounds.x - desiredBounds.x);
		final int effectiveY = Math.max(0, effectiveBounds.y - desiredBounds.y);
		final int effectiveBodyHeight = ChatboxGeometry.bodyHeight(effectiveHeight, chatboxButtonsHidden);
		if (dialogueFitActive) {
			chatboxPlacement.applyHostBounds(desiredBounds, false);
		}

		final boolean hostChanged = slot.getWidth() != hostWidth || slot.getHeight() != hostHeight;
		final boolean positionChanged = universe.getRelativeX() != effectiveX || universe.getRelativeY() != effectiveY;
		final boolean widthChanged = universe.getWidth() != effectiveWidth || chatArea.getWidth() != effectiveWidth;
		final boolean heightChanged = universe.getHeight() != effectiveHeight || chatArea.getHeight() != effectiveBodyHeight;
		final boolean controlsChanged = !controlsLayout.matches(effectiveWidth);

		if (hostChanged || positionChanged || widthChanged || heightChanged || controlsChanged) {
			applyGeometry(slot, universe, chatArea, hostWidth, hostHeight, effectiveX, effectiveY,
					effectiveWidth, effectiveHeight, effectiveBodyHeight, controlsChanged, false);
		}

		final ChatboxBackgroundService.Result backgroundResult =
				backgroundService.apply(chatArea, effectiveWidth, effectiveBodyHeight);

		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());
		if (liveDepth == 0) {
			syncDialoguePrompts(effectiveWidth, effectiveBodyHeight);
		}

		syncChatPresentation();
		resizedLayoutApplied = true;

		recordApply(started, widthChanged, heightChanged);

		return new ResizeResult(true, widthChanged, heightChanged);
	}

	public ResizeResult applySize(int width, int height) {
		return applySizeInternal(width, height, true, null);
	}

	private ResizeResult applySizeInternal(int width, int height, boolean evaluateSideLayout, InterfaceBounds.Overrides suppliedOverrides) {
		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime()
				: 0L;
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			handleInactiveLayout(layout);
			return ResizeResult.NOT_APPLIED;
		}

		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		recordMutations(controlsLayout.syncHidden(chatboxButtonsHidden));

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (slot == null || universe == null || chatArea == null) {
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		/*
		 * Require UNIVERSE to be mounted under the active chat container.
		 */
		final Widget parent = universe.getParent();
		if (parent == null || parent.getId() != slot.getId()) {
			return ResizeResult.NOT_APPLIED;
		}

		boolean restoreTemporaryHost = false;
		if (dialogueFitActive
				&& (width != dialogueFitConfiguredWidth || height != dialogueFitConfiguredHeight)) {
			restoreTemporaryHost = true;
			clearDialogueFitState();
		}

		final int requestedWidth = dialogueFitActive ? dialogueFitRequestedWidth : width;
		final int requestedHeight = dialogueFitActive ? dialogueFitRequestedHeight : height;
		final ChatboxPlacement.State placement = chatboxPlacement.capture(slot, requestedWidth, requestedHeight);
		final Rectangle desiredBounds = placement.getDesiredBounds();
		if (placement.requiresHostReposition()) {
			/*
			 * Correct a stale host remount offset once before collision solving.
			 */
			slot.setForcedPosition(
					slot.getRelativeX() + placement.getHostDeltaX(),
					slot.getRelativeY() + placement.getHostDeltaY());
			recordMutation();

			slot.revalidate();
			recordRevalidate();
		}

		/*
		 * Manual client-edge anchoring needs one absolute host move when a configured
		 * size change shifts the fixed edge. Do this before side-row collision solving
		 * so rows observe the same desired host position as ChatboxBounds.
		 */
		boolean configuredHostPositionChanged = false;
		if (placement.requiresHostAnchorUpdate() && !dialogueFitActive && !dialogueFitResolving && !canvasResizePending) {
			chatboxPlacement.applyHostBounds(desiredBounds, true);
			configuredHostPositionChanged = chatboxPlacement.moveHost(slot, desiredBounds);
			if (configuredHostPositionChanged) {
				recordMutation();
				slot.revalidate();
				recordRevalidate();
			}
		}

		InterfaceBounds.Overrides interfaceOverrides = suppliedOverrides;
		if (layout == ChatboxLayout.RESIZABLE_MODERN && evaluateSideLayout) {
			/*
			 * Use SideContainerLayout targets for same-pass chatbox collision solving.
			 */
			final SideContainerLayout.Result sideResult = sideContainerLayout.apply(slot, width, height);

			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
			interfaceOverrides = sideResult.getInterfaceOverrides();
		} else if (layout != ChatboxLayout.RESIZABLE_MODERN) {
			sideContainerLayout.reset();
		}

		/*
		 * Use the full desired rectangle until post-login geometry stabilizes.
		 */
		final Rectangle effectiveBounds;
		if (loginGeometryPending) {
			effectiveBounds = new Rectangle(desiredBounds);
			geometryState = new GeometryState(width, height, desiredBounds, effectiveBounds);
		} else {
			final ChatboxBounds.Result effective = ChatboxBounds.resolve(
					client, placement, chatboxButtonsHidden, chatboxBoundsTracker, interfaceOverrides);
			effectiveBounds = effective.getEffectiveBounds();
			geometryState = new GeometryState(width, height, effective.getDesiredBounds(), effectiveBounds);
		}
		final int hostWidth = desiredBounds.width;
		final int hostHeight = desiredBounds.height;
		final int effectiveWidth = effectiveBounds.width;
		final int effectiveHeight = effectiveBounds.height;
		final int effectiveX = Math.max(0, effectiveBounds.x - desiredBounds.x);
		final int effectiveY = Math.max(0, effectiveBounds.y - desiredBounds.y);
		final int effectiveBodyHeight = ChatboxGeometry.bodyHeight(effectiveHeight, chatboxButtonsHidden);
		final boolean temporaryHost = dialogueFitActive || dialogueFitResolving || restoreTemporaryHost;
		final boolean moveTemporaryHost = temporaryHost && !dialogueFitActive && !canvasResizePending;
		if (temporaryHost) {
			chatboxPlacement.applyHostBounds(desiredBounds, moveTemporaryHost);
		}

		final boolean temporaryHostPositionChanged = moveTemporaryHost && chatboxPlacement.moveHost(slot, desiredBounds);
		if (temporaryHostPositionChanged) {
			recordMutation();
		}
		final boolean hostPositionChanged = configuredHostPositionChanged || temporaryHostPositionChanged;

		final boolean hostChanged = slot.getWidth() != hostWidth || slot.getHeight() != hostHeight;
		final boolean positionChanged = universe.getRelativeX() != effectiveX || universe.getRelativeY() != effectiveY;
		final boolean widthChanged = universe.getWidth() != effectiveWidth || chatArea.getWidth() != effectiveWidth;
		final boolean heightChanged = universe.getHeight() != effectiveHeight || chatArea.getHeight() != effectiveBodyHeight;
		final boolean controlsChanged = !controlsLayout.matches(effectiveWidth);

		if (hostPositionChanged || hostChanged || positionChanged || widthChanged || heightChanged || controlsChanged) {
			applyGeometry(slot, universe, chatArea, hostWidth, hostHeight, effectiveX, effectiveY,
					effectiveWidth, effectiveHeight, effectiveBodyHeight, controlsChanged, hostPositionChanged);
		}

		final ChatboxBackgroundService.Result backgroundResult =
				backgroundService.apply(chatArea, effectiveWidth, effectiveBodyHeight);

		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());
		if (liveDepth == 0) {
			syncDialoguePrompts(effectiveWidth, effectiveBodyHeight);
		}

		syncChatPresentation();

		resizedLayoutApplied = true;

		recordApply(started, widthChanged, heightChanged);

		return new ResizeResult(true, widthChanged, heightChanged);
	}

	private void applyGeometry(Widget slot, Widget universe, Widget chatArea, int hostWidth, int hostHeight, int effectiveX, int effectiveY,
			int effectiveWidth, int effectiveHeight, int bodyHeight, boolean controlsChanged, boolean hostPositionChanged) {
		boolean hostChanged = hostPositionChanged;
		if (slot.getWidth() != hostWidth || slot.getHeight() != hostHeight) {
			slot.setSize(hostWidth, hostHeight);
			recordMutation();
			hostChanged = true;
		}

		if (hostChanged) {
			slot.revalidate();
			recordRevalidate();
		}

		applyInnerGeometry(
				universe,
				chatArea,
				effectiveX,
				effectiveY,
				effectiveWidth,
				effectiveHeight,
				bodyHeight,
				controlsChanged);
	}

	private void applyInnerGeometry(
			Widget universe,
			Widget chatArea,
			int effectiveX,
			int effectiveY,
			int effectiveWidth,
			int effectiveHeight,
			int bodyHeight,
			boolean controlsChanged) {
		boolean universeChanged = false;
		if (universe.getWidth() != effectiveWidth || universe.getHeight() != effectiveHeight) {
			universe.setSize(effectiveWidth, effectiveHeight, WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
			recordMutation();
			universeChanged = true;
		}

		if (universe.getRelativeX() != effectiveX || universe.getRelativeY() != effectiveY) {
			universe.setForcedPosition(effectiveX, effectiveY);
			recordMutation();
			universeChanged = true;
		}

		if (universeChanged) {
			universe.revalidate();
			recordRevalidate();
		}

		final int bodyMargin = Math.max(0, effectiveHeight - bodyHeight);
		if (chatArea.getOriginalWidth() != effectiveWidth
				|| chatArea.getOriginalHeight() != bodyMargin || chatArea.getHeightMode() != WidgetSizeMode.MINUS) {
			chatArea.setSize(effectiveWidth, bodyMargin, chatArea.getWidthMode(), WidgetSizeMode.MINUS);
			recordMutation();
		}

		if (controlsChanged) {
			recordMutations(controlsLayout.apply(effectiveWidth));
		}

		recordRevalidates(ChatboxWidgets.revalidateChildren(universe));
	}

	/**
	 * ================================================================
	 * SCROLL BASELINE
	 * ================================================================
	 */
	private void beginLiveScroll() {
		if (liveDepth++ != 0) {
			return;
		}

		liveBaseline = captureScrollBaseline();
		liveWidthChanged = false;
		liveHeightChanged = false;
	}

	private void finishLiveScroll() {
		if (liveDepth <= 0) {
			return;
		}

		liveDepth--;
		if (liveDepth != 0) {
			return;
		}

		final ScrollBaseline baseline = liveBaseline;
		final boolean widthChanged = liveWidthChanged;
		final boolean heightChanged = liveHeightChanged;
		liveBaseline = null;
		liveWidthChanged = false;
		liveHeightChanged = false;

		if (widthChanged || heightChanged) {
			restoreScrollBaseline(baseline, true);

			/*
			 * Queue retained-chat reconstruction for the render boundary.
			 */
			widthRefreshPending |= widthChanged;
			heightRefreshPending |= heightChanged;
		}
	}

	/*
	 * Reconciles owned Modern side-row and chatbox geometry before rendering.
	 */
	public void reconcileBeforeRender(int width, int height) {
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			clearCanvasResize();
			return;
		}

		if (layout != ChatboxLayout.RESIZABLE_MODERN) {
			clearCanvasResize();
			reconcileDialogueBeforeRender();
			return;
		}

		if (canvasResizePending) {
			final int canvasWidth = Math.max(0, client.getCanvasWidth());
			final int canvasHeight = Math.max(0, client.getCanvasHeight());
			if (canvasWidth == observedCanvasWidth && canvasHeight == observedCanvasHeight) {
				resizeStableSamples++;
			} else {
				observedCanvasWidth = canvasWidth;
				observedCanvasHeight = canvasHeight;
				resizeStableSamples = 1;

				final SideContainerLayout.Result translated = sideContainerLayout.onCanvasSizeChanged();
				recordMutations(translated.getMutations());
				recordRevalidates(translated.getRevalidates());
			}

			if (resizeStableSamples >= RESIZE_STABLE_SAMPLES) {
				sideContainerLayout.endCanvasResize();
				chatboxBoundsTracker.reset();

				final ResizeResult resizeResult = applySize(width, height);
				if (resizeResult.isApplied()) {
					clearCanvasResize();
					widthRefreshPending |= resizeResult.isWidthChanged();
					heightRefreshPending |= resizeResult.isHeightChanged();
				} else {
					final SideContainerLayout.Result held = sideContainerLayout.onCanvasSizeChanged();
					recordMutations(held.getMutations());
					recordRevalidates(held.getRevalidates());
				}
			}
		}

		final SideContainerLayout.Result sideResult = sideContainerLayout.reconcileBeforeRender();
		recordMutations(sideResult.getMutations());
		recordRevalidates(sideResult.getRevalidates());

		if (sideResult.getMutations() > 0 || sideResult.getRevalidates() > 0) {
			final ResizeResult result = applySizeInternal(width, height, false, sideResult.getInterfaceOverrides());
			if (result.isApplied()) {
				widthRefreshPending |= result.isWidthChanged();
				heightRefreshPending |= result.isHeightChanged();
			}
		}

		/*
		 * Native dialogue pages can rebuild after the script callbacks that applied ChatXL geometry.
		 * Reconcile once at the render boundary so each newly mounted/page-advanced dialogue is laid
		 * out against the current effective chatbox before it becomes visible.
		 */
		reconcileDialogueBeforeRender();
	}

	public LiveRefresh consumeLiveRefresh() {
		if (liveDepth != 0 || !widthRefreshPending && !heightRefreshPending) {
			return null;
		}

		final LiveRefresh refresh = new LiveRefresh(widthRefreshPending, heightRefreshPending);

		widthRefreshPending = false;
		heightRefreshPending = false;
		return refresh;
	}

	public ScrollBaseline captureScrollBaseline() {
		if (!isResizableLayout(getLayout())) {
			return null;
		}

		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		if (scrollArea == null) {
			return null;
		}

		final int scrollY = Math.max(0, scrollArea.getScrollY());
		final int height = Math.max(0, scrollArea.getHeight());
		final int bottom = Math.max(0, scrollArea.getScrollHeight() - height);
		final ScrollPosition position;

		if (bottom == 0 || scrollY >= bottom) {
			position = ScrollPosition.BOTTOM;
		} else if (scrollY == 0) {
			position = ScrollPosition.TOP;
		} else {
			position = ScrollPosition.MIDDLE;
		}

		return new ScrollBaseline(position, scrollY + height);
	}

	public void restoreScrollBaseline(ScrollBaseline baseline) {
		restoreScrollBaseline(baseline, false);
	}

	void restoreRebuildScroll(ScrollBaseline baseline) {
		restoreScrollBaseline(baseline, true);
	}

	private void restoreScrollBaseline(ScrollBaseline baseline, boolean reanchor) {
		if (baseline == null || !isResizableLayout(getLayout())) {
			return;
		}

		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		if (scrollArea == null) {
			return;
		}

		final int height = Math.max(0, scrollArea.getHeight());
		if (reanchor && baseline.position != ScrollPosition.TOP) {
			reanchorScrollBottom(scrollArea, height);
		}

		final int scrollHeight = Math.max(0, scrollArea.getScrollHeight());
		final int bottom = Math.max(0, scrollHeight - height);
		final int target;

		switch (baseline.position) {
			case TOP:
				target = 0;
				break;
			case BOTTOM:
				target = bottom;
				break;
			case MIDDLE:
				target = Math.max(0, Math.min(bottom, baseline.viewportBottom - height));
				break;
			default:
				return;
		}

		if (scrollArea.getScrollY() != target) {
			scrollArea.setScrollY(target);
		}

		client.setVarcIntValue(VarClientID.CHAT_LASTSCROLLPOS, target);
		client.setVarcIntValue(VarClientID.CHAT_LASTSCROLLSIZE, scrollArea.getScrollHeight());
	}

	private void reanchorScrollBottom(Widget scrollArea, int height) {
		final int shift = height - scrollArea.getScrollHeight();
		if (shift <= 0) {
			return;
		}

		shiftScrollChildren(scrollArea.getStaticChildren(), shift);
		shiftScrollChildren(scrollArea.getDynamicChildren(), shift);
		scrollArea.setScrollHeight(height);
		recordMutation();
	}

	private void shiftScrollChildren(Widget[] children, int shift) {
		if (children == null) {
			return;
		}

		for (Widget child : children) {
			if (child == null || child.getHeight() <= 0) {
				continue;
			}

			child.setOriginalY(child.getOriginalY() + shift);
			recordMutation();

			child.revalidate();
			recordRevalidate();
		}
	}

	private void reconcileDialogueBeforeRender() {
		if (dialoguePrompts.needsPortraitReconcile()) {
			syncDialoguePrompts();
		}
	}

	private void syncDialoguePrompts() {
		if (geometryState != null) {
			final int effectiveHeight = geometryState.effectiveBounds.height;
			syncDialoguePrompts(
					geometryState.effectiveBounds.width,
					ChatboxGeometry.bodyHeight(effectiveHeight, chatboxButtonsHidden));
		}
	}

	private void syncDialoguePrompts(int effectiveWidth, int effectiveBodyHeight) {
		DialoguePrompts.Result result = dialoguePrompts.apply(effectiveWidth, effectiveBodyHeight);
		recordMutations(result.getMutations());
		recordRevalidates(result.getRevalidates());

		if (!dialogueFitResolving && liveDepth == 0 && reconcileDialogueFit()) {
			final GeometryState adjusted = geometryState;
			if (adjusted != null) {
				final int finalWidth = adjusted.effectiveBounds.width;
				final int finalBodyHeight =
						ChatboxGeometry.bodyHeight(adjusted.effectiveBounds.height, chatboxButtonsHidden);

				result = dialoguePrompts.apply(finalWidth, finalBodyHeight);
				recordMutations(result.getMutations());
				recordRevalidates(result.getRevalidates());
			}
		}
	}

	private boolean reconcileDialogueFit() {
		final GeometryState current = geometryState;
		if (current == null || loginGeometryPending || canvasResizePending || sideLayoutDepth > 0) {
			return false;
		}

		final DialoguePrompts.FitRequirement currentRequirement =
				dialoguePrompts.measureFit(current.effectiveBounds.width);
		if (!currentRequirement.isActive()) {
			return dialogueFitActive && restoreConfiguredDialogueGeometry(current);
		}

		final int currentFingerprint = dialoguePrompts.fitFingerprint();
		final boolean contentChanged = dialogueFitActive && currentFingerprint != dialogueFitFingerprint;
		final int currentBodyHeight =
				ChatboxGeometry.bodyHeight(current.effectiveBounds.height, chatboxButtonsHidden);
		if (!dialogueFitActive && currentRequirement.fitsMinimum(currentBodyHeight)) {
			return false;
		}

		if (dialogueFitActive && !contentChanged && currentRequirement.fitsMinimum(currentBodyHeight)) {
			dialogueFitRequirementWidth = current.effectiveBounds.width;
			dialogueFitMinimumBodyHeight = currentRequirement.getMinimumBodyHeight();
			dialogueFitPreferredBodyHeight = currentRequirement.getPreferredBodyHeight();
			return false;
		}

		final DialogueFitCandidate candidate = findDialogueFitCandidate(current, !contentChanged);
		if (candidate == null) {
			return false;
		}

		final Rectangle desired = candidate.result.getDesiredBounds();
		final Rectangle effective = candidate.result.getEffectiveBounds();
		final DialoguePrompts.FitRequirement selectedRequirement = dialoguePrompts.measureFit(effective.width);
		if (desired.equals(current.desiredBounds) && effective.equals(current.effectiveBounds)) {
			updateDialogueFitState(current, candidate, selectedRequirement);
			return false;
		}

		return applyDialogueFitCandidate(current, candidate);
	}

	private DialogueFitCandidate findDialogueFitCandidate(GeometryState current, boolean retainActiveFit) {
		final ChatboxLayout layout = getLayout();
		final Widget slot = getSlot(layout);
		if (slot == null || !isResizableLayout(layout)) {
			return null;
		}

		final int configuredWidth = current.configuredWidth;
		final int configuredHeight = current.configuredHeight;
		final int baseRequestedWidth = retainActiveFit && dialogueFitActive && dialogueFitRequestedWidth > 0
				? Math.max(configuredWidth, dialogueFitRequestedWidth)
				: configuredWidth;
		final int baseRequestedHeight = retainActiveFit && dialogueFitActive && dialogueFitRequestedHeight > 0
				? Math.max(configuredHeight, dialogueFitRequestedHeight)
				: configuredHeight;
		final DialogueFitCandidate base = resolveDialogueCandidate(slot, baseRequestedWidth, baseRequestedHeight,
				configuredWidth, configuredHeight, configuredWidth, configuredHeight, null);
		if (base == null) {
			return null;
		}
		base.baselineDesired = base.result.getDesiredBounds();

		DialogueFitCandidate best = null;
		final Rectangle baseEffective = base.result.getEffectiveBounds();
		final boolean widthExpansionAllowed = baseEffective.width >= configuredWidth;
		final boolean heightExpansionAllowed = baseEffective.height >= configuredHeight;
		final int maxWidth = widthExpansionAllowed
				? Math.max(baseRequestedWidth, configuredWidth + MAX_DIALOGUE_FIT_EXPANSION)
				: baseRequestedWidth;
		for (int requestedWidth = baseRequestedWidth; requestedWidth <= maxWidth; requestedWidth += DIALOGUE_FIT_STEP) {
			final DialogueFitCandidate provisional = resolveDialogueCandidate(slot, requestedWidth, baseRequestedHeight,
					configuredWidth, configuredHeight, baseRequestedWidth, baseRequestedHeight, baseEffective);
			if (provisional == null) {
				continue;
			}

			final int provisionalWidth = provisional.result.getEffectiveBounds().width;
			final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(provisionalWidth);
			if (!requirement.isActive()) {
				return base;
			}

			final int requiredSlotHeight = slotHeightForBody(requirement.getMinimumBodyHeight());
			final int requestedHeight = Math.max(baseRequestedHeight, requiredSlotHeight);
			if (requestedHeight > configuredHeight && !heightExpansionAllowed
					|| requestedHeight > configuredHeight + MAX_DIALOGUE_FIT_EXPANSION) {
				continue;
			}

			final DialogueFitCandidate candidate = resolveDialogueCandidate(slot, requestedWidth, requestedHeight,
					configuredWidth, configuredHeight, baseRequestedWidth, baseRequestedHeight, baseEffective);
			if (candidate == null) {
				continue;
			}
			candidate.baselineDesired = base.result.getDesiredBounds();

			final Rectangle effective = candidate.result.getEffectiveBounds();
			final int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
			final DialoguePrompts.FitRequirement actual = dialoguePrompts.measureFit(effective.width);
			if (!actual.isActive() || !actual.fitsMinimum(bodyHeight)) {
				continue;
			}

			candidate.preferredDeficit = Math.max(0, actual.getPreferredBodyHeight() - bodyHeight);
			candidate.score = Math.max(0, effective.width - baseEffective.width)
					+ Math.max(0, effective.height - baseEffective.height);
			if (best == null
					|| candidate.score < best.score
					|| candidate.score == best.score && candidate.preferredDeficit < best.preferredDeficit
					|| candidate.score == best.score && candidate.preferredDeficit == best.preferredDeficit
					&& candidate.requestedWidth < best.requestedWidth) {
				best = candidate;
			}

			if (best != null && best.score == 0) {
				break;
			}
		}

		return best;
	}

	private DialogueFitCandidate resolveDialogueCandidate(Widget slot, int requestedWidth, int requestedHeight,
			int configuredWidth, int configuredHeight, int baselineWidth, int baselineHeight, Rectangle baseEffective) {
		final ChatboxPlacement.State placement = chatboxPlacement.capture(slot, requestedWidth, requestedHeight);
		final ChatboxBounds.Result result = ChatboxBounds.resolve(
				client, placement, chatboxButtonsHidden, new ChatboxBounds.Tracker(), null);
		final Rectangle effective = result.getEffectiveBounds();
		final int widthGrowth = Math.max(0, requestedWidth - baselineWidth);
		final int heightGrowth = Math.max(0, requestedHeight - baselineHeight);
		if (baseEffective != null) {
			if (effective.width < baseEffective.width || effective.height < baseEffective.height) {
				return null;
			}
			if (widthGrowth > 0 && effective.width < baseEffective.width + widthGrowth) {
				return null;
			}
			if (heightGrowth > 0 && effective.height < baseEffective.height + heightGrowth) {
				return null;
			}
		}

		final boolean expanded = requestedWidth > configuredWidth || requestedHeight > configuredHeight;
		return new DialogueFitCandidate(requestedWidth, requestedHeight, result, expanded);
	}

	private boolean applyDialogueFitCandidate(GeometryState current, DialogueFitCandidate candidate) {
		final ChatboxLayout layout = getLayout();
		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (slot == null || universe == null || chatArea == null) {
			return false;
		}

		final Rectangle desired = candidate.result.getDesiredBounds();
		final Rectangle effective = candidate.result.getEffectiveBounds();
		final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(effective.width);
		final int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
		if (!requirement.isActive() || !requirement.fitsMinimum(bodyHeight)) {
			return false;
		}

		final int effectiveX = Math.max(0, effective.x - desired.x);
		final int effectiveY = Math.max(0, effective.y - desired.y);
		final boolean controlsChanged = !controlsLayout.matches(effective.width);
		final boolean widthChanged = universe.getWidth() != effective.width || chatArea.getWidth() != effective.width;
		final boolean heightChanged = universe.getHeight() != effective.height || chatArea.getHeight() != bodyHeight;

		geometryState = new GeometryState(current.configuredWidth, current.configuredHeight, desired, effective);
		chatboxPlacement.applyHostBounds(desired, !canvasResizePending);
		final boolean hostPositionChanged = !canvasResizePending && chatboxPlacement.moveHost(slot, desired);
		if (hostPositionChanged) {
			recordMutation();
		}

		applyGeometry(slot, universe, chatArea, desired.width, desired.height, effectiveX, effectiveY,
				effective.width, effective.height, bodyHeight, controlsChanged, hostPositionChanged);

		final ChatboxBackgroundService.Result backgroundResult = backgroundService.apply(chatArea, effective.width, bodyHeight);
		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());
		syncChatPresentation();

		updateDialogueFitState(current, candidate, requirement);
		widthRefreshPending |= widthChanged;
		heightRefreshPending |= heightChanged;
		return true;
	}

	private void updateDialogueFitState(GeometryState current, DialogueFitCandidate candidate, DialoguePrompts.FitRequirement requirement) {
		dialogueFitActive = candidate.expanded;
		if (!dialogueFitActive) {
			clearDialogueFitState();
			return;
		}

		dialogueFitConfiguredWidth = current.configuredWidth;
		dialogueFitConfiguredHeight = current.configuredHeight;
		dialogueFitRequestedWidth = candidate.requestedWidth;
		dialogueFitRequestedHeight = candidate.requestedHeight;
		dialogueFitRequirementWidth = candidate.result.getEffectiveBounds().width;
		dialogueFitMinimumBodyHeight = requirement.getMinimumBodyHeight();
		dialogueFitPreferredBodyHeight = requirement.getPreferredBodyHeight();
		dialogueFitFingerprint = dialoguePrompts.fitFingerprint();
	}

	private void clearDialogueFitState() {
		dialogueFitActive = false;
		dialogueFitConfiguredWidth = -1;
		dialogueFitConfiguredHeight = -1;
		dialogueFitRequestedWidth = -1;
		dialogueFitRequestedHeight = -1;
		dialogueFitRequirementWidth = -1;
		dialogueFitMinimumBodyHeight = -1;
		dialogueFitPreferredBodyHeight = -1;
		dialogueFitFingerprint = 0;
	}

	private boolean restoreConfiguredDialogueGeometry(GeometryState current) {
		dialogueFitResolving = true;
		try {
			final Rectangle previous = new Rectangle(current.effectiveBounds);
			clearDialogueFitState();
			final ResizeResult result = applySizeInternal(current.configuredWidth, current.configuredHeight, false, null);
			return result.isApplied() && geometryState != null && !previous.equals(geometryState.effectiveBounds);
		} finally {
			dialogueFitResolving = false;
		}
	}

	private int slotHeightForBody(int bodyHeight) {
		return Math.max(1, bodyHeight + (chatboxButtonsHidden ? 0 : ChatboxGeometry.NATIVE_TAB_HEIGHT));
	}

	private void resetDialoguePrompts() {
		final DialoguePrompts.Result result = dialoguePrompts.reset();
		recordMutations(result.getMutations());
		recordRevalidates(result.getRevalidates());
	}

	/**
	 * ================================================================
	 * PRESENTATION
	 * ================================================================
	 */
	private void syncChatPresentation() {
		rememberVisibleView();

		/*
		 * Preserve the native chat-control transition until the click completes.
		 */
		if (controlClickPending) {
			syncChatVisibility();
			return;
		}

		if (nativeRevealActive) {
			return;
		}

		if (manualSuppressionActive) {
			hideNativeView();
			return;
		}

		restoreOwnedView();
		syncChatVisibility();
	}

	public void setChatboxButtonsHidden(boolean hidden) {
		chatboxButtonsHidden = hidden;
		if (!isResizableLayout(getLayout())) {
			return;
		}

		recordMutations(controlsLayout.syncHidden(hidden));
	}

	public void toggleChatPresentation() {
		if (!isResizableLayout(getLayout())) {
			return;
		}

		if (isChatHidden()) {
			showChatPresentation();
			return;
		}

		rememberVisibleView();

		nativeRevealActive = false;
		manualSuppressionActive = true;
		hideNativeView();
	}

	public void showChatPresentation() {
		manualSuppressionActive = false;
		nativeRevealActive = false;
		if (!isResizableLayout(getLayout())) {
			ownsHiddenView = false;
			return;
		}

		if (!isChatHidden()) {
			ownsHiddenView = false;
			rememberVisibleView();
			syncChatVisibility();
			return;
		}

		ownsHiddenView = false;
		setNativeView(lastChatView);
	}

	public boolean onChatControlClicked(Widget widget) {
		if (!isResizableLayout(getLayout()) || findChatControl(widget) == -1) {
			return false;
		}

		controlClickPending = true;
		return true;
	}

	public void finishChatControlClick() {
		if (!isResizableLayout(getLayout())) {
			controlClickPending = false;
			manualSuppressionActive = false;
			nativeRevealActive = false;
			ownsHiddenView = false;
			return;
		}

		if (!controlClickPending) {
			return;
		}

		controlClickPending = false;
		manualSuppressionActive = false;
		nativeRevealActive = false;
		ownsHiddenView = false;

		if (getLayout() == ChatboxLayout.RESIZABLE_MODERN) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.reassertOwnedLayout();
			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		}

		rememberVisibleView();

		if (!isChatHidden()) {
			rememberVisibleGraphic();
		}

		syncChatVisibility();
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

	private void beginNativeReveal() {
		if (!manualSuppressionActive) {
			return;
		}

		nativeRevealActive = true;
		ownsHiddenView = false;
	}

	private void finishNativeReveal() {
		if (!nativeRevealActive) {
			return;
		}

		nativeRevealActive = false;
		if (manualSuppressionActive) {
			hideNativeView();
		}
	}

	private void hideNativeView() {
		final int chatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (chatView == CHAT_VIEW_HIDDEN) {
			syncChatVisibility();
			return;
		}

		lastChatView = chatView;
		ownsHiddenView = true;

		setNativeView(CHAT_VIEW_HIDDEN);
	}

	private void restoreOwnedView() {
		if (!ownsHiddenView) {
			return;
		}

		ownsHiddenView = false;
		setNativeView(lastChatView);
	}

	private void rememberVisibleView() {
		final int chatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (chatView != CHAT_VIEW_HIDDEN) {
			lastChatView = chatView;
		}
	}

	private void rememberVisibleGraphic() {
		for (int graphicId : CHAT_CONTROL_GRAPHIC_IDS) {
			final Widget graphic = client.getWidget(graphicId);
			if (graphic == null) {
				continue;
			}

			final int spriteId = graphic.getSpriteId();
			if (spriteId == SpriteID.ChatTabButton.SELECTED || spriteId == SpriteID.ChatTabButton.SELECTED_HOVERED) {
				lastChatGraphic = graphicId;
				return;
			}
		}
	}

	private void syncStoredGraphic(boolean selected) {
		if (lastChatGraphic == -1) {
			return;
		}

		final Widget graphic = client.getWidget(lastChatGraphic);
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

	boolean isChatHidden() {
		return client.getVarcIntValue(VarClientID.CHAT_VIEW) == CHAT_VIEW_HIDDEN;
	}

	private void setNativeView(int chatView) {
		final int currentChatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (currentChatView == chatView) {
			syncChatVisibility();
			return;
		}

		if (chatView == CHAT_VIEW_HIDDEN) {
			rememberVisibleGraphic();
		}

		client.setVarcIntValue(VarClientID.CHAT_VIEW, chatView);

		if (performanceMetrics != null) {
			performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.OTHER);
		}

		nativeRefreshActive = true;
		try {
			client.refreshChat();
		} finally {
			nativeRefreshActive = false;
		}

		syncChatVisibility();
		syncStoredGraphic(chatView != CHAT_VIEW_HIDDEN);
	}

	private void syncChatVisibility() {
		setChatHidden(isChatHidden());
	}

	private void setChatHidden(boolean hidden) {
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chatArea == null || chatArea.isSelfHidden() == hidden) {
			return;
		}

		chatArea.setHidden(hidden);
		recordMutation();
	}

	private void resetChatPresentation() {
		controlClickPending = false;
		manualSuppressionActive = false;
		nativeRevealActive = false;

		restoreOwnedView();
		syncChatVisibility();
	}

	/**
	 * ================================================================
	 * RESTORATION
	 * ================================================================
	 */
	private boolean restoreSharedGeometry() {
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (universe == null || chatArea == null) {
			return false;
		}

		final boolean wasApplied = resizedLayoutApplied;

		/*
		 * Release resizable ownership before restoring native presentation.
		 */
		resizedLayoutApplied = false;

		resetChatPresentation();
		resetDialoguePrompts();
		recordMutations(controlsLayout.restoreHidden());

		restoreNativeUniverse(universe);

		chatArea.setSize(ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_TAB_HEIGHT, chatArea.getWidthMode(), WidgetSizeMode.MINUS);
		recordMutation();

		recordMutations(controlsLayout.restoreNative());
		recordRevalidates(ChatboxWidgets.revalidateChildren(universe));

		final ChatboxBackgroundService.Result backgroundResult = backgroundService.restore(chatArea);

		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());

		if (wasApplied && performanceMetrics != null) {
			performanceMetrics.recordResizeRestore();
		}

		return true;
	}

	public void restoreNativeSize() {
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			handleInactiveLayout(layout);
			return;
		}

		geometryState = null;
		chatboxBoundsTracker.reset();
		chatboxPlacement.reset();
		resetChatPresentation();
		resetDialoguePrompts();
		recordMutations(controlsLayout.restoreHidden());

		final boolean wasApplied = resizedLayoutApplied;

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
			chatArea.setSize(ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_TAB_HEIGHT, chatArea.getWidthMode(), WidgetSizeMode.MINUS);
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

	/**
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

	/**
	 * ================================================================
	 * STATE TYPES
	 * ================================================================
	 */
	private static final class DialogueFitCandidate {
		private final int requestedWidth;
		private final int requestedHeight;
		private final ChatboxBounds.Result result;
		private final boolean expanded;
		private Rectangle baselineDesired;
		private int score = Integer.MAX_VALUE;
		private int preferredDeficit = Integer.MAX_VALUE;

		private DialogueFitCandidate(int requestedWidth, int requestedHeight, ChatboxBounds.Result result, boolean expanded) {
			this.requestedWidth = requestedWidth;
			this.requestedHeight = requestedHeight;
			this.result = result;
			this.expanded = expanded;
		}
	}

	private static final class GeometryState {
		private final int configuredWidth;
		private final int configuredHeight;
		private final Rectangle desiredBounds;
		private final Rectangle effectiveBounds;

		private GeometryState(int configuredWidth, int configuredHeight, Rectangle desiredBounds, Rectangle effectiveBounds) {
			this.configuredWidth = configuredWidth;
			this.configuredHeight = configuredHeight;
			this.desiredBounds = new Rectangle(desiredBounds);
			this.effectiveBounds = new Rectangle(effectiveBounds);
		}
	}

	private enum ScrollPosition {
		TOP,
		BOTTOM,
		MIDDLE
	}

	public static final class ScrollBaseline {
		private final ScrollPosition position;
		private final int viewportBottom;

		private ScrollBaseline(ScrollPosition position, int viewportBottom) {
			this.position = position;
			this.viewportBottom = viewportBottom;
		}
	}

	public static final class LiveRefresh {
		private final boolean widthChanged;
		private final boolean heightChanged;

		private LiveRefresh(boolean widthChanged, boolean heightChanged) {
			this.widthChanged = widthChanged;
			this.heightChanged = heightChanged;
		}

		public boolean isWidthChanged() {
			return widthChanged;
		}

		public boolean isHeightChanged() {
			return heightChanged;
		}
	}

	public static final class ResizeResult {
		private static final ResizeResult NOT_APPLIED = new ResizeResult(false, false, false);

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
