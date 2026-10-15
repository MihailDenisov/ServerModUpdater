package dev.servermodupdates;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Run without Gradle: javac Fingerprint.java ModScanner.java OfflineSelfTest.java ... */
public final class OfflineSelfTest {
    public static void main(String[] args) throws Exception {
        fingerprint("", 1540447798L);
        fingerprint("hello", 2788266382L);
        fingerprint("hello world", 2824650221L);
        fingerprint(" a\nb\r\t c", 1621425345L);
        Path root = Files.createTempDirectory("modupdates-selftest");
        try {
            Path regular = root.resolve("mods/example.jar");
            Path hostMain = root.resolve("automodpack/host-modpack/main/mods/client.jar");
            Path hostExtra = root.resolve("automodpack/host-modpack/optional/mods/addon.jar");
            Path legacy = root.resolve("automodpack/host-midpack/main/mods/legacy.jar");
            Path unrelated = root.resolve("automodpack/host-modpack/main/scripts/ignore.jar");
            for (Path p : List.of(regular, hostMain, hostExtra, legacy, unrelated)) {
                Files.createDirectories(p.getParent());
                Files.writeString(p, "hello");
            }
            var jars = ModScanner.scan(root, true, true, warning -> {
                throw new AssertionError(warning);
            });
            if (jars.size() != 4) throw new AssertionError("Expected 4 jars, got " + jars.size());
            for (var jar : jars) {
                if (jar.fingerprint() != 2788266382L) throw new AssertionError(jar.relative());
                if (!jar.sha1().equals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"))
                    throw new AssertionError("SHA-1 mismatch: " + jar.relative());
            }
            if (ModScanner.scan(root, false, false, warning -> { }).size() != 3)
                throw new AssertionError("Legacy disabled option failed");
            System.out.println("PASS: fingerprint vectors; standard mods; AutoModpack groups; legacy flag; SHA-1");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void fingerprint(String input, long expected) throws Exception {
        Path tmp = Files.createTempFile("fingerprint", ".jar");
        try {
            Files.write(tmp, input.getBytes(StandardCharsets.UTF_8));
            long actual = Fingerprint.calculate(tmp);
            if (actual != expected) throw new AssertionError(input + ": got " + actual + " expected " + expected);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
