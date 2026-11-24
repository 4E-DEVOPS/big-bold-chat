package com.bigboldchat.dialogue;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.bigboldchat.dialogue.DialogueFitSolver.DialogueFitCandidate;
import com.bigboldchat.dialogue.DialogueFitSolver.DialogueFitSearch;
import com.bigboldchat.dialogue.DialogueFitSolver.DialogueFitSelection;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.debug.DialogueDiagnostics;
import com.bigboldchat.layout.ChatboxBounds;
import com.bigboldchat.layout.InterfaceBounds;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bigboldchat.chatbox.ChatboxCoreService;
import com.bigboldchat.chatbox.ChatboxGeometry;
import com.bigboldchat.chatbox.ChatboxPlacement;
import com.bigboldchat.chatbox.ChatboxLayout;

/**
 * Owns dialogue-specific lifecycle, content fit, drag classification, collision
 * adaptation and temporary placement/normal-chatbox restoration.
 * Native chatbox geometry, scripts and presentation belong to ChatboxCoreService.
 */
public final class DialogueCoreService extends ChatboxCoreService {
	private static final Logger LOG = LoggerFactory.getLogger(DialogueCoreService.class);

	private static final int DIALOGUE_REBUILD = 903;
	private static final int DIALOGUE_FIT_STEP = 4;

	private static final long ORTHOGONAL_FAILED_RETRY_NANOS = 150_000_000L;
	private static final int DIALOGUE_PARTIAL_GROWTH_LIMIT = 160;

	private static final int DRAG_PREVIEW_MIN_WIDTH = 200;
	private static final int DRAG_PREVIEW_MIN_HEIGHT = 100;

	private static final int NATIVE_DROP_SAVE_WAIT_FRAMES = 12;
	private static final int DIALOGUE_SETTLED_ALIGNMENT_WAIT_FRAMES = 8;

	private static final int NATIVE_SNAP_REFLOW_TOLERANCE = 2;
	/* Only a genuinely settled native SNAP may override a size-only fit's requested X/Y;
	 * transient 1-2px snap rounding must still converge. */
	private static final int SNAPPED_REFLOW_FALLBACK_WAIT_CYCLES = 24;
	private static final int SNAPPED_REFLOW_STABLE_SAMPLES = 3;

	private static final int DIALOGUE_FIT_AXIS_TOLERANCE_PERCENT = 10;

	private static final String STATIC_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS1";
	private static final String MOVABLE_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS2";

	private final DialoguePrompts dialoguePrompts;
	private final DialogueResizeService dialogueResizeService;
	private final DialogueViewport dialogueViewport;

	private DialogueDiagnostics dialogueDiagnostics = new DialogueDiagnostics();
	private BooleanSupplier chatboxDiagnosticsEnabled = () -> false;

	private int lastDialogueCanvasWidth = -1;
	private int lastDialogueCanvasHeight = -1;
	private boolean dialogueCanvasRebasePending;
	private Rectangle dialogueCanvasRebaseReference;
	private int dialogueCanvasRebaseOldWidth;
	private int dialogueCanvasRebaseOldHeight;

	private boolean dialogueFitActive;
	private boolean dialogueFitConstrainedWrap;
	private boolean dialogueFitClippedFallback;
	private boolean dialogueNativeAutoFit;

	private boolean dialogueClipFromTop;
	private boolean dialogueClipOriginObserved;
	private int dialogueClipContentFingerprint = Integer.MIN_VALUE;
	private boolean restoringClippedDialogueHost;
	private boolean dialogueFitRetryStrict;

	private boolean dialogueCollisionLiveReflow;
	private volatile boolean dialogueCollisionDragPending;
	private volatile boolean dialogueCollisionDropPending;
	private volatile boolean dialogueCollisionDragObserved;
	private int dialogueCollisionDropFrames;

	private boolean dialogueFitResolving;

	private boolean dialogueTransitionPending;
	private boolean dialogueTransitionReady;
	private boolean dialogueRelayoutDirty;

	/* A dialogue-surface/script rebuild may change the native host's size
	 * without moving its saved MANUAL X/Y. This is not a user drag. */
	private boolean dialoguePageRebuildPending;
	private int dialoguePageRebuildCycle = Integer.MIN_VALUE;

	private boolean dialogueUserDropPinned;
	private Rectangle dialogueConfirmedDropBounds;

	private final DialogueSession dialogueSession = new DialogueSession();

	/* A right-click/context reset can change RuneLite's native host without a
	 * CHATBOX_DRAG. It is not a new dialogue-center decision. Rebase only on
	 * the next physically confirmed user drop, never on the reset itself. */
	private boolean dialogueNativePlacementResetObserved;
	/* A verified RuneLite snap may supersede an older MANUAL fit without a
	 * confirmed dialogue drag. Never apply stale clipping during that handoff. */
	private Rectangle dialogueNativeSnapTransitionTarget;
	private int dialogueOtherOverlayResetCycle = Integer.MIN_VALUE;
	private boolean dialogueObstacleResetRecoveryPending;

	/* Emergency placement is session-local until native, widget, AND durable
	 * normal-center readbacks succeed. Never replace an existing valid center. */
	private boolean dialogueEmergencyFallbackCaptured;
	private int dialogueEmergencyRestoreWaitFrames;
	private int dialogueEmergencyRestoreLastCycle = Integer.MIN_VALUE;
	private int dialogueEmergencyAdoptWaitFrames;
	private int dialogueEmergencyAdoptLastCycle = Integer.MIN_VALUE;

	private boolean normalChatRestoredEvent;

	private long dialoguePinnedRejectedFitKey = Long.MIN_VALUE;

	private boolean dialogueDropSettlePending;
	private int dialogueDropSettleDelay;
	private int dialogueDropSettleAlignmentWaits;
	private Rectangle dialogueDropSample;

	private volatile Point dialogueMousePress;
	private volatile Rectangle dialogueLastHostHitBounds;
	/* Capture the native host on the client thread.
	 * A press inside the host is not evidence of ownership: any other movable
	 * overlay may occupy the same pixels. Only native movement may confirm it. */
	private volatile Rectangle dialogueLastNativeHostHitBounds;
	private volatile Rectangle dialoguePressHostBounds;
	private volatile Rectangle dialoguePressNativeHostBounds;
	private volatile boolean dialoguePressInsideHost;
	private volatile Point dialogueMouseDragPosition;
	private volatile int dialogueMousePressSerial;

	private int dialogueProcessedPressSerial;
	private int dialogueAdoptedPhysicalPressSerial = Integer.MIN_VALUE;
	private int dialogueClassifiedChatboxPressSerial = Integer.MIN_VALUE;

	private boolean dialogueHostDragActive;
	private boolean dialogueDragKindDecided;

	private boolean dialogueNativeSaveDuringDrag;
	private boolean dialogueAwaitingNativeSave;
	private int dialogueNativeSaveWaitFrames;
	private boolean dialogueUnconfirmedDropSuspended;

	private String dialogueInteraction = "UNKNOWN";
	private Rectangle dialogueHostDragStart;

	private boolean dialogueFitDeferredHostMismatch;
	private Rectangle dialogueDeferredExpectedHost;
	private Rectangle dialogueDeferredActualHost;
	private long dialogueDeferredObstacleFingerprint = Long.MIN_VALUE;
	private int dialogueDeferredContentFingerprint = Integer.MIN_VALUE;

	private volatile boolean dialogueMouseHeld;

	private GeometryState dialogueDragPreview;
	private ChatboxBounds.Tracker dialogueDragPreviewTracker;

	private boolean dialoguePostDropSearchPending;
	private boolean dialoguePostDropShapeSearch;
	private boolean dialoguePostDropSearchCompleted;

	private int dialogueFitConfiguredWidth = -1;
	private int dialogueFitConfiguredHeight = -1;
	private int dialogueFitRequestedWidth = -1;
	private int dialogueFitRequestedHeight = -1;

	private int dialogueFitFingerprint;
	private long dialogueCollisionFingerprint = Long.MIN_VALUE;
	private GeometryState dialogueFitGeometry;

	private boolean dialogueNormalRestoreWriting;

	private Rectangle dialogueCornerReference;

	private Rectangle dialogueOrthogonalFitBaseline;

	/* A first-contact fit keeps the configured host width and original X;
	 * the collision-shrunk effective viewport is not the physical host. */
	private boolean dialogueFirstContactHostPreserved;

	/* RuneLite owns SNAP/AUTO X/Y after a size-only fit. Wait for the native
	 * overlay and rendered widget to agree, then translate fit geometry once. */
	private boolean dialogueNativeSizeReflowPending;
	private int dialogueNativeSizeReflowCycle = Integer.MIN_VALUE;
	private Rectangle dialogueNativeSizeReflowStartingHost;
	private Rectangle dialogueSnappedReflowStableHost;
	private int dialogueSnappedReflowLastSampleCycle = Integer.MIN_VALUE;
	private int dialogueSnappedReflowStableSamples;

	/* A configured-size restore is not complete until native preferredLocation,
	 * host and viewport have all returned to the verified baseline. */
	private boolean dialogueOrthogonalRestorePending;

	/* Preserve the previously verified orthogonal fitting baseline across its restoration.
	 * A larger standard-fit dialogue is not the normal configured-size host. */
	private Rectangle dialogueOrthogonalRestoreTarget;

	/* Captured before a configured-size orthogonal fitting restore releases its MANUAL
	 * temporary host. Only that exact native transaction may verify a
	 * subsequent readback after clearDialogueFitState() discards its token. */
	private ChatboxPlacement.TemporaryHostRestoreEvidence dialogueReleasedRestoreEvidence;

	private int dialogueOrthogonalBaselineContentFingerprint = Integer.MIN_VALUE;

	private long dialogueReleasedGrowthFailedFingerprint = Long.MIN_VALUE;
	private long dialogueReleasedGrowthFailedAtNanos;

	private Rectangle dialogueReleasedFitRetained;
	private int dialogueReleasedFitContentFingerprint = Integer.MIN_VALUE;

	private long dialogueOrthogonalFailedFingerprint = Long.MIN_VALUE;
	private long dialogueOrthogonalFailedAtNanos;
	private int dialogueOrthogonalFailedContentFingerprint = Integer.MIN_VALUE;

	private boolean dialogueCornerRight;
	private boolean dialogueCornerBottom;
	private boolean dialogueCornerVerticalLocked;
	private boolean dialogueCornerHorizontalLocked;
	private boolean dialogueUserRightEdgePinned;

	/**
	 * ================================================================
	 * INITIALIZATION & DIAGNOSTICS
	 * ================================================================
	 */
	public DialogueCoreService(Client client, PerformanceMetrics performanceMetrics) {
		this(client, null, null, performanceMetrics);
	}

	public DialogueCoreService(Client client, ConfigManager configManager, PerformanceMetrics performanceMetrics) {
		this(client, configManager, null, performanceMetrics);
	}

	public DialogueCoreService(Client client, ConfigManager configManager, OverlayManager overlayManager, PerformanceMetrics performanceMetrics) {
		super(client, configManager, overlayManager, performanceMetrics);
		this.dialoguePrompts = new DialoguePrompts(client);
		this.dialogueViewport = new DialogueViewport(client, dialoguePrompts, chatboxPlacement);
		this.dialogueResizeService = new DialogueResizeService(client, chatboxPlacement, dialoguePrompts);
	}

	public void setDiagnostics(DialogueDiagnostics diagnostics, BooleanSupplier chatboxEnabled) {
		if (diagnostics != null) {
			this.dialogueDiagnostics = diagnostics;
		}
		if (chatboxEnabled != null) {
			this.chatboxDiagnosticsEnabled = chatboxEnabled;
		}
	}

	private boolean isChatboxTraceEnabled() {
		return LOG.isDebugEnabled() && chatboxDiagnosticsEnabled.getAsBoolean();
	}

	private boolean isDialogueTraceEnabled() {
		return dialogueDiagnostics.isEnabled();
	}

	@Override
	protected void reconcileDialogueAfterClientTick(int width, int height) {
		observeDialogueDragStart();
		if (dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended) {
			return;
		}
		if (unclassifiedNativeDialogueDrag()) {
			return;
		}
		/* A dialogue rebuild can consume the generic OVERLAY_RELEASE signal
		 * before collision reconciliation handles it. An already classified
		 * physical chatbox drag still must finish its own gesture. */
		if (dialogueHostDragActive && !dialogueMouseHeld && (dialogueCollisionDropPending || releasedDialogueHostMoved())) {
			finishPhysicalDialogueDrag(width, height);
		}
		if (dialogueHostDragActive) {
			return;
		}
		if (!dialogueRelayoutDirty) {
			return;
		}

		dialogueRelayoutDirty = false;

		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			return;
		}

		reassertModernChatboxRows();

		final GeometryState dialogueGeometry = renderDialogueGeometry(width, height);
		if (dialogueGeometry != null && !matchesCommittedDialogueGeometry(dialogueGeometry)) {
			final ResizeResult result = applyCommittedGeometry(dialogueGeometry, width, height, true);
			if (result.isApplied()) {
				widthRefreshPending |= result.isWidthChanged();
				heightRefreshPending |= result.isHeightChanged();
			}
		}
		/* Confirm both the writable widget model and the actual rendered bounds.
		 * Do not reapply a model-correct geometry just to chase a pending render. */
		verifyOrthogonalRestoreReadback(dialogueGeometry);

		if (dialogueTransitionPending) {
			if (!dialogueTransitionReady && dialoguePrompts.isActive()) {
				dialogueRelayoutDirty = true;
				return;
			}

			clearDialogueTransition();
		}

