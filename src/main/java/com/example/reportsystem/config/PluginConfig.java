package com.example.reportsystem.config;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime config with sensible defaults.
 * Includes multi-factor priority, cooldown, auth, and HTTP fields.
 */
public class PluginConfig {
    // Core
    public boolean allowSelfReport = false;
    public int stackWindowSeconds = 600;
    public boolean exportHtmlChatlog = true;
    public String htmlExportDir = "html-logs";

    public int reportsPerPage = 10;
    /** Staff-level permission (can see /reports etc.) */
    public String staffPermission = "reportsystem.reports";
    /** Admin-level permission (reload, logoutall, force-claim) */
    public String adminPermission = "reportsystem.admin";
    /** Force-claim permission (also implied by admin) */
    public String forceClaimPermission = "reportsystem.forceclaim";
    public String notifyPermission = "reportsystem.notify";

    // Cooldown (seconds). Staff/staffPermission holders bypass.
    public int reportCooldownSeconds = 60;

    // Inline preview safety
    public int previewLines = 10;
    public int previewLineMaxChars = 200;

    // Public web base (optional). If empty, ReportsCommand will prefer http-server.externalBaseUrl when enabled.
    public String publicBaseUrl = "";

    // Priority sorting defaults & colors
    public String tieBreaker = "newest";       // "newest" or "oldest"
    public int threshYellow = 3;
    public int threshGold = 5;
    public int threshRed = 10;
    public int threshDarkRed = 15;

    public String colorYellow = "<yellow>";
    public String colorGold = "<gold>";
    public String colorRed = "<red>";
    public String colorDarkRed = "<dark_red>";

    // Storage backend selection
    public StorageConfig storage = new StorageConfig();

    // Optional embedded HTTP server
    public HttpServerConfig httpServer = new HttpServerConfig();

    // Discord webhook (existing)
    public DiscordConfig discord = new DiscordConfig();

    // Lightweight web auth options used by WebServer/AuthService
    public AuthConfig auth = new AuthConfig();

    // NEW: Multi-factor priority configuration
    public PriorityConfig priority = new PriorityConfig();

    // Messages & dynamic types
    public Map<String, Object> messages = new LinkedHashMap<>();
    public Map<String, ReportTypeDef> reportTypes = new LinkedHashMap<>();

    // Helpers
    public String msg(String key, String def) {
        Object v = messages.get(key);
        return v instanceof String s ? s : def;
    }
    @SuppressWarnings("unchecked")
    public List<String> msgList(String key, List<String> def) {
        Object v = messages.get(key);
        return v instanceof List<?> list ? (List<String>) list : def;
    }

    // Nested configs
    public static class ReportTypeDef {
        public String display;
        public Map<String, String> categories = new LinkedHashMap<>();
    }

    public static class DiscordConfig {
        public boolean enabled = false;
        public String webhookUrl = "";
        public String username = "ReportSystem";
        public String avatarUrl = "";
        public int timeoutMs = 4000;
    }

    public static class StorageConfig {
        public enum Type { YAML, MYSQL }

        public Type type = Type.YAML;
        public MySqlConfig mysql = new MySqlConfig();
    }

    public static class MySqlConfig {
        public String host = "127.0.0.1";
        public int port = 3306;
        public String database = "reportsystem";
        public String username = "root";
        public String password = "password";
        public boolean useSsl = true;
        public boolean allowPublicKeyRetrieval = false;
        public String connectionOptions = "characterEncoding=UTF-8&useUnicode=true";
        public String tableReports = "reports";
        public String tableChat = "report_chat";
        public int connectionPoolSize = 8;
    }

    public static class HttpServerConfig {
        public boolean enabled = false;
        /** Example: "https://public.domain/reports" */
        public String externalBaseUrl = "";
        public String bind = "0.0.0.0";
        public int port = 8085;
        /** Mount path inside the tiny server (default "/"). */
        public String basePath = "/";
    }

    /** Auth block used by WebServer/AuthService/ReportsCommand */
    public static class AuthConfig {
        public boolean enabled = true;
        public String cookieName = "rsid";
        /** minutes; sliding session extension */
        public int sessionTtlMinutes = 60 * 24; // 24h
        /** seconds for one-time code validity */
        public int codeTtlSeconds = 120;
        /** digits in the one-time code */
        public int codeLength = 6;
        /** optional signing secret for sessions */
        public String secret = "change-me";
        /** allow unauthenticated paths when auth is enabled */
        public List<String> openPaths = List.of("/login", "/favicon.ico");
        /** require staff perm to request codes via /reports auth */
        public boolean requirePermission = true;
    }

    /**
     * Multi-factor priority configuration. Each factor contributes a weighted score
     * when enabled; the intent is to keep values near the 0–1 range so weighting stays
     * intuitive for server owners tweaking config.yml.
     */
    public static class PriorityConfig {
        public boolean enabled = true;

        public boolean useCount = true;
        public boolean useRecency = true;
        public boolean useSeverity = true;
        public boolean useEvidence = true;
        public boolean useUnassigned = true;
        public boolean useAging = true;
        public boolean useSlaBreach = true;

        public double weightCount = 2.0;
        public double weightRecency = 2.0;
        public double weightSeverity = 3.0;
        public double weightEvidence = 1.0;
        public double weightUnassigned = 0.5;
        public double weightAging = 1.0;
        public double weightSlaBreach = 4.0;

        /** Exponential decay constant for recency in milliseconds. */
        public double tauMs = 900_000d; // 15 minutes by default

        /** "typeId/categoryId" -> severity weight. */
        public Map<String, Double> severityByKey = new LinkedHashMap<>();

        /** SLA targets in minutes per "typeId/categoryId" key. */
        public Map<String, Integer> slaMinutes = new LinkedHashMap<>();
    }

    /* -------------------- minimal loader stubs -------------------- */
    public static PluginConfig loadOrCreate(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (Exception ignored) {}
        return new PluginConfig();
    }
    public static PluginConfig loadOrCreate(Path dataDir, Logger logger) {
        return loadOrCreate(dataDir);
    }
}
