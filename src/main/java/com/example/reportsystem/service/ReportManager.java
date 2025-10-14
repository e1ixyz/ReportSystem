package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.model.ReportStatus;
import com.example.reportsystem.model.ReportType;
import com.example.reportsystem.storage.FileReportStorage;
import com.example.reportsystem.storage.MysqlReportStorage;
import com.example.reportsystem.storage.ReportStorage;
import com.example.reportsystem.storage.StoredReportPayload;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * ReportManager
 *
 * - Thread-safe in-memory store of reports
 * - Persists each report as YAML via pluggable backends (filesystem or MySQL)
 * - Stacking by (reported + type + category) within config.stackWindowSeconds
 * - Priority sorting for open reports: higher count first, then time (config.tieBreaker)
 * - Closed reports ordering tracked via internal closedAt map (Report has no closedAt field)
 * - Search by simple query
 * - Assign/Unassign/Close/Reopen
 * - Chat append support + initial chat capture from ChatLogService buffer
 */
public class ReportManager {

    private static final long INITIAL_CHAT_WINDOW_MS = 90_000L; // pull last 90s on creation

    private final ReportSystem plugin;
    private final Logger log;
    private final ReportStorage storage;
    private volatile PluginConfig config;

    private volatile ChatLogService chat; // optional; injected by ChatLogService constructor

    private final Map<Long, Report> reports = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<String, Set<Long>> openReportsByReported = new ConcurrentHashMap<>();

    /** last "activity" timestamp we use for stacking-window checks */
    private final Map<Long, Long> lastUpdateMillis = new ConcurrentHashMap<>();
    /** closed timestamp per id (since Report doesn't have a closedAt field) */
    private final Map<Long, Long> closedAtById = new ConcurrentHashMap<>();

    private final Yaml yaml = new Yaml();

    public ReportManager(ReportSystem plugin, Path dataDir, PluginConfig config) {
        this.plugin = plugin;
        this.log = plugin.logger();
        this.config = config;
        this.storage = createStorage(dataDir, config);
        try {
            storage.init();
            loadAll();
        } catch (Exception e) {
            log.warn("Failed to initialise {} storage: {}", storage.backendKey(), e.toString());
        }
    }

    /* =========================
              PUBLIC API
       ========================= */

    public void setConfig(PluginConfig cfg) {
        this.config = cfg;
        String requested = normalizeStorageMode(cfg);
        if (!storage.backendKey().equalsIgnoreCase(requested)) {
            log.warn("Storage backend changes at runtime are not supported (current={}, requested={}). Keeping {} backend.",
                    storage.backendKey(), requested, storage.backendKey());
        }
    }

