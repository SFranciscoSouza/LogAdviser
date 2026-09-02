package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pins the Summer Sweep-Up 2026 elemental jewels and necklace of fangs. Three of the five drops
 * arrive with brand-new activities (260-262); the other two land on tables that already existed,
 * where they become the fastest row and so change an estimate the ranking was already using.
 * Both effects are hand-entered data whose only other check is a row count, so the arithmetic is
 * asserted explicitly here.
 * <p>
 * All five sit in the "neither" bucket — separate rolls on one monster's drop table — so on a
 * shared table their rates combine harmonically rather than the fastest one winning outright.
 */
public class SummerSweepUpDataTest
{
	private static final int TUROTH = 176;
	private static final int INFERNAL_MAGES = 198;
	private static final int MOLANISKS = 260;
	private static final int SEA_SNAKE_HATCHLINGS = 261;
	private static final int KILLERWATTS = 262;

	private static final int NECKLACE_OF_FANGS = 34401;
	private static final int AIR_DIAMOND = 34404;
	private static final int WATER_SAPPHIRE = 34410;
	private static final int EARTH_EMERALD = 34416;
	private static final int FIRE_RUBY = 34422;

	private StaticData data;
	private AdviserEngine engine;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
		engine = new AdviserEngine(data, () -> false);
		// Gating is covered by ActivityRequirementsTest; here we only care about the math.
		engine.setIgnoreRequirements(true);
	}

	@Test
	public void eachNewActivityIsItsJewelAloneOverTheKillRate()
	{
		// One slot apiece, all at 1/64, so the estimate is just 64 kills at the activity's rate.
		assertSoleJewel(MOLANISKS, EARTH_EMERALD, 300.0);
		assertSoleJewel(SEA_SNAKE_HATCHLINGS, WATER_SAPPHIRE, 560.0);
		assertSoleJewel(KILLERWATTS, AIR_DIAMOND, 400.0);
	}

	@Test
	public void fireRubyBecomesTheInfernalMageEstimate()
	{
		// Was mystic hat + boots (dark), both 1/512, combining to 256 kills at 450/hr. Fire ruby
		// at 1/64 dominates that: the combined attempts drop to 51.2 and it drives the display.
		double combined = 1.0 / (1.0 / 512.0 + 1.0 / 512.0 + 1.0 / 64.0);
		assertEquals(51.2, combined, 1e-9);

		RankedActivity r = find(INFERNAL_MAGES);
		assertEquals(combined / 450.0, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(FIRE_RUBY, r.getDisplayItem().getItemId());
		assertEquals("the log entry has three items", 3, r.getSlotsTotal());
	}

	@Test
	public void necklaceOfFangsBecomesTheTurothEstimate()
	{
		// Leaf-bladed sword 1/500 and mystic robe bottom (light) 1/512 carried the estimate; the
		// shield left half and dragon spear rows are rare enough to barely move it. At 1/128 the
		// necklace is four times faster than anything already on the table.
		double combined = 1.0 / (1.0 / 500.0 + 1.0 / 512.0 + 1.0 / 104858.0 + 1.0 / 139810.0
			+ 1.0 / 128.0);

		RankedActivity r = find(TUROTH);
		assertEquals(combined / 300.0, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(NECKLACE_OF_FANGS, r.getDisplayItem().getItemId());
		assertEquals("the log entry has five items", 5, r.getSlotsTotal());
	}

	@Test
	public void obtainingTheJewelRestoresTheOldEstimate()
	{
		// The pre-update estimates must come back once the new row is collected — proves the new
		// rows joined the existing bucket rather than replacing what was there.
		engine.markObtained(FIRE_RUBY);
		assertEquals(256.0 / 450.0, find(INFERNAL_MAGES).getTimeToNextSlotHours(), 1e-9);

		engine.markObtained(NECKLACE_OF_FANGS);
		double turothWas = 1.0 / (1.0 / 500.0 + 1.0 / 512.0 + 1.0 / 104858.0 + 1.0 / 139810.0);
		assertEquals(turothWas / 300.0, find(TUROTH).getTimeToNextSlotHours(), 1e-9);
		assertEquals("four slots left once the necklace is in", 4, find(TUROTH).getSlotsLeft());
	}

	@Test
	public void newSlotNamesResolveFromTheClogChatString()
	{
		// The "New item added to your collection log: X" handler matches on lower-cased slot
		// name, so a typo here silently drops live drops.
		assertEquals(Integer.valueOf(NECKLACE_OF_FANGS), data.itemIdsByName().get("necklace of fangs"));
		assertEquals(Integer.valueOf(AIR_DIAMOND), data.itemIdsByName().get("air diamond"));
		assertEquals(Integer.valueOf(WATER_SAPPHIRE), data.itemIdsByName().get("water sapphire"));
		assertEquals(Integer.valueOf(EARTH_EMERALD), data.itemIdsByName().get("earth emerald"));
		assertEquals(Integer.valueOf(FIRE_RUBY), data.itemIdsByName().get("fire ruby"));
	}

	private void assertSoleJewel(int activityIndex, int itemId, double killsPerHour)
	{
		RankedActivity r = find(activityIndex);
		assertEquals(64.0 / killsPerHour, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(itemId, r.getDisplayItem().getItemId());
		assertEquals(1, r.getSlotsTotal());
	}

	private RankedActivity find(int activityIndex)
	{
		List<RankedActivity> ranking = engine.getRanking();
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
