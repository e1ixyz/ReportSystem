package com.example.reportsystem.platform.bukkit;

import com.example.reportsystem.platform.ChatListener;
import com.example.reportsystem.platform.CommandContext;
import com.example.reportsystem.platform.CommandHandler;
import com.example.reportsystem.platform.PlatformAdapter;
import com.example.reportsystem.platform.PlatformPlayer;
import com.example.reportsystem.platform.PlatformType;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BukkitPlatformAdapter implements PlatformAdapter {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path dataDirectory;
    private final BukkitAudiences audiences;

    private final Map<String, CommandRegistration> commands = new ConcurrentHashMap<>();
    private final Map<ChatListener, BukkitChatBridge> chatBridges = new ConcurrentHashMap<>();

    public BukkitPlatformAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = LoggerFactory.getLogger("ReportSystem");
        this.dataDirectory = plugin.getDataFolder().toPath();
        this.audiences = BukkitAudiences.create(plugin);
    }

    public void close() {
        audiences.close();
    }

    @Override
    public PlatformType type() {
        return PlatformType.BUKKIT;
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

        PluginCommand command = plugin.getCommand(name);
        if (command == null) {
            logger.warn("Command '{}' is not defined in plugin.yml; skipping registration.", name);
            return;
        }

        CommandExecutor executor = (sender, cmd, label, args) -> {
            CommandContext ctx = new CommandContext(new BukkitCommandActor(sender, audiences), label, args);
            handler.execute(ctx);
            return true;
        };
        TabCompleter completer = (sender, cmd, alias, args) -> handler.suggest(new CommandContext(new BukkitCommandActor(sender, audiences), alias, args));

        command.setExecutor(executor);
        command.setTabCompleter(completer);
        commands.put(key, new CommandRegistration(command, executor, completer));
    }

    @Override
    public void unregisterCommand(String name) {
        if (name == null) return;
        CommandRegistration reg = commands.remove(name.toLowerCase());
        if (reg != null) {
            reg.command().setExecutor(null);
            reg.command().setTabCompleter(null);
        }
    }

    @Override
    public void registerChatListener(ChatListener listener) {
        BukkitChatBridge bridge = new BukkitChatBridge(listener, audiences);
        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(bridge, plugin);
        chatBridges.put(listener, bridge);
    }

    @Override
    public void unregisterChatListener(ChatListener listener) {
        BukkitChatBridge bridge = chatBridges.remove(listener);
        if (bridge != null) {
            HandlerList.unregisterAll(bridge);
        }
    }

    @Override
    public Collection<? extends PlatformPlayer> onlinePlayers() {
        return Bukkit.getOnlinePlayers().stream()
                .map(player -> new BukkitPlayerAdapter(player, audiences))
                .toList();
    }

    @Override
    public Optional<? extends PlatformPlayer> findPlayer(String username) {
        if (username == null) return Optional.empty();
        return Optional.ofNullable(Bukkit.getPlayerExact(username))
                .map(player -> new BukkitPlayerAdapter(player, audiences));
    }

    @Override
    public void runAsync(Runnable runnable) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    @Override
    public Optional<String> jumpCommandFor(String serverName) {
        return Optional.empty();
    }

    private record CommandRegistration(PluginCommand command, CommandExecutor executor, TabCompleter completer) {
    }
}
