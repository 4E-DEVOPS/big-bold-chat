package com.bigboldchat.chat;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChatTextNormalizerTest
{
    private static final boolean PERFORMANCE_TEST = false;
    private static final int PERFORMANCE_WARMUP_ITERATIONS = 10_000;
    private static final int PERFORMANCE_ITERATIONS = 100_000;

    private ChatTextNormalizer normalizer;

    @Before
    public void setUp()
    {
        normalizer =
                new ChatTextNormalizer();
    }

    /*
     * TESTS
     */

    @Test
    public void normalizeSemanticPreservesCurrentNullAndTrimBehavior()
    {
        assertNull(
                normalizer.normalizeSemantic(
                        null));

        assertEquals(
                "hello",
                normalizer.normalizeSemantic(
                        " hello "));
    }

    @Test
    public void normalizeSemanticRemovesRuneScapeMarkup()
    {
        assertEquals(
                "Hello",
                normalizer.normalizeSemantic(
                        "<col=ff0000>Hello</col>"));
    }

    @Test
    public void normalizeSemanticConvertsNonBreakingSpaces()
    {
        assertEquals(
                "Hello world",
                normalizer.normalizeSemantic(
                        "Hello\u00A0world"));
    }

    @Test
    public void normalizeSemanticPreservesExplicitLineBreaks()
    {
        assertEquals(
                "one\ntwo",
                normalizer.normalizeSemantic(
                        "one<br>two"));

        assertEquals(
                "one\ntwo",
                normalizer.normalizeSemantic(
                        "one<BR>two"));

        assertEquals(
                "one\ntwo",
                normalizer.normalizeSemantic(
                        "one<br/>two"));

        assertEquals(
                "one\ntwo",
                normalizer.normalizeSemantic(
                        "one<br />two"));

        assertEquals(
                "one\ntwo",
                normalizer.normalizeSemantic(
                        "one<Br   />two"));
    }

    @Test
    public void measureSemanticPreservesInlineImages()
    {
        assertEquals(
                "<col=00ea8b>Hello <img=412> world</col>",
                normalizer.measureSemantic(
                        "<col=00ea8b>Hello\u00A0<img=412>\u00A0world</col>"));
    }

    @Test
    public void measureSemanticPreservesExplicitLineBreaks()
    {
        assertEquals(
                "<col=00ea8b>one\ntwo <img=412></col>",
                normalizer.measureSemantic(
                        "<col=00ea8b>one<br>two <img=412></col>"));

        assertEquals(
                "<col=00ea8b>one\ntwo <img=412></col>",
                normalizer.measureSemantic(
                        "<col=00ea8b>one<BR />two <img=412></col>"));
    }

    @Test
    public void measureSemanticPreservesNullAndTrimBehavior()
    {
        assertNull(
                normalizer.measureSemantic(
                        null));

        assertEquals(
                "",
                normalizer.measureSemantic(
                        "   "));

        assertEquals(
                "Hello world",
                normalizer.measureSemantic(
                        "  Hello world  "));
    }

    @Test
    public void normalizeSemanticPreservesImageOnlyMessages()
    {
        assertEquals(
                "<img=413>",
                normalizer.normalizeSemantic(
                        "<col=9090ff><img=413></col>"));

        assertEquals(
                "<img=412><img=413>",
                normalizer.normalizeSemantic(
                        "<col=9090ff><img=412><img=413></col>"));

        assertEquals(
                "<img=413>",
                normalizer.normalizeSemantic(
                        "<col=9090ff><IMG=413></col>"));
    }

    @Test
    public void normalizeSemanticRemovesInlineImagesFromText()
    {
        assertEquals(
                "Hello  world",
                normalizer.normalizeSemantic(
                        "<col=9090ff>Hello <img=413> world</col>"));
    }

    /*
     * PERFORMANCE
     */

    @Test
    public void performanceNormalizeSemantic()
    {
        if (!PERFORMANCE_TEST)
        {
            return;
        }

        final String plainText =
                "Hello everyone this is a normal RuneScape chat message";

        final String markupText =
                "<col=ff0000>Hello\u00A0everyone</col>"
                        + "<br />"
                        + "<u>PlayerName:</u>";

        long checksum =
                0L;

        for (int i = 0;
             i < PERFORMANCE_WARMUP_ITERATIONS;
             i++)
        {
            checksum +=
                    normalizer.normalizeSemantic(
                                    plainText)
                            .length();

            checksum +=
                    normalizer.normalizeSemantic(
                                    markupText)
                            .length();
        }

        long started =
                System.nanoTime();

        for (int i = 0;
             i < PERFORMANCE_ITERATIONS;
             i++)
        {
            checksum +=
                    normalizer.normalizeSemantic(
                                    plainText)
                            .length();
        }

        final long plainElapsed =
                System.nanoTime()
                        - started;

        started =
                System.nanoTime();

        for (int i = 0;
             i < PERFORMANCE_ITERATIONS;
             i++)
        {
            checksum +=
                    normalizer.normalizeSemantic(
                                    markupText)
                            .length();
        }

        final long markupElapsed =
                System.nanoTime()
                        - started;

        assertTrue(
                checksum > 0L);

        final double plainTotalMs =
                plainElapsed
                        / 1_000_000.0;

        final double markupTotalMs =
                markupElapsed
                        / 1_000_000.0;

        System.out.printf(
                "[Chat XL][ChatTextNormalizerTest] Performance= "
                        + "NormalizePlain: %.3fms (%.6fms) | "
                        + "NormalizeMarkup: %.3fms (%.6fms) | "
                        + "Iterations: %d%n",
                plainTotalMs,
                plainTotalMs
                        / PERFORMANCE_ITERATIONS,
                markupTotalMs,
                markupTotalMs
                        / PERFORMANCE_ITERATIONS,
                PERFORMANCE_ITERATIONS);
    }
}