package com.bigboldchat.dialogue;

import com.bigboldchat.chatbox.ChatboxGeometry;
import com.bigboldchat.layout.ChatboxBounds;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Pure dialogue sizing, shape-selection, and read-only collision-fit policies.
 *
 * This class cannot move RuneLite widgets or modify the user's placement.
 * Collision-fit methods inspect host proposals; the controller owns approval
 * and shared chatbox services remain responsible for geometry commits.
 */
public final class DialogueFitSolver {
	private static final int DIALOGUE_FIT_STEP = 4;
	private static final int DIALOGUE_FIT_CORRECTIONS = 3;
	private static final int POST_DROP_BALANCE_AREA_PERCENT = 15;
	private static final int DIALOGUE_FIT_AXIS_TOLERANCE_PERCENT = 10;
	private static final int RETAINED_FIT_AREA_TOLERANCE_PERCENT = 8;
	private static final int RETAINED_FIT_DISTANCE_MARGIN = DIALOGUE_FIT_STEP * 2;
	private static final int FALLBACK_HEIGHT_DEFICIT_TOLERANCE = DIALOGUE_FIT_STEP * 4;
	private static final int BALANCED_FIT_AREA_TOLERANCE_PERCENT = 45;
	private static final int MIN_SHAPE_IMBALANCE_IMPROVEMENT = DIALOGUE_FIT_STEP * 8;

	private final DialoguePrompts dialoguePrompts;

	public DialogueFitSolver(DialoguePrompts dialoguePrompts) {
		this.dialoguePrompts = dialoguePrompts;
	}

	/**
	 * ================================================================
	 * STANDARD FIT SEARCH
	 * ================================================================
	 */
	public static int alignGrowth(int value, int configuredValue) {
		final int growth = Math.max(0, value - configuredValue);
		final int alignedGrowth = (growth + DIALOGUE_FIT_STEP - 1) / DIALOGUE_FIT_STEP * DIALOGUE_FIT_STEP;
		return configuredValue + alignedGrowth;
	}

	/* An alternate full fit is useful only when it stays near the user's
	 * original horizontal anchor. Beyond this distance the preferred side
	 * should keep its current (possibly clipped) fallback. */
	public static int oppositeCornerDisplacementLimit(int configuredWidth, int referenceWidth) {
		return Math.max(DIALOGUE_FIT_STEP * 4, Math.min(96, Math.max(configuredWidth, referenceWidth) / 3));
	}

	public static long growthArea(int configuredWidth, int configuredHeight, int requestedWidth, int requestedHeight) {
		final long configuredArea = (long) configuredWidth * configuredHeight;
		final long requestedArea = (long) requestedWidth * requestedHeight;
		return Math.max(0L, requestedArea - configuredArea);
	}

	/*
	 * Compare added area first; within the near-equal area band, a non-square
	 * configured chatbox prefers growth along its longer axis. This is a soft
	 * preference, not a constraint, and does not change collision admissibility.
	 */
	public static int compareShapes(int configuredWidth, int configuredHeight, int candidateWidth, int candidateHeight, int bestWidth, int bestHeight) {
		final long candidateArea = growthArea(configuredWidth, configuredHeight, candidateWidth, candidateHeight);
		final long bestArea = growthArea(configuredWidth, configuredHeight, bestWidth, bestHeight);
		// Never prefer a rectangle strictly dominated on both dimensions.
		if (candidateWidth >= bestWidth && candidateHeight >= bestHeight && (candidateWidth > bestWidth || candidateHeight > bestHeight)) {
			return -1;
		}
		if (bestWidth >= candidateWidth && bestHeight >= candidateHeight && (bestWidth > candidateWidth || bestHeight > candidateHeight)) {
			return 1;
		}
		final long smallerArea = Math.min(candidateArea, bestArea);
		final long largerArea = Math.max(candidateArea, bestArea);
		/* A small base chatbox is a minimum, not a request for a 200px-wide
		 * dialogue reaching the top of the canvas. Permit a moderate area
		 * tradeoff when it gives a materially more balanced reading surface. */
		if (largerArea - smallerArea <= smallerArea * BALANCED_FIT_AREA_TOLERANCE_PERCENT / 100L) {
			final int candidateImbalance = Math.abs(candidateWidth - candidateHeight);
			final int bestImbalance = Math.abs(bestWidth - bestHeight);
			/* Where both are reasonably balanced, favor the wider reading
			 * layout over a slender column. This never rescues a clipped
			 * candidate or overrides obstacle clearance. */
			final boolean candidateReadableWide = candidateWidth >= candidateHeight && candidateWidth <= candidateHeight * 3 / 2;
			final boolean bestReadableWide = bestWidth >= bestHeight && bestWidth <= bestHeight * 3 / 2;
			if (candidateReadableWide != bestReadableWide) {
				return candidateReadableWide ? 1 : -1;
			}
			if (candidateImbalance + MIN_SHAPE_IMBALANCE_IMPROVEMENT < bestImbalance) {
				return 1;
			}
			if (bestImbalance + MIN_SHAPE_IMBALANCE_IMPROVEMENT < candidateImbalance) {
				return -1;
			}
		}
		if (configuredWidth != configuredHeight && largerArea - smallerArea <= smallerArea * DIALOGUE_FIT_AXIS_TOLERANCE_PERCENT / 100L) {
			final boolean widthPreferred = configuredWidth > configuredHeight;
			final int candidatePreferredGrowth = widthPreferred ? candidateWidth - configuredWidth : candidateHeight - configuredHeight;
			final int candidateOtherGrowth = widthPreferred ? candidateHeight - configuredHeight : candidateWidth - configuredWidth;
			final int bestPreferredGrowth = widthPreferred ? bestWidth - configuredWidth : bestHeight - configuredHeight;
			final int bestOtherGrowth = widthPreferred ? bestHeight - configuredHeight : bestWidth - configuredWidth;
			final int candidateBias = candidatePreferredGrowth - candidateOtherGrowth;
			final int bestBias = bestPreferredGrowth - bestOtherGrowth;
			if (candidateBias != bestBias) {
				return Integer.compare(candidateBias, bestBias);
			}
		}
		if (candidateArea != bestArea) {
			return Long.compare(bestArea, candidateArea);
		}
		final int candidateLinear = candidateWidth - configuredWidth + candidateHeight - configuredHeight;
		final int bestLinear = bestWidth - configuredWidth + bestHeight - configuredHeight;
		return Integer.compare(bestLinear, candidateLinear);
	}

