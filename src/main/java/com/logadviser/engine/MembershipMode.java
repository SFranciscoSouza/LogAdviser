package com.logadviser.engine;

/**
 * The "Membership" filter, a sibling of {@link AccountMode}. Unlike account mode there is no
 * live AUTO state: on the first login for a profile the plugin seeds the value from the world
 * type ({@code WorldType.MEMBERS}) and persists a concrete F2P/P2P choice, after which the saved
 * value always wins. {@code P2P} applies no filter (a member can collect everything); {@code F2P}
 * keeps only the free-to-play obtainable slots (see f2p_slots.json).
 */
public enum MembershipMode
{
	F2P,
	P2P;

	/**
	 * Parses the saved override string. Returns {@code null} when nothing is saved (or the value
	 * is unrecognised) so the caller knows to seed the default from the player's world type.
	 */
	public static MembershipMode parse(String s)
	{
		if (s == null)
		{
			return null;
		}
		try
		{
			return valueOf(s.toUpperCase());
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}
}
