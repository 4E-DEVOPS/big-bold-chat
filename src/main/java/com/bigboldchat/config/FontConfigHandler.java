package com.bigboldchat.config;

import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.chat.InputFontService;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;

/**
 * Owns font configuration changes.
 */
public final class FontConfigHandler {
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String CHAT_FONT_KEY = "chatFont";
	private static final String INPUT_FONT_KEY = "inputFont";

	private final Client client;
	private final ClientThread clientThread;
	private final FontLayoutService layoutService;
	private final InputFontService inputFontService;
	private final PerformanceMetrics performanceMetrics;

	private volatile boolean active = true;

	public FontConfigHandler(Client client, ClientThread clientThread, FontLayoutService layoutService,
			InputFontService inputFontService, PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.clientThread = clientThread;
		this.layoutService = layoutService;
		this.inputFontService = inputFontService;
		this.performanceMetrics = performanceMetrics;
	}

	public boolean onConfigChanged(ConfigChanged event) {
		if (!active || event == null || !CONFIG_GROUP.equals(event.getGroup())) {
			return false;
		}

		if (CHAT_FONT_KEY.equals(event.getKey())) {
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
					if (inputFontService != null) {
						inputFontService.sync();
					}
				}
			});

			return true;
		}

		if (INPUT_FONT_KEY.equals(event.getKey())) {
			clientThread.invokeLater(() -> {
				if (!active || inputFontService == null) {
					return;
				}

				inputFontService.sync();

				if (performanceMetrics != null) {
					performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.FONT_CHANGED);
				}

				client.refreshChat();
			});

			return true;
		}

		return false;
	}

	public void deactivate() {
		active = false;
	}
}
