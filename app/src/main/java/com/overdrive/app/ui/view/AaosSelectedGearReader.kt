package com.overdrive.app.ui.view

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock

/** Read-only parked-check input. Calls and teardown run on the check's single I/O executor. */
internal class AaosSelectedGearReader(private val context: Context) : AutoCloseable {
    private var car: Car? = null
    private var properties: CarPropertyManager? = null

    /** Positive for Park, zero for any other gear, or null if the standard source is unavailable. */
    fun readParkSample(): Long? = try {
        val connected = car?.takeIf { it.isConnected } ?: run {
            close()
            Car.createCar(context)?.also { car = it }
        }
        val manager = properties ?: (connected?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)
            ?.also { properties = it }
        val started = SystemClock.elapsedRealtime()
        val value = manager?.getProperty(Integer::class.java, VehiclePropertyIds.GEAR_SELECTION, 0)
        value?.let {
            parkObservation(it.propertyId, it.areaId, it.status, it.value?.toInt(),
                it.timestamp, started, SystemClock.elapsedRealtimeNanos())
        }
    } catch (_: Exception) {
        close()
        null
    }

    override fun close() {
        properties = null
        try { car?.disconnect() } catch (_: Exception) {}
        car = null
    }

    companion object {
        internal fun parkObservation(
            property: Int, area: Int, status: Int, gear: Int?,
            valueNanos: Long, readStartedMillis: Long, nowNanos: Long
        ): Long? {
            if (property != VehiclePropertyIds.GEAR_SELECTION || area != 0 ||
                status != CarPropertyValue.STATUS_AVAILABLE || gear == null ||
                valueNanos <= 0 || valueNanos > nowNanos || readStartedMillis <= 0
            ) return null
            // GEAR_SELECTION changes only when the driver shifts. A synchronous property GET
            // confirms the current selection even if its last-change timestamp is minutes old.
            // Stamp at the start of this read so a stalled response never renews the UI's lease.
            return when (gear) {
                VehicleGear.GEAR_PARK -> readStartedMillis
                VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_DRIVE,
                VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
                VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH,
                VehicleGear.GEAR_SEVENTH, VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH -> 0L
                else -> null
            }
        }
    }
}