	public int[] measureUnobstructedFit(int configuredWidth, int configuredHeight, int maximumWidth, int maximumHeight, boolean constrainedWrap, boolean chatboxButtonsHidden) {
		int bestWidth = -1;
		int bestHeight = -1;
		long bestArea = Long.MAX_VALUE;
		int bestLinear = Integer.MAX_VALUE;
		int bestPreferredDeficit = Integer.MAX_VALUE;
		final int tabHeight = chatboxButtonsHidden ? 0 : ChatboxGeometry.NATIVE_TAB_HEIGHT;
		for (int width = configuredWidth; width <= maximumWidth; width += DIALOGUE_FIT_STEP) {
			final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(width, constrainedWrap);
			if (!requirement.isActive()) {
				return new int[] {configuredWidth, configuredHeight};
			}
			if (width < requirement.getMinimumWidth()) {
				continue;
			}

			final int requiredHeight = Math.max(configuredHeight, alignGrowth(requirement.getMinimumBodyHeight() + tabHeight, configuredHeight));
			if (requiredHeight > maximumHeight) {
				continue;
			}
			final long area = growthArea(configuredWidth, configuredHeight, width, requiredHeight);
			if (bestWidth >= 0 && area > bestArea) {
				break;
			}
			final int linear = width - configuredWidth + requiredHeight - configuredHeight;
			final int preferredDeficit = Math.max(0, requirement.getPreferredBodyHeight() - ChatboxGeometry.bodyHeight(requiredHeight, chatboxButtonsHidden));
			if (area < bestArea || area == bestArea && (linear < bestLinear || linear == bestLinear && preferredDeficit < bestPreferredDeficit)) {
				bestWidth = width;
				bestHeight = requiredHeight;
				bestArea = area;
				bestLinear = linear;
				bestPreferredDeficit = preferredDeficit;
			}
		}
		return bestWidth >= 0 ? new int[] {bestWidth, bestHeight} : null;
	}

	// Candidate creation and host writes remain outside this solver.
	@FunctionalInterface
	public interface CandidateResolver {
		DialogueFitCandidate resolve(int requestedWidth, int requestedHeight, ChatboxBounds.Tracker tracker, boolean constrainedWrap);
	}

	public DialogueFitSelection selectDialogueFitCandidate(int configuredWidth, int configuredHeight, int maximumWidth, int maximumHeight, ChatboxBounds.Tracker tracker, Map<Integer, DialoguePrompts.FitRequirement> strictRequirements, Map<Integer, DialoguePrompts.FitRequirement> wrappedRequirements, boolean retainActiveFit, boolean chatboxButtonsHidden, boolean activeFit, int retainedWidth, int retainedHeight, boolean postDropShapeSearch, CandidateResolver resolver, Predicate<DialogueFitCandidate> candidateClear) {
		final DialogueFitSearch strict = searchDialogueFitCandidate(configuredWidth, configuredHeight, maximumWidth, maximumHeight, tracker, strictRequirements, retainActiveFit, false, Long.MAX_VALUE, chatboxButtonsHidden, activeFit, retainedWidth, retainedHeight, postDropShapeSearch, resolver, candidateClear);
		DialogueFitSearch wrapped = null;

		/*
		 * A zero-growth STRICT fit cannot be improved. Otherwise compare a
		 * wrapped full fit even if STRICT succeeded; keep the original eligibility
		 * restriction when STRICT has no full fit to preserve fallback behavior.
		 */
		if (strict.candidate != null && strict.candidate.growthArea > 0 || canUseConstrainedDialogueWrap(strict)) {
			final long areaLimit = strict.candidate != null ? dialogueFitGrowthCeiling(strict.candidate, configuredWidth, configuredHeight, postDropShapeSearch) : Long.MAX_VALUE;
			wrapped = searchDialogueFitCandidate(configuredWidth, configuredHeight, maximumWidth, maximumHeight, tracker, wrappedRequirements, retainActiveFit, true, areaLimit, chatboxButtonsHidden, activeFit, retainedWidth, retainedHeight, postDropShapeSearch, resolver, candidateClear);
		}

		DialogueFitCandidate selected = strict.candidate;
		if (wrapped != null && wrapped.candidate != null && (selected == null || isBetterDialogueFitCandidate(wrapped.candidate, selected, configuredWidth, configuredHeight, postDropShapeSearch))) {
			selected = wrapped.candidate;
		}

		final DialogueFitCandidate wrappedFallback = wrapped != null ? wrapped.fallback : null;
		final DialogueFitCandidate fallback = isBetterDialogueFitFallback(wrappedFallback, strict.fallback) ? wrappedFallback : strict.fallback;
		return new DialogueFitSelection(strict, wrapped, selected, fallback);
	}

