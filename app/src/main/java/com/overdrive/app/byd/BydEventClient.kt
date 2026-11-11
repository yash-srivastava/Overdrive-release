package com.overdrive.app.byd

import com.overdrive.app.logging.LogManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client that connects to BydEventDaemon and dispatches events to listeners.
 *
 * Usage:
 *   BydEventClient.addListener(myListener)
 *   BydEventClient.connect()
 */
object BydEventClient {

    private const val TAG = "BydEventClient"
    private const val DAEMON_HOST = "127.0.0.1"
    private const val DAEMON_PORT = 19878
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val RECONNECT_DELAY_MS = 5_000L

    private val logger = LogManager.getInstance()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BydEventClient").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    // At most one connection-loop task may be queued or running. Rapid
    // disconnect/connect cycles must not accumulate stale loop tasks in the
    // single-thread executor's otherwise unbounded work queue.
    private val loopScheduled = AtomicBoolean(false)
    // Publish and retire the socket/writer as one state transition. The reader
    // keeps its own local socket reference so disconnect closes the connection
    // that the current loop is actually reading.
    private val connectionLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: PrintWriter? = null

    private val listeners = CopyOnWriteArrayList<EventListener>()

    private fun log(msg: String) {
        logger.info(TAG, msg)
    }

    interface EventListener {
        fun onConnected() {}
        fun onDisconnected() {}
        fun onPowerLevelChanged(level: Int, levelName: String) {}
        fun onRadarEvent(area: Int, areaName: String, state: Int, stateName: String) {}
        fun onDoorEvent(area: Int, open: Boolean) {}
        fun onWindowEvent(area: Int, open: Boolean) {}
        fun onAlarmEvent(active: Boolean) {}
        fun onBatteryVoltageLevelChanged(level: Int, levelName: String) {}
        fun onBatteryInfo(voltageLevel: Int, voltageLevelName: String, voltage: Double) {}
        fun onRawEvent(event: JSONObject) {}
    }

    fun addListener(listener: EventListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: EventListener) {
        listeners.remove(listener)
    }

    fun connect() {
        if (!running.compareAndSet(false, true)) {
            log("Already running")
            return
        }
        startConnectionLoopIfNeeded()
    }

