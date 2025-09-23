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

/**
 * Captures chat lines for open "player/chat" reports.
 * Adds a per-player ring buffer so we can backfill recent messages
 * when a chat report is created AFTER the player already spoke.
 */
public class ChatLogService {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private PluginConfig config;

    /** reportedName (lowercase) -> open report IDs to capture for */
    private final Map<String, Set<Long>> watched = new ConcurrentHashMap<>();

    /** Per-player recent chat buffer for backfill (lowercase name) */
    private final Map<String, Deque<ChatMessage>> recentByPlayer = new ConcurrentHashMap<>();

    // Backfill limits (kept local to avoid changing config schema)
    private static final int BUFFER_PER_PLAYER_MAX = 100;       // keep last 100 lines per player
    private static final long BACKFILL_WINDOW_MS   = 2 * 60_000; // last 2 minutes
    private static final int BACKFILL_MAX_LINES    = 50;        // safety cap

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

    /**
     * Backfill recent chat lines for a freshly created/stacked chat report.
     * Call this right after filing a "player/chat" report.
     */
    public void backfillRecentFor(Report r) {
        if (r == null) return;
        if (r.typeId == null || r.categoryId == null) return;
        if (!r.typeId.equalsIgnoreCase("player") || !r.categoryId.equalsIgnoreCase("chat")) return;
        if (r.reported == null || r.reported.isBlank()) return;

        String key = r.reported.toLowerCase(Locale.ROOT);
        Deque<ChatMessage> buf = recentByPlayer.get(key);
        if (buf == null || buf.isEmpty()) return;

        long since = Math.max(0, r.timestamp - BACKFILL_WINDOW_MS);
        // Iterate from newest to oldest, collect qualifying, then reverse to append chronologically
        List<ChatMessage> toAdd = new ArrayList<>();
        synchronized (buf) {
            int taken = 0;
            for (Iterator<ChatMessage> it = buf.descendingIterator(); it.hasNext(); ) {
                ChatMessage m = it.next();
                if (m.time < since) break; // older than window
                toAdd.add(m);
                if (++taken >= BACKFILL_MAX_LINES) break;
            }
        }
        if (toAdd.isEmpty()) return;
        Collections.reverse(toAdd);
        for (ChatMessage m : toAdd) {
            mgr.appendChat(r.id, m);
        }

        // Ensure we start watching live lines as well
        watched.computeIfAbsent(key, k -> new HashSet<>()).add(r.id);
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        // Rebuild on login so newly-opened reports are picked up soon after joins.
        refreshWatchList();
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player sender = e.getPlayer();
        String ign = sender.getUsername();
        String key = ign.toLowerCase(Locale.ROOT);

        long now = System.currentTimeMillis();
        String backend = sender.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("unknown");
        ChatMessage cm = new ChatMessage(now, backend, ign, e.getMessage());

        // 1) Always push to the per-player ring buffer for potential backfill
        Deque<ChatMessage> buf = recentByPlayer.computeIfAbsent(key, k -> new ArrayDeque<>(BUFFER_PER_PLAYER_MAX));
        synchronized (buf) {
            if (buf.size() >= BUFFER_PER_PLAYER_MAX) buf.removeFirst();
            buf.addLast(cm);
        }

        // 2) Live capture to any open chat reports for this player
        Set<Long> ids = watched.get(key);
        if (ids == null || ids.isEmpty()) {
            // Lazy discover (in case watch list hasn't been refreshed yet)
            Set<Long> found = new HashSet<>();
            for (Report r : mgr.getOpenReportsDescending()) {
                if (r.reported != null
                        && r.reported.equalsIgnoreCase(ign)
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
        for (Long id : ids) mgr.appendChat(id, cm);
    }
}