	private DialogueFitSearch searchDialogueFitCandidate(int configuredWidth, int configuredHeight, int maximumWidth, int maximumHeight, ChatboxBounds.Tracker fitTracker, Map<Integer, DialoguePrompts.FitRequirement> requirements, boolean retainActiveFit, boolean constrainedWrap, long maximumGrowthArea, boolean chatboxButtonsHidden, boolean activeFit, int retainedWidth, int retainedHeight, boolean postDropShapeSearch, CandidateResolver resolver, Predicate<DialogueFitCandidate> candidateClear) {
		DialogueFitCandidate best = null;
		DialogueFitCandidate fallback = null;
		int requestedWidth = configuredWidth;
		int widestEffectiveWidth = 0;
		int previousRequestedWidth = -1;
		int previousEffectiveWidth = -1;
		boolean horizontalBlocked = false;
		final ChatboxBounds.Tracker widthTracker = fitTracker != null ? fitTracker.copy() : new ChatboxBounds.Tracker();

		while (requestedWidth <= maximumWidth) {
			if (DialogueFitSolver.growthArea(configuredWidth, configuredHeight, requestedWidth, configuredHeight) > maximumGrowthArea) {
				break;
			}
			final int widthGrowth = requestedWidth - configuredWidth;
			if (best != null && DialogueFitSolver.growthArea(configuredWidth, configuredHeight, requestedWidth, configuredHeight) > dialogueFitGrowthCeiling(best, configuredWidth, configuredHeight, postDropShapeSearch)) {
				break;
			}

			int nextWidth = Math.min(maximumWidth, requestedWidth + DIALOGUE_FIT_STEP);
			DialogueFitCandidate candidate = resolver.resolve(requestedWidth, configuredHeight, widthTracker, constrainedWrap);
			if (candidate == null) {
				break;
			}
			if (!candidate.topologyValid) {
				horizontalBlocked = true;
				break;
			}

			Rectangle effective = candidate.result.getEffectiveBounds();
			if (previousRequestedWidth >= 0 && requestedWidth > previousRequestedWidth && effective.width <= previousEffectiveWidth) {
				horizontalBlocked = true;
				break;
			}

			previousRequestedWidth = requestedWidth;
			previousEffectiveWidth = effective.width;
			widestEffectiveWidth = Math.max(widestEffectiveWidth, effective.width);

			int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
			DialoguePrompts.FitRequirement requirement = fitRequirement(requirements, effective.width, constrainedWrap);
			if (!requirement.isActive()) {
				return new DialogueFitSearch(candidate, null, false);
			}

			fallback = betterDialogueFitFallback(candidate, fallback, requirement, bodyHeight, configuredWidth, configuredHeight, retainActiveFit, activeFit, retainedWidth, retainedHeight);
			if (effective.width < requirement.getMinimumWidth()) {
				final int widthDeficit = requirement.getMinimumWidth() - effective.width;
				final int alignedWidth = DialogueFitSolver.alignGrowth(requestedWidth + widthDeficit, configuredWidth);
				nextWidth = Math.max(nextWidth, Math.min(maximumWidth, alignedWidth));
			} else {
				if (requirement.fitsMinimum(effective.width, bodyHeight) && candidateClear.test(candidate)) {
					candidate.growthArea = DialogueFitSolver.growthArea(configuredWidth, configuredHeight, requestedWidth, configuredHeight);
					candidate.linearGrowth = widthGrowth;
					candidate.preferredDeficit = Math.max(0, requirement.getPreferredBodyHeight() - bodyHeight);
					candidate.retainedDistance = retainedDialogueFitDistance(candidate, retainActiveFit, activeFit, retainedWidth, retainedHeight);
					if (candidate.growthArea <= maximumGrowthArea && isBetterDialogueFitCandidate(candidate, best, configuredWidth, configuredHeight, postDropShapeSearch)) {
						best = candidate;
					}
				} else {
					final ChatboxBounds.Tracker heightTracker = widthTracker.copy();
					int requestedHeight = configuredHeight;
					int previousEffectiveHeight = effective.height;
					int corrections = 0;
					int bodyDeficit = requirement.getMinimumBodyHeight() - bodyHeight;

					while (bodyDeficit > 0 && requestedHeight < maximumHeight && corrections < DIALOGUE_FIT_CORRECTIONS) {
						final int targetHeight = Math.min(maximumHeight, requestedHeight + bodyDeficit);
						final int nextHeight = Math.min(maximumHeight, DialogueFitSolver.alignGrowth(targetHeight, configuredHeight));
						if (nextHeight <= requestedHeight) {
							break;
						}
						if (best != null && DialogueFitSolver.growthArea(configuredWidth, configuredHeight, requestedWidth, nextHeight) > dialogueFitGrowthCeiling(best, configuredWidth, configuredHeight, postDropShapeSearch)) {
							break;
						}

						requestedHeight = nextHeight;
						corrections++;
						candidate = resolver.resolve(requestedWidth, requestedHeight, heightTracker, constrainedWrap);
						if (candidate == null || !candidate.topologyValid) {
							break;
						}

						effective = candidate.result.getEffectiveBounds();
						widestEffectiveWidth = Math.max(widestEffectiveWidth, effective.width);
						bodyHeight = ChatboxGeometry.bodyHeight(effective.height, chatboxButtonsHidden);
						requirement = fitRequirement(requirements, effective.width, constrainedWrap);
						if (!requirement.isActive()) {
							return new DialogueFitSearch(candidate, null, false);
						}
						fallback = betterDialogueFitFallback(candidate, fallback, requirement, bodyHeight, configuredWidth, configuredHeight, retainActiveFit, activeFit, retainedWidth, retainedHeight);
						if (effective.width < requirement.getMinimumWidth()) {
							final int widthDeficit = requirement.getMinimumWidth() - effective.width;
							final int alignedWidth = DialogueFitSolver.alignGrowth(requestedWidth + widthDeficit, configuredWidth);
							nextWidth = Math.max(nextWidth, Math.min(maximumWidth, alignedWidth));
							break;
						}
						if (requirement.fitsMinimum(effective.width, bodyHeight) && candidateClear.test(candidate)) {
							candidate.growthArea = DialogueFitSolver.growthArea(configuredWidth, configuredHeight, requestedWidth, requestedHeight);
							candidate.linearGrowth = widthGrowth + requestedHeight - configuredHeight;
							candidate.preferredDeficit = Math.max(0, requirement.getPreferredBodyHeight() - bodyHeight);
							candidate.retainedDistance = retainedDialogueFitDistance(candidate, retainActiveFit, activeFit, retainedWidth, retainedHeight);
							if (candidate.growthArea <= maximumGrowthArea && isBetterDialogueFitCandidate(candidate, best, configuredWidth, configuredHeight, postDropShapeSearch)) {
								best = candidate;
							}
							break;
						}
						if (effective.height <= previousEffectiveHeight) {
							break;
						}

						previousEffectiveHeight = effective.height;
						bodyDeficit = requirement.getMinimumBodyHeight() - bodyHeight;
					}
				}
			}

			if (best != null && DialogueFitSolver.growthArea(configuredWidth, configuredHeight, nextWidth, configuredHeight) > dialogueFitGrowthCeiling(best, configuredWidth, configuredHeight, postDropShapeSearch)) {
				break;
			}
			if (nextWidth <= requestedWidth) {
				if (requestedWidth >= maximumWidth) {
					break;
				}
				nextWidth = Math.min(maximumWidth, requestedWidth + DIALOGUE_FIT_STEP);
			}

			requestedWidth = nextWidth;
		}

		boolean constrainedWrapEligible = false;
		if (!constrainedWrap && best == null && widestEffectiveWidth > 0 && (horizontalBlocked || previousRequestedWidth >= maximumWidth)) {
			final DialoguePrompts.FitRequirement widestRequirement = fitRequirement(requirements, widestEffectiveWidth, false);
			constrainedWrapEligible = widestRequirement.isActive() && widestEffectiveWidth < widestRequirement.getMinimumWidth();
		}

		return new DialogueFitSearch(best, fallback, constrainedWrapEligible);
	}

