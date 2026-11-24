package com.overdrive.app.roadsense.detect

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Per-heartbeat signal summary for a tagged (DiLink 5) IMU stream: delivered rates, vertical
 * residual RMS, the loudest moments against the live threshold (with wall time, to match known
 * bumps), and how many samples came close. Enough to retune thresholds from the daemon log
 * alone. Single-threaded (IPC thread).
 */
class ImuSessionStats {
    private var accelN = 0
    private var accelFirstMs = 0L
    private var accelLastMs = 0L
    private var gyroN = 0
    private var gyroFirstMs = 0L
    private var gyroLastMs = 0L
    private var movingN = 0
    private var sumSq = 0.0
    // Top peaks at least PEAK_SEPARATION_MS apart: magnitude, speed, threshold, time.
    private val peakMag = FloatArray(TOP_PEAKS)
    private val peakSpeed = FloatArray(TOP_PEAKS)
    private val peakThr = FloatArray(TOP_PEAKS)
    private val peakTMs = LongArray(TOP_PEAKS)
    private var overHalf = 0
    private var overThreeQuarter = 0
    private var overThreshold = 0
    private var candidateLines = 0
    private var candidateSuppressed = 0

    fun onAccel(tMs: Long, aVert: Float, speedKmh: Float, threshold: Float) {
        if (accelN++ == 0) accelFirstMs = tMs
        accelLastMs = tMs
        if (speedKmh < MOVING_KMH || threshold <= 0f || aVert.isNaN()) return
        movingN++
        sumSq += aVert.toDouble() * aVert
        val mag = abs(aVert)
        recordPeak(mag, speedKmh, threshold, tMs)
        val ratio = mag / threshold
        if (ratio >= 0.5f) overHalf++
        if (ratio >= 0.75f) overThreeQuarter++
        if (ratio >= 1f) overThreshold++
    }

    private fun recordPeak(mag: Float, speedKmh: Float, threshold: Float, tMs: Long) {
        var slot = -1
        for (i in 0 until TOP_PEAKS) {
            if (peakMag[i] > 0f && abs(tMs - peakTMs[i]) < PEAK_SEPARATION_MS) { slot = i; break }
        }
        if (slot >= 0) {
            if (mag <= peakMag[slot]) return
        } else {
            slot = 0
            for (i in 1 until TOP_PEAKS) if (peakMag[i] < peakMag[slot]) slot = i
            if (mag <= peakMag[slot]) return
        }
        peakMag[slot] = mag; peakSpeed[slot] = speedKmh; peakThr[slot] = threshold; peakTMs[slot] = tMs
    }

    fun onGyro(tMs: Long) {
        if (gyroN++ == 0) gyroFirstMs = tMs
        gyroLastMs = tMs
    }

    /** True while this window's per-candidate log budget lasts. */
    fun allowCandidateLine(): Boolean {
        if (candidateLines < MAX_CANDIDATE_LINES) {
            candidateLines++
            return true
        }
        candidateSuppressed++
        return false
    }

    /** Summary since the previous call; starts a new window. */
    fun drain(periodMs: Float, calRms: Float, vehicleScale: Float): String {
        val rms = if (movingN > 0) sqrt(sumSq / movingN) else 0.0
        val order = (0 until TOP_PEAKS).filter { peakMag[it] > 0f }.sortedByDescending { peakMag[it] }
        val peaks = if (order.isEmpty()) "-" else order.joinToString(",") {
            String.format(Locale.ROOT, "%.2f@%.0fkm/h/thr%.2f@%s", peakMag[it], peakSpeed[it], peakThr[it], clock(peakTMs[it]))
        }
        val line = String.format(
            Locale.ROOT,
            "p=%.1fms a=%.1fHz g=%.1fHz moving=%d rms=%.3f peaks=%s " +
                ">=50%%thr=%d >=75%%thr=%d >=thr=%d calRms=%.3f scale=%.2f candLines=%d(+%d)",
            periodMs, hz(accelN, accelFirstMs, accelLastMs), hz(gyroN, gyroFirstMs, gyroLastMs),
            movingN, rms, peaks,
            overHalf, overThreeQuarter, overThreshold, calRms, vehicleScale,
            candidateLines, candidateSuppressed,
        )
        accelN = 0; gyroN = 0; movingN = 0; sumSq = 0.0
        peakMag.fill(0f); peakSpeed.fill(0f); peakThr.fill(0f); peakTMs.fill(0L)
        overHalf = 0; overThreeQuarter = 0; overThreshold = 0
        candidateLines = 0; candidateSuppressed = 0
        return line
    }

    private fun clock(tMs: Long): String = timeFormat.format(Date(tMs))

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    private fun hz(n: Int, firstMs: Long, lastMs: Long): Float =
        if (n < 2 || lastMs <= firstMs) 0f else (n - 1) * 1000f / (lastMs - firstMs)

    companion object {
        /** Below this the vertical channel is parking-lot noise, not road input. */
        const val MOVING_KMH = 5f
        const val MAX_CANDIDATE_LINES = 30
        private const val TOP_PEAKS = 3
        private const val PEAK_SEPARATION_MS = 1_000L
    }
}
