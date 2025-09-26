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
 * Thread-safe report manager with priority scoring and persistence.
 */
public class ReportManager {

    private final ReportSystem plugin;
    private final Logger log;
    private final Path storeDir;
    private volatile PluginConfig config;

    private final Map<Long, Report> reports = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /** last "activity" timestamp used for stacking-window and recency */
    private final Map<Long, Long> lastUpdateMillis = new ConcurrentHashMap<>();
    /** closed timestamp per id (Report has no closedAt field) */
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

    public void setConfig(PluginConfig cfg) { this.config = cfg; }

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

    // Tab-complete helpers
    public List<String> typeIds() { return new ArrayList<>(config.reportTypes.keySet()); }
    public List<String> categoryIdsFor(String typeId) {
        if (typeId == null) return List.of();
        var tdef = config.reportTypes.get(typeId.toLowerCase(Locale.ROOT));
        if (tdef == null || tdef.categories == null) return List.of();
        return new ArrayList<>(tdef.categories.keySet());
    }

    public Report get(long id) { return reports.get(id); }

    /* ====================== Priority ======================= */

    private double priorityScore(Report r) {
        // If new system disabled, mimic legacy ordering (count first, then tie-breaker time)
        if (!config.prioritySorting || config.priority == null || !config.priority.enabled) {
            long t = r.timestamp;
            return (r.count * 1_000_000d) + (config.tieBreaker.equalsIgnoreCase("oldest") ? -t : t);
        }

        long now = System.currentTimeMillis();
        long last = lastUpdateMillis.getOrDefault(r.id, r.timestamp);
        long ageMs = Math.max(1L, now - r.timestamp);
        long idleMs = Math.max(0L, now - last);

        double score = 0.0;

        // Count (diminishing returns)
        if (config.priority.useCount) {
            double term = Math.log1p(Math.max(1, r.count));
            score += config.priority.wCount * term;
        }
        // Recency / velocity
        if (config.priority.useRecency) {
            double recency = Math.exp(-1.0 * idleMs / Math.max(1.0, config.priority.tauMs)); // 0..1
            double vel = recency * Math.log1p(Math.max(1, r.count));
            score += config.priority.wRecency * vel;
        }
        // Severity (per type/category)
        if (config.priority.useSeverity) {
            String key = (r.typeId == null ? "" : r.typeId.toLowerCase()) + "/" +
                         (r.categoryId == null ? "" : r.categoryId.toLowerCase());
            double sev = config.priority.severityByKey.getOrDefault(key, 1.0);
            score += config.priority.wSeverity * sev;
        }
        // Evidence (chat captured)
        if (config.priority.useEvidence) {
            double has = (r.chat != null && !r.chat.isEmpty()) ? 1.0 : 0.0;
            score += config.priority.wEvidence * has;
        }
        // Unassigned bump
        if (config.priority.useUnassigned) {
            double unassigned = (r.assignee == null || r.assignee.isBlank()) ? 1.0 : 0.0;
            score += config.priority.wUnassigned * unassigned;
        }
        // Aging (slow increase over time)
        if (config.priority.useAging) {
            double aging = Math.log1p(ageMs / 60000.0); // minutes
            score += config.priority.wAging * aging;
        }
        // SLA breach
        if (config.priority.useSlaBreach) {
            String key = (r.typeId == null ? "" : r.typeId.toLowerCase()) + "/" +
                         (r.categoryId == null ? "" : r.categoryId.toLowerCase());
            int sla = config.priority.slaMinutes.getOrDefault(key, 0);
            boolean breached = (sla > 0) && (ageMs >= sla * 60_000L);
            if (breached) score += config.priority.wSlaBreach;
        }

        return score;
    }

    /** Open reports sorted by multi-factor priority score, then time tie-breaker. */
    public List<Report> getOpenReportsDescending() {
        List<Report> list = reports.values().stream().filter(Report::isOpen).collect(Collectors.toList());
        list.sort((a, b) -> {
            int byScore = Double.compare(priorityScore(b), priorityScore(a));
            if (byScore != 0) return byScore;
            boolean newest = !"oldest".equalsIgnoreCase(config.tieBreaker);
            return newest ? Long.compare(b.timestamp, a.timestamp)
                          : Long.compare(a.timestamp, b.timestamp);
        });
        return list;
    }

    /** Closed reports newest-closed first. */
    public List<Report> getClosedReportsDescending() {
        List<Report> list = reports.values().stream().filter(r -> !r.isOpen()).collect(Collectors.toList());
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

    /** Create or stack a report. */
    public synchronized Report fileOrStack(String reporter, String reported, ReportType rt, String reason) {
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
        r.assignee = null;

        reports.put(id, r);
        lastUpdateMillis.put(id, now);
        closedAtById.remove(id);
        trySave(r);
        return r;
    }

    /** Append a chat message to a report. */
    public void appendChat(Long id, ChatMessage msg) {
        if (id == null || msg == null) return;
        Report r = reports.get(id);
        if (r == null) return;
        if (r.chat == null) r.chat = new ArrayList<>();
        r.chat.add(msg);
        trySave(r);
        lastUpdateMillis.put(id, System.currentTimeMillis());
    }

    /** Unconditional assign (kept for back-compat). */
    public void assign(long id, String staff) {
        Report r = reports.get(id);
        if (r == null) return;
        r.assignee = safeStr(staff);
        trySave(r);
    }

    /** Safer assign helper: returns true if assignment changed or idempotent; false if blocked. */
    public boolean assignIfAllowed(long id, String staff, boolean force) {
        Report r = reports.get(id);
        if (r == null) return false;
        String who = safeStr(staff);
        if (r.assignee == null || r.assignee.isBlank()) {
            r.assignee = who;
            trySave(r);
            return true;
        }
        if (r.assignee.equalsIgnoreCase(who)) {
            // already assigned to same person — idempotent OK
            return true;
        }
        if (force) {
            r.assignee = who;
            trySave(r);
            return true;
        }
        return false;
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
        trySave(r);
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

    public void save() { }

    /* ====================== Internals ======================= */

    private String safeStr(String s) { return s == null ? "" : s.trim(); }

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

    /* ====================== Persistence ======================= */

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
                        r.chat.add(new ChatMessage(t, sv, pl, ms)); // order: (time, server, player, message)
                    }
                }
            }
            return r;
        } catch (Throwable t) {
            log.warn("Failed to parse report map: {}", t.toString());
            return null;
        }
    }

    private Object nullIfBlank(String s) { return (s == null || s.isBlank()) ? null : s; }
    private String asStr(Object o) { return o == null ? null : String.valueOf(o); }
    private long getLong(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    /* ====================== Debug ======================= */

    public String debugSummary() {
        long open = reports.values().stream().filter(Report::isOpen).count();
        long closed = reports.size() - open;
        long maxId = reports.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        return "reports=" + reports.size() + " open=" + open + " closed=" + closed
                + " nextId=" + nextId.get() + " maxId=" + maxId + " now=" + Instant.now();
    }
}
