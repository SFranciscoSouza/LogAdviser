package com.logadviser;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("logadviser")
public interface LogAdviserConfig extends Config
{
	@ConfigItem(
		keyName = "enabled",
		name = "Enabled",
		description = "Enable Log Adviser suggestions"
	)
	default boolean enabled()
	{
		return true;
	}
}
