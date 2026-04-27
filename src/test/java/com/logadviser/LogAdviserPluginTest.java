package com.logadviser;

import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.util.List;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LogAdviserPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Sanity-check static data + engine before launching the dev client.
		StaticData data = StaticDataLoader.loadAll();
		assert data.getActivities().size() == 250
			: "expected 250 activities, got " + data.getActivities().size();
		assert data.getActivityItems().size() == 2486
			: "expected 2486 activity_map rows, got " + data.getActivityItems().size();
		assert data.getSlots().size() == 1692
			: "expected 1692 slot rows, got " + data.getSlots().size();

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

		// Hand-off to the dev client.
		ExternalPluginManager.loadBuiltin(LogAdviserPlugin.class);
		RuneLite.main(args);
	}
}
