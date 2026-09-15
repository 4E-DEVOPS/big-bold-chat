package com.bigboldchat.chat;

import com.bigboldchat.debug.PerformanceMetrics;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for Chat XL widget correlation.
 *
 * These tests protect the row-first / lazy-fallback contract:
 *
 *  - a successful row match must not initialize fallback correlation;
 *  - the first fallback request builds one surface index;
 *  - later fallback requests in the same POST context reuse that index.
 *
 * Reflection is intentionally limited to the private correlation helpers.
 * The behavior under test is internal implementation behavior that cannot be
 * reliably forced through RuneLite's normal chat-construction lifecycle.
 */
public class FontLayoutServiceTest
{
    private Client client;

    private PerformanceMetrics performanceMetrics;

    private FontLayoutService service;

    private Class<?> surfaceClass;

    private Class<?> fallbackContextClass;

    private Object chatboxSurface;

    @Before
    public void setUp()
            throws Exception
    {
        client =
                mock(
                        Client.class);

        performanceMetrics =
                new PerformanceMetrics();

        service =
                new FontLayoutService(
                        client,
                        null,
                        new FontMeasurementService(
                                client),
                        new ChatTextNormalizer(
                                performanceMetrics),
                        performanceMetrics);

        surfaceClass =
                findNestedClass(
                        "Surface");

        fallbackContextClass =
                findNestedClass(
                        "FallbackCorrelationContext");

        chatboxSurface =
                enumConstant(
                        surfaceClass,
                        "CHATBOX");
    }

    /*
     * TESTS
     */

    @Test
    public void rowFirstMatchDoesNotBuildFallback()
            throws Exception
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

        final Object fallbackContext =
                createFallbackContext();

        final Widget result =
                findTargetWidgetForLine(
                        "Test message",
                        lineWidget,
                        Arrays.asList(
                                lineWidget,
                                bodyWidget),
                        fallbackContext);

        assertSame(
                bodyWidget,
                result);

        assertEquals(
                0L,
                metric(
                        "surfaceSearches"));

        assertEquals(
                0L,
                metric(
                        "fallbackSearches"));

        assertEquals(
                0L,
                metric(
                        "fallbackBuilds"));

        assertEquals(
                0L,
                metric(
                        "fallbackReuses"));
    }

    @Test
    public void firstFallbackRequestBuildsSurfaceIndexOnce()
            throws Exception
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

        final Object fallbackContext =
                createFallbackContext();

        final List<Widget> matches =
                findFallbackMatches(
                        fallbackContext,
                        "Test message");

        assertEquals(
                1,
                matches.size());

        assertSame(
                bodyWidget,
                matches.get(
                        0));

        assertEquals(
                1L,
                metric(
                        "surfaceSearches"));

        assertEquals(
                1L,
                metric(
                        "fallbackSearches"));

        assertEquals(
                1L,
                metric(
                        "fallbackBuilds"));

        assertEquals(
                0L,
                metric(
                        "fallbackReuses"));
    }

    @Test
    public void laterFallbackRequestsReuseExistingSurfaceIndex()
            throws Exception
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

        final Object fallbackContext =
                createFallbackContext();

        final List<Widget> bodyMatches =
                findFallbackMatches(
                        fallbackContext,
                        "Body text");

        final List<Widget> prefixMatches =
                findFallbackMatches(
                        fallbackContext,
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
         * Two fallback requests occurred, but only the first request was
         * allowed to scan and index the complete chat surface.
         */
        assertEquals(
                1L,
                metric(
                        "surfaceSearches"));

        assertEquals(
                2L,
                metric(
                        "fallbackSearches"));

        assertEquals(
                1L,
                metric(
                        "fallbackBuilds"));

        assertEquals(
                1L,
                metric(
                        "fallbackReuses"));
    }

    /*
     * HELPERS
     */

    private Object createFallbackContext()
            throws Exception
    {
        final Constructor<?> constructor =
                fallbackContextClass
                        .getDeclaredConstructor(
                                FontLayoutService.class,
                                surfaceClass);

        constructor.setAccessible(
                true);

        return constructor.newInstance(
                service,
                chatboxSurface);
    }

    @SuppressWarnings("unchecked")
    private List<Widget> findFallbackMatches(
            Object fallbackContext,
            String targetText)
            throws Exception
    {
        final Method method =
                fallbackContextClass
                        .getDeclaredMethod(
                                "findMatches",
                                String.class);

        method.setAccessible(
                true);

        return (List<Widget>) method.invoke(
                fallbackContext,
                targetText);
    }

    private Widget findTargetWidgetForLine(
            String targetText,
            Widget lineWidget,
            List<Widget> rowWidgets,
            Object fallbackContext)
            throws Exception
    {
        final Method method =
                FontLayoutService.class
                        .getDeclaredMethod(
                                "findTargetWidgetForLine",
                                String.class,
                                Widget.class,
                                surfaceClass,
                                List.class,
                                fallbackContextClass);

        method.setAccessible(
                true);

        return (Widget) method.invoke(
                service,
                targetText,
                lineWidget,
                chatboxSurface,
                rowWidgets,
                fallbackContext);
    }

    private long metric(
            String fieldName)
            throws Exception
    {
        final Field field =
                PerformanceMetrics.class
                        .getDeclaredField(
                                fieldName);

        field.setAccessible(
                true);

        return field.getLong(
                performanceMetrics);
    }

    private Class<?> findNestedClass(
            String simpleName)
    {
        for (Class<?> nestedClass
                : FontLayoutService.class.getDeclaredClasses())
        {
            if (nestedClass.getSimpleName()
                    .equals(
                            simpleName))
            {
                return nestedClass;
            }
        }

        throw new IllegalStateException(
                "Missing nested class: "
                        + simpleName);
    }

    @SuppressWarnings(
            {
                    "rawtypes",
                    "unchecked"
            })
    private Object enumConstant(
            Class<?> enumClass,
            String name)
    {
        return Enum.valueOf(
                (Class<? extends Enum>) enumClass,
                name);
    }
}