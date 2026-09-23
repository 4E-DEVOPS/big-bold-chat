package com.bigboldchat.layout;

import com.bigboldchat.Configurations;

import java.awt.Rectangle;

import com.bigboldchat.overlay.PrivateChatOverlay;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.OverlayPosition;

/*
 * Owns split-PM external placement and width without replacing RuneScape's
 * font-dependent row construction.
 */
public final class PrivateChatLayout {
	private static final String CONFIG_GROUP = "bigboldchat";
	private static final String WIDTH_KEY = "splitPmWidth";
	private static final String WIDTH_OVERRIDE_KEY = "splitPmWidthOverride";
	private static final String CHATBOX_WIDTH_KEY = "chatboxWidth";
	private static final int MIN_WIDTH = 200;

	private final Client client;
	private final Configurations config;
	private final ConfigManager configManager;
	private final PrivateChatOverlay overlay;

	private Widget forcedHost;
	private Widget sizedHost;
	private int nativeOriginalWidth;
	private int appliedOriginalWidth;
	private boolean nativeWidthCaptured;

	/*
	 * Split-PM width inheritance is explicit state rather than inferred from the
	 * visible numeric value. This lets an inherited value mirror Chatbox Width
	 * while a user-entered value remains independent until Split-PM Width is reset.
	 */
	private boolean widthOverrideInitialized;
	private boolean widthOverridden;

	/*
	 * Width rewrapping changes the visible PM block height. Collision tests must
	 * not feed that newly-wrapped height straight back into the next width solve,
	 * or the PM can alternate between colliding and not colliding every frame.
	 */
	private int stableCollisionHeight;
	private int stableDesiredWidth;

	public PrivateChatLayout(
			Client client,
			Configurations config,
			ConfigManager configManager,
			PrivateChatOverlay overlay) {
		this.client = client;
		this.config = config;
		this.configManager = configManager;
		this.overlay = overlay;
	}

	public Result sync() {
		ensureWidthOverrideInitialized();

		final Widget pmHost = activePmHost();
		final Widget chatSlot = activeChatSlot();
		if (pmHost == null || chatSlot == null) {
			overlay.setNaturalBounds(null);
			restoreNative();
			return Result.NO_CHANGE;
		}

		if (forcedHost != null && forcedHost != pmHost) {
			restoreForcedHost();
		}
		if (sizedHost != null && sizedHost != pmHost) {
			restoreSizedHost();
		}

		final Rectangle contentBounds = visiblePmBounds();
		final int currentOffsetX = pmHost.getRelativeX() - pmHost.getOriginalX();
		final int currentOffsetY = pmHost.getRelativeY() - pmHost.getOriginalY();
		final Rectangle baseContentBounds = contentBounds == null
				? null
				: translated(contentBounds, -currentOffsetX, -currentOffsetY);

		/*
		 * RuneLite's BOTTOM_LEFT snap corner is the green anchor above the
		 * chatbox. When split PM is snapped there, follow the chatbox translation
		 * directly rather than deriving Y from a reflowing overlay rectangle.
		 */
		final boolean chatboxAnchored = overlay.getPreferredPosition() == OverlayPosition.BOTTOM_LEFT;
		final Offset target = overlay.isManuallyPositioned()
				&& !chatboxAnchored
				&& baseContentBounds != null
				? manualOffset(baseContentBounds)
				: chatboxOffset(chatSlot);

		/*
		 * Translate the PM host as one unit. REBUILDPMBOX remains responsible for
		 * every row's font-specific Y geometry; this layer owns only the external
		 * RuneLite placement and horizontal surface available to those rows.
		 */
		applyOffset(pmHost, target.x, target.y);

		final Rectangle expectedContentBounds = baseContentBounds == null
				? null
				: translated(baseContentBounds, target.x, target.y);
		final int desiredWidth = desiredWidth(chatSlot);
		final Rectangle collisionBounds = stableCollisionBounds(pmHost, expectedContentBounds, desiredWidth);
		final int effectiveWidth = collisionBounds == null
				? desiredWidth
				: PrivateChatBounds.resolveWidth(client, collisionBounds, desiredWidth);
		final boolean widthChanged = setEffectiveWidth(pmHost, effectiveWidth);

		if (expectedContentBounds != null && effectiveWidth >= desiredWidth) {
			stableCollisionHeight = Math.max(1, expectedContentBounds.height);
			stableDesiredWidth = desiredWidth;
		}

		if (widthChanged) {
			final Widget pmChat = client.getWidget(InterfaceID.PM_CHAT, 0);
			if (pmChat != null) {
				pmChat.revalidate();
			}
		}

		/*
		 * The overlay hitbox represents the effective PM surface width. Visible
		 * row widgets contribute only the vertical span so malformed/stale text
		 * cannot create an enormous horizontal drag region.
		 */
		final Rectangle overlayBounds = expectedContentBounds == null
				? null
				: new Rectangle(
						expectedContentBounds.x,
						expectedContentBounds.y,
						effectiveWidth,
						expectedContentBounds.height);
		overlay.setNaturalBounds(overlayBounds);

		return widthChanged
				? Result.WIDTH_CHANGED
				: Result.NO_CHANGE;
	}

