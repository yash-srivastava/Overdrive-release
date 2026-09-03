package com.overdrive.app.byd;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

/**
 * Named seat/mirror geometry store — the OverDrive-native "seat positions" a user
 * builds up beyond BYD's fixed 3 slots. Persisted as JSON at
 * {@code /data/local/tmp/seat_positions.json} so BOTH the uid-2000 daemon (which
 * writes it, from the capture endpoint) and the app UI process (which will read it
 * for the management screen + automation picker) can see it — neither filesDir nor
 * SharedPreferences is shared across those uids. Same storage discipline as
 * {@link com.overdrive.app.surveillance.SafeLocationManager}: atomic tmp+rename,
 * then world readable/writable.
 *
 * <p>Schema:
 * <pre>{ "version":1, "positions":[ {
 *     "id":       "&lt;profile&gt;-slot-1" (captured) | "user-&lt;slug&gt;" (user-created),
 *     "name":     "Posisjon 1",
 *     "profile":  "paa*****@gmail.com",   // captured only
 *     "slot":     1,            // native DiLink slot for captured entries; absent for user-added
 *     "source":   "captured" | "user",
 *     "createdAt": &lt;epoch ms&gt;,
 *     "updatedAt": &lt;epoch ms&gt;,   // user entries, set when the geometry is re-saved
 *     "alias":    "Pål",        // captured only; user's own name, survives re-capture
 *     "axes":     { "HORIZONTAL":52, "BACKREST":56, ..., "LEFT_H":31, ... },
 *     "ambient":  { "front":{"colour":2,"brightness":5}, "rear":{...}, "musicMode":false, ... }
 * } ] }</pre>
 *
 * <p><b>A position carries parts, and either one may be absent.</b> {@code axes} is the
 * seat+mirror geometry, {@code ambient} the interior-light state. A captured entry always
 * has both, because it mirrors the car. A user-created entry has whichever the user chose
 * to save, so "just my lighting" and "just my seat" are both real positions.
 *
 * <p>Absent means absent: apply skips a part that is not stored rather than writing a
 * default, so every position saved before ambient existed keeps behaving exactly as it
 * did. This is why parts are separate keys rather than one merged blob with empty fields
 * — an empty field would be indistinguishable from "the user wants it set to nothing".</p>
 *
 * <p>Ids are stable for the life of the entry. Automations reference a position by id, so a
 * rename must not change it, and re-capturing a native slot must land on the same id.
 *
 * <p>Captured entries are keyed by native slot (1..3) and UPSERTED, so re-saving a
 * native position updates OverDrive's mirror of it rather than piling up duplicates.
 * Unlimited arbitrary named positions (source "user") come from the management UI later.
 */
public final class PositionStore {

    private static final String TAG = "PositionStore";
    public static final String STORE_FILE = ScratchPaths.path("seat_positions.json");
    private static final int VERSION = 1;

    private static final Object LOCK = new Object();
    private static volatile PositionStore instance;

    private PositionStore() {}

    public static PositionStore getInstance() {
        if (instance == null) {
            synchronized (PositionStore.class) {
                if (instance == null) instance = new PositionStore();
            }
        }
        return instance;
    }

    private JSONObject emptyRoot() {
        JSONObject root = new JSONObject();
        try { root.put("version", VERSION); root.put("positions", new JSONArray()); } catch (Throwable ignored) {}
        return root;
    }

    /**
     * Read the store. {@code forWrite} decides what an UNREADABLE-but-present file means:
     * a read path gets an empty store so the UI still renders, while a write path gets
     * null and MUST abort — saving an empty root over a corrupt file would replace every
     * stored position with nothing, and this file is the only record of them.
     */
    private JSONObject readRoot(boolean forWrite) {
        File f = new File(STORE_FILE);
        if (!f.exists()) {
            // Absent primary but a surviving .bak means the last rename was interrupted;
            // recovering from it is the difference between "one save lost" and "all of them".
            JSONObject fromBak = parseFile(new File(STORE_FILE + ".bak"));
            if (fromBak != null) {
                log("primary store missing; recovered from .bak");
                return fromBak;
            }
            return emptyRoot();
        }
        JSONObject root = parseFile(f);
        if (root != null) return root;
        JSONObject bak = parseFile(new File(STORE_FILE + ".bak"));
        if (bak != null) {
            log("store unparseable; recovered from .bak");
            return bak;
        }
        log("store unparseable and no usable .bak"
                + (forWrite ? " — refusing to overwrite it" : " — reporting empty for this read"));
        return forWrite ? null : emptyRoot();
    }

