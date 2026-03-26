package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.commands.ReportCommand;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.config.PluginConfig.ReportTypeDef;
import com.example.reportsystem.model.ReportType;
import com.example.reportsystem.util.Text;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Guides players through filing reports via chat, one question at a time.
 */
public final class ReportMenuService {

    private final ReportSystem plugin;
    private final ReportManager reportManager;
    private final ReportCommand reportCommand;
    private volatile PluginConfig config;

    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ReportMenuService(ReportSystem plugin, ReportManager reportManager, ReportCommand reportCommand, PluginConfig config) {
        this.plugin = plugin;
        this.reportManager = reportManager;
        this.reportCommand = reportCommand;
        this.config = config;
    }

    public void setConfig(PluginConfig cfg) {
        this.config = cfg;
        sessions.values().forEach(session -> session.setConfig(cfg));
    }

    public boolean openMenu(Player player) {
        PluginConfig snapshot = this.config;
        if (snapshot == null || !snapshot.reportMenuEnabled) {
            Text.msg(player, snapshot == null ? defaultMessage("report-menu-disabled", "<red>The report menu is unavailable.</red>")
                    : snapshot.msg("report-menu-disabled", "<red>The report menu is currently disabled.</red>"));
            return false;
        }

        if (snapshot.reportTypes.isEmpty()) {
            Text.msg(player, snapshot.msg("report-menu-no-types", "<red>No report types are configured.</red>"));
            return false;
        }

        if (!reportCommand.enforceCooldown(player)) {
            return false;
        }

        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) {
            Text.msg(player, snapshot.msg("report-menu-already-open", "<red>You already have a report menu open.</red>"));
            return false;
        }

