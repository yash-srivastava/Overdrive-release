package com.overdrive.app.automation;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable, named action sequences ("action groups") that many automations (and key
 * mappings) can invoke by id via {@link com.overdrive.app.automation.action.ActionGroupAction}.
 * Editing a group updates every caller, because groups are resolved at RUN time
 * (call-by-reference), not expanded into each caller.
 *
 * <p>Stored in a SEPARATE file ({@code action_groups.json}) from automations, with the
 * exact same durability discipline (atomic {@code .tmp}+rename, {@code .bak} recovery,
 * {@code SAVE_LOCK}) — kept separate so an action-group parse failure can never corrupt
 * the automations config, and vice versa.
 *
 * <p>Each group is {@code {name, actions:[...]}}, and its actions are validated through
 * the SAME {@link Automation#parseActions}-equivalent gate (an unknown/invalid action
 * rejects the group), so a group can only ever hold real, runnable actions. Cycles
 * (group A → group B → group A) are stopped at run time by the invoking action's guard
 * plus the {@link Automations#runActionList} depth cap.
 */
public final class ActionGroups {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");

    private static final File HOME = new File(ScratchPaths.path(".automations"));
    private static final File CONFIG = new File(HOME, "action_groups.json");
    private static final File BACKUP = new File(HOME, "action_groups.json.bak");
    private static final File TMP = new File(HOME, "action_groups.json.tmp");
    private static final Object SAVE_LOCK = new Object();

    // id -> group. LinkedHashMap preserves display order.
    //
    // COPY-ON-WRITE, and the reference is volatile: readers (getActions/exists/toJson/listJson)
    // run with NO lock, on the queue worker (AutomationQueue invokes actions outside its own
    // monitor), on a keymap's ad-hoc thread, and on HTTP threads. A writer mutating this map in
    // place — importGroups(replace=true) does clear() then putAll() — leaves a window in which a
    // lockless get() sees an empty or mid-rehash map, so an invoked group silently runs ZERO
    // actions. Writers therefore build a fresh map under SAVE_LOCK and publish it with one
    // volatile assignment, so every reader sees either the whole old set or the whole new one.
    private static volatile Map<String, Group> groups = new LinkedHashMap<>();

    private ActionGroups() {}

    /** One named, reusable action sequence. */
    public static final class Group {
        public final String name;
        public final List<AutomationAction> actions;
        Group(String name, List<AutomationAction> actions) {
            this.name = name;
            this.actions = actions;
        }
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                JSONArray arr = new JSONArray();
                for (AutomationAction a : actions) arr.put(a.toJson());
                o.put("actions", arr);
            } catch (Exception ignored) {}
            return o;
        }
    }

    /** The actions of a group by id, or an empty list if the id is unknown. Never null. */
    public static List<AutomationAction> getActions(String id) {
        if (id == null) return List.of();
        Group g = groups.get(id);
        return g == null ? List.of() : g.actions;
    }

    /** Whether a group with this id exists. */
    public static boolean exists(String id) {
        return id != null && groups.containsKey(id);
    }

    /** All groups as {@code {id: {name, actions}}} for the API / picker. */
    public static JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, Group> e : groups.entrySet()) {
                json.put(e.getKey(), e.getValue().toJson());
            }
        } catch (Exception ignored) {}
        return json;
    }

    /** Lightweight {id,name} list for a picker (no action bodies). */
    public static JSONArray listJson() {
        JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, Group> e : groups.entrySet()) {
                arr.put(new JSONObject().put("id", e.getKey()).put("name", e.getValue().name));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    /**
     * The outcome of one mutating call: which group it touched, and whether the change actually
     * reached disk. Returned per call rather than recorded in a shared field — a static
     * "last write failed" flag is read by the HTTP layer AFTER the call returns, so a concurrent
     * mutation on another request thread (the server runs a 32-thread pool, and a community
     * import calls {@link #save} in a loop) could flip it in between and make a FAILED write
     * report success — the exact Invariant 7 violation this reporting exists to prevent.
     */
    public static final class SaveResult {
        /** The group id, or null when the input was invalid (nothing was changed). */
        public final String id;
        /** True when the change reached {@code action_groups.json}. Meaningless if {@link #id} is null. */
        public final boolean persisted;
        SaveResult(String id, boolean persisted) {
            this.id = id;
            this.persisted = persisted;
        }
        public boolean isValid() { return id != null; }
    }

    /**
     * Create or update a group. Body: {@code {name, actions:[...]}}. Validates the
     * actions through {@link Automation#parseActionsPublic}; a bad action rejects the
     * whole write (returns null). Returns the id (minted for a new group).
     *
     * <p>Prefer {@link #saveWithResult} when the caller must report a persistence failure;
     * this overload keeps the original id/null contract for callers that don't (e.g. the
     * community bundle import, which reports its own per-bundle outcome).
     */
    public static String save(String id, JSONObject body) {
        return saveWithResult(id, body).id;
    }

    /** {@link #save}, also reporting whether the change was persisted. */
    public static SaveResult saveWithResult(String id, JSONObject body) {
        if (body == null) return new SaveResult(null, false);
        String name = body.optString("name", "").trim();
        if (name.isEmpty()) return new SaveResult(null, false);
        List<AutomationAction> actions;
        try {
            JSONArray actionsJson = body.optJSONArray("actions");
            if (actionsJson == null) return new SaveResult(null, false);
            actions = Automation.parseActionsPublic(actionsJson);
            if (actions == null || actions.isEmpty()) return new SaveResult(null, false);
        } catch (Exception e) {
            return new SaveResult(null, false);
        }
        String gid = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        synchronized (SAVE_LOCK) {
            Map<String, Group> next = new LinkedHashMap<>(groups);
            next.put(gid, new Group(name, actions));
            groups = next;   // publish whole-map (see the `groups` field note)
        }
        boolean persisted = saveToFile();
        // A group body is part of the signal-reference graph (Automations.actionsReference expands
        // actionGroup), so an edit can change which pollers must run.
        Automations.invalidateReferenceCacheForGroupChange();
        if (!persisted) {
            logger.error("Saved action group " + gid + " into memory but could NOT persist it");
            return new SaveResult(gid, false);
        }
        logger.info("Saved action group: " + gid + " (" + name + ", " + actions.size() + " actions)");
        return new SaveResult(gid, true);
    }

    /**
     * Import groups from an exported {@code {id: {name, actions}}} map, mirroring
     * {@link Automations#importAutomations}: EVERY entry is validated through the same
     * {@link Automation#parseActionsPublic} gate a single {@link #save} uses, and nothing is
     * installed unless at least one entry parsed — so a malformed file can never half-wipe the
     * store in replace mode.
     *
     * @param json    id → {name, actions} map
     * @param replace true wipes the current set first; false merges (overwrite by id)
     * @return how many groups were imported, or -1 when the groups parsed but could not be
     *     PERSISTED (Invariant 7 — the caller must report that, not claim success)
     */
    public static int importGroups(JSONObject json, boolean replace) {
        if (json == null) return 0;
        Map<String, Group> parsed = new LinkedHashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject g = json.optJSONObject(key);
            if (g == null) continue;
            String name = g.optString("name", "").trim();
            JSONArray actionsJson = g.optJSONArray("actions");
            if (name.isEmpty() || actionsJson == null) continue;
            List<AutomationAction> actions;
            try {
                actions = Automation.parseActionsPublic(actionsJson);
            } catch (Exception e) {
                continue; // skip an unparseable group, keep the rest
            }
            if (actions == null || actions.isEmpty()) continue;
            // Mint a fresh id for a blank key so an import can't collide with an unrelated
            // group (same rule as importAutomations).
            String gid = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
            parsed.put(gid, new Group(name, actions));
        }
        if (parsed.isEmpty()) return 0;
        synchronized (SAVE_LOCK) {
            Map<String, Group> next = replace
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(groups);
            next.putAll(parsed);
            groups = next;   // one volatile publish — no empty window for lockless readers
        }
        boolean persisted = saveToFile();
        // A group body is part of the signal-reference graph, so an import can change which
        // pollers must run (same reason save()/delete() invalidate). Done even on a failed
        // write: the in-memory map DID change, so the cache must not keep serving the old
        // answer for this process's lifetime.
        Automations.invalidateReferenceCacheForGroupChange();
        if (!persisted) {
            logger.error("Imported " + parsed.size()
                    + " action groups into memory but could NOT persist them");
            return -1;
        }
        logger.info("Imported " + parsed.size() + " action groups (replace=" + replace + ")");
        return parsed.size();
    }

    /** Delete a group by id. Returns true if one was removed. */
    public static boolean delete(String id) {
        return deleteWithResult(id).isValid();
    }

    /**
     * {@link #delete}, also reporting whether the removal was persisted.
     * {@code id == null} in the result means nothing was removed (unknown id) — the caller
     * should answer 404; {@code persisted == false} on a real removal means the group is gone
     * from memory but would come back at the next restart.
     */
    public static SaveResult deleteWithResult(String id) {
        boolean removed;
        synchronized (SAVE_LOCK) {
            if (groups.containsKey(id)) {
                Map<String, Group> next = new LinkedHashMap<>(groups);
                next.remove(id);
                groups = next;
                removed = true;
            } else {
                removed = false;
            }
        }
        if (!removed) return new SaveResult(null, false);
        boolean persisted = saveToFile();
        Automations.invalidateReferenceCacheForGroupChange();
        if (persisted) logger.info("Deleted action group: " + id);
        else logger.error("Deleted action group " + id
                + " from memory but could NOT persist the removal");
        return new SaveResult(id, persisted);
    }

    // ── Persistence (mirrors Automations: atomic tmp+rename, .bak recovery) ──

    /**
     * Persist the group set. Returns whether the bytes actually reached {@code action_groups.json}.
     *
     * <p>The boolean is load-bearing, per Invariant 7 in
     * {@code docs/AUTOMATION-PUBLISH-INVARIANTS.md} ("a failed write must be REPORTED, not
     * swallowed"): this used to be {@code void} and only logged, so a read-only or full
     * {@code /data/local/tmp/.automations} let a save/import mutate the in-memory map, answer the
     * user {@code success:true}, and then lose every group at the next daemon restart — the exact
     * "confirming a save that vanishes at the next boot" the invariant forbids.
     */
    public static boolean saveToFile() {
        synchronized (SAVE_LOCK) {
            if (!HOME.exists()) HOME.mkdirs();
            byte[] bytes = toJson().toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(TMP)) {
                fos.write(bytes);
                fos.getFD().sync();
            } catch (IOException e) {
                logger.error("Failed to write action-groups scratch file");
                return false;
            }
            if (CONFIG.exists()) copyFile(CONFIG, BACKUP);
            if (!TMP.renameTo(CONFIG)) {
                logger.error("Failed to promote action-groups scratch file");
                return false;
            }
            return true;
        }
    }

    public static void loadFromFile() {
        synchronized (SAVE_LOCK) {
            if (tryLoadFrom(CONFIG)) return;
            if (BACKUP.exists() && tryLoadFrom(BACKUP)) {
                logger.info("Recovered action groups from backup");
            }
        }
    }

    private static boolean tryLoadFrom(File file) {
        if (!file.exists()) return false;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Map<String, Group> loaded = new LinkedHashMap<>();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject g = json.optJSONObject(id);
                if (g == null) continue;
                String name = g.optString("name", "").trim();
                JSONArray actionsJson = g.optJSONArray("actions");
                if (name.isEmpty() || actionsJson == null) continue;
                List<AutomationAction> actions = Automation.parseActionsPublic(actionsJson);
                if (actions == null) continue; // skip a corrupt group, keep the rest
                loaded.put(id, new Group(name, actions));
            }
            groups = loaded;   // whole-map publish, same reason as the mutators
            return true;
        } catch (Exception e) {
            logger.error("Failed to load action groups from " + file.getName());
            return false;
        }
    }

    private static void copyFile(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (IOException e) {
            logger.error("Failed to back up action groups");
        }
    }
}
