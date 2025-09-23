package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChatLogService {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private PluginConfig config;

    // reportedName (lowercase) -> set of open report IDs to capture for
    private final Map<String, Set<Long>> watched = new ConcurrentHashMap<>();

    public ChatLogService(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
    }

    public void setConfig(PluginConfig config) { this.config = config; }

    /** Rebuild the watchlist from all current OPEN chat reports. */
    public void refreshWatchList() {
        watched.clear();
        for (Report r : mgr.getOpenReportsDescending()) {
            if (r.typeId != null && r.categoryId != null
                    && r.typeId.equalsIgnoreCase("player")
                    && r.categoryId.equalsIgnoreCase("chat")) {
                if (r.reported != null && !r.reported.isBlank()) {
                    watched.computeIfAbsent(r.reported.toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(r.id);
                }
            }
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        // Rebuild on login so newly-opened reports are picked up soon after joins.
        refreshWatchList();
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player sender = e.getPlayer();
        String key = sender.getUsername().toLowerCase(Locale.ROOT);

        // Fast path: use prebuilt watch set
        Set<Long> ids = watched.get(key);

        // Fallback: if not present yet (e.g., report was just created and list hasn’t refreshed),
        // lazily discover matching open reports now and cache them.
        if (ids == null || ids.isEmpty()) {
            Set<Long> found = new HashSet<>();
            for (Report r : mgr.getOpenReportsDescending()) {
                if (r.reported != null
                        && r.reported.equalsIgnoreCase(sender.getUsername())
                        && r.typeId != null && r.typeId.equalsIgnoreCase("player")
                        && r.categoryId != null && r.categoryId.equalsIgnoreCase("chat")) {
                    found.add(r.id);
                }
            }
            if (!found.isEmpty()) {
                watched.put(key, found);
                ids = found;
            }
        }

        if (ids == null || ids.isEmpty()) return;

        long now = System.currentTimeMillis();
        String backend = sender.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("unknown");

        // Velocity 3.x PlayerChatEvent#getMessage() is a String (legacy) in your codebase;
        // If you ever migrate to Component, convert to plain text here.
        ChatMessage cm = new ChatMessage(now, backend, sender.getUsername(), e.getMessage());

        for (Long id : ids) mgr.appendChat(id, cm);
    }
}
