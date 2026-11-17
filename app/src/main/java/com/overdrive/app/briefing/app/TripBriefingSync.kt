package com.overdrive.app.briefing.app

import android.content.Context
import android.util.Log
import com.overdrive.app.navmap.RecentSearchStore
import com.overdrive.app.navmap.SavedPlacesStore
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Background threads for Trip Briefing: the prompt's config reads, package queries and answers, and the sync. */
internal object TripBriefingIO {
    private const val TAG = "TripBriefing"

    private val prompt = Executors.newSingleThreadExecutor { r -> Thread(r, "TripBriefing").apply { isDaemon = true } }

    // its own thread, so a slow push never delays an offer
    val syncScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "TripBriefingSync").apply { isDaemon = true }
    }

    fun execute(task: () -> Unit) {
        prompt.execute { runCatching(task).onFailure { Log.w(TAG, "task failed: ${it.message}") } }
    }

    /** Blocking; the JSON body of a 2xx, else null. */
    fun getJson(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "GET", 1500, 4000)
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (t: Throwable) {
            Log.d(TAG, "GET $path failed: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Blocking; the JSON reply of a 2xx (waiting up to [readTimeoutMs] for it), else null. */
    fun postForJson(path: String, body: JSONObject, readTimeoutMs: Int): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "POST", 1500, readTimeoutMs)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (t: Throwable) {
            Log.d(TAG, "POST $path failed: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Blocking; true on a 2xx. */
    fun postJson(path: String, body: JSONObject): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = DaemonHttpClient.open(path, "POST", 1500, 4000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (t: Throwable) {
            Log.d(TAG, "POST $path failed: ${t.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * Pushes saved places (Home, Work, favourites) and recent search destinations to the daemon, which
 * can't read the app's prefs (§1). Debounced; a failed push is retried once after a minute.
 */
object TripBriefingSync {
    private const val DEBOUNCE_MS = 2_000L
    private const val RETRY_MS = 60_000L
    private const val MAX_RECENT = 50

    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null
    // reading the recents can prune and rewrite them, which would request again
    private val building = ThreadLocal<Boolean>()

    /** Call after any change to the saved places or recent searches. Any thread. */
    @JvmStatic
    fun request(context: Context) {
        if (building.get() == true) return
        val app = context.applicationContext ?: context
        schedule(app, DEBOUNCE_MS, retry = false)
    }

    private fun schedule(app: Context, delayMs: Long, retry: Boolean) {
        synchronized(lock) {
            pending?.cancel(false)
            pending = try {
                TripBriefingIO.syncScheduler.schedule({ push(app, retry) }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    private fun push(app: Context, retry: Boolean) {
        // Saved places stay in the app while Trip Briefing is off (off-thread: the config read can wait on its lock).
        val enabled = try {
            com.overdrive.app.config.UnifiedConfigManager.loadConfig().optJSONObject("tripBriefing")?.optBoolean("enabled", false) == true
        } catch (t: Throwable) {
            false
        }
        if (!enabled) return
        val body = try {
            building.set(true)
            payload(app)
        } catch (t: Throwable) {
            null
        } finally {
            building.set(false)
        }
        if (body == null) return
        if (!TripBriefingIO.postJson("/api/briefing/places-sync", body) && !retry) {
            schedule(app, RETRY_MS, retry = true)
        }
    }

    private fun payload(app: Context): JSONObject {
        val saved = JSONArray()
        for (p in SavedPlacesStore.getAll(app)) {
            val kind = when (p.kind) {
                SavedPlacesStore.KIND_HOME -> "home"
                SavedPlacesStore.KIND_WORK -> "work"
                else -> "fav"
            }
            place(p.result.label, p.result.lat, p.result.lng)?.let { saved.put(it.put("kind", kind)) }
        }
        val recent = JSONArray()
        for (r in RecentSearchStore.getAll(app).take(MAX_RECENT)) {
            place(r.label, r.lat, r.lng)?.let { recent.put(it) }
        }
        return JSONObject().put("saved", saved).put("recent", recent)
    }

    private fun place(label: String, lat: Double, lng: Double): JSONObject? {
        if (label.isBlank() || lat.isNaN() || lng.isNaN()) return null
        return JSONObject().put("label", label.trim()).put("lat", lat).put("lng", lng)
    }
}
