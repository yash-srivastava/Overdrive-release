package com.overdrive.app.server;

import android.os.SystemClock;

import com.overdrive.app.auth.StepUpStore;
import com.overdrive.app.auth.WebAuthnVerifier;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * /api/stepup/*: the Face ID / fingerprint lock's settings and phones. Adding a phone starts on the car
 * screen, which shows a code once someone, with the car parked, presses the brake or opens and closes a
 * front door; the phone then registers a passkey through a stable secure link.
 */
public final class StepUpApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("StepUpApi");

    private static final String PREFIX = "/api/stepup/";
    private static final String PAIRING_PREFIX = PREFIX + "pairing/";
    private static final String CREDENTIALS_PREFIX = PREFIX + "credentials/";
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final long PRESENCE_WINDOW_MS = 60_000L;
    private static final long PRESENCE_POLL_MS = 250L;
    private static final long CODE_TTL_MS = 180_000L;
    private static final long REGISTRATION_TTL_MS = 180_000L;
    private static final int MAX_WRONG_CODES = 5;
    private static final int CODE_DIGITS = 6;
    private static final int BRAKE_PRESSED_PCT = 10;
    private static final int BRAKE_STEADY_TICKS = 2;
    private static final int PROMPT_TIMEOUT_MS = 60_000;
    private static final String DEFAULT_LABEL = "Phone";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String WAITING = "waiting";
    private static final String READY = "ready";
    private static final String EXPIRED = "expired";
    private static final String USED = "used";

    private static final String MSG_NEED_LINK = "To add this phone, open OverDrive through your secure link: "
            + "zrok, your Cloudflare domain or Tailscale HTTPS.";
    private static final String MSG_LINK_CHECKING = "Still checking the secure link. Try again in a few seconds.";
    private static final String MSG_START_ON_CAR = "Start on the car screen: Vehicle Control → Safety "
            + "→ Add a phone. Then enter the code here.";
    private static final String MSG_USE_CAR_SCREEN = "Use Vehicle Control on the car screen for this.";
    private static final String MSG_STOP_REMOTE_VIEW = "Stop remote screen control first.";
    private static final String MSG_WRONG_CODE = "That code didn't work. Check it and try again.";
    private static final String MSG_TOO_MANY_CODES = "Too many wrong codes. Start again on the car screen.";
    private static final String MSG_CODE_EXPIRED = "The code expired. Start again on the car screen.";
    private static final String MSG_ADD_FAILED = "Couldn't add this phone. Try again.";
    private static final String MSG_TOO_MANY_PHONES = "Remove a phone before adding another.";
    private static final String MSG_STORE_UNREADABLE =
            "The lock settings couldn't be read. Turn the lock off here to reset them.";
    private static final String MSG_STORE_UNREADABLE_REMOTE =
            "The lock settings couldn't be read. Turn the lock off on the car screen to reset them.";

    private static final class Pairing {
        final String id;
        final long watchDeadline;
        String state = WAITING;
        // Kept after use so the same phone can fetch its registration again; shown only while ready.
        String code;
        long codeDeadline;
        long expiresAt;
        int wrongCodes;
        // Set once a phone was saved from this pairing; a later cancel can't undo that.
        boolean phoneAdded;
        ScheduledFuture<?> watch;
        // Watcher thread only: a press or a door open→close counts only once it starts after this began.
        boolean brakeArmed;
        int brakeReleasedTicks;
        int brakePressedTicks;
        final int[] doorPhase = new int[2];

        Pairing(String id, long watchDeadline, long expiresAt) {
            this.id = id;
            this.watchDeadline = watchDeadline;
            this.expiresAt = expiresAt;
        }

        boolean live() {
            return WAITING.equals(state) || READY.equals(state);
        }
    }

    private static final class Registration {
        final String id;
        final String pairingId;
        final byte[] challenge;
        final String rpId;
        final String userId;
        final String label;
        final String session;
        final long deadline;

        Registration(String id, String pairingId, byte[] challenge, String rpId, String userId, String label,
                     String session, long deadline) {
            this.id = id;
            this.pairingId = pairingId;
            this.challenge = challenge;
            this.rpId = rpId;
            this.userId = userId;
            this.label = label;
            this.session = session;
            this.deadline = deadline;
        }
    }

    private static final class BadRequest extends Exception {
        BadRequest(String message) {
            super(message);
        }
    }

    private static final Object LOCK = new Object();
    // Guarded by LOCK.
    private static Pairing pairing;
    private static Registration registration;
    private static ScheduledThreadPoolExecutor watcher;

    private StepUpApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path == null) return false;
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;
        if (!p.startsWith(PREFIX)) return false;
        String m = method == null ? "" : method;
        try {
            if (tooLarge(body)) {
                error(out, 413, "too_large", "The request is too large.");
            } else {
                route(m, p, body, out);
            }
        } catch (BadRequest e) {
            error(out, 400, "bad_request", e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            logger.error("Step-up request failed: " + t);
            error(out, 500, "step_up_error", "Something went wrong. Try again.");
        }
        return true;
    }

    private static void route(String m, String p, String body, OutputStream out) throws Exception {
        if (p.equals(PREFIX + "status")) {
            if (allow(m, "GET", out)) sendStatus(out);
        } else if (p.equals(PREFIX + "settings")) {
            if (allow(m, "POST", out)) settings(body, out);
        } else if (p.equals(PAIRING_PREFIX + "start")) {
            if (allow(m, "POST", out)) startPairing(out);
        } else if (p.startsWith(PAIRING_PREFIX) && p.length() > PAIRING_PREFIX.length()) {
            String id = p.substring(PAIRING_PREFIX.length());
            if ("GET".equals(m)) {
                pairingState(id, out);
            } else if ("DELETE".equals(m)) {
                cancelPairing(id, out);
            } else {
                error(out, 405, "method_not_allowed", "Use GET or DELETE.");
            }
        } else if (p.equals(PREFIX + "register/options")) {
            if (allow(m, "POST", out)) registerOptions(body, out);
        } else if (p.equals(PREFIX + "register/finish")) {
            if (allow(m, "POST", out)) registerFinish(body, out);
        } else if (p.startsWith(CREDENTIALS_PREFIX) && p.length() > CREDENTIALS_PREFIX.length()) {
            if (allow(m, "DELETE", out)) deleteCredential(p.substring(CREDENTIALS_PREFIX.length()), out);
        } else {
            error(out, 404, "not_found", "Not found.");
        }
    }

    // ---- settings ----------------------------------------------------------------------------

    private static void settings(String body, OutputStream out) throws Exception {
        JSONObject req = parseBody(body);
        Boolean enabled = null;
        if (req.has("enabled")) {
            Object v = req.opt("enabled");
            if (!(v instanceof Boolean)) throw new BadRequest("\"enabled\" must be true or false.");
            enabled = (Boolean) v;
        }
        Map<String, Boolean> actions = null;
        if (req.has("actions")) {
            Object v = req.opt("actions");
            if (!(v instanceof JSONObject)) throw new BadRequest("\"actions\" must be an object.");
            actions = new LinkedHashMap<>();
            for (String name : StepUpStore.actionNames()) {
                if (!((JSONObject) v).has(name)) continue;
                Object a = ((JSONObject) v).opt(name);
                if (!(a instanceof Boolean)) throw new BadRequest("\"" + name + "\" must be true or false.");
                actions.put(name, (Boolean) a);
            }
        }
        // An unreadable file accepts one write: turning the lock off from the car screen resets it.
        if (StepUpStore.get().unreadable) {
            if (!Boolean.FALSE.equals(enabled) || !StepUpGate.isCarScreenSession()) {
                refuseUnreadable(out);
                return;
            }
            StepUpStore.Change c = StepUpStore.resetToDefaults();
            if (c.error != null) {
                changeError(out, c.error);
                return;
            }
            logger.warn("Unreadable lock settings reset to the defaults from the car screen");
            sendStatus(out);
            return;
        }
        if (!authorizeChange("Change the Face ID or fingerprint lock", out)) return;
        StepUpStore.Change c = StepUpStore.update(enabled, actions);
        if (c.error != null) {
            changeError(out, c.error);
            return;
        }
        logger.info("Lock " + (c.state.enabled ? "on" : "off") + ", asks before " + c.state.actionsJson());
        sendStatus(out);
    }

    private static void deleteCredential(String rawId, OutputStream out) throws Exception {
        String id = rawId;
        try {
            id = java.net.URLDecoder.decode(rawId, "UTF-8");
        } catch (Exception ignored) {}
        StepUpStore.State s = StepUpStore.get();
        if (!StepUpStore.validCredentialId(id) || s.credential(id) == null) {
            changeError(out, StepUpStore.ERR_NOT_FOUND);
            return;
        }
        if (s.unreadable) {
            refuseUnreadable(out);
            return;
        }
        if (!authorizeChange("Remove a phone", out)) return;
        StepUpStore.Change c = StepUpStore.removeCredential(id);
        if (c.error != null) {
            changeError(out, c.error);
            return;
        }
        logger.info("Phone removed" + (c.state.enabled ? "" : "; lock off"));
        sendStatus(out);
    }

    /** The signed-in car screen changes settings directly; every other caller passes the gate. */
    private static boolean authorizeChange(String description, OutputStream out) throws Exception {
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        if (f != null && f.loopback && !f.tunnelRelayed) return requireCarScreen(out, MSG_USE_CAR_SCREEN);
        return StepUpGate.requireWhenEnabled("settings", description, out);
    }

    // ---- pairing (car screen) ------------------------------------------------------------------

    private static void startPairing(OutputStream out) throws Exception {
        if (!requireCarScreen(out, MSG_START_ON_CAR)) return;
        StepUpStore.State s = StepUpStore.get();
        if (s.unreadable) {
            refuseUnreadable(out);
            return;
        }
        if (s.credentials.size() >= StepUpStore.MAX_CREDENTIALS) {
            error(out, 409, "step_up_too_many", MSG_TOO_MANY_PHONES);
            return;
        }
        Pairing p = new Pairing(newId(), SystemClock.elapsedRealtime() + PRESENCE_WINDOW_MS,
                System.currentTimeMillis() + PRESENCE_WINDOW_MS);
        synchronized (LOCK) {
            endPairingLocked();
            registration = null;
            pairing = p;
            p.watch = watcherLocked().scheduleWithFixedDelay(
                    () -> watchTick(p), 0L, PRESENCE_POLL_MS, TimeUnit.MILLISECONDS);
        }
        logger.info("Pairing started: waiting for the brake or a front door");
        HttpResponse.sendJsonNoCors(out, 200, new JSONObject()
                .put("success", true)
                .put("pairingId", p.id)
                .put("state", WAITING)
                .put("expiresAt", p.expiresAt)
                .toString());
    }

    private static void pairingState(String id, OutputStream out) throws Exception {
        if (!requireCarScreen(out, MSG_START_ON_CAR)) return;
        JSONObject reply = new JSONObject().put("success", true).put("pairingId", id);
        synchronized (LOCK) {
            Pairing p = pairing;
            if (p == null || !p.id.equals(id)) {
                reply.put("state", EXPIRED).put("expiresAt", 0L);
            } else {
                expireIfDueLocked(p, SystemClock.elapsedRealtime());
                reply.put("state", p.state).put("expiresAt", p.expiresAt);
                if (READY.equals(p.state) && p.code != null) reply.put("code", p.code);
            }
        }
        HttpResponse.sendJsonNoCors(out, 200, reply.toString());
    }

    private static void cancelPairing(String id, OutputStream out) throws Exception {
        if (!requireCarScreen(out, MSG_START_ON_CAR)) return;
        boolean found;
        boolean added = false;
        synchronized (LOCK) {
            Pairing p = pairing;
            found = p != null && p.id.equals(id);
            if (found && p.phoneAdded) added = true;
            else if (found) killPairingLocked(p);
        }
        if (added) {
            error(out, 409, "step_up_already_added", "The phone was already added.");
            return;
        }
        if (!found) {
            error(out, 404, "step_up_unknown_pairing", "That pairing isn't active.");
            return;
        }
        logger.info("Pairing cancelled on the car screen");
        HttpResponse.sendJsonNoCors(out, 200, new JSONObject().put("success", true).toString());
    }

    // Presence first, then the code's lifetime. Remote screen control ends it: the code must never be
    // visible off the car.
    private static void watchTick(Pairing p) {
        try {
            synchronized (LOCK) {
                expireIfDueLocked(p, SystemClock.elapsedRealtime());
                if (pairing != p || !p.live()) {
                    stopWatchLocked(p);
                    return;
                }
                if (StepUpGate.remoteViewActive()) {
                    p.state = EXPIRED;
                    p.code = null;
                    stopWatchLocked(p);
                    logger.info("Pairing ended: remote screen control is active");
                    return;
                }
                if (!WAITING.equals(p.state)) return;
            }
            if (!presenceSeen(p)) return;
            synchronized (LOCK) {
                long now = SystemClock.elapsedRealtime();
                expireIfDueLocked(p, now);
                if (pairing != p || !WAITING.equals(p.state)) return;
                p.state = READY;
                p.code = newCode();
                p.codeDeadline = now + CODE_TTL_MS;
                p.expiresAt = System.currentTimeMillis() + CODE_TTL_MS;
            }
            logger.info("Pairing: presence seen, code shown on the car screen");
        } catch (Throwable t) {
            logger.warn("Pairing watch: " + t);
        }
    }

    // Parked only. The brake must read released for 2 ticks in a row, then pressed for 2; a front door
    // closed, opened, then closed. Leaving Park starts both over.
    private static boolean presenceSeen(Pairing p) {
        if (!parkedNow()) {
            p.brakeArmed = false;
            p.brakeReleasedTicks = 0;
            p.brakePressedTicks = 0;
            p.doorPhase[0] = 0;
            p.doorPhase[1] = 0;
            return false;
        }
        BydDataCollector collector;
        try {
            collector = BydDataCollector.getInstance();
        } catch (Throwable t) {
            return false;
        }
        int brake;
        try {
            brake = collector.readBrakeNow();
        } catch (Throwable t) {
            brake = BydVehicleData.UNAVAILABLE;
        }
        if (brake != BydVehicleData.UNAVAILABLE && brake >= 0 && brake < BRAKE_PRESSED_PCT) {
            p.brakePressedTicks = 0;
            if (++p.brakeReleasedTicks >= BRAKE_STEADY_TICKS) p.brakeArmed = true;
        } else if (brake >= BRAKE_PRESSED_PCT && brake <= 100) {
            p.brakeReleasedTicks = 0;
            if (p.brakeArmed && ++p.brakePressedTicks >= BRAKE_STEADY_TICKS) return true;
        } else {
            p.brakeReleasedTicks = 0;
            p.brakePressedTicks = 0;
        }
        try {
            int[] doors = collector.readAllDoorOpenStates();
            for (int i = 0; doors != null && i < 2 && i < doors.length; i++) {
                if (doors[i] == 0) {
                    if (p.doorPhase[i] == 2) return true;
                    p.doorPhase[i] = 1;
                } else if (doors[i] == 1 && p.doorPhase[i] >= 1) {
                    p.doorPhase[i] = 2;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // Park and stationary, or confirmed off; an unknown state is not parked.
    private static boolean parkedNow() {
        try {
            return com.overdrive.app.byd.routing.DrivingSafetyGuard.getMovementBlockReason() == null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void expireIfDueLocked(Pairing p, long now) {
        if (WAITING.equals(p.state) && now >= p.watchDeadline) {
            p.state = EXPIRED;
        } else if (READY.equals(p.state) && now >= p.codeDeadline) {
            p.state = EXPIRED;
            p.code = null;
        }
    }

    private static void killPairingLocked(Pairing p) {
        p.state = EXPIRED;
        p.code = null;
        stopWatchLocked(p);
        Registration r = registration;
        if (r != null && r.pairingId.equals(p.id)) registration = null;
    }

    private static void endPairingLocked() {
        Pairing old = pairing;
        if (old == null) return;
        if (old.live()) old.state = EXPIRED;
        old.code = null;
        stopWatchLocked(old);
    }

    private static void stopWatchLocked(Pairing p) {
        if (p.watch != null) {
            p.watch.cancel(false);
            p.watch = null;
        }
    }

    private static ScheduledThreadPoolExecutor watcherLocked() {
        if (watcher == null) {
            ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "StepUpPairing");
                t.setDaemon(true);
                return t;
            });
            e.setRemoveOnCancelPolicy(true);
            e.setKeepAliveTime(30L, TimeUnit.SECONDS);
            e.allowCoreThreadTimeOut(true);
            watcher = e;
        }
        return watcher;
    }

    // ---- registration (phone, through a stable secure link) -------------------------------------

    private static void registerOptions(String body, OutputStream out) throws Exception {
        JSONObject req = parseBody(body);
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        String rpId = req.optString("rpId", "").trim().toLowerCase(Locale.ROOT);
        if (!refuseUnlessSecureLink(f, rpId, out)) return;
        StepUpStore.State s = StepUpStore.get();
        if (s.unreadable) {
            refuseUnreadable(out);
            return;
        }
        if (s.credentials.size() >= StepUpStore.MAX_CREDENTIALS) {
            error(out, 409, "step_up_too_many", MSG_TOO_MANY_PHONES);
            return;
        }
        String code = normaliseCode(req.optString("code", ""));
        String label = StepUpStore.cleanLabel(req.optString("label", ""));
        String session = StepUpGate.sessionHash(f.sessionToken);
        Registration reg = null;
        boolean live = false;
        boolean killed = false;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            Pairing p = pairing;
            if (p != null) expireIfDueLocked(p, now);
            Registration pending = registration;
            boolean ready = p != null && READY.equals(p.state) && p.code != null;
            if (p != null && USED.equals(p.state) && pending != null && pending.pairingId.equals(p.id)
                    && now < pending.deadline && pending.session.equals(session) && pending.rpId.equals(rpId)
                    && codeEquals(p.code, code)) {
                reg = pending;
            } else if (ready && codeEquals(p.code, code)) {
                p.state = USED;
                stopWatchLocked(p);
                reg = new Registration(newId(), p.id, randomBytes(32), rpId,
                        WebAuthnVerifier.encode(randomBytes(16)), label, session, now + REGISTRATION_TTL_MS);
                registration = reg;
            } else if (p != null) {
                // Every miss counts in every state; the limit ends the pairing and its registration.
                live = ready;
                boolean alive = !EXPIRED.equals(p.state);
                if (++p.wrongCodes >= MAX_WRONG_CODES && alive) {
                    killPairingLocked(p);
                    killed = true;
                }
            }
        }
        if (reg == null) {
            if (killed) {
                logger.warn("Pairing: too many wrong codes; pairing ended");
                error(out, 403, "step_up_too_many_codes", MSG_TOO_MANY_CODES);
            } else if (live) {
                logger.warn("Pairing: wrong code");
                error(out, 403, "step_up_wrong_code", MSG_WRONG_CODE);
            } else {
                error(out, 410, "step_up_code_expired", MSG_CODE_EXPIRED);
            }
            return;
        }
        JSONArray exclude = new JSONArray();
        for (StepUpStore.Credential c : s.credentialsFor(rpId)) {
            exclude.put(new JSONObject().put("type", "public-key").put("id", c.id));
        }
        JSONObject publicKey = new JSONObject()
                .put("challenge", WebAuthnVerifier.encode(reg.challenge))
                .put("rp", new JSONObject().put("id", rpId).put("name", "OverDrive"))
                .put("user", new JSONObject()
                        .put("id", reg.userId)
                        .put("name", "overdrive")
                        .put("displayName", "OverDrive"))
                .put("pubKeyCredParams", new JSONArray()
                        .put(new JSONObject().put("type", "public-key").put("alg", WebAuthnVerifier.ES256))
                        .put(new JSONObject().put("type", "public-key").put("alg", WebAuthnVerifier.RS256)))
                .put("timeout", PROMPT_TIMEOUT_MS)
                .put("attestation", "none")
                .put("authenticatorSelection", new JSONObject()
                        .put("userVerification", "required")
                        .put("residentKey", "discouraged"))
                .put("excludeCredentials", exclude);
        logger.info("Pairing: code accepted, registering a passkey for " + rpId);
        HttpResponse.sendJsonNoCors(out, 200, new JSONObject()
                .put("success", true)
                .put("registrationId", reg.id)
                .put("publicKey", publicKey)
                .toString());
    }

    // A failed attempt leaves the registration in place: the phone may retry until it expires.
    private static void registerFinish(String body, OutputStream out) throws Exception {
        JSONObject req = parseBody(body);
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        String registrationId = req.optString("registrationId", "");
        Registration reg;
        synchronized (LOCK) {
            reg = registration;
            if (reg != null && !reg.id.equals(registrationId)) reg = null;
        }
        if (reg == null || SystemClock.elapsedRealtime() >= reg.deadline) {
            error(out, 410, "step_up_code_expired", MSG_CODE_EXPIRED);
            return;
        }
        if (!refuseUnlessSecureLink(f, reg.rpId, out)) return;
        if (!reg.session.equals(StepUpGate.sessionHash(f.sessionToken))) {
            logger.warn("Passkey not added: another sign-in");
            error(out, 403, "step_up_failed", MSG_ADD_FAILED);
            return;
        }
        Object algValue = req.opt("publicKeyAlgorithm");
        int alg = algValue instanceof Number ? ((Number) algValue).intValue() : 0;
        WebAuthnVerifier.Result r = WebAuthnVerifier.verifyRegistration(reg.rpId, reg.challenge,
                req.optString("id", ""), req.optString("clientDataJSON", ""),
                req.optString("authenticatorData", ""), req.optString("publicKey", ""), alg);
        if (!r.ok) {
            logger.warn("Passkey not added: " + r.reason);
            error(out, 403, "step_up_failed", MSG_ADD_FAILED);
            return;
        }
        String label = StepUpStore.cleanLabel(req.optString("label", ""));
        if (label.isEmpty()) label = reg.label;
        if (label.isEmpty()) label = DEFAULT_LABEL;
        synchronized (LOCK) {
            // A cancel or a new pairing on the car screen ends the registration too.
            boolean live = pairing != null && pairing.id.equals(reg.pairingId) && USED.equals(pairing.state);
            if (registration != reg || !live) reg = null;
            else registration = null;
        }
        if (reg == null) {
            error(out, 410, "step_up_code_expired", MSG_CODE_EXPIRED);
            return;
        }
        StepUpStore.Change c = StepUpStore.addCredential(new StepUpStore.Credential(r.credentialId, reg.rpId,
                r.publicKey, alg, r.signCount, label, System.currentTimeMillis(), 0L));
        synchronized (LOCK) {
            if (c.error != null) {
                if (registration == null && pairing != null && pairing.id.equals(reg.pairingId)
                        && USED.equals(pairing.state) && SystemClock.elapsedRealtime() < reg.deadline) {
                    registration = reg;
                }
            } else if (pairing != null && pairing.id.equals(reg.pairingId)) {
                pairing.code = null;
                if (USED.equals(pairing.state)) pairing.phoneAdded = true;
            }
        }
        if (c.error != null) {
            changeError(out, c.error);
            return;
        }
        boolean cancelled;
        synchronized (LOCK) {
            cancelled = pairing == null || !pairing.id.equals(reg.pairingId) || !USED.equals(pairing.state);
        }
        if (cancelled) {
            // The car screen cancelled while the passkey was being saved.
            StepUpStore.removeCredential(r.credentialId);
            error(out, 410, "step_up_code_expired", MSG_CODE_EXPIRED);
            return;
        }
        logger.info("Phone added for " + reg.rpId);
        sendStatus(out);
    }

    /** Digits in any script (Arabic-Indic, Devanagari, fullwidth…) as ASCII; spaces and dashes dropped. */
    static String normaliseCode(String raw) {
        StringBuilder sb = new StringBuilder();
        if (raw == null) return "";
        int i = 0;
        while (i < raw.length() && sb.length() <= CODE_DIGITS) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                    || Character.getType(cp) == Character.DASH_PUNCTUATION) {
                continue;
            }
            int d = Character.digit(cp, 10);
            sb.append(d >= 0 ? (char) ('0' + d) : '?');
        }
        return sb.toString();
    }

    private static boolean codeEquals(String expected, String given) {
        return expected != null && given != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    // ---- shared --------------------------------------------------------------------------------

    private static void sendStatus(OutputStream out) throws Exception {
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        StepUpStore.State s = StepUpStore.get();
        boolean remoteView = StepUpGate.remoteViewActive();
        boolean inCar = f != null && f.loopback && !f.tunnelRelayed;
        boolean lan = f != null && !f.loopback && !f.tunnelRelayed;
        boolean remote = f != null && !inCar;
        String host = f == null ? null : f.publicHost;
        JSONArray creds = new JSONArray();
        for (StepUpStore.Credential c : s.credentials) {
            creds.put(new JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("rpId", c.rpId)
                    .put("createdAt", c.createdAt)
                    .put("lastUsedAt", c.lastUsedAt));
        }
        JSONObject caller = new JSONObject()
                .put("inCar", inCar)
                .put("lan", lan)
                .put("remote", remote)
                .put("host", host == null ? JSONObject.NULL : host)
                .put("canPair", inCar && StepUpGate.isCarScreenSession())
                .put("canRegister", Boolean.TRUE.equals(secureLink(f, host)));
        HttpResponse.sendJsonNoCors(out, 200, new JSONObject()
                .put("success", true)
                .put("enabled", s.enabled)
                .put("actions", s.actionsJson())
                .put("credentials", creds)
                .put("caller", caller)
                .put("remoteAdbOn", remoteAdbOn())
                .put("remoteViewActive", remoteView)
                .put("storeUnreadable", s.unreadable)
                .toString());
    }

    private static boolean requireCarScreen(OutputStream out, String refusal) throws Exception {
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        if (f != null && f.loopback && !f.tunnelRelayed && StepUpGate.remoteViewActive()) {
            error(out, 403, "step_up_remote_view", MSG_STOP_REMOTE_VIEW);
            return false;
        }
        if (StepUpGate.isCarScreenSession()) return true;
        error(out, 403, "step_up_car_only", refusal);
        return false;
    }

    private static boolean refuseUnlessSecureLink(HttpServer.CallerFacts f, String rpId, OutputStream out)
            throws Exception {
        Boolean link = secureLink(f, rpId);
        if (Boolean.TRUE.equals(link)) return true;
        if (link == null) {
            error(out, 503, "step_up_link_checking", MSG_LINK_CHECKING);
        } else {
            error(out, 403, "step_up_needs_secure_link", MSG_NEED_LINK);
        }
        return false;
    }

    /** An https tunnel (not plain-http PROXY) whose public host is rpId and a stable host; null = not known yet. */
    private static Boolean secureLink(HttpServer.CallerFacts f, String rpId) {
        if (f == null || !f.tunnelRelayed || f.viaProxyProtocol || rpId == null || !rpId.equals(f.publicHost)) {
            return Boolean.FALSE;
        }
        return registrableHost(rpId);
    }

    private static Boolean registrableHost(String host) {
        if (!StepUpStore.validRpId(host)) return Boolean.FALSE;
        try {
            return DaemonsApiHandler.stablePublicHostState(host);
        } catch (Throwable t) {
            return Boolean.FALSE;
        }
    }

    // Same sentinel TailscaleLauncher writes: "true" while remote ADB is opted in.
    private static boolean remoteAdbOn() {
        File file = new File(com.overdrive.app.util.ScratchRoot.path(".tailscale") + "/adb_enabled");
        if (!file.isFile() || file.length() > 64) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line != null && "true".equals(line.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void refuseUnreadable(OutputStream out) throws Exception {
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        boolean car = f != null && f.loopback && !f.tunnelRelayed;
        error(out, 409, "step_up_store_unreadable", car ? MSG_STORE_UNREADABLE : MSG_STORE_UNREADABLE_REMOTE);
    }

    private static void changeError(OutputStream out, String error) throws Exception {
        if (StepUpStore.ERR_NO_PHONE.equals(error)) {
            error(out, 409, "step_up_no_phone", "Add a phone first.");
        } else if (StepUpStore.ERR_NOT_FOUND.equals(error)) {
            error(out, 404, "step_up_unknown_phone", "That phone isn't on the list.");
        } else if (StepUpStore.ERR_DUPLICATE.equals(error)) {
            error(out, 409, "step_up_already_added", "This phone is already added.");
        } else if (StepUpStore.ERR_TOO_MANY.equals(error)) {
            error(out, 409, "step_up_too_many", MSG_TOO_MANY_PHONES);
        } else if (StepUpStore.ERR_INVALID.equals(error)) {
            error(out, 400, "bad_request", "That passkey can't be used.");
        } else if (StepUpStore.ERR_UNREADABLE.equals(error)) {
            refuseUnreadable(out);
        } else {
            error(out, 500, "step_up_save_failed", "Couldn't save. Try again.");
        }
    }

    private static boolean allow(String method, String expected, OutputStream out) throws Exception {
        if (expected.equals(method)) return true;
        error(out, 405, "method_not_allowed", "Use " + expected + ".");
        return false;
    }

    private static boolean tooLarge(String body) {
        if (body == null) return false;
        return body.length() > MAX_BODY_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES;
    }

    // Throwable: deeply nested JSON overflows the parser's stack.
    private static JSONObject parseBody(String body) throws BadRequest {
        if (body == null || body.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(body);
        } catch (Throwable t) {
            throw new BadRequest("The request body isn't valid JSON.");
        }
    }

    private static void error(OutputStream out, int status, String code, String message) throws Exception {
        HttpResponse.sendJsonNoCors(out, status, new JSONObject()
                .put("success", false)
                .put("error", code)
                .put("message", message)
                .toString());
    }

    private static String newId() {
        return WebAuthnVerifier.encode(randomBytes(16));
    }

    private static String newCode() {
        return String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
