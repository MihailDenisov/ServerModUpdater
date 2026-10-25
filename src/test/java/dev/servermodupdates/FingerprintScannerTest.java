package dev.servermodupdates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FingerprintScannerTest {
    @TempDir Path root;

    @Test
    void knownFingerprintVectors() throws Exception {
        assertFingerprint("", 1540447798L);
        assertFingerprint("hello", 2788266382L);
        assertFingerprint("hello world", 2824650221L);
        assertFingerprint(" a\nb\r\t c", 1621425345L);
    }

    @Test
    void scansOnlyConfiguredModRootsAtArbitraryDepth() throws Exception {
        List<Path> included = List.of(
                root.resolve("mods/example.jar"),
                root.resolve("automodpack/host-modpack/main/mods/client.jar"),
                root.resolve("automodpack/host-modpack/optional/mods/nested/addon.jar"),
                root.resolve("automodpack/host-midpack/main/mods/legacy.jar"));
        Path deep = root.resolve("automodpack/host-modpack/deep/mods");
        for (int i = 0; i < 20; i++) deep = deep.resolve("level" + i);
        included = new ArrayList<>(included);
        included.add(deep.resolve("deep.jar"));
        Path unrelated = root.resolve("automodpack/host-modpack/main/scripts/ignore.jar");
        for (Path path : included) write(path, "hello");
        write(unrelated, "hello");

        List<ModScanner.Jar> jars = ModScanner.scan(root, true, true, warning -> { });
        assertEquals(5, jars.size());
        assertFalse(jars.stream().anyMatch(jar -> jar.relative().contains("scripts")));
        jars.forEach(jar -> {
            assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", jar.sha1());
            assertEquals(2788266382L, jar.fingerprint());
        });
        assertEquals(4, ModScanner.scan(root, false, false, warning -> { }).size());
        assertEquals(1, ModScanner.scan(root, false, false, true, Settings.Language.EN_US,
                warning -> { }).size());
    }

    @Test
    void ignoresSymlinkButKeepsDistinctHardLinkPathsWhenSupported() throws Exception {
        Path original = root.resolve("mods/original.jar");
        write(original, "same bytes");
        Path hostMods = root.resolve("automodpack/host-modpack/main/mods");
        Files.createDirectories(hostMods);
        try {
            Files.createLink(hostMods.resolve("hardlink.jar"), original);
            assertEquals(2, ModScanner.scan(root, false, false, warning -> { }).size());
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Some Windows filesystems or CI accounts do not permit hard links.
        }
        try {
            Files.createSymbolicLink(hostMods.resolve("symlink.jar"), original);
            assertFalse(ModScanner.scan(root, false, false, warning -> { }).stream()
                    .anyMatch(jar -> jar.relative().endsWith("symlink.jar")));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Symlink creation commonly requires elevated Windows privileges.
        }
    }

    @Test
    void ignoresSymlinkedStandardModsRootWhenSupported() throws Exception {
        Path actual = root.resolve("actual-mods");
        write(actual.resolve("outside.jar"), "outside");
        try {
            Files.createSymbolicLink(root.resolve("mods"), actual);
            assertEquals(0, ModScanner.scan(root, false, false, false, Settings.Language.EN_US,
                    warning -> { }).size());
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Symlink creation commonly requires elevated Windows privileges.
        }
    }

    private void assertFingerprint(String contents, long expected) throws Exception {
        Path file = Files.createTempFile(root, "fingerprint", ".jar");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        assertEquals(expected, Fingerprint.calculate(file));
    }

    private static void write(Path path, String contents) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents, StandardCharsets.UTF_8);
    }
}