	public void restoreNative() {
		restoreForcedHost();
		restoreSizedHost();
		stableCollisionHeight = 0;
		stableDesiredWidth = 0;
	}

	public void onConfigChanged(ConfigChanged event) {
		if (event == null || !CONFIG_GROUP.equals(event.getGroup())) {
			return;
		}

		ensureWidthOverrideInitialized();

		if (CHATBOX_WIDTH_KEY.equals(event.getKey())) {
			if (!widthOverridden) {
				syncInheritedWidthConfiguration();
			}
			return;
		}

		if (!WIDTH_KEY.equals(event.getKey())) {
			return;
		}

		final String storedWidth = configManager.getConfiguration(CONFIG_GROUP, WIDTH_KEY);
		if (storedWidth == null) {
			setWidthOverridden(false);
			syncInheritedWidthConfiguration();
			return;
		}

		/*
		 * Programmatic mirroring writes the same configured width as Chatbox Width
		 * and must not turn inheritance into an override. Once an override is
		 * already active, even matching Chatbox Width remains intentionally manual.
		 */
		if (!widthOverridden && parseWidth(storedWidth) == config.chatboxWidth()) {
			return;
		}

		setWidthOverridden(true);
	}

	private int desiredWidth(Widget chatSlot) {
		if (widthOverridden) {
			return Math.max(MIN_WIDTH, config.splitPmWidth());
		}

		/*
		 * Inherited mode mirrors the chatbox's live effective width, not merely the
		 * configured maximum. This keeps an anchored PM surface synchronized when
		 * the chatbox itself is temporarily narrowed by interface collision.
		 */
		return Math.max(MIN_WIDTH, chatSlot.getWidth());
	}

	private void ensureWidthOverrideInitialized() {
		if (widthOverrideInitialized) {
			return;
		}

		final String storedOverride = configManager.getConfiguration(CONFIG_GROUP, WIDTH_OVERRIDE_KEY);
		widthOverridden = storedOverride != null && Boolean.parseBoolean(storedOverride);
		widthOverrideInitialized = true;

		/*
		 * Older Phase-5 prototypes could leave splitPmWidth stored while it was
		 * intended to inherit. Without the explicit marker introduced here, treat
		 * that state as inherited and mirror the configured Chatbox Width.
		 */
		if (storedOverride == null) {
			setWidthOverridden(false);
			syncInheritedWidthConfiguration();
		}
	}

	private void setWidthOverridden(boolean overridden) {
		widthOverridden = overridden;
		configManager.setConfiguration(CONFIG_GROUP, WIDTH_OVERRIDE_KEY, overridden);
	}

