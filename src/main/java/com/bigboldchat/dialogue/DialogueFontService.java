package com.bigboldchat.dialogue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.bigboldchat.Configurations;
import com.bigboldchat.config.ChatFont;
import com.bigboldchat.config.DialogueFont;

import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * Applies configured dialogue and title fonts to prompt widgets while
 * retaining each widget's native font and line height for restoration.
 */
public final class DialogueFontService {
	private static final ChatFont NATIVE_DIALOGUE_FONT = ChatFont.QUILL_8;

	private final Client client;
	private final Configurations config;
	private final Map<Widget, FontState> states = new IdentityHashMap<>();

	public DialogueFontService(Client client, Configurations config) {
		this.client = client;
		this.config = config;
	}

	public void onScriptPostFired(ScriptPostFired event) {
		if (event != null) {
			sync();
		}
	}

	public void sync() {
		final DialogueFont configuredFont = selectedFont();
		final ChatFont selectedFont = configuredFont.getChatFont();
		final ChatFont titleFont = configuredFont.getTitleFont();
		final Map<Widget, ChatFont> targets = new IdentityHashMap<>();

		collectOptions(targets, selectedFont, titleFont);
		collectPortrait(targets, InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatLeft.NAME, InterfaceID.ChatLeft.TEXT, InterfaceID.ChatLeft.CONTINUE, selectedFont, titleFont);
		collectPortrait(targets, InterfaceID.ChatRight.UNIVERSE, InterfaceID.ChatRight.NAME, InterfaceID.ChatRight.TEXT, InterfaceID.ChatRight.CONTINUE, selectedFont, titleFont);
		collectMessage(targets, selectedFont);
		collectSprite(targets, selectedFont, titleFont);

		restoreMissing(targets);
		for (Map.Entry<Widget, ChatFont> entry : targets.entrySet()) {
			applyFont(entry.getKey(), entry.getValue());
		}
	}

	public void restoreNativePresentation() {
		for (FontState state : states.values()) {
			restore(state);
		}
		states.clear();
	}

	private DialogueFont selectedFont() {
		final DialogueFont configuredFont = config != null ? config.dialogueFont() : null;
		return configuredFont != null ? configuredFont : DialogueFont.QUILL_8;
	}

	/**
	 * ================================================================
	 * DIALOGUE WIDGET TARGETS
	 * ================================================================
	 */
	private void collectOptions(Map<Widget, ChatFont> targets, ChatFont selectedFont, ChatFont titleFont) {
		final Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (!isPresent(options)) {
			return;
		}

		final Widget[] children = options.getDynamicChildren();
		if (children == null) {
			return;
		}

		final List<Widget> rows = new ArrayList<>();
		for (Widget child : children) {
			if (child != null && child.getType() == WidgetType.TEXT && child.getOriginalWidth() > 0 && child.getOriginalHeight() > 0) {
				rows.add(child);
			}
		}

		// The first row is the title; subsequent rows use the dialogue font.
		rows.sort(Comparator.comparingInt(Widget::getOriginalY));
		for (int i = 0; i < rows.size(); i++) {
			targets.put(rows.get(i), i == 0 ? titleFont : selectedFont);
		}
	}

	private void collectPortrait(Map<Widget, ChatFont> targets, int rootId, int nameId, int textId, int continueId, ChatFont selectedFont, ChatFont titleFont) {
		if (!isPresent(client.getWidget(rootId))) {
			return;
		}

		putIfPresent(targets, client.getWidget(nameId), titleFont);
		putIfPresent(targets, client.getWidget(textId), selectedFont);
		putIfPresent(targets, client.getWidget(continueId), selectedFont);
	}

	private void collectMessage(Map<Widget, ChatFont> targets, ChatFont selectedFont) {
		if (!isPresent(client.getWidget(InterfaceID.Messagebox.UNIVERSE))) {
			return;
		}

		putIfPresent(targets, client.getWidget(InterfaceID.Messagebox.TEXT), selectedFont);
		putIfPresent(targets, client.getWidget(InterfaceID.Messagebox.CONTINUE), selectedFont);
	}

	private void collectSprite(Map<Widget, ChatFont> targets, ChatFont selectedFont, ChatFont titleFont) {
		final Widget item = client.getWidget(InterfaceID.Objectbox.ITEM);
		final Widget text = client.getWidget(InterfaceID.Objectbox.TEXT);
		if (!isPresent(item) && !isPresent(text)) {
			return;
		}

		putIfPresent(targets, text, selectedFont);

		final Widget root = item != null && item.getParent() != null ? item.getParent() : text != null ? text.getParent() : null;
		final Widget continueWidget = findSpriteContinue(root, text);
		putIfPresent(targets, continueWidget, selectedFont);
		putIfPresent(targets, findSpriteTitle(root, text, continueWidget), titleFont);
	}

