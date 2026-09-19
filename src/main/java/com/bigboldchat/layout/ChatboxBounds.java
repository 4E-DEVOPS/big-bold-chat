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
	private ChatboxBounds() {
		/*
		 * CHATBOX BOUNDS
		 */
	}

	public static Result resolve(Client client, Widget slot, int configuredWidth, int configuredHeight) {
		if (client == null || slot == null) {
			return new Result(configuredWidth, configuredHeight);
		}

		final InterfaceBounds interfaces = InterfaceBounds.capture(client);
		final Rectangle canvas = interfaces.getCanvas();
		final Rectangle slotBounds = slot.getBounds();
		if (slotBounds == null) {
			return new Result(configuredWidth, configuredHeight);
		}

		final int anchorX = slotBounds.x;
		final int anchorBottom = slotBounds.y + slotBounds.height;
		final int availableWidth = canvas.x + canvas.width - anchorX;
		final int availableHeight = anchorBottom - canvas.y;
		final int desiredWidth = constrain(configuredWidth, availableWidth, ChatboxGeometry.NATIVE_WIDTH);
		final int desiredHeight = constrain(configuredHeight, availableHeight, ChatboxGeometry.NATIVE_SLOT_HEIGHT);

		return fit(anchorX, anchorBottom, desiredWidth, desiredHeight, canvas, interfaces.getObstacles());
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
					best = new Result(width, height);
					bestArea = area;
				}
			}
		}

		if (best != null) {
			return best;
		}

		/*
		 * Never shrink below RuneScape's native chatbox footprint. If a player
		 * moves another interface into that footprint, overlap is unavoidable.
		 */
		return new Result(
				Math.min(desiredWidth, ChatboxGeometry.NATIVE_WIDTH),
				Math.min(desiredHeight, ChatboxGeometry.NATIVE_SLOT_HEIGHT));
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

		private Result(int width, int height) {
			this.width = width;
			this.height = height;
		}

		public int getWidth() {
			return width;
		}

		public int getHeight() {
			return height;
		}
	}
}
