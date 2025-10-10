package com.example.reportsystem.config;

import com.example.reportsystem.util.Text;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        pc.allowSelfReport      = get(root, "allow-self-report", false);
        pc.stackWindowSeconds   = get(root, "stack-window-seconds", 600);
        pc.exportHtmlChatlog    = get(root, "export-html-chatlog", true);
        pc.htmlExportDir        = get(root, "html-export-dir", "html-logs");
        pc.reportsPerPage       = get(root, "reports-per-page", 10);
        pc.staffPermission      = get(root, "staff-permission", "reportsystem.reports");
        pc.adminPermission      = get(root, "admin-permission", "reportsystem.admin");
        pc.forceClaimPermission = get(root, "force-claim-permission", "reportsystem.forceclaim");
        pc.notifyPermission     = get(root, "notify-permission", "reportsystem.notify");
        pc.reportCooldownSeconds= get(root, "report-cooldown-seconds", 60);

        // Preview + public URL
        pc.previewLines         = get(root, "preview-lines", 10);
        pc.previewLineMaxChars  = get(root, "preview-line-max-chars", 200);
        pc.publicBaseUrl        = get(root, "public-base-url", "");

        // Tie breaker + colors
        pc.tieBreaker           = get(root, "tie-breaker", "newest");
        pc.threshYellow         = get(root, "stack-thresholds.yellow", 3);
        pc.threshGold           = get(root, "stack-thresholds.gold", 5);
        pc.threshRed            = get(root, "stack-thresholds.red", 10);
        pc.threshDarkRed        = get(root, "stack-thresholds.dark-red", 15);
        pc.colorYellow          = get(root, "stack-colors.yellow", "<yellow>");
        pc.colorGold            = get(root, "stack-colors.gold", "<gold>");
        pc.colorRed             = get(root, "stack-colors.red", "<red>");
        pc.colorDarkRed         = get(root, "stack-colors.dark-red", "<dark_red>");

        // Storage backend
        Map<String,Object> storage = (Map<String,Object>) root.getOrDefault("storage", Map.of());
        String typeStr = get(storage, "type", pc.storage.type.name());
        try {
            pc.storage.type = PluginConfig.StorageConfig.Type.valueOf(typeStr.toUpperCase());
        } catch (Exception ignored) {
            pc.storage.type = PluginConfig.StorageConfig.Type.YAML;
        }
        Map<String,Object> mysql = (Map<String,Object>) storage.getOrDefault("mysql", Map.of());
        pc.storage.mysql.host = get(mysql, "host", pc.storage.mysql.host);
        pc.storage.mysql.port = get(mysql, "port", pc.storage.mysql.port);
        pc.storage.mysql.database = get(mysql, "database", pc.storage.mysql.database);
        pc.storage.mysql.username = get(mysql, "username", pc.storage.mysql.username);
        pc.storage.mysql.password = get(mysql, "password", pc.storage.mysql.password);
        pc.storage.mysql.useSsl = get(mysql, "use-ssl", pc.storage.mysql.useSsl);
        pc.storage.mysql.allowPublicKeyRetrieval = get(mysql, "allow-public-key-retrieval", pc.storage.mysql.allowPublicKeyRetrieval);
        pc.storage.mysql.connectionOptions = get(mysql, "connection-options", pc.storage.mysql.connectionOptions);
        pc.storage.mysql.tableReports = get(mysql, "table-reports", pc.storage.mysql.tableReports);
        pc.storage.mysql.tableChat = get(mysql, "table-chat", pc.storage.mysql.tableChat);
        pc.storage.mysql.connectionPoolSize = get(mysql, "pool-size", pc.storage.mysql.connectionPoolSize);

        // HTTP server
        Map<String,Object> http = (Map<String,Object>) root.getOrDefault("http-server", Map.of());
        pc.httpServer.enabled        = get(http, "enabled", false);
        pc.httpServer.externalBaseUrl= get(http, "external-base-url", "");
        pc.httpServer.bind           = get(http, "bind", "0.0.0.0");
        pc.httpServer.port           = get(http, "port", 8085);
        pc.httpServer.basePath       = get(http, "base-path", "/");

        // Discord
        Map<String,Object> d = (Map<String,Object>) root.getOrDefault("discord", Map.of());
        pc.discord.enabled   = get(d, "enabled", false);
        pc.discord.webhookUrl= get(d, "webhook-url", "");
        pc.discord.username  = get(d, "username", "ReportSystem");
        pc.discord.avatarUrl = get(d, "avatar-url", "");
        pc.discord.timeoutMs = get(d, "timeout-ms", 4000);

        // Auth
        Map<String,Object> a = (Map<String,Object>) root.getOrDefault("auth", Map.of());
        pc.auth.enabled            = get(a, "enabled", true);
        pc.auth.cookieName         = get(a, "cookie-name", "rsid");
        pc.auth.sessionTtlMinutes  = get(a, "session-ttl-minutes", 60 * 24);
        pc.auth.codeTtlSeconds     = get(a, "code-ttl-seconds", 120);
        pc.auth.codeLength         = get(a, "code-length", 6);
        pc.auth.secret             = get(a, "secret", "change-me");
        pc.auth.requirePermission  = get(a, "require-permission", true);
        Object openPathsObj = a.get("open-paths");
        if (openPathsObj instanceof List<?> lst) {
            pc.auth.openPaths = (List<String>) (List<?>) lst;
        }

        // Priority (multi-factor)
        Map<String,Object> pr = (Map<String,Object>) root.getOrDefault("priority", Map.of());
        pc.priority.enabled          = get(pr, "enabled", true);
        pc.priority.useCount         = bool(pr, "use-count", pc.priority.useCount);
        pc.priority.useRecency       = bool(pr, "use-recency", pc.priority.useRecency);
        pc.priority.useSeverity      = bool(pr, "use-severity", pc.priority.useSeverity);
        pc.priority.useEvidence      = bool(pr, "use-evidence", pc.priority.useEvidence);
        pc.priority.useUnassigned    = bool(pr, "use-unassigned", pc.priority.useUnassigned);
        pc.priority.useAging         = bool(pr, "use-aging", pc.priority.useAging);
        pc.priority.useSlaBreach     = bool(pr, "use-sla-breach", pc.priority.useSlaBreach);

        pc.priority.weightCount      = dbl(pr, "w-count", pc.priority.weightCount);
        pc.priority.weightRecency    = dbl(pr, "w-recency", pc.priority.weightRecency);
        pc.priority.weightSeverity   = dbl(pr, "w-severity", pc.priority.weightSeverity);
        pc.priority.weightEvidence   = dbl(pr, "w-evidence", pc.priority.weightEvidence);
        pc.priority.weightUnassigned = dbl(pr, "w-unassigned", pc.priority.weightUnassigned);
        pc.priority.weightAging      = dbl(pr, "w-aging", pc.priority.weightAging);
        pc.priority.weightSlaBreach  = dbl(pr, "w-sla-breach", pc.priority.weightSlaBreach);

        pc.priority.tauMs            = dbl(pr, "tau-ms", pc.priority.tauMs);

        Map<String,Object> severity = (Map<String,Object>) pr.getOrDefault("severity-by-key", Map.of());
        for (Map.Entry<String,Object> entry : severity.entrySet()) {
            pc.priority.severityByKey.put(entry.getKey().toLowerCase(), dbl(entry.getValue(), 0d));
        }

        Map<String,Object> sla = (Map<String,Object>) pr.getOrDefault("sla-minutes", Map.of());
        for (Map.Entry<String,Object> entry : sla.entrySet()) {
            pc.priority.slaMinutes.put(entry.getKey().toLowerCase(), (int)Math.max(0, dbl(entry.getValue(), 0d)));
        }

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

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    private static double dbl(Map<String, Object> m, String key, double def) {
        Object v = m == null ? null : m.get(key);
        return dbl(v, def);
    }

    private static double dbl(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignored) {
            return def;
        }
    }
}
