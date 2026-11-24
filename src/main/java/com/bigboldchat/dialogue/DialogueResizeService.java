package com.bigboldchat.dialogue;

import com.bigboldchat.chatbox.ChatboxLayout;
import com.bigboldchat.chatbox.ChatboxPlacement;
import com.bigboldchat.dialogue.DialogueFitSolver.DialogueFitCandidate;
import com.bigboldchat.dialogue.DialogueFitSolver.DialogueFitSelection;
import com.bigboldchat.layout.ChatboxBounds;
import com.bigboldchat.layout.InterfaceBounds;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Dialogue-only fit calculation, candidate selection, collision/anchor policy.
 *
 * Shared widget mutations, normal-chat center ownership and native placement writes stay in ChatboxCoreService and ChatboxPlacement.
 * Dialogue session handoffs stay in DialogueCoreService; this service adds no writer.
 * DialogueFitSolver remains the pure shape scorer; this service applies placement and collision decision policy around it.
 */
public final class DialogueResizeService {
	private static final String STATIC_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS1";
	private static final String MOVABLE_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS2";

	private final Client client;
	private final ChatboxPlacement chatboxPlacement;
	private final DialogueFitSolver solver;

	public DialogueResizeService(Client client, ChatboxPlacement chatboxPlacement, DialoguePrompts prompts) {
		this.client = client;
		this.chatboxPlacement = chatboxPlacement;
		this.solver = new DialogueFitSolver(prompts);
	}

	/**
	 * ================================================================
	 * COLLISION OVERLAY TRACKING
	 * ================================================================
	 */
	public static boolean isCollisionOverlay(Overlay overlay) {
		/*
		 * Identify only the overlays that can change dialogue collision space.
		 * Do not include ordinary UI overlays: unrelated overlays must not trigger a new dialogue fit search.
		 */
		if (overlay == null) {
			return false;
		}
		final String name = overlay.getName();
		return name != null && (name.contains("MINIMAP") || name.contains("INVENTORY_PARENT") || STATIC_TABS_OVERLAY.equals(name) || MOVABLE_TABS_OVERLAY.equals(name));
	}

	// Fingerprint live bounds; saved preferred positions are signals, not physical anchors.
	public long collisionOverlayFingerprint(OverlayManager overlayManager) {
		if (overlayManager == null) {
			return 0L;
		}
		final long[] fingerprint = {17L};
		overlayManager.anyMatch(overlay -> {
			if (!isCollisionOverlay(overlay)) {
				return false;
			}
			fingerprint[0] = fingerprint[0] * 31L + overlay.getName().hashCode();
			final Rectangle bounds = overlay.getBounds();
			if (bounds != null) {
				fingerprint[0] = fingerprint[0] * 31L + bounds.x;
				fingerprint[0] = fingerprint[0] * 31L + bounds.y;
				fingerprint[0] = fingerprint[0] * 31L + bounds.width;
				fingerprint[0] = fingerprint[0] * 31L + bounds.height;
			}
			final Point preferredLocation = overlay.getPreferredLocation();
			if (preferredLocation != null) {
				fingerprint[0] = fingerprint[0] * 31L + preferredLocation.x;
				fingerprint[0] = fingerprint[0] * 31L + preferredLocation.y;
			}
			final OverlayPosition preferredPosition = overlay.getPreferredPosition();
			fingerprint[0] = fingerprint[0] * 31L + (preferredPosition != null ? preferredPosition.ordinal() + 1 : 0);
			return false;
		});
		return fingerprint[0];
	}

