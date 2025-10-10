package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.model.ReportType;
import com.example.reportsystem.platform.CommandActor;
import com.example.reportsystem.platform.CommandContext;
import com.example.reportsystem.platform.CommandHandler;
import com.example.reportsystem.platform.PlatformPlayer;
import com.example.reportsystem.service.ChatLogService;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Text;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

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
public class ReportCommand implements CommandHandler {

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
    public void execute(CommandContext ctx) {
        CommandActor src = ctx.actor();
        String[] args = ctx.args();

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
        PlatformPlayer playerSource = src.asPlayer().orElse(null);
        String reporter = playerSource != null ? playerSource.username() : "CONSOLE";
        String reported;
        String reason;

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

            if (!config.allowSelfReport && playerSource != null && playerSource.username().equalsIgnoreCase(reported)) {
                Text.msg(src, config.msg("self-report-denied", "You cannot report yourself."));
                return;
            }

            // Optional: validate target online (non-fatal)
            plugin.platform().findPlayer(reported);
        } else {
            if (args.length < 3) {
                Text.msg(src, config.msg("usage-report", "Usage: /report <type> <category> <reason...>"));
                return;
            }
            reported = "N/A";
            reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        }

        Report r = mgr.fileOrStack(reporter, reported, rt, reason);

        if (playerSource != null) {
            playerSource.currentServerName()
                    .filter(name -> !name.isBlank())
                    .ifPresent(name -> mgr.updateSourceServer(r.id, name));
            lastReportAt.put(playerSource.uniqueId(), System.currentTimeMillis());
        }

        chat.refreshWatchList();

        if (r.count > 1) {
            Text.msg(src, config.msg("report-stacked", "Report stacked into #%id% (now x%count%)")
                    .replace("%id%", String.valueOf(r.id))
                    .replace("%count%", String.valueOf(r.count)));
        } else {
            Text.msg(src, config.msg("report-filed", "Report filed! (ID #%id%)")
                    .replace("%id%", String.valueOf(r.id)));
        }

        String notifyPerm = (config.notifyPermission == null || config.notifyPermission.isBlank())
                ? "reportsystem.notify" : config.notifyPermission;

        String expandLabel = config.msg("label-expand", "Expand");
        String expandTip   = config.msg("tip-expand", "Click to expand");

        String summary = "<yellow>New report:</yellow> <white>#"+r.id+
                "</white> <gray>("+r.typeDisplay+" / "+r.categoryDisplay+")</gray> " +
                "<white>"+(isPlayerType ? r.reported : "—")+"</white> — <gray>"+Text.escape(reason)+
                "</gray>  <gray>[</gray><aqua><hover:show_text:'"+Text.escape(expandTip)+"'><click:run_command:'/reports view "+r.id+"'>"+Text.escape(expandLabel)+"</click></hover></aqua><gray>]</gray>";

        plugin.platform().onlinePlayers().forEach(pl -> {
            if (pl.hasPermission(notifyPerm)) {
                Text.msg(pl, summary);
            }
        });

        try {
            Object n = plugin.notifier();
            if (n != null) {
                n.getClass().getMethod("notifyNew", Report.class, String.class).invoke(n, r, reason);
            }
        } catch (Throwable ignored) {}
    }

    private boolean enforceCooldown(PlatformPlayer player) {
        if (player.hasPermission(config.staffPermission)) return true;

        int cooldown = Math.max(0, config.reportCooldownSeconds);
        if (cooldown <= 0) return true;

        long now = System.currentTimeMillis();
        long last = lastReportAt.getOrDefault(player.uniqueId(), 0L);
        long waitMillis = (last + cooldown * 1000L) - now;
        if (waitMillis <= 0) return true;

        long secondsLeft = (waitMillis + 999L) / 1000L;
        Text.msg(player, config.msg("report-cooldown", "<red>You must wait %seconds%s before filing another report.</red>")
                .replace("%seconds%", String.valueOf(secondsLeft)));
        return false;
    }

    @Override
    public List<String> suggest(CommandContext ctx) {
        String[] a = ctx.args();

        if (a.length == 0) return mgr.typeIds();

        if (a.length == 1) return filter(mgr.typeIds(), a[0]);

        if (a.length == 2) return filter(mgr.categoryIdsFor(a[0]), a[1]);

        if (a[0].equalsIgnoreCase("player")) {
            if (a.length == 3) {
                List<String> names = plugin.platform().onlinePlayers().stream()
                        .map(PlatformPlayer::username)
                        .collect(Collectors.toList());
                return filter(names, a[2]);
            }
            return reasonPlaceholder(a.length >= 4 ? a[3] : "");
        }

        if (a.length >= 3) {
            return reasonPlaceholder(a[2]);
        }

        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    private static List<String> reasonPlaceholder(String current) {
        if (current == null || current.isBlank()) return List.of(REASON_PLACEHOLDER);
        String p = current.toLowerCase(Locale.ROOT);
        return REASON_PLACEHOLDER.toLowerCase(Locale.ROOT).startsWith(p)
                ? List.of(REASON_PLACEHOLDER)
                : List.of();
    }
}
