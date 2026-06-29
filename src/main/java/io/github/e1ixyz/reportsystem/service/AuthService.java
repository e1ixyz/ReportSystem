package io.github.e1ixyz.reportsystem.service;

import io.github.e1ixyz.reportsystem.config.PluginConfig;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile PluginConfig cfg;
    private final Logger log;

    public AuthService(PluginConfig cfg, Logger log) {
        this.cfg = cfg; this.log = log;
    }

    public void setConfig(PluginConfig cfg) {
        if (cfg != null) {
            this.cfg = cfg;
        }
    }

    /** Issue a short one-time numeric code for a staff player. */
    public Code issueCodeFor(Player p) {
        PluginConfig snapshot = this.cfg;
        if (snapshot.auth.requirePermission && !p.hasPermission(snapshot.staffPermission)) return null;

        pruneExpiredCodes();
        int len = Math.max(4, snapshot.auth.codeLength);
        String code = uniqueCode(len);
        long ttl = Math.max(15_000L, snapshot.auth.codeTtlSeconds * 1000L);
        Code obj = new Code(code, p.getUniqueId(), p.getUsername(), System.currentTimeMillis() + ttl);
        codes.put(code, obj);
        log.info("Auth code {} issued to {} (ttl={}s)", code, p.getUsername(), ttl / 1000);
        return obj;
    }

    /** Consume a code and create a session; returns session id or null. */
    public String redeemCode(String code, String claimedName) {
        if (code == null) return null;
        PluginConfig snapshot = this.cfg;
        Code c = codes.remove(code);
        if (c == null || c.expired()) return null;
        if (claimedName != null && !claimedName.isBlank() && !c.playerName.equalsIgnoreCase(claimedName.trim())) {
            return null;
        }
        pruneExpiredSessions();
        long ttl = Math.max(60_000L, snapshot.auth.sessionTtlMinutes * 60_000L);
        String sid = sign(randomToken());
        Session s = new Session(sid, c.playerUuid, c.playerName, System.currentTimeMillis() + ttl);
        sessions.put(sid, s);
        log.info("Session {} created for {}", sid.substring(0, 8), s.playerName);
        return sid;
    }

    /** Validate a session cookie; refresh TTL on use. */
    public Session validate(String sid) {
        if (sid == null || sid.isBlank()) return null;
        PluginConfig snapshot = this.cfg;
        Session s = sessions.get(sid);
        if (s == null || s.expired()) { if (s != null) sessions.remove(sid); return null; }
        long ttl = Math.max(60_000L, snapshot.auth.sessionTtlMinutes * 60_000L);
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

    private String uniqueCode(int len) {
        for (int i = 0; i < 8; i++) {
            String candidate = generateDigits(len);
            if (!codes.containsKey(candidate)) {
                return candidate;
            }
        }
        return generateDigits(len);
    }

    private String randomToken() {
        byte[] b = new byte[24];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static final AtomicBoolean WARNED_DEFAULT_SECRET = new AtomicBoolean(false);

    /** Effective signing secret; warns once if it's missing or left at the default. */
    private String effectiveSecret() {
        String secret = cfg.auth.secret;
        if (secret == null || secret.isBlank() || "change-me".equals(secret) || "default-secret".equals(secret)) {
            if (WARNED_DEFAULT_SECRET.compareAndSet(false, true)) {
                log.warn("auth.secret is unset or left at the default ('change-me'); web sessions are NOT secure. "
                        + "Set a unique random auth.secret in config.yml.");
            }
            return secret == null || secret.isBlank() ? "change-me" : secret;
        }
        return secret;
    }

    private byte[] hmac(String token, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(token.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** HMAC-SHA256 tag so session ids can't be forged without the secret. */
    private String sign(String token) {
        return token + "." + HexFormat.of().formatHex(hmac(token, effectiveSecret()));
    }

    /** Constant-time check that the session id carries a valid HMAC tag. */
    public boolean looksSigned(String sid) {
        if (sid == null) return false;
        int i = sid.lastIndexOf('.');
        if (i <= 0) return false;
        String token = sid.substring(0, i);
        byte[] got;
        try { got = HexFormat.of().parseHex(sid.substring(i + 1)); }
        catch (IllegalArgumentException e) { return false; }
        return MessageDigest.isEqual(hmac(token, effectiveSecret()), got);
    }

    private void pruneExpiredCodes() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAt < now);
    }

    private void pruneExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAt < now);
    }

    public Map<String, Session> snapshotSessions() { return Map.copyOf(sessions); }
    public Map<String, Code> snapshotCodes() { return Map.copyOf(codes); }

    public String nowIso() { return Instant.now().toString(); }
}
