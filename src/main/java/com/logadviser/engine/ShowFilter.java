package com.logadviser.engine;

/**
 * The selectable options in the sidebar "Show" filter. The first three mirror
 * {@link com.logadviser.data.Category}; SLAYER is a cross-cutting attribute (any activity
 * with a Slayer requirement, minus boat-combat bounty tasks — see {@code AdviserEngine}).
 * Multiple options can be ticked at once; the engine treats them as a union (an activity is
 * shown if it matches any ticked option). "Pets Only" is NOT here — it is a separate,
 * exclusive mode handled by {@code AdviserEngine#setPetsOnly}.
 */
public enum ShowFilter
{
	COMBAT("Combat"),
	MINIGAME("Minigame"),
	MISCELLANEOUS("Miscellaneous"),
	SLAYER("Slayer");

	private final String label;

	ShowFilter(String label)
	{
		this.label = label;
	}

	public String displayName()
	{
		return label;
	}
}
