package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.ActivityItem;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the "Pets Only" filter: only pet drops are considered, the time is the pet rate (not the
 * whole-table time-to-log), an activity with several pets at once shows the combined shared rate,
 * and pet-mode skips are tracked separately from the normal skip list.
 */
public class PetsOnlyTest
{
	// Single-pet activity: Killing vorkath -> Vorki (1/3000), no competing pet.
	private static final int VORKATH_INDEX = 54;
	private static final int VORKI = 21992;
	private static final double VORKATH_CPH_MAIN = 34.0;

	// Multi-pet activity: Dagannoth Kings drops prime/rex/supreme (each 1/15000) at once.
	private static final int DKS_INDEX = 14;
	private static final double DKS_CPH_MAIN = 105.0;
	private static final double DKS_PET_K = 15000.0;

	// Multi-source pet: Pet chaos elemental drops from chaos elemental (idx 9, faster) and
	// chaos fanatic (idx 10, slower); both unlocked. Dedupe must keep the chaos-elemental row.
	private static final int PET_CHAOS_ELEMENTAL = 11995;
	private static final int CHAOS_ELEMENTAL_INDEX = 9;
	private static final double CHAOS_ELEMENTAL_CPH_MAIN = 120.0;
	private static final double CHAOS_ELEMENTAL_PET_K = 300.0;

	// Locked-only multi-source pet: Tumeken's guardian only drops from the ToA reward chests, which
	// all require progress; with empty progress every source is locked. Fastest locked = ToA 500.
	private static final int TUMEKENS_GUARDIAN = 27352;
	private static final int TOA_500_INDEX = 66;

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

	@Test
	public void petsJsonHasThePetSetAndEveryIdIsAKnownSlot()
	{
		assertEquals("pets.json should hold the canonical OSRS pet set", 69, data.getPetItemIds().size());
		for (int id : data.getPetItemIds())
		{
			assertTrue("pet id " + id + " must map to a real collection-log slot",
				data.slotsByItemId().containsKey(id));
			assertTrue("isPet must agree with the set for " + id, data.isPet(id));
		}
	}

	@Test
	public void everyRankedActivityInPetsModeIsTimedAgainstAPet()
	{
		AdviserEngine engine = mainEngine();
		engine.setPetsOnly(true);

		List<RankedActivity> ranking = engine.getRanking();
		assertFalse("pets mode should rank the activities that still drop a pet", ranking.isEmpty());
		for (RankedActivity r : ranking)
		{
			ActivityItem display = r.getDisplayItem();
			assertNotNull("a ranked pet activity must have a display item", display);
			assertTrue("the display item must be a pet (" + display.getItemName() + ")",
				data.isPet(display.getItemId()));
		}
	}

	@Test
	public void singlePetActivityTimeIsThePetRateAlone()
	{
		AdviserEngine engine = mainEngine();
		engine.setPetsOnly(true);

		RankedActivity vorkath = find(engine.getRanking(), VORKATH_INDEX);
		assertNotNull("Vorkath should appear while Vorki is uncollected", vorkath);
		assertEquals("display item must be the pet", VORKI, vorkath.getDisplayItem().getItemId());

		double expected = 3000.0 / VORKATH_CPH_MAIN; // Vorki 1/3000, extraTimeFirst 0
		assertEquals("time-to-pet must be the pet's own rate, not the whole-table time-to-log",
			expected, vorkath.getTimeToNextSlotHours(), expected * 1e-6);
	}

	@Test
	public void dagannothKingsShowsOneRowAtTheCombinedSharedRate()
	{
		AdviserEngine engine = mainEngine();
		engine.setPetsOnly(true);

		List<RankedActivity> ranking = engine.getRanking();
		int dksRows = 0;
		for (RankedActivity r : ranking)
		{
			if (r.getActivity().getIndex() == DKS_INDEX)
			{
				dksRows++;
			}
		}
		assertEquals("Dagannoth Kings must appear exactly once", 1, dksRows);

		RankedActivity dks = find(ranking, DKS_INDEX);
		assertNotNull(dks);
		// Three 1/15000 pets in the harmonic "Neither" bucket combine to 1/5000 per kill.
		double shared = (DKS_PET_K / 3.0) / DKS_CPH_MAIN;
		double single = DKS_PET_K / DKS_CPH_MAIN;
		assertEquals("DKS must use the combined shared pet rate (all three at once)",
			shared, dks.getTimeToNextSlotHours(), shared * 1e-6);
		assertTrue("shared rate must be faster than chasing a single dagannoth pet",
			dks.getTimeToNextSlotHours() < single);
	}

