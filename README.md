# Log Adviser

A RuneLite plugin that tells you which collection log slot to chase next.

Log Adviser ranks every remaining collection-log activity by **Time-To-Next-Slot** — the expected wall-clock time until your next unique drop — and surfaces the recommendation through a sidebar panel, an info box, and an in-world highlight on the relevant NPC.

## Features

- **Sidebar panel** listing your top upcoming activities, ranked by expected time to the next unique slot
- **Info box** in the top-left tray showing the current target activity at a glance
- **Overlay box** rendered on the game viewport with the current target and ETA
- **NPC highlight** — light-blue convex hull around any NPC that drops the current target slot
- **Account-mode aware** — auto-detects Standard / Ironman / Hardcore Ironman / Ultimate Ironman from your in-game varbits (with a manual override)

## Configuration

| Setting | Default | What it does |
| --- | --- | --- |
| **Show next slot as** | Overlay box | Where to render the recommendation: `Info box`, `Overlay box`, `Both`, or `None` |
| **Highlight target NPCs** | On | Draws a hull around NPCs that drop the active target slot |
| **Upcoming list size** | 30 | How many activities the sidebar lists |

Settings live under **RuneLite → Configuration → Log Adviser** in the standard plugin settings panel.

## Account modes

Account type is detected from in-game varbits when you log in (standard / ironman / HCIM / UIM), with a manual override available in the sidebar. Drops that are blocked by your mode are filtered out of the ranking automatically.

## Data sources

The activity, slot, and NPC tables ship bundled with the plugin as JSON resources:

- `activities.json` — every collection-log activity
- `slots.json` — every collection-log slot, with the activities that drop it
- `activity_map.json` — slot ↔ activity drop-rate map
- `activity_npcs.json` — NPCs associated with each activity (for the world highlight)

The bundled JSON is generated from a maintained spreadsheet via a Python helper kept outside the plugin repo (in `../LogAdviser-extras/tools/`). The generated JSON is committed to this repo, so a fresh build never needs Python.

## Networking

Log Adviser makes **no network requests**. All data ships bundled with the plugin, and collection-log progress is tracked entirely from in-game events. Nothing is sent anywhere; nothing is written outside the standard RuneLite config dir.

## Building locally

```bash
./gradlew build   # compile + unit tests
./gradlew run     # boots a RuneLite client with the plugin loaded (also runs static-data sanity asserts on startup)
```

Targets Java 11. Pulls the latest released RuneLite client (`net.runelite:client:latest.release`).

## Releasing

To publish an update to the Plugin Hub: commit and push to `origin/master`, then update the manifest in your fork of `runelite/plugin-hub` so its `plugins/log-adviser` file points at the new commit (full 40-char SHA). Open a small PR titled `log-adviser: bump to <short-sha>` against `runelite/plugin-hub:master`.

## License

[BSD 2-Clause](LICENSE) © SFranciscoSouza
