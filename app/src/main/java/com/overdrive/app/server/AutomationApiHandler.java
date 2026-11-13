package com.overdrive.app.server;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.Iterator;

/**
 * HTTP routes for automations.
 *
 * Endpoints:
 * - GET    /api/automations/list            → List all automations
 * - GET    /api/automations/schema          → Get the automation schema
 * - POST   /api/automations/automation      → Create a new automation
 * - PUT    /api/automations/automation/{id} → Update an existing automation by id
 * - DELETE /api/automations/automation/{id} → Delete an existing automation by id
 * - POST   /api/automations/test/{id}       → Run the actions for an automation by id
 * - POST   /api/automations/mode/{id}       → Set automatic/manual/disabled mode
 * - POST   /api/automations/disable/{id}    → Disable an existing automation by id
 * - GET    /api/automations/state           → Live value of every observed signal (editor hints)
 * - GET    /api/automations/export          → Download all automations as a JSON backup
 * - POST   /api/automations/import          → Restore an automation backup (merge or replace)
 * - POST   /api/action-groups/{id}/run      → Run a reusable action group's actions now
 * - GET    /api/action-groups/export        → Download all action groups as a JSON backup
 * - POST   /api/action-groups/import        → Restore an action-group backup (merge or replace)
 */
public final class AutomationApiHandler {

