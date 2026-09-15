package com.bigboldchat;

import com.bigboldchat.chat.ChatTextNormalizer;
import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.chat.FontMeasurementService;
import com.bigboldchat.debug.ChatDiagnostics;
import com.bigboldchat.debug.PerformanceMetrics;

import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.externalplugins.PluginHubManifest;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
		name = "Chat XL",
		description = "Resize the chatbox and its text for improved readability.<br>"
				+ "[A.K.A. BBC - Big Bold Chat]",
		tags = {"1877", "bbc", "big", "bold", "chat", "chatbox", "text", "font", "fonts", "size", "resize", "resizer", "resizing", "resizable", "magnify", "magnifier", "zoom", "scale", "large", "bigger", "small", "readability", "accessibility", "private", "pm", "messages"},
		enabledByDefault = true
)
public class ChatXL extends Plugin
{
	private static final boolean DIAGNOSTICS_ENABLED = false;
	private static final boolean PERFORMANCE_METRICS = false;

	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String VERSION_CONFIG_KEY = "lastNotifiedVersion";

	private static final String INSTALL_MESSAGE = "Thank you for installing ChatXL! Report any issues you find to Github.";
	private static final String UNINSTALL_MESSAGE = "Please submit a review/issue report on Github of your experience. Thanks!";
	private static final String UPDATE_MESSAGE = "Performance Improvements, Version Update Messages, a `::clear' / `::cls' command to clear chat history, and an emoji fix reported by Ms_Gizzy.";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Configurations config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ExternalPluginManager externalPluginManager;

	/*
	 * DIAGNOSTICS & PERFORMANCE
	 */
	private ChatDiagnostics chatDiagnostics;
	private PerformanceMetrics performanceMetrics;

	/*
	 * Production chat-font pipeline.
	 */
	private ChatTextNormalizer textNormalizer;
	private FontMeasurementService fontMeasurementService;
	private FontLayoutService fontLayoutService;

	@Override
	protected void startUp()
	{
		if (PERFORMANCE_METRICS)
		{
			performanceMetrics = new PerformanceMetrics();
		}

		textNormalizer = new ChatTextNormalizer(performanceMetrics);
		fontMeasurementService = new FontMeasurementService(client, performanceMetrics);
		fontLayoutService = new FontLayoutService(client, config, fontMeasurementService, performanceMetrics);

		if (DIAGNOSTICS_ENABLED)
		{
			chatDiagnostics = new ChatDiagnostics(client, config);
		}
		if (performanceMetrics != null)
		{
			performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.STARTUP);
		}

		/*
		 * Refresh retained rows through the active layout pipeline.
		 */
		clientThread.invokeLater(() -> {
			client.refreshChat();

			if (client.getGameState() == GameState.LOGGED_IN)
			{
				showUpdateMessage();
			}
		});

