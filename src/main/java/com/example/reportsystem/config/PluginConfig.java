package com.example.reportsystem.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // Optional embedded HTTP server (lets staff click links without a domain; use proxy IP:port)
    public HttpServerConfig httpServer = new HttpServerConfig();

    // Discord webhook (existing)
    public DiscordConfig discord = new DiscordConfig();

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
        public String externalBaseUrl = ""; // e.g. "http://123.45.67.89:8085"
        public String bind = "0.0.0.0";
        public int port = 8085;
        public String basePath = "/";
    }
}
