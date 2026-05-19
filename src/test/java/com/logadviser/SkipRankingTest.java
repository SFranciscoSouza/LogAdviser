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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers skip/unskip and the skip-list view that backs the panel's
 * "View skip list" / "Skip selected" buttons — no RuneLite client involved.
 */
public class SkipRankingTest
{
	private StaticData data;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
	}

	@Test
	public void skippedActivitiesLeaveRankingAndAppearInSkipList()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);

		List<RankedActivity> before = engine.getRanking();
		assertTrue("need at least two rankable activities to test", before.size() >= 2);
		int idxA = before.get(0).getActivity().getIndex();
		int idxB = before.get(1).getActivity().getIndex();

		engine.skip(idxA);
		engine.skip(idxB);

		assertFalse("skipped A must drop out of the ranking", contains(engine.getRanking(), idxA));
		assertFalse("skipped B must drop out of the ranking", contains(engine.getRanking(), idxB));

		List<RankedActivity> skipped = engine.getSkippedRanking();
		assertEquals(2, skipped.size());
		assertTrue(contains(skipped, idxA));
		assertTrue(contains(skipped, idxB));
	}

	@Test
	public void unskipReversesASingleActivity()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);

		List<RankedActivity> before = engine.getRanking();
		int idxA = before.get(0).getActivity().getIndex();
		int idxB = before.get(1).getActivity().getIndex();
		engine.skip(idxA);
		engine.skip(idxB);

		engine.unskip(idxA);

		assertTrue("unskipped A must return to the ranking", contains(engine.getRanking(), idxA));
		assertFalse("A must be gone from the skip list", contains(engine.getSkippedRanking(), idxA));
		assertTrue("B must still be skipped", contains(engine.getSkippedRanking(), idxB));
		assertEquals(1, engine.getSkippedRanking().size());

		// Unskipping something that was never skipped is a harmless no-op.
		engine.unskip(idxA);
		assertEquals(1, engine.getSkippedRanking().size());
	}

	private static boolean contains(List<RankedActivity> ranking, int activityIndex)
	{
		for (RankedActivity r : ranking)
		{
			if (r.getActivity().getIndex() == activityIndex)
			{
				return true;
			}
		}
		return false;
	}
}
