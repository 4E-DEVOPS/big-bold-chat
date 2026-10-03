package com.bigboldchat.chat;

import com.bigboldchat.Configurations;
import com.bigboldchat.config.ChatFont;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;

/**
 * Applies the selected font and geometry to the chat input widget.
 */
public final class InputFontService {
	private final Client client;
	private final Configurations config;
	private final BandState bandState = new BandState();

	private InputState inputState;

	public InputFontService(Client client, Configurations config) {
		this.client = client;
		this.config = config;
	}

	public void onScriptPostFired(ScriptPostFired event) {
		if (event == null) {
			return;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == ScriptID.CHAT_TEXT_INPUT_REBUILD || scriptId == ScriptID.CHAT_PROMPT_INIT
				|| scriptId == ScriptID.UPDATE_SCROLLBAR) {
			sync();
		}
	}

	public void sync() {
		final Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
		if (input == null) {
			return;
		}

		final Widget chatDisplay = client.getWidget(InterfaceID.Chatbox.CHATDISPLAY);
		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		final Widget scrollbar = client.getWidget(InterfaceID.Chatbox.CHATSCROLLBAR);
		final Widget separator = firstDynamicChild(chatDisplay);

		captureInput(input);
		captureBand(scrollArea, scrollbar, separator);

		final ChatFont configuredFont = config != null
				? config.inputFont()
				: null;
		final ChatFont selectedFont = configuredFont != null
				? configuredFont
				: ChatFont.PLAIN_12;
		final ChatFontProfile profile = ChatFontRegistry.get(selectedFont);
		final int targetHeight = Math.max(1, inputState.nativeHeight
				+ profile.getLineHeightAdjustment() + profile.getInputHeightAdjustment());
		final int targetY = inputState.nativeY + profile.getInputYOffset();
		final String targetText = FontGlyphCorrections.apply(selectedFont, inputState.nativeText);

		applyInput(input, selectedFont, targetHeight, targetY, targetText);

		final int inputTopDelta = inputTopDelta(inputState, targetHeight, targetY);
		applyBand(scrollArea, scrollbar, separator, inputTopDelta);
	}

	public void restoreNativePresentation() {
		final Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
		if (inputState != null && input == inputState.widget) {
			restoreInput(input);
		}

		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		final Widget scrollbar = client.getWidget(InterfaceID.Chatbox.CHATSCROLLBAR);
		final Widget separator = firstDynamicChild(client.getWidget(InterfaceID.Chatbox.CHATDISPLAY));
		restoreBand(scrollArea, scrollbar, separator);

		inputState = null;
		bandState.clear();
	}

	private void applyInput(Widget input, ChatFont selectedFont, int targetHeight, int targetY, String targetText) {
		boolean layoutChanged = false;

		if (input.getFontId() != selectedFont.getFontId()) {
			input.setFontId(selectedFont.getFontId());
			layoutChanged = true;
		}

		if (input.getLineHeight() != 0) {
			input.setLineHeight(0);
			layoutChanged = true;
		}

		if (input.getOriginalHeight() != targetHeight) {
			input.setOriginalHeight(targetHeight);
			layoutChanged = true;
		}

		if (input.getOriginalY() != targetY) {
			input.setOriginalY(targetY);
			layoutChanged = true;
		}

		if (targetText != null && !targetText.equals(input.getText())) {
			input.setText(targetText);
		}

		if (layoutChanged) {
			input.revalidate();
		}

		inputState.appliedFontId = selectedFont.getFontId();
		inputState.appliedLineHeight = 0;
		inputState.appliedHeight = targetHeight;
		inputState.appliedY = targetY;
		inputState.appliedText = targetText;
	}

