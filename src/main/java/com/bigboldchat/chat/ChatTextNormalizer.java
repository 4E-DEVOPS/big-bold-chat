package com.bigboldchat.chat;

import com.bigboldchat.debug.PerformanceMetrics;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.runelite.client.util.Text;

/**
 * Normalizes chat text for semantic comparison and font measurement.
 */
public final class ChatTextNormalizer {
	private static final Pattern LINE_BREAK_PATTERN = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
	private static final Pattern IMAGE_PATTERN = Pattern.compile("<img=(\\d+)>", Pattern.CASE_INSENSITIVE);
	private static final Pattern AT_PATTERN = Pattern.compile("<at>", Pattern.CASE_INSENSITIVE);
	private static final Pattern PARAMETER_PATTERN = Pattern.compile("<((?:int|str)_[a-z0-9_]+)(?:=([^>]*))?(?::([a-z0-9_]+))?>", Pattern.CASE_INSENSITIVE);

	// Optional performance instrumentation; null when unavailable.
	private final PerformanceMetrics performanceMetrics;

	public ChatTextNormalizer() {
		this(null);
	}

	public ChatTextNormalizer(PerformanceMetrics performanceMetrics) {
		this.performanceMetrics = performanceMetrics;
	}

	String normalizeSemantic(String text) {
		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime()
				: 0L;

		try {
			if (text == null) {
				return null;
			}

			/*
			 * Most chat text contains no markup.
			 */
			if (text.indexOf('<') < 0) {
				final String normalized = text.indexOf('\u00A0') >= 0
						? text.replace('\u00A0', ' ')
						: text;

				return normalized.trim();
			}

			final String expanded = expandParameters(text);
			final String withLineBreaks = LINE_BREAK_PATTERN.matcher(expanded).replaceAll("\n");
			final String withVisibleAtCharacters = AT_PATTERN.matcher(withLineBreaks).replaceAll("@");
			final String semantic = Text.removeTags(withVisibleAtCharacters).replace('\u00A0', ' ').trim();
			if (!semantic.isEmpty()) {
				return semantic;
			}

			// Keep image-only messages identifiable after markup is removed.
			return extractImages(withVisibleAtCharacters);
		} finally {
			if (performanceMetrics != null && performanceMetrics.isEnabled()) {
				performanceMetrics.recordNormalization(System.nanoTime() - started);
			}
		}
	}

	String measureSemantic(String text) {
		final long started = performanceMetrics != null && performanceMetrics.isEnabled()
				? System.nanoTime()
				: 0L;

		try {
			if (text == null) {
				return null;
			}

			/*
			 * Keep inline image markup for font measurement.
			 */
			if (text.indexOf('<') < 0) {
				final String normalized = text.indexOf('\u00A0') >= 0
						? text.replace('\u00A0', ' ')
						: text;

				return normalized.trim();
			}

			final String expanded = expandParameters(text);
			final String withLineBreaks = LINE_BREAK_PATTERN.matcher(expanded).replaceAll("\n");
			final String withVisibleAtCharacters = AT_PATTERN.matcher(withLineBreaks).replaceAll("@");
			return withVisibleAtCharacters.replace('\u00A0', ' ').trim();
		} finally {
			if (performanceMetrics != null && performanceMetrics.isEnabled()) {
				performanceMetrics.recordNormalization(System.nanoTime() - started);
			}
		}
	}

	String renderText(String text) {
		if (text == null || text.indexOf('<') < 0) {
			return text;
		}

		return AT_PATTERN.matcher(expandParameters(text)).replaceAll("@");
	}

	boolean hasParameters(String text) {
		return text != null && (text.contains("<int_") || text.contains("<str_")) && PARAMETER_PATTERN.matcher(text).find();
	}

	/*
	 * Jagex's chat templates assign a value and reference it later in the same message.
	 * A native text widget contains the expanded result, not these tags.
	 * Keep assignments local to one message and leave unsupported tags untouched.
	 */
	private String expandParameters(String text) {
		if (!hasParameters(text)) {
			return text;
		}

		final Matcher matcher = PARAMETER_PATTERN.matcher(text);
		final Map<String, String> values = new HashMap<>();
		final StringBuffer expanded = new StringBuffer(text.length());
		while (matcher.find()) {
			final String key = matcher.group(1).toLowerCase(Locale.ROOT);
			final String assigned = matcher.group(2);
			final String replacement;
			if (assigned != null) {
				values.put(key, assigned);
				replacement = "";
			} else if (values.containsKey(key)) {
				final String value = values.get(key);
				replacement = key.startsWith("int_") ? formatInteger(value) : value;
			} else {
				// Do not invent a value for an unresolved native substitution.
				replacement = matcher.group();
			}
			matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(expanded);
		return expanded.toString();
	}

	private static String formatInteger(String value) {
		try {
			return String.format(Locale.US, "%,d", Long.parseLong(value));
		} catch (NumberFormatException exception) {
			return value;
		}
	}

	/*
	 * HELPERS
	 */

	private String extractImages(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}

		final Matcher matcher = IMAGE_PATTERN.matcher(text);
		final StringBuilder images = new StringBuilder();
		while (matcher.find()) {
			images.append("<img=").append(matcher.group(1)).append('>');
		}

		return images.toString();
	}
}
