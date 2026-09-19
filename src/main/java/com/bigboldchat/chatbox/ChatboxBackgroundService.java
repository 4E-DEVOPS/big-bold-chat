package com.bigboldchat.chatbox;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;

/**
 * Owns resizable chatbox background and frame geometry.
 *
 * Opaque chat stretches the parchment while preserving its baked bevel.
 * Transparent chat redistributes its gradient bands across the chat body.
 */
final class ChatboxBackgroundService
{
    private static final int BAKED_BORDER = 8;
    private static final int CORNER_BLEED_TRIM = 1;
    private static final int CORNER_SIZE = 32;
    private static final int BORDER_OFFSET = 12;

    private static final int[] BORDER_SPRITES =
            {
                    SpriteID.V2StoneBorders.SIDE_PANEL_CORNER_TOP_LEFT,
                    SpriteID.V2StoneBorders.SIDE_PANEL_CORNER_TOP_RIGHT,
                    SpriteID.V2StoneBorders.SIDE_PANEL_CORNER_BOTTOM_LEFT,
                    SpriteID.V2StoneBorders.SIDE_PANEL_CORNER_BOTTOM_RIGHT,
                    SpriteID.V2StoneBorders.SIDE_PANEL_EDGE_TOP,
                    SpriteID.V2StoneBorders.SIDE_PANEL_EDGE_LEFT,
                    SpriteID.V2StoneBorders.SIDE_PANEL_EDGE_BOTTOM,
                    SpriteID.V2StoneBorders.SIDE_PANEL_EDGE_RIGHT
            };

    private final Client client;

    private Widget[] borderPieces;
    private Widget trimmedBackground;
    private Widget zoomedBody;

    ChatboxBackgroundService(Client client)
    {
        this.client = client;
    }

    /*
     * ================================================================
     * BACKGROUND
     * ================================================================
     */

    Result apply(Widget chatArea, int width, int bodyHeight)
    {
        final Result result = new Result();
        final Widget background = client.getWidget(InterfaceID.Chatbox.CHAT_BACKGROUND);

        if (background == null)
        {
            result.add(syncFrame(chatArea, null));
            return result;
        }

        final Widget body = getBackgroundBody(background);

        if (isParchment(body))
        {
            result.add(zoomParchment(body, width, bodyHeight));
            result.add(trimBackground(background));
            result.add(syncFrame(chatArea, body));
            return result;
        }

        result.add(restoreZoom(body));
        result.add(restoreTrim(background));
        result.add(stackGradientBands(background, bodyHeight));
        result.add(removeFrame(chatArea));

        return result;
    }

    Result restore(Widget chatArea)
    {
        final Result result = new Result();
        final Widget background = client.getWidget(InterfaceID.Chatbox.CHAT_BACKGROUND);
        final Widget body = background != null
                ? getBackgroundBody(background)
                : null;

        result.add(removeFrame(chatArea));

        if (background == null)
        {
            trimmedBackground = null;
            zoomedBody = null;
            return result;
        }

        result.add(restoreTrim(background));
        result.add(restoreZoom(body));

        if (!isParchment(body))
        {
            result.add(stackGradientBands(background, ChatboxGeometry.NATIVE_BODY_HEIGHT));
        }

        return result;
    }

    @SuppressWarnings("deprecation")
    private Result zoomParchment(Widget body, int targetWidth, int targetHeight)
    {
        final Result result = new Result();
        final int overscanX = bevelOverscan(targetWidth, ChatboxGeometry.NATIVE_WIDTH);
        final int overscanY = bevelOverscan(targetHeight, ChatboxGeometry.NATIVE_BODY_HEIGHT);
        final int width = targetWidth + overscanX * 2;
        final int height = targetHeight + overscanY * 2;
        final int x = body.getOriginalX() - overscanX;
        final int y = body.getOriginalY() - overscanY;

        if (body.getWidth() != width)
        {
            body.setWidth(width);
            result.mutations++;
        }

        if (body.getHeight() != height)
        {
            body.setHeight(height);
            result.mutations++;
        }

        if (body.getRelativeX() != x || body.getRelativeY() != y)
        {
            body.setForcedPosition(x, y);
            result.mutations++;
        }

        if (body.getSpriteTiling())
        {
            body.setSpriteTiling(false);
            result.mutations++;
        }

        zoomedBody = body;
        return result;
    }

