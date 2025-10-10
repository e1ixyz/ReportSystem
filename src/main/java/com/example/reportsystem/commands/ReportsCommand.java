package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.platform.CommandActor;
import com.example.reportsystem.platform.CommandContext;
import com.example.reportsystem.platform.CommandHandler;
import com.example.reportsystem.platform.PlatformPlayer;
import com.example.reportsystem.service.AuthService;
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
 * Staff /reports command with:
 * - /reports claim (highest priority or /reports claim <id>)
 * - /reports claimed (my claimed reports)
 * - /reports <type> [category] filtering
 * - Admin-only: reload, logoutall, force-claim checks via permission
 * - Configurable EXPAND label (messages.yml: label-expand) + tip-expand hover everywhere
 * - Chat logs view:
 *      * if http-server.enabled -> export & show link only
 *      * if http-server.disabled -> paginated inline chat: /reports chat <id> [page]
 */
public class ReportsCommand implements CommandHandler {

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
        PlatformPlayer playerActor = src.asPlayer().orElse(null);
        if (args.length == 0) { showPage(src, 1, null, null); return; }

        // Allow `/reports <type> [category]` for quick filtering
        String maybeType = args[0];
        if (isKnownType(maybeType)) {
            String typeFilter = maybeType;
            String categoryFilter = null;
            if (args.length >= 2) {
                String maybeCat = args[1];
                if (isKnownCategoryFor(typeFilter, maybeCat)) {
                    categoryFilter = maybeCat;
                } else {
                    Text.msg(src, "<red>Unknown category for type '"+typeFilter+"': "+maybeCat+"</red>");
                    return;
                }
            }
            showPage(src, 1, typeFilter, categoryFilter);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "page" -> {
                int page = 1;
                if (args.length >= 2) { try { page = Math.max(1, Integer.parseInt(args[1])); } catch (Exception ignored) {} }
                showPage(src, page, null, null);
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

            case "claim" -> {
                if (playerActor == null) { Text.msg(src, "<red>Players only.</red>"); return; }

                if (args.length >= 2) {
                    long id = parseLong(args[1], -1);
                    Report r = mgr.get(id);
                    if (r == null || !r.isOpen()) { Text.msg(src, "<red>No such open report.</red>"); return; }

                    if (r.assignee != null && !r.assignee.isBlank() && !playerActor.username().equalsIgnoreCase(r.assignee)) {
                        if (!src.hasPermission(config.forceClaimPermission) && !src.hasPermission(config.adminPermission)) {
                            Text.msg(src, "<red>Already claimed by "+r.assignee+" (need force-claim).</red>");
                            return;
                        }
                    }
                    mgr.assign(r.id, playerActor.username());
                    Text.msg(src, "<gray>You claimed report <white>#"+r.id+"</white>.</gray>");
                } else {
                    var open = mgr.getOpenReportsDescending();
                    if (open.isEmpty()) { Text.msg(src, "<gray>No open reports.</gray>"); return; }
                    Report r = open.get(0);
                    if (r.assignee != null && !r.assignee.isBlank() && !playerActor.username().equalsIgnoreCase(r.assignee)) {
                        if (!src.hasPermission(config.forceClaimPermission) && !src.hasPermission(config.adminPermission)) {
                            Text.msg(src, "<red>Top report already claimed by "+r.assignee+" (need force-claim).</red>");
                            return;
                        }
                    }
                    mgr.assign(r.id, playerActor.username());
                    Text.msg(src, "<gray>You claimed report <white>#"+r.id+"</white>.</gray>");
                }
            }

            case "claimed" -> {
                if (playerActor == null) { Text.msg(src, "<red>Players only.</red>"); return; }
                var mine = mgr.getOpenReportsDescending().stream()
                        .filter(r -> r.assignee != null && r.assignee.equalsIgnoreCase(playerActor.username()))
                        .toList();
                if (mine.isEmpty()) {
                    Text.msg(src, "<gray>You have no claimed reports.</gray>");
                } else {
                    Text.msg(src, "<gray>Your claimed reports:</gray>");
                    String tip = expandTip();
                    for (Report r : mine) {
                        Text.msg(src, fmtListLine(r) + "  <gray>[</gray><aqua><hover:show_text:'" + Text.escape(tip) + "'><click:run_command:'/reports view " + r.id + "'>" + expandLabel() + "</click></hover></aqua><gray>]</gray>");
                    }
                }
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
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports chat <id> [page]"); return; }
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

                boolean webEnabled = config.httpServer != null && config.httpServer.enabled;
                if (webEnabled) {
                    try {
                        // Ensure export exists (do not show local path)
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
                    // Paginated inline echo
                    int page = 1;
                    if (args.length >= 3) {
                        try { page = Math.max(1, Integer.parseInt(args[2])); } catch (Exception ignored) {}
                    }
                    showChatPage(src, r, page);
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

                if (r.assignee != null && !r.assignee.isBlank() && !staff.equalsIgnoreCase(r.assignee)) {
                    if (!src.hasPermission(config.forceClaimPermission) && !src.hasPermission(config.adminPermission)) {
                        Text.msg(src, "<red>Already claimed by "+r.assignee+" (need force-claim).</red>");
                        return;
                    }
                }

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
                String tip = expandTip();
                for (int i=0;i<limit;i++) {
                    Report r = results.get(i);
                    String line = fmtListLine(r)
                            + "  <gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:run_command:'/reports view "+r.id+"'>"+expandLabel()+"</click></hover></aqua><gray>]</gray>";
                    Text.msg(src, line);
                    shown++;
                }
                if (results.size() > shown) Text.msg(src, "<gray>…and "+(results.size()-shown)+" more.</gray>");
            }

            case "debug" -> {
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports debug <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null || !r.isOpen()) {
                    Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                showPriorityBreakdown(src, r);
            }

            case "reload" -> {
                if (!src.hasPermission(config.adminPermission)) {
                    Text.msg(src, "<red>Admin permission required.</red>");
                    return;
                }
                boolean broadcasted = false;
                if (playerActor != null) {
                    String notifyPerm = (config.notifyPermission == null) ? "" : config.notifyPermission.trim();
                    if (!notifyPerm.isBlank()) {
                        broadcasted = playerActor.hasPermission(notifyPerm);
                    }
                }
                plugin.reload();
                if (!broadcasted) {
                    Text.msg(src, config.msg("reloaded","ReportSystem reloaded."));
                }
            }

            case "auth" -> {
                if (playerActor == null) {
                    Text.msg(src, "<red>Only players can request a login code.</red>");
                    return;
                }
                var code = auth.issueCodeFor(playerActor);
                if (code == null) {
                    Text.msg(src, "<red>Unable to generate an auth code. Do you have permission?</red>");
                    return;
                }
                Text.msg(src,
                        "<gray>Your one-time code:</gray> <white><bold>" + code.code + "</bold></white> " +
                                "<gray>(expires in " + config.msg("auth-code-ttl-s", "120") + "s)</gray>");
                String base = pickBaseUrl(config);
                if (!base.isBlank()) {
                    String tip = config.msg("tip-open-login","Open login page");
                    String link = joinUrl(base, "/login");
                    Text.msg(src,
                            "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:open_url:'" + Text.escape(link) + "'>Open login</click></hover></aqua><gray>]</gray>");
                } else {
                    Text.msg(src, config.msg("auth-no-base-url", "<red>No public URL configured. Set public-base-url or http-server.external-base-url.</red>"));
                }
            }

            case "logoutall" -> {
                if (!src.hasPermission(config.adminPermission)) {
                    Text.msg(src, "<red>Admin permission required.</red>");
                    return;
                }
                if (playerActor == null) {
                    Text.msg(src, "<red>Only players can logout their sessions.</red>");
                    return;
                }
                int n = auth.revokeAllFor(playerActor.uniqueId());
                Text.msg(src, "<gray>Revoked <white>" + n + "</white> web session(s).</gray>");
            }

            case "assigntome" -> {
                if (playerActor == null) { Text.msg(src, "<red>Players only.</red>"); return; }
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports assigntome <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }

                if (r.assignee != null && !r.assignee.isBlank() && !playerActor.username().equalsIgnoreCase(r.assignee)) {
                    if (!src.hasPermission(config.forceClaimPermission) && !src.hasPermission(config.adminPermission)) {
                        Text.msg(src, "<red>Already claimed by "+r.assignee+" (need force-claim).</red>");
                        return;
                    }
                }
                mgr.assign(id, playerActor.username());
                Text.msg(src, "<gray>Assigned report <white>#"+id+"</white> to you.</gray>");
            }

            case "unassignme" -> {
                if (playerActor == null) { Text.msg(src, "<red>Players only.</red>"); return; }
                if (args.length < 2) { Text.msg(src, "<yellow>Usage:</yellow> /reports unassignme <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { Text.msg(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                if (r.assignee == null || r.assignee.isBlank()) {
                    Text.msg(src, config.msg("already-unassigned","Report #%id% is not assigned.").replace("%id%", String.valueOf(id)));
                    return;
                }
                if (!playerActor.username().equalsIgnoreCase(r.assignee)) {
                    Text.msg(src, "<red>You are not the assignee for #"+id+".</red>");
                    return;
                }
                mgr.unassign(id);
                Text.msg(src, "<gray>Unassigned report <white>#"+id+"</white>.</gray>");
            }

            default -> showPage(src, 1, null, null);
        }
    }

    @Override
    public List<String> suggest(CommandContext ctx) {
        String[] a = ctx.args();
        if (a.length == 0) {
            return List.of("page", "view", "claim", "claimed", "close", "chat", "assign", "unassign", "search", "debug", "reload", "auth", "logoutall");
        }
        switch (a[0].toLowerCase()) {
            case "page" -> {
                if (a.length == 1) return List.of("1", "2", "3");
            }
            case "view", "close", "chat" -> {
                var ids = idSuggestions(false);
                if (a.length <= 1) return ids;
                if (a.length >= 2) {
                    List<String> filtered = filter(ids, a[1]);
                    if (a[0].equalsIgnoreCase("chat") && a.length == 3) {
                        return filter(List.of("<page>", "1", "2", "3"), a[2]);
                    }
                    return filtered;
                }
            }
            case "unassign" -> {
                var ids = idSuggestions(true);
                if (a.length <= 1) return ids;
                if (a.length >= 2) return filter(ids, a[1]);
            }
            case "assign" -> {
                var ids = idSuggestions(false);
                if (a.length <= 1) return ids;
                if (a.length == 2) return filter(ids, a[1]);
                if (a.length == 3) {
                    var names = plugin.platform().onlinePlayers().stream().map(PlatformPlayer::username).toList();
                    return filter(List.copyOf(names), a[2]);
                }
            }
            case "debug" -> {
                var ids = idSuggestions(false);
                if (a.length <= 1) return ids;
                if (a.length >= 2) return filter(ids, a[1]);
            }
            case "search" -> {
                if (a.length == 1) {
                    return searchQuerySuggestions("");
                }
                if (a.length == 2) {
                    return searchQuerySuggestions(a[1]);
                }
                if (a.length == 3) {
                    return filter(List.of("open", "closed", "all"), a[2]);
                }
            }
            case "claim" -> {
                var ids = idSuggestions(false);
                if (a.length <= 1) return ids;
                if (a.length >= 2) return filter(ids, a[1]);
            }
            case "auth", "logoutall", "claimed" -> { return List.of(); }
            case "assigntome" -> {
                var ids = idSuggestions(false);
                if (a.length <= 1) return ids;
                if (a.length >= 2) return filter(ids, a[1]);
            }
            case "unassignme" -> {
                var ids = idSuggestions(true);
                if (a.length <= 1) return ids;
                if (a.length >= 2) return filter(ids, a[1]);
            }
            default -> {
                // fall back to type/category suggestions
                if (a.length == 1) return mgr.typeIds();
                if (a.length == 2) return mgr.categoryIdsFor(a[0]);
            }
        }
        return List.of();
    }

    /* ---------------------------------------------------
       Helpers
       --------------------------------------------------- */

    private boolean isKnownType(String typeId) {
        if (typeId == null) return false;
        return mgr.typeIds().stream().anyMatch(t -> t.equalsIgnoreCase(typeId));
    }

    private boolean isKnownCategoryFor(String typeId, String catId) {
        if (typeId == null || catId == null) return false;
        return mgr.categoryIdsFor(typeId).stream().anyMatch(c -> c.equalsIgnoreCase(catId));
    }

    private void showPage(CommandActor src, int page) { showPage(src, page, null, null); }

    /** Optional filtering: by type and category. */
    private void showPage(CommandActor src, int page, String typeFilter, String categoryFilter) {
        List<Report> open = mgr.getOpenReportsDescending();
        if (typeFilter != null) {
            open = open.stream().filter(r -> r.typeId.equalsIgnoreCase(typeFilter)).toList();
            if (categoryFilter != null) {
                open = open.stream().filter(r -> r.categoryId.equalsIgnoreCase(categoryFilter)).toList();
            }
        }
        if (open.isEmpty()) { Text.msg(src, config.msg("page-empty","No open reports.")); return; }

        int per = Math.max(1, config.reportsPerPage);
        int pages = Math.max(1, (int) Math.ceil(open.size() / (double) per));
        page = Math.min(Math.max(1, page), pages);

        String header = (typeFilter == null)
                ? config.msg("page-header","Reports Page %page%/%pages%")
                : config.msg("page-header-filtered","Reports (%type%/%cat%) Page %page%/%pages%");
        header = header.replace("%type%", typeFilter == null ? "ALL" : typeFilter)
                       .replace("%cat%", categoryFilter == null ? "*" : categoryFilter)
                       .replace("%page%", String.valueOf(page))
                       .replace("%pages%", String.valueOf(pages));
        Text.msg(src, header);

        String tip = expandTip();
        for (Report r : Pagination.paginate(open, per, page)) {
            String line = fmtListLine(r)
                    + "  <gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:run_command:'/reports view "+r.id+"'>"+expandLabel()+"</click></hover></aqua><gray>]</gray>";
            Text.msg(src, line);
        }

        String prevTip = config.msg("tip-prev", "Previous page");
        String nextTip = config.msg("tip-next", "Next page");
        Component nav = Text.mm(
                "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(prevTip)+"'><click:run_command:'/reports page "+Math.max(1, page-1)+"'>« Prev</click></hover></aqua><gray>] " +
                "[</gray><aqua><hover:show_text:'"+Text.escape(nextTip)+"'><click:run_command:'/reports page "+Math.min(pages, page+1)+"'>Next »</click></hover></aqua><gray>]</gray>"
        );
        src.sendMessage(nav);
    }

    /** One-line list format: (type/category) Target [Assigned] [Server] + hover tips. */
    private String fmtListLine(Report r) {
        String target = (r.reported == null || r.reported.isBlank()) ? "UNKNOWN" : r.reported;
        String assignee = (r.assignee == null || r.assignee.isBlank()) ? "—" : r.assignee;
        String server = deriveServer(r);
        if (server == null || server.isBlank()) server = "UNKNOWN";

        String tipTarget = config.msg("tip-target", "Target: %name%").replace("%name%", target);
        String tipAssigned = config.msg("tip-assigned", "Assigned: %name%").replace("%name%", "—".equals(assignee) ? "None" : assignee);
        String tipServer = config.msg("tip-server", "Server: %name%").replace("%name%", server);

        String targetSeg =
                "<hover:show_text:'"+ Text.escape(tipTarget) +"'><white>"+ Text.escape(target) +"</white></hover>";
        String assignedSeg =
                "<hover:show_text:'"+ Text.escape(tipAssigned) +"'><gray>[</gray><white>"+ Text.escape(assignee) +"</white><gray>]</gray></hover>";
        String serverSeg =
                "<hover:show_text:'"+ Text.escape(tipServer) +"'><gray>[</gray><white>"+ Text.escape(server) +"</white><gray>]</gray></hover>";

        return "<white>#"+r.id+"</white> "
                + "<gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> "
                + targetSeg + " " + assignedSeg + " " + serverSeg
                + colorStackBadge(r.count);
    }

    /** Prefer target's current server, then sourceServer, then newest chat server. */
    private String deriveServer(Report r) {
        // 1) target online now?
        if (r.reported != null && !r.reported.isBlank()) {
            var opt = plugin.platform().findPlayer(r.reported);
            if (opt.isPresent()) {
                var sv = opt.get().currentServerName().orElse(null);
                if (sv != null && !sv.isBlank()) return sv;
            }
        }
        // 2) source server (where the report was filed from)
        if (r.sourceServer != null && !r.sourceServer.isBlank()) return r.sourceServer;

        // 3) newest chat line's server
        if (r.chat != null && !r.chat.isEmpty()) {
            return r.chat.stream()
                    .max(Comparator.comparingLong(cm -> cm.time))
                    .map(cm -> (cm.server == null || cm.server.isBlank()) ? null : cm.server)
                    .orElse(null);
        }
        return null;
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

    /** Expanded view for a single report (used by "view"). */
    private void expand(CommandActor src, Report r) {
        String header = config.msg("expanded-header","Report #%id% (%type% / %category%)")
                .replace("%id%", String.valueOf(r.id))
                .replace("%type%", r.typeDisplay)
                .replace("%category%", r.categoryDisplay);
        Text.msg(src, header);

        String serverName = deriveServer(r);
        if (serverName == null || serverName.isBlank()) serverName = "UNKNOWN";
        String serverLine = config.msg("expanded-server-line", "<gray>Server:</gray> <white>%server%</white>")
                .replace("%server%", serverName);
        Text.msg(src, serverLine);

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

        String tipClose = config.msg("tip-close", "Close this report");
        String tipChat  = config.msg("tip-chat", "View chat logs");
        String tipJump  = config.msg("tip-jump-server", "Connect to this server");

        StringBuilder actions = new StringBuilder();
        actions.append("<gray>[</gray><green><hover:show_text:'").append(Text.escape(tipClose))
                .append("'><click:run_command:'/reports close ").append(r.id).append("'>Close</click></hover></green><gray>]</gray> ");
        actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipChat))
                .append("'><click:run_command:'/reports chat ").append(r.id).append("'>Chat Logs</click></hover></aqua><gray>]</gray> ");

        // Only show Jump if the server actually exists on the proxy
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

        // Quick-assign buttons
        var playerOpt = src.asPlayer();
        if (playerOpt.isPresent()) {
            PlatformPlayer p = playerOpt.get();
            boolean assigned = r.assignee != null && !r.assignee.isBlank();
            boolean mine = assigned && p.username().equalsIgnoreCase(r.assignee);
            boolean canForce = p.hasPermission(config.forceClaimPermission)
                    || p.hasPermission(config.adminPermission);

            if (mine) {
                String tipUnassign = config.msg("tip-unassign", "Unassign");
                actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipUnassign))
                        .append("'><click:run_command:'/reports unassignme ").append(r.id)
                        .append("'>Unassign</click></hover></aqua><gray>]</gray>");
            } else {
                String tipAssignMe = config.msg("tip-assign-me", "Assign to me");
                String tipForce = config.msg("tip-force-claim", "Force-claim");
                String hover = assigned ? (canForce ? tipForce : tipAssignMe) : tipAssignMe;
                String label = assigned && canForce ? "Force-claim" : "Assign to me";
                actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(hover))
                        .append("'><click:run_command:'/reports assigntome ").append(r.id)
                        .append("'>").append(Text.escape(label)).append("</click></hover></aqua><gray>]</gray>");
            }
        }

        Text.msg(src, actions.toString());
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
                    "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(prevTip)+"'><click:run_command:'/reports chat "+r.id+" "+prev+"'>« Prev</click></hover></aqua><gray>] " +
                    "[</gray><aqua><hover:show_text:'"+Text.escape(nextTip)+"'><click:run_command:'/reports chat "+r.id+" "+next+"'>Next »</click></hover></aqua><gray>]</gray>"
            );
            src.sendMessage(nav);
        }
    }

    private List<String> idSuggestions(boolean assignedOnly) {
        List<String> ids = new ArrayList<>();
        ids.add("<id>");
        mgr.getOpenReportsDescending().stream()
                .filter(r -> !assignedOnly || (r.assignee != null && !r.assignee.isBlank()))
                .map(r -> String.valueOf(r.id))
                .forEach(ids::add);
        return ids;
    }

    private List<String> searchQuerySuggestions(String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();

        if ("<query>".startsWith(p) || p.isEmpty()) {
            suggestions.add("<query>");
        }

        for (String typeId : mgr.typeIds()) {
            String lowerType = typeId.toLowerCase(Locale.ROOT);
            if (lowerType.startsWith(p)) {
                suggestions.add(typeId);
            }
            for (String cat : mgr.categoryIdsFor(typeId)) {
                String combo = typeId + "/" + cat;
                if (combo.toLowerCase(Locale.ROOT).startsWith(p)) {
                    suggestions.add(combo);
                }
            }
        }

        return suggestions;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(opt -> opt.toLowerCase(Locale.ROOT).startsWith(p))
                .toList();
    }

    private void showPriorityBreakdown(CommandActor src, Report r) {
        var breakdown = mgr.debugPriority(r);
        if (!breakdown.enabled) {
            Text.msg(src, "<gray>Priority scoring is disabled; ordering falls back to <white>" + Text.escape(breakdown.tieBreaker) + "</white>.</gray>");
            return;
        }

        Text.msg(src, "<gray>Priority for <white>#" + r.id + "</white>: <green>" + fmt(breakdown.total) + "</green></gray>");
        if (breakdown.components.isEmpty()) {
            Text.msg(src, "<gray>No contributing factors (all weights zero or disabled).</gray>");
        } else {
            for (var comp : breakdown.components) {
                String line = "<gray>- " + Text.escape(comp.name) + ":</gray> weight <white>" + fmt(comp.weight)
                        + "</white> × value <white>" + fmt(comp.value) + "</white> = <white>" + fmt(comp.contribution)
                        + "</white> <gray>(" + Text.escape(comp.reason) + ")</gray>";
                Text.msg(src, line);
            }
        }
        Text.msg(src, "<gray>Tie-breaker after priority: <white>" + Text.escape(breakdown.tieBreaker) + "</white>.</gray>");
    }

    /** Configurable label for EXPAND button; defaults to "EXPAND". */
    private String expandLabel() {
        String s = config.msg("label-expand", "EXPAND"); // unified key
        return (s == null || s.isBlank()) ? "EXPAND" : s;
    }
    /** Hover tooltip for the expand button. */
    private String expandTip() {
        String s = config.msg("tip-expand", "Expand");
        return (s == null || s.isBlank()) ? "Expand" : s;
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
