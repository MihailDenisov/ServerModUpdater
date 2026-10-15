package dev.servermodupdates;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {
    @TempDir Path root;

    @Test
    void modrinthUsesExactHashAndBetaSwitch() throws Exception {
        Path jar = createJar("mods/example-2.0.jar", "hello");
        String hash = ModScanner.scan(root, false, false, warning -> { }).getFirst().sha1();
        UpdateChecker.Transport transport = (method, uri, body, key) -> {
            if (uri.getPath().endsWith("/version_files")) {
                return hashMap(hash, version("installed", "project", "1.0", "2025-01-01T00:00:00Z", "Example"));
            }
            if (uri.getPath().endsWith("/version_files/update")) {
                boolean beta = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("version_types")
                        .asList().stream().anyMatch(value -> "beta".equals(value.getAsString()));
                return hashMap(hash, beta
                        ? version("new", "project", "2.0-beta", "2025-02-01T00:00:00Z", "Example")
                        : version("installed", "project", "1.0", "2025-01-01T00:00:00Z", "Example"));
            }
            JsonObject project = new JsonObject();
            project.addProperty("title", "Example Mod");
            return project;
        };
        UpdateChecker checker = new UpdateChecker(transport, "https://mock.modrinth/v2", "https://mock.curse/v1");

        Report stable = checker.check(root, new Settings("", false, false, false));
        assertEquals(0, stable.updates.size());
        assertEquals(1, stable.matchedWithoutUpdateCount());
        Report beta = checker.check(root, new Settings("", true, false, false));
        assertEquals(1, beta.updates.size());
        assertEquals("1.0", beta.updates.getFirst().installed());
        assertEquals(jar.getFileName().toString(), Path.of(beta.updates.getFirst().path()).getFileName().toString());
        assertTrue(beta.updates.getFirst().url().endsWith("/project/version/new"));
        assertTrue(beta.downloads.get("mods/example-2.0.jar").get(Report.Provider.MODRINTH).actionable());
    }

    @Test
    void curseforgeUsesPaginationMetadataAndExactFileUrl() throws Exception {
        createJar("mods/example.jar", "curse file");
        long fingerprint = ModScanner.scan(root, true, false, warning -> { }).getFirst().fingerprint();
        AtomicInteger filePages = new AtomicInteger();
        UpdateChecker.Transport transport = (method, uri, body, key) -> {
            if (uri.getHost().contains("modrinth")) return new JsonObject();
            if (uri.getPath().endsWith("/fingerprints/432")) return fingerprintResponse(fingerprint);
            if (uri.getPath().endsWith("/mods/77/files")) {
                int page = filePages.getAndIncrement();
                return filesPage(page, page == 0 ? file(100, 1, "2025-01-01T00:00:00Z", "old")
                        : file(200, 1, "2025-03-01T00:00:00Z", "new"));
            }
            JsonObject links = new JsonObject();
            links.addProperty("websiteUrl", "https://www.curseforge.com/minecraft/mc-mods/example");
            JsonObject project = new JsonObject();
            project.addProperty("name", "Example CF");
            project.add("links", links);
            JsonObject response = new JsonObject();
            response.add("data", project);
            return response;
        };
        Report report = new UpdateChecker(transport, "https://mock.modrinth/v2", "https://mock.curse/v1")
                .check(root, new Settings("secret-not-logged", false, false, false));
        assertEquals(2, filePages.get());
        assertEquals(1, report.updates.size());
        assertEquals("https://www.curseforge.com/minecraft/mc-mods/example/files/200",
                report.updates.getFirst().url());
        assertTrue(report.downloads.get("mods/example.jar").get(Report.Provider.CURSEFORGE).actionable());
    }

    @Test
    void providerFailuresAreUnresolvedAndMalformedResponsesFailClosed() throws Exception {
        createJar("mods/example.jar", "unknown");
        UpdateChecker.Transport transport = (method, uri, body, key) -> {
            if (uri.getHost().contains("modrinth")) throw new IOException("временная ошибка API, HTTP 503");
            return new JsonObject(); // Missing required data.exactMatches.
        };
        Report report = new UpdateChecker(transport, "https://mock.modrinth/v2", "https://mock.curse/v1")
                .check(root, new Settings("key", false, false, false));
        assertFalse(report.modrinthSuccess);
        assertFalse(report.curseforgeSuccess);
        assertEquals(0, report.unknownCount());
        assertEquals(1, report.unresolvedCount());
        assertEquals(Report.Status.NETWORK_ERROR,
                report.outcomes.values().iterator().next().get(Report.Provider.MODRINTH).status());
        assertEquals(Report.Status.INVALID_RESPONSE,
                report.outcomes.values().iterator().next().get(Report.Provider.CURSEFORGE).status());
        assertTrue(report.warnings.stream().anyMatch(warning -> warning.contains("HTTP 503")));
        assertTrue(report.warnings.stream().noneMatch(warning -> warning.contains("key")));
    }

    @Test
    void missingCurseforgeKeySkipsItButKeepsModrinthResult() throws Exception {
        createJar("mods/example.jar", "unknown");
        AtomicInteger curseCalls = new AtomicInteger();
        UpdateChecker.Transport transport = (method, uri, body, key) -> {
            if (uri.getHost().contains("curse")) curseCalls.incrementAndGet();
            return new JsonObject();
        };
        Report report = new UpdateChecker(transport, "https://mock.modrinth/v2", "https://mock.curse/v1")
                .check(root, new Settings("", false, false, false));
        assertEquals(0, curseCalls.get());
        assertEquals(1, report.unknownCount());
        assertEquals(0, report.unresolvedCount());
        assertTrue(report.warnings.stream().anyMatch(warning -> warning.contains("no API key")));
    }

    @Test
    void curseforge401And403HaveDistinctPerFileStatuses() throws Exception {
        createJar("mods/auth.jar", "auth");
        for (int httpStatus : new int[]{401, 403}) {
            UpdateChecker.Transport transport = (method, uri, body, key) -> {
                if (uri.getHost().contains("modrinth")) return new JsonObject();
                throw new UpdateChecker.ApiException(httpStatus);
            };
            Report report = new UpdateChecker(transport, "https://mock.modrinth/v2", "https://mock.curse/v1")
                    .check(root, new Settings("configured", false, false, false));
            Report.Status expected = httpStatus == 401 ? Report.Status.AUTH_REQUIRED : Report.Status.FORBIDDEN;
            assertEquals(expected, report.outcomes.values().iterator().next()
                    .get(Report.Provider.CURSEFORGE).status());
            assertEquals(1, report.unresolvedCount());
        }
    }

    @Test
    void olderCandidateIsNeverReported() {
        assertFalse(UpdateChecker.strictlyNewer("2025-02-01T00:00:00Z", "2025-01-01T00:00:00Z"));
        assertFalse(UpdateChecker.strictlyNewer("2025-02-01T00:00:00Z", "not-a-date"));
        assertTrue(UpdateChecker.strictlyNewer("2025-01-01T00:00:00Z", "2025-02-01T00:00:00Z"));
    }

    private Path createJar(String relative, String contents) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
        return path;
    }

    private static JsonObject hashMap(String hash, JsonObject version) {
        JsonObject response = new JsonObject();
        response.add(hash, version);
        return response;
    }

    private static JsonObject version(String id, String project, String number, String date, String name) {
        JsonObject version = new JsonObject();
        version.addProperty("id", id);
        version.addProperty("project_id", project);
        version.addProperty("version_number", number);
        version.addProperty("date_published", date);
        version.addProperty("name", name);
        JsonObject hashes = new JsonObject();
        hashes.addProperty("sha1", "0123456789abcdef");
        JsonObject file = new JsonObject();
        file.addProperty("filename", "example-" + number + ".jar");
        file.addProperty("url", "https://cdn.modrinth.com/data/project/versions/" + id + "/example.jar");
        file.addProperty("size", 123);
        file.addProperty("primary", true);
        file.add("hashes", hashes);
        JsonArray files = new JsonArray();
        files.add(file);
        version.add("files", files);
        return version;
    }

    private static JsonObject fingerprintResponse(long fingerprint) {
        JsonObject file = file(100, 1, "2025-01-01T00:00:00Z", "old");
        file.addProperty("fileFingerprint", fingerprint);
        file.addProperty("modId", 77);
        JsonObject match = new JsonObject();
        match.add("file", file);
        JsonArray exact = new JsonArray();
        exact.add(match);
        JsonObject data = new JsonObject();
        data.add("exactMatches", exact);
        JsonObject response = new JsonObject();
        response.add("data", data);
        return response;
    }

    private static JsonObject filesPage(int index, JsonObject file) {
        JsonArray data = new JsonArray();
        data.add(file);
        JsonObject pagination = new JsonObject();
        pagination.addProperty("index", index);
        pagination.addProperty("pageSize", 50);
        pagination.addProperty("resultCount", 1);
        pagination.addProperty("totalCount", 2);
        JsonObject response = new JsonObject();
        response.add("data", data);
        response.add("pagination", pagination);
        return response;
    }

    private static JsonObject file(long id, int releaseType, String date, String name) {
        JsonObject file = new JsonObject();
        file.addProperty("id", id);
        file.addProperty("releaseType", releaseType);
        file.addProperty("fileDate", date);
        file.addProperty("displayName", name);
        file.addProperty("fileName", name + ".jar");
        file.addProperty("isAvailable", true);
        file.addProperty("isServerPack", false);
        file.addProperty("fileLength", 123);
        file.addProperty("downloadUrl", "https://mediafilez.forgecdn.net/files/1/" + id + "/" + name + ".jar");
        JsonObject hash = new JsonObject();
        hash.addProperty("algo", 1);
        hash.addProperty("value", "0123456789abcdef");
        JsonArray hashes = new JsonArray();
        hashes.add(hash);
        file.add("hashes", hashes);
        return file;
    }
}
