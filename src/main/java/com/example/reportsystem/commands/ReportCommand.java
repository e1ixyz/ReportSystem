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

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * /report command
 *
 * Usage:
 *   /report <player> <type> <category> [reason...]
 *
 * Notes:
 * - Adjusted to call ReportManager.fileOrStack(reporter, reported, ReportType, reason)
 *   (4 args). Any older extra parameter (e.g., server hint) was removed in ReportManager.
 */
public class ReportCommand implements SimpleCommand {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private final ChatLogService chatLogs; // kept for compatibility (may be used by listeners)
    private PluginConfig config;

    public ReportCommand(ReportSystem plugin, ReportManager mgr, ChatLogService chatLogs, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.chatLogs = chatLogs;
        this.config = config;
    }

    @Override
    public void execute(Invocation inv) {
        CommandSource src = inv.source();

        // Must be a player to file a report (adjust if you want console support)
        if (!(src instanceof Player p)) {
            Text.msg(src, "<red>Only players can use /report.</red>");
            return;
        }

        String[] args = inv.arguments();
        if (args.length < 3) {
            Text.msg(src, "<yellow>Usage:</yellow> /report <player> <type> <category> [reason...]");
            // show quick hints for types/categories
            if (!mgr.typeIds().isEmpty()) {
                Text.msg(src, "<gray>Types:</gray> <white>" + String.join(", ", mgr.typeIds()) + "</white>");
            }
            return;
        }

        String reported = args[0];
        String typeId = args[1];
        String categoryId = args[2];

        // Optional reason
        String reason = "";
        if (args.length > 3) {
            StringJoiner sj = new StringJoiner(" ");
            for (int i = 3; i < args.length; i++) sj.add(args[i]);
            reason = sj.toString();
        }

        // Validate type/category via config
        ReportType rt = mgr.resolveType(typeId, categoryId);
        if (rt == null) {
            Text.msg(src, "<red>Unknown type/category:</red> <gray>" + typeId + "/" + categoryId + "</gray>");
            // help: show categories for this type if type exists
            List<String> cats = mgr.categoryIdsFor(typeId);
            if (!cats.isEmpty()) {
                Text.msg(src, "<gray>Valid categories for</gray> <white>"+typeId+"</white><gray>:</gray> <white>"+String.join(", ", cats)+"</white>");
            } else {
                Text.msg(src, "<gray>Valid types:</gray> <white>"+String.join(", ", mgr.typeIds())+"</white>");
            }
            return;
        }

        // Self-report check
        if (!config.allowSelfReport && reported.equalsIgnoreCase(p.getUsername())) {
            Text.msg(src, "<red>You cannot report yourself.</red>");
            return;
        }

        // Create or stack (NOTE: 4-arg call!)
        Report r = mgr.fileOrStack(p.getUsername(), reported, rt, reason);

        // Persist (mgr.save() is no-op since per-report saves happen inside manager; still call for legacy)
        mgr.save();

        // Notify reporter
        String idStr = String.valueOf(r.id);
        String msg = config.msg("report-filed",
                "Reported <white>%target%</white> for <white>%type%/%cat%</white> (#%id%).")
                .replace("%target%", reported)
                .replace("%type%", rt.typeDisplay)
                .replace("%cat%", rt.categoryDisplay)
                .replace("%id%", idStr);

        Text.msg(src, msg);

        // Optional staff broadcast (if notifyPermission is set)
        plugin.proxy().getAllPlayers().forEach(pl -> {
            if (pl.hasPermission(config.notifyPermission)) {
                String staffMsg = config.msg("staff-alert",
                        "<gray>[Report]</gray> <white>%reporter%</white> -> <white>%target%</white> <gray>(%type%/%cat%)</gray> <gray>[</gray><aqua><click:run_command:'/reports view %id%'>EXPAND</click></aqua><gray>]</gray>")
                        .replace("%reporter%", p.getUsername())
                        .replace("%target%", reported)
                        .replace("%type%", rt.typeDisplay)
                        .replace("%cat%", rt.categoryDisplay)
                        .replace("%id%", idStr);

                Text.msg(pl, staffMsg);
            }
        });
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length == 0) {
            // player names
            return plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList();
        }
        if (a.length == 1) {
            // type ids
            return mgr.typeIds();
        }
        if (a.length == 2) {
            // categories for given type id
            return mgr.categoryIdsFor(a[1]);
        }
        return List.of("<reason>");
    }
}
