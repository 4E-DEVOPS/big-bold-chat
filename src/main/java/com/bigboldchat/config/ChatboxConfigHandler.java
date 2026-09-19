package com.bigboldchat.config;

import com.bigboldchat.Configurations;
import com.bigboldchat.chatbox.ChatboxResizeService;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;

/**
 * Owns chatbox-size configuration changes.
 */
public final class ChatboxConfigHandler {
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String WIDTH_KEY = "chatboxWidth";
	private static final String HEIGHT_KEY = "chatboxHeight";

	private final Client client;
	private final ClientThread clientThread;
	private final Configurations config;
	private final ChatboxResizeService resizeService;
	private final PerformanceMetrics performanceMetrics;

	private volatile boolean active = true;
	private volatile boolean commitQueued;
	private int committedWidth;
	private int committedHeight;

	public ChatboxConfigHandler(
			Client client,
			ClientThread clientThread,
			Configurations config,
			ChatboxResizeService resizeService,
			PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.resizeService = resizeService;
		this.performanceMetrics = performanceMetrics;
		this.committedWidth = config.chatboxWidth();
		this.committedHeight = config.chatboxHeight();
	}

	public boolean onConfigChanged(ConfigChanged event) {
		if (!active || event == null || !CONFIG_GROUP.equals(event.getGroup())) {
			return false;
		}

		if (!WIDTH_KEY.equals(event.getKey()) && !HEIGHT_KEY.equals(event.getKey())) {
			return false;
		}

		queueCommit();
		return true;
	}

	public void deactivate() {
		active = false;
		commitQueued = false;
	}

	public ChatboxResizeService.ResizeResult applyConfiguredSize() {
		if (!active || resizeService == null || config == null) {
			return null;
		}

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final ChatboxResizeService.ResizeResult result = resizeService.applySize(width, height);
		if (result != null && result.isApplied()) {
			committedWidth = width;
			committedHeight = height;
		}

		return result;
	}

	private void queueCommit() {
		if (commitQueued) {
			return;
		}

		commitQueued = true;
		clientThread.invokeLater(this::drainCommit);
	}

	private void drainCommit() {
		commitQueued = false;
		if (!active || resizeService == null || config == null) {
			return;
		}

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final boolean widthChanged = width != committedWidth;
		final boolean heightChanged = height != committedHeight;
		if (!widthChanged && !heightChanged) {
			return;
		}

		final ChatboxResizeService.ResizeResult result = resizeService.applySize(width, height);
		if (result == null || !result.isApplied()) {
			return;
		}

		committedWidth = width;
		committedHeight = height;

		if (!result.isWidthChanged() && !result.isHeightChanged()) {
			return;
		}

		if (performanceMetrics != null) {
			performanceMetrics.recordRefreshChat(result.isWidthChanged()
					? PerformanceMetrics.RefreshReason.WIDTH_CHANGED
					: PerformanceMetrics.RefreshReason.HEIGHT_CHANGED);
		}

		/*
		 * Geometry is committed first; refreshChat lets the native chat
		 * presentation reconcile message rows and scroll state against it.
		 */
		client.refreshChat();
	}
}
