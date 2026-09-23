package com.bigboldchat.chat;

import com.bigboldchat.config.ChatFont;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.FontTypeFace;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;

/**
 * Measures chat text, wrapping, row allocation, and presentation geometry.
 *
 * Produces ConstructionMeasurement values consumed by FontLayoutService.
 * This service does not mutate widgets or script stacks.
 */
public final class FontMeasurementService {
	static final int GAME_BODY_SCRIPT = 199;
	static final int CHAT_BODY_SCRIPT = 203;
	static final int CHANNEL_BODY_SCRIPT = 4483;

	/*
	 * Native Script 4483 rank-icon geometry:
	 *      [channel] +1px [icon] +1px [username]:
	 */
	private static final int RANK_ICON_GAP = 1;

	/*
	 * Native OS separation between:
	 *      [username] -> [message body]
	 */
	private static final int BODY_GAP = 3;

	/*
	 * Separation between text and scrollbar.
	 */
	private static final int SCROLLBAR_PADDING = 3;

	private final Client client;
	private final ChatTextNormalizer textNormalizer;

	// Optional performance instrumentation; null when disabled.
	private final PerformanceMetrics performanceMetrics;

	/*
	 * Cache resolved fonts for the current plugin session.
	 * Failed resolutions are retried on the next request.
	 */
	private final Map<Integer, FontTypeFace> fontCache = new HashMap<>();

	public FontMeasurementService(Client client) {
		this(client, new ChatTextNormalizer(), null);
	}

	public FontMeasurementService(Client client, PerformanceMetrics performanceMetrics) {
		this(client, new ChatTextNormalizer(performanceMetrics), performanceMetrics);
	}

	public FontMeasurementService(
			Client client,
			ChatTextNormalizer textNormalizer,
			PerformanceMetrics performanceMetrics) {
		this.client = client;
		this.textNormalizer = textNormalizer != null
				? textNormalizer
				: new ChatTextNormalizer(performanceMetrics);
		this.performanceMetrics = performanceMetrics;
	}

	boolean supportsScript(int scriptId) {
		return scriptId == GAME_BODY_SCRIPT || scriptId == CHAT_BODY_SCRIPT || scriptId == CHANNEL_BODY_SCRIPT;
	}

