package com.bigboldchat.chatbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import net.runelite.api.FontID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * Identifies the bottom-visible chat message by its rendered components, not its recycled row slot.
 */
final class ChatScrollAnchor {
	private static final int ROW_STRIDE = 4;
	private static final Pattern STYLE_TAGS = Pattern.compile("(?i)</?(?:col|shad|u|str)(?:=[^>]*)?>");
	private static final Pattern ICON_GAP = Pattern.compile("(?i)(<img=\\d+>)[ \t\u00a0]*");

	private final Row source;
	private final String[] older;
	private final String[] newer;
	private final int bottomOffset;
	private final boolean firstLineVisible;
	private final int view;

	private ChatScrollAnchor(Row source, String[] older, String[] newer, int bottomOffset, boolean firstLineVisible, int view) {
		this.source = source;
		this.older = older;
		this.newer = newer;
		this.bottomOffset = bottomOffset;
		this.firstLineVisible = firstLineVisible;
		this.view = view;
	}

	static ChatScrollAnchor capture(Widget area, int view) {
		final List<Row> rows = rows(area);
		if (rows == null) {
			return null;
		}
		final int top = area.getScrollY();
		final int bottom = top + area.getHeight();
		Row anchor = null;
		for (Row row : rows) {
			if (row.top >= bottom || row.bottom <= top) {
				continue;
			}
			if (anchor == null || row.top > anchor.top) {
				anchor = row;
			} else if (row.top == anchor.top) {
				return null;
			}
		}
		if (anchor == null || anchor.key == null || uniqueRow(rows, anchor.key) != anchor) {
			return null;
		}
		final int index = rows.indexOf(anchor);
		return new ChatScrollAnchor(anchor, neighbor(rows, index + 1), neighbor(rows, index - 1), bottom - anchor.bottom, anchor.top >= top, view);
	}

	Integer resolve(Widget area, int view, boolean hopping, boolean fontChange) {
		if (this.view != view) {
			return null;
		}
		final List<Row> rows = rows(area);
		if (rows == null) {
			return null;
		}
		// Font-equivalent matches must be unique, even when an exact match exists.
		final Row row = uniqueRow(rows, source.key, fontChange && !hopping, source.fontId);
		if (row == null) {
			return null;
		}
		if (hopping) {
			// A recreated message has no durable widget/UID identity. Require adjacent retained history as corroboration.
			final int index = rows.indexOf(row);
			if (!(older != null && Arrays.equals(older, neighbor(rows, index + 1))) && !(newer != null && Arrays.equals(newer, neighbor(rows, index - 1)))) {
				return null;
			}
		}
		final int lineHeight = source.lineHeight;
		final int rowHeight = source.bottom - source.top;
		final boolean fontChanged = fontChange && (row.fontId != source.fontId || row.lineHeight != lineHeight);
		final int height = area.getHeight();
		final int newRowHeight = row.bottom - row.top;
		final boolean reflow = fontChanged || newRowHeight != rowHeight;
		final int firstLineHeight = Math.min(newRowHeight, Math.max(1, row.lineHeight));
		final int firstLineBottom = row.top + firstLineHeight;
		int desiredBottom = row.bottom + bottomOffset;
		int nextTop = Integer.MAX_VALUE;
		if (reflow) {
			if (lineHeight <= 0 || row.lineHeight <= 0) {
				return null;
			}
			for (Row next : rows) {
				if (next != row && next.top >= row.top) {
					nextTop = Math.min(nextTop, next.top);
				}
			}
			// Keep the message start at its previous bottom-relative position, not its wrapped end.
			// When reading inside a long message, keep the interior offset instead.
			int offset = firstLineVisible ? bottomOffset + rowHeight - Math.min(rowHeight, Math.max(1, lineHeight)) : bottomOffset;
			if (fontChanged) {
				offset = Math.round((float) offset * row.lineHeight / lineHeight);
			}
			desiredBottom = (firstLineVisible ? firstLineBottom : row.bottom) + offset;
			final int maxBottom = Math.min(nextTop, firstLineVisible ? row.top + height : row.bottom);
			// An intersecting pixel is not a readable line. Reject impossible geometry.
			if (maxBottom < firstLineBottom || height < firstLineHeight) {
				return null;
			}
			desiredBottom = Math.max(firstLineBottom, Math.min(maxBottom, desiredBottom));
		}
		final int clamped = Math.max(0, Math.min(area.getScrollHeight() - height, desiredBottom - height));
		final int viewportBottom = clamped + height;
		final boolean visible = row.top < viewportBottom && row.bottom > clamped;
		final boolean readable = !reflow || Math.min(row.bottom, viewportBottom) - Math.max(row.top, clamped) >= firstLineHeight && viewportBottom <= nextTop && (!firstLineVisible || clamped <= row.top && viewportBottom >= firstLineBottom);
		return visible && readable ? clamped : null;
	}

