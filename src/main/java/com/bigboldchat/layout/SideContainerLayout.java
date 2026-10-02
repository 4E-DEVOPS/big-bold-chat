package com.bigboldchat.layout;

import java.awt.Point;
import java.awt.Rectangle;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Owns plugin-managed layout changes to the Modern side container.
 */
public final class SideContainerLayout {
	private static final String STATIC_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS1";
	private static final String MOVABLE_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS2";
	private static final String INVENTORY_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_PARENT";
	private static final int MIN_ONE_ROW_OVERLAP = 1;
	private static final int MAX_ONE_ROW_OVERLAP = 37;
	private static final int INVENTORY_EDGE_TOLERANCE = 5;
	private static final int INVENTORY_HORIZONTAL_TOLERANCE = 5;

	private final Client client;
	private final OverlayManager overlayManager;
	private final CollisionManager collisionManager;

	private boolean nativeLayoutRunning;
	private boolean gameStateSuspended;
	private boolean canvasResizeInProgress;
	private volatile boolean pluginTwoRows;
	private volatile boolean staticRowOwned;
	private volatile boolean movableRowOwned;
	private boolean oneRowKnown;
	private int staticRowX;
	private int staticRowY;
	private int movableRowX;
	private int movableRowY;
	private Rectangle staticRowBounds;
	private Rectangle movableRowBounds;
	private Rectangle containerRowBounds;
	private volatile InventoryAttachment rowInventoryAttachment = InventoryAttachment.NONE;
	private int rowCanvasWidth;
	private int rowCanvasHeight;
	private boolean canvasRebasePending;
	private CollisionManager.Side splitSide = CollisionManager.Side.NONE;
	private volatile VerticalSplit verticalSplit = VerticalSplit.UP;
	private CollisionManager.Source collisionSource = CollisionManager.Source.NONE;
	private OverlayState staticOverlayState;
	private OverlayState movableOverlayState;
	private volatile OverlayState inventoryOverlayState;
	private boolean inventoryForcedPosition;
	private volatile boolean inventoryUserOverride;
	private volatile Point inventoryOwnedTarget;
	private volatile Point inventoryGuardLocation;

	public SideContainerLayout(Client client) {
		this(client, null);
	}

	public SideContainerLayout(Client client, OverlayManager overlayManager) {
		this(client, overlayManager, new CollisionManager(client));
	}

	SideContainerLayout(Client client, OverlayManager overlayManager, CollisionManager collisionManager) {
		this.client = client;
		this.overlayManager = overlayManager;
		this.collisionManager = collisionManager;
	}

	/**
	 * ================================================================
	 * LAYOUT LIFECYCLE
	 * ================================================================
	 */
	public void suspendForGameState() {
		if (!gameStateSuspended) {
			restoreNative();
		}

		gameStateSuspended = true;
		nativeLayoutRunning = false;
	}

	/*
	 * Starts a logged-in interface lifecycle from native side-row geometry.
	 */
	public void resumeForGameState() {
		restoreOverlays();
		gameStateSuspended = false;
		nativeLayoutRunning = false;
		pluginTwoRows = false;
		staticRowOwned = false;
		movableRowOwned = false;
		canvasResizeInProgress = false;
		splitSide = CollisionManager.Side.NONE;
		verticalSplit = VerticalSplit.UP;
		collisionSource = CollisionManager.Source.NONE;
		clearOneRow();
	}

	/*
	 * Suspends row-policy decisions while native interface layout is running.
	 */
	public void beginNativeLayout() {
		if (gameStateSuspended) {
			return;
		}

		if (!isModernLayout()) {
			reset();
			return;
		}

		final Widgets widgets = getWidgets();
		if (widgets != null && pluginTwoRows) {
			reconcileRowDrags(widgets);
		}

		if (pluginTwoRows && oneRowKnown) {
			canvasRebasePending = rebaseCanvasRows();
		}

		if (!pluginTwoRows && widgets != null) {
			if (canCaptureRow(widgets)) {
				captureOneRow(widgets);
			} else {
				clearOneRow();
			}
		}

		nativeLayoutRunning = true;
	}

	/*
	 * Reconciles captured side-row geometry after native interface layout completes.
	 */
	public Result endNativeLayout() {
		if (gameStateSuspended) {
			return Result.NONE;
		}

		nativeLayoutRunning = false;

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			canvasRebasePending = false;
			return Result.NONE;
		}

		if (canvasRebasePending && pluginTwoRows) {
			captureRowOriginals(widgets);
		}
		canvasRebasePending = false;

		if (pluginTwoRows) {
			reconcileRowDrags(widgets);
			return pluginTwoRows
					? reassertOwnedRows(widgets, splitSide, collisionSource)
					: Result.NONE;
		}

		if (canCaptureRow(widgets)) {
			captureOneRow(widgets);
		} else {
			clearOneRow();
		}