	ConstructionMeasurement measure(int scriptId, ChatFont selectedChatFont, ChatFontProfile fontProfile) {
		if (!supportsScript(scriptId) || selectedChatFont == null || fontProfile == null) {
			return null;
		}

		final Object[] objectStack = client.getObjectStack();
		final int objectStackSize = client.getObjectStackSize();

		final int bodyIndex = findBodyIndex(objectStack, objectStackSize);
		if (bodyIndex < 0) {
			return null;
		}

		final String rawBody = (String) objectStack[bodyIndex];
		final String semanticBody = textNormalizer.normalizeSemantic(rawBody);
		if (semanticBody == null) {
			return null;
		}

		/*
		 * FontID 1446 renders ':' incorrectly.
		 *
		 * Keep native text unchanged for correlation and replace ':' only in
		 * the selected text that Chat XL measures and renders.
		 */
		final boolean replaceMalformedColons = selectedChatFont == ChatFont.VERDANA_13_BOLD;

		final String visibleRawBodyText = textNormalizer.renderText(rawBody);

		final String selectedRawBodyText = replaceMalformedColons
				? replaceVerdana13BoldColons(visibleRawBodyText)
				: visibleRawBodyText;

		// Preserve inline images while measuring wrapping.
		final String measurementBody = textNormalizer.measureSemantic(rawBody);
		final String selectedMeasurementBody = textNormalizer.measureSemantic(selectedRawBodyText);
		if (measurementBody == null || selectedMeasurementBody == null) {
			return null;
		}

		final int[] intStack = client.getIntStack();
		final int intStackSize = client.getIntStackSize();
		if (intStack == null || intStackSize < 11 || intStackSize > intStack.length) {
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
		final int rowValueIndex = intStackSize - 11;
		final int lineWidgetIndex = intStackSize - 10;
		final int parentWidgetIndex = intStackSize - 9;
		final int rightBoundaryIndex = intStackSize - 8;
		final int leftBoundaryIndex = intStackSize - 7;
		final int verticalValueIndex = intStackSize - 6;
		final int rowYIndex = intStackSize - 5;
		final int argument7Index = intStackSize - 4;
		final int senderWidthIndex = intStackSize - 3;
		final int colorIndex = intStackSize - 2;
		final int shadowIndex = intStackSize - 1;
		final int lineWidgetId = intStack[lineWidgetIndex];
		final int parentWidgetId = intStack[parentWidgetIndex];
		final int rightBoundary = intStack[rightBoundaryIndex];
		final int leftBoundary = intStack[leftBoundaryIndex];
		final int nativeLineHeight = intStack[verticalValueIndex];
		final int nativeRowY = intStack[rowYIndex];
		final int nativeArgument7 = intStack[argument7Index];
		final int nativeSenderWidth = intStack[senderWidthIndex];

		if (nativeLineHeight <= 0) {
			return null;
		}

		final Widget lineWidget = client.getWidget(lineWidgetId);
		if (lineWidget == null) {
			return null;
		}

		final FontTypeFace nativeFont = resolveFont(FontID.PLAIN_12);
		final FontTypeFace selectedFont = resolveFont(selectedChatFont.getFontId());
		final ChatFontProfile nativeFontProfile = ChatFontRegistry.get(ChatFont.PLAIN_12);
		if (nativeFont == null || selectedFont == null || nativeFontProfile == null) {
			return null;
		}

		final List<String> rawPrefixComponents = scriptId == GAME_BODY_SCRIPT
				? new ArrayList<>()
				: findPrefixComponents(objectStack, bodyIndex);

		/*
		 * Script 199 is the ordinary prefix-less GAME / system-message
		 * constructor.
		 *
		 * Script 203 requires a textual prefix.
		 *
		 * Script 4483 can legitimately provide empty title / sender
		 * components for Clan / Guest Clan system messages.
		 */
		if (scriptId == CHAT_BODY_SCRIPT && rawPrefixComponents.isEmpty()) {
			return null;
		}

		final int lineX = lineWidget.getOriginalX();

		int nativePrefixWidth = 0;
		int selectedPrefixLayoutWidth = 0;

		ChannelPrefixLayout nativeChannelLayout = null;
		ChannelPrefixLayout selectedChannelLayout = null;

		int nativeBodyX;
		int selectedBodyX;

		/*
		 * Script 203 keeps its native/raw prefix for correlation while the
		 * selected presentation may use a separately spaced raw prefix.
		 *
		 * This is primarily used by Friends Chat, where the rank icon is inline
		 * markup inside the same prefix widget as the channel name and username.
		 */
		String selectedRawPrefixText = null;

		if (scriptId == GAME_BODY_SCRIPT) {
			/*
			 * Script 199 is prefix-less and uses the full construction span.
			 * Preserve its native horizontal geometry.
			 */
			nativeBodyX = leftBoundary;
			selectedBodyX = leftBoundary;
		} else if (scriptId == CHAT_BODY_SCRIPT) {
			final String rawPrefix = rawPrefixComponents.get(rawPrefixComponents.size() - 1);

			// Measure native geometry from the unmodified prefix.
			nativePrefixWidth = nativeFont.getTextWidth(normalizeRawForMeasurement(rawPrefix));

			/*
			 * Keep the raw Friends Chat prefix for correlation and build a separate
			 * rendered prefix with configured inline-icon spacing.
			 */
			selectedRawPrefixText = isFriendsChatPrefix(rawPrefix)
					? applyInlineIconUsernameSpacing(rawPrefix, fontProfile.getFriendsChatPlayerIconSpacing(), '\u00A0')
					: rawPrefix;

			/*
			 * Replace malformed FontID 1446 colons in the selected prefix only.
			 */
			if (replaceMalformedColons) {
				selectedRawPrefixText = replaceVerdana13BoldColons(selectedRawPrefixText);
			}

			selectedPrefixLayoutWidth = selectedFont.getTextWidth(normalizeRawForMeasurement(selectedRawPrefixText));

			nativeBodyX = lineX + nativePrefixWidth + BODY_GAP;
			selectedBodyX = lineX + selectedPrefixLayoutWidth + BODY_GAP;
		} else {
			nativeChannelLayout = measureChannelPrefixLayout(
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

			selectedChannelLayout = measureChannelPrefixLayout(
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

			if (nativeChannelLayout == null || selectedChannelLayout == null) {
				return null;
			}

			nativeBodyX = nativeChannelLayout.bodyX;
			selectedBodyX = selectedChannelLayout.bodyX;
		}

		final boolean splitPrivate = isSplitPrivate(parentWidgetId);
		final int rightPadding = splitPrivate
				? 0
				: SCROLLBAR_PADDING;
		final int selectedRightBoundary = splitPrivate
				? splitPrivateRightBoundary(leftBoundary, rightBoundary)
				: rightBoundary;
		final int nativeBodyWidth = rightBoundary - nativeBodyX;
		final int selectedBodyWidth = selectedRightBoundary - selectedBodyX - rightPadding;
		if (nativeBodyWidth <= 0 || selectedBodyWidth <= 0) {
			return null;
		}

		final int nativeLines = calculateWrappedLineCount(nativeFont, measurementBody, nativeBodyWidth);
		final int selectedLines = calculateWrappedLineCount(selectedFont, selectedMeasurementBody, selectedBodyWidth);
		if (nativeLines <= 0 || selectedLines <= 0) {
			return null;
		}

		// Apply the selected profile's line-height adjustment.
		final int lineHeightAdjustment = fontProfile.getLineHeightAdjustment();
		final int selectedLineHeight = Math.max(1, nativeLineHeight + lineHeightAdjustment);

		final int rowYOffset = fontProfile.getRowYOffset();

		// Apply PRIVATE_CHAT_GAP to Split Private's bottom-relative row Y.
		final int privateChatGap = isSplitPrivate(parentWidgetId)
				? fontProfile.getPrivateChatGap()
				: 0;

		final int selectedRowY = nativeRowY + rowYOffset + privateChatGap;

		// Compensate construction height for native and selected wrapped-line counts.
		final int desiredHeight = selectedLines * selectedLineHeight;
		final int injectedValue = ceilDiv(desiredHeight, nativeLines);
		if (injectedValue <= 0) {
			return null;
		}

		final int allocatedHeight = nativeLines * injectedValue;
		final ConstructionMeasurement measurement = new ConstructionMeasurement();

		measurement.scriptId = scriptId;
		measurement.semanticBody = semanticBody;
		measurement.selectedRawBodyText = selectedRawBodyText;
		measurement.selectedChatFont = selectedChatFont;
		measurement.selectedFontId = selectedChatFont.getFontId();
		measurement.rawPrefixComponents = new ArrayList<>(rawPrefixComponents);
		// Script 203 keeps rendered raw prefix separately from native raw components used for correlation.
		measurement.selectedRawPrefixText = selectedRawPrefixText;
		measurement.rowValueIndex = rowValueIndex;
		measurement.verticalValueIndex = verticalValueIndex;
		measurement.rowYIndex = rowYIndex;
		measurement.argument7Index = argument7Index;
		measurement.senderWidthIndex = senderWidthIndex;
		measurement.lineWidgetId = lineWidgetId;
		measurement.parentWidgetId = parentWidgetId;
		measurement.leftBoundary = leftBoundary;
		measurement.rightBoundary = rightBoundary;
		measurement.lineX = lineX;
		measurement.nativeLineHeight = nativeLineHeight;
		measurement.lineHeightAdjustment = lineHeightAdjustment;
		measurement.selectedLineHeight = selectedLineHeight;
		measurement.nativeRowY = nativeRowY;
		measurement.rowYOffset = rowYOffset;
		measurement.selectedRowY = selectedRowY;
		measurement.channelTextYOffset = fontProfile.getChannelTextYOffset();
		measurement.rankIconRightAdjustment = fontProfile.getRankIconRightAdjustment();
		measurement.rankIconSizeAdjustment = fontProfile.getRankIconSizeAdjustment();
		measurement.rankIconYOffset = fontProfile.getChannelRankIconYOffset();
		measurement.accountBuildIconPadding = fontProfile.getAccountBuildIconPadding();
		measurement.nativeArgument7 = nativeArgument7;
		measurement.nativeSenderWidth = nativeSenderWidth;
		measurement.nativeColor = intStack[colorIndex];
		measurement.nativeShadow = intStack[shadowIndex];
		measurement.nativePrefixWidth = nativePrefixWidth;
		measurement.selectedPrefixLayoutWidth = selectedPrefixLayoutWidth;
		measurement.nativeChannelLayout = nativeChannelLayout;
		measurement.selectedChannelLayout = selectedChannelLayout;
		measurement.nativeBodyX = nativeBodyX;
		measurement.nativeBodyWidth = nativeBodyWidth;
		measurement.selectedBodyX = selectedBodyX;
		measurement.selectedBodyWidth = selectedBodyWidth;
		measurement.nativeLines = nativeLines;
		measurement.selectedLines = selectedLines;
		measurement.desiredHeight = desiredHeight;
		measurement.injectedValue = injectedValue;
		measurement.allocatedHeight = allocatedHeight;

		return measurement;
	}

	/*
	 * ================================================================
	 * PRIVATE CHAT
	 * ================================================================
	 */
	private boolean isSplitPrivate(int parentWidgetId) {
		return WidgetUtil.componentToInterface(parentWidgetId) == InterfaceID.PM_CHAT;
	}

	private int splitPrivateRightBoundary(int leftBoundary, int nativeRightBoundary) {
		final Widget pmHost = activeSplitPrivateHost();
		if (pmHost == null || pmHost.getWidth() <= 0) {
			return nativeRightBoundary;
		}

		/*
		 * REBUILDPMBOX continues to expose RuneScape's native construction
		 * boundary even when ChatXL has constrained the movable PM host. Measure
		 * selected wrapping against the host's effective width while preserving
		 * the native boundary above for native-line compensation and correlation.
		 */
		return leftBoundary + pmHost.getWidth();
	}

	private Widget activeSplitPrivateHost() {
		final int topLevel = client.getTopLevelInterfaceId();
		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH) {
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.PM_CONTAINER);
		}
		if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC) {
			return client.getWidget(InterfaceID.ToplevelPreEoc.PM_CONTAINER);
		}
		return null;
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
			int inlineIconUsernameSpacing) {
		if (font == null || rawPrefixComponents == null || intStack == null) {
			return null;
		}

		final ChannelPrefixLayout layout = new ChannelPrefixLayout();

		layout.titleX = lineX;

		final String rawTitleText = rawPrefixComponents.size() > 0
				? rawPrefixComponents.get(0)
				: "";

		layout.titleText = textNormalizer.normalizeSemantic(rawTitleText);

		layout.renderedTitleText = replaceMalformedColons
				? replaceVerdana13BoldColons(rawTitleText)
				: rawTitleText;

		final String rawSenderText = rawPrefixComponents.size() > 1
				? rawPrefixComponents.get(1)
				: "";

		/*
		 * Preserve the native sender semantic for correlation.
		 */
		layout.senderText = textNormalizer.normalizeSemantic(rawSenderText);

		/*
		 * The rendered sender may contain additional visible spacing after the
		 * final inline account/build icon.
		 *
		 * Example:
		 *
		 *     native:   <img=2>Username:
		 *     selected: <img=2> Username:
		 */
		layout.renderedSenderText = applyInlineIconUsernameSpacing(rawSenderText, inlineIconUsernameSpacing, ' ');

		if (replaceMalformedColons) {
			layout.renderedSenderText = replaceVerdana13BoldColons(layout.renderedSenderText);
		}

		if (layout.titleText == null) {
			layout.titleText = "";
		}

		if (layout.senderText == null) {
			layout.senderText = "";
		}

		layout.hasTitle = !layout.titleText.isEmpty();
		layout.hasSender = !layout.senderText.isEmpty();

		if (layout.hasTitle) {
			layout.titleWidth = font.getTextWidth(normalizeRawForMeasurement(layout.renderedTitleText));
		}

		if (layout.hasSender) {
			layout.senderWidth = font.getTextWidth(normalizeRawForMeasurement(layout.renderedSenderText));
		}

		/*
		 * Read optional rank sprite ID, width, and height
		 * immediately before the common Script-4483 payload.
		 */
		final int commonPayloadStart = intStackSize - 11;
		if (layout.hasSender && commonPayloadStart >= 3) {
			final int spriteIdCandidate = intStack[commonPayloadStart - 3];
			final int widthCandidate = intStack[commonPayloadStart - 2];
			final int heightCandidate = intStack[commonPayloadStart - 1];
			if (spriteIdCandidate >= 0
					&& widthCandidate > 0
					&& widthCandidate <= 32
					&& heightCandidate > 0
					&& heightCandidate <= 32) {
				layout.rankIconSpriteId = spriteIdCandidate;
				layout.rankIconWidth = Math.max(1, widthCandidate + rankIconSizeAdjustment);
				layout.rankIconHeight = Math.max(1, heightCandidate + rankIconSizeAdjustment);
			}
		}

		/*
		 * Inline account/build icons are measured as part of sender text.
		 *
		 * CHANNEL_ACCOUNT_BUILD_ICON_SPACING controls icon-to-name spacing.
		 * ACCOUNT_BUILD_ICON_PADDING reserves additional sender layout width.
		 */
		if (layout.hasSender && hasAccountBuildIcon(rawSenderText)) {
			layout.senderWidth += accountBuildIconPadding;
		}

		int cursor = lineX;

		if (layout.hasTitle) {
			cursor += layout.titleWidth;
		}

		if (layout.hasSender) {
			if (layout.rankIconWidth > 0) {
				/*
				 * Native:
				 *
				 * title -> 1px -> icon -> 1px -> sender
				 */
				if (layout.hasTitle) {
					cursor += RANK_ICON_GAP;
				}

				layout.rankIconX = cursor;
				cursor += layout.rankIconWidth;
				cursor += RANK_ICON_GAP;

				/*
				 * Enforce minimum spacing after the final inline image before the username.
				 *
				 * The caller supplies the spacing character. Existing whitespace counts
				 * toward the minimum, and consecutive inline images are not separated.
				 */
				cursor += rankIconRightAdjustment;
			} else if (layout.hasTitle) {
				cursor += BODY_GAP;
			}

			layout.senderX = cursor;
			cursor += layout.senderWidth;
			cursor += BODY_GAP;
		} else if (layout.hasTitle) {
			cursor += BODY_GAP; // Title-only Clan / Guest Clan system message.
		}

		layout.bodyX = cursor; // If both title and sender are empty, the body naturally begins at lineX.
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
	private String replaceVerdana13BoldColons(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		return text.replace(':', '-');
	}

	/*
	 * ================================================================
	 * FONT RESOLUTION
	 * ================================================================
	 */

	private FontTypeFace resolveFont(int fontId) {
		final FontTypeFace cachedFont = fontCache.get(fontId);
		if (cachedFont != null) {
			if (performanceMetrics != null) {
				performanceMetrics.recordFontCacheHit();
			}

			return cachedFont;
		}

		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime()
				: 0L;

		try {
			final Widget probe = client.getWidget(InterfaceID.Chatbox.INPUT);
			if (probe == null) {
				return null;
			}

			final int originalFontId = probe.getFontId();
			final FontTypeFace resolvedFont;

			try {
				probe.setFontId(fontId);

				resolvedFont = probe.getFont();
			} finally {
				probe.setFontId(originalFontId);
			}

			if (resolvedFont != null) {
				fontCache.put(fontId, resolvedFont);
			}

			return resolvedFont;
		} finally {
			if (performanceMetrics != null && performanceMetrics.isEnabled()) {
				performanceMetrics.recordFontCacheMiss(System.nanoTime() - started);
			}
		}
	}

	/*
	 * ================================================================
	 * OBJECT-STACK EXTRACTION
	 * ================================================================
	 */
	private int findBodyIndex(Object[] stack, int size) {
		if (stack == null || size <= 0) {
			return -1;
		}

		final int safeSize = Math.min(size, stack.length);
		for (int i = safeSize - 1; i >= 0; i--) {
			if (stack[i] instanceof String && !((String) stack[i]).isEmpty()) {
				return i;
			}
		}

		return -1;
	}

	private List<String> findPrefixComponents(Object[] stack, int bodyIndex) {
		final List<String> result = new ArrayList<>();
		if (stack == null || bodyIndex < 0) {
			return result;
		}

		final int safeSize = Math.min(bodyIndex, stack.length);
		for (int i = 0; i < safeSize; i++) {
			final Object value = stack[i];
			if (value instanceof String) {
				result.add((String) value);
			}
		}

		return result;
	}

	private boolean hasAccountBuildIcon(String rawSenderText) {
		if (rawSenderText == null || rawSenderText.isEmpty()) {
			return false;
		}

		return rawSenderText.toLowerCase(Locale.ROOT).contains("<img=");
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
	private String applyInlineIconUsernameSpacing(String rawText, int spacing, char spacingCharacter) {
		if (rawText == null || rawText.isEmpty() || spacing <= 0) {
			return rawText;
		}

		final String lower = rawText.toLowerCase(Locale.ROOT);
		final int imageStart = lower.lastIndexOf("<img=");
		if (imageStart < 0) {
			return rawText;
		}

		final int imageEnd = rawText.indexOf('>', imageStart);
		if (imageEnd < 0 || imageEnd >= rawText.length() - 1) {
			return rawText;
		}

		int existingSpacing = 0;
		for (int i = imageEnd + 1; i < rawText.length(); i++) {
			final char ch = rawText.charAt(i);
			if (ch == ' ' || ch == '\u00A0' || ch == '\t') {
				existingSpacing++;
				continue;
			}

			break;
		}

		final int spacingToAdd = Math.max(0, spacing - existingSpacing);
		if (spacingToAdd == 0) {
			return rawText;
		}

		final StringBuilder gap = new StringBuilder(spacingToAdd);
		for (int i = 0; i < spacingToAdd; i++) {
			gap.append(spacingCharacter);
		}

		return rawText.substring(0, imageEnd + 1) + gap + rawText.substring(imageEnd + 1);
	}

	/*
	 * Script 203 is used by several chat surfaces.
	 *
	 * Friends Chat is identified by its bracketed combined prefix:
	 *
	 *     [Friends Chat] <img=rank>Username:
	 */
	private boolean isFriendsChatPrefix(String rawPrefix) {
		final String semanticPrefix = textNormalizer.normalizeSemantic(rawPrefix);
		if (semanticPrefix == null || semanticPrefix.length() < 3 || semanticPrefix.charAt(0) != '[') {
			return false;
		}

		final int closingBracket = semanticPrefix.indexOf(']');
		return closingBracket > 0 && closingBracket < semanticPrefix.length() - 1;
	}

	/*
	 * ================================================================
	 * TEXT NORMALIZATION
	 * ================================================================
	 */

	private String normalizeRawForMeasurement(String text) {
		if (text == null) {
			return "";
		}

		return text.replace('\u00A0', ' ');
	}

	/*
	 * ================================================================
	 * WRAPPING / HEIGHT
	 * ================================================================
	 */
	private int calculateWrappedLineCount(FontTypeFace font, String text, int maxWidth) {
		if (font == null || maxWidth <= 0 || text == null || text.isEmpty()) {
			return 1;
		}

		final String normalized = text
				.replace("\r\n", "\n")
				.replace('\r', '\n');
		final String[] explicitLines = normalized.split("\n", -1);
		int totalLines = 0;

		for (String explicitLine : explicitLines) {
			totalLines += calculateSingleParagraphLines(font, explicitLine, maxWidth);
		}

		return Math.max(1, totalLines);
	}

	int calculateSingleParagraphLines(FontTypeFace font, String text, int maxWidth) {
		if (text == null || text.isEmpty()) {
			return 1;
		}

		if (font.getTextWidth(text) <= maxWidth) {
			return 1;
		}

		final String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return 1;
		}

		final String[] words = trimmed.split("\\s+");
		int lines = 1;
		String currentLine = "";

		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}

			/*
			 * Don't hard-wrap one uninterrupted token character by character.
			 * This applies uniformly to letters, digits, punctuation, and symbols.
			 */
			if (font.getTextWidth(word) > maxWidth) {
				if (!currentLine.isEmpty()) {
					lines++;
				}

				currentLine = word;
				continue;
			}

			if (currentLine.isEmpty()) {
				currentLine = word;
				continue;
			}

			final String candidate = currentLine + " " + word;
			if (font.getTextWidth(candidate) <= maxWidth) {
				currentLine = candidate;
			} else {
				lines++;
				currentLine = word;
			}
		}

