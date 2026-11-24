package com.overdrive.app.server;

import android.os.SystemClock;

import com.overdrive.app.auth.StepUpStore;
import com.overdrive.app.auth.WebAuthnVerifier;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Face ID / fingerprint step-up for remote vehicle actions. The car screen and in-process callers are
 * never asked. Every other caller is remote: it gets a 428 carrying a WebAuthn challenge and retries the
 * same request with {@code X-OD-StepUp}.
 */
public final class StepUpGate {

    public static final String UNLOCK = StepUpStore.UNLOCK;
    public static final String TRUNK_OPEN = StepUpStore.TRUNK_OPEN;
    public static final String POWER_OFF = StepUpStore.POWER_OFF;
    public static final String POWER_ON = StepUpStore.POWER_ON;

    private static final DaemonLogger logger = DaemonLogger.getInstance("StepUpGate");

    private static final long CHALLENGE_TTL_MS = 120_000L;
    private static final int PROMPT_TIMEOUT_MS = 60_000;
    private static final int MAX_CHALLENGES_PER_SESSION = 20;
    private static final int MAX_CHALLENGES = 256;
    private static final int MAX_HEADER_CHARS = 12_288;
    private static final int MAX_DESCRIPTION = 120;
    private static final long REMOTE_VIEW_ERROR_LOG_MS = 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String MSG_REQUIRED = "Confirm with Face ID or fingerprint.";
    private static final String MSG_NEEDS_LINK =
            "This action needs Face ID or fingerprint. Use your secure link or the car screen.";
    private static final String MSG_FAILED = "Couldn't confirm it's you. The action wasn't sent.";
    private static final String MSG_CAR_ONLY =
            "While the Face ID or fingerprint lock is on, this works only from the car screen.";
    private static final String MSG_REMOTE_VIEW = "Stop remote screen control first.";

    private static final ThreadLocal<Boolean> OVERRIDE = new ThreadLocal<>();
    private static final ThreadLocal<Satisfied> SATISFIED = new ThreadLocal<>();

    // Verify-then-record runs under this so two approvals can't both pass on one counter value.
    private static final Object VERIFY_LOCK = new Object();
    // Guarded by itself; insertion order is age order.
    private static final Map<String, Challenge> CHALLENGES = new LinkedHashMap<>();

    private static volatile long remoteViewErrorLoggedAtMs = 0L;

    private static final class Challenge {
        final String id;
        final byte[] bytes;
        final String gate;
        final String request;
        final String session;
        final String rpId;
        final long deadline;

        Challenge(String id, byte[] bytes, String gate, String request, String session, String rpId,
                  long deadline) {
            this.id = id;
            this.bytes = bytes;
            this.gate = gate;
            this.request = request;
            this.session = session;
            this.rpId = rpId;
            this.deadline = deadline;
        }
    }

    /** The request an approval belongs to, so a pooled thread can't carry it into the next one. */
    private static final class Satisfied {
        final String request;
        final String session;
        final String header;

        Satisfied(HttpServer.CallerFacts f) {
            request = requestKey(f);
            session = f.sessionToken;
            header = f.stepUpHeader;
        }

        boolean matches(HttpServer.CallerFacts f) {
            return request.equals(requestKey(f)) && Objects.equals(session, f.sessionToken)
                    && Objects.equals(header, f.stepUpHeader);
        }
    }

    private StepUpGate() {}

    public static boolean isEnabled() {
        return StepUpStore.get().enabled;
    }

    public static boolean isGated(String action) {
        StepUpStore.State s = StepUpStore.get();
        return s.enabled && s.action(action);
    }

    public static boolean anyGated() {
        StepUpStore.State s = StepUpStore.get();
        return s.enabled && s.anyAction();
    }

    /**
     * Car screen (loopback, no tunnel, no remote screen control), in-process, or already approved here.
     * Every network caller is remote, the car's own Wi-Fi and hotspot included.
     */
    public static boolean callerExempt() {
        Boolean override = OVERRIDE.get();
        if (override != null) return override || isSatisfied();
        if (isSatisfied()) return true;
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        if (f == null) return true;
        return f.loopback && !f.tunnelRelayed && !remoteViewActive();
    }

