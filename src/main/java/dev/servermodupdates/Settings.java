package dev.servermodupdates;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/** Reloaded on each command so administrators can change it without a restart. */
public record Settings(String curseforgeKey, boolean includeBeta, boolean includeAlpha,
                       boolean scanLegacyHostMidpack, int maxRetries, long retryBaseDelayMillis,
                       long retryMaxDelayMillis, int requestTimeoutSeconds, int checkBudgetSeconds,
                       boolean enableAutoModpack, Language language, int reportRetentionCount) {
    public static final String FILE_NAME = "modupdates.properties";

    public enum Language {
        EN_US("en_us"), RU_RU("ru_ru");

        private final String configValue;
        Language(String configValue) { this.configValue = configValue; }
        public String configValue() { return configValue; }
    }

    public Settings(String key, boolean beta, boolean alpha, boolean legacy) {
        this(key, beta, alpha, legacy, 2, 500, 10_000, 25, 180,
                false, Language.EN_US, 0);
    }

    public Settings(String key, boolean beta, boolean alpha, boolean legacy, int retries,
                    long baseDelay, long maxDelay, int timeout, int budget) {
        this(key, beta, alpha, legacy, retries, baseDelay, maxDelay, timeout, budget,
                false, Language.EN_US, 0);
    }

    public record LoadResult(Settings settings, Path path, boolean created, boolean environmentKey,
                             boolean languageFallback) { }

    public static LoadResult load(Path configDirectory) throws IOException {
        return load(configDirectory, System::getenv);
    }

    static LoadResult load(Path configDirectory, Function<String, String> environment) throws IOException {
        Path file = configDirectory.toAbsolutePath().normalize().resolve(FILE_NAME);
        Files.createDirectories(file.getParent());
        boolean created = Files.notExists(file);
        Properties defaults = defaults();
        Properties props = new Properties();
        if (!created) {
            try (InputStream in = Files.newInputStream(file)) { props.load(in); }
        }
        boolean migrated = false;
        for (String property : defaults.stringPropertyNames()) {
            if (!props.containsKey(property)) {
                props.setProperty(property, defaults.getProperty(property));
                migrated = true;
            }
        }
        if (created || migrated) {
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "ServerModUpdater 1.2.0; keep CurseForge API keys private");
            }
        }
        String env = trim(environment.apply("MODUPDATES_CURSEFORGE_API_KEY"));
        boolean environmentKey = !env.isBlank();
        String key = environmentKey ? env : trim(props.getProperty("curseforgeApiKey"));
        String requestedLanguage = trim(props.getProperty("language")).toLowerCase(Locale.ROOT);
        Language language = switch (requestedLanguage) {
            case "ru_ru" -> Language.RU_RU;
            case "en_us" -> Language.EN_US;
            default -> Language.EN_US;
        };
        boolean languageFallback = !requestedLanguage.equals("en_us") && !requestedLanguage.equals("ru_ru");
        Settings settings = new Settings(key,
                bool(props, "includeBeta"), bool(props, "includeAlpha"),
                bool(props, "scanLegacyHostMidpack"), integer(props, "maxRetries", 0, 5),
                number(props, "retryBaseDelayMillis", 100, 10_000),
                number(props, "retryMaxDelayMillis", 100, 60_000),
                integer(props, "requestTimeoutSeconds", 5, 60),
                integer(props, "checkBudgetSeconds", 30, 900),
                bool(props, "enableAutoModpack"), language,
                integer(props, "reportRetentionCount", 0, 10_000));
        return new LoadResult(settings, file, created, environmentKey, languageFallback);
    }

    public boolean curseforgeEnabled() { return !curseforgeKey.isBlank(); }

    @Override
    public String toString() {
        return "Settings[curseforgeKey=" + (curseforgeEnabled() ? "<configured>" : "<empty>")
                + ", includeBeta=" + includeBeta + ", includeAlpha=" + includeAlpha
                + ", enableAutoModpack=" + enableAutoModpack
                + ", scanLegacyHostMidpack=" + scanLegacyHostMidpack + ", language=" + language.configValue()
                + ", reportRetentionCount=" + reportRetentionCount + ", maxRetries=" + maxRetries
                + ", retryBaseDelayMillis=" + retryBaseDelayMillis + ", retryMaxDelayMillis="
                + retryMaxDelayMillis + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                + ", checkBudgetSeconds=" + checkBudgetSeconds + "]";
    }

    private static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("curseforgeApiKey", "");
        p.setProperty("includeBeta", "false");
        p.setProperty("includeAlpha", "false");
        p.setProperty("enableAutoModpack", "false");
        p.setProperty("scanLegacyHostMidpack", "true");
        p.setProperty("language", "en_us");
        p.setProperty("reportRetentionCount", "0");
        p.setProperty("maxRetries", "2");
        p.setProperty("retryBaseDelayMillis", "500");
        p.setProperty("retryMaxDelayMillis", "10000");
        p.setProperty("requestTimeoutSeconds", "25");
        p.setProperty("checkBudgetSeconds", "180");
        return p;
    }

    private static boolean bool(Properties p, String key) { return Boolean.parseBoolean(p.getProperty(key)); }

    private static int integer(Properties p, String key, int min, int max) throws IOException {
        return (int) number(p, key, min, max);
    }

    private static long number(Properties p, String key, long min, long max) throws IOException {
        try {
            long value = Long.parseLong(p.getProperty(key).trim());
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (RuntimeException e) {
            throw new IOException("Invalid setting value: " + key);
        }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
