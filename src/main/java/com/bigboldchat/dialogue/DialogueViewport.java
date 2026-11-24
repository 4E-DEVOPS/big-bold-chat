package com.bigboldchat.dialogue;

import com.bigboldchat.chatbox.ChatboxGeometry;
import com.bigboldchat.chatbox.ChatboxPlacement;
import com.bigboldchat.debug.DialogueDiagnostics;
import com.bigboldchat.layout.ChatboxBounds;

import java.awt.Rectangle;
import java.util.List;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * Measures dialogue-content visibility and the user-facing ideal-fit outline.
 * Read-only: never moves native overlays, changes chatbox configuration, or resizes widgets.
 * The shared native host belongs to the chatbox services.
 */
public final class DialogueViewport {
	private static final int CONTENT_EDGE_LEFT = 1;
	private static final int CONTENT_EDGE_RIGHT = 2;
	private static final int CONTENT_EDGE_TOP = 4;
	private static final int CONTENT_EDGE_BOTTOM = 8;
	private static final int CONTENT_EXTENTS_VERIFIED = 16;
	private static final int CONTENT_WIDGET_BOUNDS_TOLERANCE = 2;

	private final Client client;
	private final DialoguePrompts dialoguePrompts;
	private final ChatboxPlacement placement;

	private int idealCollisionEdges;
	private int idealCollisionFingerprint = Integer.MIN_VALUE;
	private boolean idealCollisionRetained;

	public DialogueViewport(Client client, DialoguePrompts prompts, ChatboxPlacement placement) {
		this.client = client;
		this.dialoguePrompts = prompts;
		this.placement = placement;
	}

	public static final class IdealSnapshot {
		final Rectangle desired;
		final Rectangle visible;
		final Rectangle reference;
		final int configuredHeight;
		final boolean buttonsHidden;
		final boolean constrainedWrap;
		final boolean cornerBottom;
		final int clipContentFingerprint;
		final boolean clipOriginObserved;
		final boolean clippedFallback;
		final boolean clipFromTop;
		final int activeEdges;
		final List<Rectangle> obstacles;
		final boolean traceEnabled;
		final DialogueDiagnostics diagnostics;

		public IdealSnapshot(Rectangle desired, Rectangle visible, Rectangle reference, int configuredHeight, boolean buttonsHidden, boolean constrainedWrap, boolean cornerBottom, int clipContentFingerprint, boolean clipOriginObserved, boolean clippedFallback, boolean clipFromTop, int activeEdges, List<Rectangle> obstacles, boolean traceEnabled, DialogueDiagnostics diagnostics) {
			this.desired = desired != null ? new Rectangle(desired) : null;
			this.visible = visible != null ? new Rectangle(visible) : null;
			this.reference = reference != null ? new Rectangle(reference) : null;
			this.configuredHeight = configuredHeight;
			this.buttonsHidden = buttonsHidden;
			this.constrainedWrap = constrainedWrap;
			this.cornerBottom = cornerBottom;
			this.clipContentFingerprint = clipContentFingerprint;
			this.clipOriginObserved = clipOriginObserved;
			this.clippedFallback = clippedFallback;
			this.clipFromTop = clipFromTop;
			this.activeEdges = activeEdges;
			this.obstacles = obstacles;
			this.traceEnabled = traceEnabled;
			this.diagnostics = diagnostics;
		}
	}

