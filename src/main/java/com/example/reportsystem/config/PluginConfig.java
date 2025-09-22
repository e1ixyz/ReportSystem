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

    public Map<String, Object> messages = new LinkedHashMap<>();
    public Map<String, ReportTypeDef> reportTypes = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public String msg(String key, String def) {
        Object v = messages.get(key);
        return v instanceof String ? (String) v : def;
    }

    @SuppressWarnings("unchecked")
    public List<String> msgList(String key, List<String> def) {
        Object v = messages.get(key);
        return v instanceof List ? (List<String>) v : def;
    }

    public static class ReportTypeDef {
        public String display;
        public Map<String, String> categories = new LinkedHashMap<>();
    }
}
