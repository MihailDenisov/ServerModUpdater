# Changelog

## 1.2.0

- Renamed the public project and artifact to ServerModUpdater; set author metadata to HardySofty.
- Added `language=en_us|ru_ru` with English fallback and command-time config reload.
- Made AutoModpack integration optional and disabled by default.
- Added unique paired UTC Markdown/JSON reports with collision handling, atomic writes, and opt-in retention.
- Preserved exact-hash matching, all `/modupdates` commands, `.jar.old` backup/recovery, rollback, bounded API retries, and secret-safe diagnostics.
- Added metadata and packaged-JAR validation coverage for Minecraft 1.21.1 / NeoForge 21.1.x.

## 1.1.1

- Added manual updater commands, stable path IDs, provider result merging, safe verified replacement, rollback, and bounded API diagnostics.
