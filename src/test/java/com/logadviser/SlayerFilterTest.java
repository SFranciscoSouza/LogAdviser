package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import com.logadviser.engine.ShowFilter;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the multi-select "Show" filter and the new "Slayer" option: SLAYER returns exactly the
 * activities with a Slayer-level requirement minus the boat-combat bounty tasks, ticked options are
 * unioned, and an empty selection means "All".
 */
public class SlayerFilterTest
{
	// Boat-combat *bounty*-task entries: they carry a Slayer requirement but are not slayer tasks,
	// so the Slayer filter must exclude them.
	private static final int GREAT_WHITE_SHARK_INDEX = 144;
	private static final int LAVA_STRYKEWYRM_INDEX = 254;

	private StaticData data;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
	}

	private AdviserEngine mainEngine()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		engine.setAccountMode(AccountMode.MAIN);
		return engine;
	}

	private static Set<Integer> indices(AdviserEngine engine, EnumSet<ShowFilter> filter)
	{
		engine.setShowFilter(filter);
		Set<Integer> out = new HashSet<>();
		for (RankedActivity r : engine.getRanking())
		{
			out.add(r.getActivity().getIndex());
		}
		return out;
	}

	private boolean isSlayer(int activityIndex, String name)
	{
		return data.requirementsFor(activityIndex).getSkillLevels().containsKey(Skill.SLAYER)
			&& !name.toLowerCase().contains("bounty task");
	}

	@Test
	public void slayerFilterReturnsExactlyTheSlayerActivities()
	{
		AdviserEngine engine = mainEngine();

		// Derive the expected slayer set from the unfiltered ranking using the same data rule.
		Set<Integer> expected = new HashSet<>();
		engine.setShowFilter(EnumSet.noneOf(ShowFilter.class));
		for (RankedActivity r : engine.getRanking())
		{
			if (isSlayer(r.getActivity().getIndex(), r.getActivity().getName()))
			{
				expected.add(r.getActivity().getIndex());
			}
		}
		assertFalse("there should be slayer activities in the table", expected.isEmpty());

		Set<Integer> actual = indices(engine, EnumSet.of(ShowFilter.SLAYER));
		assertEquals("Slayer filter must return exactly the slayer activities", expected, actual);
	}

	@Test
	public void slayerFilterExcludesBoatCombatBountyTasks()
	{
		AdviserEngine engine = mainEngine();
		engine.setShowFilter(EnumSet.of(ShowFilter.SLAYER));

		assertNull("great white shark (boat bounty) must not show under Slayer",
			find(engine.getRanking(), GREAT_WHITE_SHARK_INDEX));
		assertNull("lava strykewyrm (boat bounty) must not show under Slayer",
			find(engine.getRanking(), LAVA_STRYKEWYRM_INDEX));
	}

	@Test
	public void tickedOptionsAreUnioned()
	{
		AdviserEngine engine = mainEngine();

		Set<Integer> miscOnly = indices(engine, EnumSet.of(ShowFilter.MISCELLANEOUS));
		Set<Integer> slayerOnly = indices(engine, EnumSet.of(ShowFilter.SLAYER));
		Set<Integer> union = indices(engine, EnumSet.of(ShowFilter.MISCELLANEOUS, ShowFilter.SLAYER));

		assertFalse("misc set should be non-empty", miscOnly.isEmpty());
		assertFalse("slayer set should be non-empty", slayerOnly.isEmpty());

		Set<Integer> expectedUnion = new HashSet<>(miscOnly);
		expectedUnion.addAll(slayerOnly);
		assertEquals("Misc + Slayer must be the union of the two", expectedUnion, union);
	}

	@Test
	public void emptySelectionMeansAll()
	{
		AdviserEngine engine = mainEngine();

		// Every activity has exactly one category, so the three categories together cover the
		// whole table — equal to the empty ("All") selection.
		int allSize = indices(engine, EnumSet.noneOf(ShowFilter.class)).size();
		int allCategoriesSize = indices(engine,
			EnumSet.of(ShowFilter.COMBAT, ShowFilter.MINIGAME, ShowFilter.MISCELLANEOUS)).size();

		assertTrue("All should rank something", allSize > 0);
		assertEquals("empty selection must equal selecting every category", allSize, allCategoriesSize);
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
		return null;
	}
}
