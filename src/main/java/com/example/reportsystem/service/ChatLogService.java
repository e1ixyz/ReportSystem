package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Captures chat:
 *  1) Rolling buffer for ALL players (so first report gets recent lines).
 *  2) Live-append for players under "watch" (anyone with an open report).
 */
public class ChatLogService {

    // Rolling buffer defaults
    private static final int BUFFER_SECONDS = 120;        // last ~2 minutes
    private static final int MAX_LINES_PER_PLAYER = 100;  // safety cap

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private volatile PluginConfig config;
    private final Logger log;

    /** players currently being "watched" to live-append into their open reports */
    private final Set<String> watchedPlayers = ConcurrentHashMap.newKeySet();

    /** per-player ring buffer of recent chat (lowercased key -> deque of ChatMessage) */
    private final Map<String, Deque<ChatMessage>> recentByPlayer = new ConcurrentHashMap<>();

    public ChatLogService(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
        this.log = plugin.logger();

        // Let the manager pull recent lines on new-report creation
        this.mgr.setChatLogService(this);
    }

    public void setConfig(PluginConfig cfg) { this.config = cfg; }

    /** Rebuilds the watch set from open reports. Call after filing/stacking. */
    public void refreshWatchList() {
        watchedPlayers.clear();
        for (Report r : mgr.getOpenReportsDescending()) {
            if (r.reported != null && !r.reported.isBlank()) {
                watchedPlayers.add(r.reported.toLowerCase(Locale.ROOT));
            }
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        // Refresh on login so immediate chat from targets is captured going forward
        refreshWatchList();
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player p = e.getPlayer();
        String name = p.getUsername();
        String server = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("UNKNOWN");
        long now = System.currentTimeMillis();

        // Ensure ChatMessage(server) is truly a server name (not the username)
        ChatMessage msg = new ChatMessage(now, name, server, e.getMessage());

        // 1) ALWAYS record in rolling buffer
        recordToBuffer(name, msg, now);

        // 2) If the player is being watched, live-append to their open reports
        if (watchedPlayers.contains(name.toLowerCase(Locale.ROOT))) {
            for (Report r : mgr.getOpenReportsDescending()) {
                if (r.reported != null && r.reported.equalsIgnoreCase(name)) {
                    mgr.appendChat(r.id, msg);
                }
            }
        }
    }

    /* ---------------- rolling buffer helpers ---------------- */

    private void recordToBuffer(String playerName, ChatMessage msg, long now) {
        String key = key(playerName);
        Deque<ChatMessage> dq = recentByPlayer.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        dq.addLast(msg);

        // prune by size
        while (dq.size() > MAX_LINES_PER_PLAYER) {
            dq.pollFirst();
        }
        // prune by age
        long cutoff = now - BUFFER_SECONDS * 1000L;
        while (true) {
            ChatMessage head = dq.peekFirst();
            if (head == null) break;
            if (head.time >= cutoff) break;
            dq.pollFirst();
        }
    }

    private static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** Recent messages for this player within 'windowMs' (oldest→newest). */
    public List<ChatMessage> recentFor(String playerName, long windowMs) {
        if (playerName == null || playerName.isBlank()) return List.of();
        Deque<ChatMessage> dq = recentByPlayer.get(key(playerName));
        if (dq == null || dq.isEmpty()) return List.of();
        long cutoff = System.currentTimeMillis() - Math.max(1_000L, windowMs);

        List<ChatMessage> out = new ArrayList<>(dq.size());
        for (ChatMessage m : dq) {
            if (m.time >= cutoff) out.add(m);
        }
        out.sort(Comparator.comparingLong(m -> m.time));
        return out;
    }
}