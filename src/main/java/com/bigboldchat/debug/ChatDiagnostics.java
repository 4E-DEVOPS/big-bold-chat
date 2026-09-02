package com.bigboldchat.debug;

import com.bigboldchat.Configurations;
import com.bigboldchat.config.ChatFont;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Client;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Observer-only diagnostics for Chat XL.
 *
 * This class never mutates script stacks, fonts, widgets, or layout.
 * It provides compact supported-script tracing and reusable targeted
 * discovery for future investigations.
 */
@Slf4j
public final class ChatDiagnostics
{
    /*
     * Known chat-row constructors.
     */
    private static final int GAME_BODY_SCRIPT = 199;
    private static final int CHAT_BODY_SCRIPT = 203;
    private static final int CHANNEL_BODY_SCRIPT = 4483;

    /*
     * Normal diagnostic behavior.
     *
     * TRACE_SUPPORTED_DETAILS=false produces one compact summary per
     * supported constructor. Set it true when detailed PRE/POST mutation
     * tracing is needed.
     */
    private static final boolean TRACE_SUPPORTED_LIFECYCLES = true;
    private static final boolean TRACE_SUPPORTED_DETAILS = false;

    /*
     * Future discovery roots.
     *
     * Add a script ID here to trace that root and every nested script
     * until its matching POST completes.
     */
    private static final int[] TARGET_ROOT_SCRIPTS =
            {
                    // 72,      // Chat reconstruction, relative to Y-placement.
                    // 90,      // Carries actual message text (nested under 192).
                    // 192,     // Message-processing branch (nested under 663).
                    // 193,     // Carries game, system, and chat-notification text.
                    // 199,     // Welcome, Did You Know, Channel/Chat join notices, et cetera.
                    // 203,     // General chatbox body messages.
                    // 663,     // Broad chat and message refresh-dispatch lifecycle.
                    // 4742,    // Carries actual message text (nested under 192).
                    // 4483,    // Channel (Clan/Guest) body messages.
            };

    /*
     * Generic trace controls.
     */
    private static final boolean TRACE_WIDGET_CHANGES = true;
    private static final boolean IGNORE_Y_ONLY_CHANGES = false;
    private static final int INT_STACK_TAIL_SIZE = 16;
    private static final int MAX_LOGGED_STRING_LENGTH = 200;

    private static final String REVISION =
            "Diagnostics_REV-01";

    private final Client client;
    private final Configurations config;

    private final Deque<TraceFrame> traceFrames =
            new ArrayDeque<>();

    private TraceMode traceMode =
            TraceMode.NONE;

    private int rootScriptId =
            -1;

    private int traceSequence;

    public ChatDiagnostics(
            Client client,
            Configurations config)
    {
        this.client =
                client;

        this.config =
                config;
    }

    /*
     * ================================================================
     * SCRIPT LIFECYCLE
     * ================================================================
     */

