package com.example.reportsystem.platform.velocity;

import com.example.reportsystem.platform.PlatformPlayer;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.Optional;
import java.util.UUID;

final class VelocityPlayerAdapter implements PlatformPlayer {

    private final Player handle;

    VelocityPlayerAdapter(Player handle) {
        this.handle = handle;
    }

    @Override
    public UUID uniqueId() {
        return handle.getUniqueId();
    }

    @Override
    public String username() {
        return handle.getUsername();
    }

    @Override
    public Optional<String> currentServerName() {
        return handle.getCurrentServer().map(s -> s.getServerInfo().getName());
    }

    @Override
    public void sendMessage(Component component) {
        handle.sendMessage(component);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return handle.hasPermission(permission);
    }
}
