package com.example.reportsystem.model;

public class ChatMessage {
    public final long time;
    public final String server;    // backend server name (if available)
    public final String player;    // sender name
    public final String message;

    public ChatMessage(long time, String server, String player, String message) {
        this.time = time;
        this.server = server;
        this.player = player;
        this.message = message;
    }
}
