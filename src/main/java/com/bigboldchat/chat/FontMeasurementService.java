package com.bigboldchat.chat;

import com.bigboldchat.config.ChatFont;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.FontTypeFace;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Owns Chat XL text and geometry measurement.
 *
 * This service does NOT mutate chat construction or presentation widgets.
 *
 * It is responsible for determining:
 *
 *  - native and selected font metrics;
 *  - prefix / sender widths;
 *  - Clan / Guest Clan channel geometry;
 *  - rank-icon geometry;
 *  - body X / width;
 *  - native and selected wrapping;
 *  - selected line height;
 *  - required pre-construction row allocation;
 *  - per-font PRE row-Y adjustment.
 *
 * FontLayoutService consumes the resulting ConstructionMeasurement
 * and performs the actual PRE / POST mutations.
 */
public final class FontMeasurementService
{
    static final int GAME_BODY_SCRIPT = 199;
    static final int CHAT_BODY_SCRIPT = 203;
    static final int CHANNEL_BODY_SCRIPT = 4483;

    /*
     * Native RuneScape separation between:
     *
     *     prefix / username -> message body
     */
    private static final int BODY_GAP = 3;

    /*
     * Native Script 4483 rank-icon geometry:
     *
     *     channel title
     *     + 1px
     *     + icon
     *     + 1px
     *     + username
     */
    private static final int RANK_ICON_GAP = 1;

    private final Client client;

    public FontMeasurementService(
            Client client)
    {
        this.client =
                client;
    }

    boolean supportsScript(
            int scriptId)
    {
        return scriptId
                == GAME_BODY_SCRIPT
                || scriptId
                == CHAT_BODY_SCRIPT
                || scriptId
                == CHANNEL_BODY_SCRIPT;
    }

