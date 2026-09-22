package com.bigboldchat.config;

import com.bigboldchat.chatbox.ChatRebuildCoordinator;

import net.runelite.client.events.ConfigChanged;

/**
 * Owns chatbox configuration changes.
 */
public final class ChatboxConfigHandler {
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String WIDTH_KEY = "chatboxWidth";
	private static final String HEIGHT_KEY = "chatboxHeight";
	private static final String BUTTONS_KEY = "hideChatboxButtons";

	private final ChatRebuildCoordinator rebuildCoordinator;

	private volatile boolean active = true;

	public ChatboxConfigHandler(ChatRebuildCoordinator rebuildCoordinator) {
		this.rebuildCoordinator = rebuildCoordinator;
	}

	public boolean onConfigChanged(ConfigChanged event) {
		if (!active || event == null || !CONFIG_GROUP.equals(event.getGroup())) {
			return false;
		}

		if (BUTTONS_KEY.equals(event.getKey())) {
			if (rebuildCoordinator != null) {
				rebuildCoordinator.onButtonsChanged();
			}
			return true;
		}

		if (!WIDTH_KEY.equals(event.getKey()) && !HEIGHT_KEY.equals(event.getKey())) {
			return false;
		}

		if (rebuildCoordinator != null) {
			rebuildCoordinator.onConfiguredSizeChanged();
		}

		return true;
	}

	public void deactivate() {
		active = false;
	}
}
