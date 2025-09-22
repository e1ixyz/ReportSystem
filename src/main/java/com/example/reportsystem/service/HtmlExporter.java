package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.example.reportsystem.model.ChatMessage;
import com.example.reportsystem.model.Report;
import com.example.reportsystem.util.TimeUtil;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class HtmlExporter {

    private final ReportSystem plugin;
    private final PluginConfig config;

    public HtmlExporter(ReportSystem plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /** Exports to: <dataDir>/<htmlExportDir>/<id>/index.html */
    public Path export(Report r) throws IOException {
        Path out = plugin
                .dataDir()
                .resolve(config.htmlExportDir)
                .resolve(String.valueOf(r.id))
                .resolve("index.html");
        Files.createDirectories(out.getParent());

        try (Writer w = Files.newBufferedWriter(out)) {
            w.write("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Report #%ID% Chat Log</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
 body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Cantarell,Noto Sans,sans-serif;max-width:900px;margin:2rem auto;padding:0 1rem;background:#0b0d10;color:#e8edf2}
 h1{font-size:1.5rem;margin-bottom:0.5rem}
 .meta{color:#9aa7b2;margin-bottom:1rem}
 .entry{padding:0.5rem 0;border-bottom:1px solid #1c232b}
 .time{color:#7f8b96;margin-right:0.5rem;white-space:nowrap}
 .name{font-weight:600}
 .server{color:#9aa7b2;margin-left:0.5rem;font-size:0.9rem}
 .msg{display:block;margin-top:0.2rem;white-space:pre-wrap}
 a{color:#7cc4ff}
</style>
</head>
<body>
<h1>Report #%ID% — %TYPE% / %CAT%</h1>
<div class="meta">
  <div><b>Reported:</b> %REPORTED% &nbsp; <b>By:</b> %REPORTER%</div>
  <div><b>Created:</b> %WHEN% &nbsp; <b>Count:</b> %COUNT% &nbsp; <b>Status:</b> %STATUS%</div>
</div>
<div id="log">
%ROWS%
</div>
</body>
</html>
""".replace("%ID%", String.valueOf(r.id))
    .replace("%TYPE%", r.typeDisplay)
    .replace("%CAT%", r.categoryDisplay)
    .replace("%REPORTED%", safe(r.reported))
    .replace("%REPORTER%", safe(r.reporter))
    .replace("%WHEN%", TimeUtil.formatDateTime(r.timestamp))
    .replace("%COUNT%", String.valueOf(r.count))
    .replace("%STATUS%", r.status.name())
    .replace("%ROWS%", buildRows(r)));
        }
        return out;
    }

    private String buildRows(Report r) {
        if (r.chat == null || r.chat.isEmpty()) {
            return "<div class=\"entry\"><span class=\"time\">—</span><span class=\"name\">(no messages)</span></div>";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : r.chat) {
            sb.append("<div class=\"entry\">")
              .append("<span class=\"time\">").append(TimeUtil.formatTime(m.time)).append("</span>")
              .append("<span class=\"name\">").append(safe(m.player)).append("</span>")
              .append("<span class=\"server\">@ ").append(safe(m.server)).append("</span>")
              .append("<span class=\"msg\">").append(safe(m.message)).append("</span>")
              .append("</div>\n");
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
