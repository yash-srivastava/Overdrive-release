package com.overdrive.app.auth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Face ID / fingerprint lock state, daemon side: whether it is on, which actions ask, and the enrolled
 * passkeys. Owner-only file next to the auth secret. A file that can't be read keeps the last state read
 * in this process (none yet: lock on, default actions, no passkeys) and refuses writes until it is reset.
 */
public final class StepUpStore {

    public static final String UNLOCK = "unlock";
    public static final String TRUNK_OPEN = "trunkOpen";
    public static final String POWER_OFF = "powerOff";
    public static final String POWER_ON = "powerOn";
    private static final String[] ACTION_NAMES = {UNLOCK, TRUNK_OPEN, POWER_OFF, POWER_ON};
    private static final boolean[] DEFAULT_ACTIONS = {true, true, true, false};

    public static final int MAX_CREDENTIALS = 16;
    public static final int MAX_LABEL = 40;
    public static final int ALG_ES256 = -7;
    public static final int ALG_RS256 = -257;

    public static final String ERR_NO_PHONE = "no_phone";
    public static final String ERR_NOT_FOUND = "not_found";
    public static final String ERR_DUPLICATE = "duplicate";
    public static final String ERR_TOO_MANY = "too_many";
    public static final String ERR_INVALID = "invalid";
    public static final String ERR_SAVE = "save_failed";
    public static final String ERR_UNREADABLE = "store_unreadable";

    private static final String FILE = com.overdrive.app.util.ScratchRoot.path(".overdrive_stepup.json");
    private static final long RECHECK_NANOS = 1_000_000_000L;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_ID_CHARS = 1400;
    private static final int MAX_KEY_CHARS = 2800;

    /** One enrolled passkey. {@code id} and {@code publicKey} (SPKI DER) are base64url without padding. */
    public static final class Credential {
        public final String id;
        public final String rpId;
        public final String publicKey;
        public final int alg;
        public final long signCount;
        public final String label;
        public final long createdAt;
        public final long lastUsedAt;

        public Credential(String id, String rpId, String publicKey, int alg, long signCount,
                          String label, long createdAt, long lastUsedAt) {
            this.id = id;
            this.rpId = rpId;
            this.publicKey = publicKey;
            this.alg = alg;
            this.signCount = signCount;
            this.label = label;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
        }
    }

    /** Immutable snapshot. */
    public static final class State {
        public final boolean enabled;
        /** The file exists but couldn't be read or parsed; writes are refused until a reset. */
        public final boolean unreadable;
        public final List<Credential> credentials;
        private final boolean[] actions;

        State(boolean enabled, boolean unreadable, boolean[] actions, List<Credential> credentials) {
            this.enabled = enabled;
            this.unreadable = unreadable;
            this.actions = actions.clone();
            this.credentials = Collections.unmodifiableList(new ArrayList<>(credentials));
        }

        public boolean action(String name) {
            int i = actionIndex(name);
            return i >= 0 && actions[i];
        }

        public boolean anyAction() {
            for (boolean a : actions) {
                if (a) return true;
            }
            return false;
        }

        public Credential credential(String id) {
            if (id == null) return null;
            for (Credential c : credentials) {
                if (c.id.equals(id)) return c;
            }
            return null;
        }

        public List<Credential> credentialsFor(String rpId) {
            List<Credential> out = new ArrayList<>();
            if (rpId == null) return out;
            for (Credential c : credentials) {
                if (c.rpId.equals(rpId)) out.add(c);
            }
            return out;
        }

        State asUnreadable() {
            return new State(enabled, true, actions, credentials);
        }

        public JSONObject actionsJson() throws JSONException {
            JSONObject o = new JSONObject();
            for (int i = 0; i < ACTION_NAMES.length; i++) o.put(ACTION_NAMES[i], actions[i]);
            return o;
        }
    }

    /** A mutation's outcome: {@code error} is null on success; {@code state} is the state now in force. */
    public static final class Change {
        public final State state;
        public final String error;

        Change(State state, String error) {
            this.state = state;
            this.error = error;
        }
    }

