package com.bigboldchat;

import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.chat.FontMeasurementService;
import com.bigboldchat.chatbox.ChatboxResizeService;
import com.bigboldchat.config.ChatboxConfigHandler;
import com.bigboldchat.config.FontConfigHandler;
import com.bigboldchat.debug.DebugManager;
import com.bigboldchat.debug.PerformanceMetrics;

import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.events.CanvasSizeChanged;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
		name = "Chat XL",
		description = "Resize the chatbox and its text for improved readability.<br>"
				+ "[A.K.A. BBC - Big Bold Chat]",
		tags = {
				"1877",
				"accessibility",
				"bbc",
				"big",
				"bigger",
				"bold",
				"chat",
				"chatbox",
				"classic",
				"fixed",
				"font",
				"fonts",
				"large",
				"magnifier",
				"magnify",
				"messages",
				"modern",
				"pm",
				"private",
				"readability",
				"resizable",
				"resize",
				"resizer",
				"resizing",
				"scale",
				"size",
				"small",
				"text",
				"zoom"
		},
		enabledByDefault = true
)
public class ChatXL extends Plugin {

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private Configurations config;
	@Inject
	private DebugManager debugManager;

	/*
	 * Diagnostics & performance.
	 */
	private PerformanceMetrics performanceMetrics;

	/*
	 * Production services.
	 */
	private FontMeasurementService fontMeasurementService;
	private FontLayoutService fontLayoutService;
	private ChatboxResizeService chatboxResizeService;

	/*
	 * Configuration handlers.
	 */
	private ChatboxConfigHandler chatboxConfigHandler;
	private FontConfigHandler fontConfigHandler;

	@Override
	protected void startUp() {
		performanceMetrics = debugManager.activate();
		chatboxResizeService = new ChatboxResizeService(client, performanceMetrics);
		fontMeasurementService = new FontMeasurementService(client, performanceMetrics);
		fontLayoutService = new FontLayoutService(client, config, fontMeasurementService, performanceMetrics);
		chatboxConfigHandler = new ChatboxConfigHandler(client, clientThread, config, chatboxResizeService, performanceMetrics);
		fontConfigHandler = new FontConfigHandler(client, clientThread, fontLayoutService, performanceMetrics);
		performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.STARTUP);

		/*
		 * Refresh retained rows through the active layout pipeline.
		 */
		clientThread.invokeLater(() -> {
			if (chatboxConfigHandler != null) {
				chatboxConfigHandler.applyConfiguredSize();
			}

			client.refreshChat();

			debugManager.onLoggedIn();
		});

