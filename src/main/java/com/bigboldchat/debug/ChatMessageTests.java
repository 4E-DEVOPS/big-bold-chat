package com.bigboldchat.debug;

import javax.inject.Inject;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.IconID;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ChatIconManager;

/**
 * Generates synthetic chat messages for manual presentation testing.
 */
public final class ChatMessageTests {
	private static final String COMMAND = "debug-text";
	private static final String CHANNEL_NAME = "CHANNEL";

	private static final int FRIENDS_CHAT_ICON = 67;

	private static final int GUEST_RANK_SPRITE = 3061;
	private static final int GIM_RANK_SPRITE = 3074;
	private static final int CLAN_RANK_SPRITE = 3256;

	private final Client client;
	private final ClientThread clientThread;
	private final ChatIconManager chatIconManager;

	@Inject
	public ChatMessageTests(Client client, ClientThread clientThread, ChatIconManager chatIconManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.chatIconManager = chatIconManager;
	}

	public boolean onCommandExecuted(CommandExecuted event) {
		if (event == null || !COMMAND.equalsIgnoreCase(event.getCommand())) {
			return false;
		}

		clientThread.invokeLater(this::show);
		return true;
	}

	public void show() {
		showGameMessages();
		showPublicMessages();
		showPrivateMessages();
		showSocialMessages();
		showChannelMessages();
		showTradeMessages();
		showExamineMessages();
	}

	/*
	 * ================================================================
	 * GAME / SYSTEM
	 * ================================================================
	 */

	private void showGameMessages() {
		addChatTest(ChatMessageType.GAMEMESSAGE, "");
		addChatTest(ChatMessageType.ENGINE, "");
		addChatTest(ChatMessageType.CONSOLE, "");
		addChatTest(ChatMessageType.BROADCAST, "", IconID.CHAIN_LINK.toString() + "ChatXL BROADCAST test.");
		addChatTest(ChatMessageType.WELCOME, "");
		addChatTest(ChatMessageType.DIDYOUKNOW, "");
		addChatTest(ChatMessageType.LEVELUPMESSAGE, "");
		addChatTest(ChatMessageType.PLAYERRELATED, "");
		addChatTest(ChatMessageType.TENSECTIMEOUT, "");
		addChatTest(ChatMessageType.SPAM, "");
	}

	/*
	 * ================================================================
	 * PUBLIC / AUTOCHAT
	 * ================================================================
	 */

	private void showPublicMessages() {
		addChatTest(
				ChatMessageType.PUBLICCHAT,
				IconID.IRONMAN.toString() + "Player");

		addChatTest(
				ChatMessageType.MODCHAT,
				IconID.PLAYER_MODERATOR.toString() + "PMod",
				"ChatXL PLAYER_MODERATOR test.");

		addChatTest(
				ChatMessageType.MODCHAT,
				IconID.JAGEX_MODERATOR.toString() + "JMod",
				"ChatXL JAGEX_MODERATOR test.");

		addChatTest(
				ChatMessageType.AUTOTYPER,
				IconID.ULTIMATE_IRONMAN.toString() + "AutoTyper");

		addChatTest(
				ChatMessageType.MODAUTOTYPER,
				IconID.PLAYER_MODERATOR.toString() + "PMod");

		addChatTest(
				ChatMessageType.PUBLICCHAT,
				"Long",
				"The quick brown fox jumped over the lazy yellow @dog who was sleeping peacefully.");
	}

	/*
	 * ================================================================
	 * PRIVATE
	 * ================================================================
	 */

	private void showPrivateMessages() {
		addChatTest(ChatMessageType.PRIVATECHAT, "Private");
		addChatTest(ChatMessageType.PRIVATECHATOUT, "Private");
		addChatTest(ChatMessageType.MODPRIVATECHAT, IconID.PLAYER_MODERATOR.toString() + "PMod");
		addChatTest(ChatMessageType.LOGINLOGOUTNOTIFICATION, "", "Player has logged in.");
		addChatTest(ChatMessageType.LOGINLOGOUTNOTIFICATION, "", "Player has logged out.");
		addChatTest(ChatMessageType.PRIVATECHATOUT, "Private", "The quick brown fox jumped over the lazy yellow @dog who slept peacefully.");
	}

	/*
	 * ================================================================
	 * SOCIAL NOTIFICATIONS
	 * ================================================================
	 */

