package com.bigboldchat.chat;

import com.bigboldchat.debug.PerformanceMetrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for persistent shallow row indexing.
 *
 * These tests protect:
 *
 *  - first-build behavior;
 *  - direct row reuse;
 *  - local geometry repair;
 *  - conservative rebuild on an unknown row anchor;
 *  - index enrichment from a known surface scan.
 */
public class RowCorrelationIndexTest
{
    private Client client;

    private PerformanceMetrics performanceMetrics;

    @Before
    public void setUp()
    {
        client =
                mock(
                        Client.class);

        performanceMetrics =
                new PerformanceMetrics();
    }

    /*
     * ================================================================
     * TESTS
     * ================================================================
     */

    @Test
    public void firstRowLookupBuildsIndex()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget lineWidget =
                rowWidget(
                        42,
                        84);

        final Widget bodyWidget =
                rowWidget(
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

        final RowCorrelationIndex rowIndex =
                rowIndex();

        final List<Widget> row =
                rowIndex.findRow(
                        lineWidget);

        assertEquals(
                2,
                row.size());

        assertTrue(
                row.contains(
                        lineWidget));

        assertTrue(
                row.contains(
                        bodyWidget));

        verify(
                root,
                times(
                        1))
                .getDynamicChildren();
    }

    @Test
    public void laterRowLookupReusesIndex()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget lineWidget =
                rowWidget(
                        42,
                        84);

        final Widget bodyWidget =
                rowWidget(
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

        final RowCorrelationIndex rowIndex =
                rowIndex();

        final List<Widget> first =
                rowIndex.findRow(
                        lineWidget);

        final List<Widget> second =
                rowIndex.findRow(
                        lineWidget);

        assertEquals(
                first,
                second);

        /*
         * Direct reuse must not enumerate the surface again.
         */
        verify(
                root,
                times(
                        1))
                .getDynamicChildren();

        verify(
                root,
                times(
                        1))
                .getStaticChildren();

        verify(
                root,
                times(
                        1))
                .getNestedChildren();
    }

    @Test
    public void movedCachedWidgetRepairsIndexWithoutFullRebuild()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget lineWidget =
                rowWidget(
                        42,
                        84);

        final Widget bodyWidget =
                mock(
                        Widget.class);

        final AtomicInteger bodyOriginalY =
                new AtomicInteger(
                        42);

        when(bodyWidget.getOriginalY())
                .thenAnswer(
                        ignored ->
                                bodyOriginalY.get());

        when(bodyWidget.getRelativeY())
                .thenReturn(
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

        final RowCorrelationIndex rowIndex =
                rowIndex();

        final List<Widget> first =
                rowIndex.findRow(
                        lineWidget);

        assertTrue(
                first.contains(
                        bodyWidget));

        bodyOriginalY.set(
                43);

        final List<Widget> second =
                rowIndex.findRow(
                        lineWidget);

        assertFalse(
                second.contains(
                        bodyWidget));

        /*
         * Local repair must not rebuild the surface.
         */
        verify(
                root,
                times(
                        1))
                .getDynamicChildren();
    }

    @Test
    public void missingRowRefreshesIndexOnce()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget existingLine =
                rowWidget(
                        42,
                        84);

        final Widget requestedMissingLine =
                rowWidget(
                        100,
                        200);

        when(root.getDynamicChildren())
                .thenReturn(
                        new Widget[]
                                {
                                        existingLine
                                });

        when(client.getWidget(
                InterfaceID.Chatbox.SCROLLAREA))
                .thenReturn(
                        root);

        final RowCorrelationIndex rowIndex =
                rowIndex();

        rowIndex.findRow(
                existingLine);

        final List<Widget> missing =
                rowIndex.findRow(
                        requestedMissingLine);

        assertTrue(
                missing.isEmpty());

        /*
         * An unknown row anchor is confirmed with one rebuild.
         */
        verify(
                root,
                times(
                        2))
                .getDynamicChildren();
    }

    @Test
    public void observedSurfaceWidgetEnrichesIndexWithoutRebuild()
    {
        final Widget root =
                mock(
                        Widget.class);

        final Widget lineWidget =
                rowWidget(
                        42,
                        84);

        final Widget observedWidget =
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

        final RowCorrelationIndex rowIndex =
                rowIndex();

        final List<Widget> first =
                rowIndex.findRow(
                        lineWidget);

        assertEquals(
                1,
                first.size());

        rowIndex.observeSurfaceWidget(
                root,
                observedWidget);

        final List<Widget> second =
                rowIndex.findRow(
                        lineWidget);

        assertEquals(
                2,
                second.size());

        assertTrue(
                second.contains(
                        lineWidget));

        assertTrue(
                second.contains(
                        observedWidget));

        /*
         * Enrichment must reuse the existing surface index.
         */
        verify(
                root,
                times(
                        1))
                .getDynamicChildren();

        verify(
                root,
                times(
                        1))
                .getStaticChildren();

        verify(
                root,
                times(
                        1))
                .getNestedChildren();
    }

    private RowCorrelationIndex rowIndex()
    {
        return new RowCorrelationIndex(
                client,
                FontLayoutService.Surface.CHATBOX,
                performanceMetrics);
    }

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
}