	private void applyBand(Widget scrollArea, Widget scrollbar, Widget separator, int inputTopDelta) {
		if (scrollArea != null && bandState.scrollArea == scrollArea) {
			final int targetHeight = Math.max(0, bandState.nativeScrollHeight - inputTopDelta);
			if (scrollArea.getOriginalHeight() != targetHeight) {
				scrollArea.setOriginalHeight(targetHeight);
				scrollArea.revalidate();
			}
			bandState.appliedScrollHeight = targetHeight;
		}

		if (scrollbar != null && bandState.scrollbar == scrollbar) {
			final int targetHeight = Math.max(0, bandState.nativeScrollbarHeight - inputTopDelta);
			if (scrollbar.getOriginalHeight() != targetHeight) {
				scrollbar.setOriginalHeight(targetHeight);
			}
			revalidateScrollbar(scrollbar);
			bandState.appliedScrollbarHeight = targetHeight;
		}

		if (separator != null && bandState.separator == separator) {
			final int targetY = offsetOriginalY(bandState.nativeSeparatorY, bandState.separatorYMode, inputTopDelta);
			if (separator.getOriginalY() != targetY) {
				separator.setOriginalY(targetY);
				separator.revalidate();
			}
			bandState.appliedSeparatorY = targetY;
		}
	}

	private void restoreInput(Widget input) {
		boolean layoutChanged = false;

		if (input.getFontId() != inputState.nativeFontId) {
			input.setFontId(inputState.nativeFontId);
			layoutChanged = true;
		}
		if (input.getLineHeight() != inputState.nativeLineHeight) {
			input.setLineHeight(inputState.nativeLineHeight);
			layoutChanged = true;
		}
		if (input.getOriginalHeight() != inputState.nativeHeight) {
			input.setOriginalHeight(inputState.nativeHeight);
			layoutChanged = true;
		}
		if (input.getOriginalY() != inputState.nativeY) {
			input.setOriginalY(inputState.nativeY);
			layoutChanged = true;
		}
		if (inputState.nativeText != null && !inputState.nativeText.equals(input.getText())) {
			input.setText(inputState.nativeText);
		}
		if (layoutChanged) {
			input.revalidate();
		}
	}

	private void restoreBand(Widget scrollArea, Widget scrollbar, Widget separator) {
		if (scrollArea != null && scrollArea == bandState.scrollArea
				&& scrollArea.getOriginalHeight() != bandState.nativeScrollHeight) {
			scrollArea.setOriginalHeight(bandState.nativeScrollHeight);
			scrollArea.revalidate();
		}

		if (scrollbar != null && scrollbar == bandState.scrollbar) {
			if (scrollbar.getOriginalHeight() != bandState.nativeScrollbarHeight) {
				scrollbar.setOriginalHeight(bandState.nativeScrollbarHeight);
			}
			revalidateScrollbar(scrollbar);
		}

		if (separator != null && separator == bandState.separator
				&& separator.getOriginalY() != bandState.nativeSeparatorY) {
			separator.setOriginalY(bandState.nativeSeparatorY);
			separator.revalidate();
		}
	}

	private void captureInput(Widget input) {
		if (inputState == null || input != inputState.widget) {
			inputState = InputState.capture(input);
			return;
		}

		if (inputState.appliedFontId == null || input.getFontId() != inputState.appliedFontId) {
			inputState.nativeFontId = input.getFontId();
		}
		if (inputState.appliedLineHeight == null || input.getLineHeight() != inputState.appliedLineHeight) {
			inputState.nativeLineHeight = input.getLineHeight();
		}
		if (inputState.appliedHeight == null || input.getOriginalHeight() != inputState.appliedHeight) {
			inputState.nativeHeight = input.getOriginalHeight();
		}
		if (inputState.appliedY == null || input.getOriginalY() != inputState.appliedY) {
			inputState.nativeY = input.getOriginalY();
		}

		inputState.yPositionMode = input.getYPositionMode();

		final String currentText = input.getText();
		if (inputState.appliedText == null || !equals(currentText, inputState.appliedText)) {
			inputState.nativeText = currentText;
		}
	}

