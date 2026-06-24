package com.logadviser;

import com.google.gson.Gson;
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
		// Isolate the dev client to its own RuneLite home BEFORE any RuneLite class
		// loads (RuneLite.RUNELITE_DIR is derived from user.home at class-init time).
		// Without this, gradlew run also loads the Plugin Hub copy of "Log Adviser"
		// from ~/.runelite — two plugins with the same name, and the stale hub panel
		// is the one you end up looking at.
		java.io.File devHome = new java.io.File(System.getProperty("user.dir"), "build/runelite-dev");
		//noinspection ResultOfMethodCallIgnored
		devHome.mkdirs();
		System.setProperty("user.home", devHome.getAbsolutePath());
		System.out.println("[dev] isolated RuneLite home: " + devHome.getAbsolutePath()
			+ "  (no Plugin Hub plugins load here — only the builtin dev plugin)");

		// Sanity-check static data + engine before launching the dev client.
		StaticData data = StaticDataLoader.loadAll(new Gson());
		assert data.getActivities().size() == 254
			: "expected 254 activities, got " + data.getActivities().size();
		assert data.getActivityItems().size() == 2509
			: "expected 2509 activity_map rows, got " + data.getActivityItems().size();
		assert data.getSlots().size() == 1701
			: "expected 1701 slot rows, got " + data.getSlots().size();

		// This is a data/ranking-math sanity check, not a requirements test. With no
		// player progress everything gated would be locked, so ignore requirements
		// here to keep the pre-feature expectation (gryphons fastest on a fresh log).
		AdviserEngine engineMain = new AdviserEngine(data, () -> false);
		engineMain.setIgnoreRequirements(true);
		engineMain.setAccountMode(AccountMode.MAIN);
		List<RankedActivity> rankedMain = engineMain.getRanking();
		assert !rankedMain.isEmpty() : "main ranking is empty";
		String topMain = rankedMain.get(0).getActivity().getName();
		System.out.println("Top (main, fresh log): " + topMain);
		assert "Killing gryphons (on task)".equalsIgnoreCase(topMain)
			: "expected gryphons at the top, got: " + topMain;

		AdviserEngine engineIron = new AdviserEngine(data, () -> true);
		engineIron.setIgnoreRequirements(true);
		engineIron.setAccountMode(AccountMode.IRONMAN);
		List<RankedActivity> rankedIron = engineIron.getRanking();
		assert !rankedIron.isEmpty() : "iron ranking is empty";
		System.out.println("Top (iron, fresh log): " + rankedIron.get(0).getActivity().getName());

		// Hand-off to the dev client.
		ExternalPluginManager.loadBuiltin(LogAdviserPlugin.class);
		RuneLite.main(args);
	}
}
