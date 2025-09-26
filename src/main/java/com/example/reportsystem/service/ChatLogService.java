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
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Captures chat for players currently involved in open "player/chat" reports,
 * or any open player reports for the tracked target. refreshWatchList() builds the watch set.
 */
public class ChatLogService {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private volatile PluginConfig config;
    private final Logger log;

    private final Set<String> watchedPlayers = new HashSet<>();

    public ChatLogService(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
        this.log = plugin.logger();
    }

    public void setConfig(PluginConfig cfg) { this.config = cfg; }

    /** Rebuilds the watch set from open reports. Call after filing/stacking. */
    public void refreshWatchList() {
        watchedPlayers.clear();
        for (Report r : mgr.getOpenReportsDescending()) {
            if (r.reported != null && !r.reported.isBlank()) {
                watchedPlayers.add(r.reported.toLowerCase());
            }
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        // Refresh on login so immediate chat from targets is captured
        refreshWatchList();
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player p = e.getPlayer();
        String name = p.getUsername();
        if (!watchedPlayers.contains(name.toLowerCase())) return;

        String server = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("UNKNOWN");
        long now = System.currentTimeMillis();
        ChatMessage msg = new ChatMessage(now, name, server, e.getMessage());

        // Append to all open reports mentioning this player
        for (Report r : mgr.getOpenReportsDescending()) {
            if (r.reported != null && r.reported.equalsIgnoreCase(name)) {
                mgr.appendChat(r.id, msg);
            }
        }
    }
}
