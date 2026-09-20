package com.bigboldchat.layout;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Snapshots visible top-level interface rectangles
 * that can constrain plugin-managed layout regions.
 */
final class InterfaceBounds {
	private final Rectangle canvas;
	private final List<Rectangle> obstacles;

	private InterfaceBounds(Rectangle canvas, List<Rectangle> obstacles) {
		this.canvas = canvas;
		this.obstacles = Collections.unmodifiableList(obstacles);
	}

	static InterfaceBounds capture(Client client) {
		final Rectangle canvas = new Rectangle(
				0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		final List<Rectangle> obstacles = new ArrayList<>(6);

		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelOsrsStretch.ORBS));
		} else if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_BACKGROUND));
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER));
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER));
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.ORBS));
		}

		return new InterfaceBounds(canvas, obstacles);
	}

	Rectangle getCanvas() {
		return canvas;
	}

	List<Rectangle> getObstacles() {
		return obstacles;
	}

	static Rectangle liveBounds(Widget widget) {
		if (widget == null || widget.isHidden() || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
			return null;
		}

		int x = widget.getRelativeX();
		int y = widget.getRelativeY();
		for (Widget parent = widget.getParent(); parent != null; parent = parent.getParent()) {
			x += parent.getRelativeX();
			y += parent.getRelativeY();
		}

		return new Rectangle(x, y, widget.getWidth(), widget.getHeight());
	}

	private static void addLive(List<Rectangle> obstacles, Rectangle canvas, Widget widget) {
		final Rectangle bounds = liveBounds(widget);
		if (bounds == null || !bounds.intersects(canvas)) {
			return;
		}

		obstacles.add(bounds);
	}

	private static void add(List<Rectangle> obstacles, Rectangle canvas, Widget widget) {
		if (widget == null || widget.isHidden()) {
			return;
		}

		final Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0 || !bounds.intersects(canvas)) {
			return;
		}

		obstacles.add(new Rectangle(bounds));
	}
}