		syncDialoguePrompts();
	}

	/* Record generic (non-chatbox) overlay resets as a narrowly scoped input
	 * event. No individual minimap, tab-row or inventory overlay is hardcoded. */
	public void onOtherOverlayPlacementReset() {
		if (dialogueSession.isPresent() && dialoguePrompts.isActive()) {
			dialogueOtherOverlayResetCycle = client.getGameCycle();
		}
	}

	/* A saved-placement reset is NOT a confirmed dialogue drag. This is
	 * observed on the client thread before the coalesced placement readback. */
	public void onNativeChatboxPlacementReset() {
		if (!dialogueSession.isPresent() || !dialoguePrompts.isActive() || dialogueNormalRestoreWriting) {
			return;
		}
		final boolean relatedObstacleReset = dialogueOtherOverlayResetCycle != Integer.MIN_VALUE && client.getGameCycle() >= dialogueOtherOverlayResetCycle && client.getGameCycle() - dialogueOtherOverlayResetCycle <= 5;
		dialogueOtherOverlayResetCycle = Integer.MIN_VALUE;
		if (relatedObstacleReset && dialogueFitActive && !dialogueMouseHeld && !dialogueHostDragActive && chatboxPlacement.recoverTemporaryHostAfterUnrelatedReset()) {
			dialogueCollisionFingerprint = Long.MIN_VALUE;
			dialogueCollisionDropPending = true;
			dialogueObstacleResetRecoveryPending = true;

			return;
		}
		if (chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.SNAPPED && geometryState != null && !dialogueOrthogonalRestorePending) {
			final Rectangle oldHost = dialoguePhysicalHostBounds(geometryState);
			final Rectangle nativeHost = chatboxPlacement.nativeOverlayBounds();
			if (nativeHost != null && !nativeHost.equals(oldHost)) {
				/* Capture the native target BEFORE a committed-fit render
				 * callback can overwrite the overlay's mutable bounds. */
				dialogueNativeSnapTransitionTarget = new Rectangle(nativeHost);
			}
		} else if (chatboxPlacement.currentMode() != ChatboxPlacement.PlacementMode.SNAPPED) {
			dialogueNativeSnapTransitionTarget = null;
		}
		dialogueNativePlacementResetObserved = true;
		dialoguePinnedRejectedFitKey = Long.MIN_VALUE;
	}

	public boolean isDialogueCollisionOverlayChange(ConfigChanged event) {
		if (event == null || !RUNELITE_CONFIG_GROUP.equals(event.getGroup()) || event.getKey() == null) {
			return false;
		}

		final String key = event.getKey();
		return key.startsWith("RESIZABLE_MINIMAP_WIDGET_") || key.contains("INVENTORY_PARENT_") || key.startsWith(STATIC_TABS_OVERLAY + "_") || key.startsWith(MOVABLE_TABS_OVERLAY + "_");
	}

	public void markDialogueCollisionDirty() {
		dialogueCollisionFingerprint = Long.MIN_VALUE;
		dialogueFitRetryStrict = true;
		// A saved overlay location may arrive without a mouse-release callback.
		// Resample after RuneLite has had a render pass to settle its bounds.
		if (dialoguePrompts.isActive()) {
			dialogueCollisionDropPending = true;
		}
	}

	/**
	 * ================================================================
	 * DRAG CLASSIFICATION & OWNERSHIP
	 * ================================================================
	 */
	public void onOverlayMousePressed(int x, int y) {
		/* These immutable rectangles were sampled on the RuneLite client thread.
		 * Reading the published snapshot on AWT never touches a live widget. */
		final Rectangle originalHost = dialogueLastHostHitBounds;
		dialoguePressHostBounds = originalHost;
		dialoguePressInsideHost = originalHost != null && originalHost.contains(x, y);
		dialoguePressNativeHostBounds = dialogueLastNativeHostHitBounds;
		dialogueMouseHeld = true;
		dialogueMousePress = new Point(x, y);
		dialogueMouseDragPosition = new Point(x, y);
		dialogueMousePressSerial++;
	}

	public void onOverlayMouseDragged(int x, int y) {
		if (!dialogueMouseHeld) {
			return;
		}
		dialogueMouseDragPosition = new Point(x, y);
		dialogueCollisionDragObserved = true;
		dialogueCollisionDragPending = true;
	}

	public void onOverlayMouseReleased(int x, int y) {
		dialogueMouseDragPosition = new Point(x, y);
		dialogueMouseHeld = false;
		if (dialogueCollisionDragObserved) {
			dialogueCollisionDropPending = true;
			dialogueCollisionDragObserved = false;
		}
	}

	private boolean unclassifiedNativeDialogueDrag() {
		/* RUNTIME_DRAG is a placement mode, not proof that the mouse is
		 * dragging the chatbox. Once the press has been classified as an
		 * obstacle drag, its live collision updates must continue. Only
		 * suspend reconciliation if the native drag is still unclassified. */
		return dialogueMouseHeld && dialogueCollisionDragObserved && !dialogueDragKindDecided && !dialogueHostDragActive && dialogueSession.isPresent() && chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.RUNTIME_DRAG;
	}

	private boolean activeObstacleDialogueDrag() {
		return dialogueMouseHeld && dialogueCollisionDragObserved && dialogueDragKindDecided && !dialogueHostDragActive;
	}

	private boolean dialogueHostRenderAligned(GeometryState current) {
		if (current == null) {
			return false;
		}
		final Widget slot = getSlot(getLayout());
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Rectangle target = dialoguePhysicalHostBounds(current);
		return slot != null && universe != null && target != null && target.equals(slot.getBounds()) && current.effectiveBounds.equals(universe.getBounds());
	}

	private void observeDialogueDragStart() {
		if (dialogueMousePressSerial != dialogueProcessedPressSerial) {
			dialogueProcessedPressSerial = dialogueMousePressSerial;
			dialogueDragKindDecided = false;
			dialogueHostDragActive = false;
		}
		if (dialogueCollisionDragPending || dialogueCollisionDropPending) {
			classifyDialogueDrag();
		}
	}

	private void snapshotDialogueHostHitBounds() {
		if (dialogueMouseHeld || dialogueHostDragActive) {
			return;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle physical = slot != null ? slot.getBounds() : null;
		dialogueLastHostHitBounds = dialoguePrompts.isActive() && physical != null && physical.width > 0 && physical.height > 0 ? new Rectangle(physical) : null;
		dialogueLastNativeHostHitBounds = dialoguePrompts.isActive() ? chatboxPlacement.nativeOverlayBounds() : null;
	}

	private void classifyDialogueDrag() {
		if (dialogueDragKindDecided || !dialoguePrompts.isActive()) {
			return;
		}

		final Point pressed = dialogueMousePress;
		final Widget slot = getSlot(getLayout());
		final Rectangle widget = slot != null ? slot.getBounds() : null;
		final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
		final GeometryState committed = dialogueDragGeometry(geometryState != null ? geometryState.configuredWidth : -1, geometryState != null ? geometryState.configuredHeight : -1);
		final Rectangle host = dialoguePhysicalHostBounds(committed);
		if (pressed == null || widget == null || committed == null) {
			return;
		}

		/* First-contact ownership is physical, not geometric. A minimap,
		 * inventory or tab-row drag can begin within the native host. Only
		 * confirm CHATBOX_DRAG when the host and its own overlay have
		 * followed this pointer by the same displacement. orthogonal fitting is deferred
		 * while a mouse gesture is active; collision viewport clipping
		 * remains free to update independently. */
		final Rectangle pressedHost = dialoguePressHostBounds;
		final Rectangle pressedNative = dialoguePressNativeHostBounds;
		final Point dragged = dialogueMouseDragPosition;
		final boolean snapshotHit = dialoguePressInsideHost && pressedHost != null;
		final boolean matchingNative = snapshotHit && pressedNative != null && nativeOverlay != null && widget.width == pressedHost.width && widget.height == pressedHost.height && nativeOverlay.width == pressedNative.width && nativeOverlay.height == pressedNative.height && Math.abs(widget.x - nativeOverlay.x) <= 2 && Math.abs(widget.y - nativeOverlay.y) <= 2;
		final int hostDx = snapshotHit ? widget.x - pressedHost.x : 0;
		final int hostDy = snapshotHit ? widget.y - pressedHost.y : 0;
		final int pointerDx = pressed != null && dragged != null ? dragged.x - pressed.x : 0;
		final int pointerDy = pressed != null && dragged != null ? dragged.y - pressed.y : 0;
		final boolean nativeMoved = matchingNative && Math.abs(hostDx - (nativeOverlay.x - pressedNative.x)) <= 2 && Math.abs(hostDy - (nativeOverlay.y - pressedNative.y)) <= 2 && (Math.abs(hostDx) >= 3 || Math.abs(hostDy) >= 3);
		final boolean followedPointer = Math.abs(hostDx - pointerDx) <= 12 && Math.abs(hostDy - pointerDy) <= 12;
		final boolean confirmedNativeDrag = snapshotHit && nativeMoved && followedPointer;

		/* Until the native host actually moves, retain the press snapshot
		 * and let the obstacle's live collision geometry keep updating.
		 * A click with no host movement is never a user-placement drag. */
		if (!confirmedNativeDrag && snapshotHit && dialogueMouseHeld) {
			return;
		}

		dialogueDragKindDecided = true;
		dialogueHostDragActive = confirmedNativeDrag;
		dialogueInteraction = confirmedNativeDrag ? "CHATBOX_DRAG" : "OBSTACLE_DRAG";
		if (isChatboxTraceEnabled()) {
			LOG.debug("[Chat XL][Dialogue Drag] press={} | originalHost={} | currentWidget={} | nativeOverlay={} | expectedHost={} | nativeAtPress={} | pointerDelta={},{} | hostDelta={},{} | nativeMoved={} | followsPointer={} | result={}", pressed, rectangleBounds(pressedHost), rectangleBounds(widget), rectangleBounds(nativeOverlay), rectangleBounds(host), rectangleBounds(pressedNative), pointerDx, pointerDy, hostDx, hostDy, nativeMoved, followedPointer, dialogueInteraction);
		}
		if (dialogueHostDragActive) {
			/* A newly classified chatbox gesture owns its own post-drop fit. */
			dialoguePostDropSearchCompleted = false;
			dialogueClassifiedChatboxPressSerial = dialogueMousePressSerial;
			chatboxPlacement.beginNativePlacementDrag();
			dialogueNativeSaveDuringDrag = false;
			dialogueAwaitingNativeSave = false;
			dialogueUnconfirmedDropSuspended = false;
			/* Preserve the actual mouse-down position, not a widget that may
			 * already have followed the pointer before classification ran. */
			dialogueHostDragStart = new Rectangle(pressedHost);
			dialogueDragPreviewTracker = chatboxBoundsTracker.copy();
			dialogueDragPreview = null;
			// An actual user drag owns X/Y until release. Never restore an old pin.
			chatboxPlacement.discardTemporaryHost();
		}
	}

	private boolean releasedDialogueHostMoved() {
		if (dialogueMouseHeld || !dialogueHostDragActive || dialogueClassifiedChatboxPressSerial != dialogueMousePressSerial || dialogueHostDragStart == null) {
			return false;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle host = slot != null ? slot.getBounds() : null;
		final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
		return host != null && host.equals(nativeOverlay) && host.width == dialogueHostDragStart.width && host.height == dialogueHostDragStart.height && (host.x != dialogueHostDragStart.x || host.y != dialogueHostDragStart.y);
	}

	private void finishPhysicalDialogueDrag(int width, int height) {
		if (!dialogueHostDragActive) {
			return;
		}

		followPhysicalDialogueDrag(width, height);
		final Widget dropSlot = getSlot(getLayout());
		final Rectangle dropBounds = dropSlot != null ? dropSlot.getBounds() : null;
		final boolean moved = dialogueHostDragStart != null && dropBounds != null && (dialogueHostDragStart.x != dropBounds.x || dialogueHostDragStart.y != dropBounds.y);
		final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
		final boolean confirmedRelease = moved && dropBounds.equals(nativeOverlay) && dropBounds.width == dialogueHostDragStart.width && dropBounds.height == dialogueHostDragStart.height && dialogueClassifiedChatboxPressSerial == dialogueMousePressSerial;
		if (confirmedRelease) {
			updateDialogueReferenceForConfirmedDrag(dropBounds);
			dialogueConfirmedDropBounds = new Rectangle(dropBounds);
			dialogueUserDropPinned = true;
			dialoguePinnedRejectedFitKey = Long.MIN_VALUE;
			resetDialogueCornerReference(dropBounds);
			rebaseConfirmedDialogueHost(dropBounds);
		}
		dialogueHostDragActive = false;
		/* The native-save handoff returns before ordinary collision reconciliation
		 * can consume the last drag event. A released host is no longer being
		 * dragged, so do not let this stale event veto its physical-drop recovery. */
		dialogueCollisionDragPending = false;
		dialogueDragPreview = null;
		dialogueDragPreviewTracker = null;
		if (!moved && !dialogueNativeSaveDuringDrag) {
			/* A click or small pointer movement without a host move is not a drop. */
			dialogueCollisionFingerprint = Long.MIN_VALUE;
			dialogueNativeSaveDuringDrag = false;
			return;
		}
		/* Widget movement alone does not prove that OverlayManager persisted a
		 * native drag. Do not refit from the provisional drop until it does. */
		dialoguePostDropSearchPending = dialogueNativeSaveDuringDrag;
		dialogueFitRetryStrict = dialogueNativeSaveDuringDrag;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
		dialogueDropSettlePending = true;
		dialogueDropSettleDelay = 2;
		dialogueDropSettleAlignmentWaits = 0;
		dialogueDropSample = dropBounds != null ? new Rectangle(dropBounds) : new Rectangle(dialoguePhysicalHostBounds(dialogueFitGeometry));
		if (dialogueNativeSaveDuringDrag) {
			onSavedChatboxPlacementChanged(width, height);
		} else {
			dialogueAwaitingNativeSave = true;
			dialogueNativeSaveWaitFrames = NATIVE_DROP_SAVE_WAIT_FRAMES;
			chatboxPlacement.markUnconfirmedNativeDrop();
		}
		dialogueNativeSaveDuringDrag = false;
	}

	private void followPhysicalDialogueDrag(int width, int height) {
		final GeometryState committed = dialogueDragGeometry(width, height);
		final Widget slot = getSlot(getLayout());
		if (committed == null || slot == null) {
			return;
		}

		final Rectangle widget = slot.getBounds();
		final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
		final Rectangle host = dialoguePhysicalHostBounds(committed);
		if (widget == null || widget.width != host.width || widget.height != host.height) {
			return;
		}

		// A temporary renderer disagreement is not a new user placement.
		if (nativeOverlay != null && nativeOverlay.width == host.width && nativeOverlay.height == host.height && (Math.abs(nativeOverlay.x - widget.x) > 2 || Math.abs(nativeOverlay.y - widget.y) > 2)) {
			// Do not adopt a transient host location as a saved user position.
			return;
		}

		final int dx = widget.x - host.x;
		final int dy = widget.y - host.y;
		if (dx == 0 && dy == 0) {
			return;
		}

		final Rectangle desired = new Rectangle(committed.desiredBounds);
		final Rectangle effective = new Rectangle(committed.effectiveBounds);
		desired.translate(dx, dy);
		effective.translate(dx, dy);
		dialogueFitGeometry = new GeometryState(width, height, desired, effective);
		geometryState = dialogueFitGeometry;
		dialogueUserDropPinned = true;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
	}

	private void previewPhysicalDialogueCollision(int width, int height) {
		if (!dialogueHostDragActive || !dialoguePrompts.isActive()) {
			return;
		}

		final Widget slot = getSlot(getLayout());
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		final Rectangle physical = slot != null ? slot.getBounds() : null;
		if (physical == null || universe == null || chatArea == null || physical.width < DRAG_PREVIEW_MIN_WIDTH || physical.height < DRAG_PREVIEW_MIN_HEIGHT) {
			return;
		}

		if (dialogueDragPreviewTracker == null) {
			dialogueDragPreviewTracker = chatboxBoundsTracker.copy();
		}
		final ChatboxPlacement.State placement = chatboxPlacement.captureDroppedDialogue(physical, physical.width, physical.height);
		final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final ChatboxBounds.Result resolved = ChatboxBounds.resolve(client, placement, chatboxButtonsHidden, dialogueDragPreviewTracker, overrides);
		final Rectangle desired = resolved.getDesiredBounds();
		final Rectangle effective = resolved.getEffectiveBounds();
		if (effective.width < DRAG_PREVIEW_MIN_WIDTH || effective.height < DRAG_PREVIEW_MIN_HEIGHT) {
			return;
		}

		final GeometryState previous = dialogueDragPreview;
		dialogueDragPreview = new GeometryState(width, height, desired, effective);
		final int offsetX = Math.max(0, effective.x - physical.x);
		final int offsetY = Math.max(0, effective.y - physical.y);
		final int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
		final boolean innerUnchanged = universe.getRelativeX() == offsetX && universe.getRelativeY() == offsetY && universe.getWidth() == effective.width && universe.getHeight() == effective.height && chatArea.getHeight() == bodyHeight && controlsMatch(effective.width);
		if (innerUnchanged && previous != null && previous.desiredBounds.equals(desired) && previous.effectiveBounds.equals(effective)) {
			return;
		}

		if (!innerUnchanged) {
			applyInnerGeometry(universe, chatArea, offsetX, offsetY, effective.width, effective.height, bodyHeight, !controlsMatch(effective.width));
			applyBackgroundForHost(chatArea, effective.width, bodyHeight);
		}
		final DialoguePrompts.Result prompt = dialoguePrompts.apply(effective.width, bodyHeight, dialogueFitConstrainedWrap, effective.y > desired.y);
		recordMutations(prompt.getMutations());
		recordRevalidates(prompt.getRevalidates());
	}

	@Override
	protected void adoptLiveDialogueHostPosition(int width, int height) {
		if (dialogueHostDragActive) {
			followPhysicalDialogueDrag(width, height);
		}
	}

	@Override
	protected GeometryState committedDialogueGeometry(int width, int height) {
		return dialogueFitGeometry != null && dialogueFitGeometry.configuredWidth == width && dialogueFitGeometry.configuredHeight == height
				? dialogueFitGeometry
				: null;
	}

	private GeometryState dialogueDragGeometry(int width, int height) {
		final GeometryState fitted = committedDialogueGeometry(width, height);
		if (fitted != null) {
			return fitted;
		}
		final GeometryState current = geometryState;
		return dialogueSession.isPresent() && current != null && current.configuredWidth == width && current.configuredHeight == height ? current : null;
	}

	private GeometryState renderDialogueGeometry(int width, int height) {
		final GeometryState fit = committedDialogueGeometry(width, height);
		if (fit == null) {
			return null;
		}

		final GeometryState live = geometryState;
		return live != null && live.configuredWidth == width && live.configuredHeight == height
				? live
				: fit;
	}

	@Override
	protected ResizeResult applyCommittedGeometry(int width, int height) {
		return applyCommittedGeometry(width, height, false);
	}

	private ResizeResult applyCommittedGeometry(int width, int height, boolean restoreHostPosition) {
		return applyCommittedGeometry(geometryState, width, height, restoreHostPosition);
	}

	private ResizeResult applyCommittedGeometry(GeometryState committed, int width, int height, boolean restoreHostPosition) {
		/* Native scripts may rebuild the slot during a size handoff.
		 * Still reapply its size-only geometry; never block the writer here,
		 * or native/rendered bounds could stay mismatched indefinitely. */
		if (staleSnappedDialogueHost() || unverifiedManualDialogueHost()) {
			return ResizeResult.NOT_APPLIED;
		}
		/* The native overlay must finish saving the released host BEFORE we
		 * reassert a larger committed dialogue rectangle. Otherwise a host
		 * dropped near the bottom can grow past the canvas, be clamped upward
		 * by RuneLite, and be mistaken for a new physical drop. */
		if (dialogueAwaitingNativeSave) {

			return ResizeResult.NOT_APPLIED;
		}
		if (dialogueHostDragActive && dialogueFitActive) {
			return ResizeResult.NOT_APPLIED;
		}
		/* AUTO has no preferred coordinate to hold an expanded dialogue in place.
		 * Release the accepted fit instead of reasserting a stale manual rectangle. */
		if (dialogueFitActive && !canvasResizePending && chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.NATIVE && !dialogueNativeAutoFit) {
			clearDialogueFitState();
			return applySize(width, height);
		}
		// Use normal solving when committed geometry is unavailable, stale, or still stabilizing.
		if (committed == null || committed.configuredWidth != width || committed.configuredHeight != height || loginGeometryPending) {
			return applySize(width, height);
		}

		final long started = performanceMetrics != null && performanceMetrics.isEnabled() ? System.nanoTime() : 0L;
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

		// Apply committed chatbox geometry without reevaluating side-row policy.
		final Rectangle effectiveBounds = committed.effectiveBounds;
		final Rectangle hostBounds = dialoguePhysicalHostBounds(committed);
		final int hostWidth = hostBounds.width;
		final int hostHeight = hostBounds.height;
		final int effectiveWidth = effectiveBounds.width;
		final int effectiveHeight = effectiveBounds.height;
		final int effectiveX = hostBounds.equals(effectiveBounds) ? 0 : Math.max(0, effectiveBounds.x - desiredBounds.x);
		final int effectiveY = hostBounds.equals(effectiveBounds) ? 0 : Math.max(0, effectiveBounds.y - desiredBounds.y);
		final int effectiveBodyHeight = ChatboxGeometry.bodyHeight(effectiveHeight, chatboxButtonsHidden);
		boolean hostPositionChanged = false;
		if (dialogueFitActive) {
			final ChatboxPlacement.PlacementMode mode = chatboxPlacement.currentMode();
			final boolean nativeOwned = mode == ChatboxPlacement.PlacementMode.NATIVE || mode == ChatboxPlacement.PlacementMode.SNAPPED;
			final boolean updateHostLocation = (restoreHostPosition || dialogueNativeAutoFit) && !canvasResizePending && !nativeOwned;
			final boolean moveHost = updateHostLocation && (chatboxPlacement.allowsDirectHostMove() || dialogueFitClippedFallback);
			final boolean overlayMoved = !canvasResizePending && !dialogueHostDragActive && !nativeOwned && chatboxPlacement.applyTemporaryHost(hostBounds);
			if (!overlayMoved) {
				chatboxPlacement.applyHostBounds(hostBounds, updateHostLocation, dialogueFitClippedFallback || dialogueNativeAutoFit);
			}
			hostPositionChanged = moveHost && chatboxPlacement.moveHost(slot, hostBounds, dialogueFitClippedFallback);

			if (hostPositionChanged) {
				recordMutation();
			}
		}

		final boolean hostChanged = slot.getWidth() != hostWidth || slot.getHeight() != hostHeight;
		final boolean positionChanged = universe.getRelativeX() != effectiveX || universe.getRelativeY() != effectiveY;
		final boolean widthChanged = universe.getWidth() != effectiveWidth || chatArea.getWidth() != effectiveWidth;
		final boolean heightChanged = universe.getHeight() != effectiveHeight || chatArea.getHeight() != effectiveBodyHeight;
		final boolean controlsChanged = !controlsMatch(effectiveWidth);

		/*
		 * Native 1972 may transiently move the managed chatbox host while rebuilding.
		 * moveHost() restores the committed X/Y immediately; do not revalidate the
		 * host solely for that position repair, or the repair can provoke another
		 * top-level relayout and create a visible position oscillation. Host size
		 * changes still revalidate normally inside applyGeometry().
		 */
		if (hostChanged || positionChanged || widthChanged || heightChanged || controlsChanged) {
			applyGeometry(slot, universe, chatArea, hostWidth, hostHeight, effectiveX, effectiveY, effectiveWidth, effectiveHeight, effectiveBodyHeight, controlsChanged, false);
		}

		applyBackgroundForHost(chatArea, effectiveWidth, effectiveBodyHeight);
		if (liveDepth == 0) {
			syncDialoguePrompts(effectiveWidth, effectiveBodyHeight);
		}

		syncChatPresentation();
		resizedLayoutApplied = true;

		recordApply(started, widthChanged, heightChanged);

		return new ResizeResult(true, widthChanged, heightChanged);
	}

	private void rebaseDialogueAfterCanvasResize(int width, int height) {
		if (!dialogueCanvasRebasePending || !dialogueFitActive || !dialoguePrompts.isActive() || dialogueHostDragActive) {
			return;
		}
		final Rectangle old = dialogueCanvasRebaseReference;
		final Widget slot = getSlot(getLayout());
		if (old == null || slot == null) {
			return;
		}
		final int canvasWidth = Math.max(0, client.getCanvasWidth());
		final int canvasHeight = Math.max(0, client.getCanvasHeight());
		if (canvasWidth <= 0 || canvasHeight <= 0) {
			return;
		}
		final Rectangle nativeHost = chatboxPlacement.nativeOverlayBounds();
		final Rectangle widgetHost = slot.getBounds();
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Rectangle rendered = universe != null ? universe.getBounds() : null;
		final boolean physicalAligned = nativeHost != null && widgetHost != null && nativeHost.equals(widgetHost) && DialogueResizeService.withinDialogueCanvas(nativeHost, canvasWidth, canvasHeight);
		final boolean renderedValid = rendered != null && DialogueResizeService.withinDialogueCanvas(rendered, canvasWidth, canvasHeight);
		final int oldCanvasWidth = dialogueCanvasRebaseOldWidth > 0 ? dialogueCanvasRebaseOldWidth : Math.max(canvasWidth, old.x + old.width);
		final int oldCanvasHeight = dialogueCanvasRebaseOldHeight > 0 ? dialogueCanvasRebaseOldHeight : Math.max(canvasHeight, old.y + old.height);
		final int projectedX = old.x * canvasWidth / oldCanvasWidth;
		final int projectedY = old.y * canvasHeight / oldCanvasHeight;
		final int candidateX = physicalAligned ? nativeHost.x : renderedValid ? rendered.x : projectedX;
		final int candidateY = physicalAligned ? nativeHost.y : renderedValid ? rendered.y : projectedY;
		final Rectangle rebased = new Rectangle(Math.max(0, Math.min(candidateX, canvasWidth - Math.min(canvasWidth, old.width))), Math.max(0, Math.min(candidateY, canvasHeight - Math.min(canvasHeight, old.height))), old.width, old.height);
		/* Keep the full preferred dimensions so the next strict fit can trade
		 * width for height; the resolver owns any unavoidable edge clipping. */
		dialogueFitGeometry = new GeometryState(width, height, rebased, rebased);
		geometryState = dialogueFitGeometry;
		resetDialogueCornerReference(rebased);
		dialogueFitRetryStrict = true;
		dialogueFitDeferredHostMismatch = false;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
		dialoguePostDropSearchPending = false;
		dialogueCanvasRebasePending = false;
		dialogueCanvasRebaseReference = null;
	}

	private void settleDialogueDrop() {
		if (!dialogueDropSettlePending || dialogueCollisionDropPending || dialogueCollisionDropFrames > 0) {
			return;
		}

		// A later gesture must not inherit the prior drop's ownership state.
		if (dialogueMouseHeld || dialogueHostDragActive) {
			dialogueDropSettlePending = false;
			dialogueDropSettleDelay = 0;
			dialogueDropSettleAlignmentWaits = 0;
			return;
		}

		if (dialogueDropSettleDelay > 0) {
			dialogueDropSettleDelay--;
			return;
		}

		final GeometryState committed = geometryState;
		final Widget slot = getSlot(getLayout());
		final Rectangle selected = committed != null ? dialoguePhysicalHostBounds(committed) : null;
		final Rectangle widget = slot != null ? slot.getBounds() : null;
		final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Rectangle viewport = universe != null ? universe.getBounds() : null;
		final boolean aligned = selected != null && selected.equals(widget) && (nativeOverlay == null || selected.equals(nativeOverlay)) && committed != null && committed.effectiveBounds.equals(viewport);

		/* Give RuneLite several render passes to settle before retiring the
		 * drag bookkeeping. An unresolved readback never moves the host. */
		if (!aligned && dialogueDropSettleAlignmentWaits++ < DIALOGUE_SETTLED_ALIGNMENT_WAIT_FRAMES) {
			return;
		}

		dialogueDropSettlePending = false;
		if (isDialogueTraceEnabled() && !aligned) {
			dialogueDiagnostics.event("Dialogue Drop", "Native/widget alignment pending after settle window");
		}
		dialogueInteraction = "UNKNOWN";
		dialogueHostDragStart = null;
		dialoguePostDropSearchCompleted = false;
	}

	public boolean consumeNormalChatRestoredEvent() {
		final boolean restored = normalChatRestoredEvent;
		normalChatRestoredEvent = false;
		return restored;
	}

	/* Release an obsolete *dialogue* save wait only after normal presentation
	 * has independently confirmed closure. An ordinary NPC/OPTIONS rebuild
	 * cannot satisfy this predicate. The pre-dialogue reference retains its
	 * center and native anchor; the abandoned runtime drop never becomes a
	 * new normal placement. */
	private void releaseClosedDialogueNativeSaveWait() {
		if (dialoguePrompts.isActive() || dialogueTransitionPending || !dialogueSession.isClosureConfirmed() || dialogueMouseHeld || dialogueHostDragActive || dialogueNormalRestoreWriting || dialogueSession.getRestoreExpected() != null || (!dialogueAwaitingNativeSave && !dialogueUnconfirmedDropSuspended && !chatboxPlacement.isUnconfirmedNativeDrop())) {
			return;
		}

		final ChatboxPlacement.NormalPlacement normal = dialogueSession.getNormalPlacement() != null ? dialogueSession.getNormalPlacement() : !isNormalChatboxPlacementPending() ? getNormalChatboxPlacement() : null;
		/* A missing normal placement still uses emergency's emergency fallback,
		 * but requires a physically aligned host and an existing session
		 * center before releasing the native-save gate. */
		final Widget slot = getSlot(getLayout());
		final Rectangle physical = slot != null ? slot.getBounds() : null;
		final boolean verifiedEmergency = normal == null && dialogueSession.getCenter() != null && physical != null && physical.width > 0 && physical.height > 0 && physical.equals(chatboxPlacement.nativeOverlayBounds());
		if (normal == null && !verifiedEmergency) {
			return;
		}
		if (!chatboxPlacement.releaseClosedDialogueNativeHandoff(normal, verifiedEmergency)) {
			return;
		}

		dialogueAwaitingNativeSave = false;
		dialogueUnconfirmedDropSuspended = false;
		dialogueNativeSaveWaitFrames = 0;
		dialogueNativeSaveDuringDrag = false;
		dialogueCollisionDropPending = false;
		dialogueCollisionDragPending = false;
	}

	private void resumeConfirmedNativeDrop() {
		if (!dialogueUnconfirmedDropSuspended || !chatboxPlacement.rejoinSavedNativePlacement()) {
			return;
		}
		dialogueUnconfirmedDropSuspended = false;
		dialogueFitRetryStrict = true;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
	}

	private void fallbackUnconfirmedNativeDrop(int width, int height) {
		dialogueAwaitingNativeSave = false;
		dialogueNativeSaveWaitFrames = 0;
		dialogueUnconfirmedDropSuspended = true;
		final Widget slot = getSlot(getLayout());
		final Rectangle physical = slot != null ? slot.getBounds() : null;

		clearDialogueFitState();
		geometryState = null;
		chatboxBoundsTracker.reset();
		/* markUnconfirmedNativeDrop() remains active across fit-state clearing,
		 * making capture() follow the live runtime host rather than stale config. */
		applySizeInternal(width, height, true, null);
	}

	private boolean rejoinUnsavedPhysicalDrop(int width, int height) {
		/* The pending AWT drag signal is not proof that the mouse remains down.
		 * During the native-save wait it may never be consumed by the normal
		 * collision pass. Use the actual held/active drag state instead. */
		if (!dialogueAwaitingNativeSave || dialogueMouseHeld || dialogueHostDragActive || dialogueHostDragStart == null || dialogueDropSample == null || !dialoguePrompts.isActive() || !dialogueFitActive || dialogueTransitionPending) {
			return false;
		}

		final Widget slot = getSlot(getLayout());
		final Rectangle host = slot != null ? slot.getBounds() : null;
		final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
		/* The pointer-release snapshot may be older than RuneLite's final
		 * rendered drop by several pixels. The physical host and RuneLite
		 * overlay agreeing after release is stronger evidence than comparing
		 * their X/Y to that stale snapshot. Dimensions must still match. */
		/* The drag-preview rectangle is the reduced UNIVERSE, not necessarily
		 * RuneLite's physical host. In a LEFT collision the host can be wider
		 * while both host and native overlay agree on the real dropped box. */
		final Rectangle fittedHost = dialogueFitGeometry != null ? dialogueFitGeometry.desiredBounds : null;
		final boolean previewSize = host != null && host.width == dialogueDropSample.width && host.height == dialogueDropSample.height;
		final boolean fittedHostSize = host != null && fittedHost != null && host.width == fittedHost.width && host.height == fittedHost.height;
		if (host == null || nativeOverlay == null || !host.equals(nativeOverlay) || !(previewSize || fittedHostSize) || !chatboxPlacement.recoverUnconfirmedExpandedDrop(slot, host, dialogueDropSample, width, height)) {

			return false;
		}

		/* The temporary host rejoin alone is insufficient: the retained fitted
		 * desired/effective rectangles may still describe the PRE-drag location.
		 * Translate both by the same physical drop delta. Let the next collision
		 * pass shrink from that confirmed user placement, not the old anchor. */
		final GeometryState retained = committedDialogueGeometry(width, height);
		if (retained != null) {
			final Rectangle reference = fittedHostSize ? retained.desiredBounds : retained.effectiveBounds;
			final int dx = host.x - reference.x;
			final int dy = host.y - reference.y;
			if (dx != 0 || dy != 0) {
				final Rectangle desired = new Rectangle(retained.desiredBounds);
				final Rectangle effective = new Rectangle(retained.effectiveBounds);
				desired.translate(dx, dy);
				effective.translate(dx, dy);
				dialogueFitGeometry = new GeometryState(width, height, desired, effective);
				geometryState = dialogueFitGeometry;
			}
		}
		dialogueAwaitingNativeSave = false;
		dialogueNativeSaveWaitFrames = 0;
		dialogueUnconfirmedDropSuspended = false;
		dialogueFitRetryStrict = true;
		dialoguePostDropSearchPending = true;
		dialogueCollisionDropPending = true;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
		updateDialogueReferenceForConfirmedDrag(host);
		resetDialogueCornerReference(host);
		rebaseConfirmedDialogueHost(host);
		dialogueUserDropPinned = true;
		dialogueConfirmedDropBounds = new Rectangle(host);
		dialoguePinnedRejectedFitKey = Long.MIN_VALUE;
		/* Fitting has already adopted this released physical gesture. A later
		 * native config save must acknowledge it without adopting it again. */
		dialogueAdoptedPhysicalPressSerial = dialogueMousePressSerial;

		return true;
	}

	private void recoverUnconfirmedRuntimeFit(int width, int height) {
		if (!dialogueUnconfirmedDropSuspended || !dialoguePrompts.isActive() || dialogueMouseHeld || dialogueCollisionDragPending || dialogueCollisionDropPending || dialogueHostDragActive || dialogueTransitionPending || loginGeometryPending || canvasResizePending || sideLayoutDepth > 0) {
			return;
		}

		final Widget slot = getSlot(getLayout());
		final Rectangle host = slot != null ? slot.getBounds() : null;
		final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
		if (host == null || nativeOverlay == null || !host.equals(nativeOverlay) || host.width != width || host.height != height || !chatboxPlacement.recoverStableRuntimeFit(slot, width, height)) {
			return;
		}

		dialogueUnconfirmedDropSuspended = false;
		dialogueFitRetryStrict = true;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
		geometryState = null;
		chatboxBoundsTracker.reset();

		final ResizeResult result = applySizeInternal(width, height, false, null);
		if (result.isApplied()) {
			widthRefreshPending |= result.isWidthChanged();
			heightRefreshPending |= result.isHeightChanged();
		}
	}

	private void markDialoguePageRebuild() {
		dialoguePageRebuildPending = true;
		dialoguePageRebuildCycle = client.getGameCycle();
	}

	/* RuneLite can reset the chatbox to a native 519x165 host while replacing
	 * NPC content with OPTIONS. If X/Y is unchanged, a verified page rebuild
	 * is allowed to restore the previous host and measure the new text. It is
	 * never allowed to adopt a new user-owned position or dialogue center. */
	private boolean trustedDialoguePageRebuild(Rectangle expected, Rectangle rendered, Rectangle nativeBounds) {
		if (!dialoguePageRebuildPending || expected == null || rendered == null || nativeBounds == null || !dialogueSession.isPresent() || !dialoguePrompts.isActive() || dialogueSession.getRestoreExpected() != null || dialogueMouseHeld || dialogueHostDragActive || dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended || unclassifiedNativeDialogueDrag() || dialogueNativePlacementResetObserved || dialogueOrthogonalRestorePending || chatboxPlacement.currentMode() != ChatboxPlacement.PlacementMode.MANUAL || dialoguePrompts.fitFingerprint() == dialogueFitFingerprint) {
			return false;
		}
		final int cycle = client.getGameCycle();
		if (dialoguePageRebuildCycle == Integer.MIN_VALUE || cycle < dialoguePageRebuildCycle || cycle - dialoguePageRebuildCycle > DIALOGUE_SETTLED_ALIGNMENT_WAIT_FRAMES) {
			return false;
		}
		/* The native overlay may still carry the old size in the first
		 * rebuild frame, but its X/Y must independently match the rendered
		 * slot and the previously verified placement. */
		return expected.x == rendered.x && expected.y == rendered.y && nativeBounds.x == rendered.x && nativeBounds.y == rendered.y && (expected.width != rendered.width || expected.height != rendered.height);
	}

	/* A native MANUAL handoff can also leave the physical chatbox somewhere
	 * other than the committed fit. The collision resolver must not clip against
	 * that stale location; only a confirmed user drag may rebase session center.
	 * A temporary render delay is held the same way, without adopting its bounds. */
	private boolean unverifiedManualDialogueHost() {
		if (!dialogueSession.isPresent() || !dialoguePrompts.isActive() || !dialogueUserDropPinned || geometryState == null || dialogueHostDragActive || dialogueNormalRestoreWriting || dialogueSession.getRestoreExpected() != null || chatboxPlacement.currentMode() != ChatboxPlacement.PlacementMode.MANUAL) {
			return false;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle expected = dialoguePhysicalHostBounds(geometryState);
		final Rectangle rendered = slot != null ? slot.getBounds() : null;
		final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
		if (trustedDialoguePageRebuild(expected, rendered, nativeBounds)) {

			return false;
		}
		return expected == null || rendered == null || nativeBounds == null || !expected.equals(rendered) || !expected.equals(nativeBounds);
	}

	private boolean holdUnverifiedManualDialogueHost() {
		if (!unverifiedManualDialogueHost()) {
			return false;
		}
		return true;
	}

	/* A native re-snap may move the rendered host while the previous MANUAL
	 * fit still owns its desired rectangle. Never clip that obsolete rectangle:
	 * the center is user-owned, but collision geometry must use the live host. */
	private boolean staleSnappedDialogueHost() {
		if (!dialogueSession.isPresent() || !dialoguePrompts.isActive() || geometryState == null || dialogueOrthogonalRestorePending || dialogueNormalRestoreWriting || dialogueSession.getRestoreExpected() != null || chatboxPlacement.currentMode() != ChatboxPlacement.PlacementMode.SNAPPED) {
			return false;
		}
		if (dialogueNativeSnapTransitionTarget != null) {
			return true;
		}
		final Rectangle expected = dialoguePhysicalHostBounds(geometryState);
		final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
		final Widget slot = getSlot(getLayout());
		final Rectangle rendered = slot != null ? slot.getBounds() : null;
		/* Without a native-reset signal, require independent native/rendered
		 * agreement to distinguish a genuine snap from an in-flight orthogonal fitting render. */
		if (expected == null || nativeBounds == null || rendered == null || !nativeBounds.equals(rendered)) {
			return false;
		}
		/* RuneLite can shift a snapped box by 1-2px when redrawing its native
		 * border. This is not a new snap or a reason to discard a valid orthogonal fitting fit. */
		if (expected.width == rendered.width && expected.height == rendered.height && Math.abs(expected.x - rendered.x) <= NATIVE_SNAP_REFLOW_TOLERANCE && Math.abs(expected.y - rendered.y) <= NATIVE_SNAP_REFLOW_TOLERANCE) {
			return false;
		}
		return !expected.equals(rendered);
	}

	/* Retire one size-only native resize transaction and its settle samples. */
	private void resetSnappedSizeReflow() {
		dialogueNativeSizeReflowPending = false;
		dialogueNativeSizeReflowCycle = Integer.MIN_VALUE;
		dialogueNativeSizeReflowStartingHost = null;
		dialogueSnappedReflowStableHost = null;
		dialogueSnappedReflowLastSampleCycle = Integer.MIN_VALUE;
		dialogueSnappedReflowStableSamples = 0;
	}

	/* A moved RuneLite snap may retain its actual top/left anchor even when
	 * orthogonal fitting requests growth in the opposite direction. If native and rendered
	 * size/position agree across several distinct cycles, and RuneLite never
	 * reaches the selected X/Y, accept only the physical host translation.
	 * The immutable pre-orthogonal fitting baseline and user-owned center MUST NOT move. */
	private boolean rejoinSettledSnappedSize(Rectangle expected, Rectangle nativeBounds, Rectangle rendered) {
		if (expected == null || nativeBounds == null || rendered == null || !nativeBounds.equals(rendered) || nativeBounds.width != expected.width || nativeBounds.height != expected.height || dialogueNativeSizeReflowStartingHost == null || !chatboxPlacement.verifiedNativeSnappedDialogueHost(rendered)) {
			dialogueSnappedReflowStableHost = null;
			dialogueSnappedReflowStableSamples = 0;
			dialogueSnappedReflowLastSampleCycle = Integer.MIN_VALUE;
			return false;
		}
		/* A size-only write should retain at least one original physical axis.
		 * A change on both axes may instead be an unclassified native move. */
		final Rectangle original = dialogueNativeSizeReflowStartingHost;
		if (nativeBounds.x != original.x && nativeBounds.y != original.y) {
			return false;
		}
		final int cycle = client.getGameCycle();
		if (dialogueSnappedReflowLastSampleCycle != cycle) {
			if (nativeBounds.equals(dialogueSnappedReflowStableHost)) {
				dialogueSnappedReflowStableSamples++;
			} else {
				dialogueSnappedReflowStableHost = new Rectangle(nativeBounds);
				dialogueSnappedReflowStableSamples = 1;
			}
			dialogueSnappedReflowLastSampleCycle = cycle;
		}
		if (cycle < dialogueNativeSizeReflowCycle || cycle - dialogueNativeSizeReflowCycle < SNAPPED_REFLOW_FALLBACK_WAIT_CYCLES || dialogueSnappedReflowStableSamples < SNAPPED_REFLOW_STABLE_SAMPLES) {
			return false;
		}

		final int dx = nativeBounds.x - expected.x;
		final int dy = nativeBounds.y - expected.y;
		if (dx == 0 && dy == 0) {
			return false;
		}
		final Rectangle desired = new Rectangle(geometryState.desiredBounds);
		final Rectangle effective = new Rectangle(geometryState.effectiveBounds);
		desired.translate(dx, dy);
		effective.translate(dx, dy);
		/* The viewport must have followed the same native displacement before
		 * we retire the hold; a partially laid out widget is not settled. */
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (universe == null || !effective.equals(universe.getBounds())) {
			return false;
		}
		geometryState = new GeometryState(geometryState.configuredWidth, geometryState.configuredHeight, desired, effective);
		if (dialogueFitActive) {
			dialogueFitGeometry = geometryState;
		}
		/* Keep dialogueOrthogonalFitBaseline untouched. It represents the
		 * original configured size/location, not the expanded native host. */
		dialogueCollisionFingerprint = Long.MIN_VALUE;

		resetSnappedSizeReflow();
		return true;
	}

	/* A moved snap can report the selected X/Y before RuneLite finishes its
	 * size-triggered re-anchor. A right/bottom canvas-anchored snap is already
	 * constrained on that axis; an interior snap is not. Do not retire an
	 * exact-looking size transaction early on its free axis. */
	private boolean awaitMovedSnappedSizeReflow(Rectangle expected) {
		if (expected == null || dialogueNativeSizeReflowStartingHost == null) {
			return false;
		}
		final Rectangle starting = dialogueNativeSizeReflowStartingHost;
		final boolean horizontalReanchor = starting.width != expected.width && starting.x > NATIVE_SNAP_REFLOW_TOLERANCE && starting.x + starting.width < client.getCanvasWidth() - NATIVE_SNAP_REFLOW_TOLERANCE;
		final boolean verticalReanchor = starting.height != expected.height && starting.y > NATIVE_SNAP_REFLOW_TOLERANCE && starting.y + starting.height < client.getCanvasHeight() - NATIVE_SNAP_REFLOW_TOLERANCE;
		if (!horizontalReanchor && !verticalReanchor) {
			return false;
		}
		final int cycle = client.getGameCycle();
		return cycle >= dialogueNativeSizeReflowCycle && cycle - dialogueNativeSizeReflowCycle < SNAPPED_REFLOW_FALLBACK_WAIT_CYCLES;
	}

	/* Rejoin only a verified native SNAP after a placement-mode transition.
	 * An unsettled native/widget handoff is held, never interpreted as a user
	 * drag, and never solved against the former desired rectangle. */
	private boolean holdOrRejoinSnappedDialogueHost() {
		/* A genuine placement-mode change supersedes this transient size
		 * transaction. Never wait for the former native mode to reappear. */
		final ChatboxPlacement.PlacementMode liveMode = chatboxPlacement.currentMode();
		if (dialogueNativeSizeReflowPending && (liveMode != ChatboxPlacement.PlacementMode.NATIVE && liveMode != ChatboxPlacement.PlacementMode.SNAPPED || !dialoguePrompts.isActive() || !dialogueFitActive && !dialogueOrthogonalRestorePending || dialogueNativeSnapTransitionTarget != null)) {
			resetSnappedSizeReflow();
		}
		/* Give the native widget one client cycle to adopt its requested size
		 * before sampling RuneLite-owned snapped/native physical position. */
		if (dialogueNativeSizeReflowPending && client.getGameCycle() == dialogueNativeSizeReflowCycle) {
			return true;
		}
		if (dialogueNativeSizeReflowPending && geometryState != null && (dialogueFitActive || dialogueOrthogonalRestorePending) && (chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.SNAPPED || chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.NATIVE) && dialogueNativeSnapTransitionTarget == null && !dialogueMouseHeld && !dialogueHostDragActive && !dialogueAwaitingNativeSave && !dialogueUnconfirmedDropSuspended) {
			final Widget slot = getSlot(getLayout());
			final Rectangle rendered = slot != null ? slot.getBounds() : null;
			final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
			final Rectangle expected = dialoguePhysicalHostBounds(geometryState);
			/* A size-only write may be visible before RuneLite updates X/Y.
			 * In particular, a bottom-right 300->460 growth briefly appears at
			 * x=707 instead of the FitSolver's correct right-anchored x=547.
			 * Do NOT adopt that intermediate position as the new fit baseline.
			 * NATIVE retains its existing readback policy, which already works. */
			final boolean snapped = liveMode == ChatboxPlacement.PlacementMode.SNAPPED;
			/* A 1-2px snapped reflow offset can be an intermediate position,
			 * not RuneLite's final anchor. The last test showed y=405 before
			 * RuneLite completed the selected y=403. Do not rebase the fit
			 * from that transient sample: wait for exact X/Y agreement.
			 * NATIVE keeps the unchanged size-only readback behavior. */
			final boolean snappedPositionSettled = !snapped || expected != null && nativeBounds != null && nativeBounds.x == expected.x && nativeBounds.y == expected.y;
			/* A moved snap may briefly match the orthogonal fitting-selected position and only
			 * later be clamped/re-anchored by RuneLite (5 -> 0 in the reported
			 * width-growth case). Wait through the same settle window before
			 * accepting exact readback on a physically free axis. Otherwise
			 * staleSnappedDialogueHost() clears the fit at the late re-anchor. */
			final boolean movedSnapStillSettling = snapped && snappedPositionSettled && awaitMovedSnappedSizeReflow(expected);
			if (rendered != null && rendered.equals(nativeBounds) && expected != null && nativeBounds.width == expected.width && nativeBounds.height == expected.height && snappedPositionSettled && !movedSnapStillSettling) {
				final int dx = nativeBounds.x - expected.x;
				final int dy = nativeBounds.y - expected.y;
				if (dx != 0 || dy != 0) {
					final Rectangle desired = new Rectangle(geometryState.desiredBounds);
					final Rectangle effective = new Rectangle(geometryState.effectiveBounds);
					desired.translate(dx, dy);
					effective.translate(dx, dy);
					geometryState = new GeometryState(geometryState.configuredWidth, geometryState.configuredHeight, desired, effective);
					if (dialogueFitActive) {
						dialogueFitGeometry = geometryState;
					}
					if (dialogueOrthogonalFitBaseline != null) {
						dialogueOrthogonalFitBaseline.translate(dx, dy);
					}
					dialogueCollisionFingerprint = Long.MIN_VALUE;
				}
				resetSnappedSizeReflow();
				return false;
			}
			if (snapped && rejoinSettledSnappedSize(expected, nativeBounds, rendered)) {
				return false;
			}

			/* Do not interpret a partially rendered native size update as a
			 * fresh placement or restore a stale X/Y over RuneLite. */
			return true;
		}
		if (!staleSnappedDialogueHost()) {
			return false;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle rendered = slot != null ? slot.getBounds() : null;
		final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
		final Rectangle previous = new Rectangle(geometryState.desiredBounds);
		final boolean safeToRejoin = !dialogueMouseHeld && !dialogueHostDragActive && !dialogueAwaitingNativeSave && !dialogueUnconfirmedDropSuspended && !unclassifiedNativeDialogueDrag() && chatboxPlacement.verifiedNativeSnappedDialogueHost(rendered) && (dialogueNativeSnapTransitionTarget == null || dialogueNativeSnapTransitionTarget.equals(rendered));
		if (!safeToRejoin) {
			return true;
		}

		final Point userCenter = dialogueSession.getCenter();
		/* Retire only ephemeral FIT geometry. clearDialogueFitState() does NOT
		 * clear DialogueSession, its confirmed center, or its original normal
		 * placement snapshot. Native snap owns the new physical baseline. */
		clearDialogueFitState();
		dialogueNativeSnapTransitionTarget = null;
		geometryState = null;
		chatboxBoundsTracker.reset();
		dialogueFitRetryStrict = true;
		dialogueCollisionFingerprint = Long.MIN_VALUE;

		return false;
	}

	/**
	 * ================================================================
	 * COLLISION & NATIVE REFLOW
	 * ================================================================
	 */
	private void reconcileDialogueCollisions(int width, int height) {
		resumeConfirmedNativeDrop();
		recoverUnconfirmedRuntimeFit(width, height);
		if (dialogueAwaitingNativeSave) {
			if (--dialogueNativeSaveWaitFrames <= 0) {
				/* OverlayManager can save a SNAPPED drop without firing the
				 * preferredLocation callback. If the actual saved/runtime
				 * placement agrees, complete the same confirmed-save path.
				 * Otherwise retain the existing safe unconfirmed fallback. */
				if (chatboxPlacement.rejoinSavedNativePlacement()) {

					onSavedChatboxPlacementChanged(width, height);
				} else if (!rejoinUnsavedPhysicalDrop(width, height)) {
					fallbackUnconfirmedNativeDrop(width, height);
				}
			}
			return;
		}
		if (dialogueUnconfirmedDropSuspended) {
			return;
		}
		/* The native drop may settle during a dialogue rebuild. Do not let a
		 * consumed collision-release event strand its user center on the prior
		 * gesture. This check does not move the host or resize the dialogue. */
		if (dialoguePrompts.isActive() && dialogueHostDragActive && !dialogueMouseHeld && releasedDialogueHostMoved()) {
			finishPhysicalDialogueDrag(width, height);
		}
		if (!dialoguePrompts.isActive() || dialogueTransitionPending || dialogueRelayoutDirty || loginGeometryPending || canvasResizePending || sideLayoutDepth > 0) {
			return;
		}

		/* Only a drag originating on the physical chatbox may move its anchor. */
		observeDialogueDragStart();
		if (unclassifiedNativeDialogueDrag()) {
			return;
		}
		if (dialogueHostDragActive) {
			followPhysicalDialogueDrag(width, height);
			if (!dialogueCollisionDropPending) {
				previewPhysicalDialogueCollision(width, height);
				dialogueCollisionDragPending = false;
				return;
			}

			/* Release the host to normal fitting, using the last physical drop. */
			finishPhysicalDialogueDrag(width, height);
		}

		adoptLiveDialogueHostPosition(width, height);
		if (holdOrRejoinSnappedDialogueHost() || holdUnverifiedManualDialogueHost()) {
			return;
		}

		/*
		 * RuneLite can update a dragged overlay's preferred location on AWT before
		 * its rendered bounds catch up. Keep live viewport reconciliation cheap,
		 * then allow one final render sample before the settled strict fit search.
		 */
		final boolean dropPending = dialogueCollisionDropPending;
		if (dropPending) {
			dialogueCollisionDropPending = false;
			dialogueCollisionDropFrames = 2;
			if (!dialogueDropSettlePending) {
				/* Obstacle drags also get one settled snapshot, not one per frame. */
				dialogueDropSettlePending = true;
				dialogueDropSettleDelay = 2;
				dialogueDropSettleAlignmentWaits = 0;
				dialogueDropSample = null;
			}
		}

		final boolean dragPending = dialogueCollisionDragPending;
		dialogueCollisionDragPending = false;
		if (dragPending && !dropPending && dialogueCollisionDropFrames > 0) {
			dialogueCollisionDropFrames = 0;
		}

		boolean settled = false;
		if (dialogueCollisionDropFrames > 0) {
			dialogueCollisionDropFrames--;
			if (dialogueCollisionDropFrames == 0) {
				/* A successful post-drop shape search already used the final host.
				 * Do not immediately rerank that shape under a different retention
				 * policy. Obstacle solving and content-change checks still run. */
				if (!dialoguePostDropSearchCompleted) {
					dialogueFitRetryStrict = true;
				}
				dialogueCollisionFingerprint = Long.MIN_VALUE;
				settled = true;
			}
		}

		final boolean live = (dragPending || dialogueCollisionDropFrames > 0) && !settled;
		final long fingerprint = currentDialogueCollisionFingerprint();
		if (!live && !dropPending && !settled && fingerprint == dialogueCollisionFingerprint) {
			return;
		}
		final boolean retryStrict = dialogueFitRetryStrict;


		/*
		 * Preview frames must not rerun the strict candidate search. The settled
		 * frame does rerun it, even if a previous drag frame had the same fingerprint.
		 */
		dialogueCollisionLiveReflow = live;
		final ResizeResult result;
		try {
			result = applySizeInternal(width, height, true, null);
		} finally {
			dialogueCollisionLiveReflow = false;
		}
		if (!result.isApplied()) {
			return;
		}

		widthRefreshPending |= result.isWidthChanged();
		heightRefreshPending |= result.isHeightChanged();
		dialogueCollisionFingerprint = currentDialogueCollisionFingerprint();
	}

	private long currentDialogueCollisionFingerprint() {
		long fingerprint = InterfaceBounds.geometryFingerprint(client);
		fingerprint = fingerprint * 31L + dialogueResizeService.collisionOverlayFingerprint(overlayManager);
		/*
		 * Include the physical host only during an identified chatbox drag.
		 * Otherwise, clipping the host changes its own collision fingerprint,
		 * which can trigger another resolve and ratchet the viewport smaller.
		 */
		if (!dialogueHostDragActive) {
			return fingerprint;
		}

		final Widget slot = getSlot(getLayout());
		final Rectangle live = chatboxPlacement.hostBounds(slot);
		if (live == null) {
			return fingerprint;
		}

		fingerprint = fingerprint * 31L + live.x;
		fingerprint = fingerprint * 31L + live.y;
		fingerprint = fingerprint * 31L + live.width;
		return fingerprint * 31L + live.height;
	}

	private InterfaceBounds.Overrides liveDialogueCollisionOverrides(
			InterfaceBounds.Overrides base) {
		return dialogueResizeService.liveCollisionOverrides(overlayManager, getLayout(), base);
	}

	private void releaseNormalPresentationGuard() {
		releaseNormalPresentationGuard(true);
	}

	private void releaseNormalPresentationGuard(boolean normalChatReady) {
		final DialogueSession.PresentationRelease release = dialogueSession.releaseNormalPresentation(client, normalChatReady);
		recordMutations(release.getMutations());
	}

	private void reconcileDialogueBeforeRender(int width, int height) {
		snapshotDialogueHostHitBounds();
		observeDialogueDragStart();
		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		final boolean normalChatVisible = scrollArea != null && (!scrollArea.isHidden() || dialogueSession.isNormalPresentationHeld() && dialogueSession.isNormalWidgetGuarded(scrollArea));
		final DialogueSession.SurfaceObservation surfaceObservation = dialogueSession.observeSurface(client, dialoguePrompts.activeSurface(), normalChatVisible, dialogueTransitionPending, dialogueMouseHeld || dialogueHostDragActive, client.getGameCycle());
		recordMutations(surfaceObservation.getMutations());

		if (surfaceObservation.isSurfaceChanged() && dialogueSession.isPresent() && dialoguePrompts.isActive() && geometryState != null) {
			markDialoguePageRebuild();
		}


		/* A completed dialogue no longer owns a pending drag save. In
		 * particular, an unsaved RUNTIME_DRAG must not strand the original
		 * normal-chatbox restoration behind an unrelated future native
		 * save or require the user to reset the host manually. This only
		 * releases provisional dialogue ownership; it never adopts the
		 * dropped position or assigns a center. */
		releaseClosedDialogueNativeSaveWait();
		if (!dialoguePrompts.isActive() && dialogueAwaitingNativeSave) {
			fallbackUnconfirmedNativeDrop(width, height);
		}
		resumeConfirmedNativeDrop();
		if (dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended) {
			return;
		}
		if (unclassifiedNativeDialogueDrag()) {
			return;
		}
		/* A dialogue rebuild can consume the generic OVERLAY_RELEASE signal
		 * before collision reconciliation handles it. An already classified
		 * physical chatbox drag still must finish its own gesture. */
		if (dialogueHostDragActive && !dialogueMouseHeld && (dialogueCollisionDropPending || releasedDialogueHostMoved())) {
			finishPhysicalDialogueDrag(width, height);
		}
		if (dialogueHostDragActive) {
			previewPhysicalDialogueCollision(width, height);
			return;
		}
		if (!dialogueTransitionPending && !dialoguePrompts.isActive()) {
			if (dialogueSession.isPresent() && !dialogueSession.isClosureConfirmed()) {
				/* Options/NPC/player remount gap. Do not tear down the
				 * original center or restore the normal host mid-conversation. */
				return;
			}
			if (dialogueSession.isClosureConfirmed()) {
				dialogueSession.markRestoring();
			}
			if (dialogueFitActive && geometryState != null) {
				restoreConfiguredDialogueGeometry(geometryState);
			}
			restorePreDialogueNormalPlacement(width, height);
			tracePreDialogueRestoreReadback();
			/* Clear the dialogue center only once restoration has completed.
			 * An outstanding native readback still owns this session. */
			if (dialogueSession.getNormalPlacement() == null && dialogueSession.getRestoreExpected() == null) {
				if (dialogueSession.isClosureConfirmed() && dialogueEmergencyRestoreLastCycle != client.getGameCycle()) {
					dialogueEmergencyRestoreLastCycle = client.getGameCycle();
					++dialogueEmergencyRestoreWaitFrames;
				}
				if (dialogueSession.isClosureConfirmed() && dialogueEmergencyRestoreWaitFrames < DIALOGUE_SETTLED_ALIGNMENT_WAIT_FRAMES) {
					/* The physical host can be one frame behind the native
					 * reset. Allow its readback to settle before giving up
					 * on the last-resort center fallback. */
					return;
				}

				clearDialogueSessionCenter();
			}
			if (dialoguePrompts.needsPortraitReconcile()) {
				resetDialoguePrompts();
			}
			return;
		}

		adoptLiveDialogueHostPosition(width, height);
		final GeometryState dialogueGeometry = renderDialogueGeometry(width, height);
		if (dialogueTransitionPending) {
			if (dialogueGeometry != null && !matchesCommittedDialogueGeometry(dialogueGeometry)) {
				applyCommittedGeometry(dialogueGeometry, width, height, true);
			}
			if (!dialogueTransitionReady && dialoguePrompts.isActive()) {
				return;
			}
			clearDialogueTransition();
			syncDialoguePrompts();
			return;
		}

		if (dialogueGeometry != null && !matchesCommittedDialogueGeometry(dialogueGeometry)) {
			applyCommittedGeometry(dialogueGeometry, width, height, true);
			verifyOrthogonalRestoreReadback(dialogueGeometry);
			return;
		}
		verifyOrthogonalRestoreReadback(dialogueGeometry);

		if (dialoguePrompts.needsPortraitReconcile()) {
			syncDialoguePrompts();
		}
	}

	public String chatboxGeometryTrace() {
		final GeometryState committed = geometryState;
		if (committed == null || !dialoguePrompts.isActive()) {
			return null;
		}
		final Widget slot = getSlot(getLayout());
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		return "desired=" + rectangleBounds(committed.desiredBounds) + " | effective=" + rectangleBounds(committed.effectiveBounds) + " | expectedHost=" + rectangleBounds(dialoguePhysicalHostBounds(committed)) + " | slotModel=" + rectangleBounds(chatboxPlacement.hostBounds(slot)) + " | slotRendered=" + rectangleBounds(slot != null ? slot.getBounds() : null) + " | universeRendered=" + rectangleBounds(universe != null ? universe.getBounds() : null) + " | nativeDrag=" + dialogueHostDragActive + " | relayoutDirty=" + dialogueRelayoutDirty;
	}

	private void verifyOrthogonalRestoreReadback(GeometryState committed) {
		if (!dialogueOrthogonalRestorePending || committed == null || dialogueHostDragActive) {
			return;
		}

		final Widget slot = getSlot(getLayout());
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (slot == null || universe == null) {
			return;
		}

		final Rectangle expectedHost = dialoguePhysicalHostBounds(committed);
		final boolean modelMatches = matchesCommittedDialogueGeometry(committed);
		final boolean renderedMatches = expectedHost.equals(slot.getBounds());
		final boolean viewportMatches = committed.effectiveBounds.equals(universe.getBounds());

		/* A configured-size restore may release its temporary host token before
		 * native and rendered geometry have been verified. Only accept a released
		 * transaction with evidence captured before that token was discarded. */
		final boolean ownedBaseline = dialogueOrthogonalRestoreTarget != null && dialogueOrthogonalRestoreTarget.equals(expectedHost) && !dialogueUnconfirmedDropSuspended && chatboxPlacement.temporaryHostRestoredToOwnedA2Baseline(dialogueReleasedRestoreEvidence, expectedHost);
		final boolean releasedVerified = !dialogueUnconfirmedDropSuspended && chatboxPlacement.releasedTemporaryHostRestoredToSavedPosition(dialogueReleasedRestoreEvidence, expectedHost);

		if (!modelMatches || !renderedMatches || !viewportMatches || !expectedHost.equals(chatboxPlacement.nativeOverlayBounds()) || dialogueAwaitingNativeSave || unclassifiedNativeDialogueDrag() || !(chatboxPlacement.temporaryHostRestoredToSavedPosition() || chatboxPlacement.temporaryHostRestoredToVerifiedGeometry(expectedHost) || chatboxPlacement.snappedDialogueHostRestored(expectedHost) || chatboxPlacement.nativeDialogueHostRestored(expectedHost) || ownedBaseline || releasedVerified)) {
			return;
		}

		dialogueOrthogonalRestorePending = false;
		dialogueOrthogonalRestoreTarget = null;
		dialogueReleasedRestoreEvidence = null;
		if (isDialogueTraceEnabled()) {
			dialogueDiagnostics.event("Dialogue Restore", "Verified host=" + expectedHost);
		}
	}

	private boolean matchesCommittedDialogueGeometry(GeometryState committed) {
		if (!matchesCommittedGeometry(committed)) {
			return false;
		}

		final Widget slot = getSlot(getLayout());
		final Rectangle live = chatboxPlacement.hostBounds(slot);
		final Rectangle host = dialoguePhysicalHostBounds(committed);
		return live != null && live.x == host.x && live.y == host.y;
	}

	private boolean matchesCommittedGeometry(GeometryState committed) {
		final ChatboxLayout layout = getLayout();
		if (!isResizableLayout(layout)) {
			return false;
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (slot == null || universe == null || chatArea == null) {
			return false;
		}

		final Widget parent = universe.getParent();
		if (parent == null || parent.getId() != slot.getId()) {
			return false;
		}

		final Rectangle desired = committed.desiredBounds;
		final Rectangle effective = committed.effectiveBounds;
		final Rectangle host = dialoguePhysicalHostBounds(committed);
		final int effectiveX = host.equals(effective) ? 0 : Math.max(0, effective.x - desired.x);
		final int effectiveY = host.equals(effective) ? 0 : Math.max(0, effective.y - desired.y);
		final int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
		return slot.getWidth() == host.width && slot.getHeight() == host.height && universe.getRelativeX() == effectiveX && universe.getRelativeY() == effectiveY && universe.getWidth() == effective.width && universe.getHeight() == effective.height && chatArea.getWidth() == effective.width && chatArea.getHeight() == bodyHeight && controlsMatch(effective.width);
	}

	public void refreshDialoguePrompts() {
		syncDialoguePrompts();
	}

	@Override
	protected void syncDialoguePrompts() {
		final GeometryState current = geometryState;
		if (current == null) {
			return;
		}

		syncDialoguePrompts(current);
	}

	private void syncDialoguePrompts(int effectiveWidth, int effectiveBodyHeight) {
		final GeometryState current = geometryState;
		if (current == null) {
			return;
		}

		syncDialoguePrompts(current);
	}

	private void syncDialoguePrompts(GeometryState current) {
		if (dialogueTransitionPending || current == null) {
			return;
		}

		DialoguePrompts.Result result = applyDialogueViewport(current);
		recordMutations(result.getMutations());
		recordRevalidates(result.getRevalidates());

		if (!dialogueFitResolving && liveDepth == 0 && reconcileDialogueFit()) {
			final GeometryState adjusted = geometryState;
			if (adjusted != null) {
				result = applyDialogueViewport(adjusted);
				recordMutations(result.getMutations());
				recordRevalidates(result.getRevalidates());
			}
		}
	}

	private DialoguePrompts.Result applyDialogueViewport(GeometryState current) {
		final Rectangle desired = current.desiredBounds;
		final Rectangle effective = current.effectiveBounds;
		final int effectiveBodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
		final int fingerprint = dialoguePrompts.fitFingerprint();
		if (fingerprint != dialogueClipContentFingerprint) {
			dialogueClipFromTop = false;
			dialogueClipOriginObserved = false;
			dialogueClipContentFingerprint = fingerprint;
		}

		final boolean topCut = effective.y > desired.y;
		final boolean bottomCut = effective.y + effective.height < desired.y + desired.height;
		if (topCut && !bottomCut) {
			dialogueClipFromTop = true;
			dialogueClipOriginObserved = true;
		} else if (bottomCut && !topCut) {
			dialogueClipFromTop = false;
			dialogueClipOriginObserved = true;
		} else if (!topCut && !bottomCut) {
			/* A partial fit can adopt its own reduced rectangle, so desired and
			 * effective become equal. An obstacle touching its edge is still a
			 * directional constraint even without a positive-area intersection.
			 * In particular, a button row flush with the bottom must preserve the
			 * name/top, rather than inheriting a stale bottom-corner preference. */
			final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(effective.width, dialogueFitConstrainedWrap);
			if (!requirement.isActive() || requirement.fitsMinimum(effective.width, effectiveBodyHeight)) {
				dialogueClipFromTop = false;
				dialogueClipOriginObserved = false;
			} else {
				final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
				final int touching = DialogueViewport.touchingVerticalObstacles(effective, InterfaceBounds.dialogueCollisionObstacles(client, overrides));
				if (touching == 4 || touching == 8) {
					dialogueClipFromTop = touching == 4;
					dialogueClipOriginObserved = true;
				} else if (!dialogueClipOriginObserved && dialogueFitClippedFallback) {
					dialogueClipFromTop = dialogueCornerBottom;
				}
			}
		}

		return dialoguePrompts.apply(effective.width, effectiveBodyHeight, dialogueFitConstrainedWrap, dialogueClipFromTop);
	}

	private boolean nativeBottomLeftDialogueFit(GeometryState current) {
		if (chatboxPlacement.currentMode() != ChatboxPlacement.PlacementMode.NATIVE || current == null || dialogueHostDragActive || dialogueAwaitingNativeSave) {
			return false;
		}
		final Rectangle anchor = dialogueNativeAutoFit && dialogueCornerReference != null ? dialogueCornerReference : current.desiredBounds;
		final int canvasHeight = client.getCanvasHeight();
		return anchor.x == 0 && canvasHeight > 0 && anchor.y + anchor.height == canvasHeight;
	}

	/**
	 * ================================================================
	 * FIT SELECTION & COMMIT
	 * ================================================================
	 */
	private boolean reconcileDialogueFit() {
		if (dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended || dialogueHostDragActive || unclassifiedNativeDialogueDrag()) {
			return false;
		}
		final GeometryState current = geometryState;
		if (current == null || loginGeometryPending || canvasResizePending || sideLayoutDepth > 0) {
			return false;
		}
		/* A fast close/reopen may arrive before RuneLite has rendered the
		 * normal-placement restoration. Do not seed a new session center from
		 * a transient or corrupted pre-restoration rectangle. */
		if (dialogueSession.getRestoreExpected() != null) {
			tracePreDialogueRestoreReadback();
			if (dialogueSession.getRestoreExpected() != null) {
				return false;
			}
		}
		/* Fit geometry must not use a host that the native renderer has not
		 * actually placed yet. Defer speculative search until the current
		 * viewport catches up; normal collision resolution still runs. */
		if (dialogueFitActive && !dialogueHostRenderAligned(current)) {
			return false;
		}

		// Repeated script/render callbacks cannot resolve an identical native
		// host mismatch. Wait for a changed physical host, content or obstacles.
		if (dialogueFitDeferredHostMismatch && sameDeferredHostMismatch(current)) {
			return false;
		}

		final DialoguePrompts.FitRequirement currentRequirement = dialoguePrompts.measureFit(current.effectiveBounds.width, dialogueFitConstrainedWrap);
		if (currentRequirement.isActive()) {
			capturePreDialogueNormalPlacement(current);
			/* Initialize from an aligned physical chatbox only when its
			 * durable normal center is unavailable. Do not use an obsolete
			 * normal center merely because its native save is pending. */
			ensureDialogueReferenceCenter(current.desiredBounds);
			if (isNormalChatboxPlacementPending() && dialogueSession.getNormalPlacement() == null && !dialogueSession.isPresent()) {
				return false;
			}
		}
		if (!currentRequirement.isActive()) {
			/* A missing dialogue widget during an NPC/player rebuild is not a
			 * conversation close. Keep the user-owned center and native host
			 * until the session controller confirms normal chat is back. */
			return false;
		}

		/* RuneLite AUTO renders from its own native position. With no saved
		 * preferredLocation there is no safe reversible offset for ChatXL.
		 * Preserve native host geometry rather than selecting an unreachable fit. */
		if (chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.NATIVE && !nativeBottomLeftDialogueFit(current)) {

			return dialogueFitActive && restoreConfiguredDialogueGeometry(current);
		}

		/* The original orthogonal fitting native host must be physically restored before
		 * standard fitting can choose another position. Otherwise a
		 * one-pixel center-rounding correction can change preferredLocation
		 * before the readback verifies ownership, blocking later orthogonal fitting fits. */
		if (dialogueOrthogonalRestorePending) {
			verifyOrthogonalRestoreReadback(current);
			if (dialogueOrthogonalRestorePending) {
				return false;
			}
		}

		final int currentFingerprint = dialoguePrompts.fitFingerprint();

		if (dialogueUserDropPinned && dialoguePinnedRejectedFitKey == pinnedDialogueFitSearchKey(current)) {
			return false;
		}
		final boolean contentChanged = dialogueFitActive && currentFingerprint != dialogueFitFingerprint;
		final boolean retryStrict = dialogueFitRetryStrict || dialoguePostDropSearchPending;
		final int currentBodyHeight = ChatboxGeometry.bodyHeight(current.effectiveBounds.height, chatboxButtonsHidden);
		/* Collision geometry owns the obstructed edge; a separate, bounded fit
		 * pass may grow only the perpendicular axis. On a new dialogue page,
		 * do not commit vertical-only released-space growth before the full
		 * width/height fit search has considered the new content. */
		/* A new page gets the full best-fit shape search before any
		 * orthogonal recovery from the previous page's clipped rectangle. */
		final DialogueFitProposal growthProposal = contentChanged ? null : selectDialogueGrowthProposal(current, currentRequirement, true);
		if (growthProposal != null) {
			return commitDialogueFitProposal(current, growthProposal);
		}
		/* A settled obstacle drag may mark retryStrict even after released-space
		 * growth has found a fully valid rectangle. Keep that fit instead of
		 * letting a second strict search replace it with a shorter fallback.
		 * Released partial fits are similarly retained until more room opens. */
		if (retainDialogueReleasedFit(current, currentRequirement, currentFingerprint)) {
			return false;
		}
		if (dialogueCollisionLiveReflow || dialogueMouseHeld && dialogueCollisionDragObserved) {
			return false;
		}
		if (!dialogueFitActive && currentRequirement.fitsMinimum(current.effectiveBounds.width, currentBodyHeight)) {
			return false;
		}

		if (dialogueFitActive && !contentChanged && !retryStrict && (dialogueFitClippedFallback || currentRequirement.fitsMinimum(current.effectiveBounds.width, currentBodyHeight))) {
			return false;
		}

		/* Live obstacle geometry owns which edge moves. In particular, the
		 * release/settled pass is no longer marked as a live drag even while
		 * an interface still overlaps the dialogue. Never turn that contact
		 * into a new X/Y candidate: let the collision resolver shrink the
		 * effective rectangle and restore it when the obstruction clears. */
		final Rectangle preferred = dialogueOrthogonalFitBaseline != null ? dialogueOrthogonalFitBaseline : current.desiredBounds;
		final Rectangle visible = current.effectiveBounds;
		final boolean collisionShrunk = !preferred.equals(visible);
		final InterfaceBounds.Overrides contactOverrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final boolean obstacleTouchesPreferred = !DialogueResizeService.clearDialogueObstacles(preferred, InterfaceBounds.dialogueCollisionObstacles(client, contactOverrides));
		/* Existing content keeps the collision-owned opposite edge. A NEW
		 * page may choose a new compact preferred shape from the persistent
		 * dialogue center, then clip it against those same obstacles. */
		if ((collisionShrunk || obstacleTouchesPreferred) && !contentChanged) {

			return false;
		}

		final boolean postDrop = dialoguePostDropSearchPending;
		dialoguePostDropShapeSearch = postDrop;
		final DialogueFitCandidate candidate;
		try {
			candidate = findDialogueFitCandidate(current, !contentChanged && !postDrop);
		} finally {
			dialoguePostDropShapeSearch = false;
		}
		if (candidate == null) {
			/* A renderer mismatch is not a failed fit; retry after host reconciliation. */
			if (dialogueFitDeferredHostMismatch) {
				dialogueFitRetryStrict = true;
			} else {
				dialogueFitRetryStrict = false;
			}
			return false;
		}
		dialogueFitDeferredHostMismatch = false;
		final boolean unchanged = candidate.result.getDesiredBounds().equals(current.desiredBounds) && candidate.result.getEffectiveBounds().equals(current.effectiveBounds);
		final boolean committed = commitDialogueFitProposal(current, new DialogueFitProposal(DialogueResizeService.FitPath.STANDARD_FIT, candidate, null, currentFingerprint, Long.MIN_VALUE));
		/* Do not declare a post-drop search complete if applying its new
		 * geometry failed. An already-accepted identical fit is complete. */
		if (committed || unchanged) {
			dialoguePostDropSearchPending = false;
			dialoguePostDropSearchCompleted |= postDrop;
		}
		return committed;
	}

	/*
	 * Observe collision candidates without acquiring, moving,
	 * or resizing the RuneLite host. This runs only at a settled placement;
	 * live shrinking and recovery remain exclusively in ChatboxBounds.
	 */

	/*
	 * Orthogonal fitting ownership is established by a live dialogue session and an aligned
	 * physical/native host, NOT by comparing the user-owned center to that
	 * host's midpoint. The center may legitimately lie outside a clipped or
	 * expanded viewport and must never be rebased by a speculative fit.
	 * Native-save and drag handoff guards run before the orthogonal fitting proposal.
	 */
	private boolean hasVerifiedFirstContactReference(GeometryState current) {
		if (current == null || !dialogueSession.isPresent() || dialogueSession.getCenter() == null || current.desiredBounds == null || !dialogueHostRenderAligned(current)) {
			return false;
		}
		final Rectangle physicalHost = dialoguePhysicalHostBounds(current);
		final Rectangle nativeHost = chatboxPlacement.nativeOverlayBounds();
		if (physicalHost == null || nativeHost == null || !physicalHost.equals(nativeHost)) {
			return false;
		}
		final ChatboxPlacement.PlacementMode mode = chatboxPlacement.currentMode();
		if (mode == ChatboxPlacement.PlacementMode.SNAPPED) {
			return chatboxPlacement.canResizeSnappedDialogueHost(physicalHost, physicalHost);
		}
		if (mode == ChatboxPlacement.PlacementMode.NATIVE) {
			return nativeBottomLeftDialogueFit(current) && chatboxPlacement.canResizeNativeDialogueHost(physicalHost, physicalHost);
		}
		/* MANUAL placement is eligible only while both native and rendered
		 * hosts agree. A confirmed drag is owned by DialogueSession, never
		 * inferred from native displacement or speculative fit geometry. */
		return mode == ChatboxPlacement.PlacementMode.MANUAL;
	}

	/*
	 * Orthogonal fitting evaluates a single-side collision against the selected page's
	 * physical host (at least the configured size), never against the
	 * already-narrowed viewport. LEFT/RIGHT grows height; TOP/BOTTOM grows
	 * width. Centered full fits win; when a nearby obstacle blocks centering,
	 * only growth toward that obstacle is accepted (including safe partial
	 * growth with the content-clipping outline).
	 */
	private DialogueFitProposal proposeFirstContactCollisionFit(GeometryState current, DialoguePrompts.FitRequirement requirement) {
		if (current == null || requirement == null || !requirement.isActive() || dialogueHostDragActive || dialogueMouseHeld || activeObstacleDialogueDrag() || dialogueCollisionLiveReflow || dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended || unclassifiedNativeDialogueDrag() || dialogueOrthogonalRestorePending || !dialogueHostRenderAligned(current)) {
			return null;
		}
		if (dialogueOrthogonalFitBaseline == null && !hasVerifiedFirstContactReference(current)) {

			return null;
		}

		final ChatboxPlacement.PlacementMode fitMode = chatboxPlacement.currentMode();
		if (fitMode != ChatboxPlacement.PlacementMode.MANUAL && fitMode != ChatboxPlacement.PlacementMode.SNAPPED && fitMode != ChatboxPlacement.PlacementMode.NATIVE) {
			return null;
		}
		if (fitMode == ChatboxPlacement.PlacementMode.NATIVE && !nativeBottomLeftDialogueFit(current)) {
			return null;
		}
		final boolean snapped = fitMode == ChatboxPlacement.PlacementMode.SNAPPED;
		/* Native AUTO may already have shrunk its physical outer host to
		 * the collision-safe viewport. Its size-only transaction must be
		 * verified against that actual host, not the larger logical orthogonal fitting
		 * baseline. The baseline remains unchanged for candidate solving. */
		final Rectangle nativeResizeReference = fitMode == ChatboxPlacement.PlacementMode.NATIVE ? dialoguePhysicalHostBounds(current) : current.desiredBounds;
		final Rectangle desired = dialogueOrthogonalFitBaseline != null ? dialogueOrthogonalFitBaseline : current.desiredBounds;
		/* A standard full fit may already have grown the page to 300x260
		 * before a tab row cuts its bottom. Preserve that selected host as
		 * the orthogonal fitting baseline rather than restricting recovery to 300x200. */
		if (desired.width < current.configuredWidth || desired.height < current.configuredHeight || requirement.fitsMinimum(current.effectiveBounds.width, ChatboxGeometry.bodyHeight(current.effectiveBounds.height, chatboxButtonsHidden))) {
			return null;
		}
		final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final List<Rectangle> obstacles = InterfaceBounds.dialogueCollisionObstacles(client, overrides);
		final Widget slot = getSlot(getLayout());
		final Point sessionCenter = dialogueSession.getCenter();
		if (slot == null || sessionCenter == null) {
			return null;
		}
		/* After a partial orthogonal fitting growth, re-solve the ORIGINAL host against the
		 * current obstacles. This retains the same single-side ownership as
		 * the obstacle retreats, without reinterpreting the expanded host as
		 * a new user placement. */
		final Rectangle effective;
		if (dialogueOrthogonalFitBaseline == null) {
			effective = current.effectiveBounds;
		} else {
			final ChatboxPlacement.State baselinePlacement = chatboxPlacement.captureCornerAnchoredDialogue(desired, false, false);
			final DialogueFitCandidate baselineCandidate = dialogueResizeService.resolveCandidate(slot, baselinePlacement, desired.width, desired.height, current.configuredWidth, current.configuredHeight, chatboxBoundsTracker.copy(), overrides, null, dialogueFitConstrainedWrap, chatboxButtonsHidden, null);
			if (baselineCandidate == null || !baselineCandidate.topologyValid || !desired.equals(baselineCandidate.result.getDesiredBounds())) {
				return null;
			}
			effective = baselineCandidate.result.getEffectiveBounds();
		}
		if (desired.equals(effective)) {
			return null;
		}
		final int edges = DialogueResizeService.activeOrthogonalEdges(desired, effective, obstacles);
		if (edges != DialogueFitSolver.COLLISION_LEFT && edges != DialogueFitSolver.COLLISION_RIGHT && edges != DialogueFitSolver.COLLISION_TOP && edges != DialogueFitSolver.COLLISION_BOTTOM) {
			return null;
		}
		final boolean growHeight = edges == DialogueFitSolver.COLLISION_LEFT || edges == DialogueFitSolver.COLLISION_RIGHT;
		final int tabHeight = chatboxButtonsHidden ? 0 : ChatboxGeometry.NATIVE_TAB_HEIGHT;
		final DialogueFitSolver.CollisionRequirements requirements = new DialogueFitSolver.CollisionRequirements() {
			@Override
			public int minimumSlotHeight(int width) {
				return dialoguePrompts.measureFit(width, dialogueFitConstrainedWrap).getMinimumBodyHeight() + tabHeight;
			}

			@Override
			public boolean fits(int width, int slotHeight) {
				final DialoguePrompts.FitRequirement measured = dialoguePrompts.measureFit(width, dialogueFitConstrainedWrap);
				return measured.isActive() && measured.fitsMinimum(width, ChatboxGeometry.bodyHeight(slotHeight, chatboxButtonsHidden));
			}
		};
		final DialogueFitSolver.CollisionInspection inspected = DialogueFitSolver.inspectAnchoredFirstContact(desired, effective, sessionCenter, client.getCanvasWidth(), client.getCanvasHeight(), obstacles, edges, requirements, host -> {
				if (snapped && !chatboxPlacement.canResizeSnappedDialogueHost(current.desiredBounds, host)) {
					return new DialogueFitSolver.CollisionResolved(null, null, false, "SNAP_HOST_UNVERIFIED");
				}
				if (fitMode == ChatboxPlacement.PlacementMode.NATIVE && !chatboxPlacement.canResizeNativeDialogueHost(nativeResizeReference, host)) {
					return new DialogueFitSolver.CollisionResolved(null, null, false, "NATIVE_HOST_UNVERIFIED");
				}
				final ChatboxPlacement.State placement = chatboxPlacement.captureCornerAnchoredDialogue(host, false, false);
				final DialogueFitCandidate candidate = dialogueResizeService.resolveCandidate(slot, placement, host.width, host.height, current.configuredWidth, current.configuredHeight, chatboxBoundsTracker.copy(), overrides, null, dialogueFitConstrainedWrap, chatboxButtonsHidden, null);
				return candidate != null
						? new DialogueFitSolver.CollisionResolved(candidate.result.getDesiredBounds(), candidate.result.getEffectiveBounds(), candidate.topologyValid)
						: null;
		});
		if (inspected.proposedHost == null || inspected.proposedEffective == null) {
			return null;
		}
		final Rectangle host = inspected.proposedHost;
		final Rectangle visible = inspected.proposedEffective;
		/* Orthogonal fitting preserves the complete page host on the blocked axis;
		 * orthogonal growth cannot adopt the inward-clipped viewport as a new
		 * owner of either dimension. Its candidate must improve the visible
		 * space without shifting the collision-protected edge. */
		final boolean correctAxis = growHeight
				? host.x == desired.x && host.width == desired.width && host.height > current.desiredBounds.height && visible.x == effective.x && visible.width == effective.width
				: host.y == desired.y && host.height == desired.height && host.width > current.desiredBounds.width && visible.y == effective.y && visible.height == effective.height;
		if (!correctAxis || !visible.contains(current.effectiveBounds) || snapped && !chatboxPlacement.canResizeSnappedDialogueHost(current.desiredBounds, host) || fitMode == ChatboxPlacement.PlacementMode.NATIVE && !chatboxPlacement.canResizeNativeDialogueHost(nativeResizeReference, host)) {
			return null;
		}
		final ChatboxPlacement.State placement = chatboxPlacement.captureCornerAnchoredDialogue(host, false, false);
		final DialogueFitCandidate candidate = dialogueResizeService.resolveCandidate(slot, placement, host.width, host.height, current.configuredWidth, current.configuredHeight, chatboxBoundsTracker.copy(), overrides, null, dialogueFitConstrainedWrap, chatboxButtonsHidden, null);
		if (candidate == null || !candidate.topologyValid || !host.equals(candidate.result.getDesiredBounds()) || !visible.equals(candidate.result.getEffectiveBounds()) || (inspected.complete && !requirements.fits(visible.width, visible.height)) || !dialogueResizeService.pinnedDialogueOriginPreserved(pinnedFitState(current, DialogueResizeService.FitPath.ORTHOGONAL_GROW), candidate)) {
			return null;
		}
		if (!inspected.complete) {
			candidate.clippedFallback = true;
		}

		return new DialogueFitProposal(DialogueResizeService.FitPath.ORTHOGONAL_GROW, candidate, desired, dialoguePrompts.fitFingerprint(), orthogonalSearchFingerprint(desired, effective, dialoguePrompts.fitFingerprint()), true);
	}

	private DialogueFitProposal selectDialogueGrowthProposal(GeometryState current, DialoguePrompts.FitRequirement requirement, boolean allowReleasedGrowth) {
		/* Simultaneous edge contacts own both axes. A proposed fit must not
		 * expand the anchored right/top (or equivalent opposite) edges simply
		 * to avoid clipping: keep the inward-shrunk minimum-safe rectangle. */
		final Rectangle preferred = dialogueOrthogonalFitBaseline != null ? dialogueOrthogonalFitBaseline : current.desiredBounds;
		final Rectangle visible = current.effectiveBounds;
		if (!preferred.equals(visible)) {
			final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
			final List<Rectangle> obstacles = InterfaceBounds.dialogueCollisionObstacles(client, overrides);
			final int edges = DialogueResizeService.activeOrthogonalEdges(preferred, visible, obstacles);
			if ((edges & 3) != 0 && (edges & 12) != 0) {

				return null;
			}
		}

		final DialogueFitProposal firstContact = proposeFirstContactCollisionFit(current, requirement);
		if (firstContact != null) {
			return firstContact;
		}
		/* An orthogonal fitting session retains the original physical host as its baseline.
		 * Once that ownership is active, only orthogonal fitting's next directional growth
		 * or verified baseline restoration is allowed. Neither legacy
		 * growth nor released-space fitting may escape into the opposite
		 * direction merely to hide the clipping outline. */
		if (dialogueFirstContactHostPreserved && dialogueOrthogonalFitBaseline != null) {
			return proposeDialogueOrthogonalGrowth(current, requirement);
		}
		/* Never fall through to legacy effective-as-host growth from a
		 * stable page-sized collision. The page may already be larger
		 * than configured after standard fitting. If orthogonal fitting cannot validate
		 * a candidate, preserve clipping rather than relocating it. */
		if (dialogueOrthogonalFitBaseline == null && dialogueSession.isPresent() && (chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.MANUAL || chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.SNAPPED || chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.NATIVE) && current.desiredBounds.width >= current.configuredWidth && current.desiredBounds.height >= current.configuredHeight && !current.desiredBounds.equals(current.effectiveBounds)) {
			/* An unresolved native handoff must not reopen the older
			 * effective-viewport-as-host fallback. Keep inward clipping
			 * until a physically verified orthogonal fitting candidate can be committed. */
			return null;
		}
		final DialogueFitProposal orthogonal = proposeDialogueOrthogonalGrowth(current, requirement);
		if (orthogonal != null) {
			if (dialogueResizeService.pinnedDialogueOriginPreserved(pinnedFitState(current, orthogonal.reason), orthogonal.candidate)) {
				return orthogonal;
			}

			dialogueOrthogonalFailedFingerprint = orthogonal.failureFingerprint;
			dialogueOrthogonalFailedContentFingerprint = orthogonal.contentFingerprint;
			dialogueOrthogonalFailedAtNanos = System.nanoTime();
		}
		if (!allowReleasedGrowth) {
			return null;
		}
		final DialogueFitProposal released = proposeDialogueReleasedGrowth(current, requirement);
		if (released != null && !dialogueResizeService.pinnedDialogueOriginPreserved(pinnedFitState(current, released.reason), released.candidate)) {

			dialogueReleasedGrowthFailedFingerprint = released.failureFingerprint;
			dialogueReleasedGrowthFailedAtNanos = System.nanoTime();
			return null;
		}
		return released;
	}

	private DialogueResizeService.PinnedFitState pinnedFitState(GeometryState current, DialogueResizeService.FitPath reason) {
		final boolean orthogonal = reason == DialogueResizeService.FitPath.ORTHOGONAL_GROW || reason == DialogueResizeService.FitPath.ORTHOGONAL_RESTORE;
		final InterfaceBounds.Overrides overrides = orthogonal && dialogueUserDropPinned && dialogueConfirmedDropBounds != null && current != null ? liveDialogueCollisionOverrides(collisionInterfaceOverrides) : null;
		return new DialogueResizeService.PinnedFitState(dialogueUserDropPinned, dialogueConfirmedDropBounds, current != null ? current.desiredBounds : null, current != null ? current.effectiveBounds : null, dialogueSession.getCenter(), dialoguePrompts.fitFingerprint() != dialogueFitFingerprint, dialoguePostDropSearchPending, reason, dialogueOrthogonalFitBaseline, dialogueOrthogonalBaselineContentFingerprint == dialoguePrompts.fitFingerprint(), client.getCanvasWidth(), client.getCanvasHeight(), overrides);
	}

	private long pinnedDialogueFitSearchKey(GeometryState current) {
		long key = currentDialogueCollisionFingerprint();
		key = key * 31L + dialoguePrompts.fitFingerprint();
		key = key * 31L + current.desiredBounds.hashCode();
		key = key * 31L + current.effectiveBounds.hashCode();
		key = key * 31L + current.configuredWidth;
		key = key * 31L + current.configuredHeight;
		key = key * 31L + client.getCanvasWidth();
		key = key * 31L + client.getCanvasHeight();
		key = key * 31L + (dialogueConfirmedDropBounds != null ? dialogueConfirmedDropBounds.hashCode() : 0);
		return key;
	}

	private boolean commitDialogueFitProposal(GeometryState current, DialogueFitProposal proposal) {
		/* A confirmed MANUAL drop is already the physical placement owner.
		 * A same-size, fully readable STANDARD_FIT is not permission to move
		 * the host back to a possibly distant session center. This is also
		 * true immediately after a post-drop strict search. The center is
		 * retained for ranking and genuine size changes, never reassigned. */
		if (proposal.reason == DialogueResizeService.FitPath.STANDARD_FIT && dialogueUserDropPinned && dialogueSession.hasConfirmedDrag() && !dialogueHostDragActive && !dialogueAwaitingNativeSave && chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.MANUAL && current.desiredBounds.equals(current.effectiveBounds) && proposal.candidate.constrainedWrap == dialogueFitConstrainedWrap) {
			final Rectangle proposed = proposal.candidate.result.getDesiredBounds();
			final Rectangle existing = current.desiredBounds;
			final DialoguePrompts.FitRequirement measured = dialoguePrompts.measureFit(current.effectiveBounds.width, dialogueFitConstrainedWrap);
			if (!existing.equals(proposed) && proposed.width == existing.width && proposed.height == existing.height && measured.isActive() && measured.fitsMinimum(current.effectiveBounds.width, ChatboxGeometry.bodyHeight(current.effectiveBounds.height, chatboxButtonsHidden)) && !unverifiedManualDialogueHost()) {
				dialogueFitRetryStrict = false;
				dialoguePostDropSearchCompleted |= dialoguePostDropSearchPending;
				dialoguePostDropSearchPending = false;

				return false;
			}
		}
		/* A clipped fallback must not teleport an already-dropped dialogue
		 * to a different center. A full centered fit may still legitimately
		 * resize in both directions; if no full fit exists, keep placement
		 * and let the collision/outline paths report the missing space. */
		if (proposal.reason == DialogueResizeService.FitPath.STANDARD_FIT && dialogueUserDropPinned && dialogueConfirmedDropBounds != null && dialogueSession.getCenter() != null) {
			final Rectangle selected = proposal.candidate.result.getDesiredBounds();
			final Rectangle visible = proposal.candidate.result.getEffectiveBounds();
			final DialoguePrompts.FitRequirement measured = dialoguePrompts.measureFit(visible.width, proposal.candidate.constrainedWrap);
			final boolean incomplete = !measured.fitsMinimum(visible.width, ChatboxGeometry.bodyHeight(visible.height, chatboxButtonsHidden));
			final Point dropCenter = new Point(dialogueConfirmedDropBounds.x + dialogueConfirmedDropBounds.width / 2, dialogueConfirmedDropBounds.y + dialogueConfirmedDropBounds.height / 2);
			final Point selectedCenter = new Point(selected.x + selected.width / 2, selected.y + selected.height / 2);
			final boolean relocated = Math.abs(selectedCenter.x - dropCenter.x) > DIALOGUE_FIT_STEP || Math.abs(selectedCenter.y - dropCenter.y) > DIALOGUE_FIT_STEP;
			if (incomplete && relocated) {
				dialoguePinnedRejectedFitKey = pinnedDialogueFitSearchKey(current);
				dialogueFitRetryStrict = false;
				dialoguePostDropSearchPending = false;

				return false;
			}
		}
		/* The temporary fit pin can be cleared by a native-save fallback,
		 * but the confirmed session center CANNOT be cleared by fitting.
		 * Every new-page STANDARD_FIT in a movable layout must derive its
		 * desired host from that center, even if the center lies outside the
		 * currently clipped effective viewport. Never use the viewport's
		 * shifted origin as a replacement for user-owned placement. */
		if (proposal.reason == DialogueResizeService.FitPath.STANDARD_FIT && dialogueSession.hasConfirmedDrag() && dialogueSession.getCenter() != null && (chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.MANUAL || chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.RUNTIME_DRAG)) {
			final Rectangle centered = chatboxPlacement.centeredDialogueBounds(dialogueSession.getCenter(), proposal.candidate.requestedWidth, proposal.candidate.requestedHeight);
			if (!centered.equals(proposal.candidate.result.getDesiredBounds())) {

				dialoguePinnedRejectedFitKey = pinnedDialogueFitSearchKey(current);
				return false;
			}
		}
		/* RuneLite owns SNAPPED X/Y. A same-size STANDARD_FIT must not
		 * translate an already verified snapped host toward the stored
		 * dialogue center: even 5px is enough to strand later collisions
		 * on an obsolete desired rectangle. Keep the center for ranking,
		 * not as an instruction to move the native snap. */
		if (proposal.reason == DialogueResizeService.FitPath.STANDARD_FIT && chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.SNAPPED && !dialogueOrthogonalRestorePending && current.desiredBounds.equals(current.effectiveBounds) && proposal.candidate.constrainedWrap == dialogueFitConstrainedWrap) {
			final Rectangle proposed = proposal.candidate.result.getDesiredBounds();
			final Rectangle actual = current.desiredBounds;
			final DialoguePrompts.FitRequirement measured = dialoguePrompts.measureFit(current.effectiveBounds.width, dialogueFitConstrainedWrap);
			final Widget slot = getSlot(getLayout());
			final Rectangle rendered = slot != null ? slot.getBounds() : null;
			if (proposed.width == actual.width && proposed.height == actual.height && measured.isActive() && measured.fitsMinimum(current.effectiveBounds.width, ChatboxGeometry.bodyHeight(current.effectiveBounds.height, chatboxButtonsHidden)) && actual.equals(rendered) && chatboxPlacement.verifiedNativeSnappedDialogueHost(rendered)) {
				dialogueFitRetryStrict = false;
				dialoguePostDropSearchPending = false;

				return false;
			}
		}
		/* All fit producers must honor the same confirmed user position. The
		 * collision resolver may still shrink the effective edge inward, but a
		 * complete text fit cannot translate the desired rectangle elsewhere. */
		if (!dialogueResizeService.pinnedDialogueOriginPreserved(pinnedFitState(current, proposal.reason), proposal.candidate)) {

			if (proposal.reason == DialogueResizeService.FitPath.STANDARD_FIT) {
				dialoguePinnedRejectedFitKey = pinnedDialogueFitSearchKey(current);
				dialogueFitRetryStrict = false;
				dialoguePostDropSearchPending = false;
			}
			return false;
		}
		dialoguePinnedRejectedFitKey = Long.MIN_VALUE;
		/* RuneLite can round a user-dropped native host by one pixel
		 * relative to the stored dialogue center. Once orthogonal fitting has restored
		 * that host and the existing viewport fully fits, a same-size
		 * STANDARD_FIT must not move preferredLocation merely to erase
		 * the rounding difference. The native saved position wins. */
		if (proposal.reason == DialogueResizeService.FitPath.STANDARD_FIT && dialogueFirstContactHostPreserved && !dialogueOrthogonalRestorePending && !dialogueFitClippedFallback && current.desiredBounds.equals(current.effectiveBounds) && proposal.candidate.constrainedWrap == dialogueFitConstrainedWrap && proposal.candidate.result.getEffectiveBounds().equals(proposal.candidate.result.getDesiredBounds()) && proposal.candidate.result.getDesiredBounds().width == current.desiredBounds.width && proposal.candidate.result.getDesiredBounds().height == current.desiredBounds.height && Math.abs(proposal.candidate.result.getDesiredBounds().x - current.desiredBounds.x) <= 1 && Math.abs(proposal.candidate.result.getDesiredBounds().y - current.desiredBounds.y) <= 1 && dialoguePrompts.measureFit(current.effectiveBounds.width, dialogueFitConstrainedWrap).fitsMinimum(current.effectiveBounds.width, ChatboxGeometry.bodyHeight(current.effectiveBounds.height, chatboxButtonsHidden))) {
			dialogueFitRetryStrict = false;
			dialoguePostDropSearchPending = false;

			return false;
		}
		/* An unchanged standard fit only needs its wrap/measurement state
		 * refreshed; do not reapply the same host rectangle. */
		if (proposal.reason == DialogueResizeService.FitPath.STANDARD_FIT && proposal.candidate.result.getDesiredBounds().equals(current.desiredBounds) && proposal.candidate.result.getEffectiveBounds().equals(current.effectiveBounds)) {
			final boolean wrapChanged = dialogueFitConstrainedWrap != proposal.candidate.constrainedWrap;
			final Rectangle visible = current.effectiveBounds;
			final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(visible.width, proposal.candidate.constrainedWrap);
			updateDialogueFitState(current, proposal.candidate, requirement);
			return wrapChanged;
		}

		final Point originalConfirmedCenter = proposal.firstContact && dialogueSession.getCenter() != null ? new Point(dialogueSession.getCenter()) : null;
		final boolean previousHostPreserved = dialogueFirstContactHostPreserved;
		/* Save native ownership evidence BEFORE applyDialogueFitCandidate()
		 * possibly calls clearDialogueFitState() and releases the token. */
		final ChatboxPlacement.TemporaryHostRestoreEvidence restoreEvidence = proposal.reason == DialogueResizeService.FitPath.ORTHOGONAL_RESTORE && previousHostPreserved ? chatboxPlacement.captureTemporaryHostRestoreEvidence() : null;
		if (proposal.firstContact) {
			dialogueFirstContactHostPreserved = true;
		}
		if (!applyDialogueFitCandidate(current, proposal.candidate, proposal.reason, proposal.firstContact)) {
			dialogueFirstContactHostPreserved = previousHostPreserved;

			if (proposal.reason == DialogueResizeService.FitPath.ORTHOGONAL_RESTORE) {
				dialogueOrthogonalFailedFingerprint = proposal.failureFingerprint;
				dialogueOrthogonalFailedAtNanos = System.nanoTime();
			} else if (proposal.reason == DialogueResizeService.FitPath.ORTHOGONAL_GROW) {
				dialogueOrthogonalFailedFingerprint = proposal.failureFingerprint;
				dialogueOrthogonalFailedContentFingerprint = proposal.contentFingerprint;
				dialogueOrthogonalFailedAtNanos = System.nanoTime();
			} else if (proposal.reason == DialogueResizeService.FitPath.RELEASED_GROW) {
				dialogueReleasedGrowthFailedFingerprint = proposal.failureFingerprint;
				dialogueReleasedGrowthFailedAtNanos = System.nanoTime();
			}
			return false;
		}

		if (proposal.reason == DialogueResizeService.FitPath.ORTHOGONAL_RESTORE) {
			/* Orthogonal fitting still owns the full-width physical host after recovery.
			 * Clearing this flag would let the next side collision replace
			 * it with the inward-shrunk effective viewport. */
			dialogueOrthogonalRestorePending = previousHostPreserved;
			dialogueOrthogonalRestoreTarget = previousHostPreserved && dialogueOrthogonalFitBaseline != null && dialogueOrthogonalFitBaseline.equals(proposal.baseline) ? new Rectangle(dialogueOrthogonalFitBaseline) : null;
			dialogueReleasedRestoreEvidence = previousHostPreserved ? restoreEvidence : null;
			dialogueOrthogonalFitBaseline = null;
			dialogueOrthogonalBaselineContentFingerprint = Integer.MIN_VALUE;
			clearOrthogonalSearchFailure();
		} else if (proposal.reason == DialogueResizeService.FitPath.ORTHOGONAL_GROW) {
			dialogueOrthogonalRestorePending = false;
			dialogueOrthogonalRestoreTarget = null;
			dialogueReleasedRestoreEvidence = null;
			if (dialogueFitActive && dialogueOrthogonalFitBaseline == null && proposal.baseline != null) {
				dialogueOrthogonalFitBaseline = new Rectangle(proposal.baseline);
				dialogueOrthogonalBaselineContentFingerprint = proposal.contentFingerprint;
			}
			clearOrthogonalSearchFailure();
		} else if (proposal.reason == DialogueResizeService.FitPath.RELEASED_GROW) {
			dialogueReleasedGrowthFailedFingerprint = Long.MIN_VALUE;
			dialogueReleasedFitRetained = new Rectangle(proposal.candidate.result.getEffectiveBounds());
			dialogueReleasedFitContentFingerprint = proposal.contentFingerprint;
		}

		return true;
	}

	private DialogueFitProposal proposeDialogueOrthogonalGrowth(GeometryState current, DialoguePrompts.FitRequirement currentRequirement) {
		if (!dialogueFitActive || dialogueHostDragActive || dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended || unclassifiedNativeDialogueDrag()) {
			return null;
		}
		final Rectangle desired = current.desiredBounds;
		final Rectangle visible = current.effectiveBounds;
		if (activeObstacleDialogueDrag() && !dialogueHostRenderAligned(current)) {
			return null;
		}
		/* A snapshot belongs to one dialogue page. Reusing it for longer
		 * content can produce a permanently rejected restoration candidate. */
		final int contentFingerprint = dialoguePrompts.fitFingerprint();
		if (dialogueOrthogonalFitBaseline != null && dialogueOrthogonalBaselineContentFingerprint != contentFingerprint) {
			dialogueOrthogonalFitBaseline = null;
			dialogueOrthogonalBaselineContentFingerprint = Integer.MIN_VALUE;
			clearOrthogonalSearchFailure();
		}
		final Rectangle original = dialogueOrthogonalFitBaseline != null ? dialogueOrthogonalFitBaseline : desired;
		final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final List<Rectangle> obstacles = InterfaceBounds.dialogueCollisionObstacles(client, overrides);
		final boolean originalContact = !DialogueResizeService.clearDialogueObstacles(original, obstacles);
		final boolean originalReduced = !original.equals(visible);

		if (!originalContact && dialogueOrthogonalFitBaseline != null) {
			/* A moving obstacle must not revive a saved position that RuneLite
			 * has not yet physically reconciled. Its same-origin height/width
			 * restoration is safe; displaced restoration waits for release. */
			if (activeObstacleDialogueDrag() && (original.x != visible.x || original.y != visible.y)) {
				return null;
			}
			/* Restore only if the snapshot can fit the CURRENT content. A
			 * failed recovery must not run thousands of times per second. */
			final Rectangle recovery = new Rectangle(dialogueOrthogonalFitBaseline);
			final DialoguePrompts.FitRequirement recoveryRequirement = dialoguePrompts.measureFit(recovery.width, dialogueFitConstrainedWrap);
			final boolean recoveryFits = recoveryRequirement.isActive() && recoveryRequirement.fitsMinimum(recovery.width, ChatboxGeometry.bodyHeight(recovery.height, chatboxButtonsHidden));
			if (!recoveryFits || recovery.equals(desired)) {
				dialogueOrthogonalFitBaseline = null;
				dialogueOrthogonalBaselineContentFingerprint = Integer.MIN_VALUE;
				clearOrthogonalSearchFailure();
				return null;
			}
			final long recoveryFingerprint = orthogonalSearchFingerprint(recovery, visible, contentFingerprint);
			final long recoveryNow = System.nanoTime();
			if (dialogueOrthogonalFailedFingerprint == recoveryFingerprint && recoveryNow - dialogueOrthogonalFailedAtNanos < ORTHOGONAL_FAILED_RETRY_NANOS) {
				return null;
			}
			final Widget slot = getSlot(getLayout());
			if (slot == null) {
				return null;
			}
			final ChatboxPlacement.State placement = chatboxPlacement.captureCornerAnchoredDialogue(recovery, false, false);
			final DialogueFitCandidate candidate = resolveDialogueCandidate(slot, placement, recovery.width, recovery.height, current.configuredWidth, current.configuredHeight, chatboxBoundsTracker.copy(), overrides, null, dialogueFitConstrainedWrap);
			if (candidate != null && candidate.topologyValid && recovery.equals(candidate.result.getEffectiveBounds())) {
				return new DialogueFitProposal(DialogueResizeService.FitPath.ORTHOGONAL_RESTORE, candidate, recovery, contentFingerprint, recoveryFingerprint);
			}
			dialogueOrthogonalFailedFingerprint = recoveryFingerprint;
			dialogueOrthogonalFailedAtNanos = recoveryNow;
			return null;
		}
		if (dialogueFirstContactHostPreserved && dialogueOrthogonalFitBaseline != null) {
			/* Orthogonal fitting alone owns this host until restoration. Legacy growth
			 * must not escape into the opposite orthogonal direction. */
			return null;
		}
		if (!originalContact || !originalReduced) {
			return null;
		}
		if (currentRequirement.fitsMinimum(visible.width, ChatboxGeometry.bodyHeight(visible.height, chatboxButtonsHidden))) {
			clearOrthogonalSearchFailure();
			return null;
		}

		/* During a drag the obstacles may move every frame. A failed full-fit
		 * search is never permission to stall the client or rerun the same
		 * measurements for every native script. New content bypasses the budget. */
		final long searchFingerprint = orthogonalSearchFingerprint(original, visible, contentFingerprint);
		final long now = System.nanoTime();
		if (dialogueOrthogonalFailedFingerprint == searchFingerprint || dialogueOrthogonalFailedContentFingerprint == contentFingerprint && now - dialogueOrthogonalFailedAtNanos < ORTHOGONAL_FAILED_RETRY_NANOS) {
			return null;
		}

		/* A prior collision can leave the visible rectangle displaced after that
		 * obstacle has moved away. The historical difference from the baseline
		 * does not mean the same edge is still obstructed: use the obstacles
		 * actually touching each reduced side before choosing a growth axis. */
		final boolean priorLeft = visible.x > original.x;
		final boolean priorRight = visible.x + visible.width < original.x + original.width;
		final boolean priorTop = visible.y > original.y;
		final boolean priorBottom = visible.y + visible.height < original.y + original.height;
		final int blockedEdges = DialogueResizeService.activeOrthogonalEdges(original, visible, obstacles);
		final boolean left = (blockedEdges & 1) != 0;
		final boolean right = (blockedEdges & 2) != 0;
		final boolean top = (blockedEdges & 4) != 0;
		final boolean bottom = (blockedEdges & 8) != 0;
		final boolean horizontalContact = left != right;
		final boolean verticalContact = top != bottom;

		/* If both axes are obstructed, preserve the opposite edges and
		 * let collision resolution shrink inward on both axes. A full-content
		 * candidate must not expand a protected edge to escape clipping. */
		if (left && right || top && bottom || horizontalContact && verticalContact) {
			return null;
		}
		if (!horizontalContact && !verticalContact) {
			return null;
		}
		final Widget slot = getSlot(getLayout());
		if (slot == null) {
			return null;
		}
		/* Only the opposite collision edge is fixed. Search either side of the
		 * perpendicular axis, plus a split expansion. All searches are bounded
		 * by canvas and nearby obstacles before measuring text or widgets. */
		final int tabHeight = chatboxButtonsHidden ? 0 : ChatboxGeometry.NATIVE_TAB_HEIGHT;
		final int up = dialogueResizeService.orthogonalFreeSpace(visible, obstacles, 0);
		final int down = dialogueResizeService.orthogonalFreeSpace(visible, obstacles, 1);
		final int towardLeft = dialogueResizeService.orthogonalFreeSpace(visible, obstacles, 2);
		final int towardRight = dialogueResizeService.orthogonalFreeSpace(visible, obstacles, 3);
		final int bodyHeight = ChatboxGeometry.bodyHeight(visible.height, chatboxButtonsHidden);
		int goal;
		boolean partial = false;
		if (horizontalContact) {
			// More height cannot make an unrenderably narrow text column fit.
			if (visible.width < currentRequirement.getMinimumWidth()) {
				return null;
			}
			final int requiredHeight = Math.max(visible.height, DialogueFitSolver.alignGrowth(currentRequirement.getMinimumBodyHeight() + tabHeight, current.configuredHeight));
			goal = Math.min(requiredHeight, visible.height + up + down);
			partial = goal < requiredHeight || visible.width < currentRequirement.getMinimumWidth();
		} else {
			final int maximumWidth = visible.width + towardLeft + towardRight;
			if (maximumWidth <= visible.width) {
				return null;
			}
			/* Text height normally decreases monotonically as the line width
			 * increases. A single farthest-width check, followed by binary
			 * search, replaces the old unbounded four-pixel scan. */
			final DialoguePrompts.FitRequirement maximumRequirement = dialoguePrompts.measureFit(maximumWidth, dialogueFitConstrainedWrap);
			if (maximumRequirement.isActive() && maximumRequirement.fitsMinimum(maximumWidth, bodyHeight)) {
				int low = 1;
				int high = (maximumWidth - visible.width + DIALOGUE_FIT_STEP - 1) / DIALOGUE_FIT_STEP;
				while (low < high) {
					final int middle = low + (high - low) / 2;
					final int width = Math.min(maximumWidth, visible.width + middle * DIALOGUE_FIT_STEP);
					final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(width, dialogueFitConstrainedWrap);
					if (requirement.isActive() && requirement.fitsMinimum(width, bodyHeight)) {
						high = middle;
					} else {
						low = middle + 1;
					}
				}
				goal = Math.min(maximumWidth, visible.width + low * DIALOGUE_FIT_STEP);
			} else {
				/* A failed complete fit must not stretch across the entire
				 * canvas. Try a useful, bounded partial improvement instead. */
				goal = Math.min(maximumWidth, Math.max(visible.width, original.width + DIALOGUE_PARTIAL_GROWTH_LIMIT));
				partial = true;
			}
		}
		if (goal <= (horizontalContact ? visible.height : visible.width) + DIALOGUE_FIT_STEP - 1) {
			return null;
		}

		DialogueFitCandidate best = null;
		long bestDistance = Long.MAX_VALUE;
		/* Explore both individual directions and the centered combination.
		 * All three must preserve the edge owned by the collision resolver. */
		for (int direction = 0; direction < 3; direction++) {
			/* During active obstacle movement, never move the dialogue's origin
			 * as part of a speculative perpendicular-axis expansion. Edge
			 * reduction is still handled live by ChatboxBounds.resolve(). */
			if (direction != 0 && activeObstacleDialogueDrag()) {
				continue;
			}
			final int added = goal - (horizontalContact ? visible.height : visible.width);
			final int negativeRoom = horizontalContact ? up : towardLeft;
			final int positiveRoom = horizontalContact ? down : towardRight;
			final int negativeGrowth;
			if (direction == 0) {
				negativeGrowth = 0;
			} else if (direction == 1) {
				negativeGrowth = added;
			} else {
				negativeGrowth = Math.max(0, Math.min(negativeRoom, Math.max(added - positiveRoom, added / 2)));
			}
			if (negativeGrowth > negativeRoom || added - negativeGrowth > positiveRoom) {
				continue;
			}
			final Rectangle proposed = horizontalContact
					? new Rectangle(visible.x, visible.y - negativeGrowth, visible.width, goal)
					: new Rectangle(visible.x - negativeGrowth, visible.y, goal, visible.height);
			final DialogueFitCandidate candidate = clearOrthogonalDialogueCandidate(slot, chatboxPlacement.captureCornerAnchoredDialogue(proposed, false, false), proposed.width, proposed.height, visible, left, right, top, bottom, overrides, obstacles, current, partial);
			if (candidate == null) {
				continue;
			}
			/* Prefer the least movement of the rectangle center, then the
			 * smallest shift from the previously accepted temporary fit. */
			final Rectangle fitted = candidate.result.getEffectiveBounds();
			final long centerDistance = horizontalContact ? Math.abs(2L * fitted.y + fitted.height - (dialogueSession.getCenter() != null ? 2L * dialogueSession.getCenter().y : 2L * original.y + original.height)) : Math.abs(2L * fitted.x + fitted.width - (dialogueSession.getCenter() != null ? 2L * dialogueSession.getCenter().x : 2L * original.x + original.width));
			final long retainedDistance = horizontalContact ? Math.abs(fitted.y - desired.y) : Math.abs(fitted.x - desired.x);
			final long score = centerDistance * 4L + retainedDistance;
			if (best == null || score < bestDistance) {
				best = candidate;
				bestDistance = score;
			}
		}
		if (best == null || best.result.getDesiredBounds().equals(desired) && best.result.getEffectiveBounds().equals(visible)) {
			dialogueOrthogonalFailedFingerprint = searchFingerprint;
			dialogueOrthogonalFailedContentFingerprint = contentFingerprint;
			dialogueOrthogonalFailedAtNanos = System.nanoTime();

			return null;
		}
		// Do not change the preferred baseline until the host accepts the fit.
		return new DialogueFitProposal(DialogueResizeService.FitPath.ORTHOGONAL_GROW, best, original, contentFingerprint, searchFingerprint);
	}

	private boolean retainDialogueReleasedFit(GeometryState current, DialoguePrompts.FitRequirement requirement, int contentFingerprint) {
		if (dialogueReleasedFitRetained == null || !dialogueFitRetryStrict) {
			/* The normal unchanged-fit path already retains the candidate.
			 * Avoid re-reading obstacle overlays every render frame. */
			return false;
		}
		final Rectangle visible = current.effectiveBounds;
		final int bodyHeight = ChatboxGeometry.bodyHeight(visible.height, chatboxButtonsHidden);
		if (!dialogueFitActive || dialoguePostDropSearchPending || dialogueReleasedFitContentFingerprint != contentFingerprint || contentFingerprint != dialogueFitFingerprint || !dialogueReleasedFitRetained.equals(visible) || !requirement.isActive() || !dialogueFitClippedFallback && !requirement.fitsMinimum(visible.width, bodyHeight)) {
			clearDialogueReleasedFit();
			return false;
		}

		final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final List<Rectangle> obstacles = InterfaceBounds.dialogueCollisionObstacles(client, overrides);
		if (!DialogueResizeService.clearDialogueObstacles(visible, obstacles)) {
			clearDialogueReleasedFit();
			return false;
		}

		/* Once the original selected rectangle becomes unobstructed and fits
		 * the current page, give restoration priority over this temporary growth. */
		if (dialogueOrthogonalFitBaseline != null && dialogueOrthogonalBaselineContentFingerprint == contentFingerprint && !dialogueOrthogonalFitBaseline.equals(visible) && DialogueResizeService.clearDialogueObstacles(dialogueOrthogonalFitBaseline, obstacles)) {
			final DialoguePrompts.FitRequirement baselineRequirement = dialoguePrompts.measureFit(dialogueOrthogonalFitBaseline.width, dialogueFitConstrainedWrap);
			if (baselineRequirement.isActive() && baselineRequirement.fitsMinimum(dialogueOrthogonalFitBaseline.width, ChatboxGeometry.bodyHeight(dialogueOrthogonalFitBaseline.height, chatboxButtonsHidden))) {
				clearDialogueReleasedFit();
				return false;
			}
		}

		if (dialogueFitRetryStrict) {
			final String report = "selected=" + rectangleBounds(visible) + " | clipped=" + dialogueFitClippedFallback + " | action=KEEP_RELEASED_FIT";
		}
		/* Only this redundant obstacle-settle retry is consumed. A genuine
		 * chatbox drop is handled above and still performs a new fit search. */
		dialogueFitRetryStrict = false;
		return true;
	}

	private void clearDialogueReleasedFit() {
		dialogueReleasedFitRetained = null;
		dialogueReleasedFitContentFingerprint = Integer.MIN_VALUE;
	}

	private DialogueFitProposal proposeDialogueReleasedGrowth(GeometryState current, DialoguePrompts.FitRequirement requirement) {
		if (!dialogueFitActive || dialogueHostDragActive || dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended || unclassifiedNativeDialogueDrag()) {
			return null;
		}
		final Rectangle visible = current.effectiveBounds;
		/* Do not use a stale native viewport as the starting point for
		 * additional growth while a user is actively dragging an obstacle.
		 * The collision resolver still owns shrink/restoration below. */
		if (activeObstacleDialogueDrag() && !dialogueHostRenderAligned(current)) {
			return null;
		}
		final int bodyHeight = ChatboxGeometry.bodyHeight(visible.height, chatboxButtonsHidden);
		/* A valid minimum-content fit can still be vertically compressed by a
		 * moving row. Only recover unused height when a collision-growth baseline
		 * from this same dialogue page proves that the height was temporary.
		 * A newly opened compact dialogue must not grow merely to fill the canvas. */
		final boolean recoverHeight = dialogueOrthogonalFitBaseline != null && dialogueOrthogonalBaselineContentFingerprint == dialoguePrompts.fitFingerprint() && visible.height < Math.min(current.configuredHeight, dialogueOrthogonalFitBaseline.height);
		final boolean contentFits = requirement.isActive() && requirement.fitsMinimum(visible.width, bodyHeight);
		if (!requirement.isActive() || contentFits && !recoverHeight || visible.width < requirement.getMinimumWidth()) {
			return null;
		}
		final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final List<Rectangle> obstacles = InterfaceBounds.dialogueCollisionObstacles(client, overrides);
		/* A width-only recovery must not unlock the vertical edge that caused
		 * the original shrink. Use the retained pre-growth rectangle, not the
		 * latest selected shape, to recognize the still-active obstacle. */
		final Rectangle anchorReference = dialogueOrthogonalFitBaseline != null && dialogueOrthogonalBaselineContentFingerprint == dialoguePrompts.fitFingerprint() ? dialogueOrthogonalFitBaseline : current.desiredBounds;
		final int activeEdges = DialogueResizeService.activeOrthogonalEdges(anchorReference, visible, obstacles);
		if ((activeEdges & 12) != 0) {

			return null;
		}

		final int up = dialogueResizeService.orthogonalFreeSpace(visible, obstacles, 0);
		final int down = dialogueResizeService.orthogonalFreeSpace(visible, obstacles, 1);
		final int tabHeight = chatboxButtonsHidden ? 0 : ChatboxGeometry.NATIVE_TAB_HEIGHT;
		final int contentHeight = DialogueFitSolver.alignGrowth(requirement.getMinimumBodyHeight() + tabHeight, current.configuredHeight);
		final int recoveryHeight = recoverHeight ? Math.min(current.configuredHeight, dialogueOrthogonalFitBaseline.height) : visible.height;
		final int requiredHeight = Math.max(visible.height, Math.max(contentHeight, recoveryHeight));
		final int goal = Math.min(requiredHeight, visible.height + up + down);
		if (goal < visible.height + DIALOGUE_FIT_STEP) {
			return null;
		}
		final int contentFingerprint = dialoguePrompts.fitFingerprint();
		final long fingerprint = orthogonalSearchFingerprint(visible, visible, contentFingerprint);
		final long started = System.nanoTime();
		if (dialogueReleasedGrowthFailedFingerprint == fingerprint && started - dialogueReleasedGrowthFailedAtNanos < ORTHOGONAL_FAILED_RETRY_NANOS) {
			return null;
		}
		final Widget slot = getSlot(getLayout());
		if (slot == null) {
			return null;
		}
		final int added = goal - visible.height;
		/* An incomplete preference recovery is not content clipping when the
		 * text already fits; only actual content shortage is a partial fit. */
		final boolean partial = !contentFits && goal < contentHeight;
		DialogueFitCandidate best = null;
		long bestDistance = Long.MAX_VALUE;
		for (int direction = 0; direction < 3; direction++) {
			/* Keep the physical host origin fixed until the obstacle is
			 * released. Downward growth is safe while the user is dragging;
			 * upward/split recovery is retried after its final position. */
			if (direction != 0 && activeObstacleDialogueDrag()) {
				continue;
			}
			final int above;
			if (direction == 0) {
				above = 0;
			} else if (direction == 1) {
				above = added;
			} else {
				above = Math.max(0, Math.min(up, Math.max(added - down, added / 2)));
			}
			if (above > up || added - above > down) {
				continue;
			}
			final Rectangle proposed = new Rectangle(visible.x, visible.y - above, visible.width, goal);
			final DialogueFitCandidate candidate = clearOrthogonalDialogueCandidate(slot, chatboxPlacement.captureCornerAnchoredDialogue(proposed, false, false), proposed.width, proposed.height, visible, false, false, false, false, overrides, obstacles, current, partial);
			if (candidate == null) {
				continue;
			}
			final long movement = Math.abs((long) added - 2L * above);
			if (best == null || movement < bestDistance) {
				best = candidate;
				bestDistance = movement;
			}
		}
		if (best == null) {
			dialogueReleasedGrowthFailedFingerprint = fingerprint;
			dialogueReleasedGrowthFailedAtNanos = System.nanoTime();
			return null;
		}
		return new DialogueFitProposal(DialogueResizeService.FitPath.RELEASED_GROW, best, null, contentFingerprint, fingerprint);
	}

	private void clearOrthogonalSearchFailure() {
		dialogueOrthogonalFailedFingerprint = Long.MIN_VALUE;
		dialogueOrthogonalFailedAtNanos = 0L;
		dialogueOrthogonalFailedContentFingerprint = Integer.MIN_VALUE;
	}

	private long orthogonalSearchFingerprint(Rectangle original, Rectangle visible, int contentFingerprint) {
		long fingerprint = currentDialogueCollisionFingerprint();
		fingerprint = 31L * fingerprint + contentFingerprint;
		fingerprint = 31L * fingerprint + original.x;
		fingerprint = 31L * fingerprint + original.y;
		fingerprint = 31L * fingerprint + original.width;
		fingerprint = 31L * fingerprint + original.height;
		fingerprint = 31L * fingerprint + visible.x;
		fingerprint = 31L * fingerprint + visible.y;
		fingerprint = 31L * fingerprint + visible.width;
		fingerprint = 31L * fingerprint + visible.height;
		fingerprint = 31L * fingerprint + client.getCanvasWidth();
		fingerprint = 31L * fingerprint + client.getCanvasHeight();
		fingerprint = 31L * fingerprint + (dialogueFitConstrainedWrap ? 1 : 0);
		return fingerprint;
	}

	private DialogueFitCandidate clearOrthogonalDialogueCandidate(Widget slot, ChatboxPlacement.State anchor, int width, int height, Rectangle visible, boolean left, boolean right, boolean top, boolean bottom, InterfaceBounds.Overrides overrides, List<Rectangle> obstacles, GeometryState current, boolean partial) {
		final DialogueFitCandidate candidate = resolveDialogueCandidate(slot, anchor, width, height, current.configuredWidth, current.configuredHeight, chatboxBoundsTracker.copy(), overrides, null, dialogueFitConstrainedWrap);
		if (candidate == null) {
			return null;
		}
		final Rectangle bounds = candidate.result.getEffectiveBounds();
		if (!bounds.equals(candidate.result.getDesiredBounds()) || !bounds.equals(anchor.getDesiredBounds()) || !DialogueResizeService.withinDialogueCanvas(bounds, client.getCanvasWidth(), client.getCanvasHeight()) || DialogueResizeService.introducesOrthogonalOverlap(bounds, visible, obstacles) || left && bounds.x + bounds.width != visible.x + visible.width || right && bounds.x != visible.x || top && bounds.y + bounds.height != visible.y + visible.height || bottom && bounds.y != visible.y) {
			return null;
		}
		final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(bounds.width, dialogueFitConstrainedWrap);
		if (!requirement.isActive()) {
			return null;
		}
		final boolean fits = requirement.fitsMinimum(bounds.width, ChatboxGeometry.bodyHeight(bounds.height, chatboxButtonsHidden));
		if (!fits && !partial) {
			return null;
		}
		/* Reuse the existing clipped-view fallback semantics; no additional
		 * host-position writer is needed for a safe partial improvement. */
		candidate.clippedFallback = !fits;
		return candidate;
	}

	private boolean sameDeferredHostMismatch(GeometryState current) {
		if (dialogueDeferredExpectedHost == null || dialogueDeferredActualHost == null || dialogueDeferredContentFingerprint != dialoguePrompts.fitFingerprint() || dialogueDeferredObstacleFingerprint != currentDialogueCollisionFingerprint()) {
			return false;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle physical = slot != null ? slot.getBounds() : null;
		return physical != null && dialogueDeferredActualHost.equals(physical) && dialogueDeferredExpectedHost.equals(dialoguePhysicalHostBounds(current));
	}

	private void clearDialogueSessionCenter() {
		dialogueOrthogonalRestoreTarget = null;
		dialogueReleasedRestoreEvidence = null;
		dialogueNativeSnapTransitionTarget = null;
		resetDialogueEmergencyFallback();
		dialogueNativePlacementResetObserved = false;
		dialogueOtherOverlayResetCycle = Integer.MIN_VALUE;
		dialogueObstacleResetRecoveryPending = false;
		final Point previous = dialogueSession.getCenter();

		dialogueSession.complete();
	}

	private void resetDialogueEmergencyFallback() {
		dialogueEmergencyFallbackCaptured = false;
		dialogueEmergencyRestoreWaitFrames = 0;
		dialogueEmergencyRestoreLastCycle = Integer.MIN_VALUE;
		dialogueEmergencyAdoptWaitFrames = 0;
		dialogueEmergencyAdoptLastCycle = Integer.MIN_VALUE;
	}

	private void ensureDialogueReferenceCenter(Rectangle initial) {
		if (dialogueSession.isPresent() || initial == null || initial.width <= 0 || initial.height <= 0) {
			return;
		}
		/* A valid normal snapshot always wins. A missing normal center is
		 * rare (e.g. an unsaved native reset during the first dialogue
		 * frame); initialize only the TEMPORARY dialogue center from the
		 * current physical host. Never update the core's normal center. */
		final ChatboxPlacement.NormalPlacement normal = dialogueSession.getNormalPlacement() != null ? dialogueSession.getNormalPlacement() : !isNormalChatboxPlacementPending() ? getNormalChatboxPlacement() : null;
		Point center = normal != null ? normal.getCenter() : null;
		String source = "NORMAL_REFERENCE";
		if (center == null && !dialogueMouseHeld && !dialogueHostDragActive && !dialogueAwaitingNativeSave && !dialogueUnconfirmedDropSuspended && !dialogueFitActive && !dialogueOrthogonalRestorePending && dialogueSession.getRestoreExpected() == null) {
			final Widget slot = getSlot(getLayout());
			final Rectangle rendered = slot != null ? slot.getBounds() : null;
			final Rectangle nativeHost = chatboxPlacement.nativeOverlayBounds();
			if (rendered != null && rendered.width > 0 && rendered.height > 0 && rendered.equals(nativeHost) && rendered.equals(initial)) {
				/* RuneLite can briefly align its rendered and native
				 * host at an OLD location during an options-page remount.
				 * Do not adopt that stale center while the desired normal
				 * rectangle still points elsewhere. */
				center = rectangleCenter(rendered);
				source = "ALIGNED_PHYSICAL_FALLBACK";
			} else if (rendered == null && nativeHost == null) {
				/* Last resort only when the renderer has not supplied a
				 * physical host yet. Do not fit from conflicting samples. */
				center = rectangleCenter(initial);
				source = "INITIAL_GEOMETRY_FALLBACK";
			}
		}
		if (center == null || !dialogueSession.begin(center)) {
			return;
		}
	}

	private static Point rectangleCenter(Rectangle bounds) {
		return bounds != null && bounds.width > 0 && bounds.height > 0 ? new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2) : null;
	}

	private void updateDialogueReferenceForConfirmedDrag(Rectangle dropped) {
		if (dropped == null || dialogueHostDragStart == null || !"CHATBOX_DRAG".equals(dialogueInteraction)) {
			return;
		}
		ensureDialogueReferenceCenter(dialogueCornerReference != null ? dialogueCornerReference : dialogueHostDragStart);
		final Point savedCenter = dialogueSession.getCenter();
		final Point startCenter = new Point(dialogueHostDragStart.x + dialogueHostDragStart.width / 2, dialogueHostDragStart.y + dialogueHostDragStart.height / 2);
		/* An orthogonal fitting host may intentionally have a different physical midpoint.
		 * Only treat a configured-size, non-fitted host as stale when its
		 * previous center no longer describes the physical drag start. */
		final boolean stalePhysicalCenter = !dialogueFitActive && geometryState != null && dialogueHostDragStart.width == geometryState.configuredWidth && dialogueHostDragStart.height == geometryState.configuredHeight && savedCenter != null && (Math.abs(savedCenter.x - startCenter.x) > DIALOGUE_FIT_STEP || Math.abs(savedCenter.y - startCenter.y) > DIALOGUE_FIT_STEP);
		final boolean rebase = dialogueNativePlacementResetObserved || stalePhysicalCenter;
		if (!dialogueSession.recordDrag(dialogueHostDragStart, dropped, dialogueMousePressSerial, rebase)) {
			return;
		}
		dialogueNativePlacementResetObserved = false;
		dialogueNativeSnapTransitionTarget = null;

		final int dx = dropped.x - dialogueHostDragStart.x;
		final int dy = dropped.y - dialogueHostDragStart.y;
	}

	/* A confirmed drag supersedes the old effective collision viewport.
	 * Both native overlay and rendered slot must agree on the dropped host.
	 * Only geometry is rebased; DialogueSession retains the confirmed center. */
	private void rebaseConfirmedDialogueHost(Rectangle dropped) {
		if (dropped == null || geometryState == null || !dialogueSession.hasConfirmedDrag()) {
			return;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle rendered = slot != null ? slot.getBounds() : null;
		final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
		if (!dropped.equals(rendered) || !dropped.equals(nativeBounds)) {
			return;
		}
		final GeometryState previous = geometryState;
		final GeometryState rebased = new GeometryState(previous.configuredWidth, previous.configuredHeight, new Rectangle(dropped), new Rectangle(dropped));
		geometryState = rebased;
		if (dialogueFitGeometry != null) {
			dialogueFitGeometry = rebased;
		}
		/* Preserve the verified physical host while the new collision pass
		 * computes a separate, potentially smaller effective viewport. */
		dialogueFirstContactHostPreserved = dialogueFitActive;
		dialogueFitDeferredHostMismatch = false;
		dialogueDeferredExpectedHost = null;
		dialogueDeferredActualHost = null;
		dialogueDeferredObstacleFingerprint = Long.MIN_VALUE;
		dialogueDeferredContentFingerprint = Integer.MIN_VALUE;
		dialoguePageRebuildPending = false;
		dialoguePageRebuildCycle = Integer.MIN_VALUE;
		chatboxBoundsTracker.reset();
		dialogueCollisionFingerprint = Long.MIN_VALUE;
	}

	private void resetDialogueCornerReference(Rectangle confirmedPlacement) {
		dialogueOrthogonalFitBaseline = null;
		dialogueOrthogonalBaselineContentFingerprint = Integer.MIN_VALUE;
		dialogueFirstContactHostPreserved = false;
		resetSnappedSizeReflow();
		dialogueOrthogonalRestorePending = false;
		dialogueOrthogonalRestoreTarget = null;
		dialogueReleasedRestoreEvidence = null;

		dialogueReleasedGrowthFailedFingerprint = Long.MIN_VALUE;
		clearDialogueReleasedFit();
		clearOrthogonalSearchFailure();
		/* A post-drop fit can temporarily widen a dialogue to the canvas edge.
		 * That contact is not proof the user deliberately placed it there.
		 * Preserve an existing explicit right-edge pin on vertical moves, or
		 * acquire one when the physical drag moves RIGHT into that edge. */
		if (confirmedPlacement == null) {
			dialogueUserRightEdgePinned = false;
		} else {
			final int canvasWidth = Math.max(0, client.getCanvasWidth());
			final boolean touchesRight = DialogueResizeService.touchesDialogueRightCanvasEdge(confirmedPlacement, canvasWidth);
			if (dialogueHostDragStart != null && "CHATBOX_DRAG".equals(dialogueInteraction)) {
				final Rectangle start = dialogueHostDragStart;
				dialogueUserRightEdgePinned = touchesRight && (dialogueUserRightEdgePinned && DialogueResizeService.touchesDialogueRightCanvasEdge(start, canvasWidth) || confirmedPlacement.x - start.x > DIALOGUE_FIT_STEP);
			} else if (dialogueCornerReference == null) {
				/* First capture is RuneLite's actual placement, before ChatXL
				 * has had a chance to grow the dialogue into this boundary. */
				dialogueUserRightEdgePinned = touchesRight;
			} else if (!touchesRight) {
				dialogueUserRightEdgePinned = false;
			}
		}
		dialogueCornerReference = confirmedPlacement != null ? new Rectangle(confirmedPlacement) : null;
		if (confirmedPlacement != null) {
			final int width = Math.max(0, client.getCanvasWidth());
			final int height = Math.max(0, client.getCanvasHeight());
			dialogueCornerRight = confirmedPlacement.x > width - (confirmedPlacement.x + confirmedPlacement.width);
			dialogueCornerBottom = confirmedPlacement.y > height - (confirmedPlacement.y + confirmedPlacement.height);
		}
		dialogueCornerVerticalLocked = false;
		dialogueCornerHorizontalLocked = false;
	}

	private ChatboxPlacement.State dialogueCornerPlacement(Widget slot, int width, int height, GeometryState live, boolean trackCollision) {
		if (dialogueCornerReference == null) {
			final ChatboxPlacement.State initial = chatboxPlacement.capture(slot, width, height);
			dialogueCornerReference = initial.getDesiredBounds();
			final int canvasWidth = Math.max(0, client.getCanvasWidth());
			final int canvasHeight = Math.max(0, client.getCanvasHeight());
			final Rectangle reference = dialogueCornerReference;
			dialogueCornerRight = reference.x > canvasWidth - (reference.x + reference.width);
			dialogueCornerBottom = reference.y > canvasHeight - (reference.y + reference.height);
			/* The first captured native rectangle is the existing user placement. */
			dialogueUserRightEdgePinned = DialogueResizeService.touchesDialogueRightCanvasEdge(reference, canvasWidth);
		}
		/* This hook can run before reconcileDialogueFit() on the FIRST
		 * dialogue frame. Capture the durable normal placement before any
		 * stale post-restore host rectangle can initialize session center. */
		if (dialogueSession.getNormalPlacement() == null && dialoguePrompts.isActive() && geometryState != null) {
			capturePreDialogueNormalPlacement(geometryState);
		}
		ensureDialogueReferenceCenter(dialogueCornerReference);

		/* Resolve the entering obstacle once, then hold that corner throughout
		 * clipping and recovery. A clear fit unlocks the next interaction. */
		if (trackCollision && live != null) {
			final Rectangle desired = live.desiredBounds;
			final Rectangle visible = live.effectiveBounds;
			final boolean top = visible.y > desired.y;
			final boolean bottom = visible.y + visible.height < desired.y + desired.height;
			final boolean left = visible.x > desired.x;
			final boolean right = visible.x + visible.width < desired.x + desired.width;
			if (!top && !bottom && !left && !right) {
				dialogueCornerVerticalLocked = false;
				dialogueCornerHorizontalLocked = false;
			}
			if (!dialogueCornerVerticalLocked && top != bottom) {
				dialogueCornerBottom = top;
				dialogueCornerVerticalLocked = true;
			}
			/* A glancing 1-4px contact must not permanently choose a side.
			 * Bidirectional fit selection below decides which direction actually fits. */
			final int leftOverlap = Math.max(0, visible.x - desired.x);
			final int rightOverlap = Math.max(0, desired.x + desired.width - visible.x - visible.width);
			if (!dialogueCornerHorizontalLocked && left != right && Math.max(leftOverlap, rightOverlap) > DIALOGUE_FIT_STEP) {
				/* A RIGHT-edge intrusion must preserve the LEFT edge, and
				 * a LEFT-edge intrusion must preserve the RIGHT edge. */
				dialogueCornerRight = left;
				dialogueCornerHorizontalLocked = true;
			}
		}

		final Rectangle fitReference = dialogueUserDropPinned && dialogueConfirmedDropBounds != null ? dialogueConfirmedDropBounds : dialogueCornerReference;
		final boolean fitRight = dialogueUserDropPinned && dialogueConfirmedDropBounds != null ? DialogueResizeService.touchesDialogueRightCanvasEdge(fitReference, client.getCanvasWidth()) : dialogueCornerRight;
		final int bottomGap = client.getCanvasHeight() - (fitReference.y + fitReference.height);
		final boolean fitBottom = dialogueUserDropPinned && dialogueConfirmedDropBounds != null ? fitReference.y > DIALOGUE_FIT_STEP && bottomGap >= 0 && bottomGap <= DIALOGUE_FIT_STEP : dialogueCornerBottom;
		/* Centered placement is the normal MANUAL dialogue fit path. Native
		 * AUTO fallback retains its existing bottom-left ownership contract. */
		final ChatboxPlacement.PlacementMode fitMode = chatboxPlacement.currentMode();
		/* A native-save timeout may clear the FIT pin, but
		 * not this session's confirmed user-owned center. */
		final boolean centeredFit = dialogueSession.getCenter() != null && (fitMode == ChatboxPlacement.PlacementMode.MANUAL || fitMode == ChatboxPlacement.PlacementMode.RUNTIME_DRAG && dialogueSession.hasConfirmedDrag() && !dialogueMouseHeld && !dialogueHostDragActive && !dialogueAwaitingNativeSave && !dialogueUnconfirmedDropSuspended);
		final ChatboxPlacement.State placement = centeredFit ? chatboxPlacement.captureCenteredDialogue(dialogueSession.getCenter(), width, height) : chatboxPlacement.captureCornerAnchoredDialogue(fitReference, fitRight, fitBottom);

		return placement;
	}

	private DialogueFitSelection selectDialogueFitCandidate(Widget slot, ChatboxPlacement.State placement, int configuredWidth, int configuredHeight, int maximumWidth, int maximumHeight, ChatboxBounds.Tracker tracker, InterfaceBounds.Overrides overrides, ChatboxBounds.Result baseline, Map<Integer, DialoguePrompts.FitRequirement> strictRequirements, Map<Integer, DialoguePrompts.FitRequirement> wrappedRequirements, boolean retainActiveFit) {
		return dialogueResizeService.selectFitCandidate(slot, placement, configuredWidth, configuredHeight, maximumWidth, maximumHeight, tracker, overrides, baseline, strictRequirements, wrappedRequirements, retainActiveFit, chatboxButtonsHidden, dialogueFitActive, dialogueFitRequestedWidth, dialogueFitRequestedHeight, dialoguePostDropShapeSearch, candidate -> dialogueResizeService.candidateClear(candidate, overrides), null);
	}

	private DialoguePrompts.FitRequirement fitRequirement(Map<Integer, DialoguePrompts.FitRequirement> requirements, int width, boolean constrainedWrap) {
		return dialogueResizeService.fitRequirement(requirements, width, constrainedWrap);
	}

	private DialogueFitCandidate findDialogueFitCandidate(GeometryState current, boolean retainActiveFit) {
		final ChatboxLayout layout = getLayout();
		final Widget slot = getSlot(layout);
		if (slot == null || !isResizableLayout(layout)) {
			return null;
		}

		final int configuredWidth = current.configuredWidth;
		final int configuredHeight = current.configuredHeight;
		final int maximumWidth = Math.max(configuredWidth, Math.max(0, client.getCanvasWidth()));
		final int maximumHeight = Math.max(configuredHeight, Math.max(0, client.getCanvasHeight()));
		/*
		 * Only a verified physical host may supply the reference for a TOP fit.
		 * When RuneLite briefly restores its native origin, postpone this search
		 * instead of turning the transient disagreement into a new user anchor.
		 */
		final boolean topClipped = dialogueFitActive && dialogueFitGeometry != null && current.effectiveBounds.y > current.desiredBounds.y;
		dialogueFitDeferredHostMismatch = false;
		if (topClipped) {
			final Rectangle physical = slot.getBounds();
			// The committed fit's viewport may be older than the latest collision
			// pass. Validate against the current solved viewport, not that old clip.
			final Rectangle expected = dialoguePhysicalHostBounds(current);
			if (physical == null || !physical.equals(expected)) {
				dialogueFitDeferredHostMismatch = true;

				dialogueDeferredExpectedHost = new Rectangle(expected);
				dialogueDeferredActualHost = physical != null ? new Rectangle(physical) : null;
				dialogueDeferredObstacleFingerprint = currentDialogueCollisionFingerprint();
				dialogueDeferredContentFingerprint = dialoguePrompts.fitFingerprint();
				return null;
			}
		}
		dialogueDeferredExpectedHost = null;
		dialogueDeferredActualHost = null;

		/* Never derive a candidate's origin from a clipped viewport.
		 * This stable seed is also shared with the full-content outline. */
		final ChatboxPlacement.State fitPlacement = dialogueCornerPlacement(slot, configuredWidth, configuredHeight, current, true);

		final ChatboxBounds.Tracker fitTracker = chatboxBoundsTracker.copy();
		final InterfaceBounds.Overrides fitOverrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final DialogueFitCandidate base = resolveDialogueCandidate(slot, fitPlacement, configuredWidth, configuredHeight, configuredWidth, configuredHeight, fitTracker, fitOverrides, null, false);

		if (base == null) {
			return null;
		}

		final Map<Integer, DialoguePrompts.FitRequirement> strictRequirements = new HashMap<>();
		final Rectangle baseEffective = base.result.getEffectiveBounds();
		final DialoguePrompts.FitRequirement baseRequirement = fitRequirement(strictRequirements, baseEffective.width, false);

		if (!baseRequirement.isActive()) {
			return base;
		}

		final Map<Integer, DialoguePrompts.FitRequirement> wrappedRequirements = new HashMap<>();
		final DialogueFitSelection primary = selectDialogueFitCandidate(slot, fitPlacement, configuredWidth, configuredHeight, maximumWidth, maximumHeight, fitTracker, fitOverrides, base.result, strictRequirements, wrappedRequirements, retainActiveFit);
		DialogueFitSelection selection = primary;
		boolean choseOpposite = false;

		/* The confirmed user's corner owns vertical growth. A bottom-corner
		 * placement must remain bottom-anchored even if a top-anchored full
		 * fit offers a comparable area. Only an actual entering collision may
		 * temporarily change the protected edge in dialogueCornerPlacement().
		 * In particular, never rewrite dialogueCornerBottom as a preference
		 * during a collision-free strict fit or settled retry. */

		/* Both horizontal directions must be tested against the same
		 * confirmed reference, but each gets an independent collision baseline
		 * and tracker. Do not derive the opposite origin from the live viewport. */
		if (dialogueSession.getCenter() == null && !dialogueUserDropPinned && chatboxPlacement.currentMode() != ChatboxPlacement.PlacementMode.NATIVE && dialogueCornerReference != null && (selection.candidate == null || selection.candidate.requestedWidth > configuredWidth || selection.candidate.requestedHeight > configuredHeight)) {
			final boolean oppositeRight = !dialogueCornerRight;
			final ChatboxPlacement.State oppositePlacement = chatboxPlacement.captureCornerAnchoredDialogue(dialogueCornerReference, oppositeRight, dialogueCornerBottom);
			final ChatboxBounds.Tracker oppositeTracker = chatboxBoundsTracker.copy();
			DialogueFitCandidate oppositeBase = resolveDialogueCandidate(slot, oppositePlacement, configuredWidth, configuredHeight, configuredWidth, configuredHeight, oppositeTracker, fitOverrides, null, false);
			if (oppositeBase != null) {
				/* Once we have a full fit, competing directions only need to search
				 * shapes in its near-optimal area band. With no full fit, search all. */
				int oppositeMaximumWidth = maximumWidth;
				if (selection.candidate != null) {
					final long areaCeiling = selection.candidate.growthArea + selection.candidate.growthArea * DIALOGUE_FIT_AXIS_TOLERANCE_PERCENT / 100L;
					final long widthLimit = ((long) configuredWidth * configuredHeight + areaCeiling) / Math.max(1, configuredHeight);
					oppositeMaximumWidth = (int) Math.min(maximumWidth, Math.max(configuredWidth, widthLimit + DIALOGUE_FIT_STEP));
				}
				DialogueFitSelection opposite = selectDialogueFitCandidate(slot, oppositePlacement, configuredWidth, configuredHeight, oppositeMaximumWidth, maximumHeight, oppositeTracker, fitOverrides, oppositeBase.result, strictRequirements, wrappedRequirements, retainActiveFit);

				/* A one-pixel native drop at a minimap/side-widget boundary can
				 * lose the entire LEFT-anchored search: widening by four pixels
				 * moves the visible left edge by one, which topology rejects as
				 * EFFECTIVE_BASELINE_LOST. The preceding fitted viewport may
				 * already have the one-pixel clearance. Retry that exact stable
				 * edge before allowing a full right-anchored fit to jump across
				 * the canvas. This never changes a saved native position. */
				Rectangle clearanceReference = null;
				if (opposite.candidate == null && selection.candidate != null && dialoguePostDropShapeSearch && dialogueUserDropPinned && dialogueCornerRight && !dialogueCornerHorizontalLocked && !dialogueUserRightEdgePinned && !dialogueMouseHeld && !dialogueCollisionLiveReflow && current != null) {
					final Rectangle reference = dialogueCornerReference;
					final Rectangle previous = current.effectiveBounds;
					final int clearance = previous.x - reference.x;
					final Rectangle primaryDesired = selection.candidate.result.getDesiredBounds();
					if (clearance > 0 && clearance <= DIALOGUE_FIT_STEP && previous.width >= configuredWidth && previous.x + previous.width == reference.x + reference.width && Math.abs(primaryDesired.x - previous.x) > configuredWidth) {
						final Rectangle adjusted = new Rectangle(reference);
						adjusted.x = previous.x;
						adjusted.width -= clearance;
						final ChatboxPlacement.State clearedPlacement = chatboxPlacement.captureCornerAnchoredDialogue(adjusted, false, dialogueCornerBottom);
						final ChatboxBounds.Tracker clearedTracker = chatboxBoundsTracker.copy();
						final DialogueFitCandidate clearedBase = resolveDialogueCandidate(slot, clearedPlacement, configuredWidth, configuredHeight, configuredWidth, configuredHeight, clearedTracker, fitOverrides, null, false);
						if (clearedBase != null) {
							final DialogueFitSelection cleared = selectDialogueFitCandidate(slot, clearedPlacement, configuredWidth, configuredHeight, oppositeMaximumWidth, maximumHeight, clearedTracker, fitOverrides, clearedBase.result, strictRequirements, wrappedRequirements, retainActiveFit);
							if (cleared.candidate != null && cleared.candidate.result.getDesiredBounds().x == adjusted.x) {
								opposite = cleared;
								oppositeBase = clearedBase;
								clearanceReference = adjusted;
							}
						}
					}
				}
				/* A wide physical drop may place its center on the right half of
				 * the canvas even when the user dropped it on the left. Between
				 * comparable, fully fitting shapes, keep the candidate closest to
				 * the confirmed drop rather than preserving a guessed right edge.
				 * Actual collision locks and canvas-edge attachment take priority. */
				final DialogueResizeService.HorizontalFitState horizontalState = horizontalFitState();
				final boolean dropContinuity = dialogueResizeService.preferPostDropHorizontalContinuity(selection, opposite, horizontalState);
				final String directionGuard = dialogueResizeService.oppositeDialogueCornerGuard(selection, opposite, configuredWidth, horizontalState);
				final boolean distanceOverride = dropContinuity && directionGuard != null && directionGuard.startsWith("DISTANT_OPPOSITE_");
				choseOpposite = (directionGuard == null || distanceOverride) && (dropContinuity || dialogueResizeService.preferOppositeDialogueCorner(selection, opposite, configuredWidth, configuredHeight, dialoguePostDropShapeSearch));

				if (choseOpposite) {
					selection = opposite;
					/* Keep the selected one-pixel clearance for subsequent pages;
					 * the physical saved placement remains RuneLite-owned. */
					if (clearanceReference != null) {
						dialogueCornerReference = clearanceReference;
					}
					dialogueCornerRight = oppositeRight;
					/* A selected alternative is stable for the interaction. A later
					 * obstruction may still override it if no full fit remains. */
					dialogueCornerHorizontalLocked = true;
				}
			}
		}

		/* Never translate the chatbox to escape an obstacle. Even on a
		 * settled/retry frame the user's original placement owns X/Y; a
		 * contact may shrink only the impacted edge. Clipped fallback is
		 * preferable to an unrequested full-box relocation. */

		final DialogueFitSearch strict = selection.strict;
		final DialogueFitSearch wrapped = selection.wrapped;
		if (selection.candidate != null) {
			final DialogueFitCandidate selected = selection.candidate;
			final String mode = selected.constrainedWrap ? "WRAP" : "STRICT";
			final Map<Integer, DialoguePrompts.FitRequirement> selectedRequirements = selected.constrainedWrap ? wrappedRequirements : strictRequirements;
			return selected;
		}

		final DialogueFitCandidate clipped = DialogueResizeService.clippedFallback(selection.fallback);
		if (clipped != null) {
			final Map<Integer, DialoguePrompts.FitRequirement> selectedRequirements = clipped.constrainedWrap ? wrappedRequirements : strictRequirements;
			return clipped;
		}
		return null;
	}

	private DialogueResizeService.HorizontalFitState horizontalFitState() {
		return new DialogueResizeService.HorizontalFitState(dialogueCornerReference, dialoguePostDropShapeSearch, dialogueUserDropPinned, dialogueMouseHeld, dialogueCollisionLiveReflow, dialogueCornerHorizontalLocked, dialogueUserRightEdgePinned, geometryState != null ? geometryState.desiredBounds : null, geometryState != null ? geometryState.effectiveBounds : null, client.getCanvasWidth());
	}

	private static boolean sameSize(Rectangle a, Rectangle b) {
		return a != null && b != null && a.width == b.width && a.height == b.height;
	}

	private static String rectangleBounds(Rectangle bounds) {
		return bounds != null ? bounds.x + "," + bounds.y + "," + bounds.width + "x" + bounds.height : "-";
	}

	private DialogueFitCandidate resolveDialogueCandidate(Widget slot, ChatboxPlacement.State fitPlacement, int requestedWidth, int requestedHeight, int configuredWidth, int configuredHeight, ChatboxBounds.Tracker fitTracker, InterfaceBounds.Overrides fitOverrides, ChatboxBounds.Result baselineResult, boolean constrainedWrap) {
		return dialogueResizeService.resolveCandidate(slot, fitPlacement, requestedWidth, requestedHeight, configuredWidth, configuredHeight, fitTracker, fitOverrides, baselineResult, constrainedWrap, chatboxButtonsHidden, null);
	}

	private boolean applyDialogueFitCandidate(GeometryState current, DialogueFitCandidate candidate, DialogueResizeService.FitPath reason, boolean firstContact) {
		/* AUTO may be selected by RuneLite during a fit search. It cannot
		 * acquire a temporary host placement, so refuse a stale manual fit. */
		final ChatboxPlacement.PlacementMode placementMode = chatboxPlacement.currentMode();
		final boolean nativeAuto = placementMode == ChatboxPlacement.PlacementMode.NATIVE;
		final boolean snapped = placementMode == ChatboxPlacement.PlacementMode.SNAPPED;
		final boolean nativeOwned = nativeAuto || snapped;
		if (nativeAuto && !nativeBottomLeftDialogueFit(current)) {
			return false;
		}
		final ChatboxLayout layout = getLayout();
		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (slot == null || universe == null || chatArea == null) {
			return false;
		}

		final Rectangle desired = candidate.result.getDesiredBounds();
		final Rectangle effective = candidate.result.getEffectiveBounds();
		final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(effective.width, candidate.constrainedWrap);
		final int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
		if (nativeAuto && (desired.x != 0 || desired.y + desired.height != client.getCanvasHeight())) {
			return false;
		}
		if (!candidate.topologyValid || !requirement.isActive() || !candidate.clippedFallback && !requirement.fitsMinimum(effective.width, bodyHeight)) {
			return false;
		}

		/* A stable manual dialogue uses its collision-safe rectangle as the
		 * physical host. Never mutate the native widget during a user drag. */
		final Rectangle hostBounds = (nativeAuto && !dialogueFirstContactHostPreserved || !nativeOwned && !dialogueFirstContactHostPreserved && shouldUseEffectiveDialogueHost(candidate.clippedFallback)) ? effective : desired;
		final int effectiveX = hostBounds.equals(effective) ? 0 : Math.max(0, effective.x - desired.x);
		final int effectiveY = hostBounds.equals(effective) ? 0 : Math.max(0, effective.y - desired.y);
		final boolean controlsChanged = !controlsMatch(effective.width);
		final boolean widthChanged = universe.getWidth() != effective.width || chatArea.getWidth() != effective.width;
		final boolean heightChanged = universe.getHeight() != effective.height || chatArea.getHeight() != bodyHeight;

		/* A native-owned fit must not relocate an unchanged-size host.
		 * RuneLite chooses its physical X/Y; only size is ours to change. */
		final Rectangle physicalHost = chatboxPlacement.nativeOverlayBounds();
		if (nativeOwned && physicalHost != null && physicalHost.width == hostBounds.width && physicalHost.height == hostBounds.height && (physicalHost.x != hostBounds.x || physicalHost.y != hostBounds.y)) {
			return false;
		}
		final boolean updateHostLocation = !canvasResizePending;
		final boolean restoringA2 = reason == DialogueResizeService.FitPath.ORTHOGONAL_RESTORE && dialogueFirstContactHostPreserved;
		final boolean ownedOrthogonalMove = firstContact || restoringA2;
		final boolean moveHost = updateHostLocation && !nativeOwned && (ownedOrthogonalMove || candidate.directHostMove || candidate.clippedFallback || !dialogueFirstContactHostPreserved && shouldUseEffectiveDialogueHost(candidate.clippedFallback) && !desired.equals(effective));
		final boolean adaptiveCandidate = candidate.expanded || candidate.constrainedWrap || candidate.clippedFallback;
		/* A configured-size orthogonal fitting restore has expanded=false. It MUST still
		 * reverse the temporary native offset before the host is resized.
		 * Refuse the logical commit if a genuine drag has taken ownership. */
		final boolean overlayMoved = (adaptiveCandidate || ownedOrthogonalMove) && updateHostLocation && !dialogueHostDragActive && (ownedOrthogonalMove && snapped ? chatboxPlacement.resizeSnappedDialogueHost(current.desiredBounds, hostBounds) : ownedOrthogonalMove && nativeAuto ? chatboxPlacement.resizeNativeDialogueHost(firstContact ? physicalHost : current.desiredBounds, hostBounds) : !nativeOwned && chatboxPlacement.applyTemporaryHost(hostBounds));
		if (ownedOrthogonalMove && !overlayMoved) {
			return false;
		}
		geometryState = new GeometryState(current.configuredWidth, current.configuredHeight, desired, effective);
		if (!overlayMoved) {
			chatboxPlacement.applyHostBounds(hostBounds, updateHostLocation && !nativeOwned, candidate.clippedFallback || nativeAuto);
		}
		final boolean nativeSizeChanged = nativeOwned && physicalHost != null && (physicalHost.width != hostBounds.width || physicalHost.height != hostBounds.height);
		/* A verified manual page change can resize and re-anchor in one
		 * transaction. applyTemporaryHost() has already moved the native
		 * overlay, but the widget can otherwise render the NEW width at
		 * its OLD X/Y for one frame (top-right options-page jump). This
		 * is a widget readback sync, not a new user placement or fit. */
		final boolean ownedManualFitReanchor = !nativeOwned && reason == DialogueResizeService.FitPath.STANDARD_FIT && overlayMoved && physicalHost != null && (physicalHost.x != hostBounds.x || physicalHost.y != hostBounds.y);

		final boolean hostPositionChanged = (moveHost || ownedManualFitReanchor) && chatboxPlacement.moveHost(slot, hostBounds, candidate.clippedFallback);

		if (hostPositionChanged) {
			recordMutation();
		}

		/* Dialogue fit selection is an overlay-owned temporary placement. The
		 * forced position itself must not trigger a second slot revalidation.
		 * Any actual size change is revalidated within applyGeometry(). */
		applyGeometry(slot, universe, chatArea, hostBounds.width, hostBounds.height, effectiveX, effectiveY, effective.width, effective.height, bodyHeight, controlsChanged, false);

		applyBackgroundForHost(chatArea, effective.width, bodyHeight);
		syncChatPresentation();

		updateDialogueFitState(current, candidate, requirement);
		/* A configured-size orthogonal fitting restore may clear fit state above. Keep the
		 * native readback pending until the physical size has settled. */
		if (nativeSizeChanged && (dialogueFitActive || ownedOrthogonalMove)) {
			dialogueNativeSizeReflowPending = true;
			dialogueNativeSizeReflowCycle = client.getGameCycle();
			dialogueNativeSizeReflowStartingHost = new Rectangle(physicalHost);
			dialogueSnappedReflowStableHost = null;
			dialogueSnappedReflowLastSampleCycle = Integer.MIN_VALUE;
			dialogueSnappedReflowStableSamples = 0;
		}
		rememberDialogueIdealCollision(candidate.result, collisionInterfaceOverrides);
		if (nativeAuto && dialogueFitActive) {

			dialogueNativeAutoFit = true;
		}

		widthRefreshPending |= widthChanged;
		heightRefreshPending |= heightChanged;
		return true;
	}

	private boolean shouldUseEffectiveDialogueHost(boolean clippedFallback) {
		/* Keep the same physical-host contract in fit commits, ordinary
		 * collision passes and restoration readback. SNAP retains the
		 * complete native host, even while its viewport is clipped. */
		final ChatboxPlacement.PlacementMode mode = chatboxPlacement.currentMode();
		if (mode == ChatboxPlacement.PlacementMode.SNAPPED) {
			return false;
		}
		/* Native AUTO retains h.2.6's effective host for ordinary fitting;
		 * first-contact orthogonal fitting separately preserves the full desired host. */
		if (mode == ChatboxPlacement.PlacementMode.NATIVE) {
			return !dialogueHostDragActive && !dialogueAwaitingNativeSave;
		}
		return !dialogueHostDragActive && !dialogueAwaitingNativeSave && (mode == ChatboxPlacement.PlacementMode.MANUAL || clippedFallback || dialogueNativeAutoFit);
	}

	private Rectangle dialoguePhysicalHostBounds(GeometryState current) {
		if (current == null) {
			return new Rectangle();
		}

		return new Rectangle(dialogueFitActive && !dialogueFirstContactHostPreserved && shouldUseEffectiveDialogueHost(dialogueFitClippedFallback) ? current.effectiveBounds : current.desiredBounds);
	}

	private void updateDialogueFitState(GeometryState current, DialogueFitCandidate candidate, DialoguePrompts.FitRequirement requirement) {
		/* Returning to configured dimensions ends adaptive sizing, not the
		 * dialogue interaction. A confirmed user drop still owns its position
		 * until the dialogue actually closes or the layout is invalidated. */
		dialogueFitActive = candidate.expanded || candidate.constrainedWrap || candidate.clippedFallback || dialogueUserDropPinned && dialogueSession.isPresent();
		if (!dialogueFitActive) {
			clearDialogueFitState();
			return;
		}

		dialogueFitConstrainedWrap = candidate.constrainedWrap;
		dialogueFitClippedFallback = candidate.clippedFallback;
		dialogueFitRetryStrict = false;
		dialogueFitConfiguredWidth = current.configuredWidth;
		dialogueFitConfiguredHeight = current.configuredHeight;
		dialogueFitRequestedWidth = candidate.requestedWidth;
		dialogueFitRequestedHeight = candidate.requestedHeight;
		dialogueFitFingerprint = dialoguePrompts.fitFingerprint();
		dialoguePageRebuildPending = false;
		dialoguePageRebuildCycle = Integer.MIN_VALUE;

		dialogueFitGeometry = new GeometryState(current.configuredWidth, current.configuredHeight, candidate.result.getDesiredBounds(), candidate.result.getEffectiveBounds());
		dialogueCollisionFingerprint = currentDialogueCollisionFingerprint();
	}

	private void clearDialogueFitState() {
		chatboxPlacement.restoreTemporaryHost();
		dialogueUserDropPinned = false;
		dialogueConfirmedDropBounds = null;
		/* Adaptive sizing is not session ownership. Only confirmed dialogue
		 * closure or layout teardown may clear DialogueSession. */
		dialoguePinnedRejectedFitKey = Long.MIN_VALUE;
		dialogueHostDragActive = false;
		dialogueDragKindDecided = false;
		dialogueInteraction = "UNKNOWN";
		dialogueHostDragStart = null;
		dialogueAdoptedPhysicalPressSerial = Integer.MIN_VALUE;
		dialogueFitDeferredHostMismatch = false;
		dialogueDeferredExpectedHost = null;
		dialogueDeferredActualHost = null;
		dialogueDeferredObstacleFingerprint = Long.MIN_VALUE;
		dialogueDeferredContentFingerprint = Integer.MIN_VALUE;
		dialogueDragPreview = null;
		dialogueDragPreviewTracker = null;
		dialoguePostDropSearchPending = false;
		dialoguePostDropShapeSearch = false;
		dialoguePostDropSearchCompleted = false;
		dialogueDropSettlePending = false;
		dialogueDropSettleDelay = 0;
		dialogueDropSettleAlignmentWaits = 0;
		dialogueDropSample = null;
		dialogueOrthogonalFitBaseline = null;
		dialogueOrthogonalBaselineContentFingerprint = Integer.MIN_VALUE;
		dialogueFirstContactHostPreserved = false;
		resetSnappedSizeReflow();
		dialogueOrthogonalRestorePending = false;
		dialogueOrthogonalRestoreTarget = null;
		dialogueReleasedRestoreEvidence = null;
		dialogueReleasedGrowthFailedFingerprint = Long.MIN_VALUE;
		clearDialogueReleasedFit();
		clearOrthogonalSearchFailure();
		dialogueCanvasRebasePending = false;
		dialogueCanvasRebaseReference = null;
		dialogueFitActive = false;
		dialogueFitConstrainedWrap = false;
		dialogueFitClippedFallback = false;
		dialogueNativeAutoFit = false;
		dialogueClipFromTop = false;
		dialogueViewport.resetIdealCollision();
		dialogueClipOriginObserved = false;
		dialogueClipContentFingerprint = Integer.MIN_VALUE;
		dialogueFitRetryStrict = false;
		dialoguePageRebuildPending = false;
		dialoguePageRebuildCycle = Integer.MIN_VALUE;
		dialogueCollisionLiveReflow = false;
		dialogueCollisionDragPending = false;
		dialogueCollisionDropPending = false;
		dialogueCollisionDragObserved = false;
		dialogueCollisionDropFrames = 0;
		dialogueFitConfiguredWidth = -1;
		dialogueFitConfiguredHeight = -1;
		dialogueFitRequestedWidth = -1;
		dialogueFitRequestedHeight = -1;
		dialogueFitFingerprint = 0;
		resetDialogueCornerReference(null);
		dialogueFitGeometry = null;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
		dialogueRelayoutDirty = false;
		clearDialogueTransition();
	}

	private void clearDialogueTransition() {
		dialogueTransitionPending = false;
		dialogueTransitionReady = false;
	}

	private void capturePreDialogueNormalPlacement(GeometryState current) {
		if (dialogueSession.getNormalPlacement() != null || current == null || dialogueFitActive || !dialoguePrompts.isActive() || dialogueHostDragActive || dialogueAwaitingNativeSave || dialogueSession.getLastDragSerial() != Integer.MIN_VALUE) {
			return;
		}

		/* Core validates/caches the DURABLE normal host; dialogue owns only
		 * this session's immutable snapshot. Never take a baseline from a
		 * clipped or physically dragged dialogue host. */
		final ChatboxPlacement.NormalPlacement previous = getNormalChatboxPlacement();
		final ChatboxPlacement.NormalPlacement normal = normalChatboxSnapshotForDialogue(current.desiredBounds);
		if (normal == null) {
			final String diagnostic = chatboxPlacement.normalCaptureDiagnostic(current.desiredBounds);
			return;
		}
		dialogueSession.captureNormalPlacement(normal, client.getCanvasWidth(), client.getCanvasHeight());
	}

	private void captureMissingNormalRestoreReference(int width, int height) {
		final int canvasWidth = client.getCanvasWidth();
		final int canvasHeight = client.getCanvasHeight();
		if (width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0 || dialogueSession.getNormalPlacement() != null) {
			return;
		}
		final ChatboxPlacement.NormalPlacement normal = !isNormalChatboxPlacementPending() ? getNormalChatboxPlacement() : null;
		if (normal != null) {
			dialogueSession.captureNormalPlacement(normal, canvasWidth, canvasHeight);

			return;
		}

		/* The session's confirmed dialogue center is the authoritative
		 * user placement, including after orthogonal fitting or a dialogue drag. A native
		 * reset is not by itself permission to discard this center. */
		Point center = dialogueSession.getCenter();
		String source = "DIALOGUE_CENTER";
		if (center == null) {
			final Widget slot = getSlot(getLayout());
			final Rectangle rendered = slot != null ? slot.getBounds() : null;
			final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
			if (rendered == null || !rendered.equals(nativeBounds)) {
				/* Revisit after the native overlay finishes rendering. An
				 * unaligned host is not an authoritative fallback. */
				return;
			}
			center = rectangleCenter(rendered);
			source = "CURRENT_PHYSICAL_HOST";
		}
		if (center == null) {
			return;
		}
		final Rectangle target = chatboxPlacement.centeredDialogueBounds(center, width, height);
		final ChatboxPlacement.NormalPlacement fallback = chatboxPlacement.captureDialogueFallbackPlacement(target);
		if (fallback == null || !dialogueSession.captureNormalPlacement(fallback, canvasWidth, canvasHeight)) {
			return;
		}
		dialogueEmergencyFallbackCaptured = true;
	}

	/**
	 * ================================================================
	 * NORMAL CHATBOX RESTORATION
	 * ================================================================
	 */
	private void restorePreDialogueNormalPlacement(int width, int height) {
		if (dialogueFitActive || dialogueTransitionPending || dialoguePrompts.isActive() || dialogueHostDragActive || dialogueAwaitingNativeSave || dialogueSession.getRestoreExpected() != null) {
			return;
		}
		/* The normal snapshot may genuinely be missing after a native
		 * reset. Recover from a valid core normal reference first; only
		 * then from the temporary dialogue center or aligned live host.
		 * The emergency reference is session-local until RESTORE_VERIFIED. */
		if (dialogueSession.getNormalPlacement() == null && dialogueSession.isClosureConfirmed()) {
			captureMissingNormalRestoreReference(width, height);
		}
		final ChatboxPlacement.NormalPlacement snapshot = dialogueSession.getNormalPlacement();
		if (snapshot == null) {
			return;
		}
		dialogueNormalRestoreWriting = true;
		final Rectangle expected;
		try {
			expected = dialogueSession.requestRestore(chatboxPlacement, width, height, client.getCanvasWidth(), client.getCanvasHeight());
			if (expected == null) {
				/* Rejecting the native write does not invalidate the durable
				 * normal center. Avoid adopting any dialogue position. */
				dialogueSession.discardNormalPlacement();
			}
		} finally {
			dialogueNormalRestoreWriting = false;
		}
	}

	private void tracePreDialogueRestoreReadback() {
		final Rectangle expected = dialogueSession.getRestoreExpected();
		if (expected == null) {
			return;
		}
		final Widget slot = getSlot(getLayout());
		final Rectangle rendered = slot != null ? slot.getBounds() : null;
		final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
		final String saveState = chatboxPlacement.normalRestoreReadbackDiagnostic();
		/* A native overlay reload can emit several configuration saves.
		 * They belong to RESTORATION, not a new physical dialogue drag. */
		final DialogueSession.RestoreReadback readback;
		dialogueNormalRestoreWriting = true;
		try {
			readback = dialogueSession.observeRestoreReadback(rendered, nativeBounds, saveState, client.getGameCycle(), chatboxPlacement, DIALOGUE_SETTLED_ALIGNMENT_WAIT_FRAMES);
		} finally {
			dialogueNormalRestoreWriting = false;
		}
		if (readback.getOutcome() == DialogueSession.RestoreOutcome.VERIFIED) {
			/* The core alone owns the durable NORMAL placement. The
			 * session provides the evidence, not a center assignment. */
			final boolean normalCenterUpdated = onVerifiedNormalChatboxRestoration(expected);
			if (dialogueEmergencyFallbackCaptured && !normalCenterUpdated) {
				/* Physical restoration is not enough: when the durable
				 * normal center was missing, retain this session until the
				 * normal placement owner has adopted the verified target.
				 * A bounded retry leaves the target pending for core's
				 * normal-chat reconciliation if saves are still settling. */
				if (dialogueEmergencyAdoptLastCycle != client.getGameCycle()) {
					dialogueEmergencyAdoptLastCycle = client.getGameCycle();
					++dialogueEmergencyAdoptWaitFrames;
				}
				if (dialogueEmergencyAdoptWaitFrames < DIALOGUE_SETTLED_ALIGNMENT_WAIT_FRAMES) {
					return;
				}
				onUnverifiedNormalChatboxRestoration(expected);

				releaseNormalPresentationGuard();
				clearDialogueSessionCenter();
				return;
			}

			if (isDialogueTraceEnabled()) {
				LOG.debug("[Chat XL][Dialogue Restore] event=VERIFIED" + " | center={} | expected={} | rendered={} | native={}" + " | durableUpdated={} | modeDecision={} | mode={}", getNormalChatboxPlacement() != null ? getNormalChatboxPlacement().getCenter() : null, rectangleBounds(expected), rectangleBounds(rendered), rectangleBounds(nativeBounds), normalCenterUpdated, readback.getDecision(), chatboxPlacement.currentMode());
			}
			/* The renderer has returned to normal chat, but a previous
			 * dialogue fit may still be the logical geometryState. Retire
			 * that rectangle only after verified native AND widget readback,
			 * so a new dialogue cannot inherit a stale committed host. */
			if (geometryState == null || !expected.equals(geometryState.desiredBounds) || !expected.equals(geometryState.effectiveBounds)) {
				geometryState = new GeometryState(expected.width, expected.height, expected, expected);
				chatboxBoundsTracker.reset();
			}
			releaseNormalPresentationGuard();
			normalChatRestoredEvent = true;
			clearDialogueSessionCenter();
			return;
		}
		if (readback.getOutcome() == DialogueSession.RestoreOutcome.UNCHANGED_CYCLE || readback.getOutcome() == DialogueSession.RestoreOutcome.NOT_REQUESTED) {
			return;
		}

		if (readback.getOutcome() == DialogueSession.RestoreOutcome.TIMED_OUT) {
			onUnverifiedNormalChatboxRestoration(expected);
			/* A renderer that reached the final rectangle can still need one
			 * chat-row refresh when native overlay readback lagged too long. */
			if (expected.equals(rendered)) {
				normalChatRestoredEvent = true;
			}
			/* Do not present a stale dialogue position forever. Failure is
			 * logged, then normal native behavior resumes for later recovery. */

			releaseNormalPresentationGuard();
			clearDialogueSessionCenter();
		}
	}

	private boolean restoreConfiguredDialogueGeometry(GeometryState current) {
		dialogueFitResolving = true;
		restoringClippedDialogueHost = dialogueFitClippedFallback && !current.desiredBounds.equals(current.effectiveBounds);
		try {
			final Rectangle previous = new Rectangle(current.effectiveBounds);
			/* A confirmed expanded drop may have saved the full dialogue width.
			 * Preserve physical right/bottom canvas contact when restoring the
			 * configured size; leave floating and unconfirmed drops untouched. */
			final Rectangle confirmedDrop = dialogueUserDropPinned && dialogueCornerReference != null ? new Rectangle(dialogueCornerReference) : null;
			clearDialogueFitState();
			if (dialogueSession.getNormalPlacement() == null) {
				chatboxPlacement.rebaseExpandedDropForConfiguredSize(confirmedDrop, current.configuredWidth, current.configuredHeight);
			}
			final ResizeResult result = applySizeInternal(current.configuredWidth, current.configuredHeight, false, null);
			restorePreDialogueNormalPlacement(current.configuredWidth, current.configuredHeight);

			return result.isApplied() && geometryState != null && !previous.equals(geometryState.effectiveBounds);
		} finally {
			restoringClippedDialogueHost = false;
			dialogueFitResolving = false;
		}
	}

	private void resetDialoguePrompts() {
		dialogueClipFromTop = false;
		dialogueClipOriginObserved = false;
		dialogueClipContentFingerprint = Integer.MIN_VALUE;
		final DialoguePrompts.Result result = dialoguePrompts.reset();
		recordMutations(result.getMutations());
		recordRevalidates(result.getRevalidates());
	}

	@Override
	protected boolean isDialogueHostSessionActive() {
		return dialoguePrompts.isActive() || dialogueSession.isPresent() || dialogueSession.getNormalPlacement() != null || dialogueSession.getRestoreExpected() != null || dialogueFitActive || dialogueTransitionPending || dialogueNormalRestoreWriting || dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended || dialogueHostDragActive;
	}

	@Override
	protected boolean isChatboxPointerHeld() {
		return dialogueMouseHeld;
	}

	/**
	 * ================================================================
	 * NATIVE LIFECYCLE CALLBACKS
	 * ================================================================
	 */
	@Override
	protected void onDialogueGameStateReset() {
		dialogueNativeSnapTransitionTarget = null;
		resetDialogueEmergencyFallback();
		dialogueSession.discardNormalPlacement();
		dialogueSession.resetRestoreReadback();
		releaseNormalPresentationGuard();
		dialogueSession.reset();
		normalChatRestoredEvent = false;
		clearDialogueFitState();
		dialogueAwaitingNativeSave = false;
		dialogueUnconfirmedDropSuspended = false;
		dialogueNativeSaveWaitFrames = 0;
		dialogueFitResolving = false;
	}

	@Override
	protected void onDialogueInactiveLayout() {
		dialogueNativeSnapTransitionTarget = null;
		resetDialogueEmergencyFallback();
		dialogueSession.discardNormalPlacement();
		dialogueSession.resetRestoreReadback();
		releaseNormalPresentationGuard();
		dialogueSession.reset();
		normalChatRestoredEvent = false;
		clearDialogueFitState();
		dialogueFitResolving = false;
	}

	@Override
	protected void onDialogueCanvasSizeChanging() {
		dialogueNativeSnapTransitionTarget = null;
		if (!canvasResizePending && dialogueFitActive && dialoguePrompts.isActive() && dialogueFitGeometry != null && !dialogueHostDragActive) {
			dialogueCanvasRebasePending = true;
			dialogueCanvasRebaseReference = new Rectangle(dialogueFitGeometry.desiredBounds);
			dialogueCanvasRebaseOldWidth = lastDialogueCanvasWidth;
			dialogueCanvasRebaseOldHeight = lastDialogueCanvasHeight;
		}
	}

	@Override
	protected void onDialogueNativeGeometryRestore() {
		releaseNormalPresentationGuard();
	}

	@Override
	protected void onDialoguePromptsReset() {
		resetDialoguePrompts();
	}

	@Override
	protected void onDialogueScriptPreFired(int scriptId) {
		if (dialogueFitGeometry != null && scriptId == DIALOGUE_REBUILD) {
			if (dialogueSession.isPresent() && dialoguePrompts.isActive()) {
				markDialoguePageRebuild();
			}
			dialogueTransitionPending = true;
			dialogueTransitionReady = false;
			dialogueRelayoutDirty = true;
		}
	}

	@Override
	protected void onDialogueNativeLayerClose() {
		dialogueSession.onNativeLayerClose();
	}

	@Override
	protected boolean onDialogueScriptPostFired(int scriptId) {
		if (dialogueTransitionPending && scriptId == DIALOGUE_REBUILD) {
			dialogueTransitionReady = true;
			dialogueRelayoutDirty = true;
			return true;
		}
		return false;
	}

	@Override
	protected boolean shouldDeferChatboxResize(int width, int height) {
		/* Core may also resize outside the collision-render pass. Hold native
		 * snap handoffs without losing the existing native-save/drag guard. */
		return ((dialogueAwaitingNativeSave || dialogueHostDragActive || unclassifiedNativeDialogueDrag()) && committedDialogueGeometry(width, height) != null) || staleSnappedDialogueHost() || unverifiedManualDialogueHost();
	}

	@Override
	protected boolean prepareDialogueHostFit(int width, int height) {
		/* Native AUTO invalidates manual temporary dialogue placement. */
		if (dialogueFitActive && !canvasResizePending && chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.NATIVE && !dialogueNativeAutoFit) {
			clearDialogueFitState();
		}
		boolean restoreTemporaryHost = false;
		if (dialogueFitActive && (width != dialogueFitConfiguredWidth || height != dialogueFitConfiguredHeight)) {
			restoreTemporaryHost = true;
			clearDialogueFitState();
		}
		return restoreTemporaryHost;
	}

	@Override
	protected int requestedChatboxWidth(int width) {
		return dialogueFitActive ? dialogueFitRequestedWidth : width;
	}

	@Override
	protected int requestedChatboxHeight(int height) {
		return dialogueFitActive ? dialogueFitRequestedHeight : height;
	}

	@Override
	protected ChatboxPlacement.State captureChatboxPlacement(Widget slot, int width, int height) {
		return dialogueFitActive && dialogueFitGeometry != null
				? chatboxPlacement.captureLogical(dialogueFitGeometry.desiredBounds, width, height)
				: super.captureChatboxPlacement(slot, width, height);
	}

	@Override
	protected boolean allowChatboxHostAnchorUpdate() {
		return !dialogueFitActive && !dialogueFitResolving && !canvasResizePending;
	}

	@Override
	protected InterfaceBounds.Overrides refineChatboxCollisionOverrides(InterfaceBounds.Overrides overrides) {
		return dialogueFitActive || dialoguePrompts.isActive() ? liveDialogueCollisionOverrides(overrides) : overrides;
	}

	@Override
	protected void onChatboxCollisionSolved(ChatboxPlacement.State placement, InterfaceBounds.Overrides overrides, ChatboxBounds.Result result, Widget slot, Widget universe, int width, int height, int requestedWidth, int requestedHeight) {
		rememberDialogueIdealCollision(result, overrides);
	}

	@Override
	protected Rectangle selectChatboxHostBounds(Rectangle desiredBounds, Rectangle effectiveBounds) {
		return dialogueFitActive && !dialogueFirstContactHostPreserved && shouldUseEffectiveDialogueHost(dialogueFitClippedFallback) ? effectiveBounds : desiredBounds;
	}

	@Override
	protected boolean updateDialogueTemporaryHost(Widget slot, ChatboxPlacement.State placement, Rectangle hostBounds, boolean restoreTemporaryHost) {
		/* Hold temporary native location for the entire adaptive dialogue fit lifetime. */
		final boolean temporaryHost = dialogueFitActive || dialogueFitResolving || restoreTemporaryHost;
		final boolean nativeOwned = placement.getMode() == ChatboxPlacement.PlacementMode.NATIVE || placement.getMode() == ChatboxPlacement.PlacementMode.SNAPPED;
		final boolean updateTemporaryHostLocation = temporaryHost && !canvasResizePending && !nativeOwned && (!dialogueFitActive || dialogueFitClippedFallback || placement.getMode() == ChatboxPlacement.PlacementMode.MANUAL || dialogueNativeAutoFit);
		final boolean allowUnmanagedHost = dialogueFitClippedFallback || restoringClippedDialogueHost || dialogueNativeAutoFit;
		final boolean moveTemporaryHost = updateTemporaryHostLocation && (placement.allowsDirectHostMove() || allowUnmanagedHost);
		if (temporaryHost) {
			final boolean overlayMoved = dialogueFitActive && !dialogueHostDragActive && !nativeOwned && chatboxPlacement.applyTemporaryHost(hostBounds);
			if (!overlayMoved) {
				chatboxPlacement.applyHostBounds(hostBounds, updateTemporaryHostLocation, allowUnmanagedHost);
			}
		}
		final boolean changed = moveTemporaryHost && chatboxPlacement.moveHost(slot, hostBounds, allowUnmanagedHost);
		if (changed) {
			recordMutation();
		}
		return changed;
	}

	@Override
	protected boolean shouldRevalidateHostPosition() {
		/* A temporary dialogue X/Y correction must not restart RuneLite relayout. */
		return !dialogueFitActive && !dialogueFitResolving;
	}

	@Override
	protected void onChatboxEffectiveSizeApplied(int effectiveWidth, int effectiveBodyHeight) {
		syncDialoguePrompts(effectiveWidth, effectiveBodyHeight);
	}

	@Override
	protected void onDialogueCollisionBeforeRender(int width, int height) {
		reconcileDialogueCollisions(width, height);
	}

	@Override
	protected void onDialogueBeforeRender(int width, int height) {
		reconcileDialogueBeforeRender(width, height);
	}

	@Override
	protected void onDialogueSettledDrop() {
		settleDialogueDrop();
	}

	@Override
	protected void onDialogueCanvasSettled(int width, int height) {
		lastDialogueCanvasWidth = Math.max(0, client.getCanvasWidth());
		lastDialogueCanvasHeight = Math.max(0, client.getCanvasHeight());
		rebaseDialogueAfterCanvasResize(width, height);
	}

	@Override
	protected void markDialogueRelayoutDirty() {
		dialogueRelayoutDirty = true;
	}

	@Override
	protected boolean onDialogueNativeChatboxPlacementSave(int width, int height) {
		/* The saved location was restored from the verified orthogonal fitting acquisition
		 * after an unrelated overlay reset. Acknowledge that configuration
		 * event without turning it into a fresh chatbox drag or discarding
		 * our temporary host owner. */
		if (dialogueObstacleResetRecoveryPending) {
			dialogueObstacleResetRecoveryPending = false;
			chatboxPlacement.markNativePlacementAdopted();

			return true;
		}
		if (dialogueNormalRestoreWriting || chatboxPlacement.isNormalRestoreSave() && !dialoguePrompts.isActive() && !dialogueMouseHeld) {
			return true;
		}
		/* RuneLite may save on AWT while the mouse is still down. Apply the
		 * new placement only after the physical drag has ended. */
		if (dialogueHostDragActive) {
			dialogueNativeSaveDuringDrag = true;
			return true;
		}
		/* Outside a dialogue the core owns saved-placement deduplication,
		 * resize fallback and normal-center invalidation. */
		if (!isDialogueHostSessionActive()) {
			return false;
		}
		final boolean recoveredUnconfirmed = dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended;
		final boolean newSave = chatboxPlacement.isNewNativePlacementSave();
		/* RuneLite can write the chatbox overlay while another movable overlay is
		 * being dragged. It is not a completed CHATBOX_DRAG, and therefore must
		 * not clear a valid dialogue fit or adopt that incidental host position. */
		if (newSave && dialogueSession.isPresent() && dialoguePrompts.isActive() && "OBSTACLE_DRAG".equals(dialogueInteraction) && !dialogueMouseHeld) {
			/* The native overlay may have replaced our temporary preferred
			 * location. Reacquire its NEW saved location as the offset base,
			 * while keeping the old, collision-owned preferred rectangle. */
			chatboxPlacement.invalidateSavedPlacement();
			chatboxPlacement.authorizeSavedPlacementHandoff();
			chatboxPlacement.markNativePlacementAdopted();
			dialogueCollisionFingerprint = Long.MIN_VALUE;

			return true;
		}
		/* An explicit rejoin may complete an unsaved-drop wait with unchanged
		 * config. In all other cases the identical native save is not a new drag. */
		if (!newSave && !recoveredUnconfirmed) {

			return true;
		}
		/* The physical drop is already adopted for this press. A later native
		 * location/origin save acknowledges persistence; it must not adopt the
		 * same dropped host again or reset the fit/corner reference. */
		final boolean physicalDrop = dialogueHostDragStart != null && "CHATBOX_DRAG".equals(dialogueInteraction);
		final boolean sameGesture = dialoguePrompts.isActive() && !dialogueMouseHeld && dialogueAdoptedPhysicalPressSerial == dialogueMousePressSerial;
		if (sameGesture && (physicalDrop || recoveredUnconfirmed)) {
			dialogueAwaitingNativeSave = false;
			dialogueNativeSaveWaitFrames = 0;
			dialogueUnconfirmedDropSuspended = false;
			chatboxPlacement.markNativePlacementAdopted();

			return true;
		}
		if (sameGesture && !recoveredUnconfirmed) {

			return true;
		}
		dialogueAwaitingNativeSave = false;
		dialogueNativeSaveWaitFrames = 0;
		dialogueUnconfirmedDropSuspended = false;

		/*
		 * RuneLite saves the top-left of the EXPANDED dialogue when it is dragged.
		 * Do not clear the fit and reinterpret that same point as a configured
		 * 200x200 placement: centered expansion would move the visible box again.
		 */
		final GeometryState committed = dialogueDragGeometry(width, height);
		final Widget slot = getSlot(getLayout());
		final ChatboxPlacement.PlacementMode mode = chatboxPlacement.currentMode();

		if (mode != ChatboxPlacement.PlacementMode.NATIVE) {
			dialogueNativeAutoFit = false;
		}
		/* A genuine native drag can snap to a RuneLite corner on release.
		 * Preserve its FULL expanded drop rectangle instead of reinterpreting
		 * its upper-left point as the configured 200x200 rectangle. */
		final boolean confirmedDrag = dialogueHostDragStart != null && (recoveredUnconfirmed || dialogueNativeSaveDuringDrag || dialogueUserDropPinned);
		final boolean manualDrop = mode == ChatboxPlacement.PlacementMode.MANUAL || mode == ChatboxPlacement.PlacementMode.RUNTIME_DRAG || mode == ChatboxPlacement.PlacementMode.SNAPPED && confirmedDrag;
		/* A matching widget and native overlay prove only that RuneLite rendered
		 * the same geometry. They do NOT prove a user dragged this chatbox.
		 * An incidental native save during a fit must never replace its saved
		 * corner, even when the physical host happens to match the fit size. */
		final boolean verifiedPhysicalDrop = dialogueHostDragStart != null && "CHATBOX_DRAG".equals(dialogueInteraction) && !dialogueMouseHeld && dialogueClassifiedChatboxPressSerial == dialogueMousePressSerial && dialogueAdoptedPhysicalPressSerial != dialogueMousePressSerial;
		if (committed != null && dialogueSession.isPresent() && dialoguePrompts.isActive() && !verifiedPhysicalDrop) {
			chatboxPlacement.acknowledgeUnverifiedNativeSave();

			return true;
		}
		if (committed != null && dialogueSession.isPresent() && dialoguePrompts.isActive() && slot != null && manualDrop) {
			final Rectangle expectedHost = dialoguePhysicalHostBounds(committed);
			final Rectangle nativeOverlay = chatboxPlacement.nativeOverlayBounds();
			final Rectangle widgetBounds = slot.getBounds();
			final boolean overlayValid = sameSize(nativeOverlay, expectedHost);
			final boolean widgetValid = sameSize(widgetBounds, expectedHost);
			final boolean nativeRuntimeDrag = mode == ChatboxPlacement.PlacementMode.RUNTIME_DRAG || dialogueHostDragActive;
			GeometryState dropGeometry = committed;
			Rectangle dropReference = expectedHost;
			Rectangle dropped;
			if (nativeRuntimeDrag && widgetValid) {
				dropped = new Rectangle(widgetBounds);
			} else if (overlayValid) {
				dropped = nativeOverlay;
			} else {
				dropped = widgetValid ? new Rectangle(widgetBounds) : null;
			}
			/* On a real released drag, a collision may have reduced the physical
			 * host from the retained fitted size. The saved position then belongs
			 * to the latest LIVE geometry, not necessarily to the earlier fit.
			 * Only accept this alternate source if the actual RuneLite widget and
			 * native overlay agree, and the drag was classified at mouse-down. */
			if (dropped == null && verifiedPhysicalDrop && widgetBounds != null && widgetBounds.equals(nativeOverlay)) {
				final GeometryState live = geometryState != null && geometryState.configuredWidth == width && geometryState.configuredHeight == height ? geometryState : null;
				if (live != null && sameSize(widgetBounds, live.effectiveBounds)) {
					dropGeometry = live;
					dropReference = live.effectiveBounds;
				} else if (live != null && sameSize(widgetBounds, live.desiredBounds)) {
					dropGeometry = live;
					dropReference = live.desiredBounds;
				} else if (sameSize(widgetBounds, committed.effectiveBounds)) {
					dropReference = committed.effectiveBounds;
				} else if (sameSize(widgetBounds, committed.desiredBounds)) {
					dropReference = committed.desiredBounds;
				} else {
					dropReference = null;
				}
				if (dropReference != null) {
					dropped = new Rectangle(widgetBounds);
				}
			}

			if (dropped != null) {
				final int dx = dropped.x - dropReference.x;
				final int dy = dropped.y - dropReference.y;
				final Rectangle desired = new Rectangle(dropGeometry.desiredBounds);
				final Rectangle effective = new Rectangle(dropGeometry.effectiveBounds);
				desired.translate(dx, dy);
				effective.translate(dx, dy);
				chatboxPlacement.invalidateSavedPlacement();
				if (!dialogueMouseHeld) {
					chatboxPlacement.authorizeSavedPlacementHandoff();
				}
				dialogueFitGeometry = new GeometryState(width, height, desired, effective);
				geometryState = dialogueFitGeometry;
				updateDialogueReferenceForConfirmedDrag(dropped);
				resetDialogueCornerReference(dropped);
				rebaseConfirmedDialogueHost(dropped);
				dialogueUserDropPinned = true;
				dialogueConfirmedDropBounds = new Rectangle(dropped);
				dialoguePinnedRejectedFitKey = Long.MIN_VALUE;
				if (dialogueHostDragStart != null) {
					dialoguePostDropSearchPending = true;
				}
				dialogueDropSample = new Rectangle(dropped);
				dialogueDropSettlePending = true;
				dialogueDropSettleDelay = 2;
				dialogueDropSettleAlignmentWaits = 0;
				dialogueCollisionFingerprint = Long.MIN_VALUE;
				dialogueCollisionDropPending = true;
				/* A settled search may change size, but must keep this dropped origin. */
				dialogueFitRetryStrict = true;
				if (physicalDrop || confirmedDrag) {
					dialogueAdoptedPhysicalPressSerial = dialogueMousePressSerial;
				}
				chatboxPlacement.markNativePlacementAdopted();

				return true;
			}
		}

		return false;
	}

	@Override
	protected void clearDialogueFitForNativeSaveFallback() {
		clearDialogueFitState();
		dialogueFitResolving = false;
		dialogueCollisionFingerprint = Long.MIN_VALUE;
	}

	/**
	 * ================================================================
	 * USER-FACING FITMENT OVERLAY
	 * ================================================================
	 */
	public int getDialogueVisibleContentEdges() {
		final GeometryState state = geometryState;
		return dialogueViewport.visibleContentEdges(state != null ? state.effectiveBounds : null, chatboxButtonsHidden);
	}

	public Rectangle getDialogueTextBounds() {
		return dialogueViewport.textBounds();
	}

	public Rectangle getDialogueContinueBounds() {
		return dialogueViewport.continueBounds();
	}

	public void traceDialogueViewportDiagnostics() {
		if (!isDialogueTraceEnabled() || dialogueHostDragActive || dialogueCollisionDragPending) {
			return;
		}
		if (!dialoguePrompts.isActive()) {
			dialogueDiagnostics.reset("viewport");
			return;
		}
		final GeometryState state = geometryState;
		if (state == null) {
			return;
		}

		// Build the read-only snapshot only while ::debug-dialogue is armed.
		final String report = "desired=" + state.desiredBounds + " | effective=" + state.effectiveBounds + " | host=" + dialoguePhysicalHostBounds(state) + " | clipped=" + dialogueFitClippedFallback + " | native=" + chatboxPlacement.nativeOverlayBounds();
		dialogueDiagnostics.changed("viewport", "Dialogue Geometry", report, report);
	}

	private void rememberDialogueIdealCollision(ChatboxBounds.Result result, InterfaceBounds.Overrides overrides) {
		final List<Rectangle> obstacles = result != null && dialoguePrompts.isActive() ? InterfaceBounds.dialogueCollisionObstacles(client, overrides) : null;
		dialogueViewport.rememberIdealCollision(result, obstacles);
	}

	public Rectangle getDialogueIdealBounds() {
		final GeometryState state = dialogueHostDragActive && dialogueDragPreview != null ? dialogueDragPreview : geometryState;
		if (state == null || !isResizableLayout(getLayout()) || !dialoguePrompts.isActive()) {
			return null;
		}
		final Rectangle visible = state.effectiveBounds;
		final Rectangle reference = dialogueOrthogonalFitBaseline != null && dialogueOrthogonalBaselineContentFingerprint == dialoguePrompts.fitFingerprint() ? dialogueOrthogonalFitBaseline : state.desiredBounds;
		final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		final List<Rectangle> obstacles = InterfaceBounds.dialogueCollisionObstacles(client, overrides);
		return dialogueViewport.idealBounds(new DialogueViewport.IdealSnapshot(state.desiredBounds, visible, reference, state.configuredHeight, chatboxButtonsHidden, dialogueFitConstrainedWrap, dialogueCornerBottom, dialogueClipContentFingerprint, dialogueClipOriginObserved, dialogueFitClippedFallback, dialogueClipFromTop, DialogueResizeService.activeOrthogonalEdges(reference, visible, obstacles), obstacles, isDialogueTraceEnabled(), dialogueDiagnostics));
	}

	public Rectangle getDialogueRenderedViewportBounds() {
		return dialogueViewport.renderedViewport(isResizableLayout(getLayout()), getDialogueEffectiveBounds());
	}

	public Rectangle getDialoguePhysicalHostBounds() {
		final ChatboxLayout layout = getLayout();
		final boolean resizable = isResizableLayout(layout);
		return dialogueViewport.physicalHost(resizable, resizable ? getSlot(layout) : null);
	}

	public Rectangle getDialogueNativeOverlayBounds() {
		return dialogueViewport.nativeOverlay(isResizableLayout(getLayout()));
	}

	public String dialogueFitOutlineDeferralReason() {
		if (unverifiedManualDialogueHost()) {
			return "UNVERIFIED_MANUAL_HOST";
		}
		/* A trusted page rebuild may temporarily change the native slot
		 * dimensions before the new content fit has been committed. Hold
		 * the outline until both physical hosts return to the fit target. */
		if (dialoguePageRebuildPending && geometryState != null) {
			final Widget slot = getSlot(getLayout());
			final Rectangle rendered = slot != null ? slot.getBounds() : null;
			final Rectangle nativeBounds = chatboxPlacement.nativeOverlayBounds();
			final Rectangle expected = dialoguePhysicalHostBounds(geometryState);
			if (expected == null || !expected.equals(rendered) || !expected.equals(nativeBounds)) {
				return "PAGE_REBUILD_HANDOFF";
			}
		}
		if (dialogueHostDragActive || dialogueAwaitingNativeSave || dialogueUnconfirmedDropSuspended || unclassifiedNativeDialogueDrag()) {
			return "NATIVE_DRAG_HANDOFF";
		}
		if (dialogueCollisionDropPending || dialogueCollisionDropFrames > 0) {
			return "DROP_FIT_SETTLING";
		}
		/* The orthogonal fitting proposal is selected before the standard solver in
		 * reconcileDialogueFit(). During a live side collision that can
		 * use orthogonal fitting on release, do not briefly paint the standard solver's
		 * one-sided ideal while that orthogonal choice is unresolved. */
		if (dialogueMouseHeld && dialogueCollisionDragObserved && hasVerifiedFirstContactReference(geometryState) && !dialogueFitActive && !dialogueHostDragActive && chatboxPlacement.currentMode() == ChatboxPlacement.PlacementMode.MANUAL && geometryState != null && !geometryState.desiredBounds.equals(geometryState.effectiveBounds) && geometryState.desiredBounds.width == geometryState.configuredWidth && geometryState.desiredBounds.height == geometryState.configuredHeight) {
			final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
			final int edges = DialogueResizeService.activeOrthogonalEdges(geometryState.desiredBounds, geometryState.effectiveBounds, InterfaceBounds.dialogueCollisionObstacles(client, overrides));
			if (edges == DialogueFitSolver.COLLISION_LEFT || edges == DialogueFitSolver.COLLISION_RIGHT) {
				return "ORTHOGONAL_FIT_SETTLING";
			}
		}
		if (dialogueTransitionPending || dialogueRelayoutDirty || dialogueFitResolving || canvasResizePending) {
			return "LAYOUT_PENDING";
		}
		return null;
	}

	public boolean isDialogueContentClipped() {
		return isDialogueContentClipped(isDialogueContentObscuredByObstacle());
	}

	/* The overlay already obtains this read-only foreground result. Reuse it
	 * rather than capturing all movable obstacles twice in one render. */
	boolean isDialogueContentClipped(boolean foregroundObscured) {
		final GeometryState state = geometryState;
		return foregroundObscured || dialogueViewport.isContentClipped(state != null ? state.effectiveBounds : null, dialogueFitConstrainedWrap, chatboxButtonsHidden);
	}

	/*
	 * Obstacle pass-through at the established width minimum is legal geometry,
	 * but it must not be reported as fully visible if a foreground actually
	 * covers a rendered name, option, message or continue text widget.
	 */
	public boolean isDialogueContentObscuredByObstacle() {
		final GeometryState state = geometryState;
		if (state == null || !dialoguePrompts.isActive() || state.effectiveBounds.width != 200) {
			return false;
		}
		final InterfaceBounds.Overrides overrides = liveDialogueCollisionOverrides(collisionInterfaceOverrides);
		return dialogueViewport.isContentObscuredByObstacles(state.effectiveBounds, chatboxButtonsHidden, InterfaceBounds.dialogueCollisionObstacles(client, overrides));
	}

	public Rectangle getDialogueDesiredBounds() {
		final GeometryState state = dialogueHostDragActive && dialogueDragPreview != null ? dialogueDragPreview : geometryState;
		return state != null && dialoguePrompts.isActive() ? new Rectangle(state.desiredBounds) : null;
	}

	public Rectangle getDialogueEffectiveBounds() {
		final GeometryState state = dialogueHostDragActive && dialogueDragPreview != null ? dialogueDragPreview : geometryState;
		return state != null && dialoguePrompts.isActive() ? new Rectangle(state.effectiveBounds) : null;
	}

	private static final class DialogueFitProposal {
		private final DialogueResizeService.FitPath reason;
		private final DialogueFitCandidate candidate;
		private final Rectangle baseline;
		private final int contentFingerprint;
		private final long failureFingerprint;
		private final boolean firstContact;

		private DialogueFitProposal(DialogueResizeService.FitPath reason, DialogueFitCandidate candidate, Rectangle baseline, int contentFingerprint, long failureFingerprint) {
			this(reason, candidate, baseline, contentFingerprint, failureFingerprint, false);
		}

		private DialogueFitProposal(DialogueResizeService.FitPath reason, DialogueFitCandidate candidate, Rectangle baseline, int contentFingerprint, long failureFingerprint, boolean firstContact) {
			this.reason = reason;
			this.candidate = candidate;
			this.baseline = baseline != null ? new Rectangle(baseline) : null;
			this.contentFingerprint = contentFingerprint;
			this.failureFingerprint = failureFingerprint;
			this.firstContact = firstContact;
		}
	}
}