    private Result trimBackground(Widget background)
    {
        final Result result = new Result();
        boolean changed = false;

        if (background.getWidth() != CORNER_BLEED_TRIM || background.getHeight() != CORNER_BLEED_TRIM)
        {
            background.setSize(CORNER_BLEED_TRIM, CORNER_BLEED_TRIM);
            result.mutations++;
            changed = true;
        }

        if (background.getRelativeX() != 0 || background.getRelativeY() != CORNER_BLEED_TRIM)
        {
            background.setForcedPosition(0, CORNER_BLEED_TRIM);
            result.mutations++;
            changed = true;
        }

        if (changed)
        {
            background.revalidate();
            result.revalidates++;
        }

        trimmedBackground = background;
        return result;
    }

    private Result restoreTrim(Widget background)
    {
        final Result result = new Result();

        if (trimmedBackground != background)
        {
            trimmedBackground = null;
            return result;
        }

        background.setSize(0, 0);
        background.setForcedPosition(-1, -1);
        background.revalidate();

        result.mutations += 2;
        result.revalidates++;
        trimmedBackground = null;

        return result;
    }

    private Result restoreZoom(Widget currentBody)
    {
        final Result result = new Result();

        if (zoomedBody == null)
        {
            return result;
        }

        if (zoomedBody != currentBody)
        {
            zoomedBody = null;
            return result;
        }

        zoomedBody.setSpriteTiling(true);
        zoomedBody.setForcedPosition(-1, -1);
        zoomedBody.revalidate();

        result.mutations += 2;
        result.revalidates++;
        zoomedBody = null;

        return result;
    }

    private Result stackGradientBands(Widget background, int targetHeight)
    {
        final Result result = new Result();
        final Widget[] bands = background.getDynamicChildren();

        if (bands == null
                || bands.length == 0
                || background.getHeight() != targetHeight
                || targetHeight < bands.length)
        {
            return result;
        }

        final int bandHeight = targetHeight / bands.length;

        for (int i = 0; i < bands.length; i++)
        {
            final Widget band = bands[i];

            if (band == null || band.getHeightMode() != WidgetSizeMode.ABSOLUTE)
            {
                continue;
            }

            final int y = bandHeight * i;
            final int height = i == bands.length - 1
                    ? targetHeight - y
                    : bandHeight;

            boolean changed = false;

            if (band.getOriginalY() != y)
            {
                band.setOriginalY(y);
                result.mutations++;
                changed = true;
            }

            if (band.getOriginalHeight() != height)
            {
                band.setOriginalHeight(height);
                result.mutations++;
                changed = true;
            }

            if (changed)
            {
                band.revalidate();
                result.revalidates++;
            }
        }

        return result;
    }

    private static int bevelOverscan(int target, int nativeSize)
    {
        final int denominator = nativeSize - 2 * BAKED_BORDER;

        if (denominator <= 0)
        {
            return BAKED_BORDER;
        }

        return (BAKED_BORDER * target + denominator - 1) / denominator;
    }

    private static Widget getBackgroundBody(Widget background)
    {
        final Widget[] dynamic = background.getDynamicChildren();

        return dynamic != null && dynamic.length > 0
                ? dynamic[0]
                : null;
    }

    private static boolean isParchment(Widget body)
    {
        return body != null && body.getType() == WidgetType.GRAPHIC;
    }

    /*
     * ================================================================
     * FRAME
     * ================================================================
     */

