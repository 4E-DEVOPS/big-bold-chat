package com.bigboldchat.chatbox;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Targeted widget-tree helpers for the chatbox.
 */
final class ChatboxWidgets {
	private ChatboxWidgets() {
	}

	/**
	 * Revalidate mounted chatbox descendants without walking nested
	 * interfaces or descending through the scroll area.
	 *
	 * @return number of widgets revalidated
	 */
	static int revalidateChildren(Widget widget) {
		if (widget == null) {
			return 0;
		}

		return revalidateAll(widget.getStaticChildren()) + revalidateAll(widget.getDynamicChildren());
	}

	private static int revalidateAll(Widget[] children) {
		if (children == null) {
			return 0;
		}

		int count = 0;

		for (Widget child : children) {
			if (child == null) {
				continue;
			}

			child.revalidate();

			count++;

			if (child.getId() == InterfaceID.Chatbox.SCROLLAREA) {
				continue;
			}

			count += revalidateChildren(child);
		}

		return count;
	}
}
