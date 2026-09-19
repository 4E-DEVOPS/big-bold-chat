package com.bigboldchat.config;

import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;

/**
 * Owns chat-font configuration changes.
 */
public final class FontConfigHandler {
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String FONT_KEY = "chatFont";

	private final Client client;
	private final ClientThread clientThread;
	private final FontLayoutService layoutService;
	private final PerformanceMetrics performanceMetrics;

	private volatile boolean active = true;

	public FontConfigHandler(
			Client client,
			ClientThread clientThread,
			FontLayoutService layoutService,
			PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.clientThread = clientThread;
		this.layoutService = layoutService;
		this.performanceMetrics = performanceMetrics;
	}

	public boolean onConfigChanged(ConfigChanged event) {
		if (!active || event == null || !CONFIG_GROUP.equals(event.getGroup()) || !FONT_KEY.equals(event.getKey())) {
			return false;
		}

		if (layoutService != null) {
			layoutService.refreshActiveFontState();
			layoutService.reset();
		}

		if (performanceMetrics != null) {
			performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.FONT_CHANGED);
		}

		clientThread.invokeLater(() -> {
			if (active) {
				client.refreshChat();
			}
		});

		return true;
	}

	public void deactivate() {
		active = false;
	}
}