	/*
	 * Read the live minimap/inventory overlay rectangles for collision solving.
	 * Native obstacle widgets can lag these bounds during an ongoing drag.
	 */
	public InterfaceBounds.Overrides liveCollisionOverrides(OverlayManager overlayManager, ChatboxLayout layout, InterfaceBounds.Overrides base) {
		if (overlayManager == null) {
			return base;
		}
		final Rectangle[] minimapBounds = {null};
		final Rectangle[] inventoryBounds = {null};
		overlayManager.anyMatch(overlay -> {
			if (overlay == null || overlay.getName() == null) {
				return false;
			}
			final String name = overlay.getName();
			if ("RESIZABLE_MINIMAP_WIDGET".equals(name)) {
				minimapBounds[0] = liveOverlayBounds(overlay);
			} else if (name.contains("INVENTORY_PARENT")) {
				inventoryBounds[0] = liveOverlayBounds(overlay);
			}
			return false;
		});
		final Rectangle classicMap = layout == ChatboxLayout.RESIZABLE_CLASSIC ? minimapBounds[0] : null;
		final Rectangle modernMap = layout == ChatboxLayout.RESIZABLE_MODERN ? minimapBounds[0] : null;
		final Rectangle modernInventory = layout == ChatboxLayout.RESIZABLE_MODERN ? inventoryBounds[0] : null;
		return InterfaceBounds.Overrides.withDialogueCollisionBounds(base, classicMap, modernMap, modernInventory);
	}

	private static Rectangle liveOverlayBounds(Overlay overlay) {
		final Rectangle bounds = overlay != null ? overlay.getBounds() : null;
		return bounds != null && bounds.width > 0 && bounds.height > 0 ? new Rectangle(bounds) : null;
	}

	/**
	 * ================================================================
	 * FIT CANDIDATES & TOPOLOGY
	 * ================================================================
	 */
	@FunctionalInterface
	public interface TopologyRejectHandler {
		void rejected(DialogueFitCandidate candidate, ChatboxBounds.Result baseline, String reason);
	}

	public DialogueFitCandidate resolveCandidate(Widget slot, ChatboxPlacement.State fitPlacement, int requestedWidth, int requestedHeight, int configuredWidth, int configuredHeight, ChatboxBounds.Tracker tracker, InterfaceBounds.Overrides overrides, ChatboxBounds.Result baseline, boolean constrainedWrap, boolean chatboxButtonsHidden, TopologyRejectHandler rejectHandler) {
		final ChatboxPlacement.State placement = chatboxPlacement.resizeForFit(slot, fitPlacement, requestedWidth, requestedHeight);
		final ChatboxBounds.Tracker candidateTracker = tracker != null ? tracker.copy() : new ChatboxBounds.Tracker();
		final ChatboxBounds.Result result = ChatboxBounds.resolve(client, placement, chatboxButtonsHidden, candidateTracker, overrides);
		final boolean expanded = requestedWidth > configuredWidth || requestedHeight > configuredHeight;
		final String reason = topologyReason(baseline, result);
		final DialogueFitCandidate candidate = new DialogueFitCandidate(requestedWidth, requestedHeight, result, placement.allowsDirectHostMove(), expanded, constrainedWrap, reason == null);
		if (candidate.topologyValid && tracker != null) {
			tracker.replaceWith(candidateTracker);
		} else if (!candidate.topologyValid && rejectHandler != null) {
			rejectHandler.rejected(candidate, baseline, reason);
		}
		return candidate;
	}

	public DialogueFitSelection selectFitCandidate(Widget slot, ChatboxPlacement.State placement, int configuredWidth, int configuredHeight, int maximumWidth, int maximumHeight, ChatboxBounds.Tracker tracker, InterfaceBounds.Overrides overrides, ChatboxBounds.Result baseline, Map<Integer, DialoguePrompts.FitRequirement> strictRequirements, Map<Integer, DialoguePrompts.FitRequirement> wrappedRequirements, boolean retainActiveFit, boolean buttonsHidden, boolean activeFit, int retainedWidth, int retainedHeight, boolean postDropShapeSearch, Predicate<DialogueFitCandidate> candidateClear, TopologyRejectHandler rejectHandler) {
		return solver.selectDialogueFitCandidate(configuredWidth, configuredHeight, maximumWidth, maximumHeight, tracker, strictRequirements, wrappedRequirements, retainActiveFit, buttonsHidden, activeFit, retainedWidth, retainedHeight, postDropShapeSearch, (width, height, candidateTracker, wrap) -> resolveCandidate(slot, placement, width, height, configuredWidth, configuredHeight, candidateTracker, overrides, baseline, wrap, buttonsHidden, rejectHandler), candidateClear);
	}

