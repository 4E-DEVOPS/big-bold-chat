package com.bigboldchat.chatbox;

/**
 * Native chatbox geometry shared by resize and restore paths.
 *
 * The resizable chat slot includes the 23-pixel
 * control/tab band beneath the 142-pixel chat body.
 */
public final class ChatboxGeometry {
	public static final int NATIVE_WIDTH = 519;
	public static final int NATIVE_BODY_HEIGHT = 142;
	public static final int NATIVE_SLOT_HEIGHT = 165;
	public static final int NATIVE_TAB_HEIGHT = NATIVE_SLOT_HEIGHT - NATIVE_BODY_HEIGHT;

	private ChatboxGeometry() {
		/*
		 * CHATBOX GEOMETRY
		 */
	}

	public static int bodyHeight(int slotHeight) {
		return Math.max(0, slotHeight - NATIVE_TAB_HEIGHT);
	}

	public static int bodyHeight(int slotHeight, boolean buttonsHidden) {
		return buttonsHidden
				? Math.max(0, slotHeight)
				: bodyHeight(slotHeight);
	}
}
