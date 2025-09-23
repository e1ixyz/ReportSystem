package com.example.reportsystem;

import com.example.reportsystem.commands.ReportsCommand;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.service.AuthService;
import com.example.reportsystem.service.ChatLogService;
import com.example.reportsystem.service.ReportManager;
import com.example.reportsystem.service.WebServer;
import com.example.reportsystem.util.Text;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
        id = "reportsystem",
        name = "ReportSystem",
        version = "2.2.0",
        description = "Proxy-side report & chat-log system with staff auth codes",
        authors = {"you"}
)
public final class ReportSystem {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig config;
    private ReportManager reportManager;
    private ChatLogService chatLogService;


    private Object notifier; // keep type-loose to avoid forcing a specific class here

    // NEW: web auth + tiny HTTP server
    private AuthService authService;
    private WebServer webServer;

    @Inject
    public ReportSystem(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent e) {
        try {
            Files.createDirectories(dataDirectory);
            this.config = PluginConfig.loadOrCreate(dataDirectory, logger);

            // Core services
            this.reportManager = new ReportManager(config, logger, dataDirectory);
            this.chatLogService = new ChatLogService(server, reportManager, config, logger);

            // this.notifier = new Notifier(...);

            // NEW: auth + web server for HTML logs
            this.authService = new AuthService(config, logger);
            Path htmlRoot = dataDirectory.resolve(config.htmlExportDir);
            Files.createDirectories(htmlRoot);
            this.webServer = new WebServer(config, logger, htmlRoot, authService);
            try {
                webServer.start();
            } catch (Exception ex) {
                logger.error("Failed to start embedded HTTP server", ex);
            }

            // Commands
            registerCommands();

            // Listeners (chat capture)
            server.getEventManager().register(this, chatLogService);

            logger.info("ReportSystem enabled.");
        } catch (Exception ex) {
            logger.error("Failed to initialize ReportSystem", ex);
        }
    }

    private void registerCommands() {
        // /reports command (now includes 'auth' and 'logoutall')
        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("reports").build(),
                new ReportsCommand(this, reportManager, config, authService)
        );

        // Keep existing registrations for /report and /reporthistory elsewhere.
    }

    /** Reload from disk and restart services; used by /reports reload and elsewhere. */
    public synchronized void reload() {
        try {
            // Stop HTTP first
            if (webServer != null) {
                webServer.stop();
            }

            // Reload config and apply
            this.config = PluginConfig.loadOrCreate(dataDirectory, logger);
            if (reportManager != null) reportManager.applyConfig(config);
            if (chatLogService != null) chatLogService.applyConfig(config);

            // Recreate auth so new secrets/TTLs apply
            this.authService = new AuthService(config, logger);

            // Restart HTTP server with new settings
            Path htmlRoot = dataDirectory.resolve(config.htmlExportDir);
            Files.createDirectories(htmlRoot);
            this.webServer = new WebServer(config, logger, htmlRoot, authService);
            try {
                webServer.start();
            } catch (Exception ex) {
                logger.error("Failed to start embedded HTTP server after reload", ex);
            }

            // Re-register /reports to pick up any permission/message changes
            server.getCommandManager().unregister("reports");
            server.getCommandManager().register(
                    server.getCommandManager().metaBuilder("reports").build(),
                    new ReportsCommand(this, reportManager, config, authService)
            );

            Text.broadcastIf(server, config.notifyPermission, config.msg("reloaded", "<green>ReportSystem reloaded.</green>"));
            logger.info("ReportSystem reloaded.");
        } catch (Exception ex) {
            logger.error("Error during ReportSystem reload", ex);
        }
    }

    /* ---------------------------------------------------
       Accessors retained for existing code
       --------------------------------------------------- */

    public ProxyServer proxy() { return server; }

    public Object notifier() { return notifier; }

    public PluginConfig config() { return config; }

    public Logger logger() { return logger; }

    public Path dataDirectory() { return dataDirectory; }
}
