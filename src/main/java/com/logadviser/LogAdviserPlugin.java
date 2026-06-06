package com.logadviser;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.logadviser.data.ActivityRequirements;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.PlayerProgress;
import com.logadviser.engine.RankedActivity;
import com.logadviser.sync.CollectionLogSyncState;
import com.logadviser.sync.CollectionLogTracker;
import com.logadviser.ui.CollectionLogSyncOverlay;
import com.logadviser.ui.LogAdviserPanel;
import com.logadviser.ui.TargetInfoBox;
import com.logadviser.ui.TargetTextOverlay;
import com.logadviser.ui.TargetWorldOverlay;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.vars.AccountType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Log Adviser",
	description = "Suggests the next collection log activity to chase, ranked by time-to-next-slot",
	tags = {"collection", "log", "clog", "adviser", "advisor"}
)
public class LogAdviserPlugin extends Plugin
{
	private static final java.util.concurrent.atomic.AtomicInteger LIVE_INSTANCES =
		new java.util.concurrent.atomic.AtomicInteger();
	private static final String CONFIG_GROUP = "logadviser";
	private static final String CFG_ACCOUNT_MODE = "accountModeOverride";
	private static final String CFG_IGNORE_REQ = "ignoreRequirements";
	// Quest completion has no dedicated event, so we re-poll on a throttled GameTick.
	private static final int PROGRESS_POLL_TICKS = 10;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ConfigManager configManager;
	@Inject private LogAdviserConfig config;
	@Inject private Gson gson;
	@Inject private ItemManager itemManager;
	@Inject private InfoBoxManager infoBoxManager;
	@Inject private OverlayManager overlayManager;
	@Inject private net.runelite.client.eventbus.EventBus eventBus;

	private StaticData staticData;
	private AdviserEngine engine;
	private CollectionLogTracker tracker;
	private CollectionLogSyncState syncState;
	private CollectionLogSyncOverlay syncOverlay;
	private LogAdviserPanel panel;
	private NavigationButton navButton;
	private TargetInfoBox currentInfoBox;
	private TargetWorldOverlay worldOverlay;
	private TargetTextOverlay textOverlay;
	// Cached account-type flag. client.getAccountType() reads a varbit and asserts
	// it's invoked on the client thread, so we never call it from startUp/EDT — only
	// from @Subscribe events (already on the client thread) and from clientThread.invokeLater.
	private volatile boolean detectedIronman = false;
	// Union of every skill/quest referenced by activity_requirements.json — so the
	// per-tick progress poll only touches what actually gates an activity.
	private Set<Skill> reqSkills = new HashSet<>();
	private Set<Quest> reqQuests = new HashSet<>();
	// Last quest-completion snapshot, refreshed only on GameTick (a script-safe
	// point). StatChanged reuses this so it never has to run Quest.getState().
	private Set<Quest> finishedQuestsCache = new HashSet<>();
	private int progressPollTick = 0;

