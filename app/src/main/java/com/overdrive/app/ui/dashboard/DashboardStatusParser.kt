package com.overdrive.app.ui.dashboard

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

sealed interface DashboardStatusResult {
    data object Loading : DashboardStatusResult
    data class Available(val snapshot: DashboardVehicleSnapshot) : DashboardStatusResult
    data class Unavailable(val reason: Reason) : DashboardStatusResult

    enum class Reason {
        MALFORMED_RESPONSE,
        SERVICE_UNAVAILABLE,
        VEHICLE_DATA_UNAVAILABLE,
    }
}

data class DashboardVehicleSnapshot(
    val socPercent: Double?,
    val range: DashboardDistance?,
    val charging: DashboardChargingSnapshot?,
    val activeRecordingCameras: Int?,
    val rangeDetails: DashboardRangeDetails? = null,
    val gear: String? = null,
    val speedKmh: Double? = null,
    val isAccOn: Boolean? = null,
    val isRecording: Boolean? = null,
    val isGpuSurveillance: Boolean? = null,
)

/**
 * Personalized / per-leg range detail derived from the daemon's `range` block.
 *
 * [personalized] is the learned headline range (per-leg learned-else-HAL sum
 * on a PHEV); null means the estimator has not seen enough driving yet, in
 * which case the card keeps showing the plain HAL range. [evLeg]/[fuelLeg]
 * are the resolved per-leg figures for the PHEV breakdown row.
 */
data class DashboardRangeDetails(
    val personalized: DashboardDistance?,
    val isPhev: Boolean,
    val evLeg: DashboardDistance?,
    val fuelLeg: DashboardDistance?,
    val fuelPercent: Double?,
)

data class DashboardDistance(
    val value: Int,
    val unit: Unit,
) {
    enum class Unit(val label: String) {
        KILOMETRES("km"),
        MILES("mi"),
    }
}

data class DashboardChargingSnapshot(
    val charging: Boolean,
    val plugged: Boolean,
    val full: Boolean,
    val fault: Boolean,
    val stateName: String?,
    val powerKw: Double?,
    val powerEstimated: Boolean,
    val timeToFullMinutes: Int?,
    val sessionKwh: Double?,
    val sessionEnergyIncomplete: Boolean,
    val sessionEnergyEstimated: Boolean,
    val sessionEnergySource: String?,
)

/**
 * Strict parser for the daemon's existing /status contract.
 *
 * Invalid values are dropped rather than coerced. The dashboard can therefore
 * distinguish a real zero from data that was not published by the vehicle.
 */
object DashboardStatusParser {
    private const val KM_TO_MILES = 0.621371

