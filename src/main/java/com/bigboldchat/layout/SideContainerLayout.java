package com.bigboldchat.layout;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Owns plugin-managed layout changes to the Modern side container.
 */
public final class SideContainerLayout {
	private final Client client;

	private boolean nativeLayoutRunning;
	private boolean pluginTwoRows;
	private boolean oneRowKnown;
	private int oneRowMovableX;
	private int oneRowMovableY;
	private int oneRowContainerY;
	private RelativeBounds movableOneRow;
	private List<RelativeBounds> movableChildrenOneRow = new ArrayList<>();
	private RelativeBounds backgroundOneRow;
	private RelativeBounds containerOneRow;

	public SideContainerLayout(Client client) {
		this.client = client;
	}

	/**
	 * Suspends plugin row ownership while RuneScape chooses its native layout.
	 */
	public void beginNativeLayout() {
		if (!isModernLayout()) {
			reset();
			return;
		}

		nativeLayoutRunning = true;
	}

	/**
	 * Accepts the row arrangement chosen by RuneScape's native resize script.
	 */
	public void endNativeLayout() {
		nativeLayoutRunning = false;
		pluginTwoRows = false;

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			clearOneRow();
			return;
		}

		if (isTwoRows(widgets.staticLayer, widgets.movableLayer)) {
			clearOneRow();
			return;
		}