	/**
	 * ================================================================
	 * VISIBLE CONTENT EXTENTS & OBSTRUCTION
	 * ================================================================
	 */
	public int visibleContentEdges(Rectangle visible, boolean buttonsHidden) {
		if (visible == null || !dialoguePrompts.isActive()) {
			return 0;
		}
		if (visible.width <= 0 || visible.height <= 0) {
			return 0;
		}
		final Rectangle body = new Rectangle(visible.x, visible.y, visible.width, Math.max(1, ChatboxGeometry.bodyHeight(visible.height, buttonsHidden)));

		if (visibleMountedWidget(InterfaceID.Chatmenu.OPTIONS) != null) {
			int edges = 0;
			final Widget[] rows = client.getWidget(InterfaceID.Chatmenu.OPTIONS).getDynamicChildren();
			if (rows != null) {
				for (Widget row : rows) {
					/* The final option can lose its last pixels behind the
					 * controls. Unlike portrait text, option rows cannot
					 * use the general 2px visual-overflow tolerance. */
					edges |= dialogueContentWidgetEdges(row, body, false, 0);
				}
			}
			return edges;
		}
		if (visibleMountedWidget(InterfaceID.ChatLeft.UNIVERSE) != null) {
			return dialogueContentWidgetEdges(client.getWidget(InterfaceID.ChatLeft.NAME), body, false) | dialogueContentWidgetEdges(client.getWidget(InterfaceID.ChatLeft.TEXT), body, false) | dialogueContentWidgetEdges(client.getWidget(InterfaceID.ChatLeft.CONTINUE), body, false);
		}
		if (visibleMountedWidget(InterfaceID.ChatRight.UNIVERSE) != null) {
			return dialogueContentWidgetEdges(client.getWidget(InterfaceID.ChatRight.NAME), body, false) | dialogueContentWidgetEdges(client.getWidget(InterfaceID.ChatRight.TEXT), body, false) | dialogueContentWidgetEdges(client.getWidget(InterfaceID.ChatRight.CONTINUE), body, false);
		}
		if (visibleMountedWidget(InterfaceID.Messagebox.UNIVERSE) != null) {
			return dialogueContentWidgetEdges(client.getWidget(InterfaceID.Messagebox.TEXT), body, false) | dialogueContentWidgetEdges(client.getWidget(InterfaceID.Messagebox.CONTINUE), body, false);
		}
		final Widget item = visibleMountedWidget(InterfaceID.Objectbox.ITEM);
		final Widget itemText = visibleMountedWidget(InterfaceID.Objectbox.TEXT);
		if (item != null || itemText != null) {
			/* The item title and continue label need not be direct dynamic
			 * children of the root. Use the mounted leaves that the dialogue
			 * layout service already identified instead of assuming an index. */
			int edges = dialogueContentWidgetEdges(item, body, true) | dialogueContentWidgetEdges(itemText, body, false) | dialogueContentWidgetEdges(dialoguePrompts.itemTitleWidget(), body, false) | dialogueContentWidgetEdges(dialoguePrompts.itemContinueWidget(), body, false);
			final Widget root = item != null ? item.getParent() : itemText.getParent();
			if (root != null) {
				final Widget[] children = root.getDynamicChildren();
				if (children != null) {
					for (Widget child : children) {
						edges |= dialogueContentWidgetEdges(child, body, false);
					}
				}
			}
			return edges;
		}
		return 0;
	}

	/*
	 * The 200px minimum permits a side obstacle to remain over the viewport.
	 * A fitting text rectangle is therefore not necessarily visible. Check
	 * mounted TEXT leaves against real foreground rectangles, clipped to
	 * their visible body and ancestor region. This is a read-only rendering
	 * assessment, never an instruction to move or resize the host.
	 */
	public boolean isContentObscuredByObstacles(Rectangle visible, boolean buttonsHidden, List<Rectangle> obstacles) {
		if (visible == null || obstacles == null || obstacles.isEmpty() || !dialoguePrompts.isActive()) {
			return false;
		}
		final Rectangle body = new Rectangle(visible.x, visible.y, visible.width, Math.max(1, ChatboxGeometry.bodyHeight(visible.height, buttonsHidden)));
		final Widget options = visibleMountedWidget(InterfaceID.Chatmenu.OPTIONS);
		if (options != null) {
			final Widget[] rows = options.getDynamicChildren();
			if (rows != null) {
				for (Widget row : rows) {
					if (isTextObscuredByObstacle(row, body, obstacles)) {
						return true;
					}
				}
			}
			return false;
		}
		if (visibleMountedWidget(InterfaceID.ChatLeft.UNIVERSE) != null) {
			return isTextObscuredByObstacle(client.getWidget(InterfaceID.ChatLeft.NAME), body, obstacles) || isTextObscuredByObstacle(client.getWidget(InterfaceID.ChatLeft.TEXT), body, obstacles) || isTextObscuredByObstacle(client.getWidget(InterfaceID.ChatLeft.CONTINUE), body, obstacles);
		}
		if (visibleMountedWidget(InterfaceID.ChatRight.UNIVERSE) != null) {
			return isTextObscuredByObstacle(client.getWidget(InterfaceID.ChatRight.NAME), body, obstacles) || isTextObscuredByObstacle(client.getWidget(InterfaceID.ChatRight.TEXT), body, obstacles) || isTextObscuredByObstacle(client.getWidget(InterfaceID.ChatRight.CONTINUE), body, obstacles);
		}
		if (visibleMountedWidget(InterfaceID.Messagebox.UNIVERSE) != null) {
			return isTextObscuredByObstacle(client.getWidget(InterfaceID.Messagebox.TEXT), body, obstacles) || isTextObscuredByObstacle(client.getWidget(InterfaceID.Messagebox.CONTINUE), body, obstacles);
		}
		if (visibleMountedWidget(InterfaceID.Objectbox.ITEM) != null || visibleMountedWidget(InterfaceID.Objectbox.TEXT) != null) {
			return isTextObscuredByObstacle(client.getWidget(InterfaceID.Objectbox.TEXT), body, obstacles) || isTextObscuredByObstacle(dialoguePrompts.itemTitleWidget(), body, obstacles) || isTextObscuredByObstacle(dialoguePrompts.itemContinueWidget(), body, obstacles);
		}
		return false;
	}

