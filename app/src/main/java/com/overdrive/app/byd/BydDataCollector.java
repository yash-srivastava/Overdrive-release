package com.overdrive.app.byd;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.Messages;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Universal BYD Data Collector — singleton that initializes ALL BYD device types,
 * reads initial values, registers listeners for live updates, and exposes a
 * thread-safe BydVehicleData snapshot.
 * 
 * Every device init and every method call is individually try/caught — one device
 * failing never affects others. Never crashes.
 */
public class BydDataCollector {

    private static final String TAG = "BydDataCollector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Connected-unit BYDAutoSettingDevice.setPadRotation enum. */
    public static final int PAD_ROTATION_HORIZONTAL = 1;
    public static final int PAD_ROTATION_VERTICAL = 2;

    /** OEM panorama-view event codes used by the native camera application. */
    public static final int NATIVE_CAMERA_VIEW_FRONT = 3001;
    public static final int NATIVE_CAMERA_VIEW_REAR = 3002;
    public static final int NATIVE_CAMERA_VIEW_LEFT = 3003;
    public static final int NATIVE_CAMERA_VIEW_RIGHT = 3004;
    public static final int NATIVE_CAMERA_VIEW_FRONT_WIDE = 3006;
    public static final int NATIVE_CAMERA_VIEW_REAR_WIDE = 3007;
    public static final int NATIVE_CAMERA_VIEW_LEFT_RIGHT = 3008;
    private static BydDataCollector instance;
    private static final Object lock = new Object();

    private final AtomicReference<BydVehicleData> snapshot = new AtomicReference<>();
    /**
     * Serializes BMS/gun callback publication against a poll's final snapshot write. The version
     * lets a poll preserve a newer edge instead of overwriting FINISHED/DISCONNECTED with the state
     * it read before that callback arrived.
     */
    private final Object chargingEdgePublishLock = new Object();
    /**
     * Serializes raw BMS transition publication through listener delivery. Lock order is always
     * this monitor before {@link #chargingEdgePublishLock}.
     */
    private final Object chargingStateTransitionLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong bmsEdgeVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong gunEdgeVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chargingTypeVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong capacityEdgeVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong devicePowerEdgeVersion =
            new java.util.concurrent.atomic.AtomicLong();
    /** Guarded by {@link #chargingEdgePublishLock}. */
    private boolean latestDevicePowerCameFromCallback = false;
    /** Guarded by {@link #chargingEdgePublishLock}. */
    private long lastPositiveDevicePowerCallbackAtMs = 0L;
    private final java.util.concurrent.atomic.AtomicLong externalPowerEdgeVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong enginePowerEdgeVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chargingRateClearVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong chargingPollGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * Invalidates callback work that began before an init/stop publication barrier. Listener
     * generations identify a registered HAL handle; this epoch additionally distinguishes two
     * lifetimes of the same still-registered singleton handle.
     */
    private final java.util.concurrent.atomic.AtomicLong callbackLifecycleGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * Identifies the currently installed periodic poll lane. A task checks this only after acquiring
     * the collector monitor, so shutdown also fences a runnable already blocked at monitor entry.
     */
    private final java.util.concurrent.atomic.AtomicLong pollSchedulerGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private final ChargingObservationOrder chargingObservationOrder =
            new ChargingObservationOrder();
    /** Guarded by {@link #chargingEdgePublishLock}. */
    private long lastPublishedChargingPollGeneration = 0L;
    private Context context;
    private volatile boolean initialized = false;
    private final java.util.concurrent.atomic.AtomicLong safetyBeltListenerGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile Object activeSafetyBeltListenerDevice;
    private volatile long activeSafetyBeltListenerGeneration = -1L;
    private final java.util.concurrent.atomic.AtomicLong passengerOccupancySampleGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private final Object passengerOccupancyPublishLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong chargingListenerGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong instrumentListenerGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong engineListenerGeneration =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile Object activeChargingListenerDevice;
    private volatile Object activeInstrumentListenerDevice;
    private volatile Object activeEngineListenerDevice;
    private volatile long activeChargingListenerGeneration = -1L;
    private volatile long activeInstrumentListenerGeneration = -1L;
    private volatile long activeEngineListenerGeneration = -1L;
    /**
     * Serializes the complete drivetrain probe/establishment transaction. This is deliberately not
     * the collector monitor: charging callbacks may probe while holding chargingEdgePublishLock,
     * whereas collectAll holds the collector monitor before that lock.
     */
    private final Object drivetrainProbeLock = new Object();

    // Device references (all nullable)
    private Object bodyworkDevice;
    private Object rearViewMirrorDevice;
    // Volatile: read by the cluster speed overlay's 2 Hz thread in readCurrentSpeedKmh()
    // without the monitor, while init() (synchronized) may reassign it on an ACC-on
    // re-init. Volatile gives the happens-before so the overlay never sees a stale ref
    // (matches speedHwFactor/hwUnitDetected, read on the same path).
    private volatile Object speedDevice;
    private Object engineDevice;
    private Object statisticDevice;
    private Object energyDevice;
    private Object tyreDevice;
    private Object chargingDevice;
    private Object doorLockDevice;
    private Object instrumentDevice;
    private Object otaDevice;
    private Object sensorDevice;
    private Object gearboxDevice;
    private Object safetyBeltDevice;
    private Object acDevice;
    // PM2.5 air-quality device. A DEDICATED HAL device (the reference apps use exactly this
    // class + getPM2p5Value); the values are not on the bodywork/AC devices, which is why our
    // only PM2.5 source used to be the BYD cloud snapshot — leaving the local reading, and the
    // pm25 automation signal, permanently empty offline.
    private Object pm25Device;
    private Object lightDevice;
    private Object wiperDevice;
    private Object adasDevice;
    private Object collectDataDevice;
    private Object radarDevice;
    private Object powerDevice;
    private Object settingDevice;
    private Object multimediaDevice;
    /** Last capability verdict from a real config value; unavailable reads do not erase it. */
    private volatile Boolean acChargingCurrentLimitSupported;

    // Unit conversion: BYD APIs return values in the user's configured unit.
    // If the user set miles on the instrument cluster, mileage/speed/range come back in miles/mph.
    // We detect this once at init and convert everything to km at the ingestion boundary.
    private static final double MILES_TO_KM = 1.60934;
    private volatile double distanceToKmFactor = 1.0;  // 1.0 = already km, 1.60934 = miles→km
    private boolean unitDetected = false;
    // HARDWARE-ONLY SDK→km factor for the cluster speed badge. Unlike
    // distanceToKmFactor (which setDistanceUnitOverride drives from the user's APP
    // display preference and so can diverge from the cluster's real unit), this
    // tracks ONLY the authoritative getMileageUnit() hardware detection — so
    // readCurrentSpeedKmh() returns TRUE km/h and the overlay's single mph conversion
    // isn't double-applied. When hardware detection never succeeds (hwUnitDetected
    // stays false) readCurrentSpeedKmh() returns NaN ("--") — it NEVER falls back to
    // the app override (distanceToKmFactor), since that can be unit-contaminated and
    // the app preference can't disambiguate the raw cluster unit. Volatile: read from
    // the overlay's 2 Hz thread, written on the init/API threads.
    private volatile double speedHwFactor = 1.0;
    private volatile boolean hwUnitDetected = false;

    // PHEV half-scale energy correction. FIELD-CONFIRMED (owner ground truth,
    // multiple BYD PHEVs): on EVERY PHEV the BYD HAL reports remaining battery
    // energy at HALF the true (gross-nameplate) scale — a constant ~0.497
    // fraction across pack sizes, the fingerprint of a fixed scaling artifact,
    // not real degradation (e.g. ~9.1 kWh read on an 18.3 kWh gross pack at full
    // charge; ×2 = 18.2 ≈ nameplate). This is NOT a "usable window" — every
    // remaining-energy getter (getBatteryRemainPowerEV / getRemainingBatteryPower
    // / getBatteryPowerHEV / getBatteryCapacity) is affected. We correct it ONCE,
    // at the read boundary in collectBodywork, so the single corrected remainKwh
    // flows in the true gross frame into trips, MQTT, and SOH. BEV is never
    // touched (gated on isPhevForKwh). Applied BEFORE the validation gates so a
    // gross value at full charge passes the impliedCap[10,130] check instead of
    // failing it (a half value implies ~9 kWh and would be rejected).
    private static final double PHEV_ENERGY_HALF_SCALE_CORRECTION = 2.0;

    // Throttle for the INFO-level PHEV energy diagnostic (all raw getters + SOC).
    // Lets a captured on-device log prove which getter tracks SOC and which is
    // stale, without spamming every 5s poll.
    private volatile long lastPhevEnergyDiagMs = 0;
    private static final long PHEV_ENERGY_DIAG_INTERVAL_MS = 60_000;

    private final List<String> availableDevices = new ArrayList<>();
    private final List<String> unavailableDevices = new ArrayList<>();

    // ==================== EVENT LISTENERS ====================
    // Subscribers receive door/lock events from the typed BYD HAL listeners.
    // Use these instead of polling the snapshot when you need immediate
    // notification of state transitions (e.g. surveillance arming gates).

    /** Raw SDK door-open/close events from the bodywork HAL. */
    public interface DoorStateListener {
        /** @param area BYD area constant. @param state 0=closed,1=open per SDK. */
        void onDoorStateChanged(int area, int state);
    }

    /** Raw SDK lock events from the doorlock HAL. */
    public interface DoorLockListener {
        /** @param area BYD area constant. @param sdkState SDK semantics: INVALID=0,UNLOCK=1,LOCK=2. */
        void onDoorLockStatusChanged(int area, int sdkState);
    }

    /** Snapshot-level lock summary listener — called on every snapshot update
     *  whose lock data may have changed. Use this when you want a single
     *  cohesive view of all areas rather than per-area events. */
    public interface LockSnapshotListener {
        void onLockSnapshotUpdated(BydVehicleData snapshot);
    }

    /** Raw BMS charging-state edges from the charging HAL. Fires only on
     *  transitions (current != previous), not on every poll. State values
     *  match {@code ChargingStateData.CHARGING_BATTERY_STATE_*}. */
    public interface ChargingStateListener {
        void onChargingStateChanged(int previousState, int newState);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<DoorStateListener> doorStateListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<DoorLockListener> doorLockListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<LockSnapshotListener> lockSnapshotListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<ChargingStateListener> chargingStateListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addDoorStateListener(DoorStateListener l) { if (l != null) doorStateListeners.addIfAbsent(l); }
    public void removeDoorStateListener(DoorStateListener l) { doorStateListeners.remove(l); }
    public void addDoorLockListener(DoorLockListener l) { if (l != null) doorLockListeners.addIfAbsent(l); }
    public void removeDoorLockListener(DoorLockListener l) { doorLockListeners.remove(l); }
    public void addLockSnapshotListener(LockSnapshotListener l) { if (l != null) lockSnapshotListeners.addIfAbsent(l); }
    public void removeLockSnapshotListener(LockSnapshotListener l) { lockSnapshotListeners.remove(l); }
    public void addChargingStateListener(ChargingStateListener l) { if (l != null) chargingStateListeners.addIfAbsent(l); }
    public void removeChargingStateListener(ChargingStateListener l) { chargingStateListeners.remove(l); }

    private void notifyDoorStateListeners(int area, int state) {
        notePassengerDoorStateForSeatbelt(area, state);
        for (DoorStateListener l : doorStateListeners) {
            try { l.onDoorStateChanged(area, state); }
            catch (Exception e) { logger.debug("DoorStateListener error: " + e.getMessage()); }
        }
    }

    private void notifyDoorLockListeners(int area, int sdkState) {
        for (DoorLockListener l : doorLockListeners) {
            try { l.onDoorLockStatusChanged(area, sdkState); }
            catch (Exception e) { logger.debug("DoorLockListener error: " + e.getMessage()); }
        }
    }

    private void notifyLockSnapshotListeners(BydVehicleData snap) {
        for (LockSnapshotListener l : lockSnapshotListeners) {
            try { l.onLockSnapshotUpdated(snap); }
            catch (Exception e) { logger.debug("LockSnapshotListener error: " + e.getMessage()); }
        }
    }

    private void notifyChargingStateListeners(int previousState, int newState) {
        for (ChargingStateListener l : chargingStateListeners) {
            try { l.onChargingStateChanged(previousState, newState); }
            catch (Exception e) { logger.debug("ChargingStateListener error: " + e.getMessage()); }
        }
    }

    private BydDataCollector() {}

    public static BydDataCollector getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new BydDataCollector();
            }
        }
        return instance;
    }

    public static final String[] SNAPSHOT_FILE_PATHS = new String[] {
            "/storage/emulated/0/Android/data/com.overdrive.app/files/byd_telemetry_snap.json",
            "/storage/emulated/0/Overdrive/byd_telemetry_snap.json",
            "/data/local/tmp/byd_telemetry_snap.json"
    };

    public static void writeSnapshotDiskFile(BydVehicleData data) {
        if (data == null) return;
        byte[] bytes = data.toJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String path : SNAPSHOT_FILE_PATHS) {
            try {
                java.io.File file = new java.io.File(path);
                java.io.File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                java.io.File tmp = new java.io.File(path + ".tmp");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                    fos.write(bytes);
                    fos.flush();
                }
                tmp.renameTo(file);
                file.setReadable(true, false);
                file.setWritable(true, false);
            } catch (Throwable ignored) {}
        }
    }

    public static BydVehicleData readSnapshotDiskFile() {
        for (String path : SNAPSHOT_FILE_PATHS) {
            try {
                java.io.File file = new java.io.File(path);
                if (file.exists() && (System.currentTimeMillis() - file.lastModified()) <= 60_000) {
                    byte[] bytes = new byte[(int) file.length()];
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        fis.read(bytes);
                    }
                    String jsonStr = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    BydVehicleData parsed = BydVehicleData.fromJson(new org.json.JSONObject(jsonStr));
                    if (parsed != null) return parsed;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Get the latest vehicle data snapshot. Thread-safe. */
    public BydVehicleData getData() {
        BydVehicleData current = snapshot.get();
        if (current == null || current.tyrePressure == null) {
            BydVehicleData fromDisk = readSnapshotDiskFile();
            if (fromDisk != null) {
                if (current == null) {
                    snapshot.set(fromDisk);
                    return fromDisk;
                }
                BydVehicleData.Builder b = current.toBuilder();
                if (current.tyrePressure == null && fromDisk.tyrePressure != null) {
                    b.tyrePressure(fromDisk.tyrePressure);
                    b.tyrePressureState(fromDisk.tyrePressureState);
                    b.tyreTemperature(fromDisk.tyreTemperature);
                    b.tyreSystemState(fromDisk.tyreSystemState);
                }
                BydVehicleData merged = b.build();
                snapshot.set(merged);
                return merged;
            }
        }
        return current;
    }

    static BydVehicleData preserveNewerChargingEdge(BydVehicleData collected,
                                                     BydVehicleData latest,
                                                     long versionAtStart,
                                                     long versionNow) {
        return preserveNewerChargingEdges(collected, latest,
                versionAtStart, versionNow, versionAtStart, versionNow);
    }

    static BydVehicleData preserveNewerChargingEdges(BydVehicleData collected,
                                                      BydVehicleData latest,
                                                      long bmsVersionAtRead,
                                                      long bmsVersionNow,
                                                      long gunVersionAtRead,
                                                      long gunVersionNow) {
        return preserveNewerChargingEdges(collected, latest,
                bmsVersionAtRead, bmsVersionNow,
                gunVersionAtRead, gunVersionNow, 0L, 0L);
    }

    static BydVehicleData preserveNewerChargingEdges(BydVehicleData collected,
                                                      BydVehicleData latest,
                                                      long bmsVersionAtRead,
                                                      long bmsVersionNow,
                                                      long gunVersionAtRead,
                                                      long gunVersionNow,
                                                      long typeVersionAtRead,
                                                      long typeVersionNow) {
        if (collected == null || latest == null) return collected;
        boolean newerBms = bmsVersionAtRead != bmsVersionNow;
        boolean newerGun = gunVersionAtRead != gunVersionNow;
        boolean newerType = typeVersionAtRead != typeVersionNow;
        if (!newerBms && !newerGun && !newerType) return collected;
        BydVehicleData.Builder merged = collected.toBuilder();
        if (newerBms) {
            merged.chargingState(latest.chargingState)
                    .chargingStateAtMs(latest.chargingStateAtMs);
        }
        if (newerGun) {
            merged.chargingGunState(latest.chargingGunState);
        }
        if (newerType) {
            merged.chargingType(latest.chargingType);
        }
        int effectiveGun = newerGun ? latest.chargingGunState : collected.chargingGunState;
        int effectiveType = newerType ? latest.chargingType : collected.chargingType;
        merged.vtolCharging(effectiveGun == 5 || effectiveType == 3);
        return merged.build();
    }

    static void refreshChargingLifecycleContext(BydVehicleData.Builder target,
                                                BydVehicleData latest) {
        refreshChargingLifecycleContext(target, latest, true, true, true);
    }

    static void refreshChargingLifecycleContext(BydVehicleData.Builder target,
                                                BydVehicleData latest,
                                                boolean refreshBms,
                                                boolean refreshGun,
                                                boolean refreshType) {
        if (target == null || latest == null) return;
        if (refreshBms) {
            target.chargingState(latest.chargingState)
                    .chargingStateAtMs(latest.chargingStateAtMs);
        }
        if (refreshGun) target.chargingGunState(latest.chargingGunState);
        if (refreshType) target.chargingType(latest.chargingType);
        target.vtolCharging(target.chargingGunState == 5 || target.chargingType == 3);
    }

    static BydVehicleData clearChargingRateFields(BydVehicleData current,
                                                  boolean preserveExternalCounter) {
        return clearChargingRateFields(current, preserveExternalCounter, false);
    }

    static BydVehicleData clearChargingRateFields(BydVehicleData current,
                                                  boolean preserveExternalCounter,
                                                  boolean preserveFinishedMovementBaselines) {
        if (current == null) return null;
        BydVehicleData.Builder cleared = current.toBuilder()
                .chargingPowerKw(Double.NaN)
                .chargePowerKw(Double.NaN)
                .clusterChargePowerKw(Double.NaN)
                .enginePowerKw(Double.NaN);
        if (!preserveExternalCounter) {
            cleared.externalChargingPowerKw(Double.NaN);
        }
        if (!preserveFinishedMovementBaselines) {
            cleared.clearChargingRateMovement();
        }
        return cleared.build();
    }

    static BydVehicleData preserveNewerChargingRateClear(BydVehicleData collected,
                                                         BydVehicleData latest,
                                                         long versionAtStart,
                                                         long versionNow) {
        if (collected == null || latest == null || versionAtStart == versionNow) {
            return collected;
        }
        return collected.toBuilder()
                .chargingPowerKw(latest.chargingPowerKw)
                .chargingPowerAtMs(latest.chargingPowerAtMs)
                .chargingPowerChangedAtMs(latest.chargingPowerChangedAtMs)
                .chargingPowerLastObservedKw(latest.chargingPowerLastObservedKw)
                .externalChargingPowerKw(latest.externalChargingPowerKw)
                .externalChargingPowerAtMs(latest.externalChargingPowerAtMs)
                .externalChargingPowerChangedAtMs(latest.externalChargingPowerChangedAtMs)
                .externalChargingPowerLastObservedKw(
                        latest.externalChargingPowerLastObservedKw)
                .chargePowerKw(latest.chargePowerKw)
                .chargePowerAtMs(latest.chargePowerAtMs)
                .chargePowerChangedAtMs(latest.chargePowerChangedAtMs)
                .chargePowerLastObservedKw(latest.chargePowerLastObservedKw)
                .clusterChargePowerKw(latest.clusterChargePowerKw)
                .clusterChargePowerAtMs(latest.clusterChargePowerAtMs)
                .clusterChargePowerChangedAtMs(latest.clusterChargePowerChangedAtMs)
                .clusterChargePowerLastObservedKw(latest.clusterChargePowerLastObservedKw)
                .enginePowerKw(latest.enginePowerKw)
                .enginePowerAtMs(latest.enginePowerAtMs)
                .build();
    }

    static final class CounterCallbackReservation {
        final long observation;
        final long lifecycleGeneration;
        final String source;
        final double raw;
        final long observedAtMs;
        volatile boolean settledByTerminal;

        CounterCallbackReservation(long observation, long lifecycleGeneration,
                                   String source, double raw, long observedAtMs) {
            this.observation = observation;
            this.lifecycleGeneration = lifecycleGeneration;
            this.source = source;
            this.raw = raw;
            this.observedAtMs = observedAtMs;
        }
    }

    private static final class CounterReservationBatch {
        boolean hasCapacity;
        double capacityKwh = Double.NaN;
        long capacityAtMs;
        long capacityObservation;
        boolean hasExternal;
        double externalKwh = Double.NaN;
        long externalAtMs;
        long externalObservation;

        void include(CounterCallbackReservation reservation) {
            if (ChargeSourceClassifier.SRC_CAPACITY.equals(reservation.source)) {
                if (!hasCapacity || reservation.observation > capacityObservation) {
                    capacityKwh = reservation.raw;
                    capacityAtMs = reservation.observedAtMs;
                    capacityObservation = reservation.observation;
                }
                hasCapacity = true;
            } else if (ChargeSourceClassifier.SRC_EXTERNAL.equals(reservation.source)) {
                if (!hasExternal || reservation.observation > externalObservation) {
                    externalKwh = reservation.raw;
                    externalAtMs = reservation.observedAtMs;
                    externalObservation = reservation.observation;
                }
                hasExternal = true;
            }
        }
    }

    /**
     * Orders charging observations by when their hardware read or callback dispatch begins, not by
     * when a callback eventually acquires {@link #chargingEdgePublishLock}.
     */
    static final class ChargingObservationOrder {
        private final java.util.concurrent.atomic.AtomicLong sequence =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.ArrayList<CounterCallbackReservation> counterReservations =
                new java.util.ArrayList<>();
        private long latestBms;
        private long latestGun;

        long begin() {
            return sequence.incrementAndGet();
        }

        synchronized CounterCallbackReservation reserveCounterCallback(
                long lifecycleGeneration, String source, double raw) {
            CounterCallbackReservation reservation = new CounterCallbackReservation(
                    sequence.incrementAndGet(), lifecycleGeneration, source, raw,
                    System.currentTimeMillis());
            counterReservations.add(reservation);
            return reservation;
        }

        synchronized void completeCounterCallback(CounterCallbackReservation reservation) {
            if (reservation == null || reservation.settledByTerminal) return;
            counterReservations.remove(reservation);
        }

        synchronized CounterReservationBatch settleCounterCallbacks(
                long terminalObservation, long lifecycleGeneration, boolean account) {
            CounterReservationBatch batch = new CounterReservationBatch();
            java.util.Iterator<CounterCallbackReservation> iterator =
                    counterReservations.iterator();
            while (iterator.hasNext()) {
                CounterCallbackReservation reservation = iterator.next();
                if (reservation.lifecycleGeneration != lifecycleGeneration
                        || reservation.observation > terminalObservation) {
                    continue;
                }
                reservation.settledByTerminal = true;
                if (account) batch.include(reservation);
                iterator.remove();
            }
            return batch;
        }

        synchronized void discardCounterCallbacks() {
            for (CounterCallbackReservation reservation : counterReservations) {
                reservation.settledByTerminal = true;
            }
            counterReservations.clear();
        }

        synchronized void recordBmsPoll(long observation) {
            if (observation > latestBms) latestBms = observation;
        }

        synchronized void recordGunPoll(long observation) {
            if (observation > latestGun) latestGun = observation;
        }

        synchronized boolean claimBmsCallback(long observation) {
            if (observation < latestBms) return false;
            latestBms = observation;
            return true;
        }

        synchronized boolean claimGunCallback(long observation) {
            if (observation < latestGun) return false;
            latestGun = observation;
            return true;
        }

        synchronized long latestBms() {
            return latestBms;
        }

        synchronized long latestGun() {
            return latestGun;
        }
    }

    private static final class ChargingObservationVersions {
        final long bms;
        final long gun;
        final long bmsObservation;
        final long gunObservation;
        final long type;
        final long capacity;
        final long devicePower;
        final long rateClear;
        long externalPower;
        long enginePower;
        boolean externalPowerObserved;
        boolean enginePowerObserved;
        final boolean connectionObserved;
        final boolean typeObserved;
        final Boolean powerIsCharging;
        final boolean bmsObserved;
        final int observedBmsState;
        final boolean terminalRateBarrier;
        ChargingObservationVersions(long bms, long gun,
                                    long bmsObservation, long gunObservation,
                                    long type,
                                    long capacity, long devicePower, long rateClear,
                                    boolean connectionObserved,
                                    boolean typeObserved,
                                    Boolean powerIsCharging,
                                    boolean bmsObserved,
                                    int observedBmsState,
                                    boolean terminalRateBarrier) {
            this.bms = bms;
            this.gun = gun;
            this.bmsObservation = bmsObservation;
            this.gunObservation = gunObservation;
            this.type = type;
            this.capacity = capacity;
            this.devicePower = devicePower;
            this.rateClear = rateClear;
            this.connectionObserved = connectionObserved;
            this.typeObserved = typeObserved;
            this.powerIsCharging = powerIsCharging;
            this.bmsObserved = bmsObserved;
            this.observedBmsState = observedBmsState;
            this.terminalRateBarrier = terminalRateBarrier;
        }
    }

    private static final class ChargingPollEvidence {
        boolean connectionObserved;
        boolean typeObserved;
        Boolean powerIsCharging;
        boolean bmsObserved;
        int observedBmsState = BydVehicleData.UNAVAILABLE;
        long bmsObservation;
        long gunObservation;
        boolean terminalCapacityObserved;
    }

    private static final class ChargingCapacityReading {
        final double kwh;
        final String source;

        ChargingCapacityReading(double kwh, String source) {
            this.kwh = kwh;
            this.source = source;
        }

        boolean isValid() {
            return Double.isFinite(kwh)
                    && kwh >= 0.0
                    && kwh <= CHARGING_CAPACITY_MAX_KWH;
        }
    }

    /** Framework-declared range of the per-session charged-energy counter. */
    public static final double CHARGING_CAPACITY_MAX_KWH = 131.07;

    static final class ChargingPowerReading {
        final double raw;
        final String getter;

        ChargingPowerReading(double raw, String getter) {
            this.raw = raw;
            this.getter = getter;
        }

        boolean answered() {
            return getter != null;
        }
    }

    private BydVehicleData publishCollectedSnapshot(BydVehicleData collected,
                                                     ChargingObservationVersions observed,
                                                     long pollGeneration) {
        synchronized (chargingEdgePublishLock) {
            if (pollGeneration < lastPublishedChargingPollGeneration) {
                return snapshot.get(); // a newer concurrent poll already won
            }
            BydVehicleData previous = snapshot.get();
            BydVehicleData merged = preserveNewerChargingEdges(
                    collected, previous,
                    observed.bmsObservation, chargingObservationOrder.latestBms(),
                    observed.gunObservation, chargingObservationOrder.latestGun(),
                    observed.type, chargingTypeVersion.get());
            boolean newerCapacity = observed.capacity != capacityEdgeVersion.get();
            // A terminal poll is newer lifecycle evidence than any live-rate callback admitted
            // after its hardware read but before this final publication. Counters remain eligible
            // for their final tail, but live device/external/engine rates must not cross that fence.
            boolean newerDevicePower = !observed.terminalRateBarrier
                    && observed.devicePower != devicePowerEdgeVersion.get();
            boolean newerExternalPower =
                    (!observed.terminalRateBarrier
                            || ChargeSourceClassifier.isCounter(
                                    ChargeSourceClassifier.SRC_EXTERNAL))
                    && (!observed.externalPowerObserved
                            || observed.externalPower != externalPowerEdgeVersion.get());
            boolean newerEnginePower = !observed.terminalRateBarrier
                    && (!observed.enginePowerObserved
                            || observed.enginePower != enginePowerEdgeVersion.get());
            boolean newerRateClear =
                    observed.rateClear != chargingRateClearVersion.get();
            if (newerRateClear) {
                merged = preserveNewerChargingRateClear(
                        merged, previous, observed.rateClear, chargingRateClearVersion.get());
            }
            if (previous != null && (newerCapacity || newerDevicePower
                    || newerExternalPower || newerEnginePower)) {
                BydVehicleData.Builder latestValues = merged.toBuilder();
                if (newerCapacity) {
                    latestValues.chargingCapacityKwh(previous.chargingCapacityKwh);
                }
                if (newerDevicePower) {
                    latestValues.chargingPowerKw(previous.chargingPowerKw)
                            .chargingPowerAtMs(previous.chargingPowerAtMs)
                            .chargingPowerChangedAtMs(previous.chargingPowerChangedAtMs)
                            .chargingPowerLastObservedKw(
                                    previous.chargingPowerLastObservedKw);
                }
                if (newerExternalPower) {
                    latestValues.externalChargingPowerKw(previous.externalChargingPowerKw)
                            .externalChargingPowerAtMs(previous.externalChargingPowerAtMs)
                            .externalChargingPowerChangedAtMs(
                                    previous.externalChargingPowerChangedAtMs)
                            .externalChargingPowerLastObservedKw(
                                    previous.externalChargingPowerLastObservedKw);
                }
                if (newerEnginePower) {
                    latestValues.enginePowerKw(previous.enginePowerKw)
                            .enginePowerAtMs(previous.enginePowerAtMs);
                }
                merged = latestValues.build();
            }
            BydVehicleData published;
            try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                         com.overdrive.app.monitor.ChargingDetector.beginPublicationMutation()) {
                snapshot.set(merged);
                lastPublishedChargingPollGeneration = pollGeneration;
                // Confirm every successful hardware read, not only a snapshot edge. A positive
                // callback publishes into the snapshot before the next poll, so edge-only delivery
                // skipped the very poll that was supposed to corroborate a genuine reconnect.
                boolean stableGunObservation = observed.connectionObserved
                        && observed.gunObservation == chargingObservationOrder.latestGun()
                        && merged != null;
                boolean stableTypeObservation = observed.typeObserved
                        && observed.type == chargingTypeVersion.get() && merged != null;
                boolean stableBmsObservation = observed.bmsObserved
                        && observed.bmsObservation == chargingObservationOrder.latestBms();
                boolean cohesivePowerObservation =
                        observed.bmsObservation == chargingObservationOrder.latestBms()
                        && observed.gunObservation == chargingObservationOrder.latestGun()
                        && observed.type == chargingTypeVersion.get();
                // Snapshot publication and detector delivery are one externally fenced handoff.
                pushChargingEvidence(merged, observed,
                        stableGunObservation, stableTypeObservation,
                        stableBmsObservation, cohesivePowerObservation);
                published = snapshot.get();
                com.overdrive.app.monitor.ChargingDetector detector =
                        com.overdrive.app.monitor.ChargingDetector.getInstance();
                if (published != null && isPhev(published)
                        && detector.isCharging()
                        && !detector.isTerminalSessionBarrierActive()) {
                    // Resolver proof state is part of the same externally-observable charging
                    // publication. Keep it inside this mutation so a terminal callback cannot
                    // publish a stable stop between detector delivery and proof installation.
                    com.overdrive.app.monitor.VehicleDataMonitor.observePhevSessionRateProofs(
                            published, detector.getLastSessionStartedAtMs(),
                            System.currentTimeMillis());
                }
            }
            publishAutomationSnapshot(published);
            writeSnapshotDiskFile(published);
            return published;
        }
    }

    /**
     * Publish a callback that does not own charging fields without restoring an older charging
     * snapshot. Most HAL listeners build from a snapshot before acquiring any shared lock; a
     * FINISHED/gun-out/power callback can land in between that read and write.
     */
    private BydVehicleData publishNonChargingSnapshot(BydVehicleData candidate) {
        if (candidate == null) return null;
        synchronized (chargingEdgePublishLock) {
            BydVehicleData latest = snapshot.get();
            if (latest == null) {
                snapshot.set(candidate);
                publishAutomationSnapshot(candidate);
                return candidate;
            }
            // This callback owns no charging field. Always merge the complete charging surface from
            // the current snapshot; comparing only the primary power fields allowed a stale callback
            // candidate to roll back mode, percent, remaining time, or charger/wireless state.
            candidate = candidate.toBuilder()
                    .chargingState(latest.chargingState)
                    .chargingStateAtMs(latest.chargingStateAtMs)
                    .chargingGunState(latest.chargingGunState)
                    .chargerWorkState(latest.chargerWorkState)
                    .chargingMode(latest.chargingMode)
                    .chargingType(latest.chargingType)
                    .vtolCharging(latest.vtolCharging)
                    .chargingPowerKw(latest.chargingPowerKw)
                    .chargingPowerAtMs(latest.chargingPowerAtMs)
                    .chargingPowerChangedAtMs(latest.chargingPowerChangedAtMs)
                    .chargingPowerLastObservedKw(latest.chargingPowerLastObservedKw)
                    .externalChargingPowerKw(latest.externalChargingPowerKw)
                    .externalChargingPowerAtMs(latest.externalChargingPowerAtMs)
                    .externalChargingPowerChangedAtMs(
                            latest.externalChargingPowerChangedAtMs)
                    .externalChargingPowerLastObservedKw(
                            latest.externalChargingPowerLastObservedKw)
                    .chargingCapacityKwh(latest.chargingCapacityKwh)
                    .chargePowerKw(latest.chargePowerKw)
                    .chargePowerAtMs(latest.chargePowerAtMs)
                    .chargePowerChangedAtMs(latest.chargePowerChangedAtMs)
                    .chargePowerLastObservedKw(latest.chargePowerLastObservedKw)
                    .clusterChargePowerKw(latest.clusterChargePowerKw)
                    .clusterChargePowerAtMs(latest.clusterChargePowerAtMs)
                    .clusterChargePowerChangedAtMs(latest.clusterChargePowerChangedAtMs)
                    .clusterChargePowerLastObservedKw(
                            latest.clusterChargePowerLastObservedKw)
                    .enginePowerKw(latest.enginePowerKw)
                    .enginePowerAtMs(latest.enginePowerAtMs)
                    .chargingRestTimeHours(latest.chargingRestTimeHours)
                    .chargingRestTimeMinutes(latest.chargingRestTimeMinutes)
                    .chargingPercent(latest.chargingPercent)
                    .wirelessChargingLeftState(latest.wirelessChargingLeftState)
                    .wirelessChargingRightState(latest.wirelessChargingRightState)
                    .wirelessChargingStatus(latest.wirelessChargingStatus)
                    .insideTempC(latest.insideTempC, latest.insideTempReadAt)
                    .build();
            snapshot.set(candidate);
            publishAutomationSnapshot(candidate);
            return candidate;
        }
    }

    private void publishAutomationSnapshot(BydVehicleData published) {
        if (published == null) return;
        try {
            com.overdrive.app.automation.condition.BydEvent.bydEvent(published);
        } catch (Throwable t) {
            logger.debug("Automation snapshot publish error: " + t.getMessage());
        }
    }

    /** Check if the collector has been initialized. */
    public boolean isInitialized() {
        return initialized;
    }

    // ==================== INITIALIZATION ====================

    /**
     * Initialize all BYD devices. Each device is independent — failures are logged and skipped.
     */
    public synchronized void init(Context context) {
        if (initialized && this.context == context) {
            return;
        }
        // A callback queued from the previous context must not publish while its devices are
        // being replaced. Take the same lock hierarchy as stop(): a callback that already passed
        // its optimistic check either finishes before this barrier or carries the prior lifecycle
        // epoch and fails its final validation after reactivation.
        deactivateCallbackPublication();
        pollSchedulerGeneration.incrementAndGet();
        cancelPersistedDaemonEnergyReconciliation(true);
        this.context = context;
        com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(context);
        ChargeSourceClassifier.initializePersistence(context);
        logger.info("=== BYD Data Collector Initializing ===");
        long start = System.currentTimeMillis();

        // Re-init: tear down state that would otherwise accumulate.
        availableDevices.clear();
        unavailableDevices.clear();
        // Manager-channel handles were resolved from the PREVIOUS Context and are latched
        // once-per-process, so without this they survive a re-init. That is exactly wrong for the
        // path that matters: the daemon can start with a broken synthetic context (the observed
        // "0/17 devices" case), latch "no manager available", and then ACC-ON replaces the context
        // and re-inits — devices resolve, but the manager channel would stay permanently dead on
        // precisely the vehicle this feature exists for. Re-probing is cheap (one-shot per handle).
        BydManagerChannel.invalidate();
        // A new Context is genuine new information, so bypass any dead-binder cool-down that
        // invalidate() just armed — it was armed against the OLD context and waiting it out here
        // would delay exactly the recovery this re-init exists to perform.
        BydManagerChannel.allowImmediateReprobe();
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
            pollScheduler = null;
        }

        // Initialize each device type. Charging-critical listeners are identity/generation-fenced:
        // an old HAL handle can remain registered after re-init, but it must never remain an
        // authorized publisher once a replacement handle is installed.
        Object previousEngineDevice = engineDevice;
        Object previousChargingDevice = chargingDevice;
        Object previousInstrumentDevice = instrumentDevice;
        bodyworkDevice = initDevice("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "Bodywork");
        rearViewMirrorDevice = initDevice(
                BydConstants.REAR_VIEW_MIRROR_DEVICE_CLASS, "RearViewMirror");
        speedDevice = initDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", "Speed");
        engineDevice = initDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", "Engine");
        statisticDevice = initDevice("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", "Statistic");
        chargingDevice = initDevice("android.hardware.bydauto.charging.BYDAutoChargingDevice", "Charging");
        if (chargingDevice != previousChargingDevice) {
            resetChargeCapVerification();
            activeChargingListenerDevice = null;
            activeChargingListenerGeneration = chargingListenerGeneration.incrementAndGet();
        }
        instrumentDevice = initDevice("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", "Instrument");
        if (instrumentDevice != previousInstrumentDevice) {
            activeInstrumentListenerDevice = null;
            activeInstrumentListenerGeneration = instrumentListenerGeneration.incrementAndGet();
        }
        if (engineDevice != previousEngineDevice) {
            activeEngineListenerDevice = null;
            activeEngineListenerGeneration = engineListenerGeneration.incrementAndGet();
        }
        otaDevice = initDevice("android.hardware.bydauto.ota.BYDAutoOtaDevice", "OTA");
        gearboxDevice = initDevice("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", "Gearbox");
        acDevice = initDevice("android.hardware.bydauto.ac.BYDAutoAcDevice", "AC");
        pm25Device = initDevice("android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device", "PM2.5");
        lightDevice = initDevice("android.hardware.bydauto.light.BYDAutoLightDevice", "Light");
        wiperDevice = initDevice("android.hardware.bydauto.wiper.BYDAutoWiperDevice", "Wiper");
        adasDevice = initDevice("android.hardware.bydauto.adas.BYDAutoADASDevice", "ADAS");
        // Drop the blind-spot counter baselines on every (re-)init. init() re-runs
        // on the ACC-ON edge, and if the firmware zeroes these per-drive counters
        // across an ACC cycle, a baseline carried over from the previous drive is
        // HIGHER than the fresh reading — the first alert of the new drive then
        // looks like a decrease and is silently dropped. Clearing means the first
        // post-init read only re-establishes the baseline (no false alert) and the
        // next genuine increment is caught.
        bsCounterBaseline.clear();
        // Also re-prove the event path per ACC cycle. The listener is re-registered
        // here, and if that registration now fails (or this firmware stops
        // delivering), an id left marked "proven" would be neither polled nor
        // evented — silently dead. Re-earning the exemption keeps the fallback
        // honest at the cost of a few polls per drive.
        bsEventProvenIds.clear();
        powerDevice = initDevice("android.hardware.bydauto.power.BYDAutoPowerDevice", "Power");
        Object previousSafetyBeltDevice = safetyBeltDevice;
        Object nextSafetyBeltDevice = initDevice(
                "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice", "SafetyBelt");
        if (previousSafetyBeltDevice != null && previousSafetyBeltDevice != nextSafetyBeltDevice) {
            if (BydDeviceHelper.unregisterSafetyBeltListener(previousSafetyBeltDevice)) {
                registeredHandles.remove(previousSafetyBeltDevice);
            }
            activeSafetyBeltListenerDevice = null;
            activeSafetyBeltListenerGeneration = safetyBeltListenerGeneration.incrementAndGet();
        }
        safetyBeltDevice = nextSafetyBeltDevice;
        if (previousSafetyBeltDevice != nextSafetyBeltDevice) {
            resetPassengerSeatbeltState();
            pollPassengerDoorStateForSeatbeltNow();
        }
        tyreDevice = initDevice("android.hardware.bydauto.tyre.BYDAutoTyreDevice", "Tyre");
        doorLockDevice = initDevice("android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice", "DoorLock");
        sensorDevice = initDevice("android.hardware.bydauto.sensor.BYDAutoSensorDevice", "Sensor");
        Object previousEnergyDevice = energyDevice;
        energyDevice = initDevice("android.hardware.bydauto.energy.BYDAutoEnergyDevice", "Energy");
        if (energyDevice != previousEnergyDevice) {
            lastEnergyModeEvent = -1;
            lastEnergyOperationModeEvent = -1;
            lastEnergyRoadSurfaceEvent = -1;
            lastNormalEcoDriveMode = -1;
            lastModeDiagnosticState = null;
        }
        radarDevice = initDevice("android.hardware.bydauto.radar.BYDAutoRadarDevice", "Radar");
        Object previousSettingDevice = settingDevice;
        settingDevice = initDevice("android.hardware.bydauto.setting.BYDAutoSettingDevice", "Setting");
        collectDataDevice = initDevice("android.hardware.bydauto.collectdata.BYDAutoCollectDataDevice", "CollectData");
        if (settingDevice != previousSettingDevice) {
            acChargingCurrentLimitSupported = null;
        }
        if (energyDevice != previousEnergyDevice || settingDevice != previousSettingDevice) {
            resetDriveModeDiagnosticProbes();
        }
        multimediaDevice = initMultimediaDevice();
        // Multimedia resolves through its own multi-strategy path (4 separate accept sites), so it
        // bypasses initDevice() and would otherwise be the ONE device never activated or type-learned
        // — inconsistent with "every telemetry device" and a gap a future reader would trip over.
        // Done once here rather than at each accept site.
        if (multimediaDevice != null) {
            BydManagerChannel.rememberDeviceType(
                    "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice", multimediaDevice);
            try {
                BydManagerChannel.enableDevice(context, multimediaDevice, "Multimedia");
            } catch (Throwable t) {
                logger.debug("enableDevice[Multimedia] threw: " + t.getMessage());
            }
        }

        logger.info("Devices available: " + availableDevices.size() + "/" +
            (availableDevices.size() + unavailableDevices.size()));
        if (!unavailableDevices.isEmpty()) {
            logger.info("Unavailable: " + String.join(", ", unavailableDevices));
        }

        // Detect mileage unit from instrument cluster
        detectMileageUnit();

        // If auto-detection failed, fall back to user's persisted preference
        if (!unitDetected) {
            try {
                com.overdrive.app.trips.TripConfig tripConfig = new com.overdrive.app.trips.TripConfig();
                tripConfig.load();
                String savedUnit = tripConfig.getDistanceUnit();
                if ("mi".equals(savedUnit)) {
                    distanceToKmFactor = MILES_TO_KM;
                    unitDetected = true;
                    logger.info("Mileage unit: MILES (from user config override, factor=" + MILES_TO_KM + ")");
                }
            } catch (Exception e) {
                logger.info("Could not load distance unit from TripConfig: " + e.getMessage());
            }
        }

        // Read initial values (full collection including display-only devices)
        collectAllFull();
        // A daemon restart loses the process-local charge-stop proof although
        // the vehicle keeps its configured limit. Reapply the currently-read
        // limit and require matching capacity and switch readback before
        // exposing the controls again; never persist a prior positive result.
        reprobeChargeCapFromCurrentState();
        reconcilePersistedDaemonEnergyState();

        // Dump all battery/energy related getter methods on key devices
        // to discover the correct remaining kWh API at runtime
        // Discovery methods removed — getBatteryRemainPowerEV() confirmed as correct BEV API.
        // BYD light/setting APIs have no write access from UID 2000.

        // Register listeners — ONCE PER DISTINCT SET OF HANDLES.
        //
        // init() is re-entered on the ACC-ON path with a fresh Context, and registerAllListeners()
        // has ~22 register call sites with NO unregister path anywhere in this class. The device
        // accessors are singletons (getInstance), so a re-init usually hands back the SAME device
        // objects — re-registering on them stacks a second set of callbacks on the HAL, and the
        // consumers include the door/charging event notifiers, so every push notification would
        // then fire twice (three times after two re-inits, and so on).
        //
        // Tracked PER HANDLE, not as one all-or-nothing fingerprint. A whole-set fingerprint was
        // wrong in both directions:
        //   - it latched PARTIAL FAILURE. Every register* helper returns false on failure (dead
        //     binder, no register method) and the call site merely skips its counter — so a first
        //     init under the broken synthetic Context could fail to attach the bodywork/charging/
        //     instrument listeners, and because the singleton handles were unchanged the re-init
        //     skipped the retry and those callbacks went silent for the process lifetime. That is
        //     strictly worse than the duplicate callbacks the guard was added to prevent: it trades
        //     "fires twice" for "never fires".
        //   - it was all-or-nothing, so ONE changed handle (multimediaDevice is the likeliest, it has
        //     a createPackageContext fallback) forced re-registration on the other 18 unchanged
        //     handles — re-introducing duplicates via a device the guard doesn't even register on.
        // registerAllListeners() consults registeredHandles and skips only the individual devices it
        // has already attached to successfully, so a failed device is retried on the next init and a
        // successful one is never doubled.
        registerAllListeners();

        // Runtime receiver for power-cable plug edges. Manifest receiver
        // BootReceiver already covers cold-boot delivery, but Android
        // delivers POWER_CONNECTED/DISCONNECTED to runtime-registered
        // receivers more reliably while the process is alive — and the
        // ChargingDetector needs these edges within milliseconds of the
        // user plugging in so the fused state doesn't lag a 5s collect
        // cycle waiting for BMS to catch up.
        registerPlugEdgeReceiver();

        // Bridge BYD door-state events to push notifications. Safe to start
        // here — the door listener is only invoked once the bodywork HAL
        // fires onDoorStateChanged, which requires registerAllListeners to
        // have run first.
        com.overdrive.app.notifications.DoorEventNotifier.start();
        com.overdrive.app.notifications.ChargingEventNotifier.start();

        // Callbacks are active before the final charging read. A FINISHED edge delivered while
        // listeners were being registered may have been intentionally dropped while initialized=false;
        // this narrow reconciliation closes that handoff without repeating every display-only getter.
        activateCallbackPublication();
        reconcileChargingAfterCallbackActivation();

        // Start periodic polling to keep data fresh (listeners may not fire for all values).
        startPolling();

        long elapsed = System.currentTimeMillis() - start;
        logger.info("=== BYD Data Collector Ready (" + elapsed + "ms) ===");
    }

    /**
     * Publication lock order for lifecycle transitions:
     * collector monitor -> chargingStateTransitionLock -> chargingEdgePublishLock
     * -> passengerOccupancyPublishLock.
     *
     * Callback paths never acquire the collector monitor, and BMS callbacks take transition before
     * edge, so this matches stop() and does not introduce an edge/transition inversion.
     */
    private void deactivateCallbackPublication() {
        synchronized (chargingStateTransitionLock) {
            synchronized (chargingEdgePublishLock) {
                synchronized (passengerOccupancyPublishLock) {
                    initialized = false;
                    callbackLifecycleGeneration.incrementAndGet();
                    chargingObservationOrder.discardCounterCallbacks();
                    clearDevicePowerCallbackOriginLocked();
                }
            }
        }
    }

    private void activateCallbackPublication() {
        synchronized (chargingStateTransitionLock) {
            synchronized (chargingEdgePublishLock) {
                synchronized (passengerOccupancyPublishLock) {
                    initialized = true;
                }
            }
        }
    }

    private boolean isCallbackLifecycleCurrent(long lifecycleGeneration) {
        return initialized && lifecycleGeneration == callbackLifecycleGeneration.get();
    }

    /**
     * Re-read only the charging device after callback activation. The builder starts from the latest
     * published snapshot, and the normal ordered publication path supplies detector reconciliation.
     */
    private synchronized void reconcileChargingAfterCallbackActivation() {
        if (!initialized) return;
        try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                     com.overdrive.app.monitor.ChargingDetector.beginPublicationMutation()) {
            long pollGeneration = chargingPollGeneration.incrementAndGet();
            BydVehicleData current = snapshot.get();
            BydVehicleData.Builder b =
                    current != null ? current.toBuilder() : new BydVehicleData.Builder();
            ChargingObservationVersions observed = collectChargingOrdered(b);
            publishCollectedSnapshot(b.build(), observed, pollGeneration);
        }
    }

    /**
     * Detect whether the BYD instrument cluster is configured for miles or km.
     * getMileageUnit() returns 1 for km, 0 for miles.
     * If detection fails, defaults to km (factor = 1.0).
     */
    private void detectMileageUnit() {
        if (instrumentDevice == null) {
            logger.info("Mileage unit: defaulting to km (no instrument device)");
            return;
        }
        try {
            Object unitVal = BydDeviceHelper.callGetter(instrumentDevice, "getMileageUnit");
            if (unitVal instanceof Number) {
                int unit = ((Number) unitVal).intValue();
                if (unit == 0) {
                    // Miles mode
                    distanceToKmFactor = MILES_TO_KM;
                    unitDetected = true;
                    // Authoritative HARDWARE factor for the speed badge (never touched
                    // by the app-preference override).
                    speedHwFactor = MILES_TO_KM;
                    hwUnitDetected = true;
                    logger.info("Mileage unit: MILES detected (factor=" + MILES_TO_KM + ")");
                } else if (unit == 1) {
                    // km mode
                    distanceToKmFactor = 1.0;
                    unitDetected = true;
                    speedHwFactor = 1.0;
                    hwUnitDetected = true;
                    logger.info("Mileage unit: KM detected (factor=1.0)");
                } else {
                    // Unrecognized / in-band SDK sentinel (getMileageUnit can return a
                    // non-zero garbage value on flaky trims, e.g. SDK_NOT_AVAILABLE).
                    // Do NOT latch the HARDWARE flag: leave hwUnitDetected=false so the
                    // speed badge shows "--" instead of a possibly-1.6×-wrong number
                    // (readCurrentSpeedKmh returns NaN when the true cluster unit is
                    // unknown — it does NOT consult the app override, which can't
                    // disambiguate the raw unit).
                    // For the DISPLAY factor (distanceToKmFactor, used by odometer/
                    // distance reads): default to km ONLY on a FRESH detect. If a PRIOR
                    // init already detected a good unit (unitDetected), PRESERVE it — a
                    // flaky re-init returning garbage must not clobber a known-good MILES
                    // factor back to km and silently halve every distance read.
                    if (!unitDetected) {
                        distanceToKmFactor = 1.0;
                        logger.info("Mileage unit: unrecognized getMileageUnit=" + unit
                                + " — defaulting display to km, HW unit undetected");
                    } else {
                        logger.info("Mileage unit: unrecognized getMileageUnit=" + unit
                                + " on re-init — preserving prior factor=" + distanceToKmFactor);
                    }
                }
            } else {
                logger.info("Mileage unit: defaulting to km (getMileageUnit returned null)");
            }
        } catch (Exception e) {
            logger.info("Mileage unit: defaulting to km (detection failed: " + e.getMessage() + ")");
        }
    }

    /**
     * Get the distance-to-km conversion factor.
     * Returns 1.0 if km, 1.60934 if miles.
     * Used by OdometerReader and other components that read BYD distance values directly.
     */
    public double getDistanceToKmFactor() {
        return distanceToKmFactor;
    }

    /**
     * Speed-unit factor for INTERPRETING a raw {@code getCurrentSpeed()} reading
     * (recording overlay + trip telemetry path). The raw reading's unit is fixed
     * by the CLUSTER hardware ({@code getMileageUnit}), NOT the app's km/mi display
     * preference — so this returns the HARDWARE factor ({@link #speedHwFactor}) when
     * hardware detection succeeded, mirroring {@link #readCurrentSpeedKmh()}.
     *
     * <p>Using {@link #distanceToKmFactor} here (as the telemetry path historically
     * did) is a bug: {@link #setDistanceUnitOverride} drives it from the user's
     * DISPLAY preference, which is ambiguous about the raw unit. When the display
     * preference diverges from the cluster's real unit (e.g. km cluster, user picks
     * mi), the raw reading gets scaled by ~1.6× before the overlay re-derives the
     * display value — showing a confidently-wrong speed.
     *
     * <p>Falls back to {@link #distanceToKmFactor} ONLY when hardware detection never
     * succeeded ({@code !hwUnitDetected}) — best-effort on trims where
     * {@code getMileageUnit} is unavailable; behavior there is unchanged.
     */
    public double getSpeedToKmhFactor() {
        return hwUnitDetected ? speedHwFactor : distanceToKmFactor;
    }

    /** Convert one raw speed reading to km/h, rejecting every non-reading consistently. */
    static double convertRawSpeedToKmh(double raw, double factor) {
        if (raw == BydFeatureIds.SDK_NOT_AVAILABLE
                || Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0.0
                || Double.isNaN(factor) || Double.isInfinite(factor) || factor <= 0.0) {
            return Double.NaN;
        }
        return raw * factor;
    }

    /**
     * Override the distance unit from user settings. Called when the user
     * explicitly selects km or miles in the Trip Settings UI. This fixes the
     * case where auto-detection via getMileageUnit() fails (instrumentDevice
     * null, SDK returns null, etc.) and the raw miles values pass through
     * unconverted.
     *
     * @param unit "mi" for miles (factor=1.60934), "km" for km (factor=1.0)
     */
    public void setDistanceUnitOverride(String unit) {
        if ("mi".equals(unit)) {
            distanceToKmFactor = MILES_TO_KM;
            unitDetected = true;
            logger.info("Distance unit OVERRIDE: MILES (factor=" + MILES_TO_KM + ")");
        } else {
            distanceToKmFactor = 1.0;
            unitDetected = true;
            logger.info("Distance unit OVERRIDE: KM (factor=1.0)");
        }
    }

    /**
     * Returns true if the vehicle's instrument cluster is configured for miles.
     * Used by the /status API to tell the web UI which display unit to use.
     */
    public boolean isMilesMode() {
        return distanceToKmFactor > 1.0;
    }

    private java.util.concurrent.ScheduledExecutorService pollScheduler;
    /**
     * Upper bound on a RAW charging-rate reading whose unit is not yet known, in raw units.
     *
     * <p>500 kW is the resolved-rate ceiling, but a raw reading may be in hectowatts — so the same
     * physical rate arrives as a number 100x larger. Bounding the raw value at 500 therefore discarded
     * every hectowatt reading above 5 kW, including ordinary three-phase AC (raw 700 = 7.0 kW), before
     * {@link com.overdrive.app.monitor.ChargeRateResolver} could calibrate it. The resolved figure is
     * still bounded at 500 kW downstream, so widening here cannot let an implausible rate through.
     */
    private static final double RAW_RATE_ENVELOPE_MAX = 500.0 * 100.0;
    /** Must match VehicleDataMonitor's resolver key for InstrumentDevice.getChargePower(). */
    static final String SRC_PACK_SIDE_DIRECT = "__packSideDirect";

    private static final long POLL_INTERVAL_MS = 5000; // 5 seconds when ACC on
    private static final long POLL_INTERVAL_PARKED_MS = 90000; // 90 seconds when ACC off — listener callbacks keep the snapshot fresh between polls
    private String lastSummaryHash = "";

    // ==================== RoadSense fast dynamics poll ====================
    // RoadSense needs brake/accel/gear event-aligned to ~200 ms jolts (R-PERF-4),
    // but the main 5 s poll is far too coarse and we must NOT speed the whole poll
    // up (battery/SDK load) just for one consumer. So we expose an OPT-IN, narrowly
    // scoped fast poll that reads ONLY the four signals RoadSense uses
    // (brake %, accel %, gear, speed) via the device handles the collector already
    // holds, and publishes them to a SEPARATE lightweight atomic — never touching
    // the main snapshot, so no other consumer's freshness/values change. Started by
    // RoadSenseController only while RoadSense is ENABLED and the regime is DRIVING;
    // stopped otherwise. Zero cost when RoadSense is off.

    /** Immutable fast-dynamics tuple — only the fields RoadSense rejection needs. */
    public static final class FastDynamics {
        public final double speedKmh;
        public final int accelPercent;
        public final int brakePercent;
        public final int gearMode;
        public final long timestamp;
        FastDynamics(double speedKmh, int accelPercent, int brakePercent, int gearMode, long timestamp) {
            this.speedKmh = speedKmh; this.accelPercent = accelPercent;
            this.brakePercent = brakePercent; this.gearMode = gearMode; this.timestamp = timestamp;
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<FastDynamics> fastDynamics =
            new java.util.concurrent.atomic.AtomicReference<>(null);
    private java.util.concurrent.ScheduledExecutorService fastPollScheduler;
    /** Fast-poll cadence: 250 ms ≈ event-aligned for ~200 ms jolts without hammering
     *  the SDK (4 Hz on three cheap getters, vs the 5 s full poll). */
    private static final long FAST_POLL_INTERVAL_MS = 250;

    /**
     * Latest RoadSense fast-dynamics tuple, or null if the fast poll isn't running
     * (RoadSense disabled / not driving). Consumers must treat null as "use the main
     * snapshot instead". Lock-free.
     */
    public FastDynamics getFastDynamics() {
        return fastDynamics.get();
    }

    /**
     * Current vehicle speed in km/h for the cluster speed badge — self-contained, so
     * it does NOT depend on RoadSense's {@link #startFastDynamicsPoll() fast poll}
     * being active (that poll only runs while RoadSense is enabled + driving).
     *
     * <p>This is a SINGLE live SDK read of {@code getCurrentSpeed}, scaled ONLY by the
     * HARDWARE-detected unit factor ({@link #speedHwFactor} from {@code getMileageUnit}).
     * It is NEVER scaled by {@link #distanceToKmFactor} when that has been driven by the
     * app's km/mi DISPLAY preference, because the app preference is fundamentally
     * AMBIGUOUS about the raw unit — "user picked mi" could mean "my cluster reads
     * miles" OR "my cluster reads km but I want mph shown", and those are
     * indistinguishable. Only hardware detection knows the true raw unit.
     *
     * <p>So:
     * <ul>
     *   <li>hardware unit detected ({@link #hwUnitDetected}) → scale by
     *       {@link #speedHwFactor} → TRUE km/h; the overlay then applies the single
     *       display km↔mph conversion.</li>
     *   <li>hardware unit NOT detected (getMileageUnit failed / returned garbage) →
     *       the raw unit is genuinely UNKNOWN, so return {@link Double#NaN} → the badge
     *       shows "--". Assuming km would read ~1.6× LOW on a real miles cluster, and
     *       the app preference can't disambiguate it — a blank speedometer is safer than
     *       a confidently-wrong one (matches this collector's "-- over a wrong number"
     *       philosophy).</li>
     * </ul>
     * The cached {@code fastDynamics}/{@code snapshot} values are deliberately NOT used
     * as a fallback (they are pre-scaled by the possibly-overridden
     * {@link #distanceToKmFactor}, so they can be unit-contaminated). A transient SDK
     * miss therefore returns NaN → the badge shows "--" for that ~500 ms tick and
     * self-corrects. NaN is also returned when the trim has no speed device / SDK
     * unavailable / ACC off. Lock-free; safe from the overlay's 2 Hz thread.
     */
    public double readCurrentSpeedKmh() {
        // Only a HARDWARE-detected unit is trustworthy for the raw value. Without it the
        // unit is unknown → NaN ("--"), never a guess (km would be ~1.6× low on a miles
        // cluster; the app preference can't disambiguate the raw unit).
        if (!hwUnitDetected) return Double.NaN;
        try {
            if (speedDevice != null) {
                Object sp = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
                if (sp instanceof Number) {
                    double v = ((Number) sp).doubleValue();
                    return convertRawSpeedToKmh(v, speedHwFactor);
                }
            }
        } catch (Throwable ignored) {}
        return Double.NaN;
    }

    /**
     * Start the narrowly-scoped fast dynamics poll (idempotent). Reads ONLY
     * brake/accel/gear/speed from the already-resolved device handles. Safe to call
     * from any thread; a no-op if already running or if the speed device isn't
     * available on this trim.
     */
    public synchronized void startFastDynamicsPoll() {
        if (fastPollScheduler != null) return;       // already running
        if (speedDevice == null && gearboxDevice == null) return; // nothing to poll on this trim
        fastPollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RoadSenseFastPoll");
            t.setDaemon(true);
            return t;
        });
        fastPollScheduler.scheduleWithFixedDelay(() -> {
            try {
                double speedKmh = Double.NaN;
                int accel = BydVehicleData.UNAVAILABLE;
                int brake = BydVehicleData.UNAVAILABLE;
                int gear = BydVehicleData.UNAVAILABLE;
                if (speedDevice != null) {
                    Object sp = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
                    if (sp instanceof Number) {
                        double v = ((Number) sp).doubleValue();
                        speedKmh = convertRawSpeedToKmh(v, getSpeedToKmhFactor());
                    }
                    Object ac = BydDeviceHelper.callGetter(speedDevice, "getAccelerateDeepness");
                    if (ac instanceof Number) accel = ((Number) ac).intValue();
                    Object br = BydDeviceHelper.callGetter(speedDevice, "getBrakeDeepness");
                    if (br instanceof Number) brake = ((Number) br).intValue();
                }
                if (gearboxDevice != null) {
                    Object g = BydDeviceHelper.callGetter(gearboxDevice, "getGearboxAutoModeType");
                    if (g instanceof Number) gear = ((Number) g).intValue();
                }
                // Fall back to the last main-snapshot value for any field the fast
                // read couldn't get, so a momentary SDK miss doesn't blank a signal.
                BydVehicleData snap = snapshot.get();
                if (Double.isNaN(speedKmh) && snap != null) speedKmh = snap.speedKmh;
                if (accel == BydVehicleData.UNAVAILABLE && snap != null) accel = snap.accelPercent;
                if (brake == BydVehicleData.UNAVAILABLE && snap != null) brake = snap.brakePercent;
                if (gear == BydVehicleData.UNAVAILABLE && snap != null) gear = snap.gearMode;
                fastDynamics.set(new FastDynamics(speedKmh, accel, brake, gear, System.currentTimeMillis()));
            } catch (Throwable t) {
                logger.debug("Fast dynamics poll error: " + t.getMessage());
            }
        }, 0, FAST_POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        logger.info("RoadSense fast dynamics poll started (" + FAST_POLL_INTERVAL_MS + "ms)");
    }

    /** Stop the fast dynamics poll and clear its snapshot (idempotent). */
    public synchronized void stopFastDynamicsPoll() {
        if (fastPollScheduler != null) {
            fastPollScheduler.shutdownNow();
            fastPollScheduler = null;
            logger.info("RoadSense fast dynamics poll stopped");
        }
        fastDynamics.set(null);
    }

    // ── Turn-indicator read (Blind Spot) ─────────────────────────────────────
    // The main light poll runs on the 5s full-snapshot cadence — far too slow to
    // pop the blind-spot overlay the instant the driver flicks the indicator.
    // The overlay (app process, no BYD handles) instead reads the lamps on-demand
    // over the daemon's loopback /api/stream/turn at its own 250ms tick, so there
    // is exactly ONE cadence and NO background scheduler here — the daemon just
    // answers each read inline.

    /** Read the turn lamps inline (no background scheduler), packed bit0=L, bit1=R
     *  (so 0=none, 1=left, 2=right, 3=both/hazard). Returns -1 if the light device
     *  is unavailable.
     *
     *  Uses getTurnLightFlashState() — a SINGLE combined enum — NOT the per-side
     *  getTurnLightState(1/2), which on this BYD firmware does not reflect the
     *  blinking indicator (it returned 0 even with the indicator on, so the
     *  blind-spot overlay never popped on a real turn signal — only debugPreview
     *  worked). TelemetryDataCollector uses this same getter+enum and is proven to
     *  detect turn signals reliably. Flash-state enum: 2|3=left, 4|5=right,
     *  6|7=hazard (both). Caller bridges the blink off-phase (the lamp toggles
     *  ~1.5Hz) via its own off-debounce. */
    public int readTurnNow() {
        if (lightDevice == null) return -1;
        try {
            Object fs = BydDeviceHelper.callGetter(lightDevice, "getTurnLightFlashState");
            // -1, not 0: the device exists but the getter is absent on this trim (or threw), and 0
            // means "no indicator lit" — a definite answer the callers act on. Returning it would
            // cancel a live turn state and make the snapshot fallback unreachable.
            if (!(fs instanceof Number)) return -1;
            int flashState = ((Number) fs).intValue();
            // A sentinel is not a flash state. Falling through would leave every side false and
            // publish a confident "no indicator" from an unset rail. Both 16-bit rails.
            if (flashState < 0 || flashState == 65535 || flashState == 65534) return -1;
            boolean left = (flashState == 2 || flashState == 3);
            boolean right = (flashState == 4 || flashState == 5);
            if (flashState == 6 || flashState == 7) { left = true; right = true; }  // hazard
            int packed = 0;
            if (left) packed |= 0x1;
            if (right) packed |= 0x2;
            return packed;
        } catch (Throwable t) {
            logger.debug("readTurnNow error: " + t.getMessage());
            return -1;
        }
    }

    // ── Blind-spot / lane-change / cross-traffic warning reads ───────────────
    // Packed bits identify the warning family and side; -1 = ADAS unavailable.
    public static final int BS_LEFT_BIT = 0x1;
    public static final int BS_RIGHT_BIT = 0x2;
    public static final int RCTA_LEFT_BIT = 0x4;
    public static final int RCTA_RIGHT_BIT = 0x8;
    public static final int DOW_LEFT_BIT = 0x10;
    public static final int DOW_RIGHT_BIT = 0x20;

    // The per-side alert IDs. FL/FR are level-encoded; the rest are counters.
    private static final int[] BS_LEVEL_IDS_LEFT = { BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM };
    private static final int[] BS_LEVEL_IDS_RIGHT = { BydFeatureIds.ADAS_FR_BLIND_SPOT_ALARM };
    private static final int[] BS_COUNTER_IDS_LEFT = { BydFeatureIds.ADAS_LCA_WARNING_LEFT };
    private static final int[] BS_COUNTER_IDS_RIGHT = { BydFeatureIds.ADAS_LCA_WARNING_RIGHT };
    private static final int[] RCTA_COUNTER_IDS_LEFT = { BydFeatureIds.ADAS_RCTA_WARNING_LEFT };
    private static final int[] RCTA_COUNTER_IDS_RIGHT = { BydFeatureIds.ADAS_RCTA_WARNING_RIGHT };
    private static final int[] DOW_COUNTER_IDS_LEFT = { BydFeatureIds.ADAS_DOW_WARN_LEFT };
    private static final int[] DOW_COUNTER_IDS_RIGHT = { BydFeatureIds.ADAS_DOW_WARN_RIGHT };

    /** Feature ids the ADAS listener subscribes to. SLW is included so the
     *  pre-existing speed-limit-warning event keeps arriving unchanged. */
    private static final int[] ADAS_EVENT_FILTER = {
        BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE,
        BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM,
        BydFeatureIds.ADAS_FR_BLIND_SPOT_ALARM,
        BydFeatureIds.ADAS_LCA_WARNING_LEFT,
        BydFeatureIds.ADAS_LCA_WARNING_RIGHT,
        BydFeatureIds.ADAS_RCTA_WARNING_LEFT,
        BydFeatureIds.ADAS_RCTA_WARNING_RIGHT,
        BydFeatureIds.ADAS_DOW_WARN_LEFT,
        BydFeatureIds.ADAS_DOW_WARN_RIGHT,
    };

    /** How far a blind-spot counter must fall below its retained peak to be read as
     *  a firmware reset/wrap rather than a stale out-of-order reading. Warning
     *  counters advance one step per event, so a drop of more than a few steps
     *  cannot be ordering noise. */
    private static final int COUNTER_RESET_DROP = 4;

    // HEV-mileage probe budget. Only ever touched from the single telemetry-poll
    // thread inside collectStatistic, so plain fields are sufficient. A handful of
    // attempts covers a HAL that needs a moment after boot before it answers.
    private int hevMileageProbesLeft = 5;
    private boolean hevMileagePresent = false;

    // Counter feature ids that have been seen arriving as ADAS EVENTS. The poll
    // skips reading an id once its events are proven to work — polling all eight
    // registers at a fast cadence is a needless stream of HAL round-trips when the
    // callback already delivers them the moment they happen.
    //
    // Tracked PER ID, not as one global flag: coverage of these registers is
    // trim-dependent, so "a door-open event arrived" is no evidence that
    // lane-change events also arrive. A global flag would stop polling the ids that
    // never fire an event, and those alerts would then be missed entirely.
    private final java.util.Set<Integer> bsEventProvenIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Last value seen per counter-encoded feature id, so an alert can be detected
    // as an INCREASE. Written by the ADAS event callback and the fast poll; a
    // concurrent map keeps that safe without locking either path.
    private final java.util.Map<Integer, Integer> bsCounterBaseline =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Live ADAS handle for the read-only blind-spot probe. Uses ensureAdasDevice so a
     *  boot-race null self-heals, exactly as readBlindSpotNow does. */
    public Object adasHandleForProbe() {
        return ensureAdasDevice();
    }

    /** Unmodifiable view of the counter baselines, so the probe can report what the poll
     *  WOULD conclude without advancing them (which would consume a real alert). */
    public java.util.Map<Integer, Integer> bsBaselineView() {
        return java.util.Collections.unmodifiableMap(bsCounterBaseline);
    }

    /** Unmodifiable view of the ids whose ADAS events are proven, i.e. no longer polled. */
    public java.util.Set<Integer> bsProvenView() {
        return java.util.Collections.unmodifiableSet(bsEventProvenIds);
    }

    /**
     * Live ADAS warning state. Returns -1 when the ADAS device is unavailable so
     * callers can distinguish "no data" from "no alert".
     *
     * <p>Always reads the two dedicated FL/FR level alarms. The six LCA/RCTA/DOW
     * counters are read ONLY while the ADAS event callback has not been seen
     * working: this method is called at a fast cadence, and reading all eight
     * registers every tick means eight synchronous HAL round-trips several times a
     * second — far more than any comparable signal in this daemon, and almost
     * entirely redundant, since the event path already delivers every counter
     * alert the moment it happens. Polling them is the fallback for firmware that
     * doesn't honour the filtered registration, so once an event has actually
     * arrived the poll drops to two reads and the events carry the rest.
     *
     * <p>While PARKED the radar is unpowered and only the door-open warning can fire, so the
     * read narrows to that one counter per side (2 reads/tick instead of 8) — the level alarms
     * and LCA/RCTA all need a moving car. That keeps the parked exit-warning working while
     * removing the bulk of this signal's parked cost; the ADAS event path stays fully armed
     * either way, and the next tick after ACC returns reads everything again.
     */
    public int readAdasWarningsNow() {
        // ensureAdasDevice (not the raw field) so a boot-race null self-heals
        // instead of leaving the signal permanently dead.
        Object device = ensureAdasDevice();
        if (device == null) return -1;
        // Read once: a mid-method ACC edge would otherwise mix the two register sets.
        boolean accOn = accIsOn;
        try {
            int packed = 0;
            // Tracks whether ANY register answered a real value this tick. Without it an ADAS
            // device that answers nothing at all (every read a sentinel) yields packed=0, which the
            // caller cannot tell from a genuine "no vehicle in the blind spot" — a safety signal
            // reporting a confident, permanent "clear". Every id being unreadable must report
            // unavailable (-1) so the caller expires its holds instead of publishing "clear".
            boolean[] readOk = new boolean[1];
            // Each half is evaluated into its own local FIRST, deliberately avoiding a short-circuit
            // ||: the counter read is not just a test, it ADVANCES each counter's baseline. Skipping
            // it whenever the level alarm was already asserting froze those baselines for as long as
            // a car sat in the blind spot, so the next genuine counter step was measured against a
            // stale value and re-fired as a phantom alert once the level alarm cleared.
            boolean leftLevel = accOn && anyLevelActive(device, BS_LEVEL_IDS_LEFT, readOk);
            boolean leftCounter = accOn
                    && anyCounterAdvanced(device, BS_COUNTER_IDS_LEFT, readOk);
            if (leftLevel || leftCounter) packed |= BS_LEFT_BIT;
            boolean rightLevel = accOn && anyLevelActive(device, BS_LEVEL_IDS_RIGHT, readOk);
            boolean rightCounter = accOn
                    && anyCounterAdvanced(device, BS_COUNTER_IDS_RIGHT, readOk);
            if (rightLevel || rightCounter) packed |= BS_RIGHT_BIT;
            if (accOn && anyCounterAdvanced(device, RCTA_COUNTER_IDS_LEFT, readOk)) {
                packed |= RCTA_LEFT_BIT;
            }
            if (accOn && anyCounterAdvanced(device, RCTA_COUNTER_IDS_RIGHT, readOk)) {
                packed |= RCTA_RIGHT_BIT;
            }
            if (anyCounterAdvanced(device, DOW_COUNTER_IDS_LEFT, readOk)) {
                packed |= DOW_LEFT_BIT;
            }
            if (anyCounterAdvanced(device, DOW_COUNTER_IDS_RIGHT, readOk)) {
                packed |= DOW_RIGHT_BIT;
            }
            // Exception: when every polled id is event-proven the poll deliberately reads nothing,
            // and that is a healthy state, not a dead device — the callback carries the alerts.
            if (!readOk[0] && !allBsIdsEventProven(accOn)) return -1;
            return packed;
        } catch (Throwable t) {
            logger.debug("readAdasWarningsNow error: " + t.getMessage());
            return -1;
        }
    }

    /** Compatibility view for callers interested only in the blind-spot/lane-change family. */
    public int readBlindSpotNow() {
        int packed = readAdasWarningsNow();
        return packed < 0 ? packed : packed & (BS_LEFT_BIT | BS_RIGHT_BIT);
    }

    /** The subscribable subset of {@code ids} — an id this SDK does not publish must not be sent in
     *  a registration filter, since a HAL that rejects one unknown id can refuse the whole call. */
    private static int[] resolvedIds(int[] ids) {
        int n = 0;
        for (int id : ids) if (BydFeatureIds.isResolved(id)) n++;
        if (n == ids.length) return ids;
        int[] out = new int[n];
        int i = 0;
        for (int id : ids) if (BydFeatureIds.isResolved(id)) out[i++] = id;
        return out;
    }

    /**
     * Whether every id this tick WOULD have polled is already event-proven, i.e. the poll
     * legitimately read nothing because the ADAS callback is delivering these alerts instead.
     * Distinguishes that healthy case from an ADAS device answering only sentinels.
     */
    private boolean allBsIdsEventProven(boolean accOn) {
        // Level ids are never event-proven-skipped by anyLevelActive, so if they were polled at all
        // (ACC on) a total read failure is genuine unavailability.
        if (accOn) return false;
        for (int id : DOW_COUNTER_IDS_LEFT) if (!bsEventProvenIds.contains(id)) return false;
        for (int id : DOW_COUNTER_IDS_RIGHT) if (!bsEventProvenIds.contains(id)) return false;
        return true;
    }

    /** True when any level-encoded alarm in {@code ids} reads as a real, active alert.
     *  {@code readOk[0]} is set whenever ANY id in this call answered a real value — the caller
     *  uses it to tell a genuine "clear" from an ADAS device that answered nothing at all. */
    private boolean anyLevelActive(Object device, int[] ids, boolean[] readOk) {
        boolean active = false;
        for (int id : ids) {
            int raw = readAdasAlertRaw(device, id);
            if (raw == BydVehicleData.UNAVAILABLE) continue;
            readOk[0] = true;
            if (raw >= 1) active = true;
        }
        return active;
    }

    /**
     * Read one ADAS alert register through the {@code get(int[], Class)} overload — the form the
     * OEM's own ADAS code uses for these ids. The {@code get(int deviceType, int featureId)}
     * overload answers the {@code -10011} unavailable sentinel for this family, which is why every
     * blind-spot read looked permanently dead.
     *
     * <p>Tries BOTH read overloads and takes whichever answers a real value. Which one a given
     * register honours is firmware-dependent and not knowable from here: sibling ADAS reads
     * (speed-limit warning, ISLA) demonstrably work through {@code get(int deviceType, int
     * featureId)}, while the OEM's own ADAS code reads the blind-spot registers through
     * {@code get(int[], Class)} — and a register that answers only through the other form reads as
     * a permanent sentinel, which is indistinguishable from "no alert". Trying both costs one extra
     * reflective call only when the first form fails.
     *
     * <p>The array form answers a {@code BYDAutoEventValue}, NOT a boxed int, so its result must go
     * through {@link BydDeviceHelper#getIntValue}; testing it for {@code Number} would discard every
     * real reading.
     *
     * <p>Returns {@link BydVehicleData#UNAVAILABLE} for a failed read or a known sentinel, so
     * callers can tell "no data" from a genuine value. Negatives (the {@code -10011} family) and
     * {@code 65535} are this HAL's unset/unsupported markers and must never be read as an alert
     * level or a counter step.
     */
    private int readAdasAlertRaw(Object device, int id) {
        // An id this SDK does not publish is not sendable — the HAL would answer a sentinel that a
        // magnitude test could read as a real alert level.
        if (!BydFeatureIds.isResolved(id)) return BydVehicleData.UNAVAILABLE;
        int single = sanitizeAdasAlert(BydDeviceHelper.callGetSingle(device, id));
        if (single != BydVehicleData.UNAVAILABLE) return single;
        return sanitizeAdasAlert(
                BydDeviceHelper.getIntValue(BydDeviceHelper.callGet(device, id, Integer.TYPE)));
    }

    /** Map a raw ADAS alert reading to itself, or UNAVAILABLE for a failure/unset sentinel. */
    private static int sanitizeAdasAlert(int raw) {
        // Both 16-bit rails, not just 0xFFFF: 65534 is filtered everywhere else in this file, and a
        // level register resting on it would satisfy `>= 1` on every tick — asserting the side
        // permanently so the hold never expires and a blind-spot automation latches on.
        if (raw < 0 || raw == 65535 || raw == 65534) return BydVehicleData.UNAVAILABLE;
        return raw;
    }

    /**
     * True when any counter-encoded warning in {@code ids} has INCREASED since the
     * last observed value.
     */
    private boolean anyCounterAdvanced(Object device, int[] ids, boolean[] readOk) {
        boolean advanced = false;
        for (int id : ids) {
            // Skip ids whose events are proven to arrive — the callback already
            // reports those, so polling them is pure duplicate HAL traffic. Per id,
            // so an id that never fires an event keeps being polled.
            if (bsEventProvenIds.contains(id)) continue;
            int raw = readAdasAlertRaw(device, id);
            // Unreadable or a sentinel this tick — leave the baseline alone. Feeding a sentinel in
            // would either fake an increment or park the baseline at a value no real count reaches.
            if (raw == BydVehicleData.UNAVAILABLE) continue;
            readOk[0] = true;
            if (counterAdvanced(id, raw)) advanced = true;
        }
        return advanced;
    }

    /**
     * Record one counter-encoded reading and report whether it represents a new
     * alert (a genuine increase over the highest value seen so far).
     *
     * <p>Shared by the poll and the ADAS event callback, which run on different
     * threads and observe the same counters. The update is a monotonic MAX rather
     * than a plain overwrite: a stale reading arriving after a newer one (an
     * out-of-order event, or an event racing a poll) would otherwise push the
     * baseline BACKWARDS, and the next read of the unchanged counter would be
     * re-detected as a fresh increment — a phantom alert. Taking the max makes the
     * detection idempotent, so whichever path observes an increment first reports
     * it and the other sees nothing new.
     *
     * <p>But a monotonic max alone would WEDGE the signal if the counter genuinely
     * resets or wraps mid-drive: every later reading sits below the retained peak,
     * so no increase is ever seen again and the side goes permanently silent. A
     * LARGE drop is therefore treated as a reset and re-baselines downward, while a
     * small one is treated as stale ordering and ignored. The two cases are
     * distinguishable because out-of-order noise moves the value by a step or two,
     * whereas a reset drops it to near zero.
     *
     * <p>First sight only establishes the baseline and never reports an alert:
     * a counter resting at a non-zero value would otherwise fire the moment the
     * feature was enabled.
     */
    private boolean counterAdvanced(int id, int raw) {
        // Atomic per key on a ConcurrentHashMap, so two threads observing the same
        // counter cannot interleave a read-modify-write or lose an update.
        Integer prev = bsCounterBaseline.get(id);
        bsCounterBaseline.merge(id, raw, (oldVal, newVal) ->
                newVal < oldVal - COUNTER_RESET_DROP ? newVal : Math.max(oldVal, newVal));
        // An alert is a genuine increase. A reset is NOT an alert — it re-baselines
        // (above) and waits for the next real increment, so a wrap can't fire a
        // phantom warning.
        return prev != null && raw > prev;
    }

    // ── Fast dynamic-input reads (speed / accelerator / brake / steering) ─────
    // Single live SDK reads mirroring readTurnNow(), for the self-gated DynamicsEvent
    // fast poll so an "accelerator > X%" / "steering past Y°" automation fires promptly
    // (the 5s telemetry snapshot lagged it by up to that long). Each guards the
    // SDK_NOT_AVAILABLE sentinel so a miss returns NaN/UNAVAILABLE (the caller skips the
    // publish) rather than a bogus value. Only called while a matching automation exists.

    /** Live vehicle speed in canonical km/h, or NaN on a miss/sentinel. Speed uses the
     * hardware-derived factor when available: the app's distance-display override can differ from
     * the cluster's real raw-speed unit and must not scale an automation threshold by 1.6x. */
    public double readSpeedNowKmh() {
        if (speedDevice == null) return Double.NaN;
        try {
            Object speed = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
            if (speed instanceof Number) {
                double raw = ((Number) speed).doubleValue();
                return convertRawSpeedToKmh(raw, getSpeedToKmhFactor());
            }
        } catch (Throwable t) {
            logger.debug("readSpeedNowKmh error: " + t.getMessage());
        }
        return Double.NaN;
    }

    /** Live accelerator deepness 0-100, or UNAVAILABLE on a miss/sentinel. */
    public int readAccelNow() {
        if (speedDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object ac = BydDeviceHelper.callGetter(speedDevice, "getAccelerateDeepness");
            if (ac instanceof Number) {
                int a = ((Number) ac).intValue();
                if (a != BydFeatureIds.SDK_NOT_AVAILABLE) return a;
            }
        } catch (Throwable t) { logger.debug("readAccelNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live brake deepness 0-100, or UNAVAILABLE on a miss/sentinel. */
    public int readBrakeNow() {
        if (speedDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object br = BydDeviceHelper.callGetter(speedDevice, "getBrakeDeepness");
            if (br instanceof Number) {
                int b = ((Number) br).intValue();
                if (b != BydFeatureIds.SDK_NOT_AVAILABLE) return b;
            }
        } catch (Throwable t) { logger.debug("readBrakeNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live signed steering angle in degrees (clamped ±780), or UNAVAILABLE on a
     *  miss/sentinel/out-of-range. Returned as an int (rounded) to match the published
     *  STEERING_ANGLE event's integer value. */
    public int readSteeringNow() {
        if (bodyworkDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object s = BydDeviceHelper.callGetter(bodyworkDevice, "getSteeringWheelValue", 1);
            if (s instanceof Number) {
                double angle = ((Number) s).doubleValue();
                if (angle != BydFeatureIds.SDK_NOT_AVAILABLE && angle >= -780 && angle <= 780) {
                    return (int) Math.round(angle);
                }
            }
        } catch (Throwable t) { logger.debug("readSteeringNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    // ── Live single-signal reads for the fast automation pollers (GearEvent /
    // ClimateEvent) ─────────────────────────────────────────────────────────────
    // Same contract as readAccelNow/readSteeringNow above: read ONE signal directly off
    // the already-resolved device handle and return it, WITHOUT touching the shared
    // snapshot or its debounce state. The gear read deliberately uses ONLY
    // getGearboxAutoModeType() — never the crashing learningEPB() path that forced the
    // gearbox HAL LISTENER to be disabled (see registerAllListeners) — so it's safe to
    // call at a fast cadence. Each returns UNAVAILABLE on a miss so the caller skips the
    // publish rather than manufacturing a spurious edge.

    private int readGearFromCarAdapter() {
        try {
            Class<?> camCls = Class.forName("com.ts.lib.caradapter.CarAdapterManager");
            Method getInst = camCls.getMethod("getInstance", Context.class);
            Object cam = getInst.invoke(null, context);
            if (cam != null) {
                Method getMgr = camCls.getMethod("getCarAdapterManager", String.class);
                Object bodyMgr = getMgr.invoke(cam, "body");
                if (bodyMgr != null) {
                    Method m = bodyMgr.getClass().getMethod("getShiftMode");
                    Object res = m.invoke(bodyMgr);
                    if (res instanceof Number) {
                        int shift = ((Number) res).intValue();
                        switch (shift) {
                            case 0:
                            case 1: return 1; // GEAR_P
                            case 2: return 2; // GEAR_R
                            case 3: return 3; // GEAR_N
                            case 4: return 4; // GEAR_D
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live gearbox mode (raw SDK enum for {@link com.overdrive.app.monitor.GearMonitor}),
     *  or UNAVAILABLE on a miss. Uses the same getter the 5s poll (collectGearbox) and the
     *  fast-dynamics poll already call — NOT the learningEPB() listener path. */
    public int readGearNow() {
        if (gearboxDevice != null) {
            try {
                Object g = BydDeviceHelper.callGetter(gearboxDevice, "getGearboxAutoModeType");
                if (g instanceof Number) {
                    int val = ((Number) g).intValue();
                    if (val >= 1 && val <= 7) return val;
                }
            } catch (Throwable t) { logger.debug("readGearNow error: " + t.getMessage()); }
        }
        int carAdapterGear = readGearFromCarAdapter();
        if (carAdapterGear != BydVehicleData.UNAVAILABLE) {
            return carAdapterGear;
        }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live per-seat climate level normalized to 0=off/1=low/2=high (the wire format the
     *  {@code seatClimate} events use), or UNAVAILABLE on a miss. {@code heat=true} reads
     *  ventilation-OFF heating; {@code heat=false} reads cooling (ventilation). {@code area}
     *  is 1=driver, 2=passenger. Mirrors collectSettings' normalization exactly. */
    public int readSeatClimateNow(boolean heat, int area) {
        if (settingDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            String getter = heat ? "getSeatHeatingState" : "getSeatVentilatingState";
            Object v = BydDeviceHelper.callGetter(settingDevice, getter, area);
            if (v instanceof Number) {
                int norm = ((Number) v).intValue() - 1; // SDK 1=off,2=low,3=high → 0/1/2
                if (norm >= 0 && norm <= 2) return norm;
            }
        } catch (Throwable t) { logger.debug("readSeatClimateNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live AC power state: 0=off, 1=on, or UNAVAILABLE for every other SDK value. */
    public int readAcPowerNow() {
        if (acDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object v = BydDeviceHelper.callGetter(acDevice, "getAcStartState");
            if (v instanceof Number) {
                int raw = ((Number) v).intValue();
                if (raw == 0 || raw == 1) return raw;
            }
        } catch (Throwable t) {
            logger.debug("readAcPowerNow error: " + t.getMessage());
        }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Live headlamp beam state: {@code high=true} reads high-beam, else low-beam. Returns 1
     *  (on) / 0 (off), or UNAVAILABLE on a miss. Shares collectLight's light-TYPE constants
     *  ({@link #LIGHT_TYPE_LOW_BEAM}/{@link #LIGHT_TYPE_HIGH_BEAM}; status 1 = that beam is
     *  on) so the fast automation path and the snapshot path can't drift apart — this used to
     *  hold its own copy of the numbering and inherited the same off-by-one. */
    public int readBeamNow(boolean high) {
        if (lightDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            return getLightStatus(high ? LIGHT_TYPE_HIGH_BEAM : LIGHT_TYPE_LOW_BEAM);
        } catch (Throwable t) { logger.debug("readBeamNow error: " + t.getMessage()); }
        return BydVehicleData.UNAVAILABLE;
    }

    /** Daytime-running-light switch: SDK 1=on, 2=off. */
    public int readDrlNow() {
        if (lightDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object value = BydDeviceHelper.callGetter(lightDevice, "getDayTimeLightState");
            if (value instanceof Number) {
                return normalizeDrlState(((Number) value).intValue());
            }
        } catch (Throwable t) {
            logger.debug("readDrlNow error: " + t.getMessage());
        }
        return BydVehicleData.UNAVAILABLE;
    }

    /** AUTO-headlight mode switch: SDK 0=off, 1=on. This is not a darkness sensor. */
    public int readAutoHeadlightNow() {
        if (lightDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object value = BydDeviceHelper.callGetter(lightDevice, "getLightAutoStatus");
            if (value instanceof Number) {
                return normalizeAutoHeadlightState(((Number) value).intValue());
            }
        } catch (Throwable t) {
            logger.debug("readAutoHeadlightNow error: " + t.getMessage());
        }
        return BydVehicleData.UNAVAILABLE;
    }

    /**
     * Automatic rain-wiper mode, normalized to 0/1.
     *
     * <p>The connected-model SDK exposes this on Setting as 1=on, 2=off. An older Bodywork getter
     * uses 1=on, 0=off and is retained only as a compatibility fallback. Every other value is
     * unavailable.
     */
    public int readAutoWiperNow() {
        if (settingDevice != null) {
            try {
                Object value = BydDeviceHelper.callGetter(settingDevice, "getAutoRainWiperState");
                if (value instanceof Number) {
                    int normalized = normalizeAutoWiperSettingState(
                            ((Number) value).intValue());
                    if (normalized != BydVehicleData.UNAVAILABLE) return normalized;
                }
            } catch (Throwable t) {
                logger.debug("readAutoWiperNow setting error: " + t.getMessage());
            }
        }
        if (bodyworkDevice != null) {
            try {
                Object value = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoWiperState");
                if (value instanceof Number) {
                    int normalized = normalizeAutoWiperBodyworkState(
                            ((Number) value).intValue());
                    if (normalized != BydVehicleData.UNAVAILABLE) return normalized;
                }
            } catch (Throwable t) {
                logger.debug("readAutoWiperNow bodywork error: " + t.getMessage());
            }
        }
        return BydVehicleData.UNAVAILABLE;
    }

    /**
     * Front-wiper activity, normalized to 0/1 from the two available rails: dedicated Wiper front
     * level 8/9 is active, or Setting rain-wiper speed above 1 is active.
     */
    public int readWiperActiveNow() {
        int wiperLevel = BydVehicleData.UNAVAILABLE;
        int settingSpeed = BydVehicleData.UNAVAILABLE;
        if (wiperDevice != null) {
            try {
                Object event = BydDeviceHelper.callGet(
                        wiperDevice, BydFeatureIds.WIPER_FRONT_LEVEL, Integer.class);
                wiperLevel = BydDeviceHelper.getIntValue(event);
            } catch (Throwable t) {
                logger.debug("readWiperActiveNow wiper error: " + t.getMessage());
            }
        }
        if (settingDevice != null) {
            try {
                Object event = BydDeviceHelper.callGet(
                        settingDevice, BydFeatureIds.SETTING_RAIN_WIPER_SPEED, Integer.class);
                settingSpeed = BydDeviceHelper.getIntValue(event);
            } catch (Throwable t) {
                logger.debug("readWiperActiveNow setting error: " + t.getMessage());
            }
        }
        return normalizeWiperActivity(wiperLevel, settingSpeed);
    }

    static int normalizeDrlState(int raw) {
        if (raw == 1) return 1;
        if (raw == 2) return 0;
        return BydVehicleData.UNAVAILABLE;
    }

    static int normalizeAutoHeadlightState(int raw) {
        return raw == 0 || raw == 1 ? raw : BydVehicleData.UNAVAILABLE;
    }

    static int normalizeAutoWiperSettingState(int raw) {
        if (raw == 1) return 1;
        if (raw == 2) return 0;
        return BydVehicleData.UNAVAILABLE;
    }

    static int normalizeAutoWiperBodyworkState(int raw) {
        return raw == 0 || raw == 1 ? raw : BydVehicleData.UNAVAILABLE;
    }

    static int normalizeWiperActivity(int wiperLevel, int settingSpeed) {
        boolean levelValid = wiperLevel >= 0 && wiperLevel <= 9;
        boolean speedValid = settingSpeed >= 0 && settingSpeed <= 255;
        if ((levelValid && (wiperLevel == 8 || wiperLevel == 9))
                || (speedValid && settingSpeed > 1)) {
            return 1;
        }
        return levelValid || speedValid ? 0 : BydVehicleData.UNAVAILABLE;
    }

    private void startPolling() {
        // Honour the CURRENT ACC state. This used to hard-code the 5s ACC-on interval, so a
        // re-init while parked (context recovery / watchdog) left the poll at 5s for the whole
        // park — 18x the intended rate — with no ACC edge left to correct it.
        long interval = accIsOn ? POLL_INTERVAL_MS : POLL_INTERVAL_PARKED_MS;
        long schedulerGeneration = pollSchedulerGeneration.incrementAndGet();
        pollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BydDataPoll");
            t.setDaemon(true);
            return t;
        });
        pollScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!collectAllFromScheduler(schedulerGeneration)) return;
                // Log when data actually changes
                BydVehicleData d = snapshot.get();
                if (d != null) {
                    String hash = String.format("%.1f|%.2f|%.1f/%.1f/%.1f|%.3f/%.3f",
                        d.socPercent, d.voltage12v, d.highCellTempC, d.lowCellTempC, d.avgCellTempC,
                        d.highCellVoltage, d.lowCellVoltage);
                    if (!hash.equals(lastSummaryHash)) {
                        logger.info("Data changed: SOC=" + d.socPercent + "% 12V=" + d.voltage12v + "V" +
                            " Temp=" + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C" +
                            " CellV=" + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
                        lastSummaryHash = hash;
                    }
                }
            } catch (Throwable t) {
                logger.debug("Poll error: " + t.getMessage());
            }
        }, interval, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
        logger.info("BydDataPoll started at " + (interval / 1000) + "s (ACC "
                + (accIsOn ? "ON" : "OFF") + ")");
    }

    public synchronized void stop() {
        // Make shutdown a publication barrier for charging transitions, charging-rate callbacks,
        // and occupancy samples. A callback that already passed its optimistic outer check either
        // finishes before these locks are acquired or fails its final check after initialized=false.
        deactivateCallbackPublication();
        pollSchedulerGeneration.incrementAndGet();
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
            pollScheduler = null;
        }
        cancelPersistedDaemonEnergyReconciliation(true);
        if (safetyBeltDevice != null) {
            if (BydDeviceHelper.unregisterSafetyBeltListener(safetyBeltDevice)) {
                registeredHandles.remove(safetyBeltDevice);
                activeSafetyBeltListenerDevice = null;
                activeSafetyBeltListenerGeneration = safetyBeltListenerGeneration.incrementAndGet();
            }
        }
        resetPassengerSeatbeltState();
        stopFastDynamicsPoll();
        unregisterPlugEdgeReceiver();
    }

    private android.content.BroadcastReceiver plugEdgeReceiver;

    private void registerPlugEdgeReceiver() {
        if (context == null) return;
        // Idempotent — re-init flow tears down and re-registers.
        unregisterPlugEdgeReceiver();
        plugEdgeReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                long lifecycleGeneration = callbackLifecycleGeneration.get();
                if (!isCallbackLifecycleCurrent(lifecycleGeneration)) return;
                switch (intent.getAction()) {
                    case android.content.Intent.ACTION_POWER_CONNECTED:
                        synchronized (chargingEdgePublishLock) {
                            if (!isCallbackLifecycleCurrent(lifecycleGeneration)) return;
                            com.overdrive.app.monitor.ChargingDetector.getInstance()
                                    .onPowerConnected();
                        }
                        break;
                    case android.content.Intent.ACTION_POWER_DISCONNECTED:
                        publishChargingGunEdge(
                                null, -1L, lifecycleGeneration,
                                1, chargingObservationOrder.begin());
                        break;
                }
            }
        };
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_POWER_CONNECTED);
            f.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
            context.registerReceiver(plugEdgeReceiver, f);
            logger.info("Plug-edge receiver registered (CONNECTED/DISCONNECTED)");
        } catch (Exception e) {
            logger.debug("registerPlugEdgeReceiver failed: " + e.getMessage());
            plugEdgeReceiver = null;
        }
    }

    private void unregisterPlugEdgeReceiver() {
        if (context == null || plugEdgeReceiver == null) return;
        try {
            context.unregisterReceiver(plugEdgeReceiver);
        } catch (Exception ignored) {}
        plugEdgeReceiver = null;
    }

    /**
     * Resolve one HAL device, with FALLBACK TIERS for the case where the singleton accessor we have
     * always used returns null.
     *
     * <p>Tier 1 is unchanged ({@code Device.getInstance(ctx)}), so a vehicle that works today
     * resolves on the first attempt and takes a byte-identical path — the fallbacks are pure
     * addition and cannot regress it. Tiers 2-3 ({@code DeviceManager.getDevice(type)}, then direct
     * construction) come from a reference app's own device-init ladder; we previously had only
     * tier 1, so on a trim where the singleton is null we ended up with no handle at all and every
     * getter on that device returned nothing in lockstep. That is exactly the pattern a BEV capture
     * showed for the whole charging surface at once.
     *
     * <p>The tier that won is logged once per device so a single capture identifies which
     * acquisition route a trim needs.
     */
    private Object initDevice(String className, String shortName) {
        Object device = BydDeviceHelper.getDevice(className, context);
        boolean viaFallback = false;
        if (device == null) {
            // Tier 2/3. A deviceType cannot be known before we hold an instance, so pass MIN_VALUE
            // and let resolveDeviceFallback substitute a LEARNED type when one was recorded on an
            // earlier pass — that is what makes its manager tier reachable at all (it is otherwise
            // statically dead, since this is its only caller).
            device = BydManagerChannel.resolveDeviceFallback(context, className, Integer.MIN_VALUE);
            if (device != null) {
                viaFallback = true;
                logger.info("initDevice[" + shortName + "]: recovered via fallback tier"
                        + " (getInstance returned null)");
            }
        }
        if (device != null) {
            // Learn this class's HAL type while we hold a live handle. Read off the instance
            // because the bundled stubs report placeholder types, so a hardcoded table would be
            // wrong. Feeds the manager acquisition tier and the manager-level reads.
            BydManagerChannel.rememberDeviceType(className, device);
            // Tag a fallback-acquired handle so it stays DISTINGUISHABLE in the diagnostic dump.
            // availableDevices is the project's "is the HAL alive" readout (snapshot JSON + the
            // "N devices" health line); listing a fallback handle identically to a real singleton
            // would collapse the very "no handle" vs "handle but dead" distinction this whole
            // change set was reasoned from — a future capture would read 17/17 available with the
            // charging surface still NaN and no way to tell which case it was.
            availableDevices.add(viaFallback ? shortName + "(fallback)" : shortName);
            // ACTIVATION. Reference behaviour is to explicitly enable each telemetry device before
            // polling it; we never did. Harmless when the HAL does not require it (the call simply
            // reports a non-zero code or is absent), and decisive if it does.
            //
            // The RESULT IS DELIBERATELY IGNORED. Activation is advisory: the overwhelmingly common
            // case is a HAL that needs no enabling and answers non-zero (or has no such method at
            // all), and treating that as "device unusable" would drop every device on every trim
            // that works today — a catastrophic regression to buy a theoretical gain. The outcome
            // is logged per device instead, so a capture still shows whether activation mattered.
            try {
                BydManagerChannel.enableDevice(context, device, shortName);
            } catch (Throwable t) {
                logger.debug("enableDevice[" + shortName + "] threw: " + t.getMessage());
            }
        } else {
            unavailableDevices.add(shortName);
        }
        return device;
    }

    /**
     * Initialize the multimedia device with multiple context strategies.
     * BYDAutoMultimediaDevice does NOT extend AbsBYDAutoDevice — it's a separate class
     * that connects to a binder service and may require a specific package identity.
     */
    private Object initMultimediaDevice() {
        String className = "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice";

        // Strategy 1: Use our normal context (works for all other devices)
        Object device = BydDeviceHelper.getDevice(className, context);
        if (device != null) {
            availableDevices.add("Multimedia");
            return device;
        }

        // Strategy 2: Try with a proper app context for com.overdrive.app
        // The daemon runs via app_process with a synthetic context. But the actual app
        // is installed — createPackageContext gives us a real app context with proper
        // service bindings that the multimedia device might need.
        try {
            android.content.Context appPkgCtx = context.createPackageContext(
                "com.overdrive.app",
                android.content.Context.CONTEXT_INCLUDE_CODE | android.content.Context.CONTEXT_IGNORE_SECURITY);
            if (appPkgCtx != null) {
                device = BydDeviceHelper.getDevice(className, appPkgCtx);
                if (device != null) {
                    logger.info("Multimedia device OK via com.overdrive.app package context");
                    availableDevices.add("Multimedia");
                    return device;
                }
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 2 (overdrive package context) failed: " + e.getMessage());
        }

        // Strategy 3: Try with system context directly (with timeout — can deadlock)
        try {
            final Object[] result = new Object[1];
            Thread t = new Thread(() -> {
                try {
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    java.lang.reflect.Method currentAt = atClass.getMethod("currentActivityThread");
                    Object at = currentAt.invoke(null);
                    if (at != null) {
                        java.lang.reflect.Method getSystemContext = atClass.getMethod("getSystemContext");
                        android.content.Context sysCtx = (android.content.Context) getSystemContext.invoke(at);
                        if (sysCtx != null) {
                            result[0] = BydDeviceHelper.getDevice(className, sysCtx);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Multimedia strategy 3 inner: " + e.getMessage());
                }
            }, "MultimediaInit-SysCtx");
            t.setDaemon(true);
            t.start();
            t.join(3000); // 3s timeout — abort if it hangs
            if (t.isAlive()) {
                logger.warn("Multimedia strategy 3 timed out (3s) — skipping to avoid freeze");
                t.interrupt();
            } else if (result[0] != null) {
                device = result[0];
                logger.info("Multimedia device OK via system context");
                availableDevices.add("Multimedia");
                return device;
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 3 (system context) failed: " + e.getMessage());
        }

        // Strategy 4: Try a DIFFERENT context object than the one already tried.
        // The daemon's PermissionBypassContext.getApplicationContext() returns the
        // wrapper itself (so its BYD-permission overrides survive SDK re-normalization),
        // which means getApplicationContext() == context here and would make this
        // fallback a no-op. Unwrap to the underlying base context in that case so
        // Strategy 4 genuinely tries a distinct handle. Compare identity to skip when
        // there's nothing new to try.
        try {
            android.content.Context appCtx = context.getApplicationContext();
            if (appCtx == context && context instanceof android.content.ContextWrapper) {
                android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
                if (base != null) appCtx = base;
            }
            if (appCtx != null && appCtx != context) {
                device = BydDeviceHelper.getDevice(className, appCtx);
                if (device != null) {
                    logger.info("Multimedia device OK via alternate (app/base) context");
                    availableDevices.add("Multimedia");
                    return device;
                }
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 4 (app/base context) failed: " + e.getMessage());
        }

        unavailableDevices.add("Multimedia");
        return null;
    }

    // ==================== DATA COLLECTION ====================

    // Core data polled every 5s. Display-only data updated via listeners only (no polling).
    // Core = fields consumed by ABRP, MQTT, trip analytics, SOC history.
    // Display = fields only shown on the web dashboard — updated by BYD HAL listener callbacks
    //           or on-demand via collectAllFull() when the HTTP API is queried.

    // Hard throttle: never poll devices more frequently than this, even if listeners fire.
    // Listener callbacks update individual values directly in the snapshot without polling.
    // This guard prevents any code path from triggering a full device sweep within the interval.
    private volatile long lastCoreCollectTime = 0;
    private static final long MIN_COLLECT_INTERVAL_MS = 5000; // 5 seconds

    // ACC state: when off, skip polling speed/engine/gearbox (always 0 when parked)
    private volatile boolean accIsOn = true;

    /**
     * Threshold below which a post-ACC-OFF engine-power reading is treated
     * as plausible "current flowing into pack" (plug-in charging) rather
     * than ECU residue. Values more positive than this (above the deadband)
     * are rejected when accIsOn==false because the ICE cannot be running
     * with the key removed — those readings are stale/noisy.
     */
    private static final double ENGINE_POWER_CHARGING_DEADBAND = 0.3;

    /**
     * HAL handles we have SUCCESSFULLY attached a listener to. Identity-keyed
     * ({@link java.util.IdentityHashMap}) because the question is "is this the same object", not
     * "is it equals()" — a device's equals may be value-based or absent.
     *
     * <p>Per-handle rather than one whole-set fingerprint so that a device whose registration FAILED
     * is retried on the next {@code init()}, while a device already attached is never doubled. There
     * is no unregister path in this class, so a duplicate registration is permanent — but so is a
     * missing one, and the missing direction silently kills door/charging notifications.
     */
    private final java.util.Map<Object, Boolean> registeredHandles =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<Object, Boolean>());

    /**
     * True when {@code device} already has our listener attached, so the caller should skip it.
     * Null devices report false — the register helpers null-check anyway and will report failure.
     */
    private boolean alreadyRegistered(Object device) {
        return device != null && Boolean.TRUE.equals(registeredHandles.get(device));
    }

    /**
     * Mark a handle as attached iff this pass actually attempted it.
     *
     * @param field    the live field value (the real device)
     * @param attempted the local that {@code registerAllListeners} used — null when the handle was
     *                  skipped because it was already registered, in which case there is nothing new
     *                  to record
     */
    private void markRegistered(Object field, Object attempted) {
        // Marks ONLY on a recorded success. registerAllListeners() calls noteRegisterOk(device) in
        // each branch that actually attached, so a handle whose registration FAILED is never marked
        // and is retried on the next init.
        //
        // The earlier version marked whenever the handle was merely ATTEMPTED, which inverted the
        // intent: the ~25 call sites discard the helper's boolean, so a first init that failed under
        // the broken synthetic Context marked the handle anyway and the ACC-ON re-init then skipped
        // it — killing charging/instrument/bodywork/doorLock callbacks for the process lifetime.
        // That traded "fires twice" for "never fires", which is strictly worse.
        if (field != null && attempted != null && registerOkThisPass.contains(attempted)) {
            registeredHandles.put(field, Boolean.TRUE);
        }
    }

    /** Handles that successfully attached during the current registerAllListeners() pass. */
    private final java.util.Set<Object> registerOkThisPass =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());

    /** Called by registerAllListeners() from each branch that actually attached a listener. */
    private boolean noteRegisterOk(Object device, boolean ok) {
        if (ok && device != null) registerOkThisPass.add(device);
        return ok;
    }

    /** Throttle for the collectEngine power-resolution diagnostic (1/min). {@code volatile} for the
     *  same poll-thread vs HTTP-thread reason as {@link #lastClusterRawLogMs} — and it matters more
     *  here, because this line is the only field signal that the value is stuck on carry-forward. */
    private volatile long lastEnginePowerLogMs = 0;

    /**
     * How long an engine-power value (from either collectEngine read or the generic-event
     * listener) is honoured by the poll path before it is cleared as stale.
     *
     * <p>Deliberately ALIGNED to {@code ChargingDetector.ENGINE_POWER_FRESHNESS_MS} (15 s) rather
     * than to the poll interval. A longer TTL here was self-defeating: it kept the value non-NaN
     * for up to 24 five-second polls while the detector — which stamps freshness on any non-NaN
     * push — re-armed it each time, exactly reproducing the phantom this clear exists to remove
     * for anything in the 15-120 s band. Keeping a value only while its consumer would still call
     * it fresh means the two cannot disagree. The age itself now travels in
     * {@code BydVehicleData.enginePowerAtMs}, so no separate per-writer marker is needed.
     */
    private static final long ENGINE_POWER_LIVE_TTL_MS = 15_000L;
    /**
     * Matches VehicleDataMonitor's charging-power freshness bound. A poll may preserve the original
     * callback observation inside this window, but must never re-stamp it as a new hardware read.
     */
    static final long DEVICE_POWER_CALLBACK_MAX_AGE_MS = 120_000L;
    /** Maximum age/skew for the moving-rate proof that can reopen a connected FINISHED taper. */
    private static final long POST_FINISHED_RATE_PROOF_FRESHNESS_MS = 15_000L;

    /** Throttle for the pre-gate cluster charge-power raw-value capture (1/min). Separate from
     *  {@link #loggedClusterChargePowerScale}, which only fires on an ACCEPTED value and so says
     *  nothing on a trim where the gates never open. {@code volatile}: written from the poll
     *  thread and the HTTP {@code collectAllFull} thread (which bypasses the collect throttle by
     *  design), so a non-volatile long could tear or go unpublished and either spam or suppress
     *  the very capture we need. */
    private volatile long lastClusterRawLogMs = 0;

    /** Called by CameraDaemon when ACC state changes. Adjusts poll rate accordingly. */
    public synchronized void setAccState(boolean isOn) {
        boolean wasOn = this.accIsOn;
        this.accIsOn = isOn;

        if (wasOn != isOn) {
            try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                         com.overdrive.app.monitor.ChargingDetector.beginPublicationMutation()) {
                // Notify the fused detector first so it can invalidate ACC-dependent signals
                // before the corresponding collector value becomes externally stable.
                com.overdrive.app.monitor.ChargingDetector.getInstance().updateAccState(isOn);

                // ACC just transitioned OFF: also clear the snapshot's enginePowerKw so direct
                // snapshot consumers cannot retain the last drive's value while parked.
                if (!isOn) {
                    synchronized (chargingEdgePublishLock) {
                        BydVehicleData current = snapshot.get();
                        if (current != null && !Double.isNaN(current.enginePowerKw)) {
                            BydVehicleData published = current.toBuilder()
                                    .enginePowerKw(Double.NaN).build();
                            snapshot.set(published);
                            enginePowerEdgeVersion.incrementAndGet();
                            publishAutomationSnapshot(published);
                            logger.info("ACC OFF: invalidated stale enginePowerKw");
                        }
                    }
                }
            }
        } else {
            com.overdrive.app.monitor.ChargingDetector.getInstance().updateAccState(isOn);
        }

        // NOTE: the snapshot's powerLevel is deliberately NOT rewritten here. It is only
        // refreshed by collectBodywork, so it can carry a stale value across this edge and fight
        // the instant "power" publish (Invariant 1) — but correcting it would need a
        // snapshot rewrite. BydEvent itself suppresses a power value that contradicts this flag,
        // so the next committed publication cannot strobe an unrelated AC rule.

        // Restart poll scheduler at the appropriate rate
        if (initialized && pollScheduler != null && !pollScheduler.isShutdown()) {
            pollScheduler.shutdownNow();
            long interval = isOn ? POLL_INTERVAL_MS : POLL_INTERVAL_PARKED_MS;
            long schedulerGeneration = pollSchedulerGeneration.incrementAndGet();
            pollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BydDataPoll");
                t.setDaemon(true);
                return t;
            });
            pollScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!collectAllFromScheduler(schedulerGeneration)) return;
                    BydVehicleData d = snapshot.get();
                    if (d != null) {
                        String hash = String.format("%.1f|%.2f|%.1f/%.1f/%.1f|%.3f/%.3f",
                            d.socPercent, d.voltage12v, d.highCellTempC, d.lowCellTempC, d.avgCellTempC,
                            d.highCellVoltage, d.lowCellVoltage);
                        if (!hash.equals(lastSummaryHash)) {
                            logger.info("Data changed: SOC=" + d.socPercent + "% 12V=" + d.voltage12v + "V" +
                                " Temp=" + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C" +
                                " CellV=" + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
                            lastSummaryHash = hash;
                        }
                    }
                } catch (Throwable t) {
                    logger.debug("Poll error: " + t.getMessage());
                }
            }, 0, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
            logger.info("BydDataPoll rate changed to " + (interval / 1000) + "s (ACC " + (isOn ? "ON" : "OFF") + ")");
        }
    }

    /**
     * Scheduled entry point. The generation/lifecycle check intentionally runs after this method has
     * acquired the collector monitor; shutdownNow cannot interrupt a thread blocked on that monitor.
     */
    private synchronized boolean collectAllFromScheduler(long schedulerGeneration) {
        if (!initialized || schedulerGeneration != pollSchedulerGeneration.get()) {
            return false;
        }
        collectAll();
        return true;
    }

    /**
     * Current ACC (ignition) state as last set by {@link #setAccState} on the ACC edge.
     * Defaults to {@code true} (fail toward polling) until the first edge is observed.
     * Used by the fast automation pollers to skip their live SDK reads while parked —
     * accelerator / brake / steering / gear / drive mode / regen are all inert with the key
     * removed, so reading them 1–4x/sec on a parked car is pure waste. The reads resume on the
     * very next tick once ACC returns, so trigger latency is unchanged.
     *
     * <p><b>Do NOT gate the turn signal on this.</b> {@link #readTurnNow} folds the HAZARD
     * flash state into both side bits, and hazards are used on a parked car — gating it would
     * silently kill a hazard-driven rule and could strand a side latched "on". Blind spot is
     * likewise only NARROWED while parked (door-open warning stays armed), never gated off.
     */
    public boolean isAccOn() {
        return accIsOn;
    }

    /**
     * Collect core telemetry data from devices into the snapshot.
     * Safe to call from any thread.
     * 
     * Hard-throttled: will not poll devices if called within 5 seconds of the last poll.
     * 
     * Only polls CORE devices (used by ABRP, MQTT, trips, SOC history).
     * When ACC is off, skips speed/engine/gearbox (always 0 when parked).
     * Display-only devices are NOT polled — updated via listeners or on-demand.
     */
    /** True if ANY of the given automation events is referenced by an enabled automation.
     *  Used to self-gate the display-only device polls below so they cost nothing (no SDK
     *  read) unless a rule actually keys off that signal. Never throws. */
    private static boolean anyReferenced(com.overdrive.app.automation.condition.EventData... keys) {
        try {
            for (com.overdrive.app.automation.condition.EventData k : keys) {
                if (com.overdrive.app.automation.Automations.isEventReferenced(k)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ── Overlay beam demand ──────────────────────────────────────────────
    // Set by whichever recording flow currently burns in the LOW_BEAM/HIGH_BEAM
    // telemetry fields (see OverlayBitmapRenderer). Keyed so set/clear is
    // idempotent across the pano / surveillance / OEM flows without start-stop
    // pairing. Empty map => no overlay wants beams, and the light poll falls
    // back to being automation-gated exactly as before.
    private static final java.util.Set<String> beamDemand =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Declare whether {@code key}'s recording flow currently draws the
     * low/high-beam overlay fields. Safe to call from any thread.
     */
    public static void setOverlayBeamDemand(String key, boolean wanted) {
        if (key == null) return;
        if (wanted) beamDemand.add(key);
        else beamDemand.remove(key);
    }

    /** True if any active overlay flow draws the beam fields. */
    private static boolean overlayWantsBeams() {
        return !beamDemand.isEmpty();
    }

    public synchronized void collectAll() {
        long now = System.currentTimeMillis();
        // Hard throttle: skip if called within MIN_COLLECT_INTERVAL_MS of last poll.
        if (now - lastCoreCollectTime < MIN_COLLECT_INTERVAL_MS) {
            return;
        }
        try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                     com.overdrive.app.monitor.ChargingDetector.beginPublicationMutation()) {
            lastCoreCollectTime = now;

            long pollGeneration = chargingPollGeneration.incrementAndGet();
            BydVehicleData.Builder b = (snapshot.get() != null) ? snapshot.get().toBuilder() : new BydVehicleData.Builder();
            b.availableDevices(availableDevices.toArray(new String[0]));
            b.unavailableDevices(unavailableDevices.toArray(new String[0]));

        // ALWAYS needed: battery, SOC, charging, temperature, 12V
        collectBodywork(b);     // SOC, 12V, remainKwh, powerLevel
        StatisticHalResult statResult = collectStatistic(b);    // SOC, mileage, range, cellTemps, cellVoltages
        boolean socHalSucceeded = statResult.socSucceeded;
        boolean rangeHalSucceeded = statResult.elecRangeSucceeded;
        boolean fuelHalSucceeded = statResult.fuelSucceeded;
        collectSocTarget(b);    // configured SOC target/hold percentage
        ChargingObservationVersions chargingObserved = collectChargingOrdered(b);
        collectInstrumentOrdered(b, chargingObserved); // outsideTemp, externalChargingPower
        collectOta(b);          // 12V voltage (precise)
        collectTyre(b);         // pressure (kPa), pressure/leak/signal state per wheel

        // DRIVING ONLY: skip most when ACC is off (values are always 0/stale when parked).
        // EXCEPTION: enginePower remains meaningful when the car is plugged in and
        // charging — current flowing into the pack reads negative on the engine
        // bus and is the most authoritative charging signal we have on PHEVs
        // (where chargingGunState is often UNAVAILABLE and chargingState is
        // stuck at 15=IDLE due to firmware bugs). Detect "probably charging"
        // from the listener-delivered chargingPower / externalChargingPower
        // values populated from typed callbacks even while ACC is off.
        if (accIsOn) {
            collectSpeed(b);        // speed, accel, brake
            collectEngineOrdered(b, chargingObserved); // enginePower, motorSpeed/torque
            collectGearbox(b);      // gearMode
            collectSteeringAngle(b);// live steering angle (init-only otherwise → dead trigger)
        } else {
            // NB the power terms here read fields the admission gate populates, so on a trim whose
            // gun state is UNAVAILABLE and whose BMS sits at IDLE they can both be NaN even during a
            // real charge. The detector's own ungated observation is the reliable signal in that
            // case, so consult it too — otherwise engine power (the most authoritative charging
            // evidence on such a PHEV) is never collected and the detector is starved of the one
            // input that could resolve the session.
            boolean rawSignalActive = false;
            try {
                rawSignalActive = com.overdrive.app.monitor.ChargingDetector.getInstance()
                        .hasRecentRawChargingSignal();
            } catch (Throwable rawSignalError) {}
            boolean possiblyCharging =
                (!Double.isNaN(b.chargingPowerKw) && Math.abs(b.chargingPowerKw) > 0.1)
                || (!Double.isNaN(b.externalChargingPowerKw) && b.externalChargingPowerKw > 0.1)
                || rawSignalActive
                || b.chargingState == 1   // BMS explicitly says CHARGING
                || b.chargingGunState == 2 || b.chargingGunState == 3
                || b.chargingGunState == 4 || b.chargingGunState == 5;
            if (possiblyCharging) {
                collectEngineOrdered(b, chargingObserved); // adds enginePowerKw → confirms direction
            }
        }

        // Read AC_TEMP_INSIDE exactly once for this poll. Both legacy cabin fields consume this
        // one observation, and the result tells cloud fallback whether the HAL answered now
        // rather than merely leaving a carried-forward value in the builder.
        boolean cabinTempHalSucceeded = collectCabinTemperature(b);

        // Extended data consumed by ABRP/MQTT/trips
        collectStatisticExtended(b);   // SOH, driving time, key battery
        collectInstrumentExtended(b);  // trip data, consumption
        // AC device: acStartState/fan. Normally a "display-only,
        // listener-driven" device, but its listener (onDisplayCallback) is a
        // deliberate no-op, so without polling here acStartState was
        // read exactly once at init (collectAllFull) and then carried forward
        // unchanged on every toBuilder() poll. Poll it every cycle (ACC on AND off).
        // The cabin read above remains separate so this method cannot read the same
        // AC_TEMP_INSIDE channel a second time.
        collectAc(b);
        // Charging rest time (time-to-full) HAL fallback. The primary feature-ID
        // read lives in collectInstrument() above, but many trims/firmware leave
        // those instrument IDs at 255/not-available while charging — so the
        // dashboard "Time to full" stayed blank because the chargingDevice
        // getChargingRestTime() fallback only ran once at init (collectAllFull).
        // Run it every poll here so a live charge populates rest time. The method
        // self-guards on chargingRestTimeHours==UNAVAILABLE, so it only fills the
        // field when the instrument read missed.
        collectChargingExtended(b);    // charging rest time (fallback)

        // Key proximity probe — runs every poll (ACC on or off) so we keep observing
        // fob state across the parked-charging window and any "approach unlock" event.
        collectKeyProximity(b);

        // Door open/close POLL fallback — every cycle, ACC on AND off. The HAL stops
        // pushing onDoorStateChanged callbacks when parked, so a door automation only
        // fired with the car on; polling getDoorState(area) here keeps it working parked.
        // Self-guards on "no door listeners" so it's a true no-op without a door rule.
        pollDoorStatesNow();

        // ── Display-only device polls, self-gated per automation event ──────────────
        // These devices were polled ONLY in collectAllFull() (daemon init + on-demand
        // HTTP), so after startup their fields were frozen: the toBuilder() snapshot
        // carried the init value forward and the corresponding automation events never
        // transitioned — so a trigger/condition on them could never fire (field-reported
        // for seatbelt; the same root cause as the gear-P bug). Their HAL listeners cover
        // only a subset (light→DRL only, adas→SLW, settings→CPD/ambient/seatHeat), leaving
        // the rest stale. Poll each on the LIVE path, but ONLY when an enabled automation
        // references its event — anyReferenced() gates the SDK read to zero cost otherwise.
        // Cheap HAL getters, each self-guarded + try/catch inside its collector.
        // Seatbelt (buckled/unbuckled) — instrument device — AND seat occupancy, which is
        // read in the SAME collector off the safety-belt device. The occupant events must be
        // named here too: collectSafetyBelt is the only producer of passengerDetection, so
        // gating it on the belt events alone left occupancy permanently null (never published,
        // so an occupancy trigger/condition could never fire) unless the user happened to also
        // have a seatbelt automation.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.SEATBELT_DRIVER,
                          com.overdrive.app.automation.condition.BydEvent.SEATBELT_PASSENGER,
                          com.overdrive.app.automation.condition.BydEvent.OCCUPANT_PASSENGER,
                          com.overdrive.app.automation.condition.BydEvent.OCCUPANT_DRIVER)) {
            collectSafetyBelt(b);
        }
        // Drive mode + powertrain (EV/HEV) — energy/drive-config device.
        if (!socHalSucceeded) {
            socHalSucceeded = collectEnergy(b, socHalSucceeded);
        } else if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.DRIVE_MODE,
                          com.overdrive.app.automation.condition.BydEvent.POWERTRAIN_MODE)) {
            collectEnergy(b, socHalSucceeded);
        }
        // Lights (hazard / high-beam / low-beam) + auto-lights — light device. The light
        // callback refreshes only DRL, so these need the poll. (DRL stays callback-fed.)
        //
        // NOT automation-only: collectLight is the SOLE producer of lowBeam/highBeam,
        // and three non-automation consumers read them off this snapshot —
        // the burn-in telemetry overlay (OverlayBitmapRenderer LOW_BEAM/HIGH_BEAM
        // fields), /api/vehicle-control's lights block, and the MQTT
        // light_low_beam/light_high_beam topics. Gating purely on anyReferenced()
        // meant that, with no lights automation enabled, the beams were read once
        // in collectAllFull() at init and then carried forward unchanged forever by
        // toBuilder() — so the overlay's beam glyphs were frozen at their boot
        // state (the reported "headlight icons don't work") and MQTT/API reported a
        // stale value. Poll whenever an automation OR the overlay wants beams.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.LIGHTS_HAZARD,
                          com.overdrive.app.automation.condition.BydEvent.LIGHTS_HIGH_BEAM,
                          com.overdrive.app.automation.condition.BydEvent.LIGHTS_LOW_BEAM,
                          com.overdrive.app.automation.condition.BydEvent.AUTO_LIGHTS)
                || overlayWantsBeams()) {
            collectLight(b);
        }
        // Interior ambient MAIN SWITCH — UNGATED, every poll. Deliberately NOT folded into the
        // automation-gated collectLight above, for two reasons:
        //  (1) its non-automation consumers (the HA switch, MQTT ambient_enabled, the /api state
        //      lights object) would otherwise never see a change, because collectAllFull runs
        //      only at daemon init;
        //  (2) one of its two tiers is the carsettings PROVIDER, for which no HAL callback can
        //      ever fire — so unlike beams, the listener cannot cover the gap. On a
        //      provider-only trim the state would latch at its boot value forever, and the HA
        //      toggle would then resolve against a stale "on" and look dead.
        // Cost is two cheap reads (one reflective HAL get + one provider get), both self-guarded
        // and each returning UNAVAILABLE rather than a fabricated 0. Runs regardless of
        // lightDevice, since the provider tier needs no Light device.
        int ambientOn = getAmbientLightEnabled();
        if (ambientOn != BydVehicleData.UNAVAILABLE) b.ambientEnabled(ambientOn);
        // Steering-wheel heater — UNGATED, every poll, for the same reason as the ambient
        // switch above: its producer collectSettings() runs only in collectAllFull() at
        // daemon init, and no settings-HAL callback covers this id (onSettingsCallback
        // handles only CPD, ambient, and the four seat channels). Left init-only, the
        // value would latch at its boot state forever — the /api/vehicle/state tile would
        // then keep reverting the user's own successful toggle on the next poll.
        int wheelHeat = getSteeringWheelHeatingState();
        if (wheelHeat != BydVehicleData.UNAVAILABLE) b.steeringWheelHeat(wheelHeat);
        // Slope (incline degrees) — sensor device.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.SLOPE)) {
            collectSensor(b);
        }
        // Central-lock state (locked/unlocked) — OTA rail (getLFDoorLockState). Published
        // here on the ALWAYS-ALIVE poll loop (both ACC-on and ACC-off) so a "when the car
        // locks/unlocks" automation fires regardless of vehicle state. Previously the only
        // publisher was CameraDaemon.applyLockEvent, which runs solely inside the ACC-off
        // surveillance arm-gate AND early-returns while ACC is on — so the lock trigger was
        // dead outside that narrow window (the reported "lock trigger doesn't fire" bug).
        // Self-gated by anyReferenced so it costs nothing unless a rule keys off it.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.LOCK)) {
            collectLockState();
        }
        // Nearest radar obstacle (cm) — radar/PDC device (parked-radar dependent).
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.RADAR_NEAREST)) {
            collectRadar(b);
        }
        // Air quality PM2.5 — dedicated pm2p5 device. MUST be on this periodic path, not only
        // in collectAllFull(): that runs at daemon init / the ACC-ON edge only, so a value read
        // there is carried forward by toBuilder() unchanged for the whole drive. Cabin PM2.5
        // climbing in a tunnel would never reach a "PM2.5 above X" rule, and — because
        // mergeCloudData below only fills when the field is still UNAVAILABLE — a stale local
        // value would also block the cloud value that used to refresh every poll.
        if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.PM25_INSIDE,
                com.overdrive.app.automation.condition.BydEvent.PM25_OUTSIDE)) {
            collectPm25(b);
        }

        // Cloud data merge (when toggle enabled and data is fresh)
        mergeCloudData(b, cabinTempHalSucceeded, socHalSucceeded, rangeHalSucceeded, fuelHalSucceeded);

            BydVehicleData built = b.build();
            built = publishCollectedSnapshot(built, chargingObserved, pollGeneration);
        }
    }

    /**
     * Force a full collection of ALL data including display-only fields.
     * Bypasses the 5-second throttle. Called by the HTTP API when a client
     * opens the dashboard or requests full vehicle state.
     */
    public synchronized void collectAllFull() {
        try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                     com.overdrive.app.monitor.ChargingDetector.beginPublicationMutation()) {
            lastCoreCollectTime = 0;  // Bypass throttle

            long pollGeneration = chargingPollGeneration.incrementAndGet();
            BydVehicleData.Builder b = (snapshot.get() != null) ? snapshot.get().toBuilder() : new BydVehicleData.Builder();
            b.availableDevices(availableDevices.toArray(new String[0]));
            b.unavailableDevices(unavailableDevices.toArray(new String[0]));

        // Core devices
        collectBodywork(b);
        collectSpeed(b);
        StatisticHalResult statResult = collectStatistic(b);
        boolean socHalSucceeded = statResult.socSucceeded;
        boolean rangeHalSucceeded = statResult.elecRangeSucceeded;
        boolean fuelHalSucceeded = statResult.fuelSucceeded;
        collectSocTarget(b);
        ChargingObservationVersions chargingObserved = collectChargingOrdered(b);
        collectInstrumentOrdered(b, chargingObserved);
        collectEngineOrdered(b, chargingObserved);
        collectOta(b);
        collectGearbox(b);

        // Read AC_TEMP_INSIDE exactly once for this poll and publish that one observation to
        // both legacy cabin fields.
        boolean cabinTempHalSucceeded = collectCabinTemperature(b);

        // Display-only devices (normally listener-driven, polled here on-demand)
        collectAc(b);
        collectLight(b);
        // Ambient main switch — separate from collectLight on purpose (it also has a
        // carsettings-provider tier that works with no Light device); see the ungated call on
        // the incremental path for the full rationale.
        int ambientOn = getAmbientLightEnabled();
        if (ambientOn != BydVehicleData.UNAVAILABLE) b.ambientEnabled(ambientOn);
        collectAdas(b);
        collectSettings(b);
        collectPower(b);
        collectSafetyBelt(b);
        collectTyre(b);
        collectDoorLock(b);
        collectSensor(b);
        socHalSucceeded = collectEnergy(b, socHalSucceeded);
        collectRadar(b);

        // Extended data — core + display-only
        collectStatisticExtended(b);   // SOH, driving time, key battery
        collectInstrumentExtended(b);  // trip data, consumption
        collectChargingExtended(b);    // charging rest time
        collectBodyworkExtended(b);
        // PM2.5 independently of the bodywork device: collectBodyworkExtended early-returns when
        // bodyworkDevice is null, which would silently skip air quality on a trim that HAS the
        // pm2p5 device but no bodywork one. collectPm25 self-guards on its own null device.
        collectPm25(b);                // air quality, inside + outside (µg/m³)
        collectEngineExtended(b);      // coolant, oil, engine code

        // Cloud data merge (when toggle enabled and data is fresh)
        mergeCloudData(b, cabinTempHalSucceeded, socHalSucceeded, rangeHalSucceeded, fuelHalSucceeded);

            BydVehicleData built = b.build();
            built = publishCollectedSnapshot(built, chargingObserved, pollGeneration);
            lastCoreCollectTime = System.currentTimeMillis();
        }
    }

    /**
     * Push the latest snapshot into the fused ChargingDetector so its
     * inference layer can reason about fresh power-flow / gun / gear data.
     * The detector's L1 (BMS edge) and L2 (Power.isCharging) inputs come
     * from listener callbacks and the explicit poll above; this method
     * supplies L3 evidence.
     */
    private void pushChargingEvidence(BydVehicleData built,
                                      ChargingObservationVersions observed,
                                      boolean connectionObserved,
                                      boolean typeObserved,
                                      boolean bmsObserved,
                                      boolean powerObserved) {
        if (built == null) return;
        // AGE-OUT A CARRIED-FORWARD ENGINE-POWER VALUE before the detector sees it.
        //
        // collectEngine's own clear only runs when collectEngine RUNS — and while ACC is off it is
        // skipped entirely unless `possiblyCharging` holds. This method, by contrast, is called on
        // every poll from both entry points, so it is the only place that can guarantee the
        // detector never receives a value older than the freshness window it claims to enforce.
        // Without this, the most common parked case (ACC off, nothing charging) carried the last
        // reading forward indefinitely and the detector re-stamped it fresh every 90 s — and
        // VehicleDataMonitor publishes abs(enginePowerKw) as charging power, so an hours-old
        // reading could surface as a live kW rate (invariants I2/I4).
        if (!Double.isNaN(built.enginePowerKw)
                && built.enginePowerAtMs > 0
                && (System.currentTimeMillis() - built.enginePowerAtMs) >= ENGINE_POWER_LIVE_TTL_MS) {
            synchronized (chargingEdgePublishLock) {
                BydVehicleData current = snapshot.get();
                // Clear only the exact stale sample this poll evaluated. A live engine callback may
                // have published a newer timestamp while the poll was being assembled.
                if (current != null
                        && current.enginePowerAtMs == built.enginePowerAtMs
                        && current.enginePowerAtMs > 0
                        && (System.currentTimeMillis() - current.enginePowerAtMs)
                                >= ENGINE_POWER_LIVE_TTL_MS) {
                    built = current.toBuilder().enginePowerKw(Double.NaN).build();
                    snapshot.set(built);
                } else if (current != null) {
                    built = current;
                }
            }
        }
        // Resolve gear from authoritative GearMonitor (returns last-known
        // value even when its monitor stops on ACC OFF). On a parked car
        // that's always P. The detector uses gear==P as an L3 guard.
        int gearNow;
        try {
            com.overdrive.app.monitor.GearMonitor gm =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            gearNow = gm.getCurrentGear();
        } catch (Exception e) {
            gearNow = (built.gearMode != BydVehicleData.UNAVAILABLE)
                ? built.gearMode
                : com.overdrive.app.monitor.GearMonitor.GEAR_P;
        }
        int effectiveBmsState = (built.chargingState == 1) ? 1 : observed.observedBmsState;
        boolean effectiveBmsObserved = bmsObserved || (built.chargingState == 1);
        boolean effectiveConnectionObserved = connectionObserved || (built.chargingGunState >= 2);
        com.overdrive.app.monitor.ChargingDetector.getInstance()
            .updatePollObservation(
                built, gearNow, com.overdrive.app.monitor.GearMonitor.GEAR_P,
                effectiveConnectionObserved, typeObserved,
                effectiveBmsObserved, effectiveBmsState,
                powerObserved, observed.powerIsCharging);

        // Feed the ring-buffer power estimator (FALLBACK power source for models
        // that report no direct/external charging power). It accumulates ONLY
        // while the fused detector says CHARGING and the car is in Park, and only
        // from a rising charge-energy counter — so regen (gear D/R) and V2L
        // discharge can never produce a phantom reading. See ChargingPowerEstimator.
        try {
            boolean fusedCharging =
                com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
            boolean inPark = (gearNow == com.overdrive.app.monitor.GearMonitor.GEAR_P);
            // SOC-derived energy = SOC × nominal × SOH. The SOC gauge is the ONE
            // signal that reliably tracks charging on every drivetrain, and it's the
            // estimator's PREFERRED source now: on PHEV the hardware energy getters
            // lie (getBatteryRemainPowerEV=0, getBatteryPowerHEV constant,
            // getRemainingBatteryPower FREEZES for tens of minutes while charging),
            // and externalChargingPower reports the EVSE's rated capacity, not the
            // real draw — so SOC-rate is the only truthful charging power on those
            // trims. NaN when SOC or nominal isn't known yet, so the estimator falls
            // back to the remain/cap counters exactly as before.
            // PHEV ONLY. Pass raw SOC% + the SOC→energy scale (nominal × SOH); the
            // estimator FREEZES the scale at session start so socE moves only with
            // SOC, not with mid-charge SohEstimator revisions. On BEV we pass
            // socScaleKwh = NaN so the estimator's socE stays NaN. DC and unknown
            // connector states remain remain-first; confirmed AC cross-checks that
            // field against the session-capacity counter because the Atto 3 exposes
            // a cycling non-energy value through remainKwh.
            // Only PHEV — whose hardware energy counters freeze/lie during charge —
            // needs the SOC-derived source.
            double socPctForEst = built.socPercent;
            double socScaleKwh = Double.NaN;
            // Gated on (fusedCharging && inPark) as well as PHEV: sample() discards everything and
            // calls reset() unless BOTH hold, so on a parked/driving car this whole block — an
            // isPhev() probe, a cross-subsystem getSohEstimator(), getNominalCapacityKwh() and the
            // SOH read — was computed and thrown away on every poll. Both flags are already in hand
            // above, so the gate is free.
            if (fusedCharging && inPark && isPhev(built)) {
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                            soh != null ? soh.getCapacitySohSnapshot() : null;
                    double nominal = capacitySoh != null
                            ? capacitySoh.getNominalCapacityKwh() : 0;
                    if (nominal > 0) {
                        double dSoh = capacitySoh.hasDisplaySoh()
                                ? capacitySoh.getDisplaySoh() : 0;
                        double sohFrac = (dSoh > 0) ? dSoh / 100.0 : 1.0;
                        if (sohFrac <= 0) sohFrac = 1.0;
                        socScaleKwh = nominal * sohFrac;
                    }
                } catch (Throwable ignored) { /* leave NaN → estimator uses remain/cap */ }
            }
            long estimatorSampleAtMs = System.currentTimeMillis();
            com.overdrive.app.monitor.ChargingPowerEstimator.getInstance().sample(
                estimatorSampleAtMs,
                built.chargingCapacityKwh,
                built.remainKwh,
                socPctForEst, socScaleKwh,
                fusedCharging, inPark, built.chargingGunState);
            // Calibrate the charged-energy counter's UNIT against remaining pack energy. Both are read
            // on this same tick, which is what makes the pair comparable; remainKwh is a separate
            // register the charging counter does not feed, which is what makes it independent.
            if (fusedCharging && inPark) {
                com.overdrive.app.charging.CounterScaleCalibrator.observePaired(
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY,
                    built.chargingCapacityKwh, built.remainKwh, estimatorSampleAtMs);
            }
        } catch (Exception e) {
            logger.info("ChargingPowerEstimator.sample failed: " + e.getMessage());
        }
    }

    /**
     * Resolve a raw PHEV remaining-energy reading to the true GROSS frame.
     *
     * <p>The BYD HAL reports PHEV remaining energy at HALF the gross-nameplate
     * scale on its PRIMARY getter ({@code getBatteryRemainPowerEV}). But that
     * getter goes stale when the ICE is running, and the priority cascade then
     * falls back to {@code getRemainingBatteryPower} / {@code getBatteryCapacity},
     * which are NOT necessarily in the same half frame — field-confirmed: on a
     * 21.5 kWh-gross Tang-class DM-i, the live card read a correct ~16.5 kWh at
     * 77% SOC most of the time (half primary ×2), but intermittently jumped to
     * ~22 kWh for the ICE-running window because a near-gross fallback getter was
     * being blindly doubled. That doubled remainKwh also poisoned the SOC capacity
     * heuristic (estimatedCapacity = remainKwh/SOC) and per-trip kWh, so BOTH the
     * displayed remaining AND trip consumption read exactly double until detection
     * re-anchored — hence the "sometimes, especially after a SOH reset" symptom.
     *
     * <p>So a BLANKET ×2 is wrong. Instead, when we have a trustworthy nominal
     * capacity anchor and a valid SOC, pick whichever frame — raw, or raw×2 —
     * implies a pack capacity CLOSEST to nominal. A genuine half reading (implied
     * cap ≈ nominal/2) doubles cleanly; an already-gross fallback (implied cap ≈
     * nominal) is left alone. When a reading can't be placed in either frame
     * within tolerance, return NaN so the caller skips it and keeps the last
     * known-good value rather than writing a wrong one.
     *
     * <p>When no nominal anchor is available yet, only a getter with a known half-frame contract may
     * use the historical ×2. Ambiguous fallback getters are kept raw; otherwise an already-gross
     * series is doubled and its derivative publishes exactly twice the real charging power.
     *
     * @param rawKwh the raw HAL reading (already in kWh, e.g. rawVal/10 for the
     *               0.1-kWh-unit getters)
     * @param socPercent current display SOC, or NaN if unknown this cycle
     * @param assumeHalfWithoutNominal true only for a getter known to use the half frame
     * @return the gross-frame kWh, or NaN if the reading is frame-ambiguous and
     *         should be skipped
     */
    private double phevGrossRemainKwh(double rawKwh, double socPercent,
                                      boolean assumeHalfWithoutNominal) {
        double nominal = 0;
        try {
            com.overdrive.app.abrp.SohEstimator sohEst =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (sohEst != null) nominal = sohEst.getNominalCapacityKwh();
        } catch (Throwable ignored) { /* nominal stays 0 */ }
        return resolvePhevGrossRemainKwh(
                rawKwh, socPercent, nominal, assumeHalfWithoutNominal);
    }

    static double resolvePhevGrossRemainKwh(double rawKwh, double socPercent, double nominal,
                                            boolean assumeHalfWithoutNominal) {
        if (Double.isNaN(rawKwh) || rawKwh <= 0) return rawKwh;
        // No anchor (or no SOC) means the frame cannot be inferred from the value. Preserve ambiguous
        // fallbacks as reported; apply ×2 only where the getter contract supplies that missing fact.
        if (nominal <= 0 || Double.isNaN(socPercent) || socPercent <= 5) {
            return assumeHalfWithoutNominal
                    ? rawKwh * PHEV_ENERGY_HALF_SCALE_CORRECTION : rawKwh;
        }

        // The discriminator is the IMPLIED CAPACITY = remainKwh / SOC, NOT the
        // remaining kWh itself. A reading in the true gross frame implies a pack
        // capacity equal to nominal × SOH; in the half frame it implies half that.
        // The two candidate frames (raw, raw×2) are therefore exactly 2× apart in
        // implied capacity, while a real pack's implied capacity sits in a bounded
        // band below nominal — so at most ONE frame can land in the band, making
        // the choice unambiguous given a trustworthy nominal + SOC.
        //
        // Worked example (the user's challenge): raw = 8.5 kWh.
        //  - at 40% SOC, 8.5 is the TRUE gross value: impliedRaw = 8.5/0.40 = 21.25
        //    (≈ nominal, in band) while impliedDoubled = 42.5 (≫ nominal, rejected)
        //    → return 8.5, NOT doubled. Correct.
        //  - at 77% SOC, 8.5 is the HALF value: impliedRaw = 11.0 (too low, below
        //    band) while impliedDoubled = 17/0.77 = 22.1 (≈ nominal, in band)
        //    → return 17. Correct. SOC breaks the tie; doubling a genuine gross
        //    reading always implies ~2× the pack, which never qualifies.
        double socFraction = socPercent / 100.0;
        double impliedRaw = rawKwh / socFraction;                               // capacity if raw is gross
        double impliedDoubled = (rawKwh * PHEV_ENERGY_HALF_SCALE_CORRECTION) / socFraction; // capacity if raw is half
        double errRaw = Math.abs(impliedRaw - nominal);        // how well "raw is gross" fits the pack
        double errDoubled = Math.abs(impliedDoubled - nominal);// how well "raw is half" fits the pack

        // Pick the frame whose implied capacity fits the known pack, but ONLY when
        // the fit is BOTH (a) within a plausible-capacity tolerance of nominal, and
        // (b) DECISIVELY better than the other frame. The two frames are exactly 2×
        // apart, so for a genuine reading one fits tightly while the other implies
        // ~2× (or ~0.5×) the pack — a clear winner. When the two errors are
        // comparable, the reading is frame-ambiguous (a stale / decoupled getter
        // value that fits neither clean frame), so we SKIP it (return NaN) and the
        // caller keeps the last known-good remainKwh — never writing a doubled value.
        //
        // Tolerance 0.45·nominal on the absolute fit accommodates a degraded pack
        // (implied cap = nominal × SOH, SOH down to ~0.6) plus SOC-curve slop,
        // without being so wide both frames qualify. "Decisive" = the loser's error
        // is at least 2× the winner's — guarantees we only correct/keep when the
        // frame is unambiguous.
        double fitTol = 0.45 * nominal;
        boolean halfWins = errDoubled < errRaw;
        double winErr = halfWins ? errDoubled : errRaw;
        double loseErr = halfWins ? errRaw : errDoubled;
        boolean decisive = winErr <= fitTol && loseErr >= 2.0 * winErr;

        if (!decisive) {
            // Ambiguous — e.g. raw ≈ 11 at 77% on a 21.5 pack implies 14.3 (raw)
            // vs 28.6 (doubled): both ~7 off nominal, neither clean → skip.
            return Double.NaN;
        }
        double chosen = halfWins ? rawKwh * PHEV_ENERGY_HALF_SCALE_CORRECTION : rawKwh;

        // Hard physical ceiling: a pack cannot hold more than its nameplate (plus a
        // little top-balancing / measurement slop). A STALE getter that froze at a
        // full-charge value while SOC has since dropped implies a capacity far above
        // nominal (field bug: 22.4 kWh frozen at 77% SOC → implies 29 kWh on a 21.5
        // pack, ratio 1.35). The fit tolerance above can let such a value through as
        // "already gross", so enforce the ceiling explicitly: if the CHOSEN frame
        // still implies > ~1.12× nominal, reject it (return NaN) so the caller keeps
        // last-good and the SOC-synthesized remaining (which tracks live) takes over.
        double chosenImpliedCap = chosen / socFraction;
        if (chosenImpliedCap > 1.12 * nominal) {
            return Double.NaN;
        }
        return chosen;
    }

    static boolean isPlausibleBevRemainKwh(double remainKwh, double socPercent,
                                            double nominalKwh) {
        if (!Double.isFinite(remainKwh) || remainKwh <= 1.0 || remainKwh >= 120.0) {
            return false;
        }
        if (!Double.isFinite(socPercent) || socPercent <= 5.0) return true;
        double impliedCapacity = remainKwh / (socPercent / 100.0);
        if (nominalKwh > 0) {
            double ratio = impliedCapacity / nominalKwh;
            return ratio >= 0.5 && ratio <= 1.12;
        }
        return impliedCapacity >= 10.0 && impliedCapacity <= 130.0;
    }

    private void collectBodywork(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;
        try {
            // VIN. Prefer getRealAutoVIN — the real 17-character VIN (e.g.
            // LGXCH6CD0R2085367). getAutoVIN returns a HASHED wrapper string on
            // this trim (see AccOffReaders.getRealAutoVin/getAutoVin), which is
            // not a displayable VIN, so it's only a fallback for trims that
            // don't declare the "Real" getter. Both are cheap bodywork getters
            // and live ACC=OFF, and the value is static per vehicle — once a
            // non-empty VIN lands, toBuilder() carries it forward, so a
            // later read miss never blanks it.
            Object vin = BydDeviceHelper.callGetter(bodyworkDevice, "getRealAutoVIN");
            if (!(vin instanceof String) || ((String) vin).trim().isEmpty()) {
                vin = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoVIN");
            }
            if (vin instanceof String && !((String) vin).trim().isEmpty()) {
                b.vin(((String) vin).trim());
            }

            // 12V auxiliary battery voltage (0-255 → 0-25.5V)
            // NOTE: getBatteryPowerValue() returns 12V battery voltage, NOT traction battery SOC.
            // SOC comes from StatisticDevice.getElecPercentageValue() — see collectStatistic().
            Object battPowerRaw = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerValue");
            if (battPowerRaw instanceof Number) {
                double rawVal = ((Number) battPowerRaw).doubleValue();
                double voltage12v = rawVal > 100 ? rawVal / 10.0 : rawVal;
                // Only treat as 12V voltage if it's in a plausible range (8-16V)
                if (voltage12v >= 8.0 && voltage12v <= 16.0) {
                    b.voltage12v(voltage12v);
                }
            }

            // Battery remaining energy — try multiple APIs in priority order.
            // PHEV-first: when computeIsPhev() reports PHEV, BodyworkDevice.getBatteryPowerHEV()
            // is the authoritative source. The Power/Statistic getters echo SOC% in the kWh
            // field on Sealion-class PHEVs (the "SOC-as-kWh" firmware bug), so even with
            // SOC-mimic guards they only ever produce rejects on PHEV. Skipping straight
            // to HEV avoids two reflective probe attempts every cycle and removes the
            // window where a freshly-classified-PHEV vehicle could still latch a bogus
            // BEV reading before computeIsPhev() updates.
            //
            // NOTE: We deliberately do NOT gate the priority reads on
            // `Double.isNaN(b.remainKwh)`. Because `b` is built from the previous snapshot
            // via toBuilder(), gating on NaN means we only ever read these getters ONCE
            // (the very first poll after init), and the value freezes thereafter —
            // observable as "Remaining kWh stuck at last seen value when the vehicle is
            // off". The validation block below already protects the cached value from HAL
            // garbage: out-of-range readings are skipped (not written), so the last-known
            // good value is preserved when the BYD HAL goes flaky after ACC OFF.
            //
            // We track whether any priority wrote a fresh kWh this cycle so the
            // capacity fallback (older SDKs only) doesn't clobber it.
            boolean kwhWrittenThisCycle = false;
            boolean isPhevForKwh = isPhev(b);
            double bevNominalKwh = 0;
            if (!isPhevForKwh) {
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    if (soh != null) bevNominalKwh = soh.getNominalCapacityKwh();
                } catch (Throwable ignored) { /* generic implied-capacity gate remains available */ }
            }

            // PHEV: read getBatteryPowerHEV ONLY to populate socHevPercent (telemetry)
            // and STASH it as a last-resort remainKwh fallback — it is not the
            // PHEV-primary energy source. Its frame varies across firmware, so it is resolved
            // against nominal capacity when available and otherwise preserved raw rather than
            // blindly doubled. getBatteryRemainPowerEV (Priority 1 below) remains preferred;
            // HEV is retained so firmwares where that getter echoes SOC% still get some reading.
            double phevHevKwh = Double.NaN;
            boolean phevHevKwhUsable = false;
            if (isPhevForKwh && bodyworkDevice != null) {
                try {
                    Object hev = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
                    if (hev instanceof Number) {
                        double hevVal = ((Number) hev).doubleValue();
                        if (hevVal >= 0) {
                            // socHevPercent telemetry + the SOC-mimic check stay on the
                            // RAW value (the check detects firmware echoing SOC% in the
                            // kWh field — comparing a corrected value to SOC% would break
                            // it). The stashed remainKwh fallback is frame-resolved to the
                            // gross frame (raw vs raw×2 vs nominal anchor — see
                            // phevGrossRemainKwh) so a near-gross HEV reading isn't blindly
                            // doubled.
                            b.socHevPercent(hevVal);
                            double soc = b.socPercent;
                            boolean looksLikeSocPercent = !Double.isNaN(soc)
                                    && soc > 0 && Math.abs(hevVal - soc) < 3.0;
                            double hevKwh = phevGrossRemainKwh(hevVal, soc, false);
                            if (!looksLikeSocPercent && !Double.isNaN(hevKwh)
                                    && hevKwh > 1 && hevKwh < 120) {
                                phevHevKwh = hevKwh;
                                phevHevKwhUsable = true;
                            } else if (looksLikeSocPercent) {
                                logger.debug("getBatteryPowerHEV returned " +
                                    String.format("%.1f", hevVal) + " ≈ SOC " +
                                    String.format("%.1f", soc) + "% — treating as SOC%, not kWh");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getBatteryPowerHEV (PHEV socHev/stash) failed: " + e.getMessage());
                }
            }

            // Priority 1 (BEV and PHEV): PowerDevice.getBatteryRemainPowerEV() — the
            // most accurate remaining-energy source on both drivetrains. On PHEVs the
            // HAL reports it at HALF the true gross scale, so we apply the half-scale
            // correction (×2) immediately on read — the gross value then passes the
            // implied-capacity gate below (a half value implies ~9 kWh and would fail
            // it). It may go stale when the ICE is running, which the implied-capacity
            // gate rejects. The SOC-as-kWh guard skips firmwares that echo SOC% here
            // (checked against the RAW value), falling through to the PHEV HEV
            // last-resort fallback further down.
            if (!kwhWrittenThisCycle && powerDevice != null) {
                try {
                    Object evKwh = BydDeviceHelper.callGetter(powerDevice, "getBatteryRemainPowerEV");
                    if (evKwh instanceof Number) {
                        double evRaw = ((Number) evKwh).doubleValue();
                        // Frame-resolve on PHEV (raw may be half OR already gross when
                        // the ICE-running fallback wins). NaN = frame-ambiguous → the
                        // `evVal > 1` guard below skips it, keeping the last good value.
                        double evVal = isPhevForKwh
                                ? phevGrossRemainKwh(evRaw, b.socPercent, true) : evRaw;
                        if (evVal > 1 && evVal < 120) {
                            // Validate: implied capacity should be within 50-150% of any BYD pack
                            double soc = b.socPercent;
                            if (!Double.isNaN(soc) && soc > 5) {
                                // SOC-as-kWh PHEV firmware bug: HAL echoes SOC% in the kWh field.
                                // Reject before the implied-capacity range check, because at
                                // SOC=84 the bogus 84.1 produces impliedCap=100, which falls
                                // inside the 10-130 BEV-friendly window and would otherwise
                                // be accepted. When this is the SOC-mimic bug, let the
                                // slot stay NaN so a later priority / the PHEV HEV
                                // last-resort fallback fills it. Checked against evRaw —
                                // the half-scale ×2 would push a genuine SOC echo out of
                                // the ±5 window and defeat the guard.
                                boolean looksLikeSocMimic = Math.abs(evRaw - soc) < 5.0;
                                double impliedCap = evVal / (soc / 100.0);
                                boolean plausibleFrame = isPhevForKwh
                                        ? impliedCap >= 10 && impliedCap <= 130
                                        : isPlausibleBevRemainKwh(
                                                evVal, soc, bevNominalKwh);
                                if (looksLikeSocMimic) {
                                    logger.debug("getBatteryRemainPowerEV rejected: raw " +
                                        String.format("%.1f", evRaw) + " ≈ SOC " +
                                        String.format("%.0f", soc) + "% — SOC-as-kWh firmware bug");
                                } else if (plausibleFrame) {
                                    b.remainKwh(evVal);
                                    kwhWrittenThisCycle = true;
                                    logger.debug("remainKwh from getBatteryRemainPowerEV: " +
                                        String.format("%.1f", evVal));
                                } else {
                                    logger.debug("getBatteryRemainPowerEV rejected: " +
                                        String.format("%.1f", evVal) + " kWh at " +
                                        String.format("%.0f", soc) + "% SOC → implied " +
                                        String.format("%.1f", impliedCap) + " kWh");
                                }
                            } else if (Double.isNaN(b.remainKwh) && !isPhevForKwh) {
                                // No SOC to validate against (cold boot, SOC read by the
                                // later collectStatistic). Accept the unvalidated reading
                                // ONLY on BEV first-poll — a BEV's getBatteryRemainPowerEV
                                // is its authoritative source and a one-poll seed is safe.
                                // On PHEV we deliberately DON'T accept here: this getter is
                                // PHEV-primary now, and without SOC we cannot run the
                                // SOC-mimic guard — a firmware echoing SOC% would seed a
                                // bogus remainKwh that flows raw into MQTT/trip accounting
                                // before the downstream ratio gates ever see it. Defer one
                                // poll until SOC arrives and the guarded branch can run.
                                b.remainKwh(evVal);
                                kwhWrittenThisCycle = true;
                            } else if (Double.isNaN(b.remainKwh) && isPhevForKwh) {
                                logger.debug("getBatteryRemainPowerEV " +
                                    String.format("%.1f", evVal) + " kWh held on PHEV — no SOC "
                                    + "yet to validate against; deferring one poll");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getBatteryRemainPowerEV failed: " + e.getMessage());
                }
            }

            // Priority 2: StatisticDevice.getRemainingBatteryPower() — returns int (0.1 kWh units)
            // Only consulted when Priority 1 did NOT succeed this cycle. Without this guard,
            // both sources race every cycle and last-writer-wins; on Seal we observed
            // Priority 1 reporting 16.5 kWh (correct → 82.5 kWh nominal at 20% SOC) being
            // overwritten by Priority 2 reporting 20.6 kWh (wrong → 103 kWh implied), which
            // poisoned every downstream auto-detection.
            if (!kwhWrittenThisCycle && statisticDevice != null) {
                try {
                    Object rawPower = BydDeviceHelper.callGetter(statisticDevice, "getRemainingBatteryPower");
                    if (rawPower instanceof Number) {
                        int rawVal = ((Number) rawPower).intValue();
                        if (rawVal > 10 && rawVal < 1200) {  // 1-120 kWh in 0.1 units
                            double kwhRaw = rawVal / 10.0;
                            // Frame-resolve on PHEV (raw may be half OR already gross).
                            // BEV unchanged. NaN = ambiguous → skipped by the impliedCap
                            // gate below, keeping the last good value.
                            double kwh = isPhevForKwh
                                    ? phevGrossRemainKwh(kwhRaw, b.socPercent, false) : kwhRaw;
                            // Validate against SOC
                            double soc = b.socPercent;
                            if (!Double.isNaN(soc) && soc > 5) {
                                // SOC-as-kWh PHEV firmware bug — same guard as Priority 1.
                                // The Sealion 6 DM-i HAL returns raw=841 (84.1 kWh) at 84% SOC;
                                // impliedCap=100 passes a generic [10,130] gate even though
                                // the pack is only 18.3 kWh. Reject so a later priority /
                                // the PHEV HEV last-resort fallback fills this slot. Checked
                                // against the RAW kWh — the ×2 would defeat the ±5 SOC window.
                                boolean looksLikeSocMimic = Math.abs(kwhRaw - soc) < 5.0;
                                double impliedCap = kwh / (soc / 100.0);
                                boolean plausibleFrame = isPhevForKwh
                                        ? impliedCap >= 10 && impliedCap <= 130
                                        : isPlausibleBevRemainKwh(
                                                kwh, soc, bevNominalKwh);
                                if (looksLikeSocMimic) {
                                    logger.debug("getRemainingBatteryPower rejected: raw " +
                                        String.format("%.1f", kwhRaw) + " ≈ SOC " +
                                        String.format("%.0f", soc) + "% — SOC-as-kWh firmware bug (raw=" +
                                        rawVal + ")");
                                } else if (plausibleFrame) {
                                    b.remainKwh(kwh);
                                    kwhWrittenThisCycle = true;
                                    logger.debug("remainKwh from getRemainingBatteryPower: " +
                                        String.format("%.1f", kwh) + " (raw=" + rawVal + ")");
                                }
                            } else if (Double.isNaN(b.remainKwh) && !isPhevForKwh) {
                                // No SOC to validate. BEV first-poll seed only — on PHEV we
                                // defer until SOC arrives so the SOC-mimic guard can run
                                // (see Priority 1 note: raw remainKwh flows into MQTT/trip
                                // accounting ungated, so an unvalidated PHEV seed is unsafe).
                                b.remainKwh(kwh);
                                kwhWrittenThisCycle = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getRemainingBatteryPower failed: " + e.getMessage());
                }
            }

            // PHEV last-resort fallback: the getBatteryPowerHEV value stashed above
            // (already ×2-corrected to the gross frame). Only used when
            // getBatteryRemainPowerEV (Priority 1) and getRemainingBatteryPower
            // (Priority 2) both failed to produce a usable reading this cycle — e.g.
            // a firmware where the EV getter genuinely echoes SOC% and gets rejected.
            if (isPhevForKwh && !kwhWrittenThisCycle && phevHevKwhUsable
                    && !Double.isNaN(phevHevKwh)) {
                b.remainKwh(phevHevKwh);
                kwhWrittenThisCycle = true;
                logger.debug("remainKwh from getBatteryPowerHEV (PHEV last-resort fallback, "
                    + "may under-report): " + String.format("%.1f", phevHevKwh));
            }

            // BEV-side fallback: BodyworkDevice.getBatteryPowerHEV() also runs for BEVs
            // when Priority 1/2 didn't yield a value, in case a particular BEV firmware
            // exposes remaining kWh here too. Skipped when PHEV-priority above already
            // handled this getter.
            if (!isPhevForKwh && !kwhWrittenThisCycle && bodyworkDevice != null) {
                try {
                    Object hev = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
                    if (hev instanceof Number) {
                        double hevVal = ((Number) hev).doubleValue();
                        if (hevVal >= 0) {
                            b.socHevPercent(hevVal);
                            double soc = b.socPercent;
                            boolean looksLikeSocPercent = !Double.isNaN(soc)
                                    && soc > 0 && Math.abs(hevVal - soc) < 3.0;
                            if (!looksLikeSocPercent
                                    && isPlausibleBevRemainKwh(
                                            hevVal, soc, bevNominalKwh)) {
                                b.remainKwh(hevVal);
                                kwhWrittenThisCycle = true;
                                logger.debug("remainKwh from getBatteryPowerHEV (BEV-fallback): " +
                                    String.format("%.1f", hevVal) + " (soc=" +
                                    String.format("%.1f", soc) + "%)");
                            } else if (looksLikeSocPercent) {
                                logger.debug("getBatteryPowerHEV returned " +
                                    String.format("%.1f", hevVal) + " ≈ SOC " +
                                    String.format("%.1f", soc) + "% — treating as SOC%, not kWh");
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getBatteryPowerHEV failed: " + e.getMessage());
                }
            }

            // getBatteryCapacity() — semantics vary by model:
            // - Newer models: returns Ah rating (fixed, e.g. 150 for Atto 3)
            // - Older models: returns remaining energy in 0.1 kWh units (changes with SOC)
            // Used as remainKwh fallback when prior priorities haven't filled it.
            Object cap = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryCapacity");
            if (cap instanceof Number) {
                double capVal = ((Number) cap).doubleValue();
                if (capVal > 0) b.capacityAh(capVal);

                // Fallback for older models where Priorities 1/2 are unavailable:
                // getBatteryCapacity() returns remaining energy in 0.1 kWh units (changes
                // with SOC). Skip when the value looks like a static Ah rating (50-350
                // range, handled by the SOH-feed block above) — otherwise we'd overwrite
                // a real kWh reading with an Ah number scaled by 10. Also skip if
                // priorities 1 or 2 already wrote a fresh validated kWh this cycle, so
                // this fallback truly stays a fallback.
                //
                // No `Double.isNaN(b.remainKwh)` guard here: on the older SDKs that need
                // this fallback, this is the only signal, and gating on NaN would freeze
                // it at the first poll's value (the bug this whole block was rewritten
                // to fix). The 1-120 kWh sanity window protects against junk readings.
                boolean looksLikeAhRating = (capVal >= 50 && capVal <= 350);
                if (!kwhWrittenThisCycle && !looksLikeAhRating && capVal > 0) {
                    // Frame-resolve on PHEV (raw may be half OR already gross).
                    // BEV unchanged. NaN (frame-ambiguous) is excluded by the
                    // 1-120 window below, keeping the last good value.
                    double kwhFromCap = isPhevForKwh
                            ? phevGrossRemainKwh(capVal / 10.0, b.socPercent, false)
                            : (capVal / 10.0);
                    // Plausible remaining energy range for any BYD model: 1-120 kWh
                    if (isPhevForKwh
                            ? kwhFromCap > 1.0 && kwhFromCap < 120.0
                            : isPlausibleBevRemainKwh(
                                    kwhFromCap, b.socPercent, bevNominalKwh)) {
                        b.remainKwh(kwhFromCap);
                    }
                }
            }

            // ── PHEV energy diagnostic (INFO, throttled) ───────────────────
            // Dumps every remaining-energy getter's RAW value side-by-side with
            // SOC and the resolved remainKwh, so a captured log proves which
            // getter tracks SOC live and which goes stale (the frozen-22.4 bug).
            // PHEV-only, ~once/min, never on the hot path otherwise.
            if (isPhevForKwh) {
                long nowDiag = System.currentTimeMillis();
                if (nowDiag - lastPhevEnergyDiagMs >= PHEV_ENERGY_DIAG_INTERVAL_MS) {
                    lastPhevEnergyDiagMs = nowDiag;
                    try {
                        String evStr = "n/a", rbpStr = "n/a", hevStr = "n/a", capStr = "n/a";
                        if (powerDevice != null) {
                            Object o = BydDeviceHelper.callGetter(powerDevice, "getBatteryRemainPowerEV");
                            if (o instanceof Number) evStr = String.format("%.2f", ((Number) o).doubleValue());
                        }
                        if (statisticDevice != null) {
                            Object o = BydDeviceHelper.callGetter(statisticDevice, "getRemainingBatteryPower");
                            if (o instanceof Number) rbpStr = ((Number) o).intValue() + " (raw/10=" + String.format("%.1f", ((Number) o).intValue() / 10.0) + ")";
                        }
                        if (bodyworkDevice != null) {
                            Object o = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
                            if (o instanceof Number) hevStr = String.format("%.2f", ((Number) o).doubleValue());
                            Object c = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryCapacity");
                            if (c instanceof Number) capStr = String.format("%.2f", ((Number) c).doubleValue());
                        }
                        double socNow = b.socPercent;
                        double nominalNow = 0;
                        try {
                            com.overdrive.app.abrp.SohEstimator se =
                                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                            if (se != null) nominalNow = se.getNominalCapacityKwh();
                        } catch (Throwable ignored) {}
                        logger.info("[phev-energy] SOC=" + String.format("%.1f", socNow)
                            + "% nominal=" + String.format("%.1f", nominalNow)
                            + " | getBatteryRemainPowerEV=" + evStr
                            + " | getRemainingBatteryPower=" + rbpStr
                            + " | getBatteryPowerHEV=" + hevStr
                            + " | getBatteryCapacity=" + capStr
                            + " | RESOLVED remainKwh=" + (Double.isNaN(b.remainKwh) ? "NaN" : String.format("%.2f", b.remainKwh))
                            + " (impliedCap=" + (socNow > 5 && !Double.isNaN(b.remainKwh)
                                ? String.format("%.1f", b.remainKwh / (socNow / 100.0)) : "n/a") + ")");
                    } catch (Throwable t) {
                        logger.debug("[phev-energy] diagnostic failed: " + t.getMessage());
                    }
                }
            }

            // Power level
            Object pl = BydDeviceHelper.callGetter(bodyworkDevice, "getPowerLevel");
            if (pl instanceof Number) b.powerLevel(((Number) pl).intValue());

            // getEnergyType removed — observed returning 1 on both BEV and PHEV
            // firmwares, so it cannot be trusted as a drivetrain discriminator.
            // PHEV detection now uses live fuel HAL signals (computeIsPhev).

            // Battery temp from bodywork (feature ID 300941320, Double.TYPE)
            Object battTemp = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_BATTERY_METRIC, Double.class);
            if (battTemp != null) {
                double tempVal = BydDeviceHelper.getDoubleValue(battTemp);
                if (!Double.isNaN(tempVal) && tempVal > -50 && tempVal < 80) b.bodyworkBattTempC(tempVal);
            }

            // Battery range from bodywork (feature ID 300941336).
            // INT-FIRST probe, because the extraction below is getIntValue: the HAL fills only
            // the BYDAutoEventValue field matching the width it was asked for, so requesting
            // Double left intValue at its default 0 — and 0 passed the range check, publishing a
            // hard "0 km" every poll (which fires a "range below X" automation permanently and
            // publishes bodywork_range_km=0 over MQTT). Ask for the width we read.
            Object battRange = BydDeviceHelper.callGetProbing(
                    bodyworkDevice, BydFeatureIds.BODYWORK_BATTERY_RANGE, true);
            if (battRange != null) {
                int rangeVal = BydDeviceHelper.getIntValue(battRange);
                // > 0, not >= 0: 0 is what a failed/absent read yields here, and a real pack
                // reporting exactly 0 km is indistinguishable from it, so treat it as no reading.
                if (rangeVal > 0 && rangeVal <= 1016) b.bodyworkRangeKm((int) Math.round(rangeVal * distanceToKmFactor));
            }

            // Window open percent (positions 1-6). Via readWindowPercent so the 0..100 domain check
            // is applied here too — a rail (65535) or any out-of-domain value published verbatim
            // renders as a window position in the UI and over MQTT. -1 = no reading.
            int[] windows = new int[6];
            for (int i = 0; i < 6; i++) {
                windows[i] = readWindowPercent(i + 1);
            }
            b.windowOpenPercent(windows);

            // Emergency alarm. SENTINEL-GATED, unlike the original write.
            //
            // This read was previously dead (it passed a wrapper Class to a HAL that matches
            // primitives only), so an unvalidated store was harmless. Now that the read can
            // succeed, a trim that answers with a sentinel instead of throwing would store
            // e.g. -10011 — and BydEvent treats "not UNAVAILABLE and not 0" as ALARM ACTIVE,
            // so every user automation with an emergency-alarm trigger would fire spuriously
            // and MQTT would publish the sentinel. A sentinel means "no reading", which for a
            // boolean-ish alarm is the OPPOSITE of what an unguarded store implies, so it must
            // be filtered rather than passed through.
            Object alarm = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_EMERGENCY_ALARM, Integer.class);
            if (alarm != null) {
                int alarmRaw = BydDeviceHelper.getIntValue(alarm);
                if (alarmRaw != BydFeatureIds.BMS_UNAVAILABLE
                        && alarmRaw != BydFeatureIds.INVALID_VALUE
                        && alarmRaw != BydFeatureIds.INVALID_VALUE_2
                        && alarmRaw != Integer.MIN_VALUE
                        && alarmRaw >= 0) {
                    b.emergencyAlarmState(alarmRaw);
                }
            }

        } catch (Exception e) {
            logger.debug("collectBodywork error: " + e.getMessage());
        }
    }

    private void collectSpeed(BydVehicleData.Builder b) {
        if (speedDevice == null) return;
        try {
            Object speed = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
            if (speed instanceof Number) {
                double v = ((Number) speed).doubleValue();
                double speedKmh = convertRawSpeedToKmh(v, getSpeedToKmhFactor());
                if (!Double.isNaN(speedKmh)) b.speedKmh(speedKmh);
            }
            // Guard the SDK_NOT_AVAILABLE sentinel exactly like getCurrentSpeed above:
            // getAccelerateDeepness/getBrakeDeepness can return it, and it is NOT the
            // BydVehicleData.UNAVAILABLE (Integer.MIN_VALUE) that the automation
            // publish-guard checks — so an unguarded sentinel would flow through as a
            // bogus ~-2.1e9 pedal value and false-fire a "pedal < N" automation.
            Object accel = BydDeviceHelper.callGetter(speedDevice, "getAccelerateDeepness");
            if (accel instanceof Number) {
                int a = ((Number) accel).intValue();
                if (a != BydFeatureIds.SDK_NOT_AVAILABLE) b.accelPercent(a);
            }
            Object brake = BydDeviceHelper.callGetter(speedDevice, "getBrakeDeepness");
            if (brake instanceof Number) {
                int br = ((Number) brake).intValue();
                if (br != BydFeatureIds.SDK_NOT_AVAILABLE) b.brakePercent(br);
            }
        } catch (Exception e) {
            logger.debug("collectSpeed error: " + e.getMessage());
        }
    }

    private void collectEngine(BydVehicleData.Builder b) {
        if (engineDevice == null) return;
        try {
            // ==================== ENGINE SPEED ====================
            // Feature ID path first — try ENGINE_SPEED (339738642), then ENGINE_SPEED_GB (282066952)
            try {
                Object val = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_SPEED, Integer.class);
                if (val != null) {
                    int raw = BydDeviceHelper.getIntValue(val);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                        && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0 && raw <= 8000) {
                        b.engineSpeedRpm(raw);
                    }
                }
                // Try alternate signal if primary didn't populate
                if (b.engineSpeedRpm == BydVehicleData.UNAVAILABLE) {
                    Object altVal = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_SPEED_ALT, Integer.class);
                    if (altVal != null) {
                        int altRaw = BydDeviceHelper.getIntValue(altVal);
                        if (altRaw != BydFeatureIds.BMS_UNAVAILABLE && altRaw != BydFeatureIds.INVALID_VALUE
                            && altRaw != BydFeatureIds.INVALID_VALUE_2 && altRaw >= 0 && altRaw <= 8000) {
                            b.engineSpeedRpm(altRaw);
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("collectEngine engineSpeed feature ID error: " + e.getMessage());
            }
            // Fallback to typed getter if feature ID didn't populate
            if (b.engineSpeedRpm == BydVehicleData.UNAVAILABLE) {
                Object rpm = BydDeviceHelper.callGetter(engineDevice, "getEngineSpeed");
                if (rpm instanceof Number) {
                    int rpmVal = ((Number) rpm).intValue();
                    if (rpmVal >= 0 && rpmVal <= 8000) b.engineSpeedRpm(rpmVal);
                }
            }

            // ==================== ENGINE POWER ====================
            // Net HV-bus power: positive = motor draw, negative = into battery (regen
            // when driving, plug-in charging when parked).
            //
            // Feature ID path returns a Double in mixed units across firmware:
            //   - On most models: kW (range roughly -200..400)
            //   - On some models: deciwatts × 10 (raw > 100 → scale ×0.1)
            // Range-check excludes sentinels (BMS_UNAVAILABLE etc.) and bogus values.
            //
            // IMPORTANT: the builder is seeded from the PREVIOUS snapshot
            // (toBuilder()), so b.enginePowerKw already carries last cycle's
            // value and is almost never NaN. The typed-getter fallback below must
            // therefore NOT gate on isNaN — that would lock the getter out forever
            // once any read succeeds, and the value would freeze ("correct but
            // stuck") on every cycle the flaky feature-ID read returns null.
            // Track whether THIS cycle wrote a live value instead.
            boolean powerWritten = false;
            String powerSource = "carry-forward";
            double powerRaw = Double.NaN;
            try {
                long powerObservedAtMs = System.currentTimeMillis();
                Object val = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_POWER, Double.class);
                if (val != null) {
                    double raw = BydDeviceHelper.getDoubleValue(val);
                    double kw = scaleEnginePowerKw(raw);
                    if (!Double.isNaN(raw) && raw >= -200.0 && raw <= 400.0
                            && !isEnginePowerSentinel(kw)
                            && allowsEnginePowerObservation(
                                    b, kw, powerObservedAtMs)) {
                        b.enginePowerKw(kw).enginePowerAtMs(powerObservedAtMs);
                        powerWritten = true;
                        powerSource = "featureId";
                        powerRaw = raw;
                    }
                }
            } catch (Exception e) {
                logger.debug("collectEngine enginePower feature ID error: " + e.getMessage());
            }
            // Fallback to typed getter whenever the feature-ID read did NOT write a
            // fresh value THIS cycle — a live re-read that keeps the value tracking
            // instead of freezing on the carried-forward number.
            if (!powerWritten) {
                long powerObservedAtMs = System.currentTimeMillis();
                Object power = BydDeviceHelper.callGetter(engineDevice, "getEnginePower");
                if (power instanceof Number) {
                    double kw = ((Number) power).doubleValue();
                    // ACC-OFF sign gate (mirrors the listener path): with the key
                    // removed the only plausible engine-power direction is current
                    // INTO the pack (kw < 0, plug-in charging). Positive readings
                    // are stale ECU residue — reject so this fresh-read path can't
                    // feed the ChargingDetector a spurious "engine running" value.
                    boolean accOffReject = !accIsOn && kw > -ENGINE_POWER_CHARGING_DEADBAND;
                    if (kw >= -200.0 && kw <= 400.0 && !accOffReject
                            && !isEnginePowerSentinel(kw)
                            && allowsEnginePowerObservation(
                                    b, kw, powerObservedAtMs)) {
                        b.enginePowerKw(kw).enginePowerAtMs(powerObservedAtMs);
                        powerWritten = true;
                        powerSource = "getter";
                        powerRaw = kw;
                    }
                }
            }
            // NO LIVE VALUE THIS CYCLE → publish NaN rather than the carried-forward number.
            //
            // The builder is seeded from the previous snapshot via toBuilder(), so without this
            // the field is non-NaN forever after the first successful read. That silently broke
            // ChargingDetector's freshness contract: it stamps enginePowerAtMs inside
            // `if (!Double.isNaN(vd.enginePowerKw))`, so a carried-forward value was re-stamped
            // as fresh on every push and ENGINE_POWER_FRESHNESS_MS (15 s) could never expire —
            // it stamps the timestamp and then recompute()s microseconds later, making
            // engineFresh unconditionally true from the poll path. Consequence: once a genuine
            // negative (e.g. -5 kW regen) was read, every later poll rejected the frozen sentinel,
            // the old value persisted, and the detector kept counting it as "current into pack"
            // for as long as ACC stayed on — phantom L3 CHARGING on a parked car.
            //
            // Clearing here makes that NaN guard load-bearing and lets the freshness window mean
            // what it says (invariant I2: a stuck value is not a measurement). Safe against a
            // one-off flaky read: the detector needs HYSTERESIS_SAMPLES (3) consecutive
            // observations, so a single blank cycle cannot flip a verdict. Distinct from the
            // :1955-1962 warning, which forbids gating the fallback READ on isNaN — not clearing
            // after BOTH reads have failed.
            //
            // EXCEPT when the value we would wipe is a FRESH LISTENER value. onEngineCallback's
            // ENGINE_POWER branch writes enginePowerKw straight into the snapshot between polls,
            // and only the poll path calls pushChargingEvidence — so on the PHEV firmware where
            // the listener is the dominant refresh (90 s parked polls) a blanket clear here would
            // discard the only live reading before the detector ever saw it, and could BREAK
            // charging detection rather than just de-latching a phantom.
            //
            // Freshness is judged from the stamp CARRIED IN THE SNAPSHOT (enginePowerAtMs), and the
            // bound is the detector's own window — not a separate 120 s TTL. A longer TTL here was
            // self-defeating: it kept a value non-NaN for up to 24 five-second polls while the
            // detector re-stamped it fresh each time, reproducing the pre-fix phantom for anything
            // in the 15-120 s band. Aligning to the consumer's window means a value survives here
            // only while the consumer would still call it fresh.
            if (!powerWritten) {
                long liveAt = b.enginePowerAtMs;
                boolean listenerFresh = liveAt > 0
                        && (System.currentTimeMillis() - liveAt) < ENGINE_POWER_LIVE_TTL_MS
                        && allowsEnginePowerObservation(b, b.enginePowerKw, liveAt);
                if (!listenerFresh) {
                    b.enginePowerKw(Double.NaN);   // also zeroes enginePowerAtMs (see the setter)
                    powerSource = "cleared-stale";
                } else {
                    powerSource = "live-recent";
                    powerRaw = b.enginePowerKw;
                }
            }
            // Diagnostic (throttled 1/min, INFO so it lands in default captures):
            // confirms the value is refreshing each poll and which source won. If
            // this logs source=carry-forward repeatedly while driving, BOTH live
            // reads are missing and the value is genuinely stuck at the HAL layer.
            long powerNow = System.currentTimeMillis();
            if (powerNow - lastEnginePowerLogMs > 60_000L) {
                lastEnginePowerLogMs = powerNow;
                logger.info(String.format(java.util.Locale.US,
                    "enginePower resolved=%.2fkW source=%s raw=%.1f",
                    b.enginePowerKw, powerSource, powerRaw));
            }

            // Motor speed / torque. Sentinel-gated for the same reason as the emergency-alarm
            // read above: these stores were unvalidated but harmless while the wrapper-Class
            // read was dead. Now that it can succeed, a sentinel would land in the snapshot
            // (and be NEGATED for the front pair, turning -10011 into a plausible +10011 rpm).
            // Downstream MQTT happens to gate these, but the snapshot is shared and a future
            // consumer without a gate would inherit the bug — so filter at the source.
            //
            // Front motor speed (negated)
            Object fms = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_FRONT_MOTOR_SPEED, Integer.class);
            if (fms != null) {
                int v = BydDeviceHelper.getIntValue(fms);
                if (isPlausibleMotorRpm(v)) b.frontMotorSpeed(-v);
            }

            // Rear motor speed
            Object rms = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_REAR_MOTOR_SPEED, Integer.class);
            if (rms != null) {
                int v = BydDeviceHelper.getIntValue(rms);
                if (isPlausibleMotorRpm(v)) b.rearMotorSpeed(v);
            }

            // Front motor torque (negated double)
            Object fmt = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_FRONT_MOTOR_TORQUE, Double.class);
            if (fmt != null) {
                double t = BydDeviceHelper.getDoubleValue(fmt);
                // ±2000 Nm covers every BYD drivetrain with margin; sentinels are far outside.
                if (!Double.isNaN(t) && Math.abs(t) <= 2000.0) b.frontMotorTorque(-t);
            }
        } catch (Exception e) {
            logger.debug("collectEngine error: " + e.getMessage());
        }
    }

    private boolean allowsEnginePowerObservation(BydVehicleData.Builder context,
                                                 double powerKw,
                                                 long observedAtMs) {
        BydVehicleData current = context.build();
        return allowsEnginePowerObservation(
                current.chargingState, current.chargingGunState, current.vtolCharging,
                powerKw, accIsOn, isTerminalChargingBarrierActive(),
                current.chargingStateAtMs, observedAtMs,
                hasCoherentPostFinishedRateProof(current, observedAtMs));
    }

    /**
     * Plausibility gate for a raw motor-RPM reading. Rejects the BYD "no value" sentinels and
     * anything past ±25000 rpm (no BYD motor spins near that), so a sentinel can never be
     * negated into a plausible-looking positive rpm.
     */
    private static boolean isPlausibleMotorRpm(int v) {
        if (v == BydFeatureIds.BMS_UNAVAILABLE || v == BydFeatureIds.INVALID_VALUE
                || v == BydFeatureIds.INVALID_VALUE_2 || v == Integer.MIN_VALUE) return false;
        return Math.abs(v) <= 25000;
    }

    static final class StatisticHalResult {
        final boolean socSucceeded;
        final boolean elecRangeSucceeded;
        final boolean fuelSucceeded;

        StatisticHalResult(boolean socSucceeded, boolean elecRangeSucceeded, boolean fuelSucceeded) {
            this.socSucceeded = socSucceeded;
            this.elecRangeSucceeded = elecRangeSucceeded;
            this.fuelSucceeded = fuelSucceeded;
        }
    }

    private StatisticHalResult collectStatistic(BydVehicleData.Builder b) {
        if (statisticDevice == null) return new StatisticHalResult(false, false, false);
        boolean socSucceeded = false;
        boolean elecRangeSucceeded = false;
        boolean fuelSucceeded = false;
        try {
            // ==================== TOTAL MILEAGE ====================
            // Named getter primary, feature ID fallback
            Object mileage = BydDeviceHelper.callGetter(statisticDevice, "getTotalMileageValue");
            if (mileage instanceof Number) {
                int raw = ((Number) mileage).intValue();
                if (raw > 0) b.totalMileageKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.totalMileageKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_TOTAL_MILEAGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.totalMileageKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic totalMileage feature ID error: " + e.getMessage());
                }
            }

            // ==================== EV MILEAGE ====================
            // Named getter primary, feature ID fallback
            Object evMileage = BydDeviceHelper.callGetter(statisticDevice, "getEVMileageValue");
            if (evMileage instanceof Number) {
                int raw = ((Number) evMileage).intValue();
                if (raw > 0) b.evMileageKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.evMileageKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_MILEAGE_EV, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.evMileageKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic evMileage feature ID error: " + e.getMessage());
                }
            }

            // ==================== HEV MILEAGE ====================
            // Lifetime distance with the engine contributing — the other half of
            // the EV/HEV split. Feature ID only (no named getter is exposed for
            // this register). PHEV-only in practice: a BEV leaves it UNAVAILABLE,
            // which suppresses it everywhere downstream rather than showing 0.
            //
            // Probe-then-stop: on a BEV this register can never answer, and this
            // block runs on every telemetry poll for the life of the daemon. After
            // a few consecutive misses we stop asking rather than paying an
            // allocation and a HAL round-trip forever for a value that will never
            // arrive. Any single success latches the register as present.
            if (hevMileageProbesLeft > 0 || hevMileagePresent) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_MILEAGE_HEV, Integer.class);
                    boolean got = false;
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.hevMileageKm((int) Math.round(raw * distanceToKmFactor));
                            got = true;
                        }
                    }
                    if (got) {
                        hevMileagePresent = true;
                    } else if (!hevMileagePresent && --hevMileageProbesLeft == 0) {
                        logger.info("HEV mileage register not reported — no longer polling it");
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic hevMileage feature ID error: " + e.getMessage());
                    if (!hevMileagePresent && hevMileageProbesLeft > 0) hevMileageProbesLeft--;
                }
            }

            // ============ ELEC CONSUMPTION RATES (kWh/100km) ============
            // The vehicle's own lifetime and last-trip averages, so a displayed
            // rate matches the cluster instead of being re-derived from a coarse
            // SoC delta. Same shape as the fuel PHM reads below. Rail-guarded:
            // outside 0..200 kWh/100km is a sentinel, not data (a road car cannot
            // sustain 200; 0 means the register is unpopulated).
            Object avgElec = BydDeviceHelper.callGetter(statisticDevice, "getTotalElecConPHMValue");
            if (avgElec instanceof Number) {
                double v = ((Number) avgElec).doubleValue();
                if (v > 0 && v <= 200) b.avgElecConPer100Km(v);
            }
            Object lastElec = BydDeviceHelper.callGetter(statisticDevice, "getLastElecConPHMValue");
            if (lastElec instanceof Number) {
                double v = ((Number) lastElec).doubleValue();
                if (v > 0 && v <= 200) b.lastElecConPer100Km(v);
            }

            // ==================== SOC (ELEC PERCENTAGE) ====================
            // Named getter primary, then feature ID fallback
            Object elecPct = BydDeviceHelper.callGetter(statisticDevice, "getElecPercentageValue");
            if (elecPct instanceof Number) {
                double soc = ((Number) elecPct).doubleValue();
                // Note: On DiLink 5.0 (Sealion 7 etc.), getElecPercentageValue() returns 0.0 when unpopulated.
                // An unpopulated 0.0 must not block cloud / VHAL fallback.
                if (soc > 0 && soc <= 100) {
                    // The on-demand getter returns a COARSE (integer on this trim) SoC,
                    // while the typed onElecPercentageChanged event carries the true
                    // decimal. Don't let an integer poll clobber a fresher decimal that
                    // rounds to the same whole number — otherwise SoC flickers
                    // integer<->decimal every poll cycle. Take the poll only when it
                    // actually moves the rounded value (or nothing has been set yet).
                    double prevSoc = b.socPercent;
                    if (Double.isNaN(prevSoc) || Math.round(prevSoc) != Math.round(soc)) {
                        b.socPercent(soc);
                    }
                    socSucceeded = true;
                }
            }
            if (!socSucceeded) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_ELEC_PERCENTAGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0 && raw <= 100) {
                            b.socPercent((double) raw);
                            socSucceeded = true;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic socPercent feature ID error: " + e.getMessage());
                }
            }

            // ==================== WATER TEMP ====================
            Object waterTemp = BydDeviceHelper.callGetter(statisticDevice, "getWaterTemperature");
            if (waterTemp instanceof Number) b.waterTempC(((Number) waterTemp).intValue());

            // Fallback: try Engine device if Statistic didn't provide coolant temp.
            // Some firmware only exposes coolant temperature via the Engine device.
            if (b.waterTempC == BydVehicleData.UNAVAILABLE && engineDevice != null) {
                try {
                    String[] coolantGetters = {
                        "getWaterTemperature", "getCoolantTemperature",
                        "getEngineCoolantTemperature", "getEngineWaterTemperature",
                        "getEngineCoolantTemp", "getWaterTemp"
                    };
                    for (String getter : coolantGetters) {
                        Object engineCoolant = BydDeviceHelper.callGetter(engineDevice, getter);
                        if (engineCoolant instanceof Number) {
                            int tempC = ((Number) engineCoolant).intValue();
                            if (tempC >= -50 && tempC <= 200) {
                                b.waterTempC(tempC);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ==================== TOTAL ELEC CONSUMPTION ====================
            Object totalElec = BydDeviceHelper.callGetter(statisticDevice, "getTotalElecConValue");
            if (totalElec instanceof Number) b.totalElecCon(((Number) totalElec).doubleValue());

            // ==================== TOTAL FUEL CONSUMPTION ====================
            // Lifetime litres burned. SDK range 0.0-104857.4 L, already litres —
            // no scaling (confirmed against the SDK javadoc and both reference
            // apps). The trip-level delta of this counter is what prices the
            // petrol leg of a PHEV trip.
            Object totalFuel = BydDeviceHelper.callGetter(statisticDevice, "getTotalFuelConValue");
            if (totalFuel instanceof Number) b.totalFuelCon(((Number) totalFuel).doubleValue());

            // ==================== AVG FUEL CONSUMPTION (L/100km) ====================
            // The vehicle's own lifetime average, so our display agrees with the
            // cluster instead of showing a separately-derived figure. SDK range
            // 0.0-51.1, no scaling; anything outside that is a rail, not data.
            // PHEV-gated like the fuel %/range reads below: a BEV whose HAL exposes
            // this getter returns an unpopulated 0.0, and publishing that asserts a
            // real "0.0 L/100km" lifetime average for a car with no engine.
            // 0.0 stays acceptable ON a PHEV (a car that has burned no petrol).
            if (isPhev(b)) {
                Object fuelPhm = BydDeviceHelper.callGetter(statisticDevice, "getTotalFuelConPHMValue");
                if (fuelPhm instanceof Number) {
                    double phm = ((Number) fuelPhm).doubleValue();
                    if (!Double.isNaN(phm) && phm >= 0 && phm <= 51.1) b.avgFuelConPer100Km(phm);
                }
            }

            // ==================== ELECTRIC DRIVING RANGE ====================
            // Named getter primary, feature ID fallback
            // elecRangeKm KEEPS the distanceToKmFactor conversion. This is
            // long-standing behaviour and, unlike fuel range, it is PERSISTED:
            // charging_sessions.start_range_km / range_gained_km and
            // soc_history.range_km are all derived from it. Dropping the factor
            // would leave one column holding miles-scaled values for old rows and
            // raw values for new ones on a miles-configured install — a silent
            // unit split in stored history, which is worse than the inconsistency
            // it would fix. Fuel range is new, unpersisted, and documented by the
            // SDK as km, so it is left unscaled; see the fuel block below.
            Object elecRange = BydDeviceHelper.callGetter(statisticDevice, "getElecDrivingRangeValue");
            if (elecRange instanceof Number) {
                int raw = ((Number) elecRange).intValue();
                if (raw > 0) {
                    b.elecRangeKm((int) Math.round(raw * distanceToKmFactor));
                    elecRangeSucceeded = true;
                }
            }
            if (!elecRangeSucceeded) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_ELEC_DRIVING_RANGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.elecRangeKm((int) Math.round(raw * distanceToKmFactor));
                            elecRangeSucceeded = true;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic elecRange feature ID error: " + e.getMessage());
                }
            }

            // ==================== FUEL PERCENTAGE & FUEL RANGE (PHEV only) ====================
            // BEVs return bogus CAN bus values for fuel (e.g. constant 62% on a Seal).
            boolean isPhev = isPhev(b);

            // ==================== FUEL DRIVING RANGE (PHEV only) ====================
            // The BYD SDK documents getFuelDrivingRangeValue() as 0..4095 with 4095
            // as the classic "invalid" rail, and the reference apps (reference app C /
            // reference app B) apply no scaling to it. The real bug fixed here is the
            // MISSING SENTINEL FILTER on the named-getter path: a raw 4095 passed a
            // bare `raw > 0` and was published as "4095 km of petrol range".
            //
            // The distanceToKmFactor conversion is KEPT, for consistency with the
            // sibling elecRangeKm read above: DrivingRangeData.totalRangeKm is
            // elecRangeKm + fuelRangeKm, so scaling only one of them would sum two
            // different units on a miles-configured install. elecRangeKm cannot
            // drop the factor (it is persisted into charging_sessions.start_range_km
            // and soc_history.range_km, so changing it would split that column's
            // units between old and new rows), which makes matching it the correct
            // direction here.
            if (isPhev) {
                // Named getter primary, feature ID fallback
                Object fuelRange = BydDeviceHelper.callGetter(statisticDevice, "getFuelDrivingRangeValue");
                if (fuelRange instanceof Number) {
                    int raw = ((Number) fuelRange).intValue();
                    // Plausibility is checked on the RAW value (the SDK's own
                    // 0..4095 domain), then converted like elecRangeKm.
                    if (isPlausibleFuelRangeKm(raw)) {
                        b.fuelRangeKm((int) Math.round(raw * distanceToKmFactor));
                        fuelSucceeded = true;
                    }
                }
                if (!fuelSucceeded) {
                    try {
                        Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_FUEL_DRIVING_RANGE, Integer.class);
                        if (val != null) {
                            int raw = BydDeviceHelper.getIntValue(val);
                            if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                                && raw != BydFeatureIds.INVALID_VALUE_2 && isPlausibleFuelRangeKm(raw)) {
                                b.fuelRangeKm((int) Math.round(raw * distanceToKmFactor));
                                fuelSucceeded = true;
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("collectStatistic fuelRange feature ID error: " + e.getMessage());
                    }
                }

                // ==================== FUEL PERCENTAGE (PHEV only) ====================
                // SDK range is 0..100 inclusive — 0 is a LEGITIMATE reading (empty
                // tank, or a 1%-quantised gauge that has bottomed out), so the data
                // path accepts >= 0. Rejecting 0 here previously blanked the fuel
                // gauge and silently dropped the fuelPct×tank cost fallback exactly
                // when a driver most needs to see it. (computeIsPhev separately still
                // requires > 0 to call a reading "real" — that guard is about not
                // misclassifying a BEV, and is documented there.) Sentinels are
                // filtered on BOTH paths now.
                boolean fuelPctSucceeded = false;
                Object fuelPct = BydDeviceHelper.callGetter(statisticDevice, "getFuelPercentageValue");
                if (fuelPct instanceof Number) {
                    int pct = ((Number) fuelPct).intValue();
                    if (pct >= 0 && pct <= 100 && !isBevFuelSentinel(pct)) {
                        b.fuelPercent(pct);
                        fuelPctSucceeded = true;
                    }
                }
                // Feature ID fallback
                if (!fuelPctSucceeded) {
                    try {
                        Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_FUEL_PERCENTAGE, Integer.class);
                        if (val != null) {
                            int raw = BydDeviceHelper.getIntValue(val);
                            if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                                && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0 && raw <= 100
                                && !isBevFuelSentinel(raw)) {
                                b.fuelPercent(raw);
                                fuelPctSucceeded = true;
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Fuel percentage feature ID failed: " + e.getMessage());
                    }
                }
                fuelSucceeded = fuelSucceeded || fuelPctSucceeded;
            }

            // Battery temps via get() — intValue - 40 = °C
            collectStatTemp(b, BydFeatureIds.STAT_HIGHEST_BATTERY_TEMP, "high");
            collectStatTemp(b, BydFeatureIds.STAT_LOWEST_BATTERY_TEMP, "low");
            collectStatTemp(b, BydFeatureIds.STAT_AVERAGE_BATTERY_TEMP, "avg");

            // Cell voltages via get() — intValue / 1000.0 = V
            double cellHi = collectStatVoltage(b, BydFeatureIds.STAT_HIGHEST_BATTERY_VOLTAGE, "high");
            double cellLo = collectStatVoltage(b, BydFeatureIds.STAT_LOWEST_BATTERY_VOLTAGE, "low");

            // HV pack voltage, derived from the (accurate) per-cell voltage × series cell count.
            // The statistic-device event 1151336480 (formerly read as pack voltage in decivolts)
            // under-reports on some trims — e.g. on the Seal 82.5 kWh it tracks only ~149 cells'
            // worth (~494 V) while the true pack is ~570 V (verified vs an OBD2 reading of 567 V at
            // 3.294 V/cell). The per-cell voltages read correctly, so pack = avg_cell × N, where N
            // is the pack's series cell count from its nominal capacity (cellCountForCapacity, e.g.
            // 82.5 kWh → 172s) — so this stays correct across BYD models. If capacity isn't known
            // yet (cellCount == 0) we skip the override rather than publish a wrong value.
            if (!Double.isNaN(cellHi) && !Double.isNaN(cellLo)) {
                int cellCount = 0;
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    if (soh != null) {
                        cellCount = com.overdrive.app.abrp.SohEstimator
                            .cellCountForCapacity(soh.getNominalCapacityKwh());
                    }
                } catch (Throwable ignored) {}
                if (cellCount > 0) {
                    b.hvPackVoltage(((cellHi + cellLo) / 2.0) * cellCount);
                }
            }
        } catch (Exception e) {
            logger.debug("collectStatistic error: " + e.getMessage());
        }
        return new StatisticHalResult(socSucceeded, elecRangeSucceeded, fuelSucceeded);
    }

    private void collectStatTemp(BydVehicleData.Builder b, int featureId, String which) {
        // Probe the primitive widths, INT FIRST. This used to be "try Integer.TYPE, then
        // Integer.class"; callGet now normalizes a wrapper to its primitive (the HAL matches
        // primitives only), which made the second call an identical retry of the first.
        //
        // The intFirst flag is load-bearing, not a preference: BYDAutoEventValue carries
        // independent intValue/doubleValue fields, the HAL fills only the requested one, and
        // getIntValue below always reads intValue. A Double-first ladder that won here would
        // leave intValue at its default 0 — and 0 is IN BAND for this scale (raw 0 → -40 °C),
        // so it would sail through the range check and publish -40 °C for a perfectly normal
        // pack, which also silently blocks SOH calibration (it needs 15-35 °C).
        Object val = BydDeviceHelper.callGetProbing(statisticDevice, featureId, true);
        if (val == null) return;
        int raw = BydDeviceHelper.getIntValue(val);
        if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE
            || raw == BydFeatureIds.INVALID_VALUE_2 || raw == Integer.MIN_VALUE) return;
        if (raw < 0 || raw > 120) return;
        // Belt-and-braces against the type/field mismatch described above: if a Double-first
        // probe ever wins here again, intValue reads its default 0, which maps to exactly
        // -40 °C — in band, so the range check would pass it through.
        //
        // Deliberately NOT a blanket `raw == 0 → drop`: -40 °C is the low end of a real
        // reporting range (the MQTT gate below accepts >= -40), so an extreme-cold pack could
        // legitimately read 0 and dropping it would be silent data loss. Instead, only treat 0
        // as suspect when the CORROBORATING evidence says the value never came from intValue:
        // a populated doubleValue alongside a zero intValue is the mismatch signature, whereas
        // a genuine -40 °C reading arrives with doubleValue at its own default.
        if (raw == 0) {
            double asDouble = BydDeviceHelper.getDoubleValue(val);
            if (!Double.isNaN(asDouble) && asDouble != 0.0) {
                logger.debug("collectStatTemp " + which + ": intValue=0 but doubleValue="
                        + asDouble + " — type/field mismatch, discarding");
                return;
            }
        }
        double tempC = raw - 40;
        switch (which) {
            case "high": b.highCellTempC(tempC); break;
            case "low": b.lowCellTempC(tempC); break;
            case "avg": b.avgCellTempC(tempC); break;
        }
    }

    /**
     * True when an ENGINE_POWER reading is the HAL's "no data" placeholder rather than a real
     * power flow, so it must not reach {@code enginePowerKw}.
     *
     * <p><b>Why {@code -1} specifically.</b> A device capture on a Sealion-class DM-i PHEV
     * (log_X5RRX996, 2026-07-28) reported {@code enginePower resolved=-1.00kW source=featureId
     * raw=-1.0} on ALL 148 samples across 3h41m — spanning a full charge (SOC 97→100%), the
     * charge finishing, and hours of idle. A genuine mechanical power flow cannot hold exactly
     * -1.0 kW to the bit across that; it is an idle/unavailable placeholder. It also sits
     * INSIDE the plausible {@code [-200, 400]} band, so the range check cannot reject it.
     *
     * <p>Why that matters beyond display: {@code -1.0} is more negative than
     * {@code -ENGINE_POWER_DEADBAND} (0.3), so {@link com.overdrive.app.monitor.ChargingDetector}
     * counted it as "current flowing INTO the pack" — permanent phantom charging evidence that
     * could latch L3 on a parked car. Filtering it here fixes the display AND the detector,
     * because both read this one field.
     *
     * <p>Deliberately EXACT equality on a small denylist, not a band: a real engine can
     * legitimately produce -1 kW of regen momentarily, and rejecting a whole band would lose
     * genuine light-regen data. Exact-match on the observed placeholder loses at most one
     * sample per crossing of that value, and the reference OEM app (reference app B) likewise accepts
     * the full signed band with no low-magnitude carve-out. {@code 0.0} is NOT filtered — a
     * genuine idle engine reads exactly 0 and callers already treat 0 as "no flow".
     *
     * @param kw the scaled kW reading (post {@code ×0.1} deciwatt correction)
     */
    private static boolean isEnginePowerSentinel(double kw) {
        return kw == -1.0;
    }

    /**
     * Apply the OEM engine-power register's signed scaling rule. Only positive raw values above 100
     * use the 0.1 multiplier; a negative value is already kW and may legitimately be below -100.
     */
    static double scaleEnginePowerKw(double raw) {
        return raw > 100.0 ? raw * 0.1 : raw;
    }

    /**
     * Identity. The cluster charge readout is stored RAW.
     *
     * <p>This used to divide anything above 22 by 100, inferring the unit from the magnitude.
     * That guess is unsound in both directions: the ambiguous band contains both a genuine
     * DC-charging rate (60-500 kW) and a plausible counter value, so no threshold separates
     * them. It was contained by consuming the field on one drivetrain only, which meant the
     * other drivetrain simply lost access to the vehicle's own dash figure.
     *
     * <p>The unit question is now answered by {@link ChargeSourceClassifier} from how the value
     * MOVES across a charge, which is drivetrain-independent and needs no per-trim table. Kept as
     * a named identity function so the intent — "deliberately unscaled" — stays explicit at the
     * call site and in the pinned test.
     */
    static double scaleClusterChargePowerKw(double raw) {
        return raw;
    }

    /** @return the cell voltage in V, or NaN if unavailable/out-of-range. */
    private double collectStatVoltage(BydVehicleData.Builder b, int featureId, String which) {
        // Same as collectStatTemp, including the INT-FIRST ladder — getIntValue below reads
        // BYDAutoEventValue.intValue, so the probe must request the width it will extract.
        // (This method already rejects raw <= 0, so it degraded safely even before that fix.)
        Object val = BydDeviceHelper.callGetProbing(statisticDevice, featureId, true);
        if (val == null) return Double.NaN;
        int raw = BydDeviceHelper.getIntValue(val);
        if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE
            || raw == BydFeatureIds.INVALID_VALUE_2 || raw == Integer.MIN_VALUE || raw <= 0) return Double.NaN;
        double volts = raw / 1000.0;
        if (volts < 1.0 || volts > 5.0) return Double.NaN;
        switch (which) {
            case "high": b.highCellVoltage(volts); break;
            case "low": b.lowCellVoltage(volts); break;
        }
        return volts;
    }

    /** True when the gun reports DISCONNECTED — a counter's value then stops belonging to this session. */
    private static boolean gunOutForSource(BydVehicleData.Builder b) {
        return b.chargingGunState == 1;
    }

    static boolean isPowerExportContext(int gunState, boolean vtolCharging) {
        return vtolCharging || gunState == 5;
    }

    /**
     * Whether a raw charging channel may be offered to the detector.
     *
     * <p>The builder can contain a current synchronous gun-out observation before that same poll is
     * atomically delivered to ChargingDetector. Rejecting here prevents stale source reads later in
     * the poll from briefly reviving L3 against the detector's previous connected-gun snapshot.
     */
    static boolean allowsRawChargingEvidence(int gunState, boolean vtolCharging) {
        return gunState != 1 && !isPowerExportContext(gunState, vtolCharging);
    }

    static boolean isChargingCallbackLifecycleCurrent(
            long bmsVersionAtDispatch, long bmsVersionNow,
            long gunVersionAtDispatch, long gunVersionNow,
            long typeVersionAtDispatch, long typeVersionNow) {
        return bmsVersionAtDispatch == bmsVersionNow
                && gunVersionAtDispatch == gunVersionNow
                && typeVersionAtDispatch == typeVersionNow;
    }

    static boolean allowsEnginePowerCallback(BydVehicleData current, double powerKw,
                                             boolean accOn,
                                             long gunVersionAtDispatch, long gunVersionNow,
                                             long rateClearVersionAtDispatch,
                                             long rateClearVersionNow) {
        return allowsEnginePowerCallback(
                current, powerKw, accOn,
                0L, 0L,
                gunVersionAtDispatch, gunVersionNow,
                0L, 0L,
                rateClearVersionAtDispatch, rateClearVersionNow);
    }

    static boolean allowsEnginePowerCallback(
            BydVehicleData current, double powerKw, boolean accOn,
            long bmsVersionAtDispatch, long bmsVersionNow,
            long gunVersionAtDispatch, long gunVersionNow,
            long typeVersionAtDispatch, long typeVersionNow,
            long rateClearVersionAtDispatch, long rateClearVersionNow) {
        return allowsEnginePowerCallback(
                current, powerKw, accOn,
                bmsVersionAtDispatch, bmsVersionNow,
                gunVersionAtDispatch, gunVersionNow,
                typeVersionAtDispatch, typeVersionNow,
                rateClearVersionAtDispatch, rateClearVersionNow,
                0L, false);
    }

    static boolean allowsEnginePowerCallback(
            BydVehicleData current, double powerKw, boolean accOn,
            long bmsVersionAtDispatch, long bmsVersionNow,
            long gunVersionAtDispatch, long gunVersionNow,
            long typeVersionAtDispatch, long typeVersionNow,
            long rateClearVersionAtDispatch, long rateClearVersionNow,
            long observedAtMs, boolean coherentPostFinishedRateProof) {
        if (current == null) return false;
        if (powerKw >= 0.0) return true;
        if (!isChargingCallbackLifecycleCurrent(
                    bmsVersionAtDispatch, bmsVersionNow,
                    gunVersionAtDispatch, gunVersionNow,
                    typeVersionAtDispatch, typeVersionNow)
                || rateClearVersionAtDispatch != rateClearVersionNow) {
            return false;
        }
        return allowsEnginePowerObservation(
                current.chargingState, current.chargingGunState, current.vtolCharging,
                powerKw, accOn, isTerminalChargingBarrierActive(),
                current.chargingStateAtMs, observedAtMs,
                coherentPostFinishedRateProof);
    }

    /**
     * A negative engine-power level is charging evidence only while the matching charging lifecycle
     * is still live. FINISHED with the gun left connected is especially important: affected PHEVs
     * keep returning the final -3 kW register level, and writing it through the builder would give it
     * a new timestamp on every poll even though no new power observation occurred.
     */
    static boolean allowsEnginePowerObservation(
            int chargingState, int chargingGunState, boolean vtolCharging,
            double powerKw, boolean accOn, boolean terminalBarrierActive) {
        return allowsEnginePowerObservation(
                chargingState, chargingGunState, vtolCharging,
                powerKw, accOn, terminalBarrierActive,
                0L, 0L, false);
    }

    static boolean allowsEnginePowerObservation(
            int chargingState, int chargingGunState, boolean vtolCharging,
            double powerKw, boolean accOn, boolean terminalBarrierActive,
            long chargingStateAtMs, long observedAtMs,
            boolean coherentPostFinishedRateProof) {
        if (powerKw >= 0.0) return true;
        boolean chargingConnection = chargingGunState == 2
                || chargingGunState == 3 || chargingGunState == 4;
        boolean chargingContext = chargingConnection || !accOn;
        if (chargingContext
                && (terminalBarrierActive || isTerminalChargingState(chargingState))) {
            // FINISHED can precede the physical end of a connected PHEV taper. The terminal edge
            // clears every pre-FINISHED rate first; only an engine observation that began strictly
            // afterward and has a separate fresh post-FINISHED rate observation may re-enter.
            return chargingState == 2
                    && chargingConnection
                    && !vtolCharging
                    && powerKw < -ENGINE_POWER_CHARGING_DEADBAND
                    && chargingStateAtMs > 0L
                    && observedAtMs > chargingStateAtMs
                    && coherentPostFinishedRateProof;
        }
        // Negative power with ACC on and no charging connection is ordinary regenerative driving.
        // With ACC off it must still have a non-disconnected, non-export charging context.
        return accOn || allowsRawChargingEvidence(chargingGunState, vtolCharging);
    }

    static boolean isCoherentPostFinishedRateProof(
            double rate, long rateObservedAtMs,
            long finishedAtMs, long engineObservedAtMs, long nowMs) {
        return Double.isFinite(rate) && rate > 0.1
                && finishedAtMs > 0L
                && rateObservedAtMs > finishedAtMs
                && engineObservedAtMs > finishedAtMs
                && nowMs >= rateObservedAtMs
                && nowMs >= engineObservedAtMs
                && nowMs - rateObservedAtMs <= POST_FINISHED_RATE_PROOF_FRESHNESS_MS
                && nowMs - engineObservedAtMs <= POST_FINISHED_RATE_PROOF_FRESHNESS_MS
                && Math.abs(engineObservedAtMs - rateObservedAtMs)
                        <= POST_FINISHED_RATE_PROOF_FRESHNESS_MS;
    }

    private static boolean hasCoherentPostFinishedRateProof(
            BydVehicleData current, long engineObservedAtMs) {
        if (current == null || current.chargingState != 2
                || current.chargingStateAtMs <= 0L) {
            return false;
        }
        long nowMs = System.currentTimeMillis();
        return ChargeSourceClassifier.isRate(ChargeSourceClassifier.SRC_CLUSTER)
                        && isCoherentPostFinishedRateProof(
                                current.clusterChargePowerKw,
                                current.clusterChargePowerAtMs,
                                current.chargingStateAtMs, engineObservedAtMs, nowMs)
                || ChargeSourceClassifier.isRate(ChargeSourceClassifier.SRC_DEVICE)
                        && isCoherentPostFinishedRateProof(
                                current.chargingPowerKw,
                                current.chargingPowerAtMs,
                                current.chargingStateAtMs, engineObservedAtMs, nowMs)
                || ChargeSourceClassifier.isRate(ChargeSourceClassifier.SRC_EXTERNAL)
                        && isCoherentPostFinishedRateProof(
                                current.externalChargingPowerKw,
                                current.externalChargingPowerAtMs,
                                current.chargingStateAtMs, engineObservedAtMs, nowMs)
                || isCoherentPostFinishedRateProof(
                        current.chargePowerKw,
                        current.chargePowerAtMs,
                        current.chargingStateAtMs, engineObservedAtMs, nowMs);
    }

    /** Explicit BMS states during which source behavior must not train the rate/counter classifier. */
    static boolean shouldClassifyChargingSource(int chargingState) {
        return chargingState != 0
                && chargingState != 2
                && chargingState != 3
                && chargingState != 4
                && chargingState != 5
                && chargingState != 6
                && chargingState != 7
                && chargingState != 8
                && chargingState != 10
                && chargingState != 11
                && chargingState != 12;
    }

    static boolean shouldClassifyChargingSource(int chargingState,
                                                boolean terminalBarrierActive) {
        return !terminalBarrierActive && shouldClassifyChargingSource(chargingState);
    }

    /** Shared raw-domain validation for every ambiguous charging rate/counter accessor. */
    static boolean isRawChargingSourceValueAdmissible(double raw) {
        return Double.isFinite(raw)
                && raw >= 0
                && raw <= RAW_RATE_ENVELOPE_MAX
                && !isChargePowerSentinel(raw);
    }

    /** Drivetrain-aware admission applied before detector/classifier/resolver state can be mutated. */
    static boolean isRawChargingSourceValueAdmissible(double raw, boolean phev) {
        return isRawChargingSourceValueAdmissible(raw)
                && !com.overdrive.app.monitor.ChargeRateResolver
                        .isKnownPhevRawPowerJunk(raw, phev);
    }

    /**
     * Tri-state drivetrain admission for captured PHEV junk signatures. UNKNOWN is intentionally
     * treated like PHEV, not BEV: during startup a PHEV fuel HAL can return BEV sentinels for the
     * entire 60-second cache window, and a persisted RATE/divisor would otherwise turn 359.4 into a
     * plausible measured 3.594 kW before the drivetrain is recognized.
     */
    static boolean isRawChargingSourceValueAdmissible(double raw, int establishedDrivetrain) {
        boolean signatureMustRemainUntrusted =
                establishedDrivetrain != DRIVETRAIN_BEV;
        return isRawChargingSourceValueAdmissible(raw)
                && !com.overdrive.app.monitor.ChargeRateResolver
                        .isKnownPhevRawPowerJunk(raw, signatureMustRemainUntrusted);
    }

    boolean isRawChargingSourceValueAdmissibleForCurrentDrivetrain(double raw) {
        // Refresh/probe first, then consult only the independently established verdict. The ordinary
        // cached boolean can be a provisional startup BEV result and is not sufficient for this gate.
        computeIsPhev();
        return isRawChargingSourceValueAdmissible(raw, establishedDrivetrain);
    }

    /**
     * Source-aware admission. The dedicated charging-device channel has a fixed framework contract
     * and must not inherit the instrument field's captured PHEV junk signatures.
     */
    boolean isRawChargingSourceValueAdmissibleForCurrentDrivetrain(String source, double raw) {
        if (ChargeSourceClassifier.SRC_DEVICE.equals(source)) {
            return Double.isFinite(raw)
                    && raw >= 0.0
                    && raw <= 500.0
                    && !isChargePowerSentinel(raw);
        }
        return isRawChargingSourceValueAdmissibleForCurrentDrivetrain(raw);
    }

    static boolean isValidChargingBmsState(int raw) {
        return raw >= 0 && raw <= 15
                && raw != BydFeatureIds.BMS_UNAVAILABLE
                && raw != BydFeatureIds.INVALID_VALUE
                && raw != BydFeatureIds.INVALID_VALUE_2;
    }

    /**
     * Callback-only BMS levels cannot create a new lifecycle epoch after an authoritative stop.
     * A versioned synchronous poll owns the evidence needed to release that detector barrier.
     */
    static boolean shouldPublishBmsCallbackTransition(
            int previousState, int nextState, boolean terminalBarrierActive) {
        return previousState == nextState || !terminalBarrierActive;
    }

    static boolean isValidChargingGunState(int raw) {
        return raw >= 1 && raw <= 5;
    }

    /** Decode only the documented boolean encodings; non-zero HAL sentinels are unavailable. */
    static Boolean decodePowerIsCharging(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (!(value instanceof Number)) return null;
        double numeric = ((Number) value).doubleValue();
        if (!Double.isFinite(numeric)) return null;
        if (numeric == 0.0) return Boolean.FALSE;
        if (numeric == 1.0) return Boolean.TRUE;
        return null;
    }

    static boolean isChargingPowerCallbackPayload(double power) {
        return Double.isFinite(power)
                && Math.abs(power) <= 500.0
                && !isChargePowerSentinel(power);
    }

    static boolean shouldRetainFreshDevicePowerCallback(
            BydVehicleData current, boolean callbackOwned, long callbackAtMs, long nowMs,
            int chargingState, int gunState, boolean vtolCharging,
            boolean sessionLive, boolean terminalBarrierActive) {
        if (!callbackOwned || current == null
                || !isChargingPowerCallbackPayload(current.chargingPowerKw)
                || current.chargingPowerKw <= 0.1
                || callbackAtMs <= 0L
                || current.chargingPowerAtMs != callbackAtMs
                || nowMs < callbackAtMs
                || nowMs - callbackAtMs > DEVICE_POWER_CALLBACK_MAX_AGE_MS
                || !allowsRawChargingEvidence(gunState, vtolCharging)) {
            return false;
        }
        boolean gunCharging = gunState == 2 || gunState == 3 || gunState == 4;
        if (isTerminalChargingState(chargingState)) {
            return chargingState == 2 && gunCharging;
        }
        return shouldClassifyChargingSource(chargingState, terminalBarrierActive)
                && (gunCharging || sessionLive);
    }

    private void clearDevicePowerCallbackOriginLocked() {
        latestDevicePowerCameFromCallback = false;
        lastPositiveDevicePowerCallbackAtMs = 0L;
    }

    private boolean retainFreshDevicePowerCallback(BydVehicleData.Builder b) {
        BydVehicleData current = snapshot.get();
        boolean sessionLive = false;
        try {
            sessionLive = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
        } catch (Throwable ignored) {}
        if (!shouldRetainFreshDevicePowerCallback(
                current, latestDevicePowerCameFromCallback,
                lastPositiveDevicePowerCallbackAtMs, System.currentTimeMillis(),
                b.chargingState, b.chargingGunState, b.vtolCharging,
                sessionLive, isTerminalChargingBarrierActive())) {
            clearDevicePowerCallbackOriginLocked();
            return false;
        }
        b.chargingPowerKw(current.chargingPowerKw)
                .chargingPowerAtMs(current.chargingPowerAtMs)
                .chargingPowerChangedAtMs(current.chargingPowerChangedAtMs)
                .chargingPowerLastObservedKw(current.chargingPowerLastObservedKw);
        return true;
    }

    static boolean isExplicitExternalRateStop(
            double power, ChargeSourceClassifier.Kind sourceKind) {
        return sourceKind == ChargeSourceClassifier.Kind.RATE
                && isChargingPowerCallbackPayload(power)
                && power <= 0.1;
    }

    static boolean canPreserveFinishedConnectedRate(String source) {
        return SRC_PACK_SIDE_DIRECT.equals(source) || ChargeSourceClassifier.isRate(source);
    }

    private static boolean isTerminalChargingState(int chargingState) {
        return !shouldClassifyChargingSource(chargingState);
    }

    static boolean allowsTerminalCounterTail(int chargingState) {
        return chargingState == 0 || chargingState == 2 || chargingState == 4;
    }

    static boolean shouldObserveRawChargingSignal(int chargingState,
                                                  boolean finishedConnectedRate) {
        return !isTerminalChargingState(chargingState) || finishedConnectedRate;
    }

    static boolean shouldObserveClusterRawChargingSignal(
            int chargingState, int gunState, ChargeSourceClassifier.Kind sourceKind) {
        boolean gunCharging = gunState == 2 || gunState == 3 || gunState == 4;
        boolean finishedConnectedRate = chargingState == 2
                && gunCharging
                && sourceKind == ChargeSourceClassifier.Kind.RATE;
        return shouldObserveRawChargingSignal(chargingState, finishedConnectedRate);
    }

    private static boolean isFinalEnergyCounterSource(String source) {
        return ChargeSourceClassifier.SRC_CAPACITY.equals(source)
                || (ChargeSourceClassifier.SRC_EXTERNAL.equals(source)
                    && ChargeSourceClassifier.isCounter(source));
    }

    /**
     * Narrow post-unplug admission for the manager's bounded final-counter drain.
     *
     * <p>This is accounting-only. Callers must return immediately after forwarding so the value
     * cannot reach detector evidence, source classification, or the generic rate slope.
     */
    static boolean allowsFinalCounterDuringLifecycleHold(String source, double raw,
                                                         int gunState, boolean vtolCharging,
                                                         boolean lifecycleHoldActive) {
        ChargeSourceClassifier.Kind sourceKind =
                ChargeSourceClassifier.SRC_CAPACITY.equals(source)
                        ? ChargeSourceClassifier.Kind.COUNTER
                        : ChargeSourceClassifier.kindOf(source);
        return allowsFinalCounterDuringLifecycleHold(
                source, sourceKind, raw, gunState, vtolCharging, lifecycleHoldActive);
    }

    /** Pure overload for pinning source-kind boundaries without persisted classifier state. */
    static boolean allowsFinalCounterDuringLifecycleHold(
            String source, ChargeSourceClassifier.Kind sourceKind, double raw,
            int gunState, boolean vtolCharging, boolean lifecycleHoldActive) {
        boolean finalCounter = ChargeSourceClassifier.SRC_CAPACITY.equals(source)
                || (ChargeSourceClassifier.SRC_EXTERNAL.equals(source)
                    && sourceKind == ChargeSourceClassifier.Kind.COUNTER);
        if (!lifecycleHoldActive || gunState != 1 || vtolCharging
                || !finalCounter) {
            return false;
        }
        if (!Double.isFinite(raw) || raw < 0 || isChargePowerSentinel(raw)) return false;
        if (ChargeSourceClassifier.SRC_CAPACITY.equals(source)) {
            return raw <= CHARGING_CAPACITY_MAX_KWH;
        }
        // The external counter has a wider captured register, but values above 500 are outside the
        // DB/resolver counter domain and must not enter an open row merely because a hold exists.
        return raw <= 500.0;
    }

    private static boolean isChargingLifecycleHoldActive() {
        try {
            return com.overdrive.app.monitor.SocHistoryDatabase.getInstance()
                    .isChargingLifecycleHoldActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTerminalChargingBarrierActive() {
        try {
            return com.overdrive.app.monitor.ChargingDetector.getInstance()
                    .isTerminalSessionBarrierActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Feed an accounting-only final counter directly to the open DB row. */
    private static void forwardFinalEnergyCounterToDatabase(String source, double raw) {
        try {
            com.overdrive.app.monitor.SocHistoryDatabase.getInstance()
                    .onChargeCounterObserved(source, raw);
        } catch (Throwable ignored) {}
    }

    private static boolean forwardFinalCounterAfterGunOut(String source, double raw,
                                                          int gunState, boolean vtolCharging) {
        if (!allowsFinalCounterDuringLifecycleHold(
                source, raw, gunState, vtolCharging, isChargingLifecycleHoldActive())) {
            return false;
        }
        forwardFinalEnergyCounterToDatabase(source, raw);
        return true;
    }

    /** Feed a known kWh counter to the scale yardstick and the open session, without classification. */
    private static void forwardEnergyCounterObservation(String source, double raw) {
        try {
            com.overdrive.app.monitor.ChargeRateResolver.observeCounterForScale(source, raw);
        } catch (Throwable ignored) {}
        forwardFinalEnergyCounterToDatabase(source, raw);
    }

    enum CounterObservationRoute {
        NONE,
        DATABASE_ONLY,
        SCALE_AND_DATABASE
    }

    static CounterObservationRoute counterObservationRoute(
            String source, ChargeSourceClassifier.Kind sourceKind) {
        if (ChargeSourceClassifier.SRC_CAPACITY.equals(source)) {
            return CounterObservationRoute.SCALE_AND_DATABASE;
        }
        if (!ChargeSourceClassifier.SRC_EXTERNAL.equals(source)) {
            return CounterObservationRoute.NONE;
        }
        // UNKNOWN is provisional only, and RATE must reach the DB once so it can discard any held
        // provisional baseline. Neither may enter the counter scale/energy path.
        return sourceKind == ChargeSourceClassifier.Kind.COUNTER
                ? CounterObservationRoute.SCALE_AND_DATABASE
                : CounterObservationRoute.DATABASE_ONLY;
    }

    private static void forwardCounterObservation(
            String source, ChargeSourceClassifier.Kind sourceKind, double raw) {
        CounterObservationRoute route = counterObservationRoute(source, sourceKind);
        if (route == CounterObservationRoute.SCALE_AND_DATABASE) {
            forwardEnergyCounterObservation(source, raw);
        } else if (route == CounterObservationRoute.DATABASE_ONLY) {
            forwardFinalEnergyCounterToDatabase(source, raw);
        }
    }

    /** Sink for a validated charging-source value. */
    interface ChargeSourceSink { void accept(double value); }

    /**
     * Single entry point for storing any charging power/energy source.
     *
     * <p>Every such source now passes through here so that the admission gate and the unit
     * interpretation are defined exactly once. Previously the poll path and the listener path
     * for the SAME accessor applied different rules — one divided by 100, the other did not —
     * so the value the rest of the daemon saw depended on which fired last.
     *
     * <p>The gate is the load-bearing part. A charging value is only meaningful while a cable is
     * actually delivering, so we require either a charging-direction gun assertion or a live
     * fused verdict, and refuse a terminal BMS state. Without this a counter that keeps its
     * value with the gun out is admitted as a live reading and never ages out — the exact
     * mechanism behind phantom charging power and phantom sessions.
     *
     * @param raw the RAW getter value, with no scaling applied
     */
    private void storeChargingSource(BydVehicleData.Builder b, String source, double raw,
                                     ChargeSourceSink sink) {
        if (!isRawChargingSourceValueAdmissibleForCurrentDrivetrain(source, raw)) return;
        // BREAK THE CIRCULARITY. The gate below consults ChargingDetector, and the detector's own
        // L3 inference reads the very fields this gate controls — so on the case L3 exists to catch
        // (PHEV parked, BMS stuck at IDLE, gunState UNAVAILABLE, no plug broadcast) nothing would
        // ever be admitted, L3 could never fire, and a real charge would record nothing at all.
        //
        // The detector is given the raw reading through a channel the gate does NOT control. It is
        // evidence for the "is a charge happening" question only; it never becomes a published rate
        // or persisted energy, so a stale or wrong-unit value cannot reach a number the user sees.
        // SENTINELS AND OUT-OF-ENVELOPE VALUES ARE FILTERED FIRST — including before the detector's
        // movement channel below. A getter that flaps between a failure code and a real reading
        // "moves" on every poll, and feeding that to the detector would manufacture charging evidence
        // out of a broken accessor. Only plausible readings may count as movement.
        // 104857.5 and the large negatives are the documented BYD failure codes; 65535 is
        // BMS-unavailable. The bound admits a HECTOWATT reading's full range, because the unit is not
        // known here: a raw 700 is either an impossible 700 kW or an ordinary 7.0 kW three-phase AC
        // charge, and bounding at 500 silently discarded the latter before it could be calibrated
        // against the counter slope. Downstream still bounds the RESOLVED rate at 500 kW.
        if (!allowsRawChargingEvidence(b.chargingGunState, b.vtolCharging)) {
            if (forwardFinalCounterAfterGunOut(
                    source, raw, b.chargingGunState, b.vtolCharging)) {
                // Keep the final value available to the close path, but do not execute any normal
                // source pipeline below this branch.
                sink.accept(raw);
            }
            return;
        }

        boolean gunCharging = b.chargingGunState == 2
                || b.chargingGunState == 3 || b.chargingGunState == 4;
        boolean dischargingState = b.chargingState == 3 || b.chargingState == 11;
        boolean terminalState = isTerminalChargingState(b.chargingState);
        boolean finishedConnected = b.chargingState == 2 && gunCharging;
        boolean finishedConnectedRate =
                finishedConnected && canPreserveFinishedConnectedRate(source);
        if (shouldObserveRawChargingSignal(b.chargingState, finishedConnectedRate)) {
            try {
                com.overdrive.app.monitor.ChargingDetector.getInstance()
                        .observeRawChargingSignal(source, raw);
            } catch (Throwable ignored) { /* detector not up yet */ }
        }
        boolean terminalBarrierActive = isTerminalChargingBarrierActive();
        boolean sessionLive = false;
        try {
            sessionLive = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
        } catch (Throwable ignored) { /* detector not up yet -> treat as idle */ }

        // ONE EXEMPTION: a confirmed-COUNTER energy total at a TERMINAL state. The gate exists to stop a
        // stale RATE being published, but an energy total read as the session ends is the session's final
        // figure — and the close path runs on a slower thread, so refusing it left the tail between the
        // last accepted observation and the end uncounted. Still refused when the gun is OUT, which is
        // where a counter's value stops belonging to this session.
        // Applies to EITHER energy counter. Restricting it to the external source was arbitrary — the
        // capacity counter is the DOCUMENTED per-session total and uses this same gate, so its final
        // callback was still dropped and its tail lost. The test is "is this an energy total", not "which
        // accessor is it".
        boolean terminalCounterTail = allowsTerminalCounterTail(b.chargingState)
                && !dischargingState && !gunOutForSource(b)
                && isFinalEnergyCounterSource(source);
        if (terminalState) {
            if (terminalCounterTail) {
                // Final kWh is accounting data, not another behavioral sample. Forward it without
                // letting a frozen post-FINISHED value train either classifier or generic slope.
                if (terminalBarrierActive) {
                    forwardFinalEnergyCounterToDatabase(source, raw);
                } else {
                    forwardEnergyCounterObservation(source, raw);
                }
                sink.accept(raw);
            } else if (finishedConnectedRate) {
                // Preserve a previously-classified live rate for the independently corroborated taper
                // path, but do not let post-FINISHED repeats or jitter mutate classification/resolution.
                sink.accept(raw);
            }
            return;
        }
        if (!shouldClassifyChargingSource(b.chargingState, terminalBarrierActive)) {
            if (isFinalEnergyCounterSource(source)) {
                forwardFinalEnergyCounterToDatabase(source, raw);
                sink.accept(raw);
            }
            return;
        }
        if (!(gunCharging || sessionLive)) {
            return;
        }

        // Let the source declare what it is from how it moves. Only observed inside the gate,
        // so a value read with the gun out can never shape the verdict.
        ChargeSourceClassifier.observeWhileCharging(source, raw);
        ChargeSourceClassifier.Kind sourceKind = ChargeSourceClassifier.kindOf(source);
        // The DB buffers UNKNOWN external values provisionally and clears them if this source becomes
        // RATE. Only a confirmed counter is also admitted to scale calibration and session energy.
        forwardCounterObservation(source, sourceKind, raw);
        // Advance this source's slope HERE, at observation time. getChargingState() is a read path
        // hit by HTTP/MQTT/ABRP/the sampler, so letting it advance the slope made the derived kW a
        // function of poll cadence rather than of the telemetry.
        try {
            com.overdrive.app.monitor.ChargeRateResolver.observe(source, raw);
        } catch (Throwable ignored) {}
        sink.accept(raw);
    }

    /**
     * Listener-path twin of {@link #storeChargingSource}. The HAL callbacks write straight into
     * the published snapshot rather than into a Builder, so they need the same gate applied
     * against the CURRENT snapshot's gun/BMS state.
     *
     * <p>This exists because the listener path used to admit values the poll path would have
     * rejected — same accessor, different rules, last writer wins. A callback is not evidence
     * that a cable is delivering: the HAL keeps firing them with the gun out on some firmware.
     *
     * @param raw the RAW callback value, with no scaling applied
     * @return the accepted value, or NaN when the gate refused it
     */
    private double admitChargingCallback(BydVehicleData current, String source, double raw) {
        if (current == null
                || !isRawChargingSourceValueAdmissibleForCurrentDrivetrain(source, raw)) {
            return Double.NaN;
        }
        if (!allowsRawChargingEvidence(current.chargingGunState, current.vtolCharging)) {
            return forwardFinalCounterAfterGunOut(
                    source, raw, current.chargingGunState, current.vtolCharging)
                    ? raw : Double.NaN;
        }

        boolean gunCharging = current.chargingGunState == 2
                || current.chargingGunState == 3 || current.chargingGunState == 4;
        boolean dischargingState = current.chargingState == 3 || current.chargingState == 11;
        boolean terminalState = isTerminalChargingState(current.chargingState);
        boolean finishedConnected = current.chargingState == 2 && gunCharging;
        boolean finishedConnectedRate =
                finishedConnected && canPreserveFinishedConnectedRate(source);
        // Feed L3's movement channel before the live-session gate, exactly as the poll path does.
        // Terminal counters and fault/timeout values are excluded; only a classified connected
        // FINISHED rate may supply the post-finish movement required by the taper proof.
        if (shouldObserveRawChargingSignal(current.chargingState, finishedConnectedRate)) {
            try {
                com.overdrive.app.monitor.ChargingDetector.getInstance()
                        .observeRawChargingSignal(source, raw);
            } catch (Throwable ignored) { /* detector not up yet */ }
        }
        boolean terminalBarrierActive = isTerminalChargingBarrierActive();
        boolean sessionLive = false;
        try {
            sessionLive = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
        } catch (Throwable ignored) {}

        // Same terminal exemption as the poll path: a confirmed-COUNTER energy total arriving as the
        // session ends is that session's final figure, and the close path runs on a slower thread. Having
        // it only on the poll path meant a trim that reports by CALLBACK still lost its tail. Refused once
        // the gun is OUT, where a counter's value stops belonging to this session.
        boolean terminalCounterTail = allowsTerminalCounterTail(current.chargingState)
                && !dischargingState
                && current.chargingGunState != 1 && isFinalEnergyCounterSource(source);
        if (terminalState) {
            if (terminalCounterTail) {
                if (terminalBarrierActive) {
                    forwardFinalEnergyCounterToDatabase(source, raw);
                } else {
                    forwardEnergyCounterObservation(source, raw);
                }
                return raw;
            }
            return finishedConnectedRate ? raw : Double.NaN;
        }
        if (!shouldClassifyChargingSource(current.chargingState, terminalBarrierActive)) {
            if (isFinalEnergyCounterSource(source)) {
                forwardFinalEnergyCounterToDatabase(source, raw);
                return raw;
            }
            return Double.NaN;
        }
        if (!(gunCharging || sessionLive)) {
            return Double.NaN;
        }

        ChargeSourceClassifier.observeWhileCharging(source, raw);
        ChargeSourceClassifier.Kind sourceKind = ChargeSourceClassifier.kindOf(source);
        forwardCounterObservation(source, sourceKind, raw);
        // Same reason as the poll path: the slope is advanced at observation time only.
        try {
            com.overdrive.app.monitor.ChargeRateResolver.observe(source, raw);
        } catch (Throwable ignored) {}
        return raw;
    }

    /** True for the documented BYD failure/unavailable codes that share the numeric channel. */
    private static boolean isChargePowerSentinel(double v) {
        return v == 104857.5
                || v == 65535.0
                || v == -10011.0
                || v == BydFeatureIds.INVALID_VALUE
                || v == BydFeatureIds.INVALID_VALUE_2
                || v == Integer.MIN_VALUE;
    }

    /**
     * The dedicated SDK method reads charging property 740506 directly. Some older SDK surfaces
     * expose a compatibility alias, which remains a fallback.
     * A numeric zero is an answered reading and must not fall through to another potentially stale
     * alias.
     */
    static ChargingPowerReading readChargingDevicePower(Object device) {
        Object value = BydDeviceHelper.callGetter(device, "getChargingPower");
        if (value instanceof Number) {
            return new ChargingPowerReading(
                    ((Number) value).doubleValue(), "getChargingPower");
        }
        value = BydDeviceHelper.callGetter(device, "getChargePower");
        if (value instanceof Number) {
            return new ChargingPowerReading(
                    ((Number) value).doubleValue(), "getChargePower");
        }
        return new ChargingPowerReading(Double.NaN, null);
    }

    private ChargingCapacityReading readChargingCapacity() {
        if (chargingDevice == null) {
            return new ChargingCapacityReading(Double.NaN, null);
        }
        try {
            Object value = BydDeviceHelper.callGetter(chargingDevice, "getChargingCapacity");
            if (value instanceof Number) {
                return new ChargingCapacityReading(
                        ((Number) value).doubleValue(), "getChargingCapacity");
            }
        } catch (Exception e) {
            logger.debug("getChargingCapacity error: " + e.getMessage());
        }
        try {
            Object value = BydDeviceHelper.callGetProbing(
                    chargingDevice, BydFeatureIds.CHARGING_CHARGE_CAPACITY);
            if (value != null) {
                return new ChargingCapacityReading(
                        BydDeviceHelper.getDoubleValue(value), "featureId(0x27C00018)");
            }
        } catch (Exception e) {
            logger.debug("charging-capacity feature ID error: " + e.getMessage());
        }
        return new ChargingCapacityReading(Double.NaN, null);
    }

    private void logChargingCapacityOutcome(ChargingCapacityReading reading) {
        if (reading.isValid()) {
            if (!loggedChargeCapacitySource) {
                loggedChargeCapacitySource = true;
                logger.info("Charged-energy counter alive via " + reading.source
                        + ": " + reading.kwh + " kWh");
            }
            return;
        }
        if (loggedChargeCapacityAbsent) return;
        loggedChargeCapacityAbsent = true;
        logger.info(reading.source == null
                ? "Charged-energy counter unavailable (getter and feature id both silent)"
                  + " — session energy falls back to integration/SOC"
                : "Charged-energy counter answered OUT OF DOMAIN via " + reading.source
                  + ": " + reading.kwh + " (expected 0.."
                  + CHARGING_CAPACITY_MAX_KWH + " kWh) — rejected, falling back");
    }

    private static boolean isValidFinalCounterValue(String source, double raw) {
        if (!Double.isFinite(raw) || raw < 0.0 || isChargePowerSentinel(raw)) return false;
        if (ChargeSourceClassifier.SRC_CAPACITY.equals(source)) {
            return raw <= CHARGING_CAPACITY_MAX_KWH;
        }
        return ChargeSourceClassifier.SRC_EXTERNAL.equals(source) && raw <= 500.0;
    }

    static double reconcileTerminalCounterObservation(
            String source, double existing, double observed) {
        if (!isValidFinalCounterValue(source, observed)) return existing;
        // Counter order comes from the serialized observation path, not numeric magnitude. A valid
        // lower reading can be the first sample after a register wrap or BMS/session reset.
        return observed;
    }

    /**
     * Read only known energy counters while a terminal edge owns the publication lock. The detector
     * is notified after this returns, so its five-second authoritative-stop drain cannot expire before
     * a getter-only counter is observed. These values bypass behavioral training and raw movement:
     * they are final accounting observations, never evidence that charging is still active.
     */
    private BydVehicleData collectFinalChargeCountersLocked(BydVehicleData current) {
        if (current == null) return null;

        BydVehicleData.Builder finalValues = current.toBuilder();
        boolean updated = false;

        ChargingCapacityReading capacity = readChargingCapacity();
        logChargingCapacityOutcome(capacity);
        if (capacity.isValid()) {
            double finalCapacity = reconcileTerminalCounterObservation(
                    ChargeSourceClassifier.SRC_CAPACITY,
                    current.chargingCapacityKwh, capacity.kwh);
            forwardEnergyCounterObservation(
                    ChargeSourceClassifier.SRC_CAPACITY, finalCapacity);
            finalValues.chargingCapacityKwh(finalCapacity);
            capacityEdgeVersion.incrementAndGet();
            updated = true;
        }

        if (instrumentDevice != null
                && ChargeSourceClassifier.isCounter(ChargeSourceClassifier.SRC_EXTERNAL)) {
            try {
                Object value = BydDeviceHelper.callGetter(
                        instrumentDevice, "getExternalChargingPower");
                if (value instanceof Number) {
                    double raw = ((Number) value).doubleValue();
                    if (isValidFinalCounterValue(
                            ChargeSourceClassifier.SRC_EXTERNAL, raw)) {
                        double finalExternal = reconcileTerminalCounterObservation(
                                ChargeSourceClassifier.SRC_EXTERNAL,
                                current.externalChargingPowerKw, raw);
                        forwardEnergyCounterObservation(
                                ChargeSourceClassifier.SRC_EXTERNAL, finalExternal);
                        finalValues.externalChargingPowerKw(finalExternal)
                                .externalChargingPowerAtMs(System.currentTimeMillis());
                        externalPowerEdgeVersion.incrementAndGet();
                        updated = true;
                    }
                }
            } catch (Exception e) {
                logger.debug("final external charge-counter read error: " + e.getMessage());
            }
        }

        if (!updated) return current;
        BydVehicleData published = finalValues.build();
        snapshot.set(published);
        return published;
    }

    /**
     * Settle counter callbacks that linearized before an accepted terminal observation. This path is
     * accounting-only: it never invokes the normal callback admission path, raw detector evidence,
     * source classification, or the generic rate resolver.
     */
    private BydVehicleData reconcileReservedFinalCountersLocked(
            BydVehicleData current, long terminalObservation,
            long lifecycleGeneration, boolean sessionWasLive) {
        CounterReservationBatch batch = chargingObservationOrder.settleCounterCallbacks(
                terminalObservation, lifecycleGeneration, sessionWasLive);
        if (!sessionWasLive || (!batch.hasCapacity && !batch.hasExternal)) return current;

        BydVehicleData.Builder finalValues = current != null ? current.toBuilder() : null;
        if (batch.hasCapacity) {
            double existing = current != null
                    ? current.chargingCapacityKwh : Double.NaN;
            double finalCapacity = reconcileTerminalCounterObservation(
                    ChargeSourceClassifier.SRC_CAPACITY, existing, batch.capacityKwh);
            forwardEnergyCounterObservation(
                    ChargeSourceClassifier.SRC_CAPACITY, finalCapacity);
            if (finalValues != null) finalValues.chargingCapacityKwh(finalCapacity);
            capacityEdgeVersion.incrementAndGet();
        }
        if (batch.hasExternal) {
            double existing = current != null
                    ? current.externalChargingPowerKw : Double.NaN;
            double finalExternal = reconcileTerminalCounterObservation(
                    ChargeSourceClassifier.SRC_EXTERNAL, existing, batch.externalKwh);
            forwardEnergyCounterObservation(
                    ChargeSourceClassifier.SRC_EXTERNAL, finalExternal);
            if (finalValues != null) {
                finalValues.externalChargingPowerKw(finalExternal)
                        .externalChargingPowerAtMs(batch.externalAtMs);
            }
            externalPowerEdgeVersion.incrementAndGet();
        }

        if (finalValues == null) return current;
        BydVehicleData published = finalValues.build();
        snapshot.set(published);
        return published;
    }

    private ChargingObservationVersions collectChargingOrdered(BydVehicleData.Builder b) {
        synchronized (chargingEdgePublishLock) {
            // The builder may have been created before a callback updated the snapshot. Refresh the
            // edge-owned fields before reading hardware so a failed getter cannot restore that older
            // seed. Callbacks cannot interleave until both hardware observations are complete.
            BydVehicleData latest = snapshot.get();
            refreshChargingLifecycleContext(b, latest);
            ChargingPollEvidence pollEvidence = collectCharging(b);
            boolean terminalRateBarrier = publishTerminalPollFence(b, pollEvidence);
            return new ChargingObservationVersions(
                    bmsEdgeVersion.get(), gunEdgeVersion.get(),
                    pollEvidence.bmsObserved
                            ? pollEvidence.bmsObservation
                            : chargingObservationOrder.latestBms(),
                    pollEvidence.connectionObserved
                            ? pollEvidence.gunObservation
                            : chargingObservationOrder.latestGun(),
                    chargingTypeVersion.get(),
                    capacityEdgeVersion.get(), devicePowerEdgeVersion.get(),
                    chargingRateClearVersion.get(),
                    pollEvidence.connectionObserved, pollEvidence.typeObserved,
                    pollEvidence.powerIsCharging,
                    pollEvidence.bmsObserved, pollEvidence.observedBmsState,
                    terminalRateBarrier);
        }
    }

    /**
     * Publish only the lifecycle portion of a terminal hardware poll before releasing the charging
     * publication lock. Power callbacks capture lifecycle versions before waiting for this lock; a
     * callback already queued is rejected by the version advance, while one dispatched afterward
     * sees the terminal snapshot and fails the live-rate admission gate.
     */
    private boolean publishTerminalPollFence(BydVehicleData.Builder b,
                                             ChargingPollEvidence evidence) {
        boolean authoritativeGun = evidence.connectionObserved
                && (b.chargingGunState == 1 || b.chargingGunState == 5);
        boolean authoritativeBms = evidence.bmsObserved
                && isTerminalChargingState(evidence.observedBmsState);
        // chargingType=3 is an independent V2L assertion. A power callback can be dispatched after
        // this getter advances chargingTypeVersion but before the poll publishes its final snapshot;
        // without a fence it sees matching versions plus the old non-V2L snapshot and is retained
        // as newer live charging power. Publish export context and clear rates before releasing the
        // lock so type-derived V2L dominates that callback exactly like gunState=5.
        boolean authoritativeTypeExport =
                evidence.typeObserved && b.chargingType == 3;
        if (!authoritativeGun && !authoritativeBms && !authoritativeTypeExport) return false;

        if (authoritativeGun) gunEdgeVersion.incrementAndGet();
        if (authoritativeBms) bmsEdgeVersion.incrementAndGet();
        chargingRateClearVersion.incrementAndGet();
        clearDevicePowerCallbackOriginLocked();

        boolean preserveExternalCounter =
                ChargeSourceClassifier.isCounter(ChargeSourceClassifier.SRC_EXTERNAL);
        b.chargingPowerKw(Double.NaN)
                .chargePowerKw(Double.NaN)
                .clusterChargePowerKw(Double.NaN)
                .enginePowerKw(Double.NaN);
        if (!preserveExternalCounter) {
            b.externalChargingPowerKw(Double.NaN);
        }

        try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                     com.overdrive.app.monitor.ChargingDetector.beginPublicationMutation()) {
            BydVehicleData current = snapshot.get();
            BydVehicleData published = current;
            boolean sessionWasLive = current != null
                    && (current.chargingState == 1
                        || com.overdrive.app.monitor.ChargingDetector
                                .getInstance().isCharging());
            if (current != null) {
                BydVehicleData.Builder fenced = current.toBuilder();
                if (evidence.connectionObserved) {
                    fenced.chargingGunState(b.chargingGunState);
                }
                if (evidence.typeObserved) {
                    fenced.chargingType(b.chargingType);
                }
                if (evidence.bmsObserved) {
                    fenced.chargingState(evidence.observedBmsState)
                            .chargingStateAtMs(b.chargingStateAtMs);
                }
                fenced.vtolCharging(b.chargingGunState == 5 || b.chargingType == 3);
                boolean preserveFinishedMovementBaselines = authoritativeBms
                        && evidence.observedBmsState == 2
                        && !authoritativeGun && !authoritativeTypeExport;
                published = clearChargingRateFields(
                        fenced.build(), preserveExternalCounter,
                        preserveFinishedMovementBaselines);
                snapshot.set(published);
            }

            long terminalObservation = 0L;
            if (authoritativeBms) {
                terminalObservation = evidence.bmsObservation;
            }
            if (authoritativeGun && b.chargingGunState == 1) {
                terminalObservation = Math.max(
                        terminalObservation, evidence.gunObservation);
            }
            if (terminalObservation > 0L) {
                published = reconcileReservedFinalCountersLocked(
                        published, terminalObservation,
                        callbackLifecycleGeneration.get(), sessionWasLive);
            }
            if (evidence.terminalCapacityObserved
                    && isValidFinalCounterValue(
                            ChargeSourceClassifier.SRC_CAPACITY,
                            b.chargingCapacityKwh)) {
                // The poll read occurs after every callback reservation settled above. Preserve that
                // chronology even when the newer value is lower because the register wrapped/reset.
                double finalCapacity = reconcileTerminalCounterObservation(
                        ChargeSourceClassifier.SRC_CAPACITY,
                        published != null
                                ? published.chargingCapacityKwh : Double.NaN,
                        b.chargingCapacityKwh);
                forwardEnergyCounterObservation(
                        ChargeSourceClassifier.SRC_CAPACITY, finalCapacity);
                if (published != null) {
                    published = published.toBuilder()
                            .chargingCapacityKwh(finalCapacity)
                            .build();
                    snapshot.set(published);
                }
                capacityEdgeVersion.incrementAndGet();
            }

            // Close the detector half of the terminal handoff before exposing a stable collector
            // generation. The complete poll below will deliver the same observation atomically
            // again; these idempotent calls exist to eliminate the early terminal-snapshot gap.
            com.overdrive.app.monitor.ChargingDetector detector =
                    com.overdrive.app.monitor.ChargingDetector.getInstance();
            if (authoritativeGun) {
                detector.confirmConnectionState(
                        b.chargingGunState, b.chargingGunState == 5);
            } else if (authoritativeTypeExport) {
                detector.confirmV2lState(true);
            } else if (authoritativeBms) {
                detector.confirmBmsState(evidence.observedBmsState);
            }
        }
        return true;
    }

    private void collectInstrumentOrdered(BydVehicleData.Builder b,
                                          ChargingObservationVersions observed) {
        synchronized (chargingEdgePublishLock) {
            BydVehicleData latest = snapshot.get();
            refreshChargingLifecycleContext(
                    b, latest,
                    observed.bms != bmsEdgeVersion.get(),
                    observed.gun != gunEdgeVersion.get(),
                    observed.type != chargingTypeVersion.get());
            if (latest != null) {
                b.externalChargingPowerKw(latest.externalChargingPowerKw)
                        .externalChargingPowerAtMs(latest.externalChargingPowerAtMs)
                        .externalChargingPowerChangedAtMs(
                                latest.externalChargingPowerChangedAtMs)
                        .externalChargingPowerLastObservedKw(
                                latest.externalChargingPowerLastObservedKw)
                        .chargePowerKw(latest.chargePowerKw)
                        .chargePowerAtMs(latest.chargePowerAtMs)
                        .chargePowerChangedAtMs(latest.chargePowerChangedAtMs)
                        .chargePowerLastObservedKw(latest.chargePowerLastObservedKw)
                        .clusterChargePowerKw(latest.clusterChargePowerKw)
                        .clusterChargePowerAtMs(latest.clusterChargePowerAtMs)
                        .clusterChargePowerChangedAtMs(
                                latest.clusterChargePowerChangedAtMs)
                        .clusterChargePowerLastObservedKw(
                                latest.clusterChargePowerLastObservedKw);
            }
            collectInstrument(b);
            observed.externalPower = externalPowerEdgeVersion.get();
            observed.externalPowerObserved = true;
        }
    }

    private void collectEngineOrdered(BydVehicleData.Builder b,
                                      ChargingObservationVersions observed) {
        synchronized (chargingEdgePublishLock) {
            BydVehicleData latest = snapshot.get();
            refreshChargingLifecycleContext(
                    b, latest,
                    observed.bms != bmsEdgeVersion.get(),
                    observed.gun != gunEdgeVersion.get(),
                    observed.type != chargingTypeVersion.get());
            if (latest != null) {
                b.enginePowerKw(latest.enginePowerKw)
                        .enginePowerAtMs(latest.enginePowerAtMs);
            }
            collectEngine(b);
            observed.enginePower = enginePowerEdgeVersion.get();
            observed.enginePowerObserved = true;
        }
    }

    private ChargingPollEvidence collectCharging(BydVehicleData.Builder b) {
        ChargingPollEvidence evidence = new ChargingPollEvidence();
        int observedChargingState = BydVehicleData.UNAVAILABLE;
        boolean chargingStateApplied = false;
        // Power.isCharging belongs to a different HAL device and remains usable even when the
        // ChargingDevice handle disappears during a manager restart.
        if (powerDevice != null) {
            try {
                Object pic = BydDeviceHelper.callGetter(powerDevice, "isCharging");
                evidence.powerIsCharging = decodePowerIsCharging(pic);
            } catch (Exception e) {
                logger.debug("collectCharging Power.isCharging error: " + e.getMessage());
            }
        }

        // These are per-read values. A missing handle must not turn the previous CHARGING/rate
        // snapshot into a fresh observation on every poll.
        b.chargingPowerKw(Double.NaN);
        if (chargingDevice == null) {
            if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
                try {
                    String propDump = com.overdrive.app.monitor.AccMonitor.execShell(
                        "dumpsys car_service 2>/dev/null | grep -E '0x21403407|0x2140461c' | grep 'lastEvent'");
                    if (!propDump.isEmpty()) {
                        long gunObs = chargingObservationOrder.begin();
                        if (propDump.contains("Property:0x21403407")) {
                            if (propDump.contains("Property:0x21403407,status: 0") && (propDump.contains("int32Values: [1]") || propDump.contains("int32Values: [2]"))) {
                                chargingObservationOrder.recordGunPoll(gunObs);
                                b.chargingGunState(2); // Gun Connected
                                evidence.connectionObserved = true;
                                evidence.gunObservation = gunObs;
                            } else if (propDump.contains("int32Values: [0]")) {
                                chargingObservationOrder.recordGunPoll(gunObs);
                                b.chargingGunState(1); // Gun Disconnected
                                evidence.connectionObserved = true;
                                evidence.gunObservation = gunObs;
                            }
                        }
                        if (propDump.contains("Property:0x2140461c")) {
                            if (propDump.contains("int32Values: [4]")) {
                                observedChargingState = com.overdrive.app.monitor.ChargingStateData.CHARGING_BATTERY_STATE_SCHEDULE; // 9 = SCHEDULED
                            } else if (propDump.contains("int32Values: [1]")) {
                                observedChargingState = com.overdrive.app.monitor.ChargingStateData.CHARGING_BATTERY_STATE_CHARGING; // 1 = CHARGING
                                evidence.powerIsCharging = Boolean.TRUE;
                            } else if (propDump.contains("int32Values: [2]")) {
                                observedChargingState = com.overdrive.app.monitor.ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH; // 2 = FINISHED
                            } else if (propDump.contains("int32Values: [0]")) {
                                observedChargingState = com.overdrive.app.monitor.ChargingStateData.CHARGING_BATTERY_STATE_IDLE; // 15 = IDLE
                            }
                        }
                        if (observedChargingState != BydVehicleData.UNAVAILABLE) {
                            b.chargingState(observedChargingState);
                        } else if (b.chargingGunState == 1) {
                            b.chargingState(com.overdrive.app.monitor.ChargingStateData.CHARGING_BATTERY_STATE_IDLE);
                        }
                        b.chargingType(1); // AC charging
                        b.vtolCharging(false);
                        return evidence;
                    }
                } catch (Throwable t) {
                    logger.debug("DiLink5 charging probe error: " + t.getMessage());
                }
            }
            b.chargingState(BydVehicleData.UNAVAILABLE)
                    .chargingGunState(BydVehicleData.UNAVAILABLE)
                    .chargingType(BydVehicleData.UNAVAILABLE)
                    .vtolCharging(false);
            return evidence;
        }
        try {
            // Named getters for init read
            long gunObservation = chargingObservationOrder.begin();
            Object gunState = BydDeviceHelper.callGetter(chargingDevice, "getChargingGunState");
            if (gunState instanceof Number) {
                int observedGun = ((Number) gunState).intValue();
                if (observedGun >= 1 && observedGun <= 5) {
                    chargingObservationOrder.recordGunPoll(gunObservation);
                    b.chargingGunState(observedGun);
                    evidence.connectionObserved = true;
                    evidence.gunObservation = gunObservation;
                }
            }

            Object charger = BydDeviceHelper.callGetter(chargingDevice, "getChargerWorkState");
            if (charger instanceof Number) b.chargerWorkState(((Number) charger).intValue());

            // Resolve V2L before reading any positive charging source. Otherwise this same poll can
            // feed export-period values into the classifier/resolver before the mode flag is known.
            Object type = BydDeviceHelper.callGetter(chargingDevice, "getChargingType");
            if (type instanceof Number) {
                int observedType = ((Number) type).intValue();
                int previousType = b.chargingType;
                b.chargingType(observedType);
                evidence.typeObserved = true;
                if (observedType != previousType) {
                    chargingTypeVersion.incrementAndGet();
                }
            }
            boolean isVtol = b.chargingGunState == 5 || b.chargingType == 3;
            b.vtolCharging(isVtol);

            // BYDAutoPowerDevice.isCharging() — independent ground truth from
            // the power MCU. Used by ChargingDetector as the L2 cross-check
            // that catches the PHEV "BMS stuck at 15 IDLE while charging" bug.
            // Tri-state: null when the device is unavailable or the call fails.
            if (isVtol) evidence.powerIsCharging = Boolean.FALSE;

            // Read into a local before publishing it to the builder. Both the feature-id and the
            // named-getter fallback below can fail,
            // and toBuilder() carries the previous poll's value forward — so a BMS that stops
            // answering left chargingState pinned at whatever it last said. Pinned at CHARGING(1) that
            // is worse than a blank: it is L1, the authoritative fusion layer, so the detector keeps a
            // finished session alive indefinitely on a dead accessor. UNAVAILABLE is the honest input
            // (the detector treats it as ambiguous and falls through to L2/L3), and it is what
            // powerIsCharging above already does by resetting to null each poll.
            // Feature ID for battery device state, fallback to named getter
            try {
                long bmsObservation = chargingObservationOrder.begin();
                Object val = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_BATTERY_DEVICE_STATE, Integer.class);
                if (val != null) {
                    int raw = BydDeviceHelper.getIntValue(val);
                    if (isValidChargingBmsState(raw)) {
                        observedChargingState = raw;
                        evidence.bmsObservation = bmsObservation;
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging batteryState feature ID error: " + e.getMessage());
            }
            if (observedChargingState == BydVehicleData.UNAVAILABLE) {
                long bmsObservation = chargingObservationOrder.begin();
                Object battState = BydDeviceHelper.callGetter(chargingDevice, "getBatteryManagementDeviceState");
                if (battState instanceof Number) {
                    int raw = ((Number) battState).intValue();
                    if (isValidChargingBmsState(raw)) {
                        observedChargingState = raw;
                        evidence.bmsObservation = bmsObservation;
                    }
                }
            }
            // The setter stamps only a real transition. Avoiding an intermediate UNAVAILABLE write is
            // what makes chargingStateAtMs the actual FINISHED boundary instead of "this poll".
            b.chargingState(observedChargingState);
            chargingStateApplied = true;
            if (observedChargingState != BydVehicleData.UNAVAILABLE) {
                chargingObservationOrder.recordBmsPoll(evidence.bmsObservation);
                evidence.bmsObserved = true;
                evidence.observedBmsState = observedChargingState;
            }

            // Charging power from the dedicated SDK getter. Older SDK generations expose a
            // compatibility alias, but the framework-defined method is always preferred.
            // Sentinel filter: SDK reports up to ±500 kW; reject anything beyond.
            // Listener callbacks (onChargingPowerChanged) keep this fresh between polls.
            // Reset BEFORE the read, like its siblings. A null/failed getter left toBuilder() carrying
            // the previous poll's value forward with nothing to age it out — and on a PHEV whose rate
            // arrives by callback that stale figure stayed selected and sampled. The callback re-populates
            // it within the same cycle when the device is genuinely alive, so a working trim loses nothing.
            ChargingPowerReading power = readChargingDevicePower(chargingDevice);
            boolean getterPowerPublished = false;
            if (power.answered()) {
                double kw = power.raw;
                if (!loggedChargingPowerGetterSource) {
                    loggedChargingPowerGetterSource = true;
                    logger.info("Charging-device power getter answered via "
                            + power.getter + ": raw=" + kw);
                }
                // Signed by contract: negative is discharge (V2L), which is not charging.
                // Keep the sign here; the consumer rejects negatives rather than abs()-ing them.
                if (Math.abs(kw) > 0.01) {
                    storeChargingSource(b, ChargeSourceClassifier.SRC_DEVICE, kw,
                            v -> b.chargingPowerKw(v)
                                    .chargingPowerAtMs(System.currentTimeMillis()));
                    getterPowerPublished = Double.isFinite(b.chargingPowerKw);
                    if (getterPowerPublished) {
                        clearDevicePowerCallbackOriginLocked();
                    }
                }
            }
            if (!getterPowerPublished && !retainFreshDevicePowerCallback(b)) {
                if (power.answered() && Math.abs(power.raw) <= 0.01) {
                    // A ZERO READING IS INFORMATION: the device is answering and says no power is
                    // flowing. The only exception is a still-fresh value owned by the typed callback:
                    // several SDK variants return zero here while that callback carries the real kW.
                    // Retaining its original timestamp preserves it only for the normal freshness
                    // window; it cannot become an indefinitely sticky rate.
                    b.chargingPowerKw(Double.NaN);
                    if (allowsRawChargingEvidence(b.chargingGunState, b.vtolCharging)) {
                        try {
                            com.overdrive.app.monitor.ChargingDetector.getInstance()
                                    .observeRawChargingSignal(
                                            ChargeSourceClassifier.SRC_DEVICE, 0.0);
                        } catch (Throwable ignored) {}
                    }
                }
            }

            // Targeted clear: when the BMS reports an EXPLICIT non-charging
            // terminal state (including fault and timeout states),
            // we know the previous charging session is over. Clear sticky listener-
            // delivered power so the inference layer in VehicleDataMonitor can't
            // false-trigger from leftover values. We do NOT clear on IDLE (15)
            // because that's the buggy reading some PHEV firmwares give while
            // actually charging — clearing there would break detection again.
            // We do NOT clear on disconnect-only signals (gunState==1) without a
            // BMS state agreeing, because PHEVs often leave gunState UNAVAILABLE.
            // CLEAR ON ABSENCE OF EVIDENCE, not on a specific state code. The old condition
            // listed only the terminal BMS codes (0/2/4/12), so a trim whose BMS parks at an
            // idle code the list did not name never cleared at all and carried a stale value for
            // the whole daemon uptime. That idle code is NOT drivetrain-specific — it has been
            // observed on both a PHEV and a BEV parked with the gun out — so the old condition
            // leaked phantom power on both.
            //
            // Gun-out (state 1) is unambiguous physical proof that nothing is delivering, and it
            // does not depend on the BMS agreeing. Clear when the gun is out, or on any terminal
            // code, EXCEPT while the fused detector still asserts a live session (which is the
            // firmware case where the BMS lies about an in-progress charge).
            boolean gunOut = b.chargingGunState == 1;
            boolean dischargingBms = b.chargingState == 3 || b.chargingState == 11;
            boolean terminalBms = isTerminalChargingState(b.chargingState);
            boolean detectorLive = false;
            try {
                detectorLive = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
            } catch (Throwable ignored) {}
            if (gunOut || dischargingBms || (terminalBms && !detectorLive)) {
                clearDevicePowerCallbackOriginLocked();
                b.chargingPowerKw(Double.NaN);
                // externalChargingPowerKw is cleared ONLY while it is a RATE. Once the classifier has
                // ruled it a COUNTER it is an ENERGY TOTAL serving the same role as the capacity
                // counter below, and the reasoning there applies identically: the close path runs on a
                // slower thread, so clearing at the gun-out edge discards whatever was delivered
                // between the last tick and the unplug. A stale rate must not survive; a stale energy
                // total is still this session's true figure.
                if (!ChargeSourceClassifier.isCounter(ChargeSourceClassifier.SRC_EXTERNAL)) {
                    b.externalChargingPowerKw(Double.NaN);
                }
                // The charged-energy counter is deliberately NOT cleared here. It is an ENERGY
                // total, not a rate, so a value read after the gun comes out is still the true
                // amount this session delivered — and the session-close path runs on a different,
                // slower thread. Clearing it at the gun-out edge dropped whatever was delivered
                // between the last SoC tick and the unplug (at 150 kW, a 90 s tail is 3.75 kWh of
                // real, paid-for energy).
                //
                // It cannot go stale into the NEXT session: the accumulator re-baselines at SESSION
                // START, and the counter itself resets per session.
                // The pack-side alias remains a guarded fallback, but a carried-forward in-band
                // value could still surface after the dedicated signal disappears. Clear it with
                // its sibling rates at the physical session edge.
                b.chargePowerKw(Double.NaN);
                // The cluster rate is another guarded fallback and toBuilder() carries it between
                // polls. Clear it here so it cannot outlive the session if a later read stalls.
                b.clusterChargePowerKw(Double.NaN);
            }

            // Charging mode — getChargingMode() raw value (AC vs DC vs wireless, model-specific).
            // Stored on the snapshot; logged once on first sight then throttled at 5min.
            Object mode = BydDeviceHelper.callGetter(chargingDevice, "getChargingMode");
            if (mode instanceof Number) {
                int rawMode = ((Number) mode).intValue();
                // Filter sentinels (BMS_UNAVAILABLE=65535, INVALID values)
                if (rawMode >= 0 && rawMode < 100) {
                    b.chargingMode(rawMode);
                    long now = System.currentTimeMillis();
                    if (now - lastChargingModeLogMs > 300_000) {
                        lastChargingModeLogMs = now;
                        logger.info("getChargingMode=" + rawMode);
                    }
                }
            }

            // SDK getChargingState() — distinct from getBatteryManagementDeviceState() above.
            // Diagnostic only for now: log to verify the value space against our existing
            // chargingState (which may come from a different source).
            Object chState = BydDeviceHelper.callGetter(chargingDevice, "getChargingState");
            if (chState instanceof Number) {
                int rawState = ((Number) chState).intValue();
                if (rawState >= 0 && rawState < 100) {
                    long now = System.currentTimeMillis();
                    if (now - lastChargingStateRawLogMs > 300_000) {
                        lastChargingStateRawLogMs = now;
                        logger.debug("getChargingState=" + rawState + " (collector chargingState=" + b.chargingState + ")");
                    }
                }
            }

            // PER-SESSION CHARGED-ENERGY COUNTER (kWh). This is the vehicle's own metered figure
            // for "energy added", so when it is alive it outranks anything we could integrate or
            // derive from the SOC gauge: no unit guess, no pack-capacity divisor, and none of the
            // 1%-SOC quantisation. Session energy is simply end - start.
            //
            // Read via the typed getter first, then the feature id, because the getter is absent
            // on some trims while the id still answers. The id is read DOUBLE-first to match the
            // HAL's float accessor width — requesting the wrong width returns null silently.
            ChargingCapacityReading capacity = readChargingCapacity();
            double capKwh = capacity.kwh;
            // Bound to the SDK's documented counter domain [0, 131.07] kWh. A value outside it is
            // not this counter, whatever else it may be.
            //
            // ZERO IS ADMITTED. It is a legitimate — in fact the ideal — reading: a counter the
            // vehicle has just reset for a new session reads exactly 0, and that is the only value
            // that makes a perfect baseline. Rejecting it meant the baseline could only be taken from
            // the first POSITIVE reading, so on a DC session everything delivered between the reset
            // and that reading was absorbed into the baseline and never counted. The domain is
            // inclusive of 0 by the SDK's own definition, so the filter now matches it.
            if (capacity.isValid()) {
                boolean terminalPollObservation =
                        isTerminalChargingState(b.chargingState)
                        || b.chargingGunState == 1
                        || b.chargingGunState == 5
                        || b.vtolCharging;
                if (terminalPollObservation) {
                    // Publish/forward this only after the terminal fence has settled callbacks whose
                    // observation sequence precedes this synchronous getter.
                    b.chargingCapacityKwh(capKwh);
                    evidence.terminalCapacityObserved = true;
                } else {
                    storeChargingSource(b, ChargeSourceClassifier.SRC_CAPACITY, capKwh,
                            v -> b.chargingCapacityKwh(v));
                }
            }
            // Distinguish "nothing answered" from "answered out-of-domain": the second means the
            // accessor exists but reports in another unit or a sentinel, not simply that it is absent.
            logChargingCapacityOutcome(capacity);

            // Charging percent from chargingDevice
            Object pct = BydDeviceHelper.callGetter(chargingDevice, "getChargingPercent");
            if (pct instanceof Number) {
                int chgPct = ((Number) pct).intValue();
                if (chgPct >= 0 && chgPct <= 100) b.chargingPercent(chgPct);
            }

            // Charger work state via feature ID fallback
            if (b.chargerWorkState == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_CHARGER_WORK_STATE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0) {
                            b.chargerWorkState(raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectCharging chargerWorkState feature ID error: " + e.getMessage());
                }
            }

            // Wireless charging states via feature IDs
            try {
                Object wlLeft = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_LEFT_STATE, Integer.class);
                if (wlLeft != null) {
                    int raw = BydDeviceHelper.getIntValue(wlLeft);
                    if (raw >= 0
                            && raw != BydFeatureIds.BMS_UNAVAILABLE
                            && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2) {
                        b.wirelessChargingLeftState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessLeft error: " + e.getMessage());
            }
            try {
                Object wlRight = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_RIGHT_STATE, Integer.class);
                if (wlRight != null) {
                    int raw = BydDeviceHelper.getIntValue(wlRight);
                    if (raw >= 0
                            && raw != BydFeatureIds.BMS_UNAVAILABLE
                            && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2) {
                        b.wirelessChargingRightState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessRight error: " + e.getMessage());
            }
            try {
                Object wlState = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_STATE, Integer.class);
                if (wlState != null) {
                    int raw = BydDeviceHelper.getIntValue(wlState);
                    if (raw >= 0
                            && raw != BydFeatureIds.BMS_UNAVAILABLE
                            && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2) {
                        b.wirelessChargingStatus(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessState error: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("collectCharging error: " + e.getMessage());
        }
        if (!chargingStateApplied) {
            // A failure before the BMS read is still an unavailable observation, but it must be one
            // semantic write rather than UNAVAILABLE followed by a restoration of the old state.
            b.chargingState(observedChargingState);
        }
        return evidence;
    }

    private void collectInstrument(BydVehicleData.Builder b) {
        // Clear the cluster charge-power BEFORE anything can bail out. The read itself lives
        // ~100 lines down inside the shared try below, so a null instrument device (early
        // return) or a throw from ANY earlier read in this method would skip the reset — and
        // because this builder is seeded from the previous snapshot via toBuilder(), the last
        // good fallback rate would otherwise stick indefinitely. Resetting up front makes these
        // ambiguous fields genuinely per-poll on every exit path.
        b.clusterChargePowerKw(Double.NaN);
        b.chargePowerKw(Double.NaN);
        if (!ChargeSourceClassifier.isCounter(ChargeSourceClassifier.SRC_EXTERNAL)) {
            b.externalChargingPowerKw(Double.NaN);
        }
        if (instrumentDevice == null) return;
        try {
            // Named getter for outside temperature
            Object extTemp = BydDeviceHelper.callGetter(instrumentDevice, "getOutCarTemperature");
            if (extTemp instanceof Number) {
                int t = ((Number) extTemp).intValue();
                if (isPlausibleOutsideTempC(t)) b.outsideTempC(t);
            }
            if (Double.isNaN(b.outsideTempC) && acDevice != null) {
                try {
                    java.lang.reflect.Method m = acDevice.getClass().getMethod("getTemprature", int.class);
                    Object tempVal = m.invoke(acDevice, 4);
                    if (tempVal instanceof Number) {
                        int t = ((Number) tempVal).intValue();
                        if (isPlausibleOutsideTempC(t)) b.outsideTempC(t);
                    }
                } catch (Exception ignored) {}
            }

            // External charging power. STORED RAW — no scaling, on either the listener or the polled
            // path. The old "above 50 means hectowatts, divide" heuristic is deleted: it rested on
            // asserted captures (221.7 for ~1.9 kW, 189.5 for ~1.8 kW) that exist nowhere in this repo
            // as a log or device record, and a magnitude split is unsound regardless, since the
            // ambiguous band holds both a real DC rate and a plausible hectowatt reading. Worse, this
            // same accessor answers a cumulative kWh COUNTER on some firmware — a captured sample read
            // 119.0 with the gun physically out — so a divide here silently turned an energy total into
            // a believable rate. The unit is decided at runtime from movement (ChargeSourceClassifier)
            // and converted by ChargeRateResolver against the kWh-grounded counter slope.

            // Pack-side charge power from getChargePower(). Store the RAW value: some firmware
            // exposes hectowatts (650/700 means 6.5/7.0 kW), while another exposes plain kW, and no
            // magnitude split can distinguish that from a real DC rate. The shared source pipeline
            // classifies behavior and lets ChargeRateResolver corroborate scale independently.
            // Reset before the read, exactly as clusterChargePowerKw is. toBuilder() carries the
            // previous poll's value forward, so a FAILING read (callGetter returns null, or the value
            // falls outside the envelope) silently republished the last good rate — and this field is
            // a priceable cascade tier, so a stale in-band value becomes persisted energy on a car
            // that has stopped charging. The gun-out clear does not cover this: it only fires when the
            // gun/BMS says the session ended, whereas a dead accessor says nothing at all.
            Object chgPower = BydDeviceHelper.callGetter(instrumentDevice, "getChargePower");
            if (chgPower instanceof Number) {
                double raw = ((Number) chgPower).doubleValue();
                if (Math.abs(raw) > 0.01) {
                    storeChargingSource(b, SRC_PACK_SIDE_DIRECT, raw,
                            v -> b.chargePowerKw(v)
                                    .chargePowerAtMs(System.currentTimeMillis()));
                } else if (allowsRawChargingEvidence(
                        b.chargingGunState, b.vtolCharging)) {
                    try {
                        com.overdrive.app.monitor.ChargingDetector.getInstance()
                                .observeRawChargingSignal(SRC_PACK_SIDE_DIRECT, 0.0);
                    } catch (Throwable ignored) {}
                }
            }

            // Reset before the read, exactly as clusterChargePowerKw is. toBuilder() carries the previous
            // poll's value forward, so a FAILED getter silently republished the last rate with nothing to
            // age it out — and a RATE source has no freshness bound in the resolver, so it stayed
            // publishable and priceable indefinitely. The gun-out clear does not cover a dead accessor,
            // which reports no terminal state at all.
            // Reset unconditionally while this source is a RATE. Once it is a confirmed COUNTER, HOLD
            // the previous value instead: the admission gate refuses everything at a terminal BMS state,
            // so resetting here left the close path with NaN and the session lost its final energy tail.
            // An energy total does not go stale the way a rate does — it remains this session's figure —
            // and it cannot leak into the next session, which re-baselines at SESSION START.
            // getExternalChargingPower is NOT reliably an instantaneous rate. On some firmware
            // families it answers a cumulative charged-energy counter (kWh) that keeps its value
            // with the gun out — a captured sample read 119.0 while gunState=1 (unplugged) and
            // the BMS was idle, which no live rate can be. Its meaning is therefore decided by
            // ChargeSourceClassifier from how the value MOVES, not by its magnitude.
            //
            // The previous `raw > 50 -> raw/100` rule is deleted: it is a magnitude guess with no
            // basis in the API, it silently turned that 119.0 counter into a plausible-looking
            // "1.19 kW", and it would divide a genuine 60-500 kW DC session by 100 on a BEV.
            // Store the RAW value and let the consumer interpret it.
            Object extPower = BydDeviceHelper.callGetter(instrumentDevice, "getExternalChargingPower");
            if (extPower instanceof Number) {
                double raw = ((Number) extPower).doubleValue();
                storeChargingSource(b, ChargeSourceClassifier.SRC_EXTERNAL, raw,
                        v -> b.externalChargingPowerKw(v)
                                .externalChargingPowerAtMs(System.currentTimeMillis()));
            }

            // CLUSTER charge power — feature ID 842006552 (0x32300018,
            // Instrument.CHARGING_CHARGE_POWER_DD). This is the number the dash itself shows,
            // and remains a useful guarded fallback on PHEV when the dedicated charging-device
            // property is unavailable. It is not the primary source: firmware can retain a
            // plausible idle value here, so VehicleDataMonitor selects the dedicated property first.
            //
            // Read UNCONDITIONALLY. It used to be gated behind
            //   isNaN(externalChargingPowerKw) && (isNaN(chargingPowerKw) || ==0)
            // i.e. "only if the typed getters found nothing" — which is exactly backwards for
            // PHEV. There getExternalChargingPower() returns the EVSE's RATED capacity: a flat,
            // confidently-WRONG value (~7 kW on a 1.7 kW charge). That satisfied the gate, so
            // this good source NEVER ran on the very trims that need it, and PHEV fell through
            // to the SOC-derived estimator and the nominal 3.3/7.0 kW placeholder. The OEM
            // reference cascades on the VALUE being out of band, never on a previous getter
            // having succeeded — so do the same: read it, then let the consumer cascade decide.
            //
            // Stored separately in clusterChargePowerKw (NOT over chargingPowerKw) so the
            // consumer can prefer it explicitly without this read clobbering the device-getter
            // value that BEV logic still uses.
            //
            // Scaling: NONE. scaleClusterChargePowerKw() is an identity, and the magnitude-inferred
            // "/100 above 22 kW" rule it used to apply is deleted. That rule rested on a single
            // asserted capture (a "1.8 kW charger reports 189.5 raw") for which no log or device
            // record exists anywhere in this repo — only the assertion itself, restated in comments.
            // Meanwhile a magnitude split cannot work even in principle: the ambiguous band holds
            // both a genuine 60-500 kW DC rate and a plausible hectowatt reading, so any threshold
            // is a 100x error on one firmware family or the other. The unit is decided at runtime
            // from how the value MOVES (ChargeSourceClassifier) and converted by ChargeRateResolver,
            // which corroborates a rate against the kWh-grounded counter slope instead of guessing.
            // Do not reintroduce a divide here without a device capture that shows the raw value
            // alongside the dash reading DURING a charge.
            //
            // That inference is not decidable for raw 22..500: it is either a 22-500 kW DC fast
            // charge or a 0.22-5 kW AC charge, and it is resolved as the latter (our field
            // evidence). Which means this stored value is only trustworthy where the ambiguous
            // band is physically unreachable — i.e. on PHEV, whose onboard charger cannot exceed
            // ~7 kW. The read itself stays unconditional and drivetrain-agnostic (a debug dump
            // should show what the cluster reports on any car); it is the CONSUMER that is
            // restricted, in VehicleDataMonitor.getChargingState(), which uses this field on PHEV
            // only. Anyone lifting that restriction must first make the scale unambiguous —
            // otherwise a genuine 150 kW BEV session displays as 1.5 kW.
            try {
                // Probe the primitive widths rather than passing a wrapper Class: the HAL
                // dispatches on the EXACT Class object and matches PRIMITIVES only, so the old
                // `Double.class` argument returned null on every trim (see
                // BydDeviceHelper.callGetProbing). That alone would have kept this read dead
                // even without the gating bug above.
                Object val = BydDeviceHelper.callGetProbing(
                        instrumentDevice, BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_POWER_DD);
                // SECOND CHANNEL. The per-device get(int[], Class) form above is not the only way
                // into the HAL: the manager exposes getDouble(deviceType, featureId), and a trim
                // may implement one and not the other. Reference apps resolve BOTH accessors at
                // init; we only ever probed the per-device form, so on such a trim this read came
                // back null and the whole PHEV cascade collapsed to an estimate. Only consulted
                // when the primary yielded nothing, so a working trim is unaffected.
                // Trigger on "no USABLE number", not merely on a null reference. callGetProbing's
                // width ladder returns the first non-null result, but getDoubleValue always reads
                // BYDAutoEventValue.doubleValue — so a trim that answers only on Integer.TYPE hands
                // back a non-null wrapper whose doubleValue is still its default 0.0. Gating on
                // `val == null` would treat that as a successful read and never consult the second
                // channel, which is precisely the trim this fallback exists for.
                double devRaw = (val != null) ? BydDeviceHelper.getDoubleValue(val) : Double.NaN;
                boolean devUsable = !Double.isNaN(devRaw) && devRaw != 0.0;
                // Plausible-charging-context gate, hoisted so the READ shares it with the log below.
                // A parked, unplugged car cannot have a charge power, so walking the fallback chain
                // there is pure IPC waste: each attempt is 1-3 uncached binder round-trips (the
                // Method resolves on a real trim, so only the VALUE lookup can be cached — the
                // invoke cannot). Ungated that was thousands of pointless calls/day. The primary
                // read above is deliberately NOT gated: it is a single cheap call and the field is
                // recomputed per poll by design.
                boolean chargingContext = accIsOn
                        || b.chargingGunState == 2 || b.chargingGunState == 3
                        || b.chargingGunState == 4 || b.chargingState == 1;
                double mgrKw = Double.NaN;
                if (!devUsable && chargingContext) {
                    mgrKw = BydManagerChannel.getDouble(context, instrumentDevice,
                            BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_POWER_DD);
                }
                // Resolve to a value or explicitly to NaN on EVERY poll. Unlike its siblings
                // (which stay sticky until the terminal-state clear in collectCharging), this
                // field is the TOP of the power cascade and toBuilder() carries it forward, so
                // a one-off good read followed by failing reads would pin a phantom rate for
                // the rest of the session. Recomputing per poll makes it self-correcting.
                // (The method entry already reset it to NaN so the paths that never reach this
                // line — null device, a throw from an earlier read — are covered too.)
                double clusterKw = Double.NaN;
                // Take whichever channel produced a number. Both feed the SAME scaling and the SAME
                // validity gates below, so a manager-sourced value can never bypass a check that a
                // device-sourced one has to pass.
                //
                // UNIT CAVEAT — deliberately unresolved, and instrumented rather than guessed.
                // Asymmetry 1 records that this feature id is hectowatts on the firmware family we
                // captured and plain kW on the family the reference apps target, with no unit flag;
                // scaleClusterChargePowerKw() therefore INFERS the scale from magnitude. Whether the
                // manager channel pre-scales differently from the per-device channel is unknown —
                // we have no capture in which the manager channel produced a value at all (it only
                // runs where the per-device read already failed). Both are fed through the same
                // inference because a magnitude-based rule is unit-agnostic by construction: a kW
                // value stays kW, a hectowatt value is divided. The residual risk is confined to
                // the ambiguous 22..500 raw band, which a PHEV onboard charger cannot reach — the
                // same containment that already makes this field PHEV-only. The raw log below
                // records WHICH channel produced the number, so the first capture that exercises
                // the manager path settles it with data instead of inference.
                boolean fromManager = !devUsable;
                if (devUsable || !Double.isNaN(mgrKw)) {
                    double raw = devUsable ? devRaw : mgrKw;
                    // RAW-SCALE CAPTURE, throttled 1/min and independent of every gate below.
                    // The one-shot scale logs only fire when a value is ACCEPTED, so on a trim
                    // where the gates never open (exactly the log_X5RRX996 case) we learned
                    // nothing about the unit — and the kW-vs-hectowatts question can only be
                    // settled by seeing the raw number next to a known charger rate. Logging the
                    // pre-scale value plus the gate states makes one real charge conclusive.
                    // Gated on a plausible charging context, not unconditional: an always-on
                    // 1/min INFO would emit ~1440 lines/day forever on every trim including a
                    // parked car, long after the kW-vs-hectowatts question is settled. Emitting
                    // only while ACC is on or a charge looks live keeps the capture useful and
                    // bounded — and the answer only exists during a charge anyway.
                    long rawNow = System.currentTimeMillis();
                    if (chargingContext && rawNow - lastClusterRawLogMs > 60_000L) {
                        lastClusterRawLogMs = rawNow;
                        logger.info(String.format(java.util.Locale.US,
                            "CHARGING_CHARGE_POWER_DD raw=%.3f src=%s kind=%s"
                            + " (bmsState=%d gunState=%d)",
                            raw, fromManager ? "manager" : "device",
                            ChargeSourceClassifier.kindOf(ChargeSourceClassifier.SRC_CLUSTER),
                            b.chargingState, b.chargingGunState));
                    }
                    // Floor matches the CONSUMERS (>0.1), not a separate 1.0. A kW-reporting trim
                    // tapering at 0.9 kW had its cluster reading dropped here while the taper gate
                    // still accepted >0.1 — so the source vanished exactly when the taper needed it,
                    // ending taper accounting and losing the tail energy. Two thresholds for one
                    // field is the bug; there is now one.
                    // Ceiling is the shared raw envelope, not a separate 35000. The two disagreed, and
                    // the smaller one won: a 350-500 kW DC session reported in hectowatts (raw
                    // 35000-50000) was rejected here before the resolver could calibrate it.
                    if (!Double.isNaN(raw) && Math.abs(raw) > 0.1
                            && Math.abs(raw) <= RAW_RATE_ENVELOPE_MAX
                            && isRawChargingSourceValueAdmissibleForCurrentDrivetrain(raw)) {
                        double kw = scaleClusterChargePowerKw(raw);
                        // Band matches every CONSUMER of this field exactly (VehicleDataMonitor's
                        // cascade gate and the diagnostic JSON both use >0.1 && <=300, the same
                        // convention as its chargePowerKw sibling). Accepting up to 500 here
                        // while consumers cap at 300 would store values that silently never
                        // surface — the field would look populated in a debug dump yet be
                        // skipped by the cascade, which is worse than rejecting them outright.
                        // Suppress on an EXPLICIT non-charging terminal state (READY=0,
                        // FINISHED=2, TERMINATED=4, DISCHARG_FINISH=12). The cluster feature id
                        // keeps answering with the last in-band rate after the gun comes out, and
                        // collectInstrument runs immediately AFTER collectCharging in both poll
                        // entry points and overwrites this field unconditionally — so the sibling
                        // clear in collectCharging's terminal block could never survive the same
                        // poll. Without this, a finished session reported a phantom rate (e.g.
                        // 1.895 kW) into the PHEV cascade and poisoned the session peak/avg. NOT
                        // suppressed on IDLE=15, which is the buggy state some PHEV firmwares
                        // report while genuinely charging.
                        // GUN-CONNECTED CARVE-OUT for FINISHED(2). Field evidence
                        // (log_X5RRX996): at 07:01:28 the BMS flipped to state 2 at 100% SOC
                        // while gunState stayed 2 (AC connected) and the pack kept drawing taper
                        // current. Treating FINISHED as terminal there suppressed this — the only
                        // trustworthy PHEV source — for the whole session, and the cascade fell
                        // through to a frozen SOC-derived estimate. On BYD PHEV firmware "FINISHED"
                        // means "bulk charge complete", NOT "cable removed": the CV taper continues
                        // and the dash keeps showing a real rate.
                        //
                        // Scoped deliberately to FINISHED only, and only while the gun asserts a
                        // CHARGING-direction connection (2=AC, 3=DC, 4=AC_DC — never 5=V2L, where
                        // the pack DISCHARGES and any rate would be backwards; see I7). READY(0),
                        // TERMINATED(4) and DISCHARG_FINISH(12) stay terminal unconditionally:
                        // those mean the session is genuinely over or reversed, so the sticky
                        // feature-id value must not survive them.
                        boolean gunCharging = b.chargingGunState == 2
                                || b.chargingGunState == 3 || b.chargingGunState == 4;
                        boolean terminalState = isTerminalChargingState(b.chargingState);
                        boolean finishedConnected = b.chargingState == 2 && gunCharging;
                        // ALSO require the fused detector to agree a session is live. Device
                        // capture (boot, ACC off, gun out): this feature id emitted raw=359.4 →
                        // 3.594 kW — the same "~359 garbage when idle" signature getChargePower
                        // shows — and 3.594 is squarely in band, so the magnitude gates can't
                        // reject it, and chargingState wasn't a terminal code at that instant so
                        // the suppression above didn't either. The detector fuses BMS state +
                        // isCharging() + power flow and reads nothing derived from this field
                        // (no circularity), so idle junk is rejected while a real session —
                        // including the PHEV stuck-at-IDLE(15) firmware case, which the detector
                        // exists to catch — still passes on its very first poll.
                        boolean sessionLive = false;
                        try {
                            sessionLive = com.overdrive.app.monitor.ChargingDetector
                                    .getInstance().isCharging();
                        } catch (Throwable ignored) { /* detector not up yet → treat as idle */ }
                        // SECOND ADMISSION PATH — the detector is not the only proof of a live
                        // session. It can legitimately read false during a real charge: it fused
                        // ON->OFF at 07:01:28 on `l1-bms-negative` (BMS said FINISHED) while the
                        // cable was still in and SOC still rising. Requiring isCharging() alone
                        // therefore gated the good source off exactly when the BMS lies — which is
                        // the failure this field exists to cover.
                        //
                        // A charging-direction gun assertion is independent physical evidence that
                        // a cable is delivering energy, and it defeats the raw=359.4 idle-junk case
                        // that motivated the original gate: that capture was taken with the gun OUT
                        // (boot, ACC off), so gunCharging is false there and the junk is still
                        // rejected. No circularity — gunState comes from collectCharging (which runs
                        // BEFORE this method in both poll entry points) and is not derived from this
                        // field (I6).
                        // NB: no `&& !terminalState` here — the accept condition below already
                        // requires it, so repeating it was pure boolean noise.
                        boolean plugAsserted = gunCharging;
                        // Movement evidence for L3, fed BEFORE the gate below — the same ungated
                        // channel the other sources get in storeChargingSource. On a trim where the
                        // cluster feature id is the only live charging signal, this is the only
                        // evidence L3 can bootstrap from, and the gate consults the detector, so a
                        // gated-only feed deadlocks. Bounded to the SDK envelope first so idle junk
                        // and sentinels cannot register as movement. The bound is the RAW envelope, not
                        // the kW ceiling: this channel only reports that a value MOVED, which is
                        // unit-agnostic, and capping at 500 excluded every hectowatt reading above 5 kW
                        // from detection — so a 7 kW session (raw 700) was stored but could never start.
                        boolean rawEvidenceAllowed = allowsRawChargingEvidence(
                                b.chargingGunState, b.vtolCharging);
                        ChargeSourceClassifier.Kind clusterKind =
                                ChargeSourceClassifier.kindOf(
                                        ChargeSourceClassifier.SRC_CLUSTER);
                        boolean finishedTaperRate = terminalState
                                && finishedConnected
                                && clusterKind == ChargeSourceClassifier.Kind.RATE;
                        boolean rawMovementAllowed = shouldObserveClusterRawChargingSignal(
                                b.chargingState, b.chargingGunState, clusterKind);
                        if (rawEvidenceAllowed && rawMovementAllowed
                                && kw > 0.1 && kw <= RAW_RATE_ENVELOPE_MAX) {
                            try {
                                com.overdrive.app.monitor.ChargingDetector.getInstance()
                                        .observeRawChargingSignal(
                                                ChargeSourceClassifier.SRC_CLUSTER, kw);
                            } catch (Throwable ignored) { /* detector not up yet */ }
                        }
                        boolean terminalBarrierActive = isTerminalChargingBarrierActive();
                        boolean liveTrainingAllowed =
                                !terminalState && !terminalBarrierActive;
                        // Envelope must admit a HECTOWATT reading's full range, not just a kW one.
                        // The unit is unknown at this point — that is the whole premise — so a raw 700
                        // is either an impossible 700 kW or a perfectly ordinary 7.0 kW three-phase AC
                        // charge. Capping at 500 discarded the latter before ChargeRateResolver ever got
                        // the chance to calibrate it against the counter slope, which is precisely the
                        // trim this pipeline exists to serve. The ceiling is therefore the kW ceiling
                        // scaled by the candidate unit factor; the resolver still bounds the RESOLVED
                        // rate at 500 kW, so nothing implausible can reach a consumer.
                        // Direction still matters — a negative reading is discharge, not charging.
                        if (rawEvidenceAllowed && kw > 0.1 && kw <= RAW_RATE_ENVELOPE_MAX
                                && (sessionLive || plugAsserted)
                                && (liveTrainingAllowed || finishedTaperRate)) {
                            clusterKw = kw;
                            if (liveTrainingAllowed) {
                                ChargeSourceClassifier.observeWhileCharging(
                                        ChargeSourceClassifier.SRC_CLUSTER, kw);
                                // FINISHED values may refresh a known taper RATE snapshot, but frozen
                                // post-stop data must not train classification or resolver state.
                                try {
                                    com.overdrive.app.monitor.ChargeRateResolver.observe(
                                            ChargeSourceClassifier.SRC_CLUSTER, kw);
                                } catch (Throwable ignored) {}
                            }
                            if (!loggedClusterChargePowerScale) {
                                loggedClusterChargePowerScale = true;
                                logger.info("CHARGING_CHARGE_POWER_DD accepted raw=" + raw
                                        + " (stored unscaled; kind decided by observed movement)");
                            }
                        }
                    }
                }
                b.clusterChargePowerKw(clusterKw);
            } catch (Exception e) {
                logger.debug("collectInstrument cluster charge-power feature ID error: " + e.getMessage());
            }

            // Charging percent via instrument feature ID (842006544) — read
            // unconditionally as fallback when the chargingDevice path didn't
            // populate it. Gating on a BMS-derived "may be charging" flag here
            // creates the same circular dependency we removed from the power
            // reads above; the safe-clear in collectCharging() wipes stale
            // values when the vehicle is genuinely idle (BMS not charging AND
            // gun disconnected).
            if (b.chargingPercent == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(instrumentDevice,
                            BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_PERCENT_DD, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw >= 0 && raw <= 100) {
                            b.chargingPercent(raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectInstrument chargingPercent feature ID error: " + e.getMessage());
                }
            }

            // Charging rest time via instrument feature IDs (primary path)
            // Fallback to chargingDevice.getChargingRestTime() is in collectChargingExtended()
            // Validates: 255 = not available, hours 0-23, minutes 0-59
            //
            // CRITICAL: reset to UNAVAILABLE FIRST so this value RE-DERIVES every poll.
            // The builder carries the previous snapshot forward via toBuilder(), and the
            // fallback in collectChargingExtended() self-guards on `== UNAVAILABLE` — so
            // without this reset the FIRST rest-time reading of a session would latch and
            // never count down (the "Time to full stuck / not updating" bug). Clearing
            // here lets both the feature-ID path (below) and the fallback re-populate a
            // FRESH value each cycle; feature-ID priority is preserved because it runs
            // first and the fallback only fills when this is still UNAVAILABLE.
            b.chargingRestTimeHours(BydVehicleData.UNAVAILABLE);
            b.chargingRestTimeMinutes(BydVehicleData.UNAVAILABLE);
            try {
                Object hourVal = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_REST_HOUR_DD, Integer.class);
                Object minVal = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_REST_MINUTE_DD, Integer.class);
                if (hourVal != null && minVal != null) {
                    int hours = BydDeviceHelper.getIntValue(hourVal);
                    int minutes = BydDeviceHelper.getIntValue(minVal);
                    if (hours != 255 && minutes != 255 && hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                        b.chargingRestTimeHours(hours);
                        b.chargingRestTimeMinutes(minutes);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectInstrument chargingRestTime feature ID error: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("collectInstrument error: " + e.getMessage());
        }
    }

    private void collectOta(BydVehicleData.Builder b) {
        if (otaDevice == null) return;
        try {
            Object voltage = BydDeviceHelper.callGetter(otaDevice, "getBatteryPowerVoltage");
            if (voltage instanceof Number && ((Number) voltage).doubleValue() > 0 && ((Number) voltage).doubleValue() < 20) {
                b.voltage12v(((Number) voltage).doubleValue());
            } else {
                try {
                    java.lang.reflect.Method m = otaDevice.getClass().getMethod("getBatteryVoltage", int.class);
                    Object v5 = m.invoke(otaDevice, 0);
                    if (v5 instanceof Number) {
                        double v = ((Number) v5).doubleValue();
                        if (v > 0 && v < 20) b.voltage12v(v);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.debug("collectOta error: " + e.getMessage());
        }
    }

    private void collectGearbox(BydVehicleData.Builder b) {
        int g = readGearNow();
        if (g != BydVehicleData.UNAVAILABLE) {
            b.gearMode(g);
        }
    }

    private void collectAc(BydVehicleData.Builder b) {
        if (acDevice == null) return;
        try {
            int acState = readAcPowerNow();
            if (acState != BydVehicleData.UNAVAILABLE) b.acStartState(acState);
            Object cycle = BydDeviceHelper.callGetter(acDevice, "getAcCycleMode");
            if (cycle instanceof Number) b.acCycleMode(((Number) cycle).intValue());
            Object wind = BydDeviceHelper.callGetter(acDevice, "getAcWindMode");
            if (wind instanceof Number) b.acWindMode(((Number) wind).intValue());
            Object fanLevel = BydDeviceHelper.callGetter(acDevice, "getAcWindLevel");
            if (fanLevel instanceof Number) {
                int level = ((Number) fanLevel).intValue();
                if (level >= 0 && level <= 7) b.acFanLevel(level);
            }
            Object unit = BydDeviceHelper.callGetter(acDevice, "getTemperatureUnit");
            if (unit instanceof Number) b.tempUnit(((Number) unit).intValue());
            // Dial SETPOINTS (areas 1/2) — the value the user asked for, as opposed to the
            // sensed cabin air. Written only when the read answered, so a miss leaves the
            // carried-forward value rather than fabricating one.
            int spDriver = readAcSetpointNow(AC_TEMP_AREA_DRIVER);
            if (spDriver != BydVehicleData.UNAVAILABLE) b.acSetpointDriver(spDriver);
            int spPassenger = readAcSetpointNow(AC_TEMP_AREA_PASSENGER);
            if (spPassenger != BydVehicleData.UNAVAILABLE) b.acSetpointPassenger(spPassenger);
        } catch (Exception e) {
            logger.debug("collectAc error: " + e.getMessage());
        }
    }

    private void collectLight(BydVehicleData.Builder b) {
        if (lightDevice == null) return;
        try {
            Object left = BydDeviceHelper.callGetter(lightDevice, "getTurnLightState", 1);
            if (left instanceof Number) b.leftTurnState(((Number) left).intValue());
            Object right = BydDeviceHelper.callGetter(lightDevice, "getTurnLightState", 2);
            if (right instanceof Number) b.rightTurnState(((Number) right).intValue());
            // Light TYPE indices are the BYDAutoLightDevice constants (SDK javadoc
            // doc/constant-values.html): 1=LIGHT_SIDE, 2=LIGHT_LOW_BEAM, 3=LIGHT_HIGH_BEAM,
            // 4/5=turn signals, 6=LIGHT_FRONT_FOG, 7=LIGHT_REAR_FOG, 8=LIGHT_FOOT.
            //
            // These were previously read one position LOW (1=low, 2=high, …), so every beam
            // and fog readout named the wrong lamp. The visible symptom was "both low AND
            // high beam show on in AUTO mode": with the headlights on, side(1)+low(2) are both
            // lit, which the old mapping reported as low+high. In MANUAL high beam the
            // extra high(3) lamp made the same wrong pair look correct — hence "works in
            // manual, wrong in auto". Fog was swapped front/rear for the same reason, and
            // hazard read LIGHT_FOOT (the footwell lamp) rather than any hazard state.
            // Each lamp is written ONLY when the read actually answered. getLightStatus returns
            // UNAVAILABLE (not 0) on a miss, because a fabricated "0" is indistinguishable from a
            // genuine "off": on a trim that doesn't implement getLightStatus, every poll used to
            // publish a definite lights=off, firing "when low beam turns off" repeatedly. The
            // fast automation path (readBeamNow → BydEvent.pollClimate) already skipped the
            // publish on a miss, so this makes the snapshot path agree with it.
            int lowBeamRaw = getLightStatus(LIGHT_TYPE_LOW_BEAM);
            if (lowBeamRaw != BydVehicleData.UNAVAILABLE) b.lowBeam(lowBeamRaw == 1);
            int highBeamRaw = getLightStatus(LIGHT_TYPE_HIGH_BEAM);
            if (highBeamRaw != BydVehicleData.UNAVAILABLE) b.highBeam(highBeamRaw == 1);
            int frontFogRaw = getLightStatus(LIGHT_TYPE_FRONT_FOG);
            if (frontFogRaw != BydVehicleData.UNAVAILABLE) b.frontFog(frontFogRaw == 1);
            int rearFogRaw = getLightStatus(LIGHT_TYPE_REAR_FOG);
            if (rearFogRaw != BydVehicleData.UNAVAILABLE) b.rearFog(rearFogRaw == 1);
            // Hazard comes from getTurnLightFlashState (states 6/7), not getLightStatus(8):
            // light type 8 is the footwell lamp. The live automation path reads the combined
            // turn enum directly; the snapshot retains the raw current phase for telemetry.
            int turn = readTurnNow();
            if (turn >= 0) b.hazard((turn & 0x3) == 0x3);
            int drl = readDrlNow();
            if (drl != BydVehicleData.UNAVAILABLE) b.dayTimeLight(drl == 1);
            int autoLight = readAutoHeadlightNow();
            if (autoLight != BydVehicleData.UNAVAILABLE) b.lightAutoStatus(autoLight);
        } catch (Exception e) {
            logger.debug("collectLight error: " + e.getMessage());
        }
    }

    // BYDAutoLightDevice.getLightStatus() light-TYPE constants, per the SDK javadoc's
    // constant-values table. Named here (rather than inlined as bare ints) because the two
    // callers — collectLight's snapshot path and readBeamNow's fast automation path — MUST
    // agree; they previously each carried their own off-by-one copy of the numbering.
    static final int LIGHT_TYPE_LOW_BEAM   = 2;
    static final int LIGHT_TYPE_HIGH_BEAM  = 3;
    static final int LIGHT_TYPE_FRONT_FOG  = 6;
    static final int LIGHT_TYPE_REAR_FOG   = 7;
    /**
     * Whether a raw AC_TEMP_INSIDE reading is a usable CABIN temperature in Celsius.
     *
     * <p>The value needs NO decoding — the feature id reports whole degrees Celsius directly
     * (no offset, no scale). Verified against the reference app, which stores the raw int
     * verbatim and renders it as "<i>n</i>℃".
     *
     * <p>The lower bound mirrors the reference app's ({@code <= -128} → reject), which also
     * excludes {@link Integer#MIN_VALUE}, our own read-failed marker, and every negative
     * unavailable sentinel in this file (BMS_UNAVAILABLE, INVALID_VALUE, …).
     *
     * <p>An UPPER bound is required as well, even though the reference app has none: unlike that
     * app — which only renders the value into a text view — this reading fans out to consumers
     * with no band of their own (the MQTT {@code cabin_temp} field, the ABRP {@code car_temp}
     * telemetry, the vehicle-control API/snapshot JSON, and the {@code temperature} automation
     * condition). A positive "not available" sentinel would therefore be published as a real
     * temperature, and because {@link BydVehicleData#toBuilder()} carries the field forward while
     * a later failed read writes nothing, that bad value would LATCH indefinitely — permanently
     * satisfying every "cabin above X" rule. 0xFFFF/0xFF are the classic CAN not-available values
     * (this file already treats them as such elsewhere), and they are positive, so the lower
     * bound alone cannot stop them.
     *
     * <p>90 C is the ceiling: a car parked in direct sun genuinely reaches 60-70 C, so the bound
     * has to sit well above habitable to avoid discarding true extremes — exactly when a
     * heat-warning automation matters most — while still rejecting 255/65535 and any
     * offset/scale decode error.
     *
     * <p>Note 0 is NOT treated as invalid even though {@code AC_TEMP_INVALID == 0}: that constant
     * applies to the {@code getTemprature(area)} setpoint API, whose valid range starts at 17,
     * not to this measured feature id — where 0 C is a genuine winter reading. The reference app
     * accepts 0 on this channel too.
     */
    private static boolean isPlausibleCabinTempC(int raw) {
        return raw > -128 && raw <= 90;
    }

    /**
     * Whether a raw reading is a plausible OUTSIDE/ambient temperature in Celsius.
     *
     * <p>The -50..60 band the HAL read has always used, extracted so the CLOUD fallback shares it.
     * Previously only the HAL producer applied it, so a cloud {@code tempOutCar} sentinel (255,
     * 65535) was published verbatim — and because {@link BydVehicleData#toBuilder()} carries the
     * field forward while a later failed read writes nothing, it LATCHED. That fed
     * {@code OUTSIDE_TEMPERATURE} and, since the smart {@code TEMPERATURE} event falls back to
     * ambient whenever the car is parked, the cabin condition too — permanently satisfying every
     * "above X" rule, plus HA's {@code ext_temp}.
     *
     * <p>Narrower than the cabin band on purpose: ambient air has a much tighter physical range
     * than a sun-baked cabin interior.
     */
    private static boolean isPlausibleOutsideTempC(int raw) {
        return raw >= -50 && raw <= 60;
    }

    /**
     * Read the measured cabin temperature in whole degrees Celsius, or {@link Integer#MIN_VALUE}
     * when unavailable. The poll path calls this single reader once and publishes that observation
     * to both cabin fields, so they cannot disagree about the channel, decode, or validity rule.
     *
     * <p>Guards the {@code intValue == 0} type-mismatch signature the same way
     * {@code collectStatTemp} does: {@link BydDeviceHelper#callGet}'s {@code (int,int)} overload
     * branch ignores the requested return type, so on a device exposing only that form the
     * returned event object can carry an unpopulated {@code intValue} — which reads as a
     * perfectly plausible 0 C. A populated {@code doubleValue} alongside a zero {@code intValue}
     * is that mismatch; a genuine 0 C reading arrives with doubleValue at its own default.
     * Without this, a mid-summer cabin could publish 0 C to HA/ABRP and fire a
     * "cabin below 5 C" rule.
     */
    private int readCabinTempC() {
        if (acDevice == null) return Integer.MIN_VALUE;
        Object val = BydDeviceHelper.callGet(acDevice, BydFeatureIds.AC_TEMP_INSIDE, Integer.class);
        if (val == null) return Integer.MIN_VALUE;
        int raw = BydDeviceHelper.getIntValue(val);
        if (raw == 0) {
            double asDouble = BydDeviceHelper.getDoubleValue(val);
            if (!Double.isNaN(asDouble) && asDouble != 0.0) {
                logger.debug("readCabinTempC: intValue=0 but doubleValue=" + asDouble
                        + " — type/field mismatch, discarding");
                return Integer.MIN_VALUE;
            }
        }
        return isPlausibleCabinTempC(raw) ? raw : Integer.MIN_VALUE;
    }

    /**
     * Read and publish the current poll's one cabin-temperature observation.
     *
     * @return true only when AC_TEMP_INSIDE answered with a valid raw whole-degree Celsius value
     */
    private boolean collectCabinTemperature(BydVehicleData.Builder b) {
        try {
            int raw = readCabinTempC();
            if (raw == Integer.MIN_VALUE) return false;
            b.insideTempC(raw, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            logger.debug("collectCabinTemperature error: " + e.getMessage());
            return false;
        }
    }

    // ── AC temperature SETPOINT (the dial), distinct from the measured cabin temp ────

    /** {@code getTemprature(area)} areas — MAIN=1 / DEPUTY=2 are the driver / passenger dials. */
    public static final int AC_TEMP_AREA_DRIVER = 1;
    public static final int AC_TEMP_AREA_PASSENGER = 2;
    /** {@code getTemperatureUnit()}: 0 = Fahrenheit, non-zero = Celsius (proven against the OEM implementation). */
    public static final int TEMP_UNIT_FAHRENHEIT = 0;
    /** Setpoint clamps per display unit — the SDK's own documented dial ranges. */
    public static final int AC_SETPOINT_MIN_C = 17, AC_SETPOINT_MAX_C = 33;
    public static final int AC_SETPOINT_MIN_F = 64, AC_SETPOINT_MAX_F = 91;
    /** {@code AC_TEMP_INVALID} — the setpoint API's documented "no value" return. */
    private static final int AC_TEMP_INVALID = 0;

    /**
     * The AC temperature SETPOINT (what the dial is asking for), in whatever unit the head
     * unit displays — NOT {@link #readCabinTempC()}, which is the measured air temperature.
     * Confusing the two is the trap a relative "+1 degree" step falls into: with the dial at
     * 22 and a cold cabin reading 12, stepping from the sensed value would slam the dial to 13.
     *
     * <p>Area 1 = driver (MAIN), 2 = passenger (DEPUTY) — the mapping both reference apps use
     * (the OEM reads area 1 for the dial and treats 1/2/4 as driver/passenger/outside).
     *
     * <p>Returns {@link BydVehicleData#UNAVAILABLE} on a miss rather than a plausible-looking
     * default. Both reference apps fall back to a hardcoded 24, which is exactly the fabricated
     * reading this codebase refuses elsewhere: a step built on a fake 24 would move the dial to
     * 25 regardless of where it actually sat. The value is accepted if it falls in EITHER dial
     * band, so a Fahrenheit dial (64..91) isn't rejected by the Celsius one.
     *
     * @param area {@link #AC_TEMP_AREA_DRIVER} or {@link #AC_TEMP_AREA_PASSENGER}
     */
    public int readAcSetpointNow(int area) {
        if (acDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object val = BydDeviceHelper.callGetter(acDevice, "getTemprature", area);
            if (!(val instanceof Number)) return BydVehicleData.UNAVAILABLE;
            int raw = ((Number) val).intValue();
            // AC_TEMP_INVALID (0) is the documented no-value return on THIS api — and 0 is
            // outside both dial ranges anyway, so it can never be a real setpoint.
            if (raw == AC_TEMP_INVALID) return BydVehicleData.UNAVAILABLE;
            // Validate against EITHER dial band rather than re-reading the unit here. The bands
            // are disjoint (17..33 vs 64..91), so "in one of them" is already the full validity
            // test — and asking the device for the unit on every setpoint read would triple the
            // per-poll reads (collectAc reads it once for tempUnit) while adding a window where
            // the unit answers but the reading was taken under the other one.
            return inferUnitFromSetpoint(raw) != BydVehicleData.UNAVAILABLE
                    ? raw : BydVehicleData.UNAVAILABLE;
        } catch (Throwable t) {
            logger.debug("readAcSetpointNow error: " + t.getMessage());
            return BydVehicleData.UNAVAILABLE;
        }
    }

    /**
     * The head unit's display temperature unit, or {@link BydVehicleData#UNAVAILABLE} on a miss.
     * Deliberately NOT defaulted to Celsius: the unit decides both the clamp band and the value
     * the write declares, so guessing it would silently mis-clamp a Fahrenheit dial.
     */
    public int readTempUnitNow() {
        if (acDevice == null) return BydVehicleData.UNAVAILABLE;
        Object unit = BydDeviceHelper.callGetter(acDevice, "getTemperatureUnit");
        return (unit instanceof Number) ? ((Number) unit).intValue() : BydVehicleData.UNAVAILABLE;
    }

    /**
     * The display unit implied by an already-validated setpoint READING, used when
     * {@code getTemperatureUnit()} doesn't answer.
     *
     * <p>Sound because the two dial bands are disjoint — 17..33 C ends far below 64..91 F — so a
     * reading can only belong to one of them. This matters: falling back to Celsius on an
     * unreadable unit would clamp a legitimate 73 F step down to 33, yanking the dial across the
     * scale. Inferring from the value keeps the step correct on a trim whose unit getter is
     * dormant but whose dial reads fine.
     *
     * @return the unit constant, or {@link BydVehicleData#UNAVAILABLE} if [setpoint] is in neither band
     */
    private static int inferUnitFromSetpoint(int setpoint) {
        if (setpoint >= AC_SETPOINT_MIN_C && setpoint <= AC_SETPOINT_MAX_C) return 1;  // non-zero = Celsius
        if (setpoint >= AC_SETPOINT_MIN_F && setpoint <= AC_SETPOINT_MAX_F) return TEMP_UNIT_FAHRENHEIT;
        return BydVehicleData.UNAVAILABLE;
    }

    /** Clamp a setpoint into the dial range for [unit] (mirrors the OEM's own clamp). */
    public static int clampSetpoint(int value, int unit) {
        boolean f = unit == TEMP_UNIT_FAHRENHEIT;
        int min = f ? AC_SETPOINT_MIN_F : AC_SETPOINT_MIN_C;
        int max = f ? AC_SETPOINT_MAX_F : AC_SETPOINT_MAX_C;
        return value < min ? min : value > max ? max : value;
    }

    /**
     * Raw {@code getLightStatus(type)} read, or {@link BydVehicleData#UNAVAILABLE} when the
     * device did not answer. Returning UNAVAILABLE rather than 0 is deliberate: 0 is the SDK's
     * {@code LIGHT_STATE_OFF}, so a miss reported as 0 is a FABRICATED "that lamp is off" —
     * which the callers then publish as a real state transition. Callers must check.
     */
    private int getLightStatus(int position) {
        Object val = BydDeviceHelper.callGetter(lightDevice, "getLightStatus", position);
        if (!(val instanceof Number)) return BydVehicleData.UNAVAILABLE;
        int raw = ((Number) val).intValue();
        return (raw == 0 || raw == 1) ? raw : BydVehicleData.UNAVAILABLE;
    }

    private void collectAdas(BydVehicleData.Builder b) {
        if (adasDevice == null) return;
        try {
            // Read the SLW state id first (raw 2 = on); if this trim doesn't expose it,
            // fall back to the reference-confirmed ISLA status id (raw 1 = on). Each id
            // has its OWN on-value convention, so we compare against the right one.
            // A bare `>= 0` admitted the 65535 not-available rail, which is neither "on" value and so
            // published a confident "speed-limit warning OFF" from an unset register — and
            // speedLimitWarning is a plain boolean with no unavailable state to fall back to.
            //
            // SLW is 1=off / 2=on, matching the event handler's `iVal > 0 && iVal < 3`.
            int slw = BydDeviceHelper.callGetSingle(adasDevice, BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE);
            if (slw == 1 || slw == 2) {
                b.speedLimitWarning(slw == 2);
            } else {
                // ISLA's encoding is UNSETTLED and deliberately left as-is: this read treats 1 as
                // "on", while setSpeedLimitWarning writes 1=off/2=on to the same axis. Only the rail
                // filter is added here (0..2 keeps every value the old `>= 0` accepted as
                // meaningful) — changing the 1/2 interpretation on a guess would silently invert a
                // working trim. Settle it on-device by reading this id after toggling in the OEM UI.
                int isla = BydDeviceHelper.callGetSingle(adasDevice, BydFeatureIds.ADAS_ISLA_SWITCH_STATUS);
                if (isla >= 0 && isla <= 2) b.speedLimitWarning(isla == 1);
            }
        } catch (Exception e) {
            logger.debug("collectAdas error: " + e.getMessage());
        }
    }

    private void collectSettings(BydVehicleData.Builder b) {
        if (settingDevice == null) return;
        try {
            int[] seatHeat = new int[2];
            int[] seatCool = new int[2];
            boolean completeSeatClimateRead = true;
            // SDK returns 1=off, 2=low, 3=high — normalize to 0/1/2 for the wire format.
            // On unsupported firmwares the display remains backward-compatible
            // at zero, but the composite cloud fallback is never marked fresh.
            for (int i = 0; i < 2; i++) {
                Object heat = BydDeviceHelper.callGetter(settingDevice, "getSeatHeatingState", i + 1);
                if (heat instanceof Number) {
                    int v = ((Number) heat).intValue() - 1;
                    if (v >= 0 && v <= 2) seatHeat[i] = v;
                    else completeSeatClimateRead = false;
                } else completeSeatClimateRead = false;
                Object cool = BydDeviceHelper.callGetter(settingDevice, "getSeatVentilatingState", i + 1);
                if (cool instanceof Number) {
                    int v = ((Number) cool).intValue() - 1;
                    if (v >= 0 && v <= 2) seatCool[i] = v;
                    else completeSeatClimateRead = false;
                } else completeSeatClimateRead = false;
            }
            b.seatHeat(seatHeat).seatCool(seatCool);
            b.seatClimateAtMs(completeSeatClimateRead ? System.currentTimeMillis() : 0L);
            int wheelHeat = getSteeringWheelHeatingState();
            if (wheelHeat != BydVehicleData.UNAVAILABLE) b.steeringWheelHeat(wheelHeat);
            int childPresenceDetection = BydDeviceHelper.callGetSingle(settingDevice, BydFeatureIds.SETTING_CPD_SWITCH_STATUS);
            // Domain 1=on, 2=off, 3=delay — the SAME range the event handler for this id enforces.
            // A bare `>= 0` also admitted 0 (unpopulated) and 65535 (the not-available rail), and
            // the API then reports "detection disabled" for a car that never answered.
            if (childPresenceDetection >= 1 && childPresenceDetection <= 3) {
                b.childPresenceDetection(childPresenceDetection);
            }
            // Interior ambient colour. The "all area" query does not report reliably;
            // read the FRONT (area 1) colour as the representative value. 1-based index.
            Object ambient = BydDeviceHelper.callGetter(settingDevice, "getIALColor", 1);
            if (ambient instanceof Number) {
                int c = ((Number) ambient).intValue();
                if (c >= 1 && c <= 31) b.ambientColour(c);
            }
            // NOTE: the ambient MAIN SWITCH readback lives in collectLight, not here — it uses
            // lightDevice + the carsettings provider, and this method early-returns when the
            // SETTING device is absent, which would have left it permanently unpublished on
            // such a trim.
        } catch (Exception e) {
            logger.debug("collectSettings error: " + e.getMessage());
        }
    }

    /** Read the SOC target without polling the rest of the display-only settings. */
    private void collectSocTarget(BydVehicleData.Builder b) {
        if (settingDevice == null) return;
        int target = readSocTarget();
        if (target >= SOC_TARGET_MIN && target <= SOC_TARGET_MAX) {
            b.socTargetPercent(target);
        }
    }

    private void collectPower(BydVehicleData.Builder b) {
        if (powerDevice == null) return;
        try {
            // BYDAutoPowerDevice is a singleton that may have been initialized by another daemon
            // with a null/stale context. Force-update the internal context before calling methods.
            ensureDeviceContext(powerDevice);
            
            Object mcu = BydDeviceHelper.callGetter(powerDevice, "getMcuStatus");
            if (mcu instanceof Number) b.mcuStatus(((Number) mcu).intValue());
            // NOTE: getBatteryRemainPowerEV() intentionally NOT called here.
            // On PHEVs (Sealion 6 DM-i), the PowerDevice EV subsystem returns stale kWh
            // values when the ICE is running. We rely on Statistic/Bodywork paths for
            // remaining kWh on both BEVs and PHEVs.
        } catch (Exception e) {
            logger.debug("collectPower error: " + e.getMessage());
        }
    }
    
    /**
     * Force-update a BYD device singleton's internal context field.
     * BYD singletons store context from the first getInstance() call.
     * If another daemon initialized it first with a null/stale context, methods NPE.
     */
    /**
     * PHEV detection. getEnergyType is unreliable — observed returning 1 on
     * both BEV and PHEV firmwares, so we cannot trust it as the discriminator.
     * Primary signal: live fuel HAL values. If both getFuelPercentageValue
     * and getFuelDrivingRangeValue return BMS-unavailable sentinels, the
     * vehicle has no fuel system → BEV. Otherwise (real fuel readings, OR
     * we haven't been able to probe yet) treat as PHEV/HEV.
     *
     * Cached after first successful probe to avoid hammering reflection.
     */
    static final int DRIVETRAIN_UNKNOWN = 0;
    static final int DRIVETRAIN_BEV = 1;
    static final int DRIVETRAIN_PHEV = 2;

    private volatile int cachedDrivetrain = DRIVETRAIN_UNKNOWN;
    /**
     * Conservative verdict used only for ambiguous raw charging-power signatures. PHEV evidence
     * establishes immediately; BEV needs two sentinel-only probes separated by the cache window.
     */
    private volatile int establishedDrivetrain = DRIVETRAIN_UNKNOWN;
    private volatile long lastDrivetrainProbeMs = 0;
    private static final long DRIVETRAIN_REPROBE_MS = 60_000;

    private boolean isPhev(BydVehicleData.Builder b) {
        return computeIsPhev();
    }

    private boolean isPhev(BydVehicleData snapshot) {
        return computeIsPhev();
    }

    /**
     * Public drivetrain accessor for callers outside this class (TripDetector,
     * TripAnalyticsManager, API handlers). Reuses the cached probe with the
     * same {@link #DRIVETRAIN_REPROBE_MS} TTL so a hot path call doesn't hit
     * reflection. Returns true for PHEV/HEV, false for BEV/unknown.
     */
    public boolean isPhevPublic() {
        return computeIsPhev();
    }

    /** Accurate capability name for new callers; HEV and PHEV are intentionally one bucket. */
    public boolean isFuelCapableHybridPublic() {
        return computeIsPhev();
    }

    private boolean computeIsPhev() {
        // The cache check and the establishment write are one transaction. Without this lock, two
        // startup callers can both pass the UNKNOWN check, perform the same sentinel-only probe,
        // and let the second caller mistake the first caller's provisional BEV write for an
        // independent confirmation. Keep this off the collector monitor: engine callbacks can
        // probe while holding chargingEdgePublishLock, whereas collectAll takes the collector
        // monitor before that lock.
        synchronized (drivetrainProbeLock) {
            return computeIsPhevLocked();
        }
    }

    private boolean computeIsPhevLocked() {
        long now = System.currentTimeMillis();
        if (cachedDrivetrain != DRIVETRAIN_UNKNOWN
                && (now - lastDrivetrainProbeMs) < DRIVETRAIN_REPROBE_MS) {
            if (cachedDrivetrain == DRIVETRAIN_PHEV) {
                establishedDrivetrain = DRIVETRAIN_PHEV;
            }
            return cachedDrivetrain == DRIVETRAIN_PHEV;
        }

        // ── Pre-probe: capacity-based PHEV gate ────────────────────────────
        // If the daemon has already locked in a known small (<30 kWh) nominal
        // pack — typically because the user picked a Sealion 6 / Song / Tang
        // DM-i in the model selector — that signal is far stronger than the
        // live fuel HAL probes. The fuel HAL on these PHEVs goes through a
        // warm-up period where getFuelPercentageValue / getFuelDrivingRangeValue
        // can BOTH return BMS sentinels (255/2046/etc), which the
        // sentinel-AND-sentinel branch below would incorrectly latch as BEV
        // for 60s. That regression dropped fuel-percent display on PHEVs in
        // v17. Restoring the v12-era capacity-first behaviour: small known
        // nominal → PHEV verdict, full TTL.
        //
        // Inverse risk (BEV with <30 kWh nominal) is ~zero — the smallest BYD
        // BEV is the Atto 3 at 49.9 kWh. Capacity sub-30 kWh uniquely names a
        // PHEV pack across the catalog.
        double knownNominal = 0;
        try {
            com.overdrive.app.abrp.SohEstimator sohEst =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (sohEst != null) knownNominal = sohEst.getNominalCapacityKwh();
        } catch (Exception ignored) {}
        if (knownNominal > 0 && knownNominal < 30.0) {
            cachedDrivetrain = DRIVETRAIN_PHEV;
            establishedDrivetrain = DRIVETRAIN_PHEV;
            lastDrivetrainProbeMs = now;
            logger.info("computeIsPhev → PHEV (capacity-gate, nominal="
                + String.format("%.1f", knownNominal) + " kWh)");
            return true;
        }

        boolean fuelPctSentinel = false;
        boolean fuelRangeSentinel = false;
        boolean fuelPctReal = false;
        boolean fuelRangeReal = false;
        int fuelPctRaw = Integer.MIN_VALUE;
        int fuelRangeRaw = Integer.MIN_VALUE;
        if (statisticDevice != null) {
            try {
                Object fp = BydDeviceHelper.callGetter(statisticDevice, "getFuelPercentageValue");
                if (fp instanceof Number) {
                    int v = ((Number) fp).intValue();
                    fuelPctRaw = v;
                    if (isBevFuelSentinel(v)) fuelPctSentinel = true;
                    // 0 is intentionally NOT counted as "real" — a BEV that
                    // happens to return 0 instead of a sentinel would falsely
                    // classify as PHEV. PHEVs with truly empty tanks will be
                    // caught by fuelRangeReal once driven, or by the capacity
                    // fallback in the meantime.
                    else if (v > 0 && v <= 100) fuelPctReal = true;
                }
            } catch (Exception ignored) {}
            try {
                Object fr = BydDeviceHelper.callGetter(statisticDevice, "getFuelDrivingRangeValue");
                if (fr instanceof Number) {
                    int v = ((Number) fr).intValue();
                    fuelRangeRaw = v;
                    if (isBevFuelSentinel(v)) fuelRangeSentinel = true;
                    else if (v > 0 && v < 1500) fuelRangeReal = true;
                }
            } catch (Exception ignored) {}
        }
        // PHEV: at least one fuel signal returns a real, non-zero, non-sentinel
        // value AND the other is either real or at a sentinel (i.e. NOT a real
        // value claiming the opposite — that would indicate firmware lying).
        // BEV: both signals at sentinel.
        // Otherwise: defer to capacity heuristic, don't cache.
        if (fuelPctReal && fuelRangeReal) {
            cachedDrivetrain = DRIVETRAIN_PHEV;
            establishedDrivetrain = DRIVETRAIN_PHEV;
            lastDrivetrainProbeMs = now;
            logger.info("computeIsPhev → PHEV (fuelPct=" + fuelPctRaw
                + ", fuelRange=" + fuelRangeRaw + ")");
            return true;
        }
        if (fuelPctSentinel && fuelRangeSentinel) {
            boolean repeatedBevProbe = cachedDrivetrain == DRIVETRAIN_BEV
                    && lastDrivetrainProbeMs > 0L;
            cachedDrivetrain = DRIVETRAIN_BEV;
            if (establishedDrivetrain != DRIVETRAIN_PHEV && repeatedBevProbe) {
                establishedDrivetrain = DRIVETRAIN_BEV;
            }
            lastDrivetrainProbeMs = now;
            logger.info("computeIsPhev → BEV (both fuel signals at sentinel: pct="
                + fuelPctRaw + ", range=" + fuelRangeRaw + "; raw-power verdict="
                + (establishedDrivetrain == DRIVETRAIN_BEV
                    ? "established" : "provisional") + ")");
            return false;
        }
        // One real + one sentinel is the "PHEV with empty tank or 0 km" case.
        // Cache as PHEV with a SHORTER TTL so a transient HAL miss self-heals
        // quickly. Without the cache, every isPhev() call re-runs both
        // reflection probes — onFuelPercentageChanged fires at HAL rate.
        if ((fuelPctReal && fuelRangeSentinel) || (fuelRangeReal && fuelPctSentinel)) {
            cachedDrivetrain = DRIVETRAIN_PHEV;
            establishedDrivetrain = DRIVETRAIN_PHEV;
            // 5s TTL via lastDrivetrainProbeMs offset trick: pretend the probe
            // happened (DRIVETRAIN_REPROBE_MS - 5000) ms ago, so the next call
            // in >5s will re-probe.
            lastDrivetrainProbeMs = now - (DRIVETRAIN_REPROBE_MS - 5_000);
            logger.info("computeIsPhev → PHEV (mixed signals: pct=" + fuelPctRaw
                + ", range=" + fuelRangeRaw + ") — short TTL re-probe");
            return true;
        }
        // Capacity gate already ran above with knownNominal < 30. Fall through
        // here only when nominal isn't known yet OR is >=30 (BEV-sized) — both
        // resolve to BEV verdict, deliberately uncached so the next probe can
        // re-evaluate once SohEstimator picks up a real nominal.
        logger.info("computeIsPhev → BEV (no fuel signals, no small nominal: "
            + "pct=" + fuelPctRaw + ", range=" + fuelRangeRaw
            + ", nominal=" + String.format("%.1f", knownNominal) + " kWh) — uncached");
        return false;
    }

    private static boolean isBevFuelSentinel(int v) {
        return v == 255 || v == 254 || v == 511 || v == 1023
            || v == 2046 || v == 2047 || v == 4095
            || v == 65534 || v == 65535;
    }

    /**
     * Upper bound for a believable petrol range, in km. The SDK's field can carry
     * up to 4095, but no BYD PHEV tank goes remotely that far — a value in the
     * upper hundreds is already a sensor rail, not a range. 1200 km leaves ample
     * headroom over the ~1000 km best case for a full tank plus a full battery.
     */
    private static final int MAX_PLAUSIBLE_FUEL_RANGE_KM = 1200;

    /**
     * True when a raw {@code getFuelDrivingRangeValue()} reading is a real range
     * rather than a sentinel/rail. Zero is accepted — an empty tank genuinely has
     * no petrol range, and reporting that is more honest than blanking the field.
     */
    private static boolean isPlausibleFuelRangeKm(int raw) {
        // Bound only. Do NOT consult isBevFuelSentinel here: that set is for the
        // 0..100 PERCENT domain, and it contains 254/255/511/1023 — all ordinary
        // petrol ranges in km for a DM-i tank. Rejecting them dropped real
        // readings. The rails that matter for a range field (2046/2047/4095 and
        // the 16-bit ones) are already excluded by the 1200 km bound.
        return raw >= 0 && raw <= MAX_PLAUSIBLE_FUEL_RANGE_KM;
    }

    private void ensureDeviceContext(Object device) {
        if (device == null || context == null) return;
        try {
            // Walk up to AbsBYDAutoDevice and set mContext
            Class<?> cls = device.getClass();
            while (cls != null && cls != Object.class) {
                try {
                    java.lang.reflect.Field contextField = cls.getDeclaredField("mContext");
                    contextField.setAccessible(true);
                    Object currentCtx = contextField.get(device);
                    if (currentCtx == null) {
                        contextField.set(device, context);
                        logger.info("Fixed null context on " + device.getClass().getSimpleName());
                    }
                    return;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
        } catch (Exception e) {
            logger.debug("ensureDeviceContext failed: " + e.getMessage());
        }
    }

    // Seatbelt raw values that are NOT a real belt state — SDK failure/unavailable
    // codes. Matches the OEM firmware's FAILURE_STATUS_CODES set exactly. These are RAW
    // 32-bit negatives, so sanitizeSeatbelt tests them BEFORE the &0xFFFF mask (see the
    // ORDER MATTERS note there); a raw read equal to any of these — or masked to 2
    // (INVALID) — is dropped to "unknown" rather than mis-reported as buckled/unbuckled.
    private static final java.util.Set<Integer> SEATBELT_FAILURE_CODES = new java.util.HashSet<>(
            java.util.Arrays.asList(-2147482647, -2147482648, -2147482645, -2147482646,
                    -10011, Integer.MIN_VALUE, -1, -10013));

    private static final int SEATBELT_AREA_FRONT_PASSENGER = 2;
    private static final int BODYWORK_AREA_FRONT_PASSENGER = 2;
    private final PassengerSeatbeltTracker passengerSeatbeltTracker =
            new PassengerSeatbeltTracker();

    // Debounce thresholds for two safety-adjacent, on-device-unverified inferences in
    // readSeatbeltPair: (1) the driver INVALID(2)→unbuckled best-effort mapping and (2) the
    // passenger confirmed-empty occupancy gate. Both require N consecutive matching reads
    // before acting, so a SINGLE transient/spurious read can't publish (or suppress) a belt
    // edge. N=2 is the minimum that still surfaces a genuinely sustained state within one extra
    // poll tick (≤1s on the fast poll).
    private static final int DRIVER_INVALID_UNBUCKLE_STREAK = 2;
    private static final int PASSENGER_EMPTY_STREAK = 2;

    // Consecutive-read counters backing the two debounces above. They are touched by BOTH the 5s
    // BydDataPoll thread (collectSafetyBelt) AND the 500ms SeatbeltEvent poll thread
    // (readSeatbeltsNow) via the shared readSeatbeltPair; both read the SAME sensor each tick, so
    // a benign cross-thread read-modify-write on these plain volatile ints can at most advance or
    // reset a streak by one tick (delaying an edge by one poll), never mis-report a state.
    private volatile int driverInvalidStreak = 0;
    private volatile int passengerEmptyStreak = 0;

    // Very-short-lived memo of the last readSeatbeltPair result, so the several callers that can
    // run within one poll tick share ONE set of HAL reads.
    //
    // Two reasons, the second being the important one:
    //  1. Cost — with both a seatbelt rule and a driver-occupancy rule enabled, the 500ms poller
    //     called readSeatbeltPair twice per tick (pollSeatbelts, then again inside
    //     readDriverOccupancyNow) = 3 wasted HAL reads/tick, paid 24/7 because this poller is
    //     deliberately not ACC-gated (parked entry/exit is its whole purpose).
    //  2. Correctness — the two de-glitch streaks above advance once per CALL, so that second
    //     call made a "2 consecutive reads" debounce complete in ONE tick. The debounce interval
    //     silently depended on which rules the user had enabled. Sharing one read per tick makes
    //     it exactly N ticks in every configuration.
    //
    // TTL is far below the 500ms fast-poll period, so a fresh tick always re-reads; it only
    // collapses calls made back-to-back within the same tick.
    private static final long SEATBELT_PAIR_MEMO_TTL_MS = 100;
    private final Object seatbeltPairLock = new Object();
    /** Sentinel stored in the memo to mean "the read returned null", so a memoized null is not
     *  mistaken for "nothing memoized yet". Never handed to a caller. */
    private static final int[] EMPTY_SEATBELT_PAIR = new int[0];
    private long seatbeltPairMemoAtMs = 0;
    private int[] seatbeltPairMemo = null;
    /** Bumped by every invalidation so an in-flight read can detect that its sample was
     *  superseded and decline to store it. All access under {@link #seatbeltPairLock}. */
    private long seatbeltPairGeneration = 0;

    // Throttle (30s) for the diagnostic raw-seatbelt log emitted by readSeatbeltPair — so a
    // future on-device log pins the EXACT raw code the driver channel returns on unbuckle,
    // which the best-effort driver mapping is inferring. Written from both the 5s poll and
    // the 500ms fast poll; a benign cross-thread race only affects log cadence, not data.
    private volatile long lastSeatbeltRawLogMs = 0;

    /** Cached BYDAutoInstrumentDevice.getSafetyBeltStatus(int) — the dedicated named getter
     *  the WORKING telemetry-recording overlay uses (TelemetryDataCollector.probeSeatbeltApis
     *  / getSafetyBeltStatusMethod). Resolved once, off the same instrumentDevice. Absent →
     *  the feature-id fallback below is used. */
    private volatile Method seatbeltStatusMethod;
    private volatile boolean seatbeltStatusMethodResolved = false;

    private Method resolveSeatbeltStatusMethod() {
        if (seatbeltStatusMethodResolved) return seatbeltStatusMethod;
        Method m = null;
        try {
            if (instrumentDevice != null) {
                m = instrumentDevice.getClass().getMethod("getSafetyBeltStatus", int.class);
            }
        } catch (Throwable ignored) { /* absent on this trim → fall back to feature-id */ }
        seatbeltStatusMethod = m;
        seatbeltStatusMethodResolved = true;
        if (m != null) logger.info("collectSafetyBelt: using BYDAutoInstrumentDevice.getSafetyBeltStatus(int) (telemetry-overlay path)");
        return m;
    }

    /**
     * Read one seat's raw seatbelt state by AREA (1 = driver / main, 2 = passenger / deputy).
     *
     * <p>PRIMARY: the dedicated {@code BYDAutoInstrumentDevice.getSafetyBeltStatus(int area)}
     * method — the exact call the telemetry-recording overlay uses and which is confirmed to
     * return LIVE per-seat state on this firmware. The prior automation read used the generic
     * {@code get(int[],Class)} feature-id channel (INSTRUMENT_DD_*_SAFETYBELT_STATE) instead,
     * which does NOT return a live value here, so the seatbelt state never transitioned and an
     * "On Change Seatbelt" automation never fired (while the test/play button, which bypasses
     * the trigger, worked). FALLBACK: the feature-id read, for a trim where the named method is
     * absent. Returns {@link Integer#MIN_VALUE} on any miss (→ sanitizeSeatbelt → UNAVAILABLE). */
    private int readInstrumentSeatbelt(int area, int featureId) {
        Method m = resolveSeatbeltStatusMethod();
        if (m != null) {
            try {
                Object r = m.invoke(instrumentDevice, area);
                if (r instanceof Number) return ((Number) r).intValue();
            } catch (Throwable t) {
                logger.debug("getSafetyBeltStatus(area=" + area + ") failed: " + t.getMessage());
            }
        }
        // Fallback: generic feature-id read.
        return BydDeviceHelper.getIntValue(
                BydDeviceHelper.callGet(instrumentDevice, featureId, Integer.class));
    }

    /**
     * Read per-seat seatbelt buckled/unbuckled state.
     *
     * <p>Reads the belt state from the INSTRUMENT device via the dedicated
     * {@code getSafetyBeltStatus(int area)} method (area 1 = driver/main, 2 = passenger/
     * deputy) — the SAME call the telemetry-recording overlay uses (and which is confirmed
     * to return LIVE per-seat state on this firmware), with the generic feature-id read
     * (INSTRUMENT_DD_*_SAFETYBELT_STATE) as a fallback for trims lacking the method. An
     * earlier revision used ONLY the feature-id channel; that did not return a live value
     * here, so the seatbelt state never transitioned and an "On Change Seatbelt" automation
     * never fired (while the test/play button, which bypasses the trigger, still worked).
     * There are only TWO real seatbelt signals on this platform (driver + front passenger);
     * no rear-seat signals exist.
     *
     * <p>Each raw read is sanitized (mask low 16 bits, drop failure codes + INVALID(2)) to
     * a 2-slot array: index 0 = driver, index 1 = passenger, each {@code 0 = unbuckled},
     * {@code 1 = buckled}, or {@link BydVehicleData#UNAVAILABLE} when unknown/dropped — so a
     * consumer can tell "unbuckled" from "no reading".
     */
    private void collectSafetyBelt(BydVehicleData.Builder b) {
        // NOTE: the instrument-device guard is per-BLOCK, not a method-level early return.
        // Occupancy comes off the SAFETY-BELT device, so an instrument-less trim must still
        // get its occupancy read (an early return here left it permanently unpublished).
        if (instrumentDevice != null) {
            try {
                // Single shared read (see readSeatbeltPair): dedicated getSafetyBeltStatus(area)
                // with driver best-effort mapping + passenger occupancy gate + session tracker.
                // Only publish when at least one seat gave a real reading, so a trim that doesn't
                // expose these feature-ids leaves seatbeltStatus null (unseeded) rather than a pair
                // of UNAVAILABLE sentinels.
                int[] belts = readSeatbeltPair();
                if (belts != null) {
                    b.seatbeltStatus(belts);
                }
            } catch (Exception e) {
                logger.debug("collectSafetyBelt error: " + e.getMessage());
            }
        }
        // Seat occupancy (someone present) — separate from belt state. Shared read with the
        // fast poll (see readOccupantsNow); null when no seat gave a real reading, so the
        // snapshot field stays unseeded rather than holding a pair of sentinels.
        int[] occupants = readOccupantsNow();
        if (occupants != null) {
            b.passengerDetection(occupants);
        }
    }

    /**
     * On-demand LIVE per-seat seatbelt read for the fast automation poll ({@link
     * com.overdrive.app.automation.condition.SeatbeltEvent}) — the belt equivalent of
     * {@link #readTurnNow()}. Reads both seats via the same dedicated {@code
     * getSafetyBeltStatus(area)} method + sanitize + passenger session tracking as {@link
     * #collectSafetyBelt}, so the fast path and the 5s poll agree exactly. Returns a
     * 2-slot array {index 0 = driver, 1 = passenger}, each {@code 0=unbuckled / 1=buckled /
     * }{@link BydVehicleData#UNAVAILABLE}, or {@code null} when the instrument device is
     * unavailable (caller leaves the events untouched — no false reading).
     *
     * <p>Called on the SeatbeltEvent poll thread (not the BydDataPoll thread). Passenger state is
     * resolved by the synchronized {@link PassengerSeatbeltTracker}: an empty-seat {@code 1} is
     * withheld until a {@code 0} is observed with the passenger door closed, and opening that door
     * ends the session before the getter can rebound to a false buckle.
     */
    public int[] readSeatbeltsNow() {
        return readSeatbeltPair();
    }

    /**
     * THE single seatbelt read — shared by the 5s telemetry poll ({@link #collectSafetyBelt})
     * and the 500ms fast poll ({@link #readSeatbeltsNow} → {@code SeatbeltEvent}) so both paths
     * publish identical edges. Reads each seat via the dedicated {@code getSafetyBeltStatus(area)}
     * (area 1 = driver/main, 2 = passenger/deputy — the SAME call the working telemetry-recording
     * overlay uses and which returns LIVE per-seat state here), then applies three per-seat rules:
     *
     * <ol>
     *   <li><b>Driver best-effort mapping</b> ({@link #sanitizeSeatbelt(int, boolean)} with
     *       {@code driverBestEffort=true}): the driver's unbuckle → cooling-OFF (the ELSE) never
     *       fired because the strict sanitize dropped the driver's off-code to UNAVAILABLE, so no
     *       "off" edge published. Treating driver INVALID(2) as unbuckled surfaces that edge.</li>
     *   <li><b>Passenger occupancy gate</b>: the front-passenger belt sensor floats 0↔1 on an
     *       EMPTY seat, which strobed a passenger-belt automation on/off. When the seat is
     *       confirmed empty ({@code getPassengerStatus(1) == NOBODY} — area 1 is the front
     *       passenger, see {@link #OCCUPANT_AREA_FRONT_PASSENGER}; 2 would be second-row LEFT)
     *       we force the passenger belt
     *       to a stable UNBUCKLED(0) — no one is there to buckle, so it can't flap. Only a real,
     *       occupied-seat transition drives the automation. When occupancy is unknown we don't
     *       gate (fail-open), so a trim without the occupancy getter behaves as before.</li>
     *   <li><b>Passenger de-glitch</b> (mirrors the OEM firmware's seatbeltEverUnlatched): a
     *       never-unlatched passenger belt idles at "buckled" from boot, so withhold its 1 until a
     *       genuine 0 establishes the current closed-door passenger session. Opening the passenger
     *       door ends that session because the empty-seat getter rebounds to 1 during egress.
     *       A typed SDK callback is an observed edge and temporarily overrides a lagging getter.</li>
     * </ol>
     *
     * Returns a 2-slot array {index 0 = driver, 1 = passenger}, each {@code 0=unbuckled /
     * 1=buckled /} {@link BydVehicleData#UNAVAILABLE}, or {@code null} when the instrument device
     * is unavailable or neither seat gave a real reading (caller leaves the events untouched — no
     * false edge). The passenger tracker synchronizes its phase/callback state; the small debounce
     * counters below remain deliberately tolerant of a one-poll cross-thread skew.
     */
    public int[] readSeatbeltPair() {
        // The lock covers ONLY the memo check and the store — never the HAL read. Three threads
        // reach here (the 5s/90s BydDataPoll, the 500ms SeatbeltEvent poll, and the belt-status
        // binder callback); holding a monitor across three reflective binder calls would let one
        // wedged HAL read stall the fast safety poll, and risks lock inversion against the SDK's
        // own monitors on the callback thread. Two threads racing the same expiry can therefore
        // both read and each advance a debounce streak once — the pre-existing benign race these
        // counters are already documented to tolerate (a one-tick skew, never a wrong state).
        // What the memo removes is the SAME-THREAD double read (pollSeatbelts then
        // pollDriverOccupant within one tick), which is the actual waste and the actual
        // double-advance. The store is guarded so the loser of such a race can never overwrite
        // the newer sample.
        long startedAtMs;
        long startedGen;
        synchronized (seatbeltPairLock) {
            int[] memo = memoIfFresh(System.currentTimeMillis());
            // Copy out: callers must not be able to mutate the shared memo. null is a real
            // result ("no reading") and is memoized as such.
            if (memo != null) return memo.length == 0 ? null : memo.clone();
            // Stamp taken BEFORE the read so the store below can tell whose sample is newer.
            // Stamping after would let a slow reader overwrite a newer sample with its older
            // one, serving stale belt state for a further TTL.
            startedAtMs = System.currentTimeMillis();
            startedGen = seatbeltPairGeneration;
        }
        int[] fresh = readSeatbeltPairUncached();
        synchronized (seatbeltPairLock) {
            // Store only if (a) no invalidation happened while we were reading — otherwise a read
            // that began before an edge-driven invalidate would resurrect its pre-edge value —
            // and (b) our sample started no earlier than the one already held.
            if (startedGen == seatbeltPairGeneration && startedAtMs >= seatbeltPairMemoAtMs) {
                // EMPTY_SEATBELT_PAIR encodes a memoized null so "no entry" and "null was read"
                // stay distinguishable without a second flag.
                seatbeltPairMemo = (fresh == null) ? EMPTY_SEATBELT_PAIR : fresh;
                seatbeltPairMemoAtMs = startedAtMs;
            }
        }
        return fresh == null ? null : fresh.clone();
    }

    /** The memoized pair when still inside the TTL, else null. Caller must hold
     *  {@link #seatbeltPairLock}. A zero-length array means "null was memoized". */
    private int[] memoIfFresh(long now) {
        if (seatbeltPairMemoAtMs == 0 || seatbeltPairMemo == null) return null;
        // now < stamp guards a backwards clock jump (NTP/RTC set), which would otherwise freeze
        // the memo until wall-clock caught up.
        if (now < seatbeltPairMemoAtMs) return null;
        return (now - seatbeltPairMemoAtMs < SEATBELT_PAIR_MEMO_TTL_MS) ? seatbeltPairMemo : null;
    }

    /**
     * Drop the seatbelt memo so the next {@link #readSeatbeltPair} does a real HAL read.
     *
     * <p>For the belt-status binder callback, whose entire purpose is to beat the 500ms poll: an
     * edge landing just after a poll read would otherwise be served the pre-edge memo and wait
     * for the next tick, losing the instant path.
     */
    public void invalidateSeatbeltPairMemo() {
        synchronized (seatbeltPairLock) {
            seatbeltPairMemoAtMs = 0;
            seatbeltPairMemo = null;
            // Bump so an in-flight read that started before this cannot store its pre-edge value.
            seatbeltPairGeneration++;
        }
    }

    /** Accept only the documented passenger area and 0=unbuckled / 1=buckled states. */
    static int normalizePassengerSeatbeltCallback(Object areaValue, Object stateValue) {
        if (!(areaValue instanceof Number) || !(stateValue instanceof Number)) {
            return BydVehicleData.UNAVAILABLE;
        }
        double area = ((Number) areaValue).doubleValue();
        double state = ((Number) stateValue).doubleValue();
        if (area != SEATBELT_AREA_FRONT_PASSENGER || (state != 0.0d && state != 1.0d)) {
            return BydVehicleData.UNAVAILABLE;
        }
        return (int) state;
    }

    /**
     * Opening the passenger door starts a new occupancy lifecycle. This trim has no working
     * occupancy getter and its belt getter rebounds from 0 to a false 1 while an unbuckled
     * passenger exits, so a previous unlatch cannot make that idle 1 trustworthy forever.
     */
    private void notePassengerDoorStateForSeatbelt(int area, int state) {
        if (area != BODYWORK_AREA_FRONT_PASSENGER
                || (state != BodyworkConstants.STATE_OPEN
                    && state != BodyworkConstants.STATE_CLOSED)) {
            return;
        }
        boolean open = state == BodyworkConstants.STATE_OPEN;
        if (!passengerSeatbeltTracker.onPassengerDoorState(open)) return;

        if (open) {
            passengerEmptyStreak = 0;
            logger.info("Passenger door opened: re-arming seatbelt detection to suppress "
                    + "the empty-seat buckled rebound");
        }
        invalidateSeatbeltPairMemo();
    }

    /**
     * Read only the physical front-passenger door for the passenger-belt session tracker.
     *
     * <p>The regular door fallback is gated by door-automation references. A passenger-belt rule
     * still needs this lifecycle edge, so its 500ms fast poll calls this method before reading the
     * belt. This deliberately does not fan the sample out to door notifications or door
     * automations; it exists only to disambiguate the empty-seat belt getter.
     */
    public void pollPassengerDoorStateForSeatbeltNow() {
        if (bodyworkDevice == null) return;
        try {
            Object value = BydDeviceHelper.callMethod(
                    bodyworkDevice, "getDoorState", BODYWORK_AREA_FRONT_PASSENGER);
            if (!(value instanceof Number)) return;
            int state = ((Number) value).intValue();
            notePassengerDoorStateForSeatbelt(BODYWORK_AREA_FRONT_PASSENGER, state);
        } catch (Throwable t) {
            logger.debug("passenger door state read for seatbelt failed: " + t.getMessage());
        }
    }

    private void resetPassengerSeatbeltState() {
        passengerSeatbeltTracker.reset();
        passengerEmptyStreak = 0;
        invalidateSeatbeltPairMemo();
    }

    /** The real HAL read behind {@link #readSeatbeltPair}. Call that, not this — it applies the
     *  per-tick memo that keeps the de-glitch streaks advancing once per tick. */
    private int[] readSeatbeltPairUncached() {
        if (instrumentDevice == null) return null;
        try {
            int driverRaw = readInstrumentSeatbelt(1, BydFeatureIds.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE);
            int passengerRaw = readInstrumentSeatbelt(2, BydFeatureIds.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE);
            // Driver best-effort INVALID(2)→UNBUCKLED mapping, DEBOUNCED. sanitizeSeatbelt with
            // driverBestEffort=true differs from the strict result ONLY in the INVALID(2) case
            // (best→0, strict→UNAVAILABLE), so driverBest != driverStrict precisely identifies an
            // INVALID driver read. Guard the "off" edge behind DRIVER_INVALID_UNBUCKLE_STREAK
            // consecutive INVALID reads: a SINGLE transient INVALID reports UNAVAILABLE (no edge)
            // rather than publishing a spurious unbuckle, while a SUSTAINED INVALID still surfaces
            // the unbuckle edge — preserving the author's premise that INVALID is the driver's
            // genuine off-code (returning UNAVAILABLE outright would reintroduce the original bug).
            int driverStrict = sanitizeSeatbelt(driverRaw, false);
            int driverBest = sanitizeSeatbelt(driverRaw, true);
            int driver;
            if (driverBest != driverStrict) {                          // driver INVALID(2)
                if (driverInvalidStreak < DRIVER_INVALID_UNBUCKLE_STREAK) driverInvalidStreak++;
                driver = (driverInvalidStreak >= DRIVER_INVALID_UNBUCKLE_STREAK)
                        ? driverBest : BydVehicleData.UNAVAILABLE;
            } else {
                driverInvalidStreak = 0;
                driver = driverStrict;
            }
            PassengerSeatbeltTracker.Reading passengerReading =
                    passengerSeatbeltTracker.resolveGetter(
                            sanitizeSeatbelt(passengerRaw, false));
            int passengerBelt = passengerReading.beltState;
            int passenger = passengerReading.automationState;

            // Passenger occupancy gate: force UNBUCKLED when the seat is empty so an unoccupied
            // seat's floating belt sensor cannot oscillate the automation. The physical occupancy
            // sensor remains authoritative. On trims where it is unavailable, the user-selected
            // heuristic treats an active reminder or an established buckled belt as occupied,
            // otherwise empty; its UI description makes the resulting false-positive risk
            // explicit.
            //
            // Debounce every empty source, including the heuristic, so one transient belt read
            // cannot suppress a real buckled-passenger reading.
            int passengerOccupant = frontPassengerOccupancy(
                    passengerBelt, passengerReading.buckledGetterTrusted);
            if (passengerOccupant == 0) {
                if (passengerEmptyStreak < PASSENGER_EMPTY_STREAK) passengerEmptyStreak++;
                if (passengerEmptyStreak >= PASSENGER_EMPTY_STREAK) {
                    passenger = 0;
                }
            } else {
                passengerEmptyStreak = 0;
            }

            // Diagnostic (throttled 30s): pin the EXACT raw driver/passenger codes on-device so
            // the best-effort driver mapping above can be replaced with an exact rule once seen.
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSeatbeltRawLogMs > 30_000) {
                lastSeatbeltRawLogMs = nowMs;
                logger.info("seatbelt raw: driver=" + driverRaw + " (masked=" + (driverRaw & 0xFFFF)
                        + " → " + driver + "), passenger=" + passengerRaw + " (masked="
                        + (passengerRaw & 0xFFFF) + ", source="
                        + (passengerReading.callbackAuthoritative ? "callback" : "getter")
                        + ", tracker=" + passengerSeatbeltTracker.diagnosticState() + " → "
                        + passenger + "), passengerOccupant="
                        + passengerOccupant + " (driverInvalidStreak=" + driverInvalidStreak
                        + ", passengerEmptyStreak=" + passengerEmptyStreak + ")");
            }

            if (driver == BydVehicleData.UNAVAILABLE && passenger == BydVehicleData.UNAVAILABLE) {
                return null; // nothing real this tick — don't publish a pair of sentinels
            }
            return new int[]{ driver, passenger };
        } catch (Throwable t) {
            logger.debug("readSeatbeltPair error: " + t.getMessage());
            return null;
        }
    }

    /**
     * Seat areas for {@code getPassengerStatus(area)} — SAFETY_BELT_PASSENGER_* in the SDK.
     * These are NOT the SAFETY_BELT_AREA_* values used by {@code getSafetyBeltStatus}: the belt
     * set is MAIN=1, DEPUTY=2, SECOND_ROW L/R/MID=3/4/5, while this set is DEPUTY=1,
     * SECOND_ROW L=2, R=3, MID=4 — and has NO main-seat entry. Passing a belt area here reads a
     * different seat: the old value of 2 asked for the second-row LEFT seat, not the front
     * passenger, so the front-passenger occupancy gate and the published occupant events were
     * both keyed to the wrong seat.
     *
     * <p>AUTHORITATIVE, do not "hedge" this: the bundled SDK javadoc
     * ({@code doc/constant-values.html}) states {@code SAFETY_BELT_PASSENGER_DEPUTY = 1} and
     * {@code SAFETY_BELT_PASSENGER_SECOND_ROW_SEAT_LEFT = 2}. The reference app agrees — its
     * {@code getOmsFeatureId} uses a PUBLIC-API convention (1=driver, 2=passenger) that
     * {@code mapPassengerArea} resolves to HAL index 1 for the passenger, and that same
     * {@code mapPassengerArea} is otherwise used as a BIT INDEX into {@code REMINDER_MASK},
     * NOT as a {@code getPassengerStatus} area. So there is no competing HAL numbering, and
     * probing area 2 as a "fallback" would read the SECOND-ROW LEFT seat — reintroducing
     * exactly the wrong-seat bug described above.
     */
    private static final int OCCUPANT_AREA_FRONT_PASSENGER = 1;   // SAFETY_BELT_PASSENGER_DEPUTY

    /**
     * Read one seat's occupancy via {@code getPassengerStatus(area)}: 1=SOMEBODY, 0=NOBODY,
     * else {@link BydVehicleData#UNAVAILABLE} (INVALID/unknown).
     *
     * <p>Only an exact 1 or 0 is accepted; every other code (INVALID=2, the 255/254/65535
     * "not available" sentinels, the negative HAL failure codes) reads UNAVAILABLE, so a
     * sentinel can never publish a spurious "occupied"/"empty" edge. No masking here, unlike
     * {@link #sanitizeSeatbelt(int)} — masking would turn Integer.MIN_VALUE into a firm 0.
     *
     * <p>Pass an {@code OCCUPANT_AREA_*} constant — NOT a {@code SAFETY_BELT_AREA_*} one.
     * getPassengerStatus and getSafetyBeltStatus take DIFFERENT area numberings (see
     * {@link #OCCUPANT_AREA_FRONT_PASSENGER}), which is easy to miss because both hang off the
     * same device.
     */
    private int occupantOf(int area) {
        Object s = BydDeviceHelper.callGetter(safetyBeltDevice, "getPassengerStatus", area);
        return normalizePassengerStatus(s);
    }

    /** Map the documented passenger-state domain to an automation-safe occupancy value. */
    static int normalizePassengerStatus(Object value) {
        if (!(value instanceof Number)) return BydVehicleData.UNAVAILABLE;
        double raw = ((Number) value).doubleValue();
        if (raw == 1.0d) return 1;
        if (raw == 0.0d) return 0;
        return BydVehicleData.UNAVAILABLE;
    }

    /**
     * Direct front-passenger occupancy sensor: 1 = someone, 0 = nobody,
     * {@link BydVehicleData#UNAVAILABLE} when it gives no real reading.
     *
     * <p>Reads ONLY {@code getPassengerStatus(}{@link #OCCUPANT_AREA_FRONT_PASSENGER}{@code )} —
     * see that constant for why probing any other area is wrong rather than a fallback.
     *
     * <p><b>No OMS fallback, deliberately.</b> The ADAS
     * {@code OMS_PASSENGER_DETECTION_RESULT} feature looked like a free extra tier, but
     * {@code callGetSingle} cannot distinguish "this HAL does not implement the pair" from a
     * genuine 0: an unrecognised {@code get(deviceType, featureId)} can answer 0, and the SDK's
     * own {@code STATUS_SUCCESS} is 0 (the same trap documented on the mirror-fold read, which
     * only LOGS such a 0 rather than acting on it). Acting on it would hand the seatbelt gate
     * below a confident, fabricated NOBODY on trims that previously published nothing — turning
     * "no data, belt drives directly" into "seat is empty, suppress the belt signal". A silent
     * wrong answer is strictly worse here than no answer.
     *
     * <p>Accepts ONLY an exact 0/1, so a HAL failure code (-1), INVALID(2) or a "not available"
     * sentinel can never publish a spurious occupied/empty edge — the property both the
     * automation events and the passenger seatbelt gate depend on.
     */
    private int directFrontPassengerOccupancy() {
        if (safetyBeltDevice == null) return BydVehicleData.UNAVAILABLE;
        int direct = occupantOf(OCCUPANT_AREA_FRONT_PASSENGER);
        noteOccupancySensorReading(direct);
        return direct;
    }

    // One-shot dead-sensor diagnostic for the front-passenger occupancy getter. On some trims
    // getPassengerStatus(1) returns Integer.MIN_VALUE on EVERY call (2026-08 field log: 274/274
    // reads invalid), which silently disables the occupant{seat:passenger} automation event AND
    // the passenger-belt occupancy gate — the user just sees "seat occupancy doesn't work" with
    // nothing in the log naming the cause. After a sustained run of invalid reads, say so once,
    // with the automation-facing consequence spelled out. A later real 0/1 resets the streak
    // (but not the once-only flag — one line per process is enough).
    private static final int OCCUPANCY_DEAD_SENSOR_STREAK = 60; // ≈30s at the 500ms fast poll
    private volatile int occupancyInvalidStreak = 0;
    private volatile boolean occupancyDeadSensorLogged = false;

    private void noteOccupancySensorReading(int direct) {
        if (direct == BydVehicleData.UNAVAILABLE) {
            if (occupancyInvalidStreak < OCCUPANCY_DEAD_SENSOR_STREAK) {
                occupancyInvalidStreak++;
            } else if (!occupancyDeadSensorLogged) {
                occupancyDeadSensorLogged = true;
                logger.warn("Front-passenger occupancy sensor returns no valid reading ("
                        + OCCUPANCY_DEAD_SENSOR_STREAK + " consecutive invalid reads) — this trim "
                        + "does not expose getPassengerStatus. 'Seat occupancy (passenger)' "
                        + "automations cannot fire, and passenger seatbelt automations only work "
                        + "after an unbuckled sample establishes a closed-door passenger session.");
            }
        } else {
            occupancyInvalidStreak = 0;
        }
    }

    /**
     * Resolve front-passenger occupancy. The dedicated sensor is authoritative; the belt/reminder
     * estimate is used only on trims that expose no direct 0/1 result.
     */
    private int frontPassengerOccupancy(int passengerBelt, boolean buckledGetterTrusted) {
        int direct = directFrontPassengerOccupancy();
        if (direct != BydVehicleData.UNAVAILABLE) return direct;
        return resolvePassengerOccupancy(
                direct,
                seatbeltReminderActive(REMINDER_BIT_PASSENGER),
                passengerBelt,
                passengerBelt == 0 || buckledGetterTrusted);
    }

    /** Read the passenger belt through the same session tracker used by automation publication. */
    private PassengerSeatbeltTracker.Reading readPassengerBeltForOccupancy() {
        int state = BydVehicleData.UNAVAILABLE;
        if (instrumentDevice != null) {
            int raw = readInstrumentSeatbelt(
                    2, BydFeatureIds.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE);
            state = sanitizeSeatbelt(raw, false);
        }
        return passengerSeatbeltTracker.resolveGetter(state);
    }

    /**
     * User-selected passenger estimate for trims lacking a real occupancy sensor.
     *
     * <p>An active reminder means an unbelted passenger is present. A buckled belt is also
     * treated as present only after the passenger session tracker has established that the
     * getter's buckled value is trustworthy. Before then the firmware can leave an empty seat at
     * the same value. When neither is true, a valid unbuckled belt reports empty. An unknown belt
     * remains unknown so a missing instrument signal cannot manufacture an empty edge.
     */
    static int resolvePassengerOccupancy(int direct, boolean reminderActive, int passengerBelt,
                                         boolean beltEstablished) {
        if (direct != BydVehicleData.UNAVAILABLE) return direct;
        if (reminderActive || (passengerBelt == 1 && beltEstablished)) return 1;
        if (passengerBelt == 0) return 0;
        return BydVehicleData.UNAVAILABLE;
    }

    private int frontPassengerOccupancy() {
        PassengerSeatbeltTracker.Reading reading = readPassengerBeltForOccupancy();
        return frontPassengerOccupancy(reading.beltState, reading.buckledGetterTrusted);
    }

    /**
     * Bit index into {@code SAFETYBELT_REMINDER_MASK} for the driver/main seat.
     *
     * <p>This is the ONLY route to a driver-seat signal. {@code getPassengerStatus}'s area set
     * has no main-seat entry (see {@link #OCCUPANT_AREA_FRONT_PASSENGER}), but the reminder mask
     * is BIT-indexed and does carry the driver — which is why the reference app feeds
     * {@code mapPassengerArea} into {@code (mask >> bitIndex) & 1} rather than into a
     * {@code getPassengerStatus} call. Do not confuse these numbers with either area set.
     */
    private static final int REMINDER_BIT_DRIVER = 0;
    private static final int REMINDER_BIT_PASSENGER = 1;

    /**
     * Is the seatbelt-not-fastened reminder ACTIVE for this seat? A firing reminder means the
     * belt is unfastened AND the car believes someone is there to fasten it — so it is a
     * high-confidence "occupied".
     *
     * <p><b>POSITIVE-ONLY, deliberately</b> — mirrors the reference app's
     * {@code seatbeltReminderIndicatesOccupant}, which returns {@code active ? true : null} and
     * never {@code false}. A silent reminder does NOT mean the seat is empty: it is equally
     * silent when the belt is fastened, when the chime has timed out, while parked/ACC-off, and
     * on a trim that never populates the mask. Reporting "empty" from silence would fabricate a
     * firm NOBODY — exactly the failure mode the OMS fallback was rejected for
     * ({@link #frontPassengerOccupancy()}).
     *
     * <p>{@code callGetSingle} collapses every failure to -1, and the SDK's own failure codes are
     * negative, so any negative reading is treated as "no data" rather than a mask of all-ones.
     * A mask of 0 is a legitimate "nothing reminding" and simply yields false (= no positive
     * evidence), never "empty".
     *
     * @return true only when the mask is readable AND this seat's bit is set
     */
    private boolean seatbeltReminderActive(int bitIndex) {
        // Bounds-checked before the read (the reference app checks the same 0..31 range): a shift
        // by a negative or >=32 count in Java silently wraps (n & 31) and would test a bit of a
        // different seat rather than failing.
        if (bitIndex < 0 || bitIndex >= 32) return false;
        if (safetyBeltDevice == null) return false;
        int mask = BydDeviceHelper.callGetSingle(safetyBeltDevice, BydFeatureIds.SAFETYBELT_REMINDER_MASK);
        // Diagnostic (throttled 30s, same idiom as the raw-seatbelt log): the bit LAYOUT is an
        // inference from the reference app's mapPassengerArea (driver=bit 0). Read this on-device
        // with the driver seated + unbelted to confirm bit 0 is the one that lights up.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastReminderMaskLogMs > 30_000) {
            lastReminderMaskLogMs = nowMs;
            logger.info("seatbelt reminder mask: raw=" + mask
                    + (mask < 0 ? " (unreadable)" : " bits=0b" + Integer.toBinaryString(mask))
                    + " driverBit" + REMINDER_BIT_DRIVER + "="
                    + (mask >= 0 ? ((mask >> REMINDER_BIT_DRIVER) & 1) : -1)
                    + " passengerBit" + REMINDER_BIT_PASSENGER + "="
                    + (mask >= 0 ? ((mask >> REMINDER_BIT_PASSENGER) & 1) : -1));
        }
        // Negative == unreadable/failure (callGetSingle's -1, or a raw SDK failure code). Never
        // bit-test it: Integer.MIN_VALUE and friends would light up arbitrary seat bits.
        //
        // 65535/65534 must be rejected EXPLICITLY: they are the CAN not-available rails, they are
        // POSITIVE (so `mask < 0` misses them) and they are absent from SEATBELT_FAILURE_CODES.
        // Bit-testing 0xFFFF sets every seat bit, so an unset rail would report the driver present
        // and belted forever — and driver occupancy is positive-only, so nothing retracts it. The
        // belt-status path and readKeyInt already filter this rail; only this mask did not.
        if (mask < 0 || mask == 65535 || mask == 65534 || SEATBELT_FAILURE_CODES.contains(mask)) {
            return false;
        }
        return ((mask >> bitIndex) & 1) == 1;
    }

    // Throttle (30s) for the reminder-mask diagnostic above, so a 500ms poll can't flood the log.
    // Same benign-race stance as lastSeatbeltRawLogMs: a cross-thread write only affects cadence.
    private volatile long lastReminderMaskLogMs = 0;

    // Driver presence is inferred, in this order, mirroring the reference app:
    //  tier 1 — reminder mask bit 0 active ⇒ OCCUPIED (seatbeltReminderIndicatesOccupant)
    //  tier 2 — driver belt BUCKLED ⇒ OCCUPIED (inferPresenceFromSeatbelt: beltState==1 → 1)
    //  tier 3 — belt UNBUCKLED ⇒ the reference app says 0, but an unbuckled-yet-present driver
    //           reads identically, so we withhold it entirely (see readDriverOccupancyNow).
    // The ladder lives inline in readDriverOccupancyNow because tier 1 must run even when the
    // instrument device (tier 2's source) is missing.

    /**
     * Live seat-occupancy read — shared by the 5s telemetry poll ({@link #collectSafetyBelt})
     * and the fast occupancy poll, so both publish identical edges. Uses the direct passenger
     * sensor when it supplies 0/1; otherwise the passenger belt/reminder estimate selected for
     * this vehicle (unbuckled=empty, buckled/reminder=occupied).
     *
     * <p><b>SHAPE IS FROZEN AT ONE SLOT.</b> Index 0 = front passenger, from its physical sensor
     * when available or the disclosed belt/reminder estimate otherwise. The driver seat has no
     * sensor and is NOT added here even though
     * {@link #readDriverOccupancyNow()} can now infer it: this array is serialized POSITIONALLY
     * and wholesale by two external consumers — {@code BydVehicleData.toJson}'s
     * {@code extendedSafety.passengerDetection} and the MQTT {@code passenger_detection} topic —
     * so appending a slot would silently redefine an existing published contract for every
     * Home-Assistant/API consumer. The inferred driver value is published only as its own
     * automation event ({@code BydEvent.OCCUPANT_DRIVER}), which is additive.
     *
     * @return a 1-slot array {index 0 = front passenger}, {@code 1=occupied / 0=empty}, or
     *         {@code null} when neither the direct sensor nor belt fallback can read a valid
     *         state. Never contains a sentinel: an unreadable seat yields null rather than an
     *         UNAVAILABLE slot, so the serializers that dump this array wholesale cannot publish
     *         Integer.MIN_VALUE as a seat state.
     */
    public int[] readOccupantsNow() {
        if (safetyBeltDevice == null && instrumentDevice == null) return null;
        try {
            int passenger = frontPassengerOccupancy();
            if (passenger == BydVehicleData.UNAVAILABLE) {
                return null;
            }
            return new int[]{ passenger };
        } catch (Throwable t) {
            logger.debug("readOccupantsNow error: " + t.getMessage());
            return null;
        }
    }

    /**
     * Sample and publish the front-passenger occupancy event from the direct getter.
     *
     * <p>The getter and the typed listener callback share one publication lock. This makes their
     * order explicit: a callback cannot finish after a later direct sample and republish its
     * older value. An unavailable getter remains a no-op, preserving the typed callback as the
     * fallback on trims where the synchronous API only returns INVALID.
     */
    public void pollPassengerOccupancyEvent() {
        synchronized (passengerOccupancyPublishLock) {
            if (!initialized) return;
            int[] occupants = readOccupantsNow();
            if (occupants == null || occupants.length == 0) return;
            long sampleGeneration = passengerOccupancySampleGeneration.incrementAndGet();
            publishPassengerOccupancySample(sampleGeneration, occupants[0]);
        }
    }

    /**
     * Live DRIVER-seat presence, inferred (no occupancy sensor exists for that seat).
     *
     * <p>Returns only the two tiers that are safe to publish as a positive:
     * {@code 1} when the reminder mask says someone is sitting there unbelted, or when the
     * driver belt is buckled. Otherwise {@link BydVehicleData#UNAVAILABLE} — <b>never 0</b>.
     *
     * <p><b>Why "empty" is withheld.</b> An unbuckled driver who is present and an empty seat
     * produce the identical reading (belt 0, reminder silent), so a 0 here would be a guess
     * dressed as a fact. Publishing it would let {@code occupant{seat:driver} == empty} fire
     * while someone is sitting in the seat — worse than the condition simply never matching.
     * The reference app's {@code inferPresenceFromSeatbelt} does return 0 for this case, but it
     * feeds an internal presence heuristic, not a user-visible automation trigger; its
     * reminder tier is likewise positive-only ({@code active ? true : null}).
     *
     * <p>Shares {@link #readSeatbeltPair()}'s sanitized driver reading, so it cannot disagree
     * with the {@code seatbelt{seat:driver}} event and adds at most ONE extra HAL read (the
     * reminder mask) per call.
     *
     * <p><b>The two tiers are independent on purpose.</b> The belt read lives on the INSTRUMENT
     * device and the reminder mask on the SAFETY-BELT device, and a trim can expose either
     * without the other — {@link #readSeatbeltPair()} returns null outright when
     * {@code instrumentDevice} is absent. So the reminder tier must NOT be nested behind a
     * successful belt read, or it would be dead on exactly the trims it is there to rescue.
     *
     * @return 1 = driver present, or UNAVAILABLE when there is no positive evidence
     */
    public int readDriverOccupancyNow() {
        try {
            // Tier 1 first, and unconditionally: it needs only the safety-belt device, so it must
            // still run when the instrument device (and therefore the belt tier) is unavailable.
            if (seatbeltReminderActive(REMINDER_BIT_DRIVER)) return 1;
            if (instrumentDevice == null) return BydVehicleData.UNAVAILABLE;
            int[] belts = readSeatbeltPair();
            int driverBelt = (belts != null && belts.length > 0) ? belts[0] : BydVehicleData.UNAVAILABLE;
            return driverBelt == 1 ? 1 : BydVehicleData.UNAVAILABLE;
        } catch (Throwable t) {
            logger.debug("readDriverOccupancyNow error: " + t.getMessage());
            return BydVehicleData.UNAVAILABLE;
        }
    }

    /** Strict sanitize (used for the passenger + every non-driver caller): a raw instrument
     *  seatbelt read → 0 (unbuckled) / 1 (buckled) / {@link BydVehicleData#UNAVAILABLE} (unknown).
     *  Mirrors the OEM firmware's sanitizeSeatbeltState: drop failure codes, then mask &0xFFFF and
     *  drop INVALID(2). See {@link #sanitizeSeatbelt(int, boolean)} for the driver best-effort variant.
     *
     *  <p>ORDER MATTERS: the failure codes are RAW 32-bit negatives (e.g.
     *  Integer.MIN_VALUE, -1, -10011), so they must be tested against the RAW value
     *  BEFORE masking. If tested after &0xFFFF, Integer.MIN_VALUE would mask to 0 and be
     *  mis-reported as "unbuckled" — a spurious belt-off trigger. */
    private static int sanitizeSeatbelt(int raw) {
        return sanitizeSeatbelt(raw, false);
    }

    /**
     * As {@link #sanitizeSeatbelt(int)}, with a per-seat {@code driverBestEffort} switch.
     *
     * <p>Confirmed on this firmware: {@code masked == 1} is BUCKLED (the buckle → seat-cooling
     * "THEN" automation fires reliably for both seats, so a 1 genuinely reaches the engine),
     * and {@code masked == 0} is UNBUCKLED.
     *
     * <p><b>Driver best-effort (driverBestEffort=true).</b> The reported defect: the driver's
     * "unbuckle → cooling OFF" (the ELSE) never fires, while the passenger's does. Since the
     * write path is symmetric and the passenger proves the ELSE mechanism works, the driver's
     * unbuckle raw code is evidently one this strict sanitize was dropping to UNAVAILABLE
     * (INVALID(2) or another non-0/1 masked value) — so no "off" transition was ever published.
     * For the DRIVER channel only we therefore treat INVALID(2) as UNBUCKLED(0): the driver
     * belt idles at a stable BUCKLED(1) while fastened, so its off-state surfacing as INVALID
     * is the most probable dropped code on this trim. This is an inference made without a
     * device reading (see the throttled raw log in {@link #readSeatbeltPair}, which will pin
     * the EXACT code on the next drive); the passenger keeps the strict mapping (its problem
     * is empty-seat oscillation, handled by the occupancy gate, not a stuck-on state).
     */
    private static int sanitizeSeatbelt(int raw, boolean driverBestEffort) {
        if (SEATBELT_FAILURE_CODES.contains(raw)) return BydVehicleData.UNAVAILABLE;
        int masked = raw & 0xFFFF;
        if (masked == 1) return 1;   // buckled (confirmed on this firmware)
        if (masked == 0) return 0;   // unbuckled
        if (masked == 2) {           // INVALID
            // Best-effort: the driver's off-state is dropped here today (the ELSE never
            // fires). Treat driver INVALID as unbuckled so the OFF edge publishes; keep it
            // UNAVAILABLE for the passenger so its empty-seat noise stays "no reading".
            return driverBestEffort ? 0 : BydVehicleData.UNAVAILABLE;
        }
        return BydVehicleData.UNAVAILABLE;
    }

    private void collectTyre(BydVehicleData.Builder b) {
        if (tyreDevice == null) return;
        try {
            // Pressure value (kPa, raw int — per the OEM firmware's pressure
            // formatter: no scaling for kPa, *0.1450377 for psi,
            // /100 for bar). Areas: 1=FL, 2=FR, 3=RL, 4=RR.
            int[] pressures = new int[4];
            int[] pressureStates = new int[4];
            int[] airLeakStates = new int[4];
            int[] signalStates = new int[4];
            for (int i = 0; i < 4; i++) {
                Object p = BydDeviceHelper.callGetter(tyreDevice, "getTyrePressureValue", i + 1);
                pressures[i] = (p instanceof Number) ? ((Number) p).intValue() : -1;
                Object s = BydDeviceHelper.callGetter(tyreDevice, "getTyrePressureState", i + 1);
                pressureStates[i] = (s instanceof Number) ? ((Number) s).intValue() : -1;
                Object leak = BydDeviceHelper.callGetter(tyreDevice, "getTyreAirLeakState", i + 1);
                airLeakStates[i] = (leak instanceof Number) ? ((Number) leak).intValue() : -1;
                Object sig = BydDeviceHelper.callGetter(tyreDevice, "getTyreSignalState", i + 1);
                signalStates[i] = (sig instanceof Number) ? ((Number) sig).intValue() : -1;

                // Poll per-wheel temperature via the matching SDK getter.
                // The async onTyreBatteryValueChanged callback is dormant on
                // some firmwares (PHEV models on this fleet, confirmed by
                // log capture), but a small subset of those firmwares still
                // answer getTyreBatteryValue(area) with the same temperature
                // value the cluster reads. callGetter is null-safe so this
                // is a no-op on firmwares that don't expose the getter.
                pollPerWheelTyreTemp(i);
            }
            b.tyrePressure(pressures);
            b.tyrePressureState(pressureStates);
            b.tyreAirLeakState(airLeakStates);
            b.tyreSignalState(signalStates);
            b.tyreTemperature(snapshotTyreTemperatures());

            Object sys = BydDeviceHelper.callGetter(tyreDevice, "getTyreSystemState");
            if (sys instanceof Number) b.tyreSystemState(((Number) sys).intValue());
            Object temp = BydDeviceHelper.callGetter(tyreDevice, "getTyreTemperatureState");
            if (temp instanceof Number) b.tyreTemperatureState(((Number) temp).intValue());

            // Per-tyre temperature has three possible channels:
            //   1. Async listener: AbsBYDAutoTyreListener.onTyreBatteryValueChanged
            //      — fires on BEV firmware when registered via the two-arg
            //      registerListener(listener, int[]) overload. Dormant on some
            //      PHEV firmware with single-arg registration only.
            //   2. Polled getter: pollPerWheelTyreTemp() above tries
            //      getTyreBatteryValue / getTyreTemperatureValue /
            //      getTyreTemperature / getTyreTemperatureState.
            //   3. InstrumentDevice feature IDs: polled in
            //      collectInstrumentExtended() using the LF/RF/LB/RB
            //      tyre temperature feature IDs from BydFeatureIds.
            // If all three channels stay silent, tyre temperature is not
            // available on this firmware via any known SDK path.

            logTyreAlertsIfChanged(pressures, pressureStates, airLeakStates, signalStates,
                    sys instanceof Number ? ((Number) sys).intValue() : Integer.MIN_VALUE,
                    temp instanceof Number ? ((Number) temp).intValue() : Integer.MIN_VALUE);
        } catch (Exception e) {
            logger.debug("collectTyre error: " + e.getMessage());
        }
    }

    // Last-seen tyre alert state — change-only logging so a developing slow leak
    // surfaces at info, but a healthy car doesn't spam the log every poll.
    private volatile int[] lastTyrePressuresKpa = null;
    private volatile int[] lastTyrePressureStates = null;
    private volatile int[] lastTyreAirLeakStates = null;
    private volatile int[] lastTyreSignalStates = null;
    private volatile int lastTyreSystemState = Integer.MIN_VALUE;
    private volatile int lastTyreTemperatureState = Integer.MIN_VALUE;

    // --- Tyre alarm notification state machine (per corner FL,FR,RL,RR) ---
    // The push notification is LATCHED so a persisting alarm notifies exactly
    // ONCE and re-arms only after the corner reads normal again. This replaces
    // the old 0->non-zero edge check, which (a) never fired for a tyre already
    // low at daemon start, (b) turned a -1 signal dropout into a bogus
    // "-1 kPa Overpressure" alert, and (c) missed a real alarm that arrived
    // right after a dropout. All access is guarded by tyreAlarmLock because the
    // poll thread and the async onTyreCallback binder thread both drive it.
    //
    // The latch stores the NOTIFIED severity level (0=none, 1=WARN, 2=CRITICAL).
    // We re-notify ONLY on strict escalation (level rises above the latched
    // level, e.g. slow->fast leak, or low->deflated pressure); a same-or-lower
    // reading never re-fires. So a persisting alarm produces at most one push
    // per severity step, and re-arms (latch back to 0) after the corner reads
    // normal for TYRE_ALARM_REARM_STREAK consecutive valid polls.
    private final int[] tyrePressureLatchedLevel = new int[4];
    // Monotone-threshold debounce: consecutive confirmed reads at or above each
    // severity. warnStreak counts level>=1 reads, critStreak counts level>=2.
    // A severity fires only once ITS OWN streak meets the required count — so a
    // lone transient deflation spike bumps critStreak to 1 (no CRITICAL) while
    // warnStreak stays continuous (WARN still holds), and a tyre flapping right
    // at the CRITICAL boundary still fires WARN (level>=1 is continuous) without
    // firing a flappy CRITICAL. Escalation earns its own confirming reads
    // instead of inheriting the lower level's count.
    private final int[] tyrePressureWarnStreak = new int[4];
    private final int[] tyrePressureCritStreak = new int[4];
    private final int[] tyrePressureNormalStreak = new int[4];
    // Per corner: has the firmware pressureState enum EVER reported non-zero?
    // This tells us the enum channel is actually live on this firmware. On the
    // stuck-at-0 firmwares the kPa fallback exists to serve, this stays false
    // forever, so a bare enum==0 is NOT accepted as proof-of-normal for re-arm
    // (only a valid kPa reading is) — otherwise a kPa signal dropout, which
    // leaves enum stuck at 0, would masquerade as a normal read, re-arm the
    // latch, and let the SAME persisting low tyre notify again (duplicate).
    private final boolean[] tyrePressureEnumEverWorked = new boolean[4];
    // Per corner: consecutive reads where kPa was INVALID (getter null / sentinel)
    // while the corner was still being evaluated. kPa is the ground-truth channel,
    // so a SHORT dropout must HOLD the latch — we can't confirm the tyre recovered
    // without a real reading, and an intermittent enum lying with 0 during the
    // dropout must not re-arm us (that re-fires the same persisting low tyre =
    // duplicate). But once kPa has been dead for TYRE_KPA_DEAD_STREAK reads it is
    // treated as a dead channel, and we fall back to trusting a proven enum's 0
    // for re-arm — otherwise a firmware whose kPa dies (or a single spurious
    // startup kPa reading) would latch the corner forever and suppress every
    // future alarm on it (a missed SAFETY alarm, worse than a duplicate).
    private final int[] tyrePressureKpaInvalidStreak = new int[4];
    private final int[] tyreLeakLatchedSeverity = new int[4];
    private final int[] tyreLeakNormalStreak = new int[4];
    private final Object tyreAlarmLock = new Object();

    // kPa numeric thresholds — now USER-CONFIGURABLE per axle via the tyres
    // section of the unified config (Notifications screen → Tyre pressure
    // limits). The shipped defaults still mirror the vehicle-control.js corner
    // colouring (PSI = kPa * 0.1450377): warn below ~34 PSI / above ~45 PSI,
    // critical below ~22 PSI (deflated). These are the SAFETY NET for firmwares
    // whose getTyrePressureState enum stays stuck at 0 even when a tyre is
    // clearly low — the exact case the old enum-only notifier missed.
    //
    // Read fresh per evaluation pass (see evaluateTyreAlarms) so a limit the
    // user just saved applies to the very next poll without a daemon restart.
    // Corner index order is [FL, FR, RL, RR], so 0/1 are the front axle.
    // These mirror the UnifiedConfigManager defaults and are the fallback used
    // when the config is unreadable.
    private static final int TYRE_LOW_DEFAULT_KPA =
            com.overdrive.app.config.UnifiedConfigManager.TYRE_LOW_DEFAULT_KPA;
    private static final int TYRE_HIGH_DEFAULT_KPA =
            com.overdrive.app.config.UnifiedConfigManager.TYRE_HIGH_DEFAULT_KPA;
    private static final int TYRE_CRITICAL_LOW_DEFAULT_KPA =
            com.overdrive.app.config.UnifiedConfigManager.TYRE_CRITICAL_LOW_DEFAULT_KPA;
    // Consecutive-read debounce so a single transient kPa sample (hard cornering,
    // temperature spike) can't fire, and a single blip can't re-arm.
    private static final int TYRE_ALARM_FIRE_STREAK = 2;
    private static final int TYRE_ALARM_REARM_STREAK = 2;
    // How many consecutive invalid-kPa reads before kPa is considered a DEAD
    // channel (vs. a transient dropout). Above this, a proven-live enum's 0 is
    // allowed to re-arm the latch again. Kept comfortably above the dropout
    // lengths seen in practice so a brief signal gap can't re-enable enum re-arm
    // and re-fire a persisting alarm, while a genuinely dead kPa channel still
    // recovers its ability to re-arm within a few polls.
    private static final int TYRE_KPA_DEAD_STREAK = 4;

    private final int[] tyreTemperatureCache = new int[]{
            BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE,
            BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE
    };
    private volatile boolean loggedTyreSlot0 = false;
    private volatile boolean loggedInstrumentTyreTemp = false;
    // Per-wheel one-shot log. We surface the FIRST onTyreBatteryValueChanged
    // arrival for each wheel at info level so it's obvious from a single log
    // pull whether the BYD HAL is delivering temperature events at all on
    // this vehicle. Without this, a silent firmware looks identical to a
    // working firmware where we just haven't received an event yet.
    private final boolean[] loggedTyreFirstEvent = new boolean[]{false, false, false, false};
    private volatile boolean loggedTyreOutOfRange = false;

    private void logTyreAlertsIfChanged(int[] pressuresKpa, int[] pressureStates,
                                        int[] airLeakStates, int[] signalStates,
                                        int sysState, int tempState) {
        // Notification emit — delegated to a per-corner LATCHED state machine
        // (see evaluateTyreAlarms). Unlike the old 0->non-zero enum edge, this:
        //   - fires for a tyre already low at daemon start (no baseline miss),
        //   - falls back to kPa thresholds when the firmware alarm enum is
        //     stuck at 0 (the common BYD-HAL failure mode our own UI already
        //     works around), and
        //   - never emits a duplicate: one push per severity step, re-armed
        //     only after the corner reads normal again.
        // Runs BEFORE the change-detection early-return below, which only
        // throttles the diagnostic log — the state machine must observe every
        // poll so its normal-streak re-arm counter advances and a tyre that
        // stays low across the very first poll still notifies once.
        evaluateTyreAlarms(pressuresKpa, pressureStates, airLeakStates);

        // Pressures fluctuate constantly (heat, drive cycle). Only treat as
        // "changed" if any wheel moves more than 5 kPa; otherwise the log
        // would fire every poll on a moving car.
        boolean pressureChanged = lastTyrePressuresKpa == null;
        if (!pressureChanged) {
            for (int i = 0; i < pressuresKpa.length; i++) {
                if (Math.abs(pressuresKpa[i] - lastTyrePressuresKpa[i]) > 5) {
                    pressureChanged = true;
                    break;
                }
            }
        }
        boolean alertChanged =
                !java.util.Arrays.equals(pressureStates, lastTyrePressureStates)
                || !java.util.Arrays.equals(airLeakStates, lastTyreAirLeakStates)
                || !java.util.Arrays.equals(signalStates, lastTyreSignalStates)
                || sysState != lastTyreSystemState
                || tempState != lastTyreTemperatureState;
        if (!pressureChanged && !alertChanged) return;

        lastTyrePressuresKpa = pressuresKpa.clone();
        lastTyrePressureStates = pressureStates.clone();
        lastTyreAirLeakStates = airLeakStates.clone();
        lastTyreSignalStates = signalStates.clone();
        lastTyreSystemState = sysState;
        lastTyreTemperatureState = tempState;
        // Per-wheel readout: kPa, alarm-state enum (0=NORMAL/1=OVER/2=UNDER),
        // leak-state enum (0=Normal/1=Slow/2=Fast), signal-state enum (0=OK/1=Err)
        StringBuilder sb = new StringBuilder("Tyre:");
        String[] labels = {" FL", " FR", " RL", " RR"};
        for (int i = 0; i < 4; i++) {
            sb.append(labels[i]).append("=").append(pressuresKpa[i]).append("kPa");
            sb.append("/alarm=").append(pressureStates[i]);
            sb.append("/leak=").append(airLeakStates[i]);
            sb.append("/sig=").append(signalStates[i]);
        }
        sb.append(" sys=").append(sysState == Integer.MIN_VALUE ? "n/a" : sysState);
        sb.append(" temp=").append(tempState == Integer.MIN_VALUE ? "n/a" : tempState);
        logger.info(sb.toString());
    }

    private static String tyreWheelLabel(int wheel) {
        switch (wheel) {
            case 0: return Messages.get("notifications.area_front_left");
            case 1: return Messages.get("notifications.area_front_right");
            case 2: return Messages.get("notifications.area_rear_left");
            case 3: return Messages.get("notifications.area_rear_right");
            default: return Messages.get("notifications.area_door_n", wheel + 1);
        }
    }

    /**
     * Format a tyre pressure reading in the user's display unit for
     * notification text (push, Telegram, persisted history — they all share
     * the composed string). The measured/compared unit remains kPa
     * throughout; this converts at the last render step only, mirroring what
     * the web UI does via {@code BYD.units.pressure}.
     *
     * <p>Decimals per unit match the web UI: kPa integer (the TPMS step is
     * ~3 kPa), PSI one decimal ({@code VehicleControlApiHandler} rounds the
     * API's psi field the same way), bar two decimals. The unit label comes
     * from the message catalog so locales that localise it (ru: "кПа") stay
     * correct.
     */
    private static String formatTyrePressure(int kPa) {
        String unit;
        try {
            unit = com.overdrive.app.config.UnifiedConfigManager.getTyrePressureUnit();
        } catch (Throwable t) {
            unit = "psi"; // config unreadable — fall back to the shipped default
        }
        switch (unit) {
            case "kpa":
                return kPa + " " + Messages.get("units.kpa");
            case "bar":
                return String.format(java.util.Locale.US, "%.2f", kPa / 100.0)
                        + " " + Messages.get("units.bar");
            case "psi":
            default:
                return String.format(java.util.Locale.US, "%.1f", kPa * 0.1450377)
                        + " " + Messages.get("units.psi");
        }
    }

    /**
     * Per-corner latched tyre-alarm evaluator. Called on every tyre read (poll
     * or async listener), it fires a push at most ONCE per severity step and
     * re-arms only after the corner returns to normal — so a persisting alarm
     * never spams, but a fresh or escalating one always notifies.
     *
     * <p><b>Pressure severity</b> is the max of the firmware enum and the kPa
     * fallback:
     * <ul>
     *   <li>2 (CRITICAL): kPa &le; the configured {@code criticalLow} (deflated)</li>
     *   <li>1 (WARN): firmware pressureState != 0, OR kPa outside the
     *       configured [low, high] band for THIS corner's axle</li>
     *   <li>0: normal</li>
     * </ul>
     * Limits come from {@code UnifiedConfigManager.getTyreThresholds()} (front
     * and rear are independent); the shipped defaults reproduce the previous
     * hardcoded 234 / 310 / 152 kPa behaviour.
     * <b>Leak severity</b> comes straight from the firmware air-leak enum
     * (1=slow/WARN, 2=fast/CRITICAL) — there is no kPa proxy for a leak.
     *
     * <p>A value of -1 (getter returned null / signal dropout) is treated as
     * "no data": it neither fires an alarm nor counts toward the normal-streak
     * re-arm, so a transient dropout can't emit a bogus "-1 kPa" push and can't
     * silently re-arm a latch mid-alarm.
     */
    private void evaluateTyreAlarms(int[] pressuresKpa, int[] pressureStates, int[] airLeakStates) {
        if (pressuresKpa == null || pressureStates == null || airLeakStates == null) return;
        // Resolve the user's limits ONCE per pass — all four corners must be
        // judged against the same snapshot, and re-reading per corner would
        // also multiply the config read by 4. getTyreThresholds() is clamped
        // and invariant-checked, so the comparisons below need no validation.
        int lowFront = TYRE_LOW_DEFAULT_KPA;
        int highFront = TYRE_HIGH_DEFAULT_KPA;
        int lowRear = TYRE_LOW_DEFAULT_KPA;
        int highRear = TYRE_HIGH_DEFAULT_KPA;
        int criticalLow = TYRE_CRITICAL_LOW_DEFAULT_KPA;
        try {
            org.json.JSONObject th =
                    com.overdrive.app.config.UnifiedConfigManager.getTyreThresholds();
            lowFront = th.optInt("frontLow", lowFront);
            highFront = th.optInt("frontHigh", highFront);
            lowRear = th.optInt("rearLow", lowRear);
            highRear = th.optInt("rearHigh", highRear);
            criticalLow = th.optInt("criticalLow", criticalLow);
        } catch (Throwable t) {
            // Config unreadable — fall back to the shipped defaults rather than
            // skipping evaluation. A missed tyre alarm is a safety issue.
            logger.debug("Tyre threshold read failed, using defaults: " + t.getMessage());
        }

        synchronized (tyreAlarmLock) {
            for (int i = 0; i < 4; i++) {
                int kPa = i < pressuresKpa.length ? pressuresKpa[i] : -1;
                int pState = i < pressureStates.length ? pressureStates[i] : -1;
                int leak = i < airLeakStates.length ? airLeakStates[i] : -1;

                // [FL, FR, RL, RR] — indices 0/1 are the front axle.
                boolean isFront = i < 2;
                evaluatePressureCorner(i, kPa, pState,
                        isFront ? lowFront : lowRear,
                        isFront ? highFront : highRear,
                        criticalLow);
                evaluateLeakCorner(i, kPa, leak);
            }
        }
    }

    /**
     * Pressure alarm for one corner. Caller holds tyreAlarmLock.
     *
     * @param lowWarnKpa  under-pressure WARN floor for THIS corner's axle
     * @param highWarnKpa over-pressure WARN ceiling for THIS corner's axle
     * @param lowAlertKpa deflated/CRITICAL threshold (axle-independent)
     */
    private void evaluatePressureCorner(int i, int kPa, int pState,
                                        int lowWarnKpa, int highWarnKpa, int lowAlertKpa) {
        // kPa is valid only when the getter answered with a real reading.
        // BydVehicleData.UNAVAILABLE (Integer.MIN_VALUE) and the -1 dropout
        // sentinel both mean "no pressure data". <=0 is never a real tyre.
        boolean kPaValid = kPa > 0 && kPa != BydVehicleData.UNAVAILABLE;
        boolean enumOver = BydVehicleData.isTyreOverpressureState(pState);
        boolean enumUnder = BydVehicleData.isTyreUnderpressureState(pState);
        boolean stateValid =
                pState == BydVehicleData.TYRE_PRESSURE_STATE_NORMAL
                        || enumOver || enumUnder;
        boolean enumAlarm = enumOver || enumUnder;
        if (enumAlarm) tyrePressureEnumEverWorked[i] = true;
        // Track kPa channel liveness: reset on any valid reading, count up while
        // it's invalid. A single valid read clears the "dead" state, so one
        // spurious startup blip can't poison re-arm forever.
        if (kPaValid) tyrePressureKpaInvalidStreak[i] = 0;
        else if (tyrePressureKpaInvalidStreak[i] < Integer.MAX_VALUE) tyrePressureKpaInvalidStreak[i]++;

        // No usable signal at all this poll — don't advance either streak so a
        // dropout neither fires nor re-arms; hold the latch as-is.
        if (!kPaValid && !stateValid) return;

        // Severity = max(firmware enum, kPa fallback). The kPa net is what
        // catches firmwares whose pressureState stays stuck at 0. Direction
        // (under/over) is taken from kPa when we have a real reading — the kPa
        // is ground truth; the enum's 1=OVER/2=UNDER is only a fallback for the
        // direction word when kPa is absent, and it can disagree with reality
        // on a misreporting firmware, so kPa wins when present.
        int level = 0;
        boolean under = false, over = false;
        if (enumAlarm) {
            level = 1;
            over = enumOver;
            under = enumUnder;
        }
        if (kPaValid) {
            if (kPa <= lowAlertKpa) { level = 2; under = true; over = false; }
            else if (kPa < lowWarnKpa) { level = Math.max(level, 1); under = true; over = false; }
            else if (kPa > highWarnKpa) { level = Math.max(level, 1); over = true; under = false; }
            // else: kPa is in the normal band. It does NOT clear an enum alarm
            // (level stays 1 from enumAlarm above) — the firmware enum is
            // authoritative for "there is a problem"; we just can't name a kPa
            // direction, so leave the enum's under/over.
        }

        if (level == 0) {
            // Normal reading — but re-arm ONLY on positive proof of normal, not
            // mere absence of an alarm. Channel priority mirrors firing: kPa is
            // ground truth, the enum is only trusted where kPa isn't answering.
            //   - kPa valid & in-band  -> confirmed normal.
            //   - kPa dead (invalid for >= TYRE_KPA_DEAD_STREAK reads) but a
            //     proven-live enum reads 0 -> confirmed normal (enum is all we
            //     have; refusing forever would suppress every future alarm on a
            //     firmware whose kPa channel died = missed safety alarm).
            //   - kPa merely dropped out briefly (invalid but under the dead
            //     threshold) -> NOT confirmed, even if a proven enum reads 0:
            //     without a real kPa we can't tell the tyre recovered, and an
            //     intermittent enum lying with 0 during the dropout would
            //     otherwise re-arm and re-fire the same persisting low tyre
            //     (duplicate). Hold the latch instead.
            //   - stuck-at-0 enum never proven + kPa invalid -> NOT confirmed.
            boolean kPaDead = tyrePressureKpaInvalidStreak[i] >= TYRE_KPA_DEAD_STREAK;
            boolean normalConfirmed = kPaValid
                    || (stateValid && tyrePressureEnumEverWorked[i] && kPaDead);
            if (!normalConfirmed) return; // hold latch + streaks; no re-arm, no reset

            tyrePressureWarnStreak[i] = 0;
            tyrePressureCritStreak[i] = 0;
            if (tyrePressureLatchedLevel[i] != 0) {
                if (++tyrePressureNormalStreak[i] >= TYRE_ALARM_REARM_STREAK) {
                    tyrePressureLatchedLevel[i] = 0;
                    tyrePressureNormalStreak[i] = 0;
                    logger.info("Tyre pressure re-armed (normal): " + tyreWheelLabel(i)
                            + (kPaValid ? " " + kPa + " kPa" : ""));
                }
            }
            return;
        }

        // Abnormal reading. Advance monotone per-severity streaks: any abnormal
        // read (level>=1) advances warnStreak; only a deflated read (level==2)
        // advances critStreak. A read that drops from CRITICAL back to WARN
        // resets critStreak (the deflation didn't persist) while warnStreak
        // keeps climbing — so boundary flapping still holds WARN and a lone
        // deflation spike can't fire CRITICAL. A firmware-enum alarm is already
        // debounced by the TPMS firmware, so it fires on the first read;
        // the noisier kPa-only fallback waits for a confirming read.
        tyrePressureNormalStreak[i] = 0;
        tyrePressureWarnStreak[i]++;
        if (level >= 2) tyrePressureCritStreak[i]++; else tyrePressureCritStreak[i] = 0;

        // WARN may fire on the first read when the firmware enum confirms it
        // (already firmware-debounced); a kPa-only WARN waits for a confirming
        // read. CRITICAL is ALWAYS kPa-derived (level 2 comes only from
        // kPa<=deflated threshold, never from the enum), so it ALWAYS waits for
        // a confirming read — a single transient deflation sample never fires
        // CRITICAL, even when an enum WARN alarm is simultaneously active.
        int warnRequired = enumAlarm ? 1 : TYRE_ALARM_FIRE_STREAK;
        int critRequired = TYRE_ALARM_FIRE_STREAK;

        // The severity we're allowed to fire is the HIGHEST whose own streak has
        // met its required count. Check CRITICAL first, then WARN.
        int fireLevel = 0;
        if (level >= 2 && tyrePressureCritStreak[i] >= critRequired) fireLevel = 2;
        else if (tyrePressureWarnStreak[i] >= warnRequired) fireLevel = 1;

        // Notify only on strict escalation above the latched level. A
        // same-or-lower fireLevel while already latched is the persisting alarm
        // — stay silent.
        if (fireLevel > tyrePressureLatchedLevel[i]) {
            tyrePressureLatchedLevel[i] = fireLevel;
            String kPaText = kPaValid ? formatTyrePressure(kPa)
                    : Messages.get("notifications.tyre_no_reading");
            String title = fireLevel >= 2
                    ? Messages.get("notifications.tyre_critically_low")
                    : Messages.get(over
                            ? "notifications.tyre_overpressure"
                            : "notifications.tyre_underpressure");
            com.overdrive.app.notifications.NotificationEvent.Severity sev = fireLevel >= 2
                    ? com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL
                    : com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
            try {
                org.json.JSONObject data = new org.json.JSONObject();
                data.put("wheel", i);
                if (kPaValid) data.put("kPa", kPa);
                if (stateValid) data.put("state", pState);
                data.put("level", fireLevel);
                com.overdrive.app.notifications.NotificationBus.get().publish(
                        new com.overdrive.app.notifications.NotificationEvent(
                                "vehicle.health.tyre.pressure",
                                sev,
                                title,
                                Messages.get("notifications.tyre_wheel_reading",
                                        tyreWheelLabel(i), kPaText),
                                "tyre-pressure-" + i,
                                null,
                                data));
            } catch (Throwable t) {
                // Roll the latch back so a publish failure doesn't permanently
                // suppress this corner — next abnormal read retries.
                tyrePressureLatchedLevel[i] = 0;
                logger.debug("tyre.pressure notify failed: " + t.getMessage());
            }
        }
    }

    /** Leak alarm for one corner. Caller holds tyreAlarmLock. */
    private void evaluateLeakCorner(int i, int kPa, int leak) {
        if (leak < 0) return; // getter returned null / dropout — hold latch, no re-arm

        if (leak == 0) {
            if (tyreLeakLatchedSeverity[i] != 0) {
                if (++tyreLeakNormalStreak[i] >= TYRE_ALARM_REARM_STREAK) {
                    tyreLeakLatchedSeverity[i] = 0;
                    tyreLeakNormalStreak[i] = 0;
                    logger.info("Tyre leak re-armed (normal): " + tyreWheelLabel(i));
                }
            }
            return;
        }

        // leak >= 1. Fire once on first detection and once more on escalation
        // (slow=1 -> fast=2); a same-or-lower severity while latched is silent.
        tyreLeakNormalStreak[i] = 0;
        if (leak > tyreLeakLatchedSeverity[i]) {
            tyreLeakLatchedSeverity[i] = leak;
            boolean kPaValid = kPa > 0 && kPa != BydVehicleData.UNAVAILABLE;
            com.overdrive.app.notifications.NotificationEvent.Severity sev = leak == 2
                    ? com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL
                    : com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
            try {
                org.json.JSONObject data = new org.json.JSONObject();
                data.put("wheel", i);
                data.put("leakState", leak);
                if (kPaValid) data.put("kPa", kPa);
                com.overdrive.app.notifications.NotificationBus.get().publish(
                        new com.overdrive.app.notifications.NotificationEvent(
                                "vehicle.health.tyre.leak",
                                sev,
                                Messages.get(leak == 2
                                        ? "notifications.tyre_fast_leak"
                                        : "notifications.tyre_slow_leak"),
                                kPaValid
                                        ? Messages.get("notifications.tyre_wheel_pressure",
                                                tyreWheelLabel(i), formatTyrePressure(kPa))
                                        : tyreWheelLabel(i),
                                "tyre-leak-" + i,
                                null,
                                data));
            } catch (Throwable t) {
                tyreLeakLatchedSeverity[i] = 0;
                logger.debug("tyre.leak notify failed: " + t.getMessage());
            }
        }
    }

    // Per-wheel temperature poll: candidate (device, method, slot-mapping)
    // tuples, in priority order. Each candidate names a getter on either
    // tyreDevice or instrumentDevice plus a per-corner slot map, because the
    // two HALs use DIFFERENT wheel-index conventions:
    //   tyreDevice.getTyreXxx(int):       1=LF, 2=RF, 3=LR, 4=RR
    //   instrumentDevice.getWheelTemperature(int): 1=RF, 2=RR, 3=LF, 4=LR
    // Cache layout is fixed at [FL=0, FR=1, RL=2, RR=3]; each candidate's
    // slotForCacheIdx[i] gives the int to pass for cache slot i.
    //
    // On the first poll we look up each via reflection; from then on we go
    // straight to the surviving method (or short-circuit if none exist on
    // this firmware). This means a sensor that wakes up later still gets a
    // chance to report — we only lock out based on method-existence, not on
    // whether a value was in range.
    private static final int DEV_TYRE = 0;
    private static final int DEV_INSTRUMENT = 1;
    private static final class TyreTempCandidate {
        final int deviceKind;
        final String methodName;
        final int[] slotForCacheIdx; // index by [FL=0, FR=1, RL=2, RR=3]
        TyreTempCandidate(int deviceKind, String methodName, int[] slotForCacheIdx) {
            this.deviceKind = deviceKind;
            this.methodName = methodName;
            this.slotForCacheIdx = slotForCacheIdx;
        }
    }
    private static final int[] TYRE_DEVICE_SLOTS       = {1, 2, 3, 4}; // identity
    private static final int[] INSTRUMENT_DEVICE_SLOTS = {3, 1, 4, 2}; // FL=slot3, FR=slot1, RL=slot4, RR=slot2
    private static final TyreTempCandidate[] TYRE_TEMP_CANDIDATES = {
            // Instrument-side first: confirmed to return real per-corner °C
            // on firmwares where every tyreDevice candidate returns null.
            new TyreTempCandidate(DEV_INSTRUMENT, "getWheelTemperature",     INSTRUMENT_DEVICE_SLOTS),
            // tyreDevice fallbacks — order preserves prior behaviour.
            new TyreTempCandidate(DEV_TYRE,       "getTyreBatteryValue",      TYRE_DEVICE_SLOTS),
            new TyreTempCandidate(DEV_TYRE,       "getTyreTemperatureValue",  TYRE_DEVICE_SLOTS),
            new TyreTempCandidate(DEV_TYRE,       "getTyreTemperature",       TYRE_DEVICE_SLOTS),
            new TyreTempCandidate(DEV_TYRE,       "getTyreTemperatureState",  TYRE_DEVICE_SLOTS),
    };
    // null = not resolved yet (first cycle still running),
    // != null and != NO_TYRE_TEMP_GETTER = the resolved Method,
    // == NO_TYRE_TEMP_GETTER sentinel = no candidate exists on this firmware.
    private static final java.lang.reflect.Method NO_TYRE_TEMP_GETTER;
    static {
        java.lang.reflect.Method m = null;
        try { m = Object.class.getDeclaredMethod("toString"); }
        catch (NoSuchMethodException e) { /* impossible */ }
        NO_TYRE_TEMP_GETTER = m;
    }
    private volatile java.lang.reflect.Method resolvedTyreTempMethod = null;
    // Index into TYRE_TEMP_CANDIDATES: which candidate is currently resolved.
    // When the resolved method consistently returns out-of-range values, we advance
    // to the next candidate. This ensures getTyreBatteryValue returning battery voltage
    // doesn't permanently block getTyreTemperatureState from being tried.
    private volatile int resolvedTyreTempCandidateIdx = 0;
    private volatile int tyreTempOutOfRangeCount = 0;
    private static final int TYRE_TEMP_OUT_OF_RANGE_THRESHOLD = 5; // after 5 bad reads, try next
    private final boolean[] loggedTyrePollHit = new boolean[]{false, false, false, false};
    // Diagnostics: surface the FIRST observation per failure mode so a single
    // log capture tells us which path the BYD HAL is taking.
    private volatile boolean loggedTyrePollNullReturn = false;
    private volatile boolean loggedTyrePollNonNumber = false;
    private volatile boolean loggedTyrePollThrew = false;

    /**
     * Try to read per-wheel temperature via a getter call. On firmwares where
     * the {@code onTyreBatteryValueChanged} async callback never fires, a
     * polled getter is the only remaining channel.
     *
     * <p>The first invocation looks up each candidate method by name; the
     * first one that exists is cached and used from then on. We do NOT
     * require a value in range to "resolve" the getter — a sensor that's
     * temporarily out of signal can still come online later.
     */
    private void pollPerWheelTyreTemp(int idx) {
        java.lang.reflect.Method method = resolvedTyreTempMethod;
        if (method == NO_TYRE_TEMP_GETTER) return;
        if (method == null) {
            method = resolveTyreTempMethod();
            resolvedTyreTempMethod = method;
            if (method == NO_TYRE_TEMP_GETTER) return;
        }
        if (resolvedTyreTempCandidateIdx >= TYRE_TEMP_CANDIDATES.length) return;

        TyreTempCandidate cand = TYRE_TEMP_CANDIDATES[resolvedTyreTempCandidateIdx];
        Object device = (cand.deviceKind == DEV_INSTRUMENT) ? instrumentDevice : tyreDevice;
        if (device == null) {
            // The candidate's device was nulled out after resolution (init
            // failure, device unavailable). Advance so the next poll picks
            // a candidate whose device is still alive.
            resolvedTyreTempCandidateIdx++;
            resolvedTyreTempMethod = null;
            return;
        }
        int wheel = cand.slotForCacheIdx[idx];

        Object raw;
        try {
            raw = method.invoke(device, wheel);
        } catch (Throwable t) {
            if (!loggedTyrePollThrew) {
                loggedTyrePollThrew = true;
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") threw " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
            }
            return; // transient failure; method stays resolved for next cycle
        }
        if (raw == null) {
            if (!loggedTyrePollNullReturn) {
                loggedTyrePollNullReturn = true;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned null — getter exists but firmware has no value");
            }
            return;
        }
        if (!(raw instanceof Number)) {
            if (!loggedTyrePollNonNumber) {
                loggedTyrePollNonNumber = true;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned " + raw.getClass().getSimpleName() + " = " + raw);
            }
            return;
        }
        double v = ((Number) raw).doubleValue();
        if (!(v >= -40.0 && v <= 125.0)) {
            tyreTempOutOfRangeCount++;
            if (tyreTempOutOfRangeCount == 1) {
                // Log on first occurrence
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned " + v + " — outside temperature range, "
                        + "this firmware reports battery voltage via this getter. "
                        + "Will try next candidate after " + TYRE_TEMP_OUT_OF_RANGE_THRESHOLD + " bad reads.");
            }
            if (tyreTempOutOfRangeCount >= TYRE_TEMP_OUT_OF_RANGE_THRESHOLD) {
                // This method consistently returns garbage — advance to next candidate
                tyreTempOutOfRangeCount = 0;
                resolvedTyreTempCandidateIdx++;
                resolvedTyreTempMethod = null; // force re-resolution from next candidate
                logger.info("Tyre temp poll: " + method.getName()
                        + " returned out-of-range " + TYRE_TEMP_OUT_OF_RANGE_THRESHOLD
                        + " times — advancing to next candidate (idx="
                        + resolvedTyreTempCandidateIdx + ")");
            }
            return;
        }
        // Valid reading — reset the out-of-range counter
        tyreTempOutOfRangeCount = 0;

        int tempC = (int) Math.round(v);
        synchronized (tyreTemperatureCache) {
            tyreTemperatureCache[idx] = tempC;
        }
        if (!loggedTyrePollHit[idx]) {
            loggedTyrePollHit[idx] = true;
            logger.info("Tyre temp poll FIRST: wheel=" + wheel
                    + " (" + new String[]{"FL","FR","RL","RR"}[idx] + ") via "
                    + method.getName() + " = " + tempC + "°C");
        }
    }

    private java.lang.reflect.Method resolveTyreTempMethod() {
        for (int i = resolvedTyreTempCandidateIdx; i < TYRE_TEMP_CANDIDATES.length; i++) {
            TyreTempCandidate cand = TYRE_TEMP_CANDIDATES[i];
            Object device = (cand.deviceKind == DEV_INSTRUMENT) ? instrumentDevice : tyreDevice;
            if (device == null) continue; // device unavailable on this firmware
            try {
                java.lang.reflect.Method m = device.getClass().getMethod(cand.methodName, int.class);
                resolvedTyreTempCandidateIdx = i;
                logger.info("Tyre temp poll: using " + cand.methodName + "(int) on "
                        + device.getClass().getSimpleName() + " (candidate idx=" + i + ")");
                return m;
            } catch (NoSuchMethodException ignored) { /* try next */ }
        }
        StringBuilder tried = new StringBuilder();
        for (int i = 0; i < TYRE_TEMP_CANDIDATES.length; i++) {
            if (i > 0) tried.append(", ");
            tried.append(TYRE_TEMP_CANDIDATES[i].deviceKind == DEV_INSTRUMENT ? "instrument." : "tyre.");
            tried.append(TYRE_TEMP_CANDIDATES[i].methodName);
        }
        logger.info("Tyre temp poll: no getter on this firmware "
                + "(tried " + tried + " starting from idx=" + resolvedTyreTempCandidateIdx + ")");
        return NO_TYRE_TEMP_GETTER;
    }

    private int[] snapshotTyreTemperatures() {
        synchronized (tyreTemperatureCache) {
            return new int[]{
                    tyreTemperatureCache[0], tyreTemperatureCache[1],
                    tyreTemperatureCache[2], tyreTemperatureCache[3]
            };
        }
    }

    // Diagnostic: log each unknown tyre feature ID at most once, capped at 32
    // unique IDs total. The cap prevents a chatty HAL (some emit a feature ID
    // every 100ms for trip metrics) from flooding the log if an unknown one
    // happens to slip through the listener filter.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> loggedUnknownTyreIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_UNKNOWN_TYRE_IDS = 32;

    private void logUnknownTyreEventOnce(int eventId, int rawInt, double rawDbl) {
        if (loggedUnknownTyreIds.size() >= MAX_UNKNOWN_TYRE_IDS) return;
        if (loggedUnknownTyreIds.putIfAbsent(eventId, Boolean.TRUE) != null) return;
        logger.info("Tyre event UNKNOWN id=" + eventId
                + " intValue=" + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — if this looks like a temperature, add it to BydFeatureIds.INSTRUMENT_*_TYRE_TEMPERATURE");
    }

    // Per-wheel out-of-range counters for the known LF/RF/LB/RB feature IDs.
    // Logged once per wheel so a sleeping TPMS sensor doesn't spam.
    private final boolean[] loggedTyreEventOutOfRange = new boolean[]{false, false, false, false};

    private void logTyreEventOutOfRangeOnce(int eventId, int wheelIdx, int rawInt, double rawDbl) {
        if (wheelIdx < 0 || wheelIdx > 3 || loggedTyreEventOutOfRange[wheelIdx]) return;
        loggedTyreEventOutOfRange[wheelIdx] = true;
        logger.info("Tyre event OUT-OF-RANGE: " + new String[]{"FL","FR","RL","RR"}[wheelIdx]
                + " (id=" + eventId + ") intValue="
                + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — sensor likely asleep; will retry silently on next event.");
    }

    private void onTyreCallback(String method, Object[] args) {
        if (args == null) return;
        try {
            if ("onTyreTemperatureValueChanged".equals(method) && args.length >= 2) {
                int wheel = ((Number) args[0]).intValue();
                int value = ((Number) args[1]).intValue();
                int idx = wheel;
                if (idx >= 1 && idx <= 4) idx = idx - 1; // 1-based to 0-based
                if (idx >= 0 && idx < 4 && value >= -40 && value <= 125) {
                    synchronized (tyreTemperatureCache) {
                        tyreTemperatureCache[idx] = value;
                    }
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .tyreTemperature(snapshotTyreTemperatures()).build());
                    }
                }
                return;
            }
            if ("onTyrePressureValueByTypeChanged".equals(method) && args.length >= 2) {
                int wheel = ((Number) args[0]).intValue();
                float value = ((Number) args[1]).floatValue();
                int idx = wheel;
                if (idx >= 1 && idx <= 4) idx = idx - 1;
                if (idx >= 0 && idx < 4 && value >= 0f && value <= 5000f) {
                    int kpa = value < 10.0f ? (int) Math.round(value * 100.0) : (int) Math.round(value);
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        int[] pressures = current.tyrePressure != null ? current.tyrePressure.clone() : new int[]{-1, -1, -1, -1};
                        pressures[idx] = kpa;
                        publishNonChargingSnapshot(current.toBuilder()
                                .tyrePressure(pressures).build());
                    }
                }
                return;
            }

            // Generic feature-ID event from the 2-arg listener registration.
            // Per-wheel temperature on this firmware family arrives here keyed
            // on the LF/RF/LB/RB Instrument feature IDs. We accept either
            // intValue (some firmwares emit °C as an integer) or doubleValue
            // (others emit a fractional °C).
            if ("onDataEventChanged".equals(method) && args.length >= 2) {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int idx = -1;
                if (eventId == BydFeatureIds.INSTRUMENT_LF_TYRE_TEMPERATURE) idx = 0;
                else if (eventId == BydFeatureIds.INSTRUMENT_RF_TYRE_TEMPERATURE) idx = 1;
                else if (eventId == BydFeatureIds.INSTRUMENT_LB_TYRE_TEMPERATURE) idx = 2;
                else if (eventId == BydFeatureIds.INSTRUMENT_RB_TYRE_TEMPERATURE) idx = 3;

                if (idx < 0) {
                    // Unknown feature ID — log once per ID so the next log
                    // capture surfaces real per-wheel temperature IDs we
                    // can add to BydFeatureIds.
                    if (eventValue != null) {
                        int rawInt = BydDeviceHelper.getIntValue(eventValue);
                        double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);
                        logUnknownTyreEventOnce(eventId, rawInt, rawDbl);
                    }
                    return;
                }

                // Known wheel — extract value, prefer the int slot.
                int rawInt = BydDeviceHelper.getIntValue(eventValue);
                double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);
                Double tempC = null;
                if (rawInt != Integer.MIN_VALUE && rawInt >= -40 && rawInt <= 125) {
                    tempC = (double) rawInt;
                } else if (!Double.isNaN(rawDbl) && rawDbl >= -40.0 && rawDbl <= 125.0) {
                    tempC = rawDbl;
                }
                if (tempC == null) {
                    // Sentinel — TPMS hasn't reported this wheel yet, or
                    // the value lives in a slot we don't know about.
                    logTyreEventOutOfRangeOnce(eventId, idx, rawInt, rawDbl);
                    return;
                }
                int tempCi = (int) Math.round(tempC);
                synchronized (tyreTemperatureCache) {
                    tyreTemperatureCache[idx] = tempCi;
                }
                if (!loggedTyreFirstEvent[idx]) {
                    loggedTyreFirstEvent[idx] = true;
                    logger.info("Tyre event FIRST: " + new String[]{"FL","FR","RL","RR"}[idx]
                            + " (id=" + eventId + ") = " + tempCi + "°C");
                }
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    publishNonChargingSnapshot(current.toBuilder()
                            .tyreTemperature(snapshotTyreTemperatures()).build());
                }
                return;
            }

            if ("onTyreBatteryValueChanged".equals(method) && args.length >= 2) {
                int wheel = ((Number) args[0]).intValue();
                double value = ((Number) args[1]).doubleValue();
                if (wheel == 0) {
                    if (!loggedTyreSlot0) {
                        loggedTyreSlot0 = true;
                        logger.info("Tyre slot 0 raw event observed (value=" + value + ") — ignoring further slot 0");
                    }
                    return;
                }
                if (wheel < 1 || wheel > 4) {
                    logger.info("Tyre battery callback: unexpected wheel=" + wheel + " value=" + value);
                    return;
                }
                int idx = wheel - 1;
                if (!loggedTyreFirstEvent[idx]) {
                    loggedTyreFirstEvent[idx] = true;
                    logger.info("Tyre battery callback FIRST: wheel=" + wheel
                            + " (" + new String[]{"FL","FR","RL","RR"}[idx] + ") value=" + value);
                }
                if (!(value >= -40.0 && value <= 125.0)) {
                    if (!loggedTyreOutOfRange) {
                        loggedTyreOutOfRange = true;
                        logger.info("Tyre battery callback: value " + value
                                + " outside temperature range — likely battery voltage on this firmware");
                    }
                    return;
                }
                int tempC = (int) Math.round(value);
                synchronized (tyreTemperatureCache) {
                    tyreTemperatureCache[idx] = tempC;
                }
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    publishNonChargingSnapshot(current.toBuilder()
                            .tyreTemperature(snapshotTyreTemperatures()).build());
                }
                return;
            }

            if ("onTyrePressureValueChanged".equals(method)
                    || "onTyrePressureStateChanged".equals(method)
                    || "onTyreAirLeakStateChanged".equals(method)
                    || "onTyreSignalStateChanged".equals(method)
                    || "onTyreSystemStateChanged".equals(method)
                    || "onTyreTemperatureStateChanged".equals(method)
                    || "onIndirectTyreSystemStateChanged".equals(method)) {
                BydVehicleData current = snapshot.get();
                if (current == null) return;
                BydVehicleData.Builder b = current.toBuilder();
                collectTyre(b);
                publishNonChargingSnapshot(b.build());
            }
        } catch (Exception e) {
            logger.debug("onTyreCallback error (" + method + "): " + e.getMessage());
        }
    }

    // Last door-open state seen by the POLL fallback, per raw bodywork area (1..7).
    // Distinct from the event path: this lets the poll synthesize an edge only on a real
    // change. -1 = not yet read. Accessed by the telemetry and DoorEvent poll threads.
    private final java.util.Map<Integer, Integer> lastPolledDoorState = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * POLL-based door open/close fallback. The bodywork HAL delivers {@code onDoorStateChanged}
     * callbacks only while the vehicle is powered/awake — field reports show a parked car
     * stops pushing them, so a "when a door opens" automation never fired once the car was
     * off (the reported bug). The reference app reads door state on-demand via
     * {@code bodyworkDevice.getDoorState(area)} (area 1..7; 1=open, 0=closed, 255=unavailable),
     * which keeps working parked — so the self-gated DoorEvent poll reads it every second and
     * the core collect retains a fallback call; both synthesize the
     * same {@link #notifyDoorStateListeners} edges the callback would, but only on a real
     * transition. The callback path stays primary (instant while awake); this only covers the
     * gap. Both feed {@link com.overdrive.app.automation.condition.DoorEvent}, whose
     * {@code Automations.update} is transition-gated, so an overlap can't double-fire.
     *
     * <p>Zero-cost when unused: skipped unless a door signal is referenced by an enabled rule
     * (or the automation editor is requesting live state).
     */
    public void pollDoorStatesNow() {
        if (bodyworkDevice == null) return;
        // DoorEvent is always registered as a callback listener. Gate on an actual rule/editor
        // reference instead, otherwise this fallback would perform seven HAL reads every core
        // telemetry cycle even on cars that never use door automations.
        if (!com.overdrive.app.automation.condition.DoorEvent.shouldPoll()) return;
        for (int area = 1; area <= 7; area++) {
            try {
                Object v = BydDeviceHelper.callMethod(bodyworkDevice, "getDoorState", area);
                if (!(v instanceof Integer)) continue;
                int raw = (Integer) v;
                // Only 0 (closed) / 1 (open) are meaningful; 255/-1 = unavailable → skip so
                // an unreadable area never manufactures a spurious "closed" edge.
                if (raw != com.overdrive.app.byd.bodywork.BodyworkConstants.STATE_OPEN
                        && raw != com.overdrive.app.byd.bodywork.BodyworkConstants.STATE_CLOSED) continue;
                Integer prev = lastPolledDoorState.get(area);
                if (prev != null && prev == raw) continue; // no change
                lastPolledDoorState.put(area, raw);
                // Publish the first definite value too. Automations.update silently seeds a first
                // sample, so suppressing "closed" here only left the condition uninitialized and
                // caused the first later close/open transition to be lost as that silent seed.
                notifyDoorStateListeners(area, raw);
            } catch (Exception ignored) {
                // Getter absent on this trim → nothing to poll; leave to the callback path.
            }
        }
    }

    private void collectDoorLock(BydVehicleData.Builder b) {
        // The BYDAutoDoorLockDevice service does not expose lock state to
        // user-UID processes on most BYD firmwares — every getDoorLockStatus(area)
        // call returns INVALID(0) and onDoorLockStatusChanged never fires.
        // Field testing confirmed this on Sealion 6 / Atto 3 / others.
        //
        // Lock state is sourced exclusively from the BYD cloud REST/MQTT path
        // via BydCloudDataProvider. The vehicle-control page calls the cloud
        // API directly on load; the lock-gate uses CloudLockStateListener.
        //
        // We still publish a doorLockStatus[] array on the snapshot for
        // compatibility with downstream consumers, but with all-UNAVAILABLE
        // values — the cloud-lock fields on the JSON response carry the
        // authoritative state.
        if (b.doorLockStatus == null) {
            int[] locks = new int[7];
            for (int i = 0; i < 7; i++) locks[i] = -1;
            b.doorLockStatus(locks);
        }
    }

    private void collectSensor(BydVehicleData.Builder b) {
        if (sensorDevice == null) return;
        try {
            Object slope = BydDeviceHelper.callGetter(sensorDevice, "getSlope");
            if (slope instanceof Number) {
                int raw = ((Number) slope).intValue();
                double degrees = Math.toDegrees(Math.atan(raw / 100.0));
                if (degrees >= -60 && degrees <= 60) b.slopeDegrees(degrees);
            }
        } catch (Exception e) {
            logger.debug("collectSensor error: " + e.getMessage());
        }
    }

    private boolean collectEnergy(BydVehicleData.Builder b, boolean socHalSucceeded) {
        if (energyDevice == null) return socHalSucceeded;
        try {
            Object mode = BydDeviceHelper.callGetter(energyDevice, "getEnergyMode");
            if (mode instanceof Number) {
                int em = ((Number) mode).intValue();
                b.energyMode(em);
                // Throttled 1/min. EV/HEV was field-reported broken with NOTHING in the log to
                // show what the car reports, so a device run couldn't distinguish "the HAL
                // refuses our write" from "we mis-decode what it answers".
                long now = System.currentTimeMillis();
                if (now - lastEnergyModeLogMs > 60000) {
                    lastEnergyModeLogMs = now;
                    logger.info("getEnergyMode raw=" + em + " (" + energyModeName(em) + ")");
                }
            }
            // Drive mode is normalized from operation + road-surface readback; the
            // setting-device drive-config getter is only a last fallback.
            int driveConfig = getDriveConfigMode();
            if (driveConfig >= 1 && driveConfig <= 4) {
                b.operationMode(driveConfig);
            }
            
            // SOC fallback: EnergyDevice.getElecPercentageValue() — try if statistic didn't provide SOC
            if (!socHalSucceeded) {
                Object elecPct = BydDeviceHelper.callGetter(energyDevice, "getElecPercentageValue");
                if (elecPct instanceof Number) {
                    double soc = ((Number) elecPct).doubleValue();
                    if (soc > 0 && soc <= 100) {
                        b.socPercent(soc);
                        socHalSucceeded = true;
                        logger.debug("SOC from EnergyDevice: " + soc + "%");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("collectEnergy error: " + e.getMessage());
        }
        return socHalSucceeded;
    }

    private void collectRadar(BydVehicleData.Builder b) {
        if (radarDevice == null) return;
        try {
            Object distances = BydDeviceHelper.callGetter(radarDevice, "getAllRadarDistance");
            if (distances instanceof int[]) b.radarDistances((int[]) distances);
        } catch (Exception e) {
            logger.debug("collectRadar error: " + e.getMessage());
        }
    }

    // ==================== EXTENDED GETTERS ====================

    /**
     * Extended statistic data: OEM SOH, driving time, key battery level.
     * Called from collectAll() (core telemetry consumers need SOH).
     */
    /**
     * Is this raw OEM state-of-health index a real reading?
     *
     * <p>Extracted so the band is pinned by a test and stated once. It gates FOUR places in the
     * SOH cascade below (three tier entry checks plus the publish), and every one of them has to
     * agree: if a single site accepts 0, a healthy pack is published as "SOH 0%" — which fires
     * the BATTERY_SOH automation trigger (it accepts {@code >= 0}) and publishes {@code soh_oem}
     * as 0 over MQTT. A battery is never 0% healthy, so 0 means "no reading" and is exactly the
     * value every failure mode in the cascade lands on.
     *
     * @param raw boxed so tier gates can pass a not-yet-read value directly; null is not plausible
     */
    static boolean isPlausibleOemSohPercent(Integer raw) {
        return raw != null && raw > 0 && raw <= 100;
    }

    private void collectStatisticExtended(BydVehicleData.Builder b) {
        if (statisticDevice == null) return;

        // OEM SOH. This direct battery-health index is SohEstimator's authoritative
        // source on every drivetrain; capacity-derived routes are fallbacks only.
        //
        // 0 IS REJECTED THROUGHOUT, not just filtered at the far end. A battery is never 0%
        // healthy, so a 0 from this index means "no reading", and it is the value every failure
        // mode here lands on: an absent feature, and (before the int-first ladder fix below) a
        // width mismatch leaving BYDAutoEventValue.intValue at its default. Publishing it is not
        // a cosmetic problem — sohPercent feeds an automation trigger that accepts >= 0
        // (BydEvent's BATTERY_SOH) and an MQTT soh_oem field, so a 0 fires user automations and
        // publishes "0% health" for a healthy pack. Each tier below therefore requires > 0
        // before accepting, which also lets a later tier retry what an earlier one couldn't read.
        try {
            Integer sohValue = null;
            try {
                Object result = BydDeviceHelper.callGetter(statisticDevice, "getStatisticBatteryHealthyIndex");
                if (result instanceof Integer) {
                    sohValue = (Integer) result;
                } else if (result instanceof Double) {
                    sohValue = (int) ((Double) result).doubleValue();
                } else if (result instanceof Float) {
                    sohValue = (int) ((Float) result).floatValue();
                }
            } catch (NoSuchMethodError nsme) {
                // method missing — try feature ID below
            } catch (Exception e) {
                if (!(e.getCause() instanceof NoSuchMethodError)) {
                    logger.debug("SOH getter failed: " + e.getMessage());
                }
            }

            // TIER 2 — the get(deviceType, featureId) form, with the STATISTIC device type
            // (1014). The OEM app tries exactly this pairing as its own second attempt when the
            // named getter comes up empty, so it is the form known to answer on trims where
            // that getter is absent. Our generic callGet prefers the get(int[], Class)
            // signature and only falls back to (int,int) when the array form is missing
            // entirely — so on a device exposing BOTH, this specific pairing was never tried.
            if (!isPlausibleOemSohPercent(sohValue)) {
                try {
                    Object r = BydDeviceHelper.callGetWithDeviceType(
                            statisticDevice, STATISTIC_DEVICE_TYPE,
                            BydFeatureIds.STAT_BATTERY_HEALTHY_INDEX);
                    if (r instanceof Number) {
                        int raw = ((Number) r).intValue();
                        if (isPlausibleOemSohPercent(raw)) sohValue = raw;   // 0 = no reading
                    }
                } catch (Exception e) {
                    logger.debug("SOH get(deviceType,id) failed: " + e.getMessage());
                }
            }

            // TIER 3 — the generic feature-ID read, probing primitive widths. Note this used
            // to pass the WRAPPER Integer.class, which the HAL does not match (it dispatches
            // on the exact primitive Class), so this tier could never produce a value.
            //
            // INT-FIRST ladder, for the same load-bearing reason as collectStatTemp: the
            // extraction below is getIntValue, which reads BYDAutoEventValue.intValue, and the
            // HAL populates only the field for the width it was asked for. A Double-first probe
            // that won here would hand back an object whose intValue is still 0 — and 0 passes
            // the 0..100 range check, so this tier would report a healthy pack as "SOH 0%" and
            // (because the canonical display chain prefers a valid OEM value) it would then be
            // rejected as absent rather than corrected. Ask for the width we extract.
            if (!isPlausibleOemSohPercent(sohValue)) {
                try {
                    Object sohVal = BydDeviceHelper.callGetProbing(
                            statisticDevice, BydFeatureIds.STAT_BATTERY_HEALTHY_INDEX, true);
                    if (sohVal != null) {
                        int raw = BydDeviceHelper.getIntValue(sohVal);
                        if (isPlausibleOemSohPercent(raw)) {   // 0 = no reading, never a real pack
                            sohValue = raw;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("SOH feature ID failed: " + e.getMessage());
                }
            }

            // Log the outcome once so a single device capture answers "does the OEM index
            // actually report on this trim?" — which is the open question behind preferring
            // it for canonical SOH. Without this the value's absence is indistinguishable from a
            // read bug.
            if (!loggedOemSohOutcome) {
                loggedOemSohOutcome = true;
                // Distinguish the two "no value" cases: nothing answered at all, versus a tier
                // answering with an out-of-band number (0 included). They look identical
                // downstream but mean different things when diagnosing a trim.
                String outcome;
                if (sohValue == null) {
                    outcome = "NOT REPORTED on this trim (no tier answered)";
                } else if (!isPlausibleOemSohPercent(sohValue)) {
                    outcome = "REJECTED out-of-band raw=" + sohValue
                            + " (0 or >100 means no reading, not a real pack health)";
                } else {
                    outcome = sohValue + "%";
                }
                logger.info("OEM SOH (STATISTIC_BATTERY_HEALTHY_INDEX) first read -> " + outcome);
            }

            if (isPlausibleOemSohPercent(sohValue)) {
                b.sohPercent(sohValue);
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended SOH error: " + e.getMessage());
        }

        // Driving time
        try {
            Object drivingTime = BydDeviceHelper.callGetter(statisticDevice, "getDrivingTimeValue");
            if (drivingTime instanceof Number) {
                double hours = ((Number) drivingTime).doubleValue();
                if (hours >= 0) b.drivingTimeHours(hours);
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended drivingTime error: " + e.getMessage());
        }

        // Key battery level
        try {
            Object keyBatt = BydDeviceHelper.callGetter(statisticDevice, "getKeyBatteryLevel");
            if (keyBatt instanceof Number) {
                b.keyBatteryLevel(((Number) keyBatt).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended keyBattery error: " + e.getMessage());
        }
    }

    /** Extended instrument trip and consumption data consumed by ABRP/MQTT/trips. */
    private void collectInstrumentExtended(BydVehicleData.Builder b) {
        // Per-tyre temperature from InstrumentDevice via feature ID get() calls.
        // Slot mapping from BYDAutoFeatureIds.Instrument:
        //   LF_TYRE_TEMPERATURE, RF_TYRE_TEMPERATURE, LB_TYRE_TEMPERATURE, RB_TYRE_TEMPERATURE
        // These may return null on some firmware but are the correct channel on others.
        try {
            if (instrumentDevice != null) {
                int[] featureIds = BydFeatureIds.INSTRUMENT_TYRE_TEMP_IDS;
                // Order: LF=0, RF=1, LB(RL)=2, RB(RR)=3
                int[] tempResults = new int[4];
                boolean anyValid = false;
                for (int i = 0; i < featureIds.length; i++) {
                    Object result = BydDeviceHelper.callGet(instrumentDevice, featureIds[i], Integer.class);
                    if (result != null) {
                        int raw = BydDeviceHelper.getIntValue(result);
                        if (raw >= -40 && raw <= 125) {
                            tempResults[i] = raw;
                            anyValid = true;
                        } else {
                            tempResults[i] = Integer.MIN_VALUE;
                        }
                    } else {
                        tempResults[i] = Integer.MIN_VALUE;
                    }
                }
                if (anyValid) {
                    // Map: index 0=LF, 1=RF, 2=LB(RL), 3=RB(RR)
                    synchronized (tyreTemperatureCache) {
                        if (tempResults[0] != Integer.MIN_VALUE) tyreTemperatureCache[0] = tempResults[0];
                        if (tempResults[1] != Integer.MIN_VALUE) tyreTemperatureCache[1] = tempResults[1];
                        if (tempResults[2] != Integer.MIN_VALUE) tyreTemperatureCache[2] = tempResults[2];
                        if (tempResults[3] != Integer.MIN_VALUE) tyreTemperatureCache[3] = tempResults[3];
                    }
                    b.tyreTemperature(snapshotTyreTemperatures());
                    if (!loggedInstrumentTyreTemp) {
                        loggedInstrumentTyreTemp = true;
                        logger.info("Tyre temp from InstrumentDevice feature IDs: LF=" + tempResults[0]
                            + " RF=" + tempResults[1] + " RL=" + tempResults[2] + " RR=" + tempResults[3] + "°C");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tyreTemp error: " + e.getMessage());
        }

        // Current trip mileage
        try {
            if (instrumentDevice != null) {
                Object tripMileage = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE, Double.class);
                if (tripMileage != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripMileage);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripMileageKm(val * distanceToKmFactor);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripMileage error: " + e.getMessage());
        }

        // Current trip time
        try {
            if (instrumentDevice != null) {
                Object tripTime = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME, Double.class);
                if (tripTime != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripTime);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripTimeHours(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripTime error: " + e.getMessage());
        }

        // This trip electricity consumption from statistic device
        try {
            if (statisticDevice != null) {
                Object tripElec = BydDeviceHelper.callGet(statisticDevice,
                        BydFeatureIds.STAT_THIS_TRIP_ELEC_CONSUMPTION, Double.class);
                if (tripElec != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripElec);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripConsumptionKwh(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripElecConsumption error: " + e.getMessage());
        }

        // Last 50km power consumption
        try {
            if (instrumentDevice != null) {
                Object last50km = BydDeviceHelper.callGetter(instrumentDevice, "getLast50KmPowerConsume");
                if (last50km instanceof Number) {
                    double val = ((Number) last50km).doubleValue();
                    if (val >= 0) b.last50KmConsumption(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended last50km error: " + e.getMessage());
        }
    }

    // ==================== KEY PROXIMITY ====================
    // Discrete key/fob proximity & authentication state probed from SettingDevice and
    // InstrumentDevice. Methods are read reflectively so missing ones (model variation)
    // don't break collection. Each value is the raw int from the SDK; UNAVAILABLE means
    // the method returned a sentinel (BMS unavailable / invalid) or wasn't present.
    //
    // Logged on every state transition and at most once per 5 minutes regardless,
    // so the log captures fob behaviour during parked / charging / approach windows.

    private volatile int lastKeyStartState = Integer.MIN_VALUE;
    private volatile int lastKeyMissingInd = Integer.MIN_VALUE;
    private volatile int lastKeyBtLowPowerMode = Integer.MIN_VALUE;
    private volatile int lastKeyPowerLowInd = Integer.MIN_VALUE;
    private volatile int lastKeyDetectionReminder = Integer.MIN_VALUE;
    private volatile int lastSmartKeyWarnState = Integer.MIN_VALUE;
    private volatile long lastKeyProbeLogMs = 0;

    private void collectKeyProximity(BydVehicleData.Builder b) {
        int startState = readKeyInt(settingDevice, "getStartKeyState");
        int missingInd = readKeyInt(settingDevice, "getMissKeyInd");
        int btLowPower = readKeyInt(settingDevice, "getIKEYBTLowPowerMode");
        int powerLow = readKeyInt(settingDevice, "getKeyPowerLowInd");
        int detectionRem = readKeyInt(instrumentDevice, "getKeyDetectionReminder");
        int warnState = readKeyInt(instrumentDevice, "getSmartKeySysWarnLightState");

        if (startState != BydVehicleData.UNAVAILABLE) b.keyStartState(startState);
        if (missingInd != BydVehicleData.UNAVAILABLE) b.keyMissingInd(missingInd);
        if (btLowPower != BydVehicleData.UNAVAILABLE) b.keyBtLowPowerMode(btLowPower);
        if (powerLow != BydVehicleData.UNAVAILABLE) b.keyPowerLowInd(powerLow);
        if (detectionRem != BydVehicleData.UNAVAILABLE) b.keyDetectionReminder(detectionRem);
        if (warnState != BydVehicleData.UNAVAILABLE) b.smartKeyWarnState(warnState);

        boolean changed =
            startState != lastKeyStartState
            || missingInd != lastKeyMissingInd
            || btLowPower != lastKeyBtLowPowerMode
            || powerLow != lastKeyPowerLowInd
            || detectionRem != lastKeyDetectionReminder
            || warnState != lastSmartKeyWarnState;

        long now = System.currentTimeMillis();
        boolean heartbeat = now - lastKeyProbeLogMs > 300_000;

        if (changed || heartbeat) {
            lastKeyStartState = startState;
            lastKeyMissingInd = missingInd;
            lastKeyBtLowPowerMode = btLowPower;
            lastKeyPowerLowInd = powerLow;
            lastKeyDetectionReminder = detectionRem;
            lastSmartKeyWarnState = warnState;
            lastKeyProbeLogMs = now;
            logger.info("KeyProbe: startState=" + fmtKeyVal(startState)
                + " missingInd=" + fmtKeyVal(missingInd)
                + " btLowPower=" + fmtKeyVal(btLowPower)
                + " powerLow=" + fmtKeyVal(powerLow)
                + " detectionReminder=" + fmtKeyVal(detectionRem)
                + " smartKeyWarn=" + fmtKeyVal(warnState)
                + " accIsOn=" + accIsOn
                + (changed ? " [CHANGE]" : " [hb]"));
        }
    }

    /**
     * Reflective single-int getter that filters BYD sentinel values
     * (BMS_UNAVAILABLE=65535, INVALID_VALUE=-10011, INVALID_VALUE_2=-10013).
     * Returns BydVehicleData.UNAVAILABLE if the method is missing, the device is
     * null, or the value is a known sentinel.
     */
    private static int readKeyInt(Object device, String methodName) {
        if (device == null) return BydVehicleData.UNAVAILABLE;
        try {
            java.lang.reflect.Method m = device.getClass().getMethod(methodName);
            Object result = m.invoke(device);
            if (!(result instanceof Number)) return BydVehicleData.UNAVAILABLE;
            int v = ((Number) result).intValue();
            if (v == 65535 || v == -10011 || v == -10013) return BydVehicleData.UNAVAILABLE;
            return v;
        } catch (NoSuchMethodException e) {
            return BydVehicleData.UNAVAILABLE;
        } catch (Exception e) {
            return BydVehicleData.UNAVAILABLE;
        }
    }

    private static String fmtKeyVal(int v) {
        return v == BydVehicleData.UNAVAILABLE ? "n/a" : Integer.toString(v);
    }

    /**
     * Extended charging data: charging rest time (time-to-full).
     * Called from collectAll() (every poll, so a live charge keeps it fresh)
     * and collectAllFull(). Acts as the fallback when the instrument feature-ID
     * read in collectInstrument() leaves rest time UNAVAILABLE.
     */
    private void collectChargingExtended(BydVehicleData.Builder b) {
        if (chargingDevice == null) return;

        // Fallback: chargingDevice.getChargingRestTime() when instrument feature IDs
        // didn't populate in collectInstrument(). Checks gun state first — if NONE, skip.
        if (b.chargingRestTimeHours == BydVehicleData.UNAVAILABLE) {
            try {
                if (b.chargingGunState != 1) {
                    Object restTime = BydDeviceHelper.callGetter(chargingDevice, "getChargingRestTime");
                    if (restTime instanceof int[]) {
                        int[] times = (int[]) restTime;
                        if (times.length >= 2) {
                            int hours = times[0];
                            int minutes = times[1];
                            if (hours != 255 && minutes != 255 && hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                                b.chargingRestTimeHours(hours);
                                b.chargingRestTimeMinutes(minutes);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("collectChargingExtended restTime error: " + e.getMessage());
            }
        }
    }

    /**
     * Extended bodywork data: steering angle, auto system state, 12V level, sunroof, sunshade.
     * Called from collectAllFull() only (display-only, on-demand).
     */
    /**
     * Read the live steering-wheel angle into the builder. Extracted so the periodic
     * poll ({@link #collectAll}) can refresh it every cycle — {@code
     * collectBodyworkExtended} runs ONLY at init ({@code collectAllFull}), and the
     * live listener {@code handleSteeringAngleChanged} is unwired, so without this the
     * angle was seeded once at boot (wheel centered → 0) and carried forward forever:
     * the "steeringAngle" automation event never saw a transition and could never
     * fire. Guards the SDK sentinel + the same ±780° sanity range the listener uses.
     */
    private void collectSteeringAngle(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;
        try {
            Object steering = BydDeviceHelper.callGetter(bodyworkDevice, "getSteeringWheelValue", 1);
            if (steering instanceof Number) {
                double angle = ((Number) steering).doubleValue();
                // Guard the SDK not-available sentinel and clamp to the physical range
                // (mirrors handleSteeringAngleChanged): a bogus value would false-fire
                // a steering-angle automation.
                if (angle != BydFeatureIds.SDK_NOT_AVAILABLE && angle >= -780 && angle <= 780) {
                    b.steeringAngleDegrees(angle);
                }
            }
        } catch (Exception e) {
            logger.debug("collectSteeringAngle error: " + e.getMessage());
        }
    }

    private void collectBodyworkExtended(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;

        // Steering wheel angle (shared with the periodic poll via collectSteeringAngle).
        collectSteeringAngle(b);

        // Auto system state (0=normal, 1=set_secure, 2=start_secure)
        try {
            Object autoState = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoSystemState");
            if (autoState instanceof Number) {
                b.autoSystemState(((Number) autoState).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended autoSystemState error: " + e.getMessage());
        }

        // 12V battery voltage level (LOW/NORMAL/INVALID)
        try {
            Object battLevel = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryVoltageLevel");
            if (battLevel instanceof Number) {
                b.battery12vLevel(((Number) battLevel).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended battery12vLevel error: " + e.getMessage());
        }

        // Sunroof state (if available)
        try {
            Object sunroof = BydDeviceHelper.callGetter(bodyworkDevice, "getSunroofState");
            if (sunroof instanceof Number) {
                b.sunroofState(((Number) sunroof).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunroofState error: " + e.getMessage());
        }

        // Front wiper state (raw). Per the OEM firmware the wiper getters live on the
        // BODYWORK device (not a dedicated wiper device). Raw int; consumers threshold
        // it (0/off vs any active level). Populates the pre-existing wiperState field.
        try {
            Object wiper = BydDeviceHelper.callGetter(bodyworkDevice, "getFrontWiperState");
            if (wiper instanceof Number) {
                b.wiperState(((Number) wiper).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended frontWiperState error: " + e.getMessage());
        }
        // Auto-wiper (rain-sensing wipe) enabled — the closest "it's raining" proxy this
        // platform exposes; there is no rain-intensity sensor. getAutoWiperState: 1=on.
        try {
            Object autoWiper = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoWiperState");
            if (autoWiper instanceof Number) {
                b.autoWiperState(((Number) autoWiper).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended autoWiperState error: " + e.getMessage());
        }

        // Sunroof position (if available)
        try {
            Object sunroofPos = BydDeviceHelper.callGetter(bodyworkDevice, "getSunroofPosition");
            if (sunroofPos instanceof Number) {
                b.sunroofPosition(((Number) sunroofPos).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunroofPosition error: " + e.getMessage());
        }

        // Sunshade panel percent
        try {
            Object sunshade = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODY_SUNSHADE_PANEL_PERCENT, Integer.class);
            if (sunshade != null) {
                int val = BydDeviceHelper.getIntValue(sunshade);
                if (val >= 0 && val <= 100) b.sunshadePercent(val);
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunshade error: " + e.getMessage());
        }
    }

    /**
     * Extended engine data: coolant level, oil level, engine code.
     * Called from collectAllFull() only (display-only, on-demand).
     */
    /**
     * Air quality PM2.5 (µg/m³) from {@code BYDAutoPM2p5Device.getPM2p5Value()}, which returns
     * an {@code int[]} of {inside, outside} — the same call the reference apps make.
     *
     * <p>Previously PM2.5 came ONLY from the BYD cloud snapshot, so the local reading (and the
     * {@code pm25} automation signal) were empty whenever the car was offline or no cloud
     * account was configured. The cloud merge already defers to the SDK value when present
     * ("only if SDK returned UNAVAILABLE"), so populating it here makes local the primary
     * source and leaves cloud as the fallback, with no change to that merge.
     *
     * <p>Negative slots are left UNAVAILABLE rather than published: the HAL fills an unreadable
     * sensor with a sentinel, and 0 µg/m³ is a legitimate reading that must not be invented.
     */
    private void collectPm25(BydVehicleData.Builder b) {
        if (pm25Device == null) return;
        try {
            Object raw = BydDeviceHelper.callGetter(pm25Device, "getPM2p5Value");
            if (!(raw instanceof int[])) return;
            int[] pm = (int[]) raw;
            if (pm.length > 0 && pm[0] >= 0) b.pm25Inside(pm[0]);
            if (pm.length > 1 && pm[1] >= 0) b.pm25Outside(pm[1]);
        } catch (Exception e) {
            logger.debug("collectPm25 error: " + e.getMessage());
        }
    }

    private void collectEngineExtended(BydVehicleData.Builder b) {
        if (engineDevice == null) return;

        // Engine coolant level. BYD SDK constants: 0=NORMAL, 1=LOW.
        // Some firmwares return -1 or sentinel when the value is unavailable.
        Integer coolantRaw = null;
        try {
            Object coolant = BydDeviceHelper.callGetter(engineDevice, "getEngineCoolantLevel");
            if (coolant instanceof Number) {
                coolantRaw = ((Number) coolant).intValue();
                b.engineCoolantLevel(coolantRaw);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended coolant error: " + e.getMessage());
        }

        // Oil level from Engine device. SDK range 0-254 (dipstick scale).
        // 0 may be a "no value" sentinel rather than empty tank — needs
        // verification against the cluster's own oil-level UI.
        Integer engineOilRaw = null;
        try {
            Object oil = BydDeviceHelper.callGetter(engineDevice, "getOilLevel");
            if (oil instanceof Number) {
                engineOilRaw = ((Number) oil).intValue();
                b.oilLevel(engineOilRaw);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended oilLevel error: " + e.getMessage());
        }

        // Parallel reading from the Setting device (different code path).
        // Setting.getEngineOilLevel exists on most BYD firmwares — pulling it
        // alongside the Engine device version lets us cross-check which one
        // is actually populated on this car. Logged for diagnostics only;
        // not surfaced on the snapshot until we know which is canonical.
        Integer settingOilRaw = null;
        try {
            if (settingDevice != null) {
                Object oil = BydDeviceHelper.callGetter(settingDevice, "getEngineOilLevel");
                if (oil instanceof Number) settingOilRaw = ((Number) oil).intValue();
            }
        } catch (Exception ignored) {}

        // "Low oil indicator" lamp from Setting device — when this is set, the
        // dashboard is already showing the warning. Useful as a sanity check.
        Integer lowOilIndRaw = null;
        try {
            if (settingDevice != null) {
                Object ind = BydDeviceHelper.callGetter(settingDevice, "getLowOilInd");
                if (ind instanceof Number) lowOilIndRaw = ((Number) ind).intValue();
            }
        } catch (Exception ignored) {}

        logEngineFluidsIfChanged(coolantRaw, engineOilRaw, settingOilRaw, lowOilIndRaw);

        // Engine code (e.g. "BYD473QF")
        try {
            Object code = BydDeviceHelper.callGetter(engineDevice, "getEngineCode");
            if (code instanceof String) {
                b.engineCode((String) code);
            } else if (code != null) {
                String codeStr = BydDeviceHelper.getStringValue(code);
                if (codeStr != null && !codeStr.isEmpty()) b.engineCode(codeStr);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended engineCode error: " + e.getMessage());
        }
    }

    // Last-seen engine-fluid readings — change-only logging plus a 5-min
    // heartbeat so a healthy car doesn't spam the log but transitions
    // (e.g. coolant drops to LOW after a leak develops) surface immediately.
    private volatile Integer lastCoolantRaw = null;
    private volatile Integer lastEngineOilRaw = null;
    private volatile Integer lastSettingOilRaw = null;
    private volatile Integer lastLowOilIndRaw = null;
    private volatile long lastEngineFluidsLogMs = 0;
    private volatile boolean firstEngineFluidsLog = true;

    private void logEngineFluidsIfChanged(Integer coolantRaw, Integer engineOilRaw,
                                          Integer settingOilRaw, Integer lowOilIndRaw) {
        boolean changed = firstEngineFluidsLog
                || !java.util.Objects.equals(coolantRaw, lastCoolantRaw)
                || !java.util.Objects.equals(engineOilRaw, lastEngineOilRaw)
                || !java.util.Objects.equals(settingOilRaw, lastSettingOilRaw)
                || !java.util.Objects.equals(lowOilIndRaw, lastLowOilIndRaw);
        long now = System.currentTimeMillis();
        boolean heartbeat = now - lastEngineFluidsLogMs > 300_000;
        if (!changed && !heartbeat) return;
        firstEngineFluidsLog = false;
        lastCoolantRaw = coolantRaw;
        lastEngineOilRaw = engineOilRaw;
        lastSettingOilRaw = settingOilRaw;
        lastLowOilIndRaw = lowOilIndRaw;
        lastEngineFluidsLogMs = now;
        logger.info("EngineFluids: coolant=" + fmtFluid(coolantRaw)
                + " (0=NORMAL,1=LOW)"
                + " engineOil=" + fmtFluid(engineOilRaw) + " (0-254)"
                + " settingOil=" + fmtFluid(settingOilRaw)
                + " lowOilInd=" + fmtFluid(lowOilIndRaw)
                + (changed ? " [CHANGE]" : " [hb]"));
    }

    private static String fmtFluid(Integer v) {
        return v == null ? "n/a" : v.toString();
    }

    // ==================== CLOUD DATA MERGE ====================

    /**
     * Merge cloud data as FALLBACK — only fills fields where SDK returned no value or where vehicle is parked/sleeping.
     * SDK is always primary (real-time 5s poll). Cloud fills gaps and keeps parked/charging data fresh.
     */
    void mergeCloudData(BydVehicleData.Builder b, boolean cabinTempHalSucceeded,
                        boolean socHalSucceeded, boolean rangeHalSucceeded,
                        boolean fuelHalSucceeded) {
        try {
            com.overdrive.app.byd.cloud.BydCloudConfig config =
                    com.overdrive.app.byd.cloud.BydCloudConfig.fromUnifiedConfig();
            if (!config.cloudDataMerge) return;

            com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            if (!provider.isTelemetryFresh()) return;

            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
            if (cs == null) return;

            mergeCloudDataSnapshot(b, cs, accIsOn, cabinTempHalSucceeded,
                    socHalSucceeded, rangeHalSucceeded, fuelHalSucceeded);
        } catch (Exception e) {
            logger.debug("mergeCloudData error: " + e.getMessage());
        }
    }

    static void mergeCloudDataSnapshot(BydVehicleData.Builder b,
                                       com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs,
                                       boolean accIsOn,
                                       boolean cabinTempHalSucceeded,
                                       boolean socHalSucceeded,
                                       boolean rangeHalSucceeded,
                                       boolean fuelHalSucceeded) {
        if (cs == null) return;
        try {
            // SOC — merge if SDK didn't provide a fresh valid reading this cycle, or if car is parked/asleep (ACC OFF)
            if ((!socHalSucceeded || !accIsOn || Double.isNaN(b.socPercent)) && cs.hasSoc()) {
                b.socPercent(cs.socPercent);
            }

            // EV range — merge if SDK didn't provide a fresh valid reading this cycle, or if parked/asleep (ACC OFF)
            if ((!rangeHalSucceeded || !accIsOn || b.elecRangeKm == BydVehicleData.UNAVAILABLE) && cs.hasElecRange()) {
                b.elecRangeKm(cs.elecRangeKm);
            }

            // Fuel range / percent (PHEV) — merge if SDK didn't provide a fresh valid reading this cycle, or if parked/asleep (ACC OFF)
            if (!fuelHalSucceeded || !accIsOn || b.fuelRangeKm == BydVehicleData.UNAVAILABLE) {
                if (cs.hasFuelRange()) b.fuelRangeKm(cs.fuelRangeKm);
            }
            if (!fuelHalSucceeded || !accIsOn || Double.isNaN(b.fuelPercent)) {
                if (cs.hasFuelPercent()) b.fuelPercent(cs.fuelPercent);
            }

            // Charging state — if SDK returned UNAVAILABLE, or if hardware returned READY(0)
            // but cloud confirms active CHARGING(1) while plugged in
            if (cs.hasChargingState()) {
                int sdkState = cs.getChargingStateAsSdk();
                if (sdkState >= 0) {
                    if (b.chargingState == BydVehicleData.UNAVAILABLE) {
                        b.chargingState(sdkState);
                    } else if (b.chargingState == 0 && sdkState == 1 && (b.chargingGunState >= 2 || cs.chargingState == 1)) {
                        b.chargingState(1);
                        if (b.chargingGunState < 2) {
                            b.chargingGunState(2);
                        }
                    }
                }
            }

            // Charge ETA — only if SDK has nothing
            if ((b.chargingRestTimeHours == BydVehicleData.UNAVAILABLE || b.chargingRestTimeHours == 0) && cs.hasRemainingHours())
                b.chargingRestTimeHours(cs.remainingHours);
            if ((b.chargingRestTimeMinutes == BydVehicleData.UNAVAILABLE || b.chargingRestTimeMinutes == 0) && cs.hasRemainingMinutes())
                b.chargingRestTimeMinutes(cs.remainingMinutes);

            // Temperatures — cabin cloud data may replace a carried-forward HAL value when this
            // poll's HAL read failed. Presence alone is insufficient because toBuilder() carries
            // the previous value and observation timestamp forward.
            // The cloud cabin value gets the SAME validity band as the HAL one
            // (isPlausibleCabinTempC): hasInsideTemp() only rejects a low sentinel and has no
            // UPPER bound, so a cloud tempInCar of 255/65535 would otherwise be published
            // verbatim as inside_temp / climate.insideTempC and into the `temperature` automation
            // event — where it latches and permanently satisfies every "cabin above X" rule.
            // Validating here (rather than re-adding a clip at each consumer) keeps ONE definition
            // of a plausible cabin temperature for both sources.
            if (!cabinTempHalSucceeded && cs.insideTempObservedAt > b.insideTempReadAt
                    && cs.hasFreshInsideTemp()
                    && isPlausibleCabinTempC((int) Math.round(cs.insideTempC))) {
                b.insideTempC(cs.insideTempC, cs.insideTempObservedAt);
            }
            // Same band for the cloud OUTSIDE value, for exactly the reason above: hasOutsideTemp()
            // has no bounds at all, and tempOutCar is parsed two lines from tempInCar in the very
            // same payload — so the sentinel the cabin gate blocks would otherwise walk in here.
            // A latched 255 would drive OUTSIDE_TEMPERATURE and permanently satisfy every
            // "outside above X" rule, plus HA's ext_temp. Cabin temperature remains separate
            // and is never synthesized from ambient data.
            if (Double.isNaN(b.outsideTempC) && cs.hasOutsideTemp()
                    && isPlausibleOutsideTempC((int) Math.round(cs.outsideTempC))) {
                b.outsideTempC(cs.outsideTempC);
            }

            // Odometer — only if SDK returned UNAVAILABLE
            if (b.totalMileageKm == BydVehicleData.UNAVAILABLE && cs.hasTotalMileage())
                b.totalMileageKm(cs.totalMileageKm);

            // Air quality — only if SDK returned UNAVAILABLE
            if (b.pm25Inside == BydVehicleData.UNAVAILABLE && cs.hasPm25Inside())
                b.pm25Inside((int) cs.pm25Inside);
            if (b.pm25Outside == BydVehicleData.UNAVAILABLE && cs.hasPm25Outside())
                b.pm25Outside((int) cs.pm25Outside);

            // Doors / Locks — merge if SDK doorLockStatus is unavailable or all sentinels
            if (cs.hasValidLockState()) {
                boolean sdkLocksEmpty = (b.doorLockStatus == null);
                if (!sdkLocksEmpty) {
                    boolean allInvalid = true;
                    for (int s : b.doorLockStatus) {
                        if (s == 1 || s == 2) { allInvalid = false; break; }
                    }
                    sdkLocksEmpty = allInvalid;
                }
                if (sdkLocksEmpty) {
                    b.doorLockStatus(cs.getDoorLockStatusAsArray());
                }
            }

            // Windows — merge if SDK windowOpenPercent is unavailable or all sentinels
            if (cs.hasWindows()) {
                boolean sdkWindowsEmpty = (b.windowOpenPercent == null);
                if (!sdkWindowsEmpty) {
                    boolean allInvalid = true;
                    for (int p : b.windowOpenPercent) {
                        if (p >= 0 && p <= 100) { allInvalid = false; break; }
                    }
                    sdkWindowsEmpty = allInvalid;
                }
                if (sdkWindowsEmpty) {
                    b.windowOpenPercent(cs.getWindowOpenPercentAsArray());
                }
            }

        } catch (Exception e) {
            logger.debug("Cloud data merge error: " + e.getMessage());
        }
    }

    // ==================== LISTENER REGISTRATION ====================

    private void registerAllListeners() {
        logger.info("Registering listeners...");
        int count = 0;
        registerOkThisPass.clear();   // per-pass; markRegistered() consults it at the end
        // PER-HANDLE RE-ENTRY GUARD. init() is re-entered on the ACC-ON path and there is no
        // unregister path in this class, so re-registering on a handle we are already attached to
        // stacks a duplicate HAL callback — and the consumers include the door/charging notifiers,
        // so pushes would fire twice. Rather than guard each of the ~25 call sites below (easy to
        // miss one, as the speedDevice fingerprint omission showed), null out the locals for handles
        // already registered: every helper null-checks and returns false, so those sites become
        // no-ops while any handle that FAILED last time is retried normally.
        //
        // Deliberately snapshot into locals instead of mutating the fields — the fields must keep
        // pointing at the real devices for the polling reads.
        Object bodyworkDevice = alreadyRegistered(this.bodyworkDevice) ? null : this.bodyworkDevice;
        Object speedDevice = alreadyRegistered(this.speedDevice) ? null : this.speedDevice;
        Object engineDevice = alreadyRegistered(this.engineDevice) ? null : this.engineDevice;
        Object statisticDevice = alreadyRegistered(this.statisticDevice) ? null : this.statisticDevice;
        Object chargingDevice = alreadyRegistered(this.chargingDevice) ? null : this.chargingDevice;
        Object instrumentDevice = alreadyRegistered(this.instrumentDevice) ? null : this.instrumentDevice;
        Object doorLockDevice = alreadyRegistered(this.doorLockDevice) ? null : this.doorLockDevice;
        Object tyreDevice = alreadyRegistered(this.tyreDevice) ? null : this.tyreDevice;
        Object energyDevice = alreadyRegistered(this.energyDevice) ? null : this.energyDevice;
        Object otaDevice = alreadyRegistered(this.otaDevice) ? null : this.otaDevice;
        Object sensorDevice = alreadyRegistered(this.sensorDevice) ? null : this.sensorDevice;
        Object acDevice = alreadyRegistered(this.acDevice) ? null : this.acDevice;
        Object lightDevice = alreadyRegistered(this.lightDevice) ? null : this.lightDevice;
        Object adasDevice = alreadyRegistered(this.adasDevice) ? null : this.adasDevice;
        Object radarDevice = alreadyRegistered(this.radarDevice) ? null : this.radarDevice;
        Object powerDevice = alreadyRegistered(this.powerDevice) ? null : this.powerDevice;
        Object settingDevice = alreadyRegistered(this.settingDevice) ? null : this.settingDevice;
        Object safetyBeltDevice = alreadyRegistered(this.safetyBeltDevice)
                ? null : this.safetyBeltDevice;

        // Bodywork: use the typed listener so onDoorStateChanged /
        // onWindowStateChanged / onWindowOpenPercentChanged actually dispatch.
        // The generic IBYDAutoListener registration succeeds but never fires
        // those device-specific callbacks.
        if (noteRegisterOk(bodyworkDevice, BydDeviceHelper.registerBodyworkListener(bodyworkDevice, this::onBodyworkCallback))) {
            logger.info("  Bodywork listener registered (typed)");
            count++;
        } else if (noteRegisterOk(bodyworkDevice, BydDeviceHelper.registerListener(bodyworkDevice, this::onBodyworkCallback))) {
            // Fallback for stub/older firmwares that only expose the generic interface.
            logger.info("  Bodywork listener registered (generic fallback — door/window callbacks may not fire)");
            count++;
        }
        if (noteRegisterOk(speedDevice, BydDeviceHelper.registerListener(speedDevice, this::onGenericCallback))) {
            logger.info("  Speed listener registered");
            count++;
        }
        // SKIP gearbox listener — BYDAutoGearboxDevice.learningEPB() crashes with
        // "Given calling package android does not match caller's uid 2000" when running
        // as shell (UID 2000). The crash kills the BYD device manager's HandlerThread,
        // which cascades into GL thread hang → watchdog kill → daemon restart loop.
        // Gear data is collected via polling (collectAll) and GearMonitor handles gear changes.
        // if (noteRegisterOk(gearboxDevice, BydDeviceHelper.registerListener(gearboxDevice, this::onGenericCallback))) {
        //     logger.info("  Gearbox listener registered");
        //     count++;
        // }
        // Charging: prefer typed registration. The generic IBYDAutoListener
        // proxy used to register here misses onBatteryManagementDeviceStateChanged
        // on some PHEV firmwares, which is the root of the inconsistent
        // charging-detection bug (BMS state would freeze at 15 IDLE while
        // charging). Typed listener guarantees AC-charging start is seen.
        if (chargingDevice != null) {
            final Object listenerDevice = chargingDevice;
            final long listenerGeneration = chargingListenerGeneration.incrementAndGet();
            BydDeviceHelper.ListenerCallback callback = (method, args) ->
                    onChargingCallback(listenerDevice, listenerGeneration, method, args);
            if (noteRegisterOk(chargingDevice,
                    BydDeviceHelper.registerChargingListener(chargingDevice, callback))) {
                activeChargingListenerDevice = listenerDevice;
                activeChargingListenerGeneration = listenerGeneration;
                logger.info("  Charging listener registered (typed)");
                count++;
            } else if (noteRegisterOk(chargingDevice,
                    BydDeviceHelper.registerListener(chargingDevice, callback))) {
                activeChargingListenerDevice = listenerDevice;
                activeChargingListenerGeneration = listenerGeneration;
                logger.info("  Charging listener registered (generic fallback)");
                count++;
            }
        }
        // Engine listener: typed for onEngineCoolantLevelChanged /
        // onOilLevelChanged. Without this, engine fluid status is only
        // refreshed by the one-shot collectAllFull at init.
        if (engineDevice != null) {
            final Object listenerDevice = engineDevice;
            final long listenerGeneration = engineListenerGeneration.incrementAndGet();
            BydDeviceHelper.ListenerCallback callback = (method, args) ->
                    onEngineCallback(listenerDevice, listenerGeneration, method, args);
            if (noteRegisterOk(engineDevice,
                    BydDeviceHelper.registerEngineListener(engineDevice, callback))) {
                activeEngineListenerDevice = listenerDevice;
                activeEngineListenerGeneration = listenerGeneration;
                logger.info("  Engine listener registered (typed)");
                count++;
            } else if (noteRegisterOk(engineDevice,
                    BydDeviceHelper.registerListener(engineDevice, callback))) {
                activeEngineListenerDevice = listenerDevice;
                activeEngineListenerGeneration = listenerGeneration;
                logger.info("  Engine listener registered (generic fallback)");
                count++;
            }
        }
        // Instrument listener: MUST be typed. onExternalChargingPowerChanged
        // (live charging power in kW) is a concrete method on
        // AbsBYDAutoInstrumentListener, not on the IBYDAutoListener marker
        // interface — the generic Proxy path can never receive it (the HAL
        // dispatches it only to typed AbsBYDAutoInstrumentListener subscribers),
        // so charging power silently never arrived and the UI fell back to a
        // nominal estimate. Typed first; generic fallback kept for any firmware
        // that only exposes the bare 1-arg registerListener.
        if (instrumentDevice != null) {
            final Object listenerDevice = instrumentDevice;
            final long listenerGeneration = instrumentListenerGeneration.incrementAndGet();
            BydDeviceHelper.ListenerCallback callback = (method, args) ->
                    onInstrumentCallback(listenerDevice, listenerGeneration, method, args);
            if (noteRegisterOk(instrumentDevice,
                    BydDeviceHelper.registerInstrumentListener(instrumentDevice, callback))) {
                activeInstrumentListenerDevice = listenerDevice;
                activeInstrumentListenerGeneration = listenerGeneration;
                logger.info("  Instrument listener registered (typed — external charging power)");
                count++;
            } else if (noteRegisterOk(instrumentDevice,
                    BydDeviceHelper.registerListener(instrumentDevice, callback))) {
                activeInstrumentListenerDevice = listenerDevice;
                activeInstrumentListenerGeneration = listenerGeneration;
                logger.info("  Instrument listener registered (generic fallback)");
                count++;
            }
        }
        // Passenger occupancy is a concrete SafetyBelt listener callback, not a method on the
        // generic IBYDAutoListener interface. It remains useful on trims where the synchronous
        // getPassengerStatus getter returns INVALID.
        final Object listenerDevice = safetyBeltDevice;
        if (listenerDevice != null) {
            final long listenerGeneration = safetyBeltListenerGeneration.incrementAndGet();
            if (noteRegisterOk(listenerDevice, BydDeviceHelper.registerSafetyBeltListener(listenerDevice,
                    (method, args) -> onSafetyBeltCallback(listenerDevice, listenerGeneration, method, args)))) {
                activeSafetyBeltListenerDevice = listenerDevice;
                activeSafetyBeltListenerGeneration = listenerGeneration;
                logger.info("  SafetyBelt listener registered (typed — passenger occupancy)");
                count++;
            }
        }
        // Statistic listener MUST be typed to receive onElecPercentageChanged(double)
        // — the DECIMAL display SoC. It's a concrete method on
        // AbsBYDAutoStatisticListener, not on the bare IBYDAutoListener marker
        // interface, so the generic Proxy path can never deliver it (same class of
        // bug as onExternalChargingPowerChanged above): SoC then only advanced on the
        // slow getElecPercentageValue() poll (integer on this trim). Typed first;
        // generic fallback kept for firmware exposing only the bare registerListener.
        if (noteRegisterOk(statisticDevice, BydDeviceHelper.registerStatisticListener(statisticDevice, this::onGenericCallback))) {
            logger.info("  Statistic listener registered (typed — decimal SoC)");
            count++;
        } else if (noteRegisterOk(statisticDevice, BydDeviceHelper.registerListener(statisticDevice, this::onGenericCallback))) {
            logger.info("  Statistic listener registered (generic fallback)");
            count++;
        }
        if (noteRegisterOk(lightDevice, BydDeviceHelper.registerListener(lightDevice, this::onLightsCallback))) {
            logger.info("  Light listener registered");
            count++;
        }
        // ADAS listener registered WITH a feature-ID filter: on this HAL family
        // onDataEventChanged only carries feature ids when an int[] filter is
        // supplied, so the bare registration never delivered the blind-spot
        // warnings. The filter includes SLW so the previously-working switch event
        // is unaffected. registerListener(ids,…) falls back internally to the
        // unfiltered form on firmware lacking the 2-arg overload, so a trim that
        // only supports the old path behaves exactly as before.
        if (noteRegisterOk(adasDevice, BydDeviceHelper.registerListener(adasDevice, resolvedIds(ADAS_EVENT_FILTER), this::onAdasCallback))) {
            logger.info("  Adas listener registered (filtered — blind-spot warnings)");
            count++;
        }
        if (noteRegisterOk(settingDevice, BydDeviceHelper.registerListener(settingDevice, this::onSettingsCallback))) {
            logger.info("  Settings listener registered");
            count++;
        }
        if (noteRegisterOk(radarDevice, BydDeviceHelper.registerListener(radarDevice, this::onGenericCallback))) {
            logger.info("  Radar listener registered");
            count++;
        }
        if (noteRegisterOk(otaDevice, BydDeviceHelper.registerListener(otaDevice, this::onOtaCallback))) {
            logger.info("  OTA listener registered");
            count++;
        }

        // Display-only devices — no periodic polling, listener-driven only.
        // These update the snapshot when BYD HAL pushes CAN bus state changes.
        //
        // DoorLock requires the typed AbsBYDAutoDoorLockListener — the generic
        // IBYDAutoListener registration succeeds but never receives
        // onDoorLockStatusChanged. This was the root cause of stale lock data.
        if (noteRegisterOk(doorLockDevice, BydDeviceHelper.registerDoorLockListener(doorLockDevice, this::onDoorLockCallback))) {
            logger.info("  DoorLock listener registered (typed)");
            count++;
        } else if (noteRegisterOk(doorLockDevice, BydDeviceHelper.registerListener(doorLockDevice, this::onDoorLockCallback))) {
            logger.info("  DoorLock listener registered (generic fallback — lock callbacks may not fire)");
            count++;
        }
        if (noteRegisterOk(tyreDevice, BydDeviceHelper.registerTyreListener(tyreDevice, this::onTyreCallback))) {
            logger.info("  Tyre listener registered (typed)");
            count++;
        } else if (noteRegisterOk(tyreDevice, BydDeviceHelper.registerListener(tyreDevice, this::onDisplayCallback))) {
            logger.info("  Tyre listener registered (generic fallback)");
            count++;
        }
        if (noteRegisterOk(acDevice, BydDeviceHelper.registerListener(acDevice, this::onDisplayCallback))) {
            logger.info("  AC listener registered");
            count++;
        }
        if (noteRegisterOk(sensorDevice, BydDeviceHelper.registerListener(sensorDevice, this::onDisplayCallback))) {
            logger.info("  Sensor listener registered");
            count++;
        }
        if (energyDevice != null) {
            final Object energyListenerDevice = energyDevice;
            if (noteRegisterOk(energyDevice, BydDeviceHelper.registerEnergyListener(
                    energyDevice,
                    (method, args) -> onEnergyCallback(energyListenerDevice, method, args)))) {
                logger.info("  Energy listener registered (typed)");
                count++;
            } else if (noteRegisterOk(energyDevice,
                    BydDeviceHelper.registerListener(energyDevice,
                            (method, args) -> onEnergyCallback(
                                    energyListenerDevice, method, args)))) {
                logger.info("  Energy listener registered (generic diagnostic fallback)");
                count++;
            }
        }
        if (noteRegisterOk(powerDevice, BydDeviceHelper.registerListener(powerDevice, this::onDisplayCallback))) {
            logger.info("  Power listener registered");
            count++;
        }
        if (noteRegisterOk(collectDataDevice, BydDeviceHelper.registerCollectDataListener(collectDataDevice, this::onCollectDataCallback))) {
            logger.info("  CollectData listener registered (HV Voltage/Current + Motor RPM)");
            count++;
        }

        // Record which handles are now attached, so a re-init skips exactly these and retries the
        // rest. A handle whose registration FAILED is deliberately not recorded — it must be
        // retried, which is the whole point of tracking per handle rather than per batch.
        //
        // NOTE: this marks a handle as registered if it was non-null on entry (i.e. not already
        // skipped) and at least one register attempt for it ran. The helpers return false on
        // failure, but the call sites above discard that boolean in several branches, so this is
        // intentionally conservative in the SAFE direction: it can only over-record a handle that
        // was genuinely attempted while non-null, and the alternative (re-attempting everything
        // every init) is the duplicate-callback bug.
        markRegistered(this.bodyworkDevice, bodyworkDevice);
        markRegistered(this.speedDevice, speedDevice);
        markRegistered(this.engineDevice, engineDevice);
        markRegistered(this.statisticDevice, statisticDevice);
        markRegistered(this.chargingDevice, chargingDevice);
        markRegistered(this.instrumentDevice, instrumentDevice);
        markRegistered(this.doorLockDevice, doorLockDevice);
        markRegistered(this.tyreDevice, tyreDevice);
        markRegistered(this.energyDevice, energyDevice);
        markRegistered(this.otaDevice, otaDevice);
        markRegistered(this.sensorDevice, sensorDevice);
        markRegistered(this.acDevice, acDevice);
        markRegistered(this.lightDevice, lightDevice);
        markRegistered(this.adasDevice, adasDevice);
        markRegistered(this.radarDevice, radarDevice);
        markRegistered(this.powerDevice, powerDevice);
        markRegistered(this.settingDevice, settingDevice);
        markRegistered(this.safetyBeltDevice, safetyBeltDevice);
        markRegistered(this.collectDataDevice, collectDataDevice);
        logger.info("Listeners registered: " + count
                + " (tracked handles: " + registeredHandles.size() + ")");
    }

    private void onSafetyBeltCallback(Object sourceDevice, long generation, String method, Object[] args) {
        long lifecycleGeneration = callbackLifecycleGeneration.get();
        if (!isActiveSafetyBeltListener(sourceDevice, generation, lifecycleGeneration)) {
            return;
        }

        if ("onSafetyBeltStatusChanged".equals(method)) {
            int passengerState = args != null && args.length >= 2
                    ? normalizePassengerSeatbeltCallback(args[0], args[1])
                    : BydVehicleData.UNAVAILABLE;
            if (passengerState != BydVehicleData.UNAVAILABLE) {
                boolean changed = passengerSeatbeltTracker.recordCallback(passengerState);
                invalidateSeatbeltPairMemo();
                if (changed) {
                    logger.info("onSafetyBeltStatusChanged: front passenger="
                            + (passengerState == 1 ? "buckled" : "unbuckled"));
                }
                try {
                    if (anyReferenced(
                            com.overdrive.app.automation.condition.BydEvent.SEATBELT_PASSENGER)) {
                        com.overdrive.app.automation.condition.BydEvent
                                .publishPassengerSeatbeltEdge(passengerState);
                    }
                } catch (Throwable t) {
                    logger.debug("passenger seatbelt callback publish error: " + t.getMessage());
                }
            }
            return;
        }

        if (!"onPassengerStatusChanged".equals(method) || args == null || args.length < 2
                || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return;
        }
        double area = ((Number) args[0]).doubleValue();
        int state = normalizePassengerStatus(args[1]);
        if (area != OCCUPANT_AREA_FRONT_PASSENGER || state == BydVehicleData.UNAVAILABLE) {
            return;
        }

        synchronized (passengerOccupancyPublishLock) {
            if (!isActiveSafetyBeltListener(
                    sourceDevice, generation, lifecycleGeneration)) return;

            // Prefer a live direct-sensor read when this trim exposes it, but keep the documented
            // callback state when the getter returns INVALID/UNAVAILABLE. Do NOT replace a
            // callback with the belt/reminder estimate: the callback is an actual sensor value.
            try {
                int live = directFrontPassengerOccupancy();
                if (live != BydVehicleData.UNAVAILABLE) state = live;
            } catch (Throwable t) {
                logger.debug("onPassengerStatusChanged live re-read failed: " + t.getMessage());
            }

            if (!isActiveSafetyBeltListener(
                    sourceDevice, generation, lifecycleGeneration)) return;
            long sampleGeneration = passengerOccupancySampleGeneration.incrementAndGet();
            logger.info("onPassengerStatusChanged: front passenger="
                    + (state == 1 ? "occupied" : "empty"));
            publishPassengerOccupancySample(sampleGeneration, state);
        }
    }

    /**
     * Publish one ordered, validated passenger sample. The caller may already hold this
     * reentrant lock; keeping the guard here makes future sources safe by default.
     */
    private void publishPassengerOccupancySample(long sampleGeneration, int state) {
        synchronized (passengerOccupancyPublishLock) {
            if (sampleGeneration != passengerOccupancySampleGeneration.get()
                    || !anyReferenced(com.overdrive.app.automation.condition.BydEvent.OCCUPANT_PASSENGER)) {
                return;
            }
            try {
                com.overdrive.app.automation.condition.BydEvent.publishPassengerOccupancy(state);
            } catch (Throwable t) {
                logger.debug("passenger occupancy publish error: " + t.getMessage());
            }
        }
    }

    private boolean isActiveSafetyBeltListener(Object sourceDevice, long generation,
                                               long lifecycleGeneration) {
        return isCallbackLifecycleCurrent(lifecycleGeneration)
                && sourceDevice != null && sourceDevice == safetyBeltDevice
                && sourceDevice == activeSafetyBeltListenerDevice
                && generation == activeSafetyBeltListenerGeneration;
    }

    private boolean isActiveChargingListener(Object sourceDevice, long generation,
                                             long lifecycleGeneration) {
        return isCallbackLifecycleCurrent(lifecycleGeneration)
                && sourceDevice != null && sourceDevice == chargingDevice
                && sourceDevice == activeChargingListenerDevice
                && generation == activeChargingListenerGeneration;
    }

    private boolean isActiveInstrumentListener(Object sourceDevice, long generation,
                                               long lifecycleGeneration) {
        return isCallbackLifecycleCurrent(lifecycleGeneration)
                && sourceDevice != null && sourceDevice == instrumentDevice
                && sourceDevice == activeInstrumentListenerDevice
                && generation == activeInstrumentListenerGeneration;
    }

    private boolean isActiveEngineListener(Object sourceDevice, long generation,
                                           long lifecycleGeneration) {
        return isCallbackLifecycleCurrent(lifecycleGeneration)
                && sourceDevice != null && sourceDevice == engineDevice
                && sourceDevice == activeEngineListenerDevice
                && generation == activeEngineListenerGeneration;
    }

    private void onBodyworkCallback(String method, Object[] args) {
        BydVehicleData current = snapshot.get();
        if (current == null) return;
        BydVehicleData.Builder b = current.toBuilder();
        // Bodywork events also affect window/door-open state (separate from
        // lock state) and trunk position. Refresh both the bodywork view and
        // the lock view — door open/close on the bodywork bus is often the
        // first signal of an upcoming lock event, and refreshing locks here
        // means consumers see consistent state regardless of which side fires.
        collectBodywork(b);
        collectDoorLock(b);
        BydVehicleData updated = publishNonChargingSnapshot(b.build());

        // If a typed onDoorStateChanged event arrived, fan it out specifically
        // so consumers that want raw door-open events (not lock state) can
        // subscribe without polling the snapshot.
        if ("onDoorStateChanged".equals(method) && args != null && args.length >= 2) {
            int area = (args[0] instanceof Integer) ? (Integer) args[0] : -1;
            int state = (args[1] instanceof Integer) ? (Integer) args[1] : -1;
            notifyDoorStateListeners(area, state);
        }
        notifyLockSnapshotListeners(updated);
    }

    /**
     * Callback for DoorLock device — re-reads lock status on CAN bus state change.
     * Unlike other display-only devices, door lock state is critical for the
     * vehicle control page and must be updated immediately when the HAL reports
     * a change.
     *
     * The typed AbsBYDAutoDoorLockListener delivers onDoorLockStatusChanged(area,state)
     * with raw SDK semantics (UNLOCK=1, LOCK=2). We refresh the snapshot (which
     * uses inverted API contract for backwards compat) and forward the raw
     * SDK-semantic event to door-lock listeners.
     */
    private void onDoorLockCallback(String method, Object[] args) {
        BydVehicleData current = snapshot.get();
        if (current == null) return;
        BydVehicleData.Builder b = current.toBuilder();
        collectDoorLock(b);
        BydVehicleData updated = publishNonChargingSnapshot(b.build());

        if ("onDoorLockStatusChanged".equals(method) && args != null && args.length >= 2) {
            int area = (args[0] instanceof Integer) ? (Integer) args[0] : -1;
            int sdkState = (args[1] instanceof Integer) ? (Integer) args[1] : -1;
            notifyDoorLockListeners(area, sdkState);
        }
        notifyLockSnapshotListeners(updated);
    }

    /**
     * Callback for display-only devices (Tyre, AC, Sensor, Energy, Power).
     * 
     * These listeners exist solely to keep the BYD device singletons' internal caches
     * fresh. We do NOT re-poll devices here — the snapshot is updated on-demand when
     * the HTTP API calls collectAllFull(), or when the bodywork listener fires.
     * 
     * This avoids the 10Hz SensorDevice postEvent from triggering expensive
     * full display sweeps (tyre×4, seatbelt×5, AC×5, light×8, radar, etc.)
     */
    private void onDisplayCallback(String method, Object[] args) {
        // No-op: listener registration keeps BYD HAL singletons' caches alive.
        // Actual data is read on-demand via collectAllFull().
    }

    private void onEnergyCallback(Object sourceDevice, String method, Object[] args) {
        if (sourceDevice != energyDevice || method == null) return;
        if ("onDataChanged".equals(method)) {
            if (args != null && args.length > 0) {
                logModeRawEvent("energy", args[0]);
            }
            return;
        }
        if ("onDataEventChanged".equals(method)) {
            if (args != null && args.length >= 2 && args[0] instanceof Number) {
                int eventId = ((Number) args[0]).intValue();
                Object value = args[1];
                double dVal = BydDeviceHelper.getDoubleValue(value);
                String sVal = BydDeviceHelper.getStringValue(value);
                logger.info("[mode-diag] energy feature event id=" + eventId
                        + " (0x" + Integer.toHexString(eventId) + ")"
                        + " int=" + diagnosticValue(BydDeviceHelper.getIntValue(value))
                        + " double=" + (Double.isNaN(dVal) ? "n/a" : dVal)
                        + (sVal == null ? "" : " string=" + sVal));
            }
            return;
        }
        if (args == null || args.length == 0 || !(args[0] instanceof Number)) return;

        int mode = ((Number) args[0]).intValue();
        if ("onEnergyModeChanged".equals(method) && mode >= 0 && mode <= ENERGY_MODE_KEEP) {
            lastEnergyModeEvent = mode;
            logger.info("[mode-diag] Energy mode callback=" + mode
                    + " (" + energyModeName(mode) + ")");
        } else if ("onOperationModeChanged".equals(method) && mode >= 1 && mode <= 4) {
            lastEnergyOperationModeEvent = mode;
            logger.info("[mode-diag] Energy operation-mode callback=" + mode);
        } else if ("onRoadSurfaceChanged".equals(method) && mode >= 0 && mode <= 4) {
            lastEnergyRoadSurfaceEvent = mode;
            logger.info("[mode-diag] Energy road-surface callback=" + mode);
        }
    }

    private void onGenericCallback(String method, Object[] args) {
        long lifecycleGeneration = callbackLifecycleGeneration.get();
        // Stamp callback arrival before any dispatch work. A reconnect poll can begin while this
        // method is selecting its branch; assigning the sequence only inside the gun branch would
        // make an older queued gun-out callback appear newer than that synchronous reconnect.
        long callbackObservation = chargingObservationOrder.begin();
        if (!isCallbackLifecycleCurrent(lifecycleGeneration)) return;
        // Typed callbacks for real-time updates
        if ("onElecPercentageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double soc = ((Number) args[0]).doubleValue();
                if (soc >= 0 && soc <= 100) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().socPercent(soc).build());
                    }
                    // Fan out to the SoC voluntary-cutoff monitor (no-op when
                    // not running). Doesn't try to subclass the abstract
                    // listener separately — piggybacks on this hub.
                    try {
                        com.overdrive.app.power.SocCutoffMonitor.notifyElecPercentage(soc);
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onSOCBatteryPercentageChanged".equals(method) && args != null && args.length > 0) {
            try {
                int soc = ((Number) args[0]).intValue();
                if (soc >= 0 && soc <= 100) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().socPercent((double) soc).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onTotalMileageValueChanged".equals(method) && args != null && args.length > 0) {
            try {
                float mileage = ((Number) args[0]).floatValue();
                if (mileage > 0) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().totalMileageKm((int) Math.round(mileage * distanceToKmFactor)).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if (("onElecDrivingRangeChanged".equals(method) || "onDrivingRangeValueChanged".equals(method)) && args != null && args.length > 0) {
            try {
                int range = ((Number) args[0]).intValue();
                if (range >= 0) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().elecRangeKm((int) Math.round(range * distanceToKmFactor)).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if (("onEVRemainingBatteryPowerChanged".equals(method) || "onRemainingBatteryPowerChanged".equals(method)) && args != null && args.length > 0) {
            try {
                float kwh = ((Number) args[0]).floatValue();
                if (kwh >= 0) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().remainKwh((double) kwh).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onFuelPercentageChanged".equals(method) && args != null && args.length > 0) {
            try {
                int fuel = ((Number) args[0]).intValue();
                // 0 is a valid reading (empty tank) — accept it, but reject the
                // BEV/rail sentinels. Matches the polled path in collectStatistic.
                if (fuel >= 0 && fuel <= 100 && !isBevFuelSentinel(fuel)) {
                    BydVehicleData current = snapshot.get();
                    if (current != null && isPhev(current)) {
                        publishNonChargingSnapshot(current.toBuilder().fuelPercent(fuel).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onSpeedChanged".equals(method) && args != null && args.length > 0) {
            try {
                double speed = ((Number) args[0]).doubleValue();
                double speedKmh = convertRawSpeedToKmh(speed, getSpeedToKmhFactor());
                if (!Double.isNaN(speedKmh)) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .speedKmh(speedKmh).build());
                    }
                    if (com.overdrive.app.automation.Automations.isEventReferenced(
                                    com.overdrive.app.automation.condition.BydEvent.SPEED_KMPH)
                            || com.overdrive.app.automation.Automations.isEventReferenced(
                                    com.overdrive.app.automation.condition.BydEvent.SPEED_MPH)
                            || com.overdrive.app.automation.Automations.editorSeedActive()) {
                        com.overdrive.app.automation.condition.BydEvent.publishSpeedKmh(speedKmh);
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onEngineSpeedChanged".equals(method) && args != null && args.length > 0) {
            try {
                int rpm = ((Number) args[0]).intValue();
                if (rpm >= 0 && rpm <= 8000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().engineSpeedRpm(rpm).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onBatteryPowerVoltageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double voltage = ((Number) args[0]).doubleValue();
                if (voltage > 0 && voltage < 20) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().voltage12v(voltage).build());
                    }
                    // Fan out — same rationale as onOtaCallback. Some BYD
                    // trims route OTA voltage through the generic hub instead.
                    try {
                        com.overdrive.app.power.BatteryVoltageMonitorV2
                                .notifyBatteryPowerVoltage(voltage);
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onChargingGunStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                publishChargingGunEdge(
                        null, -1L, lifecycleGeneration,
                        ((Number) args[0]).intValue(), callbackObservation);
            } catch (Exception e) { /* ignore */ }
            return;
        }

        // HV pack voltage DISPLAY value is no longer taken from this event — it
        // under-reports on the Seal 82.5 trim (~494 V vs true ~570 V), so the shown
        // hvPackVoltage is derived from per-cell voltage × series count in
        // collectStatistic() instead (PR-125).
        //
        // BUT we still route the raw event into CAPACITY DETECTION only. That
        // derivation needs the series cell count, which needs the capacity — a
        // chicken-and-egg that leaves capacity unknown on models with no other
        // detection source (no user model, getBatteryCapacity()=0, SOC-ratio
        // unavailable, unknown ro.product.model, BMS-fuzzy miss). This event is the
        // ONLY independent pack-level voltage on those trims, and even an
        // under-reading value is enough for autoDetectFromPackVoltage() to SNAP to
        // the nearest known BYD pack. It self-guards: no-op once capacity is known
        // or the user set a model, so it never fights PR-125's accurate display
        // value or a user override. We do NOT write hvPackVoltage here (keeping the
        // accurate cell×count value authoritative for display/SOH math).
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                if (eventId == 1151336480) {
                    int iVal = BydDeviceHelper.getIntValue(args[1]);
                    if (iVal > 2000 && iVal < 9000) {       // decivolts: 200.0–900.0 V
                        double volts = iVal / 10.0;
                        com.overdrive.app.abrp.SohEstimator soh =
                            com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                        if (soh != null && !(soh.getNominalCapacityKwh() > 0)) {
                            soh.autoDetectFromPackVoltage(volts, snapshot.get());
                        }
                    }
                }
            } catch (Exception ignored) { /* diagnostic-only path; never disrupt collection */ }
        }
    }

    /**
     * Charging device callback — captures onChargingPowerChanged directly.
     * On many BYD models, getChargingPower() returns 0 but the callback delivers
     * the real value. We store it in the snapshot for VehicleDataMonitor to pick up.
     */
    // Throttle charging power log to once per 30 seconds
    private volatile long lastChargingPowerLogTime = 0;
    private volatile long lastChargingModeLogMs = 0;
    private volatile long lastChargingStateRawLogMs = 0;
    // One-shot: log the raw vs scaled getExternalChargingPower value the first
    // time we successfully publish a value, so the next field log capture can
    // confirm the hectowatt vs kW scaling against the cluster's own readout.
    private volatile boolean loggedExtChargePowerScale = false;

    // One-shot each: whether the per-session charged-energy counter answers on this trim, and
    // through which accessor. "Absent" has to be visible in a capture rather than inferred from
    // a missing line, because it decides whether session energy is metered or reconstructed.
    private volatile boolean loggedChargingPowerGetterSource = false;
    private volatile boolean loggedChargeCapacitySource = false;
    private volatile boolean loggedChargeCapacityAbsent = false;

    // Same one-shot, for the cluster's own CHARGING_CHARGE_POWER_DD readout — the PHEV-
    // trustworthy source. Logged once so a single field capture pins its scale.
    private volatile boolean loggedClusterChargePowerScale = false;

    // One-shot: whether the OEM battery-health index reports at all on this trim. The
    // Canonical SOH path prefers that value, so "absent" vs "present" has to be visible in a
    // single log capture rather than inferred from a missing number.
    private volatile boolean loggedOemSohOutcome = false;

    // Device-type argument for the STATISTIC family's get(deviceType, featureId) overload.
    // 1014 per the OEM app's own SOH read (its second-tier attempt).
    private static final int STATISTIC_DEVICE_TYPE = 1014;

    private int lastHvVolt = 0;
    private Integer lastHvCurrent = null;

    private void onCollectDataCallback(String method, Object[] args) {
        if (args == null) return;
        try {
            if ("onMotorMCUGeneratrixVolt".equals(method) && args.length >= 2) {
                int b = ((Number) args[1]).intValue();
                if (b >= 100 && b <= 1000) {
                    lastHvVolt = b;
                    updateLiveHvPower();
                }
                return;
            }
            if ("onMotorMCUGeneratrixCurrent".equals(method) && args.length >= 2) {
                int b = ((Number) args[1]).intValue();
                if (b >= -2000 && b <= 2000) {
                    lastHvCurrent = b;
                    updateLiveHvPower();
                }
                return;
            }
            if ("onDriverMotorSpeed".equals(method) && args.length >= 2) {
                int a = ((Number) args[0]).intValue();
                int b = ((Number) args[1]).intValue();
                int rpm = -1;
                if (b >= 0 && b <= 30000) rpm = b;
                else if (a >= 0 && a <= 30000) rpm = a;
                if (rpm >= 0) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().rearMotorSpeed(rpm).build());
                    }
                }
                return;
            }
        } catch (Exception e) {
            logger.debug("onCollectDataCallback error: " + e.getMessage());
        }
    }

    private void updateLiveHvPower() {
        if (lastHvVolt > 0 && lastHvCurrent != null) {
            double powerKw = (lastHvVolt * (double) lastHvCurrent) / 1000.0;
            BydVehicleData current = snapshot.get();
            if (current != null) {
                publishNonChargingSnapshot(current.toBuilder().enginePowerKw(powerKw).build());
            }
        }
    }

    /**
     * Publish every gun edge through the same lock/version protocol used by BMS edges and deliver it
     * to the detector before releasing that ordering position.
     */
    private void publishChargingGunEdge(Object sourceDevice, long generation,
                                        long lifecycleGeneration, int gunState,
                                        long observation) {
        if (!isValidChargingGunState(gunState)) return;
        synchronized (chargingEdgePublishLock) {
            if (sourceDevice != null && !isActiveChargingListener(
                    sourceDevice, generation, lifecycleGeneration)) {
                return;
            }
            if (sourceDevice == null
                    && !isCallbackLifecycleCurrent(lifecycleGeneration)) return;
            // A synchronous poll may have read a reconnect while this older callback waited for
            // the publication lock. Order by observation start, not lock acquisition.
            if (!chargingObservationOrder.claimGunCallback(observation)) return;
            try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                         com.overdrive.app.monitor.ChargingDetector.beginPublicationMutation()) {
                BydVehicleData current = snapshot.get();
                boolean vtol;
                if (gunState == 5) {
                    vtol = true;
                } else if (gunState == 1) {
                    vtol = false;
                } else if (gunState == 2 || gunState == 3 || gunState == 4) {
                    // Keep the snapshot's independent type-derived export bit, but do not send it as
                    // a fresh mode assertion to the detector. This callback observed only the gun.
                    vtol = current != null && current.chargingType == 3;
                } else {
                    vtol = current != null && current.vtolCharging;
                }
                BydVehicleData published = current;
                boolean authoritativeOff = gunState == 1 || gunState == 5;
                boolean sessionWasLive = current != null
                        && (current.chargingState == 1
                            || com.overdrive.app.monitor.ChargingDetector
                                    .getInstance().isCharging());
                if (current != null) {
                    published = current.toBuilder().chargingGunState(gunState)
                            .vtolCharging(vtol).build();
                    if (authoritativeOff) {
                        clearDevicePowerCallbackOriginLocked();
                        published = clearChargingRateFields(
                                published,
                                ChargeSourceClassifier.isCounter(
                                        ChargeSourceClassifier.SRC_EXTERNAL));
                    }
                    snapshot.set(published);
                }
                // Increment even for a duplicate snapshot value. An accepted callback is newer than
                // the last successful synchronous gun observation. This must precede a final-counter
                // getter that can block.
                gunEdgeVersion.incrementAndGet();
                if (authoritativeOff) {
                    chargingRateClearVersion.incrementAndGet();
                }
                if (gunState == 1) {
                    published = reconcileReservedFinalCountersLocked(
                            published, observation, lifecycleGeneration, sessionWasLive);
                }
                if (gunState == 1 && published != null) {
                    // Reservations began before this terminal edge; the synchronous getter below is
                    // newer and must be applied last even when it wrapped/reset to a lower value.
                    published = collectFinalChargeCountersLocked(published);
                }
                com.overdrive.app.monitor.ChargingDetector.getInstance()
                        .updateConnectionState(gunState, gunState == 5);
                publishAutomationSnapshot(published);
            }
        }
    }

    private void onChargingCallback(Object sourceDevice, long generation,
                                    String method, Object[] args) {
        // Capture the lifecycle first so an init/stop barrier between callback entry and later
        // publication cannot make old work look current after reactivation.
        long lifecycleGeneration = callbackLifecycleGeneration.get();
        if (!isActiveChargingListener(
                sourceDevice, generation, lifecycleGeneration)) return;
        CounterCallbackReservation capacityReservation = null;
        if ("onChargingCapacityChanged".equals(method)
                && args != null && args.length > 0 && args[0] instanceof Number) {
            try {
                double cap = ((Number) args[0]).doubleValue();
                if (isValidFinalCounterValue(ChargeSourceClassifier.SRC_CAPACITY, cap)) {
                    // Reserve the payload atomically with its observation sequence before this callback
                    // can wait for the publication lock. A later terminal edge can then settle it even
                    // if its final getter is unavailable.
                    capacityReservation = chargingObservationOrder.reserveCounterCallback(
                            lifecycleGeneration, ChargeSourceClassifier.SRC_CAPACITY, cap);
                }
            } catch (RuntimeException ignored) {}
        }
        // Hardware-observation ordering is based on callback dispatch/reservation, not on when the
        // callback eventually acquires chargingEdgePublishLock.
        long callbackObservation = capacityReservation != null
                ? capacityReservation.observation : chargingObservationOrder.begin();
        long bmsVersionAtDispatch = bmsEdgeVersion.get();
        long gunVersionAtDispatch = gunEdgeVersion.get();
        long typeVersionAtDispatch = chargingTypeVersion.get();
        // Typed callbacks for real-time charging updates
        if ("onChargingGunStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                publishChargingGunEdge(sourceDevice, generation, lifecycleGeneration,
                        ((Number) args[0]).intValue(), callbackObservation);
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // Real-time BMS state change — critical for detecting AC charging start/stop promptly
        if ("onBatteryManagementDeviceStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                int state = ((Number) args[0]).intValue();
                if (state >= 0 && state <= 15) {
                    synchronized (chargingStateTransitionLock) {
                        int previous = BydVehicleData.UNAVAILABLE;
                        boolean changed = false;
                        synchronized (chargingEdgePublishLock) {
                            if (!isActiveChargingListener(
                                    sourceDevice, generation, lifecycleGeneration)) return;
                            if (!chargingObservationOrder.claimBmsCallback(callbackObservation)) {
                                return;
                            }
                            try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation
                                         ignored = com.overdrive.app.monitor.ChargingDetector
                                                 .beginPublicationMutation()) {
                                BydVehicleData current = snapshot.get();
                                BydVehicleData published = current;
                                com.overdrive.app.monitor.ChargingDetector detector =
                                        com.overdrive.app.monitor.ChargingDetector.getInstance();
                                boolean terminalBarrierActive =
                                        detector.isTerminalSessionBarrierActive();
                                if (current != null) {
                                    previous = current.chargingState;
                                    if (previous != state
                                            && shouldPublishBmsCallbackTransition(
                                                    previous, state,
                                                    terminalBarrierActive)) {
                                        published = current.toBuilder().chargingState(state).build();
                                        changed = true;
                                    }
                                    if (isTerminalChargingState(state)) {
                                        clearDevicePowerCallbackOriginLocked();
                                        boolean chargingConnection =
                                                current.chargingGunState == 2
                                                || current.chargingGunState == 3
                                                || current.chargingGunState == 4;
                                        published = clearChargingRateFields(
                                                published,
                                                ChargeSourceClassifier.isCounter(
                                                        ChargeSourceClassifier.SRC_EXTERNAL),
                                                state == 2 && chargingConnection
                                                        && !current.vtolCharging);
                                    }
                                    snapshot.set(published);
                                }
                                // Linearize the terminal lifecycle and rate clear before any final
                                // counter getter. A newer counter-only callback can now capture these
                                // versions while the getter blocks and will be admitted afterward.
                                bmsEdgeVersion.incrementAndGet();
                                if (isTerminalChargingState(state)) {
                                    chargingRateClearVersion.incrementAndGet();
                                }
                                boolean sessionWasLive = previous == 1
                                        || com.overdrive.app.monitor.ChargingDetector
                                                .getInstance().isCharging();
                                if (isTerminalChargingState(state)) {
                                    published = reconcileReservedFinalCountersLocked(
                                            published, callbackObservation,
                                            lifecycleGeneration, sessionWasLive);
                                }
                                if (published != null
                                        && sessionWasLive
                                        && allowsTerminalCounterTail(state)) {
                                    // A getter executed after the terminal observation is newer than
                                    // every reservation settled above, regardless of value direction.
                                    published = collectFinalChargeCountersLocked(published);
                                }
                                // Snapshot publication and detector delivery share both the collector
                                // lock and the cross-component publication fence.
                                detector.updateBmsState(state);
                                publishAutomationSnapshot(published);
                            }
                        }
                        if (changed) {
                            logger.info("BMS state changed: " + state + " (" +
                                    (state == 0 ? "READY"
                                            : state == 1 ? "CHARGING"
                                            : state == 2 ? "FINISHED"
                                            : state == 3 ? "DISCHARGING"
                                            : state == 15 ? "IDLE" : "OTHER") + ")");
                            notifyChargingStateListeners(previous, state);
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // PER-SESSION CHARGED-ENERGY COUNTER, pushed by the HAL. This is the primary source for
        // session energy, so it goes through the same admission gate as the poll read — and the
        // domain is the SDK's [0, 131.07] kWh, not the old 200 (which would admit values that
        // cannot be this counter).
        if ("onChargingCapacityChanged".equals(method) && args != null && args.length > 0) {
            try {
                if (capacityReservation != null) {
                    double cap = capacityReservation.raw;
                    synchronized (chargingEdgePublishLock) {
                        if (!isActiveChargingListener(
                                sourceDevice, generation, lifecycleGeneration)) {
                            chargingObservationOrder.completeCounterCallback(
                                    capacityReservation);
                            return;
                        }
                        if (capacityReservation.settledByTerminal) return;
                        if (!isChargingCallbackLifecycleCurrent(
                                bmsVersionAtDispatch, bmsEdgeVersion.get(),
                                gunVersionAtDispatch, gunEdgeVersion.get(),
                                typeVersionAtDispatch, chargingTypeVersion.get())) {
                            chargingObservationOrder.completeCounterCallback(
                                    capacityReservation);
                            return;
                        }
                        try {
                            try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation
                                         ignored = com.overdrive.app.monitor.ChargingDetector
                                                 .beginPublicationMutation()) {
                                BydVehicleData current = snapshot.get();
                                double admitted = admitChargingCallback(
                                        current, ChargeSourceClassifier.SRC_CAPACITY, cap);
                                if (current != null && !Double.isNaN(admitted)) {
                                    BydVehicleData published = current.toBuilder()
                                            .chargingCapacityKwh(admitted).build();
                                    snapshot.set(published);
                                    capacityEdgeVersion.incrementAndGet();
                                    publishAutomationSnapshot(published);
                                    if (!loggedChargeCapacitySource) {
                                        loggedChargeCapacitySource = true;
                                        logger.info("Charged-energy counter alive via callback: "
                                                + cap + " kWh");
                                    }
                                }
                            }
                        } finally {
                            chargingObservationOrder.completeCounterCallback(
                                    capacityReservation);
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // Handle the new-style BYDAutoEvent callbacks from ChargingDevice.
        // IMPORTANT: Do NOT blindly interpret onDataEventChanged values as charging power.
        // The ChargingDevice fires events for many different metrics (voltage, current,
        // capacity, temperature, etc.) and we cannot reliably distinguish power from other
        // values without knowing the specific event ID mapping.
        // The OEM firmware does NOT use onDataEventChanged for power — it only uses
        // onExternalChargingPowerChanged from InstrumentDevice (see onInstrumentCallback).
        // We skip this path entirely to avoid misinterpreting non-power values as kW.
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            // Intentionally not processing — see comment above.
            // Power comes from onExternalChargingPowerChanged (InstrumentDevice) or
            // onChargingPowerChanged (typed callback below).
            return;
        }
        if ("onChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = ((Number) args[0]).doubleValue();
                if (isChargingPowerCallbackPayload(power)) {
                    synchronized (chargingEdgePublishLock) {
                        if (!isActiveChargingListener(
                                sourceDevice, generation, lifecycleGeneration)) return;
                        if (!isChargingCallbackLifecycleCurrent(
                                bmsVersionAtDispatch, bmsEdgeVersion.get(),
                                gunVersionAtDispatch, gunEdgeVersion.get(),
                                typeVersionAtDispatch, chargingTypeVersion.get())) {
                            return;
                        }
                        try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation
                                     ignored = com.overdrive.app.monitor.ChargingDetector
                                             .beginPublicationMutation()) {
                            BydVehicleData current = snapshot.get();
                            double admitted = power > 0.1
                                    ? admitChargingCallback(
                                            current, ChargeSourceClassifier.SRC_DEVICE, power)
                                    : Double.NaN;
                            if (power <= 0.1 && current != null
                                    && allowsRawChargingEvidence(
                                            current.chargingGunState, current.vtolCharging)) {
                                try {
                                    com.overdrive.app.monitor.ChargingDetector.getInstance()
                                            .observeRawChargingSignal(
                                                ChargeSourceClassifier.SRC_DEVICE, 0.0);
                                } catch (Throwable ignoredSignal) {}
                            }
                            if (current != null
                                    && (power <= 0.1 || !Double.isNaN(admitted))) {
                                long observedAtMs = System.currentTimeMillis();
                                BydVehicleData published = current.toBuilder()
                                        .chargingPowerKw(
                                                power <= 0.1 ? Double.NaN : admitted)
                                        .chargingPowerAtMs(
                                                power <= 0.1 ? 0L : observedAtMs)
                                        .build();
                                snapshot.set(published);
                                if (power <= 0.1) {
                                    clearDevicePowerCallbackOriginLocked();
                                } else {
                                    latestDevicePowerCameFromCallback = true;
                                    lastPositiveDevicePowerCallbackAtMs = observedAtMs;
                                }
                                devicePowerEdgeVersion.incrementAndGet();
                                publishAutomationSnapshot(published);
                                long now = System.currentTimeMillis();
                                if (power > 0.1
                                        && now - lastChargingPowerLogTime > 30_000) {
                                    lastChargingPowerLogTime = now;
                                    logger.info("Charging power via callback: "
                                            + String.format("%.1f", power) + " kW");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
    }

    private void onOtaCallback(String method, Object[] args) {
        if ("onBatteryPowerVoltageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double voltage = ((Number) args[0]).doubleValue();
                if (voltage > 0 && voltage < 20) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().voltage12v(voltage).build());
                    }
                    // Fan out to BatteryVoltageMonitorV2's MCU sleep/wake
                    // hysteresis (no-op when not running). The collector
                    // subclasses AbsBYDAutoOtaListener once and routes here;
                    // V2 piggybacks instead of trying its own registration.
                    try {
                        com.overdrive.app.power.BatteryVoltageMonitorV2
                                .notifyBatteryPowerVoltage(voltage);
                    } catch (Throwable ignored) {}
                }
            } catch (Exception e) { /* ignore */ }
        }
    }

    private void onInstrumentCallback(Object sourceDevice, long generation,
                                      String method, Object[] args) {
        long lifecycleGeneration = callbackLifecycleGeneration.get();
        if (!isActiveInstrumentListener(
                sourceDevice, generation, lifecycleGeneration)) return;
        CounterCallbackReservation externalCounterReservation = null;
        if ("onExternalChargingPowerChanged".equals(method)
                && args != null && args.length > 0 && args[0] instanceof Number
                && ChargeSourceClassifier.isCounter(ChargeSourceClassifier.SRC_EXTERNAL)) {
            try {
                double raw = ((Number) args[0]).doubleValue();
                if (isValidFinalCounterValue(ChargeSourceClassifier.SRC_EXTERNAL, raw)) {
                    externalCounterReservation = chargingObservationOrder.reserveCounterCallback(
                            lifecycleGeneration, ChargeSourceClassifier.SRC_EXTERNAL, raw);
                }
            } catch (RuntimeException ignored) {}
        }
        long bmsVersionAtDispatch = bmsEdgeVersion.get();
        long gunVersionAtDispatch = gunEdgeVersion.get();
        long typeVersionAtDispatch = chargingTypeVersion.get();
        // Handle the new-style BYDAutoEvent callbacks
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            // NOTE: Do NOT blindly interpret all instrument events as charging power.
            // The instrument device fires events for trip odometer, nav data,
            // and dozens of other metrics. Only the typed onExternalChargingPowerChanged
            // callback (below) reliably delivers charging power.
            // Previously, events like INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE
            // (event 1246801948, value=18.7 km) were misinterpreted as 18.7 kW charging.
        }
        if ("onSafetyBeltStatusChanged".equals(method)) {
            // TRULY-INSTANT belt push. The HAL fires this only on a genuine belt-state edge, so
            // it beats even the 500ms fast poll. We deliberately IGNORE the callback's (seat,
            // state) args — their seat convention (0/1 vs 1/2) and state encoding are unverified
            // on this firmware — and instead re-sample BOTH seats through readSeatbeltPair (the
            // proven getSafetyBeltStatus(area) path). So the callback is only a "something
            // changed, read now" trigger; the mapping/gating/de-glitch stays in one place and a
            // wrong-convention arg can never swap driver↔passenger or invert buckled↔unbuckled.
            // Gated on an enabled seatbelt automation so it stays zero-cost otherwise, mirroring
            // SeatbeltEvent.poll().
            try {
                if (anyReferenced(com.overdrive.app.automation.condition.BydEvent.SEATBELT_DRIVER,
                                  com.overdrive.app.automation.condition.BydEvent.SEATBELT_PASSENGER)) {
                    // Drop the per-tick memo first: this callback exists to beat the 500ms poll,
                    // and an edge landing just after a poll read would otherwise be answered from
                    // the pre-edge memo and have to wait for the next tick.
                    invalidateSeatbeltPairMemo();
                    com.overdrive.app.automation.condition.BydEvent.pollSeatbelts();
                }
            } catch (Throwable t) {
                logger.debug("onSafetyBeltStatusChanged re-sample error: " + t.getMessage());
            }
            return;
        }
        if ("onExternalChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = externalCounterReservation != null
                        ? externalCounterReservation.raw
                        : ((Number) args[0]).doubleValue();
                // RAW, unscaled, and gated exactly like the poll read. This accessor's meaning is
                // firmware-dependent (a live rate on some trims, a cumulative charged-energy
                // counter on others), so the value is stored as-is and ChargeSourceClassifier
                // decides what it is. The old code accepted it here with no gun/BMS gate at all,
                // which is how a counter reading taken with the gun OUT became "charging power".
                boolean accepted = false;
                synchronized (chargingEdgePublishLock) {
                    if (!isActiveInstrumentListener(
                            sourceDevice, generation, lifecycleGeneration)) {
                        chargingObservationOrder.completeCounterCallback(
                                externalCounterReservation);
                        return;
                    }
                    if (externalCounterReservation != null
                            && externalCounterReservation.settledByTerminal) {
                        return;
                    }
                    if (!isChargingCallbackLifecycleCurrent(
                            bmsVersionAtDispatch, bmsEdgeVersion.get(),
                            gunVersionAtDispatch, gunEdgeVersion.get(),
                            typeVersionAtDispatch, chargingTypeVersion.get())) {
                        chargingObservationOrder.completeCounterCallback(
                                externalCounterReservation);
                        return;
                    }
                    try {
                        try (com.overdrive.app.monitor.ChargingDetector.PublicationMutation ignored =
                                     com.overdrive.app.monitor.ChargingDetector
                                             .beginPublicationMutation()) {
                            BydVehicleData current = snapshot.get();
                            ChargeSourceClassifier.Kind sourceKind =
                                    ChargeSourceClassifier.kindOf(
                                            ChargeSourceClassifier.SRC_EXTERNAL);
                            boolean explicitRateStop = current != null
                                    && isExplicitExternalRateStop(power, sourceKind);
                            double admitted = explicitRateStop
                                    ? Double.NaN
                                    : admitChargingCallback(
                                            current, ChargeSourceClassifier.SRC_EXTERNAL, power);
                            accepted = current != null
                                    && (explicitRateStop || !Double.isNaN(admitted));
                            if (accepted) {
                                if (explicitRateStop && allowsRawChargingEvidence(
                                        current.chargingGunState, current.vtolCharging)) {
                                    try {
                                        com.overdrive.app.monitor.ChargingDetector.getInstance()
                                                .observeRawChargingSignal(
                                                    ChargeSourceClassifier.SRC_EXTERNAL, 0.0);
                                    } catch (Throwable ignoredSignal) {}
                                }
                                long observedAtMs = System.currentTimeMillis();
                                BydVehicleData published = current.toBuilder()
                                        .externalChargingPowerKw(
                                                explicitRateStop ? Double.NaN : admitted)
                                        .externalChargingPowerAtMs(
                                                explicitRateStop ? 0L : observedAtMs)
                                        .build();
                                snapshot.set(published);
                                externalPowerEdgeVersion.incrementAndGet();
                                publishAutomationSnapshot(published);
                            }
                        }
                    } finally {
                        chargingObservationOrder.completeCounterCallback(
                                externalCounterReservation);
                    }
                }
                // DIAGNOSTIC: log EVERY callback arrival — this is the proof the
                // typed AbsBYDAutoInstrumentListener registration actually delivers
                // onExternalChargingPowerChanged while parked-charging (the generic
                // IBYDAutoListener-Proxy path never could). Logs the raw value AND
                // whether it passed the accept gate, so a dropped/out-of-range value
                // (e.g. 0.0 → "Charging" fallback) is visible instead of silent.
                // Throttled to once / 30s to avoid spam on a chatty firmware.
                long now = System.currentTimeMillis();
                if (now - lastChargingPowerLogTime > 30_000) {
                    lastChargingPowerLogTime = now;
                    logger.info("onExternalChargingPowerChanged fired: raw=" + power + " kW"
                        + (accepted ? " (accepted)" : " (DROPPED — outside 0.1..500 gate)"));
                }
            } catch (Exception e) {
                logger.debug("onExternalChargingPowerChanged parse error: " + e.getMessage());
            }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
    }

    private void onLightsCallback(String method, Object[] args) {
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int iVal = BydDeviceHelper.getIntValue(eventValue);

                if (eventId == BydFeatureIds.LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE && iVal > 0 && iVal < 3) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .dayTimeLight(iVal == 1).build());
                    }
                    return;
                }

                // Ambient main switch. ATMOSPHERE_MAIN_SWITCH_STATUS is a LIGHT-family id
                // (0x3F30…) read off lightDevice, so this is its natural callback — but the
                // same handler also runs in onSettingsCallback because the sibling ambient
                // COLOUR events were observed arriving there. Registering both costs nothing
                // (the id match is exact) and means we don't have to guess which device the
                // firmware routes it through.
                applyAmbientEnabledEvent(eventId, iVal);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Mirror an interior-ambient MAIN SWITCH event into the snapshot so a change made in the
     * OEM UI (or by another app) shows up without waiting for the next 5s poll.
     *
     * <p>Shared by {@link #onLightsCallback} and {@link #onSettingsCallback} because the
     * feature id is Light-family but the sibling ambient-colour events were observed arriving
     * on the Setting device — rather than guess which one the firmware uses, both listen and
     * the exact id match makes a double registration harmless.
     *
     * <p>Only an exact 1/0 is accepted, so an INVALID/sentinel event cannot flip the published
     * state to a wrong value (the field stays UNAVAILABLE and nothing is published).
     *
     * @return true when this event WAS the ambient main switch (caller should stop processing).
     */
    private boolean applyAmbientEnabledEvent(int eventId, int iVal) {
        if (eventId != BydFeatureIds.LIGHT_ATMOSPHERE_MAIN_SWITCH_STATUS) return false;
        if (iVal == 0 || iVal == 1) {
            BydVehicleData current = snapshot.get();
            if (current != null) {
                publishNonChargingSnapshot(current.toBuilder().ambientEnabled(iVal).build());
            }
        }
        return true;
    }

    private void onAdasCallback(String method, Object[] args) {
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int iVal = BydDeviceHelper.getIntValue(eventValue);
                int warningBit = adasWarningBitForId(eventId);

                // Automatic diagnostic on the EXISTING filtered callback. No broad listener or
                // new poll is added, so this is bounded to the selected ADAS feature changes.
                logger.info("[adas-event] id=" + eventId
                        + " (0x" + Integer.toHexString(eventId) + ")"
                        + " route=" + adasWarningRoute(warningBit)
                        + " int=" + diagnosticValue(iVal)
                        + " double=" + diagnosticDouble(
                                BydDeviceHelper.getDoubleValue(eventValue))
                        + diagnosticString(BydDeviceHelper.getStringValue(eventValue)));

                if (eventId == BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE && iVal > 0 && iVal < 3) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .speedLimitWarning(iVal == 2).build());
                    }
                    return;
                }
                // Instant path: these warning pulses can disappear before the next poll.
                handleAdasWarningEvent(eventId, warningBit, iVal);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Route one ADAS event to its distinct automation state. FL/FR are levels;
     * LCA/RCTA/DOW are counters.
     */
    private void handleAdasWarningEvent(int eventId, int warningBit, int value) {
        // Defensive: an id this SDK does not publish must never take part in the comparisons below.
        // Currently unreachable (every constant here resolves to a real literal), kept so adding an
        // UNRESOLVED_ID fallback later cannot silently attribute an unrelated event to a side.
        if (!BydFeatureIds.isResolved(eventId) || warningBit == 0) return;
        // Sanitize FIRST, exactly as the poll does. This is the instant path with no confirmation
        // hold, and it writes the SAME bsCounterBaseline map: an event carrying a positive 16-bit
        // rail (65535/65534) would otherwise fire an immediate alert AND park the baseline there,
        // so the next real count reads as a drop and the one after it re-fires.
        int sanitized = sanitizeAdasAlert(value);
        if (sanitized == BydVehicleData.UNAVAILABLE) return;
        value = sanitized;
        boolean alerting;
        if (isAdasLevelWarningId(eventId)) {
            alerting = value >= 1;
        } else {
            // An event for THIS id proves its callback works, so the poll can stop
            // reading it (see anyCounterAdvanced). Recorded per id — an event for one
            // warning says nothing about the others.
            if (bsEventProvenIds.add(eventId)) {
                logger.info("ADAS warning events confirmed for id=" + eventId
                        + " — no longer polling it");
            }
            alerting = counterAdvanced(eventId, value);
        }
        // Only an ALERT is pushed from the event path. The "clear" edge is owned by
        // the hold timer in the automation layer: these signals are momentary
        // pulses, so treating a non-alerting event as "clear" would cancel a live
        // warning almost immediately.
        if (alerting) {
            com.overdrive.app.automation.condition.BlindSpotEvent.onAlert(warningBit);
        }
    }

    static int adasWarningBitForId(int eventId) {
        if (eventId == BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM
                || eventId == BydFeatureIds.ADAS_LCA_WARNING_LEFT) {
            return BS_LEFT_BIT;
        }
        if (eventId == BydFeatureIds.ADAS_FR_BLIND_SPOT_ALARM
                || eventId == BydFeatureIds.ADAS_LCA_WARNING_RIGHT) {
            return BS_RIGHT_BIT;
        }
        if (eventId == BydFeatureIds.ADAS_RCTA_WARNING_LEFT) return RCTA_LEFT_BIT;
        if (eventId == BydFeatureIds.ADAS_RCTA_WARNING_RIGHT) return RCTA_RIGHT_BIT;
        if (eventId == BydFeatureIds.ADAS_DOW_WARN_LEFT) return DOW_LEFT_BIT;
        if (eventId == BydFeatureIds.ADAS_DOW_WARN_RIGHT) return DOW_RIGHT_BIT;
        return 0;
    }

    static boolean isAdasLevelWarningId(int eventId) {
        return eventId == BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM
                || eventId == BydFeatureIds.ADAS_FR_BLIND_SPOT_ALARM;
    }

    private static String adasWarningRoute(int warningBit) {
        switch (warningBit) {
            case BS_LEFT_BIT: return "blind_spot:left";
            case BS_RIGHT_BIT: return "blind_spot:right";
            case RCTA_LEFT_BIT: return "rear_cross_traffic:left";
            case RCTA_RIGHT_BIT: return "rear_cross_traffic:right";
            case DOW_LEFT_BIT: return "door_open_warning:left";
            case DOW_RIGHT_BIT: return "door_open_warning:right";
            default: return "other";
        }
    }

    private static String diagnosticDouble(double value) {
        return Double.isNaN(value) ? "n/a" : Double.toString(value);
    }

    private static String diagnosticString(String value) {
        return value == null ? "" : " string=" + value;
    }

    private void onSettingsCallback(String method, Object[] args) {
        if ("onDataChanged".equals(method)) {
            if (args != null && args.length > 0) {
                logModeRawEvent("setting", args[0]);
            }
            return;
        }
        if (!"onDataEventChanged".equals(method) || args == null || args.length < 2) return;
        try {
            int eventId = ((Number) args[0]).intValue();
            int iVal = BydDeviceHelper.getIntValue(args[1]);
            double dVal = BydDeviceHelper.getDoubleValue(args[1]);
            String sVal = BydDeviceHelper.getStringValue(args[1]);
            logger.info("[mode-diag] setting feature event id=" + eventId
                    + " (0x" + Integer.toHexString(eventId) + ")"
                    + " int=" + diagnosticValue(iVal)
                    + " double=" + (Double.isNaN(dVal) ? "n/a" : dVal)
                    + (sVal == null ? "" : " string=" + sVal));
            // SDK reports 1=on, 2=off, 3=delay for CPD
            if (eventId == BydFeatureIds.SETTING_CPD_SWITCH_STATUS && iVal > 0 && iVal < 4) {
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    publishNonChargingSnapshot(current.toBuilder()
                            .childPresenceDetection(iVal).build());
                }
                return;
            }

            // Interior ambient colour (1..31). The "all area" event does not fire, so
            // monitor the FRONT and BACK colour features (same numeric ids as
            // LIGHT_AMBIENT_FRONT/REAR_COLOR) and mirror the latest into the snapshot.
            // Must precede the seat 1..3 guard below (ambient values exceed that range).
            if (eventId == BydFeatureIds.LIGHT_AMBIENT_FRONT_COLOR
                    || eventId == BydFeatureIds.LIGHT_AMBIENT_REAR_COLOR) {
                if (iVal >= 1 && iVal <= 31) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().ambientColour(iVal).build());
                    }
                }
                return;
            }

            // Interior ambient MAIN SWITCH status. Placed before the seat 1..3 guard below,
            // which would otherwise swallow the 0 (off) edge — the very state this reports.
            if (applyAmbientEnabledEvent(eventId, iVal)) return;

            // SDK reports 1=off, 2=low, 3=high for seats. Anything else is unknown — ignore.
            if (iVal < 1 || iVal > 3) return;

            int normalized = iVal - 1;
            BydVehicleData current = snapshot.get();
            if (current == null) return;
            BydVehicleData.Builder b = current.toBuilder();
            int[] heat = (current.seatHeat == null) ? new int[2] : current.seatHeat.clone();
            int[] cool = (current.seatCool == null) ? new int[2] : current.seatCool.clone();

            if (eventId == BydFeatureIds.SET_DRIVER_SEAT_HEATING_STATE)         heat[0] = normalized;
            else if (eventId == BydFeatureIds.SET_DRIVER_SEAT_VENTILATING_STATE) cool[0] = normalized;
            else if (eventId == BydFeatureIds.SET_PASSENGER_SEAT_HEATING_STATE) heat[1] = normalized;
            else if (eventId == BydFeatureIds.SET_PASSENGER_SEAT_VENTILATING_STATE) cool[1] = normalized;
            else return;

            publishNonChargingSnapshot(b.seatHeat(heat).seatCool(cool).build());
        } catch (Exception ignored) {}
    }

    private static String diagnosticValue(int value) {
        return value == Integer.MIN_VALUE ? "n/a" : Integer.toString(value);
    }

    private void logModeRawEvent(String source, Object rawEvent) {
        if (!(rawEvent instanceof android.hardware.IBYDAutoEvent)) {
            logger.info("[mode-diag] " + source + " raw event="
                    + (rawEvent == null ? "null" : rawEvent.getClass().getName()));
            return;
        }
        try {
            android.hardware.IBYDAutoEvent event = (android.hardware.IBYDAutoEvent) rawEvent;
            Object data = event.getData();
            int eventType = event.getEventType();
            double dataDouble = BydDeviceHelper.getDoubleValue(data);
            logger.info("[mode-diag] " + source
                    + " raw event device=" + event.getDeviceType()
                    + " type=" + eventType + " (0x" + Integer.toHexString(eventType) + ")"
                    + " value=" + event.getValue()
                    + " double=" + event.getDoubleValue()
                    + " dataInt=" + diagnosticValue(BydDeviceHelper.getIntValue(data))
                    + " dataDouble=" + (Double.isNaN(dataDouble) ? "n/a" : dataDouble));
        } catch (Throwable t) {
            logger.info("[mode-diag] " + source + " raw event unreadable: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ==================== EXTENDED LISTENER HANDLERS ====================
    // These handler methods exist for future use. To activate, add a registerListener() call
    // in registerAllListeners() or registerBodyworkExtendedListeners() etc.

    private void handleSteeringAngleChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double angle = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(angle) && angle >= -780 && angle <= 780) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .steeringAngleDegrees(angle).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleSteeringAngleChanged error: " + e.getMessage()); }
        }
    }

    private void handleAutoSystemStateChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int state = BydDeviceHelper.getIntValue(args[0]);
                if (state >= 0 && state <= 2) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .autoSystemState(state).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleAutoSystemStateChanged error: " + e.getMessage()); }
        }
    }

    private void handleSunroofStateChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int state = BydDeviceHelper.getIntValue(args[0]);
                if (state >= 0 && state <= 255) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().sunroofState(state).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleSunroofStateChanged error: " + e.getMessage()); }
        }
    }

    private void handleSunroofPositionChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int position = BydDeviceHelper.getIntValue(args[0]);
                if (position >= 0 && position <= 100) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .sunroofPosition(position).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleSunroofPositionChanged error: " + e.getMessage()); }
        }
    }

    private void handleChargingCapacityChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double capacity = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(capacity) && capacity >= 0 && capacity <= 200) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().remainKwh(capacity).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleChargingCapacityChanged error: " + e.getMessage()); }
        }
    }

    private void handleDrivingTimeChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double hours = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(hours) && hours >= 0 && hours <= 10000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .drivingTimeHours(hours).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleDrivingTimeChanged error: " + e.getMessage()); }
        }
    }

    private void handleKeyBatteryLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 1) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .keyBatteryLevel(level).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleKeyBatteryLevelChanged error: " + e.getMessage()); }
        }
    }

    /**
     * Dispatcher for the typed engine listener. Routes the three known
     * device-specific callbacks to existing handle* methods, and forwards
     * unrecognised feature-ID events to the discovery logger so we can
     * extend BydFeatureIds.Engine with whatever fluid temperature IDs this
     * firmware happens to publish.
     */
    private void onEngineCallback(Object sourceDevice, long generation,
                                  String method, Object[] args) {
        long callbackObservedAtMs = System.currentTimeMillis();
        long lifecycleGeneration = callbackLifecycleGeneration.get();
        if (!isActiveEngineListener(
                sourceDevice, generation, lifecycleGeneration)) return;
        if (args == null) return;
        long bmsVersionAtDispatch = bmsEdgeVersion.get();
        long gunVersionAtDispatch = gunEdgeVersion.get();
        long typeVersionAtDispatch = chargingTypeVersion.get();
        long rateClearVersionAtDispatch = chargingRateClearVersion.get();
        try {
            if ("onEngineCoolantLevelChanged".equals(method)) {
                handleEngineCoolantLevelChanged(args);
                if (!loggedEngineCoolantEvent && args.length > 0) {
                    loggedEngineCoolantEvent = true;
                    int level = BydDeviceHelper.getIntValue(args[0]);
                    logger.info("Engine event FIRST: coolantLevel=" + level
                            + " (0=NORMAL,1=LOW)");
                }
                return;
            }
            if ("onOilLevelChanged".equals(method)) {
                handleOilLevelChanged(args);
                if (!loggedEngineOilEvent && args.length > 0) {
                    loggedEngineOilEvent = true;
                    int level = BydDeviceHelper.getIntValue(args[0]);
                    logger.info("Engine event FIRST: oilLevel=" + level + " (0-254)");
                }
                return;
            }
            if ("onEngineSpeedChanged".equals(method) && args.length > 0) {
                int rpm = ((Number) args[0]).intValue();
                if (rpm >= 0 && rpm <= 8000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().engineSpeedRpm(rpm).build());
                    }
                }
                return;
            }
            if ("onDataEventChanged".equals(method) && args.length >= 2) {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int rawInt = BydDeviceHelper.getIntValue(eventValue);
                double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);

                // Known engine feature IDs — these are declared in
                // BYDAutoFeatureIds.Engine but the typed callbacks
                // (onEngineSpeedChanged etc.) are dormant on PHEV firmware,
                // so consume them off the generic event channel instead.
                // Apply the same sentinel filters and scaling as collectEngine.
                if (eventId == BydFeatureIds.ENGINE_SPEED
                        || eventId == BydFeatureIds.ENGINE_SPEED_ALT) {
                    // 8191 (0x1FFF) is a PHEV "engine off" sentinel observed on this
                    // firmware family; the standard BMS_UNAVAILABLE family covers the rest.
                    if (rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && rawInt != 8191
                            && rawInt >= 0 && rawInt <= 8000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            publishNonChargingSnapshot(current.toBuilder()
                                    .engineSpeedRpm(rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_POWER) {
                    // The event's intValue carries the raw CAN signal; doubleValue is
                    // typically 0.0 on the listener path. Same dual-scale heuristic as
                    // collectEngine: positive raw > 100 uses the 0.1 multiplier; negative values
                    // are already kW. Range-filter excludes sentinels.
                    double raw = (rawInt != Integer.MIN_VALUE) ? (double) rawInt : rawDbl;
                    if (!Double.isNaN(raw) && raw >= -200.0 && raw <= 400.0) {
                        double kw = scaleEnginePowerKw(raw);
                        // After scaling, re-check the kW range so a hectowatt value
                        // like 3095 (→ 309.5) gets rejected instead of mis-stored.
                        //
                        // Sentinel check MUST be here too, not only in collectEngine's two read
                        // paths. This generic-event listener is a THIRD writer of the same field,
                        // and on the targeted PHEV firmware it is the DOMINANT one: the typed
                        // listeners are dormant there (see the note above), and while parked the
                        // poll interval is 90 s, so the listener supplies nearly every refresh.
                        // A -1.0 sentinel also survives the ACC-off gate below (-1.0 > -0.3 is
                        // false), so without this the phantom re-entered the snapshot downstream
                        // of the filter and ChargingDetector behaviour was bit-identical to
                        // pre-fix — it also re-armed after invalidateAccDependentSignals() had
                        // just cleared it on the ACC-off edge (invariant I4).
                        if (kw >= -200.0 && kw <= 400.0 && !isEnginePowerSentinel(kw)) {
                            // ACC OFF gating: when the key is removed, the only
                            // physically plausible engine-power direction is
                            // current INTO the pack (kw < 0, plug-in charging).
                            // Positive readings while parked are stale ECU
                            // residue or sensor noise — accepting them lets the
                            // ChargingDetector's L3 inference falsely conclude
                            // "engine is running" or wash out a real charging
                            // signal. Reject them; preserve negative values so
                            // charging-while-parked detection still works.
                            if (!accIsOn && kw > -ENGINE_POWER_CHARGING_DEADBAND) {
                                return;
                            }
                            synchronized (chargingEdgePublishLock) {
                                if (!isActiveEngineListener(
                                        sourceDevice, generation,
                                        lifecycleGeneration)) return;
                                BydVehicleData current = snapshot.get();
                                if (allowsEnginePowerCallback(
                                        current, kw, accIsOn,
                                        bmsVersionAtDispatch, bmsEdgeVersion.get(),
                                        gunVersionAtDispatch, gunEdgeVersion.get(),
                                        typeVersionAtDispatch, chargingTypeVersion.get(),
                                        rateClearVersionAtDispatch,
                                        chargingRateClearVersion.get(),
                                        callbackObservedAtMs,
                                        hasCoherentPostFinishedRateProof(
                                                current, callbackObservedAtMs))) {
                                    try (com.overdrive.app.monitor.ChargingDetector
                                                     .PublicationMutation ignored =
                                                 com.overdrive.app.monitor.ChargingDetector
                                                         .beginPublicationMutation()) {
                                        // Preserve dispatch time rather than lock-acquisition time.
                                        // The terminal proof requires this callback itself to have
                                        // begun after FINISHED.
                                        BydVehicleData published = current.toBuilder()
                                                .enginePowerKw(kw)
                                                .enginePowerAtMs(callbackObservedAtMs)
                                                .build();
                                        snapshot.set(published);
                                        enginePowerEdgeVersion.incrementAndGet();
                                        publishAutomationSnapshot(published);
                                    }
                                }
                            }
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_FRONT_MOTOR_SPEED) {
                    // Front motor RPM is negated to match the cluster's display
                    // convention (forward motion = positive). Filter sentinels.
                    if (rawInt != Integer.MIN_VALUE
                            && rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && Math.abs(rawInt) <= 25000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            publishNonChargingSnapshot(current.toBuilder()
                                    .frontMotorSpeed(-rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_REAR_MOTOR_SPEED) {
                    if (rawInt != Integer.MIN_VALUE
                            && rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && Math.abs(rawInt) <= 25000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            publishNonChargingSnapshot(current.toBuilder()
                                    .rearMotorSpeed(rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_FRONT_MOTOR_TORQUE) {
                    // Negated to match cluster convention (same as collectEngine).
                    if (!Double.isNaN(rawDbl) && Math.abs(rawDbl) <= 1000.0) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            publishNonChargingSnapshot(current.toBuilder()
                                    .frontMotorTorque(-rawDbl).build());
                        }
                    }
                    return;
                }

                // Unknown ID — log once for discovery (capped at 32 unique IDs).
                logUnknownEngineEventOnce(eventId, rawInt, rawDbl);
            }
        } catch (Exception e) {
            logger.debug("onEngineCallback error (" + method + "): " + e.getMessage());
        }
    }

    // One-shot diagnostic flags for the typed engine callbacks.
    private volatile boolean loggedEngineCoolantEvent = false;
    private volatile boolean loggedEngineOilEvent = false;

    // Log each unknown engine feature ID once, capped at 32 unique IDs total.
    // Engine fluids on some firmware (coolant temp, oil temp on PHEVs) arrive
    // here keyed on IDs that aren't in BYDAutoFeatureIds.Engine — surface the
    // first sighting of each so we can extend the constant table empirically.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> loggedUnknownEngineIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_UNKNOWN_ENGINE_IDS = 32;

    private void logUnknownEngineEventOnce(int eventId, int rawInt, double rawDbl) {
        if (loggedUnknownEngineIds.size() >= MAX_UNKNOWN_ENGINE_IDS) return;
        if (loggedUnknownEngineIds.putIfAbsent(eventId, Boolean.TRUE) != null) return;
        logger.info("Engine event UNKNOWN id=" + eventId
                + " intValue=" + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — if this looks like a coolant/oil temp, add it to BydFeatureIds.Engine");
    }

    private void handleEngineCoolantLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 1) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder()
                                .engineCoolantLevel(level).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleEngineCoolantLevelChanged error: " + e.getMessage()); }
        }
    }

    private void handleOilLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 254) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        publishNonChargingSnapshot(current.toBuilder().oilLevel(level).build());
                    }
                }
            } catch (Exception e) { logger.debug("handleOilLevelChanged error: " + e.getMessage()); }
        }
    }


    // ==================== VEHICLE CONTROL SETTERS ====================
    // All setters call BydDeviceHelper directly from UID 2000.
    // If a setter fails due to UID permissions, it logs the error and returns false.
    // These methods are public and always callable — no config gate needed.

    // --- Climate Control ---

    public boolean setAcPower(boolean on) {
        // Use the named start()/stop() methods on BYDAutoAcDevice — these actually
        // turn the AC system on/off. The previous implementation used AC_AUTO_MODE_SET
        // which only toggles AUTO mode (automatic climate control) without stopping the
        // AC compressor/blower. This caused "turn off" to merely disable auto mode
        // while the AC kept running in manual mode.
        //
        // Per the OEM firmware: the AC state setter calls acDevice.start(0) / acDevice.stop(0)
        // Parameter 0 = default zone (all zones).
        // Return value: 0 = success, 1 = failed, 2 = timeout, 3 = busy, 4 = invalid value
        try {
            String methodName = on ? "start" : "stop";
            Object result = BydDeviceHelper.callGetter(acDevice, methodName, 0);
            boolean success = (result instanceof Integer && ((Integer) result).intValue() == 0);
            
            if (!success && result instanceof Integer) {
                int code = ((Integer) result).intValue();
                // Retry once on BUSY (3) — AC controller may be processing a previous command
                if (code == 3) {
                    logger.info("AC " + methodName + " returned BUSY, retrying in 500ms...");
                    Thread.sleep(500);
                    result = BydDeviceHelper.callGetter(acDevice, methodName, 0);
                    success = (result instanceof Integer && ((Integer) result).intValue() == 0);
                }
                if (!success) {
                    logger.warn("AC " + methodName + " failed: result=" + result +
                        " (0=ok, 1=fail, 2=timeout, 3=busy, 4=invalid)");
                }
            }
            
            return success;
        } catch (Exception e) {
            logger.debug("setAcPower(" + on + ") via start/stop failed: " + e.getMessage());
            // Fallback: try the feature ID approach (less reliable but works on some older firmware)
            try {
                // AC_AUTO_MODE_SET with value 0 doesn't truly stop AC on most models,
                // but on some older DiLink 3.0 firmware it's the only available method.
                return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, on ? 1 : 0);
            } catch (Exception e2) {
                logger.debug("setAcPower fallback also failed: " + e2.getMessage());
                return false;
            }
        }
    }

    /**
     * Set the AC temperature. [tempCelsius] is a CELSIUS value from our own API surface; it is
     * converted to the head unit's display unit before the write, because the SDK takes the value
     * in the DISPLAYED unit and declares which one in the 4th argument.
     *
     * <p>The 4th argument used to be the constant 1. That is Celsius by luck, not by derivation:
     * on a head unit set to Fahrenheit the SDK expects 64..91 with unit 0, so the old code's
     * {@code 17..33} pre-check rejected every legitimate write outright — AC temperature simply
     * did nothing on a °F car. The OEM reads {@code getTemperatureUnit()} and clamps to 64..91 or
     * 17..33 accordingly (proven in its bytecode), which is what this now mirrors.
     *
     * <p>An unreadable unit falls back to Celsius — the previous hardcoded behaviour, so a device
     * that never answers the unit getter behaves exactly as it does today rather than losing the
     * write entirely.
     */
    public boolean setAcTemperature(int zone, double tempCelsius) {
        try {
            // NaN/Inf would survive to Math.round as 0 (or a rail) and then be CLAMPED into a
            // valid-looking setpoint — silently setting the dial to its minimum. Refuse here so
            // every caller is covered, not just the ones that range-check first.
            if (Double.isNaN(tempCelsius) || Double.isInfinite(tempCelsius)) {
                logger.warn("setAcTemperature: refusing non-finite temperature");
                return false;
            }
            int unit = readTempUnitNow();
            int effectiveUnit = (unit == BydVehicleData.UNAVAILABLE) ? 1 : unit;
            // Convert to the DISPLAY unit, then clamp in that unit's own band.
            int tempInt = (effectiveUnit == TEMP_UNIT_FAHRENHEIT)
                    ? (int) Math.round(tempCelsius * 9.0 / 5.0 + 32.0)
                    : (int) Math.round(tempCelsius);
            int clamped = clampSetpoint(tempInt, effectiveUnit);
            return setAcTemperatureRaw(zone, clamped, effectiveUnit);
        } catch (Exception e) {
            logger.debug("setAcTemperature failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write an ALREADY-display-unit, already-clamped setpoint. The single SDK seam for both the
     * absolute setter above and the relative step (which reads the dial in display units and must
     * not round-trip through Celsius — that conversion would lose a degree on a °F car, so
     * "+1 °F" could land back on the same value or skip two).
     *
     * @param displayTemp the value in [unit]'s own scale, pre-clamped by {@link #clampSetpoint}
     * @param unit        {@code getTemperatureUnit()}'s value, passed through to the SDK verbatim
     * @return true only on the SDK's success code (0) — never accept-on-no-throw
     */
    public boolean setAcTemperatureRaw(int zone, int displayTemp, int unit) {
        try {
            // Reject the UNAVAILABLE sentinel specifically. Without this, a Celsius-range value
            // paired with UNAVAILABLE would slip past the clamp check below (clampSetpoint treats
            // any non-zero unit as Celsius, so 22 clamps to itself) and hand Integer.MIN_VALUE to
            // the SDK as its unit argument. Only the sentinel is refused — any other non-zero
            // value is passed through verbatim, because "non-zero == Celsius" is the SDK's own
            // rule and the OEM forwards whatever getTemperatureUnit() returned rather than
            // normalizing it to 1.
            if (unit == BydVehicleData.UNAVAILABLE) {
                logger.warn("setAcTemperatureRaw: refusing write with unresolved unit");
                return false;
            }
            if (displayTemp != clampSetpoint(displayTemp, unit)) return false;   // caller bug guard
            // SDK: acDevice.setAcTemperature(zone, temp, 0, unit)
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcTemperature", zone, displayTemp, 0, unit);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAcTemperatureRaw failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Relative temperature step: read the dial, add [delta] (in DISPLAY units — so ±1 is one
     * dial notch whether the car shows °C or °F), clamp, write. Returns the new setpoint, or
     * {@link BydVehicleData#UNAVAILABLE} when the dial couldn't be read or the write failed.
     *
     * <p>Reading the dial is mandatory — there is no safe fallback. If the read misses we refuse
     * rather than assuming a value: stepping from a fabricated 24 (what both reference apps
     * default to) would yank the temperature to 25 from wherever it actually was.
     *
     * <p>Already at the end of the range is reported as SUCCESS with the unchanged value, not a
     * failure: pressing "+" at 33 C is a no-op the user expects, not an error to surface.
     *
     * <p><b>Asymmetric by design, matching both reference apps:</b> [area] is the dial READ
     * (1 = driver), [zone] is the write target, and zone 0 = {@code AC_TEMPERATURE_MAIN_DEPUTY}
     * sets BOTH dials. The OEM does exactly this (reads area 1, writes zone 0), and its
     * setter defaults zone to 0, so zone 0 is the proven "set the cabin temperature" target
     * rather than an oversight. On a dual-zone car with split dials this does move the passenger
     * side to match — which is the single-temperature behaviour these actions advertise. Pass
     * zone = area to step one dial only, once a per-dial setter is verified on real hardware.
     */
    public int stepAcTemperature(int zone, int area, int delta) {
        int current = readAcSetpointNow(area);
        if (current == BydVehicleData.UNAVAILABLE) {
            logger.warn("stepAcTemperature: dial unreadable (area " + area + ") — refusing to step");
            return BydVehicleData.UNAVAILABLE;
        }
        // Unit for the clamp. The value's OWN band decides it, because the value is what we are
        // about to add to and clamp — trusting getTemperatureUnit() over the reading would be
        // trusting a DIFFERENT getter about this one's scale. When the two disagree we refuse
        // rather than pick: a 22 reading with the unit claiming Fahrenheit would clamp 23 into
        // the F band and write 64, dropping the dial ~4 C in response to a "+1" press. Which
        // scale getTemprature actually returns is not device-proven here (the SDK javadoc
        // documents a Celsius range; the OEM's clamp implies display units), so a disagreement
        // means we do not know what we are holding — the one case where doing nothing is right.
        int bandUnit = inferUnitFromSetpoint(current);
        if (bandUnit == BydVehicleData.UNAVAILABLE) {
            logger.warn("stepAcTemperature: setpoint " + current + " matches no known dial band — refusing");
            return BydVehicleData.UNAVAILABLE;
        }
        int reportedUnit = readTempUnitNow();
        if (reportedUnit != BydVehicleData.UNAVAILABLE
                && (reportedUnit == TEMP_UNIT_FAHRENHEIT) != (bandUnit == TEMP_UNIT_FAHRENHEIT)) {
            logger.warn("stepAcTemperature: unit disagreement — getTemperatureUnit()=" + reportedUnit
                    + " but setpoint " + current + " is in the "
                    + (bandUnit == TEMP_UNIT_FAHRENHEIT ? "Fahrenheit" : "Celsius")
                    + " band; refusing rather than guessing the scale");
            return BydVehicleData.UNAVAILABLE;
        }
        int effectiveUnit = bandUnit;
        int target = clampSetpoint(current + delta, effectiveUnit);
        if (target == current) {
            logger.info("stepAcTemperature: already at limit (" + current + ") — no write");
            return current;
        }
        if (!setAcTemperatureRaw(zone, target, effectiveUnit)) return BydVehicleData.UNAVAILABLE;
        logger.info("stepAcTemperature: " + current + " -> " + target + " (delta " + delta
                + ", unit " + effectiveUnit + ")");
        return target;
    }

    public boolean setAcFanLevel(int level) {
        try {
            if (level < 1 || level > 7) return false;
            // Primary: named SDK method acDevice.setAcWindLevel(0, level).
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcWindLevel", 0, level);
            if (result instanceof Integer && ((Integer) result).intValue() == 0) return true;

            // Fallback: on some DiLink 3.0 firmware setAcWindLevel is a no-op
            // (returns null / non-zero). The generic feature write
            // set(1000, AC_WIND_LEVEL_SET, level) drives the fan directly and is
            // verified to work on the Dolphin (wheregoes/byd-apps research).
            // Only reached when the named path did NOT report success, so the
            // path that already works on other firmware is left untouched.
            logger.debug("setAcWindLevel named path returned " + result + "; trying generic feature write");
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_WIND_LEVEL_SET, level);
        } catch (Exception e) {
            logger.debug("setAcFanLevel failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAcWindMode(int mode) {
        try {
            // SDK method: acDevice.setAcWindMode(0, mode)
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcWindMode", 0, mode);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAcWindMode failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setFrontDefrost(boolean on) {
        return setDefrostFeature(BydFeatureIds.AC_DEFROST_FRONT_SET, on, "front");
    }

    public boolean setRearDefrost(boolean on) {
        return setDefrostFeature(BydFeatureIds.AC_DEFROST_REAR_SET, on, "rear");
    }

    /**
     * Write a defrost feature id, trying each accepted encoding until one lands — mirroring
     * the OEM vehicle-control app, which sends the preferred value first then a fallback ({@code
     * enable → 1 then 2}; {@code disable → 0 then 2}). Some trims accept only one of the
     * encodings, so a single fixed value silently missed. sendSetCommand success = code >= 0.
     */
    private boolean setDefrostFeature(int featureId, boolean on, String label) {
        int[] candidates = on ? new int[]{1, 2} : new int[]{0, 2};
        try {
            for (int v : candidates) {
                if (BydDeviceHelper.sendSetCommand(acDevice, featureId, v)) {
                    logger.info("set_" + label + "_defrost(" + on + ") accepted value=" + v);
                    return true;
                }
            }
            logger.info("set_" + label + "_defrost(" + on + ") refused all encodings");
        } catch (Exception e) {
            logger.debug("set_" + label + "_defrost failed: " + e.getMessage());
        }
        return false;
    }

    public boolean setAcCycleMode(int mode) {
        try {
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_CYCLE_MODE_SET, mode);
        } catch (Exception e) {
            logger.debug("setAcCycleMode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * AC auto mode on/off. Feature-id path (Ac.AUTO_MODE_SET): on writes 1; off tries 0
     * then 2 (the reference tries both accepted "off" encodings, first that lands wins).
     * sendSetCommand returns true on a non-negative HAL result.
     */
    public boolean setAcAutoMode(boolean on) {
        try {
            if (on) {
                return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, 1);
            }
            // Off: try 0 first, fall back to 2 (both are "off" per the OEM enum).
            if (BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, 0)) return true;
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, 2);
        } catch (Exception e) {
            logger.debug("setAcAutoMode failed: " + e.getMessage());
            return false;
        }
    }

    /** OEM temperature-zone control: SYNC links passenger to driver (mode 0); off restores separate control (mode 1). */
    public boolean setAcTemperatureSync(boolean synced) {
        Object result = BydDeviceHelper.callMethod(
                acDevice, "setAcTemperatureControlMode", 1, synced ? 0 : 1);
        return result instanceof Integer
                && isSdkWriteSuccess(acDevice, result, "setAcTemperatureControlMode");
    }

    /**
     * Air-intake mode on the AC cycle axis: {@code recirculate=true} → RECIRCULATION (cabin
     * air recycled), false → FRESH_AIR (outside air drawn in). Raw values match the OEM
     * enum verified against the reference SDK (FRESH_AIR=0 / RECIRCULATION=1) written via
     * the AC device's {@code AC_CYCLE_MODE_SET} feature-id — the same axis we already READ
     * as {@code getAcCycleMode} in collectAc.
     */
    public boolean setAcRecirculation(boolean recirculate) {
        try {
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_CYCLE_MODE_SET,
                    recirculate ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setAcRecirculation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Fan-only (ventilation, no compressor) mode on/off. Named-method on acDevice —
     * setAcVentilationState(int); try the 1-arg form, then the 2-arg (0, state) form
     * the OEM SDK also exposes. value = enabled?1:0. Success = Integer result == 0.
     */
    public boolean setFanOnlyMode(boolean on) {
        int state = on ? 1 : 0;
        try {
            Object r = BydDeviceHelper.callMethod(acDevice, "setAcVentilationState", state);
            if (r instanceof Integer && ((Integer) r).intValue() == 0) return true;
        } catch (Exception e) {
            logger.debug("setFanOnlyMode(1-arg) failed: " + e.getMessage());
        }
        try {
            Object r = BydDeviceHelper.callMethod(acDevice, "setAcVentilationState", 0, state);
            return r instanceof Integer && ((Integer) r).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setFanOnlyMode(2-arg) failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Steering-wheel heating on/off. Named-method on settingDevice —
     * setSteeringWheelHeatingState(int state), value on=2 / off=1 (per the OEM enum).
     * Treat a null or negative/sentinel (-2147482648) result as failure.
     */
    public boolean setSteeringWheelHeating(boolean on) {
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "setSteeringWheelHeatingState", on ? 2 : 1);
            if (r instanceof Integer) {
                int v = ((Integer) r).intValue();
                return v >= 0 && v != -2147482648;
            }
            return r != null;
        } catch (Exception e) {
            logger.debug("setSteeringWheelHeating failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Smart welcome-light on/off. Named-method on settingDevice —
     * setSmartWelcomeLightState(int), value on=1 / off=2. Success = Integer result >= 0.
     */
    public boolean setWelcomeLight(boolean on) {
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "setSmartWelcomeLightState", on ? 1 : 2);
            if (r instanceof Integer) return ((Integer) r).intValue() >= 0;
            return r != null;
        } catch (Exception e) {
            logger.debug("setWelcomeLight failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Headlight (headlamp) level / height. Named-method on settingDevice —
     * setHeadlampLevel(int), clamped 1..11 (the reference coerces into that band).
     * Success = Integer result >= 0.
     */
    public boolean setHeadlightLevel(int level) {
        int clamped = Math.max(1, Math.min(11, level));
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "setHeadlampLevel", clamped);
            if (r instanceof Integer) return ((Integer) r).intValue() >= 0;
            return r != null;
        } catch (Exception e) {
            logger.debug("setHeadlightLevel failed: " + e.getMessage());
            return false;
        }
    }

    /** Four-position exterior-light selector values used by the OEM CarSetting app. */
    public static final int HEADLIGHT_MODE_OFF = 1;
    public static final int HEADLIGHT_MODE_AUTO = 2;
    public static final int HEADLIGHT_MODE_PARKING = 3;
    public static final int HEADLIGHT_MODE_LOW_BEAM = 4;

    /**
     * Set the OEM exterior-light selector through the instrument HAL.
     *
     * <p>This is deliberately separate from {@link #setDayTimeLight(boolean)} and
     * {@link #setHeadlightLevel(int)}: the reference CarSetting app writes
     * {@code INSTRUMENT_HEADLIGHT_CONTROL_SET} on {@code BYDAutoInstrumentDevice}, with the
     * exact 1..4 selector domain above. The router applies the OEM Park-only safety rule to
     * {@link #HEADLIGHT_MODE_OFF}; the other modes restore or increase exterior lighting and
     * remain available while driving.
     */
    public boolean setHeadlightMode(int mode) {
        if (!isValidHeadlightMode(mode)) {
            logger.warn("setHeadlightMode: invalid mode " + mode);
            return false;
        }
        if (instrumentDevice == null) {
            logger.warn("setHeadlightMode: instrumentDevice unavailable");
            return false;
        }
        if (mode == HEADLIGHT_MODE_OFF
                && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_HEADLIGHT_OFF)) {
            return false;
        }
        try {
            return BydDeviceHelper.sendSetCommand(
                    instrumentDevice, BydFeatureIds.INSTRUMENT_HEADLIGHT_CONTROL_SET, mode);
        } catch (Exception e) {
            logger.debug("setHeadlightMode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Read the OEM exterior-light selector feedback, or {@link BydVehicleData#UNAVAILABLE}.
     */
    public int readHeadlightModeNow() {
        if (instrumentDevice == null) return BydVehicleData.UNAVAILABLE;
        try {
            Object value = BydDeviceHelper.callGet(
                    instrumentDevice,
                    BydFeatureIds.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK,
                    Integer.TYPE);
            int mode = BydDeviceHelper.getIntValue(value);
            return isValidHeadlightMode(mode) ? mode : BydVehicleData.UNAVAILABLE;
        } catch (Exception e) {
            logger.debug("readHeadlightModeNow failed: " + e.getMessage());
            return BydVehicleData.UNAVAILABLE;
        }
    }

    private static boolean isValidHeadlightMode(int mode) {
        return mode >= HEADLIGHT_MODE_OFF && mode <= HEADLIGHT_MODE_LOW_BEAM;
    }

    /**
     * Interior cabin / reading light on/off. PARITY with the OEM vehicle-control app's
     * turnInsideLightsOn/Off, which uses a TWO-TIER ladder — we previously only had the
     * second tier, so on a trim where the bodywork feature-id write silently no-ops the
     * cabin light never changed.
     *
     * <p>Tier 1 (PREFERRED, matches OEM vehicle-control app): the named method {@code turnOffInsideLight(int)}
     * on the SETTING device. Note the counter-intuitive name + inverted argument the OEM
     * uses: {@code turnOffInsideLight(2)} turns the light ON, {@code turnOffInsideLight(1)}
     * turns it OFF (OEM vehicle-control app: "Inside lights ON via turnOffInsideLight(state=2)").
     *
     * <p>Tier 2 (FALLBACK): the feature-id write {@code Body.INSIDE_LIGHT_STATE_SET} on the
     * BODYWORK device, value on=1 / off=2 (the original path). Reached only if the named
     * method is absent or fails.
     */
    public boolean setReadingLight(boolean on) {
        // Tier 1 — settingDevice.turnOffInsideLight(on ? 2 : 1). callMethod returns null if
        // the method is absent (→ fall through to the feature-id write); a non-negative
        // Integer / any non-null return counts as accepted (same convention as the other
        // named setting writes, e.g. setWelcomeLight).
        try {
            Object r = BydDeviceHelper.callMethod(settingDevice, "turnOffInsideLight", on ? 2 : 1);
            if (r != null) {
                boolean ok = !(r instanceof Integer) || ((Integer) r).intValue() >= 0;
                if (ok) {
                    logger.info("setReadingLight(" + on + ") via turnOffInsideLight(" + (on ? 2 : 1) + ")");
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("turnOffInsideLight failed, trying feature-id: " + e.getMessage());
        }
        // Tier 2 — feature-id write on bodyworkDevice (on=1 / off=2).
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_INSIDE_LIGHT_STATE_SET, on ? 1 : 2);
        } catch (Exception e) {
            logger.debug("setReadingLight fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ambient-light music mode on/off (ambient lights pulse to audio). Feature-id path
     * (Body.ATMOSPHERE_LIGHT_MUSIC_EXECUTE) on bodyworkDevice, value on=1 / off=2.
     */
    public boolean setAmbientMusicMode(boolean on) {
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_ATMOSPHERE_LIGHT_MUSIC, on ? 1 : 2);
        } catch (Exception e) {
            logger.debug("setAmbientMusicMode failed: " + e.getMessage());
            return false;
        }
    }

    // --- Windows ---
    public boolean setSunWindowCommand(int area, int command) {
        try {
            // area: 5=Sunroof, 6=Sunshade
            if (area < 5 || area > 6) return false;
            // incoming command: 1=open, 2=close, 3=stop, 4=half, (5=breath only for sunroof)
            // Remap to these values to match windows (3 and 4 are swapped)
            // SDK command: 1=open, 2=close, 3=half, 4=stop, (5=breath only for sunroof)
            int voiceCommand = sunWindowVoiceCommand(command);
            if (voiceCommand < 0) return false;
            // SDK method: bodyworkDevice.voiceCtlMoonRoof(cmd) or bodyworkDevice.voiceCtlSunshadePanel(cmd)
            String cmd = area == 5 ? "voiceCtlMoonRoof" : "voiceCtlSunshadePanel";
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, cmd, voiceCommand);
            boolean ok = result instanceof Integer && ((Integer) result).intValue() == 0;
            // Log the ACTUAL HAL return at info level so the field-reported "sunroof is
            // hit and miss" is diagnosable: distinguishes a rejected write (non-zero /
            // non-Integer result) from an accepted-but-ineffective one (returns 0 yet the
            // roof doesn't move). voiceCtl* is a momentary command with no confirmed
            // success contract on this firmware, so we surface the raw result rather than
            // guessing a retry that could double-actuate a moving roof.
            logger.info("setSunWindow " + (area == 5 ? "Sunroof" : "Sunshade")
                    + " cmd=" + command + " via " + cmd + "(" + voiceCommand + ")"
                    + " -> result=" + result + " (ok=" + ok + ")");
            return ok;
        } catch (Exception e) {
            logger.warn("Set " + (area == 5 ? "Sunroof" : "Sunshade") +  " failed: " + e.getMessage());
            return false;
        }
    }

    static int sunWindowVoiceCommand(int command) {
        if (command < 1 || command > 5) return -1;
        return command == 3 ? 4 : command == 4 ? 3 : command;
    }

    public boolean setWindowCommand(int area, int command) {
        try {
            // area: 1=LF, 2=RF, 3=LR, 4=RR, 5=Sunroof, 6=Sunshade
            // command: 1=open, 2=close, 3=stop, 4=half, 5=breath
            // Sunshade and Sunroof have different command for set
            if (area >= 5 && area <= 6) return setSunWindowCommand(area, command);
            if (area < 1 || area > 4) return false;
            // SDK method: bodyworkDevice.setAllWindowState(lf, rf, lr, rr)
            // Only the target area gets the command, others get 0
            int lf = area == 1 ? command : 0;
            int rf = area == 2 ? command : 0;
            int lr = area == 3 ? command : 0;
            int rr = area == 4 ? command : 0;
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, "setAllWindowState", lf, rf, lr, rr);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setWindowCommand failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAllWindowsCommand(int command) {
        // Bound here as well as at the HTTP edge: MQTT and the keymap build the command directly,
        // and 0 is the HAL's "no command for this area" filler — a silent no-op that returns 0 and
        // so reads as success. Anything outside 1..3 is undefined for a 4-window write.
        if (command < 1 || command > 3) {
            logger.warn("setAllWindowsCommand: refusing out-of-domain command " + command
                    + " (1=open, 2=close, 3=stop)");
            return false;
        }
        try {
            // command: 1=open, 2=close, 3=stop
            // SDK method: bodyworkDevice.setAllWindowState(cmd, cmd, cmd, cmd)
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, "setAllWindowState", command, command, command, command);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAllWindowsCommand failed: " + e.getMessage());
            return false;
        }
    }

    // Per-area executor so a new target on one window cancels its prior
    // motion without affecting the others. Lazy-init.
    private final java.util.concurrent.ExecutorService[] windowExecutors =
            new java.util.concurrent.ExecutorService[6];
    private final java.util.concurrent.Future<?>[] windowMotionTasks =
            new java.util.concurrent.Future<?>[6];

    private synchronized java.util.concurrent.ExecutorService getWindowExecutor(int areaIdx) {
        java.util.concurrent.ExecutorService ex = windowExecutors[areaIdx];
        if (ex == null) {
            ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "WinMove-" + (areaIdx + 1));
                t.setDaemon(true);
                return t;
            });
            windowExecutors[areaIdx] = ex;
        }
        return ex;
    }

    private int readWindowPercent(int area) {
        try {
            Object wp = BydDeviceHelper.callGetter(bodyworkDevice, "getWindowOpenPercent", area);
            if (wp instanceof Number) {
                int pct = ((Number) wp).intValue();
                // Only 0..100 is a position. Returning a rail (65535/65534) or any out-of-domain
                // value verbatim would be read as a real reading by every caller: the closed-loop
                // task would compute its direction from it and the "already at target" test could
                // short-circuit, while the UI would render it as a window position.
                if (pct >= 0 && pct <= 100) return pct;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Closed-loop window positioning: drives the window towards {@code targetPercent}
     * and stops when it reaches the target (within tolerance), the motor stalls,
     * or a safety timeout elapses. Returns immediately; motion runs on a
     * per-window background thread so a fresh target cancels the previous one.
     *
     * @param area     1=LF, 2=RF, 3=LR, 4=RR
     * @param targetPercent 0 (closed) through 100 (fully open)
     * @return true if motion was scheduled, false if inputs were invalid or
     *         the window is already at the target.
     */
    public boolean moveWindowToPercent(int area, int targetPercent) {
        if (area < 1 || area > 6) return false;
        if (targetPercent < 0 || targetPercent > 100) return false;
        int areaIdx = area - 1;

        final int target = targetPercent;
        // Set the tolerance to 0 when fully open or closed requested to prevent windows being slightly open
        final int tolerance = (targetPercent == 100 || targetPercent == 0) ? 0 : 5; // ±5 % is the realistic floor (motor coast)
        final long pollIntervalMs = 200;  // SDK getter is cheap; tight loop = clean stop
        final long maxRunMs = 12_000;     // window full-travel ≈ 4–6 s; cap at 12 s
        final long stallWindowMs = 1_200; // no progress for this long → stall / pinch

        // Cancel any in-flight motion for this window FIRST. Deciding "already at target" before
        // cancelling silently dropped the user's newest request: a window sweeping past 50% on its
        // way to 100% reads as already-at-50, so a fresh "50%" tap returned without cancelling and
        // the old task kept driving to 100. The cancelled task's own `if (!stopped)` path issues the
        // stop, and the per-area single-thread executor runs it before the new task starts.
        java.util.concurrent.Future<?> prev = windowMotionTasks[areaIdx];
        if (prev != null && !prev.isDone()) prev.cancel(true);

        int initial = readWindowPercent(area);
        if (initial >= 0 && Math.abs(initial - target) <= tolerance) {
            // TRUE: the requested position is already the actual one, so the command is satisfied.
            // Returning false reported "window move FAILED" to the caller — the keymap and
            // automation surfaces both branch on that outcome, so asking for a position the window
            // already holds looked like a broken control.
            logger.debug("Window " + area + " already near target (" + initial + "% vs " + target + "%)");
            return true;
        }

        // No position readback on this slot (common for sunroof/sunshade): closed-loop positioning
        // is impossible, because `reached` and the stall detector both sit inside `now >= 0` so the
        // only brake left is the 12s cap — and the direction would be GUESSED from an assumed
        // mid-travel, so "half open" could drive the pane fully shut and hold the motor for 12s.
        //
        // Only the two unambiguous endpoints are safe without feedback: 0 can only mean close, 100
        // can only mean open, and the pane's own limit stops it. Anything between is refused here,
        // synchronously, so the caller gets a real failure instead of a silent wrong movement.
        if (initial < 0) {
            if (target != 0 && target != 100) {
                logger.warn("Window " + area + ": no position readback — refusing target " + target
                        + "% (only 0/100 are safe without feedback)");
                return false;
            }
            int endpointDir = (target == 100) ? 1 : 2;
            logger.info("Window " + area + ": no position readback — driving to the "
                    + (endpointDir == 1 ? "open" : "closed") + " endpoint");
            boolean ok = setWindowCommand(area, endpointDir);
            if (!ok) {
                try { setWindowCommand(area, 3); } catch (Exception ignored) {}
            }
            return ok;
        }

        Runnable task = () -> {
            try {
                int start = readWindowPercent(area);
                // Re-read can still fail between the check above and here; fall back to the value
                // that passed the gate rather than guessing mid-travel.
                if (start < 0) start = initial;

                int direction = target > start ? 1 : 2; // 1=open, 2=close
                boolean issued = setWindowCommand(area, direction);
                if (!issued) {
                    // Do NOT return here. A false result only means the HAL did not answer the
                    // success code — on this firmware a momentary window/roof command can be
                    // accepted and MOVE while returning non-zero. Returning would skip every stop
                    // path below and leave the pane travelling to its mechanical limit. Issue one
                    // stop and give up instead.
                    logger.warn("Window " + area + ": initial command returned failure — issuing a"
                            + " stop in case it moved anyway, then aborting");
                    try { setWindowCommand(area, 3); } catch (Exception ignored) {}
                    return;
                }

                long startMs = System.currentTimeMillis();
                long lastProgressMs = startMs;
                int lastSeenPercent = start;
                boolean stopped = false;

                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(pollIntervalMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    int now = readWindowPercent(area);
                    long elapsed = System.currentTimeMillis() - startMs;

                    if (now >= 0) {
                        // Stop once we've crossed the target in the direction we
                        // were moving. Crossing-based comparison avoids stopping
                        // early on a noisy reading near the boundary.
                        boolean reached = direction == 1
                                ? now >= target - tolerance
                                : now <= target + tolerance;
                        if (reached) {
                            setWindowCommand(area, 3);
                            stopped = true;
                            logger.info("Window " + area + " reached target=" + target
                                    + "% (final=" + now + "%)");
                            break;
                        }

                        if (Math.abs(now - lastSeenPercent) >= 1) {
                            lastSeenPercent = now;
                            lastProgressMs = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - lastProgressMs > stallWindowMs) {
                            setWindowCommand(area, 3);
                            stopped = true;
                            logger.warn("Window " + area + " stalled at " + now
                                    + "% (target=" + target + "%) — stopped");
                            break;
                        }
                    }

                    if (elapsed > maxRunMs) {
                        setWindowCommand(area, 3);
                        stopped = true;
                        logger.warn("Window " + area + " motion timed out at "
                                + (now >= 0 ? now : -1) + "% — stopped");
                        break;
                    }
                }

                if (!stopped) setWindowCommand(area, 3);
            } catch (Exception e) {
                logger.warn("Window " + area + " motion task error: " + e.getMessage());
                try { setWindowCommand(area, 3); } catch (Exception ignored) {}
            }
        };

        windowMotionTasks[areaIdx] = getWindowExecutor(areaIdx).submit(task);
        return true;
    }

    // --- Door lock state ---

    /** Driver's-door lock states from {@code BYDAutoOtaDevice.getLFDoorLockState}. */
    public static final int DOOR_STATE_INVALID = 0;
    public static final int DOOR_STATE_UNLOCK = 1;
    public static final int DOOR_STATE_LOCK = 2;

    /**
     * Read the driver's-door (LF) lock state via the OTA device.
     *
     * <p>This is the same local rail AccSentry/CameraDaemon use
     * ({@code BYDAutoOtaDevice.getLFDoorLockState}) — it works ACC=OFF with
     * sub-second latency on DiLink 3.0. The legacy
     * {@code BYDAutoDoorLockDevice.getDoorLockStatus(area)} path returns
     * INVALID to user-UID processes on every field firmware and is not used.
     *
     * @return {@link #DOOR_STATE_INVALID}(0), {@link #DOOR_STATE_UNLOCK}(1),
     *         or {@link #DOOR_STATE_LOCK}(2).
     */
    public int readDoorLockState() {
        if (otaDevice == null) return DOOR_STATE_INVALID;
        try {
            Object v = BydDeviceHelper.callGetter(otaDevice, "getLFDoorLockState");
            if (v instanceof Number) {
                int state = ((Number) v).intValue();
                if (state == DOOR_STATE_UNLOCK || state == DOOR_STATE_LOCK) {
                    return state;
                }
            }
        } catch (Exception e) {
            logger.debug("readDoorLockState error: " + e.getMessage());
        }
        return DOOR_STATE_INVALID;
    }

    // UNLOCK-direction debounce for collectLockState, mirroring CameraDaemon's unlock poll:
    // the OTA getLFDoorLockState rail emits transient/bluff DOOR_STATE_UNLOCK reads on this
    // hardware, so a single one must NOT fire an "unlocked" automation. Require 2 consecutive
    // UNLOCK reads before publishing unlocked; LOCK publishes eagerly (a real lock is safe to
    // report immediately and resets the streak). Read/written only on the BydDataPoll thread,
    // but volatile because setAccState() swaps that single-thread executor on every ACC edge
    // (shutdownNow + new executor), so successive ticks can run on different threads with no
    // happens-before. A stale read only nudges the debounce counter by one for one cycle (the
    // transition gate still prevents any spurious/duplicate fire), but volatile closes the gap
    // for the price of one word.
    private volatile int lockUnlockStreak = 0;

    /**
     * Publish the central-lock state to the automation engine as a {@code locked}/
     * {@code unlocked} word, sourced from the OTA rail ({@link #readDoorLockState}). Called
     * from {@link #collectAll} on the always-alive poll loop when an automation references
     * {@link com.overdrive.app.automation.condition.BydEvent#LOCK}. {@code Automations.update}
     * is transition-gated + deduped, so a repeated same-state read no-ops; a genuine
     * lock↔unlock edge fires the trigger. An INVALID/unreadable tick is skipped so we never
     * manufacture a false transition. This is the ACC-agnostic counterpart to the
     * surveillance arm-gate's own lock funnel (which stays ACC-off only, by design) — and it
     * shares that funnel's 2-consecutive-read UNLOCK debounce so a transient OTA misread
     * can't fire a spurious "unlocked" automation.
     */
    private void collectLockState() {
        int state = readDoorLockState();
        boolean locked;
        if (state == DOOR_STATE_LOCK) {
            lockUnlockStreak = 0;
            locked = true;
        } else if (state == DOOR_STATE_UNLOCK) {
            // Debounce the UNLOCK direction: need 2 consecutive reads before publishing.
            if (++lockUnlockStreak < 2) return;
            locked = false;
        } else {
            return; // INVALID/unreadable → no false edge, and don't disturb the streak
        }
        try {
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.LOCK,
                    locked ? "locked" : "unlocked");
        } catch (Throwable t) {
            logger.debug("collectLockState publish failed: " + t.getMessage());
        }
    }

    // --- Tailgate ---

    public boolean openTailgate() {
        // Method 1: SettingDevice.voiceCtlBackDoor(1) — official OEM vehicle-control app method
        if (settingDevice != null) {
            if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_TRUNK)) return false;
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 1);
                logger.info("openTailgate voiceCtlBackDoor(1) result: " + result);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("openTailgate voiceCtlBackDoor failed: " + e.getMessage());
            }
        }
        // Method 2: Bodywork BACK_DOOR_TRIGGER
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_TRUNK)) return false;
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 1);
        } catch (Exception e) {
            logger.debug("openTailgate BACK_DOOR_TRIGGER failed: " + e.getMessage());
            return false;
        }
    }

    public boolean closeTailgate() {
        // SOTA FIX: the OEM firmware uses value 3 for close via SETTING_VOICE_CTRL_BACK_DOOR_SET
        // Values: 1=open, 2=stop, 3=close (confirmed from the OEM vehicle-control app (decompiled))
        
        // Method 1: SettingDevice sendSetCommand with value 3 (close)
        if (settingDevice != null) {
            if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_TRUNK)) return false;
            try {
                boolean result = BydDeviceHelper.sendSetCommand(settingDevice, 
                    BydFeatureIds.SETTING_VOICE_CTRL_BACK_DOOR_SET, 3);
                logger.info("closeTailgate sendSetCommand(VOICE_CTRL_BACK_DOOR, 3) result: " + result);
                if (result) return true;
            } catch (Exception e) {
                logger.debug("closeTailgate sendSetCommand failed: " + e.getMessage());
            }
            
            // Method 1b: Try voiceCtlBackDoor(3) directly
            if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_TRUNK)) return false;
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 3);
                logger.info("closeTailgate voiceCtlBackDoor(3) result: " + result);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("closeTailgate voiceCtlBackDoor(3) failed: " + e.getMessage());
            }
        }
        
        // Method 2: Bodywork BACK_DOOR_TRIGGER with value 3 (close)
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_TRUNK)) return false;
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 3);
        } catch (Exception e) {
            logger.debug("closeTailgate BACK_DOOR_TRIGGER(3) failed: " + e.getMessage());
            return false;
        }
    }

    public boolean stopTailgate() {
        // SOTA FIX: the OEM firmware uses value 2 for stop
        // Values: 1=open, 2=stop, 3=close
        
        // Method 1: SettingDevice sendSetCommand with value 2 (stop)
        if (settingDevice != null) {
            try {
                boolean result = BydDeviceHelper.sendSetCommand(settingDevice,
                    BydFeatureIds.SETTING_VOICE_CTRL_BACK_DOOR_SET, 2);
                logger.info("stopTailgate sendSetCommand(VOICE_CTRL_BACK_DOOR, 2) result: " + result);
                if (result) return true;
            } catch (Exception e) {
                logger.debug("stopTailgate sendSetCommand failed: " + e.getMessage());
            }
            
            // Fallback: voiceCtlBackDoor(2)
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 2);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("stopTailgate voiceCtlBackDoor(2) failed: " + e.getMessage());
            }
        }
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 0);
        } catch (Exception e) {
            logger.debug("stopTailgate BACK_DOOR_TRIGGER failed: " + e.getMessage());
            return false;
        }
    }

    // --- AVAS / Exterior Speaker ---

    /** Get the multimedia device (for direct access by audio test handler). */
    public Object getMultimediaDevice() {
        return multimediaDevice;
    }

    /** Get exterior speaker state: 1=enabled, 0=disabled, null=unavailable or unsupported. */
    public Integer getExteriorSpeakerState() {
        if (multimediaDevice == null) return null;
        try {
            Method m = multimediaDevice.getClass().getMethod("getExteriorSpeakerState");
            Object result = m.invoke(multimediaDevice);
            return (result instanceof Integer) ? (Integer) result : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            logger.debug("getExteriorSpeakerState failed: " + e.getMessage());
            return null;
        }
    }

    /** Set exterior speaker state: 1=enable, 0=disable. Returns false if unsupported on this device. */
    public boolean setExteriorSpeakerState(int state) {
        if (multimediaDevice == null) return false;
        try {
            Method m = multimediaDevice.getClass().getMethod("setExteriorSpeakerState", int.class);
            m.invoke(multimediaDevice, state);
            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("setExteriorSpeakerState: method not present on multimedia device — exterior speaker routing unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setExteriorSpeakerState failed: " + e.getMessage());
            return false;
        }
    }

    /** Get AVAS sound source type. Returns null if unsupported. */
    public Integer getAVASSoundSource() {
        if (multimediaDevice == null) return null;
        try {
            Method m = multimediaDevice.getClass().getMethod("getAVASSoundSource");
            Object result = m.invoke(multimediaDevice);
            return (result instanceof Integer) ? (Integer) result : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            logger.debug("getAVASSoundSource failed: " + e.getMessage());
            return null;
        }
    }

    /** Set AVAS sound source type. Returns false if unsupported on this device. */
    public boolean setAVASSoundSource(int sourceType) {
        if (multimediaDevice == null) return false;
        try {
            Method m = multimediaDevice.getClass().getMethod("setAVASSoundSource", int.class);
            m.invoke(multimediaDevice, sourceType);
            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("setAVASSoundSource: method not present on multimedia device — AVAS routing unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setAVASSoundSource failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Probe the multimedia device for any AVAS / exterior-speaker / outside-sound related methods.
     * Returns a list of method signatures (name + param types) whose name matches the regex.
     * Used by the audio test handler to discover what the OEM build actually exposes.
     */
    public java.util.List<String> probeMultimediaMethods(String regex) {
        java.util.List<String> matches = new java.util.ArrayList<>();
        if (multimediaDevice == null) return matches;
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return matches;
        }
        Class<?> cls = multimediaDevice.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!p.matcher(m.getName()).find()) continue;
                StringBuilder sig = new StringBuilder();
                sig.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(params[i].getSimpleName());
                }
                sig.append(')');
                matches.add(sig.toString());
            }
            cls = cls.getSuperclass();
        }
        java.util.Collections.sort(matches);
        return matches;
    }

    /** Check if multimedia device is available. */
    public boolean isMultimediaAvailable() {
        return multimediaDevice != null;
    }

    // --- Charging ---
    // The smart-charging schedule lives in BYD cloud, not the HAL. The Seal HAL
    // exposes setChargeStop*/getChargeStop* methods that look like they should
    // work but: getChargeStopSupportConfig=0, getters return 0xFFFF, setters
    // silently return success-but-no-op. See feedback_byd_hal_unreliable_signals.
    // All schedule reads/writes go through BydCloudClient smart-charging
    // endpoints in VehicleControlApiHandler / VehicleCommandRouter.

    // Generic charge limit and PHEV SOC hold are distinct controls.
    //
    // Generic charge limit uses BYDAutoChargingDevice
    // setChargeStopCapacityState (target %) and setChargeStopSwitchState
    // (master on/off). Some Seal trims accept these writes without applying
    // them, so every write requires matching readback before the control is
    // exposed as supported.
    //
    // SOC hold uses setSOCTarget + setSocSaveSwitch and is intentionally
    // available only through the explicit SOC-hold commands below. It controls
    // PHEV driving behavior, not the generic charge-stop cutoff.
    //
    // The smart-charging SCHEDULE (distinct from the cap) lives in BYD cloud,
    // not the HAL — see BydCloudClient smart-charging endpoints.

    public static final int SOC_TARGET_MIN = 15;      // SET_DR_SOC_TARGET_MIN
    public static final int SOC_TARGET_MAX = 70;      // SET_DR_SOC_TARGET_MAX
    private static final int SOC_TARGET_FLOOR_DEFAULT = SOC_TARGET_MIN;
    private static final int SOC_TARGET_FLOOR_ALT = 25; // when getSOCConfig()==2

    private volatile boolean chargeCapProbed = false;
    private volatile boolean chargeCapSupported = false;
    /**
     * Serializes only generic charge-stop mutations and their confirmation reads.
     * The HAL has separate capacity and switch registers, but capacity support is
     * inferred from a write/readback pair, so overlapping writes can otherwise
     * make a working backend look unsupported.
     */
    private final Object chargeCapTransactionLock = new Object();
    /** Effective cap the last accepted write actually applied (post-clamp); -1 if none yet. */
    private volatile int lastAppliedCapPercent = -1;
    /** Effective range and path from the last verified generic charge-limit write. */
    private volatile int chargeCapMinimumPercent = -1;
    private volatile int chargeCapMaximumPercent = -1;
    private volatile String chargeCapControlKind = "unknown";

    /**
     * Final state sampled while a combined charge-cap write still owns the transaction lock.
     * The HAL exposes independent capacity and switch setters, so a rejected second setter must
     * be reported as a partial application instead of hiding a capacity change that took effect.
     */
    public static final class ChargeCapUpdateResult {
        public final boolean fullyApplied;
        public final int capacityPercent;
        public final int enabledState;

        private ChargeCapUpdateResult(boolean fullyApplied, int capacityPercent, int enabledState) {
            this.fullyApplied = fullyApplied;
            this.capacityPercent = capacityPercent;
            this.enabledState = enabledState;
        }

        public boolean partiallyApplied(int requestedPercent, boolean requestedEnabled) {
            return !fullyApplied
                    && (capacityPercent == requestedPercent
                    || enabledState == (requestedEnabled ? 1 : 0));
        }
    }

    /** Last known cap %. -1 if never probed/read or the HAL returned a sentinel. */
    public int getChargeCapPercent() {
        return readChargeStopCapacity();
    }

    /** Effective cap the vehicle holds after the last accepted write; -1 if none. */
    public int getLastAppliedCapPercent() {
        return lastAppliedCapPercent;
    }

    public int getChargeCapMinimumPercent() {
        return chargeCapMinimumPercent;
    }

    public int getChargeCapMaximumPercent() {
        return chargeCapMaximumPercent;
    }

    /** {@code charge_stop} after a verified generic-limit write; {@code unknown} otherwise. */
    public String getChargeCapControlKind() {
        return chargeCapControlKind;
    }

    /** Last known on/off state. -1 if unsupported/read failed or a sentinel. */
    public int getChargeCapEnabled() {
        return readChargeStopSwitch();
    }

    /**
     * Has the BEV charge-cap been observed to actually take effect on this
     * trim? null = not yet probed, true/false = result of the first write.
     * UI uses this to hide the section on no-op trims.
     */
    public Boolean isChargeCapSupported() {
        return chargeCapProbed ? Boolean.valueOf(chargeCapSupported) : null;
    }

    // OEM ElectricLimit state values: 1=6 A, 2=8 A, 3=10 A, 4=16 A, 5=maximum.
    public static final int AC_CHARGE_CURRENT_6A = 1;
    public static final int AC_CHARGE_CURRENT_8A = 2;
    public static final int AC_CHARGE_CURRENT_10A = 3;
    public static final int AC_CHARGE_CURRENT_16A = 4;
    public static final int AC_CHARGE_CURRENT_MAX = 5;

    private static boolean isValidAcChargingCurrentLimitState(int state) {
        return state >= AC_CHARGE_CURRENT_6A && state <= AC_CHARGE_CURRENT_MAX;
    }

    /** Human-readable value for REST/MQTT/UI readback. */
    public static String acChargingCurrentLimitLabel(int state) {
        switch (state) {
            case AC_CHARGE_CURRENT_6A: return "6 A";
            case AC_CHARGE_CURRENT_8A: return "8 A";
            case AC_CHARGE_CURRENT_10A: return "10 A";
            case AC_CHARGE_CURRENT_16A: return "16 A";
            case AC_CHARGE_CURRENT_MAX: return "Max";
            default: return null;
        }
    }

    private int readSettingIntFeature(int featureId) {
        if (settingDevice == null || !BydFeatureIds.isResolved(featureId)) {
            return BydVehicleData.UNAVAILABLE;
        }
        try {
            Object value = BydDeviceHelper.callGet(settingDevice, featureId, Integer.TYPE);
            if (value == null) return BydVehicleData.UNAVAILABLE;
            return BydDeviceHelper.getIntValue(value);
        } catch (Exception e) {
            logger.debug("Setting feature 0x" + Integer.toHexString(featureId)
                    + " read failed: " + e.getMessage());
            return BydVehicleData.UNAVAILABLE;
        }
    }

    static boolean isSettingFeatureUnavailable(int value) {
        return value == BydVehicleData.UNAVAILABLE
                || value == BydFeatureIds.BMS_UNAVAILABLE
                || value == BydFeatureIds.INVALID_VALUE
                || value == BydFeatureIds.INVALID_VALUE_2
                || value == 65535
                || value < 0;
    }

    public static Boolean resolveAcChargingCurrentLimitSupport(
            int configState, int currentState, Boolean previousVerdict) {
        if (isValidAcChargingCurrentLimitState(currentState)) {
            return Boolean.TRUE;
        }
        // Once a real 1..5 readback has proved the hardware, an unavailable state or an
        // inconsistent config byte cannot make that physical capability disappear.
        if (Boolean.TRUE.equals(previousVerdict)) {
            return Boolean.TRUE;
        }
        if (!isSettingFeatureUnavailable(configState)) {
            return Boolean.valueOf(configState == 2);
        }
        return previousVerdict;
    }

    /** One coherent capability/readback sample for the current-limit API and UI. */
    public static final class AcChargingCurrentLimitStatus {
        public final Boolean supported;
        public final boolean available;
        public final int configState;
        public final int state;

        private AcChargingCurrentLimitStatus(
                Boolean supported, boolean available, int configState, int state) {
            this.supported = supported;
            this.available = available;
            this.configState = configState;
            this.state = state;
        }
    }

    /**
     * Read whether the vehicle advertises the OEM AC current-limit control.
     *
     * <p>The config field equals 2 when this setting is fitted. A framework unavailable sentinel
     * means "cannot read now", not "unsupported"; the last real verdict is retained across that
     * state. The setting's own config and valid 1..5 readback are authoritative, avoiding a false
     * negative from a temporarily ambiguous drivetrain classification.
     */
    public AcChargingCurrentLimitStatus getAcChargingCurrentLimitStatus() {
        int config = readSettingIntFeature(
                BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_CONFIG_STATUS);
        boolean configAvailable = !isSettingFeatureUnavailable(config);
        int rawState = readSettingIntFeature(
                BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS);
        int state = isValidAcChargingCurrentLimitState(rawState)
                ? rawState : BydVehicleData.UNAVAILABLE;
        Boolean supported = resolveAcChargingCurrentLimitSupport(
                config, rawState, acChargingCurrentLimitSupported);
        if (configAvailable || isValidAcChargingCurrentLimitState(rawState)) {
            acChargingCurrentLimitSupported = supported;
        }
        boolean available = Boolean.TRUE.equals(supported)
                ? isValidAcChargingCurrentLimitState(state)
                : configAvailable && Boolean.FALSE.equals(supported);
        return new AcChargingCurrentLimitStatus(
                supported, available, config, state);
    }

    public boolean isAcChargingCurrentLimitSupported() {
        return Boolean.TRUE.equals(getAcChargingCurrentLimitStatus().supported);
    }

    /** Current OEM AC current-limit state (1..5), or UNAVAILABLE. */
    public int getAcChargingCurrentLimitState() {
        return getAcChargingCurrentLimitStatus().state;
    }

    /**
     * Set the AC inlet current limit and require matching HAL readback.
     *
     * <p>This is a configured limit, not measured charging current; it never feeds charging power,
     * delivered energy, or average-power accounting.
     */
    public synchronized boolean setAcChargingCurrentLimitState(int state) {
        if (!isValidAcChargingCurrentLimitState(state)) {
            logger.warn("setAcChargingCurrentLimitState: invalid state " + state);
            return false;
        }
        AcChargingCurrentLimitStatus status = getAcChargingCurrentLimitStatus();
        if (!Boolean.TRUE.equals(status.supported)
                || !status.available
                || !BydFeatureIds.isResolved(
                        BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET)) {
            logger.warn("setAcChargingCurrentLimitState: capability unavailable or unsupported");
            return false;
        }
        boolean daemonAccepted = BydDeviceHelper.sendSetCommand(
                settingDevice,
                BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET,
                state);
        try {
            VehicleActuatorBridge.dispatchAcChargeCurrentLimit(state);
        } catch (Throwable appDispatchFailure) {
            logger.debug("AC charge current app-process dispatch failed: "
                    + appDispatchFailure.getMessage());
        }

        int readBack = BydVehicleData.UNAVAILABLE;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            readBack = readSettingIntFeature(
                    BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS);
            if (readBack == state) break;
        }
        boolean confirmed = readBack == state;
        logger.info("AC charge current limit: requested=" + state
                + " (" + acChargingCurrentLimitLabel(state) + ") readBack=" + readBack
                + " daemonAccepted=" + daemonAccepted + " confirmed=" + confirmed);
        return confirmed;
    }

    private void resetChargeCapVerification() {
        synchronized (chargeCapTransactionLock) {
            chargeCapProbed = false;
            chargeCapSupported = false;
            lastAppliedCapPercent = -1;
            chargeCapMinimumPercent = -1;
            chargeCapMaximumPercent = -1;
            chargeCapControlKind = "unknown";
        }
    }

    /**
     * Re-establish process-local charge-limit support after a fresh device
     * initialization without changing the vehicle's requested limit or switch.
     *
     * <p>The SDK provides no reliable read-only support flag. When both
     * registers already have in-range values, write the same capacity back and
     * require the capacity plus unchanged switch to read back exactly. An
     * unavailable reading leaves support unknown so a later explicit command
     * can attempt its normal verified write.
     */
    public Boolean reprobeChargeCapFromCurrentState() {
        synchronized (chargeCapTransactionLock) {
            if (chargeCapProbed) return Boolean.valueOf(chargeCapSupported);

            int capacity = readChargeStopCapacity();
            int enabled = readChargeStopSwitch();
            if (capacity < 50 || capacity > 100 || (enabled != 0 && enabled != 1)) {
                return null;
            }
            if (!setChargeStopCapacityFallback(capacity)) {
                return chargeCapProbed ? Boolean.valueOf(chargeCapSupported) : null;
            }

            int capacityReadBack = readChargeStopCapacity();
            int enabledReadBack = readChargeStopSwitch();
            boolean confirmed = capacityReadBack == capacity && enabledReadBack == enabled;
            if (!confirmed) {
                chargeCapProbed = true;
                chargeCapSupported = false;
                chargeCapMinimumPercent = -1;
                chargeCapMaximumPercent = -1;
                chargeCapControlKind = "unknown";
            }
            logger.info("charge-stop restart re-probe: capacity=" + capacity
                    + " enabled=" + enabled + " capacityReadBack=" + capacityReadBack
                    + " enabledReadBack=" + enabledReadBack + " supported=" + confirmed);
            return Boolean.valueOf(confirmed);
        }
    }

    /**
     * Set a generic charging cutoff only through the charge-stop backend.
     * SOC target/hold is a PHEV driving feature and is intentionally exposed
     * solely through the explicit SOC-hold commands below.
     */
    public boolean setChargeCapPercent(int percent) {
        synchronized (chargeCapTransactionLock) {
            return setChargeStopCapacityFallback(percent);
        }
    }

    /**
     * Apply both generic charge-stop registers as one SDK transaction.
     *
     * <p>The capacity setter is the proof that this trim supports the generic
     * charge-stop backend, so it always runs before the switch. Holding the
     * same lock used by single-field writes prevents a concurrent API or MQTT
     * update from leaving this request's capacity paired with another request's
     * enabled state. Both registers are read again at the end before success is
     * reported.
     */
    public boolean setChargeCapPercentAndEnabled(int percent, boolean enabled) {
        return setChargeCapPercentAndEnabledWithResult(percent, enabled).fullyApplied;
    }

    /**
     * Apply both generic charge-stop registers and return the final observed state.
     *
     * <p>A safe rollback is not possible when the old value is unreadable or the original setter
     * only partially works. Returning the in-lock readback makes the public API honest about that
     * hardware limitation.
     */
    public ChargeCapUpdateResult setChargeCapPercentAndEnabledWithResult(int percent, boolean enabled) {
        synchronized (chargeCapTransactionLock) {
            boolean capacityApplied = setChargeStopCapacityFallback(percent);
            boolean switchApplied = capacityApplied && setChargeStopSwitchFallback(enabled);
            int capacityReadBack = readChargeStopCapacity();
            int switchReadBack = readChargeStopSwitch();
            boolean confirmed = capacityApplied && switchApplied
                    && capacityReadBack == percent
                    && switchReadBack == (enabled ? 1 : 0);
            logger.info("setChargeCapPercentAndEnabled capacity=" + percent
                    + " enabled=" + enabled
                    + " capacityApplied=" + capacityApplied
                    + " switchApplied=" + switchApplied
                    + " finalCapacityReadBack=" + capacityReadBack
                    + " finalSwitchReadBack=" + switchReadBack
                    + " confirmed=" + confirmed);
            return new ChargeCapUpdateResult(confirmed, capacityReadBack, switchReadBack);
        }
    }

    /**
     * Set the PHEV driving SOC target through BYDAutoSettingDevice.setSOCTarget(percent).
     */
    public boolean setSocTargetPercent(int percent) {
        if (percent < SOC_TARGET_MIN || percent > SOC_TARGET_MAX) {
            logger.warn("setSocTargetPercent: percent must be "
                    + SOC_TARGET_MIN + ".." + SOC_TARGET_MAX + " (got " + percent + ")");
            return false;
        }
        return Boolean.TRUE.equals(trySetSocTarget(percent));
    }

    /**
     * PHEV SOC-target write: BYDAutoSettingDevice.setSOCTarget(percent),
     * clamped to [floor, 70] where floor = getSOCConfig()==2 ? 25 : 15 (matches
     * the OEM). A successful setter return is not enough: several BYD SDK
     * setters acknowledge a no-op, so require a matching readback before
     * reporting the value as applied.
     */
    private Boolean trySetSocTarget(int percent) {
        if (settingDevice == null) return null;
        int floor = socTargetFloor();
        int target = percent;
        if (target < floor) target = floor;
        if (target > SOC_TARGET_MAX) target = SOC_TARGET_MAX;
        // callMethod returns null both when the method is absent and on invoke
        // failure; SOC hold then reports failure without using charge-stop.
        Object result = BydDeviceHelper.callMethod(settingDevice, "setSOCTarget", target);
        if (result == null) return null;
        boolean accepted = (result instanceof Integer) && ((Integer) result).intValue() == 0;
        if (!accepted) return Boolean.FALSE;
        try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        int readBack = readSocTarget();
        boolean confirmed = readBack == target;
        logger.info("setSOCTarget(" + target + ") [requested=" + percent + " floor=" + floor
                + "] accepted=true readBack=" + readBack + " confirmed=" + confirmed);
        return Boolean.valueOf(confirmed);
    }

    private int readSocTarget() {
        try {
            Object v = BydDeviceHelper.callGetter(settingDevice, "getSOCTarget");
            if (v instanceof Number) {
                int value = ((Number) v).intValue();
                return (value >= SOC_TARGET_MIN && value <= SOC_TARGET_MAX) ? value : -1;
            }
        } catch (Exception e) {
            logger.debug("getSOCTarget readback failed: " + e.getMessage());
        }
        return -1;
    }

    /** Read the generic charge-stop capacity directly; never fall through to SOC-hold state. */
    private int readChargeStopCapacity() {
        try {
            Object value = BydDeviceHelper.callGetter(chargingDevice, "getChargeStopCapacityState");
            if (value instanceof Number) {
                int percent = ((Number) value).intValue();
                return (percent >= 50 && percent <= 100) ? percent : -1;
            }
        } catch (Exception e) {
            logger.debug("getChargeStopCapacityState failed: " + e.getMessage());
        }
        return -1;
    }

    /** Read the generic charge-stop master switch directly. */
    private int readChargeStopSwitch() {
        try {
            Object value = BydDeviceHelper.callGetter(chargingDevice, "getChargeStopSwitchState");
            if (value instanceof Number) {
                int state = ((Number) value).intValue();
                return (state == 0 || state == 1) ? state : -1;
            }
        } catch (Exception e) {
            logger.debug("getChargeStopSwitchState failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Generic charge-cap write: BYDAutoChargingDevice.setChargeStopCapacityState
     * (50..100%). Probes on every write via read-back; if the framework did not
     * honor it, flip supported=false so the UI hides. Subsequent calls
     * short-circuit if already known to no-op.
     */
    private boolean setChargeStopCapacityFallback(int percent) {
        if (chargingDevice == null) {
            logger.warn("setChargeStopCapacityState: chargingDevice null");
            return false;
        }
        if (percent < 50 || percent > 100) {
            logger.warn("setChargeStopCapacityState: percent must be 50..100 (got " + percent + ")");
            return false;
        }
        if (chargeCapProbed && !chargeCapSupported) {
            logger.debug("setChargeStopCapacityState: known unsupported on this trim");
            return false;
        }
        try {
            Method m;
            try {
                m = chargingDevice.getClass().getMethod("setChargeStopCapacityState", int.class);
            } catch (NoSuchMethodException nsme) {
                logger.warn("setChargeStopCapacityState not present on this firmware");
                chargeCapProbed = true; chargeCapSupported = false;
                return false;
            }
            Object result = m.invoke(chargingDevice, percent);
            boolean accepted = result instanceof Integer && ((Integer) result).intValue() == 0;
            if (!accepted) {
                logger.debug("setChargeStopCapacityState(" + percent + ") returned " + result);
                return false;
            }
            int readBack = -1;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                readBack = readChargeStopCapacity();
                if (readBack >= 50 && readBack <= 100) break;
            }
            // An unavailable getter is not proof that the setter is a no-op.
            // Keep the support state unknown so the next explicit capacity
            // write can retry instead of permanently hiding a working control.
            if (readBack < 0) {
                logger.info("setChargeStopCapacityState accepted but read-back remained unavailable");
                return false;
            }
            chargeCapProbed = true;
            chargeCapSupported = (readBack == percent);
            logger.info("setChargeStopCapacityState probe: wrote=" + percent
                    + " readBack=" + readBack + " supported=" + chargeCapSupported);
            if (chargeCapSupported) lastAppliedCapPercent = percent;
            if (chargeCapSupported) {
                chargeCapMinimumPercent = 50;
                chargeCapMaximumPercent = 100;
                chargeCapControlKind = "charge_stop";
            }
            return chargeCapSupported;
        } catch (Exception e) {
            logger.debug("setChargeStopCapacityState failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set the generic charge-stop master switch after its capacity backend has
     * been verified. This deliberately does not touch PHEV SOC-hold mode.
     */
    public boolean setChargeCapEnabled(boolean enabled) {
        synchronized (chargeCapTransactionLock) {
            if (!chargeCapProbed || !chargeCapSupported) {
                logger.warn("setChargeCapEnabled refused: charge-stop capacity is not verified");
                return false;
            }
            return setChargeStopSwitchFallback(enabled);
        }
    }

    // setSocSaveSwitch modes. 1 and 2 are two DISTINCT hold modes, not a boolean — reference app
    // the OEM uses 1 for its city preset and 2 for its long-distance preset.
    // The previous code hardcoded `enabled ? 1 : 0`, so mode 2 was unreachable.
    //
    // What each mode MEANS follows from the target the OEM pairs with it, and it is the opposite
    // of what "city/long-distance" suggests to an English reader:
    //   mode 1 ← setSOCTarget(FLOOR)          → let the pack DEPLETE to the floor (urban EV driving)
    //   mode 2 ← setSOCTarget(min(SOC, 50))   → HOLD the charge you have now (save it for later)
    // The AT_FLOOR / AT_CURRENT names encode the behaviour rather than the OEM's city /
    // long-distance labels, which read backwards in English. The wire values are unchanged, and
    // automations/keymaps store option WORDS, so no stored config depends on these names.
    public static final int SOC_HOLD_MODE_OFF = 0;
    public static final int SOC_HOLD_MODE_AT_FLOOR = 1;
    public static final int SOC_HOLD_MODE_AT_CURRENT = 2;

    /** Ceiling the OEM applies to a hold-at-current target. Not the API max (70) — see applySocHoldAtCurrent. */
    private static final int SOC_HOLD_AT_CURRENT_MAX = 50;

    /**
     * Set the SOC-hold (battery-hold) mode. The SOC target says WHERE to hold,
     * while this switch says whether/how to hold it.
     *
     * <p>Modes: 0 = off, 1 = city-style hold, 2 = long-distance hold. It
     * never falls back to the legacy charge-stop switch: that is a charging
     * cutoff with different semantics, not a hold mode.
     *
     * <p><b>UNVERIFIED on device:</b> the behavioural difference between mode 1 and mode 2. Both
     * are proven to be what the OEM writes for its two presets, but no SDK constants document
     * them and OverDrive's daemon UID may not be permitted to write them at all.
     */
    public boolean setSocHoldMode(int mode) {
        if (mode < SOC_HOLD_MODE_OFF || mode > SOC_HOLD_MODE_AT_CURRENT) return false;
        Boolean primary = trySetSocSaveSwitch(mode);
        return primary != null && primary.booleanValue();
    }

    /** Enable battery hold at the already configured SOC target without rewriting it. */
    public boolean enableSocHoldAtSavedTarget() {
        int target = readSocTarget();
        if (target < SOC_TARGET_MIN || target > SOC_TARGET_MAX) {
            logger.warn("SOC hold AT_TARGET: saved target unavailable");
            return false;
        }
        return setSocHoldMode(SOC_HOLD_MODE_AT_CURRENT);
    }

    /**
     * The SOC-target floor this trim enforces: 25 on a DM2.5 platform
     * ({@code getSOCConfig()==2}), else 15. Named after the official SDK constants
     * {@code DM25_SOC_TARGET_MIN} / {@code DM20_SOC_TARGET_MIN}, which is what that magic
     * {@code getSOCConfig()==2} test actually means.
     */
    private int socTargetFloor() {
        try {
            Object cfg = BydDeviceHelper.callGetter(settingDevice, "getSOCConfig");
            if (cfg instanceof Number && ((Number) cfg).intValue() == 2) return SOC_TARGET_FLOOR_ALT;
        } catch (Exception e) {
            logger.debug("getSOCConfig failed, assuming DM2.0 floor: " + e.getMessage());
        }
        return SOC_TARGET_FLOOR_DEFAULT;
    }

    /**
     * "Deplete to floor" preset (the OEM's city mode): target = this trim's floor, hold mode 1.
     * Lets the pack run down to the reserve — what you want for urban EV driving.
     */
    public boolean applySocHoldAtFloor() {
        return applySocHold(socTargetFloor(), SOC_HOLD_MODE_AT_FLOOR);
    }

    /**
     * Apply one SOC-hold preset: set the hold switch, then the target level.
     *
     * <p>Switch first, and the target only if the switch was accepted. The
     * SOC-hold register is separate from the generic charge-stop limit.
     *
     * <p>Uses {@link #trySetSocTarget} / {@link #trySetSocSaveSwitch} directly.
     * A hold exists only on the SOC-target path, so when that path is absent
     * this reports failure rather than actuating the unrelated charge-stop
     * limit.
     */
    private boolean applySocHold(int target, int mode) {
        String label = mode == SOC_HOLD_MODE_AT_CURRENT ? "AT_CURRENT" : "AT_FLOOR";
        // Both legs must be the genuine SOC-hold pair. Probe the real switch
        // directly and bail if the firmware lacks it.
        Boolean switchOk = trySetSocSaveSwitch(mode);
        if (switchOk == null) {
            logger.warn("SOC hold " + label + ": setSocSaveSwitch absent on this firmware — no"
                    + " genuine SOC-hold path, so nothing was written");
            return false;
        }
        if (!switchOk.booleanValue()) {
            logger.warn("SOC hold " + label + ": setSocSaveSwitch(" + mode + ") refused — leaving the"
                    + " SOC target untouched");
            return false;
        }
        Boolean targetOk = trySetSocTarget(target);
        if (targetOk == null) {
            logger.warn("SOC hold " + label + ": setSOCTarget absent on this firmware — the hold"
                    + " switch was set but the level is whatever the car already had");
            return false;
        }
        logger.info("SOC hold " + label + ": setSocSaveSwitch(" + mode + ")=true setSOCTarget("
                + target + ")=" + targetOk);
        return targetOk.booleanValue();
    }

    /**
     * "Hold current charge" preset (the OEM's long-distance mode, the requested Highway
     * behaviour): target = min(current SOC, 50), hold mode 2 — preserve the charge you have
     * for later instead of spending it now.
     *
     * <p>The 50 ceiling is the OEM's, NOT the API max (70): holding above 50 on a PHEV means
     * the engine must generate to reach the target, which is the opposite of "keep what I
     * have". Clamping DOWN to the floor is handled by {@link #trySetSocTarget}. Note the OEM
     * itself has a bug here (an over-max target falls through to the MINIMUM) which is
     * deliberately not reproduced.
     *
     * <p>When the current SOC is BELOW the trim's floor, {@code trySetSocTarget} clamps up to
     * the floor — the HAL's own minimum, so there is nothing lower to ask for. Logged plainly,
     * because the hold then sits slightly above the current charge.
     *
     * @return false when the current SOC can't be read — better to do nothing than to hold at
     *         a guessed level.
     */
    public boolean applySocHoldAtCurrent() {
        int soc = readSocPercentForHold();
        if (soc < 0) {
            logger.warn("SOC hold AT_CURRENT: current SOC unavailable — refusing to guess a target");
            return false;
        }
        int target = Math.min(soc, SOC_HOLD_AT_CURRENT_MAX);
        int floor = socTargetFloor();
        if (target < floor) {
            logger.info("SOC hold AT_CURRENT: soc=" + soc + "% is below this trim's floor (" + floor
                    + "%), so the hold applies at the floor — the lowest the HAL accepts");
        }
        return applySocHold(target, SOC_HOLD_MODE_AT_CURRENT);
    }

    /**
     * Turn any SOC hold off, leaving the target untouched.
     */
    public boolean clearSocHold() {
        boolean ok = setSocHoldMode(SOC_HOLD_MODE_OFF);
        logger.info("SOC hold OFF: setSocSaveSwitch(0)=" + ok);
        return ok;
    }

    /**
     * Current SOC as a whole percent for the hold presets, or -1 if unknown. Prefers the live
     * snapshot (already resolved across several HAL sources) and falls back to the statistic
     * getter the OEM uses.
     */
    private int readSocPercentForHold() {
        BydVehicleData d = snapshot.get();
        if (d != null && !Double.isNaN(d.socPercent) && d.socPercent > 0 && d.socPercent <= 100) {
            return (int) Math.floor(d.socPercent);
        }
        try {
            Object v = BydDeviceHelper.callGetter(statisticDevice, "getElecPercentageValue");
            if (v instanceof Number) {
                double soc = ((Number) v).doubleValue();
                if (soc > 0 && soc <= 100) return (int) Math.floor(soc);
            }
        } catch (Exception e) {
            logger.debug("getElecPercentageValue failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * SOC-hold enable: BYDAutoChargingDevice.setSocSaveSwitch(mode) — 0=off,
     * 1=city hold, 2=long-distance hold. Returns null when the method is absent.
     */
    private Boolean trySetSocSaveSwitch(int mode) {
        // Direct reflection (via the shared charging-setter judge), NOT callMethod:
        // callMethod collapses a VOID return into null, which applySocHold/
        // setSocHoldMode would read as "method absent" after the switch had
        // physically engaged. This distinguishes absent from void-success.
        Boolean accepted = invokeChargingSetter("setSocSaveSwitch", mode);
        if (accepted == null || !accepted.booleanValue()) return accepted;
        try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        try {
            Object value = BydDeviceHelper.callGetter(chargingDevice, "getSocSaveSwitch");
            if (value instanceof Number) {
                int readBack = ((Number) value).intValue();
                boolean confirmed = readBack == mode;
                logger.info("setSocSaveSwitch(" + mode + ") readBack=" + readBack
                        + " confirmed=" + confirmed);
                return Boolean.valueOf(confirmed);
            }
        } catch (Exception e) {
            logger.debug("getSocSaveSwitch readback failed: " + e.getMessage());
        }
        return Boolean.FALSE;
    }

    /** Generic charge-stop master switch with direct readback confirmation. */
    private boolean setChargeStopSwitchFallback(boolean enabled) {
        if (chargingDevice == null) {
            logger.warn("setChargeStopSwitchState: chargingDevice null");
            return false;
        }
        if (chargeCapProbed && !chargeCapSupported) {
            return false;
        }
        try {
            Method m;
            try {
                m = chargingDevice.getClass().getMethod("setChargeStopSwitchState", int.class);
            } catch (NoSuchMethodException nsme) {
                logger.warn("setChargeStopSwitchState not present on this firmware");
                return false;
            }
            int v = enabled ? 1 : 0;
            Object result = m.invoke(chargingDevice, v);
            boolean accepted = result instanceof Integer && ((Integer) result).intValue() == 0;
            if (!accepted) {
                logger.debug("setChargeStopSwitchState(" + v + ") returned " + result);
                return false;
            }
            try { Thread.sleep(150L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            int readBack = readChargeStopSwitch();
            boolean confirmed = readBack == v;
            logger.info("setChargeStopSwitchState(" + v + ") readBack=" + readBack
                    + " confirmed=" + confirmed);
            return confirmed;
        } catch (Exception e) {
            logger.debug("setChargeStopSwitchState failed: " + e.getMessage());
            return false;
        }
    }

    // --- Ambient Lighting ---
    //
    // Zoning per the OEM SDK: the Setting-device methods setIALArea(area),
    // setIALBrightness(area, level, 0) and setIALColor(area, value, 0) take an AREA code —
    // FRONT=1, REAR=2, BOTH/ALL=3 — where the "all" one-shot (area 3) falls back to writing
    // FRONT then REAR if the combined call is rejected. Brightness is a 0..5 LEVEL at the SDK
    // (we accept a 0..100 percent from the UI and convert). CRITICAL: the SDK methods are
    // 3-int with a fixed trailing 0 — reflecting a 2-int form NoSuchMethods and silently
    // no-ops (the old ambient-colour bug). The interior on/off main switch is GLOBAL (no
    // per-zone switch exists on this platform).

    /** Map a zone token to a BYD IAL area code: front=1, rear=2, both/all=3 (default both). */
    private static int ambientZoneToArea(String zone) {
        if ("front".equalsIgnoreCase(zone)) return 1;
        if ("rear".equalsIgnoreCase(zone)) return 2;
        return 3; // "both" / null / unknown → all
    }

    /**
     * GLOBAL interior-ambient main switch (no per-zone switch exists on this platform).
     *
     * <p>THREE TIERS, tried in order until one lands — matching the reference app's
     * {@code setAmbientLightEnabled} exactly. A single-tier write was the bug: when the
     * Light-device main switch is refused (it is signature-gated on some trims), "ambient
     * light off" did nothing at all and reported failure with no second attempt.
     *
     * <ol>
     *   <li>Light device {@code ATMOSPHERE_MAIN_SWITCH_SET} — on=1 / off=0.</li>
     *   <li>Bodywork {@code ATMOSPHERE_LIGHT_SWITCH_EXECUTE} — on=1 / <b>off=2</b>. NOTE the
     *       different off value: this is an EXECUTE-style command feature where 2 means off,
     *       not 0 (0 would be "no command"). Confirmed against the reference app.</li>
     *   <li>The carsettings provider's {@code atmosphere_lamp} flag — on=1 / off=0, the same
     *       key BYD's own settings UI writes.</li>
     * </ol>
     *
     * <p>Tier 3 uses the CONFIRMED-only write: an optimistic "true" from a void provider
     * call would let this method claim success when nothing moved.
     */
    public boolean setAmbientLightEnabled(boolean on) {
        boolean ok = false;
        try {
            ok = BydDeviceHelper.sendSetCommand(
                    lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_MAIN_SWITCH_SET, on ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setAmbientLightEnabled tier1 (light main switch) failed: " + e.getMessage());
        }
        if (!ok) {
            try {
                // on=1 / off=2 — an execute-style feature, NOT the 1/0 of tier 1.
                ok = BydDeviceHelper.sendSetCommand(
                        bodyworkDevice, BydFeatureIds.BODY_ATMOSPHERE_LIGHT_SWITCH, on ? 1 : 2);
            } catch (Exception e) {
                logger.debug("setAmbientLightEnabled tier2 (body execute) failed: " + e.getMessage());
            }
        }
        if (!ok) {
            try {
                ok = BydCarSettings.getInstance()
                        .writeIntConfirmed(BydCarSettings.KEY_ATMOSPHERE_LAMP, on ? 1 : 0);
            } catch (Exception e) {
                logger.debug("setAmbientLightEnabled tier3 (atmosphere_lamp) failed: " + e.getMessage());
            }
        }
        if (!ok) {
            logger.warn("setAmbientLightEnabled(" + on + ") failed across all three tiers");
        }
        return ok;
    }

    /**
     * Interior-ambient main-switch READBACK: 1 = on, 0 = off, {@link BydVehicleData#UNAVAILABLE}
     * when neither source answers. Mirrors the reference app's {@code isAmbientLightEnabled}
     * (Light-device status feature, then the {@code atmosphere_lamp} provider flag).
     *
     * <p>Only an exact 0/1 counts. {@code callGetSingle} returns -1 both for "absent" and for a
     * HAL failure, and the provider read needs a sentinel default for the same reason — so any
     * other value reads UNAVAILABLE rather than being coerced to "off". Without that, a trim
     * that cannot report ambient state would publish a permanent, wrong "ambient off".
     */
    public int getAmbientLightEnabled() {
        try {
            int direct = BydDeviceHelper.callGetSingle(
                    lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_MAIN_SWITCH_STATUS);
            if (direct == 0 || direct == 1) return direct;
            int flag = BydCarSettings.getInstance()
                    .readInt(BydCarSettings.KEY_ATMOSPHERE_LAMP, Integer.MIN_VALUE);
            if (flag == 0 || flag == 1) return flag;
        } catch (Exception e) {
            logger.debug("getAmbientLightEnabled failed: " + e.getMessage());
        }
        return BydVehicleData.UNAVAILABLE;
    }

    /**
     * Steering-wheel heater READBACK, raw setting-HAL domain: 2 = on, 1 = off,
     * {@link BydVehicleData#UNAVAILABLE} when the trim does not answer. Same domain as
     * {@link #setSteeringWheelHeating}.
     *
     * <p>Only an exact 1/2 counts — 0 (unpopulated) and 65535 (the not-available rail) read
     * UNAVAILABLE rather than being coerced to "off", so a trim without a heated wheel never
     * publishes a confident wrong "off".
     */
    public int getSteeringWheelHeatingState() {
        try {
            Object raw = BydDeviceHelper.callGetter(settingDevice, "getSteeringWheelHeatingState");
            if (raw instanceof Number) {
                int v = ((Number) raw).intValue();
                if (v == 1 || v == 2) return v;
            }
        } catch (Exception e) {
            logger.debug("getSteeringWheelHeatingState failed: " + e.getMessage());
        }
        return BydVehicleData.UNAVAILABLE;
    }

    /**
     * Zoned ambient on/off. "both" uses the real GLOBAL main switch above (all three tiers).
     *
     * <p>A single zone (front/rear) has NO dedicated switch on this platform, so "off" zeroes
     * that zone's brightness and "on" restores the level it had BEFORE the off — read live if
     * the trim supports it, else from the in-memory pre-off level, else full. Restoring to full
     * unconditionally (the earlier behaviour) silently reset anyone running a dimmed cabin.
     *
     * <p>zone: front/rear/both.
     */
    public boolean setAmbientLightEnabledZoned(String zone, boolean on) {
        int area = ambientZoneToArea(zone);
        if (area == 3) return setAmbientLightEnabled(on);
        if (on) {
            // Restore the level this zone had before it was dimmed out, so off→on does not
            // silently reset a user who runs the cabin at level 2 to full brightness. Falls
            // back to full only when there is no remembered/readable level (first-ever use on
            // a trim with no readback), which is the old behaviour.
            int restore = ambientZoneRestoreLevel(area);
            return applyIalBrightness(area, restore);
        }
        // Remember the pre-off level so the matching "on" can restore it. Read it BEFORE
        // zeroing; a failed read leaves the previous memory intact rather than recording 0
        // (which would make the next "on" a no-op). This read is what captures a level the
        // user set in the CAR's own UI (which we never commanded, so applyIalBrightness had no
        // chance to record it); levels we DID command are recorded at write time instead, which
        // is the only tier that works on a trim with no readback.
        int current = readIalBrightness(area);
        if (current > 0) rememberAmbientZoneLevel(area, current);
        return applyIalBrightness(area, 0);
    }

    /**
     * Per-zone "level before we dimmed it out", so a zoned ambient off→on round-trip preserves
     * the user's brightness. Keyed by IAL area (1=front, 2=rear). Absent → no memory yet.
     *
     * <p>Populated from BOTH directions: every non-zero level we command (in
     * {@link #applyIalBrightness}, the only tier that works with no readback) and the pre-off
     * live read (which catches a level set in the car's own UI).
     *
     * <p>Deliberately in-memory only: a stale level persisted across a reboot could fight a
     * change the user made in the OEM UI meanwhile, and the live readback is preferred whenever
     * the trim supports it.
     */
    private final java.util.Map<Integer, Integer> ambientZoneLevelMemory =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void rememberAmbientZoneLevel(int area, int level) {
        if (level >= 1 && level <= 5) ambientZoneLevelMemory.put(area, level);
    }

    /**
     * The level to restore for a zoned "ambient on": the live SDK level if this trim reports
     * one and it is non-zero, else the remembered pre-off level, else full (5).
     *
     * <p>A live read of 0 is ignored on purpose — the zone is currently dimmed out, which is
     * exactly the state we are leaving, so 0 would make "on" a no-op.
     */
    private int ambientZoneRestoreLevel(int area) {
        int live = readIalBrightness(area);
        if (live >= 1 && live <= 5) return live;
        Integer remembered = ambientZoneLevelMemory.get(area);
        if (remembered != null && remembered >= 1 && remembered <= 5) return remembered;
        return 5;
    }

    /**
     * Read one zone's IAL brightness LEVEL (0..5) via {@code getIALBrightness(area)}, or
     * {@link BydVehicleData#UNAVAILABLE} when the getter is absent or answers out of band.
     * The SDK getter is the 1-arg form (see the reference app); a negative is a HAL failure
     * code, not a level.
     */
    private int readIalBrightness(int area) {
        Object v = BydDeviceHelper.callGetter(settingDevice, "getIALBrightness", area);
        if (!(v instanceof Number)) return BydVehicleData.UNAVAILABLE;
        int level = ((Number) v).intValue();
        return (level >= 0 && level <= 5) ? level : BydVehicleData.UNAVAILABLE;
    }

    /** Whole-cabin ambient brightness (0..100) via the Light-device custom-brightness feature
     *  id — the pre-existing path, kept unchanged for existing callers and as the "both"
     *  fallback below. */
    public boolean setAmbientBrightness(int level) {
        try {
            if (level < 0 || level > 100) return false;
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS_SET, level);
        } catch (Exception e) {
            logger.debug("setAmbientBrightness failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Zoned ambient brightness. {@code percent} 0..100 (converted to the SDK's 0..5 level).
     * zone: front/rear/both. For "both" (area 3), if the IAL path is rejected we
     * fall back to the whole-cabin custom-brightness feature id.
     */
    public boolean setAmbientBrightnessZoned(String zone, int percent) {
        if (percent < 0 || percent > 100) return false;
        int area = ambientZoneToArea(zone);
        int level = Math.round(percent / 100f * 5f); // percent → 0..5 SDK level
        boolean ok = applyIalBrightness(area, level);
        if (!ok && area == 3) {
            ok = setAmbientBrightness(percent);
        }
        return ok;
    }

    /**
     * IAL brightness write: select the area, then {@code setIALBrightness(area, level, 0)}
     * (level 0..5). area 1=front / 2=rear / 3=all; area 3 falls back to front-then-rear if
     * the combined write is rejected. Returns true when the SDK reports success (result == 0).
     */
    private boolean applyIalBrightness(int area, int level) {
        // Record every NON-ZERO level we command, whatever the caller. This is what makes the
        // zoned off→on restore work on a trim with NO readback: readIalBrightness returns
        // UNAVAILABLE there, so remembering only at off-time never populated the map and the
        // restore always fell through to full brightness — defeating the feature on exactly the
        // trims the memory exists for. Recording at write time also captures the level the user
        // actually chose via setAmbientBrightnessZoned. Level 0 is skipped: that IS the off
        // state, and storing it would make the next "on" a no-op.
        if (level > 0) {
            if (area == 3) { rememberAmbientZoneLevel(1, level); rememberAmbientZoneLevel(2, level); }
            else rememberAmbientZoneLevel(area, level);
        }
        boolean areaSelected = prepareAmbientArea(area);
        Object r = BydDeviceHelper.callMethod(settingDevice, "setIALBrightness", area, level, 0);
        // STRICT result == 0, deliberately. A void setIALBrightness would return null here on a
        // successful invoke, so this can under-report success — but the callers TREAT false as
        // "try the next path" (area 3 → front+rear, then the whole-cabin custom-brightness
        // feature id), and those extra attempts are what make ambient control work on this
        // fleet. Loosening this to accept-on-no-throw would stop the fallbacks from running.
        boolean ok = r instanceof Integer && ((Integer) r).intValue() == 0;
        if (!ok && area == 3) {
            ok = applyIalBrightness(1, level);
            ok = applyIalBrightness(2, level) || ok; // run both; OR so either landing = success
        }
        // Per-zone legacy fallback: the IAL setter is absent/refused on some trims, and area 3
        // has already been decomposed into front+rear above, so only areas 1/2 land here.
        if (!ok && area != 3) {
            ok = applyLegacyZoneBrightness(area, ialLevelToPercent(level), areaSelected);
        }
        return ok;
    }

    /** SDK level (0..5) → percent, the inverse of the percent→level conversion above. */
    private static int ialLevelToPercent(int level) {
        int clamped = Math.max(0, Math.min(5, level));
        return Math.round(clamped / 5f * 100f);
    }

    /** Percent (0..100) → the 0..255 byte scale some legacy brightness features expect. */
    private static int ambientPercentToByte(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        return Math.round(clamped / 100f * 255f);
    }

    /**
     * Select an ambient zone before a write: {@code setIALArea(area)}, falling back to the
     * legacy Light-device {@code ATMOSPHERE_ADJUST_AREA_SET} (written as {area} then {0}, per
     * the reference app) on trims that lack the SDK method. Returns whether the zone was
     * actually selected — the legacy whole-cabin brightness features below apply to the
     * SELECTED area, so writing them without a confirmed selection would hit the wrong zone.
     */
    private boolean prepareAmbientArea(int area) {
        Object r = BydDeviceHelper.callMethod(settingDevice, "setIALArea", area);
        if (r instanceof Integer && ((Integer) r).intValue() == 0) return true;
        // Legacy selection is defined for the PHYSICAL zones only. The reference app never
        // writes the synthetic "all" code (3) to this feature — it only ever selects FRONT or
        // REAR — so sending 3 here would push an out-of-domain value the HAL has no meaning
        // for. Area 3 simply reports "not selected"; its callers already decompose into
        // front+rear, which each select their own zone properly.
        if (area != 1 && area != 2) return false;
        // The reference app pushes {area} then a trailing {0} to this feature; keep both writes
        // so a trim that needs the pair behaves identically.
        //
        // But ONLY the {area} write decides the return value. The reference ORs both results,
        // which means a HAL that refuses the zone code yet accepts 0 reports "selected" while
        // the selection actually points at area 0 (whole cabin). Our callers use that boolean to
        // decide whether the selected-area-relative brightness features are safe to write, so
        // trusting a 0-only success is precisely how "off front" would dim the rear — the thing
        // the areaSelected guard exists to prevent. Fail closed instead.
        boolean zoneSelected = BydDeviceHelper.sendSetCommand(
                lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_ADJUST_AREA_SET, area);
        BydDeviceHelper.sendSetCommand(
                lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_ADJUST_AREA_SET, 0);
        return zoneSelected;
    }

    /**
     * Legacy per-zone brightness fallback for trims whose Setting-device IAL setter is missing
     * or refuses the write — the chain the reference app walks, in its order:
     *
     * <ol>
     *   <li>{@code ATMOSPHERE_CUSTOM_BRIGHTNESS_SET} (percent) — applies to the SELECTED area,
     *       so only attempted when the area selection was confirmed.</li>
     *   <li>{@code ATMOSPHERE_VEHICLE_BRIGHTNESS_SET} (percent, then the 0..255 byte scale) —
     *       same selected-area caveat.</li>
     *   <li>The zone's own dedicated feature id (percent), then its {@code _ALT} byte-scaled
     *       twin. These carry the zone in the feature id itself, so they need no selection.</li>
     * </ol>
     *
     * @param areaSelected whether {@link #prepareAmbientArea} confirmed the zone selection;
     *                     when false the selected-area-relative tiers are SKIPPED rather than
     *                     gambled on, so an "off front" cannot silently dim the rear instead.
     */
    private boolean applyLegacyZoneBrightness(int area, int percent, boolean areaSelected) {
        int zoneId = (area == 2)
                ? BydFeatureIds.LIGHT_AMBIENT_REAR_BRIGHTNESS
                : BydFeatureIds.LIGHT_AMBIENT_FRONT_BRIGHTNESS;
        int zoneIdAlt = (area == 2)
                ? BydFeatureIds.LIGHT_AMBIENT_REAR_BRIGHTNESS_ALT
                : BydFeatureIds.LIGHT_AMBIENT_FRONT_BRIGHTNESS_ALT;
        int asByte = ambientPercentToByte(percent);
        if (areaSelected) {
            if (BydDeviceHelper.sendSetCommand(
                    lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS_SET, percent)) return true;
            if (BydDeviceHelper.sendSetCommand(
                    lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_VEHICLE_BRIGHTNESS_SET, percent)) return true;
            if (BydDeviceHelper.sendSetCommand(
                    lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_VEHICLE_BRIGHTNESS_SET, asByte)) return true;
        }
        if (BydDeviceHelper.sendSetCommand(lightDevice, zoneId, percent)) return true;
        return BydDeviceHelper.sendSetCommand(lightDevice, zoneIdAlt, asByte);
    }

    public boolean setAmbientColor(int colorValue) {
        try {
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_COLOR_SET, colorValue);
        } catch (Exception e) {
            logger.debug("setAmbientColor failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Whole-cabin ambient colour by 1-based palette index (1-31). Delegates to the zoned path
     * with "both", so existing callers (vehicle control, MQTT) now cover BOTH zones rather than
     * only the front (the old area-1-only behaviour). See {@link #setAmbientLightZoned}.
     */
    public boolean setAmbientLight(int colour) {
        return setAmbientLightZoned("both", colour);
    }

    /**
     * Zoned ambient colour by 1-based palette index (1-31), via the Setting device's
     * {@code setIALColor(area, colour, 0)} (the OEM 3-arg form; a 2-arg form is
     * tried as a fallback for trims that expose it — the old shipping path). zone:
     * front/rear/both; "both" (area 3) falls back to front-then-rear. Returns true when the SDK
     * reports success (result == 0).
     */
    public boolean setAmbientLightZoned(String zone, int colour) {
        if (colour < 1 || colour > 31) return false;
        return applyIalColor(ambientZoneToArea(zone), colour);
    }

    private boolean applyIalColor(int area, int colour) {
        // Area select with the same SDK-then-legacy fallback the brightness path uses, so a trim
        // without setIALArea still lands the per-zone colour on the intended zone.
        boolean areaSelected = prepareAmbientArea(area);
        // Walk the arities the SDK is known to expose, widest first: 3-arg (area, colour, 0),
        // then 2-arg (area, colour), then the 1-arg (colour) form some trims ship — the last of
        // these was the only working path on those trims, so it stays in the chain. A null means
        // "absent, or void" and simply advances to the next arity; the strict result == 0 keeps
        // the area-3 → front+rear fallback below reachable, same rationale as
        // applyIalBrightness.
        Object r = BydDeviceHelper.callMethod(settingDevice, "setIALColor", area, colour, 0);
        if (r == null) r = BydDeviceHelper.callMethod(settingDevice, "setIALColor", area, colour);
        if (r == null) r = BydDeviceHelper.callMethod(settingDevice, "setIALColor", colour);
        boolean ok = r instanceof Integer && ((Integer) r).intValue() == 0;
        // One retry when the zone selection did NOT take: some trims only accept the colour
        // write on an already-selected area, and the selection can land on the second attempt.
        // Mirrors the reference app's post-selection retry.
        if (!ok && !areaSelected) {
            Object retry = BydDeviceHelper.callMethod(settingDevice, "setIALColor", area, colour, 0);
            ok = retry instanceof Integer && ((Integer) retry).intValue() == 0;
        }
        if (!ok && area == 3) {
            ok = applyIalColor(1, colour);
            ok = applyIalColor(2, colour) || ok; // run both; OR so either landing = success
        }
        // NOTE: deliberately NO fallback to LIGHT_AMBIENT_{FRONT,REAR}_COLOR here. Those
        // features take a 24-bit RGB value, not this 1-31 palette index — writing the index
        // there would command a near-black colour instead of the chosen preset. They are the
        // fallback for a true RGB setter, which this method is not.
        return ok;
    }

    // --- Seats ---

    public boolean setSeatHeating(int position, int level) {
        try {
            if (position < 1 || position > 4) return false;
            if (level < 0 || level > 3) return false;
            // SDK method: settingDevice.setSeatHeatingState(position, normalizedLevel)
            // Level normalization: coerceIn(level, 0, 2) + 1 → 0→1(off), 1→2(low), 2→3(high)
            int normalizedLevel = Math.min(level, 2) + 1;
            Object result = BydDeviceHelper.callMethod(settingDevice, "setSeatHeatingState", position, normalizedLevel);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setSeatHeating failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setSeatVentilation(int position, int level) {
        try {
            if (position < 1 || position > 4) return false;
            if (level < 0 || level > 3) return false;
            // Level normalization: coerceIn(level, 0, 2) + 1 → 0→1(off), 1→2(low), 2→3(high).
            // Matches the OEM firmware's seat-level normalization.
            int normalizedLevel = Math.min(level, 2) + 1;

            // Capability probe via BYDAutoSettingDevice.hasFeature() — ADVISORY ONLY. A trim
            // without ventilated seats (e.g. Atto 3 base) reports NOT_HAS_THE_FEATURE, which is
            // worth surfacing, but the probe cannot distinguish "no such hardware" from "the
            // method is absent / threw / answered something unexpected", and it may not answer
            // while the car is asleep. It therefore must NOT veto the write: a field log showed
            // 353 seat-vent commands reported as failures on a car that HAS ventilated seats,
            // because a single non-1 probe result was cached for the whole daemon lifetime.
            // Re-probed until it gives a definitive yes so one bad reading isn't permanent.
            if (!seatVentFeatureProbed) {
                int probe = probeFeatureState(settingDevice, "SEAT_VENTILATING");
                // Latch only a definitive answer; INDETERMINATE leaves it open to re-probe.
                if (probe != FEATURE_INDETERMINATE) {
                    seatVentFeatureProbed = true;
                    seatVentFeatureSupported = (probe == FEATURE_SUPPORTED);
                }
                if (probe != FEATURE_SUPPORTED) {
                    logger.warn("Seat ventilation: hasFeature(\"SEAT_VENTILATING\") → "
                        + describeFeatureState(probe) + " (advisory only; the write still proceeds "
                        + "and the HAL's own return decides). UI may grey out the control only on "
                        + "UNSUPPORTED.");
                }
            }

            // Use the canonical SDK method directly. This matches the OEM
            // firmware's seat-ventilation setter, and the BYD stub SDK at
            // android/hardware/bydauto/setting/BYDAutoSettingDevice.java only
            // defines this name. The previous "fallback chain" of
            // setSeatBlowingState / setSeatCoolingState / etc. was guesswork
            // — none of those exist in the OEM firmware or the
            // stub SDK. Removed.
            Method m;
            try {
                m = settingDevice.getClass().getMethod("setSeatVentilatingState", int.class, int.class);
            } catch (NoSuchMethodException nsme) {
                logger.warn("Seat ventilation: setSeatVentilatingState not present on this firmware "
                    + "(framework-side gap, not hardware) — cannot control ventilation.");
                return false;
            }
            Object result = m.invoke(settingDevice, position, normalizedLevel);
            boolean accepted = result instanceof Integer && ((Integer) result).intValue() == 0;
            if (!accepted) {
                logger.warn("setSeatVentilatingState(" + position + ", " + normalizedLevel
                    + ") returned " + result + " (expected 0)");
                return false;
            }
            // This HAL returns 0 even when an asleep vehicle ignores the write. Give the
            // setting rail a short window to settle and require matching readback whenever
            // that getter is available; a confirmed mismatch lets the router fall through
            // to BYD Cloud instead of reporting a false local success.
            int requestedLevel = normalizedLevel - 1;
            int actualLevel = BydVehicleData.UNAVAILABLE;
            for (int attempt = 0; attempt < 3; attempt++) {
                actualLevel = readSeatClimateNow(false, position);
                if (actualLevel == requestedLevel) return true;
                if (attempt < 2) SystemClock.sleep(75L);
            }
            if (actualLevel == BydVehicleData.UNAVAILABLE) {
                logger.info("Seat ventilation readback unavailable after accepted write; "
                    + "trusting HAL result for position=" + position);
                return true;
            }
            logger.warn("Seat ventilation write was accepted but did not take: position="
                + position + " requested=" + requestedLevel + " actual=" + actualLevel);
            return false;
        } catch (Exception e) {
            logger.debug("setSeatVentilation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Recall a stored driver-side seat memory position (slot 1 or 2) — moves the
     * seat to whatever was previously saved into that slot.
     *
     * <p>Uses the "WAKE" feature id ({@code SET_LF_MEMORY_LOCATION_WAKE_SET}): on
     * this platform the memory subsystem has two distinct ids per seat — a plain
     * "SET" id that <em>stores</em> the current physical position into a slot, and
     * a "WAKE" id that <em>recalls</em> (activates) a stored slot. This is the
     * recall half; {@link #setSeatMemorySave} is the store half. SDK feature lives
     * on settingDevice — Adas.* IDs do not accept this set.
     */
    public boolean setSeatMemoryPosition(int position) {
        try {
            if (position < 1 || position > 2) return false;
            if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_POSITIONING)) return false;
            int result = BydDeviceHelper.callSetSingle(settingDevice, BydFeatureIds.SETTING_LF_MEMORY_LOCATION_WAKE_SET, position);
            // Success = HAL accepted. callSetSingle returns the raw SDK code on
            // success (SETTING_COMMAND_SUCCESS, which is NOT guaranteed 0 on this
            // platform — sibling families prove non-zero SUCCESS, e.g. CHARGING=2)
            // and -1 on sigperm/exception; documented HAL failure is -2147482648.
            // So test >= 0 (the proven convention used by sendSetCommand), NOT == 0.
            return result >= 0;
        } catch (Exception e) {
            logger.debug("setSeatMemoryPosition failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Save (store) the driver seat's <em>current</em> physical position into a
     * memory slot (1 or 2) — the mirror of {@link #setSeatMemoryPosition}. This
     * writes to the same slots as the physical door memory buttons, so a save here
     * overwrites what those buttons would recall.
     *
     * <p>Uses the "SET" feature id ({@code SET_LF_MEMORY_LOCATION_SET}), not the
     * "WAKE" recall id. Same signature-permission caveat as every other
     * settingDevice write (CPD, seat heat/vent): the SDK method resolves and rides
     * the identical {@link BydDeviceHelper#callSetSingle} path, but the HAL may
     * reject the write from our UID on a firmware that server-side-gates it
     * (HAL failure = -1 / -2147482648). Returns true when the HAL accepts.
     */
    public boolean setSeatMemorySave(int position) {
        try {
            if (position < 1 || position > 2) return false;
            int result = BydDeviceHelper.callSetSingle(settingDevice, BydFeatureIds.SETTING_LF_MEMORY_LOCATION_SET, position);
            // See setSeatMemoryPosition: >= 0 is the correct success test (SDK
            // SUCCESS code is not guaranteed 0; -1/-2147482648 are the failures).
            return result >= 0;
        } catch (Exception e) {
            logger.debug("setSeatMemorySave failed: " + e.getMessage());
        }
        return false;
    }

    public boolean setChildPresenceDetection(int value) {
        try {
            if (value < 1 || value > 3) return false;
            // 1 is for on, 2 is for off and 3 is for delay
            // Route through sendSetCommand (success = code >= 0) on the setting device — the
            // same convention the sibling setItacState uses on this device and the
            // feature-id ADAS setters use. The old callSetSingle(...) == 0 used the fragile
            // 3-int set() overload with an exact-zero test, so a benign non-zero-positive HAL
            // return was misread as failure.
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_CPD_SWITCH_STATUS_SET, value);
        } catch (Exception e) {
            logger.debug("setChildPresenceDetection failed: " + e.getMessage());
        }
        return false;
    }

    /** Cached BYDAutoSettingDevice.hasFeature("SEAT_VENTILATING") result; probed once. */
    private volatile boolean seatVentFeatureProbed = false;
    private volatile boolean seatVentFeatureSupported = false;

    /**
     * Probe (and cache) whether the trim has ventilated seats. Used by the
     * vehicle-control UI to grey out the cool buttons on cars without the
     * hardware (e.g. base-trim Atto 3, Seal without comfort package).
     *
     * <p>FAILS OPEN: an INDETERMINATE probe (method absent / threw / unexpected value, or the
     * device asleep) reports supported and does not latch, so the control stays usable and the
     * probe is retried. Only a definitive NOT_HAS_THE_FEATURE greys the control out.
     */
    public boolean isSeatVentilationSupported() {
        if (!seatVentFeatureProbed) {
            int probe = probeFeatureState(settingDevice, "SEAT_VENTILATING");
            if (probe == FEATURE_INDETERMINATE) return true; // fail open, re-probe next call
            seatVentFeatureProbed = true;
            seatVentFeatureSupported = (probe == FEATURE_SUPPORTED);
        }
        return seatVentFeatureSupported;
    }

    // Tri-state capability-probe results. A probe that cannot answer is INDETERMINATE, which is
    // NOT the same as "unsupported": callers must fail OPEN on it and must not cache it, or one
    // transient miss disables a feature for the whole daemon lifetime (the seat-ventilation bug).
    private static final int FEATURE_SUPPORTED = 1;
    private static final int FEATURE_UNSUPPORTED = 0;
    private static final int FEATURE_INDETERMINATE = -1;

    /**
     * Capability probe via BYDAutoSettingDevice.hasFeature(String), tri-state.
     *
     * <p>The canonical SDK returns DEVICE_HAS_THE_FEATURE(1) / DEVICE_NOT_HAS_THE_FEATURE(0).
     * Only those two are definitive. A null device, an absent method, a thrown exception or a
     * non-numeric/out-of-domain answer means we genuinely do not know →
     * {@link #FEATURE_INDETERMINATE}.
     *
     * @return {@link #FEATURE_SUPPORTED}, {@link #FEATURE_UNSUPPORTED} or
     *         {@link #FEATURE_INDETERMINATE}
     */
    private static int probeFeatureState(Object settingDevice, String feature) {
        if (settingDevice == null || feature == null) return FEATURE_INDETERMINATE;
        try {
            Method m = settingDevice.getClass().getMethod("hasFeature", String.class);
            Object result = m.invoke(settingDevice, feature);
            if (result instanceof Number) {
                int v = ((Number) result).intValue();
                if (v == 1) return FEATURE_SUPPORTED;
                if (v == 0) return FEATURE_UNSUPPORTED;
            }
            return FEATURE_INDETERMINATE; // unexpected shape/value — don't guess
        } catch (Exception e) {
            return FEATURE_INDETERMINATE;
        }
    }

    /** Human-readable probe state for logs (never a hardcoded value — that hid the real answer). */
    private static String describeFeatureState(int state) {
        switch (state) {
            case FEATURE_SUPPORTED:   return "SUPPORTED(1)";
            case FEATURE_UNSUPPORTED: return "UNSUPPORTED(0)";
            default:                  return "INDETERMINATE (absent/threw/unexpected value)";
        }
    }

    // --- Lights ---

    public boolean setDayTimeLight(boolean enable) {
        try {
            Object result = BydDeviceHelper.callMethod(lightDevice, "setDayTimeLightState", enable ? 1 : 2);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setDayTimeLight failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Set hazard (double-flash) lights on/off. Writes the double-flash COMMAND feature
     * via the generic set path (on=1, off=2 — the {@code setDayTimeLightState}
     * convention). The feature id is {@link HazardLightProbe#LIGHT_CMD_DOUBLE_FLASH}
     * (resolve-by-name → 0x39400033 fallback); it is the single source of truth so the
     * on-device probe ({@code GET /api/debug/light/fire?candidate=A}) and this SET agree.
     *
     * <p><b>Unconfirmed on this platform.</b> Hazard SET has no reference-app precedent
     * (mature OEM apps only READ hazard state) and the writable feature id is inferred,
     * not a documented SDK constant. If the HAL rejects it (uid/package gate or a
     * standstill interlock), this returns false and the action reports failure rather
     * than pretending to work. Validate with the probe first; if a different candidate
     * lands, update {@link HazardLightProbe#LIGHT_CMD_DOUBLE_FLASH} (or add a dedicated
     * winning id) and this method follows automatically. The hazard READBACK
     * ({@code getLightStatus(8)} → {@code snap.hazard}) is independent of this write, but it is
     * NOT confirmed either — position 8 is LIGHT_FOOT in the SDK's light-type table, so it may
     * report the footwell lamp rather than hazard state (see {@link #collectLight}).
     */
    public boolean setHazardLights(boolean enable) {
        if (lightDevice == null) return false;
        try {
            int code = BydDeviceHelper.sendSetCommandRaw(
                    lightDevice, HazardLightProbe.LIGHT_CMD_DOUBLE_FLASH, enable ? 1 : 2);
            // sendSetCommandRaw returns the raw SDK code: 0 = accepted. A negative code
            // (e.g. -2147482648 FAILED) means the HAL refused the write.
            return code == 0;
        } catch (Exception e) {
            logger.debug("setHazardLights failed: " + e.getMessage());
        }
        return false;
    }

    // --- ADAS ---

    /**
     * Speed-limit warning on/off. Value convention on=2/off=1 (matches the reference
     * SDK). Two feature ids exist for this on different trims: our original
     * {@code ADAS_SLW_FUNC_SWITCH_STATE_SET} (name absent from the reference SDK — a
     * trim-specific id), and the reference's own {@code ADAS_ISLA_SWITCH_SET} (ISLA,
     * the id its speed-limit-alert control uses). Rather than bet on one, try the SLW id
     * first and, if the HAL refuses it, fall back to the ISLA id — so the control works
     * whichever id this trim honours. Both are writes on adasDevice via callSetSingle
     * (0 = accepted).
     */
    public boolean setSpeedLimitWarning(boolean enable) {
        int v = enable ? 2 : 1;
        try {
            // Route through setAdasFeature → sendSetCommand (success = code >= 0), the SAME
            // path the OEM vehicle-control app's setSpeedLimitAlert uses for the ISLA id and the
            // convention every other feature-id ADAS setter here already uses. The old
            // callSetSingle(...) == 0 used the fragile 3-int set() overload AND an exact-zero
            // test, so a benign non-zero-positive HAL return was read as failure (the
            // reported "speed-limit warning doesn't work"). A refused primary id still
            // returns the large-negative FAILED code, so the ISLA fallback is still tried.
            if (setAdasFeature(BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE_SET, v)) {
                return true;
            }
            // Primary id refused → fall back to the reference-confirmed ISLA id.
            logger.info("setSpeedLimitWarning: SLW id refused, trying ISLA id");
            return setAdasFeature(BydFeatureIds.ADAS_ISLA_SWITCH_SET, v);
        } catch (Exception e) {
            logger.debug("setSpeedLimitWarning failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Lane-assist mode (Lane Departure Warning / Prevention) via the OEM firmware's
     * dedicated {@code BYDAutoADASDevice.setLKSMode(int)} method — NOT the generic
     * {@code set(featureId,value)} path, and NOT the ELKA (emergency) switch the old
     * impl mistakenly wrote to on the wrong (setting) device.
     *
     * <p>Takes an APP-LEVEL mode and maps it to the MCU value the HAL expects, per the
     * OEM firmware: {@code 0→0 (Off), 1→1 (LDW), 2→4 (LDP), 3→3 (LDW+LDP)}. Note the
     * non-contiguous 2→4 — LDP is MCU value 4, so a naive pass-through would set the
     * wrong mode. Probed by name so an SDK rename surfaces at WARN rather than silently
     * writing to a dead device.
     */
    public boolean setLaneAssistMode(int mode) {
        if (ensureAdasDevice() == null) {
            logger.warn("setLaneAssistMode: adasDevice unavailable");
            return false;
        }
        int mcuValue;
        switch (mode) {
            case 1:  mcuValue = 1; break; // LDW
            case 2:  mcuValue = 4; break; // LDP (MCU 4, not 2)
            case 3:  mcuValue = 3; break; // LDW + LDP
            case 0:
            default: mcuValue = 0; break; // Off
        }
        Method m;
        try {
            m = adasDevice.getClass().getMethod("setLKSMode", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setLKSMode: method not present on "
                + adasDevice.getClass().getSimpleName()
                + " — lane-assist control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setLKSMode lookup failed: " + e.getMessage());
            return false;
        }
        try {
            // Accept-on-no-throw (matches the OEM vehicle-control app's setLaneAssistMode and
            // our setAdasReflection/mirror-fold/brightness contract) — do NOT gate on the
            // SDK return, which can be a benign non-zero/negative code read as failure.
            m.invoke(adasDevice, mcuValue);
            logger.info("setLKSMode(mode=" + mode + " mcu=" + mcuValue + ") invoked");
            return true;
        } catch (Exception e) {
            logger.warn("setLKSMode(mode=" + mode + " mcu=" + mcuValue + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Current lane-assist mode as app-level {@code 0=Off / 1=LDW / 2=LDP / 3=LDW+LDP},
     * or -1 if unavailable. Reads {@code BYDAutoADASDevice.getLKSMode()} and undoes the
     * setter's app→MCU mapping (MCU {@code 0→0, 1→1, 4→2, 3→3}). Used for toggle/cycle
     * readback and UI state.
     */
    public int getLaneAssistMode() {
        if (ensureAdasDevice() == null) return -1;
        try {
            Method m = adasDevice.getClass().getMethod("getLKSMode");
            Object r = m.invoke(adasDevice);
            if (!(r instanceof Number)) return -1;
            int mcu = ((Number) r).intValue();
            if (mcu == BydFeatureIds.SDK_NOT_AVAILABLE) return -1;
            switch (mcu) {
                case 0: return 0; // Off
                case 1: return 1; // LDW
                case 4: return 2; // LDP
                case 3: return 3; // LDW + LDP
                default: return -1;
            }
        } catch (NoSuchMethodException nsme) {
            return -1;
        } catch (Exception e) {
            logger.debug("getLaneAssistMode failed: " + e.getMessage());
            return -1;
        }
    }

    // ── ADAS feature writes ──────────────────────────────────────────────────
    // All ADAS feature-id writes target the ADAS device (BYDAutoADASDevice), NOT the
    // setting device. The orphaned setters below originally wrote to settingDevice,
    // which is why they were silent no-ops. A generic helper keeps them consistent and
    // guards the null device. Feature ids + on/off values are per the OEM SDK; verify
    // on-car via GET /api/vehicle/adas before trusting on any given trim.

    /** Fully-qualified ADAS device class — shared by init and the on-demand re-acquire. */
    private static final String ADAS_DEVICE_CLASS = "android.hardware.bydauto.adas.BYDAutoADASDevice";

    /**
     * Return the ADAS device, re-acquiring it on demand if it is currently null.
     *
     * <p>{@code adasDevice} is normally bound once during {@link #init(Context)}. But if the
     * ADAS HAL binder isn't ready at daemon startup (a boot race), that single bind returns
     * null and — with no re-acquire path — EVERY ADAS control (cross-traffic brake/alert,
     * TLA/DOW/RCW, ISLC/ELKA/FCW, BSD/AEB/TSR, ESP, lane-assist) stays permanently dead for
     * the collector's lifetime. This mirrors the OEM SDK reference's own ensure-device pattern:
     * if already bound, reuse it; otherwise attempt a fresh bind now so a transient startup
     * null self-heals on the next control call. A genuinely absent class on an unsupported
     * trim still returns null, same as before.
     */
    private Object ensureAdasDevice() {
        if (adasDevice != null) return adasDevice;
        if (context == null) {
            // init() hasn't run yet — nothing to bind against.
            return null;
        }
        Object device = BydDeviceHelper.getDevice(ADAS_DEVICE_CLASS, context);
        if (device != null) {
            adasDevice = device;
            logger.info("ensureAdasDevice: ADAS device re-acquired on demand (was null at init)");
        } else {
            logger.warn("ensureAdasDevice: ADAS device still unavailable on re-acquire");
        }
        return adasDevice;
    }

    /** Generic ADAS feature-id write on the ADAS device. False if device unavailable. */
    private boolean setAdasFeature(int featureId, int value) {
        if (ensureAdasDevice() == null) {
            logger.warn("setAdasFeature: adasDevice unavailable (id=" + featureId + ")");
            return false;
        }
        try {
            return BydDeviceHelper.sendSetCommand(adasDevice, featureId, value);
        } catch (Exception e) {
            logger.debug("setAdasFeature(id=" + featureId + ",v=" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generic ADAS reflection write for functions with a dedicated method and no feature
     * id (BSD/AEB/SLA/…), mirroring {@link #setLaneAssistMode}'s setLKSMode path. False
     * if the device or method is absent on this OEM build.
     *
     * <p>SUCCESS SEMANTICS: accept-on-no-throw — the SAME contract the OEM vehicle-control app
     * app uses for EVERY ADAS reflection setter (it invokes {@code setBSDState/setSLAState/
     * setAEBState/setLKSMode/…} and unconditionally returns true, never inspecting the SDK
     * return). Routing these through {@link #isSdkWriteSuccess} was the reported "most ADAS
     * controls don't work" bug: that helper reports failure when the SDK method returns
     * Boolean {@code false} or a benign NEGATIVE int, so a physically-successful write was
     * misreported as failed and the automation/keymap said it didn't work. This mirrors the
     * fix already applied to {@link #setMirrorsFolded} and {@link #setBrightnessViaMethodOn}.
     * The invoke NOT throwing is the success signal; only a missing method or a thrown
     * invoke is a real failure.
     */
    private boolean setAdasReflection(String method, int value) {
        if (ensureAdasDevice() == null) {
            logger.warn(method + ": adasDevice unavailable");
            return false;
        }
        try {
            Method m = adasDevice.getClass().getMethod(method, int.class);
            m.invoke(adasDevice, value);
            logger.info(method + "(" + value + ") invoked");
            return true;
        } catch (NoSuchMethodException nsme) {
            logger.warn(method + ": not present on " + adasDevice.getClass().getSimpleName()
                    + " — unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn(method + "(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Blind-Spot Detection on/off — reflection setBSDState (on=1/off=0). */
    public boolean setBlindSpotDetection(boolean enabled) {
        return setAdasReflection("setBSDState", enabled ? 1 : 0);
    }

    /**
     * Automatic Emergency Braking. SAFETY-CRITICAL and ENABLE-ONLY — enforced HERE, at
     * the single chokepoint, not just in the automation action. A disable request from
     * ANY entry point (automation, key mapping, MQTT/Home Assistant, cloud) is refused,
     * so no path can silently switch off collision braking. The automation action only
     * offers "on"; but the keymap catalog + MQTT switch could pass false, so we reject
     * it defensively here. (The HAL/ECU also re-arms AEB each ignition regardless.)
     * Returns true only on a successful ENABLE; false for a refused disable or a failed
     * write. Reflection setAEBState, on=1.
     */
    public boolean setEmergencyBraking(boolean enabled) {
        if (!enabled) {
            logger.warn("setEmergencyBraking: refusing to DISABLE AEB — enable-only safety control");
            return false;
        }
        return setAdasReflection("setAEBState", 1);
    }

    /** Traffic Sign Recognition on/off — reflection setSLAState (on=1/off=0). */
    public boolean setTrafficSignRecognition(boolean enabled) {
        return setAdasReflection("setSLAState", enabled ? 1 : 0);
    }

    /** Rear Cross Traffic Alert on/off (RCTA id, on=1/off=0). */
    public boolean setRearCrossTrafficAlert(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_RCTA_STATE_SET, enabled ? 1 : 0);
    }

    /** Rear Cross Traffic BRAKE on/off (ECTB id, on=1/off=0). SAFETY (auto-brake). */
    public boolean setRearCrossTrafficBraking(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_ECTB_STATE_SET, enabled ? 1 : 0);
    }

    /**
     * Front cross-traffic switch encoding. Both alert and brake controls use
     * 2=on / 1=off; their user-facing on/off payloads must not be passed through.
     */
    static int frontCrossTrafficSwitchValue(boolean enabled) {
        return enabled ? 2 : 1;
    }

    /** Front Cross Traffic Alert on/off (FCTA id, on=2/off=1). */
    public boolean setFrontCrossTrafficAlert(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_FCTA_SWITCH_SET, frontCrossTrafficSwitchValue(enabled));
    }

    /** Front Cross Traffic BRAKE on/off (FCTB id, on=2/off=1). SAFETY (auto-brake). */
    public boolean setFrontCrossTrafficBraking(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_FCTB_SWITCH_SET, frontCrossTrafficSwitchValue(enabled));
    }

    /** Traffic Light Attention on/off (TLA id, on=1/off=0). */
    public boolean setTrafficLightAttention(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_TLA_SWITCH_SET, enabled ? 1 : 0);
    }

    /** Open-Door Warning on/off (DOW id, on=1/off=0). */
    public boolean setOpenDoorWarning(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_DOW_STATE_SET, enabled ? 1 : 0);
    }

    /** Rear Collision Warning on/off (RCW id, on=1/off=0). */
    public boolean setRearCollisionWarning(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_RCW_STATE_SET, enabled ? 1 : 0);
    }

    /** Speed Limit Control (ISLC) on/off (on=2/off=1, per OEM convention). */
    public boolean setSpeedLimitControl(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_ISLC_SWITCH_SET, enabled ? 2 : 1);
    }

    /** Emergency / Urgent Lane Keeping (ELKA) on/off (on=2/off=1). SAFETY (auto-steer). */
    public boolean setEmergencyLaneKeeping(boolean enabled) {
        return setAdasFeature(BydFeatureIds.ADAS_ELKA_SWITCH_SET, enabled ? 2 : 1);
    }

    /**
     * Forward Collision Warning sensitivity LEVEL (not on/off): 0=off, 1=low, 2=med,
     * 3=high mapped to the OEM's 1/2/3/4. SAFETY — lowering the level delays warnings.
     */
    public boolean setFcwLevel(int level) {
        int mcu;
        switch (level) {
            case 1:  mcu = 2; break; // low
            case 2:  mcu = 3; break; // medium
            case 3:  mcu = 4; break; // high
            case 0:
            default: mcu = 1; break; // off
        }
        return setAdasFeature(BydFeatureIds.ADAS_FCW_LEVEL_SET, mcu);
    }

    /**
     * Set Electronic Stability Program (ESP/ESC) on/off. SAFETY-CRITICAL, so this does
     * NOT trust a hard-coded polarity: the OEM SDK's {@code setESPState(int)} uses an
     * INVERTED convention (0=ON / 1=OFF) on {@code adasDevice}, but that is unverified
     * on every trim, and getting it backwards would DISABLE stability control when the
     * user asked to enable it. So we write the desired state, then READ IT BACK via
     * {@link #getEspState()} and, if the car reports the opposite of what was asked,
     * retry with the flipped value. The method returns true only when a readback
     * confirms the requested state actually took (or, if no readback is available on
     * this trim, when the primary write's SDK code was success — best effort).
     *
     * <p>Uses the dedicated {@code setESPState} reflection method on {@code adasDevice}
     * (the same BYD SDK surface family as our working {@code setLKSMode}), NOT the old
     * mis-wired {@code ADAS_ESP_STATE_SET} feature-id write on {@code settingDevice}
     * (wrong device + guessed id + wrong polarity — it was effectively inert/inverted).
     */
    public boolean setEspState(boolean enabled) {
        if (ensureAdasDevice() == null) {
            logger.warn("setEspState: adasDevice unavailable");
            return false;
        }
        Method m;
        try {
            m = adasDevice.getClass().getMethod("setESPState", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setESPState: method not present on "
                    + adasDevice.getClass().getSimpleName() + " — ESP control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setESPState lookup failed: " + e.getMessage());
            return false;
        }
        // Primary polarity per the OEM SDK: ESP ON = 0, OFF = 1 (INVERTED). Try it,
        // then verify by readback; flip on mismatch so a wrong-polarity trim self-heals.
        int primary = enabled ? 0 : 1;
        int flipped = enabled ? 1 : 0;
        boolean wrote = invokeEspWrite(m, primary);
        Boolean now = readEspOn();
        if (now != null) {
            if (now == enabled) return true;                  // confirmed correct
            logger.warn("setEspState: readback shows " + now + " after writing " + primary
                    + " for enabled=" + enabled + " — retrying flipped value " + flipped);
            invokeEspWrite(m, flipped);
            Boolean after = readEspOn();
            // Confirmed after flip, or (no second readback) assume the flip took.
            return after == null ? true : after == enabled;
        }
        // No readback on this trim — can't verify; report the primary write's result.
        return wrote;
    }

    /** Invoke setESPState(value) on adasDevice; true on no-throw. The caller
     *  ({@link #setEspState}) prefers a getESPState readback to confirm; this raw result is
     *  only used on trims with no readback, so it uses the same accept-on-no-throw contract
     *  as the OEM vehicle-control app's setESPEnabled (which never inspects the SDK return) —
     *  rather than isSdkWriteSuccess, which can read a benign non-zero/negative code as a
     *  failure and make the no-readback fallback wrongly report ESP as unchanged. */
    private boolean invokeEspWrite(Method m, int value) {
        try {
            m.invoke(adasDevice, value);
            return true;
        } catch (Exception e) {
            logger.warn("setESPState(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** ESP state as a resolved boolean (true=on/false=off), or null if unreadable. */
    private Boolean readEspOn() {
        int raw = getEspState();
        if (raw == 0) return Boolean.TRUE;   // SDK inverted: 0 = ON
        if (raw == 1) return Boolean.FALSE;  // 1 = OFF
        return null;                          // -1 / unknown encoding → can't verify
    }

    /**
     * Read the raw ESP/ESC state via the {@code getESPState()} reflection method on
     * {@code adasDevice} (matching the {@code setESPState} write path). Returns the raw
     * SDK int (INVERTED convention: 0=on / 1=off), or -1 when unreadable. NOTE: raw
     * semantics differ from the old setting-HAL reader — callers use {@link #readEspOn()}
     * for the resolved boolean. Kept public for on-device verification via
     * {@code GET /api/vehicle/adas}.
     */
    public int getEspState() {
        if (ensureAdasDevice() == null) return -1;
        try {
            Method m = adasDevice.getClass().getMethod("getESPState");
            Object r = m.invoke(adasDevice);
            return (r instanceof Number) ? ((Number) r).intValue() : -1;
        } catch (Exception e) {
            logger.debug("getEspState failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * iTAC (Intelligent Torque Adaption Control) on/off via the setting HAL
     * ({@code SETTING_ITAC_STATE_SET}). Unlike ESP this is a performance/traction
     * feature, not a stability-safety interlock. Returns false if the write path
     * threw. The feature ids are decoded from the DiLink APK (see BydFeatureIds);
     * verify with {@link #getItacState()} on-car before trusting the toggle.
     *
     * <p>Value convention per the OEM firmware's
     * {@code setItacEnabled}: the HAL wants {@code on=1 / off=2} (the same 1=on/2=off
     * convention as CPD), NOT {@code off=0}. Sending 0 was ignored by the HAL, which
     * is why the toggle appeared to do nothing.
     */
    public boolean setItacState(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_ITAC_STATE_SET, enabled ? 1 : 2);
        } catch (Exception e) {
            logger.debug("setItacState failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Read the raw iTAC state via the setting HAL ({@code SETTING_ITAC_STATE}).
     * Returns the raw SDK int (typically 1=on / 0=off), or -1 when the read fails /
     * device is unavailable. Exposed for on-device verification of the iTAC feature
     * ids before the toggle is trusted (mirrors {@link #getEspState()}).
     */
    public int getItacState() {
        try {
            return BydDeviceHelper.callGetSingle(settingDevice, BydFeatureIds.SETTING_ITAC_STATE);
        } catch (Exception e) {
            logger.debug("getItacState failed: " + e.getMessage());
            return -1;
        }
    }

    // NOTE: setIslaSwitch / setIslcSwitch removed — they were dead (no callers) and
    // carried the wrong-device/wrong-value bug (settingDevice + 1:0). The LIVE speed-
    // limit paths are setSpeedLimitWarning (ISLA, adasDevice, 2:1) and
    // setSpeedLimitControl (ISLC, adasDevice, 2:1), which are correct per the OEM SDK.

    // --- Media ---

    /**
     * Send media info (artist + title) to the instrument cluster display.
     * Encodes the string as UTF-16LE bytes for the BYD instrument cluster.
     */
    public boolean sendMediaInfo(String artistAndTitle) {
        try {
            if (artistAndTitle == null) return false;
            String formatted = "  " + artistAndTitle + "  ";
            byte[] bytes = formatted.getBytes("UTF-16LE");
            byte[] finalBytes;
            if (bytes.length > 255) {
                // Truncate to 253 bytes + 2-byte null terminator
                finalBytes = new byte[255];
                System.arraycopy(bytes, 0, finalBytes, 0, 253);
                finalBytes[253] = 0;
                finalBytes[254] = 0;
            } else {
                finalBytes = bytes;
            }
            int result = BydDeviceHelper.callSetBuffer(instrumentDevice, 1140527112, finalBytes);
            return result >= 0;
        } catch (Exception e) {
            logger.debug("sendMediaInfo failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicSource(int source) {
        try {
            if (source < 0 || source > 14) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_SOURCE_SET, source);
        } catch (Exception e) {
            logger.debug("setMusicSource failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicState(int state) {
        try {
            if (state < 1 || state > 2) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_STATE_SET, state);
        } catch (Exception e) {
            logger.debug("setMusicState failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicPlaybackProgress(int currentSeconds, int totalSeconds) {
        try {
            if (currentSeconds < 0 || totalSeconds < 0) return false;
            // Pack current and total into the feature ID call
            // Progress is sent as a single int: current seconds (the cluster calculates percentage)
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET, currentSeconds);
        } catch (Exception e) {
            logger.debug("setMusicPlaybackProgress failed: " + e.getMessage());
            return false;
        }
    }

    // --- Display ---

    /**
     * Invoke a dedicated {@code BYDAutoSettingDevice.<methodName>(int)} brightness
     * method (0..100) by reflection. Per the OEM firmware:
     * screen brightness is driven through dedicated methods
     * ({@code setInfotainmentBrightness} / {@code setDriverDisplayBrightness} /
     * {@code setHUDBrightness}), NOT the generic {@code set(SET_BRIGHTNESS_GEAR_SET,
     * EventValue)} feature-id path — that feature id is dead code in the SDK and
     * writing to it does nothing. Probed by name so an SDK rename surfaces at WARN.
     *
     * <p>SUCCESS SEMANTICS: the OEM firmware's own brightness setters INVOKE and then
     * return their own {@code true} on no-exception — they DO NOT inspect the SDK
     * method's return value. We match that: {@code isSdkWriteSuccess} was
     * mis-rejecting physically-successful writes for these void/int-returning setters
     * (a non-zero-but-benign return read as failure), which is why the control
     * reported failure even when it worked. So here: if the invoke does not throw, the
     * write was accepted → return {@code true}. Only a missing method or a thrown
     * invoke is a real failure.
     */
    private boolean setBrightnessViaMethod(String methodName, int level) {
        return setBrightnessViaMethodOn(settingDevice, methodName, level);
    }

    /** Invoke a {@code setXBrightness(int)} on a SPECIFIC setting-device handle. Factored
     *  out of setBrightnessViaMethod so the cluster path can also target the system-context
     *  handle. A null device is a quiet no-op (returns false) — used when the system-context
     *  fallback device couldn't be obtained. */
    private boolean setBrightnessViaMethodOn(Object dev, String methodName, int level) {
        if (dev == null) return false;
        if (level < 0 || level > 100) return false;
        Method m;
        try {
            m = dev.getClass().getMethod(methodName, int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn(methodName + ": method not present on "
                + dev.getClass().getSimpleName()
                + " — brightness control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn(methodName + " lookup failed: " + e.getMessage());
            return false;
        }
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;
        try {
            m.invoke(dev, level);
            // Accept on no-exception (mirrors the OEM firmware's own setter contract);
            // do NOT gate on the return value — these setters return a non-success-coded
            // int/void that isSdkWriteSuccess wrongly treated as failure.
            logger.info(methodName + "(" + level + ") invoked");
            return true;
        } catch (Exception e) {
            logger.warn(methodName + "(" + level + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Infotainment (centre) screen brightness. The centre screen is the MAIN Android
     * display, so besides the BYD SDK setter we ALSO drive the Android
     * {@code Settings.System.SCREEN_BRIGHTNESS} via the shell (the daemon runs as UID
     * 2000 / shell, which holds WRITE_SETTINGS). This is the lever that actually moves
     * the panel when the SDK setter is a no-op on a trim, and it also disables
     * auto-brightness first (otherwise the ambient-light sensor immediately overrides
     * a manual set — the observed "brightness does nothing" symptom). The 0..100
     * percentage maps onto the Android 0..255 range. Best-effort: succeeds if EITHER
     * path is accepted.
     */
    public boolean setInfotainmentBrightness(int level) {
        if (level < 0 || level > 100) return false;
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;
        boolean sdkOk = setBrightnessViaMethod("setInfotainmentBrightness", level);
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return sdkOk;
        boolean shellOk = setAndroidScreenBrightness(level);
        return sdkOk || shellOk;
    }

    // A second BYDAutoSettingDevice handle obtained with the REAL system context
    // (ActivityThread.getSystemContext), NOT the daemon's synthetic PermissionBypassContext.
    // Some setting-HAL writes — driver-cluster brightness among them — bind to the calling
    // package/context and SILENTLY NO-OP when invoked on a device created from the synthetic
    // context (the invoke throws nothing, so it looks like it "worked"). The reference app
    // runs in a normal app process with a real context, which is why the same SDK call moves
    // the cluster there. Built lazily + cached, off the shared `settingDevice` so the many
    // setters that already work on it are never disturbed. null if the system context or
    // device can't be obtained (we then just keep the primary result).
    private volatile Object systemCtxSettingDevice;
    private volatile boolean systemCtxSettingResolved = false;

    private Object getSystemContextSettingDevice() {
        if (systemCtxSettingResolved) return systemCtxSettingDevice;
        synchronized (this) {
            if (systemCtxSettingResolved) return systemCtxSettingDevice;
            Object dev = null;
            try {
                // Same proven "real system context" acquisition used by the multimedia
                // device fallback, guarded by a timeout thread since getSystemContext can
                // deadlock on some framework states.
                final Object[] result = new Object[1];
                Thread t = new Thread(() -> {
                    try {
                        Class<?> atClass = Class.forName("android.app.ActivityThread");
                        Object at = atClass.getMethod("currentActivityThread").invoke(null);
                        if (at != null) {
                            android.content.Context sysCtx = (android.content.Context)
                                    atClass.getMethod("getSystemContext").invoke(at);
                            if (sysCtx != null) {
                                result[0] = BydDeviceHelper.getDevice(
                                        "android.hardware.bydauto.setting.BYDAutoSettingDevice", sysCtx);
                            }
                        }
                    } catch (Throwable inner) {
                        logger.debug("systemCtx setting device inner: " + inner.getMessage());
                    }
                }, "SettingDevice-SysCtx");
                t.setDaemon(true);
                t.start();
                t.join(3000);
                if (t.isAlive()) { logger.warn("systemCtx setting device timed out"); t.interrupt(); }
                else dev = result[0];
            } catch (Throwable e) {
                logger.debug("getSystemContextSettingDevice failed: " + e.getMessage());
            }
            systemCtxSettingDevice = dev;
            systemCtxSettingResolved = true;
            if (dev != null) logger.info("System-context BYDAutoSettingDevice resolved (cluster-brightness fallback)");
            return dev;
        }
    }

    /**
     * Driver-cluster brightness. Three complementary write paths, best-effort (succeeds if
     * any lands):
     *  1) {@code setDriverDisplayBrightness(0..100)} on the setting HAL (primary handle) —
     *     matches the OEM vehicle-control app DiLink3/4 path.
     *  2) the same setter on a real system-context handle — the synthetic daemon context can
     *     silently no-op this particular write (see {@link #getSystemContextSettingDevice}).
     *  3) {@code BYDAutoInstrumentDevice.setBacklightBrightness(1..12)} — the driver INSTRUMENT
     *     cluster is served by the instrument device on some trims (DiLink5 / where the
     *     setting-HAL write no-ops). the secondary reference app drives the cluster this way (C4178d
     *     setBacklightBrightness on a 1-12 gear scale, wrapping values >11). We map the
     *     incoming 0-100 percent onto 1..12 so a single action reaches whichever HAL this
     *     trim honours.
     */
    public boolean setDriverDisplayBrightness(int level) {
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;
        boolean primary = setBrightnessViaMethod("setDriverDisplayBrightness", level);
        Object systemDevice = getSystemContextSettingDevice();
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return primary;
        boolean sysCtx = setBrightnessViaMethodOn(
                systemDevice, "setDriverDisplayBrightness", level);
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) {
            return primary || sysCtx;
        }
        boolean instrument = setInstrumentBacklightBrightness(level);
        return primary || sysCtx || instrument;
    }

    /**
     * Fallback driver-cluster path: {@code BYDAutoInstrumentDevice.setBacklightBrightness(int)}
     * on a 1..12 gear scale (the secondary reference app C4178d). Maps the incoming 0..100 percent to 1..12. A
     * missing method / device is a quiet no-op (returns false). Accept-on-no-throw, matching
     * the other reflection setters.
     */
    private boolean setInstrumentBacklightBrightness(int level) {
        if (instrumentDevice == null) return false;
        if (level < 0 || level > 100) return false;
        int gear = Math.max(1, Math.min(12, Math.round(1 + (level / 100f) * 11f)));
        try {
            Method m = instrumentDevice.getClass().getMethod("setBacklightBrightness", int.class);
            if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;
            m.invoke(instrumentDevice, gear);
            logger.info("setBacklightBrightness(" + gear + ") invoked (from " + level + "%)");
            return true;
        } catch (NoSuchMethodException nsme) {
            logger.debug("setBacklightBrightness absent on instrument device — skipping cluster fallback");
            return false;
        } catch (Exception e) {
            logger.debug("setBacklightBrightness(" + gear + ") failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setHudBrightness(int level) {
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;
        // HUD BRIGHTNESS (0..100) via the named BYDAutoSettingDevice.setHUDBrightness(int).
        // This is separate from HUD power on/off (see setHudPower, which writes the dedicated
        // SET_HUD_SWITCH_SET feature-id). From the daemon (UID 2000, synthetic context) the
        // named setter accepts the reflection call without throwing but silently no-ops — the
        // same signature-gate that afflicts every setting-family DISPLAY write here. So the
        // ACTUAL actuating path is the app-process dispatch below: it re-issues the identical
        // setHUDBrightness call from the real app process where the setting HAL honours it. The
        // daemon call is kept only as a harmless best-effort for any firmware where the write is
        // live from UID 2000 (accept-on-no-throw).
        boolean named = setBrightnessViaMethod("setHUDBrightness", level);
        // Also write through the SYSTEM-CONTEXT setting handle, exactly as
        // setDriverDisplayBrightness still does. This attempt was dropped when the app-process
        // dispatch was added, but it is a DIFFERENT device handle, not a duplicate of the line
        // above — it is the path that actuates on trims where the synthetic-context handle
        // no-ops, and losing it meant HUD brightness stopped working entirely whenever the app
        // process wasn't up to serve the dispatch.
        Object systemDevice = getSystemContextSettingDevice();
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return named;
        boolean sysCtx = setBrightnessViaMethodOn(systemDevice, "setHUDBrightness", level);
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) {
            return named || sysCtx;
        }

        // The real path: run setHUDBrightness from the REAL app process — see setMirrorsFolded
        // for the rationale (some setting HALs only actuate from a normal app-process Context,
        // not the daemon's). Async; the app service (VehicleActuatorService) logs its own result.
        try {
            com.overdrive.app.byd.VehicleActuatorBridge.dispatchHud(level);
        } catch (Throwable t) {
            logger.debug("setHudBrightness app-process dispatch failed: " + t.getMessage());
        }
        return named || sysCtx;
    }

    /**
     * HUD POWER on/off — the DEDICATED switch, distinct from {@link #setHudBrightness}. On the
     * newer OEM firmware the HUD on/off state is its own Setting feature-id
     * ({@link BydFeatureIds#SETTING_HUD_SWITCH_SET}), written as a {@code BYDAutoEventValue} via
     * the standard {@code set(int[], EventValue)} path — value 1 = on, 2 = off (NOT 0). Driving
     * brightness to 0 does NOT toggle this switch, which is why a brightness-only "HUD off" left
     * the HUD lit. Like brightness, the write only actuates from a real app process, so the
     * daemon attempt is a best-effort and the app-process dispatch is the load-bearing path.
     */
    public boolean setHudPower(boolean on) {
        if (!on && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
        int val = on ? 1 : 2; // OEM contract: 1=on, 2=off
        boolean daemonOk = false;
        try {
            daemonOk = BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_HUD_SWITCH_SET, val);
            logger.info("setHudPower: SET_HUD_SWITCH_SET(" + val + ") daemon accepted=" + daemonOk);
        } catch (Throwable t) {
            logger.debug("setHudPower daemon write failed: " + t.getMessage());
        }
        if (!on && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) {
            return daemonOk;
        }
        // Load-bearing path: run the identical switch write from the REAL app process.
        try {
            com.overdrive.app.byd.VehicleActuatorBridge.dispatchHudPower(on);
        } catch (Throwable t) {
            logger.debug("setHudPower app-process dispatch failed: " + t.getMessage());
        }
        return daemonOk;
    }

    /**
     * Drive the main Android display brightness via {@code settings put system}. The
     * daemon is UID 2000 (shell), so {@code settings put} writes System settings
     * directly — the same mechanism used elsewhere for Secure settings. Turns
     * auto-brightness OFF first ({@code screen_brightness_mode 0}) so a manual level
     * isn't instantly overridden by the ambient sensor, then sets the 0..255 value
     * scaled from the 0..100 input. Bounded exec so a stuck {@code settings} can never
     * park the caller. Returns true when the write command completes without error.
     */
    private boolean setAndroidScreenBrightness(int percent) {
        int v255 = Math.max(0, Math.min(255, Math.round(percent / 100f * 255f)));
        String script = "settings put system screen_brightness_mode 0; "
                + "settings put system screen_brightness " + v255;
        try {
            if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;
            Process p = new ProcessBuilder("sh", "-c", script)
                    .redirectErrorStream(true).start();
            // Drain + bound so the child can't wedge on a full pipe or outlive the call.
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[512];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, "screen-brightness-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); logger.warn("setAndroidScreenBrightness: settings put timed out"); return false; }
            try { is.close(); } catch (Throwable ignored) {}
            boolean ok = p.exitValue() == 0;
            logger.info("setAndroidScreenBrightness: " + percent + "% (=" + v255 + "/255) shell ok=" + ok);
            return ok;
        } catch (Throwable t) {
            logger.warn("setAndroidScreenBrightness failed: " + t.getMessage());
            return false;
        }
    }

    // --- Screen power (backlight on/off) ---
    //
    // Turn the infotainment (centre) screen fully on/off. This is the SAME
    // proven mechanism the ACC-sentry daemon uses to blank the panel while
    // keeping CPU/radio alive — deliberately NOT PowerManager.goToSleep()
    // (which the car's ACC-on keep-awake logic fights, causing the panel to
    // flick back on). The probe order, verified working on this hardware, is:
    //   1. PowerManager.turnBacklightOn/turnBacklightOff(long)   — lowercase
    //   2. PowerManager.TurnBacklightOn/TurnBacklightOff(long)   — PascalCase
    //   3. BYDAutoSettingDevice.turnBacklightOn/turnBacklightOff()
    //   4. shell: settings put system screen_brightness + input keyevent
    //      224 (WAKEUP) / 223 (SLEEP)
    // First success short-circuits. Every tier is independently try/caught so
    // a missing method just falls through to the next. Returns true when any
    // tier is accepted.
    private volatile Method screenBacklightLowerOn;
    private volatile Method screenBacklightLowerOff;
    private volatile boolean screenBacklightLowerProbed;
    private volatile Method screenBacklightPascalOn;
    private volatile Method screenBacklightPascalOff;
    private volatile boolean screenBacklightPascalProbed;

    public boolean setScreenPower(boolean on) {
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
        logger.info("setScreenPower: " + (on ? "ON" : "OFF"));
        // An explicit screen-ON while parked must not be undone within 10s by the
        // ACC-sentry keep-alive in the OTHER process (which darkens the panel on
        // dilink4). Publish a short grace it honours. Also escalate the wake
        // through StealthPanel: the tiers below have no *WithLock variant, so on
        // firmware where the panel was darkened via TurnBacklightOffWithLock they
        // may not light it at all. Both are no-ops on legacy units.
        if (on) {
            try {
                com.overdrive.app.power.StealthPanel.requestUserOverride();
                if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
                com.overdrive.app.power.StealthPanel.turnOn(context);
            } catch (Throwable t) {
                logger.debug("setScreenPower: verified wake failed: " + t.getMessage());
            }
        } else {
            // Explicit screen-OFF: the tiers below change the panel outside
            // StealthPanel, which is unobservable on firmware with no
            // getPowerScreenStatus(). Declare it so its latch doesn't later skip
            // a genuinely-needed write.
            try {
                com.overdrive.app.power.StealthPanel.notePanelStateChangedExternally();
            } catch (Throwable ignored) {}
        }
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
        // Tier 1 + 2: PowerManager backlight reflection (the primary path).
        Context ctx = context;
        if (ctx != null) {
            try {
                android.os.PowerManager pm =
                        (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    Class<?> pmClass = pm.getClass();
                    long now = android.os.SystemClock.uptimeMillis();
                    // Tier 1 — lowercase turnBacklightOn/Off(long).
                    if (!screenBacklightLowerProbed) {
                        try { screenBacklightLowerOn = pmClass.getMethod("turnBacklightOn", long.class); } catch (Throwable ignored) {}
                        try { screenBacklightLowerOff = pmClass.getMethod("turnBacklightOff", long.class); } catch (Throwable ignored) {}
                        screenBacklightLowerProbed = true;
                    }
                    Method lower = on ? screenBacklightLowerOn : screenBacklightLowerOff;
                    if (lower != null) {
                        try {
                            if (drivingSafetyBlocked(
                                    DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
                            lower.invoke(pm, now);
                            logger.info("setScreenPower: PowerManager." + (on ? "turnBacklightOn" : "turnBacklightOff") + " OK");
                            return true;
                        } catch (Throwable t) {
                            logger.debug("PowerManager backlight (lower) invoke failed: " + t.getMessage());
                        }
                    }
                    // Tier 2 — PascalCase TurnBacklightOn/Off(long).
                    if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) {
                        return false;
                    }
                    if (!screenBacklightPascalProbed) {
                        try { screenBacklightPascalOn = pmClass.getMethod("TurnBacklightOn", long.class); } catch (Throwable ignored) {}
                        try { screenBacklightPascalOff = pmClass.getMethod("TurnBacklightOff", long.class); } catch (Throwable ignored) {}
                        screenBacklightPascalProbed = true;
                    }
                    Method pascal = on ? screenBacklightPascalOn : screenBacklightPascalOff;
                    if (pascal != null) {
                        try {
                            if (drivingSafetyBlocked(
                                    DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
                            pascal.invoke(pm, now);
                            logger.info("setScreenPower: PowerManager." + (on ? "TurnBacklightOn" : "TurnBacklightOff") + " OK");
                            return true;
                        } catch (Throwable t) {
                            logger.debug("PowerManager backlight (pascal) invoke failed: " + t.getMessage());
                        }
                    }
                }
            } catch (Throwable t) {
                logger.debug("setScreenPower PowerManager path failed: " + t.getMessage());
            }
        }
        // Tier 3 — BYDAutoSettingDevice.turnBacklightOn/turnBacklightOff() (no-arg).
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
        if (settingDevice != null) {
            try {
                Method m = settingDevice.getClass().getMethod(on ? "turnBacklightOn" : "turnBacklightOff");
                if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
                m.invoke(settingDevice);
                logger.info("setScreenPower: BYDAutoSettingDevice." + (on ? "turnBacklightOn" : "turnBacklightOff") + " OK");
                return true;
            } catch (NoSuchMethodException nsme) {
                logger.debug("setScreenPower: BYDAutoSettingDevice backlight method absent");
            } catch (Throwable t) {
                logger.debug("setScreenPower BYD path failed: " + t.getMessage());
            }
        }
        // Tier 4 — shell fallback: set brightness then WAKEUP/SLEEP keyevent.
        if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
        return setScreenPowerViaShell(on);
    }

    /**
     * Shell fallback for screen power (daemon is UID 2000 / shell). Mirrors the
     * ACC-sentry daemon's fallback: nudge brightness (128 on / 0 off) then inject
     * KEYCODE_WAKEUP (224) / KEYCODE_SLEEP (223). Bounded exec so a stuck child
     * never parks the caller. Returns true when the command completes cleanly.
     */
    private boolean setScreenPowerViaShell(boolean on) {
        int brightness = on ? 128 : 0;
        int keyevent = on ? 224 : 223; // 224=WAKEUP, 223=SLEEP
        String script = "settings put system screen_brightness " + brightness + "; "
                + "input keyevent " + keyevent;
        try {
            if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;
            Process p = new ProcessBuilder("sh", "-c", script)
                    .redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[512];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, "screen-power-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); logger.warn("setScreenPowerViaShell: timed out"); return false; }
            try { is.close(); } catch (Throwable ignored) {}
            boolean ok = p.exitValue() == 0;
            logger.info("setScreenPowerViaShell: " + (on ? "ON" : "OFF") + " (kev " + keyevent + ") shell ok=" + ok);
            return ok;
        } catch (Throwable t) {
            logger.warn("setScreenPowerViaShell failed: " + t.getMessage());
            return false;
        }
    }

    // --- Miscellaneous ---

    /**
     * Fold/unfold the exterior mirrors from the UID-2000 daemon.
     *
     * <p>The connected vehicle's DiCar write profile maps the manual fold command to
     * {@code BYDAutoSettingDevice} (device type 1023), feature {@code 0x4C10A028}, with
     * 1=fold / 2=unfold. The older bodywork API uses 1/0 and remains below only as a
     * compatibility fallback for firmware that actually exposes {@code setMirrorFoldState(int)}.
     */
    public boolean setMirrorsFolded(boolean folded) {
        if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
            return false;
        }
        int settingValue = BydConstants.mirrorFoldCommand(folded);
        if (setMirrorsFoldedOnSettingDevice(settingValue, folded)) return true;
        if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
            return false;
        }

        // Legacy bodywork API encoding. Do not reuse the dedicated device's 1/2 values here.
        int val = folded ? 1 : 0;

        // Capability probe — ADVISORY ONLY, deliberately NOT a gate.
        //
        // It would be tempting to bail out when this reads 0 ("no power-folding mirrors"),
        // but a 0 here is not trustworthy enough to block the actuator: the named getter is
        // absent on many trims, and the feature-id read is CROSS-FAMILY (0x4070001A is a
        // Mirror-namespace id being read off the bodywork device, whose deviceType
        // callGetSingle derives from the device itself) — a HAL that doesn't recognise the
        // pair can answer 0 for "no data" rather than "not fitted", indistinguishable from a
        // real negative. Gating on that would newly break fold on cars where it works.
        // So we only LOG it: combined with the per-path outcome logs below, one device run
        // still tells us whether the trim lacks the actuator or the HAL refused the write.
        Integer capable = readMirrorAutoFoldCapability();
        if (capable != null) {
            logger.info("setMirrorsFolded: HAVE_REARVIEW_MIRROR_AUTO_FOLD=" + capable
                    + (capable == 0 ? " (trim may not have power-folding mirrors —"
                                    + " attempting anyway, see per-path results below)" : ""));
        }
        if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
            return false;
        }

        // Path 1 — the OEM's own call: BYDAutoBodyworkDevice.setMirrorFoldState(int).
        // This is exactly what the reference app does, and now its result is CHECKED.
        //
        // Three distinguishable outcomes, and the distinction matters: an explicit 0/true is
        // a real ACCEPT; an explicit failure code is a real REFUSE; a void/null return is NO
        // SIGNAL. The old code collapsed all three into "success", which is precisely how a
        // refusal became a false confirmation. A no-signal result must NOT short-circuit —
        // we fall through to path 2, which does return a code, and only claim success on a
        // no-signal call if nothing else produced a definite answer.
        boolean firedWithoutSignal = false;
        if (bodyworkDevice != null) {
            try {
                Method m = bodyworkDevice.getClass().getMethod("setMirrorFoldState", int.class);
                if (folded && drivingSafetyBlocked(
                        DrivingSafetyGuard.GUARD_MIRROR_FOLD)) return false;
                Object r = m.invoke(bodyworkDevice, val);
                if (r instanceof Integer) {
                    boolean ok = ((Integer) r) == 0;   // BODYWORK_COMMAND_SUCCESS
                    logger.info("setMirrorsFolded: setMirrorFoldState(" + val + ") -> code=" + r
                            + (ok ? " ACCEPTED" : " REFUSED"));
                    if (ok) return true;
                } else if (r instanceof Boolean) {
                    boolean ok = (Boolean) r;
                    logger.info("setMirrorsFolded: setMirrorFoldState(" + val + ") -> " + r
                            + (ok ? " ACCEPTED" : " REFUSED"));
                    if (ok) return true;
                } else {
                    // Declared void on this trim — it returned without throwing, but that is
                    // NOT evidence the mirrors moved.
                    firedWithoutSignal = true;
                    logger.info("setMirrorsFolded: setMirrorFoldState(" + val
                            + ") returned no value (void) — no accept/refuse signal");
                }
            } catch (NoSuchMethodException nsme) {
                logger.info("setMirrorsFolded: setMirrorFoldState absent on bodywork device");
            } catch (Exception e) {
                logger.debug("setMirrorsFolded setMirrorFoldState failed: " + e.getMessage());
            }
        }

        // Path 2 — legacy compatibility: call the protected bodywork base-class setter directly
        // with BODYWORK_REARVIEW_MIRROR_SET (0x4EF32010). Newer-device SDK wrappers use this
        // three-int base setter. Unlike the public event-value API below, this path is not rejected
        // merely because the bodywork device's advertised feature list omits the command.
        //
        // SKIPPED when path 1 already fired without a signal. Two reasons, both concrete:
        //  * If that void call DID move the mirrors, this second write lands mid-travel and
        //    the HAL answers BODYWORK_COMMAND_BUSY — which we would log as "REFUSED" on a car
        //    where fold actually worked. This method's whole purpose is to make one device run
        //    conclusive, so a self-inflicted misleading log is the worst possible outcome.
        //  * It also avoids commanding the same actuator twice per press for no benefit (the
        //    value is identical, so it is not a fold/unfold conflict, just pointless).
        // A trim that reports a real code from path 1 (ACCEPT or REFUSE) still reaches here on
        // refusal, which is exactly where a genuine second candidate is worth trying.
        if (bodyworkDevice != null && !firedWithoutSignal) {
            if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
                return false;
            }
            try {
                int code = BydDeviceHelper.callSetSingle(
                        bodyworkDevice, BydFeatureIds.MIRROR_REARVIEW_SET, val);
                boolean ok = isBodyworkCommandSuccess(code);
                logger.info("setMirrorsFolded: direct bodywork set("
                        + "BODYWORK_REARVIEW_MIRROR_SET, " + val + ") -> code=" + code
                        + (ok ? " ACCEPTED" : " REFUSED"));
                if (ok) return true;
            } catch (Exception e) {
                logger.debug("setMirrorsFolded direct bodywork write failed: " + e.getMessage());
            }
        }

        // Path 3 — retain the standard public event-value API for firmware that advertises the
        // mirror command through its feature list but does not expose the protected base setter.
        if (bodyworkDevice != null && !firedWithoutSignal) {
            if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
                return false;
            }
            try {
                int code = BydDeviceHelper.sendSetCommandRaw(
                        bodyworkDevice, BydFeatureIds.MIRROR_REARVIEW_SET, val);
                boolean ok = isBodyworkCommandSuccess(code);
                logger.info("setMirrorsFolded: public bodywork event("
                        + "BODYWORK_REARVIEW_MIRROR_SET, " + val + ") -> code=" + code
                        + (ok ? " ACCEPTED" : " REFUSED"));
                if (ok) return true;
            } catch (Exception e) {
                logger.debug("setMirrorsFolded public bodywork event failed: " + e.getMessage());
            }
        }

        // Do not substitute the named setting methods here. setRearViewMirrorFlip controls
        // reverse dip, while setAutoExternalRearMirrorFollowUpSwitch controls the persistent
        // automatic-fold preference. The manual actuator is the raw Setting feature attempted
        // before these legacy bodywork paths.

        // No daemon path returned a definite accept. Last resort, ALWAYS attempted from here:
        // run the same write from the REAL app process (UID 10xxx, real Context) via the
        // app-process actuator, in case this HAL only honours it from a normal app process.
        // Fire-and-forget — the service logs its own outcome under the VehicleActuator tag.
        // Exactly one dispatch per call (this is the only dispatch site; every accepting path
        // above returned before reaching it).
        if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
            return false;
        }
        try {
            com.overdrive.app.byd.VehicleActuatorBridge.dispatchMirror(folded);
        } catch (Throwable t) {
            logger.debug("setMirrorsFolded app-process dispatch failed: " + t.getMessage());
        }

        if (firedWithoutSignal) {
            // The OEM's own named call went through and returned nothing to contradict it.
            // There is no better evidence available for a void API, so report it as sent —
            // this is the single case where the old accept-on-no-throw behaviour was
            // defensible. The per-path logs above identify this case exactly.
            logger.info("setMirrorsFolded: reporting success on the void setMirrorFoldState "
                    + "call (no accept/refuse signal available); app-process attempt also sent");
            return true;
        }

        // Every path that CAN report a result reported refusal (or was unavailable). Say so:
        // a false confirmation here is what made this look like a working feature.
        logger.warn("setMirrorsFolded: no daemon path accepted the write (fold=" + folded
                + "); app-process attempt dispatched — see VehicleActuator log");
        return false;
    }

    private static boolean drivingSafetyBlocked(String guardKey) {
        try {
            return DrivingSafetyGuard.isActionBlocked(guardKey);
        } catch (Throwable unavailable) {
            return true;
        }
    }

    /** Use the connected model's write-profile Setting command before legacy bodywork APIs. */
    private boolean setMirrorsFoldedOnSettingDevice(int value, boolean folded) {
        Context bydContext = BydDeviceHelper.withBydPermissionBypass(context);
        Object device = settingDevice;
        if (device == null && bydContext != null) {
            device = BydDeviceHelper.getDevice(
                    BydConstants.MIRROR_FOLD_SETTING_DEVICE_CLASS, bydContext);
            if (device != null) settingDevice = device;
        }

        int code = Integer.MIN_VALUE;
        if (device != null) {
            // Match the OEM DiCar HalSetter first: BYDAutoSettingDevice.set(int[], EventValue).
            if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
                return false;
            }
            code = BydDeviceHelper.sendSetCommandRaw(
                    device, BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, value);
            boolean accepted = isBodyworkCommandSuccess(code);
            logger.info("setMirrorsFolded: setting event("
                    + "device=1023, SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, " + value
                    + ") -> code="
                    + code + (accepted ? " ACCEPTED" : " REFUSED"));
            if (accepted) {
                logManualMirrorReadback(value);
                return true;
            }

            // Some SDK builds reject unadvertised feature ids in the public set(...) wrapper.
            // Its protected base setter is the same manager-level write without that local list
            // validation, so retain it as the second form of the same Setting command.
            if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
                return false;
            }
            code = BydDeviceHelper.callSetSingle(
                    device, BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, value);
            accepted = isBodyworkCommandSuccess(code);
            logger.info("setMirrorsFolded: direct setting set("
                    + "device=1023, SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, " + value
                    + ") -> code="
                    + code + (accepted ? " ACCEPTED" : " REFUSED"));
            if (accepted) {
                logManualMirrorReadback(value);
                return true;
            }
        }

        // callSetSingle is already manager.setInt. Use the manager directly only when no singleton
        // exists or reflection could not reach the inherited protected setter.
        if ((device == null || code == -1 || code == Integer.MIN_VALUE) && bydContext != null) {
            if (folded && drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)) {
                return false;
            }
            int managerCode = BydDeviceHelper.callManagerSetInt(
                    bydContext,
                    BydConstants.MIRROR_FOLD_SETTING_DEVICE_TYPE,
                    BydFeatureIds.SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET,
                    value);
            boolean accepted = isBodyworkCommandSuccess(managerCode);
            logger.info("setMirrorsFolded: setting manager set("
                    + "device=1023, SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET, " + value
                    + ") -> code="
                    + managerCode + (accepted ? " ACCEPTED" : " REFUSED"));
            if (accepted) {
                logManualMirrorReadback(value);
                return true;
            }
        }
        return false;
    }

    private void logManualMirrorReadback(int expected) {
        Object stateDevice = rearViewMirrorDevice;
        if (stateDevice == null) {
            Context bydContext = BydDeviceHelper.withBydPermissionBypass(context);
            stateDevice = BydDeviceHelper.getDevice(
                    BydConstants.REAR_VIEW_MIRROR_DEVICE_CLASS, bydContext);
            if (stateDevice != null) rearViewMirrorDevice = stateDevice;
        }
        Object state = BydDeviceHelper.callGetter(
                stateDevice, "getAutoExternalRearMirrorState");
        if (state instanceof Number) {
            logger.info("setMirrorsFolded: mirror state="
                    + ((Number) state).intValue() + " expectedCommand=" + expected);
        } else {
            logger.info("setMirrorsFolded: mirror state unavailable after accepted write");
        }
    }

    /**
     * Enable or disable the OEM's persistent exterior-mirror follow-up setting.
     *
     * <p>This is intentionally distinct from {@link #setMirrorsFolded(boolean)}. The latter is
     * an immediate mirror actuator and is not available once the car is off on this platform.
     * The setting device exposes {@code setAutoExternalRearMirrorFollowUpSwitch(int)} as an
     * on/off preference with a documented result code and readback. Once enabled, the vehicle
     * itself owns folding and unfolding across its power lifecycle.
     *
     * <p>Some firmware accepts settings only from a device handle created with a real system
     * context. Try the collector's normal handle first, then its system-context setting handle,
     * and finally the app-process actuator before reporting failure. Each synchronous handle
     * verifies the matching getter when it is available, so an accepted-but-ignored setting
     * write can progress to the next context. This does not fall back to
     * {@code auto_mirror_for_lock}: that provider key is a separate lock-trigger preference and
     * must not be substituted for power-state mirror behavior.
     */
    public boolean setAutoExternalRearMirrorFollowUp(boolean enabled) {
        final int value = enabled ? 1 : 0; // BYDAutoSettingDevice.SET_ON / SET_OFF
        if (setAutoExternalRearMirrorFollowUpOn(settingDevice, value, "daemon")) return true;

        Object systemDevice = getSystemContextSettingDevice();
        if (systemDevice != null && systemDevice != settingDevice
                && setAutoExternalRearMirrorFollowUpOn(systemDevice, value, "system-context")) {
            return true;
        }

        // Last retry from the real app process. The dispatch is asynchronous, so it cannot
        // honestly turn this synchronous command into SUCCESS; its own verified result is logged
        // by VehicleActuatorService. This is still worth trying because the OEM setting HAL can
        // bind a write to the caller's Context/package.
        try {
            com.overdrive.app.byd.VehicleActuatorBridge
                    .dispatchAutoExternalRearMirrorFollowUp(enabled);
        } catch (Throwable t) {
            logger.debug("setAutoExternalRearMirrorFollowUp app-process dispatch failed: "
                    + t.getMessage());
        }
        logger.warn("setAutoExternalRearMirrorFollowUp(" + enabled
                + "): no daemon setting write was confirmed; app-process retry dispatched");
        return false;
    }

    /** Invoke the OEM mirror follow-up setter on one setting-device handle. */
    private boolean setAutoExternalRearMirrorFollowUpOn(Object device, int value, String source) {
        if (device == null) {
            logger.debug("setAutoExternalRearMirrorFollowUp: " + source
                    + " setting device unavailable");
            return false;
        }
        try {
            Method method = device.getClass().getMethod(
                    "setAutoExternalRearMirrorFollowUpSwitch", int.class);
            Object result = method.invoke(device, value);
            boolean accepted = isSdkWriteSuccess(
                    device, result, "setAutoExternalRearMirrorFollowUpSwitch");
            logger.info("setAutoExternalRearMirrorFollowUpSwitch(" + value + ") via " + source
                    + " -> " + result + (accepted ? " ACCEPTED" : " REFUSED"));
            if (!accepted) return false;

            // The setter's documented companion getter lets us distinguish an accepted result
            // from a context-bound write that the HAL silently ignores. An absent getter is not
            // treated as refusal: the setter is present and its explicit success result remains
            // the best available evidence on that firmware.
            Object readback = BydDeviceHelper.callGetter(
                    device, "getAutoExternalRearMirrorFollowUpSwitch");
            if (readback instanceof Number) {
                int actual = ((Number) readback).intValue();
                if (actual != value) {
                    logger.warn("setAutoExternalRearMirrorFollowUpSwitch(" + value + ") via "
                            + source + " read back " + actual + " — retrying another context");
                    return false;
                }
                logger.info("setAutoExternalRearMirrorFollowUpSwitch(" + value + ") via "
                        + source + " CONFIRMED");
            } else {
                logger.info("setAutoExternalRearMirrorFollowUpSwitch(" + value + ") via "
                        + source + " accepted; getter unavailable for confirmation");
            }
            return true;
        } catch (NoSuchMethodException e) {
            logger.info("setAutoExternalRearMirrorFollowUpSwitch absent on "
                    + device.getClass().getSimpleName() + " (" + source + ")");
            return false;
        } catch (Exception e) {
            logger.warn("setAutoExternalRearMirrorFollowUpSwitch(" + value + ") via "
                    + source + " failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Whether this trim has power-folding mirrors, via the OEM's named
     * {@code hasMirrorAutoFoldCapability()} getter on the bodywork device — the same probe the
     * reference app makes before offering the control. Returns 1/0 when the trim answers, or
     * null when the getter is absent (the common case), meaning UNKNOWN.
     *
     * <p>Deliberately does NOT fall back to a {@code MIRROR_HAVE_AUTO_FOLD} (0x4070001A)
     * feature-id read. That id is in a SETTING-family block (its only sibling in this repo is
     * {@code Setting.SET_REMOTE_CONTROL_UNLOCKING_SET} = 0x40700016), so reading it off the
     * bodywork device — whose deviceType {@code callGetSingle} derives from the device itself —
     * forms a (bodyworkDeviceType, setting-id) pair the HAL does not recognise. It would answer
     * 0 or -1 on essentially every car, and a 0 logged as "may not have power-folding mirrors"
     * would systematically mis-diagnose cars whose mirrors fold perfectly well. Since this
     * probe exists purely to make a device run conclusive, a leg that reliably lies is worse
     * than no leg at all.
     */
    private Integer readMirrorAutoFoldCapability() {
        if (bodyworkDevice == null) return null;
        try {
            Object r = BydDeviceHelper.callGetter(bodyworkDevice, "hasMirrorAutoFoldCapability");
            if (r instanceof Boolean) return ((Boolean) r) ? 1 : 0;
            if (r instanceof Integer) return (Integer) r;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * The OEM's success predicate for a bodywork write: {@code BODYWORK_COMMAND_SUCCESS} is 0
     * and every documented failure ({@code _FAILED} -2147482648, {@code _BUSY},
     * {@code _TIMEOUT}, {@code _INVALID_VALUE}) is a large negative. The reference app's own
     * {@code interpret()} tests equality with 0, so a non-zero code is NOT success — the
     * generic {@code sendSetCommand}'s looser {@code code >= 0} is what let refused mirror
     * writes read as accepted. {@link Integer#MIN_VALUE} is sendSetCommandRaw's
     * "threw before producing a code" sentinel and is correctly rejected here.
     *
     * <p>Caveat this deliberately accepts: {@code sendSetCommandRaw} also returns 0 from its
     * "non-null result, assume success" branch when the HAL's {@code set()} is declared
     * {@code void}. That is the same no-signal case the named-method path handles explicitly,
     * and there is no better evidence available for a void API — so a 0 here means "accepted,
     * or returned nothing to contradict it". The per-path log records the raw code, so a
     * device run still shows which of the two it was.
     */
    private static boolean isBodyworkCommandSuccess(int code) {
        return code == 0;
    }

    /**
     * Child lock on/off for one rear door. Per the OEM SDK this is a dedicated
     * {@code setChildLockState(int area, int enable)} reflection method on the BODYWORK
     * device (area = left?1:2, enable = 1/0) — NOT the feature-id write to a doorLock
     * device the old impl used (the doorlock HAL didn't accept it → silent no-op). Falls
     * back to the old feature-id path only if the named method is absent on this trim.
     */
    public boolean setChildLock(boolean left, boolean enable) {
        int area = left ? 1 : 2;
        int val = enable ? 1 : 0;
        if (bodyworkDevice != null) {
            try {
                Method m = bodyworkDevice.getClass().getMethod("setChildLockState", int.class, int.class);
                Object r = m.invoke(bodyworkDevice, area, val);
                return isSdkWriteSuccess(bodyworkDevice, r, "setChildLockState");
            } catch (NoSuchMethodException nsme) {
                logger.info("setChildLockState absent — falling back to doorlock feature-id write");
            } catch (Exception e) {
                logger.debug("setChildLockState failed: " + e.getMessage());
                return false;
            }
        }
        // Legacy fallback (older SDK): feature-id write on the doorlock device.
        try {
            int featureId = left ? BydFeatureIds.DOORLOCK_CHILDLOCK_LEFT_SET : BydFeatureIds.DOORLOCK_CHILDLOCK_RIGHT_SET;
            return BydDeviceHelper.sendSetCommand(doorLockDevice, featureId, val);
        } catch (Exception e) {
            logger.debug("setChildLock fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Turn the phone wireless charging pad on/off. The feature id matches the OEM SDK,
     * but the OFF value convention is NOT trustworthy across trims: several sibling BYD
     * toggles (iTAC, CPD) documented that the HAL IGNORES {@code 0} and the real "off"
     * is {@code 2}, so a plain {@code enabled?1:0} write can leave the pad ON when the
     * user asked OFF (the reported "on/off doesn't work"). So we write {@code 1/0},
     * READ BACK via {@link #getWirelessChargingState()}, and if the car didn't reach the
     * requested state, retry with the {@code 1/2} convention. Returns true once a
     * readback confirms the requested state (or, if no readback is available on this
     * trim, when the primary write's SDK code was success — best effort).
     */
    public boolean setWirelessCharging(boolean enabled) {
        boolean wrote = writeWirelessCharging(enabled ? 1 : 0);
        Boolean now = readWirelessOn();
        if (now != null) {
            if (now == enabled) return true;               // confirmed
            // Off wasn't honoured by the 0 convention (or on didn't take) — retry with
            // the 1/2 convention that iTAC/CPD proved is the real one on some trims.
            logger.warn("setWirelessCharging: readback=" + now + " after 1/0 write for enabled="
                    + enabled + " — retrying 1/2 convention");
            boolean fallbackWrote = writeWirelessCharging(enabled ? 1 : 2);
            Boolean after = readWirelessOn();
            return after == null ? fallbackWrote : after == enabled;
        }
        return wrote; // no readback on this trim — report the primary write
    }

    /** One wireless-charging switch write; true iff the SDK reports success. */
    private boolean writeWirelessCharging(int value) {
        try {
            return BydDeviceHelper.sendSetCommand(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_SWITCH_SET, value);
        } catch (Exception e) {
            logger.debug("writeWirelessCharging(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    // Wireless-charging pads. Two independent pads on trims that have them; a single pad
    // otherwise (the global setWirelessCharging above still drives that case).
    public static final int WIRELESS_PAD_LEFT = 0;
    public static final int WIRELESS_PAD_RIGHT = 1;

    static int wirelessPadPrimaryCode(int pad, boolean enabled) {
        if (pad == WIRELESS_PAD_LEFT) return enabled ? 1 : 2;
        if (pad == WIRELESS_PAD_RIGHT) return enabled ? 4 : 5;
        throw new IllegalArgumentException("invalid wireless pad " + pad);
    }

    /** OEM fallback exists only for the right pad. */
    static int wirelessPadFallbackFeatureId(int pad) {
        if (pad == WIRELESS_PAD_RIGHT) return BydFeatureIds.CHARGING_WIRELESS_RIGHT_SWITCH_DIRECT;
        throw new IllegalArgumentException("no OEM fallback for wireless pad " + pad);
    }

    static int wirelessPadFallbackValue(int pad, boolean enabled) {
        if (pad == WIRELESS_PAD_RIGHT) return enabled ? 1 : 2;
        throw new IllegalArgumentException("no OEM fallback for wireless pad " + pad);
    }

    enum WirelessPrimaryOutcome {
        ACCEPTED,
        REJECTED,
        ABSENT,
        ERROR
    }

    static boolean shouldFallbackWirelessPad(int pad, WirelessPrimaryOutcome primary) {
        // The OEM right-pad fallback runs only after the named method explicitly returned a
        // negative Integer. Null/non-Integer results, missing/throwing methods, and all left-pad
        // failures are returned directly.
        return pad == WIRELESS_PAD_RIGHT && primary == WirelessPrimaryOutcome.REJECTED;
    }

    static boolean isOemWirelessResultAccepted(Object result) {
        return result instanceof Integer && ((Integer) result).intValue() >= 0;
    }

    /**
     * Turn ONE wireless-charging pad on/off.
     *
     * <p>Primary path is the OEM's own {@code BYDAutoChargingDevice.setWirelessChargingSwitchState(int)},
     * which encodes the pad AND the state in one code — proven from the reference implementation:
     * <b>left on=1, left off=2, right on=4, right off=5</b>. The OEM has no left-pad fallback.
     * For the right pad, it falls back only when an invoked primary explicitly returns a negative
     * Integer, using {@code WIRELESS_CHARGING_RIGHT_SWITCH_DIRECT} with 1/2. A null/non-Integer,
     * missing, or throwing primary returns failure directly.
     *
     * @param pad {@link #WIRELESS_PAD_LEFT} or {@link #WIRELESS_PAD_RIGHT}
     */
    public boolean setWirelessChargingPad(int pad, boolean enabled) {
        if (pad != WIRELESS_PAD_LEFT && pad != WIRELESS_PAD_RIGHT) {
            logger.warn("setWirelessChargingPad: invalid pad " + pad);
            return false;
        }
        int code = wirelessPadPrimaryCode(pad, enabled);
        WirelessPrimaryOutcome primary = tryWirelessSwitchState(code);
        logger.info("wireless pad " + pad + " primary code=" + code + " outcome="
                + primary);
        if (primary == WirelessPrimaryOutcome.ACCEPTED) {
            // Any nonnegative OEM result is accepted (including success codes 0 and 2). The
            // readback is only a diagnostic: the *_STATE registers report CHARGING activity
            // (1 = a phone is charging), so an enabled-but-empty pad reads non-1, and treating that
            // as failure would report a genuine enable as failed. Log the discrepancy, don't act.
            logReadbackDiscrepancy(
                    "wireless pad " + pad, enabled, readWirelessPadOn(pad), true);
            return true;
        }
        if (!shouldFallbackWirelessPad(pad, primary)) {
            logReadbackDiscrepancy(
                    "wireless pad " + pad, enabled, readWirelessPadOn(pad), false);
            return false;
        }

        int featureId = wirelessPadFallbackFeatureId(pad);
        int value = wirelessPadFallbackValue(pad, enabled);
        Integer rawResult =
                BydDeviceHelper.sendSetCommandIntegerResult(chargingDevice, featureId, value);
        boolean wrote = isOemWirelessResultAccepted(rawResult);
        logger.info("wireless pad " + pad + " fallback featureId=0x"
                + Integer.toHexString(featureId) + " value=" + value + " rawResult=" + rawResult
                + " accepted=" + wrote);
        logReadbackDiscrepancy(
                "wireless pad " + pad + " (feature-id)", enabled, readWirelessPadOn(pad), wrote);
        return wrote;
    }

    /** Log when a pad's charging-activity readback disagrees with what we just commanded — a hint
     *  for a device run, never a gate (the readback reflects charging, not switch position). */
    private void logReadbackDiscrepancy(String what, boolean commanded, Boolean readback, boolean accepted) {
        if (readback != null && readback.booleanValue() != commanded) {
            logger.info(what + ": commanded " + (commanded ? "on" : "off") + " accepted=" + accepted
                    + " but activity readback=" + (readback ? "charging" : "idle/off")
                    + " (advisory — an enabled empty pad reads idle)");
        }
    }

    /**
     * Invoke {@code setWirelessChargingSwitchState(code)} with the OEM's exact result judge.
     * Returns an explicit outcome so a returned rejection cannot be confused with an unavailable
     * or throwing method. A nonnegative Integer is accepted, a negative Integer is rejected, and
     * null/non-Integer results are errors that must not trigger the feature-ID fallback.
     *
     * <p>Reflects directly rather than via {@link BydDeviceHelper#callMethod} because that helper
     * collapses a void return into null and cannot distinguish it from an unavailable method. The
     * OEM accepts every nonnegative Integer, including both charging success codes 0 and 2.
     */
    private WirelessPrimaryOutcome tryWirelessSwitchState(int code) {
        if (chargingDevice == null) return WirelessPrimaryOutcome.ABSENT;
        final Method method;
        try {
            method = chargingDevice.getClass()
                    .getMethod("setWirelessChargingSwitchState", int.class);
        } catch (NoSuchMethodException e) {
            return WirelessPrimaryOutcome.ABSENT;
        } catch (Exception e) {
            logger.debug("setWirelessChargingSwitchState lookup failed: " + e.getMessage());
            return WirelessPrimaryOutcome.ERROR;
        }
        try {
            Object result = method.invoke(chargingDevice, code);
            if (!(result instanceof Integer)) {
                logger.warn("setWirelessChargingSwitchState(" + code
                        + ") returned unexpected result type: "
                        + (result == null ? "null" : result.getClass().getName()));
                return WirelessPrimaryOutcome.ERROR;
            }
            boolean accepted = ((Integer) result).intValue() >= 0;
            logger.info("setWirelessChargingSwitchState(" + code + ") rawResult=" + result
                    + " accepted=" + accepted);
            return accepted
                    ? WirelessPrimaryOutcome.ACCEPTED : WirelessPrimaryOutcome.REJECTED;
        } catch (Exception e) {
            logger.warn("setWirelessChargingSwitchState(" + code + ") failed: " + e.getMessage());
            return WirelessPrimaryOutcome.ERROR;
        }
    }

    /**
     * Invoke a named int-arg setter on the charging device, distinguishing "absent" from
     * "refused" for a caller that has a fallback path. Returns:
     * <ul>
     *   <li>{@code null} — the method is absent on this trim (or the device is null): the caller
     *       should fall back to another mechanism;</li>
     *   <li>{@code TRUE} — accepted: a {@code Boolean.TRUE}, a void/non-Integer return (the invoke
     *       did not throw), or an Integer {@code >= 0};</li>
     *   <li>{@code FALSE} — refused: a {@code Boolean.FALSE}, an Integer {@code < 0}, or a throwing
     *       invoke. The caller decides whether a refusal should trigger another mechanism.</li>
     * </ul>
     *
     * <p>Reflects directly rather than via {@link BydDeviceHelper#callMethod}, which collapses a
     * VOID return into null and so cannot tell "absent" from "returned void" — a distinction that
     * matters because some of these setters are void on some trims.
     *
     * <p>The Integer bar is {@code >= 0}, not {@code == 0}: the charging family has TWO success
     * codes ({@code CHARGING_COMMAND_SUCCESS = 0} and {@code CHARGING_SUCCESS = 2}) while every
     * failure is a large negative, so {@code == 0} would reject a genuine {@code 2} accept. The
     * {@code Boolean.FALSE} case is honoured explicitly — the canonical {@link #isSdkWriteSuccess}
     * and {@link BydDeviceHelper#sendSetCommandRaw} both treat a Boolean false as failure, and a
     * hand-rolled judge that omitted it would report a refusal as success.
     */
    private Boolean invokeChargingSetter(String methodName, int arg) {
        if (chargingDevice == null) return null;
        java.lang.reflect.Method m;
        try {
            m = chargingDevice.getClass().getMethod(methodName, int.class);
        } catch (NoSuchMethodException nsme) {
            return null;   // absent on this trim → caller falls back
        } catch (Exception e) {
            logger.debug(methodName + " lookup failed: " + e.getMessage());
            return null;
        }
        try {
            Object result = m.invoke(chargingDevice, arg);
            boolean accepted;
            if (result instanceof Boolean) {
                accepted = (Boolean) result;
            } else if (result instanceof Integer) {
                accepted = ((Integer) result).intValue() >= 0;
            } else {
                accepted = true;   // void / non-Integer, and the invoke didn't throw
            }
            logger.info(methodName + "(" + arg + ") result=" + result + " accepted=" + accepted);
            return Boolean.valueOf(accepted);
        } catch (Exception e) {
            logger.warn(methodName + "(" + arg + ") failed: " + e.getMessage());
            return Boolean.FALSE;   // present but threw → a real failure, NOT a fall-back trigger
        }
    }

    /** One pad's state as a boolean, or null if unreadable. */
    private Boolean readWirelessPadOn(int pad) {
        try {
            int id = (pad == WIRELESS_PAD_LEFT)
                    ? BydFeatureIds.CHARGING_WIRELESS_LEFT_STATE
                    : BydFeatureIds.CHARGING_WIRELESS_RIGHT_STATE;
            Object v = BydDeviceHelper.callGet(chargingDevice, id, Integer.class);
            if (v == null) return null;
            int raw = BydDeviceHelper.getIntValue(v);
            if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE || raw < 0) return null;
            return raw == 1;
        } catch (Exception e) {
            logger.debug("readWirelessPadOn(" + pad + ") failed: " + e.getMessage());
            return null;
        }
    }

    /** Wireless pad state as a boolean (true=on/false=off), or null if unreadable. */
    private Boolean readWirelessOn() {
        int raw = getWirelessChargingState();
        // Combined status: 1 = on/charging, 0 = off. (2 also appears as "off/idle" on
        // some trims — treat anything non-1 as off.) -1/sentinel → can't verify.
        if (raw < 0) return null;
        return raw == 1;
    }

    /** Raw combined wireless-charging state (CHARGING_WIRELESS_STATE), or -1 if unreadable. */
    public int getWirelessChargingState() {
        if (chargingDevice == null) return -1;
        try {
            Object v = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_STATE, Integer.class);
            if (v == null) return -1;
            int raw = BydDeviceHelper.getIntValue(v);
            if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE) return -1;
            return raw;
        } catch (Exception e) {
            logger.debug("getWirelessChargingState failed: " + e.getMessage());
            return -1;
        }
    }

    public boolean wakeUpMcu() {
        try {
            Object result = BydDeviceHelper.callGetter(powerDevice, "wakeUpMcu");
            return result instanceof Number && ((Number) result).intValue() >= 0;
        } catch (Exception e) {
            logger.debug("wakeUpMcu failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set the centre infotainment panel orientation using this unit's documented setting-device
     * API: 1=horizontal, 2=vertical.
     */
    public boolean setPadRotation(int rotation) {
        if (rotation != PAD_ROTATION_HORIZONTAL && rotation != PAD_ROTATION_VERTICAL) {
            logger.warn("setPadRotation: invalid rotation " + rotation);
            return false;
        }
        if (settingDevice == null) {
            logger.warn("setPadRotation: settingDevice unavailable");
            return false;
        }
        try {
            Method method = settingDevice.getClass().getMethod("setPadRotation", int.class);
            Object result = method.invoke(settingDevice, rotation);
            boolean accepted = isSdkWriteSuccess(settingDevice, result, "setPadRotation");
            logger.info("setPadRotation(" + rotation + ") result=" + result
                    + " accepted=" + accepted);
            return accepted;
        } catch (NoSuchMethodException e) {
            // Older SDK wrappers may expose only the feature-id route.
            return BydDeviceHelper.sendSetCommand(
                    settingDevice, BydFeatureIds.SETTING_PAD_ROTATION_SET, rotation);
        } catch (Exception e) {
            logger.warn("setPadRotation(" + rotation + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Legacy caller compatibility; horizontal was the old hard-coded behavior. */
    @Deprecated
    public boolean rotatePad() {
        return setPadRotation(PAD_ROTATION_HORIZONTAL);
    }

    /**
     * Switch the view shown by the OEM native panorama camera application.
     *
     * <p>This only sends the OEM {@code AUTO_VIDEO_BUTTON} view command; it never opens
     * the panorama application. Key mapping separately verifies that the native camera
     * window is active before consuming a physical key. It does not touch OverDrive's
     * AVM capture/viewpoint pipeline.
     */
    public boolean setNativeCameraView(int viewCode) {
        if (!isNativeCameraViewCode(viewCode)) {
            logger.warn("setNativeCameraView: invalid view code " + viewCode);
            return false;
        }
        Context currentContext = context;
        if (currentContext == null) {
            logger.warn("setNativeCameraView: context unavailable");
            return false;
        }
        return NativeCameraViewController.show(currentContext, viewCode);
    }

    static boolean isNativeCameraViewCode(int viewCode) {
        switch (viewCode) {
            case NATIVE_CAMERA_VIEW_FRONT:
            case NATIVE_CAMERA_VIEW_REAR:
            case NATIVE_CAMERA_VIEW_LEFT:
            case NATIVE_CAMERA_VIEW_RIGHT:
            case NATIVE_CAMERA_VIEW_FRONT_WIDE:
            case NATIVE_CAMERA_VIEW_REAR_WIDE:
            case NATIVE_CAMERA_VIEW_LEFT_RIGHT:
                return true;
            default:
                return false;
        }
    }

    /**
     * Drift mode on/off. Per the OEM SDK this is a dedicated {@code setDriftModeState(int)}
     * reflection method on the SETTING device (on=1/off=0) — NOT the engine-device
     * feature-id write the old dead impl used (wrong device AND wrong mechanism). Probed
     * by name so an SDK rename surfaces at WARN rather than writing to a dead path.
     * (Currently no caller wires this; kept correct so a future drive-mode action can.)
     */
    public boolean setDriftMode(boolean enabled) {
        if (settingDevice == null) {
            logger.warn("setDriftMode: settingDevice unavailable");
            return false;
        }
        try {
            Method m = settingDevice.getClass().getMethod("setDriftModeState", int.class);
            Object r = m.invoke(settingDevice, enabled ? 1 : 0);
            return isSdkWriteSuccess(settingDevice, r, "setDriftModeState");
        } catch (NoSuchMethodException nsme) {
            logger.warn("setDriftModeState: not present on this OEM build");
            return false;
        } catch (Exception e) {
            logger.debug("setDriftMode failed: " + e.getMessage());
            return false;
        }
    }

    // --- Drive / energy modes ---
    // energy-feedback / steer-assist are written via BYDAutoSettingDevice SDK
    // setters (HAL device methods, not the CarSettings ContentProvider; SDK
    // convention: setter returns 0 on success). BYDAutoEnergyDevice exposes both
    // get{Operation,Energy}Mode (see collectEnergy) AND the matching setters
    // set{Operation,Energy}Mode(int) — confirmed present in the OEM implementation
    // and invoked below via invokeOptionalModeSetter, which surfaces a genuinely
    // absent method at WARN rather than a silent false. The writes are still gated
    // by the BYD signature-permission wall (the HAL may reject from our UID), so a
    // non-zero result is treated as failure by isSdkWriteSuccess.

    /** Drive/operation mode using the public Energy enum: economy=1, sport=2, normal=3, snow/rain=4. */
    public boolean setOperationMode(int mode) {
        if (!invokeModeSetterForReadback(energyDevice, "setOperationMode", mode)) return false;

        long deadline = SystemClock.elapsedRealtime() + DRIVE_MODE_APPLY_TIMEOUT_MS;
        boolean answered = false;
        int raw = -1;
        int surface = -1;
        int target = -1;
        do {
            Object value = BydDeviceHelper.callGetter(energyDevice, "getOperationMode");
            if (value instanceof Number) {
                answered = true;
                raw = ((Number) value).intValue();
                surface = readRoadSurfaceMode(energyDevice);
                target = raw == 1 && surface != 2
                        ? readTargetDrivingMode(settingDevice) : -1;
                if (operationModeMatchesSetter(mode, raw, surface, target)) {
                    if (mode == 3 && raw == 1 && surface != 2 && target < 1) {
                        logger.info("setOperationMode(3) accepted on shared Normal/Eco readback");
                    }
                    return true;
                }
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                Thread.sleep(Math.min(DRIVE_MODE_APPLY_POLL_MS, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (SystemClock.elapsedRealtime() < deadline);

        if (!answered) {
            logger.warn("setOperationMode(" + mode
                    + ") accepted but operation readback is unavailable");
            return false;
        }
        logger.warn("setOperationMode(" + mode + ") accepted but readback stayed operation="
                + raw + " surface=" + surface + " target=" + target);
        return false;
    }

    private static final long DRIVE_MODE_APPLY_TIMEOUT_MS = 1500L;
    private static final long DRIVE_MODE_APPLY_POLL_MS = 50L;

    /** Public setter enum -> the same normalized live axis used by triggers. */
    static boolean operationModeMatchesSetter(
            int setterMode,
            int rawOperationMode,
            int roadSurfaceMode,
            int targetDrivingMode) {
        // This head unit reports the same live operation value for Normal and Eco. A successful
        // Normal setter followed by that shared value is the strongest acknowledgement available.
        if (setterMode == 3
                && rawOperationMode == 1
                && roadSurfaceMode != 2
                && targetDrivingMode < 1) {
            return true;
        }
        int expectedConfigMode;
        switch (setterMode) {
            case 1: expectedConfigMode = 2; break; // ECO
            case 2: expectedConfigMode = 3; break; // SPORT
            case 3: expectedConfigMode = 1; break; // NORMAL
            case 4: expectedConfigMode = 4; break; // SNOW
            default: return false;
        }
        return driveModeFromEnergyAxis(
                rawOperationMode, roadSurfaceMode, targetDrivingMode) == expectedConfigMode;
    }

    /**
     * Drive mode exposed to OverDrive on the config-axis convention:
     * <pre>NORMAL = 1, ECO = 2, SPORT = 3, SNOW = 4</pre>
     *
     * <p>Fallback chain (first physically confirmed path wins):
     * <ol>
     *   <li>NORMAL/ECO/SPORT/SNOW: energy-device {@code setOperationMode(3/1/2/4)};</li>
     *   <li>SNOW compatibility fallback: {@code setRoadSurfaceMode(2)};</li>
     *   <li>Remaining fallbacks: {@code settingDevice.setDriveConfig(int)};</li>
     *   <li>generic target-driving-mode feature ids.</li>
     * </ol>
     *
     * @param configMode drive mode on the config axis (1=normal, 2=eco, 3=sport, 4=snow)
    * @return true if any path reported success
     */
    public boolean setDriveConfigMode(int configMode) {
        boolean ok = configMode >= 1 && configMode <= 4
                && VehicleActuatorBridge.dispatchStandaloneDriveMode(context, configMode);
        if (ok) {
            logger.info("setDriveConfigMode(" + configMode
                    + ") confirmed by standalone app_process");
        } else {
            ok = setDriveConfigModeInternal(configMode);
        }
        if (ok) {
            lastCommandedDriveMode = configMode;
            lastCommandedDriveModeAtMs = SystemClock.elapsedRealtime();
            if (configMode == 1 || configMode == 2) {
                lastNormalEcoDriveMode = configMode;
            } else if (configMode == 3) {
                lastNormalEcoDriveMode = -1;
            }
        }
        return ok;
    }

    // Short optimistic echo while the HAL settles. It must expire so a later physical/manual
    // mode change cannot be hidden for the rest of the process lifetime.
    private static final long DRIVE_MODE_COMMAND_ECHO_MS = 3000L;
    private volatile int lastCommandedDriveMode = -1;
    private volatile long lastCommandedDriveModeAtMs = 0L;
    // The live Energy axis collapses Normal and Eco to operation=1. Keep the last successfully
    // commanded base mode while that axis remains ambiguous; Sport clears it, Snow is an overlay.
    private volatile int lastNormalEcoDriveMode = -1;
    private volatile int lastEnergyModeEvent = -1;
    private volatile int lastEnergyOperationModeEvent = -1;
    private volatile int lastEnergyRoadSurfaceEvent = -1;
    private volatile String lastModeDiagnosticState;
    private static final long DRIVE_MODE_DIAGNOSTIC_PROBE_INTERVAL_MS = 1000L;
    // Connected CanFD Energy readback properties from BYDAutoFeatureIds; diagnostics only.
    static final int DIAG_ENERGY_OPERATION_MODE = 0x2120000e;
    static final int DIAG_ENERGY_OPERATION_MODE_SECONDARY = 0x3420001c;
    static final int DIAG_ENERGY_OPERATION_MODE_EV = 0x3420001e;
    static final int DIAG_SETTING_TARGET_DRIVING_MODE_LEGACY = 0x000ae2ac;
    private final Object driveModeDiagnosticProbeLock = new Object();
    private long lastDriveModeDiagnosticProbeAtMs;
    private String lastDriveModeDiagnosticProbeState = "not-sampled";

    static int recentDriveModeCommand(int mode, long commandedAtMs, long nowMs) {
        long age = nowMs - commandedAtMs;
        return mode >= 1 && mode <= 4 && commandedAtMs > 0L
                && age >= 0L && age <= DRIVE_MODE_COMMAND_ECHO_MS ? mode : -1;
    }

    /**
     * App config mode -> BYDAutoEnergyDevice public setter value used by the working 42.0 path.
     * The SDK remaps these setter values onto a different getter axis.
     */
    static int energyOperationModeForDriveConfig(int configMode) {
        switch (configMode) {
            case 1: return 3; // ENERGY_OPERATION_NORMAL
            case 2: return 1; // ENERGY_OPERATION_ECONOMY
            case 3: return 2; // ENERGY_OPERATION_SPORT
            case 4: return 4; // ENERGY_OPERATION_SNOW_RAIN
            default: return -1;
        }
    }

    private boolean setDriveConfigModeInternal(int configMode) {
        if (configMode < 1 || configMode > 4) {
            logger.warn("setDriveConfigMode: invalid mode " + configMode);
            return false;
        }

        int energyOperationMode = energyOperationModeForDriveConfig(configMode);
        if (configMode == 2) {
            // Leaving Snow requires restoring the common road surface before selecting Eco.
            setRoadSurfaceMode(1);
        }
        if (energyOperationMode > 0 && setOperationMode(energyOperationMode)) {
            logger.info("setDriveConfigMode(" + configMode
                    + "): applied via energy setOperationMode(" + energyOperationMode + ")");
            return true;
        }

        // Older implementations encode Snow as Eco + a separate road-surface selector.
        if (configMode == 4 && setRoadSurfaceMode(2)) {
            logger.info("setDriveConfigMode(SNOW): applied via energy-device setRoadSurfaceMode(2)");
            return true;
        }

        // Setting-device values match the app axis: 1=normal, 2=eco, 3=sport, 4=snow.
        int apiMode = configMode;

        // Dedicated setting-device method.
        if (settingDevice != null) {
            Method m = null;
            try {
                m = settingDevice.getClass().getMethod("setDriveConfig", int.class);
            } catch (NoSuchMethodException ignored) {
                // fall through to the fallbacks
            } catch (Exception e) {
                logger.debug("setDriveConfig lookup failed: " + e.getMessage());
            }
            if (m != null) {
                try {
                    Object r = m.invoke(settingDevice, apiMode);
                    logger.info("setDriveConfig(" + apiMode + ") return=" + r);
                    if (awaitDriveMode(configMode)) {
                        logger.info("setDriveConfigMode(" + configMode
                                + "): setDriveConfig(" + apiMode + ") confirmed");
                        return true;
                    }
                    logger.warn("setDriveConfigMode(" + configMode
                            + "): setDriveConfig(" + apiMode
                            + ") returned but no drive-mode axis confirmed it");
                } catch (Exception e) {
                    logger.debug("setDriveConfig(apiMode=" + apiMode + ") failed: " + e.getMessage());
                }
            }
        }

        // Target-mode feature ids use the same API value. Use the device family's
        // COMMAND_SUCCESS constant rather than assuming success is always zero.
        if (settingDevice != null) {
            int r1 = BydDeviceHelper.sendSetCommandRaw(settingDevice, BydFeatureIds.SETTING_TARGET_DRIVING_MODE, apiMode);
            if (isSdkWriteSuccess(settingDevice, r1, "SETTING_TARGET_DRIVING_MODE")) {
                if (awaitDriveMode(configMode)) {
                    logger.info("setDriveConfigMode(" + configMode
                            + "): SETTING_TARGET_DRIVING_MODE(" + apiMode + ") confirmed");
                    return true;
                }
                logger.warn("setDriveConfigMode(" + configMode
                        + "): SETTING_TARGET_DRIVING_MODE(" + apiMode
                        + ") accepted but not confirmed");
            }
            int r2 = BydDeviceHelper.sendSetCommandRaw(settingDevice, BydFeatureIds.SETTING_TARGET_DRIVING_MODE_ALT, apiMode);
            if (isSdkWriteSuccess(settingDevice, r2, "SETTING_TARGET_DRIVING_MODE_ALT")) {
                if (awaitDriveMode(configMode)) {
                    logger.info("setDriveConfigMode(" + configMode
                            + "): SETTING_TARGET_DRIVING_MODE_ALT(" + apiMode + ") confirmed");
                    return true;
                }
                logger.warn("setDriveConfigMode(" + configMode
                        + "): SETTING_TARGET_DRIVING_MODE_ALT(" + apiMode
                        + ") accepted but not confirmed");
            }
        }

        // Deliberately no CarSettings-provider tier. This unit defines power_management, but its
        // row remained 1 while the live HAL reported Sport, so it is preference storage rather
        // than reliable actuation/readback for this command.

        logger.warn("setDriveConfigMode(" + configMode + ", apiMode=" + apiMode + "): no working "
                + "drive-config path on this build (setDriveConfig + target-mode feature-ids rejected"
                + (configMode >= 2 ? " + energy setter rejected" : "")
                + (configMode == 4 ? " + setRoadSurfaceMode(2) rejected" : "")
                + ")");
        return false;
    }

    private boolean awaitDriveMode(int configMode) {
        long deadline = SystemClock.elapsedRealtime() + DRIVE_MODE_APPLY_TIMEOUT_MS;
        do {
            int energyMode = readEnergyAxisDriveMode();
            int settingMode = readDriveConfigRaw();
            if (energyMode == configMode
                    || configMode == 1 && settingMode == 1
                    || energyMode < 0 && settingMode == configMode) {
                return true;
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                Thread.sleep(Math.min(DRIVE_MODE_APPLY_POLL_MS, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    /**
     * Energy-device {@code setRoadSurfaceMode(int)} — compatibility path for implementations
     * that encode SNOW as ECO plus road-surface mode 2. Returns
     * true only on a non-throwing invoke with a success-coded result; false when the method or
     * device is absent so a trim without it never falsely reports success.
     */
    private boolean setRoadSurfaceMode(int mode) {
        if (energyDevice == null) return false;
        try {
            Method m = energyDevice.getClass().getMethod("setRoadSurfaceMode", int.class);
            Object r = m.invoke(energyDevice, mode);
            logger.info("setRoadSurfaceMode(" + mode + ") return=" + r);
            long deadline = SystemClock.elapsedRealtime() + DRIVE_MODE_APPLY_TIMEOUT_MS;
            int seen;
            do {
                seen = readRoadSurfaceMode(energyDevice);
                if (seen == mode) return true;
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                Thread.sleep(Math.min(DRIVE_MODE_APPLY_POLL_MS, remaining));
            } while (SystemClock.elapsedRealtime() < deadline);
            logger.warn("setRoadSurfaceMode(" + mode
                    + ") accepted but readback stayed " + readRoadSurfaceMode(energyDevice));
            return false;
        } catch (NoSuchMethodException nsme) {
            logger.debug("setRoadSurfaceMode absent on energy device");
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.debug("setRoadSurfaceMode(" + mode + ") failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Read the drive mode on OverDrive's config axis
     * (1=normal/2=eco/3=sport/4=snow).
     *
     * <p>The live ENERGY axis is authoritative. This trim reports both Normal and Eco as
     * operation 1, so the typed operation callback disambiguates those two; the target-driving-mode
     * property remains the startup fallback. Snow/rain is a road-surface overlay and therefore
     * takes precedence over every base operation mode.
     */
    public int getDriveConfigMode() {
        int energyMode = readEnergyAxisDriveMode();
        int cached = recentDriveModeCommand(
                lastCommandedDriveMode,
                lastCommandedDriveModeAtMs,
                SystemClock.elapsedRealtime());
        int preferred = chooseDriveModeReadback(energyMode, cached, -1);
        if (preferred >= 1) return preferred;

        int settingRaw = readDriveConfigRaw();
        int settingMode = settingRaw == Integer.MIN_VALUE ? -1 : settingRaw;
        if (settingRaw != Integer.MIN_VALUE) {
            long now = System.currentTimeMillis();
            if (now - lastDriveConfigLogMs > 60000) {
                lastDriveConfigLogMs = now;
                logger.info("drive setting-state raw=" + settingRaw
                        + " (1=normal/2=eco/3=sport/4=snow), commandEcho=" + cached);
            }
        }
        return chooseDriveModeReadback(energyMode, cached, settingMode);
    }

    /** Select a config-axis mode without allowing stale setting data to override live energy. */
    static int chooseDriveModeReadback(int energyMode, int commandedMode, int settingMode) {
        if (energyMode >= 1 && energyMode <= 4) return energyMode;
        if (commandedMode >= 1 && commandedMode <= 4) return commandedMode;
        if (settingMode >= 1 && settingMode <= 4) return settingMode;
        return -1;
    }

    /**
     * Drive mode derived from the ENERGY device axis, mapped onto the config axis
     * (1=normal/2=eco/3=sport/4=snow), or -1 when that axis can't answer either.
     *
     * <p>The live getter uses 1=eco-or-normal and 2=sport. The typed listener preserves the public
     * 1=eco/3=normal axis when operation 1 is ambiguous; target-driving-mode remains the fallback.
     * Snow/rain is either direct operation 4 or road-surface value 2 over any base mode.
     */
    private int readEnergyAxisDriveMode() {
        if (energyDevice == null) return -1;
        try {
            Object opMode = BydDeviceHelper.callGetter(energyDevice, "getOperationMode");
            if (!(opMode instanceof Number)) return -1;
            int op = ((Number) opMode).intValue();
            int surface = readRoadSurfaceMode(energyDevice);
            int target = op == 1 && surface != 2
                    ? readTargetDrivingMode(settingDevice) : -1;
            int operationEvent = lastEnergyOperationModeEvent;
            int normalized = driveModeFromEnergyAxis(
                    op, surface, target, operationEvent, lastNormalEcoDriveMode);
            if (normalized == 3) lastNormalEcoDriveMode = -1;
            String directProbes = op == 1 && surface != 2
                    ? readDriveModeDiagnosticProbes()
                    : "skipped-unambiguous";
            Object rawEnergyValue = BydDeviceHelper.callGetter(energyDevice, "getEnergyMode");
            int rawEnergy = rawEnergyValue instanceof Number
                    ? ((Number) rawEnergyValue).intValue() : Integer.MIN_VALUE;
            int settingRaw = readDriveConfigRaw();
            String diagnosticState = "energy=" + diagnosticValue(rawEnergy)
                    + (rawEnergy == Integer.MIN_VALUE ? "" : "(" + energyModeName(rawEnergy) + ")")
                    + " operation=" + op
                    + " surface=" + surface
                    + " driveConfig=" + diagnosticValue(settingRaw)
                    + " target=" + target
                    + " callbacks={energy=" + lastEnergyModeEvent
                    + ",operation=" + operationEvent
                    + ",surface=" + lastEnergyRoadSurfaceEvent + "}"
                    + " normalEcoHint=" + lastNormalEcoDriveMode
                    + " direct={" + directProbes + "}"
                    + " normalized=" + normalized;
            long now = System.currentTimeMillis();
            if (!diagnosticState.equals(lastModeDiagnosticState)
                    || now - lastEnergyDriveModeLogMs > 60000) {
                lastModeDiagnosticState = diagnosticState;
                lastEnergyDriveModeLogMs = now;
                logger.info("[mode-diag] snapshot " + diagnosticState);
            }
            return normalized;
        } catch (Exception e) {
            logger.debug("energy-axis drive-mode read failed: " + e.getMessage());
        }
        return -1;
    }

    /** Named SDK getter first, then the same Energy property through the generic feature bus. */
    static int readRoadSurfaceMode(Object device) {
        Object named = BydDeviceHelper.callGetter(device, "getRoadSurfaceMode");
        if (named instanceof Number) return ((Number) named).intValue();
        int generic = BydDeviceHelper.getIntValue(BydDeviceHelper.callGetProbing(
                device, BydFeatureIds.ENERGY_ROAD_SURFACE_MODE, true));
        return generic != Integer.MIN_VALUE ? generic : -1;
    }

    /** Target operation-mode preference used to distinguish Normal from Eco. */
    static int readTargetDrivingMode(Object device) {
        int target = BydDeviceHelper.getIntValue(BydDeviceHelper.callGetProbing(
                device, BydFeatureIds.SETTING_TARGET_DRIVING_MODE, true));
        if (target >= 1 && target <= 5) return target;
        target = BydDeviceHelper.getIntValue(BydDeviceHelper.callGetProbing(
                device, BydFeatureIds.SETTING_TARGET_DRIVING_MODE_ALT, true));
        return target >= 1 && target <= 5 ? target : -1;
    }

    private void resetDriveModeDiagnosticProbes() {
        synchronized (driveModeDiagnosticProbeLock) {
            lastDriveModeDiagnosticProbeAtMs = 0L;
            lastDriveModeDiagnosticProbeState = "not-sampled";
        }
    }

    /**
     * Read-only candidate-property dump for the Normal/Eco ambiguity. The one-second cache keeps
     * the fast automation poll from multiplying HAL traffic; the enclosing snapshot logs only when
     * one of these values changes (plus its existing one-minute heartbeat).
     */
    private String readDriveModeDiagnosticProbes() {
        long now = SystemClock.elapsedRealtime();
        synchronized (driveModeDiagnosticProbeLock) {
            if (lastDriveModeDiagnosticProbeAtMs > 0L
                    && now >= lastDriveModeDiagnosticProbeAtMs
                    && now - lastDriveModeDiagnosticProbeAtMs
                    < DRIVE_MODE_DIAGNOSTIC_PROBE_INTERVAL_MS) {
                return lastDriveModeDiagnosticProbeState;
            }
            lastDriveModeDiagnosticProbeState =
                    driveModeDiagnosticProbeState(energyDevice, settingDevice);
            lastDriveModeDiagnosticProbeAtMs = now;
            return lastDriveModeDiagnosticProbeState;
        }
    }

    static String driveModeDiagnosticProbeState(Object energy, Object setting) {
        return diagnosticFeature(
                        "energy.operation", energy, DIAG_ENERGY_OPERATION_MODE)
                + "," + diagnosticFeature(
                        "energy.operation2", energy, DIAG_ENERGY_OPERATION_MODE_SECONDARY)
                + "," + diagnosticFeature(
                        "energy.operationEv", energy, DIAG_ENERGY_OPERATION_MODE_EV)
                + "," + diagnosticFeature(
                        "setting.target", setting, BydFeatureIds.SETTING_TARGET_DRIVING_MODE)
                + "," + diagnosticFeature(
                        "setting.targetLegacy",
                        setting,
                        DIAG_SETTING_TARGET_DRIVING_MODE_LEGACY)
                + "," + diagnosticFeature(
                        "setting.targetAlt",
                        setting,
                        BydFeatureIds.SETTING_TARGET_DRIVING_MODE_ALT);
    }

    private static String diagnosticFeature(String name, Object device, int featureId) {
        int value = BydDeviceHelper.getIntValue(
                BydDeviceHelper.callGet(device, featureId, Integer.TYPE));
        return name + "[0x" + Integer.toHexString(featureId) + "]="
                + diagnosticValue(value);
    }

    /** Raw operation, road surface, and target mode -> app config mode. */
    static int driveModeFromEnergyAxis(
            int rawOperationMode, int roadSurfaceMode, int targetDrivingMode) {
        if (roadSurfaceMode == 2 || rawOperationMode == 4) return 4;
        switch (rawOperationMode) {
            case 1: return targetDrivingMode == 3 ? 1 : 2; // NORMAL or ECO
            case 2: return 3; // SPORT
            case 3: return 1; // NORMAL
            default: return -1;
        }
    }

    /**
     * The operation callback uses the public setter axis and disambiguates the getter's raw value 1.
     */
    static int driveModeFromEnergyAxis(
            int rawOperationMode,
            int roadSurfaceMode,
            int targetDrivingMode,
            int operationModeEvent) {
        if (rawOperationMode == 1
                && (operationModeEvent == 1 || operationModeEvent == 3)) {
            targetDrivingMode = operationModeEvent;
        }
        return driveModeFromEnergyAxis(rawOperationMode, roadSurfaceMode, targetDrivingMode);
    }

    /** Apply the last confirmed base-mode command only when every live Normal/Eco signal is mute. */
    static int driveModeFromEnergyAxis(
            int rawOperationMode,
            int roadSurfaceMode,
            int targetDrivingMode,
            int operationModeEvent,
            int normalEcoHint) {
        int mode = driveModeFromEnergyAxis(
                rawOperationMode, roadSurfaceMode, targetDrivingMode, operationModeEvent);
        return mode == 2
                && rawOperationMode == 1
                && roadSurfaceMode != 2
                && targetDrivingMode < 1
                && operationModeEvent < 1
                && normalEcoHint == 1 ? 1 : mode;
    }

    /**
     * Uncached raw read of the setting-device drive-config axis, for the post-write diagnostic.
     * Returns the value VERBATIM (no 1..4 filtering) so the log can distinguish an out-of-range
     * answer from an absent getter; {@link Integer#MIN_VALUE} means "no answer at all".
     *
     * <p>Must NOT consult {@code lastCommandedDriveMode} the way {@link #getDriveConfigMode()}
     * does — reading our own cache back would confirm every write unconditionally.
     */
    private int readDriveConfigRaw() {
        if (settingDevice == null) return Integer.MIN_VALUE;
        try {
            Object r = BydDeviceHelper.callGetter(settingDevice, "getDriveConfig");
            if (r instanceof Number) return ((Number) r).intValue();
        } catch (Exception e) {
            logger.debug("readDriveConfigRaw failed: " + e.getMessage());
        }
        return Integer.MIN_VALUE;
    }

    /** Throttle for the getDriveConfig diagnostic (1/min). */
    // volatile: read/written from both the slow BydDataPoll thread and the DriveModeEvent
    // fast-poll thread (getDriveConfigMode is now called from both). Only guards a 1/min
    // diagnostic log, so a torn read is cosmetic, but volatile keeps it consistent (same
    // rationale as lockUnlockStreak).
    private volatile long lastDriveConfigLogMs = 0;
    private volatile long lastEnergyDriveModeLogMs = 0;

      /** Throttle for the getEnergyMode diagnostic (1/min). Same rationale as above. */
      private volatile long lastEnergyModeLogMs = 0;

      private static final int DAEMON_ENERGY_RECONCILE_MAX_ATTEMPTS = 6;
      private static final long DAEMON_ENERGY_RECONCILE_BASE_DELAY_MS = 250L;
      private static final long DAEMON_ENERGY_RECONCILE_MAX_DELAY_MS = 4000L;
      private final Object daemonEnergyReconcileLock = new Object();
      private java.util.concurrent.ScheduledThreadPoolExecutor daemonEnergyReconcileExecutor;
      private java.util.concurrent.ScheduledFuture<?> daemonEnergyReconcileFuture;
      private long daemonEnergyReconcileTicket;
      private int daemonEnergyReconcileFailureCount;
      /** Guarded by {@link #daemonEnergyReconcileLock}. */
      private boolean daemonEnergyReconciliationEnabled;

      /** Resume only rollback work durably owned by the daemon process class. */
      private void reconcilePersistedDaemonEnergyState() {
          final long ticket;
          synchronized (daemonEnergyReconcileLock) {
              if (!daemonEnergyReconciliationEnabled) return;
              ticket = ++daemonEnergyReconcileTicket;
              daemonEnergyReconcileFailureCount = 0;
              if (daemonEnergyReconcileFuture != null) {
                  daemonEnergyReconcileFuture.cancel(false);
                  daemonEnergyReconcileFuture = null;
              }
          }
          reconcilePersistedDaemonEnergyState(ticket, 0);
      }

      private void rearmPersistedDaemonEnergyReconciliation() {
          final long ticket;
          final int attempt;
          synchronized (daemonEnergyReconcileLock) {
              if (!daemonEnergyReconciliationEnabled) return;
              ticket = ++daemonEnergyReconcileTicket;
              attempt = ++daemonEnergyReconcileFailureCount;
              if (daemonEnergyReconcileFuture != null) {
                  daemonEnergyReconcileFuture.cancel(false);
                  daemonEnergyReconcileFuture = null;
              }
          }
          schedulePersistedDaemonEnergyReconciliation(ticket, attempt);
      }

      private void notePersistedDaemonEnergyReconciliationProgress() {
          synchronized (daemonEnergyReconcileLock) {
              daemonEnergyReconcileFailureCount = 0;
          }
      }

      private void reconcilePersistedDaemonEnergyState(long ticket, int attempt) {
          synchronized (daemonEnergyReconcileLock) {
              if (!daemonEnergyReconciliationEnabled
                      || ticket != daemonEnergyReconcileTicket || context == null) {
                  return;
              }
              daemonEnergyReconcileFailureCount =
                      Math.max(daemonEnergyReconcileFailureCount, attempt);
          }
          try {
              VehicleActuatorBridge.PublishedEnergyRead read =
                      VehicleActuatorBridge.readPublishedEnergyState(context);
              VehicleActuatorBridge.PublishedEnergyRequest marker = read.request;
              boolean retry = false;
              synchronized (daemonEnergyReconcileLock) {
                  if (!daemonEnergyReconciliationEnabled
                          || ticket != daemonEnergyReconcileTicket) {
                      return;
                  }
                  if (read.status == VehicleActuatorBridge.EnergyReadStatus.VALID
                          && marker != null) {
                      if (marker.cancelled
                              && marker.rollbackPending
                              && marker.rollbackOwner
                              == VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON
                              && marker.compensationAttempts
                              < VehicleActuatorBridge.MAX_ENERGY_COMPENSATION_ATTEMPTS) {
                          retry = !energySetterLane.reconcileCancellation(marker);
                      } else if (!marker.cancelled && !marker.pending) {
                          retry = !energySetterLane.reconcileDesired(marker);
                      }
                  } else {
                      retry = read.status
                              != VehicleActuatorBridge.EnergyReadStatus.VALID
                              && read.status
                              != VehicleActuatorBridge.EnergyReadStatus.MISSING;
                  }
              }
              if (retry) {
                  schedulePersistedDaemonEnergyReconciliation(ticket, attempt + 1);
              }
          } catch (Throwable failed) {
              logger.warn("daemon energy rollback reconciliation failed: "
                      + failed.getMessage());
              schedulePersistedDaemonEnergyReconciliation(ticket, attempt + 1);
          }
      }

      private void schedulePersistedDaemonEnergyReconciliation(
              long ticket, int attempt) {
          if (attempt > DAEMON_ENERGY_RECONCILE_MAX_ATTEMPTS) {
              logger.warn("daemon energy rollback reconciliation exhausted after "
                      + Math.max(0, attempt - 1) + " retries");
              return;
          }
          long shift = Math.min(4, Math.max(0, attempt - 1));
          long delay = Math.min(
                  DAEMON_ENERGY_RECONCILE_MAX_DELAY_MS,
                  DAEMON_ENERGY_RECONCILE_BASE_DELAY_MS << shift);
          synchronized (daemonEnergyReconcileLock) {
              if (!daemonEnergyReconciliationEnabled
                      || ticket != daemonEnergyReconcileTicket || context == null) {
                  return;
              }
              if (daemonEnergyReconcileExecutor == null
                      || daemonEnergyReconcileExecutor.isShutdown()) {
                  daemonEnergyReconcileExecutor =
                          new java.util.concurrent.ScheduledThreadPoolExecutor(
                                  1,
                                  runnable -> {
                                      Thread worker =
                                              new Thread(
                                                      runnable,
                                                      "DaemonEnergyReconcile");
                                      worker.setDaemon(true);
                                      return worker;
                                  },
                                  new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
                  daemonEnergyReconcileExecutor.setRemoveOnCancelPolicy(true);
              }
              if (daemonEnergyReconcileFuture != null) {
                  daemonEnergyReconcileFuture.cancel(false);
              }
              try {
                  daemonEnergyReconcileFuture =
                          daemonEnergyReconcileExecutor.schedule(
                                  () -> {
                                      synchronized (daemonEnergyReconcileLock) {
                                          if (!daemonEnergyReconciliationEnabled
                                                  || ticket != daemonEnergyReconcileTicket) {
                                              return;
                                          }
                                          daemonEnergyReconcileFuture = null;
                                      }
                                      reconcilePersistedDaemonEnergyState(
                                              ticket, attempt);
                                  },
                                  delay,
                                  java.util.concurrent.TimeUnit.MILLISECONDS);
              } catch (Throwable unavailable) {
                  daemonEnergyReconcileFuture = null;
                  logger.warn("could not schedule daemon energy reconciliation: "
                          + unavailable.getMessage());
              }
          }
      }

      private void cancelPersistedDaemonEnergyReconciliation(boolean shutdown) {
          synchronized (daemonEnergyReconcileLock) {
              daemonEnergyReconciliationEnabled = !shutdown;
              daemonEnergyReconcileTicket++;
              if (daemonEnergyReconcileFuture != null) {
                  daemonEnergyReconcileFuture.cancel(false);
                  daemonEnergyReconcileFuture = null;
              }
              if (shutdown && daemonEnergyReconcileExecutor != null) {
                  daemonEnergyReconcileExecutor.shutdownNow();
                  daemonEnergyReconcileExecutor = null;
              }
          }
      }

    /**
     * Energy/powertrain preference: EV vs HEV. On this firmware the OEM UI writes
     * {@code setMandatoryElectricState}: 2 selects mandatory EV and 1 selects intelligent/HEV.
     * The public {@code setEnergyMode} axis is retained only as an older-firmware fallback.
     *
     * <p><b>Value guard:</b> {@code 0 = ENERGY_MODE_STOP} is refused — it is not a
     * powertrain preference and commanding it could stop the drivetrain. Only the field-validated
     * EV(1) and HEV(3) values are accepted as user commands. Other valid SDK values remain
     * readable and may be restored internally as cancellation rollback targets.
     */
    public boolean setEnergyMode(int mode) {
        if (mode != ENERGY_MODE_EV && mode != ENERGY_MODE_HEV) {
            logger.warn("setEnergyMode: refusing unproven user mode " + mode
                    + " (valid: 1=EV, 3=HEV)");
            return false;
        }

        // Claim a process-wide monotonic token at ingress, before any device lookup can block.
        // elapsedRealtimeNanos survives daemon process restarts within the same boot, so the app
        // process can reject a delayed shell launch even when either side was recreated.
        final long proposedGeneration = nextEnergyRequestGeneration();
        VehicleActuatorBridge.PublishedEnergyRequest reservation =
                VehicleActuatorBridge.reserveEnergyRequest(
                        context, mode, proposedGeneration);
        if (reservation == null) {
            logger.warn("setEnergyMode(" + mode
                    + ") aborted: global generation reservation failed");
            return false;
        }
        final long generation = reservation.generation;
        lastEnergyRequestGeneration.accumulateAndGet(generation, Math::max);
        final EnergyRequest request = new EnergyRequest(generation, mode);
        EnergyRequest published = energyModeCurrent.updateAndGet(previous ->
                previous == null || request.generation > previous.generation ? request : previous);
        if (published != request) {
            cancelEnergyRequest(request, "superseded at ingress");
            logger.info("setEnergyMode(" + mode + ") generation=" + generation
                    + " superseded at ingress");
            return false;
        }
        synchronized (energyModeWriteLock) {
            if (!isDaemonEnergyRequestCurrent(request)) {
                cancelEnergyRequest(request, "superseded before latest-state publication");
                logger.info("setEnergyMode(" + mode + ") generation=" + generation
                        + " superseded before latest-state publication");
                return false;
            }

            // reserveEnergyRequest already commits and confirms the app-visible marker. Publishing
            // it again races the service: once the service records BEGIN metadata, the original
            // desired-only payload is correctly no longer an exact match.
            final boolean statePublished = true;
            if (!isDaemonEnergyRequestCurrent(request)) {
                cancelEnergyRequest(request, "superseded during latest-state publication");
                logger.info("setEnergyMode(" + mode + ") generation=" + generation
                        + " superseded during latest-state publication");
                return false;
            }

            // Run the OEM-compatible shell app_process path first. It initializes the Energy
            // singleton, validates this generation and physically verifies the axis before exit.
            boolean standaloneConfirmed = false;
            try {
                standaloneConfirmed =
                        VehicleActuatorBridge.dispatchStandaloneEnergyMode(
                                context, mode, generation);
            } catch (Throwable t) {
                logger.warn("setEnergyMode standalone app-process failed: " + t.getMessage());
            }
            if (Thread.currentThread().isInterrupted()) {
                cancelEnergyRequest(request, "interrupted during standalone app-process");
                return false;
            }
            if (!isDaemonEnergyRequestCurrent(request)) {
                cancelEnergyRequest(request, "superseded during standalone app-process");
                return false;
            }

            boolean applied = standaloneConfirmed || awaitEnergyMode(
                    request, ENERGY_LOCAL_APPLY_TIMEOUT_MS, energyDevice);

            // Retain the isolated foreground service as the first fallback for firmware that
            // accepts the same package-context call only from an Android component process.
            boolean appLaunched = false;
            if (!applied) {
                try {
                    appLaunched =
                            VehicleActuatorBridge.dispatchEnergyMode(context, mode, generation);
                } catch (Throwable t) {
                    logger.warn("setEnergyMode app-process launch failed: " + t.getMessage());
                }
                if (Thread.currentThread().isInterrupted()) {
                    cancelEnergyRequest(request, "interrupted during app-process launch");
                    logger.info("setEnergyMode(" + mode + ") generation=" + generation
                            + " canceled during app-process launch");
                    return false;
                }
                if (!isDaemonEnergyRequestCurrent(request)) {
                    cancelEnergyRequest(request, "superseded during app-process launch");
                    logger.info("setEnergyMode(" + mode + ") generation=" + generation
                            + " superseded during app-process launch");
                    return false;
                }
                applied = awaitEnergyMode(
                        request, ENERGY_APP_APPLY_TIMEOUT_MS, energyDevice);
            }
            boolean localAccepted = false;
            if (Thread.currentThread().isInterrupted() || request.cancelled) {
                cancelEnergyRequest(request, "interrupted during app-process verification");
                return false;
            }
            if (!isDaemonEnergyRequestCurrent(request)) {
                cancelEnergyRequest(request, "superseded during app-process verification");
                return false;
            }
            if (!applied && isDaemonEnergyRequestCurrent(request)) {
                VehicleActuatorBridge.PublishedEnergyRead ownerRead =
                        VehicleActuatorBridge.readPublishedEnergyState(context);
                VehicleActuatorBridge.PublishedEnergyRequest marker = ownerRead.request;
                if (ownerRead.status != VehicleActuatorBridge.EnergyReadStatus.VALID
                        || !VehicleActuatorBridge.isEnergyActuatorOwnershipAvailable(
                                marker, VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON)) {
                    logger.info("setEnergyMode(" + mode + ") generation=" + generation
                            + " daemon fallback skipped: ownership status=" + ownerRead.status
                            + " started=" + (marker != null && marker.actuationStarted)
                            + " owner=" + (marker == null
                            ? VehicleActuatorBridge.ENERGY_ACTUATOR_NONE
                            : marker.rollbackOwner));
                } else {
                    // The daemon may write only while the generation is unclaimed or already
                    // daemon-owned. Transferring an app-owned generation would race its rollback.
                    long deviceResolutionSequence =
                            energyDeviceResolutionSequence.incrementAndGet();
                    Object device = freshEnergyDevice();
                    if (Thread.currentThread().isInterrupted()) {
                        cancelEnergyRequest(request, "interrupted during daemon device lookup");
                        return false;
                    }
                    if (!isDaemonEnergyRequestCurrent(request)) {
                        cancelEnergyRequest(request, "superseded during daemon device lookup");
                        return false;
                    }
                    EnergySetterOutcome setterOutcome = invokeOptionalModeSetterBounded(
                            request, device, "setEnergyMode", mode,
                            "energy/powertrain mode (" + energyModeName(mode) + ")",
                            deviceResolutionSequence);
                    localAccepted = setterOutcome.accepted;
                    // If interruption raced a setter that had already begun, tombstone this
                    // generation. The fixed lane may finish the Binder call, but it must not retain
                    // retries for a command whose caller canceled.
                    if (Thread.currentThread().isInterrupted() || request.cancelled) {
                        cancelEnergyRequest(request, "interrupted during daemon setter");
                        return false;
                    }
                    if (!isDaemonEnergyRequestCurrent(request)) {
                        cancelEnergyRequest(request, "superseded during daemon setter");
                        return false;
                    }
                    // Verify through the same handle that received the fallback write. During
                    // Binder recovery the cached field can be stale while this fresh handle works.
                    // A timed-out setter transfers verification to the fixed lane.
                    if (!setterOutcome.verificationHandedOff) {
                        applied = awaitEnergyMode(
                                request, ENERGY_LOCAL_APPLY_TIMEOUT_MS, device);
                    }
                }
            }
            if (Thread.currentThread().isInterrupted() || request.cancelled) {
                cancelEnergyRequest(request, "after daemon verification");
                return false;
            }
            if (!isDaemonEnergyRequestCurrent(request)) {
                cancelEnergyRequest(request, "superseded after daemon verification");
                return false;
            }

            logger.info("setEnergyMode(" + mode + "=" + energyModeName(mode) + ") generation="
                    + generation + " statePublished=" + statePublished
                    + " standaloneConfirmed=" + standaloneConfirmed
                    + " appLaunched=" + appLaunched
                    + " localAccepted=" + localAccepted + " applied=" + applied);
            // A launch or setter return is not success: this HAL is known to accept ignored writes.
            // Report only generation-aware confirmation of the selected OEM preference.
            return applied;
        }
    }

    /** One energy-mode command: its sequence number and the mode it asked for, published together
     *  so a pending verify can never see one without the other. */
    private static final class EnergyRequest {
        final long generation; final int mode;
        volatile boolean cancelled;
        EnergyRequest(long generation, int mode) { this.generation = generation; this.mode = mode; }
    }

    /**
     * Invalidate a request both in this process and in the boot-scoped app-service marker.
     * Thread interruption is temporarily cleared only so the bounded marker lane can accept and
     * confirm the tombstone; the exact interrupted status is restored before returning.
     */
    private void cancelEnergyRequest(EnergyRequest request, String stage) {
        if (request == null) return;
        request.cancelled = true;
        energySetterLane.cancelThrough(request.generation);
        boolean restoreInterrupt = Thread.interrupted();
        try {
            boolean markerCancelled =
                    VehicleActuatorBridge.cancelPublishedEnergyRequest(
                            context, request.generation);
            if (!markerCancelled) {
                logger.warn("setEnergyMode(" + request.mode + ") generation="
                        + request.generation + " cancellation at " + stage
                        + " was not synchronously confirmed");
            }
            VehicleActuatorBridge.PublishedEnergyRead read =
                    VehicleActuatorBridge.readPublishedEnergyState(context);
            if (read.status == VehicleActuatorBridge.EnergyReadStatus.VALID
                    && read.request != null
                    && read.request.cancelled
                    && read.request.generation == request.generation) {
                energySetterLane.reconcileCancellation(read.request);
            }
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    /** The newest energy-mode command. A verify compares its own ticket against this to tell whether
     *  it has been superseded, and by a DIFFERENT mode or a repeat of its own. */
    private final java.util.concurrent.atomic.AtomicReference<EnergyRequest> energyModeCurrent =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Reconcile process-local ordering with the boot-scoped marker. A daemon left over from an
     * earlier automation process can never use its local reference as authority.
     */
    private boolean isDaemonEnergyRequestCurrent(EnergyRequest request) {
        if (request == null || request.cancelled
                || energyModeCurrent.get() != request) {
            return false;
        }
        VehicleActuatorBridge.PublishedEnergyRead read =
                VehicleActuatorBridge.readPublishedEnergyState(context);
        VehicleActuatorBridge.PublishedEnergyRequest marker = read.request;
        if (read.status != VehicleActuatorBridge.EnergyReadStatus.VALID
                || marker == null) {
            return false;
        }
        if (marker.cancelled) {
            if (marker.generation == request.generation) request.cancelled = true;
            return false;
        }
        if (marker.pending) return false;
        if (marker.generation > request.generation) {
            adoptAuthoritativeEnergyRequest(marker);
            return false;
        }
        return marker.generation == request.generation
                && marker.mode == request.mode
                && energyModeCurrent.get() == request
                && !request.cancelled;
    }

    private EnergyRequest adoptAuthoritativeEnergyRequest(
            VehicleActuatorBridge.PublishedEnergyRequest marker) {
        if (marker == null || marker.cancelled || marker.pending
                || marker.mode < 1 || marker.mode > ENERGY_MODE_KEEP) {
            return null;
        }
        lastEnergyRequestGeneration.accumulateAndGet(marker.generation, Math::max);
        while (true) {
            EnergyRequest current = energyModeCurrent.get();
            if (current != null && current.generation >= marker.generation) return current;
            EnergyRequest adopted = new EnergyRequest(marker.generation, marker.mode);
            if (energyModeCurrent.compareAndSet(current, adopted)) return adopted;
        }
    }

    /** Serializes daemon-side HAL writes; superseded waiters skip before touching the device. */
    private final Object energyModeWriteLock = new Object();

    private static final long ENERGY_APP_APPLY_TIMEOUT_MS = 1500L;
    private static final long ENERGY_LOCAL_APPLY_TIMEOUT_MS = 500L;
    private static final long ENERGY_APPLY_POLL_MS = 50L;
    private static final long ENERGY_DEVICE_LOOKUP_TIMEOUT_MS = 500L;
    private static final long ENERGY_SETTER_TIMEOUT_MS = 500L;
    private static final long ENERGY_READ_TIMEOUT_MS = 200L;
    private static final int ENERGY_CORRECTION_MAX_ATTEMPTS = 2;
    private static final java.util.concurrent.ExecutorService ENERGY_DEVICE_LOOKUP_EXECUTOR =
            newEnergyHalExecutor("EnergyDeviceLookup");
    private static final java.util.concurrent.ExecutorService ENERGY_DIRECT_SET_EXECUTOR =
            newEnergyHalExecutor("EnergyModeDirectSet");
    private static final java.util.concurrent.ExecutorService ENERGY_READ_EXECUTOR =
            newEnergyHalExecutor("EnergyModeRead");
    private static final java.util.concurrent.atomic.AtomicBoolean ENERGY_LOOKUP_STALLED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean ENERGY_DIRECT_SET_STALLED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean ENERGY_READ_STALLED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong energyDeviceResolutionSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private final EnergySetterLane energySetterLane = new EnergySetterLane();

    private static java.util.concurrent.ExecutorService newEnergyHalExecutor(String threadName) {
        return new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread worker = new Thread(runnable, threadName);
                    worker.setDaemon(true);
                    return worker;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    /** Monotonic source generation shared with the ordered app-process bridge. */
    private final java.util.concurrent.atomic.AtomicLong lastEnergyRequestGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    private long nextEnergyRequestGeneration() {
        final long clock = SystemClock.elapsedRealtimeNanos();
        return lastEnergyRequestGeneration.updateAndGet(
                previous -> Math.max(clock, previous == Long.MAX_VALUE ? previous : previous + 1L));
    }

    /**
     * A freshly-resolved {@code BYDAutoEnergyDevice} handle for a one-shot write, falling back to
     * the one cached at {@link #init} when re-resolution fails.
     *
     * <p>Returned as a LOCAL only — {@link #energyDevice} is deliberately not reassigned. The
     * listener bookkeeping in {@code registerAllListeners} is keyed by handle identity, so
     * swapping the field to a new instance would make it look unregistered and stack a duplicate
     * HAL callback on the next init.
     *
     * <p>A handle that is genuinely NEW (not the process singleton) has never been through
     * {@code init}'s activation step, and on a trim whose HAL requires enabling before it honours
     * commands an un-activated handle accepts the write and ignores it — reproducing the exact
     * symptom this path exists to diagnose. So activate any new instance before returning it;
     * {@code enableDevice} is advisory and its result is deliberately ignored, matching
     * {@code initDevice}. When {@code getInstance} hands back the cached singleton this is skipped
     * entirely, so the common case costs nothing.
     */
    private Object freshEnergyDevice() {
        return callEnergyHalBounded(
                ENERGY_DEVICE_LOOKUP_EXECUTOR,
                this::freshEnergyDeviceDirect,
                ENERGY_DEVICE_LOOKUP_TIMEOUT_MS,
                energyDevice,
                ENERGY_LOOKUP_STALLED,
                "energy device lookup/activation");
    }

    private Object freshEnergyDeviceDirect() {
        try {
            Object d = BydDeviceHelper.getDevice(
                    "android.hardware.bydauto.energy.BYDAutoEnergyDevice", context);
            if (d != null && d != energyDevice) {
                try {
                    BydManagerChannel.enableDevice(context, d, "Energy");
                } catch (Throwable t) {
                    logger.debug("freshEnergyDevice activation threw: " + t.getMessage());
                }
                return d;
            }
            if (d != null) return d;
        } catch (Throwable t) {
            logger.debug("freshEnergyDevice failed, using cached handle: " + t.getMessage());
        }
        return energyDevice;
    }

    /**
     * Reference-app path: invoke the named setter directly, then trust only physical readback.
     * The bounded write lane prevents a wedged vendor Binder call from blocking automation.
     */
    private boolean setEnergyModeDirectVerified(int mode) {
        Object device = freshEnergyDevice();
        if (device == null) return false;

        int seen = readEnergyModeRawOn(device, ENERGY_READ_TIMEOUT_MS);
        if (seen == mode) {
            logger.info("setEnergyMode(" + mode + "=" + energyModeName(mode)
                    + ") direct path: axis already at target");
            return true;
        }

        Boolean accepted = callEnergyHalBounded(
                ENERGY_DIRECT_SET_EXECUTOR,
                () -> Boolean.valueOf(
                        invokeModeSetterForReadback(device, "setEnergyMode", mode)),
                ENERGY_SETTER_TIMEOUT_MS,
                Boolean.FALSE,
                ENERGY_DIRECT_SET_STALLED,
                "direct energy-mode setter");
        if (!Boolean.TRUE.equals(accepted) || Thread.currentThread().isInterrupted()) {
            return false;
        }

        long deadline = SystemClock.elapsedRealtime() + ENERGY_APP_APPLY_TIMEOUT_MS;
        do {
            seen = readEnergyModeRawOn(
                    device,
                    Math.min(ENERGY_READ_TIMEOUT_MS,
                            Math.max(1L, deadline - SystemClock.elapsedRealtime())));
            if (seen == mode) {
                logger.info("setEnergyMode(" + mode + "=" + energyModeName(mode)
                        + ") direct path confirmed");
                return true;
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                Thread.sleep(Math.min(ENERGY_APPLY_POLL_MS, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (SystemClock.elapsedRealtime() < deadline);

        logger.warn("setEnergyMode(" + mode + "=" + energyModeName(mode)
                + ") direct setter was accepted but axis remained " + energyModeName(seen));
        return false;
    }

    private boolean invokeModeSetterForReadback(
            Object device, String methodName, int mode) {
        if (device == null) return false;
        try {
            Method setter = device.getClass().getMethod(methodName, int.class);
            Object result = setter.invoke(device, mode);
            logger.info(methodName + "(" + mode + ") return=" + result);
            return true;
        } catch (NoSuchMethodException absent) {
            logger.warn(methodName + "(int) is absent on " + device.getClass().getName());
        } catch (Throwable failed) {
            Throwable cause = failed instanceof java.lang.reflect.InvocationTargetException
                    && failed.getCause() != null ? failed.getCause() : failed;
            logger.warn(methodName + "(" + mode + ") invoke failed: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        return false;
    }

    /**
     * Wait for the real energy axis to reach this request's mode. The request identity check makes
     * the readback an acknowledgement of the current generation rather than a stale earlier write.
     */
    private boolean awaitEnergyMode(EnergyRequest request, long timeoutMs, Object device) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int seen = BydVehicleData.UNAVAILABLE;
        while (isDaemonEnergyRequestCurrent(request)) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            seen = readEnergyModeRawOn(
                    device, Math.min(ENERGY_READ_TIMEOUT_MS, remaining));
            if (!isDaemonEnergyRequestCurrent(request)) return false;
            if (seen == request.mode) {
                boolean committed = VehicleActuatorBridge.completeEnergyActuation(
                        context, request.generation, request.mode);
                if (!committed) {
                    logger.warn("setEnergyMode(" + request.mode + ") generation="
                            + request.generation
                            + " reached target but durable completion was unavailable");
                }
                return committed;
            }
            remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                Thread.sleep(Math.min(ENERGY_APPLY_POLL_MS, remaining));
            } catch (InterruptedException e) {
                cancelEnergyRequest(request, "physical verification wait");
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn("setEnergyMode(" + request.mode + ") generation=" + request.generation
                + " did not reach target; axis=" + energyModeName(seen));
        return false;
    }

    /** Uncached OEM preference read, with the legacy runtime axis as a compatibility fallback. */
    private int readEnergyModeRawOn(Object device, long timeoutMs) {
        if (device == null) return BydVehicleData.UNAVAILABLE;
        Integer result = callEnergyHalBounded(
                ENERGY_READ_EXECUTOR,
                () -> readEnergyModeRawOnDirect(device),
                timeoutMs,
                Integer.valueOf(BydVehicleData.UNAVAILABLE),
                ENERGY_READ_STALLED,
                "energy-mode readback");
        return result != null ? result.intValue() : BydVehicleData.UNAVAILABLE;
    }

    private int readEnergyModeRawOnDirect(Object device) {
        int selected = VehicleActuatorBridge.energyModeForMandatoryElectricState(
                VehicleActuatorBridge.readMandatoryElectricState(device));
        if (selected > 0) return selected;
        try {
            Object result = BydDeviceHelper.callGetter(device, "getEnergyMode");
            if (result instanceof Number) return ((Number) result).intValue();
        } catch (Exception e) {
            logger.debug("readEnergyModeRawOn failed: " + e.getMessage());
        }
        return BydVehicleData.UNAVAILABLE;
    }

    private static final class EnergySetterOutcome {
        final boolean accepted;
        final boolean verificationHandedOff;

        EnergySetterOutcome(boolean accepted, boolean verificationHandedOff) {
            this.accepted = accepted;
            this.verificationHandedOff = verificationHandedOff;
        }
    }

    private EnergySetterOutcome invokeOptionalModeSetterBounded(
            EnergyRequest request,
            Object device,
            String methodName,
            int value,
            String label,
            long deviceResolutionSequence) {
        if (request.cancelled || Thread.currentThread().isInterrupted()) {
            cancelEnergyRequest(request, "before daemon setter submission");
            return new EnergySetterOutcome(false, false);
        }
        EnergySetterTask task =
                energySetterLane.submit(
                        request,
                        device,
                        methodName,
                        value,
                        label,
                        deviceResolutionSequence);
        Boolean result = task.await(ENERGY_SETTER_TIMEOUT_MS);
        if (Thread.currentThread().isInterrupted()) {
            energySetterLane.cancel(task);
            return new EnergySetterOutcome(false, false);
        }
        if (result == null) {
            logger.warn(methodName + "(" + value + ") exceeded "
                    + ENERGY_SETTER_TIMEOUT_MS
                    + "ms; fixed setter lane retains only the latest corrective request");
        }
        return new EnergySetterOutcome(
                Boolean.TRUE.equals(result),
                result == null && task.callerTimedOut());
    }

    private boolean isDaemonSetterTaskCurrent(EnergySetterTask task) {
        if (task == null) return false;
        VehicleActuatorBridge.PublishedEnergyRead read =
                VehicleActuatorBridge.readPublishedEnergyState(context);
        VehicleActuatorBridge.PublishedEnergyRequest marker = read.request;
        if (read.status != VehicleActuatorBridge.EnergyReadStatus.VALID
                || marker == null) {
            return false;
        }
        if (task.compensation) {
            return marker.cancelled
                    && marker.rollbackPending
                    && marker.generation == task.request.generation
                    && marker.rollbackMode == task.value
                    && marker.rollbackOwner
                    == VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON;
        }
        if (marker.cancelled || marker.pending) return false;
        if (!VehicleActuatorBridge.isEnergyActuatorOwnershipAvailable(
                marker, VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON)) {
            return false;
        }
        if (marker.generation > task.request.generation) {
            adoptAuthoritativeEnergyRequest(marker);
            return false;
        }
        return !task.request.cancelled
                && marker.generation == task.request.generation
                && marker.mode == task.value;
    }

    private int readEnergyModeForSetterTask(
            EnergySetterTask task, long timeoutMs) {
        if (!isDaemonSetterTaskCurrent(task)) return BydVehicleData.UNAVAILABLE;
        int mode = readEnergyModeRawOn(task.device, timeoutMs);
        return isDaemonSetterTaskCurrent(task)
                ? mode : BydVehicleData.UNAVAILABLE;
    }

    private boolean awaitEnergySetterTask(
            EnergySetterTask task, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int seen = BydVehicleData.UNAVAILABLE;
        while (isDaemonSetterTaskCurrent(task)) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            seen = readEnergyModeForSetterTask(
                    task, Math.min(ENERGY_READ_TIMEOUT_MS, remaining));
            if (!isDaemonSetterTaskCurrent(task)) return false;
            if (seen == task.value) return true;
            remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            try {
                Thread.sleep(Math.min(ENERGY_APPLY_POLL_MS, remaining));
            } catch (InterruptedException interrupted) {
                task.cancel();
                Thread.currentThread().interrupt();
                return false;
            }
        }
        logger.warn("setEnergyMode(" + task.value + ") generation="
                + task.request.generation + " did not reach target in daemon lane; axis="
                + energyModeName(seen));
        return false;
    }

    private boolean invokeEnergyModeSetter(EnergySetterTask task) {
        if (task.device == null) {
            logger.warn(task.methodName + ": device unavailable — "
                    + task.label + " cannot be set");
            return false;
        }
        final Method method;
        try {
            method = task.device.getClass().getMethod(task.methodName, int.class);
        } catch (NoSuchMethodException absent) {
            logger.warn(task.methodName + ": method not present on "
                    + task.device.getClass().getSimpleName() + " — "
                    + task.label + " has no local SDK write path on this build");
            return false;
        } catch (Throwable failed) {
            logger.warn(task.methodName + ": lookup failed: " + failed.getMessage());
            return false;
        }
        try {
            Object result = task.invoke(this, method, task.device);
            if (result == EnergySetterTask.INVOCATION_SKIPPED) return false;
            return isSdkWriteSuccess(task.device, result, task.methodName);
        } catch (Throwable failed) {
            logger.warn(task.methodName + "(" + task.value + ") failed: "
                    + failed.getMessage());
            return false;
        }
    }

    private final class EnergySetterLane {
        private EnergySetterTask active;
        private EnergySetterTask pending;
        private boolean workerRunning;

        synchronized EnergySetterTask submit(
                EnergyRequest request,
                Object device,
                String methodName,
                int value,
                String label,
                long deviceResolutionSequence) {
            EnergySetterTask task =
                    new EnergySetterTask(
                            request,
                            device,
                            methodName,
                            value,
                            label,
                            deviceResolutionSequence,
                            device != null && device != energyDevice,
                            0,
                            false);
            replacePendingLocked(task);
            ensureWorkerLocked();
            return task;
        }

        synchronized void cancel(EnergySetterTask task) {
            task.cancel();
            if (pending == task) pending = null;
            task.complete(Boolean.FALSE);
        }

        synchronized void cancelThrough(long generation) {
            if (active != null && active.request.generation <= generation
                    && !(active.compensation
                    && active.request.generation == generation)) {
                active.cancel();
            }
            if (pending != null && pending.request.generation <= generation
                    && !(pending.compensation
                    && pending.request.generation == generation)) {
                EnergySetterTask cancelled = pending;
                pending = null;
                cancelled.cancel();
                cancelled.complete(Boolean.FALSE);
            }
        }

          synchronized boolean reconcileCancellation(
                  VehicleActuatorBridge.PublishedEnergyRequest marker) {
              if (marker == null || !marker.cancelled || !marker.rollbackPending
                      || marker.rollbackMode < 1 || marker.rollbackMode > ENERGY_MODE_KEEP
                    || marker.rollbackOwner
                    != VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON
                      || marker.compensationAttempts
                      >= VehicleActuatorBridge.MAX_ENERGY_COMPENSATION_ATTEMPTS) {
                  return false;
              }
              cancelThrough(marker.generation);
              if (hasOtherCompensationLocked(
                      null, marker.generation, marker.rollbackMode)) {
                  return true;
              }
            EnergyRequest request =
                    new EnergyRequest(marker.generation, marker.requestedMode);
            replacePendingLocked(new EnergySetterTask(
                    request,
                    energyDevice,
                    "setEnergyMode",
                    marker.rollbackMode,
                    "energy cancellation rollback ("
                            + energyModeName(marker.rollbackMode) + ")",
                    energyDeviceResolutionSequence.incrementAndGet(),
                    false,
                    0,
                      true));
              ensureWorkerLocked();
              return hasOtherCompensationLocked(
                      null, marker.generation, marker.rollbackMode);
          }

          synchronized boolean reconcileDesired(
                  VehicleActuatorBridge.PublishedEnergyRequest marker) {
              if (marker == null || marker.cancelled || marker.pending
                      || marker.mode < 1 || marker.mode > ENERGY_MODE_KEEP) {
                  return false;
              }
              if (!VehicleActuatorBridge.isEnergyActuatorOwnershipAvailable(
                      marker, VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON)) {
                  return true;
              }
              EnergyRequest request = adoptAuthoritativeEnergyRequest(marker);
              if (request == null) return false;
              cancelThrough(marker.generation - 1L);
              if (hasDesiredLocked(marker.generation, marker.mode)) return true;
              replacePendingLocked(new EnergySetterTask(
                      request,
                      energyDevice,
                      "setEnergyMode",
                      marker.mode,
                      "energy/powertrain mode (" + energyModeName(marker.mode)
                              + ") persisted reconciliation",
                      energyDeviceResolutionSequence.incrementAndGet(),
                      false,
                      1,
                      false));
              ensureWorkerLocked();
              return hasDesiredLocked(marker.generation, marker.mode);
          }

        private void drain() {
            while (true) {
                final EnergySetterTask task;
                synchronized (this) {
                    task = pending;
                    pending = null;
                    if (task == null) {
                        workerRunning = false;
                        return;
                    }
                    active = task;
                }

                boolean accepted = false;
                if (isDaemonSetterTaskCurrent(task)) {
                      if (!task.compensation) {
                          int previousMode =
                                  readEnergyModeForSetterTask(task, ENERGY_READ_TIMEOUT_MS);
                          if (previousMode == task.value) {
                              accepted = true;
                          } else if (previousMode >= 1
                                  && previousMode <= ENERGY_MODE_KEEP
                                  && VehicleActuatorBridge.beginEnergyActuation(
                                          context,
                                          task.request.generation,
                                        task.request.mode,
                                        previousMode,
                                        VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON)) {
                            task.setPreviousMode(previousMode);
                            accepted = invokeEnergyModeSetter(task);
                        } else {
                            logger.warn("setEnergyMode(" + task.value + ") generation="
                                    + task.request.generation
                                    + " skipped: pre-write mode or actuator ownership unavailable");
                        }
                          } else {
                              int currentMode =
                                      readEnergyModeForSetterTask(
                                              task, ENERGY_READ_TIMEOUT_MS);
                              if (currentMode == task.value) {
                                  task.markCompensationPreconfirmed();
                                  accepted = true;
                              } else {
                                  int claim =
                                          VehicleActuatorBridge.claimEnergyRollbackAttempt(
                                                  context,
                                                  task.request.generation,
                                                  task.value,
                                                  VehicleActuatorBridge
                                                  .ENERGY_ACTUATOR_DAEMON);
                                  task.setCompensationClaim(claim);
                                  if (claim > 0 && isDaemonSetterTaskCurrent(task)) {
                                      accepted = invokeEnergyModeSetter(task);
                                  } else {
                                      logger.warn("energy rollback generation="
                                              + task.request.generation
                                              + " was not invoked; shared compensation claim="
                                              + claim);
                                  }
                              }
                          }
                }
                boolean applied = false;
                boolean durableCompletion = false;
                boolean verifyInLane =
                        task.correctionAttempt > 0 || task.compensation;
                if (!verifyInLane) {
                    task.complete(Boolean.valueOf(accepted));
                    verifyInLane = task.callerTimedOut();
                }
                if (verifyInLane && !task.needsCompensation()) {
                    applied = awaitEnergySetterTask(
                            task, ENERGY_LOCAL_APPLY_TIMEOUT_MS);
                    if (applied) {
                        durableCompletion = task.compensation
                                ? VehicleActuatorBridge.completeEnergyRollback(
                                        context,
                                        task.request.generation,
                                        task.value)
                                : VehicleActuatorBridge.completeEnergyActuation(
                                        context,
                                        task.request.generation,
                                        task.value);
                    }
                }
                task.complete(Boolean.valueOf(accepted));

                boolean retained = false;
                if (task.compensation) {
                    if (task.compensationClaim() < 0
                            || task.wasCompensationPreconfirmed()
                            && (!applied || !durableCompletion)) {
                        task.requirePersistedReconciliation();
                    }
                    if ((!applied || !durableCompletion)
                            && task.compensationClaim() > 0) {
                        retained = retainAuthoritativeCorrection(task);
                    }
                } else if (task.needsCompensation()) {
                    retained = retainAuthoritativeCorrection(task);
                } else if (verifyInLane && (!applied || !durableCompletion)) {
                    retained = retainSameRequestCorrection(task);
                    if (!retained) retained = retainAuthoritativeCorrection(task);
                } else {
                    retained = retainAuthoritativeCorrection(task);
                }
                if (durableCompletion) {
                    notePersistedDaemonEnergyReconciliationProgress();
                }
                synchronized (this) {
                    // Atomic handoff with cancelThrough. If cancellation marked the active task
                    // before this monitor was acquired, retain its rollback here. If this block
                    // clears active first, the durable BEGIN metadata lets reconcileCancellation
                    // retain the same rollback afterward.
                    if (retained && (pending == null
                            || pending.request.generation
                            < task.request.generation)) {
                        retained = false;
                    }
                    if (!retained && task.needsCompensation()
                            && task.previousMode() >= 1
                            && task.previousMode() <= ENERGY_MODE_KEEP) {
                        VehicleActuatorBridge.PublishedEnergyRead read =
                                VehicleActuatorBridge.readPublishedEnergyState(context);
                        if (read.status
                                == VehicleActuatorBridge.EnergyReadStatus.VALID
                                && read.request != null
                                && read.request.cancelled
                                  && read.request.generation
                                  == task.request.generation
                                  && read.request.rollbackPending
                                  && !hasOtherCompensationLocked(
                                          task,
                                          read.request.generation,
                                          read.request.rollbackMode)) {
                              replacePendingLocked(new EnergySetterTask(
                                      new EnergyRequest(
                                              read.request.generation,
                                            read.request.requestedMode),
                                    task.device,
                                    "setEnergyMode",
                                    read.request.rollbackMode,
                                    "energy cancellation rollback ("
                                            + energyModeName(
                                                    read.request.rollbackMode)
                                            + ")",
                                    task.deviceResolutionSequence,
                                    task.freshDevice,
                                    0,
                                    true));
                            retained = true;
                        }
                    }
                    if (active == task) active = null;
                }
                if (task.needsPersistedReconciliation()) {
                    rearmPersistedDaemonEnergyReconciliation();
                }
            }
        }

        /**
         * A synthetic correction is not trusted on its setter return: this HAL is known to accept
         * ignored writes. Verify the physical axis and retain one fresh-handle retry when it did not
         * move, unless a real/newer request is already pending.
         */
        private boolean retainSameRequestCorrection(EnergySetterTask completed) {
            if (completed.correctionAttempt >= ENERGY_CORRECTION_MAX_ATTEMPTS) {
                logger.warn("setEnergyMode(" + completed.request.mode + ") generation="
                        + completed.request.generation
                        + " corrective retries exhausted without physical confirmation");
                return false;
            }
            if (!isDaemonSetterTaskCurrent(completed)) return false;
            EnergyRequest current = completed.request;

            long retryResolution = energyDeviceResolutionSequence.incrementAndGet();
            Object retryDevice = freshEnergyDevice();
            boolean retryFresh = retryDevice != null && retryDevice != energyDevice;
            synchronized (this) {
                if (!isDaemonSetterTaskCurrent(completed)) return false;
                if (pending != null
                        && pending.request.generation >= current.generation
                        && isAtLeastAsFresh(pending, retryFresh, retryResolution)) {
                    return false;
                }
                if (pending != null) pending.complete(Boolean.FALSE);
                pending = new EnergySetterTask(
                        current,
                        retryDevice,
                        "setEnergyMode",
                        current.mode,
                        "energy/powertrain mode (" + energyModeName(current.mode)
                                + ") corrective retry",
                        retryResolution,
                        retryFresh,
                        completed.correctionAttempt + 1,
                        completed.compensation);
                return true;
            }
        }

        private boolean retainAuthoritativeCorrection(EnergySetterTask completed) {
            VehicleActuatorBridge.PublishedEnergyRead read =
                    VehicleActuatorBridge.readPublishedEnergyState(context);
            VehicleActuatorBridge.PublishedEnergyRequest marker = read.request;
            if (read.status != VehicleActuatorBridge.EnergyReadStatus.VALID
                    || marker == null) {
                completed.requirePersistedReconciliation();
                return false;
            }
            if (marker.cancelled) {
                if (!marker.rollbackPending
                        || marker.generation < completed.request.generation
                        || marker.rollbackMode < 1
                        || marker.rollbackMode > ENERGY_MODE_KEEP
                        || marker.rollbackOwner
                        != VehicleActuatorBridge.ENERGY_ACTUATOR_DAEMON
                        || marker.compensationAttempts
                        >= VehicleActuatorBridge.MAX_ENERGY_COMPENSATION_ATTEMPTS) {
                    return false;
                  }
                  synchronized (this) {
                      if (hasOtherCompensationLocked(
                              completed,
                              marker.generation,
                              marker.rollbackMode)) {
                          return false;
                      }
                    replacePendingLocked(new EnergySetterTask(
                            new EnergyRequest(
                                    marker.generation, marker.requestedMode),
                            completed.device,
                            "setEnergyMode",
                            marker.rollbackMode,
                            "energy cancellation rollback ("
                                    + energyModeName(marker.rollbackMode) + ")",
                            completed.deviceResolutionSequence,
                            completed.freshDevice,
                            0,
                            true));
                    return true;
                }
            }
            if (marker.pending
                    || marker.generation < completed.request.generation
                    || marker.generation == completed.request.generation
                    && marker.mode == completed.value
                    && !completed.needsCompensation()) {
                return false;
            }
            EnergyRequest latest = adoptAuthoritativeEnergyRequest(marker);
            if (latest == null) return false;
            long correctionResolution =
                    energyDeviceResolutionSequence.incrementAndGet();
            Object correctionDevice = freshEnergyDevice();
            synchronized (this) {
                EnergyRequest current = energyModeCurrent.get();
                if (current != null && current.generation > latest.generation) latest = current;
                if (latest.cancelled) return false;
                boolean correctionFresh =
                        correctionDevice != null && correctionDevice != energyDevice;
                if (pending != null && pending.request.generation >= latest.generation) {
                    if (isAtLeastAsFresh(
                            pending, correctionFresh, correctionResolution)) {
                        return false;
                    }
                }
                replacePendingLocked(new EnergySetterTask(
                        latest,
                        correctionDevice,
                        "setEnergyMode",
                        latest.mode,
                        "energy/powertrain mode (" + energyModeName(latest.mode)
                                + ") corrective",
                        correctionResolution,
                        correctionFresh,
                        1,
                        false));
                return true;
              }
          }

          private boolean hasOtherCompensationLocked(
                  EnergySetterTask excluded, long generation, int mode) {
              return active != excluded && isCompensation(active, generation, mode)
                      || pending != excluded && isCompensation(pending, generation, mode);
          }

          private boolean hasDesiredLocked(long generation, int mode) {
              return isDesired(active, generation, mode)
                      || isDesired(pending, generation, mode);
          }

          private boolean isCompensation(
                  EnergySetterTask task, long generation, int mode) {
              return task != null
                      && task.compensation
                      && task.request.generation == generation
                      && task.value == mode;
          }

          private boolean isDesired(
                  EnergySetterTask task, long generation, int mode) {
              return task != null
                      && !task.compensation
                      && task.request.generation == generation
                      && task.value == mode;
          }

          private void replacePendingLocked(EnergySetterTask replacement) {
              if (pending != null && pending != replacement) {
                  pending.cancel();
                pending.complete(Boolean.FALSE);
            }
            pending = replacement;
        }

        private void ensureWorkerLocked() {
            if (workerRunning) return;
            workerRunning = true;
            try {
                Thread worker = new Thread(this::drain, "EnergyModeSetter");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable unavailable) {
                EnergySetterTask stranded = pending;
                pending = null;
                workerRunning = false;
                if (stranded != null) {
                    stranded.cancel();
                    stranded.complete(Boolean.FALSE);
                }
                logger.warn("energy setter worker could not start: "
                        + unavailable.getMessage());
            }
        }

        private boolean isAtLeastAsFresh(
                EnergySetterTask candidate, boolean freshDevice, long resolutionSequence) {
            return (candidate.freshDevice && !freshDevice)
                    || (candidate.freshDevice == freshDevice
                    && candidate.deviceResolutionSequence >= resolutionSequence);
        }
    }

    private static final class EnergySetterTask {
        static final Object INVOCATION_SKIPPED = new Object();
        final EnergyRequest request;
        final Object device;
        final String methodName;
        final int value;
        final String label;
        final long deviceResolutionSequence;
        final boolean freshDevice;
        final int correctionAttempt;
        final boolean compensation;
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.locks.ReentrantLock invocationGate =
                new java.util.concurrent.locks.ReentrantLock();
        private volatile boolean cancelled;
        private volatile boolean actuationStarted;
        private volatile boolean compensationRequired;
        private volatile boolean persistedReconciliationRequired;
        private volatile int previousMode = BydVehicleData.UNAVAILABLE;
        private volatile int compensationClaim = Integer.MIN_VALUE;
        private volatile boolean compensationPreconfirmed;
        private Boolean result;
        private boolean completed;
        private boolean callerTimedOut;

        EnergySetterTask(
                EnergyRequest request,
                Object device,
                String methodName,
                int value,
                String label,
                long deviceResolutionSequence,
                boolean freshDevice,
                int correctionAttempt,
                boolean compensation) {
            this.request = request;
            this.device = device;
            this.methodName = methodName;
            this.value = value;
            this.label = label;
            this.deviceResolutionSequence = deviceResolutionSequence;
            this.freshDevice = freshDevice;
            this.correctionAttempt = correctionAttempt;
            this.compensation = compensation;
        }

        Boolean await(long timeoutMs) {
            try {
                if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    synchronized (this) {
                        if (completed) return result;
                        callerTimedOut = true;
                        return null;
                    }
                }
                synchronized (this) {
                    return result;
                }
            } catch (InterruptedException interrupted) {
                cancel();
                Thread.currentThread().interrupt();
                return null;
            }
        }

        synchronized boolean callerTimedOut() {
            return callerTimedOut;
        }

        void setPreviousMode(int mode) {
            previousMode = mode;
        }

        int previousMode() {
            return previousMode;
        }

        boolean needsCompensation() {
            return compensationRequired;
        }

        void setCompensationClaim(int claim) {
            compensationClaim = claim;
        }

        int compensationClaim() {
            return compensationClaim;
        }

        void markCompensationPreconfirmed() {
            compensationClaim = 0;
            compensationPreconfirmed = true;
        }

        boolean wasCompensationPreconfirmed() {
            return compensationPreconfirmed;
        }

        void requirePersistedReconciliation() {
            persistedReconciliationRequired = true;
        }

        boolean needsPersistedReconciliation() {
            return persistedReconciliationRequired;
        }

        Object invoke(
                BydDataCollector owner, Method method, Object target) throws Exception {
            invocationGate.lock();
            try {
                if (cancelled || !owner.isDaemonSetterTaskCurrent(this)) {
                    return INVOCATION_SKIPPED;
                }
                actuationStarted = true;
                Object value = method.invoke(target, this.value);
                if (cancelled || !owner.isDaemonSetterTaskCurrent(this)) {
                    if (!compensation) compensationRequired = true;
                }
                return value;
            } finally {
                invocationGate.unlock();
            }
        }

        void cancel() {
            if (invocationGate.tryLock()) {
                try {
                    cancelled = true;
                    if (actuationStarted && !compensation) {
                        compensationRequired = true;
                    }
                } finally {
                    invocationGate.unlock();
                }
                return;
            }
            cancelled = true;
            if (!compensation) compensationRequired = true;
        }

        synchronized void complete(Boolean value) {
            if (completed) return;
            completed = true;
            result = value;
            done.countDown();
        }
    }

    /**
     * Run one potentially blocking energy HAL call on a process-lifetime, one-thread lane.
     * Binder calls can ignore interruption; a permanently blocked call therefore strands at most
     * that lane. Later calls fail fast while it remains occupied, then resume once it unwinds,
     * instead of leaking another thread or blocking the automation worker beyond its deadline.
     */
    private <T> T callEnergyHalBounded(
            java.util.concurrent.ExecutorService executor,
            java.util.concurrent.Callable<T> task,
            long timeoutMs,
            T fallback,
            java.util.concurrent.atomic.AtomicBoolean stalled,
            String operation) {
        java.util.concurrent.ThreadPoolExecutor lane =
                (java.util.concurrent.ThreadPoolExecutor) executor;
        if (stalled.get()) {
            if (lane.getActiveCount() != 0) {
                logger.debug(operation + " skipped while its bounded HAL lane is stalled");
                return fallback;
            }
            stalled.set(false);
        }
        final java.util.concurrent.Future<T> call;
        try {
            call = executor.submit(task);
        } catch (Throwable unavailable) {
            logger.debug(operation + " skipped while its bounded HAL lane is occupied");
            return fallback;
        }
        try {
            T result = call.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            stalled.set(false);
            return result;
        } catch (java.util.concurrent.TimeoutException timeout) {
            call.cancel(true);
            lane.remove((Runnable) call);
            if (stalled.compareAndSet(false, true)) {
                logger.warn(operation + " exceeded " + timeoutMs
                        + "ms; later calls fail fast until the HAL lane unwinds");
            }
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            lane.remove((Runnable) call);
            Thread.currentThread().interrupt();
        } catch (Throwable failed) {
            call.cancel(true);
            lane.remove((Runnable) call);
            stalled.set(false);
            logger.warn(operation + " failed: " + failed.getMessage());
        }
        return fallback;
    }

    // BYDAutoEnergyDevice energy-mode enum, from the OFFICIAL SDK constants (and the
    // onEnergyModeChanged javadoc: Stop / Electric / Fuel / Force EV / HEV). Only EV and HEV
    // were previously representable, so a car sitting in any other mode published NO
    // powertrain state at all and no powertrain automation could match it.
    public static final int ENERGY_MODE_STOP = 0;
    public static final int ENERGY_MODE_EV = 1;
    public static final int ENERGY_MODE_FORCE_EV = 2;
    public static final int ENERGY_MODE_HEV = 3;
    public static final int ENERGY_MODE_FUEL = 4;
    public static final int ENERGY_MODE_KEEP = 5;

    /** Stable lowercase word for an energy mode, or "unknown" — shared by telemetry and logs. */
    public static String energyModeName(int mode) {
        switch (mode) {
            case ENERGY_MODE_STOP:     return "stop";
            case ENERGY_MODE_EV:       return "ev";
            case ENERGY_MODE_FORCE_EV: return "force_ev";
            case ENERGY_MODE_HEV:      return "hev";
            case ENERGY_MODE_FUEL:     return "fuel";
            case ENERGY_MODE_KEEP:     return "keep";
            default:                   return "unknown";
        }
    }

    /**
     * Invoke an SDK setter by name, tolerating a firmware that dropped or renamed
     * it. {@code set{Operation,Energy}Mode} ARE real BYDAutoEnergyDevice methods
     * (confirmed in the OEM implementation), but reflecting by name lets a variant
     * that lacks them fail loudly instead of silently. Probes for the method and:
     * <ul>
     *   <li>if present — invokes it and honors the SDK 0=success convention
     *       (a non-zero return is the HAL rejecting the write, e.g. sigperm);</li>
     *   <li>if absent — logs at WARN (so an SDK name/shape mismatch surfaces
     *       loudly in production instead of a silent {@code false}) and returns
     *       false, letting the command router treat the SDK leg as no-path.</li>
     * </ul>
     */
    private boolean invokeOptionalModeSetter(Object device, String methodName, int value, String label) {
        if (device == null) {
            logger.warn(methodName + ": device unavailable — " + label + " cannot be set");
            return false;
        }
        Method m;
        try {
            m = device.getClass().getMethod(methodName, int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn(methodName + ": method not present on " + device.getClass().getSimpleName()
                + " — " + label + " has no local SDK write path on this build; route via cloud/CAN instead");
            return false;
        } catch (Exception e) {
            logger.warn(methodName + ": lookup failed: " + e.getMessage());
            return false;
        }
        try {
            Object r = m.invoke(device, value);
            return isSdkWriteSuccess(device, r, methodName);
        } catch (Exception e) {
            logger.warn(methodName + "(" + value + ") failed: " + e.getMessage());
            return false;
        }
    }

    // Per-device cache of the resolved <FAMILY>_COMMAND_SUCCESS constant value, so
    // we reflect it once per device class rather than on every write.
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Integer> COMMAND_SUCCESS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int COMMAND_SUCCESS_UNKNOWN = Integer.MIN_VALUE;

    /**
     * Judge whether a BYD SDK named-setter result means success.
     *
     * <p>The SDK's setter methods return an int result code documented ONLY by
     * name — {@code <FAMILY>_COMMAND_SUCCESS} (e.g. {@code ENERGY_COMMAND_SUCCESS},
     * {@code SETTING_COMMAND_SUCCESS}) — whose NUMERIC value is NOT published in the
     * SDK docs and is NOT guaranteed to be 0. Sibling constants prove non-zero
     * success values exist on this platform (e.g. {@code CHARGING_SUCCESS=2},
     * {@code MALFUNCTION_OK=19}). So the old hardcoded {@code r == 0} test could
     * report FAILURE on a genuine success if the HAL's SUCCESS constant isn't 0 —
     * which, combined with the wrong enum values, is why drive/energy-mode writes
     * logged FAILED.
     *
     * <p>Resolution order (first that applies):
     * <ol>
     *   <li>Reflect the device class's {@code <FAMILY>_COMMAND_SUCCESS} field and
     *       compare the result to it — correct-by-construction, immune to whatever
     *       value BYD chose. The family prefix is derived from the device's simple
     *       class name (BYDAutoEnergyDevice → ENERGY, BYDAutoSettingDevice →
     *       SETTING).</li>
     *   <li>If that constant can't be resolved, fall back to {@code code >= 0} —
     *       the SAME non-inverting convention the proven-working generic write path
     *       ({@link BydDeviceHelper#sendSetCommand}) uses, whose documented failure
     *       code is a large negative ({@code -2147482648}). This never inverts a
     *       real success the way {@code == 0} can.</li>
     *   <li>A {@code Boolean} result maps true→success. A null/void result is
     *       treated as success (the call returned without throwing) — matching
     *       {@code sendSetCommandRaw}'s "non-null result, assume success".</li>
     * </ol>
     */
    private boolean isSdkWriteSuccess(Object device, Object result, String methodName) {
        if (result instanceof Boolean) return (Boolean) result;
        if (!(result instanceof Integer)) {
            // void / null / unexpected type: the invoke didn't throw, so treat as
            // accepted (mirrors BydDeviceHelper.sendSetCommandRaw's assume-success).
            return true;
        }
        int code = (Integer) result;
        int success = resolveCommandSuccess(device.getClass());
        if (success != COMMAND_SUCCESS_UNKNOWN) {
            return code == success;
        }
        // No resolvable SUCCESS constant → use the working generic-path convention.
        return code >= 0;
    }

    /** Resolve and cache {@code <FAMILY>_COMMAND_SUCCESS} for a BYD device class,
     *  or {@link #COMMAND_SUCCESS_UNKNOWN} if none is exposed. */
    private int resolveCommandSuccess(Class<?> deviceClass) {
        Integer cached = COMMAND_SUCCESS_CACHE.get(deviceClass);
        if (cached != null) return cached;
        int resolved = COMMAND_SUCCESS_UNKNOWN;
        try {
            // BYDAutoEnergyDevice → "ENERGY"; BYDAutoSettingDevice → "SETTING".
            String simple = deviceClass.getSimpleName(); // e.g. BYDAutoEnergyDevice
            // Locale.ROOT: a Turkish default locale maps "Setting" to "SETTİNG" (dotted İ), missing
            // the field and silently degrading this judge to its >=0 fallback.
            String family = simple.replaceFirst("^BYDAuto", "").replaceFirst("Device$", "")
                    .toUpperCase(java.util.Locale.ROOT);
            java.lang.reflect.Field f = deviceClass.getField(family + "_COMMAND_SUCCESS");
            Object v = f.get(null);
            if (v instanceof Integer) resolved = (Integer) v;
        } catch (Throwable ignored) {
            // No such constant on this build — fall back to >= 0 (handled by caller).
        }
        COMMAND_SUCCESS_CACHE.put(deviceClass, resolved);
        if (resolved != COMMAND_SUCCESS_UNKNOWN) {
            logger.info("Resolved " + deviceClass.getSimpleName() + " COMMAND_SUCCESS=" + resolved);
        }
        return resolved;
    }

    /**
     * Energy recuperation / regen-braking strength (BYDAutoSettingDevice).
     *
     * <p>Takes a normalized user level 0..2 (0 = standard/low, 1 = high/large,
     * 2 = max) and converts it to the raw MCU value the HAL expects. Per the OEM
     * firmware's regen-level mapping: the SDK's
     * {@code setEnergyFeedback} wants MCU values {@code 2/3/4}, NOT {@code 0/1/2}.
     * The earlier mapping sent {@code standard=1} (below the valid MCU range — a
     * silent no-op) and {@code high=2} (which is the HAL's *standard* MCU value), so
     * "standard did nothing" and "high set standard" — exactly the observed bug.
     */
    public boolean setEnergyFeedback(int level) {
        if (settingDevice == null) {
            logger.warn("setEnergyFeedback: settingDevice unavailable");
            return false;
        }
        // Normalize the user level (0..2) then map to the MCU value: 0->2, 1->3, 2->4.
        int normalized = Math.max(0, Math.min(2, level));
        int mcuValue = normalized + 2;
        // Probe by name (like setSteerAssist) so an SDK rename of
        // "setEnergyFeedback" surfaces at WARN instead of silently returning
        // NOT_SUPPORTED for every regen_level command.
        Method m;
        try {
            m = settingDevice.getClass().getMethod("setEnergyFeedback", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setEnergyFeedback: method not present on "
                + settingDevice.getClass().getSimpleName()
                + " — regen/energy-recuperation strength unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setEnergyFeedback lookup failed: " + e.getMessage());
            return false;
        }
        try {
            Object r = m.invoke(settingDevice, mcuValue);
            return isSdkWriteSuccess(settingDevice, r, "setEnergyFeedback");
        } catch (Exception e) {
            logger.warn("setEnergyFeedback(level=" + level + " mcu=" + mcuValue + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Steering-assist weighting: comfort vs sport (BYDAutoSettingDevice). */
    public boolean setSteerAssist(int mode) {
        if (settingDevice == null) return false;
        // Method name must match the SDK exactly — the earlier target string
        // "setSteerAssist" (with the trailing 't') resolved to nothing, so every
        // steering-mode command silently failed as NOT_SUPPORTED. The real
        // BYDAutoSettingDevice method is `public int setSteerAssis(int value)`
        // (no trailing 't'). Probe the correct name first and fall back to the
        // with-'t' spelling in case a future SDK settles on the other stem, so a
        // genuine rename surfaces at WARN instead of returning false.
        Method m = null;
        try {
            m = settingDevice.getClass().getMethod("setSteerAssis", int.class);
        } catch (NoSuchMethodException nsme) {
            try {
                m = settingDevice.getClass().getMethod("setSteerAssist", int.class);
            } catch (NoSuchMethodException nsme2) {
                logger.warn("setSteerAssis: method not present on "
                    + settingDevice.getClass().getSimpleName()
                    + " — steering-assist weighting unsupported on this OEM build");
                return false;
            } catch (Exception e) {
                logger.warn("setSteerAssist lookup failed: " + e.getMessage());
                return false;
            }
        } catch (Exception e) {
            logger.warn("setSteerAssis lookup failed: " + e.getMessage());
            return false;
        }
        try {
            Object r = m.invoke(settingDevice, mode);
            return isSdkWriteSuccess(settingDevice, r, "setSteerAssis");
        } catch (Exception e) {
            logger.warn("setSteerAssis(" + mode + ") failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setNavigationActive(boolean active) {
        try {
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVIGATION_ACTIVATED_SET, active ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setNavigationActive failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Brake-pedal feel ("comfort" vs "sport"/"strong") via {@code
     * BYDAutoADASDevice.setBrakeFootSenseState(int)}. Value convention per the OEM
     * firmware: the app-level choice {@code 0 = comfort / 1 = sport} maps to HAL
     * values {@code comfort → 2}, {@code sport → 0}. This method takes the app-level
     * 0/1 and applies that mapping, so callers use the same 0=comfort/1=sport
     * convention the UI shows.
     *
     * <p>Probed by name on {@code adasDevice} (same device the SLW/ESP controls use)
     * so an SDK rename surfaces at WARN instead of silently no-opping. Returns false
     * when the ADAS device is unavailable or the write path threw.
     */
    public boolean setBrakeFootSense(int appLevel) {
        if (adasDevice == null) {
            logger.warn("setBrakeFootSense: adasDevice unavailable");
            return false;
        }
        // App-level 0=comfort/1=sport → HAL 2=comfort/0=sport (OEM firmware mapping).
        int mcuValue = (appLevel == 0) ? 2 : 0;
        Method m;
        try {
            m = adasDevice.getClass().getMethod("setBrakeFootSenseState", int.class);
        } catch (NoSuchMethodException nsme) {
            logger.warn("setBrakeFootSenseState: method not present on "
                + adasDevice.getClass().getSimpleName()
                + " — brake-feel control unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setBrakeFootSenseState lookup failed: " + e.getMessage());
            return false;
        }
        try {
            // Accept-on-no-throw (matches the OEM vehicle-control app's setBrakeAssistMode and
            // our ADAS-reflection contract) — do NOT gate on the SDK return, which can be a
            // benign non-zero/negative code the isSdkWriteSuccess helper reads as failure.
            m.invoke(adasDevice, mcuValue);
            logger.info("setBrakeFootSenseState(app=" + appLevel + " mcu=" + mcuValue + ") invoked");
            return true;
        } catch (Exception e) {
            logger.warn("setBrakeFootSenseState(app=" + appLevel + " mcu=" + mcuValue + ") failed: " + e.getMessage());
            return false;
        }
    }

    // ── Drive-mode readbacks (for toggle/cycle + UI state) ────────────────────
    // These mirror the OEM firmware's own getters so a "toggle" flips the ACTUAL
    // current mode (including one set from the car's own menu), not a stale cache.
    // Each returns the APP-LEVEL value (matching the corresponding setter's input),
    // or a negative sentinel when unavailable so the caller can fall back gracefully.

    /**
     * Current brake-pedal feel as app-level {@code 0=comfort / 1=sport}, or -1 if
     * unavailable. Reads {@code BYDAutoADASDevice.getBrakeFootSenseState()} and applies
     * the OEM firmware's own mapping: HAL {@code 2 → comfort(0)}, anything else {@code
     * → sport(1)} (matching setBrakeFootSense's inverse comfort→2/sport→0).
     */
    public int getBrakeFootSense() {
        if (adasDevice == null) return -1;
        try {
            Method m = adasDevice.getClass().getMethod("getBrakeFootSenseState");
            Object r = m.invoke(adasDevice);
            if (!(r instanceof Number)) return -1;
            int hal = ((Number) r).intValue();
            if (hal == BydFeatureIds.SDK_NOT_AVAILABLE) return -1;
            // HAL 2 = comfort → app 0; anything else → sport (app 1). Mirrors the OEM.
            return (hal == 2) ? 0 : 1;
        } catch (NoSuchMethodException nsme) {
            return -1; // getter absent on this trim
        } catch (Exception e) {
            logger.debug("getBrakeFootSense failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Current energy-recuperation (regen) level as app-level {@code 0=standard / 1=high
     * / 2=max}, or -1 if unavailable. Reads {@code BYDAutoSettingDevice.getEnergyFeedback()}
     * (raw HAL MCU value 2/3/4) and undoes setEnergyFeedback's {@code app+2} mapping.
     */
    public int getEnergyFeedback() {
        if (settingDevice == null) return -1;
        try {
            Method m = settingDevice.getClass().getMethod("getEnergyFeedback");
            Object r = m.invoke(settingDevice);
            if (!(r instanceof Number)) return -1;
            int mcu = ((Number) r).intValue();
            if (mcu == BydFeatureIds.SDK_NOT_AVAILABLE) return -1;
            int app = mcu - 2; // inverse of setEnergyFeedback's normalized+2
            return (app >= 0 && app <= 2) ? app : -1;
        } catch (NoSuchMethodException nsme) {
            return -1;
        } catch (Exception e) {
            logger.debug("getEnergyFeedback failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Current steering-assist weighting as app-level {@code 0=comfort / 1=sport}, or -1
     * if unavailable. Reads {@code BYDAutoSettingDevice.getSteerAssis()} and applies the
     * OEM firmware's own mapping: HAL {@code 1 → comfort(0)}, HAL {@code 2 → sport(1)}.
     */
    public int getSteerAssist() {
        if (settingDevice == null) return -1;
        try {
            Method m;
            try {
                m = settingDevice.getClass().getMethod("getSteerAssis");
            } catch (NoSuchMethodException nsme) {
                m = settingDevice.getClass().getMethod("getSteerAssist"); // with-'t' fallback
            }
            Object r = m.invoke(settingDevice);
            if (!(r instanceof Number)) return -1;
            int hal = ((Number) r).intValue();
            if (hal == 1) return 0;      // comfort
            if (hal == 2) return 1;      // sport
            return -1;                    // unknown / unavailable
        } catch (NoSuchMethodException nsme) {
            return -1;
        } catch (Exception e) {
            logger.debug("getSteerAssist failed: " + e.getMessage());
            return -1;
        }
    }

    public boolean setNavigationETA(int minutes) {
        try {
            if (minutes < 0) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVI_ESTIMATED_TIME_SET, minutes);
        } catch (Exception e) {
            logger.debug("setNavigationETA failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setNavigationDistance(int meters) {
        try {
            if (meters < 0) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVI_ESTIMATED_MILEAGE_SET, meters);
        } catch (Exception e) {
            logger.debug("setNavigationDistance failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Log a summary of all current values (for debugging).
     */
    public void logSummary() {
        BydVehicleData d = snapshot.get();
        if (d == null) {
            logger.info("No data collected yet");
            return;
        }
        logger.info("=== BYD Vehicle Data Summary ===");
        if (d.vin != null) logger.info("  VIN: " + d.vin);
        if (!Double.isNaN(d.socPercent)) logger.info("  SOC: " + d.socPercent + "%");
        else logger.warn("  SOC: UNAVAILABLE (statistic/energy devices returned blank)");
        if (!Double.isNaN(d.voltage12v)) logger.info("  12V: " + d.voltage12v + "V");
        if (!Double.isNaN(d.remainKwh)) logger.info("  Remaining: " + d.remainKwh + " kWh");
        if (!Double.isNaN(d.speedKmh)) logger.info("  Speed: " + d.speedKmh + " km/h");
        if (d.gearMode != BydVehicleData.UNAVAILABLE) logger.info("  Gear: " + d.gearMode);
        if (d.totalMileageKm != BydVehicleData.UNAVAILABLE) logger.info("  Odometer: " + d.totalMileageKm + " km");
        if (d.elecRangeKm != BydVehicleData.UNAVAILABLE) logger.info("  EV Range: " + d.elecRangeKm + " km");
        if (!Double.isNaN(d.highCellTempC)) logger.info("  Cell Temp: " + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C");
        if (!Double.isNaN(d.highCellVoltage)) logger.info("  Cell Voltage: " + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
        if (!Double.isNaN(d.outsideTempC)) logger.info("  Outside: " + d.outsideTempC + "°C");
        if (d.tyrePressure != null) logger.info("  Tyres: FL=" + d.tyrePressure[0] + " FR=" + d.tyrePressure[1] + " RL=" + d.tyrePressure[2] + " RR=" + d.tyrePressure[3]);
        if (d.powerLevel != BydVehicleData.UNAVAILABLE) logger.info("  Power Level: " + d.powerLevel);
        logger.info("  Devices: " + d.availableDevices.length + " available");
        logger.info("================================");
    }
}
