package com.bigboldchat.chat;

import com.bigboldchat.debug.PerformanceMetrics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.runelite.client.util.Text;

/**
 * Normalizes RuneScape chat text for comparison and measurement.
 */
public final class ChatTextNormalizer
{
    private static final Pattern LINE_BREAK_PATTERN =
            Pattern.compile(
                    "<br\\s*/?>",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern IMAGE_PATTERN =
            Pattern.compile(
                    "<img=(\\d+)>",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern AT_PATTERN =
            Pattern.compile(
                    "<at>",
                    Pattern.CASE_INSENSITIVE);

    // TODO: Record normalization performance during development.
    private final PerformanceMetrics performanceMetrics;

    public ChatTextNormalizer()
    {
        this(null);
    }

    public ChatTextNormalizer(
            PerformanceMetrics performanceMetrics)
    {
        this.performanceMetrics =
                performanceMetrics;
    }

    String normalizeSemantic(
            String text)
    {
        // TODO: Measure semantic normalization during development.
        final long started =
                performanceMetrics != null
                        ? System.nanoTime()
                        : 0L;

        try
        {
            if (text == null)
            {
                return null;
            }

            /*
             * Most chat text contains no markup.
             */
            if (text.indexOf('<') < 0)
            {
                final String normalized =
                        text.indexOf('\u00A0') >= 0
                                ? text.replace(
                                '\u00A0',
                                ' ')
                                : text;

                return normalized.trim();
            }

            final String withLineBreaks =
                    LINE_BREAK_PATTERN
                            .matcher(
                                    text)
                            .replaceAll(
                                    "\n");

            final String withVisibleAtCharacters =
                    AT_PATTERN
                            .matcher(
                                    withLineBreaks)
                            .replaceAll(
                                    "@");

            final String semantic =
                    Text.removeTags(
                                    withVisibleAtCharacters)
                            .replace(
                                    '\u00A0',
                                    ' ')
                            .trim();

            if (!semantic.isEmpty())
            {
                return semantic;
            }

// Keep image-only messages identifiable after markup is removed.
            return extractImages(
                    withVisibleAtCharacters);
        }
        finally
        {
            if (performanceMetrics != null)
            {
                performanceMetrics.recordNormalization(
                        System.nanoTime()
                                - started);
            }
        }
    }

    String measureSemantic(
            String text)
    {
        // TODO: Measure wrapping normalization during development.
        final long started =
                performanceMetrics != null
                        ? System.nanoTime()
                        : 0L;

        try
        {
            if (text == null)
            {
                return null;
            }

            /*
             * Keep inline image markup for font measurement.
             */
            if (text.indexOf('<') < 0)
            {
                final String normalized =
                        text.indexOf('\u00A0') >= 0
                                ? text.replace(
                                '\u00A0',
                                ' ')
                                : text;

                return normalized.trim();
            }

            final String withLineBreaks =
                    LINE_BREAK_PATTERN
                            .matcher(
                                    text)
                            .replaceAll(
                                    "\n");

            final String withVisibleAtCharacters =
                    AT_PATTERN
                            .matcher(
                                    withLineBreaks)
                            .replaceAll(
                                    "@");

            return withVisibleAtCharacters
                    .replace(
                            '\u00A0',
                            ' ')
                    .trim();
        }
        finally
        {
            if (performanceMetrics != null)
            {
                performanceMetrics.recordNormalization(
                        System.nanoTime()
                                - started);
            }
        }
    }

    /*
     * HELPERS
     */

    private String extractImages(
            String text)
    {
        if (text == null
                || text.isEmpty())
        {
            return "";
        }

        final Matcher matcher =
                IMAGE_PATTERN.matcher(
                        text);

        final StringBuilder images =
                new StringBuilder();

        while (matcher.find())
        {
            images.append(
                            "<img=")
                    .append(
                            matcher.group(
                                    1))
                    .append(
                            '>');
        }

        return images.toString();
    }
}