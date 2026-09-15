package com.bigboldchat;

import com.bigboldchat.chat.ChatTextNormalizer;
import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.chat.FontMeasurementService;
import com.bigboldchat.debug.ChatDiagnostics;
import com.bigboldchat.debug.PerformanceMetrics;

import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
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
		tags = {"1877", "bbc", "big", "bold", "chat", "chatbox", "text", "font", "fonts", "size", "resize", "resizer", "resizing", "resizable", "magnify", "magnifier", "zoom", "scale", "large", "bigger", "small", "readability", "accessibility", "private", "pm", "messages"},
		enabledByDefault = true
)
public class ChatXL extends Plugin
{
	private static final boolean DIAGNOSTICS_ENABLED = false;
	private static final boolean PERFORMANCE_METRICS = true;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Configurations config;

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
		clientThread.invokeLater(client::refreshChat);

		log.debug("[Chat XL] Plugin Initiated.");
	}

	@Override
	protected void shutDown()
	{
		final FontLayoutService shutdownLayoutService = fontLayoutService;
		final PerformanceMetrics shutdownPerformanceMetrics = performanceMetrics;

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

		// Restore native presentation on the client thread.
		clientThread.invokeLater(() -> {
			if (shutdownLayoutService != null)
			{
				shutdownLayoutService.restoreNativePresentation();
				shutdownLayoutService.reset();
			}
			client.refreshChat();

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
	 * CONFIGURATION
	 * ================================================================
	 */
	@Subscribe
	public void onConfigChanged(
			ConfigChanged event)
	{
		if (event == null
				|| !"bigboldchat".equals(
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