	private void syncInheritedWidthConfiguration() {
		final int chatboxWidth = config.chatboxWidth();
		final String storedWidth = configManager.getConfiguration(CONFIG_GROUP, WIDTH_KEY);
		if (parseWidth(storedWidth) == chatboxWidth) {
			return;
		}

		configManager.setConfiguration(CONFIG_GROUP, WIDTH_KEY, chatboxWidth);
	}

	private static int parseWidth(String value) {
		if (value == null || value.isEmpty()) {
			return -1;
		}

		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	private Rectangle stableCollisionBounds(
			Widget pmHost,
			Rectangle expectedContentBounds,
			int desiredWidth) {
		if (pmHost == null || expectedContentBounds == null) {
			return null;
		}

		final int currentHeight = Math.max(1, expectedContentBounds.height);
		if (stableCollisionHeight <= 0
				|| stableDesiredWidth != desiredWidth
				|| pmHost.getWidth() >= desiredWidth) {
			stableCollisionHeight = currentHeight;
			stableDesiredWidth = desiredWidth;
		}

		/*
		 * Width wrapping may make the visible PM block taller. Use the last
		 * unconstrained height while solving collision so our own rewrap cannot
		 * make an obstacle disappear on the next frame and create a width/position
		 * oscillation. Bottom-left chatbox anchoring preserves the PM's lower edge;
		 * freely detached overlays preserve their top edge.
		 */
		final boolean bottomAnchored = !overlay.isManuallyPositioned()
				|| overlay.getPreferredPosition() == OverlayPosition.BOTTOM_LEFT;
		final int y = bottomAnchored
				? expectedContentBounds.y + expectedContentBounds.height - stableCollisionHeight
				: expectedContentBounds.y;

		return new Rectangle(
				expectedContentBounds.x,
				y,
				desiredWidth,
				stableCollisionHeight);
	}

	private Offset chatboxOffset(Widget chatSlot) {
		final Rectangle bounds = chatSlot.getBounds();
		if (bounds == null) {
			return Offset.ZERO;
		}

		/*
		 * Use the chatbox bottom-left corner to isolate external movement from
		 * ChatXL's temporary width/height changes. A normal resizable chatbox ends
		 * at the canvas bottom-left regardless of its current constrained size.
		 */
		return new Offset(
				bounds.x,
				bounds.y + bounds.height - client.getCanvasHeight());
	}

	private Offset manualOffset(Rectangle baseContentBounds) {
		final Rectangle overlayBounds = overlay.getBounds();
		return new Offset(
				overlayBounds.x - baseContentBounds.x,
				overlayBounds.y - baseContentBounds.y);
	}

	private void applyOffset(Widget pmHost, int offsetX, int offsetY) {
		if (forcedHost != null && forcedHost != pmHost) {
			restoreForcedHost();
		}

		if (offsetX == 0 && offsetY == 0 && !overlay.isManuallyPositioned()) {
			restoreForcedHost();
			return;
		}

		final int targetX = pmHost.getOriginalX() + offsetX;
		final int targetY = pmHost.getOriginalY() + offsetY;
		if (forcedHost == pmHost
				&& pmHost.getRelativeX() == targetX
				&& pmHost.getRelativeY() == targetY) {
			return;
		}

		pmHost.setForcedPosition(targetX, targetY);
		forcedHost = pmHost;
	}

	private boolean setEffectiveWidth(Widget pmHost, int width) {
		if (pmHost == null || width <= 0 || pmHost.getWidth() == width) {
			return false;
		}

		captureNativeWidth(pmHost);
		final int targetOriginalWidth;
		switch (pmHost.getWidthMode()) {
			case WidgetSizeMode.ABSOLUTE:
				targetOriginalWidth = width;
				break;
			case WidgetSizeMode.MINUS:
				final Widget parent = pmHost.getParent();
				if (parent == null) {
					return false;
				}

				targetOriginalWidth = Math.max(0, parent.getWidth() - width);
				break;
			case WidgetSizeMode.ABSOLUTE_16384THS:
				final Widget proportionalParent = pmHost.getParent();
				if (proportionalParent == null || proportionalParent.getWidth() <= 0) {
					return false;
				}

				targetOriginalWidth = Math.max(0, Math.min(16384,
						(int) Math.round(width * 16384.0 / proportionalParent.getWidth())));
				break;
			default:
				return false;
		}

		if (pmHost.getOriginalWidth() == targetOriginalWidth) {
			return false;
		}

		if (nativeWidthCaptured && pmHost.getOriginalWidth() != appliedOriginalWidth) {
			nativeOriginalWidth = pmHost.getOriginalWidth();
		}

		pmHost.setOriginalWidth(targetOriginalWidth);
		pmHost.revalidate();
		appliedOriginalWidth = targetOriginalWidth;
		return true;
	}

	private void captureNativeWidth(Widget pmHost) {
		if (sizedHost == pmHost && nativeWidthCaptured) {
			return;
		}

		if (sizedHost != null && sizedHost != pmHost) {
			restoreSizedHost();
		}

		sizedHost = pmHost;
		nativeOriginalWidth = pmHost.getOriginalWidth();
		appliedOriginalWidth = nativeOriginalWidth;
		nativeWidthCaptured = true;
	}

	private void restoreForcedHost() {
		if (forcedHost == null) {
			return;
		}

		forcedHost.setForcedPosition(-1, -1);
		forcedHost.revalidate();
		forcedHost = null;
	}

	private void restoreSizedHost() {
		if (sizedHost == null || !nativeWidthCaptured) {
			return;
		}

		if (sizedHost.getOriginalWidth() == appliedOriginalWidth
				&& sizedHost.getOriginalWidth() != nativeOriginalWidth) {
			sizedHost.setOriginalWidth(nativeOriginalWidth);
			sizedHost.revalidate();
		}

		sizedHost = null;
		nativeOriginalWidth = 0;
		appliedOriginalWidth = 0;
		nativeWidthCaptured = false;
	}

	private Rectangle visiblePmBounds() {
		final Widget pmChat = client.getWidget(InterfaceID.PM_CHAT, 0);
		if (pmChat == null || pmChat.isHidden()) {
			return null;
		}

		Rectangle bounds = null;
		for (Widget child : pmChat.getDynamicChildren()) {
			bounds = union(bounds, visibleBounds(child));
		}

		bounds = union(bounds, visibleBounds(client.getWidget(InterfaceID.PmChat.PM1)));
		bounds = union(bounds, visibleBounds(client.getWidget(InterfaceID.PmChat.PM2)));
		bounds = union(bounds, visibleBounds(client.getWidget(InterfaceID.PmChat.PM3)));
		bounds = union(bounds, visibleBounds(client.getWidget(InterfaceID.PmChat.PM4)));
		bounds = union(bounds, visibleBounds(client.getWidget(InterfaceID.PmChat.PM5)));
		return bounds;
	}

	private Widget activeChatSlot() {
		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
		}
		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			return client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);
		}
		return null;
	}

	private Widget activePmHost() {
		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.PM_CONTAINER);
		}
		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			return client.getWidget(InterfaceID.ToplevelPreEoc.PM_CONTAINER);
		}
		return null;
	}

	private static Rectangle visibleBounds(Widget widget) {
		if (widget == null || widget.isHidden() || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
			return null;
		}

		final Rectangle bounds = widget.getBounds();
		return bounds == null || bounds.isEmpty()
				? null
				: new Rectangle(bounds);
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

	private static Rectangle translated(Rectangle bounds, int x, int y) {
		final Rectangle translated = new Rectangle(bounds);
		translated.translate(x, y);
		return translated;
	}

	public static final class Result {
		private static final Result NO_CHANGE = new Result(false);
		private static final Result WIDTH_CHANGED = new Result(true);

		private final boolean widthChanged;

		private Result(boolean widthChanged) {
			this.widthChanged = widthChanged;
		}

		public boolean isWidthChanged() {
			return widthChanged;
		}
	}

	private static final class Offset {
		private static final Offset ZERO = new Offset(0, 0);
		private final int x;
		private final int y;

		private Offset(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}
}