    public void onScriptPreFired(
            ScriptPreFired event)
    {
        if (event == null)
        {
            return;
        }

        final int scriptId =
                event.getScriptId();

        if (traceMode == TraceMode.NONE)
        {
            if (isTargetRoot(
                    scriptId))
            {
                startTrace(
                        TraceMode.TARGETED,
                        scriptId);
            }
            else if (TRACE_SUPPORTED_LIFECYCLES
                    && isSupportedScript(
                    scriptId))
            {
                startTrace(
                        TraceMode.SUPPORTED,
                        scriptId);
            }
        }

        if (traceMode == TraceMode.NONE)
        {
            return;
        }

        final TraceFrame frame =
                new TraceFrame();

        frame.scriptId =
                scriptId;

        frame.sequence =
                ++traceSequence;

        frame.rootFrame =
                traceFrames.isEmpty();

        frame.before =
                snapshotChatWidgets();

        frame.intTailBefore =
                traceIntStackTail();

        frame.stringsBefore =
                traceObjectStackStrings();

        traceFrames.push(
                frame);

        if (isVerboseTrace())
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " PRE"
                            + " | sequence={}"
                            + " | depth={}"
                            + " | scriptId={}"
                            + " | intTail={}"
                            + " | strings={}",
                    frame.sequence,
                    traceFrames.size(),
                    frame.scriptId,
                    frame.intTailBefore,
                    frame.stringsBefore);
        }
    }

    public void onScriptPostFired(
            ScriptPostFired event)
    {
        if (event == null
                || traceMode == TraceMode.NONE
                || traceFrames.isEmpty())
        {
            return;
        }

        final int scriptId =
                event.getScriptId();

        final TraceFrame frame =
                traceFrames.peek();

        if (frame == null)
        {
            reset();
            return;
        }

        if (frame.scriptId
                != scriptId)
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " STACK MISMATCH"
                            + " | expectedScriptId={}"
                            + " | actualScriptId={}"
                            + " | depth={}",
                    frame.scriptId,
                    scriptId,
                    traceFrames.size());

            reset();
            return;
        }

        traceFrames.pop();

        final IdentityHashMap<Widget, WidgetState> after =
                snapshotChatWidgets();

        final boolean logChanges =
                isVerboseTrace()
                        && TRACE_WIDGET_CHANGES;

        final int mutations =
                logMutations(
                        frame,
                        after,
                        logChanges);

        if (isVerboseTrace())
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " POST"
                            + " | sequence={}"
                            + " | depth={}"
                            + " | scriptId={}"
                            + " | mutations={}"
                            + " | intTail={}",
                    frame.sequence,
                    traceFrames.size(),
                    frame.scriptId,
                    mutations,
                    traceIntStackTail());
        }

        if (!frame.rootFrame)
        {
            return;
        }

        if (traceMode == TraceMode.SUPPORTED
                && !TRACE_SUPPORTED_DETAILS)
        {
            logSupportedSummary(
                    frame,
                    mutations);
        }
        else
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " END"
                            + " | mode={}"
                            + " | rootScriptId={}"
                            + " | observedScripts={}",
                    traceMode,
                    rootScriptId,
                    traceSequence);

            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " ========================================");
        }

        finishTrace();
    }

    private void startTrace(
            TraceMode mode,
            int scriptId)
    {
        traceFrames.clear();

        traceMode =
                mode;

        rootScriptId =
                scriptId;

        traceSequence =
                0;

        if (isVerboseTrace())
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " ========================================");

            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " START"
                            + " | mode={}"
                            + " | revision={}"
                            + " | rootScriptId={}",
                    traceMode,
                    REVISION,
                    rootScriptId);

            logConfiguredProfile();
        }
    }

    private void finishTrace()
    {
        traceFrames.clear();

        traceMode =
                TraceMode.NONE;

        rootScriptId =
                -1;

        traceSequence =
                0;
    }

    private boolean isVerboseTrace()
    {
        return traceMode
                == TraceMode.TARGETED
                || (traceMode
                == TraceMode.SUPPORTED
                && TRACE_SUPPORTED_DETAILS);
    }

    /*
     * ================================================================
     * SUPPORTED SUMMARY
     * ================================================================
     */

    private void logSupportedSummary(
            TraceFrame frame,
            int mutations)
    {
        if (frame == null)
        {
            return;
        }

        final ChatFont selected =
                getConfiguredFont();

        final ChatFontProfile profile =
                ChatFontRegistry.get(
                        selected);

        if (profile == null)
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " SUPPORTED"
                            + " | scriptId={}"
                            + " | font={}"
                            + " | profile=NOT FOUND"
                            + " | intTail={}"
                            + " | strings={}"
                            + " | mutations={}",
                    frame.scriptId,
                    selected,
                    frame.intTailBefore,
                    frame.stringsBefore,
                    mutations);

            return;
        }

        log.debug(
                "[Chat XL][Diagnostic]"
                        + " SUPPORTED"
                        + " | scriptId={}"
                        + " | font={}"
                        + " | fontId={}"
                        + " | lineAdj={}"
                        + " | rowYOff={}"
                        + " | intTail={}"
                        + " | strings={}"
                        + " | mutations={}",
                frame.scriptId,
                selected,
                selected.getFontId(),
                profile.getLineHeightAdjustment(),
                profile.getRowYOffset(),
                frame.intTailBefore,
                frame.stringsBefore,
                mutations);
    }

    private void logConfiguredProfile()
    {
        final ChatFont selected =
                getConfiguredFont();

        final ChatFontProfile profile =
                ChatFontRegistry.get(
                        selected);

        if (profile == null)
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " PROFILE"
                            + " | font={}"
                            + " | profile=NOT FOUND",
                    selected);

            return;
        }

        log.debug(
                "[Chat XL][Diagnostic]"
                        + " PROFILE"
                        + " | font={}"
                        + " | fontId={}"
                        + " | lineHeightAdjustment={}"
                        + " | rowYOffset={}",
                selected,
                selected.getFontId(),
                profile.getLineHeightAdjustment(),
                profile.getRowYOffset());
    }

    private ChatFont getConfiguredFont()
    {
        final ChatFont configured =
                config != null
                        ? config.chatFont()
                        : null;

        return configured != null
                ? configured
                : ChatFont.PLAIN_12;
    }

    /*
     * ================================================================
     * WIDGET MUTATIONS
     * ================================================================
     */

    private int logMutations(
            TraceFrame frame,
            IdentityHashMap<Widget, WidgetState> after,
            boolean logChanges)
    {
        if (frame == null
                || frame.before == null
                || after == null)
        {
            return 0;
        }

        int mutationCount =
                0;

        for (Map.Entry<Widget, WidgetState> entry
                : after.entrySet())
        {
            final Widget widget =
                    entry.getKey();

            final WidgetState afterState =
                    entry.getValue();

            final WidgetState beforeState =
                    frame.before.get(
                            widget);

            if (beforeState == null)
            {
                mutationCount++;

                if (logChanges)
                {
                    log.debug(
                            "[Chat XL][Diagnostic]"
                                    + " CHANGE"
                                    + " | sequence={}"
                                    + " | scriptId={}"
                                    + " | kind=NEW"
                                    + " | identity={}"
                                    + " | after={}",
                            frame.sequence,
                            frame.scriptId,
                            System.identityHashCode(
                                    widget),
                            afterState.describe());
                }

                continue;
            }

            if (!beforeState.materiallyDiffers(
                    afterState))
            {
                continue;
            }

            mutationCount++;

            if (logChanges)
            {
                log.debug(
                        "[Chat XL][Diagnostic]"
                                + " CHANGE"
                                + " | sequence={}"
                                + " | scriptId={}"
                                + " | kind=MODIFIED"
                                + " | identity={}"
                                + " | before={}"
                                + " | after={}",
                        frame.sequence,
                        frame.scriptId,
                        System.identityHashCode(
                                widget),
                        beforeState.describe(),
                        afterState.describe());
            }
        }

        for (Map.Entry<Widget, WidgetState> entry
                : frame.before.entrySet())
        {
            if (after.containsKey(
                    entry.getKey()))
            {
                continue;
            }

            mutationCount++;

            if (logChanges)
            {
                log.debug(
                        "[Chat XL][Diagnostic]"
                                + " CHANGE"
                                + " | sequence={}"
                                + " | scriptId={}"
                                + " | kind=REMOVED"
                                + " | identity={}"
                                + " | before={}",
                        frame.sequence,
                        frame.scriptId,
                        System.identityHashCode(
                                entry.getKey()),
                        entry.getValue()
                                .describe());
            }
        }

        return mutationCount;
    }

    /*
     * ================================================================
     * CHAT WIDGET SNAPSHOTS
     * ================================================================
     */

    private IdentityHashMap<Widget, WidgetState> snapshotChatWidgets()
    {
        final IdentityHashMap<Widget, WidgetState> result =
                new IdentityHashMap<>();

        final IdentityHashMap<Widget, Boolean> visited =
                new IdentityHashMap<>();

        collectWidgetTree(
                client.getWidget(
                        InterfaceID.Chatbox.SCROLLAREA),
                Surface.CHATBOX,
                result,
                visited);

        collectWidgetTree(
                client.getWidget(
                        InterfaceID.PM_CHAT,
                        0),
                Surface.SPLIT_PRIVATE,
                result,
                visited);

        return result;
    }

    private void collectWidgetTree(
            Widget widget,
            Surface surface,
            IdentityHashMap<Widget, WidgetState> result,
            IdentityHashMap<Widget, Boolean> visited)
    {
        if (widget == null
                || surface == null
                || result == null
                || visited == null
                || visited.containsKey(
                widget))
        {
            return;
        }

        visited.put(
                widget,
                Boolean.TRUE);

        final String semanticText =
                normalizeSemantic(
                        widget.getText());

        final boolean hasText =
                semanticText != null
                        && !semanticText.isEmpty();

        final boolean hasSprite =
                widget.getSpriteId() >= 0;

        if (hasText
                || hasSprite)
        {
            result.put(
                    widget,
                    new WidgetState(
                            surface,
                            widget,
                            semanticText));
        }

        collectWidgetArray(
                widget.getDynamicChildren(),
                surface,
                result,
                visited);

        collectWidgetArray(
                widget.getStaticChildren(),
                surface,
                result,
                visited);

        collectWidgetArray(
                widget.getNestedChildren(),
                surface,
                result,
                visited);
    }

    private void collectWidgetArray(
            Widget[] widgets,
            Surface surface,
            IdentityHashMap<Widget, WidgetState> result,
            IdentityHashMap<Widget, Boolean> visited)
    {
        if (widgets == null)
        {
            return;
        }

        for (Widget widget : widgets)
        {
            collectWidgetTree(
                    widget,
                    surface,
                    result,
                    visited);
        }
    }

    /*
     * Manual snapshot for one-off debugging.
     */
    public void dumpVisibleChatWidgets(
            String reason)
    {
        final IdentityHashMap<Widget, WidgetState> snapshot =
                snapshotChatWidgets();

        log.debug(
                "[Chat XL][Diagnostic]"
                        + " VISIBLE CHAT DUMP"
                        + " | reason='{}'"
                        + " | widgets={}",
                reason != null
                        ? reason
                        : "",
                snapshot.size());

        for (Map.Entry<Widget, WidgetState> entry
                : snapshot.entrySet())
        {
            log.debug(
                    "[Chat XL][Diagnostic]"
                            + " WIDGET"
                            + " | identity={}"
                            + " | {}",
                    System.identityHashCode(
                            entry.getKey()),
                    entry.getValue()
                            .describe());
        }
    }

    /*
     * ================================================================
     * STACK HELPERS
     * ================================================================
     */

    private String traceIntStackTail()
    {
        final int[] stack =
                client.getIntStack();

        final int size =
                client.getIntStackSize();

        if (stack == null
                || size <= 0
                || size > stack.length)
        {
            return "[]";
        }

        final int start =
                Math.max(
                        0,
                        size
                                - INT_STACK_TAIL_SIZE);

        return Arrays.toString(
                Arrays.copyOfRange(
                        stack,
                        start,
                        size));
    }

    private String traceObjectStackStrings()
    {
        final Object[] stack =
                client.getObjectStack();

        final int size =
                client.getObjectStackSize();

        if (stack == null
                || size <= 0
                || size > stack.length)
        {
            return "[]";
        }

        final List<String> strings =
                new ArrayList<>();

        for (int i = 0;
             i < size;
             i++)
        {
            final Object value =
                    stack[i];

            if (!(value instanceof String))
            {
                continue;
            }

            String text =
                    (String) value;

            text =
                    text.replace(
                                    "\r\n",
                                    "\\n")
                            .replace(
                                    '\r',
                                    '\n')
                            .replace(
                                    "\n",
                                    "\\n");

            if (text.length()
                    > MAX_LOGGED_STRING_LENGTH)
            {
                text =
                        text.substring(
                                0,
                                MAX_LOGGED_STRING_LENGTH)
                                + "...";
            }

            strings.add(
                    "'"
                            + text
                            + "'");
        }

        return strings.toString();
    }

    private String normalizeSemantic(
            String text)
    {
        if (text == null)
        {
            return null;
        }

        final String withLineBreaks =
                text.replaceAll(
                        "(?i)<br\\s*/?>",
                        "\n");

        return Text.removeTags(
                        withLineBreaks)
                .replace(
                        '\u00A0',
                        ' ')
                .replace(
                        '\u202F',
                        ' ')
                .replace(
                        '\u2009',
                        ' ')
                .trim();
    }

    /*
     * ================================================================
     * STATE
     * ================================================================
     */

    public void reset()
    {
        finishTrace();
    }

    private boolean isSupportedScript(
            int scriptId)
    {
        return scriptId
                == GAME_BODY_SCRIPT
                || scriptId
                == CHAT_BODY_SCRIPT
                || scriptId
                == CHANNEL_BODY_SCRIPT;
    }

    private boolean isTargetRoot(
            int scriptId)
    {
        for (int targetScriptId : TARGET_ROOT_SCRIPTS)
        {
            if (scriptId == targetScriptId)
            {
                return true;
            }
        }

        return false;
    }

    private enum TraceMode
    {
        NONE,
        SUPPORTED,
        TARGETED
    }

    private enum Surface
    {
        CHATBOX,
        SPLIT_PRIVATE
    }

    private static final class TraceFrame
    {
        private int scriptId;
        private int sequence;
        private boolean rootFrame;

        private IdentityHashMap<Widget, WidgetState> before;

        private String intTailBefore;
        private String stringsBefore;
    }

    private static final class WidgetState
    {
        private final Surface surface;

        private final int id;
        private final int parentId;

        private final String text;

        private final int spriteId;
        private final int fontId;
        private final int lineHeight;

        private final int originalX;
        private final int originalY;
        private final int originalWidth;
        private final int originalHeight;

        private final int relativeX;
        private final int relativeY;

        private final int width;
        private final int height;

        private final boolean hidden;

        private WidgetState(
                Surface surface,
                Widget widget,
                String text)
        {
            this.surface =
                    surface;

            this.id =
                    widget.getId();

            this.parentId =
                    widget.getParentId();

            this.text =
                    text != null
                            ? text
                            : "";

            this.spriteId =
                    widget.getSpriteId();

            this.fontId =
                    widget.getFontId();

            this.lineHeight =
                    widget.getLineHeight();

            this.originalX =
                    widget.getOriginalX();

            this.originalY =
                    widget.getOriginalY();

            this.originalWidth =
                    widget.getOriginalWidth();

            this.originalHeight =
                    widget.getOriginalHeight();

            this.relativeX =
                    widget.getRelativeX();

            this.relativeY =
                    widget.getRelativeY();

            this.width =
                    widget.getWidth();

            this.height =
                    widget.getHeight();

            this.hidden =
                    widget.isHidden();
        }

        private boolean materiallyDiffers(
                WidgetState other)
        {
            if (other == null)
            {
                return true;
            }

            if (surface != other.surface
                    || id != other.id
                    || parentId != other.parentId
                    || !text.equals(
                    other.text)
                    || spriteId != other.spriteId
                    || fontId != other.fontId
                    || lineHeight != other.lineHeight
                    || originalX != other.originalX
                    || originalWidth != other.originalWidth
                    || originalHeight != other.originalHeight
                    || relativeX != other.relativeX
                    || width != other.width
                    || height != other.height
                    || hidden != other.hidden)
            {
                return true;
            }

            return !IGNORE_Y_ONLY_CHANGES
                    && (originalY
                    != other.originalY
                    || relativeY
                    != other.relativeY);
        }

        private String describe()
        {
            return "surface="
                    + surface
                    + ", id="
                    + id
                    + ", parentId="
                    + parentId
                    + ", text='"
                    + text
                    + "'"
                    + ", spriteId="
                    + spriteId
                    + ", fontId="
                    + fontId
                    + ", lineHeight="
                    + lineHeight
                    + ", original=[x="
                    + originalX
                    + ", y="
                    + originalY
                    + ", w="
                    + originalWidth
                    + ", h="
                    + originalHeight
                    + "]"
                    + ", relative=[x="
                    + relativeX
                    + ", y="
                    + relativeY
                    + "]"
                    + ", calculated=[w="
                    + width
                    + ", h="
                    + height
                    + "]"
                    + ", hidden="
                    + hidden;
        }
    }
}
