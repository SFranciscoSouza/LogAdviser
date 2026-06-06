package com.logadviser;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(LogAdviserConfig.GROUP)
public interface LogAdviserConfig extends Config
{
	String GROUP = "logadviser";

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
		description = "Where to surface the next collection log target. The info box lives in the top-left tray; the overlay box renders on top of the game viewport.",
		position = 1
	)
	default DisplayMode displayMode()
	{
		return DisplayMode.INFOBOX;
	}

	@ConfigItem(
		keyName = "upcomingListSize",
		name = "Upcoming list size",
		description = "Number of upcoming activities shown in the sidebar list.",
		position = 2
	)
	default int upcomingListSize()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "disableHover",
		name = "Disable hover preview",
		description = "Stop showing the pop-up list of an activity's log slots when hovering rows in the sidebar panel.",
		position = 3
	)
	default boolean disableHover()
	{
		return false;
	}

	// Standing guidance placed BELOW the options. Kept as an empty section at the bottom: RuneLite
	// builds every section into the config panel regardless of whether it holds items, so the header
	// renders on its own. The name is HTML so the JLabel wraps it across lines instead of clipping,
	// and closedByDefault leaves no empty expanded area under it — just the wrapping text.
	@ConfigSection(
		name = "<html>To see the complete list and more functionalities<br>"
			+ "open the side bar panel with the collection log book<br>"
			+ "icon on the right side</html>",
		description = "Open the Log Adviser side panel (collection log book icon) for the full "
			+ "ranked activity list, hover previews, the skip list and more.",
		position = 100,
		closedByDefault = true
	)
	String infoSection = "infoSection";
}
