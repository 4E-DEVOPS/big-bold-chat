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
 * Snapshots visible interface geometry used by chatbox and side-row collision handling.
 */
public final class InterfaceBounds {
	private final Rectangle canvas;
	private final List<Rectangle> obstacles;
	private final List<Obstacle> identifiedObstacles;
	private final List<Rectangle> foregrounds;

	private InterfaceBounds(Rectangle canvas, List<Rectangle> obstacles, List<Obstacle> identifiedObstacles, List<Rectangle> foregrounds) {
		this.canvas = canvas;
		this.obstacles = Collections.unmodifiableList(obstacles);
		this.identifiedObstacles = Collections.unmodifiableList(identifiedObstacles);
		this.foregrounds = Collections.unmodifiableList(foregrounds);
	}

	/**
	 * ================================================================
	 * CAPTURE
	 * ================================================================
	 */
	static InterfaceBounds capture(Client client) {
		return capture(client, null);
	}

	static InterfaceBounds capture(Client client, Overrides overrides) {
		final Rectangle canvas = new Rectangle(0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		final List<Rectangle> obstacles = new ArrayList<>(8);
		final List<Obstacle> identifiedObstacles = new ArrayList<>(8);
		final List<Rectangle> foregrounds = new ArrayList<>(2);

		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.CLASSIC_SIDE_MENU, canvas,
					client.getWidget(InterfaceID.ToplevelOsrsStretch.SIDE_MENU));
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.CLASSIC_MAP, canvas,
					client.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER));
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.CLASSIC_ORBS, canvas,
					client.getWidget(InterfaceID.ToplevelOsrsStretch.ORBS));
			addMountedObstacle(client, identifiedObstacles, foregrounds, ObstacleKey.CLASSIC_MAINMODAL, canvas,
					InterfaceID.ToplevelOsrsStretch.MAINMODAL);
			addMountedObstacle(client, identifiedObstacles, foregrounds, ObstacleKey.CLASSIC_FLOATER, canvas,
					InterfaceID.ToplevelOsrsStretch.FLOATER);
		} else if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.MODERN_SIDE_BACKGROUND, canvas,
					client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_BACKGROUND));
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.MODERN_SIDE_STATIC, canvas,
					client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER),
					overrides != null ? overrides.modernSideStatic : null);
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.MODERN_SIDE_MOVABLE, canvas,
					client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER),
					overrides != null ? overrides.modernSideMovable : null);
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.MODERN_SIDE_CONTAINER, canvas,
					client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER),
					overrides != null ? overrides.modernSideContainer : null);
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.MODERN_MAP, canvas,
					client.getWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER));
			addObstacle(obstacles, identifiedObstacles, ObstacleKey.MODERN_ORBS, canvas,
					client.getWidget(InterfaceID.ToplevelPreEoc.ORBS));
			addMountedObstacle(client, identifiedObstacles, foregrounds, ObstacleKey.MODERN_MAINMODAL, canvas,
					InterfaceID.ToplevelPreEoc.MAINMODAL);
			addMountedObstacle(client, identifiedObstacles, foregrounds, ObstacleKey.MODERN_FLOATER, canvas,
					InterfaceID.ToplevelPreEoc.FLOATER);
		}

		return new InterfaceBounds(canvas, obstacles, identifiedObstacles, foregrounds);
	}

	Rectangle getCanvas() {
		return canvas;
	}

	List<Rectangle> getObstacles() {
		return obstacles;
	}

	List<Obstacle> getIdentifiedObstacles() {
		return identifiedObstacles;
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

	/**
	 * ================================================================
	 * GEOMETRY HELPERS
	 * ================================================================
	 */
	public static long geometryFingerprint(Client client) {
		if (client == null) {
			return 0L;
		}

		final InterfaceBounds snapshot = capture(client);
		long hash = 17L;
		hash = mix(hash, client.getTopLevelInterfaceId());
		hash = mix(hash, snapshot.canvas);

		for (Obstacle obstacle : snapshot.identifiedObstacles) {
			hash = mix(hash, obstacle.key.ordinal());
			hash = mix(hash, obstacle.bounds);
		}

		return hash;
	}

	private static long mix(long hash, Rectangle bounds) {
		if (bounds == null) {
			return mix(hash, 0);
		}

		hash = mix(hash, bounds.x);
		hash = mix(hash, bounds.y);
		hash = mix(hash, bounds.width);
		return mix(hash, bounds.height);
	}

	private static long mix(long hash, int value) {
		return hash * 31L + value;
	}

	/*
	 * Returns rendered canvas-space widget bounds.
	 */
	public static Rectangle liveBounds(Widget widget) {
		if (widget == null || widget.isHidden()) {
			return null;
		}

		final Rectangle bounds = widget.getBounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0
				? new Rectangle(bounds)
				: null;
	}

	/**
	 * ================================================================
	 * OBSTACLE COLLECTION
	 * ================================================================
	 */
	private static void addMountedObstacle(Client client, List<Obstacle> identifiedObstacles,
			List<Rectangle> foregrounds, ObstacleKey key, Rectangle canvas, int componentId) {
		if (client.getComponentTable() == null || client.getComponentTable().get(componentId) == null) {
			return;
		}

		final Widget mount = client.getWidget(componentId);
		final Rectangle bounds = mountedContentBounds(mount, canvas);
		if (bounds == null || bounds.isEmpty()) {
			return;
		}

		final Rectangle foreground = new Rectangle(bounds);
		foregrounds.add(foreground);
		identifiedObstacles.add(new Obstacle(key, foreground));
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

	private static void addObstacle(List<Rectangle> obstacles, List<Obstacle> identifiedObstacles,
			ObstacleKey key, Rectangle canvas, Widget widget) {
		addObstacle(obstacles, identifiedObstacles, key, canvas, widget, null);
	}

	private static void addObstacle(List<Rectangle> obstacles, List<Obstacle> identifiedObstacles, ObstacleKey key,
			Rectangle canvas, Widget widget, Rectangle overrideBounds) {
		final Rectangle bounds = overrideBounds != null
				? new Rectangle(overrideBounds)
				: liveBounds(widget);
		if (bounds == null || !bounds.intersects(canvas)) {
			return;
		}

		final Rectangle captured = new Rectangle(bounds);
		obstacles.add(captured);
		identifiedObstacles.add(new Obstacle(key, captured));
	}

	/**
	 * ================================================================
	 * STATE TYPES
	 * ================================================================
	 */
	public static final class Overrides {
		private final Rectangle modernSideStatic;
		private final Rectangle modernSideMovable;
		private final Rectangle modernSideContainer;

		public Overrides(Rectangle modernSideStatic, Rectangle modernSideMovable, Rectangle modernSideContainer) {
			this.modernSideStatic = copy(modernSideStatic);
			this.modernSideMovable = copy(modernSideMovable);
			this.modernSideContainer = copy(modernSideContainer);
		}

		private static Rectangle copy(Rectangle bounds) {
			return bounds != null ? new Rectangle(bounds) : null;
		}
	}

	enum ObstacleKey {
		CLASSIC_SIDE_MENU,
		CLASSIC_MAP,
		CLASSIC_ORBS,
		CLASSIC_MAINMODAL,
		CLASSIC_FLOATER,
		MODERN_SIDE_BACKGROUND,
		MODERN_SIDE_STATIC,
		MODERN_SIDE_MOVABLE,
		MODERN_SIDE_CONTAINER,
		MODERN_MAP,
		MODERN_ORBS,
		MODERN_MAINMODAL,
		MODERN_FLOATER
	}

	static final class Obstacle {
		private final ObstacleKey key;
		private final Rectangle bounds;

		private Obstacle(ObstacleKey key, Rectangle bounds) {
			this.key = key;
			this.bounds = new Rectangle(bounds);
		}

		ObstacleKey getKey() {
			return key;
		}

		Rectangle getBounds() {
			return new Rectangle(bounds);
		}
	}
}