	private static boolean canUseConstrainedDialogueWrap(DialogueFitSearch strict) {
		return strict != null && strict.candidate == null && strict.constrainedWrapEligible;
	}

	private DialogueFitCandidate betterDialogueFitFallback(DialogueFitCandidate candidate, DialogueFitCandidate best, DialoguePrompts.FitRequirement requirement, int bodyHeight, int configuredWidth, int configuredHeight, boolean retainActiveFit, boolean activeFit, int retainedWidth, int retainedHeight) {
		if (candidate == null || !candidate.topologyValid || requirement == null || !requirement.isActive()) {
			return best;
		}

		final Rectangle effective = candidate.result.getEffectiveBounds();
		candidate.minimumWidthDeficit = Math.max(0, requirement.getMinimumWidth() - effective.width);
		candidate.minimumBodyHeightDeficit = Math.max(0, requirement.getMinimumBodyHeight() - bodyHeight);
		candidate.growthArea = DialogueFitSolver.growthArea(configuredWidth, configuredHeight, candidate.requestedWidth, candidate.requestedHeight);
		candidate.linearGrowth = Math.max(0, candidate.requestedWidth - configuredWidth) + Math.max(0, candidate.requestedHeight - configuredHeight);
		candidate.preferredDeficit = Math.max(0, requirement.getPreferredBodyHeight() - bodyHeight);
		candidate.retainedDistance = retainedDialogueFitDistance(candidate, retainActiveFit, activeFit, retainedWidth, retainedHeight);
		return isBetterDialogueFitFallback(candidate, best)
				? candidate
				: best;
	}

	private static boolean isBetterDialogueFitFallback(DialogueFitCandidate candidate, DialogueFitCandidate best) {
		if (candidate == null) {
			return false;
		}
		if (best == null) {
			return true;
		}
		if (candidate.minimumWidthDeficit != best.minimumWidthDeficit) {
			return candidate.minimumWidthDeficit < best.minimumWidthDeficit;
		}
		/* Avoid a much larger clipped rectangle for a difference of at most
		 * one dialogue line, unless its area is effectively the same. */
		final int bodyDeficitDifference = Math.abs(candidate.minimumBodyHeightDeficit - best.minimumBodyHeightDeficit);
		if (bodyDeficitDifference > FALLBACK_HEIGHT_DEFICIT_TOLERANCE) {
			return candidate.minimumBodyHeightDeficit < best.minimumBodyHeightDeficit;
		}
		if (candidate.constrainedWrap != best.constrainedWrap) {
			return !candidate.constrainedWrap;
		}
		final long smallerGrowth = Math.min(candidate.growthArea, best.growthArea);
		final long largerGrowth = Math.max(candidate.growthArea, best.growthArea);
		if (largerGrowth - smallerGrowth > smallerGrowth / 10L && candidate.growthArea != best.growthArea) {
			return candidate.growthArea < best.growthArea;
		}
		if (candidate.minimumBodyHeightDeficit != best.minimumBodyHeightDeficit) {
			return candidate.minimumBodyHeightDeficit < best.minimumBodyHeightDeficit;
		}
		if (candidate.growthArea != best.growthArea) {
			return candidate.growthArea < best.growthArea;
		}
		if (candidate.linearGrowth != best.linearGrowth) {
			return candidate.linearGrowth < best.linearGrowth;
		}
		if (candidate.preferredDeficit != best.preferredDeficit) {
			return candidate.preferredDeficit < best.preferredDeficit;
		}
		if (candidate.retainedDistance != best.retainedDistance) {
			return candidate.retainedDistance < best.retainedDistance;
		}
		if (candidate.requestedWidth != best.requestedWidth) {
			return candidate.requestedWidth < best.requestedWidth;
		}

		return candidate.requestedHeight < best.requestedHeight;
	}

	public DialoguePrompts.FitRequirement fitRequirement(Map<Integer, DialoguePrompts.FitRequirement> requirements, int effectiveWidth, boolean constrainedWrap) {
		DialoguePrompts.FitRequirement requirement = requirements.get(effectiveWidth);
		if (requirement == null) {
			requirement = dialoguePrompts.measureFit(effectiveWidth, constrainedWrap);
			requirements.put(effectiveWidth, requirement);
		}

		return requirement;
	}

	private int retainedDialogueFitDistance(DialogueFitCandidate candidate, boolean retainActiveFit, boolean activeFit, int retainedWidth, int retainedHeight) {
		if (!retainActiveFit || !activeFit || retainedWidth <= 0 || retainedHeight <= 0) {
			return 0;
		}

		return Math.abs(candidate.requestedWidth - retainedWidth) + Math.abs(candidate.requestedHeight - retainedHeight);
	}

	public long dialogueFitGrowthCeiling(DialogueFitCandidate best, int configuredWidth, int configuredHeight, boolean postDropShapeSearch) {
		if (best == null) {
			return Long.MAX_VALUE;
		}
		if (postDropShapeSearch) {
			return best.growthArea + best.growthArea * POST_DROP_BALANCE_AREA_PERCENT / 100L;
		}
		return best.growthArea + best.growthArea * (configuredWidth == configuredHeight ? BALANCED_FIT_AREA_TOLERANCE_PERCENT : DIALOGUE_FIT_AXIS_TOLERANCE_PERCENT) / 100L;
	}

