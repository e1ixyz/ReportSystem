package com.example.reportsystem.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PluginConfig {
    public boolean allowSelfReport = false;
    public int stackWindowSeconds = 600;
    public boolean exportHtmlChatlog = true;
    public String htmlExportDir = "html-logs";
    public int reportsPerPage = 10;
    public String staffPermission = "reportsystem.reports";
    public String notifyPermission = "reportsystem.notify";

    // NEW: safe preview + public URL options
    public int previewLines = 10;              // max lines to preview inline
    public int previewLineMaxChars = 200;      // max chars per preview line
    public String publicBaseUrl = "";          // e.g. https://reports.example.com

    public DiscordConfig discord = new DiscordConfig();

    public Map<String, Object> messages = new LinkedHashMap<>();
    public Map<String, ReportTypeDef> reportTypes = new LinkedHashMap<>();

    public String msg(String key, String def) {
        Object v = messages.get(key);
        return v instanceof String s ? s : def;
    }
    @SuppressWarnings("unchecked")
    public List<String> msgList(String key, List<String> def) {
        Object v = messages.get(key);
        return v instanceof List<?> list ? (List<String>) list : def;
    }

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
}
