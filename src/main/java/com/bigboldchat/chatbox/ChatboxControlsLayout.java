package com.bigboldchat.chatbox;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;

/**
 * Owns the resizable chat control/tab bar geometry.
 */
public final class ChatboxControlsLayout {
	private static final int TAB_NATIVE_WIDTH = 56;

	private static final int[] TAB_NATIVE_X = {
		458,
		396,
		334,
		272,
		210,
		148,
		86
	};

	private static final int[] TAB_COMPONENTS = {
		InterfaceID.Chatbox.CHAT_ALL,
		InterfaceID.Chatbox.CHAT_GAME,
		InterfaceID.Chatbox.CHAT_PUBLIC,
		InterfaceID.Chatbox.CHAT_PRIVATE,
		InterfaceID.Chatbox.CHAT_FRIENDSCHAT,
		InterfaceID.Chatbox.CHAT_CLAN,
		InterfaceID.Chatbox.CHAT_TRADE
	};

	private final Client client;

	public ChatboxControlsLayout(Client client) {
		this.client = client;
	}

	/**
	 * Apply the control bar width and native-width tab positions.
	 *
	 * @return number of widget fields mutated
	 */
	int apply(int width) {
		int mutations = 0;

		final Widget controls = client.getWidget(InterfaceID.Chatbox.CONTROLS);
		final Widget background = client.getWidget(InterfaceID.Chatbox.CONTROLS_BACKGROUND_GRAPHIC);

		if (controls != null && controls.getOriginalWidth() != width) {
			controls.setOriginalWidth(width);
			mutations++;
		}

		if (background != null && background.getOriginalWidth() != width) {
			background.setOriginalWidth(width);
			mutations++;
		}

		final Widget[] tabs = getReadyTabs();
		if (tabs == null) {
			return mutations;
		}

		final int widthDelta = width - ChatboxGeometry.NATIVE_WIDTH;
		final int tabCount = tabs.length;

		for (int i = 0; i < tabCount; i++) {
			final Widget tab = tabs[i];
			final int targetX = spreadX(i, widthDelta, tabCount);

			if (tab.getOriginalWidth() != TAB_NATIVE_WIDTH) {
				tab.setOriginalWidth(TAB_NATIVE_WIDTH);
				mutations++;
			}

			if (tab.getOriginalX() != targetX) {
				tab.setOriginalX(targetX);
				mutations++;
			}
		}

		return mutations;
	}

	int restoreNative() {
		return apply(ChatboxGeometry.NATIVE_WIDTH);
	}

	boolean matches(int width) {
		final Widget controls = client.getWidget(InterfaceID.Chatbox.CONTROLS);
		final Widget background = client.getWidget(InterfaceID.Chatbox.CONTROLS_BACKGROUND_GRAPHIC);

		if (controls != null && controls.getWidth() != width) {
			return false;
		}

		if (background != null && background.getWidth() != width) {
			return false;
		}

		final Widget[] tabs = getReadyTabs();
		if (tabs == null) {
			return true;
		}

		final int widthDelta = width - ChatboxGeometry.NATIVE_WIDTH;
		final int tabCount = tabs.length;

		for (int i = 0; i < tabCount; i++) {
			final Widget tab = tabs[i];
			if (tab.getOriginalWidth() != TAB_NATIVE_WIDTH || tab.getOriginalX() != spreadX(i, widthDelta, tabCount)) {
				return false;
			}
		}

		return true;
	}

	private Widget[] getReadyTabs() {
		final Widget[] tabs = new Widget[TAB_COMPONENTS.length];

		for (int i = 0; i < TAB_COMPONENTS.length; i++) {
			final Widget tab = client.getWidget(TAB_COMPONENTS[i]);
			if (tab == null || tab.getXPositionMode() != WidgetPositionMode.ABSOLUTE_RIGHT) {
				return null;
			}

			tabs[i] = tab;
		}

		return tabs;
	}

	private static int spreadX(int index, int widthDelta, int tabCount) {
		return TAB_NATIVE_X[index] + widthDelta * (tabCount - index) / tabCount;
	}
}
