package com.example.reportsystem.commands;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.service.ReportManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import java.util.List;

public final class CommandTrees {

    private CommandTrees() {}

    public static void registerAll(ReportSystem plugin, CommandManager cm, ReportManager mgr, PluginConfig cfg) {
        // Best-effort cleanup so suggestions refresh (safe if not supported)
        try { cm.unregister("report"); } catch (Throwable ignored) {}
        try { cm.unregister("reports"); } catch (Throwable ignored) {}
        try { cm.unregister("reporthistory"); } catch (Throwable ignored) {}

        // Register Brigadier-backed commands (they delegate to your SimpleCommand handlers)
        cm.register(cm.metaBuilder("report").build(), new BrigadierCommand(buildReportTree(plugin, mgr)));
        cm.register(cm.metaBuilder("reports").build(), new BrigadierCommand(buildReportsTree(plugin, mgr)));
        cm.register(cm.metaBuilder("reporthistory").build(), new BrigadierCommand(buildHistoryTree(plugin, mgr)));
    }

    private static LiteralCommandNode<CommandSource> buildReportTree(ReportSystem plugin, ReportManager mgr) {
        SuggestionProvider<CommandSource> types = (c, b) -> {
            mgr.typeIds().forEach(b::suggest);
            return b.buildFuture();
        };
        SuggestionProvider<CommandSource> cats = (c, b) -> {
            String type = c.getArgument("type", String.class);
            mgr.categoryIdsFor(type).forEach(b::suggest);
            return b.buildFuture();
        };
        SuggestionProvider<CommandSource> players = (c, b) -> {
            plugin.proxy().getAllPlayers().stream().map(Player::getUsername).forEach(b::suggest);
            return b.buildFuture();
        };

        LiteralArgumentBuilder<CommandSource> root =
                LiteralArgumentBuilder.<CommandSource>literal("report")
                    .then(RequiredArgumentBuilder.<CommandSource, String>argument("type", StringArgumentType.word()).suggests(types)
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("category", StringArgumentType.word()).suggests(cats)
                            .then(RequiredArgumentBuilder.<CommandSource, String>argument("target_or_reason", StringArgumentType.greedyString()).suggests(players)
                                .executes(ctx -> {
                                    String full = "/report " + ctx.getArgument("type", String.class)
                                            + " " + ctx.getArgument("category", String.class)
                                            + " " + ctx.getArgument("target_or_reason", String.class);
                                    plugin.proxy().getCommandManager().executeAsync(ctx.getSource(), full);
                                    return 1;
                                })
                            )
                        )
                    );

        return root.build();
    }

    private static LiteralCommandNode<CommandSource> buildReportsTree(ReportSystem plugin, ReportManager mgr) {
        SuggestionProvider<CommandSource> idsOpen = (c, b) -> {
            mgr.getOpenReportsDescending().forEach(r -> b.suggest(String.valueOf(r.id)));
            return b.buildFuture();
        };
        SuggestionProvider<CommandSource> staff = (c, b) -> {
            plugin.proxy().getAllPlayers().stream().map(Player::getUsername).forEach(b::suggest);
            return b.buildFuture();
        };
        SuggestionProvider<CommandSource> scopes = (c, b) -> {
            List.of("open","closed","all").forEach(b::suggest);
            return b.buildFuture();
        };

        LiteralArgumentBuilder<CommandSource> root =
                LiteralArgumentBuilder.<CommandSource>literal("reports")
                    .then(LiteralArgumentBuilder.<CommandSource>literal("page")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("n", StringArgumentType.word())
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reports page " + ctx.getArgument("n", String.class)))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("view")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word()).suggests(idsOpen)
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reports view " + ctx.getArgument("id", String.class)))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("close")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word()).suggests(idsOpen)
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reports close " + ctx.getArgument("id", String.class)))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("chat")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word()).suggests(idsOpen)
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reports chat " + ctx.getArgument("id", String.class)))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("assign")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word()).suggests(idsOpen)
                            .then(RequiredArgumentBuilder.<CommandSource, String>argument("staff", StringArgumentType.word()).suggests(staff)
                                .executes(ctx -> exec(plugin, ctx.getSource(), "reports assign "
                                        + ctx.getArgument("id", String.class) + " "
                                        + ctx.getArgument("staff", String.class))))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("unassign")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word()).suggests(idsOpen)
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reports unassign " + ctx.getArgument("id", String.class)))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("search")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("query", StringArgumentType.greedyString())
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reports search " + ctx.getArgument("query", String.class)))
                            .then(RequiredArgumentBuilder.<CommandSource, String>argument("scope", StringArgumentType.word()).suggests(scopes)
                                .executes(ctx -> exec(plugin, ctx.getSource(), "reports search "
                                        + ctx.getArgument("query", String.class) + " "
                                        + ctx.getArgument("scope", String.class))))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                        .executes(ctx -> exec(plugin, ctx.getSource(), "reports reload")))
                    .executes(ctx -> exec(plugin, ctx.getSource(), "reports"));

        return root.build();
    }

    private static LiteralCommandNode<CommandSource> buildHistoryTree(ReportSystem plugin, ReportManager mgr) {
        SuggestionProvider<CommandSource> idsClosed = (c, b) -> {
            mgr.getClosedReportsDescending().forEach(r -> b.suggest(String.valueOf(r.id)));
            return b.buildFuture();
        };

        LiteralArgumentBuilder<CommandSource> root =
                LiteralArgumentBuilder.<CommandSource>literal("reporthistory")
                    .then(LiteralArgumentBuilder.<CommandSource>literal("page")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("n", StringArgumentType.word())
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reporthistory page " + ctx.getArgument("n", String.class)))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("view")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word()).suggests(idsClosed)
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reporthistory view " + ctx.getArgument("id", String.class)))))
                    .then(LiteralArgumentBuilder.<CommandSource>literal("reopen")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("id", StringArgumentType.word()).suggests(idsClosed)
                            .executes(ctx -> exec(plugin, ctx.getSource(), "reporthistory reopen " + ctx.getArgument("id", String.class)))))
                    .executes(ctx -> exec(plugin, ctx.getSource(), "reporthistory"));

        return root.build();
    }

    private static int exec(ReportSystem plugin, CommandSource src, String raw) {
        plugin.proxy().getCommandManager().executeAsync(src, "/" + raw);
        return 1;
    }
}
