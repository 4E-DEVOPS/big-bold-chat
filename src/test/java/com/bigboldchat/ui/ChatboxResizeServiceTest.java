package com.bigboldchat.ui;

import com.bigboldchat.Configurations;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for Chat XL resizable-layout geometry.
 */
public class ChatboxResizeServiceTest
{
	private Client client;
	private Configurations config;
	private Widget universe;
	private Widget chatArea;
	private Widget slot;
	private Widget dynamicChild;
	private Widget staticChild;
	private Widget nestedChild;
	private ChatboxResizeService service;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		config = mock(Configurations.class);
		universe = mock(Widget.class);
		chatArea = mock(Widget.class);
		slot = mock(Widget.class);
		dynamicChild = mock(Widget.class);
		staticChild = mock(Widget.class);
		nestedChild = mock(Widget.class);

		when(client.isResized()).thenReturn(true);
		when(client.getWidget(InterfaceID.Chatbox.UNIVERSE)).thenReturn(universe);
		when(client.getWidget(InterfaceID.Chatbox.CHATAREA)).thenReturn(chatArea);
		when(universe.getParent()).thenReturn(slot);

		when(universe.getDynamicChildren()).thenReturn(new Widget[]{dynamicChild});
		when(universe.getStaticChildren()).thenReturn(new Widget[]{staticChild});
		when(universe.getNestedChildren()).thenReturn(new Widget[]{nestedChild});

		when(slot.getId()).thenReturn(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER);

		when(config.chatboxWidth()).thenReturn(640);
		when(config.chatboxHeight()).thenReturn(220);

		service = new ChatboxResizeService(client, config, null);
	}

	@Test
	public void resizableLayoutAppliesConfiguredGeometry()
	{
		when(slot.getWidth()).thenReturn(519);
		when(slot.getHeight()).thenReturn(165);
		when(universe.getWidth()).thenReturn(519);
		when(universe.getHeight()).thenReturn(165);
		when(chatArea.getWidth()).thenReturn(519);

		final ChatboxResizeService.ResizeResult result = service.applyConfiguredSize();

		assertTrue(result.isApplied());
		assertTrue(result.isWidthChanged());
		assertTrue(result.isHeightChanged());

		verify(slot).setSize(640, 220);
		verify(universe).setSize(
				640,
				220,
				WidgetSizeMode.ABSOLUTE,
				WidgetSizeMode.ABSOLUTE);
		verify(universe).setForcedPosition(0, 0);
		verify(chatArea).setOriginalWidth(640);

		verify(dynamicChild).revalidate();
		verify(staticChild).revalidate();
		verify(nestedChild).revalidate();
	}

	@Test
	public void matchingGeometryIsNoop()
	{
		when(slot.getWidth()).thenReturn(640);
		when(slot.getHeight()).thenReturn(220);
		when(universe.getWidth()).thenReturn(640);
		when(universe.getHeight()).thenReturn(220);
		when(chatArea.getWidth()).thenReturn(640);

		final ChatboxResizeService.ResizeResult result = service.applyConfiguredSize();

		assertTrue(result.isApplied());
		assertFalse(result.isWidthChanged());
		assertFalse(result.isHeightChanged());

		verify(slot, never()).setSize(640, 220);
		verify(chatArea, never()).setOriginalWidth(640);
	}

	@Test
	public void fixedLayoutIsNotModified()
	{
		when(slot.getId()).thenReturn(InterfaceID.Toplevel.CHAT_CONTAINER);

		final ChatboxResizeService.ResizeResult result = service.applyConfiguredSize();

		assertFalse(result.isApplied());
		verify(slot, never()).setSize(640, 220);
		verify(chatArea, never()).setOriginalWidth(640);
	}

	@Test
	public void restoreReturnsResizableGeometryToNativeModes()
	{
		service.restoreNativeSize();

		verify(slot).setSize(519, 165);
		verify(slot).setForcedPosition(-1, -1);
		verify(universe).setSize(
				0,
				0,
				WidgetSizeMode.MINUS,
				WidgetSizeMode.MINUS);
		verify(universe).setForcedPosition(-1, -1);
		verify(chatArea).setOriginalWidth(519);

		verify(dynamicChild).revalidate();
		verify(staticChild).revalidate();
		verify(nestedChild).revalidate();
	}

	@Test
	public void heightOnlyChangeRevalidatesChatboxChildren()
	{
		when(slot.getWidth()).thenReturn(640);
		when(slot.getHeight()).thenReturn(165);
		when(universe.getWidth()).thenReturn(640);
		when(universe.getHeight()).thenReturn(165);
		when(chatArea.getWidth()).thenReturn(640);

		final ChatboxResizeService.ResizeResult result = service.applyConfiguredSize();

		assertTrue(result.isApplied());
		assertFalse(result.isWidthChanged());
		assertTrue(result.isHeightChanged());

		verify(slot).setSize(640, 220);
		verify(universe).setSize(
				640,
				220,
				WidgetSizeMode.ABSOLUTE,
				WidgetSizeMode.ABSOLUTE);

		verify(chatArea, never()).setOriginalWidth(640);

		verify(dynamicChild).revalidate();
		verify(staticChild).revalidate();
		verify(nestedChild).revalidate();
	}
}
