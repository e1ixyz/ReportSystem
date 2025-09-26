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
     * Multi-factor priority:
     * Each factor can be enabled/disabled and weighted. Final score is:
     *   score = Σ (enabled ? weight * factorValue : 0)
     * Factors:
     *   - stackCount: more duplicate reports → higher priority
     *   - recencyDecay: newer reports score more; decays by half-life (minutes)
     *   - categoryWeight: per-category boost (via map)
     *   - targetOnline: boost if reported player is currently online
     *   - unassignedBoost: boost if nobody has claimed it yet
     *   - escalatingStatus: optional future use (left on for extension)
     */
    public static class PriorityConfig {
        public boolean enabled = true;

        public boolean useStackCount = true;
        public double weightStackCount = 1.0;

        public boolean useRecencyDecay = true;
        public double weightRecencyDecay = 1.0;
        /** minutes until score halves (e.g., 60 = half every hour) */
        public int recencyHalfLifeMinutes = 60;

        public boolean useCategoryWeight = true;
        public double weightCategory = 1.0;
        /** categoryId -> numeric weight (e.g., "cheating": 2.0) */
        public Map<String, Double> categoryWeights = new LinkedHashMap<>();

        public boolean useTargetOnline = true;
        public double weightTargetOnline = 0.5;

        public boolean useUnassignedBoost = true;
        public double weightUnassigned = 0.25;

        // placeholder for future expansion
        public boolean useEscalatingStatus = false;
        public double weightEscalatingStatus = 0.0;
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
