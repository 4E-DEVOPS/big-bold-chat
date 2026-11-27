package com.bigboldchat.chatbox;

import com.bigboldchat.Configurations;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.client.callback.ClientThread;

/**
 * Coalesces configured chatbox geometry changes and native chat reconstruction.
 */
public final class ChatRebuildCoordinator {
	private final ClientThread clientThread;
	private final Configurations config;
	private final ChatboxCoreService chatboxService;
	private final PerformanceMetrics performanceMetrics;

	private volatile boolean active = true;

	private boolean commitQueued;
	private boolean boundsChanged;
	private boolean forceRefreshAfterCommit;
	private boolean forceRefreshTracksVisibility;
	private PerformanceMetrics.RefreshReason forceRefreshReason;

	private boolean refreshQueued;
	private boolean refreshWidthChanged;
	private boolean refreshHeightChanged;
	private boolean refreshForced;
	private boolean refreshTracksVisibility;
	private PerformanceMetrics.RefreshReason refreshReason;
	private ChatboxCoreService.ScrollBaseline refreshScrollBaseline;

	private boolean visibilityRefreshPending;
	private PerformanceMetrics.RefreshReason visibilityRefreshReason;

	private boolean refreshing;

	private int committedWidth;
	private int committedHeight;

	public ChatRebuildCoordinator(Client client, ClientThread clientThread, Configurations config, ChatboxCoreService chatboxService, PerformanceMetrics performanceMetrics) {
		this.clientThread = clientThread;
		this.config = config;
		this.chatboxService = chatboxService;
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

		queueCommit(false);
	}

	public void onButtonsChanged() {
		if (!active) {
			return;
		}

		queueCommit(true);
	}

	public void onCanvasSizeChanged() {
		if (!active) {
			return;
		}

		queueCommit(true);
	}

	public void onInterfaceChanged() {
		if (!active) {
			return;
		}

		queueCommit(true);
	}

	/*
	 * Native dialogue teardown may restore the configured width without rebuilding retained chat rows.
	 * Rebuild them after normal chat has been restored.
	 */
	public void onDialogueClosed() {
		if (!active || refreshing || chatboxService == null) {
			return;
		}

		queueRefresh(true, false, false, PerformanceMetrics.RefreshReason.WIDTH_CHANGED, false, chatboxService.captureScrollBaseline());
	}

	public void refreshLiveGeometry(boolean widthChanged, boolean heightChanged) {
		if (!active || refreshing || chatboxService == null || !widthChanged && !heightChanged) {
			return;
		}

		final ChatboxCoreService.ScrollBaseline scrollBaseline = chatboxService.captureScrollBaseline();

		/*
		 * Live geometry is already committed. Requeueing geometry would compete with
		 * RuneLite's native relayout, so rebuild retained rows without another resize.
		 * The caller coalesces chatbox and split-PM width changes.
		 */
		refreshing = true;
		try {
			if (performanceMetrics != null) {
				// Width changes take precedence because they require text rewrapping.
				final PerformanceMetrics.RefreshReason reason = widthChanged
						? PerformanceMetrics.RefreshReason.WIDTH_CHANGED
						: PerformanceMetrics.RefreshReason.HEIGHT_CHANGED;

				performanceMetrics.recordRefreshChat(reason);
			}

			chatboxService.refreshChatPreservingScroll(scrollBaseline, true);
		} finally {
			refreshing = false;
		}
	}

