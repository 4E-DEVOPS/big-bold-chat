package com.bigboldchat.dialogue;

import com.bigboldchat.chatbox.ChatboxPlacement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.IdentityHashMap;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Owns one conversation's temporary center and lifecycle. Never owns the
 * durable normal-chatbox placement or mutates RuneLite host size/position.
 * It does own the temporary visibility-only presentation guard.
 *
 * A single inactive widget sample is not a conversation close: RuneLite
 * unmounts the dialogue root between options/player/NPC pages. The close
 * decision requires a stable return to visible normal chat across distinct
 * client cycles, optionally accompanied by a native close-script signal.
 */
public final class DialogueSession {
	private static final int NORMAL_CHAT_CYCLES_TO_CLOSE = 2;
	private static final int SCRIPT_CLOSE_CYCLES_TO_CLOSE = 2;
	/* A saved snap corner may settle after the initial restore window.
	 * Keep verifying its original mode until the native anchor can stabilize. */
	private static final int SNAP_MODE_SETTLE_CYCLES = 24;
	private static final int SNAP_MODE_MAX_READBACKS = 40;

	private enum State {
		IDLE,
		ACTIVE,
		TRANSITION,
		RESTORING
	}

	private State state = State.IDLE;
	private Point center;
	private int lastDragSerial = Integer.MIN_VALUE;
	private int lastObservedCycle = Integer.MIN_VALUE;
	private int normalChatCycles;
	private boolean closeScriptObserved;
	private boolean closureConfirmed;
	private DialoguePrompts.Surface lastSurface = DialoguePrompts.Surface.NONE;
	/* Capture the original normal placement once for the entire session.
	 * The snapshot retains physical position independently of RuneLite's
	 * saved MANUAL, NATIVE, or SNAPPED placement mode. */
	private ChatboxPlacement.NormalPlacement normalPlacement;
	private int originalCanvasWidth;
	private int originalCanvasHeight;

	/* A native overlay moves asynchronously. Keep its restore readback state
	 * with this session, never with the persistent normal-chatbox placement. */
	private Rectangle restoreExpected;
	private Rectangle restoreBeforeNative;
	private Rectangle restoreLastNative;
	private String restoreLastSaveState;
	private int restoreLastCycle = Integer.MIN_VALUE;
	private int restoreFrames;
	private int restoreStableFrames;
	private boolean restoreReconciled;

	private final Map<Widget, Boolean> heldNormalChatWidgets = new IdentityHashMap<>();
	private boolean normalPresentationHeld;
	private int normalPresentationGuardFrames;

	/* Placement MODE is restored only after the physical normal-chatbox
	 * rectangle is verified. A moved snap may no longer resolve to that same
	 * rectangle, so mode recovery has a bounded, geometry-preserving fallback. */
	private enum ModeRestorePhase {
		NONE,
		VERIFY_ORIGINAL,
		VERIFY_MANUAL_FALLBACK
	}

	private ModeRestorePhase modeRestorePhase = ModeRestorePhase.NONE;
	private int modeRestoreLastCycle = Integer.MIN_VALUE;
	private int modeRestoreFrames;
	private int modeRestoreStableFrames;
	private int modeRestoreStartCycle = Integer.MIN_VALUE;
	private String modeRestoreDecision = "NOT_REQUESTED";

	public enum RestoreOutcome {
		NOT_REQUESTED,
		UNCHANGED_CYCLE,
		PENDING,
		VERIFIED,
		TIMED_OUT
	}

	public static final class RestoreReadback {
		private final RestoreOutcome outcome;
		private final String decision;
		private final boolean nativeMoved;
		private final boolean rendererNativeAligned;
		private final int stableFrames;

		private RestoreReadback(RestoreOutcome outcome, String decision, boolean nativeMoved, boolean rendererNativeAligned, int stableFrames) {
			this.outcome = outcome;
			this.decision = decision;
			this.nativeMoved = nativeMoved;
			this.rendererNativeAligned = rendererNativeAligned;
			this.stableFrames = stableFrames;
		}

		public RestoreOutcome getOutcome() {
			return outcome;
		}

		public String getDecision() {
			return decision;
		}

		public boolean isNativeMoved() {
			return nativeMoved;
		}

		public boolean isRendererNativeAligned() {
			return rendererNativeAligned;
		}

