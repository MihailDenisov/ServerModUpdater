package dev.servermodupdates;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWriterTest {
    @TempDir Path root;

    @Test
    void writesValidMarkdownAndJsonWithSeparateStates() throws Exception {
        Path jar = root.resolve("mods/example.jar").toAbsolutePath();
        Report report = new Report();
        report.scanned.add(new ModScanner.Jar(jar, "mods/example.jar", "abc", 1));
        report.modrinthSuccess = false;
        report.curseforgeEnabled = false;
        report.initializeOutcomes();
        report.setPending(Report.Provider.MODRINTH, Report.Status.NETWORK_ERROR, "IO_ERROR");
        report.warnings.add("Modrinth: временно недоступен");

        Settings russian = new Settings("", false, false, false, 2, 500, 10_000,
                25, 180, false, Settings.Language.RU_RU, 0);
        ReportWriter.Written first = ReportWriter.write(root, report, russian);
        ReportWriter.Written second = ReportWriter.write(root, report, russian);

        assertNotEquals(first.markdown(), second.markdown());
        assertTrue(Files.isRegularFile(first.markdown()));
        assertTrue(Files.isRegularFile(first.json()));
        String markdown = Files.readString(first.markdown());
        assertTrue(markdown.contains("Не удалось полностью проверить"));
        assertTrue(markdown.contains("mods/example.jar"));
        JsonObject json = JsonParser.parseString(Files.readString(first.json()))
                .getAsJsonObject();
        assertEquals("UTC", json.get("timeZone").getAsString());
        assertEquals(0, json.get("unmatchedFiles").getAsInt());
        assertEquals(1, json.get("unresolvedFiles").getAsInt());
        assertEquals(json.get("scannedFiles").getAsInt(), json.getAsJsonArray("files").size());
        assertEquals(json.get("scannedFiles").getAsInt(), json.get("filesWithUpdates").getAsInt()
                + json.get("unresolvedFiles").getAsInt() + json.get("unmatchedFiles").getAsInt()
                + json.get("matchedWithoutUpdate").getAsInt());
        assertEquals("NETWORK_ERROR", json.getAsJsonArray("files").get(0).getAsJsonObject()
                .getAsJsonObject("providers").getAsJsonObject("modrinth").get("status").getAsString());
        assertEquals("mods/example.jar", json.getAsJsonArray("unresolved").get(0).getAsString());
    }

    @Test
    void localizesEnglishReportAndPrunesOnlyWhenConfigured() throws Exception {
        Report report = new Report();
        report.curseforgeEnabled = false;
        report.initializeOutcomes();
        Settings english = new Settings("", false, false, false, 2, 500, 10_000,
                25, 180, false, Settings.Language.EN_US, 1);
        ReportWriter.Written first = ReportWriter.write(root, report, english);
        ReportWriter.Written second = ReportWriter.write(root, report, english);
        assertTrue(Files.readString(second.markdown()).contains("# Mod updates"));
        assertFalse(Files.exists(first.markdown()));
        assertFalse(Files.exists(first.json()));
    }
}
