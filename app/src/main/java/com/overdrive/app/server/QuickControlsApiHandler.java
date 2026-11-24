package com.overdrive.app.server;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * HTTP routes for USER-DEFINED quick-control buttons.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/quick-controls} — {@code { buttons:[…] }} as persisted</li>
 *   <li>{@code POST /api/quick-controls} — replace the button list wholesale</li>
 *   <li>{@code POST /api/quick-controls/fire/{id}} — run one button's action</li>
 * </ul>
 *
 * <p><b>Why this owns no actuation.</b> A button's {@code action} is exactly the payload a
 * physical-key binding stores, and firing delegates to {@link KeymapApiHandler#runBoundAction}
 * — the same resolver, the same curated catalog, the same API allowlist, the same
 * {@code allowAdvanced} shell gate. So a button can do anything a key can (catalog entity,
 * allowlisted API call, automation, action group, sequence) while adding no second actuation
 * path and no new privilege. Anything the keymap refuses, a button refuses identically.
 *
 * <p>Persisted in the {@code quickControls} section of the unified config. {@code updateSection}
 * MERGES top-level keys, so what makes a deletion stick is that the whole {@code buttons} ARRAY is
 * replaced on every save — not the section. Anyone adding a second key to this section must
 * re-check that: a removed sibling key would survive the merge.
 */
public final class QuickControlsApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("QuickControlsApi");

    /** Bound so a corrupted or hostile config can't make the dashboard unrenderable. */
    private static final int MAX_BUTTONS = 32;
    private static final int MAX_LABEL_LEN = 40;
    /** Nesting ceiling for an action body — see {@link #containsShell}. */
    private static final int MAX_ACTION_DEPTH = 8;

    private QuickControlsApiHandler() { }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/quick-controls") && method.equals("GET")) {
            return getButtons(out);
        }
        if (cleanPath.equals("/api/quick-controls") && method.equals("POST")) {
            return saveButtons(body, out);
        }
        if (cleanPath.startsWith("/api/quick-controls/fire/") && method.equals("POST")) {
            String id = cleanPath.substring("/api/quick-controls/fire/".length());
            return fireButton(id, out);
        }
        return false;
    }

    /** The persisted button list, always as {@code { success, buttons:[…] }}. */
    private static boolean getButtons(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("buttons", UnifiedConfigManager.getQuickControlButtons());
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * Replace the whole button ARRAY. Each entry keeps only the fields we understand
     * ({@code id}, {@code label}, {@code icon}, {@code action}), so a hand-edited config can't
     * smuggle extra keys into the persisted section.
     *
     * <p>An entry with no {@code action} is dropped rather than 400'ing the whole save: the
     * editor never produces one, and refusing the entire list over a single bad row would lose
     * the user's other buttons.
     */
    private static boolean saveButtons(String body, OutputStream out) throws Exception {
        if (body == null || body.isBlank()) {
            HttpResponse.sendJsonError(out, "Empty request body");
            return true;
        }
        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return true;
        }
        JSONArray in = req.optJSONArray("buttons");
        if (in == null) in = new JSONArray();
        if (in.length() > MAX_BUTTONS) {
            HttpResponse.sendJsonError(out, "Too many buttons (max " + MAX_BUTTONS + ")");
            return true;
        }

        JSONArray clean = new JSONArray();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject b = in.optJSONObject(i);
            if (b == null) continue;
            JSONObject action = b.optJSONObject("action");
            if (action == null) {
                logger.warn("quick-controls: dropping button " + i + " — no action");
                continue;
            }
            // Reject a shell action outright unless the keymap's advanced gate is on. The fire
            // path enforces this too (it shares the keymap executor), but refusing at SAVE time
            // means a disabled gate can't leave a live-looking button that always fails.
            if (containsShell(action, MAX_ACTION_DEPTH) && !UnifiedConfigManager.isKeymapAdvancedAllowed()) {
                HttpResponse.sendJsonError(out,
                        "Shell actions require the advanced key-mapping option to be enabled");
                return true;
            }
            JSONObject o = new JSONObject();
            // Ids must be UNIQUE: fireButton resolves by first match, so a duplicate would make
            // the later button permanently unreachable. An index-derived auto-id could also
            // collide with an explicit "btn3", so keep generating until the id is free.
            String id = b.optString("id", "").trim();
            if (id.isEmpty() || seenIds.contains(id)) {
                String base = id.isEmpty() ? ("btn" + i) : id;
                String candidate = base;
                int n = 2;
                while (seenIds.contains(candidate)) candidate = base + "-" + (n++);
                id = candidate;
            }
            seenIds.add(id);
            o.put("id", id);
            String label = b.optString("label", "").trim();
            if (label.length() > MAX_LABEL_LEN) label = label.substring(0, MAX_LABEL_LEN);
            o.put("label", label);
            String icon = b.optString("icon", "").trim();
            if (!icon.isEmpty()) o.put("icon", icon);
            o.put("action", action);
            clean.put(o);
        }

        JSONObject section = new JSONObject();
        section.put("buttons", clean);
        if (!StepUpGate.requireBundle("config", "Change quick controls", out)) return true;
        boolean ok = UnifiedConfigManager.setQuickControls(section);
        if (!ok) {
            HttpResponse.sendJsonError(out, "Failed to persist quick controls");
            return true;
        }
        logger.info("quick-controls saved: " + clean.length() + " button(s)");
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("buttons", clean);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * Does this action (or any step of a sequence) run a shell command?
     *
     * <p>Depth-bounded: the editor never nests sequences, so anything deeper than a couple of
     * levels is a hand-edited or hostile body, and unbounded recursion here would be a
     * {@link StackOverflowError} — an {@code Error}, which the caller's {@code catch (Exception)}
     * would NOT catch. Hitting the cap returns TRUE (assume shell present) so an unreadably deep
     * action fails closed rather than slipping past the gate.
     */
    private static boolean containsShell(JSONObject action, int depthLeft) {
        if (action == null) return false;
        if (depthLeft <= 0) return true;
        if ("shell".equals(action.optString("kind", ""))) return true;
        JSONArray steps = action.optJSONArray("steps");
        if (steps == null) return false;
        for (int i = 0; i < steps.length(); i++) {
            if (containsShell(steps.optJSONObject(i), depthLeft - 1)) return true;
        }
        return false;
    }

    /**
     * Run the action bound to {@code id}. Resolved from the persisted list by id — the client
     * never sends the action itself, so a button can only ever do what the user saved.
     */
    private static boolean fireButton(String id, OutputStream out) throws Exception {
        if (id == null || id.isBlank()) {
            HttpResponse.sendJsonError(out, "Missing button id");
            return true;
        }
        JSONArray buttons = UnifiedConfigManager.getQuickControlButtons();
        JSONObject action = null;
        String label = "";
        for (int i = 0; i < buttons.length(); i++) {
            JSONObject b = buttons.optJSONObject(i);
            if (b != null && id.equals(b.optString("id", ""))) {
                action = b.optJSONObject("action");
                label = b.optString("label", "").trim();
                break;
            }
        }
        if (action == null) {
            HttpResponse.sendError(out, 404, "Quick control not found.");
            return true;
        }
        // Mirror the physical-key path's error handling (KeymapApiHandler.handleFire): a runner
        // that throws must become a graceful {success:false} JSON body, not an exception out of
        // the request handler — which the server can only log while the client waits.
        JSONObject result;
        try {
            if (!KeymapApiHandler.stepUpForAction(action,
                    label.isEmpty() ? "a quick control" : label, out)) return true;
            result = KeymapApiHandler.runBoundAction(action);
        } catch (Exception e) {
            logger.warn("quick-control " + id + " failed: " + e.getMessage());
            result = new JSONObject();
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        int status = result.optInt("httpStatus", 200);
        result.remove("httpStatus");
        logger.info("quick-control fired: " + id + " -> success=" + result.optBoolean("success", false));
        HttpResponse.sendJson(out, status, result.toString());
        return true;
    }
}
