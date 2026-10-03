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

/**
 * Applies the selected font and geometry to the chat input widget.
 */
public final class InputFontService {
	private final Client client;
	private final Configurations config;

	private Widget trackedInput;
	private NativeInputState nativeInputState;
	private AppliedInputState appliedInputState;

	private Widget trackedScrollArea;
	private NativeScrollState nativeScrollState;
	private Integer appliedScrollReserve;

	public InputFontService(Client client, Configurations config) {
		this.client = client;
		this.config = config;
	}

	public void onScriptPostFired(ScriptPostFired event) {
		if (event == null) {
			return;
		}

		final int scriptId = event.getScriptId();
		if (scriptId == ScriptID.CHAT_TEXT_INPUT_REBUILD || scriptId == ScriptID.CHAT_PROMPT_INIT) {
			sync();
		}
	}

	public void sync() {
		final Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
		if (input == null) {
			return;
		}

		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		captureNativeInputState(input);
		captureNativeScrollState(scrollArea);

		final ChatFont configuredFont = config != null
				? config.inputFont()
				: null;
		final ChatFont selectedFont = configuredFont != null
				? configuredFont
				: ChatFont.PLAIN_12;
		final ChatFontProfile profile = ChatFontRegistry.get(selectedFont);

		final int targetHeight = Math.max(1, nativeInputState.originalHeight
				+ profile.getLineHeightAdjustment() + profile.getInputHeightAdjustment());
		final int targetY = nativeInputState.originalY + profile.getInputYOffset();
		final String targetText = FontGlyphCorrections.apply(selectedFont, nativeInputState.text);
		final int targetScrollReserve = nativeScrollState != null
				? Math.max(0, nativeScrollState.originalHeight + profile.getLineHeightAdjustment())
				: 0;

		boolean inputLayoutChanged = false;

		if (input.getFontId() != selectedFont.getFontId()) {
			input.setFontId(selectedFont.getFontId());
			inputLayoutChanged = true;
		}

		if (input.getLineHeight() != 0) {
			input.setLineHeight(0);
			inputLayoutChanged = true;
		}

		if (input.getOriginalHeight() != targetHeight) {
			input.setOriginalHeight(targetHeight);
			inputLayoutChanged = true;
		}

		if (input.getOriginalY() != targetY) {
			input.setOriginalY(targetY);
			inputLayoutChanged = true;
		}

		if (targetText != null && !targetText.equals(input.getText())) {
			input.setText(targetText);
		}

		if (inputLayoutChanged) {
			input.revalidate();
		}

		if (scrollArea != null && nativeScrollState != null && scrollArea.getOriginalHeight() != targetScrollReserve) {
			scrollArea.setOriginalHeight(targetScrollReserve);
			scrollArea.revalidate();

			final Widget scrollbar = client.getWidget(InterfaceID.Chatbox.CHATSCROLLBAR);
			if (scrollbar != null) {
				scrollbar.revalidate();
			}
		}

		appliedInputState = new AppliedInputState(selectedFont.getFontId(), 0, targetHeight, targetY, targetText);
		appliedScrollReserve = nativeScrollState != null
				? targetScrollReserve
				: null;
	}

	public void restoreNativePresentation() {
		final Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
		if (input != null && input == trackedInput && nativeInputState != null) {
			boolean layoutChanged = false;

			if (input.getFontId() != nativeInputState.fontId) {
				input.setFontId(nativeInputState.fontId);
				layoutChanged = true;
			}

			if (input.getLineHeight() != nativeInputState.lineHeight) {
				input.setLineHeight(nativeInputState.lineHeight);
				layoutChanged = true;
			}

			if (input.getOriginalHeight() != nativeInputState.originalHeight) {
				input.setOriginalHeight(nativeInputState.originalHeight);
				layoutChanged = true;
			}

			if (input.getOriginalY() != nativeInputState.originalY) {
				input.setOriginalY(nativeInputState.originalY);
				layoutChanged = true;
			}

			if (nativeInputState.text != null && !nativeInputState.text.equals(input.getText())) {
				input.setText(nativeInputState.text);
			}

			if (layoutChanged) {
				input.revalidate();
			}
		}

		final Widget scrollArea = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		if (scrollArea != null && scrollArea == trackedScrollArea && nativeScrollState != null
				&& scrollArea.getOriginalHeight() != nativeScrollState.originalHeight) {
			scrollArea.setOriginalHeight(nativeScrollState.originalHeight);
			scrollArea.revalidate();

			final Widget scrollbar = client.getWidget(InterfaceID.Chatbox.CHATSCROLLBAR);
			if (scrollbar != null) {
				scrollbar.revalidate();
			}
		}

		trackedInput = null;
		nativeInputState = null;
		appliedInputState = null;
		trackedScrollArea = null;
		nativeScrollState = null;
		appliedScrollReserve = null;
	}

	private void captureNativeInputState(Widget input) {
		if (input != trackedInput || nativeInputState == null) {
			trackedInput = input;
			nativeInputState = NativeInputState.capture(input);
			appliedInputState = null;
			return;
		}

		if (appliedInputState == null || input.getFontId() != appliedInputState.fontId) {
			nativeInputState.fontId = input.getFontId();
		}

		if (appliedInputState == null || input.getLineHeight() != appliedInputState.lineHeight) {
			nativeInputState.lineHeight = input.getLineHeight();
		}

		if (appliedInputState == null || input.getOriginalHeight() != appliedInputState.originalHeight) {
			nativeInputState.originalHeight = input.getOriginalHeight();
		}

		if (appliedInputState == null || input.getOriginalY() != appliedInputState.originalY) {
			nativeInputState.originalY = input.getOriginalY();
		}

		final String currentText = input.getText();
		if (appliedInputState == null || !equals(currentText, appliedInputState.text)) {
			nativeInputState.text = currentText;
		}
	}

	private void captureNativeScrollState(Widget scrollArea) {
		if (scrollArea == null) {
			trackedScrollArea = null;
			nativeScrollState = null;
			appliedScrollReserve = null;
			return;
		}

		if (scrollArea != trackedScrollArea || nativeScrollState == null) {
			trackedScrollArea = scrollArea;
			nativeScrollState = new NativeScrollState(scrollArea.getOriginalHeight());
			appliedScrollReserve = null;
			return;
		}

		if (appliedScrollReserve == null || scrollArea.getOriginalHeight() != appliedScrollReserve) {
			nativeScrollState.originalHeight = scrollArea.getOriginalHeight();
		}
	}

	private static boolean equals(String first, String second) {
		return first == null
				? second == null
				: first.equals(second);
	}

	private static final class NativeInputState {
		private int fontId;
		private int lineHeight;
		private int originalHeight;
		private int originalY;
		private String text;

		private static NativeInputState capture(Widget input) {
			final NativeInputState state = new NativeInputState();

			state.fontId = input.getFontId();
			state.lineHeight = input.getLineHeight();
			state.originalHeight = input.getOriginalHeight();
			state.originalY = input.getOriginalY();
			state.text = input.getText();

			return state;
		}
	}

	private static final class AppliedInputState {
		private final int fontId;
		private final int lineHeight;
		private final int originalHeight;
		private final int originalY;
		private final String text;

		private AppliedInputState(int fontId, int lineHeight, int originalHeight, int originalY, String text) {
			this.fontId = fontId;
			this.lineHeight = lineHeight;
			this.originalHeight = originalHeight;
			this.originalY = originalY;
			this.text = text;
		}
	}

	private static final class NativeScrollState {
		private int originalHeight;

		private NativeScrollState(int originalHeight) {
			this.originalHeight = originalHeight;
		}
	}
}
