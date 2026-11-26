package com.bigboldchat.chatbox;

import java.awt.Rectangle;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.layout.ChatboxBounds;
import com.bigboldchat.layout.InterfaceBounds;
import com.bigboldchat.layout.SideContainerLayout;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Owns normal chatbox geometry, presentation and native layout lifecycle.
 * DialogueCoreService extends this service with temporary dialogue fitting.
 * Normal placement changes only after the native overlay and rendered host agree.
 */

public abstract class ChatboxCoreService {

	protected static final int CHAT_VIEW_ALL = 0;
	protected static final int CHAT_VIEW_HIDDEN = 1337;

	public static final int CHAT_VISIBILITY = 923;
	protected static final int CHAT_ONCHATTRANSMIT = 663;
	protected static final int TOPLEVEL_RELAYOUT = 1972;
	protected static final int SIDE_CONTAINER_UPDATE = 9336;

	protected static final String RUNELITE_CONFIG_GROUP = "runelite";
	protected static final String INVENTORY_LOCATION_KEY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_PARENT_preferredLocation";

	protected static final int LOGIN_STABLE_SAMPLES = 3;
	protected static final int RESIZE_STABLE_SAMPLES = 2;

	protected static final int[] CHAT_CONTROL_IDS = {InterfaceID.Chatbox.CHAT_ALL, InterfaceID.Chatbox.CHAT_GAME, InterfaceID.Chatbox.CHAT_PUBLIC, InterfaceID.Chatbox.CHAT_PRIVATE, InterfaceID.Chatbox.CHAT_FRIENDSCHAT, InterfaceID.Chatbox.CHAT_CLAN, InterfaceID.Chatbox.CHAT_TRADE};
	protected static final int[] CHAT_CONTROL_GRAPHIC_IDS = {InterfaceID.Chatbox.CHAT_ALL_GRAPHIC, InterfaceID.Chatbox.CHAT_GAME_GRAPHIC, InterfaceID.Chatbox.CHAT_PUBLIC_GRAPHIC, InterfaceID.Chatbox.CHAT_PRIVATE_GRAPHIC, InterfaceID.Chatbox.CHAT_FRIENDSCHAT_GRAPHIC, InterfaceID.Chatbox.CHAT_CLAN_GRAPHIC, InterfaceID.Chatbox.CHAT_TRADE_GRAPHIC};

	protected final Client client;
	protected final OverlayManager overlayManager;
	protected final PerformanceMetrics performanceMetrics;

	protected final ChatboxControlsLayout controlsLayout;
	protected final ChatboxBackgroundService backgroundService;
	protected final SideContainerLayout sideContainerLayout;
	protected final ChatboxPlacement chatboxPlacement;
	protected final ChatboxBounds.Tracker chatboxBoundsTracker;
	private BooleanSupplier overlayHotkeyHeld = () -> false;
	private final InterfaceBounds.ModalRelayout modalRelayout = new InterfaceBounds.ModalRelayout();
	private List<Rectangle> modalOtherObstacles;
	private boolean modalCommittedButtonsHidden;
	private ChatboxLayout modalCommittedLayout;

	protected boolean resizedLayoutApplied;
	protected boolean manualSuppressionActive;
	protected boolean nativeRevealActive;
	protected boolean nativeRefreshActive;

	protected boolean ownsHiddenView;
	protected boolean controlClickPending;
	protected boolean chatboxButtonsHidden;

	protected boolean liveWidthChanged;
	protected boolean liveHeightChanged;
	protected boolean widthRefreshPending;
	protected boolean heightRefreshPending;

	protected boolean loginGeometryPending;
	protected boolean canvasResizePending;

	protected int liveDepth;
	protected int sideLayoutDepth;

	protected int loginStableSamples;
	protected int resizeStableSamples;

	protected int observedCanvasWidth = -1;
	protected int observedCanvasHeight = -1;
	protected int lastChatboxCanvasWidth = -1;
	protected int lastChatboxCanvasHeight = -1;

	protected long loginGeometryFingerprint = Long.MIN_VALUE;
	protected Rectangle loginDesiredBounds;

	protected int lastChatView = CHAT_VIEW_ALL;
	protected int lastChatGraphic = -1;

	protected ScrollBaseline liveBaseline;
	protected GeometryState geometryState;
	protected InterfaceBounds.Overrides collisionInterfaceOverrides;

	// Persisted normal-chatbox center, never replaced by a temporary dialogue fit.
	private ChatboxPlacement.NormalPlacement normalChatboxPlacement;

	// Requires a new verified placement sample after a native save or normal reflow.
	private boolean normalChatboxSaveObserved;

	// Exact restoration target awaiting agreement between native and rendered bounds.
	private Rectangle pendingNormalRestoreBounds;

	protected ChatboxCoreService(Client client, ConfigManager configManager, OverlayManager overlayManager, PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.overlayManager = overlayManager;
		this.performanceMetrics = performanceMetrics;
		this.controlsLayout = new ChatboxControlsLayout(client);
		this.backgroundService = new ChatboxBackgroundService(client);
		this.sideContainerLayout = new SideContainerLayout(client, overlayManager);
		this.chatboxPlacement = new ChatboxPlacement(client, configManager, overlayManager);
		this.chatboxBoundsTracker = new ChatboxBounds.Tracker();
	}

	// Shared chatbox widget operations. The dialogue subclass calls the host bridge.
	protected final boolean controlsMatch(int width) {
		return controlsLayout.matches(width);
	}

	protected final int syncControlsHidden(boolean hidden) {
		return controlsLayout.syncHidden(hidden);
	}

	protected final int restoreControlsHidden() {
		return controlsLayout.restoreHidden();
	}

	protected final int restoreControlsNative() {
		return controlsLayout.restoreNative();
	}

	protected final int revalidateChatboxChildren(Widget parent) {
		return ChatboxWidgets.revalidateChildren(parent);
	}

	protected final void applyBackgroundForHost(Widget chatArea, int width, int bodyHeight) {
		final ChatboxBackgroundService.Result result = backgroundService.apply(chatArea, width, bodyHeight);
		recordMutations(result.getMutations());
		recordRevalidates(result.getRevalidates());
	}