	public boolean isBetterDialogueFitCandidate(DialogueFitCandidate candidate, DialogueFitCandidate best, int configuredWidth, int configuredHeight, boolean postDropShapeSearch) {
		if (candidate == null) {
			return false;
		}
		if (best == null) {
			return true;
		}
		// Identical geometry should not switch text-wrapping modes.
		if (candidate.requestedWidth == best.requestedWidth && candidate.requestedHeight == best.requestedHeight && candidate.constrainedWrap != best.constrainedWrap) {
			return !candidate.constrainedWrap;
		}
		/* Hold the previously selected shape when two complete fits require
		 * nearly the same growth. This preference is inactive for a new
		 * dialogue/content fingerprint or an intentional post-drop search. */
		if (!postDropShapeSearch && candidate.retainedDistance != best.retainedDistance && candidate.retainedDistance != Integer.MAX_VALUE && best.retainedDistance != Integer.MAX_VALUE) {
			final long smallerGrowth = Math.min(candidate.growthArea, best.growthArea);
			final long largerGrowth = Math.max(candidate.growthArea, best.growthArea);
			final boolean nearEqualArea = largerGrowth - smallerGrowth <= smallerGrowth * RETAINED_FIT_AREA_TOLERANCE_PERCENT / 100L;
			final boolean candidateDominates = candidate.requestedWidth <= best.requestedWidth && candidate.requestedHeight <= best.requestedHeight;
			final boolean bestDominates = best.requestedWidth <= candidate.requestedWidth && best.requestedHeight <= candidate.requestedHeight;
			if (nearEqualArea && !candidateDominates && !bestDominates) {
				if (candidate.retainedDistance + RETAINED_FIT_DISTANCE_MARGIN <= best.retainedDistance) {
					return true;
				}
				if (best.retainedDistance + RETAINED_FIT_DISTANCE_MARGIN <= candidate.retainedDistance) {
					return false;
				}
			}
		}
		/* After relocation only, allow a noticeably less slender fit within 15% area. */
		if (postDropShapeSearch) {
			final long candidateArea = candidate.growthArea;
			final long bestArea = best.growthArea;
			final long smallerArea = Math.min(candidateArea, bestArea);
			final long largerArea = Math.max(candidateArea, bestArea);
			if (largerArea - smallerArea <= smallerArea * POST_DROP_BALANCE_AREA_PERCENT / 100L) {
				final int candidateImbalance = Math.abs(candidate.requestedWidth - candidate.requestedHeight);
				final int bestImbalance = Math.abs(best.requestedWidth - best.requestedHeight);
				if (candidateImbalance + DIALOGUE_FIT_STEP < bestImbalance) {
					return true;
				}
				if (bestImbalance + DIALOGUE_FIT_STEP < candidateImbalance) {
					return false;
				}
			}
		}
		final int shapePreference = DialogueFitSolver.compareShapes(configuredWidth, configuredHeight, candidate.requestedWidth, candidate.requestedHeight, best.requestedWidth, best.requestedHeight);
		if (shapePreference != 0) {
			return shapePreference > 0;
		}
		if (candidate.preferredDeficit != best.preferredDeficit) {
			return candidate.preferredDeficit < best.preferredDeficit;
		}
		if (candidate.retainedDistance != best.retainedDistance) {
			return candidate.retainedDistance < best.retainedDistance;
		}
		if (candidate.constrainedWrap != best.constrainedWrap) {
			return !candidate.constrainedWrap;
		}
		if (candidate.requestedWidth != best.requestedWidth) {
			return candidate.requestedWidth < best.requestedWidth;
		}
		return candidate.requestedHeight < best.requestedHeight;
	}

	/**
	 * ================================================================
	 * FIT RESULT TYPES
	 * ================================================================
	 */
	public static final class DialogueFitCandidate {
		public final int requestedWidth;
		public final int requestedHeight;
		public final ChatboxBounds.Result result;
		public final boolean directHostMove;
		public final boolean expanded;
		public final boolean constrainedWrap;
		public final boolean topologyValid;
		public boolean clippedFallback;
		public Rectangle baselineDesired;
		public long growthArea = Long.MAX_VALUE;
		public int linearGrowth = Integer.MAX_VALUE;
		public int minimumWidthDeficit = Integer.MAX_VALUE;
		public int minimumBodyHeightDeficit = Integer.MAX_VALUE;
		public int preferredDeficit = Integer.MAX_VALUE;
		public int retainedDistance = Integer.MAX_VALUE;

		public DialogueFitCandidate(int requestedWidth, int requestedHeight, ChatboxBounds.Result result, boolean directHostMove, boolean expanded, boolean constrainedWrap, boolean topologyValid) {
			this.requestedWidth = requestedWidth;
			this.requestedHeight = requestedHeight;
			this.result = result;
			this.directHostMove = directHostMove;
			this.expanded = expanded;
			this.constrainedWrap = constrainedWrap;
			this.topologyValid = topologyValid;
		}
	}

	public static final class DialogueFitSelection {
		public final DialogueFitSearch strict;
		public final DialogueFitSearch wrapped;
		public final DialogueFitCandidate candidate;
		public final DialogueFitCandidate fallback;

		public DialogueFitSelection(DialogueFitSearch strict, DialogueFitSearch wrapped, DialogueFitCandidate candidate, DialogueFitCandidate fallback) {
			this.strict = strict;
			this.wrapped = wrapped;
			this.candidate = candidate;
			this.fallback = fallback;
		}
	}

	public static final class DialogueFitSearch {
		public final DialogueFitCandidate candidate;
		public final DialogueFitCandidate fallback;
		public final boolean constrainedWrapEligible;

		public DialogueFitSearch(DialogueFitCandidate candidate, DialogueFitCandidate fallback, boolean constrainedWrapEligible) {
			this.candidate = candidate;
			this.fallback = fallback;
			this.constrainedWrapEligible = constrainedWrapEligible;
		}
	}

	/**
	 * ================================================================
	 * COLLISION FIT INSPECTION
	 * ================================================================
	 */
	/* Standard fitting searches from the configured width and height. Collision
	 * inspection instead evaluates the effective viewport returned by
	 * ChatboxBounds, which may be as small as 200x100. Neither path changes
	 * the session center, native widgets, or the caller's collision tracker. */
	static final int COLLISION_LEFT = 1;
	static final int COLLISION_RIGHT = 2;
	static final int COLLISION_TOP = 4;
	static final int COLLISION_BOTTOM = 8;

	private static final int COLLISION_STEP = 4;
	/* At the shared 200px minimum width, a side obstacle may already cross
	 * the effective viewport. Orthogonal growth may extend the EXISTING
	 * overlap vertically but must never start a new obstacle contact or
	 * penetrate any farther horizontally. Whether text is actually obscured
	 * is evaluated separately against the rendered dialogue leaves. */
	private static final int COLLISION_MINIMUM_WIDTH = 200;