		return Math.max(1, lines);
	}

	private int ceilDiv(int numerator, int denominator) {
		if (denominator <= 0) {
			return numerator;
		}

		return (numerator + denominator - 1) / denominator;
	}

	/*
	 * ================================================================
	 * RESULT TYPES
	 * ================================================================
	 */
	static final class ChannelPrefixLayout {
		String titleText = ""; // Native semantic title used for correlation.
		String renderedTitleText = ""; // Exact title text rendered after correlation.
		String senderText = ""; // Native semantic sender used for correlation.
		String renderedSenderText = ""; // Exact selected raw sender markup rendered after correlation.

		boolean hasTitle;
		boolean hasSender;

		int titleX;
		int titleWidth;
		int rankIconSpriteId = -1;
		int rankIconX = -1;
		int rankIconWidth;
		int rankIconHeight;
		int senderX;
		int senderWidth;
		int bodyX;
	}

	static final class ConstructionMeasurement {
		int scriptId;

		String semanticBody; // Native semantic body used for correlation.
		String selectedRawBodyText; // Exact selected raw body rendered after correlation.

		ChatFont selectedChatFont;
		int selectedFontId;

		List<String> rawPrefixComponents;
		String selectedRawPrefixText; // Exact selected raw Script-203 prefix rendered in POST.

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
