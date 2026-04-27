package com.logadviser.engine;

import com.logadviser.data.Activity;
import com.logadviser.data.ActivityItem;
import lombok.Value;

@Value
public class RankedActivity
{
	Activity activity;
	double timeToNextSlotHours;
	ActivityItem easiestItem;
	ActivityItem fastestItem;
	int slotsLeft;
	int slotsTotal;

	public double percentComplete()
	{
		return slotsTotal == 0 ? 0.0 : 1.0 - (double) slotsLeft / slotsTotal;
	}
}