	interface CollisionRequirements {
		int minimumSlotHeight(int width);
		boolean fits(int width, int slotHeight);
	}

	interface CollisionResolver {
		CollisionResolved resolve(Rectangle desiredHost);
	}

	static final class CollisionResolved {
		final Rectangle desired;
		final Rectangle effective;
		final boolean topologyValid;
		final String rejection;

		CollisionResolved(Rectangle desired, Rectangle effective, boolean topologyValid) {
			this(desired, effective, topologyValid, null);
		}

		CollisionResolved(Rectangle desired, Rectangle effective, boolean topologyValid, String rejection) {
			this.desired = desired != null ? new Rectangle(desired) : null;
			this.effective = effective != null ? new Rectangle(effective) : null;
			this.topologyValid = topologyValid;
			this.rejection = rejection;
		}
	}

	static final class CollisionInspection {
		final String outcome;
		final int edges;
		final int checked;
		final int neededHeight;
		final Rectangle proposedHost;
		final Rectangle proposedEffective;
		final boolean complete;
		final String rejection;
		final String selectionSource;

		CollisionInspection(String outcome, int edges, int checked, int neededHeight, Rectangle proposedHost, Rectangle proposedEffective, boolean complete, String rejection, String selectionSource) {
			this.outcome = outcome;
			this.edges = edges;
			this.checked = checked;
			this.neededHeight = neededHeight;
			this.proposedHost = proposedHost != null ? new Rectangle(proposedHost) : null;
			this.proposedEffective = proposedEffective != null ? new Rectangle(proposedEffective) : null;
			this.complete = complete;
			this.rejection = rejection;
			this.selectionSource = selectionSource;
		}
	}

	/* Evaluate first-contact growth from the original desired host, not a
	 * previously clipped viewport. Full fits and content-improving partial
	 * fits must preserve the collision-owned edge and existing visible area. */
	static CollisionInspection inspectAnchoredFirstContact(Rectangle desired, Rectangle effective, Point userCenter, int canvasWidth, int canvasHeight, List<Rectangle> obstacles, int edges, CollisionRequirements requirements, CollisionResolver resolver) {
		if (desired == null || effective == null || requirements == null || resolver == null || canvasWidth <= 0 || canvasHeight <= 0) {
			return emptyCollisionInspection("INVALID_INPUT", edges, 0, 0, "MISSING_GEOMETRY");
		}
		if (desired.equals(effective) || edges == 0) {
			return emptyCollisionInspection("NO_COLLISION", edges, 0, 0, "NO_ACTIVE_EDGE");
		}
		final int needed = requirements.minimumSlotHeight(effective.width);
		if (requirements.fits(effective.width, effective.height)) {
			return emptyCollisionInspection("CONTENT_FITS", edges, 0, needed, "NO_DEFICIT");
		}
		if ((edges & (COLLISION_LEFT | COLLISION_RIGHT)) == (COLLISION_LEFT | COLLISION_RIGHT) || (edges & (COLLISION_TOP | COLLISION_BOTTOM)) == (COLLISION_TOP | COLLISION_BOTTOM) || (edges & (COLLISION_LEFT | COLLISION_RIGHT)) != 0 && (edges & (COLLISION_TOP | COLLISION_BOTTOM)) != 0) {
			return emptyCollisionInspection("MULTI_EDGE_HOLD", edges, 0, needed, "PROTECTED_AXES");
		}
		final boolean horizontalContact = (edges & (COLLISION_LEFT | COLLISION_RIGHT)) != 0;
		final int roomBefore = orthogonalFreeSpace(effective, obstacles, canvasWidth, canvasHeight, horizontalContact ? 0 : 2);
		final int roomAfter = orthogonalFreeSpace(effective, obstacles, canvasWidth, canvasHeight, horizontalContact ? 1 : 3);
		final int goal;
		if (horizontalContact) {
			final int requested = alignGrowth(needed, desired.height);
			goal = Math.min(requested, effective.height + roomBefore + roomAfter);
		} else {
			final int maxWidth = effective.width + roomBefore + roomAfter;
			if (maxWidth <= desired.width) {
				return emptyCollisionInspection("NO_HORIZONTAL_ROOM", edges, 0, needed, "NO_FREE_SPACE");
			}
			if (requirements.fits(maxWidth, effective.height)) {
				int low = 1;
				int high = (maxWidth - effective.width + COLLISION_STEP - 1) / COLLISION_STEP;
				while (low < high) {
					final int mid = low + (high - low) / 2;
					final int width = Math.min(maxWidth, effective.width + mid * COLLISION_STEP);
					if (requirements.fits(width, effective.height)) {
						high = mid;
					} else {
						low = mid + 1;
					}
				}
				goal = Math.min(maxWidth, effective.width + low * COLLISION_STEP);
			} else {
				goal = Math.min(maxWidth, desired.width + 160);
			}
		}
		final int originalSize = horizontalContact ? desired.height : desired.width;
		if (goal < originalSize + COLLISION_STEP) {
			return emptyCollisionInspection("NO_USEFUL_GROWTH", edges, 0, needed, "BELOW_STEP");
		}
		final Point center = userCenter != null ? new Point(userCenter) : new Point(desired.x + desired.width / 2, desired.y + desired.height / 2);
		final Rectangle canvas = new Rectangle(0, 0, canvasWidth, canvasHeight);
		Rectangle bestHost = null;
		Rectangle bestVisible = null;
		boolean completeBest = false;
		long bestScore = Long.MAX_VALUE;
		String bestSource = "NO_CANDIDATE";
		String rejected = "NO_CANDIDATE";
		int checked = 0;
		/* Prefer centered growth unless an adjacent obstacle blocks it. Then
		 * consider only recovery toward that obstacle, without escaping to
		 * the opposite direction solely to obtain a complete fit. */
		final int growth = Math.max(0, goal - originalSize);
		final int recoverySide = horizontalContact ? adjacentVerticalRecoverySide(desired, effective, obstacles, growth) : adjacentHorizontalRecoverySide(desired, effective, obstacles, growth);
		final List<Rectangle> hosts;
		if (recoverySide == 0) {
			/* Prefer the immutable dialogue-center candidate, but also try
			 * the verified physical host's own anchor. RuneLite TOP_CENTER
			 * may settle a few pixels away from the saved user center, and
			 * a previously clipped viewport can be far from it. Those differences
			 * must not make a native-safe candidate ineligible. */
			hosts = collisionHosts(desired, center, horizontalContact, goal);
		} else {
			final int direction = horizontalContact ? recoverySide < 0 ? 0 : 1 : recoverySide < 0 ? 2 : 3;
			final int free = orthogonalFreeSpace(effective, obstacles, canvasWidth, canvasHeight, direction);
			final int added = Math.min(growth, free);
			final int alignedAdded = added / COLLISION_STEP * COLLISION_STEP;
			if (alignedAdded < COLLISION_STEP) {
				return emptyCollisionInspection("DIRECTIONAL_BLOCKED", edges, 0, needed, "NO_SPACE_TOWARD_OBSTACLE");
			}
			final int size = originalSize + alignedAdded;
			if (horizontalContact) {
				final int y = recoverySide < 0 ? desired.y + desired.height - size : desired.y;
				hosts = Collections.singletonList(new Rectangle(desired.x, y, desired.width, size));
			} else {
				final int x = recoverySide < 0 ? desired.x + desired.width - size : desired.x;
				hosts = Collections.singletonList(new Rectangle(x, desired.y, size, desired.height));
			}
		}
		/* The original desired rectangle owns host X during side collisions
		 * and host Y during top/bottom collisions. The viewport may still
		 * shrink inward along the obstructed axis. */
		for (Rectangle host : hosts) {
			if (!canvas.contains(host)) {
				/* Keep the on-canvas rejection; a later off-canvas proposal must
				 * not overwrite a more useful explanation of the failed fit. */
				if ("NO_CANDIDATE".equals(rejected)) {
					rejected = collisionRejection("CANVAS", host, null);
				}
				continue;
			}
			final CollisionResolved resolved = resolver.resolve(new Rectangle(host));
			checked++;
			if (resolved == null) {
				rejected = collisionRejection("RESOLVER_UNAVAILABLE", host, null);
				continue;
			}
			if (!resolved.topologyValid) {
				rejected = collisionRejection(resolved.rejection != null ? resolved.rejection : "TOPOLOGY_INVALID", host, resolved.effective);
				continue;
			}
			if (resolved.effective == null) {
				rejected = collisionRejection("EFFECTIVE_UNAVAILABLE", host, null);
				continue;
			}
			if (!host.equals(resolved.desired)) {
				rejected = collisionRejection("DESIRED_RESOLUTION_MISMATCH", host, resolved.effective);
				continue;
			}
			final Rectangle visible = resolved.effective;
			if (!canvas.contains(visible)) {
				rejected = collisionRejection("VISIBLE_OUTSIDE_CANVAS", host, visible);
				continue;
			}
			if (!collisionProtectedEdgePreserved(edges, effective, visible)) {
				rejected = collisionRejection("PROTECTED_EDGE_CHANGED", host, visible);
				continue;
			}
			if (!visible.contains(effective)) {
				rejected = collisionRejection("ORIGINAL_VIEWPORT_LOST", host, visible);
				continue;
			}
			if (collisionAddsObstacleOverlap(effective, visible, obstacles, edges)) {
				rejected = collisionRejection("UNSAFE_NEW_OBSTACLE_OVERLAP", host, visible);
				continue;
			}
			final boolean complete = requirements.fits(visible.width, visible.height);
			final boolean improves = horizontalContact ? visible.height > effective.height : requirements.minimumSlotHeight(visible.width) < needed;
			if (!complete && !improves) {
				rejected = collisionRejection("NO_CONTENT_IMPROVEMENT", host, visible);
				continue;
			}
			final long centerError = horizontalContact ? Math.abs(2L * visible.y + visible.height - 2L * center.y) : Math.abs(2L * visible.x + visible.width - 2L * center.x);
			final long score = centerError * 4L + (horizontalContact ? Math.abs(host.y - desired.y) : Math.abs(host.x - desired.x));
			if (bestHost == null || complete && !completeBest || complete == completeBest && score < bestScore) {
				bestHost = host;
				bestVisible = visible;
				completeBest = complete;
				bestScore = score;
				bestSource = recoverySide == 0 && host.equals(hosts.get(0)) ? "CENTER_RANKED" : "HOST_ANCHORED_FALLBACK";
			}
		}
		if (bestHost == null) {
			return emptyCollisionInspection("NO_ANCHORED_CANDIDATE", edges, checked, needed, rejected);
		}
		return new CollisionInspection(completeBest ? "ANCHORED_FULL_FIT" : "ANCHORED_PARTIAL_FIT", edges, checked, needed, bestHost, bestVisible, completeBest, "NONE", bestSource);
	}

