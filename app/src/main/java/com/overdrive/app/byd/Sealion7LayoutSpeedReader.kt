package com.overdrive.app.byd

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.launcher.dilink5.cluster.Sealion7HdmiProjection
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** Optional app-process speed input for one visible Custom Layouts cluster session. */
internal class Sealion7LayoutSpeedReader private constructor(private val context: Context?) : AutoCloseable {
    companion object {
        private const val TAG = "Sealion7LayoutSpeed"
        private const val ADAPTER = "com.ts.lib.caradapter."
        private const val POLL_DELAY_MS = 100L
        private const val MAX_SAMPLE_AGE_MS = 500L
        private const val SELECTION_CHECK_MS = 1_000L
        private const val CONFIG_READ_BUDGET_MS = 100L

        @JvmStatic
        fun start(context: Context): Sealion7LayoutSpeedReader {
            val app = context.applicationContext
            return Sealion7LayoutSpeedReader(app).apply {
                if (app != null && app.packageName == Application.getProcessName() &&
                    app.applicationInfo.uid == Process.myUid()
                ) {
                    thread.start()
                }
            }
        }

        internal fun eligible(mode: String?, modelId: Any?, modelSource: Any?): Boolean =
            DiLink5Platform.isSelected(mode, null) && Sealion7HdmiProjection.isUserSelected(modelId, modelSource)
    }

    internal class LatestReading {
        private data class Sample(val kmh: Double, val readStartedAtMs: Long)

        private var generation = 0L
        private var sample: Sample? = null

        @Synchronized
        fun generation(): Long = generation

        @Synchronized
        fun clear() {
            generation++
            sample = null
        }

        @Synchronized
        fun publish(rawKmh: Double, readStartedAtMs: Long, generation: Long) {
            if (generation != this.generation) return
            // Apply the same raw vehicle speed bounds as the DiLink 5 collector.
            sample = if (rawKmh in 0.0..300.0) Sample(rawKmh, readStartedAtMs) else null
        }

        @Synchronized
        fun valueOr(nowMs: Long, feedSpeedKmh: Double): Double {
            val current = sample ?: return feedSpeedKmh
            // The getter has no vehicle timestamp. Expire by call start so a delayed
            // response cannot renew a reading after the freshness window has passed.
            return if (nowMs - current.readStartedAtMs in 0..MAX_SAMPLE_AGE_MS) current.kmh else feedSpeedKmh
        }
    }

    private data class Connection(
        val manager: Any,
        val listener: Any,
        val removeListener: Method,
        val getAdapter: Method,
        val serviceKey: String,
        val readSpeed: Method,
    )

    private val latest = LatestReading()
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    @Volatile private var worker: Handler? = null
    private var activeMode: DiLink5Platform.ActiveModeSnapshot? = null
    private var connection: Connection? = null
    private var sensor: Any? = null
    private val poll = Runnable(::pollSpeed)
    private val checkSelection = Runnable(::recheckSelection)
    private val configListener = object : UnifiedConfigManager.ConfigChangeListener {
        override fun onConfigChanged(section: String, config: JSONObject) {
            when (section) {
                "all", "vehicle" -> {
                    val vehicle = if (section == "all") config.optJSONObject("vehicle") else config
                    if (!Sealion7HdmiProjection.isUserSelected(vehicle?.opt("modelId"), vehicle?.opt("modelSource"))) {
                        close()
                    }
                }
            }
        }
    }
    private val thread = object : HandlerThread(TAG) {
        override fun onLooperPrepared() {
            worker = Handler(looper)
            if (closed.get()) quitSafely() else open()
        }
    }

    /** Resolve speed on the render thread from the latest reading, with the normal feed as fallback. */
    fun speedKmhOr(feedSpeedKmh: Double): Double {
        if (closed.get() || !connected.get()) return feedSpeedKmh
        return latest.valueOr(SystemClock.elapsedRealtime(), feedSpeedKmh)
    }

    private fun readEligibleModel(mode: DiLink5Platform.ActiveModeSnapshot): Boolean = try {
        val deadline = SystemClock.elapsedRealtime() + CONFIG_READ_BUDGET_MS
        val vehicle = UnifiedConfigManager.readDurableConfigBefore(deadline)?.optJSONObject("vehicle")
        eligible(mode.mode, vehicle?.opt("modelId"), vehicle?.opt("modelSource"))
    } catch (_: Exception) {
        false
    }

