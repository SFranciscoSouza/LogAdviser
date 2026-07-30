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
 * Pins the Wyrmscraig data added for the 29 July 2026 update (activities 257-259). These are
 * hand-entered rows whose only other check is a row count, so the arithmetic that the ranking
 * depends on — attempts / completions-per-hour, and which bucket each drop lands in — is
 * asserted here explicitly.
 */
public class WyrmscraigDataTest
{
	private static final int GOLEM_CRAFTING = 257;
	private static final int GOAT_HUNTING = 258;
	private static final int MAD_ANGEL = 259;

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
	public void golemCraftingIsChiselOverFiftyEightPerHour()
	{
		// Jeweller's chisel at 1/315, 58 golems/hour.
		RankedActivity r = find(GOLEM_CRAFTING);
		assertEquals(315.0 / 58.0, r.getTimeToNextSlotHours(), 1e-6);
		assertEquals(34024, r.getDisplayItem().getItemId());
		assertEquals(1, r.getSlotsTotal());
	}

	@Test
	public void goatHuntingIsMcgrootOverNineHundredPerHour()
	{
		// Mr mcgroot has no published rate; 36000 attempts is a placeholder standing in for
		// roughly 40 hours at 900 catches/hour. Revisit when Jagex or the wiki publishes one.
		RankedActivity r = find(GOAT_HUNTING);
		assertEquals(40.0, r.getTimeToNextSlotHours(), 1e-6);
		assertEquals(34040, r.getDisplayItem().getItemId());
		assertEquals(1, r.getSlotsTotal());
	}

	@Test
	public void madAngelHeadlinesGraniteDustAsAGuaranteedDrop()
	{
		// Granite dust is a 100% drop, so one kill (1/60 h) is the next slot and it wins the
		// exact bucket outright. The other four rows are slower and must not displace it.
		RankedActivity r = find(MAD_ANGEL);
		assertEquals(1.0 / 60.0, r.getTimeToNextSlotHours(), 1e-9);
		assertEquals(21726, r.getDisplayItem().getItemId());
		assertEquals("the log entry has five items", 5, r.getSlotsTotal());
	}

	@Test
	public void madAngelFallsBackToTheCombinedPreRollOnceGraniteDustIsObtained()
	{
		// With Granite dust gone the "neither" bucket decides: Ardeaglais teleport (7/156),
		// Hallowfell (7/896) and Jar of light (1/896) combine harmonically.
		engine.markObtained(21726);
		double combined = 1.0 / (1.0 / 22.2857 + 1.0 / 128.0 + 1.0 / 896.0);
		RankedActivity r = find(MAD_ANGEL);
		assertEquals(combined / 60.0, r.getTimeToNextSlotHours(), 1e-6);
		assertEquals("four slots left once Granite dust is in", 4, r.getSlotsLeft());
	}

	@Test
	public void bothNewPetsAreKnownSlotsAndFlaggedAsPets()
	{
		for (int petId : new int[]{34040, 34042})
		{
			assertTrue("pet " + petId + " must be flagged a pet", data.isPet(petId));
			assertNotNull("pet " + petId + " must be a known slot",
				data.slotsByItemId().get(petId));
		}
	}

	@Test
	public void newSlotNamesResolveFromTheClogChatString()
	{
		// The "New item added to your collection log: X" handler matches on lower-cased slot
		// name, so a typo here silently drops live drops.
		assertEquals(Integer.valueOf(34024), data.itemIdsByName().get("jeweller's chisel"));
		assertEquals(Integer.valueOf(34027), data.itemIdsByName().get("hallowfell"));
		assertEquals(Integer.valueOf(34030), data.itemIdsByName().get("jar of light"));
		assertEquals(Integer.valueOf(34033), data.itemIdsByName().get("ardeaglais teleport"));
		assertEquals(Integer.valueOf(34040), data.itemIdsByName().get("mr mcgroot"));
		assertEquals(Integer.valueOf(34042), data.itemIdsByName().get("aggy"));
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
