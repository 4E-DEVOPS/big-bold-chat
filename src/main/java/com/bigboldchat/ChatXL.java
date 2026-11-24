package com.bigboldchat;

import com.bigboldchat.dialogue.DialogueCoreService;
import com.bigboldchat.dialogue.DialogueFitOverlay;
import com.bigboldchat.dialogue.DialogueFontService;
import com.bigboldchat.chat.FontLayoutService;
import com.bigboldchat.chat.FontMeasurementService;
import com.bigboldchat.chat.InputFontService;
import com.bigboldchat.chatbox.ChatRebuildCoordinator;
import com.bigboldchat.chatbox.ChatboxCoreService;
import com.bigboldchat.config.ChatboxConfigHandler;
import com.bigboldchat.config.FontConfigHandler;
import com.bigboldchat.debug.DebugManager;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.input.ChatboxHotkey;
import com.bigboldchat.layout.PrivateChatLayout;
import com.bigboldchat.overlay.PrivateChatOverlay;

import com.google.inject.Provides;

import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
		name = "Chat XL",
		description = "Resize the chatbox and its text for improved readability.<br>"
				+ "[A.K.A. BBC - Big Bold Chat]",
		conflicts = {"Chat Resizer", "Resizable Chat"},
		tags = {"1877", "accessibility", "bbc", "big", "bigger", "bold", "chat", "chatbox", "classic", "fixed", "font", "fonts", "large", "magnifier", "magnify", "messages", "modern", "pm", "private", "readability", "resizable", "resize", "resizer", "resizing", "scale", "size", "small", "split", "text", "zoom"},
		enabledByDefault = true
)
public class ChatXL extends Plugin {

	private static final Logger log = LoggerFactory.getLogger(ChatXL.class);

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
	private MouseManager mouseManager;
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
	private InputFontService inputFontService;
	private DialogueFontService dialogueFontService;

	private DialogueCoreService dialogueService;
	private ChatboxCoreService chatboxService;

	private ChatRebuildCoordinator chatRebuildCoordinator;

	private ChatboxHotkey chatboxHotkey;
	private MouseListener dialogueCollisionMouseListener;
	private final AtomicBoolean chatboxPlacementSaveQueued = new AtomicBoolean();

	private DialogueFitOverlay dialogueFitOverlay;
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
		chatboxPlacementSaveQueued.set(false);
		performanceMetrics = debugManager.activate();
		dialogueService = new DialogueCoreService(client, configManager, overlayManager, performanceMetrics);
		chatboxService = dialogueService;
		dialogueService.setDiagnostics(debugManager.getDialogueDiagnostics(), debugManager::isChatboxDiagnosticsArmed);
		debugManager.bindChatboxGeometryTrace(dialogueService::chatboxGeometryTrace);
		chatboxService.onGameStateChanged(client.getGameState());
		dialogueCollisionMouseListener = new MouseAdapter() {
			// Observe native drags without intercepting RuneLite's mouse handling.
			@Override
			public MouseEvent mousePressed(MouseEvent mouseEvent) {
				final DialogueCoreService currentDialogueService = dialogueService;
				if (currentDialogueService != null && mouseEvent.getButton() == MouseEvent.BUTTON1) {
					currentDialogueService.onOverlayMousePressed(mouseEvent.getX(), mouseEvent.getY());
				}

				return mouseEvent;
			}

			@Override
			public MouseEvent mouseDragged(MouseEvent mouseEvent) {
				final DialogueCoreService currentDialogueService = dialogueService;
				if (currentDialogueService != null) {
					currentDialogueService.onOverlayMouseDragged(mouseEvent.getX(), mouseEvent.getY());
				}

				return mouseEvent;
			}

			@Override
			public MouseEvent mouseReleased(MouseEvent mouseEvent) {
				final DialogueCoreService currentDialogueService = dialogueService;
				if (currentDialogueService != null && mouseEvent.getButton() == MouseEvent.BUTTON1) {
					currentDialogueService.onOverlayMouseReleased(mouseEvent.getX(), mouseEvent.getY());
				}

				return mouseEvent;
			}
		};
		mouseManager.registerMouseListener(0, dialogueCollisionMouseListener);
		dialogueFitOverlay = new DialogueFitOverlay(dialogueService, debugManager.getDialogueDiagnostics());
		overlayManager.add(dialogueFitOverlay);
		privateChatOverlay = new PrivateChatOverlay();
		privateChatLayout = new PrivateChatLayout(client, config, configManager, privateChatOverlay);
		overlayManager.add(privateChatOverlay);
		chatRebuildCoordinator = new ChatRebuildCoordinator(client, clientThread, config, chatboxService, performanceMetrics);
		chatboxHotkey = new ChatboxHotkey(clientThread, config, chatboxService, keyManager, this::clearChatHistory);
		chatboxHotkey.activate();
		fontMeasurementService = new FontMeasurementService(client, performanceMetrics);
		fontLayoutService = new FontLayoutService(client, config, fontMeasurementService, performanceMetrics);
		inputFontService = new InputFontService(client, config);
		dialogueFontService = new DialogueFontService(client, config);
		chatboxConfigHandler = new ChatboxConfigHandler(chatRebuildCoordinator);
		fontConfigHandler = new FontConfigHandler(client, clientThread, fontLayoutService, inputFontService, dialogueFontService, dialogueService, performanceMetrics);

