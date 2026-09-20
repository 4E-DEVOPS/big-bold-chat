package com.bigboldchat.debug;

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

/**
 * Observer-only diagnostics for Modern side-container layout and collisions.
 */
@Slf4j
public final class SideContainerDiagnostics {
	private static final String COMMAND = "debug-inventory";
	private static final int INT_STACK_TAIL_SIZE = 16;
	private static final int MAX_LOGGED_STRING_LENGTH = 200;

	private final Client client;
	private final Configurations config;
	private final Deque<TraceFrame> traceFrames = new ArrayDeque<>();

	private boolean armed;
	private CollisionState collisionState;

	public SideContainerDiagnostics(Client client, Configurations config) {
		this.client = client;
		this.config = config;
	}

	public synchronized boolean onCommandExecuted(CommandExecuted event) {
		if (event == null || !COMMAND.equalsIgnoreCase(event.getCommand())) {
			return false;
		}

		armed = !armed;
		traceFrames.clear();
		collisionState = null;

		log.debug(
				"[Chat XL][Side Container Diagnostic] {}", armed
						? "ARMED"
						: "DISARMED");

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
		frame.intTailBefore = traceIntStackTail();
		frame.stringsBefore = traceObjectStackStrings();
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
				nextScriptId >= 0
						? Integer.toString(nextScriptId)
						: "-",
				frame.checkpoint.topLevel,
				current.topLevel,
				frame.arguments,
				frame.intTailBefore,
				frame.stringsBefore);

		for (String change : changes) {
			log.debug(
					"[Chat XL][Side Container Diagnostic]"
							+ " CHANGE"
							+ " | scriptId={}"
							+ " | {}",
					frame.scriptId,
					change);
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

		return changes;
	}

