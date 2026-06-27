package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.Activity;
import com.logadviser.data.ActivityItem;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.MembershipMode;
import com.logadviser.engine.RankedActivity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the "Membership" filter: P2P (the default) shows everything, F2P keeps only the
 * free-to-play obtainable slots (f2p_slots.json), so the F2P ranking is a strict subset of the
 * P2P ranking and every slot a ranked F2P activity exposes is itself F2P.
 */
public class MembershipFilterTest
{
	// f2p_slots.json size — bump this when the wiki-sourced F2P list is regenerated
	// (tools/generate_f2p_slots.py), the same way the activity/slot row counts are bumped.
	private static final int F2P_SLOT_COUNT = 101;

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

	private static Set<Integer> indices(AdviserEngine engine, MembershipMode mode)
	{
		engine.setMembershipMode(mode);
		Set<Integer> out = new HashSet<>();
		for (RankedActivity r : engine.getRanking())
		{
			out.add(r.getActivity().getIndex());
		}
		return out;
	}

	@Test
	public void f2pSlotSetIsPopulated()
	{
		assertEquals("f2p_slots.json row count changed — regenerate and bump F2P_SLOT_COUNT",
			F2P_SLOT_COUNT, data.getF2pItemIds().size());
	}

	@Test
	public void defaultModeIsP2pAndShowsEverything()
	{
		AdviserEngine engine = mainEngine();
		assertEquals("default membership mode must be P2P (show all)",
			MembershipMode.P2P, engine.getMembershipMode());
		assertFalse("P2P ranking must not be empty", engine.getRanking().isEmpty());
	}

	@Test
	public void f2pIsAStrictSubsetOfP2p()
	{
		AdviserEngine engine = mainEngine();
		Set<Integer> p2p = indices(engine, MembershipMode.P2P);
		Set<Integer> f2p = indices(engine, MembershipMode.F2P);

		assertFalse("P2P ranking must not be empty", p2p.isEmpty());
		assertFalse("F2P ranking must not be empty", f2p.isEmpty());
		assertTrue("every F2P activity must also be in the P2P ranking", p2p.containsAll(f2p));
		assertTrue("F2P must hide some members-only activities", f2p.size() < p2p.size());
	}

	@Test
	public void f2pRankedActivitiesExposeOnlyF2pSlots()
	{
		AdviserEngine engine = mainEngine();
		engine.setMembershipMode(MembershipMode.F2P);
		for (RankedActivity r : engine.getRanking())
		{
			int idx = r.getActivity().getIndex();
			List<ActivityItem> visible = engine.visibleItemsForActivity(idx);
			assertFalse("a ranked F2P activity must keep at least one slot", visible.isEmpty());
			for (ActivityItem it : visible)
			{
				assertTrue("activity " + idx + " exposes non-F2P slot " + it.getItemId() + " in F2P mode",
					data.isF2p(it.getItemId()));
			}
		}
	}

	@Test
	public void knownFreeToPlayBossShowsInF2p()
	{
		// Obor is a free-to-play boss (drops the Hill giant club), so its activity must survive F2P.
		int oborIndex = -1;
		for (Activity a : data.getActivities())
		{
			if (a.getName().toLowerCase().contains("obor"))
			{
				oborIndex = a.getIndex();
				break;
			}
		}
		assertTrue("data should contain the Obor activity", oborIndex >= 0);

		AdviserEngine engine = mainEngine();
		assertTrue("Obor (F2P boss) must appear in the F2P ranking",
			indices(engine, MembershipMode.F2P).contains(oborIndex));
	}
}
