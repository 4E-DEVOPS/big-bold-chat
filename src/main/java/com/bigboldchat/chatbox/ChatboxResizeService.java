package com.bigboldchat.chatbox;

import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * Owns resizable-layout chatbox geometry.
 *
 * Width changes are applied before chat construction so text is
 * measured against the committed width. Height changes only
 * update widget geometry and do not require a chat rebuild.
 */
public final class ChatboxResizeService {
	/*
	 * Native top-level relayout helper. Script 113 resets chat geometry
	 * immediately before invoking this helper, making PRE the earliest
	 * observable point where ChatXL can restore its committed geometry
	 * before the remainder of the native relayout uses it.
	 */
	private static final int TOPLEVEL_RELAYOUT = 1972;

	private final Client client;
	private final PerformanceMetrics performanceMetrics;
	private final ChatboxControlsLayout controlsLayout;
	private final ChatboxBackgroundService backgroundService;

	private boolean resizedLayoutApplied;

	public ChatboxResizeService(Client client, PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.performanceMetrics = performanceMetrics;
		this.controlsLayout = new ChatboxControlsLayout(client);
		this.backgroundService = new ChatboxBackgroundService(client);
	}

	/*
	 * ================================================================
	 * LAYOUT
	 * ================================================================
	 */
	ChatboxLayout getLayout() {
		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL) {
			return ChatboxLayout.FIXED;
		}

		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			return ChatboxLayout.RESIZABLE_CLASSIC;
		}

		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			return ChatboxLayout.RESIZABLE_MODERN;
		}

		return ChatboxLayout.UNKNOWN;
	}

	private Widget getSlot(ChatboxLayout layout) {
		switch (layout) {
			case RESIZABLE_CLASSIC:
				return client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
			case RESIZABLE_MODERN:
				return client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);
			case FIXED:
				return client.getWidget(InterfaceID.Toplevel.CHAT_CONTAINER);
			default:
				return null;
		}
	}

	/*
	 * ================================================================
	 * SCRIPT LIFECYCLE
	 * ================================================================
	 */
	public void onScriptPreFired(ScriptPreFired event, int width, int height) {
		if (event == null) {
			return;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == ScriptID.BUILD_CHATBOX || scriptId == ScriptID.SPLITPM_CHANGED || scriptId == TOPLEVEL_RELAYOUT) {
			applySize(width, height);
		}
	}

	public void onScriptPostFired(ScriptPostFired event, int width, int height) {
		if (event == null) {
			return;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == ScriptID.TOPLEVEL_REDRAW
				|| scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE
				|| scriptId == ScriptID.MESSAGE_LAYER_OPEN) {
			applySize(width, height);
		}
	}

	/*
	 * ================================================================
	 * CHATBOX GEOMETRY
	 * ================================================================
	 */
	public ResizeResult applySize(int width, int height) {
		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime()
				: 0L;
		final ChatboxLayout layout = getLayout();
		if (layout == ChatboxLayout.FIXED || layout == ChatboxLayout.UNKNOWN) {
			resizedLayoutApplied = false;
			return ResizeResult.NOT_APPLIED;
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (slot == null || universe == null || chatArea == null) {
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		/*
		 * Ignore transient layout swaps where Chatbox.UNIVERSE has not yet
		 * been mounted under the active resizable chat container.
		 */
		final Widget parent = universe.getParent();
		if (parent == null || parent.getId() != slot.getId()) {
			return ResizeResult.NOT_APPLIED;
		}

		final boolean widthChanged = slot.getWidth() != width || universe.getWidth() != width || chatArea.getWidth() != width;
		final boolean heightChanged = slot.getHeight() != height || universe.getHeight() != height;
		final boolean controlsChanged = !controlsLayout.matches(width);
		if (widthChanged || heightChanged || controlsChanged) {
			applyGeometry(slot, universe, chatArea, width, height, controlsChanged);
		}

		final ChatboxBackgroundService.Result backgroundResult = backgroundService.apply(
				chatArea,
				width,
				ChatboxGeometry.bodyHeight(height));

		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());

		resizedLayoutApplied = true;

		recordApply(started, widthChanged, heightChanged);

		return new ResizeResult(true, widthChanged, heightChanged);
	}

	private void applyGeometry(
			Widget slot,
			Widget universe,
			Widget chatArea,
			int width,
			int height,
			boolean controlsChanged) {
		/*
		 * Resize the top-level chat slot first so dependent chatbox children
		 * resolve against the requested outer geometry.
		 */
		if (slot.getWidth() != width || slot.getHeight() != height) {
			slot.setSize(width, height);
			recordMutation();

			slot.revalidate();
			recordRevalidate();
		}

		/*
		 * UNIVERSE normally fills using MINUS sizing. Pin it to the explicit
		 * committed dimensions while ChatXL owns the resizable layout.
		 */
		if (universe.getWidth() != width || universe.getHeight() != height) {
			universe.setSize(width, height, WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
			universe.setForcedPosition(0, 0);
			recordMutation();

			universe.revalidate();
			recordRevalidate();
		}

		/*
		 * CHATAREA's width is absolute and does not auto follow the UNIVERSE width.
		 */
		if (chatArea.getWidth() != width) {
			chatArea.setOriginalWidth(width);
			recordMutation();
		}

		/*
		 * The tab/control bar must be updated before the child revalidation
		 * cascade so its descendants resolve against the final width.
		 */
		if (controlsChanged) {
			recordMutations(controlsLayout.apply(width));
		}

		/*
		 * Revalidate only static/dynamic chatbox descendants. Deliberately stop
		 * at SCROLLAREA so RuneScape remains responsible for message-row layout.
		 */
		recordRevalidates(ChatboxWidgets.revalidateChildren(universe));
	}

	/*
	 * ================================================================
	 * RESTORATION
	 * ================================================================
	 */
	public void restoreNativeSize() {
		final boolean wasApplied = resizedLayoutApplied;
		final ChatboxLayout layout = getLayout();
		if (layout == ChatboxLayout.FIXED || layout == ChatboxLayout.UNKNOWN) {
			resizedLayoutApplied = false;
			return;
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (slot == null || universe == null) {
			resizedLayoutApplied = false;
			return;
		}

		slot.setSize(ChatboxGeometry.NATIVE_WIDTH, ChatboxGeometry.NATIVE_SLOT_HEIGHT);
		slot.setForcedPosition(-1, -1);
		recordMutation();
		slot.revalidate();
		recordRevalidate();

		universe.setSize(0, 0, WidgetSizeMode.MINUS, WidgetSizeMode.MINUS);
		universe.setForcedPosition(-1, -1);
		recordMutation();
		universe.revalidate();
		recordRevalidate();

		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chatArea != null) {
			chatArea.setOriginalWidth(ChatboxGeometry.NATIVE_WIDTH);
			recordMutation();
		}

		recordMutations(controlsLayout.restoreNative());
		recordRevalidates(ChatboxWidgets.revalidateChildren(universe));

		final ChatboxBackgroundService.Result backgroundResult = backgroundService.restore(chatArea);

		recordMutations(backgroundResult.getMutations());
		recordRevalidates(backgroundResult.getRevalidates());

		resizedLayoutApplied = false;

		if (wasApplied && performanceMetrics != null) {
			performanceMetrics.recordResizeRestore();
		}
	}

	/*
	 * ================================================================
	 * PERFORMANCE HELPERS
	 * ================================================================
	 */
	private void recordApply(long started, boolean widthChanged, boolean heightChanged) {
		if (performanceMetrics == null || !performanceMetrics.isEnabled()) {
			return;
		}

		performanceMetrics.recordResizeApply(
				System.nanoTime() - started,
				widthChanged, heightChanged);
	}

	private void recordMissingWidgets() {
		if (performanceMetrics != null) {
			performanceMetrics.recordResizeMissingWidgets();
		}
	}

	private void recordMutation() {
		if (performanceMetrics != null) {
			performanceMetrics.recordWidgetMutation();
		}
	}

	private void recordMutations(int count) {
		if (performanceMetrics == null || count <= 0) {
			return;
		}

		for (int i = 0; i < count; i++) {
			performanceMetrics.recordWidgetMutation();
		}
	}

	private void recordRevalidate() {
		if (performanceMetrics != null) {
			performanceMetrics.recordRevalidate();
		}
	}

	private void recordRevalidates(int count) {
		if (performanceMetrics == null || count <= 0) {
			return;
		}

		for (int i = 0; i < count; i++) {
			performanceMetrics.recordRevalidate();
		}
	}

	public static final class ResizeResult {
		private static final ResizeResult NOT_APPLIED = new ResizeResult(false, false, false);

		private final boolean applied;
		private final boolean widthChanged;
		private final boolean heightChanged;

		private ResizeResult(boolean applied, boolean widthChanged, boolean heightChanged) {
			this.applied = applied;
			this.widthChanged = widthChanged;
			this.heightChanged = heightChanged;
		}

		public boolean isApplied() {
			return applied;
		}

		public boolean isWidthChanged() {
			return widthChanged;
		}

		public boolean isHeightChanged() {
			return heightChanged;
		}
	}
}
