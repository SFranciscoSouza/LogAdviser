package com.logadviser.data;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;

/**
 * Resolves a free-form quest token from the requirements JSON to a RuneLite
 * {@link Quest}. Tolerant of either the enum constant name ("LUNAR_DIPLOMACY") or
 * the display name ("Lunar Diplomacy", "lunar diplomacy") — anything that
 * normalises to the same alphanumeric run matches. Unknown tokens resolve to
 * {@code null} so a typo drops that single requirement instead of hard-locking.
 */
@Slf4j
public final class QuestResolver
{
	private static final Map<String, Quest> BY_NORMALISED = new HashMap<>();

	static
	{
		for (Quest q : Quest.values())
		{
			BY_NORMALISED.putIfAbsent(normalise(q.name()), q);
			BY_NORMALISED.putIfAbsent(normalise(q.getName()), q);
		}
	}

	private QuestResolver()
	{
	}

	public static Quest resolve(String token)
	{
		if (token == null)
		{
			return null;
		}
		Quest q = BY_NORMALISED.get(normalise(token));
		if (q == null)
		{
			log.warn("Unknown quest requirement '{}' — ignoring it", token);
		}
		return q;
	}

	private static String normalise(String s)
	{
		return s == null ? "" : s.toUpperCase().replaceAll("[^A-Z0-9]", "");
	}
}
