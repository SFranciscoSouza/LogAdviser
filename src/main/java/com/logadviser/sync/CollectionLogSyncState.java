package com.logadviser.sync;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks which collection log pages the user has opened/scraped in-game, keyed by the
 * game's own (tab index, category index) — exactly the {@code COLLECTION_LAST_TAB} /
 * {@code COLLECTION_LAST_CATEGORY} varbits the client sets, which are also the position
 * of the entry in the tab's left list. Page-name / colour matching proved unreliable
 * (entries are recoloured by completion state), so everything is index-based: a page is
 * "synced" once its items were scraped (script 2731); a tab is fully synced once every
 * category under it is synced; the per-tab category total is the visible list size.
 *
 * <p>Persisted per character via RSProfile config. Writes happen on the client thread;
 * cross-thread readers (Swing panel, infobox) read a {@code volatile} {@link Snapshot}.
 */
@Slf4j
@Singleton
public class CollectionLogSyncState
{
	private static final String CONFIG_GROUP = "logadviser";
	private static final String KEY_SYNCED = "syncedCats";
	private static final String KEY_TOTALS = "tabTotals";
	private static final int TAB_STRIDE = 100_000;
	private static final int TAB_COUNT = 5;

	private final ConfigManager configManager;
	private final ClientThread clientThread;

	// Mutated on the client thread only. A synced page is encoded tab*STRIDE+category.
	private final Set<Integer> synced = new HashSet<>();
	private final Map<Integer, Integer> tabTotals = new HashMap<>();

	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

	private volatile Snapshot snapshot = Snapshot.empty();

	@Inject
	public CollectionLogSyncState(ConfigManager configManager, ClientThread clientThread)
	{
		this.configManager = configManager;
		this.clientThread = clientThread;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Defer one tick so RSProfile has the display name attached, mirroring
			// CollectionLogTracker.
			clientThread.invokeLater(() ->
			{
				load();
				return true;
			});
		}
	}

	public void load()
	{
		synced.clear();
		tabTotals.clear();
		for (int key : parseInts(configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_SYNCED)))
		{
			synced.add(key);
		}
		String totals = configManager.getRSProfileConfiguration(CONFIG_GROUP, KEY_TOTALS);
		if (totals != null && !totals.isEmpty())
		{
			for (String tok : totals.split(","))
			{
				int colon = tok.indexOf(':');
				if (colon <= 0)
				{
					continue;
				}
				try
				{
					tabTotals.put(
						Integer.parseInt(tok.substring(0, colon).trim()),
						Integer.parseInt(tok.substring(colon + 1).trim()));
				}
				catch (NumberFormatException ignored)
				{
					// drop malformed token
				}
			}
		}
		rebuildSnapshot();
		fire();
	}

	public void persist()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_SYNCED, joinInts(synced));
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> e : tabTotals.entrySet())
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		configManager.setRSProfileConfiguration(CONFIG_GROUP, KEY_TOTALS, sb.toString());
	}

	private static int key(int tabIndex, int categoryIndex)
	{
		return tabIndex * TAB_STRIDE + categoryIndex;
	}

	/** A page's items were just scraped. Call on the client thread. */
	public void recordOpenedPage(int tabIndex, int categoryIndex)
	{
		if (tabIndex < 0 || categoryIndex < 0)
		{
			return;
		}
		if (synced.add(key(tabIndex, categoryIndex)))
		{
			rebuildSnapshot();
			persist();
			fire();
		}
	}

	/** Whether the page at the given list position in a tab has been synced. */
	public boolean isSyncedPage(int tabIndex, int categoryIndex)
	{
		return snapshot.synced.contains(key(tabIndex, categoryIndex));
	}

	/** Number of category entries seen in a tab's left list (the per-tab universe). */
	public void recordTabTotal(int tabIndex, int count)
	{
		if (tabIndex < 0 || count <= 0)
		{
			return;
		}
		Integer prev = tabTotals.get(tabIndex);
		if (prev != null && prev >= count)
		{
			return;
		}
		tabTotals.put(tabIndex, count);
		rebuildSnapshot();
		persist();
		fire();
	}

	public int syncedCount()
	{
		return snapshot.syncedCount;
	}

	public int knownCount()
	{
		return snapshot.knownCount;
	}

	/** True once the tab's category total is known and every category under it is synced. */
	public boolean tabFullySynced(int tabIndex)
	{
		Integer total = snapshot.tabTotals.get(tabIndex);
		if (total == null || total <= 0)
		{
			return false;
		}
		int done = 0;
		int lo = tabIndex * TAB_STRIDE;
		for (int k : snapshot.synced)
		{
			if (k >= lo && k < lo + TAB_STRIDE)
			{
				done++;
			}
		}
		return done >= total;
	}

	/** False until every tab has been discovered and all its categories synced. */
	public boolean fullySynced()
	{
		return snapshot.fullySynced;
	}

	public void addChangeListener(Runnable r)
	{
		listeners.add(r);
	}

	private void rebuildSnapshot()
	{
		Set<Integer> syncedCopy = Collections.unmodifiableSet(new HashSet<>(synced));
		Map<Integer, Integer> totalsCopy =
			Collections.unmodifiableMap(new HashMap<>(tabTotals));
		int knownCount = 0;
		for (int v : totalsCopy.values())
		{
			knownCount += v;
		}
		boolean fully = totalsCopy.size() >= TAB_COUNT
			&& knownCount > 0
			&& syncedCopy.size() >= knownCount;
		snapshot = new Snapshot(syncedCopy, totalsCopy, syncedCopy.size(), knownCount, fully);
	}

	private void fire()
	{
		for (Runnable l : listeners)
		{
			try
			{
				l.run();
			}
			catch (RuntimeException ex)
			{
				log.warn("Sync-state listener threw", ex);
			}
		}
	}

	// Public for the assert-in-main test harness (lives in a different package).
	public static Set<Integer> parseInts(String csv)
	{
		Set<Integer> out = new HashSet<>();
		if (csv == null || csv.isEmpty())
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			String t = part.trim();
			if (t.isEmpty())
			{
				continue;
			}
			try
			{
				out.add(Integer.parseInt(t));
			}
			catch (NumberFormatException ignored)
			{
				// drop malformed token
			}
		}
		return out;
	}

	public static String joinInts(Set<Integer> values)
	{
		if (values.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(values.size() * 6);
		for (int v : values)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(v);
		}
		return sb.toString();
	}

	private static final class Snapshot
	{
		private final Set<Integer> synced;
		private final Map<Integer, Integer> tabTotals;
		private final int syncedCount;
		private final int knownCount;
		private final boolean fullySynced;

		private Snapshot(
			Set<Integer> synced,
			Map<Integer, Integer> tabTotals,
			int syncedCount,
			int knownCount,
			boolean fullySynced)
		{
			this.synced = synced;
			this.tabTotals = tabTotals;
			this.syncedCount = syncedCount;
			this.knownCount = knownCount;
			this.fullySynced = fullySynced;
		}

		private static Snapshot empty()
		{
			return new Snapshot(Collections.emptySet(), Collections.emptyMap(), 0, 0, false);
		}
	}
}
