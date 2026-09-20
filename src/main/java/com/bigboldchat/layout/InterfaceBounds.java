package com.bigboldchat.layout;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

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

		final Widget mount = client.getWidget(componentId);
		final Rectangle bounds = mountedContentBounds(mount, canvas);
		if (bounds == null || bounds.isEmpty()) {
			return;
		}

		foregrounds.add(bounds);
	}

	private static Rectangle mountedContentBounds(Widget mount, Rectangle canvas) {
		if (mount == null || mount.isHidden()) {
			return null;
		}

		final Rectangle mountBounds = liveBounds(mount);
		if (mountBounds == null) {
			return null;
		}

		final Rectangle clip = mountBounds.intersection(canvas);
		if (clip.isEmpty()) {
			return null;
		}

		Rectangle bounds = null;
		for (Widget root : mount.getNestedChildren()) {
			bounds = union(bounds, visibleContentBounds(root, clip));
		}

		return bounds;
	}

	private static Rectangle visibleContentBounds(Widget widget, Rectangle clip) {
		if (widget == null || widget.isHidden() || clip == null || clip.isEmpty()) {
			return null;
		}

		final Rectangle widgetBounds = widget.getBounds();
		if (widgetBounds == null || widgetBounds.width <= 0 || widgetBounds.height <= 0) {
			return null;
		}

		final Rectangle visible = widgetBounds.intersection(clip);
		if (visible.isEmpty()) {
			return null;
		}

		Rectangle bounds = widget.getType() != WidgetType.LAYER && widget.getOpacity() != 255
				? new Rectangle(visible)
				: null;

		for (Widget child : widget.getStaticChildren()) {
			bounds = union(bounds, visibleContentBounds(child, visible));
		}

		for (Widget child : widget.getDynamicChildren()) {
			bounds = union(bounds, visibleContentBounds(child, visible));
		}

		for (Widget child : widget.getNestedChildren()) {
			bounds = union(bounds, visibleContentBounds(child, visible));
		}

		return bounds;
	}

	private static Rectangle union(Rectangle first, Rectangle second) {
		if (second == null || second.isEmpty()) {
			return first;
		}

		if (first == null) {
			return new Rectangle(second);
		}

		first.add(second);
		return first;
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