		captureOneRow(widgets);
	}

	/**
	 * Gives the Modern button strip a second row when the configured chatbox
	 * reaches only the movable half of the native one-row strip.
	 */
	public Result apply(Widget slot, int configuredWidth, int configuredHeight) {
		if (slot == null || !isModernLayout()) {
			reset();
			return Result.NONE;
		}

		if (nativeLayoutRunning) {
			return Result.NONE;
		}

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			return Result.NONE;
		}

		final boolean currentTwoRows = isTwoRows(widgets.staticLayer, widgets.movableLayer);
		if (pluginTwoRows && !currentTwoRows) {
			pluginTwoRows = false;
			captureOneRow(widgets);
		} else if (!pluginTwoRows) {
			if (currentTwoRows) {
				return Result.NONE;
			}

			captureOneRow(widgets);
		}

		if (!oneRowKnown) {
			return Result.NONE;
		}

		final Rectangle desired = desiredBounds(slot, configuredWidth, configuredHeight);
		if (desired == null) {
			return Result.NONE;
		}

		if (needsTwoRows(desired, widgets)) {
			return pluginTwoRows
					? Result.NONE
					: applyTwoRows(widgets, desired);
		}

		return pluginTwoRows
				? applyOneRow(widgets)
				: Result.NONE;
	}

	public Result restoreNative() {
		if (!pluginTwoRows || !oneRowKnown) {
			reset();
			return Result.NONE;
		}

		final Widgets widgets = getWidgets();
		if (widgets == null) {
			reset();
			return Result.NONE;
		}

		final Result result = applyOneRow(widgets);
		reset();
		return result;
	}

	public void reset() {
		nativeLayoutRunning = false;
		pluginTwoRows = false;
		clearOneRow();
	}

	private boolean needsTwoRows(Rectangle desired, Widgets widgets) {
		final Rectangle staticBounds = InterfaceBounds.liveBounds(widgets.staticLayer);
		if (staticBounds == null) {
			return false;
		}

		final boolean staticButtonsHit = hitsVisibleChildren(desired, widgets.staticLayer);
		final boolean movableButtonsHit = pluginTwoRows
				? hitsRelativeChildren(desired, staticBounds, movableChildrenOneRow, movableOneRow)
				: hitsVisibleChildren(desired, widgets.movableLayer);
		final Rectangle backgroundBounds = pluginTwoRows && backgroundOneRow != null
				? backgroundOneRow.resolve(staticBounds)
				: InterfaceBounds.liveBounds(widgets.background);
		final Rectangle containerBounds = pluginTwoRows && containerOneRow != null
				? containerOneRow.resolve(staticBounds)
				: InterfaceBounds.liveBounds(widgets.container);

		return !staticButtonsHit
				&& movableButtonsHit
				&& !intersects(desired, backgroundBounds)
				&& !intersects(desired, containerBounds)
				&& !intersects(desired, InterfaceBounds.liveBounds(widgets.mapContainer))
				&& !intersects(desired, InterfaceBounds.liveBounds(widgets.orbs));
	}

	private Result applyTwoRows(Widgets widgets, Rectangle desired) {
		captureOneRow(widgets);
		if (!oneRowKnown) {
			return Result.NONE;
		}

		final int rowHeight = Math.max(1, Math.max(widgets.staticLayer.getHeight(), widgets.movableLayer.getHeight()));
		final int movableX = widgets.staticLayer.getOriginalX();
		final int movableY = widgets.staticLayer.getOriginalY() + rowHeight;
		final int containerY = oneRowContainerY + rowHeight;
		int mutations = 0;
		int revalidates = 0;

		if (widgets.movableLayer.getOriginalX() != movableX || widgets.movableLayer.getOriginalY() != movableY) {
			widgets.movableLayer.setOriginalX(movableX);
			widgets.movableLayer.setOriginalY(movableY);
			mutations++;

			widgets.movableLayer.revalidate();
			revalidates++;
		}

		if (intersects(desired, InterfaceBounds.liveBounds(widgets.movableLayer))) {
			widgets.movableLayer.setOriginalX(oneRowMovableX);
			widgets.movableLayer.setOriginalY(oneRowMovableY);
			mutations++;

			widgets.movableLayer.revalidate();
			revalidates++;
			return new Result(mutations, revalidates);
		}

		if (widgets.container.getOriginalY() != containerY) {
			widgets.container.setOriginalY(containerY);
			mutations++;

			widgets.container.revalidate();
			revalidates++;
		}

		pluginTwoRows = true;
		return new Result(mutations, revalidates);
	}

	private Result applyOneRow(Widgets widgets) {
		int mutations = 0;
		int revalidates = 0;

		if (widgets.movableLayer.getOriginalX() != oneRowMovableX
				|| widgets.movableLayer.getOriginalY() != oneRowMovableY) {
			widgets.movableLayer.setOriginalX(oneRowMovableX);
			widgets.movableLayer.setOriginalY(oneRowMovableY);
			mutations++;

			widgets.movableLayer.revalidate();
			revalidates++;
		}

		if (widgets.container.getOriginalY() != oneRowContainerY) {
			widgets.container.setOriginalY(oneRowContainerY);
			mutations++;

			widgets.container.revalidate();
			revalidates++;
		}

		pluginTwoRows = false;
		return new Result(mutations, revalidates);
	}

	private void captureOneRow(Widgets widgets) {
		final Rectangle staticBounds = InterfaceBounds.liveBounds(widgets.staticLayer);
		final Rectangle movableBounds = InterfaceBounds.liveBounds(widgets.movableLayer);
		if (staticBounds == null || movableBounds == null) {
			return;
		}

		oneRowMovableX = widgets.movableLayer.getOriginalX();
		oneRowMovableY = widgets.movableLayer.getOriginalY();
		oneRowContainerY = widgets.container.getOriginalY();
		movableOneRow = RelativeBounds.capture(staticBounds, movableBounds);
		movableChildrenOneRow = captureRelativeChildren(staticBounds, widgets.movableLayer);

		final Rectangle backgroundBounds = InterfaceBounds.liveBounds(widgets.background);
		backgroundOneRow = backgroundBounds != null
				? RelativeBounds.capture(staticBounds, backgroundBounds)
				: null;

		final Rectangle containerBounds = InterfaceBounds.liveBounds(widgets.container);
		containerOneRow = containerBounds != null
				? RelativeBounds.capture(staticBounds, containerBounds)
				: null;
		oneRowKnown = true;
	}

	private void clearOneRow() {
		oneRowKnown = false;
		movableOneRow = null;
		movableChildrenOneRow = new ArrayList<>();
		backgroundOneRow = null;
		containerOneRow = null;
	}

	private Rectangle desiredBounds(Widget slot, int configuredWidth, int configuredHeight) {
		final Rectangle slotBounds = InterfaceBounds.liveBounds(slot);
		if (slotBounds == null) {
			return null;
		}

		final int anchorX = slotBounds.x;
		final int anchorBottom = slotBounds.y + slotBounds.height;
		final int availableWidth = client.getCanvasWidth() - anchorX;
		final int availableHeight = anchorBottom;
		if (availableWidth <= 0 || availableHeight <= 0) {
			return null;
		}

		final int width = Math.min(configuredWidth, availableWidth);
		final int height = Math.min(configuredHeight, availableHeight);
		return new Rectangle(anchorX, anchorBottom - height, width, height);
	}

	private Widgets getWidgets() {
		if (!isModernLayout()) {
			return null;
		}

		final Widget staticLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER);
		final Widget movableLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER);
		final Widget container = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);
		if (staticLayer == null || movableLayer == null || container == null
				|| staticLayer.isHidden() || movableLayer.isHidden()) {
			return null;
		}

		return new Widgets(
				staticLayer,
				movableLayer,
				client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_BACKGROUND),
				container,
				client.getWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER),
				client.getWidget(InterfaceID.ToplevelPreEoc.ORBS));
	}

	private static boolean hitsVisibleChildren(Rectangle desired, Widget parent) {
		final List<Rectangle> children = visibleChildBounds(parent);
		if (children.isEmpty()) {
			return intersects(desired, InterfaceBounds.liveBounds(parent));
		}

		for (Rectangle child : children) {
			if (intersects(desired, child)) {
				return true;
			}
		}

		return false;
	}

	private static boolean hitsRelativeChildren(
			Rectangle desired, Rectangle anchor, List<RelativeBounds> children, RelativeBounds fallback) {
		if (children != null && !children.isEmpty()) {
			for (RelativeBounds child : children) {
				if (intersects(desired, child.resolve(anchor))) {
					return true;
				}
			}

			return false;
		}

		return fallback != null && intersects(desired, fallback.resolve(anchor));
	}

	private static List<RelativeBounds> captureRelativeChildren(Rectangle anchor, Widget parent) {
		final List<RelativeBounds> children = new ArrayList<>();

		captureRelativeChildren(children, anchor, parent != null
				? parent.getStaticChildren()
				: null);
		captureRelativeChildren(children, anchor, parent != null
				? parent.getDynamicChildren()
				: null);
		captureRelativeChildren(children, anchor, parent != null
				? parent.getNestedChildren()
				: null);

		return children;
	}

	private static void captureRelativeChildren(List<RelativeBounds> bounds, Rectangle anchor, Widget[] children) {
		if (children == null) {
			return;
		}

		for (Widget child : children) {
			final Rectangle childBounds = InterfaceBounds.liveBounds(child);
			if (childBounds != null) {
				bounds.add(RelativeBounds.capture(anchor, childBounds));
			}
		}
	}

	private static List<Rectangle> visibleChildBounds(Widget parent) {
		final List<Rectangle> bounds = new ArrayList<>();

		addVisibleChildren(bounds, parent != null
				? parent.getStaticChildren()
				: null);
		addVisibleChildren(bounds, parent != null
				? parent.getDynamicChildren()
				: null);
		addVisibleChildren(bounds, parent != null
				? parent.getNestedChildren()
				: null);

		return bounds;
	}

	private static void addVisibleChildren(List<Rectangle> bounds, Widget[] children) {
		if (children == null) {
			return;
		}

		for (Widget child : children) {
			final Rectangle childBounds = InterfaceBounds.liveBounds(child);
			if (childBounds != null) {
				bounds.add(childBounds);
			}
		}
	}

	private boolean isModernLayout() {
		return client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_PRE_EOC;
	}

	private static boolean isTwoRows(Widget staticLayer, Widget movableLayer) {
		return movableLayer.getOriginalY() != staticLayer.getOriginalY();
	}

	private static boolean intersects(Rectangle first, Rectangle second) {
		return first != null && second != null && first.intersects(second);
	}

	private static final class Widgets {
		private final Widget staticLayer;
		private final Widget movableLayer;
		private final Widget background;
		private final Widget container;
		private final Widget mapContainer;
		private final Widget orbs;

		private Widgets(
				Widget staticLayer,
				Widget movableLayer,
				Widget background,
				Widget container,
				Widget mapContainer,
				Widget orbs) {
			this.staticLayer = staticLayer;
			this.movableLayer = movableLayer;
			this.background = background;
			this.container = container;
			this.mapContainer = mapContainer;
			this.orbs = orbs;
		}
	}

	private static final class RelativeBounds {
		private final int x;
		private final int y;
		private final int width;
		private final int height;

		private RelativeBounds(int x, int y, int width, int height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		private static RelativeBounds capture(Rectangle anchor, Rectangle bounds) {
			return new RelativeBounds(
					bounds.x - anchor.x,
					bounds.y - anchor.y,
					bounds.width,
					bounds.height);
		}

		private Rectangle resolve(Rectangle anchor) {
			return new Rectangle(anchor.x + x, anchor.y + y, width, height);
		}
	}

	public static final class Result {
		private static final Result NONE = new Result(0, 0);

		private final int mutations;
		private final int revalidates;

		private Result(int mutations, int revalidates) {
			this.mutations = mutations;
			this.revalidates = revalidates;
		}

		public int getMutations() {
			return mutations;
		}

		public int getRevalidates() {
			return revalidates;
		}
	}
}