	private Snapshot snapshot() {
		final Map<String, WidgetState> widgets = new LinkedHashMap<>();

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

		return new Snapshot(client.getTopLevelInterfaceId(), widgets);
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
						+ " | desired={}"
						+ " | staticBounds={}"
						+ " | movableBounds={}"
						+ " | backgroundBounds={}"
						+ " | containerBounds={}",
				scriptId,
				current.configuredWidth,
				current.buttonLayers,
				current.staticLayerHit,
				current.movableLayerHit,
				current.staticChildHits,
				current.movableChildHits,
				current.backgroundHit,
				current.containerHit,
				describeBounds(current.desired),
				describeBounds(current.staticBounds),
				describeBounds(current.movableBounds),
				describeBounds(current.backgroundBounds),
				describeBounds(current.containerBounds));
	}

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

	private String traceIntStackTail() {
		final int[] stack = client.getIntStack();
		final int size = client.getIntStackSize();
		if (stack == null || size <= 0 || size > stack.length) {
			return "[]";
		}

		final int start = Math.max(0, size - INT_STACK_TAIL_SIZE);

		return Arrays.toString(Arrays.copyOfRange(stack, start, size));
	}

	private String traceObjectStackStrings() {
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

			text = text
					.replace("\r\n", "\\n")
					.replace('\r', '\n')
					.replace("\n", "\\n");

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

		return "[x=" + bounds.x
				+ ", y=" + bounds.y
				+ ", width=" + bounds.width
				+ ", height=" + bounds.height
				+ "]";
	}

	private static final class CollisionState {
		private final int configuredWidth;
		private final Rectangle desired;
		private final Rectangle backgroundBounds;
		private final Rectangle staticBounds;
		private final Rectangle movableBounds;
		private final Rectangle containerBounds;
		private final boolean backgroundHit;
		private final boolean staticLayerHit;
		private final boolean movableLayerHit;
		private final boolean containerHit;
		private final int staticChildHits;
		private final int movableChildHits;
		private final int buttonLayers;

		private CollisionState(
				int configuredWidth,
				Rectangle desired,
				Rectangle backgroundBounds,
				Rectangle staticBounds,
				Rectangle movableBounds,
				Rectangle containerBounds,
				int staticChildHits,
				int movableChildHits) {
			this.configuredWidth = configuredWidth;
			this.desired = desired;
			this.backgroundBounds = backgroundBounds;
			this.staticBounds = staticBounds;
			this.movableBounds = movableBounds;
			this.containerBounds = containerBounds;
			this.backgroundHit = intersects(desired, backgroundBounds);
			this.staticLayerHit = intersects(desired, staticBounds);
			this.movableLayerHit = intersects(desired, movableBounds);
			this.containerHit = intersects(desired, containerBounds);
			this.staticChildHits = staticChildHits;
			this.movableChildHits = movableChildHits;
			this.buttonLayers = (staticChildHits > 0
					? 1
					: 0) + (movableChildHits > 0
					? 1
					: 0);
		}

		private static CollisionState capture(Client client, Configurations config) {
			if (client == null
					|| config == null
					|| client.getTopLevelInterfaceId() != InterfaceID.TOPLEVEL_PRE_EOC) {
				return null;
			}

			final Widget slot = client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);
			final Rectangle slotBounds = visibleBounds(slot);
			if (slotBounds == null) {
				return null;
			}

			final int configuredWidth = config.chatboxWidth();
			final Rectangle desired = new Rectangle(
					slotBounds.x, slotBounds.y, Math.max(0, configuredWidth), slotBounds.height);

			final Widget background = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_BACKGROUND);
			final Widget staticLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER);
			final Widget movableLayer = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER);
			final Widget container = client.getWidget(InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);

			return new CollisionState(
					configuredWidth,
					desired,
					visibleBounds(background),
					visibleBounds(staticLayer),
					visibleBounds(movableLayer),
					visibleBounds(container),
					countChildHits(desired, staticLayer),
					countChildHits(desired, movableLayer));
		}

		private boolean sameAs(CollisionState other) {
			return other != null
					&& configuredWidth == other.configuredWidth
					&& backgroundHit == other.backgroundHit
					&& staticLayerHit == other.staticLayerHit
					&& movableLayerHit == other.movableLayerHit
					&& containerHit == other.containerHit
					&& staticChildHits == other.staticChildHits
					&& movableChildHits == other.movableChildHits;
		}

		private static Rectangle visibleBounds(Widget widget) {
			if (widget == null || widget.isHidden()) {
				return null;
			}

			final Rectangle bounds = widget.getBounds();
			return bounds != null && bounds.width > 0 && bounds.height > 0
					? new Rectangle(bounds)
					: null;
		}

		private static int countChildHits(Rectangle desired, Widget parent) {
			if (desired == null || parent == null || parent.isHidden()) {
				return 0;
			}

			return countChildHits(desired, parent.getStaticChildren())
					+ countChildHits(desired, parent.getDynamicChildren())
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

		private Snapshot(int topLevel, Map<String, WidgetState> widgets) {
			this.topLevel = topLevel;
			this.widgets = widgets;
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
			appendDifference(difference, "hidden", before.hidden, after.hidden);
			appendDifference(difference, "staticChildren", before.staticChildren, after.staticChildren);
			appendDifference(difference, "dynamicChildren", before.dynamicChildren, after.dynamicChildren);
			appendDifference(difference, "nestedChildren", before.nestedChildren, after.nestedChildren);

			return difference.length() == 0
					? null
					: "component=" + label + " | " + difference;
		}

		private String describe() {
			if (!present) {
				return "MISSING";
			}

			return "identity="
					+ identity
					+ ", original=["
					+ originalX
					+ ","
					+ originalY
					+ ","
					+ originalWidth
					+ "x"
					+ originalHeight
					+ "]"
					+ ", relative=["
					+ relativeX
					+ ","
					+ relativeY
					+ "]"
					+ ", calculated=["
					+ width
					+ "x"
					+ height
					+ "]";
		}

		private static int childCount(Widget[] children) {
			return children != null
					? children.length
					: 0;
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

	private static boolean intersects(Rectangle first, Rectangle second) {
		return first != null && second != null && first.intersects(second);
	}
}
