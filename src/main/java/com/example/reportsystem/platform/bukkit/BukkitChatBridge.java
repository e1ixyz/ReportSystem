package com.example.reportsystem.platform.bukkit;

import com.example.reportsystem.platform.ChatListener;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;

final class BukkitChatBridge implements Listener {

    private final ChatListener delegate;
    private final BukkitAudiences audiences;

    BukkitChatBridge(ChatListener delegate, BukkitAudiences audiences) {
        this.delegate = delegate;
        this.audiences = audiences;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        delegate.onPlayerLogin(new BukkitPlayerAdapter(event.getPlayer(), audiences));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        delegate.onPlayerChat(new BukkitPlayerAdapter(event.getPlayer(), audiences), event.getMessage());
    }
}
