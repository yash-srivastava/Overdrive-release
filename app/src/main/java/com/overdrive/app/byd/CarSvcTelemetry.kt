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
    private const val PROP_SHIFT_MODE = 0x21406407                // SHIFT_MODE
    private const val PROP_GEAR_STATUS = 0x21406406               // GEAR_STATUS
    private const val PROP_GEAR_BOX_AUTO_MODE_TYPE = 0x21404605   // GEAR_BOX_AUTO_MODE_TYPE
    private const val PROP_GEAR_BOX_AUTO_MODE_TYPE_TWO = 0x21403a06 // GEAR_BOX_AUTO_MODE_TYPE_TWO
    private const val PROP_BATTERY_VOLTAGE_SECOND = 0x2140461e    // 12V/secondary battery voltage
    private const val PROP_BATTERY_VOLTAGE = 0x2140460c           // 12V battery voltage — fallback for PROP_BATTERY_VOLTAGE_SECOND
    private const val PROP_SPEED_VALUE = 0x21406006               // SPEED_VALUE, vehicle speed km/h
    private const val PROP_VEHICLE_SPEED = 0x21604601             // VEHICLE_SPEED, float km/h (separate lastEvent)
    private const val PROP_SOC_VALUER = 0x21404622                // SOC_VALUER, main HV battery %
    // Live parked+driving display SOC on DiLink 5 (float lastEvent, e.g. 62.8).
    private const val PROP_REMAINING_BATTERY_POWER = 0x21604420    // REMAINING_BATTERY_POWER_R
    private const val PROP_ELEC_RANGE = 0x21404401                // ELEC_DRIVING_RANGE_BY_STANDARD_R, km
    // Regular cluster odometer. STATISTIC_TOTAL_MILEAGE is BYDAuto feature
    // 4096 (0x1000) in the vendor INT32/FLOAT car_service namespace; the
    // dumpsys property name is the source of truth when the hex differs.
    private const val PROP_STATISTIC_TOTAL_MILEAGE = 0x21401000   // STATISTIC_TOTAL_MILEAGE, int km
    private const val PROP_STATISTIC_TOTAL_MILEAGE_FLOAT = 0x21601000
    private const val PROP_TOTAL_MILEAGE = 0x21604409             // TOTAL_MILEAGE_VALUER, float km
    private const val PROP_EV_MILEAGE = 0x21404411                // E_V_MILEAGE_VALUES, int km
    private const val NAME_STATISTIC_TOTAL_MILEAGE = "STATISTIC_TOTAL_MILEAGE"
    private const val PROP_RIGHT_FRONT_LOCK = 0x2140506e          // door lock: right-front
    private const val PROP_LEFT_FRONT_LOCK = 0x21404627           // door lock: left-front
    private const val PROP_RIGHT_REAR_LOCK = 0x21405070           // door lock: right-rear
    private const val PROP_LEFT_REAR_LOCK = 0x2140506f            // door lock: left-rear
    private const val PROP_CHARGE_DISCHARGE_STATE = 0x2140461c    // CHARGE_AND_DISCHARGE_SYSTEM_STATE
    private const val PROP_CHARGING_GUN_STATE = 0x21403407        // CHARGING_GUN_STATER, 0=unplugged 1=connected
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
    // AC_COMPRESSOR_MODE, not A_C_WORK_MODE_R (0x214010a4). The latter is what this
    // constant pointed at before -- confirmed live to NEVER produce a lastEvent on
    // this vehicle across an entire drive session, including through explicit AC
    // off->on->off toggles. Property-name-swept the whole car_service dump during a
    // live toggle and found the actual firing signal: AC_COMPRESSOR_MODE,
    // AC_WORK_MODE_DRIVER_R (0x2140106a) and AC_WORK_MODE_COPILOT_R (0x2140106b) all
    // fired together (same event count, same timestamp) on the real toggle.
    // AC_COMPRESSOR_MODE is the single, non-seat-split reading of the three, so it's
    // the direct replacement here. Confirmed live: 1 while AC is genuinely on.
    private const val PROP_AC_WORK_MODE = 0x21401021               // AC_COMPRESSOR_MODE, 1=on
    private const val PROP_AC_FAN_LEVEL = 0x21401027              // AC_CONTROLLER_WIND_LEVEL, 0=no airflow
    // AC temperature setpoints, degrees C. Both confirmed live reading 17 at
    // the same moment on this vehicle -- a same-value reading on both is
    // consistent with a non-dual-zone/synced setting, not independent proof
    // each property maps to the side its name implies. Lower-confidence
    // mapping than the rest of this file's properties.
    private const val PROP_AC_DRIVER_TEMP_SET = 0x21401023        // AC_CONTROLLER_DRIVER_TEMP_SET
    private const val PROP_AC_TEMP_DEPUTY = 0x2140104b            // AC_TEMP_DEPUTY ("deputy" = passenger)
    // Pedals + indicators — live-decoded on DiLink 5 (brake held → 33% / state 1,
    // accel held → 99%, left stalk → 2, right stalk → 4, idle → 0 / 1).
    private const val PROP_ACCEL_DEEPNESS = 0x21400d00            // ACCELERATE_DEEPNESSR, 0-100
    private const val PROP_BRAKE_DEEPNESS = 0x21400d01            // BRAKE_DEEPNESSR, 0-100
    private const val PROP_BRAKE_PEDAL_STATE = 0x21403a05         // BRAKE_PEDAL_STATE, 0=up/1=pressed
    private const val PROP_TURN_LIGHT_STATE = 0x21404716          // TURN_LIGHT_STATE, 1=off 2=left 4=right

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

    @Volatile private var lastSampleAtMs: Long = 0L
    private val chargingDebounce = CarSvcChargingDebounce(NOT_CHARGING_DEBOUNCE_MS)
    private val sessionAdmit = CarSvcChargeSessionAdmit()

    /**
     * Reuse one dumpsys dump across nearby getters (SOC + 12V + speed + gear
     * on the same HTTP / collector tick). The live dump is ~16k lines and
     * [Process.waitFor] is the expensive part — without a TTL, overlaying
     * four signals would shell out four times.
     *
     * 2 s is the usability bound: overlay/locks/charging can lag that far,
     * trip motion still sees a stable fused speed for its 3 s start streak,
     * and ACC edges remain within a second of the next poll. Concurrent
     * callers share one in-flight dump so GearMonitor + /status + ACC
     * probes cannot stampede `com.android.car`.
     */
    const val DUMP_TTL_MS = 2000L
    private val dumpLock = Any()
    @Volatile private var cachedDump: String? = null
    @Volatile private var cachedDumpAtMs: Long = 0L

    /**
     * Last valid reading per car_service property id. DiLink 5 drops
     * `lastEvent` for a property while the car is asleep even though the
     * value has not changed — tyres, windows, and climate already looked
     * live only because those lastEvents were still present. SOC (and any
     * other overlay signal) went blank the moment its lastEvent vanished.
     * Hold the last sane value until a new valid lastEvent arrives; never
     * replace a good reading with "not in this dump". The map is also
     * written to {@code /data/local/tmp/carsvc_last_known.json} so a daemon
     * restart while parked can still paint the dash from the last ACC-on
     * dump.
     */
    private val lastKnown = java.util.concurrent.ConcurrentHashMap<Int, Number>()
    @Volatile private var lastGear: Int = -1
    @Volatile private var lastOdometerKm: Int = -1
    @Volatile private var lastKnownHydrated = false
    private val lastKnownFile = File("/data/local/tmp/carsvc_last_known.json")
    @JvmField var lastKnownStoreOverride: File? = null
    private val lastKnownPersistLock = Any()

    private fun lastKnownStore(): File = lastKnownStoreOverride ?: lastKnownFile

    private fun ensureLastKnownLoaded() {
        if (lastKnownHydrated) return
        synchronized(lastKnownPersistLock) {
            if (lastKnownHydrated) return
            lastKnownHydrated = true
            loadLastKnownFromDisk()
        }
    }

    private fun loadLastKnownFromDisk() {
        try {
            val file = lastKnownStore()
            if (!file.isFile) return
            val obj = JSONObject(file.readText())
            val props = obj.optJSONObject("props")
            if (props != null) {
                val keys = props.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val id = key.toIntOrNull() ?: continue
                    val d = props.optDouble(key, Double.NaN)
                    if (d.isNaN()) continue
                    lastKnown[id] = if (d == d.toLong().toDouble()
                        && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
                    ) d.toInt() else d.toFloat()
                }
            }
            val gear = obj.optInt("gear", -1)
            if (gear in 1..6) lastGear = gear
            val odometer = obj.optInt("odometerKm", -1)
            if (odometer > 0) {
                lastOdometerKm = odometer
                lastKnown.putIfAbsent(PROP_STATISTIC_TOTAL_MILEAGE, odometer)
            }
        } catch (_: Throwable) {
        }
    }

    private fun persistLastKnown() {
        synchronized(lastKnownPersistLock) {
            try {
                val props = JSONObject()
                for ((id, value) in lastKnown) {
                    props.put(id.toString(), value.toDouble())
                }
                val obj = JSONObject()
                obj.put("props", props)
                if (lastGear in 1..6) obj.put("gear", lastGear)
                val odo = if (lastOdometerKm > 0) lastOdometerKm
                else lastKnown[PROP_STATISTIC_TOTAL_MILEAGE]?.toInt()?.takeIf { it > 0 } ?: -1
                if (odo > 0) {
                    lastOdometerKm = odo
                    obj.put("odometerKm", odo)
                }
                val file = lastKnownStore()
                val parent = file.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                file.writeText(obj.toString())
            } catch (_: Throwable) {
            }
        }
    }

    // ==================== Core primitives ====================

    /**
     * Runs `dumpsys car_service`, waits for it to exit, then reads the
     * completed output from a per-call-unique temp file. Returns null on any
     * failure, or immediately (without shelling out) when [DiLink5Platform]
     * is not active. Fresh dumps are cached for [DUMP_TTL_MS].
     */
    fun dumpsysText(): String? {
        if (!DiLink5Platform.isActive()) return null
        val now = System.currentTimeMillis()
        val cached = cachedDump
        if (cached != null && now - cachedDumpAtMs in 0 until DUMP_TTL_MS) return cached
        synchronized(dumpLock) {
            val nowLocked = System.currentTimeMillis()
            val cachedLocked = cachedDump
            if (cachedLocked != null && nowLocked - cachedDumpAtMs in 0 until DUMP_TTL_MS) {
                return cachedLocked
            }
            val path = "/data/local/tmp/.carsvc_dump_${Thread.currentThread().id}_${System.nanoTime()}.txt"
            val tmpFile = File(path)
            return try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", "-c", "dumpsys car_service > $path 2>&1")
                )
                process.waitFor()
                if (!tmpFile.isFile) return null
                val text = tmpFile.readText()
                cachedDump = text
                cachedDumpAtMs = System.currentTimeMillis()
                text
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
                    content.toDoubleOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    // Single-pass index of every `lastEvent:Property:0x...` line, built once
    // per distinct dump text and reused for every findInText() lookup against
    // that same dump. Before this, every call to findInText — and every
    // caller that routes through it (getInt/getFloat, stickyInt/stickyFloat,
    // parseGearFromText's 3 property IDs, etc.) — did its own independent
    // linear scan of the full ~15k-line cached dump. applyCarSvcOverlay()
    // alone calls roughly a dozen of these per collector tick, so that was a
    // dozen full-text scans every 5s while driving. Keyed by reference
    // equality against [text] (which is always the same cached String
    // instance returned by repeat dumpsysText() calls within DUMP_TTL_MS),
    // so a fresh dump correctly invalidates the index without needing a
    // separate TTL/generation counter.
    @Volatile private var indexSourceText: String? = null
    @Volatile private var indexedProperties: Map<Int, Number> = emptyMap()
    private val indexLock = Any()

    private fun indexFor(text: String): Map<Int, Number> {
        if (text === indexSourceText) return indexedProperties
        synchronized(indexLock) {
            if (text === indexSourceText) return indexedProperties
            val map = HashMap<Int, Number>()
            val seen = HashSet<Int>()
            val marker = "lastEvent:Property:0x"
            for (line in text.lineSequence()) {
                val start = line.indexOf(marker)
                if (start < 0) continue
                val idStart = start + marker.length
                var idEnd = idStart
                while (idEnd < line.length && line[idEnd] != ',') idEnd++
                val propId = line.substring(idStart, idEnd).toIntOrNull(16) ?: continue
                // First occurrence of a propId wins, even if its value fails
                // to parse — matching findInText's original "return on first
                // matching line" behavior exactly (a later duplicate line for
                // the same propId must not silently override that outcome).
                if (!seen.add(propId)) continue
                parseValueLine(line)?.let { map[propId] = it }
            }
            indexedProperties = map
            indexSourceText = text
            return map
        }
    }

    /** Finds `propId`'s line in `text` and parses its value, or null if either step fails. */
    fun findInText(text: String?, propId: Int): Number? {
        if (text == null) return null
        return indexFor(text)[propId]
    }

    /**
     * car_service property id whose dumpsys name is [name], or null.
     * The definition line is present even when that property has no lastEvent.
     */
    fun findPropertyIdByName(text: String?, name: String): Int? {
        if (text == null || name.isEmpty()) return null
        val marker = "Property name:$name"
        for (line in text.lineSequence()) {
            if (!line.contains(marker)) continue
            val p = line.indexOf("Property:0x")
            if (p < 0) continue
            val start = p + "Property:0x".length
            var end = start
            while (end < line.length) {
                val c = line[end]
                if ((c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')) {
                    end++
                } else {
                    break
                }
            }
            if (end > start) return line.substring(start, end).toIntOrNull(16)
        }
        return null
    }

    fun getInt(propId: Int): Int {
        val live = findInText(dumpsysText(), propId)?.toInt()
        return holdInt(propId, live, live != null)
    }

    fun getFloat(propId: Int): Float {
        val live = findInText(dumpsysText(), propId)?.toFloat()
        return holdFloat(propId, live, live != null)
    }

    /**
     * Last valid int for [propId], or [live] when [valid] is true.
     * Package-visible so tests can prove a missing lastEvent does not
     * blank a previously seen SOC / tyre / window / climate reading.
     */
    fun holdInt(propId: Int, live: Int?, valid: Boolean): Int {
        ensureLastKnownLoaded()
        if (valid && live != null) {
            val previous = lastKnown.put(propId, live)
            if (previous == null || previous.toInt() != live) persistLastKnown()
            return live
        }
        return lastKnown[propId]?.toInt() ?: -1
    }

    fun holdFloat(propId: Int, live: Float?, valid: Boolean): Float {
        ensureLastKnownLoaded()
        if (valid && live != null) {
            val previous = lastKnown.put(propId, live)
            if (previous == null || previous.toFloat() != live) persistLastKnown()
            return live
        }
        return lastKnown[propId]?.toFloat() ?: -1.0f
    }

    /**
     * Parse [text] for [propId] and keep the last value that falls in
     * [[min], [max]]. A dump with no lastEvent, a failed dumpsys (`text`
     * null), or an out-of-range reading all return the held value.
     */
    fun stickyInt(text: String?, propId: Int, min: Int, max: Int): Int {
        val live = findInText(text, propId)?.toInt()
        return holdInt(propId, live, live != null && live in min..max)
    }

    fun stickyFloat(text: String?, propId: Int, min: Float, max: Float): Float {
        val live = findInText(text, propId)?.toFloat()
        return holdFloat(propId, live, live != null && live in min..max)
    }

    /** Test hook — production never clears last-known except by a new valid lastEvent. */
    fun clearLastKnownForTest() {
        lastKnown.clear()
        lastGear = -1
        lastOdometerKm = -1
        lastKnownHydrated = true
    }

    fun reloadLastKnownForTest() {
        lastKnown.clear()
        lastGear = -1
        lastOdometerKm = -1
        lastKnownHydrated = false
        ensureLastKnownLoaded()
    }

    // ==================== Individual signals ====================

    /**
     * Raw gear value (1=P, 2=R, 3=N, 4=D, 5=M, 6=S — matches
     * RecordingModeManager.GEAR_* exactly), or -1 if unavailable/out of range.
     *
     * <p>Reads only gear-named lastEvents. A full `dumpsys car_service` dump
     * always contains unrelated {@code int32Values: [2]} / {@code [4]} lines;
     * treating those as PRND made overlay gear flicker while parked.
     */
    fun gearValue(): Int {
        ensureLastKnownLoaded()
        val parsed = parseGearFromText(dumpsysText())
        if (parsed in 1..6) {
            if (lastGear != parsed) {
                lastGear = parsed
                persistLastKnown()
            }
            return parsed
        }
        return lastGear
    }

    /**
     * Parse PRND from a dumpsys blob without inventing gear from other
     * properties. Package-visible for tests.
     */
    fun parseGearFromText(text: String?): Int {
        if (text == null) return -1
        decodeShiftMode(findInText(text, PROP_SHIFT_MODE)?.toInt())?.let { return it }
        prndIfValid(findInText(text, PROP_GEAR_STATUS)?.toInt())?.let { return it }
        prndIfValid(findInText(text, PROP_GEAR_BOX_AUTO_MODE_TYPE)?.toInt())?.let { return it }
        prndIfValid(findInText(text, PROP_GEAR_BOX_AUTO_MODE_TYPE_TWO)?.toInt())?.let { return it }
        prndIfValid(findInText(text, PROP_GEAR_R)?.toInt())?.let { return it }
        return -1
    }

    private fun prndIfValid(raw: Int?): Int? {
        return if (raw != null && raw in 1..6) raw else null
    }

    /** SHIFT_MODE: 0/1=P, 2=R, 3=N, 4=D, 5=M, 6=S. */
    private fun decodeShiftMode(raw: Int?): Int? {
        return when (raw) {
            0, 1 -> 1
            2 -> 2
            3 -> 3
            4 -> 4
            5 -> 5
            6 -> 6
            else -> null
        }
    }

    /** 12V/secondary battery voltage, or -1.0 if unavailable. Float (not Int) since a future
     *  firmware could report a fractional volt even though today's reading is a whole number.
     *  Falls back to [PROP_BATTERY_VOLTAGE] when the secondary property is missing, 0, or
     *  outside a plausible 12V range — a dead lastEvent of 0 used to win and blank the UI. */
    fun batteryVoltage12v(): Float {
        val text = dumpsysText()
        val primary = stickyFloat(text, PROP_BATTERY_VOLTAGE_SECOND, 8f, 16f)
        if (isPlausible12v(primary)) return primary
        val fallback = stickyFloat(text, PROP_BATTERY_VOLTAGE, 8f, 16f)
        if (isPlausible12v(fallback)) return fallback
        return -1.0f
    }

    private fun isPlausible12v(volts: Float): Boolean = volts in 8f..16f

    /** Vehicle speed in km/h from SPEED_VALUE, or -1 if unavailable. */
    fun speedValue(): Int {
        return stickyInt(dumpsysText(), PROP_SPEED_VALUE, 0, 300)
    }

    /**
     * Vehicle speed in km/h from VEHICLE_SPEED ([PROP_VEHICLE_SPEED]), a
     * separate lastEvent from [speedValue]. Live Sealion 7 parked reading is
     * float `0.0` (status 2) while SPEED_VALUE has no lastEvent at all.
     * Returns -1 if unavailable.
     */
    fun vehicleSpeedKmh(): Int {
        val raw = stickyFloat(dumpsysText(), PROP_VEHICLE_SPEED, 0f, 300f)
        if (raw >= 0f && raw <= 300f) return Math.round(raw)
        return -1
    }

    /** [speedValue] first, then [vehicleSpeedKmh]. -1 if neither has a reading. */
    fun resolvedSpeedKmh(): Int {
        val primary = speedValue()
        if (primary >= 0) return primary
        return vehicleSpeedKmh()
    }

    /** Accelerator travel percent (0-100), or -1 if unavailable. */
    fun accelPercent(): Int {
        return stickyInt(dumpsysText(), PROP_ACCEL_DEEPNESS, 0, 100)
    }

    /** Brake travel percent (0-100), or -1 if unavailable. */
    fun brakePercent(): Int {
        return stickyInt(dumpsysText(), PROP_BRAKE_DEEPNESS, 0, 100)
    }

    /** Brake switch: 1=pressed, 0=released, -1=unavailable. */
    fun brakePressed(): Int {
        val raw = stickyInt(dumpsysText(), PROP_BRAKE_PEDAL_STATE, 0, 1)
        return if (raw == 0 || raw == 1) raw else -1
    }

    /**
     * Physical charging-gun latch from CHARGING_GUN_STATER.
     * Proven live on DiLink 5: 0 = unplugged, 1 = connected. -1 if unavailable.
     *
     * Binary car_service encoding — not [BydVehicleData.chargingGunState]
     * (1=disconnected, 2/3/4=connected, 5=vtol). Overlay callers must map
     * before writing that field.
     */
    fun gunConnected(): Int {
        val raw = stickyInt(dumpsysText(), PROP_CHARGING_GUN_STATE, 0, 1)
        return if (raw == 0 || raw == 1) raw else -1
    }

    /**
     * Combined turn-lamp enum matching the overlay HAL
     * `getTurnLightFlashState` mapping: 1=off, 2|3=left, 4|5=right, 6|7=hazard.
     * -1 if unavailable.
     */
    fun turnLightState(): Int {
        return stickyInt(dumpsysText(), PROP_TURN_LIGHT_STATE, 1, 7)
    }

    /**
     * Main HV battery state-of-charge percent, or [Double.NaN] if none.
     *
     * <p>DiLink 5 first call is car_service {@code REMAINING_BATTERY_POWER_R}
     * (live lastEvent, parked and driving). {@code SOC_VALUER} is only used
     * when that property is blank. A live car_service reading is written to
     * the local cache. When car_service is blank: in-memory hold, then the
     * disk cache, then the latest {@code soc_history} sample.
     */
    fun parseSocFromText(text: String?): Double {
        val remain = findInText(text, PROP_REMAINING_BATTERY_POWER)?.toDouble()
        if (remain != null && remain in 0.0..100.0) return remain
        val named = findInText(text, PROP_SOC_VALUER)?.toDouble()
        if (named != null && named in 0.0..100.0) return named
        return Double.NaN
    }

    fun socPercentValue(): Double {
        val live = parseSocFromText(dumpsysText())
        if (!live.isNaN() && live in 0.0..100.0) {
            holdFloat(PROP_REMAINING_BATTERY_POWER, live.toFloat(), true)
            persistSoc(live)
            return live
        }
        val held = holdFloat(PROP_REMAINING_BATTERY_POWER, null, false)
        if (held in 0f..100f) return held.toDouble()
        val disk = readPersistedSoc()
        if (!disk.isNaN() && disk in 0.0..100.0) {
            holdFloat(PROP_REMAINING_BATTERY_POWER, disk.toFloat(), true)
            return disk
        }
        val hist = readHistorySoc()
        if (hist in 0..100) {
            holdFloat(PROP_REMAINING_BATTERY_POWER, hist.toFloat(), true)
            persistSoc(hist.toDouble())
            return hist.toDouble()
        }
        return Double.NaN
    }

    fun socPercent(): Int {
        val value = socPercentValue()
        if (value.isNaN() || value !in 0.0..100.0) return -1
        return Math.round(value).toInt()
    }

    private val lastSocFile = File("/data/local/tmp/carsvc_last_soc.txt")

    private fun persistSoc(percent: Double) {
        if (percent.isNaN() || percent !in 0.0..100.0) return
        try {
            lastSocFile.writeText(percent.toString())
        } catch (_: Throwable) {
        }
    }

    private fun readPersistedSoc(): Double {
        return try {
            if (!lastSocFile.isFile) return Double.NaN
            lastSocFile.readText().trim().toDoubleOrNull()
                ?.takeIf { it in 0.0..100.0 } ?: Double.NaN
        } catch (_: Throwable) {
            Double.NaN
        }
    }

    private fun readHistorySoc(): Int {
        return try {
            val raw = SocHistoryDatabase.getInstance()?.latestRecordedSocPercent ?: return -1
            if (raw.isNaN()) return -1
            Math.round(raw).toInt().takeIf { it in 0..100 } ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Remaining EV range in km ([PROP_ELEC_RANGE]), or -1 if unavailable.
     * Firmware sentinels 2046/2047 (and the 0..4095 "no reading" band above
     * 999) are treated as missing — live Sealion 7 lastEvent is a real km
     * value in the same 1-999 band the rest of the app already displays.
     */
    fun elecRangeKm(): Int {
        return stickyInt(dumpsysText(), PROP_ELEC_RANGE, 1, 999)
    }

    /**
     * Cluster odometer in km from car_service {@code STATISTIC_TOTAL_MILEAGE}.
     * Parked DiLink 5 often has no live lastEvent; [holdInt] plus the named
     * {@code odometerKm} last-known field keep the last ACC-on reading so
     * trips and overnight charge sessions still see the cluster mileage.
     */
    fun totalMileageKm(): Int {
        val live = parseTotalMileageFromText(dumpsysText()).takeIf { it > 0 }
        if (live != null) lastOdometerKm = live
        val km = holdInt(PROP_STATISTIC_TOTAL_MILEAGE, live, live != null)
        if (km > 0) lastOdometerKm = km
        return km
    }

    fun parseTotalMileageFromText(text: String?): Int {
        val namedId = findPropertyIdByName(text, NAME_STATISTIC_TOTAL_MILEAGE)
            ?: findPropertyIdByName(text, "TOTAL_MILEAGE_VALUER")
        val ids = intArrayOf(
            namedId ?: 0,
            PROP_STATISTIC_TOTAL_MILEAGE,
            PROP_STATISTIC_TOTAL_MILEAGE_FLOAT,
            PROP_TOTAL_MILEAGE,
            PROP_EV_MILEAGE
        )
        var prev = 0
        for (id in ids) {
            if (id == 0 || id == prev) continue
            prev = id
            val km = mileageToKm(findInText(text, id)?.toDouble())
            if (km != null && km > 0) return km
        }
        return -1
    }

    internal fun mileageToKm(raw: Double?): Int? {
        if (raw == null || !raw.isFinite() || raw <= 0.0) return null
        val km = if (raw >= 1_000_000.0) raw / 10.0 else raw
        val rounded = Math.round(km).toInt()
        return rounded.takeIf { it in 1..999_999 }
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
        val text = dumpsysText()
        var rf = -1
        var lf = -1
        var rr = -1
        var lr = -1
        if (text != null) {
            val rfKey = buildSearchKey(PROP_RIGHT_FRONT_LOCK)
            val lfKey = buildSearchKey(PROP_LEFT_FRONT_LOCK)
            val rrKey = buildSearchKey(PROP_RIGHT_REAR_LOCK)
            val lrKey = buildSearchKey(PROP_LEFT_REAR_LOCK)
            for (line in text.lineSequence()) {
                if (rf == -1 && line.contains(rfKey)) rf = parseValueLine(line)?.toInt() ?: -1
                if (lf == -1 && line.contains(lfKey)) lf = parseValueLine(line)?.toInt() ?: -1
                if (rr == -1 && line.contains(rrKey)) rr = parseValueLine(line)?.toInt() ?: -1
                if (lr == -1 && line.contains(lrKey)) lr = parseValueLine(line)?.toInt() ?: -1
                if (rf != -1 && lf != -1 && rr != -1 && lr != -1) break
            }
        }
        rf = holdInt(PROP_RIGHT_FRONT_LOCK, rf, rf == 1 || rf == 2)
        lf = holdInt(PROP_LEFT_FRONT_LOCK, lf, lf == 1 || lf == 2)
        rr = holdInt(PROP_RIGHT_REAR_LOCK, rr, rr == 1 || rr == 2)
        lr = holdInt(PROP_LEFT_REAR_LOCK, lr, lr == 1 || lr == 2)
        // `overall` used to require ALL FOUR doors to report before claiming
        // "locked" — on this vehicle one door's lock property (e.g. left-front)
        // can go a long time without ever firing a car_service event, which
        // permanently stuck `overall` at -1 even when the other three doors
        // clearly agreed. Now it only requires agreement among the doors that
        // HAVE reported (any confirmed-unlocked door still wins immediately —
        // that's a safety property, not a completeness one).
        val known = listOf(rf, lf, rr, lr).filter { it != -1 }
        val overall = when {
            rf == 1 || lf == 1 || rr == 1 || lr == 1 -> 1
            known.isNotEmpty() && known.all { it == 2 } -> 2
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
        val text = dumpsysText()
        var fl = -1
        var fr = -1
        var rl = -1
        var rr = -1
        if (text != null) {
            val flKey = buildSearchKey(PROP_TYRE_LEFT_FRONT)
            val frKey = buildSearchKey(PROP_TYRE_RIGHT_FRONT)
            val rlKey = buildSearchKey(PROP_TYRE_LEFT_REAR)
            val rrKey = buildSearchKey(PROP_TYRE_RIGHT_REAR)
            for (line in text.lineSequence()) {
                if (fl == -1 && line.contains(flKey)) fl = parseValueLine(line)?.toInt() ?: -1
                if (fr == -1 && line.contains(frKey)) fr = parseValueLine(line)?.toInt() ?: -1
                if (rl == -1 && line.contains(rlKey)) rl = parseValueLine(line)?.toInt() ?: -1
                if (rr == -1 && line.contains(rrKey)) rr = parseValueLine(line)?.toInt() ?: -1
                if (fl != -1 && fr != -1 && rl != -1 && rr != -1) break
            }
        }
        return intArrayOf(
            holdInt(PROP_TYRE_LEFT_FRONT, fl, fl > 0),
            holdInt(PROP_TYRE_RIGHT_FRONT, fr, fr > 0),
            holdInt(PROP_TYRE_LEFT_REAR, rl, rl > 0),
            holdInt(PROP_TYRE_RIGHT_REAR, rr, rr > 0)
        )
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
    // Plausible tyre-pressure bounds used to sanity-check the raw reading
    // before trusting it. This property's raw unit is confirmed 0.1psi on
    // the vehicle this was field-verified against, but BYD doesn't document
    // the unit anywhere and other models/regions could report something
    // else entirely -- the same risk that produced the bar-vs-kPa mismatch
    // handled in BydDataCollector.collectTyre(). A genuine tyre is never
    // outside this window even half-flat; anything outside it is far more
    // likely a misread raw scale on an untested vehicle than a real
    // reading, so it's logged and withheld rather than shown as a wrong
    // number.
    private const val MIN_PLAUSIBLE_TYRE_PSI = 10.0
    private const val MAX_PLAUSIBLE_TYRE_PSI = 60.0

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
                if (psi in MIN_PLAUSIBLE_TYRE_PSI..MAX_PLAUSIBLE_TYRE_PSI) {
                    corner.put("psi", psi)
                    corner.put("kPa", Math.round(psi * 6.89476).toInt())
                    corner.put("available", true)
                    any = true
                } else {
                    logger.warn("Tyre pressure raw=$r at ${keys[i]} produced implausible " +
                            "$psi psi under the confirmed 0.1psi scale — withholding rather " +
                            "than showing a likely wrong-unit reading. If this vehicle " +
                            "genuinely reports a different raw scale, this value is the one " +
                            "to investigate.")
                    corner.put("available", false)
                }
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
        val text = dumpsysText()
        var lf = -1
        var rf = -1
        var lr = -1
        var rr = -1
        var sun = -1
        if (text != null) {
            val lfKey = buildSearchKey(PROP_WINDOW_LEFT_FRONT)
            val rfKey = buildSearchKey(PROP_WINDOW_RIGHT_FRONT)
            val lrKey = buildSearchKey(PROP_WINDOW_LEFT_REAR)
            val rrKey = buildSearchKey(PROP_WINDOW_RIGHT_REAR)
            val sunKey = buildSearchKey(PROP_WINDOW_SUNROOF)
            for (line in text.lineSequence()) {
                if (lf == -1 && line.contains(lfKey)) lf = parseValueLine(line)?.toInt() ?: -1
                if (rf == -1 && line.contains(rfKey)) rf = parseValueLine(line)?.toInt() ?: -1
                if (lr == -1 && line.contains(lrKey)) lr = parseValueLine(line)?.toInt() ?: -1
                if (rr == -1 && line.contains(rrKey)) rr = parseValueLine(line)?.toInt() ?: -1
                if (sun == -1 && line.contains(sunKey)) sun = parseValueLine(line)?.toInt() ?: -1
                if (lf != -1 && rf != -1 && lr != -1 && rr != -1 && sun != -1) break
            }
        }
        return intArrayOf(
            holdInt(PROP_WINDOW_LEFT_FRONT, lf, lf in 0..100),
            holdInt(PROP_WINDOW_RIGHT_FRONT, rf, rf in 0..100),
            holdInt(PROP_WINDOW_LEFT_REAR, lr, lr in 0..100),
            holdInt(PROP_WINDOW_RIGHT_REAR, rr, rr in 0..100),
            holdInt(PROP_WINDOW_SUNROOF, sun, sun in 0..100)
        )
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
        val text = dumpsysText()
        var mode = -1
        var fan = -1
        if (text != null) {
            val modeKey = buildSearchKey(PROP_AC_WORK_MODE)
            val fanKey = buildSearchKey(PROP_AC_FAN_LEVEL)
            for (line in text.lineSequence()) {
                if (mode == -1 && line.contains(modeKey)) mode = parseValueLine(line)?.toInt() ?: -1
                if (fan == -1 && line.contains(fanKey)) fan = parseValueLine(line)?.toInt() ?: -1
                if (mode != -1 && fan != -1) break
            }
        }
        mode = holdInt(PROP_AC_WORK_MODE, mode, mode != -1)
        fan = holdInt(PROP_AC_FAN_LEVEL, fan, fan >= 0)
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
        val text = dumpsysText()
        var driver = -1
        var passenger = -1
        if (text != null) {
            val driverKey = buildSearchKey(PROP_AC_DRIVER_TEMP_SET)
            val passengerKey = buildSearchKey(PROP_AC_TEMP_DEPUTY)
            for (line in text.lineSequence()) {
                if (driver == -1 && line.contains(driverKey)) driver = parseValueLine(line)?.toInt() ?: -1
                if (passenger == -1 && line.contains(passengerKey)) passenger = parseValueLine(line)?.toInt() ?: -1
                if (driver != -1 && passenger != -1) break
            }
        }
        return intArrayOf(
            holdInt(PROP_AC_DRIVER_TEMP_SET, driver, driver in 10..40),
            holdInt(PROP_AC_TEMP_DEPUTY, passenger, passenger in 10..40)
        )
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
        val text = dumpsysText()
        var state = -1f
        var power = -1f
        var hour = -1f
        var min = -1f
        if (text != null) {
            val stateKey = buildSearchKey(PROP_CHARGE_DISCHARGE_STATE)
            val powerKey = buildSearchKey(PROP_CHARGING_POWER)
            val hourKey = buildSearchKey(PROP_CHARGING_RESTTIME_HOUR)
            val minKey = buildSearchKey(PROP_CHARGING_RESTTIME_MIN)
            for (line in text.lineSequence()) {
                if (state == -1f && line.contains(stateKey)) state = parseValueLine(line)?.toFloat() ?: -1f
                if (power == -1f && line.contains(powerKey)) power = parseValueLine(line)?.toFloat() ?: -1f
                if (hour == -1f && line.contains(hourKey)) hour = parseValueLine(line)?.toFloat() ?: -1f
                if (min == -1f && line.contains(minKey)) min = parseValueLine(line)?.toFloat() ?: -1f
                if (state != -1f && power != -1f && hour != -1f && min != -1f) break
            }
        }
        return floatArrayOf(
            holdFloat(PROP_CHARGE_DISCHARGE_STATE, state, state >= 0f),
            holdFloat(PROP_CHARGING_POWER, power, power >= 0f),
            holdFloat(PROP_CHARGING_RESTTIME_HOUR, hour, hour >= 0f),
            holdFloat(PROP_CHARGING_RESTTIME_MIN, min, min >= 0f)
        )
    }

    /**
     * Whether the user has BYD cloud telemetry merge enabled (the same
     * toggle the Settings cloud/telemetry switch controls). Defaults to
     * `false` (local-only) on any error — matching
     * [BydCloudConfig.fromUnifiedConfig]'s own default — so a config read
     * failure cannot keep painting stale cloud lock/SOC/range over live
     * car_service values.
     */
    fun isCloudEnabled(): Boolean {
        return try {
            BydCloudConfig.fromUnifiedConfig().cloudDataMerge
        } catch (t: Throwable) {
            false
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
            val now = System.currentTimeMillis()
            if (state == -1) {
                // car_service gave us NOTHING usable for the whole charge-state
                // cluster this poll -- confirmed live to be a genuine dumpsys
                // read flake (the SAME lastEvent, same timestamp, reappeared on
                // a LATER poll with nothing having changed on the vehicle side),
                // not proof charging actually stopped. Bridge it the same way a
                // true->false blip is already bridged below: if the debounce is
                // still holding an armed true from a recent real reading, keep
                // showing charging/plugged true rather than falling back to
                // readLivePublication's own verdict (often ALSO unavailable --
                // the vendor HAL is dead under the same conditions). No power
                // reading exists this tick, so powerKw/session fields are left
                // as whatever was already written before this override ran.
                if (chargingDebounce.apply(false, now)) {
                    json.put("charging", true)
                    json.put("plugged", true)
                }
                return
            }

            val rawCharging = (state == CHARGE_STATE_ACTIVE)
            val gun = gunConnected()
            val pluggedBase = (gun == 1)
            // CHARGE_AND_DISCHARGE_SYSTEM_STATE==2 was originally assumed to
            // always mean "actively charging", but live user reports showed
            // sessions opening while the car was actually driving between
            // two different GPS locations, 0% SOC change, 1-13 min
            // durations -- not real charging. The property name covers both
            // charge AND discharge, and state 2 also fires during regen.
            // Plugged-in is the physical gun latch, not that same state
            // (which cannot represent "cable in, idle / scheduled wait").
            // Sessions still require notDriving (regen is high kW). Gun is
            // primary; if that property flickers, pack inflow admits: ≥3 kW
            // immediately, or ≥1 kW for 45s. Zero-kW state==2 is not a
            // session — that is idle / stuck / discharge.
            val gear = gearValue()
            val speed = resolvedSpeedKmh()
            val notDriving = (gear == GEAR_PARK) || (speed == 0) || (gear == -1 && speed == -1)
            val charging = sessionAdmit.admit(
                    rawCharging, notDriving, pluggedBase, snapshot[1],
                    now)

            // Debounce the DISPLAYED charging/plugged/powerKw too, not just the
            // feed into the session-tracking manager below. A durably-open
            // session with real recorded power samples is exactly what this
            // debounce protects: a single missed/blipped car_service read
            // (confirmed this session to happen even mid-charge, with no
            // change on the vehicle side) previously flickered the dashboard
            // straight to charging=false while the session stayed open
            // underneath it -- the two could visibly disagree. Held for
            // NOT_CHARGING_DEBOUNCE_MS (60s) after the last real charging=true
            // reading before actually reporting false.
            val debouncedCharging = chargingDebounce.apply(charging, now)
            val plugged = notDriving && (pluggedBase || debouncedCharging)

            feedSessionManager(debouncedCharging)

            json.put("charging", debouncedCharging)
            json.put("plugged", plugged)
            // Own socPercent the same way every other field above is owned here:
            // fresh from car_service, unconditionally, on every call. Without this,
            // the dashboard's charging widget (index.html's dashChargeSoc) and the
            // charging-page hero both fell back to "--" whenever readLivePublication's
            // own earlier SOC read (CarSvcTelemetry.socPercentValue() +
            // VehicleDataMonitor fallback, evaluated before this override runs) came
            // back empty, even while the SAME socPercentValue() call succeeded a few
            // lines later for the dashboard's top SOC ring -- a stale/absent value
            // sitting next to a live one from the identical source. Applying it here
            // guarantees whichever socPercentValue() call this request's dumpsysText()
            // cache actually served for the OTHER dashboard fields is the one every
            // charging-block consumer sees too.
            //
            // Same fallback as the top SOC ring (HttpServer's /status handler):
            // socPercentValue() already chains live -> held -> persisted-disk ->
            // history internally, so it rarely comes back NaN, but on the rare miss
            // fall through to VehicleDataMonitor's vendor-HAL reading rather than
            // omitting the field -- otherwise this widget alone would blank on
            // exactly the narrow case the top ring is protected against.
            var socPercent = socPercentValue()
            if (socPercent.isNaN() || socPercent !in 0.0..100.0) {
                val hwSoc = VehicleDataMonitor.getInstance()?.batterySoc?.socPercent
                if (hwSoc != null && hwSoc in 0.0..100.0) socPercent = hwSoc
            }
            if (!socPercent.isNaN() && socPercent in 0.0..100.0) {
                json.put("socPercent", socPercent)
            }
            if (debouncedCharging) {
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
