package com.example.reportsystem.model;

public class ChatMessage {
    public long time;
    public String player;
    public String server;
    public String message;

    public ChatMessage() {}

    // IMPORTANT: keep this exact parameter order everywhere.
    public ChatMessage(long time, String player, String server, String message) {
        this.time = time;
        this.player = player;
        this.server = server;
        this.message = message;
    }
}