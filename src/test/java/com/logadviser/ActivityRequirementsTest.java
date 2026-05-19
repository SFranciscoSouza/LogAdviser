package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.ActivityRequirements;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.PlayerProgress;
import com.logadviser.engine.RankedActivity;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the skill/quest gating end-to-end against the real activity_requirements.json,
 * with no RuneLite client involved — so a failure here is a code bug, and a pass means
 * any in-client failure is a stale-build / wrong-launch-path issue.
 */
public class ActivityRequirementsTest
{
	private StaticData data;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
	}

	@Test
	public void requirementsJsonLoaded()
	{
		assertFalse("activity_requirements.json produced no entries — resource not on "
			+ "the classpath or failed to parse", data.getRequirementsByActivity().isEmpty());

		ActivityRequirements a1 = data.requirementsFor(1);
		ActivityRequirements a2 = data.requirementsFor(2);
		assertEquals(Integer.valueOf(85), a1.getSkillLevels().get(Skill.SLAYER));
		assertEquals(Integer.valueOf(95), a2.getSkillLevels().get(Skill.SLAYER));
		// Quest tokens must resolve from their display names.
		assertTrue("Cook's Assistant must resolve", a1.getQuests().contains(Quest.COOKS_ASSISTANT));
		assertTrue("Recipe for Disaster must resolve",
			a2.getQuests().contains(Quest.RECIPE_FOR_DISASTER));
	}

	@Test
	public void lowSlayerLocksAndDemotesGatedActivities()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);

		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		levels.put(Skill.SLAYER, 27);
		engine.setPlayerProgress(new PlayerProgress(levels, Collections.<Quest>emptySet()));

		List<RankedActivity> ranking = engine.getRanking();

		RankedActivity act1 = find(ranking, 1);
		RankedActivity act2 = find(ranking, 2);
		assertTrue("activity 1 should be locked at 27 Slayer", act1.isLocked());
		assertTrue("activity 2 should be locked at 27 Slayer", act2.isLocked());
		// Skill listed first, then unmet quests, by display name.
		assertEquals("85 Slayer, Cook's Assistant", act1.getRequirementLabel());
		assertEquals("95 Slayer, Recipe for Disaster", act2.getRequirementLabel());

		// Every locked entry must sort after every unlocked entry.
		int firstLocked = -1;
		int lastUnlocked = -1;
		for (int i = 0; i < ranking.size(); i++)
		{
			if (ranking.get(i).isLocked())
			{
				if (firstLocked < 0)
				{
					firstLocked = i;
				}
			}
			else
			{
				lastUnlocked = i;
			}
		}
		assertTrue("locked activities must be demoted below all unlocked ones",
			firstLocked > lastUnlocked);
	}

	@Test
	public void meetingTheLevelUnlocks()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		levels.put(Skill.SLAYER, 99);
		Set<Quest> done = EnumSet.of(Quest.COOKS_ASSISTANT, Quest.RECIPE_FOR_DISASTER);
		engine.setPlayerProgress(new PlayerProgress(levels, done));

		// Level met but quest still outstanding → still locked on the quest alone.
		levels.put(Skill.SLAYER, 99);
		engine.setPlayerProgress(new PlayerProgress(levels, Collections.<Quest>emptySet()));
		assertTrue("quest requirement alone must keep it locked",
			find(engine.getRanking(), 1).isLocked());
		assertEquals("Cook's Assistant", find(engine.getRanking(), 1).getRequirementLabel());

		// Both level and quest met → unlocked.
		engine.setPlayerProgress(new PlayerProgress(levels, done));
		assertFalse(find(engine.getRanking(), 1).isLocked());
		assertFalse(find(engine.getRanking(), 2).isLocked());
	}

	@Test
	public void ignoreRequirementsUnlocksEverything()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		levels.put(Skill.SLAYER, 1);
		engine.setPlayerProgress(new PlayerProgress(levels, Collections.<Quest>emptySet()));
		assertTrue(find(engine.getRanking(), 1).isLocked());

		engine.setIgnoreRequirements(true);
		assertFalse("ignore-requirements must unlock gated activities",
			find(engine.getRanking(), 1).isLocked());
	}

	private static RankedActivity find(List<RankedActivity> ranking, int activityIndex)
	{
		for (RankedActivity r : ranking)
		{
			if (r.getActivity().getIndex() == activityIndex)
			{
				return r;
			}
		}
		throw new AssertionError("activity index " + activityIndex + " not in ranking");
	}
}
