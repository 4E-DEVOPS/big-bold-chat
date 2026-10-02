package com.bigboldchat.layout;

import java.awt.Rectangle;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;

/**
 * Resolves Modern side-row collision direction without mutating layout state.
 */
public final class CollisionManager {
	private final Client client;

	public CollisionManager(Client client) {
		this.client = client;
	}

	/*
	 * Determines whether the connected Modern tab strip must split and which side is obstructed.
	 */
	public Result resolveSideRows(Rectangle chatboxBounds, Rectangle staticRow, Rectangle movableRow, Side currentSide) {
		if (client == null || client.getTopLevelInterfaceId() != InterfaceID.TOPLEVEL_PRE_EOC || staticRow == null || movableRow == null) {
			return Result.NONE;
		}

		final Rectangle strip = new Rectangle(staticRow);
		strip.add(movableRow);
		if (strip.isEmpty()) {
			return Result.NONE;
		}

		Candidate best = candidate(Source.CHATBOX, chatboxBounds, staticRow, movableRow, strip, currentSide);
		final InterfaceBounds interfaces = InterfaceBounds.capture(client);
		for (InterfaceBounds.Obstacle obstacle : interfaces.getIdentifiedObstacles()) {
			final Source source = sourceFor(obstacle.getKey());
			if (source == Source.NONE) {
				continue;
			}

			best = prefer(best, candidate(source, obstacle.getBounds(), staticRow, movableRow, strip, currentSide), currentSide);
		}

		return best != null
				? new Result(best.side, best.source)
				: Result.NONE;
	}

	private static Candidate candidate(Source source, Rectangle bounds, Rectangle staticRow, Rectangle movableRow, Rectangle strip, Side currentSide) {
		if (bounds == null || bounds.isEmpty() || !bounds.intersects(strip)) {
			return null;
		}

		final long staticArea = intersectionArea(bounds, staticRow);
		final long movableArea = intersectionArea(bounds, movableRow);
		final Side side;

		if (staticArea > 0L && movableArea == 0L) {
			side = Side.RIGHT;
		} else if (movableArea > 0L && staticArea == 0L) {
			side = Side.LEFT;
		} else if (staticArea != movableArea) {
			side = staticArea > movableArea
					? Side.RIGHT
					: Side.LEFT;
		} else if (currentSide != null && currentSide != Side.NONE) {
			/*
			 * Balanced overlap preserves the current split side.
			 */
			side = currentSide;
		} else {
			final long boundsCenter2 = (long) bounds.x * 2L + bounds.width;
			final long stripCenter2 = (long) strip.x * 2L + strip.width;
			side = boundsCenter2 >= stripCenter2
					? Side.RIGHT
					: Side.LEFT;
		}

		return new Candidate(side, source, intersectionArea(bounds, strip));
	}

	private static Candidate prefer(Candidate current, Candidate next, Side currentSide) {
		if (next == null) {
			return current;
		}

		if (current == null) {
			return next;
		}

		/*
		 * The current split side wins when multiple collisions compete.
		 */
		if (currentSide != null && currentSide != Side.NONE) {
			final boolean currentMatches = current.side == currentSide;
			final boolean nextMatches = next.side == currentSide;
			if (currentMatches != nextMatches) {
				return nextMatches
						? next
						: current;
			}
		}

		if (next.score != current.score) {
			return next.score > current.score
					? next
					: current;
		}

		return next.source.priority < current.source.priority
				? next
				: current;
	}

	private static long intersectionArea(Rectangle first, Rectangle second) {
		if (first == null || second == null) {
			return 0L;
		}

		final Rectangle intersection = first.intersection(second);
		return intersection.isEmpty()
				? 0L
				: (long) intersection.width * intersection.height;
	}

	private static Source sourceFor(InterfaceBounds.ObstacleKey key) {
		if (key == null) {
			return Source.NONE;
		}

		/*
		 * Only map and orb geometry can trigger an interface-driven row split.
		 */
		switch (key) {
			case MODERN_MAP:
				return Source.MAP;
			case MODERN_ORBS:
				return Source.ORBS;
			default:
				return Source.NONE;
		}
	}

	public enum Side {
		NONE,
		LEFT,
		RIGHT
	}

	public enum Source {
		NONE(99),
		CHATBOX(0),
		MAP(1),
		ORBS(2);

		private final int priority;

		Source(int priority) {
			this.priority = priority;
		}
	}

	public static final class Result {
		private static final Result NONE = new Result(Side.NONE, Source.NONE);

		private final Side side;
		private final Source source;

		private Result(Side side, Source source) {
			this.side = side != null ? side : Side.NONE;
			this.source = source != null ? source : Source.NONE;
		}

		public Side getSide() {
			return side;
		}

		public Source getSource() {
			return source;
		}

		public boolean needsTwoRows() {
			return side != Side.NONE;
		}
	}

	private static final class Candidate {
		private final Side side;
		private final Source source;
		private final long score;

		private Candidate(Side side, Source source, long score) {
			this.side = side;
			this.source = source;
			this.score = score;
		}
	}
}
