package com.bigboldchat.layout;

import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bigboldchat.chatbox.ChatboxGeometry;
import com.bigboldchat.chatbox.ChatboxPlacement;

import net.runelite.api.Client;

/**
 * Resolves configured chatbox geometry against visible interface bounds.
 */
public final class ChatboxBounds {
	private static final int MIN_WIDTH = 200;
	private static final int MIN_HEIGHT = 100;

	private static final int COLLIDE_LEFT = 1;
	private static final int COLLIDE_RIGHT = 1 << 1;
	private static final int COLLIDE_TOP = 1 << 2;
	private static final int COLLIDE_BOTTOM = 1 << 3;

	private static final int COLLIDE_HORIZONTAL = COLLIDE_LEFT | COLLIDE_RIGHT;
	private static final int COLLIDE_VERTICAL = COLLIDE_TOP | COLLIDE_BOTTOM;
	private static final int COLLIDE_ALL = COLLIDE_HORIZONTAL | COLLIDE_VERTICAL;

	private ChatboxBounds() {
		/*
		 * CHATBOX BOUNDS
		 */
	}

	/**
	 * ================================================================
	 * RESOLUTION
	 * ================================================================
	 */
	public static Result resolve(Client client, ChatboxPlacement.State placement, boolean buttonsHidden) {
		return resolve(client, placement, buttonsHidden, new Tracker(), null);
	}

	public static Result resolve(Client client, ChatboxPlacement.State placement, boolean buttonsHidden, Tracker tracker) {
		return resolve(client, placement, buttonsHidden, tracker, null);
	}

	public static Result resolve(Client client, ChatboxPlacement.State placement, boolean buttonsHidden, Tracker tracker,
			InterfaceBounds.Overrides overrides) {
		if (client == null || placement == null) {
			return Result.unbounded(0, 0);
		}

		final Rectangle desiredBounds = placement.getDesiredBounds();
		if (!placement.isBounded()) {
			return Result.unbounded(desiredBounds.width, desiredBounds.height);
		}

		final InterfaceBounds interfaces = InterfaceBounds.capture(client, overrides);
		final Tracker activeTracker = tracker != null ? tracker : new Tracker();
		final Resolution resolution = activeTracker.resolve(desiredBounds, interfaces.getCanvas(), interfaces.getIdentifiedObstacles());
		final Rectangle effectiveBounds = resolution.bounds;
		final Rectangle presentationBounds = new Rectangle(effectiveBounds.x, effectiveBounds.y, effectiveBounds.width,
				ChatboxGeometry.bodyHeight(effectiveBounds.height, buttonsHidden));

		return new Result(desiredBounds, effectiveBounds, interfaces.intersectsForeground(presentationBounds));
	}

	private static boolean fits(Rectangle candidate, Rectangle canvas, List<InterfaceBounds.Obstacle> obstacles) {
		if (!canvas.contains(candidate)) {
			return false;
		}

		for (InterfaceBounds.Obstacle obstacle : obstacles) {
			if (candidate.intersects(obstacle.getBounds())) {
				return false;
			}
		}

		return true;
	}

	/**
	 * ================================================================
	 * COLLISION TRACKING
	 * ================================================================
	 */
	public static final class Tracker {
		private final Map<InterfaceBounds.ObstacleKey, Contact> contacts = new EnumMap<>(InterfaceBounds.ObstacleKey.class);

		private Rectangle previousDesired;

		public void reset() {
			contacts.clear();
			previousDesired = null;
		}

