package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.Report;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class Notifier {

    private final ReportSystem plugin;
    private PluginConfig config;

    public Notifier(ReportSystem plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setConfig(PluginConfig config) { this.config = config; }

    public void notifyNew(Report r, String reason) {
        send("🆕 **New Report #"+r.id+"** ("+r.typeDisplay+" / "+r.categoryDisplay+")\n"+
             "**Reported:** "+r.reported+"  •  **By:** "+r.reporter+"\n"+
             "**Reason:** "+truncate(reason)+"\n"+
             "**Count:** "+r.count);
    }

    public void notifyClosed(Report r) {
        send("✅ **Closed Report #"+r.id+"** ("+r.typeDisplay+" / "+r.categoryDisplay+") — "+r.reported+
             (r.assignee != null ? " • **Assignee:** "+r.assignee : ""));
    }

    public void notifyReopened(Report r) {
        send("♻️ **Reopened Report #"+r.id+"** ("+r.typeDisplay+" / "+r.categoryDisplay+") — "+r.reported);
    }

    public void notifyAssigned(Report r, String staff) {
        send("👤 **Assigned Report #"+r.id+"** to **"+staff+"** — "+r.typeDisplay+"/"+r.categoryDisplay+" ("+r.reported+")");
    }

    public void notifyUnassigned(Report r) {
        send("👤 **Unassigned Report #"+r.id+"** — "+r.typeDisplay+"/"+r.categoryDisplay+" ("+r.reported+")");
    }

    private void send(String content) {
        if (config == null || config.discord == null || !config.discord.enabled) return;
        String url = config.discord.webhookUrl;
        if (url == null || url.isBlank()) return;

        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(config.discord.timeoutMs);
            con.setReadTimeout(config.discord.timeoutMs);
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");

            String json = "{\"username\":\""+escape(config.discord.username)+"\""
                    + (config.discord.avatarUrl != null && !config.discord.avatarUrl.isBlank() ? ",\"avatar_url\":\""+escape(config.discord.avatarUrl)+"\"" : "")
                    + ",\"content\":\""+escape(content)+"\"}";

            try (OutputStream os = con.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) {
                plugin.logger().warn("Discord webhook responded with status {}", code);
            }
        } catch (Exception e) {
            plugin.logger().warn("Failed to deliver Discord webhook: {}", e.toString());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 350 ? s.substring(0, 347) + "..." : s;
    }
}
