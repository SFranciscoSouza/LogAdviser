---
name: edit-logadviser-data
description: Use before changing any LogAdviser ranking DATA or MATH — activities, the activity→item mapping, slot definitions, quest/skill requirements, the skip list, account-type (iron vs main) rates, or the time-to-next-slot calculation in AdviserEngine. Explains the full data model and the four-bucket probability math so changes don't silently break the ranking. For adding a single new slot, use add-logadviser-slot instead.
user_invocable: true
---

# Edit LogAdviser ranking data & math

LogAdviser ranks collection-log activities by **time to the next log slot**. That ranking is the
product of a few JSON data files and one calculation engine, and the math is subtle: four
probability buckets, iron-vs-main rates, requirement gating, and an iron-only auto-complete
exclusion. Read this before touching any of it so a change doesn't silently corrupt the ranking.

## Repo

`C:\Users\Chico\source\repos\SFranciscoSouza\LogAdviser` — `master` is shippable; owner pushes
directly. Data: `src/main/resources/com/logadviser/data/*.json`. Models + engine:
`src/main/java/com/logadviser/{data,engine}/`.

## When to use this vs add-logadviser-slot

- **This skill** — understanding the model and making *broader* changes: a new activity, rate
  tuning, requirement edits, mapping/flag changes, or touching the engine math itself.
- **[[add-logadviser-slot]]** — the step-by-step checklist for adding **one** collection-log
  item/slot. If that's the whole task, use it directly; it already covers the per-slot edits and
  the test-assert bumps.

## The data files

All under `src/main/resources/com/logadviser/data/`. Row counts are asserted in
`src/test/java/com/logadviser/LogAdviserPluginTest.java` (~lines 35-40) — **bump those asserts
whenever a row count changes**, or `.\gradlew run` (`-ea`) crashes before the panel opens.

| File | Model (`data/*.java`) | Key fields |
|---|---|---|
| `activities.json` (251 rows) | `Activity` | `index, name, completionsPerHrMain, completionsPerHrIron, extraTimeFirst, category` |
| `activity_map.json` (2499 rows) | `ActivityItem` | `activityIndex, itemId, itemName, slotDifficulty, requiresPrevious, exact, independent, dropRateAttempts` |
| `slots.json` (1701 rows) | `LogSlot` | `itemId, slotName, activityCount` |
| `activity_requirements.json` | `ActivityRequirements` | per-activity `{ "skills": {SKILL: lvl}, "quests": [...] }` (all AND) |
| `activity_npcs.json` | — | per-activity NPC info (highlight/hints) |

Loader: `StaticDataLoader.java`. Indexed access: `StaticData.java`
(`activitiesByIndex`, `slotsByItemId`, `itemIdsByName`). Item-id aliasing:
`ItemAliases.java` + `StaticData.canonicalItemId`. Quest-token resolution: `QuestResolver.java`.

## How the pieces relate

An `activity_map` row ties an `activityIndex` (→ `activities.json` for the rates) to an `itemId`
(→ `slots.json` for the display name and obtained-tracking). The engine groups all activity_map
rows by activity (`itemsByActivity`) and computes one time per activity. `slots.json` is the
canonical slot catalog: it drives obtained-event matching — the clog chat string
*"New item added to your collection log: X"* is matched case-insensitively via `itemIdsByName` —
and the panel's total/collected counts.

## The math (AdviserEngine.recomputeActivity)

The whole calculation lives in `src/main/java/com/logadviser/engine/AdviserEngine.java`
(`recomputeActivity`). It mirrors the source spreadsheet's "Time to next log slot" column.

**1. Base conversion.** For a single drop:
```
time_hours = dropRateAttempts / cph + extraTimeFirst
```
where `cph = completionsPerHrIron` if `isUsingIronRates()` else `completionsPerHrMain`. If
`cph <= 0` the activity is not advisable → time `+∞` (filtered out of the ranking).

**2. Active filter.** An item is "active" iff:
```
!obtained && (!requiresPrevious || previousRow.obtained)
```
`requiresPrevious` chains to the **immediately preceding row in file order** — so row order in
`activity_map.json` is semantically meaningful for chained items; don't reorder casually.

**3. Four buckets** by the `(exact, independent)` flags. Each active item with `k =
dropRateAttempts > 0` contributes to exactly one bucket; each bucket yields a candidate time and
the activity's time is the **minimum of the four**:

| Bucket | Condition | Candidate time |
|---|---|---|
| Neither | `!exact && !independent` | `(1 / Σ(1/k)) / cph + extra` — harmonic combine (these drop together, so the combined rate adds) |
| Independent only | `!exact && independent` | `min(k) / cph + extra` |
| Exact only | `exact && !independent` | `min(k) / cph + extra` |
| Exact & Independent | `exact && independent` | `min(k) / cph + extra` |

