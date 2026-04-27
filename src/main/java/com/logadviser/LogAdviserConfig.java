package com.logadviser;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(LogAdviserConfig.GROUP)
public interface LogAdviserConfig extends Config
{
	String GROUP = "logadviser";

	@ConfigItem(
		keyName = "templeWarmStart",
		name = "TempleOSRS warm-start",
		description = "On first launch with empty cache, query TempleOSRS to seed the obtained-items list before you click through the in-game collection log."
	)
	default boolean templeWarmStart()
	{
		return true;
	}

	enum DisplayMode
	{
		INFOBOX("Info box (top-left tray)"),
		OVERLAY_PANEL("Overlay box"),
		BOTH("Both"),
		NONE("None");

		private final String label;

		DisplayMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}

		public boolean infoBox()
		{
			return this == INFOBOX || this == BOTH;
		}

		public boolean overlayPanel()
		{
			return this == OVERLAY_PANEL || this == BOTH;
		}
	}

	@ConfigItem(
		keyName = "displayMode",
		name = "Show next slot as",
		description = "Where to surface the next collection log target. The info box lives in the top-left tray; the overlay box renders on top of the game viewport."
	)
	default DisplayMode displayMode()
	{
		return DisplayMode.OVERLAY_PANEL;
	}

	@ConfigItem(
		keyName = "highlightNpcs",
		name = "Highlight target NPCs",
		description = "Draw a light-blue convex hull around NPCs that drop the current target slot."
	)
	default boolean highlightNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "upcomingListSize",
		name = "Upcoming list size",
		description = "Number of upcoming activities shown in the sidebar list."
	)
	default int upcomingListSize()
	{
		return 30;
	}
}
