package com.bigboldchat;

import com.bigboldchat.config.ChatFont;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("bigboldchat")
public interface Configurations extends Config {
	/**
	 *  CHAT BOX
	 */
	@ConfigSection(
			name = "Chatbox",
			description = "Modify the Chatbox in game layouts.",
			position = 0
	)
	String chatBoxSection = "chatBox";

	@ConfigItem(
		keyName = "chatFont",
		name = "Font",
		description = "Font used for chatbox and private-message text.",
		position = 0,
		section = chatBoxSection
	)
	default ChatFont chatFont() {
		return ChatFont.PLAIN_12;
	}

	@Range(min = 320, max = 1000)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "chatboxWidth",
		name = "Chatbox Width",
		description = "Width of the chatbox.",
		position = 1,
		section = chatBoxSection
	)
	default int chatboxWidth() {
		return 519;
	}

	@Range(min = 100, max = 500)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "chatboxHeight",
		name = "Chatbox Height",
		description = "Height of the chatbox.",
		position = 2,
		section = chatBoxSection
	)
	default int chatboxHeight() {
		return 165;
	}

	@ConfigItem(
		keyName = "hideChatboxHotkey",
		name = "Hide Chatbox Hotkey",
		description = "Toggle the chatbox body between hidden and visible.",
		position = 3,
		section = chatBoxSection
	)
	default Keybind hideChatboxHotkey() {
		return Keybind.NOT_SET;
	}
}
