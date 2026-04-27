# Log Adviser

A RuneLite plugin that tells you which collection log slot to chase next.

Log Adviser ranks every remaining collection-log activity by **Time-To-Next-Slot** — the expected wall-clock time until your next unique drop — and surfaces the recommendation through a sidebar panel, an info box, and an in-world highlight on the relevant NPC.

![Log Adviser overview](docs/Collection_log_detail.png)

## Features

- **Sidebar panel** listing your top upcoming activities, ranked by expected time to the next unique slot
- **Info box** in the top-left tray showing the current target activity at a glance
- **Overlay box** rendered on the game viewport with the current target and ETA
- **NPC highlight** — light-blue convex hull around any NPC that drops the current target slot
- **Account-mode aware** — auto-detects Standard / Ironman / Hardcore Ironman / Ultimate Ironman from your in-game varbits and the OSRS Hiscores
- **Optional TempleOSRS warm-start** — on first launch, seeds your obtained-items list from the public [TempleOSRS](https://templeosrs.com) collection log so you don't have to click through every collection log page

## Configuration

| Setting | Default | What it does |
| --- | --- | --- |
| **Show next slot as** | Overlay box | Where to render the recommendation: `Info box`, `Overlay box`, `Both`, or `None` |
| **TempleOSRS warm-start** | On | One-time query to TempleOSRS to seed obtained items if the local cache is empty |
| **Highlight target NPCs** | On | Draws a hull around NPCs that drop the active target slot |
| **Upcoming list size** | 30 | How many activities the sidebar lists |

Settings live under **RuneLite → Configuration → Log Adviser** in the standard plugin settings panel.

## Account modes

Account type is detected from in-game varbits when you log in and cross-checked against the OSRS Hiscores endpoint that matches your account (standard / ironman / HCIM / UIM). Drops that are blocked by your mode are filtered out of the ranking automatically.

## Data sources

The activity, slot, and NPC tables ship bundled with the plugin as JSON resources:

- `activities.json` — every collection-log activity
- `slots.json` — every collection-log slot, with the activities that drop it
- `activity_map.json` — slot ↔ activity drop-rate map
- `activity_npcs.json` — NPCs associated with each activity (for the world highlight)

The bundled JSON is generated from a maintained spreadsheet (`docs/Collection Log Adviser.xlsx`) by the helper script `tools/generate_log_data.py`. To regenerate after editing the spreadsheet:

```bash
./gradlew generateLogData
```

## Networking

Log Adviser makes two read-only HTTP calls, both via the RuneLite-injected `OkHttpClient`:

- **OSRS Hiscores** (`secure.runescape.com`) — to fetch your Collections Logged rank/score and confirm account mode. Cached for 5 minutes.
- **TempleOSRS** (`templeosrs.com`) — *optional, opt-in* — used only for the one-time warm-start seeding of obtained items.

No data is sent to either service beyond your character name; nothing is written anywhere outside the standard RuneLite config dir.

## Building locally

```bash
./gradlew build       # compile + tests
./gradlew runSanity   # headless engine sanity check against bundled data
./gradlew run         # boots a RuneLite client with the plugin loaded
```

Targets Java 11. Pulls the latest released RuneLite client (`net.runelite:client:latest.release`).

## Releasing

Updates to the Plugin Hub are bumped via a single helper script — no rebuild, no manual hash editing:

```bash
# 1. Make changes, commit, push to origin/master
git push

# 2. From a sibling clone of your plugin-hub fork on a fresh branch:
./bump-pluginhub.sh

# 3. Open a small PR titled "log-adviser: bump to <short-sha>" against runelite/plugin-hub
```

The script reads the latest commit on `origin/master` of this repo, rewrites `plugins/log-adviser` in the sibling plugin-hub clone with the **full 40-character** SHA, then commits and pushes.

## License

[BSD 2-Clause](LICENSE) © SFranciscoSouza
