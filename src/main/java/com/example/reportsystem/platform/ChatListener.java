package com.example.reportsystem.platform;

/**
 * Listener interface so the chat log service can receive login and chat events on any platform.
 */
public interface ChatListener {

    default void onPlayerLogin(PlatformPlayer player) {
        // optional
    }

    void onPlayerChat(PlatformPlayer player, String message);
}