		return Result.NONE;
	}

	/*
	 * Reapplies owned two-row geometry without reevaluating collisions.
	 */
	public Result reassertOwnedLayout() {
		if (gameStateSuspended || nativeLayoutRunning || !pluginTwoRows || splitSide == CollisionManager.Side.NONE) {
			return Result.NONE;
		}

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			return Result.NONE;
		}

		reconcileRowDrags(widgets);
		if (!pluginTwoRows) {
			return Result.NONE;
		}

		if (rebaseCanvasRows()) {
			captureRowOriginals(widgets);
		}

		return reassertOwnedRows(widgets, splitSide, collisionSource);
	}

	/*
	 * Reapplies owned row geometry before rendering without reevaluating collisions.
	 */
	public Result reconcileBeforeRender() {
		if (gameStateSuspended || nativeLayoutRunning || !pluginTwoRows || splitSide == CollisionManager.Side.NONE) {
			return Result.NONE;
		}

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			return Result.NONE;
		}

		reconcileRowDrags(widgets);
		if (!pluginTwoRows) {
			return Result.NONE;
		}

		if (rebaseCanvasRows()) {
			captureRowOriginals(widgets);
		}

		return reassertOwnedRows(widgets, splitSide, collisionSource);
	}

	/*
	 * Reattaches the inventory to the currently owned row layout after an overlay reset.
	 */
	public Result reconcileInventoryReset() {
		if (gameStateSuspended || nativeLayoutRunning || !pluginTwoRows || !inventoryOffsetOwned()
				|| !inventoryUserOverride || splitSide == CollisionManager.Side.NONE) {
			return Result.NONE;
		}

		final boolean guardedReset = adoptInventoryGuard();
		if (!guardedReset && hasManualPlacement(INVENTORY_OVERLAY)) {
			return Result.NONE;
		}

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			return Result.NONE;
		}

		if (!guardedReset && inventoryAttachment(InterfaceBounds.liveBounds(widgets.container),
				staticRowBounds, movableRowBounds) != rowInventoryAttachment) {
			return Result.NONE;
		}

		inventoryUserOverride = false;
		return reassertOwnedRows(widgets, splitSide, collisionSource);
	}

	/*
	 * Preserves the owned inventory position during an overlay reset.
	 */
	public boolean guardInventoryOverlayReset() {
		final OverlayState state = inventoryOverlayState;
		final Point target = inventoryOwnedTarget;
		if (!pluginTwoRows || !inventoryOffsetOwned() || !inventoryUserOverride || state == null || target == null
				|| state.overlay.getPreferredLocation() != null || state.overlay.getPreferredPosition() != null) {
			return false;
		}

		final Point guarded = new Point(target);
		state.overlay.setPreferredPosition(null);
		state.overlay.setPreferredLocation(guarded);
		state.overlay.getBounds().setLocation(guarded);
		inventoryGuardLocation = guarded;
		return true;
	}

	/*
	 * Translates captured side-row geometry to the current canvas.
	 */
	public Result onCanvasSizeChanged() {
		canvasResizeInProgress = true;
		if (gameStateSuspended || !isModernLayout() || !oneRowKnown) {
			return Result.NONE;
		}

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			return Result.NONE;
		}

		if (pluginTwoRows) {
			reconcileRowDrags(widgets);
		}

		if (!rebaseCanvasRows()) {
			return pluginTwoRows
					? reassertOwnedRows(widgets, splitSide, collisionSource)
					: Result.NONE;
		}

		if (pluginTwoRows && splitSide != CollisionManager.Side.NONE) {
			return reassertOwnedRows(widgets, splitSide, collisionSource);
		}

		if (hasManualRows()) {
			return Result.NONE;
		}

		return reassertOneRow(widgets);
	}

	/*
	 * Ends the canvas-resize hold so normal row policy can resume.
	 */
	public void endCanvasResize() {
		canvasResizeInProgress = false;
	}

	/**
	 * ================================================================
	 * ROW POLICY
	 * ================================================================
	 */
	public Result apply(Widget slot, int configuredWidth, int configuredHeight) {
		if (gameStateSuspended || nativeLayoutRunning) {
			return Result.NONE;
		}

		if (slot == null || !isModernLayout()) {
			reset();
			return Result.NONE;
		}

		final Rectangle chatboxBounds = desiredBounds(slot, configuredWidth, configuredHeight);
		if (chatboxBounds == null || chatboxBounds.isEmpty()) {
			return Result.NONE;
		}

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			return Result.NONE;
		}

		if (pluginTwoRows && rebaseCanvasRows()) {
			captureRowOriginals(widgets);
		}

		if (pluginTwoRows && reconcileRowDrags(widgets)) {
			return pluginTwoRows
					? reassertOwnedRows(widgets, splitSide, collisionSource)
					: Result.NONE;
		}

		if (canvasResizeInProgress) {
			if (pluginTwoRows && splitSide != CollisionManager.Side.NONE) {
				return reassertOwnedRows(widgets, splitSide, collisionSource);
			}

			if (oneRowKnown && !hasManualRows()) {
				return reassertOneRow(widgets);
			}

			return Result.NONE;
		}

		if (pluginTwoRows && hasPartialOwnership()) {
			return reassertOwnedRows(widgets, splitSide, collisionSource);
		}

		if (!pluginTwoRows) {
			if (!canCaptureRow(widgets)) {
				clearOneRow();
				return Result.NONE;
			}

			captureOneRow(widgets);
		}

		if (!oneRowKnown || staticRowBounds == null || movableRowBounds == null) {
			return Result.NONE;
		}

		final CollisionManager.Result collision =
				collisionManager.resolveSideRows(chatboxBounds, staticRowBounds, movableRowBounds, splitSide);

		if (!collision.needsTwoRows()) {
			return pluginTwoRows
					? applyOneRow(widgets)
					: Result.NONE;
		}

		return applyTwoRows(widgets, collision.getSide(), collision.getSource());
	}

	public Result restoreNative() {
		final Widgets widgets = getWidgets();
		if (pluginTwoRows && widgets != null) {
			reconcileRowDrags(widgets);
		}

		Result result = Result.NONE;
		if (pluginTwoRows && hasFullOwnership() && oneRowKnown && widgets != null) {
			result = applyOneRow(widgets);
		} else {
			final int overlayMutations = restoreOverlays();
			if (overlayMutations > 0) {
				result = new Result(overlayMutations, 0);
			}
		}

		clearPluginState();
		return result;
	}

	public void reset() {
		restoreNative();
	}

	private void clearPluginState() {
		nativeLayoutRunning = false;
		canvasResizeInProgress = false;
		canvasRebasePending = false;
		pluginTwoRows = false;
		staticRowOwned = false;
		movableRowOwned = false;
		splitSide = CollisionManager.Side.NONE;
		verticalSplit = VerticalSplit.UP;
		collisionSource = CollisionManager.Source.NONE;
		inventoryOwnedTarget = null;
		inventoryGuardLocation = null;
		clearOneRow();
	}

	private boolean reconcileRowDrags(Widgets widgets) {
		if (!pluginTwoRows || widgets == null) {
			return false;
		}

		final boolean staticUserDrag = staticRowOwned && hasUserDrag(staticOverlayState);
		final boolean movableUserDrag = movableRowOwned && hasUserDrag(movableOverlayState);
		if (!staticUserDrag && !movableUserDrag) {
			return false;
		}

		if (staticUserDrag) {
			releaseOverlay(true, true);
			staticRowOwned = false;
		}

		if (movableUserDrag) {
			releaseOverlay(false, true);
			movableRowOwned = false;
		}

		if (!inventoryOffsetOwned()) {
			restoreInventoryOverlay();
			inventoryOwnedTarget = null;
			inventoryGuardLocation = null;
		}

		if (!staticRowOwned && !movableRowOwned) {
			pluginTwoRows = false;
			splitSide = CollisionManager.Side.NONE;
			collisionSource = CollisionManager.Source.NONE;
			canvasRebasePending = false;
			clearOneRow();
		}

		return true;
	}

	private boolean hasFullOwnership() {
		return pluginTwoRows && staticRowOwned && movableRowOwned;
	}

	private boolean hasPartialOwnership() {
		return pluginTwoRows && staticRowOwned != movableRowOwned;
	}

	private static boolean hasUserDrag(OverlayState state) {
		if (state == null || state.temporaryLocation == null) {
			return false;
		}

		return !samePoint(state.overlay.getPreferredLocation(), state.temporaryLocation)
				|| state.overlay.getPreferredPosition() != null;
	}

	/**
	 * ================================================================
	 * ROW LAYOUT
	 * ================================================================
	 */
	private Result applyTwoRows(Widgets widgets, CollisionManager.Side side, CollisionManager.Source source) {
		if (!oneRowKnown || staticRowBounds == null || movableRowBounds == null || side == null || side == CollisionManager.Side.NONE) {
			return Result.NONE;
		}

		staticRowOwned = true;
		movableRowOwned = true;
		pluginTwoRows = true;
		final int rowHeight = Math.max(1, Math.max(staticRowBounds.height, movableRowBounds.height));
		verticalSplit = chooseVerticalSplit(rowHeight);
		splitSide = side;
		collisionSource = source != null
				? source
				: CollisionManager.Source.NONE;
		return reassertOwnedRows(widgets, splitSide, collisionSource);
	}

	private Result reassertOwnedRows(Widgets widgets, CollisionManager.Side side, CollisionManager.Source source) {
		if (!pluginTwoRows || !oneRowKnown || staticRowBounds == null || movableRowBounds == null
				|| side == null || side == CollisionManager.Side.NONE) {
			return Result.NONE;
		}

		final int rowHeight = Math.max(1, Math.max(staticRowBounds.height, movableRowBounds.height));
		final int leftX = Math.min(staticRowBounds.x, movableRowBounds.x);
		final int rightX = Math.max(staticRowBounds.x, movableRowBounds.x);
		final int anchorX = side == CollisionManager.Side.RIGHT
				? leftX
				: rightX;
		final int upperRowY;
		final int lowerRowY;
		if (verticalSplit == VerticalSplit.DOWN) {
			upperRowY = movableRowBounds.y;
			lowerRowY = upperRowY + rowHeight;
		} else {
			lowerRowY = staticRowBounds.y;
			upperRowY = lowerRowY - rowHeight;
		}
		int mutations = 0;
		int revalidates = 0;

		/*
		 * TABS2 is always the upper row and TABS1 is always the lower row.
		 */
		if (movableRowOwned) {
			if (applyOverlay(MOVABLE_TABS_OVERLAY, widgets.movableLayer, anchorX, upperRowY, false)) {
				mutations++;
			} else if (forceWidgetPosition(widgets.movableLayer, anchorX, upperRowY)) {
				mutations++;
				widgets.movableLayer.revalidate();
				revalidates++;
			}
		}

		if (staticRowOwned) {
			if (applyOverlay(STATIC_TABS_OVERLAY, widgets.staticLayer, anchorX, lowerRowY, true)) {
				mutations++;
			} else if (forceWidgetPosition(widgets.staticLayer, anchorX, lowerRowY)) {
				mutations++;
				widgets.staticLayer.revalidate();
				revalidates++;
			}
		}

		if (adoptInventoryGuard()) {
			inventoryUserOverride = false;
		}

		final boolean inventoryDragDetected = hasInventoryDrag();
		if (inventoryDragDetected && !inventoryUserOverride) {
			if (inventoryOverlayState != null) {
				inventoryOverlayState.temporaryLocation = null;
			}
			inventoryForcedPosition = false;
			inventoryUserOverride = true;
			widgets.container.setForcedPosition(-1, -1);
			widgets.container.revalidate();
			mutations++;
			revalidates++;
		}

		if (!inventoryDragDetected && inventoryUserOverride && !hasManualPlacement(INVENTORY_OVERLAY)
				&& inventoryAttachment(InterfaceBounds.liveBounds(widgets.container),
				staticRowBounds, movableRowBounds) == rowInventoryAttachment) {
			inventoryUserOverride = false;
		}

		Rectangle targetContainerBounds = null;
		if (inventoryOffsetOwned() && containerRowBounds != null) {
			final int inventoryY = rowInventoryAttachment == InventoryAttachment.BELOW
					? containerRowBounds.y + rowHeight
					: containerRowBounds.y - rowHeight;
			final Rectangle ownedContainerBounds = new Rectangle(
					containerRowBounds.x,
					inventoryY,
					containerRowBounds.width,
					containerRowBounds.height);
			inventoryOwnedTarget = new Point(ownedContainerBounds.x, ownedContainerBounds.y);
			if (!inventoryUserOverride) {
				targetContainerBounds = ownedContainerBounds;
				if (applyInventoryPosition(widgets.container, ownedContainerBounds.x, ownedContainerBounds.y)) {
					mutations++;
				}
			}
		} else {
			inventoryOwnedTarget = null;
			inventoryGuardLocation = null;
		}

		splitSide = side;
		collisionSource = source != null
				? source
				: CollisionManager.Source.NONE;

		final Rectangle staticOverride = staticRowOwned
				? new Rectangle(anchorX, lowerRowY, staticRowBounds.width, staticRowBounds.height)
				: null;
		final Rectangle movableOverride = movableRowOwned
				? new Rectangle(anchorX, upperRowY, movableRowBounds.width, movableRowBounds.height)
				: null;
		final InterfaceBounds.Overrides overrides = new InterfaceBounds.Overrides(
				staticOverride, movableOverride, targetContainerBounds);
		return new Result(mutations, revalidates, overrides);
	}

	private Result applyOneRow(Widgets widgets) {
		int mutations = restoreOverlays();
		int revalidates = 0;

		boolean staticChanged = false;
		if (widgets.staticLayer.getOriginalX() != staticRowX || widgets.staticLayer.getOriginalY() != staticRowY) {
			widgets.staticLayer.setOriginalX(staticRowX);
			widgets.staticLayer.setOriginalY(staticRowY);
			mutations++;
			staticChanged = true;
		}

		if (staticRowBounds != null && forceWidgetPosition(widgets.staticLayer, staticRowBounds.x, staticRowBounds.y)) {
			mutations++;
			staticChanged = true;
		}

		if (staticChanged) {
			widgets.staticLayer.revalidate();
			revalidates++;
		}

		boolean movableChanged = false;
		if (widgets.movableLayer.getOriginalX() != movableRowX || widgets.movableLayer.getOriginalY() != movableRowY) {
			widgets.movableLayer.setOriginalX(movableRowX);
			widgets.movableLayer.setOriginalY(movableRowY);
			mutations++;
			movableChanged = true;
		}

		if (movableRowBounds != null && forceWidgetPosition(widgets.movableLayer, movableRowBounds.x, movableRowBounds.y)) {
			mutations++;
			movableChanged = true;
		}

		if (movableChanged) {
			widgets.movableLayer.revalidate();
			revalidates++;
		}

		inventoryUserOverride = false;
		inventoryOwnedTarget = null;
		inventoryGuardLocation = null;
		pluginTwoRows = false;
		staticRowOwned = false;
		movableRowOwned = false;
		splitSide = CollisionManager.Side.NONE;
		verticalSplit = VerticalSplit.UP;
		collisionSource = CollisionManager.Source.NONE;

		final InterfaceBounds.Overrides overrides = new InterfaceBounds.Overrides(staticRowBounds, movableRowBounds, containerRowBounds);
		return new Result(mutations, revalidates, overrides);
	}

	private void captureOneRow(Widgets widgets) {
		if (pluginTwoRows) {
			return;
		}

		final Rectangle staticBounds = rowBounds(widgets.staticLayer, STATIC_TABS_OVERLAY);
		final Rectangle movableBounds = rowBounds(widgets.movableLayer, MOVABLE_TABS_OVERLAY);
		final Rectangle containerBounds = InterfaceBounds.liveBounds(widgets.container);
		if (!isOneRow(staticBounds, movableBounds)) {
			clearOneRow();
			return;
		}

		captureRowOriginals(widgets);
		staticRowBounds = new Rectangle(staticBounds);
		movableRowBounds = new Rectangle(movableBounds);
		containerRowBounds = containerBounds != null && !containerBounds.isEmpty()
				? new Rectangle(containerBounds)
				: null;
		rowInventoryAttachment = inventoryAttachment(containerRowBounds, staticRowBounds, movableRowBounds);
		rowCanvasWidth = Math.max(0, client.getCanvasWidth());
		rowCanvasHeight = Math.max(0, client.getCanvasHeight());
		canvasRebasePending = false;
		oneRowKnown = true;
	}

	private void clearOneRow() {
		oneRowKnown = false;
		staticRowBounds = null;
		movableRowBounds = null;
		containerRowBounds = null;
		rowInventoryAttachment = InventoryAttachment.NONE;
		verticalSplit = VerticalSplit.UP;
		rowCanvasWidth = 0;
		rowCanvasHeight = 0;
		canvasRebasePending = false;
		inventoryUserOverride = false;
		inventoryOwnedTarget = null;
		inventoryGuardLocation = null;
	}

	private void captureRowOriginals(Widgets widgets) {
		if (widgets == null) {
			return;
		}

		staticRowX = widgets.staticLayer.getOriginalX();
		staticRowY = widgets.staticLayer.getOriginalY();
		movableRowX = widgets.movableLayer.getOriginalX();
		movableRowY = widgets.movableLayer.getOriginalY();
	}

	private boolean rebaseCanvasRows() {
		if (!oneRowKnown || staticRowBounds == null || movableRowBounds == null) {
			return false;
		}

		final int canvasWidth = Math.max(0, client.getCanvasWidth());
		final int canvasHeight = Math.max(0, client.getCanvasHeight());
		if (canvasWidth <= 0 || canvasHeight <= 0) {
			return false;
		}

		if (rowCanvasWidth <= 0 || rowCanvasHeight <= 0) {
			rowCanvasWidth = canvasWidth;
			rowCanvasHeight = canvasHeight;
			return false;
		}

		final int deltaX = canvasWidth - rowCanvasWidth;
		final int deltaY = canvasHeight - rowCanvasHeight;
		if (deltaX == 0 && deltaY == 0) {
			return false;
		}

		staticRowBounds.translate(deltaX, deltaY);
		movableRowBounds.translate(deltaX, deltaY);
		if (containerRowBounds != null) {
			containerRowBounds.translate(deltaX, deltaY);
		}

		translateOverlayState(staticOverlayState, deltaX, deltaY);
		translateOverlayState(movableOverlayState, deltaX, deltaY);
		translateOverlayState(inventoryOverlayState, deltaX, deltaY);

		rowCanvasWidth = canvasWidth;
		rowCanvasHeight = canvasHeight;
		return true;
	}

	private static void translateOverlayState(OverlayState state, int deltaX, int deltaY) {
		if (state == null) {
			return;
		}

		state.originalBounds.translate(deltaX, deltaY);
	}

	private boolean canCaptureRow(Widgets widgets) {
		if (widgets == null) {
			return false;
		}

		return isOneRow(rowBounds(widgets.staticLayer, STATIC_TABS_OVERLAY), rowBounds(widgets.movableLayer, MOVABLE_TABS_OVERLAY));
	}

	private Rectangle rowBounds(Widget widget, String overlayName) {
		if (widget == null) {
			return null;
		}

		if (overlayManager != null) {
			final Overlay overlay = findOverlay(overlayName);
			if (overlay != null && (overlay.getPreferredLocation() != null || overlay.getPreferredPosition() != null)) {
				final Rectangle overlayBounds = overlay.getBounds();
				if (overlayBounds != null && !overlayBounds.isEmpty()) {
					return new Rectangle(overlayBounds);
				}
			}
		}

		return InterfaceBounds.liveBounds(widget);
	}

	private boolean hasManualRows() {
		return hasManualPlacement(STATIC_TABS_OVERLAY) || hasManualPlacement(MOVABLE_TABS_OVERLAY);
	}

	private boolean hasManualPlacement(String overlayName) {
		if (overlayManager == null) {
			return false;
		}

		final Overlay overlay = findOverlay(overlayName);
		return overlay != null && (overlay.getPreferredLocation() != null || overlay.getPreferredPosition() != null);
	}

	/**
	 * ================================================================
	 * OVERLAY MANAGEMENT
	 * ================================================================
	 */
	private boolean applyInventoryPosition(Widget widget, int targetX, int targetY) {
		if (widget == null) {
			return false;
		}

		final Overlay overlay = overlayManager != null
				? findOverlay(INVENTORY_OVERLAY)
				: null;
		if (overlay != null) {
			if (inventoryOverlayState != null && inventoryOverlayState.overlay != overlay) {
				restoreInventoryOverlay();
			}

			if (inventoryOverlayState == null) {
				final Rectangle currentBounds = InterfaceBounds.liveBounds(widget);
				if (currentBounds == null || currentBounds.isEmpty()) {
					return false;
				}

				inventoryOverlayState = new OverlayState(overlay, widget, copy(overlay.getPreferredLocation()),
						overlay.getPreferredPosition(), new Rectangle(currentBounds));
			}

			return applyOverlayState(inventoryOverlayState, targetX, targetY);
		}

		if (!forceWidgetPosition(widget, targetX, targetY)) {
			return false;
		}

		inventoryForcedPosition = true;
		return true;
	}

	private Result reassertOneRow(Widgets widgets) {
		if (!oneRowKnown || staticRowBounds == null || movableRowBounds == null) {
			return Result.NONE;
		}

		int mutations = 0;
		int revalidates = 0;
		if (forceWidgetPosition(widgets.staticLayer, staticRowBounds.x, staticRowBounds.y)) {
			mutations++;
			widgets.staticLayer.revalidate();
			revalidates++;
		}

		if (forceWidgetPosition(widgets.movableLayer, movableRowBounds.x, movableRowBounds.y)) {
			mutations++;
			widgets.movableLayer.revalidate();
			revalidates++;
		}

		if (rowInventoryAttachment != InventoryAttachment.NONE && containerRowBounds != null
				&& forceWidgetPosition(widgets.container, containerRowBounds.x, containerRowBounds.y)) {
			mutations++;
			widgets.container.revalidate();
			revalidates++;
		}

		final InterfaceBounds.Overrides overrides = new InterfaceBounds.Overrides(staticRowBounds, movableRowBounds, containerRowBounds);
		return new Result(mutations, revalidates, overrides);
	}

	private void releaseOverlay(boolean staticOverlay, boolean userDragged) {
		final OverlayState state = staticOverlay
				? staticOverlayState
				: movableOverlayState;
		if (state == null) {
			return;
		}

		if (!userDragged) {
			restoreOverlay(staticOverlay);
			state.widget.revalidate();
			return;
		}

		if (staticOverlay) {
			staticOverlayState = null;
		} else {
			movableOverlayState = null;
		}

		final Rectangle overlayBounds = state.overlay.getBounds();
		if (overlayBounds != null && !overlayBounds.isEmpty() && forceWidgetPosition(state.widget, overlayBounds.x, overlayBounds.y)) {
			state.widget.revalidate();
		}
	}

	private boolean applyOverlayState(OverlayState state, int targetX, int targetY) {
		final int deltaX = targetX - state.originalBounds.x;
		final int deltaY = targetY - state.originalBounds.y;
		final Point temporaryLocation = state.preferredLocation != null
				? new Point(state.preferredLocation.x + deltaX, state.preferredLocation.y + deltaY)
				: new Point(targetX, targetY);

		final boolean alreadyApplied = samePoint(state.overlay.getPreferredLocation(), temporaryLocation)
				&& state.overlay.getPreferredPosition() == null && state.overlay.getBounds().x == targetX
				&& state.overlay.getBounds().y == targetY;
		state.temporaryLocation = new Point(temporaryLocation);
		if (alreadyApplied) {
			forceWidgetPosition(state.widget, targetX, targetY);
			return false;
		}

		state.overlay.setPreferredPosition(null);
		state.overlay.setPreferredLocation(temporaryLocation);
		state.overlay.getBounds().setLocation(targetX, targetY);
		forceWidgetPosition(state.widget, targetX, targetY);
		inventoryForcedPosition = true;
		return true;
	}

	private boolean hasInventoryDrag() {
		final OverlayState state = inventoryOverlayState;
		if (state == null) {
			return false;
		}

		if (state.temporaryLocation != null) {
			return hasUserDrag(state);
		}

		return !samePoint(state.overlay.getPreferredLocation(), state.preferredLocation)
				|| state.overlay.getPreferredPosition() != state.preferredPosition;
	}

	private boolean adoptInventoryGuard() {
		final Point guarded = inventoryGuardLocation;
		final OverlayState state = inventoryOverlayState;
		if (guarded == null || state == null || !samePoint(state.overlay.getPreferredLocation(), guarded)
				|| state.overlay.getPreferredPosition() != null) {
			return false;
		}

		state.temporaryLocation = new Point(guarded);
		inventoryGuardLocation = null;
		return true;
	}

	private boolean applyOverlay(String overlayName, Widget widget, int targetX, int targetY, boolean staticOverlay) {
		if (overlayManager == null || widget == null) {
			return false;
		}

		final Overlay overlay = findOverlay(overlayName);
		if (overlay == null) {
			return false;
		}

		OverlayState state = staticOverlay
				? staticOverlayState
				: movableOverlayState;
		if (state != null && state.overlay != overlay) {
			restoreOverlay(staticOverlay);
			state = null;
		}

		if (state == null) {
			final Rectangle currentBounds = InterfaceBounds.liveBounds(widget);
			if (currentBounds == null || currentBounds.isEmpty()) {
				return false;
			}

			final Point preferredLocation = copy(overlay.getPreferredLocation());
			final OverlayPosition preferredPosition = overlay.getPreferredPosition();

			state = new OverlayState(overlay, widget, preferredLocation, preferredPosition, new Rectangle(currentBounds));

			if (staticOverlay) {
				staticOverlayState = state;
			} else {
				movableOverlayState = state;
			}
		}

		/*
		 * Calculates temporary overlay offsets from the captured one-row bounds.
		 */
		final int deltaX = targetX - state.originalBounds.x;
		final int deltaY = targetY - state.originalBounds.y;
		final Point temporaryLocation = state.preferredLocation != null
				? new Point(state.preferredLocation.x + deltaX, state.preferredLocation.y + deltaY)
				: new Point(targetX, targetY);

		final boolean alreadyApplied = samePoint(state.overlay.getPreferredLocation(), temporaryLocation)
				&& state.overlay.getPreferredPosition() == null && state.overlay.getBounds().x == targetX
				&& state.overlay.getBounds().y == targetY;
		state.temporaryLocation = new Point(temporaryLocation);
		if (alreadyApplied) {
			forceWidgetPosition(widget, targetX, targetY);
			return false;
		}

		state.overlay.setPreferredPosition(null);
		state.overlay.setPreferredLocation(temporaryLocation);
		state.overlay.getBounds().setLocation(targetX, targetY);
		forceWidgetPosition(widget, targetX, targetY);
		return true;
	}

	private int restoreOverlays() {
		int mutations = 0;
		if (restoreInventoryOverlay()) {
			mutations++;
		}

		if (restoreOverlay(false)) {
			mutations++;
		}

		if (restoreOverlay(true)) {
			mutations++;
		}

		return mutations;
	}

	private boolean restoreInventoryOverlay() {
		final OverlayState state = inventoryOverlayState;
		inventoryOverlayState = null;

		if (state == null) {
			if (!inventoryForcedPosition) {
				return false;
			}

			final Widgets widgets = getWidgets();
			inventoryForcedPosition = false;
			if (widgets == null) {
				return false;
			}

			widgets.container.setForcedPosition(-1, -1);
			widgets.container.revalidate();
			return true;
		}

		if (state.temporaryLocation != null) {
			final Point currentLocation = state.overlay.getPreferredLocation();
			final boolean temporaryStillOwned = samePoint(currentLocation, state.temporaryLocation) && state.overlay.getPreferredPosition() == null;
			if (!temporaryStillOwned) {
				inventoryForcedPosition = false;
				return false;
			}

			state.overlay.setPreferredLocation(copy(state.preferredLocation));
			state.overlay.setPreferredPosition(state.preferredPosition);
			state.overlay.getBounds().setBounds(state.originalBounds);
			forceWidgetPosition(state.widget, state.originalBounds.x, state.originalBounds.y);
			inventoryForcedPosition = false;
			return true;
		}

		final boolean originalOverlayState = samePoint(state.overlay.getPreferredLocation(), state.preferredLocation)
				&& state.overlay.getPreferredPosition() == state.preferredPosition;
		if (!originalOverlayState) {
			inventoryForcedPosition = false;
			return false;
		}

		state.widget.setForcedPosition(-1, -1);
		state.widget.revalidate();
		state.overlay.getBounds().setBounds(state.originalBounds);
		inventoryForcedPosition = false;
		return true;
	}

	private boolean restoreOverlay(boolean staticOverlay) {
		final OverlayState state = staticOverlay
				? staticOverlayState
				: movableOverlayState;
		if (state == null) {
			return false;
		}

		if (staticOverlay) {
			staticOverlayState = null;
		} else {
			movableOverlayState = null;
		}

		/*
		 * Preserves user placement when ChatXL no longer owns the temporary position.
		 */
		final Point currentLocation = state.overlay.getPreferredLocation();
		final boolean temporaryStillOwned = samePoint(currentLocation, state.temporaryLocation) && state.overlay.getPreferredPosition() == null;
		if (!temporaryStillOwned) {
			return false;
		}

		state.overlay.setPreferredLocation(copy(state.preferredLocation));
		state.overlay.setPreferredPosition(state.preferredPosition);
		state.overlay.getBounds().setBounds(state.originalBounds);
		forceWidgetPosition(state.widget, state.originalBounds.x, state.originalBounds.y);
		return true;
	}

	private boolean forceWidgetPosition(Widget widget, int x, int y) {
		if (widget == null) {
			return false;
		}

		final Rectangle current = InterfaceBounds.liveBounds(widget);
		if (current != null && current.x == x && current.y == y) {
			return false;
		}

		final Widget parent = widget.getParent();
		final Rectangle parentBounds = parent != null ? parent.getBounds() : null;
		if (parentBounds != null) {
			widget.setForcedPosition(x - parentBounds.x, y - parentBounds.y);
		} else {
			widget.setForcedPosition(x, y);
		}

		return true;
	}

	private Overlay findOverlay(String name) {
		final Overlay[] match = new Overlay[1];
		overlayManager.anyMatch(overlay -> {
			if (name.equals(overlay.getName())) {
				match[0] = overlay;
				return true;
			}

			return false;
		});
		return match[0];
	}

	/**
	 * ================================================================
	 * GEOMETRY HELPERS
	 * ================================================================
	 */
	private Rectangle desiredBounds(Widget slot, int configuredWidth, int configuredHeight) {
		final Rectangle slotBounds = InterfaceBounds.liveBounds(slot);
		if (slotBounds == null) {
			return null;
		}

		/*
		 * Uses the live host position with configured dimensions; ChatboxBounds applies temporary constraints.
		 */
		return new Rectangle(slotBounds.x, slotBounds.y, Math.max(1, configuredWidth), Math.max(1, configuredHeight));
	}

	private Widgets getWidgets() {
		if (!isModernLayout()) {
			return null;
		}

		final Widget staticLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER);
		final Widget movableLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER);
		final Widget container = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);
		if (staticLayer == null || movableLayer == null || container == null || staticLayer.isHidden() || movableLayer.isHidden()) {
			return null;
		}

		return new Widgets(staticLayer, movableLayer, container);
	}

	private boolean isModernLayout() {
		return client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_PRE_EOC;
	}

	private static boolean isOneRow(Rectangle staticBounds, Rectangle movableBounds) {
		if (staticBounds == null || movableBounds == null || staticBounds.isEmpty() || movableBounds.isEmpty()) {
			return false;
		}

		final int overlapLeft = Math.max(staticBounds.x, movableBounds.x);
		final int overlapRight = Math.min(staticBounds.x + staticBounds.width, movableBounds.x + movableBounds.width);
		final int horizontalOverlap = Math.max(0, overlapRight - overlapLeft);
		if (horizontalOverlap < MIN_ONE_ROW_OVERLAP || horizontalOverlap > MAX_ONE_ROW_OVERLAP) {
			return false;
		}

		final int rowHeight = Math.max(1, Math.min(staticBounds.height, movableBounds.height));
		return Math.abs(staticBounds.y - movableBounds.y) < Math.max(1, rowHeight / 2);
	}

	private static InventoryAttachment inventoryAttachment(Rectangle containerBounds, Rectangle staticBounds, Rectangle movableBounds) {
		if (containerBounds == null || staticBounds == null || movableBounds == null
				|| containerBounds.isEmpty() || staticBounds.isEmpty() || movableBounds.isEmpty()) {
			return InventoryAttachment.NONE;
		}

		final Rectangle stripBounds = new Rectangle(staticBounds);
		stripBounds.add(movableBounds);
		final int containerRight = containerBounds.x + containerBounds.width;
		final int stripRight = stripBounds.x + stripBounds.width;
		final boolean horizontallyNear = containerBounds.x <= stripRight + INVENTORY_HORIZONTAL_TOLERANCE
				&& containerRight >= stripBounds.x - INVENTORY_HORIZONTAL_TOLERANCE;
		if (!horizontallyNear) {
			return InventoryAttachment.NONE;
		}

		final int rowHeight = Math.max(1, Math.max(staticBounds.height, movableBounds.height));
		final int containerBottom = containerBounds.y + containerBounds.height;
		final int stripBottom = stripBounds.y + stripBounds.height;
		final int aboveGap = stripBounds.y - containerBottom;
		final int belowGap = containerBounds.y - stripBottom;
		final boolean above = aboveGap >= -INVENTORY_EDGE_TOLERANCE && aboveGap <= rowHeight;
		final boolean below = belowGap >= -INVENTORY_EDGE_TOLERANCE && belowGap <= rowHeight;

		if (above && below) {
			return Math.abs(aboveGap) <= Math.abs(belowGap)
					? InventoryAttachment.ABOVE
					: InventoryAttachment.BELOW;
		}

		if (above) {
			return InventoryAttachment.ABOVE;
		}

		return below
				? InventoryAttachment.BELOW
				: InventoryAttachment.NONE;
	}

	private boolean inventoryOffsetOwned() {
		return rowInventoryAttachment == InventoryAttachment.ABOVE && verticalSplit == VerticalSplit.UP && movableRowOwned
				|| rowInventoryAttachment == InventoryAttachment.BELOW && verticalSplit == VerticalSplit.DOWN && staticRowOwned;
	}

	private VerticalSplit chooseVerticalSplit(int rowHeight) {
		final int canvasHeight = Math.max(0, client.getCanvasHeight());
		final int upperCandidateY = staticRowBounds.y - rowHeight;
		final int lowerCandidateY = movableRowBounds.y + rowHeight;
		final int lowerCandidateBottom = lowerCandidateY + staticRowBounds.height;
		final boolean upFits = upperCandidateY >= 0;
		final boolean downFits = canvasHeight <= 0 || lowerCandidateBottom <= canvasHeight;

		if (rowInventoryAttachment == InventoryAttachment.ABOVE) {
			if (upFits || !downFits) {
				return VerticalSplit.UP;
			}
			return VerticalSplit.DOWN;
		}

		if (rowInventoryAttachment == InventoryAttachment.BELOW) {
			if (downFits || !upFits) {
				return VerticalSplit.DOWN;
			}
			return VerticalSplit.UP;
		}

		if (upFits) {
			return VerticalSplit.UP;
		}

		if (downFits) {
			return VerticalSplit.DOWN;
		}

		final int rowTop = Math.min(staticRowBounds.y, movableRowBounds.y);
		final int rowBottom = Math.max(staticRowBounds.y + staticRowBounds.height, movableRowBounds.y + movableRowBounds.height);
		final int spaceAbove = Math.max(0, rowTop);
		final int spaceBelow = canvasHeight > 0
				? Math.max(0, canvasHeight - rowBottom)
				: 0;
		return spaceBelow > spaceAbove
				? VerticalSplit.DOWN
				: VerticalSplit.UP;
	}

	private static Point copy(Point point) {
		return point != null ? new Point(point) : null;
	}

	private static boolean samePoint(Point first, Point second) {
		return first == second || first != null && first.equals(second);
	}

	/**
	 * ================================================================
	 * STATE TYPES
	 * ================================================================
	 */
	private enum InventoryAttachment {
		NONE,
		ABOVE,
		BELOW
	}

	private enum VerticalSplit {
		UP,
		DOWN
	}

	private static final class Widgets {
		private final Widget staticLayer;
		private final Widget movableLayer;
		private final Widget container;

		private Widgets(Widget staticLayer, Widget movableLayer, Widget container) {
			this.staticLayer = staticLayer;
			this.movableLayer = movableLayer;
			this.container = container;
		}
	}

	private static final class OverlayState {
		private final Overlay overlay;
		private final Widget widget;
		private final Point preferredLocation;
		private final OverlayPosition preferredPosition;
		private final Rectangle originalBounds;
		private Point temporaryLocation;

		private OverlayState(Overlay overlay, Widget widget, Point preferredLocation, OverlayPosition preferredPosition, Rectangle originalBounds) {
			this.overlay = overlay;
			this.widget = widget;
			this.preferredLocation = preferredLocation;
			this.preferredPosition = preferredPosition;
			this.originalBounds = originalBounds;
		}
	}

	public static final class Result {
		private static final Result NONE = new Result(0, 0, null);

		private final int mutations;
		private final int revalidates;
		private final InterfaceBounds.Overrides interfaceOverrides;

		private Result(int mutations, int revalidates) {
			this(mutations, revalidates, null);
		}

		private Result(int mutations, int revalidates, InterfaceBounds.Overrides interfaceOverrides) {
			this.mutations = mutations;
			this.revalidates = revalidates;
			this.interfaceOverrides = interfaceOverrides;
		}

		public int getMutations() {
			return mutations;
		}

		public int getRevalidates() {
			return revalidates;
		}

		public InterfaceBounds.Overrides getInterfaceOverrides() {
			return interfaceOverrides;
		}
	}
}
