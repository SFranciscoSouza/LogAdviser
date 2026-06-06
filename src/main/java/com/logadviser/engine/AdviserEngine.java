package com.logadviser.engine;

import com.logadviser.data.Activity;
import com.logadviser.data.ActivityItem;
import com.logadviser.data.ActivityRequirements;
import com.logadviser.data.Category;
import com.logadviser.data.StaticData;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Mirrors the spreadsheet's "List of activities" → "Time to next log slot" column. For
 * each activity we group still-active items into the four rate-mode buckets the sheet
 * uses (Neither / Independent / Exact / E&I) and compute the minimum expected hours to
 * obtain the next slot. Public API is thread-confined to the calling thread (the plugin
 * runs everything on the client thread); listeners are fanned out synchronously on the
 * same thread. Listeners that touch Swing must marshal to the EDT themselves.
 */
@Slf4j
public class AdviserEngine
{
	private final StaticData data;
	private final BooleanSupplier detectedIronman;
	private final Map<Integer, List<ActivityItem>> itemsByActivity;
	private final Map<Integer, Activity> activitiesByIndex;

	private final Set<Integer> obtained = new HashSet<>();
	private final Set<Integer> skipped = new HashSet<>();
	private EnumSet<Category> categoryFilter = EnumSet.allOf(Category.class);
	private AccountMode accountMode = AccountMode.AUTO;
	private boolean ignoreRequirements = false;
	private PlayerProgress playerProgress = PlayerProgress.EMPTY;

	private final Map<Integer, Double> cachedTime = new HashMap<>();
	private final Map<Integer, ActivityItem> cachedFastest = new HashMap<>();
	private final Map<Integer, ActivityItem> cachedEasiest = new HashMap<>();
	private final Map<Integer, int[]> cachedSlotCounts = new HashMap<>();

	/**
	 * Slots that ONLY an ironman obtains automatically as a by-product of other logs,
	 * so they must not be advised on iron accounts but a main account still has to
	 * chase normally. The activity_map row is therefore kept (mains see it unchanged)
	 * and filtered out here only when iron rates are in effect.
	 *
	 * This is distinct from slots like Champion's cape / Barronite mace, which are
	 * auto-completed for EVERY account type — those simply have no activity_map row
	 * and need no code; do not add them here.
	 */
	private static final Set<Integer> IRON_AUTO_COMPLETED = Collections.singleton(32110); // Merchant's paint

	private final List<Consumer<List<RankedActivity>>> listeners = new CopyOnWriteArrayList<>();

	public AdviserEngine(StaticData data, BooleanSupplier detectedIronman)
	{
		this.data = data;
		this.detectedIronman = detectedIronman;
		this.activitiesByIndex = data.activitiesByIndex();

		Map<Integer, List<ActivityItem>> grouped = new LinkedHashMap<>();
		for (ActivityItem item : data.getActivityItems())
		{
			grouped.computeIfAbsent(item.getActivityIndex(), k -> new ArrayList<>()).add(item);
		}
		Map<Integer, List<ActivityItem>> immutable = new HashMap<>();
		for (Map.Entry<Integer, List<ActivityItem>> e : grouped.entrySet())
		{
			immutable.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
		}
		this.itemsByActivity = immutable;

		recomputeAll();
	}

	public void addListener(Consumer<List<RankedActivity>> l)
	{
		listeners.add(l);
	}

	public void replaceObtained(Set<Integer> ids)
	{
		obtained.clear();
		for (int id : ids)
		{
			obtained.add(data.canonicalItemId(id));
		}
		recomputeAll();
		fire();
	}

	public boolean isObtained(int itemId)
	{
		return obtained.contains(itemId);
	}

	public Set<Integer> obtainedItems()
	{
		return Collections.unmodifiableSet(obtained);
	}