	/**
	 * ================================================================
	 * FONT APPLICATION & RESTORATION
	 * ================================================================
	 */
	private void restoreMissing(Map<Widget, ChatFont> targets) {
		final Iterator<Map.Entry<Widget, FontState>> iterator = states.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Widget, FontState> entry = iterator.next();
			if (!targets.containsKey(entry.getKey())) {
				restore(entry.getValue());
				iterator.remove();
			}
		}
	}

	// Only revalidate when a font or line-height value actually changes.
	private void applyFont(Widget widget, ChatFont font) {
		FontState state = states.get(widget);
		if (state == null) {
			state = FontState.capture(widget);
			states.put(widget, state);
		} else {
			state.captureNative();
		}

		boolean changed = false;
		if (widget.getFontId() != font.getFontId()) {
			widget.setFontId(font.getFontId());
			changed = true;
		}
		final int targetLineHeight = targetLineHeight(state, widget, font);
		if (widget.getLineHeight() != targetLineHeight) {
			widget.setLineHeight(targetLineHeight);
			changed = true;
		}

		if (changed) {
			widget.revalidate();
		}

		state.appliedFontId = font.getFontId();
		state.appliedLineHeight = targetLineHeight;
	}

	private static int targetLineHeight(FontState state, Widget widget, ChatFont font) {
		if (font == NATIVE_DIALOGUE_FONT) {
			return state.nativeLineHeight;
		}

		final FontTypeFace selectedFont = widget.getFont();
		return selectedFont != null ? Math.max(1, selectedFont.getBaseline()) : Math.max(1, state.nativeFontBaseline);
	}

	private static void restore(FontState state) {
		final Widget widget = state.widget;
		boolean changed = false;
		if (widget.getFontId() != state.nativeFontId) {
			widget.setFontId(state.nativeFontId);
			changed = true;
		}
		if (widget.getLineHeight() != state.nativeLineHeight) {
			widget.setLineHeight(state.nativeLineHeight);
			changed = true;
		}
		if (changed) {
			widget.revalidate();
		}
	}

	/**
	 * ================================================================
	 * SPRITE WIDGET HELPERS
	 * ================================================================
	 */
	private static Widget findSpriteContinue(Widget root, Widget messageText) {
		if (root == null) {
			return null;
		}

		Widget fallback = null;
		final Widget[] dynamic = root.getDynamicChildren();
		if (dynamic != null) {
			for (Widget child : dynamic) {
				if (child == null || child == messageText || child.getType() != WidgetType.TEXT) {
					continue;
				}

				final String text = child.getText();
				if (text != null && text.toLowerCase().contains("click here to continue")) {
					return child;
				}

				if (fallback == null && child.getOriginalHeight() > 0) {
					fallback = child;
				}
			}
		}
		return fallback;
	}

	private static Widget findSpriteTitle(Widget root, Widget messageText, Widget continueWidget) {
		if (root == null) {
			return null;
		}

		Widget title = findSpriteTitle(root.getDynamicChildren(), messageText, continueWidget);
		return title != null ? title : findSpriteTitle(root.getStaticChildren(), messageText, continueWidget);
	}

	private static Widget findSpriteTitle(Widget[] children, Widget messageText, Widget continueWidget) {
		if (children == null) {
			return null;
		}

		Widget title = null;
		for (Widget child : children) {
			if (child == null || child == messageText || child == continueWidget || child.getType() != WidgetType.TEXT || child.getOriginalWidth() <= 0 || child.getOriginalHeight() <= 0 || child.getText() == null || child.getText().isEmpty()) {
				continue;
			}

			if (title == null || child.getOriginalY() < title.getOriginalY()) {
				title = child;
			}
		}
		return title;
	}

	private static void putIfPresent(Map<Widget, ChatFont> targets, Widget widget, ChatFont font) {
		if (isPresent(widget)) {
			targets.put(widget, font);
		}
	}

	private static boolean isPresent(Widget widget) {
		return widget != null && widget.getParent() != null;
	}

	private static int fontBaseline(Widget widget) {
		final FontTypeFace font = widget != null ? widget.getFont() : null;
		return font != null ? Math.max(1, font.getBaseline()) : 16;
	}

	private static final class FontState {
		private final Widget widget;
		private int nativeFontId;
		private int nativeLineHeight;
		private int nativeFontBaseline;
		private Integer appliedFontId;
		private Integer appliedLineHeight;

		private FontState(Widget widget) {
			this.widget = widget;
			nativeFontId = widget.getFontId();
			nativeLineHeight = widget.getLineHeight();
			nativeFontBaseline = fontBaseline(widget);
		}

		/*
		 * RuneLite may update a reused widget during a script rebuild. Only
		 * values that differ from ChatXL's last write replace the native backup.
		 */
		private void captureNative() {
			if (appliedFontId == null || widget.getFontId() != appliedFontId) {
				nativeFontId = widget.getFontId();
				nativeFontBaseline = fontBaseline(widget);
			}
			if (appliedLineHeight == null || widget.getLineHeight() != appliedLineHeight) {
				nativeLineHeight = widget.getLineHeight();
			}
		}

		private static FontState capture(Widget widget) {
			return new FontState(widget);
		}
	}
}
