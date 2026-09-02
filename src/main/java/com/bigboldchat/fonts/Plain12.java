package com.bigboldchat.fonts;

import com.bigboldchat.config.ChatFont;

public final class Plain12 implements ChatFontProfile
{
    private static final int LINE_HEIGHT_ADJUSTMENT = 0;
    private static final int ROW_Y_OFFSET = 0;

    private static final int PRIVATE_CHAT_GAP = 0;

    private static final int CHANNEL_NAME_Y_OFFSET = 0;
    private static final int CHANNEL_RANK_ICON_Y_OFFSET = 0;
    private static final int CHANNEL_ACCOUNT_BUILD_ICON_SPACING = 0;
    private static final int CHANNEL_USERNAME_Y_OFFSET = 0;
    private static final int CHANNEL_TEXT_Y_OFFSET = 0;

    private static final int FRIENDS_CHAT_PREFIX_Y_OFFSET = 0;
    private static final int FRIENDS_CHAT_TEXT_Y_OFFSET = 0;
    private static final int FRIENDS_CHAT_PLAYER_ICON_SPACING = 1;

    private static final int RANK_ICON_RIGHT_ADJUSTMENT = 0;
    private static final int RANK_ICON_SIZE_ADJUSTMENT = 0;

    private static final int ACCOUNT_BUILD_ICON_PADDING = 0;

    @Override
    public ChatFont getChatFont()
    {
        return ChatFont.PLAIN_12;
    }

    @Override
    public int getLineHeightAdjustment()
    {
        return LINE_HEIGHT_ADJUSTMENT;
    }

    @Override
    public int getRowYOffset()
    {
        return ROW_Y_OFFSET;
    }

    @Override
    public int getPrivateChatGap()
    {
        return PRIVATE_CHAT_GAP;
    }

    @Override
    public int getChannelNameYOffset() { return CHANNEL_NAME_Y_OFFSET; }

    @Override
    public int getChannelRankIconYOffset() { return CHANNEL_RANK_ICON_Y_OFFSET; }

    @Override
    public int getChannelAccountBuildIconSpacing() { return CHANNEL_ACCOUNT_BUILD_ICON_SPACING; }

    @Override
    public int getChannelUsernameYOffset() { return CHANNEL_USERNAME_Y_OFFSET; }

    @Override
    public int getChannelTextYOffset()
    {
        return CHANNEL_TEXT_Y_OFFSET;
    }

    @Override
    public int getFriendsChatPrefixYOffset() { return FRIENDS_CHAT_PREFIX_Y_OFFSET; }

    @Override
    public int getFriendsChatTextYOffset() { return FRIENDS_CHAT_TEXT_Y_OFFSET; }

    @Override
    public int getFriendsChatPlayerIconSpacing() { return FRIENDS_CHAT_PLAYER_ICON_SPACING; }

    @Override
    public int getRankIconRightAdjustment()
    {
        return RANK_ICON_RIGHT_ADJUSTMENT;
    }

    @Override
    public int getRankIconSizeAdjustment() { return RANK_ICON_SIZE_ADJUSTMENT; }

    @Override
    public int getAccountBuildIconPadding()
    {
        return ACCOUNT_BUILD_ICON_PADDING;
    }
}