package com.example.reportsystem.platform.velocity;

import com.example.reportsystem.platform.ChatListener;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;

final class VelocityChatBridge {

    private final ChatListener delegate;

    VelocityChatBridge(ChatListener delegate) {
        this.delegate = delegate;
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        delegate.onPlayerLogin(new VelocityPlayerAdapter(event.getPlayer()));
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        delegate.onPlayerChat(new VelocityPlayerAdapter(event.getPlayer()), event.getMessage());
    }
}
