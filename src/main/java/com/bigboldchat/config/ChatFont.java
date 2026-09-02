package com.bigboldchat.config;

import net.runelite.api.FontID;

public enum ChatFont
{
    PLAIN_11("Plain 11", FontID.PLAIN_11),
    PLAIN_12("Plain 12 (Default)", FontID.PLAIN_12),
    BOLD_12("Bold 12", FontID.BOLD_12),

    QUILL_8("Quill Small", FontID.QUILL_8),
    QUILL_MEDIUM("Quill Medium", FontID.QUILL_MEDIUM),
    BARBARIAN("Barbarian", FontID.BARBARIAN),

    TAHOMA_11("Tahoma 11", FontID.TAHOMA_11),
    VERDANA_11("Verdana 11", FontID.VERDANA_11),
    VERDANA_11_BOLD("Verdana 11 Bold", FontID.VERDANA_11_BOLD),
    VERDANA_13("Verdana 13", FontID.VERDANA_13),
    VERDANA_13_BOLD("Verdana 13 Bold", FontID.VERDANA_13_BOLD),
    VERDANA_15("Verdana 15", FontID.VERDANA_15);

    private final String displayName;
    private final int fontId;

    ChatFont(String displayName, int fontId)
    {
        this.displayName = displayName;
        this.fontId = fontId;
    }

    public int getFontId()
    {
        return fontId;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}