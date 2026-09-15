package com.bigboldchat.fonts;

import com.bigboldchat.config.ChatFont;

/**
 * Defines layout and presentation tuning for one Chat XL font.
 */
public interface ChatFontProfile
{
    ChatFont getChatFont();

    /**
     * Selected line-height adjustment.
     * Positive increases spacing; negative reduces it.
     */
    int getLineHeightAdjustment();

    /**
     * PRE Y offset applied to the complete chat row.
     * Negative moves up; positive moves down.
     */
    int getRowYOffset();

    /**
     * Additional Split Private gap above the chatbox.
     * Positive increases the gap; negative reduces it.
     */
    int getPrivateChatGap();

    /**
     * Finalize Y offset for the Clan / Guest Clan channel name.
     * Negative moves up; positive moves down.
     */
    int getChannelNameYOffset();

    /**
     * Finalize Y offset for the separate Clan / Guest Clan rank sprite.
     * Negative moves up; positive moves down.
     */
    int getChannelRankIconYOffset();

    /**
     * Non-breaking spaces inserted between the final inline account/build
     * icon and the Clan / Guest Clan username.
     */
    int getChannelAccountBuildIconSpacing();

    /**
     * Finalize Y offset for the Clan / Guest Clan username widget,
     * including any inline account/build icon.
     */
    int getChannelUsernameYOffset();

    /**
     * Finalize Y offset for the Clan / Guest Clan message body.
     */
    int getChannelTextYOffset();

    /**
     * Finalize Y offset for the combined Friends Chat prefix:
     * channel name, inline rank icon, and username.
     */
    int getFriendsChatPrefixYOffset();

    /**
     * Finalize Y offset for the Friends Chat message body.
     */
    int getFriendsChatTextYOffset();

    /**
     * Non-breaking spaces inserted between the final Friends Chat
     * inline player/rank icon and username.
     */
    int getFriendsChatPlayerIconSpacing();

    /**
     * Pixel spacing after the separate Clan / Guest Clan rank sprite
     * and before the username.
     */
    int getRankIconRightAdjustment();

    /**
     * Width and height adjustment for the separate Clan / Guest Clan rank sprite.
     * Does not resize inline image tags.
     */
    int getRankIconSizeAdjustment();

    /**
     * Additional sender-layout width reserved for an inline
     * Clan / Guest Clan account/build icon.
     */
    int getAccountBuildIconPadding();
}