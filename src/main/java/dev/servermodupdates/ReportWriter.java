package dev.servermodupdates;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ReportWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

    private ReportWriter() { }

    public record Written(Path markdown, Path json) { }

    /** Compatibility overload: historical callers produced Russian reports. */
    public static Written write(Path gameDir, Report report) throws IOException {
        return write(gameDir, report, new Settings("", false, false, false, 2, 500, 10_000,
                25, 180, false, Settings.Language.RU_RU, 0));
    }

    public static synchronized Written write(Path gameDir, Report report, Settings settings) throws IOException {
        Path output = gameDir.resolve("modupdates/reports");
        Files.createDirectories(output);
        String base = "report-" + FILE_TIME.format(report.time);
        String stem = base + "-000";
        for (int collision = 1; Files.exists(output.resolve(stem + ".md"))
                || Files.exists(output.resolve(stem + ".json")); collision++) {
            stem = base + "-" + String.format(Locale.ROOT, "%03d", collision);
        }
        Path markdown = output.resolve(stem + ".md");
        Path json = output.resolve(stem + ".json");
        Path mdTemporary = Files.createTempFile(output, stem, ".md.tmp");
        Path jsonTemporary = Files.createTempFile(output, stem, ".json.tmp");
        boolean mdMoved = false;
        try {
            Files.writeString(mdTemporary, markdown(report, settings), StandardCharsets.UTF_8);
            Files.writeString(jsonTemporary, GSON.toJson(json(report)), StandardCharsets.UTF_8);
            moveNew(mdTemporary, markdown);
            mdMoved = true;
            moveNew(jsonTemporary, json);
        } catch (IOException failure) {
            if (mdMoved) Files.deleteIfExists(markdown);
            Files.deleteIfExists(json);
            throw failure;
        } finally {
            Files.deleteIfExists(mdTemporary);
            Files.deleteIfExists(jsonTemporary);
        }
        prune(output, settings.reportRetentionCount());
        return new Written(markdown, json);
    }

    private static String markdown(Report report, Settings settings) {
        boolean ru = settings.language() == Settings.Language.RU_RU;
        StringBuilder md = new StringBuilder();
        md.append(ru ? "# Обновления модов — NeoForge 1.21.1\n\n" : "# Mod updates — NeoForge 1.21.1\n\n")
                .append(ru ? "Проверено: " : "Checked: ").append(report.time).append(" (UTC)\n\n")
                .append(ru ? "Файлов проверено: **" : "Files scanned: **").append(report.scanned.size())
                .append(ru ? "**, файлов с обновлениями: **" : "**, files with updates: **")
                .append(report.filesWithUpdates()).append(ru ? "**, опознано без новой версии: **" : "**, matched without update: **")
                .append(report.matchedWithoutUpdateCount()).append(ru ? "**, не опознано: **" : "**, unmatched: **")
                .append(report.unknownCount()).append(ru ? "**, не удалось полностью проверить: **" : "**, unresolved: **")
                .append(report.unresolvedCount()).append("**.\n\n")
                .append(ru ? "Источники: Modrinth = " : "Providers: Modrinth = ")
                .append(report.modrinthSuccess ? "OK" : (ru ? "ОШИБКА" : "ERROR"))
                .append("; CurseForge = ").append(!report.curseforgeEnabled ? (ru ? "ПРОПУЩЕН (нет ключа)" : "SKIPPED (no key)")
                        : report.curseforgeSuccess ? "OK" : (ru ? "ОШИБКА" : "ERROR")).append(".\n\n");
        if (report.filesWithUpdates() == 0) {
            md.append(ru ? "**Подтверждённых обновлений не найдено.** Недоступный источник не доказывает отсутствие обновлений.\n\n"
                    : "**No confirmed updates found.** An unavailable provider does not prove that no updates exist.\n\n");
        } else {
            md.append(ru ? "| ID | Путь | Modrinth | CurseForge |\n" : "| ID | Path | Modrinth | CurseForge |\n")
                    .append("|---|---|---|---|\n");
            for (ModScanner.Jar jar : report.scanned) {
                if (!report.hasUpdate(jar)) continue;
                md.append('|').append(UpdatePlan.stableId(jar.relative())).append("|`")
                        .append(cell(jar.relative())).append("`|")
                        .append(providerCell(report, jar, Report.Provider.MODRINTH)).append('|')
                        .append(providerCell(report, jar, Report.Provider.CURSEFORGE)).append("|\n");
            }
            md.append('\n');
        }
        section(md, ru ? "Не опознаны по хешу доступными источниками" : "Not recognized by hash",
                report.scanned.stream().filter(report::isUnknown).map(ModScanner.Jar::relative).toList(), ru);
        section(md, ru ? "Не удалось полностью проверить" : "Could not be checked completely",
                report.scanned.stream().filter(report::isUnresolved).map(ModScanner.Jar::relative).toList(), ru);
        md.append(ru ? "\n## Статусы каждого файла и источника\n\n| Путь | Итог | Modrinth | CurseForge |\n"
                : "\n## Per-file and provider status\n\n| Path | Classification | Modrinth | CurseForge |\n")
                .append("|---|---|---|---|\n");
        for (ModScanner.Jar jar : report.scanned) {
            String classification = report.hasUpdate(jar) ? "UPDATE_AVAILABLE"
                    : report.isUnresolved(jar) ? "UNRESOLVED"
                    : report.isUpToDate(jar) ? "UP_TO_DATE" : "NOT_FOUND_BY_HASH";
            md.append("|`").append(cell(jar.relative())).append("`|").append(classification).append('|')
                    .append(report.outcomes.get(jar.relative()).get(Report.Provider.MODRINTH).status()).append('|')
                    .append(report.outcomes.get(jar.relative()).get(Report.Provider.CURSEFORGE).status()).append("|\n");
        }
        md.append(ru ? "\n## Предупреждения\n\n" : "\n## Warnings\n\n");
        if (report.warnings.isEmpty()) md.append(ru ? "Нет.\n" : "None.\n");
        else report.warnings.forEach(warning -> md.append("- ").append(cell(warning)).append('\n'));
        md.append(ru ? "\nПроверка не обновляет JAR и не гарантирует совместимость зависимостей. Совпадения определяются только по точному хешу.\n"
                : "\nA scan does not update JARs or guarantee dependency compatibility. Matches use exact file hashes only.\n");
        return md.toString();
    }

    private static void section(StringBuilder md, String title, List<String> paths, boolean ru) {
        md.append("\n## ").append(title).append("\n\n");
        if (paths.isEmpty()) md.append(ru ? "Нет.\n" : "None.\n");
        else paths.forEach(path -> md.append("- `").append(cell(path)).append("`\n"));
    }

    private static JsonObject json(Report report) {
        JsonObject json = new JsonObject();
        json.addProperty("scanTime", report.time.toString());
        json.addProperty("timeZone", "UTC");
        json.addProperty("minecraft", "1.21.1");
        json.addProperty("loader", "neoforge");
        json.addProperty("scannedFiles", report.scanned.size());
        json.addProperty("filesWithUpdates", report.filesWithUpdates());
        json.addProperty("unmatchedFiles", report.unmatchedCount());
        json.addProperty("unresolvedFiles", report.unresolvedCount());
        json.addProperty("matchedWithoutUpdate", report.matchedWithoutUpdateCount());
        json.addProperty("actionableFiles", UpdatePlan.from(report).actionable().size());
        json.addProperty("modrinthOk", report.modrinthSuccess);
        json.addProperty("curseforgeEnabled", report.curseforgeEnabled);
        json.addProperty("curseforgeOk", report.curseforgeSuccess);
        List<Report.Update> sorted = report.updates.stream().sorted(Comparator.comparing(Report.Update::mod)
                .thenComparing(Report.Update::path).thenComparing(Report.Update::service)).toList();
        JsonArray updates = new JsonArray();
        for (Report.Update u : sorted) {
            JsonObject item = new JsonObject();
            item.addProperty("source", u.service()); item.addProperty("mod", u.mod());
            item.addProperty("installed", u.installed()); item.addProperty("available", u.available());
            item.addProperty("url", u.url()); item.addProperty("path", u.path());
            item.addProperty("releasedAt", u.releaseDate()); updates.add(item);
        }
        json.add("updates", updates);
        JsonArray files = new JsonArray();
        for (ModScanner.Jar jar : report.scanned) {
            JsonObject file = new JsonObject();
            file.addProperty("id", UpdatePlan.stableId(jar.relative()));
            file.addProperty("path", jar.relative()); file.addProperty("sha1", jar.sha1());
            file.addProperty("classification", report.hasUpdate(jar) ? "UPDATE_AVAILABLE"
                    : report.isUnresolved(jar) ? "UNRESOLVED" : report.isUpToDate(jar) ? "UP_TO_DATE" : "NOT_FOUND_BY_HASH");
            JsonObject providers = new JsonObject();
            report.outcomes.get(jar.relative()).forEach((provider, outcome) -> {
                JsonObject value = new JsonObject();
                value.addProperty("status", outcome.status().name()); value.addProperty("code", outcome.code());
                value.addProperty("installed", outcome.installed()); value.addProperty("available", outcome.available());
                value.addProperty("url", outcome.url());
                Report.Download download = report.downloads.get(jar.relative()).get(provider);
                if (download != null) {
                    JsonObject candidate = new JsonObject();
                    candidate.addProperty("projectId", download.projectId()); candidate.addProperty("fileId", download.fileId());
                    candidate.addProperty("fileName", download.fileName()); candidate.addProperty("downloadUrl", download.downloadUrl());
                    candidate.addProperty("size", download.size()); candidate.addProperty("hashAlgorithm", download.hashAlgorithm());
                    candidate.addProperty("hash", download.hash()); candidate.addProperty("version", download.version());
                    candidate.addProperty("releasedAt", download.releasedAt()); candidate.addProperty("pageUrl", download.pageUrl());
                    candidate.addProperty("actionable", download.actionable()); value.add("candidate", candidate);
                }
                providers.add(provider.name().toLowerCase(Locale.ROOT), value);
            });
            file.add("providers", providers); files.add(file);
        }
        json.add("files", files);
        JsonArray unmatched = new JsonArray();
        report.scanned.stream().filter(report::isUnknown).map(ModScanner.Jar::relative).forEach(unmatched::add);
        json.add("unmatched", unmatched);
        JsonArray unresolved = new JsonArray();
        report.scanned.stream().filter(report::isUnresolved).map(ModScanner.Jar::relative).forEach(unresolved::add);
        json.add("unresolved", unresolved);
        JsonArray warnings = new JsonArray(); report.warnings.forEach(warnings::add); json.add("warnings", warnings);
        return json;
    }

    private static void moveNew(Path temporary, Path target) throws IOException {
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target); }
    }

    private static void prune(Path directory, int keep) throws IOException {
        if (keep <= 0) return;
        List<Path> markdown;
        try (var paths = Files.list(directory)) {
            markdown = paths.filter(path -> path.getFileName().toString().matches("report-.*\\.md"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed()).toList();
        }
        for (Path old : markdown.stream().skip(keep).toList()) {
            String name = old.getFileName().toString();
            Path json = old.resolveSibling(name.substring(0, name.length() - 3) + ".json");
            if (!Files.exists(json)) continue;
            String suffix = ".prune-" + java.util.UUID.randomUUID();
            Path oldTemporary = old.resolveSibling(old.getFileName() + suffix);
            Path jsonTemporary = json.resolveSibling(json.getFileName() + suffix);
            Files.move(old, oldTemporary);
            try {
                Files.move(json, jsonTemporary);
            } catch (IOException failure) {
                Files.move(oldTemporary, old);
                throw failure;
            }
            Files.deleteIfExists(oldTemporary);
            Files.deleteIfExists(jsonTemporary);
        }
    }

    private static String cell(String value) {
        return (value == null ? "" : value).replace("|", "\\|").replace("\r", " ")
                .replace("\n", " ").replace("`", "'").replace("]", "\\]");
    }

    private static String providerCell(Report report, ModScanner.Jar jar, Report.Provider provider) {
        Report.Outcome outcome = report.outcomes.get(jar.relative()).get(provider);
        if (outcome.status() != Report.Status.UPDATE_AVAILABLE) return outcome.status().name();
        String label = cell(outcome.available());
        return outcome.url().startsWith("https://") ? "[" + label + "](" + outcome.url() + ")" : label;
    }
}
