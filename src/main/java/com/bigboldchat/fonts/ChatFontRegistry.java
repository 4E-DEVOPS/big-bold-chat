package com.bigboldchat.fonts;

import com.bigboldchat.config.ChatFont;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Central registry for Chat XL font profiles.
 *
 * Layout code should request a profile from this registry rather than
 * containing per-font switch statements or tuning constants.
 */
public final class ChatFontRegistry
{
    private static final Map<ChatFont, ChatFontProfile> PROFILES;

    static
    {
        final EnumMap<ChatFont, ChatFontProfile> profiles =
                new EnumMap<>(
                        ChatFont.class);

        register(
                profiles,
                new Plain11());

        register(
                profiles,
                new Plain12());

        register(
                profiles,
                new Bold12());

        register(
                profiles,
                new QuillSmall());

        register(
                profiles,
                new QuillMedium());

        register(
                profiles,
                new Barbarian());

        register(
                profiles,
                new Tahoma11());

        register(
                profiles,
                new Verdana11());

        register(
                profiles,
                new Verdana11Bold());

        register(
                profiles,
                new Verdana13());

        register(
                profiles,
                new Verdana13Bold());

        register(
                profiles,
                new Verdana15());

        PROFILES =
                Collections.unmodifiableMap(
                        profiles);
    }

    private ChatFontRegistry()
    {
        /*
         * Utility class.
         */
    }

    /**
     * Returns the tuning profile for the selected ChatFont.
     *
     * Plain 12 is used as the safe fallback for null or an
     * unexpectedly unregistered font.
     */
    public static ChatFontProfile get(
            ChatFont chatFont)
    {
        if (chatFont == null)
        {
            return PROFILES.get(
                    ChatFont.PLAIN_12);
        }

        final ChatFontProfile profile =
                PROFILES.get(
                        chatFont);

        if (profile != null)
        {
            return profile;
        }

        return PROFILES.get(
                ChatFont.PLAIN_12);
    }

    private static void register(
            EnumMap<ChatFont, ChatFontProfile> profiles,
            ChatFontProfile profile)
    {
        if (profiles == null
                || profile == null
                || profile.getChatFont() == null)
        {
            return;
        }

        profiles.put(
                profile.getChatFont(),
                profile);
    }
}