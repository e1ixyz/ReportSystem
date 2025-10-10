package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.platform.CommandActor;
import com.example.reportsystem.platform.CommandContext;
import com.example.reportsystem.platform.CommandHandler;
import com.example.reportsystem.service.HtmlExporter;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Pagination;
import com.example.reportsystem.util.Text;
import com.example.reportsystem.util.TimeUtil;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /reporthistory
 *   - /reporthistory                 : paginated list of closed reports
 *   - /reporthistory page <n>        : go to page n
 *   - /reporthistory view <id>       : expand a closed report
 *   - /reporthistory chat <id> [p]   : chat logs (web link if web server enabled, else inline pagination)
 *   - /reporthistory reopen <id>     : reopen a closed report
 *
 * Visuals aligned with /reports:
 *   - List line: #id (Type/Category) <target> [assignee] [server]  [^ Expand] [Reopen]
 *   - target/assignee/server have hover tooltips.
 *   - Expand label uses messages.label-expand (e.g., "^")
 */
public class ReportHistoryCommand implements CommandHandler {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private PluginConfig config;

    public ReportHistoryCommand(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    @Override
    public void execute(CommandContext ctx) {
        CommandActor src = ctx.actor();
        if (!src.hasPermission(config.staffPermission)) {
            Text.msg(src, config.msg("no-permission","You don't have permission."));
            return;
        }

        String[] args = ctx.args();
        if (args.length == 0) { showPage(src, 1); return; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
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

            case "chat" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reporthistory chat <id> [page]"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null || r.chat == null || r.chat.isEmpty()) {
                    Text.msg(src, config.msg("chatlog-none","No chat messages were captured for this report."));
                    return;
                }

                boolean webEnabled = config.httpServer != null && config.httpServer.enabled;
                if (webEnabled) {
                    try {
                        new HtmlExporter(plugin, config).export(r);
                        String link = buildPublicLinkFor(r);
                        if (link == null || link.isBlank()) {
                            Text.msg(src, "<red>Web viewer is enabled but external/public base URL is not configured.</red>");
                            return;
                        }
                        String tip = config.msg("tip-open-browser", "Open in browser");
                        Text.msg(src, "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:open_url:'" + link + "'>"+config.msg("open-chatlog-label","Open chat log")+"</click></hover></aqua><gray>]</gray>");
                    } catch (Exception ex) {
                        Text.msg(src, "<red>Failed to export HTML chat log:</red> <gray>"+Text.escape(ex.getMessage())+"</gray>");
                    }
                } else {
                    int page = 1;
                    if (args.length >= 3) {
                        try { page = Math.max(1, Integer.parseInt(args[2])); } catch (Exception ignored) {}
                    }
                    showChatPage(src, r, page);
                }
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
                    try {
                        Object n = plugin.notifier();
                        if (n != null) n.getClass().getMethod("notifyReopened", Report.class).invoke(n, r);
                    } catch (Throwable ignored) {}
                } else {
                    Text.msg(src, "<red>Failed to reopen report.</red>");
                }
            }

