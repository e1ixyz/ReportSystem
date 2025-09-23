package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.service.AuthService;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Staff /reports command.
 * Preserves your original behavior and formatting, plus:
 *  - /reports auth       (issues a one-time login code + link)
 *  - /reports logoutall  (revokes all web sessions for the caller)
 *  - Tooltips on all clickable texts
 *  - Expanded view shows inferred server (from latest chat message) + Jump button
 *  - Quick "Assign to me" / "Unassign" actions in expanded view (keeps /assign, /unassign too)
 */
public class ReportsCommand implements SimpleCommand {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private PluginConfig config;
    private final AuthService auth;

    public ReportsCommand(ReportSystem plugin, ReportManager mgr, PluginConfig config, AuthService auth) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
        this.auth = auth;
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
                if (r == null || !r.isOpen()) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                expand(src, r);
            }

            case "close" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports close <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                mgr.close(id); mgr.save();
                Text.msg(src, config.msg("closed","Closed report #%id%").replace("%id%", String.valueOf(id)));
                try {
                    Object n = plugin.notifier();
                    if (n != null) n.getClass().getMethod("notifyClosed", Report.class).invoke(n, r);
                } catch (Throwable ignored) {}
            }

            case "chat" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports chat <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                if (r.chat == null || r.chat.isEmpty()) {
                    Text.msg(src, config.msg("chatlog-none","No chat messages were captured for this report."));
                    return;
                }

                if (config.exportHtmlChatlog) {
                    try {
                        Path html = new HtmlExporter(plugin, config).export(r);
                        String link = buildPublicLinkFor(r);
                        if (link != null) {
                            String tip = config.msg("tip-open-browser", "Open in browser");
                            Text.msg(src, "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:open_url:'" + link + "'>Open chat log</click></hover></aqua><gray>]</gray>");
                        } else {
                            String pathStr = html.toAbsolutePath().toString();
                            String tip = config.msg("tip-copy-path", "Copy local path");
                            Text.msg(src, "<gray>Saved chat log:</gray> <white>" + Text.escape(pathStr) + "</white>");
                            Text.msg(src, "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:suggest_command:'" + Text.escape(pathStr) + "'>copy path</click></hover></aqua><gray>]</gray>");
                        }
                    } catch (Exception ex) {
                        Text.msg(src, "<red>Failed to export HTML chat log:</red> <gray>"+Text.escape(ex.getMessage())+"</gray>");
                    }
                } else {
                    Text.msg(src, "<gray>Showing up to "+config.previewLines+" chat lines for #"+r.id+" (truncated):</gray>");
                    int n = Math.min(Math.max(1, config.previewLines), r.chat.size());
                    for (int i=0;i<n;i++) {
                        var m = r.chat.get(i);
                        String raw = "["+ TimeUtil.formatTime(m.time)+"] "+m.player+"@"+m.server+": "+m.message;
                        String safe = Text.escape(raw);
                        if (safe.length() > config.previewLineMaxChars) {
                            int lim = Math.max(0, config.previewLineMaxChars - 1);
                            safe = safe.substring(0, lim) + "…";
                        }
                        Text.msg(src, "<gray>"+ safe +"</gray>");
                    }
                    if (r.chat.size() > n) {
                        Text.msg(src, "<gray>…and "+(r.chat.size()-n)+" more lines.</gray>");
                    }
                }
            }

            case "assign" -> {
                if (args.length < 3) { Text.msg(src, "<yellow>Usage:</yellow> /reports assign <id> <staff>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                String staff = args[2];
                mgr.assign(id, staff);
                Text.msg(src, config.msg("assigned","Assigned report #%id% to %assignee%")
                        .replace("%id%", String.valueOf(id)).replace("%assignee%", staff));
                try {
                    Object n = plugin.notifier();
                    if (n != null) n.getClass().getMethod("notifyAssigned", Report.class, String.class).invoke(n, r, staff);
                } catch (Throwable ignored) {}
            }

            case "unassign" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports unassign <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                if (r.assignee == null || r.assignee.isBlank()) {
                    Text.msg(src, config.msg("already-unassigned","Report #%id% is not assigned.").replace("%id%", String.valueOf(id)));
                    return;
                }
                mgr.unassign(id);
                Text.msg(src, config.msg("unassigned","Unassigned report #%id%").replace("%id%", String.valueOf(id)));
                try {
                    Object n = plugin.notifier();
                    if (n != null) n.getClass().getMethod("notifyUnassigned", Report.class).invoke(n, r);
                } catch (Throwable ignored) {}
            }

            case "search" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports search <query> [open|closed|all]</yellow>"); return; }
                String scope = args.length >= 3 ? args[2] : "open";
                String query = args[1];
                var results = mgr.search(query, scope);
                if (results.isEmpty()) { Text.msg(src, config.msg("search-empty","No matching reports.")); return; }
                Text.msg(src, config.msg("search-header","Search: %query% (%scope%)")
                        .replace("%query%", query).replace("%scope%", scope));
                int shown = 0, limit = Math.min(30, results.size());
                String expandTip = config.msg("tip-expand", "Click to expand");
                for (int i=0;i<limit;i++) {
                    Report r = results.get(i);
                    String line = "<white>#"+r.id+"</white> <gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> <white>"+r.reported+"</white>"
                            + colorStackBadge(r.count)
                            + (r.assignee != null ? " <gray>[</gray><white>"+r.assignee+"</white><gray>]</gray>" : "")
                            + " <gray>[</gray><aqua><hover:show_text:'"+Text.escape(expandTip)+"'><click:run_command:'/reports view "+r.id+"'>expand</click></hover></aqua><gray>]</gray>";
                    Text.msg(src, line);
                    shown++;
                }
                if (results.size() > shown) Text.msg(src, "<gray>…and "+(results.size()-shown)+" more.</gray>");
            }

            case "reload" -> {
                plugin.reload();
                Text.msg(src, config.msg("reloaded","ReportSystem reloaded."));
            }

            /* ---------- NEW SUBCOMMANDS ---------- */

            case "auth" -> {
                if (!(src instanceof Player p)) {
                    Text.msg(src, "<red>Only players can request a login code.</red>");
                    return;
                }
                var code = auth.issueCodeFor(p);

                // Pick the best base and join safely to avoid /login/login
                String base = pickBaseUrl(config);
                String link = joinUrl(base, "/login");

                Text.msg(src,
                        "<gray>Your one-time code:</gray> <white><bold>" + code.code + "</bold></white> " +
                                "<gray>(expires in " + config.msg("auth-code-ttl-s", "120") + "s)</gray>");
                String tip = config.msg("tip-open-login","Open login page");
                Text.msg(src,
                        "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:open_url:'" + link + "'>Open login</click></hover></aqua><gray>]</gray>");
            }

            case "logoutall" -> {
                if (!(src instanceof Player p)) {
                    Text.msg(src, "<red>Only players can logout their sessions.</red>");
                    return;
                }
                int n = auth.revokeAllFor(p.getUniqueId());
                Text.msg(src, "<gray>Revoked <white>" + n + "</white> web session(s).</gray>");
            }

            /* ---------- QUICK ACTIONS (not in help) ---------- */

            case "assigntome" -> {
                // /reports assigntome <id>
                if (!(src instanceof Player p)) { Text.msg(src, "<red>Players only.</red>"); return; }
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports assigntome <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                mgr.assign(id, p.getUsername());
                Text.msg(src, "<gray>Assigned report <white>#"+id+"</white> to you.</gray>");
            }

            case "unassignme" -> {
                // /reports unassignme <id>
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports unassignme <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                if (r.assignee == null || r.assignee.isBlank()) {
                    Text.msg(src, config.msg("already-unassigned","Report #%id% is not assigned.").replace("%id%", String.valueOf(id)));
                    return;
                }
                mgr.unassign(id);
                Text.msg(src, "<gray>Unassigned report <white>#"+id+"</white>.</gray>");
            }

            default -> showPage(src, 1);
        }
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length == 0) {
            return List.of("page", "view", "close", "chat", "assign", "unassign", "search", "reload", "auth", "logoutall");
        }
        switch (a[0].toLowerCase()) {
            case "page" -> {
                if (a.length == 1) return List.of("1", "2", "3");
            }
            case "view", "close", "chat", "unassign" -> {
                var ids = mgr.getOpenReportsDescending().stream().map(r -> String.valueOf(r.id)).toList();
                if (a.length == 1) return ids;
            }
            case "assign" -> {
                if (a.length == 1) {
                    return mgr.getOpenReportsDescending().stream().map(r -> String.valueOf(r.id)).toList();
                } else if (a.length == 2) {
                    return mgr.getOpenReportsDescending().stream().map(r -> String.valueOf(r.id)).toList();
                } else if (a.length == 3) {
                    return plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList();
                }
            }
            case "search" -> {
                if (a.length == 1) return List.of("<query>");
                if (a.length == 2) return List.of("open", "closed", "all");
            }
            case "auth" -> {
                return List.of(); // no args
            }
            case "logoutall" -> {
                return List.of(); // no args
            }
        }
        return List.of();
    }

    /* ---------------------------------------------------
       Helpers
       --------------------------------------------------- */

    private void showPage(CommandSource src, int page) {
        List<Report> open = mgr.getOpenReportsDescending();
        if (open.isEmpty()) { Text.msg(src, config.msg("page-empty","No open reports.")); return; }

        int per = Math.max(1, config.reportsPerPage);
        int pages = Math.max(1, (int) Math.ceil(open.size() / (double) per));
        page = Math.min(Math.max(1, page), pages);

        Text.msg(src, config.msg("page-header","Reports Page %page%/%pages%")
                .replace("%page%", String.valueOf(page))
                .replace("%pages%", String.valueOf(pages)));

        String expandTip = config.msg("tip-expand", "Click to expand");
        for (Report r : Pagination.paginate(open, per, page)) {
            String assignee = r.assignee != null ? " <gray>[</gray><white>"+r.assignee+"</white><gray>]</gray>" : "";
            String line = "<white>#"+r.id+"</white> "
                    + "<gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> "
                    + "<white>"+r.reported+"</white>"
                    + colorStackBadge(r.count)
                    + assignee
                    + "  <gray>[</gray><aqua><hover:show_text:'"+Text.escape(expandTip)+"'><click:run_command:'/reports view "+r.id+"'>expand</click></hover></aqua><gray>]</gray>";
            Text.msg(src, line);
        }

        String prevTip = config.msg("tip-prev", "Previous page");
        String nextTip = config.msg("tip-next", "Next page");
        String reloadTip = config.msg("tip-reload", "Reload reports");
        Component nav = Text.mm(
                "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(prevTip)+"'><click:run_command:'/reports page "+Math.max(1, page-1)+"'>« Prev</click></hover></aqua><gray>] " +
                "[</gray><aqua><hover:show_text:'"+Text.escape(nextTip)+"'><click:run_command:'/reports page "+Math.min(pages, page+1)+"'>Next »</click></hover></aqua><gray>] · " +
                "[</gray><aqua><hover:show_text:'"+Text.escape(reloadTip)+"'><click:run_command:'/reports reload'>Reload</click></hover></aqua><gray>]</gray>"
        );
        src.sendMessage(nav);
    }

    /** Expanded view for a single report. Includes server + Jump button + quick assign actions. */
    private void expand(CommandSource src, Report r) {
        String header = config.msg("expanded-header","Report #%id% (%type% / %category%)")
                .replace("%id%", String.valueOf(r.id))
                .replace("%type%", r.typeDisplay)
                .replace("%category%", r.categoryDisplay);
        Text.msg(src, header);

        // Infer server from latest chat message (if any)
        String serverName = inferServer(r);
        String serverLine = config.msg("expanded-server-line", "<gray>Server:</gray> <white>%server%</white>")
                .replace("%server%", serverName);
        Text.msg(src, serverLine);

        // Standard detail lines
        var lines = config.msgList("expanded-lines", List.of(
                "<gray>Reported:</gray> <white>%target%</white> <gray>by</gray> <white>%player%</white>",
                "<gray>When:</gray> <white>%timestamp%</white>",
                "<gray>Count:</gray> <white>%count%</white>",
                "<gray>Reason(s):</gray> <white>%reasons%</white>",
                "<gray>Status:</gray> <white>%status%</white>",
                "<gray>Assignee:</gray> <white>%assignee%</white>"
        ));
        String ts = TimeUtil.formatDateTime(r.timestamp);
        String assignee = (r.assignee == null || r.assignee.isBlank()) ? "—" : r.assignee;
        String reasons = (r.reason == null || r.reason.isBlank()) ? "—" : r.reason;

        for (String tmpl : lines) {
            String out = tmpl
                    .replace("%id%", String.valueOf(r.id))
                    .replace("%player%", r.reporter == null ? "UNKNOWN" : r.reporter)
                    .replace("%target%", r.reported == null ? "UNKNOWN" : r.reported)
                    .replace("%timestamp%", ts)
                    .replace("%count%", String.valueOf(r.count))
                    .replace("%reasons%", reasons)
                    .replace("%status%", r.status == null ? "OPEN" : r.status.name())
                    .replace("%assignee%", assignee)
                    .replace("%server%", serverName);
            Text.msg(src, out);
        }

        // Actions
        String tipClose = config.msg("tip-close", "Close this report");
        String tipChat  = config.msg("tip-chat", "View chat logs");
        String tipJump  = config.msg("tip-jump-server", "Connect to this server");
        String tipAssignMe = config.msg("tip-assign-me", "Assign to me");
        String tipUnassign = config.msg("tip-unassign", "Unassign");

        String jumpCmdTemplate = config.msg("jump-command-template", "/server %server%");
        String jumpCmd = jumpCmdTemplate.replace("%server%", serverName);

        StringBuilder actions = new StringBuilder();
        actions.append("<gray>[</gray><green><hover:show_text:'").append(Text.escape(tipClose))
                .append("'><click:run_command:'/reports close ").append(r.id).append("'>Close</click></hover></green><gray>]</gray> ");
        actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipChat))
                .append("'><click:run_command:'/reports chat ").append(r.id).append("'>Chat Logs</click></hover></aqua><gray>]</gray> ");

        if (!"UNKNOWN".equalsIgnoreCase(serverName)) {
            actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipJump))
                    .append("'><click:run_command:'").append(Text.escape(jumpCmd))
                    .append("'>Jump to server</click></hover></aqua><gray>]</gray> ");
        }

        // Quick assign controls
        if (src instanceof Player) {
            actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipAssignMe))
                    .append("'><click:run_command:'/reports assigntome ").append(r.id)
                    .append("'>Assign to me</click></hover></aqua><gray>]</gray> ");
            if (r.assignee != null && !r.assignee.isBlank()) {
                actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipUnassign))
                        .append("'><click:run_command:'/reports unassignme ").append(r.id)
                        .append("'>Unassign</click></hover></aqua><gray>]</gray>");
            }
        }

        Text.msg(src, actions.toString());
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

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    /** Build a public URL to the exported HTML page if configured (cleans duplicate slashes). */
    private String buildPublicLinkFor(Report r) {
        String base = pickBaseUrl(config);
        if (base.isBlank()) return null;
        // Ensure exactly one slash between base and /<id>/index.html
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

    /** Join base + path without doubling “/” or “/login”. */
    private static String joinUrl(String base, String path) {
        if (base == null || base.isBlank()) {
            return (path == null || path.isBlank()) ? "/" : (path.startsWith("/") ? path : "/" + path);
        }
        String p = (path == null) ? "" : path.trim();
        if (p.isEmpty() || p.equals("/")) return base;
        // If base already ends with /login and path is /login, don't append again
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/login") && ("/login".equalsIgnoreCase(p) || "login".equalsIgnoreCase(p))) {
            return base;
        }
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
}