	/**
	 * The visible (iron-aware) item list for one activity, mirroring exactly what the
	 * ranking evaluated — so a hover preview shows the same slots as the ranked counts.
	 * Empty for unknown indices or activities whose every slot is iron-auto-completed.
	 * May contain duplicate itemIds; callers that want unique slots must dedup.
	 */
	public List<ActivityItem> visibleItemsForActivity(int activityIndex)
	{
		List<ActivityItem> items = itemsByActivity.get(activityIndex);
		if (items == null || items.isEmpty())
		{
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(visibleItems(items));
	}

	public void markObtained(int itemId)
	{
		itemId = data.canonicalItemId(itemId);
		if (!obtained.add(itemId))
		{
			return;
		}
		// Recompute only activities that contained this item.
		boolean changed = false;
		for (List<ActivityItem> items : itemsByActivity.values())
		{
			for (ActivityItem ai : items)
			{
				if (ai.getItemId() == itemId)
				{
					recomputeActivity(ai.getActivityIndex());
					changed = true;
					break;
				}
			}
		}
		if (changed)
		{
			fire();
		}
	}

	public void skip(int activityIndex)
	{
		if (skipped.add(activityIndex))
		{
			fire();
		}
	}

	public void unskip(int activityIndex)
	{
		if (skipped.remove(activityIndex))
		{
			fire();
		}
	}

	public void unskipAll()
	{
		if (!skipped.isEmpty())
		{
			skipped.clear();
			fire();
		}
	}

	public Set<Integer> skippedActivities()
	{
		return Collections.unmodifiableSet(skipped);
	}

	public void replaceSkipped(Set<Integer> ids)
	{
		skipped.clear();
		skipped.addAll(ids);
		fire();
	}

	public void setCategoryFilter(EnumSet<Category> filter)
	{
		this.categoryFilter = filter.isEmpty() ? EnumSet.allOf(Category.class) : EnumSet.copyOf(filter);
		fire();
	}

	public EnumSet<Category> getCategoryFilter()
	{
		return EnumSet.copyOf(categoryFilter);
	}

	public void setAccountMode(AccountMode mode)
	{
		AccountMode m = mode == null ? AccountMode.AUTO : mode;
		if (m == this.accountMode)
		{
			return;
		}
		this.accountMode = m;
		recomputeAll();
		fire();
	}

	public AccountMode getAccountMode()
	{
		return accountMode;
	}

	public void setIgnoreRequirements(boolean ignore)
	{
		if (ignore == this.ignoreRequirements)
		{
			return;
		}
		this.ignoreRequirements = ignore;
		fire();
	}

	public boolean isIgnoreRequirements()
	{
		return ignoreRequirements;
	}

	/** Pushed from the plugin on the client thread. No-op (no listener fan-out) when
	 *  the snapshot is unchanged, so a quiet GameTick poll costs nothing downstream. */
	public void setPlayerProgress(PlayerProgress progress)
	{
		PlayerProgress next = progress == null ? PlayerProgress.EMPTY : progress;
		if (next.equals(this.playerProgress))
		{
			return;
		}
		this.playerProgress = next;
		fire();
	}

	/** null label = requirements met. Otherwise label lists only the unmet parts. */
	private String unmetRequirementLabel(int activityIndex)
	{
		if (ignoreRequirements)
		{
			return null;
		}
		ActivityRequirements req = data.requirementsFor(activityIndex);
		if (req.isEmpty())
		{
			return null;
		}
		List<String> missing = new ArrayList<>();
		for (Map.Entry<Skill, Integer> e : req.getSkillLevels().entrySet())
		{
			if (playerProgress.level(e.getKey()) < e.getValue())
			{
				missing.add(e.getValue() + " " + e.getKey().getName());
			}
		}
		List<Quest> quests = req.getQuests();
		for (int i = 0; i < quests.size(); i++)
		{
			if (!playerProgress.isFinished(quests.get(i)))
			{
				missing.add(quests.get(i).getName());
			}
		}
		return missing.isEmpty() ? null : String.join(", ", missing);
	}

	/** Recompute everything and fire — call after the underlying iron/main signal changes
	 *  (e.g. account-type varbit settled after login). Must be invoked on the client thread. */
	public void refreshRates()
	{
		recomputeAll();
		fire();
	}

	public boolean isUsingIronRates()
	{
		switch (accountMode)
		{
			case MAIN:
				return false;
			case IRONMAN:
				return true;
			case AUTO:
			default:
				return detectedIronman.getAsBoolean();
		}
	}

	public List<RankedActivity> getRanking()
	{
		List<RankedActivity> out = new ArrayList<>();
		for (Activity a : data.getActivities())
		{
			if (!categoryFilter.contains(a.getCategory()))
			{
				continue;
			}
			if (skipped.contains(a.getIndex()))
			{
				continue;
			}
			Double t = cachedTime.get(a.getIndex());
			if (t == null || Double.isInfinite(t) || Double.isNaN(t))
			{
				continue;
			}
			int[] slots = cachedSlotCounts.getOrDefault(a.getIndex(), new int[]{0, 0});
			String missing = unmetRequirementLabel(a.getIndex());
			out.add(new RankedActivity(
				a,
				t,
				cachedEasiest.get(a.getIndex()),
				cachedFastest.get(a.getIndex()),
				slots[0],
				slots[1],
				missing != null,
				missing));
		}
		// Unlocked first (by time-to-slot), then locked activities demoted to the
		// bottom — themselves still ordered by time so the closest unlock floats up.
		out.sort((x, y) ->
		{
			if (x.isLocked() != y.isLocked())
			{
				return x.isLocked() ? 1 : -1;
			}
			return Double.compare(x.getTimeToNextSlotHours(), y.getTimeToNextSlotHours());
		});
		return out;
	}

	/** The skipped activities, ranked like {@link #getRanking()} but containing
	 *  only what the player has skipped. Ignores the category filter so a
	 *  filtered-out skip is still visible (and recoverable) in the skip list. */
	public List<RankedActivity> getSkippedRanking()
	{
		List<RankedActivity> out = new ArrayList<>();
		for (Activity a : data.getActivities())
		{
			if (!skipped.contains(a.getIndex()))
			{
				continue;
			}
			Double t = cachedTime.get(a.getIndex());
			if (t == null || Double.isInfinite(t) || Double.isNaN(t))
			{
				continue;
			}
			int[] slots = cachedSlotCounts.getOrDefault(a.getIndex(), new int[]{0, 0});
			String missing = unmetRequirementLabel(a.getIndex());
			out.add(new RankedActivity(
				a,
				t,
				cachedEasiest.get(a.getIndex()),
				cachedFastest.get(a.getIndex()),
				slots[0],
				slots[1],
				missing != null,
				missing));
		}
		out.sort((x, y) ->
		{
			if (x.isLocked() != y.isLocked())
			{
				return x.isLocked() ? 1 : -1;
			}
			return Double.compare(x.getTimeToNextSlotHours(), y.getTimeToNextSlotHours());
		});
		return out;
	}

	public int totalSlots()
	{
		return data.getSlots().size();
	}

	public int collectedSlotCount()
	{
		// Only count item IDs that map to a real log slot (not e.g. duplicates).
		Set<Integer> known = data.slotsByItemId().keySet();
		int n = 0;
		for (int id : obtained)
		{
			if (known.contains(id))
			{
				n++;
			}
		}
		return n;
	}

	private void recomputeAll()
	{
		cachedTime.clear();
		cachedFastest.clear();
		cachedEasiest.clear();
		cachedSlotCounts.clear();
		for (Integer idx : itemsByActivity.keySet())
		{
			recomputeActivity(idx);
		}
	}

	private void recomputeActivity(int activityIndex)
	{
		Activity activity = activitiesByIndex.get(activityIndex);
		List<ActivityItem> items = itemsByActivity.get(activityIndex);
		if (activity == null || items == null || items.isEmpty())
		{
			cachedTime.put(activityIndex, Double.POSITIVE_INFINITY);
			return;
		}
		items = visibleItems(items);
		if (items.isEmpty())
		{
			// Every item this activity drops is iron-auto-completed: treat the
			// activity as having nothing to chase, mirroring an absent activity_map row.
			cachedTime.put(activityIndex, Double.POSITIVE_INFINITY);
			cachedSlotCounts.put(activityIndex, new int[]{0, 0});
			return;
		}
		double cph = isUsingIronRates() ? activity.getCompletionsPerHrIron() : activity.getCompletionsPerHrMain();
		if (cph <= 0.0)
		{
			cachedTime.put(activityIndex, Double.POSITIVE_INFINITY);
			cachedSlotCounts.put(activityIndex, slotCounts(items));
			return;
		}

		// Active flag mirrors the xlsx: !Completed AND (!RequiresPrevious OR prevRow.Completed)
		boolean[] active = new boolean[items.size()];
		boolean prevCompleted = false;
		for (int i = 0; i < items.size(); i++)
		{
			ActivityItem it = items.get(i);
			boolean done = obtained.contains(it.getItemId());
			boolean requiresPrev = it.isRequiresPrevious();
			if (done)
			{
				active[i] = false;
			}
			else if (!requiresPrev)
			{
				active[i] = true;
			}
			else
			{
				active[i] = prevCompleted;
			}
			prevCompleted = done;
		}

		// Bucket active items by (exact, independent).
		double sumInverseNeither = 0.0;
		double minK_indOnly = Double.POSITIVE_INFINITY;
		double minK_exactOnly = Double.POSITIVE_INFINITY;
		double minK_ei = Double.POSITIVE_INFINITY;

		ActivityItem fastest = null;
		double fastestK = Double.POSITIVE_INFINITY;
		ActivityItem easiest = null;
		int easiestDifficulty = Integer.MAX_VALUE;
		double easiestKTie = Double.POSITIVE_INFINITY;

		for (int i = 0; i < items.size(); i++)
		{
			if (!active[i])
			{
				continue;
			}
			ActivityItem it = items.get(i);
			double k = it.getDropRateAttempts();
			if (k <= 0.0)
			{
				continue;
			}
			boolean ex = it.isExact();
			boolean in = it.isIndependent();
			if (!ex && !in)
			{
				sumInverseNeither += 1.0 / k;
			}
			else if (!ex && in)
			{
				if (k < minK_indOnly)
				{
					minK_indOnly = k;
				}
			}
			else if (ex && !in)
			{
				if (k < minK_exactOnly)
				{
					minK_exactOnly = k;
				}
			}
			else
			{
				if (k < minK_ei)
				{
					minK_ei = k;
				}
			}

			if (k < fastestK)
			{
				fastestK = k;
				fastest = it;
			}
			int diff = it.getSlotDifficulty();
			if (diff < easiestDifficulty || (diff == easiestDifficulty && k < easiestKTie))
			{
				easiestDifficulty = diff;
				easiestKTie = k;
				easiest = it;
			}
		}

		double timeNeither = sumInverseNeither > 0.0
			? (1.0 / sumInverseNeither) / cph + activity.getExtraTimeFirst()
			: Double.POSITIVE_INFINITY;
		double timeInd = Double.isFinite(minK_indOnly)
			? minK_indOnly / cph + activity.getExtraTimeFirst()
			: Double.POSITIVE_INFINITY;
		double timeExact = Double.isFinite(minK_exactOnly)
			? minK_exactOnly / cph + activity.getExtraTimeFirst()
			: Double.POSITIVE_INFINITY;
		double timeEI = Double.isFinite(minK_ei)
			? minK_ei / cph + activity.getExtraTimeFirst()
			: Double.POSITIVE_INFINITY;

		double best = Math.min(Math.min(timeNeither, timeInd), Math.min(timeExact, timeEI));
		cachedTime.put(activityIndex, best);
		if (fastest != null)
		{
			cachedFastest.put(activityIndex, fastest);
		}
		if (easiest != null)
		{
			cachedEasiest.put(activityIndex, easiest);
		}
		else
		{
			cachedEasiest.put(activityIndex, fastest);
		}
		cachedSlotCounts.put(activityIndex, slotCounts(items));
	}

	/**
	 * The items an activity should be evaluated against for the current account type.
	 * On iron rates this drops {@link #IRON_AUTO_COMPLETED} slots so they neither show
	 * up in the ranking nor count toward the activity's slot total; mains get the list
	 * untouched. Recomputed via {@link #refreshRates()} whenever the iron/main signal
	 * settles, so toggling account type re-includes/excludes these correctly.
	 */
	private List<ActivityItem> visibleItems(List<ActivityItem> items)
	{
		if (!isUsingIronRates())
		{
			return items;
		}
		List<ActivityItem> out = null;
		for (int i = 0; i < items.size(); i++)
		{
			ActivityItem it = items.get(i);
			if (IRON_AUTO_COMPLETED.contains(it.getItemId()))
			{
				if (out == null)
				{
					out = new ArrayList<>(items.subList(0, i));
				}
			}
			else if (out != null)
			{
				out.add(it);
			}
		}
		return out == null ? items : out;
	}

	private int[] slotCounts(List<ActivityItem> items)
	{
		Set<Integer> uniq = new HashSet<>();
		Set<Integer> uniqDone = new HashSet<>();
		for (ActivityItem it : items)
		{
			uniq.add(it.getItemId());
			if (obtained.contains(it.getItemId()))
			{
				uniqDone.add(it.getItemId());
			}
		}
		int total = uniq.size();
		int left = total - uniqDone.size();
		return new int[]{left, total};
	}

	private void fire()
	{
		List<RankedActivity> snapshot = getRanking();
		for (Consumer<List<RankedActivity>> l : listeners)
		{
			try
			{
				l.accept(snapshot);
			}
			catch (RuntimeException ex)
			{
				log.warn("Listener threw", ex);
			}
		}
	}
}