    ConstructionMeasurement measure(
            int scriptId,
            ChatFont selectedChatFont,
            ChatFontProfile fontProfile)
    {
        if (!supportsScript(
                scriptId)
                || selectedChatFont == null
                || fontProfile == null)
        {
            return null;
        }

        final Object[] objectStack =
                client.getObjectStack();

        final int objectStackSize =
                client.getObjectStackSize();

        final String rawBody =
                findBody(
                        objectStack,
                        objectStackSize);

        if (rawBody == null)
        {
            return null;
        }

        final String semanticBody =
                normalizeSemantic(
                        rawBody);

        if (semanticBody == null
                || semanticBody.isEmpty())
        {
            return null;
        }

        /*
         * FontID 1446 renders ':' incorrectly.
         *
         * Keep native text unchanged for correlation and replace ':' only in
         * the selected text that Chat XL measures and renders.
         */
        final boolean replaceMalformedColons =
                selectedChatFont
                        == ChatFont.VERDANA_13_BOLD;

        final String selectedRawBodyText =
                replaceMalformedColons
                        ? replaceVerdana13BoldColons(
                        rawBody)
                        : rawBody;

        final String selectedSemanticBody =
                replaceMalformedColons
                        ? replaceVerdana13BoldColons(
                        semanticBody)
                        : semanticBody;

        final int[] intStack =
                client.getIntStack();

        final int intStackSize =
                client.getIntStackSize();

        if (intStack == null
                || intStackSize < 11
                || intStackSize > intStack.length)
        {
            return null;
        }

        /*
         * Common trailing Script 199 / 203 / 4483 construction payload:
         *
         * [0]  row / order
         * [1]  line widget
         * [2]  parent widget
         * [3]  right boundary
         * [4]  left boundary
         * [5]  vertical / line-height construction input
         * [6]  row Y
         * [7]  unknown - preserve native
         * [8]  sender / prefix width
         * [9]  color
         * [10] shadow
         */
        final int rowValueIndex =
                intStackSize - 11;

        final int lineWidgetIndex =
                intStackSize - 10;

        final int parentWidgetIndex =
                intStackSize - 9;

        final int rightBoundaryIndex =
                intStackSize - 8;

        final int leftBoundaryIndex =
                intStackSize - 7;

        final int verticalValueIndex =
                intStackSize - 6;

        final int rowYIndex =
                intStackSize - 5;

        final int argument7Index =
                intStackSize - 4;

        final int senderWidthIndex =
                intStackSize - 3;

        final int colorIndex =
                intStackSize - 2;

        final int shadowIndex =
                intStackSize - 1;

        final int lineWidgetId =
                intStack[lineWidgetIndex];

        final int parentWidgetId =
                intStack[parentWidgetIndex];

        final int rightBoundary =
                intStack[rightBoundaryIndex];

        final int leftBoundary =
                intStack[leftBoundaryIndex];

        final int nativeLineHeight =
                intStack[verticalValueIndex];

        final int nativeRowY =
                intStack[rowYIndex];

        final int nativeArgument7 =
                intStack[argument7Index];

        final int nativeSenderWidth =
                intStack[senderWidthIndex];

        if (nativeLineHeight <= 0)
        {
            return null;
        }

        final Widget lineWidget =
                client.getWidget(
                        lineWidgetId);

        if (lineWidget == null)
        {
            return null;
        }

        final FontTypeFace nativeFont =
                resolveFont(
                        FontID.PLAIN_12);

        final FontTypeFace selectedFont =
                resolveFont(
                        selectedChatFont.getFontId());

        final ChatFontProfile nativeFontProfile =
                ChatFontRegistry.get(
                        ChatFont.PLAIN_12);

        if (nativeFont == null
                || selectedFont == null
                || nativeFontProfile == null)
        {
            return null;
        }

        final List<String> rawPrefixComponents =
                scriptId == GAME_BODY_SCRIPT
                        ? new ArrayList<>()
                        : findPrefixComponents(
                        objectStack,
                        objectStackSize,
                        semanticBody);

        /*
         * Script 199 is the ordinary prefix-less GAME / system-message
         * constructor.
         *
         * Script 203 requires a textual prefix.
         *
         * Script 4483 can legitimately provide empty title / sender
         * components for Clan / Guest Clan system messages.
         */
        if (scriptId == CHAT_BODY_SCRIPT
                && rawPrefixComponents.isEmpty())
        {
            return null;
        }

        final int lineX =
                lineWidget.getOriginalX();

        int nativePrefixWidth =
                0;

        int selectedPrefixLayoutWidth =
                0;

        ChannelPrefixLayout nativeChannelLayout =
                null;

        ChannelPrefixLayout selectedChannelLayout =
                null;

        int nativeBodyX;
        int selectedBodyX;

        /*
         * Script 203 keeps its native/raw prefix for correlation while the
         * selected presentation may use a separately spaced raw prefix.
         *
         * This is primarily used by Friends Chat, where the rank icon is inline
         * markup inside the same prefix widget as the channel name and username.
         */
        String selectedRawPrefixText =
                null;

        if (scriptId == GAME_BODY_SCRIPT)
        {
            /*
             * Script 199 constructs ordinary GAME / system rows with no
             * textual prefix. Diagnostic tracing confirmed that the body
             * occupies the full construction span:
             *
             *     leftBoundary -> rightBoundary
             *
             * Keep native and selected horizontal geometry identical. Only
             * wrapping, line height, row allocation, row Y, and FontID change.
             */
            nativeBodyX =
                    leftBoundary;

            selectedBodyX =
                    leftBoundary;
        }
        else if (scriptId == CHAT_BODY_SCRIPT)
        {
            final String rawPrefix =
                    rawPrefixComponents.get(
                            rawPrefixComponents.size() - 1);

            /*
             * Native RuneScape geometry must always be measured from the native
             * unmodified prefix.
             */
            nativePrefixWidth =
                    nativeFont.getTextWidth(
                            normalizeRawForMeasurement(
                                    rawPrefix));

            /*
             * Friends Chat renders:
             *     [channel] <img=rank>Username:
             *          inside one prefix widget.
             *
             * Preserve the original raw prefix for widget correlation, but build a
             * separate selected/rendered prefix containing the configured visual gap
             * between the final inline image and the username.
             */
            selectedRawPrefixText =
                    isFriendsChatPrefix(
                            rawPrefix)
                            ? applyInlineIconUsernameSpacing(
                            rawPrefix,
                            fontProfile.getFriendsChatPlayerIconSpacing(),
                            '\u00A0')
                            : rawPrefix;

            /*
             * Replace malformed FontID 1446 colons in the selected prefix only.
             */
            if (replaceMalformedColons)
            {
                selectedRawPrefixText =
                        replaceVerdana13BoldColons(
                                selectedRawPrefixText);
            }

            selectedPrefixLayoutWidth =
                    selectedFont.getTextWidth(
                            normalizeRawForMeasurement(
                                    selectedRawPrefixText));

            nativeBodyX =
                    lineX
                            + nativePrefixWidth
                            + BODY_GAP;

            selectedBodyX =
                    lineX
                            + selectedPrefixLayoutWidth
                            + BODY_GAP;
        }
        else
        {
            nativeChannelLayout =
                    measureChannelPrefixLayout(
                            nativeFont,
                            rawPrefixComponents,
                            intStack,
                            intStackSize,
                            lineX,
                            false,
                            0,
                            0,
                            nativeFontProfile.getAccountBuildIconPadding(),
                            0);

            selectedChannelLayout =
                    measureChannelPrefixLayout(
                            selectedFont,
                            rawPrefixComponents,
                            intStack,
                            intStackSize,
                            lineX,
                            replaceMalformedColons,
                            fontProfile.getRankIconRightAdjustment(),
                            fontProfile.getRankIconSizeAdjustment(),
                            fontProfile.getAccountBuildIconPadding(),
                            fontProfile.getChannelAccountBuildIconSpacing());

            if (nativeChannelLayout == null
                    || selectedChannelLayout == null)
            {
                return null;
            }

            nativeBodyX =
                    nativeChannelLayout.bodyX;

            selectedBodyX =
                    selectedChannelLayout.bodyX;
        }

        final int nativeBodyWidth =
                rightBoundary
                        - nativeBodyX;

        final int selectedBodyWidth =
                rightBoundary
                        - selectedBodyX;

        if (nativeBodyWidth <= 0
                || selectedBodyWidth <= 0)
        {
            return null;
        }

        final int nativeLines =
                calculateWrappedLineCount(
                        nativeFont,
                        semanticBody,
                        nativeBodyWidth);

        final int selectedLines =
                calculateWrappedLineCount(
                        selectedFont,
                        selectedSemanticBody,
                        selectedBodyWidth);

        if (nativeLines <= 0
                || selectedLines <= 0)
        {
            return null;
        }

        /*
         * Line-height allocation is measurement, not presentation.
         *
         * Each profile describes how much the native RuneScape cadence
         * needs to change for that font.
         */
        final int lineHeightAdjustment =
                fontProfile
                        .getLineHeightAdjustment();

        final int selectedLineHeight =
                Math.max(
                        1,
                        nativeLineHeight
                                + lineHeightAdjustment);

        final int rowYOffset =
                fontProfile
                        .getRowYOffset();

        /*
         * Split Private Chat uses a bottom-relative row-Y construction value.
         *
         * RuneScape / Resizable Chat already maintain the native relationship
         * between Split Private Chat and the current chatbox position.
         *
         * PRIVATE_CHAT_GAP therefore adds only the font-specific extra clearance
         * requested by the selected profile.
         *
         * Positive values increase the gap above the chatbox.
         * Negative values reduce the gap.
         */
        final int privateChatGap =
                isSplitPrivateChatConstruction(
                        scriptId,
                        parentWidgetId)
                        ? fontProfile.getPrivateChatGap()
                        : 0;

        final int selectedRowY =
                nativeRowY
                        + rowYOffset
                        + privateChatGap;

        /*
         * RuneScape initially wraps using its native Plain-12 geometry.
         *
         * Therefore the construction input must compensate for the number
         * of native wrapped lines while reserving enough space for the
         * selected font.
         */
        final int desiredHeight =
                selectedLines
                        * selectedLineHeight;

        final int injectedValue =
                ceilDiv(
                        desiredHeight,
                        nativeLines);

        if (injectedValue <= 0)
        {
            return null;
        }

        final int allocatedHeight =
                nativeLines
                        * injectedValue;

        final ConstructionMeasurement measurement =
                new ConstructionMeasurement();

        measurement.scriptId =
                scriptId;

        measurement.semanticBody =
                semanticBody;

        measurement.selectedRawBodyText =
                selectedRawBodyText;

        measurement.selectedChatFont =
                selectedChatFont;

        measurement.selectedFontId =
                selectedChatFont.getFontId();

        measurement.rawPrefixComponents =
                new ArrayList<>(
                        rawPrefixComponents);

        /*
         * Script 203 keeps the selected/rendered raw prefix separately from the
         * native raw components used for correlation.
         */
        measurement.selectedRawPrefixText =
                selectedRawPrefixText;

        measurement.rowValueIndex =
                rowValueIndex;

        measurement.verticalValueIndex =
                verticalValueIndex;

        measurement.rowYIndex =
                rowYIndex;

        measurement.argument7Index =
                argument7Index;

        measurement.senderWidthIndex =
                senderWidthIndex;

        measurement.lineWidgetId =
                lineWidgetId;

        measurement.parentWidgetId =
                parentWidgetId;

        measurement.leftBoundary =
                leftBoundary;

        measurement.rightBoundary =
                rightBoundary;

        measurement.lineX =
                lineX;

        measurement.nativeLineHeight =
                nativeLineHeight;

        measurement.lineHeightAdjustment =
                lineHeightAdjustment;

        measurement.selectedLineHeight =
                selectedLineHeight;

        measurement.nativeRowY =
                nativeRowY;

        measurement.rowYOffset =
                rowYOffset;

        measurement.selectedRowY =
                selectedRowY;

        measurement.channelTextYOffset =
                fontProfile
                        .getChannelTextYOffset();

        measurement.rankIconRightAdjustment =
                fontProfile
                        .getRankIconRightAdjustment();

        measurement.rankIconSizeAdjustment =
                fontProfile
                        .getRankIconSizeAdjustment();

        measurement.rankIconYOffset =
                fontProfile
                        .getChannelRankIconYOffset();

        measurement.accountBuildIconPadding =
                fontProfile
                        .getAccountBuildIconPadding();

        measurement.nativeArgument7 =
                nativeArgument7;

        measurement.nativeSenderWidth =
                nativeSenderWidth;

        measurement.nativeColor =
                intStack[colorIndex];

        measurement.nativeShadow =
                intStack[shadowIndex];

        measurement.nativePrefixWidth =
                nativePrefixWidth;

        measurement.selectedPrefixLayoutWidth =
                selectedPrefixLayoutWidth;

        measurement.nativeChannelLayout =
                nativeChannelLayout;

        measurement.selectedChannelLayout =
                selectedChannelLayout;

        measurement.nativeBodyX =
                nativeBodyX;

        measurement.nativeBodyWidth =
                nativeBodyWidth;

        measurement.selectedBodyX =
                selectedBodyX;

        measurement.selectedBodyWidth =
                selectedBodyWidth;

        measurement.nativeLines =
                nativeLines;

        measurement.selectedLines =
                selectedLines;

        measurement.desiredHeight =
                desiredHeight;

        measurement.injectedValue =
                injectedValue;

        measurement.allocatedHeight =
                allocatedHeight;

        return measurement;
    }

