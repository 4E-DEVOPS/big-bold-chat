package com.bigboldchat.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/*
 * Transparent RuneLite overlay used as the movable handle for split private chat.
 */
public final class PrivateChatOverlay extends Overlay {
	private Rectangle naturalBounds;

	public PrivateChatOverlay() {
		setPriority(Overlay.PRIORITY_HIGHEST);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPosition(OverlayPosition.DYNAMIC);
		setMovable(true);
		setSnappable(true);
	}

	@Override
	public String getName() {
		return "CHATXL_SPLIT_PM";
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (naturalBounds == null || naturalBounds.isEmpty()) {
			return null;
		}

		/*
		 * The drag surface represents the PM layout rectangle, not the union of
		 * text-widget widths. This keeps horizontal Alt-drag usable even when a
		 * stale or malformed row temporarily extends beyond the effective width.
		 */
		if (!isManuallyPositioned()) {
			getBounds().setLocation(naturalBounds.x, naturalBounds.y);
		}

		return new Dimension(naturalBounds.width, naturalBounds.height);
	}

	public boolean isManuallyPositioned() {
		return getPreferredLocation() != null || getPreferredPosition() != null;
	}

	public void setNaturalBounds(Rectangle bounds) {
		naturalBounds = bounds == null
				? null
				: new Rectangle(bounds);
	}
}
