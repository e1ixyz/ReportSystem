package com.example.reportsystem.platform;

import java.util.List;

/**
 * Cross-platform command executor with optional tab completion.
 */
public interface CommandHandler {

    void execute(CommandContext context);

    default List<String> suggest(CommandContext context) {
        return List.of();
    }
}