	private void showSocialMessages() {
		addChatTest(ChatMessageType.FRIENDNOTIFICATION, "");
		addChatTest(ChatMessageType.IGNORENOTIFICATION, "");
	}

	/*
	 * ================================================================
	 * FRIENDS / CLAN CHANNELS
	 * ================================================================
	 */

	private void showChannelMessages() {
		final String clanRank = clanRankIcon(CLAN_RANK_SPRITE);
		final String guestRank = clanRankIcon(GUEST_RANK_SPRITE);
		final String gimRank = clanRankIcon(GIM_RANK_SPRITE);

		addChatTest(
				ChatMessageType.FRIENDSCHAT,
				iconTag(FRIENDS_CHAT_ICON) + "Player",
				"ChatXL FRIENDSCHAT test.",
				CHANNEL_NAME);
		addChatTest(
				ChatMessageType.FRIENDSCHATNOTIFICATION,
				"",
				"ChatXL FRIENDSCHATNOTIFICATION test.",
				CHANNEL_NAME);

		addChatTest(
				ChatMessageType.CLAN_CHAT,
				clanRank + IconID.IRONMAN.toString() + "Player",
				"ChatXL CLAN_CHAT test.",
				CHANNEL_NAME);
		addChatTest(
				ChatMessageType.CLAN_MESSAGE,
				"",
				"ChatXL CLAN_MESSAGE test.",
				CHANNEL_NAME);

		addChatTest(
				ChatMessageType.CLAN_GUEST_CHAT,
				guestRank + "Player",
				"ChatXL CLAN_GUEST_CHAT test.",
				CHANNEL_NAME);
		addChatTest(
				ChatMessageType.CLAN_GUEST_MESSAGE,
				"",
				"ChatXL CLAN_GUEST_MESSAGE test.",
				CHANNEL_NAME);

		addChatTest(
				ChatMessageType.CLAN_GIM_CHAT,
				gimRank + IconID.GROUP_IRONMAN.toString() + "Player",
				"ChatXL CLAN_GIM_CHAT test.",
				CHANNEL_NAME);
		addChatTest(
				ChatMessageType.CLAN_GIM_MESSAGE,
				"",
				"ChatXL CLAN_GIM_MESSAGE test.",
				CHANNEL_NAME);
	}

	/*
	 * ================================================================
	 * TRADE / CHALLENGE
	 * ================================================================
	 */

	private void showTradeMessages() {
		addChatTest(ChatMessageType.TRADE_SENT, "Player");
		addChatTest(ChatMessageType.TRADEREQ, "Player");
		addChatTest(ChatMessageType.TRADE, "");
		addChatTest(ChatMessageType.CHALREQ_TRADE, "Player");
		addChatTest(ChatMessageType.CHALREQ_FRIENDSCHAT, "Player");
		addChatTest(ChatMessageType.CHALREQ_CLANCHAT, "Player");
	}

	/*
	 * ================================================================
	 * EXAMINE
	 * ================================================================
	 */

	private void showExamineMessages() {
		addChatTest(ChatMessageType.ITEM_EXAMINE, "");
		addChatTest(ChatMessageType.NPC_EXAMINE, "");
		addChatTest(ChatMessageType.OBJECT_EXAMINE, "");
	}

	/*
	 * ================================================================
	 * MESSAGE HELPERS
	 * ================================================================
	 */

	private void addChatTest(ChatMessageType type, String name) {
		addChatTest(type, name, type.name() + " test.", null);
	}

	private void addChatTest(ChatMessageType type, String name, String message) {
		addChatTest(type, name, message, null);
	}

	private void addChatTest(ChatMessageType type, String name, String message, String sender) {
		client.addChatMessage(type, name, message, sender);
	}

	/*
	 * ================================================================
	 * ICON HELPERS
	 * ================================================================
	 */

	private String iconTag(int iconId) {
		return iconId >= 0
				? "<img=" + iconId + ">"
				: "";
	}

	private String clanRankIcon(int spriteId) {
		final EnumComposition rankGraphics = client.getEnum(EnumID.CLAN_RANK_GRAPHIC);
		if (rankGraphics == null || rankGraphics.getKeys() == null) {
			return "";
		}

		for (int key : rankGraphics.getKeys()) {
			if (rankGraphics.getIntValue(key) != spriteId) {
				continue;
			}

			final int iconId = chatIconManager.getIconNumber(new ClanTitle(key, "ChatXL"));

			return iconTag(iconId);
		}

		return "";
	}
}
