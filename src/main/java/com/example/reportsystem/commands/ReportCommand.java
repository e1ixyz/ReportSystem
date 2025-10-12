package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.model.ReportType;
import com.example.reportsystem.service.ChatLogService;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Text;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * /report command
 *
 * Usage (restored):
 *   /report <type> <category> [<target>] <reason...>
 *   - player type: /report player <category> <target> <reason...>
 *   - non-player : /report <type> <category> <reason...>
 *
 * Extras:
 *   - Staff notification button label & tooltip are configurable via messages:
 *       messages:
 *         label-expand: "Expand"   # e.g. set to "^"
 *         tip-expand:   "Click to expand"
 *   - Tab completion shows a "<reason...>" placeholder when you're at the reason position.
 */
public class ReportCommand implements SimpleCommand {

    private static final String REASON_PLACEHOLDER = "<reason...>";

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private final ChatLogService chat;
    private PluginConfig config;
    private final ConcurrentMap<UUID, Long> lastReportAt = new ConcurrentHashMap<>();

    public ReportCommand(ReportSystem plugin, ReportManager mgr, ChatLogService chat, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.chat = chat;
        this.config = config;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    @Override
    public void execute(Invocation inv) {
        CommandSource src = inv.source();
        String[] args = inv.arguments();

        // Expect at least: /report <type> <category> ...
        if (args.length < 2) {
            Text.msg(src, config.msg("usage-report", "Usage: /report <type> <category> [<target>] <reason...>"));
            return;
        }

        String typeId = args[0];
        String catId = args[1];

        ReportType rt = mgr.resolveType(typeId, catId);
        if (rt == null) {
            Text.msg(src, config.msg("invalid-type", "Unknown type or category: %type%/%cat%")
                    .replace("%type%", typeId)
                    .replace("%cat%", catId));
            return;
        }

        boolean isPlayerType = rt.typeId.equalsIgnoreCase("player");
        String reporter = (src instanceof Player p) ? p.getUsername() : "CONSOLE";
        String reported;
        String reason;

        Player playerSource = (src instanceof Player p) ? p : null;

        if (playerSource != null && !enforceCooldown(playerSource)) {
            return;
        }

        if (isPlayerType) {
            if (args.length < 4) {
                Text.msg(src, config.msg("usage-report", "Usage: /report player <category> <target> <reason...>"));
                return;
            }
            reported = args[2];
            reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

            if (!config.allowSelfReport && src instanceof Player p && p.getUsername().equalsIgnoreCase(reported)) {
                Text.msg(src, config.msg("self-report-denied", "You cannot report yourself."));
                return;
            }

            // Optional: validate target online (non-fatal)
            Optional<Player> t = plugin.proxy().getPlayer(reported);
            if (t.isEmpty()) {
                // still allow filing against offline names
            }
        } else {
            // Non-player-type: everything after category is reason
            if (args.length < 3) {
                Text.msg(src, config.msg("usage-report", "Usage: /report <type> <category> <reason...>"));
                return;
            }
            reported = "N/A";
            reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        }

        // File or stack
        Report r = mgr.fileOrStack(reporter, reported, rt, reason);

        // Persist the source server for better "Jump" inference later
        if (src instanceof Player p) {
            String srcServer = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
            if (srcServer != null && !srcServer.isBlank()) {
                mgr.updateSourceServer(r.id, srcServer);
            }
            lastReportAt.put(p.getUniqueId(), System.currentTimeMillis());
        }

        chat.refreshWatchList(); // ensure chat capture watches targets for subsequent messages

        if (r.count > 1) {
            Text.msg(src, config.msg("report-stacked", "Report stacked into #%id% (now x%count%)")
                    .replace("%id%", String.valueOf(r.id))
                    .replace("%count%", String.valueOf(r.count)));
        } else {
            Text.msg(src, config.msg("report-filed", "Report filed! (ID #%id%)")
                    .replace("%id%", String.valueOf(r.id)));
        }

        // In-game notify to staff with permission
        String notifyPerm = (config.notifyPermission == null || config.notifyPermission.isBlank())
                ? "reportsystem.notify" : config.notifyPermission;

        // Configurable action label & tooltip, defaults match /reports style
        String expandLabel = config.msg("label-expand", "Expand");        // e.g., set to "^" in config messages if desired
        String expandTip   = config.msg("tip-expand", "Click to expand"); // hover tooltip

        String expandTemplate = config.msg("reports-notify-expand-button",
                "<gray>[</gray><aqua><hover:show_text:'%expand_tip%'><click:run_command:'/reports view %id%'>%expand_label%</click></hover></aqua><gray>]</gray>");
        String expandSegment = expandTemplate
                .replace("%expand_tip%", Text.escape(expandTip))
                .replace("%expand_label%", Text.escape(expandLabel))
                .replace("%id%", String.valueOf(r.id));

        String summaryTemplate = config.msg("reports-notify-summary",
                "<yellow>New report:</yellow> <white>#%id%</white> <gray>(%type% / %category%)</gray> " +
                        "<white>%target%</white> — <gray>%reason%</gray> %expand%");
        String summary = summaryTemplate
                .replace("%id%", String.valueOf(r.id))
                .replace("%type%", Text.escape(r.typeDisplay))
                .replace("%category%", Text.escape(r.categoryDisplay))
                .replace("%target%", Text.escape(isPlayerType ? r.reported : "—"))
                .replace("%reason%", Text.escape(reason))
                .replace("%expand%", expandSegment);

        plugin.proxy().getAllPlayers().forEach(pl -> {
            if (pl.hasPermission(notifyPerm)) {
                Text.msg(pl, summary + "\n");
            }
        });

        // Optional notifier (kept)
        try {
            Object n = plugin.notifier();
            if (n != null) {
                n.getClass().getMethod("notifyNew", Report.class, String.class).invoke(n, r, reason);
            }
        } catch (Throwable ignored) {}
    }

    private boolean enforceCooldown(Player player) {
        if (player.hasPermission(config.staffPermission)) return true;

        int cooldown = Math.max(0, config.reportCooldownSeconds);
        if (cooldown <= 0) return true;

        long now = System.currentTimeMillis();
        long last = lastReportAt.getOrDefault(player.getUniqueId(), 0L);
        long waitMillis = (last + cooldown * 1000L) - now;
        if (waitMillis <= 0) return true;

        long secondsLeft = (waitMillis + 999L) / 1000L;
        Text.msg(player, config.msg("report-cooldown", "<red>You must wait %seconds%s before filing another report.</red>")
                .replace("%seconds%", String.valueOf(secondsLeft)));
        return false;
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();

        // /report <type>
        if (a.length == 0) return mgr.typeIds();

        // /report <type> ...
        if (a.length == 1) return filter(mgr.typeIds(), a[0]);

        // /report <type> <category> ...
        if (a.length == 2) return filter(mgr.categoryIdsFor(a[0]), a[1]);

        // Player type: /report player <category> <target> <reason...>
        if (a[0].equalsIgnoreCase("player")) {
            if (a.length == 3) {
                // suggest online names for <target>
                var names = plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList();
                return filter(names, a[2]);
            }
            // Next (a.length >= 4) -> reason placeholder
            return reasonPlaceholder(a.length >= 4 ? a[3] : "");
        }

        // Non-player types: after <type> <category>, suggest <reason...>
        if (a.length >= 3) {
            return reasonPlaceholder(a[2]);
        }

        return List.of();
    }

    /* -------------------- helpers -------------------- */

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    private static List<String> reasonPlaceholder(String current) {
        // Only suggest a placeholder (token-based), not real text completions
        if (current == null || current.isBlank()) return List.of(REASON_PLACEHOLDER);
        String p = current.toLowerCase(Locale.ROOT);
        return REASON_PLACEHOLDER.toLowerCase(Locale.ROOT).startsWith(p)
                ? List.of(REASON_PLACEHOLDER)
                : List.of(); // user already typing the reason
    }
}