    /*
     * ================================================================
     * PRIVATE CHAT
     * ================================================================
     */
    private boolean isSplitPrivateChatConstruction(
            int scriptId,
            int parentWidgetId)
    {
        if (scriptId != CHAT_BODY_SCRIPT)
        {
            return false;
        }

        final Widget splitPrivate =
                client.getWidget(
                        InterfaceID.PM_CHAT,
                        0);

        return splitPrivate != null
                && parentWidgetId
                == splitPrivate.getId();
    }

    /*
     * ================================================================
     * CHANNEL / RANK-ICON MEASUREMENT
     * ================================================================
     */
    private ChannelPrefixLayout measureChannelPrefixLayout(
            FontTypeFace font,
            List<String> rawPrefixComponents,
            int[] intStack,
            int intStackSize,
            int lineX,
            boolean replaceMalformedColons,
            int rankIconRightAdjustment,
            int rankIconSizeAdjustment,
            int accountBuildIconPadding,
            int inlineIconUsernameSpacing)
    {
        if (font == null
                || rawPrefixComponents == null
                || intStack == null)
        {
            return null;
        }

        final ChannelPrefixLayout layout =
                new ChannelPrefixLayout();

        layout.titleX =
                lineX;

        final String rawTitleText =
                rawPrefixComponents.size() > 0
                        ? rawPrefixComponents.get(
                        0)
                        : "";

        layout.titleText =
                normalizeSemantic(
                        rawTitleText);

        layout.renderedTitleText =
                replaceMalformedColons
                        ? replaceVerdana13BoldColons(
                        rawTitleText)
                        : rawTitleText;

        final String rawSenderText =
                rawPrefixComponents.size() > 1
                        ? rawPrefixComponents.get(
                        1)
                        : "";

        /*
         * Preserve the native sender semantic for correlation.
         */
        layout.senderText =
                normalizeSemantic(
                        rawSenderText);

        /*
         * The rendered sender may contain additional visible spacing after the
         * final inline account/build icon.
         *
         * Example:
         *
         *     native:   <img=2>Username:
         *     selected: <img=2> Username:
         */
        layout.renderedSenderText =
                applyInlineIconUsernameSpacing(
                        rawSenderText,
                        inlineIconUsernameSpacing,
                        ' ');

        if (replaceMalformedColons)
        {
            layout.renderedSenderText =
                    replaceVerdana13BoldColons(
                            layout.renderedSenderText);
        }

        if (layout.titleText == null)
        {
            layout.titleText =
                    "";
        }

        if (layout.senderText == null)
        {
            layout.senderText =
                    "";
        }

        layout.hasTitle =
                !layout.titleText.isEmpty();

        layout.hasSender =
                !layout.senderText.isEmpty();

        if (layout.hasTitle)
        {
            layout.titleWidth =
                    font.getTextWidth(
                            normalizeRawForMeasurement(
                                    layout.renderedTitleText));
        }

        if (layout.hasSender)
        {
            layout.senderWidth =
                    font.getTextWidth(
                            normalizeRawForMeasurement(
                                    layout.renderedSenderText));
        }

        /*
         * Immediately before the common eleven-value 4483 payload,
         * RuneScape can provide:
         *
         *     rankIconSpriteId
         *     rankIconWidth
         *     rankIconHeight
         */
        final int commonPayloadStart =
                intStackSize - 11;

        if (layout.hasSender
                && commonPayloadStart >= 3)
        {
            final int spriteIdCandidate =
                    intStack[commonPayloadStart - 3];

            final int widthCandidate =
                    intStack[commonPayloadStart - 2];

            final int heightCandidate =
                    intStack[commonPayloadStart - 1];

            if (spriteIdCandidate >= 0
                    && widthCandidate > 0
                    && widthCandidate <= 32
                    && heightCandidate > 0
                    && heightCandidate <= 32)
            {
                layout.rankIconSpriteId =
                        spriteIdCandidate;

                layout.rankIconWidth =
                        Math.max(
                                1,
                                widthCandidate
                                        + rankIconSizeAdjustment);

                layout.rankIconHeight =
                        Math.max(
                                1,
                                heightCandidate
                                        + rankIconSizeAdjustment);
            }
        }

        /*
         * ACCOUNT_BUILD_ICON
         *
         * Account/build icons are inline image markup inside the username
         * widget, for example:
         *
         *     <img=2>Splamna:
         *     <img=3>Swole Milk:
         *
         * Their width is measured directly as part of the raw sender text.
         * This is intentionally independent from the separate channel rank
         * icon, whose sprite and dimensions are controlled separately.
         *
         * CHANNEL_ACCOUNT_BUILD_ICON_SPACING changes the visible gap between
         * the final inline icon and the username.
         *
         * ACCOUNT_BUILD_ICON_PADDING is different: it reserves additional
         * sender width after the complete sender measurement. It therefore
         * affects where the message body begins, not the visible icon-to-name
         * gap inside the sender widget.
         */
        if (layout.hasSender
                && hasAccountBuildIcon(
                rawSenderText))
        {
            layout.senderWidth +=
                    accountBuildIconPadding;
        }

        int cursor =
                lineX;

        if (layout.hasTitle)
        {
            cursor +=
                    layout.titleWidth;
        }

        if (layout.hasSender)
        {
            if (layout.rankIconWidth > 0)
            {
                /*
                 * Native:
                 *
                 * title -> 1px -> icon -> 1px -> sender
                 */
                if (layout.hasTitle)
                {
                    cursor +=
                            RANK_ICON_GAP;
                }

                layout.rankIconX =
                        cursor;

                cursor +=
                        layout.rankIconWidth;

                cursor +=
                        RANK_ICON_GAP;

                /*
                 * Per-font adjustment is additional space only AFTER
                 * the rank icon.
                 *
                 * The icon itself remains at its measured X.
                 */
                cursor +=
                        rankIconRightAdjustment;
            }
            else if (layout.hasTitle)
            {
                cursor +=
                        BODY_GAP;
            }

            layout.senderX =
                    cursor;

            cursor +=
                    layout.senderWidth;

            cursor +=
                    BODY_GAP;
        }
        else if (layout.hasTitle)
        {
            /*
             * Title-only Clan / Guest Clan system message.
             */
            cursor +=
                    BODY_GAP;
        }

        /*
         * If both title and sender are empty, the body naturally
         * begins at lineX.
         */
        layout.bodyX =
                cursor;

        return layout;
    }

