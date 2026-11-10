package com.overdrive.app.power;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * MCU power-rail control via BYD HAL device-event writes.
 *
 * <p>Two surfaces:
 * <ol>
 *   <li><b>MCU sleep/wake</b> — {@code BYDAutoPowerDevice.set(new int[]{-1442840502}, BYDAutoEventValue.intValue=0/1)}.
 *       value=0 → request MCU sleep; value=1 → request MCU wake. The MCU
 *       gates the DC-DC converter that runs the head-unit + camera ISP rails;
 *       letting it sleep when the 12V is healthy is what makes ACC=OFF
 *       surveillance not drain the battery in 4 hours.</li>
 *   <li><b>Sentry-mode MCU</b> — {@code BYDAutoSpecialDevice.set(new int[]{1901}, …)} +
 *       {@code set(new int[]{1902}, …)}. Sleep: 1901→0, 1902→2.
 *       Wake: 1901→1, 1902→1.</li>
 * </ol>
 *
 * <p>All device handles are resolved process-locally from the caller-supplied
 * {@code appContext}. Reaching into {@code BydDataCollector.getInstance()}
 * doesn't work cross-process — that singleton is per-process and is only
 * initialised in cam_daemon. Other daemons (e.g. acc_sentry) get a fresh
 * empty collector.
 *
 * <p>Class FQNs:
 * <ul>
 *   <li>{@code android.hardware.bydauto.power.BYDAutoPowerDevice} — stable
 *       across the fleet.</li>
 *   <li>BYDAutoSpecialDevice lives in <b>one of two packages</b> depending on
 *       the trim, so it is probed in order — see
 *       {@link #SPECIAL_CLASS_CANDIDATES}. The {@code .bydauto.special.} form
 *       was verified on DiLink 3.0; the reference OEM dashcam app uses the bare
 *       {@code .special.} form, verified in its raw dex string table.
 *       Hardcoding only the former made every
 *       sentry-mode write a silent no-op on trims that ship the latter.</li>
 * </ul>
 *
 * <p>All calls are best-effort. When the underlying HAL/class is missing on a
 * trim, the call returns false and logs at info level. Caller must not rely
 * on success — guard surveillance behaviour with separate state.
 */
public final class McuPowerHal {

    private static final String TAG = "McuPowerHal";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Event-type constant for MCU sleep/wake writes. */
    public static final int EVENT_MCU_SLEEP_WAKE = -1442840502;

    /** Sentry-mode special-device feature keys. */
    public static final int SENTRY_KEY_1 = 1901;
    public static final int SENTRY_KEY_2 = 1902;

    /** Power-device class FQN. */
    private static final String POWER_CLASS = "android.hardware.bydauto.power.BYDAutoPowerDevice";

    /**
     * Candidate FQNs for BYDAutoSpecialDevice, probed in declaration order.
     *
     * <p>The {@code .bydauto.special.} form was verified on DiLink 3.0. The reference
     * reference app ships the BARE {@code .special.} form — confirmed in its raw dex
     * string table ({@code /Landroid/hardware/special/BYDAutoSpecialDevice;}), and
     * corroborated by the BYD SDK javadoc in {@code doc/}, which lists 20
     * {@code android.hardware.bydauto.*} packages and no {@code special} one.
     *
     * <p>Order matters and is deliberately legacy-first: any trim that already
     * resolved the {@code .bydauto.special.} class keeps resolving the exact same
     * class, so its behaviour is bit-identical.
     *
     * <p><b>The bare fallback is DiLink 4 only</b>, enforced in
     * {@link #resolveSpecialDevice()}. It is tempting to treat the extra candidate as
     * "strictly additive because the old path already returned null" — that is WRONG
     * here. {@code BatteryVoltageMonitorV2} runs on <i>every</i> variant, not just
     * DiLink 4, and its {@link #requestSentryWake}/{@link #requestSentrySleep} calls
     * flow straight into this resolver. On a legacy trim that ships only the bare
     * class, those sentry-key writes are currently inert no-ops; letting them start
     * landing would newly drive vehicle sentry-mode flags (1901/1902 and the 0x2EA0
     * pair) on hardware we have never exercised them against. That is a behaviour
     * change to the 90% fleet, so the fallback stays gated.
     */
    private static final String[] SPECIAL_CLASS_CANDIDATES = {
        "android.hardware.bydauto.special.BYDAutoSpecialDevice",
        "android.hardware.special.BYDAutoSpecialDevice",
    };

    /** Index into {@link #SPECIAL_CLASS_CANDIDATES} of the first DiLink4-only entry. */
    private static final int SPECIAL_CLASS_LEGACY_CANDIDATE_COUNT = 1;

    /**
     * True when the user has selected DiLink 4 (byd_apa) camera mode.
     *
     * <p>Uses the active-mode fence shared by every process, so staged
     * configuration cannot expand the write set before restart succeeds.
     */
    private static boolean isDilink4CameraMode() {
        try {
            return com.overdrive.app.camera.dilink5.DiLink5Platform
                    .isDiLink4Selected();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isDilink5Mode() {
        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
    }

    /**
     * Process-local context handed in by whichever daemon boots us. Required
     * for both power and special device {@code getInstance} calls.
     */
    private static volatile android.content.Context appContext;

    private static volatile Object cachedPowerDevice;
    private static volatile Object cachedSpecialDevice;
    /**
     * Special-device handle for the opt-in parked keep-alive lease ONLY. Kept
     * separate from {@link #cachedSpecialDevice} on purpose: the lease may
     * resolve the bare-package FQN on firmware where the legacy paths deliberately
     * do not, and sharing one cache would let that handle leak into the
     * fleet-wide requestSentryWake/Sleep writers.
     */
    private static volatile Object cachedLeaseSpecialDevice;
    private static volatile boolean leaseSpecialDeviceUnavailable;

    private McuPowerHal() {}

    /**
     * Daemons set this to their own (PermissionBypass-wrapped) appContext.
     * Must be called before any {@code request*} call — without it every
     * request silently no-ops because the device classes need a Context.
     */
    public static void setAppContext(android.content.Context ctx) {
        appContext = ctx;
        // Drop cached handles — they were resolved against the previous
        // (possibly null) context and may be unusable.
        cachedPowerDevice = null;
        cachedSpecialDevice = null;
        cachedLeaseSpecialDevice = null;
        leaseSpecialDeviceUnavailable = false;
    }

    /**
     * Set the context only when none is present yet. Unlike {@link #setAppContext}
     * this never drops already-resolved device handles, so a periodic caller can
     * guarantee a context without re-resolving the HAL on every tick.
     */
    public static void ensureAppContext(android.content.Context ctx) {
        if (appContext == null && ctx != null) {
            setAppContext(ctx);
        }
    }

    // ── MCU sleep/wake on BYDAutoPowerDevice ──────────────────────────

    /** Request MCU sleep ({@code EVENT_MCU_SLEEP_WAKE} ← 0). */
    public static boolean requestMcuSleep() {
        if (isDilink5Mode()) return false;
        Object device = resolvePowerDevice();
        if (device == null) {
            logger.info("requestMcuSleep: power device unresolved — no-op");
            return false;
        }
        boolean ok = writeConfirmed(device, EVENT_MCU_SLEEP_WAKE, 0, "mcuSleep -1442840502<-0");
        logger.info("requestMcuSleep -> " + ok);
        return ok;
    }

    /** Request MCU wake ({@code EVENT_MCU_SLEEP_WAKE} ← 1). */
    public static boolean requestMcuWake() {
        if (isDilink5Mode()) return false;
        Object device = resolvePowerDevice();
        if (device == null) {
            logger.info("requestMcuWake: power device unresolved — no-op");
            return false;
        }
        boolean ok = writeConfirmed(device, EVENT_MCU_SLEEP_WAKE, 1, "mcuWake -1442840502<-1");
        logger.info("requestMcuWake -> " + ok);
        return ok;
    }

    // ── Sentry-mode keys on BYDAutoSpecialDevice ─────────────────────

    // The OEM SENTRY-MODE pair, per the DiCarServer feature catalog
    // (dev/byd-property-bus/dicarserver_feature_catalog.txt):
    //   782237711 = 0x2EA0000F SPECIAL_LOCAL_CTL_ENTER_SENTRY_MODE_SET
    //   782237728 = 0x2EA00020 SPECIAL_SENTRY_MODE_SET
    // So these are NOT "5V rail" / "modem-USB rail" holds, despite how they are labelled
    // elsewhere in this codebase — they are the vehicle's own sentry/surveillance-mode
    // enter+state flags on BYDAutoSpecialDevice. Holding sentry mode is what keeps the MCU
    // from cutting the rails the cameras need, which is why they LOOK like rail holds.
    //
    // The 1901/1902 writes below are untouched. These are written IN ADDITION because the two
    // pairs are not interchangeable — 1901/1902 are bare small integers with no BYD namespace
    // prefix, while these are properly-formed 0x2EA0 ids, so a trim whose HAL ignores one may
    // honour the other. Both use the same value convention here (sleep 0/2, wake 1/1), so the
    // two writes agree and can never contradict each other.
    //
    // GATED TO DILINK 4 — deliberately NOT fleet-wide. It is tempting to call the extra pair
    // "additive because it's just two more writes", but that is the same mistake the
    // SPECIAL_CLASS_CANDIDATES note above warns about: BatteryVoltageMonitorV2 drives
    // requestSentrySleep/Wake on EVERY variant, so on a legacy trim these ids would start
    // driving real vehicle sentry-mode flags that have never been exercised on that hardware.
    // A write that lands is a behaviour change even when the surrounding code is unchanged.
    // DiLink 4 is where the AVM rail problem lives and where the reference behaviour was
    // observed, so that is the only place this runs; the 90% fleet stays bit-identical.
    private static final int SENTRY_MODE_ENTER = 782237711;  // 0x2EA0000F
    private static final int SENTRY_MODE_STATE = 782237728;  // 0x2EA00020

    /** Request sentry-mode sleep — writes 1901←0, 1902←2 plus the 0x2EA0 sentry pair (0/2). */
    public static boolean requestSentrySleep() {
        if (isDilink5Mode()) return false;
        Object device = resolveSpecialDevice();
        if (device == null) {
            logger.info("requestSentrySleep: BYDAutoSpecialDevice unavailable — no-op");
            return false;
        }
        boolean a = writeConfirmed(device, SENTRY_KEY_1, 0, "sleep 1901<-0");
        boolean b = writeConfirmed(device, SENTRY_KEY_2, 2, "sleep 1902<-2");
        // 0x2EA0 pair: DiLink 4 only (see the constants' note — a landing write on a legacy
        // trim is a behaviour change to the 90% fleet).
        boolean c = false, d = false;
        if (isDilink4CameraMode()) {
            c = writeConfirmed(device, SENTRY_MODE_ENTER, 0, "sleep 782237711<-0");
            d = writeConfirmed(device, SENTRY_MODE_STATE, 2, "sleep 782237728<-2");
            logger.info("requestSentrySleep 1901<-0=" + a + " 1902<-2=" + b
                    + " 782237711<-0=" + c + " 782237728<-2=" + d);
            // Either pair landing is a real sleep request — don't fail the call just because
            // the trim ignores one id family.
            return (a && b) || (c && d);
        }
        logger.info("requestSentrySleep 1901<-0=" + a + " 1902<-2=" + b);
        return a && b;
    }

    /** Request sentry-mode wake — writes 1901←1, 1902←1 plus the 0x2EA0 sentry pair (1/1). */
    public static boolean requestSentryWake() {
        if (isDilink5Mode()) return false;
        Object device = resolveSpecialDevice();
        if (device == null) {
            logger.info("requestSentryWake: BYDAutoSpecialDevice unavailable — no-op");
            return false;
        }
        boolean a = writeConfirmed(device, SENTRY_KEY_1, 1, "wake 1901<-1");
        boolean b = writeConfirmed(device, SENTRY_KEY_2, 1, "wake 1902<-1");
        // 0x2EA0 pair: DiLink 4 only — see requestSentrySleep and the constants' note.
        boolean c = false, d = false;
        if (isDilink4CameraMode()) {
            c = writeConfirmed(device, SENTRY_MODE_ENTER, 1, "wake 782237711<-1");
            d = writeConfirmed(device, SENTRY_MODE_STATE, 1, "wake 782237728<-1");
            logger.info("requestSentryWake 1901<-1=" + a + " 1902<-1=" + b
                    + " 782237711<-1=" + c + " 782237728<-1=" + d);
            return (a && b) || (c && d);
        }
        logger.info("requestSentryWake 1901<-1=" + a + " 1902<-1=" + b);
        return a && b;
    }

    // ── DiLink 5 parked keep-alive lease ─────────────────────────────
    //
    // The ONLY DiLink 5-capable write path in this class. Every legacy entry
    // point above keeps its DiLink 5-mode fail-closed early return, and
    // AccSentryDaemon's setPowerConfig()/VERIFIED_DILINK5_RAIL_SIGNATURES gate
    // is untouched: those callers run on every variant and were never
    // exercised against DI5 hardware. This path is reachable only through a
    // Di5LeaseToken minted by Di5ParkedPowerHold for a specific parked
    // generation, so the user's explicit opt-in (master toggle) and the current
    // sentry generation are both required before a single write lands.
    //
    // DELIBERATELY NOT keyed to the camera-mode selection. DiLink 5 head units
    // exist in two flavours — with the QCarCam stack (the user picks the
    // "dilink5" camera mode) and without it (the user stays on another camera
    // mode) — and the MCU/sentry flags are a vehicle-power concern, not a
    // camera one. The user's Experimental master toggle is the platform
    // declaration; the token supplies the generation fence. Nothing here runs
    // for an install that never turned the toggle on.
    //
    // Value convention is identical to requestSentryWake/requestSentrySleep
    // (enter 1/0, state 1/2). EVENT_MCU_SLEEP_WAKE (-1442840502) is touched
    // ONLY by writeMcuPowerHoldForLease (1 = hold/wake, 0 = release), and the
    // controller issues the release-side 0 only when it asserted the 1 itself —
    // never as a bare "request MCU sleep".

    /** Capability token for the lease path. Minted only by Di5ParkedPowerHold glue. */
    public static final class Di5LeaseToken {
        private final long generation;
        private final java.util.function.BooleanSupplier current;

        private Di5LeaseToken(long generation, java.util.function.BooleanSupplier current) {
            this.generation = generation;
            this.current = current;
        }

        public long generation() {
            return generation;
        }

        /** True while the parked generation this token was minted for is current. */
        public boolean isCurrent() {
            try {
                return current != null && current.getAsBoolean();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public static Di5LeaseToken newDi5LeaseToken(
            long generation, java.util.function.BooleanSupplier current) {
        return new Di5LeaseToken(generation, current);
    }

    /**
     * Assert-side authorization: a token whose parked generation is still
     * current. Release-side calls only need a token — releasing is the safe
     * direction and must work from start-up hygiene and shutdown paths where no
     * parked generation is current. There is no camera-mode check here (see the
     * section note): the opt-in toggle evaluated by Di5ParkedPowerHold before it
     * mints a token is the only platform declaration this path relies on.
     */
    private static boolean leaseAuthorized(Di5LeaseToken token, boolean assertSide) {
        if (token == null) return false;
        return !assertSide || token.isCurrent();
    }

    /**
     * Special device for the lease. Probes BOTH known FQNs (the bare
     * {@code android.hardware.special} package exists in the wild — see
     * SPECIAL_CLASS_CANDIDATES) because a DiLink 5 unit that is not in the
     * "dilink5" camera mode would otherwise be limited to the legacy single-FQN
     * probe and the opt-in lease would silently no-op. Uses its own cache so the
     * fleet-wide writers behind {@link #resolveSpecialDevice()} keep their
     * exact resolution behaviour.
     */
    private static Object resolveLeaseSpecialDevice() {
        if (cachedLeaseSpecialDevice != null) return cachedLeaseSpecialDevice;
        if (leaseSpecialDeviceUnavailable) return null;
        if (appContext == null) {
            logger.debug("resolveLeaseSpecialDevice: no appContext — call setAppContext first");
            return null;
        }
        for (String fqn : SPECIAL_CLASS_CANDIDATES) {
            try {
                Class<?> cls = Class.forName(fqn);
                Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
                Object device = getInstance.invoke(null, appContext);
                if (device == null) {
                    logger.debug("resolveLeaseSpecialDevice: " + fqn + " getInstance returned null");
                    continue;
                }
                cachedLeaseSpecialDevice = device;
                logger.info("resolveLeaseSpecialDevice: " + fqn
                        + " -> " + device.getClass().getName());
                return device;
            } catch (ClassNotFoundException e) {
                Object injected = BydDeviceHelper.getDevice(fqn, appContext);
                if (injected != null) {
                    cachedLeaseSpecialDevice = injected;
                    logger.info("resolveLeaseSpecialDevice: " + fqn
                            + " -> " + injected.getClass().getName());
                    return injected;
                }
                logger.debug("resolveLeaseSpecialDevice: " + fqn + " not present");
            } catch (Throwable t) {
                logger.debug("resolveLeaseSpecialDevice: " + fqn + " failed: " + t.getMessage());
            }
        }
        leaseSpecialDeviceUnavailable = true;
        logger.info("resolveLeaseSpecialDevice: BYDAutoSpecialDevice unavailable ("
                + SPECIAL_CLASS_CANDIDATES.length
                + " candidates probed) — lease sentry-flag writes will no-op");
        return null;
    }

    /**
     * Sentry-mode flag pair for the DI5 lease: assert = {@code 782237711←1,
     * 782237728←1}; release = {@code 782237711←0, 782237728←2}.
     *
     * @return {@code int[]{rcEnter, rcState}} — the HAL's RAW return codes (0 =
     *         confirmed). {@code Integer.MIN_VALUE} when the lease is not
     *         authorized or the special device is unavailable; nothing is written
     *         in either case.
     */
    public static int[] writeSentryFlagsForLease(Di5LeaseToken token, boolean assertFlags) {
        int[] unavailable = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        if (!leaseAuthorized(token, assertFlags)) {
            logger.info("writeSentryFlagsForLease: lease not authorized — no-op");
            return unavailable;
        }
        Object device = resolveLeaseSpecialDevice();
        if (device == null) {
            logger.info("writeSentryFlagsForLease: BYDAutoSpecialDevice unavailable — no-op");
            return unavailable;
        }
        int enter = assertFlags ? 1 : 0;
        int state = assertFlags ? 1 : 2;
        int rcEnter = BydDeviceHelper.sendSetCommandRaw(device, SENTRY_MODE_ENTER, enter);
        int rcState = BydDeviceHelper.sendSetCommandRaw(device, SENTRY_MODE_STATE, state);
        logger.info("writeSentryFlagsForLease gen=" + token.generation()
                + " 782237711<-" + enter + " rc=" + rcEnter
                + " 782237728<-" + state + " rc=" + rcState);
        return new int[]{rcEnter, rcState};
    }

    /**
     * MCU power hold for the lease: {@code EVENT_MCU_SLEEP_WAKE (-1442840502) ←
     * 1} on hold, {@code ← 0} on release. OEM-app parity — its shipping default
     * (V1) writes exactly this, unconditionally, on ACC OFF, and never gates it
     * on {@code getMcuStatus()}; the status-gated variant is the documented
     * cause of the DiLink 4 rail collapse. The caller (Di5ParkedPowerHold) only
     * issues the release-side 0 when it asserted the 1 itself, so an install
     * with the lever off never writes this event at all.
     *
     * @return the HAL's RAW return code (0 = confirmed); {@code Integer.MIN_VALUE}
     *         when the lease is not authorized or the power device is unavailable —
     *         nothing is written in either case.
     */
    public static int writeMcuPowerHoldForLease(Di5LeaseToken token, boolean hold) {
        if (!leaseAuthorized(token, hold)) {
            logger.info("writeMcuPowerHoldForLease: lease not authorized — no-op");
            return Integer.MIN_VALUE;
        }
        Object device = resolvePowerDevice();
        if (device == null) {
            logger.info("writeMcuPowerHoldForLease: BYDAutoPowerDevice unavailable — no-op");
            return Integer.MIN_VALUE;
        }
        int value = hold ? 1 : 0;
        int rc = BydDeviceHelper.sendSetCommandRaw(device, EVENT_MCU_SLEEP_WAKE, value);
        logger.info("writeMcuPowerHoldForLease gen=" + token.generation()
                + " -1442840502<-" + value + " rc=" + rc);
        return rc;
    }

    /** {@code BYDAutoPowerDevice.getMcuStatus()} for the lease; -1 when unavailable. */
    public static int readMcuStatusForLease(Di5LeaseToken token) {
        if (!leaseAuthorized(token, true)) return -1;
        Object device = resolvePowerDevice();
        if (device == null) return -1;
        Object v = BydDeviceHelper.callGetter(device, "getMcuStatus");
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    /**
     * {@code BYDAutoPowerDevice.getMcuStatus()} as a plain read-only probe — no lease, no token.
     * Reading status writes nothing to the HAL, so it is safe on every platform (the remote-key
     * readiness gate uses it to decide whether a door/power frame needs an MCU wake first).
     * {@link #ensureAppContext} must have been called. Returns -1 when the device or status is
     * unavailable, matching the lease variant.
     */
    public static int readMcuStatus() {
        Object device = resolvePowerDevice();
        if (device == null) return -1;
        Object v = BydDeviceHelper.callGetter(device, "getMcuStatus");
        return v instanceof Number ? ((Number) v).intValue() : -1;
    }

    /** {@code BYDAutoPowerDevice.wakeUpMcu()} for the lease; true when the HAL returned 0. */
    public static boolean wakeUpMcuForLease(Di5LeaseToken token) {
        if (!leaseAuthorized(token, true)) return false;
        Object device = resolvePowerDevice();
        if (device == null) return false;
        Object v = BydDeviceHelper.callGetter(device, "wakeUpMcu");
        boolean ok = v instanceof Number && ((Number) v).intValue() == 0;
        logger.info("wakeUpMcuForLease gen=" + token.generation() + " rc=" + v);
        return ok;
    }

    // ── Internals ────────────────────────────────────────────────────

    /**
     * Write one feature id and report whether the HAL actually CONFIRMED it.
     *
     * <p>Why not {@code BydDeviceHelper.sendSetCommand}: that helper maps
     * {@code code >= 0} to true, but the BYD HAL contract is {@code code == 0} for
     * success — a positive non-zero code is a real failure, and
     * {@code sendSetCommandRaw} additionally returns 0 for "non-null result, assume
     * success" when the SDK hands back something it can't interpret. Under the
     * loose rule these all logged as {@code true}, so a field log showing
     * "requestSentryWake 1901<-1=true" told us nothing about whether the rail was
     * actually held. Every log line here is now the RAW code, which is what we
     * need to tell "write landed" from "write silently rejected".
     *
     * <p>Scoped deliberately to this class. The shared
     * {@code BydDeviceHelper.sendSetCommand} is used by many other subsystems
     * across all variants; tightening it globally would change behaviour far
     * outside the DiLink 4 ACC-off path, so it is left exactly as-is.
     *
     * @return true only when the HAL returned the success code 0.
     */
    private static boolean writeConfirmed(Object device, int featureId, int value, String label) {
        int code = BydDeviceHelper.sendSetCommandRaw(device, featureId, value);
        boolean confirmed = (code == 0);
        if (!confirmed) {
            logger.info(label + " NOT confirmed (rc=" + code + ")");
        }
        return confirmed;
    }

    private static Object resolvePowerDevice() {
        if (cachedPowerDevice != null) return cachedPowerDevice;
        if (appContext == null) {
            logger.debug("resolvePowerDevice: no appContext — call setAppContext first");
            return null;
        }
        try {
            Class<?> cls = Class.forName(POWER_CLASS);
            Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
            cachedPowerDevice = getInstance.invoke(null, appContext);
            if (cachedPowerDevice != null) {
                logger.info("resolvePowerDevice: " + cachedPowerDevice.getClass().getName());
            }
        } catch (Throwable t) {
            logger.debug("BYDAutoPowerDevice.getInstance failed: " + t.getMessage());
        }
        return cachedPowerDevice;
    }

    private static Object resolveSpecialDevice() {
        if (cachedSpecialDevice != null) return cachedSpecialDevice;
        if (appContext == null) {
            logger.debug("resolveSpecialDevice: no appContext — call setAppContext first");
            return null;
        }
        // Legacy trims probe ONLY the historically-resolved FQN, so their write set
        // is byte-identical to before. The bare-package fallback is DiLink 4 only —
        // see SPECIAL_CLASS_CANDIDATES for why this must not be "additive for all".
        int limit = isDilink4CameraMode()
                ? SPECIAL_CLASS_CANDIDATES.length
                : SPECIAL_CLASS_LEGACY_CANDIDATE_COUNT;
        for (int i = 0; i < limit; i++) {
            String fqn = SPECIAL_CLASS_CANDIDATES[i];
            try {
                Class<?> cls = Class.forName(fqn);
                Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
                Object device = getInstance.invoke(null, appContext);
                if (device == null) {
                    logger.debug("resolveSpecialDevice: " + fqn + " getInstance returned null");
                    continue;
                }
                cachedSpecialDevice = device;
                logger.info("resolveSpecialDevice: " + fqn
                        + " -> " + device.getClass().getName());
                return cachedSpecialDevice;
            } catch (ClassNotFoundException e) {
                logger.debug("resolveSpecialDevice: " + fqn + " not present");
            } catch (Throwable t) {
                logger.debug("resolveSpecialDevice: " + fqn + " failed: " + t.getMessage());
            }
        }
        // Warn (not debug): with no special device, sentry keep-alive is inert.
        logger.warn("resolveSpecialDevice: BYDAutoSpecialDevice unavailable ("
                + limit + " candidate(s) probed) — sentry-mode writes will no-op");
        return cachedSpecialDevice;
    }
}
