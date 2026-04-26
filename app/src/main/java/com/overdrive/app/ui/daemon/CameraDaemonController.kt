package com.overdrive.app.ui.daemon

import android.content.Context
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Controller for the Camera Daemon (byd_cam_daemon).
 * 
 * Related processes that will be killed on stop:
 * - byd_cam_daemon (main daemon)
 * - ffmpeg (encoding/streaming)
 * - mediamtx (RTSP server)
 */
class CameraDaemonController(
    private val context: Context,
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {
    
    companion object {
        private const val TAG = "CameraDaemonCtrl"
        private const val DAEMON_TCP_PORT = 19876
        
        // All processes related to camera daemon that should be killed on stop
        private val RELATED_PROCESSES = listOf(
            "byd_cam_daemon",
            "ffmpeg",
            "mediamtx"
        )
    }
    
    override val type = DaemonType.CAMERA_DAEMON
    
    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting camera daemon...")
        
        val outputDir = context.getExternalFilesDir(null)?.absolutePath ?: "/data/local/tmp/overdrive"
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        
        adbLauncher.launchDaemon(
            outputDir = outputDir,
            nativeLibDir = nativeLibDir,
            callback = object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    callback.onStatusChanged(DaemonStatus.STARTING, message)
                }
                
                override fun onLaunched() {
                    callback.onStatusChanged(DaemonStatus.RUNNING, "Camera daemon running")
                }
                
                override fun onError(error: String) {
                    callback.onError(error)
                }
            }
        )
    }
    
    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping camera daemon...")
        
        // Try graceful shutdown via TCP command first
        Thread {
            sendShutdownCommand()
            Thread.sleep(500)
            
            // Kill the watchdog script FIRST so it can't respawn the daemon,
            // then kill the daemon and related processes. Without this, the
            // watchdog sees the daemon die and relaunches it immediately.
            val killCommands = buildString {
                append("pkill -9 -f 'start_cam_daemon' 2>/dev/null; ")
                append("rm -f /data/local/tmp/start_cam_daemon.sh 2>/dev/null; ")
                append("sleep 1; ")
                RELATED_PROCESSES.forEach { proc ->
                    append("pkill -9 -f '$proc' 2>/dev/null; ")
                    append("killall -9 $proc 2>/dev/null; ")
                }
                append("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null; ")
                append("echo done")
            }
            adbLauncher.executeShellCommand(
                killCommands,
                object : AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) {}
                    override fun onLaunched() {
                        callback.onStatusChanged(DaemonStatus.STOPPED, "Camera daemon stopped")
                    }
                    override fun onError(error: String) {
                        callback.onStatusChanged(DaemonStatus.STOPPED, "Camera daemon stopped")
                    }
                }
            )
        }.start()
    }
    
    private fun sendShutdownCommand(): Boolean {
        return try {
            android.util.Log.i("CameraDaemonCtrl", "Attempting TCP shutdown on port $DAEMON_TCP_PORT")
            Socket("127.0.0.1", DAEMON_TCP_PORT).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("{\"cmd\":\"shutdown\"}\n")
                writer.flush()
                
                val reader = socket.getInputStream().bufferedReader()
                val response = reader.readLine()
                android.util.Log.i("CameraDaemonCtrl", "Shutdown response: $response")
                
                response?.contains("\"status\":\"ok\"") == true
            }
        } catch (e: Exception) {
            android.util.Log.e("CameraDaemonCtrl", "TCP shutdown failed: ${e.message}")
            false
        }
    }
    
    override fun isRunning(callback: (Boolean) -> Unit) {
        adbLauncher.isDaemonRunning(callback)
    }
    
    override fun cleanup() {
        sendShutdownCommand()
        // Kill the watchdog first so the daemon stays dead.
        val killCommands = buildString {
            append("pkill -9 -f 'start_cam_daemon' 2>/dev/null; ")
            append("rm -f /data/local/tmp/start_cam_daemon.sh 2>/dev/null; ")
            append("sleep 1; ")
            RELATED_PROCESSES.forEach { proc ->
                append("pkill -9 -f '$proc' 2>/dev/null; ")
                append("killall -9 $proc 2>/dev/null; ")
            }
            append("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null; ")
            append("echo done")
        }
        adbLauncher.executeShellCommand(
            killCommands,
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
    }
    
    /**
     * Set the stream mode on the camera daemon.
     * @param mode "private" or "public"
     * @param callback Optional callback for result
     */
    fun setStreamMode(mode: String, callback: ((Boolean) -> Unit)? = null) {
        Thread {
            val success = sendTcpCommand("""{"cmd":"setStreamMode","mode":"$mode"}""")
            android.util.Log.i(TAG, "setStreamMode($mode) -> $success")
            callback?.invoke(success)
        }.start()
    }
    
    /**
     * Get the current stream mode from the camera daemon.
     */
    fun getStreamMode(callback: (String?) -> Unit) {
        Thread {
            val response = sendTcpCommandWithResponse("""{"cmd":"getStreamMode"}""")
            val mode = try {
                JSONObject(response ?: "{}").optString("mode", null)
            } catch (e: Exception) {
                null
            }
            callback(mode)
        }.start()
    }
    
    private fun sendTcpCommand(command: String): Boolean {
        return try {
            Socket("127.0.0.1", DAEMON_TCP_PORT).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("$command\n")
                writer.flush()
                
                val reader = socket.getInputStream().bufferedReader()
                val response = reader.readLine()
                android.util.Log.d(TAG, "TCP response: $response")
                
                response?.contains("\"status\":\"ok\"") == true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "TCP command failed: ${e.message}")
            false
        }
    }
    
    private fun sendTcpCommandWithResponse(command: String): String? {
        return try {
            Socket("127.0.0.1", DAEMON_TCP_PORT).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("$command\n")
                writer.flush()
                
                val reader = socket.getInputStream().bufferedReader()
                reader.readLine()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "TCP command failed: ${e.message}")
            null
        }
    }
}
