package dev.servermodupdates;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Re-resolves the immutable provider file/version ID immediately before a plan action. */
final class CandidateAvailabilityValidator {
    private static final String MODRINTH = "https://api.modrinth.com/v2";
    private static final String CURSEFORGE = "https://api.curseforge.com/v1";
    private final Settings settings;
    private final UpdateChecker.Transport transport;
    private final String modrinthBase;
    private final String curseforgeBase;

    CandidateAvailabilityValidator(Settings settings) {
        this(settings, new UpdateChecker.JdkTransport(settings), MODRINTH, CURSEFORGE);
    }

    CandidateAvailabilityValidator(Settings settings, UpdateChecker.Transport transport,
                                   String modrinthBase, String curseforgeBase) {
        this.settings = settings;
        this.transport = transport;
        this.modrinthBase = modrinthBase.replaceAll("/+$", "");
        this.curseforgeBase = curseforgeBase.replaceAll("/+$", "");
    }

    void validate(Report.Download expected) throws IOException, InterruptedException {
        if (transport instanceof UpdateChecker.JdkTransport jdk) {
            jdk.beginCheck("plan-validate-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        }
        if (expected.provider() == Report.Provider.MODRINTH) validateModrinth(expected);
        else validateCurseForge(expected);
    }

    private void validateModrinth(Report.Download expected) throws IOException, InterruptedException {
        JsonObject version = transport.send("GET", URI.create(modrinthBase + "/version/" + segment(expected.fileId())), null, "");
        require(string(version, "id").equals(expected.fileId())
                && string(version, "project_id").equals(expected.projectId()), "Modrinth version identity changed");
        require(contains(version, "game_versions", "1.21.1") && contains(version, "loaders", "neoforge"),
                "Modrinth candidate is no longer Minecraft 1.21.1 / NeoForge compatible");
        String type = string(version, "version_type").toLowerCase(Locale.ROOT);
        require(type.isBlank() || type.equals("release") || type.equals("beta") && settings.includeBeta()
                || type.equals("alpha") && settings.includeAlpha(), "Modrinth release channel is not allowed");
        JsonObject file = findModrinthFile(version, expected);
        require(file != null, "Modrinth candidate file is unavailable");
        JsonObject hashes = object(file, "hashes");
        require(number(file, "size") == expected.size() && string(file, "filename").equals(expected.fileName())
                && string(hashes, expected.hashAlgorithm()).equalsIgnoreCase(expected.hash()),
                "Modrinth candidate metadata changed");
    }

    private void validateCurseForge(Report.Download expected) throws IOException, InterruptedException {
        require(settings.curseforgeEnabled(), "CurseForge API key is not configured");
        JsonObject response = transport.send("GET", URI.create(curseforgeBase + "/mods/" + segment(expected.projectId())
                + "/files/" + segment(expected.fileId())), null, settings.curseforgeKey());
        JsonObject file = object(response, "data");
        require(file != null && Long.toString(number(file, "id")).equals(expected.fileId()),
                "CurseForge file identity changed");
        long modId = number(file, "modId");
        require(modId < 0 || Long.toString(modId).equals(expected.projectId()), "CurseForge project identity changed");
        require(!file.has("isAvailable") || file.get("isAvailable").getAsBoolean(), "CurseForge file is unavailable");
        require(contains(file, "gameVersions", "1.21.1") && containsExactIgnoreCase(file, "gameVersions", "neoforge"),
                "CurseForge candidate is no longer Minecraft 1.21.1 / NeoForge compatible");
        require(number(file, "fileLength") == expected.size() && string(file, "fileName").equals(expected.fileName()),
                "CurseForge candidate metadata changed");
        require(hashMatches(file, expected), "CurseForge candidate hash changed");
    }

    private static JsonObject findModrinthFile(JsonObject version, Report.Download expected) {
        JsonArray files = array(version, "files");
        if (files == null) return null;
        for (JsonElement element : files) {
            if (element.isJsonObject() && string(element.getAsJsonObject(), "filename").equals(expected.fileName())) {
                return element.getAsJsonObject();
            }
        }
        return null;
    }

    private static boolean hashMatches(JsonObject file, Report.Download expected) {
        JsonArray hashes = array(file, "hashes");
        if (hashes == null) return false;
        int code = switch (expected.hashAlgorithm()) { case "sha1" -> 1; case "sha256" -> 3; default -> -1; };
        for (JsonElement element : hashes) {
            if (element.isJsonObject() && number(element.getAsJsonObject(), "algo") == code
                    && string(element.getAsJsonObject(), "value").equalsIgnoreCase(expected.hash())) return true;
        }
        return false;
    }

    private static String segment(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) throw new IOException("unsafe provider identifier");
        return value;
    }

    private static boolean contains(JsonObject object, String name, String expected) {
        JsonArray values = array(object, name);
        if (values == null) return false;
        for (JsonElement value : values) if (value.isJsonPrimitive() && expected.equals(value.getAsString())) return true;
        return false;
    }

    private static boolean containsExactIgnoreCase(JsonObject object, String name, String expected) {
        JsonArray values = array(object, name);
        if (values == null) return false;
        for (JsonElement value : values) if (value.isJsonPrimitive()
                && value.getAsString().trim().equalsIgnoreCase(expected)) return true;
        return false;
    }

    private static JsonArray array(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : null;
    }

    private static JsonObject object(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : null;
    }

    private static String string(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private static long number(JsonObject object, String name) {
        try { return Long.parseLong(string(object, name)); } catch (NumberFormatException e) { return -1; }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
