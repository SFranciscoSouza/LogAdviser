package com.logadviser.sync;

import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.SpriteID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;

/**
 * Builds a native-looking "Sync" button on the collection log header, styled to match the
 * in-game Search button (and the Temple-OSRS button it's modelled on). It is a 9-slice
 * made of nine GRAPHIC children (centre fill + four corners + four edges) plus a refresh
 * icon, all overlaid by a single TEXT child that carries the menu action and click/hover
 * listeners. Hovering swaps the nine slice sprites to their highlighted variants, exactly
 * like the real header buttons.
 *
 * <p>Every child uses {@link WidgetPositionMode#ABSOLUTE_RIGHT}: in that mode {@code x} is
 * the distance from the parent's RIGHT edge to the child's RIGHT edge, so a larger {@code x}
 * sits further LEFT. All the layout maths below is in that "distance from the right" space.
 * The button is anchored just left of the live Search button so it aligns at any scale.
 *
 * <p>All methods mutate game widgets and so MUST run on the client thread (collection-log
 * setup script + op listener).
 */
public final class CollectionLogSyncButton
{
	private static final String SYNC_ACTION = "Sync collection log";
	private static final String LABEL_IDLE = "Sync";
	private static final String LABEL_BUSY = "Syncing...";

	private static final int FONT_COLOUR_IDLE = 0xd6d6d6;
	private static final int FONT_COLOUR_HOVER = 0xffffff;

	private static final int BUTTON_WIDTH = 80;
	private static final int FALLBACK_RIGHT = 33; // distance from right edge if the Search button isn't loaded
	private static final int GAP_FROM_SEARCH = 6; // gap between our button and the Search button
	private static final int CORNER = 9;
	private static final int ICON_SIZE = 13;
	private static final int PAD_LEFT = 6;   // border -> icon
	private static final int ICON_GAP = 5;   // icon -> text
	private static final int PAD_RIGHT = 12; // text -> border (extra breathing room at the end)

	// 9-slice sprite sets: index 0 = centre fill, 1-4 = corners (TL, TR, BL, BR),
	// 5-8 = edges (left, top, right, bottom). Idle reuses the world-map metal button
	// sprites; hover uses the equipment button's highlighted variants — the same families
	// the genuine collection log header buttons are drawn from.
	private static final int[] SLICE_IDLE = {
		SpriteID.DIALOG_BACKGROUND,
		SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_TOP_LEFT,
		SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_TOP_RIGHT,
		SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_BOTTOM_LEFT,
		SpriteID.WORLD_MAP_BUTTON_METAL_CORNER_BOTTOM_RIGHT,
		SpriteID.WORLD_MAP_BUTTON_EDGE_LEFT,
		SpriteID.WORLD_MAP_BUTTON_EDGE_TOP,
		SpriteID.WORLD_MAP_BUTTON_EDGE_RIGHT,
		SpriteID.WORLD_MAP_BUTTON_EDGE_BOTTOM,
	};

	private static final int[] SLICE_HOVER = {
		SpriteID.RESIZEABLE_MODE_SIDE_PANEL_BACKGROUND,
		SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_TOP_LEFT_HOVERED,
		SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_TOP_RIGHT_HOVERED,
		SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_BOTTOM_LEFT_HOVERED,
		SpriteID.EQUIPMENT_BUTTON_METAL_CORNER_BOTTOM_RIGHT_HOVERED,
		SpriteID.EQUIPMENT_BUTTON_EDGE_LEFT_HOVERED,
		SpriteID.EQUIPMENT_BUTTON_EDGE_TOP_HOVERED,
		SpriteID.EQUIPMENT_BUTTON_EDGE_RIGHT_HOVERED,
		SpriteID.EQUIPMENT_BUTTON_EDGE_BOTTOM_HOVERED,
	};

	// The nine slice widgets (for the hover swap), the refresh icon, and the text overlay.
	private final Widget[] slices = new Widget[SLICE_IDLE.length];
	private Widget icon;
	private Widget text;
	private boolean attached = false;
	// Logical "sync in flight" state, kept independent of the widget lifecycle so it
	// survives an interface rebuild (which wipes and re-creates the button mid-sync).
	private boolean busy = false;