	private static boolean matches(Row row, String[] key, boolean fontChange, int fontId) {
		if (!fontChange || row.fontId == fontId) {
			return Arrays.equals(row.key, key);
		}
		final boolean verdana = fontId == FontID.VERDANA_13_BOLD || row.fontId == FontID.VERDANA_13_BOLD;
		for (int i = 0; i < ROW_STRIDE; i++) {
			if (!Objects.equals(fontKey(key[i], i, verdana), fontKey(row.key[i], i, verdana))) {
				return false;
			}
		}
		return true;
	}

	private static String fontKey(String text, int component, boolean verdana) {
		if (text == null || component == 3) {
			return text;
		}
		if (verdana) {
			text = text.replace(':', '-');
		}
		return component != 1 && text.contains("<img=") ? ICON_GAP.matcher(text).replaceAll("$1") : text;
	}

	private static String[] neighbor(List<Row> rows, int index) {
		if (index < 0 || index >= rows.size()) {
			return null;
		}
		final Row row = rows.get(index);
		return row.key != null && uniqueRow(rows, row.key) == row ? row.key : null;
	}

	private static Row uniqueRow(List<Row> rows, String[] key) {
		return uniqueRow(rows, key, false, 0);
	}

	private static Row uniqueRow(List<Row> rows, String[] key, boolean fontChange, int fontId) {
		Row match = null;
		for (Row row : rows) {
			if (row.key == null || !matches(row, key, fontChange, fontId)) {
				continue;
			}
			if (match != null) {
				return null;
			}
			match = row;
		}
		return match;
	}

	private static List<Row> rows(Widget area) {
		if (area == null || area.isHidden() || area.getWidth() <= 0 || area.getHeight() <= 0 || area.getScrollHeight() <= 0) {
			return null;
		}
		final Widget[] children = area.getChildren();
		if (children == null || children.length == 0 || children.length % ROW_STRIDE != 0) {
			return null;
		}
		final List<Row> rows = new ArrayList<>();
		for (int i = 0; i < children.length; i += ROW_STRIDE) {
			int top = Integer.MAX_VALUE;
			int bottom = Integer.MIN_VALUE;
			String[] key = null;
			int fontId = -1;
			int lineHeight = 0;
			for (int j = 0; j < ROW_STRIDE; j++) {
				final Widget child = children[i + j];
				if (child == null || child.isSelfHidden() || child.getWidth() <= 0 || child.getHeight() <= 0) {
					continue;
				}
				if (child.getIndex() != i + j || child.getParentId() != area.getId()) {
					return null;
				}
				if (j == 3 && child.getType() == WidgetType.GRAPHIC) {
					if (key != null && child.getSpriteId() >= 0) {
						key[j] = Integer.toString(child.getSpriteId());
					}
					continue;
				}
				if (child.getType() != WidgetType.TEXT) {
					return null;
				}
				final String text = normalize(child.getText());
				if (text.isEmpty()) {
					continue;
				}
				if (j == 3) {
					return null;
				}
				if (key == null) {
					key = new String[ROW_STRIDE];
				}
				key[j] = text;
				if (j == 1) {
					fontId = child.getFontId();
					lineHeight = child.getLineHeight();
				}
				top = Math.min(top, child.getRelativeY());
				bottom = Math.max(bottom, child.getRelativeY() + child.getHeight());
			}
			if (top == Integer.MAX_VALUE || bottom <= 0 || top >= area.getScrollHeight()) {
				continue;
			}
			// Unknown or incomplete rows may be visible, but cannot become anchors.
			rows.add(new Row(top, bottom, key != null && key[1] != null ? key : null, fontId, lineHeight));
		}
		return rows;
	}

	private static String normalize(String text) {
		if (text == null) {
			return "";
		}
		return (text.indexOf('<') >= 0 ? STYLE_TAGS.matcher(text).replaceAll("") : text).replace('\u00a0', ' ').trim();
	}

	private static final class Row {
		private final int top;
		private final int bottom;
		private final String[] key;
		private final int fontId;
		private final int lineHeight;

		private Row(int top, int bottom, String[] key, int fontId, int lineHeight) {
			this.top = top;
			this.bottom = bottom;
			this.key = key;
			this.fontId = fontId;
			this.lineHeight = lineHeight;
		}
	}
}