    /** Signed in on the car screen: loopback, no tunnel, no remote screen control, a valid JWT. */
    public static boolean isCarScreenSession() {
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        return f != null && f.loopback && !f.tunnelRelayed && !remoteViewActive()
                && HttpServer.isLocalSessionCaller();
    }

    /** One vehicle action. true = proceed. false = a 428 (ask) or 403 (can't ask here) reply was written. */
    public static boolean require(String action, OutputStream out) throws Exception {
        if (!isGated(action) || callerExempt()) return true;
        return ask(action, describe(action), out);
    }

    /** A bundle that may reach a gated action. Asks only when anyGated() and the caller isn't exempt. */
    public static boolean requireBundle(String gateId, String description, OutputStream out) throws Exception {
        if (!anyGated() || callerExempt()) return true;
        return ask(gateId == null || gateId.isEmpty() ? "run" : gateId, description, out);
    }

    /** While the lock is on, only the car screen or in-process callers; others get 403 step_up_car_only. */
    public static boolean carOnly(OutputStream out) throws Exception {
        if (!isEnabled() || carOrInProcess()) return true;
        if (carScreenUnderRemoteView(HttpServer.callerFacts())) {
            reply(out, 403, "step_up_remote_view", MSG_REMOTE_VIEW, null);
        } else {
            reply(out, 403, "step_up_car_only", MSG_CAR_ONLY, null);
        }
        return false;
    }

    /**
     * Like carOnly, for feeds the app itself posts over loopback (not UI taps), so remote screen
     * control can't produce them and they keep working while it is active.
     */
    public static boolean carOnlyFeed(OutputStream out) throws Exception {
        if (!isEnabled()) return true;
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        if (f == null || (f.loopback && !f.tunnelRelayed)) return true;
        reply(out, 403, "step_up_car_only", MSG_CAR_ONLY, null);
        return false;
    }

    /** The lock's own settings: asks whenever the lock is on, even with no action ticked. */
    public static boolean requireWhenEnabled(String gateId, String description, OutputStream out)
            throws Exception {
        if (!isEnabled() || callerExempt()) return true;
        return ask(gateId == null || gateId.isEmpty() ? "settings" : gateId, description, out);
    }

    /** For executor hops: capture on the request thread, apply on the worker thread. */
    public static boolean captureExemption() {
        return callerExempt() || isSatisfied();
    }

    public static <T> T callWithExemption(boolean exempt, Callable<T> work) throws Exception {
        Boolean previous = OVERRIDE.get();
        OVERRIDE.set(exempt);
        try {
            return work.call();
        } finally {
            restore(previous);
        }
    }

    public static void runWithExemption(boolean exempt, Runnable work) {
        Boolean previous = OVERRIDE.get();
        OVERRIDE.set(exempt);
        try {
            work.run();
        } finally {
            restore(previous);
        }
    }

    /** HttpServer calls this when a request ends, next to clearing its caller. */
    public static void clearThread() {
        OVERRIDE.remove();
        SATISFIED.remove();
    }

    static String describe(String action) {
        if (UNLOCK.equals(action)) return "Unlock the car";
        if (TRUNK_OPEN.equals(action)) return "Open the trunk";
        if (POWER_OFF.equals(action)) return "Switch the car off";
        if (POWER_ON.equals(action)) return "Switch the car on";
        return "Confirm this action";
    }

    static String sessionHash(String token) {
        try {
            byte[] raw = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
            return WebAuthnVerifier.encode(WebAuthnVerifier.sha256(raw));
        } catch (Exception e) {
            return "";
        }
    }

