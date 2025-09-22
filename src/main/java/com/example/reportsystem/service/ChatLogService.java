package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.util.TimeUtil;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures player chat messages seen by the proxy and
 * associates them to any OPEN chat-type reports targeting that player.
 *
 * NOTE: Velocity can only see messages that actually traverse the proxy.
 * Backend messages not routed through the proxy won't be visible here.
 */
public class ChatLogService {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private PluginConfig config;

    // Cache: playerName -> open report ids that want chat logs
    private final Map<String, Set<Long>> watched = new ConcurrentHashMap<>();

    public ChatLogService(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
    }

    public void setConfig(PluginConfig config) { this.config = config; }

    /** Call when a new/stacked report is created to update watchers. */
    public void refreshWatchList() {
        watched.clear();
        for (Report r : mgr.getOpenReportsDescending()) {
            if (r.typeId.equalsIgnoreCase("player") && r.categoryId.equalsIgnoreCase("chat")) {
                watched.computeIfAbsent(r.reported.toLowerCase(), k -> new HashSet<>()).add(r.id);
            }
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        // refresh watchers on player joins (safe to do; cheap)
        refreshWatchList();
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player sender = e.getPlayer();
        String name = sender.getUsername().toLowerCase();

        Set<Long> ids = watched.get(name);
        if (ids == null || ids.isEmpty()) return;

        long now = System.currentTimeMillis();
        String backend = sender.getCurrentServer().flatMap(s -> Optional.ofNullable(s.getServerInfo().getName())).orElse("unknown");
        String msg = e.getMessage();

        ChatMessage cm = new ChatMessage(now, backend, sender.getUsername(), msg);
        for (Long id : ids) {
            mgr.appendChat(id, cm);
        }
    }
}
