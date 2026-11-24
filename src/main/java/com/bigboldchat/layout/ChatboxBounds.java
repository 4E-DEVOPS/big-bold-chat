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
 * Resolves desired chatbox geometry against the canvas and movable interface obstacles.
 * The desired rectangle remains unchanged; collisions affect only the effective rectangle.
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

	/* Preserve the 200x100 collision minimum independently of dialogue-fit requirements. */
	public static Result resolve(Client client, ChatboxPlacement.State placement, boolean buttonsHidden, Tracker tracker, InterfaceBounds.Overrides overrides) {
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
		final Rectangle presentationBounds = new Rectangle(effectiveBounds.x, effectiveBounds.y, effectiveBounds.width, ChatboxGeometry.bodyHeight(effectiveBounds.height, buttonsHidden));

		return new Result(desiredBounds, effectiveBounds, interfaces.intersectsForeground(presentationBounds), resolution.collisionMask);
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

		public Tracker copy() {
			final Tracker copy = new Tracker();
			for (Map.Entry<InterfaceBounds.ObstacleKey, Contact> entry : contacts.entrySet()) {
				copy.contacts.put(entry.getKey(), entry.getValue().copy());
			}

			copy.previousDesired = previousDesired != null ? new Rectangle(previousDesired) : null;
			return copy;
		}

		public void replaceWith(Tracker source) {
			contacts.clear();
			previousDesired = null;
			if (source == null) {
				return;
			}

			for (Map.Entry<InterfaceBounds.ObstacleKey, Contact> entry : source.contacts.entrySet()) {
				contacts.put(entry.getKey(), entry.getValue().copy());
			}

			previousDesired = source.previousDesired != null ? new Rectangle(source.previousDesired) : null;
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
				/* Preserve the entry edge until contact ends. Reclassifying at the
				 * minimum size can make a moving obstacle reverse the resize axis. */
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

			final Axis horizontal = enforceMinimum(desired.x, desired.x + desired.width, left, right, MIN_WIDTH, collisionMask & COLLIDE_HORIZONTAL, COLLIDE_LEFT, COLLIDE_RIGHT);
			final Axis vertical = enforceMinimum(desired.y, desired.y + desired.height, top, bottom, MIN_HEIGHT, collisionMask & COLLIDE_VERTICAL, COLLIDE_TOP, COLLIDE_BOTTOM);
			final Rectangle trackedBounds = new Rectangle(horizontal.start, vertical.start, Math.max(0, horizontal.end - horizontal.start), Math.max(0, vertical.end - vertical.start));
			return new Resolution(trackedBounds, collisionMask);
		}
	}

	/**
	 * ================================================================
	 * COLLISION HELPERS
	 * ================================================================
	 */
	private static int detectEntrySides(Rectangle previousDesired, Rectangle previousObstacle, Rectangle desired, Rectangle obstacle) {
		/* Prefer the currently covered boundary over an earlier trajectory;
		 * movable rows can cross the box between geometry samples. */
		final int boundary = currentBoundaryContact(desired, obstacle);
		if (Integer.bitCount(boundary) == 1) {
			return boundary;
		}

		if (previousDesired != null && previousObstacle != null && !previousDesired.intersects(previousObstacle)) {
			int entry = 0;
			final int previousRight = previousDesired.x + previousDesired.width;
			final int previousBottom = previousDesired.y + previousDesired.height;
			final int obstacleRight = previousObstacle.x + previousObstacle.width;
			final int obstacleBottom = previousObstacle.y + previousObstacle.height;
			if (obstacleRight <= previousDesired.x) {
				entry |= COLLIDE_LEFT;
			}
			if (previousObstacle.x >= previousRight) {
				entry |= COLLIDE_RIGHT;
			}
			if (obstacleBottom <= previousDesired.y) {
				entry |= COLLIDE_TOP;
			}
			if (previousObstacle.y >= previousBottom) {
				entry |= COLLIDE_BOTTOM;
			}
			/* A previous entry is valid only when consistent with the current
			 * boundary; fully internal overlaps retain their entry direction. */
			final int supported = boundary != 0 ? entry & boundary : entry;
			if (supported != 0) {
				return preferredTravelSide(desired, previousObstacle, obstacle, supported);
			}
		}

		if (previousObstacle != null) {
			final int travelSide = travelDirectionSide(previousObstacle, obstacle);
			final int allowed = boundary != 0 ? boundary : COLLIDE_ALL;
			if ((travelSide & allowed) != 0) {
				return travelSide;
			}
		}
		return bestSingleSide(desired, obstacle, boundary != 0 ? boundary : COLLIDE_ALL);
	}

	private static int currentBoundaryContact(Rectangle desired, Rectangle obstacle) {
		int mask = 0;
		final int desiredRight = desired.x + desired.width;
		final int desiredBottom = desired.y + desired.height;
		final int obstacleRight = obstacle.x + obstacle.width;
		final int obstacleBottom = obstacle.y + obstacle.height;
		if (obstacle.x <= desired.x && obstacleRight > desired.x) {
			mask |= COLLIDE_LEFT;
		}
		if (obstacle.x < desiredRight && obstacleRight >= desiredRight) {
			mask |= COLLIDE_RIGHT;
		}
		if (obstacle.y <= desired.y && obstacleBottom > desired.y) {
			mask |= COLLIDE_TOP;
		}
		if (obstacle.y < desiredBottom && obstacleBottom >= desiredBottom) {
			mask |= COLLIDE_BOTTOM;
		}
		return mask;
	}

	private static int preferredTravelSide(Rectangle desired, Rectangle previous, Rectangle current, int candidates) {
		if (Integer.bitCount(candidates) == 1) {
			return candidates;
		}
		final int trajectory = travelDirectionSide(previous, current);
		return (candidates & trajectory) != 0 ? trajectory : bestSingleSide(desired, current, candidates);
	}

	private static int travelDirectionSide(Rectangle previous, Rectangle current) {
		final int dx = current.x - previous.x;
		final int dy = current.y - previous.y;
		if (Math.abs(dx) > Math.abs(dy) && dx != 0) {
			return dx > 0 ? COLLIDE_LEFT : COLLIDE_RIGHT;
		}
		if (dy != 0) {
			return dy > 0 ? COLLIDE_TOP : COLLIDE_BOTTOM;
		}
		return 0;
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

			if (bestSide == 0 || valid && !bestValid || valid == bestValid && remainingArea > bestArea || valid == bestValid && remainingArea == bestArea && loss < bestLoss) {
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

	/* A single collision preserves its opposite edge; two-sided contacts
	 * retain the minimum area around the original center. */
	private static Axis enforceMinimum(int desiredStart, int desiredEnd, int constrainedStart, int constrainedEnd, int minimum, int collisionMask, int startCollision, int endCollision) {
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

		private Contact copy() {
			final Contact copy = new Contact();
			copy.previousBounds = previousBounds != null ? new Rectangle(previousBounds) : null;
			copy.collisionMask = collisionMask;
			return copy;
		}
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
		private final int collisionMask;

		private Result(Rectangle desiredBounds, Rectangle effectiveBounds, boolean foregroundOverlap, int collisionMask) {
			this.desiredBounds = new Rectangle(desiredBounds);
			this.effectiveBounds = new Rectangle(effectiveBounds);
			this.foregroundOverlap = foregroundOverlap;
			this.collisionMask = collisionMask;
		}

		private static Result unbounded(int width, int height) {
			final Rectangle bounds = new Rectangle(0, 0, width, height);
			return new Result(bounds, bounds, false, 0);
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

		public boolean isLeftCollision() {
			return (collisionMask & COLLIDE_LEFT) != 0;
		}

		public boolean isRightCollision() {
			return (collisionMask & COLLIDE_RIGHT) != 0;
		}

		public boolean isTopCollision() {
			return (collisionMask & COLLIDE_TOP) != 0;
		}

		public boolean isBottomCollision() {
			return (collisionMask & COLLIDE_BOTTOM) != 0;
		}
	}
}
