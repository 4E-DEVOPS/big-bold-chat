package com.bigboldchat.chatbox;

import java.awt.Point;
import java.awt.Rectangle;

import com.bigboldchat.layout.InterfaceBounds;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Separates the user's movable-chatbox placement from ChatXL's temporary effective geometry.
 */
public final class ChatboxPlacement {
	private static final int EDGE_TOLERANCE = 1;

	private static final String RUNELITE_CONFIG_GROUP = "runelite";
	private static final String PREFERRED_LOCATION_SUFFIX = "_preferredLocation";
	private static final String PREFERRED_POSITION_SUFFIX = "_preferredPosition";
	private static final String ORIGIN_X_SUFFIX = "_originX";
	private static final String ORIGIN_Y_SUFFIX = "_originY";
	private static final String CLASSIC_OVERLAY = "RESIZABLE_VIEWPORT_CHATBOX_PARENT";
	private static final String MODERN_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_CHATBOX_PARENT";

	private final Client client;
	private final ConfigManager configManager;
	private final OverlayManager overlayManager;

	private Rectangle manualBounds;

	public ChatboxPlacement(Client client, ConfigManager configManager, OverlayManager overlayManager) {
		this.client = client;
		this.configManager = configManager;
		this.overlayManager = overlayManager;
	}

