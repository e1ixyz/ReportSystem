package com.example.reportsystem.storage;

import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.model.ReportStatus;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File-based storage using one YAML file per report (legacy behaviour).
 */
public class YamlReportStorage implements ReportStorage {

    private final Path storeDir;
    private final Logger log;
    private final Yaml yaml = new Yaml();

    public YamlReportStorage(Path dataDir, Logger logger) {
        this.storeDir = dataDir.resolve("reports");
        this.log = logger;
    }

    public void init() throws IOException {
        Files.createDirectories(storeDir);
    }

    @Override
    public List<StoredReport> loadAll() throws Exception {
        List<StoredReport> out = new ArrayList<>();
        if (!Files.isDirectory(storeDir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(storeDir, "*.yml")) {
            for (Path file : ds) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = yaml.load(reader);
                    if (map == null) continue;
                    Report report = fromMap(map);
                    if (report == null) continue;
                    long fallback = Math.max(report.timestamp, getLong(map.get("closedAt"), 0L));
                    if (report.chat != null && !report.chat.isEmpty()) {
                        fallback = Math.max(fallback, report.chat.get(report.chat.size() - 1).time);
                    }
                    long lastUpdate = getLong(map.get("lastUpdate"), fallback);
                    Long closedAt = getLong(map.get("closedAt"), 0L);
                    if (closedAt != null && closedAt <= 0) closedAt = null;
                    out.add(new StoredReport(report, lastUpdate, closedAt));
                }
            }
        }
        return out;
    }

    @Override
    public void save(Report report, long lastUpdateMillis, Long closedAt) throws Exception {
        Path file = storeDir.resolve(report.id + ".yml");
        Map<String, Object> map = toMap(report, closedAt == null ? 0L : closedAt, lastUpdateMillis);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            yaml.dump(map, writer);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public String backendId() {
        return "yaml";
    }

    private Map<String, Object> toMap(Report r, long closedAt, long lastUpdate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.id);
        map.put("reporter", nullIfBlank(r.reporter));
        map.put("reported", nullIfBlank(r.reported));
        map.put("typeId", r.typeId);
        map.put("typeDisplay", r.typeDisplay);
        map.put("categoryId", r.categoryId);
        map.put("categoryDisplay", r.categoryDisplay);
        map.put("reason", nullIfBlank(r.reason));
        map.put("count", r.count);
        map.put("timestamp", r.timestamp);
        map.put("status", r.status == null ? ReportStatus.OPEN.name() : r.status.name());
        map.put("assignee", nullIfBlank(r.assignee));
        map.put("sourceServer", nullIfBlank(r.sourceServer));
        map.put("closedAt", closedAt);
        map.put("lastUpdate", lastUpdate);
        if (r.chat != null && !r.chat.isEmpty()) {
            List<Map<String, Object>> chats = new ArrayList<>(r.chat.size());
            for (ChatMessage msg : r.chat) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("time", msg.time);
                cm.put("player", msg.player);
                cm.put("server", msg.server);
                cm.put("message", msg.message);
                chats.add(cm);
            }
            map.put("chat", chats);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Report fromMap(Map<String, Object> map) {
        try {
            Report r = new Report();
            r.id = getLong(map.get("id"), 0L);
            if (r.id <= 0) return null;
            r.reporter = asStr(map.get("reporter"));
            r.reported = asStr(map.get("reported"));
            r.typeId = asStr(map.get("typeId"));
            r.typeDisplay = asStr(map.get("typeDisplay"));
            r.categoryId = asStr(map.get("categoryId"));
            r.categoryDisplay = asStr(map.get("categoryDisplay"));
            r.reason = asStr(map.get("reason"));
            r.count = (int) getLong(map.get("count"), 1);
            r.timestamp = getLong(map.get("timestamp"), System.currentTimeMillis());
            String status = asStr(map.get("status"));
            r.status = (status == null || status.isBlank()) ? ReportStatus.OPEN : ReportStatus.valueOf(status);
            r.assignee = asStr(map.get("assignee"));
            r.sourceServer = asStr(map.get("sourceServer"));
            Object chatObj = map.get("chat");
            if (chatObj instanceof List<?> list) {
                for (Object obj : list) {
                    if (obj instanceof Map<?, ?> cm) {
                        ChatMessage message = new ChatMessage(
                                getLong(cm.get("time"), System.currentTimeMillis()),
                                asStr(cm.get("player")),
                                asStr(cm.get("server")),
                                asStr(cm.get("message"))
                        );
                        r.chat.add(message);
                    }
                }
            }
            return r;
        } catch (Exception ex) {
            if (log != null) {
                log.warn("Failed to parse YAML report: {}", ex.toString());
            }
            return null;
        }
    }

    private static Object nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String asStr(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }

    private static long getLong(Object obj, long def) {
        if (obj == null) return def;
        if (obj instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(obj));
        } catch (Exception ex) {
            return def;
        }
    }
}
