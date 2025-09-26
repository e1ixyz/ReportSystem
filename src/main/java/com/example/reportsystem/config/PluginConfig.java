package com.example.reportsystem.config;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime config bag with sensible defaults.
 * NOTE: If you have a separate YAML loader, keep it. This class only holds fields + defaults.
 */
public class PluginConfig {
    // Core
    public boolean allowSelfReport = false;
    public int stackWindowSeconds = 600;
    public boolean exportHtmlChatlog = true;
    public String htmlExportDir = "html-logs";

    public int reportsPerPage = 10;
    public String staffPermission = "reportsystem.reports"; // general staff
    public String adminPermission = "reportsystem.admin";   // admin-only ops (reload, logoutall, force claim)
    public String forceClaimPermission = "reportsystem.forceclaim"; // separate perm (admin implies force)

    public String notifyPermission = "reportsystem.notify";

    // Inline preview safety
    public int previewLines = 10;
    public int previewLineMaxChars = 200;

    // Optional public URL (preferred) when building links. If empty, we’ll fall back to http-server.externalBaseUrl.
    public String publicBaseUrl = "";

    // Legacy priority fallback
    public boolean prioritySorting = true;     // stacked first (legacy)
    public String tieBreaker = "newest";       // "newest" or "oldest" (legacy)

    // Stack badge thresholds/colors
    public int threshYellow = 3;
    public int threshGold   = 5;
    public int threshRed    = 10;
    public int threshDarkRed= 15;

    public String colorYellow = "<yellow>";
    public String colorGold   = "<gold>";
    public String colorRed    = "<red>";
    public String colorDarkRed= "<dark_red>";

    // Embedded HTTP server (served behind your tunnel / reverse proxy)
    public HttpServerConfig httpServer = new HttpServerConfig();

    // Discord webhook
    public DiscordConfig discord = new DiscordConfig();

    // Web auth
    public AuthConfig auth = new AuthConfig();

    // Report cooldown
    public CooldownConfig cooldown = new CooldownConfig();

    // Multi-factor priority scoring
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

    /* ================= nested configs ================= */

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
        /** Example: "https://reports.example.com" (no trailing slash) */
        public String externalBaseUrl = "";
        public String bind = "0.0.0.0";
        public int port = 8085;
        /** Mount path inside the tiny server (default "/"). */
        public String basePath = "/";
    }

    /** Auth used by WebServer/AuthService/ReportsCommand */
    public static class AuthConfig {
        public boolean enabled = true;
        public String cookieName = "rsid";
        /** minutes; sliding session extension */
        public int sessionTtlMinutes = 60 * 24; // 24h
        /** seconds for one-time code validity */
        public int codeTtlSeconds = 120;
        /** digits in the one-time code */
        public int codeLength = 6;
        /** signing secret */
        public String secret = "change-me";
        /** allow unauthenticated paths when auth is enabled */
        public List<String> openPaths = List.of("/login", "/favicon.ico");
        /** require staff perm to request codes via /reports auth */
        public boolean requirePermission = true;
    }

    /** Cooldown for filing reports (players without staffPermission are throttled) */
    public static class CooldownConfig {
        public boolean enabled = true;
        public int seconds = 60; // default 1 minute
    }

    /**
     * Multi-factor priority configuration.
     *
     * We compute a score per open report:
     *   score = w_count*Fcount + w_recency*Frecency + w_chat*Fchat + w_type*Ftype
     *
     * - Fcount: raw stack count (>=1). Higher = more reports about same target.
     * - Frecency: 1 / (1 + ageHours)  (fresh reports score higher; bounded in [0,1]).
     * - Fchat: 1 if chat messages exist, else 0 (chat-evidence bumps urgency).
     * - Ftype: type/category boost: typeBoost(typeId) + categoryBoost(categoryId)
     *
     * All factors can be toggled individually and carry weights (doubles).
     */
    public static class PriorityConfig {
        public boolean enabled = true;

        public Factor count   = new Factor(true, 1.0);  // strongest default signal
        public Factor recency = new Factor(true, 0.7);  // recent > old
        public Factor chat    = new Factor(true, 0.3);  // evidence available
        public Factor type    = new Factor(true, 0.4);  // domain-specific boosting

        /** Optional boosts; keys are lowercase typeId/categoryId */
        public Map<String, Double> typeBoosts = Map.of(
                "player", 0.6  // player reports are typically highest priority
        );
        public Map<String, Double> categoryBoosts = Map.of(
                "cheating", 0.6,
                "dupe",     0.5,
                "grief",    0.4,
                "chat",     0.2
        );
    }
    public static class Factor {
        public boolean enabled;
        public double weight;
        public Factor() {}
        public Factor(boolean enabled, double weight) { this.enabled = enabled; this.weight = weight; }
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
