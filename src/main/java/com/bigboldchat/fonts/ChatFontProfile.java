package com.bigboldchat.fonts;

import com.bigboldchat.config.ChatFont;

/**
 * Defines all layout tuning associated with one Chat XL font.
 *
 * Keeping each font's tuning in its own profile prevents the layout
 * service from accumulating large per-font switch statements.
 */
public interface ChatFontProfile
{
    ChatFont getChatFont();

    /**
     * Additional adjustment applied to RuneScape's native line-height
     * allocation for this font.
     *
     * This controls how much vertical space each rendered line owns.
     *
     * Positive = increase line spacing / row height.
     * Negative = reduce line spacing / row height.
     *
     * This is independent from row positioning. It changes the amount
     * of space allocated to the line, not where the row is constructed.
     */
    int getLineHeightAdjustment();

    /**
     * Vertical adjustment applied to the entire chat row during PRE
     * construction through RuneScape's row-Y argument.
     *
     * This moves the complete constructed row together, including its
     * text and any other elements positioned relative to that row.
     *
     * Negative = construct the row higher.
     * Positive = construct the row lower.
     *
     * Because the adjustment happens during construction, subsequent
     * rows are laid out relative to the adjusted result rather than
     * allowing one widget to visually overlap an adjacent row.
     */
    int getRowYOffset();

    /**
     * Additional vertical clearance applied only to Split Private Chat.
     *
     * RuneScape determines the native Split Private Chat position relative
     * to the chatbox. This value adds a font-specific adjustment to that
     * native relationship during Script 203 PRE construction.
     *
     * Positive = increase the gap above the chatbox.
     * Negative = reduce the gap above the chatbox.
     * Zero = preserve the normal calculated relationship.
     */
    int getPrivateChatGap();

    /**
     * Final vertical correction applied only to the Clan / Guest Clan
     * channel-name widget after RuneScape has established the row's
     * final native Y position.
     *
     * This does not move the separate rank icon, username, or message body.
     *
     * Negative = move the channel name upward.
     * Positive = move the channel name downward.
     */
    int getChannelNameYOffset();

    /**
     * Final vertical correction applied only to the separate Clan / Guest
     * Clan rank-icon widget after RuneScape has established the row's
     * final native Y position.
     *
     * This does not affect inline account/build icons contained within the
     * username text widget, nor does it affect Friends Chat inline rank icons.
     *
     * This does not move the channel name, username, or message body.
     *
     * Negative = move the separate rank icon upward.
     * Positive = move the separate rank icon downward.
     */
    int getChannelRankIconYOffset();

    /**
     * Number of non-breaking spaces inserted between an inline account/build
     * icon and the Clan / Guest Clan username that follows it.
     *
     * Account/build icons are rendered inside the username widget:
     *
     *     <img=2>PlayerName:
     *
     * This is separate from the Clan / Guest Clan rank icon, which is a
     * standalone sprite widget controlled by getRankIconRightAdjustment().
     *
     * This value represents a number of non-breaking spaces, not pixels.
     *
     *     0 = no additional spacing
     *     1 = one non-breaking space
     *     2 = two non-breaking spaces
     */
    int getChannelAccountBuildIconSpacing();

    /**
     * Final vertical correction applied only to the Clan / Guest Clan
     * username widget after RuneScape has established the row's final
     * native Y position.
     *
     * Any inline account/build icon contained within the username widget
     * moves together with the username because both are rendered by the
     * same text widget.
     *
     * This does not move the channel name, separate rank icon, or message body.
     *
     * Negative = move the username upward.
     * Positive = move the username downward.
     */
    int getChannelUsernameYOffset();

    /**
     * Final vertical correction applied only to the Clan / Guest Clan
     * message-body widget after RuneScape has established the row's
     * final native Y position.
     *
     * This does not move the channel name, separate rank icon, or username.
     *
     * Negative = move the message text upward.
     * Positive = move the message text downward.
     */
    int getChannelTextYOffset();