    /*
     * ================================================================
     * VERDANA 13 BOLD CORRECTION
     * ================================================================
     */

    /*
     * FontID 1446 renders ':' incorrectly.
     *
     * This helper is used only for selected Verdana 13 Bold text.
     * Native text remains unchanged for widget correlation.
     */
    private String replaceVerdana13BoldColons(
            String text)
    {
        if (text == null
                || text.isEmpty())
        {
            return text;
        }

        return text.replace(
                ':',
                '-');
    }

    /*
     * ================================================================
     * FONT RESOLUTION
     * ================================================================
     */
    private FontTypeFace resolveFont(
            int fontId)
    {
        final Widget probe =
                client.getWidget(
                        InterfaceID.Chatbox.INPUT);

        if (probe == null)
        {
            return null;
        }

        final int originalFontId =
                probe.getFontId();

        try
        {
            probe.setFontId(
                    fontId);

            return probe.getFont();
        }
        finally
        {
            probe.setFontId(
                    originalFontId);
        }
    }

    /*
     * ================================================================
     * OBJECT-STACK EXTRACTION
     * ================================================================
     */
    private String findBody(
            Object[] stack,
            int size)
    {
        if (stack == null
                || size <= 0)
        {
            return null;
        }

        final int safeSize =
                Math.min(
                        size,
                        stack.length);

        for (int i = safeSize - 1;
             i >= 0;
             i--)
        {
            final Object value =
                    stack[i];

            if (!(value instanceof String))
            {
                continue;
            }

            final String raw =
                    (String) value;

            final String semantic =
                    normalizeSemantic(
                            raw);

            if (semantic == null
                    || semantic.isEmpty())
            {
                continue;
            }

            return raw;
        }

        return null;
    }

