package com.example.reportsystem.service;

import com.example.reportsystem.config.PluginConfig;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthService {
    public static final class Code {
        public final String code;
        public final UUID playerUuid;
        public final String playerName;
        public final long expiresAt;
        Code(String code, UUID playerUuid, String playerName, long expiresAt) {
            this.code = code; this.playerUuid = playerUuid; this.playerName = playerName; this.expiresAt = expiresAt;
        }
        public boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    public static final class Session {
        public final String id;
        public final UUID playerUuid;
        public final String playerName;
        public volatile long expiresAt;
        Session(String id, UUID uuid, String name, long exp) {
            this.id = id; this.playerUuid = uuid; this.playerName = name; this.expiresAt = exp;
        }
        public boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private final SecureRandom rng = new SecureRandom();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final PluginConfig cfg;
    private final Logger log;

    public AuthService(PluginConfig cfg, Logger log) {
        this.cfg = cfg; this.log = log;
    }

    /** Issue a short one-time numeric code for a staff player. */
    public Code issueCodeFor(Player p) {
        int len = Math.max(4, cfg.auth.codeLength);
        String code = generateDigits(len);
        long ttl = Math.max(15_000L, cfg.auth.codeTtlSeconds * 1000L);
        Code obj = new Code(code, p.getUniqueId(), p.getUsername(), System.currentTimeMillis() + ttl);
        codes.put(code, obj);
        log.info("Auth code {} issued to {} (ttl={}s)", code, p.getUsername(), ttl / 1000);
        return obj;
    }

    /** Consume a code and create a session; returns session id or null. */
    public String redeemCode(String code, String claimedName) {
        if (code == null) return null;
        Code c = codes.remove(code);
        if (c == null || c.expired()) return null;
        // (Optional) You could also verify claimedName equals c.playerName
        long ttl = Math.max(60_000L, cfg.auth.sessionTtlMinutes * 60_000L);
        String sid = sign(randomToken());
        Session s = new Session(sid, c.playerUuid, c.playerName, System.currentTimeMillis() + ttl);
        sessions.put(sid, s);
        log.info("Session {} created for {}", sid.substring(0, 8), s.playerName);
        return sid;
    }

    /** Validate a session cookie; refresh TTL on use. */
    public Session validate(String sid) {
        if (sid == null || sid.isBlank()) return null;
        Session s = sessions.get(sid);
        if (s == null || s.expired()) { if (s != null) sessions.remove(sid); return null; }
        // sliding expiration
        long ttl = Math.max(60_000L, cfg.auth.sessionTtlMinutes * 60_000L);
        s.expiresAt = System.currentTimeMillis() + ttl;
        return s;
    }

    /** Logout a session id. */
    public void revoke(String sid) {
        if (sid != null) sessions.remove(sid);
    }

    /** Logout all sessions for a player. */
    public int revokeAllFor(UUID player) {
        int n = 0;
        for (var e : sessions.entrySet()) {
            if (Objects.equals(e.getValue().playerUuid, player)) { sessions.remove(e.getKey()); n++; }
        }
        return n;
    }

    private String generateDigits(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(rng.nextInt(10));
        return sb.toString();
    }

    private String randomToken() {
        byte[] b = new byte[24];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Cheap HMAC-ish tag so session ids aren’t trivially forgeable. */
    private String sign(String token) {
        String secret = cfg.auth.secret == null ? "default-secret" : cfg.auth.secret;
        int h = (token + "|" + secret).hashCode();
        return token + "." + Integer.toHexString(h);
    }

    /** Basic check if the session id has the right tag. */
    public boolean looksSigned(String sid) {
        int i = sid.lastIndexOf('.');
        if (i <= 0) return false;
        String token = sid.substring(0, i);
        String tag = sid.substring(i + 1);
        String secret = cfg.auth.secret == null ? "default-secret" : cfg.auth.secret;
        int h = (token + "|" + secret).hashCode();
        return tag.equalsIgnoreCase(Integer.toHexString(h));
    }

    public Map<String, Session> snapshotSessions() { return Map.copyOf(sessions); }
    public Map<String, Code> snapshotCodes() { return Map.copyOf(codes); }

    public String nowIso() { return Instant.now().toString(); }
}
