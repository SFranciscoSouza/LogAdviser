---
name: bump-logadviser-version
description: Use when deciding or setting the LogAdviser plugin version number before a Plugin Hub release. Encodes the project's versioning rule (MAJOR fixed at 1, MINOR only for new collection log slots, PATCH for everything else), where the version lives (build.gradle), and why it matters (an unset version makes the Hub display the raw commit hash).
user_invocable: true
---

# Bump the LogAdviser plugin version

The version shown on the RuneLite Plugin Hub comes from `project.version` in
`build.gradle`. If it is **unset**, the packager falls back to the short git commit hash
(e.g. `ceb8868`) instead of a real version — which is wrong and confusing. So every
release must carry an explicit, correctly-incremented `version`.

## Where the version lives

`C:\Users\Chico\source\repos\SFranciscoSouza\LogAdviser\build.gradle`, right next to the
`group` declaration:

```gradle
group = 'com.logadviser'
version = '1.1.1'
```

That is the **only** place the version is set. There is no `gradle.properties` version
and nothing in `runelite-plugin.properties`.

## The versioning rule (MAJOR.MINOR.PATCH)

Read the three numbers left-to-right:

1. **MAJOR (first number)** — **always `1`** unless the user explicitly says to change it.
   Do not bump this on your own.
2. **MINOR (second number)** — increment **only when a new collection log slot/item was
   added** in this release (i.e. a change that went through [[add-logadviser-slot]] and
   touched `slots.json` + the row-count asserts). When you bump MINOR, **reset PATCH to 0**.
3. **PATCH (third number)** — increment for **any other small change** where **no new
   collection log slot was added**: config defaults, ranking/math tweaks, bug fixes,
   schematic/activity edits, refactors, etc.

### How to compute the next version

- Start from the current `version` in `build.gradle`.
- Did this release add a new collection log slot?
  - **Yes** → bump MINOR, reset PATCH to 0. `1.1.1` → `1.2.0`.
  - **No**  → bump PATCH. `1.1.1` → `1.1.2`.
- Only touch MAJOR if the user explicitly asks. (`1.x.x` → `2.0.0`.)

Worked examples (current = `1.1.1`):

| This release contains… | Next version |
|---|---|
| Added a new collection log slot (slots.json) | `1.2.0` |
| Added two new slots in one release | `1.2.0` (one MINOR bump, not two) |
| Changed a config default (e.g. infobox default) | `1.1.2` |
| Fixed ranking math / skip-list / requirements | `1.1.2` |
| Both a new slot **and** a config tweak | `1.2.0` (a new slot was added → MINOR wins) |

**MINOR wins over PATCH:** if a release contains *both* a new slot and other small
changes, it's a MINOR bump. PATCH is only for releases with **no** new slot.

## Steps

1. Determine whether this release added a collection log slot — check the commits since the
   last release for changes to `src/main/resources/.../slots.json` and bumped row-count
   asserts in `LogAdviserPluginTest.java`. When unsure, ask the user "did this release add a
   new collection log slot?" before choosing MINOR vs PATCH.
2. Read the current `version` from `build.gradle`.
3. Apply the rule above to get the new version.
4. Edit the `version = '...'` line in `build.gradle`.
5. `.\gradlew build` to confirm it still compiles.
6. Commit to `master` (owner pushes directly — no PR in our own repo) and push.
7. Hand off to [[release-logadviser-to-plugin-hub]] to pin the new commit and open the
   Plugin Hub PR. Reference the new version in the PR title/body, e.g.
   `log-adviser: bump to <short-sha> (v1.2.0)`.

## Common mistakes

- **Leaving `version` unset** → the Hub shows the commit hash instead of a version.
- **Bumping MINOR for a change that added no slot** → PATCH-only changes must keep MINOR
  fixed (`1.1.1` → `1.1.2`, not `1.2.0`).
- **Bumping MINOR twice for two slots in one release** → it's one MINOR bump per release.
- **Forgetting to reset PATCH to 0 on a MINOR bump** → `1.1.1` + new slot is `1.2.0`, not
  `1.2.1`.
- **Touching MAJOR without being told** → it stays `1`.

## Related

- [[release-logadviser-to-plugin-hub]] — the release runbook that consumes this version
- [[add-logadviser-slot]] — adding a slot is what justifies a MINOR bump
- [[edit-logadviser-data]] — data/math changes are PATCH bumps
