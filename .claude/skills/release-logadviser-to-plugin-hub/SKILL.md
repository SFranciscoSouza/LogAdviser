---
name: release-logadviser-to-plugin-hub
description: Use when publishing/shipping a production update of the LogAdviser RuneLite plugin to the RuneLite Plugin Hub. Covers the real update route (bump the pinned commit in runelite/plugin-hub, NOT a PR in our own repo), the always-stale fork sync, the packager's forbidden-API traps (new Gson()), and how to read the Hub CI checks (the ❌ is the human-review gate, not a failure).
user_invocable: true
---

# Release a LogAdviser update to the Plugin Hub

LogAdviser is already merged into the official RuneLite Plugin Hub. "Releasing" does **not**
mean opening a PR in our own repo — the Hub only reads a *pinned commit* of our repo from its
own manifest. This skill is the gotcha-aware runbook; skipping a step here is how past releases
broke (stale fork, packager rejecting `new Gson()`, or panicking at a non-blocking ❌).

## Repo

`C:\Users\Chico\source\repos\SFranciscoSouza\LogAdviser` — `master` is shippable and the owner
pushes directly (no PR in the own repo). The fork used for the Hub PR is
`SFranciscoSouza/plugin-hub` (a fork of `runelite/plugin-hub`).

## What actually updates the Hub

The Hub builds whatever commit is pinned in `runelite/plugin-hub:plugins/log-adviser`. That
manifest file holds:

- `repository=<https git URL of SFranciscoSouza/LogAdviser>` — leave unchanged.
- `commit=<exact 40-char SHA>` — **this is the only thing a normal update changes.**

So a release = land code on our `master`, then bump that `commit=` to the new `master` SHA via a
PR to `runelite/plugin-hub`. A PR (or even a tag/release) inside our own repo does **nothing**
for the Hub.

Identity facts (from `runelite-plugin.properties`): plugin class
`com.logadviser.LogAdviserPlugin`, `build=standard`, `displayName=Log Adviser`,
`author=SFranciscoSouza`.

## Pre-flight: make master shippable

1. Land the change on `master` of `SFranciscoSouza/LogAdviser`. Owner pushes directly — no PR.
   Don't add AI co-author trailers unless asked.
2. Green build + tests:
   ```powershell
   .\gradlew build
   .\gradlew test
   ```
3. `.\gradlew run` launches the dev client with `-ea`, which fires the row-count asserts in
   `src/test/java/com/logadviser/LogAdviserPluginTest.java`. A data change that forgot to bump
   those asserts dies here — fix it before shipping (see [[edit-logadviser-data]] /
   [[add-logadviser-slot]]).
4. Grab the SHA to pin: `git rev-parse HEAD` (full 40 chars — the manifest wants the full hash,
   not the short form).

## Forbidden-API check before you ship

The Hub packager (`net.runelite.pluginhub.packager.Plugin.assembleDisplayData`) **fails the
build** for terminally-deprecated APIs. The one we've hit:

- **`new Gson()` / `new GsonBuilder().create()`** → *"Do not create fresh Gson instances, always
  @Inject the client's Gson. You can customize it by calling .newBuilder() on it."*

Fix: `@Inject Gson gson;` in Guice-managed singletons; for static utilities, thread the `Gson`
through as a parameter from the `@Inject` site. The error names the offending class but not the
line. Test code under `src/test/` is **not** scanned, so `new Gson()` is fine there.

Guard before every release that includes code changes:

```
Grep "new Gson|GsonBuilder" over src/main/
```

Pure JSON-data updates almost never trip this; any `src/main` code change can.

## The release steps

1. **Push code to `SFranciscoSouza/LogAdviser` master** (done in pre-flight).
2. **Sync the fork — every single time.** `runelite/plugin-hub` is high-velocity and the fork
   goes stale fast; a stale base produces confusing diffs and conflicts.
   ```powershell
   gh repo sync SFranciscoSouza/plugin-hub --source runelite/plugin-hub
   ```
3. **Bump the pinned commit on a fork branch.** Set `commit=<new full master SHA>` in
   `plugins/log-adviser`, keeping `repository=` unchanged. This can be done without cloning the
   fork, via the contents API (needs the current file `sha`, the new base64 content, and a
   branch name):
   ```powershell
   gh api -X PUT repos/SFranciscoSouza/plugin-hub/contents/plugins/log-adviser `
     -f message="log-adviser: bump to <short-sha>" `
     -f branch="<branch>" `
     -f sha="<current-file-blob-sha>" `
     -f content="<base64 of new manifest>"
   ```
4. **Open the PR against the upstream:**
   ```powershell
   gh pr create --repo runelite/plugin-hub --head SFranciscoSouza:<branch> --base master `
     --title "log-adviser: bump to <short-sha>"
   ```

## Reading the Hub CI checks

A PR to `runelite/plugin-hub` shows three checks — read them precisely:

- **`build`** (`.github/workflows/build.yml`) — the real one. ❌ here means the packager couldn't
  build the plugin: bad manifest, deprecated API (`new Gson()`), or an unverified third-party
  dep. Fix the underlying problem.
- **`upload`** — SKIPPED on open PRs by design. Ignore it.
- **`RuneLite Plugin Hub Checks`** (from `runelite-github-app`) — an auto-review gate posted by a
  private bot. Its PR comment (prefix `<!-- RL CHECKS -->`) reveals the verdict in plain text:
  - ✅ auto-pass → short comment, just `Internal use only: [Reviewer details] [Maintainer details]`.
  - ❌ → comment leads with **"This plugin requires a review from a Plugin Hub maintainer. The
    reviewer will request any additional changes if needed."** This is **not blocking** — it just
    routes the plugin into the human-review queue.

LogAdviser will **not** auto-pass and that is expected: auto-pass is reserved for trivial plugins,
and LogAdviser ships ~792 KB of bundled JSON, makes outbound HTTP calls (OSRS Hiscores /
TempleOSRS via injected `OkHttpClient`), and has a multi-package source tree. Accept the
human-review queue as the normal path — **do not strip real features chasing the auto-pass gate.**

Inspect any PR's true state with:
```powershell
gh pr view <num> --repo runelite/plugin-hub --json statusCheckRollup,comments,labels
```

## Common mistakes (these have all happened)

- **Opening a PR in our own repo and expecting the Hub to update** — it won't. Only the pinned
  `commit=` in `runelite/plugin-hub` matters.
- **Skipping the fork sync** — stale fork base → confusing diff and merge conflicts.
- **Panicking at `RuneLite Plugin Hub Checks ❌`** — it's the human-review gate, not a failure.
  The check to actually watch is `build`.
- **Letting `new Gson()` reach `src/main`** — the packager's `build` check fails on it.
- **Pinning a short SHA** — the manifest wants the full 40-char hash.

## Related memory

- [[reference_plugin_hub_update_route]] — the bump-the-commit update route
- [[reference_runelite_plugin_hub]] — meaning of the Plugin Hub Checks ❌ status
- [[reference_plugin_hub_forbidden_apis]] — APIs the packager rejects (`new Gson()`)
- [[edit-logadviser-data]] / [[add-logadviser-slot]] — for the data change you're shipping
