package com.overdrive.app.byd;

import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Run the AC for N minutes, then switch it off" — a single, cancellable, re-armable
 * shutdown timer for the climate system.
 *
 * <p>Why a scheduler and not the existing {@code pause} action: automation actions run
 * sequentially on the ONE {@link com.overdrive.app.automation.AutomationQueue} worker
 * thread, so a blocking sleep would park EVERY other automation for the whole window —
 * and {@code PauseAction} is deliberately capped at 5 minutes for exactly that reason.
 * The durations wanted here (10/15/30/45/60+ min) are an order of magnitude longer than
 * that cap, so the wait has to be scheduled off-thread.
 *
 * <p>Semantics, chosen so a repeatedly-triggered automation behaves sanely:
 * <ul>
 *   <li><b>Single timer, last-write-wins.</b> Arming replaces any pending timer rather
 *       than stacking a second one, so a rule that fires on every door-open doesn't queue
 *       ten overlapping shutdowns. The window is measured from the LATEST arm, which is
 *       what "run the AC for 30 minutes" means to a user who just re-triggered it.</li>
 *   <li><b>Turning the AC off cancels it.</b> {@link #cancel()} is called from the
 *       climate-off path, so a manual/HA/automation power-off doesn't leave a timer that
 *       later fires into an AC the user has since switched back on by hand.</li>
 *   <li><b>Fires only if the AC is still on.</b> The state is re-checked at fire time and
 *       the command is skipped when the AC already reads off, so the timer never issues a
 *       redundant HAL/cloud write (and never fights a user who just turned it off).</li>
 * </ul>
 *
 * <p>The shutdown goes through {@link VehicleCommandRouter} (CLOUD_FIRST with SDK
 * fallback), i.e. the exact same path {@code POST /api/vehicle/climate power_off} uses, so
 * there is one implementation of "turn the AC off" and the timer inherits its routing,
 * driving-safety guard and logging.
 *
 * <p><b>Deliberately NOT gated on {@code Automations.isDisabled()}</b>, unlike the event
 * publishers in {@code BydEvent}. Those gates suppress SENSING (writing signals into the shared
 * state); this timer is an already-committed physical promise — "the AC will switch itself off" —
 * and it is armable from the UI and Home Assistant, not only from an automation. Dropping it on
 * a disable-all would leave the AC running indefinitely, i.e. exactly the outcome the feature
 * exists to prevent. Use {@link #cancel()} to stop it explicitly.
 *
 * <p>The scheduler is a static daemon executor that is never shut down, matching this
 * codebase's existing pattern for long-lived timers (see {@code TimeEvent}'s scheduler): the
 * daemon owns the process lifetime, and a daemon thread cannot hold JVM exit open.
 */
public final class AcAutoOffTimer {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AcAutoOff");

    /** Upper bound on a single window. 8h covers any plausible pre-conditioning or
     *  cabin-airing case while stopping a hand-edited config from arming a timer that
     *  effectively never fires. */
    public static final int MAX_MINUTES = 480;

    /** Attempts for the off command, and the gap between them — a transient cloud/HAL refusal
     *  must not silently end the promise. Runs on the timer thread, so sleeping here is safe. */
    private static final int SHUTDOWN_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 10_000L;

    /** How long after a missed deadline it is still meaningful to switch the AC off on restart.
     *  Generous enough to cover a parked stint plus retries, short enough that a deadline from
     *  days ago can't switch off an AC the driver turned on today. */
    private static final long OVERDUE_GRACE_MS = TimeUnit.HOURS.toMillis(12);

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AcAutoOff");
                t.setDaemon(true);
                return t;
            });

    /**
     * The armed timer, or null when none is. Holds the future TOGETHER WITH the generation that
     * scheduled it so a firing timer can only ever clear its OWN state: {@link #fire} used to
     * null this unconditionally, which meant a shutdown that fired while another thread was
     * re-arming would wipe the NEW timer's reference — after which cancel() found nothing to
     * cancel and isArmed() said false, yet the orphaned future still switched the AC off later.
     */
    private static final AtomicReference<Armed> pending = new AtomicReference<>();

    /** An armed timer: its future, its generation stamp, and when it is due. Immutable. */
    private static final class Armed {
        final ScheduledFuture<?> future;
        final long generation;
        final long dueAtMs;
        Armed(ScheduledFuture<?> future, long generation, long dueAtMs) {
            this.future = future;
            this.generation = generation;
            this.dueAtMs = dueAtMs;
        }
    }

    /** Monotonic stamp handed to each scheduled task so it can recognise its own state. */
    private static final AtomicLong generations = new AtomicLong();

    /**
     * Bumped by every {@link #cancel()}. An in-flight shutdown captures this at entry and aborts if
     * it changes, which is the ONLY way to notice a cancel that lands mid-retry: {@code cancel()}
     * leaves {@code pending == null}, and that is the very same state {@link #fire} leaves behind
     * when it legitimately owns the shutdown — so "pending is null" cannot distinguish "nobody
     * cancelled" from "the user just said no". Without this, a cancel arriving during the retry
     * back-off was silently discarded and the AC switched off anyway, against the explicit request.
     */
    private static final AtomicLong cancellations = new AtomicLong();

    /**
     * Where the pending deadline is persisted (epoch millis, chmod 666 — the same shape and
     * location convention as the daemon's parked-shutdown marker).
     *
     * <p>Persistence is REQUIRED, not a nicety: this daemon deliberately kills its own process
     * ({@code CameraDaemon.shutdownInternal} → {@code Process.killProcess}), and in
     * "vehicle-ON-only" mode it does so on the ACC-off edge via {@code parkTerminate()}. A timer
     * held only in memory would therefore be destroyed at exactly the moment it matters most —
     * the car is parked, BYD parked/remote climate keeps the AC running, and nothing is left to
     * switch it off. That is the battery drain this class exists to prevent. A watchdog restart
     * or crash has the same effect.
     */
    private static final String STATE_PATH = ScratchPaths.path("overdrive_ac_auto_off_due");

    private AcAutoOffTimer() {}

    /**
     * Arm (or re-arm) the shutdown for {@code minutes} from now, replacing any pending
     * timer. A non-positive duration cancels instead — that is what an automation storing
     * "no auto-off" means, and it keeps the caller from having to special-case zero.
     *
     * @param minutes window length; clamped to [1, {@link #MAX_MINUTES}]
     * @return true if a timer is now armed, false if this cancelled instead
     */
    public static boolean arm(int minutes) {
        if (minutes <= 0) {
            cancel();
            return false;
        }
        int m = Math.min(MAX_MINUTES, minutes);
        long generation = generations.incrementAndGet();
        ScheduledFuture<?> next;
        try {
            next = scheduler.schedule(() -> fire(generation), m, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Scheduler rejected the task (daemon tearing down) — report honestly rather
            // than leaving the caller believing the AC will switch itself off.
            logger.warn("Could not arm AC auto-off: " + e.getMessage());
            return false;
        }
        long dueAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(m);
        Armed armed = new Armed(next, generation, dueAt);
        // Publish the new timer only after it is scheduled, so a failure above can never leave
        // the AC with no timer while the caller was told one exists. Then stop the previous one.
        Armed previous = pending.getAndSet(armed);
        if (previous != null) previous.future.cancel(false);
        persistDueAt(dueAt);
        logger.info("AC auto-off armed for " + m + " minute(s)"
                + (previous != null ? " (replaced a pending timer)" : ""));
        return true;
    }

    /** Cancel any pending shutdown. Safe to call when nothing is armed. */
    public static void cancel() {
        Armed previous = pending.getAndSet(null);
        // Stamp the cancel BEFORE anything else, so a shutdown already in its retry back-off sees
        // it on the next attempt even though there is no pending timer left to look at.
        cancellations.incrementAndGet();
        clearPersisted();
        if (previous != null && previous.future.cancel(false)) {
            logger.info("AC auto-off cancelled");
        }
    }

    /**
     * Re-arm a window that was still pending when the process last exited. Call once during
     * daemon startup, BEFORE anything else can arm a timer.
     *
     * <p>A RECENTLY overdue deadline fires immediately rather than being dropped: the car may have
     * been parked with the AC running for the whole gap, which is precisely the case the window was
     * meant to bound. "Recently" is bounded by {@link #OVERDUE_GRACE_MS} — a deadline that lapsed
     * days ago says nothing about the AC's state today, and acting on it would switch off an AC the
     * driver turned on this morning. A deadline further in the FUTURE than {@link #MAX_MINUTES} is
     * discarded as corrupt or the result of a clock jump, instead of arming a timer that would
     * never fire.
     */
    public static void restore() {
        long dueAt = readPersisted();
        if (dueAt <= 0L) return;
        long remainingMs = dueAt - System.currentTimeMillis();
        if (remainingMs > TimeUnit.MINUTES.toMillis(MAX_MINUTES)) {
            logger.warn("Ignoring persisted AC auto-off deadline " + remainingMs
                    + "ms out (beyond the " + MAX_MINUTES + "min ceiling) — discarding");
            clearPersisted();
            return;
        }
        if (remainingMs <= 0L) {
            if (-remainingMs > OVERDUE_GRACE_MS) {
                logger.warn("Persisted AC auto-off deadline lapsed " + (-remainingMs / 60000)
                        + " minute(s) ago — too stale to act on, discarding");
                clearPersisted();
                return;
            }
            logger.info("Persisted AC auto-off deadline already passed — switching the AC off now");
            // The marker is NOT cleared here: sendShutdown clears it once the AC is actually off,
            // so a failure (or another kill mid-retry) is picked up again on the next start.
            scheduler.execute(() -> sendShutdown("restore-overdue"));
            return;
        }
        // Re-arm on the ORIGINAL deadline (not a fresh full window), so a restart cannot extend
        // the window the user actually asked for.
        long generation = generations.incrementAndGet();
        try {
            ScheduledFuture<?> next = scheduler.schedule(
                    () -> fire(generation), remainingMs, TimeUnit.MILLISECONDS);
            // CAS from "nothing armed", never a bare set(). The HTTP server is already accepting
            // requests by the time startup reaches here, so an arm() or cancel() can land between
            // this method's readPersisted() and this publish. A blind set would then resurrect a
            // just-cancelled shutdown (with no marker left to explain it) or replace a newer
            // window with the older persisted deadline. Losing the CAS means a live owner already
            // exists and knows better than this restored snapshot.
            if (pending.compareAndSet(null, new Armed(next, generation, dueAt))) {
                logger.info("Restored AC auto-off: " + (remainingMs / 60000) + " minute(s) remaining");
            } else {
                next.cancel(false);
                logger.info("Skipped AC auto-off restore — a timer was already armed or cancelled"
                        + " during startup");
            }
        } catch (Exception e) {
            logger.warn("Could not restore AC auto-off: " + e.getMessage());
        }
    }

    private static void persistDueAt(long dueAtMs) {
        try (java.io.FileWriter fw = new java.io.FileWriter(STATE_PATH)) {
            fw.write(String.valueOf(dueAtMs));
        } catch (Exception e) {
            // Non-fatal: the in-memory timer still works for this process lifetime. Warn, because
            // it means the window will NOT survive the ACC-off self-terminate.
            logger.warn("Could not persist AC auto-off deadline: " + e.getMessage());
            return;
        }
        try {
            java.io.File f = new java.io.File(STATE_PATH);
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (Exception ignored) {}
    }

    private static void clearPersisted() {
        try {
            java.io.File f = new java.io.File(STATE_PATH);
            if (!f.exists()) return;
            if (f.delete()) return;
            // Could not unlink (permissions) — blank it so restore() can't resurrect a
            // cancelled window on the next boot.
            try (java.io.FileWriter fw = new java.io.FileWriter(STATE_PATH)) { fw.write("0"); }
        } catch (Exception e) {
            // BOTH the unlink and the blanking failed, so the marker survives: restore() would
            // re-arm (or immediately fire) a window the user just cancelled. Warn rather than
            // swallow — silence here is exactly how a cancelled shutdown comes back from the dead,
            // and it is the same standard persistDueAt holds itself to.
            logger.warn("Could not clear the persisted AC auto-off deadline (" + STATE_PATH
                    + ") — a cancelled window may be restored after a restart: " + e.getMessage());
        }
    }

    /** The persisted deadline in epoch millis, or 0 when absent/unreadable/blank. */
    private static long readPersisted() {
        try {
            java.io.File f = new java.io.File(STATE_PATH);
            if (!f.isFile()) return 0L;
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return 0L;
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Whether a shutdown is currently pending. */
    public static boolean isArmed() {
        Armed a = pending.get();
        return a != null && !a.future.isDone();
    }

    /** Seconds until the pending shutdown, or -1 when nothing is armed. */
    public static long secondsRemaining() {
        Armed a = pending.get();
        // Read the snapshot ONCE: re-reading via isArmed() could observe a different (or null)
        // timer between the check and the due-time read, reporting a countdown that belongs to
        // a timer that no longer exists.
        if (a == null || a.future.isDone()) return -1;
        long remainingMs = a.dueAtMs - System.currentTimeMillis();
        return remainingMs <= 0 ? 0 : TimeUnit.MILLISECONDS.toSeconds(remainingMs);
    }

    /**
     * The scheduled shutdown. Runs on the dedicated timer thread — never the automation
     * worker or a telemetry thread — so a slow cloud round-trip here delays nothing else.
     */
    private static void fire(long generation) {
        // Clear ONLY our own state. A plain set(null) would wipe a timer armed by another thread
        // in the moment before this task started running, orphaning it: cancel() would then find
        // nothing, isArmed() would say false, and that orphan would still switch the AC off.
        // compareAndSet against our own generation makes a stale task a no-op instead.
        Armed self = pending.get();
        if (self == null) {
            // cancel() ran while this task was already past the point of being cancellable
            // (cancel(false) cannot stop a task the executor has started). The user explicitly
            // said "no shutdown", so honour that rather than switching the AC off anyway.
            logger.info("AC auto-off was cancelled just as it came due — not switching off");
            return;
        }
        if (self.generation != generation) {
            // A NEWER timer is armed (the user extended the window after this task became
            // un-cancellable). That newer timer owns the shutdown — drop this one silently
            // rather than switching the AC off early.
            logger.info("AC auto-off superseded by a newer timer — skipping this shutdown");
            return;
        }
        // The CAS RESULT decides whether we own this shutdown. Ignoring it would re-open both
        // holes the checks above close, just through a narrower window: if cancel() or arm() swaps
        // `pending` between the get() and here, the CAS fails and this stale task would otherwise
        // fall through and switch the AC off anyway — against an explicit cancel, or early and
        // clearing the NEW timer's persisted deadline.
        if (!pending.compareAndSet(self, null)) {
            logger.info("AC auto-off state changed while coming due — leaving the shutdown to the"
                    + " current owner");
            return;
        }
        // NOTE: the persisted deadline is deliberately NOT cleared here — sendShutdown() clears it
        // only once the AC is actually off. See sendShutdown.
        sendShutdown("timer");
    }

    /**
     * Issue the AC-off command, with a bounded retry.
     *
     * <p>A single failed attempt used to end the whole promise: the result was only interpolated
     * into an INFO log, the state had already been cleared, and nothing re-armed — so a transient
     * cloud 5xx (or a momentary driving-safety block) left the AC running indefinitely while
     * {@code isArmed()} reported false. Retrying a few times, and logging a FAILURE at warn level
     * when they are exhausted, keeps the class's own standard: report honestly rather than leave
     * the caller believing the AC will switch itself off.
     *
     * <p>OWNS clearing the persisted deadline, and clears it only on an outcome that means the AC
     * is off (SUCCESS, or already-off). Clearing it up-front would defeat the persistence layer in
     * the one case it exists for: the window expires while driving, the command returns
     * BLOCKED_DRIVING, and the daemon self-terminates during the retry sleep — with the marker
     * already gone, the restart would find nothing and the AC would run indefinitely. Leaving the
     * marker in place means {@link #restore()} picks the (now overdue) deadline up and retries.
     */
    private static void sendShutdown(String reason) {
        // Snapshot the cancel epoch at ENTRY. Captured here rather than re-read fresh each attempt
        // because our own SUCCESS path calls back into cancel() via the router's retirement hook —
        // comparing against a live read would make a successful shutdown look like it had been
        // cancelled. Only a cancel that arrives AFTER this point changes the value we compare to.
        final long cancelEpoch = cancellations.get();
        // Skip when the AC is already off: the user (or another rule) got there first, and
        // re-issuing the command would be a pointless HAL/cloud write. UNAVAILABLE (state
        // unknown, e.g. the AC device didn't answer) still proceeds — the whole point of the
        // timer is to guarantee the AC does not stay on, so an unknown state must fail SAFE by
        // switching off rather than silently doing nothing.
        if (isAcKnownOff()) {
            logger.info("AC auto-off due (" + reason + "), but the AC already reads off — skipping");
            // Only retire the marker if no NEWER window has been armed meanwhile. Without this
            // ownership test — the same one the retry loop makes — the restore-overdue task could
            // delete a marker that now belongs to a freshly armed timer (arm() landing between
            // restore()'s read and this task's start), silently costing that new window its
            // crash/parkTerminate durability while it still looked armed in memory.
            if (pending.get() == null) clearPersisted();
            return;
        }
        for (int attempt = 1; attempt <= SHUTDOWN_ATTEMPTS; attempt++) {
            // Ownership is re-checked before EVERY attempt, including the FIRST. The retry window
            // is up to ~20s of wall clock, and a user can turn the AC back on with a fresh window
            // inside it — a later attempt would then switch off an AC that is deliberately running,
            // while the new timer still shows a live countdown. A newly-armed timer means someone
            // else owns the AC now, so abandon this shutdown.
            //
            // Checking on attempt 1 matters for the restore-overdue path specifically: unlike
            // fire(), it does not earn ownership through a CAS (there is no future to publish for a
            // deadline that has already passed), so without this an arm() landing between
            // restore()'s readPersisted() and this task's first attempt would have its brand-new
            // window switched off immediately — and then retired by the router's cancel hook.
            // fire() already CAS-cleared `pending` before calling in, so this is a no-op there.
            //
            // An explicit cancel is checked separately (see `cancellations`): it leaves pending null
            // — indistinguishable from normal ownership — so only the epoch reveals it. Honour it on
            // EVERY attempt, including the first, since a cancel can land between fire()'s CAS and
            // this loop.
            if (cancellations.get() != cancelEpoch) {
                logger.info("AC auto-off (" + reason + ") abandoned — cancelled while the shutdown"
                        + " was in flight, so the AC is left on as requested");
                return;
            }
            if (pending.get() != null) {
                logger.info("AC auto-off (" + reason + ") abandoned — a newer timer is armed, so the"
                        + " AC is wanted on");
                return;
            }
            if (attempt > 1 && isAcKnownOff()) {
                logger.info("AC auto-off (" + reason + ") no longer needed — the AC now reads off");
                clearPersisted();
                return;
            }
            try {
                VehicleCommandRouter.CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.ClimateOffCommand());
                if (r.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                    logger.info("AC auto-off (" + reason + ") succeeded on attempt " + attempt
                            + " path=" + r.path);
                    clearPersisted();   // the AC is off — the promise is discharged
                    return;
                }
                logger.warn("AC auto-off (" + reason + ") attempt " + attempt + "/"
                        + SHUTDOWN_ATTEMPTS + " did not succeed: " + r.outcome + " path=" + r.path);
                // NOT_SUPPORTED / AUTH_REQUIRED are terminal — retrying cannot change the answer,
                // and neither can a later restart, so drop the marker rather than re-attempting
                // this same doomed shutdown on every boot from now on.
                if (r.outcome == VehicleCommandRouter.Outcome.NOT_SUPPORTED
                        || r.outcome == VehicleCommandRouter.Outcome.AUTH_REQUIRED) {
                    clearPersisted();
                    break;
                }
            } catch (Throwable t) {
                // Never let a failure kill the scheduler thread — a future arm() must still work.
                logger.warn("AC auto-off (" + reason + ") attempt " + attempt + " threw: " + t.getMessage());
            }
            if (attempt < SHUTDOWN_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        logger.warn("AC auto-off (" + reason + ") FAILED after " + SHUTDOWN_ATTEMPTS
                + " attempt(s) — the AC may still be running");
    }

    /**
     * True only when the AC is DEFINITELY off. An unknown/unavailable reading returns
     * false so {@link #sendShutdown(String)} still sends the off command (fail-safe, see caller).
     */
    private static boolean isAcKnownOff() {
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            if (collector == null) return false;
            BydVehicleData d = collector.getData();
            if (d == null) return false;
            if (d.acStartState == BydVehicleData.UNAVAILABLE) return false;
            return d.acStartState == 0;   // BYDAutoAcDevice.AC_POWER_OFF
        } catch (Throwable t) {
            return false;
        }
    }
}
