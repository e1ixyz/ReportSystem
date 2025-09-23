package com.example.reportsystem;

import com.example.reportsystem.commands.ReportCommand;
import com.example.reportsystem.commands.ReportHistoryCommand;
import com.example.reportsystem.commands.ReportsCommand;
import com.example.reportsystem.config.ConfigManager;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.service.AuthService;
import com.example.reportsystem.service.ChatLogService;
import com.example.reportsystem.service.HttpServerService;
import com.example.reportsystem.service.Notifier;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Text;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
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
    private HttpServerService httpServerService;
    private AuthService authService;
    private Notifier notifier;

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
        // Load config.yml via ConfigManager (matches your current files)
        try {
            this.config = new ConfigManager(dataDir).loadOrCreate();
        } catch (Exception ex) {
            logger.error("Failed to load config.yml", ex);
            this.config = new PluginConfig(); // fall back to defaults
        }

        // Core services (match constructors in your current code)
        this.reportManager     = new ReportManager(this, dataDir, config);
        this.chatLogService    = new ChatLogService(this, reportManager, config);
        this.httpServerService = new HttpServerService(this, config);
        this.authService       = new AuthService(config, logger);
        this.notifier          = new Notifier(this, config);

        // Register event listeners for @Subscribe handlers
        proxy.getEventManager().register(this, chatLogService);

        // Start embedded HTTP server if enabled
        if (config.httpServer != null && config.httpServer.enabled) {
            try {
                httpServerService.start();
            } catch (IOException io) {
                logger.warn("HTTP server failed to start: {}", io.toString());
            }
        }

        // Register commands (Velocity CommandMeta)
        CommandManager cm = proxy.getCommandManager();

        CommandMeta reportMeta = cm.metaBuilder("report").build();
        cm.register(reportMeta, new ReportCommand(this, reportManager, chatLogService, config));

        CommandMeta reportsMeta = cm.metaBuilder("reports").build();
        cm.register(reportsMeta, new ReportsCommand(this, reportManager, config, authService));

        CommandMeta historyMeta = cm.metaBuilder("reporthistory").build();
        cm.register(historyMeta, new ReportHistoryCommand(this, reportManager, config));

        // (Optional) short alias for history
        try {
            CommandMeta rhMeta = cm.metaBuilder("rh").build();
            cm.register(rhMeta, new ReportHistoryCommand(this, reportManager, config));
        } catch (Throwable ignored) {
            // If alias collides with another plugin, just ignore
        }

        logger.info("ReportSystem enabled.");
    }

    /** Hot-reload config and reapply to services. */
    public void reload() {
        try {
            PluginConfig newCfg = new ConfigManager(dataDir).loadOrCreate();
            this.config = newCfg;

            // Re-apply config to services that support it
            this.reportManager.setConfig(newCfg);
            this.chatLogService.setConfig(newCfg);
            this.notifier.setConfig(newCfg);

            // Restart HTTP server if toggle/port/base changed
            if (httpServerService != null) {
                httpServerService.stop();
            }
            this.httpServerService = new HttpServerService(this, newCfg);
            if (newCfg.httpServer != null && newCfg.httpServer.enabled) {
                try {
                    httpServerService.start();
                } catch (IOException io) {
                    logger.warn("HTTP server failed to start after reload: {}", io.toString());
                }
            }

            // Let staff know (respecting notifyPermission)
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

    // Accessors for services (used elsewhere)
    public ReportManager reports() { return reportManager; }
    public ChatLogService chatLogs() { return chatLogService; }
    public HttpServerService httpServer() { return httpServerService; }
    public AuthService auth() { return authService; }

    // Notifier hook used by commands & history
    public Notifier notifier() { return notifier; }
}