	@Override
	protected void startUp() throws Exception
	{
		staticData = StaticDataLoader.loadAll(gson);
		engine = new AdviserEngine(staticData, this::detectedIronman);

		reqSkills = new HashSet<>();
		reqQuests = new HashSet<>();
		for (Map.Entry<Integer, ActivityRequirements> e : staticData.getRequirementsByActivity().entrySet())
		{
			reqSkills.addAll(e.getValue().getSkillLevels().keySet());
			reqQuests.addAll(e.getValue().getQuests());
		}
		// Unmistakable build marker — if this line is absent from the client log, the
		// running client is NOT this build (stale jar / Plugin Hub copy is loaded).
		int instances = LIVE_INSTANCES.incrementAndGet();
		log.info("Log Adviser [requirement-gating build] startUp #{} (cl={}): {} requirement maps, "
				+ "skills={}, quests={}",
			instances, getClass().getClassLoader(),
			staticData.getRequirementsByActivity().size(), reqSkills, reqQuests);
		if (instances > 1)
		{
			log.warn("Log Adviser: {} live instances — a duplicate (e.g. Plugin Hub) copy "
				+ "is loaded; the panel you see may be the stale one", instances);
		}

		// Construct tracker manually — its @Inject ctor needs StaticData/AdviserEngine,
		// which aren't Guice bindings. Guice would fail with "no implementation for ..."
		// and the plugin toggle would refuse to enable.
		syncState = new CollectionLogSyncState(configManager, clientThread);
		tracker = new CollectionLogTracker(
			client,
			clientThread,
			configManager,
			staticData,
			engine,
			syncState);
		panel = new LogAdviserPanel(
			engine,
			itemManager,
			tracker,
			syncState,
			staticData,
			this::onAccountModeSelected,
			this::onIgnoreRequirementsSelected,
			config::upcomingListSize);

		engine.addListener(this::onRankingChanged);

		worldOverlay = new TargetWorldOverlay(client, engine, staticData, config);
		overlayManager.add(worldOverlay);

		textOverlay = new TargetTextOverlay(engine);
		overlayManager.add(textOverlay);

		syncOverlay = new CollectionLogSyncOverlay(client, syncState);
		overlayManager.add(syncOverlay);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/logadviser/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Log Adviser")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		eventBus.register(tracker);
		eventBus.register(syncState);
		eventBus.register(worldOverlay);

		syncState.addChangeListener(panel::onSyncStateChanged);
		// A page sync with no new item doesn't fire the engine, so re-run the infobox /
		// overlay rebuild on the client thread to refresh the "data may be stale" line.
		syncState.addChangeListener(() -> clientThread.invokeLater(() ->
		{
			onRankingChanged(engine.getRanking());
			return true;
		}));

		// If we're already logged in when the plugin enables (toggling at runtime), pull
		// state on the client thread — including the first read of the account-type varbit.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				refreshDetectedIronman();
				tracker.load();
				syncState.load();
				applyAccountModeFromConfig();
				applyIgnoreRequirementsFromConfig();
				refreshPlayerProgress();
				updatePlayerLabel();
				return true;
			});
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		LIVE_INSTANCES.decrementAndGet();
		if (tracker != null)
		{
			eventBus.unregister(tracker);
		}
		if (syncState != null)
		{
			eventBus.unregister(syncState);
		}
		if (syncOverlay != null)
		{
			overlayManager.remove(syncOverlay);
			syncOverlay = null;
		}
		if (worldOverlay != null)
		{
			eventBus.unregister(worldOverlay);
			overlayManager.remove(worldOverlay);
		}
		if (textOverlay != null)
		{
			overlayManager.remove(textOverlay);
			textOverlay = null;
		}
		if (currentInfoBox != null)
		{
			infoBoxManager.removeInfoBox(currentInfoBox);
			currentInfoBox = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		if (panel != null)
		{
			panel.shutdown();
		}
	}

	@Provides
	LogAdviserConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LogAdviserConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				refreshDetectedIronman();
				applyAccountModeFromConfig();
				applyIgnoreRequirementsFromConfig();
				refreshPlayerProgress();
				updatePlayerLabel();
				return true;
			});
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			if (panel != null)
			{
				panel.setPlayerLabel(null, false);
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Account type can change in-session (group iron rank changes, "demote" actions).
		// We're on the client thread here, so reading the varbit is safe.
		refreshDetectedIronman();
		if (panel != null)
		{
			updatePlayerLabel();
		}
		// NOTE: do NOT read quest state here. VarbitChanged is posted from inside a
		// running client script; Quest.getState() runs a script and re-entering the
		// script engine throws "scripts are not reentrant". Quest polling is done on
		// GameTick (a safe, non-script point) instead.
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		if ("displayMode".equals(key))
		{
			// Reapply the most recent ranking to add/remove infobox or clear overlay matches.
			if (engine != null)
			{
				onRankingChanged(engine.getRanking());
			}
		}
		else if ("upcomingListSize".equals(key) && engine != null && panel != null)
		{
			// Push the new size into the panel by re-rebuilding the list from the
			// most recent ranking — IntSupplier picks up the new value on read.
			panel.onRankingChanged(engine.getRanking());
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Level-ups are immediate. getRealSkillLevel() is script-free so it's safe in
		// any event handler; quest state is taken from the cache (last GameTick poll)
		// so this never re-enters the script engine.
		if (reqSkills.contains(event.getSkill()))
		{
			refreshSkillLevels();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Quest completion has no event — poll on a throttle. setPlayerProgress() is a
		// no-op when nothing changed, so a quiet tick costs nothing past this check.
		if (reqQuests.isEmpty())
		{
			return;
		}
		if (++progressPollTick < PROGRESS_POLL_TICKS)
		{
			return;
		}
		progressPollTick = 0;
		refreshPlayerProgress();
	}

	private Map<Skill, Integer> readReqLevels()
	{
		Map<Skill, Integer> levels = new HashMap<>();
		for (Skill s : reqSkills)
		{
			levels.put(s, client.getRealSkillLevel(s));
		}
		return levels;
	}

	/** Script-free: refreshes only skill levels, reusing the cached quest snapshot.
	 *  Safe to call from any client-thread event handler (incl. StatChanged). */
	private void refreshSkillLevels()
	{
		if (engine == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		engine.setPlayerProgress(new PlayerProgress(readReqLevels(), new HashSet<>(finishedQuestsCache)));
	}

	/** Full snapshot incl. quest state. Quest.getState() runs a client script, so
	 *  this MUST only be called from a script-safe point — GameTick or a
	 *  clientThread.invokeLater callback — never directly from VarbitChanged/StatChanged. */
	private void refreshPlayerProgress()
	{
		if (engine == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Map<Skill, Integer> levels = readReqLevels();
		Set<Quest> finished = new HashSet<>();
		for (Quest q : reqQuests)
		{
			if (q.getState(client) == QuestState.FINISHED)
			{
				finished.add(q);
			}
		}
		finishedQuestsCache = new HashSet<>(finished);
		engine.setPlayerProgress(new PlayerProgress(levels, finished));

		if (log.isDebugEnabled())
		{
			long locked = engine.getRanking().stream().filter(RankedActivity::isLocked).count();
			log.debug("Log Adviser progress: levels={}, finishedQuests={}, lockedActivities={}",
				levels, finished, locked);
		}
	}

	private void onIgnoreRequirementsSelected(boolean ignore)
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CFG_IGNORE_REQ, ignore);
		clientThread.invokeLater(() ->
		{
			engine.setIgnoreRequirements(ignore);
			return true;
		});
	}

	private void applyIgnoreRequirementsFromConfig()
	{
		String saved = configManager.getRSProfileConfiguration(CONFIG_GROUP, CFG_IGNORE_REQ);
		boolean ignore = Boolean.parseBoolean(saved);
		engine.setIgnoreRequirements(ignore);
		if (panel != null)
		{
			panel.setIgnoreRequirements(ignore);
		}
	}

	/** Cheap, thread-safe read used by the engine. */
	private boolean detectedIronman()
	{
		return detectedIronman;
	}

	/** Reads the account-type varbit. MUST be called on the client thread. */
	private void refreshDetectedIronman()
	{
		AccountType type = client.getAccountType();
		boolean updated = type != null && type.isIronman();
		if (updated == detectedIronman)
		{
			return;
		}
		detectedIronman = updated;
		if (engine != null && engine.getAccountMode() == AccountMode.AUTO)
		{
			engine.refreshRates();
		}
	}

	private void onAccountModeSelected(AccountMode mode)
	{
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CFG_ACCOUNT_MODE, mode.name());
		clientThread.invokeLater(() ->
		{
			engine.setAccountMode(mode);
			updatePlayerLabel();
			return true;
		});
	}

	private void applyAccountModeFromConfig()
	{
		String saved = configManager.getRSProfileConfiguration(CONFIG_GROUP, CFG_ACCOUNT_MODE);
		AccountMode mode = AccountMode.parse(saved);
		engine.setAccountMode(mode);
		if (panel != null)
		{
			panel.setAccountMode(mode);
		}
	}

	private void updatePlayerLabel()
	{
		if (panel == null)
		{
			return;
		}
		String name = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
		panel.setPlayerLabel(name, detectedIronman());
	}

	private void onRankingChanged(List<RankedActivity> ranking)
	{
		// Drop the old InfoBox unconditionally — it's cheaper to rebuild than to mutate.
		if (currentInfoBox != null)
		{
			infoBoxManager.removeInfoBox(currentInfoBox);
			currentInfoBox = null;
		}

		LogAdviserConfig.DisplayMode mode = config.displayMode();

		if (ranking.isEmpty())
		{
			if (textOverlay != null)
			{
				textOverlay.clearTarget();
			}
			return;
		}

		RankedActivity top = ranking.get(0);
		int displayItemId = top.getEasiestItem() != null
			? top.getEasiestItem().getItemId()
			: (top.getFastestItem() != null ? top.getFastestItem().getItemId() : -1);
		String itemName = top.getEasiestItem() != null ? top.getEasiestItem().getItemName()
			: (top.getFastestItem() != null ? top.getFastestItem().getItemName() : "");
		String hint = staticData.npcInfoFor(top.getActivity().getIndex()).getHint();

		if (mode.overlayPanel() && textOverlay != null)
		{
			textOverlay.setTarget(top, itemName, hint);
		}
		else if (textOverlay != null)
		{
			textOverlay.clearTarget();
		}

		if (mode.infoBox() && displayItemId > 0)
		{
			BufferedImage img = itemManager.getImage(displayItemId);
			currentInfoBox = new TargetInfoBox(img, this, top, itemName, hint, syncState.fullySynced());
			infoBoxManager.addInfoBox(currentInfoBox);
		}
	}
}