    /** Called by ChatLogService so we can pull recent lines on new report creation. */
    public void setChatLogService(ChatLogService chat) {
        this.chat = chat;
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

    /** Open reports sorted by priority (configurable multi-factor scoring). */
    public List<Report> getOpenReportsDescending() {
        List<Report> list = reports.values().stream()
                .filter(Report::isOpen)
                .collect(Collectors.toCollection(ArrayList::new));

        var priority = config.priority;
        if (priority != null && priority.enabled) {
            long now = System.currentTimeMillis();
            list.sort((a, b) -> {
                double sb = computePriorityScore(b, now, priority);
                double sa = computePriorityScore(a, now, priority);
                int cmp = Double.compare(sb, sa);
                if (cmp != 0) return cmp;
                return Long.compare(b.timestamp, a.timestamp);
            });
            return list;
        }

        // fallback: simple count and timestamp ordering
        list.sort((a, b) -> {
            int byCount = Integer.compare(b.count, a.count);
            if (byCount != 0) return byCount;
            boolean newest = !"oldest".equalsIgnoreCase(config.tieBreaker);
            return newest ? Long.compare(b.timestamp, a.timestamp)
                          : Long.compare(a.timestamp, b.timestamp);
        });
        return list;
    }

    /** Lightweight look-up for ChatLogService: open reports where reported equals name. */
    public List<Report> getOpenReportsFor(String reportedName) {
        if (reportedName == null || reportedName.isBlank()) return List.of();
        String needle = reportedName.toLowerCase(Locale.ROOT);
        Set<Long> ids = openReportsByReported.get(needle);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Report> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null) continue;
            Report r = reports.get(id);
            if (r == null || !r.isOpen()) continue;
            if (r.reported == null) continue;
            if (r.reported.toLowerCase(Locale.ROOT).equals(needle)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Closed reports newest-closed first (tracked via closedAtById; fallback to timestamp). */
    public List<Report> getClosedReportsDescending() {
        List<Report> list = reports.values().stream()
                .filter(r -> !r.isOpen())
                .collect(Collectors.toCollection(ArrayList::new));
        list.sort((a, b) -> {
            long ca = closedAtById.getOrDefault(a.id, a.timestamp);
            long cb = closedAtById.getOrDefault(b.id, b.timestamp);
            return Long.compare(cb, ca);
        });
        return list;
    }

    public int countClosedReports() {
        return (int) reports.values().stream().filter(r -> !r.isOpen()).count();
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

        // Attach initial chat from rolling buffer (for the TARGET)
        if (chat != null && r.reported != null && !r.reported.isBlank()) {
            List<ChatMessage> recent = chat.recentFor(r.reported, INITIAL_CHAT_WINDOW_MS);
            if (recent != null && !recent.isEmpty()) {
                if (r.chat == null) r.chat = new ArrayList<>();
                r.chat.addAll(recent);
            }
        }

        reports.put(id, r);
        lastUpdateMillis.put(id, now);
        closedAtById.remove(id);
        indexOpenReport(r);
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

    /** Optional: persist the source server the report was filed from. */
    public void updateSourceServer(long id, String server) {
        Report r = reports.get(id);
        if (r == null) return;
        r.sourceServer = (server == null || server.isBlank()) ? null : server;
        trySave(r);
    }

    /** Close/Reopen. */
    public void close(long id) {
        Report r = reports.get(id);
        if (r == null) return;
        if (r.isOpen()) {
            removeIndexedReport(r);
        }
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
        indexOpenReport(r);
        trySave(r);
        return true;
    }

    /** No-op (per-report saves are immediate). */
    public void save() { }

    /* =========================
               INTERNALS
       ========================= */

    private void indexOpenReport(Report r) {
        if (r == null || !r.isOpen()) return;
        String key = keyForReported(r.reported);
        if (key == null) return;
        openReportsByReported
                .computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                .add(r.id);
    }

    private void removeIndexedReport(Report r) {
        String key = keyForReported(r.reported);
        if (key == null) return;
        Set<Long> ids = openReportsByReported.get(key);
        if (ids == null) return;
        ids.remove(r.id);
        if (ids.isEmpty()) {
            openReportsByReported.remove(key, ids);
        }
    }

    private String keyForReported(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

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
        String key = keyForReported(target);
        List<Report> candidates;
        if (key != null) {
            Set<Long> ids = openReportsByReported.get(key);
            if (ids == null || ids.isEmpty()) {
                return null;
            }
            candidates = new ArrayList<>(ids.size());
            for (Long id : ids) {
                if (id == null) continue;
                Report candidate = reports.get(id);
                if (candidate == null || !candidate.isOpen()) continue;
                if (!candidate.typeId.equalsIgnoreCase(rt.typeId)) continue;
                if (!candidate.categoryId.equalsIgnoreCase(rt.categoryId)) continue;
                if (!equalsCI(candidate.reported, target)) continue;
                candidates.add(candidate);
            }
        } else {
            candidates = reports.values().stream()
                    .filter(Report::isOpen)
                    .filter(r -> equalsCI(r.reported, target))
                    .filter(r -> r.typeId.equalsIgnoreCase(rt.typeId) && r.categoryId.equalsIgnoreCase(rt.categoryId))
                    .collect(Collectors.toList());
        }
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

    private void loadAll() throws Exception {
        long maxId = 0;
        openReportsByReported.clear();
        List<StoredReportPayload> payloads = storage.loadAll();
        for (StoredReportPayload payload : payloads) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = yaml.load(payload.yaml());
                if (m == null) continue;
                Report r = fromMap(m);
                if (r != null) {
                    reports.put(r.id, r);
                    maxId = Math.max(maxId, r.id);
                    long ca = getLong(m.get("closedAt"), 0L);
                    if (ca > 0) closedAtById.put(r.id, ca);
                    lastUpdateMillis.put(r.id, Math.max(r.timestamp, ca));
                    if (r.isOpen()) {
                        indexOpenReport(r);
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to decode report #{}: {}", payload.id(), ex.toString());
            }
        }
        nextId.set(Math.max(nextId.get(), maxId + 1));
        log.info("Loaded {} reports (nextId={}) via {} storage", reports.size(), nextId.get(), storage.backendKey());
    }

    private void trySave(Report r) {
        try { saveOne(r); }
        catch (Exception e) { log.warn("Failed to save report #{}: {}", r.id, e.toString()); }
    }

    private void saveOne(Report r) throws Exception {
        Map<String, Object> m = toMap(r);
        String yamlPayload = yaml.dump(m);
        storage.save(r.id, yamlPayload);
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
        m.put("sourceServer", nullIfBlank(r.sourceServer));
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
            r.sourceServer = asStr(m.get("sourceServer"));

            Object chatObj = m.get("chat");
            if (chatObj instanceof List<?> list) {
                if (r.chat == null) r.chat = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> mm) {
                        long t = getLong(mm.get("time"), System.currentTimeMillis());
                        String pl = asStr(mm.get("player"));
                        String sv = asStr(mm.get("server"));
                        String ms = asStr(mm.get("message"));
                        r.chat.add(new ChatMessage(t, pl, sv, ms));
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

    private ReportStorage createStorage(Path dataDir, PluginConfig cfg) {
        String mode = normalizeStorageMode(cfg);
        if ("mysql".equals(mode)) {
            return new MysqlReportStorage(cfg.storage.mysql, log);
        }
        Path dir = dataDir.resolve("reports");
        return new FileReportStorage(dir, log);
    }

    private String normalizeStorageMode(PluginConfig cfg) {
        if (cfg == null || cfg.storage == null || cfg.storage.mode == null) {
            return "filesystem";
        }
        return cfg.storage.mode.trim().toLowerCase(Locale.ROOT);
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

    private double computePriorityScore(Report r, long now, PluginConfig.PriorityConfig priority) {
        return computePriorityBreakdown(r, now, priority, false).total;
    }

    public PriorityBreakdown debugPriority(Report r) {
        if (r == null) return PriorityBreakdown.disabled(config.tieBreaker);
        return computePriorityBreakdown(r, System.currentTimeMillis(), config.priority, true);
    }

    private PriorityBreakdown computePriorityBreakdown(Report r, long now, PluginConfig.PriorityConfig priority, boolean detailed) {
        if (priority == null || !priority.enabled) {
            return PriorityBreakdown.disabled(config.tieBreaker);
        }

        List<PriorityBreakdown.Component> details = detailed ? new ArrayList<>() : null;
        double total = 0d;

        if (priority.useCount) {
            double value = Math.max(1, r.count);
            double contribution = priority.weightCount * value;
            total += contribution;
            if (details != null) {
                String reason = (r.count <= 1)
                        ? "Single report"
                        : r.count + " reports stacked";
                details.add(new PriorityBreakdown.Component("Count", priority.weightCount, value, contribution, reason));
            }
        }

        if (priority.useRecency) {
            long last = lastUpdateMillis.getOrDefault(r.id, r.timestamp);
            long age = Math.max(0, now - last);
            double tau = priority.tauMs <= 0 ? 1d : priority.tauMs;
            double recency = Math.exp(-age / tau);
            double contribution = priority.weightRecency * recency;
            total += contribution;
            if (details != null) {
                String reason = "Last update " + formatDuration(age) + " ago, decay=" + formatDouble(recency);
                details.add(new PriorityBreakdown.Component("Recency", priority.weightRecency, recency, contribution, reason));
            }
        }

        if (priority.useSeverity) {
            String key = (r.typeId == null ? "" : r.typeId.toLowerCase(Locale.ROOT)) + "/"
                    + (r.categoryId == null ? "" : r.categoryId.toLowerCase(Locale.ROOT));
            double sev = priority.severityByKey.getOrDefault(key, 1d);
            double contribution = priority.weightSeverity * sev;
            total += contribution;
            if (details != null) {
                String reason = "Configured weight for " + (key.isBlank() ? "default" : key) + " = " + formatDouble(sev);
                details.add(new PriorityBreakdown.Component("Severity", priority.weightSeverity, sev, contribution, reason));
            }
        }

        if (priority.useEvidence) {
            int lines = (r.chat == null) ? 0 : r.chat.size();
            boolean hasEvidence = lines > 0;
            double value = hasEvidence ? Math.min(1d, lines / 10.0) : 0d;
            double contribution = priority.weightEvidence * value;
            total += contribution;
            if (details != null) {
                String reason = hasEvidence ? lines + " chat lines captured" : "No chat evidence";
                details.add(new PriorityBreakdown.Component("Evidence", priority.weightEvidence, value, contribution, reason));
            }
        }

        if (priority.useUnassigned) {
            boolean unassigned = r.assignee == null || r.assignee.isBlank();
            double value = unassigned ? 1d : 0d;
            double contribution = priority.weightUnassigned * value;
            total += contribution;
            if (details != null) {
                String reason = unassigned ? "Unassigned" : "Assigned to " + r.assignee;
                details.add(new PriorityBreakdown.Component("Unassigned", priority.weightUnassigned, value, contribution, reason));
            }
        }

        if (priority.useAging) {
            long ageMs = Math.max(0, now - r.timestamp);
            double aging = Math.log1p(ageMs / 60_000d);
            double contribution = priority.weightAging * aging;
            total += contribution;
            if (details != null) {
                String reason = "Report age " + formatDuration(ageMs) + " -> log factor=" + formatDouble(aging);
                details.add(new PriorityBreakdown.Component("Aging", priority.weightAging, aging, contribution, reason));
            }
        }

        if (priority.useSlaBreach) {
            String key = (r.typeId == null ? "" : r.typeId.toLowerCase(Locale.ROOT)) + "/"
                    + (r.categoryId == null ? "" : r.categoryId.toLowerCase(Locale.ROOT));
            Integer sla = priority.slaMinutes.get(key);
            double contribution = 0d;
            double value = 0d;
            String reason;
            if (sla == null || sla <= 0) {
                reason = "No SLA configured for " + (key.isBlank() ? "default" : key);
            } else {
                double ageMinutes = Math.max(0d, (now - r.timestamp) / 60_000d);
                value = ageMinutes <= sla ? 0d : Math.min(1d, (ageMinutes - sla) / sla);
                contribution = priority.weightSlaBreach * value;
                reason = "Age " + formatDouble(ageMinutes) + "m vs SLA " + sla + "m";
            }
            total += contribution;
            if (details != null) {
                details.add(new PriorityBreakdown.Component("SLA Breach", priority.weightSlaBreach, value, contribution, reason));
            }
        }

        List<PriorityBreakdown.Component> out = details == null ? List.of() : List.copyOf(details);
        return new PriorityBreakdown(true, total, out, config.tieBreaker);
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private static String formatDouble(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    public static final class PriorityBreakdown {
        public final boolean enabled;
        public final double total;
        public final List<Component> components;
        public final String tieBreaker;

        private PriorityBreakdown(boolean enabled, double total, List<Component> components, String tieBreaker) {
            this.enabled = enabled;
            this.total = total;
            this.components = components;
            this.tieBreaker = tieBreaker == null || tieBreaker.isBlank() ? "newest" : tieBreaker;
        }

        public static PriorityBreakdown disabled(String tieBreaker) {
            return new PriorityBreakdown(false, 0d, List.of(), tieBreaker);
        }

        public static final class Component {
            public final String name;
            public final double weight;
            public final double value;
            public final double contribution;
            public final String reason;

            public Component(String name, double weight, double value, double contribution, String reason) {
                this.name = name;
                this.weight = weight;
                this.value = value;
                this.contribution = contribution;
                this.reason = reason;
            }
        }
    }
}