	protected final void restoreBackgroundForHost(Widget chatArea) {
		final ChatboxBackgroundService.Result result = backgroundService.restore(chatArea);
		recordMutations(result.getMutations());
		recordRevalidates(result.getRevalidates());
	}

	protected ChatboxLayout getLayout() {
		// Classify gameplay from the mounted GAMEFRAME; the welcome screen remains UNKNOWN.
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

	/*
	 * The normal chatbox is the only owner of its durable placement. A dialogue
	 * may read this immutable reference, but may not overwrite or dirty it.
	 */
	protected final ChatboxPlacement.NormalPlacement getNormalChatboxPlacement() {
		return normalChatboxPlacement;
	}

	protected final boolean isNormalChatboxPlacementPending() {
		return normalChatboxPlacement == null || normalChatboxSaveObserved;
	}

	/*
	 * True while a dialogue owns the physical chatbox host (including closing
	 * and native readback). Normal placement must never sample those bounds.
	 */
	protected boolean isDialogueHostSessionActive() {
		return false;
	}

	// Prevent recording a provisional normal host while an overlay is dragged.
	protected boolean isChatboxPointerHeld() {
		return false;
	}

	private void markNormalChatboxPlacementDirty() {
		if (!isDialogueHostSessionActive()) {
			// A normal placement change supersedes a pending restoration.
			pendingNormalRestoreBounds = null;
			normalChatboxSaveObserved = true;
		}
	}

	/*
	 * Called only when the dialogue session has read back the expected normal
	 * rectangle from BOTH the rendered host and RuneLite's native overlay.
	 * The core independently rechecks that evidence and captures the native
	 * placement mode/saved origin; dialogue never assigns the durable center.
	 * A delayed config/render update leaves the center pending, not guessed.
	 */
	protected final boolean onVerifiedNormalChatboxRestoration(Rectangle expected) {
		if (expected == null) {
			return false;
		}
		pendingNormalRestoreBounds = new Rectangle(expected);
		normalChatboxSaveObserved = true;
		return reconcilePendingNormalRestoration();
	}

	/*
	 * An unverified restoration cannot replace the normal center.
	 * Retain its target until the native overlay and rendered host are both fully aligned.
	 */
	protected final void onUnverifiedNormalChatboxRestoration(Rectangle expected) {
		if (expected != null) {
			pendingNormalRestoreBounds = new Rectangle(expected);
			normalChatboxSaveObserved = true;
		}
	}

	private boolean reconcilePendingNormalRestoration() {
		final Rectangle expected = pendingNormalRestoreBounds;
		if (expected == null || canvasResizePending || loginGeometryPending || isChatboxPointerHeld() || !isUsablePlacement(expected) || !isResizableLayout(getLayout())) {
			return false;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle rendered = slot != null ? slot.getBounds() : null;
		final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
		if (!expected.equals(rendered) || !expected.equals(nativeBounds)) {
			return false;
		}
		// Here, unlike unsolicited native saves, a matching restoration signature is expected and must not suppress this verified readback.
		final ChatboxPlacement.NormalPlacement sample = chatboxPlacement.captureNormalPlacement(expected);
		if (sample == null) {
			return false;
		}
		adoptNormalChatboxPlacement(sample);
		pendingNormalRestoreBounds = null;
		return true;
	}

	/*
	 * Observe a settled normal host, never its collision-clipped child region.
	 * RuneLite's overlay configuration and physical widget must agree with the
	 * desired rectangle before the persistent center can change.
	 */
	private ChatboxPlacement.NormalPlacement sampleNormalChatboxPlacement(Rectangle desiredBounds) {
		if (desiredBounds == null || !isUsablePlacement(desiredBounds) || canvasResizePending || loginGeometryPending || isChatboxPointerHeld() || !isResizableLayout(getLayout())) {
			return null;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle rendered = slot != null ? slot.getBounds() : null;
		final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
		if (!desiredBounds.equals(rendered) || !desiredBounds.equals(nativeBounds)) {
			return null;
		}
		/*
		 * The caller reached this path only for a pending placement or a
		 * physical change from the cached normal bounds. Exact native/widget
		 * alignment is authoritative even when a prior restore-save marker
		 * still matches RuneLite's configuration signature.
		 */
		return chatboxPlacement.captureNormalPlacement(desiredBounds);
	}

	private void adoptNormalChatboxPlacement(ChatboxPlacement.NormalPlacement sample) {
		normalChatboxPlacement = sample;
		normalChatboxSaveObserved = false;
	}

	/*
	 * Capture proactively while normal chat is visible. A new effective bounds
	 * rectangle caused by an obstacle is deliberately NOT a placement event.
	 */
	private void reconcileNormalChatboxPlacement() {
		if (isDialogueHostSessionActive()) {
			return;
		}
		if (pendingNormalRestoreBounds != null) {
			reconcilePendingNormalRestoration();
			return;
		}
		if (geometryState == null) {
			return;
		}
		final Rectangle desired = geometryState.desiredBounds;
		if (!isNormalChatboxPlacementPending() && normalChatboxPlacement != null && normalChatboxPlacement.getBounds().equals(desired)) {
			return;
		}
		/*
		 * A native reset may relocate an already-settled normal host without
		 * emitting a new chatbox save. Geometry is authoritative only when
		 * the rendered widget and native overlay independently agree.
		 */
		final ChatboxPlacement.NormalPlacement sample = sampleNormalChatboxPlacement(desired);
		if (sample != null) {
			adoptNormalChatboxPlacement(sample);
		}
	}

	/*
	 * First dialogue page: capture only the verified pre-fit normal host.
	 * If an authorized normal save is still awaiting native/render alignment,
	 * return null rather than reuse an obsolete center.
	 */
	protected final ChatboxPlacement.NormalPlacement normalChatboxSnapshotForDialogue(Rectangle preDialogueBounds) {
		if (pendingNormalRestoreBounds != null) {
			if (!reconcilePendingNormalRestoration()) {
				return null;
			}
		}
		/*
		 * An unchanged cached placement is still the durable owner. But a
		 * different physical pre-dialogue host (including NATIVE reset or a
		 * configured-size change) must be recaptured before that old center
		 * is offered as the restoration target. Never silently reuse it.
		 */
		if (!isNormalChatboxPlacementPending() && normalChatboxPlacement != null && normalChatboxPlacement.getBounds().equals(preDialogueBounds)) {
			return normalChatboxPlacement;
		}
		final ChatboxPlacement.NormalPlacement sample = sampleNormalChatboxPlacement(preDialogueBounds);
		if (sample == null) {
			normalChatboxSaveObserved = true;
			return null;
		}
		adoptNormalChatboxPlacement(sample);
		return normalChatboxPlacement;
	}

	protected boolean isVisible(int widgetId) {
		final Widget widget = client.getWidget(widgetId);
		return widget != null && !widget.isHidden();
	}

	protected static boolean isResizableLayout(ChatboxLayout layout) {
		return layout == ChatboxLayout.RESIZABLE_CLASSIC || layout == ChatboxLayout.RESIZABLE_MODERN;
	}

	protected void beginLoginStabilization() {
		loginGeometryPending = true;
		resetLoginSamples();
	}

	protected void cancelLoginStabilization() {
		loginGeometryPending = false;
		resetLoginSamples();
	}

	protected void resetLoginSamples() {
		loginStableSamples = 0;
		loginGeometryFingerprint = Long.MIN_VALUE;
		loginDesiredBounds = null;
	}

	protected boolean isUsablePlacement(Rectangle desired) {
		if (desired == null || desired.isEmpty()) {
			return false;
		}

		final int canvasWidth = Math.max(0, client.getCanvasWidth());
		final int canvasHeight = Math.max(0, client.getCanvasHeight());
		if (canvasWidth <= 0 || canvasHeight <= 0) {
			return false;
		}

		// A settled host must fit each canvas axis when its desired size fits that axis.
		final boolean horizontalReady = desired.width > canvasWidth || desired.x >= 0 && desired.x + desired.width <= canvasWidth;
		final boolean verticalReady = desired.height > canvasHeight || desired.y >= 0 && desired.y + desired.height <= canvasHeight;

		return horizontalReady && verticalReady;
	}

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
			modalRelayout.cancel();
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

		// Restart persistent collision tracking from the settled geometry.
		cancelLoginStabilization();
		chatboxBoundsTracker.reset();
		modalRelayout.cancel();

		final ResizeResult settled = applySize(width, height);
		if (settled.isApplied()) {
			widthRefreshPending |= settled.isWidthChanged();
			heightRefreshPending |= settled.isHeightChanged();
		}
	}

	protected Widget getSlot(ChatboxLayout layout) {
		switch (layout) {
			case RESIZABLE_CLASSIC:
				return client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
			case RESIZABLE_MODERN:
				return client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);
			default:
				return null;
		}
	}

	protected void clearCanvasResize() {
		canvasResizePending = false;
		resizeStableSamples = 0;
		observedCanvasWidth = -1;
		observedCanvasHeight = -1;
		sideContainerLayout.endCanvasResize();
	}

	public boolean isInventoryOverlayReset(ConfigChanged event) {
		return event != null && RUNELITE_CONFIG_GROUP.equals(event.getGroup()) && INVENTORY_LOCATION_KEY.equals(event.getKey()) && event.getNewValue() == null;
	}

	// Recognizes RuneLite's saved chatbox overlay position and origin-axis changes.
	public boolean isChatboxOverlayPlacementChange(ConfigChanged event) {
		if (event == null || !RUNELITE_CONFIG_GROUP.equals(event.getGroup()) || event.getKey() == null) {
			return false;
		}

		final String key = event.getKey();
		final boolean chatboxOverlay = key.startsWith("RESIZABLE_VIEWPORT_CHATBOX_PARENT_") || key.startsWith("RESIZABLE_VIEWPORT_BOTTOM_LINE_CHATBOX_PARENT_");
		return chatboxOverlay && (key.endsWith("_preferredLocation") || key.endsWith("_preferredPosition") || key.endsWith("_originX") || key.endsWith("_originY"));
	}

	public void guardInventoryOverlayReset() {
		sideContainerLayout.guardInventoryOverlayReset();
	}

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

	protected void applyGeometry(Widget slot, Widget universe, Widget chatArea, int hostWidth, int hostHeight, int effectiveX, int effectiveY, int effectiveWidth, int effectiveHeight, int bodyHeight, boolean controlsChanged, boolean hostPositionChanged) {
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

		applyInnerGeometry(universe, chatArea, effectiveX, effectiveY, effectiveWidth, effectiveHeight, bodyHeight, controlsChanged);
	}

	protected void applyInnerGeometry(Widget universe, Widget chatArea, int effectiveX, int effectiveY, int effectiveWidth, int effectiveHeight, int bodyHeight, boolean controlsChanged) {
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
		if (chatArea.getOriginalWidth() != effectiveWidth || chatArea.getOriginalHeight() != bodyMargin || chatArea.getHeightMode() != WidgetSizeMode.MINUS) {
			chatArea.setSize(effectiveWidth, bodyMargin, chatArea.getWidthMode(), WidgetSizeMode.MINUS);
			recordMutation();
		}

		if (controlsChanged) {
			recordMutations(controlsLayout.apply(effectiveWidth));
		}

		recordRevalidates(ChatboxWidgets.revalidateChildren(universe));
	}

	protected void beginLiveScroll() {
		if (liveDepth++ != 0) {
			return;
		}

		liveBaseline = captureScrollBaseline();
		liveWidthChanged = false;
		liveHeightChanged = false;
	}

	protected void finishLiveScroll() {
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

			// Queue retained-chat reconstruction for the render boundary.
			widthRefreshPending |= widthChanged;
			heightRefreshPending |= heightChanged;
		}
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

	protected void restoreRebuildScroll(ScrollBaseline baseline) {
		restoreScrollBaseline(baseline, true);
	}

	protected void restoreScrollBaseline(ScrollBaseline baseline, boolean reanchor) {
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

	protected void reanchorScrollBottom(Widget scrollArea, int height) {
		final int shift = height - scrollArea.getScrollHeight();
		if (shift <= 0) {
			return;
		}

		shiftScrollChildren(scrollArea.getStaticChildren(), shift);
		shiftScrollChildren(scrollArea.getDynamicChildren(), shift);
		scrollArea.setScrollHeight(height);
		recordMutation();
	}

	protected void shiftScrollChildren(Widget[] children, int shift) {
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

	protected void syncChatPresentation() {
		rememberVisibleView();

		// Preserve the native chat-control transition until the click completes.
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

	protected int findChatControl(Widget widget) {
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

	protected void beginNativeReveal() {
		if (!manualSuppressionActive) {
			return;
		}

		nativeRevealActive = true;
		ownsHiddenView = false;
	}

	protected void finishNativeReveal() {
		if (!nativeRevealActive) {
			return;
		}

		nativeRevealActive = false;
		if (manualSuppressionActive) {
			hideNativeView();
		}
	}

	protected void hideNativeView() {
		final int chatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (chatView == CHAT_VIEW_HIDDEN) {
			syncChatVisibility();
			return;
		}

		lastChatView = chatView;
		ownsHiddenView = true;

		setNativeView(CHAT_VIEW_HIDDEN);
	}

	protected void restoreOwnedView() {
		if (!ownsHiddenView) {
			return;
		}

		ownsHiddenView = false;
		setNativeView(lastChatView);
	}

	protected void rememberVisibleView() {
		final int chatView = client.getVarcIntValue(VarClientID.CHAT_VIEW);
		if (chatView != CHAT_VIEW_HIDDEN) {
			lastChatView = chatView;
		}
	}

	protected void rememberVisibleGraphic() {
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

	protected void syncStoredGraphic(boolean selected) {
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

	protected boolean isChatHidden() {
		return client.getVarcIntValue(VarClientID.CHAT_VIEW) == CHAT_VIEW_HIDDEN;
	}

	protected void setNativeView(int chatView) {
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

	protected void syncChatVisibility() {
		setChatHidden(isChatHidden());
	}

	protected void setChatHidden(boolean hidden) {
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chatArea == null || chatArea.isSelfHidden() == hidden) {
			return;
		}

		chatArea.setHidden(hidden);
		recordMutation();
	}

	protected void resetChatPresentation() {
		controlClickPending = false;
		manualSuppressionActive = false;
		nativeRevealActive = false;

		restoreOwnedView();
		syncChatVisibility();
	}

	@SuppressWarnings("deprecation")
	protected void restoreNativeUniverse(Widget universe) {
		universe.setSize(0, 0, WidgetSizeMode.MINUS, WidgetSizeMode.MINUS);
		universe.setForcedPosition(-1, -1);
		recordMutation();

		universe.revalidate();
		recordRevalidate();

		universe.setWidth(ChatboxGeometry.NATIVE_WIDTH);
		universe.setHeight(ChatboxGeometry.NATIVE_SLOT_HEIGHT);
		recordMutation();
	}

	protected void recordApply(long started, boolean widthChanged, boolean heightChanged) {
		if (performanceMetrics == null || !performanceMetrics.isEnabled()) {
			return;
		}

		performanceMetrics.recordResizeApply(System.nanoTime() - started, widthChanged, heightChanged);
	}

	protected void recordMissingWidgets() {
		if (performanceMetrics != null) {
			performanceMetrics.recordResizeMissingWidgets();
		}
	}

	protected void recordMutation() {
		if (performanceMetrics != null) {
			performanceMetrics.recordWidgetMutation();
		}
	}

	protected void recordMutations(int count) {
		if (performanceMetrics == null || count <= 0) {
			return;
		}

		for (int i = 0; i < count; i++) {
			performanceMetrics.recordWidgetMutation();
		}
	}

	protected void recordRevalidate() {
		if (performanceMetrics != null) {
			performanceMetrics.recordRevalidate();
		}
	}

	protected void recordRevalidates(int count) {
		if (performanceMetrics == null || count <= 0) {
			return;
		}

		for (int i = 0; i < count; i++) {
			performanceMetrics.recordRevalidate();
		}
	}

	public void onGameStateChanged(GameState gameState) {
		if (gameState == null) {
			return;
		}

		// Discard transient tracking; RuneLite retains the saved overlay position.
		onDialogueGameStateReset();
		pendingNormalRestoreBounds = null;
		normalChatboxSaveObserved = true;
		geometryState = null;
		chatboxPlacement.reset();
		chatboxBoundsTracker.reset();
		modalRelayout.bypass(null);
		modalOtherObstacles = null;
		collisionInterfaceOverrides = null;
		onDialoguePromptsReset();
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

	protected void handleInactiveLayout(ChatboxLayout layout) {
		// Fixed restores ChatXL-owned shared geometry; UNKNOWN clears transient state only.
		if (layout == ChatboxLayout.FIXED && resizedLayoutApplied) {
			restoreSharedGeometry();
		}

		onDialogueInactiveLayout();
		pendingNormalRestoreBounds = null;
		normalChatboxSaveObserved = true;
		geometryState = null;
		chatboxBoundsTracker.reset();
		modalRelayout.bypass(null);
		modalOtherObstacles = null;
		collisionInterfaceOverrides = null;
		onDialoguePromptsReset();
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

	public void onCanvasSizeChanged() {
		final ChatboxLayout layout = getLayout();
		if (isResizableLayout(layout)) {
			markNormalChatboxPlacementDirty();
		}
		if (layout != ChatboxLayout.RESIZABLE_MODERN) {
			clearCanvasResize();
			return;
		}

		onDialogueCanvasSizeChanging();
		canvasResizePending = true;
		resizeStableSamples = 0;
		observedCanvasWidth = Math.max(0, client.getCanvasWidth());
		observedCanvasHeight = Math.max(0, client.getCanvasHeight());

		final SideContainerLayout.Result sideResult = sideContainerLayout.onCanvasSizeChanged();
		recordMutations(sideResult.getMutations());
		recordRevalidates(sideResult.getRevalidates());
	}

	protected boolean restoreSharedGeometry() {
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (universe == null || chatArea == null) {
			return false;
		}

		final boolean wasApplied = resizedLayoutApplied;

		// Release resizable ownership before restoring native presentation.
		resizedLayoutApplied = false;

		resetChatPresentation();
		onDialoguePromptsReset();
		recordMutations(restoreControlsHidden());

		restoreNativeUniverse(universe);

		chatArea.setSize(ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_TAB_HEIGHT, chatArea.getWidthMode(), WidgetSizeMode.MINUS);
		recordMutation();

		recordMutations(restoreControlsNative());
		recordRevalidates(revalidateChatboxChildren(universe));

		restoreBackgroundForHost(chatArea);

		if (wasApplied && performanceMetrics != null) {
			performanceMetrics.recordResizeRestore();
		}

		return true;
	}

	// Releases owned widget geometry and restores RuneLite's native chatbox layout.
	public void restoreNativeSize() {
		// Releases a pending close guard when ChatXL is disabled mid-dialogue.
		onDialogueNativeGeometryRestore();
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			handleInactiveLayout(layout);
			return;
		}

		geometryState = null;
		chatboxBoundsTracker.reset();
		modalRelayout.bypass(null);
		modalOtherObstacles = null;
		collisionInterfaceOverrides = null;
		chatboxPlacement.reset();
		resetChatPresentation();
		onDialoguePromptsReset();
		recordMutations(restoreControlsHidden());

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

		recordMutations(restoreControlsNative());
		recordRevalidates(revalidateChatboxChildren(universe));

		restoreBackgroundForHost(chatArea);

		resizedLayoutApplied = false;

		if (wasApplied && performanceMetrics != null) {
			performanceMetrics.recordResizeRestore();
		}
	}

	protected void onDialogueGameStateReset() {
	}

	protected void onDialogueInactiveLayout() {
	}

	protected void onDialogueCanvasSizeChanging() {
	}

	protected void onDialogueNativeGeometryRestore() {
	}

	protected void onDialoguePromptsReset() {
	}

	public void setOverlayHotkeyHeld(BooleanSupplier overlayHotkeyHeld) {
		this.overlayHotkeyHeld = overlayHotkeyHeld;
	}

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

		onDialogueScriptPreFired(scriptId);

		if (scriptId == ScriptID.MESSAGE_LAYER_OPEN || scriptId == CHAT_VISIBILITY && !controlClickPending && !nativeRefreshActive) {
			beginNativeReveal();
		}

		if (scriptId == SIDE_CONTAINER_UPDATE && layout == ChatboxLayout.RESIZABLE_MODERN) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.reconcileInventoryReveal();
			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		}

		// Clear pending plugin refreshes before native chat reconstruction.
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
			adoptLiveDialogueHostPosition(width, height);
			beginLiveScroll();

			/*
			 * A single client cycle can run 1972 several times and expose transient
			 * host positions between those passes. Do not fight those intermediate
			 * coordinates while an adaptive dialogue fit is active. Reassert the
			 * committed fit once from PostClientTick after the native script batch.
			 */
			final GeometryState dialogueGeometry = committedDialogueGeometry(width, height);
			if (dialogueGeometry != null) {
				markDialogueRelayoutDirty();
				return ResizeResult.NOT_APPLIED;
			}

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

	protected boolean shouldUseCommittedRelayout() {
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
			recordMutations(syncControlsHidden(chatboxButtonsHidden));
		}

		if (scriptId == ScriptID.MESSAGE_LAYER_CLOSE) {
			onDialogueNativeLayerClose();
			finishNativeReveal();
		}

		if (scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE && sideLayoutDepth > 0 && --sideLayoutDepth == 0) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.endNativeLayout();
			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		}

		if (scriptId == TOPLEVEL_RELAYOUT) {
			final GeometryState dialogueGeometry = committedDialogueGeometry(width, height);
			if (dialogueGeometry != null) {
				markDialogueRelayoutDirty();
			}

			finishLiveScroll();

			if (dialogueGeometry == null) {
				// Reapply existing side-row ownership without reevaluating row policy.
				if (layout == ChatboxLayout.RESIZABLE_MODERN) {
					final SideContainerLayout.Result sideResult = sideContainerLayout.reassertOwnedLayout();
					recordMutations(sideResult.getMutations());
					recordRevalidates(sideResult.getRevalidates());
				}

				syncDialoguePrompts();
			}

			return ResizeResult.NOT_APPLIED;
		}

		if (scriptId == ScriptID.TOPLEVEL_REDRAW || scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE || scriptId == ScriptID.MESSAGE_LAYER_OPEN) {
			final ResizeResult result = applySize(width, height);
			syncDialoguePrompts();
			return result;
		}

		if (onDialogueScriptPostFired(scriptId)) {
			return ResizeResult.NOT_APPLIED;
		}

		syncDialoguePrompts();
		return ResizeResult.NOT_APPLIED;
	}

	protected void onDialogueScriptPreFired(int scriptId) {
	}

	protected void onDialogueNativeLayerClose() {
	}

	protected boolean onDialogueScriptPostFired(int scriptId) {
		return false;
	}

	protected void adoptLiveDialogueHostPosition(int width, int height) {
	}

	protected GeometryState committedDialogueGeometry(int width, int height) {
		return null;
	}

	protected void syncDialoguePrompts() {
	}

	protected ResizeResult applyCommittedGeometry(int width, int height) {
		return applySize(width, height);
	}

	// Reapply committed inner geometry without changing placement or collision ownership.
	private ResizeResult reassertModalCommittedGeometry(int width, int height, ChatboxLayout layout) {
		if (!canDeferModalGeometry(width, height, layout)) {
			return ResizeResult.NOT_APPLIED;
		}
		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (slot == null || universe == null || chatArea == null) {
			return ResizeResult.NOT_APPLIED;
		}
		final Widget parent = universe.getParent();
		if (parent == null || parent.getId() != slot.getId()) {
			return ResizeResult.NOT_APPLIED;
		}

		final Rectangle desired = geometryState.desiredBounds;
		final Rectangle effective = geometryState.effectiveBounds;
		// Native placement changes take precedence over modal settlement.
		if (!desired.equals(slot.getBounds()) || !desired.equals(chatboxPlacement.hostBounds(slot))) {
			return ResizeResult.NOT_APPLIED;
		}

		final int relativeX = Math.max(0, effective.x - desired.x);
		final int relativeY = Math.max(0, effective.y - desired.y);
		final int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
		if (universe.getRelativeX() != relativeX || universe.getRelativeY() != relativeY || universe.getWidth() != effective.width || universe.getHeight() != effective.height || chatArea.getWidth() != effective.width || chatArea.getHeight() != bodyHeight || !controlsMatch(effective.width)) {
			applyInnerGeometry(universe, chatArea, relativeX, relativeY, effective.width, effective.height, bodyHeight, !controlsMatch(effective.width));
		}
		return new ResizeResult(true, false, false);
	}

	// Preserve the native management fallback during resets and mouse release.
	private boolean overlayManagementActive() {
		return (isChatboxPointerHeld() && overlayHotkeyHeld.getAsBoolean()) || hasNativeCornerOverlays();
	}

	private boolean hasNativeCornerOverlays() {
		return overlayManager != null && overlayManager.anyMatch(overlay -> {
			final String name = overlay.getName();
			return name != null && name.startsWith("CornerOverlay_");
		});
	}

	// Only defer a modal transition while the committed geometry remains eligible.
	private boolean canDeferModalGeometry(int width, int height, ChatboxLayout layout) {
		return resizedLayoutApplied && geometryState != null && modalCommittedLayout == layout && modalLayoutMatches(layout) && geometryState.configuredWidth == width && geometryState.configuredHeight == height && modalCommittedButtonsHidden == chatboxButtonsHidden && (lastChatboxCanvasWidth <= 0 || (lastChatboxCanvasWidth == client.getCanvasWidth() && lastChatboxCanvasHeight == client.getCanvasHeight())) && !loginGeometryPending && !canvasResizePending && !isDialogueHostSessionActive() && !overlayManagementActive() && pendingNormalRestoreBounds == null && !manualSuppressionActive && (!nativeRefreshActive || modalRelayout.isActive()) && !controlClickPending;
	}

	private boolean modalLayoutMatches(ChatboxLayout layout) {
		final int topLevel = client.getTopLevelInterfaceId();
		return layout == ChatboxLayout.RESIZABLE_CLASSIC && topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH || layout == ChatboxLayout.RESIZABLE_MODERN && topLevel == InterfaceID.TOPLEVEL_PRE_EOC;
	}

	private boolean observeModalOtherObstacles() {
		final List<Rectangle> current = InterfaceBounds.otherObstacleBounds(client);
		final boolean unchanged = modalOtherObstacles == null || modalOtherObstacles.equals(current);
		modalOtherObstacles = current;
		return unchanged;
	}

	// Return the prior accepted geometry only while the shared transaction is active.
	private ResizeResult deferModalGeometry(int width, int height, ChatboxLayout layout, InterfaceBounds.Overrides suppliedOverrides) {
		if (!modalLayoutMatches(layout)) {
			modalRelayout.bypass(null);
			modalOtherObstacles = null;
			return ResizeResult.NOT_APPLIED;
		}
		final Widget root = InterfaceBounds.mainModalRoot(client);
		final boolean otherObstaclesUnchanged = observeModalOtherObstacles();
		if (suppliedOverrides != null || !canDeferModalGeometry(width, height, layout) || !otherObstaclesUnchanged) {
			modalRelayout.bypass(root);
			return ResizeResult.NOT_APPLIED;
		}
		if (modalRelayout.beforeGeometryUpdate(root)) {
			final ResizeResult committed = reassertModalCommittedGeometry(width, height, layout);
			if (committed.isApplied()) {
				return committed;
			}
			modalRelayout.bypass(root);
		}
		return ResizeResult.NOT_APPLIED;
	}

	private void reconcileModalRelayout(int width, int height, ChatboxLayout layout) {
		if (!modalLayoutMatches(layout)) {
			modalRelayout.bypass(null);
			modalOtherObstacles = null;
			return;
		}
		final Widget root = InterfaceBounds.mainModalRoot(client);
		if (!modalRelayout.needsObservation(root)) {
			return;
		}
		final boolean otherObstaclesUnchanged = observeModalOtherObstacles();
		boolean solve;
		if (!canDeferModalGeometry(width, height, layout) || !otherObstaclesUnchanged) {
			modalRelayout.bypass(root);
			solve = true;
		} else {
			solve = modalRelayout.beforeRender(root, InterfaceBounds.mainModalContentBounds(client));
			if (!solve && modalRelayout.isActive()) {
				if (!reassertModalCommittedGeometry(width, height, layout).isApplied()) {
					modalRelayout.bypass(root);
					solve = true;
				}
			}
		}
		if (solve) {
			final ResizeResult settled = applySize(width, height);
			if (settled.isApplied()) {
				widthRefreshPending |= settled.isWidthChanged();
				heightRefreshPending |= settled.isHeightChanged();
			}
		}
	}

	public ResizeResult applySize(int width, int height) {
		return applySizeInternal(width, height, true, null);
	}

	/*
	 * Resolves the desired host separately from its collision-constrained viewport.
	 * Dialogue hooks may select a temporary host without changing normal ownership.
	 */
	protected ResizeResult applySizeInternal(int width, int height, boolean evaluateSideLayout, InterfaceBounds.Overrides suppliedOverrides) {
		if (shouldDeferChatboxResize(width, height)) {
			return ResizeResult.NOT_APPLIED;
		}
		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime() : 0L;
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			handleInactiveLayout(layout);
			return ResizeResult.NOT_APPLIED;
		}

		final ResizeResult deferred = deferModalGeometry(width, height, layout, suppliedOverrides);
		if (deferred.isApplied()) {
			return deferred;
		}

		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		recordMutations(syncControlsHidden(chatboxButtonsHidden));

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (slot == null || universe == null || chatArea == null) {
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		// Require UNIVERSE to be mounted under the active chat container.
		final Widget parent = universe.getParent();
		if (parent == null || parent.getId() != slot.getId()) {
			return ResizeResult.NOT_APPLIED;
		}

		/* Configured size/anchored reflow is a normal placement change.
		 * Pure obstacle clipping changes only effectiveBounds, not the
		 * durable normal reference. The new host is sampled after render. */
		if (geometryState != null && (geometryState.configuredWidth != width || geometryState.configuredHeight != height)) {
			markNormalChatboxPlacementDirty();
		}
		final boolean restoreTemporaryHost = prepareDialogueHostFit(width, height);
		final int requestedWidth = requestedChatboxWidth(width);
		final int requestedHeight = requestedChatboxHeight(height);
		final ChatboxPlacement.State placement = captureChatboxPlacement(slot, requestedWidth, requestedHeight);
		final Rectangle desiredBounds = placement.getDesiredBounds();
		if (placement.requiresHostReposition()) {
			// Correct a stale host remount offset once before collision solving.
			slot.setForcedPosition(slot.getRelativeX() + placement.getHostDeltaX(), slot.getRelativeY() + placement.getHostDeltaY());
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
		if (placement.requiresHostAnchorUpdate() && allowChatboxHostAnchorUpdate()) {
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
			// Use SideContainerLayout targets for same-pass chatbox collision solving.
			final SideContainerLayout.Result sideResult = sideContainerLayout.apply(slot, requestedWidth, requestedHeight);

			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
			interfaceOverrides = sideResult.getInterfaceOverrides();
		} else if (layout != ChatboxLayout.RESIZABLE_MODERN) {
			sideContainerLayout.reset();
		}

		interfaceOverrides = refineChatboxCollisionOverrides(interfaceOverrides);
		collisionInterfaceOverrides = interfaceOverrides;

		// Use the full desired rectangle until post-login geometry stabilizes.
		final Rectangle effectiveBounds;
		ChatboxBounds.Result collisionResult = null;
		if (loginGeometryPending) {
			effectiveBounds = new Rectangle(desiredBounds);
			geometryState = new GeometryState(width, height, desiredBounds, effectiveBounds);
		} else {
			collisionResult = ChatboxBounds.resolve(client, placement, chatboxButtonsHidden, chatboxBoundsTracker, interfaceOverrides);
			effectiveBounds = collisionResult.getEffectiveBounds();
			geometryState = new GeometryState(width, height, collisionResult.getDesiredBounds(), effectiveBounds);
		}
		onChatboxCollisionSolved(placement, interfaceOverrides, collisionResult, slot, universe, width, height, requestedWidth, requestedHeight);
		final Rectangle hostBounds = selectChatboxHostBounds(desiredBounds, effectiveBounds);
		final int hostWidth = hostBounds.width;
		final int hostHeight = hostBounds.height;
		final int effectiveWidth = effectiveBounds.width;
		final int effectiveHeight = effectiveBounds.height;
		final int effectiveX = hostBounds.equals(effectiveBounds) ? 0 : Math.max(0, effectiveBounds.x - desiredBounds.x);
		final int effectiveY = hostBounds.equals(effectiveBounds) ? 0 : Math.max(0, effectiveBounds.y - desiredBounds.y);
		final int effectiveBodyHeight = ChatboxGeometry.bodyHeight(effectiveHeight, chatboxButtonsHidden);
		final boolean temporaryHostPositionChanged = updateDialogueTemporaryHost(slot, placement, hostBounds, restoreTemporaryHost);
		final boolean hostPositionChanged = configuredHostPositionChanged || temporaryHostPositionChanged;

		final boolean hostChanged = slot.getWidth() != hostWidth || slot.getHeight() != hostHeight;
		final boolean positionChanged = universe.getRelativeX() != effectiveX || universe.getRelativeY() != effectiveY;
		final boolean widthChanged = universe.getWidth() != effectiveWidth || chatArea.getWidth() != effectiveWidth;
		final boolean heightChanged = universe.getHeight() != effectiveHeight || chatArea.getHeight() != effectiveBodyHeight;
		final boolean controlsChanged = !controlsMatch(effectiveWidth);

		if (hostPositionChanged || hostChanged || positionChanged || widthChanged || heightChanged || controlsChanged) {
			final boolean revalidateForPosition = hostPositionChanged && shouldRevalidateHostPosition();
			applyGeometry(slot, universe, chatArea, hostWidth, hostHeight, effectiveX, effectiveY, effectiveWidth, effectiveHeight, effectiveBodyHeight, controlsChanged, revalidateForPosition);
		}
		onChatboxGeometryApplied(placement, interfaceOverrides, collisionResult, slot, universe, width, height, requestedWidth, requestedHeight);

		applyBackgroundForHost(chatArea, effectiveWidth, effectiveBodyHeight);
		if (liveDepth == 0) {
			onChatboxEffectiveSizeApplied(effectiveWidth, effectiveBodyHeight);
		}

		syncChatPresentation();

		resizedLayoutApplied = true;
		modalCommittedButtonsHidden = chatboxButtonsHidden;
		modalCommittedLayout = layout;

		recordApply(started, widthChanged, heightChanged);

		return new ResizeResult(true, widthChanged, heightChanged);
	}

	/*
	 * Dialogue-specific hooks preserve temporary host ownership during the
	 * shared resize and render lifecycle. Core placement checks remain authoritative.
	 */
	protected boolean shouldDeferChatboxResize(int width, int height) {
		return false;
	}

	protected boolean prepareDialogueHostFit(int width, int height) {
		return false;
	}

	protected int requestedChatboxWidth(int width) {
		return width;
	}

	protected int requestedChatboxHeight(int height) {
		return height;
	}

	protected ChatboxPlacement.State captureChatboxPlacement(Widget slot, int width, int height) {
		return chatboxPlacement.capture(slot, width, height);
	}

	protected boolean allowChatboxHostAnchorUpdate() {
		return !canvasResizePending;
	}

	protected InterfaceBounds.Overrides refineChatboxCollisionOverrides(InterfaceBounds.Overrides overrides) {
		return overrides;
	}

	protected void onChatboxCollisionSolved(ChatboxPlacement.State placement, InterfaceBounds.Overrides overrides, ChatboxBounds.Result result, Widget slot, Widget universe, int width, int height, int requestedWidth, int requestedHeight) {
	}

	protected Rectangle selectChatboxHostBounds(Rectangle desiredBounds, Rectangle effectiveBounds) {
		return desiredBounds;
	}

	protected boolean updateDialogueTemporaryHost(Widget slot, ChatboxPlacement.State placement, Rectangle hostBounds, boolean restoreTemporaryHost) {
		return false;
	}

	protected boolean shouldRevalidateHostPosition() {
		return true;
	}

	protected void onChatboxGeometryApplied(ChatboxPlacement.State placement, InterfaceBounds.Overrides overrides, ChatboxBounds.Result result, Widget slot, Widget universe, int width, int height, int requestedWidth, int requestedHeight) {
	}

	protected void onChatboxEffectiveSizeApplied(int effectiveWidth, int effectiveBodyHeight) {
	}

	/*
	 * Reconciles native widget changes at the render boundary, then samples
	 * only an aligned normal host for durable placement updates.
	 */
	public void reconcileBeforeRender(int width, int height) {
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			modalRelayout.bypass(null);
			modalOtherObstacles = null;
			clearCanvasResize();
			return;
		}

		if (layout != ChatboxLayout.RESIZABLE_MODERN) {
			final boolean canvasChanged = lastChatboxCanvasWidth > 0 && (lastChatboxCanvasWidth != client.getCanvasWidth() || lastChatboxCanvasHeight != client.getCanvasHeight());
			if (canvasChanged) {
				markNormalChatboxPlacementDirty();
			}
			if (!canvasChanged && layout == ChatboxLayout.RESIZABLE_CLASSIC) {
				reconcileModalRelayout(width, height, layout);
			} else if (canvasChanged) {
				modalRelayout.bypass(InterfaceBounds.mainModalRoot(client));
				modalOtherObstacles = null;
			}

			lastChatboxCanvasWidth = Math.max(0, client.getCanvasWidth());
			lastChatboxCanvasHeight = Math.max(0, client.getCanvasHeight());
			clearCanvasResize();
			reconcileNormalChatboxPlacement();
			onDialogueCollisionBeforeRender(width, height);
			onDialogueBeforeRender(width, height);
			onDialogueSettledDrop();
			return;
		}

		// Capture canvas changes even when a native size event is skipped.
		if (!canvasResizePending && lastChatboxCanvasWidth > 0 && (lastChatboxCanvasWidth != client.getCanvasWidth() || lastChatboxCanvasHeight != client.getCanvasHeight())) {
			onCanvasSizeChanged();
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

		if (!canvasResizePending) {
			lastChatboxCanvasWidth = Math.max(0, client.getCanvasWidth());
			lastChatboxCanvasHeight = Math.max(0, client.getCanvasHeight());
			onDialogueCanvasSettled(width, height);
		}

		onDialogueCollisionBeforeRender(width, height);

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

		if (!canvasResizePending) {
			reconcileModalRelayout(width, height, layout);
		} else {
			modalRelayout.bypass(InterfaceBounds.mainModalRoot(client));
			modalOtherObstacles = null;
		}

		// Only a settled NORMAL host is eligible to refresh the durable center.
		reconcileNormalChatboxPlacement();

		/*
		 * Native dialogue pages can rebuild after the script callbacks that applied ChatXL geometry.
		 * Reconcile once at the render boundary so each newly mounted/page-advanced dialogue is laid
		 * out against the current effective chatbox before it becomes visible.
		 */
		onDialogueBeforeRender(width, height);
		onDialogueSettledDrop();
	}

	protected void onDialogueCollisionBeforeRender(int width, int height) {
	}

	protected void onDialogueBeforeRender(int width, int height) {
	}

	protected void onDialogueSettledDrop() {
	}

	protected void onDialogueCanvasSettled(int width, int height) {
	}

	protected void markDialogueRelayoutDirty() {
	}

	/*
	 * De-duplicates native placement saves before updating normal ownership.
	 * Dialogue-owned saves are handled by the dialogue lifecycle first.
	 */
	public void onSavedChatboxPlacementChanged(int width, int height) {
		if (onDialogueNativeChatboxPlacementSave(width, height)) {
			return;
		}
		final boolean dialogueFallback = isDialogueHostSessionActive();
		/*
		 * A single native drag can emit several configuration callbacks.
		 * A duplicate does not authorize another normal-center update or resize.
		 */
		if (!chatboxPlacement.isNewNativePlacementSave()) {
			return;
		}
		if (!chatboxPlacement.isNormalRestoreSave()) {
			markNormalChatboxPlacementDirty();
		}
		chatboxPlacement.markNativePlacementAdopted();
		if (dialogueFallback) {
			onDialogueNativeSaveFallback();
		}
		chatboxPlacement.invalidateSavedPlacement();
		if (dialogueFallback) {
			clearDialogueFitForNativeSaveFallback();
		}
		geometryState = null;
		chatboxBoundsTracker.reset();
		modalRelayout.bypass(null);
		modalOtherObstacles = null;
		collisionInterfaceOverrides = null;
		final ResizeResult result = applySizeInternal(width, height, true, null);
		if (result.isApplied()) {
			widthRefreshPending |= result.isWidthChanged();
			heightRefreshPending |= result.isHeightChanged();
		}
	}

	protected boolean onDialogueNativeChatboxPlacementSave(int width, int height) {
		return false;
	}

	protected void onDialogueNativeSaveFallback() {
	}

	protected void clearDialogueFitForNativeSaveFallback() {
	}

	// Normal chatbox maintenance runs independently of dialogue processing.
	public void reconcileAfterClientTick(int width, int height) {
		reconcileDialogueAfterClientTick(width, height);
	}

	protected void reconcileDialogueAfterClientTick(int width, int height) {
	}

	protected final void reassertModernChatboxRows() {
		if (getLayout() != ChatboxLayout.RESIZABLE_MODERN) {
			return;
		}
		final SideContainerLayout.Result sideResult = sideContainerLayout.reassertOwnedLayout();
		recordMutations(sideResult.getMutations());
		recordRevalidates(sideResult.getRevalidates());
	}

	public static final class GeometryState {
		public final int configuredWidth;
		public final int configuredHeight;
		public final Rectangle desiredBounds;
		public final Rectangle effectiveBounds;

		public GeometryState(int configuredWidth, int configuredHeight, Rectangle desiredBounds, Rectangle effectiveBounds) {
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
		public static final ResizeResult NOT_APPLIED = new ResizeResult(false, false, false);

		private final boolean applied;
		private final boolean widthChanged;
		private final boolean heightChanged;

		public ResizeResult(boolean applied, boolean widthChanged, boolean heightChanged) {
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
