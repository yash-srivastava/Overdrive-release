package com.overdrive.app.server;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.mqtt.VehicleControlCatalog;
import com.overdrive.app.recording.ManualClipService;
import com.overdrive.app.recording.ManualClipWindow;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Daemon-side executor for physical-key mappings.
 *
 * The key interception itself lives in {@code KeepAliveAccessibilityService}
 * (onKeyEvent → {@code KeyMapDispatcher}), which runs in the <b>app</b> process
 * (UID 10xxx). BYD SDK actuation, however, only
 * clears the signature-permission wall from the <b>daemon</b> process (UID 2000),
 * which is where {@link VehicleCommandRouter} and {@link BydDataCollector} live.
 * So the a11y service does not actuate directly — it POSTs the bound action here
 * (via {@code DaemonHttpClient}, JWT-authenticated) and this handler runs it in
 * the daemon, identical to how {@link com.overdrive.app.mqtt.MqttCommandRouter}
 * dispatches Home-Assistant control commands.
 *
 * Endpoints:
 *   GET  /api/keymap/config — { enabled, allowAdvanced, bindings[],
 *                               a11yEnabled, a11yBound, a11yPending,
 *                               restartRequired, fuelCapableHybrid? }
 *       a11yEnabled = OS bind PRECONDITION (listed + master-on, or in-proc).
 *       a11yBound   = service ACTUALLY bound/live (in-proc isRunning, or a live
 *                     ServiceRecord via dumpsys) — the honest "keys will fire" truth.
 *       a11yPending = precondition met but not yet bound (the transient enable-edge
 *                     window); the client can show "enabling…" and confirm the real
 *                     bind before hiding the nudge instead of trusting a11yEnabled.
 *   POST /api/keymap/config — persist { enabled, allowAdvanced, bindings[] };
 *                             response echoes { success, a11yEnabled, a11yBound,
 *                             a11yPending, restartRequired } (auto-enables the
 *                             service on the enable edge; never restarts camera).
 *   POST /api/keymap/fire  — body { kind, ... } → run one bound action
 *
 * Action kinds (mirror the settings UI's curated picker + advanced escape hatch):
 *   { "kind":"catalog", "key":"drl", "sub":null, "payload":"on" }
 *       Resolve against {@link VehicleControlCatalog}. SDK-only commands stay
 *       local; declared SDK-first commands use the cloud only as a fallback.
 *       The full curated set includes drl, sunroof, sunshade, windows_all,
 *       tailgate, seat_*, climate, charge_cap_*, child_lock,
 *       wireless_charging, and adas_*.
 *   { "kind":"vehicle", "action":"lock|unlock|flash|find_car" }
 *       Terminally confirmed cloud commands not registered in the catalog.
 *   { "kind":"api", "method":"POST", "path":"/api/...", "body":"{...}" }
 *       Fire an internal API action (surveillance, recording mode, camera view,
 *       blind spot) for features that have no VehicleControlCatalog/SDK entity.
 *       Routed through {@link HttpServer#automationApiRequest} — the SAME auth-free,
 *       allowlisted path automations use — so it can only ever reach the curated
 *       control surface, never the sensitive /api/debug|backup|update|... endpoints.
 *       The authoritative reachable set is HttpServer's isAutomationAllowed policy;
 *       don't restate it here, it drifts.
 *   { "kind":"automation", "id":"&lt;uuid&gt;" }
 *       Run a saved automation's actions on a keypress — the physical-key
 *       equivalent of the automation's trigger. Automatic rules use the shared
 *       {@link com.overdrive.app.automation.AutomationQueue} (their conditions still
 *       gate them); manual-only rules use the serialized explicit-run worker and fire
 *       their primary actions unconditionally. Neither path runs inline — a chain may
 *       block for minutes on pause/waitUntil and must never tie up this HTTP worker.
 *       Deliberately NOT routed through the automation API allowlist (which excludes
 *       /api/automations on purpose): this is a dedicated in-process call, like
 *       {@code manualClip}. Unknown ids are rejected; fully disabled rules retain the
 *       legacy accepted no-op behavior.
 *   { "kind":"actionGroup", "id":"&lt;uuid&gt;" }
 *       Run a reusable action group's actions on a keypress. Dispatched to a daemon
 *       thread (a group may pause/waitUntil for minutes and must not hold this HTTP
 *       worker). A group has no conditions and no run-stats, so it fires
 *       unconditionally. Like {@code manualClip}, a dedicated in-process call —
 *       deliberately NOT via the automation API allowlist, which excludes
 *       /api/action-groups on purpose.
 *   { "kind":"openApp", "package":"com.foo.bar", "label":"Foo" }
 *       Launch an installed app (resolves its launcher activity). Not gated —
 *       opening an app is no more privileged than a user tapping its icon.
 *   { "kind":"manualClip", "beforeSeconds":30, "afterSeconds":0 }
 *       Save a user-triggered instant replay from the encoded camera ring.
 *       Dedicated manual-only path: deliberately not routed through the
 *       automation API allowlist.
 *   { "kind":"voice_assistant" }
 *       Push-to-talk. The app's key dispatcher starts the voice turn; it must be a
 *       direct binding, so a sequence step or quick control reaching here is refused.
 *   { "kind":"shell", "cmd":"..." }         — advanced, gated by allowAdvanced
 *   { "kind":"sequence", "steps":[ {kind:...}, ... ] }
 *       Run several of the above actions in order on one keypress. Best-effort:
 *       a failing step is recorded but does not abort the rest.
 *
 * All actuation runs synchronously on the caller's request thread (the HttpServer
 * worker), never the app's input-dispatch thread — the a11y service fires the
 * POST on a background executor and does not block on the result.
 */
public final class KeymapApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("KeymapApi");

    // ── Accessibility bind watchdog (daemon-side supervisor) ─────────────────
    // The key-filter rides on KeepAliveAccessibilityService in the APP process,
    // which shares one heavy main looper with the WebView, two MapLibre GL
    // surfaces, four foreground services and the overlays. On a long-lived app
    // process we observed the OS leave that service stuck in AMS "Binding" and
    // never promote it to "Bound" — onServiceConnected never runs, onKeyEvent
    // never fires, so every mapped wheel button falls straight through to the
    // OEM (and because this firmware delivers one press as a burst, the OEM
    // action repeats — the "looping next-track" symptom). A FRESH app process
    // binds cleanly every time; the only reliable recovery is to kill the wedged
    // process so AMS re-binds into a new one (a toggle of the Secure setting does
    // NOT un-wedge it — verified on-device).
    //
    // WHY the toggle does not help, and the restart does: BYD's start gate refuses
    // the system's bind for a third-party app whose uid is marked blocked while its
    // process is not running, and it is consulted from bindServiceLocked. A settings
    // toggle does not create a process; a restart does. See
    // BYD_ACCESSIBILITY_BIND_GATE_DISCOVERY.md.
    //
    // This daemon runs as UID 2000, a DIFFERENT uid than the app, so it survives
    // `am force-stop com.overdrive.app` (same reason SocCutoffMonitor pkills the
    // daemon separately). That makes it the stable supervisor: it periodically
    // checks the bind and, only when the service is enabled-but-not-bound for a
    // sustained window (a genuine wedge, not the transient async-bind gap after
    // enable), force-restarts the app so it respawns with a clean bind.
    //
    // Zero-cost + non-disruptive by construction:
    //   • Does nothing unless keymap is enabled AND at least one binding exists —
    //     the restart is FOR key mapping, so we never bounce the app for a
    //     feature the user hasn't configured.
    //   • The precondition-missing case (listed-but-master-off / not listed) is
    //     healed with the cheap `settings put` re-assert, never a restart.
    //   • The disruptive force-restart fires ONLY on a confirmed sustained wedge
    //     (keys already dead), is rate-limited so a pathological case can't
    //     restart-loop the app, and respawns HEADLESSLY (starts the keep-alive
    //     service, not the UI) so navigation/UI aren't yanked to the foreground.
    private static final long WATCHDOG_POLL_MS = 60_000L;
    // Backoff poll used once the state is HEALTHY or IDLE (bound-and-fine, or the
    // feature is off / has no bindings). In those states there is nothing to catch
    // quickly, so the priciest per-tick work (a `dumpsys activity services` exec on
    // the enabled+healthy path) doesn't need to run every 60s. Widen to 5 min while
    // healthy/idle; the loop snaps back to WATCHDOG_POLL_MS the instant a tick sees
    // a not-bound (potential-wedge) or precondition-missing state, so wedge
    // detection latency is unchanged in the case that matters. Cuts the steady-state
    // enabled dumpsys rate ~2×, and is a no-op when disabled (idle path never execs).
    // Tuned DOWN from 300s: a wedge beginning right after a healthy tick was invisible
    // for up to 5 min (dead keys the whole time). 120s halves that worst case while
    // keeping most of the dumpsys savings — the wedge is field-observed to recur, so
    // faster detection matters more than shaving the last of the (cheap) poll cost.
    private static final long WATCHDOG_HEALTHY_POLL_MS = 120_000L;
    // Delay the first check so we never mistake the app's own startup (or a fresh
    // post-restart bind) for a wedge. The daemon is itself launched ~45-90s after
    // the app, so by our first tick the a11y service has normally long since bound.
    private static final long WATCHDOG_INITIAL_DELAY_MS = 90_000L;
    // Require this many consecutive enabled-but-not-bound polls before the hammer.
    // The transient async-bind window after enable is seconds and closes between
    // 60s polls, so 2 confirms a real wedge without over-reacting to a slow bind.
    private static final int WATCHDOG_WEDGE_CONFIRM_CHECKS = 2;
    // Never force-restart more often than this. Not a retry CAP (the watchdog
    // keeps supervising forever, per the "no retry-cap watchdogs" rule) — just a
    // cooldown so a fresh process that itself takes a while to bind can't trigger
    // a restart-loop. A clean bind normally lands within ~10s of the respawn.
    // Tuned DOWN from 600s: the wedge is field-observed to RECUR (a fresh bind can
    // re-wedge within ~45 min), and a 10-min cooldown meant a second wedge left keys
    // dead for up to 10 min before recovery was even allowed. 3 min still comfortably
    // clears a respawn+bind (~10s) with margin, but recovers a recurrence ~3× sooner.
    private static final long WATCHDOG_MIN_RESTART_INTERVAL_MS = 3L * 60_000L;
    private static final String APP_PACKAGE = "com.overdrive.app";

    private static volatile boolean watchdogStarted = false;
    // Only ever read/written by the single watchdog thread → no synchronization.
    private static long lastForceRestartMs = 0L;
    // Set by watchdogTick each call: true when the observed state is fully settled
    // (feature off/idle, or bound-and-healthy) so the loop may back off to the wider
    // poll; false when actively watching (not-bound, precondition-missing, or just
    // force-restarted) so the loop stays on the tight poll. Watchdog-thread-only.
    private static boolean watchdogTickSettled = false;
    // Watchdog-thread-only: the DiLink 5 "restart disabled" notice is logged once per not-bound episode.
    private static boolean diLink5RestartSkipLogged = false;
    // Watchdog-thread-only: the DiLink 5 bind diagnostics are captured once per daemon run.
    private static boolean diLink5A11yDiagnosed = false;

    /**
     * One-shot DiLink 5 evidence for why the system never binds the service: current user, the
     * enable settings, the bind app-op (AOSP skips the service when it is not allowed), the
     * accessibility user state, and the system's own log lines about it.
     */
    private static void logDiLink5A11yDiagnostics() {
        if (diLink5A11yDiagnosed) return;
        diLink5A11yDiagnosed = true;
        logDiLink5A11yDiagnosticsSnapshot();
    }

    /** Also run at each DI5 calibration start: the startup capture is often truncated away by then. */
    public static void logDiLink5A11yDiagnosticsSnapshot() {
        int bindings;
        try {
            bindings = com.overdrive.app.config.UnifiedConfigManager.getKeymapBindings().length();
        } catch (Throwable t) {
            bindings = -1;
        }
        logger.warn("Keymap a11y DI5 keymap: enabled="
                + com.overdrive.app.config.UnifiedConfigManager.isKeymapEnabled()
                + " bindings=" + bindings + " bound=" + isAccessibilityServiceBound());
        String state = execBounded(
                "echo user=$(am get-current-user 2>/dev/null)"
                        + " enabled=$(settings get secure enabled_accessibility_services)"
                        + " master=$(settings get secure accessibility_enabled);"
                        + " appops get " + APP_PACKAGE + " BIND_ACCESSIBILITY_SERVICE 2>&1;"
                        + " dumpsys accessibility 2>/dev/null | grep -E 'User state|Bound services"
                        + "|Enabled services|Binding services|Crashed services|overdrive' | head -12");
        logger.warn("Keymap a11y DI5 state: " + oneLine(state));
        String reasons = execBounded("logcat -d -t 4000 2>/dev/null | grep -iE "
                + "'accessibility.*overdrive|overdrive.*accessib|KeepAliveAccessibility' | tail -8");
        logger.warn("Keymap a11y DI5 system log: " + oneLine(reasons));
    }

    // Watchdog-thread-only: last DiLink 5 crashed-service recovery, rate-limited like a restart.
    private static long lastDiLink5A11yRecoveryMs = 0L;

    /**
     * Android marks an accessibility service crashed when its process dies while bound, and never
     * rebinds it while it stays enabled (field dump: Crashed services held ours, Bound did not).
     * The flag clears only once the component leaves the enabled list, so drop it, then re-add it.
     */
    private static boolean recoverDiLink5CrashedService() {
        String dump = execBounded("dumpsys accessibility 2>/dev/null");
        if (dump == null || !listsOurComponent(dump, "Crashed services:")) return false;
        long now = System.currentTimeMillis();
        if (lastDiLink5A11yRecoveryMs != 0L
                && now - lastDiLink5A11yRecoveryMs < WATCHDOG_MIN_RESTART_INTERVAL_MS) {
            return false;
        }
        lastDiLink5A11yRecoveryMs = now;
        String current = readEnabledA11yServices();
        StringBuilder others = new StringBuilder();
        if (current != null) {
            for (String entry : current.trim().split(":")) {
                String e = entry.trim();
                if (e.isEmpty() || "null".equals(e) || containsOurService(e)) continue;
                if (others.length() > 0) others.append(':');
                others.append(e);
            }
        }
        execBounded("settings put secure enabled_accessibility_services '" + others + "'; sleep 2");
        ensureAccessibilityServiceEnabled();
        logger.warn("Keymap a11y watchdog: DiLink 5 marked the service crashed; removed and "
                + "re-added it to clear that (others kept: " + safeLog(others.toString()) + ")");
        return true;
    }

    /** Brace-scoped: does the named dumpsys list contain our component? */
    private static boolean listsOurComponent(String dump, String header) {
        int i = dump.indexOf(header);
        if (i < 0) return false;
        int open = dump.indexOf('{', i);
        if (open < 0) return false;
        int depth = 0;
        for (int p = open; p < dump.length(); p++) {
            char c = dump.charAt(p);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                return containsOurService(dump.substring(open, p + 1));
            }
        }
        return false;
    }

    private static String oneLine(String text) {
        if (text == null || text.trim().isEmpty()) return "(none)";
        String line = text.trim().replace('\n', '|');
        return line.length() > 1500 ? line.substring(0, 1500) + "…" : line;
    }

    /**
     * Start the accessibility bind watchdog. Idempotent; call once at daemon
     * boot. Runs on its own daemon thread (blocking `settings`/`dumpsys` execs
     * must never touch an HTTP worker), and supervises the app-process a11y
     * bind for the life of the daemon.
     */
    public static synchronized void startAccessibilityWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_INITIAL_DELAY_MS);
            } catch (InterruptedException ie) {
                return;
            }
            int notBound = 0;
            while (true) {
                long nextSleep = WATCHDOG_POLL_MS;
                try {
                    notBound = watchdogTick(notBound);
                    // Back off to the wider interval only when fully settled: bound-
                    // and-healthy or feature-off/idle (watchdogTickSettled tracks which
                    // branch the tick took). Any not-bound (wedge-in-progress),
                    // precondition-missing, or just-restarted state keeps the tight
                    // WATCHDOG_POLL_MS so wedge detection/recovery latency is unchanged.
                    nextSleep = watchdogTickSettled ? WATCHDOG_HEALTHY_POLL_MS : WATCHDOG_POLL_MS;
                } catch (Throwable th) {
                    logger.warn("Keymap a11y watchdog tick failed: " + th.getMessage());
                }
                try {
                    Thread.sleep(nextSleep);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "keymap-a11y-watchdog");
        t.setDaemon(true);
        t.start();
        logger.info("Keymap a11y watchdog started (poll=" + (WATCHDOG_POLL_MS / 1000)
                + "s, confirm=" + WATCHDOG_WEDGE_CONFIRM_CHECKS
                + ", restart-cooldown=" + (WATCHDOG_MIN_RESTART_INTERVAL_MS / 1000) + "s)");
    }

    /**
     * One watchdog evaluation. Returns the updated consecutive-not-bound counter.
     * Reads the keymap section via the cache-fresh getKeymap() path (which picks up
     * a cross-UID edit on the next real on-disk change, no forced re-parse), then
     * walks the escalation ladder: off/no-bindings → idle; precondition-missing →
     * cheap re-assert; enabled+not-bound (sustained) → force-restart; enabled+bound
     * → healthy.
     */
    private static int watchdogTick(int notBound) {
        // Read the keymap section WITHOUT forceReload(). getKeymap()->loadConfig()
        // already re-parses on a real on-disk change (isCacheFresh's mtime check
        // catches cross-UID writes) and returns the cheap in-memory cache otherwise.
        // The earlier forceReload() here nulled the PROCESS-WIDE config cache every
        // 60s unconditionally — even when keymap was disabled — forcing the ~44
        // other subsystems that read overdrive_config.json to re-parse from disk on
        // the next access. That defeated the very cache designed to prevent it and
        // is a real, self-inflicted periodic cost. Plain reads are correct and cheap.
        boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isKeymapEnabled();
        int bindingCount;
        try {
            bindingCount = com.overdrive.app.config.UnifiedConfigManager.getKeymapBindings().length();
        } catch (Throwable t) {
            bindingCount = 0;
        }
        if (!enabled || bindingCount == 0) {
            // Feature off or nothing to fire — the a11y bind is irrelevant to key
            // mapping, so don't supervise it (process keep-alive is separately
            // covered by DaemonKeepaliveService). Reset so a later enable starts fresh.
            // Settled/idle: the loop may back off to the wider poll (this path never
            // execs anything, so even the tight cadence was cheap — but no reason to
            // re-read config every 60s when off).
            watchdogTickSettled = true;
            return 0;
        }

        boolean precondition = isAccessibilityServiceEnabled();
        if (!precondition) {
            // Listed-but-master-off, or not listed at all. Cheap, non-disruptive
            // fix: re-assert the Secure setting (settings put). No restart. Stay on
            // the TIGHT poll so we re-verify the assert took hold promptly.
            logger.info("Keymap a11y watchdog: enable precondition missing — re-asserting accessibility settings");
            ensureAccessibilityServiceEnabled();
            watchdogTickSettled = false;
            return 0;
        }

        if (isAccessibilityServiceBound()) {
            watchdogTickSettled = true; // healthy — bound and dispatching; back off
            diLink5RestartSkipLogged = false;
            return 0;
        }

        // Precondition met but the service is NOT bound. Could be the transient
        // async-bind window right after enable/boot, so confirm it's sustained.
        // Actively watching — keep the tight poll.
        watchdogTickSettled = false;
        notBound++;
        if (notBound >= WATCHDOG_WEDGE_CONFIRM_CHECKS
                && com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            // DiLink 5's app process also hosts the vehicle bridge, and a restart has never
            // re-bound the service there (field log: 26 restarts, 0 binds). Report once; no restart.
            if (!diLink5RestartSkipLogged) {
                diLink5RestartSkipLogged = true;
                logger.warn("Keymap a11y watchdog: service not bound on DiLink 5; force-restart "
                        + "disabled there (it restarts the vehicle bridge and never re-binds)");
                logDiLink5A11yDiagnostics();
            }
            // A recovery attempt keeps the tight poll so its bind is confirmed on the next tick.
            watchdogTickSettled = !recoverDiLink5CrashedService();
            return WATCHDOG_WEDGE_CONFIRM_CHECKS;
        }
        logger.warn("Keymap a11y watchdog: service enabled but NOT bound ("
                + notBound + "/" + WATCHDOG_WEDGE_CONFIRM_CHECKS + " consecutive)");
        if (notBound < WATCHDOG_WEDGE_CONFIRM_CHECKS) {
            return notBound;
        }

        long now = System.currentTimeMillis();
        if (lastForceRestartMs != 0L && (now - lastForceRestartMs) < WATCHDOG_MIN_RESTART_INTERVAL_MS) {
            long sinceS = (now - lastForceRestartMs) / 1000;
            logger.warn("Keymap a11y watchdog: wedge persists but within restart cooldown ("
                    + sinceS + "s < " + (WATCHDOG_MIN_RESTART_INTERVAL_MS / 1000)
                    + "s) — deferring restart, will re-check next tick");
            return notBound; // keep counting; fire once the cooldown clears
        }

        logger.warn("Keymap a11y watchdog: CONFIRMED sustained bind wedge — force-restarting "
                + "the app process so AMS re-binds the accessibility service into a fresh process");
        forceRestartAppForA11y();
        lastForceRestartMs = now;
        return 0; // give the fresh process a full poll interval to come up + bind
    }

    /**
     * Kill the wedged app process and respawn it headlessly. `am force-stop`
     * clears the wedged ServiceRecord and puts the package in the stopped state;
     * an explicit component start then clears that flag and spawns a fresh
     * process. App startup re-asserts the accessibility settings, after which
     * AMS binds the service into the live process — the clean bind we verified
     * a fresh process always gets.
     *
     * The mechanism behind "a fresh process always binds" is BYD's start gate:
     * ActivityManagerService.isTargetAppEnabledStartedBy3rd permits the bind when
     * isAppRunning(uid) is true, so the relaunch satisfies the gate rather than
     * clearing anything in AMS. See BYD_ACCESSIBILITY_BIND_GATE_DISCOVERY.md.
     *
     * We respawn via the keep-alive foreground service (NOT MainActivity) so the
     * UI isn't pulled to the foreground mid-drive; OverdriveApplication.onCreate
     * runs on ANY process start, so the daemon-startup + a11y-enable path fires
     * regardless of which component brought the process up. The daemon runs as
     * UID 2000, so `am force-stop com.overdrive.app` (UID 10xxx) does not take
     * this supervisor down with it. Bounded exec so a wedged `am` can't hang the
     * watchdog thread.
     */
    private static void forceRestartAppForA11y() {
        String script =
                "am force-stop " + APP_PACKAGE + "; " +
                "sleep 3; " +
                "am start-foreground-service -n " + APP_PACKAGE
                        + "/.services.DaemonKeepaliveService";
        try {
            final Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(script))
                    .redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[2048];
                try { while (is.read(buf) != -1) { /* discard */ } }
                catch (Throwable ignored) { }
            }, "keymap-a11y-restart-drain");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            drain.join(500);
            try { is.close(); } catch (Throwable ignored) { }
            logger.info("Keymap a11y watchdog: force-restart issued (force-stop + headless keep-alive respawn)");
        } catch (Throwable t) {
            logger.warn("Keymap a11y watchdog: force-restart failed: " + t.getMessage());
        }
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/keymap/config") && method.equals("GET")) {
            handleGetConfig(out);
            return true;
        }
        if (cleanPath.equals("/api/keymap/config") && method.equals("POST")) {
            handleSaveConfig(out, body);
            return true;
        }
        if (cleanPath.equals("/api/keymap/fire") && method.equals("POST")) {
            handleFire(out, body);
            return true;
        }
        if (cleanPath.equals("/api/keymap/inject") && method.equals("POST")) {
            handleInject(out, body);
            return true;
        }
        if (cleanPath.equals("/api/keymap/di5-key-source") && method.equals("POST")) {
            handleDi5KeySourceReport(out, body);
            return true;
        }
        return false;
    }

    /** App-process DiLink 5 key-source status: its own log is not uploadable, so record it here. */
    private static void handleDi5KeySourceReport(OutputStream out, String body) throws Exception {
        try {
            JSONObject req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            String detail = req.optString("detail", "");
            if (detail.length() > 200) detail = detail.substring(0, 200);
            logger.info("DI5 key source: " + req.optString("status", "?")
                    + (detail.isEmpty() ? "" : " " + detail));
        } catch (Exception ignored) {
            // Malformed report: nothing to record.
        }
        HttpResponse.sendJson(out, "{\"success\":true}");
    }

    /**
     * Replay a native key tap: {@code input keyevent <keycode>} as the UID-2000 daemon.
     * Used ONLY by the dispatcher's "block native single" double-binding, which consumed
     * the user's first tap during the double window; when no second tap arrives it asks
     * us to inject the button's native event so the OEM function still happens. Body:
     * {@code {"keycode": <int>}}. The keycode is validated to a plain non-negative int
     * (it goes into a shell {@code input keyevent} argument), fire-and-forget so a slow
     * {@code input} never stalls the worker.
     */
    private static void handleInject(OutputStream out, String body) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            int keycode = req.optInt("keycode", -1);
            // Strict bound: a keycode is a small non-negative int. Reject anything else so
            // nothing but a bare integer ever reaches the `input keyevent` shell argument.
            if (keycode < 0 || keycode > 1000) {
                resp.put("success", false);
                resp.put("error", "invalid keycode");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            // Fire-and-forget shell, exactly like the UI-nav keyevents in
            // VehicleControlApiHandler.handleSystem. The int is range-checked above.
            Runtime.getRuntime().exec(new String[]{"sh", "-c", com.overdrive.app.util.ScratchRoot.remap("input keyevent " + keycode)});
            logger.info("Keymap inject native keyevent " + keycode);
            resp.put("success", true);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Keymap inject failed: " + e.getMessage());
            resp.put("success", false);
            resp.put("error", e.getMessage());
            HttpResponse.sendJson(out, resp.toString());
        }
    }

    /**
     * Return the whole keymap section plus a derived {@code a11yEnabled} flag so
     * the settings page can prompt the user to turn the accessibility service on
     * (key filtering is inert until it is). We read the section fresh — the app
     * or a prior POST may have written it under a different UID.
     */
    private static void handleGetConfig(OutputStream out) throws Exception {
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        JSONObject section = com.overdrive.app.config.UnifiedConfigManager.getKeymap();
        boolean enabled = section.optBoolean("enabled", false);
        boolean a11yOn = isAccessibilityServiceEnabled();
        // Distinct honest bind signal (see isAccessibilityServiceBound): a11yOn is
        // the OS BIND PRECONDITION (listed + master-on, or in-proc), which AMS may
        // have satisfied without having finished binding the app-process service —
        // so keys can still be briefly dead while a11yEnabled reads true. a11yBound
        // is the stronger truth (an active ServiceRecord / live in-proc instance),
        // and a11yPending marks the transient window between the two. The client
        // can render "enabling…" on a11yPending instead of "ready", and confirm the
        // real bind before hiding the nudge, rather than trusting the precondition.
        boolean a11yBound = isAccessibilityServiceBound();
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("enabled", enabled);
        resp.put("allowAdvanced", section.optBoolean("allowAdvanced", false));
        resp.put("doubleTapWindowMs",
                com.overdrive.app.config.UnifiedConfigManager.getKeymapDoubleTapWindowMs());
        resp.put("bindings", section.optJSONArray("bindings") != null
                ? section.optJSONArray("bindings") : new org.json.JSONArray());
        resp.put("a11yEnabled", a11yOn);
        resp.put("a11yBound", a11yBound);
        resp.put("a11yPending", a11yOn && !a11yBound);
        resp.put("restartRequired", ManualClipService.getInstance()
                .isCameraDaemonRestartRequired(section));
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            if (collector.isInitialized()) {
                resp.put("fuelCapableHybrid", collector.isFuelCapableHybridPublic());
            }
        } catch (Throwable ignored) {
            // Capability is optional; an unknown drivetrain keeps all choices visible.
        }
        HttpResponse.sendJson(out, resp.toString());

        // Load-time self-heal: the reported on-car state is an already-enabled
        // keymap config whose a11y service is off (listed-but-master-off, or not
        // listed) — which the enable-edge POST alone never recovers, since the
        // user isn't re-toggling. If mapping is ON but a11y is NOT enabled, kick
        // ensureAccessibilityServiceEnabled() so it recovers on page load without
        // the non-obvious toggle-off-then-on dance.
        //
        // Runs OFF this request thread: ensureAccessibilityServiceEnabled() blocks
        // on ~2 settings execs (waitFor up to 3s) plus two read-backs (~4s), which
        // must never park the HttpServer worker or add several seconds to page
        // load. We deliberately report a11yEnabled from the PRE-heal read above
        // rather than blocking this GET on the heal (that stall is the whole
        // reason the heal is async). The healed truth is served by any SUBSEQUENT
        // GET of this endpoint once the background heal has landed, so the client
        // must re-fetch /api/keymap/config after the heal cost (~4-5s) to pick up
        // a11yEnabled=true and hide the nudge. That client-side half now EXISTS in
        // key-mapping.js (load()'s a11yRecheckPending-guarded setTimeout(...,5500)
        // re-fetch, which self-terminates once a11yEnabled flips true), so a manual
        // reload is no longer required. The guard below keeps overlapping GETs
        // (including that client re-fetch) from stacking multiple concurrent
        // heals — one in flight is enough.
        if (enabled && !a11yOn) {
            maybeSelfHealAccessibility();
        }
    }

    /**
     * Fire an off-thread accessibility self-heal at most one at a time. The guard
     * prevents a polling client (or several open tabs) from spawning a pile of
     * concurrent {@code settings} exec bursts; a heal already in flight is enough.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean healInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void maybeSelfHealAccessibility() {
        if (!healInFlight.compareAndSet(false, true)) return; // one at a time
        Thread t = new Thread(() -> {
            try {
                logger.info("Keymap a11y: load-time self-heal — mapping enabled but a11y off, re-asserting");
                ensureAccessibilityServiceEnabled();
            } catch (Throwable th) {
                logger.warn("Keymap a11y: load-time self-heal failed: " + th.getMessage());
            } finally {
                healInFlight.set(false);
            }
        }, "keymap-a11y-selfheal");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Persist the keymap section. The page sends the full desired state
     * {enabled, allowAdvanced, bindings:[...]}; we replace the section wholesale
     * (updateSection) so a removed binding actually disappears rather than
     * lingering from a key-merge.
     */
    private static void handleSaveConfig(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        if (body == null || body.isBlank()) {
            HttpResponse.sendJsonError(out, "Empty request body");
            return;
        }
        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return;
        }

        // Advanced actions are the arbitrary-shell hatch (runShell, UID 2000), so turning
        // the gate ON needs a JWT-authenticated non-tunnel caller — a loopback socket alone
        // is every local app. Turning it off, or re-saving it unchanged, is always allowed
        // so a remote client can still de-escalate and normal saves never break.
        boolean wantAdvanced = req.optBoolean("allowAdvanced", false);
        boolean advancedRefused = false;
        if (wantAdvanced && !HttpServer.canGrantShellAccess()) {
            // Only the refusal candidate pays a fresh read: the section may have been
            // written under another UID, and a stale cache must not reject a re-save of
            // an already-enabled gate. A trusted save never reaches this.
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            if (!com.overdrive.app.config.UnifiedConfigManager.isKeymapAdvancedAllowed()) {
                // STRIP, don't refuse the request. The page posts the WHOLE desired state
                // (enabled, doubleTapWindowMs, bindings), so returning early here threw
                // away edits the caller had every right to make — a regression against the
                // original handler, which always applied them. Dropping just this one flag
                // keeps the gate and matches how the other three defences behave
                // (/api/settings/unified, backup import, SafeKeep restore all strip).
                wantAdvanced = false;
                advancedRefused = true;
            }
        }

        JSONObject section = new JSONObject();
        section.put("enabled", req.optBoolean("enabled", false));
        section.put("allowAdvanced", wantAdvanced);
        // Single-vs-double disambiguation window (ms). Persist only a sane value; the
        // accessor (getKeymapDoubleTapWindowMs) clamps on read too, so a hand-edited
        // config can't push it out of the 250..1500 band. Absent → the accessor's default.
        if (req.has("doubleTapWindowMs")) {
            long win = req.optLong("doubleTapWindowMs", 450L);
            section.put("doubleTapWindowMs", Math.max(250L, Math.min(1500L, win)));
        }
        org.json.JSONArray bindings = req.optJSONArray("bindings") != null
                ? req.optJSONArray("bindings") : new org.json.JSONArray();
        String manualClipError = validateManualClipBindings(bindings);
        if (manualClipError != null) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", manualClipError);
            HttpResponse.sendJson(out, 400, error.toString());
            return;
        }
        String voiceAssistantError = validateVoiceAssistantBindings(bindings);
        if (voiceAssistantError != null) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", voiceAssistantError);
            HttpResponse.sendJson(out, 400, error.toString());
            return;
        }
        // Preserve all existing binding kinds as sent. manualClip is validated
        // above (and again at fire time) because a malformed action would still
        // consume its physical key while doing nothing.
        section.put("bindings", bindings);

        boolean ok = com.overdrive.app.config.UnifiedConfigManager.setKeymap(section);
        boolean restartRequired = false;
        if (ok) {
            restartRequired = ManualClipService.getInstance()
                    .onKeymapConfigChanged(section);
        }
        // Self-heal: when the user turns key mapping ON, make sure the
        // accessibility service (which key filtering rides on) is actually
        // enabled, rather than leaving them to toggle it in system settings.
        // Only on the enable edge, so a disable-save doesn't re-arm it. On that
        // edge ensureAccessibilityServiceEnabled() already computed the post-write
        // listed+master-on truth, so reuse its return as the a11yEnabled we report
        // rather than firing a second read-back pair of `settings get` execs. Off
        // the enable edge (disable, or persist failed) nothing was written, so a
        // single fresh read is the correct source.
        boolean a11yEnabled;
        if (ok && section.optBoolean("enabled", false)) {
            a11yEnabled = ensureAccessibilityServiceEnabled();
        } else {
            a11yEnabled = isAccessibilityServiceEnabled();
        }
        // a11yEnabled is the OS bind PRECONDITION (kept for backward compatibility
        // with the client's enable-edge adopt). On the enable edge it is reported
        // true the instant after `settings put`, but AMS binds the app-process
        // service asynchronously, so onKeyEvent may not fire for a brief window
        // even though a11yEnabled is already true — a transient false-positive if
        // the client treats it as "ready". So ALSO report the honest bind truth:
        // a11yBound (an active ServiceRecord / live in-proc instance) and
        // a11yPending (precondition met but not yet bound = the transient window),
        // letting the client show "enabling…" and confirm the real bind before
        // hiding the nudge instead of trusting the write-echo. Right after the
        // enable write a11yBound is expected false (bind hasn't completed); a
        // subsequent GET/re-fetch flips it true once AMS lands the bind.
        boolean a11yBound = isAccessibilityServiceBound();
        response.put("success", ok);
        response.put("a11yEnabled", a11yEnabled);
        response.put("a11yBound", a11yBound);
        response.put("a11yPending", a11yEnabled && !a11yBound);
        response.put("restartRequired", ok && restartRequired);
        // Always echo the EFFECTIVE flag so the client's checkbox can follow what was
        // actually stored rather than what it asked for; this is how the page learns that
        // an untrusted caller's allowAdvanced=true was dropped.
        response.put("allowAdvanced", wantAdvanced);
        if (advancedRefused) {
            // Wording matters: the caller may well BE on the car with a stale cookie (e.g.
            // just after a token regenerate), so don't claim they are remote.
            response.put("warning", "Advanced keymap actions need a signed-in local "
                    + "session, so that setting was not changed. Sign in again on the car, "
                    + "or reload this page; it cannot be enabled over a tunnel.");
        }
        if (!ok) response.put("error", Messages.get("errors.keymap_persist_failed"));
        HttpResponse.sendJson(out, response.toString());
    }

    /** Fully-qualified component the accessibility service registers under. */
    private static final String A11Y_COMPONENT =
            "com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService";

    /**
     * Ensure our AccessibilityService is enabled in Secure settings — enabling it
     * ourselves rather than nagging the user. Key filtering (and process
     * keep-alive) is inert until it's on, and the app-side auto-enable runs
     * through an ADB shell session that isn't always available; the daemon,
     * however, runs as UID shell (2000) and holds WRITE_SECURE_SETTINGS, so it can
     * flip the Secure setting directly with `settings put`. Append-safe: never
     * clobbers other enabled services; the list write is a no-op when the
     * component is already present. It does NOT early-return on a present
     * component, though — it always (re)asserts the master accessibility_enabled
     * flag, since a listed-but-master-off install (keys dead) must still be healed.
     *
     * <p>Verify-after-enable: logs the enabled-services list and the master flag
     * BEFORE and AFTER the write, so the device log shows the truth (did the write
     * land? is the master flag on?) rather than us guessing. Best-effort — a
     * failure just leaves the settings-page nudge as the fallback.
     *
     * <p>Returns the post-write truth ({@code nowListed && masterOn}) it already
     * computes for the log line, so callers on the enable edge can reuse it as the
     * {@code a11yEnabled} they report instead of firing a second read-back pair of
     * {@code settings get} execs. Returns {@code false} on any failure (fail-safe:
     * matches {@link #isAccessibilityServiceEnabled()} reporting "off" so the nudge
     * stays visible rather than hiding it over a glitch).
     */
    private static boolean ensureAccessibilityServiceEnabled() {
        try {
            String before = readEnabledA11yServices();
            boolean alreadyListed = containsOurService(before);
            // Do NOT return early when already listed: the master flag
            // (accessibility_enabled) may still be 0, in which case the OS will
            // not bind/dispatch to the service (keys stay dead) even though the
            // component lingers in enabled_accessibility_services — the common
            // path on existing installs, where the keep-alive path already listed
            // the component but never asserted the master flag from here. The
            // script's list write is a no-op when the component is present (its
            // grep guard), and it always (re)asserts accessibility_enabled=1 —
            // mirroring ServiceLauncher's always-run second command. So letting
            // it run unconditionally is the self-heal.
            // Append our component to the ':'-separated list, preserving any
            // existing services; then set the master accessibility_enabled flag.
            // Single sh -c so the read-modify-write is one atomic settings call
            // sequence (matches ServiceLauncher's logic, run in-daemon).
            String script =
                "cur=$(settings get secure enabled_accessibility_services 2>/dev/null); " +
                "if echo \"$cur\" | grep -q '" + A11Y_COMPONENT + "'; then :; " +
                "elif [ -z \"$cur\" ] || [ \"$cur\" = 'null' ]; then " +
                "settings put secure enabled_accessibility_services '" + A11Y_COMPONENT + "'; " +
                "else settings put secure enabled_accessibility_services \"$cur:" + A11Y_COMPONENT + "\"; fi; " +
                "settings put secure accessibility_enabled 1";
            Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(script)).redirectErrorStream(true).start();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            // Re-read to confirm the write actually landed in Secure settings.
            String after = readEnabledA11yServices();
            boolean nowListed = containsOurService(after);
            boolean masterOn = isAccessibilityMasterEnabled();
            logger.info("Keymap a11y: enable attempted. wasListed=" + alreadyListed
                    + " before=" + safeLog(before) + " after=" + safeLog(after)
                    + " listed=" + nowListed + " masterEnabled=" + masterOn);
            if (!nowListed) {
                logger.warn("Keymap a11y: component still NOT in enabled_accessibility_services "
                        + "after settings put — the write did not take (permission or read-back lag)");
            } else if (!masterOn) {
                // Distinct from a failed write: the list is correct but the OS
                // master flag did not stick, so the app-process service will not
                // be bound and onKeyEvent will not fire — keys stay dead.
                logger.warn("Keymap a11y: component IS listed but accessibility_enabled != 1 "
                        + "after settings put — service will not bind (master flag did not take)");
            } else {
                // Listed AND master-on: the OS bind condition is satisfied. Note
                // this still does not prove the app-process service has finished
                // binding — AMS binds asynchronously and onServiceConnected (which
                // installs the key filter) may run shortly AFTER this write.
                // KeepAliveAccessibilityService.isRunning() is always false in the
                // UID-2000 daemon (the instance lives in the app process), so the
                // final bind is observed separately via isAccessibilityServiceBound()
                // (dumpsys ServiceRecord) rather than inferred from these flags.
                logger.info("Keymap a11y: listed + master-on (OS bind condition met; "
                        + "app-process bind completes asynchronously)");
            }
            // The OS bind condition (listed AND master-on) is the same predicate
            // isAccessibilityServiceEnabled() computes via its shell branch, so
            // hand it back for the enable-edge caller to reuse rather than re-read.
            return nowListed && masterOn;
        } catch (Throwable t) {
            logger.warn("Keymap: could not auto-enable accessibility service: " + t.getMessage());
            return false;
        }
    }

    /**
     * Read enabled_accessibility_services via the `settings` CLI. This is the
     * authoritative path in the daemon (UID 2000): the daemon's synthetic
     * ContentResolver is unreliable for Secure reads, whereas `settings get`
     * always works as shell. Returns "" on any failure.
     */
    private static String readEnabledA11yServices() {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    com.overdrive.app.util.ScratchRoot.remap("settings get secure enabled_accessibility_services 2>/dev/null"))
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            String out = sb.toString().trim();
            return ("null".equals(out)) ? "" : out;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Read the master accessibility_enabled flag via the `settings` CLI. The OS
     * will not bind/dispatch to ANY accessibility service unless this is 1 — so a
     * component listed in enabled_accessibility_services while this is 0 means the
     * service is inert (key filtering dead). Reliable as UID shell; returns false
     * on any failure (fail-safe: report "off" so the nudge stays visible rather
     * than hiding it over a read glitch).
     */
    private static boolean isAccessibilityMasterEnabled() {
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    com.overdrive.app.util.ScratchRoot.remap("settings get secure accessibility_enabled 2>/dev/null"))
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            return "1".equals(sb.toString().trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if the flat enabled-services string contains our component. */
    private static boolean containsOurService(String flat) {
        return flat != null
                && flat.contains("com.overdrive.app/")
                && flat.contains("KeepAliveAccessibilityService");
    }

    private static String safeLog(String s) {
        if (s == null || s.isEmpty()) return "<empty>";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * Whether our AccessibilityService is enabled — reported to the settings page
     * so it can hide the "enable accessibility" nudge.
     *
     * <p>Two independent sources, EITHER of which proves it's on:
     *   1. The live in-process instance — {@link
     *      com.overdrive.app.services.KeepAliveAccessibilityService#isRunning()}.
     *      Ground truth when the daemon and app share the process; a bound service
     *      can't lie. (When the a11y service and this handler run in different
     *      processes this is simply false, and we fall through to #2.)
     *   2. The Secure settings, read via the `settings` CLI (reliable as UID
     *      shell) — NOT the daemon ContentResolver, which returned false-negatives.
     *      This branch requires BOTH conditions the OS uses to bind a service:
     *      the component is listed in enabled_accessibility_services AND the master
     *      accessibility_enabled flag is 1. Listing alone is NOT proof — an install
     *      where the keep-alive path listed the component but never set the master
     *      flag would otherwise be reported enabled while the service is inert and
     *      keys are dead (the false-positive that hid the nudge with broken keys).
     *
     * <p>Caveat: in the UID-2000 daemon, source #1 is structurally always false
     * (the bound instance lives in the app process), so only source #2 fires there.
     * Even a satisfied source #2 proves the OS <i>bind condition</i> is met, not
     * that the app-process service has finished binding (AMS binds asynchronously).
     * This is deliberately the PRECONDITION signal, kept distinct from the stronger
     * "actually bound" truth — see {@link #isAccessibilityServiceBound()}, which the
     * daemon CAN observe from UID 2000 via the service's ServiceRecord. Callers that
     * must not over-report "ready" (e.g. the enable edge, where AMS has not bound
     * yet) should report the bound signal alongside this one rather than treating a
     * met precondition as proof of a live, key-dispatching service.
     */
    private static boolean isAccessibilityServiceEnabled() {
        try {
            // In-proc ground truth: a live bound instance can't lie, and its
            // presence already implies both Secure-settings conditions hold.
            if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) return true;
        } catch (Throwable ignored) { /* class may be absent in a stripped build */ }
        // Shell branch (the only one that fires in the UID-2000 daemon): report
        // enabled only when the service is BOTH listed AND the master flag is on —
        // matching the OS bind condition.
        return containsOurService(readEnabledA11yServices()) && isAccessibilityMasterEnabled();
    }

    /**
     * Whether our AccessibilityService is <b>actually bound and live</b> — the
     * stronger, honest counterpart to {@link #isAccessibilityServiceEnabled()},
     * which only reports the OS bind PRECONDITION (listed + master-on). The
     * precondition can be satisfied while AMS has not yet finished binding the
     * app-process service, so onKeyEvent does not fire and keys are dead even
     * though the precondition reads true — the transient false-positive on the
     * enable edge. This method resolves that ambiguity for the client.
     *
     * <p>Two independent sources, EITHER of which proves a live bind:
     *   1. In-proc {@link
     *      com.overdrive.app.services.KeepAliveAccessibilityService#isRunning()}
     *      — set in onServiceConnected, cleared in onDestroy, so it is exactly the
     *      bound state. Ground truth when this handler shares the app process; in
     *      the UID-2000 daemon the instance lives in another process and this is
     *      always false, so we fall through to #2.
     *   2. {@code dumpsys activity services <component>} showing an active
     *      ServiceRecord (not {@code app=null}). AMS hosts every bound
     *      AccessibilityService as a real bound service, so a live ServiceRecord
     *      for our component means the app-process bind has completed — the same
     *      probe {@code ServiceLauncher.isLocationSidecarRunning} and
     *      {@code SentryDaemon} already use for their services, run in-daemon here.
     *
     * <p>Fail-safe: returns {@code false} on any read glitch or timeout (so the
     * client keeps the nudge / "enabling…" state rather than falsely hiding it),
     * matching {@link #isAccessibilityServiceEnabled()}'s conservative reporting.
     */
    /**
     * Short-lived cache for the {@code dumpsys} bind probe below. The probe shells out with a 2s
     * ceiling and is called on EVERY config GET and POST, so an unbound service (exactly the
     * cold-boot case the client polls through) charged every request up to 2s — the "binding a key
     * takes too long right after car on" report. A bind state cannot flip meaningfully faster than
     * this window, and a POSITIVE result is never cached at all (see below), so the freshness that
     * matters is preserved. Volatile pair, written last-wins: a duplicated probe is harmless.
     */
    // Must EXCEED the client's 5500ms recheck cadence (key-mapping.js scheduleA11yRecheck), or the
    // entry expires just before every poll and the cache serves nobody — which is what a 2s and
    // then a 5s TTL did: each recheck still paid the full 2s dumpsys. 8s covers the cadence with
    // margin while staying short enough that a bind landing mid-window is reported on the next
    // poll. Only NEGATIVE results are cached, so this never delays observing a teardown.
    private static final long A11Y_BOUND_PROBE_TTL_MS = 8_000L;
    // MONOTONIC (elapsedRealtime), not wall clock: an RTC/NTP step is routine seconds after car
    // power-on, which is exactly when this cache is hot. A wall-clock age can jump or go
    // negative across that step; elapsedRealtime cannot, so the TTL means what it says. Matches
    // the clock choice in StreamingApiHandler for the same head-unit hazard.
    private static volatile long a11yBoundProbeAtMs = 0L;
    private static volatile boolean a11yBoundProbeResult = false;

    private static boolean isAccessibilityServiceBound() {
        try {
            // In-proc ground truth: onServiceConnected sets the instance, onDestroy
            // clears it, so this is precisely "bound right now".
            if (com.overdrive.app.services.KeepAliveAccessibilityService.isRunning()) return true;
        } catch (Throwable ignored) { /* class may be absent in a stripped build */ }
        // Serve a recent NEGATIVE probe from cache. Only negatives are cached: a false is what
        // costs a full 2s shell-out (nothing to match, so the dump is read to EOF), and it is the
        // state a polling client hammers while AMS is still binding. A true is cheap and must stay
        // instantaneous — caching it could report "bound" for up to the TTL after a teardown,
        // which would hide a genuinely dead key filter.
        long age = android.os.SystemClock.elapsedRealtime() - a11yBoundProbeAtMs;
        if (a11yBoundProbeAtMs != 0L && age >= 0 && age < A11Y_BOUND_PROBE_TTL_MS
                && !a11yBoundProbeResult) {
            return false;
        }
        // Daemon path (UID 2000): an active ServiceRecord for the component proves
        // AMS has bound it. Mirrors ServiceLauncher.isLocationSidecarRunning's
        // "non-empty && !app=null" test. 2s ceiling so a slow dumpsys can never
        // park the request thread; drained so the child can't wedge on a full pipe.
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    com.overdrive.app.util.ScratchRoot.remap("dumpsys activity services " + A11Y_COMPONENT + " 2>/dev/null"))
                    .redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
            String dump = sb.toString();
            // A bound service shows a ServiceRecord referencing our component and a
            // non-null hosting app. "app=null" means the record exists but nothing
            // is bound (not live) — reject it, matching the sidecar probe.
            boolean bound = dump.contains("ServiceRecord")
                    && dump.contains("KeepAliveAccessibilityService")
                    && !dump.contains("app=null");
            // VETO on a bind AMS says is still in flight. The three contains() tests above
            // are evaluated against one flat dump and nothing requires them to describe the
            // SAME record, so a wedged unit (an unrelated ServiceRecord with a non-null app=,
            // plus ConnectionRecord lines that merely mention our component) reads as BOUND
            // and the watchdog stops escalating — the observed 11-minute a11y wedge with the
            // key filter and BluetoothStateMonitor both dead. A service stuck mid-bind sits in
            // AMS's "Binding services" list and never in "Bound services", so asking the owner
            // of that truth can only ever turn a false "bound" into "not bound".
            //
            // Asked ONLY when the cheap probe already claims bound: it exists purely to
            // overturn that answer, so running it first (as the change that added it did) paid
            // a second dumpsys fork on every request that was already going to answer "not
            // bound" — the cost A11Y_BOUND_PROBE_TTL_MS was introduced to remove. The truth
            // table is identical either way.
            if (bound) {
                try {
                    String a11y = execBounded("dumpsys accessibility 2>/dev/null");
                    if (a11y != null && isBindPending(a11y)) bound = false;
                } catch (Throwable ignored) {
                    // Dump unavailable — keep the heuristic's answer, i.e. exactly the
                    // pre-existing behaviour. This check only ever adds detection.
                }
            }
            // Publish RESULT before TIMESTAMP, and never the other way round. The two volatiles
            // are unsynchronised, so a concurrent reader can observe a torn pair; this order
            // makes the only possible tear "new result, old timestamp", which just expires the
            // entry and re-probes. The reverse order could pair a fresh stamp with the PREVIOUS
            // result and serve a stale negative for a full TTL after the bind landed.
            a11yBoundProbeResult = bound;
            a11yBoundProbeAtMs = android.os.SystemClock.elapsedRealtime();
            return bound;
        } catch (Throwable t) {
            // Cache the failure too: a wedged/absent dumpsys otherwise re-pays its timeout on
            // every request. Fail-safe value is unchanged (false).
            a11yBoundProbeResult = false;
            a11yBoundProbeAtMs = android.os.SystemClock.elapsedRealtime();
            return false;
        }
    }

    /**
     * True when AMS lists our component under "Binding services" — the bind was started
     * but {@code onServiceConnected} never ran.
     *
     * <p>Scoped to that ONE list on purpose. "Enabled services" lists the same component
     * whenever the user has switched the service on, and "Bound services" is printed
     * immediately above, so a whole-dump {@code contains()} would match in every state and
     * report a permanent wedge on a perfectly healthy unit.
     *
     * <p>The scope is taken by BRACE MATCHING, not by reading to the end of the line. AMS
     * wraps a list onto continuation lines once it holds more than one entry — observed
     * live, with "Bound services" spilling its second entry ({@code Service[label=OverDrive
     * …]}) onto the next line. A line-bounded read would therefore silently miss a pending
     * component whenever a second service happens to be binding at the same time, i.e. it
     * would fail exactly in the busier situations this check exists for.
     *
     * <p>Note "Bound services" is NOT tested as the positive counterpart: it prints
     * {@code label=} (a user-facing, translatable, rebrandable string), never the component,
     * so keying "healthy" off it would turn a label change into a permanent restart loop.
     * The pending list names components, so it is the one that can be trusted.
     */
    private static boolean isBindPending(String dump) {
        int i = dump.indexOf("Binding services:");
        if (i < 0) return false;   // list absent on this build — say nothing
        int open = dump.indexOf('{', i);
        if (open < 0) return false;
        int depth = 0;
        for (int p = open; p < dump.length(); p++) {
            char c = dump.charAt(p);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                return dump.substring(open, p + 1).contains(A11Y_COMPONENT);
            }
        }
        // Unbalanced (truncated dump): fall back to the conservative answer rather than
        // scanning the remainder, which would sweep in the Window[...] entries below.
        return false;
    }

    /**
     * Run a shell command and return its output, or null on any failure. Bounded the same
     * way as the ServiceRecord probe: output drained on this thread so the child cannot
     * wedge on a full pipe, 2s ceiling so a slow dumpsys cannot park a watchdog tick, and
     * force-killed either way.
     */
    private static String execBounded(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    private static void handleFire(OutputStream out, String body) throws Exception {
        try {
            JSONObject req = new JSONObject(body == null ? "" : body);
            // A "sequence" runs several steps in order (one key → N actions).
            // Anything else is a single action. The advanced-shell
            // 403 is signalled by a runStep result with httpStatus=403 so the
            // gate behaves the same whether shell is a lone binding or a step.
            JSONObject r = runBoundAction(req);
            int status = r.optInt("httpStatus", 200);
            r.remove("httpStatus");
            if (status != 200) {
                HttpResponse.sendError(out, status, r.optString("error", "Refused"));
            } else {
                HttpResponse.sendJson(out, r.toString());
            }
        } catch (Exception e) {
            logger.warn("Keymap fire failed: " + e.getMessage());
            JSONObject response = new JSONObject();
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Run one bound action — a {@code sequence} or a single step — and return its result JSON
     * (never writes to the socket). The shared entry point for every surface that fires a
     * stored action payload: the physical-key {@code /api/keymap/fire} route above and the
     * user-defined quick-control buttons ({@link QuickControlsApiHandler}).
     *
     * <p>Routing through ONE method is what guarantees a quick-control button carries exactly
     * the physical key's privileges — same curated resolution, same API allowlist, same
     * {@code allowAdvanced} shell gate — instead of a parallel actuation path that could drift.
     * A refusal is reported via {@code httpStatus} in the result, which the caller may translate
     * to an HTTP status; it is never thrown.
     */
    static JSONObject runBoundAction(JSONObject req) throws org.json.JSONException {
        if (req == null) {
            JSONObject r = new JSONObject();
            r.put("success", false);
            r.put("error", "Missing action");
            return r;
        }
        return "sequence".equals(req.optString("kind", "")) ? runSequence(req) : runStep(req);
    }

    /**
     * Run one action step and return a result JSON (never writes to the socket).
     * {@code kind} selects the path; an advanced-shell step with the gate off
     * returns {success:false, httpStatus:403} so the caller can 403 a lone shell
     * binding while a sequence just records the refusal and moves on.
     */
    private static JSONObject runStep(JSONObject req) throws org.json.JSONException {
        String kind = req.optString("kind", "");
        switch (kind) {
            case "catalog": return runCatalog(req);
            case "vehicle": return runVehicle(req);
            case "api":     return runApi(req);
            case "automation": return runAutomation(req);
            case "actionGroup": return runActionGroup(req);
            case "radio":   return runRadio(req);
            case "manualClip": return runManualClip(req);
            case "openApp": return runOpenApp(req);
            case "shell":   return runShell(req);
            case "voice_assistant": return runVoiceAssistant();
            default: {
                JSONObject r = new JSONObject();
                r.put("success", false);
                r.put("error", "Unknown keymap action kind: " + kind);
                return r;
            }
        }
    }

    private static String validateVoiceAssistantBindings(org.json.JSONArray bindings) {
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.optJSONObject(i);
            JSONObject action = binding == null ? null : binding.optJSONObject("action");
            if (action == null || !"sequence".equals(action.optString("kind", ""))) continue;
            org.json.JSONArray steps = action.optJSONArray("steps");
            for (int j = 0; steps != null && j < steps.length(); j++) {
                JSONObject step = steps.optJSONObject(j);
                if (step != null && "voice_assistant".equals(step.optString("kind", ""))) {
                    return "bindings[" + i + "].action.steps[" + j
                            + "]: voice_assistant must be a direct key binding, not a sequence step";
                }
            }
        }
        return null;
    }

    private static JSONObject runVoiceAssistant() throws org.json.JSONException {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("error", "Talk to OverDrive runs only from a direct key binding");
        return response;
    }

    private static String validateManualClipBindings(org.json.JSONArray bindings) {
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.optJSONObject(i);
            if (binding == null) continue;
            String error = validateManualClipAction(
                    binding.optJSONObject("action"), "bindings[" + i + "].action", false);
            if (error != null) return error;
        }
        return null;
    }

    private static String validateManualClipAction(
            JSONObject action, String path, boolean insideSequence) {
        if (action == null) return null;
        String kind = action.optString("kind", "");
        if ("sequence".equals(kind)) {
            org.json.JSONArray steps = action.optJSONArray("steps");
            if (steps == null) return null;
            for (int i = 0; i < steps.length(); i++) {
                String error = validateManualClipAction(
                        steps.optJSONObject(i), path + ".steps[" + i + "]", true);
                if (error != null) return error;
            }
            return null;
        }
        if (!"manualClip".equals(kind)) return null;
        if (insideSequence) {
            return path + ": manualClip must be a direct key binding, not a sequence step";
        }

        Integer before = strictJsonInteger(action, "beforeSeconds");
        Integer after = strictJsonInteger(action, "afterSeconds");
        if (before == null || after == null) {
            return path + " requires integer beforeSeconds and afterSeconds";
        }
        try {
            ManualClipWindow.create(before, after);
            return null;
        } catch (IllegalArgumentException e) {
            return path + ": " + e.getMessage();
        }
    }

    private static Integer strictJsonInteger(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        Object raw = object.opt(key);
        if (!(raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long)) {
            return null;
        }
        long value = ((Number) raw).longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return null;
        return (int) value;
    }

    /** Manual-only replay action. Never passes through automationApiRequest(). */
    private static JSONObject runManualClip(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        Integer before = strictJsonInteger(req, "beforeSeconds");
        Integer after = strictJsonInteger(req, "afterSeconds");
        if (before == null || after == null) {
            response.put("success", false);
            response.put("status", ManualClipService.Status.INVALID_WINDOW.name());
            response.put("error", "beforeSeconds and afterSeconds must be integers");
            return response;
        }

        ManualClipService.RequestResult result =
                ManualClipService.getInstance().requestClip(before, after);
        response.put("success", result.isAccepted());
        response.put("status", result.status.name());
        response.put("availableBeforeSeconds", result.availableBeforeSeconds);
        if (result.isAccepted()) response.put("message", result.message);
        else response.put("error", result.message);
        logger.info("Keymap manualClip before=" + before + "s after=" + after
                + "s -> " + result.status);
        return response;
    }

    /**
     * Run a sequence of steps in order. Best-effort: a failing step is recorded
     * but does not abort the rest (a key bound to "close windows; lock" should
     * still try to lock even if the window close reports a non-zero HAL result).
     * Overall success = every step succeeded. Steps run on this request thread
     * (the a11y service fires the POST off its own executor and ignores the body),
     * so ordering is deterministic and sequential.
     */
    private static JSONObject runSequence(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        org.json.JSONArray steps = req.optJSONArray("steps");
        if (steps == null || steps.length() == 0) {
            response.put("success", false);
            response.put("error", "Sequence has no steps");
            return response;
        }
        org.json.JSONArray results = new org.json.JSONArray();
        boolean allOk = true;
        int ran = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            // Replay completes asynchronously (up to 60 s of post-roll). Treating
            // ACCEPTED as a completed sequence step would let a following camera
            // lifecycle action tear down the encoder before export. Bind it
            // directly instead; config validation and the UI enforce the same.
            if ("manualClip".equals(step.optString("kind", ""))) {
                response.put("success", false);
                response.put("error", "manualClip must be a direct key binding");
                return response;
            }
            JSONObject r = runStep(step);
            r.remove("httpStatus"); // sequence never 403s the whole call; per-step success carries it
            results.put(r);
            ran++;
            if (!r.optBoolean("success", false)) allOk = false;
        }
        // A sequence whose every entry was non-runnable (e.g. steps:[null] from a
        // hand-corrupted binding) actuated nothing, so it is not a success — mirror
        // the empty-steps contract above rather than report success:true for a no-op.
        boolean anyRan = ran > 0;
        logger.info("Keymap sequence: " + steps.length() + " step(s), ran=" + ran + ", allOk=" + allOk);
        response.put("success", allOk && anyRan);
        if (!anyRan) response.put("error", "Sequence had no runnable steps");
        response.put("steps", results);
        return response;
    }

    /**
     * Curated catalog action — same resolution path as the MQTT control router:
     * look up the entity, build a command from (sub, payload) against the current
     * vehicle snapshot, and run it SDK-only (no BYD cloud round-trip; key presses
     * must be instant and offline-safe).
     */
    private static JSONObject runCatalog(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String key = req.optString("key", null);
        String sub = req.has("sub") && !req.isNull("sub") ? req.optString("sub", null) : null;
        String payload = req.optString("payload", "");

        if (key == null || key.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing catalog key");
            return response;
        }

        VehicleControlCatalog.ControlEntity entity = VehicleControlCatalog.get(key);
        if (entity == null) {
            response.put("success", false);
            response.put("error", "Unknown control entity: " + key);
            return response;
        }

        BydDataCollector collector = BydDataCollector.getInstance();
        BydVehicleData snap = collector.isInitialized() ? collector.getData() : null;

        VehicleControlCatalog.ControlAction action = entity.toAction(sub, payload, snap);
        if (action == null || action.command == null) {
            response.put("success", false);
            response.put("error", "No action for " + key + (sub != null ? "/" + sub : "") + " payload='" + payload + "'");
            return response;
        }

        // Key mappings preserve the SDK as the first leg, but must use the
        // full router when a command declares a cloud fallback. This lets a
        // sleeping vehicle use its proven remote path without changing the
        // immediate local behavior while it is awake. MQTT remains explicitly
        // local-only in MqttCommandRouter.
        boolean needsCloud = action.command.hasCloudPath()
                && action.command.defaultPreference() != VehicleCommandRouter.RoutePreference.SDK_ONLY;
        VehicleCommandRouter.CommandResult r = needsCloud
                ? VehicleCommandRouter.getInstance().execute(action.command)
                : VehicleCommandRouter.getInstance().executeSdkOnly(action.command);
        logger.info("Keymap catalog '" + key + (sub != null ? "/" + sub : "") + "' payload='" + payload
                + "' -> " + action.command.name() + " " + r.outcome + " (" + r.latencyMs + "ms, "
                + (needsCloud ? "full" : "sdk-only") + ")");

        // Record what we commanded for a blind-toggle entity (mirror fold) — but only once the
        // command actually reached the vehicle, so a press refused by the motion-safety gate
        // can't silently swap what the NEXT press does. See ControlAction.commitIfAttempted.
        action.commitIfAttempted(r.outcome);

        response.put("success", r.outcome == VehicleCommandRouter.Outcome.SUCCESS);
        response.put("outcome", r.outcome.toString());
        response.put("message", r.displayMessage);
        return response;
    }

    /**
     * Composite vehicle commands not registered in the catalog. These are
     * cloud-only (lock/flash have no local SDK path on this generation), so
     * unlike the catalog path they route through the full {@code execute()}
     * capability matrix rather than SDK-only.
     */
    private static JSONObject runVehicle(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String action = req.optString("action", "");
        VehicleCommandRouter.VehicleCommand cmd;
        switch (action) {
            case "lock":     cmd = new VehicleCommandRouter.LockCommand(); break;
            case "unlock":   cmd = new VehicleCommandRouter.UnlockCommand(); break;
            case "flash":    cmd = new VehicleCommandRouter.FlashLightsCommand(); break;
            case "find_car": cmd = new VehicleCommandRouter.FindCarCommand(); break;
            default:
                response.put("success", false);
                response.put("error", "Unknown vehicle action: " + action);
                return response;
        }

        VehicleCommandRouter.CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
        logger.info("Keymap vehicle '" + action + "' -> " + cmd.name() + " " + r.outcome + " (" + r.latencyMs + "ms)");

        response.put("success", r.outcome == VehicleCommandRouter.Outcome.SUCCESS);
        response.put("outcome", r.outcome.toString());
        response.put("message", r.displayMessage);
        return response;
    }

    /**
     * Internal API action — for curated features that have no VehicleControlCatalog
     * SDK entity (surveillance enable/disable, recording mode). Routes through the
     * same auth-free, allowlisted in-process path automations use
     * ({@link HttpServer#automationApiRequest}), so the keymap can reach exactly the
     * control surface the allowlist permits and nothing more. Not advanced-gated: the
     * allowlist — NOT this handler — is the boundary, and everything on it is a curated
     * vehicle/camera control, unlike the arbitrary-command shell escape hatch. Note a
     * hand-written binding may name any allowlisted path, not just the ones the UI
     * offers; see HttpServer.isAutomationAllowed for the authoritative policy.
     */
    private static JSONObject runApi(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String method = req.optString("method", "POST");
        String path = req.optString("path", null);
        String body = req.optString("body", "");

        if (path == null || path.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing api path");
            return response;
        }

        HttpServer server = CameraDaemon.getHttpServer();
        if (server == null) {
            response.put("success", false);
            response.put("error", "HTTP server not available");
            return response;
        }

        // automationApiRequest returns the full HTTP response (status line + headers
        // + body), or null when the path is outside the automation allowlist, the
        // route is unhandled, or the handler threw. A non-null "HTTP/1.1 2xx" is success.
        String httpResponse = server.automationApiRequest(method, path, body);
        // ...but the status line ALONE is not enough: HttpResponse.sendJsonError replies
        // 200 with {"success":false}, so a rejected request (a bad value, a feature that
        // declined) looked identical to a working one and was logged as SUCCESS. Treat an
        // explicit success:false body as failure too, matching ApiAction.logFailure. An
        // async {"starting":true} is a "come back later", not a failure.
        boolean ok = httpResponse != null && isSuccessStatus(httpResponse)
                && !isDeclinedBody(httpResponse);
        logger.info("Keymap api '" + method + " " + path + "' -> " + (ok ? "SUCCESS" : "FAILED"));

        response.put("success", ok);
        if (!ok) {
            response.put("error", httpResponse == null
                    ? "API request denied or not handled" : "API request returned an error");
        }
        return response;
    }

    /**
     * Run a saved automation on a keypress. Automatic rules enqueue on the normal
     * singleton worker, preserving their existing conditions/run-stat behavior.
     * Manual-only rules dispatch to the serialized explicit-run worker, where the
     * keypress itself is the trigger and the primary branch runs unconditionally.
     * Rejects an unknown id up front so a stale binding reports a clear error.
     */
    private static JSONObject runAutomation(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String id = req.optString("id", "");
        if (id == null || id.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing automation id");
            return response;
        }
        if (!com.overdrive.app.automation.Automations.automationExists(id)) {
            response.put("success", false);
            response.put("error", "Unknown automation: " + id);
            return response;
        }
        if (com.overdrive.app.automation.Automations.isManualOnly(id)) {
            AutomationApiHandler.dispatchExplicitAutomation(id, true);
            logger.info("Keymap manual-only automation dispatched: " + id);
            response.put("success", true);
            return response;
        }
        // Enqueue with no delay — the worker checks conditions and records stats.
        com.overdrive.app.automation.AutomationQueue.addToQueue(id, 0);
        logger.info("Keymap automation enqueued: " + id);
        response.put("success", true);
        return response;
    }

    /**
     * Run a reusable action group on a keypress — the group equivalent of {@link #runAutomation}.
     *
     * <p>Dispatched to a daemon thread rather than run inline, for the same reason the automation
     * kind enqueues instead of running: a group may contain a {@code pause}/{@code waitUntil}
     * step that blocks for minutes, and this executes on the HTTP worker thread. Unlike an
     * automation there is no AutomationQueue entry to use (a group has no id in that store, no
     * conditions and no run-stats), so a group fires unconditionally — matching what a user
     * pressing a button expects.
     *
     * <p>Like {@code manualClip}, this is a dedicated in-process call and deliberately NOT
     * routed through the automation API allowlist, which excludes /api/action-groups on purpose.
     */
    private static JSONObject runActionGroup(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String id = req.optString("id", "");
        if (id == null || id.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing action group id");
            return response;
        }
        if (!com.overdrive.app.automation.ActionGroups.exists(id)) {
            response.put("success", false);
            response.put("error", "Unknown action group: " + id);
            return response;
        }
        // IN-FLIGHT GUARD. A physical button gets mashed, and each press would otherwise spawn
        // another thread running the same list CONCURRENTLY — interleaving vehicle commands, and
        // piling up threads for minutes if the group contains a pause/waitUntil. The automation
        // kind gets this free (AutomationQueue dedupes by id); a group has no queue, so it is
        // enforced here. A repeat press while the group is still running is a logged no-op, which
        // is what the automation path does too.
        if (!GROUPS_IN_FLIGHT.add(id)) {
            logger.info("Keymap action group " + id + " already running — ignoring repeat press");
            response.put("success", true);
            response.put("message", "Already running");
            return response;
        }
        Thread t = new Thread(() -> {
            try {
                com.overdrive.app.automation.Automations.runActionList(
                        com.overdrive.app.automation.ActionGroups.getActions(id));
            } catch (Throwable e) {
                logger.error("Keymap action group " + id + " failed", e);
            } finally {
                GROUPS_IN_FLIGHT.remove(id);
            }
        }, "KeymapActionGroup");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable e) {
            // The runnable never ran, so its finally never will either — release the guard here or
            // this group stays "in flight" for the life of the process and becomes unrunnable.
            // Realistic trigger: OutOfMemoryError creating a native thread on a starved daemon.
            GROUPS_IN_FLIGHT.remove(id);
            logger.error("Keymap action group " + id + ": could not start worker", e);
            response.put("success", false);
            response.put("error", "Could not start action group");
            return response;
        }
        logger.info("Keymap action group dispatched: " + id);
        response.put("success", true);
        return response;
    }

    /** Action-group ids currently executing from a keypress — see {@link #runActionGroup}. */
    private static final java.util.Set<String> GROUPS_IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Toggle a device radio (WiFi / Bluetooth / mobile-data) on a keypress. Delegates
     * to {@link com.overdrive.app.byd.RadioControl}, which runs the {@code svc} shell
     * command and — for WiFi — keeps the keep-alive suppression flag in sync so an
     * explicit off isn't auto-re-enabled by the watchdog. Not advanced-gated: toggling a
     * radio is a curated, bounded action (the UI offers only wifi/bluetooth/data), not an
     * arbitrary-exec hatch.
     */
    private static JSONObject runRadio(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        com.overdrive.app.byd.RadioControl.Radio radio =
                com.overdrive.app.byd.RadioControl.parse(req.optString("radio", ""));
        if (radio == null) {
            response.put("success", false);
            response.put("error", "Unknown or missing radio (expected wifi/bluetooth/data)");
            return response;
        }
        // Accept either an explicit boolean or an on/off state string.
        boolean enable = req.has("enable")
                ? req.optBoolean("enable", false)
                : "on".equalsIgnoreCase(req.optString("state", ""));
        boolean ok = com.overdrive.app.byd.RadioControl.set(radio, enable);
        logger.info("Keymap radio '" + radio + "' " + (enable ? "on" : "off") + " -> " + (ok ? "SUCCESS" : "FAILED"));
        response.put("success", ok);
        if (!ok) response.put("error", "Radio toggle failed");
        return response;
    }

    /**
     * Open an installed app by package name via the shared {@link AppLauncher}.
     * Not gated by allowAdvanced — launching an app is equivalent to the user
     * tapping its launcher icon, not an arbitrary-exec escape hatch.
     */
    private static JSONObject runOpenApp(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        String pkg = req.optString("package", "");
        if (pkg == null || pkg.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing app package");
            return response;
        }
        boolean split = req.optBoolean("split", false);
        boolean ok = com.overdrive.app.launcher.AppLauncher.launch(pkg, split);
        logger.info("Keymap openApp '" + pkg + "'" + (split ? " [split]" : "") + " -> " + (ok ? "SUCCESS" : "FAILED"));
        response.put("success", ok);
        if (!ok) response.put("error", "Could not launch " + pkg);
        return response;
    }

    /**
     * True if a raw HTTP response's JSON body explicitly DECLINED the request, i.e.
     * carries {@code "success":false} without the async {@code "starting":true} marker.
     * Needed because the daemon's JSON error helper replies 200, so the status line alone
     * cannot distinguish "applied" from "rejected". A body we can't parse, or one with no
     * {@code success} key, is left alone (not treated as a failure).
     */
    private static boolean isDeclinedBody(String httpResponse) {
        try {
            // Anchor on the header/body separator rather than the first '{': a brace in a
            // header would otherwise start the parse mid-headers, and a top-level JSON
            // ARRAY would parse its first element and let one item's success flag speak
            // for the whole response.
            int sep = httpResponse.indexOf("\r\n\r\n");
            if (sep < 0) return false;
            String body = httpResponse.substring(sep + 4).trim();
            if (!body.startsWith("{") || !body.contains("\"success\"")) return false;
            org.json.JSONObject json = new org.json.JSONObject(body);
            if (json.optBoolean("starting", false)) return false;
            return !json.optBoolean("success", true);
        } catch (Exception e) {
            return false;   // unparseable → fail OPEN (pre-fix behaviour)
        }
    }

    /** True if a raw HTTP response string carries a 2xx status line. */
    private static boolean isSuccessStatus(String httpResponse) {
        // Format: "HTTP/1.1 <code> <reason>\r\n..."; parse the code defensively.
        int sp = httpResponse.indexOf(' ');
        if (sp < 0 || sp + 4 > httpResponse.length()) return false;
        try {
            int code = Integer.parseInt(httpResponse.substring(sp + 1, sp + 4));
            return code >= 200 && code < 300;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Advanced escape hatch — run a shell command. Gated by the keymap
     * section's {@code allowAdvanced} flag; refused with 403 otherwise so a
     * curated-only install can never be coerced into arbitrary exec by a
     * crafted request. Runs in the daemon (UID 2000), same as the other
     * daemon-side exec sites.
     */
    private static JSONObject runShell(JSONObject req) throws org.json.JSONException {
        JSONObject response = new JSONObject();
        if (!com.overdrive.app.config.UnifiedConfigManager.isKeymapAdvancedAllowed()) {
            // Signal a 403 to the single-fire caller; runSequence strips this and
            // just treats the step as failed. Never executes when the gate is off.
            response.put("success", false);
            response.put("error", "Advanced keymap actions are disabled.");
            response.put("httpStatus", 403);
            return response;
        }
        String cmd = req.optString("cmd", "");
        if (cmd.isBlank()) {
            response.put("success", false);
            response.put("error", "Missing shell command");
            return response;
        }
        try {
            final Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd))
                    .redirectErrorStream(true)
                    .start();
            final java.io.InputStream is = p.getInputStream();
            // Bound the whole exec by the 5s ceiling, not just the reap. A
            // dedicated daemon thread drains (and discards) stdout so the child
            // can never wedge on a full (>~64KB) pipe, but because it runs off
            // this request thread its blocking read() can never outlive the
            // deadline below — a long-lived-stdout command (logcat, top,
            // tail -f, a backgrounded child) is force-killed at 5s instead of
            // parking this HttpServer worker forever. (The earlier idiom drained
            // to EOF *before* waitFor, so destroyForcibly was unreachable for
            // exactly those commands; AccSentryDaemon.execShell has no waitFor
            // timeout and so is not a bounded precedent either.)
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    while (is.read(buf) != -1) { /* discard */ }
                } catch (Throwable ignored) { /* pipe closed on kill */ }
            }, "keymap-shell-drain");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            drain.join(500);        // let the reader unwind after the pipe closes
            try { is.close(); } catch (Throwable ignored) { }
            logger.info("Keymap shell fired: " + cmd);
            response.put("success", true);
        } catch (Throwable t) {
            logger.warn("Keymap shell failed: " + t.getMessage());
            response.put("success", false);
            response.put("error", t.getMessage());
        }
        return response;
    }

    // NOTE: a raw-CAN "kind" was intentionally left out. Sending arbitrary bus
    // frames needs (a) a proven reach into the acquisition/diagnostic service
    // from UID 2000 — BYDACQUISITION_SEND_BUFFER is declared but its HAL
    // acceptance for our UID is unverified — and (b) a per-vehicle frame map the
    // user can only obtain by sniffing their own bus. Until both exist, exposing
    // a hex textbox is unusable. Track as future work; the shell hatch covers the
    // advanced cases in the meantime.

    private KeymapApiHandler() {}
}
