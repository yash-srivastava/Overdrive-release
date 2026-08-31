package com.overdrive.app.abrp;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.VehicleDataMonitor;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * Shape B SOH estimator.
 *
 * Live SOH = 100 × (remainKwh / SOC%) ÷ nominalKwh.
 * Calibration is recorded as a SEPARATE displayed-only anchor (not blended).
 *
 * Nominal capacity precedence (read at init() and on every getStatus()):
 *   1. User-set kWh from UnifiedConfigManager.vehicle.nominalKwh
 *   2. Auto-detected kWh persisted to /data/local/tmp/abrp_soh_estimate.properties
 *   3. Auto-detection probes (BMS-Ah → SOC heuristic → model string → pack voltage)
 */
public class SohEstimator {

    private static final String TAG = "SohEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private double nominalCapacityKwh = 0;
    private String nominalSource = "unset"; // "user" | "auto" | "unset"

    private static final String SOH_FILE = ScratchPaths.path("abrp_soh_estimate.properties");
    private final File sohFile;
    private final PersistenceWriter persistenceWriter;
    private final UserNominalConfig userNominalConfig;

    enum PersistenceOutcome {
        FAILED,
        COMMITTED_DURABILITY_UNCERTAIN,
        DURABLE;

        boolean wasCommitted() {
            return this != FAILED;
        }
    }

    interface PersistenceWriter {
        PersistenceOutcome write(File destination, Properties properties)
                throws IOException;
    }

    interface UserNominalConfig {
        boolean write(Object value);
        double read();

        default void runUnderConfigLock(Runnable work) {
            work.run();
        }
    }

    private static final UserNominalConfig DEFAULT_USER_NOMINAL_CONFIG =
            new UserNominalConfig() {
                @Override
                public boolean write(Object value) {
                    return UnifiedConfigManager.updateValues(
                        "vehicle",
                        java.util.Collections.singletonMap(
                            "nominalKwh", value));
                }

                @Override
                public double read() {
                    return UnifiedConfigManager.readVehicleNominalKwhStrict();
                }

                @Override
                public void runUnderConfigLock(Runnable work) {
                    UnifiedConfigManager.runUnderConfigLock(() -> {
                        work.run();
                        return null;
                    });
                }
            };

    public SohEstimator() {
        this(new File(SOH_FILE));
    }

    SohEstimator(File sohFile) {
        this(
            sohFile,
            SohEstimator::persistPropertiesWithOutcome,
            DEFAULT_USER_NOMINAL_CONFIG);
    }

    SohEstimator(
            File sohFile,
            PersistenceWriter persistenceWriter,
            UserNominalConfig userNominalConfig) {
        if (sohFile == null) {
            throw new IllegalArgumentException("SOH persistence file is required");
        }
        if (persistenceWriter == null || userNominalConfig == null) {
            throw new IllegalArgumentException(
                "SOH persistence and config adapters are required");
        }
        this.sohFile = sohFile;
        this.persistenceWriter = persistenceWriter;
        this.userNominalConfig = userNominalConfig;
    }

    private static final String PROP_SOH_PERCENT = "soh_percent";
    private static final String PROP_LAST_UPDATED = "last_updated";
    private static final String PROP_NOMINAL_CAPACITY = "nominal_capacity_kwh";
    private static final String PROP_NOMINAL_SOURCE = "nominal_source";
    private static final String PROP_CALIBRATION_SOH = "calibration_soh";
    private static final String PROP_CALIBRATION_TIMESTAMP = "calibration_timestamp_ms";
    private static final String PROP_CAPACITY_AH_SOH = "capacity_ah_soh";
    private static final String PROP_CAPACITY_AH_TIMESTAMP = "capacity_ah_timestamp_ms";
    private static final String PROP_CAPACITY_AH_DISABLED = "capacity_ah_disabled";
    private static final String PROP_LIVE_HISTORY = "live_history";
    private static final String PROP_PEAK_REMAIN_KWH = "peak_remain_kwh";
    private static final String PROP_PEAK_REMAIN_KWH_SAMPLES = "peak_remain_kwh_samples";
    private static final String PROP_PEAK_REMAIN_KWH_TS = "peak_remain_kwh_ts";
    private static final String PROP_PEAK_REMAIN_KWH_NOTIFIED = "peak_remain_kwh_notified";
    private static final String PROP_SCHEMA_VERSION = "schema_version";
    private static final String PROP_STATE_CLEARED = "state_cleared";
    private static final String PROP_NOMINAL_IDENTITY = "nominal_identity";
    private static final String PROP_RESET_MODEL_EPOCH = "reset_model_epoch";
    private static final int CLEAR_TOMBSTONE_DURABILITY_ATTEMPTS = 3;
    // v3: PHEV peak-charge anchor — derives full-charge kWh by tracking max
    // remainKwh observed at SOC≥99%, giving a noise-robust SOH source on small
    // packs. remainKwh is gross-framed (corrected at the HAL read boundary), so
    // a healthy pack's peak ≈ gross nominal (ratio ≈ 1.0); the legacy
    // frame-mismatch flag effectively no longer fires for the old half-scale
    // reason but is retained as a guard against a genuinely wrong user nominal.
    private static final int CURRENT_SCHEMA_VERSION = 3;

    private static final int LIVE_HISTORY_SIZE = 10;
    private final java.util.ArrayDeque<Double> liveHistory = new java.util.ArrayDeque<>(LIVE_HISTORY_SIZE);

    // SOH bounds. A battery's State of Health is, by definition, capacity
    // relative to a NEW pack — it CANNOT exceed 100%. Earlier code railed at
    // 110% to allow measurement slop, but that 10% headroom let a BMS that
    // reads slightly above the user-entered nominal at full charge (e.g. 22.4
    // kWh measured vs 21.5 entered → 104%, or a stale full-charge getter held
    // while SOC dropped → railed 110%) report >100% SOH. That impossible value
    // then multiplied into BOTH the SOH display AND the synthesized remaining
    // kWh / per-trip consumption (computedKwh = SOC × nominal × SOH/100),
    // inflating every downstream number by up to 10%. Capping at 100% is the
    // physically-correct ceiling and removes that inflation: a healthy pack
    // reads exactly 100%, a degraded one reads below. The 60% floor stays —
    // genuine degradation lives below nominal. Values above the cap are clamped
    // to 100, not discarded, so a slightly-high reading still yields a usable
    // (healthy) estimate instead of none.
    private static final double MAX_SOH = 100.0;
    private static final double MIN_SOH = 60.0;

    private double currentSoh = -1;
    private double calibrationSoh = -1;
    private long calibrationTimestampMs = 0;

    // Capacity-Ah anchor — PHEV-only secondary SOH source. The BMS reports
    // its full-charge Ah counter directly via getBatteryCapacity(); on PHEVs
    // this gives a parallel SOH read that's independent of the noisier
    // remainKwh / SOC live formula. Stored as an anchor (like calibrationSoh)
    // — never blends into currentSoh, displayed separately in /api/performance/soh.
    //
    // Disabled when the BMS is observed returning the static nameplate Ah
    // for several consecutive readings (firmware not coulomb-counting).
    private double capacityAhSoh = -1;
    private long capacityAhTimestampMs = 0;
    private double lastCapacityAhReading = -1;
    private int capacityAhNameplateMatchCount = 0;
    private boolean capacityAhDisabled = false;
    private static final int CAPACITY_AH_NAMEPLATE_TRIPS = 5;
    private static final double CAPACITY_AH_NAMEPLATE_TOLERANCE_AH = 0.5;

    // SOC-coupling detector. If the BMS reports getBatteryCapacity() in
    // 0.1-kWh-remaining units (older firmware semantic), the value walks with
    // SOC instead of staying flat at the full-charge Ah counter. Track the
    // first-seen Ah reading + SOC; if subsequent ticks show |Δah| moving with
    // |Δsoc|, latch the source off — it's not coulomb-counting.
    private double capacityAhFirstSocSeen = -1;
    private double capacityAhFirstAhSeen = -1;
    private int capacityAhSocCoupledCount = 0;
    private static final int CAPACITY_AH_SOC_COUPLED_TRIPS = 3;

    // Throttling state for the SOH-rail-saturation warning. Bumped on every
    // computeLiveSoh() that hits the 60% / 110% clamp; reset when a value lands
    // inside the rails. We log once per SATURATION_WARN_PERIOD consecutive
    // saturated samples so a wrong nominal pick is surfaced without flooding.
    private int saturationStreak = 0;
    private static final int SATURATION_WARN_PERIOD = 30;

    // True when fuel signals (getFuelPercentageValue / getFuelDrivingRangeValue)
    // are at BEV sentinels. Set by autoDetectCarModel before the SOC heuristic
    // runs so we can suppress the PHEV-kWh-bug detector on real BEVs whose
    // remainKwh happens to be numerically close to SOC% by coincidence.
    private boolean fuelSignalsLookBev = false;

    // Plausible BYD pack range. Smallest BEV-side is Sealion 6 DM-i PHEV at
    // 18.3 kWh; largest is Tang at 108.8 kWh. PHEV packs whose users want to
    // enter a usable-frame value (e.g. Tang DM-i ~12.9 kWh out of 21.5 nominal)
    // need a lower floor — the BMS-reported remainKwh + display SOC live in
    // the usable frame on those models, and the live SOH formula only matches
    // when nominalCapacityKwh is in the same frame.
    private static final double MIN_PLAUSIBLE_KWH = 15.0;
    private static final double MIN_PLAUSIBLE_KWH_PHEV = 8.0;
    private static final double MAX_PLAUSIBLE_KWH = 120.0;

    // PHEV-only peak-charge frame anchor. Tracks max remainKwh observed at
    // SOC≥99% across N samples. peakRemainKwhAtFull / nominalCapacityKwh × 100
    // yields a frame-aware SOH that survives the "user enters nameplate but
    // BMS reports usable" mismatch — when ratio is < 0.85 the dialog shows
    // a frame-mismatch warning so the user can correct the input.
    private static final int PEAK_REMAIN_KWH_REQUIRED_SAMPLES = 3;
    private static final double PEAK_REMAIN_KWH_FULL_SOC_THRESHOLD = 99.0;
    private double peakRemainKwhAtFull = -1;
    private int peakRemainKwhSamples = 0;
    private long peakRemainKwhTimestampMs = 0;
    // One-shot notified flag — flipped true the first time we publish the
    // frame-mismatch notification, cleared on every event that wipes the
    // anchor (reset / clearUserNominal / nominal change). Without this the
    // notification would re-fire every daemon restart.
    private boolean peakMismatchNotified = false;

    // Reset, seed, nominal-frame/source, and calibration mutations advance
    // this token. Long-running consumers can reject stale work even when a
    // reset/reseed cycle returns to the same numeric capacity and SOH.
    private long estimatorGeneration = 0;

    // Durable lineage for reset and nominal/model identity invalidations.
    // Unlike estimatorGeneration, this survives daemon restarts. Zero means
    // no durable lineage has been established and is never published by a
    // successfully initialized estimator.
    private long resetModelEpoch = 0;

    private enum InitializationState {
        NOT_STARTED,
        DEFERRED,
        READY
    }

    // A config read failure leaves user-nominal authority unknown. Keep that
    // state explicit so no heuristic can replace a preserved user-bound
    // snapshot before a later init() obtains an authoritative config read.
    private InitializationState initializationState =
        InitializationState.NOT_STARTED;

    // BYD Blade LFP reference cell voltage. 3.22 V derived from BYD's
    // published kWh / Ah / cellCount specs.
    private static final double BYD_BLADE_REFERENCE_CELL_VOLTAGE = 3.22;

    public void setNominalCapacityKwh(double capacityKwh) {
        synchronized (autoDetectLock) {
            setAutoNominalCapacityKwhLocked(capacityKwh, "auto");
        }
    }

    /**
     * Commit an auto-derived nominal as a nominal-only snapshot, then publish
     * the matching in-memory identity and cleared estimate state under the same
     * lock. A failed write leaves both the previous identity and its anchors
     * untouched.
     */
    private boolean setAutoNominalCapacityKwhLocked(
            double capacityKwh, String source) {
        if (initializationState != InitializationState.READY) {
            logger.warn("Auto nominal ignored while SOH initialization is "
                + initializationState);
            return false;
        }
        // Re-check user authority inside the mutation lock. Pack-voltage
        // detection performs work before reaching this method; a user write can
        // win during that work and must never be overwritten while retaining a
        // misleading "user" source.
        if ("user".equals(nominalSource)) {
            logger.info("Auto nominal ignored because a user override is active");
            return false;
        }

        // Drivetrain-aware floor. The flat BEV floor (15 kWh) made it impossible
        // for auto-detect to land a real PHEV pack.
        double floor = isPhevForCapacityFloor()
            ? MIN_PLAUSIBLE_KWH_PHEV : MIN_PLAUSIBLE_KWH;
        if (capacityKwh < floor || capacityKwh > MAX_PLAUSIBLE_KWH) {
            logger.warn("Rejecting implausible nominal capacity: " + capacityKwh
                + " kWh (valid range: " + floor + "-" + MAX_PLAUSIBLE_KWH + ")");
            return false;
        }

        String normalizedSource =
            source == null || source.isEmpty() ? "auto" : source;
        boolean identityChanged =
            !sameNominal(nominalCapacityKwh, capacityKwh)
                || !normalizedSource.equals(nominalSource);
        if (!identityChanged) return true;

        long replacementEpoch =
            nextResetModelEpoch(resetModelEpoch);
        RestoredState replacementState = new RestoredState();
        replacementState.nominalCapacityKwh = capacityKwh;
        replacementState.nominalSource = normalizedSource;
        replacementState.resetModelEpoch = replacementEpoch;
        Properties replacement =
            nominalOnlyProperties(replacementState, false);
        try {
            PersistenceOutcome outcome =
                publishPropertiesWithDurabilityRetries(
                    replacement, "change auto nominal identity");
            if (!outcome.wasCommitted()) return false;
            if (outcome != PersistenceOutcome.DURABLE) {
                initializationState = InitializationState.DEFERRED;
            }
        } catch (IOException writeFailure) {
            logger.error("Auto nominal persistence failed; retaining prior "
                + "identity: " + writeFailure.getMessage());
            return false;
        }

        double previous = nominalCapacityKwh;
        nominalCapacityKwh = capacityKwh;
        nominalSource = normalizedSource;
        clearEstimateStateLocked(true);
        resetModelEpoch = replacementEpoch;
        estimatorGeneration++;
        if (previous > 0 && !sameNominal(previous, capacityKwh)) {
            invalidateActiveTripKwhBaseline("auto nominal changed "
                + String.format("%.1f", previous) + "→"
                + String.format("%.1f", capacityKwh) + " kWh");
        }
        logger.info("Nominal capacity set to " + capacityKwh
            + " KWh (source=" + nominalSource + ")");
        return true;
    }

    private boolean clearAutoNominalLocked(String operation) {
        long replacementEpoch =
            nextResetModelEpoch(resetModelEpoch);
        PersistenceOutcome outcome =
            persistClearedStateTombstoneWithDurabilityRetries(
                operation, replacementEpoch);
        if (!outcome.wasCommitted()) {
            logger.error("Auto nominal clear deferred because its tombstone "
                + "could not be committed");
            return false;
        }

        double previous = nominalCapacityKwh;
        nominalCapacityKwh = 0;
        nominalSource = "unset";
        clearEstimateStateLocked(true);
        resetModelEpoch = replacementEpoch;
        estimatorGeneration++;
        if (previous > 0) {
            invalidateActiveTripKwhBaseline(operation);
        }
        if (outcome != PersistenceOutcome.DURABLE) {
            // Memory follows the committed rename, but no new auto state may be
            // published until init() re-reads the tombstone and establishes a
            // fresh durability boundary.
            initializationState = InitializationState.DEFERRED;
        }
        return true;
    }