	/**
	 * ================================================================
	 * PLACEMENT CAPTURE
	 * ================================================================
	 */
	public State capture(Widget slot, int configuredWidth, int configuredHeight) {
		final Rectangle liveBounds = InterfaceBounds.liveBounds(slot);
		if (liveBounds == null) {
			return State.unbounded(configuredWidth, configuredHeight);
		}

		final Rectangle canvas = new Rectangle(0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		if (canvas.width <= 0 || canvas.height <= 0) {
			reset();
			return State.unbounded(configuredWidth, configuredHeight);
		}

		final String overlayName = overlayName();
		if (overlayName == null || configManager == null) {
			resetManual();
			return nativePlacement(liveBounds, canvas, configuredWidth, configuredHeight);
		}

		final OverlayPosition preferredPosition = configManager.getConfiguration(
				RUNELITE_CONFIG_GROUP, overlayName + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		final String preferredLocation = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + PREFERRED_LOCATION_SUFFIX);

		if (usesRuntimeDrag(overlayName, preferredPosition, preferredLocation)) {
			return transientPlacement(liveBounds, configuredWidth, configuredHeight);
		}

		if (preferredPosition == null) {
			if (preferredLocation == null || preferredLocation.isEmpty()) {
				resetManual();
				return nativePlacement(liveBounds, canvas, configuredWidth, configuredHeight);
			}

			return manualPlacement(liveBounds, canvas, overlayName, preferredLocation, configuredWidth, configuredHeight);
		}

		resetManual();

		/*
		 * Snapped placement follows RuneLite's live host while ChatXL derives the desired rectangle.
		 */
		final HorizontalAnchor horizontalAnchor = horizontalAnchor(preferredPosition);
		final VerticalAnchor verticalAnchor = verticalAnchor(preferredPosition);
		final Rectangle desired = snapBounds(
				liveBounds, canvas, configuredWidth, configuredHeight, horizontalAnchor, verticalAnchor);

		return new State(desired, true);
	}

	public void reset() {
		resetManual();
	}

	/**
	 * ================================================================
	 * OVERLAY PLACEMENT
	 * ================================================================
	 */
	private boolean usesRuntimeDrag(String overlayName, OverlayPosition configuredPosition, String configuredLocation) {
		final Overlay overlay = findOverlay(overlayName);
		if (overlay == null) {
			return false;
		}

		final Point runtimeLocation = overlay.getPreferredLocation();
		final OverlayPosition runtimePosition = overlay.getPreferredPosition();

		if (configuredPosition != null) {
			return runtimePosition != configuredPosition && runtimeLocation != null;
		}

		if (configuredLocation == null || configuredLocation.isEmpty()) {
			return runtimeLocation != null || runtimePosition != null;
		}

		/*
		 * Persisted manual placement remains authoritative until the first stable sample is captured.
		 */
		if (manualBounds == null || runtimePosition != null) {
			return false;
		}

		final int[] configured = parseLocation(configuredLocation);
		if (configured == null) {
			return false;
		}

		return runtimeLocation != null && (runtimeLocation.x != configured[0] || runtimeLocation.y != configured[1]);
	}

	/*
	 * Primes RuneLite's movable chatbox overlay with temporary host geometry without
	 * changing or persisting the user's preferred position/location.
	 */
	public void applyTemporaryHostBounds(Rectangle hostBounds, boolean updateLocation) {
		if (hostBounds == null) {
			return;
		}

		final Overlay overlay = findOverlay(overlayName());
		if (!isManagedOverlay(overlay)) {
			return;
		}

		final Rectangle overlayBounds = overlay.getBounds();
		if (overlayBounds == null) {
			return;
		}

		overlayBounds.setSize(hostBounds.width, hostBounds.height);
		if (updateLocation) {
			overlayBounds.setLocation(hostBounds.x, hostBounds.y);
		}
	}

	/*
	 * Applies a selected temporary host anchor once. Subsequent frame/layout
	 * ownership remains with RuneLite's WidgetOverlay.
	 */
	public boolean moveTemporaryHost(Widget slot, Rectangle hostBounds) {
		if (slot == null || hostBounds == null || !isManagedOverlay(findOverlay(overlayName()))) {
			return false;
		}

		final Rectangle liveBounds = InterfaceBounds.liveBounds(slot);
		if (liveBounds == null || liveBounds.x == hostBounds.x && liveBounds.y == hostBounds.y) {
			return false;
		}

		final Widget parent = slot.getParent();
		final Rectangle parentBounds = parent != null ? parent.getBounds() : null;
		if (parentBounds != null) {
			slot.setForcedPosition(hostBounds.x - parentBounds.x, hostBounds.y - parentBounds.y);
		} else {
			slot.setForcedPosition(hostBounds.x, hostBounds.y);
		}

		return true;
	}

	private static boolean isManagedOverlay(Overlay overlay) {
		return overlay != null && (overlay.getPreferredLocation() != null || overlay.getPreferredPosition() != null);
	}

	private Overlay findOverlay(String name) {
		if (overlayManager == null || name == null) {
			return null;
		}

		final Overlay[] match = new Overlay[1];
		overlayManager.anyMatch(overlay -> {
			if (name.equals(overlay.getName())) {
				match[0] = overlay;
				return true;
			}

			return false;
		});
		return match[0];
	}

	private static State transientPlacement(Rectangle liveBounds, int width, int height) {
		return new State(new Rectangle(liveBounds.x, liveBounds.y, width, height), true);
	}

	private static State nativePlacement(Rectangle liveBounds, Rectangle canvas, int configuredWidth, int configuredHeight) {
		/*
		 * Native resizable placement stays bottom-anchored to the canvas.
		 */
		final Rectangle desired = new Rectangle(
				liveBounds.x, canvas.y + canvas.height - configuredHeight, configuredWidth, configuredHeight);
		return new State(desired, true);
	}

	private State manualPlacement(Rectangle liveBounds, Rectangle canvas, String overlayName, String preferredLocation,
			int configuredWidth, int configuredHeight) {
		/*
		 * Restore the first manual sample from RuneLite's saved origin-relative location, then follow the live host.
		 */
		if (manualBounds == null) {
			final Rectangle restored = configuredManualBounds(
					canvas, overlayName, preferredLocation, configuredWidth, configuredHeight);
			if (restored != null) {
				manualBounds = restored;
				return new State(manualBounds, true, restored.x - liveBounds.x, restored.y - liveBounds.y);
			}

			manualBounds = new Rectangle(liveBounds.x, liveBounds.y, configuredWidth, configuredHeight);
		} else {
			manualBounds.setBounds(liveBounds.x, liveBounds.y, configuredWidth, configuredHeight);
		}

		return new State(manualBounds, true);
	}

	private Rectangle configuredManualBounds(Rectangle canvas, String overlayName, String preferredLocation, int width, int height) {
		final int[] location = parseLocation(preferredLocation);
		if (location == null) {
			return null;
		}

		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + ORIGIN_Y_SUFFIX);

		return new Rectangle(
				horizontalOrigin(canvas.x, canvas.width, location[0], originX),
				verticalOrigin(canvas.y, canvas.height, location[1], originY),
				width,
				height);
	}

	private static int[] parseLocation(String value) {
		if (value == null) {
			return null;
		}

		final int separator = value.indexOf(':');
		if (separator <= 0 || separator >= value.length() - 1) {
			return null;
		}

		try {
			return new int[] {
					Integer.parseInt(value.substring(0, separator)),
					Integer.parseInt(value.substring(separator + 1))
			};
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static int horizontalOrigin(int canvasStart, int canvasSize, int offset, String origin) {
		if ("RIGHT".equals(origin)) {
			return canvasStart + canvasSize + offset;
		}
		if ("CENTER".equals(origin)) {
			return canvasStart + canvasSize / 2 + offset;
		}

		return canvasStart + offset;
	}

	private static int verticalOrigin(int canvasStart, int canvasSize, int offset, String origin) {
		if ("BOTTOM".equals(origin)) {
			return canvasStart + canvasSize + offset;
		}
		if ("CENTER".equals(origin)) {
			return canvasStart + canvasSize / 2 + offset;
		}

		return canvasStart + offset;
	}

	private void resetManual() {
		manualBounds = null;
	}

	private String overlayName() {
		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			return CLASSIC_OVERLAY;
		}
		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			return MODERN_OVERLAY;
		}

		return null;
	}