	/** Builds the button on the collection log header if it isn't already there. */
	public void attach(Client client, Runnable onClick)
	{
		Widget parent = client.getWidget(InterfaceID.Collection.UNIVERSE);
		if (parent == null)
		{
			return;
		}
		// If the button already exists (a redraw that didn't wipe the container's children),
		// re-adopt our overlay so the busy label keeps updating instead of going stale — then
		// re-apply the current sync state so it shows "Syncing..." again.
		Widget existing = findExistingText(parent);
		if (existing != null)
		{
			text = existing;
			attached = true;
			applyBusyState();
			return;
		}
		Widget searchButton = client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);

		final int w = BUTTON_WIDTH;
		final int h = searchButton != null ? searchButton.getOriginalHeight() : 20;
		// Right edge of our button = just left of the Search button (both ABSOLUTE_RIGHT).
		final int x = searchButton != null
			? searchButton.getOriginalX() + searchButton.getOriginalWidth() + GAP_FROM_SEARCH
			: FALLBACK_RIGHT;
		final int y = searchButton != null ? searchButton.getOriginalY() : 5;
		final int yMode = searchButton != null
			? searchButton.getYPositionMode() : WidgetPositionMode.ABSOLUTE_TOP;
		final int sideH = Math.max(1, h - 2 * CORNER);
		final int edgeW = Math.max(1, w - 2 * CORNER);

		// [0] centre fill — spans the whole button, tiled so the texture doesn't stretch.
		slices[0] = slice(parent, SLICE_IDLE[0], x, y, w, h, yMode, true);
		// Corners. Remember: x = right edge, x + (w - CORNER) = left edge.
		slices[1] = slice(parent, SLICE_IDLE[1], x + (w - CORNER), y, CORNER, CORNER, yMode, false); // top-left
		slices[2] = slice(parent, SLICE_IDLE[2], x, y, CORNER, CORNER, yMode, false);                // top-right
		slices[3] = slice(parent, SLICE_IDLE[3], x + (w - CORNER), y + h - CORNER, CORNER, CORNER, yMode, false); // bottom-left
		slices[4] = slice(parent, SLICE_IDLE[4], x, y + h - CORNER, CORNER, CORNER, yMode, false);    // bottom-right
		// Edges between the corners.
		slices[5] = slice(parent, SLICE_IDLE[5], x + (w - CORNER), y + CORNER, CORNER, sideH, yMode, false); // left
		slices[6] = slice(parent, SLICE_IDLE[6], x + CORNER, y, edgeW, CORNER, yMode, false);               // top
		slices[7] = slice(parent, SLICE_IDLE[7], x, y + CORNER, CORNER, sideH, yMode, false);               // right
		slices[8] = slice(parent, SLICE_IDLE[8], x + CORNER, y + h - CORNER, edgeW, CORNER, yMode, false);  // bottom

		// Refresh-arrows icon on the LEFT of the label (decorative, not swapped on hover).
		icon = parent.createChild(-1, WidgetType.GRAPHIC);
		icon.setSpriteId(SpriteID.UNKNOWN_WHITE_REFRESH_ARROWS);
		icon.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		icon.setYPositionMode(yMode);
		icon.setOriginalWidth(ICON_SIZE);
		icon.setOriginalHeight(ICON_SIZE);
		icon.setOriginalX(x + w - PAD_LEFT - ICON_SIZE); // left edge sits PAD_LEFT in from the button's left
		icon.setOriginalY(y + (h - ICON_SIZE) / 2);
		icon.revalidate();

		for (Widget s : slices)
		{
			if (s != null)
			{
				s.revalidate();
			}
		}