    private static final com.overdrive.app.logging.DaemonLogger logger =
            com.overdrive.app.logging.DaemonLogger.getInstance("AutomationApi");

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/automations/list") && method.equals("GET")) {
            return getAutomations(out);
        }
        if ((path.equals("/api/automations/schema")
                || path.startsWith("/api/automations/schema?"))
                && method.equals("GET")) {
            return getSchema(queryParam(path, "lang"), out);
        }
        // Lightweight [{id,name}] list for the "Control Automation" target picker.
        // Optional ?self=<id> excludes that automation (so a rule can't pick itself).
        if (path.startsWith("/api/automations/picker") && method.equals("GET")) {
            // decodeId, not URLDecoder directly: a malformed escape ("?self=%") makes
            // URLDecoder throw IllegalArgumentException, which escapes handle() and leaves the
            // client with a closed socket and no HTTP response at all.
            String self = null;
            int q = path.indexOf("?self=");
            if (q >= 0) self = decodeId(path.substring(q + "?self=".length()));
            HttpResponse.sendJson(out, Automations.listForPicker(self).toString());
            return true;
        }
        if (path.equals("/api/automations/automation") && method.equals("POST")) {
            return addOrUpdateAutomation(null, body, out);
        }
        if (path.startsWith("/api/automations/automation/") && method.equals("PUT")) {
            String id = decodeId(path.substring("/api/automations/automation/".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            // PUT targets an existing automation by id. Reject an unknown id with 404 rather than
            // silently creating a new automation under a caller-chosen id (creation is POST, which mints
            // a UUID). Keeps the route consistent with DELETE/test/disable, which already 404 on unknown.
            if (!Automations.exists(id)) {
                HttpResponse.sendError(out, 404, "Automation not found.");
                return true;
            }
            return addOrUpdateAutomation(id, body, out);
        }
        if (path.startsWith("/api/automations/automation/") && method.equals("DELETE")) {
            String id = decodeId(path.substring("/api/automations/automation/".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            return deleteAutomation(id, out);
        }
        if (path.startsWith("/api/automations/test/") && method.equals("POST")) {
            String id = decodeId(path.substring("/api/automations/test/".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            return testAutomation(id, out);
        }
        if (path.startsWith("/api/automations/mode/") && method.equals("POST")) {
            String id = decodeId(path.substring("/api/automations/mode/".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            return setAutomationMode(id, body, out);
        }
        if (path.startsWith("/api/automations/disable/") && method.equals("POST")) {
            String id = decodeId(path.substring("/api/automations/disable/".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            return disableAutomation(id, body, out);
        }
        // Automation-wide settings (not per-automation): shell-action permission
        // plus the global driving-safety guard switches shown on this page and on
        // Vehicle Control's Safety tab (the one writer for both).
        // Live signal values for the editor's "reads X right now" hints. Read-only.
        if (path.equals("/api/automations/state") && method.equals("GET")) {
            return getState(out);
        }
        if (path.equals("/api/automations/settings") && method.equals("GET")) {
            return getSettings(out);
        }
        if (path.equals("/api/automations/settings") && method.equals("POST")) {
            return saveSettings(body, out);
        }
        // Export the full automation set as a downloadable JSON backup, and import one
        // back (merge or replace). Import validates every entry via the same fromJson
        // gate as a single create, so a bad file can't corrupt the store.
        if (path.equals("/api/automations/export") && method.equals("GET")) {
            return exportAutomations(out);
        }
        if (path.equals("/api/automations/import") && method.equals("POST")) {
            return importAutomations(body, out);
        }
        // Publish an EXTERNAL automation event from the app process (things the daemon
        // can't observe itself — e.g. phone call state, which needs an app-process
        // TelephonyManager). Tightly whitelisted so the relay can only touch a known,
        // safe set of event keys, never arbitrary automation state.
        if (path.equals("/api/automations/event") && method.equals("POST")) {
            return publishExternalEvent(body, out);
        }
        // Reusable action groups: list / create / update / delete. A group is a named,
        // validated action sequence invoked by the "actionGroup" action (and keymap).
        if (path.equals("/api/action-groups/list") && method.equals("GET")) {
            HttpResponse.sendJson(out, com.overdrive.app.automation.ActionGroups.listJson().toString());
            return true;
        }
        if (path.equals("/api/action-groups") && method.equals("GET")) {
            HttpResponse.sendJson(out, com.overdrive.app.automation.ActionGroups.toJson().toString());
            return true;
        }
        // Export / import the action-group set, same envelope + merge semantics as
        // /api/automations/export|import. MUST be matched before the generic
        // /api/action-groups/{id} PUT/DELETE below, or "export"/"import" would be decoded as
        // a group id (the same ordering trap the "{id}/run" comment below documents).
        if (path.equals("/api/action-groups/export") && method.equals("GET")) {
            return exportActionGroups(out);
        }
        if (path.equals("/api/action-groups/import") && method.equals("POST")) {
            return importActionGroups(body, out);
        }
        // Run a group's actions NOW, without an automation wrapper. Must be tested before the
        // generic /api/action-groups/{id} POST below, or "{id}/run" would be read as an id.
        // Length guard, not just the prefix+suffix test: for the path "/api/action-groups/run"
        // BOTH match (the suffix consumes the prefix's own slash), and the substring below would
        // then be called with begin(19) > end(18) — a StringIndexOutOfBoundsException thrown
        // before any guard, which the server's outer handler can only log, leaving the client
        // hanging with no HTTP response. ">=" so the empty-id path ("…//run") still reaches
        // isBlankId and answers a clean 400 instead of falling through to a 404.
        if (path.startsWith("/api/action-groups/") && path.endsWith("/run") && method.equals("POST")
                && path.length() >= "/api/action-groups/".length() + "/run".length()) {
            String id = decodeId(
                    path.substring("/api/action-groups/".length(), path.length() - "/run".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            return runActionGroup(id, out);
        }
        if (path.equals("/api/action-groups") && method.equals("POST")) {
            return saveActionGroup(null, body, out);
        }
        if (path.startsWith("/api/action-groups/") && method.equals("PUT")) {
            String id = decodeId(path.substring("/api/action-groups/".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            return saveActionGroup(id, body, out);
        }
        if (path.startsWith("/api/action-groups/") && method.equals("DELETE")) {
            String id = decodeId(path.substring("/api/action-groups/".length()));
            if (isBlankId(id)) return rejectBlankId(out);
            com.overdrive.app.automation.ActionGroups.SaveResult removed =
                    com.overdrive.app.automation.ActionGroups.deleteWithResult(id);
            if (!removed.isValid()) {
                HttpResponse.sendError(out, 404, "Action group not found.");
            } else if (!removed.persisted) {
                HttpResponse.sendJsonError(out,
                        "Action group was removed but the change could not be saved to disk - "
                                + "it would come back on restart. Check storage and try again.");
            } else {
                HttpResponse.sendJsonSuccess(out);
            }
            return true;
        }
        return false;
    }

    /** Create/update an action group from { name, actions:[...] }. */
    private static boolean saveActionGroup(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) { HttpResponse.sendError(out, 400, "Missing body."); return true; }
        JSONObject json;
        try { json = new JSONObject(body); }
        catch (JSONException e) { HttpResponse.sendError(out, 400, "Malformed JSON body."); return true; }
        com.overdrive.app.automation.ActionGroups.SaveResult saved =
                com.overdrive.app.automation.ActionGroups.saveWithResult(id, json);
        if (saved.isValid()) {
            // Valid group, but the write may not have landed — report that rather than
            // confirming a save that vanishes at the next restart (Invariant 7). The result is
            // per-call, so a concurrent mutation on another request thread cannot mask this.
            if (!saved.persisted) {
                // sendJsonError (200 + {success:false,error}) rather than sendError: sendError
                // puts the text in the HTTP STATUS LINE with a text/plain body, so the frontend's
                // resp.json() rejects and the user gets a generic toast instead of this reason.
                HttpResponse.sendJsonError(out,
                        "Action group could not be saved to disk. It is active now but would be "
                                + "lost on restart — check storage and try again.");
                return true;
            }
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("id", saved.id);
            HttpResponse.sendJson(out, resp.toString());
        } else {
            HttpResponse.sendError(out, 400, "Invalid action group (need a name and valid actions).");
        }
        return true;
    }

    /**
     * Whitelisted external-event ingress. Body: { "event": "&lt;key&gt;", "value": "&lt;string&gt;" }.
     * Only the keys in {@link Automations#publishExternalEvent} are accepted; anything
     * else is rejected. This is the app→daemon bridge for signals the daemon can't read
     * directly (call state today; a small, curated set going forward).
     */
    private static boolean publishExternalEvent(String body, OutputStream out) throws Exception {
        if (body == null || body.isBlank()) { HttpResponse.sendJsonError(out, "Empty request body"); return true; }
        JSONObject json;
        try { json = new JSONObject(body); }
        catch (JSONException e) { HttpResponse.sendError(out, 400, "Malformed JSON body."); return true; }
        String event = json.optString("event", "");
        String value = json.optString("value", "");
        boolean ok = Automations.publishExternalEvent(event, value);
        JSONObject resp = new JSONObject();
        resp.put("success", ok);
        if (!ok) resp.put("error", "Unknown or unsupported external event: " + event);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /** GET the full set as a backup { version, exportedAt, automations:{...} }. */
    private static boolean exportAutomations(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("version", 1);
        // Timestamp is informational only (the daemon has a real clock); the import
        // path ignores it and reads only `automations`.
        resp.put("exportedAt", System.currentTimeMillis());
        resp.put("automations", Automations.toJson());
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * POST an exported backup back. Body: { automations:{id:...}, replace?:bool } — or,
     * for convenience, a bare {id:...} map (treated as merge). replace=true wipes the
     * current set first; default merges (add/overwrite by id). Responds with the count
     * actually imported so the UI can report "imported N".
     */
    private static boolean importAutomations(String body, OutputStream out) throws Exception {
        if (body == null || body.isBlank()) { HttpResponse.sendJsonError(out, "Empty request body"); return true; }
        JSONObject json;
        try { json = new JSONObject(body); }
        catch (JSONException e) { HttpResponse.sendError(out, 400, "Malformed JSON body."); return true; }
        // Accept either the wrapped export shape or a bare id→automation map.
        JSONObject map = json.optJSONObject("automations");
        boolean replace = json.optBoolean("replace", false);
        if (map == null) map = json; // bare map (no wrapper) → merge
        int count = Automations.importAutomations(map, replace);
        JSONObject resp = new JSONObject();
        resp.put("success", count > 0);
        resp.put("imported", count);
        if (count == 0) resp.put("error", "No valid automations found in the file");
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * GET the action-group set as a backup {@code { version, exportedAt, actionGroups:{...} }}.
     * Deliberately a SEPARATE envelope key from the automation export's {@code automations}, so
     * neither import can silently swallow the other file: posting an action-group backup to
     * /api/automations/import finds no {@code automations} key, and the bare-map fallback there
     * rejects every entry (a group has no trigger/conditions), answering "no valid automations
     * found" instead of storing nonsense.
     */
    private static boolean exportActionGroups(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("version", 1);
        // Informational only, exactly like the automation export — the import path reads
        // nothing but `actionGroups`.
        resp.put("exportedAt", System.currentTimeMillis());
        resp.put("actionGroups", com.overdrive.app.automation.ActionGroups.toJson());
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * POST an exported action-group backup back. Body: {@code { actionGroups:{id:...},
     * replace?:bool }} — or a bare {@code {id:...}} map (treated as merge). Mirrors
     * {@link #importAutomations}: replace=true wipes first, default merges by id, and the count
     * is returned so the UI can report "imported N". Every group is validated by
     * {@link com.overdrive.app.automation.ActionGroups#importGroups} before anything is stored.
     */
    private static boolean importActionGroups(String body, OutputStream out) throws Exception {
        if (body == null || body.isBlank()) { HttpResponse.sendJsonError(out, "Empty request body"); return true; }
        JSONObject json;
        try { json = new JSONObject(body); }
        catch (JSONException e) { HttpResponse.sendError(out, 400, "Malformed JSON body."); return true; }
        JSONObject map = json.optJSONObject("actionGroups");
        boolean replace = json.optBoolean("replace", false);
        if (map == null) map = json; // bare map (no wrapper) → merge
        int count = com.overdrive.app.automation.ActionGroups.importGroups(map, replace);
        // -1 = parsed but NOT persisted. Answer 500 rather than confirming a save that would
        // vanish at the next daemon restart (Invariant 7).
        if (count < 0) {
            HttpResponse.sendJsonError(out,
                    "Action groups could not be saved to disk. They are active now but would be "
                            + "lost on restart - check storage and try again.");
            return true;
        }
        JSONObject resp = new JSONObject();
        resp.put("success", count > 0);
        resp.put("imported", count);
        if (count == 0) resp.put("error", "No valid action groups found in the file");
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * GET {@code { success, state:{ "<signal address>": value, … } }} — the live value of every
     * signal the daemon has observed, keyed by the same {@code ${signal:…}} address the editor
     * emits. Backs the "reads X right now" hint next to a signal picker, so a user does not have
     * to guess whether {@code gear} says {@code p} or {@code park}.
     *
     * <p>Read-only and side-effect-free: it does not seed, publish, or expire anything, so polling
     * it while the editor is open cannot perturb rule evaluation. Signals that have never been
     * observed (or whose value has expired) are simply absent, which the UI renders as "unknown"
     * rather than inventing a default.
     */
    private static boolean getState(OutputStream out) throws Exception {
        // Open the seed window FIRST: with no automation enabled, Automations.update() stores
        // nothing, so without this every signal read "not reported yet on this car" — worst of all
        // on a user's first automation. This lets the next telemetry snapshot store (never fire),
        // and the window lapses shortly after the editor stops polling.
        Automations.markEditorSeedActive();
        // Publish from the CURRENT snapshot right now rather than waiting for the next telemetry
        // build: that runs every 5s with ACC on but only every 90s parked, which is exactly when a
        // user sits in the car building a rule — a 90s blank hint reads as "my car doesn't report
        // this". getData() is the already-collected snapshot (no HAL round-trip), and the publish
        // is store-only while nothing is enabled, so this cannot fire a rule.
        try {
            com.overdrive.app.byd.BydVehicleData snapshot =
                    com.overdrive.app.byd.BydDataCollector.getInstance().getData();
            // seedForEditor, not bydEvent: the snapshot path DROPS every FAST_POLL_OWNED key
            // (gear, driveMode, seatbelt, turn signal, seat climate, beams, AC setpoint) because a
            // fast poller owns it — and those pollers only run once a rule references the key. In
            // the editor they therefore had no publisher at all, which is why gear read "not
            // reported yet on this car" whatever the car was doing. seedForEditor also drives each
            // owned key's live poller.
            com.overdrive.app.byd.BydDataCollector.getInstance()
                    .runWithDiLink5AutomationFreshness(() -> {
                        com.overdrive.app.automation.condition.BydEvent
                                .seedForEditor(snapshot);
                        com.overdrive.app.automation.condition.DynamicsEvent
                                .seedForEditor();
                        com.overdrive.app.automation.condition.BlindSpotEvent
                                .seedForEditor();
                        com.overdrive.app.automation.condition.EnergyRegenEvent
                                .seedForEditor();
                        com.overdrive.app.automation.condition.DoorEvent
                                .seedForEditor();
                    });
            com.overdrive.app.automation.condition.TimeEvent.seedForEditor();
            com.overdrive.app.automation.condition.NetworkEvent.seedForEditor();
        } catch (Throwable t) {
            // Best-effort: fall back to whatever the state map already holds.
            logger.warn("state seed publish failed: " + t.getMessage());
        }
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("state", Automations.stateSnapshotJson());
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /** GET automation-wide settings. Fresh read for cross-UID. */
    private static boolean getSettings(OutputStream out) throws Exception {
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("allowShell", com.overdrive.app.config.UnifiedConfigManager.isAutomationShellAllowed());
        resp.put("drivingSafety", DrivingSafetyGuard.getGuardSettings());
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /** POST a partial automation-wide settings update. */
    private static boolean saveSettings(String body, OutputStream out) throws Exception {
        if (body == null || body.isBlank()) { HttpResponse.sendJsonError(out, "Empty request body"); return true; }
        JSONObject request;
        try {
            request = new JSONObject(body);
        } catch (Exception e) { HttpResponse.sendJsonError(out, "Invalid JSON"); return true; }

        Boolean allowShell = null;
        if (request.has("allowShell")) {
            Object allow = request.opt("allowShell");
            if (!(allow instanceof Boolean)) {
                HttpResponse.sendJsonError(out, "allowShell must be a boolean");
                return true;
            }
            allowShell = (Boolean) allow;
        }

        JSONObject safetyUpdate = null;
        if (request.has("drivingSafety")) {
            JSONObject requested = request.optJSONObject("drivingSafety");
            if (requested == null || requested.length() == 0) {
                HttpResponse.sendJsonError(out, "drivingSafety must be a non-empty object");
                return true;
            }
            safetyUpdate = new JSONObject();
            Iterator<String> keys = requested.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object enabled = requested.opt(key);
                if (!DrivingSafetyGuard.isKnownGuard(key)) {
                    HttpResponse.sendJsonError(out, "Unknown drivingSafety setting: " + key);
                    return true;
                }
                if (!(enabled instanceof Boolean)) {
                    HttpResponse.sendJsonError(out, "drivingSafety." + key + " must be a boolean");
                    return true;
                }
                safetyUpdate.put(key, enabled);
            }
        }

        if (allowShell == null && safetyUpdate == null) {
            HttpResponse.sendJsonError(out, "No supported automation settings supplied");
            return true;
        }
        if (allowShell != null && safetyUpdate != null) {
            HttpResponse.sendJsonError(out, "Update allowShell or drivingSafety, not both");
            return true;
        }

        // Arming unattended shell needs a JWT-authenticated non-tunnel caller; the loopback
        // safety net is every local app. Disabling it, or re-saving the current value, is
        // always allowed. Mirrors the keymap allowAdvanced gate.
        if (allowShell != null && allowShell.booleanValue()
                && !HttpServer.canGrantShellAccess()) {
            // Fresh read only on the refusal candidate, so a stale cross-UID cache can't
            // reject a re-save of an already-enabled gate.
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            if (!com.overdrive.app.config.UnifiedConfigManager.isAutomationShellAllowed()) {
                HttpResponse.sendJson(out, 403, new JSONObject()
                        .put("success", false)
                        .put("error", "Automation shell needs a signed-in local session. "
                                + "Sign in again on the car, or reload this page; it "
                                + "cannot be enabled over a tunnel.")
                        .toString());
                return true;
            }
        }

        boolean ok = allowShell == null
                || com.overdrive.app.config.UnifiedConfigManager
                        .setAutomationShellAllowed(allowShell.booleanValue());
        if (ok && safetyUpdate != null) {
            ok = com.overdrive.app.config.UnifiedConfigManager.updateSection(
                    "drivingSafety", safetyUpdate);
        }
        JSONObject resp = new JSONObject();
        resp.put("success", ok);
        resp.put("allowShell",
                com.overdrive.app.config.UnifiedConfigManager.isAutomationShellAllowed());
        resp.put("drivingSafety", DrivingSafetyGuard.getGuardSettings());
        if (!ok) resp.put("error", "Failed to persist automation settings");
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * Whether a path-derived automation id is missing or blank.
     * A trailing-slash route (e.g. PUT /api/automations/automation/) yields an empty id; without this
     * guard PUT would silently create a NEW random-UUID automation and DELETE would report success
     * while removing nothing. Only path-derived ids are validated here — the create route (POST with a
     * null id) intentionally leaves the id unset so a UUID is generated.
     *
     * @param id The id extracted from the request path
     * @return true if the id is null or blank and the request must be rejected
     */
    private static boolean isBlankId(String id) {
        return id == null || id.isBlank();
    }

    /**
     * Percent-decode a path-derived id. The UI always sends {@code encodeURIComponent(id)}, and ids
     * are not guaranteed to be UUIDs — a community import keeps the shared map's keys verbatim, so
     * an id can legally contain a space or a {@code #}. Without decoding, the lookup misses and the
     * automation/group 404s on every PUT, DELETE and test: permanently uneditable and undeletable
     * from the UI. Never throws: a malformed escape returns the raw id (which then simply 404s)
     * rather than propagating out of handle(), which would close the socket with no response.
     */
    private static String decodeId(String id) {
        if (id == null || id.indexOf('%') < 0) return id;
        try {
            return java.net.URLDecoder.decode(id, "UTF-8");
        } catch (Exception e) {
            return id;
        }
    }

    /** Read one percent-decoded query parameter without letting malformed input escape routing. */
    private static String queryParam(String path, String name) {
        if (path == null || name == null) return null;
        int q = path.indexOf('?');
        if (q < 0 || q + 1 >= path.length()) return null;
        String[] pairs = path.substring(q + 1).split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                String key = java.net.URLDecoder.decode(rawKey, "UTF-8");
                if (name.equals(key)) {
                    return java.net.URLDecoder.decode(rawValue, "UTF-8");
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Send a 400 for a request that carried a missing/blank automation id.
     *
     * @param out The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean rejectBlankId(OutputStream out) throws Exception {
        HttpResponse.sendError(out, 400, "Missing automation id.");
        return true;
    }

    private static boolean getAutomations(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, Automations.toJson().toString());
        return true;
    }

    private static boolean getSchema(String locale, OutputStream out) throws Exception {
        JSONArray schema = LocaleManager.isSupported(locale)
                ? Messages.withLocale(locale, Automations::schemaJson)
                : Automations.schemaJson();
        HttpResponse.sendJson(out, schema.toString());
        return true;
    }

    /**
     * Create (id == null) or update (non-blank id) an automation from the request body.
     * The body is parsed inside a try/catch so malformed JSON returns a 400 rather than propagating a
     * JSONException out of handle() — the server's outer catch only logs and closes the socket, which
     * would leave the client hanging with no HTTP response.
     *
     * @param id   The id of the automation to update, or null to create a new one
     * @param body The raw request body expected to contain the automation JSON
     * @param out  The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean addOrUpdateAutomation(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "Missing body.");
            return true;
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException e) {
            HttpResponse.sendError(out, 400, "Malformed JSON body.");
            return true;
        }
        // Parse first so a schema error stays a 400, then use the Automation overload, whose result
        // is the PERSISTENCE outcome: a validated automation that fails to write is a 500, never a
        // success — reporting "saved" for a write that never landed loses the user's edits at the
        // next boot with no signal at all.
        com.overdrive.app.automation.Automation parsed =
                com.overdrive.app.automation.Automation.fromJson(json);
        if (parsed == null) {
            HttpResponse.sendError(out, 400, "Invalid automation provided. Check the automation follows the schema");
            return true;
        }
        if (Automations.hasInvalidMqttChannel(parsed)) {
            HttpResponse.sendError(out, 400, Automations.MQTT_CHANNEL_ERROR);
            return true;
        }
        if (Automations.updateAutomation(id, parsed)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 500, "Saved in memory but could not be written to storage — check free space.");
        }
        return true;
    }

    /**
     * Delete an automation by id, returning 404 when no automation with that id exists so the client
     * is not told a delete succeeded when nothing was removed.
     *
     * @param id  The id of the automation to delete
     * @param out The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean deleteAutomation(String id, OutputStream out) throws Exception {
        if (Automations.deleteAutomation(id)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 404, "Automation not found.");
        }
        return true;
    }

    // Off-HTTP-thread executor for running tested action chains. A test fires the
    // chain which may now contain blocking steps (pause up to 5 min, waitUntil up to
    // 10 min); running it inline would tie up an HttpServer pool thread for that whole
    // time (and repeated tests could exhaust the fixed pool and stall the web UI /
    // telemetry serving). So /test returns as soon as it confirms the automation
    // EXISTS and dispatches the actual firing here. Daemon thread so it never blocks
    // process exit; single-thread so two tests queue rather than pile up.
    private static final java.util.concurrent.ExecutorService TEST_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AutomationTest");
                t.setDaemon(true);
                return t;
            });

    /**
     * Run an automation's primary actions without checking its conditions so a user can test them.
     * Automatic and manual-only modes execute; fully disabled remains an accepted no-op for
     * compatibility with the previous endpoint contract. Unknown ids return 404.
     *
     * <p>The action chain is fired on {@link #TEST_EXECUTOR}, NOT inline, so a chain
     * containing a blocking {@code pause}/{@code waitUntil} step cannot hold the
     * HttpServer pool thread for minutes (which would risk pool exhaustion). The
     * response reports that the test was ACCEPTED (the automation exists); the actions
     * then run in the background exactly as an event-triggered fire would.
     *
     * @param id  The id of the automation to test
     * @param out The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean testAutomation(String id, OutputStream out) throws Exception {
        if (Automations.isServiceModeDisabled()) {
            HttpResponse.sendJson(out, 403, new JSONObject()
                    .put("success", false)
                    .put("error", "Automation execution is paused by Service Mode.")
                    .toString());
            return true;
        }
        if (!Automations.exists(id)) {
            HttpResponse.sendError(out, 404, "Automation not found.");
            return true;
        }
        // Fire off-thread; triggerExplicitActions already wraps action execution in
        // catch(Throwable), so a misbehaving action can't kill the test executor.
        dispatchExplicitAutomation(id, false);
        HttpResponse.sendJsonSuccess(out);
        return true;
    }

    /**
     * Shared serialized dispatcher for explicit automation runs. Package-private so
     * KeymapApiHandler can route manual-only key bindings through the exact same
     * single worker as Run now; long pause/wait actions therefore cannot block an HTTP
     * thread or race another explicit automation chain.
     */
    static void dispatchExplicitAutomation(String id, boolean recordStats) {
        TEST_EXECUTOR.submit(() -> Automations.triggerExplicitActions(id, recordStats));
    }

    /**
     * Run a reusable action group's actions immediately, with no automation wrapper — the
     * missing counterpart to the group CRUD, so a group can be a first-class one-tap action
     * (quick-control button, physical key, or a direct call) instead of requiring a dummy
     * automation whose only job is to invoke it.
     *
     * <p>Fired on {@link #TEST_EXECUTOR} for the SAME reason {@link #testAutomation} is: a
     * group may contain a blocking {@code pause}/{@code waitUntil} step, and running it inline
     * would hold an HttpServer pool thread for minutes. Sharing that single-thread executor
     * also means a group and an automation test queue behind each other rather than racing.
     *
     * <p>{@code runActionList} applies the same depth cap the automation path uses, but it has NO
     * per-action {@code catch(Throwable)} — a throwing action unwinds the whole remaining chain. The
     * executor survives only because of this method's own outer catch below, so keep it. Note the
     * ActionGroupAction cycle guard is thread-local to the executing thread, which is exactly
     * where it is needed.
     */
    private static boolean runActionGroup(String id, OutputStream out) throws Exception {
        if (Automations.isServiceModeDisabled()) {
            HttpResponse.sendJson(out, 403, new JSONObject()
                    .put("success", false)
                    .put("error", "Action-group execution is paused by Service Mode.")
                    .toString());
            return true;
        }
        if (!com.overdrive.app.automation.ActionGroups.exists(id)) {
            HttpResponse.sendError(out, 404, "Action group not found.");
            return true;
        }
        TEST_EXECUTOR.submit(() -> {
            try {
                Automations.runActionList(com.overdrive.app.automation.ActionGroups.getActions(id));
            } catch (Throwable t) {
                logger.error("Action group " + id + " failed", t);
            }
        });
        HttpResponse.sendJsonSuccess(out);
        return true;
    }

    /**
     * Enable or disable an automation by id from the request body's "disabled" flag.
     * The body is parsed inside a try/catch so malformed JSON returns a 400 instead of propagating a
     * JSONException out of handle() (see addOrUpdateAutomation).
     *
     * @param id   The id of the automation to enable/disable
     * @param body The raw request body expected to contain the "disabled" boolean
     * @param out  The output stream to write the response to
     * @return true so the router treats the request as handled
     */
    private static boolean disableAutomation(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "Missing body.");
            return true;
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException e) {
            HttpResponse.sendError(out, 400, "Malformed JSON body.");
            return true;
        }
        // Require a real boolean rather than defaulting. optBoolean("disabled", false) turned a
        // missing or non-boolean value into an ENABLE — the opposite of what a caller sending
        // {"disabled":"yes"} intends, and the unsafe direction for a flag that governs autonomous
        // vehicle actuation.
        if (!(json.opt("disabled") instanceof Boolean)) {
            HttpResponse.sendError(out, 400, "Body must contain a boolean \"disabled\" field.");
            return true;
        }
        if (Automations.disableAutomation(id, json.getBoolean("disabled"))) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 404, "Automation not found.");
        }
        return true;
    }

    /**
     * Set automatic/manual/disabled mode. Kept separate from the legacy disable
     * endpoint so existing clients retain their exact boolean enable/disable contract.
     */
    private static boolean setAutomationMode(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "Missing body.");
            return true;
        }
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (JSONException e) {
            HttpResponse.sendError(out, 400, "Malformed JSON body.");
            return true;
        }
        Object rawMode = json.opt("mode");
        if (!(rawMode instanceof String)
                || !com.overdrive.app.automation.Automation.isValidMode((String) rawMode)) {
            HttpResponse.sendError(out, 400,
                    "Body must contain mode \"automatic\", \"manual\", or \"disabled\".");
            return true;
        }
        if (Automations.setAutomationMode(id, (String) rawMode)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 404, "Automation not found.");
        }
        return true;
    }
}
