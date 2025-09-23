package com.example.reportsystem.service;

import com.example.reportsystem.config.PluginConfig;
import org.slf4j.Logger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Tiny embedded HTTP + simple cookie auth protecting the html-logs.
 * Behind Cloudflare Tunnel you can route / to this server.
 */
public class WebServer {
    private final PluginConfig cfg;
    private final Logger log;
    private final Path root;             // html-logs dir
    private final AuthService auth;
    private HttpServer http;

    public WebServer(PluginConfig cfg, Logger log, Path root, AuthService auth) {
        this.cfg = cfg; this.log = log; this.root = root; this.auth = auth;
    }

    public void start() throws IOException {
        if (!cfg.httpServer.enabled) return;
        InetSocketAddress addr = new InetSocketAddress(cfg.httpServer.bind, cfg.httpServer.port);
        http = HttpServer.create(addr, 0);

        // Public endpoints
        http.createContext("/login", this::handleLogin);          // GET form / POST code
        http.createContext("/logout", this::handleLogout);

        // Everything else is protected
        http.createContext("/", this::handleProtectedStatic);

        http.setExecutor(null);
        http.start();
        log.info("HTTP server started on {}:{} serving {}", cfg.httpServer.bind, cfg.httpServer.port, root);
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
            log.info("HTTP server stopped.");
        }
    }

    /* ----------------- handlers ----------------- */

    private void handleLogin(HttpExchange ex) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                String ret = getQueryParam(ex, "return");
                respondHtml(ex, 200, loginForm(null, ret));
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                Map<String, String> form = parseForm(ex);
                String code = form.getOrDefault("code", "").trim();
                String who  = form.getOrDefault("name", "").trim();
                String ret  = form.getOrDefault("return", "/");
                if (ret == null || ret.isBlank()) ret = "/";

                String sid = auth.redeemCode(code, who);
                if (sid == null || !auth.looksSigned(sid)) {
                    respondHtml(ex, 401, loginForm("Invalid or expired code.", ret));
                    return;
                }
                Headers h = ex.getResponseHeaders();
                h.add("Set-Cookie", cookie(cfg.auth.cookieName, sid, cfg.auth.sessionTtlMinutes));
                h.add("Location", normalizeReturn(ret));
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            sendStatus(ex, 405, "Method Not Allowed");
        } catch (Throwable t) {
            log.warn("Login handler error: {}", t.toString());
            safe500(ex);
        }
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        try {
            String sid = readCookie(ex, cfg.auth.cookieName);
            if (sid != null) auth.revoke(sid);
            Headers h = ex.getResponseHeaders();
            h.add("Set-Cookie", cfg.auth.cookieName + "=; Max-Age=0; Path=/; SameSite=Lax; HttpOnly");
            h.add("Location", "/login");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } catch (Throwable t) {
            log.warn("Logout handler error: {}", t.toString());
            safe500(ex);
        }
    }

    private void handleProtectedStatic(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (isOpen(path)) { serveStatic(ex, path); return; }

            String sid = readCookie(ex, cfg.auth.cookieName);
            var session = (cfg.auth.enabled) ? auth.validate(sid) : null;

            if (cfg.auth.enabled && session == null) {
                // redirect to login with return=<original path + query>
                String original = ex.getRequestURI().getPath();
                String qs = ex.getRequestURI().getQuery();
                if (qs != null && !qs.isBlank()) original += "?" + qs;
                Headers h = ex.getResponseHeaders();
                h.add("Location", "/login?return=" + URLEncoder.encode(original, StandardCharsets.UTF_8));
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            }
            serveStatic(ex, path);
        } catch (Throwable t) {
            log.warn("Static handler error: {}", t.toString());
            safe500(ex);
        }
    }

    /* ----------------- helpers ----------------- */

    private boolean isOpen(String path) {
        if (!cfg.auth.enabled) return true;
        for (String p : cfg.auth.openPaths) {
            if (path.equals(p) || path.startsWith(p)) return true;
        }
        return false;
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (decoded.equals("/")) decoded = "/index.html"; // optional landing page
        Path target = safeResolve(decoded);
        if (target == null || !Files.exists(target) || !Files.isRegularFile(target)) {
            sendStatus(ex, 404, "Not Found");
            return;
        }
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", mime(target));
        h.add("Cache-Control", "no-store");
        long len = Files.size(target);
        ex.sendResponseHeaders(200, len);
        try (OutputStream os = ex.getResponseBody()) {
            Files.copy(target, os);
        }
    }

    private Path safeResolve(String uriPath) {
        Path p = root.resolve(uriPath.substring(1)).normalize();
        if (!p.startsWith(root)) return null; // prevent traversal
        return p;
    }

    private static String mime(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            String k = i > 0 ? URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8) : kv;
            String v = i > 0 ? URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8) : "";
            out.put(k, v);
        }
        return out;
    }

    private String readCookie(HttpExchange ex, String name) {
        List<String> cookies = ex.getRequestHeaders().getOrDefault("Cookie", List.of());
        for (String line : cookies) {
            for (String c : line.split(";")) {
                String[] kv = c.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        }
        return null;
    }

    private String cookie(String name, String value, int ttlMinutes) {
        int maxAge = Math.max(60, ttlMinutes * 60);
        return name + "=" + value + "; Max-Age=" + maxAge + "; Path=/; SameSite=Lax; HttpOnly";
    }

    private void respondHtml(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "text/html; charset=utf-8");
        h.add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendStatus(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void safe500(HttpExchange ex) throws IOException {
        try { sendStatus(ex, 500, "Internal Server Error"); }
        catch (Throwable ignored) { try { ex.close(); } catch (Throwable __) {} }
    }

    private String getQueryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = (i > 0) ? kv.substring(0,i) : kv;
            if (name.equals(k)) {
                return (i > 0) ? URLDecoder.decode(kv.substring(i+1), StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private String normalizeReturn(String ret) {
        if (ret == null || ret.isBlank()) return "/";
        // Only allow local relative paths for safety
        if (!ret.startsWith("/")) return "/";
        return ret;
    }

    /**
     * Build the HTML for the login page WITHOUT using String.format / formatted,
     * so % characters in CSS are safe.
     */
    private String loginForm(String error, String ret) {
        String errBlock = (error == null)
                ? ""
                : "<div style='color:#c33;margin-bottom:10px;'>" + escape(error) + "</div>";
        String hiddenReturn = (ret == null || ret.isBlank())
                ? ""
                : "<input type='hidden' name='return' value='" + escape(ret) + "'>";

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n")
          .append("<html><head><meta charset=\"utf-8\"><title>ReportSystem Login</title>\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
          .append("<style>\n")
          .append("body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Arial,sans-serif;background:#0f1115;color:#e6e6e6;display:flex;min-height:100vh;align-items:center;justify-content:center}\n")
          .append(".card{background:#151924;border:1px solid #252b3a;border-radius:14px;padding:22px;max-width:420px;width:92%}\n")
          .append("label{display:block;margin:8px 0 4px;color:#aab; font-size:14px}\n")
          .append("input{width:100%;padding:10px;border-radius:10px;border:1px solid #3a4157;background:#0f1320;color:#fff}\n")
          .append("button{margin-top:14px;width:100%;padding:10px 12px;border-radius:10px;background:#2563eb;border:0;color:#fff;font-weight:600;cursor:pointer}\n")
          .append("a{color:#9cf}\n")
          .append("</style></head>\n")
          .append("<body><div class=\"card\">\n")
          .append("<h2>ReportSystem Login</h2>\n")
          .append(errBlock).append("\n")
          .append("<p>Run <code>/reports auth</code> in-game to get a one-time code.</p>\n")
          .append("<form method=\"post\" action=\"/login\">\n")
          .append(hiddenReturn).append("\n")
          .append("<label for=\"name\">Minecraft Name (optional)</label>\n")
          .append("<input id=\"name\" name=\"name\" placeholder=\"Your IGN\">\n")
          .append("<label for=\"code\">One-time Code</label>\n")
          .append("<input id=\"code\" name=\"code\" placeholder=\"e.g. 123456\" autofocus>\n")
          .append("<button type=\"submit\">Sign in</button>\n")
          .append("</form>\n")
          .append("<p style=\"margin-top:12px;\"><a href=\"/logout\">Logout</a></p>\n")
          .append("</div></body></html>\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;");
    }
}
