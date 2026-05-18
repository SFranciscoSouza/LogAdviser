package com.logadviser.ui;

import com.logadviser.sync.CollectionLogSyncState;
import com.logadviser.sync.CollectionLogWidgets;
import com.logadviser.sync.CollectionLogWidgets.ClogTab;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws a yellow "*" next to every un-synced collection log page in the visible list
 * (clipped to the scroll viewport so it never bleeds outside the window) and on each
 * top tab button that still has an un-synced page. Renders only while the collection
 * log is open; also feeds each tab's category total back into {@link CollectionLogSyncState}.
 */
public class CollectionLogSyncOverlay extends Overlay
{
	private static final Color STAR = new Color(255, 221, 0);
	private static final String STAR_TEXT = "*";

	private final Client client;
	private final CollectionLogSyncState syncState;

	@Inject
	public CollectionLogSyncOverlay(Client client, CollectionLogSyncState syncState)
	{
		this.client = client;
		this.syncState = syncState;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!CollectionLogWidgets.isOpen(client))
		{
			return null;
		}

		int ascent = graphics.getFontMetrics().getAscent();

		ClogTab active = CollectionLogWidgets.activeTab(client);
		if (active != null)
		{
			java.util.List<Widget> entries = CollectionLogWidgets.pageEntries(client, active);
			syncState.recordTabTotal(active.tabIndex(), entries.size());

			Widget list = client.getWidget(active.listWidgetId());
			Widget parent = list == null ? null : list.getParent();
			// The scroll container (parent) is the visible viewport; the list widget
			// itself spans the full scrolled content height. Clip to the viewport so
			// stars on scrolled-off rows never bleed outside the window.
			Rectangle viewport = parent != null ? parent.getBounds()
				: (list != null ? list.getBounds() : null);
			if (viewport != null)
			{
				java.awt.Shape oldClip = graphics.getClip();
				graphics.setClip(viewport);
				for (int p = 0; p < entries.size(); p++)
				{
					Widget entry = entries.get(p);
					if (entry.isHidden() || syncState.isSyncedPage(active.tabIndex(), p))
					{
						continue;
					}
					Rectangle b = entry.getBounds();
					if (b == null || b.width <= 0 || b.height <= 0 || !viewport.intersects(b))
					{
						continue;
					}
					// Anchor inside the row at its right edge so it stays within the
					// scrollable area and never clips past the window.
					int x = b.x + b.width - 9;
					int y = b.y + (b.height + ascent) / 2 - 1;
					draw(graphics, x, y);
				}
				graphics.setClip(oldClip);
			}
		}

		for (ClogTab tab : ClogTab.values())
		{
			Widget tabWidget = client.getWidget(tab.tabWidgetId());
			if (tabWidget == null || tabWidget.isHidden())
			{
				continue;
			}
			if (syncState.tabFullySynced(tab.tabIndex()))
			{
				continue;
			}
			Rectangle b = tabWidget.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				continue;
			}
			draw(graphics, b.x + b.width - 7, b.y + ascent);
		}

		return null;
	}

	private static void draw(Graphics2D g, int x, int y)
	{
		g.setColor(Color.BLACK);
		g.drawString(STAR_TEXT, x + 1, y + 1);
		g.setColor(STAR);
		g.drawString(STAR_TEXT, x, y);
	}
}
