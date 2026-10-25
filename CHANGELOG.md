# Changelog

## 1.3.0

- Added persistent, timestamped Markdown update plans with every choice unchecked by default.
- Added plan list, preview, apply, status, and safe between-item cancellation commands.
- Plan checkbox edits are validated against immutable JSON manifests; unknown, duplicate, malformed, tampered, stale, and conflicting choices fail closed.
- Added per-application Markdown/JSON results, durable idempotence state, immediate provider candidate revalidation, and path-specific AutoModpack reminders.
- Preserved existing direct update, bulk update, rollback, reports, localization, exact-hash matching, and `.jar.old` recovery behavior.

## 1.2.0

- Renamed the public project and artifact to ServerModUpdater; set author metadata to HardySofty.
- Added `language=en_us|ru_ru` with English fallback and command-time config reload.
- Made AutoModpack integration optional and disabled by default.
- Added unique paired UTC Markdown/JSON reports with collision handling, atomic writes, and opt-in retention.
- Preserved exact-hash matching, all `/modupdates` commands, `.jar.old` backup/recovery, rollback, bounded API retries, and secret-safe diagnostics.
- Added metadata and packaged-JAR validation coverage for Minecraft 1.21.1 / NeoForge 21.1.x.

## 1.1.1

- Added manual updater commands, stable path IDs, provider result merging, safe verified replacement, rollback, and bounded API diagnostics.
