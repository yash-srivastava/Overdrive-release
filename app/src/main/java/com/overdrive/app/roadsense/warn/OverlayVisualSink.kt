package com.overdrive.app.roadsense.warn

import com.overdrive.app.roadsense.detect.Severity

/**
 * Daemon-side [WarningCoordinator.VisualSink] (D-024): turns the coordinator's
 * "show this approach / clear it" calls into an [OverlayState] published to
 * [RoadSenseOverlayChannel] (daemon memory). The app-side overlays read it over the
 * loopback IPC and render — this class writes no UI, just state.
 *
 * Calibration level (the green/orange/red dot, R-OVL-1) isn't known to the
 * WarningCoordinator, so the controller supplies it via [calibrationLevelSupplier]
 * (typically `VehicleCalibrator.maturity`, optionally blended with confirmed-hazard
 * density later). A pending Calibration-Mode confirm is injected separately by the
 * controller via [setPendingConfirm] and rides along on the next write.
 *
 * Threading: published only on the daemon warn-tick thread. Every tick publishes (an
 * in-memory store), so the state's timestamp is the overlays' liveness signal; the
 * channel itself decides what counts as a visible change. `clock` injected for testability.
 */
class OverlayVisualSink(
    private val calibrationLevelSupplier: () -> Float,
    /** Route-coverage level of the CURRENT tile (0=new,1=seen,2=mapped) — the
     *  controller supplies it from RouteCoverage. Distinct from calibration. */
    private val coverageSupplier: () -> Int = { 0 },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WarningCoordinator.VisualSink {

    @Volatile private var pending: OverlayState.PendingConfirm? = null

    /** Controller sets/clears the Calibration-Mode confirm payload (D-025). */
    fun setPendingConfirm(p: OverlayState.PendingConfirm?) {
        pending = p
    }

    override fun showApproach(
        hazardId: String,
        rangeM: Double,
        relativeBearingDeg: Double,
        severity: Severity,
        typeOrdinal: Int,
        zoneCount: Int,
        zoneLengthM: Int,
        zoneRough: Boolean,
    ) {
        write(
            OverlayState(
                calibrationLevel = calibrationLevelSupplier(),
                coverage = coverageSupplier(),
                hazardAhead = true,
                nextHazardMeters = rangeM.toInt(),
                nextHazardRelBearingDeg = relativeBearingDeg.toInt(),
                nextHazardSeverity = severity.level,
                nextHazardType = typeOrdinal,
                zoneCount = zoneCount,
                zoneLengthM = zoneLengthM,
                zoneRough = zoneRough,
                pendingConfirm = pending,
                updatedMs = clock(),
            )
        )
    }

    override fun clearApproach() {
        write(OverlayState.idle(calibrationLevelSupplier(), coverageSupplier(), clock())
            .copy(pendingConfirm = pending))
    }

    /** Publish idle state on demand (controller calls this each warn-tick even
     *  when nothing's ahead, so the calibration dot + a fresh timestamp keep
     *  flowing and the app overlay knows the daemon is alive). */
    fun publishIdle() = clearApproach()

    private fun write(state: OverlayState) = RoadSenseOverlayChannel.publish(state)
}
