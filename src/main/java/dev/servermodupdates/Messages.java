package dev.servermodupdates;

final class Messages {
    private Messages() { }

    static String text(Settings settings, String english, String russian) {
        return settings.language() == Settings.Language.RU_RU ? russian : english;
    }

    static String restart(Settings settings) {
        return restart(settings, settings.enableAutoModpack());
    }

    static String restart(Settings settings, boolean autoModpackChanged) {
        if (autoModpackChanged) {
            return text(settings,
                    "Mod files changed. Run /amp generate to regenerate the AutoModpack modpack, then restart the server.",
                    "Файлы модов изменены. Выполните /amp generate для пересборки AutoModpack, затем перезапустите сервер.");
        }
        return text(settings, "Mod files changed. Restart the server to apply them; no hot reload was performed.",
                "Файлы модов изменены. Перезапустите сервер для применения; hot reload не выполнялся.");
    }
}
