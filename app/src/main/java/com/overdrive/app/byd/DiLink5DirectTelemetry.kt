package com.overdrive.app.byd

import android.os.SystemClock
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.logging.DaemonLogger

/**
 * Low-frequency Binder reads for DI5 signals that need sub-second freshness.
 *
 * Each signal group chooses one owner on its first read. A successful direct
 * capability probe keeps the direct path; otherwise the existing car-service
 * parser remains the owner for the process lifetime.
 */
object DiLink5DirectTelemetry {
    private val logger = DaemonLogger.getInstance("DiLink5DirectTelemetry")

    private const val TX_INT = 5
    private const val TX_FLOAT = 7
    private const val DIRECT_CACHE_MS = 1_000L

    private const val DEV_GEAR = 1011
    private const val DEV_SPEED = 1013
    private const val DEV_LIGHT = 1004

    private const val FID_SPEED = -1807745016
    private const val FID_ACCEL = 874512392
    private const val FID_BRAKE = 874512400
    private const val FID_GEAR = 555745336
    private const val FID_TURN = 950009900

    private enum class Owner { UNKNOWN, DIRECT, CAR_SERVICE }

    internal data class RawRead(val status: Int, val valueBits: Int)

    private val ownerLock = Any()
    @Volatile private var dynamicsOwner = Owner.UNKNOWN
    @Volatile private var turnOwner = Owner.UNKNOWN
    private class TimedDynamics(
        val observation: CarSvcTelemetry.DynamicObservation,
        val atMs: Long
    )
    @Volatile private var lastDynamics: TimedDynamics? = null
    @Volatile private var lastTurn = -1
    @Volatile private var lastTurnAtMs = 0L

    @JvmStatic
    fun currentDynamics(): CarSvcTelemetry.DynamicObservation? = readDynamics(byField = false)

    /**
     * [currentDynamics] for trip/overlay sampling: when the full tuple fails, each field is decoded
     * on its own (-1 when its read fails) instead of serving the cache. Null when no field decodes.
     */
    @JvmStatic
    fun currentDynamicsByField(): CarSvcTelemetry.DynamicObservation? = readDynamics(byField = true)

    /**
     * One uncached transaction-7 speed observation for a final actuator safety decision.
     * A failed read stays unknown; this path never serves the telemetry cache and never rounds
     * the OEM float before comparing it with a safety threshold.
     */
    @JvmStatic
    fun readFreshSpeedKmh(): Double {
        if (!DiLink5Platform.isSelected()) return Double.NaN
        return decodeFloat(read(TX_FLOAT, DEV_SPEED, FID_SPEED), 0f, 400f)
            ?.toDouble() ?: Double.NaN
    }

    private fun readDynamics(byField: Boolean): CarSvcTelemetry.DynamicObservation? {
        if (!DiLink5Platform.isSelected()) return null
        if (dynamicsOwner == Owner.CAR_SERVICE) return null

        val now = SystemClock.elapsedRealtime()
        val speed = read(TX_FLOAT, DEV_SPEED, FID_SPEED)
        val accel = read(TX_INT, DEV_SPEED, FID_ACCEL)
        val brake = read(TX_INT, DEV_SPEED, FID_BRAKE)
        val gear = read(TX_INT, DEV_GEAR, FID_GEAR)
        val observation = decodeDynamics(speed, accel, brake, gear)
        if (dynamicsOwner == Owner.UNKNOWN) {
            synchronized(ownerLock) {
                if (dynamicsOwner == Owner.UNKNOWN) {
                    dynamicsOwner =
                        if (observation != null) Owner.DIRECT else Owner.CAR_SERVICE
                    logger.info("DI5 dynamics owner: "
                            + if (dynamicsOwner == Owner.DIRECT) "autoservice" else "car_service")
                }
            }
        }
        if (dynamicsOwner != Owner.DIRECT) return null
        if (observation != null) {
            synchronized(ownerLock) {
                val previous = lastDynamics
                if (previous == null || now >= previous.atMs) {
                    lastDynamics = TimedDynamics(observation, now)
                }
            }
            return observation
        }
        if (byField) return decodeDynamicsByField(speed, accel, brake, gear)
        val cached = lastDynamics ?: return null
        // Aged after the failed reads, so a stalled read cannot make the cached value look newer.
        val age = SystemClock.elapsedRealtime() - cached.atMs
        return if (age in 0..DIRECT_CACHE_MS) {
            CarSvcTelemetry.DynamicObservation(
                cached.observation.speedKmh,
                cached.observation.accelPercent,
                cached.observation.brakePercent,
                cached.observation.gear,
                age
            )
        } else {
            null
        }
    }

