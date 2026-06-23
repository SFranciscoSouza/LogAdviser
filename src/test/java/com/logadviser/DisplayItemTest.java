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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards "icon follows the time": the headline display item must be the slot the time estimate
 * is for (the one driving {@code best}), not the lowest-difficulty slot. Regression for the bug
 * where Araxxor showed the non-guaranteed Noxious point icon next to the ~89s estimate that
 * actually belongs to the near-guaranteed Coagulated venom drop.
 */
public class DisplayItemTest
{
	// Araxxor ("Killing araxxor (on task)") and two of its drops.
	private static final int ARAXXOR_INDEX = 4;
	private static final int COAGULATED_VENOM = 29781; // dropRateAttempts 1.0 — drives the estimate
	private static final int NOXIOUS_POINT = 29790;    // dropRateAttempts 200.0 — the old (wrong) icon

	private StaticData data;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
	}

	@Test
	public void displayItemIsTheSlotDrivingTheEstimateNotTheEasiestSlot()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);

		RankedActivity araxxor = find(engine.getRanking(), ARAXXOR_INDEX);
		assertNotNull("Araxxor (index " + ARAXXOR_INDEX + ") must be rankable", araxxor);

		assertNotNull("display item must be set for a rankable activity", araxxor.getDisplayItem());
		assertEquals(
			"display item must be the slot the time estimate is for (Coagulated venom), not the "
				+ "lowest-difficulty slot (Noxious point)",
			COAGULATED_VENOM,
			araxxor.getDisplayItem().getItemId());
		assertTrue(
			"the near-guaranteed driver makes the estimate fast — sanity-check it is not the "
				+ "non-guaranteed Noxious point's multi-hour time",
			araxxor.getTimeToNextSlotHours() < 0.05);
		assertTrue("Noxious point must not be the headline slot",
			araxxor.getDisplayItem().getItemId() != NOXIOUS_POINT);
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