	private static boolean isTextObscuredByObstacle(Widget widget, Rectangle body, List<Rectangle> obstacles) {
		if (widget == null || widget.getParent() == null || widget.isHidden() || widget.getType() != WidgetType.TEXT || widget.getText() == null || widget.getText().trim().isEmpty()) {
			return false;
		}
		final Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
			return false;
		}
		final Rectangle drawnRegion = bounds.intersection(visibleAncestorClip(widget, body));
		if (drawnRegion.isEmpty()) {
			return false;
		}
		for (Rectangle obstacle : obstacles) {
			if (obstacle != null && obstacle.width > 0 && obstacle.height > 0 && drawnRegion.intersects(obstacle)) {
				return true;
			}
		}
		return false;
	}

	private static int dialogueContentWidgetEdges(Widget widget, Rectangle body, boolean graphic) {
		return dialogueContentWidgetEdges(widget, body, graphic, CONTENT_WIDGET_BOUNDS_TOLERANCE);
	}

	private static int dialogueContentWidgetEdges(Widget widget, Rectangle body, boolean graphic, int tolerance) {
		if (widget == null || widget.getParent() == null || widget.isHidden() || (!graphic && (widget.getType() != WidgetType.TEXT || widget.getText() == null || widget.getText().trim().isEmpty()))) {
			return 0;
		}
		final Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
			return 0;
		}
		/* Text can be inside the CHAT_UNIVERSE while its own parent (for
		 * example NPC_SAFE) clips the name or the continue row. Comparing
		 * against the chatbox body alone incorrectly reports CONTENT_FITS.
		 * Intersect the effective viewport with each real ancestor's visible
		 * rectangle; do not use a speculative desired rectangle as a clip. */
		final Rectangle clip = visibleAncestorClip(widget, body);
		int edges = CONTENT_EXTENTS_VERIFIED;
		if (bounds.x < clip.x - tolerance) {
			edges |= CONTENT_EDGE_LEFT;
		}
		if ((long) bounds.x + bounds.width > (long) clip.x + clip.width + tolerance) {
			edges |= CONTENT_EDGE_RIGHT;
		}
		if (bounds.y < clip.y - tolerance) {
			edges |= CONTENT_EDGE_TOP;
		}
		if ((long) bounds.y + bounds.height > (long) clip.y + clip.height + tolerance) {
			edges |= CONTENT_EDGE_BOTTOM;
		}
		return edges;
	}

	private static Rectangle visibleAncestorClip(Widget widget, Rectangle body) {
		Rectangle clip = new Rectangle(body);
		Widget ancestor = widget.getParent();
		for (int depth = 0; ancestor != null && depth < 16; depth++) {
			final Rectangle parentBounds = ancestor.getBounds();
			if (parentBounds != null && parentBounds.width > 0 && parentBounds.height > 0) {
				clip = clip.intersection(parentBounds);
			}
			final Widget parent = ancestor.getParent();
			if (parent == ancestor) {
				break;
			}
			ancestor = parent;
		}
		return clip;
	}

	/* A speaker name, item title, option heading or message text can be
	 * clipped by its parent even when the overall viewport has room. Account
	 * for the measured missing edge in the user-facing ideal outline, not only the
	 * theoretical text-height requirement. This never changes a fit proposal. */
	private int leadingTextTopOverflow(Rectangle visible, boolean buttonsHidden) {
		final Rectangle body = contentBody(visible, buttonsHidden);
		int overflow = Math.max(textTopOverflow(visibleMountedWidget(InterfaceID.ChatLeft.NAME), body), textTopOverflow(visibleMountedWidget(InterfaceID.ChatRight.NAME), body));
		overflow = Math.max(overflow, textTopOverflow(visibleMountedWidget(InterfaceID.Messagebox.TEXT), body));
		overflow = Math.max(overflow, textTopOverflow(dialoguePrompts.itemTitleWidget(), body));
		final Widget options = visibleMountedWidget(InterfaceID.Chatmenu.OPTIONS);
		if (options != null) {
			final Widget[] rows = options.getDynamicChildren();
			if (rows != null) {
				for (Widget row : rows) {
					overflow = Math.max(overflow, textTopOverflow(row, body));
				}
			}
		}
		return overflow;
	}

	private int continueTextBottomOverflow(Rectangle visible, boolean buttonsHidden) {
		final Rectangle body = contentBody(visible, buttonsHidden);
		int overflow = Math.max(textBottomOverflow(visibleMountedWidget(InterfaceID.ChatLeft.CONTINUE), body), textBottomOverflow(visibleMountedWidget(InterfaceID.ChatRight.CONTINUE), body));
		overflow = Math.max(overflow, textBottomOverflow(visibleMountedWidget(InterfaceID.Messagebox.CONTINUE), body));
		return Math.max(overflow, textBottomOverflow(dialoguePrompts.itemContinueWidget(), body));
	}

	private static Rectangle contentBody(Rectangle visible, boolean buttonsHidden) {
		return new Rectangle(visible.x, visible.y, visible.width, Math.max(1, ChatboxGeometry.bodyHeight(visible.height, buttonsHidden)));
	}

	private static int textTopOverflow(Widget widget, Rectangle body) {
		if (!visibleText(widget)) {
			return 0;
		}
		final Rectangle bounds = widget.getBounds();
		final Rectangle clip = visibleAncestorClip(widget, body);
		return Math.max(0, clip.y - bounds.y - CONTENT_WIDGET_BOUNDS_TOLERANCE);
	}

	private static int textBottomOverflow(Widget widget, Rectangle body) {
		if (!visibleText(widget)) {
			return 0;
		}
		final Rectangle bounds = widget.getBounds();
		final Rectangle clip = visibleAncestorClip(widget, body);
		return (int) Math.max(0L, (long) bounds.y + bounds.height - ((long) clip.y + clip.height) - CONTENT_WIDGET_BOUNDS_TOLERANCE);
	}

	private static boolean visibleText(Widget widget) {
		return widget != null && widget.getParent() != null && !widget.isHidden() && widget.getType() == WidgetType.TEXT && widget.getText() != null && !widget.getText().trim().isEmpty() && widget.getBounds() != null && !widget.getBounds().isEmpty();
	}

	public Rectangle textBounds() {
		if (!dialoguePrompts.isActive()) {
			return null;
		}

		Widget widget = visibleMountedWidget(InterfaceID.ChatLeft.TEXT);
		if (widget == null) {
			widget = visibleMountedWidget(InterfaceID.ChatRight.TEXT);
		}
		if (widget == null) {
			widget = visibleMountedWidget(InterfaceID.Messagebox.TEXT);
		}
		return widget != null ? copyValidWidgetBounds(widget) : null;
	}

	public Rectangle continueBounds() {
		if (!dialoguePrompts.isActive()) {
			return null;
		}

		Widget widget = visibleMountedWidget(InterfaceID.ChatLeft.CONTINUE);
		if (widget == null) {
			widget = visibleMountedWidget(InterfaceID.ChatRight.CONTINUE);
		}
		if (widget == null) {
			widget = visibleMountedWidget(InterfaceID.Messagebox.CONTINUE);
		}
		return widget != null ? copyValidWidgetBounds(widget) : null;
	}

	private Widget visibleMountedWidget(int widgetId) {
		final Widget widget = client.getWidget(widgetId);
		return widget != null && widget.getParent() != null && !widget.isHidden() && widget.getWidth() > 0 && widget.getHeight() > 0 ? widget : null;
	}

	private static Rectangle copyValidWidgetBounds(Widget widget) {
		final Rectangle bounds = widget != null ? widget.getBounds() : null;
		return bounds != null && bounds.width > 0 && bounds.height > 0 ? new Rectangle(bounds) : null;
	}

	public static int touchingVerticalObstacles(Rectangle visible, List<Rectangle> obstacles) {
		if (visible == null || obstacles == null) {
			return 0;
		}
		final long visibleRight = (long) visible.x + visible.width;
		final long visibleBottom = (long) visible.y + visible.height;
		int edges = 0;
		for (Rectangle obstacle : obstacles) {
			if (obstacle == null || obstacle.width <= 0 || obstacle.height <= 0 || obstacle.x >= visibleRight || (long) obstacle.x + obstacle.width <= visible.x) {
				continue;
			}
			if (Math.abs((long) obstacle.y - visibleBottom) <= 1) {
				edges |= 8;
			}
			if (Math.abs((long) obstacle.y + obstacle.height - visible.y) <= 1) {
				edges |= 4;
			}
		}
		return edges;
	}

	/**
	 * ================================================================
	 * IDEAL FIT OUTLINE GEOMETRY
	 * ================================================================
	 */
	public void rememberIdealCollision(ChatboxBounds.Result result, List<Rectangle> obstacles) {
		if (!dialoguePrompts.isActive() || result == null) {
			idealCollisionEdges = 0;
			idealCollisionFingerprint = Integer.MIN_VALUE;
			idealCollisionRetained = false;
			return;
		}
		final int fingerprint = dialoguePrompts.fitFingerprint();
		int edges = 0;
		if (result.isLeftCollision()) {
			edges |= 1;
		}
		if (result.isRightCollision()) {
			edges |= 2;
		}
		if (result.isTopCollision()) {
			edges |= 4;
		}
		if (result.isBottomCollision()) {
			edges |= 8;
		}
		if (edges != 0) {
			idealCollisionEdges = edges;
			idealCollisionFingerprint = fingerprint;
			idealCollisionRetained = false;
			return;
		}
		final Rectangle visible = result.getEffectiveBounds();
		final boolean retainedContact = idealCollisionEdges != 0 && idealCollisionFingerprint == fingerprint && !DialogueResizeService.clearDialogueObstacles(visible, obstacles);
		if (!retainedContact) {
			idealCollisionEdges = 0;
		}
		idealCollisionFingerprint = fingerprint;
		idealCollisionRetained = retainedContact;
	}

	public Rectangle idealBounds(IdealSnapshot snapshot) {
		if (snapshot == null || snapshot.desired == null || snapshot.visible == null || !dialoguePrompts.isActive()) {
			return null;
		}
		/* The actual viewport width determines text wrapping. Reusing a separate
		 * optimal width would compare two different, valid dialogue layouts. */
		final Rectangle visible = snapshot.visible;
		final int textWidth = visible.width;
		if (textWidth <= 0) {
			return null;
		}
		final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(textWidth, snapshot.constrainedWrap);
		if (!requirement.isActive()) {
			return null;
		}
		final int tabHeight = snapshot.buttonsHidden ? 0 : ChatboxGeometry.NATIVE_TAB_HEIGHT;
		final int requiredHeight = DialogueFitSolver.alignGrowth(requirement.getMinimumBodyHeight() + tabHeight, snapshot.configuredHeight);
		final int leadingTopOverflow = leadingTextTopOverflow(visible, snapshot.buttonsHidden);
		final int continueBottomOverflow = continueTextBottomOverflow(visible, snapshot.buttonsHidden);
		final int completeHeight = Math.max(Math.max(snapshot.desired.height, requiredHeight), visible.height + Math.max(leadingTopOverflow, continueBottomOverflow));
		/* The visual ideal uses the current collision viewport, not the original
		 * configured-size corner seed. Side contacts keep the same X; top
		 * contacts keep the actual bottom edge. This drives the gameplay outline. */
		final Rectangle desired = snapshot.desired;
		/* Retain the collision origin through orthogonal width recovery. The
		 * selected desired bounds may already be the minimum-height viewport,
		 * so comparing only desired against visible loses the BOTTOM contact. */
		final Rectangle reference = snapshot.reference;
		final List<Rectangle> obstacles = snapshot.obstacles;
		final int activeEdges = snapshot.activeEdges;
		/* The resolved collision direction wins even when the preferred rectangle
		 * was replaced with its 100px viewport. Historical displacement is used
		 * only when there is no tracked active contact for this dialogue page. */
		final boolean trackedContact = idealCollisionEdges != 0 && idealCollisionFingerprint == dialoguePrompts.fitFingerprint();
		final int resolvedEdges = trackedContact ? idealCollisionEdges : activeEdges;
		/* When two rectangles merely touch, the resolver may report NONE.
		 * Prefer that physical contact over the user's generic corner. The
		 * current content clipping origin is the final source of truth if no
		 * vertical edge is known (including a canvas-limited partial fit). */
		final int touchingEdges = (resolvedEdges & 12) == 0 ? touchingVerticalObstacles(visible, obstacles) : 0;
		final boolean topContact = (resolvedEdges & 4) != 0 || !trackedContact && visible.y > reference.y;
		final boolean bottomContact = (resolvedEdges & 8) != 0 || !trackedContact && visible.y + visible.height < reference.y + reference.height;
		final boolean topTouch = !topContact && !bottomContact && touchingEdges == 4;
		final boolean bottomTouch = !topContact && !bottomContact && touchingEdges == 8;
		final int bodyHeight = ChatboxGeometry.bodyHeight(visible.height, snapshot.buttonsHidden);
		final int visualEdges = visibleContentEdges(visible, snapshot.buttonsHidden);
		final boolean visualTop = (visualEdges & CONTENT_EDGE_TOP) != 0;
		final boolean visualBottom = (visualEdges & CONTENT_EDGE_BOTTOM) != 0;
		/* A known leaf-widget overflow direction wins over a historical
		 * corner/side contact. A LEFT-only collision cannot place the ideal
		 * above the box when the actual option/text extent spills below it. */
		final boolean explicitContentOrigin = !topContact && !bottomContact && !topTouch && !bottomTouch && !requirement.fitsMinimum(visible.width, bodyHeight);
		final boolean measuredClipTop = snapshot.clipContentFingerprint == dialoguePrompts.fitFingerprint() && (snapshot.clipOriginObserved || snapshot.clippedFallback) && snapshot.clipFromTop;
		final boolean contentTop = visualTop && !visualBottom || visualTop && visualBottom && measuredClipTop;
		final boolean contentBottom = visualBottom && !visualTop || visualTop && visualBottom && !measuredClipTop;
		final boolean idealTop = contentTop || !contentBottom && ((topContact && !bottomContact) || topTouch) || !contentBottom && !topContact && !bottomContact && !topTouch && !bottomTouch && explicitContentOrigin && measuredClipTop;
		final boolean idealBottom = contentBottom || !contentTop && ((bottomContact && !topContact) || bottomTouch) || !contentTop && !topContact && !bottomContact && !topTouch && !bottomTouch && explicitContentOrigin && !measuredClipTop;
		final int idealY = idealTop && !idealBottom ? visible.y + visible.height - completeHeight : idealBottom && !idealTop ? visible.y : snapshot.cornerBottom ? visible.y + visible.height - completeHeight : visible.y;
		final Rectangle ideal = new Rectangle(visible.x, idealY, textWidth, completeHeight);
		if (snapshot.traceEnabled && completeHeight > visible.height) {
			final String diagnostic = "direction=" + (idealTop && !idealBottom ? "TOP" : idealBottom && !idealTop ? "BOTTOM" : "USER_CORNER") + " | source=" + (contentTop || contentBottom ? "VISIBLE_CONTENT" : trackedContact ? idealCollisionRetained ? "RETAINED_COLLISION" : "RESOLVED_COLLISION" : activeEdges != 0 ? "ACTIVE_BASELINE" : topTouch || bottomTouch ? "ADJACENT_OBSTACLE" : explicitContentOrigin ? "CONTENT_CLIP_ORIGIN" : "USER_CORNER") + " | resolvedEdges=" + edgeNames((resolvedEdges & 1) != 0, (resolvedEdges & 2) != 0, (resolvedEdges & 4) != 0, (resolvedEdges & 8) != 0) + " | reference=" + boundsText(reference) + " | visible=" + boundsText(visible) + " | ideal=" + boundsText(ideal) + " | contactEdges=" + edgeNames(false, false, (touchingEdges & 4) != 0, (touchingEdges & 8) != 0) + " | visualContent=" + edgeNames((visualEdges & CONTENT_EDGE_LEFT) != 0, (visualEdges & CONTENT_EDGE_RIGHT) != 0, visualTop, visualBottom) + " | contentClipTop=" + snapshot.clipFromTop + " | canvasTop=" + (visible.y == 0) + " | canvasBottom=" + ((long) visible.y + visible.height == client.getCanvasHeight());
			snapshot.diagnostics.changed("ideal-collision-direction", "Ideal Collision Direction", diagnostic, diagnostic);
		}
		return ideal;
	}

	/**
	 * ================================================================
	 * PHYSICAL VIEWPORT & CLIPPING
	 * ================================================================
	 */
	public Rectangle renderedViewport(boolean resizable, Rectangle effective) {
		if (!resizable || !dialoguePrompts.isActive()) {
			return null;
		}
		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		if (universe != null && !universe.isHidden()) {
			final Rectangle physical = universe.getBounds();
			if (physical != null && physical.width > 0 && physical.height > 0) {
				return new Rectangle(physical);
			}
		}
		return effective;
	}

	public Rectangle physicalHost(boolean resizable, Widget slot) {
		return resizable && dialoguePrompts.isActive() && slot != null ? placement.hostBounds(slot) : null;
	}

	public Rectangle nativeOverlay(boolean resizable) {
		return resizable && dialoguePrompts.isActive() ? placement.nativeOverlayBounds() : null;
	}

	public boolean isContentClipped(Rectangle effective, boolean constrainedWrap, boolean buttonsHidden) {
		if (effective == null || !dialoguePrompts.isActive()) {
			return false;
		}
		if (effective.width <= 0 || effective.height <= 0) {
			return true;
		}
		final DialoguePrompts.FitRequirement requirement = dialoguePrompts.measureFit(effective.width, constrainedWrap);
		if (!requirement.isActive()) {
			return false;
		}
		final int visualEdges = visibleContentEdges(effective, buttonsHidden);
		if ((visualEdges & CONTENT_EXTENTS_VERIFIED) != 0) {
			return (visualEdges & (CONTENT_EDGE_LEFT | CONTENT_EDGE_RIGHT | CONTENT_EDGE_TOP | CONTENT_EDGE_BOTTOM)) != 0;
		}
		final int bodyHeight = ChatboxGeometry.bodyHeight(effective.height, buttonsHidden);
		return !requirement.fitsMinimum(effective.width, bodyHeight);
	}

	public void resetIdealCollision() {
		idealCollisionEdges = 0;
		idealCollisionFingerprint = Integer.MIN_VALUE;
		idealCollisionRetained = false;
	}

	private static String boundsText(Rectangle bounds) {
		return bounds == null ? "-" : bounds.x + "," + bounds.y + "," + bounds.width + "x" + bounds.height;
	}

	private static String edgeNames(boolean left, boolean right, boolean top, boolean bottom) {
		final StringBuilder names = new StringBuilder();
		if (left) {
			names.append("LEFT");
		}
		if (right) {
			names.append(names.length() > 0 ? ",RIGHT" : "RIGHT");
		}
		if (top) {
			names.append(names.length() > 0 ? ",TOP" : "TOP");
		}
		if (bottom) {
			names.append(names.length() > 0 ? ",BOTTOM" : "BOTTOM");
		}
		return names.length() == 0 ? "NONE" : names.toString();
	}
}
