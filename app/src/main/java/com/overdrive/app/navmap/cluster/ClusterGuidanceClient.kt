package com.overdrive.app.navmap.cluster

import android.os.SystemClock
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.navmap.nav.NavGuidanceEngine
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * App-side bounded latest-value publisher. HTTP work never runs on the map/notification thread.
 * Source-owned clears are ordered with updates, so a queued turn cannot resurrect a stopped trip.
 */
object ClusterGuidanceClient {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ClusterGuidanceClient").apply { isDaemon = true }
    }
    private val pending = LinkedHashMap<String, JSONObject>()
    private val liveSources = HashSet<String>()
    private var scheduled = false

    @JvmStatic
    fun enabled(): Boolean = try {
        val config = UnifiedConfigManager.loadConfig().optJSONObject("projection")
        config?.optBoolean("clusterGuidanceEnabled", false) == true ||
            config?.optBoolean("clusterGuidanceDi5StripEnabled", false) == true
    } catch (_: Throwable) { false }

    fun publishRoadSense(state: NavGuidanceEngine.GuidanceState, etaSeconds: Double) {
        if (!enabled()) return
        if (state.offRoute || state.arrived) {
            clear("roadsense")
            return
        }
        val maneuver = state.currentManeuver ?: run { clear("roadsense"); return }
        if (!state.distanceToManeuverM.isFinite() || !state.remainingDistanceM.isFinite() ||
            !etaSeconds.isFinite()) return
        publish(ClusterGuidanceFrame("roadsense", maneuver.type, meters(state.distanceToManeuverM),
            ClusterGuidanceText.roadLabel(maneuver.streetNames, maneuver.instruction, maneuver.type),
            meters(state.remainingDistanceM), meters(etaSeconds), maneuver.roundaboutExitCount))
    }

    @JvmStatic
    fun publish(frame: ClusterGuidanceFrame) {
        if (!enabled()) return
        enqueue(frame.source, JSONObject()
            .put("source", frame.source).put("sequence", SystemClock.elapsedRealtimeNanos())
            .put("maneuverType", frame.maneuverType).put("distanceMeters", frame.distanceMeters)
            .put("roadName", frame.roadName).put("remainingDistanceMeters", frame.remainingDistanceMeters)
            .put("remainingTimeSeconds", frame.remainingTimeSeconds).put("roundaboutExit", frame.roundaboutExit)
            .put("canIconOverride", frame.canIconOverride))
    }

    @JvmStatic
    fun clear(source: String) {
        if (!ClusterGuidanceFrame.validSource(source)) return
        enqueue(source, JSONObject().put("source", source).put("clear", true)
            .put("sequence", SystemClock.elapsedRealtimeNanos()))
    }

    private fun meters(value: Double): Int =
        value.coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()

    private fun enqueue(source: String, event: JSONObject) {
        synchronized(lock) {
            if (event.optBoolean("clear", false)) {
                if (!liveSources.remove(source)) return
            } else liveSources.add(source)
            pending[source] = event
            if (scheduled) return
            scheduled = true
            worker.execute(::drain)
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val entry = pending.entries.firstOrNull()
                if (entry == null) { scheduled = false; return }
                entry.value.also { pending.remove(entry.key) }
            }
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = DaemonHttpClient.open("/api/cluster/guidance", "POST", 1500, 2000)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(next.toString().toByteArray(Charsets.UTF_8)) }
                connection.inputStream.use { it.readBytes() }
            } catch (_: Exception) {
                // Old/offline daemon: next live frame retries; never replay an old turn later.
            } finally { connection?.disconnect() }
        }
    }
}
