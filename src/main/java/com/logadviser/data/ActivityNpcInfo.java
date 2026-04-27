package com.logadviser.data;

import java.util.Collections;
import java.util.List;
import lombok.Value;

@Value
public class ActivityNpcInfo
{
	List<Integer> npcIds;
	String hint;

	public static ActivityNpcInfo empty()
	{
		return new ActivityNpcInfo(Collections.emptyList(), "");
	}
}