		// Clickable text overlay — occupies the space to the right of the icon, with
		// PAD_RIGHT of breathing room before the border. Carries all the listeners.
		final int textWidth = Math.max(1, w - PAD_LEFT - ICON_SIZE - ICON_GAP - PAD_RIGHT);
		Widget label = parent.createChild(-1, WidgetType.TEXT);
		label.setText(LABEL_IDLE);
		label.setTextColor(FONT_COLOUR_IDLE);
		label.setFontId(FontID.PLAIN_11);
		label.setTextShadowed(true);
		label.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		label.setYPositionMode(yMode);
		label.setXTextAlignment(WidgetTextAlignment.CENTER);
		label.setYTextAlignment(WidgetTextAlignment.CENTER);
		label.setOriginalWidth(textWidth);
		label.setOriginalHeight(h);
		label.setOriginalX(x + PAD_RIGHT);
		label.setOriginalY(y);
		label.setHasListener(true);
		label.setAction(0, SYNC_ACTION);
		label.setOnOpListener((JavaScriptCallback) ev -> onClick.run());
		label.setOnMouseOverListener((JavaScriptCallback) ev -> setHover(true));
		label.setOnMouseLeaveListener((JavaScriptCallback) ev -> setHover(false));
		label.revalidate();

		this.text = label;
		this.attached = true;
		// Re-apply the current sync state so a rebuild mid-sync still shows "Syncing...".
		applyBusyState();
		parent.revalidate();
	}

	/** Flips the button into/out of its "Syncing..." state. Safe to call before attach —
	 *  the state is re-applied when the button is (re)built. */
	public void setBusy(boolean busy)
	{
		this.busy = busy;
		applyBusyState();
	}

	/** Forgets the button — call when the interface closes/rebuilds (its children are wiped).
	 *  The {@link #busy} flag is intentionally kept so an in-flight sync re-renders correctly. */
	public void reset()
	{
		java.util.Arrays.fill(slices, null);
		icon = null;
		text = null;
		attached = false;
	}

	/** While syncing, hide the icon and centre the longer "Syncing..." label across the
	 *  whole button; otherwise show the icon with the "Sync" label beside it. */
	private void applyBusyState()
	{
		if (text == null)
		{
			return;
		}
		int idleWidth = Math.max(1, BUTTON_WIDTH - PAD_LEFT - ICON_SIZE - ICON_GAP - PAD_RIGHT);
		int busyWidth = Math.max(1, BUTTON_WIDTH - PAD_LEFT - PAD_RIGHT);
		text.setText(busy ? LABEL_BUSY : LABEL_IDLE);
		text.setOriginalWidth(busy ? busyWidth : idleWidth);
		text.revalidate();
		if (icon != null)
		{
			icon.setHidden(busy);
			icon.revalidate();
		}
	}

	private void setHover(boolean hover)
	{
		int[] set = hover ? SLICE_HOVER : SLICE_IDLE;
		for (int i = 0; i < slices.length; i++)
		{
			if (slices[i] != null)
			{
				slices[i].setSpriteId(set[i]);
			}
		}
		if (text != null)
		{
			text.setTextColor(hover ? FONT_COLOUR_HOVER : FONT_COLOUR_IDLE);
		}
	}

	private static Widget slice(Widget parent, int spriteId, int x, int y, int w, int h, int yMode, boolean tile)
	{
		Widget s = parent.createChild(-1, WidgetType.GRAPHIC);
		s.setSpriteId(spriteId);
		s.setSpriteTiling(tile);
		s.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		s.setYPositionMode(yMode);
		s.setOriginalWidth(w);
		s.setOriginalHeight(h);
		s.setOriginalX(x);
		s.setOriginalY(y);
		return s;
	}

	/** Finds our clickable text overlay among the container's children (identified by its
	 *  menu action), or null if the button isn't present. */
	private static Widget findExistingText(Widget parent)
	{
		Widget[] dyn = parent.getDynamicChildren();
		if (dyn == null)
		{
			return null;
		}
		for (Widget w : dyn)
		{
			if (w == null)
			{
				continue;
			}
			String[] actions = w.getActions();
			if (actions == null)
			{
				continue;
			}
			for (String a : actions)
			{
				if (SYNC_ACTION.equals(a))
				{
					return w;
				}
			}
		}
		return null;
	}
}
