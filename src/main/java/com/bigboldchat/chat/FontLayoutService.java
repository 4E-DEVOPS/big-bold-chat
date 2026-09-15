package com.bigboldchat.chat;

import com.bigboldchat.Configurations;
import com.bigboldchat.config.ChatFont;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Production Chat XL layout service.
 *
 * FontMeasurementService determines what geometry is required.
 *
 * This production service is intentionally silent. Diagnostic output belongs
 * exclusively to ChatDiagnostics.
 *
 * This service owns the lifecycle:
 *
 * PRE
 *  - request a ConstructionMeasurement;
 *  - inject the required row allocation;
 *  - inject the selected row-Y position;
 *  - preserve unknown / native construction values.
 *
 * POST
 *  - correlate the exact constructed body;
 *  - correlate prefix / channel / username widgets when present;
 *  - apply the selected FontID;
 *  - apply selected line height;
 *  - apply selected horizontal geometry;
 *  - resize / horizontally position the correct rank icon;
 *  - queue component-specific vertical corrections.
 *
 * FINALIZE
 *  - after RuneScape establishes final native row Y;
 *  - apply queued Channel / Friends Chat component-specific corrections
 *    at chat Script 72 PRE.
 *
 * Ordinary Script 199 GAME / system rows do not use component-specific
 * vertical corrections. Their profile row Y is owned entirely by PRE geometry.
 */
public final class FontLayoutService
{
    /*
     * RuneScape's final chat-row positioning lifecycle.
     *
     * Diagnostic tracing confirmed that native Y placement for Channel,
     * Guest Clan, and Friends Chat row components has completed before
     * this script begins.
     */
    private static final int CHAT_FINALIZE_SCRIPT = 72;

    private final Client client;
    private final Configurations config;
    private final FontMeasurementService measurementService;
    private final ChatTextNormalizer textNormalizer;

    // TODO: Record layout performance during development.
    private final PerformanceMetrics performanceMetrics;

    /*
     * Active font selection resolved outside the construction hot path.
     *
     * Configuration changes replace this immutable pair once, while every
     * supported PRE construction simply snapshots the already-resolved state.
     */
    private ActiveFontState activeFontState;

    /*
     * One exact PRE -> POST construction pair.
     *
     * Nested scripts do not replace this state because only supported
     * construction scripts create a new measurement.
     */
    private FontMeasurementService.ConstructionMeasurement pending;

    /*
     * Exact profile paired with the pending PRE -> POST construction.
     *
     * This snapshots activeFontState for the construction so POST cannot
     * observe a different profile if configuration changes between events.
     */
    private ChatFontProfile pendingFontProfile;

    /*
     * Widgets whose profile-specific Y correction must wait until RuneScape
     * has completed its native row positioning.
     *
     * Identity semantics are intentional because RuneScape recycles
     * individual Widget objects between chat-row slots.
     *
     * Text widgets retain their expected semantic text while separate rank
     * widgets retain their expected sprite ID. Those guards prevent a queued
     * correction from being applied after a Widget object has been recycled
     * for a different logical row component.
     */
    private final IdentityHashMap<Widget, PendingYOffset> pendingYOffsets =
            new IdentityHashMap<>();

    /*
     * Native presentation values captured immediately before Chat XL first
     * mutates a widget field.
     *
     * RuneScape recycles chat Widget objects. Each field therefore also tracks
     * the last value applied by Chat XL. If RuneScape changes that field later,
     * the next Chat XL mutation treats the current value as a new native
     * baseline instead of restoring stale geometry from an older logical row.
     */
    private final IdentityHashMap<Widget, NativeWidgetState> nativeWidgetStates =
            new IdentityHashMap<>();

    /*
     * Persistent row indexes for each chat surface.
     *
     * RuneScape constructs many rows against the same surface. Rebuilding the
     * complete candidate set for every POST was the dominant remaining
     * correlation cost. Each surface index groups the same widgets previously
     * examined by collectRow() by their exact native OriginalY + RelativeY
     * pair and reuses that grouping across constructions.
     *
     * Candidate geometry is validated before reuse. If RuneScape has recycled
     * or repositioned any indexed widget for the requested row, that surface
     * is rebuilt before correlation continues. Broad semantic fallback remains
     * unchanged as the final correctness path.
     */
    private final Map<Surface, RowCorrelationIndex> rowIndexes =
            new EnumMap<>(
                    Surface.class);

    public FontLayoutService(
            Client client,
            Configurations config,
            FontMeasurementService measurementService)
    {
        this(
                client,
                config,
                measurementService,
                new ChatTextNormalizer(),
                null);
    }

    public FontLayoutService(
            Client client,
            Configurations config,
            FontMeasurementService measurementService,
            PerformanceMetrics performanceMetrics)
    {
        this(
                client,
                config,
                measurementService,
                new ChatTextNormalizer(
                        performanceMetrics),
                performanceMetrics);
    }

    public FontLayoutService(
            Client client,
            Configurations config,
            FontMeasurementService measurementService,
            ChatTextNormalizer textNormalizer,
            PerformanceMetrics performanceMetrics)
    {
        this.client =
                client;

        this.config =
                config;

        this.measurementService =
                measurementService;

        this.textNormalizer =
                textNormalizer != null
                        ? textNormalizer
                        : new ChatTextNormalizer(
                        performanceMetrics);

        this.performanceMetrics =
                performanceMetrics;

        refreshActiveFontState();
    }

    /*
     * ================================================================
     * PRE
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

        /*
         * Script 72 begins only after RuneScape has replaced construction-local
         * component Y values with the row's final native Y.
         *
         * Apply any queued component-specific corrections now, before Script 72
         * executes.
         */
        if (scriptId == CHAT_FINALIZE_SCRIPT)
        {
            applyPendingYOffsetsIfChatFinalize();
            return;
        }

        if (!measurementService.supportsScript(
                scriptId))
        {
            return;
        }

        /*
         * A supported PRE starts a new exact construction pair.
         *
         * Supported constructors are resolved by MeasurementService:
         * Script 199 GAME/system, Script 203 chat/private/FC, and
         * Script 4483 Clan/Guest Clan.
         */
        pending = null;
        pendingFontProfile = null;

        final ActiveFontState fontState =
                activeFontState;

        if (fontState == null
                || fontState.fontProfile == null)
        {
            return;
        }

        final ChatFont selectedChatFont =
                fontState.chatFont;

        final ChatFontProfile fontProfile =
                fontState.fontProfile;

        // TODO: Measure construction measurement during development.
        final long measurementStarted =
                performanceMetrics != null
                        ? System.nanoTime()
                        : 0L;

        final FontMeasurementService.ConstructionMeasurement measurement =
                measurementService.measure(
                        scriptId,
                        selectedChatFont,
                        fontProfile);

        if (performanceMetrics != null)
        {
            performanceMetrics.recordMeasurement(
                    System.nanoTime()
                            - measurementStarted);
        }

        if (measurement == null)
        {
            return;
        }

        final int[] intStack =
                client.getIntStack();

        final int intStackSize =
                client.getIntStackSize();

        if (intStack == null
                || intStackSize <= 0
                || intStackSize > intStack.length)
        {
            return;
        }

        /*
         * PRE owns construction geometry.
         *
         * Height:
         *     compensated for the selected font's wrapping / cadence.
         *
         * Row Y:
         *     profile-specific ARG6 adjustment.
         *
         * ARG7:
         *     unknown; preserve RuneScape's native value.
         *
         * Sender width:
         *     preserve RuneScape's native value. Selected horizontal
         *     presentation is applied to the resulting widgets in POST.
         */
        intStack[measurement.verticalValueIndex] =
                measurement.injectedValue;

        intStack[measurement.rowYIndex] =
                measurement.selectedRowY;

        intStack[measurement.argument7Index] =
                measurement.nativeArgument7;

        intStack[measurement.senderWidthIndex] =
                measurement.nativeSenderWidth;

        pending =
                measurement;