		log.debug("[Chat XL] Plugin Initiated.");
	}

	@Override
	protected void shutDown() {
		final FontLayoutService shutdownLayoutService = fontLayoutService;
		final ChatboxResizeService shutdownResizeService = chatboxResizeService;
		final PerformanceMetrics shutdownPerformanceMetrics = performanceMetrics;
		final boolean uninstalling = debugManager.prepareShutdown();

		/*
		 * Disable PRE / POST and config handling before restoration.
		 */
		if (chatboxConfigHandler != null) {
			chatboxConfigHandler.deactivate();
		}

		if (fontConfigHandler != null) {
			fontConfigHandler.deactivate();
		}

		fontLayoutService = null;
		chatboxResizeService = null;
		fontMeasurementService = null;
		chatboxConfigHandler = null;
		fontConfigHandler = null;

		debugManager.deactivate();

		if (shutdownPerformanceMetrics != null) {
			shutdownPerformanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.SHUTDOWN);
		}

		// Restore native presentation on the client thread.
		clientThread.invokeLater(() -> {
			if (shutdownResizeService != null) {
				shutdownResizeService.restoreNativeSize();
			}

			if (shutdownLayoutService != null) {
				shutdownLayoutService.restoreNativePresentation();
				shutdownLayoutService.reset();
			}

			client.refreshChat();

			debugManager.finishShutdown(uninstalling);

			if (shutdownPerformanceMetrics != null) {
				shutdownPerformanceMetrics.reportNow();
			}
		});

		performanceMetrics = null;

		log.debug("[Chat XL] Plugin Terminated.");
	}

	/*
	 * ================================================================
	 * GAME STATE
	 * ================================================================
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event == null || event.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		if (chatboxConfigHandler != null) {
			chatboxConfigHandler.applyConfiguredSize();
		}

		debugManager.onLoggedIn();
	}

	/*
	 * ================================================================
	 * CANVAS SIZE
	 * ================================================================
	 */
	@Subscribe
	public void onCanvasSizeChanged(CanvasSizeChanged event) {
		if (event == null) {
			return;
		}

		if (chatboxConfigHandler != null) {
			chatboxConfigHandler.onCanvasSizeChanged();
		}
	}

	/*
	 * ================================================================
	 * CHAT COMMANDS
	 * ================================================================
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event) {
		if (event == null) {
			return;
		}

		final String command = event.getCommand();
		if ("clear".equalsIgnoreCase(command) || "cls".equalsIgnoreCase(command)) {
			clientThread.invokeLater(this::clearChatHistory);
			return;
		}

		debugManager.onCommandExecuted(event);
	}

	private void clearChatHistory() {
		boolean removed = false;

		for (ChatMessageType messageType : ChatMessageType.values()) {
			final ChatLineBuffer lineBuffer = client.getChatLineMap().get(messageType.getType());
			if (lineBuffer == null) {
				continue;
			}

			final MessageNode[] lines = lineBuffer.getLines().clone();

			for (MessageNode line : lines) {
				if (line == null) {
					continue;
				}

				lineBuffer.removeMessageNode(line);
				removed = true;
			}
		}

		if (removed) {
			client.refreshChat();
		}
	}

	/*
	 * ================================================================
	 * CONFIGURATION
	 * ================================================================
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		debugManager.onConfigChanged(event);

		if (chatboxConfigHandler != null && chatboxConfigHandler.onConfigChanged(event)) {
			return;
		}

		if (fontConfigHandler != null) {
			fontConfigHandler.onConfigChanged(event);
		}
	}

	/*
	 * ================================================================
	 * CONFIGURATION PROVIDER
	 * ================================================================
	 */
	@Provides
	Configurations provideConfig(ConfigManager configManager) {
		return configManager.getConfig(Configurations.class);
	}

	/*
	 * ================================================================
	 * CHAT CONSTRUCTION
	 * ================================================================
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		debugManager.onChatboxScriptPreFired(event);

		final ChatboxResizeService.ResizeResult resizeResult = chatboxResizeService != null
				? chatboxResizeService.onScriptPreFired(event, config.chatboxWidth(), config.chatboxHeight())
				: null;
		if (chatboxConfigHandler != null) {
			chatboxConfigHandler.onLayoutChanged(resizeResult);
		}

		debugManager.onFontScriptPreFired(event);

		if (fontLayoutService == null) {
			return;
		}

		final boolean metricsEnabled = performanceMetrics != null && performanceMetrics.isEnabled();

		final long started = metricsEnabled
				? System.nanoTime()
				: 0L;

		fontLayoutService.onScriptPreFired(event);

		if (metricsEnabled && event != null) {
			performanceMetrics.recordPre(
					event.getScriptId(),
					System.nanoTime() - started);
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		debugManager.onChatboxScriptPostFired(event);

		if (fontLayoutService != null) {
			final boolean metricsEnabled = performanceMetrics != null && performanceMetrics.isEnabled();

			final long started = metricsEnabled
					? System.nanoTime()
					: 0L;

			fontLayoutService.onScriptPostFired(event);

			if (metricsEnabled && event != null) {
				performanceMetrics.recordPost(
						event.getScriptId(),
						System.nanoTime() - started);
			}
		}

		final ChatboxResizeService.ResizeResult resizeResult = chatboxResizeService != null
				? chatboxResizeService.onScriptPostFired(event, config.chatboxWidth(), config.chatboxHeight())
				: null;
		if (chatboxConfigHandler != null) {
			chatboxConfigHandler.onLayoutChanged(resizeResult);
		}

		debugManager.onFontScriptPostFired(event);
		debugManager.reportPerformanceIfDue();
	}
}