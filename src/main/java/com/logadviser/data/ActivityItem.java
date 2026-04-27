package com.logadviser.data;

import lombok.Value;

@Value
public class ActivityItem
{
	int activityIndex;
	int itemId;
	String itemName;
	int slotDifficulty;
	boolean requiresPrevious;
	boolean exact;
	boolean independent;
	double dropRateAttempts;
}
