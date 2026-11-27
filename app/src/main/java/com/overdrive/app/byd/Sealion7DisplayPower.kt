package com.overdrive.app.byd

import android.os.SystemClock
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.config.UnifiedConfigManager
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Demand-driven display fallback in the DiLink 5 daemon. The existing collector worker owns all I/O. */
internal class Sealion7DisplayPower(
    private val worker: Executor,
    private val eligible: () -> Boolean,
    private val readDump: () -> CarSvcTelemetry.CachedDump?,
    private val elapsedNanos: () -> Long,
) : UnifiedConfigManager.ConfigChangeListener {
    constructor(worker: Executor) : this(worker, ::eligibleSealion7, {
        // Peer writes are reloaded off the display thread before touching the property.
        UnifiedConfigManager.loadConfig()
        if (eligibleSealion7()) CarSvcTelemetry.readFreshDump() else null
    }, SystemClock::elapsedRealtimeNanos)

    private companion object {
        fun eligibleSealion7() = DiLink5Platform.isSelected() &&
            UnifiedConfigManager.cachedUserSelectedVehicleModelIs("sealion7")
    }

    private data class Reading(val kw: Int, val eventNs: Long, val observedNs: Long)

    private val maxAgeNs = TimeUnit.MILLISECONDS.toNanos(BydDataCollector.DILINK5_DYNAMIC_MAX_AGE_MS)
    private var reading: Reading? = null
    private var pending = false
    private var nextRefreshNs = 0L
    @Volatile private var invalidatedAtNs = -1L

    @Synchronized
    fun invalidate() {
        invalidatedAtNs = maxOf(elapsedNanos(), invalidatedAtNs + 1L)
        reading = null
        nextRefreshNs = 0L
    }

    override fun onConfigChanged(section: String, config: org.json.JSONObject) {
        if ((section == "all" || section == "vehicle") && !eligible()) invalidate()
    }

    @Synchronized
    fun resolve(packKw: Double): Double {
        if (!packKw.isNaN()) return packKw
        if (!eligible()) {
            if (reading != null) invalidate()
            return Double.NaN
        }
        val now = elapsedNanos()
        if (!pending && now >= nextRefreshNs) {
            pending = true
            nextRefreshNs = now + TimeUnit.MILLISECONDS.toNanos(CarSvcTelemetry.DUMP_TTL_MS)
            try {
                val boundary = invalidatedAtNs
                worker.execute { refresh(boundary) }
            } catch (_: RejectedExecutionException) {
                pending = false
            }
        }
        val sample = reading ?: return Double.NaN
        val age = now - sample.observedNs
        return if (age in 0..maxAgeNs) {
            sample.kw.toDouble()
        } else Double.NaN
    }

    private fun refresh(boundary: Long) {
        var result: Reading? = null
        try {
            if (boundary == invalidatedAtNs && eligible()) result = readDump()?.let(::parse)
        } finally {
            synchronized(this) {
                if (boundary == invalidatedAtNs && eligible()) {
                    val previous = reading
                    val now = elapsedNanos()
                    reading = result?.takeIf { sample ->
                        // The gauge reports changes. A new healthy dump can confirm a steady value.
                        val continuous = previous != null && sample.eventNs == previous.eventNs &&
                            sample.kw == previous.kw && sample.observedNs - previous.observedNs in 0..maxAgeNs
                        sample.eventNs > boundary && now - sample.observedNs in 0..maxAgeNs &&
                            (now - sample.eventNs in 0..maxAgeNs || continuous)
                    }
                    nextRefreshNs = (reading?.observedNs ?: now) +
                        TimeUnit.MILLISECONDS.toNanos(CarSvcTelemetry.DUMP_TTL_MS)
                }
                pending = false
            }
        }
    }

    private fun parse(dump: CarSvcTelemetry.CachedDump): Reading? {
        val row = dump.text.lineSequence().singleOrNull {
            it.contains("lastEvent:Property:0x21402402,") && it.contains("zone:0x0,")
        } ?: return null
        if (CarSvcTelemetry.explicitStatus(row) != 0) return null
        val timestamp = CarSvcTelemetry.parseTimestamp(row)?.takeIf { it in 1..elapsedNanos() } ?: return null
        val kw = CarSvcTelemetry.parseValueLine(row) as? Int ?: return null
        // Native integer kW, including light regen. The pack callback accepts up to 1000 V and ±2000 A.
        if (kw !in -2000..2000) return null
        return Reading(kw, timestamp, TimeUnit.MILLISECONDS.toNanos(dump.observedAtElapsedMs))
    }
}
