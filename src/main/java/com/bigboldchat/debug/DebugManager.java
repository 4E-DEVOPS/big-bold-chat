package com.bigboldchat.debug;

import com.bigboldchat.Configurations;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.events.ConfigChanged;

/**
 * Owns optional Chat XL diagnostics and routes debug-only events and commands.
 */
public final class DebugManager
{
    private final Client client;
    private final Configurations config;
    private final ChatMessageTests chatMessageTests;

    private ChatboxDiagnostics chatboxDiagnostics;
    private FontDiagnostics fontDiagnostics;
    private PerformanceMetrics performanceMetrics;

    @Inject
    public DebugManager(Client client, Configurations config, ChatMessageTests chatMessageTests)
    {
        this.client = client;
        this.config = config;
        this.chatMessageTests = chatMessageTests;
    }

    public void activate(PerformanceMetrics performanceMetrics)
    {
        deactivate();

        this.performanceMetrics = performanceMetrics;
        chatboxDiagnostics = new ChatboxDiagnostics(client);
        fontDiagnostics = new FontDiagnostics(client, config);
    }

    public void deactivate()
    {
        if (fontDiagnostics != null)
        {
            fontDiagnostics.reset();
        }

        if (chatboxDiagnostics != null)
        {
            chatboxDiagnostics.reset();
        }

        fontDiagnostics = null;
        chatboxDiagnostics = null;
        performanceMetrics = null;
    }

    public boolean onCommandExecuted(CommandExecuted event)
    {
        if (chatboxDiagnostics != null && chatboxDiagnostics.onCommandExecuted(event))
        {
            return true;
        }

        if (fontDiagnostics != null && fontDiagnostics.onCommandExecuted(event))
        {
            return true;
        }

        if (performanceMetrics != null && performanceMetrics.onCommandExecuted(event))
        {
            return true;
        }

        return chatMessageTests != null && chatMessageTests.onCommandExecuted(event);
    }

    public void onConfigChanged(ConfigChanged event)
    {
        if (fontDiagnostics != null)
        {
            fontDiagnostics.onConfigChanged(event);
        }
    }

    public void onChatboxScriptPreFired(ScriptPreFired event)
    {
        if (chatboxDiagnostics != null)
        {
            chatboxDiagnostics.onScriptPreFired(event);
        }
    }

    public void onFontScriptPreFired(ScriptPreFired event)
    {
        if (fontDiagnostics != null)
        {
            fontDiagnostics.onScriptPreFired(event);
        }
    }

    public void onChatboxScriptPostFired(ScriptPostFired event)
    {
        if (chatboxDiagnostics != null)
        {
            chatboxDiagnostics.onScriptPostFired(event);
        }
    }

    public void onFontScriptPostFired(ScriptPostFired event)
    {
        if (fontDiagnostics != null)
        {
            fontDiagnostics.onScriptPostFired(event);
        }
    }

    public void reportPerformanceIfDue()
    {
        if (performanceMetrics != null)
        {
            performanceMetrics.reportIfDue();
        }
    }
}