    private fun recheckSelection() {
        if (closed.get()) return
        val mode = activeMode ?: return close()
        if (!mode.matches(DiLink5Platform.currentActiveModeSnapshot()) || !readEligibleModel(mode)) return close()
        worker?.postDelayed(checkSelection, SELECTION_CHECK_MS)
    }

    private fun open() {
        val app = checkNotNull(context)
        try {
            // The app's cached mode may predate the daemon's committed selection.
            val mode = DiLink5Platform.currentActiveModeSnapshot() ?: return close()
            if (!DiLink5Platform.isSelected(mode.mode, null)) return close()
            activeMode = mode
            UnifiedConfigManager.addListener(configListener)
            recheckSelection()
            if (closed.get()) return

            val loader = app.classLoader
            val managerType = loader.loadClass(ADAPTER + "CarAdapterManager")
            val listenerType = loader.loadClass(ADAPTER + "interfaces.OnCarAdapterConnectListener")
            val constants = loader.loadClass(ADAPTER + "common.constant.SettingsConstants")
            val sensorType = loader.loadClass(ADAPTER + "sensor.CarSensorAdapterManager")
            val manager = checkNotNull(managerType.getMethod("getInstance", Context::class.java).invoke(null, app))
            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> TAG
                    "onBinderStateChanged" -> {
                        onConnectionChanged(args?.firstOrNull() == true)
                        null
                    }
                    else -> null
                }
            }
            connection = Connection(
                manager,
                listener,
                managerType.getMethod("removeOnManagerConnChangedListener", listenerType),
                managerType.getMethod("getCarAdapterManager", String::class.java),
                constants.getField("SENSOR_SERVICE").get(null) as String,
                sensorType.getMethod("getSpeedValue"),
            )
            if (closed.get()) return
            managerType.getMethod("setOnManagerConnChangedListener", listenerType).invoke(manager, listener)
            if (closed.get()) return
            if (managerType.getMethod("isServiceBound").invoke(manager) == true) {
                onConnectionChanged(true)
            } else {
                managerType.getMethod("connect").invoke(manager)
            }
        } catch (error: Exception) {
            stopOnFailure(error)
        } catch (error: LinkageError) {
            stopOnFailure(error)
        }
    }

    private fun onConnectionChanged(bound: Boolean) {
        if (closed.get() || connected.getAndSet(bound) == bound) return
        if (!bound) latest.clear()
        worker?.post {
            if (closed.get()) return@post
            worker?.removeCallbacks(poll)
            sensor = null
            if (connected.get()) pollSpeed()
        }
    }

    private fun pollSpeed() {
        if (closed.get() || !connected.get()) return
        val source = connection ?: return
        val generation = latest.generation()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val adapter = sensor ?: source.getAdapter.invoke(source.manager, source.serviceKey).also { sensor = it }
            val value = (adapter?.let { source.readSpeed.invoke(it) } as? Number)?.toDouble() ?: Double.NaN
            if (!closed.get() && connected.get()) latest.publish(value, startedAt, generation)
        } catch (error: Exception) {
            val cause = (error as? InvocationTargetException)?.cause ?: error
            if (cause is SecurityException || cause is UnsupportedOperationException ||
                cause is IllegalAccessException || cause is LinkageError
            ) return stopOnFailure(cause)
            latest.clear()
            sensor = null
        } catch (error: LinkageError) {
            return stopOnFailure(error)
        }
        if (!closed.get() && connected.get()) worker?.postDelayed(poll, POLL_DELAY_MS)
    }

    private fun stopOnFailure(error: Throwable) {
        Log.w(TAG, "Speed adapter unavailable", (error as? InvocationTargetException)?.cause ?: error)
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        latest.clear()
        worker?.post {
            worker?.removeCallbacks(poll)
            worker?.removeCallbacks(checkSelection)
            UnifiedConfigManager.removeListener(configListener)
            try {
                // The manager singleton can also serve other features in this process.
                connection?.let { it.removeListener.invoke(it.manager, it.listener) }
            } catch (error: Exception) {
                Log.w(TAG, "Speed adapter listener removal failed", error)
            } catch (error: LinkageError) {
                Log.w(TAG, "Speed adapter listener removal failed", error)
            } finally {
                connection = null
                sensor = null
                thread.quitSafely()
            }
        }
    }
}
