package com.example.reportsystem;

import com.example.reportsystem.commands.ReportCommand;
import com.example.reportsystem.commands.ReportHistoryCommand;
import com.example.reportsystem.commands.ReportsCommand;
import com.example.reportsystem.config.ConfigManager;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.service.AuthService;
import com.example.reportsystem.service.ChatLogService;
import com.example.reportsystem.service.Notifier;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.service.WebServer;
import com.example.reportsystem.util.Text;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

@Plugin(
        id = "reportsystem",
        name = "ReportSystem",
        version = "2.1.0",
        authors = {"you"}
)
public final class ReportSystem {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private PluginConfig config;
    private ReportManager reportManager;
    private ChatLogService chatLogService;
    private AuthService authService;
    private Notifier notifier;

    /** Use the auth-aware WebServer (handles /login & cookie gating). */
    private WebServer webServer;

    @Inject
    public ReportSystem(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    public ProxyServer proxy() { return proxy; }
    public Logger logger() { return logger; }
    public Path dataDir() { return dataDir; }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        // 1) Load config
        try {
            this.config = new ConfigManager(dataDir).loadOrCreate();
        } catch (Exception ex) {
            logger.error("Failed to load config.yml", ex);
            this.config = new PluginConfig(); // safe defaults
        }

        // 2) Core services
        this.reportManager  = new ReportManager(this, dataDir, config);          // matches ctor(ReportSystem, Path, PluginConfig)
        this.chatLogService = new ChatLogService(this, reportManager, config);
        this.authService    = new AuthService(config, logger);
        this.notifier       = new Notifier(this, config);

        // 3) Event listeners (chat capture, etc.)
        proxy.getEventManager().register(this, chatLogService);

        // 4) Start AUTH-PROTECTED static server for html-logs (only if enabled)
        if (config.httpServer != null && config.httpServer.enabled) {
            Path root = dataDir.resolve(config.htmlExportDir).normalize();
            this.webServer = new WebServer(config, logger, root, authService);
            try {
                webServer.start();
            } catch (IOException io) {
                logger.warn("HTTP server failed to start: {}", io.toString());
                this.webServer = null;
            }
        }

        // 5) Commands
        CommandManager cm = proxy.getCommandManager();

        CommandMeta reportMeta = cm.metaBuilder("report").build();
        cm.register(reportMeta, new ReportCommand(this, reportManager, chatLogService, config));

        CommandMeta reportsMeta = cm.metaBuilder("reports").build();
        cm.register(reportsMeta, new ReportsCommand(this, reportManager, config, authService));

        CommandMeta historyMeta = cm.metaBuilder("reporthistory").build();
        cm.register(historyMeta, new ReportHistoryCommand(this, reportManager, config));

        logger.info("ReportSystem enabled.");
    }

    /** Invoked by /reports reload */
    public void reload() {
        try {
            // Reload config from disk
            PluginConfig newCfg = new ConfigManager(dataDir).loadOrCreate();
            this.config = newCfg;

            // Reapply to services
            reportManager.setConfig(newCfg);
            chatLogService.setConfig(newCfg);
            notifier.setConfig(newCfg);

            // Restart auth-protected web server with fresh config
            if (webServer != null) {
                webServer.stop();
                webServer = null;
            }
            if (newCfg.httpServer != null && newCfg.httpServer.enabled) {
                Path root = dataDir.resolve(newCfg.htmlExportDir).normalize();
                webServer = new WebServer(newCfg, logger, root, authService);
                try {
                    webServer.start();
                } catch (IOException io) {
                    logger.warn("HTTP server failed to start after reload: {}", io.toString());
                    webServer = null;
                }
            }

            // Let online staff know (keeps your existing notify-permission behavior)
            proxy.getAllPlayers().forEach(p -> {
                if (p.hasPermission(config.notifyPermission)) {
                    Text.msg(p, config.msg("reloaded", "ReportSystem reloaded."));
                }
            });
            logger.info("ReportSystem reloaded.");
        } catch (Exception ex) {
            logger.error("Reload failed", ex);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (webServer != null) {
            try { webServer.stop(); } catch (Throwable ignored) {}
            webServer = null;
        }
        logger.info("ReportSystem disabled.");
    }

    // Accessors used elsewhere
    public ReportManager reports() { return reportManager; }
    public ChatLogService chatLogs() { return chatLogService; }
    public AuthService auth() { return authService; }
    public Notifier notifier() { return notifier; }
}
