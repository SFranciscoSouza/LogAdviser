package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.ActivityRequirements;
import com.logadviser.data.QuestResolver;
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
import static org.junit.Assume.assumeTrue;

/**
 * Proves the skill/quest gating end-to-end against the real activity_requirements.json,
 * with no RuneLite client involved — so a failure here is a code bug, and a pass means
 * any in-client failure is a stale-build / wrong-launch-path issue.
 */
public class ActivityRequirementsTest
{
	// Quest tokens intentionally present in activity_requirements.json but not yet in the
	// released RuneLite Quest enum (the build resolves 'latest.release'). QuestResolver returns
	// null for these, so the gate stays inert until a RuneLite release adds the quest, at which
	// point it activates automatically. Listed here (normalised lower-case) so
	// everyQuestTokenResolves treats them as pending rather than as typos. Remove an entry once
	// RuneLite ships the quest.
	// "The Blood Moon Rises" was removed from this list once RuneLite shipped it (the running
	// build resolves it, so the guard now covers that token again).
	private static final Set<String> PENDING_QUESTS = new HashSet<>(Collections.singletonList(
		"fallen from grace"));

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
			for (String q : r.getRawQuestStrings())
			{
				// A pending quest (not yet in the running RuneLite's Quest enum) is allowed to be
				// unresolved; any other token that fails to resolve is a real typo. Once RuneLite
				// ships the quest, resolve() returns non-null and this still passes — remove the
				// allowlist entry then so the guard resumes catching that token.
				if (QuestResolver.resolve(q) == null
					&& !PENDING_QUESTS.contains(q.trim().toLowerCase()))
				{
					unresolved.append("\n  activity ").append(e.getKey())
						.append(": unresolved quest '").append(q).append('\'');
				}
			}
		}
		assertEquals("some quest tokens did not resolve to a RuneLite Quest:"
			+ unresolved, 0, unresolved.length());
	}

	@Test
	public void bloodMoonGateActivatesWhenRuneLiteKnowsTheQuest()
	{
		// "The Blood Moon Rises" gates activities 255 (Killing Maggot King) and 256 (Killing
		// Venators). It only reached RuneLite's Quest enum in 1.12.32; on older clients the token
		// is unresolvable, so this skips there and asserts once the running RuneLite has it —
		// proving the gate wires up (rather than silently doing nothing).
		Quest bloodMoon = QuestResolver.resolve("The Blood Moon Rises");
		assumeTrue("skipped: this RuneLite build has no 'The Blood Moon Rises' quest yet",
			bloodMoon != null);

		assertTrue("Blood Moon must be the resolved gate for activity 255",
			data.requirementsFor(255).getQuests().contains(bloodMoon));
		assertTrue("Blood Moon must be the resolved gate for activity 256",
			data.requirementsFor(256).getQuests().contains(bloodMoon));

		AdviserEngine engine = new AdviserEngine(data, () -> false);
		engine.setPlayerProgress(new PlayerProgress(
			new EnumMap<>(Skill.class), Collections.<Quest>emptySet()));
		List<RankedActivity> ranking = engine.getRanking();
		assertTrue("Killing Maggot King (255) is locked without the quest",
			find(ranking, 255).isLocked());
		assertTrue("Killing Venators (256) is locked without the quest",
			find(ranking, 256).isLocked());
		assertEquals("The Blood Moon Rises", find(ranking, 255).getRequirementLabel());

		Set<Quest> done = new HashSet<>();
		done.add(bloodMoon);
		engine.setPlayerProgress(new PlayerProgress(new EnumMap<>(Skill.class), done));
		assertFalse("255 unlocks once the quest is complete",
			find(engine.getRanking(), 255).isLocked());
	}

	@Test
	public void fallenFromGraceGateActivatesWhenRuneLiteKnowsTheQuest()
	{
		// "Fallen From Grace" gates the three Wyrmscraig activities: 257 (Golem crafting),
		// 258 (Hunting Wyrmscraig goats) and 259 (Killing Mad Angel). It shipped 29 July 2026
		// and only reaches RuneLite's Quest enum in a later release, so this skips on older
		// clients and asserts once the running RuneLite has it — proving the gate wires up
		// rather than silently doing nothing.
		Quest fallen = QuestResolver.resolve("Fallen From Grace");
		assumeTrue("skipped: this RuneLite build has no 'Fallen From Grace' quest yet",
			fallen != null);

		for (int idx : new int[]{257, 258, 259})
		{
			assertTrue("Fallen From Grace must be a resolved gate for activity " + idx,
				data.requirementsFor(idx).getQuests().contains(fallen));
		}

		// Max the gated skills so the quest is the only thing that can still hold these locked.
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		levels.put(Skill.CRAFTING, 99);
		levels.put(Skill.HUNTER, 99);
		levels.put(Skill.SAILING, 99);

		AdviserEngine engine = new AdviserEngine(data, () -> false);
		engine.setPlayerProgress(new PlayerProgress(levels, Collections.<Quest>emptySet()));
		assertTrue("Golem crafting (257) stays locked on the quest alone",
			find(engine.getRanking(), 257).isLocked());
		assertTrue("Killing Mad Angel (259) stays locked on the quest alone",
			find(engine.getRanking(), 259).isLocked());

		// 258 is deliberately left out below: it also requires Sheep Herder, so completing
		// Fallen From Grace alone must not unlock it.
		Set<Quest> done = new HashSet<>();
		done.add(fallen);
		engine.setPlayerProgress(new PlayerProgress(levels, done));
		assertFalse("257 unlocks once the quest is complete",
			find(engine.getRanking(), 257).isLocked());
		assertFalse("259 unlocks once the quest is complete",
			find(engine.getRanking(), 259).isLocked());
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
