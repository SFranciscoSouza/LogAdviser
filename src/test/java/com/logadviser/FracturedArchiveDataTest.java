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
 * Pins the data added for The Fractured Archive (20 October 2026, activities 263-264).
 * <p>
 * The raid's rates were not known at release, so its completions/hour and every drop rate are
 * left at 0: the engine treats that as "no time" and keeps the raid out of the ranking, while
 * its 17 slots still count toward the log totals. Fill in the numbers here and in the JSON once
 * the wiki publishes them. Ascension shards also come from mining ascension rocks, timed at
 * 5 seconds per shard.
 */
public class FracturedArchiveDataTest
{
	private static final int FRACTURED_ARCHIVE = 263;
	private static final int ASCENSION_ROCKS = 264;
	private static final int ASCENSION_SHARDS = 34669;

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
	public void raidWithOpenRatesStaysOutOfTheRanking()
	{
		for (RankedActivity r : engine.getRanking())
		{
			assertFalse("raid has no rates yet", r.getActivity().getIndex() == FRACTURED_ARCHIVE);
		}
		assertEquals("the log entry has seventeen items", 17,
			engine.visibleItemsForActivity(FRACTURED_ARCHIVE).size());
	}

	@Test
	public void livingRecordTiersChainLikeSinhazaShrouds()
	{
		List<ActivityItem> items = engine.visibleItemsForActivity(FRACTURED_ARCHIVE);
		int[] tiers = {34659, 34661, 34663, 34665, 34667};
		int first = -1;
		for (int i = 0; i < items.size(); i++)
		{
			if (items.get(i).getItemId() == tiers[0])
			{
				first = i;
			}
		}
		assertTrue("tier 1 must be present", first >= 0);
		for (int t = 0; t < tiers.length; t++)
		{
			ActivityItem it = items.get(first + t);
			assertEquals(tiers[t], it.getItemId());
			assertTrue(it.isExact());
			assertEquals("only tiers 2-5 wait on the previous tier", t > 0, it.isRequiresPrevious());
		}
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
