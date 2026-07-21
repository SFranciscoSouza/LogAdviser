package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AdviserEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The full collection-log sync merges its whole harvest through
 * {@link AdviserEngine#markObtainedAll}. Doing that item-by-item fired the listener fan-out once
 * per newly obtained item, and each fan-out queues a complete panel rebuild on the EDT — the same
 * thread RuneLite dispatches mouse events on — so a first-time sync froze the client's input for
 * as long as the backlog took to drain. These tests pin the batched behaviour and prove it agrees
 * with the per-item path it replaced.
 */
public class BulkMarkObtainedTest
{
	private StaticData data;
	private List<Integer> manyItemIds;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
		manyItemIds = new ArrayList<>(data.slotsByItemId().keySet());
		// Guard the premise: the freeze only showed up because a real harvest is large.
		assertTrue("expected a large slot set to make this test meaningful",
			manyItemIds.size() > 100);
	}

	@Test
	public void markObtainedAllFiresListenersExactlyOnce()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		AtomicInteger fires = new AtomicInteger();
		engine.addListener(ranking -> fires.incrementAndGet());

		engine.markObtainedAll(manyItemIds);

		assertEquals("a whole harvest must cost exactly one listener fan-out", 1, fires.get());
	}

	@Test
	public void markObtainedAllMatchesPerItemMarking()
	{
		AdviserEngine bulk = new AdviserEngine(data, () -> false);
		AdviserEngine perItem = new AdviserEngine(data, () -> false);

		bulk.markObtainedAll(manyItemIds);
		for (int id : manyItemIds)
		{
			perItem.markObtained(id);
		}

		assertEquals("batched merge must record the same slots as the per-item path",
			perItem.collectedSlotCount(), bulk.collectedSlotCount());
		assertEquals("batched merge must store the same ids as the per-item path",
			perItem.obtainedItems(), bulk.obtainedItems());
		for (int id : manyItemIds)
		{
			// The obtained set holds canonical ids only (isObtained does no aliasing), so a
			// Body-type-B id is recorded under its Body-type-A twin — same as markObtained.
			assertTrue("item " + id + " should be obtained after the batched merge",
				bulk.isObtained(data.canonicalItemId(id)));
		}
	}

	@Test
	public void markObtainedAllIsSilentWhenNothingIsNew()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		engine.markObtainedAll(manyItemIds);

		AtomicInteger fires = new AtomicInteger();
		engine.addListener(ranking -> fires.incrementAndGet());
		// Re-syncing an already-complete log — the common case for an up-to-date account.
		engine.markObtainedAll(manyItemIds);

		assertEquals("a no-op merge must not recompute or notify", 0, fires.get());
	}
}
