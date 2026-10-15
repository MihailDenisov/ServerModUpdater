# ServerModUpdater

ServerModUpdater is a server-only NeoForge mod by **HardySofty** for manually checking and safely applying Minecraft 1.21.1 mod updates from Modrinth and CurseForge. It never hot-reloads mods, restarts the server, runs `/amp generate`, or guesses a project from a filename.

## Requirements and installation

- Minecraft 1.21.1, NeoForge 21.1.x, and Java 21.
- Copy `ServerModUpdater-1.2.0.jar` to the server's `mods/` directory. Clients do not install it.
- Start once to create `config/modupdates.properties`. Keep the CurseForge key private; `MODUPDATES_CURSEFORGE_API_KEY` can supply it from the environment.
- Commands require console access or OP permission level 2.

```properties
curseforgeApiKey=YOUR_CURSEFORGE_API_KEY
includeBeta=false
includeAlpha=false
enableAutoModpack=false
scanLegacyHostMidpack=true
language=en_us
reportRetentionCount=0
maxRetries=2
retryBaseDelayMillis=500
retryMaxDelayMillis=10000
requestTimeoutSeconds=25
checkBudgetSeconds=180
```

`language` accepts `en_us` or `ru_ru` and reloads on the next command/check; unsupported values fall back to English with a warning. `reportRetentionCount=0` keeps all reports. A positive value retains that many newest report pairs.

AutoModpack is strictly opt-in. With `enableAutoModpack=false`, only `mods/` is inspected and no AutoModpack directory or command is used. When enabled, the updater also scans `automodpack/host-modpack/<group>/mods/` and, when configured, legacy `host-midpack` groups. Server administrators are responsible for redistribution rights for every mod distributed through AutoModpack.

## Commands

```text
/modupdates check
/modupdates status
/modupdates list [page]
/modupdates update list [page]
/modupdates update <id> <modrinth|curseforge>
/modupdates update all dryrun
/modupdates update all confirm <token>
/modupdates update status
/modupdates rollback <id>
/modupdates diagnose curseforge
/modupdates configpath
/modupdates help
```

Checks write matching UTF-8 Markdown and JSON files under `modupdates/reports/`, named with a UTC timestamp and collision suffix. JSON field names and status enums remain language-neutral.

Only exact provider/hash matches can be updated. A successful replacement preserves the original as `.jar.old`; rollback restores it. Unknown or unmatched JARs are never changed. Any changed JAR requires a full server restart. When AutoModpack integration is enabled, run `/amp generate` before restarting; the mod only prints this reminder.

## Build and issues

Run `./gradlew build` (Windows: `.\gradlew.bat build`) with Java 21. The release JAR is written to `build/libs/ServerModUpdater-1.2.0.jar`.

Open an issue at [github.com/MihailDenisov/ServerModUpdater/issues](https://github.com/MihailDenisov/ServerModUpdater/issues) and include the Minecraft/NeoForge versions, sanitized logs, the command used, and the matching report. Never include API keys or signed download URLs.

ServerModUpdater is released under the MIT License. See `LICENSE`.

