package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.model.ReportStatus;
import com.example.reportsystem.model.ReportType;
import com.example.reportsystem.util.TimeUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ReportManager {

    private final ReportSystem plugin;
    private PluginConfig config;
    private final Path dataDir;
    private final Path storePath;

    private final Map<Long, Report> reports = new ConcurrentHashMap<>();
    private long nextId = 1L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public ReportManager(ReportSystem plugin, Path dataDir, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.dataDir = dataDir;
        this.storePath = dataDir.resolve("reports.json");
        load();
    }

    public void setConfig(PluginConfig config) { this.config = config; }

    public synchronized Report fileOrStack(String reporter, String reported, ReportType type, String reason) {
        long now = System.currentTimeMillis();
        int windowMs = Math.max(0, config.stackWindowSeconds) * 1000;
        Report stack = getOpenReportsDescending().stream()
                .filter(r -> r.reported.equalsIgnoreCase(reported))
                .filter(r -> r.typeId.equalsIgnoreCase(type.typeId) && r.categoryId.equalsIgnoreCase(type.categoryId))
                .filter(r -> (now - r.timestamp) <= windowMs)
                .findFirst()
                .orElse(null);
        if (stack != null) {
            stack.count += 1;
            stack.reason = stack.reason + " | " + reason;
            save();
            return stack;
        }
        long id = nextId++;
        Report r = new Report(id, reporter, reported, type, reason, now);
        reports.put(id, r);
        save();
        return r;
    }

    public synchronized void close(long id) {
        Report r = reports.get(id);
        if (r != null) {
            r.status = ReportStatus.CLOSED;
            save();
        }
    }

    public synchronized void appendChat(long reportId, ChatMessage msg) {
        Report r = reports.get(reportId);
        if (r != null) {
            r.chat.add(msg);
        }
    }

    public List<Report> getOpenReportsDescending() {
        return reports.values().stream()
                .filter(Report::isOpen)
                .sorted(Comparator.comparingLong((Report r) -> r.timestamp).reversed())
                .collect(Collectors.toList());
    }

    public Report get(long id) { return reports.get(id); }

    public synchronized void save() {
        try {
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
            Map<String,Object> root = new HashMap<>();
            root.put("nextId", nextId);
            root.put("reports", reports.values());
            try (Writer w = Files.newBufferedWriter(storePath)) {
                GSON.toJson(root, w);
            }
        } catch (IOException e) {
            plugin.logger().error("Failed to save reports: {}", e.getMessage(), e);
        }
    }

    private synchronized void load() {
        if (!Files.exists(storePath)) return;
        try (Reader r = Files.newBufferedReader(storePath)) {
            @SuppressWarnings("unchecked")
            Map<String,Object> root = GSON.fromJson(r, Map.class);
            Number nNext = (Number) root.getOrDefault("nextId", 1.0);
            this.nextId = nNext.longValue();
            List<Map<String,Object>> list = (List<Map<String,Object>>) root.getOrDefault("reports", List.of());
            for (Map<String,Object> m : list) {
                Report rp = GSON.fromJson(GSON.toJson(m), Report.class);
                reports.put(rp.id, rp);
            }
        } catch (Exception e) {
            plugin.logger().error("Failed to load reports.json: {}", e.getMessage(), e);
        }
    }

    public ReportType resolveType(String typeId, String categoryId) {
        if (typeId == null || categoryId == null) return null;
        var t = config.reportTypes.get(typeId.toLowerCase());
        if (t == null) return null;
        String dispType = t.display != null ? t.display : typeId;
        String dispCat = t.categories.get(categoryId.toLowerCase());
        if (dispCat == null) return null;
        return new ReportType(typeId, dispType, categoryId, dispCat);
    }

    public List<String> typeIds() {
        return new ArrayList<>(config.reportTypes.keySet());
    }

    public List<String> categoryIdsFor(String typeId) {
        var t = config.reportTypes.get(typeId.toLowerCase());
        if (t == null) return List.of();
        return new ArrayList<>(t.categories.keySet());
    }
}
