package com.overdrive.app.launcher;

import android.graphics.Rect;
import android.os.Looper;

import com.overdrive.app.camera.dilink5.ClusterTransport;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.launcher.layout.ClusterLayoutDisplayEvidence;
import com.overdrive.app.launcher.layout.ClusterLayoutLifecycle;
import com.overdrive.app.launcher.layout.ClusterLayoutSelection;
import com.overdrive.app.launcher.layout.ClusterLayoutSurfaceHost;
import com.overdrive.app.launcher.layout.LayoutModeGate;
import com.overdrive.app.launcher.layout.LayoutPreset;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.navmap.ClusterMapProjector;
import com.overdrive.app.surveillance.ClusterAdasLifecycle;
import com.overdrive.app.surveillance.ClusterProjectionController;
import com.overdrive.app.surveillance.ClusterViewMirrorService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Dedicated DI3 multi-app projection. All resource/task/config work is serialized on WORKER;
 * the mirror and projection cancellation paths see only immutable snapshots and atomic state.
 */
public final class ClusterLayouts {
    private static final String RECOVERY_KEY = "clusterLayoutRecovery";
    private static final long SETTLE_WAIT_MS = 45_000L, OPEN_WAIT_MS = 60_000L, DISPLAY_WAIT_MS = 20_000L;
    private static final long CLEANUP_WAIT_MS = 10_000L;
    private static final Object ADMISSION = new Object();
    private static final ClusterLayoutLifecycle STATE =
            new ClusterLayoutLifecycle(System.currentTimeMillis() * 1000L);
    private static volatile Thread workerThread;
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ClusterLayouts");
        thread.setDaemon(true);
        workerThread = thread;
        return thread;
    });
    private static final AtomicReference<Snapshot> ACTIVE = new AtomicReference<>();
    private static final ClusterLayoutSelection SELECTED = new ClusterLayoutSelection();
    private static final AtomicBoolean CLEANUP_QUEUED = new AtomicBoolean();
    private static final AtomicInteger CLEANUP_RETRIES = new AtomicInteger();
    private static final AtomicBoolean READ_RETRY_QUEUED = new AtomicBoolean();
    private static final AtomicInteger READ_RETRIES = new AtomicInteger(30);
    private static volatile Session session;
    private static volatile long admissionOwner;
    private static volatile boolean recoveryLoaded;
    private static volatile JSONObject recovery;
    private static volatile boolean resourceOnly;
    private static volatile String reason = "";
    private static volatile String lastFailure = "";
    private static volatile String presetId = "";

    private ClusterLayouts() {}

    /** Async acceptance. ACTIVE is published only after every slot is launched and shown. */
    public static boolean start(LayoutPreset preset) {
        if (preset == null) { reason = "invalid_preset"; return false; }
        if (!ClusterLayoutSurfaceHost.isSupported()) {
            reason = ClusterTransport.isDiLink5()
                    ? "di5_layouts_unavailable_private_overlay_backend_not_verified"
                    : "layouts_require_verified_di3_platform";
            return false;
        }
        initializeRecoveryFence();
        // A previous layout's cleanup finishes on its own; a new start waits for it briefly.
        long cleanupDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_WAIT_MS);
        while (STATE.phase() == ClusterLayoutLifecycle.Phase.STOPPING
                && System.nanoTime() < cleanupDeadline) pause(200);
        final long next;
        synchronized (ADMISSION) {
            if (STATE.busy()) { reason = "layout_cleanup_or_activation_in_progress"; return false; }
            boolean changing = STATE.active();
            next = STATE.begin();
            if (!changing && !LayoutModeGate.reserveLayout(next)) {
                STATE.idle();
                reason = "another_cluster_mode_is_starting";
                return false;
            }
            if (!changing) admissionOwner = next;
            ACTIVE.set(null);
            SELECTED.clear();
            resourceOnly = false;
            presetId = preset.id;
            reason = "preparing_layout";
            lastFailure = "";
        }
        CameraDaemon.log("Cluster layouts: preparing preset=" + preset.id + " generation=" + next
                + " slots=" + preset.slots.size());
        WORKER.execute(() -> activate(preset, next));
        return true;
    }

    public static boolean stop() {
        lastFailure = "";
        cancel(false, "stopped");
        return awaitCleanup(1600);
    }

    /** Cache cancellation is immediate; no AMS/wm operation runs on this path. */
    public static void stopForAccOff() {
        cancel(true, "acc_off_recovery_deferred");
        queueCleanup();
    }

    /** Called before mirror/Binder teardown so an in-flight launch cannot republish a slot. */
    public static void invalidateForProjectionClose(String closeReason) {
        synchronized (ADMISSION) {
            if (session == null && !STATE.active() && !STATE.busy()) return;
            boolean recoveryRequired = !recoveryLoaded || recovery != null
                    || (session != null && !session.resourcesReleased());
            ACTIVE.set(null);
            SELECTED.clear();
            if (STATE.phase() != ClusterLayoutLifecycle.Phase.STOPPING) STATE.cancel();
            // Only an ACC-off close replays the ACC-on auto-start; a close mid-drive must not recast.
            if (recoveryRequired) {
                if (accOn()) STATE.recovering();
                else STATE.recoveringForStandardRestore();
            }
            resourceOnly = true;
            reason = closeReason == null ? "projection_closed" : closeReason;
        }
    }

    /** True only after all overlay/VD handles are confirmed released. */
    public static boolean beforeProjectionClose(String closeReason) {
        Session current = session;
        if (current == null || current.resourcesReleased()) return true;
        invalidateForProjectionClose(closeReason);
        queueCleanup();
        // Releasing our last projection token invokes this method on WORKER. Likewise, a
        // main-looper caller must not wait for the host release runnable on that same looper.
        if (Thread.currentThread() == workerThread || Looper.myLooper() == Looper.getMainLooper())
            return current.resourcesReleased();
        awaitCleanup(1100);
        current = session;
        return current == null || current.resourcesReleased();
    }

    public static boolean isActive() { return liveSnapshot() != null; }
    public static boolean isBusy() { return STATE.busy(); }
    public static long generation() { return STATE.generation(); }
    public static boolean ownsGeneration(long generation) {
        Snapshot value = liveSnapshot();
        return value != null && value.generation == generation && STATE.owns(generation);
    }
    public static int physicalDisplayId() { Snapshot value = liveSnapshot(); return value == null ? -1 : value.displayId; }
    public static int panelWidth() { Snapshot value = liveSnapshot(); return value == null ? 0 : value.width; }
    public static int panelHeight() { Snapshot value = liveSnapshot(); return value == null ? 0 : value.height; }

    public static InputSlot inputSlotAt(float x, float y) {
        Snapshot value = liveSnapshot();
        if (value == null || !Float.isFinite(x) || !Float.isFinite(y)) return null;
        for (InputSlot slot : value.slots) {
            if (x >= slot.left && y >= slot.top && x < slot.left + slot.width
                    && y < slot.top + slot.height) return slot;
        }
        return null;
    }

    public static InputSlot selectedInputSlot() {
        Snapshot value = liveSnapshot();
        if (value == null) return null;
        String selected = SELECTED.selected(value.generation);
        for (InputSlot slot : value.slots) if (slot.slotId.equals(selected)) return slot;
        return null;
    }
    public static InputSlot selectedSlot() { return selectedInputSlot(); }
    public static boolean selectInputSlot(int displayId, long generation) {
        Snapshot value = liveSnapshot();
        if (value == null || value.generation != generation) return false;
        for (InputSlot slot : value.slots) {
            if (slot.displayId == displayId) {
                return SELECTED.select(generation, slot.slotId)
                        && ACTIVE.get() == value && STATE.owns(generation);
            }
        }
        return false;
    }

    public static boolean mapSlotActive(long generation, int displayId) {
        Snapshot value = ACTIVE.get();
        // Cooperative map starts while surfaces are staged. Its generation must remain
        // valid during preparation, before the all-slot commit.
        if (value != null && value.generation == generation && STATE.owns(generation)) {
            for (InputSlot slot : value.slots)
                if (slot.displayId == displayId && LayoutPreset.MAP_PACKAGE.equals(slot.pkg)) return true;
        }
        Session preparing = session;
        if (preparing == null || preparing.generation != generation || !STATE.owns(generation)) return false;
        for (OwnedSlot slot : preparing.slotsSnapshot)
            if (slot.resource.virtualDisplayId == displayId
                    && LayoutPreset.MAP_PACKAGE.equals(slot.slot.packageName)) return true;
        return false;
    }

    public static JSONObject status() throws Exception {
        Snapshot value = liveSnapshot();
        JSONArray slots = new JSONArray();
        if (value != null) for (InputSlot slot : value.slots) slots.put(slot.json());
        Session preparing = session;
        boolean staged = value == null && preparing != null && STATE.owns(preparing.generation);
        if (staged) for (OwnedSlot owned : preparing.slotsSnapshot)
            slots.put(new InputSlot(preparing.generation, owned).json());
        boolean supported = ClusterLayoutSurfaceHost.isSupported();
        boolean di5 = ClusterTransport.isDiLink5();
        String unavailable = di5 ? "di5_layouts_unavailable_private_overlay_backend_not_verified"
                : "layouts_require_verified_di3_platform";
        return new JSONObject().put("success", true).put("active", value != null)
                .put("busy", isBusy()).put("generation", generation()).put("presetId", presetId)
                .put("phase", STATE.phase().name().toLowerCase(java.util.Locale.ROOT))
                .put("reason", supported ? reason : unavailable).put("supported", supported)
                .put("failure", lastFailure)
                .put("platform", di5 ? "di5" : supported ? "di3" : "unknown")
                .put("physicalDisplayId", value != null ? value.displayId : staged ? preparing.physicalDisplayId : -1)
                .put("width", value != null ? value.width : staged ? preparing.width : 0)
                .put("height", value != null ? value.height : staged ? preparing.height : 0)
                .put("slots", slots).put("selectedSlotId", SELECTED.selected(generation()));
    }

    /** Boot calls this before ordinary casts. No IPC or shell command is issued. */
    public static void initializeRecoveryFence() {
        if (recoveryLoaded) return;
        // Config may take a filesystem lock. It must stay outside our state lock.
        JSONObject marker;
        try {
            JSONObject root = UnifiedConfigManager.readDurableConfigStrict();
            Object rawProjection = root.opt("projection");
            if (rawProjection != null && rawProjection != JSONObject.NULL
                    && !(rawProjection instanceof JSONObject))
                throw new IllegalStateException("projection_config_not_object");
            JSONObject projection = root.optJSONObject("projection");
            Object rawMarker = projection == null ? null : projection.opt(RECOVERY_KEY);
            if (rawMarker != null && rawMarker != JSONObject.NULL && !(rawMarker instanceof JSONObject))
                throw new IllegalStateException("layout_recovery_record_not_object");
            JSONObject read = projection == null ? null : projection.optJSONObject(RECOVERY_KEY);
            marker = read == null ? null : new JSONObject(read.toString());
        } catch (Exception unavailable) {
            synchronized (ADMISSION) {
                if (!recoveryLoaded) {
                    if (admissionOwner == 0 && LayoutModeGate.reserveLayout(STATE.generation()))
                        admissionOwner = STATE.generation();
                    STATE.recoveringForStandardRestore();
                    reason = "layout_recovery_record_unreadable";
                }
            }
            if (READ_RETRIES.getAndDecrement() > 0 && READ_RETRY_QUEUED.compareAndSet(false, true))
                WORKER.schedule(() -> {
                    READ_RETRY_QUEUED.set(false);
                    initializeRecoveryFence();
                    if (recoveryLoaded && accOn()) recoverWhenAccOn();
                }, 500, TimeUnit.MILLISECONDS);
            return;
        }
        synchronized (ADMISSION) {
            if (recoveryLoaded) return;
            recoveryLoaded = true;
            if (marker == null) {
                // Retire a formerly unreadable record outside this lock so a standard
                // auto-start replay cannot run under admission/config locks.
                if (STATE.phase() == ClusterLayoutLifecycle.Phase.STOPPING) STATE.recovering();
            } else {
                recovery = marker;
                long owner = marker.optLong("generation", 0);
                if (owner <= 0) owner = STATE.generation();
                if (admissionOwner != 0 && admissionOwner != owner
                        && LayoutModeGate.transferLayout(admissionOwner, owner)) admissionOwner = owner;
                else if (admissionOwner == 0 && LayoutModeGate.reserveLayout(owner)) admissionOwner = owner;
                STATE.recoveringForStandardRestore();
                reason = marker.optInt("version") == 1
                        ? "previous_layout_recovery_pending" : "unsupported_layout_recovery_record";
                presetId = marker.optString("presetId", "");
            }
        }
        if (marker == null) completeIdle();
    }

    public static void recoverAtBoot() { initializeRecoveryFence(); recoverWhenAccOn(); }
    public static void recoverWhenAccOn() {
        initializeRecoveryFence();
        if (!accOn()) return;
        CLEANUP_RETRIES.set(30);
        WORKER.execute(() -> {
            if (session != null) {
                if (!STATE.active() && !STATE.owns(session.generation) && !cleanupCurrent()) queueCleanup();
                return;
            }
            if (recovery == null) return;
            if (!recoverMarker()) { STATE.recovering(); queueCleanup(); return; }
            completeIdle();
        });
    }

    private static Snapshot liveSnapshot() {
        Snapshot value = ACTIVE.get();
        if (value == null || !STATE.active() || !STATE.owns(value.generation)) return null;
        for (ClusterLayoutSurfaceHost.SlotResource resource : value.resources)
            if (resource.isReleased() || !resource.lastFailure().isEmpty()) return null;
        return value;
    }

    private static void cancel(boolean resourcesOnly, String message) {
        synchronized (ADMISSION) {
            ACTIVE.set(null);
            SELECTED.clear();
            boolean owned = session != null || STATE.active() || STATE.busy();
            if (owned) STATE.cancel();
            if (resourcesOnly && owned) STATE.recoveringForStandardRestore();
            resourceOnly |= resourcesOnly;
            reason = message;
            CLEANUP_RETRIES.set(30);
        }
        queueCleanup();
    }

    private static void queueCleanup() {
        if (!CLEANUP_QUEUED.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            boolean complete;
            try { complete = cleanupCurrent(); }
            finally { CLEANUP_QUEUED.set(false); }
            if (!complete && !STATE.owns(STATE.generation())
                    && (session != null || accOn()) && CLEANUP_RETRIES.getAndDecrement() > 0)
                WORKER.schedule(ClusterLayouts::queueCleanup, 500, TimeUnit.MILLISECONDS);
        });
    }

    private static boolean awaitCleanup(long milliseconds) {
        if (Thread.currentThread() == workerThread || Looper.myLooper() == Looper.getMainLooper())
            return session == null && !STATE.busy();
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        WORKER.execute(() -> done.complete(cleanupCurrent()));
        try { return done.get(milliseconds, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (Exception unavailable) { return false; }
    }

    private static void activate(LayoutPreset preset, long next) {
        Session previous = session;
        String previousToken = null;
        try {
            if (!STATE.owns(next) || !accOn()) throw new IllegalStateException("acc_off_or_activation_cancelled");
            if (previous != null) {
                if (!cleanup(previous, true, false)) throw new IllegalStateException("previous_layout_cleanup_pending");
                if (recovery != null) throw new IllegalStateException("previous_layout_recovery_pending");
                previousToken = previous.token;
                session = null;
                if (!LayoutModeGate.transferLayout(admissionOwner, next))
                    throw new IllegalStateException("layout_admission_changed");
                admissionOwner = next;
            } else {
                // A close or gauge restore that is still running settles on its own; wait it out.
                long settleDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_WAIT_MS);
                while (STATE.owns(next) && accOn() && !ClusterCast.isActive()
                        && !ClusterMapProjector.isActive()
                        && (ClusterProjectionController.isLegacyRestoreInFlight()
                                || ClusterCast.isStopping())
                        && System.nanoTime() < settleDeadline) pause(200);
                ClusterAdasLifecycle.Source projection = ClusterProjectionController.clusterAdasState();
                if (!ClusterCast.isActive() && !ClusterMapProjector.isActive() && !projection.active
                        && ClusterProjectionController.isLegacyProjectionAdmissionBlocked()
                        && !ClusterProjectionController.isLegacyRestoreInFlight())
                    throw new IllegalStateException("previous_cluster_command_still_blocked");
                // A turn-signal projection holds no session; the layout takes it over like a cast does.
                if (ClusterCast.isActive() || ClusterMapProjector.isActive()
                        || ClusterProjectionController.isSustainedHeldStatic()
                        || ClusterProjectionController.isLegacyRestoreInFlight())
                    throw new IllegalStateException("stop_current_cluster_projection_before_starting_layout");
            }
            if (!STATE.owns(next) || !accOn()) throw new IllegalStateException("activation_cancelled");
            Session current = new Session(next, preset);
            session = current;
            ClusterProjectionController projection = ClusterProjectionController.getInstance();
            if (!projection.acquireSustained(current.token)) throw new IllegalStateException("projection_admission_refused");
            current.tokenHeld = true;
            if (previousToken != null) {
                projection.releaseSustainedUnlessSignalled(previousToken);
                previousToken = null;
            }
            // The open's opcode sequence must finish before the display can appear; it then gets
            // the same 20 s window as a single-app cast.
            int physical = -1;
            long openDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OPEN_WAIT_MS);
            while (live(current) && !projection.isReady()) {
                if (ClusterProjectionController.legacyOpenProgress() < 0
                        || System.nanoTime() >= openDeadline)
                    throw new IllegalStateException("cluster_projection_did_not_open");
                pause(150);
            }
            long displayDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISPLAY_WAIT_MS);
            while (live(current) && System.nanoTime() < displayDeadline) {
                physical = ClusterLayoutDisplayEvidence.fissionDisplay(shell("dumpsys", "display"));
                if (physical > 0) break;
                pause(250);
            }
            if (physical <= 0 || !live(current)) throw new IllegalStateException("cluster_display_not_ready");
            Rect panel = ClusterLayoutSurfaceHost.physicalDisplayBounds(physical);
            current.physicalDisplayId = physical;
            current.width = panel.width();
            current.height = panel.height();
            for (LayoutPreset.Slot slot : preset.slots) {
                if (!live(current)) throw new IllegalStateException("activation_cancelled");
                int[] bounds = slot.pixelBounds(current.width, current.height);
                Rect rect = new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
                int dpi = slot.dpi == 0 ? 160 : slot.dpi;
                ClusterLayoutSurfaceHost.SlotResource resource;
                current.allocationInFlight = true;
                try {
                    resource = ClusterLayoutSurfaceHost.create(next, slot.id, physical, rect, dpi, () -> live(current));
                    current.resources.add(resource);
                } catch (ClusterLayoutSurfaceHost.CreateException failed) {
                    if (failed.resource != null && !failed.cleanupConfirmed) current.resources.add(failed.resource);
                    throw failed;
                } finally {
                    current.resourcesSnapshot = Collections.unmodifiableList(new ArrayList<>(current.resources));
                    current.allocationInFlight = false;
                }
                OwnedSlot owned = new OwnedSlot(slot, resource);
                current.slots.add(owned);
                current.slotsSnapshot = Collections.unmodifiableList(new ArrayList<>(current.slots));
                CameraDaemon.log("Cluster layouts: allocated slot=" + slot.id + " package=" + slot.packageName
                        + " display=" + resource.virtualDisplayId + " backend=" + resource.surfaceBackend
                        + " bounds=" + rect.toShortString() + " dpi=" + dpi);
                if (!persist(current)) throw new IllegalStateException("layout_recovery_record_write_failed");
                if (!live(current)) throw new IllegalStateException("activation_cancelled");
                // The durable record intentionally precedes AMS: a cancelled/failed launch may
                // still have moved a task even when am's result was lost.
                owned.launchAttempted = true;
                owned.launcher = new ClusterLayoutTaskLauncher(slot.packageName, resource, next,
                        () -> live(current));
                if (!owned.launcher.launch() || !live(current))
                    throw new IllegalStateException("slot_launch_failed:" + slot.id);
                if (!awaitForeground(owned, current))
                    throw new IllegalStateException("slot_foreground_not_verified:" + slot.id);
            }
            // A later app's launch must not have stolen an earlier slot's foreground.
            for (OwnedSlot owned : current.slots) {
                if (!awaitForeground(owned, current))
                    throw new IllegalStateException("slot_foreground_lost:" + owned.slot.id);
            }
            if (ClusterLayoutSurfaceHost.nativeTargetStatus(current.resources) != 1)
                throw new IllegalStateException("layout_cluster_target_not_verified");
            if (!live(current) || !ClusterLayoutSurfaceHost.showAll(current.resources, () -> live(current)))
                throw new IllegalStateException("slot_surface_commit_failed");
            List<InputSlot> input = new ArrayList<>();
            for (OwnedSlot owned : current.slots) input.add(new InputSlot(next, owned));
            Snapshot committed = new Snapshot(next, physical, current.width, current.height, input, current.resourcesSnapshot);
            synchronized (ADMISSION) {
                if (!STATE.commit(next)) throw new IllegalStateException("activation_cancelled");
                ACTIVE.set(committed);
                SELECTED.initialize(next, input.get(0).slotId);
                reason = "active";
            }
            publishMapActive(true);
            CameraDaemon.log("Cluster layouts: active preset=" + preset.id + " generation=" + next
                    + " physicalDisplay=" + physical);
            WORKER.schedule(() -> checkLiveness(next), 500, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            if (STATE.owns(next) && accOn() && !resourceOnly) {
                reason = failure.getMessage() == null ? "layout_activation_failed" : failure.getMessage();
                lastFailure = reason;
                CameraDaemon.log("Cluster layouts: failed preset=" + preset.id + " generation=" + next
                        + " reason=" + lastFailure);
            } else {
                CameraDaemon.log("Cluster layouts: cancelled preset=" + preset.id + " generation=" + next);
            }
            ACTIVE.set(null);
            synchronized (ADMISSION) { if (STATE.owns(next)) STATE.cancel(); }
            cleanupCurrent();
            CLEANUP_RETRIES.set(30);
            queueCleanup();
        } finally {
            if (previousToken != null) {
                try { ClusterProjectionController.getInstance().releaseSustainedUnlessSignalled(previousToken); }
                catch (Throwable ignored) {}
            }
        }
    }

    private static void checkLiveness(long generation) {
        Session current = session;
        if (current == null || current.generation != generation || !STATE.owns(generation)) return;
        if (!live(current)) {
            cancel(true, "projection_or_acc_identity_lost");
            return;
        }
        for (ClusterLayoutSurfaceHost.SlotResource resource : current.resourcesSnapshot) {
            if (resource.isReleased() || !resource.lastFailure().isEmpty()) {
                lastFailure = "slot_resource_lost:" + resource.lastFailure();
                cancel(false, "slot_resource_lost:" + resource.lastFailure());
                return;
            }
        }
        tickWatchdogs(current);
        if (!live(current)) return;
        if (System.nanoTime() >= current.nextTargetCheck) {
            current.nextTargetCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            int target = ClusterLayoutSurfaceHost.nativeTargetStatus(current.resourcesSnapshot);
            if (session != current || !STATE.owns(generation)) return;
            if (!live(current)) { cancel(true, "projection_or_acc_identity_lost"); return; }
            if (target == 1) current.targetReadFailures = 0;
            if (target == 0 || (target < 0 && ++current.targetReadFailures >= 3)) {
                lastFailure = target == 0 ? "layout_cluster_target_changed" : "layout_cluster_target_unreadable";
                CameraDaemon.log("Cluster layouts: " + lastFailure + " generation=" + generation);
                cancel(false, lastFailure);
                return;
            }
        }
        WORKER.schedule(() -> checkLiveness(generation), 500, TimeUnit.MILLISECONDS);
    }

    private static boolean awaitForeground(OwnedSlot slot, Session current) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (live(current) && System.nanoTime() < deadline) {
            tickWatchdogs(current);
            if (!live(current)) return false;
            if (slot.slot.packageName.equals(
                    ClusterCast.resumedPackageOnDisplay(slot.resource.virtualDisplayId)))
                return live(current);
            pause(150);
        }
        return false;
    }

    private static void tickWatchdogs(Session current) {
        for (OwnedSlot slot : current.slots) {
            if (!live(current)) return;
            if (slot.launcher != null) slot.launcher.tick();
        }
    }

    private static boolean live(Session current) {
        return session == current && STATE.owns(current.generation) && !resourceOnly && accOn()
                && ClusterProjectionController.holdsTokenStatic(current.token);
    }

    private static boolean accOn() { return AccMonitor.isAccStateAuthoritative() && AccMonitor.isAccOn(); }

    private static boolean cleanupCurrent() {
        Session current = session;
        if (current == null) {
            // A completion queued by an older stop must not remove admission from a newly
            // accepted generation whose activation runnable has not started yet.
            if (STATE.phase() == ClusterLayoutLifecycle.Phase.STARTING) return false;
            if (!recoveryLoaded) {
                STATE.recoveringForStandardRestore();
                reason = "layout_recovery_record_unreadable";
                return false;
            }
            if (recovery != null) {
                STATE.recovering();
                if (!accOn() || !recoverMarker()) { STATE.recovering(); return false; }
            }
            completeIdle();
            return true;
        }
        if (STATE.owns(current.generation)) return false;
        if (accOn() && ClusterProjectionController.isProjectionIdleStatic()) resourceOnly = false;
        boolean normal = !resourceOnly && accOn();
        if (!cleanup(current, normal, true)) { STATE.recovering(); return false; }
        session = null;
        if (recovery != null) {
            STATE.recovering();
            return false;
        }
        completeIdle();
        return true;
    }

    private static boolean cleanup(Session current, boolean normal, boolean releaseToken) {
        ACTIVE.set(null);
        publishMapActive(false);
        if (!ClusterViewMirrorService.detachBeforeLayoutRelease("layout_cleanup")) {
            reason = "layout_mirror_detach_pending";
            return false;
        }
        boolean appsClean = normal && accOn();
        if (appsClean) {
            // The map observes generation invalidation and finishes cooperatively; it is
            // never re-launched on display 0 while its private cluster alias is unwinding.
            for (OwnedSlot slot : current.slots)
                if (LayoutPreset.MAP_PACKAGE.equals(slot.slot.packageName) && slot.launchAttempted) {
                    pause(700); break;
                }
            for (OwnedSlot slot : current.slots) {
                if (!slot.launchAttempted) continue;
                if (!accOn() || resourceOnly) { appsClean = false; break; }
                if (!rehome(slot.slot.packageName, slot.resource.virtualDisplayId,
                        slot.resource.displayName, slot.resource.virtualDisplayUniqueId)) {
                    appsClean = false;
                    reason = "slot_rehome_not_confirmed:" + slot.slot.id;
                }
            }
        }
        // Normal stop preserves live app tasks until the move is verified. ACC/direct close
        // must release surfaces without issuing AMS commands, retaining the recovery marker.
        if (normal && !appsClean && accOn() && !resourceOnly) return false;
        boolean released = true;
        for (ClusterLayoutSurfaceHost.SlotResource resource : current.resources) {
            if (!resource.release()) { released = false; reason = resource.lastFailure(); }
        }
        if (!released) return false;
        if (appsClean && !clearMarker()) return false;
        if (releaseToken && current.tokenHeld) {
            current.tokenHeld = false;
            // The cache already confirms resource release when forceClose calls us back.
            ClusterProjectionController.getInstance().releaseSustainedUnlessSignalled(current.token);
        }
        return true;
    }

    private static boolean rehome(String pkg, int displayId, String name, String uniqueId) {
        if (!accOn() || resourceOnly || displayId <= 0 || !LayoutPreset.validPackage(pkg)) return false;
        String displays = shell("dumpsys", "display");
        int identity = ClusterLayoutDisplayEvidence.displayIdentity(displays, displayId, name, uniqueId);
        if (identity < 0) return false;
        String before = shell("dumpsys", "activity", "activities");
        Boolean present = ClusterLayoutDisplayEvidence.packageOnDisplay(before, displayId, pkg);
        if (present == null) return false;
        if (!present) return true;
        if (identity == 0 || !accOn() || resourceOnly) return false;
        if (LayoutPreset.MAP_PACKAGE.equals(pkg)) return false;
        if (!ClusterFreeformWindow.moveDisplayTaskToDefaultDisplay(pkg, displayId)) return false;
        if (!accOn() || resourceOnly) return false;
        Boolean remaining = ClusterLayoutDisplayEvidence.packageOnDisplay(
                shell("dumpsys", "activity", "activities"), displayId, pkg);
        return Boolean.FALSE.equals(remaining);
    }

    private static boolean persist(Session current) {
        try {
            JSONArray apps = new JSONArray();
            for (OwnedSlot slot : current.slots) apps.put(new JSONObject()
                    .put("package", slot.slot.packageName).put("displayId", slot.resource.virtualDisplayId)
                    .put("displayName", slot.resource.displayName).put("uniqueId", slot.resource.virtualDisplayUniqueId));
            JSONObject marker = new JSONObject().put("version", 1).put("bootId", bootId())
                    .put("generation", current.generation).put("presetId", current.preset.id)
                    .put("apps", apps).put("resourcesPending", true);
            if (!UnifiedConfigManager.updateSection("projection", new JSONObject().put(RECOVERY_KEY, marker))) return false;
            recovery = marker;
            return true;
        } catch (Exception unavailable) {
            reason = "layout_recovery_record_write_failed";
            return false;
        }
    }

    private static boolean recoverMarker() {
        JSONObject marker = recovery;
        if (marker == null) return true;
        if (!accOn() || marker.optInt("version") != 1
                || !ClusterProjectionController.isProjectionIdleStatic()) return false;
        resourceOnly = false;
        String previousBoot = marker.optString("bootId", "");
        String currentBoot = bootId();
        if (!previousBoot.isEmpty() && !currentBoot.isEmpty() && !previousBoot.equals(currentBoot))
            return clearMarker(); // A private VD/task cannot survive a Linux boot.
        JSONArray apps = marker.optJSONArray("apps");
        long owner = marker.optLong("generation", 0);
        if (apps == null || apps.length() > LayoutPreset.MAX_SLOTS || owner <= 0) {
            reason = "invalid_layout_recovery_record";
            return false;
        }
        // Validate the complete record before the first move. An arbitrary display name
        // from a config restore cannot grant this controller ownership of that display.
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null || !LayoutPreset.validPackage(app.optString("package"))
                    || app.optInt("displayId", -1) <= 0
                    || !ClusterLayoutDisplayEvidence.ownedDisplayName(app.optString("displayName"), owner)) {
                reason = "invalid_layout_recovery_display_identity";
                return false;
            }
        }
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app == null || !rehome(app.optString("package"), app.optInt("displayId", -1),
                    app.optString("displayName"), app.optString("uniqueId"))) {
                reason = "layout_recovery_display_or_task_not_verified";
                return false;
            }
        }
        return clearMarker();
    }

    private static boolean clearMarker() {
        if (recovery == null) return true;
        boolean cleared;
        try {
            cleared = UnifiedConfigManager.updateSection("projection",
                    new JSONObject().put(RECOVERY_KEY, JSONObject.NULL));
        } catch (Exception unavailable) { cleared = false; }
        if (!cleared) {
            reason = "layout_recovery_record_clear_failed";
            return false;
        }
        recovery = null;
        return true;
    }

    private static void publishMapActive(boolean active) {
        boolean map = false;
        Snapshot value = liveSnapshot();
        if (active && value != null) for (InputSlot slot : value.slots)
            if (LayoutPreset.MAP_PACKAGE.equals(slot.pkg)) { map = true; break; }
        try {
            UnifiedConfigManager.updateSection("projection",
                    new JSONObject().put("clusterLayoutMapActive", map));
        } catch (Exception ignored) {}
    }

    private static void completeIdle() {
        boolean replayStandard;
        synchronized (ADMISSION) {
            ClusterLayoutLifecycle.IdleCompletion completion =
                    STATE.finishIdle(recoveryLoaded, session != null || recovery != null);
            if (completion == ClusterLayoutLifecycle.IdleCompletion.BLOCKED) return;
            LayoutModeGate.releaseLayout(admissionOwner);
            admissionOwner = 0;
            resourceOnly = false;
            presetId = "";
            replayStandard = completion == ClusterLayoutLifecycle.IdleCompletion.RECOVERED;
        }
        if (replayStandard) AccMonitor.retryClusterAutoProjectionAfterRecovery();
    }

    private static String bootId() {
        try { return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),
                StandardCharsets.US_ASCII).trim(); }
        catch (Throwable unavailable) { return ""; }
    }

    /** Bounded argv execution, with capped capture and child/stream cleanup even on interruption. */
    private static String shell(String... command) {
        return shell(2500, command);
    }

    /** Launch commands get five seconds; dump/recovery callers retain their existing bound. */
    static String shell(long timeoutMs, String... command) {
        return shell(timeoutMs, null, command);
    }

    static String shell(long timeoutMs, BooleanSupplier admission, String... command) {
        Process process = null;
        InputStream stream = null;
        boolean interrupted = false;
        try {
            if (admission != null && !admission.getAsBoolean()) return null;
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            stream = process.getInputStream();
            InputStream input = stream;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            AtomicBoolean tooLarge = new AtomicBoolean();
            Thread drain = new Thread(() -> {
                byte[] buffer = new byte[4096];
                try {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count <= 2 * 1024 * 1024) bytes.write(buffer, 0, count);
                        else tooLarge.set(true);
                    }
                } catch (Exception ignored) {}
            }, "ClusterLayoutsShell");
            drain.setDaemon(true);
            drain.start();
            if (admission == null) {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return null;
            } else {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (!admission.getAsBoolean() || System.nanoTime() >= deadline) return null;
                }
                if (!admission.getAsBoolean()) return null;
            }
            drain.join(250);
            if (drain.isAlive() || tooLarge.get() || process.exitValue() != 0) return null;
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (InterruptedException stopped) { interrupted = true; return null; }
        catch (Exception unavailable) { return null; }
        finally {
            if (process != null) try { process.destroyForcibly(); } catch (Exception ignored) {}
            if (stream != null) try { stream.close(); } catch (Exception ignored) {}
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void pause(long milliseconds) {
        try { Thread.sleep(milliseconds); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    public static final class InputSlot {
        public final long generation;
        public final int displayId, physicalDisplayId, left, top, width, height;
        public final int virtualWidth, virtualHeight, inputWidth, inputHeight, dpi;
        public final String pkg, packageName, slotId, surfaceBackend;
        private InputSlot(long generation, OwnedSlot slot) {
            Rect bounds = slot.resource.bounds();
            this.generation = generation;
            displayId = slot.resource.virtualDisplayId;
            physicalDisplayId = slot.resource.physicalDisplayId;
            left = bounds.left; top = bounds.top; width = bounds.width(); height = bounds.height();
            virtualWidth = inputWidth = slot.resource.width;
            virtualHeight = inputHeight = slot.resource.height;
            dpi = slot.resource.dpi;
            pkg = packageName = slot.slot.packageName;
            slotId = slot.slot.id;
            surfaceBackend = slot.resource.surfaceBackend;
        }
        private JSONObject json() throws Exception {
            return new JSONObject().put("id", slotId).put("package", pkg).put("displayId", displayId)
                    .put("left", left).put("top", top).put("right", left + width)
                    .put("bottom", top + height).put("dpi", dpi)
                    .put("surfaceBackend", surfaceBackend);
        }
    }

    private static final class Snapshot {
        final long generation;
        final int displayId, width, height;
        final List<InputSlot> slots;
        final List<ClusterLayoutSurfaceHost.SlotResource> resources;
        Snapshot(long generation, int displayId, int width, int height, List<InputSlot> slots,
                List<ClusterLayoutSurfaceHost.SlotResource> resources) {
            this.generation = generation; this.displayId = displayId; this.width = width; this.height = height;
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
            this.resources = Collections.unmodifiableList(new ArrayList<>(resources));
        }
    }

    private static final class OwnedSlot {
        final LayoutPreset.Slot slot;
        final ClusterLayoutSurfaceHost.SlotResource resource;
        boolean launchAttempted;
        ClusterLayoutTaskLauncher launcher;
        OwnedSlot(LayoutPreset.Slot slot, ClusterLayoutSurfaceHost.SlotResource resource) {
            this.slot = slot; this.resource = resource;
        }
    }

    private static final class Session {
        final long generation;
        final LayoutPreset preset;
        final String token;
        final List<OwnedSlot> slots = new ArrayList<>();
        final List<ClusterLayoutSurfaceHost.SlotResource> resources = new ArrayList<>();
        volatile List<OwnedSlot> slotsSnapshot = Collections.emptyList();
        volatile List<ClusterLayoutSurfaceHost.SlotResource> resourcesSnapshot = Collections.emptyList();
        volatile boolean allocationInFlight;
        volatile boolean tokenHeld;
        volatile int physicalDisplayId = -1, width, height;
        long nextTargetCheck;
        int targetReadFailures;
        Session(long generation, LayoutPreset preset) {
            this.generation = generation; this.preset = preset; this.token = "layout:" + generation;
        }
        boolean resourcesReleased() {
            // Resource allocation is still possible while this generation is live. A clean
            // empty list during STARTING is not a permission to retire the physical display.
            if (STATE.owns(generation) || allocationInFlight) return false;
            for (ClusterLayoutSurfaceHost.SlotResource resource : resourcesSnapshot)
                if (!resource.isReleased()) return false;
            return true;
        }
    }
}
