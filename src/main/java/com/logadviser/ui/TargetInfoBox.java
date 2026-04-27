package com.logadviser.ui;

import com.logadviser.engine.RankedActivity;
import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

public class TargetInfoBox extends InfoBox
{
	private final String itemName;
	private final String activityName;
	private final String hint;
	private final double timeHours;
	private final int slotsLeft;
	private final int slotsTotal;

	public TargetInfoBox(BufferedImage image, Plugin plugin, RankedActivity ranked, String itemName, String hint)
	{
		super(image, plugin);
		this.itemName = itemName;
		this.activityName = ranked.getActivity().getName();
		this.hint = hint;
		this.timeHours = ranked.getTimeToNextSlotHours();
		this.slotsLeft = ranked.getSlotsLeft();
		this.slotsTotal = ranked.getSlotsTotal();
	}

	@Override
	public String getText()
	{
		return null;
	}

	@Override
	public Color getTextColor()
	{
		return Color.WHITE;
	}

	@Override
	public String getTooltip()
	{
		// RuneLite tooltips use Jagex tag syntax (<col=hex>...</col>, <br>) — NOT HTML.
		// Wrapping in <html><b>...</b></html> renders the markup literally.
		StringBuilder sb = new StringBuilder();
		sb.append("Next log slot: <col=ffff00>").append(itemName).append("</col><br>");
		sb.append("Activity: ").append(activityName).append("<br>");
		if (hint != null && !hint.isEmpty())
		{
			sb.append("How: <col=aaaaaa>").append(hint).append("</col><br>");
		}
		sb.append("Time to slot: <col=9bc7ff>").append(formatHours(timeHours)).append("</col><br>");
		sb.append("Progress: ").append(slotsTotal - slotsLeft).append(" / ").append(slotsTotal);
		return sb.toString();
	}

	static String formatHours(double hours)
	{
		if (Double.isInfinite(hours) || Double.isNaN(hours))
		{
			return "—";
		}
		double totalSeconds = hours * 3600.0;
		if (totalSeconds < 90)
		{
			return String.format("%.0fs", totalSeconds);
		}
		if (totalSeconds < 3600)
		{
			return String.format("%.0fm", totalSeconds / 60.0);
		}
		long h = (long) hours;
		long m = (long) ((hours - h) * 60.0);
		if (h < 24)
		{
			return m == 0 ? h + "h" : h + "h " + m + "m";
		}
		long d = h / 24;
		long hr = h % 24;
		return hr == 0 ? d + "d" : d + "d " + hr + "h";
	}

}
