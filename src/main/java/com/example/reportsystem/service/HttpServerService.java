package com.example.reportsystem.service;

import com.example.reportsystem.ReportSystem;
import com.example.reportsystem.config.PluginConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public class HttpServerService {

    private final ReportSystem plugin;
    private final PluginConfig config;
    private HttpServer server;

    public HttpServerService(ReportSystem plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() throws IOException {
        if (server != null) return;
        InetSocketAddress addr = new InetSocketAddress(config.httpServer.bind, config.httpServer.port);
        server = HttpServer.create(addr, 0);
        final Path root = plugin.dataDir().resolve(config.htmlExportDir).normalize();

        server.createContext(config.httpServer.basePath, new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                try {
                    String raw = ex.getRequestURI().getPath();
                    if (raw == null || raw.isEmpty() || "/".equals(raw)) {
                        send(ex, 200, "ReportSystem HTML server.");
                        return;
                    }
                    String rel = raw.startsWith("/") ? raw.substring(1) : raw;
                    Path p = root.resolve(rel).normalize();
                    if (!p.startsWith(root) || !Files.exists(p) || Files.isDirectory(p)) {
                        send(ex, 404, "Not found");
                        return;
                    }
                    String mime = guessMime(p);
                    byte[] bytes = Files.readAllBytes(p);
                    ex.getResponseHeaders().add("Content-Type", mime);
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                } catch (Exception e) {
                    send(ex, 500, "Internal error");
                }
            }
        });

        server.start();
        plugin.logger().info("HTTP server started on {}:{} serving {}", config.httpServer.bind, config.httpServer.port, root.toAbsolutePath());
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            plugin.logger().info("HTTP server stopped.");
        }
    }

    private static void send(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String guessMime(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }
}
