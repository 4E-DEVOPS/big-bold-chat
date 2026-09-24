package com.bigboldchat;

import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.chat.FontMeasurementService;
import com.bigboldchat.chatbox.ChatRebuildCoordinator;
import com.bigboldchat.chatbox.ChatboxResizeService;
import com.bigboldchat.config.ChatboxConfigHandler;
import com.bigboldchat.config.FontConfigHandler;
import com.bigboldchat.debug.DebugManager;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.input.ChatboxHotkey;
import com.bigboldchat.layout.PrivateChatLayout;
import com.bigboldchat.overlay.PrivateChatOverlay;

import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.CanvasSizeChanged;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
		name = "Chat XL",
		description = "Resize the chatbox and its text for improved readability.<br>"
				+ "[A.K.A. BBC - Big Bold Chat]",
		conflicts = {"Chat Resizer", "Resizable Chat"},
		tags = {"1877", "accessibility", "bbc", "big", "bigger", "bold", "chat", "chatbox", "classic", "fixed", "font", "fonts", "large", "magnifier", "magnify", "messages", "modern", "pm", "private", "readability", "resizable", "resize", "resizer", "resizing", "scale", "size", "small", "text", "zoom"},
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
	private ConfigManager configManager;
	@Inject
	private DebugManager debugManager;
	@Inject
	private KeyManager keyManager;
	@Inject
	private OverlayManager overlayManager;

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
	private ChatRebuildCoordinator chatRebuildCoordinator;
	private ChatboxHotkey chatboxHotkey;
	private PrivateChatOverlay privateChatOverlay;
	private PrivateChatLayout privateChatLayout;

	/*
	 * Configuration handlers.
	 */
	private ChatboxConfigHandler chatboxConfigHandler;
	private FontConfigHandler fontConfigHandler;

	/**
	 * ================================================================
	 * START-UP & SHUT-DOWN
	 * ================================================================
	 */
	@Override
	protected void startUp() {
		performanceMetrics = debugManager.activate();
		chatboxResizeService = new ChatboxResizeService(client, performanceMetrics);
		privateChatOverlay = new PrivateChatOverlay();
		privateChatLayout = new PrivateChatLayout(client, config, configManager, privateChatOverlay);
		overlayManager.add(privateChatOverlay);
		chatRebuildCoordinator = new ChatRebuildCoordinator(
				client, clientThread, config, chatboxResizeService, performanceMetrics);
		chatboxHotkey = new ChatboxHotkey(
				clientThread, config, chatboxResizeService, keyManager, this::clearChatHistory);
		chatboxHotkey.activate();
		fontMeasurementService = new FontMeasurementService(client, performanceMetrics);
		fontLayoutService = new FontLayoutService(client, config, fontMeasurementService, performanceMetrics);
		chatboxConfigHandler = new ChatboxConfigHandler(chatRebuildCoordinator);
		fontConfigHandler = new FontConfigHandler(client, clientThread, fontLayoutService, performanceMetrics);
		/*
		 * Refresh retained rows through the active layout pipeline.
		 */
		clientThread.invokeLater(() -> {
			if (chatRebuildCoordinator != null) {
				chatRebuildCoordinator.onStartup();
			}

			debugManager.onLoggedIn();
		});

		log.debug("[Chat XL] Plugin Initiated.");
	}

	@Override
	protected void shutDown() {
		final FontLayoutService shutdownLayoutService = fontLayoutService;
		final ChatboxResizeService shutdownResizeService = chatboxResizeService;
		final PrivateChatOverlay shutdownPrivateChatOverlay = privateChatOverlay;
		final PrivateChatLayout shutdownPrivateChatLayout = privateChatLayout;
		final PerformanceMetrics shutdownPerformanceMetrics = performanceMetrics;
		final boolean uninstalling = debugManager.prepareShutdown();

		/*
		 * Disable PRE / POST and config handling before restoration.
		 */
		if (chatboxConfigHandler != null) {
			chatboxConfigHandler.deactivate();
		}

		if (chatRebuildCoordinator != null) {
			chatRebuildCoordinator.deactivate();
		}

		if (fontConfigHandler != null) {
			fontConfigHandler.deactivate();
		}

		if (chatboxHotkey != null) {
			chatboxHotkey.deactivate();
		}

		fontLayoutService = null;
		chatboxResizeService = null;
		chatRebuildCoordinator = null;
		chatboxHotkey = null;
		privateChatOverlay = null;
		privateChatLayout = null;
		fontMeasurementService = null;
		chatboxConfigHandler = null;
		fontConfigHandler = null;

		if (shutdownPrivateChatOverlay != null) {
			overlayManager.remove(shutdownPrivateChatOverlay);
		}

		debugManager.deactivate();

		if (shutdownPerformanceMetrics != null) {
			shutdownPerformanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.SHUTDOWN);
		}

		// Restore native presentation on the client thread.
		clientThread.invokeLater(() -> {
			if (shutdownPrivateChatLayout != null) {
				shutdownPrivateChatLayout.restoreNative();
			}

			final ChatboxResizeService.ScrollBaseline scrollBaseline = shutdownResizeService != null
					? shutdownResizeService.captureScrollBaseline()
					: null;

			if (shutdownResizeService != null) {
				shutdownResizeService.restoreNativeSize();
			}

			if (shutdownLayoutService != null) {
				shutdownLayoutService.restoreNativePresentation();
				shutdownLayoutService.reset();
			}

			client.refreshChat();

			if (shutdownResizeService != null) {
				shutdownResizeService.restoreScrollBaseline(scrollBaseline);
			}

			debugManager.finishShutdown(uninstalling);

			if (shutdownPerformanceMetrics != null) {
				shutdownPerformanceMetrics.reportNow();
			}
		});

		performanceMetrics = null;

		log.debug("[Chat XL] Plugin Terminated.");
	}

	/**
	 * ================================================================
	 * GAME STATE
	 * ================================================================
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event == null || event.getGameState() != GameState.LOGGED_IN) {
			return;
		}

		if (chatRebuildCoordinator != null) {
			chatRebuildCoordinator.onLoggedIn();
		}

		debugManager.onLoggedIn();
	}

	/**
	 * ================================================================
	 * CANVAS SIZE
	 * ================================================================
	 */
	@Subscribe
	public void onCanvasSizeChanged(CanvasSizeChanged event) {
		if (event == null) {
			return;
		}

		if (chatRebuildCoordinator != null) {
			chatRebuildCoordinator.onCanvasSizeChanged();
		}
	}

	/**
	 * ================================================================
	 * INTERFACE LIFECYCLE
	 * ================================================================
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event == null) {
			return;
		}

		if (chatRebuildCoordinator != null) {
			chatRebuildCoordinator.onInterfaceChanged();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event == null) {
			return;
		}

		if (chatRebuildCoordinator != null) {
			chatRebuildCoordinator.onInterfaceChanged();
		}
	}

	/**
	 * ================================================================
	 * CHAT CONTROLS
	 * ================================================================
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		if (event == null || chatboxResizeService == null) {
			return;
		}

		if (!chatboxResizeService.onChatControlClicked(event.getWidget())) {
			return;
		}

		clientThread.invokeLater(() -> {
			if (chatboxResizeService != null) {
				chatboxResizeService.finishChatControlClick();
			}
		});
	}

	/**
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

	/**
	 * ================================================================
	 * CONFIGURATION
	 * ================================================================
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		debugManager.onConfigChanged(event);

		if (privateChatLayout != null) {
			privateChatLayout.onConfigChanged(event);
		}

		if (chatboxConfigHandler != null && chatboxConfigHandler.onConfigChanged(event)) {
			return;
		}

		if (fontConfigHandler != null) {
			fontConfigHandler.onConfigChanged(event);
		}
	}

	/**
	 * ================================================================
	 * CONFIGURATION PROVIDER
	 * ================================================================
	 */
	@Provides
	Configurations provideConfig(ConfigManager configManager) {
		return configManager.getConfig(Configurations.class);
	}

	/**
	 * ================================================================
	 * CHAT CONSTRUCTION
	 * ================================================================
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		debugManager.onChatboxScriptPreFired(event);

		if (chatboxResizeService != null) {
			chatboxResizeService.onScriptPreFired(event, config.chatboxWidth(), config.chatboxHeight());
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

		if (chatboxResizeService != null) {
			chatboxResizeService.onScriptPostFired(event, config.chatboxWidth(), config.chatboxHeight());
		}

		if (chatRebuildCoordinator != null) {
			chatRebuildCoordinator.onScriptPostFired(event);
		}

		debugManager.onFontScriptPostFired(event);
		debugManager.reportPerformanceIfDue();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		boolean widthChanged = false;
		boolean heightChanged = false;

		if (privateChatLayout != null) {
			/*
			 * Resolve movable split-PM placement and effective width before the
			 * render-boundary rebuild. rebuildpmbox and FontMeasurementService then
			 * see the constrained host width during the same native reconstruction.
			 */
			final PrivateChatLayout.Result privateResult = privateChatLayout.sync();
			widthChanged |= privateResult.isWidthChanged();
		}

		if (chatboxResizeService != null) {
			final ChatboxResizeService.LiveRefresh refresh = chatboxResizeService.consumeLiveRefresh();
			if (refresh != null) {
				widthChanged |= refresh.isWidthChanged();
				heightChanged |= refresh.isHeightChanged();
			}
		}

		if (chatRebuildCoordinator != null && (widthChanged || heightChanged)) {
			/*
			 * Coalesce chatbox and split-PM width changes into one refreshChat().
			 * The coordinator rebuilds retained presentation only and never feeds the
			 * result back through the geometry commit path that previously flickered.
			 */
			chatRebuildCoordinator.refreshLiveGeometry(widthChanged, heightChanged);
		}
	}
}