	/**
	 * ================================================================
	 * ANCHOR GEOMETRY
	 * ================================================================
	 */
	private static HorizontalAnchor horizontalAnchor(OverlayPosition preferredPosition) {
		switch (preferredPosition) {
			case TOP_RIGHT:
			case BOTTOM_RIGHT:
			case ABOVE_CHATBOX_RIGHT:
			case CANVAS_TOP_RIGHT:
				return HorizontalAnchor.RIGHT;
			case TOP_CENTER:
				return HorizontalAnchor.CENTER;
			default:
				return HorizontalAnchor.LEFT;
		}
	}

	private static VerticalAnchor verticalAnchor(OverlayPosition preferredPosition) {
		switch (preferredPosition) {
			case BOTTOM_LEFT:
			case BOTTOM_RIGHT:
			case ABOVE_CHATBOX_RIGHT:
				return VerticalAnchor.BOTTOM;
			default:
				return VerticalAnchor.TOP;
		}
	}

	private static Rectangle snapBounds(Rectangle liveBounds, Rectangle canvas, int width, int height,
			HorizontalAnchor horizontalAnchor, VerticalAnchor verticalAnchor) {
		int x = anchoredX(liveBounds, width, horizontalAnchor);
		int y = anchoredY(liveBounds, height, verticalAnchor);

		if (horizontalAnchor == HorizontalAnchor.LEFT && touchesLeft(liveBounds, canvas)) {
			x = canvas.x;
		} else if (horizontalAnchor == HorizontalAnchor.RIGHT && touchesRight(liveBounds, canvas)) {
			x = canvas.x + canvas.width - width;
		}

		if (verticalAnchor == VerticalAnchor.TOP && touchesTop(liveBounds, canvas)) {
			y = canvas.y;
		} else if (verticalAnchor == VerticalAnchor.BOTTOM && touchesBottom(liveBounds, canvas)) {
			y = canvas.y + canvas.height - height;
		}

		return new Rectangle(x, y, width, height);
	}

	private static boolean touchesLeft(Rectangle bounds, Rectangle canvas) {
		return Math.abs(bounds.x - canvas.x) <= EDGE_TOLERANCE;
	}

	private static boolean touchesRight(Rectangle bounds, Rectangle canvas) {
		return Math.abs(bounds.x + bounds.width - (canvas.x + canvas.width)) <= EDGE_TOLERANCE;
	}

	private static boolean touchesTop(Rectangle bounds, Rectangle canvas) {
		return Math.abs(bounds.y - canvas.y) <= EDGE_TOLERANCE;
	}

	private static boolean touchesBottom(Rectangle bounds, Rectangle canvas) {
		return Math.abs(bounds.y + bounds.height - (canvas.y + canvas.height)) <= EDGE_TOLERANCE;
	}

	private static int anchoredX(Rectangle bounds, int width, HorizontalAnchor anchor) {
		switch (anchor) {
			case RIGHT:
				return bounds.x + bounds.width - width;
			case CENTER:
				return bounds.x + (bounds.width - width) / 2;
			default:
				return bounds.x;
		}
	}

	private static int anchoredY(Rectangle bounds, int height, VerticalAnchor anchor) {
		switch (anchor) {
			case BOTTOM:
				return bounds.y + bounds.height - height;
			case CENTER:
				return bounds.y + (bounds.height - height) / 2;
			default:
				return bounds.y;
		}
	}

	/**
	 * ================================================================
	 * STATE TYPES
	 * ================================================================
	 */
	private enum HorizontalAnchor {
		LEFT,
		CENTER,
		RIGHT
	}

	private enum VerticalAnchor {
		TOP,
		CENTER,
		BOTTOM
	}

	public static final class State {
		private final Rectangle desiredBounds;
		private final boolean bounded;
		private final int hostDeltaX;
		private final int hostDeltaY;

		private State(Rectangle desiredBounds, boolean bounded) {
			this(desiredBounds, bounded, 0, 0);
		}

		private State(Rectangle desiredBounds, boolean bounded, int hostDeltaX, int hostDeltaY) {
			this.desiredBounds = new Rectangle(desiredBounds);
			this.bounded = bounded;
			this.hostDeltaX = hostDeltaX;
			this.hostDeltaY = hostDeltaY;
		}

		private static State unbounded(int width, int height) {
			return new State(new Rectangle(0, 0, width, height), false);
		}

		public Rectangle getDesiredBounds() {
			return new Rectangle(desiredBounds);
		}

		public boolean isBounded() {
			return bounded;
		}

		public boolean requiresHostReposition() {
			return hostDeltaX != 0 || hostDeltaY != 0;
		}

		public int getHostDeltaX() {
			return hostDeltaX;
		}

		public int getHostDeltaY() {
			return hostDeltaY;
		}
	}
}
