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

    private final Map<String, Set<Long>> watched = new ConcurrentHashMap<>();

    public ChatLogService(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
    }

    public void setConfig(PluginConfig config) { this.config = config; }

    public void refreshWatchList() {
        watched.clear();
        for (Report r : mgr.getOpenReportsDescending()) {
            if (r.typeId.equalsIgnoreCase("player") && r.categoryId.equalsIgnoreCase("chat")) {
                watched.computeIfAbsent(r.reported.toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(r.id);
            }
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        refreshWatchList();
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player sender = e.getPlayer();
        String key = sender.getUsername().toLowerCase(Locale.ROOT);
        Set<Long> ids = watched.get(key);
        if (ids == null || ids.isEmpty()) return;

        long now = System.currentTimeMillis();
        String backend = sender.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("unknown");
        ChatMessage cm = new ChatMessage(now, backend, sender.getUsername(), e.getMessage());

        for (Long id : ids) mgr.appendChat(id, cm);
    }
}
