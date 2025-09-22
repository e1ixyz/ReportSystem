package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Pagination;
import com.example.reportsystem.util.Text;
import com.example.reportsystem.util.TimeUtil;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.util.List;

public class ReportHistoryCommand implements SimpleCommand {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private final PluginConfig config;

    public ReportHistoryCommand(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
    }

    @Override
    public void execute(Invocation inv) {
        CommandSource src = inv.source();
        if (!src.hasPermission(config.staffPermission)) {
            Text.msg(src, config.msg("no-permission","You don't have permission."));
            return;
        }

        String[] args = inv.arguments();
        if (args.length == 0) { showPage(src, 1); return; }

        switch (args[0].toLowerCase()) {
            case "page" -> {
                int page = 1;
                if (args.length >= 2) {
                    try { page = Math.max(1, Integer.parseInt(args[1])); } catch (Exception ignored) {}
                }
                showPage(src, page);
            }
            case "view" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reporthistory view <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null || r.isOpen()) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                expandClosed(src, r);
            }
            case "reopen" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reporthistory reopen <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                if (r.isOpen()) {
                    Text.msg(src, config.msg("already-open","Report #%id% is already open.").replace("%id%", String.valueOf(id)));
                    return;
                }
                if (mgr.reopen(id)) {
                    Text.msg(src, config.msg("reopened","Reopened report #%id%").replace("%id%", String.valueOf(id)));
                    plugin.notifier().notifyReopened(r);
                } else {
                    Text.msg(src, "<red>Failed to reopen report.</red>");
                }
            }
            default -> showPage(src, 1);
        }
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length == 0) {
            return List.of("page", "view", "reopen");
        }
        switch (a[0].toLowerCase()) {
            case "page" -> {
                if (a.length == 1) return List.of("1", "2", "3");
            }
            case "view", "reopen" -> {
                var ids = mgr.getClosedReportsDescending().stream().map(r -> String.valueOf(r.id)).toList();
                if (a.length == 1) return ids;
            }
            default -> { /* no-op */ }
        }
        return List.of();
    }

    private void showPage(CommandSource src, int page) {
        List<Report> closed = mgr.getClosedReportsDescending();
        if (closed.isEmpty()) { Text.msg(src, config.msg("history-page-empty","No closed reports.")); return; }

        int per = Math.max(1, config.reportsPerPage);
        int pages = Math.max(1, (int) Math.ceil(closed.size() / (double) per));
        page = Math.min(Math.max(1, page), pages);

        Text.msg(src, config.msg("history-page-header","Closed Reports Page %page%/%pages%")
                .replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(pages)));

        Pagination.paginate(closed, per, page).forEach(r -> {
            String line = "<white>#"+r.id+"</white> "
                    + "<gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> "
                    + "<white>"+r.reported+"</white>"
                    + (r.assignee != null ? " <gray>[</gray><white>"+r.assignee+"</white><gray>]</gray>" : "")
                    + "  <gray>[</gray><aqua><click:run_command:'/reporthistory view "+r.id+"'>expand</click></aqua><gray>]</gray>"
                    + " <gray>[</gray><green><click:run_command:'/reporthistory reopen "+r.id+"'>reopen</click></green><gray>]</gray>";
            Text.msg(src, line);
        });

        Text.msg(src, "<gray>[</gray><aqua><click:run_command:'/reporthistory page "+Math.max(1, page-1)+"'>« Prev</click></aqua><gray>] [</gray><aqua><click:run_command:'/reporthistory page "+Math.min(pages, page+1)+"'>Next »</click></aqua><gray>]</gray>");
    }

    private void expandClosed(CommandSource src, Report r) {
        Text.msg(src, config.msg("expanded-header","Report #%id% (%type%/%category%)")
                .replace("%id%", String.valueOf(r.id))
                .replace("%type%", r.typeDisplay)
                .replace("%category%", r.categoryDisplay));

        for (String line : config.msgList("expanded-lines", List.of(
                "<gray>Reported:</gray> <white>%target%</white> <gray>by</gray> <white>%player%</white>",
                "<gray>When:</gray> <white>%timestamp%</white>",
                "<gray>Count:</gray> <white>%count%</white>",
                "<gray>Reason(s):</gray> <white>%reasons%</white>",
                "<gray>Status:</gray> <white>%status%</white>",
                "<gray>Assignee:</gray> <white>%assignee%</white>"
        ))) {
            String out = line
                    .replace("%player%", r.reporter)
                    .replace("%target%", r.reported)
                    .replace("%timestamp%", TimeUtil.formatDateTime(r.timestamp))
                    .replace("%count%", String.valueOf(r.count))
                    .replace("%reasons%", Text.escape(r.reason))
                    .replace("%status%", r.status.name())
                    .replace("%assignee%", r.assignee == null ? "—" : r.assignee);
            Text.msg(src, out);
        }

        Text.msg(src, config.msg("history-expanded-actions","[Reopen]").replace("%id%", String.valueOf(r.id)));
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
}
