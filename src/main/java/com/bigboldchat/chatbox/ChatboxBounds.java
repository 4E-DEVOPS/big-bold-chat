package com.bigboldchat.chatbox;

/**
 * Resolves configured chatbox dimensions against the current canvas.
 */
public final class ChatboxBounds {
	private ChatboxBounds() {
		/*
		 * CHATBOX BOUNDS
		 */
	}

	public static int effectiveWidth(int configuredWidth, int canvasWidth) {
		return clamp(configuredWidth, canvasWidth);
	}

	public static int effectiveHeight(int configuredHeight, int canvasHeight) {
		return clamp(configuredHeight, canvasHeight);
	}

	private static int clamp(int configuredSize, int availableSize) {
		if (availableSize <= 0) {
			return configuredSize;
		}

		return Math.min(configuredSize, availableSize);
	}
}
