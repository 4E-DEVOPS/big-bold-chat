package com.bigboldchat.config;

import com.bigboldchat.Configurations;
import com.bigboldchat.chatbox.ChatboxResizeService;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;

/**
 * Owns chatbox configuration changes.
 */
public final class ChatboxConfigHandler {
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String WIDTH_KEY = "chatboxWidth";
	private static final String HEIGHT_KEY = "chatboxHeight";
	private static final String BUTTONS_KEY = "hideChatboxButtons";

	private final Client client;
	private final ClientThread clientThread;
	private final Configurations config;
	private final ChatboxResizeService resizeService;
	private final PerformanceMetrics performanceMetrics;

	private volatile boolean active = true;
	private boolean commitQueued;
	private boolean boundsChanged;
	private boolean pendingWidthRefresh;
	private boolean pendingHeightRefresh;
	private boolean refreshQueued;
	private boolean refreshWidthChanged;
	private boolean refreshHeightChanged;
	private boolean refreshing;
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

		if (BUTTONS_KEY.equals(event.getKey())) {
			clientThread.invokeLater(() -> {
				if (active && resizeService != null) {
					resizeService.setChatboxButtonsHidden(config.hideChatboxButtons());
					queueCommit(true, false, false);
				}
			});
			return true;
		}

		if (!WIDTH_KEY.equals(event.getKey()) && !HEIGHT_KEY.equals(event.getKey())) {
			return false;
		}

		queueCommit(false, false, false);
		return true;
	}

	public void onCanvasSizeChanged() {
		if (!active) {
			return;
		}

		queueCommit(true, false, false);
	}

	public void onInterfaceChanged() {
		if (!active) {
			return;
		}

		queueCommit(true, false, false);
	}

	public void onLayoutChanged(ChatboxResizeService.ResizeResult result) {
		if (!active
				|| refreshing
				|| result == null
				|| !result.isApplied()
				|| !result.isWidthChanged() && !result.isHeightChanged()) {
			return;
		}

		/*
		 * Script-driven layout changes apply geometry synchronously so the
		 * native relayout sees it. Reapply once after that script stack settles,
		 * then refresh presentation against the stable effective dimensions.
		 */
		queueCommit(true, result.isWidthChanged(), result.isHeightChanged());
	}

	public synchronized void deactivate() {
		active = false;
		commitQueued = false;
		boundsChanged = false;
		pendingWidthRefresh = false;
		pendingHeightRefresh = false;
		refreshQueued = false;
		refreshWidthChanged = false;
		refreshHeightChanged = false;
	}

	public ChatboxResizeService.ResizeResult applyConfiguredSize() {
		if (!active || resizeService == null || config == null) {
			return null;
		}

		resizeService.setChatboxButtonsHidden(config.hideChatboxButtons());

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final ChatboxResizeService.ResizeResult result = resizeService.applySize(width, height);
		if (result != null && result.isApplied()) {
			committedWidth = width;
			committedHeight = height;
		}

		return result;
	}

	private synchronized void queueCommit(boolean boundsChanged, boolean widthRefresh, boolean heightRefresh) {
		if (!active) {
			return;
		}

		this.boundsChanged |= boundsChanged;
		pendingWidthRefresh |= widthRefresh;
		pendingHeightRefresh |= heightRefresh;
		if (commitQueued) {
			return;
		}

		commitQueued = true;
		clientThread.invokeLater(this::drainCommit);
	}

	private void drainCommit() {
		final boolean reapplyBounds;
		final boolean widthRefresh;
		final boolean heightRefresh;
		synchronized (this) {
			commitQueued = false;
			reapplyBounds = boundsChanged;
			widthRefresh = pendingWidthRefresh;
			heightRefresh = pendingHeightRefresh;
			boundsChanged = false;
			pendingWidthRefresh = false;
			pendingHeightRefresh = false;
		}

		if (!active || resizeService == null || config == null) {
			return;
		}

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final boolean widthChanged = width != committedWidth;
		final boolean heightChanged = height != committedHeight;
		if (!widthChanged && !heightChanged && !reapplyBounds && !widthRefresh && !heightRefresh) {
			return;
		}

		final ChatboxResizeService.ResizeResult result = resizeService.applySize(width, height);
		if (result == null || !result.isApplied()) {
			return;
		}

		committedWidth = width;
		committedHeight = height;

		queueRefresh(widthRefresh || result.isWidthChanged(), heightRefresh || result.isHeightChanged());
	}

	private synchronized void queueRefresh(boolean widthChanged, boolean heightChanged) {
		if (!active || !widthChanged && !heightChanged) {
			return;
		}

		refreshWidthChanged |= widthChanged;
		refreshHeightChanged |= heightChanged;
		if (refreshQueued) {
			return;
		}

		refreshQueued = true;
		clientThread.invokeLater(this::drainRefresh);
	}

	private void drainRefresh() {
		final boolean widthChanged;
		final boolean heightChanged;
		synchronized (this) {
			refreshQueued = false;
			widthChanged = refreshWidthChanged;
			heightChanged = refreshHeightChanged;
			refreshWidthChanged = false;
			refreshHeightChanged = false;
		}

		if (!active || !widthChanged && !heightChanged) {
			return;
		}

		refreshing = true;
		try {
			if (performanceMetrics != null) {
				performanceMetrics.recordRefreshChat(widthChanged
						? PerformanceMetrics.RefreshReason.WIDTH_CHANGED
						: PerformanceMetrics.RefreshReason.HEIGHT_CHANGED);
			}

			/*
			 * Geometry is stable before refreshChat so native row wrapping and
			 * scroll metrics resolve against the final effective viewport.
			 */
			client.refreshChat();
			scrollToBottom();
		} finally {
			refreshing = false;
		}
	}

	private void scrollToBottom() {
		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		if (scrollArea == null) {
			return;
		}

		final int scrollY = Math.max(0, scrollArea.getScrollHeight() - scrollArea.getHeight());
		if (scrollArea.getScrollY() != scrollY) {
			scrollArea.setScrollY(scrollY);
		}
	}
}
