package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.ActivityItem;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the data for The Fractured Archive (20 October 2026, activities 263-264).
 * <p>
 * The raid is timed at a trio running 3 completions/hour. At that scale a player's personal
 * unique rate is 1/37.5 per raid, so the five purples are the usual shared-table ("neither")
 * bucket whose reciprocals add back up to 1/37.5. Everything else rolls on the standard chest,
 * which only appears when no unique did, so the wiki rates are divided by 1 - 1/37.5 = 0.973333.
 * The four elemental fragments are 1/14 for a fragment the player does not own yet, so they chain
 * off one another like the Sinhaza shrouds rather than combining. Ascension shards also come from
 * mining ascension rocks, timed at 5 seconds per shard.
 */
public class FracturedArchiveDataTest
{
	private static final int FRACTURED_ARCHIVE = 263;
	private static final int ASCENSION_ROCKS = 264;
	private static final int ASCENSION_SHARDS = 34669;

	/** Personal unique rate for a trio: one raid in 37.5 ends in a purple for a given player. */
	private static final double TRIO_UNIQUE_RATE = 37.5;
	/** Raids per hour for the trio the raid is timed against. */
	private static final double RAIDS_PER_HOUR = 3.0;

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
	public void miningAscensionRocksIsFiveSecondsPerShard()
	{
		RankedActivity r = find(ASCENSION_ROCKS);
		assertEquals(1.0 / 720.0, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(ASCENSION_SHARDS, r.getDisplayItem().getItemId());
		assertEquals(1, r.getSlotsTotal());
	}

	@Test
	public void theTrioRaidIsRankedOnItsFastestSlot()
	{
		RankedActivity r = find(FRACTURED_ARCHIVE);
		// Ascension shards are 55/330 over three standard-chest rolls, the quickest slot by far.
		assertEquals(2.44 / RAIDS_PER_HOUR, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(ASCENSION_SHARDS, r.getDisplayItem().getItemId());
		assertEquals("the log entry has seventeen items", 17,
			engine.visibleItemsForActivity(FRACTURED_ARCHIVE).size());
	}

	@Test
	public void thePurplesShareOneRollWorthOneRaidInThirtySevenAndAHalf()
	{
		// Rondache 5/15, tome 4/15, horn 3/15, crossbows 2/15, obligator 1/15 of the unique roll.
		int[] purples = {34719, 34727, 34679, 34681, 34717};
		double sumInverse = 0.0;
		for (int itemId : purples)
		{
			ActivityItem it = item(itemId);
			assertFalse("purples share one roll, so they must stay in the combining bucket",
				it.isExact() || it.isIndependent());
			sumInverse += 1.0 / it.getDropRateAttempts();
		}
		assertEquals(TRIO_UNIQUE_RATE, 1.0 / sumInverse, 1e-6);
	}

	@Test
	public void livingRecordTiersChainLikeSinhazaShrouds()
	{
		// 100 / 500 / 1000 / 1500 / 2000 completions, stored as the gap from the previous tier.
		assertChain(new int[]{34659, 34661, 34663, 34665, 34667},
			new double[]{100.0, 400.0, 500.0, 500.0, 500.0}, 1e-9);
	}

	@Test
	public void elementalFragmentsChainBecauseDuplicatesAreAvoided()
	{
		// 1/14 for a fragment you don't own, so each one costs the same 14 raids in turn.
		double perFragment = 14.38;
		assertChain(new int[]{34675, 34677, 34673, 34671},
			new double[]{perFragment, perFragment, perFragment, perFragment}, 1e-9);
	}

	/**
	 * Asserts the given items appear in this order, are exact, and that only the first is free of
	 * a {@code requiresPrevious} dependency - the idiom that keeps one tier active at a time.
	 */
	private void assertChain(int[] itemIds, double[] attempts, double delta)
	{
		List<ActivityItem> items = engine.visibleItemsForActivity(FRACTURED_ARCHIVE);
		int first = -1;
		for (int i = 0; i < items.size(); i++)
		{
			if (items.get(i).getItemId() == itemIds[0])
			{
				first = i;
			}
		}
		assertTrue("the chain must start somewhere", first >= 0);
		for (int t = 0; t < itemIds.length; t++)
		{
			ActivityItem it = items.get(first + t);
			assertEquals(itemIds[t], it.getItemId());
			assertTrue(it.isExact());
			assertEquals("only the later links wait on the previous one", t > 0,
				it.isRequiresPrevious());
			assertEquals(attempts[t], it.getDropRateAttempts(), delta);
		}
	}

	private ActivityItem item(int itemId)
	{
		for (ActivityItem it : engine.visibleItemsForActivity(FRACTURED_ARCHIVE))
		{
			if (it.getItemId() == itemId)
			{
				return it;
			}
		}
		throw new AssertionError("item " + itemId + " not mapped to the raid");
	}

	@Test
	public void orionIsAPetAndShardsAreSharedByBothActivities()
	{
		assertTrue(data.isPet(34646));
		assertEquals(2, data.slotsByItemId().get(ASCENSION_SHARDS).getActivityCount());
	}

	@Test
	public void newSlotNamesResolveFromTheClogChatString()
	{
		assertEquals(Integer.valueOf(34717), data.itemIdsByName().get("obligator (uncharged)"));
		assertEquals(Integer.valueOf(34727), data.itemIdsByName().get("zorya's tome (uncharged)"));
		assertEquals(Integer.valueOf(34679), data.itemIdsByName().get("horn of bran"));
		assertEquals(Integer.valueOf(34722), data.itemIdsByName().get("archivist's folio (inert)"));
		assertEquals(Integer.valueOf(34667), data.itemIdsByName().get("living record (tier 5)"));
	}

	private RankedActivity find(int activityIndex)
	{
		for (RankedActivity r : engine.getRanking())
		{
			if (r.getActivity().getIndex() == activityIndex)
			{
				return r;
			}
		}
		throw new AssertionError("activity index " + activityIndex + " not in ranking");
	}
}
