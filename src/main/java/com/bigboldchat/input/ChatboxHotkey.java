package com.bigboldchat.input;

import com.bigboldchat.Configurations;
import com.bigboldchat.chatbox.ChatboxResizeService;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Owns the chatbox visibility hotkey lifecycle.
 */
public final class ChatboxHotkey {
	private final ClientThread clientThread;
	private final ChatboxResizeService resizeService;
	private final KeyManager keyManager;
	private final HotkeyListener hotkeyListener;

	private volatile boolean active;

	public ChatboxHotkey(
			ClientThread clientThread,
			Configurations config,
			ChatboxResizeService resizeService,
			KeyManager keyManager) {
		this.clientThread = clientThread;
		this.resizeService = resizeService;
		this.keyManager = keyManager;
		this.hotkeyListener = new HotkeyListener(config::hideChatboxHotkey) {
			@Override
			public void hotkeyPressed() {
				if (!active) {
					return;
				}

				clientThread.invokeLater(() -> {
					if (active) {
						resizeService.toggleChatPresentation();
					}
				});
			}
		};
	}

	public void activate() {
		if (active) {
			return;
		}

		active = true;
		keyManager.registerKeyListener(hotkeyListener);
	}

	public void deactivate() {
		if (!active) {
			return;
		}

		active = false;
		keyManager.unregisterKeyListener(hotkeyListener);
	}
}
