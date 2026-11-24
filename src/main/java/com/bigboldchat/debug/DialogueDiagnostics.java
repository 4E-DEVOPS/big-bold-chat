package com.bigboldchat.debug;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.runelite.api.events.CommandExecuted;

/**
 * Provides optional, read-only dialogue diagnostics without
 * controlling fitting, restoration, or the gameplay fitment overlay.
 */
public final class DialogueDiagnostics {
	private static final Logger LOG = LoggerFactory.getLogger(DialogueDiagnostics.class);
	private static final String COMMAND = "debug-dialogue";

	private final Map<String, String> lastReports = new HashMap<>();
	private boolean armed;

	public synchronized boolean onCommandExecuted(CommandExecuted event) {
		if (event == null || !COMMAND.equalsIgnoreCase(event.getCommand())) {
			return false;
		}

		armed = !armed;
		lastReports.clear();
		LOG.debug("[Chat XL][Dialogue Diagnostic] {}", armed
				? "ARMED"
				: "DISARMED");
		return true;
	}

	public synchronized boolean isEnabled() {
		return armed && LOG.isDebugEnabled();
	}

	public synchronized void reset(String channel) {
		lastReports.remove(channel);
	}

	// Report only changes to a channel's observed state while diagnostics are armed.
	public synchronized void changed(String channel, String label, String identity, String report) {
		if (!isEnabled() || lastReports.containsKey(channel) && Objects.equals(identity, lastReports.get(channel))) {
			return;
		}

		lastReports.put(channel, identity);
		LOG.debug("[Chat XL][{}] {}", label, report);
	}

	public synchronized void event(String label, String report) {
		if (isEnabled()) {
			LOG.debug("[Chat XL][{}] {}", label, report);
		}
	}

	public synchronized void deactivate() {
		armed = false;
		lastReports.clear();
	}
}