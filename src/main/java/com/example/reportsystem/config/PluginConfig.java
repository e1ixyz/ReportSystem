package com.example.reportsystem.config;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime config bag with sensible defaults.
 * NOTE: Minimal loader stubs included so older callsites like
 * PluginConfig.loadOrCreate(Path, Logger) compile. If you already
 * have a real YAML loader elsewhere, keep it and just ensure the
 * new Auth fields exist.
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

    // Inline preview safety
    public int previewLines = 10;
    public int previewLineMaxChars = 200;

    // Public web base (optional). If empty, ReportsCommand will prefer http-server.externalBaseUrl when enabled.
    public String publicBaseUrl = "";

    // Priority sorting & stack badge colors
    public boolean prioritySorting = true;     // stacked first
    public String tieBreaker = "newest";       // "newest" or "oldest"

    public int threshYellow = 3;               // >3 -> yellow
    public int threshGold = 5;                 // >5 -> gold
    public int threshRed = 10;                 // >10 -> red
    public int threshDarkRed = 15;             // >15 -> dark red

    public String colorYellow = "<yellow>";
    public String colorGold = "<gold>";
    public String colorRed = "<red>";
    public String colorDarkRed = "<dark_red>";

    // Optional embedded HTTP server
    public HttpServerConfig httpServer = new HttpServerConfig();

    // Discord webhook (existing)
    public DiscordConfig discord = new DiscordConfig();

    // NEW: lightweight web auth options used by WebServer/AuthService
    public AuthConfig auth = new AuthConfig();

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
        /** Example: "https://reports.example.com" or "https://map.example.com/reports" */
        public String externalBaseUrl = "";
        public String bind = "0.0.0.0";
        public int port = 8085;
        /** Mount path inside the tiny server (default "/"). */
        public String basePath = "/";
    }

    /** NEW: auth block used by WebServer/AuthService/ReportsCommand */
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

    /* -------------------- minimal loader stubs -------------------- */
    public static PluginConfig loadOrCreate(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (Exception ignored) {}
        // If you have a real YAML loader, use it here and populate this class.
        // Stub returns defaults so the plugin can boot even without a file.
        return new PluginConfig();
    }
    public static PluginConfig loadOrCreate(Path dataDir, Logger logger) {
        return loadOrCreate(dataDir);
    }
}
