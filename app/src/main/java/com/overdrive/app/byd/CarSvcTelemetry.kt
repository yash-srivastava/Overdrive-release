package com.overdrive.app.byd

import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.monitor.ChargingStateData
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DiLink 5 vehicle telemetry read from `dumpsys car_service`.
 *
 * The S7 vendor SDK frequently returns 0/-1 or throws for these properties.
 * Every live read is explicitly fenced by [DiLink5Platform.isSelected], so
 * non-DiLink5 models never execute a shell command or change data source.
 */
object CarSvcTelemetry {
    private val logger = DaemonLogger.getInstance("CarSvcTelemetry")

    private const val PROP_GEAR_R = 0x21403a0a
    private const val PROP_SHIFT_MODE = 0x21406407
    private const val PROP_GEAR_STATUS = 0x21406406
    private const val PROP_GEAR_BOX_AUTO_MODE_TYPE = 0x21404605
    private const val PROP_GEAR_BOX_AUTO_MODE_TYPE_TWO = 0x21403a06
    private const val PROP_BATTERY_VOLTAGE_SECOND = 0x2140461e
    private const val PROP_BATTERY_VOLTAGE = 0x2140460c
    private const val PROP_SPEED_VALUE = 0x21406006
    private const val PROP_VEHICLE_SPEED = 0x21604601
    private const val PROP_SOC_VALUE = 0x21404622
    private const val PROP_REMAINING_BATTERY_POWER = 0x21604420
    private const val PROP_ELEC_RANGE = 0x21404401
    private const val PROP_STATISTIC_TOTAL_MILEAGE = 0x21401000
    private const val PROP_STATISTIC_TOTAL_MILEAGE_FLOAT = 0x21601000
    private const val PROP_TOTAL_MILEAGE = 0x21604409
    private const val PROP_EV_MILEAGE = 0x21404411
    private const val PROP_RIGHT_FRONT_LOCK = 0x2140506e
    private const val PROP_LEFT_FRONT_LOCK = 0x21404627
    private const val PROP_RIGHT_REAR_LOCK = 0x21405070
    private const val PROP_LEFT_REAR_LOCK = 0x2140506f
    private const val PROP_CHARGE_DISCHARGE_STATE = 0x2140461c
    private const val PROP_CHARGING_GUN_STATE = 0x21403407
    private const val PROP_CHARGING_POWER = 0x21603408
    private const val PROP_CHARGING_RESTTIME_HOUR = 0x21403440
    private const val PROP_CHARGING_RESTTIME_MIN = 0x21403441
    private const val PROP_TYRE_LEFT_FRONT = 0x2160801d
    private const val PROP_TYRE_RIGHT_FRONT = 0x2160801e
    private const val PROP_TYRE_LEFT_REAR = 0x2160801f
    private const val PROP_TYRE_RIGHT_REAR = 0x21608020
    private const val PROP_WINDOW_LEFT_FRONT = 0x21405018
    private const val PROP_WINDOW_RIGHT_FRONT = 0x2140501a
    private const val PROP_WINDOW_LEFT_REAR = 0x21405019
    private const val PROP_WINDOW_RIGHT_REAR = 0x2140501b
    private const val PROP_WINDOW_SUNROOF = 0x2140501d
    private const val PROP_AC_COMPRESSOR_MODE = 0x21401021
    private const val PROP_AC_FAN_LEVEL = 0x21401027
    private const val PROP_AC_DRIVER_TEMP_SET = 0x21401023
    private const val PROP_AC_PASSENGER_TEMP_SET = 0x2140104b
    private const val PROP_ACCEL_DEEPNESS = 0x21400d00
    private const val PROP_BRAKE_DEEPNESS = 0x21400d01
    private const val PROP_BRAKE_PEDAL_STATE = 0x21403a05
    private const val PROP_TURN_LIGHT_STATE = 0x21404716