	public DialoguePrompts.FitRequirement fitRequirement(Map<Integer, DialoguePrompts.FitRequirement> requirements, int width, boolean constrainedWrap) {
		return solver.fitRequirement(requirements, width, constrainedWrap);
	}

	public boolean isBetterFitCandidate(DialogueFitCandidate candidate, DialogueFitCandidate best, int configuredWidth, int configuredHeight, boolean postDropShapeSearch) {
		return solver.isBetterDialogueFitCandidate(candidate, best, configuredWidth, configuredHeight, postDropShapeSearch);
	}

	public int[] measureUnobstructedFit(int configuredWidth, int configuredHeight, int maximumWidth, int maximumHeight, boolean constrainedWrap, boolean chatboxButtonsHidden) {
		return solver.measureUnobstructedFit(configuredWidth, configuredHeight, maximumWidth, maximumHeight, constrainedWrap, chatboxButtonsHidden);
	}

	// A candidate cannot discard part of the already-visible valid baseline.
	static String topologyReason(ChatboxBounds.Result baseline, ChatboxBounds.Result candidate) {
		if (baseline == null || candidate == null) {
			return null;
		}
		return topologyReason(baseline.getDesiredBounds(), baseline.getEffectiveBounds(), candidate.getDesiredBounds(), candidate.getEffectiveBounds());
	}

	static String topologyReason(Rectangle baselineDesired, Rectangle baselineEffective, Rectangle candidateDesired, Rectangle candidateEffective) {
		if (baselineDesired == null || baselineEffective == null || candidateDesired == null || candidateEffective == null) {
			return null;
		}
		if (!candidateDesired.contains(baselineDesired)) {
			return "DESIRED_BASELINE_LOST";
		}
		return candidateEffective.contains(baselineEffective) ? null : "EFFECTIVE_BASELINE_LOST";
	}

	/**
	 * ================================================================
	 * COLLISION AXES & CLEARANCE
	 * ================================================================
	 */
	public static int activeOrthogonalEdges(Rectangle original, Rectangle visible, List<Rectangle> obstacles) {
		// Resolve the currently clipped edge without writing native placement.
		int edges = 0;
		final int visibleRight = visible.x + visible.width;
		final int visibleBottom = visible.y + visible.height;
		for (Rectangle obstacle : obstacles) {
			if (obstacle == null || obstacle.width <= 0 || obstacle.height <= 0 || !original.intersects(obstacle)) {
				continue;
			}
			final int obstacleRight = obstacle.x + obstacle.width;
			final int obstacleBottom = obstacle.y + obstacle.height;
			final boolean crossesHeight = obstacle.y < visibleBottom && obstacleBottom > visible.y;
			final boolean crossesWidth = obstacle.x < visibleRight && obstacleRight > visible.x;
			if (crossesHeight) {
				if (visible.x > original.x && obstacle.x < visible.x && obstacleRight >= visible.x) {
					edges |= 1;
				}
				if (visibleRight < original.x + original.width && obstacle.x <= visibleRight && obstacleRight > visibleRight) {
					edges |= 2;
				}
			}
			if (crossesWidth) {
				if (visible.y > original.y && obstacle.y < visible.y && obstacleBottom >= visible.y) {
					edges |= 4;
				}
				if (visibleBottom < original.y + original.height && obstacle.y <= visibleBottom && obstacleBottom > visibleBottom) {
					edges |= 8;
				}
			}
		}
		return edges;
	}

