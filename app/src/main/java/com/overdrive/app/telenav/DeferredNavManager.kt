package com.overdrive.app.telenav

import android.os.SystemClock
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.monitor.GearMonitor
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deferred "navigate here". A phone / on-car "Navigate here" that arrives while the
 * car is off (ACC off) would otherwise be a silent no-op — guidance only engages
 * while Telenav is foregrounded, and the screen is off. So instead we store the
 * latest such target and, on the next ACC-on, offer it as a floating prompt.
 *
 * Also stops, while parked, any Telenav process that OverDrive started (launch or bind,
 * from either process); a Telenav the driver or the car started is left running.
 *
 * Runs entirely in the daemon process (byd_cam_daemon) and receives the already
 * deduplicated ACC edge from [com.overdrive.app.daemon.CameraDaemon]. Gear comes
 * from the daemon's [GearMonitor]; the overlay itself lives in the APP process
 * (Telenav bind + SYSTEM_ALERT_WINDOW), reached over 127.0.0.1:19882.
 */
object DeferredNavManager {

    private const val TAG = "DeferredNav"
    private const val TELENAV_PACKAGE = "com.telenav.app.arp"
    private const val SHOW_DELAY_MS = 5_000L        // let the launcher/Telenav settle after power-on
    private const val REVERSE_WAIT_MAX_MS = 30_000L // hold the prompt while reversing (rear cam)
    private const val REVERSE_POLL_MS = 1_000L
    private const val USE_WINDOW_MS = 30_000L       // outlasts one Telenav bind (20 s timeout)
    private const val OWNED_KEY = "telenavOwned"

    private val promptInFlight = AtomicBoolean(false)

    // Telenav is also the car's own navigation on some markets: only processes that appeared
    // while an OverDrive Telenav call was in progress are OverDrive's (pid -> start time).
    private val lock = Any()
    private val stopLock = Any()
    private val persistLock = Any()
    private var owned: MutableMap<Int, Long>? = null
    private var useBaseline: Map<Int, Proc>? = null // Telenav processes alive when the window opened
    private var useForeign = false                  // the driver's or the car's Telenav was already open
    private var useDeadline = 0L
    @Volatile private var parked: Boolean? = null

