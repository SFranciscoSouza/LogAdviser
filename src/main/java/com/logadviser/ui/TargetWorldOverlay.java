package com.logadviser.ui;

import com.logadviser.LogAdviserConfig;
import com.logadviser.data.ActivityNpcInfo;
import com.logadviser.data.StaticData;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ColorUtil;

@Slf4j
public class TargetWorldOverlay extends Overlay
{
	private static final Color HULL = new Color(173, 216, 230); // light blue
	private static final Color FILL = ColorUtil.colorWithAlpha(HULL, 30);
	private static final Color BORDER_DARKER = HULL.darker();

	private final Client client;
	private final AdviserEngine engine;
	private final StaticData staticData;
	private final LogAdviserConfig config;

	private final List<NPC> matched = new ArrayList<>();
	private Set<Integer> activeNpcIds = Collections.emptySet();

	@Inject
	public TargetWorldOverlay(Client client, AdviserEngine engine, StaticData staticData, LogAdviserConfig config)
	{
		this.client = client;
		this.engine = engine;
		this.staticData = staticData;
		this.config = config;
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
		engine.addListener(this::onRankingChanged);
		onRankingChanged(engine.getRanking());
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightNpcs() || matched.isEmpty())
		{
			return null;
		}
		for (NPC npc : matched)
		{
			if (npc == null || npc.getId() == -1)
			{
				continue;
			}
			Shape hull = npc.getConvexHull();
			if (hull == null)
			{
				continue;
			}
			OverlayUtil.renderHoverableArea(
				graphics,
				new Area(hull),
				client.getMouseCanvasPosition(),
				FILL,
				BORDER_DARKER,
				HULL);
		}
		return null;
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		considerNpc(event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		matched.remove(event.getNpc());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		matched.remove(event.getNpc());
		considerNpc(event.getNpc());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState s = event.getGameState();
		if (s == GameState.HOPPING || s == GameState.LOGIN_SCREEN)
		{
			matched.clear();
		}
		else if (s == GameState.LOGGED_IN)
		{
			rescan();
		}
	}

	private void considerNpc(NPC npc)
	{
		if (npc == null || activeNpcIds.isEmpty())
		{
			return;
		}
		if (matchesActive(npc))
		{
			matched.add(npc);
		}
	}

	private boolean matchesActive(NPC npc)
	{
		if (activeNpcIds.contains(npc.getId()))
		{
			return true;
		}
		NPCComposition comp = npc.getTransformedComposition();
		if (comp != null && activeNpcIds.contains(comp.getId()))
		{
			return true;
		}
		return false;
	}

	private void rescan()
	{
		matched.clear();
		if (activeNpcIds.isEmpty())
		{
			return;
		}
		client.getTopLevelWorldView().npcs().forEach(this::considerNpc);
	}

	private void onRankingChanged(List<RankedActivity> ranked)
	{
		if (ranked.isEmpty())
		{
			activeNpcIds = Collections.emptySet();
			matched.clear();
			return;
		}
		RankedActivity top = ranked.get(0);
		ActivityNpcInfo info = staticData.npcInfoFor(top.getActivity().getIndex());
		Set<Integer> next = new HashSet<>(info.getNpcIds());
		if (next.equals(activeNpcIds))
		{
			return;
		}
		activeNpcIds = Collections.unmodifiableSet(next);
		rescan();
	}
}
