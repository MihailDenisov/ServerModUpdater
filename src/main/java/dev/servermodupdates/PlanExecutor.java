package dev.servermodupdates;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Validates and executes a persisted Markdown selection without trusting Markdown data fields. */
public final class PlanExecutor {
    @FunctionalInterface
    public interface CandidateValidator {
        void validate(Report.Download download) throws IOException, InterruptedException;
    }

    public enum Status { READY, SKIPPED, ALREADY_APPLIED, STALE, INVALID, UNAVAILABLE, CONFLICT, INSTALLED, STATE_ERROR, FAILED, CANCELLED }
    public record Action(String choiceId, String itemId, String path, String destinationPath, String backupPath,
                         Report.Provider provider,
                         Status status, String message, boolean changed, boolean autoModpack) { }
    public record Preview(String planId, int selected, int skipped, List<Action> actions) {
        public boolean valid() { return actions.stream().noneMatch(action -> switch (action.status()) {
            case STALE, INVALID, UNAVAILABLE, CONFLICT, STATE_ERROR, CANCELLED -> true;
            default -> false;
        }); }
    }
    public record Execution(String planId, String scanId, Instant startedAt, Instant completedAt,
                            List<Action> actions, boolean restartRequired, boolean autoModpackChanged) { }
    private record State(Set<String> completedChoices, Set<String> completedItems) { }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path gameDir;
    private final Settings settings;
    private final MarkdownPlanRepository repository;
    private final CandidateValidator validator;
    private final JarUpdateService service;

    public PlanExecutor(Path gameDir, Settings settings, MarkdownPlanRepository repository) {
        this(gameDir, settings, repository, new CandidateAvailabilityValidator(settings)::validate,
                new JarUpdateService(gameDir, settings));
    }

