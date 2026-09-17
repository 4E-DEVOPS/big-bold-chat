package com.bigboldchat.chatbox;

import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * Owns Chat XL's resizable-layout chatbox geometry.
 *
 * Width changes are applied before chat construction so text is measured against
 * the committed width. Height changes only update widget geometry and do not
 * require a chat rebuild.
 */
public final class ChatboxResizeService
{
	private static final int NATIVE_WIDTH = 519;
	private static final int NATIVE_HEIGHT = 165;

	private final Client client;
	private final PerformanceMetrics performanceMetrics;

	private boolean resizedLayoutApplied;

	public ChatboxResizeService(Client client, PerformanceMetrics performanceMetrics)
	{
		this.client = client;
		this.performanceMetrics = performanceMetrics;
	}

	/*
	 * ================================================================
	 * LAYOUT
	 * ================================================================
	 */
	ChatboxLayout getLayout()
	{
		final int topLevel = client.getTopLevelInterfaceId();

		if (topLevel == InterfaceID.TOPLEVEL)
		{
			return ChatboxLayout.FIXED;
		}

		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH)
		{
			return ChatboxLayout.RESIZABLE_CLASSIC;
		}

		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC)
		{
			return ChatboxLayout.RESIZABLE_MODERN;
		}

		return ChatboxLayout.UNKNOWN;
	}

	private Widget getSlot(ChatboxLayout layout)
	{
		switch (layout)
		{
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
	 * CHATBOX GEOMETRY
	 * ================================================================
	 */
	public ResizeResult applySize(int width, int height)
	{
		final long started = performanceMetrics != null
				? System.nanoTime()
				: 0L;

		final ChatboxLayout layout = getLayout();

		if (layout == ChatboxLayout.FIXED || layout == ChatboxLayout.UNKNOWN)
		{
			resizedLayoutApplied = false;
			return ResizeResult.NOT_APPLIED;
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		final Widget controls = client.getWidget(InterfaceID.Chatbox.CONTROLS);
		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);

		if (slot == null || universe == null || chatArea == null)
		{
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		/*
		 * Ignore transient layout swaps where Chatbox.UNIVERSE has not yet
		 * been mounted under the active resizable chat container.
		 */
		final Widget parent = universe.getParent();

		if (parent == null || parent.getId() != slot.getId())
		{
			return ResizeResult.NOT_APPLIED;
		}

		final boolean widthChanged = slot.getWidth() != width || universe.getWidth() != width || chatArea.getWidth() != width;
		final boolean heightChanged = slot.getHeight() != height || universe.getHeight() != height;
		final boolean controlsChanged = controls != null && controls.getWidth() != width;

		if (widthChanged || heightChanged || controlsChanged)
		{
			applyGeometry(slot, universe, chatArea, controls, width, height);
		}

		if (heightChanged)
		{
			updateScroll(scrollArea);
		}

		resizedLayoutApplied = true;

		recordApply(started, widthChanged, heightChanged);

		return new ResizeResult(true, widthChanged, heightChanged);
	}

	private void applyGeometry(
			Widget slot,
			Widget universe,
			Widget chatArea,
			Widget controls,
			int width,
			int height)
	{
		if (slot.getWidth() != width || slot.getHeight() != height)
		{
			slot.setSize(width, height);
			recordMutation();

			slot.revalidate();
			recordRevalidate();
		}

		if (universe.getWidth() != width || universe.getHeight() != height)
		{
			universe.setSize(width, height, WidgetSizeMode.ABSOLUTE, WidgetSizeMode.ABSOLUTE);
			universe.setForcedPosition(0, 0);
			recordMutation();

			universe.revalidate();
			recordRevalidate();
		}

		if (chatArea.getWidth() != width)
		{
			chatArea.setOriginalWidth(width);
			recordMutation();
		}

		if (controls != null && controls.getWidth() != width)
		{
			controls.setOriginalWidth(width);
			recordMutation();
		}
	}

	/*
	 * ================================================================
	 * GEOMETRY HELPERS
	 * ================================================================
	 */
	private void updateScroll(Widget scrollArea)
	{
		if (scrollArea == null)
		{
			return;
		}

		final int height = scrollArea.getHeight();
		final int scrollHeight = scrollArea.getScrollHeight();

		if (scrollHeight < height)
		{
			scrollArea.setScrollHeight(height);
			recordMutation();
		}

		if (scrollArea.getScrollY() != 0)
		{
			scrollArea.setScrollY(0);
			recordMutation();
		}

		scrollArea.revalidate();
		recordRevalidate();
	}

	/*
	 * ================================================================
	 * RESTORATION
	 * ================================================================
	 */
	public void restoreNativeSize()
	{
		final boolean wasApplied = resizedLayoutApplied;
		final ChatboxLayout layout = getLayout();

		if (layout == ChatboxLayout.FIXED || layout == ChatboxLayout.UNKNOWN)
		{
			resizedLayoutApplied = false;
			return;
		}

		final Widget slot = getSlot(layout);
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);

		if (slot == null || universe == null)
		{
			resizedLayoutApplied = false;
			return;
		}

		slot.setSize(NATIVE_WIDTH, NATIVE_HEIGHT);
		slot.setForcedPosition(-1, -1);
		slot.revalidate();

		universe.setSize(0, 0, WidgetSizeMode.MINUS, WidgetSizeMode.MINUS);
		universe.setForcedPosition(-1, -1);
		universe.revalidate();

		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);

		if (chatArea != null)
		{
			chatArea.setOriginalWidth(NATIVE_WIDTH);
			recordMutation();
		}

		final Widget controls = client.getWidget(InterfaceID.Chatbox.CONTROLS);

		if (controls != null)
		{
			controls.setOriginalWidth(NATIVE_WIDTH);
			recordMutation();

			controls.revalidate();
			recordRevalidate();
		}

		if (chatArea != null)
		{
			chatArea.revalidate();
			recordRevalidate();
		}

		resizedLayoutApplied = false;

		if (wasApplied && performanceMetrics != null)
		{
			performanceMetrics.recordResizeRestore();
		}
	}

	/*
	 * ================================================================
	 * PERFORMANCE HELPERS
	 * ================================================================
	 */
	private void recordApply(
		long started,
		boolean widthChanged,
		boolean heightChanged)
	{
		if (performanceMetrics == null)
		{
			return;
		}

		performanceMetrics.recordResizeApply(
				System.nanoTime() - started,
				widthChanged,
				heightChanged);
	}

	private void recordMissingWidgets()
	{
		if (performanceMetrics != null)
		{
			performanceMetrics.recordResizeMissingWidgets();
		}
	}

	private void recordMutation()
	{
		if (performanceMetrics != null)
		{
			performanceMetrics.recordWidgetMutation();
		}
	}

	private void recordRevalidate()
	{
		if (performanceMetrics != null)
		{
			performanceMetrics.recordRevalidate();
		}
	}

	public static final class ResizeResult
	{
		private static final ResizeResult NOT_APPLIED =
				new ResizeResult(
						false,
						false,
						false);

		private final boolean applied;
		private final boolean widthChanged;
		private final boolean heightChanged;

		private ResizeResult(
			boolean applied,
			boolean widthChanged,
			boolean heightChanged)
		{
			this.applied = applied;
			this.widthChanged = widthChanged;
			this.heightChanged = heightChanged;
		}

		public boolean isApplied()
		{
			return applied;
		}

		public boolean isWidthChanged()
		{
			return widthChanged;
		}

		public boolean isHeightChanged()
		{
			return heightChanged;
		}
	}
}
