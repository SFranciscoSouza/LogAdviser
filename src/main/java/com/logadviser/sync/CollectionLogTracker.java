package com.logadviser.sync;

import com.logadviser.data.LogSlot;
import com.logadviser.data.StaticData;
import com.logadviser.engine.AdviserEngine;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Three-way input pump that converges into AdviserEngine.markObtained:
 *   1) ChatMessage "New item added to your collection log" — live drop notifications.
 *   2) ScriptPostFired id 2731 — fires every time the in-game collection log widget
 *      redraws a tab; we walk the items list and mark every faded child as obtained.
 *   3) TempleOSRS warm-start (one-shot) when local cache is empty.
 *
 * Persists per-character via RSProfile config so each alt has its own state.
 */
@Slf4j
@Singleton
public class CollectionLogTracker
{
	private static final String CONFIG_GROUP = "logadviser";
	private static final String CONFIG_KEY = "obtained";
	private static final String CONFIG_KEY_SKIPPED = "skipped";
	private static final int COLLECTION_LOG_DRAW_SCRIPT_ID = 2731;
	private static final String NEW_ITEM_PREFIX = "New item added to your collection log:";

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final StaticData staticData;
	private final AdviserEngine engine;
	private final TempleOsrsClient temple;

	private final Map<String, Integer> itemIdsByName;

	private boolean warmStartAttempted = false;

	@Inject
	public CollectionLogTracker(
		Client client,
		ClientThread clientThread,
		ConfigManager configManager,
		StaticData staticData,
		AdviserEngine engine,
		TempleOsrsClient temple)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.staticData = staticData;
		this.engine = engine;
		this.temple = temple;
		this.itemIdsByName = staticData.itemIdsByName();
	}

	public void load()
	{
		Set<Integer> ids = parseCsv(configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY));
		Set<Integer> skips = parseCsv(configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_SKIPPED));
		engine.replaceObtained(ids);
		engine.replaceSkipped(skips);
	}

	public void persist()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY, toCsv(engine.obtainedItems()));
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_SKIPPED, toCsv(engine.skippedActivities()));
	}

	public void persistSkipped()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_SKIPPED, toCsv(engine.skippedActivities()));
	}

	private void markAndPersist(int itemId)
	{
		boolean wasObtained = engine.isObtained(itemId);
		engine.markObtained(itemId);
		if (!wasObtained)
		{
			persist();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Defer one tick so RSProfile has the display name attached.
			clientThread.invokeLater(() ->
			{
				load();
				maybeWarmStart();
				return true;
			});
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		switch (event.getType())
		{
			case GAMEMESSAGE:
			case SPAM:
				break;
			default:
				return;
		}
		String message = Text.removeTags(event.getMessage());
		if (!message.startsWith(NEW_ITEM_PREFIX))
		{
			return;
		}
		String itemName = message.substring(NEW_ITEM_PREFIX.length()).trim();
		Integer id = itemIdsByName.get(itemName.toLowerCase());
		if (id == null)
		{
			log.debug("Could not resolve collection log item name: {}", itemName);
			return;
		}
		markAndPersist(id);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != COLLECTION_LOG_DRAW_SCRIPT_ID)
		{
			return;
		}
		Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (items == null)
		{
			return;
		}
		Widget[] children = items.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			return;
		}
		boolean any = false;
		int seen = 0;
		for (Widget child : children)
		{
			if (child == null)
			{
				continue;
			}
			int itemId = child.getItemId();
			if (itemId <= 0)
			{
				continue;
			}
			seen++;
			// In the collection log: opacity 0 == drawn at full color (obtained),
			// opacity 175 == faded (not obtained).
			if (child.getOpacity() != 0)
			{
				continue;
			}
			if (!engine.isObtained(itemId) && staticData.slotsByItemId().containsKey(itemId))
			{
				engine.markObtained(itemId);
				any = true;
			}
		}
		log.debug("Collection log tab scrape: {} item slots seen, {} new obtained", seen, any ? 1 : 0);
		if (any)
		{
			persist();
		}
	}

	private void maybeWarmStart()
	{
		if (warmStartAttempted)
		{
			return;
		}
		warmStartAttempted = true;
		if (!engine.obtainedItems().isEmpty())
		{
			return;
		}
		String displayName = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
		if (displayName == null || displayName.isEmpty())
		{
			return;
		}
		temple.fetchObtainedAsync(displayName, ids ->
		{
			if (ids == null || ids.isEmpty())
			{
				return;
			}
			clientThread.invoke(() ->
			{
				if (!engine.obtainedItems().isEmpty())
				{
					return;
				}
				engine.replaceObtained(filterToKnownSlots(ids));
				persist();
				log.debug("TempleOSRS warm-start populated {} obtained items for {}", ids.size(), displayName);
			});
		});
	}

	private Set<Integer> filterToKnownSlots(Set<Integer> ids)
	{
		Set<Integer> known = staticData.slotsByItemId().keySet();
		Set<Integer> out = new HashSet<>(ids.size());
		for (Integer id : ids)
		{
			if (known.contains(id))
			{
				out.add(id);
			}
		}
		return out;
	}

	private static Set<Integer> parseCsv(String csv)
	{
		if (csv == null || csv.isEmpty())
		{
			return new HashSet<>();
		}
		return Arrays.stream(csv.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.map(s ->
			{
				try
				{
					return Integer.parseInt(s);
				}
				catch (NumberFormatException ex)
				{
					return null;
				}
			})
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toCollection(HashSet::new));
	}

	private static String toCsv(Set<Integer> ids)
	{
		if (ids.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(ids.size() * 6);
		boolean first = true;
		for (Integer id : ids)
		{
			if (!first)
			{
				sb.append(',');
			}
			sb.append(id);
			first = false;
		}
		return sb.toString();
	}

	// Convenience for slot lookups by item id (used by panel for display names).
	public LogSlot slotFor(int itemId)
	{
		return staticData.slotsByItemId().get(itemId);
	}
}