    PlanExecutor(Path gameDir, Settings settings, MarkdownPlanRepository repository,
                 CandidateValidator validator, JarUpdateService service) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.settings = settings;
        this.repository = repository;
        this.validator = validator;
        this.service = service;
    }

    public Preview preview(String planId) throws IOException, InterruptedException {
        return preview(planId, () -> false);
    }

    public Preview preview(String planId, BooleanSupplier cancelled) throws IOException, InterruptedException {
        MarkdownPlanRepository.Parsed parsed = repository.parse(planId);
        State state = loadState(planId);
        Map<String, Long> selectedPerItem = parsed.selected().stream()
                .collect(java.util.stream.Collectors.groupingBy(MarkdownPlanRepository.Candidate::itemId,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
        List<Action> actions = new ArrayList<>();
        for (MarkdownPlanRepository.Candidate candidate : parsed.selected()) {
            if (cancelled.getAsBoolean()) {
                actions.add(action(candidate, Status.CANCELLED, "cancelled before this validation", false));
            } else if (selectedPerItem.get(candidate.itemId()) > 1) {
                actions.add(action(candidate, Status.CONFLICT, "multiple provider candidates selected for one installed file", false));
            } else if (state.completedChoices().contains(candidate.choiceId())
                    || state.completedItems().contains(candidate.itemId())) {
                actions.add(action(candidate, Status.ALREADY_APPLIED, "selection was already applied", false));
            } else {
                actions.add(validate(candidate));
            }
        }
        for (MarkdownPlanRepository.Candidate candidate : parsed.skipped()) {
            actions.add(action(candidate, Status.SKIPPED, "unchecked; file will not be touched", false));
        }
        return new Preview(planId, parsed.selected().size(), parsed.skipped().size(), List.copyOf(actions));
    }

    public Execution apply(String planId, BooleanSupplier cancelled) throws IOException, InterruptedException {
        MarkdownPlanRepository.Parsed parsed = repository.parse(planId);
        Instant started = Instant.now();
        State state = loadState(planId);
        Set<String> completedChoices = new LinkedHashSet<>(state.completedChoices());
        Set<String> completedItems = new LinkedHashSet<>(state.completedItems());
        Map<String, Long> selectedPerItem = parsed.selected().stream()
                .collect(java.util.stream.Collectors.groupingBy(MarkdownPlanRepository.Candidate::itemId,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
        List<Action> actions = new ArrayList<>();
        for (MarkdownPlanRepository.Candidate candidate : parsed.selected()) {
            if (cancelled.getAsBoolean()) {
                actions.add(action(candidate, Status.CANCELLED, "cancelled before this safe item operation", false));
                continue;
            }
            if (selectedPerItem.get(candidate.itemId()) > 1) {
                actions.add(action(candidate, Status.CONFLICT, "multiple provider candidates selected for one installed file", false));
                continue;
            }
            if (completedChoices.contains(candidate.choiceId()) || completedItems.contains(candidate.itemId())) {
                actions.add(action(candidate, Status.ALREADY_APPLIED, "selection was already applied", false));
                continue;
            }
            Action validation = validate(candidate); // Rechecks provider metadata immediately before download.
            if (validation.status() == Status.ALREADY_APPLIED) {
                actions.add(validation);
                completedChoices.add(candidate.choiceId());
                completedItems.add(candidate.itemId());
                saveState(planId, new State(Set.copyOf(completedChoices), Set.copyOf(completedItems)));
                continue;
            }
            if (validation.status() != Status.READY) {
                actions.add(validation);
                continue;
            }
            UpdatePlan.Item item = item(candidate);
            JarUpdateService.Result result = service.apply(item, candidate.provider());
            Action applied = action(candidate, result.success()
                            ? result.changed() ? Status.INSTALLED : Status.ALREADY_APPLIED : Status.FAILED,
                    result.message(), result.changed());
            actions.add(applied);
            if (result.success()) {
                completedChoices.add(candidate.choiceId());
                completedItems.add(candidate.itemId());
                try {
                    saveState(planId, new State(Set.copyOf(completedChoices), Set.copyOf(completedItems)));
                } catch (IOException stateFailure) {
                    actions.set(actions.size() - 1, action(candidate, Status.STATE_ERROR,
                            result.message() + "; durable state write failed: " + safe(stateFailure), result.changed()));
                }
            }
        }
        for (MarkdownPlanRepository.Candidate candidate : parsed.skipped()) {
            actions.add(action(candidate, Status.SKIPPED, "unchecked; file was not touched", false));
        }
        boolean changed = actions.stream().anyMatch(Action::changed);
        boolean auto = actions.stream().anyMatch(action -> action.changed() && action.autoModpack());
        return new Execution(planId, parsed.manifest().scanId(), started, Instant.now(), List.copyOf(actions), changed, auto);
    }

    private Action validate(MarkdownPlanRepository.Candidate candidate) throws InterruptedException {
        Path source;
        try {
            source = resolve(candidate.relativePath());
            if (!allowed(source)) {
                return action(candidate, Status.INVALID, "source path is outside configured mod roots", false);
            }
            for (Path cursor = source.getParent(); cursor != null && cursor.startsWith(gameDir); cursor = cursor.getParent()) {
                if (Files.isSymbolicLink(cursor)) {
                    return action(candidate, Status.INVALID, "symbolic links are forbidden in the target path", false);
                }
                if (cursor.equals(gameDir)) break;
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                UpdatePlan.Item item = item(candidate);
                if (service.isAlreadyApplied(item, candidate.provider())) {
                    return action(candidate, Status.ALREADY_APPLIED, "installed candidate and original backup are already verified", false);
                }
                return action(candidate, Status.STALE, "source JAR is missing, linked, or outside configured mod roots", false);
            }
            if (Files.isSymbolicLink(source)) return action(candidate, Status.STALE, "source JAR is a symbolic link", false);
            if (!sha1(source).equalsIgnoreCase(candidate.sourceSha1())) {
                return action(candidate, Status.STALE, "source JAR hash no longer matches the scan", false);
            }
            if (!candidate.download().actionable() || candidate.download().provider() != candidate.provider()
                    || candidate.download().projectId().isBlank() || candidate.download().fileId().isBlank()) {
                return action(candidate, Status.INVALID, "immutable download metadata is incomplete", false);
            }
            validator.validate(candidate.download());
            return action(candidate, Status.READY, "verified candidate; target=" + candidate.relativePath()
                    + "; file=" + candidate.download().fileName(), false);
        } catch (IOException e) {
            return action(candidate, Status.UNAVAILABLE, safe(e), false);
        }
    }

    private UpdatePlan.Item item(MarkdownPlanRepository.Candidate candidate) throws IOException {
        Path source = resolve(candidate.relativePath());
        ModScanner.Jar jar = new ModScanner.Jar(source, candidate.relativePath(), candidate.sourceSha1(), 0);
        return new UpdatePlan.Item(candidate.itemId(), jar, Map.of(candidate.provider(), candidate.download()));
    }

    private Path resolve(String relative) throws IOException {
        Path value = Path.of(relative);
        if (value.isAbsolute()) throw new IOException("absolute path in plan manifest");
        Path resolved = gameDir.resolve(value).normalize();
        if (!resolved.startsWith(gameDir)) throw new IOException("path escapes game directory");
        return resolved;
    }

    private boolean allowed(Path source) {
        if (source.startsWith(gameDir.resolve("mods"))) return true;
        return settings.enableAutoModpack() && (source.startsWith(gameDir.resolve("automodpack/host-modpack"))
                || source.startsWith(gameDir.resolve("automodpack/host-midpack")));
    }

    private Action action(MarkdownPlanRepository.Candidate candidate, Status status, String message, boolean changed) {
        Path relative = Path.of(candidate.relativePath());
        String destination = relative.resolveSibling(candidate.download().fileName()).toString().replace('\\', '/');
        String backup = candidate.relativePath().replace('\\', '/') + ".old";
        return new Action(candidate.choiceId(), candidate.itemId(), candidate.relativePath(), destination, backup, candidate.provider(),
                status, message, changed, candidate.relativePath().replace('\\', '/').startsWith("automodpack/"));
    }

    private State loadState(String planId) throws IOException {
        Path path = repository.statePath(planId);
        if (!Files.exists(path)) return new State(Set.of(), Set.of());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("invalid plan state file");
        }
        try {
            State state = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), State.class);
            return state == null ? new State(Set.of(), Set.of())
                    : new State(state.completedChoices() == null ? Set.of() : Set.copyOf(state.completedChoices()),
                    state.completedItems() == null ? Set.of() : Set.copyOf(state.completedItems()));
        } catch (RuntimeException e) {
            throw new IOException("invalid plan state file", e);
        }
    }

    private void saveState(String planId, State state) throws IOException {
        Path path = repository.statePath(planId);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String safe(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replaceAll("(?i)https://\\S+", "<redacted-url>")
                .replaceAll("(?i)(api[_-]?key|token|signature)=[^\\s&]+", "$1=<redacted>");
    }
}