        Session session = new Session(player, snapshot);
        sessions.put(id, session);
        session.start();
        return true;
    }

    public void cancel(Player player, boolean notify) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null && notify) {
            Text.msg(player, message(session.config, "report-menu-cancelled", "<gray>Report menu cancelled.</gray>"));
        }
    }

    public void clearSessions() {
        sessions.clear();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        PluginConfig snapshot = this.config;
        if (snapshot == null || !snapshot.reportMenuEnabled) {
            sessions.remove(player.getUniqueId());
            return;
        }

        event.setResult(PlayerChatEvent.ChatResult.denied());
        session.handleResponse(event.getMessage());
    }

    private static String defaultMessage(String key, String def) {
        return def;
    }

    private static String message(PluginConfig config, String key, String def) {
        return config == null ? def : config.msg(key, def);
    }

    private final class Session {
        private final Player player;
        private final UUID playerId;
        private PluginConfig config;
        private Stage stage = Stage.TYPE;
        private String typeId;
        private ReportTypeDef typeDef;
        private String categoryId;
        private String targetName;

        private Session(Player player, PluginConfig config) {
            this.player = player;
            this.playerId = player.getUniqueId();
            this.config = config;
        }

        private void setConfig(PluginConfig cfg) {
            this.config = cfg;
        }

        private void start() {
            String startMsg = message(config, "report-menu-start",
                    "<green>Opening the report menu. Type <white>%cancel%</white> to cancel.</green>")
                    .replace("%cancel%", cancelKeyword());
            Text.msg(player, startMsg);
            promptType();
        }

        private void handleResponse(String rawMessage) {
            String input = rawMessage == null ? "" : rawMessage.trim();
            if (input.equalsIgnoreCase(cancelKeyword())) {
                Text.msg(player, message(config, "report-menu-cancelled", "<gray>Report menu cancelled.</gray>"));
                sessions.remove(playerId);
                return;
            }

            switch (stage) {
                case TYPE -> handleType(input);
                case CATEGORY -> handleCategory(input);
                case TARGET -> handleTarget(input);
                case REASON -> handleReason(input);
            }
        }

        private void handleType(String input) {
            if (input.isBlank()) {
                promptType();
                return;
            }

            Optional<TypeChoice> choice = chooseType(input);
            if (choice.isEmpty()) {
                Text.msg(player, message(config, "report-menu-invalid-type",
                        "<red>That's not a valid report type. Options: %options%</red>")
                        .replace("%options%", formatTypeOptions()));
                promptType();
                return;
            }

            this.typeId = choice.get().id();
            this.typeDef = choice.get().definition();
            this.stage = Stage.CATEGORY;
            promptCategory();
        }

        private void handleCategory(String input) {
            if (typeDef == null) {
                stage = Stage.TYPE;
                promptType();
                return;
            }

            if (input.isBlank()) {
                promptCategory();
                return;
            }

            Optional<CategoryChoice> choice = chooseCategory(input);
            if (choice.isEmpty()) {
                Text.msg(player, message(config, "report-menu-invalid-category",
                        "<red>That category doesn't match %type%. Options: %options%</red>")
                        .replace("%type%", display(typeDef.display, typeId))
                        .replace("%options%", formatCategoryOptions()));
                promptCategory();
                return;
            }

            this.categoryId = choice.get().id();
            if (isPlayerType()) {
                this.stage = Stage.TARGET;
                promptTarget();
            } else {
                this.targetName = "N/A";
                this.stage = Stage.REASON;
                promptReason();
            }
        }

        private void handleTarget(String input) {
            if (!isPlayerType()) {
                stage = Stage.REASON;
                promptReason();
                return;
            }

            if (input.isBlank()) {
                promptTarget();
                return;
            }

            String reporter = player.getUsername();
            if (!config.allowSelfReport && reporter.equalsIgnoreCase(input.trim())) {
                Text.msg(player, config.msg("self-report-denied", "<red>You cannot report yourself.</red>"));
                promptTarget();
                return;
            }

            this.targetName = input.trim();
            this.stage = Stage.REASON;
            promptReason();
        }

        private void handleReason(String input) {
            String reason = input == null ? "" : input.trim();
            if (reason.isBlank()) {
                Text.msg(player, message(config, "report-menu-reason-required",
                        "<red>Please provide a brief reason.</red>"));
                promptReason();
                return;
            }

            ReportType rt = reportManager.resolveType(typeId, categoryId);
            if (rt == null) {
                stage = Stage.TYPE;
                promptType();
                return;
            }

            boolean submitted = reportCommand.submitFromMenu(player, rt, targetName, reason);
            if (submitted) {
                sessions.remove(playerId);
            } else {
                // Fallback: if submission failed, stay on current stage so the player can retry.
                promptReason();
            }
        }

        private void promptType() {
            Text.msg(player, message(config, "report-menu-prompt-type",
                    "<yellow>What type of report? %options%</yellow>")
                    .replace("%options%", formatTypeOptions()));
        }

        private void promptCategory() {
            Text.msg(player, message(config, "report-menu-prompt-category",
                    "<yellow>Which category fits best? %options%</yellow>")
                    .replace("%options%", formatCategoryOptions()));
        }

        private void promptTarget() {
            Text.msg(player, message(config, "report-menu-prompt-target",
                    "<yellow>Who are you reporting? Type a player name.</yellow>"));
        }

        private void promptReason() {
            Text.msg(player, message(config, "report-menu-prompt-reason",
                    "<yellow>Please describe the reason for this report.</yellow>"));
        }

        private Optional<TypeChoice> chooseType(String input) {
            List<TypeChoice> choices = typeChoices();
            if (choices.isEmpty()) return Optional.empty();

            TypeChoice byIndex = parseIndex(input, choices);
            if (byIndex != null) return Optional.of(byIndex);

            String normalized = normalize(input);
            return choices.stream()
                    .filter(choice -> normalize(choice.id()).equals(normalized)
                            || normalize(display(choice.definition().display, choice.id())).equals(normalized))
                    .findFirst();
        }

        private Optional<CategoryChoice> chooseCategory(String input) {
            List<CategoryChoice> choices = categoryChoices();
            if (choices.isEmpty()) return Optional.empty();

            CategoryChoice byIndex = parseIndex(input, choices);
            if (byIndex != null) return Optional.of(byIndex);

            String normalized = normalize(input);
            return choices.stream()
                    .filter(choice -> normalize(choice.id()).equals(normalized)
                            || normalize(choice.display()).equals(normalized))
                    .findFirst();
        }

        private List<TypeChoice> typeChoices() {
            PluginConfig snapshot = this.config;
            if (snapshot == null || snapshot.reportTypes.isEmpty()) return List.of();

            List<TypeChoice> list = new ArrayList<>();
            int index = 1;
            for (Map.Entry<String, ReportTypeDef> entry : snapshot.reportTypes.entrySet()) {
                list.add(new TypeChoice(index++, entry.getKey(), entry.getValue()));
            }
            return list;
        }

        private List<CategoryChoice> categoryChoices() {
            if (typeDef == null || typeDef.categories == null || typeDef.categories.isEmpty()) {
                return List.of();
            }
            List<CategoryChoice> list = new ArrayList<>();
            int index = 1;
            for (Map.Entry<String, String> entry : typeDef.categories.entrySet()) {
                String id = entry.getKey();
                String display = entry.getValue();
                list.add(new CategoryChoice(index++, id, display(display, id)));
            }
            return list;
        }

        private String formatTypeOptions() {
            List<TypeChoice> choices = typeChoices();
            if (choices.isEmpty()) return "None";
            List<String> parts = new ArrayList<>(choices.size());
            for (TypeChoice choice : choices) {
                parts.add(choice.index() + ") " + display(choice.definition().display, choice.id()));
            }
            return String.join(", ", parts);
        }

        private String formatCategoryOptions() {
            List<CategoryChoice> choices = categoryChoices();
            if (choices.isEmpty()) return "None";
            List<String> parts = new ArrayList<>(choices.size());
            for (CategoryChoice choice : choices) {
                parts.add(choice.index() + ") " + choice.display());
            }
            return String.join(", ", parts);
        }

        private boolean isPlayerType() {
            return typeId != null && typeId.equalsIgnoreCase("player");
        }

        private String cancelKeyword() {
            PluginConfig snapshot = this.config;
            String keyword = snapshot == null ? "cancel" : snapshot.reportMenuCancelKeyword;
            return keyword == null || keyword.isBlank() ? "cancel" : keyword;
        }

        private <T extends Choice> T parseIndex(String input, List<T> choices) {
            try {
                int idx = Integer.parseInt(input.trim());
                if (idx >= 1 && idx <= choices.size()) {
                    return choices.get(idx - 1);
                }
            } catch (NumberFormatException ignored) {
            }
            return null;
        }
    }

    private interface Choice {
        int index();
    }

    private record TypeChoice(int index, String id, ReportTypeDef definition) implements Choice { }

    private record CategoryChoice(int index, String id, String display) implements Choice { }

    private enum Stage {
        TYPE,
        CATEGORY,
        TARGET,
        REASON
    }

    private static String normalize(String input) {
        String lower = input == null ? "" : input.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static String display(String display, String fallback) {
        if (display == null || display.isBlank()) {
            return fallback;
        }
        return display;
    }
}
