package com.example.reportsystem.util;

import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.config.PluginConfig.QuickAction;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class QuickActions {

    private QuickActions() {}

    public static String render(Player player, PluginConfig config) {
        if (player == null || config == null || config.reportsActions == null) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        for (QuickAction action : config.reportsActions) {
            if (action == null) continue;

            String perm = action.permission == null ? "" : action.permission.trim();
            if (!perm.isEmpty() && !player.hasPermission(perm)) continue;

            String command = action.command == null ? "" : action.command.trim();
            if (command.isEmpty()) continue;

            String label = resolveLabel(action, config);
            String hover = resolveHover(action, config, label);
            String color = normalizeColor(action.color);
            String closing = closingTag(color);
            String click = normalizeClick(action.click);
            String escapedCommand = escapeClickArg(command);

            String segment = "<gray>[</gray>" + color
                    + "<hover:show_text:'" + Text.escape(hover) + "'><click:" + click + ":'" + escapedCommand + "'>"
                    + Text.escape(label) + "</click></hover>" + closing + "<gray>]</gray>";
            segments.add(segment);
        }

        if (segments.isEmpty()) {
            return null;
        }
        return String.join(" ", segments);
    }

    private static String resolveLabel(QuickAction action, PluginConfig config) {
        if (action.label != null && !action.label.isBlank()) {
            return action.label;
        }
        String fallback = (action.labelFallback == null || action.labelFallback.isBlank()) ? "Action" : action.labelFallback;
        String key = action.labelKey;
        if (key != null && !key.isBlank()) {
            return config.msg(key, fallback);
        }
        return fallback;
    }

    private static String resolveHover(QuickAction action, PluginConfig config, String label) {
        if (action.hover != null && !action.hover.isBlank()) {
            return action.hover;
        }
        String fallback = (action.hoverFallback == null || action.hoverFallback.isBlank()) ? label : action.hoverFallback;
        String key = action.hoverKey;
        if (key != null && !key.isBlank()) {
            return config.msg(key, fallback);
        }
        return fallback;
    }

    private static String normalizeColor(String color) {
        String trimmed = color == null ? "" : color.trim();
        return trimmed.isEmpty() ? "<aqua>" : trimmed;
    }

    private static String closingTag(String color) {
        if (color == null) return "";
        String trimmed = color.trim();
        if (!trimmed.startsWith("<") || !trimmed.endsWith(">")) return "";
        String inner = trimmed.substring(1, trimmed.length() - 1);
        if (inner.startsWith("/")) return "";
        int colon = inner.indexOf(':');
        if (colon > 0) inner = inner.substring(0, colon);
        int space = inner.indexOf(' ');
        if (space > 0) inner = inner.substring(0, space);
        if (inner.isEmpty()) return "";
        return "</" + inner + ">";
    }

    private static String normalizeClick(String raw) {
        if (raw == null) return "run_command";
        String c = raw.trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "suggest", "suggest_command" -> "suggest_command";
            case "open", "open_url", "url" -> "open_url";
            default -> "run_command";
        };
    }

    private static String escapeClickArg(String arg) {
        if (arg == null) return "";
        return Text.escape(arg).replace("'", "\\'");
    }
}
