package dev.servermodupdates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class ModScanner {
    private ModScanner() { }

    public record Jar(Path absolute, String relative, String sha1, long fingerprint) { }

    public static List<Jar> scan(Path root, boolean needFingerprint, boolean legacy,
                                 Consumer<String> warnings) throws IOException {
        return scan(root, needFingerprint, true, legacy, Settings.Language.RU_RU, warnings);
    }

    public static List<Jar> scan(Path root, boolean needFingerprint, boolean enableAutoModpack,
                                 boolean legacy, Settings.Language language,
                                 Consumer<String> warnings) throws IOException {
        Path gameDir = root.toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(mods)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path path : stream) if (regularJar(path)) files.add(path);
            } catch (IOException e) {
                warnings.accept(localized(language, "Cannot scan ", "Ошибка сканирования ")
                        + mods + ": " + e.getClass().getSimpleName());
            }
        }
        if (enableAutoModpack) {
            collectHost(gameDir.resolve("automodpack/host-modpack"), files, language, warnings);
            if (legacy) collectHost(gameDir.resolve("automodpack/host-midpack"), files, language, warnings);
        }
        files = deduplicate(files);
        files.sort(Comparator.comparing(Path::toString));

        List<Jar> jars = new ArrayList<>();
        for (Path path : files) {
            try {
                String sha1 = sha1(path);
                long fingerprint = needFingerprint ? Fingerprint.calculate(path) : 0;
                jars.add(new Jar(path, gameDir.relativize(path).toString().replace('\\', '/'), sha1, fingerprint));
            } catch (IOException e) {
                warnings.accept(localized(language, "Cannot read ", "Невозможно прочитать ")
                        + gameDir.relativize(path) + ": " + e.getClass().getSimpleName());
            }
        }
        return jars;
    }

    private static void collectHost(Path host, List<Path> found, Settings.Language language,
                                    Consumer<String> warnings) {
        if (!Files.isDirectory(host, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(host)) return;
        try (Stream<Path> paths = Files.walk(host)) {
            paths.filter(ModScanner::regularJar).filter(path -> {
                Path relative = host.relativize(path);
                // host-modpack/<group>/mods/...; avoid caches/scripts/other jar files.
                return relative.getNameCount() >= 3
                        && relative.getName(1).toString().equalsIgnoreCase("mods");
            }).forEach(found::add);
        } catch (IOException e) {
            warnings.accept(localized(language, "Cannot scan ", "Ошибка сканирования ")
                    + host + ": " + e.getClass().getSimpleName());
        }
    }

    private static String localized(Settings.Language language, String english, String russian) {
        return language == Settings.Language.RU_RU ? russian : english;
    }

    private static List<Path> deduplicate(List<Path> paths) {
        List<Path> unique = new ArrayList<>();
        Set<Path> normalized = new HashSet<>();
        for (Path path : paths) {
            Path absolute = path.toAbsolutePath().normalize();
            if (normalized.add(absolute)) unique.add(absolute);
        }
        return unique;
    }

    private static boolean regularJar(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String sha1(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = new DigestInputStream(Files.newInputStream(path), md)) {
                in.transferTo(OutputStreamNull.INSTANCE);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static final class OutputStreamNull extends java.io.OutputStream {
        static final OutputStreamNull INSTANCE = new OutputStreamNull();
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    }
}
