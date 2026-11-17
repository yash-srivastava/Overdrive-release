package com.overdrive.app.parking;

import com.overdrive.app.parking.signage.SignageReader;
import com.overdrive.app.parking.signage.TextOcrBackend;
import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.DetectionBaseline;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Parking Intelligence: the session lifecycle behind "where did I park, what
 * happened around the car, who was next to me".
 *
 * <h3>Lifecycle (mirrors RoadSenseController)</h3>
 * <ul>
 *   <li>{@link #attach()} — cheap. Reads the {@code parking} config section
 *       and calls {@link #start()} ONLY if {@code parking.enabled}; installs a
 *       config listener so a toggle flip starts/stops the feature live. With
 *       the master switch off the feature owns no thread, no store, no
 *       listener: every hook site in the daemon is a null check.</li>
 *   <li>{@link #start()} — opens the H2 store, spins up the single
 *       {@code parking-worker} thread, installs itself into
 *       {@link ParkingHooks} / {@link DetectionBaseline} / the door listener.</li>
 *   <li>{@link #stop()} — the exact inverse. An open session is left open in
 *       the store and adopted (or closed as {@code recovered}) on the next
 *       start.</li>
 * </ul>
 *
 * <h3>Session model</h3>
 * ACC-off opens a session (GPS + place + safe zone + sentry state), ~90 s
 * later the four "arrived" stills are captured and the <i>Parked</i>
 * notification goes out. While parked, the detection baseline and finalized
 * sentry events feed the {@link ParkingNeighbourObserver}; the
 * {@link ParkingCropHarvester} keeps the sharpest frames of moving vehicles.
 * The first return signal (unlock in lock-arm mode, door open otherwise,
 * ACC-on as the fallback) captures the "returned" stills, closes the session
 * and publishes <i>Back at car</i>. Garage signage OCR (v2) is read right
 * after the arrived stills so the <i>Parked</i> message carries the level and
 * bay while the owner walks away; a read deferred by a recording sentry clip
 * is retried while parked and, failing that, ~2 min after the next ACC-on.
 *
 * <h3>Threading</h3>
 * Every hook returns immediately after handing a task to the worker; all
 * session state is touched on that one thread. Read-mostly fields the API
 * needs ({@link #currentSessionId()}, {@link #store()}) are volatile.
 *
 * <p>FP-neutral by construction: nothing here writes to the detector, the
 * baseline, the trigger, the recorder or the discard logic.
 */
public final class ParkingController implements ParkingHooks.Listener, DetectionBaseline.Listener {

    static final long ARRIVED_DELAY_MS = 90_000L;
    static final long ARRIVED_RETRY_MS = 60_000L;
    static final int ARRIVED_MAX_ATTEMPTS = 3;
    /** An arrival attempt this long after switch-off is a timer firing after a sleep, not an arrival. */
    static final long ARRIVED_STALE_MS = 15L * 60_000L;
    static final long TICK_MS = 1_000L;
    static final long PRUNE_INITIAL_DELAY_MS = 30_000L;
    static final long PRUNE_PERIOD_MS = 6L * 3600_000L;
    static final long SIGNAGE_AFTER_ACC_ON_MS = 120_000L;
    /** Signage skipped because a sentry event is recording: try again shortly. */
    static final long SIGNAGE_BUSY_RETRY_MS = 60_000L;
    /** drive_away: poll cadence for "has the gear left P" after ACC-on. */
    static final long GEAR_WATCH_POLL_MS = 3_000L;
    /**
     * drive_away: polls WITHOUT ANY fresh gear reading (a P reading is
     * knowledge and never counts) before GPS decides, or the close falls back
     * to acc_on (~2 min). Bounds the mode on trims with no gear feed.
     */
    static final int GEAR_WATCH_MAX_ATTEMPTS = 40;
    /**
     * drive_away: consecutive polls without a reading AFTER a P was seen before
     * the gear evidence is no longer trusted to prove "never moved" (a feed that
     * died mid-drive would otherwise read as P throughout). 30 s can hide at
     * most a same-driveway shuffle; the daemon's ACC-off teardown stops the gear
     * poller a moment before the parking hook fires, so a short streak is normal.
     */
    static final int GEAR_WATCH_GAP_POLLS = 10;
    /** A fix older than this cannot say whether the car moved (garage). */
    static final long GPS_MOVE_MAX_FIX_AGE_MS = 5L * 60_000L;
    /** Displacement below this is GPS drift on a parked car, not a drive. */
    static final double GPS_MOVE_MIN_M = 120.0;
    /** Arrival read retries while the session is open, before the queue takes over. */
    static final int SIGNAGE_ARRIVAL_MAX_ATTEMPTS = 5;
    /**
     * return / power_on: an ACC-on closes the session only once it has HELD for
     * this long. The daemon's ACC line flaps for a few seconds without anyone
     * in the car — an acc_sentry restart probing ON before the hardware answers
     * OFF, a head-unit power glitch, an IPC race — and closing on the edge
     * turned every such flap into a "Back at car" plus a one-minute junk row
     * (field log: one flap every 68 s for a quarter of an hour).
     */
    static final long ACC_ON_SETTLE_MS = 30_000L;
    /**
     * Human evidence window for an ACC-on that did not settle: a guarded door
     * open or an unlock within this long before the flap means the owner was
     * at the car, so the flap WAS a return (and the switch-off a new park).
     */
    static final long ACC_FLAP_HUMAN_WINDOW_MS = 10L * 60_000L;
    static final int SIGNAGE_BATCH = 3;
    static final long REARM_SESSION_MAX_GAP_MS = 24L * 3600_000L;
    /**
     * A door opening within this window of switch-off is the driver getting
     * out (or unloading), never a return. Lock-armed installs get the unlock
     * edge first anyway; this guards the power-armed ones.
     */
    static final long DOOR_RETURN_MIN_AGE_MS = 180_000L;
    /** Door activity (open OR close) within this window means "still unloading". */
    static final long DOOR_QUIET_MS = 60_000L;
    /**
     * An unlock counts as the return only once a door opens within this window
     * (or the car powers on). Proximity / walk-away locking and a lagging cloud
     * lock state unlock-and-relock an untouched car for hours; each of those
     * unlocks used to close the session with a "Back at car" and the re-lock
     * silently opened the next one — a message storm with nobody at the car.
     */
    static final long UNLOCK_CONFIRM_MS = 3L * 60_000L;
    /** Boot recovery waits for the daemon's ACC reading to become authoritative. */
    static final long RECOVER_RETRY_MS = 5_000L;
    static final int RECOVER_MAX_ATTEMPTS = 24;
    /** An open row older than this is a leftover, never the current park. */
    static final long ADOPT_MAX_AGE_MS = 7L * 86_400_000L;
    /** The daemon's boot ACC-off replay lands within this window of adoption; later edges are real. */
    static final long ADOPT_EDGE_WINDOW_MS = 10L * 60_000L;

    private final ParkingConfig.Source configSource;
    private final ParkingEnvironment env;
    private final Supplier<ParkingStore> storeFactory;

    private final Object lifecycleLock = new Object();
    /** Serializes start/stop sequences so a fast toggle flip cannot interleave them. */
    private final Object reconcileLock = new Object();
    private boolean attached;
    private volatile boolean started;
    /** stop() in progress: {@link #started} stays true until teardown is complete. */
    private boolean stopping;
    private Consumer<JSONObject> configListener;
    private volatile ParkingConfig config = ParkingConfig.disabled();

    // ---- live only while started (all touched on the worker unless volatile)
    private volatile ParkingStore store;
    private volatile ScheduledExecutorService worker;
    private ParkingNeighbourObserver observer;
    private ParkingSnapshotter snapshotter;
    private ParkingCropHarvester harvester;
    private ParkingNotifier notifier;
    private SignageReader signageReader;

    private volatile ParkingSession current;
    private volatile String currentSessionId;
    private long lastAccOffGeneration = Long.MIN_VALUE;
    private long lastAccOnGeneration = Long.MIN_VALUE;
    private boolean returnInProgress;
    /** Wall-clock of the last door open/close seen while parked (worker-only). */
    private long lastDoorActivityMs;
    /**
     * The current session was adopted from the store at (re)start; the daemon's
     * boot ACC-off edge for the same park may still arrive and must NOT
     * supersede it.
     */
    private boolean adoptedAwaitingEdge;
    private long adoptedAtMs;
    private ScheduledFuture<?> arrivedTask;
    private ScheduledFuture<?> tickTask;
    private ScheduledFuture<?> pruneTask;
    private ScheduledFuture<?> signageTask;
    /** drive_away: the "has the gear left P yet" poll armed at ACC-on. */
    private ScheduledFuture<?> gearTask;
    /** drive_away: ACC is on and the live session is waiting for the gear to leave P (worker-only). */
    private boolean gearWatchArmed;
    /** drive_away: the current watch has seen at least one FRESH P reading (worker-only). */
    private boolean gearWatchSawPark;
    /** drive_away: consecutive reading-less polls since the last fresh reading (worker-only). */
    private int gearWatchNullStreak;
    /** drive_away: the feed went silent for {@link #GEAR_WATCH_GAP_POLLS} after a P — evidence incomplete. */
    private boolean gearWatchGap;
    /** Wall-clock of the last "powered on without driving" resume of the live session; 0 = none. */
    private long resumedAtMs;
    /** Wall-clock of an unlock awaiting its door-open confirmation; 0 = none (worker-only). */
    private long unlockPendingMs;
    /** Settle window for an ACC-on close; package-visible so tests can shorten it. */
    volatile long accOnSettleMs = ACC_ON_SETTLE_MS;
    /** return / power_on: the ACC-on whose close is waiting to settle (worker-only). */
    private ScheduledFuture<?> accOnSettleTask;
    private long pendingAccOnGeneration = Long.MIN_VALUE;
    private long pendingAccOnSinceMs;
    /** Wall-clock of the last door open that passed the return-age guard; 0 = none (worker-only). */
    private long lastGuardedDoorOpenMs;

    public ParkingController(ParkingConfig.Source configSource, ParkingEnvironment env,
                             Supplier<ParkingStore> storeFactory) {
        this.configSource = configSource;
        this.env = env;
        this.storeFactory = storeFactory;
    }

    // ==================== LIFECYCLE ====================

    /** Idempotent. Starts iff enabled; installs the live-toggle listener. */
    public void attach() {
        synchronized (lifecycleLock) {
            if (attached) return;
            attached = true;
            configListener = section -> {
                // The unified config fires listeners inside its file lock:
                // never reconcile inline (start() opens H2 and spawns a thread).
                Thread t = new Thread(() -> {
                    try { reconcile(); } catch (Throwable e) { env.log("Parking reconcile error: " + e.getMessage()); }
                }, "parking-toggle");
                t.setDaemon(true);
                t.start();
            };
            try { configSource.addListener(configListener); }
            catch (Throwable t) { env.log("Parking config listener install failed: " + t.getMessage()); }
        }
        reconcile();
    }

    /** Idempotent inverse of {@link #attach()}: removes the listener and stops. */
    public void detach() {
        synchronized (lifecycleLock) {
            if (configListener != null) {
                try { configSource.removeListener(configListener); } catch (Throwable ignored) {}
                configListener = null;
            }
            attached = false;
        }
        synchronized (reconcileLock) {
            // Daemon shutdown: leave an open session in the store so the next
            // start adopts it (the car is still parked).
            stop(false);
        }
    }

    /** Re-read config; start/stop to match. Serialized and idempotent. */
    void reconcile() {
        synchronized (reconcileLock) {
            synchronized (lifecycleLock) {
                // A toggle thread that was already past the listener when detach()
                // ran must not resurrect the feature in a shutting-down process.
                if (!attached) { stop(false); return; }
            }
            ParkingConfig cfg;
            try { cfg = configSource.read(); } catch (Throwable t) { cfg = ParkingConfig.disabled(); }
            ParkingConfig previous = config;
            config = cfg;
            if (cfg.enabled) {
                boolean wasStarted = started;
                start();
                if (wasStarted && previous.neighbours != cfg.neighbours) {
                    // Live flip of the neighbours toggle mid-session: the 1 Hz
                    // harvester tick follows it.
                    submit(() -> { stopTick(); if (current != null) startTick(); });
                }
            } else {
                // User switched the feature off: the open session ends here, so
                // it can never be closed days later with a bogus duration.
                stop(true);
            }
        }
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (started) return;
            ParkingStore s = storeFactory.get();
            if (s == null || !s.open()) {
                env.log("Parking: store unavailable — feature stays off");
                return;
            }
            store = s;
            observer = new ParkingNeighbourObserver(s);
            snapshotter = new ParkingSnapshotter(env);
            harvester = new ParkingCropHarvester(env);
            notifier = new ParkingNotifier(env);
            signageReader = new SignageReader(env);
            lastDoorActivityMs = 0L;
            adoptedAwaitingEdge = false;
            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "parking-worker");
                t.setDaemon(true);
                // Everything here (stills, OCR, frame scoring) is best-effort
                // background work: yield to the camera/detector threads.
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            started = true;
            // Recovery is queued BEFORE the hooks go live: the worker is FIFO,
            // so an ACC / door / baseline edge arriving during start-up can
            // never run against un-recovered state.
            submit(() -> recoverOpenSession(1));
            ParkingHooks.setListener(this);
            try { env.setBaselineListener(this); } catch (Throwable t) { env.log("Parking baseline hook failed: " + t.getMessage()); }
            try { env.setDoorListener(open -> submit(() -> handleDoorEvent(open))); }
            catch (Throwable t) { env.log("Parking door hook failed: " + t.getMessage()); }
            pruneTask = worker.scheduleWithFixedDelay(() -> guard(this::prune),
                    PRUNE_INITIAL_DELAY_MS, PRUNE_PERIOD_MS, TimeUnit.MILLISECONDS);
            env.log("Parking Intelligence started");
        }
    }

    /** Stops without touching an open session (see {@link #stop(boolean)}). */
    public void stop() {
        stop(false);
    }

    /**
     * Exact inverse of {@link #start()}.
     *
     * @param closeOpenSession true when the USER disabled the feature: the open
     *        session is closed (trigger {@code disabled}, no notification) so a
     *        later re-enable cannot close it with a days-long duration. False at
     *        daemon shutdown, where the row must stay open for adoption.
     */
    public void stop(boolean closeOpenSession) {
        ScheduledExecutorService w;
        ParkingStore s;
        synchronized (lifecycleLock) {
            if (!started || stopping) return;
            stopping = true;
            // Detach from the world FIRST so no new work can arrive.
            ParkingHooks.setListener(null);
            try { env.setBaselineListener(null); } catch (Throwable ignored) {}
            try { env.setDoorListener(null); } catch (Throwable ignored) {}
            w = worker;
            s = store;
            worker = null;
        }
        boolean terminated = true;
        // A store the stop interrupt kills is healed by this thread below, not the dying worker.
        if (s != null) s.setHealingEnabled(false);
        if (w != null) {
            w.shutdownNow();
            try { terminated = w.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                terminated = false;
            }
        }
        if (!terminated) {
            // A capture or OCR read is still running on the worker; it can only
            // touch the store (which fails closed) — leave its in-memory state alone.
            env.log("Parking worker did not stop within 2 s; skipping in-memory teardown");
        } else {
            if (s != null) s.setHealingEnabled(true);
            ParkingSession open = current;
            if (closeOpenSession && open != null && open.isOpen() && s != null && s.isOpen()) {
                try { closeSession(open, ParkingSession.END_DISABLED, false); }
                catch (Throwable t) { env.log("Parking close-on-disable failed: " + t.getMessage()); }
            }
            if (harvester != null) harvester.clear();
        }
        current = null;
        currentSessionId = null;
        returnInProgress = false;
        adoptedAwaitingEdge = false;
        arrivedTask = null; tickTask = null; pruneTask = null; signageTask = null; gearTask = null;
        gearWatchArmed = false; gearWatchSawPark = false; gearWatchNullStreak = 0; gearWatchGap = false;
        resumedAtMs = 0L; unlockPendingMs = 0L;
        accOnSettleTask = null; pendingAccOnGeneration = Long.MIN_VALUE; pendingAccOnSinceMs = 0L;
        lastGuardedDoorOpenMs = 0L;
        if (s != null) s.close();
        store = null;
        observer = null; snapshotter = null; harvester = null; notifier = null; signageReader = null;
        synchronized (lifecycleLock) {
            // Published LAST: anyone who observes !isStarted() sees a fully
            // torn-down controller (no store, no hooks, no worker).
            started = false;
            stopping = false;
        }
        env.log("Parking Intelligence stopped");
    }

    public boolean isStarted() { return started; }

    public ParkingConfig config() { return config; }

    /** The live store, or null when the feature is not running. */
    public ParkingStore store() { return store; }

    /** Snapshot of the open session (may be slightly stale), or null. */
    public ParkingSession currentSession() { return current; }

    /**
     * Live energy view for an OPEN row: SoC now against the row's start
     * bookend. Display-only — never persisted, the close bookend stays the
     * durable record. Null when the feature is stopped, the row is closed,
     * either reading is missing, or JSON assembly fails. Callable from any
     * thread: the collector/SOH snapshots are cross-thread reads (the HTTP
     * handlers already use them) and the row object is not mutated.
     */
    public JSONObject liveEnergyJson(ParkingSession s) {
        if (s == null || !s.isOpen() || !started) return null;
        double socNow = Double.NaN, kwhNow = Double.NaN;
        try { socNow = env.readSocPercent(); } catch (Throwable ignored) {}
        try { kwhNow = env.readRemainKwh(); } catch (Throwable ignored) {}
        boolean socPair = !Double.isNaN(s.startSocPercent) && !Double.isNaN(socNow);
        boolean kwhPair = !Double.isNaN(s.startRemainKwh) && !Double.isNaN(kwhNow);
        if (!socPair && !kwhPair) return null;
        try {
            JSONObject e = new JSONObject();
            e.put("live", true);
            if (!Double.isNaN(s.startSocPercent)) e.put("startSoc", s.startSocPercent);
            if (!Double.isNaN(s.startRemainKwh)) e.put("startKwh", s.startRemainKwh);
            double socDelta = Double.NaN;
            if (socPair) {
                socDelta = socNow - s.startSocPercent;
                e.put("liveSoc", socNow);
                e.put("socDelta", socDelta);
            }
            // Same channel precedence and noise floors as the frozen close.
            if (kwhPair) {
                double kwhDelta = kwhNow - s.startRemainKwh;
                e.put("liveKwh", kwhNow);
                e.put("kwh", kwhDelta);
                e.put("source", ParkingSession.ENERGY_SRC_BMS);
                e.put("charged", kwhDelta >= ParkingSession.MIN_ENERGY_KWH);
                e.put("measurable", Math.abs(kwhDelta) >= ParkingSession.MIN_ENERGY_KWH);
            } else {
                boolean measurable = Math.abs(socDelta) >= ParkingSession.MIN_SOC_DELTA_PERCENT;
                e.put("charged", socDelta >= ParkingSession.MIN_SOC_DELTA_PERCENT);
                e.put("measurable", measurable);
                if (measurable) {
                    double est = env.estimateEnergyKwh(Math.abs(socDelta));
                    if (!Double.isNaN(est)) {
                        e.put("estKwh", est);
                        e.put("kwh", Math.signum(socDelta) * est);
                        e.put("source", ParkingSession.ENERGY_SRC_SOC);
                    }
                }
            }
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Asset directory for a session (created lazily by writers). */
    public File sessionDir(String sessionId) {
        return new File(env.parkingBaseDir(), sessionId);
    }

    // ==================== HOOKS (never block) ====================

    @Override public void onAccOff(long transitionGeneration) {
        submit(() -> handleAccOff(transitionGeneration));
    }

    @Override public void onAccOn(long transitionGeneration) {
        submit(() -> handleAccOn(transitionGeneration));
    }

    @Override public void onUnlockWhileParked() {
        submit(this::handleUnlock);
    }

    @Override public void onLockWhileParked() {
        submit(this::handleLock);
    }

    @Override public void onEventFinalized(File mp4, List<Actor> actors, long segmentStartMs,
                                           boolean finalSegment) {
        final List<Actor> copy = actors == null ? new ArrayList<>() : new ArrayList<>(actors);
        submit(() -> handleEventFinalized(mp4, copy, segmentStartMs, finalSegment));
    }

    @Override public String currentSessionId() {
        return currentSessionId;
    }

    // ---- DetectionBaseline.Listener (fired inside the baseline monitor: hop off immediately)

    @Override public void onEntrySeeded(int quadrant, List<DetectionBaseline.EntrySnapshot> entries) {
        final List<DetectionBaseline.EntrySnapshot> copy = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        submit(() -> {
            ensureSessionOnRearm();
            markSentryArmed(current);
            if (current != null && config.neighbours) observer.onEntrySeeded(quadrant, copy, env.nowMs());
        });
    }

    @Override public void onEntryAdded(DetectionBaseline.EntrySnapshot entry, String source) {
        submit(() -> {
            markSentryArmed(current);
            if (current != null && config.neighbours) observer.onEntryAdded(entry, source, env.nowMs());
        });
    }

    @Override public void onEntryConfirmed(DetectionBaseline.EntrySnapshot entry) {
        submit(() -> { if (current != null && config.neighbours) observer.onEntryConfirmed(entry, env.nowMs()); });
    }

    @Override public void onEntryRetired(DetectionBaseline.EntrySnapshot entry, String reason) {
        submit(() -> { if (current != null && config.neighbours) observer.onEntryRetired(entry, reason, env.nowMs()); });
    }

    /** Re-queue the signage read for one session (API "read again"). */
    public boolean requestSignage(String sessionId) {
        if (!started) return false;
        return submitChecked(() -> {
            ParkingStore st = store;
            if (st == null) return;
            ParkingSession live = current;
            if (live != null && live.sessionId.equals(sessionId) && live.isOpen()) {
                // The queue skips the live session and the close path writes this object back.
                live.signageState = ParkingSession.SIGNAGE_PENDING;
                if (isEventRecording()) {
                    st.updateSession(live);
                    cancel(signageTask);
                    signageTask = schedule(() -> retryOpenSessionSignage(sessionId, 1), SIGNAGE_BUSY_RETRY_MS);
                    return;
                }
                readSignageNow(live);
                st.updateSession(live);
                return;
            }
            ParkingSession s = st.getSession(sessionId);
            if (s == null) return;
            s.signageState = ParkingSession.SIGNAGE_PENDING;
            st.updateSession(s);
            if (isEventRecording()) {
                schedule(() -> retryRequestedSignage(2), SIGNAGE_BUSY_RETRY_MS);
                return;
            }
            runSignageQueue(true);
        });
    }

    /** A requested read of a closed session that a sentry clip deferred: retry while parked too. */
    void retryRequestedSignage(int attempt) {
        if (isEventRecording()) {
            if (attempt < SIGNAGE_ARRIVAL_MAX_ATTEMPTS) schedule(() -> retryRequestedSignage(attempt + 1), SIGNAGE_BUSY_RETRY_MS);
            return;
        }
        runSignageQueue(true);
    }

    // ==================== WORKER: ACC EDGES ====================

    void handleAccOff(long generation) {
        if (generation == lastAccOffGeneration) return;   // replayed transition
        lastAccOffGeneration = generation;
        long now = env.nowMs();
        ParkingSession open = current;
        if (open != null) {
            if (pendingAccOnGeneration != Long.MIN_VALUE) {
                // ACC went off again before its ACC-on settled.
                long heldMs = now - pendingAccOnSinceMs;
                cancel(accOnSettleTask); accOnSettleTask = null;
                pendingAccOnGeneration = Long.MIN_VALUE;
                boolean human = (lastGuardedDoorOpenMs > 0L && now - lastGuardedDoorOpenMs <= ACC_FLAP_HUMAN_WINDOW_MS)
                        || (unlockPendingMs > 0L && now - unlockPendingMs <= ACC_FLAP_HUMAN_WINDOW_MS)
                        || carMovedSinceParkStart(open);
                if (!human) {
                    // Nobody opened a door, nothing was unlocked, the car is where
                    // it was: a power glitch / probe race on the ACC line, not the
                    // owner. The park never ended — keep the session exactly as it
                    // was, dropping the "returned" stills that power-on took.
                    discardReturnedStills(open);
                    open.transitionGeneration = generation;
                    store.updateSession(open);
                    env.log("Parking: ACC flapped on/off for " + heldMs + " ms with no door, unlock or"
                            + " GPS evidence — ignored, session " + open.sessionId + " continues");
                    return;
                }
                // The owner was at the car: that ACC-on was the return, this
                // ACC-off is a new park.
                closeSession(open, ParkingSession.END_ACC_ON, true);
                openSession(generation, false);
                return;
            }
            if (adoptedAwaitingEdge && now - adoptedAtMs <= ADOPT_EDGE_WINDOW_MS) {
                // The daemon's boot-time ACC-off edge for the park we already
                // adopted from the store: same park, just record the generation.
                adoptedAwaitingEdge = false;
                open.transitionGeneration = generation;
                store.updateSession(open);
                env.log("Parking session " + open.sessionId + " confirmed by boot ACC-off edge");
                return;
            }
            if (gearWatchArmed && !carMovedSinceParkStart(open)) {
                // drive_away: the car was powered on but never left P (sat in
                // it, warmed it up, checked something) and is now off again.
                // That is the SAME park — resume the session instead of
                // superseding it with a duplicate row at the same spot.
                resumeSession(open, generation);
                return;
            }
            // Still-open row at ACC-off: a missed ACC-on edge (stale session),
            // or a drive_away session whose car did move (gear feed silent, GPS
            // says otherwise). Close it silently — the new row opening below
            // carries the park onward.
            closeSession(open, ParkingSession.END_SUPERSEDED, false);
        } else {
            // Boot while parked, recovery still waiting for an authoritative ACC
            // reading: the daemon has now told us ACC is OFF, so the open row
            // (if any) IS this park — adopt it rather than opening a duplicate.
            ParkingSession row = store.getOpenSession();
            if (row != null && now - row.startedMs >= 0 && now - row.startedMs < ADOPT_MAX_AGE_MS) {
                closeOtherOpenRows(row.sessionId, ParkingSession.END_SUPERSEDED, false);
                adopt(row, false);
                row.transitionGeneration = generation;
                store.updateSession(row);
                return;
            }
            closeOtherOpenRows(null, ParkingSession.END_RECOVERED, false);
        }
        openSession(generation, false);
    }

    void handleAccOn(long generation) {
        if (generation == lastAccOnGeneration) return;
        lastAccOnGeneration = generation;
        adoptedAwaitingEdge = false;
        ParkingSession s = current;
        if (s != null) {
            if (config.snapshots && !s.returnedSnapshotOk) captureReturned(s);
            if (ParkingConfig.END_TRIGGER_DRIVE_AWAY.equals(config.endTrigger)) {
                // The session stays open until the gear leaves P (or the watch
                // gives up and falls back to acc_on). Persist the stills taken
                // above — normally finalizeRow does that, but there is no close
                // here. Sentry is off while the car is on, so the 1 Hz harvester
                // tick has nothing to feed on: stop it now instead of at close.
                store.updateSession(s);
                armGearWatch(s);
            } else if (accOnSettleMs <= 0L) {
                closeSession(s, ParkingSession.END_ACC_ON, true);
            } else {
                // Close only once the ACC-on has held (see ACC_ON_SETTLE_MS);
                // handleAccOff decides whether an earlier ACC-off was a flap.
                store.updateSession(s);           // persist the returned-stills bookkeeping
                pendingAccOnGeneration = generation;
                pendingAccOnSinceMs = env.nowMs();
                cancel(accOnSettleTask);
                final String sid = s.sessionId;
                accOnSettleTask = schedule(() -> settleAccOn(sid, generation), accOnSettleMs);
            }
        }
        // Rows left open by a previous daemon incarnation are over too: the car
        // is being driven. Tell the user only about a park they were told about.
        closeOtherOpenRows(current == null ? null : current.sessionId,
                ParkingSession.END_RECOVERED, true);
        scheduleSignageQueue(SIGNAGE_AFTER_ACC_ON_MS);
        schedule(this::retryMissingPlaces, SIGNAGE_AFTER_ACC_ON_MS);
    }

    /** The ACC-on held for the settle window: a real power-on, close now. */
    void settleAccOn(String sessionId, long generation) {
        ParkingSession s = current;
        if (s == null || !s.sessionId.equals(sessionId) || pendingAccOnGeneration != generation) return;
        pendingAccOnGeneration = Long.MIN_VALUE;
        accOnSettleTask = null;
        closeSession(s, ParkingSession.END_ACC_ON, true);
    }

    /** drive_away, ACC on: start (or restart) the gear watch on the live session. */
    private void armGearWatch(ParkingSession s) {
        stopTick();
        cancel(gearTask);
        gearWatchArmed = true;
        gearWatchSawPark = false;
        gearWatchNullStreak = 0;
        gearWatchGap = false;
        final String sid = s.sessionId;
        gearTask = schedule(() -> gearWatchAttempt(sid, 1), GEAR_WATCH_POLL_MS);
        env.log("Parking: drive-away watch armed for " + sid);
    }

    private void disarmGearWatch() {
        cancel(gearTask); gearTask = null;
        gearWatchArmed = false;
        gearWatchSawPark = false;
        gearWatchNullStreak = 0;
        gearWatchGap = false;
    }

    /**
     * drive_away wait: the car is on but the session is still open. Close on
     * the first FRESH non-P gear reading. A fresh P reading is knowledge — the
     * car is still here — so the watch simply continues for as long as ACC
     * stays on (ACC-off then resumes or supersedes, see handleAccOff). Only
     * when NO reading at all arrives for {@link #GEAR_WATCH_MAX_ATTEMPTS} polls
     * does it fall back to the power-on close, so a trim without a usable gear
     * feed degrades to end-at-power-on (+ ~2 min), never to a session that will
     * not close.
     */
    void gearWatchAttempt(String sessionId, int attempt) {
        ParkingSession s = current;
        if (s == null || !s.sessionId.equals(sessionId) || !s.isOpen() || !gearWatchArmed) return;
        Boolean inPark = null;
        try { inPark = env.gearInPark(); } catch (Throwable ignored) {}
        if (inPark != null && !inPark) {
            closeSession(s, ParkingSession.END_DRIVE_AWAY, true);
            return;
        }
        if (inPark != null) {
            gearWatchSawPark = true;
            gearWatchNullStreak = 0;
        } else if (!gearWatchSawPark && attempt >= GEAR_WATCH_MAX_ATTEMPTS) {
            env.log("Parking: no gear reading at all after " + attempt
                    + " polls — closing " + sessionId + " as acc_on");
            closeSession(s, ParkingSession.END_ACC_ON, true);
            return;
        } else if (gearWatchSawPark && ++gearWatchNullStreak >= GEAR_WATCH_GAP_POLLS) {
            gearWatchGap = true;
        }
        gearTask = schedule(() -> gearWatchAttempt(sessionId, attempt + 1), GEAR_WATCH_POLL_MS);
    }

    /**
     * Did the car leave the spot it parked at? Decides whether an ACC-off that
     * ends a drive_away watch resumes the session (no) or supersedes it (yes).
     * The gear watch's own evidence comes first: a fresh P seen and never a
     * non-P means it stayed. Without gear evidence, a fresh GPS fix well away
     * from the park is the tiebreaker; no usable fix (garage) reads as "stayed",
     * because a duplicate row at the same spot is the mistake this exists to
     * prevent, and a genuinely moved car is caught by the next real drive-away.
     */
    boolean carMovedSinceParkStart(ParkingSession s) {
        // Continuous gear evidence is definitive; a feed that went silent after
        // the P could have missed a whole drive, so GPS must decide instead.
        if (gearWatchSawPark && !gearWatchGap) return false;
        if (s == null || !s.hasFix()) return false;
        ParkingEnvironment.GpsFix fix;
        try { fix = env.readGpsFix(); } catch (Throwable t) { return false; }
        if (fix == null || !fix.hasLocation() || fix.loadedFromCache) return false;
        long age = ParkingLocationCapture.fixAgeMs(fix, env.nowElapsedMs(), env.nowMs());
        if (age < 0 || age > GPS_MOVE_MAX_FIX_AGE_MS) return false;
        double d = ParkingLocationCapture.distanceM(s.lat, s.lng, fix.lat, fix.lng);
        // Both fixes' error circles must not explain the displacement.
        double slack = Math.max(0f, s.accuracyM) + Math.max(0f, fix.accuracyM);
        return d > Math.max(GPS_MOVE_MIN_M, slack);
    }

    /** drive_away, powered on without driving, off again: same park, keep the session. */
    private void resumeSession(ParkingSession s, long generation) {
        disarmGearWatch();
        resumedAtMs = env.nowMs();
        s.transitionGeneration = generation;
        // The "returned" stills taken at that power-on show the owner getting
        // in and not leaving; drop them so the real departure's set is the one
        // the page shows (assets are listed from disk, not from the flag).
        discardReturnedStills(s);
        store.updateSession(s);
        startTick();                       // sentry may re-arm for the rest of the park
        env.log("Parking session resumed " + s.sessionId + " (powered on without driving)");
    }

    /** Drop a power-on's "returned" stills: that power-on turned out not to be the departure. */
    private void discardReturnedStills(ParkingSession s) {
        s.returnedSnapshotOk = false;
        s.returnedSnapshotMs = 0L;
        deleteStills(sessionDir(s.sessionId), ParkingSnapshotter.PREFIX_RETURNED);
    }

    /**
     * Door edge while ACC is off. Only a door OPENING after a quiet period,
     * well after switch-off, counts as the owner returning: the driver getting
     * out (and any unloading) produces door activity within the first minutes,
     * and lock-armed installs see the unlock edge before any door opens.
     */
    void handleDoorEvent(boolean open) {
        long now = env.nowMs();
        boolean quiet = lastDoorActivityMs == 0L || now - lastDoorActivityMs >= DOOR_QUIET_MS;
        lastDoorActivityMs = now;
        ParkingSession s = current;
        if (s == null || !open) return;
        // A resumed drive_away session (powered on, never left P, off again) is
        // being exited right now: that door activity is the driver leaving again.
        long since = Math.max(s.startedMs, resumedAtMs);
        if (now - since < DOOR_RETURN_MIN_AGE_MS) return;         // driver leaving
        // Past the age guard a door open is somebody AT the car, whether or not
        // it goes on to count as a return below; an ACC flap right after it is
        // then a real power-on, not a glitch (handleAccOff).
        lastGuardedDoorOpenMs = now;
        if (unlockPendingMs > 0L && now - unlockPendingMs <= UNLOCK_CONFIRM_MS) {
            // Unlocked moments ago and now a door opens: the owner is getting
            // in. That IS the return, whatever the recent door activity was.
            unlockPendingMs = 0L;
            handleReturnSignal(ParkingSession.END_UNLOCK);
            return;
        }
        if (!quiet) return;                                        // still unloading
        handleReturnSignal(ParkingSession.END_DOOR_OPEN);
    }

    /**
     * Doors unlocked while parked. Not a return by itself: the owner may be
     * grabbing something and re-locking, and proximity / walk-away locking or a
     * lagging cloud lock state unlock-and-relock an untouched car all night.
     * The "returned" stills are taken NOW (surveillance tears the camera
     * pipeline down right after this unlock); the close waits for a door to
     * open ({@link #handleDoorEvent}) or the car to power on. A re-lock first
     * ({@link #handleLock}) withdraws it.
     */
    void handleUnlock() {
        ParkingSession s = current;
        if (s == null) return;
        long now = env.nowMs();
        long since = Math.max(s.startedMs, resumedAtMs);
        if (now - since < DOOR_RETURN_MIN_AGE_MS) return;         // just parked / just re-armed
        if (config.snapshots) {
            captureReturned(s);
            store.updateSession(s);
        }
        unlockPendingMs = now;
    }

    /** Locked again while parked: an unlock nobody followed through on was not a return. */
    void handleLock() {
        if (unlockPendingMs > 0L) {
            env.log("Parking: unlock withdrawn by re-lock (no door opened) — not a return");
        }
        unlockPendingMs = 0L;
    }

    /**
     * A confirmed return (guarded door-open, or unlock followed by a door):
     * the owner is back. With the default endTrigger this closes the session;
     * with power_on / drive_away it only refreshes the "returned" stills (the
     * LAST walk-up before the configured end wins — a mid-park door-open that
     * never becomes a departure is overwritten by the real return) and the
     * session closes later at ACC-on or gear-out-of-P.
     */
    void handleReturnSignal(String trigger) {
        ParkingSession s = current;
        if (s == null || returnInProgress) return;
        returnInProgress = true;
        try {
            if (ParkingConfig.END_TRIGGER_RETURN.equals(config.endTrigger)) {
                if (config.snapshots) captureReturned(s);
                closeSession(s, trigger, true);
            } else if (config.snapshots) {
                captureReturned(s);
                store.updateSession(s);   // no close here to persist the stills bookkeeping
            }
        } finally {
            returnInProgress = false;
        }
    }

    // ==================== WORKER: SESSION ====================

    private void openSession(long generation, boolean silent) {
        long now = env.nowMs();
        ParkingSession s = new ParkingSession();
        s.sessionId = uniqueSessionId(now);
        s.startedMs = now;
        s.createdMs = now;
        s.transitionGeneration = generation;
        try { ParkingLocationCapture.apply(s, env.readGpsFix(), env.nowElapsedMs(), now); }
        catch (Throwable t) { env.log("Parking GPS read failed: " + t.getMessage()); }
        try { s.safeZone = env.currentSafeZoneName(); } catch (Throwable ignored) {}
        try { s.sentryState = env.sentryState(); } catch (Throwable ignored) {}
        try { s.rectifyStrength = env.rectifyStrength(); } catch (Throwable ignored) {}
        try { s.startSocPercent = env.readSocPercent(); } catch (Throwable ignored) {}
        try { s.startRemainKwh = env.readRemainKwh(); } catch (Throwable ignored) {}
        s.signageState = config.signage ? ParkingSession.SIGNAGE_PENDING : ParkingSession.SIGNAGE_SKIPPED;
        if (silent) s.notifiedStarted = true;
        if (!store.insertSession(s)) {
            env.log("Parking: insert failed for " + s.sessionId);
            return;
        }
        current = s;
        currentSessionId = s.sessionId;
        observer.startSession(s);
        harvester.clear();
        env.log("Parking session opened " + s.sessionId + " gps=" + s.gpsQuality
                + " sentry=" + s.sentryState + (silent ? " (re-arm)" : ""));

        if (s.hasFix()) {
            final String sid = s.sessionId;
            try {
                env.resolvePlaceAsync(s.lat, s.lng, place -> submit(() -> applyPlace(sid, place)));
            } catch (Throwable t) { env.log("Parking geocode failed: " + t.getMessage()); }
        }
        cancel(arrivedTask);
        final String sid = s.sessionId;
        arrivedTask = schedule(() -> arrivedAttempt(sid, 1), silent ? ARRIVED_RETRY_MS : ARRIVED_DELAY_MS);
        startTick();
    }

    /** Sentry re-armed with no open session (owner popped the trunk and re-locked): reopen silently. */
    private void ensureSessionOnRearm() {
        if (current != null) return;
        boolean accOn;
        try { accOn = env.isAccOn(); } catch (Throwable t) { accOn = true; }
        if (accOn) return;
        ParkingSession last = store.getLatestSession();
        if (last == null || last.isOpen()) return;
        if (env.nowMs() - last.endedMs > REARM_SESSION_MAX_GAP_MS) return;
        openSession(last.transitionGeneration, true);
    }

    private void applyPlace(String sessionId, ParkingEnvironment.Place place) {
        if (place == null) return;
        ParkingSession s = current;
        if (s == null || !s.sessionId.equals(sessionId)) {
            s = store.getSession(sessionId);
            if (s == null) return;
        }
        s.placeShort = place.shortLabel;
        s.placeDisplay = place.displayName;
        s.placeSource = place.source;
        store.updateSession(s);
    }

    /** ~90 s after switch-off: re-read sentry state, take the arrived stills, notify. */
    void arrivedAttempt(String sessionId, int attempt) {
        ParkingSession s = current;
        if (s == null || !s.sessionId.equals(sessionId) || !s.isOpen()) return;
        if (!s.notifiedStarted && env.nowMs() - s.startedMs > ARRIVED_STALE_MS) {
            // The head unit slept through the timer (nothing keeps it awake with
            // sentry off) and this is firing at wake-up, i.e. as the owner comes
            // back: a "Parked" now would be hours late, and any stills would
            // show the return, not the arrival.
            s.notifiedStarted = true;
            store.updateSession(s);
            env.log("Parking: arrival timer for " + s.sessionId + " fired stale — skipped");
            return;
        }
        try { s.sentryState = env.sentryState(); } catch (Throwable ignored) {}
        File composite = null;
        boolean pipelineUp = false;
        try { pipelineUp = env.isPipelineRunning(); } catch (Throwable ignored) {}
        if (config.snapshots && pipelineUp && !s.arrivedSnapshotOk) {
            ParkingSnapshotter.Result r = snapshotter.capture(sessionDir(s.sessionId), ParkingSnapshotter.PREFIX_ARRIVED);
            s.arrivedSnapshotOk = r.ok();
            if (r.ok()) s.arrivedSnapshotMs = env.nowMs();
            composite = r.compositeFile;
        }
        boolean mayComeUp = ParkingSession.SENTRY_LOCK_WAIT.equals(s.sentryState)
                || ParkingSession.SENTRY_PIPELINE_DOWN.equals(s.sentryState)
                || ParkingSession.SENTRY_UNKNOWN.equals(s.sentryState);
        if (config.snapshots && !s.arrivedSnapshotOk && mayComeUp && attempt < ARRIVED_MAX_ATTEMPTS) {
            store.updateSession(s);
            arrivedTask = schedule(() -> arrivedAttempt(sessionId, attempt + 1), ARRIVED_RETRY_MS);
            return;
        }
        String levelLabel = null;
        if (config.signage && ParkingSession.SIGNAGE_PENDING.equals(s.signageState)) {
            if (isEventRecording()) {
                // A sentry clip is being encoded: decoding the approach clip +
                // TFLite on top of that is the codec/CPU contention this SoC
                // cannot afford. The deferred queue picks the session up later.
                env.log("Signage read deferred for " + s.sessionId + ": sentry clip recording");
                final String sid = s.sessionId;
                cancel(signageTask);
                signageTask = schedule(() -> retryOpenSessionSignage(sid, 1), SIGNAGE_BUSY_RETRY_MS);
            } else {
                // Read NOW — the level and bay are worth most while the owner is
                // walking away, so the "Parked" message can carry them. One
                // ~5–15 s burst per park, on the low-priority worker; sentry
                // itself runs the cameras and detector for hours on the same
                // battery, so this is a rounding error next to it.
                levelLabel = readSignageNow(s);
            }
        }
        if (!s.notifiedStarted) {
            notifier.publishStarted(s, composite, levelLabel);
            s.notifiedStarted = true;
        }
        store.updateSession(s);
    }

    private void captureReturned(ParkingSession s) {
        boolean pipelineUp = false;
        try { pipelineUp = env.isPipelineRunning(); } catch (Throwable ignored) {}
        if (!pipelineUp) return;
        ParkingSnapshotter.Result r = snapshotter.capture(sessionDir(s.sessionId), ParkingSnapshotter.PREFIX_RETURNED);
        s.returnedSnapshotOk = r.ok();
        s.returnedSnapshotMs = env.nowMs();
    }

    /** Close the LIVE session: tears down timers/harvester state, then finalizes the row. */
    private void closeSession(ParkingSession s, String trigger, boolean notify) {
        cancel(arrivedTask); arrivedTask = null;
        disarmGearWatch();
        cancel(accOnSettleTask); accOnSettleTask = null;
        pendingAccOnGeneration = Long.MIN_VALUE;
        resumedAtMs = 0L;
        unlockPendingMs = 0L;
        lastGuardedDoorOpenMs = 0L;
        stopTick();
        long now = env.nowMs();
        observer.endSession(s, now);
        finalizeRow(s, trigger, notify, now);
        harvester.clear();
        current = null;
        currentSessionId = null;
        adoptedAwaitingEdge = false;
    }

    /**
     * Close a row that is NOT the live session (a leftover of a previous daemon
     * incarnation). Store-only: the observer's live state and the live
     * session's timers are untouched.
     */
    private void closeLeftoverRow(ParkingSession s, String trigger, boolean notify) {
        long now = env.nowMs();
        ParkingNeighbourObserver.finalizeRows(store, s, now);
        finalizeRow(s, trigger, notify, now);
    }

    private void finalizeRow(ParkingSession s, String trigger, boolean notify, long now) {
        s.endedMs = now;
        s.endTrigger = trigger;
        // Energy bookend: only a GENUINE end-of-park close may stamp it. A repair
        // close (recovered after a restart with the car already driving,
        // superseded by a missed edge) reads SoC long after — or a drive after —
        // the park it belongs to, and a wrong number is worse than none.
        boolean genuineReturn = ParkingSession.END_ACC_ON.equals(trigger)
                || ParkingSession.END_DOOR_OPEN.equals(trigger)
                || ParkingSession.END_UNLOCK.equals(trigger)
                || ParkingSession.END_DRIVE_AWAY.equals(trigger)
                || ParkingSession.END_DISABLED.equals(trigger);
        if (genuineReturn && Double.isNaN(s.endSocPercent) && Double.isNaN(s.endRemainKwh)) {
            try {
                double endSoc = env.readSocPercent();
                if (!Double.isNaN(endSoc)) {
                    s.endSocPercent = endSoc;
                    if (!Double.isNaN(s.startSocPercent)) {
                        // Estimate only from a real SoC move: the polled gauge is
                        // whole-percent, so below one step there is nothing to convert.
                        double delta = endSoc - s.startSocPercent;
                        if (Math.abs(delta) >= ParkingSession.MIN_SOC_DELTA_PERCENT) {
                            s.energyEstKwh = env.estimateEnergyKwh(Math.abs(delta));
                        }
                    }
                }
            } catch (Throwable ignored) {}
            try { s.endRemainKwh = env.readRemainKwh(); } catch (Throwable ignored) {}
            // "Charged" follows the channel that answers: BMS kWh first (its own
            // noise floor), else SoC above one quantization step.
            if (s.hasKwhBookends()) {
                s.chargedWhileParked = s.endRemainKwh - s.startRemainKwh >= ParkingSession.MIN_ENERGY_KWH;
            } else if (s.hasSocBookends()) {
                s.chargedWhileParked = s.socDeltaPercent() >= ParkingSession.MIN_SOC_DELTA_PERCENT;
            }
        }
        // -1 = the recordings index is not available (e.g. still warming up
        // right after a daemon restart): unknown, not zero.
        int events = -1;
        boolean critical = false;
        try {
            events = env.countSentryEvents(s.startedMs, now, false);
            critical = env.countSentryEvents(s.startedMs, now, true) > 0;
        } catch (Throwable ignored) {}
        if (events >= 0) s.eventCount = Math.max(s.eventCount, events);
        int moved = 0;
        try {
            List<ParkingNeighbour> rows = store.listNeighbours(s.sessionId);
            int neighbours = 0;
            for (ParkingNeighbour n : rows) {
                if (!ParkingNeighbour.KIND_NEIGHBOUR.equals(n.kind)) continue;
                neighbours++;
                // Status-independent: endSession above has just relabelled the
                // arrivals that are still here as STILL_THERE, but they did come.
                if (n.arrivedMs > 0 || n.departedMs > 0
                        || ParkingNeighbour.STATUS_LEFT_UNKNOWN.equals(n.status)) moved++;
            }
            s.neighbourCount = neighbours;
        } catch (Throwable ignored) {}
        // "Back at car" answers "what happened while you were away" — it only
        // makes sense for a park the user was told about. A stop too short for
        // its "Parked" (switched on again before the arrival stills) ends in
        // silence instead of a lone "Away 1 m · 0 events".
        if (notify && s.notifiedStarted && !s.notifiedEnded) {
            notifier.publishEnded(s, events >= 0 ? s.eventCount : -1, moved, critical);
            s.notifiedEnded = true;
        }
        // A session that closed before its "Parked" message went out (a short
        // stop) must not fire it late, after "Back at car".
        s.notifiedStarted = true;
        store.updateSession(s);
        env.log("Parking session closed " + s.sessionId + " trigger=" + trigger
                + " events=" + s.eventCount + " neighbours=" + s.neighbourCount + " moved=" + moved);
        // An underground garage often has no internet at arrival, so the
        // address lookup never completed; the owner is back now and connectivity
        // is likely returning — ask again (the resolver only calls back on success).
        requestPlaceIfMissing(s);
    }

    /** Geocode a session that has a fix but no address yet; idempotent, store-backed apply. */
    private void requestPlaceIfMissing(ParkingSession s) {
        if (s == null || !s.hasFix()) return;
        if ((s.placeShort != null && !s.placeShort.isEmpty())
                || (s.placeDisplay != null && !s.placeDisplay.isEmpty())) return;
        final String sid = s.sessionId;
        try { env.resolvePlaceAsync(s.lat, s.lng, place -> submit(() -> applyPlace(sid, place))); }
        catch (Throwable t) { env.log("Parking geocode retry failed: " + t.getMessage()); }
    }

    /** ~2 min after ACC-on (network is usually back): fill in addresses the garage denied us. */
    void retryMissingPlaces() {
        ParkingStore st = store;
        if (st == null) return;
        for (ParkingSession s : st.listSessions(0, 0, 5, 0)) requestPlaceIfMissing(s);
    }

    /**
     * Daemon (re)start: adopt an open session when still parked, else close it.
     * Waits (bounded) for the daemon's ACC reading to become authoritative —
     * AccMonitor defaults to "off" until the first real reading, and acting on
     * that default would adopt a finished park as "parked now" while driving.
     */
    void recoverOpenSession(int attempt) {
        ParkingSession open = store.getOpenSession();
        if (open == null) return;
        if (current != null) {
            // A hook (boot ACC edge) already settled the state while we waited.
            closeOtherOpenRows(current.sessionId, ParkingSession.END_SUPERSEDED, false);
            return;
        }
        boolean authoritative;
        try { authoritative = env.isAccStateAuthoritative(); } catch (Throwable t) { authoritative = true; }
        if (!authoritative && attempt < RECOVER_MAX_ATTEMPTS) {
            schedule(() -> recoverOpenSession(attempt + 1), RECOVER_RETRY_MS);
            return;
        }
        boolean accOn;
        try { accOn = env.isAccOn(); } catch (Throwable t) { accOn = false; }
        long age = env.nowMs() - open.startedMs;
        boolean adoptable = age >= 0 && age < ADOPT_MAX_AGE_MS;
        if (accOn) {
            if (adoptable && ParkingConfig.END_TRIGGER_DRIVE_AWAY.equals(config.endTrigger)) {
                // drive_away: an open row with the car on is the mode's normal
                // waiting state, not a leftover — the daemon restarted while the
                // owner sat in the car. Adopt it and let the gear watch decide
                // (non-P → drove away; still P at ACC-off → same park resumes).
                closeOtherOpenRows(open.sessionId, ParkingSession.END_SUPERSEDED, false);
                adopt(open, false);
                armGearWatch(open);
                scheduleSignageQueue(SIGNAGE_AFTER_ACC_ON_MS);
                return;
            }
            closeOtherOpenRows(null, ParkingSession.END_RECOVERED, true);
            scheduleSignageQueue(SIGNAGE_AFTER_ACC_ON_MS);
            return;
        }
        if (!adoptable) {
            closeOtherOpenRows(null, ParkingSession.END_RECOVERED, false);
            return;
        }
        closeOtherOpenRows(open.sessionId, ParkingSession.END_SUPERSEDED, false);
        adopt(open, true);
    }

    /** Remove one prefix's stills ({@code <prefix>_front.jpg …, <prefix>_mosaic.jpg}) from a session dir. */
    private static void deleteStills(File dir, String prefix) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(prefix + "_") && n.endsWith(".jpg")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Make an open store row the live session (worker thread). */
    private void adopt(ParkingSession open, boolean awaitBootEdge) {
        current = open;
        currentSessionId = open.sessionId;
        adoptedAwaitingEdge = awaitBootEdge;
        adoptedAtMs = env.nowMs();
        observer.adoptSession(open);
        harvester.clear();
        final String sid = open.sessionId;
        if (!open.notifiedStarted) {
            cancel(arrivedTask);
            arrivedTask = schedule(() -> arrivedAttempt(sid, ARRIVED_MAX_ATTEMPTS), ARRIVED_RETRY_MS);
        }
        if (open.hasFix() && open.placeShort == null && open.placeDisplay == null) {
            // The geocode callback was lost with the previous process.
            try { env.resolvePlaceAsync(open.lat, open.lng, place -> submit(() -> applyPlace(sid, place))); }
            catch (Throwable t) { env.log("Parking geocode failed: " + t.getMessage()); }
        }
        startTick();
        env.log("Parking session adopted " + open.sessionId);
    }

    /**
     * Close every open row except {@code keepId} (null = all). Rows are
     * leftovers of a previous daemon incarnation, so they are closed through
     * the normal path (neighbour relabelling, counts) but notified only when
     * the user was told the park started AND {@code notifyIfStarted}.
     */
    private void closeOtherOpenRows(String keepId, String trigger, boolean notifyIfStarted) {
        List<ParkingSession> rows;
        try { rows = store.listOpenSessions(); } catch (Throwable t) { return; }
        for (ParkingSession row : rows) {
            if (row == null || row.sessionId == null || row.sessionId.equals(keepId)) continue;
            if (current != null && row.sessionId.equals(current.sessionId)) continue;
            try {
                closeLeftoverRow(row, trigger, notifyIfStarted && row.notifiedStarted);
            } catch (Throwable t) {
                env.log("Parking: closing leftover " + row.sessionId + " failed: " + t.getMessage());
            }
        }
    }

    // ==================== WORKER: EVENTS / HARVEST ====================

    /**
     * Sentry evidence arrived for the open session (a finalized clip, a baseline
     * update): sentry IS armed, whatever the 90 s snapshot said ("lock_wait",
     * "pipeline_down"). Upgrade the recorded state so the closing message and
     * the history are truthful.
     */
    private void markSentryArmed(ParkingSession s) {
        if (s == null || ParkingSession.SENTRY_ARMED.equals(s.sentryState)) return;
        if (ParkingSession.SENTRY_LOCK_WAIT.equals(s.sentryState)
                || ParkingSession.SENTRY_PIPELINE_DOWN.equals(s.sentryState)
                || ParkingSession.SENTRY_UNKNOWN.equals(s.sentryState)) {
            s.sentryState = ParkingSession.SENTRY_ARMED;
            store.updateSession(s);
        }
    }

    void handleEventFinalized(File mp4, List<Actor> actors, long segmentStartMs, boolean finalSegment) {
        ParkingSession s = current;
        if (s == null) return;
        markSentryArmed(s);
        long now = env.nowMs();
        if (config.neighbours) {
            List<ParkingNeighbourObserver.Attachment> att =
                    observer.onEventFinalized(mp4, actors, segmentStartMs, finalSegment, now);
            for (ParkingNeighbourObserver.Attachment a : att) {
                ParkingNeighbour n = store.findNeighbourByKey(s.sessionId, a.neighbourKey);
                if (n == null) continue;
                n.framesJson = harvester.persistFrames(sessionDir(s.sessionId), a.neighbourKey, a.actorIds, n.framesJson);
                n.updatedMs = now;
                store.upsertNeighbour(n);
            }
        }
        if (finalSegment) {
            harvester.dropUnclaimed();
            s.eventCount++;
            store.updateSession(s);
        }
    }

    private void startTick() {
        if (tickTask != null || !config.neighbours) return;
        ScheduledExecutorService w = worker;
        if (w == null) return;
        tickTask = w.scheduleWithFixedDelay(() -> guard(() -> {
            if (current != null) harvester.tick(env.nowMs());
        }), TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void stopTick() {
        cancel(tickTask);
        tickTask = null;
    }

    // ==================== WORKER: SIGNAGE (v2) ====================

    private void scheduleSignageQueue(long delayMs) {
        if (!config.signage) return;
        cancel(signageTask);
        signageTask = schedule(() -> runSignageQueue(false), delayMs);
    }

    /**
     * Catch-up read for CLOSED sessions still pending (their arrival read was
     * deferred by sentry clips, or the models were installed later) — runs when
     * the car is on or charging, i.e. at the next drive.
     */
    void runSignageQueue(boolean force) {
        if (!config.signage && !force) return;
        boolean cheap = force;
        try { cheap = cheap || env.isAccOn() || env.isCharging(); } catch (Throwable ignored) {}
        if (!cheap) return;
        if (isEventRecording()) {
            // A sentry clip is being encoded right now: MP4 decode + TFLite on
            // top of that is the codec/CPU contention this SoC cannot afford.
            // Come back in a minute (bounded: one retry per scheduled run).
            if (!force) scheduleSignageQueue(SIGNAGE_BUSY_RETRY_MS);
            return;
        }
        List<ParkingSession> pending = store.listSessionsWithSignageState(ParkingSession.SIGNAGE_PENDING, SIGNAGE_BATCH);
        // Never process the LIVE session here: this loop works on its own row
        // objects, and the close path writes the controller's aliased copy —
        // with drive_away holding a session open past the ACC-on queue delay,
        // that write would clobber a result stored below. The open session has
        // its own read paths (arrivedAttempt / retryOpenSessionSignage), and
        // the next queue run picks it up once closed.
        String liveId = currentSessionId;
        if (liveId != null) {
            List<ParkingSession> keep = new ArrayList<>(pending.size());
            for (ParkingSession p : pending) if (!liveId.equals(p.sessionId)) keep.add(p);
            pending = keep;
        }
        if (pending.isEmpty()) return;
        TextOcrBackend backend = null;
        try {
            try { backend = env.openSignageOcr(); } catch (Throwable t) { env.log("Signage OCR open failed: " + t.getMessage()); }
            for (ParkingSession s : pending) {
                SignageReader.Outcome o = signageReader.read(s, sessionDir(s.sessionId), backend);
                s.signageState = o.state;
                s.signageJson = o.json != null ? o.json.toString() : null;
                store.updateSession(s);
                env.log("Signage " + s.sessionId + ": " + o.state + (o.label() != null ? " → " + o.label() : ""));
            }
        } finally {
            if (backend != null) { try { backend.close(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Arrival read that had to wait for a sentry clip to finish: retry a few
     * times while the session is still open, then leave it to the queue.
     */
    void retryOpenSessionSignage(String sessionId, int attempt) {
        ParkingSession s = current;
        if (s == null || !s.sessionId.equals(sessionId) || !s.isOpen()) return;
        if (!config.signage || !ParkingSession.SIGNAGE_PENDING.equals(s.signageState)) return;
        if (isEventRecording()) {
            if (attempt < SIGNAGE_ARRIVAL_MAX_ATTEMPTS) {
                signageTask = schedule(() -> retryOpenSessionSignage(sessionId, attempt + 1), SIGNAGE_BUSY_RETRY_MS);
            }
            return;
        }
        String label = readSignageNow(s);
        store.updateSession(s);
        if (label != null) env.log("Signage " + s.sessionId + " (late arrival read): " + label);
    }

    /** Synchronous read for the open session at arrival. Returns the label or null. */
    private String readSignageNow(ParkingSession s) {
        TextOcrBackend backend = null;
        try {
            backend = env.openSignageOcr();
            SignageReader.Outcome o = signageReader.read(s, sessionDir(s.sessionId), backend);
            s.signageState = o.state;
            s.signageJson = o.json != null ? o.json.toString() : null;
            return o.label();
        } catch (Throwable t) {
            env.log("Signage read failed: " + t.getMessage());
            return null;
        } finally {
            if (backend != null) { try { backend.close(); } catch (Throwable ignored) {} }
        }
    }

    private boolean isCharging() {
        try { return env.isCharging(); } catch (Throwable t) { return false; }
    }

    private boolean isEventRecording() {
        try { return env.isEventRecording(); } catch (Throwable t) { return false; }
    }

    // ==================== WORKER: RETENTION ====================

    void prune() {
        ParkingStore st = store;
        if (st == null) return;
        ParkingConfig cfg = config;
        long now = env.nowMs();
        long cutoff = now - cfg.retentionDays * 86_400_000L;
        String keep = currentSessionId;
        // Age-based retention. Rows stamped before the clock floor were written
        // while the head unit's clock was still unset (1970 after a cold boot);
        // their "age" is meaningless, so only the storage cap may reclaim them.
        for (String id : st.listSessionIdsStartedBetween(ParkingSession.CLOCK_FLOOR_MS, cutoff, 200)) {
            if (id.equals(keep)) continue;
            deleteRecursively(sessionDir(id));
            st.deleteSession(id);
        }
        File base = env.parkingBaseDir();
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null) return;
        // Orphan directories (row deleted by the API or retention while a late
        // signage / geocode write recreated the folder; crash between the two
        // deletes) hold nothing the UI can reach: drop them.
        List<File> live = new ArrayList<>();
        for (File d : dirs) {
            if (d.getName().equals(keep)) { live.add(d); continue; }
            Boolean exists = st.sessionExists(d.getName());
            // A store that cannot answer must not turn every session into an orphan; retry next prune.
            if (exists == null) return;
            if (!exists) deleteRecursively(d);
            else live.add(d);
        }
        // Storage cap: oldest assets first, never the open session.
        long capBytes = cfg.storageCapMb * 1024L * 1024L;
        long total = 0;
        for (File d : live) total += dirSize(d);
        if (total <= capBytes) return;
        live.sort((a, b) -> a.getName().compareTo(b.getName()));  // ids sort by time
        for (File d : live) {
            if (total <= capBytes) break;
            if (d.getName().equals(keep)) continue;
            long sz = dirSize(d);
            deleteRecursively(d);
            total -= sz;
            ParkingSession s = st.getSession(d.getName());
            if (s != null) {
                s.arrivedSnapshotOk = false;
                s.returnedSnapshotOk = false;
                st.updateSession(s);
                // The neighbour frames lived in that folder too.
                st.clearNeighbourFrames(s.sessionId);
            }
        }
    }

    /** Remove a session's asset directory (used by the API when the feature is stopped). */
    public static void deleteAssets(File sessionDir) {
        deleteRecursively(sessionDir);
    }

    /** Delete a session's row and assets (API). Runs inline; safe from any thread. */
    public boolean deleteSession(String sessionId) {
        ParkingStore st = store;
        if (st == null || sessionId == null) return false;
        if (sessionId.equals(currentSessionId)) return false;
        deleteRecursively(sessionDir(sessionId));
        return st.deleteSession(sessionId);
    }

    // ==================== PLUMBING ====================

    private void submit(Runnable r) {
        submitChecked(r);
    }

    private boolean submitChecked(Runnable r) {
        ScheduledExecutorService w = worker;
        if (w == null) return false;
        try {
            w.execute(() -> guard(r));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private ScheduledFuture<?> schedule(Runnable r, long delayMs) {
        ScheduledExecutorService w = worker;
        if (w == null) return null;
        try { return w.schedule(() -> guard(r), delayMs, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException e) { return null; }
    }

    private void guard(Runnable r) {
        try { r.run(); }
        catch (Throwable t) { env.log("Parking worker error: " + t); }
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    private String uniqueSessionId(long nowMs) {
        String base = sessionIdFor(nowMs);
        String id = base;
        int n = 2;
        while (store.getSession(id) != null) id = base + "_" + (n++);
        return id;
    }

    /** {@code park_yyyyMMdd_HHmmss} in local time — sorts chronologically and reads as a timestamp. */
    static String sessionIdFor(long ms) {
        return "park_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(ms));
    }

    static long dirSize(File d) {
        long total = 0;
        File[] files = d.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.isDirectory() ? dirSize(f) : f.length();
        return total;
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
