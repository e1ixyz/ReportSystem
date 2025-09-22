package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.service.HtmlExporter;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Pagination;
import com.example.reportsystem.util.Text;
import com.example.reportsystem.util.TimeUtil;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class ReportsCommand implements SimpleCommand {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private PluginConfig config;

    public ReportsCommand(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
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

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "page" -> {
                int page = 1;
                if (args.length >= 2) { try { page = Math.max(1, Integer.parseInt(args[1])); } catch (Exception ignored) {} }
                showPage(src, page);
            }
            case "view" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports view <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null || !r.isOpen()) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                expand(src, r);
            }
            case "close" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports close <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                mgr.close(id); mgr.save();
                Text.msg(src, config.msg("closed","Closed report #%id%").replace("%id%", String.valueOf(id)));
                plugin.notifier().notifyClosed(r);
            }
            case "chat" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports chat <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                if (r.chat == null || r.chat.isEmpty()) { Text.msg(src, config.msg("chatlog-none","No chat messages were captured for this report.")); return; }
                if (config.exportHtmlChatlog) {
                    try {
                        Path html = new HtmlExporter(plugin, config).export(r);
                        String url = "file://" + html.toAbsolutePath();
                        Text.msg(src, config.msg("chatlog-open-url","Open chat log: %url%").replace("%url%", url));
                    } catch (Exception ex) {
                        Text.msg(src, "<red>Failed to export HTML chat log:</red> <gray>"+ex.getMessage()+"</gray>");
                    }
                } else {
                    Text.msg(src, "<gray>Showing first 20 chat lines for #"+r.id+":</gray>");
                    int n = Math.min(20, r.chat.size());
                    for (int i=0;i<n;i++) {
                        var m = r.chat.get(i);
                        Text.msg(src, "<gray>"+ TimeUtil.formatTime(m.time)+"</gray> <white>"+m.player+"</white><gray>@</gray><white>"+m.server+"</white><gray>:</gray> "+Text.escape(m.message));
                    }
                }
            }
            case "assign" -> {
                if (args.length < 3) { Text.msg(src, "<yellow>Usage:</yellow> /reports assign <id> <staff>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                String staff = args[2];
                mgr.assign(id, staff);
                Text.msg(src, config.msg("assigned","Assigned report #%id% to %assignee%").replace("%id%", String.valueOf(id)).replace("%assignee%", staff));
                plugin.notifier().notifyAssigned(r, staff);
            }
            case "unassign" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports unassign <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                mgr.unassign(id);
                Text.msg(src, config.msg("unassigned","Unassigned report #%id%").replace("%id%", String.valueOf(id)));
                plugin.notifier().notifyUnassigned(r);
            }
            case "search" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports search <query> [open|closed|all]</yellow>"); return; }
                String scope = args.length >= 3 ? args[2] : "open";
                String query = args[1];
                var results = mgr.search(query, scope);
                if (results.isEmpty()) { Text.msg(src, config.msg("search-empty","No matching reports.")); return; }
                Text.msg(src, config.msg("search-header","Search: %query% (%scope%)").replace("%query%", query).replace("%scope%", scope));
                int shown = 0, limit = Math.min(30, results.size());
                for (int i=0;i<limit;i++) {
                    Report r = results.get(i);
                    String line = "<white>#"+r.id+"</white> <gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> <white>"+r.reported+"</white>"
                            + (r.assignee != null ? " <gray>[</gray><white>"+r.assignee+"</white><gray>]</gray>" : "")
                            + " <gray>[</gray><aqua><click:run_command:'/reports view "+r.id+"'>expand</click></aqua><gray>]</gray>";
                    Text.msg(src, line);
                    shown++;
                }
                if (results.size() > shown) Text.msg(src, "<gray>…and "+(results.size()-shown)+" more.</gray>");
            }
            case "reload" -> {
                plugin.reload();
                Text.msg(src, config.msg("reloaded","ReportSystem reloaded."));
            }
            default -> showPage(src, 1);
        }
    }

    // (helpers unchanged)
    private void showPage(CommandSource src, int page) { /* ... unchanged from v2 ... */ 
        List<Report> open = mgr.getOpenReportsDescending();
        if (open.isEmpty()) { Text.msg(src, config.msg("page-empty","No open reports.")); return; }

        int per = Math.max(1, config.reportsPerPage);
        int pages = Math.max(1, (int) Math.ceil(open.size() / (double) per));
        page = Math.min(Math.max(1, page), pages);

        Text.msg(src, config.msg("page-header","Reports Page %page%/%pages%")
                .replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(pages)));

        Pagination.paginate(open, per, page).forEach(r -> {
            String stacked = r.count > 1 ? " <gray>(x"+r.count+")</gray>" : "";
            String assignee = r.assignee != null ? " <gray>[</gray><white>"+r.assignee+"</white><gray>]</gray>" : "";
            String line = "<white>#"+r.id+"</white> "
                    + "<gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> "
                    + "<white>"+r.reported+"</white>"
                    + stacked + assignee
                    + "  <gray>[</gray><aqua><click:run_command:'/reports view "+r.id+"'>expand</click></aqua><gray>]</gray>";
            Text.msg(src, line);
        });

        Component nav = Text.mm("<gray>[</gray><aqua><click:run_command:'/reports page "+Math.max(1, page-1)+"'>« Prev</click></aqua><gray>] [</gray><aqua><click:run_command:'/reports page "+Math.min(pages, page+1)+"'>Next »</click></aqua><gray>] · [</gray><aqua><click:run_command:'/reports reload'>Reload</click></aqua><gray>]</gray>");
        src.sendMessage(nav);
    }

    private void expand(CommandSource src, Report r) { /* unchanged from v2 */ 
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
                    .replace("%player%", Text.escape(r.reporter))
                    .replace("%target%", Text.escape(r.reported))
                    .replace("%timestamp%", TimeUtil.formatDateTime(r.timestamp))
                    .replace("%count%", String.valueOf(r.count))
                    .replace("%reasons%", Text.escape(r.reason))
                    .replace("%status%", r.status.name())
                    .replace("%assignee%", r.assignee == null ? "—" : Text.escape(r.assignee));
            Text.msg(src, out);
        }
        Text.msg(src, config.msg("expanded-actions","[Close] [Chat Logs]").replace("%id%", String.valueOf(r.id)));
        Text.msg(src, "<gray>[</gray><aqua><click:run_command:'/reports assign "+r.id+" "+suggestAssignee(src)+"'>assign to me</click></aqua><gray>] [</gray><aqua><click:run_command:'/reports unassign "+r.id+"'>unassign</click></aqua><gray>]</gray>");
    }

    private static String suggestAssignee(CommandSource src) {
        return (src instanceof Player p) ? p.getUsername() : "CONSOLE";
    }

    private static long parseLong(String s, long def) { try { return Long.parseLong(s); } catch (Exception e) { return def; } }
}
