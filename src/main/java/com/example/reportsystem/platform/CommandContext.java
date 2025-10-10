package com.example.reportsystem.platform;

/**
 * Simple wrapper for execution context shared between platforms.
 */
public record CommandContext(CommandActor actor, String alias, String[] args) {

    public CommandContext(CommandActor actor, String[] args) {
        this(actor, "", args);
    }
}
