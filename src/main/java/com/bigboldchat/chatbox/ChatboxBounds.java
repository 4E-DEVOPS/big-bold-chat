package com.bigboldchat.chatbox;

import java.awt.Rectangle;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Resolves configured chatbox dimensions against the active layout bounds.
 */
public final class ChatboxBounds {
	private ChatboxBounds() {
		/*
		 * CHATBOX BOUNDS
		 */
	}

	public static int effectiveWidth(Client client, ChatboxLayout layout, Widget slot, int configuredWidth) {
		if (client == null) {
			return configuredWidth;
		}

		final int canvasWidth = client.getCanvasWidth();
		final int availableWidth = sideBoundWidth(client, layout, slot, canvasWidth);
		return clamp(configuredWidth, availableWidth);
	}

	public static int effectiveHeight(Client client, int configuredHeight) {
		if (client == null) {
			return configuredHeight;
		}

		return clamp(configuredHeight, client.getCanvasHeight());
	}

	private static int sideBoundWidth(Client client, ChatboxLayout layout, Widget slot, int canvasWidth) {
		if (slot == null) {
			return canvasWidth;
		}

		final Widget side = getSideContainer(client, layout);
		if (side == null) {
			return canvasWidth;
		}

		final Rectangle slotBounds = slot.getBounds();
		final Rectangle sideBounds = side.getBounds();
		final int width = sideBounds.x - slotBounds.x;
		if (width < ChatboxGeometry.NATIVE_WIDTH) {
			return canvasWidth;
		}

		return Math.min(canvasWidth, width);
	}

	private static Widget getSideContainer(Client client, ChatboxLayout layout) {
		switch (layout) {
			case RESIZABLE_CLASSIC:
				return client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
			case RESIZABLE_MODERN:
				return client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);
			default:
				return null;
		}
	}

	private static int clamp(int configuredSize, int availableSize) {
		if (availableSize <= 0) {
			return configuredSize;
		}

		return Math.min(configuredSize, availableSize);
	}
}
