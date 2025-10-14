package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.service.AuthService;
import com.example.reportsystem.service.HtmlExporter;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Pagination;
import com.example.reportsystem.util.Text;
import com.example.reportsystem.util.QuickActions;
import com.example.reportsystem.util.TimeUtil;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
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
public class ReportsCommand implements SimpleCommand {

    private static final List<String> ROOT_SUBCOMMANDS = List.of("page", "view", "claim", "claimed", "close",
            "chat", "assign", "unassign", "search", "debug", "reload", "auth", "logoutall",
            "assigntome", "unassignme");

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
    public void execute(Invocation inv) {
        CommandSource src = inv.source();
        if (!src.hasPermission(config.staffPermission)) {
            reply(src, config.msg("no-permission","You don't have permission."));
            return;
        }

        String[] args = inv.arguments();
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
                    String template = msg("reports-unknown-category",
                            "<red>Unknown category for type '%type%': %category%</red>");
                    reply(src, template
                            .replace("%type%", typeFilter)
                            .replace("%category%", maybeCat));
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
                if (args.length < 2) { send(src, "usage-reports-view", "<yellow>Usage:</yellow> /reports view <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null || !r.isOpen()) {
                    reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                expand(src, r);
            }

            case "claim" -> {
                if (!(src instanceof Player p)) { send(src, "error-players-only", "<red>Players only.</red>"); return; }

                if (args.length >= 2) {
                    long id = parseLong(args[1], -1);
                    Report r = mgr.get(id);
                    if (r == null || !r.isOpen()) { send(src, "reports-no-open", "<red>No such open report.</red>"); return; }

                    if (r.assignee != null && !r.assignee.isBlank() && !p.getUsername().equalsIgnoreCase(r.assignee)) {
                        if (!src.hasPermission(config.forceClaimPermission) && !src.hasPermission(config.adminPermission)) {
                            String msgForce = msg("reports-force-claim-required", "<red>Already claimed by %assignee% (need force-claim).</red>")
                                    .replace("%assignee%", Text.escape(r.assignee));
                            reply(src, msgForce);
                            return;
                        }
                    }
                    mgr.assign(r.id, p.getUsername());
                    String claim = msg("reports-claim-success", "<gray>You claimed report <white>#%id%</white>.</gray>")
                            .replace("%id%", String.valueOf(r.id));
                    reply(src, withExpand(r, claim));
                } else {
                    var open = mgr.getOpenReportsDescending();
                    if (open.isEmpty()) { reply(src, config.msg("claim-none", "<gray>No claimable reports available.</gray>")); return; }

                    boolean canForce = src.hasPermission(config.forceClaimPermission) || src.hasPermission(config.adminPermission);
                    Report target = null;
                    Report forceCandidate = null;

                    for (Report r : open) {
                        boolean hasAssignee = r.assignee != null && !r.assignee.isBlank();
                        if (!hasAssignee) {
                            target = r;
                            break;
                        }
                        if (p.getUsername().equalsIgnoreCase(r.assignee)) {
                            continue; // already owned by caller; look for next available
                        }
                        if (canForce && forceCandidate == null) {
                            forceCandidate = r;
                        }
                    }

                    if (target == null) {
                        if (forceCandidate != null) {
                            mgr.assign(forceCandidate.id, p.getUsername());
                            String forced = config.msg("force-claimed", "<gray>You force-claimed report <white>#%id%</white>.</gray>")
                                    .replace("%id%", String.valueOf(forceCandidate.id));
                            reply(src, withExpand(forceCandidate, forced));
                        } else {
                            reply(src, config.msg("claim-none", "<gray>No claimable reports available.</gray>"));
                        }
                        return;
                    }

                    mgr.assign(target.id, p.getUsername());
                    String claimMsg = msg("reports-claim-success", "<gray>You claimed report <white>#%id%</white>.</gray>")
                            .replace("%id%", String.valueOf(target.id));
                    reply(src, withExpand(target, claimMsg));
                }
            }

            case "claimed" -> {
                if (!(src instanceof Player p)) { send(src, "error-players-only", "<red>Players only.</red>"); return; }
                var mine = mgr.getOpenReportsDescending().stream()
                        .filter(r -> r.assignee != null && r.assignee.equalsIgnoreCase(p.getUsername()))
                        .toList();
                if (mine.isEmpty()) {
                    send(src, "reports-claimed-empty", "<gray>You have no claimed reports.</gray>");
                } else {
                    reply(src, msg("reports-claimed-header", "<gray>Your claimed reports:</gray>"));
                    String tip = expandTip();
                    String entryTemplate = msg("reports-claimed-entry",
                            "%row%  <gray>[</gray><aqua><hover:show_text:'%expand_tip%'><click:run_command:'/reports view %id%'>%expand_label%</click></hover></aqua><gray>]</gray>");
                    for (Report r : mine) {
                        String entry = entryTemplate
                                .replace("%row%", fmtListLine(r))
                                .replace("%id%", String.valueOf(r.id))
                                .replace("%expand_tip%", Text.escape(tip))
                                .replace("%expand_label%", Text.escape(expandLabel()));
                        reply(src, entry);
                    }
                }
            }

            case "close" -> {
                if (args.length < 2) { send(src, "usage-reports-close", "<yellow>Usage:</yellow> /reports close <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                mgr.close(id); mgr.save();
                reply(src, config.msg("closed","Closed report #%id%").replace("%id%", String.valueOf(id)));
                try {
                    Object n = plugin.notifier();
                    if (n != null) n.getClass().getMethod("notifyClosed", Report.class).invoke(n, r);
                } catch (Throwable ignored) {}
            }

            case "chat" -> {
                if (args.length < 2) { send(src, "usage-reports-chat", "<yellow>Usage:</yellow> /reports chat <id> [page]"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                if (r.chat == null || r.chat.isEmpty()) {
                    reply(src, config.msg("chatlog-none","No chat messages were captured for this report."));
                    return;
                }

                boolean webEnabled = config.httpServer != null && config.httpServer.enabled;
                if (webEnabled) {
                    try {
                        // Ensure export exists (do not show local path)
                        new HtmlExporter(plugin, config).export(r);
                        String link = buildPublicLinkFor(r);
                        if (link == null || link.isBlank()) {
                            send(src, "reports-chatlog-misconfigured", "<red>Web viewer is enabled but external/public base URL is not configured.</red>");
                            return;
                        }
                        String tip = config.msg("tip-open-browser", "Open in browser");
                        reply(src, "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:open_url:'" + link + "'>"+config.msg("open-chatlog-label","Open chat log")+"</click></hover></aqua><gray>]</gray>");
                    } catch (Exception ex) {
                        String template = msg("reports-chatlog-export-failed", "<red>Failed to export HTML chat log:</red> <gray>%error%</gray>");
                        reply(src, template.replace("%error%", Text.escape(ex.getMessage())));
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
                if (args.length < 3) { send(src, "usage-reports-assign", "<yellow>Usage:</yellow> /reports assign <id> <staff>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                String staff = args[2];

                if (r.assignee != null && !r.assignee.isBlank() && !staff.equalsIgnoreCase(r.assignee)) {
                    if (!src.hasPermission(config.forceClaimPermission) && !src.hasPermission(config.adminPermission)) {
                        reply(src, msg("reports-force-claim-required", "<red>Already claimed by %assignee% (need force-claim).</red>")
                                .replace("%assignee%", Text.escape(r.assignee)));
                        return;
                    }
                }

                mgr.assign(id, staff);
                String assigned = config.msg("assigned","Assigned report #%id% to %assignee%")
                        .replace("%id%", String.valueOf(id)).replace("%assignee%", staff);
                reply(src, withExpand(r, assigned));
                try {
                    Object n = plugin.notifier();
                    if (n != null) n.getClass().getMethod("notifyAssigned", Report.class, String.class).invoke(n, r, staff);
                } catch (Throwable ignored) {}
            }

            case "unassign" -> {
                if (args.length < 2) { send(src, "usage-reports-unassign", "<yellow>Usage:</yellow> /reports unassign <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) {
                    reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                if (r.assignee == null || r.assignee.isBlank()) {
                    reply(src, config.msg("already-unassigned","Report #%id% is not assigned.").replace("%id%", String.valueOf(id)));
                    return;
                }
                mgr.unassign(id);
                reply(src, config.msg("unassigned","Unassigned report #%id%").replace("%id%", String.valueOf(id)));
                try {
                    Object n = plugin.notifier();
                    if (n != null) n.getClass().getMethod("notifyUnassigned", Report.class).invoke(n, r);
                } catch (Throwable ignored) {}
            }

            case "search" -> {
                if (args.length < 2) { send(src, "usage-reports-search", "<yellow>Usage:</yellow> /reports search <query> [open|closed|all]</yellow>"); return; }
                String scope = args.length >= 3 ? args[2] : "open";
                String query = args[1];
                var results = mgr.search(query, scope);
                if (results.isEmpty()) { reply(src, config.msg("search-empty","No matching reports.")); return; }
                reply(src, config.msg("search-header","Search: %query% (%scope%)")
                        .replace("%query%", query).replace("%scope%", scope));
                int shown = 0, limit = Math.min(30, results.size());
                String tip = expandTip();
                String entryTemplate = msg("reports-list-entry",
                        "%row%  <gray>[</gray><aqua><hover:show_text:'%expand_tip%'><click:run_command:'/reports view %id%'>%expand_label%</click></hover></aqua><gray>]</gray>");
                String expandLabel = expandLabel();
                for (int i=0;i<limit;i++) {
                    Report r = results.get(i);
                    String entry = entryTemplate
                            .replace("%row%", fmtListLine(r))
                            .replace("%id%", String.valueOf(r.id))
                            .replace("%expand_tip%", Text.escape(tip))
                            .replace("%expand_label%", Text.escape(expandLabel));
                    reply(src, entry);
                    shown++;
                }
                if (results.size() > shown) {
                    String extra = msg("reports-search-more", "<gray>…and %count% more.</gray>")
                            .replace("%count%", String.valueOf(results.size() - shown));
                    reply(src, extra);
                }
            }

            case "debug" -> {
                if (args.length < 2) { send(src, "usage-reports-debug", "<yellow>Usage:</yellow> /reports debug <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null || !r.isOpen()) {
                    reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1]));
                    return;
                }
                showPriorityBreakdown(src, r);
            }

            case "reload" -> {
                if (!src.hasPermission(config.adminPermission)) {
                    send(src, "error-admin-permission", "<red>Admin permission required.</red>");
                    return;
                }
                boolean broadcasted = false;
                if (src instanceof Player p) {
                    String notifyPerm = (config.notifyPermission == null) ? "" : config.notifyPermission.trim();
                    if (!notifyPerm.isBlank()) {
                        broadcasted = p.hasPermission(notifyPerm);
                    }
                }
                plugin.reload();
                if (!broadcasted) {
                    reply(src, config.msg("reloaded","ReportSystem reloaded."));
                }
            }

            case "auth" -> {
                if (!(src instanceof Player p)) {
                    send(src, "error-players-only-auth", "<red>Only players can request a login code.</red>");
                    return;
                }
                var code = auth.issueCodeFor(p);
                if (code == null) {
                    send(src, "auth-code-failed", "<red>Unable to generate an auth code. Do you have permission?</red>");
                    return;
                }
                reply(src,
                        "<gray>Your one-time code:</gray> <white><bold>" + code.code + "</bold></white> " +
                                "<gray>(expires in " + config.msg("auth-code-ttl-s", "120") + "s)</gray>");
                String base = pickBaseUrl(config);
                if (!base.isBlank()) {
                    String tip = config.msg("tip-open-login","Open login page");
                    String link = joinUrl(base, "/login");
                    reply(src,
                            "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(tip)+"'><click:open_url:'" + Text.escape(link) + "'>Open login</click></hover></aqua><gray>]</gray>");
                } else {
                    reply(src, config.msg("auth-no-base-url", "<red>No public URL configured. Set public-base-url or http-server.external-base-url.</red>"));
                }
            }

            case "logoutall" -> {
                if (!src.hasPermission(config.adminPermission)) {
                    send(src, "error-admin-permission", "<red>Admin permission required.</red>");
                    return;
                }
                if (!(src instanceof Player p)) {
                    send(src, "error-players-only-logout", "<red>Only players can logout their sessions.</red>");
                    return;
                }
                int n = auth.revokeAllFor(p.getUniqueId());
                reply(src, msg("auth-logoutall-success", "<gray>Revoked <white>%count%</white> web session(s).</gray>")
                        .replace("%count%", String.valueOf(n)));
            }

            case "assigntome" -> {
                if (!(src instanceof Player p)) { send(src, "error-players-only", "<red>Players only.</red>"); return; }
                if (args.length < 2) { send(src, "usage-reports-assigntome", "<yellow>Usage:</yellow> /reports assigntome <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }

                if (r.assignee != null && !r.assignee.isBlank() && !p.getUsername().equalsIgnoreCase(r.assignee)) {
                    if (!src.hasPermission(config.forceClaimPermission) && !src.hasPermission(config.adminPermission)) {
                        String msg = msg("reports-force-claim-required", "<red>Already claimed by %assignee% (need force-claim).</red>")
                                .replace("%assignee%", Text.escape(r.assignee));
                        reply(src, msg);
                        return;
                    }
                }
                mgr.assign(id, p.getUsername());
                String selfAssign = msg("reports-assign-self", "<gray>Assigned report <white>#%id%</white> to you.</gray>")
                        .replace("%id%", String.valueOf(id));
                reply(src, withExpand(r, selfAssign));
            }

            case "unassignme" -> {
                if (!(src instanceof Player p)) { send(src, "error-players-only", "<red>Players only.</red>"); return; }
                if (args.length < 2) { send(src, "usage-reports-unassignme", "<yellow>Usage:</yellow> /reports unassignme <id>"); return; }
                long id = parseLong(args[1], -1);
                Report r = mgr.get(id);
                if (r == null) { reply(src, config.msg("not-found","No such report: #%id%").replace("%id%", args[1])); return; }
                if (r.assignee == null || r.assignee.isBlank()) {
                    reply(src, config.msg("already-unassigned","Report #%id% is not assigned.").replace("%id%", String.valueOf(id)));
                    return;
                }
                if (!p.getUsername().equalsIgnoreCase(r.assignee)) {
                    reply(src, msg("reports-unassign-not-owner", "<red>You are not the assignee for #%id%.</red>")
                            .replace("%id%", String.valueOf(id)));
                    return;
                }
                mgr.unassign(id);
                reply(src, msg("reports-unassign-self", "<gray>Unassigned report <white>#%id%</white>.</gray>")
                        .replace("%id%", String.valueOf(id)));
            }

            default -> showPage(src, 1, null, null);
        }
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length == 0) {
            return ROOT_SUBCOMMANDS;
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
                    var names = plugin.proxy().getAllPlayers().stream().map(Player::getUsername).toList();
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
                List<String> filteredCommands = filter(ROOT_SUBCOMMANDS, a[0]);
                if (!filteredCommands.isEmpty()) {
                    return filteredCommands;
                }
                if (a.length == 1) {
                    return filter(mgr.typeIds(), a[0]);
                }
                if (a.length == 2) {
                    return filter(mgr.categoryIdsFor(a[0]), a[1]);
                }
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

    private void showPage(CommandSource src, int requestedPage) { showPage(src, requestedPage, null, null); }

    /** Optional filtering: by type and category. */
    private void showPage(CommandSource src, int requestedPage, String typeFilter, String categoryFilter) {
        List<Report> open = mgr.getOpenReportsDescending();
        if (typeFilter != null) {
            open = open.stream().filter(r -> r.typeId.equalsIgnoreCase(typeFilter)).toList();
            if (categoryFilter != null) {
                open = open.stream().filter(r -> r.categoryId.equalsIgnoreCase(categoryFilter)).toList();
            }
        }
        if (open.isEmpty()) { reply(src, config.msg("page-empty","No open reports.")); return; }

        int per = Math.max(1, config.reportsPerPage);
        int pages = Math.max(1, (int) Math.ceil(open.size() / (double) per));
        int page = Math.min(Math.max(1, requestedPage), pages);
        boolean clamped = page != requestedPage;
        boolean overshoot = requestedPage > pages;

        String header = (typeFilter == null)
                ? config.msg("page-header","Reports Page %page%/%pages%")
                : config.msg("page-header-filtered","Reports (%type%/%cat%) Page %page%/%pages%");
        header = header.replace("%type%", typeFilter == null ? "ALL" : typeFilter)
                       .replace("%cat%", categoryFilter == null ? "*" : categoryFilter)
                       .replace("%page%", String.valueOf(page))
                       .replace("%pages%", String.valueOf(pages));
        reply(src, header);

        String tip = expandTip();
        String entryTemplate = msg("reports-list-entry",
                "%row%  <gray>[</gray><aqua><hover:show_text:'%expand_tip%'><click:run_command:'/reports view %id%'>%expand_label%</click></hover></aqua><gray>]</gray>");
        String expandLabel = expandLabel();
        for (Report r : Pagination.paginate(open, per, page)) {
            String entry = entryTemplate
                    .replace("%row%", fmtListLine(r))
                    .replace("%id%", String.valueOf(r.id))
                    .replace("%expand_tip%", Text.escape(tip))
                    .replace("%expand_label%", Text.escape(expandLabel));
            reply(src, entry);
        }

        if (pages > 1) {
            String prevTip = config.msg("tip-prev", "Previous page");
            String nextTip = config.msg("tip-next", "Next page");
            Component nav = Text.mm(
                    "<gray>[</gray><aqua><hover:show_text:'"+Text.escape(prevTip)+"'><click:run_command:'/reports page "+Math.max(1, page-1)+"'>« Prev</click></hover></aqua><gray>] " +
                    "[</gray><aqua><hover:show_text:'"+Text.escape(nextTip)+"'><click:run_command:'/reports page "+Math.min(pages, page+1)+"'>Next »</click></hover></aqua><gray>]</gray>"
            );
            src.sendMessage(nav);
        }
        if (clamped) {
            String key = overshoot ? "page-end" : "page-start";
            String def = overshoot
                    ? "<gray>You're already on the last page.</gray>"
                    : "<gray>You're already on the first page.</gray>";
        Text.msg(src, msg(key, def));
        }

        if (src instanceof Player p) {
            String quick = QuickActions.render(p, config);
            if (quick != null && !quick.isBlank()) {
                src.sendMessage(Text.mm(quick));
            }
        }
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
            var opt = plugin.proxy().getPlayer(r.reported);
            if (opt.isPresent()) {
                var sv = opt.get().getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
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
    private void expand(CommandSource src, Report r) {
        reply(src, "");
        String header = config.msg("expanded-header","Report #%id% (%type% / %category%)")
                .replace("%id%", String.valueOf(r.id))
                .replace("%type%", r.typeDisplay)
                .replace("%category%", r.categoryDisplay);
        reply(src, header);

        String serverName = deriveServer(r);
        if (serverName == null || serverName.isBlank()) serverName = "UNKNOWN";
        String serverLine = config.msg("expanded-server-line", "<gray>Server:</gray> <white>%server%</white>")
                .replace("%server%", serverName);
        reply(src, serverLine);

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
            reply(src, out);
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
        if (!"UNKNOWN".equalsIgnoreCase(serverName)
                && plugin.proxy().getServer(serverName).isPresent()) {

            String jumpCmdTemplate = config.msg("jump-command-template", "/server %server%");
            String jumpCmd = jumpCmdTemplate.replace("%server%", serverName);

            actions.append("<gray>[</gray><aqua><hover:show_text:'").append(Text.escape(tipJump))
                    .append("'><click:run_command:'").append(Text.escape(jumpCmd))
                    .append("'>Jump to server</click></hover></aqua><gray>]</gray> ");
        }

        // Quick-assign buttons
        if (src instanceof Player p) {
            boolean assigned = r.assignee != null && !r.assignee.isBlank();
            boolean mine = assigned && p.getUsername().equalsIgnoreCase(r.assignee);
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

        reply(src, actions.toString());
    }

    /** Paginated inline chat output when web viewer is disabled. */
    private void showChatPage(CommandSource src, Report r, int page) {
        int per = Math.max(1, config.previewLines);
        int total = r.chat.size();
        int pages = Math.max(1, (int)Math.ceil(total / (double) per));
        page = Math.min(Math.max(1, page), pages);

        int start = (page - 1) * per;
                int end = Math.min(start + per, total);

                reply(src, msg("reports-chat-header", "<gray>Chat for #%id% — page %page%/%pages% (%total% lines):</gray>")
                        .replace("%id%", String.valueOf(r.id))
                        .replace("%page%", String.valueOf(page))
                        .replace("%pages%", String.valueOf(pages))
                        .replace("%total%", String.valueOf(total)));
                for (int i = start; i < end; i++) {
                    var m = r.chat.get(i);
                    String raw = "["+ TimeUtil.formatTime(m.time)+"] "+m.player+"@"+m.server+": "+m.message;
                    String safe = Text.escape(raw);
                    if (safe.length() > config.previewLineMaxChars) {
                        int lim = Math.max(0, config.previewLineMaxChars - 1);
                        safe = safe.substring(0, lim) + "…";
                    }
                    reply(src, msg("reports-chat-line", "<gray>%line%</gray>").replace("%line%", safe));
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

    private String msg(String key, String def) {
        return config.msg(key, def);
    }

    private void reply(CommandSource src, String mini) {
        Text.msg(src, mini == null ? "" : mini);
    }

    private void send(CommandSource src, String key, String def) {
        reply(src, msg(key, def));
    }

    private String expandButton(long id) {
        String template = config.msg("reports-inline-expand-button", "");
        if (template == null || template.isBlank()) {
            template = config.msg("reports-notify-expand-button",
                    "<gray>[</gray><aqua><hover:show_text:'%expand_tip%'><click:run_command:'/reports view %id%'>%expand_label%</click></hover></aqua><gray>]</gray>");
        }
        String tip = expandTip();
        String label = expandLabel();
        return template
                .replace("%id%", String.valueOf(id))
                .replace("%expand_tip%", Text.escape(tip))
                .replace("%expand_label%", Text.escape(label));
    }

    private String withExpand(Report report, String message) {
        if (report == null) return message;
        String button = expandButton(report.id);
        if (button == null || button.isBlank()) return message;
        return message + " " + button;
    }

    private void showPriorityBreakdown(CommandSource src, Report r) {
        var breakdown = mgr.debugPriority(r);
        if (!breakdown.enabled) {
            reply(src, msg("reports-priority-disabled", "<gray>Priority scoring is disabled; ordering falls back to <white>%tiebreaker%</white>.</gray>")
                    .replace("%tiebreaker%", Text.escape(breakdown.tieBreaker)));
            return;
        }

        reply(src, msg("reports-priority-total", "<gray>Priority for <white>#%id%</white>: <green>%score%</green></gray>")
                .replace("%id%", String.valueOf(r.id))
                .replace("%score%", fmt(breakdown.total)));
        if (breakdown.components.isEmpty()) {
            reply(src, msg("reports-priority-empty", "<gray>No contributing factors (all weights zero or disabled).</gray>"));
        } else {
            for (var comp : breakdown.components) {
                String line = "<gray>- " + Text.escape(comp.name) + ":</gray> weight <white>" + fmt(comp.weight)
                        + "</white> × value <white>" + fmt(comp.value) + "</white> = <white>" + fmt(comp.contribution)
                        + "</white> <gray>(" + Text.escape(comp.reason) + ")</gray>";
                reply(src, line);
            }
        }
        reply(src, msg("reports-priority-tiebreaker", "<gray>Tie-breaker after priority: <white>%tiebreaker%</white>.</gray>")
                .replace("%tiebreaker%", Text.escape(breakdown.tieBreaker)));
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
