package com.overdrive.app.byd

import com.overdrive.app.byd.cloud.BydCloudConfig
import com.overdrive.app.daemon.CameraDaemon
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.monitor.SocHistoryDatabase
import com.overdrive.app.monitor.VehicleDataMonitor
import org.json.JSONObject
import java.io.File

/**
 * Reads BYD vehicle telemetry from `dumpsys car_service`'s property dump.
 *
 * ## Why this class exists
 * On some DiLink 5.0 platforms (field-verified on a BYD Sealion 7) the normal
 * vendor-HAL read paths (`BYDAutoGearboxDevice`, `BYDAutoDoorLockDevice`, the
 * charging/energy devices, [CarPropertyBridge]'s direct
 * `ICarPropertyService` binder) throw `SecurityException` or return garbage
 * for gear, door-lock, 12V battery voltage, and EV charging state/power —
 * every one of those signals is simply unavailable through the paths the
 * rest of this app already trusts. `dumpsys car_service`'s own property dump
 * turns out to expose the SAME underlying property bus and is reliable on
 * that hardware, so this class shells out to it as a parallel, best-effort
 * fallback channel.
 *
 * ## Platform gate
 * Every method that actually shells out ([dumpsysText], [doorsArray],
 * [chargingSnapshot]) checks [DiLink5Platform.isActive] FIRST and returns
 * the "unavailable" sentinel immediately if it's not active — so on any
 * vehicle/platform this class hasn't been verified on, none of this code
 * runs at all and there is zero behavior change from the vendor-HAL-only
 * app. [gearValue], [batteryVoltage12v], and [speedValue] all funnel through
 * [dumpsysText], so they are automatically covered by the same gate.
 *
 * ## Reliability notes (field-verified on-device)
 * - The live `dumpsys car_service` output is ~16k lines. Reading the
 *   PROCESS's stdout pipe directly while it's still running was found to be
 *   unreliable — some property lookups silently failed with no exception.
 *   Waiting for the process to exit ([Process.waitFor]) and then reading the
 *   fully-written temp file is reliable.
 * - The temp file name includes both the calling thread id and
 *   [System.nanoTime] so concurrent callers (e.g. overlapping HTTP requests)
 *   never race on the same path — a shared fixed filename was observed to
 *   have one call's file truncated/deleted mid-read by another concurrent
 *   call.
 */
object CarSvcTelemetry {

    private val logger = DaemonLogger.getInstance("CarSvcTelemetry")

