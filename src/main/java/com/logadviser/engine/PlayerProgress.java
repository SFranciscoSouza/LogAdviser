package com.logadviser.engine;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import lombok.Value;

/**
 * Immutable snapshot of the bits of player state that gate activities: real
 * (un-boosted) skill levels and the set of finished quests. Built on the client
 * thread by the plugin and pushed into {@link AdviserEngine}. {@link #EMPTY} means
 * "nothing known yet" — everything is treated as locked until the first refresh.
 */
@Value
public class PlayerProgress
{
	Map<Skill, Integer> realLevels;
	Set<Quest> finishedQuests;

	public static final PlayerProgress EMPTY =
		new PlayerProgress(Collections.emptyMap(), Collections.emptySet());

	public int level(Skill skill)
	{
		Integer l = realLevels.get(skill);
		return l == null ? 0 : l;
	}

	public boolean isFinished(Quest quest)
	{
		return finishedQuests.contains(quest);
	}
}
