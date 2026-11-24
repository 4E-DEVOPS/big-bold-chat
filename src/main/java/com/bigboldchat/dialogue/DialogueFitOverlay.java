package com.bigboldchat.dialogue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import com.bigboldchat.debug.DialogueDiagnostics;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Displays the ideal dialogue footprint and clipped viewport during gameplay.
 * Provides visual feedback without changing dialogue fitting or drag ownership.
 */
public final class DialogueFitOverlay extends Overlay {
	private static final Color DESIRED_COLOR = new Color(255, 162, 46, 225);
	private static final Color CLIPPED_COLOR = new Color(255, 132, 137, 225);
	private static final int RECTANGLE_TOLERANCE = 1;

	private final DialogueCoreService dialogueService;
	private final DialogueDiagnostics diagnostics;

	private Rectangle desiredBounds;
	private Rectangle syncedLogicalBounds;

	public DialogueFitOverlay(DialogueCoreService dialogueService) {
		this(dialogueService, null);
	}

	public DialogueFitOverlay(DialogueCoreService dialogueService, DialogueDiagnostics diagnostics) {
		this.dialogueService = dialogueService;
		this.diagnostics = diagnostics;
		setPriority(Overlay.PRIORITY_HIGHEST);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public String getName() {
		// Retain the existing overlay identifier for compatibility with RuneLite.
		return "CHATXL_CHATBOX_FIT_DIAGNOSTIC";
	}

	public void sync() {
		// The native chatbox owns mouse interaction; this overlay only paints outlines.
		getBounds().setBounds(0, 0, 0, 0);
		if (dialogueService == null) {
			clear();
			return;
		}

		final Rectangle logical = dialogueService.getDialogueEffectiveBounds();
		final Rectangle physical = dialogueService.getDialogueRenderedViewportBounds();
		final Rectangle ideal = dialogueService.getDialogueIdealBounds();
		if (!isValid(ideal) || !isValid(logical) || !isValid(physical)) {
			clear();
			return;
		}

		final boolean foregroundObscured = dialogueService.isDialogueContentObscuredByObstacle();
		final boolean clipped = dialogueService.isDialogueContentClipped(foregroundObscured);
		final String deferredReason = dialogueService.dialogueFitOutlineDeferralReason();
		final boolean viewportAligned = sameBounds(logical, physical);

		/*
		 * The physical UNIVERSE may lag the requested viewport during native relayout.
		 * Only show the outlines when actual content is clipped, the viewport has
		 * reached its expected position, and a pending fit is not still settling.
		 */
		final boolean drawOutline = clipped && viewportAligned && deferredReason == null;
		desiredBounds = drawOutline ? new Rectangle(ideal) : null;
		syncedLogicalBounds = new Rectangle(logical);

		if (diagnostics != null && diagnostics.isEnabled()) {
			final String state = !viewportAligned ? "WAIT_VIEWPORT_SYNC" : clipped && deferredReason != null ? "DEFERRED_FIT" : clipped ? "CLIPPED" : "CONTENT_VISIBLE";
			final Rectangle selected = dialogueService.getDialogueDesiredBounds();
			final String report = "state=" + state + " | ideal=" + rectangleText(ideal) + " | selected=" + rectangleText(selected) + " | logical=" + rectangleText(logical) + " | physical=" + rectangleText(physical) + " | native=" + rectangleText(dialogueService.getDialogueNativeOverlayBounds()) + " | geometryClipped=" + (exceeds(ideal, logical) || selected != null && exceeds(selected, logical)) + " | foregroundObscured=" + foregroundObscured + " | outline=" + (drawOutline ? "DRAW" : "HOLD") + " | reason=" + (deferredReason != null ? deferredReason : "-");
			// A state transition is useful; every pixel of an active drag is not.
			diagnostics.changed("fit-outline", "Dialogue Fit Outline", state + ":" + deferredReason, report);
		}
	}

	private void clear() {
		desiredBounds = null;
		syncedLogicalBounds = null;
		if (diagnostics != null) {
			diagnostics.reset("fit-outline");
			diagnostics.reset("fit-outline-transform");
		}
	}

	private static boolean isValid(Rectangle rectangle) {
		return rectangle != null && rectangle.width > 0 && rectangle.height > 0;
	}

	private static boolean sameBounds(Rectangle left, Rectangle right) {
		return left != null && right != null && Math.abs(left.x - right.x) <= RECTANGLE_TOLERANCE && Math.abs(left.y - right.y) <= RECTANGLE_TOLERANCE && Math.abs(left.width - right.width) <= RECTANGLE_TOLERANCE && Math.abs(left.height - right.height) <= RECTANGLE_TOLERANCE;
	}

	private static boolean exceeds(Rectangle full, Rectangle viewport) {
		return full.width > viewport.width + RECTANGLE_TOLERANCE || full.height > viewport.height + RECTANGLE_TOLERANCE;
	}

	private static String rectangleText(Rectangle rectangle) {
		return rectangle == null ? "-" : rectangle.x + "," + rectangle.y + "," + rectangle.width + "x" + rectangle.height;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		// Painting is read-only; native fitting and placement are never invoked here.
		if (dialogueService == null || !isValid(desiredBounds)) {
			return null;
		}

		/* Recheck the viewport immediately before drawing. RuneLite can relocate
		 * the host between sync() and render(), so never paint a stale ideal fit. */
		final Rectangle logical = dialogueService.getDialogueEffectiveBounds();
		final Rectangle physical = dialogueService.getDialogueRenderedViewportBounds();
		final Rectangle ideal = dialogueService.getDialogueIdealBounds();
		if (!isValid(ideal) || !isValid(logical) || !isValid(physical) || !dialogueService.isDialogueContentClipped() || dialogueService.dialogueFitOutlineDeferralReason() != null || !sameBounds(logical, physical) || !sameBounds(logical, syncedLogicalBounds) || !sameBounds(ideal, desiredBounds)) {
			return null;
		}

		if (diagnostics != null && diagnostics.isEnabled()) {
			final String transform = graphics.getTransform().toString();
			diagnostics.changed("fit-outline-transform", "Dialogue Fit Transform", transform, "transform=" + transform + " | ideal=" + rectangleText(ideal) + " | viewport=" + rectangleText(physical));
		}

		// Orange shows the required footprint; pink follows the rendered UNIVERSE.
		final Color previousColor = graphics.getColor();
		try {
			graphics.setColor(DESIRED_COLOR);
			graphics.drawRect(ideal.x, ideal.y, ideal.width - 1, ideal.height - 1);
			graphics.setColor(CLIPPED_COLOR);
			graphics.drawRect(physical.x, physical.y, physical.width - 1, physical.height - 1);
		} finally {
			graphics.setColor(previousColor);
		}
		return null;
	}
}