    private List<String> findPrefixComponents(
            Object[] stack,
            int size,
            String semanticBody)
    {
        final List<String> result =
                new ArrayList<>();

        if (stack == null
                || size <= 0
                || semanticBody == null)
        {
            return result;
        }

        final int safeSize =
                Math.min(
                        size,
                        stack.length);

        for (int i = 0;
             i < safeSize;
             i++)
        {
            final Object value =
                    stack[i];

            if (!(value instanceof String))
            {
                continue;
            }

            final String raw =
                    (String) value;

            final String semantic =
                    normalizeSemantic(
                            raw);

            if (semantic == null)
            {
                continue;
            }

            if (semantic.equalsIgnoreCase(
                    semanticBody))
            {
                break;
            }

            /*
             * Preserve empty prefix components.
             *
             * Script 4483 uses them to represent title-only and
             * prefix-less system rows.
             */
            result.add(
                    raw);
        }

        return result;
    }

    private boolean hasAccountBuildIcon(
            String rawSenderText)
    {
        if (rawSenderText == null
                || rawSenderText.isEmpty())
        {
            return false;
        }

        return rawSenderText
                .toLowerCase(
                        Locale.ROOT)
                .contains(
                        "<img=");
    }

    /*
     * Add visible spacing between the final inline image tag and the
     * username that follows it.
     *
     * The caller supplies the actual spacing character because Chat XL uses
     * different spacing characters for the two supported inline-icon cases:
     *
     *     Friends Chat player/rank icon:
     *         \u00A0  non-breaking space
     *
     *     Clan / Guest Clan account-build icon:
     *         \u0020  normal space
     *
     * If multiple inline images occur before the username, only the final
     * image receives the username gap:
     *
     *     <img=67><img=2>PlayerName:
     *
     * becomes:
     *
     *     <img=67><img=2> PlayerName:
     *
     * rather than inserting spacing between the consecutive icons.
     *
     * Existing whitespace immediately after the final image counts toward
     * the configured minimum, which keeps repeated processing idempotent.
     */
    private String applyInlineIconUsernameSpacing(
            String rawText,
            int spacing,
            char spacingCharacter)
    {
        if (rawText == null
                || rawText.isEmpty()
                || spacing <= 0)
        {
            return rawText;
        }

        final String lower =
                rawText.toLowerCase(
                        Locale.ROOT);

        final int imageStart =
                lower.lastIndexOf(
                        "<img=");

        if (imageStart < 0)
        {
            return rawText;
        }

        final int imageEnd =
                rawText.indexOf(
                        '>',
                        imageStart);

        if (imageEnd < 0
                || imageEnd
                >= rawText.length() - 1)
        {
            return rawText;
        }

        int existingSpacing =
                0;

        for (int i = imageEnd + 1;
             i < rawText.length();
             i++)
        {
            final char ch =
                    rawText.charAt(
                            i);

            if (ch == ' '
                    || ch == '\u00A0'
                    || ch == '\t')
            {
                existingSpacing++;

                continue;
            }

            break;
        }

        final int spacingToAdd =
                Math.max(
                        0,
                        spacing
                                - existingSpacing);

        if (spacingToAdd == 0)
        {
            return rawText;
        }

        final StringBuilder gap =
                new StringBuilder(
                        spacingToAdd);

        for (int i = 0;
             i < spacingToAdd;
             i++)
        {
            gap.append(
                    spacingCharacter);
        }

        return rawText.substring(
                0,
                imageEnd + 1)
                + gap
                + rawText.substring(
                imageEnd + 1);
    }