		// Run the initial geometry and font sync after the client thread is ready.
		clientThread.invokeLater(() -> {
			if (chatRebuildCoordinator != null) {
				chatRebuildCoordinator.onStartup();
			}

			if (inputFontService != null) {
				inputFontService.sync();
			}

			if (dialogueFontService != null) {
				dialogueFontService.sync();
			}

			debugManager.onLoggedIn();
		});

		log.debug("[Chat XL] Plugin Initiated.");
	}

	@Override
	protected void shutDown() {
		final FontLayoutService shutdownLayoutService = fontLayoutService;
		final InputFontService shutdownInputFontService = inputFontService;
		final DialogueFontService shutdownDialogueFontService = dialogueFontService;
		final ChatboxCoreService shutdownChatboxService = chatboxService;
		final DialogueFitOverlay shutdownDialogueFitOverlay = dialogueFitOverlay;
		final PrivateChatOverlay shutdownPrivateChatOverlay = privateChatOverlay;
		final PrivateChatLayout shutdownPrivateChatLayout = privateChatLayout;
		final PerformanceMetrics shutdownPerformanceMetrics = performanceMetrics;
		final boolean uninstalling = debugManager.prepareShutdown();

		// Disable PRE / POST and config handling before restoring native widgets.
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

		if (dialogueCollisionMouseListener != null) {
			mouseManager.unregisterMouseListener(dialogueCollisionMouseListener);
			dialogueCollisionMouseListener = null;
		}

		fontLayoutService = null;
		inputFontService = null;
		dialogueFontService = null;
		dialogueService = null;
		chatboxService = null;
		dialogueFitOverlay = null;
		chatRebuildCoordinator = null;
		chatboxHotkey = null;
		privateChatOverlay = null;
		privateChatLayout = null;
		fontMeasurementService = null;
		chatboxConfigHandler = null;
		fontConfigHandler = null;

		if (shutdownDialogueFitOverlay != null) {
			overlayManager.remove(shutdownDialogueFitOverlay);
		}

		if (shutdownPrivateChatOverlay != null) {
			overlayManager.remove(shutdownPrivateChatOverlay);
		}

		debugManager.deactivate();

		if (shutdownPerformanceMetrics != null) {
			shutdownPerformanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.SHUTDOWN);
		}

		// Retain local service references for the deferred native restoration.
		clientThread.invokeLater(() -> {
			if (shutdownPrivateChatLayout != null) {
				shutdownPrivateChatLayout.restoreNative();
			}

			final ChatboxCoreService.ScrollBaseline scrollBaseline = shutdownChatboxService != null
					? shutdownChatboxService.captureScrollBaseline()
					: null;

			if (shutdownChatboxService != null) {
				shutdownChatboxService.restoreNativeSize();
			}

			if (shutdownLayoutService != null) {
				shutdownLayoutService.restoreNativePresentation();
				shutdownLayoutService.reset();
			}

			if (shutdownInputFontService != null) {
				shutdownInputFontService.restoreNativePresentation();
			}

			if (shutdownDialogueFontService != null) {
				shutdownDialogueFontService.restoreNativePresentation();
			}

			client.refreshChat();

			if (shutdownChatboxService != null) {
				shutdownChatboxService.restoreScrollBaseline(scrollBaseline);
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
		if (event == null) {
			return;
		}

		if (chatboxService != null) {
			chatboxService.onGameStateChanged(event.getGameState());
		}

		if (event.getGameState() != GameState.LOGGED_IN) {
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

		if (chatboxService != null) {
			chatboxService.onCanvasSizeChanged();
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
		if (event == null || chatboxService == null) {
			return;
		}

		if (!chatboxService.onChatControlClicked(event.getWidget())) {
			return;
		}

		clientThread.invokeLater(() -> {
			if (chatboxService != null) {
				chatboxService.finishChatControlClick();
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

		if (chatboxService != null && chatboxService.isChatboxOverlayPlacementChange(event)) {
			/* A context-menu reset clears preferredLocation; unlike a native drag,
			 * that alone must not transfer ownership of the dialogue center.
			 * Deliver the reset evidence before the coalesced placement callback. */
			if (dialogueService != null && event.getKey().endsWith("_preferredLocation") && event.getNewValue() == null) {
				clientThread.invokeLater(() -> {
					if (dialogueService != null) {
						dialogueService.onNativeChatboxPlacementReset();
					}
				});
			}
			if (chatboxPlacementSaveQueued.compareAndSet(false, true)) {
				clientThread.invokeLater(() -> {
					chatboxPlacementSaveQueued.set(false);
					if (chatboxService != null) {
						chatboxService.onSavedChatboxPlacementChanged(config.chatboxWidth(), config.chatboxHeight());
					}
				});
			}
		}

		/* The reset target is determined by RuneLite; distinguish an unrelated
		 * overlay's reset from a standalone chatbox-parent reset without listing
		 * specific movable widget names or assuming every right-click is a drag. */
		if (dialogueService != null && event != null && "runelite".equals(event.getGroup()) && event.getKey() != null && event.getKey().endsWith("_preferredLocation") && event.getNewValue() == null && (chatboxService == null || !chatboxService.isChatboxOverlayPlacementChange(event))) {
			clientThread.invokeLater(() -> {
				if (dialogueService != null) {
					dialogueService.onOtherOverlayPlacementReset();
				}
			});
		}

		if (dialogueService != null && dialogueService.isDialogueCollisionOverlayChange(event)) {
			clientThread.invokeLater(() -> {
				if (dialogueService != null) {
					dialogueService.markDialogueCollisionDirty();
				}
			});
		}

		if (chatboxService != null && chatboxService.isInventoryOverlayReset(event)) {
			chatboxService.guardInventoryOverlayReset();
			clientThread.invokeLater(() -> {
				if (chatboxService != null) {
					chatboxService.reconcileInventoryOverlayReset(config.chatboxWidth(), config.chatboxHeight());
				}
			});
		}

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
		debugManager.onChatboxPre(event);

		if (chatboxService != null) {
			chatboxService.onScriptPreFired(event, config.chatboxWidth(), config.chatboxHeight());
		}

		debugManager.onFontPre(event);

		if (fontLayoutService == null) {
			return;
		}

		final boolean metricsEnabled = performanceMetrics != null && performanceMetrics.isEnabled();

		final long started = metricsEnabled
				? System.nanoTime()
				: 0L;

		fontLayoutService.onScriptPreFired(event);

		if (metricsEnabled && event != null) {
			performanceMetrics.recordPre(event.getScriptId(), System.nanoTime() - started);
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		debugManager.onChatboxPost(event);

		if (fontLayoutService != null) {
			final boolean metricsEnabled = performanceMetrics != null && performanceMetrics.isEnabled();

			final long started = metricsEnabled
					? System.nanoTime()
					: 0L;

			fontLayoutService.onScriptPostFired(event);

			if (metricsEnabled && event != null) {
				performanceMetrics.recordPost(event.getScriptId(), System.nanoTime() - started);
			}
		}

		if (inputFontService != null) {
			inputFontService.onScriptPostFired(event);
		}

		if (dialogueFontService != null) {
			dialogueFontService.onScriptPostFired(event);
		}

		if (chatboxService != null) {
			chatboxService.onScriptPostFired(event, config.chatboxWidth(), config.chatboxHeight());
		}

		if (chatRebuildCoordinator != null) {
			chatRebuildCoordinator.onScriptPostFired(event);
		}

		if (privateChatLayout != null) {
			privateChatLayout.onScriptPostFired(event);
		}

		debugManager.onFontPost(event);
		debugManager.reportPerformance();
	}

	/**
	 * ================================================================
	 * RENDER LIFECYCLE
	 * ================================================================
	 */
	@Subscribe
	public void onPostClientTick(PostClientTick event) {
		if (chatboxService != null) {
			chatboxService.reconcilePostLoginGeometry(config.chatboxWidth(), config.chatboxHeight());
			chatboxService.reconcileAfterClientTick(config.chatboxWidth(), config.chatboxHeight());
		}

		if (privateChatLayout != null) {
			privateChatLayout.reconcileAfterClientTick();
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		boolean widthChanged = false;
		boolean heightChanged = false;

		if (privateChatLayout != null) {
			// Resolve split-PM placement before refreshing retained chat geometry.
			final PrivateChatLayout.Result privateResult = privateChatLayout.sync();
			widthChanged |= privateResult.isWidthChanged();
		}

		if (chatboxService != null) {
			chatboxService.reconcileBeforeRender(config.chatboxWidth(), config.chatboxHeight());
		}
		if (dialogueService != null) {
			// Diagnostics collect data only while explicitly armed.
			dialogueService.traceDialogueViewportDiagnostics();
			if (dialogueFitOverlay != null) {
				dialogueFitOverlay.sync();
			}

			/* Rebuild retained rows only after the normal chatbox has returned.
			 * Temporary dialogue remounts must not trigger a native refresh. */
			if (dialogueService.consumeNormalChatRestoredEvent() && chatRebuildCoordinator != null) {
				chatRebuildCoordinator.onDialogueClosed();
			}
		}

		if (chatboxService != null) {
			final ChatboxCoreService.LiveRefresh refresh = chatboxService.consumeLiveRefresh();
			if (refresh != null) {
				widthChanged |= refresh.isWidthChanged();
				heightChanged |= refresh.isHeightChanged();
			}
		}

		if (chatRebuildCoordinator != null && (widthChanged || heightChanged)) {
			// Coalesce chatbox and split-PM geometry changes into one retained-chat refresh.
			chatRebuildCoordinator.refreshLiveGeometry(widthChanged, heightChanged);
		}
	}
}
