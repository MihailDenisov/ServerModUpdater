package dev.servermodupdates;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UpdatePlanTest {
    @Test
    void mergesProvidersPerPathButKeepsDistinctPathsAndUnmatchedProtected() {
        Report report = new Report();
        ModScanner.Jar first = new ModScanner.Jar(Path.of("C:/server/mods/a.jar"), "mods/a.jar", "same", 1);
        ModScanner.Jar second = new ModScanner.Jar(Path.of("C:/server/automodpack/host-modpack/g/mods/a.jar"),
                "automodpack/host-modpack/g/mods/a.jar", "same", 1);
        ModScanner.Jar unmatched = new ModScanner.Jar(Path.of("C:/server/mods/private.jar"), "mods/private.jar", "x", 2);
        report.scanned.add(first);
        report.scanned.add(second);
        report.scanned.add(unmatched);
        report.curseforgeEnabled = true;
        report.initializeOutcomes();
        report.set(Report.Provider.MODRINTH, first, new Report.Outcome(Report.Status.UPDATE_AVAILABLE,
                "NEW", "1", "2", "https://modrinth.example/project"));
        report.set(Report.Provider.CURSEFORGE, first, new Report.Outcome(Report.Status.UPDATE_AVAILABLE,
                "NEW", "one", "two", "https://curseforge.example/project"));
        report.setDownload(Report.Provider.MODRINTH, first, download(Report.Provider.MODRINTH, "a-2.jar"));
        report.setDownload(Report.Provider.CURSEFORGE, first, download(Report.Provider.CURSEFORGE, "a-2-cf.jar"));
        report.set(Report.Provider.MODRINTH, second, Report.Status.UP_TO_DATE, "CURRENT");
        report.set(Report.Provider.CURSEFORGE, second, Report.Status.UP_TO_DATE, "CURRENT");
        report.set(Report.Provider.MODRINTH, unmatched, Report.Status.NOT_FOUND_BY_HASH, "HASH_NOT_FOUND");
        report.set(Report.Provider.CURSEFORGE, unmatched, Report.Status.NOT_FOUND_BY_HASH, "HASH_NOT_FOUND");

        UpdatePlan plan = UpdatePlan.from(report);
        assertEquals(3, plan.items().size());
        assertEquals(1, plan.actionable().size());
        assertEquals(2, plan.actionable().getFirst().providers().size());
        assertNotEquals(plan.items().get(0).id(), plan.items().get(1).id());
        assertTrue(plan.find(plan.items().get(2).id()).orElseThrow().providers().isEmpty());
    }

    @Test
    void confirmationIsShortLivedCaseInsensitiveAndSingleUse() {
        UpdatePlan plan = UpdatePlan.from(new Report());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UpdatePlan.Confirmation token = plan.issueConfirmation(now);
        assertTrue(plan.consumeConfirmation(token.token().toLowerCase(), now.plusSeconds(299)));
        assertFalse(plan.consumeConfirmation(token.token(), now.plusSeconds(299)));
        UpdatePlan.Confirmation expired = plan.issueConfirmation(now);
        assertFalse(plan.consumeConfirmation(expired.token(), now.plusSeconds(301)));
    }

    private static Report.Download download(Report.Provider provider, String name) {
        return new Report.Download(provider, "project", "file", name, "https://cdn.example/" + name,
                10, "sha1", "abc", "2", "2026-01-01T00:00:00Z", "https://example/project");
    }
}
