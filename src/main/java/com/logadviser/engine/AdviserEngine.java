package com.logadviser.engine;

import com.logadviser.data.Activity;
import com.logadviser.data.ActivityItem;
import com.logadviser.data.Category;
import com.logadviser.data.StaticData;
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

	private final Map<Integer, Double> cachedTime = new HashMap<>();
	private final Map<Integer, ActivityItem> cachedFastest = new HashMap<>();
	private final Map<Integer, ActivityItem> cachedEasiest = new HashMap<>();
	private final Map<Integer, int[]> cachedSlotCounts = new HashMap<>();

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
		obtained.addAll(ids);
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

	public void markObtained(int itemId)
	{
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
			out.add(new RankedActivity(
				a,
				t,
				cachedEasiest.get(a.getIndex()),
				cachedFastest.get(a.getIndex()),
				slots[0],
				slots[1]));
		}
		out.sort((x, y) -> Double.compare(x.getTimeToNextSlotHours(), y.getTimeToNextSlotHours()));
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
