package com.example.reportsystem;

import com.example.reportsystem.commands.ReportCommand;
import com.example.reportsystem.commands.ReportHistoryCommand;
import com.example.reportsystem.commands.ReportsCommand;
import com.example.reportsystem.config.ConfigManager;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.service.ChatLogService;
import com.example.reportsystem.service.HttpServerService;
import com.example.reportsystem.service.Notifier;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.util.Text;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
    id = "reportsystem",
    name = "ReportSystem",
    version = "2.1.1",
    authors = {"yourname"},
    description = "Dynamic report system with priority sorting, stacking colors, tooltips, Discord webhooks, and chat logs."
)
public final class ReportSystem {

    private final ProxyServer proxy;
    private final Logger logger;
    private Path dataDir;

    private PluginConfig config;
    private ConfigManager configManager;
    private ReportManager reportManager;
    private ChatLogService chatLogService;
    private Notifier notifier;
    private HttpServerService httpServerService;

    @Inject
    public ReportSystem(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Inject
    public void injectDataDir(@DataDirectory Path dataDirectory) {
        this.dataDir = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        try {
            this.configManager = new ConfigManager(dataDir);
            this.config = configManager.loadOrCreate();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load config", ex);
        }

        this.reportManager = new ReportManager(this, dataDir, config);
        this.chatLogService = new ChatLogService(this, reportManager, config);
        this.notifier = new Notifier(this, config);
        proxy.getEventManager().register(this, chatLogService);

        if (config.httpServer != null && config.httpServer.enabled) {
            this.httpServerService = new HttpServerService(this, config);
            try { httpServerService.start(); } catch (Exception ex) { logger.warn("Failed to start embedded HTTP server: {}", ex.toString()); }
        }

        CommandManager cm = proxy.getCommandManager();
        cm.register(cm.metaBuilder("report").build(), new ReportCommand(this, reportManager, chatLogService, config));
        cm.register(cm.metaBuilder("reports").build(), new ReportsCommand(this, reportManager, config));
        cm.register(cm.metaBuilder("reporthistory").build(), new ReportHistoryCommand(this, reportManager, config));

        logger.info("ReportSystem initialized. Data dir: {}", dataDir.toAbsolutePath());
    }

    public ProxyServer proxy() { return proxy; }
    public Logger logger() { return logger; }
    public Path dataDir() { return dataDir; }
    public PluginConfig config() { return config; }
    public ConfigManager configManager() { return configManager; }
    public ReportManager reportManager() { return reportManager; }
    public ChatLogService chatLogService() { return chatLogService; }
    public Notifier notifier() { return notifier; }
    public HttpServerService httpServerService() { return httpServerService; }

    public void reload() {
        try {
            this.config = configManager.loadOrCreate();
            reportManager.setConfig(config);
            chatLogService.setConfig(config);
            notifier.setConfig(config);

            if (httpServerService != null) { httpServerService.stop(); httpServerService = null; }
            if (config.httpServer != null && config.httpServer.enabled) {
                httpServerService = new HttpServerService(this, config);
                try { httpServerService.start(); } catch (Exception ex) { logger.warn("HTTP server restart failed: {}", ex.toString()); }
            }

            Text.reloadMiniMessage();
            logger.info("ReportSystem reloaded.");
        } catch (Exception ex) {
            logger.error("Reload failed", ex);
        }
    }
}
