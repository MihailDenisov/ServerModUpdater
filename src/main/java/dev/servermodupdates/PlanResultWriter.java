package dev.servermodupdates;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class PlanResultWriter {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                    (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);
    public record Written(Path markdown, Path json) { }
    private PlanResultWriter() { }

    public static synchronized Written write(Path gameDir, PlanExecutor.Execution execution, Settings settings)
            throws IOException {
        Path directory = gameDir.toAbsolutePath().normalize().resolve("modupdates/results");
        Files.createDirectories(directory);
        String base = "update-result-" + FILE_TIME.format(execution.completedAt());
        String stem = base + "-000";
        for (int collision = 1; Files.exists(directory.resolve(stem + ".md"))
                || Files.exists(directory.resolve(stem + ".json")); collision++) {
            stem = base + "-" + String.format(Locale.ROOT, "%03d", collision);
        }
        Path markdown = directory.resolve(stem + ".md");
        Path json = directory.resolve(stem + ".json");
        Path mdTemporary = Files.createTempFile(directory, stem, ".md.tmp");
        Path jsonTemporary = Files.createTempFile(directory, stem, ".json.tmp");
        boolean mdMoved = false;
        try {
            Files.writeString(mdTemporary, markdown(execution, settings), StandardCharsets.UTF_8);
            Files.writeString(jsonTemporary, GSON.toJson(execution), StandardCharsets.UTF_8);
            MarkdownPlanRepository.moveNew(mdTemporary, markdown); mdMoved = true;
            MarkdownPlanRepository.moveNew(jsonTemporary, json);
        } catch (IOException e) {
            if (mdMoved) Files.deleteIfExists(markdown);
            Files.deleteIfExists(json);
            throw e;
        } finally {
            Files.deleteIfExists(mdTemporary); Files.deleteIfExists(jsonTemporary);
        }
        return new Written(markdown, json);
    }

    private static String markdown(PlanExecutor.Execution execution, Settings settings) {
        boolean ru = settings.language() == Settings.Language.RU_RU;
        StringBuilder text = new StringBuilder(ru ? "# Результат плана обновления\n\n" : "# Update plan result\n\n")
                .append("Plan ID: `").append(execution.planId()).append("`\n\n")
                .append("Scan ID: `").append(execution.scanId()).append("`\n\n")
                .append("Started: `").append(execution.startedAt()).append("`; completed: `")
                .append(execution.completedAt()).append("` (UTC)\n\n")
                .append("| Choice | Original | Destination | Backup | Provider | Status | Message |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (PlanExecutor.Action action : execution.actions()) {
            text.append('|').append(cell(action.choiceId())).append("|`").append(cell(action.path())).append("`|")
                    .append('`').append(cell(action.destinationPath())).append("`|`").append(cell(action.backupPath())).append("`|")
                    .append(action.provider().name().toLowerCase(Locale.ROOT)).append('|').append(action.status())
                    .append('|').append(cell(action.message())).append("|\n");
        }
        if (execution.restartRequired()) text.append("\n").append(Messages.restart(settings, execution.autoModpackChanged())).append('\n');
        return text.toString();
    }

    private static String cell(String value) {
        return (value == null ? "" : value).replace("|", "\\|").replace("\r", " ").replace("\n", " ").replace("`", "'");
    }
}
