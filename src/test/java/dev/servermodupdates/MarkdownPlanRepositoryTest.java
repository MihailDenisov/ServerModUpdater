package dev.servermodupdates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownPlanRepositoryTest {
    @TempDir Path root;

    @Test
    void newPlanIsUncheckedAndProviderDuplicatesCollapseWithoutLosingLinks() throws Exception {
        Fixture fixture = fixture(true);
        MarkdownPlanRepository.Parsed parsed = fixture.repository.parse(fixture.written.planId());
        assertEquals(0, parsed.selected().size());
        assertEquals(2, parsed.skipped().size());
        String markdown = Files.readString(fixture.written.markdown());
        assertFalse(markdown.contains("- [x]"));
        MarkdownPlanRepository.Candidate first = parsed.skipped().getFirst();
        assertEquals(2, first.providerLinks().size());
        assertEquals(Report.Provider.MODRINTH, first.provider());
        assertTrue(Files.isRegularFile(root.resolve("mods/a.jar")));
        assertFalse(Files.exists(root.resolve("mods/a-new.jar")));
    }

    @Test
    void reorderedCheckboxesUseStableIdsAndTamperingOrDuplicatesFailClosed() throws Exception {
        Fixture fixture = fixture(false);
        List<String> lines = new ArrayList<>(Files.readAllLines(fixture.written.markdown()));
        List<Integer> checkboxLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith("- [")) checkboxLines.add(i);
        String first = lines.get(checkboxLines.get(0));
        lines.set(checkboxLines.get(0), lines.get(checkboxLines.get(1)));
        lines.set(checkboxLines.get(1), first);
        Files.write(fixture.written.markdown(), lines, StandardCharsets.UTF_8);
        assertEquals(2, fixture.repository.parse(fixture.written.planId()).skipped().size());

        lines = Files.readAllLines(fixture.written.markdown());
        int checkbox = checkboxLines.get(0);
        lines.set(checkbox, lines.get(checkbox).replace("candidate=2", "candidate=999"));
        Files.write(fixture.written.markdown(), lines, StandardCharsets.UTF_8);
        Fixture tamperedFixture = fixture;
        assertThrows(IOException.class, () -> tamperedFixture.repository.parse(tamperedFixture.written.planId()));

        fixture = fixture(false);
        lines = new ArrayList<>(Files.readAllLines(fixture.written.markdown()));
        String duplicate = lines.stream().filter(line -> line.startsWith("- [")).findFirst().orElseThrow();
        lines.add(duplicate);
        Files.write(fixture.written.markdown(), lines, StandardCharsets.UTF_8);
        Fixture finalFixture = fixture;
        assertThrows(IOException.class, () -> finalFixture.repository.parse(finalFixture.written.planId()));
    }

    @Test
    void exactlyOneCheckedEntryChangesOnlyItsTargetAndRepeatIsIdempotent() throws Exception {
        Fixture fixture = fixture(false);
        byte[] untouched = Files.readAllBytes(root.resolve("mods/b.jar"));
        MarkdownPlanRepository.Parsed parsed = fixture.repository.parse(fixture.written.planId());
        String selectedId = parsed.skipped().getFirst().choiceId();
        String markdown = Files.readString(fixture.written.markdown())
                .replace("- [ ] " + selectedId, "- [x] " + selectedId);
        Files.writeString(fixture.written.markdown(), markdown, StandardCharsets.UTF_8);

        JarUpdateService.Downloader downloader = (URI uri, Path destination, long maximum) -> {
            Path candidate = uri.getPath().endsWith("a-new.jar") ? fixture.candidateA : fixture.candidateB;
            Files.copy(candidate, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Files.size(destination);
        };
        Settings settings = new Settings("", false, false, false);
        PlanExecutor executor = new PlanExecutor(root, settings, fixture.repository, download -> { },
                new JarUpdateService(root, downloader, settings));
        PlanExecutor.Execution execution = executor.apply(fixture.written.planId(), () -> false);
        assertEquals(1, execution.actions().stream().filter(action -> action.status() == PlanExecutor.Status.INSTALLED).count());
        assertArrayEquals(Files.readAllBytes(fixture.candidateA), Files.readAllBytes(root.resolve("mods/a-new.jar")));
        assertTrue(Files.isRegularFile(root.resolve("mods/a.jar.old")));
        assertArrayEquals(untouched, Files.readAllBytes(root.resolve("mods/b.jar")));
        assertFalse(Files.exists(root.resolve("mods/b.jar.old")));

        PlanExecutor.Execution repeated = executor.apply(fixture.written.planId(), () -> false);
        assertEquals(1, repeated.actions().stream()
                .filter(action -> action.status() == PlanExecutor.Status.ALREADY_APPLIED).count());
        assertArrayEquals(untouched, Files.readAllBytes(root.resolve("mods/b.jar")));
    }

    @Test
    void staleSelectedFileAndCancelledNextItemRemainUntouched() throws Exception {
        Fixture fixture = fixture(false);
        MarkdownPlanRepository.Parsed parsed = fixture.repository.parse(fixture.written.planId());
        String markdown = Files.readString(fixture.written.markdown());
        for (MarkdownPlanRepository.Candidate candidate : parsed.skipped()) {
            markdown = markdown.replace("- [ ] " + candidate.choiceId(), "- [x] " + candidate.choiceId());
        }
        Files.writeString(fixture.written.markdown(), markdown);
        Files.writeString(root.resolve("mods/a.jar"), "changed after scan");
        byte[] b = Files.readAllBytes(root.resolve("mods/b.jar"));
        PlanExecutor executor = new PlanExecutor(root, new Settings("", false, false, false), fixture.repository,
                download -> { }, new JarUpdateService(root, (uri, destination, maximum) -> 0));
        PlanExecutor.Preview preview = executor.preview(fixture.written.planId());
        assertTrue(preview.actions().stream().anyMatch(action -> action.status() == PlanExecutor.Status.STALE));
        PlanExecutor.Execution cancelled = executor.apply(fixture.written.planId(), () -> true);
        assertTrue(cancelled.actions().stream().allMatch(action -> action.status() == PlanExecutor.Status.CANCELLED));
        assertArrayEquals(b, Files.readAllBytes(root.resolve("mods/b.jar")));
    }

    @Test
    void partialDownloadFailureRecordsPerItemAndPreservesFailedSource() throws Exception {
        Fixture fixture = fixture(false);
        MarkdownPlanRepository.Parsed parsed = fixture.repository.parse(fixture.written.planId());
        String markdown = Files.readString(fixture.written.markdown());
        for (MarkdownPlanRepository.Candidate candidate : parsed.skipped()) {
            markdown = markdown.replace("- [ ] " + candidate.choiceId(), "- [x] " + candidate.choiceId());
        }
        Files.writeString(fixture.written.markdown(), markdown);
        byte[] originalB = Files.readAllBytes(root.resolve("mods/b.jar"));
        Settings settings = new Settings("", false, false, false);
        JarUpdateService.Downloader downloader = (uri, destination, maximum) -> {
            if (uri.getPath().endsWith("b-new.jar")) throw new IOException("simulated unavailable download");
            Files.copy(fixture.candidateA, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Files.size(destination);
        };
        PlanExecutor executor = new PlanExecutor(root, settings, fixture.repository, download -> { },
                new JarUpdateService(root, downloader, settings));
        PlanExecutor.Execution result = executor.apply(fixture.written.planId(), () -> false);
        assertTrue(result.actions().stream().anyMatch(action -> action.status() == PlanExecutor.Status.INSTALLED));
        assertTrue(result.actions().stream().anyMatch(action -> action.status() == PlanExecutor.Status.FAILED));
        assertArrayEquals(originalB, Files.readAllBytes(root.resolve("mods/b.jar")));
        assertFalse(Files.exists(root.resolve("mods/b.jar.old")));
    }

    @Test
    void autoModpackReminderDependsOnChangedPathNotOnlyConfiguration() {
        Settings enabled = new Settings("", false, false, false, 2, 500, 10_000,
                25, 180, true, Settings.Language.EN_US, 0);
        assertFalse(Messages.restart(enabled, false).contains("/amp generate"));
        assertTrue(Messages.restart(enabled, true).contains("/amp generate"));
    }

    @Test
    void resultPairsNeverOverwrite() throws Exception {
        PlanExecutor.Execution execution = new PlanExecutor.Execution("p", "s", java.time.Instant.now(),
                java.time.Instant.now(), List.of(), false, false);
        Settings settings = new Settings("", false, false, false);
        PlanResultWriter.Written first = PlanResultWriter.write(root, execution, settings);
        PlanResultWriter.Written second = PlanResultWriter.write(root, execution, settings);
        assertNotEquals(first.markdown(), second.markdown());
        assertTrue(Files.isRegularFile(first.json()));
        assertTrue(Files.isRegularFile(second.json()));
    }

    private Fixture fixture(boolean duplicateProvider) throws Exception {
        Path a = jar(root.resolve("mods/a.jar"), "old-a");
        Path b = jar(root.resolve("mods/b.jar"), "old-b");
        Path candidateA = jar(root.resolve("candidates/a-new.jar"), "new-a");
        Path candidateB = jar(root.resolve("candidates/b-new.jar"), "new-b");
        List<ModScanner.Jar> installed = ModScanner.scan(root, false, false, false,
                Settings.Language.EN_US, warning -> fail(warning));
        Report report = new Report();
        report.scanned.addAll(installed);
        report.curseforgeEnabled = duplicateProvider;
        report.initializeOutcomes();
        for (ModScanner.Jar item : installed) {
            Path candidate = item.relative().endsWith("a.jar") ? candidateA : candidateB;
            String name = candidate.getFileName().toString();
            Report.Download modrinth = download(Report.Provider.MODRINTH, candidate, name,
                    "https://modrinth.com/mod/example/version/v2");
            report.set(Report.Provider.MODRINTH, item, new Report.Outcome(Report.Status.UPDATE_AVAILABLE,
                    "NEW", "1", "2", modrinth.pageUrl()));
            report.setDownload(Report.Provider.MODRINTH, item, modrinth);
            report.updates.add(new Report.Update("Modrinth", item.relative(), "1", "2", modrinth.pageUrl(),
                    item.relative(), "2026-01-01T00:00:00Z"));
            if (duplicateProvider && item.relative().endsWith("a.jar")) {
                Report.Download curse = download(Report.Provider.CURSEFORGE, candidate, name,
                        "https://www.curseforge.com/minecraft/mc-mods/example/files/2");
                report.set(Report.Provider.CURSEFORGE, item, new Report.Outcome(Report.Status.UPDATE_AVAILABLE,
                        "NEW", "1", "2", curse.pageUrl()));
                report.setDownload(Report.Provider.CURSEFORGE, item, curse);
            } else {
                report.set(Report.Provider.CURSEFORGE, item, Report.Status.SKIPPED, "NO_KEY");
            }
        }
        ReportWriter.Written reports = ReportWriter.write(root, report, new Settings("", false, false, false));
        MarkdownPlanRepository repository = new MarkdownPlanRepository(root);
        MarkdownPlanRepository.Written written = repository.create(report, reports);
        return new Fixture(repository, written, candidateA, candidateB);
    }

    private static Report.Download download(Report.Provider provider, Path candidate, String name, String link)
            throws Exception {
        return new Report.Download(provider, provider == Report.Provider.MODRINTH ? "project" : "77", "file2", name,
                provider == Report.Provider.MODRINTH ? "https://cdn.modrinth.com/" + name
                        : "https://mediafilez.forgecdn.net/files/1/2/" + name,
                Files.size(candidate), "sha1", sha1(candidate), "2", "2026-01-01T00:00:00Z", link);
    }

    private static Path jar(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("data.txt"));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static String sha1(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path)));
    }

    private record Fixture(MarkdownPlanRepository repository, MarkdownPlanRepository.Written written,
                           Path candidateA, Path candidateB) { }
}
