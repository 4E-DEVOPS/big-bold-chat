package com.bigboldchat.layout;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Snapshots visible top-level interface rectangles that either constrain
 * plugin-managed geometry or occlude the chatbox as foreground content.
 */
final class InterfaceBounds {
	private final Rectangle canvas;
	private final List<Rectangle> obstacles;
	private final List<Rectangle> foregrounds;

	private InterfaceBounds(Rectangle canvas, List<Rectangle> obstacles, List<Rectangle> foregrounds) {
		this.canvas = canvas;
		this.obstacles = Collections.unmodifiableList(obstacles);
		this.foregrounds = Collections.unmodifiableList(foregrounds);
	}

	static InterfaceBounds capture(Client client) {
		final Rectangle canvas = new Rectangle(
				0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		final List<Rectangle> obstacles = new ArrayList<>(6);
		final List<Rectangle> foregrounds = new ArrayList<>(2);

		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelOsrsStretch.ORBS));
			addMounted(client, foregrounds, canvas, InterfaceID.ToplevelOsrsStretch.MAINMODAL);
			addMounted(client, foregrounds, canvas, InterfaceID.ToplevelOsrsStretch.FLOATER);
		} else if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_BACKGROUND));
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER));
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER));
			addLive(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER));
			add(obstacles, canvas, client.getWidget(InterfaceID.ToplevelPreEoc.ORBS));
			addMounted(client, foregrounds, canvas, InterfaceID.ToplevelPreEoc.MAINMODAL);
			addMounted(client, foregrounds, canvas, InterfaceID.ToplevelPreEoc.FLOATER);
		}

		return new InterfaceBounds(canvas, obstacles, foregrounds);
	}

	Rectangle getCanvas() {
		return canvas;
	}

	List<Rectangle> getObstacles() {
		return obstacles;
	}

	boolean intersectsForeground(Rectangle bounds) {
		if (bounds == null || bounds.isEmpty()) {
			return false;
		}

		for (Rectangle foreground : foregrounds) {
			if (bounds.intersects(foreground)) {
				return true;
			}
		}

		return false;
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

	private static void addMounted(
			Client client, List<Rectangle> foregrounds, Rectangle canvas, int componentId) {
		if (client.getComponentTable() == null || client.getComponentTable().get(componentId) == null) {
			return;
		}

		final Rectangle bounds = liveBounds(client.getWidget(componentId));
		if (bounds == null || !bounds.intersects(canvas)) {
			return;
		}

		foregrounds.add(bounds);
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