		public int getStableFrames() {
			return stableFrames;
		}
	}

	/**
	 * ================================================================
	 * NORMAL PLACEMENT SNAPSHOT
	 * ================================================================
	 */
	public boolean captureNormalPlacement(ChatboxPlacement.NormalPlacement placement, int canvasWidth, int canvasHeight) {
		// Capture the physical normal placement once, not on each dialogue page.
		if (normalPlacement != null || placement == null || canvasWidth <= 0 || canvasHeight <= 0) {
			return false;
		}
		normalPlacement = placement;
		originalCanvasWidth = canvasWidth;
		originalCanvasHeight = canvasHeight;
		resetRestoreReadback();
		return true;
	}

	public ChatboxPlacement.NormalPlacement getNormalPlacement() {
		return normalPlacement;
	}

	public void discardNormalPlacement() {
		normalPlacement = null;
	}

	/* Derive an absolute rectangle in the CURRENT canvas. Preserve physical
	 * edge contact but never claim that a floating chatbox was edge-anchored. */
	public Rectangle restoreTarget(int width, int height, int canvasWidth, int canvasHeight) {
		if (normalPlacement == null || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
			return null;
		}
		return normalPlacement.canvasRelativeBounds(originalCanvasWidth, originalCanvasHeight, width, height, canvasWidth, canvasHeight);
	}

	// Pure geometry policy, independently testable without a RuneLite client.
	static Rectangle canvasAwareBounds(Rectangle initial, int originalWidth, int originalHeight, int width, int height, int canvasWidth, int canvasHeight) {
		if (initial == null || originalWidth <= 0 || originalHeight <= 0 || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
			return null;
		}
		int x = initial.x + initial.width / 2 - width / 2;
		int y = initial.y + initial.height / 2 - height / 2;
		if (initial.x == 0) {
			x = 0;
		} else if (initial.x + initial.width == originalWidth) {
			x = canvasWidth - width;
		}
		if (initial.y == 0) {
			y = 0;
		} else if (initial.y + initial.height == originalHeight) {
			y = canvasHeight - height;
		}
		x = Math.max(0, Math.min(x, Math.max(0, canvasWidth - width)));
		y = Math.max(0, Math.min(y, Math.max(0, canvasHeight - height)));
		return new Rectangle(x, y, width, height);
	}

	public Rectangle getRestoreExpected() {
		return restoreExpected != null ? new Rectangle(restoreExpected) : null;
	}

	/**
	 * ================================================================
	 * RESTORATION READBACK & MODE RECOVERY
	 * ================================================================
	 */
	public Rectangle requestRestore(ChatboxPlacement placement, int width, int height, int canvasWidth, int canvasHeight) {
		/* One coordinated request. The shared ChatboxPlacement is still the
		 * only native location writer; rendering/readback cannot be synchronous. */
		final Rectangle target = restoreTarget(width, height, canvasWidth, canvasHeight);
		if (target == null || restoreExpected != null) {
			return null;
		}
		final Rectangle before = placement.nativeOverlayBounds();
		final Rectangle requested = placement.restoreNormalPlacement(normalPlacement, target);
		resetRestoreReadback();
		restoreBeforeNative = before;
		restoreExpected = requested != null ? new Rectangle(requested) : null;
		return getRestoreExpected();
	}

