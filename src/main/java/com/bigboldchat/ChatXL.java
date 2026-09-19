package com.bigboldchat;

import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.chat.FontMeasurementService;
import com.bigboldchat.chatbox.ChatboxResizeService;
import com.bigboldchat.config.ChatboxConfigHandler;
import com.bigboldchat.config.FontConfigHandler;
import com.bigboldchat.debug.ChatboxDiagnostics;
import com.bigboldchat.debug.ChatMessageTests;
import com.bigboldchat.debug.FontDiagnostics;
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
		tags = {"1877", "accessibility", "bbc", "big", "bigger", "bold", "chat", "chatbox", "classic", "fixed", "font", "fonts", "large", "magnifier", "magnify", "messages", "modern", "pm", "private", "readability", "resizable", "resize", "resizer", "resizing", "scale", "size", "small", "text", "zoom"},
		enabledByDefault = true
)
public class ChatXL extends Plugin
{
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String VERSION_CONFIG_KEY = "lastNotifiedVersion";

	private static final String INSTALL_MESSAGE = "Thank you for installing ChatXL! Report any issues you find to Github.";
	private static final String UNINSTALL_MESSAGE = "Please submit a review/issue report on Github of your experience. Thanks!";
	private static final String UPDATE_MESSAGE = "Resizable Chatbox, and a Broadcast Message + Logged Message fix.";

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

	@Inject
	private ChatMessageTests chatMessageTests;

	/*
	 * Diagnostics & performance.
	 */
	private ChatboxDiagnostics chatboxDiagnostics;
	private FontDiagnostics fontDiagnostics;
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
	protected void startUp()
	{
		performanceMetrics = new PerformanceMetrics();

		chatboxResizeService = new ChatboxResizeService(client, performanceMetrics);
		fontMeasurementService = new FontMeasurementService(client, performanceMetrics);
		fontLayoutService = new FontLayoutService(
				client,
				config,
				fontMeasurementService,
				performanceMetrics);

		chatboxConfigHandler = new ChatboxConfigHandler(
				client,
				clientThread,
				config,
				chatboxResizeService,
				performanceMetrics);

		fontConfigHandler = new FontConfigHandler(
				client,
				clientThread,
				fontLayoutService,
				performanceMetrics);

		chatboxDiagnostics = new ChatboxDiagnostics(client);
		fontDiagnostics = new FontDiagnostics(client, config);

		performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.STARTUP);

