package com.bigboldchat.debug;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bigboldchat.Configurations;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Observer-only diagnostics for Modern side-container layout and collisions.
 */
@Slf4j
public final class SideContainerDiagnostics {
	private static final String COMMAND = "debug-inventory";
	private static final String STATIC_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS1";
	private static final String MOVABLE_TABS_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_TABS2";
	private static final String INVENTORY_OVERLAY = "RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_PARENT";
	private static final int INT_STACK_TAIL_SIZE = 16;
	private static final int MAX_LOGGED_STRING_LENGTH = 200;

	private final Client client;
	private final Configurations config;
	private final OverlayManager overlayManager;
	private final Deque<TraceFrame> traceFrames = new ArrayDeque<>();

	private boolean armed;
	private CollisionState collisionState;

	public SideContainerDiagnostics(Client client, Configurations config) {
		this(client, config, null);
	}

	public SideContainerDiagnostics(Client client, Configurations config, OverlayManager overlayManager) {
		this.client = client;
		this.config = config;
		this.overlayManager = overlayManager;
	}

	/**
	 * ================================================================
	 * EVENT ROUTING
	 * ================================================================
	 */
	public synchronized boolean onCommandExecuted(CommandExecuted event) {
		if (event == null || !COMMAND.equalsIgnoreCase(event.getCommand())) {
			return false;
		}

		armed = !armed;
		traceFrames.clear();
		collisionState = null;

		log.debug("[Chat XL][Side Container Diagnostic] {}", armed ? "ARMED" : "DISARMED");

		return true;
	}

	public synchronized void onScriptPreFired(ScriptPreFired event) {
		if (!armed || event == null) {
			return;
		}

		logCollision(event.getScriptId());

		final Snapshot current = snapshot();
		final TraceFrame parent = traceFrames.peek();
		if (parent != null) {
			logChanges(parent, current, "BEFORE_CHILD", event.getScriptId());
			parent.checkpoint = current;
		}

		final TraceFrame frame = new TraceFrame();
		frame.scriptId = event.getScriptId();
		frame.depth = traceFrames.size() + 1;
		frame.checkpoint = current;
		frame.intTailBefore = traceIntTail();
		frame.stringsBefore = traceStackStrings();
		frame.arguments = traceScriptArguments(event);

		traceFrames.push(frame);
	}

	public synchronized void onScriptPostFired(ScriptPostFired event) {
		if (!armed || event == null || traceFrames.isEmpty()) {
			return;
		}

		final TraceFrame frame = traceFrames.peek();
		if (frame == null) {
			traceFrames.clear();
			return;
		}

		if (frame.scriptId != event.getScriptId()) {
			log.debug(
					"[Chat XL][Side Container Diagnostic]"
							+ " STACK MISMATCH"
							+ " | expectedScriptId={}"
							+ " | actualScriptId={}"
							+ " | depth={}",
					frame.scriptId,
					event.getScriptId(),
					traceFrames.size());
			traceFrames.clear();
			return;
		}

		final Snapshot current = snapshot();

		logCollision(frame.scriptId);
		logChanges(frame, current, "POST", -1);

		traceFrames.pop();

		final TraceFrame parent = traceFrames.peek();
		if (parent != null) {
			parent.checkpoint = current;
		}
	}

	public synchronized void reset() {
		traceFrames.clear();
		collisionState = null;
		armed = false;
	}

	/**
	 * ================================================================
	 * SNAPSHOT COMPARISON
	 * ================================================================
	 */
	private void logChanges(TraceFrame frame, Snapshot current, String phase, int nextScriptId) {
		if (frame == null || frame.checkpoint == null || current == null) {
			return;
		}

		final List<String> changes = compare(frame.checkpoint, current);
		if (changes.isEmpty()) {
			return;
		}

		log.debug(
				"[Chat XL][Side Container Diagnostic]"
						+ " SCRIPT"
						+ " | phase={}"
						+ " | depth={}"
						+ " | scriptId={}"
						+ " | nextScriptId={}"
						+ " | topLevel={}->{}"
						+ " | args={}"
						+ " | intTail={}"
						+ " | strings={}",
				phase,
				frame.depth,
				frame.scriptId,
				nextScriptId >= 0 ? Integer.toString(nextScriptId) : "-",
				frame.checkpoint.topLevel,
				current.topLevel,
				frame.arguments,
				frame.intTailBefore,
				frame.stringsBefore);

		for (String change : changes) {
			log.debug("[Chat XL][Side Container Diagnostic] CHANGE | scriptId={} | {}", frame.scriptId, change);
		}

	}

