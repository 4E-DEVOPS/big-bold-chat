package com.bigboldchat.chat;

import net.runelite.api.FontTypeFace;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for the Chat XL measurement service.
 */
public class FontMeasurementServiceTest
{
    private FontMeasurementService service;

    @Before
    public void setUp()
    {
        /*
         * The supported-script and wrapping contracts do not access
         * Client state.
         */
        service =
                new FontMeasurementService(
                        null);
    }

    /*
     * ================================================================
     * SCRIPT SUPPORT
     * ================================================================
     */

    @Test
    public void supportsOnlyKnownChatConstructionScripts()
    {
        assertTrue(
                service.supportsScript(
                        199));

        assertTrue(
                service.supportsScript(
                        203));

        assertTrue(
                service.supportsScript(
                        4483));

        assertFalse(
                service.supportsScript(
                        -1));

        assertFalse(
                service.supportsScript(
                        0));

        assertFalse(
                service.supportsScript(
                        72));

        assertFalse(
                service.supportsScript(
                        193));

        assertFalse(
                service.supportsScript(
                        4482));

        assertFalse(
                service.supportsScript(
                        4484));
    }

    /*
     * ================================================================
     * SINGLE-PARAGRAPH WRAPPING
     * ================================================================
     */

    @Test
    public void oversizedLettersRemainOneTokenLine()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "aaaaaaaaaa",
                        40);

        assertEquals(
                1,
                lines);
    }

    @Test
    public void oversizedNumbersRemainOneTokenLine()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "1111111111",
                        40);

        assertEquals(
                1,
                lines);
    }

    @Test
    public void oversizedHashRunRemainsOneTokenLine()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "##########",
                        40);

        assertEquals(
                1,
                lines);
    }

    @Test
    public void oversizedCaretRunRemainsOneTokenLine()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "^^^^^^^^^^",
                        40);

        assertEquals(
                1,
                lines);
    }

    @Test
    public void oversizedAtRunRemainsOneTokenLine()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "@@@@@@@@@@",
                        40);

        assertEquals(
                1,
                lines);
    }

    @Test
    public void oversizedMixedSymbolRunRemainsOneTokenLine()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "#^%&*!@#$^",
                        40);

        assertEquals(
                1,
                lines);
    }

    @Test
    public void oversizedTokenFollowedByNormalWordStartsNextLine()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "########## hi",
                        40);

        assertEquals(
                2,
                lines);
    }

    @Test
    public void normalWordsStillWrapByAvailableWidth()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        /*
         * "aa bb" = 5 characters = 50px.
         *
         * "aa bb cc" = 8 characters = 80px.
         *
         * With a 50px row:
         *
         *     line 1: aa bb
         *     line 2: cc
         */
        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "aa bb cc",
                        50);

        assertEquals(
                2,
                lines);
    }

    @Test
    public void normalWordsRemainOneLineWhenTheyFit()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "aa bb",
                        50);

        assertEquals(
                1,
                lines);
    }

    @Test
    public void oversizedTokenBetweenNormalWordsPreservesLineBoundaries()
    {
        final FontTypeFace font =
                fixedWidthFont(
                        10);

        /*
         * "hi" fits.
         *
         * The oversized token cannot join "hi", so it starts the next
         * logical line.
         *
         * "bye" cannot join the oversized token, so it starts another.
         */
        final int lines =
                service.calculateSingleParagraphLines(
                        font,
                        "hi ########## bye",
                        40);

        assertEquals(
                3,
                lines);
    }

    /*
     * ================================================================
     * HELPERS
     * ================================================================
     */

    private FontTypeFace fixedWidthFont(
            int characterWidth)
    {
        final FontTypeFace font =
                mock(
                        FontTypeFace.class);

        when(font.getTextWidth(
                anyString()))
                .thenAnswer(
                        invocation ->
                        {
                            final String text =
                                    invocation.getArgument(
                                            0);

                            return text.length()
                                    * characterWidth;
                        });

        return font;
    }
}