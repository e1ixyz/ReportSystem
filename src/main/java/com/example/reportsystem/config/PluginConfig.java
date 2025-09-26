package com.example.reportsystem.config;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime config bag with sensible defaults.
 */
public class PluginConfig {
    // Core
    public boolean allowSelfReport = false;
    public int stackWindowSeconds = 600;
    public boolean exportHtmlChatlog = true;
    public String htmlExportDir = "html-logs";

    public int reportsPerPage = 10;
    public String staffPermission = "reportsystem.reports";
    public String notifyPermission = "reportsystem.notify";

    // Report cooldown (players WITHOUT staffPermission are throttled)
    public int reportCooldownSeconds = 60; // default 1 minute

    // Permission to force-claim reports already claimed by another staff member
    public String forceClaimPermission = "reportsystem.forceclaim";

    // Inline preview safety
    public int previewLines = 10;
    public int previewLineMaxChars = 200;

    // Public web base (optional)
    public String publicBaseUrl = "";

    // Legacy priority toggles (kept for back-compat)
    public boolean prioritySorting = true;     // still used as a master switch
    public String tieBreaker = "newest";       // "newest" or "oldest"

    // Stack badge thresholds/colors
    public int threshYellow = 3;
    public int threshGold = 5;
    public int threshRed = 10;
    public int threshDarkRed = 15;

    public String colorYellow = "<yellow>";
    public String colorGold = "<gold>";
    public String colorRed = "<red>";
    public String colorDarkRed = "<dark_red>";

    // Optional embedded HTTP server
    public HttpServerConfig httpServer = new HttpServerConfig();

    // Discord webhook
    public DiscordConfig discord = new DiscordConfig();

    // Web auth options (used by WebServer/AuthService)
    public AuthConfig auth = new AuthConfig();

    // NEW: Multi-factor priority settings
    public Priority priority = Priority.defaultsForLargeNetwork();

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

    public static class HttpServerConfig {
        public boolean enabled = false;
        public String externalBaseUrl = "";
        public String bind = "0.0.0.0";
        public int port = 8085;
        public String basePath = "/";
    }

    public static class AuthConfig {
        public boolean enabled = true;
        public String cookieName = "rsid";
        public int sessionTtlMinutes = 60 * 24;
        public int codeTtlSeconds = 120;
        public int codeLength = 6;
        public String secret = "change-me";
        public List<String> openPaths = List.of("/login", "/favicon.ico");
        public boolean requirePermission = true;
    }

    /** NEW: Multi-factor priority config (enable/disable & weight each factor). */
    public static class Priority {
        public boolean enabled = true;

        // Factor toggles
        public boolean useCount = true;
        public boolean useRecency = true;
        public boolean useSeverity = true;
        public boolean useEvidence = true;
        public boolean useUnassigned = true;
        public boolean useAging = true;
        public boolean useSlaBreach = true;

        // Weights ("rankings")
        public double wCount = 2.0;
        public double wRecency = 2.0;
        public double wSeverity = 3.0;
        public double wEvidence = 1.0;
        public double wUnassigned = 0.5;
        public double wAging = 1.0;
        public double wSlaBreach = 4.0;

        // Decay constant for recency (ms)
        public long tauMs = 15 * 60_000L; // 15 minutes

        // Per (type/category) overrides
        public Map<String, Double> severityByKey = new LinkedHashMap<>(); // "player/cheat" -> 3.0
        public Map<String, Integer> slaMinutes = new LinkedHashMap<>();   // "server/crash" -> 2

        public static Priority defaultsForLargeNetwork() {
            Priority p = new Priority();
            // Sensible defaults for busy networks:
            p.enabled = true;
            p.useCount = true;      p.wCount = 2.0;
            p.useRecency = true;    p.wRecency = 2.0;
            p.useSeverity = true;   p.wSeverity = 3.0;
            p.useEvidence = true;   p.wEvidence = 1.0;
            p.useUnassigned = true; p.wUnassigned = 0.5;
            p.useAging = true;      p.wAging = 1.0;
            p.useSlaBreach = true;  p.wSlaBreach = 4.0;
            p.tauMs = 15 * 60_000L;

            // Example baselines (edit in config.yml)
            p.severityByKey.put("player/cheat", 3.0);
            p.severityByKey.put("server/crash", 2.5);
            p.severityByKey.put("player/chat", 1.0);

            p.slaMinutes.put("player/cheat", 5);
            p.slaMinutes.put("server/crash", 2);
            return p;
        }
    }

    /* -------------------- minimal loader stubs -------------------- */
    public static PluginConfig loadOrCreate(Path dataDir) {
        try { Files.createDirectories(dataDir); } catch (Exception ignored) {}
        return new PluginConfig();
    }
    public static PluginConfig loadOrCreate(Path dataDir, Logger logger) {
        return loadOrCreate(dataDir);
    }
}
