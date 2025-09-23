package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.model.ReportStatus;
import com.example.reportsystem.model.ReportType;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * ReportManager
 *
 * - Thread-safe in-memory store of reports
 * - Persists each report as YAML: <dataDir>/reports/<id>.yml
 * - Stacking by (reported + type + category) within config.stackWindowSeconds
 * - Priority sorting for open reports: higher count first, then time (config.tieBreaker)
 * - Closed reports ordering tracked via internal closedAt map (Report has no closedAt field)
 * - Search by simple query
 * - Assign/Unassign/Close/Reopen
 * - Chat append support for ChatLogService
 */
public class ReportManager {

    private final ReportSystem plugin;
    private final Logger log;
    private final Path storeDir;
    private volatile PluginConfig config;

    private final Map<Long, Report> reports = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /** last "activity" timestamp we use for stacking-window checks */
    private final Map<Long, Long> lastUpdateMillis = new ConcurrentHashMap<>();
    /** closed timestamp per id (since Report doesn't have a closedAt field) */
    private final Map<Long, Long> closedAtById = new ConcurrentHashMap<>();

    private final Yaml yaml = new Yaml();

    public ReportManager(ReportSystem plugin, Path dataDir, PluginConfig config) {
        this.plugin = plugin;
        this.log = plugin.logger();
        this.storeDir = dataDir.resolve("reports");
        this.config = config;
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create reports dir: " + storeDir, e);
        }
        try {
            loadAll();
        } catch (Exception e) {
            log.warn("Failed to load reports: {}", e.toString());
        }
    }

    /* =========================
              PUBLIC API
       ========================= */

    public void setConfig(PluginConfig cfg) {
        this.config = cfg;
    }

    /** Resolve dynamic type/category from config; null if invalid. */
    public ReportType resolveType(String typeId, String categoryId) {
        if (typeId == null || categoryId == null) return null;
        var tdef = config.reportTypes.get(typeId.toLowerCase(Locale.ROOT));
        if (tdef == null) return null;
        String typeDisplay = (tdef.display == null || tdef.display.isBlank()) ? typeId : tdef.display;
        String catDisplay = tdef.categories.get(categoryId.toLowerCase(Locale.ROOT));
        if (catDisplay == null) return null;
        return new ReportType(typeId, typeDisplay, categoryId, catDisplay);
    }

    /** Tab-complete helpers. */
    public List<String> typeIds() {
        return new ArrayList<>(config.reportTypes.keySet());
    }
    public List<String> categoryIdsFor(String typeId) {
        if (typeId == null) return List.of();
        var tdef = config.reportTypes.get(typeId.toLowerCase(Locale.ROOT));
        if (tdef == null || tdef.categories == null) return List.of();
        return new ArrayList<>(tdef.categories.keySet());
    }

    /** Fetch by id. */
    public Report get(long id) { return reports.get(id); }

    /** Open reports sorted by priority (count desc) then tie-breaker time. */
    public List<Report> getOpenReportsDescending() {
        List<Report> list = reports.values().stream()
                .filter(Report::isOpen)
                .collect(Collectors.toList());
        list.sort((a, b) -> {
            int byCount = Integer.compare(b.count, a.count);
            if (byCount != 0) return byCount;
            boolean newest = !"oldest".equalsIgnoreCase(config.tieBreaker);
            return newest ? Long.compare(b.timestamp, a.timestamp)
                          : Long.compare(a.timestamp, b.timestamp);
        });
        return list;
    }

    /** Closed reports newest-closed first (tracked via closedAtById; fallback to timestamp). */
    public List<Report> getClosedReportsDescending() {
        List<Report> list = reports.values().stream()
                .filter(r -> !r.isOpen())
                .collect(Collectors.toList());
        list.sort((a, b) -> {
            long ca = closedAtById.getOrDefault(a.id, a.timestamp);
            long cb = closedAtById.getOrDefault(b.id, b.timestamp);
            return Long.compare(cb, ca);
        });
        return list;
    }

    /** Search by query across basic fields; scope=open|closed|all. */
    public List<Report> search(String query, String scope) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        boolean wantOpen, wantClosed;
        switch (scope == null ? "open" : scope.toLowerCase(Locale.ROOT)) {
            case "all" -> { wantOpen = true; wantClosed = true; }
            case "closed" -> { wantOpen = false; wantClosed = true; }
            default -> { wantOpen = true; wantClosed = false; }
        }
        return reports.values().stream()
                .filter(r -> (r.isOpen() && wantOpen) || (!r.isOpen() && wantClosed))
                .filter(r -> matches(r, q))
                .sorted(Comparator.comparingLong((Report r) -> r.timestamp).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Create or stack a report.
     * NOTE: augmented with serverName (backend) capture for expanded view/jump button.
     */
    public synchronized Report fileOrStack(String reporter, String reported, ReportType rt, String reason, String serverName) {
        long now = System.currentTimeMillis();

        Report target = findStackTarget(reported, rt);
        if (target != null) {
            long last = lastUpdateMillis.getOrDefault(target.id, target.timestamp);
            int windowSec = Math.max(0, config.stackWindowSeconds);
            if (windowSec == 0 || (now - last) <= windowSec * 1000L) {
                target.count = Math.max(1, target.count) + 1;
                if (reason != null && !reason.isBlank()) {
                    target.reason = (target.reason == null || target.reason.isBlank())
                            ? reason
                            : target.reason + " | " + reason;
                }
                // backfill server if unknown
                if ((target.server == null || target.server.isBlank()) && serverName != null && !serverName.isBlank()) {
                    target.server = serverName;
                }
                lastUpdateMillis.put(target.id, now);
                trySave(target);
                return target;
            }
        }

        long id = nextId.getAndIncrement();
        Report r = new Report();
        r.id = id;
        r.reporter = safeStr(reporter);
        r.reported = safeStr(reported);
        r.typeId = rt.typeId;
        r.typeDisplay = rt.typeDisplay;
        r.categoryId = rt.categoryId;
        r.categoryDisplay = rt.categoryDisplay;
        r.reason = safeStr(reason);
        r.count = 1;
        r.timestamp = now;
        r.status = ReportStatus.OPEN;
        r.server = safeStr(serverName);
        r.assignee = null;

        reports.put(id, r);
        lastUpdateMillis.put(id, now);
        closedAtById.remove(id);
        trySave(r);
        return r;
    }

    /** Append a chat message to a report (used by ChatLogService). */
    public void appendChat(Long id, ChatMessage msg) {
        if (id == null || msg == null) return;
        Report r = reports.get(id);
        if (r == null) return;
        if (r.chat == null) r.chat = new ArrayList<>();
        r.chat.add(msg);
        trySave(r);
        lastUpdateMillis.put(id, System.currentTimeMillis());
    }

    /** Assign/Unassign. */
    public void assign(long id, String staff) {
        Report r = reports.get(id);
        if (r == null) return;
        r.assignee = safeStr(staff);
        trySave(r);
    }
    public void unassign(long id) {
        Report r = reports.get(id);
        if (r == null) return;
        r.assignee = null;
        trySave(r);
    }
    public boolean isAssigned(long id) {
        Report r = reports.get(id);
        return r != null && r.assignee != null && !r.assignee.isBlank();
    }

    /** Close/Reopen. */
    public void close(long id) {
        Report r = reports.get(id);
        if (r == null) return;
        r.status = ReportStatus.CLOSED;
        long now = System.currentTimeMillis();
        closedAtById.put(id, now);
        trySave(r); // we also persist closedAt
    }
    public boolean reopen(long id) {
        Report r = reports.get(id);
        if (r == null) return false;
        if (r.isOpen()) return true;
        r.status = ReportStatus.OPEN;
        closedAtById.remove(id);
        lastUpdateMillis.put(id, System.currentTimeMillis());
        trySave(r);
        return true;
    }

    /** No-op (per-report saves are immediate). */
    public void save() { }

    /* =========================
               INTERNALS
       ========================= */

    private String safeStr(String s) {
        return s == null ? "" : s.trim();
    }

    private boolean matches(Report r, String q) {
        if (q.isEmpty()) return true;
        if (String.valueOf(r.id).equals(q)) return true;
        if (contains(r.reporter, q)) return true;
        if (contains(r.reported, q)) return true;
        if (contains(r.reason, q)) return true;
        if (contains(r.typeDisplay, q)) return true;
        if (contains(r.categoryDisplay, q)) return true;
        return false;
    }
    private boolean contains(String hay, String needle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle);
    }

    private Report findStackTarget(String reported, ReportType rt) {
        String target = reported == null ? "" : reported;
        List<Report> candidates = reports.values().stream()
                .filter(Report::isOpen)
                .filter(r -> equalsCI(r.reported, target))
                .filter(r -> r.typeId.equalsIgnoreCase(rt.typeId) && r.categoryId.equalsIgnoreCase(rt.categoryId))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> {
            int byCount = Integer.compare(b.count, a.count);
            if (byCount != 0) return byCount;
            return Long.compare(b.timestamp, a.timestamp);
        });
        return candidates.get(0);
    }
    private boolean equalsCI(String a, String b) {
        if (a == null) return b == null || b.isEmpty();
        if (b == null) return a.isEmpty();
        return a.equalsIgnoreCase(b);
    }

    /* =========================
              PERSISTENCE
       ========================= */

    private void loadAll() throws IOException {
        if (!Files.isDirectory(storeDir)) return;

        long maxId = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(storeDir, "*.yml")) {
            for (Path p : ds) {
                try (Reader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = yaml.load(reader);
                    if (m == null) continue;
                    Report r = fromMap(m);
                    if (r != null) {
                        reports.put(r.id, r);
                        maxId = Math.max(maxId, r.id);
                        // restore closedAt (stored in file) and lastUpdate
                        long ca = getLong(m.get("closedAt"), 0L);
                        if (ca > 0) closedAtById.put(r.id, ca);
                        lastUpdateMillis.put(r.id, Math.max(r.timestamp, ca));
                    }
                } catch (Exception ex) {
                    log.warn("Failed to load {}: {}", p.getFileName(), ex.toString());
                }
            }
        }
        nextId.set(Math.max(nextId.get(), maxId + 1));
        log.info("Loaded {} reports (nextId={})", reports.size(), nextId.get());
    }

    private void trySave(Report r) {
        try { saveOne(r); }
        catch (Exception e) { log.warn("Failed to save report #{}: {}", r.id, e.toString()); }
    }

    private void saveOne(Report r) throws IOException {
        Path out = storeDir.resolve(r.id + ".yml");
        Map<String, Object> m = toMap(r);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            yaml.dump(m, w);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Map<String, Object> toMap(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("reporter", nullIfBlank(r.reporter));
        m.put("reported", nullIfBlank(r.reported));
        m.put("typeId", r.typeId);
        m.put("typeDisplay", r.typeDisplay);
        m.put("categoryId", r.categoryId);
        m.put("categoryDisplay", r.categoryDisplay);
        m.put("reason", nullIfBlank(r.reason));
        m.put("count", r.count);
        m.put("timestamp", r.timestamp);
        m.put("status", r.status == null ? ReportStatus.OPEN.name() : r.status.name());
        m.put("assignee", nullIfBlank(r.assignee));
        m.put("server", nullIfBlank(r.server)); // persist report's server
        long closedAt = closedAtById.getOrDefault(r.id, 0L);
        m.put("closedAt", closedAt);

        if (r.chat != null && !r.chat.isEmpty()) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            for (ChatMessage c : r.chat) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("time", c.time);
                cm.put("player", c.player);
                cm.put("server", c.server);
                cm.put("message", c.message);
                msgs.add(cm);
            }
            m.put("chat", msgs);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Report fromMap(Map<String, Object> m) {
        try {
            Report r = new Report();
            r.id = getLong(m.get("id"), 0L);
            if (r.id <= 0) return null;

            r.reporter = asStr(m.get("reporter"));
            r.reported = asStr(m.get("reported"));
            r.typeId = asStr(m.get("typeId"));
            r.typeDisplay = asStr(m.get("typeDisplay"));
            r.categoryId = asStr(m.get("categoryId"));
            r.categoryDisplay = asStr(m.get("categoryDisplay"));
            r.reason = asStr(m.get("reason"));
            r.count = (int) getLong(m.get("count"), 1);
            r.timestamp = getLong(m.get("timestamp"), System.currentTimeMillis());
            r.server = asStr(m.get("server"));

            String st = asStr(m.get("status"));
            r.status = (st == null || st.isBlank()) ? ReportStatus.OPEN : ReportStatus.valueOf(st);
            r.assignee = asStr(m.get("assignee"));

            Object chatObj = m.get("chat");
            if (chatObj instanceof List<?> list) {
                if (r.chat == null) r.chat = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> mm) {
                        long t = getLong(mm.get("time"), System.currentTimeMillis());
                        String pl = asStr(mm.get("player"));
                        String sv = asStr(mm.get("server"));
                        String ms = asStr(mm.get("message"));
                        // ChatMessage ctor: (time, server, player, message)
                        r.chat.add(new ChatMessage(t, sv, pl, ms));
                    }
                }
            }
            return r;
        } catch (Throwable t) {
            log.warn("Failed to parse report map: {}", t.toString());
            return null;
        }
    }

    private Object nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
    private String asStr(Object o) { return o == null ? null : String.valueOf(o); }
    private long getLong(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    /* =========================
            DEBUG / UTILITIES
       ========================= */

    public String debugSummary() {
        long open = reports.values().stream().filter(Report::isOpen).count();
        long closed = reports.size() - open;
        long maxId = reports.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        return "reports=" + reports.size() + " open=" + open + " closed=" + closed
                + " nextId=" + nextId.get() + " maxId=" + maxId + " now=" + Instant.now();
    }
}