	/* -1: upward; +1: downward; 0: centered search. Only a neighboring
	 * obstacle blocking symmetric growth may select a recovery direction. */
	private static int adjacentVerticalRecoverySide(Rectangle desired, Rectangle effective, List<Rectangle> obstacles, int growth) {
		if (obstacles == null || growth < COLLISION_STEP) {
			return 0;
		}
		final int halfGrowth = (growth + 1) / 2;
		int topDistance = Integer.MAX_VALUE;
		int bottomDistance = Integer.MAX_VALUE;
		for (Rectangle obstacle : obstacles) {
			if (obstacle == null || obstacle.width <= 0 || obstacle.height <= 0 || obstacle.x >= effective.x + effective.width || obstacle.x + obstacle.width <= effective.x) {
				continue;
			}
			final int obstacleBottom = obstacle.y + obstacle.height;
			if (obstacleBottom <= desired.y) {
				topDistance = Math.min(topDistance, desired.y - obstacleBottom);
			}
			if (obstacle.y >= desired.y + desired.height) {
				bottomDistance = Math.min(bottomDistance, obstacle.y - desired.y - desired.height);
			}
		}
		final boolean topBlocksCenter = topDistance < halfGrowth;
		final boolean bottomBlocksCenter = bottomDistance < halfGrowth;
		if (topBlocksCenter && !bottomBlocksCenter) {
			return -1;
		}
		if (bottomBlocksCenter && !topBlocksCenter) {
			return 1;
		}
		return topBlocksCenter && bottomBlocksCenter ? topDistance <= bottomDistance ? -1 : 1 : 0;
	}

