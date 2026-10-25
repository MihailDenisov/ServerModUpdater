# ServerModUpdater

ServerModUpdater 1.3.0 is a server-only NeoForge mod by **HardySofty** for manually checking and safely applying Minecraft 1.21.1 mod updates from Modrinth and CurseForge. It never guesses projects from filenames, hot-reloads mods, restarts the server, or runs `/amp generate`.

## Install and configure

Use Java 21 and NeoForge 21.1.x. Copy `ServerModUpdater-1.3.0.jar` into the dedicated server's `mods/` directory; clients do not install it. The first start creates `config/modupdates.properties`. Commands require the server console or OP permission level 2.

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

Prefer the `MODUPDATES_CURSEFORGE_API_KEY` environment variable and never publish a real key. Settings reload on commands/checks. `language` supports `en_us` and `ru_ru`; unsupported values fall back to English. AutoModpack is strictly opt-in. Disabled mode scans only `mods/`; enabled mode also scans recognized `automodpack/host-modpack/<group>/mods/` and configured legacy `host-midpack` groups.

## Selective Markdown plans

1. Run `/modupdates check`. It writes paired scan reports in `modupdates/reports/` and a new Markdown plan plus immutable JSON manifest in `modupdates/plans/`.
2. Open the reported `update-plan-<planId>.md`. Every update is `- [ ]` (unchecked). Change only desired markers to `- [x]`; do not edit IDs or displayed fields.
3. Run `/modupdates plan preview [planId]`. It validates checkbox syntax, immutable metadata, source hashes, provider file/version identity, Minecraft/NeoForge compatibility, and exact target paths without modifying JARs.
4. Run `/modupdates plan apply [planId]`. Only explicitly checked, valid entries are downloaded and replaced. Unchecked and unmatched files remain untouched.
5. Review `modupdates/results/update-result-*.md/.json` and `/modupdates plan status`.

Plans and results use UTC timestamps plus collision suffixes and never overwrite prior files. Reordered checkbox entries remain tied to stable IDs. Unknown, duplicate, missing, malformed, tampered, stale, unavailable, or conflicting selections fail closed. Differing provider versions are separate mutually exclusive choices; identical versions collapse to one deterministic choice while retaining both provider links. An empty selection changes nothing. Reapplying completed choices is idempotent.

## Commands

```text
/modupdates check
/modupdates status
/modupdates list [page]
/modupdates plan list [page]
/modupdates plan preview [planId]
/modupdates plan apply [planId]
/modupdates plan status
/modupdates plan cancel
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

`plan cancel` stops between complete file operations; it never interrupts a replacement halfway through. Direct single/bulk update commands remain available for compatibility.

## File safety and restart

Only exact provider/hash matches with trusted HTTPS metadata can be updated. Downloads go to temporary files and must pass size, checksum, JAR, path, and current-source-hash checks. The original becomes `filename.jar.old`; an existing backup causes that item to fail without overwriting it. Rollback restores the backup. Partial plan results are recorded per item.

Changes are live file replacements, not hot reloads. After any successful replacement, restart the server. `/amp generate` is requested only when an AutoModpack-managed JAR actually changed and must be run before restarting; neither action is automatic. Server administrators remain responsible for mod redistribution rights.

## Build and issues

Run `./gradlew build` (Windows: `.\gradlew.bat build`) with Java 21. The artifact is `build/libs/ServerModUpdater-1.3.0.jar`.

Report issues at [github.com/MihailDenisov/ServerModUpdater/issues](https://github.com/MihailDenisov/ServerModUpdater/issues) with sanitized logs, command, Minecraft/NeoForge versions, plan ID, and result report. Never include API keys or signed URLs. The project is licensed under the MIT License; see `LICENSE`.
