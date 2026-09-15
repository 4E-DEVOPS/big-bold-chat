package com.bigboldchat.chat;

import com.bigboldchat.Configurations;
import com.bigboldchat.config.ChatFont;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for Chat XL layout, presentation, and correlation.
 *
 * These tests protect:
 *
 *  - row-first / lazy-fallback text correlation;
 *  - row-first rank correlation, shallow refresh, and recursive correctness fallback;
 *  - active font/profile lifecycle;
 *  - idempotent widget mutation;
 *  - conditional widget revalidation;
 *  - FontLayoutService integration with the persistent row index.
 *
 * The test class deliberately uses package-private test seams instead of
 * Java reflection.
 */
public class FontLayoutServiceTest
{
    private Client client;

    private Configurations config;

    private PerformanceMetrics performanceMetrics;

    private FontLayoutService service;

    @Before
    public void setUp()
    {
        client =
                mock(
                        Client.class);

        config =
                mock(
                        Configurations.class);

        when(config.chatFont())
                .thenReturn(
                        ChatFont.PLAIN_12);

        performanceMetrics =
                new PerformanceMetrics();

        service =
                new FontLayoutService(
                        client,
                        config,
                        new FontMeasurementService(
                                client),
                        new ChatTextNormalizer(
                                performanceMetrics),
                        performanceMetrics);
    }

    /*
     * ================================================================
     * TEXT CORRELATION
     * ================================================================
     */

    @Test
    public void rowFirstMatchDoesNotBuildFallback()
    {
        final Widget lineWidget =
                mock(
                        Widget.class);

        final Widget bodyWidget =
                mock(
                        Widget.class);

        when(bodyWidget.getText())
                .thenReturn(
                        "Test message");

        final FontLayoutService.FallbackCorrelationContext fallbackContext =
                service.new FallbackCorrelationContext(
                        FontLayoutService.Surface.CHATBOX);

        final Widget result =
                service.findTargetWidgetForLine(
                        "Test message",
                        lineWidget,
                        FontLayoutService.Surface.CHATBOX,
                        Arrays.asList(
                                lineWidget,
                                bodyWidget),
                        fallbackContext);

        assertSame(
                bodyWidget,
                result);

        /*
         * A successful row-first match must never initialize the complete
         * surface fallback index.
         */
        verify(
                client,
                never())
                .getWidget(
                        InterfaceID.Chatbox.SCROLLAREA);
    }

    @Test
    public void firstFallbackRequestBuildsSurfaceIndexOnce()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget bodyWidget =
                mock(
                        Widget.class);

        when(bodyWidget.getText())
                .thenReturn(
                        "<col=ffffff>Test message</col>");

