package com.bigboldchat.chat;

import com.bigboldchat.Configurations;
import com.bigboldchat.config.ChatFont;
import com.bigboldchat.debug.PerformanceMetrics;
import com.bigboldchat.fonts.ChatFontProfile;
import com.bigboldchat.fonts.ChatFontRegistry;

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for Chat XL widget correlation and active font state.
 *
 * These tests protect:
 *
 *  - the row-first / lazy-fallback text correlation contract;
 *  - row-first rank correlation and its recursive correctness fallback;
 *  - active font/profile initialization and refresh behavior;
 *  - preservation of active state across reset();
 *  - preservation of a pending PRE -> POST profile snapshot when the
 *    configured active font changes.
 *
 * Reflection is intentionally limited to private implementation state and
 * correlation helpers whose behavior cannot be reliably forced through
 * RuneLite's normal chat-construction lifecycle in a unit test.
 */
public class FontLayoutServiceTest
{
    private Client client;

    private Configurations config;

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
     * ================================================================
     * TEXT CORRELATION
     * ================================================================
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
     * ================================================================
     * RANK CORRELATION
     * ================================================================
     */

    @Test
    public void rankRowMatchDoesNotUseFallback()
            throws Exception
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
                findRankIconWidget(
                        nativeLayout,
                        rowAnchor,
                        Arrays.asList(
                                unrelatedWidget,
                                rankWidget));

        assertSame(
                rankWidget,
                result);

        assertEquals(
                1L,
                metric(
                        "rankSearches"));

        assertEquals(
                0L,
                metric(
                        "rankFallbacks"));

        assertEquals(
                2L,
                metric(
                        "rankNodesExamined"));
    }

    @Test
    public void rankRowMissUsesRecursiveFallback()
            throws Exception
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
                findRankIconWidget(
                        nativeLayout,
                        rowAnchor,
                        Arrays.asList(
                                rowCandidate));

        assertSame(
                rankWidget,
                result);

        assertEquals(
                1L,
                metric(
                        "rankSearches"));

        assertEquals(
                1L,
                metric(
                        "rankFallbacks"));

        /*
         * One row candidate was examined first, then the recursive fallback
         * examined the root and matching dynamic child.
         */
        assertEquals(
                3L,
                metric(
                        "rankNodesExamined"));
    }

    /*
     * ================================================================
     * ACTIVE FONT / PROFILE STATE
     * ================================================================
     */

    @Test
    public void activeFontStateInitializesFromConfiguration()
            throws Exception
    {
        final Object activeState =
                fieldValue(
                        service,
                        "activeFontState");

        assertNotNull(
                activeState);

        assertSame(
                ChatFont.PLAIN_12,
                fieldValue(
                        activeState,
                        "chatFont"));

        assertSame(
                ChatFontRegistry.get(
                        ChatFont.PLAIN_12),
                fieldValue(
                        activeState,
                        "fontProfile"));
    }

    @Test
    public void refreshActiveFontStateUpdatesProfile()
            throws Exception
    {
        when(config.chatFont())
                .thenReturn(
                        ChatFont.BARBARIAN);

        service.refreshActiveFontState();

        final Object activeState =
                fieldValue(
                        service,
                        "activeFontState");

        assertNotNull(
                activeState);

        assertSame(
                ChatFont.BARBARIAN,
                fieldValue(
                        activeState,
                        "chatFont"));

        assertSame(
                ChatFontRegistry.get(
                        ChatFont.BARBARIAN),
                fieldValue(
                        activeState,
                        "fontProfile"));
    }

    @Test
    public void resetPreservesActiveFontState()
            throws Exception
    {
        final Object activeStateBefore =
                fieldValue(
                        service,
                        "activeFontState");

        service.reset();

        final Object activeStateAfter =
                fieldValue(
                        service,
                        "activeFontState");

        assertSame(
                activeStateBefore,
                activeStateAfter);

        assertSame(
                ChatFont.PLAIN_12,
                fieldValue(
                        activeStateAfter,
                        "chatFont"));
    }

    @Test
    public void refreshActiveFontStatePreservesPendingProfileSnapshot()
            throws Exception
    {
        final ChatFontProfile plainProfile =
                ChatFontRegistry.get(
                        ChatFont.PLAIN_12);

        final ChatFontProfile barbarianProfile =
                ChatFontRegistry.get(
                        ChatFont.BARBARIAN);

        /*
         * Simulate the exact state immediately after a supported PRE has
         * captured its profile for the pending PRE -> POST construction.
         *
         * We intentionally set only the snapshot under test here. The purpose
         * of this test is to guarantee that refreshing the active font cannot
         * overwrite an already-captured pending profile.
         */
        setFieldValue(
                service,
                "pendingFontProfile",
                plainProfile);

        when(config.chatFont())
                .thenReturn(
                        ChatFont.BARBARIAN);

        service.refreshActiveFontState();

        final Object activeState =
                fieldValue(
                        service,
                        "activeFontState");

        assertSame(
                barbarianProfile,
                fieldValue(
                        activeState,
                        "fontProfile"));

        assertSame(
                plainProfile,
                fieldValue(
                        service,
                        "pendingFontProfile"));
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

    private Widget findRankIconWidget(
            FontMeasurementService.ChannelPrefixLayout nativeLayout,
            Widget rowAnchor,
            List<Widget> rowWidgets)
            throws Exception
    {
        final Method method =
                FontLayoutService.class
                        .getDeclaredMethod(
                                "findRankIconWidget",
                                FontMeasurementService.ChannelPrefixLayout.class,
                                Widget.class,
                                List.class);

        method.setAccessible(
                true);

        return (Widget) method.invoke(
                service,
                nativeLayout,
                rowAnchor,
                rowWidgets);
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

    private Object fieldValue(
            Object target,
            String fieldName)
            throws Exception
    {
        final Field field =
                target.getClass()
                        .getDeclaredField(
                                fieldName);

        field.setAccessible(
                true);

        return field.get(
                target);
    }

    private void setFieldValue(
            Object target,
            String fieldName,
            Object value)
            throws Exception
    {
        final Field field =
                target.getClass()
                        .getDeclaredField(
                                fieldName);

        field.setAccessible(
                true);

        field.set(
                target,
                value);
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