package com.example.reportsystem.platform.velocity;

import com.example.reportsystem.platform.CommandActor;
import com.example.reportsystem.platform.PlatformPlayer;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.Optional;

final class VelocityCommandActor implements CommandActor {

    private final CommandSource source;

    VelocityCommandActor(CommandSource source) {
        this.source = source;
    }

    @Override
    public void sendMessage(Component component) {
        source.sendMessage(component);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return source.hasPermission(permission);
    }

    @Override
    public Optional<PlatformPlayer> asPlayer() {
        if (source instanceof Player player) {
            return Optional.of(new VelocityPlayerAdapter(player));
        }
        return Optional.empty();
    }
}