	public RestoreReadback observeRestoreReadback(Rectangle rendered, Rectangle nativeBounds, String saveState, int cycle, ChatboxPlacement placement, int maximumCycles) {
		if (restoreExpected == null) {
			return new RestoreReadback(RestoreOutcome.NOT_REQUESTED, "NOT_REQUESTED", false, false, 0);
		}
		final boolean physicallyAligned = restoreExpected.equals(rendered) && restoreExpected.equals(nativeBounds);
		if (modeRestorePhase != ModeRestorePhase.NONE) {
			if (cycle == modeRestoreLastCycle) {
				return new RestoreReadback(RestoreOutcome.UNCHANGED_CYCLE, "MODE_SAME_CYCLE", false, physicallyAligned, modeRestoreStableFrames);
			}
			modeRestoreLastCycle = cycle;
			++modeRestoreFrames;
			final boolean modeMatched = modeRestorePhase == ModeRestorePhase.VERIFY_ORIGINAL ? placement.originalNormalModeMatches(normalPlacement) : placement.manualModeFallbackMatches(normalPlacement, restoreExpected);
			modeRestoreStableFrames = physicallyAligned && modeMatched ? modeRestoreStableFrames + 1 : 0;
			/* A moved snap can re-anchor a few render callbacks after first
			 * appearing aligned. Never accept an exact-looking early sample. */
			final boolean verifyingSnap = modeRestorePhase == ModeRestorePhase.VERIFY_ORIGINAL && normalPlacement != null && normalPlacement.wasSnapped();
			final boolean snapSettled = !verifyingSnap || cycle - modeRestoreStartCycle >= SNAP_MODE_SETTLE_CYCLES;
			final int requiredStableFrames = verifyingSnap ? 3 : 2;
			if (modeRestoreStableFrames >= requiredStableFrames && snapSettled) {
				modeRestoreDecision = modeRestorePhase == ModeRestorePhase.VERIFY_ORIGINAL ? "ORIGINAL_MODE_VERIFIED" : "MANUAL_FALLBACK_VERIFIED";
				return new RestoreReadback(RestoreOutcome.VERIFIED, modeRestoreDecision, true, true, modeRestoreStableFrames);
			}
			/* The ordinary 8-cycle timeout was previously triggering the
			 * MANUAL fallback long before a snapped anchor could be verified.
			 * Keep native and fallback verification on the existing limit. */
			final int modeTimeout = verifyingSnap ? Math.max(maximumCycles, SNAP_MODE_MAX_READBACKS) : maximumCycles;
			if (modeRestoreFrames >= modeTimeout) {
				if (modeRestorePhase == ModeRestorePhase.VERIFY_ORIGINAL && placement.restoreManualModeFallback(normalPlacement, restoreExpected)) {
					modeRestorePhase = ModeRestorePhase.VERIFY_MANUAL_FALLBACK;
					modeRestoreFrames = 0;
					modeRestoreStableFrames = 0;
					modeRestoreDecision = "ORIGINAL_MODE_REJECTED_MANUAL_FALLBACK";
					return new RestoreReadback(RestoreOutcome.PENDING, modeRestoreDecision, true, physicallyAligned, 0);
				}
				modeRestoreDecision = "ORIGINAL_MODE_FALLBACK_UNVERIFIED";
				return new RestoreReadback(RestoreOutcome.TIMED_OUT, modeRestoreDecision, false, physicallyAligned, 0);
			}
			return new RestoreReadback(RestoreOutcome.PENDING, verifyingSnap && physicallyAligned && modeMatched && !snapSettled ? "WAIT_SNAP_SETTLE" : modeRestorePhase == ModeRestorePhase.VERIFY_ORIGINAL ? "WAIT_ORIGINAL_MODE_AND_GEOMETRY" : "WAIT_MANUAL_FALLBACK_AND_GEOMETRY", false, physicallyAligned, modeRestoreStableFrames);
		}
		if (physicallyAligned && (restoreReconciled || !placement.normalRestoreAnchorChanged(normalPlacement))) {
			if (placement.normalModeRecoveryEligible(normalPlacement) && !placement.originalNormalModeMatches(normalPlacement) && placement.requestOriginalNormalMode(normalPlacement, restoreExpected)) {
				modeRestorePhase = ModeRestorePhase.VERIFY_ORIGINAL;
				modeRestoreLastCycle = cycle;
				modeRestoreStartCycle = cycle;
				modeRestoreFrames = 0;
				modeRestoreStableFrames = 0;
				modeRestoreDecision = "ORIGINAL_MODE_REQUESTED";
				return new RestoreReadback(RestoreOutcome.PENDING, modeRestoreDecision, true, true, 0);
			}
			return new RestoreReadback(RestoreOutcome.VERIFIED, placement.originalNormalModeMatches(normalPlacement) ? "ORIGINAL_MODE_RETAINED" : placement.normalModeRecoveryEligible(normalPlacement) ? "PHYSICAL_VERIFIED_MODE_UNAVAILABLE" : "ALIGNED", true, true, restoreStableFrames);
		}
		if (cycle == restoreLastCycle) {
			return new RestoreReadback(RestoreOutcome.UNCHANGED_CYCLE, "SAME_CYCLE", false, false, restoreStableFrames);
		}
		restoreLastCycle = cycle;
		++restoreFrames;
		final boolean agree = rendered != null && rendered.equals(nativeBounds);
		final boolean nativeMoved = nativeBounds != null && restoreBeforeNative != null && !nativeBounds.equals(restoreBeforeNative);
		/* A wrong live origin may already have settled *before* the restore
		 * request; requiring nativeMoved would disable the guarded correction
		 * indefinitely. Require repeat physical agreement and several cycles
		 * instead, then permit exactly one normal-anchor reload. */
		if (agree && restoreFrames >= 2 && nativeBounds.getSize().equals(restoreExpected.getSize()) && nativeBounds.equals(restoreLastNative) && saveState != null && saveState.equals(restoreLastSaveState)) {
			restoreStableFrames++;
		} else {
			restoreStableFrames = 0;
		}
		restoreLastNative = nativeBounds != null ? new Rectangle(nativeBounds) : null;
		restoreLastSaveState = saveState;
		String decision = !agree ? "WAIT_WIDGET_NATIVE_ALIGNMENT" : restoreStableFrames < 2 ? "WAIT_NATIVE_STABLE" : placement.normalRestoreAnchorChanged(normalPlacement) ? "SETTLED_ORIGIN_MISMATCH" : "SETTLED_MISMATCH";
		if (!restoreReconciled && agree && restoreStableFrames >= 2 && placement.reconcileNormalRestoration(normalPlacement, restoreExpected, nativeBounds)) {
			restoreReconciled = true;
			restoreFrames = 0;
			restoreStableFrames = 0;
			restoreLastNative = null;
			restoreLastSaveState = null;
			decision = "GUARDED_CORRECTION_APPLIED";
		}
		return new RestoreReadback(restoreFrames >= maximumCycles ? RestoreOutcome.TIMED_OUT : RestoreOutcome.PENDING, decision, nativeMoved, agree, restoreStableFrames);
	}