    /** Parse a store file, or null if it is missing, empty or not valid JSON. */
    private JSONObject parseFile(File f) {
        try {
            if (f == null || !f.isFile() || f.length() == 0) return null;
            JSONObject root = new JSONObject(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
            // optJSONArray, not has(): {"positions":null} and a non-array both have to
            // read as "no usable list" rather than passing through as one.
            if (root.optJSONArray("positions") == null) root.put("positions", new JSONArray());
            return root;
        } catch (Throwable t) {
            return null;
        }
    }

    /** False when the store exists but neither it nor its .bak can be parsed. */
    public boolean isReadable() {
        synchronized (LOCK) {
            return readRoot(true) != null;
        }
    }

    /** Tolerant read for list/lookup paths; never null. */
    private JSONObject load() {
        return readRoot(false);
    }

    /** Read for a mutation; null means the store is unreadable and the caller must not save. */
    private JSONObject loadForWrite() {
        return readRoot(true);
    }

    /**
     * Atomic write: fsync'd tmp, previous copy kept as .bak, then rename. Returns false when
     * nothing was persisted. A failed rename leaves the existing store untouched — writing
     * into the target directly would truncate the only good copy before replacing it.
     */
    private boolean save(JSONObject root) {
        File tmp = new File(STORE_FILE + ".tmp");
        try {
            root.put("version", VERSION);
            byte[] bytes = root.toString(2).getBytes("UTF-8");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(bytes);
                fos.flush();
                fos.getFD().sync();
            }
            File target = new File(STORE_FILE);
            if (target.exists()) {
                File bak = new File(STORE_FILE + ".bak");
                try {
                    bak.delete();
                    Files.copy(target.toPath(), bak.toPath());
                    bak.setReadable(true, false);
                    bak.setWritable(true, false);
                } catch (Throwable ignored) { /* best effort */ }
            }
            if (!tmp.renameTo(target)) {
                log("save FAILED: could not rename over " + STORE_FILE + "; store left intact");
                tmp.delete();
                return false;
            }
            target.setReadable(true, false);
            target.setWritable(true, false);
            return true;
        } catch (Throwable t) {
            log("save failed: " + t);
            try { tmp.delete(); } catch (Throwable ignored) {}
            return false;
        }
    }

    /** All positions, as a JSON array (never null). */
    public JSONArray list() {
        synchronized (LOCK) {
            return load().optJSONArray("positions");
        }
    }

