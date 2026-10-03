package com.bigboldchat.chat;

import com.bigboldchat.config.ChatFont;

/**
 * Applies font-specific visible-text substitutions.
 */
public final class FontGlyphCorrections {
	private FontGlyphCorrections() {
	}

	public static String apply(ChatFont font, String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		if (font == ChatFont.VERDANA_13_BOLD) {
			return text.replace(':', '-');
		}

		return text;
	}
}