    private Result syncFrame(Widget chatArea, Widget parchment)
    {
        final Result result = new Result();

        if (chatArea == null)
        {
            borderPieces = null;
            return result;
        }

        if (parchment == null)
        {
            result.add(syncFrameVisibility(true));
            return result;
        }

        if (!framePresent(chatArea))
        {
            result.add(removeFrame(chatArea));
            result.add(createFrame(chatArea));
        }

        if (borderPieces == null)
        {
            return result;
        }

        final int width = chatArea.getWidth();
        final int height = chatArea.getHeight();
        final int innerWidth = Math.max(0, width - 2 * CORNER_SIZE);
        final int innerHeight = Math.max(0, height - 2 * CORNER_SIZE);

        final int[][] rectangles =
                {
                        {0, 0, CORNER_SIZE, CORNER_SIZE},
                        {width - CORNER_SIZE, 0, CORNER_SIZE, CORNER_SIZE},
                        {0, height - CORNER_SIZE, CORNER_SIZE, CORNER_SIZE},
                        {width - CORNER_SIZE, height - CORNER_SIZE, CORNER_SIZE, CORNER_SIZE},
                        {CORNER_SIZE, -BORDER_OFFSET - 1, innerWidth, CORNER_SIZE},
                        {-BORDER_OFFSET - 1, CORNER_SIZE, CORNER_SIZE, innerHeight},
                        {CORNER_SIZE, height - CORNER_SIZE + BORDER_OFFSET, innerWidth, CORNER_SIZE},
                        {width - CORNER_SIZE + BORDER_OFFSET, CORNER_SIZE, CORNER_SIZE, innerHeight}
                };

        for (int i = 0; i < borderPieces.length; i++)
        {
            final Widget piece = borderPieces[i];
            final int[] rectangle = rectangles[i];
            boolean changed = false;

            if (piece.getOriginalX() != rectangle[0])
            {
                piece.setOriginalX(rectangle[0]);
                result.mutations++;
                changed = true;
            }

            if (piece.getOriginalY() != rectangle[1])
            {
                piece.setOriginalY(rectangle[1]);
                result.mutations++;
                changed = true;
            }

            if (piece.getOriginalWidth() != rectangle[2] || piece.getOriginalHeight() != rectangle[3])
            {
                piece.setSize(rectangle[2], rectangle[3]);
                result.mutations++;
                changed = true;
            }

            if (changed)
            {
                piece.revalidate();
                result.revalidates++;
            }
        }

        result.add(syncFrameVisibility(parchment.isHidden()));
        return result;
    }

    private Result createFrame(Widget chatArea)
    {
        final Result result = new Result();

        borderPieces = new Widget[BORDER_SPRITES.length];

        for (int i = 0; i < BORDER_SPRITES.length; i++)
        {
            final Widget piece = chatArea.createChild(-1, WidgetType.GRAPHIC);

            piece.setSpriteId(BORDER_SPRITES[i]);
            piece.setSpriteTiling(true);
            borderPieces[i] = piece;
            result.mutations++;
        }

        return result;
    }

    private Result removeFrame(Widget chatArea)
    {
        final Result result = new Result();

        if (borderPieces == null)
        {
            return result;
        }

        final Widget[] children = chatArea != null
                ? chatArea.getChildren()
                : null;

        if (children != null)
        {
            for (Widget piece : borderPieces)
            {
                if (piece == null)
                {
                    continue;
                }

                final int index = piece.getIndex();

                if (index >= 0 && index < children.length && children[index] == piece)
                {
                    children[index] = null;
                    result.mutations++;
                }
            }
        }

        borderPieces = null;
        return result;
    }

    private Result syncFrameVisibility(boolean hidden)
    {
        final Result result = new Result();

        if (borderPieces == null)
        {
            return result;
        }

        for (Widget piece : borderPieces)
        {
            if (piece != null && piece.isSelfHidden() != hidden)
            {
                piece.setHidden(hidden);
                result.mutations++;
            }
        }

        return result;
    }

    private boolean framePresent(Widget chatArea)
    {
        if (borderPieces == null)
        {
            return false;
        }

        final Widget[] children = chatArea.getChildren();

        if (children == null)
        {
            return false;
        }

        for (Widget piece : borderPieces)
        {
            if (piece == null)
            {
                return false;
            }

            final int index = piece.getIndex();

            if (index < 0 || index >= children.length || children[index] != piece)
            {
                return false;
            }
        }

        return true;
    }

    static final class Result
    {
        private int mutations;
        private int revalidates;

        int getMutations()
        {
            return mutations;
        }

        int getRevalidates()
        {
            return revalidates;
        }

        private void add(Result other)
        {
            if (other == null)
            {
                return;
            }

            mutations += other.mutations;
            revalidates += other.revalidates;
        }
    }
}
