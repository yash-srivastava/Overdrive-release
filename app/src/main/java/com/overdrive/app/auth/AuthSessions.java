package com.overdrive.app.auth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browser sign-ins, daemon side: one entry per login JWT ({@code jti}) so it can be listed and signed out.
 * Kept in an owner-only file next to the auth secret. Tokens without a jti (the app's own, logins from
 * before this existed) are not tracked and keep working until they expire or the secret changes.
 */
public final class AuthSessions {

    private static final String FILE = com.overdrive.app.util.ScratchRoot.path(".overdrive_auth_sessions.json");
    private static final int MAX_SESSIONS = 64;
    private static final int MAX_LABEL = 48;
    // Another process (or a restart) may have changed the file; checking it on every request is not needed.
    private static final long RECHECK_MS = 2_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Entry {
        final String id;
        final long createdSec;
        final long expiresSec;
        final String label;
        final String keyTag;
        long lastSeenSec;

        Entry(String id, long createdSec, long expiresSec, String label, String keyTag, long lastSeenSec) {
            this.id = id;
            this.createdSec = createdSec;
            this.expiresSec = expiresSec;
            this.label = label;
            this.keyTag = keyTag;
            this.lastSeenSec = lastSeenSec;
        }
    }

    // Guarded by AuthSessions.class.
    private static final Map<String, Entry> sessions = new LinkedHashMap<>();
    private static long loadedMtime = -1L;   // -1: never read; 0: no file
    private static boolean checkedOnce = false;
    private static long checkedAtNanos = 0L;

    private AuthSessions() {}

    /** A new sign-in's id, or null when it can't be saved (the caller then issues an untracked token, as before). */
    static String register(String label, long createdSec, long expiresSec, String secret) {
        if (secret == null || secret.isEmpty()) return null;
        synchronized (AuthSessions.class) {
            refreshLocked(true);
            Map<String, Entry> before = new LinkedHashMap<>(sessions);
            String tag = keyTag(secret);
            pruneExpiredLocked(createdSec);
            String id = newId();
            sessions.put(id, new Entry(id, createdSec, expiresSec, cleanLabel(label), tag, createdSec));
            while (sessions.size() > MAX_SESSIONS) evictOneLocked(tag, id);
            if (!persistLocked()) {
                sessions.clear();
                sessions.putAll(before);
                return null;
            }
            return id;
        }
    }

    /** Whether a tracked sign-in is still valid (the JWT's signature and expiry are checked by the caller). */
    static boolean isActive(String id) {
        if (!validId(id)) return false;
        long now = System.currentTimeMillis() / 1000;
        synchronized (AuthSessions.class) {
            refreshLocked(false);
            Entry e = sessions.get(id);
            if (e == null || e.expiresSec <= now) return false;
            e.lastSeenSec = now;
            return true;
        }
    }

    /** Signs one browser out; false when it isn't listed or the change couldn't be saved. */
    public static boolean revoke(String id) {
        if (!validId(id)) return false;
        synchronized (AuthSessions.class) {
            refreshLocked(true);
            Entry removed = sessions.remove(id);
            if (removed == null) return false;
            if (!persistLocked()) {
                sessions.put(id, removed);
                return false;
            }
            return true;
        }
    }

    /** Signs out every tracked browser except {@code keepId} (may be null); -1 when the change couldn't be saved. */
    public static int revokeAllExcept(String keepId) {
        synchronized (AuthSessions.class) {
            refreshLocked(true);
            Map<String, Entry> before = new LinkedHashMap<>(sessions);
            List<String> drop = new ArrayList<>();
            for (String id : sessions.keySet()) {
                if (!id.equals(keepId)) drop.add(id);
            }
            if (drop.isEmpty()) return 0;
            for (String id : drop) sessions.remove(id);
            if (!persistLocked()) {
                sessions.clear();
                sessions.putAll(before);
                return -1;
            }
            return drop.size();
        }
    }

