package com.example.reportsystem.config;

import com.example.reportsystem.util.Text;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ConfigManager {
    private final Path dataDir;
    private final Path configPath;

    public ConfigManager(Path dataDir) {
        this.dataDir = dataDir;
        this.configPath = dataDir.resolve("config.yml");
    }

    public PluginConfig loadOrCreate() throws IOException {
        if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
        if (!Files.exists(configPath)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) throw new FileNotFoundException("default config.yml missing in jar");
                Files.copy(in, configPath);
            }
        }
        Yaml yaml = new Yaml();
        try (Reader reader = Files.newBufferedReader(configPath)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = yaml.load(reader);
            return parse(root);
        }
    }

    @SuppressWarnings("unchecked")
    private PluginConfig parse(Map<String, Object> root) {
        PluginConfig pc = new PluginConfig();

        // Core
        pc.allowSelfReport = get(root, "allow-self-report", false);
        pc.stackWindowSeconds = get(root, "stack-window-seconds", 600);
        pc.exportHtmlChatlog = get(root, "export-html-chatlog", true);
        pc.htmlExportDir = get(root, "html-export-dir", "html-logs");
        pc.reportsPerPage = get(root, "reports-per-page", 10);
        pc.staffPermission = get(root, "staff-permission", "reportsystem.reports");
        pc.notifyPermission = get(root, "notify-permission", "reportsystem.notify");

        // Preview + public URL
        pc.previewLines = get(root, "preview-lines", 10);
        pc.previewLineMaxChars = get(root, "preview-line-max-chars", 200);
        pc.publicBaseUrl = get(root, "public-base-url", "");

        // Priority sorting / thresholds / colors
        pc.prioritySorting = get(root, "priority-sorting", true);
        pc.tieBreaker = get(root, "tie-breaker", "newest");

        pc.threshYellow = get(root, "stack-thresholds.yellow", 3);
        pc.threshGold = get(root, "stack-thresholds.gold", 5);
        pc.threshRed = get(root, "stack-thresholds.red", 10);
        pc.threshDarkRed = get(root, "stack-thresholds.dark-red", 15);

        pc.colorYellow = get(root, "stack-colors.yellow", "<yellow>");
        pc.colorGold   = get(root, "stack-colors.gold", "<gold>");
        pc.colorRed    = get(root, "stack-colors.red", "<red>");
        pc.colorDarkRed= get(root, "stack-colors.dark-red", "<dark_red>");

        // HTTP server
        Map<String,Object> http = (Map<String,Object>) root.getOrDefault("http-server", Map.of());
        pc.httpServer.enabled = get(http, "enabled", false);
        pc.httpServer.externalBaseUrl = get(http, "external-base-url", "");
        pc.httpServer.bind = get(http, "bind", "0.0.0.0");
        pc.httpServer.port = get(http, "port", 8085);
        pc.httpServer.basePath = get(http, "base-path", "/");

        // Discord
        Map<String,Object> d = (Map<String,Object>) root.getOrDefault("discord", Map.of());
        pc.discord.enabled   = get(d, "enabled", false);
        pc.discord.webhookUrl= get(d, "webhook-url", "");
        pc.discord.username  = get(d, "username", "ReportSystem");
        pc.discord.avatarUrl = get(d, "avatar-url", "");
        pc.discord.timeoutMs = get(d, "timeout-ms", 4000);

        // Messages
        pc.messages = (Map<String, Object>) root.getOrDefault("messages", Map.of());

        // Dynamic report types
        Map<String, Object> rtypes = (Map<String, Object>) root.getOrDefault("report-types", Map.of());
        for (String key : rtypes.keySet()) {
            Map<String, Object> t = (Map<String, Object>) rtypes.get(key);
            PluginConfig.ReportTypeDef def = new PluginConfig.ReportTypeDef();
            def.display = (String) t.getOrDefault("display", key);
            Map<String, Object> rawCats = (Map<String, Object>) t.getOrDefault("categories", Map.of());
            for (String ck : rawCats.keySet()) {
                def.categories.put(ck, String.valueOf(rawCats.get(ck)));
            }
            pc.reportTypes.put(key.toLowerCase(), def);
        }

        Text.setPrefix(pc.msg("prefix", ""));
        return pc;
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Map<String, Object> m, String k, T def) {
        if (m == null) return def;
        Object v;
        if (k.contains(".")) {
            String[] parts = k.split("\\.");
            Map<String, Object> cur = m;
            for (int i = 0; i < parts.length - 1; i++) {
                Object nxt = cur.get(parts[i]);
                if (!(nxt instanceof Map)) return def;
                cur = (Map<String, Object>) nxt;
            }
            v = cur.get(parts[parts.length - 1]);
        } else {
            v = m.get(k);
        }
        return v == null ? def : (T) v;
    }
}
