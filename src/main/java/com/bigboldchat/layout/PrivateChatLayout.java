package com.bigboldchat.layout;

import com.bigboldchat.Configurations;

import java.awt.Rectangle;

import com.bigboldchat.overlay.PrivateChatOverlay;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.ScriptPostFired;
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
	private static final int SURFACE_TOP_PADDING = 4;
	private static final int SURFACE_BOTTOM_PADDING = 2;

	/*
	 * RuneLite currently exposes no ScriptID constants for the native top-level
	 * relayout or chat_onchattransmit scripts. Observe them only; ChatXL never
	 * executes either script directly.
	 */
	private static final int TOPLEVEL_LAYOUT_SCRIPT = 1972;
	private static final int CHAT_ON_CHAT_TRANSMIT_SCRIPT = 663;

	private final Client client;
	private final Configurations config;
	private final ConfigManager configManager;
	private final PrivateChatOverlay overlay;

	private Widget forcedHost;
	private Widget shiftedContent;
	private int nativeContentScrollY;
	private int appliedContentShiftY;
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
	 * Width rewrapping changes the visible PM block height.
	 */
	private int stableCollisionHeight;
	private int stableDesiredWidth;
	private boolean nativeLayoutDirty;

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

		final Widget pmChat = client.getWidget(InterfaceID.PM_CHAT, 0);
		if (shiftedContent != null && shiftedContent != pmChat) {
			restoreContentShift();
		}

		final Rectangle contentBounds = visiblePmBounds();
		if (contentBounds == null) {
			overlay.setNaturalBounds(null);
			restoreContentShift();
			restoreForcedHost();
			restoreSizedHost();
			stableCollisionHeight = 0;
			stableDesiredWidth = 0;
			return Result.NO_CHANGE;
		}

		final int currentOffsetX = pmHost.getRelativeX() - pmHost.getOriginalX();
		final int currentOffsetY = pmHost.getRelativeY() - pmHost.getOriginalY();
		final int currentContentShiftY = shiftedContent == pmChat
				? appliedContentShiftY
				: 0;
		final Rectangle baseContentBounds = contentBounds == null
				? null
				: translated(
						contentBounds,
						-currentOffsetX,
						-currentOffsetY - currentContentShiftY);

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

		if (widthChanged && pmChat != null) {
			pmChat.revalidate();
		}

		/*
		 * Native split-PM rows are built upward from PM_CHAT and can therefore occupy
		 * negative Y coordinates relative to PM_CHAT. That works while the native
		 * clipping hierarchy remains above them, but a detached PM_CONTAINER can be
		 * moved below those rows after a top-level canvas relayout. The RuneLite
		 * overlay then remains visible while the game clips the actual PM text.
		 *
		 * Keep PM_CHAT at its native geometry. Move PM_CONTAINER upward only enough
		 * to contain the requested visible row surface, then compensate the children
		 * with a negative PM_CHAT scroll value. The rows stay at the overlay location
		 * while both native clipping containers begin at or above the visible text.
		 */
		applyClippingSafePlacement(pmHost, pmChat, target, expectedContentBounds);

		/*
		 * Width rewraps and newly-arrived PMs can change the real row union after
		 * the first placement calculation. Reconcile once against those live rows
		 * so a newly-grown top line cannot escape either native clipping envelope.
		 * The second pass is idempotent when the first placement already contains
		 * the complete visible row surface.
		 */
		Rectangle finalContentBounds = visiblePmBounds();
		if (finalContentBounds != null) {
			applyClippingSafePlacement(pmHost, pmChat, target, finalContentBounds);
			finalContentBounds = visiblePmBounds();
		}

		/*
		 * The RuneLite drag surface should match the actual visible PM stack, not a
		 * fixed or predicted height. Keep its width constrained to effectiveWidth so
		 * an unbreakable native word cannot create an oversized horizontal handle.
		 */
		final Rectangle overlayBounds = surfaceBounds(finalContentBounds, effectiveWidth);
		overlay.setNaturalBounds(overlayBounds);

		return widthChanged
				? Result.WIDTH_CHANGED
				: Result.NO_CHANGE;
	}

	public void onScriptPostFired(ScriptPostFired event) {
		if (event == null) {
			return;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == TOPLEVEL_LAYOUT_SCRIPT
				|| scriptId == CHAT_ON_CHAT_TRANSMIT_SCRIPT
				|| scriptId == ScriptID.SPLITPM_CHANGED) {
			nativeLayoutDirty = true;
		}
	}

	public void reconcileAfterClientTick() {
		if (!nativeLayoutDirty) {
			return;
		}

		nativeLayoutDirty = false;

		final Widget pmHost = activePmHost();
		final Widget pmChat = client.getWidget(InterfaceID.PM_CHAT, 0);
		final Rectangle contentBounds = visiblePmBounds();
		final Rectangle overlayBounds = overlay.getBounds();
		if (pmHost == null
				|| pmChat == null
				|| contentBounds == null
				|| overlayBounds == null
				|| overlayBounds.isEmpty()) {
			return;
		}

		/*
		 * ClientTick runs client scripts before PostClientTick. Top-level relayout
		 * can therefore fire 1972 several times and expose multiple intermediate PM
		 * geometries during one cycle. Only reconcile here, after that whole native
		 * script batch has completed, so ChatXL never fights a transient 1972 state.
		 *
		 * Recover the rows' native vertical offset from the final geometry, then
		 * reassert the existing movable-overlay placement before the next frame.
		 */
		final int nativeRowOffsetY = contentBounds.y
				- pmHost.getRelativeY()
				+ pmChat.getScrollY()
				- nativeContentScrollY;
		final int desiredContentY = overlayBounds.y + SURFACE_TOP_PADDING;
		final int contentShiftY = Math.max(
				0,
				SURFACE_TOP_PADDING - nativeRowOffsetY);
		final int targetHostY = desiredContentY
				- nativeRowOffsetY
				- contentShiftY;

		/*
		 * Horizontal PM rows do not use the vertical scroll compensation. Preserve
		 * their final native offset while translating the host so the row union
		 * remains aligned with the movable overlay.
		 */
		final int targetHostX = pmHost.getRelativeX()
				+ overlayBounds.x
				- contentBounds.x;

		applyContentShift(pmChat, contentShiftY);
		applyOffset(
				pmHost,
				targetHostX - pmHost.getOriginalX(),
				targetHostY - pmHost.getOriginalY());

		final Rectangle finalContentBounds = visiblePmBounds();
		if (finalContentBounds != null) {
			overlay.setNaturalBounds(surfaceBounds(
					finalContentBounds,
					Math.min(pmHost.getWidth(), finalContentBounds.width)));
		}
	}

	public void restoreNative() {
		restoreContentShift();
		restoreForcedHost();
		restoreSizedHost();
		stableCollisionHeight = 0;
		stableDesiredWidth = 0;
		nativeLayoutDirty = false;
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
		 * Treat this state as inherited and mirror the configured Chatbox Width.
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
				overlayBounds.y + SURFACE_TOP_PADDING - baseContentBounds.y);
	}

	private void applyClippingSafePlacement(
			Widget pmHost,
			Widget pmChat,
			Offset target,
			Rectangle expectedContentBounds) {
		if (pmChat == null || expectedContentBounds == null) {
			restoreContentShift();
			applyOffset(pmHost, target.x, target.y);
			return;
		}

		final int requestedHostY = pmHost.getOriginalY() + target.y;

		/*
		 * Width remains owned by PM_CONTAINER and horizontal placement remains
		 * unchanged. The currently-proven failure is vertical: the visible PM rows
		 * can sit above both native clipping containers after the canvas shrinks.
		 */
		final int envelopeY = Math.min(
				requestedHostY,
				expectedContentBounds.y - SURFACE_TOP_PADDING);
		final int contentShiftY = requestedHostY - envelopeY;

		applyContentShift(pmChat, contentShiftY);
		applyOffset(
				pmHost,
				target.x,
				envelopeY - pmHost.getOriginalY());
	}

	private void applyContentShift(Widget pmChat, int shiftY) {
		if (shiftedContent != null && shiftedContent != pmChat) {
			restoreContentShift();
		}

		if (shiftY <= 0) {
			restoreContentShift();
			return;
		}

		if (shiftedContent != pmChat) {
			shiftedContent = pmChat;
			nativeContentScrollY = pmChat.getScrollY();
		}

		final int targetScrollY = nativeContentScrollY - shiftY;
		if (pmChat.getScrollY() != targetScrollY) {
			pmChat.setScrollY(targetScrollY);
		}

		appliedContentShiftY = shiftY;
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

	private void restoreContentShift() {
		if (shiftedContent == null) {
			nativeContentScrollY = 0;
			appliedContentShiftY = 0;
			return;
		}

		if (shiftedContent.getScrollY() != nativeContentScrollY) {
			shiftedContent.setScrollY(nativeContentScrollY);
		}

		shiftedContent = null;
		nativeContentScrollY = 0;
		appliedContentShiftY = 0;
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
			bounds = union(bounds, visibleLiveBounds(child));
		}

		bounds = union(bounds, visibleLiveBounds(client.getWidget(InterfaceID.PmChat.PM1)));
		bounds = union(bounds, visibleLiveBounds(client.getWidget(InterfaceID.PmChat.PM2)));
		bounds = union(bounds, visibleLiveBounds(client.getWidget(InterfaceID.PmChat.PM3)));
		bounds = union(bounds, visibleLiveBounds(client.getWidget(InterfaceID.PmChat.PM4)));
		bounds = union(bounds, visibleLiveBounds(client.getWidget(InterfaceID.PmChat.PM5)));
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

	private static Rectangle visibleLiveBounds(Widget widget) {
		if (widget == null || widget.isHidden() || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
			return null;
		}

		/*
		 * getBounds() can retain the previous on-canvas PM coordinates after the
		 * externally-forced host has moved. Detached placement derives a native
		 * content origin by subtracting the host offset, so mixing those stale
		 * bounds with the live host offset compounds the translation every frame.
		 * Build the current canvas position from resolved relative coordinates and
		 * parent scroll offsets instead so both sides use the same geometry state.
		 */
		int x = widget.getRelativeX();
		int y = widget.getRelativeY();
		for (Widget parent = widget.getParent(); parent != null; parent = parent.getParent()) {
			x += parent.getRelativeX() - parent.getScrollX();
			y += parent.getRelativeY() - parent.getScrollY();
		}

		return new Rectangle(x, y, widget.getWidth(), widget.getHeight());
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

	private static Rectangle surfaceBounds(Rectangle contentBounds, int width) {
		if (contentBounds == null || contentBounds.isEmpty() || width <= 0) {
			return null;
		}

		return new Rectangle(
				contentBounds.x,
				contentBounds.y - SURFACE_TOP_PADDING,
				width,
				contentBounds.height + SURFACE_TOP_PADDING + SURFACE_BOTTOM_PADDING);
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
