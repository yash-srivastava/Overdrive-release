package com.overdrive.app.roadsense.sidecar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.overdrive.app.BuildConfig
import java.util.Locale
import kotlin.math.sqrt

/**
 * DiLink 5 IMU acquisition for the RoadSense sidecar. Each FAST start probes the best source
 * (direct channel, else the 10 Hz listener), validates the sensor once per process, then
 * streams with the measured accel period so the daemon can adapt its constants. Every
 * decision goes to [Sink.onStatus] for the daemon log. All calls run on [handler]'s thread.
 */
internal class DiLink5ImuPump(
    private val context: Context,
    private val handler: Handler,
    private val listener: SensorEventListener,
    private val sink: Sink,
) {
    interface Sink {
        fun onSample(type: Int, timestampNs: Long, x: Float, y: Float, z: Float)
        fun onStatus(message: String)
        fun onUnsupported(reason: String)
        fun healthExtras(): String
    }

    private enum class Source { DIRECT, LISTENER }

    /** Accel period (ms) to tag on shipped batches; null until streaming. */
    var periodMs: Float? = null
        private set

    private var sm: SensorManager? = null
    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var source = Source.LISTENER
    private var direct: DiLink5DirectImu? = null
    private var verdicts: DirectVerdicts? = null
    private var directProbing = false
    private var gyroViaListener = false
    private var streaming = false
    private var streamSummary = ""
    private var phaseStartMs = 0L
    private var lastAccelAtMs = 0L
    private var lastAccelNs = 0L
    private var lastGyroNs = 0L
    private var lastReRegisterMs = 0L
    private var listenerRetries = 0
    private var lastPollMs = 0L
    private var maxPollGapMs = 0L
    private var windowStartMs = 0L
    private var rejected = 0L
    // Our own accel listener kept alive beside a direct channel: if it starves, other
    // listener clients (OEM navigation) are starving too.
    private var canaryCount = 0
    private var lastCanaryAtMs = 0L
    private val tsOffsetNs = LongArray(2)
    private val tsOffsetKnown = BooleanArray(2)
    private var lastRebaseLogMs = 0L
    private var shippedAccel = 0L
    private var shippedGyro = 0L
    private val accelRate = RateMeter()
    private val gyroRate = RateMeter()
    private val accelDecimator = Decimator()
    private val gyroDecimator = Decimator()
    private val sanity = Sanity()
    private val directSink = DirectRingParser.Sink { type, ts, x, y, z -> onRaw(type, ts, x, y, z) }
    private val pollTask = Runnable { pollDirect() }
    private val probeTask = Runnable { evaluateProbe() }
    private val tickTask = Runnable { tick() }

    fun start(sm: SensorManager, accel: Sensor, gyro: Sensor?) {
        stop()
        this.sm = sm
        this.accel = accel
        this.gyro = gyro
        lastAccelNs = 0L
        lastGyroNs = 0L
        tsOffsetKnown.fill(false)
        val store = DirectVerdicts(context, accel)
        verdicts = store
        val skip = if (DIRECT_CHANNEL_ENABLED) store.skipReason() else "off in this build"
        if (skip != null) sink.onStatus("direct: skipped ($skip)")
        if (skip != null || !openDirect(sm, accel, gyro, store)) registerListeners(sm, accel, gyro)
        beginProbe()
    }

    fun stop() {
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(probeTask)
        handler.removeCallbacks(tickTask)
        if (directProbing) verdicts?.cancelAttempt()
        directProbing = false
        direct?.close()
        direct = null
        sm?.unregisterListener(listener)
        gyroViaListener = false
        streaming = false
        periodMs = null
    }

    fun onListenerEvent(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val type = event.sensor.type
        if (source == Source.DIRECT) {
            if (type == Sensor.TYPE_ACCELEROMETER) {
                canaryCount++
                lastCanaryAtMs = SystemClock.elapsedRealtime()
                return
            }
            if (!(type == Sensor.TYPE_GYROSCOPE && gyroViaListener)) return
        }
        onRaw(type, event.timestamp, v[0], v[1], v[2])
    }

    private fun openDirect(sm: SensorManager, accel: Sensor, gyro: Sensor?, store: DirectVerdicts): Boolean {
        store.markAttempt()
        val opened = DiLink5DirectImu.open(sm, accel, gyro)
        val imu = opened.imu
        if (imu == null) {
            store.recordFail(opened.detail)
            sink.onStatus("direct: unavailable (${opened.detail}) -> listener")
            return false
        }
        direct = imu
        directProbing = true
        source = Source.DIRECT
        lastPollMs = 0L
        sink.onStatus("direct: opened ${opened.detail}")
        lastCanaryAtMs = SystemClock.elapsedRealtime()
        canaryCount = 0
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_FASTEST, handler)
        if (gyro != null && imu.gyroToken <= 0) {
            gyroViaListener = sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        handler.postDelayed(pollTask, POLL_MS)
        return true
    }

    private fun registerListeners(sm: SensorManager, accel: Sensor, gyro: Sensor?) {
        source = Source.LISTENER
        val a = sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_FASTEST, handler)
        val g = gyro?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
        if (!a || g == false) sink.onStatus("listener: registerListener accel=$a gyro=${g ?: "none"}")
    }

    private fun beginProbe() {
        streaming = false
        periodMs = null
        accelRate.reset()
        gyroRate.reset()
        rejected = 0L
        phaseStartMs = SystemClock.elapsedRealtime()
        lastAccelAtMs = phaseStartMs
        handler.removeCallbacks(probeTask)
        handler.removeCallbacks(tickTask)
        handler.postDelayed(probeTask, PROBE_MS)
        handler.postDelayed(tickTask, TICK_MS)
    }

    private fun pollDirect() {
        val imu = direct ?: return
        val now = SystemClock.elapsedRealtime()
        if (lastPollMs != 0L) maxPollGapMs = maxOf(maxPollGapMs, now - lastPollMs)
        lastPollMs = now
        try {
            imu.poll(directSink)
        } catch (t: Throwable) {
            val reason = "poll ${DiLink5DirectImu.describe(t)}"
            if (directProbing) verdicts?.recordFail(reason)
            fallBackToListener(reason)
            return
        }
        handler.postDelayed(pollTask, POLL_MS)
    }

    private fun onRaw(type: Int, rawTimestampNs: Long, x: Float, y: Float, z: Float) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val timestampNs = rebase(type, rawTimestampNs, nowNs)
        if (!plausible(type, timestampNs, nowNs, x, y, z)) {
            rejected++
            return
        }
        if (type == Sensor.TYPE_ACCELEROMETER) {
            if (timestampNs <= lastAccelNs) return
            lastAccelNs = timestampNs
            lastAccelAtMs = nowNs / 1_000_000L
            if (listenerRetries != 0) listenerRetries = 0
            accelRate.add(timestampNs)
            if (!sanityPassedInProcess) sanity.add(x, y, z)
            if (streaming && accelDecimator.add(timestampNs, x, y, z)) {
                shippedAccel++
                sink.onSample(type, accelDecimator.ts, accelDecimator.x, accelDecimator.y, accelDecimator.z)
            }
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            if (timestampNs <= lastGyroNs) return
            lastGyroNs = timestampNs
            gyroRate.add(timestampNs)
            if (streaming && gyroDecimator.add(timestampNs, x, y, z)) {
                shippedGyro++
                sink.onSample(type, gyroDecimator.ts, gyroDecimator.x, gyroDecimator.y, gyroDecimator.z)
            }
        }
    }

    /**
     * Timestamps must be on the boot clock. A HAL on another clock is re-based on arrival; the
     * offset is re-learned whenever a corrected timestamp falls outside the plausible window.
     */
    private fun rebase(type: Int, ts: Long, nowNs: Long): Long {
        val i = if (type == Sensor.TYPE_ACCELEROMETER) 0 else 1
        val corrected = ts + tsOffsetNs[i]
        val skew = nowNs - corrected
        if (tsOffsetKnown[i] && skew <= MAX_AGE_NS && skew >= -MAX_FUTURE_NS) return corrected
        val rawSkew = nowNs - ts
        val offset = if (rawSkew > MAX_AGE_NS || rawSkew < -MAX_FUTURE_NS) rawSkew else 0L
        val nowMs = nowNs / 1_000_000L
        if (offset != 0L && kotlin.math.abs(offset - tsOffsetNs[i]) > REBASE_LOG_DELTA_NS &&
            nowMs - lastRebaseLogMs > REBASE_LOG_INTERVAL_MS
        ) {
            lastRebaseLogMs = nowMs
            sink.onStatus("timestamps t$type are ${rawSkew / 1_000_000L}ms off the boot clock via $source -> re-based on arrival")
        }
        tsOffsetNs[i] = offset
        tsOffsetKnown[i] = true
        return ts + offset
    }

    private fun canaryHz(): Float {
        val expected = accel?.minDelay ?: 0
        return if (expected <= 0) -1f else canaryCount * 1000f / maxOf(1L, SystemClock.elapsedRealtime() - phaseStartMs)
    }

    private fun evaluateProbe() {
        val elapsed = SystemClock.elapsedRealtime() - phaseStartMs
        val aHz = accelRate.hz()
        val gHz = gyroRate.hz()
        val imu = direct
        if (source == Source.DIRECT && imu != null && directProbing) {
            directProbing = false
            val detail = String.format(
                Locale.ROOT,
                "a=%.1fHz g=%.1fHz%s canary=%.1fHz counter=%d torn=%d tokenMismatch=%d unattributed=%d rejected=%d pollGap=%dms",
                aHz, gHz, if (gyroViaListener) "(listener)" else "", canaryHz(), imu.maxCounter, imu.torn,
                imu.tokenMismatch, imu.unattributed, rejected, maxPollGapMs,
            )
            val listenerHz = accel?.minDelay?.takeIf { it > 0 }?.let { 1e6f / it } ?: 0f
            val starved = aHz >= MIN_DIRECT_HZ && listenerHz > 0f && canaryHz() >= 0f &&
                canaryHz() < listenerHz * CANARY_MIN_FRACTION
            if (aHz >= MIN_DIRECT_HZ && !starved) {
                verdicts?.recordOk(aHz)
                sink.onStatus("probe direct ok $detail")
            } else {
                verdicts?.recordFail("probe $detail")
                sink.onStatus("probe direct FAIL${if (starved) " (listener clients starved)" else ""} $detail -> listener")
                fallBackToListener(null)
                return
            }
        }
        if (aHz < MIN_STREAM_HZ) {
            if (elapsed < MAX_PROBE_MS) {
                handler.postDelayed(probeTask, PROBE_EXTEND_MS)
                return
            }
            sink.onUnsupported(
                "accel delivered ${accelRate.count} samples in ${elapsed}ms via $source (rejected=$rejected)"
            )
            return
        }
        if (!sanityPassedInProcess) {
            if (sanity.count < SANITY_MIN_SAMPLES && elapsed < MAX_PROBE_MS) {
                handler.postDelayed(probeTask, PROBE_EXTEND_MS)
                return
            }
            val pass = sanity.passes()
            sink.onStatus("sanity ${sanity.summary()} -> ${if (pass) "pass" else "FAIL"}")
            if (!pass) {
                sink.onUnsupported("sanity failed ${sanity.summary()}")
                return
            }
            sanityPassedInProcess = true
        }
        beginStreaming(aHz, gHz)
    }

    private fun beginStreaming(aHz: Float, gHz: Float) {
        val viaDirect = source == Source.DIRECT
        val aN = if (viaDirect) maxOf(1, Math.round(aHz / TARGET_HZ)) else 1
        val gN = if (viaDirect && !gyroViaListener) maxOf(1, Math.round(gHz / TARGET_HZ)) else 1
        accelDecimator.reset(aN)
        gyroDecimator.reset(gN)
        val period = Math.round(10_000f * aN / aHz) / 10f
        periodMs = period
        streaming = true
        streamSummary = String.format(
            Locale.ROOT, "src=%s%s a=%.1fHz/%d g=%.1fHz/%d p=%.1fms",
            source, if (gyroViaListener) "+gyroListener" else "", aHz, aN, gHz, gN, period,
        )
        sink.onStatus("stream $streamSummary")
        resetWindow(SystemClock.elapsedRealtime())
    }

    private fun fallBackToListener(reason: String?) {
        if (reason != null) sink.onStatus("direct: $reason -> listener")
        handler.removeCallbacks(pollTask)
        directProbing = false
        direct?.close()
        direct = null
        val manager = sm ?: return
        val accelSensor = accel ?: return
        manager.unregisterListener(listener)
        gyroViaListener = false
        if (!sanityPassedInProcess) sanity.reset()
        tsOffsetKnown.fill(false)
        registerListeners(manager, accelSensor, gyro)
        beginProbe()
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (streaming && source == Source.DIRECT && now - lastCanaryAtMs > CANARY_SILENCE_MS &&
            now - lastAccelAtMs <= SILENCE_MS
        ) {
            val reason = "listener clients starved (canary silent ${now - lastCanaryAtMs}ms)"
            verdicts?.recordFail(reason)
            fallBackToListener(reason)
            return
        }
        if (streaming) {
            val silentMs = now - lastAccelAtMs
            if (silentMs > SILENCE_MS) {
                if (source == Source.DIRECT) {
                    fallBackToListener("silent ${silentMs}ms")
                    return
                }
                if (now - lastReRegisterMs > (if (listenerRetries == 0) SILENCE_MS else LISTENER_RETRY_MS)) {
                    lastReRegisterMs = now
                    listenerRetries++
                    val manager = sm
                    val accelSensor = accel
                    if (manager != null && accelSensor != null) {
                        manager.unregisterListener(listener)
                        registerListeners(manager, accelSensor, gyro)
                    }
                    sink.onStatus("listener silent ${silentMs}ms -> re-registered")
                }
            }
            if (now - windowStartMs >= HEALTH_MS) emitHealth(now)
        }
        handler.postDelayed(tickTask, TICK_MS)
    }

    private fun emitHealth(now: Long) {
        val imu = direct
        val directPart = if (imu == null) "" else String.format(
            Locale.ROOT, " canary=%d torn=%d counter=%d pollGap=%dms", canaryCount, imu.torn, imu.maxCounter, maxPollGapMs,
        )
        sink.onStatus(
            String.format(
                Locale.ROOT, "health %s live a=%.1fHz g=%.1fHz shipped=%d/%d rejected=%d%s %s",
                streamSummary, accelRate.hz(), gyroRate.hz(), shippedAccel, shippedGyro, rejected,
                directPart, sink.healthExtras(),
            )
        )
        resetWindow(now)
    }

    private fun resetWindow(now: Long) {
        windowStartMs = now
        accelRate.reset()
        gyroRate.reset()
        shippedAccel = 0L
        shippedGyro = 0L
        rejected = 0L
        maxPollGapMs = 0L
        canaryCount = 0
    }

    private fun plausible(type: Int, ts: Long, nowNs: Long, x: Float, y: Float, z: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (ts > nowNs + MAX_FUTURE_NS || ts < nowNs - MAX_AGE_NS) return false
        val m2 = x * x + y * y + z * z
        return if (type == Sensor.TYPE_ACCELEROMETER) m2 in MIN_ACCEL_SQ..MAX_ACCEL_SQ else m2 <= MAX_GYRO_SQ
    }

    private class RateMeter {
        var count = 0
            private set
        private var firstNs = 0L
        private var lastNs = 0L

        fun reset() { count = 0; firstNs = 0L; lastNs = 0L }

        fun add(ts: Long) {
            if (count++ == 0) firstNs = ts
            lastNs = ts
        }

        fun hz(): Float = if (count < 2 || lastNs <= firstNs) 0f else (count - 1) * 1e9f / (lastNs - firstNs)
    }

    /** Box-average of [n] consecutive samples (anti-alias + downsample to ~100 Hz). */
    private class Decimator {
        private var n = 1
        private var k = 0
        private var sumTs = 0L
        private var sx = 0f
        private var sy = 0f
        private var sz = 0f
        var ts = 0L
        var x = 0f
        var y = 0f
        var z = 0f

        fun reset(factor: Int) {
            n = factor; k = 0; sumTs = 0L; sx = 0f; sy = 0f; sz = 0f
        }

        fun add(t: Long, ax: Float, ay: Float, az: Float): Boolean {
            if (n == 1) {
                ts = t; x = ax; y = ay; z = az
                return true
            }
            sumTs += t; sx += ax; sy += ay; sz += az
            if (++k < n) return false
            ts = sumTs / n; x = sx / n; y = sy / n; z = sz / n
            k = 0; sumTs = 0L; sx = 0f; sy = 0f; sz = 0f
            return true
        }
    }

    /** Rejects a stub (frozen, looping values) or dead sensor before anything ships. */
    private class Sanity {
        private val mags = FloatArray(SANITY_MAX_SAMPLES)
        private val keys = HashSet<Long>()
        var count = 0
            private set

        fun reset() { count = 0; keys.clear() }

        fun add(x: Float, y: Float, z: Float) {
            if (count >= SANITY_MAX_SAMPLES) return
            mags[count++] = sqrt(x * x + y * y + z * z)
            var h = x.toRawBits().toLong()
            h = h * 31 + y.toRawBits()
            h = h * 31 + z.toRawBits()
            keys.add(h)
        }

        private fun mean(): Float {
            if (count == 0) return 0f
            var s = 0.0
            for (i in 0 until count) s += mags[i]
            return (s / count).toFloat()
        }

        private fun sd(): Float {
            if (count < 2) return 0f
            val m = mean()
            var s = 0.0
            for (i in 0 until count) { val d = mags[i] - m; s += d * d }
            return sqrt(s / (count - 1)).toFloat()
        }

        fun passes(): Boolean =
            count >= SANITY_MIN_SAMPLES && mean() in MIN_GRAVITY..MAX_GRAVITY && keys.size * 2 >= count

        fun summary(): String =
            String.format(Locale.ROOT, "n=%d |a|=%.2f sd=%.3f distinct=%d", count, mean(), sd(), keys.size)
    }

    /** Per app version + firmware + sensor memory of whether the direct channel works. */
    private class DirectVerdicts(context: Context, accel: Sensor) {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val key = "direct." + BuildConfig.VERSION_CODE + "." +
            Integer.toHexString("${Build.FINGERPRINT}|${accel.name}|${accel.vendor}".hashCode())
        private val attemptsKey = "$key.attempts"

        fun skipReason(): String? {
            val verdict = prefs.getString(key, null)
            if (verdict != null && verdict.startsWith(FAIL)) {
                val body = verdict.removePrefix(FAIL)
                val atMs = body.substringBefore(':').toLongOrNull() ?: 0L
                val age = System.currentTimeMillis() - atMs
                if (age in 0 until FAIL_RETRY_MS) return "cached fail: ${body.substringAfter(':')}"
            }
            val attempts = prefs.getInt(attemptsKey, 0)
            if (attempts >= MAX_UNFINISHED_ATTEMPTS) {
                val reason = "previous $attempts probes never finished"
                recordFail(reason)
                return reason
            }
            return null
        }

        fun markAttempt() {
            prefs.edit().putInt(attemptsKey, prefs.getInt(attemptsKey, 0) + 1).commit()
        }

        fun cancelAttempt() {
            val attempts = prefs.getInt(attemptsKey, 0)
            if (attempts > 0) prefs.edit().putInt(attemptsKey, attempts - 1).apply()
        }

        fun recordOk(hz: Float) {
            prefs.edit().putString(key, "ok:" + String.format(Locale.ROOT, "%.1fHz", hz))
                .putInt(attemptsKey, 0).apply()
        }

        fun recordFail(detail: String) {
            prefs.edit().putString(key, FAIL + System.currentTimeMillis() + ":" + detail.take(200))
                .putInt(attemptsKey, 0).apply()
        }
    }

    companion object {
        @Volatile private var sanityPassedInProcess = false

        /** Opening a direct channel on the SMI230 HAL can take system_server down; keep the 10 Hz listener. */
        private const val DIRECT_CHANNEL_ENABLED = false

        private const val PROBE_MS = 1_500L
        private const val PROBE_EXTEND_MS = 1_000L
        private const val MAX_PROBE_MS = 6_000L
        private const val POLL_MS = 20L
        private const val TICK_MS = 1_000L
        private const val SILENCE_MS = 2_000L
        private const val LISTENER_RETRY_MS = 30_000L
        private const val HEALTH_MS = 60_000L
        /** The daemon's detector constants were tuned at 100 Hz. */
        private const val TARGET_HZ = 100f
        /** Below this the direct channel isn't worth it over the 10 Hz listener. */
        private const val MIN_DIRECT_HZ = 15f
        private const val MIN_STREAM_HZ = 2f
        /** Our listener must keep at least this share of its nominal rate beside a direct channel. */
        private const val CANARY_MIN_FRACTION = 0.5f
        private const val CANARY_SILENCE_MS = 3_000L
        private const val REBASE_LOG_DELTA_NS = 1_000_000_000L
        private const val REBASE_LOG_INTERVAL_MS = 30_000L
        private const val SANITY_MIN_SAMPLES = 15
        private const val SANITY_MAX_SAMPLES = 64
        private const val MIN_GRAVITY = 7.0f
        private const val MAX_GRAVITY = 12.5f
        private const val MIN_ACCEL_SQ = 0.25f
        private const val MAX_ACCEL_SQ = 6_400f
        private const val MAX_GYRO_SQ = 1_225f
        private const val MAX_AGE_NS = 5_000_000_000L
        private const val MAX_FUTURE_NS = 1_000_000_000L
        private const val PREFS = "roadsense_imu"
        private const val FAIL = "fail:"
        private const val FAIL_RETRY_MS = 24L * 60 * 60 * 1000
        private const val MAX_UNFINISHED_ATTEMPTS = 2
    }
}