    // ── car_service property ids (confirmed live on a Sealion 7 / DiLink 5) ──
    private const val PROP_GEAR_R = 0x21403a0a                    // GEAR_R
    private const val PROP_BATTERY_VOLTAGE_SECOND = 0x2140461e    // 12V/secondary battery voltage
    private const val PROP_SPEED_VALUE = 0x21406006               // vehicle speed, km/h
    private const val PROP_SOC_VALUER = 0x21404622                // SOC_VALUER, main HV battery %
    private const val PROP_RIGHT_FRONT_LOCK = 0x2140506e          // door lock: right-front
    private const val PROP_LEFT_FRONT_LOCK = 0x21404627           // door lock: left-front
    private const val PROP_RIGHT_REAR_LOCK = 0x21405070           // door lock: right-rear
    private const val PROP_LEFT_REAR_LOCK = 0x2140506f            // door lock: left-rear
    private const val PROP_CHARGE_DISCHARGE_STATE = 0x2140461c    // CHARGE_AND_DISCHARGE_SYSTEM_STATE
    private const val PROP_CHARGING_POWER = 0x21603408            // CHARGING_POWERR (float, kW)
    private const val PROP_CHARGING_RESTTIME_HOUR = 0x21403440    // CHARGING_RESTTIME_HOURR
    private const val PROP_CHARGING_RESTTIME_MIN = 0x21403441     // CHARGING_RESTTIME_MINUTER
    private const val PROP_TYRE_LEFT_FRONT = 0x2160801d           // LEFTFRONTTIREPRESSURE, raw 0.1psi
    private const val PROP_TYRE_RIGHT_FRONT = 0x2160801e          // RIGHTFRONTTIREPRESSURE, raw 0.1psi
    private const val PROP_TYRE_LEFT_REAR = 0x2160801f            // LEFTREARTIREPRESSURE, raw 0.1psi
    private const val PROP_TYRE_RIGHT_REAR = 0x21608020           // RIGHTREARTIREPRESSURE, raw 0.1psi
    private const val PROP_WINDOW_LEFT_FRONT = 0x21405018         // WINDOW_OPEN_PERCENT_LEFT_FRONT_R
    private const val PROP_WINDOW_RIGHT_FRONT = 0x2140501a        // WINDOW_OPEN_PERCENT_RIGHT_FRONT_R
    private const val PROP_WINDOW_LEFT_REAR = 0x21405019          // WINDOW_OPEN_PERCENT_LEFT_REAR_R
    private const val PROP_WINDOW_RIGHT_REAR = 0x2140501b         // WINDOW_OPEN_PERCENT_RIGHT_REAR_R
    private const val PROP_WINDOW_SUNROOF = 0x2140501d            // WINDOW_OPEN_PERCENT_SUN_R (glass)
    private const val PROP_AC_WORK_MODE = 0x214010a4              // A_C_WORK_MODE_R, 1=on/2=off
    private const val PROP_AC_FAN_LEVEL = 0x21401027              // AC_CONTROLLER_WIND_LEVEL, 0=no airflow
    // AC temperature setpoints, degrees C. Both confirmed live reading 17 at
    // the same moment on this vehicle -- a same-value reading on both is
    // consistent with a non-dual-zone/synced setting, not independent proof
    // each property maps to the side its name implies. Lower-confidence
    // mapping than the rest of this file's properties.
    private const val PROP_AC_DRIVER_TEMP_SET = 0x21401023        // AC_CONTROLLER_DRIVER_TEMP_SET
    private const val PROP_AC_TEMP_DEPUTY = 0x2140104b            // AC_TEMP_DEPUTY ("deputy" = passenger)

    /** Raw gear value meaning "Park" — matches RecordingModeManager.GEAR_P. */
    private const val GEAR_PARK = 1

    /** state==2 means power is genuinely flowing (charging, incl. regen while driving). */
    private const val CHARGE_STATE_ACTIVE = 2

    private const val NOT_CHARGING_DEBOUNCE_MS = 60_000L

    /**
     * Minimum spacing between [recordChargingSample] DB inserts. This class's
     * [applyChargingOverrides] entry point can be invoked once per HTTP
     * request (the /status and /api/charging/overview endpoints are polled
     * frequently by the web UI), but a power SAMPLE is meant to be one point
     * on the session's ramp curve, not one row per HTTP request. 12s matches
     * this app's own established "fine sample" cadence (see
     * SocHistoryDatabase's durable-sample comments).
     */
    private const val SAMPLE_MIN_INTERVAL_MS = 12_000L

    @Volatile private var notChargingSinceMs: Long = 0L
    @Volatile private var lastSampleAtMs: Long = 0L

    // ==================== Core primitives ====================

