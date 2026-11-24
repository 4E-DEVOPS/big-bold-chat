package com.bigboldchat.chatbox;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Objects;

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
	private static final int SNAPPED_EDGE_TOLERANCE = 2;

	private static final String RUNELITE_CONFIG_GROUP = "runelite";
	private static final String PREFERRED_LOCATION_SUFFIX = "_preferredLocation";
	private static final String PREFERRED_POSITION_SUFFIX = "_preferredPosition";
	private static final String ORIGIN_X_SUFFIX = "_originX";
	private static final String ORIGIN_SUFFIX = "_origin";
	private static final String ORIGIN_Y_SUFFIX = "_originY";
	private static final String CLASSIC_OVERLAY = "RESIZABLE_VIEWPORT_CHATBOX_PARENT";
	private static final String MODERN_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_CHATBOX_PARENT";

	private final Client client;
	private final ConfigManager configManager;
	private final OverlayManager overlayManager;

	private Rectangle manualBounds;

	private Overlay temporaryHostOverlay;
	private Point originalPreferredLocation;
	private Point appliedPreferredLocation;

	/* Preserve the original saved location and axes when acquiring a temporary host.
	 * An unrelated overlay reset can re-save all overlays and clear this preference. */
	private String temporarySavedOrigin;
	private String temporarySavedOriginX;
	private String temporarySavedOriginY;

	// Only an observed RuneLite save may reconcile a one-pixel render/save gap.
	private boolean savedPlacementHandoffPending;
	// A moved widget without a matching native save must not reuse stale manual config.
	private boolean unconfirmedNativeDrop;

	/* A settled unsaved drag may supply a temporary in-memory fit reference,
	 * but cannot authorize copying stale configuration or inventing a save. */
	private Rectangle recoveredRuntimeFitBounds;
	/* Physical host at the recovered drag release. The configured-size fit
	 * reference may differ in size and X/Y after RIGHT/BOTTOM edge anchoring.
	 * RuneLite still renders the released host, not that configured rectangle. */
	private Rectangle recoveredRuntimeFitHostBounds;
	private Point recoveredRuntimeFitPreferred;
	/* Only use RuneLite's runtime origin for an unsaved drop when its
	 * preferred location independently matches the real release sample.
	 * A fitted host may already have moved between release and recovery. */
	private boolean recoveredRuntimeOriginVerified;

	private String lastHandoffDecision = "NONE";

	/* OverlayManager saves preferredLocation and origins separately. Track the
	 * complete persisted placement rather than interpreting each ConfigChanged
	 * callback as a new user drag. Never include temporary runtime geometry. */
	private String lastAdoptedNativeSave;
	private String normalRestoreSignature;

	private static final int SAVED_HANDOFF_TOLERANCE = 1;

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
		final Rectangle hostBounds = hostBounds(slot);
		if (hostBounds == null) {
			return State.unbounded(configuredWidth, configuredHeight);
		}

		final Rectangle canvas = new Rectangle(0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		if (canvas.width <= 0 || canvas.height <= 0) {
			reset();
			return State.unbounded(configuredWidth, configuredHeight);
		}

		if (recoveredRuntimeFitBounds != null) {
			final Overlay recoveredOverlay = findOverlay(overlayName());
			if (temporaryHostOverlay == null && (recoveredOverlay == null || !recoveredRuntimeFitPreferred.equals(recoveredOverlay.getPreferredLocation()))) {
				clearRecoveredRuntimeFit();
			} else {
				return new State(new Rectangle(recoveredRuntimeFitBounds.x, recoveredRuntimeFitBounds.y, configuredWidth, configuredHeight), true, PlacementMode.MANUAL);
			}
		}

		final String overlayName = overlayName();
		if (overlayName == null || configManager == null) {
			resetManual();
			return nativePlacement(hostBounds, canvas, configuredWidth, configuredHeight);
		}

		final OverlayPosition preferredPosition = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		final String preferredLocation = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + PREFERRED_LOCATION_SUFFIX);

		if (unconfirmedNativeDrop || usesRuntimeDrag(overlayName, preferredPosition, preferredLocation)) {
			return transientPlacement(hostBounds, configuredWidth, configuredHeight);
		}

		if (preferredPosition == null) {
			if (preferredLocation == null || preferredLocation.isEmpty()) {
				resetManual();
				return nativePlacement(hostBounds, canvas, configuredWidth, configuredHeight);
			}

			return manualPlacement(hostBounds, canvas, overlayName, preferredLocation, configuredWidth, configuredHeight);
		}

		resetManual();

		/* Saved preferredPosition identifies snapped ownership, not the physical anchor.
		 * Use the native overlay: scripts can briefly rebuild the widget at a different size. */
		final Rectangle nativeBounds = nativeOverlayBounds();
		final Rectangle reference = nativeBounds != null
				? nativeBounds
				: hostBounds;
		final HorizontalAnchor horizontalAnchor = liveHorizontalSnapAnchor(reference, canvas);
		final VerticalAnchor verticalAnchor = liveVerticalSnapAnchor(reference, canvas);
		final Rectangle desired = new Rectangle(anchoredX(reference, configuredWidth, horizontalAnchor), anchoredY(reference, configuredHeight, verticalAnchor), configuredWidth, configuredHeight);

		return new State(desired, true, PlacementMode.SNAPPED);
	}

	/*
	 * Derives speculative fit geometry from one stable placement sample. Manual
	 * candidates must not rewrite manualBounds while the fit solver explores widths.
	 */
	public State resizeForFit(Widget slot, State base, int width, int height) {
		if (base == null) {
			return capture(slot, width, height);
		}

		/* The dialogue reference center is immutable across speculative sizes.
		 * Canvas edges may restrict the preferred rectangle, but movable
		 * obstacles are resolved separately by ChatboxBounds. */
		if (base.fitCenter != null) {
			return new State(centeredDialogueBounds(base.fitCenter, width, height), base.bounded, PlacementMode.MANUAL, base.fitCenter);
		}

		// Preserve the selected corner when fitting a dialogue with a corner reference.
		if (base.droppedOrigin) {
			// A dialogue fit expands and contracts about one immutable corner.
			final int x = base.rightAnchored
					? base.desiredBounds.x + base.desiredBounds.width - width
					: base.desiredBounds.x;
			final int y = base.bottomAnchored
					? base.desiredBounds.y + base.desiredBounds.height - height
					: base.desiredBounds.y;
			return new State(new Rectangle(x, y, width, height), base.bounded, PlacementMode.MANUAL, true, base.bottomAnchored, base.rightAnchored);
		}

		if (base.mode == PlacementMode.MANUAL) {
			final Rectangle canvas = new Rectangle(0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
			if (canvas.width <= 0 || canvas.height <= 0) {
				return State.unbounded(width, height);
			}

			final Rectangle desired = edgeAnchoredBounds(base.desiredBounds, canvas, width, height);
			return new State(desired, base.bounded, PlacementMode.MANUAL);
		}

		if (base.mode == PlacementMode.RUNTIME_DRAG) {
			final Rectangle desired = base.getDesiredBounds();
			desired.setSize(width, height);
			return new State(desired, base.bounded, PlacementMode.RUNTIME_DRAG);
		}

		return capture(slot, width, height);
	}

	/*
	 * Reconstructs placement from ChatXL's logical desired rectangle instead of the
	 * temporarily clipped RuneLite host. This keeps adaptive dialogue anchoring
	 * stable while the physical host follows only the collision-safe viewport.
	 */
	public State captureLogical(Rectangle logicalBounds, int width, int height) {
		if (logicalBounds == null) {
			return State.unbounded(width, height);
		}

		final Rectangle canvas = new Rectangle(0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		if (canvas.width <= 0 || canvas.height <= 0) {
			return State.unbounded(width, height);
		}

		final PlacementMode mode = currentMode();
		switch (mode) {
			case MANUAL:
				return new State(edgeAnchoredBounds(logicalBounds, canvas, width, height), true, PlacementMode.MANUAL);
			case RUNTIME_DRAG:
				return transientPlacement(logicalBounds, width, height);
			case SNAPPED:
				/* While a dialogue fit owns the logical rectangle, an old
				 * preferredPosition cannot relocate it. The live native
				 * renderer is responsible for snapped X/Y after resizing. */
				return new State(new Rectangle(logicalBounds.x, logicalBounds.y, width, height), true, PlacementMode.SNAPPED);
			case NATIVE:
				return nativePlacement(logicalBounds, canvas, width, height);
			case UNBOUNDED:
			default:
				return State.unbounded(width, height);
		}
	}

	/*
	 * A user dragged an already-expanded dialogue. The dropped rectangle, rather
	 * than an imaginary configured-size rectangle at the same X/Y, is the origin
	 * for subsequent candidate widths/heights until dialogue fitting ends.
	 */
	public State captureDroppedDialogue(Rectangle droppedDialogue, int configuredWidth, int configuredHeight) {
		if (droppedDialogue == null) {
			return State.unbounded(configuredWidth, configuredHeight);
		}

		return new State(new Rectangle(droppedDialogue.x, droppedDialogue.y, configuredWidth, configuredHeight), true, PlacementMode.MANUAL, true);
	}

	/*
	 * Immutable-corner fit seed. The rectangle is the confirmed user placement,
	 * NOT a collision-clipped viewport or the previously selected fit.
	 * Passing all four corner combinations uses the same candidate search.
	 */
	public State captureCornerAnchoredDialogue(Rectangle reference, boolean anchorRight, boolean anchorBottom) {
		if (reference == null) {
			return State.unbounded(0, 0);
		}

		return new State(reference, true, PlacementMode.MANUAL, true, anchorBottom, anchorRight);
	}

	/* Every candidate uses the same dialogue center. Canvas limits may constrain
	 * desired geometry, while obstacles only affect the effective viewport. */
	public State captureCenteredDialogue(Point referenceCenter, int width, int height) {
		if (referenceCenter == null) {
			return State.unbounded(width, height);
		}
		return new State(centeredDialogueBounds(referenceCenter, width, height), true, PlacementMode.MANUAL, referenceCenter);
	}

	public Rectangle centeredDialogueBounds(Point referenceCenter, int width, int height) {
		final int canvasWidth = Math.max(0, client.getCanvasWidth());
		final int canvasHeight = Math.max(0, client.getCanvasHeight());
		int x = referenceCenter.x - width / 2;
		int y = referenceCenter.y - height / 2;
		/* Canvas attachment is not obstacle avoidance. Preserve the proposed
		 * size while growing inward from a canvas edge, if it fits the canvas. */
		if (canvasWidth > 0 && width <= canvasWidth) {
			x = Math.max(0, Math.min(x, canvasWidth - width));
		}
		if (canvasHeight > 0 && height <= canvasHeight) {
			y = Math.max(0, Math.min(y, canvasHeight - height));
		}
		return new Rectangle(x, y, width, height);
	}

	// Pre-dialogue normal placement, independent of temporary dialogue geometry.
	public static final class NormalPlacement {
		private final String overlayName;
		private final Point center;
		private final Dimension size;
		private final String savedLocation;
		private final OverlayPosition snappedPosition;
		/* The native preferredLocation is relative to these axes. Never
		 * reinterpret a pre-dialogue save with origins changed by a drag. */
		private final String origin;
		private final String originX;
		private final String originY;

		private NormalPlacement(String overlayName, Rectangle bounds, String savedLocation, String origin, String originX, String originY, OverlayPosition snappedPosition) {
			this.overlayName = overlayName;
			this.center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
			this.size = bounds.getSize();
			this.savedLocation = savedLocation;
			this.origin = origin;
			this.originX = originX;
			this.originY = originY;
			this.snappedPosition = snappedPosition;
		}

		public Point getCenter() {
			return new Point(center);
		}

		// Reconstruct the original rectangle from the recorded center and size.
		public Rectangle getBounds() {
			return boundsAtSize(size.width, size.height);
		}

		// Retain the original center even if the configured chatbox size changes.
		private Rectangle boundsAtSize(int width, int height) {
			return new Rectangle(center.x - width / 2, center.y - height / 2, width, height);
		}

		public String getSavedLocation() {
			return savedLocation;
		}

		public boolean wasSnapped() {
			return snappedPosition != null;
		}

		/* The normal placement remains relative to its original RuneLite axes
		 * across a canvas resize. Dialogue fit geometry must not rewrite them. */
		public Rectangle canvasRelativeBounds(int oldCanvasWidth, int oldCanvasHeight, int width, int height, int canvasWidth, int canvasHeight) {
			if (oldCanvasWidth <= 0 || oldCanvasHeight <= 0 || width <= 0 || height <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
				return null;
			}
			final Rectangle original = getBounds();
			int shiftX = 0;
			int shiftY = 0;
			if (savedLocation != null) {
				shiftX = horizontalOrigin(0, canvasWidth, 0, originX) - horizontalOrigin(0, oldCanvasWidth, 0, originX);
				shiftY = verticalOrigin(0, canvasHeight, 0, originY) - verticalOrigin(0, oldCanvasHeight, 0, originY);
			} else if (snappedPosition != null) {
				final String name = snappedPosition.name();
				shiftX = name.endsWith("RIGHT") ? canvasWidth - oldCanvasWidth
						: name.contains("CENTER") ? canvasWidth / 2 - oldCanvasWidth / 2 : 0;
				shiftY = name.startsWith("BOTTOM") ? canvasHeight - oldCanvasHeight
						: name.startsWith("CENTER") ? canvasHeight / 2 - oldCanvasHeight / 2 : 0;
			}
			int x = original.x + shiftX + (original.width - width) / 2;
			int y = original.y + shiftY + (original.height - height) / 2;
			/* Physical canvas-edge attachment takes priority over the stored
			 * axis, including for native placements without a saved offset. */
			if (original.x == 0) {
				x = 0;
			} else if (original.x + original.width == oldCanvasWidth) {
				x = canvasWidth - width;
			}
			if (original.y == 0) {
				y = 0;
			} else if (original.y + original.height == oldCanvasHeight) {
				y = canvasHeight - height;
			}
			x = Math.max(0, Math.min(x, Math.max(0, canvasWidth - width)));
			y = Math.max(0, Math.min(y, Math.max(0, canvasHeight - height)));
			return new Rectangle(x, y, width, height);
		}
	}

	/* The immutable pre-dialogue center is independent of RuneLite's native
	 * placement mode. Only capture a normal, physically aligned host: MANUAL
	 * needs its saved origin/offset, while SNAPPED and NATIVE must match the
	 * live overlay bounds. Never capture a temporary fit or pending drag. */
	public NormalPlacement captureNormalPlacement(Rectangle normalBounds) {
		final PlacementMode mode = currentMode();
		if (normalBounds == null || normalBounds.width <= 0 || normalBounds.height <= 0 || temporaryHostOverlay != null || recoveredRuntimeFitBounds != null || unconfirmedNativeDrop || mode != PlacementMode.MANUAL && mode != PlacementMode.SNAPPED && mode != PlacementMode.NATIVE) {
			return null;
		}

		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || overlay == null || configManager == null) {
			return null;
		}

		final String origin = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX);
		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX);
		if (mode == PlacementMode.SNAPPED || mode == PlacementMode.NATIVE) {
			final OverlayPosition snappedPosition = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
			final Rectangle nativeBounds = overlay.getBounds();
			if ((mode == PlacementMode.SNAPPED && snappedPosition == null) || (mode == PlacementMode.NATIVE && snappedPosition != null) || nativeBounds == null || !normalBounds.equals(nativeBounds)) {
				return null;
			}
			return new NormalPlacement(name, normalBounds, null, origin, originX, originY, snappedPosition);
		}

		final String location = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX);
		final int[] saved = parseLocation(location);
		final Point runtime = overlay.getPreferredLocation();
		final Dimension real = client.getRealDimensions();
		if (saved == null || runtime == null || real == null || real.width <= 0 || real.height <= 0 || Math.abs(saved[0] - runtime.x) > SAVED_HANDOFF_TOLERANCE || Math.abs(saved[1] - runtime.y) > SAVED_HANDOFF_TOLERANCE) {
			return null;
		}

		final int actualX = horizontalOrigin(0, real.width, saved[0], originX);
		final int actualY = verticalOrigin(0, real.height, saved[1], originY);
		if (Math.abs(actualX - normalBounds.x) > SAVED_HANDOFF_TOLERANCE || Math.abs(actualY - normalBounds.y) > SAVED_HANDOFF_TOLERANCE) {
			return null;
		}

		return new NormalPlacement(name, normalBounds, location, origin, originX, originY, null);
	}

	/* Exact RuneLite anchor identity captured before dialogue fitting. This is
	 * NOT inferred from the physical corner: users can move snap boxes. */
	public String normalSnapAnchor(NormalPlacement normal) {
		return normal != null && normal.snappedPosition != null ? normal.snappedPosition.name() : "-";
	}

	/* Only SNAPPED and native AUTO have a mode to recover. Manual placement
	 * is already restored by the existing, independently verified path. */
	public boolean normalModeRecoveryEligible(NormalPlacement normal) {
		return normal != null && normal.overlayName.equals(overlayName()) && normal.savedLocation == null;
	}

	/* Config and runtime must agree on the same placement mode. Neither the
	 * old corner name nor proximity to a canvas edge proves restored geometry. */
	public boolean originalNormalModeMatches(NormalPlacement normal) {
		if (!normalModeRecoveryEligible(normal) || configManager == null || temporaryHostOverlay != null || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null) {
			return false;
		}
		final Overlay overlay = findOverlay(normal.overlayName);
		if (overlay == null || overlay.getPreferredLocation() != null) {
			return false;
		}
		final OverlayPosition saved = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		final String location = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_LOCATION_SUFFIX);
		return saved == normal.snappedPosition && overlay.getPreferredPosition() == normal.snappedPosition && (location == null || location.isEmpty()) && currentMode() == (normal.snappedPosition != null ? PlacementMode.SNAPPED : PlacementMode.NATIVE);
	}

	/* Called ONLY after native and rendered rectangles already equal the
	 * original pre-dialogue rectangle. Reapply the exact saved snap anchor,
	 * or clear temporary placement for native AUTO; never estimate a corner. */
	public boolean requestOriginalNormalMode(NormalPlacement normal, Rectangle verified) {
		if (!normalModeRecoveryEligible(normal) || configManager == null || overlayManager == null || verified == null || !verified.equals(nativeOverlayBounds()) || temporaryHostOverlay != null || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null) {
			return false;
		}
		final Overlay overlay = findOverlay(normal.overlayName);
		if (overlay == null || normal.snappedPosition != null && (!overlay.isSnappable() || !isSupportedSnapAnchor(normal.snappedPosition))) {
			return false;
		}
		overlay.setPreferredPosition(normal.snappedPosition);
		overlay.setPreferredLocation(null);
		overlayManager.saveOverlay(overlay);
		/* Native WidgetOverlay needs an actual revalidation to release the
		 * previous forcedPosition; this does not guess a new X/Y. */
		overlay.revalidate();
		normalRestoreSignature = nativeSaveSignature(normal.overlayName);
		lastAdoptedNativeSave = normalRestoreSignature;
		lastHandoffDecision = normal.snappedPosition != null
				? "ORIGINAL_SNAP_MODE_REQUESTED_" + normal.snappedPosition
				: "ORIGINAL_NATIVE_MODE_REQUESTED";
		return true;
	}

	/* If a restored snap/native mode moves the host, revert to the already
	 * verified absolute rectangle as MANUAL instead of moving it repeatedly.
	 * Reload the SAME native overlay to synchronize live origin and config. */
	public boolean restoreManualModeFallback(NormalPlacement normal, Rectangle target) {
		if (!normalModeRecoveryEligible(normal) || target == null || target.width <= 0 || target.height <= 0 || overlayManager == null || configManager == null || temporaryHostOverlay != null || unconfirmedNativeDrop) {
			return false;
		}
		final Overlay overlay = findOverlay(normal.overlayName);
		if (overlay == null || !overlayManager.remove(overlay)) {
			return false;
		}
		try {
			configManager.unsetConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_POSITION_SUFFIX);
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_LOCATION_SUFFIX, target.x + ":" + target.y);
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_SUFFIX, "MANUAL");
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_X_SUFFIX, "LEFT");
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_Y_SUFFIX, "TOP");
		} finally {
			overlayManager.add(overlay);
		}
		overlay.revalidate();
		normalRestoreSignature = nativeSaveSignature(normal.overlayName);
		lastAdoptedNativeSave = normalRestoreSignature;
		lastHandoffDecision = "ORIGINAL_MODE_FALLBACK_TO_VERIFIED_MANUAL";
		return true;
	}

	private static boolean isSupportedSnapAnchor(OverlayPosition anchor) {
		if (anchor == null) {
			return false;
		}
		switch (anchor) {
			case TOP_LEFT:
			case TOP_CENTER:
			case TOP_RIGHT:
			case BOTTOM_LEFT:
			case BOTTOM_RIGHT:
			case ABOVE_CHATBOX_RIGHT:
			case CANVAS_TOP_RIGHT:
				return true;
			default:
				return false;
		}
	}

	public boolean manualModeFallbackMatches(NormalPlacement normal, Rectangle target) {
		if (normal == null || target == null || !normal.overlayName.equals(overlayName()) || currentMode() != PlacementMode.MANUAL || configManager == null) {
			return false;
		}
		final Overlay overlay = findOverlay(normal.overlayName);
		final String saved = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_LOCATION_SUFFIX);
		return overlay != null && overlay.getPreferredPosition() == null && overlay.getPreferredLocation() != null && (target.x + ":" + target.y).equals(saved);
	}

	/* Emergency, session-local restoration reference when no pre-dialogue
	 * normal placement could be captured. This is NOT a verified normal
	 * snapshot and must never be installed as the durable normal center until
	 * both the rendered and native host agree with the restored rectangle.
	 *
	 * Preserve RuneLite's current origin axes. A snapped overlay cannot be
	 * moved to an unrelated absolute point without changing its native mode;
	 * only accept a fallback target matching the existing snap in that case. */
	public NormalPlacement captureDialogueFallbackPlacement(Rectangle target) {
		if (target == null || target.width <= 0 || target.height <= 0 || configManager == null || overlayName() == null) {
			return null;
		}
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (overlay == null || client.getCanvasWidth() <= 0 || client.getCanvasHeight() <= 0) {
			return null;
		}
		final OverlayPosition snapped = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		if (snapped != null && !target.equals(overlay.getBounds())) {
			return null;
		}
		return new NormalPlacement(name, target, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX), configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX), configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX), configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX), snapped);
	}

	/*
	 * Restore the verified absolute chatbox rectangle without changing RuneLite's
	 * native origin axes. A dialogue drag can switch CENTER to BOTTOM or LEFT to
	 * RIGHT, and OverlayManager can retain that live origin while ConfigChanged
	 * callbacks are processed. Changing the origin and the saved offset together
	 * therefore risks resolving a correct old offset under the wrong live axis.
	 * Rebase only the location against the current axes, after returning the
	 * host to its configured size. Do not force overlay or widget bounds.
	 */
	public Rectangle restoreNormalPlacement(NormalPlacement normal, int width, int height) {
		return restoreNormalPlacement(normal, normal != null ? normal.boundsAtSize(width, height) : null);
	}

	// Apply an absolute restoration target prepared by the dialogue session.
	public Rectangle restoreNormalPlacement(NormalPlacement normal, Rectangle requested) {
		final int width = requested != null ? requested.width : 0;
		final int height = requested != null ? requested.height : 0;
		if (requested == null || normal == null || width <= 0 || height <= 0 || !normal.overlayName.equals(overlayName()) || unconfirmedNativeDrop) {
			return null;
		}

		final Overlay overlay = findOverlay(normal.overlayName);
		final Dimension real = client.getRealDimensions();
		if (overlay == null || configManager == null || real == null || real.width <= 0 || real.height <= 0) {
			return null;
		}

		final OverlayPosition savedPosition = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		/* A session that never left its original snap needs no native-position
		 * write. After size restoration RuneLite owns the same snap anchor;
		 * ordinary post-render readback still verifies the absolute target. */
		if (savedPosition != null && savedPosition != normal.snappedPosition) {
			lastHandoffDecision = "NORMAL_RESTORE_UNSUPPORTED_SNAP";
			return null;
		}

		final int canvasWidth = Math.max(0, client.getCanvasWidth());
		final int canvasHeight = Math.max(0, client.getCanvasHeight());
		if (canvasWidth == 0 || canvasHeight == 0) {
			return null;
		}
		/* Reconstruct the absolute destination from the fixed pre-dialogue
		 * center, never from the dialogue's last dragged rectangle. The native
		 * origin axes are used only when encoding this destination below. */
		final Rectangle target = new Rectangle(requested);
		target.x = Math.max(0, Math.min(target.x, Math.max(0, canvasWidth - width)));
		target.y = Math.max(0, Math.min(target.y, Math.max(0, canvasHeight - height)));
		if (savedPosition != null) {
			restoreTemporaryHost();
			clearRecoveredRuntimeFit();
			resetManual();
			lastHandoffDecision = "PRE_DIALOGUE_SNAP_RETAINED";
			return target;
		}

		/* With an unchanged RuneLite-native placement, the verified physical
		 * center is already correct. Avoid manufacturing a manual save when
		 * the dialogue was fitted but never dragged. */
		if (normal.snappedPosition == null && normal.savedLocation == null && currentMode() == PlacementMode.NATIVE) {
			restoreTemporaryHost();
			clearRecoveredRuntimeFit();
			final Rectangle nativeBounds = overlay.getBounds();
			if (target.equals(nativeBounds)) {
				resetManual();
				lastHandoffDecision = "PRE_DIALOGUE_NATIVE_RETAINED";
				return target;
			}
		}

		/* Read AFTER the dialogue's native save. These are the axes that the
		 * existing overlay is actually using; do not restore the old axes in
		 * the same pass, as that can leave runtime and config out of sync. */
		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_Y_SUFFIX);
		if (originX != null && !"LEFT".equals(originX) && !"CENTER".equals(originX) && !"RIGHT".equals(originX) || originY != null && !"TOP".equals(originY) && !"CENTER".equals(originY) && !"BOTTOM".equals(originY)) {
			lastHandoffDecision = "NORMAL_RESTORE_UNSUPPORTED_ORIGIN";
			return null;
		}
		final int configuredBaseX = horizontalOrigin(0, real.width, 0, originX);
		final int configuredBaseY = verticalOrigin(0, real.height, 0, originY);

		/* A RuneLite drag can update the overlay's in-memory origin before
		 * its saved origin matches it. The renderer uses the LIVE axes, not
		 * the ConfigManager values. Measure those axes from the last aligned
		 * native bounds and runtime offset before releasing the fit preview.
		 * Reject clamped/ambiguous samples rather than inventing an origin. */
		final Rectangle liveBounds = overlay.getBounds();
		final Point livePreferred = overlay.getPreferredLocation();
		final Integer liveBaseX = observedCanvasOrigin(
				liveBounds != null ? liveBounds.x : 0, livePreferred != null ? livePreferred.x : 0, real.width, liveBounds != null ? liveBounds.width : 0, liveBounds != null && livePreferred != null);
		final Integer liveBaseY = observedCanvasOrigin(
				liveBounds != null ? liveBounds.y : 0, livePreferred != null ? livePreferred.y : 0, real.height, liveBounds != null ? liveBounds.height : 0, liveBounds != null && livePreferred != null);
		final boolean liveOriginVerified = liveBaseX != null && liveBaseY != null;
		final boolean liveOriginMismatch = liveOriginVerified && (liveBaseX != configuredBaseX || liveBaseY != configuredBaseY);
		final int baseX = liveOriginVerified ? liveBaseX : configuredBaseX;
		final int baseY = liveOriginVerified ? liveBaseY : configuredBaseY;
		final Point preferred = new Point(target.x - baseX, target.y - baseY);
		final String newLocation = preferred.x + ":" + preferred.y;

		restoreTemporaryHost();
		clearRecoveredRuntimeFit();
		discardTemporaryHost();
		final Point current = overlay.getPreferredLocation();
		if (!preferred.equals(current)) {
			overlay.setPreferredLocation(new Point(preferred));
		}
		if (liveOriginMismatch) {
			/* Persist the exact origin axes used by the running renderer.
			 * Saving only preferredLocation would retain the stale axes and
			 * reproduce the mismatch after restart. RuneLite owns this save. */
			overlayManager.saveOverlay(overlay);
		} else {
			final String saved = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_LOCATION_SUFFIX);
			if (!newLocation.equals(saved)) {
				configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_LOCATION_SUFFIX, newLocation);
			}
		}
		resetManual();
		normalRestoreSignature = nativeSaveSignature(normal.overlayName);
		lastAdoptedNativeSave = normalRestoreSignature;
		lastHandoffDecision = liveOriginMismatch
				? "PRE_DIALOGUE_LIVE_ORIGIN_RESTORED"
				: !liveOriginVerified
						? "PRE_DIALOGUE_ORIGIN_UNVERIFIED_CONFIG_FALLBACK"
						: "PRE_DIALOGUE_ABSOLUTE_RESTORED";
		return target;
	}

	/* Infer the live canvas origin only when its clamped position is unique.
	 * An off-canvas offset can render at an edge under multiple origin axes;
	 * that observation must not be used to rewrite native placement state. */
	private static Integer observedCanvasOrigin(int absolute, int preferred, int realSize, int overlaySize, boolean available) {
		if (!available || realSize <= 0 || overlaySize <= 0 || overlaySize > realSize) {
			return null;
		}
		final int[] candidates = {0, realSize / 2, realSize};
		Integer match = null;
		for (int base : candidates) {
			final int unclamped = base + preferred;
			final int clamped = Math.max(0, Math.min(unclamped, realSize - overlaySize));
			if (Math.abs(clamped - absolute) <= SAVED_HANDOFF_TOLERANCE) {
				if (match != null && match != base) {
					return null;
				}
				match = base;
			}
		}
		return match;
	}

	/* A visually aligned host can still have the WRONG relative anchor. On a
	 * subsequent canvas resize it would drift, so restoration must also verify
	 * the saved origin axes before releasing the normal presentation guard. */
	public boolean normalRestoreAnchorChanged(NormalPlacement normal) {
		if (normal == null || normal.savedLocation == null || configManager == null || !("AUTO".equals(normal.origin) || "MANUAL".equals(normal.origin)) || !validNormalOriginX(normal.originX) || !validNormalOriginY(normal.originY)) {
			return false;
		}
		final String currentOrigin = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_SUFFIX);
		final String currentX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_X_SUFFIX);
		final String currentY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_Y_SUFFIX);
		return !normal.origin.equals(currentOrigin) || !normal.originX.equals(currentX) || !normal.originY.equals(currentY);
	}

	/*
	 * Re-establish the saved NORMAL placement relationship after an independently
	 * stable, but incorrect, native/widget readback. A physical dialogue drag may
	 * change RuneLite's LIVE origin without leaving matching saved origin axes.
	 * Merely changing preferredLocation cannot restore a LEFT/CENTER/RIGHT anchor;
	 * it would shift the chatbox again on the next canvas resize.
	 *
	 * RuneLite's origin setters are package-private. Re-load this *same* movable
	 * overlay through the public manager only as a last, single correction. This
	 * applies the pre-dialogue native origin/offset together, and the session
	 * still requires a subsequent physical native/widget verification.
	 */
	public boolean reconcileNormalRestoration(NormalPlacement normal, Rectangle expected, Rectangle nativeBounds) {
		if (normal == null || expected == null || nativeBounds == null || !normal.overlayName.equals(overlayName()) || normal.savedLocation == null || !("AUTO".equals(normal.origin) || "MANUAL".equals(normal.origin)) || !validNormalOriginX(normal.originX) || !validNormalOriginY(normal.originY) || temporaryHostOverlay != null || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null || configManager == null || overlayManager == null || currentMode() != PlacementMode.MANUAL || !isNormalRestoreSave() || !expected.getSize().equals(nativeBounds.getSize())) {
			return false;
		}
		final Overlay overlay = findOverlay(normal.overlayName);
		final Rectangle measured = overlay != null ? overlay.getBounds() : null;
		final Point runtime = overlay != null ? overlay.getPreferredLocation() : null;
		final int[] saved = parseLocation(configManager.getConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_LOCATION_SUFFIX));
		final Dimension real = client.getRealDimensions();
		if (measured == null || !measured.equals(nativeBounds) || runtime == null || saved == null || real == null || real.width <= 0 || real.height <= 0 || client.getCanvasWidth() <= 0 || client.getCanvasHeight() <= 0 || Math.abs(runtime.x - saved[0]) > SAVED_HANDOFF_TOLERANCE || Math.abs(runtime.y - saved[1]) > SAVED_HANDOFF_TOLERANCE) {
			return false;
		}

		/* The original origin defines the relative placement; the current canvas
		 * only changes the resolved absolute coordinate. Never retain a guessed
		 * origin from a temporary orthogonal fit or a native drag. */
		final Point restoredOffset = new Point(expected.x - horizontalOrigin(0, real.width, 0, normal.originX), expected.y - verticalOrigin(0, real.height, 0, normal.originY));
		final String restoredLocation = restoredOffset.x + ":" + restoredOffset.y;
		if (!overlayManager.remove(overlay)) {
			return false;
		}
		try {
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + PREFERRED_LOCATION_SUFFIX, restoredLocation);
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_SUFFIX, normal.origin);
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_X_SUFFIX, normal.originX);
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, normal.overlayName + ORIGIN_Y_SUFFIX, normal.originY);
		} finally {
			/* Never leave the movable native overlay detached, even if a save
			 * callback fails. Only one caller-guarded reload is permitted. */
			overlayManager.add(overlay);
		}
		normalRestoreSignature = nativeSaveSignature(normal.overlayName);
		lastAdoptedNativeSave = normalRestoreSignature;
		lastHandoffDecision = "PRE_DIALOGUE_ORIGINAL_ANCHOR_RELOADED";
		return true;
	}

	private static boolean validNormalOriginX(String origin) {
		return "LEFT".equals(origin) || "CENTER".equals(origin) || "RIGHT".equals(origin);
	}

	private static boolean validNormalOriginY(String origin) {
		return "TOP".equals(origin) || "CENTER".equals(origin) || "BOTTOM".equals(origin);
	}

	// Read-only evidence for a native restore; does not mutate placement.
	public String normalRestoreReadbackDiagnostic() {
		final String name = overlayName();
		if (name == null || configManager == null) {
			return "unavailable";
		}
		final Overlay overlay = findOverlay(name);
		final Point runtime = overlay != null ? overlay.getPreferredLocation() : null;
		final String saved = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX);
		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX);
		final String origin = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX);
		final int[] location = parseLocation(saved);
		final Dimension real = client.getRealDimensions();
		final String predicted = location == null || real == null || real.width <= 0 || real.height <= 0 ? "-" : horizontalOrigin(0, real.width, location[0], originX) + ":" + verticalOrigin(0, real.height, location[1], originY);
		return "mode=" + currentMode() + " | saved=" + saved + " | runtime=" + runtime + " | origin=" + origin + " | originX=" + originX + " | originY=" + originY + " | predictedNative=" + predicted + " | restoreSave=" + isNormalRestoreSave();
	}

	// Describe a skipped normal-center capture without modifying the host.
	public String normalCaptureDiagnostic(Rectangle candidate) {
		return "candidate=" + candidate + " | native=" + nativeOverlayBounds() + " | temporary=" + (temporaryHostOverlay != null) + " | unconfirmedDrop=" + unconfirmedNativeDrop + " | " + normalRestoreReadbackDiagnostic();
	}

	/* Ignore the ConfigChanged emitted by our own single location restoration.
	 * A new native drag resets this marker even if it saves the same values. */
	public boolean isNormalRestoreSave() {
		final String name = overlayName();
		return normalRestoreSignature != null && name != null && normalRestoreSignature.equals(nativeSaveSignature(name));
	}

	// Returns a snapshot of RuneLite's native movable-chatbox rectangle.
	public Rectangle nativeOverlayBounds() {
		final Overlay overlay = findOverlay(overlayName());
		final Rectangle bounds = overlay != null ? overlay.getBounds() : null;
		return bounds != null && bounds.width > 0 && bounds.height > 0 ? new Rectangle(bounds) : null;
	}

	public PlacementMode currentMode() {
		if (recoveredRuntimeFitBounds != null) {
			return PlacementMode.MANUAL;
		}
		if (unconfirmedNativeDrop) {
			return PlacementMode.RUNTIME_DRAG;
		}
		final String overlayName = overlayName();
		if (overlayName == null || configManager == null) {
			return PlacementMode.NATIVE;
		}

		final OverlayPosition preferredPosition = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		final String preferredLocation = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + PREFERRED_LOCATION_SUFFIX);
		if (usesRuntimeDrag(overlayName, preferredPosition, preferredLocation)) {
			return PlacementMode.RUNTIME_DRAG;
		}
		if (preferredPosition != null) {
			return PlacementMode.SNAPPED;
		}
		if (preferredLocation != null && !preferredLocation.isEmpty()) {
			return PlacementMode.MANUAL;
		}

		return PlacementMode.NATIVE;
	}

	public boolean allowsDirectHostMove() {
		return currentMode().allowsDirectHostMove();
	}

	public void reset() {
		lastAdoptedNativeSave = null;
		normalRestoreSignature = null;
		restoreTemporaryHost();
		clearRecoveredRuntimeFit();
		unconfirmedNativeDrop = false;
		resetManual();
	}

	// A dropped runtime host is provisional until RuneLite writes its placement.
	public void markUnconfirmedNativeDrop() {
		clearRecoveredRuntimeFit();
		discardTemporaryHost();
		resetManual();
		unconfirmedNativeDrop = true;
		lastHandoffDecision = "WAITING_FOR_NATIVE_SAVE";
	}

	public boolean isUnconfirmedNativeDrop() {
		return unconfirmedNativeDrop;
	}

	/* Release an unconfirmed dialogue-only drag after the controller has
	 * confirmed closure and identified the original normal placement (or a
	 * physically aligned emergency reference). This never saves or adopts the
	 * dropped runtime location. restoreNormalPlacement() will independently
	 * verify the target before applying native placement and readback. */
	public boolean releaseClosedDialogueNativeHandoff(NormalPlacement normal, boolean verifiedEmergencyReference) {
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || overlay == null || configManager == null || (normal == null && !verifiedEmergencyReference) || (normal != null && !name.equals(normal.overlayName))) {
			return false;
		}
		if (unconfirmedNativeDrop) {
			/* Releasing this veto is not accepting a native save: the normal
			 * session snapshot still owns the eventual restore destination. */
			unconfirmedNativeDrop = false;
			savedPlacementHandoffPending = false;
			lastHandoffDecision = "CLOSED_DIALOGUE_DROP_RELEASED";
		}
		return true;
	}

	// Reads native preference only; the caller must separately verify stability.
	public Point nativeOverlayPreferredLocation() {
		final Overlay overlay = findOverlay(overlayName());
		final Point preferred = overlay != null ? overlay.getPreferredLocation() : null;
		return preferred != null ? new Point(preferred) : null;
	}

	/*
	 * Restore adaptive fitting after a native drop remained unsaved but the
	 * physical widget and overlay have settled at the same configured rectangle.
	 * This grants in-memory fit ownership, NOT configuration/save ownership.
	 */
	public boolean recoverStableRuntimeFit(Widget slot, int width, int height) {
		if (!unconfirmedNativeDrop || slot == null || width <= 0 || height <= 0 || configManager == null || overlayName() == null) {
			return false;
		}
		final Overlay overlay = findOverlay(overlayName());
		final Rectangle widgetBounds = slot.getBounds();
		final Rectangle overlayBounds = overlay != null ? overlay.getBounds() : null;
		final Point preferred = overlay != null ? overlay.getPreferredLocation() : null;
		if (widgetBounds == null || overlayBounds == null || preferred == null || widgetBounds.width != width || widgetBounds.height != height || !widgetBounds.equals(overlayBounds) || overlay.getPreferredPosition() != null) {
			return false;
		}
		clearRecoveredRuntimeFit();
		recoveredRuntimeFitBounds = new Rectangle(widgetBounds);
		recoveredRuntimeFitHostBounds = new Rectangle(widgetBounds);
		recoveredRuntimeFitPreferred = new Point(preferred);
		unconfirmedNativeDrop = false;
		resetManual();
		lastHandoffDecision = "STABLE_RUNTIME_FIT_ONLY";
		return true;
	}

	/*
	 * A real chatbox drag may not cause a saved preferredLocation event. If its
	 * expanded host and RuneLite overlay physically agree on the dropped box,
	 * retain that position for fitting without persisting or inventing a save.
	 * Only actual canvas-edge contact alters the configured-size origin.
	 */
	public boolean recoverUnconfirmedExpandedDrop(Widget slot, Rectangle drop, Rectangle releaseSample, int width, int height) {
		if (!unconfirmedNativeDrop || slot == null || drop == null || width <= 0 || height <= 0 || configManager == null || overlayName() == null) {
			return false;
		}
		final Overlay overlay = findOverlay(overlayName());
		final Rectangle widgetBounds = slot.getBounds();
		final Rectangle overlayBounds = overlay != null ? overlay.getBounds() : null;
		final Point preferred = overlay != null ? overlay.getPreferredLocation() : null;
		if (widgetBounds == null || overlayBounds == null || preferred == null || !widgetBounds.equals(drop) || !overlayBounds.equals(drop) || overlay.getPreferredPosition() != null) {
			return false;
		}
		final Rectangle canvas = new Rectangle(0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		if (canvas.width <= 0 || canvas.height <= 0) {
			return false;
		}
		final int x = drop.x + (touchesRight(drop, canvas) ? drop.width - width : 0);
		final int y = drop.y + (touchesBottom(drop, canvas) ? drop.height - height : 0);
		clearRecoveredRuntimeFit();
		recoveredRuntimeFitBounds = new Rectangle(x, y, width, height);
		recoveredRuntimeFitHostBounds = new Rectangle(drop);
		recoveredRuntimeFitPreferred = new Point(preferred);
		recoveredRuntimeOriginVerified = runtimeOriginMatchesRelease(preferred, releaseSample);
		unconfirmedNativeDrop = false;
		resetManual();
		lastHandoffDecision = "UNSAVED_PHYSICAL_FIT_ONLY";
		return true;
	}

	private void clearRecoveredRuntimeFit() {
		recoveredRuntimeFitBounds = null;
		recoveredRuntimeFitHostBounds = null;
		recoveredRuntimeFitPreferred = null;
		recoveredRuntimeOriginVerified = false;
	}

	/*
	 * The released widget can already have been repositioned by ChatXL before
	 * the unsaved handoff is recovered. Verify the independent native runtime
	 * origin against the actual release, not the subsequently fitted host.
	 * Unknown/widget-relative origins retain the existing physical-host path.
	 */
	private boolean runtimeOriginMatchesRelease(Point preferred, Rectangle releaseSample) {
		final String name = overlayName();
		final Dimension real = client.getRealDimensions();
		if (name == null || preferred == null || releaseSample == null || configManager == null || real == null || real.width <= 0 || real.height <= 0) {
			return false;
		}

		final String origin = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX);
		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX);
		if (origin != null && !"AUTO".equals(origin) && !"MANUAL".equals(origin) || originX != null && !"LEFT".equals(originX) && !"CENTER".equals(originX) && !"RIGHT".equals(originX) || originY != null && !"TOP".equals(originY) && !"CENTER".equals(originY) && !"BOTTOM".equals(originY)) {
			return false;
		}

		// A native drag snapshot can lag a pixel or two behind the overlay.
		final int nativeX = horizontalOrigin(0, real.width, preferred.x, originX);
		final int nativeY = verticalOrigin(0, real.height, preferred.y, originY);
		return Math.abs(nativeX - releaseSample.x) <= 2 && Math.abs(nativeY - releaseSample.y) <= 2;
	}

	// A runtime placement may safely rejoin its saved anchor without a new save.
	public boolean rejoinSavedNativePlacement() {
		if (!unconfirmedNativeDrop) {
			return true;
		}
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || configManager == null || overlay == null) {
			return false;
		}
		final int[] saved = parseLocation(configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX));
		final Point runtime = overlay.getPreferredLocation();
		if (saved == null || runtime == null || runtime.x != saved[0] || runtime.y != saved[1]) {
			return false;
		}
		unconfirmedNativeDrop = false;
		lastHandoffDecision = "SAVED_RUNTIME_REJOINED";
		return true;
	}

	/*
	 * RuneLite emits separate saved-location, position and origin events for one
	 * drag. Consume a persisted snapshot at most once; transient fit writes to
	 * Overlay.getPreferredLocation() do not constitute a native placement save.
	 *
	 * The owning resize service also consumes a physical drag only once, since
	 * RuneLite can deliver these fields across more than one client callback.
	 */
	public boolean isNewNativePlacementSave() {
		final String name = overlayName();
		if (name == null || configManager == null) {
			return true;
		}

		return !nativeSaveSignature(name).equals(lastAdoptedNativeSave);
	}

	// Record a save only after it has actually been applied to placement.
	public void markNativePlacementAdopted() {
		final String name = overlayName();
		if (name != null && configManager != null) {
			lastAdoptedNativeSave = nativeSaveSignature(name);
		}
	}

	/*
	 * A config save is not proof of a chatbox drag. Acknowledge it without
	 * replacing the owning dialogue fit. When saved and runtime positions now
	 * agree, the temporary offset can safely reacquire that new native base on
	 * the next fit without restoring an obsolete preferredLocation.
	 */
	public void acknowledgeUnverifiedNativeSave() {
		markNativePlacementAdopted();
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		final Point runtime = overlay != null ? overlay.getPreferredLocation() : null;
		final int[] saved = name != null && configManager != null ? parseLocation(configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX)) : null;
		if (overlay != null && overlay == temporaryHostOverlay && runtime != null && saved != null && runtime.x == saved[0] && runtime.y == saved[1]) {
			discardTemporaryHost();
			savedPlacementHandoffPending = true;
			lastHandoffDecision = "UNVERIFIED_SAVE_BASE_REACQUIRE";
			return;
		}
		lastHandoffDecision = "UNVERIFIED_SAVE_ACKNOWLEDGED";
	}

	private String nativeSaveSignature(String name) {
		return name + "|" + savedValue(name, PREFERRED_LOCATION_SUFFIX) + "|" + savedValue(name, PREFERRED_POSITION_SUFFIX) + "|" + savedValue(name, ORIGIN_SUFFIX) + "|" + savedValue(name, ORIGIN_X_SUFFIX) + "|" + savedValue(name, ORIGIN_Y_SUFFIX);
	}

	private String savedValue(String overlay, String suffix) {
		final String value = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlay + suffix);
		return value != null ? value : "<unset>";
	}

	// A new physical drag may save the same numeric location again.
	public void beginNativePlacementDrag() {
		lastAdoptedNativeSave = null;
		normalRestoreSignature = null;
	}

	// Called on the client thread only after RuneLite reports a saved chatbox location.
	public void authorizeSavedPlacementHandoff() {
		clearRecoveredRuntimeFit();
		unconfirmedNativeDrop = false;
		savedPlacementHandoffPending = true;
		lastHandoffDecision = "SAVE_OBSERVED";
	}

	/*
	 * Verify the saved RuneLite snap and current rendered host without
	 * requiring an old MANUAL temporary-fit token to have been discarded.
	 * This is read-only: a native snap can supersede that temporary token.
	 */
	public boolean verifiedNativeSnappedDialogueHost(Rectangle rendered) {
		if (rendered == null || rendered.width <= 0 || rendered.height <= 0 || currentMode() != PlacementMode.SNAPPED || configManager == null || unconfirmedNativeDrop) {
			return false;
		}
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || overlay == null || overlay.getBounds() == null || !rendered.equals(overlay.getBounds())) {
			return false;
		}
		final OverlayPosition saved = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		return saved != null && overlay.getPreferredPosition() == saved && overlay.getPreferredLocation() == null;
	}

	/* Verify a live snapped host. preferredPosition describes RuneLite's
	 * placement ownership, NOT a fixed screen corner for ChatXL to reconstruct.
	 * The fit solver chooses the proposed size and collision-safe viewport;
	 * RuneLite alone determines where that snapped size renders. */
	public boolean canResizeSnappedDialogueHost(Rectangle reference, Rectangle target) {
		if (reference == null || target == null || reference.width <= 0 || reference.height <= 0 || target.width <= 0 || target.height <= 0 || currentMode() != PlacementMode.SNAPPED || configManager == null || temporaryHostOverlay != null || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null) {
			return false;
		}
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || overlay == null || overlay.getBounds() == null || !reference.equals(overlay.getBounds())) {
			return false;
		}
		final OverlayPosition snap = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_POSITION_SUFFIX, OverlayPosition.class);
		return snap != null && overlay.getPreferredPosition() == snap && overlay.getPreferredLocation() == null;
	}

	/* Change the snapped host's size only. Never set native or widget X/Y:
	 * WidgetOverlay will reposition it according to its LIVE snap geometry. */
	public boolean resizeSnappedDialogueHost(Rectangle reference, Rectangle target) {
		if (!canResizeSnappedDialogueHost(reference, target)) {
			return false;
		}
		final Overlay overlay = findOverlay(overlayName());
		overlay.getBounds().setSize(target.width, target.height);
		return true;
	}

	/* Native AUTO has no saved location token. Resize only while the overlay
	 * remains physically bottom-left anchored. */
	public boolean canResizeNativeDialogueHost(Rectangle reference, Rectangle target) {
		if (reference == null || target == null || reference.width <= 0 || reference.height <= 0 || target.width <= 0 || target.height <= 0 || currentMode() != PlacementMode.NATIVE || temporaryHostOverlay != null || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null) {
			return false;
		}
		final Overlay overlay = findOverlay(overlayName());
		final Rectangle bounds = overlay != null ? overlay.getBounds() : null;
		final int canvasHeight = client.getCanvasHeight();
		return bounds != null && bounds.equals(reference) && overlay.getPreferredLocation() == null && overlay.getPreferredPosition() == null && canvasHeight > 0 && reference.x == 0 && target.x == 0 && reference.y + reference.height == canvasHeight && target.y + target.height == canvasHeight;
	}

	// Resize the native AUTO host but leave its bottom-left X/Y to RuneLite.
	public boolean resizeNativeDialogueHost(Rectangle reference, Rectangle target) {
		if (!canResizeNativeDialogueHost(reference, target)) {
			return false;
		}
		final Overlay overlay = findOverlay(overlayName());
		overlay.getBounds().setSize(target.width, target.height);
		return true;
	}

	// Snapped placement restores its original geometry without a manual location token.
	public boolean snappedDialogueHostRestored(Rectangle target) {
		final Overlay overlay = findOverlay(overlayName());
		return overlay != null && target != null && overlay.getBounds() != null && overlay.getBounds().equals(target) && canResizeSnappedDialogueHost(target, target);
	}

	/* Native AUTO restoration is verified against its physical bottom edge,
	 * rather than a MANUAL temporary-host token which cannot exist here. */
	public boolean nativeDialogueHostRestored(Rectangle target) {
		return canResizeNativeDialogueHost(target, target);
	}

	/* RuneLite's WidgetOverlay renderer recomputes X/Y from preferredLocation on
	 * every pass. Its rendered bounds are mutable and are not an authoritative
	 * origin for an offset (ChatXL may have just changed them). Instead derive
	 * each temporary coordinate from the saved origin-relative user placement.
	 * This is the same origin model OverlayManager uses for MANUAL overlays.
	 * Never persist this position; RuneLite must remain the owner on user drag. */
	public boolean applyTemporaryHost(Rectangle target) {
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (overlay == null || target == null || target.width <= 0 || target.height <= 0 || configManager == null || name == null) {
			return false;
		}

		if (temporaryHostOverlay != null && temporaryHostOverlay != overlay) {
			restoreTemporaryHost();
		}

		final Point preferred = overlay.getPreferredLocation();
		if (preferred == null) {
			return false;
		}

		if (temporaryHostOverlay == null) {
			final boolean recovered = recoveredRuntimeFitBounds != null;
			boolean settledSave = false;
			boolean exact = false;
			if (recovered) {
				final Rectangle bounds = overlay.getBounds();
				/* The logical configured-size baseline can be smaller than the
				 * expanded physical drop; only compare against the actual host. */
				if (!preferred.equals(recoveredRuntimeFitPreferred) || bounds == null || !bounds.equals(recoveredRuntimeFitHostBounds)) {
					clearRecoveredRuntimeFit();
					lastHandoffDecision = "REJECT_RECOVERED_RUNTIME_CHANGED";
					return false;
				}
			} else {
				/* An unsaved drag is never a new placement baseline. A confirmed native
				 * save permits at most one pixel of renderer/config rounding difference. */
				final int[] saved = parseLocation(configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX));
				if (saved == null) {
					return false;
				}
				exact = preferred.x == saved[0] && preferred.y == saved[1];
				settledSave = savedPlacementHandoffPending && Math.abs(preferred.x - saved[0]) <= SAVED_HANDOFF_TOLERANCE && Math.abs(preferred.y - saved[1]) <= SAVED_HANDOFF_TOLERANCE;
				if (!exact && !settledSave) {
					lastHandoffDecision = "REJECT_UNCONFIRMED_RUNTIME";
					return false;
				}
				if (currentMode() != PlacementMode.MANUAL && !settledSave) {
					lastHandoffDecision = "REJECT_NATIVE_OWNERSHIP";
					return false;
				}
				final String origin = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX);
				/* Widget-relative origins need OverlayManager's private widget resolution.
				 * Do not guess coordinates for them. */
				if (origin != null && !"AUTO".equals(origin) && !"MANUAL".equals(origin)) {
					lastHandoffDecision = "REJECT_WIDGET_ORIGIN";
					return false;
				}
			}
			temporaryHostOverlay = overlay;
			// Recovered runtime is an in-memory origin only, never a saved location.
			originalPreferredLocation = new Point(preferred);
			appliedPreferredLocation = new Point(preferred);
			temporarySavedOrigin = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX);
			temporarySavedOriginX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX);
			temporarySavedOriginY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX);
			savedPlacementHandoffPending = false;
			lastHandoffDecision = recovered ? "ACQUIRED_STABLE_RUNTIME" : settledSave && !exact ? "ACQUIRED_SAVED_1PX" : "ACQUIRED_EXACT";
		} else if (!preferred.equals(appliedPreferredLocation)) {
			/* RuneLite's active mouse drag has taken over. Never restore the old
			 * value over a user placement, even if the save event arrives later. */
			clearRecoveredRuntimeFit();
			discardTemporaryHost();
			lastHandoffDecision = "NATIVE_DRAG_TOOK_CONTROL";
			return false;
		}

		final Dimension real = client.getRealDimensions();
		if (real == null || real.width <= 0 || real.height <= 0) {
			return false;
		}
		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX);
		/* A recovered drag may not have a trustworthy native origin. Use it only
		 * when verified against the release sample; otherwise use the physical host. */
		final int baseX = recoveredRuntimeFitHostBounds != null && !recoveredRuntimeOriginVerified
				? recoveredRuntimeFitHostBounds.x
				: horizontalOrigin(0, real.width, originalPreferredLocation.x, originX);
		final int baseY = recoveredRuntimeFitHostBounds != null && !recoveredRuntimeOriginVerified
				? recoveredRuntimeFitHostBounds.y
				: verticalOrigin(0, real.height, originalPreferredLocation.y, originY);
		if (recoveredRuntimeOriginVerified && recoveredRuntimeFitHostBounds != null && (baseX != recoveredRuntimeFitHostBounds.x || baseY != recoveredRuntimeFitHostBounds.y)) {
			lastHandoffDecision = "RECOVERED_NATIVE_ORIGIN_REBASED";
		}
		final Point requested = new Point(originalPreferredLocation.x + target.x - baseX, originalPreferredLocation.y + target.y - baseY);
		if (!requested.equals(preferred)) {
			overlay.setPreferredLocation(requested);
		}
		appliedPreferredLocation = requested;
		final Rectangle rendered = overlay.getBounds();
		if (rendered != null) {
			rendered.setBounds(target);
		}
		return true;
	}

	/*
	 * A confirmed native drag saves the size of the expanded dialogue, not the
	 * configured chatbox. When the dropped dialogue touches the RIGHT/BOTTOM
	 * canvas edge, preserve that physical edge as its size returns to normal.
	 * Floating drops retain their exact user-selected X/Y.
	 *
	 * Only translate an exact, saved MANUAL placement. In particular, do not
	 * overwrite a native drag in progress, a temporary host or stale config.
	 */
	public boolean rebaseExpandedDropForConfiguredSize(Rectangle drop, int configuredWidth, int configuredHeight) {
		if (drop == null || configuredWidth <= 0 || configuredHeight <= 0 || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null || currentMode() != PlacementMode.MANUAL || temporaryHostOverlay != null) {
			return false;
		}

		final Rectangle canvas = new Rectangle(0, 0, Math.max(0, client.getCanvasWidth()), Math.max(0, client.getCanvasHeight()));
		if (canvas.width <= 0 || canvas.height <= 0) {
			return false;
		}

		final int shiftX = touchesRight(drop, canvas) ? drop.width - configuredWidth : 0;
		final int shiftY = touchesBottom(drop, canvas) ? drop.height - configuredHeight : 0;
		if (shiftX == 0 && shiftY == 0) {
			return false;
		}

		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || overlay == null || configManager == null) {
			return false;
		}

		final int[] saved = parseLocation(configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX));
		final Point runtime = overlay.getPreferredLocation();
		final Dimension real = client.getRealDimensions();
		if (saved == null || runtime == null || real == null || real.width <= 0 || real.height <= 0 || runtime.x != saved[0] || runtime.y != saved[1]) {
			return false;
		}

		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX);
		final int savedX = horizontalOrigin(0, real.width, saved[0], originX);
		final int savedY = verticalOrigin(0, real.height, saved[1], originY);
		if (Math.abs(savedX - drop.x) > EDGE_TOLERANCE || Math.abs(savedY - drop.y) > EDGE_TOLERANCE) {
			return false;
		}

		final Point restored = new Point(saved[0] + shiftX, saved[1] + shiftY);
		overlay.setPreferredLocation(restored);
		configManager.setConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX, restored.x + ":" + restored.y);
		resetManual();
		lastHandoffDecision = "CONFIGURED_EDGE_REBASED";
		return true;
	}

	/* Recover temporary ownership if an unrelated overlay reset re-saves all overlays.
	 * The original saved location and axes must still be valid; an active drag
	 * or chatbox reset cannot authorize this recovery. */
	public boolean recoverTemporaryHostAfterUnrelatedReset() {
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || configManager == null || overlay == null || overlay != temporaryHostOverlay || originalPreferredLocation == null || appliedPreferredLocation == null || temporarySavedOriginX == null || temporarySavedOriginY == null || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null || configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX) != null) {
			return false;
		}
		final Point live = overlay.getPreferredLocation();
		if (live != null && !live.equals(appliedPreferredLocation) && !live.equals(originalPreferredLocation)) {
			lastHandoffDecision = "REJECT_OBSTACLE_RESET_NATIVE_MOVE";
			return false;
		}
		if (temporarySavedOrigin != null) {
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX, temporarySavedOrigin);
		}
		if (temporarySavedOriginX != null) {
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX, temporarySavedOriginX);
		}
		if (temporarySavedOriginY != null) {
			configManager.setConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX, temporarySavedOriginY);
		}
		final String saved = originalPreferredLocation.x + ":" + originalPreferredLocation.y;
		configManager.setConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX, saved);
		overlay.setPreferredLocation(new Point(appliedPreferredLocation));
		lastHandoffDecision = "OBSTACLE_RESET_TEMPORARY_HOST_RECOVERED";
		return true;
	}

	/* Immutable proof that ChatXL owns one temporary MANUAL native host.
	 * Retain it across a configured-size restore that releases the live token.
	 * This is not a new saved location and cannot authorize any host movement. */
	public static final class TemporaryHostRestoreEvidence {
		private final String overlayName;
		private final Overlay overlay;
		private final Point originalLocation;
		private final String savedLocation;
		private final String savedOrigin;
		private final String savedOriginX;
		private final String savedOriginY;

		private TemporaryHostRestoreEvidence(String overlayName, Overlay overlay, Point originalLocation, String savedLocation, String savedOrigin, String savedOriginX, String savedOriginY) {
			this.overlayName = overlayName;
			this.overlay = overlay;
			this.originalLocation = new Point(originalLocation);
			this.savedLocation = savedLocation;
			this.savedOrigin = savedOrigin;
			this.savedOriginX = savedOriginX;
			this.savedOriginY = savedOriginY;
		}
	}

	/* Capture only an existing, fully owned MANUAL transaction. Do not
	 * manufacture ownership from a physically aligned host or an unsaved drag. */
	public TemporaryHostRestoreEvidence captureTemporaryHostRestoreEvidence() {
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		if (name == null || overlay == null || overlay != temporaryHostOverlay || configManager == null || currentMode() != PlacementMode.MANUAL || originalPreferredLocation == null || appliedPreferredLocation == null || !appliedPreferredLocation.equals(overlay.getPreferredLocation()) || savedPlacementHandoffPending || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null) {
			return null;
		}
		final String saved = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX);
		final int[] location = parseLocation(saved);
		final String origin = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_SUFFIX);
		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + ORIGIN_Y_SUFFIX);
		if (location == null || Math.abs(location[0] - originalPreferredLocation.x) > SAVED_HANDOFF_TOLERANCE || Math.abs(location[1] - originalPreferredLocation.y) > SAVED_HANDOFF_TOLERANCE || !Objects.equals(origin, temporarySavedOrigin) || !Objects.equals(originX, temporarySavedOriginX) || !Objects.equals(originY, temporarySavedOriginY)) {
			return null;
		}
		return new TemporaryHostRestoreEvidence(name, overlay, originalPreferredLocation, saved, origin, originX, originY);
	}

	/* The pre-collision dialogue fit may be larger than the normal chatbox, so its temporary offset need not equal the normal saved offset.
	 * Accept only the original owned transaction and unchanged persisted origin axes. */
	public boolean temporaryHostRestoredToOwnedA2Baseline(TemporaryHostRestoreEvidence evidence, Rectangle expected) {
		if (evidence == null || expected == null || configManager == null || savedPlacementHandoffPending || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null || currentMode() != PlacementMode.MANUAL || !evidence.overlayName.equals(overlayName()) || originalPreferredLocation == null || appliedPreferredLocation == null || !evidence.originalLocation.equals(originalPreferredLocation)) {
			return false;
		}
		final Overlay overlay = findOverlay(evidence.overlayName);
		if (overlay == null || overlay != evidence.overlay || overlay != temporaryHostOverlay || !appliedPreferredLocation.equals(overlay.getPreferredLocation()) || !expected.equals(overlay.getBounds())) {
			return false;
		}
		return Objects.equals(evidence.savedLocation, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + PREFERRED_LOCATION_SUFFIX)) && Objects.equals(evidence.savedOrigin, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + ORIGIN_SUFFIX)) && Objects.equals(evidence.savedOriginX, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + ORIGIN_X_SUFFIX)) && Objects.equals(evidence.savedOriginY, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + ORIGIN_Y_SUFFIX));
	}

	/* Verify the same native transaction AFTER ChatXL has restored and released its temporary host.
	 * The controller independently verifies model/slot/native/viewport rectangles before accepting this evidence.
	 * Any native save, drag, origin change or new temporary owner invalidates it. */
	public boolean releasedTemporaryHostRestoredToSavedPosition(TemporaryHostRestoreEvidence evidence, Rectangle expected) {
		if (evidence == null || expected == null || configManager == null || temporaryHostOverlay != null || savedPlacementHandoffPending || unconfirmedNativeDrop || recoveredRuntimeFitBounds != null || currentMode() != PlacementMode.MANUAL || !"RESTORED_SAVED".equals(lastHandoffDecision) || !evidence.overlayName.equals(overlayName())) {
			return false;
		}
		final Overlay overlay = findOverlay(evidence.overlayName);
		if (overlay == null || overlay != evidence.overlay || !evidence.originalLocation.equals(overlay.getPreferredLocation()) || !expected.equals(overlay.getBounds())) {
			return false;
		}
		return Objects.equals(evidence.savedLocation, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + PREFERRED_LOCATION_SUFFIX)) && Objects.equals(evidence.savedOrigin, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + ORIGIN_SUFFIX)) && Objects.equals(evidence.savedOriginX, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + ORIGIN_X_SUFFIX)) && Objects.equals(evidence.savedOriginY, configManager.getConfiguration(RUNELITE_CONFIG_GROUP, evidence.overlayName + ORIGIN_Y_SUFFIX));
	}

	// Retain the native placement reference until physical readback confirms that the temporary host returned to the saved position.
	public boolean temporaryHostRestoredToSavedPosition() {
		final String name = overlayName();
		if (temporaryHostOverlay == null || name == null || configManager == null || originalPreferredLocation == null || appliedPreferredLocation == null) {
			return false;
		}
		final Overlay overlay = findOverlay(name);
		final Point runtime = overlay != null ? overlay.getPreferredLocation() : null;
		final int[] saved = parseLocation(configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX));
		return overlay == temporaryHostOverlay && runtime != null && saved != null && originalPreferredLocation.equals(runtime) && appliedPreferredLocation.equals(runtime) && Math.abs(saved[0] - runtime.x) <= SAVED_HANDOFF_TOLERANCE && Math.abs(saved[1] - runtime.y) <= SAVED_HANDOFF_TOLERANCE;
	}

	/* A temporary fit can return to its physical baseline with a small native offset delta.
	 * Require the original saved location, live ownership and caller-verified geometry; never persist the temporary runtime offset. */
	public boolean temporaryHostRestoredToVerifiedGeometry(Rectangle expected) {
		final String name = overlayName();
		if (expected == null || temporaryHostOverlay == null || configManager == null || name == null || originalPreferredLocation == null || appliedPreferredLocation == null || savedPlacementHandoffPending || unconfirmedNativeDrop || currentMode() != PlacementMode.MANUAL) {
			return false;
		}
		final Overlay overlay = findOverlay(name);
		final Point runtime = overlay != null ? overlay.getPreferredLocation() : null;
		final Rectangle nativeBounds = overlay != null ? overlay.getBounds() : null;
		final int[] saved = parseLocation(configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX));
		return overlay == temporaryHostOverlay && runtime != null && saved != null && appliedPreferredLocation.equals(runtime) && originalPreferredLocation.x == saved[0] && originalPreferredLocation.y == saved[1] && Math.abs(runtime.x - originalPreferredLocation.x) <= SAVED_HANDOFF_TOLERANCE + 1 && Math.abs(runtime.y - originalPreferredLocation.y) <= SAVED_HANDOFF_TOLERANCE + 1 && expected.equals(nativeBounds);
	}

	public String temporaryHostRestorationDiagnostic() {
		return "handoff={" + hostHandoffDiagnostic() + "}" + " | original=" + originalPreferredLocation + " | applied=" + appliedPreferredLocation;
	}

	public void restoreTemporaryHost() {
		boolean restored = false;
		if (temporaryHostOverlay != null && originalPreferredLocation != null && appliedPreferredLocation != null) {
			final Point current = temporaryHostOverlay.getPreferredLocation();
			if (appliedPreferredLocation.equals(current)) {
				temporaryHostOverlay.setPreferredLocation(new Point(originalPreferredLocation));
				restored = true;
			}
		}
		discardTemporaryHost();
		if (restored) {
			lastHandoffDecision = "RESTORED_SAVED";
		}
	}

	public String overlayPlacementDiagnostic() {
		final Overlay overlay = findOverlay(overlayName());
		if (overlay == null) {
			return "missing";
		}

		final Rectangle bounds = overlay.getBounds();
		final Point preferred = overlay.getPreferredLocation();
		return "bounds=" + bounds + ",preferred=" + preferred + ",temporary=" + (temporaryHostOverlay == overlay) + ",original=" + originalPreferredLocation;
	}

	// Compact native/temporary ownership snapshot for the settled diagnostic.
	public String hostHandoffDiagnostic() {
		final String name = overlayName();
		final Overlay overlay = findOverlay(name);
		final Point runtime = overlay != null ? overlay.getPreferredLocation() : null;
		final String saved = configManager != null && name != null
				? configManager.getConfiguration(RUNELITE_CONFIG_GROUP, name + PREFERRED_LOCATION_SUFFIX)
				: null;
		return "saved=" + saved + " | runtime=" + runtime + " | pending=" + savedPlacementHandoffPending + " | unconfirmedDrop=" + unconfirmedNativeDrop + " | temporary=" + (temporaryHostOverlay == overlay && overlay != null) + " | decision=" + lastHandoffDecision;
	}

	public void discardTemporaryHost() {
		savedPlacementHandoffPending = false;
		temporaryHostOverlay = null;
		originalPreferredLocation = null;
		appliedPreferredLocation = null;
		temporarySavedOrigin = null;
		temporarySavedOriginX = null;
		temporarySavedOriginY = null;
	}

	/*
	 * A RuneLite overlay-save event is an explicit user placement decision.
	 * Discard ChatXL's temporary offset rather than restoring the old position.
	 */
	public void invalidateSavedPlacement() {
		clearRecoveredRuntimeFit();
		unconfirmedNativeDrop = false;
		discardTemporaryHost();
		lastHandoffDecision = "NATIVE_SAVED";
		resetManual();
	}

	// Returns the physical chatbox host geometry for read-only consumers.
	public Rectangle hostBounds(Widget slot) {
		if (slot == null) {
			return null;
		}

		final int width = slot.getWidth();
		final int height = slot.getHeight();
		final Widget parent = slot.getParent();
		final Rectangle parentBounds = parent != null ? parent.getBounds() : null;
		if (parentBounds != null) {
			return new Rectangle(parentBounds.x + slot.getRelativeX(), parentBounds.y + slot.getRelativeY(), width, height);
		}

		final Rectangle renderedBounds = InterfaceBounds.liveBounds(slot);
		return renderedBounds != null
				? new Rectangle(renderedBounds.x, renderedBounds.y, width, height)
				: null;
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
		if (overlay == temporaryHostOverlay && appliedPreferredLocation != null && appliedPreferredLocation.equals(runtimeLocation)) {
			return false;
		}
		final OverlayPosition runtimePosition = overlay.getPreferredPosition();

		if (configuredPosition != null) {
			return runtimePosition != configuredPosition && runtimeLocation != null;
		}

		if (configuredLocation == null || configuredLocation.isEmpty()) {
			return runtimeLocation != null || runtimePosition != null;
		}

		// Persisted manual placement remains authoritative until the first stable sample is captured.
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
	 * Primes RuneLite's movable chatbox overlay with host geometry without
	 * changing or persisting the user's preferred position/location.
	 */
	public void applyHostBounds(Rectangle hostBounds, boolean updateLocation) {
		applyHostBounds(hostBounds, updateLocation, false);
	}

	public void applyHostBounds(Rectangle hostBounds, boolean updateLocation, boolean allowUnmanaged) {
		if (hostBounds == null) {
			return;
		}

		final Overlay overlay = findOverlay(overlayName());
		if (overlay == null || !allowUnmanaged && !isManagedOverlay(overlay)) {
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
	 * Applies a selected host anchor once. Subsequent frame/layout ownership
	 * remains with RuneLite's WidgetOverlay.
	 */
	public boolean moveHost(Widget slot, Rectangle hostBounds) {
		return moveHost(slot, hostBounds, false);
	}

	public boolean moveHost(Widget slot, Rectangle hostBounds, boolean allowUnmanaged) {
		if (slot == null || hostBounds == null || !allowUnmanaged && !isManagedOverlay(findOverlay(overlayName()))) {
			return false;
		}

		final Rectangle liveBounds = hostBounds(slot);
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
		return new State(new Rectangle(liveBounds.x, liveBounds.y, width, height), true, PlacementMode.RUNTIME_DRAG);
	}

	private static State nativePlacement(Rectangle liveBounds, Rectangle canvas, int configuredWidth, int configuredHeight) {
		// Native resizable placement stays bottom-anchored to the canvas.
		final Rectangle desired = new Rectangle(liveBounds.x, canvas.y + canvas.height - configuredHeight, configuredWidth, configuredHeight);
		return new State(desired, true, PlacementMode.NATIVE);
	}

	private State manualPlacement(Rectangle liveBounds, Rectangle canvas, String overlayName, String preferredLocation, int configuredWidth, int configuredHeight) {
		// Restore the first manual sample from RuneLite's saved origin-relative location.
		if (manualBounds == null) {
			final Rectangle restored = configuredManualBounds(canvas, overlayName, preferredLocation, configuredWidth, configuredHeight);
			if (restored != null) {
				manualBounds = restored;
				return new State(manualBounds, true, restored.x - liveBounds.x, restored.y - liveBounds.y, false, PlacementMode.MANUAL);
			}

			manualBounds = new Rectangle(liveBounds);
		}

		// Stable live geometry updates the saved manual location.
		if (temporaryHostOverlay == null && liveBounds.width == manualBounds.width && liveBounds.height == manualBounds.height) {
			manualBounds.setLocation(liveBounds.x, liveBounds.y);
		}

		if (manualBounds.width == configuredWidth && manualBounds.height == configuredHeight) {
			return new State(manualBounds, true, PlacementMode.MANUAL);
		}

		/*
		 * Client-edge contact is authoritative for manual resizing. An attached
		 * edge remains fixed while the opposite edge grows or shrinks; an axis
		 * with no client-edge contact remains centered like a floating placement.
		 */
		final Rectangle resized = edgeAnchoredBounds(manualBounds, canvas, configuredWidth, configuredHeight);
		manualBounds.setBounds(resized);

		final boolean hostAnchorUpdate = resized.x != liveBounds.x || resized.y != liveBounds.y;
		return new State(manualBounds, true, 0, 0, hostAnchorUpdate, PlacementMode.MANUAL);
	}

	private static Rectangle edgeAnchoredBounds(Rectangle liveBounds, Rectangle canvas, int width, int height) {
		final HorizontalAnchor horizontalAnchor;
		if (touchesLeft(liveBounds, canvas)) {
			horizontalAnchor = HorizontalAnchor.LEFT;
		} else if (touchesRight(liveBounds, canvas)) {
			horizontalAnchor = HorizontalAnchor.RIGHT;
		} else {
			horizontalAnchor = HorizontalAnchor.CENTER;
		}

		final VerticalAnchor verticalAnchor;
		if (touchesTop(liveBounds, canvas)) {
			verticalAnchor = VerticalAnchor.TOP;
		} else if (touchesBottom(liveBounds, canvas)) {
			verticalAnchor = VerticalAnchor.BOTTOM;
		} else {
			verticalAnchor = VerticalAnchor.CENTER;
		}

		return new Rectangle(anchoredX(liveBounds, width, horizontalAnchor), anchoredY(liveBounds, height, verticalAnchor), width, height);
	}

	private Rectangle configuredManualBounds(Rectangle canvas, String overlayName, String preferredLocation, int width, int height) {
		final int[] location = parseLocation(preferredLocation);
		if (location == null) {
			return null;
		}

		final String originX = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + ORIGIN_X_SUFFIX);
		final String originY = configManager.getConfiguration(RUNELITE_CONFIG_GROUP, overlayName + ORIGIN_Y_SUFFIX);

		return new Rectangle(horizontalOrigin(canvas.x, canvas.width, location[0], originX), verticalOrigin(canvas.y, canvas.height, location[1], originY), width, height);
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
					Integer.parseInt(value.substring(0, separator)), Integer.parseInt(value.substring(separator + 1))
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
	private static HorizontalAnchor liveHorizontalSnapAnchor(Rectangle live, Rectangle canvas) {
		/* Infer which CANVAS edges are physically occupied, not which snap point
		 * the saved preferredPosition names. RuneLite may leave a 2px border gap. */
		if (Math.abs(live.x - canvas.x) <= SNAPPED_EDGE_TOLERANCE) {
			return HorizontalAnchor.LEFT;
		}
		if (Math.abs(live.x + live.width - canvas.x - canvas.width) <= SNAPPED_EDGE_TOLERANCE) {
			return HorizontalAnchor.RIGHT;
		}
		return HorizontalAnchor.CENTER;
	}

	private static VerticalAnchor liveVerticalSnapAnchor(Rectangle live, Rectangle canvas) {
		if (Math.abs(live.y - canvas.y) <= SNAPPED_EDGE_TOLERANCE) {
			return VerticalAnchor.TOP;
		}
		if (Math.abs(live.y + live.height - canvas.y - canvas.height) <= SNAPPED_EDGE_TOLERANCE) {
			return VerticalAnchor.BOTTOM;
		}
		return VerticalAnchor.CENTER;
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

	public enum PlacementMode {
		UNBOUNDED(false),
		NATIVE(false),
		SNAPPED(false),
		MANUAL(true),
		RUNTIME_DRAG(true);

		private final boolean directHostMove;

		PlacementMode(boolean directHostMove) {
			this.directHostMove = directHostMove;
		}

		private boolean allowsDirectHostMove() {
			return directHostMove;
		}
	}

	public static final class State {
		private final Rectangle desiredBounds;
		private final boolean bounded;
		private final int hostDeltaX;
		private final int hostDeltaY;
		private final boolean hostAnchorUpdate;
		private final PlacementMode mode;
		private final boolean droppedOrigin;
		private final boolean bottomAnchored;
		private final boolean rightAnchored;
		private final Point fitCenter;

		private State(Rectangle desiredBounds, boolean bounded, PlacementMode mode, Point fitCenter) {
			this(desiredBounds, bounded, 0, 0, false, mode, false, false, false, fitCenter);
		}

		private State(Rectangle desiredBounds, boolean bounded, PlacementMode mode) {
			this(desiredBounds, bounded, 0, 0, false, mode);
		}

		private State(Rectangle desiredBounds, boolean bounded, PlacementMode mode, boolean droppedOrigin) {
			this(desiredBounds, bounded, 0, 0, false, mode, droppedOrigin);
		}

		private State(Rectangle desiredBounds, boolean bounded, int hostDeltaX, int hostDeltaY, boolean hostAnchorUpdate, PlacementMode mode) {
			this(desiredBounds, bounded, hostDeltaX, hostDeltaY, hostAnchorUpdate, mode, false);
		}

		private State(Rectangle desiredBounds, boolean bounded, PlacementMode mode, boolean droppedOrigin, boolean bottomAnchored) {
			this(desiredBounds, bounded, 0, 0, false, mode, droppedOrigin, bottomAnchored);
		}

		private State(Rectangle desiredBounds, boolean bounded, int hostDeltaX, int hostDeltaY, boolean hostAnchorUpdate, PlacementMode mode, boolean droppedOrigin) {
			this(desiredBounds, bounded, hostDeltaX, hostDeltaY, hostAnchorUpdate, mode, droppedOrigin, false);
		}

		private State(Rectangle desiredBounds, boolean bounded, int hostDeltaX, int hostDeltaY, boolean hostAnchorUpdate, PlacementMode mode, boolean droppedOrigin, boolean bottomAnchored) {
			this(desiredBounds, bounded, hostDeltaX, hostDeltaY, hostAnchorUpdate, mode, droppedOrigin, bottomAnchored, false);
		}

		private State(Rectangle desiredBounds, boolean bounded, PlacementMode mode, boolean droppedOrigin, boolean bottomAnchored, boolean rightAnchored) {
			this(desiredBounds, bounded, 0, 0, false, mode, droppedOrigin, bottomAnchored, rightAnchored);
		}

		private State(Rectangle desiredBounds, boolean bounded, int hostDeltaX, int hostDeltaY, boolean hostAnchorUpdate, PlacementMode mode, boolean droppedOrigin, boolean bottomAnchored, boolean rightAnchored) {
			this(desiredBounds, bounded, hostDeltaX, hostDeltaY, hostAnchorUpdate, mode, droppedOrigin, bottomAnchored, rightAnchored, null);
		}

		private State(Rectangle desiredBounds, boolean bounded, int hostDeltaX, int hostDeltaY, boolean hostAnchorUpdate, PlacementMode mode, boolean droppedOrigin, boolean bottomAnchored, boolean rightAnchored, Point fitCenter) {
			this.desiredBounds = new Rectangle(desiredBounds);
			this.bounded = bounded;
			this.hostDeltaX = hostDeltaX;
			this.hostDeltaY = hostDeltaY;
			this.hostAnchorUpdate = hostAnchorUpdate;
			this.mode = mode;
			this.droppedOrigin = droppedOrigin;
			this.bottomAnchored = bottomAnchored;
			this.rightAnchored = rightAnchored;
			this.fitCenter = fitCenter != null ? new Point(fitCenter) : null;
		}

		private static State unbounded(int width, int height) {
			return new State(new Rectangle(0, 0, width, height), false, PlacementMode.UNBOUNDED);
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

		public boolean requiresHostAnchorUpdate() {
			return hostAnchorUpdate;
		}

		public PlacementMode getMode() {
			return mode;
		}

		public boolean allowsDirectHostMove() {
			return mode.allowsDirectHostMove();
		}
	}
}