    private static final State DEFAULTS =
            new State(false, false, DEFAULT_ACTIONS, Collections.<Credential>emptyList());
    private static final State FAIL_CLOSED =
            new State(true, true, DEFAULT_ACTIONS, Collections.<Credential>emptyList());

    private static volatile State state = DEFAULTS;
    private static volatile boolean checkedOnce = false;
    private static volatile long checkedAtNanos = 0L;
    // Guarded by StepUpStore.class: the last state read cleanly (the defaults for a missing file), and
    // what the loaded state was read from.
    private static State lastGood = null;
    private static boolean stampExists = false;
    private static long stampMtime = -1L;
    private static long stampLength = -1L;

    private StepUpStore() {}

    public static String[] actionNames() {
        return ACTION_NAMES.clone();
    }

    /** The current state; served from memory, re-read only when the file's mtime or size changes. */
    public static State get() {
        if (checkedOnce && System.nanoTime() - checkedAtNanos < RECHECK_NANOS) return state;
        synchronized (StepUpStore.class) {
            refreshLocked(false);
            return state;
        }
    }

    /** Turns the lock on/off and/or sets which actions ask. Null leaves a value unchanged. */
    public static Change update(Boolean enabled, Map<String, Boolean> actions) {
        synchronized (StepUpStore.class) {
            refreshLocked(true);
            State cur = state;
            if (cur.unreadable) return new Change(cur, ERR_UNREADABLE);
            if (Boolean.TRUE.equals(enabled) && cur.credentials.isEmpty()) return new Change(cur, ERR_NO_PHONE);
            boolean[] next = cur.actions.clone();
            if (actions != null) {
                for (Map.Entry<String, Boolean> e : actions.entrySet()) {
                    int i = actionIndex(e.getKey());
                    if (i >= 0 && e.getValue() != null) next[i] = e.getValue();
                }
            }
            boolean nextEnabled = enabled == null ? cur.enabled : enabled;
            if (nextEnabled == cur.enabled && java.util.Arrays.equals(next, cur.actions)) {
                return new Change(cur, null);
            }
            return commitLocked(new State(nextEnabled, false, next, cur.credentials), cur);
        }
    }

    public static Change addCredential(Credential c) {
        if (c == null || !validCredentialId(c.id) || !validRpId(c.rpId) || !validB64(c.publicKey, MAX_KEY_CHARS)
                || (c.alg != ALG_ES256 && c.alg != ALG_RS256) || c.signCount < 0 || c.signCount > 0xFFFFFFFFL) {
            return new Change(get(), ERR_INVALID);
        }
        Credential clean = new Credential(c.id, c.rpId, c.publicKey, c.alg, c.signCount,
                cleanLabel(c.label), Math.max(0L, c.createdAt), Math.max(0L, c.lastUsedAt));
        synchronized (StepUpStore.class) {
            refreshLocked(true);
            State cur = state;
            if (cur.unreadable) return new Change(cur, ERR_UNREADABLE);
            if (cur.credential(clean.id) != null) return new Change(cur, ERR_DUPLICATE);
            if (cur.credentials.size() >= MAX_CREDENTIALS) return new Change(cur, ERR_TOO_MANY);
            List<Credential> list = new ArrayList<>(cur.credentials);
            list.add(clean);
            return commitLocked(new State(cur.enabled, false, cur.actions, list), cur);
        }
    }