    fun parse(
        body: String,
        personalizedRangeBody: String? = null,
    ): DashboardStatusResult {
        val root = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.MALFORMED_RESPONSE
            )
        }

        if (root.optString("status", "ok") != "ok") {
            return DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.SERVICE_UNAVAILABLE
            )
        }

        if (root.has("vehicleDataReady") && !root.optBoolean("vehicleDataReady", true)) {
            return DashboardStatusResult.Loading
        }

        val soc = root.optJSONObject("soc")
            ?.finiteDouble("percent")
            ?.takeIf { it in 0.0..100.0 }

        val rangeObject = root.optJSONObject("range")
        val rangeKm = rangeObject?.let { range ->
            range.finiteDouble("totalRangeKm")
                ?.takeIf { it >= 0.0 }
                ?: range.finiteDouble("elecRangeKm")?.takeIf { it >= 0.0 }
        }
        val distanceUnit = if (root.optString("distanceUnit").equals("mi", true)) {
            DashboardDistance.Unit.MILES
        } else {
            DashboardDistance.Unit.KILOMETRES
        }
        fun toDistance(km: Double): DashboardDistance {
            val displayValue = if (distanceUnit == DashboardDistance.Unit.MILES) {
                km * KM_TO_MILES
            } else {
                km
            }
            return DashboardDistance(displayValue.roundToInt(), distanceUnit)
        }
        val distance = rangeKm?.let(::toDistance)
        val learnedRange = parsePersonalizedRange(personalizedRangeBody)
        val rangeDetails = rangeObject?.toRangeDetails(::toDistance, learnedRange)

        val charging = root.optJSONObject("charging")?.toChargingSnapshot()
        val recordingCount = root.optJSONArray("recording")?.validItemCount()

        val recordingStatus = root.optJSONObject("recordingStatus")
        val gear = recordingStatus?.optString("gear")?.takeIf { it.isNotBlank() }
        val isRecording = recordingStatus?.optBoolean("isRecording", false) ?: false
        val isAccOn = root.optBoolean("acc", false)
        val isGpuSurveillance = root.optBoolean("gpuSurveillance", false)
        val gps = root.optJSONObject("gps")
        val speedKmh = gps?.finiteDouble("speed")?.let { it * 3.6 }?.takeIf { it >= 0.0 }

        val hasVehicleData = soc != null || distance != null || charging != null
        if (!hasVehicleData) {
            return DashboardStatusResult.Unavailable(
                DashboardStatusResult.Reason.VEHICLE_DATA_UNAVAILABLE
            )
        }

        return DashboardStatusResult.Available(
            DashboardVehicleSnapshot(
                socPercent = soc,
                range = distance,
                charging = charging,
                activeRecordingCameras = recordingCount,
                rangeDetails = rangeDetails,
                gear = gear,
                speedKmh = speedKmh,
                isAccOn = isAccOn,
                isRecording = isRecording,
                isGpuSurveillance = isGpuSurveillance,
            )
        )
    }

    /**
     * Personalized / per-leg detail. Per-leg precedence mirrors the daemon's
     * own /api/trips/range logic: a learned leg wins, otherwise that leg's
     * HAL value fills in — falling back per leg, never mixing a 0 into the
     * sum for an unseeded leg.
     */
    private fun JSONObject.toRangeDetails(
        toDistance: (Double) -> DashboardDistance,
        learnedRange: LearnedRange?,
    ): DashboardRangeDetails? {
        val halElecKm = finiteDouble("elecRangeKm")?.takeIf { it >= 0.0 }
        val halFuelKm = finiteDouble("fuelRangeKm")?.takeIf { it >= 0.0 }
        val isPhev = optBoolean("isPhev", false)
        val fuelPercent = finiteDouble("fuelPercent")?.takeIf { it in 0.0..100.0 }

        val embedded = optJSONObject("personalized")
        val learnedEvKm = learnedRange?.evKm
            ?: embedded?.finiteDouble("evKm")?.takeIf { it > 0.0 }
        val learnedFuelKm = learnedRange?.fuelKm
            ?: embedded?.finiteDouble("fuelKm")?.takeIf { it > 0.0 }

        val evLegKm = learnedEvKm ?: halElecKm
        val fuelLegKm = if (isPhev) learnedFuelKm ?: halFuelKm else null
        val hasLearnedLeg = learnedEvKm != null || learnedFuelKm != null

        val personalizedKm = when {
            !hasLearnedLeg -> null
            isPhev -> (evLegKm ?: 0.0) + (fuelLegKm ?: 0.0)
            else -> learnedEvKm
        }?.takeIf { it > 0.0 }

        if (personalizedKm == null && !isPhev) return null

        return DashboardRangeDetails(
            personalized = personalizedKm?.let(toDistance),
            isPhev = isPhev,
            evLeg = evLegKm?.let(toDistance),
            fuelLeg = fuelLegKm?.let(toDistance),
            fuelPercent = fuelPercent,
        )
    }

    /**
     * `/api/trips/range` is the daemon's learned-range contract. `/status`
     * intentionally stays lightweight and publishes only the vehicle's HAL
     * range, so the native dashboard supplies this optional second payload.
     */
    private fun parsePersonalizedRange(body: String?): LearnedRange? {
        if (body.isNullOrBlank()) return null
        val root = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return null
        }
        if (!root.optBoolean("success", false)) return null

        val evKm = root.optJSONObject("range")
            ?.finiteDouble("predictedRangeKm")
            ?.takeIf { it > 0.0 }
        val fuelKm = root.optJSONObject("fuelRange")
            ?.finiteDouble("predictedRangeKm")
            ?.takeIf { it > 0.0 }
        if (evKm == null && fuelKm == null) return null
        return LearnedRange(evKm = evKm, fuelKm = fuelKm)
    }

    private data class LearnedRange(
        val evKm: Double?,
        val fuelKm: Double?,
    )

    private fun JSONObject.toChargingSnapshot(): DashboardChargingSnapshot? {
        val charging = optBoolean("charging", false)
        val plugged = optBoolean("plugged", false)
        val full = optBoolean("full", false)
        val fault = optBoolean("fault", false) || optBoolean("isError", false)
        if (!charging && !plugged && !full && !fault) return null

        val powerIsEstimated = optBoolean("isEstimated", false)
        val powerSource = optString("powerSource", "").trim()
        val nominalPlaceholder = powerSource.equals("nominalPlaceholder", true)
        val power = (
            finiteDouble("powerKw")
                ?: finiteDouble("chargingPowerKW")
            )?.takeIf { it > 0.0 && !nominalPlaceholder }
        val eta = optPositiveInt("timeToFullMin")
        val session = finiteDouble("sessionKwh")?.takeIf { it > 0.0 }
        val sessionSource = optString("sessionEnergySource", "")
            .trim()
            .takeIf { session != null && it.isNotEmpty() && !it.equals("none", true) }
        val sessionIncomplete = session != null
            && optBoolean("sessionEnergyIncomplete", false)
        val sessionEstimated = session != null && (
            sessionIncomplete
                || optBoolean("sessionEnergyEstimated", false)
                || sessionSource == null
                || !sessionSource.equals("metered_counter", true)
            )
        val state = optString("stateName", "")
            .trim()
            .takeIf { it.isNotEmpty() && !it.equals("Unavailable", true) }

        return DashboardChargingSnapshot(
            charging = charging,
            plugged = plugged,
            full = full,
            fault = fault,
            stateName = state,
            powerKw = power,
            powerEstimated = power != null && powerIsEstimated,
            timeToFullMinutes = eta,
            sessionKwh = session,
            sessionEnergyIncomplete = sessionIncomplete,
            sessionEnergyEstimated = sessionEstimated,
            sessionEnergySource = sessionSource,
        )
    }

    private fun JSONObject.finiteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    private fun JSONObject.optPositiveInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key, -1).takeIf { it > 0 }
    }

    private fun JSONArray.validItemCount(): Int = length()
}
