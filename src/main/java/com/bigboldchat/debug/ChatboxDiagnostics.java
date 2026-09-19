package com.bigboldchat.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Observer-only diagnostics for chatbox geometry and reconstruction.
 */
@Slf4j
public final class ChatboxDiagnostics {
	private static final String COMMAND = "debug-chatbox";

	/*
	 * Reference plugins use these native rebuild paths for resizable chat.
	 * Diagnostics observe them only; ChatXL never invokes them.
	 */
	private static final int RESIZES_CHAT = 924;
	private static final int REWRAPS_CHAT = 663;

	private static final int INT_STACK_TAIL_SIZE = 16;
	private static final int MAX_LOGGED_STRING_LENGTH = 200;

	private final Client client;
	private final Deque<ResizeTraceFrame> resizeTraceFrames = new ArrayDeque<>();

	private boolean armed;

	public ChatboxDiagnostics(Client client) {
		this.client = client;
	}

	public synchronized boolean onCommandExecuted(CommandExecuted event) {
		if (event == null || !COMMAND.equalsIgnoreCase(event.getCommand())) {
			return false;
		}

		armed = !armed;
		resizeTraceFrames.clear();

		log.debug(
				"[Chat XL][Chatbox Diagnostic] {}", armed
						? "ARMED"
						: "DISARMED");

		return true;
	}

	public synchronized void onScriptPreFired(ScriptPreFired event) {
		if (!armed || event == null) {
			return;
		}

		final ResizeSnapshot current = snapshotResizeWidgets();
		final ResizeTraceFrame parent = resizeTraceFrames.peek();
		if (parent != null) {
			logResizeChanges(parent, current, "BEFORE_CHILD", event.getScriptId());
			parent.checkpoint = current;
		}

		final ResizeTraceFrame frame = new ResizeTraceFrame();

		frame.scriptId = event.getScriptId();
		frame.depth = resizeTraceFrames.size() + 1;
		frame.checkpoint = current;
		frame.intTailBefore = traceIntStackTail();
		frame.stringsBefore = traceObjectStackStrings();
		frame.arguments = traceScriptArguments(event);

		if (isRebuildCandidate(frame.scriptId)) {
			frame.candidateEntry = current;
			logRebuildState(frame, "PRE", snapshotChatState());
		}

		resizeTraceFrames.push(frame);
	}

	public synchronized void onScriptPostFired(ScriptPostFired event) {
		if (!armed || event == null || resizeTraceFrames.isEmpty()) {
			return;
		}

		final ResizeTraceFrame frame = resizeTraceFrames.peek();
		if (frame == null) {
			resizeTraceFrames.clear();
			return;
		}

		if (frame.scriptId != event.getScriptId()) {
			log.debug(
					"[Chat XL][Chatbox Diagnostic]"
							+ " STACK MISMATCH"
							+ " | expectedScriptId={}"
							+ " | actualScriptId={}"
							+ " | depth={}",
					frame.scriptId,
					event.getScriptId(),
					resizeTraceFrames.size());
			resizeTraceFrames.clear();
			return;
		}

		final ResizeSnapshot current = snapshotResizeWidgets();

		if (isRebuildCandidate(frame.scriptId)) {
			logRebuildResult(frame, current);
		}

		logResizeChanges(frame, current, "POST", -1);

		resizeTraceFrames.pop();

		final ResizeTraceFrame parent = resizeTraceFrames.peek();
		if (parent != null) {
			parent.checkpoint = current;
		}
	}

	private static boolean isRebuildCandidate(int scriptId) {
		return scriptId == RESIZES_CHAT || scriptId == REWRAPS_CHAT;
	}

	private void logRebuildResult(ResizeTraceFrame frame, ResizeSnapshot current) {
		final ChatStateSnapshot after = snapshotChatState();

		logRebuildState(frame, "POST", after);

		final List<String> changes = compareResizeSnapshots(frame.candidateEntry, current);
		if (changes.isEmpty()) {
			log.debug(
					"[Chat XL][Chatbox Diagnostic]"
							+ " REBUILD CHANGE"
							+ " | scriptId={}"
							+ " | none",
					frame.scriptId);
			return;
		}

		for (String change : changes) {
			log.debug(
					"[Chat XL][Chatbox Diagnostic]"
							+ " REBUILD CHANGE"
							+ " | scriptId={}"
							+ " | {}",
					frame.scriptId,
					change);
		}
	}

	private void logRebuildState(ResizeTraceFrame frame, String phase, ChatStateSnapshot state) {
		log.debug(
				"[Chat XL][Chatbox Diagnostic]"
						+ " REBUILD"
						+ " | phase={}"
						+ " | depth={}"
						+ " | scriptId={}"
						+ " | args={}"
						+ " | intTail={}"
						+ " | {}",
				phase,
				frame.depth,
				frame.scriptId,
				frame.arguments,
				frame.intTailBefore,
				state.describe());
	}

