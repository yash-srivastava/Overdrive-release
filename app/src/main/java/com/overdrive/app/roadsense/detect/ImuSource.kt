package com.overdrive.app.roadsense.detect

import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.util.Log

/**
 * Selects the **real** inertial sensors on the head unit, not the stub.
 *
 * Findings F-001..F-004 (see dev/roadsense/05-FINDINGS.md): the BYD head unit
 * exposes TWO physical accelerometers and TWO gyros (Bosch smi130):
 *
 *   smi130-accel        type=accelerometer(1)  ← STUB: loops 5 frozen values, Z=0
 *   smi130-accel-iner   type=accelerometer(1)  ← REAL inertial sensor
 *   smi130-gyro         type=gyroscope(4)      ← stub
 *   smi130-gyro-iner    type=gyroscope(4)      ← real
 *
 * `SensorManager.getDefaultSensor(type)` returns the STUB, so we must enumerate
 * `getSensorList(type)` and pick the "-iner" instance by name. This is exactly
 * what the DIP app does (C2704d.java:679 hunts "-iner", skips "default").
 *
 * Fusion sensors (TYPE_LINEAR_ACCELERATION, TYPE_GRAVITY, rotation vector) are
 * derived from the *default* (stub) accel → poisoned (F-004). We never use them;
 * gravity removal is done ourselves in [GravityFrame].
 *
 * Pure selection logic — no listener registration here (that's RoadSenseService's
 * job). Kept separate so it's unit-testable and so the "did we get a real sensor"
 * decision lives in one place (feeds R-EXT-6 graceful fallback).
 */
object ImuSource {

    private const val TAG = "RoadSense/ImuSource"
    private const val INERTIAL_MARKER = "-iner"
    private const val STUB_NAME = "default"

    /** Result of resolving the real IMU. [accelerometer]/[gyroscope] are null
     *  when no usable sensor exists on this trim (→ feature self-disables). */
    data class Resolved(
        val accelerometer: Sensor?,
        val gyroscope: Sensor?,
        val accelIsInertial: Boolean,
        val gyroIsInertial: Boolean,
    ) {
        /** True only when we have a real (inertial) accelerometer — the minimum
         *  bar for detection. Gyro is used for rejection but is not strictly
         *  required to detect a vertical event. */
        val usableForDetection: Boolean get() = accelerometer != null && accelIsInertial
    }

    /**
     * Resolve the best accelerometer + gyroscope for detection.
     *
     * Selection order per type:
     *   1. an instance whose name contains "-iner" (the real inertial sensor),
     *   2. else the last instance whose name is not "default" (best-effort),
     *   3. else getDefaultSensor() (last resort — may be the stub).
     */
    fun resolve(sm: SensorManager): Resolved {
        logInventory(sm)
        val accel = pick(sm, Sensor.TYPE_ACCELEROMETER)
        val gyro = pick(sm, Sensor.TYPE_GYROSCOPE)
        val resolved = Resolved(
            accelerometer = accel,
            gyroscope = gyro,
            accelIsInertial = accel.isInertial(),
            gyroIsInertial = gyro.isInertial(),
        )
        Log.i(
            TAG,
            "resolved accel=${accel?.name ?: "none"} (inertial=${resolved.accelIsInertial}) " +
                "gyro=${gyro?.name ?: "none"} (inertial=${resolved.gyroIsInertial}) " +
                "usableForDetection=${resolved.usableForDetection}"
        )
        if (accel != null && !resolved.accelIsInertial) {
            // Loud warning: we fell back to a possibly-stub accelerometer. On the
            // BYD Seal this means readings may be the 5-value canned waveform.
            Log.w(TAG, "accelerometer is NOT the -iner sensor; readings may be a stub waveform")
        }
        return resolved
    }

    private fun pick(sm: SensorManager, type: Int): Sensor? {
        val list = sm.getSensorList(type)
        if (list.isEmpty()) return sm.getDefaultSensor(type)
        return list.firstOrNull { it.name.contains(INERTIAL_MARKER, ignoreCase = true) }
            ?: list.lastOrNull { !it.name.equals(STUB_NAME, ignoreCase = true) }
            ?: sm.getDefaultSensor(type)
    }

    private fun Sensor?.isInertial(): Boolean =
        this != null && name.contains(INERTIAL_MARKER, ignoreCase = true)

    /** The single real IMU of a DiLink 5 table (no "-iner" twin, no stub). */
    data class SingleImu(val accelerometer: Sensor, val gyroscope: Sensor?)

    /**
     * DiLink 5 only: the sole non-wake-up accelerometer (and gyro) when the table has no
     * "-iner" sensor. Null when the table doesn't have that shape, so callers keep [resolve].
     */
    fun resolveDiLink5(sm: SensorManager): SingleImu? {
        val accels = sm.getSensorList(Sensor.TYPE_ACCELEROMETER)
        if (accels.any { it.name.contains(INERTIAL_MARKER, ignoreCase = true) }) return null
        val accel = soleCandidate(accels) ?: return null
        return SingleImu(accel, soleCandidate(sm.getSensorList(Sensor.TYPE_GYROSCOPE)))
    }

    private fun soleCandidate(list: List<Sensor>): Sensor? =
        list.filter { !it.isWakeUpSensor && !it.name.equals(STUB_NAME, ignoreCase = true) }
            .singleOrNull()

    /** Motion-sensor table for the daemon log (rates, FIFO, direct-channel support). */
    fun inventory(sm: SensorManager): String =
        sm.getSensorList(Sensor.TYPE_ALL)
            .filter { it.type in INVENTORY_TYPES }
            .joinToString(prefix = "[", postfix = "]", separator = "; ") { s ->
                val memFile = s.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE)
                "t${s.type} '${s.name}' ${s.vendor} v${s.version} minDelay=${s.minDelay} " +
                    "maxDelay=${s.maxDelay} fifo=${s.fifoMaxEventCount} wake=${s.isWakeUpSensor} " +
                    "direct=${s.highestDirectReportRateLevel}${if (memFile) "/memfile" else ""}"
            }

    private val INVENTORY_TYPES = setOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
    )

    /** One-time inventory log so we can audit any trim's sensor table from logcat. */
    private fun logInventory(sm: SensorManager) {
        for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
            Log.d(
                TAG,
                "sensor type=${s.type} name=${s.name} vendor=${s.vendor} " +
                    "minDelay=${s.minDelay}us res=${s.resolution} maxRange=${s.maximumRange}"
            )
        }
    }
}
