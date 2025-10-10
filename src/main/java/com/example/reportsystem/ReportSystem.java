package com.example.reportsystem;

import com.example.reportsystem.commands.ReportCommand;
import com.example.reportsystem.commands.ReportHistoryCommand;
import com.example.reportsystem.commands.ReportsCommand;
import com.example.reportsystem.config.ConfigManager;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.platform.PlatformAdapter;
import com.example.reportsystem.platform.PlatformPlayer;
import com.example.reportsystem.service.AuthService;
import com.example.reportsystem.service.ChatLogService;
import com.example.reportsystem.service.Notifier;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.service.WebServer;
import com.example.reportsystem.storage.MySqlReportStorage;
import com.example.reportsystem.storage.ReportStorage;
import com.example.reportsystem.storage.YamlReportStorage;
import com.example.reportsystem.util.Text;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Platform-neutral core of the plugin. Lifecycle is managed by the platform bootstraps.
 */
public final class ReportSystem {

    private final PlatformAdapter platform;
    private final Path dataDir;

    private PluginConfig config = new PluginConfig();
    private ReportStorage storage;
    private ReportManager reportManager;
    private ChatLogService chatLogService;
    private AuthService authService;
    private Notifier notifier;
    private WebServer webServer;

    private ReportCommand reportCommand;
    private ReportsCommand reportsCommand;
    private ReportHistoryCommand reportHistoryCommand;

    private boolean enabled;

    public ReportSystem(PlatformAdapter platform) {
        this.platform = platform;
        this.dataDir = platform.dataDirectory();
    }

    public PlatformAdapter platform() {
        return platform;
    }

    public Logger logger() {
        return platform.logger();
    }

    public Path dataDir() {
        return dataDir;
    }

    public boolean enabled() {
        return enabled;
    }

    public PluginConfig config() {
        return config;
    }

    public ReportManager reports() {
        return reportManager;
    }

    public ChatLogService chatLogs() {
        return chatLogService;
    }

    public AuthService auth() {
        return authService;
    }

    public Notifier notifier() {
        return notifier;
    }

    public void enable() {
        if (enabled) {
            return;
        }

        try {
            this.config = new ConfigManager(dataDir).loadOrCreate();
        } catch (Exception ex) {
            logger().error("Failed to load config.yml", ex);
            this.config = new PluginConfig();
        }

        this.storage = createStorage(config);
        this.reportManager = new ReportManager(this, config, storage);
        this.chatLogService = new ChatLogService(this, reportManager, config);
        this.authService = new AuthService(config, logger());
        this.notifier = new Notifier(this, config);

        platform.registerChatListener(chatLogService);

        if (config.httpServer != null && config.httpServer.enabled) {
            startWebServer(config);
        }

        this.reportCommand = new ReportCommand(this, reportManager, chatLogService, config);
        this.reportsCommand = new ReportsCommand(this, reportManager, config, authService);
        this.reportHistoryCommand = new ReportHistoryCommand(this, reportManager, config);

        platform.registerCommand("report", reportCommand);
        platform.registerCommand("reports", reportsCommand);
        platform.registerCommand("reporthistory", reportHistoryCommand);

        enabled = true;
        logger().info("ReportSystem enabled on {}.", platform.type());
    }

    public void disable() {
        if (!enabled) {
            return;
        }

        if (reportCommand != null) {
            platform.unregisterCommand("report");
            reportCommand = null;
        }
        if (reportsCommand != null) {
            platform.unregisterCommand("reports");
            reportsCommand = null;
        }
        if (reportHistoryCommand != null) {
            platform.unregisterCommand("reporthistory");
            reportHistoryCommand = null;
        }

        if (chatLogService != null) {
            platform.unregisterChatListener(chatLogService);
        }

        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }

        if (storage != null) {
            try {
                storage.close();
            } catch (Exception ex) {
                logger().warn("Failed to close storage backend: {}", ex.toString());
            }
        }

        enabled = false;
        logger().info("ReportSystem disabled.");
    }

    /** `/reports reload` */
    public void reload() {
        try {
            PluginConfig newCfg = new ConfigManager(dataDir).loadOrCreate();
            PluginConfig.StorageConfig.Type oldType = this.config != null ? this.config.storage.type : null;
            this.config = newCfg;

            if (oldType != null && oldType != newCfg.storage.type) {
                logger().warn("Storage backend change detected ({} -> {}); please restart to apply it.", oldType, newCfg.storage.type);
            }

            if (reportManager != null) reportManager.setConfig(newCfg);
            if (chatLogService != null) chatLogService.setConfig(newCfg);
            if (notifier != null) notifier.setConfig(newCfg);
            if (reportCommand != null) reportCommand.setConfig(newCfg);
            if (reportsCommand != null) reportsCommand.setConfig(newCfg);
            if (reportHistoryCommand != null) reportHistoryCommand.setConfig(newCfg);

            if (webServer != null) {
                webServer.stop();
            }
            if (newCfg.httpServer != null && newCfg.httpServer.enabled) {
                startWebServer(newCfg);
            } else {
                webServer = null;
            }

            String notifyPerm = (newCfg.notifyPermission == null) ? "" : newCfg.notifyPermission.trim();
            for (PlatformPlayer player : platform.onlinePlayers()) {
                if (notifyPerm.isBlank() || player.hasPermission(notifyPerm)) {
                    Text.msg(player, newCfg.msg("reloaded", "ReportSystem reloaded."));
                }
            }
            logger().info("ReportSystem reloaded.");
        } catch (Exception ex) {
            logger().error("Reload failed", ex);
        }
    }

    private void startWebServer(PluginConfig cfg) {
        var root = dataDir.resolve(cfg.htmlExportDir);
        this.webServer = new WebServer(cfg, logger(), root, authService);
        try {
            webServer.start();
        } catch (IOException io) {
            logger().warn("HTTP server failed to start: {}", io.toString());
        }
    }

    private ReportStorage createStorage(PluginConfig cfg) {
        try {
            if (cfg.storage.type == PluginConfig.StorageConfig.Type.MYSQL) {
                logger().info("Using MySQL storage backend.");
                return new MySqlReportStorage(cfg.storage.mysql, logger());
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to initialize storage backend", ex);
        }

        try {
            logger().info("Using YAML storage backend.");
            YamlReportStorage yaml = new YamlReportStorage(dataDir, logger());
            yaml.init();
            return yaml;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to initialize YAML storage directory", ex);
        }
    }
}
