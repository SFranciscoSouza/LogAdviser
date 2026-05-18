package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.LogSlot;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AdviserEngine;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Body-type-B Farmer's outfit items must canonicalise onto the Body-type-A ids that
 * live in slots.json, so a Body-type-B player's collection log syncs correctly.
 */
public class ItemAliasesTest
{
	// Body-type-B id -> canonical Body-type-A id (the ones present in slots.json).
	private static final int[][] FARMER_PAIRS = {
		{13641, 13640}, // boro trousers
		{13643, 13642}, // shirt/jacket
		{13645, 13644}, // boots
		{13647, 13646}, // strawhat
	};

	private StaticData data;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
	}

	@Test
	public void slotsByItemIdResolvesBothBodyTypes()
	{
		Map<Integer, LogSlot> byId = data.slotsByItemId();
		for (int[] pair : FARMER_PAIRS)
		{
			int b = pair[0], a = pair[1];
			LogSlot slotA = byId.get(a);
			assertNotNull("canonical id " + a + " missing from slots.json", slotA);
			assertSame("Body-type-B id " + b + " must map to the same slot as " + a,
				slotA, byId.get(b));
		}
	}

	@Test
	public void canonicalItemIdMapsBToA()
	{
		for (int[] pair : FARMER_PAIRS)
		{
			assertEquals(pair[1], data.canonicalItemId(pair[0]));
			// Canonical ids and unrelated ids are returned unchanged.
			assertEquals(pair[1], data.canonicalItemId(pair[1]));
		}
	}

	@Test
	public void shirtJacketChatNamesResolve()
	{
		Map<String, Integer> byName = data.itemIdsByName();
		assertEquals(Integer.valueOf(13642), byName.get("farmer's jacket"));
		assertEquals(Integer.valueOf(13642), byName.get("farmer's shirt"));
	}

	@Test
	public void markObtainedCanonicalisesWithoutDoubleCount()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		int before = engine.collectedSlotCount();
		for (int[] pair : FARMER_PAIRS)
		{
			int b = pair[0], a = pair[1];
			engine.markObtained(b);             // Body-type-B item from the widget scrape
			assertTrue("canonical id " + a + " should be obtained after marking " + b,
				engine.isObtained(a));
			engine.markObtained(a);             // same slot via the other body type / chat
		}
		assertEquals("each Farmer's pair must count exactly once",
			before + FARMER_PAIRS.length, engine.collectedSlotCount());
	}
}
