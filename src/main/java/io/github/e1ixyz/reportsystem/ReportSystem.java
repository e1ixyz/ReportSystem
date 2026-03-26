package io.github.e1ixyz.reportsystem;

import io.github.e1ixyz.reportsystem.commands.ReportCommand;
import io.github.e1ixyz.reportsystem.commands.ReportHistoryCommand;
import io.github.e1ixyz.reportsystem.commands.ReportsCommand;
import io.github.e1ixyz.reportsystem.config.ConfigManager;
import io.github.e1ixyz.reportsystem.config.PluginConfig;
import io.github.e1ixyz.reportsystem.service.AuthService;
import io.github.e1ixyz.reportsystem.service.ChatLogService;
import io.github.e1ixyz.reportsystem.service.Notifier;
import io.github.e1ixyz.reportsystem.service.PlayerNotificationService;
import io.github.e1ixyz.reportsystem.service.ReportManager;
import io.github.e1ixyz.reportsystem.service.ReportMenuService;
import io.github.e1ixyz.reportsystem.service.WebServer;
import io.github.e1ixyz.reportsystem.util.Text;
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
        version = "3.0.0",
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
    private PlayerNotificationService playerNotificationService;
    private WebServer webServer;
    private ReportMenuService reportMenuService;
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
        this.playerNotificationService = new PlayerNotificationService(this, reportManager, config);

        proxy.getEventManager().register(this, chatLogService);
        proxy.getEventManager().register(this, playerNotificationService);

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
        this.reportMenuService = new ReportMenuService(this, reportManager, reportCommand, config);
        this.reportCommand.setMenuService(reportMenuService);
        cm.register(reportMeta, reportCommand);
        proxy.getEventManager().register(this, reportMenuService);

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
            authService.setConfig(newCfg);
            notifier.setConfig(newCfg);
            if (playerNotificationService != null) playerNotificationService.setConfig(newCfg);
            if (reportCommand != null) reportCommand.setConfig(newCfg);
            if (reportsCommand != null) reportsCommand.setConfig(newCfg);
            if (reportHistoryCommand != null) reportHistoryCommand.setConfig(newCfg);
            if (reportMenuService != null) {
                reportMenuService.setConfig(newCfg);
                if (!newCfg.reportMenuEnabled) {
                    reportMenuService.clearSessions();
                }
            }

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
    public PlayerNotificationService playerNotifications() { return playerNotificationService; }
}
