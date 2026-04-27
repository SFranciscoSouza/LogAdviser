package com.logadviser.data;

public enum Category
{
	COMBAT,
	MINIGAME,
	MISCELLANEOUS;

	public static Category fromString(String s)
	{
		if (s == null)
		{
			return MISCELLANEOUS;
		}
		switch (s.toLowerCase())
		{
			case "combat":
				return COMBAT;
			case "minigame":
				return MINIGAME;
			default:
				return MISCELLANEOUS;
		}
	}

	public String displayName()
	{
		switch (this)
		{
			case COMBAT:
				return "Combat";
			case MINIGAME:
				return "Minigame";
			default:
				return "Miscellaneous";
		}
	}
}
