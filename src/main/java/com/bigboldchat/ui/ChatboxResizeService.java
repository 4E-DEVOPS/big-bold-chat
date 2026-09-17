package com.bigboldchat.ui;

import com.bigboldchat.Configurations;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
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
	private final Configurations config;
	private final PerformanceMetrics performanceMetrics;

	private boolean resizedLayoutApplied;

	public ChatboxResizeService(
		Client client,
		Configurations config,
		PerformanceMetrics performanceMetrics)
	{
		this.client = client;
		this.config = config;
		this.performanceMetrics = performanceMetrics;
	}

	/*
	 * ================================================================
	 * CHATBOX GEOMETRY
	 * ================================================================
	 */
	public ResizeResult applyConfiguredSize()
	{
		final long started =
				performanceMetrics != null
						? System.nanoTime()
						: 0L;

		if (!client.isResized())
		{
			if (resizedLayoutApplied)
			{
				restoreNativeSize();
			}

			return ResizeResult.NOT_APPLIED;
		}

		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);

		if (universe == null || chatArea == null)
		{
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		final Widget slot = universe.getParent();

		/*
		 * The fixed-layout slot uses Toplevel.CHAT_CONTAINER. Both modern and
		 * classic resizable layouts mount Chatbox.UNIVERSE under another slot.
		 */
		if (slot == null || slot.getId() == InterfaceID.Toplevel.CHAT_CONTAINER)
		{
			recordMissingWidgets();
			return ResizeResult.NOT_APPLIED;
		}

		final int width = config.chatboxWidth();
		final int height = config.chatboxHeight();

		final boolean widthChanged = slot.getWidth() != width || universe.getWidth() != width || chatArea.getWidth() != width;
		final boolean heightChanged = slot.getHeight() != height || universe.getHeight() != height;

		if (widthChanged || heightChanged)
		{
			applyGeometry(
					slot,
					universe,
					chatArea,
					width,
					height);
		}

		resizedLayoutApplied = true;

		recordApply(
				started,
				widthChanged,
				heightChanged);

		return new ResizeResult(
				true,
				widthChanged,
				heightChanged);
	}

	private void applyGeometry(
			Widget slot,
			Widget universe,
			Widget chatArea,
			int width,
			int height)
	{
		if (slot.getWidth() != width || slot.getHeight() != height)
		{
			slot.setSize(
					width,
					height);
			recordMutation();

			slot.revalidate();
			recordRevalidate();
		}

		if (universe.getWidth() != width || universe.getHeight() != height)
		{
			universe.setSize(
					width,
					height,
					WidgetSizeMode.ABSOLUTE,
					WidgetSizeMode.ABSOLUTE);
			universe.setForcedPosition(
					0,
					0);
			recordMutation();

			universe.revalidate();
			recordRevalidate();
		}

		if (chatArea.getWidth() != width)
		{
			chatArea.setOriginalWidth(width);
			recordMutation();
		}

		/*
		 * Re-resolve child geometry after changing the outer chatbox
		 * dimensions. Resizable chat children derive their final bounds
		 * from the updated parent hierarchy.
		 */
		revalidateChildren(universe);
	}

	/*
	 * ================================================================
	 * GEOMETRY HELPERS
	 * ================================================================
	 */
	private void revalidateChildren(Widget parent)
	{
		if (parent == null)
		{
			return;
		}

		revalidateWidgets(parent.getDynamicChildren());
		revalidateWidgets(parent.getStaticChildren());
		revalidateWidgets(parent.getNestedChildren());
	}

	private void revalidateWidgets(Widget[] widgets)
	{
		if (widgets == null)
		{
			return;
		}

		for (Widget widget : widgets)
		{
			if (widget == null)
			{
				continue;
			}

			widget.revalidate();
			recordRevalidate();
		}
	}

	/*
	 * ================================================================
	 * CHAT CONSTRUCTION
	 * ================================================================
	 */
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event != null && event.getScriptId() == ScriptID.BUILD_CHATBOX)
		{
			applyConfiguredSize();
		}
	}

	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event != null
				&& (event.getScriptId() == ScriptID.TOPLEVEL_REDRAW
				|| event.getScriptId() == ScriptID.MESSAGE_LAYER_OPEN))
		{
			applyConfiguredSize();
		}
	}

	/*
	 * ================================================================
	 * RESTORATION
	 * ================================================================
	 */
	public void restoreNativeSize()
	{
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);

		if (universe == null)
		{
			resizedLayoutApplied = false;
			return;
		}

		final Widget slot = universe.getParent();

		if (slot != null && slot.getId() != InterfaceID.Toplevel.CHAT_CONTAINER)
		{
			slot.setSize(
					NATIVE_WIDTH,
					NATIVE_HEIGHT);
			slot.setForcedPosition(
					-1,
					-1);
			slot.revalidate();
		}

		universe.setSize(
				0,
				0,
				WidgetSizeMode.MINUS,
				WidgetSizeMode.MINUS);
		universe.setForcedPosition(
				-1,
				-1);
		universe.revalidate();

		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);

		if (chatArea != null)
		{
			chatArea.setOriginalWidth(NATIVE_WIDTH);
		}

		revalidateChildren(universe);

		resizedLayoutApplied = false;

		if (performanceMetrics != null)
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
