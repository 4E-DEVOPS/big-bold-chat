package com.bigboldchat;

import com.bigboldchat.config.ChatFont;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("bigboldchat")
public interface Configurations extends Config
{
	@ConfigItem(
		keyName = "chatFont",
		name = "Chat Font",
		description = "Font used for chatbox and private-message text."
	)
	default ChatFont chatFont()
	{
		return ChatFont.PLAIN_12;
	}
}
