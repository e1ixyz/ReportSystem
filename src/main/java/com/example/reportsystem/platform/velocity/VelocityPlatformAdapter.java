package com.example.reportsystem.platform.velocity;

import com.example.reportsystem.platform.ChatListener;
import com.example.reportsystem.platform.CommandContext;
import com.example.reportsystem.platform.CommandHandler;
import com.example.reportsystem.platform.PlatformAdapter;
import com.example.reportsystem.platform.PlatformPlayer;
import com.example.reportsystem.platform.PlatformType;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity implementation of the platform bridge.
 */
public final class VelocityPlatformAdapter implements PlatformAdapter {

    private final Object pluginInstance;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final CommandManager commandManager;
    private final EventManager eventManager;

    private final Map<String, RegisteredCommand> commands = new ConcurrentHashMap<>();
    private final Map<ChatListener, Object> chatListeners = new ConcurrentHashMap<>();

    public VelocityPlatformAdapter(Object pluginInstance, ProxyServer proxy, Logger logger, Path dataDirectory) {
        this.pluginInstance = pluginInstance;
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.commandManager = proxy.getCommandManager();
        this.eventManager = proxy.getEventManager();
    }

    @Override
    public PlatformType type() {
        return PlatformType.VELOCITY;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public void registerCommand(String name, CommandHandler handler, String... aliases) {
        String key = name.toLowerCase();
        unregisterCommand(name);

        CommandMeta meta = commandManager.metaBuilder(name).aliases(aliases).build();
        VelocityCommandWrapper wrapper = new VelocityCommandWrapper(handler);
        commandManager.register(meta, wrapper);
        commands.put(key, new RegisteredCommand(meta, wrapper));
    }

    @Override
    public void unregisterCommand(String name) {
        if (name == null) return;
        RegisteredCommand reg = commands.remove(name.toLowerCase());
        if (reg != null) {
            commandManager.unregister(reg.meta());
        }
    }

    @Override
    public void registerChatListener(ChatListener listener) {
        VelocityChatBridge bridge = new VelocityChatBridge(listener);
        eventManager.register(pluginInstance, bridge);
        chatListeners.put(listener, bridge);
    }

    @Override
    public void unregisterChatListener(ChatListener listener) {
        Object bridge = chatListeners.remove(listener);
        if (bridge != null) {
            eventManager.unregisterListener(bridge);
        }
    }

    @Override
    public Collection<? extends PlatformPlayer> onlinePlayers() {
        return proxy.getAllPlayers().stream().map(VelocityPlayerAdapter::new).toList();
    }

    @Override
    public Optional<? extends PlatformPlayer> findPlayer(String username) {
        if (username == null) return Optional.empty();
        return proxy.getPlayer(username).map(VelocityPlayerAdapter::new);
    }

    @Override
    public void runAsync(Runnable runnable) {
        proxy.getScheduler().buildTask(pluginInstance, runnable).schedule();
    }

    @Override
    public Optional<String> jumpCommandFor(String serverName) {
        if (serverName == null || serverName.isBlank()) return Optional.empty();
        return proxy.getServer(serverName).isPresent()
                ? Optional.of("/server " + serverName)
                : Optional.empty();
    }

    private record RegisteredCommand(CommandMeta meta, VelocityCommandWrapper wrapper) {
    }

    private final class VelocityCommandWrapper implements com.velocitypowered.api.command.SimpleCommand {

        private final CommandHandler handler;

        VelocityCommandWrapper(CommandHandler handler) {
            this.handler = handler;
        }

        @Override
        public void execute(Invocation invocation) {
            CommandContext ctx = new CommandContext(
                    new VelocityCommandActor(invocation.source()),
                    invocation.alias(),
                    invocation.arguments()
            );
            handler.execute(ctx);
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            CommandContext ctx = new CommandContext(
                    new VelocityCommandActor(invocation.source()),
                    invocation.alias(),
                    invocation.arguments()
            );
            return handler.suggest(ctx);
        }
    }
}