    private fun startConnectionLoopIfNeeded() {
        if (!running.get() || !loopScheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    connectionLoop()
                } finally {
                    loopScheduled.set(false)
                    // connect() may have raced with the loop's final exit. If
                    // the desired state is running again, exactly one new loop
                    // is scheduled after the old one has relinquished ownership.
                    if (running.get()) startConnectionLoopIfNeeded()
                }
            }
        } catch (e: RejectedExecutionException) {
            loopScheduled.set(false)
            running.set(false)
            logger.error(TAG, "Unable to start connection loop", e)
        }
    }

    fun disconnect() {
        val activeSocket = synchronized(connectionLock) {
            running.set(false)
            writer = null
            socket.also { socket = null }
        }
        try {
            activeSocket?.close()
        } catch (e: Exception) {
            logger.warn(TAG, "Socket close failed: ${e.message}")
        }
    }

    fun isConnected(): Boolean {
        val activeSocket = socket ?: return false
        return activeSocket.isConnected && !activeSocket.isClosed
    }

    /** Send a command as JSON, returning false when disconnected or the write fails. */
    fun sendCommand(cmd: String): Boolean = synchronized(connectionLock) {
        val activeSocket = socket ?: return@synchronized false
        val activeWriter = writer ?: return@synchronized false
        if (!activeSocket.isConnected || activeSocket.isClosed) return@synchronized false
        try {
            // JSONObject correctly escapes quotes, backslashes and control
            // characters; PrintWriter otherwise hides I/O failures unless checked.
            activeWriter.println(JSONObject().put("cmd", cmd).toString())
            val success = !activeWriter.checkError()
            if (!success) {
                log("Send failed: writer reported an I/O error")
                try { activeSocket.close() } catch (_: Exception) {}
            }
            success
        } catch (e: Exception) {
            logger.warn(TAG, "Send failed: ${e.message}")
            try { activeSocket.close() } catch (_: Exception) {}
            false
        }
    }

    private fun connectionLoop() {
        log("Connection loop started")

        while (running.get()) {
            var candidate: Socket? = null
            try {
                log("Connecting to daemon at $DAEMON_HOST:$DAEMON_PORT...")
                val newSocket = Socket()
                candidate = newSocket
                // Do not let an absent daemon pin the only client worker in connect().
                newSocket.connect(
                    InetSocketAddress(DAEMON_HOST, DAEMON_PORT),
                    CONNECT_TIMEOUT_MS
                )
                newSocket.soTimeout = 0 // Event stream remains open until disconnect.
                val newWriter = PrintWriter(newSocket.getOutputStream(), true)
                val reader = BufferedReader(
                    InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8)
                )

                // disconnect() and publication are serialized. If disconnect
                // won while connect() was in progress, close this candidate
                // rather than publishing a socket after shutdown.
                val published = synchronized(connectionLock) {
                    if (!running.get()) {
                        false
                    } else {
                        socket = newSocket
                        writer = newWriter
                        true
                    }
                }
                if (!published) break

                log("Connected to BydEventDaemon!")
                notifyConnected()

                while (running.get()) {
                    val line = reader.readLine() ?: break
                    try {
                        dispatchEvent(JSONObject(line))
                    } catch (e: Exception) {
                        log("Failed to process event: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running.get()) log("Connection error: ${e.message}")
            } finally {
                val retired = candidate
                if (retired != null) {
                    synchronized(connectionLock) {
                        if (socket === retired) {
                            socket = null
                            writer = null
                        }
                    }
                    try { retired.close() } catch (_: Exception) {}
                }
                notifyDisconnected()
            }

            if (running.get()) {
                log("Reconnecting in ${RECONNECT_DELAY_MS}ms...")
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (!running.get()) break
                }
            }
        }

        log("Connection loop ended")
    }

    private fun dispatchEvent(event: JSONObject) {
        val type = event.optString("type", "")
        log("Event received: $type - $event")

        notifyListeners("raw event") { it.onRawEvent(event) }

        when (type) {
            "powerLevel" -> {
                val level = event.optInt("level", -1)
                val levelName = event.optString("levelName", "UNKNOWN")
                notifyListeners("power level") { it.onPowerLevelChanged(level, levelName) }
            }
            "radar" -> {
                val area = event.optInt("area", -1)
                val areaName = event.optString("areaName", "")
                val state = event.optInt("state", -1)
                val stateName = event.optString("stateName", "")
                notifyListeners("radar") { it.onRadarEvent(area, areaName, state, stateName) }
            }
            "door" -> {
                val area = event.optInt("area", -1)
                val open = event.optBoolean("open", false)
                notifyListeners("door") { it.onDoorEvent(area, open) }
            }
            "window" -> {
                val area = event.optInt("area", -1)
                val open = event.optBoolean("open", false)
                notifyListeners("window") { it.onWindowEvent(area, open) }
            }
            "alarm" -> {
                val active = event.optBoolean("active", false)
                notifyListeners("alarm") { it.onAlarmEvent(active) }
            }
            "state" -> {
                val level = event.optInt("powerLevel", -1)
                val levelName = event.optString("powerLevelName", "UNKNOWN")
                if (level >= 0) {
                    notifyListeners("power level") { it.onPowerLevelChanged(level, levelName) }
                }
                val batteryVoltageLevel = event.optInt("batteryVoltageLevel", -1)
                val batteryVoltageLevelName = event.optString("batteryVoltageLevelName", "UNKNOWN")
                val batteryVoltage = event.optDouble("batteryVoltage", 0.0)
                if (batteryVoltageLevel >= 0 || batteryVoltage > 0) {
                    notifyListeners("initial battery info") {
                        it.onBatteryInfo(
                            batteryVoltageLevel,
                            batteryVoltageLevelName,
                            batteryVoltage
                        )
                    }
                }
            }
            "batteryVoltage" -> {
                val level = event.optInt("level", -1)
                val levelName = event.optString("levelName", "UNKNOWN")
                notifyListeners("battery voltage") {
                    it.onBatteryVoltageLevelChanged(level, levelName)
                }
            }
            "batteryInfo" -> {
                val voltageLevel = event.optInt("voltageLevel", -1)
                val voltageLevelName = event.optString("voltageLevelName", "UNKNOWN")
                val voltage = event.optDouble("voltage", 0.0)
                notifyListeners("battery info") {
                    it.onBatteryInfo(voltageLevel, voltageLevelName, voltage)
                }
            }
        }
    }

    private fun notifyListeners(
        eventName: String,
        callback: (EventListener) -> Unit
    ) {
        listeners.forEach { listener ->
            try {
                callback(listener)
            } catch (e: Exception) {
                logger.error(TAG, "Listener failed during $eventName", e)
            }
        }
    }

    private fun notifyConnected() {
        notifyListeners("connect") { it.onConnected() }
    }

    private fun notifyDisconnected() {
        notifyListeners("disconnect") { it.onDisconnected() }
    }
}
