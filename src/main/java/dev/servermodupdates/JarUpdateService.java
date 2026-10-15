package dev.servermodupdates;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verified download and recoverable on-disk replacement. It never reloads or restarts the server. */
public final class JarUpdateService {
    static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;

    @FunctionalInterface
    interface Downloader {
        long download(URI uri, Path destination, long maximumBytes) throws IOException, InterruptedException;
    }

    public record Result(String id, String path, boolean changed, boolean success, String message) { }
    private record Applied(Path original, Path installed, Path backup) { }

    private final Path gameDir;
    private final Downloader downloader;
    private final Settings settings;
    private final Map<String, Applied> applied = new LinkedHashMap<>();

    public JarUpdateService(Path gameDir, Settings settings) {
        this(gameDir, new HttpDownloader(settings), settings);
    }

    JarUpdateService(Path gameDir, Downloader downloader) {
        this(gameDir, downloader, new Settings("", false, false, false));
    }

    JarUpdateService(Path gameDir, Downloader downloader, Settings settings) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
        this.downloader = downloader;
        this.settings = settings;
    }

    public static boolean recoverPending(Path gameDir) {
        return recoverPending(gameDir, true);
    }

    public static boolean recoverPending(Path gameDir, boolean enableAutoModpack) {
        Path root = gameDir.toAbsolutePath().normalize();
        boolean changed = false;
        List<Path> roots = new ArrayList<>();
        roots.add(root.resolve("mods"));
        if (enableAutoModpack) {
            roots.add(root.resolve("automodpack/host-modpack"));
            roots.add(root.resolve("automodpack/host-midpack"));
        }
        for (Path scanRoot : roots) {
            if (!Files.isDirectory(scanRoot)) continue;
            try (var paths = Files.walk(scanRoot)) {
                for (Path journal : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().startsWith(".modupdates-")
                        && path.getFileName().toString().endsWith(".journal")).toList()) {
                    try {
                        changed |= recover(journal, journal.getParent());
                    } catch (IOException ignored) {
                        // Preserve an invalid journal for operator inspection; never follow its paths.
                    }
                }
            } catch (IOException ignored) {
                // A later explicit operation will report access errors for its exact target.
            }
        }
        return changed;
    }

    public synchronized Result apply(UpdatePlan.Item item, Report.Provider provider) {
        Report.Download download = item.provider(provider).orElse(null);
        if (download == null) return fail(item, Messages.text(settings,
                "No verified download data for " + provider + ".", "Нет проверенных данных загрузки для " + provider + "."));
        Path source = item.installed().absolute().toAbsolutePath().normalize();
        Path temporary = null;
        try {
            validateSource(item, source);
            Path parent = source.getParent();
            validateContainedParent(parent);
            String newName = safeFileName(download.fileName());
            Path destination = parent.resolve(newName).normalize();
            Path backup = source.resolveSibling(source.getFileName() + ".old");
            if (!destination.getParent().equals(parent)) throw new IOException("имя файла выходит за пределы каталога");
            if (!destination.equals(source) && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("целевой JAR уже существует: " + destination.getFileName());
            }
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("резервная копия уже существует: " + backup.getFileName());
            }
            temporary = Files.createTempFile(parent, ".modupdates-", ".download");
            long bytes = downloader.download(URI.create(download.downloadUrl()), temporary, MAX_DOWNLOAD_BYTES);
            if (bytes != download.size()) throw new IOException("размер загрузки не совпадает с метаданными");
            verifyHash(temporary, download.hashAlgorithm(), download.hash());
            verifyJar(temporary);

            Path journal = parent.resolve(".modupdates-" + item.id() + ".journal");
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) recover(journal, parent);
            writeJournal(journal, "PREPARED", source, destination, backup, temporary);
            move(source, backup, false);
            try {
                move(temporary, destination, false);
            } catch (Exception installFailure) {
                if (Files.notExists(source) && Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                    move(backup, source, false);
                }
                Files.deleteIfExists(journal);
                throw installFailure;
            }
            temporary = null;
            try { Files.deleteIfExists(journal); } catch (IOException ignored) { }
            applied.put(item.id(), new Applied(source, destination, backup));
            return new Result(item.id(), item.installed().relative(), true, true, Messages.text(settings,
                    "Installed " + newName + "; backup " + backup.getFileName(),
                    "Установлен " + newName + "; резервная копия " + backup.getFileName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(item, Messages.text(settings,
                    "Operation interrupted; the original file was preserved or restored.",
                    "Операция прервана; исходный файл сохранён или восстановлен."));
        } catch (Exception e) {
            return fail(item, sanitize(e));
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    public synchronized Result rollback(UpdatePlan.Item item) {
        Applied state = applied.get(item.id());
        try {
            if (state == null) state = inferApplied(item);
            validateContainedParent(state.original().getParent());
            if (!Files.isRegularFile(state.backup(), LinkOption.NOFOLLOW_LINKS)) {
                return fail(item, Messages.text(settings, "The .jar.old backup was not found.",
                        "Резервная копия .jar.old не найдена."));
            }
            if (Files.exists(state.original(), LinkOption.NOFOLLOW_LINKS)
                    && !state.original().equals(state.installed())) {
                return fail(item, Messages.text(settings, "The original name is occupied; rollback cancelled.",
                        "Исходное имя уже занято; rollback отменён."));
            }
            if (!Files.isRegularFile(state.installed(), LinkOption.NOFOLLOW_LINKS)) {
                return fail(item, Messages.text(settings, "The installed JAR was not found; rollback cancelled.",
                        "Установленный JAR не найден; rollback отменён."));
            }
            Path removed = Files.createTempFile(state.installed().getParent(), ".modupdates-rollback-", ".jar");
            Files.deleteIfExists(removed);
            move(state.installed(), removed, false);
            try {
                move(state.backup(), state.original(), false);
                Files.deleteIfExists(removed);
                applied.remove(item.id());
                return new Result(item.id(), item.installed().relative(), true, true, Messages.text(settings,
                        "Restored " + state.original().getFileName(), "Восстановлен " + state.original().getFileName()));
            } catch (Exception restoreFailure) {
                if (Files.notExists(state.installed()) && Files.exists(removed)) move(removed, state.installed(), false);
                throw restoreFailure;
            }
        } catch (Exception e) {
            return fail(item, sanitize(e));
        }
    }

    private Applied inferApplied(UpdatePlan.Item item) throws IOException {
        Path original = item.installed().absolute().toAbsolutePath().normalize();
        Path backup = original.resolveSibling(original.getFileName() + ".old");
        List<Path> possible = item.providers().values().stream().filter(Report.Download::actionable)
                .map(download -> original.getParent().resolve(download.fileName()).normalize()).distinct().toList();
        List<Path> existing = possible.stream().filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
        if (existing.size() != 1) throw new IOException("невозможно однозначно определить установленный JAR");
        return new Applied(original, existing.getFirst(), backup);
    }

    private void validateSource(UpdatePlan.Item item, Path source) throws IOException {
        if (!isAllowedTarget(source) || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("исходный JAR отсутствует, является ссылкой или вне каталога сервера");
        }
        String currentSha1 = digest(source, "SHA-1");
        if (!currentSha1.equalsIgnoreCase(item.installed().sha1())) {
            throw new IOException("исходный JAR изменился после проверки; выполните /modupdates check снова");
        }
    }

    private void validateContainedParent(Path parent) throws IOException {
        if (!isAllowedTarget(parent.resolve("placeholder.jar"))) {
            throw new IOException("целевой каталог вне разрешённых каталогов модов");
        }
        Path cursor = parent;
        while (cursor != null && cursor.startsWith(gameDir)) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("символические ссылки в целевом пути запрещены");
            if (cursor.equals(gameDir)) break;
            cursor = cursor.getParent();
        }
    }

    private boolean isAllowedTarget(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(gameDir.resolve("mods"))) return true;
        if (!settings.enableAutoModpack()) return false;
        return normalized.startsWith(gameDir.resolve("automodpack/host-modpack"))
                || normalized.startsWith(gameDir.resolve("automodpack/host-midpack"));
    }

    private static String safeFileName(String value) throws IOException {
        if (value == null || value.isBlank() || !value.toLowerCase(Locale.ROOT).endsWith(".jar")
                || value.contains("/") || value.contains("\\") || value.contains(":")
                || !Path.of(value).getFileName().toString().equals(value)) {
            throw new IOException("провайдер вернул небезопасное имя JAR");
        }
        return value;
    }

    static void verifyHash(Path file, String algorithm, String expected) throws IOException {
        String javaAlgorithm = switch (algorithm == null ? "" : algorithm.toLowerCase(Locale.ROOT)) {
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            default -> throw new IOException("неподдерживаемый или отсутствующий checksum");
        };
        String actual = digest(file, javaAlgorithm);
        if (!actual.equalsIgnoreCase(expected)) throw new IOException("checksum загрузки не совпадает");
    }

    static void verifyJar(Path file) throws IOException {
        int entries = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IOException("JAR содержит небезопасный ZIP-путь");
                }
                entries++;
                if (entries > 1_000_000) throw new IOException("слишком много записей в JAR");
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("загрузка не является корректным JAR/ZIP", e);
        }
        if (entries == 0) throw new IOException("JAR пуст");
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        List<StandardCopyOption> options = new ArrayList<>();
        options.add(StandardCopyOption.ATOMIC_MOVE);
        if (replace) options.add(StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(source, target, options.toArray(StandardCopyOption[]::new));
        } catch (AtomicMoveNotSupportedException e) {
            options.remove(StandardCopyOption.ATOMIC_MOVE);
            Files.move(source, target, options.toArray(StandardCopyOption[]::new));
        }
    }

    private static void writeJournal(Path journal, String state, Path source, Path destination,
                                     Path backup, Path temporary) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("state", state);
        properties.setProperty("source", source.toString());
        properties.setProperty("destination", destination.toString());
        properties.setProperty("backup", backup.toString());
        if (temporary != null) properties.setProperty("temporary", temporary.toString());
        try (var output = Files.newOutputStream(journal, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(output, "Server Mod Updates transaction journal");
        }
    }

    private static boolean recover(Path journal, Path expectedParent) throws IOException {
        if (Files.isSymbolicLink(journal) || !journal.getParent().equals(expectedParent)) {
            throw new IOException("небезопасный transaction journal");
        }
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(journal)) { p.load(input); }
        Path source = Path.of(p.getProperty("source"));
        Path destination = Path.of(p.getProperty("destination"));
        Path backup = Path.of(p.getProperty("backup"));
        if (!source.getParent().equals(expectedParent) || !destination.getParent().equals(expectedParent)
                || !backup.getParent().equals(expectedParent)) throw new IOException("journal содержит путь вне каталога");
        String state = p.getProperty("state", "");
        boolean changed = false;
        if ("PREPARED".equals(state) && Files.notExists(source) && Files.exists(backup)
                && Files.notExists(destination)) {
            move(backup, source, false);
            changed = true;
        }
        String temporary = p.getProperty("temporary");
        if (temporary != null) {
            Path temporaryPath = Path.of(temporary);
            if (!temporaryPath.getParent().equals(expectedParent)) throw new IOException("journal содержит внешний temp-путь");
            Files.deleteIfExists(temporaryPath);
        }
        Files.deleteIfExists(journal);
        return changed;
    }

    private Result fail(UpdatePlan.Item item, String message) {
        return new Result(item.id(), item.installed().relative(), false, false, localizeError(message));
    }

    private String localizeError(String message) {
        if (settings.language() == Settings.Language.RU_RU) return message;
        return message
                .replace("имя файла выходит за пределы каталога", "file name escapes the target directory")
                .replace("целевой JAR уже существует", "target JAR already exists")
                .replace("резервная копия уже существует", "backup already exists")
                .replace("размер загрузки не совпадает с метаданными", "download size does not match metadata")
                .replace("невозможно однозначно определить установленный JAR", "cannot uniquely identify the installed JAR")
                .replace("исходный JAR отсутствует, является ссылкой или вне каталога сервера", "source JAR is missing, a link, or outside allowed directories")
                .replace("исходный JAR изменился после проверки; выполните /modupdates check снова", "source JAR changed after the scan; run /modupdates check again")
                .replace("целевой каталог вне разрешённых каталогов модов", "target directory is outside allowed mod directories")
                .replace("символические ссылки в целевом пути запрещены", "symbolic links are forbidden in the target path")
                .replace("провайдер вернул небезопасное имя JAR", "provider returned an unsafe JAR name")
                .replace("неподдерживаемый или отсутствующий checksum", "unsupported or missing checksum")
                .replace("checksum загрузки не совпадает", "download checksum mismatch")
                .replace("JAR содержит небезопасный ZIP-путь", "JAR contains an unsafe ZIP path")
                .replace("слишком много записей в JAR", "JAR contains too many entries")
                .replace("загрузка не является корректным JAR/ZIP", "download is not a valid JAR/ZIP")
                .replace("JAR пуст", "JAR is empty")
                .replace("загрузка отклонена, HTTP", "download rejected, HTTP")
                .replace("загрузка превышает лимит размера", "download exceeds the size limit")
                .replace("слишком много redirect при загрузке", "too many download redirects")
                .replace("разрешены только HTTPS URL без credentials и fragment", "only HTTPS URLs without credentials or fragments are allowed")
                .replace("download host не принадлежит разрешённому провайдеру", "download host is not allowed for the selected provider");
    }

    private static String sanitize(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replaceAll("(?i)https://\\S+", "<redacted-url>")
                .replaceAll("(?i)(api[_-]?key|token|signature)=[^\\s&]+", "$1=<redacted>");
    }

    private static final class HttpDownloader implements Downloader {
        private final HttpClient client;
        private final Duration timeout;

        HttpDownloader(Settings settings) {
            timeout = Duration.ofSeconds(settings.requestTimeoutSeconds());
            client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public long download(URI initial, Path destination, long maximumBytes) throws IOException, InterruptedException {
            URI current = initial;
            for (int redirect = 0; redirect <= 5; redirect++) {
                requireSafeHttps(current);
                HttpRequest request = HttpRequest.newBuilder(current).timeout(timeout)
                        .header("Accept", "application/java-archive, application/octet-stream")
                        .header("User-Agent", "ServerModUpdater/1.2.0").GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    try (InputStream ignored = response.body()) { }
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("redirect без Location"));
                    current = current.resolve(location);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    try (InputStream ignored = response.body()) { }
                    throw new IOException("загрузка отклонена, HTTP " + status);
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maximumBytes) throw new IOException("загрузка превышает лимит размера");
                long total = 0;
                try (InputStream input = response.body(); var output = Files.newOutputStream(destination,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        total += read;
                        if (total > maximumBytes) throw new IOException("загрузка превышает лимит размера");
                        output.write(buffer, 0, read);
                    }
                }
                return total;
            }
            throw new IOException("слишком много redirect при загрузке");
        }

        private static void requireSafeHttps(URI uri) throws IOException {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IOException("разрешены только HTTPS URL без credentials и fragment");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("modrinth.com") || host.endsWith(".modrinth.com")
                    || host.equals("curseforge.com") || host.endsWith(".curseforge.com")
                    || host.equals("forgecdn.net") || host.endsWith(".forgecdn.net"))) {
                throw new IOException("download host не принадлежит разрешённому провайдеру");
            }
        }
    }
}