	public void resetRestoreReadback() {
		restoreExpected = null;
		restoreFrames = 0;
		restoreReconciled = false;
		restoreLastCycle = Integer.MIN_VALUE;
		restoreBeforeNative = null;
		restoreLastNative = null;
		restoreLastSaveState = null;
		restoreStableFrames = 0;
		modeRestorePhase = ModeRestorePhase.NONE;
		modeRestoreLastCycle = Integer.MIN_VALUE;
		modeRestoreFrames = 0;
		modeRestoreStableFrames = 0;
		modeRestoreStartCycle = Integer.MIN_VALUE;
		modeRestoreDecision = "NOT_REQUESTED";
	}

	/**
	 * ================================================================
	 * SESSION LIFECYCLE & DRAG
	 * ================================================================
	 */
	public boolean shouldHoldNormalPresentation(boolean normalVisible, boolean dialogueMounted) {
		return isPresent() && normalPlacement != null && !dialogueMounted && normalVisible;
	}

	public boolean isPresent() {
		return state != State.IDLE && center != null;
	}

	public Point getCenter() {
		return center != null ? new Point(center) : null;
	}

	public int getLastDragSerial() {
		return lastDragSerial;
	}

	public boolean hasConfirmedDrag() {
		return lastDragSerial != Integer.MIN_VALUE;
	}

	public boolean isClosureConfirmed() {
		return closureConfirmed;
	}

	// Read-only lifecycle snapshot for diagnosing options-only close gaps.
	public String diagnosticState() {
		return "state=" + state + " | center=" + center + " | normalChatCycles=" + normalChatCycles + " | closeScript=" + closeScriptObserved + " | closureConfirmed=" + closureConfirmed + " | lastCycle=" + lastObservedCycle;
	}

	public boolean begin(Point normalCenter) {
		if (normalCenter == null || isPresent()) {
			return false;
		}
		center = new Point(normalCenter);
		state = State.ACTIVE;
		lastDragSerial = Integer.MIN_VALUE;
		lastObservedCycle = Integer.MIN_VALUE;
		normalChatCycles = 0;
		closeScriptObserved = false;
		closureConfirmed = false;
		return true;
	}

	// Only a verified user drag may modify the session center.
	public boolean recordDrag(Rectangle start, Rectangle drop, int gestureSerial) {
		return recordDrag(start, drop, gestureSerial, false);
	}

