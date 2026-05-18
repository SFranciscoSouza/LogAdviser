package com.logadviser.sync;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Static description of the in-game collection log interface tabs and helpers to read
 * the currently visible page list. The collection log has five top tabs; each tab owns
 * a "*_TEXT" widget whose children are the clickable page-name entries, and a "*_TAB"
 * button. Only the active tab's list widget is non-hidden, which is how we detect the
 * selected tab without reading an account varbit.
 */
public final class CollectionLogWidgets
{
	private CollectionLogWidgets()
	{
	}

	public enum ClogTab
	{
		// tabIndex matches the on-screen left-to-right tab order (= VarbitID.COLLECTION_LAST_TAB).
		BOSS("Bosses", 0, InterfaceID.Collection.BOSS_TEXT, InterfaceID.Collection.BOSS_TAB),
		RAID("Raids", 1, InterfaceID.Collection.RAID_TEXT, InterfaceID.Collection.RAID_TAB),
		CLUE("Clues", 2, InterfaceID.Collection.CLUE_TEXT, InterfaceID.Collection.CLUE_TAB),
		MINIGAME("Minigames", 3, InterfaceID.Collection.MINIGAME_TEXT, InterfaceID.Collection.MINIGAME_TAB),
		OTHER("Other", 4, InterfaceID.Collection.OTHER_TEXT, InterfaceID.Collection.OTHER_TAB);

		private final String key;
		private final int tabIndex;
		private final int listWidgetId;
		private final int tabWidgetId;

		ClogTab(String key, int tabIndex, int listWidgetId, int tabWidgetId)
		{
			this.key = key;
			this.tabIndex = tabIndex;
			this.listWidgetId = listWidgetId;
			this.tabWidgetId = tabWidgetId;
		}

		public String key()
		{
			return key;
		}

		public int tabIndex()
		{
			return tabIndex;
		}

		public int listWidgetId()
		{
			return listWidgetId;
		}

		public int tabWidgetId()
		{
			return tabWidgetId;
		}
	}

	/** True when the collection log interface is open and drawn. */
	public static boolean isOpen(Client client)
	{
		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		return header != null && !header.isHidden();
	}

	/** The tab whose page list is currently visible, or null if none. */
	public static ClogTab activeTab(Client client)
	{
		for (ClogTab tab : ClogTab.values())
		{
			Widget list = client.getWidget(tab.listWidgetId);
			if (list != null && !list.isHidden())
			{
				return tab;
			}
		}
		return null;
	}

	/** The clickable page-name entry widgets for a tab's list (may be empty). */
	public static List<Widget> pageEntries(Client client, ClogTab tab)
	{
		List<Widget> out = new ArrayList<>();
		Widget list = client.getWidget(tab.listWidgetId);
		if (list == null)
		{
			return out;
		}
		collect(out, list.getDynamicChildren());
		if (out.isEmpty())
		{
			collect(out, list.getChildren());
		}
		return out;
	}

	private static void collect(List<Widget> out, Widget[] children)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			if (child != null && !clean(child.getText()).isEmpty())
			{
				out.add(child);
			}
		}
	}

	/** Strips Jagex colour/format tags and trims, never returns null. */
	public static String clean(String raw)
	{
		if (raw == null)
		{
			return "";
		}
		return Text.removeTags(raw).trim();
	}
}
