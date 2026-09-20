package com.bigboldchat.debug;

import com.bigboldchat.Configurations;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.events.ConfigChanged;

/**
 * Owns optional Chat XL diagnostics and routes debug-only events and commands.
 */
public final class DebugManager {
	private final Client client;
	private final Configurations config;
	private final ChatMessageTests chatMessageTests;
	private final PerformanceMetrics performanceMetrics;
	private final UpdateMessages updateMessages;

	private ChatboxDiagnostics chatboxDiagnostics;
	private FontDiagnostics fontDiagnostics;
	private SideContainerDiagnostics sideContainerDiagnostics;

	@Inject
	public DebugManager(
			Client client,
			Configurations config,
			ChatMessageTests chatMessageTests,
			UpdateMessages updateMessages) {
		this.client = client;
		this.config = config;
		this.chatMessageTests = chatMessageTests;
		this.updateMessages = updateMessages;
		this.performanceMetrics = new PerformanceMetrics();
	}

	public PerformanceMetrics activate() {
		deactivate();
		performanceMetrics.reset();
		chatboxDiagnostics = new ChatboxDiagnostics(client);
		fontDiagnostics = new FontDiagnostics(client, config);
		sideContainerDiagnostics = new SideContainerDiagnostics(client, config);
		return performanceMetrics;
	}

	public void deactivate() {
		if (fontDiagnostics != null) {
			fontDiagnostics.reset();
		}

		if (chatboxDiagnostics != null) {
			chatboxDiagnostics.reset();
		}

		if (sideContainerDiagnostics != null) {
			sideContainerDiagnostics.reset();
		}

		fontDiagnostics = null;
		chatboxDiagnostics = null;
		sideContainerDiagnostics = null;
	}

	public boolean onCommandExecuted(CommandExecuted event) {
		if (chatboxDiagnostics != null && chatboxDiagnostics.onCommandExecuted(event)) {
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

	public void onChatboxScriptPreFired(ScriptPreFired event) {
		if (chatboxDiagnostics != null) {
			chatboxDiagnostics.onScriptPreFired(event);
		}

		if (sideContainerDiagnostics != null) {
			sideContainerDiagnostics.onScriptPreFired(event);
		}
	}

	public void onFontScriptPreFired(ScriptPreFired event) {
		if (fontDiagnostics != null) {
			fontDiagnostics.onScriptPreFired(event);
		}
	}

	public void onChatboxScriptPostFired(ScriptPostFired event) {
		if (chatboxDiagnostics != null) {
			chatboxDiagnostics.onScriptPostFired(event);
		}

		if (sideContainerDiagnostics != null) {
			sideContainerDiagnostics.onScriptPostFired(event);
		}
	}

	public void onFontScriptPostFired(ScriptPostFired event) {
		if (fontDiagnostics != null) {
			fontDiagnostics.onScriptPostFired(event);
		}
	}

	public void reportPerformanceIfDue() {
		performanceMetrics.reportIfDue();
	}
}
