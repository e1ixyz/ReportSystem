package com.example.reportsystem.platform;

import net.kyori.adventure.text.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-platform representation of an online player.
 */
public interface PlatformPlayer extends CommandActor {

    UUID uniqueId();

    String username();

    /**
     * Best-effort server name or world identifier the player currently occupies.
     */
    Optional<String> currentServerName();

    @Override
    default Optional<PlatformPlayer> asPlayer() {
        return Optional.of(this);
    }
}