`extra = activity.extraTimeFirst`. The only bucket that combines items is **Neither** (sum of
reciprocals); the other three take the single fastest item.

**4. UI extras.** `fastest` = min `dropRateAttempts`; `easiest` = min `slotDifficulty`
(tie-broken by `k`). Both are surfaced on `RankedActivity`. `slotCounts` = `{slotsLeft,
slotsTotal}` over unique itemIds (after the iron filter below).

**5. Ranking order** (`getRanking`): unlocked activities first, sorted ascending by
time-to-slot; locked activities (unmet requirements) demoted below all unlocked, themselves
still ordered by time so the nearest unlock floats up. Skipped and category-filtered activities
are excluded.

## Account type (iron vs main)

`AccountMode` is MAIN / IRONMAN / AUTO; AUTO uses the detected ironman varbit
(`isUsingIronRates()`). On iron rates, `visibleItems()` drops every itemId in
`IRON_AUTO_COMPLETED` (currently just `32110`, Merchant's paint) so those slots neither show in
the ranking nor count toward the activity total. **Consequence:** for such an activity the iron
denominator is 1 lower than main — a frequent "the count is wrong" red herring; check the
account mode before assuming the data is broken.

This is distinct from slots auto-completed for **every** account type (e.g. Champion's cape,
Barronite mace), which simply have **no** `activity_map` row and need no code. Toggling
account-type behaviour calls `refreshRates()` to recompute.

## Requirements & skip list

`activity_requirements.json` is **per-activity, not per-item** — every listed skill level and
quest must be met (AND) for the *whole* activity to unlock. Gating is computed in
`unmetRequirementLabel()`; `ignoreRequirements` unlocks everything. Quest tokens resolve via
`QuestResolver` (tolerant of enum or display name; an unknown token means that one requirement is
silently ignored). The skip list (`skip` / `unskip` / `unskipAll` / `getSkippedRanking`) hides an
activity from the main ranking but keeps it recoverable.

**Per-item requirements are unsupported.** If asked for them, push back — either propose an
activity-wide gate (and confirm it's correct for every item in the activity) or propose extending
the model (`ActivityItem` + loader + engine). Don't silently dump a per-activity gate that
over-gates the activity's other items.

## Making a change safely (by change type)

- **Tune a rate** → edit `completionsPerHrMain` / `completionsPerHrIron` in `activities.json`. No
  row-count asserts affected.
- **New activity** → add to `activities.json` + its `activity_map` rows + any `slots.json` rows,
  then **bump the test asserts**; optionally `activity_requirements.json` / `activity_npcs.json`.
- **Add / remove a single slot or item** → use [[add-logadviser-slot]].
- **Change drop math / flags** → first identify which bucket a row falls in; flipping
  `exact` / `independent` / `requiresPrevious` changes the formula and can change the activity's
  min-time. Re-reason it before committing.
- **Requirements** → per-activity only; verify it's correct for *every* item in the activity.
- **Iron auto-complete a slot** → add the itemId to `IRON_AUTO_COMPLETED` in `AdviserEngine.java`
  (convert the `Collections.singleton(...)` to a real `Set` for a 2nd entry) and update the
  comment block above it.

## Verification

```powershell
# 1. JSON parses + counts match the asserts
python -c "import json; am=json.load(open('src/main/resources/com/logadviser/data/activity_map.json',encoding='utf-8')); sl=json.load(open('src/main/resources/com/logadviser/data/slots.json',encoding='utf-8')); ac=json.load(open('src/main/resources/com/logadviser/data/activities.json',encoding='utf-8')); print('map',len(am),'slots',len(sl),'activities',len(ac))"

# 2. Loader + engine logic tests
.\gradlew test   # LogAdviserPluginTest, ActivityRequirementsTest, SkipRankingTest, EngineSanityCheck

# 3. Dev launch — -ea fires the row-count asserts (~LogAdviserPluginTest.java:35-40)
.\gradlew run
```

Then spot-check in-game: the affected activity card's `X / Y` count, top-rank sanity, and the
iron-vs-main denominator difference if the activity has an `IRON_AUTO_COMPLETED` member.

## When to push back

- **Per-item requirement requests** — the architecture is per-activity; don't over-gate.
- **`dropRateAttempts` given in a non-attempts unit** (e.g. "10 minutes") — it's *attempts*;
  convert via `time = dropRateAttempts / cph` and confirm the intended time.
- **"The count is wrong" reports** — first check whether the player is on iron mode and the
  activity has an `IRON_AUTO_COMPLETED` member; the denominator differs by 1 by design.

## Related

- [[add-logadviser-slot]] — single-slot add checklist
- [[release-logadviser-to-plugin-hub]] — shipping the change to the Plugin Hub
