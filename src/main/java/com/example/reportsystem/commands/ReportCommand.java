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
import java.util.Optional;

public class ReportCommand implements SimpleCommand {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private final ChatLogService chat;
    private PluginConfig config;

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

        if (args.length < 3) {
            Text.msg(src, config.msg("usage-report","Usage: /report <type> <category> <target|reason...> <reason...>"));
            return;
        }

        String typeId = args[0];
        String catId = args[1];

        ReportType rt = mgr.resolveType(typeId, catId);
        if (rt == null) {
            Text.msg(src, config.msg("invalid-type","Unknown type: %type%").replace("%type%", typeId));
            return;
        }

        boolean isPlayerType = rt.typeId.equalsIgnoreCase("player");

        String reported;
        String reason;
        if (isPlayerType) {
            if (args.length < 4) {
                Text.msg(src, config.msg("usage-report","Usage: /report <type> <category> <target> <reason...>"));
                return;
            }
            reported = args[2];
            reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));

            if (!config.allowSelfReport && src instanceof Player p && p.getUsername().equalsIgnoreCase(reported)) {
                Text.msg(src, config.msg("self-report-denied","You cannot report yourself."));
                return;
            }

            // verify target exists (online check only; you could extend with Redis or DB for offline)
            Optional<Player> target = plugin.proxy().getPlayer(reported);
            if (target.isEmpty()) {
                Text.msg(src, config.msg("player-not-found","Player not found: %target%").replace("%target%", reported));
                // Still allow filing for offline? You can change the policy. We'll allow filing anyway:
                // return;
            }

        } else {
            // bug or other type: entire rest is the "reported" subject + reason; we treat first token as subject if you want.
            reported = (src instanceof Player p) ? "N/A" : "N/A";
            reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            if (reason.isBlank()) {
                Text.msg(src, config.msg("usage-report","Usage: /report <type> <category> <reason...>"));
                return;
            }
        }

        String reporter = (src instanceof Player p) ? p.getUsername() : "CONSOLE";
        Report r = mgr.fileOrStack(reporter, reported, rt, reason);

        // Update chat watchers if it's a chat report against a player
        chat.refreshWatchList();

        if (r.count > 1) {
            Text.msg(src, config.msg("report-stacked","Report stacked into #%id% (now x%count%)")
                    .replace("%id%", String.valueOf(r.id))
                    .replace("%count%", String.valueOf(r.count)));
        } else {
            Text.msg(src, config.msg("report-filed","Report filed! (ID #%id%)")
                    .replace("%id%", String.valueOf(r.id)));
        }

        // Optional: notify online staff
        String staffPerm = config.staffPermission;
        plugin.proxy().getAllPlayers().forEach(pl -> {
            if (pl.hasPermission(staffPerm)) {
                Text.msg(pl, "<yellow>New report:</yellow> <white>#"+r.id+"</white> <gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> <white>"+r.reported+"</white> — <gray>"+reason+"</gray>  <gray>[</gray><aqua><click:run_command:'/reports view "+r.id+"'>view</click></aqua><gray>]</gray>");
            }
        });
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length == 0) return mgr.typeIds();
        if (a.length == 1) return filter(mgr.typeIds(), a[0]);

        if (a.length == 2) {
            return filter(mgr.categoryIdsFor(a[0]), a[1]);
        }

        // For player type with category 'chat' or 'cheating', suggest online players at arg3
        if (a.length == 3 && a[0].equalsIgnoreCase("player")) {
            var names = plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList();
            return filter(names, a[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(p)).toList();
    }
}