	@Test
	public void activityLeavesPetsRankingOnceItsOnlyPetIsObtained()
	{
		AdviserEngine engine = mainEngine();
		engine.setPetsOnly(true);
		assertNotNull("Vorkath present before obtaining Vorki", find(engine.getRanking(), VORKATH_INDEX));

		engine.markObtained(VORKI);
		assertNull("Vorkath must drop out once its only pet is collected", find(engine.getRanking(), VORKATH_INDEX));
	}

	@Test
	public void togglingPetsOffRestoresTheNormalRanking()
	{
		AdviserEngine engine = mainEngine();
		int normalSize = engine.getRanking().size();

		engine.setPetsOnly(true);
		int petSize = engine.getRanking().size();
		assertTrue("pets mode should be a strict subset of the full ranking", petSize < normalSize);

		engine.setPetsOnly(false);
		assertEquals("turning pets mode off restores the normal ranking", normalSize, engine.getRanking().size());
	}

	@Test
	public void petSkipsAreSeparateFromNormalSkips()
	{
		AdviserEngine engine = mainEngine();

		// Skip DKS in the normal mode.
		engine.skip(DKS_INDEX);
		assertTrue(engine.skippedActivities().contains(DKS_INDEX));
		assertTrue("normal skip must not leak into the pet skip list", engine.petsSkippedActivities().isEmpty());

		// Switch to pets mode and skip Vorkath there.
		engine.setPetsOnly(true);
		engine.skip(VORKATH_INDEX);
		assertTrue("pet skip recorded in the pet list", engine.petsSkippedActivities().contains(VORKATH_INDEX));
		assertFalse("pet skip must not leak into the normal list", engine.skippedActivities().contains(VORKATH_INDEX));
		assertEquals("normal skip list untouched while in pets mode", 1, engine.skippedActivities().size());

		// In pets mode the normal-skipped DKS is still shown (it has pets); the pet-skipped Vorkath is hidden.
		assertNotNull("DKS is only normal-skipped, so it still appears in pets mode", find(engine.getRanking(), DKS_INDEX));
		assertNull("Vorkath is pet-skipped, so it is hidden in pets mode", find(engine.getRanking(), VORKATH_INDEX));
	}

	@Test
	public void everyPetAppearsAtMostOnce()
	{
		AdviserEngine engine = mainEngine();
		engine.setPetsOnly(true);

		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (RankedActivity r : engine.getRanking())
		{
			int petId = r.getDisplayItem().getItemId();
			assertTrue("pet " + r.getDisplayItem().getItemName() + " is listed more than once", seen.add(petId));
		}
	}

	@Test
	public void multiSourcePetCollapsesToFastestUnlockedSource()
	{
		AdviserEngine engine = mainEngine();
		engine.setPetsOnly(true);

		List<RankedActivity> ranking = engine.getRanking();
		int rows = 0;
		for (RankedActivity r : ranking)
		{
			if (r.getDisplayItem().getItemId() == PET_CHAOS_ELEMENTAL)
			{
				rows++;
			}
		}
		assertEquals("Pet chaos elemental must collapse to a single row", 1, rows);

		RankedActivity row = find(ranking, CHAOS_ELEMENTAL_INDEX);
		assertNotNull("the kept source must be the faster chaos-elemental kill", row);
		assertEquals(PET_CHAOS_ELEMENTAL, row.getDisplayItem().getItemId());
		double expected = CHAOS_ELEMENTAL_PET_K / CHAOS_ELEMENTAL_CPH_MAIN;
		assertEquals(expected, row.getTimeToNextSlotHours(), expected * 1e-6);
		assertNull("the slower chaos-fanatic source must be deduped away", find(ranking, 10));
	}

	@Test
	public void lockedOnlyPetIsShownOnceAsLocked()
	{
		AdviserEngine engine = mainEngine(); // empty player progress -> ToA is locked
		engine.setPetsOnly(true);

		List<RankedActivity> ranking = engine.getRanking();
		int rows = 0;
		for (RankedActivity r : ranking)
		{
			if (r.getDisplayItem().getItemId() == TUMEKENS_GUARDIAN)
			{
				rows++;
			}
		}
		assertEquals("Tumeken's guardian must appear exactly once", 1, rows);

		RankedActivity row = find(ranking, TOA_500_INDEX);
		assertNotNull("kept source must be the fastest (ToA 500) reward chest", row);
		assertEquals(TUMEKENS_GUARDIAN, row.getDisplayItem().getItemId());
		assertTrue("a pet with no unlocked source must be shown as locked", row.isLocked());
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
