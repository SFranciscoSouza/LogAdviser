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
		return Collections.unmodifiableMap(m);
	}

	public Map<String, Integer> itemIdsByName()
	{
		java.util.Map<String, Integer> m = new java.util.HashMap<>(slots.size() * 2);
		for (LogSlot s : slots)
		{
			m.put(s.getSlotName().toLowerCase(), s.getItemId());
		}
		return Collections.unmodifiableMap(m);
	}

	public ActivityNpcInfo npcInfoFor(int activityIndex)
	{
		ActivityNpcInfo info = npcInfoByActivity.get(activityIndex);
		return info != null ? info : ActivityNpcInfo.empty();
	}
}