    /**
     * Drivetrain probe for the nominal-capacity floor only. PHEV packs are far smaller than
     * any BEV pack, so the plausibility floor has to differ or a valid DM-i capacity looks
     * like junk. Best-effort: an unknown drivetrain falls back to the STRICTER BEV floor, so
     * a failed probe can never widen the accepted range.
     *
     * <p>Deliberately NOT called while holding a collector lock — {@code isPhevPublic()} can
     * reach back into other subsystems (same reasoning as {@link #getDisplaySoh()}). It is
     * called under {@code autoDetectLock}, which the collector never acquires.
     */
    private static boolean isPhevForCapacityFloor() {
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            return col != null && col.isInitialized() && col.isPhevPublic();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * User-driven override. Persists to UnifiedConfigManager so it survives
     * across daemon restarts AND across re-runs of autoDetectCarModel — only
     * clearUserNominal() can demote this back to "auto" / "unset".
     *
     * <p>The plausible floor is drivetrain-aware: BEV uses {@link #MIN_PLAUSIBLE_KWH}
     * (15 kWh), PHEV uses {@link #MIN_PLAUSIBLE_KWH_PHEV} (8 kWh) so users on
     * small Blade DM-i packs can enter usable-frame values like ~12.9 kWh.
     * The drivetrain hint is read from {@code BydDataCollector.isPhevPublic()};
     * if that probe fails the conservative BEV floor wins.
     */
    public void setNominalCapacityKwhFromUser(double capacityKwh) {
        boolean isPhev = false;
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                isPhev = col.isPhevPublic();
            }
        } catch (Throwable ignored) { /* default isPhev=false -> BEV floor */ }
        double floor = isPhev ? MIN_PLAUSIBLE_KWH_PHEV : MIN_PLAUSIBLE_KWH;
        if (capacityKwh < floor || capacityKwh > MAX_PLAUSIBLE_KWH) {
            logger.warn("Rejecting user nominal " + capacityKwh + " kWh — outside "
                + floor + "-" + MAX_PLAUSIBLE_KWH + " range (drivetrain="
                + (isPhev ? "PHEV" : "BEV") + ")");
            return;
        }

        final boolean[] shouldSeed = {false};
        userNominalConfig.runUnderConfigLock(() -> {
            final boolean configSaved;
            try {
                // UnifiedConfig is the authority. Its stable lock is acquired
                // before autoDetectLock and held through the matching SOH
                // identity publication.
                configSaved = userNominalConfig.write(capacityKwh);
            } catch (Throwable t) {
                throw new IllegalStateException(
                    "Failed to persist user nominalKwh to UnifiedConfig", t);
            }
            if (!configSaved) {
                throw new IllegalStateException(
                    "User nominal update deferred because UnifiedConfig is unavailable");
            }

            synchronized (autoDetectLock) {
                double previous = this.nominalCapacityKwh;
                String previousSource = this.nominalSource;
                boolean capacityChanged =
                    Math.abs(previous - capacityKwh) > 0.01;
                boolean identityChanged =
                    capacityChanged || !"user".equals(previousSource);
                long replacementEpoch = identityChanged
                    ? nextResetModelEpoch(resetModelEpoch)
                    : resetModelEpoch;

                PersistenceOutcome identityOutcome = PersistenceOutcome.DURABLE;
                if (identityChanged) {
                    RestoredState replacement = new RestoredState();
                    replacement.nominalCapacityKwh = capacityKwh;
                    replacement.nominalSource = "user";
                    replacement.resetModelEpoch = replacementEpoch;
                    try {
                        identityOutcome =
                            publishPropertiesWithDurabilityRetries(
                                nominalOnlyProperties(replacement, false),
                                "change user nominal identity");
                    } catch (IOException writeFailure) {
                        identityOutcome = PersistenceOutcome.FAILED;
                    }
                    if (!identityOutcome.wasCommitted()) {
                        initializationState = InitializationState.DEFERRED;
                        throw new IllegalStateException(
                            "User nominal identity persistence was not committed");
                    }
                }

                this.nominalCapacityKwh = capacityKwh;
                this.nominalSource = "user";
                this.resetModelEpoch = replacementEpoch;
                initializationState =
                    identityOutcome == PersistenceOutcome.DURABLE
                        ? InitializationState.READY
                        : InitializationState.DEFERRED;
                if (identityChanged) {
                    clearEstimateStateLocked(true);
                }
                if (capacityChanged) {
                    invalidateActiveTripKwhBaseline("user nominal changed " +
                        String.format("%.1f", previous) + "→" +
                        String.format("%.1f", capacityKwh) + " kWh");
                }
                if (identityChanged) {
                    estimatorGeneration++;
                } else {
                    persistEstimate();
                }
                logger.info("User-set nominal capacity: " + capacityKwh + " kWh");
                shouldSeed[0] = identityChanged || currentSoh <= 0;
                if (identityOutcome
                        == PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN) {
                    throw new IllegalStateException(
                        "User nominal identity committed but durability is uncertain");
                }
            }
        });

        // VehicleDataMonitor and drivetrain probes must run without carrying
        // autoDetectLock into another subsystem.
        if (shouldSeed[0]) {
            try {
                seedInitialEstimate();
            } catch (Throwable t) {
                logger.debug("seedInitialEstimate after user override failed: " + t.getMessage());
            }
        }
    }

    /**
     * Clear the user override. Drops the unified-config key and re-runs
     * auto-detection so the estimate falls back to whichever pack we can
     * identify from BMS / SOC / model / voltage.
     */
    public void clearUserNominal() {
        userNominalConfig.runUnderConfigLock(() -> {
            final boolean configCleared;
            try {
                configCleared = userNominalConfig.write(JSONObject.NULL);
            } catch (Throwable t) {
                throw new IllegalStateException(
                    "Failed to clear user nominalKwh from UnifiedConfig", t);
            }
            if (!configCleared) {
                throw new IllegalStateException(
                    "User nominal clear deferred because UnifiedConfig is unavailable");
            }

            synchronized (autoDetectLock) {
                long replacementEpoch =
                    nextResetModelEpoch(resetModelEpoch);
                PersistenceOutcome tombstoneOutcome =
                    persistClearedStateTombstoneWithDurabilityRetries(
                        "clear user nominal", replacementEpoch);
                if (!tombstoneOutcome.wasCommitted()) {
                    initializationState = InitializationState.DEFERRED;
                    throw new IllegalStateException(
                        "User nominal clear tombstone was not committed");
                }

                double previous = this.nominalCapacityKwh;
                this.nominalCapacityKwh = 0;
                this.nominalSource = "unset";
                clearEstimateStateLocked(true);
                resetModelEpoch = replacementEpoch;
                estimatorGeneration++;
                initializationState =
                    tombstoneOutcome == PersistenceOutcome.DURABLE
                        ? InitializationState.READY
                        : InitializationState.DEFERRED;
                if (previous > 0) {
                    invalidateActiveTripKwhBaseline("user nominal cleared (was "
                        + String.format("%.1f", previous) + " kWh)");
                }
                if (tombstoneOutcome != PersistenceOutcome.DURABLE) {
                    throw new IllegalStateException(
                        "User nominal clear committed but durability is uncertain");
                }
            }
        });

        try {
            android.content.Context ctx =
                com.overdrive.app.daemon.CameraDaemon.getAppContext();
            autoDetectCarModel(ctx);
        } catch (Throwable t) {
            logger.warn("Re-detect after clearUserNominal failed: " + t.getMessage());
        }
    }

    public double getNominalCapacityKwh() {
        return getNominalSnapshot().getNominalCapacityKwh();
    }

    public String getNominalSource() {
        return getNominalSnapshot().getNominalSource();
    }

    /**
     * Detect capacity from pack voltage (called by BydDataCollector on first HV voltage event).
     * Skips entirely when the user has set a nominal explicitly OR a value has
     * already been detected — pack voltage is the least reliable source.
     */
    public void autoDetectFromPackVoltage(double packVoltage, BydVehicleData vd) {
        if (packVoltage < 200 || packVoltage > 900) return;
        synchronized (autoDetectLock) {
            if (initializationState != InitializationState.READY
                    || "user".equals(nominalSource)) {
                return;
            }
            if (nominalCapacityKwh > 0) {
                logger.debug("Pack voltage " + String.format("%.1f", packVoltage) +
                    "V ignored — capacity already detected: " + nominalCapacityKwh + " kWh");
                return;
            }
            double cellVoltage = 3.2;
            int cellCount = (int) Math.round(packVoltage / cellVoltage);
            double capacity = mapCellCountToCapacity(cellCount);
            if (capacity > 0
                    && setAutoNominalCapacityKwhLocked(capacity, "auto")) {
                logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" +
                    String.format("%.1f", packVoltage) + "V, nominal cellV=3.2V" +
                    ", cells≈" + cellCount + "s)");
            } else if (capacity <= 0) {
                logger.debug("Pack voltage " + String.format("%.1f", packVoltage) + "V → " +
                    cellCount + " cells — no matching BYD pack");
            }
        }
    }

    // ==================== AUTO-DETECT ====================

    private static boolean isSentinelInt(int v) {
        return v == 255 || v == 254
            || v == 511 || v == 1023
            || v == 2046 || v == 2047
            || v == 4095
            || v == 65534 || v == 65535;
    }
    private static boolean isSentinelInt(Object o) {
        return (o instanceof Number) && isSentinelInt(((Number) o).intValue());
    }

    private static String describeException(Throwable e) {
        if (e == null) return "null";
        String msg = e.getMessage();
        if (msg != null && !msg.trim().isEmpty()) {
            return e.getClass().getSimpleName() + ": " + msg;
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) {
            return e.getClass().getSimpleName() + " (cause: "
                + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")";
        }
        return e.getClass().getSimpleName() + " (no message)";
    }