    private const val RAW_CHARGE_STATE_ACTIVE = 2
    private const val GEAR_PARK = 1
    private const val NAME_STATISTIC_TOTAL_MILEAGE = "STATISTIC_TOTAL_MILEAGE"
    private const val POWER_MUTE_SECTION_MARKER = "power mute state"
    private const val POWER_MODE_LINE_MARKER = "powermode"
    private const val POWER_MUTE_SECTION_SCAN_LINES = 4

    const val DUMP_TTL_MS = 2_000L
    private const val DUMP_PROCESS_TIMEOUT_MS = 4_000L
    private const val DUMP_FAILURE_BACKOFF_MS = 2_000L

    private val dumpLock = Any()
    private data class DumpSnapshot(
        val text: String,
        val observedAtNanos: Long,
        val cachedAtNanos: Long
    )
    @Volatile private var cachedDump: DumpSnapshot? = null
    @Volatile private var lastDumpFailureAtNanos = 0L

    private val indexLock = Any()
    private data class PropertyIndex(
        val source: String?,
        val values: Map<Int, Number>,
        val healthyValues: Map<Int, Number>
    )
    @Volatile private var propertyIndex =
        PropertyIndex(null, emptyMap(), emptyMap())
    private val lastKnown = ConcurrentHashMap<Int, Number>()
    @Volatile private var lastGear = -1
    @Volatile private var lastOdometerKm = -1

    @JvmStatic
    fun isActive(): Boolean = DiLink5Platform.isSelected()

