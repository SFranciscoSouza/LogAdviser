package com.logadviser.data;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class StaticDataLoader
{
	private static final String BASE = "/com/logadviser/data/";
	private static final Gson GSON = new Gson();

	private StaticDataLoader()
	{
	}

	public static StaticData loadAll() throws IOException
	{
		List<Activity> activities = loadActivities();
		List<ActivityItem> items = loadActivityItems();
		List<LogSlot> slots = loadSlots();
		Map<Integer, ActivityNpcInfo> npcInfo = loadNpcInfo();
		log.debug("Loaded static data: {} activities, {} items, {} slots, {} npc maps",
			activities.size(), items.size(), slots.size(), npcInfo.size());
		return new StaticData(
			Collections.unmodifiableList(activities),
			Collections.unmodifiableList(items),
			Collections.unmodifiableList(slots),
			Collections.unmodifiableMap(npcInfo));
	}

	private static List<Activity> loadActivities() throws IOException
	{
		Type listType = new TypeToken<List<RawActivity>>(){}.getType();
		try (InputStreamReader r = open("activities.json"))
		{
			List<RawActivity> raw = GSON.fromJson(r, listType);
			List<Activity> out = new ArrayList<>(raw.size());
			for (RawActivity a : raw)
			{
				out.add(new Activity(
					a.index,
					a.name,
					a.completionsPerHrMain,
					a.completionsPerHrIron,
					a.extraTimeFirst,
					Category.fromString(a.category)));
			}
			return out;
		}
	}

	private static List<ActivityItem> loadActivityItems() throws IOException
	{
		Type listType = new TypeToken<List<RawActivityItem>>(){}.getType();
		try (InputStreamReader r = open("activity_map.json"))
		{
			List<RawActivityItem> raw = GSON.fromJson(r, listType);
			List<ActivityItem> out = new ArrayList<>(raw.size());
			for (RawActivityItem ri : raw)
			{
				out.add(new ActivityItem(
					ri.activityIndex,
					ri.itemId,
					ri.itemName,
					ri.slotDifficulty,
					ri.requiresPrevious,
					ri.exact,
					ri.independent,
					ri.dropRateAttempts));
			}
			return out;
		}
	}

	private static List<LogSlot> loadSlots() throws IOException
	{
		Type listType = new TypeToken<List<RawSlot>>(){}.getType();
		try (InputStreamReader r = open("slots.json"))
		{
			List<RawSlot> raw = GSON.fromJson(r, listType);
			List<LogSlot> out = new ArrayList<>(raw.size());
			for (RawSlot rs : raw)
			{
				out.add(new LogSlot(rs.itemId, rs.slotName, rs.activityCount));
			}
			return out;
		}
	}

	private static Map<Integer, ActivityNpcInfo> loadNpcInfo() throws IOException
	{
		try (InputStreamReader r = open("activity_npcs.json"))
		{
			JsonObject root = GSON.fromJson(r, JsonObject.class);
			Map<Integer, ActivityNpcInfo> out = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : root.entrySet())
			{
				if (e.getKey().startsWith("_"))
				{
					continue;
				}
				int idx;
				try
				{
					idx = Integer.parseInt(e.getKey());
				}
				catch (NumberFormatException nfe)
				{
					continue;
				}
				JsonObject obj = e.getValue().getAsJsonObject();
				List<Integer> ids = new ArrayList<>();
				if (obj.has("npcIds"))
				{
					obj.getAsJsonArray("npcIds").forEach(je -> ids.add(je.getAsInt()));
				}
				String hint = obj.has("hint") ? obj.get("hint").getAsString() : "";
				out.put(idx, new ActivityNpcInfo(Collections.unmodifiableList(ids), hint));
			}
			return out;
		}
	}

	private static InputStreamReader open(String name) throws IOException
	{
		InputStream in = StaticDataLoader.class.getResourceAsStream(BASE + name);
		if (in == null)
		{
			throw new IOException("Missing resource: " + BASE + name);
		}
		return new InputStreamReader(in, StandardCharsets.UTF_8);
	}

	private static final class RawActivity
	{
		int index;
		String name;
		double completionsPerHrMain;
		double completionsPerHrIron;
		double extraTimeFirst;
		String category;
	}

	private static final class RawActivityItem
	{
		int activityIndex;
		int itemId;
		String itemName;
		int slotDifficulty;
		boolean requiresPrevious;
		boolean exact;
		boolean independent;
		double dropRateAttempts;
	}

	private static final class RawSlot
	{
		int itemId;
		String slotName;
		int activityCount;
	}
}
