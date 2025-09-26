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
import java.util.concurrent.ConcurrentHashMap;

/** /report <type> <category> [<target>] <reason...> */
public class ReportCommand implements SimpleCommand {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private final ChatLogService chat;
    private PluginConfig config;

    // Cooldown tracking per reporter name (case-insensitive)
    private final ConcurrentHashMap<String, Long> lastReportAt = new ConcurrentHashMap<>();

    public ReportCommand(ReportSystem plugin, ReportManager mgr, ChatLogService chat, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.chat = chat;
        this.config = config;
    }

    @Override
    public void execute(Invocation inv) {
        CommandSource src = inv.source();
        String[] args = inv.arguments();

        if (args.length < 2) {
            Text.msg(src, config.msg("usage-report", "Usage: /report <type> <category> [<target>] <reason...>"));
            return;
        }

        // Cooldown for non-staff players
        if (src instanceof Player p) {
            boolean isStaff = p.hasPermission(config.staffPermission);
            if (!isStaff) {
                int cd = Math.max(0, config.reportCooldownSeconds);
                if (cd > 0) {
                    String key = p.getUsername().toLowerCase(Locale.ROOT);
                    long now = System.currentTimeMillis();
                    long last = lastReportAt.getOrDefault(key, 0L);
                    long leftMs = (last + cd * 1000L) - now;
                    if (leftMs > 0) {
                        long leftSec = (leftMs + 999) / 1000;
                        Text.msg(src, "<red>Please wait "+leftSec+"s before submitting another report.</red>");
                        return;
                    }
                }
            }
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

            Optional<Player> t = plugin.proxy().getPlayer(reported);
            if (t.isEmpty()) {
                // still allow filing against offline names
            }
        } else {
            if (args.length < 3) {
                Text.msg(src, config.msg("usage-report", "Usage: /report <type> <category> <reason...>"));
                return;
            }
            reported = "N/A";
            reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        }

        Report r = mgr.fileOrStack(reporter, reported, rt, reason);
        chat.refreshWatchList();

        if (r.count > 1) {
            Text.msg(src, config.msg("report-stacked", "Report stacked into #%id% (now x%count%)")
                    .replace("%id%", String.valueOf(r.id))
                    .replace("%count%", String.valueOf(r.count)));
        } else {
            Text.msg(src, config.msg("report-filed", "Report filed! (ID #%id%)")
                    .replace("%id%", String.valueOf(r.id)));
        }

        // Set cooldown timestamp AFTER a successful file
        if (src instanceof Player p && !p.hasPermission(config.staffPermission)) {
            lastReportAt.put(p.getUsername().toLowerCase(Locale.ROOT), System.currentTimeMillis());
        }

        // Notify staff
        String notifyPerm = (config.notifyPermission == null || config.notifyPermission.isBlank())
                ? "reportsystem.notify" : config.notifyPermission;

        String summary = "<yellow>New report:</yellow> <white>#"+r.id+
                "</white> <gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> " +
                "<white>"+(isPlayerType ? r.reported : "—")+"</white> — <gray>"+Text.escape(reason)+
                "</gray>  <gray>[</gray><aqua><hover:show_text:'View report'><click:run_command:'/reports view "+r.id+"'>view</click></hover></aqua><gray>]</gray>";

        plugin.proxy().getAllPlayers().forEach(pl -> {
            if (pl.hasPermission(notifyPerm)) Text.msg(pl, summary);
        });

        // Optional notifier
        try {
            Object n = plugin.notifier();
            if (n != null) n.getClass().getMethod("notifyNew", Report.class, String.class).invoke(n, r, reason);
        } catch (Throwable ignored) {}
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length == 0) return mgr.typeIds();
        if (a.length == 1) return filter(mgr.typeIds(), a[0]);
        if (a.length == 2) return filter(mgr.categoryIdsFor(a[0]), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("player")) {
            var names = plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList();
            return filter(names, a[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
