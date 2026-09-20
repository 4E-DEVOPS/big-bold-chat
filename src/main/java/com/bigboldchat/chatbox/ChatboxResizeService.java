package com.bigboldchat.chatbox;

import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.layout.ChatboxBounds;
import com.bigboldchat.layout.SideContainerLayout;

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
 * Geometry changes are applied before native chat presentation is refreshed
 * so message rows and scroll state resolve against the committed viewport.
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
	private final SideContainerLayout sideContainerLayout;

	private boolean resizedLayoutApplied;

	public ChatboxResizeService(Client client, PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.performanceMetrics = performanceMetrics;
		this.controlsLayout = new ChatboxControlsLayout(client);
		this.backgroundService = new ChatboxBackgroundService(client);
		this.sideContainerLayout = new SideContainerLayout(client);
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
	public ResizeResult onScriptPreFired(ScriptPreFired event, int width, int height) {
		if (event == null) {
			return ResizeResult.NOT_APPLIED;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE) {
			sideContainerLayout.beginNativeLayout();
		}

		if (scriptId == ScriptID.BUILD_CHATBOX
				|| scriptId == ScriptID.SPLITPM_CHANGED
				|| scriptId == TOPLEVEL_RELAYOUT) {
			return applySize(width, height);
		}

		return ResizeResult.NOT_APPLIED;
	}

	public ResizeResult onScriptPostFired(ScriptPostFired event, int width, int height) {
		if (event == null) {
			return ResizeResult.NOT_APPLIED;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE) {
			sideContainerLayout.endNativeLayout();
		}

		if (scriptId == ScriptID.TOPLEVEL_REDRAW
				|| scriptId == ScriptID.TOPLEVEL_RESIZE_CUSTOMISE
				|| scriptId == ScriptID.MESSAGE_LAYER_OPEN) {
			return applySize(width, height);
		}

		return ResizeResult.NOT_APPLIED;
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
			sideContainerLayout.reset();
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

		if (layout == ChatboxLayout.RESIZABLE_MODERN) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.apply(slot, width, height);
			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		} else {
			sideContainerLayout.reset();
		}

		/*
		 * Configuration remains the desired size. Constrain only the live
		 * geometry so the desired dimensions survive temporary layout limits.
		 */
		final ChatboxBounds.Result effective = ChatboxBounds.resolve(client, slot, width, height);
		final int effectiveWidth = effective.getWidth();
		final int effectiveHeight = effective.getHeight();
		final boolean widthChanged = slot.getWidth() != effectiveWidth
				|| universe.getWidth() != effectiveWidth
				|| chatArea.getWidth() != effectiveWidth;
		final boolean heightChanged = slot.getHeight() != effectiveHeight
				|| universe.getHeight() != effectiveHeight;
		final boolean controlsChanged = !controlsLayout.matches(effectiveWidth);
		if (widthChanged || heightChanged || controlsChanged) {
			applyGeometry(
					slot,
					universe,
					chatArea,
					effectiveWidth,
					effectiveHeight,
					controlsChanged);
		}

		final ChatboxBackgroundService.Result backgroundResult =
				backgroundService.apply(chatArea, effectiveWidth, ChatboxGeometry.bodyHeight(effectiveHeight));

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
		 * resolve against the effective outer geometry.
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
			sideContainerLayout.reset();
			resizedLayoutApplied = false;
			return;
		}

		if (layout == ChatboxLayout.RESIZABLE_MODERN) {
			final SideContainerLayout.Result sideResult = sideContainerLayout.restoreNative();
			recordMutations(sideResult.getMutations());
			recordRevalidates(sideResult.getRevalidates());
		} else {
			sideContainerLayout.reset();
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

		restoreNativeUniverse(universe);

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

	@SuppressWarnings("deprecation")
	private void restoreNativeUniverse(Widget universe) {
		/*
		 * Native resizable layout keeps UNIVERSE as MINUS/MINUS, but the
		 * top-level layout resolves it to the stock chat slot rather than the
		 * client root. A plain revalidate resolves against the client root, so
		 * restore the native layout fields first and then pin only the resolved
		 * dimensions that script 113 normally establishes.
		 */
		universe.setSize(0, 0, WidgetSizeMode.MINUS, WidgetSizeMode.MINUS);
		universe.setForcedPosition(-1, -1);
		recordMutation();

		universe.revalidate();
		recordRevalidate();

		/*
		 * Doesn't revalidate UNIVERSE after these resolved-size setters.
		 * MINUS/MINUS would resolve against the client root again
		 * instead of retaining the native chat-slot dimensions.
		 */
		universe.setWidth(ChatboxGeometry.NATIVE_WIDTH);
		universe.setHeight(ChatboxGeometry.NATIVE_SLOT_HEIGHT);
		recordMutation();
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