		private Resolution resolve(Rectangle desired, Rectangle canvas, List<InterfaceBounds.Obstacle> obstacles) {
			int left = desired.x;
			int top = desired.y;
			int right = desired.x + desired.width;
			int bottom = desired.y + desired.height;
			int collisionMask = 0;

			/*
			 * Canvas collisions constrain only the crossed edge.
			 */
			if (left < canvas.x) {
				left = canvas.x;
				collisionMask |= COLLIDE_LEFT;
			}
			if (right > canvas.x + canvas.width) {
				right = canvas.x + canvas.width;
				collisionMask |= COLLIDE_RIGHT;
			}
			if (top < canvas.y) {
				top = canvas.y;
				collisionMask |= COLLIDE_TOP;
			}
			if (bottom > canvas.y + canvas.height) {
				bottom = canvas.y + canvas.height;
				collisionMask |= COLLIDE_BOTTOM;
			}

			final Set<InterfaceBounds.ObstacleKey> seen = EnumSet.noneOf(InterfaceBounds.ObstacleKey.class);
			for (InterfaceBounds.Obstacle obstacle : obstacles) {
				final InterfaceBounds.ObstacleKey key = obstacle.getKey();
				final Rectangle bounds = obstacle.getBounds();
				seen.add(key);

				final Contact contact = contacts.computeIfAbsent(key, ignored -> new Contact());
				if (!desired.intersects(bounds)) {
					contact.collisionMask = 0;
					contact.previousBounds = new Rectangle(bounds);
					continue;
				}

				if (contact.collisionMask == 0) {
					contact.collisionMask = detectEntrySides(previousDesired, contact.previousBounds, desired, bounds);
				}

				collisionMask |= contact.collisionMask;
				if ((contact.collisionMask & COLLIDE_LEFT) != 0) {
					left = Math.max(left, bounds.x + bounds.width);
				}
				if ((contact.collisionMask & COLLIDE_RIGHT) != 0) {
					right = Math.min(right, bounds.x);
				}
				if ((contact.collisionMask & COLLIDE_TOP) != 0) {
					top = Math.max(top, bounds.y + bounds.height);
				}
				if ((contact.collisionMask & COLLIDE_BOTTOM) != 0) {
					bottom = Math.min(bottom, bounds.y);
				}

				contact.previousBounds = new Rectangle(bounds);
			}

			contacts.keySet().removeIf(key -> !seen.contains(key));
			previousDesired = new Rectangle(desired);

			final Axis horizontal = enforceMinimum(desired.x, desired.x + desired.width, left, right, MIN_WIDTH,
					collisionMask & COLLIDE_HORIZONTAL, COLLIDE_LEFT, COLLIDE_RIGHT);
			final Axis vertical = enforceMinimum(desired.y, desired.y + desired.height, top, bottom, MIN_HEIGHT,
					collisionMask & COLLIDE_VERTICAL, COLLIDE_TOP, COLLIDE_BOTTOM);
			final Rectangle trackedBounds = new Rectangle(horizontal.start, vertical.start,
					Math.max(0, horizontal.end - horizontal.start), Math.max(0, vertical.end - vertical.start));

			return preferSingleEdge(desired, canvas, obstacles, new Resolution(trackedBounds, collisionMask));
		}
	}

	/**
	 * ================================================================
	 * COLLISION HELPERS
	 * ================================================================
	 */
	/*
	 * Prefer one detected edge when it clears every current obstacle and preserves more usable area.
	 */
	private static Resolution preferSingleEdge(Rectangle desired, Rectangle canvas, List<InterfaceBounds.Obstacle> obstacles, Resolution tracked) {
		if (desired == null || canvas == null || tracked == null || !canvas.contains(desired) || tracked.collisionMask == 0) {
			return tracked;
		}

		Rectangle bestBounds = tracked.bounds;
		int bestMask = tracked.collisionMask;
		long bestArea = area(bestBounds);

		final int[] sides = {COLLIDE_LEFT, COLLIDE_RIGHT, COLLIDE_TOP, COLLIDE_BOTTOM};
		for (int side : sides) {
			if ((tracked.collisionMask & side) == 0) {
				continue;
			}

			final Rectangle candidate = singleEdgeCandidate(desired, obstacles, side);
			if (candidate == null || candidate.width < MIN_WIDTH || candidate.height < MIN_HEIGHT || !fits(candidate, canvas, obstacles)) {
				continue;
			}

			final long candidateArea = area(candidate);
			if (candidateArea > bestArea) {
				bestBounds = candidate;
				bestMask = side;
				bestArea = candidateArea;
			}
		}

		return bestBounds == tracked.bounds ? tracked : new Resolution(bestBounds, bestMask);
	}

	private static Rectangle singleEdgeCandidate(Rectangle desired, List<InterfaceBounds.Obstacle> obstacles, int side) {
		int left = desired.x;
		int top = desired.y;
		int right = desired.x + desired.width;
		int bottom = desired.y + desired.height;
		boolean intersects = false;

		for (InterfaceBounds.Obstacle obstacle : obstacles) {
			final Rectangle bounds = obstacle.getBounds();
			if (!desired.intersects(bounds)) {
				continue;
			}

			intersects = true;
			if (side == COLLIDE_LEFT) {
				left = Math.max(left, bounds.x + bounds.width);
			} else if (side == COLLIDE_RIGHT) {
				right = Math.min(right, bounds.x);
			} else if (side == COLLIDE_TOP) {
				top = Math.max(top, bounds.y + bounds.height);
			} else if (side == COLLIDE_BOTTOM) {
				bottom = Math.min(bottom, bounds.y);
			}
		}

		if (!intersects || right <= left || bottom <= top) {
			return null;
		}

		return new Rectangle(left, top, right - left, bottom - top);
	}

