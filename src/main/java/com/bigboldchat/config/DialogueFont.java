package com.bigboldchat.config;

public enum DialogueFont {
	PLAIN_11("Plain 11", ChatFont.PLAIN_11),
	PLAIN_12("Plain 12", ChatFont.PLAIN_12),
	BOLD_12("Bold 12", ChatFont.BOLD_12),

	QUILL_8("Quill Small (Default)", ChatFont.QUILL_8),
	QUILL_MEDIUM("Quill Medium", ChatFont.QUILL_MEDIUM),
	BARBARIAN("Barbarian", ChatFont.BARBARIAN),

	TAHOMA_11("Tahoma 11", ChatFont.TAHOMA_11),
	VERDANA_11("Verdana 11", ChatFont.VERDANA_11),
	VERDANA_11_BOLD("Verdana 11 Bold", ChatFont.VERDANA_11_BOLD),
	VERDANA_13("Verdana 13", ChatFont.VERDANA_13),
	VERDANA_13_BOLD("Verdana 13 Bold", ChatFont.VERDANA_13_BOLD),
	VERDANA_15("Verdana 15", ChatFont.VERDANA_15);

	private final String displayName;
	private final ChatFont chatFont;

	DialogueFont(String displayName, ChatFont chatFont) {
		this.displayName = displayName;
		this.chatFont = chatFont;
	}

	public ChatFont getChatFont() {
		return chatFont;
	}

	public ChatFont getTitleFont() {
		switch (this) {
			case PLAIN_12:
				return ChatFont.BOLD_12;
			case VERDANA_11:
				return ChatFont.VERDANA_11_BOLD;
			case VERDANA_13:
				return ChatFont.VERDANA_13_BOLD;
			default:
				return chatFont;
		}
	}

	@Override
	public String toString() {
		return displayName;
	}
}