	// Dialogue-only orthogonal collision computation, no native geometry writes.
	public int orthogonalFreeSpace(Rectangle visible, List<Rectangle> obstacles, int direction) {
		final int maximum;
		switch (direction) {
			case 0: maximum = visible.y;
				break;
			case 1: maximum = client.getCanvasHeight() - visible.y - visible.height;
				break;
			case 2: maximum = visible.x;
				break;
			default: maximum = client.getCanvasWidth() - visible.x - visible.width;
				break;
		}
		int space = Math.max(0, maximum);
		for (Rectangle obstacle : obstacles) {
			if (obstacle == null || obstacle.width <= 0 || obstacle.height <= 0) {
				continue;
			}
			if (direction < 2) {
				if (obstacle.x >= visible.x + visible.width || obstacle.x + obstacle.width <= visible.x) {
					continue;
				}
				if (direction == 0 && obstacle.y + obstacle.height <= visible.y) {
					space = Math.min(space, visible.y - obstacle.y - obstacle.height);
				} else if (direction == 1 && obstacle.y >= visible.y + visible.height) {
					space = Math.min(space, obstacle.y - visible.y - visible.height);
				}
			} else {
				if (obstacle.y >= visible.y + visible.height || obstacle.y + obstacle.height <= visible.y) {
					continue;
				}
				if (direction == 2 && obstacle.x + obstacle.width <= visible.x) {
					space = Math.min(space, visible.x - obstacle.x - obstacle.width);
				} else if (direction == 3 && obstacle.x >= visible.x + visible.width) {
					space = Math.min(space, obstacle.x - visible.x - visible.width);
				}
			}
		}
		return Math.max(0, space);
	}

	// Dialogue-only orthogonal collision computation, no native geometry writes.
	public static boolean introducesOrthogonalOverlap(Rectangle proposed, Rectangle visible, List<Rectangle> obstacles) {
		if (proposed == null || visible == null) {
			return true;
		}
		for (Rectangle obstacle : obstacles) {
			if (obstacle == null || obstacle.width <= 0 || obstacle.height <= 0) {
				continue;
			}
			final Rectangle proposedOverlap = proposed.intersection(obstacle);
			if (proposedOverlap.isEmpty()) {
				continue;
			}
			final Rectangle existingOverlap = visible.intersection(obstacle);
			if (existingOverlap.isEmpty() || !existingOverlap.contains(proposedOverlap)) {
				return true;
			}
		}
		return false;
	}

