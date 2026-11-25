package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the data for Prison of Raeun (activity 265, gated on The Graveyard).
 * <p>
 * A run takes 1:50, so 3600 / 110 = 32.73 runs per hour. Sunken treasure and Ghostly sail fabric
 * are guaranteed, so they sit in the independent bucket (one run each) rather than the combining
 * bucket, where two 1/1 items would claim two slots in half a run. The Fremennik graceful ornament
 * kit (~1/25) and sail fabric (~1/50) use placeholder rates until official ones are published.
 */
public class PrisonOfRaeunDataTest
{
	private static final int PRISON_OF_RAEUN = 265;
	private static final int SUNKEN_TREASURE = 35033;
	private static final int GHOSTLY_SAIL_FABRIC = 35031;
	private static final int FREMENNIK_GRACEFUL_KIT = 35027;
	private static final int FREMENNIK_SAIL_FABRIC = 35029;
	private static final double RUNS_PER_HOUR = 32.73;

	private StaticData data;
	private AdviserEngine engine;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
		engine = new AdviserEngine(data, () -> false);
		engine.setIgnoreRequirements(true);
	}

	@Test
	public void aGuaranteedSlotTakesOneRun()
	{
		RankedActivity r = find();
		assertEquals(1.0 / RUNS_PER_HOUR, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals("the activity covers four slots", 4, r.getSlotsTotal());
	}

	@Test
	public void secondGuaranteedSlotStillTakesOneRun()
	{
		engine.markObtained(SUNKEN_TREASURE);
		RankedActivity r = find();
		assertEquals(1.0 / RUNS_PER_HOUR, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(GHOSTLY_SAIL_FABRIC, r.getDisplayItem().getItemId());
	}

	@Test
	public void kitAndSailFabricCombineToSixteenAndTwoThirdsRuns()
	{
		engine.markObtained(SUNKEN_TREASURE);
		engine.markObtained(GHOSTLY_SAIL_FABRIC);
		// Placeholder rates: kit ~1/25, Fremennik sail fabric ~1/50. They share the combining
		// bucket, so the next of the two is 1 / (1/25 + 1/50) = 16.67 runs away.
		RankedActivity r = find();
		assertEquals((50.0 / 3.0) / RUNS_PER_HOUR, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(FREMENNIK_GRACEFUL_KIT, r.getDisplayItem().getItemId());
	}

	@Test
	public void lastSlotIsTheSailFabricAtOneInFifty()
	{
		engine.markObtained(SUNKEN_TREASURE);
		engine.markObtained(GHOSTLY_SAIL_FABRIC);
		engine.markObtained(FREMENNIK_GRACEFUL_KIT);
		assertEquals(50.0 / RUNS_PER_HOUR, find().getTimeToNextSlotHours(), 1e-9);
	}

	@Test
	public void newSlotNamesResolveFromTheClogChatString()
	{
		for (int id : new HashSet<>(Arrays.asList(SUNKEN_TREASURE, GHOSTLY_SAIL_FABRIC,
			FREMENNIK_GRACEFUL_KIT, FREMENNIK_SAIL_FABRIC)))
		{
			String name = data.slotsByItemId().get(id).getSlotName().toLowerCase();
			assertEquals(Integer.valueOf(id), data.itemIdsByName().get(name));
		}
	}

	private RankedActivity find()
	{
		for (RankedActivity r : engine.getRanking())
		{
			if (r.getActivity().getIndex() == PRISON_OF_RAEUN)
			{
				return r;
			}
		}
		throw new AssertionError("Prison of Raeun not in ranking");
	}
}