    // A failing probe counts as not active, so a bug there can't lock the car screen out.
    static boolean remoteViewActive() {
        try {
            return RemoteDevViewApiHandler.isSessionActive();
        } catch (Throwable t) {
            long now = SystemClock.elapsedRealtime();
            long last = remoteViewErrorLoggedAtMs;
            if (last == 0L || now - last >= REMOTE_VIEW_ERROR_LOG_MS) {
                remoteViewErrorLoggedAtMs = now;
                logger.warn("Remote screen control state unreadable, treated as not active: " + t);
            }
            return false;
        }
    }

    private static boolean carScreenUnderRemoteView(HttpServer.CallerFacts f) {
        return f != null && f.loopback && !f.tunnelRelayed && remoteViewActive();
    }

    private static void restore(Boolean previous) {
        if (previous == null) {
            OVERRIDE.remove();
        } else {
            OVERRIDE.set(previous);
        }
    }

    private static boolean isSatisfied() {
        Satisfied s = SATISFIED.get();
        if (s == null) return false;
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        return f != null && s.matches(f);
    }

    private static boolean carOrInProcess() {
        Boolean override = OVERRIDE.get();
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        if (f == null) return override == null || override;
        if (Boolean.FALSE.equals(override)) return false;
        return f.loopback && !f.tunnelRelayed && !remoteViewActive();
    }

    private static boolean ask(String gate, String description, OutputStream out) throws Exception {
        HttpServer.CallerFacts f = HttpServer.callerFacts();
        String host = f == null ? null : f.publicHost;
        if (carScreenUnderRemoteView(f)) {
            reply(out, 403, "step_up_remote_view", MSG_REMOTE_VIEW, null);
            return false;
        }
        // A plain-http tunnel can't run WebAuthn, though its host name may match an https link's passkey.
        if (f != null && f.viaProxyProtocol) {
            reply(out, 403, "step_up_needs_secure_link", MSG_NEEDS_LINK, null);
            return false;
        }
        if (f != null && f.stepUpHeader != null && !f.stepUpHeader.isEmpty()) {
            String failure = verify(f, gate);
            if (failure == null) {
                SATISFIED.set(new Satisfied(f));
                logger.info("Approved " + gate + " for " + f.method + " " + f.path);
                return true;
            }
            logger.warn("Refused " + gate + " for " + f.method + " " + f.path + ": " + failure);
            reply(out, 403, "step_up_failed", MSG_FAILED, null);
            return false;
        }
        List<StepUpStore.Credential> creds = f == null || !StepUpStore.validRpId(host)
                ? Collections.<StepUpStore.Credential>emptyList()
                : StepUpStore.get().credentialsFor(host);
        // A passkey exists only for a host that was stable when it was added; the browser binds it there.
        if (creds.isEmpty()) {
            logger.info("Can't ask for " + gate + " via " + (host == null ? "an unknown host" : host));
            reply(out, 403, "step_up_needs_secure_link", MSG_NEEDS_LINK, null);
            return false;
        }
        Challenge c = issue(gate, f, host);
        JSONArray allow = new JSONArray();
        for (StepUpStore.Credential cred : creds) {
            allow.put(new JSONObject().put("type", "public-key").put("id", cred.id));
        }
        JSONObject publicKey = new JSONObject()
                .put("challenge", WebAuthnVerifier.encode(c.bytes))
                .put("rpId", host)
                .put("timeout", PROMPT_TIMEOUT_MS)
                .put("userVerification", "required")
                .put("allowCredentials", allow);
        JSONObject stepUp = new JSONObject()
                .put("challengeId", c.id)
                .put("gate", gate)
                .put("description", clip(description))
                .put("publicKey", publicKey);
        reply(out, 428, "step_up_required", MSG_REQUIRED, stepUp);
        return false;
    }