    /**
     * Final vertical correction applied to the single Friends Chat prefix
     * widget after RuneScape has established the row's final native Y position.
     *
     * Friends Chat renders these elements together inside one text widget:
     *
     *     [Friends Chat name] + inline rank icon + username
     *
     * Because all three elements share the same widget, they cannot be moved
     * vertically independently through widget positioning.
     *
     * This does not move the Friends Chat message body.
     *
     * Negative = move the complete Friends Chat prefix upward.
     * Positive = move the complete Friends Chat prefix downward.
     */
    int getFriendsChatPrefixYOffset();

    /**
     * Final vertical correction applied only to the Friends Chat message-body
     * widget after RuneScape has established the row's final native Y position.
     *
     * This does not move the combined Friends Chat prefix containing the
     * channel name, inline rank icon, and username.
     *
     * Negative = move the message text upward.
     * Positive = move the message text downward.
     */
    int getFriendsChatTextYOffset();

    /**
     * Number of non-breaking spaces inserted between the inline Friends Chat
     * player/rank icon and the username that follows it.
     *
     * Friends Chat renders the channel name, player/rank icon, and username
     * together inside one textual prefix widget:
     *
     *     [Friends Chat] <img=67>PlayerName:
     *
     * This value controls only the visible space between the final inline
     * player/rank icon and the username.
     *
     * This value represents a number of non-breaking spaces, not pixels.
     *
     *     0 = no additional spacing
     *     1 = one non-breaking space
     *     2 = two non-breaking spaces
     */
    int getFriendsChatPlayerIconSpacing();

    /**
     * Additional horizontal spacing placed immediately AFTER the separate
     * Clan / Guest Clan rank-icon widget and BEFORE the username widget.
     *
     * Clan / Guest Clan rank icons are separate sprite widgets. This value
     * increases or reduces the horizontal gap following that sprite without
     * changing the icon's own position or dimensions.
     *
     * This value does NOT affect:
     *
     *     Friends Chat inline rank icons
     *     Clan / Guest Clan inline account/build icons
     *
     * Those icons are embedded inside text widgets using <img=...> markup,
     * and their username spacing is controlled by
     * getFriendsChatPlayerIconSpacing().
     *
     * Positive = more space between the separate rank icon and username.
     * Negative = less space between the separate rank icon and username.
     */
    int getRankIconRightAdjustment();

    /**
     * Size adjustment applied to the native width and height of the separate
     * Clan / Guest Clan rank-icon widget.
     *
     * The adjustment is added equally to the icon's native dimensions.
     *
     * Example:
     *
     *     native icon = 13x13
     *     adjustment  = +2
     *     final icon  = 15x15
     *
     * Positive = enlarge the icon.
     * Negative = reduce the icon.
     *
     * Horizontal layout accounts for the adjusted width so an enlarged or
     * reduced rank icon does not overlap the username.
     *
     * This value does NOT resize inline <img=...> icons contained within
     * text widgets, including Friends Chat rank icons and account/build icons.
     */
    int getRankIconSizeAdjustment();

    /**
     * Additional horizontal width reserved for Clan / Guest Clan usernames
     * containing an inline account/build icon.
     *
     * Account/build icons are embedded inside the username widget using markup
     * such as:
     *
     *     <img=2>PlayerName:
     *
     * Their actual rendered icon width is already measured from the raw
     * username markup. This value provides an additional profile-specific
     * layout allowance when calculating the sender width and message-body X.
     *
     * This is intentionally separate from getFriendsChatPlayerIconSpacing():
     *
     *     getFriendsChatPlayerIconSpacing()
     *         = visible spaces inserted between the inline icon and username
     *
     *     getAccountBuildIconPadding()
     *         = additional measured width reserved by the layout
     *
     * This value does not affect the separate Clan / Guest Clan rank sprite
     * and does not represent Friends Chat rank-icon spacing.
     *
     * Positive = reserve additional horizontal width.
     * Negative = reduce the additional reserved width.
     */
    int getAccountBuildIconPadding();
}