package com.bigboldchat.chatbox;

import com.bigboldchat.Configurations;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

/**
 * Coalesces chatbox geometry commits and native chat rebuilds.
 */
public final class ChatRebuildCoordinator {
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
	private boolean forceRefreshAfterCommit;
	private boolean forceRefreshTracksVisibility;
	private PerformanceMetrics.RefreshReason forceRefreshReason;
	private boolean refreshQueued;
	private boolean refreshWidthChanged;
	private boolean refreshHeightChanged;
	private boolean refreshForced;
	private boolean refreshTracksVisibility;
	private PerformanceMetrics.RefreshReason refreshReason;
	private boolean visibilityRefreshPending;
	private PerformanceMetrics.RefreshReason visibilityRefreshReason;
	private boolean refreshing;
	private int committedWidth;
	private int committedHeight;

	public ChatRebuildCoordinator(
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

	public void onStartup() {
		applyConfiguredSize(PerformanceMetrics.RefreshReason.STARTUP, true);
	}

	public void onLoggedIn() {
		applyConfiguredSize(PerformanceMetrics.RefreshReason.OTHER, true);
	}

	public void onConfiguredSizeChanged() {
		if (!active) {
			return;
		}

		queueCommit(false, false, false);
	}

	public void onButtonsChanged() {
		if (!active) {
			return;
		}

		queueCommit(true, false, false);
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
		if (!active || refreshing || result == null || !result.isApplied()
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

	public void onScriptPostFired(ScriptPostFired event) {
		if (!active || event == null || event.getScriptId() != ChatboxResizeService.CHAT_VISIBILITY
				|| resizeService == null || resizeService.isChatViewHidden()) {
			return;
		}

		final PerformanceMetrics.RefreshReason reason;
		synchronized (this) {
			if (!visibilityRefreshPending) {
				return;
			}

			visibilityRefreshPending = false;
			reason = visibilityRefreshReason;
			visibilityRefreshReason = null;
		}

		queueForcedCommit(reason, false);
	}

	public synchronized void deactivate() {
		active = false;
		commitQueued = false;
		boundsChanged = false;
		pendingWidthRefresh = false;
		pendingHeightRefresh = false;
		forceRefreshAfterCommit = false;
		forceRefreshTracksVisibility = false;
		forceRefreshReason = null;
		refreshQueued = false;
		refreshWidthChanged = false;
		refreshHeightChanged = false;
		refreshForced = false;
		refreshTracksVisibility = false;
		refreshReason = null;
		visibilityRefreshPending = false;
		visibilityRefreshReason = null;
	}

	private void applyConfiguredSize(PerformanceMetrics.RefreshReason reason, boolean trackVisibility) {
		if (!active || resizeService == null || config == null) {
			return;
		}

		resizeService.setChatboxButtonsHidden(config.hideChatboxButtons());

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final ChatboxResizeService.ResizeResult result = resizeService.applySize(width, height);
		if (result != null && result.isApplied()) {
			committedWidth = width;
			committedHeight = height;
		} else if (resizeService.getLayout() != ChatboxLayout.FIXED) {
			queueForcedCommit(reason, trackVisibility);
		}

		/*
		 * Retained rows need one native rebuild even when configured geometry
		 * already matches the mounted chatbox.
		 */
		queueRefresh(
				result != null && result.isWidthChanged(),
				result != null && result.isHeightChanged(),
				true, reason, trackVisibility);
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

	private synchronized void queueForcedCommit(PerformanceMetrics.RefreshReason reason, boolean trackVisibility) {
		if (!active) {
			return;
		}

		boundsChanged = true;
		forceRefreshAfterCommit = true;
		forceRefreshTracksVisibility |= trackVisibility;
		if (reason != null) {
			forceRefreshReason = reason;
		}

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
		final boolean forceRefresh;
		final boolean trackVisibility;
		final PerformanceMetrics.RefreshReason forcedReason;
		synchronized (this) {
			commitQueued = false;
			reapplyBounds = boundsChanged;
			widthRefresh = pendingWidthRefresh;
			heightRefresh = pendingHeightRefresh;
			forceRefresh = forceRefreshAfterCommit;
			trackVisibility = forceRefreshTracksVisibility;
			forcedReason = forceRefreshReason;
			boundsChanged = false;
			pendingWidthRefresh = false;
			pendingHeightRefresh = false;
		}

		if (!active || resizeService == null || config == null) {
			return;
		}

		resizeService.setChatboxButtonsHidden(config.hideChatboxButtons());

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final boolean widthChanged = width != committedWidth;
		final boolean heightChanged = height != committedHeight;
		if (!widthChanged
				&& !heightChanged
				&& !reapplyBounds
				&& !widthRefresh
				&& !heightRefresh
				&& !forceRefresh) {
			return;
		}

		final ChatboxResizeService.ResizeResult result = resizeService.applySize(width, height);
		if (result == null || !result.isApplied()) {
			if (resizeService.getLayout() == ChatboxLayout.FIXED) {
				clearForcedCommit();
			}
			return;
		}

		committedWidth = width;
		committedHeight = height;

		if (forceRefresh) {
			clearForcedCommit();
		}

		queueRefresh(
				widthRefresh || result.isWidthChanged(),
				heightRefresh || result.isHeightChanged(),
				forceRefresh, forcedReason, trackVisibility);
	}

	private synchronized void clearForcedCommit() {
		forceRefreshAfterCommit = false;
		forceRefreshTracksVisibility = false;
		forceRefreshReason = null;
	}

	private synchronized void queueRefresh(boolean widthChanged, boolean heightChanged,
			boolean forced, PerformanceMetrics.RefreshReason reason, boolean trackVisibility) {
		if (!active || (!forced && !widthChanged && !heightChanged)) {
			return;
		}

		refreshWidthChanged |= widthChanged;
		refreshHeightChanged |= heightChanged;
		refreshForced |= forced;
		refreshTracksVisibility |= trackVisibility;
		if (reason != null) {
			refreshReason = reason;
		}

		if (refreshQueued) {
			return;
		}

		refreshQueued = true;
		clientThread.invokeLater(this::drainRefresh);
	}

	private void drainRefresh() {
		final boolean widthChanged;
		final boolean heightChanged;
		final boolean forced;
		final boolean trackVisibility;
		final PerformanceMetrics.RefreshReason explicitReason;
		synchronized (this) {
			refreshQueued = false;
			widthChanged = refreshWidthChanged;
			heightChanged = refreshHeightChanged;
			forced = refreshForced;
			trackVisibility = refreshTracksVisibility;
			explicitReason = refreshReason;
			refreshWidthChanged = false;
			refreshHeightChanged = false;
			refreshForced = false;
			refreshTracksVisibility = false;
			refreshReason = null;
		}

		if (!active || (!forced && !widthChanged && !heightChanged)) {
			return;
		}

		refreshing = true;
		try {
			if (performanceMetrics != null) {
				performanceMetrics.recordRefreshChat(resolveRefreshReason(widthChanged, heightChanged, explicitReason));
			}

			/*
			 * Geometry is stable before refreshChat so native row wrapping and
			 * scroll metrics resolve against the final effective viewport.
			 */
			client.refreshChat();

			if (widthChanged || heightChanged) {
				scrollToBottom();
			}

			if (trackVisibility) {
				rememberVisibilityRefresh(explicitReason);
			}
		} finally {
			refreshing = false;
		}
	}

	private PerformanceMetrics.RefreshReason resolveRefreshReason(boolean widthChanged, boolean heightChanged,
			PerformanceMetrics.RefreshReason explicitReason) {
		if (explicitReason != null) {
			return explicitReason;
		}

		if (widthChanged) {
			return PerformanceMetrics.RefreshReason.WIDTH_CHANGED;
		}

		return heightChanged
				? PerformanceMetrics.RefreshReason.HEIGHT_CHANGED
				: PerformanceMetrics.RefreshReason.OTHER;
	}

	private synchronized void rememberVisibilityRefresh(PerformanceMetrics.RefreshReason reason) {
		if (resizeService == null || !resizeService.isChatViewHidden()) {
			visibilityRefreshPending = false;
			visibilityRefreshReason = null;
			return;
		}

		visibilityRefreshPending = true;
		visibilityRefreshReason = reason;
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