    @JvmStatic
    fun ownsDynamics(): Boolean = dynamicsOwner == Owner.DIRECT

    @JvmStatic
    fun turnLightState(): Int? {
        if (!DiLink5Platform.isSelected()) return null
        if (turnOwner == Owner.CAR_SERVICE) return null

        val now = SystemClock.elapsedRealtime()
        val turn = decodeTurn(read(TX_INT, DEV_LIGHT, FID_TURN))
        if (turnOwner == Owner.UNKNOWN) {
            synchronized(ownerLock) {
                if (turnOwner == Owner.UNKNOWN) {
                    turnOwner = if (turn != null) Owner.DIRECT else Owner.CAR_SERVICE
                    logger.info("DI5 turn-signal owner: "
                            + if (turnOwner == Owner.DIRECT) "autoservice" else "car_service")
                }
            }
        }
        if (turnOwner != Owner.DIRECT) return null
        if (turn != null) {
            lastTurn = turn
            lastTurnAtMs = now
            return turn
        }
        val age = now - lastTurnAtMs
        return lastTurn.takeIf { it >= 0 && age in 0..DIRECT_CACHE_MS }
    }

    @JvmStatic
    fun ownsTurnSignal(): Boolean = turnOwner == Owner.DIRECT

    internal fun decodeDynamics(
        speed: RawRead?,
        accel: RawRead?,
        brake: RawRead?,
        gear: RawRead?
    ): CarSvcTelemetry.DynamicObservation? {
        val speedKmh = decodeFloat(speed, 0f, 400f)?.let { Math.round(it) } ?: return null
        val accelPercent = decodeInt(accel, 0, 100) ?: return null
        val brakePercent = decodeInt(brake, 0, 100) ?: return null
        val gearMode = decodeInt(gear, 1, 6) ?: return null
        return CarSvcTelemetry.DynamicObservation(
            speedKmh, accelPercent, brakePercent, gearMode, 0L
        )
    }

    internal fun decodeDynamicsByField(
        speed: RawRead?,
        accel: RawRead?,
        brake: RawRead?,
        gear: RawRead?
    ): CarSvcTelemetry.DynamicObservation? {
        val speedKmh = decodeFloat(speed, 0f, 400f)?.let { Math.round(it) } ?: -1
        val accelPercent = decodeInt(accel, 0, 100) ?: -1
        val brakePercent = decodeInt(brake, 0, 100) ?: -1
        val gearMode = decodeInt(gear, 1, 6) ?: -1
        if (speedKmh < 0 && accelPercent < 0 && brakePercent < 0 && gearMode < 0) return null
        return CarSvcTelemetry.DynamicObservation(
            speedKmh, accelPercent, brakePercent, gearMode, 0L
        )
    }

    internal fun decodeTurn(read: RawRead?): Int? =
        decodeInt(read, 0, 7)

    internal fun decodeInt(read: RawRead?, min: Int, max: Int): Int? {
        if (read == null || read.status != 0 || isSentinel(read.valueBits)) return null
        return read.valueBits.takeIf { it in min..max }
    }

    internal fun decodeFloat(read: RawRead?, min: Float, max: Float): Float? {
        if (read == null || read.status != 0 || isSentinel(read.valueBits)) return null
        return Float.fromBits(read.valueBits)
            .takeIf { it.isFinite() && it in min..max }
    }

    private fun read(tx: Int, device: Int, featureId: Int): RawRead? =
        AutoServiceBridge.readDiLink5(tx, device, featureId)?.let {
            RawRead(it.status, it.valueBits)
        }

    private fun isSentinel(value: Int): Boolean =
        value == 65_535 || value == 1_048_575 || value == -10_013 || value == -10_011

    @JvmStatic
    fun clearForTest() {
        synchronized(ownerLock) {
            dynamicsOwner = Owner.UNKNOWN
            turnOwner = Owner.UNKNOWN
            lastDynamics = null
            lastTurn = -1
            lastTurnAtMs = 0L
        }
    }
}
