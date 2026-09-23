package com.bigboldchat.layout;

import java.awt.Rectangle;

import net.runelite.api.Client;

/*
 * Resolves split-PM width against the interfaces that overlap its own
 * screen position. The configured/inherited width remains the maximum.
 */
final class PrivateChatBounds {
	private static final int MIN_WIDTH = 200;

	private PrivateChatBounds() {
		/*
		 * PRIVATE CHAT BOUNDS
		 */
	}

	static int resolveWidth(Client client, Rectangle surfaceBounds, int desiredWidth) {
		if (client == null || surfaceBounds == null || desiredWidth <= 0) {
			return Math.max(MIN_WIDTH, desiredWidth);
		}

		final InterfaceBounds interfaces = InterfaceBounds.capture(client);
		final Rectangle canvas = interfaces.getCanvas();
		final int anchorX = surfaceBounds.x;
		final int availableWidth = canvas.x + canvas.width - anchorX;
		int effectiveWidth = constrain(desiredWidth, availableWidth);

		/*
		 * This prototype preserves the PM surface's left edge and constrains only
		 * against interfaces to its right. Left-edge preservation is deliberate:
		 * the general symmetric movable-geometry phase will add opposite-edge
		 * ownership for left-side collisions without duplicating that solver here.
		 */
		for (Rectangle obstacle : interfaces.getObstacles()) {
			if (!intersectsVertically(surfaceBounds, obstacle)
					|| obstacle.x <= anchorX
					|| obstacle.x >= anchorX + effectiveWidth) {
				continue;
			}

			effectiveWidth = Math.max(MIN_WIDTH, obstacle.x - anchorX);
		}

		return effectiveWidth;
	}

	private static boolean intersectsVertically(Rectangle surfaceBounds, Rectangle obstacle) {
		final int surfaceHeight = Math.max(1, surfaceBounds.height);
		final int surfaceBottom = surfaceBounds.y + surfaceHeight;
		final int obstacleBottom = obstacle.y + obstacle.height;
		return surfaceBounds.y < obstacleBottom && surfaceBottom > obstacle.y;
	}

	private static int constrain(int desiredWidth, int availableWidth) {
		if (availableWidth <= 0) {
			return Math.max(MIN_WIDTH, desiredWidth);
		}

		return Math.max(MIN_WIDTH, Math.min(desiredWidth, availableWidth));
	}
}
