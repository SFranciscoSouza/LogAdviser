package com.logadviser.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Some collection log slots can be filled by more than one item id. A character's
 * body type (A / B) decides which physical item the Tithe Farm "Farmer's" outfit
 * pieces are awarded as, but the in-game collection log treats each piece as a
 * single slot. slots.json only carries the Body-type-A (even) id for each piece, so
 * a Body-type-B player's items (the odd ids) would otherwise never match.
 *
 * This maps every alternate id back to the canonical id that lives in slots.json,
 * and also maps the chat-message names that the canonical slotName never matches
 * verbatim (only "Farmer's shirt/jacket", whose slash form never appears in chat).
 *
 * Adding a future variant pair is a single line in each map below.
 */
public final class ItemAliases
{
	// Body-type-B item id -> canonical Body-type-A item id (the one in slots.json).
	private static final Map<Integer, Integer> CANONICAL_BY_ALT;
	// Lowercase collection-log chat name -> canonical item id. Only needed where the
	// slots.json slotName never appears verbatim in the chat notification.
	private static final Map<String, Integer> EXTRA_NAMES;

	static
	{
		Map<Integer, Integer> alt = new HashMap<>();
		alt.put(13641, 13640); // Farmer's boro trousers (Body type B -> A)
		alt.put(13643, 13642); // Farmer's shirt/jacket   (Body type B -> A)
		alt.put(13645, 13644); // Farmer's boots          (Body type B -> A)
		alt.put(13647, 13646); // Farmer's strawhat       (Body type B -> A)
		CANONICAL_BY_ALT = Collections.unmodifiableMap(alt);

		Map<String, Integer> names = new HashMap<>();
		// slots.json names this slot "Farmer's shirt/jacket"; the chat message says
		// either "Farmer's jacket" or "Farmer's shirt", neither of which matches.
		names.put("farmer's jacket", 13642);
		names.put("farmer's shirt", 13642);
		EXTRA_NAMES = Collections.unmodifiableMap(names);
	}

	private ItemAliases()
	{
	}

	/** Returns the canonical (slots.json) item id for an item, or the id unchanged. */
	public static int canonical(int itemId)
	{
		return CANONICAL_BY_ALT.getOrDefault(itemId, itemId);
	}

	public static Map<Integer, Integer> canonicalByAlt()
	{
		return CANONICAL_BY_ALT;
	}

	public static Map<String, Integer> extraNames()
	{
		return EXTRA_NAMES;
	}
}
