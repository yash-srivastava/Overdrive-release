package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles ADB shell command execution and connection management.
 */
class AdbShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AdbShellExecutor"
        private const val ADB_PORT = 5555
        private const val ADB_KEY_FILE = "adbkey"
        private const val ADB_PUB_KEY_FILE = "adbkey.pub"
        // Dadb defaults both values to 0 (infinite). A stale loopback transport
        // can therefore strand the single shell executor forever, leaving every
        // daemon switch disabled at STARTING. Keep connection setup short and
        // give legitimate longer shell commands (for example zrok's 30s probe)
        // room to finish while still guaranteeing recovery from a dead socket.
        private const val ADB_CONNECT_TIMEOUT_MS = 3_000
        private const val ADB_SOCKET_TIMEOUT_MS = 45_000
        
        @Volatile
        private var cachedKeyPair: AdbKeyPair? = null
        private val keyPairLock = Object()
        
        @Volatile
        private var sharedDadb: Dadb? = null
        private val sharedDadbLock = Object()
        
        // Auth state tracking
        private val isAuthPending = AtomicBoolean(false)
        private val wasAuthGranted = AtomicBoolean(false)
        private val pollingStarted = AtomicBoolean(false)
        
        @Volatile
        private var authCallback: AdbAuthCallback? = null
        
        // Dedicated polling executor (separate from command executor)
        private val pollingExecutor = Executors.newSingleThreadExecutor()

        // Process-wide lifeboat for commands whose OWNING executor was shut down mid-chain
        // (the bootManager→Activity handoff). Daemon thread: never shut down, so a
        // non-daemon one would hold the JVM alive after the last Activity finishes. Shares
        // the sharedDadb that cleanup() deliberately leaves open.
        private val fallbackExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "AdbShellFallback").apply { isDaemon = true }
        }

        // Process-wide tiebreaker for executeScript path/delimiter nonces.
        // Per-instance was insufficient: two AdbShellExecutor instances calling
        // executeScript at the same nanoTime with seq=1 each would produce
        // identical tmp paths and heredoc delimiters → the second's heredoc
        // write truncates the first's still-running script and the first's
        // trap-EXIT rm deletes the second's body. Companion-static eliminates
        // cross-instance collisions.
        private val scriptSeq = java.util.concurrent.atomic.AtomicLong(0)
        
        fun setAuthCallback(callback: AdbAuthCallback?) {
            authCallback = callback
        }
        
        fun checkAndClearAuthGranted(): Boolean {
            return wasAuthGranted.getAndSet(false)
        }
        
        fun isAuthPending(): Boolean = isAuthPending.get()

        fun enforceGlobalAdbSettings(context: Context) {
            try {
                val cr = context.contentResolver
                android.provider.Settings.Global.putInt(cr, "adb_enabled", 1)
                android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                android.provider.Settings.Global.putInt(cr, "adb_allowed_connection_time", 0)
                android.provider.Settings.Global.putInt(cr, "development_settings_enabled", 1)
                android.provider.Settings.Global.putInt(cr, "stay_on_while_plugged_in", 7)
            } catch (t: Throwable) {
                LogManager.getInstance().warn(TAG, "enforceGlobalAdbSettings failed: ${t.message}")
            }
        }
    }
    
    interface AdbAuthCallback {
        fun onAuthPending()
        fun onAuthGranted()
        fun onAuthFailed(error: String)
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    private val logger = LogManager.getInstance()
    
    interface ShellCallback {
        fun onSuccess(output: String)
        fun onError(error: String)
    }
    
    data class ShellResult(
        val exitCode: Int,
        val output: String
    )

    private val cmdSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Submit [task] to this instance's executor, falling back to the process-wide
     * [fallbackExecutor] if this one has been shut down. Every submit in this class goes
     * through here so that a rejection can never throw at the caller — `execute()` is
     * chained from inside onSuccess/onError on a worker thread, where an uncaught
     * RejectedExecutionException kills the process.
     */
    private fun submit(seq: Int, commandForLog: String, task: Runnable): Boolean {
        return when (ExecutorFallback.submit(task, executor, fallbackExecutor)) {
            ExecutorFallback.Outcome.OWNER -> true
            ExecutorFallback.Outcome.REROUTED -> {
                logger.warn(TAG, "adb#$seq REROUTED to the shared executor " +
                    "(owner's executor was shut down mid-chain): $commandForLog")
                true
            }
            ExecutorFallback.Outcome.REFUSED -> {
                logger.warn(TAG, "adb#$seq REJECTED (shared executor is down too): $commandForLog")
                false
            }
        }
    }

    fun execute(command: String, callback: ShellCallback) {
        executeInternal(command, command, callback)
    }

    /**
     * Execute [command] unchanged while keeping its credentials out of diagnostic logs.
     */
    fun executeSensitive(command: String, description: String, callback: ShellCallback) {
        executeInternal(command, "<sensitive:$description>", callback)
    }

    private fun executeInternal(
        command: String,
        commandForLog: String,
        callback: ShellCallback
    ) {
        val seq = cmdSeq.incrementAndGet()
        logger.debug(TAG, "adb#$seq SUBMIT [${Thread.currentThread().name}]: $commandForLog")
        // Return value ignored deliberately: on a double rejection we log and stop rather
        // than call callback.onError, because ServiceLauncher chains the next command from
        // inside onError — an error raised on the caller's thread would re-enter execute(),
        // be rejected again, and recurse. Unreachable anyway: fallbackExecutor never dies.
        submit(seq, commandForLog) {
            val t0 = System.currentTimeMillis()
            try {
                logger.debug(TAG, "adb#$seq RUN [${Thread.currentThread().name}]")
                val dadb = getOrCreateConnection()
                val tConn = System.currentTimeMillis() - t0
                val result = dadb.shell(command)
                logger.debug(TAG, "adb#$seq DONE conn=${tConn}ms total=${System.currentTimeMillis() - t0}ms exit=${result.exitCode}")

                if (result.exitCode == 0) {
                    callback.onSuccess(result.allOutput)
                } else {
                    callback.onError("Exit code ${result.exitCode}: ${result.allOutput}")
                }
            } catch (e: Exception) {
                logger.error(TAG, "adb#$seq FAILED after ${System.currentTimeMillis() - t0}ms: $commandForLog", e)
                callback.onError("Execution failed: ${e.message}")
            }
        }
    }

    fun executeSync(command: String): ShellResult {
        logger.debug(TAG, "Executing sync: $command")
        val dadb = getOrCreateConnection()
        val result = dadb.shell(command)
        return ShellResult(result.exitCode, result.allOutput)
    }

    /**
     * Run a script via a temp file rather than `sh -c "<script>"`. The
     * direct `sh -c` form puts the entire script in the calling shell's
     * argv[2]; toybox `pkill -f 'pattern'` then matches the calling shell
     * itself if the pattern appears literally in the script (which it
     * always does — "pkill -f 'cam_daemon'" contains "cam_daemon"), and
     * SIGKILL's the shell, dropping every command after the first pkill.
     *
     * Writing the script to a file first means the running shell's argv
     * is just `sh /data/local/tmp/<id>.sh` — no daemon pattern in argv —
     * so pkill cannot self-match. The script content is read from disk
     * by `sh`, not from argv.
     *
     * Use this for any multi-command shell payload that contains a
     * `pkill -f` whose pattern also appears as a literal in earlier
     * commands of the same payload. The temp file is cleaned up on
     * completion (best-effort).
     */
    fun executeScript(scriptBody: String, callback: ShellCallback) {
        // Routed through submit() like execute(). This path submitted directly, so a
        // rejection propagated uncaught — the same process-killing crash execute() has
        // guarded against all along, just never fixed here.
        val seq = cmdSeq.incrementAndGet()
        submit(seq, "<script:${scriptBody.length}B>") {
            // Per-call nonce = nanoTime + atomic counter. nanoTime alone
            // is non-decreasing (not strictly increasing) so two same-nano
            // calls can collide on emulators / older hardware. The counter
            // breaks ties. The nonce is used as both the path suffix AND
            // the heredoc delimiter — fixed delimiters would be a landmine
            // (any future script body containing the literal delimiter on
            // its own line would terminate the heredoc early).
            val nonce = "${System.nanoTime()}_${scriptSeq.incrementAndGet()}"
            val scriptPath = "/data/local/tmp/.adb_script_${nonce}.sh"
            val eofMarker = "__ADB_SCRIPT_EOF_${nonce}__"
            try {
                logger.debug(TAG, "Executing script via $scriptPath (${scriptBody.length} bytes)")
                val dadb = getOrCreateConnection()

                // Write via a heredoc — the heredoc body comes from stdin
                // not argv, so a `pkill -f cam_daemon` pattern inside the
                // body never appears in any shell's argv and self-match
                // is impossible. No chmod needed: `sh <path>` reads the
                // script regardless of x-bit, so the previous `chmod 755`
                // was dead code.
                val writeCmd = "cat > $scriptPath <<'$eofMarker'\n" +
                        scriptBody +
                        "\n$eofMarker"
                val writeResult = dadb.shell(writeCmd)
                if (writeResult.exitCode != 0) {
                    // Best-effort cleanup of any partial write
                    try { dadb.shell("rm -f $scriptPath 2>/dev/null") } catch (ignored: Exception) {}
                    callback.onError("script-write failed: ${writeResult.allOutput}")
                    return@submit
                }

                // Run with `trap 'rm -f path' EXIT` so the tmpfile is
                // removed on ANY shell exit — normal, signal, or abnormal.
                // The previous `sh path; RC=$?; rm -f path; exit $RC`
                // form leaked the tmpfile if the inner shell was killed
                // mid-run (rm never reached). trap-EXIT runs even on
                // SIGTERM/SIGHUP from dadb transport teardown.
                val runResult = dadb.shell(
                    "trap 'rm -f $scriptPath' EXIT; sh $scriptPath"
                )

                if (runResult.exitCode == 0) {
                    callback.onSuccess(runResult.allOutput)
                } else {
                    callback.onError("Exit code ${runResult.exitCode}: ${runResult.allOutput}")
                }
            } catch (e: Exception) {
                logger.error(TAG, "Script execution failed", e)
                callback.onError("Execution failed: ${e.message}")
                // Best-effort cleanup if the connection is still alive.
                // `getOrCreateConnection()` may itself throw if the
                // failure was a connection drop — swallow.
                try { getOrCreateConnection().shell("rm -f $scriptPath 2>/dev/null") } catch (ignored: Exception) {}
            }
        }
    }
    
    fun checkProcessRunning(processName: String): Int? {
        return try {
            val dadb = getOrCreateConnection()
            val result = dadb.shell("pgrep -f '$processName'")
            
            if (result.exitCode == 0 && result.allOutput.trim().isNotEmpty()) {
                result.allOutput.trim().lines().firstOrNull()?.toIntOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to check process: $processName", e)
            null
        }
    }
    
    fun killProcess(processName: String): Boolean {
        return try {
            val dadb = getOrCreateConnection()
            // ps+awk+kill instead of pkill -f. dadb.shell runs `shell:cmd`
            // over ADB which is equivalent to `sh -c "<cmd>"`. With
            // pkill -f, the wrapper sh's argv contains the literal
            // processName, so toybox pkill -f matches it and SIGKILLs
            // the calling shell — exit code 137 (returned as
            // result.exitCode), so killProcess returns false even
            // when the target was actually killed.
            val cmd = "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F '$processName' | grep -v grep " +
                "| awk '{print \$1}' | while read pid; do " +
                "if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done; " +
                "echo done"
            val result = dadb.shell(cmd)
            result.exitCode == 0
        } catch (e: Exception) {
            logger.error(TAG, "Failed to kill process: $processName", e)
            false
        }
    }
    
    fun getOrCreateConnection(): Dadb {
        // sharedDadbLock is PROCESS-WIDE and the liveness probe below is an un-timed
        // blocking round-trip, so a hang on a half-dead connection stalls every
        // AdbShellExecutor behind this monitor — and a thread blocked on a monitor reports
        // as S (sleeping) in /proc, indistinguishable from idle. Time it explicitly.
        val tWait = System.currentTimeMillis()
        synchronized(sharedDadbLock) {
            val waited = System.currentTimeMillis() - tWait
            if (waited > 1000) logger.warn(TAG, "getOrCreateConnection: waited ${waited}ms for sharedDadbLock")
            var dadb = sharedDadb
            if (dadb != null) {
                try {
                    val tProbe = System.currentTimeMillis()
                    val result = dadb.shell("echo ok")
                    val probeMs = System.currentTimeMillis() - tProbe
                    if (probeMs > 1000) logger.warn(TAG, "getOrCreateConnection: liveness probe took ${probeMs}ms (holding the shared lock)")
                    if (result.exitCode == 0) {
                        if (isAuthPending.getAndSet(false)) {
                            wasAuthGranted.set(true)
                            logger.info(TAG, "ADB auth granted! Connection established.")
                            authCallback?.onAuthGranted()
                        }
                        return dadb
                    }
                } catch (e: Exception) {
                    logger.debug(TAG, "Existing ADB connection dead, reconnecting...")
                }
                try { dadb.close() } catch (e: Exception) {}
                sharedDadb = null
            }
            
            // Check if ADB port is even listening before trying to connect
            if (!isAdbPortOpen()) {
                logger.warn(TAG, "ADB port $ADB_PORT not open - attempting self-healing via Settings.Global...")
                enforceGlobalAdbSettings(context)
                try { Thread.sleep(1000) } catch (ignored: InterruptedException) {}
                if (!isAdbPortOpen()) {
                    logger.warn(TAG, "ADB port $ADB_PORT still not open after self-healing attempt")
                    throw Exception("ADB port not open")
                }
            }
            
            val adbKeyPair = getOrCreateAdbKeyPair()
            logger.info(TAG, "Attempting ADB connection...")
            
            // Start polling BEFORE we try to connect (on separate executor)
            if (!isAuthPending.get() && pollingStarted.compareAndSet(false, true)) {
                isAuthPending.set(true)
                authCallback?.onAuthPending()
                startAuthPollingInternal(adbKeyPair)
            }
            
            // Try quick connection with timeout wrapper
            dadb = tryConnectWithTimeout(adbKeyPair, 2000)
            
            if (dadb != null) {
                sharedDadb = dadb
                isAuthPending.set(false)
                wasAuthGranted.set(true)
                pollingStarted.set(false)
                logger.info(TAG, "ADB connection established successfully")
                authCallback?.onAuthGranted()
                return dadb
            } else {
                throw Exception("ADB auth pending - waiting for user to accept")
            }
        }
    }
    
    /**
     * Check if ADB port is open (quick TCP check).
     */
    private fun isAdbPortOpen(): Boolean {
        return try {
            Socket("127.0.0.1", ADB_PORT).use { true }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Try to connect with a timeout. Returns null if times out (auth pending).
     *
     * NOTE: java.net.Socket I/O is NOT interruptible — `Thread.interrupt()`
     * does not unblock a thread blocked inside connect/read. Dadb 1.2.8 does,
     * however, expose native connect + socket timeouts. Every connection below
     * uses those bounds, so a probe thread that outlives this quick wrapper can
     * survive for at most [ADB_SOCKET_TIMEOUT_MS], never indefinitely.
     *
     * Mitigation: mark the orphan thread as daemon + named so it
     *   (a) doesn't hold the JVM alive (Android process death is
     *       unaffected, but JVM-shutdown semantics still matter for
     *       Robolectric / instrumentation tests),
     *   (b) shows up in /proc/self/status and `dumpsys meminfo` with a
     *       distinct name, so leaks are visible if they ever happen,
     *   (c) is bounded — the thread terminates naturally when its
     *       blocked I/O times out.
     *
     * Each timed-out call can leave ONE daemon thread for at most
     * [ADB_SOCKET_TIMEOUT_MS]. Under normal
     * operation (good ADB transport) this path is rarely hit.
     */
    private fun tryConnectWithTimeout(keyPair: AdbKeyPair, timeoutMs: Long): Dadb? {
        // Plain locals — Thread.join() itself provides the happens-before
        // edge required for the caller to safely read writes made on the
        // connect thread (per JMM: a successful join "happens-after" all
        // actions of the joined thread). Previously these were marked
        // `@Volatile` but Kotlin allows that annotation on locals with no
        // effect; the actual synchronization is from join.
        var result: Dadb? = null
        var error: Exception? = null

        val connectThread = Thread({
            try {
                val dadb = Dadb.create(
                    "127.0.0.1",
                    ADB_PORT,
                    keyPair,
                    ADB_CONNECT_TIMEOUT_MS,
                    ADB_SOCKET_TIMEOUT_MS
                )
                val testResult = dadb.shell("echo ok")
                if (testResult.exitCode == 0) {
                    result = dadb
                } else {
                    dadb.close()
                }
            } catch (e: Exception) {
                error = e
            }
        }, "adb-connect-probe").apply {
            // Daemon so it doesn't block JVM shutdown on Robolectric/JUnit
            isDaemon = true
        }

        connectThread.start()
        connectThread.join(timeoutMs)

        if (connectThread.isAlive) {
            // Timed out — auth is pending. We attempt to interrupt as a
            // best-effort, but Socket I/O won't actually unblock; the
            // thread will terminate naturally when its TCP/handshake
            // times out (usually within ~75s on Android).
            logger.debug(TAG, "Connection timed out - auth likely pending; orphan probe thread will exit on socket timeout")
            connectThread.interrupt()
            return null
        }

        error?.let { throw it }
        return result
    }
    
    /**
     * Background polling on dedicated executor.
     */
    private fun startAuthPollingInternal(keyPair: AdbKeyPair) {
        pollingExecutor.execute {
            logger.info(TAG, "=== AUTH POLLING STARTED ===")
            var attempts = 0
            val maxAttempts = 60
            
            while (isAuthPending.get() && attempts < maxAttempts) {
                attempts++

                try {
                    // [local] Back off so a STUCK connection doesn't hammer adbd every 3s. Each
                    // poll re-opens a TCP + dadb connect to 127.0.0.1:5555; at a 3s cadence that
                    // churns adbd and flaps any external adb-over-wifi session (observed 2026-07-01:
                    // laptop adb cycling device->offline while OverDrive was stuck relaunching
                    // daemons post-reboot). Quick first tries still catch a transient blip / a
                    // first-time auth grant; then stretch to a 30s ceiling so a persistently-stuck
                    // OverDrive polls at most every 30s and stops interrupting adb.
                    val backoffMs = if (attempts <= 3) 3000L
                                    else minOf(3000L * (1L shl minOf(attempts - 3, 4)), 30000L)
                    Thread.sleep(backoffMs)
                } catch (e: InterruptedException) {
                    logger.debug(TAG, "Polling interrupted")
                    break
                }
                
                if (!isAuthPending.get()) {
                    logger.debug(TAG, "Auth no longer pending, stopping poll")
                    break
                }
                
                logger.info(TAG, "Auth poll attempt $attempts/$maxAttempts...")
                
                // Quick TCP check first
                if (!isAdbPortOpen()) {
                    logger.debug(TAG, "ADB port not open, skipping attempt")
                    continue
                }
                
                // Try connection with short timeout
                val testDadb = tryConnectWithTimeout(keyPair, 2000)
                
                if (testDadb != null) {
                    // Success!
                    synchronized(sharedDadbLock) {
                        try { sharedDadb?.close() } catch (ignored: Exception) {}
                        sharedDadb = testDadb
                    }
                    isAuthPending.set(false)
                    wasAuthGranted.set(true)
                    pollingStarted.set(false)
                    logger.info(TAG, "=== AUTH GRANTED VIA POLLING ===")
                    authCallback?.onAuthGranted()
                    break
                }
            }
            
            if (isAuthPending.get() && attempts >= maxAttempts) {
                logger.warn(TAG, "Auth polling timed out")
                isAuthPending.set(false)
                pollingStarted.set(false)
                authCallback?.onAuthFailed("Auth timeout - please grant ADB permission and restart app")
            }
        }
    }
    
    fun closeConnection() {
        synchronized(sharedDadbLock) {
            try {
                sharedDadb?.close()
                logger.info(TAG, "Closed ADB connection")
            } catch (e: Exception) {
                logger.error(TAG, "Error closing ADB connection", e)
            }
            sharedDadb = null
        }
    }

    /**
     * Shut down the per-instance executor. Call when the owning launcher
     * is being torn down — without this, every dropped AdbShellExecutor
     * leaves its single-thread executor parked for the life of the JVM
     * because the underlying Thread is non-daemon by default.
     */
    fun shutdown() {
        try {
            executor.shutdownNow()
        } catch (e: Exception) {
            logger.warn(TAG, "executor shutdown failed: ${e.message}")
        }
    }
    
    private fun getOrCreateAdbKeyPair(): AdbKeyPair {
        cachedKeyPair?.let { return it }
        
        synchronized(keyPairLock) {
            cachedKeyPair?.let { return it }
            
            val keyDir = context.filesDir
            val privateKeyFile = File(keyDir, ADB_KEY_FILE)
            val publicKeyFile = File(keyDir, ADB_PUB_KEY_FILE)
            
            val keyPair = if (privateKeyFile.exists() && publicKeyFile.exists()) {
                try {
                    AdbKeyPair.read(privateKeyFile, publicKeyFile)
                } catch (e: Exception) {
                    logger.warn(TAG, "Failed to read existing keys: ${e.message}")
                    generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
                }
            } else {
                logger.info(TAG, "Generating new ADB key pair")
                generateAndSaveKeyPair(privateKeyFile, publicKeyFile)
            }
            
            cachedKeyPair = keyPair
            return keyPair
        }
    }
    
    private fun generateAndSaveKeyPair(privateKeyFile: File, publicKeyFile: File): AdbKeyPair {
        AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}