        pendingFontProfile =
                fontProfile;
    }

    /*
     * ================================================================
     * POST
     * ================================================================
     */

    public void onScriptPostFired(
            ScriptPostFired event)
    {
        if (event == null
                || pending == null
                || event.getScriptId()
                != pending.scriptId)
        {
            return;
        }

        final FontMeasurementService.ConstructionMeasurement state =
                pending;

        final ChatFontProfile fontProfile =
                pendingFontProfile;

        pending =
                null;

        pendingFontProfile =
                null;

        final Widget lineWidget =
                client.getWidget(
                        state.lineWidgetId);

        if (lineWidget == null)
        {
            return;
        }

        final Surface surface =
                determineSurface(
                        state.scriptId,
                        state.parentWidgetId);

        final List<Widget> rowWidgets =
                collectRow(
                        lineWidget,
                        surface);

        /*
         * Row-first correlation should satisfy ordinary chat reconstruction.
         *
         * Keep the broad surface fallback completely lazy: no index is built
         * unless a row lookup actually misses. If one miss does occur, the
         * same normalized surface index is reused by every remaining body /
         * prefix lookup in this exact POST construction.
         */
        final FallbackCorrelationContext fallbackContext =
                new FallbackCorrelationContext(
                        surface);

        final Widget bodyWidget =
                findTargetWidgetForLine(
                        state.semanticBody,
                        lineWidget,
                        surface,
                        rowWidgets,
                        fallbackContext);

        if (bodyWidget == null)
        {
            return;
        }

        /*
         * Script 199 GAME / system rows are prefix-less, so this naturally
         * returns an empty list for that constructor.
         */
        final List<Widget> prefixWidgets =
                findPrefixWidgetsForLine(
                        state.rawPrefixComponents,
                        lineWidget,
                        surface,
                        bodyWidget,
                        rowWidgets,
                        fallbackContext);

        final Widget rowAnchor =
                !prefixWidgets.isEmpty()
                        ? prefixWidgets.get(0)
                        : lineWidget;

        /*
         * Find the rank icon BEFORE changing any channel text Y values.
         *
         * The strict matcher relies on RuneScape's native row geometry.
         */
        final Widget rankIconWidget;

        if (state.scriptId
                == FontMeasurementService.CHANNEL_BODY_SCRIPT
                && state.nativeChannelLayout != null
                && state.nativeChannelLayout.rankIconSpriteId >= 0)
        {
            rankIconWidget =
                    findRankIconWidget(
                            state.nativeChannelLayout,
                            rowAnchor,
                            rowWidgets);
        }
        else
        {
            rankIconWidget =
                    null;
        }

        applyPrefixPresentation(
                state,
                prefixWidgets,
                fontProfile);

        applyBodyPresentation(
                state,
                bodyWidget,
                fontProfile);

        applyRankIconPresentation(
                state,
                rankIconWidget,
                fontProfile);
    }

    /*
     * ================================================================
     * PRESENTATION
     * ================================================================
     */

    private void applyBodyPresentation(
            FontMeasurementService.ConstructionMeasurement state,
            Widget bodyWidget,
            ChatFontProfile fontProfile)
    {
        if (state == null
                || bodyWidget == null)
        {
            return;
        }

        boolean changed =
                setFontIdIfChanged(
                        bodyWidget,
                        state.selectedFontId);

        changed |=
                setLineHeightIfChanged(
                        bodyWidget,
                        state.selectedLineHeight);

        /*
         * Correlation has already completed against the native body text.
         * Apply the exact selected text measured by MeasurementService.
         */
        if (state.selectedRawBodyText != null
                && !state.selectedRawBodyText.equals(
                bodyWidget.getText()))
        {
            changed |=
                    setTextIfChanged(
                            bodyWidget,
                            state.selectedRawBodyText);
        }

        changed |=
                setOriginalXIfChanged(
                        bodyWidget,
                        state.selectedBodyX);

        changed |=
                setOriginalWidthIfChanged(
                        bodyWidget,
                        state.selectedBodyWidth);

        /*
         * Independent body-text Y corrections are presentation adjustments,
         * not construction geometry.
         *
         * RuneScape performs another native row-positioning pass after the
         * supported constructor returns, so applying these offsets here would
         * be overwritten. Queue the exact correlated body widget and apply the
         * correction only after native final row Y has been established.
         *
         * Script 199 GAME / system rows intentionally do not enter either
         * branch below. Their vertical behavior is controlled only by the
         * profile ROW_Y_OFFSET injected during PRE.
         */
        if (fontProfile != null
                && state.scriptId
                == FontMeasurementService.CHANNEL_BODY_SCRIPT)
        {
            queueTextYOffset(
                    bodyWidget,
                    fontProfile.getChannelTextYOffset());
        }
        else if (fontProfile != null
                && isFriendsChatConstruction(
                state))
        {
            queueTextYOffset(
                    bodyWidget,
                    fontProfile.getFriendsChatTextYOffset());
        }
        else
        {
            pendingYOffsets.remove(
                    bodyWidget);
        }

        revalidateWidgetIfChanged(
                bodyWidget,
                changed);
    }

    private void applyPrefixPresentation(
            FontMeasurementService.ConstructionMeasurement state,
            List<Widget> prefixWidgets,
            ChatFontProfile fontProfile)
    {
        if (state == null
                || prefixWidgets == null
                || prefixWidgets.isEmpty())
        {
            return;
        }

        for (Widget widget : prefixWidgets)
        {
            if (widget == null)
            {
                continue;
            }

            final String semantic =
                    textNormalizer.normalizeSemantic(
                            widget.getText());

            if (semantic == null
                    || semantic.isEmpty())
            {
                continue;
            }

            boolean changed =
                    setFontIdIfChanged(
                            widget,
                            state.selectedFontId);

            changed |=
                    setLineHeightIfChanged(
                            widget,
                            state.selectedLineHeight);

            /*
             * Script 203 uses one textual prefix widget.
             */
            if (state.scriptId
                    == FontMeasurementService.CHAT_BODY_SCRIPT
                    && prefixWidgets.size()
                    == 1)
            {
                changed |=
                        setOriginalWidthIfChanged(
                                widget,
                                state.selectedPrefixLayoutWidth);

                /*
                 * MeasurementService retains the native prefix for correlation and
                 * separately provides the exact selected text to render.
                 *
                 * This includes Friends Chat icon spacing and the Verdana 13 Bold
                 * malformed-colon replacement.
                 */
                if (state.selectedRawPrefixText != null
                        && !state.selectedRawPrefixText.equals(
                        widget.getText()))
                {
                    changed |=
                            setTextIfChanged(
                                    widget,
                                    state.selectedRawPrefixText);
                }
            }

            /*
             * Script 4483 uses separate channel-title and username widgets.
             *
             * Their independent Y corrections are queued here but deliberately
             * not applied until RuneScape has established final native row Y.
             */
            if (state.scriptId
                    == FontMeasurementService.CHANNEL_BODY_SCRIPT
                    && state.selectedChannelLayout != null)
            {
                final FontMeasurementService.ChannelPrefixLayout channel =
                        state.selectedChannelLayout;

                boolean recognizedChannelComponent =
                        false;

                if (channel.hasTitle
                        && semantic.equalsIgnoreCase(
                        channel.titleText))
                {
                    changed |=
                            setOriginalXIfChanged(
                                    widget,
                                    channel.titleX);

                    changed |=
                            setOriginalWidthIfChanged(
                                    widget,
                                    channel.titleWidth);

                    if (channel.renderedTitleText != null
                            && !channel.renderedTitleText.isEmpty()
                            && !channel.renderedTitleText.equals(
                            widget.getText()))
                    {
                        changed |=
                                setTextIfChanged(
                                        widget,
                                        channel.renderedTitleText);
                    }

                    recognizedChannelComponent =
                            true;

                    if (fontProfile != null)
                    {
                        queueTextYOffset(
                                widget,
                                fontProfile.getChannelNameYOffset());
                    }
                }

                if (channel.hasSender
                        && semantic.equalsIgnoreCase(
                        channel.senderText))
                {
                    changed |=
                            setOriginalXIfChanged(
                                    widget,
                                    channel.senderX);

                    changed |=
                            setOriginalWidthIfChanged(
                                    widget,
                                    channel.senderWidth);

                    /*
                     * Apply the exact sender markup that MeasurementService measured.
                     *
                     * This may include account/build icon spacing and the Verdana 13 Bold
                     * malformed-colon replacement.
                     *
                     * Correlation has already completed using channel.senderText, so changing
                     * the rendered text here cannot interfere with row matching.
                     */
                    if (channel.renderedSenderText != null
                            && !channel.renderedSenderText.isEmpty()
                            && !channel.renderedSenderText.equals(
                            widget.getText()))
                    {
                        changed |=
                                setTextIfChanged(
                                        widget,
                                        channel.renderedSenderText);
                    }

                    recognizedChannelComponent =
                            true;

                    if (fontProfile != null)
                    {
                        queueTextYOffset(
                                widget,
                                fontProfile.getChannelUsernameYOffset());
                    }
                }

                if (!recognizedChannelComponent
                        || fontProfile == null)
                {
                    pendingYOffsets.remove(
                            widget);
                }
            }
            else if (state.scriptId
                    == FontMeasurementService.CHAT_BODY_SCRIPT
                    && prefixWidgets.size()
                    == 1
                    && fontProfile != null
                    && isFriendsChatConstruction(
                    state))
            {
                /*
                 * Friends Chat renders the channel name, inline rank icon, and
                 * username inside one prefix widget. They therefore share one
                 * vertical correction.
                 */
                queueTextYOffset(
                        widget,
                        fontProfile.getFriendsChatPrefixYOffset());
            }
            else
            {
                pendingYOffsets.remove(
                        widget);
            }

            if (widget.getOriginalHeight()
                    < state.selectedLineHeight)
            {
                changed |=
                        setOriginalHeightIfChanged(
                                widget,
                                state.selectedLineHeight);
            }

            revalidateWidgetIfChanged(
                    widget,
                    changed);
        }
    }

    private void applyRankIconPresentation(
            FontMeasurementService.ConstructionMeasurement state,
            Widget rankIconWidget,
            ChatFontProfile fontProfile)
    {
        if (state == null
                || rankIconWidget == null
                || state.selectedChannelLayout == null)
        {
            return;
        }

        /*
         * Horizontal position, width, and height are determined by
         * FontMeasurementService.
         *
         * The rank icon is correlated while it still has RuneScape's native
         * geometry. Only after that exact widget has been identified do we
         * apply the selected font's rank-icon presentation.
         *
         * Per-font right-side spacing is already reflected in senderX and
         * bodyX. It does not move the icon itself.
         */
        boolean changed =
                setOriginalXIfChanged(
                        rankIconWidget,
                        state.selectedChannelLayout.rankIconX);

        changed |=
                setOriginalWidthIfChanged(
                        rankIconWidget,
                        state.selectedChannelLayout.rankIconWidth);

        changed |=
                setOriginalHeightIfChanged(
                        rankIconWidget,
                        state.selectedChannelLayout.rankIconHeight);

        /*
         * Rank-icon vertical correction is independent from the channel title,
         * username, and body text.
         *
         * Do NOT apply it during Script 4483 POST.
         *
         * RuneScape performs another native row-positioning pass after 4483
         * returns, which overwrites the rank widget's Y while preserving its
         * selected X / width / height.
         *
         * Remember the exact reconstructed sprite widget instead. Its Y
         * correction will be applied once native row positioning has completed.
         */
        if (fontProfile != null)
        {
            queueSpriteYOffset(
                    rankIconWidget,
                    state.nativeChannelLayout.rankIconSpriteId,
                    fontProfile.getChannelRankIconYOffset());
        }
        else
        {
            pendingYOffsets.remove(
                    rankIconWidget);
        }

        revalidateWidgetIfChanged(
                rankIconWidget,
                changed);
    }

    /*
     * Queue a delayed Y correction for a text-bearing widget.
     *
     * The semantic text is retained as a recycling guard because RuneScape may
     * reuse the same Widget object for another logical row before finalization.
     */
    private void queueTextYOffset(
            Widget widget,
            int yOffset)
    {
        if (widget == null)
        {
            return;
        }

        if (yOffset == 0)
        {
            pendingYOffsets.remove(
                    widget);

            return;
        }

        final String semantic =
                textNormalizer.normalizeSemantic(
                        widget.getText());

        if (semantic == null
                || semantic.isEmpty())
        {
            pendingYOffsets.remove(
                    widget);

            return;
        }

        pendingYOffsets.put(
                widget,
                PendingYOffset.forText(
                        semantic,
                        yOffset));
    }

    /*
     * Queue a delayed Y correction for a separate sprite widget.
     */
    private void queueSpriteYOffset(
            Widget widget,
            int expectedSpriteId,
            int yOffset)
    {
        if (widget == null)
        {
            return;
        }

        if (yOffset == 0
                || expectedSpriteId < 0)
        {
            pendingYOffsets.remove(
                    widget);

            return;
        }

        pendingYOffsets.put(
                widget,
                PendingYOffset.forSprite(
                        expectedSpriteId,
                        yOffset));
    }

    /*
     * Apply queued component-specific Y corrections after RuneScape's native
     * chat-row positioning has completed.
     *
     * This method intentionally handles only the chat-specific Script 72
     * invocation whose parent argument is CHATBOX_MESSAGE_LINES.
     */
    private void applyPendingYOffsetsIfChatFinalize()
    {
        if (pendingYOffsets.isEmpty()
                || !isChatFinalizeInvocation())
        {
            return;
        }

        for (Widget widget
                : pendingYOffsets.keySet())
        {
            if (widget == null)
            {
                continue;
            }

            final PendingYOffset pendingOffset =
                    pendingYOffsets.get(
                            widget);

            if (pendingOffset == null
                    || pendingOffset.yOffset == 0
                    || widget.isHidden())
            {
                continue;
            }

            /*
             * Every queued correction carries a lightweight identity guard.
             *
             * Text components must still contain the same semantic text.
             * Separate rank widgets must still contain the same sprite.
             */
            if (!pendingOffset.matches(
                    widget,
                    textNormalizer))
            {
                continue;
            }

            applySynchronizedYOffset(
                    widget,
                    pendingOffset.yOffset);
        }

        /*
         * Each queued correction belongs only to this finalized reconstruction
         * cycle. Clearing here prevents cumulative offsets on later refreshes.
         */
        pendingYOffsets.clear();
    }

    /*
     * Refresh the selected font/profile pair only when configuration changes.
     *
     * This intentionally sits outside the supported construction hot path.
     * PRE snapshots this state once and POST consumes that exact snapshot.
     */
    public void refreshActiveFontState()
    {
        final ChatFont configuredFont =
                config != null
                        ? config.chatFont()
                        : null;

        final ChatFont selectedChatFont =
                configuredFont != null
                        ? configuredFont
                        : ChatFont.PLAIN_12;

        final ChatFontProfile fontProfile =
                ChatFontRegistry.get(
                        selectedChatFont);

        if (fontProfile == null)
        {
            activeFontState =
                    null;
            return;
        }

        final ActiveFontState current =
                activeFontState;

        if (current != null
                && current.chatFont == selectedChatFont
                && current.fontProfile == fontProfile)
        {
            return;
        }

        activeFontState =
                new ActiveFontState(
                        selectedChatFont,
                        fontProfile);
    }

    /*
     * Package-private test seams.
     *
     * These expose only the state required by FontLayoutServiceTest and keep
     * the test suite free of Java reflection. They do not alter runtime
     * behavior or lifecycle ownership.
     */
    ChatFont activeChatFontForTesting()
    {
        return activeFontState != null
                ? activeFontState.chatFont
                : null;
    }

    ChatFontProfile activeFontProfileForTesting()
    {
        return activeFontState != null
                ? activeFontState.fontProfile
                : null;
    }

    ChatFontProfile pendingFontProfileForTesting()
    {
        return pendingFontProfile;
    }

    void setPendingFontProfileForTesting(
            ChatFontProfile fontProfile)
    {
        pendingFontProfile =
                fontProfile;
    }

    /*
     * Script 203 is shared by several chat surfaces.
     *
     * Friends Chat is identified by its single combined textual prefix:
     *
     *     [Friends Chat name] + optional inline rank + username
     *
     * The channel name remains bracketed after semantic normalization, while
     * ordinary public/private Script 203 prefixes do not use this structure.
     */
    private boolean isFriendsChatConstruction(
            FontMeasurementService.ConstructionMeasurement state)
    {
        if (state == null
                || state.scriptId
                != FontMeasurementService.CHAT_BODY_SCRIPT
                || state.rawPrefixComponents == null
                || state.rawPrefixComponents.size()
                != 1)
        {
            return false;
        }

        final String semanticPrefix =
                textNormalizer.normalizeSemantic(
                        state.rawPrefixComponents.get(
                                0));

        if (semanticPrefix == null
                || semanticPrefix.length() < 3
                || semanticPrefix.charAt(0) != '[')
        {
            return false;
        }

        final int closingBracket =
                semanticPrefix.indexOf(
                        ']');

        return closingBracket > 0
                && closingBracket
                < semanticPrefix.length() - 1;
    }

    /*
     * Script 72 is used in more than one client/interface lifecycle.
     *
     * The relevant chat invocation has the CHATBOX_MESSAGE_LINES widget ID
     * as the penultimate integer-stack argument:
     *
     *     [..., messageContainerId, chatMessageLinesId, mode]
     */
    private boolean isChatFinalizeInvocation()
    {
        final int[] intStack =
                client.getIntStack();

        final int intStackSize =
                client.getIntStackSize();

        if (intStack == null
                || intStackSize < 2
                || intStackSize > intStack.length)
        {
            return false;
        }

        final Widget chatMessageLines =
                client.getWidget(
                        InterfaceID.Chatbox.SCROLLAREA);

        if (chatMessageLines == null)
        {
            return false;
        }

        final int parentWidgetId =
                intStack[intStackSize - 2];

        return parentWidgetId
                == chatMessageLines.getId();
    }

    /**
     * Applies a post-construction vertical correction to a chat widget.
     *
     * Widget Inspector testing showed that these chat widgets move reliably
     * when OriginalY and RelativeY are both assigned the same adjusted value.
     *
     * OriginalY represents the widget's configured vertical position while
     * RelativeY represents its current live position within the parent.
     *
     * Both values are therefore kept synchronized whenever a profile-specific
     * vertical offset is applied.
     */
    @SuppressWarnings("deprecation")
    void applySynchronizedYOffset(
            Widget widget,
            int yOffset)
    {
        if (widget == null
                || yOffset == 0)
        {
            return;
        }

        final RowKey previousRow =
                RowKey.of(
                        widget);

        final int adjustedY =
                widget.getOriginalY()
                        + yOffset;

        boolean changed =
                setOriginalYIfChanged(
                        widget,
                        adjustedY);

        changed |=
                setRelativeYIfChanged(
                        widget,
                        adjustedY);

        if (changed)
        {
            notifyIndexedWidgetGeometryChanged(
                    widget,
                    previousRow);
        }
    }

    /*
     * ================================================================
     * IDEMPOTENT WIDGET MUTATION / CONDITIONAL REVALIDATION
     * ================================================================
     */

    boolean setFontIdIfChanged(
            Widget widget,
            int fontId)
    {
        if (widget == null
                || widget.getFontId() == fontId)
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final int currentFontId =
                widget.getFontId();

        if (!state.fontIdCaptured
                || currentFontId
                != state.appliedFontId)
        {
            state.nativeFontId =
                    currentFontId;

            state.fontIdCaptured =
                    true;
        }

        state.appliedFontId =
                fontId;

        widget.setFontId(
                fontId);

        recordWidgetMutation();
        return true;
    }

    private boolean setLineHeightIfChanged(
            Widget widget,
            int lineHeight)
    {
        if (widget == null
                || widget.getLineHeight() == lineHeight)
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final int currentLineHeight =
                widget.getLineHeight();

        if (!state.lineHeightCaptured
                || currentLineHeight
                != state.appliedLineHeight)
        {
            state.nativeLineHeight =
                    currentLineHeight;

            state.lineHeightCaptured =
                    true;
        }

        state.appliedLineHeight =
                lineHeight;

        widget.setLineHeight(
                lineHeight);

        recordWidgetMutation();
        return true;
    }

    private boolean setTextIfChanged(
            Widget widget,
            String text)
    {
        if (widget == null
                || text == null
                || text.equals(
                widget.getText()))
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final String currentText =
                widget.getText();

        if (!state.textCaptured
                || !sameText(
                currentText,
                state.appliedText))
        {
            state.nativeText =
                    currentText;

            state.textCaptured =
                    true;
        }

        state.appliedText =
                text;

        widget.setText(
                text);

        recordWidgetMutation();
        return true;
    }

    private boolean setOriginalXIfChanged(
            Widget widget,
            int originalX)
    {
        if (widget == null
                || widget.getOriginalX() == originalX)
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final int currentOriginalX =
                widget.getOriginalX();

        if (!state.originalXCaptured
                || currentOriginalX
                != state.appliedOriginalX)
        {
            state.nativeOriginalX =
                    currentOriginalX;

            state.originalXCaptured =
                    true;
        }

        state.appliedOriginalX =
                originalX;

        widget.setOriginalX(
                originalX);

        recordWidgetMutation();
        return true;
    }

    private boolean setOriginalYIfChanged(
            Widget widget,
            int originalY)
    {
        if (widget == null
                || widget.getOriginalY() == originalY)
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final int currentOriginalY =
                widget.getOriginalY();

        if (!state.originalYCaptured
                || currentOriginalY
                != state.appliedOriginalY)
        {
            state.nativeOriginalY =
                    currentOriginalY;

            state.originalYCaptured =
                    true;
        }

        state.appliedOriginalY =
                originalY;

        widget.setOriginalY(
                originalY);

        recordWidgetMutation();
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean setRelativeYIfChanged(
            Widget widget,
            int relativeY)
    {
        if (widget == null
                || widget.getRelativeY() == relativeY)
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final int currentRelativeY =
                widget.getRelativeY();

        if (!state.relativeYCaptured
                || currentRelativeY
                != state.appliedRelativeY)
        {
            state.nativeRelativeY =
                    currentRelativeY;

            state.relativeYCaptured =
                    true;
        }

        state.appliedRelativeY =
                relativeY;

        widget.setRelativeY(
                relativeY);

        recordWidgetMutation();
        return true;
    }

    boolean setOriginalWidthIfChanged(
            Widget widget,
            int originalWidth)
    {
        if (widget == null
                || widget.getOriginalWidth() == originalWidth)
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final int currentOriginalWidth =
                widget.getOriginalWidth();

        if (!state.originalWidthCaptured
                || currentOriginalWidth
                != state.appliedOriginalWidth)
        {
            state.nativeOriginalWidth =
                    currentOriginalWidth;

            state.originalWidthCaptured =
                    true;
        }

        state.appliedOriginalWidth =
                originalWidth;

        widget.setOriginalWidth(
                originalWidth);

        recordWidgetMutation();
        return true;
    }

    private boolean setOriginalHeightIfChanged(
            Widget widget,
            int originalHeight)
    {
        if (widget == null
                || widget.getOriginalHeight() == originalHeight)
        {
            return false;
        }

        final NativeWidgetState state =
                nativeState(
                        widget);

        final int currentOriginalHeight =
                widget.getOriginalHeight();

        if (!state.originalHeightCaptured
                || currentOriginalHeight
                != state.appliedOriginalHeight)
        {
            state.nativeOriginalHeight =
                    currentOriginalHeight;

            state.originalHeightCaptured =
                    true;
        }

        state.appliedOriginalHeight =
                originalHeight;

        widget.setOriginalHeight(
                originalHeight);

        recordWidgetMutation();
        return true;
    }

    private NativeWidgetState nativeState(
            Widget widget)
    {
        return nativeWidgetStates.computeIfAbsent(
                widget,
                ignored -> new NativeWidgetState());
    }

    private boolean sameText(
            String left,
            String right)
    {
        return left == null
                ? right == null
                : left.equals(
                right);
    }

    private void recordWidgetMutation()
    {
        if (performanceMetrics != null)
        {
            performanceMetrics.recordWidgetMutation();
        }
    }

    void revalidateWidgetIfChanged(
            Widget widget,
            boolean changed)
    {
        if (widget == null
                || !changed)
        {
            return;
        }

        widget.revalidate();

        if (performanceMetrics != null)
        {
            performanceMetrics.recordRevalidate();
        }
    }

    /*
     * ================================================================
     * ROW CORRELATION
     * ================================================================
     */

    List<Widget> collectRow(
            Widget lineWidget,
            Surface surface)
    {
        if (lineWidget == null
                || surface == null)
        {
            return Collections.emptyList();
        }

        final RowCorrelationIndex rowIndex =
                rowIndexes.computeIfAbsent(
                        surface,
                        RowCorrelationIndex::new);

        final List<Widget> result =
                rowIndex.findRow(
                        lineWidget);

        // TODO: Count row-first correlation work during development.
        if (performanceMetrics != null)
        {
            performanceMetrics.recordRowSearches(
                    result.size());
        }

        return result;
    }

    /**
     * Persistent geometry index for one chat surface.
     *
     * The scan domain intentionally matches the former collectRow() exactly:
     * root + immediate dynamic/static/nested children, without recursion.
     *
     * Full surface builds are now only the correctness fallback. Normal
     * recycling is repaired locally by retaining both RowKey -> widgets and
     * Widget -> RowKey mappings. Widgets moved by Chat XL's delayed Y
     * correction are additionally watched for a return to their previous
     * native row so RuneScape can move them back without forcing a rebuild.
     */
    private final class RowCorrelationIndex
    {
        private final Surface surface;

        private Widget indexedRoot;

        private Map<RowKey, List<Widget>> widgetsByRow;

        private IdentityHashMap<Widget, RowKey> rowByWidget;

        private final IdentityHashMap<Widget, WatchedRowMove> watchedMoves =
                new IdentityHashMap<>();

        private final Map<RowKey, List<Widget>> watchedWidgetsByPreviousRow =
                new HashMap<>();

        private RowCorrelationIndex(
                Surface surface)
        {
            this.surface =
                    surface;
        }

        private List<Widget> findRow(
                Widget lineWidget)
        {
            if (lineWidget == null
                    || surface == null)
            {
                return Collections.emptyList();
            }

            final Widget root =
                    surface == Surface.SPLIT_PRIVATE
                            ? client.getWidget(
                            InterfaceID.PM_CHAT,
                            0)
                            : client.getWidget(
                            InterfaceID.Chatbox.SCROLLAREA);

            if (root == null)
            {
                clear();
                return Collections.emptyList();
            }

            final RowKey targetRow =
                    RowKey.of(
                            lineWidget);

            boolean rebuilt =
                    false;

            boolean repaired =
                    false;

            if (widgetsByRow == null
                    || rowByWidget == null
                    || indexedRoot != root)
            {
                build(
                        root);

                rebuilt =
                        true;
            }
            else
            {
                /*
                 * First repair any widget Chat XL deliberately moved away from
                 * this native row. RuneScape may have moved it back since the
                 * previous Script 72 finalization.
                 */
                repaired |=
                        repairWatchedWidgetsForRow(
                                targetRow);

                final RowKey indexedAnchorRow =
                        rowByWidget.get(
                                lineWidget);

                if (indexedAnchorRow == null)
                {
                    /*
                     * A new line-widget object appeared after the last index
                     * build. We cannot discover its sibling candidates without
                     * enumerating the surface once.
                     */
                    build(
                            root);

                    rebuilt =
                            true;
                }
                else if (!indexedAnchorRow.equals(
                        targetRow))
                {
                    /*
                     * RuneScape moved/recycled this already-known logical row.
                     * Repair only the old bucket; all siblings which moved with
                     * the anchor are re-keyed at the same time.
                     */
                    repaired |=
                            repairRow(
                                    indexedAnchorRow);
                }
            }

            if (!rebuilt)
            {
                /*
                 * Validate only the requested bucket. Outgoing recycled
                 * candidates are moved to their current row without touching
                 * unrelated rows or walking the complete surface.
                 */
                repaired |=
                        repairRow(
                                targetRow);
            }

            List<Widget> candidates =
                    widgetsByRow.get(
                            targetRow);

            /*
             * The exact line widget must be present in its requested bucket.
             * If not, local knowledge is insufficient and one complete build
             * remains the conservative correctness fallback.
             */
            if (!rebuilt
                    && !containsIdentity(
                    candidates,
                    lineWidget))
            {
                build(
                        root);

                rebuilt =
                        true;

                candidates =
                        widgetsByRow.get(
                                targetRow);
            }

            if (performanceMetrics != null)
            {
                if (rebuilt)
                {
                    performanceMetrics.recordRowIndexBuild();
                }
                else if (repaired)
                {
                    performanceMetrics.recordRowIndexRepair();
                }
                else
                {
                    performanceMetrics.recordRowIndexReuse();
                }
            }

            return candidates != null
                    ? candidates
                    : Collections.emptyList();
        }

        private boolean repairRow(
                RowKey indexedRow)
        {
            if (indexedRow == null
                    || widgetsByRow == null
                    || rowByWidget == null)
            {
                return false;
            }

            final List<Widget> indexedWidgets =
                    widgetsByRow.get(
                            indexedRow);

            if (indexedWidgets == null
                    || indexedWidgets.isEmpty())
            {
                return false;
            }

            final List<Widget> snapshot =
                    new ArrayList<>(
                            indexedWidgets);

            boolean repaired =
                    false;

            for (Widget widget : snapshot)
            {
                if (widget == null)
                {
                    continue;
                }

                recordRowWidgetExamined();

                final RowKey currentRow =
                        RowKey.of(
                                widget);

                final RowKey recordedRow =
                        rowByWidget.get(
                                widget);

                if (recordedRow == null)
                {
                    /*
                     * This should not occur for a healthy index. Keep the
                     * reverse mapping internally consistent without a scan.
                     */
                    rowByWidget.put(
                            widget,
                            currentRow);

                    if (!indexedRow.equals(
                            currentRow))
                    {
                        removeWidgetFromRow(
                                widget,
                                indexedRow);

                        addWidgetToRow(
                                widget,
                                currentRow);

                        repaired =
                                true;
                    }

                    continue;
                }

                if (!recordedRow.equals(
                        currentRow))
                {
                    moveIndexedWidget(
                            widget,
                            recordedRow,
                            currentRow);

                    reconcileWatchedMoveAfterExternalGeometryChange(
                            widget,
                            currentRow);

                    repaired =
                            true;
                }
            }

            return repaired;
        }

        private boolean repairWatchedWidgetsForRow(
                RowKey requestedRow)
        {
            if (requestedRow == null
                    || rowByWidget == null)
            {
                return false;
            }

            final List<Widget> watchedWidgets =
                    watchedWidgetsByPreviousRow.get(
                            requestedRow);

            if (watchedWidgets == null
                    || watchedWidgets.isEmpty())
            {
                return false;
            }

            final List<Widget> snapshot =
                    new ArrayList<>(
                            watchedWidgets);

            boolean repaired =
                    false;

            for (Widget widget : snapshot)
            {
                if (widget == null)
                {
                    continue;
                }

                final WatchedRowMove watchedMove =
                        watchedMoves.get(
                                widget);

                if (watchedMove == null
                        || !requestedRow.equals(
                        watchedMove.previousRow))
                {
                    removeWatchedMove(
                            widget);
                    continue;
                }

                final RowKey recordedRow =
                        rowByWidget.get(
                                widget);

                if (recordedRow == null)
                {
                    removeWatchedMove(
                            widget);
                    continue;
                }

                recordRowWidgetExamined();

                final RowKey currentRow =
                        RowKey.of(
                                widget);

                if (!recordedRow.equals(
                        currentRow))
                {
                    moveIndexedWidget(
                            widget,
                            recordedRow,
                            currentRow);

                    repaired =
                            true;
                }

                /*
                 * Keep watching only while the widget remains exactly at the
                 * Chat XL-adjusted row. Returning to the previous native row,
                 * or moving elsewhere because RuneScape recycled it, completes
                 * the watch.
                 */
                if (!watchedMove.adjustedRow.equals(
                        currentRow))
                {
                    removeWatchedMove(
                            widget);
                }
            }

            return repaired;
        }

        private void observeSurfaceWidget(
                Widget root,
                Widget widget)
        {
            if (root == null
                    || widget == null
                    || indexedRoot != root
                    || widgetsByRow == null
                    || rowByWidget == null)
            {
                return;
            }

            final RowKey currentRow =
                    RowKey.of(
                            widget);

            final RowKey recordedRow =
                    rowByWidget.get(
                            widget);

            if (recordedRow == null)
            {
                addWidgetToRow(
                        widget,
                        currentRow);

                rowByWidget.put(
                        widget,
                        currentRow);

                return;
            }

            if (!recordedRow.equals(
                    currentRow))
            {
                moveIndexedWidget(
                        widget,
                        recordedRow,
                        currentRow);

                reconcileWatchedMoveAfterExternalGeometryChange(
                        widget,
                        currentRow);
            }
        }

        private void onWidgetGeometryChangedByChatXl(
                Widget widget,
                RowKey previousRow)
        {
            if (widget == null
                    || previousRow == null
                    || rowByWidget == null)
            {
                return;
            }

            final RowKey recordedRow =
                    rowByWidget.get(
                            widget);

            if (recordedRow == null)
            {
                return;
            }

            final RowKey adjustedRow =
                    RowKey.of(
                            widget);

            if (!recordedRow.equals(
                    adjustedRow))
            {
                moveIndexedWidget(
                        widget,
                        recordedRow,
                        adjustedRow);
            }

            if (!previousRow.equals(
                    adjustedRow))
            {
                watchMove(
                        widget,
                        previousRow,
                        adjustedRow);
            }
        }

        private void moveIndexedWidget(
                Widget widget,
                RowKey oldRow,
                RowKey newRow)
        {
            if (widget == null
                    || newRow == null
                    || rowByWidget == null
                    || widgetsByRow == null)
            {
                return;
            }

            if (oldRow != null)
            {
                removeWidgetFromRow(
                        widget,
                        oldRow);
            }

            addWidgetToRow(
                    widget,
                    newRow);

            rowByWidget.put(
                    widget,
                    newRow);
        }

        private void addWidgetToRow(
                Widget widget,
                RowKey row)
        {
            if (widget == null
                    || row == null
                    || widgetsByRow == null)
            {
                return;
            }

            final List<Widget> widgets =
                    widgetsByRow.computeIfAbsent(
                            row,
                            ignored -> new ArrayList<>());

            if (!containsIdentity(
                    widgets,
                    widget))
            {
                widgets.add(
                        widget);
            }
        }

        private void removeWidgetFromRow(
                Widget widget,
                RowKey row)
        {
            if (widget == null
                    || row == null
                    || widgetsByRow == null)
            {
                return;
            }

            final List<Widget> widgets =
                    widgetsByRow.get(
                            row);

            if (widgets == null)
            {
                return;
            }

            removeIdentity(
                    widgets,
                    widget);

            if (widgets.isEmpty())
            {
                widgetsByRow.remove(
                        row);
            }
        }

        private void watchMove(
                Widget widget,
                RowKey previousRow,
                RowKey adjustedRow)
        {
            if (widget == null
                    || previousRow == null
                    || adjustedRow == null)
            {
                return;
            }

            removeWatchedMove(
                    widget);

            watchedMoves.put(
                    widget,
                    new WatchedRowMove(
                            previousRow,
                            adjustedRow));

            final List<Widget> watchedWidgets =
                    watchedWidgetsByPreviousRow.computeIfAbsent(
                            previousRow,
                            ignored -> new ArrayList<>());

            if (!containsIdentity(
                    watchedWidgets,
                    widget))
            {
                watchedWidgets.add(
                        widget);
            }
        }

        private void reconcileWatchedMoveAfterExternalGeometryChange(
                Widget widget,
                RowKey currentRow)
        {
            final WatchedRowMove watchedMove =
                    watchedMoves.get(
                            widget);

            if (watchedMove == null
                    || currentRow == null)
            {
                return;
            }

            if (!watchedMove.adjustedRow.equals(
                    currentRow))
            {
                removeWatchedMove(
                        widget);
            }
        }

        private void removeWatchedMove(
                Widget widget)
        {
            if (widget == null)
            {
                return;
            }

            final WatchedRowMove watchedMove =
                    watchedMoves.remove(
                            widget);

            if (watchedMove == null)
            {
                return;
            }

            final List<Widget> watchedWidgets =
                    watchedWidgetsByPreviousRow.get(
                            watchedMove.previousRow);

            if (watchedWidgets == null)
            {
                return;
            }

            removeIdentity(
                    watchedWidgets,
                    widget);

            if (watchedWidgets.isEmpty())
            {
                watchedWidgetsByPreviousRow.remove(
                        watchedMove.previousRow);
            }
        }

        private void build(
                Widget root)
        {
            indexedRoot =
                    root;

            widgetsByRow =
                    new HashMap<>();

            rowByWidget =
                    new IdentityHashMap<>();

            indexWidget(
                    root);

            indexWidgets(
                    root.getDynamicChildren());

            indexWidgets(
                    root.getStaticChildren());

            indexWidgets(
                    root.getNestedChildren());

            pruneWatchedMovesAfterBuild();
        }

        private void indexWidgets(
                Widget[] widgets)
        {
            if (widgets == null)
            {
                return;
            }

            for (Widget widget : widgets)
            {
                indexWidget(
                        widget);
            }
        }

        private void indexWidget(
                Widget widget)
        {
            if (widget == null)
            {
                return;
            }

            recordRowWidgetExamined();

            final RowKey row =
                    RowKey.of(
                            widget);

            addWidgetToRow(
                    widget,
                    row);

            rowByWidget.put(
                    widget,
                    row);
        }

        private void pruneWatchedMovesAfterBuild()
        {
            if (watchedMoves.isEmpty()
                    || rowByWidget == null)
            {
                return;
            }

            final List<Widget> watchedWidgets =
                    new ArrayList<>(
                            watchedMoves.keySet());

            for (Widget widget : watchedWidgets)
            {
                final WatchedRowMove watchedMove =
                        watchedMoves.get(
                                widget);

                final RowKey indexedRow =
                        rowByWidget.get(
                                widget);

                if (watchedMove == null
                        || indexedRow == null
                        || !watchedMove.adjustedRow.equals(
                        indexedRow))
                {
                    removeWatchedMove(
                            widget);
                }
            }
        }

        private void clear()
        {
            indexedRoot =
                    null;

            widgetsByRow =
                    null;

            rowByWidget =
                    null;

            watchedMoves.clear();
            watchedWidgetsByPreviousRow.clear();
        }
    }

    private void observeWidgetFromSurfaceScan(
            Surface surface,
            Widget root,
            Widget widget)
    {
        if (surface == null
                || root == null
                || widget == null)
        {
            return;
        }

        final RowCorrelationIndex rowIndex =
                rowIndexes.get(
                        surface);

        if (rowIndex != null)
        {
            rowIndex.observeSurfaceWidget(
                    root,
                    widget);
        }
    }

    private void notifyIndexedWidgetGeometryChanged(
            Widget widget,
            RowKey previousRow)
    {
        if (widget == null
                || previousRow == null
                || rowIndexes.isEmpty())
        {
            return;
        }

        for (RowCorrelationIndex rowIndex : rowIndexes.values())
        {
            if (rowIndex != null)
            {
                rowIndex.onWidgetGeometryChangedByChatXl(
                        widget,
                        previousRow);
            }
        }
    }

    private void recordRowWidgetExamined()
    {
        if (performanceMetrics != null)
        {
            performanceMetrics.recordWidgetsExamined(
                    1);
        }
    }

    private boolean containsIdentity(
            List<Widget> widgets,
            Widget target)
    {
        if (widgets == null
                || target == null)
        {
            return false;
        }

        for (Widget widget : widgets)
        {
            if (widget == target)
            {
                return true;
            }
        }

        return false;
    }

    private void removeIdentity(
            List<Widget> widgets,
            Widget target)
    {
        if (widgets == null
                || target == null)
        {
            return;
        }

        for (int i = widgets.size() - 1;
             i >= 0;
             i--)
        {
            if (widgets.get(
                    i) == target)
            {
                widgets.remove(
                        i);
            }
        }
    }

    private static final class WatchedRowMove
    {
        private final RowKey previousRow;

        private final RowKey adjustedRow;

        private WatchedRowMove(
                RowKey previousRow,
                RowKey adjustedRow)
        {
            this.previousRow =
                    previousRow;

            this.adjustedRow =
                    adjustedRow;
        }
    }

    private static final class RowKey
    {
        private final int originalY;

        private final int relativeY;

        private RowKey(
                int originalY,
                int relativeY)
        {
            this.originalY =
                    originalY;

            this.relativeY =
                    relativeY;
        }

        private static RowKey of(
                Widget widget)
        {
            return new RowKey(
                    widget.getOriginalY(),
                    widget.getRelativeY());
        }

        @Override
        public boolean equals(
                Object other)
        {
            if (this == other)
            {
                return true;
            }

            if (!(other instanceof RowKey))
            {
                return false;
            }

            final RowKey rowKey =
                    (RowKey) other;

            return originalY == rowKey.originalY
                    && relativeY == rowKey.relativeY;
        }

        @Override
        public int hashCode()
        {
            int result =
                    originalY;

            result =
                    31 * result
                            + relativeY;

            return result;
        }
    }

    private Widget matchRow(
            List<Widget> rowWidgets,
            String targetText,
            Widget excludedWidget)
    {
        if (rowWidgets == null
                || rowWidgets.isEmpty()
                || targetText == null)
        {
            return null;
        }

        Widget match =
                null;

        int matches =
                0;

        for (Widget widget : rowWidgets)
        {
            if (widget == null
                    || widget == excludedWidget)
            {
                continue;
            }

            final String semantic =
                    textNormalizer.normalizeSemantic(
                            widget.getText());

            if (semantic == null
                    || !semantic.equalsIgnoreCase(
                    targetText))
            {
                continue;
            }

            match =
                    widget;

            matches++;

            if (matches > 1)
            {
                return null;
            }
        }

        return matches == 1
                ? match
                : null;
    }

    /*
     * ================================================================
     * BODY CORRELATION
     * ================================================================
     */

    Widget findTargetWidgetForLine(
            String targetText,
            Widget lineWidget,
            Surface surface,
            List<Widget> rowWidgets,
            FallbackCorrelationContext fallbackContext)
    {
        if (targetText == null
                || lineWidget == null
                || surface == null
                || fallbackContext == null)
        {
            return null;
        }

        final Widget rowMatch =
                matchRow(
                        rowWidgets,
                        targetText,
                        null);

        if (rowMatch != null)
        {
            return rowMatch;
        }

        final List<Widget> matches =
                fallbackContext.findMatches(
                        targetText);

        if (matches.isEmpty())
        {
            return null;
        }

        /*
         * Strongest match:
         *
         * same OriginalY + same RelativeY.
         */
        for (Widget widget : matches)
        {
            if (widget.getOriginalY()
                    == lineWidget.getOriginalY()
                    && widget.getRelativeY()
                    == lineWidget.getRelativeY())
            {
                return widget;
            }
        }

        /*
         * Next strongest:
         *
         * unique OriginalY.
         */
        Widget originalYMatch =
                null;

        int originalYMatches =
                0;

        for (Widget widget : matches)
        {
            if (widget.getOriginalY()
                    == lineWidget.getOriginalY())
            {
                originalYMatch =
                        widget;

                originalYMatches++;
            }
        }

        if (originalYMatches == 1)
        {
            return originalYMatch;
        }

        /*
         * Next:
         *
         * unique RelativeY.
         */
        Widget relativeYMatch =
                null;

        int relativeYMatches =
                0;

        for (Widget widget : matches)
        {
            if (widget.getRelativeY()
                    == lineWidget.getRelativeY())
            {
                relativeYMatch =
                        widget;

                relativeYMatches++;
            }
        }

        if (relativeYMatches == 1)
        {
            return relativeYMatch;
        }

        /*
         * Final conservative fallback:
         *
         * exactly one semantic match on the entire surface.
         */
        return matches.size() == 1
                ? matches.get(0)
                : null;
    }

    /*
     * ================================================================
     * PREFIX CORRELATION
     * ================================================================
     */

    private List<Widget> findPrefixWidgetsForLine(
            List<String> rawPrefixComponents,
            Widget lineWidget,
            Surface surface,
            Widget bodyWidget,
            List<Widget> rowWidgets,
            FallbackCorrelationContext fallbackContext)
    {
        if (rawPrefixComponents == null
                || rawPrefixComponents.isEmpty()
                || lineWidget == null
                || surface == null
                || bodyWidget == null
                || fallbackContext == null)
        {
            return Collections.emptyList();
        }

        final List<Widget> result =
                new ArrayList<>();

        for (String rawPrefix : rawPrefixComponents)
        {
            final String targetText =
                    textNormalizer.normalizeSemantic(
                            rawPrefix);

            if (targetText == null
                    || targetText.isEmpty())
            {
                continue;
            }

            final Widget rowMatch =
                    matchRow(
                            rowWidgets,
                            targetText,
                            bodyWidget);

            if (rowMatch != null
                    && !containsIdentity(
                    result,
                    rowMatch))
            {
                result.add(
                        rowMatch);

                continue;
            }

            final List<Widget> matches =
                    fallbackContext.findMatches(
                            targetText);

            if (matches.isEmpty())
            {
                continue;
            }

            Widget selected =
                    null;

            /*
             * Prefer the exact body row.
             */
            for (Widget candidate : matches)
            {
                if (candidate == bodyWidget
                        || containsIdentity(
                        result,
                        candidate))
                {
                    continue;
                }

                if (candidate.getOriginalY()
                        == bodyWidget.getOriginalY()
                        && candidate.getRelativeY()
                        == bodyWidget.getRelativeY())
                {
                    if (selected != null)
                    {
                        selected =
                                null;
                        break;
                    }

                    selected =
                            candidate;
                }
            }

            if (selected == null)
            {
                /*
                 * Then prefer a unique OriginalY match.
                 */
                int originalYMatches =
                        0;

                for (Widget candidate : matches)
                {
                    if (candidate == bodyWidget
                            || containsIdentity(
                            result,
                            candidate))
                    {
                        continue;
                    }

                    if (candidate.getOriginalY()
                            == bodyWidget.getOriginalY())
                    {
                        selected =
                                candidate;

                        originalYMatches++;
                    }
                }

                if (originalYMatches != 1)
                {
                    selected =
                            null;
                }
            }

            if (selected == null)
            {
                /*
                 * Then prefer a unique RelativeY match.
                 */
                int relativeYMatches =
                        0;

                for (Widget candidate : matches)
                {
                    if (candidate == bodyWidget
                            || containsIdentity(
                            result,
                            candidate))
                    {
                        continue;
                    }

                    if (candidate.getRelativeY()
                            == bodyWidget.getRelativeY())
                    {
                        selected =
                                candidate;

                        relativeYMatches++;
                    }
                }

                if (relativeYMatches != 1)
                {
                    selected =
                            null;
                }
            }

            if (selected == null)
            {
                /*
                 * Final conservative fallback:
                 *
                 * exactly one remaining semantic match.
                 */
                Widget onlyRemaining =
                        null;

                int remaining =
                        0;

                for (Widget candidate : matches)
                {
                    if (candidate == bodyWidget
                            || containsIdentity(
                            result,
                            candidate))
                    {
                        continue;
                    }

                    onlyRemaining =
                            candidate;

                    remaining++;
                }

                if (remaining == 1)
                {
                    selected =
                            onlyRemaining;
                }
            }

            if (selected != null
                    && !containsIdentity(
                    result,
                    selected))
            {
                result.add(
                        selected);
            }
        }

        return result;
    }

    /*
     * ================================================================
     * RANK ICON CORRELATION
     * ================================================================
     */

    Widget findRankIconWidget(
            FontMeasurementService.ChannelPrefixLayout nativeLayout,
            Widget rowAnchor,
            List<Widget> rowWidgets)
    {
        if (nativeLayout == null
                || rowAnchor == null
                || nativeLayout.rankIconSpriteId < 0)
        {
            return null;
        }

        if (performanceMetrics != null)
        {
            performanceMetrics.recordRankSearch();
        }

        /*
         * First inspect the already-collected row.
         *
         * This remains the common path. The diagnostic counters below classify
         * why this strict row-first lookup failed without changing its matching
         * criteria.
         */
        final Widget rowMatch =
                findRankIconWidgetInRow(
                        nativeLayout,
                        rowAnchor,
                        rowWidgets);

        if (rowMatch != null)
        {
            return rowMatch;
        }

        final Widget root =
                client.getWidget(
                        InterfaceID.Chatbox.SCROLLAREA);

        if (root == null)
        {
            if (performanceMetrics != null)
            {
                performanceMetrics.recordRankFallback();
                performanceMetrics.recordRankFallbackMiss();
            }

            return null;
        }

        /*
         * The persistent row index intentionally avoids enumerating the complete
         * chatbox tree on every row lookup. RuneScape can, however, replace a
         * shallow rank-sprite Widget while the already-indexed text widgets remain
         * stable enough for direct row reuse.
         *
         * On a strict rank miss, refresh only the same shallow domain owned by
         * RowCorrelationIndex: root + immediate dynamic/static/nested children.
         * This preserves the normal fast path while discovering a newly-created
         * shallow rank widget without paying for a recursive tree traversal.
         */
        final Widget shallowMatch =
                findRankIconWidgetShallow(
                        root,
                        nativeLayout.rankIconSpriteId,
                        nativeLayout.rankIconX,
                        rowAnchor.getOriginalY(),
                        rowAnchor.getRelativeY());

        if (shallowMatch != null)
        {
            observeWidgetFromSurfaceScan(
                    Surface.CHATBOX,
                    root,
                    shallowMatch);

            return shallowMatch;
        }

        /*
         * Preserve the recursive tree search as the final correctness fallback.
         * RankFallback therefore continues to mean that the full-tree path was
         * actually required.
         */
        if (performanceMetrics != null)
        {
            performanceMetrics.recordRankFallback();
        }

        final Widget fallbackMatch =
                findRankIconWidgetRecursive(
                        root,
                        nativeLayout.rankIconSpriteId,
                        nativeLayout.rankIconX,
                        rowAnchor.getOriginalY(),
                        rowAnchor.getRelativeY());

        if (performanceMetrics != null)
        {
            if (fallbackMatch != null)
            {
                performanceMetrics.recordRankFallbackHit();
            }
            else
            {
                performanceMetrics.recordRankFallbackMiss();
            }
        }

        /*
         * The strict row-first lookup missed because the rank sprite was not yet
         * known to the persistent surface index.
         *
         * Recursive fallback has now positively identified the exact sprite using
         * the same native sprite/X/Y criteria. Teach the existing row index about
         * that widget so subsequent constructions can resolve it through the cheap
         * row-first path.
         */
        if (fallbackMatch != null)
        {
            observeWidgetFromSurfaceScan(
                    Surface.CHATBOX,
                    root,
                    fallbackMatch);
        }

        return fallbackMatch;
    }

    private Widget findRankIconWidgetInRow(
            FontMeasurementService.ChannelPrefixLayout nativeLayout,
            Widget rowAnchor,
            List<Widget> rowWidgets)
    {
        boolean spriteMatched =
                false;

        boolean xMatched =
                false;

        if (rowWidgets == null
                || rowWidgets.isEmpty())
        {
            if (performanceMetrics != null)
            {
                performanceMetrics.recordRankRowNoSprite();
            }

            return null;
        }

        for (Widget widget : rowWidgets)
        {
            if (widget == null)
            {
                continue;
            }

            if (performanceMetrics != null)
            {
                performanceMetrics.recordRankNodesExamined(
                        1);
            }

            if (widget.getSpriteId()
                    != nativeLayout.rankIconSpriteId)
            {
                continue;
            }

            spriteMatched =
                    true;

            if (widget.getOriginalX()
                    != nativeLayout.rankIconX)
            {
                continue;
            }

            xMatched =
                    true;

            if (widget.getOriginalY()
                    != rowAnchor.getOriginalY()
                    && widget.getRelativeY()
                    != rowAnchor.getRelativeY())
            {
                continue;
            }

            return widget;
        }

        if (performanceMetrics != null)
        {
            if (!spriteMatched)
            {
                performanceMetrics.recordRankRowNoSprite();
            }
            else if (!xMatched)
            {
                performanceMetrics.recordRankRowXMismatch();
            }
            else
            {
                performanceMetrics.recordRankRowYMismatch();
            }
        }

        return null;
    }

    private Widget findRankIconWidgetShallow(
            Widget root,
            int spriteId,
            int originalX,
            int originalY,
            int relativeY)
    {
        if (root == null)
        {
            return null;
        }

        Widget match =
                findRankIconWidgetCandidate(
                        root,
                        spriteId,
                        originalX,
                        originalY,
                        relativeY);

        if (match != null)
        {
            return match;
        }

        match =
                findRankIconWidgetInShallowArray(
                        root.getDynamicChildren(),
                        spriteId,
                        originalX,
                        originalY,
                        relativeY);

        if (match != null)
        {
            return match;
        }

        match =
                findRankIconWidgetInShallowArray(
                        root.getStaticChildren(),
                        spriteId,
                        originalX,
                        originalY,
                        relativeY);

        if (match != null)
        {
            return match;
        }

        return findRankIconWidgetInShallowArray(
                root.getNestedChildren(),
                spriteId,
                originalX,
                originalY,
                relativeY);
    }

    private Widget findRankIconWidgetInShallowArray(
            Widget[] widgets,
            int spriteId,
            int originalX,
            int originalY,
            int relativeY)
    {
        if (widgets == null)
        {
            return null;
        }

        for (Widget widget : widgets)
        {
            final Widget match =
                    findRankIconWidgetCandidate(
                            widget,
                            spriteId,
                            originalX,
                            originalY,
                            relativeY);

            if (match != null)
            {
                return match;
            }
        }

        return null;
    }

    private Widget findRankIconWidgetCandidate(
            Widget widget,
            int spriteId,
            int originalX,
            int originalY,
            int relativeY)
    {
        if (widget == null)
        {
            return null;
        }

        if (performanceMetrics != null)
        {
            performanceMetrics.recordRankNodesExamined(
                    1);
        }

        return widget.getSpriteId()
                == spriteId
                && widget.getOriginalX()
                == originalX
                && (widget.getOriginalY()
                == originalY
                || widget.getRelativeY()
                == relativeY)
                ? widget
                : null;
    }

    private Widget findRankIconWidgetRecursive(
            Widget widget,
            int spriteId,
            int originalX,
            int originalY,
            int relativeY)
    {
        if (widget == null)
        {
            return null;
        }

        if (performanceMetrics != null)
        {
            performanceMetrics.recordRankNodesExamined(1);
        }

        if (widget.getSpriteId()
                == spriteId
                && widget.getOriginalX()
                == originalX
                && (widget.getOriginalY()
                == originalY
                || widget.getRelativeY()
                == relativeY))
        {
            return widget;
        }

        final Widget[] dynamicChildren =
                widget.getDynamicChildren();

        if (dynamicChildren != null)
        {
            for (Widget child : dynamicChildren)
            {
                final Widget result =
                        findRankIconWidgetRecursive(
                                child,
                                spriteId,
                                originalX,
                                originalY,
                                relativeY);

                if (result != null)
                {
                    return result;
                }
            }
        }

        final Widget[] staticChildren =
                widget.getStaticChildren();

        if (staticChildren != null)
        {
            for (Widget child : staticChildren)
            {
                final Widget result =
                        findRankIconWidgetRecursive(
                                child,
                                spriteId,
                                originalX,
                                originalY,
                                relativeY);

                if (result != null)
                {
                    return result;
                }
            }
        }

        final Widget[] nestedChildren =
                widget.getNestedChildren();

        if (nestedChildren != null)
        {
            for (Widget child : nestedChildren)
            {
                final Widget result =
                        findRankIconWidgetRecursive(
                                child,
                                spriteId,
                                originalX,
                                originalY,
                                relativeY);

                if (result != null)
                {
                    return result;
                }
            }
        }

        return null;
    }

    /*
     * ================================================================
     * FALLBACK CORRELATION
     * ================================================================
     */

    final class FallbackCorrelationContext
    {
        private final Surface surface;

        private Map<String, List<Widget>> widgetsBySemantic;

        FallbackCorrelationContext(
                Surface surface)
        {
            this.surface =
                    surface;
        }

        List<Widget> findMatches(
                String targetText)
        {
            if (targetText == null
                    || targetText.isEmpty()
                    || surface == null)
            {
                return Collections.emptyList();
            }

            if (performanceMetrics != null)
            {
                performanceMetrics.recordFallbackSearch();
            }

            if (widgetsBySemantic == null)
            {
                buildIndex();

                if (performanceMetrics != null)
                {
                    performanceMetrics.recordFallbackBuild();
                }
            }
            else if (performanceMetrics != null)
            {
                performanceMetrics.recordFallbackReuse();
            }

            final List<Widget> matches =
                    widgetsBySemantic.get(
                            semanticKey(
                                    targetText));

            return matches != null
                    ? matches
                    : Collections.emptyList();
        }

        private void buildIndex()
        {
            widgetsBySemantic =
                    new HashMap<>();

            final Widget root =
                    surface == Surface.SPLIT_PRIVATE
                            ? client.getWidget(
                            InterfaceID.PM_CHAT,
                            0)
                            : client.getWidget(
                            InterfaceID.Chatbox.SCROLLAREA);

            if (root == null)
            {
                return;
            }

            if (performanceMetrics != null)
            {
                performanceMetrics.recordSurfaceSearch();
            }

            indexWidget(
                    root,
                    root);

            indexWidgets(
                    root,
                    root.getDynamicChildren());

            indexWidgets(
                    root,
                    root.getStaticChildren());

            indexWidgets(
                    root,
                    root.getNestedChildren());
        }

        private void indexWidgets(
                Widget root,
                Widget[] widgets)
        {
            if (widgets == null)
            {
                return;
            }

            for (Widget widget : widgets)
            {
                indexWidget(
                        root,
                        widget);
            }
        }

        private void indexWidget(
                Widget root,
                Widget widget)
        {
            if (widget == null)
            {
                return;
            }

            if (performanceMetrics != null)
            {
                performanceMetrics.recordWidgetsExamined(
                        1);
            }

            /*
             * A complete fallback scan has already paid the enumeration cost.
             * Teach the persistent row index about every widget encountered,
             * including textless sprite widgets such as channel rank icons.
             */
            observeWidgetFromSurfaceScan(
                    surface,
                    root,
                    widget);

            final String semantic =
                    textNormalizer.normalizeSemantic(
                            widget.getText());

            if (semantic == null
                    || semantic.isEmpty())
            {
                return;
            }

            widgetsBySemantic
                    .computeIfAbsent(
                            semanticKey(
                                    semantic),
                            ignored -> new ArrayList<>())
                    .add(
                            widget);
        }

        private String semanticKey(
                String text)
        {
            return text.toLowerCase(
                    Locale.ROOT);
        }
    }

    /*
     * ================================================================
     * SURFACE
     * ================================================================
     */

    private Surface determineSurface(
            int scriptId,
            int parentWidgetId)
    {
        if (scriptId
                != FontMeasurementService.CHAT_BODY_SCRIPT)
        {
            return Surface.CHATBOX;
        }

        final Widget splitPrivate =
                client.getWidget(
                        InterfaceID.PM_CHAT,
                        0);

        if (splitPrivate != null
                && parentWidgetId
                == splitPrivate.getId())
        {
            return Surface.SPLIT_PRIVATE;
        }

        return Surface.CHATBOX;
    }

    /*
     * ================================================================
     * LIFECYCLE
     * ================================================================
     */

    @SuppressWarnings("deprecation")
    public void restoreNativePresentation()
    {
        if (nativeWidgetStates.isEmpty())
        {
            return;
        }

        for (Map.Entry<Widget, NativeWidgetState> entry
                : nativeWidgetStates.entrySet())
        {
            final Widget widget =
                    entry.getKey();

            final NativeWidgetState state =
                    entry.getValue();

            if (widget == null
                    || state == null)
            {
                continue;
            }

            boolean changed =
                    false;

            /*
             * Restore a field only when the widget still contains the exact value
             * last applied by Chat XL.
             *
             * If RuneScape has already rewritten that field, leave the newer
             * native value alone. This avoids restoring stale state when a Widget
             * object has been recycled for another logical chat row.
             */
            if (state.fontIdCaptured
                    && widget.getFontId()
                    == state.appliedFontId
                    && widget.getFontId()
                    != state.nativeFontId)
            {
                widget.setFontId(
                        state.nativeFontId);

                changed =
                        true;
            }

            if (state.lineHeightCaptured
                    && widget.getLineHeight()
                    == state.appliedLineHeight
                    && widget.getLineHeight()
                    != state.nativeLineHeight)
            {
                widget.setLineHeight(
                        state.nativeLineHeight);

                changed =
                        true;
            }

            if (state.textCaptured
                    && sameText(
                    widget.getText(),
                    state.appliedText)
                    && !sameText(
                    widget.getText(),
                    state.nativeText))
            {
                widget.setText(
                        state.nativeText);

                changed =
                        true;
            }

            if (state.originalXCaptured
                    && widget.getOriginalX()
                    == state.appliedOriginalX
                    && widget.getOriginalX()
                    != state.nativeOriginalX)
            {
                widget.setOriginalX(
                        state.nativeOriginalX);

                changed =
                        true;
            }

            if (state.originalYCaptured
                    && widget.getOriginalY()
                    == state.appliedOriginalY
                    && widget.getOriginalY()
                    != state.nativeOriginalY)
            {
                widget.setOriginalY(
                        state.nativeOriginalY);

                changed =
                        true;
            }

            if (state.relativeYCaptured
                    && widget.getRelativeY()
                    == state.appliedRelativeY
                    && widget.getRelativeY()
                    != state.nativeRelativeY)
            {
                widget.setRelativeY(
                        state.nativeRelativeY);

                changed =
                        true;
            }

            if (state.originalWidthCaptured
                    && widget.getOriginalWidth()
                    == state.appliedOriginalWidth
                    && widget.getOriginalWidth()
                    != state.nativeOriginalWidth)
            {
                widget.setOriginalWidth(
                        state.nativeOriginalWidth);

                changed =
                        true;
            }

            if (state.originalHeightCaptured
                    && widget.getOriginalHeight()
                    == state.appliedOriginalHeight
                    && widget.getOriginalHeight()
                    != state.nativeOriginalHeight)
            {
                widget.setOriginalHeight(
                        state.nativeOriginalHeight);

                changed =
                        true;
            }

            if (changed)
            {
                widget.revalidate();
            }
        }

        nativeWidgetStates.clear();
    }

    public void reset()
    {
        pending =
                null;

        pendingFontProfile =
                null;

        pendingYOffsets.clear();

        rowIndexes.clear();
    }

    /*
     * Exact native presentation values for one Widget identity.
     *
     * Each field is captured independently because Chat XL does not mutate every
     * property on every widget. The paired applied value lets restoration detect
     * whether RuneScape has rewritten/recycled the widget since Chat XL last
     * touched that field.
     */
    private static final class NativeWidgetState
    {
        private boolean fontIdCaptured;
        private int nativeFontId;
        private int appliedFontId;

        private boolean lineHeightCaptured;
        private int nativeLineHeight;
        private int appliedLineHeight;

        private boolean textCaptured;
        private String nativeText;
        private String appliedText;

        private boolean originalXCaptured;
        private int nativeOriginalX;
        private int appliedOriginalX;

        private boolean originalYCaptured;
        private int nativeOriginalY;
        private int appliedOriginalY;

        private boolean relativeYCaptured;
        private int nativeRelativeY;
        private int appliedRelativeY;

        private boolean originalWidthCaptured;
        private int nativeOriginalWidth;
        private int appliedOriginalWidth;

        private boolean originalHeightCaptured;
        private int nativeOriginalHeight;
        private int appliedOriginalHeight;
    }

    /*
     * Immutable configured font/profile pair.
     */
    private static final class ActiveFontState
    {
        private final ChatFont chatFont;

        private final ChatFontProfile fontProfile;

        private ActiveFontState(
                ChatFont chatFont,
                ChatFontProfile fontProfile)
        {
            this.chatFont =
                    chatFont;

            this.fontProfile =
                    fontProfile;
        }
    }

    /*
     * One delayed component-specific Y correction.
     *
     * Text corrections retain expected semantic text.
     * Sprite corrections retain an expected sprite ID.
     *
     * Exactly one guard is populated for each instance.
     */
    private static final class PendingYOffset
    {
        private final String expectedSemanticText;

        private final int expectedSpriteId;

        private final int yOffset;

        private PendingYOffset(
                String expectedSemanticText,
                int expectedSpriteId,
                int yOffset)
        {
            this.expectedSemanticText =
                    expectedSemanticText;

            this.expectedSpriteId =
                    expectedSpriteId;

            this.yOffset =
                    yOffset;
        }

        private static PendingYOffset forText(
                String expectedSemanticText,
                int yOffset)
        {
            return new PendingYOffset(
                    expectedSemanticText,
                    -1,
                    yOffset);
        }

        private static PendingYOffset forSprite(
                int expectedSpriteId,
                int yOffset)
        {
            return new PendingYOffset(
                    null,
                    expectedSpriteId,
                    yOffset);
        }

        private boolean matches(
                Widget widget,
                ChatTextNormalizer textNormalizer)
        {
            if (widget == null)
            {
                return false;
            }

            if (expectedSpriteId >= 0)
            {
                return widget.getSpriteId()
                        == expectedSpriteId;
            }

            if (expectedSemanticText == null)
            {
                return false;
            }

            final String currentSemanticText =
                    textNormalizer.normalizeSemantic(
                            widget.getText());

            return currentSemanticText != null
                    && currentSemanticText.equalsIgnoreCase(
                    expectedSemanticText);
        }
    }

    enum Surface
    {
        CHATBOX,
        SPLIT_PRIVATE
    }
}
