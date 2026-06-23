package com.logadviser.engine;

import com.logadviser.data.Activity;
import com.logadviser.data.ActivityItem;
import lombok.Value;

@Value
public class RankedActivity
{
	Activity activity;
	double timeToNextSlotHours;
	// The slot the time estimate is for — the next slot you're most likely to obtain (the one
	// driving timeToNextSlotHours). The UI headlines this so the icon/name always match the time.
	ActivityItem displayItem;
	ActivityItem fastestItem;
	int slotsLeft;
	int slotsTotal;
	boolean locked;
	String requirementLabel;

	public double percentComplete()
	{
		return slotsTotal == 0 ? 0.0 : 1.0 - (double) slotsLeft / slotsTotal;
	}
}