	private List<String> compare(Snapshot before, Snapshot after) {
		final List<String> changes = new ArrayList<>();
		if (before == null || after == null) {
			return changes;
		}

		if (before.topLevel != after.topLevel) {
			changes.add("component=TOPLEVEL | id=" + before.topLevel + "->" + after.topLevel);
		}

		final Set<String> labels = new LinkedHashSet<>();
		labels.addAll(before.widgets.keySet());
		labels.addAll(after.widgets.keySet());

		for (String label : labels) {
			final WidgetState beforeState = before.widgets.get(label);
			final WidgetState afterState = after.widgets.get(label);
			final String difference = WidgetState.describeDifference(label, beforeState, afterState);
			if (difference != null) {
				changes.add(difference);
			}
		}

		final Set<String> overlayLabels = new LinkedHashSet<>();
		overlayLabels.addAll(before.overlays.keySet());
		overlayLabels.addAll(after.overlays.keySet());

		for (String label : overlayLabels) {
			final OverlayState beforeState = before.overlays.get(label);
			final OverlayState afterState = after.overlays.get(label);
			final String difference = OverlayState.describeDifference(label, beforeState, afterState);
			if (difference != null) {
				changes.add(difference);
			}
		}

		return changes;
	}

	private Snapshot snapshot() {
		final Map<String, WidgetState> widgets = new LinkedHashMap<>();
		final Map<String, OverlayState> overlays = new LinkedHashMap<>();

		final Widget background = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_BACKGROUND);
		final Widget staticLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER);
		final Widget movableLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER);
		final Widget container = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);

		putWidget(widgets, "SIDE_BACKGROUND", background);
		putWidget(widgets, "SIDE_STATIC_LAYER", staticLayer);
		putWidget(widgets, "SIDE_MOVABLE_LAYER", movableLayer);
		putWidget(widgets, "SIDE_CONTAINER", container);
		putWidget(widgets, "MAP_CONTAINER", client.getWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER));
		putWidget(widgets, "ORBS", client.getWidget(InterfaceID.ToplevelPreEoc.ORBS));
		putChildren(widgets, "SIDE_STATIC", staticLayer);
		putChildren(widgets, "SIDE_MOVABLE", movableLayer);
		putOverlay(overlays, "TABS1_OVERLAY", findOverlay(STATIC_TABS_OVERLAY));
		putOverlay(overlays, "TABS2_OVERLAY", findOverlay(MOVABLE_TABS_OVERLAY));
		putOverlay(overlays, "INVENTORY_OVERLAY", findOverlay(INVENTORY_OVERLAY));

		return new Snapshot(client.getTopLevelInterfaceId(), widgets, overlays);
	}

	private void putOverlay(Map<String, OverlayState> overlays, String label, Overlay overlay) {
		overlays.put(label, new OverlayState(overlay));
	}

	private Overlay findOverlay(String name) {
		if (overlayManager == null) {
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

	private static void putWidget(Map<String, WidgetState> widgets, String label, Widget widget) {
		widgets.put(label, new WidgetState(widget));
	}

	private static void putChildren(Map<String, WidgetState> widgets, String prefix, Widget parent) {
		if (parent == null) {
			return;
		}

		putChildren(widgets, prefix + "_STATIC", parent.getStaticChildren());
		putChildren(widgets, prefix + "_DYNAMIC", parent.getDynamicChildren());
		putChildren(widgets, prefix + "_NESTED", parent.getNestedChildren());
	}

	private static void putChildren(Map<String, WidgetState> widgets, String prefix, Widget[] children) {
		if (children == null) {
			return;
		}

		for (int i = 0; i < children.length; i++) {
			putWidget(widgets, prefix + "_" + i, children[i]);
		}
	}

	/**
	 * ================================================================
	 * COLLISION DIAGNOSTICS
	 * ================================================================
	 */
	private void logCollision(int scriptId) {
		final CollisionState current = CollisionState.capture(client, config);
		if (current == null || current.sameAs(collisionState)) {
			return;
		}

		collisionState = current;

		log.debug(
				"[Chat XL][Side Container Diagnostic]"
						+ " COLLISION"
						+ " | scriptId={}"
						+ " | configuredWidth={}"
						+ " | buttonLayers={}"
						+ " | staticLayer={}"
						+ " | movableLayer={}"
						+ " | staticChildren={}"
						+ " | movableChildren={}"
						+ " | background={}"
						+ " | container={}"
						+ " | map={}"
						+ " | orbs={}"
						+ " | mapTabs={}"
						+ " | orbsTabs={}"
						+ " | desired={}"
						+ " | staticBounds={}"
						+ " | movableBounds={}"
						+ " | backgroundBounds={}"
						+ " | containerBounds={}"
						+ " | mapBounds={}"
						+ " | orbsBounds={}",
				scriptId,
				current.configuredWidth,
				current.buttonLayers,
				current.staticLayerHit,
				current.movableLayerHit,
				current.staticChildHits,
				current.movableChildHits,
				current.backgroundHit,
				current.containerHit,
				current.mapHit,
				current.orbsHit,
				current.mapTabsHit,
				current.orbsTabsHit,
				describeBounds(current.desired),
				describeBounds(current.staticBounds),
				describeBounds(current.movableBounds),
				describeBounds(current.backgroundBounds),
				describeBounds(current.containerBounds),
				describeBounds(current.mapBounds),
				describeBounds(current.orbsBounds));
	}

	/**
	 * ================================================================
	 * SCRIPT TRACE
	 * ================================================================
	 */
	private String traceScriptArguments(ScriptPreFired event) {
		if (event == null || event.getScriptEvent() == null || event.getScriptEvent().getArguments() == null) {
			return "[]";
		}

		final Object[] arguments = event.getScriptEvent().getArguments();
		final List<String> values = new ArrayList<>();

		for (Object argument : arguments) {
			if (argument instanceof String) {
				String value = (String) argument;
				if (value.length() > MAX_LOGGED_STRING_LENGTH) {
					value = value.substring(0, MAX_LOGGED_STRING_LENGTH) + "...";
				}

				values.add("'" + value + "'");
				continue;
			}

			if (argument instanceof Widget) {
				final Widget widget = (Widget) argument;
				values.add("Widget(id=" + widget.getId() + ", identity=" + System.identityHashCode(widget) + ")");
				continue;
			}

			values.add(String.valueOf(argument));
		}

		return values.toString();
	}

	private String traceIntTail() {
		final int[] stack = client.getIntStack();
		final int size = client.getIntStackSize();
		if (stack == null || size <= 0 || size > stack.length) {
			return "[]";
		}

		final int start = Math.max(0, size - INT_STACK_TAIL_SIZE);
		return Arrays.toString(Arrays.copyOfRange(stack, start, size));
	}

	private String traceStackStrings() {
		final Object[] stack = client.getObjectStack();
		final int size = client.getObjectStackSize();
		if (stack == null || size <= 0 || size > stack.length) {
			return "[]";
		}

		final List<String> strings = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			final Object value = stack[i];
			if (!(value instanceof String)) {
				continue;
			}

			String text = (String) value;

			text = text.replace("\r\n", "\\n").replace('\r', '\n').replace("\n", "\\n");

			if (text.length() > MAX_LOGGED_STRING_LENGTH) {
				text = text.substring(0, MAX_LOGGED_STRING_LENGTH) + "...";
			}

			strings.add("'" + text + "'");
		}

		return strings.toString();
	}

	private static String describeBounds(Rectangle bounds) {
		if (bounds == null) {
			return "-";
		}

		return "[x=" + bounds.x + ", y=" + bounds.y + ", width=" + bounds.width + ", height=" + bounds.height + "]";
	}

	/**
	 * ================================================================
	 * STATE TYPES
	 * ================================================================
	 */
	private static final class CollisionState {
		private final int configuredWidth;
		private final Rectangle desired;
		private final Rectangle backgroundBounds;
		private final Rectangle staticBounds;
		private final Rectangle movableBounds;
		private final Rectangle containerBounds;
		private final Rectangle mapBounds;
		private final Rectangle orbsBounds;
		private final boolean backgroundHit;
		private final boolean staticLayerHit;
		private final boolean movableLayerHit;
		private final boolean containerHit;
		private final boolean mapHit;
		private final boolean orbsHit;
		private final boolean mapTabsHit;
		private final boolean orbsTabsHit;
		private final int staticChildHits;
		private final int movableChildHits;
		private final int buttonLayers;

		private CollisionState(int configuredWidth, Rectangle desired, Rectangle backgroundBounds, Rectangle staticBounds, Rectangle movableBounds,
				Rectangle containerBounds, Rectangle mapBounds, Rectangle orbsBounds, int staticChildHits, int movableChildHits) {
			this.configuredWidth = configuredWidth;
			this.desired = desired;
			this.backgroundBounds = backgroundBounds;
			this.staticBounds = staticBounds;
			this.movableBounds = movableBounds;
			this.containerBounds = containerBounds;
			this.mapBounds = mapBounds;
			this.orbsBounds = orbsBounds;
			this.backgroundHit = intersects(desired, backgroundBounds);
			this.staticLayerHit = intersects(desired, staticBounds);
			this.movableLayerHit = intersects(desired, movableBounds);
			this.containerHit = intersects(desired, containerBounds);
			this.mapHit = intersects(desired, mapBounds);
			this.orbsHit = intersects(desired, orbsBounds);
			final Rectangle stripBounds = union(staticBounds, movableBounds);
			this.mapTabsHit = intersects(stripBounds, mapBounds);
			this.orbsTabsHit = intersects(stripBounds, orbsBounds);
			this.staticChildHits = staticChildHits;
			this.movableChildHits = movableChildHits;
			this.buttonLayers = (staticChildHits > 0 ? 1 : 0) + (movableChildHits > 0 ? 1 : 0);
		}

		private static CollisionState capture(Client client, Configurations config) {
			if (client == null || config == null || client.getTopLevelInterfaceId() != InterfaceID.TOPLEVEL_PRE_EOC) {
				return null;
			}

			final Widget slot = client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);
			final Rectangle slotBounds = visibleBounds(slot);
			if (slotBounds == null) {
				return null;
			}

			final int configuredWidth = config.chatboxWidth();
			final Rectangle desired = new Rectangle(slotBounds.x, slotBounds.y, Math.max(0, configuredWidth), slotBounds.height);

			final Widget background = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_BACKGROUND);
			final Widget staticLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER);
			final Widget movableLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER);
			final Widget container = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);
			final Widget map = client.getWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER);
			final Widget orbs = client.getWidget(InterfaceID.ToplevelPreEoc.ORBS);

			return new CollisionState(
					configuredWidth,
					desired,
					visibleBounds(background),
					visibleBounds(staticLayer),
					visibleBounds(movableLayer),
					visibleBounds(container),
					visibleBounds(map),
					visibleBounds(orbs),
					countChildHits(desired, staticLayer),
					countChildHits(desired, movableLayer));
		}

		private boolean sameAs(CollisionState other) {
			return other != null && configuredWidth == other.configuredWidth && backgroundHit == other.backgroundHit
					&& staticLayerHit == other.staticLayerHit && movableLayerHit == other.movableLayerHit && containerHit == other.containerHit
					&& mapHit == other.mapHit && orbsHit == other.orbsHit && mapTabsHit == other.mapTabsHit && orbsTabsHit == other.orbsTabsHit
					&& staticChildHits == other.staticChildHits && movableChildHits == other.movableChildHits;
		}

		private static Rectangle visibleBounds(Widget widget) {
			if (widget == null || widget.isHidden()) {
				return null;
			}

			final Rectangle bounds = widget.getBounds();
			return bounds != null && bounds.width > 0 && bounds.height > 0 ? new Rectangle(bounds) : null;
		}

		private static int countChildHits(Rectangle desired, Widget parent) {
			if (desired == null || parent == null || parent.isHidden()) {
				return 0;
			}

			return countChildHits(desired, parent.getStaticChildren()) + countChildHits(desired, parent.getDynamicChildren())
					+ countChildHits(desired, parent.getNestedChildren());
		}

		private static int countChildHits(Rectangle desired, Widget[] children) {
			if (children == null) {
				return 0;
			}

			int hits = 0;
			for (Widget child : children) {
				final Rectangle bounds = visibleBounds(child);
				if (intersects(desired, bounds)) {
					hits++;
				}
			}

			return hits;
		}
	}

	private static final class TraceFrame {
		private int scriptId;
		private int depth;
		private Snapshot checkpoint;
		private String intTailBefore;
		private String stringsBefore;
		private String arguments;
	}

	private static final class Snapshot {
		private final int topLevel;
		private final Map<String, WidgetState> widgets;
		private final Map<String, OverlayState> overlays;

		private Snapshot(int topLevel, Map<String, WidgetState> widgets, Map<String, OverlayState> overlays) {
			this.topLevel = topLevel;
			this.widgets = widgets;
			this.overlays = overlays;
		}
	}

	private static final class WidgetState {
		private final boolean present;
		private final int identity;
		private final int originalX;
		private final int originalY;
		private final int originalWidth;
		private final int originalHeight;
		private final int relativeX;
		private final int relativeY;
		private final int width;
		private final int height;
		private final int boundsX;
		private final int boundsY;
		private final boolean hidden;
		private final int staticChildren;
		private final int dynamicChildren;
		private final int nestedChildren;

		private WidgetState(Widget widget) {
			if (widget == null) {
				present = false;
				identity = 0;
				originalX = 0;
				originalY = 0;
				originalWidth = 0;
				originalHeight = 0;
				relativeX = 0;
				relativeY = 0;
				width = 0;
				height = 0;
				boundsX = 0;
				boundsY = 0;
				hidden = true;
				staticChildren = 0;
				dynamicChildren = 0;
				nestedChildren = 0;
				return;
			}

			present = true;
			identity = System.identityHashCode(widget);
			originalX = widget.getOriginalX();
			originalY = widget.getOriginalY();
			originalWidth = widget.getOriginalWidth();
			originalHeight = widget.getOriginalHeight();
			relativeX = widget.getRelativeX();
			relativeY = widget.getRelativeY();
			width = widget.getWidth();
			height = widget.getHeight();
			final Rectangle bounds = widget.getBounds();
			boundsX = bounds != null ? bounds.x : 0;
			boundsY = bounds != null ? bounds.y : 0;
			hidden = widget.isHidden();
			staticChildren = childCount(widget.getStaticChildren());
			dynamicChildren = childCount(widget.getDynamicChildren());
			nestedChildren = childCount(widget.getNestedChildren());
		}

		private static String describeDifference(String label, WidgetState before, WidgetState after) {
			if (before == null && after == null) {
				return null;
			}

			if (before == null) {
				return "component=" + label + " | state=MISSING->" + after.describe();
			}

			if (after == null) {
				return "component=" + label + " | state=" + before.describe() + "->MISSING";
			}

			final StringBuilder difference = new StringBuilder();

			appendDifference(difference, "present", before.present, after.present);
			appendDifference(difference, "identity", before.identity, after.identity);
			appendDifference(difference, "originalX", before.originalX, after.originalX);
			appendDifference(difference, "originalY", before.originalY, after.originalY);
			appendDifference(difference, "originalWidth", before.originalWidth, after.originalWidth);
			appendDifference(difference, "originalHeight", before.originalHeight, after.originalHeight);
			appendDifference(difference, "relativeX", before.relativeX, after.relativeX);
			appendDifference(difference, "relativeY", before.relativeY, after.relativeY);
			appendDifference(difference, "width", before.width, after.width);
			appendDifference(difference, "height", before.height, after.height);
			appendDifference(difference, "boundsX", before.boundsX, after.boundsX);
			appendDifference(difference, "boundsY", before.boundsY, after.boundsY);
			appendDifference(difference, "hidden", before.hidden, after.hidden);
			appendDifference(difference, "staticChildren", before.staticChildren, after.staticChildren);
			appendDifference(difference, "dynamicChildren", before.dynamicChildren, after.dynamicChildren);
			appendDifference(difference, "nestedChildren", before.nestedChildren, after.nestedChildren);

			return difference.length() == 0 ? null : "component=" + label + " | " + difference;
		}

		private String describe() {
			if (!present) {
				return "MISSING";
			}

			return "identity=" + identity + ", original=[" + originalX + "," + originalY + "," + originalWidth + "x" + originalHeight + "]"
					+ ", relative=[" + relativeX + "," + relativeY + "], calculated=[" + width + "x" + height + "]";
		}

		private static int childCount(Widget[] children) {
			return children != null ? children.length : 0;
		}

		private static void appendDifference(StringBuilder builder, String name, int before, int after) {
			if (before == after) {
				return;
			}

			appendSeparator(builder);
			builder.append(name).append('=').append(before).append("->").append(after);
		}

		private static void appendDifference(StringBuilder builder, String name, boolean before, boolean after) {
			if (before == after) {
				return;
			}

			appendSeparator(builder);
			builder.append(name).append('=').append(before).append("->").append(after);
		}

		private static void appendSeparator(StringBuilder builder) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
		}
	}

	private static final class OverlayState {
		private final boolean present;
		private final Integer preferredX;
		private final Integer preferredY;
		private final OverlayPosition preferredPosition;
		private final int boundsX;
		private final int boundsY;
		private final int width;
		private final int height;

		private OverlayState(Overlay overlay) {
			if (overlay == null) {
				present = false;
				preferredX = null;
				preferredY = null;
				preferredPosition = null;
				boundsX = 0;
				boundsY = 0;
				width = 0;
				height = 0;
				return;
			}

			present = true;
			final Point preferredLocation = overlay.getPreferredLocation();
			preferredX = preferredLocation != null ? preferredLocation.x : null;
			preferredY = preferredLocation != null ? preferredLocation.y : null;
			preferredPosition = overlay.getPreferredPosition();
			final Rectangle bounds = overlay.getBounds();
			boundsX = bounds != null ? bounds.x : 0;
			boundsY = bounds != null ? bounds.y : 0;
			width = bounds != null ? bounds.width : 0;
			height = bounds != null ? bounds.height : 0;
		}

		private static String describeDifference(String label, OverlayState before, OverlayState after) {
			if (before == null && after == null) {
				return null;
			}

			if (before == null || after == null) {
				return "overlay=" + label + " | state=" + (before == null ? "MISSING" : before.describe()) + "->"
						+ (after == null ? "MISSING" : after.describe());
			}

			final StringBuilder difference = new StringBuilder();
			appendDifference(difference, "present", before.present, after.present);
			appendDifference(difference, "preferredX", before.preferredX, after.preferredX);
			appendDifference(difference, "preferredY", before.preferredY, after.preferredY);
			appendDifference(difference, "preferredPosition", String.valueOf(before.preferredPosition), String.valueOf(after.preferredPosition));
			appendDifference(difference, "boundsX", before.boundsX, after.boundsX);
			appendDifference(difference, "boundsY", before.boundsY, after.boundsY);
			appendDifference(difference, "width", before.width, after.width);
			appendDifference(difference, "height", before.height, after.height);

			return difference.length() == 0 ? null : "overlay=" + label + " | " + difference;
		}

		private String describe() {
			return "present=" + present + ", preferred=[" + preferredX + "," + preferredY + "], position=" + preferredPosition
					+ ", bounds=[" + boundsX + "," + boundsY + "," + width + "x" + height + "]";
		}

		private static void appendDifference(StringBuilder builder, String name, Object before, Object after) {
			if (before == after || before != null && before.equals(after)) {
				return;
			}

			appendSeparator(builder);
			builder.append(name).append('=').append(before).append("->").append(after);
		}

		private static void appendSeparator(StringBuilder builder) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
		}
	}

	private static Rectangle union(Rectangle first, Rectangle second) {
		if (first == null) {
			return second != null ? new Rectangle(second) : null;
		}

		final Rectangle union = new Rectangle(first);
		if (second != null) {
			union.add(second);
		}

		return union;
	}

	private static boolean intersects(Rectangle first, Rectangle second) {
		return first != null && second != null && first.intersects(second);
	}
}

