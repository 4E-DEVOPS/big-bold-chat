package com.bigboldchat.config;

import com.bigboldchat.Configurations;
import com.bigboldchat.chatbox.ChatboxResizeService;
import com.bigboldchat.debug.PerformanceMetrics;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;

/**
 * Owns chatbox-size configuration changes.
 */
public final class ChatboxConfigHandler
{
    private static final String CONFIG_GROUP = "bigboldchat";
    private static final String WIDTH_KEY = "chatboxWidth";
    private static final String HEIGHT_KEY = "chatboxHeight";

    private final Client client;
    private final ClientThread clientThread;
    private final Configurations config;
    private final ChatboxResizeService resizeService;
    private final PerformanceMetrics performanceMetrics;

    private boolean active = true;

    public ChatboxConfigHandler(
            Client client,
            ClientThread clientThread,
            Configurations config,
            ChatboxResizeService resizeService,
            PerformanceMetrics performanceMetrics)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.resizeService = resizeService;
        this.performanceMetrics = performanceMetrics;
    }

    public boolean onConfigChanged(ConfigChanged event)
    {
        if (!active || event == null || !CONFIG_GROUP.equals(event.getGroup()))
        {
            return false;
        }

        if (WIDTH_KEY.equals(event.getKey()))
        {
            clientThread.invokeLater(() -> {
                if (!active)
                {
                    return;
                }

                final ChatboxResizeService.ResizeResult result = applyConfiguredSize();

                if (result == null || !result.isApplied() || !result.isWidthChanged())
                {
                    return;
                }

                if (performanceMetrics != null)
                {
                    performanceMetrics.recordRefreshChat(PerformanceMetrics.RefreshReason.WIDTH_CHANGED);
                }

                client.refreshChat();
            });

            return true;
        }

        if (HEIGHT_KEY.equals(event.getKey()))
        {
            clientThread.invokeLater(this::applyConfiguredSize);
            return true;
        }

        return false;
    }

    public void deactivate()
    {
        active = false;
    }

    public ChatboxResizeService.ResizeResult applyConfiguredSize()
    {
        if (!active || resizeService == null || config == null)
        {
            return null;
        }

        return resizeService.applySize(
                config.chatboxWidth(),
                config.chatboxHeight());
    }
}