    /**
     * Runs `dumpsys car_service`, waits for it to exit, then reads the
     * completed output from a per-call-unique temp file. Returns null on any
     * failure, or immediately (without shelling out) when [DiLink5Platform]
     * is not active.
     */
    fun dumpsysText(): String? {
        if (!DiLink5Platform.isActive()) return null
        val path = "/data/local/tmp/.carsvc_dump_${Thread.currentThread().id}_${System.nanoTime()}.txt"
        val tmpFile = File(path)
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "dumpsys car_service > $path 2>&1")
            )
            process.waitFor()
            if (!tmpFile.isFile) return null
            tmpFile.readText()
        } catch (t: Throwable) {
            logger.debug("dumpsysText() failed: " + t.message)
            null
        } finally {
            try {
                if (tmpFile.exists()) tmpFile.delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** The exact substring that precedes a property's live value in the dumpsys output. */
    fun buildSearchKey(propId: Int): String {
        return "lastEvent:Property:0x${propId.toString(16)},"
    }

    /** Extracts the int32/float value out of one `lastEvent:Property:...` line, or null. */
    fun parseValueLine(line: String): Number? {
        val intMarker = "int32Values: ["
        val intIdx = line.indexOf(intMarker)
        if (intIdx >= 0) {
            val start = intIdx + intMarker.length
            val end = line.indexOf(']', start)
            if (end > start) {
                val content = line.substring(start, end).trim()
                if (content.isNotEmpty()) {
                    content.toIntOrNull()?.let { return it }
                }
            }
        }
        val floatMarker = "floatValues: ["
        val floatIdx = line.indexOf(floatMarker)
        if (floatIdx >= 0) {
            val start = floatIdx + floatMarker.length
            val end = line.indexOf(']', start)
            if (end > start) {
                val content = line.substring(start, end).trim()
                if (content.isNotEmpty()) {
                    content.toFloatOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    /** Finds `propId`'s line in `text` and parses its value, or null if either step fails. */
    fun findInText(text: String?, propId: Int): Number? {
        if (text == null) return null
        val key = buildSearchKey(propId)
        for (line in text.lineSequence()) {
            if (line.contains(key)) {
                return parseValueLine(line)
            }
        }
        return null
    }

    fun getInt(propId: Int): Int {
        return findInText(dumpsysText(), propId)?.toInt() ?: -1
    }

    fun getFloat(propId: Int): Float {
        return findInText(dumpsysText(), propId)?.toFloat() ?: -1.0f
    }

    // ==================== Individual signals ====================

    /**
     * Raw gear value (1=P, 2=R, 3=N, 4=D, 5=M, 6=S — matches
     * RecordingModeManager.GEAR_* exactly), or -1 if unavailable/out of range.
     */
    fun gearValue(): Int {
        val raw = getInt(PROP_GEAR_R)
        return if (raw in 1..6) raw else -1
    }

    /** 12V/secondary battery voltage, or -1.0 if unavailable. Float (not Int) since a future
     *  firmware could report a fractional volt even though today's reading is a whole number. */
    fun batteryVoltage12v(): Float = getFloat(PROP_BATTERY_VOLTAGE_SECOND)

    /** Vehicle speed in km/h, or -1 if unavailable. */
    fun speedValue(): Int = getInt(PROP_SPEED_VALUE)

    /**
     * Main HV battery state-of-charge percent (SOC_VALUER), or -1 if
     * unavailable or out of the sane 0-100 range. Only exists as a
     * fallback for when the stock BatterySocMonitor path (vendor
     * BYDAutoStatisticDevice reflection, in VehicleDataMonitor) is
     * unavailable — e.g. that vendor binder service is slow/flaky to bind.
     * [getInt] already gates on [DiLink5Platform] via [dumpsysText], so this
     * returns -1 on non-DiLink5 platforms without ever shelling out.
     */
    fun socPercent(): Int {
        val raw = getInt(PROP_SOC_VALUER)
        return if (raw in 0..100) raw else -1
    }

    /**
     * Door lock state as `[rf, lf, rr, lr, trunk, hood, overall]`, raw
     * car_service encoding (2=locked, 1=unlocked, -1=unknown). `trunk`/`hood`
     * are always -1 (no car_service property identified for them).
     * `overall` is 2 only if all four doors are locked, 1 if any is
     * unlocked, else -1. Single dumpsysText() call + single line scan.
     */
    fun doorsArray(): IntArray {
        val unavailable = intArrayOf(-1, -1, -1, -1, -1, -1, -1)
        if (!DiLink5Platform.isActive()) return unavailable
        val text = dumpsysText() ?: return unavailable

        val rfKey = buildSearchKey(PROP_RIGHT_FRONT_LOCK)
        val lfKey = buildSearchKey(PROP_LEFT_FRONT_LOCK)
        val rrKey = buildSearchKey(PROP_RIGHT_REAR_LOCK)
        val lrKey = buildSearchKey(PROP_LEFT_REAR_LOCK)
        var rf = -1
        var lf = -1
        var rr = -1
        var lr = -1
        for (line in text.lineSequence()) {
            if (rf == -1 && line.contains(rfKey)) rf = parseValueLine(line)?.toInt() ?: -1
            if (lf == -1 && line.contains(lfKey)) lf = parseValueLine(line)?.toInt() ?: -1
            if (rr == -1 && line.contains(rrKey)) rr = parseValueLine(line)?.toInt() ?: -1
            if (lr == -1 && line.contains(lrKey)) lr = parseValueLine(line)?.toInt() ?: -1
            if (rf != -1 && lf != -1 && rr != -1 && lr != -1) break
        }
        val overall = when {
            rf == 2 && lf == 2 && rr == 2 && lr == 2 -> 2
            rf == 1 || lf == 1 || rr == 1 || lr == 1 -> 1
            else -> -1
        }
        return intArrayOf(rf, lf, rr, lr, -1, -1, overall)
    }

    /**
     * Single-pass read of the 4 TPMS tyre pressure properties, `[fl, fr, rl,
     * rr]`, each in raw car_service units confirmed empirically to be
     * 0.1 psi (e.g. raw 456 == 45.6 psi, matching the vendor SDK's own psi
     * reading for the same corner at the same moment), or -1 if not found.
     */
    fun tyrePressuresRaw(): IntArray {
        val unavailable = intArrayOf(-1, -1, -1, -1)
        if (!DiLink5Platform.isActive()) return unavailable
        val text = dumpsysText() ?: return unavailable

        val flKey = buildSearchKey(PROP_TYRE_LEFT_FRONT)
        val frKey = buildSearchKey(PROP_TYRE_RIGHT_FRONT)
        val rlKey = buildSearchKey(PROP_TYRE_LEFT_REAR)
        val rrKey = buildSearchKey(PROP_TYRE_RIGHT_REAR)
        var fl = -1
        var fr = -1
        var rl = -1
        var rr = -1
        for (line in text.lineSequence()) {
            if (fl == -1 && line.contains(flKey)) fl = parseValueLine(line)?.toInt() ?: -1
            if (fr == -1 && line.contains(frKey)) fr = parseValueLine(line)?.toInt() ?: -1
            if (rl == -1 && line.contains(rlKey)) rl = parseValueLine(line)?.toInt() ?: -1
            if (rr == -1 && line.contains(rrKey)) rr = parseValueLine(line)?.toInt() ?: -1
            if (fl != -1 && fr != -1 && rl != -1 && rr != -1) break
        }
        return intArrayOf(fl, fr, rl, rr)
    }

    /**
     * `{"fl":{"kPa":N,"psi":N,"available":true},"fr":{...},"rl":{...},
     * "rr":{...},"available":true}` from [tyrePressuresRaw], matching the
     * existing stock tyre-pressure JSON schema's field names so
     * VehicleControlApiHandler can drop this straight in ahead of the stock
     * `BydVehicleData` arrays. A corner with no reading is
     * `{"available":false}`. Returns null (no override) if none of the 4
     * corners have a reading, so the caller falls through to stock.
     */
    fun tyrePressuresJson(): JSONObject? {
        val raw = tyrePressuresRaw()
        val keys = arrayOf("fl", "fr", "rl", "rr")
        val obj = JSONObject()
        var any = false
        for (i in keys.indices) {
            val corner = JSONObject()
            val r = raw[i]
            if (r > 0) {
                val psi = r / 10.0
                corner.put("psi", psi)
                corner.put("kPa", Math.round(psi * 6.89476).toInt())
                corner.put("available", true)
                any = true
            } else {
                corner.put("available", false)
            }
            obj.put(keys[i], corner)
        }
        if (!any) return null
        obj.put("available", true)
        return obj
    }

    /**
     * Single-pass read of the 5 WINDOW_OPEN_PERCENT_*_R properties
     * (front/rear side windows + sunroof glass; sunshade has no confirmed
     * car_service mapping so it's left to the stock path). `[lf, rf, lr,
     * rr, sunroof]`, each 0-100 (percent open, 0 = closed, confirmed
     * empirically against the vendor SDK's all-closed 0 reading for the
     * same corners at the same moment), or -1 if not found.
     */
    fun windowPercentsRaw(): IntArray {
        val unavailable = intArrayOf(-1, -1, -1, -1, -1)
        if (!DiLink5Platform.isActive()) return unavailable
        val text = dumpsysText() ?: return unavailable

        val lfKey = buildSearchKey(PROP_WINDOW_LEFT_FRONT)
        val rfKey = buildSearchKey(PROP_WINDOW_RIGHT_FRONT)
        val lrKey = buildSearchKey(PROP_WINDOW_LEFT_REAR)
        val rrKey = buildSearchKey(PROP_WINDOW_RIGHT_REAR)
        val sunKey = buildSearchKey(PROP_WINDOW_SUNROOF)
        var lf = -1
        var rf = -1
        var lr = -1
        var rr = -1
        var sun = -1
        for (line in text.lineSequence()) {
            if (lf == -1 && line.contains(lfKey)) lf = parseValueLine(line)?.toInt() ?: -1
            if (rf == -1 && line.contains(rfKey)) rf = parseValueLine(line)?.toInt() ?: -1
            if (lr == -1 && line.contains(lrKey)) lr = parseValueLine(line)?.toInt() ?: -1
            if (rr == -1 && line.contains(rrKey)) rr = parseValueLine(line)?.toInt() ?: -1
            if (sun == -1 && line.contains(sunKey)) sun = parseValueLine(line)?.toInt() ?: -1
            if (lf != -1 && rf != -1 && lr != -1 && rr != -1 && sun != -1) break
        }
        return intArrayOf(lf, rf, lr, rr, sun)
    }

    /**
     * `{"lf":N,"rf":N,"lr":N,"rr":N,"sunroof":N}` from [windowPercentsRaw].
     * Sunshade is intentionally omitted (no confirmed car_service mapping).
     * A corner missing a reading is omitted rather than set to -1. Returns
     * null (no override) if none of the 5 properties have a reading, so the
     * caller falls through to stock.
     */
    fun windowsJson(): JSONObject? {
        val raw = windowPercentsRaw()
        val keys = arrayOf("lf", "rf", "lr", "rr", "sunroof")
        val obj = JSONObject()
        var any = false
        for (i in keys.indices) {
            val r = raw[i]
            if (r >= 0) {
                obj.put(keys[i], r)
                any = true
            }
        }
        return if (any) obj else null
    }

    /**
     * Reads A_C_WORK_MODE_R (confirmed 1=on/2=off by watching it flip live
     * in exact sync with the AC switch being toggled, twice in a row) AND
     * AC_CONTROLLER_WIND_LEVEL (fan speed, 0=no airflow). "Climate on" means
     * actual airflow, not just the AC switch state — the switch can read
     * "on" while the fan is idle (e.g. between auto-mode cycles), and the
     * user wants that shown as off. Returns 1=on (mode==1 AND fanLevel>0),
     * 0=off, -1=unavailable/not DiLink5.
     *
     * Fan-only (no AC compressor) is not distinguished here — see
     * [climateTempsRaw] for the driver/passenger temperature setpoints,
     * which were flagged as candidates during the same investigation and
     * are now confirmed live.
     */
    fun climateAcOnRaw(): Int {
        if (!DiLink5Platform.isActive()) return -1
        val text = dumpsysText() ?: return -1

        val modeKey = buildSearchKey(PROP_AC_WORK_MODE)
        val fanKey = buildSearchKey(PROP_AC_FAN_LEVEL)
        var mode = -1
        var fan = -1
        for (line in text.lineSequence()) {
            if (mode == -1 && line.contains(modeKey)) mode = parseValueLine(line)?.toInt() ?: -1
            if (fan == -1 && line.contains(fanKey)) fan = parseValueLine(line)?.toInt() ?: -1
            if (mode != -1 && fan != -1) break
        }
        if (mode == -1) return -1
        return if (mode == 1 && fan > 0) 1 else 0
    }

    /**
     * Reads AC_CONTROLLER_WIND_LEVEL directly and unconditionally — unlike
     * the stock `BydVehicleData` fan-level field, which the vendor code only
     * reports when its own AC-power-level gate is true, so the field goes
     * missing ENTIRELY (not 0) whenever AC is off. That made "fan genuinely
     * at 0" and "no reading available" indistinguishable downstream. This
     * property was already confirmed live to read independently of AC power
     * state (it read 0 while AC was actually on, via [climateAcOnRaw]'s use
     * of the same property as an internal AND-condition), and separately
     * confirmed to now populate a `fanLevel` reading even with AC off.
     * Returns 0-N (raw fan level), or -1 if unavailable/not DiLink5.
     */
    fun climateFanLevelRaw(): Int {
        val raw = getInt(PROP_AC_FAN_LEVEL)
        return if (raw >= 0) raw else -1
    }

    /**
     * Single-pass read of the driver/passenger AC temperature setpoints:
     * [PROP_AC_DRIVER_TEMP_SET] and [PROP_AC_TEMP_DEPUTY] ("deputy" =
     * passenger). Both confirmed live reading 17 (degrees C, plausible AC
     * setpoint) at the same moment on this vehicle -- see the caveat on
     * those constants above: a same-value reading on both is consistent
     * with a non-dual-zone/synced setting, not independent proof each
     * property maps to the side its name implies.
     *
     * Returns `[driverTempC, passengerTempC]`, each -1 if not
     * found/not DiLink5.
     */
    fun climateTempsRaw(): IntArray {
        val unavailable = intArrayOf(-1, -1)
        if (!DiLink5Platform.isActive()) return unavailable
        val text = dumpsysText() ?: return unavailable

        val driverKey = buildSearchKey(PROP_AC_DRIVER_TEMP_SET)
        val passengerKey = buildSearchKey(PROP_AC_TEMP_DEPUTY)
        var driver = -1
        var passenger = -1
        for (line in text.lineSequence()) {
            if (driver == -1 && line.contains(driverKey)) driver = parseValueLine(line)?.toInt() ?: -1
            if (passenger == -1 && line.contains(passengerKey)) passenger = parseValueLine(line)?.toInt() ?: -1
            if (driver != -1 && passenger != -1) break
        }
        return intArrayOf(driver, passenger)
    }

    /**
     * `[chargeSystemState, powerKw, restTimeHour, restTimeMin]`, each -1.0
     * if not found. `chargeSystemState`: raw 2 = actively charging/discharging
     * (power genuinely flowing, incl. regen while driving); 0/1/3/4 are
     * various not-charging/transitional states we only validated as "not 2",
     * not individually. `powerKw` is only meaningful while state==2.
     * `restTimeHour`/`restTimeMin` report sentinel 255 when not applicable.
     * Single dumpsysText() call + single line scan.
     */
    fun chargingSnapshot(): FloatArray {
        val unavailable = floatArrayOf(-1f, -1f, -1f, -1f)
        if (!DiLink5Platform.isActive()) return unavailable
        val text = dumpsysText() ?: return unavailable

        val stateKey = buildSearchKey(PROP_CHARGE_DISCHARGE_STATE)
        val powerKey = buildSearchKey(PROP_CHARGING_POWER)
        val hourKey = buildSearchKey(PROP_CHARGING_RESTTIME_HOUR)
        val minKey = buildSearchKey(PROP_CHARGING_RESTTIME_MIN)
        var state = -1f
        var power = -1f
        var hour = -1f
        var min = -1f
        for (line in text.lineSequence()) {
            if (state == -1f && line.contains(stateKey)) state = parseValueLine(line)?.toFloat() ?: -1f
            if (power == -1f && line.contains(powerKey)) power = parseValueLine(line)?.toFloat() ?: -1f
            if (hour == -1f && line.contains(hourKey)) hour = parseValueLine(line)?.toFloat() ?: -1f
            if (min == -1f && line.contains(minKey)) min = parseValueLine(line)?.toFloat() ?: -1f
            if (state != -1f && power != -1f && hour != -1f && min != -1f) break
        }
        return floatArrayOf(state, power, hour, min)
    }

    /**
     * Whether the user has BYD cloud telemetry merge enabled (the same
     * toggle the Settings cloud/telemetry switch controls). Defaults to
     * `true` (assume enabled) on any error — this gate is only used to
     * decide whether to activate a car_service READ fallback, and "cloud
     * enabled" means "don't activate the fallback", so failing toward `true`
     * fails toward no behavior change.
     */
    fun isCloudEnabled(): Boolean {
        return try {
            BydCloudConfig.fromUnifiedConfig().cloudDataMerge
        } catch (t: Throwable) {
            true
        }
    }

    // ==================== Charging overrides ====================

    /**
     * Overrides `charging`/`plugged`/`powerKw`/`timeToFullMin`/`sessionKwh`
     * on an already-built charging status/live JSONObject using car_service
     * telemetry, when available. Called from both HttpServer's `/status`
     * charging block and ChargingApiHandler's live-charging block (both
     * ultimately serialize the same `ChargingApiHandler.LivePublication`, so
     * this is wired into its `toStatusJson()`/`toLiveJson()`).
     *
     * No-ops completely (leaves `json` untouched) when car_service is
     * unavailable — including simply not being on the DiLink5 platform.
     */
    @JvmStatic
    fun applyChargingOverrides(json: JSONObject) {
        try {
            val snapshot = chargingSnapshot()
            val state = snapshot[0].toInt()
            if (state == -1) return // car_service unavailable -- leave json completely untouched

            val rawCharging = (state == CHARGE_STATE_ACTIVE)
            val pluggedBase = (state == 2 || state == 3 || state == 4)
            // CHARGE_AND_DISCHARGE_SYSTEM_STATE==2 was originally assumed to
            // always mean "actively charging", but live user reports showed
            // sessions opening while the car was actually driving between
            // two different GPS locations, 0% SOC change, 1-13 min
            // durations -- not real charging. The property name covers both
            // charge AND discharge, and state 2 apparently also fires
            // during driving events (regen braking / high discharge). Both
            // `charging` and `plugged` (state==4 is separately ambiguous
            // between "plugged, idle" and "driving, coasting/regen") must
            // therefore be gated on the car genuinely not driving: gear ==
            // Park, or speed == 0, or -- right after a reboot, before
            // either has populated -- both still unknown (-1), which we
            // fail toward "not driving" rather than blocking charging
            // detection during that gap.
            val gear = gearValue()
            val speed = speedValue()
            val notDriving = (gear == GEAR_PARK) || (speed == 0) || (gear == -1 && speed == -1)
            val charging = rawCharging && notDriving
            val plugged = pluggedBase && notDriving

            // Debounced feed into the app's own session-tracking manager. Only the
            // FEED is debounced -- the displayed `charging`/`powerKw` below reflect
            // the raw live reading immediately, same as every other live field.
            feedSessionManager(debounceCharging(charging))

            json.put("charging", charging)
            json.put("plugged", plugged)
            if (charging) {
                json.put("powerKw", snapshot[1].toDouble())
                recordChargingSample(snapshot[1])

                val restHour = snapshot[2].toInt()
                val restMin = snapshot[3].toInt()
                if (restHour in 0..254 && restMin in 0..254) {
                    json.put("timeToFullMin", restHour * 60 + restMin)
                }

                val db = SocHistoryDatabase.getInstance()
                val sessionStart = db?.getOpenChargingSessionStart() ?: -1L
                if (db != null && sessionStart > 0) {
                    val kwh = db.getOpenSessionEnergyAddedKwhRaw(sessionStart)
                    if (kwh > 0) json.put("sessionKwh", kwh)
                }
            } else {
                json.put("powerKw", 0.0)
            }
        } catch (t: Throwable) {
            logger.debug("applyChargingOverrides failed: " + t.message)
        }
    }

    /**
     * On the first `false` reading, starts a "not charging since" timer but
     * still returns `true` (rides out a possible blip without feeding the
     * flip). Only returns `false` once 60 continuous real seconds have
     * elapsed with charging staying false. A `true` reading at any point
     * resets the timer and returns `true` immediately, as if the blip never
     * happened.
     *
     * Field-observed cause: unlocking the car can cause
     * CHARGE_AND_DISCHARGE_SYSTEM_STATE to drop out of the charging value
     * for a poll or two before recovering; without this debounce it
     * fragmented one continuous charge into multiple broken session rows.
     */
    private fun debounceCharging(rawCharging: Boolean): Boolean {
        if (rawCharging) {
            notChargingSinceMs = 0L
            return true
        }
        val since = notChargingSinceMs
        if (since == 0L) {
            notChargingSinceMs = System.currentTimeMillis()
            return true
        }
        return System.currentTimeMillis() - since < NOT_CHARGING_DEBOUNCE_MS
    }

    private fun feedSessionManager(charging: Boolean) {
        try {
            val csm = CameraDaemon.getChargingSessionManager() ?: return
            csm.onFusedChargingChanged(charging, "carsvc")
        } catch (t: Throwable) {
            logger.debug("feedSessionManager failed: " + t.message)
        }
    }

    // ==================== Power sample recording ====================

    /**
     * Records one power sample for the currently-open charging session via
     * SocHistoryDatabase's own public [SocHistoryDatabase.recordChargingSample]
     * DAO method (reused, not duplicated), then recomputes
     * peak/avg/is_dc and session energy from the stored curve. No-ops if no
     * session is open. Throttled to [SAMPLE_MIN_INTERVAL_MS] so a busy
     * poller can't flood `charging_power_samples`.
     */
    fun recordChargingSample(powerKw: Float) {
        try {
            val db = SocHistoryDatabase.getInstance() ?: return
            val sessionStart = db.getOpenChargingSessionStart()
            if (sessionStart <= 0) return

            val now = System.currentTimeMillis()
            if (now - lastSampleAtMs < SAMPLE_MIN_INTERVAL_MS) return
            lastSampleAtMs = now

            // Battery temperature wasn't investigated this session -- unavailable.
            val soc = VehicleDataMonitor.getInstance()?.batterySoc?.socPercent ?: 0.0
            val inserted = db.recordChargingSample(
                sessionStart, now, powerKw.toDouble(), soc, -999.0, -999.0, -999.0
            )
            if (inserted) {
                db.updateOpenSessionPeakAvgPower(sessionStart)
                recomputeSessionEnergyKwh(sessionStart)
            }
        } catch (t: Throwable) {
            logger.debug("recordChargingSample failed: " + t.message)
        }
    }

    /**
     * Corrected session-energy recompute for the currently-open session:
     * `energyKwh = avgPowerKw * (maxT - minT) / 3_600_000.0` over this
     * session's recorded samples. Deliberately bypasses the app's official
     * `SessionEnergyResolver`/`integrated_rate` pipeline for the OPEN
     * session only — see [SocHistoryDatabase.recomputeOpenSessionEnergyKwh]
     * for why.
     */
    fun recomputeSessionEnergyKwh(sessionStartTime: Long) {
        try {
            SocHistoryDatabase.getInstance()?.recomputeOpenSessionEnergyKwh(sessionStartTime)
        } catch (t: Throwable) {
            logger.debug("recomputeSessionEnergyKwh failed: " + t.message)
        }
    }
}
