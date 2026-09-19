package com.bigboldchat.debug;

import com.bigboldchat.ChatXL;

import javax.inject.Inject;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.externalplugins.PluginHubManifest;

/**
 * Owns Chat XL install, update, uninstall, and debug update messages.
 */
public final class UpdateMessages {
	private static final String COMMAND = "debug-updates";
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String VERSION_CONFIG_KEY = "lastNotifiedVersion";

	private static final String INSTALL_MESSAGE = "Thank you for installing ChatXL!"
			+ " Report any issues you find to Github.";
	private static final String UNINSTALL_MESSAGE = "Thank you for using ChatXL!"
			+ " Please submit a review/issue report on Github of your experience.";
	private static final String UPDATE_MESSAGE = "Resizable Chatbox, and a Broadcast Message + Logged Message fix.";

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final ExternalPluginManager externalPluginManager;

	@Inject
	public UpdateMessages(
			Client client,
			ClientThread clientThread,
			ConfigManager configManager,
			ExternalPluginManager externalPluginManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.externalPluginManager = externalPluginManager;
	}

	public boolean onCommandExecuted(CommandExecuted event) {
		if (event == null || !COMMAND.equalsIgnoreCase(event.getCommand())) {
			return false;
		}

		clientThread.invokeLater(this::showUpdateTests);
		return true;
	}

	public void onLoggedIn() {
		if (client.getGameState() == GameState.LOGGED_IN) {
			showUpdateMessage();
		}
	}

	public boolean prepareShutdown() {
		final boolean uninstalling = isBeingUninstalled();
		if (uninstalling) {
			configManager.unsetConfiguration(CONFIG_GROUP, VERSION_CONFIG_KEY);
		}

		return uninstalling;
	}

	public void finishShutdown(boolean uninstalling) {
		if (uninstalling && client.getGameState() == GameState.LOGGED_IN) {
			showUninstallMessage();
		}
	}

	private void showUpdateTests() {
		showInstallMessage();

		final String currentVersion = getCurrentVersion();
		showVersionMessage(currentVersion != null
				? currentVersion
				: "3.2.1");

		showUninstallMessage();
	}

	private String getCurrentVersion() {
		final PluginHubManifest.DisplayData displayData = ExternalPluginManager.getDisplayData(ChatXL.class);
		return displayData != null
				? displayData.getVersion()
				: null;
	}

	private boolean isBeingUninstalled() {
		final String internalName = ExternalPluginManager.getInternalName(ChatXL.class);
		return internalName != null && !externalPluginManager.getInstalledExternalPlugins().contains(internalName);
	}

	private void showUpdateMessage() {
		final String previousVersion = configManager.getConfiguration(CONFIG_GROUP, VERSION_CONFIG_KEY);
		final String currentVersion = getCurrentVersion();
		if (currentVersion == null || currentVersion.equals(previousVersion)) {
			return;
		}

		if (previousVersion == null || previousVersion.isEmpty()) {
			showInstallMessage();
		} else {
			showVersionMessage(currentVersion);
		}

		configManager.setConfiguration(CONFIG_GROUP, VERSION_CONFIG_KEY, currentVersion);
	}

	private void showInstallMessage() {
		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> " + INSTALL_MESSAGE,
				null);
	}

	private void showVersionMessage(String currentVersion) {
		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> Updated to v" + currentVersion + "!",
				null);

		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> " + UPDATE_MESSAGE,
				null);
	}

	private void showUninstallMessage() {
		client.addChatMessage(
				ChatMessageType.GAMEMESSAGE,
				"",
				"<col=ff981f><shad=E6B955>ChatXL:</shad></col> " + UNINSTALL_MESSAGE,
				null);
	}
}