    /**
     * One cached, single-flight car_service dump. The timeout prevents a
     * wedged Binder/service command from blocking collector threads.
     */
    private fun dumpSnapshot(): DumpSnapshot? {
        if (!isActive()) return null
        val now = System.nanoTime()
        cachedDump?.let {
            if (elapsedMs(now, it.cachedAtNanos) < DUMP_TTL_MS) return it
        }
        if (lastDumpFailureAtNanos > 0L
            && elapsedMs(now, lastDumpFailureAtNanos) < DUMP_FAILURE_BACKOFF_MS
        ) {
            return null
        }
        synchronized(dumpLock) {
            val lockedNow = System.nanoTime()
            cachedDump?.let {
                if (elapsedMs(lockedNow, it.cachedAtNanos) < DUMP_TTL_MS) {
                    return it
                }
            }
            if (lastDumpFailureAtNanos > 0L
                && elapsedMs(lockedNow, lastDumpFailureAtNanos)
                    < DUMP_FAILURE_BACKOFF_MS
            ) {
                return null
            }
            val path = com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/.carsvc_dump_${Thread.currentThread().id}_${System.nanoTime()}.txt")
            val file = File(path)
            var process: Process? = null
            var succeeded = false
            return try {
                val observedAtNanos = System.nanoTime()
                process = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", "-c", "dumpsys -t 3 car_service > $path 2>&1")
                )
                if (!process.waitFor(DUMP_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    logger.warn("dumpsys car_service timed out")
                    return null
                }
                if (!file.isFile) return null
                val text = file.readText()
                if (text.isBlank()) return null
                val snapshot = DumpSnapshot(
                    text,
                    observedAtNanos,
                    System.nanoTime()
                )
                cachedDump = snapshot
                succeeded = true
                snapshot
            } catch (t: Throwable) {
                logger.debug("dumpsysText failed: ${t.message}")
                null
            } finally {
                if (!succeeded) {
                    lastDumpFailureAtNanos = System.nanoTime()
                }
                try {
                    process?.destroy()
                    if (file.exists()) file.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun elapsedMs(nowNanos: Long, thenNanos: Long): Long {
        val elapsed = nowNanos - thenNanos
        return if (elapsed < 0L) Long.MAX_VALUE else elapsed / 1_000_000L
    }

    fun dumpsysText(): String? = dumpSnapshot()?.text

    /**
     * Raw BYD Automotive power-mode line from a car_service dump, e.g.
     * "current : 4=PowerMode Standby". Sealion 7 prints it inside a
     * "Power Mute State" section; mirror the field-validated grammar
     * (section header, then the "current" row within the next few lines).
     * Returns null when no recognizable power-mode line exists — callers
     * must treat null as indeterminate, never as ACC OFF.
     */
    fun parsePowerModeLineFromText(text: String?): String? {
        if (text == null) return null
        val lines = text.lineSequence().iterator()
        while (lines.hasNext()) {
            val line = lines.next()
            if (!line.contains(POWER_MUTE_SECTION_MARKER, ignoreCase = true)) {
                continue
            }
            if (line.contains(POWER_MODE_LINE_MARKER, ignoreCase = true)) {
                // Header carries the value inline ("Power Mute State : 4=PowerMode …").
                return line.trim()
            }
            // Only the "current" row may classify — a "previous : N=PowerMode…"
            // row inside the same section must never be mistaken for live state.
            var scanned = 0
            while (lines.hasNext() && scanned < POWER_MUTE_SECTION_SCAN_LINES) {
                val candidate = lines.next()
                scanned++
                if (candidate.contains("current", ignoreCase = true)) {
                    return candidate.trim()
                }
            }
        }
        return null
    }

    /**
     * Latest Automotive power-mode line from the cached car_service dump.
     * Null off-platform, on dump failure, or when the section is absent.
     * Freshness is inherited from [dumpSnapshot]'s [DUMP_TTL_MS] cache.
     */
    @JvmStatic
    fun powerModeLine(): String? =
        if (isActive()) parsePowerModeLineFromText(dumpsysText()) else null

    fun buildSearchKey(propId: Int): String =
        "lastEvent:Property:0x${propId.toString(16)},"

    fun parseValueLine(line: String): Number? {
        val intMarker = "int32Values: ["
        val intStart = line.indexOf(intMarker)
        if (intStart >= 0) {
            val start = intStart + intMarker.length
            val end = line.indexOf(']', start)
            if (end > start) {
                line.substring(start, end).trim().toIntOrNull()?.let { return it }
            }
        }
        val floatMarker = "floatValues: ["
        val floatStart = line.indexOf(floatMarker)
        if (floatStart >= 0) {
            val start = floatStart + floatMarker.length
            val end = line.indexOf(']', start)
            if (end > start) {
                line.substring(start, end).trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun indexFor(text: String): PropertyIndex {
        propertyIndex.let { if (text === it.source) return it }
        synchronized(indexLock) {
            propertyIndex.let { if (text === it.source) return it }
            val marker = "lastEvent:Property:0x"
            val seen = HashSet<Int>()
            val values = HashMap<Int, Number>()
            val healthyValues = HashMap<Int, Number>()
            for (line in text.lineSequence()) {
                val markerAt = line.indexOf(marker)
                if (markerAt < 0) continue
                val start = markerAt + marker.length
                var end = start
                while (end < line.length && line[end] != ',') end++
                val id = line.substring(start, end).toIntOrNull(16) ?: continue
                if (!seen.add(id)) continue
                parseValueLine(line)?.let {
                    values[id] = it
                    val status = explicitStatus(line)
                    if (status == null || status == 0) healthyValues[id] = it
                }
            }
            return PropertyIndex(text, values, healthyValues).also {
                propertyIndex = it
            }
        }
    }

    private fun explicitStatus(line: String): Int? {
        val marker = "status:"
        val markerAt = line.indexOf(marker)
        if (markerAt < 0) return null
        var start = markerAt + marker.length
        while (start < line.length && line[start].isWhitespace()) start++
        var end = start
        if (end < line.length && (line[end] == '-' || line[end] == '+')) end++
        while (end < line.length && line[end].isDigit()) end++
        return line.substring(start, end).toIntOrNull() ?: Int.MIN_VALUE
    }

    fun findInText(text: String?, propId: Int): Number? =
        text?.let { indexFor(it).values[propId] }

    private fun findHealthyInText(text: String?, propId: Int): Number? {
        return text?.let { indexFor(it).healthyValues[propId] }
    }

    private fun heldInt(text: String?, propId: Int, min: Int, max: Int): Int {
        val live = findInText(text, propId)?.toInt()
        if (live != null && live in min..max) {
            lastKnown[propId] = live
            return live
        }
        return lastKnown[propId]?.toInt() ?: -1
    }

    private fun heldFloat(text: String?, propId: Int, min: Float, max: Float): Float {
        val live = findInText(text, propId)?.toFloat()
        if (live != null && live in min..max) {
            lastKnown[propId] = live
            return live
        }
        return lastKnown[propId]?.toFloat() ?: -1f
    }

    fun parseGearFromText(text: String?): Int {
        fun prnd(id: Int): Int? =
            findInText(text, id)?.toInt()?.takeIf { it in 1..6 }
        when (findInText(text, PROP_SHIFT_MODE)?.toInt()) {
            0, 1 -> return 1
            2 -> return 2
            3 -> return 3
            4 -> return 4
            5 -> return 5
            6 -> return 6
        }
        return prnd(PROP_GEAR_STATUS)
            ?: prnd(PROP_GEAR_BOX_AUTO_MODE_TYPE)
            ?: prnd(PROP_GEAR_BOX_AUTO_MODE_TYPE_TWO)
            ?: prnd(PROP_GEAR_R)
            ?: -1
    }

    fun gearValue(): Int {
        val parsed = parseGearFromText(dumpsysText())
        if (parsed in 1..6) lastGear = parsed
        return lastGear
    }

    fun currentGearValue(): Int = currentDynamics()?.gear ?: -1

    private fun batteryVoltage12v(text: String?): Float {
        val primary = findInText(text, PROP_BATTERY_VOLTAGE_SECOND)?.toFloat()
            ?.takeIf { it in 8f..16f }
        if (primary != null) {
            lastKnown[PROP_BATTERY_VOLTAGE_SECOND] = primary
            return primary
        }
        val fallback = findInText(text, PROP_BATTERY_VOLTAGE)?.toFloat()
            ?.takeIf { it in 8f..16f }
        if (fallback != null) {
            lastKnown[PROP_BATTERY_VOLTAGE] = fallback
            return fallback
        }
        return lastKnown[PROP_BATTERY_VOLTAGE_SECOND]?.toFloat()
            ?.takeIf { it in 8f..16f }
            ?: lastKnown[PROP_BATTERY_VOLTAGE]?.toFloat()
                ?.takeIf { it in 8f..16f }
            ?: -1f
    }

    fun batteryVoltage12v(): Float = batteryVoltage12v(dumpsysText())

    private fun resolvedSpeedKmh(text: String?): Int {
        val integer = findInText(text, PROP_SPEED_VALUE)?.toInt()
            ?.takeIf { it in 0..300 }
        if (integer != null) {
            lastKnown[PROP_SPEED_VALUE] = integer
            return integer
        }
        val floating = findInText(text, PROP_VEHICLE_SPEED)?.toFloat()
            ?.takeIf { it in 0f..300f }
        if (floating != null) {
            lastKnown[PROP_VEHICLE_SPEED] = floating
            return Math.round(floating)
        }
        return lastKnown[PROP_SPEED_VALUE]?.toInt()
            ?.takeIf { it in 0..300 }
            ?: lastKnown[PROP_VEHICLE_SPEED]?.toFloat()
                ?.takeIf { it in 0f..300f }
                ?.let { Math.round(it) }
            ?: -1
    }

    fun speedValue(): Int = heldInt(dumpsysText(), PROP_SPEED_VALUE, 0, 300)
    fun vehicleSpeedKmh(): Int {
        val value = heldFloat(dumpsysText(), PROP_VEHICLE_SPEED, 0f, 300f)
        return if (value >= 0f) Math.round(value) else -1
    }
    fun resolvedSpeedKmh(): Int = resolvedSpeedKmh(dumpsysText())
    fun accelPercent(): Int = heldInt(dumpsysText(), PROP_ACCEL_DEEPNESS, 0, 100)
    fun brakePercent(): Int = heldInt(dumpsysText(), PROP_BRAKE_DEEPNESS, 0, 100)
    fun brakePressed(): Int = heldInt(dumpsysText(), PROP_BRAKE_PEDAL_STATE, 0, 1)
    fun gunConnected(): Int = heldInt(dumpsysText(), PROP_CHARGING_GUN_STATE, 0, 1)
    fun turnLightState(): Int {
        val direct = DiLink5DirectTelemetry.turnLightState()
        if (direct != null || DiLink5DirectTelemetry.ownsTurnSignal()) {
            return direct ?: -1
        }
        return findInText(dumpsysText(), PROP_TURN_LIGHT_STATE)?.toInt()
            ?.takeIf { it in 0..7 } ?: -1
    }

    fun parseSocFromText(text: String?): Double {
        val live = findInText(text, PROP_REMAINING_BATTERY_POWER)?.toDouble()
        if (live != null && live in 0.0..100.0) return live
        val fallback = findInText(text, PROP_SOC_VALUE)?.toDouble()
        return fallback?.takeIf { it in 0.0..100.0 } ?: Double.NaN
    }

    private fun socPercentValue(text: String?): Double {
        val live = parseSocFromText(text)
        if (!live.isNaN()) {
            lastKnown[PROP_REMAINING_BATTERY_POWER] = live
            return live
        }
        return lastKnown[PROP_REMAINING_BATTERY_POWER]?.toDouble()
            ?.takeIf { it in 0.0..100.0 } ?: Double.NaN
    }

    fun socPercentValue(): Double = socPercentValue(dumpsysText())
    fun socPercent(): Int =
        socPercentValue().takeIf { !it.isNaN() }?.let { Math.round(it).toInt() } ?: -1

    private fun elecRangeKm(text: String?): Int =
        heldInt(text, PROP_ELEC_RANGE, 1, 999)

    fun elecRangeKm(): Int = elecRangeKm(dumpsysText())

    fun findPropertyIdByName(text: String?, name: String): Int? {
        if (text == null || name.isBlank()) return null
        val nameMarker = "Property name:$name"
        val idMarker = "Property:0x"
        for (line in text.lineSequence()) {
            if (!line.contains(nameMarker)) continue
            val markerAt = line.indexOf(idMarker)
            if (markerAt < 0) continue
            val start = markerAt + idMarker.length
            var end = start
            while (end < line.length && line[end].digitToIntOrNull(16) != null) end++
            if (end > start) return line.substring(start, end).toIntOrNull(16)
        }
        return null
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
        var previous = 0
        for (id in ids) {
            if (id == 0 || id == previous) continue
            previous = id
            val raw = findInText(text, id)?.toDouble() ?: continue
            val km = if (raw >= 1_000_000.0) raw / 10.0 else raw
            val rounded = Math.round(km).toInt()
            if (rounded in 1..999_999) return rounded
        }
        return -1
    }

    private fun totalMileageKm(text: String?): Int {
        val live = parseTotalMileageFromText(text)
        if (live > 0) {
            lastOdometerKm = live
            return live
        }
        return lastOdometerKm
    }

    fun totalMileageKm(): Int = totalMileageKm(dumpsysText())

    private fun doorsArray(text: String?): IntArray {
        val rf = heldInt(text, PROP_RIGHT_FRONT_LOCK, 1, 2)
        val lf = heldInt(text, PROP_LEFT_FRONT_LOCK, 1, 2)
        val rr = heldInt(text, PROP_RIGHT_REAR_LOCK, 1, 2)
        val lr = heldInt(text, PROP_LEFT_REAR_LOCK, 1, 2)
        val known = intArrayOf(rf, lf, rr, lr).filter { it != -1 }
        val overall = when {
            known.any { it == 1 } -> 1
            known.isNotEmpty() && known.all { it == 2 } -> 2
            else -> -1
        }
        return intArrayOf(rf, lf, rr, lr, -1, -1, overall)
    }

    fun doorsArray(): IntArray =
        if (isActive()) doorsArray(dumpsysText()) else IntArray(7) { -1 }

    private fun tyrePressuresRaw(text: String?): IntArray = intArrayOf(
        heldInt(text, PROP_TYRE_LEFT_FRONT, 1, 2_000),
        heldInt(text, PROP_TYRE_RIGHT_FRONT, 1, 2_000),
        heldInt(text, PROP_TYRE_LEFT_REAR, 1, 2_000),
        heldInt(text, PROP_TYRE_RIGHT_REAR, 1, 2_000)
    )

    fun tyrePressuresRaw(): IntArray =
        if (isActive()) tyrePressuresRaw(dumpsysText()) else IntArray(4) { -1 }

    private fun windowPercentsRaw(text: String?): IntArray = intArrayOf(
        heldInt(text, PROP_WINDOW_LEFT_FRONT, 0, 100),
        heldInt(text, PROP_WINDOW_RIGHT_FRONT, 0, 100),
        heldInt(text, PROP_WINDOW_LEFT_REAR, 0, 100),
        heldInt(text, PROP_WINDOW_RIGHT_REAR, 0, 100),
        heldInt(text, PROP_WINDOW_SUNROOF, 0, 100)
    )

    fun windowPercentsRaw(): IntArray =
        if (isActive()) windowPercentsRaw(dumpsysText()) else IntArray(5) { -1 }

    private fun climateAcOnRaw(text: String?): Int {
        val mode = heldInt(text, PROP_AC_COMPRESSOR_MODE, 0, 2)
        val fan = heldInt(text, PROP_AC_FAN_LEVEL, 0, 20)
        if (mode < 0 || fan < 0) return -1
        return if (mode == 1 && fan > 0) 1 else 0
    }

    fun climateAcOnRaw(): Int = climateAcOnRaw(dumpsysText())
    fun climateFanLevelRaw(): Int =
        heldInt(dumpsysText(), PROP_AC_FAN_LEVEL, 0, 20)

    private fun climateTempsRaw(text: String?): IntArray = intArrayOf(
        heldInt(text, PROP_AC_DRIVER_TEMP_SET, 10, 40),
        heldInt(text, PROP_AC_PASSENGER_TEMP_SET, 10, 40)
    )

    fun climateTempsRaw(): IntArray =
        if (isActive()) climateTempsRaw(dumpsysText()) else IntArray(2) { -1 }

    class ChargingObservation(
        @JvmField val gunState: Int,
        @JvmField val bmsState: Int,
        @JvmField val powerKw: Double,
        @JvmField val restHours: Int,
        @JvmField val restMinutes: Int,
        @JvmField val charging: Boolean
    ) {
        fun isAvailable(): Boolean =
            gunState != BydVehicleData.UNAVAILABLE ||
                bmsState != BydVehicleData.UNAVAILABLE
    }

    private fun liveSpeedKmh(text: String?): Int {
        val integer = findInText(text, PROP_SPEED_VALUE)?.toInt()
            ?.takeIf { it in 0..300 }
        if (integer != null) return integer
        val floating = findInText(text, PROP_VEHICLE_SPEED)?.toFloat()
            ?.takeIf { it in 0f..300f }
        return floating?.let { Math.round(it) } ?: -1
    }

    internal fun currentResolvedSpeedKmh(text: String?): Int =
        liveSpeedKmh(text)

    fun currentResolvedSpeedKmh(): Int =
        currentDynamics()?.speedKmh ?: -1

    internal fun currentAccelPercent(text: String?): Int =
        findInText(text, PROP_ACCEL_DEEPNESS)?.toInt()
            ?.takeIf { it in 0..100 } ?: -1

    fun currentAccelPercent(): Int =
        currentDynamics()?.accelPercent ?: -1

    internal fun currentBrakePercent(text: String?): Int =
        findInText(text, PROP_BRAKE_DEEPNESS)?.toInt()
            ?.takeIf { it in 0..100 } ?: -1

    fun currentBrakePercent(): Int =
        currentDynamics()?.brakePercent ?: -1

    class DynamicObservation(
        @JvmField val speedKmh: Int,
        @JvmField val accelPercent: Int,
        @JvmField val brakePercent: Int,
        @JvmField val gear: Int,
        @JvmField val ageMs: Long
    )

    internal fun dynamicObservation(
        text: String?,
        ageMs: Long
    ): DynamicObservation? {
        if (text == null || ageMs < 0L) return null
        return DynamicObservation(
            currentResolvedSpeedKmh(text),
            currentAccelPercent(text),
            currentBrakePercent(text),
            parseGearFromText(text),
            ageMs
        )
    }

    /**
     * One coherent dynamic tuple plus the age of its car-service dump.
     * Consumers must preserve this source age instead of re-stamping cache
     * reads as new vehicle observations.
     */
    fun currentDynamics(): DynamicObservation? {
        val direct = DiLink5DirectTelemetry.currentDynamics()
        if (direct != null || DiLink5DirectTelemetry.ownsDynamics()) {
            return direct
        }
        val snapshot = dumpSnapshot() ?: return null
        return dynamicObservation(
            snapshot.text,
            elapsedMs(System.nanoTime(), snapshot.observedAtNanos)
        )
    }

    fun parseChargingObservation(text: String?): ChargingObservation {
        val rawGun = findHealthyInText(text, PROP_CHARGING_GUN_STATE)?.toInt()
        val gunState = when (rawGun) {
            0 -> 1
            1 -> 2
            else -> BydVehicleData.UNAVAILABLE
        }
        val rawState = findHealthyInText(text, PROP_CHARGE_DISCHARGE_STATE)?.toInt()
        val gear = parseGearFromText(text)
        val speed = liveSpeedKmh(text)
        val driving = gear in 2..6 || speed > 0
        val notDriving = !driving &&
            (gear == GEAR_PARK || speed == 0 || (gear < 0 && speed < 0))
        val charging =
            rawState == RAW_CHARGE_STATE_ACTIVE && gunState == 2 && notDriving
        val bmsState = when {
            gunState == 1 ->
                ChargingStateData.CHARGING_BATTERY_STATE_IDLE
            charging ->
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
            rawState != null && rawState in 0..4 &&
                (rawState != RAW_CHARGE_STATE_ACTIVE || driving) ->
                ChargingStateData.CHARGING_BATTERY_STATE_IDLE
            else -> BydVehicleData.UNAVAILABLE
        }
        val power = findHealthyInText(text, PROP_CHARGING_POWER)?.toDouble()
            ?.takeIf { charging && it in 0.0..500.0 } ?: Double.NaN
        val hours = findHealthyInText(text, PROP_CHARGING_RESTTIME_HOUR)?.toInt()
            ?.takeIf { it in 0..254 } ?: BydVehicleData.UNAVAILABLE
        val minutes = findHealthyInText(text, PROP_CHARGING_RESTTIME_MIN)?.toInt()
            ?.takeIf { it in 0..254 } ?: BydVehicleData.UNAVAILABLE
        return ChargingObservation(
            gunState, bmsState, power, hours, minutes, charging
        )
    }

    @JvmStatic
    fun readChargingObservation(): ChargingObservation =
        if (isActive()) parseChargingObservation(dumpsysText())
        else ChargingObservation(
            BydVehicleData.UNAVAILABLE,
            BydVehicleData.UNAVAILABLE,
            Double.NaN,
            BydVehicleData.UNAVAILABLE,
            BydVehicleData.UNAVAILABLE,
            false
        )

    /**
     * Shared publication boundary. One call updates every supported DI5
     * property and leaves all non-DI5 builders untouched.
     */
    @JvmStatic
    fun applyTo(builder: BydVehicleData.Builder) {
        if (!isActive()) return
        val directDynamics = DiLink5DirectTelemetry.currentDynamics()
        val directTurn = DiLink5DirectTelemetry.turnLightState()
        dumpsysText()?.let {
            applyTextTo(
                builder,
                it,
                !DiLink5DirectTelemetry.ownsDynamics(),
                !DiLink5DirectTelemetry.ownsTurnSignal()
            )
        }
        directDynamics?.let {
            builder.speedKmh(it.speedKmh.toDouble())
            builder.accelPercent(it.accelPercent)
            builder.brakePercent(it.brakePercent)
            builder.gearMode(it.gear)
        }
        if (directTurn != null) applyTurn(builder, directTurn)
    }

    internal fun applyTextTo(builder: BydVehicleData.Builder, text: String) {
        applyTextTo(builder, text, true, true)
    }

    private fun applyTextTo(
        builder: BydVehicleData.Builder,
        text: String,
        includeDynamics: Boolean,
        includeTurn: Boolean
    ) {
        val soc = socPercentValue(text)
        if (!soc.isNaN()) builder.socPercent(soc)
        val voltage = batteryVoltage12v(text)
        if (voltage in 8f..16f) builder.voltage12v(voltage.toDouble())
        if (includeDynamics) {
            val speed = resolvedSpeedKmh(text)
            if (speed >= 0) builder.speedKmh(speed.toDouble())
            val gear = parseGearFromText(text)
            if (gear in 1..6) {
                lastGear = gear
                builder.gearMode(gear)
            }
        }
        val range = elecRangeKm(text)
        if (range > 0) builder.elecRangeKm(range)
        val odometer = totalMileageKm(text)
        if (odometer > 0) builder.totalMileageKm(odometer)

        if (includeDynamics) {
            val accel = heldInt(text, PROP_ACCEL_DEEPNESS, 0, 100)
            if (accel >= 0) builder.accelPercent(accel)
            val brake = heldInt(text, PROP_BRAKE_DEEPNESS, 0, 100)
            if (brake >= 0) builder.brakePercent(brake)
        }
        if (includeTurn) {
            val turn = findInText(text, PROP_TURN_LIGHT_STATE)?.toInt()
                ?.takeIf { it in 0..7 } ?: -1
            if (turn >= 0) applyTurn(builder, turn)
        }

        val locks = doorsArray(text)
        if (locks.any { it == 1 || it == 2 }) {
            val merged = builder.doorLockStatus?.copyOf(
                maxOf(7, builder.doorLockStatus.size)
            ) ?: IntArray(7) { BydVehicleData.UNAVAILABLE }
            for (i in locks.indices) if (locks[i] >= 0) merged[i] = locks[i]
            builder.doorLockStatus(merged)
        }

        val windows = windowPercentsRaw(text)
        if (windows.any { it >= 0 }) {
            val merged = builder.windowOpenPercent?.copyOf(
                maxOf(6, builder.windowOpenPercent.size)
            ) ?: IntArray(6) { BydVehicleData.UNAVAILABLE }
            for (i in windows.indices) if (windows[i] >= 0) merged[i] = windows[i]
            builder.windowOpenPercent(merged)
        }

        val tyres = tyrePressuresRaw(text)
        if (tyres.any { it > 0 }) {
            val kpa = builder.tyrePressure?.copyOf(4)
                ?: IntArray(4) { BydVehicleData.UNAVAILABLE }
            var updated = false
            for (i in tyres.indices) {
                val psi = tyres[i] / 10.0
                if (psi in 10.0..60.0) {
                    kpa[i] = Math.round(psi * 6.89476).toInt()
                    updated = true
                }
            }
            if (updated) builder.tyrePressure(kpa)
        }

        val ac = climateAcOnRaw(text)
        if (ac >= 0) builder.acStartState(ac)
        val fan = heldInt(text, PROP_AC_FAN_LEVEL, 0, 20)
        if (fan >= 0) builder.acFanLevel(fan)
        val temps = climateTempsRaw(text)
        if (temps[0] >= 0) builder.acSetpointDriver(temps[0])
        if (temps[1] >= 0) builder.acSetpointPassenger(temps[1])

    }

    private fun applyTurn(builder: BydVehicleData.Builder, turn: Int) {
        builder.leftTurnState(if (turn == 2 || turn == 3 || turn >= 6) 1 else 0)
        builder.rightTurnState(if (turn == 4 || turn == 5 || turn >= 6) 1 else 0)
        builder.hazard(turn >= 6)
        builder.markLightKnown(BydVehicleData.LIGHT_KNOWN_TURN_HAZARD)
    }

    @JvmStatic
    fun clearForTest() {
        lastKnown.clear()
        lastGear = -1
        lastOdometerKm = -1
        cachedDump = null
        lastDumpFailureAtNanos = 0L
        propertyIndex = PropertyIndex(null, emptyMap(), emptyMap())
        DiLink5DirectTelemetry.clearForTest()
    }
}
