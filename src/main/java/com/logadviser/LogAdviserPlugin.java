package com.logadviser;

import com.google.inject.Provides;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import com.logadviser.sync.CollectionLogTracker;
import com.logadviser.sync.HiscoreRankFetcher;
import com.logadviser.ui.LogAdviserPanel;
import com.logadviser.ui.TargetInfoBox;
import com.logadviser.ui.TargetTextOverlay;
import com.logadviser.ui.TargetWorldOverlay;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.vars.AccountType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
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
	private static final String CONFIG_GROUP = "logadviser";
	private static final String CFG_ACCOUNT_MODE = "accountModeOverride";

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ConfigManager configManager;
	@Inject private LogAdviserConfig config;
	@Inject private ItemManager itemManager;
	@Inject private InfoBoxManager infoBoxManager;
	@Inject private OverlayManager overlayManager;
	@Inject private HiscoreRankFetcher hiscoreFetcher;
	@Inject private com.logadviser.sync.TempleOsrsClient templeClient;
	@Inject private net.runelite.client.eventbus.EventBus eventBus;

	private StaticData staticData;
	private AdviserEngine engine;
	private CollectionLogTracker tracker;
	private LogAdviserPanel panel;
	private NavigationButton navButton;
	private TargetInfoBox currentInfoBox;
	private TargetWorldOverlay worldOverlay;
	private TargetTextOverlay textOverlay;
	// Cached account-type flag. client.getAccountType() reads a varbit and asserts
	// it's invoked on the client thread, so we never call it from startUp/EDT — only
	// from @Subscribe events (already on the client thread) and from clientThread.invokeLater.
	private volatile boolean detectedIronman = false;

	@Override
	protected void startUp() throws Exception
	{
		staticData = StaticDataLoader.loadAll();
		engine = new AdviserEngine(staticData, this::detectedIronman);

		// Construct tracker manually — its @Inject ctor needs StaticData/AdviserEngine,
		// which aren't Guice bindings. Guice would fail with "no implementation for ..."
		// and the plugin toggle would refuse to enable.
		tracker = new CollectionLogTracker(
			client,
			clientThread,
			configManager,
			staticData,
			engine,
			templeClient);
		panel = new LogAdviserPanel(
			engine,
			itemManager,
			tracker,
			staticData,
			this::onAccountModeSelected,
			this::refreshHiscores,
			config::upcomingListSize);

		engine.addListener(this::onRankingChanged);

		worldOverlay = new TargetWorldOverlay(client, engine, staticData, config);
		overlayManager.add(worldOverlay);

		textOverlay = new TargetTextOverlay(engine);
		overlayManager.add(textOverlay);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/logadviser/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Log Adviser")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		eventBus.register(tracker);
		eventBus.register(worldOverlay);

		// If we're already logged in when the plugin enables (toggling at runtime), pull
		// state on the client thread — including the first read of the account-type varbit.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				refreshDetectedIronman();
				tracker.load();
				applyAccountModeFromConfig();
				updatePlayerLabel();
				refreshHiscores();
				return true;
			});
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (tracker != null)
		{
			eventBus.unregister(tracker);
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
				updatePlayerLabel();
				refreshHiscores();
				return true;
			});
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			if (panel != null)
			{
				panel.setPlayerLabel(null, false);
			}
			hiscoreFetcher.invalidate();
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

	private void refreshHiscores()
	{
		if (panel == null || client.getLocalPlayer() == null)
		{
			return;
		}
		String name = client.getLocalPlayer().getName();
		if (name == null || name.isEmpty())
		{
			return;
		}
		HiscoreRankFetcher.Endpoint endpoint = endpointForDetectedType();
		hiscoreFetcher.fetchAsync(name, endpoint, (result, err) ->
		{
			if (result == null)
			{
				panel.setHiscoreRank(null, null);
				return;
			}
			panel.setHiscoreRank(result.getRank(), result.getScore());
		});
	}

	private HiscoreRankFetcher.Endpoint endpointForDetectedType()
	{
		AccountType type = client.getAccountType();
		if (type == null)
		{
			return HiscoreRankFetcher.Endpoint.MAIN;
		}
		switch (type)
		{
			case ULTIMATE_IRONMAN:
				return HiscoreRankFetcher.Endpoint.ULTIMATE_IRONMAN;
			case HARDCORE_IRONMAN:
				return HiscoreRankFetcher.Endpoint.HARDCORE_IRONMAN;
			case IRONMAN:
			case GROUP_IRONMAN:
			case HARDCORE_GROUP_IRONMAN:
				return HiscoreRankFetcher.Endpoint.IRONMAN;
			default:
				return HiscoreRankFetcher.Endpoint.MAIN;
		}
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
			currentInfoBox = new TargetInfoBox(img, this, top, itemName, hint);
			infoBoxManager.addInfoBox(currentInfoBox);
		}
	}
}
