package com.logadviser.engine;

public enum AccountMode
{
	AUTO,
	MAIN,
	IRONMAN;

	public static AccountMode parse(String s)
	{
		if (s == null)
		{
			return AUTO;
		}
		try
		{
			return valueOf(s.toUpperCase());
		}
		catch (IllegalArgumentException ex)
		{
			return AUTO;
		}
	}
}
