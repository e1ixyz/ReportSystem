package com.example.reportsystem.platform.bukkit;

import com.example.reportsystem.platform.CommandActor;
import com.example.reportsystem.platform.PlatformPlayer;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

final class BukkitCommandActor implements CommandActor {

    private final CommandSender sender;
    private final BukkitAudiences audiences;

    BukkitCommandActor(CommandSender sender, BukkitAudiences audiences) {
        this.sender = sender;
        this.audiences = audiences;
    }

    @Override
    public void sendMessage(Component component) {
        audiences.sender(sender).sendMessage(component);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return sender.hasPermission(permission);
    }

    @Override
    public Optional<PlatformPlayer> asPlayer() {
        if (sender instanceof Player player) {
            return Optional.of(new BukkitPlayerAdapter(player, audiences));
        }
        return Optional.empty();
    }
}
