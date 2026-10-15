package dev.servermodupdates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @TempDir Path root;

    @Test
    void createsCanonicalConfigAndEnvironmentOverridesTrimmedProperty() throws Exception {
        Path config = root.resolve("custom-config");
        Settings.LoadResult created = Settings.load(config, name -> null);
        assertTrue(created.created());
        assertEquals(config.resolve("modupdates.properties").toAbsolutePath(), created.path());
        assertTrue(Files.isRegularFile(created.path()));
        String defaults = Files.readString(created.path());
        assertTrue(defaults.contains("enableAutoModpack=false"));
        assertTrue(defaults.contains("language=en_us"));
        assertFalse(created.settings().enableAutoModpack());
        assertEquals(Settings.Language.EN_US, created.settings().language());

        Files.writeString(created.path(), "curseforgeApiKey= property-key  \nincludeBeta=true\n");
        Settings.LoadResult property = Settings.load(config, name -> "   ");
        assertEquals("property-key", property.settings().curseforgeKey());
        assertTrue(property.settings().includeBeta());
        assertFalse(property.environmentKey());

        Map<String, String> env = Map.of("MODUPDATES_CURSEFORGE_API_KEY", "  environment-key  ");
        Settings.LoadResult override = Settings.load(config, env::get);
        assertEquals("environment-key", override.settings().curseforgeKey());
        assertTrue(override.environmentKey());
        assertFalse(override.toString().contains("environment-key"));
    }

    @Test
    void migratesMissingKeysAndFallsBackFromUnknownLanguage() throws Exception {
        Path config = root.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve(Settings.FILE_NAME);
        Files.writeString(file, "curseforgeApiKey=keep-me\nlanguage=xx_yy\n");
        Settings.LoadResult loaded = Settings.load(config, name -> null);
        assertTrue(loaded.languageFallback());
        assertEquals(Settings.Language.EN_US, loaded.settings().language());
        assertEquals("keep-me", loaded.settings().curseforgeKey());
        assertTrue(Files.readString(file).contains("enableAutoModpack=false"));
    }
}