    private void dumpPhevDiagnostics(android.content.Context context) {
        fuelSignalsLookBev = false;
        boolean fuelPctSentinel = false;
        boolean fuelRangeSentinel = false;
        boolean fuelPctProbed = false;
        boolean fuelRangeProbed = false;
        try {
            logger.info("=== POWERTRAIN DIAGNOSTICS ===");

            try {
                String model = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.product.model", "");
                logger.info("[diag] ro.product.model = \"" + model + "\"");
            } catch (Exception e) {
                logger.info("[diag] ro.product.model: failed (" + describeException(e) + ")");
            }

            if (context == null) {
                logger.info("[diag] context==null — skipping HAL probes");
                logger.info("=== POWERTRAIN DIAGNOSTICS END ===");
                return;
            }

            try {
                Class<?> energyCls = Class.forName("android.hardware.bydauto.energy.BYDAutoEnergyDevice");
                Object energyDev = energyCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (energyDev != null) {
                    try {
                        Object em = energyCls.getMethod("getEnergyMode").invoke(energyDev);
                        String hint;
                        if (Integer.valueOf(1).equals(em)) hint = " (commonly EV — not authoritative)";
                        else if (Integer.valueOf(3).equals(em)) hint = " (commonly HEV — not authoritative; observed on BEV too)";
                        else hint = " (unknown code)";
                        logger.info("[diag] BYDAutoEnergyDevice.getEnergyMode = " + em + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getEnergyMode failed: " + describeException(e));
                    }
                    try {
                        Object om = energyCls.getMethod("getOperationMode").invoke(energyDev);
                        logger.info("[diag] BYDAutoEnergyDevice.getOperationMode = " + om);
                    } catch (Exception e) {
                        logger.info("[diag] getOperationMode failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoEnergyDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoEnergyDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoEnergyDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> chargingCls = Class.forName("android.hardware.bydauto.charging.BYDAutoChargingDevice");
                Object chargingDev = chargingCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (chargingDev != null) {
                    try {
                        Object cc = chargingCls.getMethod("getChargingCapacity").invoke(chargingDev);
                        logger.info("[diag] BYDAutoChargingDevice.getChargingCapacity = " + cc
                            + " (not used — observed 0.0 on every probed vehicle)");
                    } catch (Exception e) {
                        logger.info("[diag] getChargingCapacity failed: " + describeException(e));
                    }
                    try {
                        Object ct = chargingCls.getMethod("getChargingType").invoke(chargingDev);
                        logger.info("[diag] BYDAutoChargingDevice.getChargingType = " + ct);
                    } catch (Exception e) {
                        logger.info("[diag] getChargingType failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoChargingDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoChargingDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoChargingDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> statCls = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
                Object statDev = statCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (statDev != null) {
                    try {
                        Object fp = statCls.getMethod("getFuelPercentageValue").invoke(statDev);
                        fuelPctProbed = true;
                        String hint;
                        if (isSentinelInt(fp)) {
                            hint = " (sentinel — BEV / fuel unavailable)";
                            fuelPctSentinel = true;
                        } else if (fp instanceof Number) {
                            int v = ((Number) fp).intValue();
                            if (v >= 0 && v <= 100) hint = " (in 0..100 range — PHEV fuel level)";
                            else hint = " (out of expected 0..100 range — ignore)";
                        } else hint = "";
                        logger.info("[diag] BYDAutoStatisticDevice.getFuelPercentageValue = " + fp + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getFuelPercentageValue failed: " + describeException(e));
                    }
                    try {
                        Object fr = statCls.getMethod("getFuelDrivingRangeValue").invoke(statDev);
                        fuelRangeProbed = true;
                        String hint;
                        if (isSentinelInt(fr)) {
                            hint = " (sentinel — BEV / range unavailable)";
                            fuelRangeSentinel = true;
                        } else if (fr instanceof Number) {
                            int v = ((Number) fr).intValue();
                            if (v > 0 && v < 1500) hint = " km (real PHEV fuel range)";
                            else hint = " (out of expected 0..1500 km range)";
                        } else hint = "";
                        logger.info("[diag] BYDAutoStatisticDevice.getFuelDrivingRangeValue = " + fr + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getFuelDrivingRangeValue failed: " + describeException(e));
                    }
                    try {
                        Object sohi = statCls.getMethod("getStatisticBatteryHealthyIndex").invoke(statDev);
                        logger.info("[diag] BYDAutoStatisticDevice.getStatisticBatteryHealthyIndex = " + sohi);
                    } catch (Exception e) {
                        logger.info("[diag] getStatisticBatteryHealthyIndex failed: " + describeException(e));
                    }
                    try {
                        Object remPwr = statCls.getMethod("getRemainingBatteryPower").invoke(statDev);
                        logger.info("[diag] BYDAutoStatisticDevice.getRemainingBatteryPower = " + remPwr
                            + " (raw — divide by 10 if reported in 0.1 kWh units)");
                    } catch (Exception e) {
                        logger.info("[diag] getRemainingBatteryPower failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoStatisticDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoStatisticDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoStatisticDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> bodyCls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
                Object bodyDev = bodyCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (bodyDev != null) {
                    try {
                        Object cap = bodyCls.getMethod("getBatteryCapacity").invoke(bodyDev);
                        int rawCap = (cap instanceof Number) ? ((Number) cap).intValue() : -1;
                        String semHint;
                        if (isSentinelInt(rawCap)) semHint = " (sentinel — unavailable)";
                        else if (rawCap >= 50 && rawCap <= 350) semHint = " (likely Ah rating)";
                        else if (rawCap > 350 && rawCap < 60000) semHint = " (likely 0.1 kWh units → " + (rawCap / 10.0) + " kWh)";
                        else semHint = " (unknown semantics)";
                        logger.info("[diag] BYDAutoBodyworkDevice.getBatteryCapacity = " + cap + semHint);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryCapacity failed: " + describeException(e));
                    }
                    // getBatteryPowerHEV is the PHEV-priority remainKwh source in
                    // collectBodywork — yet it was the ONE battery getter this dump
                    // never logged. Field reports show it reading ~half the true
                    // remaining energy at full charge (9.1 on an 18.3 kWh pack,
                    // ratio≈0.497, constant across pack sizes). Log it raw next to
                    // SOC so a single ACC-on capture at a known SOC confirms whether
                    // the halving is in this getter specifically and whether it is a
                    // clean 2.0× vs a per-string / tenths artifact.
                    try {
                        Object hev = bodyCls.getMethod("getBatteryPowerHEV").invoke(bodyDev);
                        String hevHint = "";
                        if (hev instanceof Number) {
                            double hevVal = ((Number) hev).doubleValue();
                            try {
                                VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
                                BatterySocData sd = vdm != null ? vdm.getBatterySoc() : null;
                                if (sd != null && sd.socPercent > 0) {
                                    double impliedFull = hevVal / (sd.socPercent / 100.0);
                                    hevHint = " (raw kWh; soc=" + String.format("%.0f", sd.socPercent)
                                        + "% → impliedFull=" + String.format("%.1f", impliedFull)
                                        + " kWh, ×2=" + String.format("%.1f", hevVal * 2)
                                        + "; compare to gross nominal " + nominalCapacityKwh + " kWh)";
                                }
                            } catch (Exception ignored) {}
                        }
                        logger.info("[diag] BYDAutoBodyworkDevice.getBatteryPowerHEV = " + hev + hevHint);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryPowerHEV failed: " + describeException(e));
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoBodyworkDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoBodyworkDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> pwrCls = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice");
                Object pwrDev = pwrCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (pwrDev != null) {
                    try {
                        Object rp = pwrCls.getMethod("getBatteryRemainPowerEV").invoke(pwrDev);
                        logger.info("[diag] BYDAutoPowerDevice.getBatteryRemainPowerEV = " + rp);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryRemainPowerEV failed: " + describeException(e));
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoPowerDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoPowerDevice probe failed: " + describeException(e));
            }

            try {
                VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
                BydVehicleData vd = vdm != null ? vdm.getVd() : null;
                BatterySocData socData = vdm != null ? vdm.getBatterySoc() : null;
                double remKwh = vdm != null ? vdm.getBatteryRemainPowerKwh() : 0;
                logger.info("[diag] internal: socPercent="
                    + (socData != null ? socData.socPercent : "null")
                    + ", getBatteryRemainPowerKwh=" + remKwh
                    + ", vd.remainKwh=" + (vd != null ? vd.remainKwh : "null")
                    + ", vd.hvPackVoltage=" + (vd != null ? vd.hvPackVoltage : "null")
                    + ", vd.fuelPercent=" + (vd != null ? vd.fuelPercent : "null")
                    + ", currentNominalKwh=" + nominalCapacityKwh);
            } catch (Exception e) {
                logger.info("[diag] internal snapshot probe failed: " + describeException(e));
            }

            if (fuelPctProbed && fuelRangeProbed && fuelPctSentinel && fuelRangeSentinel) {
                fuelSignalsLookBev = true;
                logger.info("[diag] Inferred drivetrain: BEV (both fuel signals at sentinel — getEnergyType ignored)");
            }

            logger.info("=== POWERTRAIN DIAGNOSTICS END ===");
        } catch (Throwable t) {
            logger.warn("dumpPhevDiagnostics: unexpected error: " + describeException(t));
        }
    }

    private final Object autoDetectLock = new Object();

    public void autoDetectCarModel(android.content.Context context) {
        try {
            UnifiedConfigManager.runUnderConfigLock(() -> {
                double modelKwh = readModelNominalFromManifest();
                autoDetectCarModelFromConfigSnapshot(context, modelKwh);
                return null;
            });
        } catch (com.overdrive.app.server.ModelsApiHandler
                .SelectedModelConfigUnavailableException unavailable) {
            logger.warn("Model-based capacity detection deferred: "
                + unavailable.getMessage());
        }
    }

    /**
     * Run detection from a model nominal captured while the caller held the
     * stable config lock. This method never reads UnifiedConfig.
     */
    public void autoDetectCarModelFromConfigSnapshot(
            android.content.Context context, double modelKwh) {
        synchronized (autoDetectLock) {
            if (initializationState != InitializationState.READY) {
                logger.warn("autoDetectCarModel deferred while SOH initialization is "
                    + initializationState);
                return;
            }
            autoDetectCarModelInternal(context, modelKwh);
        }
    }

    private void autoDetectCarModelInternal(
            android.content.Context context, double modelKwh) {
        // User override always wins — never let auto-detect demote it.
        if ("user".equals(nominalSource) && nominalCapacityKwh > 0) {
            logger.info("autoDetectCarModel skipped — user override active ("
                + nominalCapacityKwh + " kWh)");
            return;
        }

        // User-selected vehicle model (set via the model picker) maps to a
        // canonical pack capacity in the manifest. This sits between the
        // explicit user kWh override (above) and the SOC heuristic (below)
        // — it's stronger than heuristics because the user told us which
        // car they have, but weaker than an explicit kWh value because
        // model variants exist (Seal Standard 61.4 kWh vs Premium 82.5 kWh).
        try {
            if (modelKwh >= MIN_PLAUSIBLE_KWH && modelKwh <= MAX_PLAUSIBLE_KWH) {
                if (setAutoNominalCapacityKwhLocked(
                        modelKwh, "user_model")) {
                    logger.info("autoDetectCarModel: nominal " + modelKwh
                        + " kWh from user-selected model");
                    return;
                }
            }
        } catch (Throwable t) {
            logger.debug("Model-manifest nominalKwh lookup failed: " + t.getMessage());
        }

        if (context == null) {
            try {
                context = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (context != null) {
                    logger.warn("autoDetectCarModel called with null context — recovered via CameraDaemon.getAppContext()");
                } else {
                    logger.warn("autoDetectCarModel: null context AND no app context available — HAL probes will be skipped");
                }
            } catch (Exception e) {
                logger.warn("autoDetectCarModel: failed to recover null context: " + describeException(e));
            }
        }

        dumpPhevDiagnostics(context);

        if (context != null) {
            double exactKwh = tryBmsExactCapacity(context);
            if (exactKwh > 0 && !contradictedBySocRatio(exactKwh)) {
                if (setAutoNominalCapacityKwhLocked(exactKwh, "auto")) {
                    return;
                }
            }
        }

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            double remainingKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (remainingKwh > 1.5 && socData != null && socData.socPercent >= 10) {
                double estimatedCapacity = remainingKwh / (socData.socPercent / 100.0);
                boolean likelyPhevKwhBug = !fuelSignalsLookBev
                        && Math.abs(remainingKwh - socData.socPercent) < 5.0;
                if (!fuelSignalsLookBev && !likelyPhevKwhBug
                        && remainingKwh > 40 && estimatedCapacity > 40
                        && nominalCapacityKwh <= 0) {
                    double socKwhRatio = remainingKwh / socData.socPercent;
                    if (socKwhRatio > 0.85 && socKwhRatio < 1.15) {
                        likelyPhevKwhBug = true;
                    }
                }
                if (likelyPhevKwhBug) {
                    logger.info("SOC heuristic skipped: remainKwh (" +
                        String.format("%.1f", remainingKwh) + ") ≈ socPercent (" +
                        String.format("%.1f", socData.socPercent) + ") — likely SOC-as-kWh firmware bug");
                } else if (nominalCapacityKwh > 0 && nominalCapacityKwh < 30 && estimatedCapacity > 40) {
                    logger.info("SOC heuristic skipped: estimated " + String.format("%.1f", estimatedCapacity) +
                        " kWh but nominal already detected as " + String.format("%.1f", nominalCapacityKwh) +
                        " kWh — PHEV remainKwh unreliable");
                } else {
                    double packV = Double.NaN;
                    BydVehicleData vd = vdm.getVd();
                    if (vd != null && !Double.isNaN(vd.hvPackVoltage)
                            && vd.hvPackVoltage > 200) {
                        packV = vd.hvPackVoltage;
                    }
                    double matched = matchNearestCapacity(
                        estimatedCapacity, packV, socData.socPercent);
                    if (matched > 0) {
                        if (setAutoNominalCapacityKwhLocked(
                                matched, "auto")) {
                            double snapDelta =
                                Math.abs(estimatedCapacity - matched);
                            boolean snapped = snapDelta > 0.5;
                            logger.info("SOC-derived nominal capacity: " + matched + " kWh"
                                + (snapped
                                    ? " (estimated " + String.format("%.1f", estimatedCapacity)
                                      + " kWh, snapped to nearest known pack)"
                                    : "")
                                + " [SOC=" + String.format("%.1f", socData.socPercent) + "%, remain="
                                + String.format("%.1f", remainingKwh) + " kWh]");
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("SOC heuristic failed: " + e.getMessage());
        }

        try {
            String carType = (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class)
                .invoke(null, "ro.product.model", "");
            if (carType != null && !carType.isEmpty()) {
                double mapped = mapCarTypeToCapacity(carType);
                if (mapped > 0) {
                    if (setAutoNominalCapacityKwhLocked(
                            mapped, "auto")) {
                        logger.info("Model-Mapped Capacity (" + carType + "): " + mapped + " kWh");
                        return;
                    }
                }
            }
        } catch (Exception e) { /* ignore */ }

        if (context != null) {
            double fuzzyKwh = tryBmsFuzzyCapacity(context);
            if (fuzzyKwh > 0 && !contradictedBySocRatio(fuzzyKwh)) {
                if (setAutoNominalCapacityKwhLocked(
                        fuzzyKwh, "auto")) {
                    return;
                }
            }
        }

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            BydVehicleData vd = vdm != null ? vdm.getVd() : null;
            if (vd != null && !Double.isNaN(vd.hvPackVoltage) && vd.hvPackVoltage > 200) {
                double voltage = vd.hvPackVoltage;
                double cellVoltage = 3.2;
                int cellCount = (int) Math.round(voltage / cellVoltage);
                double capacity = mapCellCountToCapacity(cellCount);
                if (capacity > 0) {
                    if (setAutoNominalCapacityKwhLocked(
                            capacity, "auto")) {
                        logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" +
                            String.format("%.1f", voltage) + "V, nominal cellV=3.2V" +
                            ", cells≈" + cellCount + "s)");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Pack voltage capacity lookup failed: " + e.getMessage());
        }

        if (nominalCapacityKwh > 0 && contradictedBySocRatio(nominalCapacityKwh)) {
            logger.warn("Persisted nominal " + nominalCapacityKwh
                + " kWh contradicted by current SOC ratio — clearing for re-detection on next cycle");
            clearAutoNominalLocked("discard contradicted auto nominal");
        }

        logger.warn("Capacity detection failed" +
            (nominalCapacityKwh > 0 ? " — using previously saved capacity: " + nominalCapacityKwh + " kWh"
                                    : " — SOH estimation disabled until capacity is identified"));
    }

    /**
     * Look up the canonical nominal kWh for the user-selected vehicle model
     * from the bundled/cached manifest. 0 if no selection, no value, or
     * manifest unavailable. Delegated to ModelsApiHandler so the manifest
     * cache/precedence rules stay in one place.
     */
    private double readModelNominalFromManifest() {
        return com.overdrive.app.server.ModelsApiHandler.nominalKwhForSelectedModel();
    }

    private boolean contradictedBySocRatio(double bmsKwh) {
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            if (vdm == null) return false;
            double remainKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (socData == null) return false;
            double soc = socData.socPercent;
            if (remainKwh < 1.5 || soc < 10 || soc > 100) return false;
            double impliedKwh = remainKwh / (soc / 100.0);
            if (!fuelSignalsLookBev && Math.abs(remainKwh - soc) < 5.0) return false;
            double relativeDelta = Math.abs(impliedKwh - bmsKwh) / bmsKwh;
            if (relativeDelta > 0.25) {
                logger.warn("BMS exact-Ah result " + bmsKwh + " kWh contradicted by SOC ratio: "
                    + String.format("%.1f", impliedKwh) + " kWh (remain="
                    + String.format("%.1f", remainKwh) + ", SOC="
                    + String.format("%.0f", soc) + "%) — falling through to SOC heuristic");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private double tryBmsExactCapacity(android.content.Context context) {
        Integer rawOrNull = readBatteryCapacityRaw(context);
        if (rawOrNull == null) return 0;
        int raw = rawOrNull;
        if (raw > 1000 && raw < 60000) {
            double kwh = raw / 1000.0;
            if (kwh >= MIN_PLAUSIBLE_KWH && kwh <= MAX_PLAUSIBLE_KWH) {
                logger.info("BMS Capacity (exact, 0.001 kWh): " + kwh + " kWh (raw=" + raw + ")");
                return kwh;
            }
            return 0;
        }
        if (raw > 0 && raw <= 1000) {
            double kwh = mapAhToKwh(raw);
            if (kwh >= MIN_PLAUSIBLE_KWH && kwh <= MAX_PLAUSIBLE_KWH) {
                logger.info("BMS Capacity (exact, Ah=" + raw + "): " + kwh + " kWh");
                return kwh;
            }
        }
        return 0;
    }

    private double tryBmsFuzzyCapacity(android.content.Context context) {
        Integer rawOrNull = readBatteryCapacityRaw(context);
        if (rawOrNull == null) return 0;
        int raw = rawOrNull;
        if (raw <= 0 || raw > 1000) return 0;
        if (mapAhToKwh(raw) > 0) return 0;

        int snappedAh = nearestKnownAh(raw, 3);
        if (snappedAh <= 0) return 0;
        double kwh = mapAhToKwh(snappedAh);
        if (kwh < MIN_PLAUSIBLE_KWH || kwh > MAX_PLAUSIBLE_KWH) return 0;

        logger.info("BMS Capacity (fuzzy): " + kwh + " kWh (raw Ah=" + raw
            + " → snapped to " + snappedAh + " Ah)");
        return kwh;
    }

    private Integer readBatteryCapacityRaw(android.content.Context context) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Object device = cls.getMethod("getInstance", android.content.Context.class).invoke(null, context);
            if (device == null) return null;
            Method getBatteryCapacity = cls.getMethod("getBatteryCapacity");
            Number capNum = (Number) getBatteryCapacity.invoke(device);
            if (capNum == null) return null;
            int raw = capNum.intValue();
            if (raw <= 0 || raw == 255 || raw == 254 || raw == 65534 || raw == 65535) {
                return null;
            }
            return raw;
        } catch (Exception e) {
            logger.debug("readBatteryCapacityRaw failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Drop the active trip's kwhStart/kwhEnd baseline. Both readings are taken
     * via getBatteryRemainPowerKwh, which is computed against currentSoh ×
     * nominalCapacityKwh — so any nominal/SOH change mid-trip leaves the two
     * endpoints in different unit systems. Wiping them forces the trip to
     * fall through to TripAnalyticsManager's SoC-delta estimate, which uses a
     * single (post-change) nominal × SOH for both ends.
     */
    private void invalidateActiveTripKwhBaseline(String reason) {
        try {
            com.overdrive.app.trips.TripAnalyticsManager mgr =
                com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            if (mgr == null || !mgr.isTripActive()) return;
            com.overdrive.app.trips.TripRecord active = mgr.getActiveTrip();
            if (active == null) return;
            if (active.kwhStart == 0 && active.kwhEnd == 0) return;
            logger.info("Invalidating active trip kWh baseline (" + reason + ") — "
                + "kwhStart=" + String.format("%.2f", active.kwhStart)
                + " kwhEnd=" + String.format("%.2f", active.kwhEnd));
            active.kwhStart = 0;
            active.kwhEnd = 0;
        } catch (Throwable t) {
            logger.debug("invalidateActiveTripKwhBaseline noop: " + t.getMessage());
        }
    }

    /**
     * Compute live SOH from one tick of BMS data WITHOUT side effects.
     * Used by both updateFromEnergy() and any read-only consumer.
     */
    public double computeLiveSoh(double remainKwh, double socPercent, double highCellVoltage) {
        if (nominalCapacityKwh <= 0) return -1;
        if (socPercent <= 0 || socPercent > 100) return -1;
        if (remainKwh <= 0) return -1;
        double scale = displayToAbsoluteSocScale(highCellVoltage);
        double absSoc = scaleDisplaySoc(socPercent, scale);
        double impliedTotalCap = remainKwh / (absSoc / 100.0);
        double rawSoh = (impliedTotalCap / nominalCapacityKwh) * 100.0;
        boolean saturated = (rawSoh < MIN_SOH || rawSoh > MAX_SOH);
        if (saturated) {
            saturationStreak++;
            if (saturationStreak == 1 || saturationStreak % SATURATION_WARN_PERIOD == 0) {
                logger.warn("SOH saturated at " + (rawSoh < MIN_SOH ? "60%" : "100%")
                    + " rail (raw=" + String.format("%.1f", rawSoh)
                    + "%, nominal=" + String.format("%.1f", nominalCapacityKwh) + " kWh"
                    + ", source=" + nominalSource + ", streak=" + saturationStreak
                    + (rawSoh > MAX_SOH
                        ? ") — BMS reads above nominal (impossible SOH>100%); nominal may be set too low"
                        : ") — likely wrong nominal capacity selected"));
            }
        } else {
            saturationStreak = 0;
        }
        if (rawSoh < MIN_SOH) return MIN_SOH;
        if (rawSoh > MAX_SOH) return MAX_SOH;
        return rawSoh;
    }

    /**
     * Seed an initial estimate immediately after capacity detection so the UI
     * isn't blank waiting for the first SocHistoryDatabase tick.
     */
    public void seedInitialEstimate() {
        final long seedGeneration;
        final double seedNominalKwh;
        synchronized (autoDetectLock) {
            if (initializationState != InitializationState.READY
                    || currentSoh > 0 || nominalCapacityKwh <= 0) {
                return;
            }
            seedGeneration = estimatorGeneration;
            seedNominalKwh = nominalCapacityKwh;
        }

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            BatterySocData socData = vdm.getBatterySoc();
            BydVehicleData vd = vdm.getVd();
            // Try the energy-based seed first. Read RAW vd.remainKwh, never
            // getBatteryRemainPowerKwh() — the helper synthesizes from
            // currentSoh on PHEV / bad-BMS paths and would loop SOH back
            // into itself, locking the estimate at the seed value forever.
            // PHEV: skip the energy-based seed entirely — the raw getter can't be
            // trusted for SOH (half/stale/frame-ambiguous), so we seed at the
            // honest 100% default (tail below) and let only the independent
            // capacity-Ah / calibration anchors lower it. BEV seeds from energy.
            boolean isPhevForSeed = false;
            try {
                com.overdrive.app.byd.BydDataCollector col =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
                if (col != null && col.isInitialized()) isPhevForSeed = col.isPhevPublic();
            } catch (Throwable ignored) {}

            double candidateSoh = -1;
            if (!isPhevForSeed
                    && vd != null && !Double.isNaN(vd.remainKwh) && vd.remainKwh > 0
                    && socData != null
                    && socData.socPercent >= 10 && socData.socPercent <= 100) {
                double rawRemainKwh = vd.remainKwh;
                double impliedCap = rawRemainKwh / (socData.socPercent / 100.0);
                double ratio = impliedCap / seedNominalKwh;
                // Refuse junk BMS readings (ratio outside plausible band).
                if (ratio >= 0.5 && ratio <= 1.12) {
                    double highCellV = Double.isNaN(vd.highCellVoltage) ? Double.NaN : vd.highCellVoltage;
                    candidateSoh = computeSeedSoh(
                        rawRemainKwh,
                        socData.socPercent,
                        highCellV,
                        seedNominalKwh);
                }
            }

            // Always-persist tail. v15.6 had this and v17 lost it during the
            // SohEstimator rewrite — without it, vehicles whose first poll
            // can't satisfy the energy seed (PHEV firmware bug, SOC out of
            // range, junk BMS readings) never write a SOH line to disk and
            // /api/performance/soh stays empty forever. Falling back to a
            // 100% baseline is safe: the calibration anchor refines it as
            // soon as the user does a real charging session, and live SOH
            // updateFromEnergy() takes over once vd.remainKwh becomes
            // trustworthy (e.g. when getBatteryPowerHEV starts reporting).
            if (candidateSoh <= 0) {
                String why;
                if (socData == null) {
                    why = "no SOC data";
                } else if (socData.socPercent < 10) {
                    why = "SOC " + String.format("%.0f", socData.socPercent) + "% below seed threshold";
                } else if (socData.socPercent > 100) {
                    why = "SOC out of range";
                } else if (vd == null || Double.isNaN(vd.remainKwh) || vd.remainKwh <= 0) {
                    why = "no remainKwh from BMS";
                } else {
                    why = "energy reading rejected by ratio gate";
                }
                logger.info("Seeding SOH at 100% baseline (" + why + ") — nominal="
                    + String.format("%.2f", seedNominalKwh) + " kWh");
                candidateSoh = 100.0;
            }

            if (tryCommitInitialSeed(
                    seedGeneration, seedNominalKwh, candidateSoh)) {
                logger.info("Initial SOH seeded: "
                    + String.format("%.1f", candidateSoh) + "%");
            }
        } catch (Exception e) {
            logger.debug("Initial SOH seed failed: " + e.getMessage());
        }
    }

    private static double computeSeedSoh(
            double remainKwh,
            double socPercent,
            double highCellVoltage,
            double nominalKwh) {
        if (nominalKwh <= 0 || socPercent <= 0 || socPercent > 100
                || remainKwh <= 0) {
            return -1;
        }
        double scale = displayToAbsoluteSocScale(highCellVoltage);
        double absSoc = scaleDisplaySoc(socPercent, scale);
        double rawSoh =
            ((remainKwh / (absSoc / 100.0)) / nominalKwh) * 100.0;
        if (rawSoh < MIN_SOH) return MIN_SOH;
        if (rawSoh > MAX_SOH) return MAX_SOH;
        return rawSoh;
    }

    private boolean tryCommitInitialSeed(
            long expectedGeneration,
            double expectedNominalKwh,
            double candidateSoh) {
        synchronized (autoDetectLock) {
            if (initializationState != InitializationState.READY
                    || estimatorGeneration != expectedGeneration
                    || Double.doubleToLongBits(nominalCapacityKwh)
                        != Double.doubleToLongBits(expectedNominalKwh)
                    || currentSoh > 0
                    || candidateSoh <= 0) {
                return false;
            }
            currentSoh = candidateSoh;
            estimatorGeneration++;
            persistEstimate();
            return true;
        }
    }

    // ==================== LIFECYCLE ====================

    public void init() {
        try {
            userNominalConfig.runUnderConfigLock(() -> {
                final double configuredUserKwh;
                try {
                    configuredUserKwh =
                        validateConfiguredUserNominal(userNominalConfig.read());
                } catch (Throwable t) {
                    markInitializationDeferred();
                    logger.warn("UnifiedConfig vehicle.nominalKwh read deferred: "
                        + t.getMessage());
                    return;
                }

                synchronized (autoDetectLock) {
                    initializationState = InitializationState.DEFERRED;
                    if (initLocked(configuredUserKwh)) {
                        initializationState = InitializationState.READY;
                    }
                }
            });
        } catch (Throwable t) {
            markInitializationDeferred();
            logger.warn("UnifiedConfig lock unavailable during SOH init: "
                + t.getMessage());
        }
    }

    public boolean isInitializationReady() {
        synchronized (autoDetectLock) {
            return initializationState == InitializationState.READY;
        }
    }

    private void markInitializationDeferred() {
        synchronized (autoDetectLock) {
            initializationState = InitializationState.DEFERRED;
        }
    }

    private boolean initLocked(double configuredUserKwh) {
        RestoredState restored = new RestoredState();
        if (configuredUserKwh > 0) {
            restored.nominalCapacityKwh = configuredUserKwh;
            restored.nominalSource = "user";
        }

        if (!sohFile.exists()) {
            restored.resetModelEpoch = initialResetModelEpoch();
            try {
                PersistenceOutcome initialEpochOutcome =
                    publishPropertiesWithDurabilityRetries(
                        nominalOnlyProperties(restored, true),
                        "establish initial reset/model epoch");
                if (initialEpochOutcome != PersistenceOutcome.DURABLE) {
                    return false;
                }
            } catch (IOException writeFailure) {
                logger.error("Failed to establish initial reset/model epoch: "
                    + writeFailure.getMessage());
                return false;
            }
            publishRestoredStateLocked(restored);
            if (configuredUserKwh > 0) {
                logger.info("Restored user nominal capacity: "
                    + configuredUserKwh + " kWh");
            }
            return true;
        }

        Properties props = new Properties();
        try {
            try (FileInputStream fis = new FileInputStream(sohFile)) {
                props.load(fis);
            }
        } catch (Exception readFailure) {
            logger.error("Failed to read SOH snapshot: "
                + readFailure.getMessage());
            return false;
        }

        final boolean legacyEpochMissing =
            !props.containsKey(PROP_RESET_MODEL_EPOCH);
        if (legacyEpochMissing) {
            // Never expose zero for a legacy image: DB rows created before the
            // epoch contract have NULL/zero and must not compare equal to the
            // first current lineage.
            restored.resetModelEpoch = initialResetModelEpoch();
        } else {
            try {
                restored.resetModelEpoch = Long.parseLong(
                    props.getProperty(PROP_RESET_MODEL_EPOCH));
            } catch (NumberFormatException malformedEpoch) {
                logger.error("Failed to load SOH: malformed reset/model epoch");
                return false;
            }
            if (restored.resetModelEpoch <= 0) {
                logger.error("Failed to load SOH: reset/model epoch must be positive");
                return false;
            }
        }

        final int persistedVersion;
        String versionValue = props.getProperty(PROP_SCHEMA_VERSION);
        if (versionValue == null) {
            persistedVersion = 0;
        } else {
            try {
                persistedVersion = Integer.parseInt(versionValue);
            } catch (NumberFormatException malformedVersion) {
                logger.error("Failed to load SOH: malformed schema_version");
                return false;
            }
        }
        if (persistedVersion == CURRENT_SCHEMA_VERSION) {
            String lastUpdatedValue = props.getProperty(PROP_LAST_UPDATED);
            if (lastUpdatedValue != null) {
                try {
                    restored.persistedLastUpdatedMs = Math.max(
                        0L, Long.parseLong(lastUpdatedValue));
                } catch (NumberFormatException malformedTimestamp) {
                    logger.error("Failed to load SOH: malformed last_updated");
                    return false;
                }
            }
        }

        try {
            if (persistedVersion < 2) {
                // Legacy SOH semantics are incompatible. Preserve only a valid
                // non-user nominal when UnifiedConfig has no user override.
                if (configuredUserKwh <= 0) {
                    String capStr = props.getProperty(PROP_NOMINAL_CAPACITY);
                    if (capStr != null) {
                        double savedCap = Double.parseDouble(capStr);
                        if (!Double.isNaN(savedCap)
                                && !Double.isInfinite(savedCap)
                                && savedCap >= MIN_PLAUSIBLE_KWH
                                && savedCap <= MAX_PLAUSIBLE_KWH) {
                            restored.nominalCapacityKwh = savedCap;
                            String savedSource =
                                props.getProperty(PROP_NOMINAL_SOURCE);
                            restored.nominalSource =
                                savedSource != null
                                    && !savedSource.isEmpty()
                                    && !"user".equals(savedSource)
                                        ? savedSource : "auto";
                        }
                    }
                }
                if (!legacyEpochMissing) {
                    restored.resetModelEpoch =
                        nextResetModelEpoch(restored.resetModelEpoch);
                }
                PersistenceOutcome outcome =
                    publishPropertiesWithDurabilityRetries(
                        nominalOnlyProperties(restored, true),
                        "migrate legacy SOH schema");
                if (outcome != PersistenceOutcome.DURABLE) return false;
                publishRestoredStateLocked(restored);
                logger.info("SOH file migrated from legacy schema (v" + persistedVersion
                    + " → v" + CURRENT_SCHEMA_VERSION
                    + ") — currentSoh/calibration cleared, will re-seed from BMS data");
                return true;
            }
            if (persistedVersion == 2 && CURRENT_SCHEMA_VERSION >= 3) {
                logger.info("SOH file soft-upgraded v2 → v" + CURRENT_SCHEMA_VERSION
                    + " (peak frame anchor added, existing state preserved)");
            }

            if (configuredUserKwh <= 0) {
                restorePersistedNominal(props, restored);
            }

            if (persistedNominalIdentityDiffers(
                    props,
                    restored.nominalCapacityKwh,
                    restored.nominalSource)) {
                logger.warn("Discarding persisted SOH because its nominal identity "
                    + "changed");
                RestoredState cleared = new RestoredState();
                if (configuredUserKwh > 0) {
                    cleared.nominalCapacityKwh = configuredUserKwh;
                    cleared.nominalSource = "user";
                }
                cleared.resetModelEpoch =
                    nextResetModelEpoch(restored.resetModelEpoch);
                return publishClearedRestoreStateLocked(
                    cleared, "discard changed nominal identity");
            }

            if (!persistedStateMatchesNominal(
                    props,
                    restored.nominalCapacityKwh,
                    restored.nominalSource)) {
                logger.warn("Discarding persisted SOH because its nominal identity "
                    + "does not match UnifiedConfig");
                RestoredState cleared = restored;
                if (configuredUserKwh <= 0) {
                    cleared = new RestoredState();
                }
                cleared.resetModelEpoch =
                    nextResetModelEpoch(restored.resetModelEpoch);
                return publishClearedRestoreStateLocked(
                    cleared, "discard stale nominal-bound SOH");
            }

            String sohStr = props.getProperty(PROP_SOH_PERCENT);
            if (sohStr != null) {
                double persistedSoh = Double.parseDouble(sohStr);
                if (persistedSoh >= MIN_SOH && persistedSoh <= 110) {
                    restored.currentSoh =
                        Math.min(persistedSoh, MAX_SOH);
                    logger.info("Restored SOH: " + restored.currentSoh + "%"
                        + (persistedSoh > MAX_SOH ? " (capped from " + persistedSoh + "%)" : ""));
                } else {
                    logger.info("Discarding persisted SOH " + persistedSoh
                        + " — out of valid range " + MIN_SOH + "-110");
                    RestoredState cleared = new RestoredState();
                    if (configuredUserKwh > 0) {
                        cleared.nominalCapacityKwh = configuredUserKwh;
                        cleared.nominalSource = "user";
                    }
                    cleared.resetModelEpoch =
                        nextResetModelEpoch(restored.resetModelEpoch);
                    return publishClearedRestoreStateLocked(
                        cleared, "discard invalid persisted SOH");
                }
            }

            String calStr = props.getProperty(PROP_CALIBRATION_SOH);
            String calTsStr = props.getProperty(PROP_CALIBRATION_TIMESTAMP);
            if ((calStr == null) != (calTsStr == null)) {
                throw new IllegalStateException(
                    "Calibration SOH and timestamp must be persisted together");
            }
            if (calStr != null) {
                double cal = Double.parseDouble(calStr);
                long calibrationAtMs = Long.parseLong(calTsStr);
                if (Double.isNaN(cal) || Double.isInfinite(cal)
                        || cal < MIN_SOH || cal > 110
                        || calibrationAtMs <= 0) {
                    throw new IllegalStateException(
                        "Persisted calibration anchor is invalid");
                }
                restored.calibrationSoh = Math.min(cal, MAX_SOH);
                restored.calibrationTimestampMs = calibrationAtMs;
            }

            String capAhStr = props.getProperty(PROP_CAPACITY_AH_SOH);
            if (capAhStr != null) {
                double cah = Double.parseDouble(capAhStr);
                if (cah >= MIN_SOH && cah <= 110) {
                    restored.capacityAhSoh = Math.min(cah, MAX_SOH);
                }
            }
            String capAhTsStr = props.getProperty(PROP_CAPACITY_AH_TIMESTAMP);
            if (capAhTsStr != null) {
                restored.capacityAhTimestampMs =
                    Math.max(0L, Long.parseLong(capAhTsStr));
            }
            String capAhDisStr = props.getProperty(PROP_CAPACITY_AH_DISABLED);
            if ("true".equalsIgnoreCase(capAhDisStr)) {
                restored.capacityAhDisabled = true;
                logger.info("Capacity-Ah anchor restored as disabled (persisted)");
            }

            String liveHistStr = props.getProperty(PROP_LIVE_HISTORY);
            if (liveHistStr != null && !liveHistStr.isEmpty()) {
                try {
                    String[] parts = liveHistStr.split(",");
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (trimmed.isEmpty()) continue;
                        double v = Double.parseDouble(trimmed);
                        if (v >= MIN_SOH && v <= 110) {
                            restored.liveHistory.addLast(
                                Math.min(v, MAX_SOH));
                        }
                    }
                    while (restored.liveHistory.size() > LIVE_HISTORY_SIZE) {
                        restored.liveHistory.pollFirst();
                    }
                } catch (Exception ignored) {
                    restored.liveHistory.clear();
                }
            }

            String peakStr = props.getProperty(PROP_PEAK_REMAIN_KWH);
            if (peakStr != null) {
                double peak = Double.parseDouble(peakStr);
                if (peak > 0 && peak <= MAX_PLAUSIBLE_KWH) {
                    restored.peakRemainKwhAtFull = peak;
                }
            }
            String peakSamplesStr = props.getProperty(PROP_PEAK_REMAIN_KWH_SAMPLES);
            if (peakSamplesStr != null) {
                restored.peakRemainKwhSamples = Math.max(
                    0,
                    Math.min(
                        Integer.parseInt(peakSamplesStr),
                        PEAK_REMAIN_KWH_REQUIRED_SAMPLES));
            }
            String peakTsStr = props.getProperty(PROP_PEAK_REMAIN_KWH_TS);
            if (peakTsStr != null) {
                restored.peakRemainKwhTimestampMs =
                    Math.max(0L, Long.parseLong(peakTsStr));
            }
            String peakNotifiedStr = props.getProperty(PROP_PEAK_REMAIN_KWH_NOTIFIED);
            if ("true".equalsIgnoreCase(peakNotifiedStr)) {
                restored.peakMismatchNotified = true;
            }
            if (restored.peakRemainKwhAtFull > 0) {
                logger.info("Restored peak frame anchor: "
                    + String.format("%.2f", restored.peakRemainKwhAtFull) + " kWh ("
                    + restored.peakRemainKwhSamples + "/"
                    + PEAK_REMAIN_KWH_REQUIRED_SAMPLES + " samples)"
                    + (restored.peakMismatchNotified
                        ? " [mismatch already notified]" : ""));
            }

            boolean stateCleared =
                "true".equalsIgnoreCase(
                    props.getProperty(PROP_STATE_CLEARED))
                && !hasPersistedEstimateState(props);
            PersistenceOutcome checkpointOutcome =
                publishPropertiesWithDurabilityRetries(
                    completePersistenceProperties(restored, stateCleared),
                    legacyEpochMissing
                        ? "bootstrap legacy reset/model epoch"
                        : "re-establish SOH snapshot durability");
            if (checkpointOutcome != PersistenceOutcome.DURABLE) {
                return false;
            }

            publishRestoredStateLocked(restored);
            if (configuredUserKwh > 0) {
                logger.info("Restored user nominal capacity: "
                    + configuredUserKwh + " kWh");
            } else if (restored.nominalCapacityKwh > 0) {
                logger.info("Restored nominal capacity: "
                    + restored.nominalCapacityKwh + " kWh (source="
                    + restored.nominalSource + ")");
            }
            if (restored.currentSoh > 0) {
                logger.info("SOH init complete: "
                    + restored.currentSoh + "%");
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to load SOH: " + e.getMessage());
            // Every parse above targets only RestoredState. A malformed optional
            // property therefore leaves the previously published estimator
            // snapshot untouched and keeps auto-detection blocked.
            return false;
        }
    }

    private void restorePersistedNominal(
            Properties props, RestoredState restored) {
        String persistedSource =
            props.getProperty(PROP_NOMINAL_SOURCE, "unset");
        if ("user".equals(persistedSource)) {
            // Only UnifiedConfig may establish a user identity.
            return;
        }
        String capacity = props.getProperty(PROP_NOMINAL_CAPACITY);
        if (capacity == null) return;

        double persistedNominal = Double.parseDouble(capacity);
        if (Double.isNaN(persistedNominal)
                || Double.isInfinite(persistedNominal)
                || persistedNominal < MIN_PLAUSIBLE_KWH
                || persistedNominal > MAX_PLAUSIBLE_KWH) {
            throw new IllegalArgumentException(
                "persisted nominal outside plausible range: "
                    + persistedNominal);
        }
        restored.nominalCapacityKwh = persistedNominal;
        restored.nominalSource =
            persistedSource == null || persistedSource.isEmpty()
                || "unset".equals(persistedSource)
                    ? "auto" : persistedSource;
    }

    private boolean persistedStateMatchesNominal(
            Properties props, double activeNominal, String activeSource) {
        double persistedNominal;
        try {
            persistedNominal = Double.parseDouble(
                props.getProperty(PROP_NOMINAL_CAPACITY, "0"));
        } catch (NumberFormatException malformedNominal) {
            return false;
        }
        String persistedSource =
            props.getProperty(PROP_NOMINAL_SOURCE, "unset");
        String persistedIdentity =
            props.getProperty(PROP_NOMINAL_IDENTITY);
        boolean hasEstimateState = hasPersistedEstimateState(props);

        if ("user".equals(activeSource) && activeNominal > 0) {
            if (!hasEstimateState) return true;
            String activeIdentity =
                nominalIdentity(activeNominal, activeSource);
            if (persistedIdentity != null) {
                return activeIdentity.equals(persistedIdentity);
            }
            return "user".equals(persistedSource)
                && sameNominal(persistedNominal, activeNominal);
        }

        if ("user".equals(persistedSource)) return false;
        if (hasEstimateState && activeNominal <= 0) return false;
        if (!hasEstimateState) return true;
        if (persistedIdentity == null) return true;
        return persistedIdentity.equals(
            nominalIdentity(activeNominal, activeSource));
    }

    private boolean persistedNominalIdentityDiffers(
            Properties props, double activeNominal, String activeSource) {
        String persistedIdentity =
            props.getProperty(PROP_NOMINAL_IDENTITY);
        String persistedCapacity =
            props.getProperty(PROP_NOMINAL_CAPACITY);
        if (persistedIdentity == null && persistedCapacity == null) {
            // A reset tombstone deliberately carries only the already-advanced
            // epoch. UnifiedConfig may restore the same user nominal in memory
            // without causing another advance on every daemon restart.
            return false;
        }

        if (persistedIdentity != null) {
            return !persistedIdentity.equals(
                nominalIdentity(activeNominal, activeSource));
        }

        final double persistedNominal;
        try {
            persistedNominal = Double.parseDouble(persistedCapacity);
        } catch (NumberFormatException malformedNominal) {
            return true;
        }
        String persistedSource =
            props.getProperty(PROP_NOMINAL_SOURCE, "unset");
        return !sameNominal(persistedNominal, activeNominal)
            || !persistedSource.equals(activeSource);
    }

    private static boolean hasPersistedEstimateState(Properties props) {
        return props.containsKey(PROP_SOH_PERCENT)
            || props.containsKey(PROP_CALIBRATION_SOH)
            || props.containsKey(PROP_CALIBRATION_TIMESTAMP)
            || props.containsKey(PROP_CAPACITY_AH_SOH)
            || props.containsKey(PROP_CAPACITY_AH_TIMESTAMP)
            || props.containsKey(PROP_CAPACITY_AH_DISABLED)
            || props.containsKey(PROP_LIVE_HISTORY)
            || props.containsKey(PROP_PEAK_REMAIN_KWH)
            || props.containsKey(PROP_PEAK_REMAIN_KWH_SAMPLES)
            || props.containsKey(PROP_PEAK_REMAIN_KWH_TS)
            || props.containsKey(PROP_PEAK_REMAIN_KWH_NOTIFIED);
    }

    private boolean publishClearedRestoreStateLocked(
            RestoredState restored, String operation) {
        PersistenceOutcome outcome;
        try {
            outcome = publishPropertiesWithDurabilityRetries(
                nominalOnlyProperties(
                    restored,
                    !"user".equals(restored.nominalSource)
                        || restored.nominalCapacityKwh <= 0),
                operation);
        } catch (Exception writeFailure) {
            logger.error("Failed to " + operation + ": "
                + writeFailure.getMessage());
            return false;
        }
        if (outcome != PersistenceOutcome.DURABLE) return false;
        publishRestoredStateLocked(restored);
        return true;
    }

    private static final class RestoredState {
        long persistedLastUpdatedMs = 0;
        double nominalCapacityKwh = 0;
        String nominalSource = "unset";
        long resetModelEpoch = 0;
        double currentSoh = -1;
        double calibrationSoh = -1;
        long calibrationTimestampMs = 0;
        double capacityAhSoh = -1;
        long capacityAhTimestampMs = 0;
        boolean capacityAhDisabled = false;
        final java.util.ArrayDeque<Double> liveHistory =
            new java.util.ArrayDeque<>(LIVE_HISTORY_SIZE);
        double peakRemainKwhAtFull = -1;
        int peakRemainKwhSamples = 0;
        long peakRemainKwhTimestampMs = 0;
        boolean peakMismatchNotified = false;
    }

    private void publishRestoredStateLocked(RestoredState restored) {
        nominalCapacityKwh = restored.nominalCapacityKwh;
        nominalSource = restored.nominalSource;
        resetModelEpoch = restored.resetModelEpoch;
        currentSoh = restored.currentSoh;
        calibrationSoh = restored.calibrationSoh;
        calibrationTimestampMs = restored.calibrationTimestampMs;
        capacityAhSoh = restored.capacityAhSoh;
        capacityAhTimestampMs = restored.capacityAhTimestampMs;
        capacityAhDisabled = restored.capacityAhDisabled;
        liveHistory.clear();
        liveHistory.addAll(restored.liveHistory);
        peakRemainKwhAtFull = restored.peakRemainKwhAtFull;
        peakRemainKwhSamples = restored.peakRemainKwhSamples;
        peakRemainKwhTimestampMs = restored.peakRemainKwhTimestampMs;
        peakMismatchNotified = restored.peakMismatchNotified;

        lastCapacityAhReading = -1;
        capacityAhNameplateMatchCount = 0;
        capacityAhFirstSocSeen = -1;
        capacityAhFirstAhSeen = -1;
        capacityAhSocCoupledCount = 0;
        saturationStreak = 0;
        fuelSignalsLookBev = false;
        estimatorGeneration++;
    }

    private static boolean sameNominal(double first, double second) {
        return Math.abs(first - second) <= 0.000001;
    }

    private static long initialResetModelEpoch() {
        long now = System.currentTimeMillis();
        return now > 0 && now < Long.MAX_VALUE ? now : 1L;
    }

    private static long nextResetModelEpoch(long currentEpoch) {
        if (currentEpoch <= 0) {
            throw new IllegalStateException(
                "SOH reset/model epoch is not initialized");
        }
        if (currentEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException(
                "SOH reset/model epoch is exhausted");
        }
        return currentEpoch + 1L;
    }

    private static String nominalIdentity(double nominalKwh, String source) {
        String normalizedSource =
            source == null || source.isEmpty() ? "unset" : source;
        return normalizedSource + ":"
            + Long.toHexString(Double.doubleToLongBits(nominalKwh));
    }

    private void clearEstimateStateLocked(boolean clearPeakAnchor) {
        currentSoh = -1;
        calibrationSoh = -1;
        calibrationTimestampMs = 0;
        capacityAhSoh = -1;
        capacityAhTimestampMs = 0;
        lastCapacityAhReading = -1;
        capacityAhNameplateMatchCount = 0;
        capacityAhDisabled = false;
        capacityAhFirstSocSeen = -1;
        capacityAhFirstAhSeen = -1;
        capacityAhSocCoupledCount = 0;
        liveHistory.clear();
        saturationStreak = 0;
        if (clearPeakAnchor) {
            peakRemainKwhAtFull = -1;
            peakRemainKwhSamples = 0;
            peakRemainKwhTimestampMs = 0;
            peakMismatchNotified = false;
        }
    }

    // ==================== UPDATES ====================

    /**
     * Live update from one tick of BMS data. Direct assignment — no EMA, no
     * gating beyond the formula's plausibility clamps. The caller decides
     * whether the conditions are right to feed an update; we just compute.
     *
     * `atRest` is preserved for ABI compatibility but ignored.
     */
    public void updateFromEnergy(double remainingKwh, double displaySocPercent,
                                 double highCellVoltage, boolean atRest) {
        synchronized (autoDetectLock) {
            double soh = computeLiveSoh(remainingKwh, displaySocPercent, highCellVoltage);
            if (soh <= 0) return;
            liveHistory.addLast(soh);
            while (liveHistory.size() > LIVE_HISTORY_SIZE) {
                liveHistory.pollFirst();
            }
            currentSoh = median(liveHistory);
            persistEstimate();
        }
    }

    private static double median(java.util.Collection<Double> values) {
        if (values.isEmpty()) return -1;
        java.util.ArrayList<Double> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta,
                                      double packTempCelsius, boolean isAcCharge) {
        updateFromCalibration(energyEnteredBatteryKwh, socDelta, packTempCelsius, isAcCharge, Double.NaN);
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta,
                                      double packTempCelsius, boolean isAcCharge,
                                      double highCellVoltage) {
        applyCalibration(
                energyEnteredBatteryKwh, socDelta, packTempCelsius, isAcCharge,
                highCellVoltage, System.currentTimeMillis(), false);
    }

    public enum CalibrationReplayOutcome {
        APPLIED,
        PERMANENTLY_REJECTED,
        RETRY_LATER
    }

    /**
     * Replay one committed charging calibration with a stable identity/timestamp.
     *
     * <p>Returns false when initialization or validation rejects the payload, or when the anchor could
     * not be persisted. Repeating the same timestamp and payload is a no-op; an older replay is already
     * superseded by a newer calibration. This lets the charging row's applied flag be updated only after
     * the external metadata effect is durable.
     */
    public boolean applyCalibrationReplay(
            double energyEnteredBatteryKwh, double socDelta,
            double packTempCelsius, boolean isAcCharge,
            double highCellVoltage, long calibrationAtMs) {
        return applyCalibrationReplayWithOutcome(
                energyEnteredBatteryKwh, socDelta, packTempCelsius, isAcCharge,
                highCellVoltage, calibrationAtMs)
            == CalibrationReplayOutcome.APPLIED;
    }

    public CalibrationReplayOutcome applyCalibrationReplayWithOutcome(
            double energyEnteredBatteryKwh, double socDelta,
            double packTempCelsius, boolean isAcCharge,
            double highCellVoltage, long calibrationAtMs) {
        if (calibrationAtMs <= 0L) {
            return CalibrationReplayOutcome.PERMANENTLY_REJECTED;
        }
        return applyCalibration(
                energyEnteredBatteryKwh, socDelta, packTempCelsius, isAcCharge,
                highCellVoltage, calibrationAtMs, true);
    }

    private CalibrationReplayOutcome applyCalibration(
            double energyEnteredBatteryKwh, double socDelta,
            double packTempCelsius, boolean isAcCharge,
            double highCellVoltage, long calibrationAtMs,
            boolean requireDurablePersistence) {
        synchronized (autoDetectLock) {
            if (Double.isNaN(nominalCapacityKwh)
                    || Double.isInfinite(nominalCapacityKwh)
                    || nominalCapacityKwh <= 0) {
                logger.debug("Calibration rejected: nominal capacity not yet detected");
                return CalibrationReplayOutcome.RETRY_LATER;
            }
            if (Double.isNaN(energyEnteredBatteryKwh)
                    || Double.isInfinite(energyEnteredBatteryKwh)
                    || energyEnteredBatteryKwh <= 0
                    || Double.isNaN(socDelta)
                    || Double.isInfinite(socDelta)
                    || socDelta > 100.0
                    || Double.isNaN(packTempCelsius)
                    || Double.isInfinite(packTempCelsius)
                    || (!Double.isNaN(highCellVoltage)
                        && Double.isInfinite(highCellVoltage))) {
                logger.debug("Calibration rejected: non-finite or non-positive payload");
                return CalibrationReplayOutcome.PERMANENTLY_REJECTED;
            }
            // DC charging is accepted now; cluster-displayed energy/SOC remains accurate enough for SOH math, the AC-only gate was over-cautious.
            if (packTempCelsius < 15.0 || packTempCelsius > 35.0) {
                logger.debug("Calibration rejected: Pack temperature (" +
                    String.format("%.1f", packTempCelsius) + "°C) outside optimal SOH window (15-35°C).");
                return CalibrationReplayOutcome.PERMANENTLY_REJECTED;
            }
            if (socDelta < 25.0) {
                logger.debug("Calibration rejected: SOC delta " + String.format("%.1f", socDelta) +
                    "% < 25% minimum for LFP accuracy");
                return CalibrationReplayOutcome.PERMANENTLY_REJECTED;
            }

            double scale = displayToAbsoluteSocScale(highCellVoltage);
            double absSocDelta = socDelta * scale;
            double actualCapacity = energyEnteredBatteryKwh / (absSocDelta / 100.0);
            double calibratedSoh = (actualCapacity / nominalCapacityKwh) * 100.0;

            // KNOWN, BOUNDED, ONE-DIRECTIONAL BIAS. The energy handed in is the best SOC-independent
            // figure available, which on trims exposing the vehicle's charged-energy counter is that
            // counter. Whether that counter meters at the pack or at the charger inlet is NOT
            // established — the parameter name assumes the pack. If it is charger-side, this divides
            // wall energy by a pack-side SOC delta and OVERSTATES health by the conversion-loss
            // fraction (~5-15% on AC): a true 82% pack could read ~91%.
            //
            // Deliberately NOT "corrected" by a guessed loss constant — that would be the same class
            // of unfounded fudge factor this subsystem was built to remove, and it would corrupt the
            // pack-side case where no loss applies. Two things bound the damage instead: the error is
            // one-directional (it can only flatter the pack, never invent degradation), and the
            // MAX_SOH clamp below caps the visible result. Recorded here so a future capture that
            // settles the metering point knows exactly what to revisit.

            // Reject genuinely-implausible calibrations; clamp a slightly-high one
            // to the 100% ceiling (a full charge can measure a touch above nominal).
            if (calibratedSoh < MIN_SOH || calibratedSoh > 130.0) {
                logger.warn("Calibration SOH out of range: " + String.format("%.1f", calibratedSoh) + "% — rejected");
                return CalibrationReplayOutcome.PERMANENTLY_REJECTED;
            }
            if (calibratedSoh > MAX_SOH) calibratedSoh = MAX_SOH;

            if (requireDurablePersistence) {
                if (calibrationTimestampMs > calibrationAtMs) {
                    return persistEstimateDurably()
                        ? CalibrationReplayOutcome.APPLIED
                        : CalibrationReplayOutcome.RETRY_LATER;
                }
                if (calibrationTimestampMs == calibrationAtMs) {
                    if (!sameCalibration(calibrationSoh, calibratedSoh)) {
                        return CalibrationReplayOutcome.PERMANENTLY_REJECTED;
                    }
                    return persistEstimateDurably()
                        ? CalibrationReplayOutcome.APPLIED
                        : CalibrationReplayOutcome.RETRY_LATER;
                }
            }

            // Anchor only — never blends into currentSoh.
            double previousSoh = calibrationSoh;
            long previousTimestampMs = calibrationTimestampMs;
            boolean calibrationChanged =
                !sameCalibration(previousSoh, calibratedSoh)
                    || previousTimestampMs != calibrationAtMs;
            calibrationSoh = calibratedSoh;
            calibrationTimestampMs = calibrationAtMs;
            PersistenceOutcome persistenceOutcome =
                persistEstimateWithOutcome();
            if (requireDurablePersistence
                    && persistenceOutcome == PersistenceOutcome.FAILED) {
                calibrationSoh = previousSoh;
                calibrationTimestampMs = previousTimestampMs;
                return CalibrationReplayOutcome.RETRY_LATER;
            }
            if (calibrationChanged) {
                estimatorGeneration++;
            }
            if (requireDurablePersistence
                    && persistenceOutcome
                        == PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN) {
                // The rename is already visible. Keep memory aligned with the
                // committed destination and let replay retry directory
                // durability instead of rolling back behind the file.
                return CalibrationReplayOutcome.RETRY_LATER;
            }

            logger.info("Calibration anchor: " + String.format("%.1f", calibratedSoh) + "% (temp=" +
                String.format("%.1f", packTempCelsius) + "°C, " +
                String.format("%.1f", energyEnteredBatteryKwh) + " kWh / " +
                String.format("%.1f", socDelta) + "% display delta)");
            return CalibrationReplayOutcome.APPLIED;
        }
    }

    private static boolean sameCalibration(
            double calibrationSoh, double candidateSoh) {
        return Double.doubleToLongBits(calibrationSoh)
                == Double.doubleToLongBits(candidateSoh);
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta) {
        updateFromCalibration(energyEnteredBatteryKwh, socDelta, 25.0, true);
    }

    /**
     * PHEV-only capacity-Ah anchor. The BMS reports its current full-charge
     * Ah counter via {@code BYDAutoBodyworkDevice.getBatteryCapacity()} on
     * many firmwares; on PHEVs this is the most stable SOH read because
     * (a) the live remainKwh / SOC formula is noisy at PHEV pack scale (1
     * decimal place over a 9–18 kWh range), and (b) coulomb-count Ah is
     * computed continuously by the BMS regardless of SOC range. BEVs already
     * get reliable live SOH from their wider remainKwh range, so we deliberately
     * gate this source to PHEV — keeping a single source of truth on BEV.
     *
     * <p>Behaves like {@link #updateFromCalibration}: it's an <em>anchor</em>,
     * not a blender. Sets {@link #capacityAhSoh} for display (and as the
     * fall-back in {@code getStatus} when the live source is unavailable),
     * but never modifies {@link #currentSoh}. The live median window stays
     * the source of truth for the live readout.
     *
     * <p>Skips when the BMS returns the nameplate Ah for {@link #CAPACITY_AH_NAMEPLATE_TRIPS}
     * consecutive ticks — that signals a firmware that returns the static
     * factory rating, not a live coulomb count, and would otherwise pin the
     * anchor at 100% forever.
     *
     * @param bmsReportedAh BMS full-charge capacity in Ah (live coulomb count)
     * @param cellCount Series cell count for the pack (derived from voltage)
     * @param isPhev True when the drivetrain has been classified as PHEV.
     *               BEV calls early-return — keep BEV behavior unchanged.
     */
    public void updateFromCapacityAh(double bmsReportedAh, int cellCount, boolean isPhev,
                                     double currentSocPercent) {
        // grossNominalKwh<=0 → derive factory Ah from the nominalCapacityKwh field
        // (correct when that field is already gross, e.g. BEV / PHEV auto-detect).
        updateFromCapacityAh(bmsReportedAh, cellCount, isPhev, currentSocPercent, 0);
    }

    /**
     * As {@link #updateFromCapacityAh(double, int, boolean, double)} but with an
     * explicit gross-frame nameplate kWh for the factory-Ah derivation.
     *
     * <p>The BMS coulomb-count Ah ({@code bmsReportedAh}) is a PHYSICAL gross-frame
     * measurement (~71 Ah on an 18.3 kWh gross Blade pack). The factory Ah it is
     * compared against must therefore also be GROSS. The nominal field is gross on
     * every drivetrain now (PHEV remainKwh is corrected to gross at the HAL read
     * boundary), so {@code grossNominalKwh} and the nominal field agree; the param
     * is kept to let callers pass the explicit model nameplate, and {@code 0} falls
     * back to the nominal field.
     */
    public void updateFromCapacityAh(double bmsReportedAh, int cellCount, boolean isPhev,
                                     double currentSocPercent, double grossNominalKwh) {
        synchronized (autoDetectLock) {
            if (!isPhev) return;                 // BEV: live formula is enough.
            if (capacityAhDisabled) return;
            if (nominalCapacityKwh <= 0) return;
            if (bmsReportedAh <= 0 || cellCount <= 0) return;

            // Derive factory Ah first — needed by both the nameplate detector
            // and the SOC-coupling detector to interpret bmsReportedAh. Use the
            // gross-frame nameplate (see method contract) so the comparison
            // against the physical BMS Ah counter is frame-consistent.
            double grossKwh = (grossNominalKwh > 0) ? grossNominalKwh : nominalCapacityKwh;
            double nominalAh = (grossKwh * 1000.0)
                / (cellCount * BYD_BLADE_REFERENCE_CELL_VOLTAGE);
            if (nominalAh < 30 || nominalAh > 350) {
                logger.debug("Capacity-Ah anchor rejected: derived nominal "
                    + String.format("%.1f", nominalAh) + " Ah outside expected range");
                return;
            }

            // Stuck-at-nameplate detector. Runs BEFORE dedup so a firmware
            // that always returns the static factory rating advances the
            // counter on every tick instead of dedup-skipping after the
            // first one — without that ordering the source latched at the
            // nameplate value would emit 100% on tick 1 and never disable.
            if (Math.abs(bmsReportedAh - nominalAh) <= CAPACITY_AH_NAMEPLATE_TOLERANCE_AH) {
                capacityAhNameplateMatchCount++;
                if (capacityAhNameplateMatchCount >= CAPACITY_AH_NAMEPLATE_TRIPS) {
                    capacityAhDisabled = true;
                    persistEstimate();   // survive daemon restart
                    logger.warn("Capacity-Ah anchor disabled: BMS Ah ("
                        + String.format("%.1f", bmsReportedAh) + ") matches nameplate ("
                        + String.format("%.1f", nominalAh) + ") for "
                        + capacityAhNameplateMatchCount
                        + " consecutive ticks — firmware not coulomb-counting");
                }
                return;
            }
            capacityAhNameplateMatchCount = 0;

            // SOC-coupling detector: some PHEV firmwares return getBatteryCapacity()
            // as 0.1-kWh-remaining (walks with SOC) instead of full-charge Ah
            // (stays flat). Sample at first call; if subsequent ticks show the
            // Ah reading tracking SOC, latch the source off. We require the
            // SOC delta to be non-trivial (>5%) so a normal 1% jiggle doesn't
            // false-positive on a healthy coulomb counter.
            if (currentSocPercent > 0 && currentSocPercent <= 100) {
                if (capacityAhFirstSocSeen < 0) {
                    capacityAhFirstSocSeen = currentSocPercent;
                    capacityAhFirstAhSeen = bmsReportedAh;
                } else {
                    double socDelta = Math.abs(currentSocPercent - capacityAhFirstSocSeen);
                    if (socDelta > 5.0) {
                        double ahDelta = Math.abs(bmsReportedAh - capacityAhFirstAhSeen);
                        // Coulomb-count Ah varies <1% even after substantial
                        // SOC swings (it's the FULL-charge capacity, not
                        // remaining). If Δah > 5% of starting reading on a
                        // >5% SOC swing, it's tracking SOC.
                        if (ahDelta > capacityAhFirstAhSeen * 0.05) {
                            capacityAhSocCoupledCount++;
                            if (capacityAhSocCoupledCount >= CAPACITY_AH_SOC_COUPLED_TRIPS) {
                                capacityAhDisabled = true;
                                persistEstimate();   // survive daemon restart
                                logger.warn("Capacity-Ah anchor disabled: BMS reading"
                                    + " tracks SOC (firmware returns 0.1-kWh-remaining,"
                                    + " not coulomb-count Ah)");
                                return;
                            }
                        } else {
                            // Decoupled — reset the streak and re-anchor the
                            // baseline so a later coupling event has a fresh
                            // reference.
                            capacityAhSocCoupledCount = 0;
                            capacityAhFirstSocSeen = currentSocPercent;
                            capacityAhFirstAhSeen = bmsReportedAh;
                        }
                    }
                }
            }

            // Skip duplicate readings — log clutter and pointless rewrites.
            if (Math.abs(bmsReportedAh - lastCapacityAhReading) < 0.05) return;
            lastCapacityAhReading = bmsReportedAh;

            double soh = (bmsReportedAh / nominalAh) * 100.0;
            // Reject only genuinely-implausible reads (gross frame error). A
            // reading a little above nominal (BMS Ah slightly > factory rating, or
            // nominal entered a touch low) is clamped to the 100% ceiling, not
            // discarded — SOH cannot exceed 100%, but the pack is clearly healthy.
            if (soh < MIN_SOH || soh > 130.0) {
                logger.debug("Capacity-Ah anchor rejected: " + String.format("%.1f", soh)
                    + "% outside " + MIN_SOH + "-130 range (reported=" + String.format("%.1f", bmsReportedAh)
                    + " Ah, nominal=" + String.format("%.1f", nominalAh) + " Ah)");
                return;
            }
            if (soh > MAX_SOH) soh = MAX_SOH;

            capacityAhSoh = soh;
            capacityAhTimestampMs = System.currentTimeMillis();
            persistEstimate();

            logger.info("Capacity-Ah anchor: " + String.format("%.1f", soh)
                + "% (reported=" + String.format("%.1f", bmsReportedAh) + " Ah, nominal="
                + String.format("%.1f", nominalAh) + " Ah, " + cellCount + "s cells)");
        }
    }

    /**
     * PHEV-only peak-charge frame anchor. Track the maximum {@code remainKwh}
     * the BMS reports while SOC is at or above {@link #PEAK_REMAIN_KWH_FULL_SOC_THRESHOLD}.
     *
     * <p>Why: on Blade DM-i PHEVs the BMS reports {@code remainKwh} in the
     * usable frame (e.g. ~12.9 kWh full on a Tang DM-i with 21.5 kWh nameplate)
     * while users tend to enter the nameplate value as nominal. The live SOH
     * formula {@code remainKwh / (SOC/100) / nominal × 100} then computes
     * something like 12.9 / 21.5 × 100 ≈ 60% indistinguishable from a
     * genuinely-degraded battery. The peak observed at full charge is exactly
     * the BMS's view of "100% SOC" — comparing it to nominal lets us a) emit
     * a frame-aware SOH that doesn't mistake unit-mismatch for degradation,
     * and b) surface the mismatch in the UI so the user can correct nominal.
     *
     * <p>BEV calls early-return — BEV {@code remainKwh} is already in the
     * nominal frame and the live formula is sufficient.
     *
     * <p>Anchor stabilizes after {@link #PEAK_REMAIN_KWH_REQUIRED_SAMPLES}
     * observations to avoid pinning to a single tick of HAL noise. Each
     * higher-than-current observation replaces the anchor (true peak); equal
     * or lower observations only bump the sample counter.
     */
    public void observePeakAtFullCharge(double remainKwh, double socPercent, boolean isPhev) {
        synchronized (autoDetectLock) {
            if (!isPhev) return;
            if (nominalCapacityKwh <= 0) return;
            if (socPercent < PEAK_REMAIN_KWH_FULL_SOC_THRESHOLD) return;
            if (remainKwh <= 0 || remainKwh > MAX_PLAUSIBLE_KWH) return;
            // SOC-as-kWh PHEV firmware bug: HAL echoes SOC% (≈100) into the
            // remainKwh field at full charge. Reject when remainKwh is suspiciously
            // close to socPercent — that's the bug pattern, not a real reading.
            if (Math.abs(remainKwh - socPercent) < 3.0) return;

            boolean firstObservation = (peakRemainKwhAtFull <= 0);
            if (remainKwh > peakRemainKwhAtFull) {
                double prev = peakRemainKwhAtFull;
                peakRemainKwhAtFull = remainKwh;
                peakRemainKwhTimestampMs = System.currentTimeMillis();
                peakRemainKwhSamples = Math.min(peakRemainKwhSamples + 1, PEAK_REMAIN_KWH_REQUIRED_SAMPLES);
                if (firstObservation) {
                    double ratio = remainKwh / nominalCapacityKwh;
                    logger.info("Peak frame anchor seeded: " + String.format("%.2f", remainKwh)
                        + " kWh at " + String.format("%.0f", socPercent) + "% SOC (nominal="
                        + String.format("%.1f", nominalCapacityKwh) + " kWh, ratio="
                        + String.format("%.2f", ratio) + ")");
                    if (ratio < 0.85) {
                        logger.warn("Frame mismatch: peak observed kWh ("
                            + String.format("%.2f", remainKwh) + ") is "
                            + String.format("%.0f", ratio * 100)
                            + "% of user nominal (" + String.format("%.1f", nominalCapacityKwh)
                            + ") — user may have entered nameplate vs usable. SOH dialog will flag.");
                    }
                } else {
                    logger.debug("Peak frame anchor raised: " + String.format("%.2f", prev)
                        + " → " + String.format("%.2f", remainKwh) + " kWh");
                }
                persistEstimate();
            } else if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) {
                peakRemainKwhSamples++;
                if (peakRemainKwhSamples == PEAK_REMAIN_KWH_REQUIRED_SAMPLES) {
                    logger.info("Peak frame anchor stabilized at " + String.format("%.2f", peakRemainKwhAtFull)
                        + " kWh after " + peakRemainKwhSamples + " samples");
                    persistEstimate();
                }
            }

            // Notification gate. We only fire after the anchor has stabilized
            // AND the ratio is below threshold AND we haven't already notified
            // this anchor lifetime. The flag is wiped along with peak* fields
            // on every reset / nominal change, so re-detection re-notifies.
            maybeFireFrameMismatchNotification();
        }
    }

    /**
     * Publish the frame-mismatch notification once per anchor lifetime.
     * Called from observePeakAtFullCharge after every state mutation; the
     * persisted flag {@link #peakMismatchNotified} keeps it single-shot.
     */
    private void maybeFireFrameMismatchNotification() {
        if (peakMismatchNotified) return;
        if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) return;
        if (peakRemainKwhAtFull <= 0 || nominalCapacityKwh <= 0) return;
        double ratio = peakRemainKwhAtFull / nominalCapacityKwh;
        if (ratio >= 0.85) return;
        try {
            JSONObject data = new JSONObject();
            data.put("peakKwh", Math.round(peakRemainKwhAtFull * 100) / 100.0);
            data.put("nominalKwh", Math.round(nominalCapacityKwh * 10) / 10.0);
            data.put("ratio", Math.round(ratio * 100) / 100.0);
            String peakStr = String.format(java.util.Locale.US, "%.1f", peakRemainKwhAtFull);
            String nomStr = String.format(java.util.Locale.US, "%.1f", nominalCapacityKwh);
            String title = com.overdrive.app.server.Messages.get(
                "notifications.soh_frame_mismatch_title");
            String body = com.overdrive.app.server.Messages.get(
                "notifications.soh_frame_mismatch_body", peakStr, nomStr);
            com.overdrive.app.notifications.NotificationBus.get().publish(
                new com.overdrive.app.notifications.NotificationEvent(
                    "vehicle.health.soh.frame_mismatch",
                    com.overdrive.app.notifications.NotificationEvent.Severity.WARN,
                    title,
                    body,
                    "soh-frame-mismatch",
                    null,   // category default click URL
                    data));
            peakMismatchNotified = true;
            persistEstimate();
            logger.info("Published frame-mismatch notification (peak="
                + peakStr + " kWh, nominal=" + nomStr + " kWh, ratio="
                + String.format("%.2f", ratio) + ")");
        } catch (Throwable t) {
            // Bus failure must not break the SOH pipeline. Leave flag false
            // so a later observation retries.
            logger.debug("Frame-mismatch notification publish failed: " + t.getMessage());
        }
    }

    /**
     * SOH derived from the peak frame anchor. {@code -1} until the anchor has
     * stabilized at {@link #PEAK_REMAIN_KWH_REQUIRED_SAMPLES} samples; this
     * gate prevents a single noisy peak from displaying a misleading SOH
     * before enough confirming observations exist.
     */
    public double getFrameAnchorSoh() {
        if (peakRemainKwhAtFull <= 0 || nominalCapacityKwh <= 0) return -1;
        if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) return -1;
        double soh = (peakRemainKwhAtFull / nominalCapacityKwh) * 100.0;
        if (soh < MIN_SOH) return MIN_SOH;
        if (soh > MAX_SOH) return MAX_SOH;
        return soh;
    }

    // ==================== GETTERS ====================

    /** Capacity-derived diagnostic estimate; presentation consumers use {@link #getDisplaySoh()}. */
    public double getCurrentSoh() { return currentSoh; }

    /** One immutable result from the canonical SOH priority chain. */
    public static final class ResolvedSoh {
        private final double percent;
        private final String source;
        private final double oemPercent;

        ResolvedSoh(double percent, String source, double oemPercent) {
            this.percent = percent;
            this.source = source;
            this.oemPercent = oemPercent;
        }

        public double getPercent() { return percent; }
        public String getSource() { return source; }
        public double getOemPercent() { return oemPercent; }
    }

    /**
     * Pure, unit-testable SOH resolver used by every public presentation.
     *
     * <p>Priority order:
     * <ul>
     *   <li>PHEV: OEM &gt; frame anchor &gt; calibration &gt; live</li>
     *   <li>BEV: OEM &gt; live &gt; calibration</li>
     * </ul>
     *
     * <p>The vehicle/BMS health index is authoritative on both BEV and PHEV. It is the
     * same slowly-changing battery-health value exposed to diagnostic tools and does not
     * depend on instantaneous SOC, remaining-energy quantisation, temperature, or the
     * configured nominal capacity. Capacity-derived estimates remain useful fallbacks on
     * vehicles that do not expose the OEM index, but must never override it.
     *
     * <p>Returns {@code -1} when no source has a real value yet.
     *
     * <p>This method intentionally mirrors the JSON priority chain in
     * {@link #getStatus()} — keep them in sync. PHEV gating is read from
     * {@link com.overdrive.app.byd.BydDataCollector}; the lookup is best-
     * effort, falling back to BEV behaviour when the collector isn't ready.
     */
    public double getDisplaySoh() {
        return getCapacitySohSnapshot().getDisplaySoh();
    }

    /** Immutable nominal/source publication captured under autoDetectLock. */
    public static final class NominalSnapshot {
        private final double nominalCapacityKwh;
        private final String nominalSource;
        private final long resetModelEpoch;

        private NominalSnapshot(
                double nominalCapacityKwh,
                String nominalSource,
                long resetModelEpoch) {
            this.nominalCapacityKwh = nominalCapacityKwh;
            this.nominalSource = nominalSource;
            this.resetModelEpoch = resetModelEpoch;
        }

        public double getNominalCapacityKwh() {
            return nominalCapacityKwh;
        }

        public String getNominalSource() {
            return nominalSource;
        }

        public long getResetModelEpoch() {
            return resetModelEpoch;
        }
    }

    /**
     * Publish nominal capacity and source from one estimator generation. This
     * method intentionally performs no drivetrain or collector probing.
     */
    public NominalSnapshot getNominalSnapshot() {
        synchronized (autoDetectLock) {
            return new NominalSnapshot(
                nominalCapacityKwh,
                nominalSource,
                resetModelEpoch);
        }
    }

    /**
     * Immutable pair used by energy/session consumers that must not combine a
     * nominal capacity from one estimator generation with display SOH from
     * another.
     */
    public static final class CapacitySohSnapshot {
        private final double nominalCapacityKwh;
        private final double displaySoh;
        private final long estimatorGeneration;
        private final long resetModelEpoch;

        private CapacitySohSnapshot(
                double nominalCapacityKwh,
                double displaySoh,
                long estimatorGeneration,
                long resetModelEpoch) {
            this.nominalCapacityKwh = nominalCapacityKwh;
            this.displaySoh = displaySoh;
            this.estimatorGeneration = estimatorGeneration;
            this.resetModelEpoch = resetModelEpoch;
        }

        public double getNominalCapacityKwh() {
            return nominalCapacityKwh;
        }

        public double getDisplaySoh() {
            return displaySoh;
        }

        public long getEstimatorGeneration() {
            return estimatorGeneration;
        }

        public long getResetModelEpoch() {
            return resetModelEpoch;
        }

        public boolean hasDisplaySoh() {
            return displaySoh > 0;
        }
    }

    /**
     * Checked operation executed while the estimator generation is held stable.
     *
     * <p>The callback must be short and must not call back into vehicle collectors. It exists for
     * external commits whose final generation validation and durability boundary must be atomic.
     */
    @FunctionalInterface
    public interface GenerationGuardedWork {
        void run() throws Exception;
    }

    /**
     * Run {@code work} under {@code autoDetectLock} only when the expected generation is current.
     *
     * @return false without invoking {@code work} when the generation is stale
     */
    public boolean runWithEstimatorGenerationGuard(
            long expectedGeneration, GenerationGuardedWork work) throws Exception {
        if (work == null) {
            throw new IllegalArgumentException("generation-guarded work is required");
        }
        synchronized (autoDetectLock) {
            if (estimatorGeneration != expectedGeneration) return false;
            work.run();
            return true;
        }
    }

    /**
     * Run one model-lineage transaction while holding the estimator mutation
     * lock. Callers that also mutate UnifiedConfig must acquire its stable lock
     * first, matching ConfigBackupService's config -> estimator lock order.
     */
    public void runWithEstimatorLock(Runnable work) {
        if (work == null) {
            throw new IllegalArgumentException("estimator-locked work is required");
        }
        synchronized (autoDetectLock) {
            work.run();
        }
    }

    /**
     * Capture nominal capacity and the display-SOH selected from the same
     * lock-held estimator generation.
     *
     * <p>Collector lookups happen before {@code autoDetectLock}: collector code
     * has its own locks and may call into other subsystems. Once those external
     * hints are local values, all estimator fields and the resulting pair are
     * read/computed while holding {@code autoDetectLock}.
     */
    public CapacitySohSnapshot getCapacitySohSnapshot() {
        boolean phev = false;
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                phev = col.isPhevPublic();
            }
        } catch (Throwable ignored) {}
        final double oemSoh = readOemSohPercent();

        return captureCapacitySohSnapshot(phev, oemSoh);
    }

    private CapacitySohSnapshot captureCapacitySohSnapshot(
            boolean phev, double oemSoh) {
        synchronized (autoDetectLock) {
            return new CapacitySohSnapshot(
                nominalCapacityKwh,
                getDisplaySohLocked(phev, oemSoh),
                estimatorGeneration,
                resetModelEpoch);
        }
    }

    private double getDisplaySohLocked(boolean phev, double oemSoh) {
        return resolveDisplaySoh(
            phev, oemSoh, getFrameAnchorSohLocked(), currentSoh, calibrationSoh).percent;
    }

    static ResolvedSoh resolveDisplaySoh(boolean phev, double oemSoh, double frameSoh,
                                         double currentSoh, double calibrationSoh) {
        double validOem = isValidSohPercent(oemSoh) ? oemSoh : -1;
        if (validOem > 0) return new ResolvedSoh(validOem, "oem", validOem);
        if (phev && isValidSohPercent(frameSoh)) {
            return new ResolvedSoh(frameSoh, "frame_anchor", -1);
        }
        if (phev && isValidSohPercent(calibrationSoh)) {
            return new ResolvedSoh(calibrationSoh, "calibration", -1);
        }
        if (isValidSohPercent(currentSoh)) {
            return new ResolvedSoh(currentSoh, "live", -1);
        }
        if (isValidSohPercent(calibrationSoh)) {
            return new ResolvedSoh(calibrationSoh, "calibration", -1);
        }
        return new ResolvedSoh(-1, "unavailable", -1);
    }

    private static boolean isValidSohPercent(double value) {
        return !Double.isNaN(value) && value > 0 && value <= MAX_SOH;
    }

    /** Canonical SOH value and provenance for UI, APIs and integrations. */
    public ResolvedSoh getResolvedSoh() {
        // Collector calls stay outside autoDetectLock: its own locks can call back into
        // estimator consumers, so nesting them would invite a cross-subsystem deadlock.
        boolean phev = false;
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                phev = col.isPhevPublic();
            }
        } catch (Throwable ignored) {}
        final double oemSoh = readOemSohPercent();
        synchronized (autoDetectLock) {
            return resolveDisplaySoh(
                phev, oemSoh, getFrameAnchorSohLocked(), currentSoh, calibrationSoh);
        }
    }

    /**
     * The vehicle's OWN state-of-health index, as reported by the OEM
     * ({@code STATISTIC_BATTERY_HEALTHY_INDEX}) and surfaced on the snapshot as
     * {@code sohPercent}. Already a plain 0..100 percent — the collector validates the
     * range before publishing — so it needs no scaling, no nominal capacity, and no
     * usable-vs-gross frame assumption. It is authoritative for both BEV and PHEV;
     * capacity-derived values are fallbacks only when this index is unavailable.
     *
     * @return the OEM percent in (0,100], or -1 when the trim doesn't report it.
     */
    private double readOemSohPercent() {
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col == null || !col.isInitialized()) return -1;
            com.overdrive.app.byd.BydVehicleData vd = col.getData();
            if (vd == null) return -1;
            double soh = vd.sohPercent;
            // Reject the unset sentinel, junk, and the meaningless 0 — a real pack is
            // never 0% healthy, so 0 means "not reported" on this HAL.
            if (Double.isNaN(soh) || soh <= 0 || soh > 100) return -1;
            return soh;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Lock-held variant of {@link #getFrameAnchorSoh()} for callers that have
     * already acquired {@code autoDetectLock} and need a consistent snapshot
     * with sibling fields. Identical formula — see that method for behavior.
     */
    private double getFrameAnchorSohLocked() {
        if (peakRemainKwhAtFull <= 0 || nominalCapacityKwh <= 0) return -1;
        if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) return -1;
        double soh = (peakRemainKwhAtFull / nominalCapacityKwh) * 100.0;
        if (soh < MIN_SOH) return MIN_SOH;
        if (soh > MAX_SOH) return MAX_SOH;
        return soh;
    }

    /** True when {@link #getDisplaySoh()} would return a real value. */
    public boolean hasDisplaySoh() {
        return getCapacitySohSnapshot().hasDisplaySoh();
    }

    public double getCalibrationSoh() { return calibrationSoh; }
    public long getCalibrationTimestampMs() { return calibrationTimestampMs; }
    public double getCapacityAhSoh() { return capacityAhSoh; }
    public long getCapacityAhTimestampMs() { return capacityAhTimestampMs; }
    public double getPeakRemainKwhAtFull() { return peakRemainKwhAtFull; }
    public long getPeakRemainKwhTimestampMs() { return peakRemainKwhTimestampMs; }
    public int getPeakRemainKwhSamples() { return peakRemainKwhSamples; }
    public boolean hasEstimate() { return currentSoh > 0; }

    public double getEstimatedCapacityKwh() {
        CapacitySohSnapshot snapshot = getCapacitySohSnapshot();
        if (snapshot.getNominalCapacityKwh() <= 0) return -1;
        // Use the DISPLAYED SOH (capped, anchored) so the estimated-capacity kWh
        // on the health card agrees with the SOH percent shown right beside it —
        // previously this used currentSoh (live median) while the percent used
        // getDisplaySoh, so the card could read "100% / 20.0 kWh of 21.5" (=93%).
        double s = snapshot.getDisplaySoh();
        if (s <= 0) return -1;
        return (s / 100.0) * snapshot.getNominalCapacityKwh();
    }

    // ==================== RESET ====================

    public void reset() {
        userNominalConfig.runUnderConfigLock(() -> {
            ConfigNominalSnapshot configuredUser =
                readConfiguredUserNominalSnapshot();
            synchronized (autoDetectLock) {
                resetLocked(configuredUser);
            }
        });
    }

    /**
     * Reset from a strict immutable config snapshot. Callers may hold the
     * estimator lock already, but must have captured this value before taking
     * it and while holding the stable config lock.
     */
    public void resetFromConfigSnapshot(double configuredUserKwh) {
        double validated = validateConfiguredUserNominal(configuredUserKwh);
        synchronized (autoDetectLock) {
            resetLocked(ConfigNominalSnapshot.available(validated));
        }
    }

    private void resetLocked(ConfigNominalSnapshot configuredUser) {
        long replacementEpoch =
            nextResetModelEpoch(resetModelEpoch);
        // Retry a committed-but-unsynced rename before acknowledging the
        // reset. A normal return therefore means the old estimate cannot
        // reappear after a crash.
        PersistenceOutcome tombstoneOutcome =
            persistClearedStateTombstoneWithDurabilityRetries(
                "reset SOH estimator", replacementEpoch);
        if (!tombstoneOutcome.wasCommitted()) {
            throw new IllegalStateException(
                "Could not commit reset SOH estimator tombstone");
        }

        double previous = nominalCapacityKwh;
        clearEstimateStateLocked(true);
        nominalCapacityKwh = 0;
        nominalSource = "unset";
        resetModelEpoch = replacementEpoch;
        estimatorGeneration++;
        if (previous > 0) {
            invalidateActiveTripKwhBaseline("SohEstimator.reset()");
        }

        if (configuredUser.available && configuredUser.value > 0) {
            nominalCapacityKwh = configuredUser.value;
            nominalSource = "user";
            estimatorGeneration++;
        }
        initializationState = configuredUser.available
            ? InitializationState.READY : InitializationState.DEFERRED;

        if (tombstoneOutcome != PersistenceOutcome.DURABLE) {
            initializationState = InitializationState.DEFERRED;
            throw new IllegalStateException(
                "Reset SOH estimator tombstone was committed but not durable");
        }

        if ("user".equals(nominalSource)) {
            logger.info("SOH estimation RESET — local data cleared, user nominal " +
                nominalCapacityKwh + " kWh restored from UnifiedConfig.");
        } else {
            logger.info("SOH estimation RESET — local data cleared (no user nominal set).");
        }
    }

    private ConfigNominalSnapshot readConfiguredUserNominalSnapshot() {
        try {
            return ConfigNominalSnapshot.available(
                validateConfiguredUserNominal(userNominalConfig.read()));
        } catch (Throwable t) {
            logger.debug("Reset: UnifiedConfig user nominal read failed: "
                + t.getMessage());
            return ConfigNominalSnapshot.unavailable();
        }
    }

    private static double validateConfiguredUserNominal(double configuredUserKwh) {
        if (Double.isNaN(configuredUserKwh)
                || Double.isInfinite(configuredUserKwh)
                || configuredUserKwh < 0
                || configuredUserKwh > MAX_PLAUSIBLE_KWH
                || (configuredUserKwh > 0
                    && configuredUserKwh < MIN_PLAUSIBLE_KWH_PHEV)) {
            throw new IllegalStateException(
                "UnifiedConfig nominalKwh is invalid: " + configuredUserKwh);
        }
        return configuredUserKwh;
    }

    private static final class ConfigNominalSnapshot {
        final boolean available;
        final double value;

        private ConfigNominalSnapshot(boolean available, double value) {
            this.available = available;
            this.value = value;
        }

        static ConfigNominalSnapshot available(double value) {
            return new ConfigNominalSnapshot(true, value);
        }

        static ConfigNominalSnapshot unavailable() {
            return new ConfigNominalSnapshot(false, 0);
        }
    }

    // ==================== STATUS ====================

    public org.json.JSONObject getStatus() {
        org.json.JSONObject status = new org.json.JSONObject();
        boolean phev = false;
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                phev = col.isPhevPublic();
            }
        } catch (Throwable ignored) {}
        final double oemSoh = readOemSohPercent();

        String modelId = null;
        try {
            modelId = UnifiedConfigManager.getSelectedVehicleModelId();
        } catch (Throwable ignored) {}

        final StatusSnapshot snapshot =
            captureStatusSnapshot(phev, oemSoh);
        try {
            // Keep the legacy headline field canonical while retaining the moving
            // capacity-derived estimate explicitly for diagnostics.
            status.put("soh", roundedOrUnavailable(snapshot.displaySoh));
            status.put("estimatedSoh", roundedOrUnavailable(snapshot.currentSoh));
            status.put(
                "nominalCapacityKwh", snapshot.nominalCapacityKwh);
            status.put(
                "estimatedCapacityKwh",
                roundedOrUnavailable(snapshot.estimatedCapacityKwh));
            status.put("hasEstimate", snapshot.displaySoh > 0);
            status.put("nominalSource", snapshot.nominalSource);

            org.json.JSONObject calibration = new org.json.JSONObject();
            calibration.put(
                "soh", roundedOrUnavailable(snapshot.calibrationSoh));
            calibration.put(
                "timestampMs", snapshot.calibrationTimestampMs);
            status.put("calibration", calibration);

            // Capacity-Ah anchor — RETIRED as a SOH source (getBatteryCapacity is
            // a static nameplate on DM-i, not a coulomb count). Key kept in the
            // response for shape stability, but soh is always -1 / disabled=true so
            // no consumer or debugger sees a stale persisted anchor value (e.g. a
            // 76% left on disk from before retirement) next to a 100% headline.
            org.json.JSONObject capAh = new org.json.JSONObject();
            capAh.put("soh", -1);
            capAh.put("timestampMs", 0);
            capAh.put("disabled", true);
            status.put("capacityAh", capAh);

            // Frame anchor (PHEV-only). Empirically captures the BMS's view
            // of "100% SOC" by tracking peak remainKwh observed at SOC≥99%.
            // ratio = peakRemainKwhAtFull / nominalCapacityKwh tells us
            // whether the user's nominal entry matches the BMS's frame
            // (~1.0 = match) or differs (~0.6 on Tang DM-i nameplate vs usable).
            org.json.JSONObject frameAnchor = new org.json.JSONObject();
            frameAnchor.put(
                "soh", roundedOrUnavailable(snapshot.frameSoh));
            frameAnchor.put(
                "peakKwh",
                snapshot.peakRemainKwhAtFull > 0
                    ? Math.round(snapshot.peakRemainKwhAtFull * 100) / 100.0
                    : -1);
            frameAnchor.put("samples", snapshot.peakRemainKwhSamples);
            frameAnchor.put("requiredSamples", PEAK_REMAIN_KWH_REQUIRED_SAMPLES);
            frameAnchor.put(
                "timestampMs", snapshot.peakRemainKwhTimestampMs);
            frameAnchor.put(
                "ratio",
                snapshot.frameRatio > 0
                    ? Math.round(snapshot.frameRatio * 100) / 100.0
                    : -1);
            // Frame mismatch threshold: < 0.85 means user nominal is ~17%+
            // larger than BMS's full-charge reading. The most common cause is
            // user-entered nameplate (e.g. 21.5 kWh Tang DM-i) vs BMS-reported
            // usable (~12.9 kWh). Set when the anchor has stabilized.
            frameAnchor.put("mismatch",
                snapshot.frameRatio > 0 && snapshot.frameRatio < 0.85
                    && snapshot.peakRemainKwhSamples
                        >= PEAK_REMAIN_KWH_REQUIRED_SAMPLES);
            status.put("frameAnchor", frameAnchor);

            status.put(
                "displaySoh",
                roundedOrUnavailable(snapshot.displaySoh));
            status.put("displaySource", snapshot.displaySource);
            // Surface the OEM index itself (-1 when the trim doesn't report it) so
            // /api/performance/soh answers "is there a real vehicle-reported SOH here?"
            // without needing a logcat capture. This is the value the PHEV chain prefers.
            status.put("oemSoh", roundedOrUnavailable(snapshot.oemSoh));

            if (modelId != null && !modelId.isEmpty()) {
                status.put("modelId", modelId);
            } else {
                status.put("modelId", JSONObject.NULL);
            }
            if (snapshot.lastUpdatedMs > 0) {
                status.put("lastUpdated", snapshot.lastUpdatedMs);
            }
        } catch (Exception e) {
            logger.error("Failed to build SOH status: " + e.getMessage());
        }
        return status;
    }

    private StatusSnapshot captureStatusSnapshot(
            boolean phev, double oemSoh) {
        synchronized (autoDetectLock) {
            double frameSoh = getFrameAnchorSohLocked();
            ResolvedSoh resolved = resolveDisplaySoh(
                phev, oemSoh, frameSoh, currentSoh, calibrationSoh);
            double displaySoh = resolved.percent;
            String displaySource = resolved.source;

            double frameRatio =
                peakRemainKwhAtFull > 0 && nominalCapacityKwh > 0
                    ? peakRemainKwhAtFull / nominalCapacityKwh
                    : -1;
            double estimatedCapacityKwh =
                displaySoh > 0 && nominalCapacityKwh > 0
                    ? displaySoh / 100.0 * nominalCapacityKwh
                    : -1;
            return new StatusSnapshot(
                currentSoh,
                nominalCapacityKwh,
                nominalSource,
                estimatedCapacityKwh,
                calibrationSoh,
                calibrationTimestampMs,
                peakRemainKwhAtFull,
                peakRemainKwhSamples,
                peakRemainKwhTimestampMs,
                frameSoh,
                frameRatio,
                displaySoh,
                displaySource,
                oemSoh,
                readPersistedLastUpdatedLocked());
        }
    }

    private long readPersistedLastUpdatedLocked() {
        if (!sohFile.exists()) return 0;
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(sohFile)) {
                props.load(fis);
            }
            return Long.parseLong(
                props.getProperty(PROP_LAST_UPDATED, "0"));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static double roundedOrUnavailable(double value) {
        return value > 0 ? Math.round(value * 10) / 10.0 : -1;
    }

    private static final class StatusSnapshot {
        final double currentSoh;
        final double nominalCapacityKwh;
        final String nominalSource;
        final double estimatedCapacityKwh;
        final double calibrationSoh;
        final long calibrationTimestampMs;
        final double peakRemainKwhAtFull;
        final int peakRemainKwhSamples;
        final long peakRemainKwhTimestampMs;
        final double frameSoh;
        final double frameRatio;
        final double displaySoh;
        final String displaySource;
        final double oemSoh;
        final long lastUpdatedMs;

        StatusSnapshot(
                double currentSoh,
                double nominalCapacityKwh,
                String nominalSource,
                double estimatedCapacityKwh,
                double calibrationSoh,
                long calibrationTimestampMs,
                double peakRemainKwhAtFull,
                int peakRemainKwhSamples,
                long peakRemainKwhTimestampMs,
                double frameSoh,
                double frameRatio,
                double displaySoh,
                String displaySource,
                double oemSoh,
                long lastUpdatedMs) {
            this.currentSoh = currentSoh;
            this.nominalCapacityKwh = nominalCapacityKwh;
            this.nominalSource = nominalSource;
            this.estimatedCapacityKwh = estimatedCapacityKwh;
            this.calibrationSoh = calibrationSoh;
            this.calibrationTimestampMs = calibrationTimestampMs;
            this.peakRemainKwhAtFull = peakRemainKwhAtFull;
            this.peakRemainKwhSamples = peakRemainKwhSamples;
            this.peakRemainKwhTimestampMs = peakRemainKwhTimestampMs;
            this.frameSoh = frameSoh;
            this.frameRatio = frameRatio;
            this.displaySoh = displaySoh;
            this.displaySource = displaySource;
            this.oemSoh = oemSoh;
            this.lastUpdatedMs = lastUpdatedMs;
        }
    }

    // ==================== PERSISTENCE ====================

    private static Properties newPersistenceProperties(
            boolean stateCleared, long resetModelEpoch) {
        return newPersistenceProperties(
            stateCleared, resetModelEpoch, System.currentTimeMillis());
    }

    private static Properties newPersistenceProperties(
            boolean stateCleared, long resetModelEpoch, long lastUpdatedMs) {
        if (resetModelEpoch <= 0) {
            throw new IllegalStateException(
                "A positive reset/model epoch is required for persistence");
        }
        Properties props = new Properties();
        props.setProperty(
            PROP_SCHEMA_VERSION, String.valueOf(CURRENT_SCHEMA_VERSION));
        props.setProperty(
            PROP_LAST_UPDATED,
            String.valueOf(lastUpdatedMs > 0
                ? lastUpdatedMs : System.currentTimeMillis()));
        props.setProperty(
            PROP_RESET_MODEL_EPOCH,
            String.valueOf(resetModelEpoch));
        if (stateCleared) {
            props.setProperty(PROP_STATE_CLEARED, "true");
        }
        return props;
    }

    private static Properties nominalOnlyProperties(
            RestoredState restored, boolean stateCleared) {
        Properties properties =
            newPersistenceProperties(
                stateCleared,
                restored.resetModelEpoch,
                restored.persistedLastUpdatedMs);
        if (restored.nominalCapacityKwh > 0) {
            putNominalIdentity(
                properties,
                restored.nominalCapacityKwh,
                restored.nominalSource);
        }
        return properties;
    }

    private static Properties completePersistenceProperties(
            RestoredState restored, boolean stateCleared) {
        Properties props =
            nominalOnlyProperties(restored, stateCleared);
        if (restored.currentSoh > 0 && restored.currentSoh <= 110) {
            props.setProperty(
                PROP_SOH_PERCENT,
                String.valueOf(restored.currentSoh));
        }
        if (restored.calibrationSoh > 0) {
            props.setProperty(
                PROP_CALIBRATION_SOH,
                String.valueOf(restored.calibrationSoh));
        }
        if (restored.calibrationTimestampMs > 0) {
            props.setProperty(
                PROP_CALIBRATION_TIMESTAMP,
                String.valueOf(restored.calibrationTimestampMs));
        }
        if (restored.capacityAhSoh > 0) {
            props.setProperty(
                PROP_CAPACITY_AH_SOH,
                String.valueOf(restored.capacityAhSoh));
        }
        if (restored.capacityAhTimestampMs > 0) {
            props.setProperty(
                PROP_CAPACITY_AH_TIMESTAMP,
                String.valueOf(restored.capacityAhTimestampMs));
        }
        if (restored.capacityAhDisabled) {
            props.setProperty(PROP_CAPACITY_AH_DISABLED, "true");
        }
        if (!restored.liveHistory.isEmpty()) {
            StringBuilder history = new StringBuilder();
            for (Double value : restored.liveHistory) {
                if (history.length() > 0) history.append(',');
                history.append(value);
            }
            props.setProperty(PROP_LIVE_HISTORY, history.toString());
        }
        if (restored.peakRemainKwhAtFull > 0) {
            props.setProperty(
                PROP_PEAK_REMAIN_KWH,
                String.valueOf(restored.peakRemainKwhAtFull));
            props.setProperty(
                PROP_PEAK_REMAIN_KWH_SAMPLES,
                String.valueOf(restored.peakRemainKwhSamples));
            props.setProperty(
                PROP_PEAK_REMAIN_KWH_TS,
                String.valueOf(restored.peakRemainKwhTimestampMs));
        }
        if (restored.peakMismatchNotified) {
            props.setProperty(PROP_PEAK_REMAIN_KWH_NOTIFIED, "true");
        }
        return props;
    }

    private RestoredState captureRestoredStateLocked() {
        RestoredState state = new RestoredState();
        state.nominalCapacityKwh = nominalCapacityKwh;
        state.nominalSource = nominalSource;
        state.resetModelEpoch = resetModelEpoch;
        state.currentSoh = currentSoh;
        state.calibrationSoh = calibrationSoh;
        state.calibrationTimestampMs = calibrationTimestampMs;
        state.capacityAhSoh = capacityAhSoh;
        state.capacityAhTimestampMs = capacityAhTimestampMs;
        state.capacityAhDisabled = capacityAhDisabled;
        state.liveHistory.addAll(liveHistory);
        state.peakRemainKwhAtFull = peakRemainKwhAtFull;
        state.peakRemainKwhSamples = peakRemainKwhSamples;
        state.peakRemainKwhTimestampMs = peakRemainKwhTimestampMs;
        state.peakMismatchNotified = peakMismatchNotified;
        return state;
    }

    private static void putNominalIdentity(
            Properties properties, double nominalKwh, String source) {
        properties.setProperty(
            PROP_NOMINAL_CAPACITY, String.valueOf(nominalKwh));
        properties.setProperty(PROP_NOMINAL_SOURCE, source);
        properties.setProperty(
            PROP_NOMINAL_IDENTITY,
            nominalIdentity(nominalKwh, source));
    }

    /**
     * Atomically replace every persisted estimate with a minimal clear marker.
     * Success includes syncing the replacement file and its parent directory.
     */
    private PersistenceOutcome persistClearedStateTombstone()
            throws IOException {
        return persistClearedStateTombstone(resetModelEpoch);
    }

    private PersistenceOutcome persistClearedStateTombstone(
            long replacementEpoch) throws IOException {
        synchronized (autoDetectLock) {
            return publishProperties(
                newPersistenceProperties(true, replacementEpoch));
        }
    }

    private PersistenceOutcome
            persistClearedStateTombstoneWithDurabilityRetries(
                    String operation) {
        return persistClearedStateTombstoneWithDurabilityRetries(
            operation, resetModelEpoch);
    }

    private PersistenceOutcome
            persistClearedStateTombstoneWithDurabilityRetries(
                    String operation, long replacementEpoch) {
        Properties tombstone =
            newPersistenceProperties(true, replacementEpoch);
        try {
            return publishPropertiesWithDurabilityRetries(
                tombstone, operation);
        } catch (IOException impossible) {
            return PersistenceOutcome.FAILED;
        }
    }

    private PersistenceOutcome publishPropertiesWithDurabilityRetries(
            Properties properties, String operation) throws IOException {
        boolean committed = false;
        String lastFailure = null;
        for (int attempt = 1;
                attempt <= CLEAR_TOMBSTONE_DURABILITY_ATTEMPTS;
                attempt++) {
            try {
                PersistenceOutcome outcome =
                    publishProperties(properties);
                if (outcome == PersistenceOutcome.DURABLE) {
                    return outcome;
                }
                committed = true;
                lastFailure = "parent directory sync remained uncertain";
            } catch (IOException e) {
                lastFailure = e.getMessage();
            }

            if (attempt < CLEAR_TOMBSTONE_DURABILITY_ATTEMPTS) {
                logger.warn("SOH " + operation + " durability "
                    + "attempt " + attempt + " failed; retrying");
            }
        }

        logger.error("Failed to durably " + operation + " after "
            + CLEAR_TOMBSTONE_DURABILITY_ATTEMPTS + " attempts"
            + (lastFailure == null ? "" : ": " + lastFailure));
        return committed
            ? PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN
            : PersistenceOutcome.FAILED;
    }

    private void persistClearedStateTombstoneOrThrow(String operation) {
        PersistenceOutcome outcome =
            persistClearedStateTombstoneWithDurabilityRetries(operation);
        if (outcome != PersistenceOutcome.DURABLE) {
            throw new IllegalStateException(
                "Could not durably " + operation
                    + (outcome.wasCommitted()
                        ? " after committing the tombstone"
                        : ""));
        }
    }

    /**
     * Write a fresh schema-reset snapshot, preserving only a validated nominal
     * capacity when migration retained one.
     */
    private void writeSchemaStamp() {
        synchronized (autoDetectLock) {
            try {
                // Start fresh rather than loading and pruning the legacy file.
                // Unknown/stale keys must not survive a schema reset and later
                // become meaningful again.
                long replacementEpoch = resetModelEpoch > 0
                    ? nextResetModelEpoch(resetModelEpoch)
                    : initialResetModelEpoch();
                RestoredState replacement = new RestoredState();
                replacement.nominalCapacityKwh = nominalCapacityKwh;
                replacement.nominalSource = nominalSource;
                replacement.resetModelEpoch = replacementEpoch;
                Properties props =
                    nominalOnlyProperties(replacement, true);
                if (nominalCapacityKwh > 0) {
                    putNominalIdentity(
                        props, nominalCapacityKwh, nominalSource);
                }
                PersistenceOutcome outcome =
                    publishPropertiesWithDurabilityRetries(
                        props, "stamp SOH schema");
                if (outcome.wasCommitted()) {
                    resetModelEpoch = replacementEpoch;
                }
            } catch (Exception e) {
                logger.error("Failed to stamp schema version: " + e.getMessage());
            }
        }
    }

    private void persistEstimate() {
        persistEstimateWithOutcome();
    }

    private boolean persistEstimateDurably() {
        return persistEstimateWithOutcome()
            == PersistenceOutcome.DURABLE;
    }

    private PersistenceOutcome persistEstimateWithOutcome() {
        synchronized (autoDetectLock) {
            if (currentSoh <= 0 && nominalCapacityKwh <= 0
                    && calibrationSoh <= 0 && calibrationTimestampMs <= 0
                    && capacityAhSoh <= 0 && capacityAhTimestampMs <= 0
                    && !capacityAhDisabled
                    && peakRemainKwhAtFull <= 0 && peakRemainKwhSamples == 0
                    && !peakMismatchNotified) {
                return PersistenceOutcome.FAILED;
            }
            try {
                Properties props =
                    completePersistenceProperties(
                        captureRestoredStateLocked(), false);

                PersistenceOutcome outcome =
                    publishProperties(props);
                if (outcome
                        == PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN) {
                    logger.warn("SOH snapshot rename committed but parent "
                        + "directory sync failed; retaining committed memory "
                        + "and retrying on the next persistence event");
                }
                return outcome;
            } catch (Exception e) {
                logger.error("Failed to persist SOH: " + e.getMessage());
                return PersistenceOutcome.FAILED;
            }
        }
    }

    private PersistenceOutcome publishProperties(Properties properties)
            throws IOException {
        PersistenceOutcome outcome =
            persistenceWriter.write(sohFile, properties);
        if (outcome == null || outcome == PersistenceOutcome.FAILED) {
            throw new IOException("SOH properties were not committed");
        }
        return outcome;
    }

    /**
     * Publish one complete properties snapshot. The temp file is synced before
     * rename and the parent directory is synced afterwards, so success means
     * both file contents and the atomic name replacement were requested durable.
     */
    static void persistPropertiesAtomically(File destination, Properties props)
            throws IOException {
        PersistenceOutcome outcome =
            persistPropertiesWithOutcome(destination, props);
        if (outcome
                == PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN) {
            throw new IOException(
                "SOH properties committed, but directory sync failed");
        }
    }

    private static PersistenceOutcome persistPropertiesWithOutcome(
            File destination, Properties props) throws IOException {
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("SOH persistence directory is unavailable");
        }

        Set<PosixFilePermission> retainedPermissions =
                readRetainedPermissions(destination);
        File temporary = File.createTempFile(
                destination.getName() + ".", ".tmp", parent);
        try {
            applyRetainedPermissions(temporary, retainedPermissions);
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                props.store(output, "ABRP SOH Estimate");
                output.flush();
                output.getFD().sync();
            }

            Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try {
                forceDirectorySync(parent);
                return PersistenceOutcome.DURABLE;
            } catch (IOException directorySyncFailure) {
                // Files.move completed successfully, so the destination now
                // contains this snapshot even though crash durability of the
                // directory entry is uncertain. Report that state explicitly;
                // callers must retain matching memory and retry later.
                return PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN;
            }
        } finally {
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (IOException ignored) {
            }
        }
    }

    private static Set<PosixFilePermission> readRetainedPermissions(
            File destination) throws IOException {
        if (!destination.exists()) return null;
        try {
            Set<PosixFilePermission> retained =
                    EnumSet.noneOf(PosixFilePermission.class);
            retained.addAll(Files.getPosixFilePermissions(
                    destination.toPath(), LinkOption.NOFOLLOW_LINKS));
            return retained;
        } catch (UnsupportedOperationException unsupported) {
            return null;
        }
    }

    private static void applyRetainedPermissions(
            File temporary, Set<PosixFilePermission> retained)
            throws IOException {
        if (retained != null) {
            retained.add(PosixFilePermission.OWNER_READ);
            retained.add(PosixFilePermission.GROUP_READ);
            retained.add(PosixFilePermission.OTHERS_READ);
            Files.setPosixFilePermissions(temporary.toPath(), retained);
            return;
        }
        if (!temporary.setReadable(true, false)) {
            throw new IOException("Could not retain SOH file readability");
        }
    }

    private static void forceDirectorySync(File directory) throws IOException {
        try (FileChannel channel = FileChannel.open(
                directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    // ==================== CHEMISTRY-AWARE SCALES ====================

    private static double displayToAbsoluteSocScale(double highCellVoltage) {
        if (!Double.isNaN(highCellVoltage) && highCellVoltage >= 3.75) {
            return 0.95;
        }
        return 1.0;
    }

    private static double scaleDisplaySoc(double displaySoc, double scale) {
        if (scale >= 0.999) return displaySoc;
        return displaySoc * scale + (1.0 - scale) / 2.0 * 100.0;
    }

    // ==================== MAPPINGS ====================

    private static int nearestKnownAh(int rawAh, int toleranceAh) {
        int[] knownAh = {50, 56, 72, 75, 79, 80, 100, 110, 120, 135, 140,
                         150, 153, 157, 166, 170, 176, 180, 200};
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < knownAh.length; i++) {
            int k = knownAh[i];
            int d = Math.abs(rawAh - k);

            int leftGap = (i == 0) ? Integer.MAX_VALUE : (k - knownAh[i - 1]);
            int rightGap = (i == knownAh.length - 1)
                ? Integer.MAX_VALUE
                : (knownAh[i + 1] - k);
            int halfGap = Math.min(leftGap, rightGap) / 2;
            int safeTolerance = Math.min(toleranceAh, halfGap);

            if (d <= safeTolerance && d < bestDiff) {
                bestDiff = d;
                best = k;
            }
        }
        return best;
    }

    private static double mapAhToKwh(int ah) {
        switch (ah) {
            case 150: return 60.48;
            case 153: return 82.56;
            case 157: return 61.44;
            case 140: return 71.8;
            case 170: return 87.0;
            case 166: return 85.44;
            case 120: return 44.9;
            case 135: return 60.48;
            case 100: return 38.0;
            case 80:  return 30.08;
            case 200: return 108.8;
            case 176: return 56.4;
            case 180: return 91.3;
            case 110: return 43.2;
            case 50:  return 18.3;
            case 56:  return 18.3;
            case 72:  return 26.6;
            case 75:  return 26.6;
            case 79:  return 26.6;
            default:  return 0;
        }
    }

    // E6 (71.7 kWh) intentionally omitted: legacy taxi model, virtually
    // indistinguishable from Seal U (71.8 kWh).
    private static final double[] KNOWN_PACK_KWH = {
        18.3, 26.6, 30.08, 38.0, 43.2, 44.9, 56.4,
        60.48, 61.44, 71.8, 82.56, 85.44, 87.0, 91.3, 108.8
    };

    private static double matchNearestCapacity(double estimated) {
        return matchNearestCapacity(estimated, Double.NaN, Double.NaN);
    }

    private static double matchNearestCapacity(double estimated,
                                               double packVoltage,
                                               double socPercent) {
        double bestMatch = 0;
        double bestDiff = Double.MAX_VALUE;
        for (double k : KNOWN_PACK_KWH) {
            double diff = Math.abs(estimated - k);
            double tolerance = (k < 40 ? 0.20 : 0.10) * k;
            if (diff > tolerance) continue;
            if (!packVoltagePlausibleForPack(k, packVoltage, socPercent)) continue;
            if (diff < bestDiff) {
                bestDiff = diff;
                bestMatch = k;
            }
        }
        return bestMatch;
    }

    private static boolean packVoltagePlausibleForPack(double kwh,
                                                       double packVoltage,
                                                       double socPercent) {
        if (Double.isNaN(packVoltage) || packVoltage < 200) return true;
        if (Double.isNaN(socPercent) || socPercent < 5 || socPercent > 100) return true;
        int cellCount = cellCountForCapacity(kwh);
        if (cellCount <= 0) return true;
        double impliedCellV = packVoltage / cellCount;
        double minV = lfpMinCellVoltageAt(socPercent);
        double maxV = lfpMaxCellVoltageAt(socPercent);
        return impliedCellV >= minV && impliedCellV <= maxV;
    }

    private static double lfpMinCellVoltageAt(double socPercent) {
        if (socPercent >= 95) return 3.28;
        if (socPercent >= 80) return 3.18;
        if (socPercent >= 50) return 3.10;
        if (socPercent >= 30) return 3.00;
        if (socPercent >= 15) return 2.85;
        if (socPercent >= 5)  return 2.70;
        return 2.50;
    }

    private static double lfpMaxCellVoltageAt(double socPercent) {
        if (socPercent >= 95) return 3.55;
        if (socPercent >= 80) return 3.40;
        if (socPercent >= 50) return 3.30;
        if (socPercent >= 30) return 3.22;
        if (socPercent >= 15) return 3.18;
        if (socPercent >= 5)  return 3.10;
        return 3.00;
    }

    public static int cellCountForCapacity(double nominalKwh) {
        if (matches(nominalKwh, 60.48) || matches(nominalKwh, 60.4))  return 126;
        if (matches(nominalKwh, 61.44))                                return 128;
        if (matches(nominalKwh, 82.56) || matches(nominalKwh, 82.5))   return 172;
        if (matches(nominalKwh, 71.8))                                 return 138;
        if (matches(nominalKwh, 87.0))                                 return 166;
        if (matches(nominalKwh, 85.44))                                return 156;
        if (matches(nominalKwh, 91.3))                                 return 170;
        if (matches(nominalKwh, 108.8))                                return 192;
        if (matches(nominalKwh, 44.9))                                 return 104;
        if (matches(nominalKwh, 30.08))                                return 96;
        if (matches(nominalKwh, 38.0))                                 return 100;
        if (matches(nominalKwh, 43.2))                                 return 96;
        if (matches(nominalKwh, 56.4))                                 return 116;
        if (matches(nominalKwh, 18.3))                                 return 80;
        if (matches(nominalKwh, 26.6))                                 return 84;
        return 0;
    }

    private static boolean matches(double a, double b) {
        return Math.abs(a - b) < 0.5;
    }

    private static double mapCellCountToCapacity(int cellCount) {
        if (cellCount >= 82 && cellCount <= 86)   return 26.6;
        if (cellCount >= 94 && cellCount <= 98)   return 30.08;
        if (cellCount >= 102 && cellCount <= 106) return 44.9;
        if (cellCount >= 114 && cellCount <= 118) return 56.4;
        if (cellCount >= 136 && cellCount <= 140) return 71.8;
        if (cellCount >= 154 && cellCount <= 158) return 85.44;
        if (cellCount >= 164 && cellCount <= 168) return 87.0;
        if (cellCount >= 190 && cellCount <= 194) return 108.8;
        return 0;
    }

    // Package-visible (not private) purely so SohModelCapacityTest can pin the branch
    // ORDERING: the DM-i branches must precede their BEV namesakes, and adding one must not
    // change any BEV outcome. That is an ordering invariant no caller can express.
    static double mapCarTypeToCapacity(String carType) {
        String ct = carType.toUpperCase();
        // PHEV / DM-i marketing strings FIRST — otherwise "Seal U DM-i" falls into
        // the BEV "SEAL U" branch (71.8) and "Destroyer 05" matches nothing. These
        // return the GROSS nameplate (18.3), which is exactly what the SOH formula
        // needs: PHEV remainKwh is corrected to the gross frame at the HAL read
        // boundary (BydDataCollector.PHEV_ENERGY_HALF_SCALE_CORRECTION), so a
        // healthy pack reads ~100% against gross with no frame-mismatch nudge.
        // Keeps PHEV auto-detect in the right pack class instead of a wildly-wrong
        // BEV capacity.
        boolean isDmiString = ct.contains("DM-I") || ct.contains("DMI") || ct.contains("DM-P");
        if (ct.contains("DESTROYER")) return 18.3;
        if (isDmiString && (ct.contains("SEAL U") || ct.contains("SEALU") || ct.contains("SEAL-U"))) return 18.3;
        // TANG DM-i — MUST precede the bare "TANG" BEV branch below. Without this a Tang
        // DM-i was auto-detected as the 108.8 kWh BEV pack: a ~5x nominal overstatement
        // that poisons every capacity-derived number (SOH, remaining kWh, trip energy,
        // range). 21.5 kWh gross is the DM-i pack; the SOH formula wants gross because
        // PHEV remainKwh is corrected to the gross frame at the HAL boundary.
        if (isDmiString && ct.contains("TANG")) return 21.5;
        // Song / Qin / Frigate DM-i share the ~18.3 kWh DM-i pack class. Listed before the
        // BEV name branches for the same reason as above; a DM-i string must never fall
        // through to a BEV capacity.
        if (isDmiString && (ct.contains("SONG") || ct.contains("QIN") || ct.contains("FRIGATE"))) return 18.3;
        // Han DM-i / DM-p — MUST precede the bare "HAN" branch for the same reason the Tang
        // branch exists: without it a Han PHEV is auto-detected as the 85.44 kWh BEV pack, a
        // ~4.7x overstatement that poisons SOH, remaining kWh, trip energy and range.
        //
        // Returns 0 = "not detected" rather than a number, deliberately. Unlike Tang/Song/Qin
        // (one pack class each) the Han PHEV pack varies far too widely across trims and model
        // years to name a single value, and a confidently-wrong constant here would be
        // indistinguishable from a real detection to every downstream consumer. 0 falls the
        // caller through to the measurement-based tiers (BMS fuzzy capacity, then pack
        // voltage), and failing those it logs "SOH estimation disabled until capacity is
        // identified" — an honest unknown. A user who knows their trim can still pin it exactly
        // via the user-nominal override.
        if (isDmiString && ct.contains("HAN")) return 0;
        if (ct.contains("SEALION 6") || ct.contains("SEALION6") || ct.contains("SEA LION 6")) return 26.6;
        if (ct.contains("SEALION") || ct.contains("SEA LION")) return 91.3;
        if (ct.contains("SEAL U") || ct.contains("SEALU") || ct.contains("SEAL-U") || ct.contains("S7")) return 71.8;
        if (ct.contains("SEAL")) return 82.56;
        // Han BEV. The bare `|| ct.contains("DM-P")` that used to be on this line is gone: it
        // assigned an 85 kWh BEV pack to ANY DM-p (i.e. PHEV) model that reached here, which is
        // the precise bug the branches above fix — a PHEV inheriting a BEV capacity. Named DM-p
        // models are handled above; an unnamed one now falls through to 0 (not detected).
        if (ct.contains("HAN")) return 85.44;
        if (ct.contains("TANG")) return 108.8;
        if (ct.contains("ATTO 3") || ct.contains("ATTO3") || ct.contains("YUAN PLUS")) return 60.48;
        if (ct.contains("ATTO 2") || ct.contains("ATTO2")) return 44.9;
        if (ct.contains("ATTO 1") || ct.contains("ATTO1")) return 30.08;
        if (ct.contains("YUAN PRO")) return 38.0;
        if (ct.contains("YUAN")) return 60.48;
        if (ct.contains("DOLPHIN MINI") || ct.contains("SEAGULL")) return 38.0;
        if (ct.contains("DOLPHIN")) return 44.9;
        if (ct.contains("SONG")) return 71.8;
        if (ct.contains("QIN")) return 56.4;
        return 0;
    }
}
