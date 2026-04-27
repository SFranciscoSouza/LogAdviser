package com.logadviser.data;

import lombok.Value;

@Value
public class Activity
{
	int index;
	String name;
	double completionsPerHrMain;
	double completionsPerHrIron;
	double extraTimeFirst;
	Category category;
}