	// Retry a visibility-dependent rebuild once RuneLite has revealed native chat.
	public void onScriptPostFired(ScriptPostFired event) {
		if (!active || event == null || event.getScriptId() != ChatboxCoreService.CHAT_VISIBILITY || chatboxService == null || chatboxService.isChatHidden()) {
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
		forceRefreshAfterCommit = false;
		forceRefreshTracksVisibility = false;
		forceRefreshReason = null;
		refreshQueued = false;
		refreshWidthChanged = false;
		refreshHeightChanged = false;
		refreshForced = false;
		refreshTracksVisibility = false;
		refreshReason = null;
		refreshScrollBaseline = null;
		visibilityRefreshPending = false;
		visibilityRefreshReason = null;
	}

	private void applyConfiguredSize(PerformanceMetrics.RefreshReason reason, boolean trackVisibility) {
		if (!active || chatboxService == null || config == null) {
			return;
		}

		chatboxService.setChatboxButtonsHidden(config.hideChatboxButtons());

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final ChatboxCoreService.ScrollBaseline scrollBaseline = chatboxService.captureScrollBaseline();
		final ChatboxCoreService.ResizeResult result = chatboxService.applySize(width, height);
		if (result != null && result.isApplied()) {
			committedWidth = width;
			committedHeight = height;
		} else if (chatboxService.getLayout() != ChatboxLayout.FIXED) {
			queueForcedCommit(reason, trackVisibility);
		}

		// Retained rows need a rebuild even when configured geometry is unchanged.
		queueRefresh(result != null && result.isWidthChanged(), result != null && result.isHeightChanged(), true, reason, trackVisibility, scrollBaseline);
	}

	// Coalesce consecutive configuration, canvas and interface changes into one client-thread commit.
	private synchronized void queueCommit(boolean boundsChanged) {
		if (!active) {
			return;
		}

		this.boundsChanged |= boundsChanged;
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
		final boolean forceRefresh;
		final boolean trackVisibility;
		final PerformanceMetrics.RefreshReason forcedReason;
		synchronized (this) {
			commitQueued = false;
			reapplyBounds = boundsChanged;
			forceRefresh = forceRefreshAfterCommit;
			trackVisibility = forceRefreshTracksVisibility;
			forcedReason = forceRefreshReason;
			boundsChanged = false;
		}

		if (!active || chatboxService == null || config == null) {
			return;
		}

		chatboxService.setChatboxButtonsHidden(config.hideChatboxButtons());

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();
		final boolean widthChanged = width != committedWidth;
		final boolean heightChanged = height != committedHeight;
		if (!widthChanged && !heightChanged && !reapplyBounds && !forceRefresh) {
			return;
		}

		final ChatboxCoreService.ScrollBaseline scrollBaseline = chatboxService.captureScrollBaseline();
		final ChatboxCoreService.ResizeResult result = chatboxService.applySize(width, height);
		if (result == null || !result.isApplied()) {
			// Keep a forced refresh pending until a later resizable-layout commit succeeds.
			if (chatboxService.getLayout() == ChatboxLayout.FIXED) {
				clearForcedCommit();
			}
			return;
		}

		committedWidth = width;
		committedHeight = height;

		if (forceRefresh) {
			clearForcedCommit();
		}

		queueRefresh(result.isWidthChanged(), result.isHeightChanged(), forceRefresh, forcedReason, trackVisibility, scrollBaseline);
	}

	private synchronized void clearForcedCommit() {
		forceRefreshAfterCommit = false;
		forceRefreshTracksVisibility = false;
		forceRefreshReason = null;
	}

	private synchronized void queueRefresh(boolean widthChanged, boolean heightChanged, boolean forced, PerformanceMetrics.RefreshReason reason, boolean trackVisibility, ChatboxCoreService.ScrollBaseline scrollBaseline) {
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

		if (refreshScrollBaseline == null) {
			refreshScrollBaseline = scrollBaseline;
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
		final ChatboxCoreService.ScrollBaseline scrollBaseline;
		synchronized (this) {
			refreshQueued = false;
			widthChanged = refreshWidthChanged;
			heightChanged = refreshHeightChanged;
			forced = refreshForced;
			trackVisibility = refreshTracksVisibility;
			explicitReason = refreshReason;
			scrollBaseline = refreshScrollBaseline;
			refreshWidthChanged = false;
			refreshHeightChanged = false;
			refreshForced = false;
			refreshTracksVisibility = false;
			refreshReason = null;
			refreshScrollBaseline = null;
		}

		if (!active || (!forced && !widthChanged && !heightChanged)) {
			return;
		}

		refreshing = true;
		try {
			if (performanceMetrics != null) {
				performanceMetrics.recordRefreshChat(resolveRefreshReason(widthChanged, heightChanged, explicitReason));
			}

			chatboxService.refreshChatPreservingScroll(scrollBaseline, true);

			if (trackVisibility) {
				rememberVisibilityRefresh(explicitReason);
			}
		} finally {
			refreshing = false;
		}
	}

	private PerformanceMetrics.RefreshReason resolveRefreshReason(boolean widthChanged, boolean heightChanged, PerformanceMetrics.RefreshReason explicitReason) {
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
		if (chatboxService == null || !chatboxService.isChatHidden()) {
			visibilityRefreshPending = false;
			visibilityRefreshReason = null;
			return;
		}

		visibilityRefreshPending = true;
		visibilityRefreshReason = reason;
	}
}
