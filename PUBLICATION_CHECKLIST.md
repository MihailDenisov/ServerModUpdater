# Publication metadata checklist

This document records upload choices; it does not claim approval by NeoForge, Modrinth, or CurseForge.

References: [NeoForge 1.21.1 mod files](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles/), [Modrinth rules](https://modrinth.com/legal/rules), [Modrinth version upload fields](https://docs.modrinth.com/api/operations/createversion/), and [CurseForge project submission](https://support.curseforge.com/support/solutions/articles/9000197241-creating-and-submitting-a-project).

## Packaged NeoForge metadata

- Descriptor: `META-INF/neoforge.mods.toml` only; stable mod ID `modupdates`.
- Display/version/author: `ServerModUpdater`, `1.2.0`, `HardySofty`.
- Runtime: Java 21, Minecraft `1.21.1`, NeoForge `21.1.x`, server-only entrypoint/code.
- License: MIT, explicitly confirmed by HardySofty before publication.
- Repository and issue URLs point to the canonical public repository: `MihailDenisov/ServerModUpdater`.

## Modrinth website/version fields

- Project name and English description consistent with the README.
- Environment: server required; client unsupported/not required. Loader: NeoForge. Game version: 1.21.1.
- Upload the validated primary JAR as release `1.2.0` with the matching changelog and dependencies.
- Select MIT as the project license; declare dependencies and any third-party content accurately.

## CurseForge website/file fields

- Minecraft Mods project with a unique name, English summary/description, author-controlled license choice, and server-side classification.
- File metadata: release type, Minecraft 1.21.1, NeoForge, changelog, dependencies, and the validated `1.2.0` JAR.
- Platform moderation and listing fields are separate from `neoforge.mods.toml` and remain manual publication work.

No Modrinth or CurseForge upload is authorized by this checklist.
