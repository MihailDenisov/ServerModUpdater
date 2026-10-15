package dev.servermodupdates;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class Report {
    public enum Provider { MODRINTH, CURSEFORGE }
    public enum Status {
        PENDING, UPDATE_AVAILABLE, UP_TO_DATE, NOT_FOUND_BY_HASH, PROVIDER_UNAVAILABLE,
        AUTH_REQUIRED, FORBIDDEN, RATE_LIMITED, NETWORK_ERROR, INVALID_RESPONSE, SKIPPED
    }
    public record Outcome(Status status, String code, String installed, String available, String url) {
        static Outcome of(Status status, String code) { return new Outcome(status, code, "", "", ""); }
    }
    public record Update(String service, String mod, String installed, String available,
                         String url, String path, String releaseDate) { }
    public record Download(Provider provider, String projectId, String fileId, String fileName,
                           String downloadUrl, long size, String hashAlgorithm, String hash,
                           String version, String releasedAt, String pageUrl) {
        public boolean actionable() {
            return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".jar")
                    && downloadUrl != null && downloadUrl.startsWith("https://")
                    && size > 0 && hash != null && !hash.isBlank()
                    && ("sha1".equals(hashAlgorithm) || "sha256".equals(hashAlgorithm)
                    || "sha512".equals(hashAlgorithm));
        }
    }

    public final Instant time = Instant.now();
    public final List<ModScanner.Jar> scanned = new ArrayList<>();
    public final List<Update> updates = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final Map<String, EnumMap<Provider, Outcome>> outcomes = new LinkedHashMap<>();
    public final Map<String, EnumMap<Provider, Download>> downloads = new LinkedHashMap<>();
    public boolean modrinthSuccess = true;
    public boolean curseforgeSuccess = true;
    public boolean curseforgeEnabled;

    public void initializeOutcomes() {
        for (ModScanner.Jar jar : scanned) {
            EnumMap<Provider, Outcome> map = new EnumMap<>(Provider.class);
            map.put(Provider.MODRINTH, Outcome.of(Status.PENDING, "PENDING"));
            map.put(Provider.CURSEFORGE, Outcome.of(curseforgeEnabled ? Status.PENDING : Status.SKIPPED,
                    curseforgeEnabled ? "PENDING" : "KEY_NOT_CONFIGURED"));
            outcomes.put(jar.relative(), map);
            downloads.put(jar.relative(), new EnumMap<>(Provider.class));
        }
    }

    public void set(Provider provider, ModScanner.Jar jar, Status status, String code) {
        set(provider, jar, new Outcome(status, code, "", "", ""));
    }

    public void set(Provider provider, ModScanner.Jar jar, Outcome outcome) {
        outcomes.get(jar.relative()).put(provider, outcome);
    }

    public void setDownload(Provider provider, ModScanner.Jar jar, Download download) {
        downloads.get(jar.relative()).put(provider, download);
    }

    public void setPending(Provider provider, Status status, String code) {
        outcomes.values().forEach(map -> {
            if (map.get(provider).status() == Status.PENDING) map.put(provider, Outcome.of(status, code));
        });
    }

    public long filesWithUpdates() { return scanned.stream().filter(this::hasUpdate).count(); }
    public long unresolvedCount() { return scanned.stream().filter(this::isUnresolved).count(); }
    public long unmatchedCount() { return scanned.stream().filter(this::isUnknown).count(); }
    public long unknownCount() { return unmatchedCount(); }
    public long matchedWithoutUpdateCount() { return scanned.stream().filter(this::isUpToDate).count(); }

    public String providerSummary(Provider provider) {
        return providerSummary(provider, Settings.Language.RU_RU);
    }

    public String providerSummary(Provider provider, Settings.Language language) {
        EnumMap<Status, Integer> counts = new EnumMap<>(Status.class);
        outcomes.values().forEach(map -> counts.merge(map.get(provider).status(), 1, Integer::sum));
        String empty = language == Settings.Language.RU_RU ? "нет файлов" : "no files";
        if (counts.isEmpty()) return empty;
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right).orElse(empty);
    }

    boolean hasUpdate(ModScanner.Jar jar) { return values(jar).stream().anyMatch(o -> o.status() == Status.UPDATE_AVAILABLE); }
    boolean isUnresolved(ModScanner.Jar jar) {
        return !hasUpdate(jar) && values(jar).stream().anyMatch(o -> switch (o.status()) {
            case PROVIDER_UNAVAILABLE, AUTH_REQUIRED, FORBIDDEN, RATE_LIMITED, NETWORK_ERROR,
                    INVALID_RESPONSE, PENDING -> true;
            default -> false;
        });
    }
    boolean isUpToDate(ModScanner.Jar jar) {
        return !hasUpdate(jar) && !isUnresolved(jar)
                && values(jar).stream().anyMatch(o -> o.status() == Status.UP_TO_DATE);
    }
    boolean isUnknown(ModScanner.Jar jar) { return !hasUpdate(jar) && !isUnresolved(jar) && !isUpToDate(jar); }
    List<Outcome> values(ModScanner.Jar jar) { return List.copyOf(outcomes.get(jar.relative()).values()); }
}
