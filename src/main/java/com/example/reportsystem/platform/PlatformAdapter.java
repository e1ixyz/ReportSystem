package com.example.reportsystem.platform;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Describes the host platform (Velocity or Bukkit/Paper) so the core plugin can stay agnostic.
 */
public interface PlatformAdapter {

    PlatformType type();

    Logger logger();

    Path dataDirectory();

    void registerCommand(String name, CommandHandler handler, String... aliases);

    void unregisterCommand(String name);

    void registerChatListener(ChatListener listener);

    void unregisterChatListener(ChatListener listener);

    Collection<? extends PlatformPlayer> onlinePlayers();

    Optional<? extends PlatformPlayer> findPlayer(String username);

    /**
     * Execute a task asynchronously.
     */
    void runAsync(Runnable runnable);

    /**
     * Resolve a command that would jump a staff member to the given server, if applicable.
     */
    Optional<String> jumpCommandFor(String serverName);

    /**
     * Determine whether the given server exists on this platform.
     */
    default boolean serverExists(String serverName) {
        return jumpCommandFor(serverName).isPresent();
    }
}
