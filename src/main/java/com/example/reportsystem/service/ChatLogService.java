package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.util.Text;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;

import java.time.Duration;
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

    /** players currently being "watched" to live-append into their open reports */
    private final Set<String> watchedPlayers = ConcurrentHashMap.newKeySet();

    /** per-player ring buffer of recent chat (lowercased key -> deque of ChatMessage) */
    private final Map<String, Deque<ChatMessage>> recentByPlayer = new ConcurrentHashMap<>();

    public ChatLogService(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;

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

        Player player = e.getPlayer();
        PluginConfig snapshot = this.config;
        if (snapshot == null) return;

        String notifyPerm = snapshot.notifyPermission == null ? "" : snapshot.notifyPermission.trim();
        if (!notifyPerm.isEmpty() && !player.hasPermission(notifyPerm)) {
            return;
        }

        long delayTicks = Math.max(0, snapshot.staffJoinSummaryDelayTicks);
        UUID uuid = player.getUniqueId();
        var builder = plugin.proxy().getScheduler().buildTask(plugin, () -> {
            var current = plugin.proxy().getPlayer(uuid);
            if (current.isEmpty()) return;
            sendStaffSummary(current.get(), snapshot);
        });
        if (delayTicks > 0) {
            builder.delay(Duration.ofMillis(delayTicks * 50L));
        }
        builder.schedule();
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        Player p = e.getPlayer();
        String name = p.getUsername();
        String lowered = name.toLowerCase(Locale.ROOT);
        String server = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("UNKNOWN");
        long now = System.currentTimeMillis();

        // Ensure ChatMessage(server) is truly a server name (not the username)
        ChatMessage msg = new ChatMessage(now, name, server, e.getMessage());

        // 1) ALWAYS record in rolling buffer
        recordToBuffer(name, msg, now);

        // 2) If the player is being watched, live-append to their open reports
        if (watchedPlayers.contains(lowered)) {
            for (Report r : mgr.getOpenReportsFor(name)) {
                mgr.appendChat(r.id, msg);
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

    private void sendStaffSummary(Player player, PluginConfig snapshot) {
        List<Report> open = mgr.getOpenReportsDescending();
        int totalOpen = open.size();
        String name = player.getUsername();
        int mine = (int) open.stream()
                .filter(r -> r.assignee != null && r.assignee.equalsIgnoreCase(name))
                .count();
        int closed = mgr.countClosedReports();

        String openLabel = snapshot.msg("summary-open-label", "%count% open").replace("%count%", String.valueOf(totalOpen));
        String claimedLabel = snapshot.msg("summary-claimed-label", "%count% claimed").replace("%count%", String.valueOf(mine));
        String closedLabel = snapshot.msg("summary-closed-label", "%count% closed").replace("%count%", String.valueOf(closed));

        String openTip = snapshot.msg("summary-open-tip", "View open reports");
        String claimedTip = snapshot.msg("summary-claimed-tip", "View your claimed reports");
        String closedTip = snapshot.msg("summary-closed-tip", "View closed reports");

        String openColor = snapshot.msg("summary-open-color", "<white>");
        String claimedColor = snapshot.msg("summary-claimed-color", "<white>");
        String closedColor = snapshot.msg("summary-closed-color", "<white>");

        String openSegment = summarySegment(openLabel, "/reports", openTip, openColor);
        String claimedSegment = summarySegment(claimedLabel, "/reports claimed", claimedTip, claimedColor);
        String closedSegment = summarySegment(closedLabel, "/reporthistory", closedTip, closedColor);

        String template = snapshot.msg("staff-join-summary",
                "<gray>Reports summary:</gray> %open% <gray>•</gray> %claimed% <gray>•</gray> %closed%.");
        String line = template
                .replace("%open%", openSegment)
                .replace("%claimed%", claimedSegment)
                .replace("%mine%", claimedSegment)
                .replace("%closed%", closedSegment);
        Text.msg(player, line + "\n");
    }

    private static String summarySegment(String label, String command, String tip, String colorTag) {
        String safeTip = Text.escape(tip == null ? "" : tip);
        String safeLabel = Text.escape(label == null ? "" : label);
        String safeCmd = Text.escape(command == null ? "" : command).replace("'", "\\'");
        String color = normalizeColor(colorTag);
        String close = closeTag(color);
        return "<hover:show_text:'" + safeTip + "'><click:run_command:'" + safeCmd + "'>" + color + safeLabel + close + "</click></hover>";
    }

    private static String normalizeColor(String color) {
        String trimmed = color == null ? "" : color.trim();
        return trimmed.isEmpty() ? "<white>" : trimmed;
    }

    private static String closeTag(String color) {
        if (color == null) return "";
        String trimmed = color.trim();
        if (!trimmed.startsWith("<") || !trimmed.endsWith(">")) return "";
        String inner = trimmed.substring(1, trimmed.length() - 1);
        if (inner.startsWith("/")) return "";
        int colon = inner.indexOf(':');
        if (colon > 0) inner = inner.substring(0, colon);
        int space = inner.indexOf(' ');
        if (space > 0) inner = inner.substring(0, space);
        if (inner.isEmpty()) return "";
        return "</" + inner + ">";
    }
}