    private val bootId: String by lazy {
        try {
            BufferedReader(FileReader("/proc/sys/kernel/random/boot_id")).use { it.readLine() }?.trim() ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /** OverDrive is about to launch or bind Telenav. Must be called before that call. */
    @JvmStatic
    fun noteTelenavUse() {
        synchronized(lock) {
            useDeadline = SystemClock.elapsedRealtime() + USE_WINDOW_MS
            if (useBaseline != null) return
            val baseline = telenavProcesses() ?: run {
                Log.w(TAG, "cannot list processes - Telenav use not tracked")
                return
            }
            val o = ownedLocked()
            useForeign = baseline.any { (pid, p) -> p.main && o[pid] != p.start }
            useBaseline = baseline
        }
        Thread({ runUseWindow() }, "telenav-use").apply { isDaemon = true }.start()
    }

    /** ACC-on offers a deferred target; ACC-off stops a Telenav that OverDrive started. */
    @JvmStatic
    fun onAccStateChanged(accIsOff: Boolean) {
        parked = accIsOff
        if (!accIsOff) {
            onAccOn()
        } else {
            Thread({ stopOwnedTelenav("ACC-off") }, "telenav-acc-off").apply { isDaemon = true }.start()
        }
    }

    /** Store the latest target received while the car is off. Called by the endpoint. */
    @JvmStatic
    fun storePending(name: String?, lat: Double, lng: Double): Boolean {
        return try {
            TelenavActions.validateCoordinates(lat, lng)
            val stored = UnifiedConfigManager.setPendingNav(
                name ?: "", lat, lng, System.currentTimeMillis(),
            )
            if (stored) Log.i(TAG, "queued pending navigate: '$name' ($lat,$lng)")
            else Log.w(TAG, "failed to persist pending navigate")
            stored
        } catch (t: Throwable) {
            Log.w(TAG, "storePending failed: ${t.message}")
            false
        }
    }

    /** True while a target is queued and unexpired, i.e. the next ACC-on prompt will offer it. Read-only. */
    @JvmStatic
    fun hasPendingTarget(): Boolean = try {
        val d = UnifiedConfigManager.getDeferredNav()
        val receivedAt = d.optLong("receivedAt", 0L)
        d.optBoolean("pending", false) && receivedAt > 0L &&
            System.currentTimeMillis() - receivedAt <= UnifiedConfigManager.getDeferredNavExpiryHours() * 3_600_000L
    } catch (t: Throwable) {
        false
    }

    private fun onAccOn() {
        if (!promptInFlight.compareAndSet(false, true)) return
        // Off the ACC transition thread; this waits and does IPC.
        Thread({
            try {
                val d = UnifiedConfigManager.getDeferredNav()
                if (!d.optBoolean("pending", false)) return@Thread

                val receivedAt = d.optLong("receivedAt", 0L)
                val ageMs = System.currentTimeMillis() - receivedAt
                val maxMs = UnifiedConfigManager.getDeferredNavExpiryHours() * 3_600_000L
                if (receivedAt <= 0L || ageMs > maxMs) {
                    Log.i(TAG, "pending expired (age ${ageMs / 60_000}m > ${maxMs / 3_600_000}h) — clearing")
                    UnifiedConfigManager.clearPendingNav()
                    return@Thread
                }

                Thread.sleep(SHOW_DELAY_MS)

                // Don't pop over the reversing camera — wait until out of R (brief cap).
                var waited = 0L
                while (GearMonitor.getInstance().currentGear == GearMonitor.GEAR_R &&
                    waited < REVERSE_WAIT_MAX_MS
                ) {
                    Thread.sleep(REVERSE_POLL_MS)
                    waited += REVERSE_POLL_MS
                }
                if (GearMonitor.getInstance().currentGear == GearMonitor.GEAR_R) {
                    Log.i(TAG, "still in reverse after ${REVERSE_WAIT_MAX_MS / 1000}s — keeping pending")
                    return@Thread
                }

                val name = d.optString("name", "Shared location")
                val lat = d.optDouble("lat", Double.NaN)
                val lng = d.optDouble("lng", Double.NaN)
                try {
                    TelenavActions.validateCoordinates(lat, lng)
                } catch (invalid: IllegalArgumentException) {
                    UnifiedConfigManager.clearPendingNav()
                    return@Thread
                }

                val shown = sendShowPrompt(name, lat, lng)
                if (shown) {
                    UnifiedConfigManager.clearPendingNav()
                    Log.i(TAG, "ACC-on: prompt shown; pending cleared")
                } else {
                    Log.w(TAG, "ACC-on: prompt unavailable; keeping pending")
                }
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                Log.w(TAG, "onAccOn failed: ${t.message}")
            } finally {
                promptInFlight.set(false)
            }
        }, "deferrednav-accon").apply { isDaemon = true }.start()
    }

    /** Closes the use window after the last OverDrive call; a parked use is cleaned up here. */
    private fun runUseWindow() {
        var changed = false
        var stop = false
        while (true) {
            val remaining = synchronized(lock) {
                val left = useDeadline - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    telenavProcesses()?.let { changed = claimStartedLocked(it) }
                    useBaseline = null
                    stop = parked == true && ownedLocked().isNotEmpty()
                }
                left
            }
            if (remaining <= 0) break
            try {
                Thread.sleep(remaining)
            } catch (ignore: InterruptedException) {
            }
        }
        if (changed) persistOwned()
        if (stop) stopOwnedTelenav("parked Telenav use finished")
    }

    private fun stopOwnedTelenav(reason: String) {
        synchronized(stopLock) {
            val procs = telenavProcesses()
            if (procs == null) {
                Log.w(TAG, "$reason: cannot list processes - leaving Telenav")
                return
            }
            var changed = false
            val ours = synchronized(lock) {
                if (parked != true) return
                changed = claimStartedLocked(procs)
                val o = ownedLocked()
                if (o.entries.removeIf { procs[it.key]?.start != it.value }) changed = true
                o.isNotEmpty()
            }
            if (!ours) {
                Log.i(TAG, if (procs.isEmpty()) "$reason: Telenav not running"
                    else "$reason: Telenav was not started by OverDrive - leaving it running")
            } else if (forceStopTelenav(reason)) {
                synchronized(lock) { ownedLocked().clear() }
                changed = true
            }
            if (changed) persistOwned()
        }
    }

    private fun forceStopTelenav(reason: String): Boolean {
        return try {
            val process = ProcessBuilder("am", "force-stop", TELENAV_PACKAGE)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy()
                Log.w(TAG, "$reason: Telenav force-stop timed out")
                false
            } else if (process.exitValue() == 0) {
                Log.i(TAG, "$reason: Telenav stopped")
                true
            } else {
                Log.w(TAG, "$reason: Telenav force-stop exited ${process.exitValue()}")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$reason: Telenav force-stop failed: ${t.message}")
            false
        }
    }

    /** Adopts Telenav processes that were not alive when the open use window started. */
    private fun claimStartedLocked(procs: Map<Int, Proc>): Boolean {
        val baseline = useBaseline ?: return false
        if (useForeign) return false
        val o = ownedLocked()
        var changed = false
        for ((pid, p) in procs) {
            if (baseline[pid]?.start != p.start && o[pid] != p.start) {
                o[pid] = p.start
                changed = true
            }
        }
        if (changed) Log.i(TAG, "Telenav started by OverDrive: pids ${o.keys}")
        return changed
    }

    private fun ownedLocked(): MutableMap<Int, Long> {
        owned?.let { return it }
        val loaded = HashMap<Int, Long>()
        try {
            val saved = UnifiedConfigManager.getDeferredNav().optString(OWNED_KEY, "")
            val sep = saved.indexOf('|')
            if (sep >= 0 && saved.substring(0, sep) == bootId) {
                for (entry in saved.substring(sep + 1).split(',')) {
                    val colon = entry.indexOf(':')
                    if (colon <= 0) continue
                    val pid = entry.substring(0, colon).toIntOrNull() ?: continue
                    loaded[pid] = entry.substring(colon + 1).toLongOrNull() ?: continue
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loading Telenav ownership failed: ${t.message}")
        }
        owned = loaded
        return loaded
    }

    // Survives a daemon restart mid-drive; the boot id rejects pids from an earlier boot.
    private fun persistOwned() {
        synchronized(persistLock) {
            val encoded = synchronized(lock) {
                bootId + "|" + ownedLocked().entries.joinToString(",") { "${it.key}:${it.value}" }
            }
            if (!UnifiedConfigManager.updateValues("deferred_nav", mapOf(OWNED_KEY to encoded))) {
                Log.w(TAG, "failed to persist Telenav ownership")
            }
        }
    }

    private class Proc(val start: Long, val main: Boolean)

    /** Live Telenav processes (main and ":sub") by pid; null if /proc is unreadable. */
    private fun telenavProcesses(): Map<Int, Proc>? {
        val entries = File("/proc").list() ?: return null
        val found = HashMap<Int, Proc>()
        val buf = ByteArray(128)
        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            try {
                val n = FileInputStream("/proc/$pid/cmdline").use { it.read(buf) }
                if (n <= 0) continue
                var end = 0
                while (end < n && buf[end] != 0.toByte()) end++
                val name = String(buf, 0, end, Charsets.UTF_8)
                if (name != TELENAV_PACKAGE && !name.startsWith("$TELENAV_PACKAGE:")) continue
                val stat = BufferedReader(FileReader("/proc/$pid/stat")).use { it.readLine() } ?: continue
                // Field 22 (start time) is the 20th field after "pid (comm) ".
                val start = stat.substring(stat.lastIndexOf(')') + 2).split(' ')[19].toLong()
                found[pid] = Proc(start, name == TELENAV_PACKAGE)
            } catch (ignore: Exception) {
                // Exited during the scan.
            }
        }
        return found
    }

    /** Ask the APP process to draw the prompt overlay. Mirrors TelenavDebugApiHandler.forwardToApp. */
    private fun sendShowPrompt(name: String, lat: Double, lng: Double): Boolean {
        var socket: Socket? = null
        return try {
            val req = JSONObject()
                .put("op", "showNavPrompt")
                .put("name", name)
                .put("lat", lat)
                .put("lng", lng)
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", TelenavIpcServer.PORT), 2000)
            s.soTimeout = 8000
            socket = s
            PrintWriter(s.getOutputStream(), true).println(req.toString())
            val resp = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
            resp != null && resp.contains("\"success\":true")
        } catch (e: Exception) {
            Log.w(TAG, "sendShowPrompt failed: ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (ignore: Exception) {}
        }
    }
}
