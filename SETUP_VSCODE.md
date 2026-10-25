# Открыть проект в VS Code и передать задание Codex

1. Распакуйте архив и откройте **именно папку `server-mod-updates-vscode-codex`** через `File → Open Folder` (не верхнюю папку, содержащую архив).
2. Установите **JDK 21**, убедитесь, что `java -version` в терминале VS Code показывает 21. Если пользуетесь Windows, можно настроить `JAVA_HOME` на JDK 21. Интернет нужен для NeoForge/Gradle-зависимостей.
3. VS Code предложит **Install Workspace Recommended Extensions** из `.vscode/extensions.json`. Установите рекомендуемые плагины: **Codex (OpenAI)**, **Extension Pack for Java**, **Gradle for Java**. Эти файлы **только рекомендуют** расширения; ZIP не устанавливает их и не содержит исполняемых VSIX.
4. В боковой панели VS Code откройте Codex, войдите в свою учётную запись, разрешите работу в доверенной папке и откройте новый чат. Если не видно значка Codex, через Command Palette найдите `Codex: Open Codex Sidebar`.
5. Откройте `CODEX_PROMPT.txt`, скопируйте в чат Codex его текст и отправьте. Codex прочитает `AGENTS.md` и `CODEX_TASK.md` из проекта.
6. Политика субагентов задаётся `AGENTS.md`; **реальные конфигурации** — `.codex/config.toml` (макс. 2 одновременных субагента) и `.codex/agents/*.toml` (2 узкие роли, только чтение). Эти параметры применятся, если используемая версия Codex поддерживает project-scoped configuration. Перезапустите чат, если изменили агентские конфиги.
7. Воспользуйтесь **Terminal → Run Task → Gradle: test** и **Gradle: build JAR** либо терминалом `./gradlew --no-daemon test` / `./gradlew --no-daemon build` (Windows: `.\gradlew.bat`). В проекте уже находится настоящий Gradle Wrapper 9.2.1; отдельная установка Gradle не нужна.
8. После успешной сборки JAR находится в `build/libs/ServerModUpdater-1.3.0.jar`; ставьте его только на NeoForge-сервер 1.21.1. Не ставьте исходный ZIP в `mods/`. Команда `/modupdates check` доступна консоли и OP 2+; отчёты: `modupdates/reports/report-<UTC timestamp>-<suffix>.md/.json`.

## Какие расширения нужны, а какие нет

- **OpenAI Codex** — работа агента в IDE.
- **Extension Pack for Java** — JDK, навигация по Java, диагностика, тесты, отладка.
- **Gradle for Java** — запуск задач и импорт Gradle-проекта.

GitHub Copilot, дополнительные MCP, отдельные плагины Modrinth/CurseForge, Minecraft devpack и платные Java-помощники **не требуются**. CurseForge API key — ключ доступа к CurseForge для проверки модов, это **не** ключ OpenAI и не плагин VS Code. Храните его в окружении сервера `MODUPDATES_CURSEFORGE_API_KEY` или локальном приватном конфиге, не в Git.

## Официальные справочные страницы

- Codex AGENTS.md: https://learn.chatgpt.com/docs/agent-configuration/agents-md
- Codex subagents / project roles: https://learn.chatgpt.com/docs/agent-configuration/subagents
- Codex IDE: https://learn.chatgpt.com/docs/codex/ide
- VS Code Java: https://code.visualstudio.com/docs/java/extensions
- VS Code Gradle: https://code.visualstudio.com/docs/java/java-build
