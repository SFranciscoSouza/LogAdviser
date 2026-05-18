package com.logadviser;

import com.google.gson.Gson;
import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import com.logadviser.sync.CollectionLogSyncState;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LogAdviserPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Sanity-check static data + engine before launching the dev client.
		StaticData data = StaticDataLoader.loadAll(new Gson());
		assert data.getActivities().size() == 251
			: "expected 251 activities, got " + data.getActivities().size();
		assert data.getActivityItems().size() == 2498
			: "expected 2498 activity_map rows, got " + data.getActivityItems().size();
		assert data.getSlots().size() == 1700
			: "expected 1700 slot rows, got " + data.getSlots().size();

		AdviserEngine engineMain = new AdviserEngine(data, () -> false);
		engineMain.setAccountMode(AccountMode.MAIN);
		List<RankedActivity> rankedMain = engineMain.getRanking();
		assert !rankedMain.isEmpty() : "main ranking is empty";
		String topMain = rankedMain.get(0).getActivity().getName();
		System.out.println("Top (main, fresh log): " + topMain);
		assert "Killing gryphons (on task)".equalsIgnoreCase(topMain)
			: "expected gryphons at the top, got: " + topMain;

		AdviserEngine engineIron = new AdviserEngine(data, () -> true);
		engineIron.setAccountMode(AccountMode.IRONMAN);
		List<RankedActivity> rankedIron = engineIron.getRanking();
		assert !rankedIron.isEmpty() : "iron ranking is empty";
		System.out.println("Top (iron, fresh log): " + rankedIron.get(0).getActivity().getName());

		// Synced-page id persistence round-trip (CSV of tab*100000+category keys).
		assert CollectionLogSyncState.parseInts(null).isEmpty() : "null csv should be empty";
		assert CollectionLogSyncState.parseInts("").isEmpty() : "empty csv should be empty";
		assert CollectionLogSyncState.parseInts("1, 2 ,x,3").equals(
			new HashSet<>(Arrays.asList(1, 2, 3))) : "parseInts should skip blanks/garbage";
		Set<Integer> keys = new HashSet<>(Arrays.asList(5, 100_006, 400_012));
		assert CollectionLogSyncState.parseInts(CollectionLogSyncState.joinInts(keys)).equals(keys)
			: "synced-id CSV round-trip failed";
		System.out.println("Synced-id CSV round-trip OK");

		// Hand-off to the dev client.
		ExternalPluginManager.loadBuiltin(LogAdviserPlugin.class);
		RuneLite.main(args);
	}
}