	/* -1: leftward; +1: rightward; 0: centered search. Only a neighboring
	 * obstacle blocking symmetric growth may select a recovery direction. */
	private static int adjacentHorizontalRecoverySide(Rectangle desired, Rectangle effective, List<Rectangle> obstacles, int growth) {
		if (obstacles == null || growth < COLLISION_STEP) {
			return 0;
		}
		final int halfGrowth = (growth + 1) / 2;
		int leftDistance = Integer.MAX_VALUE;
		int rightDistance = Integer.MAX_VALUE;
		for (Rectangle obstacle : obstacles) {
			if (obstacle == null || obstacle.width <= 0 || obstacle.height <= 0 || obstacle.y >= effective.y + effective.height || obstacle.y + obstacle.height <= effective.y) {
				continue;
			}
			final int obstacleRight = obstacle.x + obstacle.width;
			if (obstacleRight <= desired.x) {
				leftDistance = Math.min(leftDistance, desired.x - obstacleRight);
			}
			if (obstacle.x >= desired.x + desired.width) {
				rightDistance = Math.min(rightDistance, obstacle.x - desired.x - desired.width);
			}
		}
		final boolean leftBlocksCenter = leftDistance < halfGrowth;
		final boolean rightBlocksCenter = rightDistance < halfGrowth;
		if (leftBlocksCenter && !rightBlocksCenter) {
			return -1;
		}
		if (rightBlocksCenter && !leftBlocksCenter) {
			return 1;
		}
		return leftBlocksCenter && rightBlocksCenter ? leftDistance <= rightDistance ? -1 : 1 : 0;
	}

	private static int orthogonalFreeSpace(Rectangle visible, List<Rectangle> obstacles, int canvasWidth, int canvasHeight, int direction) {
		final int maximum;
		switch (direction) {
			case 0: maximum = visible.y;
				break;
			case 1: maximum = canvasHeight - visible.y - visible.height;
				break;
			case 2: maximum = visible.x;
				break;
			default: maximum = canvasWidth - visible.x - visible.width;
				break;
		}
		int space = Math.max(0, maximum);
		if (obstacles == null) {
			return space;
		}
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

	/* Preserve the rejection reason for the last inspected on-canvas candidate;
	 * this read-only description does not influence candidate approval. */
	private static String collisionRejection(String reason, Rectangle host, Rectangle effective) {
		return reason + "(host=" + collisionBounds(host) + ",effective=" + collisionBounds(effective) + ")";
	}

	private static String collisionBounds(Rectangle bounds) {
		return bounds == null ? "-" : bounds.x + "," + bounds.y + "," + bounds.width + "x" + bounds.height;
	}

	private static CollisionInspection emptyCollisionInspection(String outcome, int edges, int checked, int neededHeight, String rejection) {
		return new CollisionInspection(outcome, edges, checked, neededHeight, null, null, false, rejection, "NONE");
	}

	private static List<Rectangle> collisionHosts(Rectangle desired, Point center, boolean growHeight, int target) {
		final List<Rectangle> result = new ArrayList<>(4);
		if (growHeight) {
			addCollisionHost(result, new Rectangle(desired.x, center.y - target / 2, desired.width, target));
			addCollisionHost(result, new Rectangle(desired.x, desired.y + (desired.height - target) / 2, desired.width, target));
			addCollisionHost(result, new Rectangle(desired.x, desired.y, desired.width, target));
			addCollisionHost(result, new Rectangle(desired.x, desired.y + desired.height - target, desired.width, target));
		} else {
			addCollisionHost(result, new Rectangle(center.x - target / 2, desired.y, target, desired.height));
			addCollisionHost(result, new Rectangle(desired.x + (desired.width - target) / 2, desired.y, target, desired.height));
			addCollisionHost(result, new Rectangle(desired.x, desired.y, target, desired.height));
			addCollisionHost(result, new Rectangle(desired.x + desired.width - target, desired.y, target, desired.height));
		}
		return result;
	}

	private static void addCollisionHost(List<Rectangle> hosts, Rectangle candidate) {
		if (!hosts.contains(candidate)) {
			hosts.add(candidate);
		}
	}

	private static boolean collisionProtectedEdgePreserved(int edges, Rectangle before, Rectangle after) {
		if ((edges & COLLISION_LEFT) != 0) {
			return after.x + after.width == before.x + before.width && after.width == before.width;
		}
		if ((edges & COLLISION_RIGHT) != 0) {
			return after.x == before.x && after.width == before.width;
		}
		if ((edges & COLLISION_TOP) != 0) {
			return after.y + after.height == before.y + before.height && after.height == before.height;
		}
		return (edges & COLLISION_BOTTOM) != 0 && after.y == before.y && after.height == before.height;
	}

	private static boolean collisionAddsObstacleOverlap(Rectangle before, Rectangle after, List<Rectangle> obstacles, int edges) {
		if (obstacles == null) {
			return false;
		}
		for (Rectangle obstacle : obstacles) {
			if (obstacle == null || obstacle.width <= 0 || obstacle.height <= 0) {
				continue;
			}
			final Rectangle newOverlap = after.intersection(obstacle);
			if (newOverlap.isEmpty()) {
				continue;
			}
			final Rectangle oldOverlap = before.intersection(obstacle);
			if (!oldOverlap.isEmpty() && oldOverlap.contains(newOverlap)) {
				continue;
			}
			/* Minimum-width pass-through is an ALREADY-OBSCURED side, not
			 * permission to move a protected edge or make new contact. The
			 * intersection with this obstacle must retain exactly the same
			 * horizontal footprint. Only its vertical extent may increase.
			 * No arbitrary pixel-depth cap: the 200px shared minimum is the
			 * explicit boundary of the collision policy. A geometrically
			 * complete layout is checked for actual foreground text occlusion
			 * by DialogueViewport before reporting visual completeness. */
			final boolean minimumSidePassThrough = (edges == COLLISION_LEFT || edges == COLLISION_RIGHT) && before.width == COLLISION_MINIMUM_WIDTH && after.width == before.width && after.x == before.x && !oldOverlap.isEmpty() && newOverlap.x == oldOverlap.x && newOverlap.width == oldOverlap.width && (edges == COLLISION_LEFT ? newOverlap.x == after.x : newOverlap.x + newOverlap.width == after.x + after.width);
			if (!minimumSidePassThrough) {
				return true;
			}
		}
		return false;
	}
}