	private void captureBand(Widget scrollArea, Widget scrollbar, Widget separator) {
		if (scrollArea == null) {
			bandState.scrollArea = null;
			bandState.appliedScrollHeight = null;
		} else if (scrollArea != bandState.scrollArea) {
			bandState.scrollArea = scrollArea;
			bandState.nativeScrollHeight = scrollArea.getOriginalHeight();
			bandState.appliedScrollHeight = null;
		} else if (bandState.appliedScrollHeight == null
				|| scrollArea.getOriginalHeight() != bandState.appliedScrollHeight) {
			bandState.nativeScrollHeight = scrollArea.getOriginalHeight();
		}

		if (scrollbar == null) {
			bandState.scrollbar = null;
			bandState.appliedScrollbarHeight = null;
		} else if (scrollbar != bandState.scrollbar) {
			bandState.scrollbar = scrollbar;
			bandState.nativeScrollbarHeight = scrollbar.getOriginalHeight();
			bandState.appliedScrollbarHeight = null;
		} else if (bandState.appliedScrollbarHeight == null
				|| scrollbar.getOriginalHeight() != bandState.appliedScrollbarHeight) {
			bandState.nativeScrollbarHeight = scrollbar.getOriginalHeight();
		}

		if (separator == null) {
			bandState.separator = null;
			bandState.appliedSeparatorY = null;
		} else {
			if (separator != bandState.separator) {
				bandState.separator = separator;
				bandState.nativeSeparatorY = separator.getOriginalY();
				bandState.appliedSeparatorY = null;
			} else if (bandState.appliedSeparatorY == null
					|| separator.getOriginalY() != bandState.appliedSeparatorY) {
				bandState.nativeSeparatorY = separator.getOriginalY();
			}
			bandState.separatorYMode = separator.getYPositionMode();
		}
	}

	private static int inputTopDelta(InputState state, int targetHeight, int targetY) {
		final int heightDelta = targetHeight - state.nativeHeight;
		final int yDelta = targetY - state.nativeY;

		if (state.yPositionMode == WidgetPositionMode.ABSOLUTE_BOTTOM) {
			return -heightDelta - yDelta;
		}
		if (state.yPositionMode == WidgetPositionMode.ABSOLUTE_CENTER) {
			return -(heightDelta / 2) + yDelta;
		}
		return yDelta;
	}

	private static int offsetOriginalY(int originalY, int yPositionMode, int relativeDelta) {
		return yPositionMode == WidgetPositionMode.ABSOLUTE_BOTTOM
				? originalY - relativeDelta
				: originalY + relativeDelta;
	}

	private static void revalidateScrollbar(Widget scrollbar) {
		scrollbar.revalidate();
		scrollbar.revalidateScroll();

		final Widget[] children = scrollbar.getDynamicChildren();
		if (children == null) {
			return;
		}

		for (Widget child : children) {
			if (child != null) {
				child.revalidate();
			}
		}
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

	private static boolean equals(String first, String second) {
		return first == null
				? second == null
				: first.equals(second);
	}

	private static final class InputState {
		private Widget widget;
		private int nativeFontId;
		private int nativeLineHeight;
		private int nativeHeight;
		private int nativeY;
		private int yPositionMode;
		private String nativeText;
		private Integer appliedFontId;
		private Integer appliedLineHeight;
		private Integer appliedHeight;
		private Integer appliedY;
		private String appliedText;

		private static InputState capture(Widget input) {
			final InputState state = new InputState();
			state.widget = input;
			state.nativeFontId = input.getFontId();
			state.nativeLineHeight = input.getLineHeight();
			state.nativeHeight = input.getOriginalHeight();
			state.nativeY = input.getOriginalY();
			state.yPositionMode = input.getYPositionMode();
			state.nativeText = input.getText();
			return state;
		}
	}

	private static final class BandState {
		private Widget scrollArea;
		private Widget scrollbar;
		private Widget separator;
		private int nativeScrollHeight;
		private int nativeScrollbarHeight;
		private int nativeSeparatorY;
		private int separatorYMode;
		private Integer appliedScrollHeight;
		private Integer appliedScrollbarHeight;
		private Integer appliedSeparatorY;

		private void clear() {
			scrollArea = null;
			scrollbar = null;
			separator = null;
			nativeScrollHeight = 0;
			nativeScrollbarHeight = 0;
			nativeSeparatorY = 0;
			separatorYMode = 0;
			appliedScrollHeight = null;
			appliedScrollbarHeight = null;
			appliedSeparatorY = null;
		}
	}
}
