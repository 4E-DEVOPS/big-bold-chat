package com.bigboldchat.chat;

import com.bigboldchat.Configurations;
import com.bigboldchat.config.ChatFont;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

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

    /*
     * One exact PRE -> POST construction pair.
     *
     * Nested scripts do not replace this state because only supported
     * construction scripts create a new measurement.
     */
    private FontMeasurementService.ConstructionMeasurement pending;

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

    public FontLayoutService(
            Client client,
            Configurations config,
            FontMeasurementService measurementService)
    {
        this.client =
                client;

        this.config =
                config;

        this.measurementService =
                measurementService;
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

        final ChatFont configuredFont =
                config.chatFont();

        final ChatFont selectedChatFont =
                configuredFont != null
                        ? configuredFont
                        : ChatFont.PLAIN_12;

        final ChatFontProfile fontProfile =
                ChatFontRegistry.get(
                        selectedChatFont);

        if (fontProfile == null)
        {
            return;
        }

        final FontMeasurementService.ConstructionMeasurement measurement =
                measurementService.measure(
                        scriptId,
                        selectedChatFont,
                        fontProfile);

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

        pending =
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

        final Widget bodyWidget =
                findTargetWidgetForLine(
                        state.semanticBody,
                        lineWidget,
                        surface);

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
                        bodyWidget);

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
                            rowAnchor);
        }
        else
        {
            rankIconWidget =
                    null;
        }

        final ChatFontProfile fontProfile =
                getConfiguredFontProfile();

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

        bodyWidget.setFontId(
                state.selectedFontId);

        bodyWidget.setLineHeight(
                state.selectedLineHeight);

        /*
         * Correlation has already completed against the native body text.
         * Apply the exact selected text measured by MeasurementService.
         */
        if (state.selectedRawBodyText != null
                && !state.selectedRawBodyText.equals(
                bodyWidget.getText()))
        {
            bodyWidget.setText(
                    state.selectedRawBodyText);
        }

        bodyWidget.setOriginalX(
                state.selectedBodyX);

        bodyWidget.setOriginalWidth(
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

        bodyWidget.revalidate();
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
                    measurementService.normalizeSemantic(
                            widget.getText());

            if (semantic == null
                    || semantic.isEmpty())
            {
                continue;
            }

            widget.setFontId(
                    state.selectedFontId);

            widget.setLineHeight(
                    state.selectedLineHeight);

            /*
             * Script 203 uses one textual prefix widget.
             */
            if (state.scriptId
                    == FontMeasurementService.CHAT_BODY_SCRIPT
                    && prefixWidgets.size()
                    == 1)
            {
                widget.setOriginalWidth(
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
                    widget.setText(
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
                    widget.setOriginalX(
                            channel.titleX);

                    widget.setOriginalWidth(
                            channel.titleWidth);

                    if (channel.renderedTitleText != null
                            && !channel.renderedTitleText.isEmpty()
                            && !channel.renderedTitleText.equals(
                            widget.getText()))
                    {
                        widget.setText(
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
                    widget.setOriginalX(
                            channel.senderX);

                    widget.setOriginalWidth(
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
                        widget.setText(
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
                widget.setOriginalHeight(
                        state.selectedLineHeight);
            }

            widget.revalidate();
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
        rankIconWidget.setOriginalX(
                state.selectedChannelLayout.rankIconX);

        rankIconWidget.setOriginalWidth(
                state.selectedChannelLayout.rankIconWidth);

        rankIconWidget.setOriginalHeight(
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

        rankIconWidget.revalidate();
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
                measurementService.normalizeSemantic(
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
                    measurementService))
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
     * Resolve the currently selected font profile for presentation-only
     * adjustments that are intentionally not owned by measurement geometry.
     */
    private ChatFontProfile getConfiguredFontProfile()
    {
        final ChatFont configuredFont =
                config.chatFont();

        final ChatFont selectedChatFont =
                configuredFont != null
                        ? configuredFont
                        : ChatFont.PLAIN_12;

        return ChatFontRegistry.get(
                selectedChatFont);
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
                measurementService.normalizeSemantic(
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
    private void applySynchronizedYOffset(
            Widget widget,
            int yOffset)
    {
        if (widget == null
                || yOffset == 0)
        {
            return;
        }

        final int adjustedY =
                widget.getOriginalY()
                        + yOffset;

        widget.setOriginalY(
                adjustedY);

        widget.setRelativeY(
                adjustedY);
    }

    /*
     * ================================================================
     * BODY CORRELATION
     * ================================================================
     */

    private Widget findTargetWidgetForLine(
            String targetText,
            Widget lineWidget,
            Surface surface)
    {
        if (targetText == null
                || lineWidget == null
                || surface == null)
        {
            return null;
        }

        final List<Widget> matches =
                findAllTargetWidgets(
                        targetText,
                        surface);

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

        return matches.size() == 1
                ? matches.get(
                0)
                : null;
    }

    private List<Widget> findAllTargetWidgets(
            String targetText,
            Surface surface)
    {
        final List<Widget> matches =
                new ArrayList<>();

        if (targetText == null
                || surface == null)
        {
            return matches;
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
            return matches;
        }

        collectTargetWidget(
                matches,
                root,
                targetText);

        collectTargetWidgets(
                matches,
                root.getDynamicChildren(),
                targetText);

        collectTargetWidgets(
                matches,
                root.getStaticChildren(),
                targetText);

        collectTargetWidgets(
                matches,
                root.getNestedChildren(),
                targetText);

        return matches;
    }

    private void collectTargetWidgets(
            List<Widget> matches,
            Widget[] children,
            String targetText)
    {
        if (matches == null
                || children == null
                || targetText == null)
        {
            return;
        }

        for (Widget widget : children)
        {
            collectTargetWidget(
                    matches,
                    widget,
                    targetText);
        }
    }

    private void collectTargetWidget(
            List<Widget> matches,
            Widget widget,
            String targetText)
    {
        if (matches == null
                || widget == null
                || targetText == null)
        {
            return;
        }

        final String semantic =
                measurementService.normalizeSemantic(
                        widget.getText());

        if (semantic == null
                || !semantic.equalsIgnoreCase(
                targetText))
        {
            return;
        }

        if (!matches.contains(
                widget))
        {
            matches.add(
                    widget);
        }
    }

    /*
     * ================================================================
     * PREFIX / USERNAME CORRELATION
     * ================================================================
     */

    private List<Widget> findPrefixWidgetsForLine(
            List<String> rawPrefixComponents,
            Widget lineWidget,
            Surface surface,
            Widget bodyWidget)
    {
        final List<Widget> result =
                new ArrayList<>();

        if (rawPrefixComponents == null
                || rawPrefixComponents.isEmpty()
                || lineWidget == null
                || surface == null)
        {
            return result;
        }

        final String lineSemantic =
                measurementService.normalizeSemantic(
                        lineWidget.getText());

        if (lineSemantic != null
                && matchesAnyPrefixComponent(
                lineSemantic,
                rawPrefixComponents))
        {
            result.add(
                    lineWidget);
        }

        for (String rawComponent : rawPrefixComponents)
        {
            final String semanticComponent =
                    measurementService.normalizeSemantic(
                            rawComponent);

            if (semanticComponent == null
                    || semanticComponent.isEmpty())
            {
                continue;
            }

            if (containsSemanticWidget(
                    result,
                    semanticComponent))
            {
                continue;
            }

            final Widget match =
                    findWidgetOnRow(
                            semanticComponent,
                            lineWidget,
                            surface,
                            bodyWidget);

            if (match != null
                    && !result.contains(
                    match))
            {
                result.add(
                        match);
            }
        }

        return result;
    }

    private Widget findWidgetOnRow(
            String targetText,
            Widget lineWidget,
            Surface surface,
            Widget excludedBodyWidget)
    {
        final List<Widget> matches =
                findAllTargetWidgets(
                        targetText,
                        surface);

        if (matches.isEmpty())
        {
            return null;
        }

        for (Widget widget : matches)
        {
            if (widget == excludedBodyWidget)
            {
                continue;
            }

            if (widget.getOriginalY()
                    == lineWidget.getOriginalY()
                    && widget.getRelativeY()
                    == lineWidget.getRelativeY())
            {
                return widget;
            }
        }

        Widget unique =
                null;

        int count =
                0;

        for (Widget widget : matches)
        {
            if (widget == excludedBodyWidget)
            {
                continue;
            }

            if (widget.getOriginalY()
                    == lineWidget.getOriginalY()
                    || widget.getRelativeY()
                    == lineWidget.getRelativeY())
            {
                unique =
                        widget;

                count++;
            }
        }

        return count == 1
                ? unique
                : null;
    }

    private boolean matchesAnyPrefixComponent(
            String semanticText,
            List<String> rawPrefixComponents)
    {
        if (semanticText == null
                || rawPrefixComponents == null)
        {
            return false;
        }

        for (String rawComponent : rawPrefixComponents)
        {
            final String component =
                    measurementService.normalizeSemantic(
                            rawComponent);

            if (component != null
                    && !component.isEmpty()
                    && semanticText.equalsIgnoreCase(
                    component))
            {
                return true;
            }
        }

        return false;
    }

    private boolean containsSemanticWidget(
            List<Widget> widgets,
            String semanticText)
    {
        if (widgets == null
                || semanticText == null)
        {
            return false;
        }

        for (Widget widget : widgets)
        {
            if (widget == null)
            {
                continue;
            }

            final String widgetText =
                    measurementService.normalizeSemantic(
                            widget.getText());

            if (widgetText != null
                    && widgetText.equalsIgnoreCase(
                    semanticText))
            {
                return true;
            }
        }

        return false;
    }

    /*
     * ================================================================
     * RANK ICON CORRELATION
     * ================================================================
     */

    private Widget findRankIconWidget(
            FontMeasurementService.ChannelPrefixLayout nativeLayout,
            Widget rowAnchor)
    {
        if (nativeLayout == null
                || nativeLayout.rankIconSpriteId < 0
                || nativeLayout.rankIconX < 0
                || rowAnchor == null)
        {
            return null;
        }

        final Widget root =
                client.getWidget(
                        InterfaceID.Chatbox.SCROLLAREA);

        if (root == null)
        {
            return null;
        }

        return findRankIconWidget(
                root,
                nativeLayout,
                rowAnchor,
                new IdentityHashMap<>());
    }

    private Widget findRankIconWidget(
            Widget widget,
            FontMeasurementService.ChannelPrefixLayout nativeLayout,
            Widget rowAnchor,
            IdentityHashMap<Widget, Boolean> visited)
    {
        if (widget == null
                || nativeLayout == null
                || rowAnchor == null
                || visited.containsKey(
                widget))
        {
            return null;
        }

        visited.put(
                widget,
                Boolean.TRUE);

        /*
         * Strict native-row matching is important.
         *
         * Chat widgets are aggressively recycled, so approximate
         * canvas-Y matching can accidentally move another visible row's
         * rank icon.
         */
        if (widget.getSpriteId()
                == nativeLayout.rankIconSpriteId
                && widget.getOriginalX()
                == nativeLayout.rankIconX
                && (widget.getOriginalY()
                == rowAnchor.getOriginalY()
                || widget.getRelativeY()
                == rowAnchor.getRelativeY()))
        {
            return widget;
        }

        Widget match =
                findRankIconWidget(
                        widget.getDynamicChildren(),
                        nativeLayout,
                        rowAnchor,
                        visited);

        if (match != null)
        {
            return match;
        }

        match =
                findRankIconWidget(
                        widget.getStaticChildren(),
                        nativeLayout,
                        rowAnchor,
                        visited);

        if (match != null)
        {
            return match;
        }

        return findRankIconWidget(
                widget.getNestedChildren(),
                nativeLayout,
                rowAnchor,
                visited);
    }

    private Widget findRankIconWidget(
            Widget[] widgets,
            FontMeasurementService.ChannelPrefixLayout nativeLayout,
            Widget rowAnchor,
            IdentityHashMap<Widget, Boolean> visited)
    {
        if (widgets == null)
        {
            return null;
        }

        for (Widget widget : widgets)
        {
            final Widget match =
                    findRankIconWidget(
                            widget,
                            nativeLayout,
                            rowAnchor,
                            visited);

            if (match != null)
            {
                return match;
            }
        }

        return null;
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
                == FontMeasurementService.GAME_BODY_SCRIPT
                || scriptId
                == FontMeasurementService.CHANNEL_BODY_SCRIPT)
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

    public void reset()
    {
        pending =
                null;

        pendingYOffsets.clear();
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
                FontMeasurementService measurementService)
        {
            if (widget == null
                    || measurementService == null)
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
                    measurementService.normalizeSemantic(
                            widget.getText());

            return currentSemanticText != null
                    && currentSemanticText.equalsIgnoreCase(
                    expectedSemanticText);
        }
    }

    private enum Surface
    {
        CHATBOX,
        SPLIT_PRIVATE
    }
}