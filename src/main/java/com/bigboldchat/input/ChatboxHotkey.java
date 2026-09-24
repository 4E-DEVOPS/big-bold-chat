package com.bigboldchat.input;

import com.bigboldchat.Configurations;
import com.bigboldchat.chatbox.ChatboxResizeService;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Owns the chatbox hotkey lifecycle.
 */
public final class ChatboxHotkey {
	private final ClientThread clientThread;
	private final ChatboxResizeService resizeService;
	private final KeyManager keyManager;
	private final Runnable clearChat;
	private final HotkeyListener visibilityHotkeyListener;
	private final HotkeyListener clearHotkeyListener;

	private volatile boolean active;

	public ChatboxHotkey(
			ClientThread clientThread,
			Configurations config,
			ChatboxResizeService resizeService,
			KeyManager keyManager,
			Runnable clearChat) {
		this.clientThread = clientThread;
		this.resizeService = resizeService;
		this.keyManager = keyManager;
		this.clearChat = clearChat;
		this.visibilityHotkeyListener = new HotkeyListener(config::hideChatboxHotkey) {
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

		/*
		 * Route the hotkey through the same clear action used by ::clear / ::cls so
		 * keyboard and command behavior cannot drift apart.
		 */
		this.clearHotkeyListener = new HotkeyListener(config::clearChatHotkey) {
			@Override
			public void hotkeyPressed() {
				if (!active) {
					return;
				}

				clientThread.invokeLater(() -> {
					if (active) {
						ChatboxHotkey.this.clearChat.run();
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
		keyManager.registerKeyListener(visibilityHotkeyListener);
		keyManager.registerKeyListener(clearHotkeyListener);
	}

	public void deactivate() {
		if (!active) {
			return;
		}

		active = false;
		keyManager.unregisterKeyListener(visibilityHotkeyListener);
		keyManager.unregisterKeyListener(clearHotkeyListener);
	}
}