	private static long area(Rectangle bounds) {
		return bounds != null ? (long) Math.max(0, bounds.width) * Math.max(0, bounds.height) : 0L;
	}

	private static int detectEntrySides(Rectangle previousDesired, Rectangle previousObstacle, Rectangle desired, Rectangle obstacle) {
		int collisionMask = 0;

		/*
		 * New contacts choose the single edge that preserves the largest usable area.
		 */
		if (previousObstacle == null) {
			return bestSingleSide(desired, obstacle, COLLIDE_ALL);
		}

		/*
		 * Discontinuous obstacle moves are resolved from current geometry instead of prior direction.
		 */
		if (isDiscontinuousMove(previousObstacle, obstacle)) {
			return bestSingleSide(desired, obstacle, COLLIDE_ALL);
		}

		if (previousDesired != null && !previousDesired.intersects(previousObstacle)) {
			final int previousRight = previousDesired.x + previousDesired.width;
			final int previousBottom = previousDesired.y + previousDesired.height;
			final int previousObstacleRight = previousObstacle.x + previousObstacle.width;
			final int previousObstacleBottom = previousObstacle.y + previousObstacle.height;

			if (previousObstacleRight <= previousDesired.x) {
				collisionMask |= COLLIDE_LEFT;
			}
			if (previousObstacle.x >= previousRight) {
				collisionMask |= COLLIDE_RIGHT;
			}
			if (previousObstacleBottom <= previousDesired.y) {
				collisionMask |= COLLIDE_TOP;
			}
			if (previousObstacle.y >= previousBottom) {
				collisionMask |= COLLIDE_BOTTOM;
			}

			if (collisionMask != 0) {
				return resolveCornerCollision(desired, obstacle, collisionMask);
			}
		}

		final int desiredRight = desired.x + desired.width;
		final int desiredBottom = desired.y + desired.height;
		final int obstacleRight = obstacle.x + obstacle.width;
		final int obstacleBottom = obstacle.y + obstacle.height;

		if (obstacle.x <= desired.x && obstacleRight > desired.x) {
			collisionMask |= COLLIDE_LEFT;
		}
		if (obstacle.x < desiredRight && obstacleRight >= desiredRight) {
			collisionMask |= COLLIDE_RIGHT;
		}
		if (obstacle.y <= desired.y && obstacleBottom > desired.y) {
			collisionMask |= COLLIDE_TOP;
		}
		if (obstacle.y < desiredBottom && obstacleBottom >= desiredBottom) {
			collisionMask |= COLLIDE_BOTTOM;
		}

		if (collisionMask != 0) {
			return resolveCornerCollision(desired, obstacle, collisionMask);
		}

		return bestSingleSide(desired, obstacle, COLLIDE_ALL);
	}

	private static boolean isDiscontinuousMove(Rectangle previous, Rectangle current) {
		if (previous == null || current == null) {
			return false;
		}

		final int horizontalThreshold = Math.max(1, Math.max(previous.width, current.width));
		final int verticalThreshold = Math.max(1, Math.max(previous.height, current.height));
		return Math.abs(current.x - previous.x) > horizontalThreshold || Math.abs(current.y - previous.y) > verticalThreshold;
	}

	private static int resolveCornerCollision(Rectangle desired, Rectangle obstacle, int collisionMask) {
		final boolean horizontal = (collisionMask & COLLIDE_HORIZONTAL) != 0;
		final boolean vertical = (collisionMask & COLLIDE_VERTICAL) != 0;
		return horizontal && vertical ? bestSingleSide(desired, obstacle, collisionMask) : collisionMask;
	}

