package com.bigboldchat.debug;

import java.util.Locale;

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
	private static final String REPORTED_CHANNEL_NAME = "CLAN";

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

		final String[] arguments = event.getArguments();
		final String selection = arguments != null && arguments.length > 0
				? arguments[0].toLowerCase(Locale.ROOT)
				: "all";
		final Runnable action;
		switch (selection) {
			case "all":
				action = this::show;
				break;
			case "game":
			case "system":
				action = this::showGameMessages;
				break;
			case "public":
				action = this::showPublicMessages;
				break;
			case "private":
				action = this::showPrivateMessages;
				break;
			case "social":
				action = this::showSocialMessages;
				break;
			case "fc":
			case "friends":
			case "friendchat":
				action = this::showFriendsMessages;
				break;
			case "channel":
			case "channels":
				action = this::showChannelMessages;
				break;
			case "cc":
			case "clan":
				action = this::showClanMessages;
				break;
			case "notices":
				action = this::showClanNotices;
				break;
			case "templates":
				action = this::showClanTemplates;
				break;
			case "trade":
				action = this::showTradeMessages;
				break;
			case "examine":
				action = this::showExamineMessages;
				break;
			case "cases":
				action = this::showSpecialCases;
				break;
			default:
				return false;
		}

		clientThread.invokeLater(action);
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
		showSpecialCases();
	}

	/* GAME / SYSTEM */
	private void showGameMessages() {
		addChatTest(ChatMessageType.GAMEMESSAGE, "");
		addChatTest(ChatMessageType.ENGINE, "");
		addChatTest(ChatMessageType.CONSOLE, "");
		addChatTest(ChatMessageType.BROADCAST, "", IconID.CHAIN_LINK.toString() + "ChatXL BROADCAST text.");
		addChatTest(ChatMessageType.WELCOME, "");
		addChatTest(ChatMessageType.DIDYOUKNOW, "");
		addChatTest(ChatMessageType.LEVELUPMESSAGE, "");
		addChatTest(ChatMessageType.PLAYERRELATED, "");
		addChatTest(ChatMessageType.TENSECTIMEOUT, "");
		addChatTest(ChatMessageType.SPAM, "");
	}

	/* PUBLIC / AUTOCHAT */
	private void showPublicMessages() {
		addChatTest(ChatMessageType.PUBLICCHAT, IconID.IRONMAN.toString() + "Player");
		addChatTest(ChatMessageType.MODCHAT, IconID.PLAYER_MODERATOR.toString() + "PMod", "ChatXL PLAYER_MODERATOR text.");
		addChatTest(ChatMessageType.MODCHAT, IconID.JAGEX_MODERATOR.toString() + "JMod", "ChatXL JAGEX_MODERATOR text.");
		addChatTest(ChatMessageType.AUTOTYPER, IconID.ULTIMATE_IRONMAN.toString() + "AutoTyper");
		addChatTest(ChatMessageType.MODAUTOTYPER, IconID.PLAYER_MODERATOR.toString() + "PMod");
		addChatTest(ChatMessageType.PUBLICCHAT, "Long", "The quick brown fox jumped over the lazy yellow @dog who was sleeping peacefully.");
	}

	/* PRIVATE */
	private void showPrivateMessages() {
		addChatTest(ChatMessageType.PRIVATECHAT, "Private");
		addChatTest(ChatMessageType.PRIVATECHATOUT, "Private");
		addChatTest(ChatMessageType.MODPRIVATECHAT, IconID.PLAYER_MODERATOR.toString() + "PMod");
		addChatTest(ChatMessageType.LOGINLOGOUTNOTIFICATION, "", "Player has logged in.");
		addChatTest(ChatMessageType.LOGINLOGOUTNOTIFICATION, "", "Player has logged out.");
		addChatTest(ChatMessageType.PRIVATECHATOUT, "Private", "The quick brown fox jumped over the lazy yellow @dog who slept peacefully.");
		addChatTest(ChatMessageType.PRIVATECHATOUT, "Private", "Testing split-private chat with inline icons " + IconID.IRONMAN + " and " + IconID.GROUP_IRONMAN + " to verify that longer messages wrap correctly.");
	}

	/* SOCIAL NOTIFICATIONS */
	private void showSocialMessages() {
		addChatTest(ChatMessageType.FRIENDNOTIFICATION, "");
		addChatTest(ChatMessageType.IGNORENOTIFICATION, "");
	}

	/* FRIENDS / CLAN CHANNELS */
	private void showChannelMessages() {
		showFriendsMessages();
		showClanMessages();
	}

	/* FRIENDS CHAT */
	private void showFriendsMessages() {
		addChatTest(ChatMessageType.FRIENDSCHAT, iconTag(FRIENDS_CHAT_ICON) + "Player", "ChatXL FRIENDSCHAT text.", CHANNEL_NAME);
		addChatTest(ChatMessageType.FRIENDSCHATNOTIFICATION, "", "ChatXL FRIENDSCHATNOTIFICATION text.", CHANNEL_NAME);
	}

	/* CLAN / GUEST / GIM */
	private void showClanMessages() {
		final String clanRank = clanRankIcon(CLAN_RANK_SPRITE);
		final String guestRank = clanRankIcon(GUEST_RANK_SPRITE);
		final String gimRank = clanRankIcon(GIM_RANK_SPRITE);

		addChatTest(ChatMessageType.CLAN_CHAT, clanRank + IconID.IRONMAN.toString() + "Player", "ChatXL CLAN_CHAT text.", CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_MESSAGE, "", "ChatXL CLAN_MESSAGE text.", CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_GUEST_CHAT, guestRank + "Player", "ChatXL CLAN_GUEST_CHAT text.", CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_GUEST_MESSAGE, "", "ChatXL CLAN_GUEST_MESSAGE text.", CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_GIM_CHAT, gimRank + IconID.GROUP_IRONMAN.toString() + "Player", "ChatXL CLAN_GIM_CHAT text.", CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_GIM_MESSAGE, "", "ChatXL CLAN_GIM_MESSAGE text.", CHANNEL_NAME);

		showClanNotices();
	}

	private void showClanNotices() {
		showClanTemplates();
		// Experience and combat-task notices are already plain-text live messages.
		addChatTest(ChatMessageType.CLAN_MESSAGE, "", "ChatXL_Player has reached 42,000,000 XP in Ranged.", REPORTED_CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_MESSAGE, "", "ChatXL_Player has completed a master combat task: Perfect Zulrah.", REPORTED_CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_GUEST_MESSAGE, "", "ChatXL_Player has reached 42,000,000 XP in Ranged.", REPORTED_CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_GIM_MESSAGE, "", "<str_quest_name_0=The Birds and the Wasps>ChatXL_Player has completed a quest: <str_quest_name_0>", REPORTED_CHANNEL_NAME);
	}

	private void showClanTemplates() {
		// Exact native OS assignment/reference templates captured in live CLAN_MESSAGE events.
		addChatTest(ChatMessageType.CLAN_MESSAGE, "", "<str_quest_name_0=The Birds and the Wasps>ChatXL_Player has completed a quest: <str_quest_name_0>", REPORTED_CHANNEL_NAME);
		addChatTest(ChatMessageType.CLAN_MESSAGE, "", "ChatXL_Player received a drop: <int_0=102><int_0:spacer> x Dragon arrowtips<int_0=254898> (<int_0:spacer> coins) from Grotesque Guardians.", REPORTED_CHANNEL_NAME);
	}

	/* TRADE / CHALLENGE */
	private void showTradeMessages() {
		addChatTest(ChatMessageType.TRADE_SENT, "Player");
		addChatTest(ChatMessageType.TRADEREQ, "Player");
		addChatTest(ChatMessageType.TRADE, "");
		addChatTest(ChatMessageType.CHALREQ_TRADE, "Player");
		addChatTest(ChatMessageType.CHALREQ_FRIENDSCHAT, "Player");
		addChatTest(ChatMessageType.CHALREQ_CLANCHAT, "Player");
	}

	/* EXAMINE */
	private void showExamineMessages() {
		addChatTest(ChatMessageType.ITEM_EXAMINE, "");
		addChatTest(ChatMessageType.NPC_EXAMINE, "");
		addChatTest(ChatMessageType.OBJECT_EXAMINE, "");
	}

	/* CASES / RICH BODY CONTROLS */
	private void showSpecialCases() {
		// A no-image multiline control and a multiline body with inline icons.
		addChatTest(ChatMessageType.PUBLICCHAT, "Wrapped", "This longer message must wrap onto several lines without any inline sprites and must remain aligned with the sender.");
		addChatTest(ChatMessageType.PUBLICCHAT, "Sprites", "The first icon " + IconID.IRONMAN + " and the second " + IconID.GROUP_IRONMAN + " must follow the same chat line-height and baseline rules.");
		addChatTest(ChatMessageType.PUBLICCHAT, "Emojis", "This is a long message 🙂 using emojis 🌺 for the test of something that wraps naturally.");
	}

	/* MESSAGE HELPERS */
	private void addChatTest(ChatMessageType type, String name) {
		addChatTest(type, name, type.name() + " text.", null);
	}

	private void addChatTest(ChatMessageType type, String name, String message) {
		addChatTest(type, name, message, null);
	}

	private void addChatTest(ChatMessageType type, String name, String message, String sender) {
		client.addChatMessage(type, name, message, sender);
	}

	/* ICON HELPERS */
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
