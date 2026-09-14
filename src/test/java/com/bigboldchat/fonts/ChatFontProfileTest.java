package com.bigboldchat.fonts;

import com.bigboldchat.config.ChatFont;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Snapshot tests for Chat XL's empirically tuned v1.0.0 font profiles.
 *
 * These values are intentionally explicit. A performance refactor should not
 * silently alter visual tuning that was established through in-game testing.
 */
public class ChatFontProfileTest
{
    /*
     * TESTS
     */

    @Test
    public void preservesV100ProfileTuning()
    {
        assertProfile(
                ChatFont.PLAIN_11,
                -2, 0, 0,
                0, -2, 0, 0, 0,
                0, 0, 1,
                0, 0, 0);

        assertProfile(
                ChatFont.PLAIN_12,
                0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 1,
                0, 0, 0);

        assertProfile(
                ChatFont.BOLD_12,
                0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1,
                0, 0, 0);

        assertProfile(
                ChatFont.QUILL_8,
                3, -2, 3,
                0, 1, 0, 0, 0,
                0, 0, 1,
                0, 2, 0);

        assertProfile(
                ChatFont.QUILL_MEDIUM,
                10, -4, 8,
                0, 6, 0, 0, 0,
                0, 0, 1,
                1, 7, 0);

        assertProfile(
                ChatFont.BARBARIAN,
                4, -8, 18,
                0, 10, 0, 0, 0,
                0, 0, 1,
                1, 2, 0);

        assertProfile(
                ChatFont.TAHOMA_11,
                -1, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1,
                0, 0, 0);

        assertProfile(
                ChatFont.VERDANA_11,
                -1, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 1,
                1, 0, 0);

        assertProfile(
                ChatFont.VERDANA_11_BOLD,
                -1, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 1,
                0, 0, 0);

        assertProfile(
                ChatFont.VERDANA_13,
                0, 0, 0,
                0, 1, 0, 0, 0,
                0, 0, 1,
                1, 0, 0);

        assertProfile(
                ChatFont.VERDANA_13_BOLD,
                0, 0, 0,
                0, 2, 0, 0, 0,
                0, 0, 1,
                1, 0, 0);

        assertProfile(
                ChatFont.VERDANA_15,
                3, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 1,
                2, 2, 0);
    }

    /*
     * HELPERS
     */

    private static void assertProfile(
            ChatFont font,
            int lineHeightAdjustment,
            int rowYOffset,
            int privateChatGap,
            int channelNameYOffset,
            int channelRankIconYOffset,
            int channelAccountBuildIconSpacing,
            int channelUsernameYOffset,
            int channelTextYOffset,
            int friendsChatPrefixYOffset,
            int friendsChatTextYOffset,
            int friendsChatPlayerIconSpacing,
            int rankIconRightAdjustment,
            int rankIconSizeAdjustment,
            int accountBuildIconPadding)
    {
        final ChatFontProfile profile =
                ChatFontRegistry.get(font);

        assertNotNull("Missing profile for " + font.name(), profile);
        assertEquals(font, profile.getChatFont());

        assertEquals(
                font.name() + " line-height adjustment",
                lineHeightAdjustment,
                profile.getLineHeightAdjustment());

        assertEquals(
                font.name() + " row Y offset",
                rowYOffset,
                profile.getRowYOffset());

        assertEquals(
                font.name() + " Split Private gap",
                privateChatGap,
                profile.getPrivateChatGap());

        assertEquals(
                font.name() + " channel-name Y offset",
                channelNameYOffset,
                profile.getChannelNameYOffset());

        assertEquals(
                font.name() + " channel rank-icon Y offset",
                channelRankIconYOffset,
                profile.getChannelRankIconYOffset());

        assertEquals(
                font.name() + " channel account/build icon spacing",
                channelAccountBuildIconSpacing,
                profile.getChannelAccountBuildIconSpacing());

        assertEquals(
                font.name() + " channel username Y offset",
                channelUsernameYOffset,
                profile.getChannelUsernameYOffset());

        assertEquals(
                font.name() + " channel text Y offset",
                channelTextYOffset,
                profile.getChannelTextYOffset());

        assertEquals(
                font.name() + " Friends Chat prefix Y offset",
                friendsChatPrefixYOffset,
                profile.getFriendsChatPrefixYOffset());

        assertEquals(
                font.name() + " Friends Chat text Y offset",
                friendsChatTextYOffset,
                profile.getFriendsChatTextYOffset());

        assertEquals(
                font.name() + " Friends Chat player-icon spacing",
                friendsChatPlayerIconSpacing,
                profile.getFriendsChatPlayerIconSpacing());

        assertEquals(
                font.name() + " rank-icon right adjustment",
                rankIconRightAdjustment,
                profile.getRankIconRightAdjustment());

        assertEquals(
                font.name() + " rank-icon size adjustment",
                rankIconSizeAdjustment,
                profile.getRankIconSizeAdjustment());

        assertEquals(
                font.name() + " account/build icon padding",
                accountBuildIconPadding,
                profile.getAccountBuildIconPadding());
    }
}
