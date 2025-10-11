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
        authors = {"e1ixyz"}
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
    private WebServer webServer;
    private ReportCommand reportCommand;
    private ReportsCommand reportsCommand;
    private ReportHistoryCommand reportHistoryCommand;

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
        try {
            this.config = new ConfigManager(dataDir).loadOrCreate();
        } catch (Exception ex) {
            logger.error("Failed to load config.yml", ex);
            this.config = new PluginConfig();
        }

        this.reportManager  = new ReportManager(this, dataDir, config);
        this.chatLogService = new ChatLogService(this, reportManager, config);
        this.authService    = new AuthService(config, logger);
        this.notifier       = new Notifier(this, config);

        proxy.getEventManager().register(this, chatLogService);

        if (config.httpServer != null && config.httpServer.enabled) {
            var root = dataDir.resolve(config.htmlExportDir);
            this.webServer = new WebServer(config, logger, root, authService);
            try {
                webServer.start();
            } catch (IOException io) {
                logger.warn("HTTP server failed to start: {}", io.toString());
            }
        }

        CommandManager cm = proxy.getCommandManager();
        CommandMeta reportMeta = cm.metaBuilder("report").build();
        this.reportCommand = new ReportCommand(this, reportManager, chatLogService, config);
        cm.register(reportMeta, reportCommand);

        CommandMeta reportsMeta = cm.metaBuilder("reports").build();
        this.reportsCommand = new ReportsCommand(this, reportManager, config, authService);
        cm.register(reportsMeta, reportsCommand);

        CommandMeta historyMeta = cm.metaBuilder("reporthistory").build();
        this.reportHistoryCommand = new ReportHistoryCommand(this, reportManager, config);
        cm.register(historyMeta, reportHistoryCommand);

        logger.info("ReportSystem enabled.");
    }

    /** /reports reload */
    public void reload() {
        try {
            PluginConfig newCfg = new ConfigManager(dataDir).loadOrCreate();
            this.config = newCfg;

            reportManager.setConfig(newCfg);
            chatLogService.setConfig(newCfg);
            notifier.setConfig(newCfg);
            if (reportCommand != null) reportCommand.setConfig(newCfg);
            if (reportsCommand != null) reportsCommand.setConfig(newCfg);
            if (reportHistoryCommand != null) reportHistoryCommand.setConfig(newCfg);

            if (webServer != null) {
                webServer.stop();
            }
            if (newCfg.httpServer != null && newCfg.httpServer.enabled) {
                var root = dataDir.resolve(newCfg.htmlExportDir);
                webServer = new WebServer(newCfg, logger, root, authService);
                try {
                    webServer.start();
                } catch (IOException io) {
                    logger.warn("HTTP server failed to start after reload: {}", io.toString());
                }
            } else {
                webServer = null;
            }

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

    public ReportManager reports() { return reportManager; }
    public ChatLogService chatLogs() { return chatLogService; }
    public AuthService auth() { return authService; }
    public Notifier notifier() { return notifier; }
}