    /** Live sign-ins made with the current secret, oldest first. */
    public static JSONArray list(String currentId, String secret) {
        JSONArray out = new JSONArray();
        if (secret == null || secret.isEmpty()) return out;
        String tag = keyTag(secret);
        long now = System.currentTimeMillis() / 1000;
        synchronized (AuthSessions.class) {
            refreshLocked(false);
            for (Entry e : sessions.values()) {
                if (e.expiresSec <= now || !e.keyTag.equals(tag)) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", e.id);
                    o.put("label", e.label);
                    o.put("createdAt", e.createdSec);
                    o.put("expiresAt", e.expiresSec);
                    o.put("lastSeenAt", e.lastSeenSec);
                    o.put("current", e.id.equals(currentId));
                    out.put(o);
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    /** A short browser/OS label from a User-Agent ("Chrome · Android"); empty when unknown. */
    public static String labelFor(String userAgent) {
        if (userAgent == null) return "";
        String ua = userAgent;
        String browser = ua.contains("Edg/") ? "Edge"
                : ua.contains("OPR/") ? "Opera"
                : ua.contains("SamsungBrowser/") ? "Samsung Internet"
                : ua.contains("Firefox/") || ua.contains("FxiOS/") ? "Firefox"
                : ua.contains("CriOS/") || ua.contains("Chrome/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari"
                : "";
        String os = ua.contains("iPhone") ? "iPhone"
                : ua.contains("iPad") ? "iPad"
                : ua.contains("Android") ? "Android"
                : ua.contains("Windows") ? "Windows"
                : ua.contains("Mac OS X") ? "Mac"
                : ua.contains("CrOS") ? "ChromeOS"
                : ua.contains("Linux") ? "Linux"
                : "";
        if (browser.isEmpty()) return os;
        if (os.isEmpty()) return browser;
        return browser + " · " + os;
    }

    static boolean validId(String id) {
        if (id == null || id.length() != 22) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Which secret a sign-in was made with, without storing the secret: one that changes hides it from the list.
    private static String keyTag(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("overdrive-session:" + secret).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(java.util.Locale.US, "%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String cleanLabel(String label) {
        if (label == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < label.length() && sb.length() < MAX_LABEL; i++) {
            char c = label.charAt(i);
            if (c >= 0x20 && c != 0x7f) sb.append(c);
        }
        return sb.toString();
    }

    private static void pruneExpiredLocked(long nowSec) {
        List<String> drop = new ArrayList<>();
        for (Entry e : sessions.values()) {
            if (e.expiresSec <= nowSec) drop.add(e.id);
        }
        for (String id : drop) sessions.remove(id);
    }

    // Over the cap: a sign-in made with an older secret goes first, then the least recently used.
    private static void evictOneLocked(String currentTag, String keepId) {
        Entry victim = null;
        for (Entry e : sessions.values()) {
            if (e.id.equals(keepId)) continue;
            if (victim == null) {
                victim = e;
                continue;
            }
            boolean eStale = !e.keyTag.equals(currentTag);
            boolean vStale = !victim.keyTag.equals(currentTag);
            if (eStale != vStale) {
                if (eStale) victim = e;
            } else if (e.lastSeenSec < victim.lastSeenSec) {
                victim = e;
            }
        }
        if (victim == null) return;
        sessions.remove(victim.id);
    }

    // A missing file means no tracked sign-ins; an unreadable one keeps memory until it is next rewritten.
    private static void refreshLocked(boolean force) {
        long nowNanos = System.nanoTime();
        if (!force && checkedOnce && nowNanos - checkedAtNanos < RECHECK_MS * 1_000_000L) return;
        checkedOnce = true;
        checkedAtNanos = nowNanos;
        File file = new File(FILE);
        long mtime = file.exists() ? file.lastModified() : 0L;
        if (mtime == loadedMtime) return;
        if (mtime == 0L) {
            sessions.clear();
            loadedMtime = 0L;
            return;
        }
        Map<String, Entry> read = readFile(file);
        loadedMtime = mtime;
        if (read == null) return;
        for (Entry e : read.values()) {
            Entry known = sessions.get(e.id);
            if (known != null && known.lastSeenSec > e.lastSeenSec) e.lastSeenSec = known.lastSeenSec;
        }
        sessions.clear();
        sessions.putAll(read);
        loadedMtime = mtime;
    }

    private static Map<String, Entry> readFile(File file) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONArray rows = new JSONObject(sb.toString()).optJSONArray("sessions");
            Map<String, Entry> out = new LinkedHashMap<>();
            if (rows == null) return out;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (!validId(id)) continue;
                out.put(id, new Entry(id, o.optLong("c", 0L), o.optLong("e", 0L),
                        cleanLabel(o.optString("l", "")), o.optString("k", ""), o.optLong("s", 0L)));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // Owner-only (0600), written whole then renamed in.
    private static boolean persistLocked() {
        File file = new File(FILE);
        File tmp = new File(FILE + ".tmp");
        try {
            JSONArray rows = new JSONArray();
            for (Entry e : sessions.values()) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("c", e.createdSec);
                o.put("e", e.expiresSec);
                o.put("s", e.lastSeenSec);
                o.put("l", e.label);
                o.put("k", e.keyTag);
                rows.put(o);
            }
            String content = new JSONObject().put("v", 1).put("sessions", rows).toString();
            tmp.delete();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                android.system.Os.chmod(tmp.getPath(), 0600);
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return false;
            }
            loadedMtime = file.lastModified();
            checkedOnce = true;
            checkedAtNanos = System.nanoTime();
            return true;
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
    }
}
