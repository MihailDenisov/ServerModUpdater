package dev.servermodupdates;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Immutable, path-keyed plan created by a completed scan. */
public final class UpdatePlan {
    public record Item(String id, ModScanner.Jar installed,
                       Map<Report.Provider, Report.Download> providers) {
        public boolean actionable() { return providers.values().stream().anyMatch(Report.Download::actionable); }
        public Optional<Report.Download> provider(Report.Provider provider) {
            return Optional.ofNullable(providers.get(provider)).filter(Report.Download::actionable);
        }
    }

    public record Confirmation(String token, Instant expiresAt) {
        boolean valid(String supplied, Instant now) {
            return supplied != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                    supplied.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))
                    && now.isBefore(expiresAt);
        }
    }

    private final Instant scanTime;
    private final List<Item> items;
    private final Map<String, Item> byId;
    private volatile Confirmation confirmation;

    private UpdatePlan(Instant scanTime, List<Item> items) {
        this.scanTime = scanTime;
        this.items = List.copyOf(items);
        LinkedHashMap<String, Item> indexed = new LinkedHashMap<>();
        items.forEach(item -> indexed.put(item.id(), item));
        this.byId = Map.copyOf(indexed);
    }

    public static UpdatePlan from(Report report) {
        List<Item> items = new ArrayList<>();
        for (ModScanner.Jar jar : report.scanned) {
            String id = stableId(jar.relative());
            Map<Report.Provider, Report.Download> providers = report.downloads.containsKey(jar.relative())
                    ? Map.copyOf(report.downloads.get(jar.relative())) : Map.of();
            items.add(new Item(id, jar, providers));
        }
        return new UpdatePlan(report.time, items);
    }

    public Instant scanTime() { return scanTime; }
    public List<Item> items() { return items; }
    public List<Item> actionable() { return items.stream().filter(Item::actionable).toList(); }
    public Optional<Item> find(String id) {
        return Optional.ofNullable(byId.get(id == null ? "" : id.toUpperCase(Locale.ROOT)));
    }

    public synchronized Confirmation issueConfirmation(Instant now) {
        byte[] bytes = new byte[5];
        new SecureRandom().nextBytes(bytes);
        confirmation = new Confirmation(HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT),
                now.plus(Duration.ofMinutes(5)));
        return confirmation;
    }

    public synchronized boolean consumeConfirmation(String token, Instant now) {
        Confirmation current = confirmation;
        confirmation = null;
        return current != null && current.valid(token, now);
    }

    public synchronized Optional<Confirmation> confirmation() { return Optional.ofNullable(confirmation); }

    static String stableId(String relativePath) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(relativePath.replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            return "MU-" + HexFormat.of().formatHex(digest, 0, 6).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