    /** Null when the header approves this gate on this request; otherwise a short reason for the log. */
    private static String verify(HttpServer.CallerFacts f, String gate) {
        JSONObject a;
        try {
            String raw = f.stepUpHeader.trim();
            byte[] json = raw.length() > MAX_HEADER_CHARS ? null : WebAuthnVerifier.decode(raw);
            if (json == null) return "bad_header";
            a = new JSONObject(new String(json, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return "bad_header";
        }
        Challenge c = take(a.optString("challengeId", ""));
        if (c == null) return "unknown_or_expired_challenge";
        String host = f.publicHost;
        if (!c.gate.equals(gate)) return "wrong_gate";
        if (!c.request.equals(requestKey(f))) return "wrong_request";
        if (!c.session.equals(sessionHash(f.sessionToken))) return "wrong_session";
        if (host == null || !c.rpId.equals(host)) return "wrong_host";
        byte[] idBytes = WebAuthnVerifier.decode(a.optString("id", ""));
        if (idBytes == null) return "unknown_credential";
        String id = WebAuthnVerifier.encode(idBytes);
        synchronized (VERIFY_LOCK) {
            StepUpStore.Credential cred = StepUpStore.get().credential(id);
            if (cred == null || !cred.rpId.equals(host)) return "unknown_credential";
            WebAuthnVerifier.Result r = WebAuthnVerifier.verifyAssertion(host, c.bytes,
                    a.optString("clientDataJSON", ""), a.optString("authenticatorData", ""),
                    a.optString("signature", ""), cred.publicKey, cred.alg, cred.signCount);
            if (!r.ok) return r.reason;
            if (r.counterNotIncreased) {
                logger.warn("Passkey \"" + cred.label + "\" counter did not increase (" + r.signCount + " after "
                        + cred.signCount + "); accepted");
            }
            StepUpStore.recordUse(cred.id, r.signCount, System.currentTimeMillis());
        }
        return null;
    }

    private static Challenge issue(String gate, HttpServer.CallerFacts f, String host) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        long now = SystemClock.elapsedRealtime();
        Challenge c = new Challenge(WebAuthnVerifier.encode(id), bytes, gate, requestKey(f),
                sessionHash(f.sessionToken), host, now + CHALLENGE_TTL_MS);
        synchronized (CHALLENGES) {
            pruneLocked(now);
            int mine = 0;
            for (Challenge e : CHALLENGES.values()) {
                if (e.session.equals(c.session)) mine++;
            }
            Iterator<Challenge> it = CHALLENGES.values().iterator();
            while (mine >= MAX_CHALLENGES_PER_SESSION && it.hasNext()) {
                if (it.next().session.equals(c.session)) {
                    it.remove();
                    mine--;
                }
            }
            it = CHALLENGES.values().iterator();
            while (CHALLENGES.size() >= MAX_CHALLENGES && it.hasNext()) {
                it.next();
                it.remove();
            }
            CHALLENGES.put(c.id, c);
        }
        return c;
    }

    /** Single use: a challenge leaves the store the first time it is presented. */
    private static Challenge take(String id) {
        if (id == null || id.isEmpty()) return null;
        long now = SystemClock.elapsedRealtime();
        synchronized (CHALLENGES) {
            pruneLocked(now);
            return CHALLENGES.remove(id);
        }
    }

    private static void pruneLocked(long now) {
        Iterator<Challenge> it = CHALLENGES.values().iterator();
        while (it.hasNext()) {
            if (now >= it.next().deadline) it.remove();
        }
    }

    // Method, path, raw query and body hash: an approval can't be replayed with other arguments.
    private static String requestKey(HttpServer.CallerFacts f) {
        return f.method + "\n" + f.path + "\n" + (f.query == null ? "" : f.query) + "\n"
                + (f.bodyHash == null ? "" : f.bodyHash);
    }

    private static String clip(String description) {
        if (description == null) return "Confirm this action";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < description.length() && sb.length() < MAX_DESCRIPTION; i++) {
            char c = description.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "Confirm this action" : out;
    }

    private static void reply(OutputStream out, int status, String error, String message, JSONObject stepUp)
            throws Exception {
        if (out == null) return;
        JSONObject body = new JSONObject()
                .put("success", false)
                .put("ok", false)
                .put("error", error)
                .put("message", message);
        if (stepUp != null) body.put("stepUp", stepUp);
        HttpResponse.sendJson(out, status, body.toString());
    }
}
