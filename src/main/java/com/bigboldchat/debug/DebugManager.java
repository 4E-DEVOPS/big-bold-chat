package com.bigboldchat.debug;

import com.bigboldchat.Configurations;

import java.util.function.Supplier;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Coordinates optional Chat XL diagnostics and plugin lifecycle messages.
 */
public final class DebugManager {
	private final Client client;
	private final Configurations config;
	private final ChatMessageTests chatMessageTests;
	private final OverlayManager overlayManager;
	private final PerformanceMetrics performanceMetrics;
	private final UpdateMessages updateMessages;

	private ChatboxDiagnostics chatboxDiagnostics;
	private DialogueDiagnostics dialogueDiagnostics;
	private FontDiagnostics fontDiagnostics;
	private SideContainerDiagnostics sideContainerDiagnostics;

	@Inject
	public DebugManager(Client client, Configurations config, ChatMessageTests chatMessageTests, OverlayManager overlayManager, UpdateMessages updateMessages) {
		this.client = client;
		this.config = config;
		this.chatMessageTests = chatMessageTests;
		this.overlayManager = overlayManager;
		this.updateMessages = updateMessages;
		this.performanceMetrics = new PerformanceMetrics();
	}

	public PerformanceMetrics activate() {
		deactivate();
		performanceMetrics.reset();
		chatboxDiagnostics = new ChatboxDiagnostics(client);
		dialogueDiagnostics = new DialogueDiagnostics();
		fontDiagnostics = new FontDiagnostics(client, config);
		sideContainerDiagnostics = new SideContainerDiagnostics(client, config, overlayManager);
		return performanceMetrics;
	}

	public void deactivate() {
		if (fontDiagnostics != null) {
			fontDiagnostics.reset();
		}

		if (chatboxDiagnostics != null) {
			chatboxDiagnostics.reset();
		}

		if (dialogueDiagnostics != null) {
			dialogueDiagnostics.deactivate();
		}

		if (sideContainerDiagnostics != null) {
			sideContainerDiagnostics.reset();
		}

		fontDiagnostics = null;
		chatboxDiagnostics = null;
		dialogueDiagnostics = null;
		sideContainerDiagnostics = null;
	}

	public boolean onCommandExecuted(CommandExecuted event) {
		if (chatboxDiagnostics != null && chatboxDiagnostics.onCommandExecuted(event)) {
			return true;
		}

		if (dialogueDiagnostics != null && dialogueDiagnostics.onCommandExecuted(event)) {
			return true;
		}

		if (sideContainerDiagnostics != null && sideContainerDiagnostics.onCommandExecuted(event)) {
			return true;
		}

		if (fontDiagnostics != null && fontDiagnostics.onCommandExecuted(event)) {
			return true;
		}

		if (performanceMetrics.onCommandExecuted(event)) {
			return true;
		}

		if (chatMessageTests != null && chatMessageTests.onCommandExecuted(event)) {
			return true;
		}

		return updateMessages.onCommandExecuted(event);
	}

	// Provides native-script diagnostics with a read-only snapshot of committed chatbox geometry.
	public void bindChatboxGeometryTrace(Supplier<String> supplier) {
		if (chatboxDiagnostics != null) {
			chatboxDiagnostics.setGeometryTraceSupplier(supplier);
		}
	}

	public DialogueDiagnostics getDialogueDiagnostics() {
		return dialogueDiagnostics;
	}

	public boolean isChatboxDiagnosticsArmed() {
		return chatboxDiagnostics != null && chatboxDiagnostics.isArmed();
	}

	public void onConfigChanged(ConfigChanged event) {
		if (fontDiagnostics != null) {
			fontDiagnostics.onConfigChanged(event);
		}
	}

	public void onLoggedIn() {
		updateMessages.onLoggedIn();
	}

	public boolean prepareShutdown() {
		return updateMessages.prepareShutdown();
	}

	public void finishShutdown(boolean uninstalling) {
		updateMessages.finishShutdown(uninstalling);
	}

	public void onChatboxPre(ScriptPreFired event) {
		if (chatboxDiagnostics != null) {
			chatboxDiagnostics.onScriptPreFired(event);
		}

		if (sideContainerDiagnostics != null) {
			sideContainerDiagnostics.onScriptPreFired(event);
		}
	}

	public void onFontPre(ScriptPreFired event) {
		if (fontDiagnostics != null) {
			fontDiagnostics.onScriptPreFired(event);
		}
	}

	public void onChatboxPost(ScriptPostFired event) {
		if (chatboxDiagnostics != null) {
			chatboxDiagnostics.onScriptPostFired(event);
		}

		if (sideContainerDiagnostics != null) {
			sideContainerDiagnostics.onScriptPostFired(event);
		}
	}

	public void onFontPost(ScriptPostFired event) {
		if (fontDiagnostics != null) {
			fontDiagnostics.onScriptPostFired(event);
		}
	}

	public void reportPerformance() {
		performanceMetrics.reportIfDue();
	}
}
