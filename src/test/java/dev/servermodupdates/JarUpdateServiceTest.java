package dev.servermodupdates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JarUpdateServiceTest {
    @TempDir Path root;

    @Test
    void verifiedReplacementCreatesBackupAndRollbackRestoresOriginal() throws Exception {
        Path original = jar(root.resolve("mods/old.jar"), "old.txt", "old");
        Path candidate = jar(root.resolve("candidate.jar"), "new.txt", "new");
        byte[] oldBytes = Files.readAllBytes(original);
        byte[] newBytes = Files.readAllBytes(candidate);
        UpdatePlan.Item item = item(original, candidate, "new.jar");
        JarUpdateService service = new JarUpdateService(root, copyDownloader(candidate));

        JarUpdateService.Result applied = service.apply(item, Report.Provider.MODRINTH);
        assertTrue(applied.success());
        assertTrue(applied.changed());
        assertArrayEquals(oldBytes, Files.readAllBytes(root.resolve("mods/old.jar.old")));
        assertArrayEquals(newBytes, Files.readAllBytes(root.resolve("mods/new.jar")));
        assertFalse(Files.exists(original));

        JarUpdateService.Result rollback = service.rollback(item);
        assertTrue(rollback.success());
        assertTrue(rollback.changed());
        assertArrayEquals(oldBytes, Files.readAllBytes(original));
        assertFalse(Files.exists(root.resolve("mods/new.jar")));
        assertFalse(Files.exists(root.resolve("mods/old.jar.old")));
    }

    @Test
    void badChecksumAndExistingBackupLeaveOriginalUntouched() throws Exception {
        Path original = jar(root.resolve("mods/old.jar"), "old.txt", "old");
        Path candidate = jar(root.resolve("candidate.jar"), "new.txt", "new");
        byte[] oldBytes = Files.readAllBytes(original);
        UpdatePlan.Item valid = item(original, candidate, "new.jar");
        Report.Download bad = new Report.Download(Report.Provider.MODRINTH, "p", "f", "new.jar",
                "https://cdn.modrinth.com/new.jar", Files.size(candidate), "sha1", "0000", "2",
                "2026-01-01T00:00:00Z", "https://example/p");
        UpdatePlan.Item invalid = new UpdatePlan.Item(valid.id(), valid.installed(), Map.of(Report.Provider.MODRINTH, bad));
        JarUpdateService service = new JarUpdateService(root, copyDownloader(candidate));
        assertFalse(service.apply(invalid, Report.Provider.MODRINTH).success());
        assertArrayEquals(oldBytes, Files.readAllBytes(original));

        Files.writeString(root.resolve("mods/old.jar.old"), "collision");
        assertFalse(service.apply(valid, Report.Provider.MODRINTH).success());
        assertArrayEquals(oldBytes, Files.readAllBytes(original));
        assertFalse(Files.exists(root.resolve("mods/new.jar")));
    }

    @Test
    void rejectsInvalidZipAndUnsafeEntry() throws Exception {
        Path invalid = root.resolve("invalid.jar");
        Files.writeString(invalid, "not a zip");
        assertThrows(IOException.class, () -> JarUpdateService.verifyJar(invalid));
        Path unsafe = root.resolve("unsafe.jar");
        Files.createDirectories(unsafe.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unsafe))) {
            zip.putNextEntry(new ZipEntry("../escape"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> JarUpdateService.verifyJar(unsafe));
    }

    @Test
    void startupRecoveryRestoresInterruptedPreparedMove() throws Exception {
        Path source = jar(root.resolve("mods/old.jar"), "old.txt", "old");
        Path backup = source.resolveSibling("old.jar.old");
        Path destination = source.resolveSibling("new.jar");
        Path temporary = Files.createTempFile(source.getParent(), ".modupdates-", ".download");
        Files.move(source, backup);
        Path journal = source.getParent().resolve(".modupdates-MU-RECOVER.journal");
        Properties properties = new Properties();
        properties.setProperty("state", "PREPARED");
        properties.setProperty("source", source.toString());
        properties.setProperty("destination", destination.toString());
        properties.setProperty("backup", backup.toString());
        properties.setProperty("temporary", temporary.toString());
        try (var output = Files.newOutputStream(journal)) { properties.store(output, "test"); }

        assertTrue(JarUpdateService.recoverPending(root));
        assertTrue(Files.isRegularFile(source));
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(temporary));
        assertFalse(Files.exists(journal));
    }

    @Test
    void downloadFailureRedactsSignedUrls() throws Exception {
        Path original = jar(root.resolve("mods/old.jar"), "old.txt", "old");
        Path candidate = jar(root.resolve("candidate.jar"), "new.txt", "new");
        UpdatePlan.Item item = item(original, candidate, "new.jar");
        JarUpdateService service = new JarUpdateService(root, (uri, destination, maximum) -> {
            throw new IOException("failed https://cdn.modrinth.com/new.jar?token=TOP_SECRET");
        });
        JarUpdateService.Result result = service.apply(item, Report.Provider.MODRINTH);
        assertFalse(result.success());
        assertFalse(result.message().contains("TOP_SECRET"));
        assertFalse(result.message().contains("token="));
        assertTrue(Files.isRegularFile(original));
    }

    @Test
    void sourceChangedDuringDownloadIsNotReplacedOrBackedUp() throws Exception {
        Path original = jar(root.resolve("mods/old.jar"), "old.txt", "old");
        Path candidate = jar(root.resolve("candidate.jar"), "new.txt", "new");
        UpdatePlan.Item item = item(original, candidate, "new.jar");
        byte[] concurrentBytes = jar(root.resolve("concurrent.jar"), "changed.txt", "changed").toFile()
                .exists() ? Files.readAllBytes(root.resolve("concurrent.jar")) : new byte[0];
        JarUpdateService service = new JarUpdateService(root, (uri, destination, maximum) -> {
            Files.copy(candidate, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(original, concurrentBytes);
            return Files.size(destination);
        });

        JarUpdateService.Result result = service.apply(item, Report.Provider.MODRINTH);
        assertFalse(result.success());
        assertArrayEquals(concurrentBytes, Files.readAllBytes(original));
        assertFalse(Files.exists(root.resolve("mods/old.jar.old")));
        assertFalse(Files.exists(root.resolve("mods/new.jar")));
    }

    private UpdatePlan.Item item(Path original, Path candidate, String newName) throws Exception {
        ModScanner.Jar installed = ModScanner.scan(root, false, false, warning -> fail(warning)).getFirst();
        Report.Download download = new Report.Download(Report.Provider.MODRINTH, "p", "f", newName,
                "https://cdn.modrinth.com/" + newName, Files.size(candidate), "sha1", sha1(candidate), "2",
                "2026-01-01T00:00:00Z", "https://example/p");
        return new UpdatePlan.Item(UpdatePlan.stableId(installed.relative()), installed,
                Map.of(Report.Provider.MODRINTH, download));
    }

    private static JarUpdateService.Downloader copyDownloader(Path candidate) {
        return (URI uri, Path destination, long maximum) -> {
            Files.copy(candidate, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Files.size(destination);
        };
    }

    private static Path jar(Path path, String entry, String content) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static String sha1(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path)));
    }
}
