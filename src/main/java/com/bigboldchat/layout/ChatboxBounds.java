package com.bigboldchat.layout;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import com.bigboldchat.chatbox.ChatboxGeometry;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * Resolves configured chatbox dimensions against visible interface bounds.
 */
public final class ChatboxBounds {
	private static final int MIN_WIDTH = 200;
	private static final int MIN_HEIGHT = 100;

	private ChatboxBounds() {
		/*
		 * CHATBOX BOUNDS
		 */
	}

	public static Result resolve(Client client, Widget slot, int configuredWidth, int configuredHeight) {
		return resolve(client, slot, configuredWidth, configuredHeight, false);
	}

	public static Result resolve(
			Client client,
			Widget slot,
			int configuredWidth,
			int configuredHeight,
			boolean buttonsHidden) {
		if (client == null || slot == null) {
			return new Result(configuredWidth, configuredHeight, false, false);
		}

		final InterfaceBounds interfaces = InterfaceBounds.capture(client);
		final Rectangle canvas = interfaces.getCanvas();
		final Rectangle slotBounds = slot.getBounds();
		if (slotBounds == null) {
			return new Result(configuredWidth, configuredHeight, false, false);
		}

		final int anchorX = slotBounds.x;
		final int anchorBottom = slotBounds.y + slotBounds.height;
		final int availableWidth = canvas.x + canvas.width - anchorX;
		final int availableHeight = anchorBottom - canvas.y;
		final int desiredWidth = constrain(configuredWidth, availableWidth, MIN_WIDTH);
		final int desiredHeight = constrain(configuredHeight, availableHeight, MIN_HEIGHT);

		final Result fitted = fit(
				anchorX, anchorBottom, desiredWidth, desiredHeight, canvas, interfaces.getObstacles());
		final Rectangle presentationBounds = new Rectangle(
				anchorX,
				anchorBottom - fitted.height,
				fitted.width,
				ChatboxGeometry.bodyHeight(fitted.height, buttonsHidden));

		return new Result(
				fitted.width, fitted.height, interfaces.intersectsForeground(presentationBounds), fitted.fallback);
	}

	private static Result fit(
			int anchorX,
			int anchorBottom,
			int desiredWidth,
			int desiredHeight,
			Rectangle canvas,
			List<Rectangle> obstacles) {
		final List<Integer> widths = new ArrayList<>();
		final List<Integer> heights = new ArrayList<>();

		addCandidate(widths, desiredWidth, ChatboxGeometry.NATIVE_WIDTH, desiredWidth);
		addCandidate(widths, ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_WIDTH, desiredWidth);
		addCandidate(heights, desiredHeight, ChatboxGeometry.NATIVE_SLOT_HEIGHT, desiredHeight);
		addCandidate(heights, ChatboxGeometry.NATIVE_SLOT_HEIGHT, ChatboxGeometry.NATIVE_SLOT_HEIGHT, desiredHeight);

		for (Rectangle obstacle : obstacles) {
			addCandidate(widths, obstacle.x - anchorX, ChatboxGeometry.NATIVE_WIDTH, desiredWidth);
			addCandidate(
					heights, anchorBottom - (obstacle.y + obstacle.height),
					ChatboxGeometry.NATIVE_SLOT_HEIGHT, desiredHeight);
		}

		Result best = null;
		long bestArea = -1L;
		for (int width : widths) {
			for (int height : heights) {
				final Rectangle candidate = new Rectangle(anchorX, anchorBottom - height, width, height);
				if (!fits(candidate, canvas, obstacles)) {
					continue;
				}

				final long area = (long) width * height;
				if (best == null
						|| area > bestArea
						|| area == bestArea && width > best.width) {
					best = new Result(width, height, false, false);
					bestArea = area;
				}
			}
		}

		if (best != null) {
			return best;
		}

		/*
		 * Never shrink both axes just because another interface enters RuneScape's
		 * native chatbox footprint. Preserve the axis that would require the larger
		 * proportional reduction and accept overlap on the constrained axis.
		 */
		return fallback(anchorX, anchorBottom, desiredWidth, desiredHeight, obstacles);
	}

	private static Result fallback(
			int anchorX,
			int anchorBottom,
			int desiredWidth,
			int desiredHeight,
			List<Rectangle> obstacles) {
		final Rectangle desired = new Rectangle(
				anchorX,
				anchorBottom - desiredHeight,
				desiredWidth,
				desiredHeight);
		int widthLoss = 0;
		int heightLoss = 0;

		for (Rectangle obstacle : obstacles) {
			if (!desired.intersects(obstacle)) {
				continue;
			}

			final int widthLimit = obstacle.x - anchorX;
			final int heightLimit = anchorBottom - (obstacle.y + obstacle.height);
			widthLoss = Math.max(widthLoss, requiredLoss(desiredWidth, widthLimit));
			heightLoss = Math.max(heightLoss, requiredLoss(desiredHeight, heightLimit));
		}

		if ((long) heightLoss * desiredWidth <= (long) widthLoss * desiredHeight) {
			return new Result(desiredWidth, Math.min(desiredHeight, ChatboxGeometry.NATIVE_SLOT_HEIGHT), false, true);
		}

		return new Result(Math.min(desiredWidth, ChatboxGeometry.NATIVE_WIDTH), desiredHeight, false, true);
	}

	private static int requiredLoss(int desiredSize, int limit) {
		if (limit >= desiredSize) {
			return 0;
		}

		if (limit <= 0) {
			return desiredSize;
		}

		return desiredSize - limit;
	}

	private static boolean fits(Rectangle candidate, Rectangle canvas, List<Rectangle> obstacles) {
		if (!canvas.contains(candidate)) {
			return false;
		}

		for (Rectangle obstacle : obstacles) {
			if (candidate.intersects(obstacle)) {
				return false;
			}
		}

		return true;
	}

	private static void addCandidate(List<Integer> candidates, int value, int minimum, int maximum) {
		if (value < minimum || value > maximum || candidates.contains(value)) {
			return;
		}

		candidates.add(value);
	}

	private static int constrain(int configuredSize, int availableSize, int minimumSize) {
		if (availableSize <= 0) {
			return configuredSize;
		}

		return Math.max(minimumSize, Math.min(configuredSize, availableSize));
	}

	public static final class Result {
		private final int width;
		private final int height;
		private final boolean foregroundOverlap;
		private final boolean fallback;

		private Result(int width, int height, boolean foregroundOverlap, boolean fallback) {
			this.width = width;
			this.height = height;
			this.foregroundOverlap = foregroundOverlap;
			this.fallback = fallback;
		}

		public int getWidth() {
			return width;
		}

		public int getHeight() {
			return height;
		}

		public boolean isForegroundOverlap() {
			return foregroundOverlap;
		}

		public boolean isFallback() {
			return fallback;
		}
	}
}
