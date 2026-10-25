package dev.servermodupdates;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateAvailabilityValidatorTest {
    @Test
    void modrinthRechecksExactVersionCompatibilityAndImmutableFileMetadata() {
        Report.Download expected = new Report.Download(Report.Provider.MODRINTH, "project", "version", "new.jar",
                "https://cdn.modrinth.com/new.jar", 10, "sha1", "abc", "2",
                "2026-01-01T00:00:00Z", "https://modrinth.com/mod/project/version/version");
        JsonObject response = modrinthResponse();
        CandidateAvailabilityValidator validator = new CandidateAvailabilityValidator(
                new Settings("", false, false, false), (method, uri, body, key) -> response,
                "https://mock.modrinth/v2", "https://mock.curse/v1");
        assertDoesNotThrow(() -> validator.validate(expected));

        response.getAsJsonArray("loaders").set(0, new com.google.gson.JsonPrimitive("fabric"));
        assertThrows(IOException.class, () -> validator.validate(expected));
    }

    @Test
    void curseforgeRequiresExactCompatibleFileAndConfiguredAuthorization() {
        Report.Download expected = new Report.Download(Report.Provider.CURSEFORGE, "77", "200", "new.jar",
                "https://mediafilez.forgecdn.net/files/1/200/new.jar", 10, "sha1", "abc", "2",
                "2026-01-01T00:00:00Z", "https://www.curseforge.com/minecraft/mc-mods/example/files/200");
        JsonObject file = new JsonObject();
        file.addProperty("id", 200); file.addProperty("modId", 77); file.addProperty("isAvailable", true);
        file.addProperty("fileLength", 10); file.addProperty("fileName", "new.jar");
        JsonArray versions = new JsonArray(); versions.add("1.21.1"); versions.add("NeoForge"); file.add("gameVersions", versions);
        JsonObject hash = new JsonObject(); hash.addProperty("algo", 1); hash.addProperty("value", "abc");
        JsonArray hashes = new JsonArray(); hashes.add(hash); file.add("hashes", hashes);
        JsonObject response = new JsonObject(); response.add("data", file);
        CandidateAvailabilityValidator valid = new CandidateAvailabilityValidator(
                new Settings("secret", false, false, false), (method, uri, body, key) -> response,
                "https://mock.modrinth/v2", "https://mock.curse/v1");
        assertDoesNotThrow(() -> valid.validate(expected));
        versions.set(1, new com.google.gson.JsonPrimitive("not-neoforge"));
        assertThrows(IOException.class, () -> valid.validate(expected));
        versions.set(1, new com.google.gson.JsonPrimitive("NeoForge"));
        CandidateAvailabilityValidator noKey = new CandidateAvailabilityValidator(
                new Settings("", false, false, false), (method, uri, body, key) -> response,
                "https://mock.modrinth/v2", "https://mock.curse/v1");
        assertThrows(IOException.class, () -> noKey.validate(expected));
    }

    private static JsonObject modrinthResponse() {
        JsonObject response = new JsonObject();
        response.addProperty("id", "version"); response.addProperty("project_id", "project");
        response.addProperty("version_type", "release");
        JsonArray game = new JsonArray(); game.add("1.21.1"); response.add("game_versions", game);
        JsonArray loaders = new JsonArray(); loaders.add("neoforge"); response.add("loaders", loaders);
        JsonObject hashes = new JsonObject(); hashes.addProperty("sha1", "abc");
        JsonObject file = new JsonObject(); file.addProperty("filename", "new.jar"); file.addProperty("size", 10);
        file.add("hashes", hashes); JsonArray files = new JsonArray(); files.add(file); response.add("files", files);
        return response;
    }
}
