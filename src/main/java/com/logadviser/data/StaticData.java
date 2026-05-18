package com.logadviser.data;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Value;

@Value
public class StaticData
{
	List<Activity> activities;
	List<ActivityItem> activityItems;
	List<LogSlot> slots;
	Map<Integer, ActivityNpcInfo> npcInfoByActivity;

	public Map<Integer, Activity> activitiesByIndex()
	{
		java.util.Map<Integer, Activity> m = new java.util.HashMap<>(activities.size() * 2);
		for (Activity a : activities)
		{
			m.put(a.getIndex(), a);
		}
		return Collections.unmodifiableMap(m);
	}

	public Map<Integer, LogSlot> slotsByItemId()
	{
		java.util.Map<Integer, LogSlot> m = new java.util.HashMap<>(slots.size() * 2);
		for (LogSlot s : slots)
		{
			m.put(s.getItemId(), s);
		}
		// Map alternate (e.g. Body-type-B) item ids onto their canonical slot so the
		// collection-log widget scrape recognises them too.
		for (Map.Entry<Integer, Integer> e : ItemAliases.canonicalByAlt().entrySet())
		{
			LogSlot canonical = m.get(e.getValue());
			if (canonical != null)
			{
				m.put(e.getKey(), canonical);
			}
		}
		return Collections.unmodifiableMap(m);
	}

	public Map<String, Integer> itemIdsByName()
	{
		java.util.Map<String, Integer> m = new java.util.HashMap<>(slots.size() * 2);
		for (LogSlot s : slots)
		{
			m.put(s.getSlotName().toLowerCase(), s.getItemId());
		}
		// Chat-message names that the canonical slotName never matches verbatim.
		m.putAll(ItemAliases.extraNames());
		return Collections.unmodifiableMap(m);
	}

	public int canonicalItemId(int itemId)
	{
		return ItemAliases.canonical(itemId);
	}

	public ActivityNpcInfo npcInfoFor(int activityIndex)
	{
		ActivityNpcInfo info = npcInfoByActivity.get(activityIndex);
		return info != null ? info : ActivityNpcInfo.empty();
	}
}