	private void logResizeChanges(ResizeTraceFrame frame, ResizeSnapshot current, String phase, int nextScriptId) {
		if (frame == null || frame.checkpoint == null || current == null) {
			return;
		}

		final List<String> changes = compareResizeSnapshots(frame.checkpoint, current);
		if (changes.isEmpty()) {
			return;
		}

		log.debug(
				"[Chat XL][Chatbox Diagnostic]"
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
					"[Chat XL][Chatbox Diagnostic]"
							+ " CHANGE"
							+ " | scriptId={}"
							+ " | {}",
					frame.scriptId,
					change);
		}
	}

	private List<String> compareResizeSnapshots(ResizeSnapshot before, ResizeSnapshot after) {
		final List<String> changes = new ArrayList<>();

		if (before == null || after == null) {
			return changes;
		}

		if (before.topLevel != after.topLevel) {
			changes.add("component=TOPLEVEL"
					+ " | id="
					+ before.topLevel
					+ "->"
					+ after.topLevel);
		}

		final Set<String> labels = new LinkedHashSet<>();

		labels.addAll(before.widgets.keySet());
		labels.addAll(after.widgets.keySet());

		for (String label : labels) {
			final ResizeWidgetState beforeState = before.widgets.get(label);
			final ResizeWidgetState afterState = after.widgets.get(label);
			final String difference = ResizeWidgetState.describeDifference(label, beforeState, afterState);
			if (difference != null) {
				changes.add(difference);
			}
		}

		return changes;
	}

	private ResizeSnapshot snapshotResizeWidgets() {
		final Map<String, ResizeWidgetState> widgets = new LinkedHashMap<>();

		putResizeWidget(widgets, "SLOT_FIXED", client.getWidget(InterfaceID.Toplevel.CHAT_CONTAINER));
		putResizeWidget(widgets, "SLOT_CLASSIC", client.getWidget(InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER));
		putResizeWidget(widgets, "SLOT_MODERN", client.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER));

		final Widget universe = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
		final Widget chatArea = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		final Widget background = client.getWidget(InterfaceID.Chatbox.CHAT_BACKGROUND);

		putResizeWidget(widgets, "UNIVERSE", universe);
		putResizeWidget(widgets, "CHATAREA", chatArea);
		putResizeWidget(widgets, "CHAT_BACKGROUND", background);
		putResizeWidget(widgets, "CHAT_BACKGROUND_BODY", firstDynamicChild(background));
		putResizeWidget(widgets, "CONTROLS", client.getWidget(InterfaceID.Chatbox.CONTROLS));
		putResizeWidget(widgets, "CONTROLS_BACKGROUND", client.getWidget(InterfaceID.Chatbox.CONTROLS_BACKGROUND_GRAPHIC));
		putResizeWidget(widgets, "SCROLLAREA", client.getWidget(InterfaceID.Chatbox.SCROLLAREA));
		putResizeWidget(widgets, "CHATSCROLLBAR", client.getWidget(InterfaceID.Chatbox.CHATSCROLLBAR));

		putResizeWidget(widgets, "TAB_ALL", client.getWidget(InterfaceID.Chatbox.CHAT_ALL));
		putResizeWidget(widgets, "TAB_GAME", client.getWidget(InterfaceID.Chatbox.CHAT_GAME));
		putResizeWidget(widgets, "TAB_PUBLIC", client.getWidget(InterfaceID.Chatbox.CHAT_PUBLIC));
		putResizeWidget(widgets, "TAB_PRIVATE", client.getWidget(InterfaceID.Chatbox.CHAT_PRIVATE));
		putResizeWidget(widgets, "TAB_FRIENDSCHAT", client.getWidget(InterfaceID.Chatbox.CHAT_FRIENDSCHAT));
		putResizeWidget(widgets, "TAB_CLAN", client.getWidget(InterfaceID.Chatbox.CHAT_CLAN));
		putResizeWidget(widgets, "TAB_TRADE", client.getWidget(InterfaceID.Chatbox.CHAT_TRADE));

		return new ResizeSnapshot(client.getTopLevelInterfaceId(), widgets);
	}

	private static void putResizeWidget(Map<String, ResizeWidgetState> widgets, String label, Widget widget) {
		widgets.put(label, new ResizeWidgetState(widget));
	}

	private static Widget firstDynamicChild(Widget widget) {
		if (widget == null) {
			return null;
		}

		final Widget[] children = widget.getDynamicChildren();
		return children != null && children.length > 0
				? children[0]
				: null;
	}

	private ChatStateSnapshot snapshotChatState() {
		return new ChatStateSnapshot(
				client.getWidget(InterfaceID.Chatbox.SCROLLAREA),
				client.getWidget(InterfaceID.Chatbox.CHATSCROLLBAR),
				client.getWidget(InterfaceID.Chatbox.CHATAREA));
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

	public synchronized void reset() {
		resizeTraceFrames.clear();
		armed = false;
	}

	private static final class ResizeTraceFrame {
		private int scriptId;
		private int depth;

		private ResizeSnapshot checkpoint;
		private ResizeSnapshot candidateEntry;

		private String intTailBefore;
		private String stringsBefore;
		private String arguments;
	}

	private static final class ChatStateSnapshot {
		private final boolean scrollAreaPresent;
		private final int viewportHeight;
		private final int scrollHeight;
		private final int scrollY;
		private final int staticChildren;
		private final int dynamicChildren;
		private final int nestedChildren;

		private final boolean scrollbarPresent;
		private final int scrollbarX;
		private final int scrollbarY;
		private final int scrollbarWidth;
		private final int scrollbarHeight;

		private final int chatAreaDynamicChildren;
		private final ChildRange childRange;

		private ChatStateSnapshot(Widget scrollArea, Widget scrollbar, Widget chatArea) {
			scrollAreaPresent = scrollArea != null;
			viewportHeight = scrollArea != null
					? scrollArea.getHeight()
					: 0;
			scrollHeight = scrollArea != null
					? scrollArea.getScrollHeight()
					: 0;
			scrollY = scrollArea != null
					? scrollArea.getScrollY()
					: 0;
			staticChildren = scrollArea != null
					? childCount(scrollArea.getStaticChildren())
					: 0;
			dynamicChildren = scrollArea != null
					? childCount(scrollArea.getDynamicChildren())
					: 0;
			nestedChildren = scrollArea != null
					? childCount(scrollArea.getNestedChildren())
					: 0;

			scrollbarPresent = scrollbar != null;
			scrollbarX = scrollbar != null
					? scrollbar.getRelativeX()
					: 0;
			scrollbarY = scrollbar != null
					? scrollbar.getRelativeY()
					: 0;
			scrollbarWidth = scrollbar != null
					? scrollbar.getWidth()
					: 0;
			scrollbarHeight = scrollbar != null
					? scrollbar.getHeight()
					: 0;

			chatAreaDynamicChildren = chatArea != null
					? childCount(chatArea.getDynamicChildren())
					: 0;
			childRange = ChildRange.of(scrollArea);
		}

		private String describe() {
			return "scrollArea=[present="
					+ scrollAreaPresent
					+ ", height="
					+ viewportHeight
					+ ", scrollHeight="
					+ scrollHeight
					+ ", scrollY="
					+ scrollY
					+ ", static="
					+ staticChildren
					+ ", dynamic="
					+ dynamicChildren
					+ ", nested="
					+ nestedChildren
					+ "]"
					+ " | directChildY="
					+ childRange.describe()
					+ " | scrollbar=[present="
					+ scrollbarPresent
					+ ", x="
					+ scrollbarX
					+ ", y="
					+ scrollbarY
					+ ", width="
					+ scrollbarWidth
					+ ", height="
					+ scrollbarHeight
					+ "]"
					+ " | chatAreaDynamic="
					+ chatAreaDynamicChildren;
		}

		private static int childCount(Widget[] children) {
			return children != null
					? children.length
					: 0;
		}
	}

	private static final class ChildRange {
		private int count;
		private int minY = Integer.MAX_VALUE;
		private int maxY = Integer.MIN_VALUE;

		private static ChildRange of(Widget widget) {
			final ChildRange range = new ChildRange();
			if (widget == null) {
				return range;
			}

			range.add(widget.getStaticChildren());
			range.add(widget.getDynamicChildren());
			return range;
		}

		private void add(Widget[] children) {
			if (children == null) {
				return;
			}

			for (Widget child : children) {
				if (child == null) {
					continue;
				}

				count++;
				minY = Math.min(minY, child.getRelativeY());
				maxY = Math.max(maxY, child.getRelativeY());
			}
		}

		private String describe() {
			if (count == 0) {
				return "[count=0]";
			}

			return "[count=" + count + ", min=" + minY + ", max=" + maxY + "]";
		}
	}

	private static final class ResizeSnapshot {
		private final int topLevel;
		private final Map<String, ResizeWidgetState> widgets;

		private ResizeSnapshot(int topLevel, Map<String, ResizeWidgetState> widgets) {
			this.topLevel = topLevel;
			this.widgets = widgets;
		}
	}

	private static final class ResizeWidgetState {
		private final boolean present;

		private final int identity;
		private final int id;
		private final int parentId;
		private final int parentIdentity;
		private final int index;
		private final int type;

		private final int originalX;
		private final int originalY;
		private final int originalWidth;
		private final int originalHeight;

		private final int relativeX;
		private final int relativeY;

		private final int width;
		private final int height;

		private final int widthMode;
		private final int heightMode;
		private final int xPositionMode;
		private final int yPositionMode;

		private final int spriteId;
		private final boolean spriteTiling;
		private final boolean hidden;

		private final int scrollY;
		private final int scrollHeight;

		private final int staticChildren;
		private final int dynamicChildren;
		private final int nestedChildren;

		private ResizeWidgetState(Widget widget) {
			if (widget == null) {
				present = false;

				identity = 0;
				id = -1;
				parentId = -1;
				parentIdentity = 0;
				index = -1;
				type = -1;

				originalX = 0;
				originalY = 0;
				originalWidth = 0;
				originalHeight = 0;

				relativeX = 0;
				relativeY = 0;

				width = 0;
				height = 0;

				widthMode = 0;
				heightMode = 0;
				xPositionMode = 0;
				yPositionMode = 0;

				spriteId = -1;
				spriteTiling = false;
				hidden = true;

				scrollY = 0;
				scrollHeight = 0;

				staticChildren = 0;
				dynamicChildren = 0;
				nestedChildren = 0;

				return;
			}

			present = true;

			identity = System.identityHashCode(widget);
			id = widget.getId();
			parentId = widget.getParentId();

			final Widget parent = widget.getParent();

			parentIdentity = parent != null
					? System.identityHashCode(parent)
					: 0;

			index = widget.getIndex();
			type = widget.getType();

			originalX = widget.getOriginalX();
			originalY = widget.getOriginalY();
			originalWidth = widget.getOriginalWidth();
			originalHeight = widget.getOriginalHeight();

			relativeX = widget.getRelativeX();
			relativeY = widget.getRelativeY();

			width = widget.getWidth();
			height = widget.getHeight();

			widthMode = widget.getWidthMode();
			heightMode = widget.getHeightMode();
			xPositionMode = widget.getXPositionMode();
			yPositionMode = widget.getYPositionMode();

			spriteId = widget.getSpriteId();
			spriteTiling = widget.getSpriteTiling();
			hidden = widget.isHidden();

			scrollY = widget.getScrollY();
			scrollHeight = widget.getScrollHeight();

			staticChildren = childCount(widget.getStaticChildren());
			dynamicChildren = childCount(widget.getDynamicChildren());
			nestedChildren = childCount(widget.getNestedChildren());
		}

		private static String describeDifference(String label, ResizeWidgetState before, ResizeWidgetState after) {
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

			if (!before.present && !after.present) {
				return difference.length() == 0
						? null
						: "component=" + label + " | " + difference;
			}

			appendDifference(difference, "identity", before.identity, after.identity);
			appendDifference(difference, "id", before.id, after.id);
			appendDifference(difference, "parentId", before.parentId, after.parentId);
			appendDifference(difference, "parentIdentity", before.parentIdentity, after.parentIdentity);
			appendDifference(difference, "index", before.index, after.index);
			appendDifference(difference, "type", before.type, after.type);

			appendDifference(difference, "originalX", before.originalX, after.originalX);
			appendDifference(difference, "originalY", before.originalY, after.originalY);
			appendDifference(difference, "originalWidth", before.originalWidth, after.originalWidth);
			appendDifference(difference, "originalHeight", before.originalHeight, after.originalHeight);

			appendDifference(difference, "relativeX", before.relativeX, after.relativeX);
			appendDifference(difference, "relativeY", before.relativeY, after.relativeY);
			appendDifference(difference, "width", before.width, after.width);
			appendDifference(difference, "height", before.height, after.height);

			appendDifference(difference, "widthMode", before.widthMode, after.widthMode);
			appendDifference(difference, "heightMode", before.heightMode, after.heightMode);
			appendDifference(difference, "xMode", before.xPositionMode, after.xPositionMode);
			appendDifference(difference, "yMode", before.yPositionMode, after.yPositionMode);

			appendDifference(difference, "spriteId", before.spriteId, after.spriteId);
			appendDifference(difference, "spriteTiling", before.spriteTiling, after.spriteTiling);
			appendDifference(difference, "hidden", before.hidden, after.hidden);

			appendDifference(difference, "scrollY", before.scrollY, after.scrollY);
			appendDifference(difference, "scrollHeight", before.scrollHeight, after.scrollHeight);

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

			return "id="
					+ id
					+ ", identity="
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

			builder.append(name)
					.append('=')
					.append(before)
					.append("->")
					.append(after);
		}

		private static void appendDifference(StringBuilder builder, String name, boolean before, boolean after) {
			if (before == after) {
				return;
			}

			appendSeparator(builder);

			builder.append(name)
					.append('=')
					.append(before)
					.append("->")
					.append(after);
		}

		private static void appendSeparator(StringBuilder builder) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
		}
	}
}