    /** Look up a position by id, or null. */
    public JSONObject getById(String id) {
        if (id == null) return null;
        JSONArray arr = list();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    /**
     * Upsert a captured position keyed by (profile, native slot 1..3). BYD's Pos 1/2/3 are
     * per-logged-in-profile, so the same slot number means different geometry for different
     * accounts — the key MUST include the profile. Overwrites the existing captured entry for
     * that (profile, slot). Returns the stored entry.
     *
     * @param profile the DiLink account nickName (from content://com.byd.accountProvider), or
     *                "default" when unknown; identifies which profile these slots belong to.
     * @param name    display name, e.g. "&lt;profile&gt; - Posisjon 1".
     * @param ambient interior-light state captured at the same moment, or null when the car
     *                would not report it. A captured entry mirrors the car, so it always takes
     *                both parts when both are readable — the pick-your-parts choice belongs to
     *                user-created positions, not to a mirror of the car's own slot.
     */
    public JSONObject upsertCaptured(String profile, int slot, String name, JSONObject axes,
                                     JSONObject ambient, long nowMs) {
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("positions");
            String prof = (profile == null || profile.trim().isEmpty()) ? "default" : profile.trim();
            String id = sanitize(prof) + "-slot-" + slot;
            // The alias is the ONE field on a captured entry the user owns rather
            // than the car. Everything else here is deliberately rebuilt from the
            // DiLink provider on every capture, but re-capture happens whenever the
            // seat is re-saved from a native UI, so wiping the alias would mean
            // nudging the seat silently renames the position back to
            // "<account> - Posisjon 2". Carried across the replace below.
            String priorAlias = null;
            JSONObject priorAmbient = null;
            long priorCreatedAt = 0L;
            // Match on id, then fall back to (profile, slot). The id is derived from the
            // profile via sanitize(), so an entry captured under an older slug spelling would
            // otherwise be missed and re-capture would append a SECOND row for the same
            // physical slot instead of replacing it.
            int priorIdx = indexOf(arr, id);
            String legacyId = null;
            if (priorIdx < 0) {
                priorIdx = indexOfCapturedSlot(arr, prof, slot);
                if (priorIdx >= 0) {
                    JSONObject legacy = arr.optJSONObject(priorIdx);
                    legacyId = (legacy != null) ? legacy.optString("id", null) : null;
                }
            }
            if (priorIdx >= 0) {
                JSONObject prior = arr.optJSONObject(priorIdx);
                if (prior != null) {
                    String a = prior.optString("alias", "").trim();
                    if (!a.isEmpty()) priorAlias = a;
                    // A capture that could not read the lights must not DELETE an ambient block
                    // the user set here; and re-saving the seat natively is not a new entry, so
                    // createdAt survives too.
                    priorAmbient = prior.optJSONObject("ambient");
                    priorCreatedAt = prior.optLong("createdAt", 0L);
                }
            }
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", id);
                entry.put("name", name != null ? name : (prof + " - Posisjon " + slot));
                if (priorAlias != null) entry.put("alias", priorAlias);
                entry.put("profile", prof);
                entry.put("slot", slot);
                entry.put("source", "captured");
                entry.put("createdAt", priorCreatedAt > 0 ? priorCreatedAt : nowMs);
                if (priorCreatedAt > 0) entry.put("updatedAt", nowMs);
                entry.put("axes", axes != null ? axes : new JSONObject());
                if (ambient != null && ambient.length() > 0) entry.put("ambient", ambient);
                else if (priorAmbient != null && priorAmbient.length() > 0) entry.put("ambient", priorAmbient);
                // Replace any existing captured entry for this slot — by the new id AND by the
                // legacy id it was stored under, so a slug change replaces rather than duplicates.
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p == null) continue;
                    String pid = p.optString("id");
                    if (id.equals(pid) || (legacyId != null && legacyId.equals(pid))) continue;
                    next.put(p);
                }
                next.put(entry);
                root.put("positions", next);
                if (!save(root)) return null;   // nothing on disk: the caller must not report success
            } catch (Throwable t) {
                log("upsertCaptured failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /**
     * Create a user-owned position from the geometry passed in. Unlike captured entries there
     * is no natural key, so the id is derived from the name and then made unique by suffix.
     * It is deliberately NOT re-derived on rename: automations reference positions by id, so
     * the id has to outlive whatever the entry is called (mirrors how AppType stores a package
     * name rather than an app label). Charset is the sanitize() output, [a-z0-9_-], which is
     * what the automation-side value validator accepts.
     *
     * <p>Takes the parts the user chose to save. Either may be null, but not both — a
     * position that stores nothing would list and apply as a no-op, which reads as a bug
     * rather than as an empty position.
     *
     * @return the stored entry, or null if the name is empty/over-long or no part was given.
     */
    public JSONObject createUser(String name, JSONObject axes, JSONObject ambient, long nowMs) {
        String clean = (name == null) ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 60) return null;
        if (isEmpty(axes) && isEmpty(ambient)) return null;
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("positions");
            String base = "user-" + sanitize(clean);
            String id = base;
            for (int n = 2; indexOf(arr, id) >= 0; n++) id = base + "-" + n;
            JSONObject entry = new JSONObject();
            try {
                entry.put("id", id);
                entry.put("name", clean);
                entry.put("source", "user");
                entry.put("createdAt", nowMs);
                if (!isEmpty(axes)) entry.put("axes", axes);
                if (!isEmpty(ambient)) entry.put("ambient", ambient);
                arr.put(entry);
                if (!save(root)) return null;
            } catch (Throwable t) {
                log("createUser failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /**
     * Save the given parts onto an existing USER entry, keeping its id and name. Captured
     * entries mirror the car and are rejected — their contents only ever come from a capture.
     *
     * <p>A null part is left as it was, which is what makes "add my lighting to this seat
     * position" a single save rather than a re-save of everything. Passing both nulls is a
     * no-op rather than an error: nothing was asked for, so nothing changed.
     *
     * @return the updated entry, or null if absent or captured.
     */
    public JSONObject updateParts(String id, JSONObject axes, JSONObject ambient, long nowMs) {
        if (id == null) return null;
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("positions");
            int i = indexOf(arr, id);
            if (i < 0) return null;
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null || !"user".equals(entry.optString("source"))) return null;
            // Nothing asked for is a no-op, not a rewrite: rewriting the whole file to change
            // nothing burns a .bak cycle and can only lose data if it is interrupted.
            if (isEmpty(axes) && isEmpty(ambient)) return entry;
            try {
                if (!isEmpty(axes)) { entry.put("axes", axes); entry.put("updatedAt", nowMs); }
                if (!isEmpty(ambient)) { entry.put("ambient", ambient); entry.put("updatedAt", nowMs); }
                arr.put(i, entry);
                if (!save(root)) return null;
            } catch (Throwable t) {
                log("updateParts failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /**
     * Replace the stored ambient block on an entry, captured or user-created.
     *
     * <p>Unlike geometry, this is allowed on CAPTURED entries too. A captured entry's
     * geometry is off-limits because it mirrors the car's own slot, but the ambient block is
     * something OverDrive added on top — BYD's slots never stored it — so there is no car
     * state being contradicted, and re-capture overwrites it from the car anyway.
     *
     * @return the updated entry, or null if absent or the block is empty.
     */
    public JSONObject setAmbient(String id, JSONObject ambient) {
        if (id == null || isEmpty(ambient)) return null;
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("positions");
            int i = indexOf(arr, id);
            if (i < 0) return null;
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) return null;
            try {
                entry.put("ambient", ambient);
                arr.put(i, entry);
                if (!save(root)) return null;
            } catch (Throwable t) {
                log("setAmbient failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /** Which parts an entry carries, for callers deciding what to apply or what to show. */
    public static boolean hasGeometry(JSONObject entry) {
        return entry != null && !isEmpty(entry.optJSONObject("axes"));
    }

    public static boolean hasAmbient(JSONObject entry) {
        return entry != null && !isEmpty(entry.optJSONObject("ambient"));
    }

    private static boolean isEmpty(JSONObject o) {
        return o == null || o.length() == 0;
    }

    /**
     * Rename a USER entry. The id is untouched, so automations pointing at it keep working.
     *
     * @return the updated entry, or null if absent, captured, or the name is empty/over-long.
     */
    public JSONObject rename(String id, String name) {
        String clean = (name == null) ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 60) return null;
        return mutate(id, null, clean, 0L);
    }

    /**
     * Set or clear the alias on a CAPTURED entry — the counterpart to {@link #rename} for the
     * entries that mirror the car. A captured entry's name comes from the DiLink provider
     * ("&lt;account&gt; - Posisjon 2") and is rebuilt on every capture, so it can't be edited in
     * place; the alias sits alongside it and survives re-capture instead.
     *
     * <p>User entries are rejected: they are named when saved and renamed with {@link #rename},
     * and giving them two competing display names would just raise the question of which wins.
     *
     * @param alias trimmed display name, max 60 chars (same bound as rename); null/empty clears
     *              it and the entry falls back to the car's own name.
     * @return the updated entry, or null if absent, not captured, or the alias is over-long.
     */
    public JSONObject setAlias(String id, String alias) {
        if (id == null) return null;
        String clean = (alias == null) ? "" : alias.trim();
        if (clean.length() > 60) return null;
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("positions");
            int i = indexOf(arr, id);
            if (i < 0) return null;
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null || !"captured".equals(entry.optString("source"))) return null;
            try {
                if (clean.isEmpty()) entry.remove("alias");
                else entry.put("alias", clean);
                arr.put(i, entry);
                if (!save(root)) return null;
            } catch (Throwable t) {
                log("setAlias failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /**
     * What to show for an entry: the user's alias when they set one, otherwise the name the
     * entry was created with. Kept here so every consumer (seat page, automation picker, home
     * panel, capture toast) resolves it the same way rather than each deciding for itself.
     */
    public static String displayName(JSONObject entry) {
        if (entry == null) return "";
        String alias = entry.optString("alias", "").trim();
        return !alias.isEmpty() ? alias : entry.optString("name", "");
    }

    /** Shared body of updateAxes/rename: find a user entry by id, apply what was passed, save. */
    private JSONObject mutate(String id, JSONObject axes, String name, long nowMs) {
        if (id == null) return null;
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("positions");
            int i = indexOf(arr, id);
            if (i < 0) return null;
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null || !"user".equals(entry.optString("source"))) return null;
            try {
                if (axes != null) { entry.put("axes", axes); entry.put("updatedAt", nowMs); }
                if (name != null) entry.put("name", name);
                arr.put(i, entry);
                if (!save(root)) return null;
            } catch (Throwable t) {
                log("mutate failed: " + t);
                return null;
            }
            return entry;
        }
    }

    /**
     * Vehicle models the bodywork axis map in {@link BodyworkSeatProbe#fullAxes()} has actually
     * been confirmed against. The ids were read off a BYD Seal; no other model has been tested.
     *
     * <p>Reading and capturing is safe everywhere and is how this list grows: capture a position,
     * move the seat, capture another, and compare. If the seat axes track the seat and the mirror
     * axes track the mirrors, the map fits that car. Applying on an unconfirmed model is allowed
     * after an explicit acknowledgement rather than blocked, because a feature that refuses to run
     * anywhere it has not already been proven can never be proven anywhere new.
     */
    private static final String[] CONFIRMED_MODELS = { "seal", "sealion7" };

    /** Whether the axis map is confirmed for this model id. Null/unknown is NOT confirmed. */
    public static boolean isModelConfirmed(String modelId) {
        if (modelId == null) return false;
        String m = modelId.trim().toLowerCase(java.util.Locale.US);
        for (String c : CONFIRMED_MODELS) if (c.equals(m)) return true;
        return false;
    }

    /**
     * Acknowledgement key. An unset model still has to be acknowledgeable, or a user who never
     * picked their car in Settings would be asked again on every single apply, forever.
     */
    private static String ackKey(String modelId) {
        return (modelId == null || modelId.trim().isEmpty())
                ? "unknown"
                : modelId.trim().toLowerCase(java.util.Locale.US);
    }

    /** Whether the user has already accepted applying on this (unconfirmed) model. */
    public boolean isModelAcknowledged(String modelId) {
        synchronized (LOCK) {
            JSONArray acked = load().optJSONArray("acknowledgedModels");
            if (acked == null) return false;
            String m = ackKey(modelId);
            for (int i = 0; i < acked.length(); i++) {
                if (m.equals(String.valueOf(acked.optString(i)).toLowerCase(java.util.Locale.US))) return true;
            }
            return false;
        }
    }

    /**
     * Record that the user accepted applying on this model. Idempotent. Returns false when the
     * acknowledgement did not persist, so the caller can say it will be asked again rather than
     * quietly re-prompting on the next apply.
     */
    public boolean acknowledgeModel(String modelId) {
        if (isModelAcknowledged(modelId)) return true;
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return false;
            JSONArray acked = root.optJSONArray("acknowledgedModels");
            if (acked == null) acked = new JSONArray();
            acked.put(ackKey(modelId));
            try { root.put("acknowledgedModels", acked); return save(root); }
            catch (Throwable t) { log("acknowledgeModel failed: " + t); return false; }
        }
    }

    /** Index of the captured entry for this (profile, slot), whatever its id spelling, or -1. */
    private static int indexOfCapturedSlot(JSONArray arr, String profile, int slot) {
        if (arr == null || profile == null) return -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            if (!"captured".equals(p.optString("source"))) continue;
            if (p.optInt("slot", -1) != slot) continue;
            if (profile.equals(p.optString("profile"))) return i;
        }
        return -1;
    }

    /** Index of the entry with this id, or -1. */
    private static int indexOf(JSONArray arr, String id) {
        if (arr == null || id == null) return -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return i;
        }
        return -1;
    }

    /** Remove a position by id. Returns true if something was removed. */
    public boolean remove(String id) {
        if (id == null) return false;
        synchronized (LOCK) {
            JSONObject root = loadForWrite();
            if (root == null) return false;
            JSONArray arr = root.optJSONArray("positions");
            JSONArray next = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null && id.equals(p.optString("id"))) { removed = true; continue; }
                next.put(p);
            }
            if (removed) {
                try { root.put("positions", next); } catch (Throwable ignored) {}
                if (!save(root)) return false;   // still on disk, so it was not removed
            }
            return removed;
        }
    }

    /** Sanitize a profile string into an id-safe token (keep ASCII alphanumerics, rest to '_'). */
    /**
     * ASCII-only [a-z0-9_] slug. Deliberately NOT Character.isLetterOrDigit, which is
     * Unicode-aware and would keep letters like "å" — the automation-side value validator
     * (SavedSeatPositionType.isValidValue) accepts ASCII only, so a non-ASCII id renders in
     * the picker and then makes the whole automation unsaveable.
     */
    private static String sanitize(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ascii = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            b.append(ascii ? Character.toLowerCase(c) : '_');
        }
        return b.toString();
    }

    private void log(String s) {
        try { CameraDaemon.log(TAG + ": " + s); } catch (Throwable ignore) {}
    }
}
