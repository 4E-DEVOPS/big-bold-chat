package com.bigboldchat.chat;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Characterization tests for the Chat XL measurement service.
 */
public class FontMeasurementServiceTest
{
    private FontMeasurementService service;

    @Before
    public void setUp()
    {
        /*
         * The supported-script contract does not access Client state.
         */
        service =
                new FontMeasurementService(
                        null);
    }

    /*
     * TESTS
     */

    @Test
    public void supportsOnlyKnownChatConstructionScripts()
    {
        assertTrue(
                service.supportsScript(
                        199));

        assertTrue(
                service.supportsScript(
                        203));

        assertTrue(
                service.supportsScript(
                        4483));

        assertFalse(
                service.supportsScript(
                        -1));

        assertFalse(
                service.supportsScript(
                        0));

        assertFalse(
                service.supportsScript(
                        72));

        assertFalse(
                service.supportsScript(
                        193));

        assertFalse(
                service.supportsScript(
                        4482));

        assertFalse(
                service.supportsScript(
                        4484));
    }
}