	public static boolean clearDialogueObstacles(Rectangle bounds, List<Rectangle> obstacles) {
		if (bounds == null || obstacles == null) {
			return true;
		}
		for (Rectangle obstacle : obstacles) {
			if (obstacle != null && bounds.intersects(obstacle)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * ================================================================
	 * FIT SHAPE & CORNER POLICY
	 * ================================================================
	 */
	private static final int FIT_STEP = 4;
	private static final int FIT_AXIS_TOLERANCE_PERCENT = 10;

	// Immutable snapshot of the confirmed horizontal placement constraints.
	public static final class HorizontalFitState {
		private final Rectangle drop;
		private final boolean postDropShapeSearch;
		private final boolean userDropPinned;
		private final boolean mouseHeld;
		private final boolean collisionLiveReflow;
		private final boolean horizontalLocked;
		private final boolean userRightEdgePinned;
		private final Rectangle desired;
		private final Rectangle visible;
		private final int canvasWidth;

		public HorizontalFitState(Rectangle drop, boolean postDropShapeSearch, boolean userDropPinned, boolean mouseHeld, boolean collisionLiveReflow, boolean horizontalLocked, boolean userRightEdgePinned, Rectangle desired, Rectangle visible, int canvasWidth) {
			this.drop = drop != null ? new Rectangle(drop) : null;
			this.postDropShapeSearch = postDropShapeSearch;
			this.userDropPinned = userDropPinned;
			this.mouseHeld = mouseHeld;
			this.collisionLiveReflow = collisionLiveReflow;
			this.horizontalLocked = horizontalLocked;
			this.userRightEdgePinned = userRightEdgePinned;
			this.desired = desired != null ? new Rectangle(desired) : null;
			this.visible = visible != null ? new Rectangle(visible) : null;
			this.canvasWidth = canvasWidth;
		}
	}

	public boolean preferPostDropHorizontalContinuity(DialogueFitSelection primary, DialogueFitSelection opposite, HorizontalFitState state) {
		if (!state.postDropShapeSearch || !state.userDropPinned || state.mouseHeld || state.collisionLiveReflow || state.horizontalLocked || state.drop == null) {
			return false;
		}
		final Rectangle drop = state.drop;
		if (state.userRightEdgePinned && touchesDialogueRightCanvasEdge(drop, state.canvasWidth)) {
			return false;
		}
		if (state.desired != null && state.visible != null && (state.desired.x != state.visible.x || state.desired.x + state.desired.width != state.visible.x + state.visible.width)) {
			return false;
		}
		final DialogueFitCandidate current = primary.candidate;
		final DialogueFitCandidate alternate = opposite.candidate;
		if (current == null || alternate == null || !current.topologyValid || !alternate.topologyValid) {
			return false;
		}
		final Rectangle currentDesired = current.result.getDesiredBounds();
		final Rectangle alternateDesired = alternate.result.getDesiredBounds();
		final Rectangle currentVisible = current.result.getEffectiveBounds();
		final Rectangle alternateVisible = alternate.result.getEffectiveBounds();
		if (currentDesired.x != currentVisible.x || currentDesired.x + currentDesired.width != currentVisible.x + currentVisible.width || alternateDesired.x != alternateVisible.x || alternateDesired.x + alternateDesired.width != alternateVisible.x + alternateVisible.width) {
			return false;
		}
		final long currentArea = (long) currentVisible.width * currentVisible.height;
		final long alternateArea = (long) alternateVisible.width * alternateVisible.height;
		if (alternateArea * 100L > currentArea * (100L + FIT_AXIS_TOLERANCE_PERCENT) || alternateArea * 100L < currentArea * (100L - FIT_AXIS_TOLERANCE_PERCENT)) {
			return false;
		}
		final int currentDisplacement = Math.abs(currentDesired.x - drop.x);
		final int alternateDisplacement = Math.abs(alternateDesired.x - drop.x);
		return currentDisplacement - alternateDisplacement > FIT_STEP;
	}

	public String oppositeDialogueCornerGuard(DialogueFitSelection primary, DialogueFitSelection opposite, int configuredWidth, HorizontalFitState state) {
		if (state.collisionLiveReflow || state.mouseHeld) {
			return "LIVE_DRAG";
		}
		if (state.horizontalLocked) {
			return "ANCHOR_LOCKED";
		}
		final DialogueFitCandidate alternate = opposite.candidate;
		if (alternate == null) {
			return "NO_COMPLETE_OPPOSITE";
		}
		final Rectangle reference = state.drop;
		final Rectangle primaryBounds = primary.candidate != null
				? primary.candidate.result.getDesiredBounds()
				: primary.fallback != null
						? primary.fallback.result.getDesiredBounds()
						: reference;
		if (primaryBounds == null) {
			return "NO_ANCHOR_REFERENCE";
		}
		final int anchorWidth = reference != null ? reference.width : configuredWidth;
		final int displacementLimit = DialogueFitSolver.oppositeCornerDisplacementLimit(configuredWidth, anchorWidth);
		final int displacement = Math.abs(alternate.result.getDesiredBounds().x - primaryBounds.x);
		return displacement > displacementLimit ? "DISTANT_OPPOSITE_" + displacement + "px_LIMIT_" + displacementLimit + "px" : null;
	}

	public boolean preferOppositeDialogueCorner(DialogueFitSelection primary, DialogueFitSelection opposite, int configuredWidth, int configuredHeight, boolean postDropShapeSearch) {
		final DialogueFitCandidate current = primary.candidate;
		final DialogueFitCandidate alternate = opposite.candidate;
		if (current == null) {
			return alternate != null;
		}
		if (alternate == null) {
			return false;
		}
		final boolean currentUnclipped = current.result.getDesiredBounds().equals(current.result.getEffectiveBounds());
		final boolean alternateUnclipped = alternate.result.getDesiredBounds().equals(alternate.result.getEffectiveBounds());
		if (!currentUnclipped && alternateUnclipped) {
			final Rectangle desired = current.result.getDesiredBounds();
			final Rectangle effective = current.result.getEffectiveBounds();
			final int displacement = Math.max(desired.width - effective.width, desired.height - effective.height);
			return displacement > FIT_STEP;
		}
		if (currentUnclipped && !alternateUnclipped) {
			return false;
		}
		final long meaningfulArea = (long) FIT_STEP * 2 * Math.max(configuredWidth, configuredHeight);
		return alternate.growthArea + meaningfulArea < current.growthArea && isBetterFitCandidate(alternate, current, configuredWidth, configuredHeight, postDropShapeSearch);
	}

	public boolean candidateClear(DialogueFitCandidate candidate, InterfaceBounds.Overrides overrides) {
		final ChatboxBounds.Result result = candidate.result;
		if (!result.isLeftCollision() && !result.isRightCollision() && !result.isTopCollision() && !result.isBottomCollision()) {
			return true;
		}
		final Rectangle visible = result.getEffectiveBounds();
		return withinDialogueCanvas(visible, client.getCanvasWidth(), client.getCanvasHeight()) && clearDialogueObstacles(visible, InterfaceBounds.dialogueCollisionObstacles(client, overrides));
	}

	public static boolean withinDialogueCanvas(Rectangle bounds, int width, int height) {
		return bounds != null && bounds.width > 0 && bounds.height > 0 && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= width && bounds.y + bounds.height <= height;
	}

	public static boolean touchesDialogueRightCanvasEdge(Rectangle bounds, int canvasWidth) {
		if (bounds == null || canvasWidth <= 0 || bounds.x <= FIT_STEP) {
			return false;
		}
		final int gap = canvasWidth - (bounds.x + bounds.width);
		return gap >= 0 && gap <= FIT_STEP;
	}

	public static DialogueFitCandidate clippedFallback(DialogueFitCandidate candidate) {
		if (candidate == null || !candidate.topologyValid) {
			return null;
		}
		candidate.clippedFallback = true;
		return candidate;
	}

	public boolean pinnedOrthogonalGrowthPreservesAnchor(Rectangle desired, Rectangle visible, DialogueFitCandidate candidate, InterfaceBounds.Overrides overrides) {
		final Rectangle fitted = candidate.result.getEffectiveBounds();
		final int edges = activeOrthogonalEdges(desired, visible, InterfaceBounds.dialogueCollisionObstacles(client, overrides));
		final boolean widthContainsPrevious = fitted.x <= visible.x && fitted.x + fitted.width >= visible.x + visible.width;
		final boolean heightContainsPrevious = fitted.y <= visible.y && fitted.y + fitted.height >= visible.y + visible.height;
		if (edges == 8) {
			return fitted.y == visible.y && fitted.height == visible.height && widthContainsPrevious;
		}
		if (edges == 4) {
			return fitted.y + fitted.height == visible.y + visible.height && fitted.height == visible.height && widthContainsPrevious;
		}
		if (edges == 1) {
			return fitted.x + fitted.width == visible.x + visible.width && fitted.width == visible.width && heightContainsPrevious;
		}
		return edges == 2 && fitted.x == visible.x && fitted.width == visible.width && heightContainsPrevious;
	}

	/**
	 * ================================================================
	 * PINNED DIALOGUE ORIGIN
	 * ================================================================
	 */
	public enum FitPath {
		STANDARD_FIT,
		ORTHOGONAL_RESTORE,
		ORTHOGONAL_GROW,
		RELEASED_GROW
	}

	// All origin-pin facts belong to one fit decision, never stored globally.
	public static final class PinnedFitState {
		private final boolean userDropPinned;
		private final Rectangle drop;
		private final Rectangle previous;
		private final Rectangle visible;
		private final Point sessionCenter;
		private final boolean pageChanged;
		private final boolean postDropSearchPending;
		private final FitPath reason;
		private final Rectangle orthogonalBaseline;
		private final boolean orthogonalBaselineCurrent;
		private final int canvasWidth;
		private final int canvasHeight;
		private final InterfaceBounds.Overrides overrides;

		public PinnedFitState(boolean userDropPinned, Rectangle drop, Rectangle previous, Rectangle visible, Point sessionCenter, boolean pageChanged, boolean postDropSearchPending, FitPath reason, Rectangle orthogonalBaseline, boolean orthogonalBaselineCurrent, int canvasWidth, int canvasHeight, InterfaceBounds.Overrides overrides) {
			this.userDropPinned = userDropPinned;
			this.drop = drop != null ? new Rectangle(drop) : null;
			this.previous = previous != null ? new Rectangle(previous) : null;
			this.visible = visible != null ? new Rectangle(visible) : null;
			this.sessionCenter = sessionCenter != null ? new Point(sessionCenter) : null;
			this.pageChanged = pageChanged;
			this.postDropSearchPending = postDropSearchPending;
			this.reason = reason;
			this.orthogonalBaseline = orthogonalBaseline != null ? new Rectangle(orthogonalBaseline) : null;
			this.orthogonalBaselineCurrent = orthogonalBaselineCurrent;
			this.canvasWidth = canvasWidth;
			this.canvasHeight = canvasHeight;
			this.overrides = overrides;
		}
	}

	// A confirmed physical drop wins over automatic fit relocation.
	public boolean pinnedDialogueOriginPreserved(PinnedFitState state, DialogueFitCandidate candidate) {
		if (!state.userDropPinned || state.drop == null || state.previous == null || state.visible == null || candidate == null) {
			return true;
		}
		final Rectangle previous = state.previous;
		final Rectangle proposed = candidate.result.getDesiredBounds();
		if (state.sessionCenter != null && (state.pageChanged || state.postDropSearchPending) && proposed.equals(chatboxPlacement.centeredDialogueBounds(state.sessionCenter, candidate.requestedWidth, candidate.requestedHeight))) {
			return true;
		}
		if (state.reason == FitPath.ORTHOGONAL_RESTORE && state.orthogonalBaseline != null && state.orthogonalBaselineCurrent && proposed.equals(state.orthogonalBaseline) && proposed.equals(candidate.result.getEffectiveBounds())) {
			return true;
		}
		if ((state.reason == FitPath.ORTHOGONAL_GROW || state.reason == FitPath.ORTHOGONAL_RESTORE) && pinnedOrthogonalGrowthPreservesAnchor(state.previous, state.visible, candidate, state.overrides)) {
			return true;
		}
		final Rectangle drop = state.drop;
		final boolean rightAttached = touchesDialogueRightCanvasEdge(drop, state.canvasWidth);
		final int bottomGap = state.canvasHeight - (drop.y + drop.height);
		final boolean bottomAttached = drop.y > FIT_STEP && bottomGap >= 0 && bottomGap <= FIT_STEP;
		final boolean xStable = proposed.x == previous.x || proposed.x == drop.x && Math.abs(state.visible.x - drop.x) <= 2 || rightAttached && proposed.x + proposed.width == drop.x + drop.width;
		final boolean yStable = proposed.y == previous.y || proposed.y == drop.y && Math.abs(state.visible.y - drop.y) <= 2 || bottomAttached && proposed.y + proposed.height == drop.y + drop.height;
		return xStable && yStable;
	}
}
