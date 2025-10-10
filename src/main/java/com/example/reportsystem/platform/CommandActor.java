package com.example.reportsystem.platform;

import net.kyori.adventure.text.Component;

import java.util.Optional;

/**
 * Abstraction over a command sender / audience so commands can run on multiple platforms.
 */
public interface CommandActor {

    /**
     * Send a pre-built Adventure component to this actor.
     */
    void sendMessage(Component component);

    /**
     * Check if this actor has the given permission node.
     */
    boolean hasPermission(String permission);

    /**
     * Obtain the actor as a player, if available.
     */
    default Optional<PlatformPlayer> asPlayer() {
        return Optional.empty();
    }
}