    /** Removing the last passkey turns the lock off. */
    public static Change removeCredential(String id) {
        synchronized (StepUpStore.class) {
            refreshLocked(true);
            State cur = state;
            if (cur.unreadable) return new Change(cur, ERR_UNREADABLE);
            List<Credential> list = new ArrayList<>(cur.credentials);
            boolean removed = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(id)) {
                    list.remove(i);
                    removed = true;
                    break;
                }
            }
            if (!removed) return new Change(cur, ERR_NOT_FOUND);
            return commitLocked(new State(cur.enabled && !list.isEmpty(), false, cur.actions, list), cur);
        }
    }

    /** Replaces an unreadable file with the defaults: lock off, no passkeys. */
    public static Change resetToDefaults() {
        synchronized (StepUpStore.class) {
            refreshLocked(true);
            State cur = state;
            // Readable again by now: just turn the lock off and keep the phones.
            if (!cur.unreadable) {
                return commitLocked(new State(false, false, cur.actions, cur.credentials), cur);
            }
            return commitLocked(DEFAULTS, cur);
        }
    }

    // Makes the rename itself durable across a power cut; best effort.
    private static void syncDirectory(File dir) {
        if (dir == null) return;
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open(dir.getPath(), android.system.OsConstants.O_RDONLY, 0);
            android.system.Os.fsync(fd);
        } catch (Throwable ignored) {
        } finally {
            if (fd != null) {
                try { android.system.Os.close(fd); } catch (Throwable ignored) {}
            }
        }
    }

    /** After a verified approval. Memory keeps the higher counter even when the file can't be written. */
    public static void recordUse(String id, long signCount, long atMs) {
        synchronized (StepUpStore.class) {
            refreshLocked(true);
            State cur = state;
            List<Credential> list = new ArrayList<>(cur.credentials);
            for (int i = 0; i < list.size(); i++) {
                Credential c = list.get(i);
                if (!c.id.equals(id)) continue;
                list.set(i, new Credential(c.id, c.rpId, c.publicKey, c.alg, Math.max(c.signCount, signCount),
                        c.label, c.createdAt, Math.max(c.lastUsedAt, atMs)));
                State next = new State(cur.enabled, cur.unreadable, cur.actions, list);
                if (cur.unreadable || !persistLocked(next)) state = next;
                return;
            }
        }
    }

    /** Printable characters only, at most {@link #MAX_LABEL} chars, trimmed. */
    public static String cleanLabel(String label) {
        if (label == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < label.length()) {
            int cp = label.codePointAt(i);
            int n = Character.charCount(cp);
            i += n;
            if (!printable(cp)) continue;
            if (sb.length() + n > MAX_LABEL) break;
            sb.appendCodePoint(cp);
        }
        return sb.toString().trim();
    }

    public static boolean validCredentialId(String id) {
        return validB64(id, MAX_ID_CHARS);
    }

    /** A lower-case DNS name with at least one dot, as a WebAuthn RP ID must be here. */
    public static boolean validRpId(String rpId) {
        if (rpId == null || rpId.isEmpty() || rpId.length() > 253 || rpId.indexOf('.') < 0) return false;
        if (rpId.startsWith(".") || rpId.endsWith(".") || rpId.contains("..")) return false;
        for (int i = 0; i < rpId.length(); i++) {
            char c = rpId.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) return false;
        }
        return true;
    }

    private static boolean validB64(String s, int maxChars) {
        if (s == null || s.isEmpty() || s.length() > maxChars) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean printable(int cp) {
        if (Character.isISOControl(cp)) return false;
        int type = Character.getType(cp);
        return type != Character.FORMAT && type != Character.SURROGATE && type != Character.PRIVATE_USE
                && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR;
    }

    private static int actionIndex(String name) {
        if (name == null) return -1;
        for (int i = 0; i < ACTION_NAMES.length; i++) {
            if (ACTION_NAMES[i].equals(name)) return i;
        }
        return -1;
    }

    private static Change commitLocked(State next, State cur) {
        return persistLocked(next) ? new Change(next, null) : new Change(cur, ERR_SAVE);
    }

    // A missing file means the defaults. A read error is retried on the next check; bad content is not
    // re-read until the file changes.
    private static void refreshLocked(boolean force) {
        long nowNanos = System.nanoTime();
        if (!force && checkedOnce && nowNanos - checkedAtNanos < RECHECK_NANOS) return;
        File file = new File(FILE);
        boolean exists = file.exists();
        long mtime = exists ? file.lastModified() : 0L;
        long length = exists ? file.length() : 0L;
        if (!checkedOnce || exists != stampExists || mtime != stampMtime || length != stampLength) {
            if (!exists) {
                state = DEFAULTS;
                lastGood = DEFAULTS;
                stamp(false, 0L, 0L);
            } else {
                String text = null;
                try {
                    text = readFile(file);
                } catch (Throwable ignored) {}
                State parsed = text == null ? null : parse(text);
                if (parsed != null) {
                    state = parsed;
                    lastGood = parsed;
                } else {
                    state = lastGood == null ? FAIL_CLOSED : lastGood.asUnreadable();
                }
                if (text != null) stamp(true, mtime, length);
            }
        }
        checkedAtNanos = nowNanos;
        checkedOnce = true;
    }

    private static void stamp(boolean exists, long mtime, long length) {
        stampExists = exists;
        stampMtime = mtime;
        stampLength = length;
    }

    /** Null when the content isn't a valid state file. */
    static State parse(String text) {
        try {
            JSONObject root = new JSONObject(text);
            Object enabled = root.opt("enabled");
            Object actions = root.opt("actions");
            Object credentials = root.opt("credentials");
            if ((enabled != null && !(enabled instanceof Boolean))
                    || (actions != null && !(actions instanceof JSONObject))
                    || (credentials != null && !(credentials instanceof JSONArray))) {
                return null;
            }
            boolean[] acts = DEFAULT_ACTIONS.clone();
            if (actions != null) {
                for (int i = 0; i < ACTION_NAMES.length; i++) {
                    Object v = ((JSONObject) actions).opt(ACTION_NAMES[i]);
                    if (v instanceof Boolean) acts[i] = (Boolean) v;
                }
            }
            List<Credential> list = new ArrayList<>();
            if (credentials != null) {
                JSONArray rows = (JSONArray) credentials;
                for (int i = 0; i < rows.length() && list.size() < MAX_CREDENTIALS; i++) {
                    Credential c = readCredential(rows.optJSONObject(i));
                    if (c == null) continue;
                    boolean dup = false;
                    for (Credential known : list) dup |= known.id.equals(c.id);
                    if (!dup) list.add(c);
                }
            }
            return new State(Boolean.TRUE.equals(enabled), false, acts, list);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Credential readCredential(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        String rpId = o.optString("rpId", "");
        String key = o.optString("publicKey", "");
        Object alg = o.opt("alg");
        if (!validCredentialId(id) || !validRpId(rpId) || !validB64(key, MAX_KEY_CHARS) || !(alg instanceof Number)) {
            return null;
        }
        int a = ((Number) alg).intValue();
        if (a != ALG_ES256 && a != ALG_RS256) return null;
        long count = o.optLong("signCount", 0L);
        if (count < 0L || count > 0xFFFFFFFFL) count = 0L;
        return new Credential(id, rpId, key, a, count, cleanLabel(o.optString("label", "")),
                Math.max(0L, o.optLong("createdAt", 0L)), Math.max(0L, o.optLong("lastUsedAt", 0L)));
    }

    private static String readFile(File file) throws IOException {
        if (file.length() > MAX_FILE_BYTES) return "";
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_FILE_BYTES) return "";
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String serialize(State s) throws JSONException {
        JSONArray rows = new JSONArray();
        for (Credential c : s.credentials) {
            rows.put(new JSONObject()
                    .put("id", c.id)
                    .put("rpId", c.rpId)
                    .put("publicKey", c.publicKey)
                    .put("alg", c.alg)
                    .put("signCount", c.signCount)
                    .put("label", c.label)
                    .put("createdAt", c.createdAt)
                    .put("lastUsedAt", c.lastUsedAt));
        }
        return new JSONObject()
                .put("enabled", s.enabled)
                .put("actions", s.actionsJson())
                .put("credentials", rows)
                .toString();
    }

    // Owner-only (0600), written whole then renamed in. On success {@code next} is the state in force.
    private static boolean persistLocked(State next) {
        File file = new File(FILE);
        File tmp = new File(FILE + ".tmp");
        try {
            byte[] content = serialize(next).getBytes(StandardCharsets.UTF_8);
            tmp.delete();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                android.system.Os.chmod(tmp.getPath(), 0600);
                out.write(content);
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return false;
            }
            syncDirectory(file.getParentFile());
            state = next;
            lastGood = next;
            stamp(true, file.lastModified(), file.length());
            checkedAtNanos = System.nanoTime();
            checkedOnce = true;
            return true;
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
    }
}
