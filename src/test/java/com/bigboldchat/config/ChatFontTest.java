package com.bigboldchat.config;

import net.runelite.api.FontID;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Locks down Chat XL's player-facing font names and RuneScape FontID mappings.
 *
 * These names are part of the plugin's public configuration contract and should
 * not change as a side effect of internal performance refactors.
 */
public class ChatFontTest
{

    /*
     * TESTS
     */

    @Test
    public void exposesExpectedFontsInExpectedOrder()
    {
        assertArrayEquals(
                new ChatFont[]
                {
                        ChatFont.PLAIN_11,
                        ChatFont.PLAIN_12,
                        ChatFont.BOLD_12,
                        ChatFont.QUILL_8,
                        ChatFont.QUILL_MEDIUM,
                        ChatFont.BARBARIAN,
                        ChatFont.TAHOMA_11,
                        ChatFont.VERDANA_11,
                        ChatFont.VERDANA_11_BOLD,
                        ChatFont.VERDANA_13,
                        ChatFont.VERDANA_13_BOLD,
                        ChatFont.VERDANA_15
                },
                ChatFont.values());
    }

    @Test
    public void preservesPlayerFacingNamesAndFontIds()
    {
        assertFont(ChatFont.PLAIN_11, "Plain 11", FontID.PLAIN_11);
        assertFont(ChatFont.PLAIN_12, "Plain 12 (Default)", FontID.PLAIN_12);
        assertFont(ChatFont.BOLD_12, "Bold 12", FontID.BOLD_12);

        assertFont(ChatFont.QUILL_8, "Quill Small", FontID.QUILL_8);
        assertFont(ChatFont.QUILL_MEDIUM, "Quill Medium", FontID.QUILL_MEDIUM);
        assertFont(ChatFont.BARBARIAN, "Barbarian", FontID.BARBARIAN);

        assertFont(ChatFont.TAHOMA_11, "Tahoma 11", FontID.TAHOMA_11);
        assertFont(ChatFont.VERDANA_11, "Verdana 11", FontID.VERDANA_11);
        assertFont(ChatFont.VERDANA_11_BOLD, "Verdana 11 Bold", FontID.VERDANA_11_BOLD);
        assertFont(ChatFont.VERDANA_13, "Verdana 13", FontID.VERDANA_13);
        assertFont(ChatFont.VERDANA_13_BOLD, "Verdana 13 Bold", FontID.VERDANA_13_BOLD);
        assertFont(ChatFont.VERDANA_15, "Verdana 15", FontID.VERDANA_15);
    }

    /*
     * HELPERS
     */

    private static void assertFont(
            ChatFont font,
            String expectedDisplayName,
            int expectedFontId)
    {
        assertEquals(expectedDisplayName, font.toString());
        assertEquals(expectedFontId, font.getFontId());
    }
}