	private static int bestSingleSide(Rectangle desired, Rectangle obstacle, int candidates) {
		int bestSide = 0;
		boolean bestValid = false;
		long bestArea = -1L;
		int bestLoss = Integer.MAX_VALUE;

		final int[] sides = {COLLIDE_LEFT, COLLIDE_RIGHT, COLLIDE_TOP, COLLIDE_BOTTOM};
		for (int side : sides) {
			if ((candidates & side) == 0) {
				continue;
			}

			final int remaining = remainingSize(desired, obstacle, side);
			final boolean horizontal = (side & COLLIDE_HORIZONTAL) != 0;
			final int desiredSize = horizontal ? desired.width : desired.height;
			final int minimum = horizontal ? MIN_WIDTH : MIN_HEIGHT;
			final boolean valid = remaining >= minimum;
			final long remainingArea = horizontal ? (long) remaining * desired.height : (long) desired.width * remaining;
			final int loss = Math.max(0, desiredSize - remaining);

			if (bestSide == 0 || valid && !bestValid || valid == bestValid && remainingArea > bestArea
					|| valid == bestValid && remainingArea == bestArea && loss < bestLoss) {
				bestSide = side;
				bestValid = valid;
				bestArea = remainingArea;
				bestLoss = loss;
			}
		}

		return bestSide != 0 ? bestSide : COLLIDE_LEFT;
	}

	private static int remainingSize(Rectangle desired, Rectangle obstacle, int side) {
		final int desiredRight = desired.x + desired.width;
		final int desiredBottom = desired.y + desired.height;
		final int obstacleRight = obstacle.x + obstacle.width;
		final int obstacleBottom = obstacle.y + obstacle.height;

		if (side == COLLIDE_LEFT) {
			return clamp(desiredRight - obstacleRight, 0, desired.width);
		}
		if (side == COLLIDE_RIGHT) {
			return clamp(obstacle.x - desired.x, 0, desired.width);
		}
		if (side == COLLIDE_TOP) {
			return clamp(desiredBottom - obstacleBottom, 0, desired.height);
		}

		return clamp(obstacle.y - desired.y, 0, desired.height);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private static Axis enforceMinimum(int desiredStart, int desiredEnd, int constrainedStart, int constrainedEnd,
			int minimum, int collisionMask, int startCollision, int endCollision) {
		if (constrainedEnd - constrainedStart >= minimum) {
			return new Axis(constrainedStart, constrainedEnd);
		}

		if (collisionMask == startCollision) {
			return new Axis(constrainedEnd - minimum, constrainedEnd);
		}

		if (collisionMask == endCollision) {
			return new Axis(constrainedStart, constrainedStart + minimum);
		}

		final int desiredCenter = desiredStart + (desiredEnd - desiredStart) / 2;
		int start = desiredCenter - minimum / 2;
		int end = start + minimum;
		if (start < desiredStart) {
			start = desiredStart;
			end = start + minimum;
		}
		if (end > desiredEnd) {
			end = desiredEnd;
			start = end - minimum;
		}

		return new Axis(start, end);
	}

	/**
	 * ================================================================
	 * STATE TYPES
	 * ================================================================
	 */
	private static final class Contact {
		private Rectangle previousBounds;
		private int collisionMask;
	}

	private static final class Resolution {
		private final Rectangle bounds;
		private final int collisionMask;

		private Resolution(Rectangle bounds, int collisionMask) {
			this.bounds = bounds;
			this.collisionMask = collisionMask;
		}
	}

	private static final class Axis {
		private final int start;
		private final int end;

		private Axis(int start, int end) {
			this.start = start;
			this.end = end;
		}
	}

	public static final class Result {
		private final Rectangle desiredBounds;
		private final Rectangle effectiveBounds;
		private final boolean foregroundOverlap;

		private Result(Rectangle desiredBounds, Rectangle effectiveBounds, boolean foregroundOverlap) {
			this.desiredBounds = new Rectangle(desiredBounds);
			this.effectiveBounds = new Rectangle(effectiveBounds);
			this.foregroundOverlap = foregroundOverlap;
		}

		private static Result unbounded(int width, int height) {
			final Rectangle bounds = new Rectangle(0, 0, width, height);
			return new Result(bounds, bounds, false);
		}

		public int getWidth() {
			return effectiveBounds.width;
		}

		public int getHeight() {
			return effectiveBounds.height;
		}

		public Rectangle getDesiredBounds() {
			return new Rectangle(desiredBounds);
		}

		public Rectangle getEffectiveBounds() {
			return new Rectangle(effectiveBounds);
		}

		public boolean isForegroundOverlap() {
			return foregroundOverlap;
		}
	}
}
