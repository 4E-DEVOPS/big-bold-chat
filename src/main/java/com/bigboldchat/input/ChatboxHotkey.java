package com.bigboldchat.input;

import com.bigboldchat.Configurations;
import com.bigboldchat.chatbox.ChatboxCoreService;

import java.awt.event.KeyEvent;
import java.util.function.Supplier;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Owns the chatbox hotkey lifecycle.
 */
public final class ChatboxHotkey {
	private final ClientThread clientThread;
	private final ChatboxCoreService resizeService;
	private final KeyManager keyManager;
	private final Runnable clearChat;
	private final HotkeyListener visibilityHotkeyListener;
	private final HotkeyListener clearHotkeyListener;
	private final KeyListener overlayHotkeyListener;
	private final Supplier<Keybind> overlayHotkey;
	private volatile Keybind pressedOverlayHotkey;

	private volatile boolean active;

	public ChatboxHotkey(ClientThread clientThread, Configurations config, ChatboxCoreService resizeService, KeyManager keyManager, Runnable clearChat, Supplier<Keybind> overlayHotkey) {
		this.clientThread = clientThread;
		this.resizeService = resizeService;
		this.keyManager = keyManager;
		this.clearChat = clearChat;
		this.overlayHotkey = overlayHotkey;
		// Observe only; HotkeyListener would consume native non-modifier hotkeys.
		this.overlayHotkeyListener = new KeyListener() {
			@Override
			public void keyPressed(KeyEvent event) {
				final Keybind binding = overlayHotkey.get();
				if (active && binding != null && binding.matches(event)) {
					pressedOverlayHotkey = binding;
				}
			}

			@Override
			public void keyReleased(KeyEvent event) {
				final Keybind binding = pressedOverlayHotkey;
				if (binding != null && binding.matches(event)) {
					clearOverlayHotkey();
				}
			}

			@Override
			public void keyTyped(KeyEvent event) {
			}

			@Override
			public void focusLost() {
				clearOverlayHotkey();
			}
		};
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

	public boolean overlayHotkeyHeld() {
		final Keybind binding = pressedOverlayHotkey;
		return active && binding != null && binding.equals(overlayHotkey.get());
	}

	public void clearOverlayHotkey() {
		pressedOverlayHotkey = null;
	}

	public void activate() {
		if (active) {
			return;
		}

		active = true;
		keyManager.registerKeyListener(overlayHotkeyListener);
		keyManager.registerKeyListener(visibilityHotkeyListener);
		keyManager.registerKeyListener(clearHotkeyListener);
	}

	public void deactivate() {
		if (!active) {
			return;
		}

		active = false;
		clearOverlayHotkey();
		keyManager.unregisterKeyListener(overlayHotkeyListener);
		keyManager.unregisterKeyListener(visibilityHotkeyListener);
		keyManager.unregisterKeyListener(clearHotkeyListener);
	}
}