		log.debug("[Chat XL] Plugin Initiated.");
	}

	@Override
	protected void shutDown()
	{
		final FontLayoutService shutdownLayoutService = fontLayoutService;
		final PerformanceMetrics shutdownPerformanceMetrics = performanceMetrics;
		final boolean uninstalling = isBeingUninstalled();

		/*
		 * Disable PRE / POST handling before restoration.
		 */
		fontLayoutService = null;
		fontMeasurementService = null;
		textNormalizer = null;

		if (chatDiagnostics != null)
		{
			chatDiagnostics.reset();
		}
		chatDiagnostics = null;

		if (shutdownPerformanceMetrics != null)
		{
			shutdownPerformanceMetrics.recordRefreshChat(
					PerformanceMetrics.RefreshReason.SHUTDOWN);
		}

		if (uninstalling)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, VERSION_CONFIG_KEY);
		}

		// Restore native presentation on the client thread.
		clientThread.invokeLater(() -> {
			if (shutdownLayoutService != null)
			{
				shutdownLayoutService.restoreNativePresentation();
				shutdownLayoutService.reset();
			}
			client.refreshChat();

			if (uninstalling && client.getGameState() == GameState.LOGGED_IN)
			{
				showUninstallMessage();
			}

			if (shutdownPerformanceMetrics != null)
			{
				shutdownPerformanceMetrics.reportNow();
			}
		});

		performanceMetrics = null;

		log.debug("[Chat XL] Plugin Terminated.");
	}

	/*
	 * ================================================================
	 * DIAGNOSTICS
	 * ================================================================
	 */
	private void runDiagnosticPre(
			ScriptPreFired event)
	{
		if (!DIAGNOSTICS_ENABLED
				|| chatDiagnostics == null)
		{
			return;
		}

		chatDiagnostics.onScriptPreFired(
				event);
	}

	private void runDiagnosticPost(
			ScriptPostFired event)
	{
		if (!DIAGNOSTICS_ENABLED
				|| chatDiagnostics == null)
		{
			return;
		}

		chatDiagnostics.onScriptPostFired(
				event);
	}

	/*
	 * ================================================================
	 * CHAT CONSTRUCTION
	 * ================================================================
	 */
	@Subscribe
	public void onScriptPreFired(
			ScriptPreFired event)
	{
		// Observe native PRE state before layout changes.
		runDiagnosticPre(event);

		if (fontLayoutService == null)
		{
			return;
		}

		// Measure production PRE processing.
		final long started =
				performanceMetrics != null
						? System.nanoTime()
						: 0L;

		fontLayoutService.onScriptPreFired(event);

		if (performanceMetrics != null && event != null)
		{
			performanceMetrics.recordPre(
					event.getScriptId(),
					System.nanoTime()
							- started);
		}
	}

	@Subscribe
	public void onScriptPostFired(
			ScriptPostFired event)
	{
		if (fontLayoutService != null)
		{
			final long started =
					performanceMetrics != null
							? System.nanoTime()
							: 0L;

			fontLayoutService.onScriptPostFired(event);

			if (performanceMetrics != null
					&& event != null)
			{
				performanceMetrics.recordPost(
						event.getScriptId(),
						System.nanoTime()
								- started);
			}
		}

		// Observe final POST presentation when enabled.
		runDiagnosticPost(event);

		// Report performance measurements when enabled.
		if (performanceMetrics != null)
		{
			performanceMetrics.reportIfDue();
		}
	}

	/*
	 * ================================================================
	 * GAME STATE
	 * ================================================================
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event == null || event.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		showUpdateMessage();
	}

	/*
	 * ================================================================
	 * CHAT COMMANDS
	 * ================================================================
	 */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (event == null)
		{
			return;
		}

		final String command = event.getCommand();

		if ("clear".equalsIgnoreCase(command) || "cls".equalsIgnoreCase(command))
		{
			clientThread.invokeLater(this::clearChatHistory);
			return;
		}

		if ("chatxl-messages".equalsIgnoreCase(command))
		{
			clientThread.invokeLater(this::showMessageTests);
		}
	}

	private void clearChatHistory()
	{
		boolean removed = false;

		for (ChatMessageType messageType : ChatMessageType.values())
		{
			final ChatLineBuffer lineBuffer =
					client.getChatLineMap().get(
							messageType.getType());

			if (lineBuffer == null)
			{
				continue;
			}

			final MessageNode[] lines = lineBuffer.getLines().clone();

			for (MessageNode line : lines)
			{
				if (line == null)
				{
					continue;
				}

				lineBuffer.removeMessageNode(line);
				removed = true;
			}
		}

		if (removed)
		{
			client.refreshChat();
		}
	}

	private void showMessageTests()
	{
		showInstallMessage();

		final String currentVersion = getCurrentVersion();

		showVersionMessage(
				currentVersion != null
						? currentVersion
						: "3.2.1");

		showUninstallMessage();
	}

	/*
	 * ================================================================
	 * VERSION NOTIFICATION
	 * ================================================================
	 */
	private String getCurrentVersion()
	{
		final PluginHubManifest.DisplayData displayData =
				ExternalPluginManager.getDisplayData(
						getClass());

		return displayData != null
				? displayData.getVersion()
				: null;
	}

	private boolean isBeingUninstalled()
	{
		final String internalName =
				ExternalPluginManager.getInternalName(
						getClass());

		return internalName != null
				&& !externalPluginManager
				.getInstalledExternalPlugins()
				.contains(
						internalName);
	}

	private void showUpdateMessage()
	{
		final String previousVersion = configManager.getConfiguration(CONFIG_GROUP, VERSION_CONFIG_KEY);
		final String currentVersion = getCurrentVersion();

		if (currentVersion == null || currentVersion.equals(previousVersion))
		{
			return;
		}

		if (previousVersion == null || previousVersion.isEmpty())
		{
			showInstallMessage();
		} else {
			showVersionMessage(currentVersion);
		}

		configManager.setConfiguration(
				CONFIG_GROUP,
				VERSION_CONFIG_KEY,
				currentVersion);
	}

	private void showInstallMessage()
	{
		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> "
						+ INSTALL_MESSAGE,
				null);
	}

	private void showVersionMessage(String currentVersion)
	{
		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> Updated to v"
						+ currentVersion
						+ "!",
				null);

		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> "
						+ UPDATE_MESSAGE,
				null);
	}

	private void showUninstallMessage()
	{
		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> "
						+ UNINSTALL_MESSAGE,
				null);
	}

	/*
	 * ================================================================
	 * CONFIGURATION
	 * ================================================================
	 */
	@Subscribe
	public void onConfigChanged(
			ConfigChanged event)
	{
		if (event == null
				|| !CONFIG_GROUP.equals(
				event.getGroup()))
		{
			return;
		}

		if ("chatFont".equals(
				event.getKey()))
		{
			/*
			 * Refresh the active font/profile before rebuilding retained rows.
			 */
			if (fontLayoutService != null)
			{
				fontLayoutService.refreshActiveFontState();
			}

			/*
			 * Clear incomplete construction state before rebuilding retained rows.
			 */
			if (fontLayoutService != null)
			{
				fontLayoutService.reset();
			}

			if (chatDiagnostics != null)
			{
				chatDiagnostics.reset();
			}

			if (performanceMetrics != null)
			{
				performanceMetrics.recordRefreshChat(
						PerformanceMetrics.RefreshReason.FONT_CHANGED);
			}

			clientThread.invokeLater(client::refreshChat);
		}
	}

	/*
	 * ================================================================
	 * CONFIGURATION PROVIDER
	 * ================================================================
	 */
	@Provides
	Configurations provideConfig(
			ConfigManager configManager)
	{
		return configManager.getConfig(
				Configurations.class);
	}
}