package com.logadviser.data;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import lombok.Value;

/**
 * Skill/quest gating for a single activity. All requirements are AND-ed: the player
 * must satisfy every skill level and have every quest finished. {@code rawQuestStrings}
 * keeps the original JSON tokens so we can still label a quest we failed to resolve.
 */
@Value
public class ActivityRequirements
{
	Map<Skill, Integer> skillLevels;
	List<Quest> quests;
	List<String> rawQuestStrings;

	public boolean isEmpty()
	{
		return skillLevels.isEmpty() && quests.isEmpty();
	}

	public static ActivityRequirements empty()
	{
		return new ActivityRequirements(
			Collections.emptyMap(),
			Collections.emptyList(),
			Collections.emptyList());
	}
}
