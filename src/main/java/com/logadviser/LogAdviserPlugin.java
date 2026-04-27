package com.logadviser;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Log Adviser",
	description = "Suggests collection log activities personalized to your log",
	tags = {"collection", "log", "clog", "adviser", "advisor"}
)
public class LogAdviserPlugin extends Plugin
{
	@Inject
	private LogAdviserConfig config;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Log Adviser started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Log Adviser stopped");
	}

	@Provides
	LogAdviserConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LogAdviserConfig.class);
	}
}
