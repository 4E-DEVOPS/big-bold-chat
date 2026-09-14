package com.bigboldchat.fonts;

import com.bigboldchat.config.ChatFont;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for the central font-profile registry.
 */
public class ChatFontRegistryTest
{
    private static final boolean PERFORMANCE_TEST = false;
    private static final int PERFORMANCE_WARMUP_ITERATIONS = 10_000;
    private static final int PERFORMANCE_ITERATIONS = 100_000;

    /*
     * TESTS
     */

    @Test
    public void everyConfiguredChatFontHasAProfile()
    {
        for (ChatFont font : ChatFont.values())
        {
            final ChatFontProfile profile =
                    ChatFontRegistry.get(font);

            assertNotNull(
                    "Missing profile for " + font.name(),
                    profile);

            assertSame(
                    "Profile is registered under the wrong ChatFont for " + font.name(),
                    font,
                    profile.getChatFont());
        }
    }

    @Test
    public void registryReturnsStableProfileInstances()
    {
        for (ChatFont font : ChatFont.values())
        {
            assertSame(
                    "Registry should return the same immutable profile instance for " + font.name(),
                    ChatFontRegistry.get(font),
                    ChatFontRegistry.get(font));
        }
    }

    @Test
    public void nullFallsBackToPlain12()
    {
        assertSame(
                ChatFontRegistry.get(ChatFont.PLAIN_12),
                ChatFontRegistry.get(null));
    }

    /*
     * PERFORMANCE
     */

    @Test
    public void performanceProfileLookup()
    {
        if (!PERFORMANCE_TEST)
        {
            return;
        }

        final ChatFont[] fonts =
                ChatFont.values();

        long checksum =
                0L;

        /*
         * Cycle through every configured font instead of repeatedly
         * querying one constant registry key.
         */
        for (int i = 0;
             i < PERFORMANCE_WARMUP_ITERATIONS;
             i++)
        {
            final ChatFontProfile profile =
                    ChatFontRegistry.get(
                            fonts[i
                                    % fonts.length]);

            checksum +=
                    profile.getChatFont()
                            .ordinal()
                            + 1L;
        }

        final long started =
                System.nanoTime();

        for (int i = 0;
             i < PERFORMANCE_ITERATIONS;
             i++)
        {
            final ChatFontProfile profile =
                    ChatFontRegistry.get(
                            fonts[i
                                    % fonts.length]);

            checksum +=
                    profile.getChatFont()
                            .ordinal()
                            + 1L;
        }

        final long elapsed =
                System.nanoTime()
                        - started;

        assertTrue(
                checksum > 0L);

        final double totalMs =
                elapsed
                        / 1_000_000.0;

        System.out.printf(
                "[Chat XL][ChatFontRegistryTest] Performance= "
                        + "ProfileLookup: %.3fms (%.6fms) | "
                        + "Iterations: %d%n",
                totalMs,
                totalMs
                        / PERFORMANCE_ITERATIONS,
                PERFORMANCE_ITERATIONS);
    }
}