            default -> showPage(src, 1);
        }
    }

    @Override
    public List<String> suggest(CommandContext ctx) {
        String[] a = ctx.args();
        if (a.length == 0) {
            return List.of("page", "view", "chat", "reopen");
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "page" -> {
                if (a.length == 1) return List.of("1", "2", "3");
            }
            case "view", "chat", "reopen" -> {
                var ids = historyIdSuggestions();
                if (a.length <= 1) return ids;
                if (a.length >= 2) {
                    if (a[0].equalsIgnoreCase("chat") && a.length == 3) {
                        return filter(List.of("<page>", "1", "2", "3"), a[2]);
                    }
                    return filter(ids, a[1]);
                }
            }
            default -> { /* no-op */ }
        }
        return List.of();
    }

    /* ------------------------- UI ------------------------- */

    private void showPage(CommandActor src, int page) {
        List<Report> closed = mgr.getClosedReportsDescending();
        if (closed.isEmpty()) { Text.msg(src, config.msg("history-page-empty","No closed reports.")); return; }

        int per = Math.max(1, config.reportsPerPage);
        int pages = Math.max(1, (int) Math.ceil(closed.size() / (double) per));
        page = Math.min(Math.max(1, page), pages);

        Text.msg(src, config.msg("history-page-header","Closed Reports Page %page%/%pages%")
                .replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(pages)));

        String tipExpand = config.msg("tip-expand", "Click to expand");
        String tipReopen = config.msg("tip-reopen", "Reopen this report");

        for (Report r : Pagination.paginate(closed, per, page)) {
            String line = fmtListLineClosed(r)
                    + "  <gray>[</gray><aqua><hover:show_text:'"+Text.escape(tipExpand)+"'><click:run_command:'/reporthistory view "+r.id+"'>"+expandLabel()+"</click></hover></aqua><gray>]</gray>"
                    + " <gray>[</gray><green><hover:show_text:'"+Text.escape(tipReopen)+"'><click:run_command:'/reporthistory reopen "+r.id+"'>Reopen</click></hover></green><gray>]</gray>";
            Text.msg(src, line);
        }

        String prevTip = config.msg("tip-prev", "Previous page");
        String nextTip = config.msg("tip-next", "Next page");
        Text.msg(src,
                "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(prevTip)+"'><click:run_command:'/reporthistory page "+Math.max(1, page-1)+"'>« Prev</click></hover></aqua><gray>] " +
                "[</gray><aqua><hover:show_text:'"+Text.escape(nextTip)+"'><click:run_command:'/reporthistory page "+Math.min(pages, page+1)+"'>Next »</click></hover></aqua><gray>]</gray>"
        );
    }

    /** List line matches /reports style: Target (no label) + [Assignee] + [Server] with hovers. */
    private String fmtListLineClosed(Report r) {
        String target = (r.reported == null || r.reported.isBlank()) ? "UNKNOWN" : r.reported;
        String tipTarget = config.msg("tip-target", "Target: %name%").replace("%name%", target);

        String assignee = (r.assignee == null || r.assignee.isBlank()) ? "—" : r.assignee;
        String tipAssigned = config.msg("tip-assigned", "Assigned: %name%").replace("%name%", assignee);

        String server = inferServer(r);
        String tipServer = config.msg("tip-server", "Server: %name%").replace("%name%", server);

        return "<white>#"+r.id+"</white> "
                + "<gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> "
                + "<hover:show_text:'"+Text.escape(tipTarget)+"'><white>"+Text.escape(target)+"</white></hover> "
                + "<gray>[</gray><hover:show_text:'"+Text.escape(tipAssigned)+"'><white>"+Text.escape(assignee)+"</white></hover><gray>]</gray> "
                + "<gray>[</gray><hover:show_text:'"+Text.escape(tipServer)+"'><white>"+Text.escape(server)+"</white></hover><gray>]</gray>"
                + colorStackBadge(r.count);
    }

    private void expandClosed(CommandActor src, Report r) {
        Text.msg(src, config.msg("expanded-header","Report #%id% (%type% / %category%)")
                .replace("%id%", String.valueOf(r.id))
                .replace("%type%", r.typeDisplay)
                .replace("%category%", r.categoryDisplay));

        String serverName = inferServer(r);
        String serverLine = config.msg("expanded-server-line", "<gray>Server:</gray> <white>%server%</white>")
                .replace("%server%", serverName);
        Text.msg(src, serverLine);

        for (String line : config.msgList("expanded-lines", List.of(
                "<gray>Reported:</gray> <white>%target%</white> <gray>by</gray> <white>%player%</white>",
                "<gray>When:</gray> <white>%timestamp%</white>",
                "<gray>Count:</gray> <white>%count%</white>",
                "<gray>Reason(s):</gray> <white>%reasons%</white>",
                "<gray>Status:</gray> <white>%status%</white>",
                "<gray>Assignee:</gray> <white>%assignee%</white>"
        ))) {
            String out = line
                    .replace("%player%", r.reporter == null ? "UNKNOWN" : r.reporter)
                    .replace("%target%", r.reported == null ? "UNKNOWN" : r.reported)
                    .replace("%timestamp%", TimeUtil.formatDateTime(r.timestamp))
                    .replace("%count%", String.valueOf(r.count))
                    .replace("%reasons%", Text.escape(r.reason == null ? "—" : r.reason))
                    .replace("%status%", r.status == null ? "CLOSED" : r.status.name())
                    .replace("%assignee%", r.assignee == null || r.assignee.isBlank() ? "—" : r.assignee)
                    .replace("%server%", serverName);
            Text.msg(src, out);
        }

        String tipChat  = config.msg("tip-chat", "View chat logs");
        String tipReopen = config.msg("tip-reopen", "Reopen this report");
        String tipJump  = config.msg("tip-jump-server", "Connect to this server");

        StringBuilder actions = new StringBuilder();
        actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipChat))
                .append("'><click:run_command:'/reporthistory chat ").append(r.id).append("'>Chat Logs</click></hover></aqua><gray>]</gray> ");
        actions.append("<gray>[</gray><green><hover:show_text:'").append(Text.escape(tipReopen))
                .append("'><click:run_command:'/reporthistory reopen ").append(r.id).append("'>Reopen</click></hover></green><gray>]</gray> ");

        var jumpOpt = plugin.platform().jumpCommandFor(serverName);
        if (jumpOpt.isPresent()) {
            String baseCommand = jumpOpt.get();
            String template = config.msg("jump-command-template", baseCommand);
            String jumpCmd = template
                    .replace("%command%", baseCommand)
                    .replace("%server%", serverName);
            actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipJump))
                    .append("'><click:run_command:'").append(Text.escape(jumpCmd))
                    .append("'>Jump to server</click></hover></aqua><gray>]</gray> ");
        }

        Text.msg(src, actions.toString());
    }

    /* ---------------------- helpers & utils ---------------------- */

    private String expandLabel() {
        String s = config.msg("label-expand", "EXPAND");
        return (s == null || s.isBlank()) ? "EXPAND" : s;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    /** Build a public URL to the exported HTML page if configured (cleans duplicate slashes). */
    private String buildPublicLinkFor(Report r) {
        String base = pickBaseUrl(config);
        if (base.isBlank()) return null;
        return joinUrl(base, "/" + r.id + "/index.html");
    }

    /** Prefer publicBaseUrl, then httpServer.externalBaseUrl. Strips trailing slashes. */
    private static String pickBaseUrl(PluginConfig c) {
        String a = (c.publicBaseUrl == null) ? "" : c.publicBaseUrl.trim();
        String b = (c.httpServer != null && c.httpServer.externalBaseUrl != null)
                ? c.httpServer.externalBaseUrl.trim() : "";
        String base = !a.isBlank() ? a : b;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    /** Join base + path without doubling “/”. */
    private static String joinUrl(String base, String path) {
        if (base == null || base.isBlank()) {
            return (path == null || path.isBlank()) ? "/" : (path.startsWith("/") ? path : "/" + path);
        }
        String p = (path == null) ? "" : path.trim();
        if (p.isEmpty() || p.equals("/")) return base;
        if (p.startsWith("/")) return base + p;
        return base + "/" + p;
    }

    /** Infer server from the newest chat message (falls back to UNKNOWN). */
    private String inferServer(Report r) {
        if (r == null || r.chat == null || r.chat.isEmpty()) return "UNKNOWN";
        return r.chat.stream()
                .max(Comparator.comparingLong(cm -> cm.time))
                .map(cm -> cm.server == null || cm.server.isBlank() ? "UNKNOWN" : cm.server)
                .orElse("UNKNOWN");
    }

    private String colorStackBadge(int count) {
        if (count <= 1) return "";
        String color;
        if (count > config.threshDarkRed) color = config.colorDarkRed;
        else if (count > config.threshRed) color = config.colorRed;
        else if (count > config.threshGold) color = config.colorGold;
        else if (count > config.threshYellow) color = config.colorYellow;
        else color = "<gray>";
        String close = "</" + color.replace("<","").replace(">","") + ">";
        return " " + color + "(x" + count + ")" + close;
    }

    /** Paginated inline chat output when web viewer is disabled. */
    private void showChatPage(CommandActor src, Report r, int page) {
        int per = Math.max(1, config.previewLines);
        int total = r.chat.size();
        int pages = Math.max(1, (int)Math.ceil(total / (double) per));
        page = Math.min(Math.max(1, page), pages);

        int start = (page - 1) * per;
        int end = Math.min(start + per, total);

        Text.msg(src, "<gray>Chat for #"+r.id+" — page "+page+"/"+pages+" ("+total+" lines):</gray>");
        for (int i = start; i < end; i++) {
            var m = r.chat.get(i);
            String raw = "["+ TimeUtil.formatTime(m.time)+"] "+m.player+"@"+m.server+": "+m.message;
            String safe = Text.escape(raw);
            if (safe.length() > config.previewLineMaxChars) {
                int lim = Math.max(0, config.previewLineMaxChars - 1);
                safe = safe.substring(0, lim) + "…";
            }
            Text.msg(src, "<gray>"+ safe +"</gray>");
        }

        if (pages > 1) {
            String prevTip = config.msg("tip-prev", "Previous page");
            String nextTip = config.msg("tip-next", "Next page");
            int prev = Math.max(1, page - 1);
            int next = Math.min(pages, page + 1);
            Component nav = Text.mm(
                    "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(prevTip)+"'><click:run_command:'/reporthistory chat "+r.id+" "+prev+"'>« Prev</click></hover></aqua><gray>] " +
                    "[</gray><aqua><hover:show_text:'"+Text.escape(nextTip)+"'><click:run_command:'/reporthistory chat "+r.id+" "+next+"'>Next »</click></hover></aqua><gray>]</gray>"
            );
            src.sendMessage(nav);
        }
    }

    private List<String> historyIdSuggestions() {
        List<String> ids = new ArrayList<>();
        ids.add("<id>");
        mgr.getClosedReportsDescending().stream()
                .map(r -> String.valueOf(r.id))
                .forEach(ids::add);
        return ids;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(opt -> opt.toLowerCase(Locale.ROOT).startsWith(p))
                .toList();
    }
}
