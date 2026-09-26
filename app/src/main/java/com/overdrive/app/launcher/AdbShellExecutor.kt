package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager
import com.overdrive.app.util.ScratchPaths
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles ADB shell command execution and connection management.
 *
 * ## Connection model (field-incident hardening)
 *
 * A single process-wide Dadb connection is shared by every AdbShellExecutor
 * instance, held in a GENERATION-TAGGED holder ([SharedConn]):
 *
 *  - Acquire happens under a short mutex ([sharedDadbLock]); commands run
 *    OUTSIDE the mutex on the acquired snapshot.
 *  - There is NO per-command liveness probe. The previous design ran
 *    `dadb.shell("echo ok")` before every command while holding the shared
 *    lock; a field incident (adbd `CHECK_EQ(payload.size, data_length)` abort
 *    on a 21-byte OPEN — exactly the `shell,v2,raw:echo ok` packet) implicated
 *    that probe as the packet class most frequently on the wire, and the
 *    un-timed probe also stalled every executor behind the monitor when a
 *    connection was half-dead. The connection is now trusted until failure.
 *  - INVALIDATION replaces probing: any transport failure or deadline breach
 *    clears the holder — but only if it still holds the SAME generation the
 *    failing command used (so concurrent failures / watchdogs can't tear down
 *    a replacement connection) — and then closes that exact connection.
 *  - Every command carries a DEADLINE enforced by a watchdog. java.net.Socket
 *    I/O is not interruptible, so the watchdog unblocks a stuck command the
 *    only way possible: it closes the connection (gen-guarded), which makes
 *    the blocked read/write throw. Deadlines are short for control-plane
 *    commands ([DEFAULT_DEADLINE_MS]) and longer for scripts
 *    ([SCRIPT_RUN_DEADLINE_MS]); bulk operations get their own lane (below).
 *  - NOTHING is ever retried automatically. A mutating command whose result
 *    is unknown (deadline, transport drop) must not be re-sent blindly —
 *    callers own that decision.
 *  - When a NEW connection replaces a previously-established one (generation
 *    > 1), [ConnectionReestablishedListener]s fire asynchronously. adbd death
 *    implies init SIGKILLed its whole cgroup — every shell-spawned daemon —
 *    so DaemonStartupManager uses this to resnapshot + relaunch immediately
 *    instead of waiting for the next 30s tick behind a wedged connection.
 *
 * ## Bulk lane
 *
 * [executeBulk] runs a command on a DEDICATED per-operation Dadb connection
 * with a caller-chosen deadline and socketTimeout=0. Use it for anything that
 * can stay output-silent longer than the control lane tolerates — the updater
 * download (`timeout 600 wget -q …`) and `pm install` — so a long-quiet
 * transfer can neither be killed by the shared lane's socket timeout nor
 * block/tear the control connection every other subsystem depends on.
 */
class AdbShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AdbShellExecutor"
        private const val ADB_PORT = 5555
        private const val ADB_KEY_FILE = "adbkey"
        private const val ADB_PUB_KEY_FILE = "adbkey.pub"

        /** TCP connect bound passed to Dadb.create. */
        private const val CONNECT_TIMEOUT_MS = 2_000

        /**
         * SO_TIMEOUT for the SHARED control connection. Dadb has no dedicated
         * reader thread — reads happen on whichever thread awaits stream data —
         * so this fires only for a thread actually blocked in a read, bounding
         * (a) a stalled ADB auth read during Dadb.create (connectTimeout covers
         * only the TCP connect) and (b) any single silent stretch of a command.
         * It is a backstop under the per-command deadline, sized to match
         * [DEFAULT_DEADLINE_MS]. Control-lane payloads must not stay silent
         * longer than this in one stretch (current max inline sleep is ~10s in
         * kill cascades); anything longer belongs on the bulk lane.
         */
        private const val SOCKET_TIMEOUT_MS = 60_000

        /** Default per-command deadline on the shared connection. */
        const val DEFAULT_DEADLINE_MS = 60_000L

        /** Default deadline for the run-phase of [executeScript] payloads. */
        const val SCRIPT_RUN_DEADLINE_MS = 120_000L

        /** SO_TIMEOUT for the bulk lane's throwaway HANDSHAKE PROBE. Auth is
         *  already granted when this runs (the control lane connected with
         *  the same key first), so a healthy CNXN/AUTH completes in
         *  milliseconds; this only bounds transport weirdness. (dadb's
         *  AdbConnection — the way to own the raw socket and retune its
         *  timeout in place — is `internal`, so the probe-then-real two-step
         *  is how a short handshake bound coexists with a multi-minute
         *  command read budget.) */
        private const val BULK_HANDSHAKE_TIMEOUT_MS = 10_000

        /**
         * SO_TIMEOUT for the throwaway AUTH PROBE connection (first step of
         * [tryConnectWithTimeout]). While user auth is pending, adbd simply
         * never answers — the probe's read blocks until this fires. Short on
         * purpose: an abandoned probe must release its socket within seconds,
         * not squat on adbd for the control lane's 60s. Sized above any
         * healthy handshake round-trip (normally <100ms) with generous slack
         * for a loaded head unit.
         */
        private const val PROBE_SOCKET_TIMEOUT_MS = 5_000

        /**
         * Single-flight gate for connection probes. While auth is pending,
         * BOTH the polling loop (every 3-30s) and every incoming command
         * (health checks, UI refreshes) attempt to connect; without a gate,
         * each attempt stacked another auth-pending socket on adbd for the
         * probe's lifetime — exactly the connection pressure a fragile vendor
         * adbd doesn't need. Held from probe start to probe-thread END (see
         * finally in [tryConnectWithTimeout]); a caller that loses the gate
         * treats it as "attempt in progress" and returns null immediately
         * (same contract as an unanswered probe).
         */
        private val probeInFlight = AtomicBoolean(false)

        /**
         * Generation-tagged holder for the process-wide shared connection.
         * The generation uniquely identifies one Dadb instance for the life of
         * the process, so invalidation can be exact: "clear the holder only if
         * it still holds ME".
         */
        private class SharedConn(val dadb: Dadb, val generation: Long, val establishedAt: Long)

        @Volatile
        private var sharedConn: SharedConn? = null
        private val sharedDadbLock = Object()
        private val connectionGeneration = java.util.concurrent.atomic.AtomicLong(0)

        @Volatile
        private var cachedKeyPair: AdbKeyPair? = null
        private val keyPairLock = Object()

        /** True after a successful connect has run the scratch write probe. */
        private val scratchProbed = AtomicBoolean(false)

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
        // the sharedConn that cleanup() deliberately leaves open.
        private val fallbackExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "AdbShellFallback").apply { isDaemon = true }
        }

        /**
         * Watchdog scheduler for per-command deadlines. Single daemon thread:
         * watchdog bodies do nothing but a gen-guarded holder swap + a socket
         * close, both fast, so one thread cannot fall meaningfully behind.
         */
        private val deadlineScheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "AdbDeadlineWatchdog").apply { isDaemon = true }
        }

        /**
         * Executor that fires [ConnectionReestablishedListener]s. Dedicated so
         * a slow listener can neither delay watchdogs (deadlineScheduler) nor
         * run under [sharedDadbLock] (listeners are notified after the lock is
         * released, from this thread).
         */
        private val reconnectNotifier = Executors.newSingleThreadExecutor { r ->
            Thread(r, "AdbReconnectNotify").apply { isDaemon = true }
        }

        private val reconnectListeners =
            java.util.concurrent.CopyOnWriteArrayList<ConnectionReestablishedListener>()

        fun addConnectionReestablishedListener(listener: ConnectionReestablishedListener) {
            reconnectListeners.addIfAbsent(listener)
        }

        fun removeConnectionReestablishedListener(listener: ConnectionReestablishedListener) {
            reconnectListeners.remove(listener)
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
    }

    interface AdbAuthCallback {
        fun onAuthPending()
        fun onAuthGranted()
        fun onAuthFailed(error: String)
    }

    /**
     * Fired when a NEW shared connection is installed and a previous one had
     * existed (generation > 1) — i.e. a genuine RE-connect, which on this
     * platform almost always means adbd died and took its whole cgroup (every
     * shell-spawned daemon) with it. First-ever connect does NOT fire (daemon
     * startup is staged separately and must not be preempted). Listeners are
     * invoked on a dedicated notifier thread and MUST NOT do blocking shell
     * work inline — post to your own handler.
     *
     * Distinct from [AdbAuthCallback]: that is a single process-wide slot
     * owned by MainActivity's onboarding flow; this is multicast and purely
     * about transport lifecycle. Register via
     * [addConnectionReestablishedListener] on the companion.
     */
    fun interface ConnectionReestablishedListener {
        fun onConnectionReestablished(generation: Long)
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
        executeInternal(command, command, DEFAULT_DEADLINE_MS, callback)
    }

    /** [execute] with an explicit per-command deadline. */
    fun execute(command: String, deadlineMs: Long, callback: ShellCallback) {
        executeInternal(command, command, deadlineMs, callback)
    }

    /**
     * Execute [command] unchanged while keeping its credentials out of diagnostic logs.
     */
    fun executeSensitive(command: String, description: String, callback: ShellCallback) {
        executeInternal(command, "<sensitive:$description>", DEFAULT_DEADLINE_MS, callback)
    }

    private fun executeInternal(
        command: String,
        commandForLog: String,
        deadlineMs: Long,
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
                val conn = acquireConnection()
                val tConn = System.currentTimeMillis() - t0
                val result = shellGuarded(conn, command, deadlineMs, seq)
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

    @JvmOverloads
    fun executeSync(command: String, deadlineMs: Long = DEFAULT_DEADLINE_MS): ShellResult {
        logger.debug(TAG, "Executing sync: $command")
        val seq = cmdSeq.incrementAndGet()
        val conn = acquireConnection()
        val result = shellGuarded(conn, command, deadlineMs, seq)
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
     * is just `sh <scratch>/<id>.sh` — no daemon pattern in argv —
     * so pkill cannot self-match. The script content is read from disk
     * by `sh`, not from argv.
     *
     * Use this for any multi-command shell payload that contains a
     * `pkill -f` whose pattern also appears as a literal in earlier
     * commands of the same payload. The temp file is cleaned up on
     * completion (best-effort).
     *
     * The run phase gets [runDeadlineMs] (default [SCRIPT_RUN_DEADLINE_MS]);
     * note the shared lane's [SOCKET_TIMEOUT_MS] still bounds any single
     * output-silent stretch — a script must not sleep longer than that in
     * one go (none currently does; long-quiet work belongs on [executeBulk]).
     */
    @JvmOverloads
    fun executeScript(
        scriptBody: String,
        callback: ShellCallback,
        runDeadlineMs: Long = SCRIPT_RUN_DEADLINE_MS
    ) {
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
            val scriptPath = ScratchPaths.path(".adb_script_${nonce}.sh")
            val eofMarker = "__ADB_SCRIPT_EOF_${nonce}__"
            try {
                logger.debug(TAG, "Executing script via $scriptPath (${scriptBody.length} bytes)")
                val conn = acquireConnection()

                // Write via a heredoc — the heredoc body comes from stdin
                // not argv, so a `pkill -f cam_daemon` pattern inside the
                // body never appears in any shell's argv and self-match
                // is impossible. No chmod needed: `sh <path>` reads the
                // script regardless of x-bit, so the previous `chmod 755`
                // was dead code. Prefix env so scratch dir exists before cat.
                val writeCmd = "cat > $scriptPath <<'$eofMarker'\n" +
                    scriptBody +
                    "\n$eofMarker"
                val writeResult = shellGuarded(conn, writeCmd, DEFAULT_DEADLINE_MS, seq)
                if (writeResult.exitCode != 0) {
                    // Best-effort cleanup of any partial write
                    tryCleanupScript(scriptPath)
                    callback.onError("script-write failed: ${writeResult.allOutput}")
                    return@submit
                }

                // Run with `trap 'rm -f path' EXIT` so the tmpfile is
                // removed on ANY shell exit — normal, signal, or abnormal.
                // The previous `sh path; RC=$?; rm -f path; exit $RC`
                // form leaked the tmpfile if the inner shell was killed
                // mid-run (rm never reached). trap-EXIT runs even on
                // SIGTERM/SIGHUP from dadb transport teardown.
                //
                // Re-acquire: the write above may have invalidated the shared
                // connection on failure paths that still threw past us; the
                // holder gives back either the same connection or a fresh one.
                val runConn = acquireConnection()
                val runResult = shellGuarded(
                    runConn,
                    "trap 'rm -f $scriptPath' EXIT; sh $scriptPath",
                    runDeadlineMs,
                    seq
                )

                if (runResult.exitCode == 0) {
                    callback.onSuccess(runResult.allOutput)
                } else {
                    callback.onError("Exit code ${runResult.exitCode}: ${runResult.allOutput}")
                }
            } catch (e: Exception) {
                logger.error(TAG, "Script execution failed", e)
                callback.onError("Execution failed: ${e.message}")
                // Best-effort cleanup if a connection is still obtainable.
                tryCleanupScript(scriptPath)
            }
        }
    }

    /** Best-effort rm of a leftover script tmpfile; swallows every failure. */
    private fun tryCleanupScript(scriptPath: String) {
        try {
            val conn = acquireConnection()
            shellGuarded(conn, "rm -f $scriptPath 2>/dev/null", DEFAULT_DEADLINE_MS, cmdSeq.incrementAndGet())
        } catch (ignored: Exception) {
        }
    }

    /**
     * BULK LANE: run [command] on a DEDICATED, per-operation Dadb connection,
     * bounded by [deadlineMs], then close it. For long-running operations —
     * the updater's `timeout 600 wget -q` download, `pm install` — that can
     * stay output-silent far longer than the shared lane's socket timeout,
     * and that must not monopolize or tear down the control connection.
     *
     * The dedicated connection's socket timeout is derived from the remaining
     * operation budget (a silent multi-minute transfer is the POINT of this
     * lane, so it must exceed any legitimate quiet stretch); the watchdog
     * closes this exact connection on deadline breach, which is safe because
     * nothing else shares it. The shared control lane is untouched throughout.
     *
     * NO automatic retry: on deadline or transport failure the command's
     * effect is unknown — the caller owns any retry decision.
     */
    fun executeBulk(command: String, deadlineMs: Long, callback: ShellCallback) {
        val seq = cmdSeq.incrementAndGet()
        logger.debug(TAG, "adb#$seq SUBMIT-BULK deadline=${deadlineMs}ms: $command")
        // The deadline anchors at SUBMISSION on the MONOTONIC clock, and the
        // callback is GUARANTEED by deadline+ε: a SETUP watchdog armed right
        // here covers the phases where the task may not even be running yet
        // (queue wait behind an earlier command on this single-thread
        // executor, control-lane acquire, bulk connect), and is swapped for a
        // COMMAND watchdog that closes the dedicated connection once the
        // command starts.
        // Without the setup watchdog, a queued task had no one to deliver its
        // timeout, so the caller's deadline+margin wait (the updater's
        // awaitFlag) could expire while the command later ran anyway —
        // recreating the cleanup-while-command-runs hazard these deadlines
        // exist to prevent. [settled] is the single-delivery gate: exactly
        // one of {setup watchdog, command watchdog, task} reports the outcome.
        val deadlineAtNanos = System.nanoTime() + deadlineMs * 1_000_000L
        val settled = AtomicBoolean(false)
        val setupWatchdog = deadlineScheduler.schedule({
            if (settled.compareAndSet(false, true)) {
                logger.warn(TAG, "adb#$seq BULK DEADLINE ${deadlineMs}ms expired before the " +
                    "command could start (queued/connecting) — reporting without executing")
                callback.onError("Execution failed: deadline ${deadlineMs}ms exceeded before start")
            }
        }, deadlineMs, TimeUnit.MILLISECONDS)
        submit(seq, "<bulk:${command.take(120)}>") {
            fun remainingMs() = (deadlineAtNanos - System.nanoTime()) / 1_000_000L
            val t0 = System.currentTimeMillis()
            var bulk: BulkConnection? = null
            try {
                // Queue phase over. If the setup watchdog already reported,
                // the outcome is delivered and the command has NOT run — do
                // nothing (a mutating command must never start after its
                // failure was already reported).
                if (settled.get()) return@submit

                // Ensure the control lane is healthy first. Same ADB key, so
                // the bulk connect below can't stall on an unauthorized-key
                // auth wait.
                acquireConnection()
                var remaining = remainingMs()
                if (remaining <= 0) return@submit   // setup watchdog delivers

                val keyPair = getOrCreateAdbKeyPair()
                val conn = createBulkConnection(keyPair, remaining)
                bulk = conn

                // Swap watchdogs. cancel() returning false means the setup
                // watchdog fired (or is firing) between the checks above and
                // here — the outcome is already delivered; abort before the
                // command starts (finally closes the connection).
                if (!setupWatchdog.cancel(false)) return@submit
                remaining = remainingMs()
                if (remaining <= 0) {
                    if (settled.compareAndSet(false, true)) {
                        callback.onError("Execution failed: deadline ${deadlineMs}ms exceeded before start")
                    }
                    return@submit
                }
                val timedOut = AtomicBoolean(false)
                val cmdWatchdog = deadlineScheduler.schedule({
                    if (settled.compareAndSet(false, true)) {
                        timedOut.set(true)
                        logger.warn(TAG, "adb#$seq BULK DEADLINE ${deadlineMs}ms (from submission) " +
                            "exceeded — closing dedicated connection to unblock")
                        conn.closeQuietly()
                        callback.onError("Execution failed: deadline ${deadlineMs}ms exceeded " +
                            "(dedicated connection closed to unblock)")
                    }
                }, remaining, TimeUnit.MILLISECONDS)
                try {
                    val result = conn.adb.shell(ScratchPaths.prepareShellCommand(command))
                    if (settled.compareAndSet(false, true)) {
                        cmdWatchdog.cancel(false)
                        logger.debug(TAG, "adb#$seq DONE-BULK total=${System.currentTimeMillis() - t0}ms exit=${result.exitCode}")
                        if (result.exitCode == 0) {
                            callback.onSuccess(result.allOutput)
                        } else {
                            callback.onError("Exit code ${result.exitCode}: ${result.allOutput}")
                        }
                    }
                    // else: the command watchdog won while the result was
                    // completing — its onError already went out; drop the
                    // result (callers never auto-retry mutating commands).
                } catch (e: Exception) {
                    cmdWatchdog.cancel(false)
                    if (settled.compareAndSet(false, true)) {
                        logger.error(TAG, "adb#$seq FAILED-BULK after ${System.currentTimeMillis() - t0}ms: ${e.message}", e)
                        callback.onError("Execution failed: ${e.message}")
                    } else if (timedOut.get()) {
                        // Expected: our own deadline close unblocked the read.
                        logger.debug(TAG, "adb#$seq bulk command unblocked by deadline close: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                // Setup-phase failure (control-lane acquire / bulk connect).
                setupWatchdog.cancel(false)
                if (settled.compareAndSet(false, true)) {
                    logger.error(TAG, "adb#$seq FAILED-BULK after ${System.currentTimeMillis() - t0}ms: ${e.message}", e)
                    callback.onError("Execution failed: ${e.message}")
                }
            } finally {
                bulk?.closeQuietly()
            }
        }
    }

    /**
     * One bulk operation's connection. Wraps the [Dadb] handle so the
     * watchdog and every cleanup path share one idempotent close.
     */
    private class BulkConnection(val adb: Dadb) {
        fun closeQuietly() {
            try { adb.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * Establish the bulk lane's dedicated connection — SYNCHRONOUSLY and
     * EAGERLY, with the handshake and the command phase bounded SEPARATELY.
     *
     * SO_TIMEOUT is fixed for a connection's life at Dadb.create (dadb's
     * AdbConnection, the socket-owning route, is `internal`), so one socket
     * cannot carry both a short handshake bound and a multi-minute command
     * read budget. Two-step instead — the same pattern the control lane's
     * auth probe uses:
     *
     *  1. Throwaway HANDSHAKE PROBE with [BULK_HANDSHAKE_TIMEOUT_MS]:
     *     `supportsFeature` forces the full CNXN/AUTH exchange (no shell
     *     OPEN); a stalled handshake throws SocketTimeoutException within
     *     seconds ON THIS THREAD and the probe is closed in the finally.
     *     No connect thread, no join, no abandoned socket to stack up.
     *  2. The REAL connection with the operation-budget socket timeout. Auth
     *     was just proven, so ITS handshake completes in milliseconds — the
     *     multi-minute SO_TIMEOUT never guards an unauthenticated read. It
     *     is likewise established eagerly here, so by the time the command
     *     watchdog is armed the connection is stored and its close()
     *     genuinely severs a stuck command.
     *
     * Worst case for a transport that stalls between the two steps: the real
     * connection's handshake read is bounded by its own SO_TIMEOUT — but
     * that read happens on the executor thread AFTER the setup watchdog is
     * armed at the submission deadline, so the caller's outcome is still
     * delivered on time regardless.
     */
    private fun createBulkConnection(keyPair: AdbKeyPair, remainingBudgetMs: Long): BulkConnection {
        // STEP 1 — bounded throwaway handshake probe, always closed.
        val probeTimeoutMs = BULK_HANDSHAKE_TIMEOUT_MS.toLong()
            .coerceAtMost(remainingBudgetMs).coerceAtLeast(1L).toInt()
        val probe = Dadb.create("127.0.0.1", ADB_PORT, keyPair, CONNECT_TIMEOUT_MS, probeTimeoutMs)
        try {
            probe.supportsFeature("shell_v2")
        } finally {
            try { probe.close() } catch (ignored: Exception) {}
        }

        // STEP 2 — the real connection, command-phase socket timeout. A
        // legitimately silent stretch (quiet 600s wget) must never trip a
        // per-read timeout, so it gets the remaining operation budget; the
        // ABSOLUTE watchdog is the real command bound.
        val commandTimeoutMs = remainingBudgetMs.coerceAtLeast(SOCKET_TIMEOUT_MS.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val real = Dadb.create("127.0.0.1", ADB_PORT, keyPair, CONNECT_TIMEOUT_MS, commandTimeoutMs)
        try {
            real.supportsFeature("shell_v2")   // establish eagerly (see doc)
        } catch (e: Exception) {
            try { real.close() } catch (ignored: Exception) {}
            throw e
        }
        return BulkConnection(real)
    }

    fun checkProcessRunning(processName: String): Int? {
        return try {
            val conn = acquireConnection()
            val result = shellGuarded(conn, "pgrep -f '$processName'", DEFAULT_DEADLINE_MS, cmdSeq.incrementAndGet())
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
            val conn = acquireConnection()
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
            val result = shellGuarded(conn, cmd, DEFAULT_DEADLINE_MS, cmdSeq.incrementAndGet())
            result.exitCode == 0
        } catch (e: Exception) {
            logger.error(TAG, "Failed to kill process: $processName", e)
            false
        }
    }

    /**
     * Acquire the shared connection, creating it if absent. NO liveness probe:
     * a cached connection is trusted until a command on it fails — that
     * failure (or its deadline watchdog) invalidates the holder via
     * [invalidateConnection], and the next acquire reconnects. This replaces
     * the removed `echo ok` per-command probe, which was both the process-wide
     * stall point (un-timed round-trip inside this monitor) and the packet
     * class implicated in the vendor adbd abort incident.
     *
     * The mutex is held only for the holder check or the connect attempt —
     * never across command execution.
     */
    private fun acquireConnection(): SharedConn {
        val tWait = System.currentTimeMillis()
        synchronized(sharedDadbLock) {
            val waited = System.currentTimeMillis() - tWait
            if (waited > 1000) logger.warn(TAG, "acquireConnection: waited ${waited}ms for sharedDadbLock")

            sharedConn?.let { return it }
            // Check if ADB port is even listening before trying to connect
            if (!isAdbPortOpen()) {
                logger.warn(TAG, "ADB port $ADB_PORT not open - ADB not enabled?")
                throw Exception("ADB port not open")
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
            val dadb = tryConnectWithTimeout(adbKeyPair, 2000)
                ?: throw Exception("ADB auth pending - waiting for user to accept")

            return installConnectionLocked(dadb)
        }
    }

    /**
     * Install a freshly-established connection into the holder. MUST be called
     * under [sharedDadbLock]. Bumps the generation, resolves auth state, and —
     * when this REPLACES a previously-established connection — schedules the
     * [ConnectionReestablishedListener]s (async, off-lock): a reconnect means
     * adbd died, and adbd's death SIGKILLed its entire cgroup of shell-spawned
     * daemons, so listeners resnapshot + relaunch instead of waiting for the
     * next periodic tick.
     */
    private fun installConnectionLocked(dadb: Dadb): SharedConn {
        val gen = connectionGeneration.incrementAndGet()
        val conn = SharedConn(dadb, gen, System.currentTimeMillis())
        sharedConn = conn
        isAuthPending.set(false)
        wasAuthGranted.set(true)
        pollingStarted.set(false)
        logger.info(TAG, "ADB connection established successfully (gen=$gen)")
        authCallback?.onAuthGranted()
        probeScratchAfterConnect(dadb)
        if (gen > 1) {
            reconnectNotifier.execute {
                logger.info(TAG, "ADB connection RE-established (gen=$gen) — notifying " +
                    "${reconnectListeners.size} listener(s)")
                for (listener in reconnectListeners) {
                    try {
                        listener.onConnectionReestablished(gen)
                    } catch (e: Exception) {
                        logger.warn(TAG, "reconnect listener failed: ${e.message}")
                    }
                }
            }
        }
        return conn
    }

    /**
     * Invalidate [conn]: atomically clear the holder ONLY if it still holds
     * this exact generation, then close it. Gen-guarding makes concurrent
     * invalidations (command failure racing its own watchdog, or two commands
     * failing on the same dead connection) idempotent, and guarantees a
     * REPLACEMENT connection can never be torn down by a stale failure.
     */
    private fun invalidateConnection(conn: SharedConn, reason: String) {
        var shouldClose = false
        synchronized(sharedDadbLock) {
            if (sharedConn === conn) {
                sharedConn = null
                shouldClose = true
            }
        }
        if (shouldClose) {
            val ageMs = System.currentTimeMillis() - conn.establishedAt
            // Forensic breadcrumb: generation + age let a field log distinguish
            // "one bad command killed a mature connection" from a connect churn
            // loop, and correlate with adbd tombstones / external ADB clients.
            logger.warn(TAG, "Invalidating shared ADB connection gen=${conn.generation} " +
                "(age=${ageMs}ms): $reason")
            try { conn.dadb.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * Run one shell command on [conn] with a deadline. Socket I/O is not
     * interruptible, so the watchdog unblocks a stuck command the only way
     * available: closing the connection (gen-guarded), which makes the blocked
     * read throw. On ANY failure the connection is invalidated — a transport
     * error means its state is unknown and the next acquire must reconnect.
     * The command itself is NEVER retried here (its effect is unknown).
     */
    private fun shellGuarded(
        conn: SharedConn,
        command: String,
        deadlineMs: Long,
        seq: Int
    ): dadb.AdbShellResponse {
        val settled = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val watchdog = deadlineScheduler.schedule({
            if (settled.compareAndSet(false, true)) {
                timedOut.set(true)
                invalidateConnection(conn, "deadline ${deadlineMs}ms exceeded by adb#$seq")
            }
        }, deadlineMs, TimeUnit.MILLISECONDS)
        try {
            val result = conn.dadb.shell(ScratchPaths.prepareShellCommand(command))
            if (settled.compareAndSet(false, true)) {
                watchdog.cancel(false)
            }
            // else: the watchdog fired while the result was completing and the
            // connection is already gone — the result itself is still real, so
            // return it; the next command simply reconnects.
            return result
        } catch (e: Exception) {
            val genuineFailure = settled.compareAndSet(false, true)
            watchdog.cancel(false)
            if (genuineFailure) {
                // Transport failure on a live watchdog: invalidate (no-op if
                // another thread's failure got there first).
                invalidateConnection(conn, "transport failure on adb#$seq: ${e.message}")
                throw e
            }
            // The exception is the consequence of our own deadline close.
            throw Exception("deadline ${deadlineMs}ms exceeded after adb#$seq " +
                "(connection closed to unblock)", e)
        }
    }

    /**
     * Once per process: try writing `/data/local/tmp` via shell (Sealion keeps
     * legacy). Only if that fails do we lock in the app-files fallback (Shark).
     * Uses a raw [Dadb.shell] — must not go through [ScratchPaths.prepareShellCommand]
     * or the probe path itself would be remapped.
     */
    private fun probeScratchAfterConnect(dadb: Dadb) {
        if (!scratchProbed.compareAndSet(false, true)) return
        try {
            ScratchPaths.probeViaShell { cmd ->
                val result = dadb.shell(cmd)
                if (result.exitCode == 0) result.allOutput else null
            }
            logger.info(TAG, "Scratch probe done: ${ScratchPaths.describe()}")
        } catch (t: Throwable) {
            logger.warn(TAG, "Scratch probe failed: ${t.message}")
            // Allow a later connect to retry if this one blew up mid-probe.
            scratchProbed.set(false)
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
     * Try to connect with a timeout. Returns null when the attempt is still
     * unresolved (auth pending) — or when another probe already holds the
     * process-wide [probeInFlight] gate, which the caller must treat the
     * same way.
     *
     * TWO-STEP ESTABLISH. SO_TIMEOUT is fixed for a connection's life at
     * Dadb.create, so one socket cannot serve both needs: an auth PROBE must
     * die within seconds when adbd never answers (user dialog up), while the
     * long-lived control connection needs the full [SOCKET_TIMEOUT_MS] for
     * command reads. Step 1 probes with [PROBE_SOCKET_TIMEOUT_MS] and is
     * ALWAYS closed; step 2 — only reached once auth is granted, so it
     * completes in milliseconds — builds the real connection with the
     * control-lane timeout. The extra connect happens once per establishment
     * (rare), and in exchange an unanswered probe holds its socket for ~5s
     * instead of 60s. Combined with the gate, at most one auth-pending
     * socket ever sits on adbd, no matter how many commands and poll
     * attempts pile up behind a pending dialog.
     *
     * `supportsFeature("shell_v2")` forces the CNXN/AUTH exchange (dadb
     * connects lazily) WITHOUT opening a shell stream — i.e. without the
     * 21-byte `shell,v2,raw:echo ok` OPEN implicated in the vendor adbd
     * abort. Returning at all means "authenticated".
     *
     * NOTE: java.net.Socket I/O is NOT interruptible — the probe thread
     * unblocks only via the socket-level timeouts above. Late-success
     * handoff: if the join times out but the thread finishes authenticating
     * afterwards, the real connection is CLOSED, not leaked (decided
     * atomically under [lock]).
     */
    private fun tryConnectWithTimeout(keyPair: AdbKeyPair, timeoutMs: Long): Dadb? {
        if (!probeInFlight.compareAndSet(false, true)) {
            logger.debug(TAG, "Connection probe already in flight — treating this attempt as pending")
            return null
        }
        val lock = Object()
        var result: Dadb? = null
        var error: Exception? = null
        var abandoned = false
        var threadStarted = false

        val connectThread = Thread({
            var real: Dadb? = null
            try {
                // STEP 1 — throwaway probe, short socket timeout, always closed.
                val probe = Dadb.create("127.0.0.1", ADB_PORT, keyPair, CONNECT_TIMEOUT_MS, PROBE_SOCKET_TIMEOUT_MS)
                try {
                    probe.supportsFeature("shell_v2")
                } finally {
                    try { probe.close() } catch (ignored: Exception) {}
                }
                // STEP 2 — auth granted: the real connection, control-lane timeout.
                val dadb = Dadb.create("127.0.0.1", ADB_PORT, keyPair, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
                real = dadb
                if (!dadb.supportsFeature("shell_v2")) {
                    logger.warn(TAG, "adbd does not advertise shell_v2 — shell commands may fail on this transport")
                }
                synchronized(lock) {
                    if (abandoned) {
                        logger.info(TAG, "ADB connect completed after caller gave up — closing it")
                    } else {
                        result = dadb
                        real = null   // ownership transferred to the caller
                    }
                }
            } catch (e: Exception) {
                synchronized(lock) { error = e }
            } finally {
                try { real?.close() } catch (ignored: Exception) {}
                // Gate released by the THREAD, not the caller: the gate's job
                // is to bound live sockets, and the socket dies with this
                // thread (≤ ~PROBE_SOCKET_TIMEOUT_MS after abandonment).
                probeInFlight.set(false)
            }
        }, "adb-connect-probe").apply {
            // Daemon so it doesn't block JVM shutdown on Robolectric/JUnit
            isDaemon = true
        }

        try {
            connectThread.start()
            threadStarted = true
        } finally {
            if (!threadStarted) probeInFlight.set(false)
        }
        try {
            connectThread.join(timeoutMs)
        } catch (ie: InterruptedException) {
            // The CALLER was interrupted (e.g. instance shutdown()
            // interrupting its executor). Two leak vectors, both handled
            // under one lock hold:
            //  - a handshake completing AFTER this return would CAS its live
            //    connection into [result], which nobody will ever read —
            //    `abandoned` makes the thread close it itself;
            //  - a handshake that completed JUST BEFORE the interrupt has
            //    ALREADY published into [result] (the thread saw
            //    abandoned=false and handed off ownership) — sweep and close
            //    that one here, or it leaks onto adbd the same way.
            val alreadyPublished: Dadb?
            synchronized(lock) {
                abandoned = true
                alreadyPublished = result
                result = null
            }
            try { alreadyPublished?.close() } catch (ignored: Exception) {}
            connectThread.interrupt()
            Thread.currentThread().interrupt()
            return null
        }

        synchronized(lock) {
            result?.let { return it }
            error?.let { throw it }
            // Timed out — auth is pending. Abandon: the thread self-cleans
            // (closes a late success under this lock; a blocked probe read
            // throws within PROBE_SOCKET_TIMEOUT_MS) and releases the gate.
            logger.debug(TAG, "Connection timed out - auth likely pending; probe exits within ${PROBE_SOCKET_TIMEOUT_MS}ms")
            abandoned = true
            connectThread.interrupt()
            return null
        }
    }

    /**
     * Background polling on dedicated executor.
     */
    private fun startAuthPollingInternal(keyPair: AdbKeyPair) {
        pollingExecutor.execute {
            logger.info(TAG, "=== AUTH POLLING STARTED ===")
            var attempts = 0
            val maxAttempts = 60

            try {
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
                        Thread.currentThread().interrupt()
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

                    // Try connection with short timeout. A transient Dadb/create
                    // exception must not escape this long-lived polling Runnable:
                    // before this guard, one transport error killed the poll while
                    // leaving isAuthPending=true + pollingStarted=true. Every daemon
                    // start was then a one-shot failure until the whole app process
                    // was force-closed. Keep polling and let a later healthy adbd
                    // handshake recover the existing process.
                    val testDadb = try {
                        tryConnectWithTimeout(keyPair, 2000)
                    } catch (e: Exception) {
                        logger.warn(TAG, "Auth poll attempt $attempts failed: ${e.message}")
                        null
                    }

                    if (testDadb != null) {
                        // Success! Install ONLY into an empty holder. A command
                        // thread may have connected on its own while this poll
                        // attempt was in flight — closing that healthy connection
                        // here would fail its in-flight commands AND fire a
                        // spurious reconnect event (gen bump → needless snapshot/
                        // relaunch churn). If someone beat us, auth is granted
                        // either way: discard the polling connection.
                        var installed = false
                        synchronized(sharedDadbLock) {
                            if (sharedConn == null) {
                                installConnectionLocked(testDadb)
                                installed = true
                            }
                        }
                        if (installed) {
                            logger.info(TAG, "=== AUTH GRANTED VIA POLLING ===")
                        } else {
                            logger.info(TAG, "Auth poll succeeded but a shared connection " +
                                "already exists — discarding the polling connection")
                            try { testDadb.close() } catch (ignored: Exception) {}
                            isAuthPending.set(false)
                            wasAuthGranted.set(true)
                            pollingStarted.set(false)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                logger.warn(TAG, "Auth polling stopped unexpectedly: ${e.message}")
            } finally {
                // No exit path may leave these process-wide gates latched.
                // Once cleared, the next daemon action / 30s health check can
                // start a fresh poll without requiring a process restart.
                if (isAuthPending.get()) {
                    val timedOut = attempts >= maxAttempts
                    if (timedOut) logger.warn(TAG, "Auth polling timed out")
                    isAuthPending.set(false)
                    pollingStarted.set(false)
                    val reason = if (timedOut) "ADB authorization timed out"
                                 else "ADB authorization polling stopped"
                    authCallback?.onAuthFailed("$reason; will retry automatically")
                }
            }
        }
    }

    fun closeConnection() {
        val toClose: SharedConn?
        synchronized(sharedDadbLock) {
            toClose = sharedConn
            sharedConn = null
        }
        if (toClose != null) {
            try {
                toClose.dadb.close()
                logger.info(TAG, "Closed ADB connection (gen=${toClose.generation})")
            } catch (e: Exception) {
                logger.error(TAG, "Error closing ADB connection", e)
            }
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
