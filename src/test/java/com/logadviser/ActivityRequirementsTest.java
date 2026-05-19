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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

		// Skill-only entries.
		assertEquals(Integer.valueOf(85), data.requirementsFor(1).getSkillLevels().get(Skill.SLAYER));
		assertEquals(Integer.valueOf(95), data.requirementsFor(2).getSkillLevels().get(Skill.SLAYER));
		assertTrue("activity 1 has no quest gate", data.requirementsFor(1).getQuests().isEmpty());

		// Skill + quest entry: 47 = Slayer 51 + Troubled Tortugans.
		ActivityRequirements a47 = data.requirementsFor(47);
		assertEquals(Integer.valueOf(51), a47.getSkillLevels().get(Skill.SLAYER));
		assertEquals("Troubled Tortugans", questName(a47, "Troubled Tortugans"));

		// Quest-only entry: 251 = The Ides of Milk.
		assertEquals("The Ides of Milk",
			questName(data.requirementsFor(251), "The Ides of Milk"));

		// A multi-word, punctuated quest name must resolve too.
		assertEquals("Desert Treasure II - The Fallen Empire",
			questName(data.requirementsFor(17), "Desert Treasure II - The Fallen Empire"));
	}

	@Test
	public void everyQuestTokenResolves()
	{
		StringBuilder unresolved = new StringBuilder();
		for (Map.Entry<Integer, ActivityRequirements> e
			: data.getRequirementsByActivity().entrySet())
		{
			ActivityRequirements r = e.getValue();
			if (r.getQuests().size() != r.getRawQuestStrings().size())
			{
				unresolved.append("\n  activity ").append(e.getKey())
					.append(": raw=").append(r.getRawQuestStrings())
					.append(" resolved=").append(r.getQuests().size());
			}
		}
		assertEquals("some quest tokens did not resolve to a RuneLite Quest:"
			+ unresolved, 0, unresolved.length());
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
		assertEquals("85 Slayer", act1.getRequirementLabel());
		assertEquals("95 Slayer", act2.getRequirementLabel());

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
	public void meetingRequirementsUnlocks()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		Quest tortugans = data.requirementsFor(47).getQuests().get(0);

		// Slayer maxed but Troubled Tortugans not done → 47 still locked on the quest.
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		levels.put(Skill.SLAYER, 99);
		engine.setPlayerProgress(new PlayerProgress(levels, Collections.<Quest>emptySet()));
		assertFalse("skill-only gate clears at 99 Slayer", find(engine.getRanking(), 1).isLocked());
		assertTrue("quest gate still locks 47", find(engine.getRanking(), 47).isLocked());
		assertEquals("Troubled Tortugans", find(engine.getRanking(), 47).getRequirementLabel());

		// Quest finished as well → 47 unlocks.
		Set<Quest> done = new HashSet<>();
		done.add(tortugans);
		engine.setPlayerProgress(new PlayerProgress(levels, done));
		assertFalse("47 unlocks once Slayer and quest are met",
			find(engine.getRanking(), 47).isLocked());
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

	/** Asserts the requirement set resolved a quest whose display name matches, and
	 *  returns that name (so a resolution failure shows up as a clear assertion). */
	private static String questName(ActivityRequirements req, String expectedDisplay)
	{
		for (Quest q : req.getQuests())
		{
			if (q.getName().equals(expectedDisplay))
			{
				return q.getName();
			}
		}
		assertNotNull("quest '" + expectedDisplay + "' failed to resolve (got " + req.getQuests()
			+ ")", null);
		return null;
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