	/* After RuneLite externally resets the host, its actual drop midpoint wins.
	 * Normal A2 drags still translate the user-owned center by the true delta. */
	public boolean recordDrag(Rectangle start, Rectangle drop, int gestureSerial, boolean rebaseFromDrop) {
		if (!isPresent() || state == State.RESTORING || start == null || drop == null || lastDragSerial == gestureSerial) {
			return false;
		}
		if (rebaseFromDrop) {
			center = new Point(drop.x + drop.width / 2, drop.y + drop.height / 2);
		} else {
			center.translate(drop.x - start.x, drop.y - start.y);
		}
		lastDragSerial = gestureSerial;
		state = State.ACTIVE;
		closureConfirmed = false;
		normalChatCycles = 0;
		return true;
	}

	// A native close script is evidence, not a definitive end of a dialogue.
	public void onNativeLayerClose() {
		if (isPresent() && state != State.RESTORING) {
			closeScriptObserved = true;
		}
	}

	public void observe(boolean dialogueMounted, boolean normalChatVisible, boolean rebuilding, boolean dragging, int clientCycle) {
		if (!isPresent() || state == State.RESTORING) {
			return;
		}
		if (dialogueMounted) {
			state = State.ACTIVE;
			normalChatCycles = 0;
			closeScriptObserved = false;
			closureConfirmed = false;
			lastObservedCycle = clientCycle;
			return;
		}
		if (rebuilding || dragging || !normalChatVisible) {
			state = State.TRANSITION;
			normalChatCycles = 0;
			closureConfirmed = false;
			lastObservedCycle = clientCycle;
			return;
		}
		state = State.TRANSITION;
		if (clientCycle != lastObservedCycle) {
			lastObservedCycle = clientCycle;
			normalChatCycles++;
		}
		final int minimumCycles = closeScriptObserved ? SCRIPT_CLOSE_CYCLES_TO_CLOSE : NORMAL_CHAT_CYCLES_TO_CLOSE;
		closureConfirmed = normalChatCycles >= minimumCycles;
	}

	public void markRestoring() {
		if (closureConfirmed && isPresent()) {
			state = State.RESTORING;
		}
	}

	public void complete() {
		reset();
	}

	public void reset() {
		normalPlacement = null;
		originalCanvasWidth = 0;
		originalCanvasHeight = 0;
		resetRestoreReadback();
		state = State.IDLE;
		center = null;
		lastDragSerial = Integer.MIN_VALUE;
		lastObservedCycle = Integer.MIN_VALUE;
		normalChatCycles = 0;
		closeScriptObserved = false;
		closureConfirmed = false;
		lastSurface = DialoguePrompts.Surface.NONE;
	}

	/**
	 * ================================================================
	 * NORMAL CHAT PRESENTATION GUARD
	 * ================================================================
	 */
	public int holdNormalPresentation(Client client) {
		/* Presentation changes belong to the session; they must never hide the
		 * shared chatbox host or a newly mounted dialogue root. */
		if (!normalPresentationHeld) {
			normalPresentationGuardFrames = 0;
		}
		normalPresentationHeld = true;
		++normalPresentationGuardFrames;
		int mutations = 0;
		mutations += holdNormalWidget(client, InterfaceID.Chatbox.SCROLLAREA);
		mutations += holdNormalWidget(client, InterfaceID.Chatbox.CHATSCROLLBAR);
		mutations += holdNormalWidget(client, InterfaceID.Chatbox.CHAT_BACKGROUND);
		mutations += holdNormalWidget(client, InterfaceID.Chatbox.CONTROLS);
		return mutations;
	}

	private int holdNormalWidget(Client client, int widgetId) {
		final Widget widget = client.getWidget(widgetId);
		if (widget == null) {
			return 0;
		}
		if (!heldNormalChatWidgets.containsKey(widget)) {
			heldNormalChatWidgets.put(widget, widget.isSelfHidden());
		}
		if (!widget.isSelfHidden()) {
			widget.setHidden(true);
			return 1;
		}
		return 0;
	}

	public boolean isNormalPresentationHeld() {
		return normalPresentationHeld;
	}

	public boolean isNormalWidgetGuarded(Widget widget) {
		return widget != null && heldNormalChatWidgets.containsKey(widget);
	}

	public static final class PresentationRelease {
		private final boolean held;
		private final int mutations;
		private final int guardFrames;

		private PresentationRelease(boolean held, int mutations, int guardFrames) {
			this.held = held;
			this.mutations = mutations;
			this.guardFrames = guardFrames;
		}

