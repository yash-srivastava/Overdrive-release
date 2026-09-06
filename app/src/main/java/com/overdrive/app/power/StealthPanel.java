package com.overdrive.app.power;

import android.content.Context;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * DiLink 4 parked-panel control: turn the screen genuinely OFF and verify it.
 *
 * <h3>Provenance — this mirrors the reference app</h3>
 * Reverse-engineered from the OEM dashcam app (the byd_apa / AVMCamera
 * reference app, {@code app_bydSofaProRelease}), classes
 * {@code BacklightController} and {@code DeviceWakeupMonitor}. The behaviour
 * copied here, verified against its decompiled bytecode:
 *
 * <ul>
 *   <li><b>It uses real backlight-off, never brightness.</b> The reference app
 *       contains <b>zero</b> writes to {@code Settings.System.SCREEN_BRIGHTNESS}
 *       and zero {@code settings put screen_brightness} shell calls. Setting
 *       brightness to 0 does NOT put this panel to sleep — the backlight rail
 *       stays powered and the screen is still visibly lit on many trims. Only
 *       {@code TurnBacklightOff} actually darkens it.</li>
 *   <li><b>Two tiers, in this order</b> — {@code PowerManager.TurnBacklightOff/
 *       turnBacklightOff(long)}, and then, only if the screen is still not off,
 *       {@code TurnBacklightOffWithLock(IBinder)} invoked on the PowerManager's
 *       private {@code mService} binder. The second tier is the one that sticks
 *       when the first is silently ignored, and we previously did not have it
 *       at all.</li>
 *   <li><b>It verifies, and retries with the other tier.</b> Every transition is
 *       checked with {@code PowerManager.getPowerScreenStatus()} (0 = off,
 *       1 = on) rather than trusted. See {@link #turnOff}/{@link #turnOn}, which
 *       reproduce the reference app's exact check-call-check-call-check shape.</li>
 *   <li><b>It never calls {@code userActivity}.</b> Zero occurrences in the
 *       whole APK. It holds the AP awake with {@code PowerManager.wakeUp} on a
 *       60 s cadence plus {@code svc wifi enable}, and it darkens the panel
 *       ONCE at park entry — it does not pump an activity timer that would
 *       fight the display state.</li>
 *   <li><b>It turns the backlight off WHILE the AVM camera records.</b>
 *       {@code DeviceWakeupMonitor.startMonitor()} dispatches its
 *       {@code sleepScreen} message at park entry, concurrently with
 *       {@code AVMCameraRecordAgent}. So on this firmware backlight-off is NOT
 *       what kills the AVM preview — the earlier assumption behind suppressing
 *       it on dilink4 was wrong.</li>
 * </ul>
 *
 * <h3>One deliberate divergence: we re-assert, they don't</h3>
 * In the reference app {@code sleepScreen} is a ONE-SHOT — {@code startMonitor()}
 * posts wakeUsb, then sleepScreen, then the wifi check, and only the wifi message
 * reschedules (60 s). It can afford one-shot precisely because it never pumps
 * {@code userActivity}, so nothing it does subsequently relights the panel.
 *
 * <p>We cannot copy that. Our keep-alive must pump {@code userActivity} every
 * 10 s (on DiLink 3 the USB VBUS follows AP wakefulness, and "keep USB powered
 * while parked" is a shipped feature) and re-casts {@code PowerManager.wakeUp}
 * every 8 minutes for the MCU/SD rails. Both relight the panel. So
 * {@link #turnOff} is called every keep-alive tick instead of once. It self-skips
 * when {@code getPowerScreenStatus()} already reads off, so the steady-state cost
 * is one status read per tick, and it recovers automatically from anything that
 * relit the panel (the 8-min wakeUp, a motion deterrent that ended without
 * cleaning up, a user who woke the screen and walked away).
 *
 * <h3>Scope</h3>
 * <b>{@link #turnOff} is a no-op unless {@code camera.cameraMode} is exactly
 * {@code dilink4}.</b> Legacy pano_h/pano_l units keep
 * {@code AccSentryDaemon.setBacklightState()} untouched — this class only adds
 * the reference app's WithLock tier and status verification for dilink4.
 * {@link #turnOn} is intentionally NOT mode-gated: waking is always safe, and
 * gating it would let a {@code cameraMode} change made mid-park strand the panel
 * dark with no way for the user to recover.
 */
public final class StealthPanel {

    private static final String TAG = "StealthPanel";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** {@code getPowerScreenStatus()} return values (reference app semantics). */
    private static final int SCREEN_STATUS_OFF = 0;
    private static final int SCREEN_STATUS_ON = 1;
    private static final int SCREEN_STATUS_UNKNOWN = -1;

    private StealthPanel() {}

    // ── Mode gate ──────────────────────────────────────────────────────────

    /**
     * True only on {@code cameraMode=dilink4}. Mirrors
     * {@code AccSentryDaemon.isDilink4CameraMode()} exactly (same section, same
     * key, same case-insensitive compare, same fail-closed default). Returns
     * false on any failure — a config read error must never divert a legacy unit
     * onto the dilink4 path.
     */
    public static boolean isDilink4() {
        try {
            if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
                return true;
            }
            JSONObject c = UnifiedConfigManager.loadConfig().optJSONObject("camera");
            if (c == null) return false;
            return "dilink4".equalsIgnoreCase(c.optString("cameraMode", "default"));
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Turn the panel OFF, reproducing the reference app's
     * {@code BacklightController.turnOffBacklight()} exactly: try the plain
     * PowerManager tier only if the screen isn't already off, then escalate to
     * the WithLock tier if it's still not off, then log the verified outcome.
     *
     * <p>Skipping a tier when the screen already reads off is the reference
     * app's own behaviour and matters — re-asserting an already-off panel is
     * what produces the flicker some trims show.
     *
     * @return true when {@code getPowerScreenStatus()} confirms the screen is
     *         off afterwards. Also returns true when the status API is absent
     *         (we cannot verify, so we trust the write rather than report a
     *         false failure); returns false only on a confirmed still-on panel.
     */
    public static boolean turnOff(Context ctx) {
        if (!isDilink4()) return false;
        if (ctx == null) return false;
        // SAFETY: never darken the panel while ACC reads ON. This is the
        // load-bearing guard for the whole class because it covers EVERY caller
        // rather than relying on each one to check.
        //
        // The case that motivated it: owner walks up to a parked car → motion →
        // deterrent wakes the panel → owner gets in and starts the car → ACC ON →
        // the deterrent's render loop exits and its cleanup() calls turnOff(). That
        // teardown is a chain of contended locked config writes and binder calls
        // racing AccSentryDaemon's ~3.3 s ScreenWake retry loop; if it lands after
        // the last wake, the driver's screen goes dark WITH the tier-2 vendor lock
        // held. If AccSentryDaemon is dead or wedged at that transition there is no
        // retry loop at all and the panel stays dark for the whole drive.
        //
        // ScreenDeterrent.shouldStop() already guards its render loop with this
        // exact direct isAccOn() read (deliberately, rather than trusting the
        // cross-process screenDeterrentForceStop flag, which is only written on the
        // ACC OFF→ON edge and is missed entirely if that daemon is down). This is
        // the same guard applied to the darken itself. isAccOn() is a free volatile
        // read; it can be stale-false in narrow windows, which fails in the safe
        // direction (we darken a genuinely parked car, and the keep-alive would
        // have anyway).
        if (isAccOnForSuppression()) {
            logger.info("turnOff suppressed — ACC is ON "
                + "(must never darken the panel while the vehicle is in use)");
            return false;
        }
        return apply(ctx, false, /* allowTier2 */ true);
    }

    /**
     * Turn the panel ON, mirroring {@code BacklightController.turnOnBacklight()}.
     * Same two-tier escalation and verification as {@link #turnOff}.
     *
     * <p>Deliberately NOT gated on {@link #isDilink4()}: if the user switches
     * {@code cameraMode} away from dilink4 while parked, the gate would flip
     * false and a mode-gated wake would strand the panel dark. Waking is always
     * safe, so it is always allowed.
     *
     * @return true when the screen is confirmed (or unverifiable-but-attempted) on.
     */
    public static boolean turnOn(Context ctx) {
        if (ctx == null) return false;
        // REGRESSION GUARD (DiLink 3): turnOn is intentionally reachable on every
        // unit, but it must not introduce a panel write that legacy never made —
        // a legacy unit must never newly invoke TurnBacklightOnWithLock on an
        // ACC-ON edge, boot recovery, deterrent wake or SoC shutdown.
        //
        // The gate is "did WE take the tier-2 lock in this process?", NOT "is this
        // unit dilink4 right now". Those differ in the case that matters: if the
        // user switches cameraMode away from dilink4 while parked with the panel
        // held dark by TurnBacklightOffWithLock, a config-based gate would flip
        // false and tier 2 could never release the lock — no other code path in
        // the app has a *WithLock variant, so the panel would be stuck dark until
        // a reboot. Tracking the actual lock means the release is always available
        // to whoever took it, while a unit that never took one never calls it.
        //
        // isDilink4() is still consulted as a fallback for the cross-process case:
        // the daemon that took the lock may be dead, and the process handling
        // ACC-ON may never have called turnOff itself.
        return apply(ctx, true, /* allowTier2 */ tier2LockHeld || isDilink4());
    }

    /**
     * Wake even when {@code getPowerScreenStatus()} already claims ON.
     * Parked DiLink 5 can report ON while the backlight is actually off;
     * {@link #turnOn} then no-ops and a screen-deterrent layer composites
     * onto a dark panel.
     */
    public static boolean forceTurnOn(Context ctx) {
        if (ctx == null) return false;
        boolean allowTier2 = tier2LockHeld || isDilink4();
        boolean t1 = setBacklightViaPowerManager(ctx, true);
        boolean t2 = allowTier2 && setBacklightWithLock(ctx, true);
        writeUnverifiedWant(true);
        int status = screenStatus(ctx);
        logStateChange(true, status);
        return t1 || t2
                || status == SCREEN_STATUS_ON
                || status == SCREEN_STATUS_UNKNOWN
                || !isPlausibleStatus(status);
    }

    /**
     * Shared engine for {@link #turnOff}/{@link #turnOn}: escalate through the
     * two tiers, re-reading the screen status only after a write has actually
     * happened, and log only when the outcome changes.
     *
     * <p>Ordering here is deliberate and was a real defect before: the previous
     * shape called {@code screenStatus()} three times unconditionally, so the
     * steady state (panel already in the wanted state — ~99.9% of the ~8600
     * keep-alive ticks in an overnight park) paid 3 binder round-trips to
     * system_server for one piece of information, and two of them could not
     * return anything different from the first because no write had intervened.
     * Now the steady state is exactly one status read and zero panel writes.
     *
     * <p>The {@code SCREEN_STATUS_UNKNOWN} handling is the other half of that
     * fix. On firmware with no {@code getPowerScreenStatus()}, status is
     * permanently -1, which is neither ON nor OFF — so the old code re-fired
     * BOTH backlight tiers on every single tick, all night. Now an unverifiable
     * panel gets one attempt per state transition: we apply the tiers, then stop,
     * because there is no signal that could ever tell us to escalate further.
     *
     * @param want       {@code true} = panel on, {@code false} = panel off.
     * @param allowTier2 whether the vendor {@code *WithLock} escalation may run.
     *                   False on legacy units, where introducing that call would
     *                   be a behavioural change on a proven fleet.
     */
    private static boolean apply(Context ctx, boolean want, boolean allowTier2) {
        final int target = want ? SCREEN_STATUS_ON : SCREEN_STATUS_OFF;
        int status = screenStatus(ctx);

        if (status == target) {
            // Already where we want to be — no write, no further reads.
            return true;
        }

        // An unrecognised value (not 0/1/-1 — e.g. a vendor dim/doze tier) is
        // treated exactly like "cannot verify": fall into the latched branch below.
        // Handling it HERE rather than after the tiers is what prevents the pattern
        // where status never equals the target, so every 10 s tick re-fires the
        // backlight write and re-writes the latch — ~8600 vendor calls AND ~8600
        // full-config writes per park, with logStateChange dedup hiding all of it
        // behind a single log line.
        if (!isPlausibleStatus(status)) {
            logger.info("getPowerScreenStatus returned unrecognised value " + status
                + " — treating panel state as unverifiable");
            status = SCREEN_STATUS_UNKNOWN;
        }

        if (status == SCREEN_STATUS_UNKNOWN) {
            // No getPowerScreenStatus() on this firmware, so status is permanently
            // -1 and can never equal the target. Without a latch the keep-alive
            // would re-fire BOTH tiers every 10 s all night (~8600 redundant
            // vendor writes per park) and could stack vendor locks.
            //
            // The latch is published to CONFIG, not held in a static. That is
            // load-bearing: the panel is one global device but the processes that
            // change it are three. acc_sentry darkens; byd_cam_daemon relights it
            // (deterrent wake, user screen-on). With a process-local latch,
            // acc_sentry would still believe "I asked for dark, nothing to do" and
            // never re-darken — panel lit for the whole park on exactly the
            // firmware this latch was added to serve.
            // Applies to DARKENING ONLY. A wake must never self-skip on the latch:
            // the latch is a record of intent, not a measurement, and if it says ON
            // while the panel is actually dark (a swallowed config write, or a
            // cross-process interleave between another process's tiers and its
            // write) then skipping here returns "already on" for a black screen and
            // the driver has no way to recover. turnOn is edge-driven — seven call
            // sites, none per-tick — so re-asserting it costs nothing, whereas the
            // 10 s keep-alive genuinely needs the darken short-circuit.
            if (!want && readUnverifiedWant() == UNVERIFIED_OFF) {
                return true;
            }
            // If NEITHER tier is even available on this firmware, this call can
            // never do anything. Say so once and give up permanently rather than
            // re-running the caller's expensive gate chain (two forced config
            // re-parses) plus two failing reflective invokes every 10 s for the
            // whole park. capability=false is a property of the build, not state.
            if (tiersKnownUnavailable) return false;
            boolean t1 = setBacklightViaPowerManager(ctx, want);
            boolean t2 = allowTier2 && setBacklightWithLock(ctx, want);
            if (!t1 && !t2) {
                // Latch "unavailable" ONLY on proof of ABSENCE, never on a runtime
                // failure. tier 2 returns false on its deliberate "may have landed,
                // not retrying" path too (a DeadObjectException across a
                // system_server restart, a transient binder error) — and that means
                // the method EXISTS. Latching on it would permanently disable
                // parked darkening after one transient blip, with the ~5-min WARN
                // silenced along with it, leaving the panel lit for every later park
                // behind a single log line. Absence is what the resolvers record.
                if (allowTier2 && tier1KnownAbsent(want) && tier2KnownAbsent) {
                    tiersKnownUnavailable = true;
                    logger.warn("Panel control unavailable on this firmware "
                        + "(no TurnBacklight*(long) and no *WithLock via mService) — "
                        + "parked auto-darkening disabled");
                }
                // NEITHER tier could even be invoked (methods absent, reflection
                // blocked). Do NOT latch: latching here would record an intent we
                // never actually asserted and permanently suppress every retry,
                // leaving the panel lit while the log shows a single benign-looking
                // "unverifiable" line. Report failure so callers can escalate.
                logStateChange(want, SCREEN_STATUS_UNKNOWN);
                return false;
            }
            writeUnverifiedWant(want);
            logStateChange(want, SCREEN_STATUS_UNKNOWN);
            return true;
        }

        // Tier 1, then verify.
        setBacklightViaPowerManager(ctx, want);
        status = screenStatus(ctx);

        // Tier 2 (the vendor lock-holding variant) only if tier 1 did not take.
        // This is the escalation the reference app relies on and the reason a
        // plain turnBacklightOff was not enough on this firmware.
        //
        // A post-write status that is UNKNOWN or unrecognised means we cannot tell
        // whether tier 1 took, so escalating would be guesswork; treat the write as
        // trusted instead. (The pre-tier normalisation above already routed a
        // permanently-unrecognised firmware into the latched branch, so reaching
        // here with a non-plausible value means the read went bad transiently — the
        // next tick re-reads and completes the escalation if it is still needed.)
        if (allowTier2 && status != target && isPlausibleStatus(status)
                && status != SCREEN_STATUS_UNKNOWN) {
            setBacklightWithLock(ctx, want);
            status = screenStatus(ctx);
        }

        boolean ok = (status == target) || !isPlausibleStatus(status)
            || (status == SCREEN_STATUS_UNKNOWN);
        logStateChange(want, status);
        return ok;
    }

    /**
     * ACC-ON check used to suppress darkening. Must be safe in BOTH directions,
     * which needs care because the underlying flag is not equally trustworthy in
     * every process.
     *
     * <p>{@code AccMonitor.accOn} and {@code inSentryMode} are strict complements
     * at every write, so combining them proves nothing. The real hazard is
     * staleness: {@code AccMonitor} is refreshed by IPC from acc_sentry_daemon and
     * by {@code probeAccState()}, but INSIDE acc_sentry_daemon itself nothing
     * refreshes it after start-up — that process calls {@code probeAccState()} only
     * on its boot path. Start the daemon while the car is ON and {@code accOn}
     * latches true for that process's whole lifetime. A bare
     * {@code isAccOn()} check here would then suppress darkening on every
     * subsequent park, silently reintroducing the bug this class exists to fix.
     *
     * <p>So a true reading is honoured only while it is FRESH. The panel-darkening
     * caller is the ACC-OFF keep-alive, which by construction runs only after an
     * ACC-OFF transition; a genuine ACC-ON during a park is therefore a recent
     * event, and {@link #noteAccOnObserved} stamps it. An ACC-ON older than
     * {@link #ACC_ON_TRUST_WINDOW_MS} is treated as stale and ignored, so the
     * boot-latched case cannot hold the panel lit.
     *
     * <p>Both failure directions stay benign: a missed ACC-ON means we darken once
     * and the ACC-ON wake paths immediately relight (a brief flicker at worst),
     * while a stale ACC-ON being ignored means we correctly darken a parked car.
     */
    private static boolean isAccOnForSuppression() {
        try {
            if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) return false;
            long stamp = accOnObservedAtMs;
            if (stamp <= 0L) return false;   // never observed a real ACC-ON edge here
            long age = System.currentTimeMillis() - stamp;
            return age >= 0 && age <= ACC_ON_TRUST_WINDOW_MS;
        } catch (Throwable t) {
            // Monitor unavailable in this process — fall through and darken, the
            // pre-existing behaviour for the parked keep-alive.
            return false;
        }
    }

    /**
     * Stamp that a REAL ACC-ON edge was just observed in this process, making
     * {@link #isAccOnForSuppression} honour it for the next
     * {@link #ACC_ON_TRUST_WINDOW_MS}. Called from the ACC-ON handlers.
     */
    public static void noteAccOnObserved() {
        accOnObservedAtMs = System.currentTimeMillis();
    }

    private static volatile long accOnObservedAtMs;

    /**
     * How long a observed ACC-ON edge suppresses darkening. Generous enough to
     * cover the whole ACC-ON teardown (deterrent cleanup, sentry teardown, the
     * ~3.3 s wake retry loop) and the drive-away, but bounded so a stale flag can
     * never disable parked darkening for a whole park.
     */
    private static final long ACC_ON_TRUST_WINDOW_MS = 120_000L;

    /** True for the only values this vendor API is known to return. */
    private static boolean isPlausibleStatus(int status) {
        return status == SCREEN_STATUS_OFF
            || status == SCREEN_STATUS_ON
            || status == SCREEN_STATUS_UNKNOWN;
    }

    /**
     * Last logged (want, status) pair, so the 10 s keep-alive does not emit
     * ~8600 identical lines per park. Only transitions and failures are logged —
     * which is also what makes the log useful for answering "did the panel get
     * relit, and by what?". Not synchronized: a duplicated line from a race is
     * harmless, and this is only ever written from the panel paths.
     */
    private static volatile int lastLoggedStatus = Integer.MIN_VALUE;
    private static volatile boolean lastLoggedWant;

    // Cross-process stand-in for the screen status, used ONLY on firmware where
    // getPowerScreenStatus() is absent. Stored in unified config rather than a
    // static because the panel is a single global device while the processes that
    // change it are three (acc_sentry darkens; byd_cam_daemon's deterrent and the
    // user screen-on relight). A per-process latch makes acc_sentry believe the
    // panel is still dark after another process lit it, and it then never
    // re-darkens for the rest of the park.
    private static final String KEY_UNVERIFIED_WANT = "panelUnverifiedWant";
    private static final int UNVERIFIED_NONE = 0;
    private static final int UNVERIFIED_OFF = 1;
    private static final int UNVERIFIED_ON = 2;

    private static int readUnverifiedWant() {
        try {
            JSONObject s = UnifiedConfigManager.loadConfig().optJSONObject(SECTION_SURVEILLANCE);
            if (s == null) return UNVERIFIED_NONE;
            return s.optInt(KEY_UNVERIFIED_WANT, UNVERIFIED_NONE);
        } catch (Throwable t) {
            return UNVERIFIED_NONE;
        }
    }

    private static void writeUnverifiedWant(boolean want) {
        try {
            UnifiedConfigManager.updateValues(SECTION_SURVEILLANCE,
                java.util.Collections.singletonMap(KEY_UNVERIFIED_WANT,
                    want ? UNVERIFIED_ON : UNVERIFIED_OFF));
        } catch (Throwable t) {
            logger.debug("Failed to publish unverified panel state: " + t.getMessage());
        }
    }

    /**
     * Declare that something OUTSIDE this class just changed the panel, so the
     * unverifiable-firmware record of "what was last asked for" no longer
     * describes reality.
     *
     * <p>Load-bearing on firmware with no {@code getPowerScreenStatus()}. The
     * keep-alive darkens the panel, then {@code performSystemWakeUp()} (every 8
     * min), a deterrent wake, or a user screen-on RELIGHTS it. With no status API
     * that relight is unobservable, so the caller that caused it must say so —
     * otherwise the next re-darken is skipped as redundant and the panel stays
     * lit. Published cross-process for the same reason the latch itself is.
     *
     * <p>Cheap no-op on firmware that CAN report status: there the latch is never
     * consulted and {@code apply()} reads the truth directly.
     */
    public static void notePanelStateChangedExternally() {
        try {
            // Already cleared → skip the write. This is a full-file config
            // read-modify-write that bumps the mtime and therefore invalidates the
            // mtime-gated loadConfig() cache in EVERY daemon process; doing it when
            // nothing changes would tax subsystems that have nothing to do with the
            // panel. The read is the cheap mtime-cached one.
            if (readUnverifiedWant() == UNVERIFIED_NONE) return;
            UnifiedConfigManager.updateValues(SECTION_SURVEILLANCE,
                java.util.Collections.singletonMap(KEY_UNVERIFIED_WANT, UNVERIFIED_NONE));
        } catch (Throwable t) {
            logger.debug("Failed to clear unverified panel state: " + t.getMessage());
        }
    }

    /**
     * Cheap "is there nothing to do?" probe for the parked keep-alive, true when
     * a {@link #turnOff} right now would be a no-op.
     *
     * <p>Exists so the 10 s tick can skip the EXPENSIVE guards (the
     * forceReload-backed deterrent confirm and the override read) in the
     * overwhelmingly common case where the panel is already dark. Costs one
     * cached-reflection binder read, or zero on firmware with no status API.
     *
     * <p>Covers both firmware classes, which is the point: a plain
     * {@link #isPanelOff} would answer false forever on units without
     * {@code getPowerScreenStatus()} (status is permanently -1, never 0), so
     * those units would pay the expensive guards on every tick of every park.
     * There we fall back to the same latch {@link #apply} uses.
     */
    public static boolean isAlreadyDark(Context ctx) {
        if (ctx == null) return false;
        // Panel control proven impossible on this build — report "nothing to do"
        // so the caller skips its expensive gate chain instead of retrying an
        // operation that can never succeed.
        if (tiersKnownUnavailable) return true;
        int status = screenStatus(ctx);
        if (status == SCREEN_STATUS_UNKNOWN) {
            // Unverifiable firmware: "dark" means dark was the last thing ANY
            // process asked for and nothing has since declared a change. Reading
            // the shared record (not a static) is what stops acc_sentry from
            // believing the panel is dark after byd_cam_daemon lit it.
            return readUnverifiedWant() == UNVERIFIED_OFF;
        }
        return status == SCREEN_STATUS_OFF;
    }

    private static void logStateChange(boolean want, int status) {
        if (status == lastLoggedStatus && want == lastLoggedWant) return;
        lastLoggedStatus = status;
        lastLoggedWant = want;
        final int target = want ? SCREEN_STATUS_ON : SCREEN_STATUS_OFF;
        String outcome = (status == target) ? "verified"
            : (status == SCREEN_STATUS_UNKNOWN) ? "unverifiable (no getPowerScreenStatus)"
            : "FAILED — still " + (want ? "off" : "on") + " after both tiers";
        logger.info((want ? "turnOn" : "turnOff") + ": backlight "
            + (want ? "ON" : "OFF") + " — " + outcome);
    }

    /**
     * Suppress the parked auto-darkening for {@link #USER_OVERRIDE_GRACE_MS}
     * because the user explicitly asked for the screen ON.
     *
     * <p>Without this, the three user-facing screen controls (HTTP
     * {@code /api/vehicle/media}, automation actions, steering-wheel key
     * bindings — all in byd_cam_daemon) were effectively inoperative while
     * parked on dilink4: the panel would light, then acc_sentry_daemon's next
     * keep-alive tick would read status==ON and darken it again within 10 s,
     * forever. The user cannot win a race against a 10 s loop in another
     * process.
     *
     * <p>The grace is written to unified config because the deciding process is
     * NOT the one being overridden — acc_sentry_daemon reads it. Kept short so a
     * user who lights the screen and walks away doesn't leave the panel burning
     * all night; the park auto-darkens again once it lapses.
     */
    public static void requestUserOverride() {
        try {
            UnifiedConfigManager.updateValues(SECTION_SURVEILLANCE,
                java.util.Collections.singletonMap(
                    KEY_USER_OVERRIDE_UNTIL,
                    System.currentTimeMillis() + USER_OVERRIDE_GRACE_MS));
            logger.info("Panel auto-darkening suppressed for "
                + (USER_OVERRIDE_GRACE_MS / 1000) + "s (explicit user screen command)");
        } catch (Throwable t) {
            logger.debug("Failed to publish user override: " + t.getMessage());
        }
    }

    /**
     * True while an explicit user screen-on request should hold off auto-darkening.
     *
     * <p>Uses {@code forceReload()} rather than the mtime-cached read for the same
     * reason the deterrent gate beside it does: the override is written by a
     * DIFFERENT process immediately before the user expects the screen to stay on,
     * and ext4 mtime granularity is 1 s. A cached read stamped earlier in the same
     * wall-clock second would miss the write and darken the panel within 10 s of
     * the keypress — the exact symptom this override exists to prevent.
     *
     * <p>Only reached when the panel is NOT already dark (the keep-alive gate
     * short-circuits on {@link #isAlreadyDark} first), so the steady-state park
     * does not pay the re-parse.
     */
    public static boolean isUserOverrideActive() {
        try {
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject(SECTION_SURVEILLANCE);
            if (s == null) return false;
            long until = s.optLong(KEY_USER_OVERRIDE_UNTIL, 0L);
            long now = System.currentTimeMillis();
            // Upper-bound the deadline: a stale far-future value (unclean teardown
            // plus a backward RTC step, a documented condition on these head units)
            // must never disable parked darkening permanently.
            return until > now && until <= now + USER_OVERRIDE_GRACE_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String SECTION_SURVEILLANCE = "surveillance";
    private static final String KEY_USER_OVERRIDE_UNTIL = "panelUserOverrideUntilMs";
    private static final long USER_OVERRIDE_GRACE_MS = 60_000L;

    /**
     * True when the panel is currently off. Used by callers that need to decide
     * whether to wake it (e.g. the motion deterrent, which must not render onto
     * a dark screen). {@code SCREEN_STATUS_UNKNOWN} reports false — when we
     * can't tell, assume the panel is usable rather than force a wake.
     */
    public static boolean isPanelOff(Context ctx) {
        return ctx != null && screenStatus(ctx) == SCREEN_STATUS_OFF;
    }

    // ── Tier 1: PowerManager.TurnBacklightOn/Off(long) ─────────────────────

    /**
     * The reference app probes BOTH capitalisations and picks whichever is
     * declared, because the method name differs across BYD firmware builds
     * (PascalCase on some, lowerCamel on others).
     */
    // Cached tier-1 Methods, one per direction. Resolving these also removes the
    // getDeclaredMethods() call below from the repeat path — it returns a fresh
    // copy of EVERY Method on PowerManager (~150 on vendor firmware) per probe.
    private static volatile Method tier1OnMethod;
    private static volatile Method tier1OffMethod;
    private static volatile boolean tier1OnResolved;
    private static volatile boolean tier1OffResolved;

    /** @return true only if the vendor method was actually invoked. */
    private static boolean setBacklightViaPowerManager(Context ctx, boolean on) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;

            // Read the RESOLVED FLAG FIRST, then the Method. The writer publishes
            // in the opposite order (method, then flag), so reading value-then-flag
            // admits an interleaving where a racing thread sees resolved=true with
            // a stale null method and silently skips the backlight write entirely.
            // Flag-first makes that impossible: if we observe resolved==true, the
            // method write already happened-before it.
            boolean resolved = on ? tier1OnResolved : tier1OffResolved;
            Method m = on ? tier1OnMethod : tier1OffMethod;
            if (!resolved) {
                // Probe BOTH capitalisations — the name differs across BYD builds
                // (PascalCase on some, lowerCamel on others).
                String[] names = on
                    ? new String[]{"TurnBacklightOn", "turnBacklightOn"}
                    : new String[]{"TurnBacklightOff", "turnBacklightOff"};
                for (String name : names) {
                    if (!hasDeclaredMethod(PowerManager.class, name, long.class)) continue;
                    m = PowerManager.class.getMethod(name, long.class);
                    logger.info("backlight tier1 resolved: PowerManager." + name);
                    break;
                }
                if (on) { tier1OnMethod = m; tier1OnResolved = true; }
                else    { tier1OffMethod = m; tier1OffResolved = true; }
                if (m == null) {
                    logger.info("backlight tier1: no TurnBacklight" + (on ? "On" : "Off")
                        + "(long) on PowerManager — relying on tier 2");
                }
            }
            if (m == null) return false;
            m.invoke(pm, SystemClock.uptimeMillis());
            return true;
        } catch (Throwable t) {
            logger.debug("backlight tier1 failed: " + t.getMessage());
            return false;
        }
    }

    // ── Tier 2: mService.TurnBacklightOn/OffWithLock(IBinder[, String]) ────

    /**
     * The tier we were missing. The reference app reaches through
     * {@code PowerManager.mService} (the {@code IPowerManager} binder proxy) and
     * calls the vendor's lock-holding variants:
     * {@code TurnBacklightOnWithLock(IBinder token, String who)} /
     * {@code TurnBacklightOffWithLock(IBinder token)} — passing a null token.
     *
     * <p>This is what makes the panel STAY off on firmware where the plain
     * {@code turnBacklightOff(long)} is accepted and then immediately undone by
     * the platform's own display policy.
     */
    /** @return true only if a vendor WithLock call was actually invoked. */
    private static boolean setBacklightWithLock(Context ctx, boolean on) {
        // Never acquire the OFF lock twice without an intervening release. The
        // no-retry logic below already refuses to stack locks WITHIN one call for
        // exactly this reason; the same hazard exists ACROSS calls, because a park
        // re-darkens on every 8-min performSystemWakeUp() and after every deterrent
        // (~75 acquires in a 10-hour park) while ACC-ON issues exactly ONE release.
        // On a refcounting vendor implementation that arithmetic leaves the panel
        // dark with no recovery: linkToDeath never fires (the process is alive),
        // boot recovery never runs (no reboot), and the legacy setBacklightState
        // retry is tier-1-only — the tier that by hypothesis does not stick on this
        // firmware. Reporting true is honest: the lock we need IS held.
        if (!on && tier2LockHeld) {
            logger.debug("backlight tier2: OFF lock already held — skipping re-acquire");
            return true;
        }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            Object service = powerManagerService(pm);
            if (service == null) {
                logger.debug("backlight tier2: PowerManager.mService unavailable");
                return false;
            }
            Method m;
            try {
                m = on
                    ? service.getClass().getMethod(
                        "TurnBacklightOnWithLock", IBinder.class, String.class)
                    : service.getClass().getMethod(
                        "TurnBacklightOffWithLock", IBinder.class);
            } catch (NoSuchMethodException absent) {
                // Proven ABSENT (not a runtime failure) — this is the only signal
                // allowed to contribute to the tiersKnownUnavailable latch.
                tier2KnownAbsent = true;
                logger.debug("backlight tier2: TurnBacklight" + (on ? "On" : "Off")
                    + "WithLock not present on this build");
                return false;
            }

            if (on) {
                // RELEASE. A lock keyed to token X is not released by token Y, and
                // the process doing the release is frequently NOT the one that
                // acquired: acc_sentry_daemon darkens the panel, but the ACC-ON
                // wake and the deterrent wake run in byd_cam_daemon, which has its
                // own LOCK_TOKEN and no idea which token the other process used
                // (tier2TokenWasNull is per-process). Passing only our own token
                // there would leave the real lock held — panel stuck dark for the
                // returning driver, or an invisible intruder warning.
                //
                // So try EVERY token variant and stop at the first that doesn't
                // throw, preferring the one this process is known to have used.
                // Releasing a lock we don't hold is harmless (the vendor either
                // no-ops or throws, both caught); failing to release one that IS
                // held is the unrecoverable outcome.
                boolean released = false;
                Object[] tokens = tier2TokenWasNull
                    ? new Object[]{null, LOCK_TOKEN}
                    : new Object[]{LOCK_TOKEN, null};
                Throwable last = null;
                for (Object token : tokens) {
                    try {
                        m.invoke(service, token, TAG);
                        released = true;
                        break;
                    } catch (Throwable t) {
                        last = t;
                    }
                }
                if (!released) {
                    logger.debug("backlight tier2 release failed for all tokens: " + last);
                    return false;
                }
                tier2LockHeld = false;
            } else {
                try {
                    m.invoke(service, LOCK_TOKEN);
                    tier2TokenWasNull = false;
                } catch (Throwable withToken) {
                    // Some firmware validates the token against its own registry
                    // and rejects an unknown one. Fall back to the reference app's
                    // exact call (null token) rather than lose tier 2 entirely.
                    //
                    // We do NOT retry blind: a throw here may mean the transaction
                    // already landed and registered a lock, in which case a second
                    // acquire would STACK vendor locks and the single release below
                    // would only undo one — panel stuck dark. So only retry when
                    // the throw is argument/binding rejection, which cannot have
                    // applied anything.
                    if (!isArgumentRejection(withToken)) {
                        logger.debug("backlight tier2 off failed after possible apply ("
                            + withToken + ") — not retrying to avoid stacking locks");
                        // Two DIFFERENT facts, deliberately not conflated:
                        //  - tier2LockHeld = true: the call may have landed, so the
                        //    release path must stay reachable or a lock we possibly
                        //    took could never be undone.
                        //  - return false: we did NOT confirm the panel was
                        //    asserted. apply() uses the return value to decide
                        //    whether to LATCH on unverifiable firmware; latching on
                        //    a write that probably failed would suppress every
                        //    future retry and leave the panel lit for the whole
                        //    park behind one benign-looking log line.
                        tier2LockHeld = true;
                        return false;
                    }
                    logger.debug("backlight tier2 token rejected — retrying with null token");
                    m.invoke(service, (Object) null);
                    tier2TokenWasNull = true;
                }
                tier2LockHeld = true;
            }
            logger.info("backlight tier2: TurnBacklight" + (on ? "On" : "Off") + "WithLock invoked"
                + (on ? "" : " (token=" + (tier2TokenWasNull ? "null" : "binder") + ")"));
            return true;
        } catch (Throwable t) {
            logger.debug("backlight tier2 (WithLock) failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Process-lifetime token passed to the {@code *WithLock} calls.
     *
     * <p>The reference app passes {@code null} here. We deliberately do NOT:
     * vendor {@code WithLock} APIs conventionally key the lock to the token and
     * {@code linkToDeath} it, so that a caller which dies has its lock released
     * automatically. A null token opts out of that safety net — if this daemon
     * is SIGKILLed while holding the backlight OFF lock, the vendor service can
     * keep the panel dark with no death recipient to release it, and the only
     * remaining cure is an explicit {@code TurnBacklightOnWithLock} from a live
     * daemon. That is the one failure mode we must never risk, because a black
     * panel is uninteractable and the user cannot recover from it.
     *
     * <p>A real {@link android.os.Binder} costs nothing, is stable for the life
     * of the process, and gives the vendor service something to death-link. If
     * a firmware build rejects a non-null token, the invoke throws and is caught
     * in {@link #setBacklightWithLock}, which then falls back to the null token
     * — so we keep reference-app parity as the fallback rather than the default.
     */
    private static final IBinder LOCK_TOKEN = new android.os.Binder();

    /**
     * Latched true once BOTH tiers have been proven uninvokable on this build
     * (no {@code TurnBacklight*(long)} on PowerManager AND no {@code *WithLock}
     * reachable through {@code mService}). A capability fact, not panel state, so
     * it never needs clearing — it stops the keep-alive from re-attempting two
     * failing reflective invokes, and re-running its expensive gate chain, every
     * 10 s for the rest of every park.
     */
    private static volatile boolean tiersKnownUnavailable;

    /**
     * True when the tier-1 method for this direction has been RESOLVED and proven
     * absent (not merely failed at invoke time). Distinguishing the two is what
     * keeps a transient failure from permanently disabling panel control.
     */
    private static boolean tier1KnownAbsent(boolean on) {
        return on ? (tier1OnResolved && tier1OnMethod == null)
                  : (tier1OffResolved && tier1OffMethod == null);
    }

    /**
     * Latched when the tier-2 vendor methods are proven unreachable — the
     * {@code mService} field or the {@code *WithLock} methods do not exist on this
     * build. Set only by {@link #setBacklightWithLock}'s absence paths, never by an
     * invoke that threw.
     */
    private static volatile boolean tier2KnownAbsent;

    /**
     * True once a tier-2 {@code TurnBacklightOffWithLock} has been issued from
     * this process and not yet released. Gates whether {@link #turnOn} may use
     * tier 2, so the release is always reachable by whoever took the lock even if
     * {@code cameraMode} changed underneath us mid-park.
     */
    private static volatile boolean tier2LockHeld;

    /**
     * Which token the tier-2 acquire actually accepted. The release MUST match:
     * a vendor lock keyed to the null token is not released by passing our Binder
     * (or vice versa), and an unreleased OFF lock means a permanently dark panel.
     */
    private static volatile boolean tier2TokenWasNull;

    /**
     * True when a reflective invoke failed in a way that proves the vendor call
     * never executed — argument/binding rejection rather than a failure inside
     * the service. Only such failures are safe to retry with a different token;
     * retrying anything else risks stacking vendor backlight locks.
     */
    private static boolean isArgumentRejection(Throwable t) {
        Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException)
            ? t.getCause() : t;
        return cause instanceof IllegalArgumentException
            || cause instanceof NullPointerException
            || t instanceof IllegalArgumentException;
    }

    /** Cached {@code PowerManager.mService} binder proxy (resolved once, on success). */
    private static volatile Object cachedService;
    private static volatile boolean serviceResolved;

    private static Object powerManagerService(PowerManager pm) {
        if (serviceResolved) return cachedService;
        synchronized (StealthPanel.class) {
            if (serviceResolved) return cachedService;
            Object svc = null;
            try {
                Field f = pm.getClass().getDeclaredField("mService");
                f.setAccessible(true);
                svc = f.get(pm);
            } catch (Throwable t) {
                logger.debug("PowerManager.mService reflection failed: " + t.getMessage());
            }
            // Latch ONLY on success. Caching a null would permanently disable
            // tier 2 for this process after a single transient failure (e.g. a
            // hidden-API check that trips on first use), silently degrading
            // every later turnOff to the tier-1-only path that this whole class
            // exists because it does not reliably stick.
            if (svc != null) {
                cachedService = svc;
                serviceResolved = true;
            }
            return svc;
        }
    }

    // ── Verification: PowerManager.getPowerScreenStatus() ──────────────────

    /**
     * 0 = screen off, 1 = screen on, -1 = unknown (method absent or threw).
     * The reference app calls this before and after every transition; it is the
     * only reliable signal that a backlight write actually landed.
     */
    // Cached getPowerScreenStatus Method. Mirrors the REFLECTION CACHES block in
    // AccSentryDaemon (see its comment: the 10 s keep-alive cadence made repeated
    // Class.getMethod() linear method-table scans measurable, and getMethod
    // allocates a fresh Method copy on every call). Same volatile publication and
    // same accepted idempotent double-resolve race. Separate resolved/failed
    // flags so firmware WITHOUT the method stops retrying the lookup ~8600 times
    // a night for an answer that cannot change.
    private static volatile Method pmScreenStatusMethod;
    private static volatile boolean pmScreenStatusResolved;
    private static volatile boolean pmScreenStatusFailed;

    private static int screenStatus(Context ctx) {
        if (pmScreenStatusFailed) return SCREEN_STATUS_UNKNOWN;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return SCREEN_STATUS_UNKNOWN;
            if (!pmScreenStatusResolved) {
                try {
                    pmScreenStatusMethod = PowerManager.class.getMethod("getPowerScreenStatus");
                    pmScreenStatusResolved = true;
                } catch (Throwable t) {
                    pmScreenStatusFailed = true;
                    logger.info("getPowerScreenStatus absent on this firmware — "
                        + "backlight state cannot be verified; writes will be trusted");
                    return SCREEN_STATUS_UNKNOWN;
                }
            }
            Object v = pmScreenStatusMethod.invoke(pm);
            if (v instanceof Integer) return (Integer) v;
            return SCREEN_STATUS_UNKNOWN;
        } catch (Throwable t) {
            return SCREEN_STATUS_UNKNOWN;
        }
    }

    private static boolean hasDeclaredMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (java.util.Arrays.equals(m.getParameterTypes(), params)) return true;
            }
        } catch (Throwable ignored) {
            // Fall through — treat as absent.
        }
        return false;
    }
}
