package com.logadviser.sync;

import com.logadviser.data.LogSlot;
import com.logadviser.data.StaticData;
import com.logadviser.engine.AdviserEngine;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * The collection log "sync brain". Three things feed the obtained set:
 *   1) ChatMessage "New item added to your collection log" — live drops.
 *   2) A one-click full sync: a {@link CollectionLogSyncButton} on the collection log
 *      interface presses the game's own Search and runs the enumerate script (2240),
 *      which fires per-item callback script 4100 for every obtained item — we harvest
 *      those and merge them in. This replaces the old open-every-page scrape.
 *
 * It also answers "is the plugin's data behind the player's real collection log?" by
 * comparing the player's true {@link VarPlayerID#COLLECTION_COUNT} against a persisted
 * baseline captured at the last point our data was known-complete. Out-of-sync drives a
 * login warning and the sidebar indication.
 *
 * <p>All mutating work runs on the client thread; the panel reads the {@code volatile}
 * {@link #inSync}/{@link #playerCount} fields cross-thread.
 */
@Slf4j
@Singleton
public class CollectionLogTracker
{
	private static final String CONFIG_GROUP = "logadviser";
	private static final String CONFIG_KEY = "obtained";
	private static final String CONFIG_KEY_SKIPPED = "skipped";
	private static final String CONFIG_KEY_BASELINE = "clogBaseline";
	private static final String NEW_ITEM_PREFIX = "New item added to your collection log:";

	// Fires once when the collection log interface is built — re-attach the button here.
	private static final int SCRIPT_COLLECTION_SETUP = 7797;
	// Per-item callback the enumerate script fires for every OBTAINED slot.
	private static final int SCRIPT_COLLECTION_ITEM = 4100;
	// The cs2 proc that walks the whole log and re-fires 4100 for all obtained items.
	private static final int SCRIPT_ENUMERATE_LOG = 2240;
	// Large logs keep firing 4100 for a few ticks; settle before consuming the harvest.
	private static final int SYNC_SETTLE_TICKS = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final StaticData staticData;
	private final AdviserEngine engine;
	private final CollectionLogSyncButton syncButton;

	private final Map<String, Integer> itemIdsByName;

	// Full-sync harvest state (client thread only).
	private final Set<Integer> harvest = new HashSet<>();
	private Integer gameTickToSync = null;
	// True only between a Sync-button click and the harvest being consumed — gates the
	// "Syncing..." button label and the baseline re-pin (passive 4100s from just browsing
	// the log still get merged, but must not claim a full sync).
	private boolean fullSyncRequested = false;

	// Sync-status state (client thread only, except the volatiles read by the panel).
	private int baseline = -1;            // player COLLECTION_COUNT at last known-complete; -1 = none stored
	private boolean migrationDone = false; // one-shot per login: seed/keep baseline once the varp is known
	private int obtainedCountAtLogin = 0;  // captured at load() so live drops can't flip the migration call
	private volatile boolean inSync = true;
	private volatile int playerCount = 0;

	private final List<Runnable> syncListeners = new CopyOnWriteArrayList<>();
	// Notified true when a full sync starts and false when it finishes, so the panel can
	// show "Syncing..." somewhere the collection-log redraw can't cover (unlike the button).
	private java.util.function.Consumer<Boolean> syncingListener;

	@Inject
	public CollectionLogTracker(
		Client client,
		ClientThread clientThread,
		ConfigManager configManager,
		StaticData staticData,
		AdviserEngine engine,
		CollectionLogSyncButton syncButton)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.staticData = staticData;
		this.engine = engine;
		this.syncButton = syncButton;
		this.itemIdsByName = staticData.itemIdsByName();
	}

	public void load()
	{
		Set<Integer> ids = parseCsv(configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY));
		Set<Integer> skips = parseCsv(configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_SKIPPED));
		engine.replaceObtained(ids);
		engine.replaceSkipped(skips);

		String savedBaseline = configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_BASELINE);
		if (savedBaseline != null && !savedBaseline.isEmpty())
		{
			try
			{
				baseline = Integer.parseInt(savedBaseline.trim());
				migrationDone = true;
			}
			catch (NumberFormatException ex)
			{
				baseline = -1;
				migrationDone = false;
			}
		}
		else
		{
			baseline = -1;
			migrationDone = false;
		}
		// Snapshot the recorded count NOW, before any live drops this session, so the
		// migration decision (existing user vs. brand new) can't be skewed later.
		obtainedCountAtLogin = engine.collectedSlotCount();
	}

	public void persist()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY, toCsv(engine.obtainedItems()));
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_SKIPPED, toCsv(engine.skippedActivities()));
	}

	public void persistSkipped()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_SKIPPED, toCsv(engine.skippedActivities()));
	}

	private void persistBaseline()
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY_BASELINE, Integer.toString(baseline));
	}

	private void markAndPersist(int itemId)
	{
		boolean wasObtained = engine.isObtained(itemId);
		engine.markObtained(itemId);
		if (!wasObtained)
		{
			persist();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Defer one tick so RSProfile has the display name attached.
			clientThread.invokeLater(() ->
			{
				load();
				syncButton.reset();
				evaluateSyncStatus();
				return true;
			});
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			syncButton.reset();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		switch (event.getType())
		{
			case GAMEMESSAGE:
			case SPAM:
				break;
			default:
				return;
		}
		String message = Text.removeTags(event.getMessage());
		if (!message.startsWith(NEW_ITEM_PREFIX))
		{
			return;
		}
		// A new collection log unlock advances the player's COLLECTION_COUNT by one and we
		// just captured it — keep the baseline in lockstep so this doesn't read as drift.
		// (Only once a baseline exists; a never-synced account stays out of sync until a
		// full Sync so we still nudge it to run one.)
		if (baseline >= 0)
		{
			baseline++;
			persistBaseline();
		}
		String itemName = message.substring(NEW_ITEM_PREFIX.length()).trim();
		Integer id = itemIdsByName.get(itemName.toLowerCase());
		if (id == null)
		{
			log.debug("Could not resolve collection log item name: {}", itemName);
		}
		else
		{
			markAndPersist(id);
		}
		evaluateSyncStatus();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == SCRIPT_COLLECTION_SETUP)
		{
			// The interface was (re)built — its dynamic children were wiped, so re-attach.
			syncButton.reset();
			syncButton.attach(client, this::triggerFullSync);
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != SCRIPT_COLLECTION_ITEM)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 2)
		{
			return;
		}
		int itemId = (int) args[1];
		if (itemId > 0)
		{
			harvest.add(itemId);
		}
		// Push the consume out to a few ticks after the LAST item so a large log finishes
		// streaming before we read it.
		gameTickToSync = client.getTickCount() + SYNC_SETTLE_TICKS;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (fullSyncRequested)
		{
			// A collection-log redraw can rebuild the button and revert it to "Sync";
			// re-pin the busy label each tick until we consume.
			syncButton.setBusy(true);
		}
		if (gameTickToSync == null || client.getTickCount() < gameTickToSync)
		{
			return;
		}
		consumeHarvest();
	}

	/** Press the in-game Search and run the enumerate script; harvested in onScriptPreFired. */
	private void triggerFullSync()
	{
		harvest.clear();
		fullSyncRequested = true;
		// Guarantee a consume even if the log is empty (no 4100 fires) so the button never
		// gets stuck on "Syncing..."; streaming items push this out further (see 4100).
		gameTickToSync = client.getTickCount() + SYNC_SETTLE_TICKS;
		syncButton.setBusy(true);
		notifySyncing(true);
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(SCRIPT_ENUMERATE_LOG);
	}

	/** Merge every harvested item into the obtained set and re-baseline. Client thread. */
	private void consumeHarvest()
	{
		int added = 0;
		for (int itemId : harvest)
		{
			if (!engine.isObtained(itemId))
			{
				added++;
			}
			engine.markObtained(itemId);
		}
		if (added > 0)
		{
			persist();
		}
		log.debug("Collection log harvest: {} items seen, {} newly obtained (fullSync={})",
			harvest.size(), added, fullSyncRequested);
		harvest.clear();
		gameTickToSync = null;

		if (fullSyncRequested)
		{
			fullSyncRequested = false;
			syncButton.setBusy(false);
			notifySyncing(false);
			// A full sync makes us complete: pin the baseline to the player's true count.
			int pc = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
			if (pc > 0)
			{
				baseline = pc;
				migrationDone = true;
				persistBaseline();
			}
		}
		evaluateSyncStatus();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() == VarPlayerID.COLLECTION_COUNT)
		{
			evaluateSyncStatus();
		}
	}

	/**
	 * Recomputes {@link #inSync} from the player's true count vs. the baseline, runs the
	 * one-shot migration when no baseline is stored yet, and fans out to listeners.
	 */
	public void evaluateSyncStatus()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		int pc = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		playerCount = pc;
		if (pc <= 0)
		{
			// Varp not delivered yet, or a fresh account with no logged items — don't warn.
			inSync = true;
			fire();
			return;
		}
		if (!migrationDone && baseline < 0)
		{
			// First run on this build. An existing user (recorded data from the old
			// per-page sync) is treated as already synced; a brand-new user is nudged
			// to run a full Sync.
			if (obtainedCountAtLogin > 0)
			{
				baseline = pc;
				persistBaseline();
			}
			migrationDone = true;
		}
		inSync = baseline >= 0 && pc <= baseline;
		fire();
	}

	public boolean isInSync()
	{
		return inSync;
	}

	public int playerClogCount()
	{
		return playerCount;
	}

	public void addSyncListener(Runnable r)
	{
		syncListeners.add(r);
	}

	/** Set the listener notified when a full sync starts (true) / finishes (false). */
	public void setSyncingListener(java.util.function.Consumer<Boolean> l)
	{
		this.syncingListener = l;
	}

	private void notifySyncing(boolean syncing)
	{
		if (syncingListener != null)
		{
			try
			{
				syncingListener.accept(syncing);
			}
			catch (RuntimeException ex)
			{
				log.warn("Syncing listener threw", ex);
			}
		}
	}

	private void fire()
	{
		for (Runnable l : syncListeners)
		{
			try
			{
				l.run();
			}
			catch (RuntimeException ex)
			{
				log.warn("Sync-status listener threw", ex);
			}
		}
	}

	private static Set<Integer> parseCsv(String csv)
	{
		if (csv == null || csv.isEmpty())
		{
			return new HashSet<>();
		}
		return Arrays.stream(csv.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.map(s ->
			{
				try
				{
					return Integer.parseInt(s);
				}
				catch (NumberFormatException ex)
				{
					return null;
				}
			})
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toCollection(HashSet::new));
	}

	private static String toCsv(Set<Integer> ids)
	{
		if (ids.isEmpty())
		{
			return "";
		}
		StringBuilder sb = new StringBuilder(ids.size() * 6);
		boolean first = true;
		for (Integer id : ids)
		{
			if (!first)
			{
				sb.append(',');
			}
			sb.append(id);
			first = false;
		}
		return sb.toString();
	}

	// Convenience for slot lookups by item id (used by panel for display names).
	public LogSlot slotFor(int itemId)
	{
		return staticData.slotsByItemId().get(itemId);
	}
}
