package com.overdrive.app.server;

import android.content.Context;

import com.overdrive.app.byd.BodyworkSeatProbe;
import com.overdrive.app.byd.PositionStore;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.daemon.DaemonBootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OverDrive-native seat/mirror position store API (feature: "seat positions").
 * Runs in the uid-2000 daemon — the only process that can read/write BYD geometry —
 * and is the endpoint the a11y "record on long-press" trigger (in the app UI process)
 * POSTs to via {@link com.overdrive.app.util.DaemonHttpClient}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/positions}                    — list stored positions</li>
 *   <li>{@code POST /api/positions/capture?slot=N&name=..} — read the live full bundle and
 *       upsert it as the captured entry for native slot N (1..3). Fired by the long-press hook.</li>
 *   <li>{@code POST /api/positions/apply?id=..}         — apply a stored position (moves seat+mirrors,
 *       full spi.p7.m() two-batch sequence via {@link BodyworkSeatProbe#applyFull}). Movement-gated.
 *       Also accepts the id in a JSON body, which is how the automation action reaches it.</li>
 *   <li>{@code POST /api/positions/delete?id=..}        — remove a stored position</li>
 *   <li>{@code POST /api/positions/create?name=..&parts=..} — save the live state as a new user
 *       entry. {@code parts} is {@code all} (default), {@code geometry} or {@code ambient}.</li>
 *   <li>{@code POST /api/positions/save?id=..&parts=..} — save the live state over a user entry.
 *       A part not asked for is left as it was, so ambient can be added to an existing
 *       geometry-only position without re-posing the seat.</li>
 *   <li>{@code POST /api/positions/rename?id=..&name=..} — rename a user entry, id unchanged</li>
 *   <li>{@code POST /api/positions/alias?id=..&alias=..} — alias a CAPTURED entry; empty clears</li>
 * </ul>
 *
 * <p>Only {@code /api/positions/apply} is reachable from an automation. Everything else here
 * creates, overwrites or destroys stored positions, which an {@code ApiAction} has no business
 * doing — see {@code HttpServer.AUTOMATION_ALLOWED_PREFIXES}, which lists the exact apply path
 * rather than the {@code /api/positions/} prefix for precisely that reason.
 */
public final class PositionsApiHandler {

    private static final String TAG = "PositionsApi";

    private PositionsApiHandler() {}

    /**
     * The owner's selected vehicle model, or null when unset. Null is deliberately NOT treated as
     * a Seal: {@code VehicleModelSelection} exists because fresh installs used to write
     * {@code modelId=seal} and camera auto-configuration then treated every unconfigured BYD as
     * one. Unknown means unknown.
     */
    private static String resolvedModel() {
        try {
            return com.overdrive.app.config.UnifiedConfigManager.getSelectedVehicleModelId();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * True when every axis in a read-back bundle is the 127.5 not-equipped sentinel, i.e. the
     * reads failed rather than the car reporting a real pose. 127.5 for SOME axes is normal
     * (a trim without an electric steering column reads it).
     */
    private static boolean allSentinel(JSONObject axes) {
        if (axes == null || axes.length() == 0) return false;
        for (java.util.Iterator<String> it = axes.keys(); it.hasNext(); ) {
            double v = axes.optDouble(it.next(), Double.NaN);
            if (!Double.isNaN(v) && Math.abs(v - 127.5) > 0.01) return false;
        }
        return true;
    }

    /**
     * The honest reason a store operation returned nothing, or null when the store is fine and
     * the caller's own message applies. Without this an unreadable store surfaces as "no
     * position with id=X" or "the name is too long" — user error the user cannot act on.
     */
    private static String storeFault() {
        try {
            if (!PositionStore.getInstance().isReadable()) {
                return "the saved-positions file could not be read; nothing was changed. "
                        + "Its last good copy is kept alongside it as seat_positions.json.bak";
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void sendStoreOrOwnError(java.io.OutputStream out, String ownMessage) throws Exception {
        String fault = storeFault();
        HttpResponse.sendJsonError(out, fault != null ? fault : ownMessage);
    }

    private static Context resolveContext() {
        Context ctx = null;
        try { ctx = CameraDaemon.getAppContext(); } catch (Throwable ignore) {}
        if (ctx == null) { try { ctx = DaemonBootstrap.getContext(); } catch (Throwable ignore) {} }
        return ctx;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path;
        Map<String, String> q = new LinkedHashMap<>();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            pathOnly = path.substring(0, qIdx);
            for (String pair : path.substring(qIdx + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) q.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }

        // GET /api/positions  (list)
        if (pathOnly.equals("/api/positions") && "GET".equals(method)) {
            JSONObject r = new JSONObject();
            r.put("positions", listWithResolvedNames());
            // Car state rides along with the list so the management page can gate its
            // buttons without a second round trip. Both are advisory for the UI — the
            // authority is still applyFull's own gate, which the UI cannot talk its way past.
            // ACC off matters most: the seat motors are unpowered, so a write is accepted
            // (code 0) and does nothing, which would otherwise look like success.
            putGateState(r);
            String model = resolvedModel();
            r.put("modelId", model != null ? model : JSONObject.NULL);
            r.put("modelConfirmed", PositionStore.isModelConfirmed(model));
            r.put("modelAcknowledged", PositionStore.getInstance().isModelAcknowledged(model));
            // Which DiLink profile is signed in right now. Opt-in via ?withProfile=1
            // because resolving it shells out to the `content` binary (an in-process
            // ContentResolver is refused: package android != uid 2000), and this
            // endpoint is also polled for the acc/movement gate. Callers that need to
            // know whose Pos 1/2/3 the captured entries belong to ask for it; the
            // pollers do not pay for it.
            if ("1".equals(q.get("withProfile")) || "YES".equals(q.get("withProfile"))) {
                String profile = null;
                try {
                    Context ctx = resolveContext();
                    if (ctx != null) {
                        String[] ps = readProfileSlot(ctx, 1);
                        if (ps != null && ps.length > 0) profile = ps[0];
                    }
                } catch (Throwable ignore) { }
                r.put("currentProfile", profile != null ? profile : JSONObject.NULL);
            }
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        // Live geometry, so the management page can show what the car is in right now and
        // the user can pose the seat and then save what they see. Read-only; deliberately
        // separate from /api/debug/seat/read, which is a probe with a debug posture.
        if (pathOnly.equals("/api/positions/current") && "GET".equals(method)) {
            JSONObject axes = readLive(out);
            if (axes == null) return true;
            JSONObject cur = new JSONObject().put("axes", axes);
            putGateState(cur);
            // Live ambient rides along so the page can show what the car is set to now and
            // offer "save this". Absent rather than empty when unreadable, so the UI can tell
            // "the car did not say" from "the lights are off".
            JSONObject ambient = readLiveAmbient();
            if (ambient != null && ambient.length() > 0) cur.put("ambient", ambient);
            // The colour swatches, and how many of them THIS car has. The palette is a
            // static table, but the bound is a HAL read and varies by trim (6/30/63/126),
            // so a picker built from the table alone would offer colours the car rejects.
            try {
                Context cctx = resolveContext();
                boolean diLink5 = com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
                int colourMax;
                String[] palette = null;
                if (diLink5) {
                    // One bridge read for both: the car's colour count and, if reported, its palette.
                    com.overdrive.app.byd.VehicleActuatorBridge.DiLink5AmbientColours read =
                            com.overdrive.app.byd.VehicleActuatorBridge.readDiLink5AmbientColours(cctx);
                    colourMax = read.count == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                            ? 31 : Math.min(read.count, 31);
                    // A 7-colour DI5 car numbers its own named colours, not the first 7 of the table.
                    palette = read.palette != null ? read.palette
                            : com.overdrive.app.byd.light.AmbientPalette.paletteFor(colourMax, true);
                } else {
                    colourMax = com.overdrive.app.byd.AmbientProbe.colourMax(cctx);
                }
                cur.put("ambientColourMax", colourMax);
                cur.put("ambientPalette",
                        new JSONArray(java.util.Arrays.asList(palette != null ? palette
                                : com.overdrive.app.byd.light.LightConstants.AMBIENT_COLOURS)));
            } catch (Throwable ignore) { }
            HttpResponse.sendJson(out, cur.toString());
            return true;
        }
        if (pathOnly.equals("/api/positions/capture")) return handleCapture(out, q);
        if (pathOnly.equals("/api/positions/apply"))   return handleApply(out, q, body);
        if (pathOnly.equals("/api/positions/delete"))  return handleDelete(out, q, body);
        if (pathOnly.equals("/api/positions/create"))  return handleCreate(out, q, body);
        if (pathOnly.equals("/api/positions/save"))    return handleSave(out, q, body);
        if (pathOnly.equals("/api/positions/rename"))  return handleRename(out, q, body);
        if (pathOnly.equals("/api/positions/alias"))   return handleAlias(out, q, body);
        if (pathOnly.equals("/api/positions/ambient-colour")) return handleAmbientColour(out, q, body);

        HttpResponse.sendError(out, 404, "Unknown positions endpoint");
        return true;
    }

    private static void putGateState(JSONObject target) {
        try { target.put("acc", com.overdrive.app.monitor.AccMonitor.isAccOn()); }
        catch (Throwable ignore) { }
        try {
            String reason =
                    com.overdrive.app.byd.routing.DrivingSafetyGuard.getMovementBlockReason();
            boolean movementBlocked = reason != null;
            target.put("movementBlocked", movementBlocked);
            if (reason != null) {
                target.put("movementBlockReason", reason);
            }
            target.put("positioningBlocked",
                    com.overdrive.app.byd.routing.DrivingSafetyGuard.isGuardEnabled(
                            com.overdrive.app.byd.routing.DrivingSafetyGuard.GUARD_POSITIONING)
                            && movementBlocked);
        } catch (Throwable ignore) { }
    }

    /**
     * Read a parameter from either the query string or a JSON body, query first.
     *
     * <p>Both forms are needed. The management UI posts query parameters, but an automation
     * {@code ApiAction} renders a JSON body from a template ({@code {"id":"${id}"}}) like every
     * other action in the catalog, so accepting only {@code ?id=} would make this endpoint the
     * odd one out. Query values are URL-decoded here — the splitter above deliberately does not
     * decode, so a name with a space or a Norwegian vowel would otherwise arrive percent-encoded.
     */
    private static String param(Map<String, String> q, String body, String key) {
        String v = q.get(key);
        if (v != null && !v.isEmpty()) {
            try {
                return java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Throwable t) {
                return v;
            }
        }
        if (body != null && !body.trim().isEmpty()) {
            try {
                String s = new JSONObject(body).optString(key, "");
                if (!s.isEmpty()) return s;
            } catch (Throwable ignore) {
                // not JSON, or key absent — fall through
            }
        }
        return null;
    }

    /** Read the live geometry and upsert it under native slot N. */
    private static boolean handleCapture(OutputStream out, Map<String, String> q) throws Exception {
        Integer slot = parseInt(q.get("slot"));
        if (slot == null || slot < 1 || slot > 3) {
            HttpResponse.sendJsonError(out, "capture needs slot=1..3");
            return true;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("error", "Daemon Context unavailable").toString());
            return true;
        }
        JSONObject axes = BodyworkSeatProbe.readFullBundle(ctx);
        if (axes.length() == 0) {
            HttpResponse.sendJsonError(out, "read of live geometry returned nothing (bodywork device unavailable?)");
            return true;
        }
        // A failed read is stored as the 127.5 not-equipped sentinel, so a bundle that is ALL
        // sentinel means the reads failed, not that the car has no seat. Capture upserts by
        // slot, so accepting it would replace a good stored position with an unusable one.
        if (allSentinel(axes)) {
            HttpResponse.sendJsonError(out, "live geometry read back as all-unavailable; "
                    + "not overwriting the stored position for slot " + slot);
            return true;
        }
        // BYD's Pos 1/2/3 are per-logged-in-profile, so key captures by profile. A captured
        // entry MIRRORS the car: its name is "<nickName> - <car slot name>", both read live
        // from the DiLink account content provider, and it is NOT user-renameable in our UI
        // (source="captured"). Freely-named entries are the separate user-created profiles.
        String[] ps = readProfileSlot(ctx, slot);   // [nickName, slotName]
        String profile = ps[0];
        String slotName = (ps[1] != null) ? ps[1] : ("Posisjon " + slot);
        String name = (profile != null ? profile : "default") + " - " + slotName;
        long now = System.currentTimeMillis();
        // A captured entry mirrors the car, so it takes the ambient state too — the whole
        // point of storing it here is that the car's own slots carry geometry ONLY, so an
        // OverDrive mirror that also remembers the lighting is strictly more than the native
        // position it shadows. Null when the car will not report it; never a default.
        JSONObject ambient = com.overdrive.app.byd.AmbientProbe.read(ctx);
        JSONObject entry = PositionStore.getInstance().upsertCaptured(profile, slot, name, axes, ambient, now);
        if (entry == null) {
            // Store unreadable or the write did not land — never confirm a capture that is not on disk.
            HttpResponse.sendJsonError(out, "capture could not be saved; the stored position is unchanged");
            return true;
        }
        log("captured profile=" + profile + " slot=" + slot + " name=" + name
                + " model=" + resolvedModel() + " axes=" + axes);
        // Confirm the capture on screen. Without this the long-press is completely silent from
        // OverDrive's side — the car shows its own feedback for ITS save, so the user has no way
        // to tell whether OverDrive mirrored it or quietly missed it (which is exactly what
        // happened for every long-press in the floating widget until it was supported).
        // TYPE_APPLICATION_OVERLAY, so it draws above the BYD widget rather than behind it.
        // TOP, not bottom: the long-press happens in BYD's own UI, so the user's attention is
        // already there and a bottom pill is easy to miss (Pål wasn't sure he'd seen it at all).
        // "long" too — this is the only signal that OverDrive mirrored the save, so it is worth
        // more than the default couple of seconds.
        try {
            com.overdrive.app.byd.MessageOverlayController.showToast(
                    Messages.get("messages.seat_position_captured", name),
                    "long", "top", "info");
        } catch (Throwable t) {
            log("capture toast failed (capture itself is unaffected): " + t);
        }
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * Read the current DiLink account nickName + the car's name for a given slot from the
     * account content provider (content://com.byd.accountProvider/driver_pos_msg → columns
     * "nickName", "driverPos_1|2|3"). Returns {nickName, slotName}; either element is null if
     * unavailable.
     *
     * <p>Shells out to the {@code content} binary rather than using our own
     * {@code ContentResolver}: the daemon's synthetic Context reports its package as
     * "android", so an in-process query throws {@code SecurityException: Given calling package
     * android does not match caller's uid 2000}. The {@code content} shim carries the correct
     * shell package identity for uid 2000 (exactly what `adb shell content query` uses, which
     * is confirmed to read this provider). Output line looks like:
     * {@code Row: 0 nickName=foo@bar, driverPos_1=Posisjon 1, driverPos_2=..., driverPos_3=...}
     */
    private static String[] readProfileSlot(Context ctx, int slot) {
        try {
            Process p = new ProcessBuilder(
                    "content", "query", "--uri", "content://com.byd.accountProvider/driver_pos_msg")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            String out = sb.toString();
            return new String[]{ field(out, "nickName"), field(out, "driverPos_" + slot) };
        } catch (Throwable t) {
            log("readProfileSlot (content shell) failed: " + t);
        }
        return new String[]{ null, null };
    }

    /**
     * Pull one {@code key=value} field out of a `content query` row. Values run to the next
     * ", <key>=" boundary or end of line, so a value may itself contain commas. Returns null
     * if absent/blank/the literal "NULL".
     */
    private static String field(String out, String key) {
        if (out == null) return null;
        // Boundary is the next ", <key>=" — the key can contain digits (driverPos_1),
        // so the char class MUST include 0-9 or the boundary misses and .*? runs to EOL.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(key) + "=(.*?)(?:, [A-Za-z0-9_]+=|$)",
                        java.util.regex.Pattern.MULTILINE)
                .matcher(out);
        if (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty() && !"NULL".equals(v)) return v;
        }
        return null;
    }

    /**
     * Save the live geometry as a NEW user-owned position. The management UI's "Save as new":
     * the user poses the seat with the physical controls, then names what the car is currently in.
     * There is no axis-level editing anywhere — a hand-typed value is a seat pose nobody chose.
     */
    private static boolean handleCreate(OutputStream out, Map<String, String> q, String body) throws Exception {
        String name = param(q, body, "name");
        if (name == null) { HttpResponse.sendJsonError(out, "create needs a name"); return true; }
        Parts parts = Parts.parse(param(q, body, "parts"));
        JSONObject axes = parts.geometry ? readLive(out) : null;
        if (parts.geometry && axes == null) return true;   // readLive already answered
        JSONObject ambient = parts.ambient ? readLiveAmbient() : null;
        if (parts.ambient && ambient == null) {
            HttpResponse.sendJsonError(out, "read of live ambient state returned nothing");
            return true;
        }
        JSONObject entry = PositionStore.getInstance().createUser(name, axes, ambient, System.currentTimeMillis());
        if (entry == null) { sendStoreOrOwnError(out, "name must be 1..60 characters, and at least one part must be saved"); return true; }
        log("created " + entry.optString("id") + " name=" + name);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * Overwrite an existing USER position with the live geometry ("Save here" on the row).
     * Captured entries are rejected by the store: they mirror the car, so their geometry only
     * ever comes from a capture.
     */
    private static boolean handleSave(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        if (id == null) { HttpResponse.sendJsonError(out, "save needs an id"); return true; }
        Parts parts = Parts.parse(param(q, body, "parts"));
        JSONObject axes = parts.geometry ? readLive(out) : null;
        if (parts.geometry && axes == null) return true;
        JSONObject ambient = parts.ambient ? readLiveAmbient() : null;
        if (parts.ambient && ambient == null) {
            HttpResponse.sendJsonError(out, "read of live ambient state returned nothing");
            return true;
        }
        JSONObject entry = PositionStore.getInstance().updateParts(id, axes, ambient, System.currentTimeMillis());
        if (entry == null) {
            sendStoreOrOwnError(out, "no user position with id=" + id + " (captured positions cannot be overwritten)");
            return true;
        }
        log("saved over " + id);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /** Rename a USER position. The id is untouched so automations referencing it keep working. */
    private static boolean handleRename(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        String name = param(q, body, "name");
        if (id == null || name == null) { HttpResponse.sendJsonError(out, "rename needs an id and a name"); return true; }
        JSONObject entry = PositionStore.getInstance().rename(id, name);
        if (entry == null) {
            sendStoreOrOwnError(out, "no user position with id=" + id + ", or the name is not 1..60 characters");
            return true;
        }
        log("renamed " + id + " to " + name);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * The stored list with {@code name} resolved to the alias wherever one is set, and the car's
     * own name preserved as {@code carName}.
     *
     * <p>Resolving here rather than in each client is what makes an alias show up everywhere at
     * once — seat page, automation picker, capture toast and the home panel all read {@code name},
     * and none of them should have to know that captured entries have a second display name. The
     * raw {@code alias} still rides along so the management UI can prefill its editor and tell an
     * aliased entry from a plain one.
     *
     * <p>The store itself is left alone: {@code getById} (what apply and the automation action
     * use) keeps returning the car's name, because an automation resolves by id and its logs
     * should say what the car calls the position.
     */
    private static JSONArray listWithResolvedNames() {
        JSONArray src = PositionStore.getInstance().list();
        JSONArray outArr = new JSONArray();
        for (int i = 0; i < src.length(); i++) {
            JSONObject p = src.optJSONObject(i);
            if (p == null) continue;
            String alias = p.optString("alias", "").trim();
            if (alias.isEmpty()) { outArr.put(p); continue; }
            try {
                JSONObject copy = new JSONObject(p.toString());
                copy.put("carName", p.optString("name", ""));
                copy.put("name", alias);
                outArr.put(copy);
            } catch (Throwable t) {
                outArr.put(p);
            }
        }
        return outArr;
    }

    /**
     * Change the ambient COLOUR stored on a position, without touching the car.
     *
     * <p>The counterpart to capture: geometry is capture-only because a typed axis value is
     * a seat pose nobody chose, but a colour is a deliberate pick from a fixed palette, so
     * choosing one directly is the natural way to do it. Editing the stored value rather
     * than the car's live state means the position can be tuned without sitting in the car
     * with the lights on.
     *
     * <p>Applies to whichever zone is named ({@code front}, {@code rear}, or {@code both},
     * the default). Refuses a position with no ambient part rather than inventing one: a
     * colour alone is not an ambient capture, and a half-built ambient block would apply as
     * a colour change with no brightness, which is not what anyone saved.
     */
    private static boolean handleAmbientColour(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        Integer colour = parseInt(param(q, body, "colour"));
        if (id == null || colour == null) {
            HttpResponse.sendJsonError(out, "ambient-colour needs an id and a colour");
            return true;
        }
        JSONObject pos = PositionStore.getInstance().getById(id);
        if (pos == null) { sendStoreOrOwnError(out, "no position with id=" + id); return true; }
        JSONObject ambient = pos.optJSONObject("ambient");
        if (ambient == null || ambient.length() == 0) {
            HttpResponse.sendJsonError(out, "this position stores no ambient light to change");
            return true;
        }
        int max = 30;
        try { max = com.overdrive.app.byd.AmbientProbe.colourMax(resolveContext()); } catch (Throwable ignore) { }
        if (colour < 1 || colour > max) {
            HttpResponse.sendJsonError(out, "colour must be 1.." + max + " on this car");
            return true;
        }
        String zone = param(q, body, "zone");
        boolean front = zone == null || "both".equalsIgnoreCase(zone) || "front".equalsIgnoreCase(zone);
        boolean rear = zone == null || "both".equalsIgnoreCase(zone) || "rear".equalsIgnoreCase(zone);
        JSONObject next = new JSONObject(ambient.toString());
        boolean changed = false;
        if (front) changed |= setZoneColour(next, "front", colour);
        if (rear) changed |= setZoneColour(next, "rear", colour);
        // setZoneColour refuses to invent a zone the car never reported, so asking for a zone
        // this position has no block for changes nothing. Saying so beats a 200 that re-renders
        // the old colour and looks like the write was ignored at random.
        if (!changed) {
            HttpResponse.sendJsonError(out, "this position stores no "
                    + (zone == null ? "front or rear" : zone.toLowerCase())
                    + " ambient zone to change");
            return true;
        }
        JSONObject entry = PositionStore.getInstance().setAmbient(id, next);
        if (entry == null) { sendStoreOrOwnError(out, "could not update id=" + id); return true; }
        log("ambient colour " + id + " zone=" + (zone == null ? "both" : zone) + " -> " + colour);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * Set a zone's colour only when that zone was captured. Creating the zone here would
     * assert the car has it — and on a car whose rear zone never reported, an invented rear
     * block would start applying a colour to lights that do not exist.
     */
    private static boolean setZoneColour(JSONObject ambient, String zone, int colour) throws Exception {
        JSONObject z = ambient.optJSONObject(zone);
        if (z == null) return false;
        z.put("colour", colour);
        ambient.put(zone, z);
        return true;
    }

    /**
     * Set or clear the alias on a CAPTURED position. Separate from rename because the two act on
     * disjoint halves of the store: a user entry is named when it is saved and renamed here, while
     * a captured entry's name belongs to the car and is rebuilt on every capture, so the only way
     * to call it something else is to hang an alias off it.
     *
     * <p>An absent or empty {@code alias} clears it, and the entry goes back to showing the name
     * the car gave it.
     */
    private static boolean handleAlias(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        if (id == null) { HttpResponse.sendJsonError(out, "alias needs an id"); return true; }
        String alias = param(q, body, "alias");
        JSONObject entry = PositionStore.getInstance().setAlias(id, alias);
        if (entry == null) {
            sendStoreOrOwnError(out,
                "no captured position with id=" + id + ", or the alias is over 60 characters");
            return true;
        }
        log((alias == null || alias.trim().isEmpty() ? "cleared alias on " : "aliased ") + id);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * Read the live 13-axis bundle, writing the error response itself and returning null when it
     * cannot. Shared by create and save.
     */
    /**
     * Which parts of a position a save should capture. A captured entry always takes
     * everything (it mirrors the car); this is the user-created side, where "save just my
     * lighting onto this seat position" is a real thing to want.
     *
     * <p>Unknown values fall back to everything rather than erroring: the caller asked to
     * save, and saving more than asked is recoverable while saving nothing looks like the
     * button is broken.
     */
    private static final class Parts {
        final boolean geometry;
        final boolean ambient;
        private Parts(boolean geometry, boolean ambient) {
            this.geometry = geometry;
            this.ambient = ambient;
        }
        static Parts parse(String v) {
            String s = (v == null) ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("geometry") || s.equals("seat")) return new Parts(true, false);
            if (s.equals("ambient") || s.equals("light")) return new Parts(false, true);
            return new Parts(true, true);
        }
    }

    /** Live interior-light state, or null when the car will not report it. */
    private static JSONObject readLiveAmbient() {
        Context ctx = resolveContext();
        if (ctx == null) return null;
        return com.overdrive.app.byd.AmbientProbe.read(ctx);
    }

    private static JSONObject readLive(OutputStream out) throws Exception {
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("error", "Daemon Context unavailable").toString());
            return null;
        }
        JSONObject axes = BodyworkSeatProbe.readFullBundle(ctx);
        if (axes.length() == 0) {
            HttpResponse.sendJsonError(out, "read of live geometry returned nothing (bodywork device unavailable?)");
            return null;
        }
        // readFullBundle fills unreadable axes with the sentinel, so it is never empty once the
        // device handle resolves — the length check above cannot catch a wholly failed read.
        // Storing that would produce a position that applies cleanly and moves nothing.
        if (allSentinel(axes)) {
            HttpResponse.sendJsonError(out, "the car reported no seat geometry (every axis read back "
                    + "as unavailable); nothing was saved");
            return null;
        }
        return axes;
    }

    /** Apply a stored position (moves seat + mirrors). */
    private static boolean handleApply(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        JSONObject pos = PositionStore.getInstance().getById(id);
        if (pos == null) { sendStoreOrOwnError(out, "no position with id=" + id); return true; }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("error", "Daemon Context unavailable").toString());
            return true;
        }
        boolean hasGeometry = PositionStore.hasGeometry(pos);
        boolean hasAmbient = PositionStore.hasAmbient(pos);
        if (!hasGeometry && !hasAmbient) {
            HttpResponse.sendJsonError(out, "position stores nothing to apply");
            return true;
        }
        JSONObject axes = pos.optJSONObject("axes");

        // Applying on a model the axis map has not been confirmed against needs one explicit
        // acknowledgement, not a refusal. The write is a round trip — every value was read from
        // these same properties on this same car when the position was captured — so the realistic
        // worst case on a mismatched id map is restoring some other property's own earlier value,
        // on a parked car. Blocking it outright would leave the confirmed-model list frozen at the
        // one car it was written on. Answered as 200 with needsModelAck so the UI can explain and
        // ask, rather than as an error the user has to decode.
        String model = resolvedModel();
        PositionStore store = PositionStore.getInstance();
        String ackRaw = param(q, body, "ack");
        boolean acked = "YES".equals(ackRaw) || "1".equals(ackRaw);
        boolean ackPersisted = true;
        // The acknowledgement exists because the AXIS ID MAP is what might differ on an
        // unconfirmed model. Ambient rides on named SDK calls with the zone as an argument,
        // not on a per-car id table, so an ambient-only position has nothing to mis-address
        // and is not worth interrupting the user for.
        if (hasGeometry && !PositionStore.isModelConfirmed(model) && !store.isModelAcknowledged(model)) {
            if (!acked) {
                JSONObject r = new JSONObject();
                r.put("needsModelAck", true);
                // A headless caller must not read this pre-flight stop as an
                // applied position: ApiAction treats a 2xx JSON body without an
                // explicit success:false as success, so the applySeatPosition
                // automation reported "applied" while nothing moved. The web UI
                // is unaffected — it acknowledges up front and already treats
                // needsModelAck as a failure condition (seat-positions.js).
                r.put("success", false);
                r.put("error", Messages.get("errors.seatpos_model_not_acknowledged"));
                r.put("modelId", model != null ? model : JSONObject.NULL);
                r.put("appliedId", id);
                HttpResponse.sendJson(out, r.toString());
                return true;
            }
            ackPersisted = store.acknowledgeModel(model);
        }
        JSONObject res = new JSONObject();

        // Geometry, when the position carries it. applyFull writes both batches and returns one
        // aggregate `accepted`; a batch-1-ok / batch-2-failed run leaves the mirrors moved and
        // the seat where it was, so it must NOT read as applied.
        if (hasGeometry) {
            Map<String, Float> overrides = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = axes.keys(); it.hasNext(); ) {
                String k = it.next();
                overrides.put(k, (float) axes.optDouble(k, Double.NaN));
            }
            res = BodyworkSeatProbe.applyFull(ctx, overrides);
        }

        // Ambient, when the position carries it. Independent of the geometry write: a
        // different device, no batching, and no gear gate — so an ambient-only position is
        // free to apply while driving, which is exactly what makes one worth having.
        JSONObject ambientResult = null;
        if (hasAmbient) {
            try {
                ambientResult = com.overdrive.app.byd.AmbientProbe.apply(
                        ctx, pos.optJSONObject("ambient"));
            } catch (Throwable t) {
                ambientResult = new JSONObject()
                        .put("applied", false)
                        .put("reason", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            res.put("ambient", ambientResult);
        }

        res.put("appliedId", id);
        // The apply itself still went ahead — the user just acknowledged — but say so, or they
        // are silently re-prompted on every future apply.
        if (!ackPersisted) res.put("ackNotPersisted", true);
        JSONArray applied = new JSONArray();
        boolean diLink5 =
                com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
        boolean geometryOk = !hasGeometry
                || (res.optBoolean("accepted", false)
                    && (!diLink5 || !res.optBoolean("inert", false)));
        boolean ambientOk = !hasAmbient
                || !diLink5
                || (ambientResult != null && ambientResult.optBoolean("applied", false));
        if (hasGeometry && geometryOk) applied.put("geometry");
        if (hasAmbient && ambientOk) applied.put("ambient");
        res.put("appliedParts", applied);
        if (diLink5) res.put("success", geometryOk && ambientOk);
        // Report the honest outcome: the HAL refusing the write, and the HAL accepting a write
        // the unpowered motors cannot act on, are both "the seat did not move".
        if (hasGeometry && !geometryOk && !res.has("error") && !res.has("skipped")) {
            res.put("error", Messages.get("errors.seatpos_seat_write_unconfirmed")
                    + (res.has("exception") ? " (" + res.optString("exception") + ")" : ""));
        } else if (hasAmbient && !ambientOk && !res.has("error")) {
            res.put("error", Messages.get("errors.seatpos_ambient_write_unconfirmed"));
        } else if (res.optBoolean("inert", false)) {
            res.put("warning", res.optString("reason", "vehicle off: nothing moved"));
        }
        log("apply " + id + " model=" + model + " geometry=" + hasGeometry + " ambient=" + hasAmbient
                + " -> batch1=" + res.optJSONObject("batch1") + " batch2=" + res.optJSONObject("batch2")
                + " ambientRes=" + res.optJSONObject("ambient"));
        HttpResponse.sendJson(out, res.toString());
        return true;
    }

    private static boolean handleDelete(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        PositionStore store = PositionStore.getInstance();
        // remove() returns false for "not there" AND for "could not persist". The UI only checks
        // `error`, so a failed delete otherwise looked like a success and the row came back on
        // the next load with nothing said.
        boolean existed = store.getById(id) != null;
        boolean removed = store.remove(id);
        if (!removed && existed) {
            sendStoreOrOwnError(out, "could not delete id=" + id + "; it is still stored");
            return true;
        }
        HttpResponse.sendJson(out, new JSONObject().put("removed", removed).put("id", id).toString());
        return true;
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static void log(String s) {
        try { CameraDaemon.log(TAG + ": " + s); } catch (Throwable ignore) {}
    }
}