		public boolean wasHeld() {
			return held;
		}

		public int getMutations() {
			return mutations;
		}

		public int getGuardFrames() {
			return guardFrames;
		}
	}

	// Only touch the same widget identities the guard originally masked.
	public PresentationRelease releaseNormalPresentation(Client client, boolean normalChatReady) {
		if (!normalPresentationHeld) {
			return new PresentationRelease(false, 0, 0);
		}
		int mutations = 0;
		for (Map.Entry<Widget, Boolean> entry : heldNormalChatWidgets.entrySet()) {
			final Widget widget = entry.getKey();
			final int widgetId = widget.getId();
			if (client.getWidget(widgetId) == widget && !entry.getValue() && widget.isSelfHidden() && (normalChatReady || widgetId == InterfaceID.Chatbox.CHAT_BACKGROUND)) {
				widget.setHidden(false);
				++mutations;
			}
		}
		heldNormalChatWidgets.clear();
		normalPresentationHeld = false;
		final int frames = normalPresentationGuardFrames;
		normalPresentationGuardFrames = 0;
		return new PresentationRelease(true, mutations, frames);
	}

	/* One observation represents the dialogue mount/unmount and normal-chat
	 * presentation handoff for a single client callback. No host writes here. */
	public static final class SurfaceObservation {
		private final DialoguePrompts.Surface previous;
		private final DialoguePrompts.Surface current;
		private final boolean surfaceChanged;
		private final boolean guardStarted;
		private final boolean guardReleased;
		private final boolean normalChatReady;
		private final boolean pendingClose;
		private final int mutations;
		private final int guardFrames;

		private SurfaceObservation(DialoguePrompts.Surface previous, DialoguePrompts.Surface current, boolean guardStarted, boolean guardReleased, boolean normalChatReady, boolean pendingClose, int mutations, int guardFrames) {
			this.previous = previous;
			this.current = current;
			this.surfaceChanged = previous != current;
			this.guardStarted = guardStarted;
			this.guardReleased = guardReleased;
			this.normalChatReady = normalChatReady;
			this.pendingClose = pendingClose;
			this.mutations = mutations;
			this.guardFrames = guardFrames;
		}

		public DialoguePrompts.Surface getPreviousSurface() {
			return previous;
		}

		public DialoguePrompts.Surface getSurface() {
			return current;
		}

		public boolean isSurfaceChanged() {
			return surfaceChanged;
		}

		public boolean isGuardStarted() {
			return guardStarted;
		}

		public boolean isGuardReleased() {
			return guardReleased;
		}

		public boolean isNormalChatReady() {
			return normalChatReady;
		}

		public boolean isPendingClose() {
			return pendingClose;
		}

		public int getMutations() {
			return mutations;
		}

		public int getGuardFrames() {
			return guardFrames;
		}
	}

	public SurfaceObservation observeSurface(Client client, DialoguePrompts.Surface surface, boolean normalChatVisible, boolean rebuilding, boolean dragging, int gameCycle) {
		final DialoguePrompts.Surface previous = lastSurface;
		lastSurface = surface;
		int mutations = 0;
		boolean guardStarted = false;
		boolean guardReleased = false;
		boolean normalChatReady = true;
		int guardedFrames = 0;

		if (!isPresent() && normalPresentationHeld) {
			final PresentationRelease release = releaseNormalPresentation(client, true);
			mutations += release.getMutations();
			guardReleased = release.wasHeld();
			guardedFrames = release.getGuardFrames();
		}
		observe(surface != DialoguePrompts.Surface.NONE, normalChatVisible, rebuilding, dragging, gameCycle);
		if (surface != DialoguePrompts.Surface.NONE) {
			final PresentationRelease release = releaseNormalPresentation(client, false);
			mutations += release.getMutations();
			if (release.wasHeld()) {
				guardReleased = true;
				normalChatReady = false;
				guardedFrames = release.getGuardFrames();
			}
		} else if (shouldHoldNormalPresentation(normalChatVisible, false)) {
			guardStarted = !normalPresentationHeld;
			mutations += holdNormalPresentation(client);
		}
		return new SurfaceObservation(previous, surface, guardStarted, guardReleased, normalChatReady, surface == DialoguePrompts.Surface.NONE && isPresent(), mutations, guardedFrames);
	}
}
