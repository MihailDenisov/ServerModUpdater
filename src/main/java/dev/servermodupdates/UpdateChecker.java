package dev.servermodupdates;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** All HTTP and disk I/O happen off the Minecraft server thread. */
public final class UpdateChecker {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(UpdateChecker.class);
    private static final String MR = "https://api.modrinth.com/v2";
    private static final String CF = "https://api.curseforge.com/v1";
    private static final int MC_GAME_ID = 432;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_CF_FILES = 500;

    @FunctionalInterface
    interface Transport {
        JsonObject send(String method, URI uri, String body, String curseKey)
                throws IOException, InterruptedException;
    }

    private final Transport transport;
    private final String modrinthBase;
    private final String curseforgeBase;

    public UpdateChecker() {
        this(new Settings("", false, false, true));
    }

    public UpdateChecker(Settings settings) {
        this(new JdkTransport(settings), MR, CF);
    }

    UpdateChecker(Transport transport, String modrinthBase, String curseforgeBase) {
        this.transport = transport;
        this.modrinthBase = withoutTrailingSlash(modrinthBase);
        this.curseforgeBase = withoutTrailingSlash(curseforgeBase);
    }

    public static String diagnoseCurseForge(Settings settings) {
        if (!settings.curseforgeEnabled()) return "SKIPPED: API key is not configured";
        JdkTransport transport = new JdkTransport(settings);
        transport.beginCheck("diagnose-" + shortId());
        try {
            transport.send("GET", URI.create(CF + "/games/" + MC_GAME_ID), null, settings.curseforgeKey());
            return "HTTP 2xx / OK";
        } catch (ApiException e) {
            return "HTTP " + e.status + " / " + code(e);
        } catch (HttpTimeoutException e) {
            return "TIMEOUT";
        } catch (IOException e) {
            return "NETWORK_OR_INVALID_RESPONSE / " + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
    }

    public Report check(Path gameDir, Settings settings) throws IOException {
        if (transport instanceof JdkTransport jdkTransport) jdkTransport.beginCheck("scan-" + shortId());
        Report report = new Report();
        report.curseforgeEnabled = settings.curseforgeEnabled();
        report.scanned.addAll(ModScanner.scan(gameDir, settings.curseforgeEnabled(),
                settings.enableAutoModpack(), settings.scanLegacyHostMidpack(), settings.language(), report.warnings::add));
        if (report.scanned.isEmpty()) return report;
        report.initializeOutcomes();
        try {
            checkModrinth(report, settings);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            report.modrinthSuccess = false;
            report.setPending(Report.Provider.MODRINTH, status(e), code(e));
            report.warnings.add(Messages.text(settings, "Modrinth: check did not complete (",
                    "Modrinth: проверка не завершена (") + failure(e) + ")");
        }
        if (settings.curseforgeEnabled()) {
            try {
                checkCurseForge(report, settings);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                report.curseforgeSuccess = false;
                report.setPending(Report.Provider.CURSEFORGE, status(e), code(e));
                report.warnings.add(Messages.text(settings, "CurseForge: check did not complete (",
                        "CurseForge: проверка не завершена (") + failure(e) + ")");
            }
        } else {
            report.warnings.add(Messages.text(settings,
                    "CurseForge skipped: no API key in config/modupdates.properties or the environment.",
                    "CurseForge пропущен: API-ключ не задан в config/modupdates.properties или переменной окружения."));
        }
        return report;
    }

    private void checkModrinth(Report report, Settings settings) throws IOException, InterruptedException {
        Map<String, List<ModScanner.Jar>> byHash = new LinkedHashMap<>();
        for (ModScanner.Jar jar : report.scanned) {
            byHash.computeIfAbsent(jar.sha1(), ignored -> new ArrayList<>()).add(jar);
        }
        List<String> hashes = new ArrayList<>(byHash.keySet());
        Map<String, String> names = new HashMap<>();
        for (int from = 0; from < hashes.size(); from += 100) {
            List<String> batch = hashes.subList(from, Math.min(from + 100, hashes.size()));
            JsonObject common = new JsonObject();
            common.add("hashes", strings(batch));
            common.addProperty("algorithm", "sha1");
            JsonObject current = post(modrinthBase + "/version_files", common, "");
            JsonObject request = common.deepCopy();
            request.add("loaders", strings(List.of("neoforge")));
            request.add("game_versions", strings(List.of("1.21.1")));
            List<String> types = new ArrayList<>(List.of("release"));
            if (settings.includeBeta()) types.add("beta");
            if (settings.includeAlpha()) types.add("alpha");
            request.add("version_types", strings(types));
            JsonObject newest = post(modrinthBase + "/version_files/update", request, "");
            for (String hash : batch) {
                JsonObject installed = mapValue(current, hash, "Modrinth /version_files");
                if (installed == null) {
                    for (ModScanner.Jar jar : byHash.get(hash)) report.set(Report.Provider.MODRINTH, jar,
                            Report.Status.NOT_FOUND_BY_HASH, "HASH_NOT_FOUND");
                    continue;
                }
                JsonObject latest = mapValue(newest, hash, "Modrinth /version_files/update");
                if (latest == null || string(installed, "id").equals(string(latest, "id"))
                        || !strictlyNewer(string(installed, "date_published"), string(latest, "date_published"))) {
                    for (ModScanner.Jar jar : byHash.get(hash)) report.set(Report.Provider.MODRINTH, jar,
                            new Report.Outcome(Report.Status.UP_TO_DATE, "NO_NEWER_COMPATIBLE_VERSION",
                                    string(installed, "version_number"), "", ""));
                    continue;
                }
                String projectId = string(latest, "project_id");
                String versionId = string(latest, "id");
                if (projectId.isBlank() || versionId.isBlank()) throw schema("Modrinth version identifiers");
                String displayName = names.get(projectId);
                if (displayName == null) {
                    displayName = string(latest, "name");
                    try {
                        JsonObject project = get(modrinthBase + "/project/" + projectId, "");
                        displayName = fallback(string(project, "title"), displayName);
                    } catch (Exception ignored) {
                        // The exact Modrinth version URL remains available.
                    }
                    names.put(projectId, displayName);
                }
                String url = "https://modrinth.com/mod/" + projectId + "/version/" + versionId;
                Report.Download download = modrinthDownload(latest, projectId, versionId, url);
                for (ModScanner.Jar jar : byHash.get(hash)) {
                    report.set(Report.Provider.MODRINTH, jar,
                            new Report.Outcome(Report.Status.UPDATE_AVAILABLE, "NEWER_COMPATIBLE_VERSION",
                                    string(installed, "version_number"), string(latest, "version_number"), url));
                    report.updates.add(new Report.Update("Modrinth",
                            fallback(displayName, jar.absolute().getFileName().toString()),
                            string(installed, "version_number"), string(latest, "version_number"), url,
                            jar.relative(), string(latest, "date_published")));
                    if (download != null) report.setDownload(Report.Provider.MODRINTH, jar, download);
                }
            }
        }
    }

    private void checkCurseForge(Report report, Settings settings) throws IOException, InterruptedException {
        Map<Long, List<ModScanner.Jar>> byFingerprint = new LinkedHashMap<>();
        for (ModScanner.Jar jar : report.scanned) {
            byFingerprint.computeIfAbsent(jar.fingerprint(), ignored -> new ArrayList<>()).add(jar);
        }
        Map<Long, JsonObject> currentFiles = new LinkedHashMap<>();
        List<Long> fingerprints = new ArrayList<>(byFingerprint.keySet());
        for (int from = 0; from < fingerprints.size(); from += 100) {
            List<Long> batch = fingerprints.subList(from, Math.min(from + 100, fingerprints.size()));
            JsonArray hashes = new JsonArray();
            batch.forEach(hashes::add);
            JsonObject body = new JsonObject();
            body.add("fingerprints", hashes);
            JsonObject response = post(curseforgeBase + "/fingerprints/" + MC_GAME_ID, body,
                    settings.curseforgeKey());
            JsonArray matches = requiredArray(requiredObject(response, "data", "CurseForge fingerprints"),
                    "exactMatches", "CurseForge fingerprints");
            for (JsonElement item : matches) {
                if (!item.isJsonObject()) throw schema("CurseForge exactMatches item");
                JsonObject matchFile = requiredObject(item.getAsJsonObject(), "file", "CurseForge exact match");
                long fingerprint = requiredNumber(matchFile, "fileFingerprint", "CurseForge exact match");
                if (!byFingerprint.containsKey(fingerprint)) continue;
                currentFiles.put(fingerprint, matchFile);
            }
            for (long fingerprint : batch) {
                if (!currentFiles.containsKey(fingerprint)) for (ModScanner.Jar jar : byFingerprint.get(fingerprint))
                    report.set(Report.Provider.CURSEFORGE, jar, Report.Status.NOT_FOUND_BY_HASH, "HASH_NOT_FOUND");
            }
        }

        Map<Long, List<JsonObject>> filesCache = new HashMap<>();
        Map<Long, String[]> projectsCache = new HashMap<>();
        for (Map.Entry<Long, JsonObject> entry : currentFiles.entrySet()) {
            JsonObject installed = entry.getValue();
            long modId = requiredNumber(installed, "modId", "CurseForge installed file");
            if (modId <= 0) throw schema("CurseForge modId");
            List<JsonObject> candidates = filesCache.get(modId);
            if (candidates == null) {
                candidates = loadCompatibleFiles(modId, settings, report);
                filesCache.put(modId, candidates);
            }
            JsonObject newest = null;
            for (JsonObject candidate : candidates) {
                if (number(candidate, "id") == number(installed, "id")) continue;
                if (!strictlyNewer(string(installed, "fileDate"), string(candidate, "fileDate"))) continue;
                if (newest == null || strictlyNewer(string(newest, "fileDate"), string(candidate, "fileDate"))) {
                    newest = candidate;
                }
            }
            if (newest == null) {
                for (ModScanner.Jar jar : byFingerprint.get(entry.getKey())) report.set(Report.Provider.CURSEFORGE, jar,
                        new Report.Outcome(Report.Status.UP_TO_DATE, "NO_NEWER_COMPATIBLE_VERSION",
                                fallback(string(installed, "displayName"), string(installed, "fileName")), "", ""));
                continue;
            }
            String[] project = projectsCache.get(modId);
            if (project == null) {
                project = new String[]{"", ""};
                try {
                    JsonObject info = requiredObject(get(curseforgeBase + "/mods/" + modId,
                            settings.curseforgeKey()), "data", "CurseForge project");
                    project[0] = string(info, "name");
                    project[1] = string(requiredObject(info, "links", "CurseForge project"), "websiteUrl");
                } catch (Exception ignored) {
                    // A candidate download URL may still be present.
                }
                projectsCache.put(modId, project);
            }
            String projectUrl = https(project[1]);
            long candidateId = number(newest, "id");
            if (candidateId > 0 && https(string(newest, "downloadUrl")).isBlank()) {
                try {
                    JsonObject response = get(curseforgeBase + "/mods/" + modId + "/files/" + candidateId
                            + "/download-url", settings.curseforgeKey());
                    String direct = https(string(response, "data"));
                    if (!direct.isBlank()) newest.addProperty("downloadUrl", direct);
                } catch (Exception ignored) {
                    // Distribution may be disabled; the result stays visible but is not actionable.
                }
            }
            String url = projectUrl.isBlank() || candidateId <= 0 ? ""
                    : projectUrl.replaceAll("/+$", "") + "/files/" + candidateId;
            url = fallback(url, https(string(newest, "downloadUrl")));
            Report.Download download = curseForgeDownload(newest, modId, candidateId, url);
            for (ModScanner.Jar jar : byFingerprint.get(entry.getKey())) {
                report.set(Report.Provider.CURSEFORGE, jar,
                        new Report.Outcome(Report.Status.UPDATE_AVAILABLE, "NEWER_COMPATIBLE_VERSION",
                                fallback(string(installed, "displayName"), string(installed, "fileName")),
                                fallback(string(newest, "displayName"), string(newest, "fileName")), url));
                report.updates.add(new Report.Update("CurseForge",
                        fallback(project[0], fallback(string(newest, "displayName"), jar.absolute().getFileName().toString())),
                        fallback(string(installed, "displayName"), string(installed, "fileName")),
                        fallback(string(newest, "displayName"), string(newest, "fileName")),
                        url, jar.relative(), string(newest, "fileDate")));
                if (download != null) report.setDownload(Report.Provider.CURSEFORGE, jar, download);
            }
        }
    }

    private List<JsonObject> loadCompatibleFiles(long modId, Settings settings, Report report)
            throws IOException, InterruptedException {
        List<JsonObject> results = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        int index = 0;
        while (index < MAX_CF_FILES) {
            String url = curseforgeBase + "/mods/" + modId
                    + "/files?gameVersion=1.21.1&modLoaderType=6&pageSize=" + PAGE_SIZE + "&index=" + index;
            JsonObject response = get(url, settings.curseforgeKey());
            JsonArray files = requiredArray(response, "data", "CurseForge files");
            JsonObject pagination = requiredObject(response, "pagination", "CurseForge files");
            long responseIndex = requiredNumber(pagination, "index", "CurseForge pagination");
            long resultCount = requiredNumber(pagination, "resultCount", "CurseForge pagination");
            long totalCount = requiredNumber(pagination, "totalCount", "CurseForge pagination");
            if (responseIndex != index || resultCount != files.size() || totalCount < resultCount) {
                throw schema("CurseForge pagination values");
            }
            for (JsonElement elem : files) {
                if (!elem.isJsonObject()) throw schema("CurseForge file item");
                JsonObject file = elem.getAsJsonObject();
                long id = requiredNumber(file, "id", "CurseForge file item");
                if (!seenIds.add(id)) continue;
                if (file.has("isAvailable") && !file.get("isAvailable").isJsonNull()
                        && !file.get("isAvailable").getAsBoolean()) continue;
                if (file.has("isServerPack") && !file.get("isServerPack").isJsonNull()
                        && file.get("isServerPack").getAsBoolean()) continue;
                int type = (int) requiredNumber(file, "releaseType", "CurseForge file item");
                if (type == 1 || (type == 2 && settings.includeBeta()) || (type == 3 && settings.includeAlpha())) {
                    results.add(file);
                }
            }
            long next = responseIndex + resultCount;
            if (next >= totalCount) return results;
            if (resultCount == 0 || next <= index) throw schema("CurseForge pagination progress");
            if (next >= MAX_CF_FILES) {
                report.warnings.add(Messages.text(settings, "CurseForge project ID ", "CurseForge: у проекта ID ")
                        + modId + Messages.text(settings, " has more than ", " больше ") + MAX_CF_FILES
                        + Messages.text(settings, " sampled files; results may be incomplete.",
                        " файлов в выборке; результат может быть неполным."));
                return results;
            }
            index = (int) next;
        }
        return results;
    }

    private JsonObject get(String url, String curseKey) throws IOException, InterruptedException {
        return transport.send("GET", URI.create(url), null, curseKey);
    }

    private JsonObject post(String url, JsonObject body, String curseKey) throws IOException, InterruptedException {
        return transport.send("POST", URI.create(url), body.toString(), curseKey);
    }

    static final class JdkTransport implements Transport {
        private final HttpClient client;
        private final Settings settings;
        private volatile long deadlineNanos;
        private volatile String scanId = "scan-unknown";

        JdkTransport(Settings settings) {
            this.settings = settings;
            this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            beginCheck("scan-" + shortId());
        }

        void beginCheck() { beginCheck("scan-" + shortId()); }

        void beginCheck(String scanId) {
            this.scanId = scanId;
            deadlineNanos = System.nanoTime() + Duration.ofSeconds(settings.checkBudgetSeconds()).toNanos();
        }

        @Override
        public JsonObject send(String method, URI uri, String body, String curseKey)
                throws IOException, InterruptedException {
            for (int attempt = 1; ; attempt++) {
                String requestId = scanId + "-" + shortId();
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) throw new HttpTimeoutException("исчерпан общий бюджет проверки API");
                long timeoutMillis = Math.max(1, Math.min(
                        Duration.ofSeconds(settings.requestTimeoutSeconds()).toMillis(),
                        Duration.ofNanos(remainingNanos).toMillis()));
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Accept", "application/json")
                    .header("User-Agent", "ServerModUpdater/1.2.0 (NeoForge 1.21.1; manual updates)");
            if (!curseKey.isBlank()) request.header("x-api-key", curseKey);
            if ("POST".equals(method)) {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                request.GET();
            }
                long started = System.nanoTime();
                HttpResponse<String> response;
                try {
                    response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (IOException | InterruptedException e) {
                    LOGGER.debug("ModUpdates HTTP scanId=" + scanId
                            + " requestId=" + requestId + " provider=" + provider(uri) + " method=" + method
                            + " path=" + safePath(uri) + " attempt=" + attempt + " outcome="
                            + e.getClass().getSimpleName() + " durationMs=" + elapsedMillis(started) + " retry=false");
                    throw e;
                }
                int status = response.statusCode();
                boolean retry = retryable(status) && attempt <= settings.maxRetries();
                LOGGER.debug("ModUpdates HTTP scanId=" + scanId
                        + " requestId=" + requestId + " provider=" + provider(uri) + " method=" + method
                        + " path=" + safePath(uri) + " attempt=" + attempt + " status=" + status
                        + " durationMs=" + elapsedMillis(started) + " retry=" + retry + " retryAfterPresent="
                        + response.headers().firstValue("Retry-After").isPresent() + " rateLimitRemaining="
                        + safeHeader(response, "X-RateLimit-Remaining"));
                if (status >= 200 && status < 300) {
                    try {
                        JsonElement parsed = JsonParser.parseString(response.body());
                        if (!parsed.isJsonObject()) throw schema("API response root");
                        return parsed.getAsJsonObject();
                    } catch (RuntimeException e) {
                throw new InvalidResponseException("некорректный JSON API", e);
                    }
                }
                if (!retry) throw new ApiException(status);
                long delay = retryDelay(response, attempt, settings);
                if (System.nanoTime() + Duration.ofMillis(delay).toNanos() > deadlineNanos) {
                    throw new ApiException(status);
                }
                LOGGER.debug("ModUpdates retry scanId=" + scanId + " requestId="
                        + requestId + " provider=" + provider(uri) + " status=" + status + " delayMs=" + delay);
                Thread.sleep(delay);
            }
        }

        private static long elapsedMillis(long started) {
            return Duration.ofNanos(System.nanoTime() - started).toMillis();
        }

        private static String provider(URI uri) {
            String host = uri.getHost() == null ? "unknown" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return host.contains("curseforge") || host.contains("forgecdn") ? "curseforge"
                    : host.contains("modrinth") ? "modrinth" : "other";
        }

        static String safePath(URI uri) {
            return uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        }

        private static String safeHeader(HttpResponse<?> response, String name) {
            String value = response.headers().firstValue(name).orElse("");
            return value.matches("[0-9]{1,12}") ? value : value.isBlank() ? "absent" : "redacted";
        }

        private static boolean retryable(int status) {
            return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
        }

        private static long retryDelay(HttpResponse<?> response, int attempt, Settings settings) {
            String retry = response.headers().firstValue("Retry-After").orElse("").trim();
            try { return Math.min(settings.retryMaxDelayMillis(), Math.max(0, Long.parseLong(retry) * 1000)); }
            catch (NumberFormatException ignored) { }
            try {
                long millis = Duration.between(Instant.now(), ZonedDateTime.parse(retry,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                if (millis > 0) return Math.min(settings.retryMaxDelayMillis(), millis);
            } catch (RuntimeException ignored) { }
            String reset = response.headers().firstValue("X-Ratelimit-Reset").orElse("").trim();
            try { return Math.min(settings.retryMaxDelayMillis(), Math.max(0, Long.parseLong(reset) * 1000)); }
            catch (NumberFormatException ignored) { }
            long exponential = settings.retryBaseDelayMillis() * (1L << Math.min(attempt - 1, 10));
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, settings.retryBaseDelayMillis() / 2));
            return Math.min(settings.retryMaxDelayMillis(), exponential + jitter);
        }
    }

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status) {
            super(status == 401 || status == 403 ? "доступ запрещён, HTTP " + status
                    : status == 429 ? "лимит запросов, HTTP 429"
                    : status >= 500 ? "временная ошибка API, HTTP " + status
                    : "HTTP " + status);
            this.status = status;
        }
    }

    private static Report.Status status(Exception e) {
        if (e instanceof InvalidResponseException) return Report.Status.INVALID_RESPONSE;
        if (e instanceof ApiException api) return switch (api.status) {
            case 401 -> Report.Status.AUTH_REQUIRED;
            case 403 -> Report.Status.FORBIDDEN;
            case 429 -> Report.Status.RATE_LIMITED;
            default -> api.status >= 500 ? Report.Status.PROVIDER_UNAVAILABLE : Report.Status.INVALID_RESPONSE;
        };
        if (e instanceof HttpTimeoutException) return Report.Status.NETWORK_ERROR;
        return e instanceof IOException ? Report.Status.NETWORK_ERROR : Report.Status.PROVIDER_UNAVAILABLE;
    }

    private static String code(Exception e) {
        if (e instanceof InvalidResponseException) return "INVALID_RESPONSE";
        if (e instanceof ApiException api) return "HTTP_" + api.status;
        if (e instanceof HttpTimeoutException) return "TIMEOUT";
        return e instanceof IOException ? "IO_ERROR" : "PROVIDER_ERROR";
    }

    private static IOException schema(String context) {
        return new InvalidResponseException("неожиданная схема ответа: " + context, null);
    }

    private static final class InvalidResponseException extends IOException {
        InvalidResponseException(String message, Throwable cause) { super(message, cause); }
    }

    private static String failure(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String withoutTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private static String https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) ? uri.toASCIIString() : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static boolean strictlyNewer(String installed, String candidate) {
        try {
            return Instant.parse(candidate).isAfter(Instant.parse(installed));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String fallback(String preferred, String other) {
        return preferred == null || preferred.isBlank() ? (other == null ? "" : other) : preferred;
    }

    private static String shortId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static Report.Download modrinthDownload(JsonObject version, String projectId,
                                                     String versionId, String pageUrl) {
        if (!version.has("files") || !version.get("files").isJsonArray()) return null;
        JsonObject selected = null;
        for (JsonElement element : version.getAsJsonArray("files")) {
            if (!element.isJsonObject()) continue;
            JsonObject file = element.getAsJsonObject();
            if (selected == null || (file.has("primary") && file.get("primary").getAsBoolean())) selected = file;
        }
        if (selected == null) return null;
        JsonObject hashes = selected.has("hashes") && selected.get("hashes").isJsonObject()
                ? selected.getAsJsonObject("hashes") : new JsonObject();
        String algorithm = hashes.has("sha512") ? "sha512" : hashes.has("sha1") ? "sha1" : "";
        return new Report.Download(Report.Provider.MODRINTH, projectId, versionId,
                string(selected, "filename"), https(string(selected, "url")), number(selected, "size"),
                algorithm, string(hashes, algorithm), string(version, "version_number"),
                string(version, "date_published"), pageUrl);
    }

    private static Report.Download curseForgeDownload(JsonObject file, long modId, long fileId, String pageUrl) {
        String algorithm = "";
        String hash = "";
        if (file.has("hashes") && file.get("hashes").isJsonArray()) {
            for (JsonElement element : file.getAsJsonArray("hashes")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                long algorithmCode = number(item, "algo");
                if (algorithmCode == 1 || algorithmCode == 2 || algorithmCode == 3) {
                    algorithm = algorithmCode == 1 ? "sha1" : algorithmCode == 2 ? "md5" : "sha256";
                    hash = string(item, "value");
                    if (!"md5".equals(algorithm)) break;
                }
            }
        }
        return new Report.Download(Report.Provider.CURSEFORGE, Long.toString(modId), Long.toString(fileId),
                string(file, "fileName"), https(string(file, "downloadUrl")), number(file, "fileLength"),
                algorithm, hash, fallback(string(file, "displayName"), string(file, "fileName")),
                string(file, "fileDate"), pageUrl);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonObject mapValue(JsonObject map, String key, String context) throws IOException {
        if (!map.has(key) || map.get(key).isJsonNull()) return null;
        if (!map.get(key).isJsonObject()) throw schema(context + " entry");
        return map.getAsJsonObject(key);
    }

    private static JsonObject requiredObject(JsonObject parent, String name, String context) throws IOException {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            throw schema(context + "." + name);
        }
        return parent.getAsJsonObject(name);
    }

    private static JsonArray requiredArray(JsonObject parent, String name, String context) throws IOException {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            throw schema(context + "." + name);
        }
        return parent.getAsJsonArray(name);
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || parent.get(name).isJsonNull()) return "";
        JsonElement element = parent.get(name);
        return element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static long number(JsonObject parent, String name) {
        try {
            return Long.parseLong(string(parent, name));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long requiredNumber(JsonObject parent, String name, String context) throws IOException {
        long value = number(parent, name);
        if (value < 0) throw schema(context + "." + name);
        return value;
    }
}