        when(root.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        bodyWidget
                                });

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        final FontLayoutService.FallbackCorrelationContext fallbackContext =
                service.new FallbackCorrelationContext(
                        FontLayoutService.Surface.CHATBOX);

        final List<Widget> matches =
                fallbackContext.findMatches(
                        "Test message");

        assertEquals(
                1,
                matches.size());

        assertSame(
                bodyWidget,
                matches.get(
                        0));

        verify(
                client,
                times(
                        1))
                .getWidget(
                        InterfaceID.Chatbox.SCROLLAREA);
    }

    @Test
    public void laterFallbackRequestsReuseExistingSurfaceIndex()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget bodyWidget =
                mock(
                        Widget.class);

        final Widget prefixWidget =
                mock(
                        Widget.class);

        when(bodyWidget.getText())
                .thenReturn(
                        "Body text");

        when(prefixWidget.getText())
                .thenReturn(
                        "Player:");

        when(root.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        bodyWidget,
                                        prefixWidget
                                });

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        final FontLayoutService.FallbackCorrelationContext fallbackContext =
                service.new FallbackCorrelationContext(
                        FontLayoutService.Surface.CHATBOX);

        final List<Widget> bodyMatches =
                fallbackContext.findMatches(
                        "Body text");

        final List<Widget> prefixMatches =
                fallbackContext.findMatches(
                        "Player:");

        assertEquals(
                1,
                bodyMatches.size());

        assertSame(
                bodyWidget,
                bodyMatches.get(
                        0));

        assertEquals(
                1,
                prefixMatches.size());

        assertSame(
                prefixWidget,
                prefixMatches.get(
                        0));

        /*
         * Both searches must be served by one surface-index construction.
         */
        verify(
                client,
                times(
                        1))
                .getWidget(
                        InterfaceID.Chatbox.SCROLLAREA);
    }

    /*
     * ================================================================
     * RANK CORRELATION
     * ================================================================
     */

    @Test
    public void rankRowMatchDoesNotUseFallback()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget rowAnchor =
                mock(
                        Widget.class);

        final Widget unrelatedWidget =
                mock(
                        Widget.class);

        final Widget rankWidget =
                mock(
                        Widget.class);

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        when(rowAnchor.getOriginalY())
                .thenReturn(
                        42);

        when(rowAnchor.getRelativeY())
                .thenReturn(
                        84);

        when(rankWidget.getSpriteId())
                .thenReturn(
                        1234);

        when(rankWidget.getOriginalX())
                .thenReturn(
                        27);

        when(rankWidget.getOriginalY())
                .thenReturn(
                        42);

        final FontMeasurementService.ChannelPrefixLayout nativeLayout =
                new FontMeasurementService.ChannelPrefixLayout();

        nativeLayout.rankIconSpriteId =
                1234;

        nativeLayout.rankIconX =
                27;

        final Widget result =
                service.findRankIconWidget(
                        nativeLayout,
                        rowAnchor,
                        Arrays.asList(
                                unrelatedWidget,
                                rankWidget));

        assertSame(
                rankWidget,
                result);

        /*
         * The recursive tree fallback must not be entered.
         */
        verify(
                root,
                never())
                .getDynamicChildren();

        verify(
                root,
                never())
                .getStaticChildren();

        verify(
                root,
                never())
                .getNestedChildren();
    }

    @Test
    public void rankRowMissUsesShallowRefreshBeforeRecursiveFallback()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget rowAnchor =
                mock(
                        Widget.class);

        final Widget rowCandidate =
                mock(
                        Widget.class);

        final Widget rankWidget =
                mock(
                        Widget.class);

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        when(root.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        rankWidget
                                });

        when(rowAnchor.getOriginalY())
                .thenReturn(
                        42);

        when(rowAnchor.getRelativeY())
                .thenReturn(
                        84);

        when(rankWidget.getSpriteId())
                .thenReturn(
                        1234);

        when(rankWidget.getOriginalX())
                .thenReturn(
                        27);

        when(rankWidget.getRelativeY())
                .thenReturn(
                        84);

        final FontMeasurementService.ChannelPrefixLayout nativeLayout =
                new FontMeasurementService.ChannelPrefixLayout();

        nativeLayout.rankIconSpriteId =
                1234;

        nativeLayout.rankIconX =
                27;

        final Widget result =
                service.findRankIconWidget(
                        nativeLayout,
                        rowAnchor,
                        Arrays.asList(
                                rowCandidate));

        assertSame(
                rankWidget,
                result);

        /*
         * A shallow match must avoid recursive traversal.
         */
        verify(
                root,
                times(
                        1))
                .getDynamicChildren();

        verify(
                root,
                never())
                .getStaticChildren();

        verify(
                root,
                never())
                .getNestedChildren();

        verify(
                rankWidget,
                never())
                .getDynamicChildren();
    }

    @Test
    public void rankShallowMissUsesRecursiveFallback()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget rowAnchor =
                mock(
                        Widget.class);

        final Widget rowCandidate =
                mock(
                        Widget.class);

        final Widget container =
                mock(
                        Widget.class);

        final Widget rankWidget =
                mock(
                        Widget.class);

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        when(root.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        container
                                });

        when(container.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        rankWidget
                                });

        when(rowAnchor.getOriginalY())
                .thenReturn(
                        42);

        when(rowAnchor.getRelativeY())
                .thenReturn(
                        84);

        when(rankWidget.getSpriteId())
                .thenReturn(
                        1234);

        when(rankWidget.getOriginalX())
                .thenReturn(
                        27);

        when(rankWidget.getRelativeY())
                .thenReturn(
                        84);

        final FontMeasurementService.ChannelPrefixLayout nativeLayout =
                new FontMeasurementService.ChannelPrefixLayout();

        nativeLayout.rankIconSpriteId =
                1234;

        nativeLayout.rankIconX =
                27;

        final Widget result =
                service.findRankIconWidget(
                        nativeLayout,
                        rowAnchor,
                        Arrays.asList(
                                rowCandidate));

        assertSame(
                rankWidget,
                result);

        /*
         * The shallow scan cannot see grandchildren, so recursive fallback
         * must find the rank. The root is read once by each path.
         */
        verify(
                root,
                times(
                        2))
                .getDynamicChildren();

        verify(
                container,
                times(
                        1))
                .getDynamicChildren();
    }

    /*
     * ================================================================
     * ACTIVE FONT / PROFILE STATE
     * ================================================================
     */

    @Test
    public void activeFontStateInitializesFromConfiguration()
    {
        assertSame(
                ChatFont.PLAIN_12,
                service.activeChatFontForTesting());

        assertSame(
                ChatFontRegistry.get(
                        ChatFont.PLAIN_12),
                service.activeFontProfileForTesting());
    }

    @Test
    public void refreshActiveFontStateUpdatesProfile()
    {
        when(config.chatFont())
                .thenReturn(
                        ChatFont.BARBARIAN);

        service.refreshActiveFontState();

        assertSame(
                ChatFont.BARBARIAN,
                service.activeChatFontForTesting());

        assertSame(
                ChatFontRegistry.get(
                        ChatFont.BARBARIAN),
                service.activeFontProfileForTesting());
    }

    @Test
    public void resetPreservesActiveFontState()
    {
        final ChatFont activeFontBefore =
                service.activeChatFontForTesting();

        final ChatFontProfile activeProfileBefore =
                service.activeFontProfileForTesting();

        assertNotNull(
                activeFontBefore);

        assertNotNull(
                activeProfileBefore);

        service.reset();

        assertSame(
                activeFontBefore,
                service.activeChatFontForTesting());

        assertSame(
                activeProfileBefore,
                service.activeFontProfileForTesting());
    }

    @Test
    public void refreshActiveFontStatePreservesPendingProfileSnapshot()
    {
        final ChatFontProfile plainProfile =
                ChatFontRegistry.get(
                        ChatFont.PLAIN_12);

        final ChatFontProfile barbarianProfile =
                ChatFontRegistry.get(
                        ChatFont.BARBARIAN);

        service.setPendingFontProfileForTesting(
                plainProfile);

        when(config.chatFont())
                .thenReturn(
                        ChatFont.BARBARIAN);

        service.refreshActiveFontState();

        assertSame(
                barbarianProfile,
                service.activeFontProfileForTesting());

        assertSame(
                plainProfile,
                service.pendingFontProfileForTesting());
    }

    /*
     * ================================================================
     * IDEMPOTENT WIDGET MUTATION
     * ================================================================
     */

    @Test
    public void unchangedWidgetDoesNotMutate()
    {
        final Widget widget =
                mock(
                        Widget.class);

        when(widget.getFontId())
                .thenReturn(
                        12);

        final boolean changed =
                service.setFontIdIfChanged(
                        widget,
                        12);

        assertFalse(
                changed);

        verify(
                widget,
                never())
                .setFontId(
                        12);
    }

    @Test
    public void changedWidgetMutatesOnlyOnceAfterStateMatches()
    {
        final Widget widget =
                mock(
                        Widget.class);

        final AtomicInteger currentFontId =
                new AtomicInteger(
                        12);

        when(widget.getFontId())
                .thenAnswer(
                        ignored ->
                                currentFontId.get());

        doAnswer(
                invocation ->
                {
                    currentFontId.set(
                            (Integer) invocation.getArgument(
                                    0));

                    return null;
                })
                .when(
                        widget)
                .setFontId(
                        99);

        assertTrue(
                service.setFontIdIfChanged(
                        widget,
                        99));

        assertFalse(
                service.setFontIdIfChanged(
                        widget,
                        99));

        verify(
                widget,
                times(
                        1))
                .setFontId(
                        99);
    }

    /*
     * ================================================================
     * CONDITIONAL REVALIDATION
     * ================================================================
     */

    @Test
    public void unchangedWidgetDoesNotRevalidate()
    {
        final Widget widget =
                mock(
                        Widget.class);

        service.revalidateWidgetIfChanged(
                widget,
                false);

        verify(
                widget,
                never())
                .revalidate();
    }

    @Test
    public void changedWidgetRevalidatesOnce()
    {
        final Widget widget =
                mock(
                        Widget.class);

        service.revalidateWidgetIfChanged(
                widget,
                true);

        verify(
                widget,
                times(
                        1))
                .revalidate();
    }

    @Test
    public void multipleChangesToSameWidgetRevalidateOnce()
    {
        final Widget widget =
                mock(
                        Widget.class);

        when(widget.getFontId())
                .thenReturn(
                        12);

        when(widget.getOriginalWidth())
                .thenReturn(
                        100);

        boolean changed =
                service.setFontIdIfChanged(
                        widget,
                        99);

        changed |=
                service.setOriginalWidthIfChanged(
                        widget,
                        200);

        service.revalidateWidgetIfChanged(
                widget,
                changed);

        verify(
                widget,
                times(
                        1))
                .setFontId(
                        99);

        verify(
                widget,
                times(
                        1))
                .setOriginalWidth(
                        200);

        verify(
                widget,
                times(
                        1))
                .revalidate();
    }

    /*
     * ================================================================
     * ROW INDEX INTEGRATION
     * ================================================================
     */

    @Test
    @SuppressWarnings("deprecation")
    public void chatXlYOffsetReturningToNativeRowRepairsWithoutFullRebuild()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget lineWidget =
                rowWidget(
                        42,
                        84);

        final Widget bodyWidget =
                mutableRowWidget(
                        42,
                        84);

        when(root.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        lineWidget,
                                        bodyWidget
                                });

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        final List<Widget> first =
                service.collectRow(
                        lineWidget,
                        FontLayoutService.Surface.CHATBOX);

        assertTrue(
                first.contains(
                        bodyWidget));

        /*
         * Move the indexed widget away from its original row.
         */
        service.applySynchronizedYOffset(
                bodyWidget,
                5);

        assertEquals(
                47,
                bodyWidget.getOriginalY());

        assertEquals(
                47,
                bodyWidget.getRelativeY());

        /*
         * Restore the widget's original geometry.
         */
        bodyWidget.setOriginalY(
                42);

        bodyWidget.setRelativeY(
                84);

        final List<Widget> second =
                service.collectRow(
                        lineWidget,
                        FontLayoutService.Surface.CHATBOX);

        assertTrue(
                second.contains(
                        bodyWidget));

        /*
         * Repair the watched move locally without rebuilding the surface.
         */
        verify(
                root,
                times(
                        1))
                .getDynamicChildren();
    }

    @Test
    public void resetClearsRowIndexes()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget lineWidget =
                rowWidget(
                        42,
                        84);

        when(root.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        lineWidget
                                });

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        service.collectRow(
                lineWidget,
                FontLayoutService.Surface.CHATBOX);

        service.reset();

        service.collectRow(
                lineWidget,
                FontLayoutService.Surface.CHATBOX);

        /*
         * reset() deliberately discards persistent row indexes.
         */
        verify(
                root,
                times(
                        2))
                .getDynamicChildren();
    }

    /*
     * ================================================================
     * HELPERS
     * ================================================================
     */

    private Widget rowWidget(
            int originalY,
            int relativeY)
    {
        final Widget widget =
                mock(
                        Widget.class);

        when(widget.getOriginalY())
                .thenReturn(
                        originalY);

        when(widget.getRelativeY())
                .thenReturn(
                        relativeY);

        return widget;
    }

    @SuppressWarnings("deprecation")
    private Widget mutableRowWidget(
            int originalY,
            int relativeY)
    {
        final Widget widget =
                mock(
                        Widget.class);

        final AtomicInteger currentOriginalY =
                new AtomicInteger(
                        originalY);

        final AtomicInteger currentRelativeY =
                new AtomicInteger(
                        relativeY);

        when(widget.getOriginalY())
                .thenAnswer(
                        ignored ->
                                currentOriginalY.get());

        when(widget.getRelativeY())
                .thenAnswer(
                        ignored ->
                                currentRelativeY.get());

        doAnswer(
                invocation ->
                {
                    currentOriginalY.set(
                            (Integer) invocation.getArgument(
                                    0));

                    return null;
                })
                .when(
                        widget)
                .setOriginalY(
                        org.mockito.ArgumentMatchers.anyInt());

        doAnswer(
                invocation ->
                {
                    currentRelativeY.set(
                            (Integer) invocation.getArgument(
                                    0));

                    return null;
                })
                .when(
                        widget)
                .setRelativeY(
                        org.mockito.ArgumentMatchers.anyInt());

        return widget;
    }
}
