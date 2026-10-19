package com.logadviser.sync;

import com.google.gson.Gson;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AdviserEngine;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Several collection log slots share a name (Mysterious page, Decorative armour, ...), so the
 * "New item added" chat message alone can't say which one unlocked. It must never be credited
 * to the wrong slot, and a full Sync must be able to undo a wrong credit.
 */
public class DuplicateNameResolutionTest
{
	private static final List<Integer> MYSTERIOUS_PAGES = Arrays.asList(24763, 24765, 24767, 24769, 24771);

	private StaticData data;

	@Before
	public void setUp() throws Exception
	{
		data = StaticDataLoader.loadAll(new Gson());
	}

	@Test
	public void allIdsForSharedNamesAreKept()
	{
		assertEquals(new HashSet<>(MYSTERIOUS_PAGES),
			new HashSet<>(data.itemIdsByNameAll().get("mysterious page")));
		assertEquals(15, data.itemIdsByNameAll().get("decorative armour").size());
		assertEquals(Collections.singletonList(13642), data.itemIdsByNameAll().get("farmer's jacket"));
	}

	@Test
	public void sharedNameResolvesToTheOnlyMissingSlot()
	{
		Set<Integer> obtained = new HashSet<>(MYSTERIOUS_PAGES);
		obtained.remove(24767);
		assertEquals(Integer.valueOf(24767),
			CollectionLogTracker.resolveChatItem(MYSTERIOUS_PAGES, obtained::contains));
	}

	@Test
	public void sharedNameWithSeveralMissingSlotsIsNotGuessed()
	{
		Set<Integer> obtained = new HashSet<>(Arrays.asList(24763, 24765));
		assertNull(CollectionLogTracker.resolveChatItem(MYSTERIOUS_PAGES, obtained::contains));
		assertNull(CollectionLogTracker.resolveChatItem(MYSTERIOUS_PAGES, id -> false));
	}

	@Test
	public void sharedNameWithNothingMissingIsNotGuessed()
	{
		assertNull(CollectionLogTracker.resolveChatItem(MYSTERIOUS_PAGES, id -> true));
	}

	@Test
	public void uniqueNameResolvesDirectly()
	{
		List<Integer> spear = data.itemIdsByNameAll().get("dragon spear");
		assertEquals(Integer.valueOf(1249), CollectionLogTracker.resolveChatItem(spear, id -> true));
		assertNull(CollectionLogTracker.resolveChatItem(null, id -> false));
	}

	@Test
	public void replaceObtainedDropsAWronglyCreditedSlot()
	{
		AdviserEngine engine = new AdviserEngine(data, () -> false);
		engine.markObtained(24763);
		engine.markObtained(24771); // wrong credit: the player actually got 24765
		Set<Integer> realLog = new HashSet<>(Arrays.asList(24763, 24765));
		engine.replaceObtained(realLog);
		assertFalse(engine.isObtained(24771));
		assertTrue(engine.isObtained(24765));
		assertEquals(2, engine.collectedSlotCount());
	}
}