		/*
		 * Refresh retained rows through the active layout pipeline.
		 */
		clientThread.invokeLater(() -> {
			if (chatboxConfigHandler != null)
			{
				chatboxConfigHandler.applyConfiguredSize();
			}

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
		final ChatboxResizeService shutdownResizeService = chatboxResizeService;
		final PerformanceMetrics shutdownPerformanceMetrics = performanceMetrics;
		final boolean uninstalling = isBeingUninstalled();

		/*
		 * Disable PRE / POST and config handling before restoration.
		 */
		if (chatboxConfigHandler != null)
		{
			chatboxConfigHandler.deactivate();
		}

		if (fontConfigHandler != null)
		{
			fontConfigHandler.deactivate();
		}

		fontLayoutService = null;
		chatboxResizeService = null;
		fontMeasurementService = null;
		chatboxConfigHandler = null;
		fontConfigHandler = null;

		if (fontDiagnostics != null)
		{
			fontDiagnostics.reset();
		}
		fontDiagnostics = null;

		if (chatboxDiagnostics != null)
		{
			chatboxDiagnostics.reset();
		}
		chatboxDiagnostics = null;

		if (shutdownPerformanceMetrics != null)
		{
			shutdownPerformanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.SHUTDOWN);
		}

		if (uninstalling)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, VERSION_CONFIG_KEY);
		}

		// Restore native presentation on the client thread.
		clientThread.invokeLater(() -> {
			if (shutdownResizeService != null)
			{
				shutdownResizeService.restoreNativeSize();
			}

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

		if (chatboxConfigHandler != null)
		{
			chatboxConfigHandler.applyConfiguredSize();
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

		if ("chatxl-updates".equalsIgnoreCase(command))
		{
			clientThread.invokeLater(this::showUpdateTests);
			return;
		}

		if (chatboxDiagnostics != null && chatboxDiagnostics.onCommandExecuted(event))
		{
			return;
		}

		if (fontDiagnostics != null && fontDiagnostics.onCommandExecuted(event))
		{
			return;
		}

		if (performanceMetrics != null && performanceMetrics.onCommandExecuted(event))
		{
			return;
		}

		if (chatMessageTests != null)
		{
			chatMessageTests.onCommandExecuted(event);
		}
	}

	private void clearChatHistory()
	{
		boolean removed = false;

		for (ChatMessageType messageType : ChatMessageType.values())
		{
			final ChatLineBuffer lineBuffer = client.getChatLineMap().get(messageType.getType());

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

	private void showUpdateTests()
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
		final PluginHubManifest.DisplayData displayData = ExternalPluginManager.getDisplayData(getClass());

		return displayData != null
				? displayData.getVersion()
				: null;
	}

	private boolean isBeingUninstalled()
	{
		final String internalName = ExternalPluginManager.getInternalName(getClass());

		return internalName != null
				&& !externalPluginManager
				.getInstalledExternalPlugins()
				.contains(internalName);
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

		configManager.setConfiguration(CONFIG_GROUP, VERSION_CONFIG_KEY, currentVersion);
	}

	private void showInstallMessage()
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> " + INSTALL_MESSAGE,
				null);
	}

	private void showVersionMessage(String currentVersion)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> Updated to v" + currentVersion + "!",
				null);

		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> " + UPDATE_MESSAGE,
				null);
	}

	private void showUninstallMessage()
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> " + UNINSTALL_MESSAGE,
				null);
	}

	/*
	 * ================================================================
	 * CONFIGURATION
	 * ================================================================
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (fontDiagnostics != null)
		{
			fontDiagnostics.onConfigChanged(event);
		}

		if (chatboxConfigHandler != null && chatboxConfigHandler.onConfigChanged(event))
		{
			return;
		}

		if (fontConfigHandler != null)
		{
			fontConfigHandler.onConfigChanged(event);
		}
	}

	/*
	 * ================================================================
	 * CONFIGURATION PROVIDER
	 * ================================================================
	 */
	@Provides
	Configurations provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(Configurations.class);
	}

	/*
	 * ================================================================
	 * CHAT CONSTRUCTION
	 * ================================================================
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (chatboxDiagnostics != null)
		{
			chatboxDiagnostics.onScriptPreFired(event);
		}

		if (chatboxResizeService != null)
		{
			chatboxResizeService.onScriptPreFired(event, config.chatboxWidth(), config.chatboxHeight());
		}

		if (fontDiagnostics != null)
		{
			fontDiagnostics.onScriptPreFired(event);
		}

		if (fontLayoutService == null)
		{
			return;
		}

		final boolean metricsEnabled = performanceMetrics != null && performanceMetrics.isEnabled();

		final long started = metricsEnabled
				? System.nanoTime()
				: 0L;

		fontLayoutService.onScriptPreFired(event);

		if (metricsEnabled && event != null)
		{
			performanceMetrics.recordPre(
					event.getScriptId(),
					System.nanoTime() - started);
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (chatboxDiagnostics != null)
		{
			chatboxDiagnostics.onScriptPostFired(event);
		}

		if (fontLayoutService != null)
		{
			final boolean metricsEnabled = performanceMetrics != null && performanceMetrics.isEnabled();

			final long started = metricsEnabled
					? System.nanoTime()
					: 0L;

			fontLayoutService.onScriptPostFired(event);

			if (metricsEnabled && event != null)
			{
				performanceMetrics.recordPost(
						event.getScriptId(),
						System.nanoTime() - started);
			}
		}

		if (chatboxResizeService != null)
		{
			chatboxResizeService.onScriptPostFired(event, config.chatboxWidth(), config.chatboxHeight());
		}

		if (fontDiagnostics != null)
		{
			fontDiagnostics.onScriptPostFired(event);
		}

		if (performanceMetrics != null)
		{
			performanceMetrics.reportIfDue();
		}
	}
}