    /*
     * Script 203 is used by several chat surfaces.
     *
     * Friends Chat is identified by its bracketed combined prefix:
     *
     *     [Friends Chat] <img=rank>Username:
     */
    private boolean isFriendsChatPrefix(
            String rawPrefix)
    {
        final String semanticPrefix =
                normalizeSemantic(
                        rawPrefix);

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
     * ================================================================
     * TEXT NORMALIZATION
     * ================================================================
     */
    String normalizeSemantic(
            String text)
    {
        if (text == null)
        {
            return null;
        }

        /*
         * Preserve explicit RuneScape line breaks before removing the
         * remaining markup.
         */
        final String withLineBreaks =
                text.replaceAll(
                        "(?i)<br\\s*/?>",
                        "\n");

        return Text.removeTags(
                        withLineBreaks)
                .replace(
                        '\u00A0',
                        ' ')
                .trim();
    }

    private String normalizeRawForMeasurement(
            String text)
    {
        if (text == null)
        {
            return "";
        }

        return text
                .replace(
                        '\u00A0',
                        ' ');
    }

    /*
     * ================================================================
     * WRAPPING / HEIGHT
     * ================================================================
     */
    private int calculateWrappedLineCount(
            FontTypeFace font,
            String text,
            int maxWidth)
    {
        if (font == null
                || maxWidth <= 0
                || text == null
                || text.isEmpty())
        {
            return 1;
        }

        final String normalized =
                text
                        .replace(
                                "\r\n",
                                "\n")
                        .replace(
                                '\r',
                                '\n');

        final String[] explicitLines =
                normalized.split(
                        "\n",
                        -1);

        int totalLines =
                0;

        for (String explicitLine : explicitLines)
        {
            totalLines +=
                    calculateSingleParagraphLines(
                            font,
                            explicitLine,
                            maxWidth);
        }

        return Math.max(
                1,
                totalLines);
    }

    private int calculateSingleParagraphLines(
            FontTypeFace font,
            String text,
            int maxWidth)
    {
        if (text == null
                || text.isEmpty())
        {
            return 1;
        }

        if (font.getTextWidth(
                text) <= maxWidth)
        {
            return 1;
        }

        final String trimmed =
                text.trim();

        if (trimmed.isEmpty())
        {
            return 1;
        }

        final String[] words =
                trimmed.split(
                        "\\s+");

        int lines =
                1;

        String currentLine =
                "";

        for (String word : words)
        {
            if (word.isEmpty())
            {
                continue;
            }

            /*
             * Hard-break a token that is itself wider than the row.
             */
            if (font.getTextWidth(
                    word) > maxWidth)
            {
                if (!currentLine.isEmpty())
                {
                    lines++;

                    currentLine =
                            "";
                }

                final StringBuilder segment =
                        new StringBuilder();

                for (int i = 0;
                     i < word.length();
                     i++)
                {
                    final char ch =
                            word.charAt(
                                    i);

                    final String candidate =
                            segment.toString()
                                    + ch;

                    if (segment.length() > 0
                            && font.getTextWidth(
                            candidate) > maxWidth)
                    {
                        lines++;

                        segment.setLength(
                                0);
                    }

                    segment.append(
                            ch);
                }

                currentLine =
                        segment.toString();

                continue;
            }

            if (currentLine.isEmpty())
            {
                currentLine =
                        word;

                continue;
            }

            final String candidate =
                    currentLine
                            + " "
                            + word;

            if (font.getTextWidth(
                    candidate) <= maxWidth)
            {
                currentLine =
                        candidate;
            }
            else
            {
                lines++;

                currentLine =
                        word;
            }
        }

        return Math.max(
                1,
                lines);
    }

    private int ceilDiv(
            int numerator,
            int denominator)
    {
        if (denominator <= 0)
        {
            return numerator;
        }

        return (numerator
                + denominator
                - 1)
                / denominator;
    }

    /*
     * ================================================================
     * RESULT TYPES
     * ================================================================
     */
    static final class ChannelPrefixLayout
    {
        /*
         * Native semantic title used for correlation.
         */
        String titleText =
                "";

        /*
         * Exact title text rendered after correlation.
         */
        String renderedTitleText =
                "";

        /*
         * Native semantic sender used for correlation.
         */
        String senderText =
                "";

        /*
         * Exact selected raw sender markup rendered after correlation.
         */
        String renderedSenderText =
                "";

        boolean hasTitle;

        boolean hasSender;

        int titleX;

        int titleWidth;

        int rankIconSpriteId =
                -1;

        int rankIconX =
                -1;

        int rankIconWidth;

        int rankIconHeight;

        int senderX;

        int senderWidth;

        int bodyX;
    }

    static final class ConstructionMeasurement
    {
        int scriptId;

        /*
         * Native semantic body used for correlation.
         */
        String semanticBody;

        /*
         * Exact selected raw body rendered after correlation.
         */
        String selectedRawBodyText;

        ChatFont selectedChatFont;

        int selectedFontId;

        List<String> rawPrefixComponents;

        /*
         * Exact selected raw Script-203 prefix rendered in POST.
         *
         * The original rawPrefixComponents remain untouched for correlation.
         */
        String selectedRawPrefixText;

        int rowValueIndex;

        int verticalValueIndex;

        int rowYIndex;

        int argument7Index;

        int senderWidthIndex;

        int lineWidgetId;

        int parentWidgetId;

        int leftBoundary;

        int rightBoundary;

        int lineX;

        int nativeLineHeight;

        int lineHeightAdjustment;

        int selectedLineHeight;

        int nativeRowY;

        int rowYOffset;

        int selectedRowY;

        int channelTextYOffset;

        int rankIconRightAdjustment;

        int rankIconSizeAdjustment;

        int rankIconYOffset;

        int accountBuildIconPadding;

        int nativeArgument7;

        int nativeSenderWidth;

        int nativeColor;

        int nativeShadow;

        int nativePrefixWidth;

        int selectedPrefixLayoutWidth;

        ChannelPrefixLayout nativeChannelLayout;

        ChannelPrefixLayout selectedChannelLayout;

        int nativeBodyX;

        int nativeBodyWidth;

        int selectedBodyX;

        int selectedBodyWidth;

        int nativeLines;

        int selectedLines;

        int desiredHeight;

        int injectedValue;

        int allocatedHeight;
    }
}