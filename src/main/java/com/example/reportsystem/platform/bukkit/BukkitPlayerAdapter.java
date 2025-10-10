package com.example.reportsystem.platform.bukkit;

import com.example.reportsystem.platform.PlatformPlayer;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

final class BukkitPlayerAdapter implements PlatformPlayer {

    private final Player player;
    private final BukkitAudiences audiences;

    BukkitPlayerAdapter(Player player, BukkitAudiences audiences) {
        this.player = player;
        this.audiences = audiences;
    }

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String username() {
        return player.getName();
    }

    @Override
    public Optional<String> currentServerName() {
        String world = player.getWorld() != null ? player.getWorld().getName() : null;
        return world == null || world.isBlank() ? Optional.empty() : Optional.of(world);
    }

    @Override
    public void sendMessage(Component component) {
        audiences.player(player).sendMessage(component);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return player.hasPermission(permission);
    }
}
