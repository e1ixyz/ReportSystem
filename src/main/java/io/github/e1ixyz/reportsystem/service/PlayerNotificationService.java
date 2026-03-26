package io.github.e1ixyz.reportsystem.service;

import io.github.e1ixyz.reportsystem.ReportSystem;
import io.github.e1ixyz.reportsystem.config.PluginConfig;
import io.github.e1ixyz.reportsystem.model.Report;
import io.github.e1ixyz.reportsystem.util.Text;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class PlayerNotificationService {

    private final ReportSystem plugin;
    private final ReportManager mgr;
    private volatile PluginConfig config;

    public PlayerNotificationService(ReportSystem plugin, ReportManager mgr, PluginConfig config) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.config = config;
    }

    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        deliverPendingResolvedNotifications(event.getPlayer());
    }

    public void deliverPendingResolvedNotificationsIfOnline(String username) {
        findOnlinePlayer(username).ifPresent(this::deliverPendingResolvedNotifications);
    }

    public void deliverPendingResolvedNotifications(Player player) {
        if (player == null) return;

        List<Report> pending = mgr.consumePendingReporterResolutionNotifications(player.getUsername());
        if (pending.isEmpty()) {
            return;
        }

        PluginConfig snapshot = this.config;
        String template = snapshot == null
                ? "Report #%id% has been marked as resolved."
                : snapshot.msg("report-resolved-notify", "Report #%id% has been marked as resolved.");

        for (Report report : pending) {
            Text.msg(player, template.replace("%id%", String.valueOf(report.id)));
        }
    }

    private Optional<Player> findOnlinePlayer(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String needle = username.toLowerCase(Locale.ROOT);
        return plugin.proxy().getAllPlayers().stream()
                .filter(player -> player.getUsername().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }
}
