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
	 * Diagnostics and Performance.
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

		/*
		 * DIAGNOSTICS & PERFORMANCE METRICS
		 */
		if (DIAGNOSTICS_ENABLED)
		{
			chatDiagnostics = new ChatDiagnostics(client, config);
		}
		if (performanceMetrics != null)
		{
			performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.STARTUP);
		}

		/*
		 * Rebuild already-existing chat rows through the production
		 * layout pipeline so the currently configured font is updated.
		 */
		clientThread.invokeLater(client::refreshChat);

		log.debug("[Chat XL] Plugin Initiated.");
	}

	@Override
	protected void shutDown()
	{
		/*
		 * Clear any incomplete PRE -> POST construction state.
		 */
		if (fontLayoutService != null)
		{
			fontLayoutService.reset();
		}

		/*
		 * Reset Diagnostic
		 */
		if (chatDiagnostics != null)
		{
			chatDiagnostics.reset();
		}
		chatDiagnostics = null;

		/*
		 * Disable our PRE / POST handling before requesting the native
		 * refresh.
		 *
		 * RuneScape can then reconstruct the visible chat rows without
		 * Chat XL reapplying its custom FontID or geometry.
		 */
		fontLayoutService =
				null;

		fontMeasurementService =
				null;

		textNormalizer =
				null;

		if (performanceMetrics != null)
		{
			performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.SHUTDOWN);
		}

		clientThread.invokeLater(client::refreshChat);

		if (performanceMetrics != null)
		{
			performanceMetrics.reportNow();
		}

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
		/*
		 * Diagnostic observes the incoming/native construction state first.
		 */
		runDiagnosticPre(event);

		if (fontLayoutService == null)
		{
			return;
		}

		// Measure production PRE processing during development.
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

		// Diagnostic observes the completed production presentation.
		runDiagnosticPost(event);

		// Report performance measurements during development.
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
			 * Font selection, wrapping, geometry, and row allocation must
			 * always be recalculated together.
			 *
			 * Discard any incomplete construction state before forcing
			 * RuneScape to reconstruct every retained chat row through
			 * the production PRE -> POST pipeline.
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