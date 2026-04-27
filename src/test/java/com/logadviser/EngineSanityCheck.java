package com.logadviser;

import com.logadviser.data.StaticData;
import com.logadviser.data.StaticDataLoader;
import com.logadviser.engine.AccountMode;
import com.logadviser.engine.AdviserEngine;
import com.logadviser.engine.RankedActivity;
import java.util.List;

/** Headless sanity check, run via `gradle dataSanity` or by main. */
public class EngineSanityCheck
{
	public static void main(String[] args) throws Exception
	{
		StaticData data = StaticDataLoader.loadAll();
		System.out.println("activities:   " + data.getActivities().size());
		System.out.println("activityItems:" + data.getActivityItems().size());
		System.out.println("slots:        " + data.getSlots().size());
		System.out.println("npc maps:     " + data.getNpcInfoByActivity().size());

		AdviserEngine main = new AdviserEngine(data, () -> false);
		main.setAccountMode(AccountMode.MAIN);
		print("MAIN top 10", main.getRanking(), 10);

		AdviserEngine iron = new AdviserEngine(data, () -> true);
		iron.setAccountMode(AccountMode.IRONMAN);
		print("IRON top 10", iron.getRanking(), 10);

		// Mark gryphon feather obtained — top should change.
		RankedActivity top = main.getRanking().get(0);
		System.out.println();
		System.out.println("Marking " + top.getEasiestItem().getItemName()
			+ " (id=" + top.getEasiestItem().getItemId() + ") obtained");
		main.markObtained(top.getEasiestItem().getItemId());
		print("MAIN top 5 after one slot", main.getRanking(), 5);
	}

	private static void print(String header, List<RankedActivity> rs, int n)
	{
		System.out.println();
		System.out.println(header);
		for (int i = 0; i < Math.min(n, rs.size()); i++)
		{
			RankedActivity r = rs.get(i);
			double secs = r.getTimeToNextSlotHours() * 3600.0;
			System.out.printf("  %2d. %-60s  %s%n",
				i + 1,
				r.getActivity().getName(),
				secs < 90 ? String.format("%.1fs", secs)
					: secs < 3600 ? String.format("%.1fm", secs / 60.0)
					: String.format("%.2fh", r.getTimeToNextSlotHours()));
		}
	}
}
