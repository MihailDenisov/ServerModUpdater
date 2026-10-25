package dev.servermodupdates;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Editable Markdown plans backed by an immutable machine-readable manifest. */
public final class MarkdownPlanRepository {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                    (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>)
                    (value, type, context) -> Instant.parse(value.getAsString()))
            .setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern SAFE_ID = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{3}");
    private static final Pattern CHECKBOX = Pattern.compile("^- \\[( |x|X)] (\\S+)(.*)$");

    public record Candidate(String choiceId, String itemId, String displayName, String installedVersion,
                            String candidateVersion, Report.Provider provider, List<String> providerLinks,
                            String relativePath, String sourceSha1, Report.Download download) { }
    public record Manifest(String planId, String scanId, Instant createdAt, String minecraft, String loader,
                           String reportMarkdown, String reportJson, List<Candidate> candidates) { }
    public record Written(String planId, Path markdown, Path manifest) { }
    public record Parsed(Manifest manifest, Path markdown, List<Candidate> selected, List<Candidate> skipped) { }
    public record Summary(String planId, Instant createdAt, int selected, int total, boolean valid) { }

    private final Path gameDir;
    private final Path plansDir;

    public MarkdownPlanRepository(Path gameDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.plansDir = this.gameDir.resolve("modupdates/plans");
    }

    public synchronized Written create(Report report, ReportWriter.Written reports) throws IOException {
        return create(report, reports, new Settings("", false, false, false));
    }

    public synchronized Written create(Report report, ReportWriter.Written reports, Settings settings) throws IOException {
        Files.createDirectories(plansDir);
        String base = FILE_TIME.format(report.time);
        String planId = base + "-000";
        for (int collision = 1; exists(planId); collision++) {
            planId = base + "-" + String.format(Locale.ROOT, "%03d", collision);
        }
        String scanId = stripExtension(reports.markdown().getFileName().toString());
        Manifest manifest = new Manifest(planId, scanId, report.time, "1.21.1", "neoforge",
                reports.markdown().getFileName().toString(), reports.json().getFileName().toString(),
                candidates(report));
        Path markdown = markdownPath(planId);
        Path json = manifestPath(planId);
        writePair(markdown, markdown(manifest, settings), json, GSON.toJson(manifest));
        return new Written(planId, markdown, json);
    }

    public Parsed parse(String planId) throws IOException {
        requireSafeId(planId);
        Path markdown = markdownPath(planId);
        Path json = manifestPath(planId);
        if (!Files.isRegularFile(markdown) || !Files.isRegularFile(json)
                || Files.isSymbolicLink(markdown) || Files.isSymbolicLink(json)) {
            throw new IOException("plan not found: " + planId);
        }
        Manifest manifest;
        try {
            manifest = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), Manifest.class);
        } catch (RuntimeException e) {
            throw new IOException("invalid plan manifest", e);
        }
        validateManifest(manifest, planId);
        Map<String, Candidate> expected = new LinkedHashMap<>();
        for (Candidate candidate : manifest.candidates()) {
            if (expected.put(candidate.choiceId(), candidate) != null) throw new IOException("duplicate manifest choice ID");
        }
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        requireHeader(text, "Plan ID: `" + manifest.planId() + "`");
        requireHeader(text, "Scan ID: `" + manifest.scanId() + "`");
        requireHeader(text, "Created: `" + manifest.createdAt() + "` (UTC)");
        requireHeader(text, "Minecraft: `" + manifest.minecraft() + "`; loader: `" + manifest.loader() + "`");
        requireHeader(text, "Reports: `" + manifest.reportMarkdown() + "`, `" + manifest.reportJson() + "`");
        List<Candidate> selected = new ArrayList<>();
        List<Candidate> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith("- [") && !CHECKBOX.matcher(line).matches()) {
                throw new IOException("malformed checkbox line");
            }
            Matcher matcher = CHECKBOX.matcher(line);
            if (!matcher.matches()) continue;
            String choiceId = matcher.group(2);
            Candidate candidate = expected.get(choiceId);
            if (candidate == null) throw new IOException("unknown checkbox ID: " + choiceId);
            if (!seen.add(choiceId)) throw new IOException("duplicate checkbox ID: " + choiceId);
            String canonical = checkbox(candidate, false);
            String normalized = "- [ ] " + choiceId + matcher.group(3);
            if (!canonical.equals(normalized)) throw new IOException("tampered immutable plan fields for " + choiceId);
            if (matcher.group(1).equalsIgnoreCase("x")) selected.add(candidate); else skipped.add(candidate);
        }
        if (!seen.equals(expected.keySet())) throw new IOException("plan checkbox entries are missing");
        return new Parsed(manifest, markdown, List.copyOf(selected), List.copyOf(skipped));
    }

    public List<Summary> list() throws IOException {
        if (!Files.isDirectory(plansDir)) return List.of();
        List<Path> manifests;
        try (var paths = Files.list(plansDir)) {
            manifests = paths.filter(path -> path.getFileName().toString()
                            .matches("update-plan-[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{3}\\.json"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed()).toList();
        }
        List<Summary> summaries = new ArrayList<>();
        for (Path path : manifests) {
            String file = path.getFileName().toString();
            String id = file.substring("update-plan-".length(), file.length() - ".json".length());
            try {
                Parsed parsed = parse(id);
                summaries.add(new Summary(id, parsed.manifest().createdAt(), parsed.selected().size(),
                        parsed.manifest().candidates().size(), true));
            } catch (IOException e) {
                summaries.add(new Summary(id, Instant.EPOCH, 0, 0, false));
            }
        }
        return List.copyOf(summaries);
    }

    Path statePath(String planId) throws IOException {
        requireSafeId(planId);
        return plansDir.resolve("update-plan-" + planId + ".state.json");
    }

    private List<Candidate> candidates(Report report) {
        List<Candidate> result = new ArrayList<>();
        for (UpdatePlan.Item item : UpdatePlan.from(report).actionable()) {
            List<Map.Entry<Report.Provider, Report.Download>> providers = item.providers().entrySet().stream()
                    .filter(entry -> entry.getValue().actionable())
                    .sorted(Map.Entry.comparingByKey()).toList();
            Map<String, List<Map.Entry<Report.Provider, Report.Download>>> byVersion = new LinkedHashMap<>();
            for (var entry : providers) byVersion.computeIfAbsent(entry.getValue().version(), ignored -> new ArrayList<>()).add(entry);
            int variant = 0;
            for (var group : byVersion.values()) {
                var selected = group.stream().filter(entry -> entry.getKey() == Report.Provider.MODRINTH)
                        .findFirst().orElse(group.getFirst());
                String choiceId = item.id() + (byVersion.size() == 1 ? "-UPDATE" : "-" + (++variant));
                List<String> links = group.stream().map(entry -> entry.getValue().pageUrl())
                        .filter(value -> value != null && value.startsWith("https://")).distinct().toList();
                result.add(new Candidate(choiceId, item.id(), displayName(report, item, selected.getKey()),
                        installedVersion(report, item, selected.getKey()), selected.getValue().version(), selected.getKey(),
                        links, item.installed().relative(), item.installed().sha1(), selected.getValue()));
            }
        }
        return List.copyOf(result);
    }

    private static String displayName(Report report, UpdatePlan.Item item, Report.Provider provider) {
        return report.updates.stream().filter(update -> update.path().equals(item.installed().relative())
                && update.service().equalsIgnoreCase(provider == Report.Provider.MODRINTH ? "Modrinth" : "CurseForge"))
                .map(Report.Update::mod).findFirst().orElse(item.installed().absolute().getFileName().toString());
    }

    private static String installedVersion(Report report, UpdatePlan.Item item, Report.Provider provider) {
        Report.Outcome outcome = report.outcomes.get(item.installed().relative()).get(provider);
        return outcome == null ? "" : outcome.installed();
    }

    private String markdown(Manifest manifest, Settings settings) {
        boolean ru = settings.language() == Settings.Language.RU_RU;
        StringBuilder text = new StringBuilder(ru ? "# Выборочный план обновления ServerModUpdater\n\n"
                : "# ServerModUpdater selective update plan\n\n")
                .append("Plan ID: `").append(manifest.planId()).append("`\n\n")
                .append("Scan ID: `").append(manifest.scanId()).append("`\n\n")
                .append("Created: `").append(manifest.createdAt()).append("` (UTC)\n\n")
                .append("Minecraft: `").append(manifest.minecraft()).append("`; loader: `").append(manifest.loader()).append("`\n\n")
                .append("Reports: `").append(manifest.reportMarkdown()).append("`, `").append(manifest.reportJson()).append("`\n\n")
                .append(ru ? "Изменяйте только флажки: `[ ]` на `[x]`. По умолчанию все обновления не отмечены.\n\n"
                        : "Edit checkbox markers only: change `[ ]` to `[x]`. All updates are unchecked by default.\n\n");
        if (manifest.candidates().isEmpty()) text.append(ru ? "Нет проверенных доступных обновлений.\n"
                : "No verified actionable updates.\n");
        for (Candidate candidate : manifest.candidates()) text.append(checkbox(candidate, false)).append('\n');
        return text.toString();
    }

    private static String checkbox(Candidate candidate, boolean selected) {
        return "- [" + (selected ? "x" : " ") + "] " + candidate.choiceId()
                + " | item=" + candidate.itemId() + " | name=" + safe(candidate.displayName())
                + " | installed=" + safe(candidate.installedVersion()) + " | candidate=" + safe(candidate.candidateVersion())
                + " | provider=" + candidate.provider().name().toLowerCase(Locale.ROOT)
                + " | path=`" + safe(candidate.relativePath()) + "` | links=" + String.join(",", candidate.providerLinks());
    }

    private static String safe(String value) {
        return (value == null ? "" : value).replace("\r", " ").replace("\n", " ").replace("|", "/").replace("`", "'");
    }

    private void validateManifest(Manifest manifest, String expectedId) throws IOException {
        if (manifest == null || !expectedId.equals(manifest.planId()) || !SAFE_ID.matcher(manifest.planId()).matches()
                || manifest.scanId() == null || manifest.createdAt() == null || !"1.21.1".equals(manifest.minecraft())
                || !"neoforge".equals(manifest.loader()) || manifest.candidates() == null) {
            throw new IOException("invalid plan manifest identity");
        }
        for (Candidate candidate : manifest.candidates()) {
            if (candidate == null || candidate.choiceId() == null || candidate.itemId() == null
                    || candidate.provider() == null || candidate.relativePath() == null || candidate.sourceSha1() == null
                    || candidate.download() == null || !candidate.download().actionable()) {
                throw new IOException("invalid plan candidate manifest");
            }
            Path resolved = gameDir.resolve(candidate.relativePath()).normalize();
            if (!resolved.startsWith(gameDir) || Path.of(candidate.relativePath()).isAbsolute()) {
                throw new IOException("plan candidate path escapes the game directory");
            }
        }
    }

    private static void requireHeader(String text, String header) throws IOException {
        if (text.lines().filter(header::equals).count() != 1) throw new IOException("missing or tampered plan metadata: " + header);
    }

    private boolean exists(String planId) { return Files.exists(markdownPath(planId)) || Files.exists(manifestPath(planId)); }
    private Path markdownPath(String planId) { return plansDir.resolve("update-plan-" + planId + ".md"); }
    private Path manifestPath(String planId) { return plansDir.resolve("update-plan-" + planId + ".json"); }

    private static void requireSafeId(String planId) throws IOException {
        if (planId == null || !SAFE_ID.matcher(planId).matches()) throw new IOException("invalid plan ID");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void writePair(Path first, String firstText, Path second, String secondText) throws IOException {
        Path firstTmp = Files.createTempFile(first.getParent(), first.getFileName().toString(), ".tmp");
        Path secondTmp = Files.createTempFile(second.getParent(), second.getFileName().toString(), ".tmp");
        boolean firstMoved = false;
        try {
            Files.writeString(firstTmp, firstText, StandardCharsets.UTF_8);
            Files.writeString(secondTmp, secondText, StandardCharsets.UTF_8);
            moveNew(firstTmp, first); firstMoved = true;
            moveNew(secondTmp, second);
        } catch (IOException e) {
            if (firstMoved) Files.deleteIfExists(first);
            Files.deleteIfExists(second);
            throw e;
        } finally {
            Files.deleteIfExists(firstTmp); Files.deleteIfExists(secondTmp);
        }
    }

    static void moveNew(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
    }
}
