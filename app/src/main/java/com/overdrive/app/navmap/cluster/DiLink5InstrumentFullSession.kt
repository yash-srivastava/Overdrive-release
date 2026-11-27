package com.overdrive.app.navmap.cluster

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.launcher.dilink5.cluster.Sealion7HdmiProjection
import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.monitor.AccMonitor
import com.overdrive.app.monitor.BoundedShell
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_BAD_REQUEST
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_BIND_TIMEOUT
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_EXPIRED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_ALREADY_FULL
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_GUARD_FAILED
import com.overdrive.app.navmap.cluster.ClusterCommContract.RESULT_INSTRUMENT_MODE_CHANGED
import com.overdrive.app.navmap.cluster.ClusterCommContract.abandonMapFocus
import com.overdrive.app.navmap.cluster.ClusterCommContract.displayModeDirect
import com.overdrive.app.navmap.cluster.ClusterCommContract.mapFocusDirect
import com.overdrive.app.navmap.cluster.ClusterCommContract.naviState
import com.overdrive.app.navmap.cluster.ClusterCommContract.presentation
import com.overdrive.app.navmap.cluster.ClusterCommDispatcher.Reply
import org.json.JSONObject

/** Temporary Instrument Full for the verified Sealion 7 hosted HDMI path. */
internal object DiLink5InstrumentFullSession {
    private const val MARKER = "di5InstrumentFullOwner"
    private const val REQUEST_MS = 4_000L
    private const val UNKNOWN = -1
    private const val OFF = 1
    private const val FULL = 4
    private val lock = Any()
    private val logger by lazy { DaemonLogger.getInstance("DiLink5InstrumentFull") }

    internal data class Owner(
        val generation: Long,
        val bootId: String,
        val closing: Boolean = false,
        val legacyMapFocus: Boolean = false,
        val rebootRestore: Boolean = false,
        // Only set after a close is known not to have reached the OEM service.
        val closeNotSent: Boolean = false,
    ) {
        val expectedCommandCount: Int get() = commandBuilders(this).size

        fun encode(): String = JSONObject()
            .put("generation", generation)
            .put("bootId", bootId)
            .put("closing", closing)
            .put("legacyMapFocus", legacyMapFocus)
            .put("rebootRestore", rebootRestore)
            .put("closeNotSent", closeNotSent)
            .toString()

        companion object {
            fun decode(raw: String): Owner? = try {
                val json = JSONObject(raw)
                val generation = json.getLong("generation")
                val bootId = json.getString("bootId")
                val closing = json.getBoolean("closing")
                // Owners persisted before this field could have acquired OEM map focus.
                val legacyMapFocus = !json.has("legacyMapFocus") || json.getBoolean("legacyMapFocus")
                val rebootRestore = json.has("rebootRestore") && json.getBoolean("rebootRestore")
                val closeNotSent = json.has("closeNotSent") && json.getBoolean("closeNotSent")
                if (generation > 0 && bootId.isNotBlank() && (closing || !rebootRestore && !closeNotSent)) {
                    Owner(generation, bootId, closing, legacyMapFocus, rebootRestore, closeNotSent)
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    internal interface Io {
        /** A null owner means absent. An unreadable store must throw. */
        fun readOwner(): Owner?
        fun writeOwner(owner: Owner?): Boolean
        fun readMode(): Int
        fun accOn(): Boolean
        fun dispatch(owner: Owner): Reply
    }

    internal class Lifecycle(private val io: Io, private val log: (String) -> Unit = {}) {
        fun start(generation: Long, bootId: String, current: () -> Boolean): Boolean {
            if (!current() || io.readOwner() != null) return false
            val mode = io.readMode()
            if (mode == FULL) return current()
            if (mode != OFF || !io.accOn() || generation <= 0 || bootId.isBlank()) return false
            val owner = Owner(generation, bootId)
            if (!io.writeOwner(owner)) return false
            if (!current() || !io.accOn() || Thread.currentThread().isInterrupted) {
                io.writeOwner(null)
                return false
            }
            val reply = io.dispatch(owner)
            if (reply.ok() && reply.sent == owner.expectedCommandCount) return current()
            if (definitelyNotSent(reply)) {
                val cleared = io.writeOwner(null)
                return cleared && reply.code == RESULT_INSTRUMENT_ALREADY_FULL && current()
            }
            return false
        }

        fun release(generation: Long?, bootId: String): Boolean {
            val owner = io.readOwner() ?: return true
            if (generation != null && generation != owner.generation) return true
            if (bootId.isBlank()) return false
            val previousBoot = owner.bootId != bootId
            val event = "Instrument Full close generation=${owner.generation}"
            val retryAcrossBoot = !owner.closing || owner.closeNotSent
            if (previousBoot && !retryAcrossBoot) {
                log("$event previous boot close unconfirmed, clearing owner")
                return io.writeOwner(null)
            }
            if (!io.accOn()) {
                if (previousBoot) log("$event waiting for authoritative ACC on after reboot")
                return false
            }
            val mode = io.readMode()
            if (previousBoot) {
                log("$event recovering previous boot owner closeNotSent=${owner.closeNotSent} mode=$mode")
                if (mode <= 0) return false
                if (mode != FULL) return io.writeOwner(null)
            }
            // Invalidate any late open and consume retry permission before dispatch. A lost
            // acknowledgement or a failed clear must never replay a possibly successful Off.
            val closing = owner.copy(
                bootId = bootId, closing = true, rebootRestore = previousBoot || owner.rebootRestore,
                closeNotSent = false,
            )
            if (closing != owner && !io.writeOwner(closing)) return false
            val reply = io.dispatch(closing)
            val expected = closing.expectedCommandCount
            val complete = reply.ok() && reply.sent == expected
            val superseded = reply.answered && reply.code == RESULT_INSTRUMENT_MODE_CHANGED
            val currentSelection = savedModeDisposition(mode, closing) == null
            val knownUnsent = !reply.dispatched || reply.answered && reply.sent == 0 &&
                (reply.definitive() || reply.code == RESULT_BIND_TIMEOUT || reply.code == RESULT_EXPIRED)
            log("$event previousBoot=$previousBoot mode=$mode " +
                "result=${reply.name()} sent=${reply.sent}/$expected")
            if (complete || superseded) return io.writeOwner(null)
            if (retryAcrossBoot && currentSelection && knownUnsent &&
                io.writeOwner(closing.copy(closeNotSent = true))
            ) {
                log("$event confirmed unsent, retained for reboot recovery")
            }
            return false
        }

        fun isSelectionCurrent(generation: Long): Boolean {
            val owner = io.readOwner()
            val mode = io.readMode()
            if (owner == null) return mode == FULL
            return owner.generation == generation && !owner.closing && mode == OFF
        }

        private fun definitelyNotSent(reply: Reply): Boolean {
            if (!reply.dispatched) return true
            if (!reply.answered || reply.sent != 0) return false
            return reply.definitive() || when (reply.code) {
                RESULT_BAD_REQUEST, RESULT_BIND_TIMEOUT, RESULT_EXPIRED,
                RESULT_INSTRUMENT_ALREADY_FULL, RESULT_INSTRUMENT_MODE_CHANGED,
                RESULT_INSTRUMENT_GUARD_FAILED -> true
                else -> false
            }
        }
    }

    fun isSupportedSelection(startGeneration: Long? = null): Boolean {
        if (!Sealion7HdmiProjection.isTestedFirmware()) return false
        val mode = readDaemonSavedMode(startGeneration)
        return mode == OFF || mode == FULL
    }

    fun isSelectionCurrent(context: Context, generation: Long): Boolean =
        attempt { Lifecycle(RuntimeIo(context)).isSelectionCurrent(generation) }

    fun start(context: Context, generation: Long, bootId: String, current: () -> Boolean = { true }): Boolean {
        if (!Sealion7HdmiProjection.isTestedFirmware()) return false
        return synchronized(lock) {
            attempt { Lifecycle(RuntimeIo(context)).start(generation, bootId, current) }
        }
    }

    fun release(context: Context, generation: Long, bootId: String): Boolean =
        restore(context, generation, bootId)

    fun recover(context: Context, bootId: String): Boolean = restore(context, null, bootId)

    fun hasPendingRestore(): Boolean {
        if (!Sealion7HdmiProjection.isTestedFirmware()) return false
        return try {
            loadOwner() != null
        } catch (_: Exception) {
            true
        }
    }

    private fun restore(context: Context, generation: Long?, bootId: String): Boolean {
        if (!Sealion7HdmiProjection.isTestedFirmware()) return true
        return synchronized(lock) {
            // A superseded start may immediately clean up on its interrupted worker.
            val interrupted = Thread.interrupted()
            try {
                attempt { Lifecycle(RuntimeIo(context), logger::info).release(generation, bootId) }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private inline fun attempt(action: () -> Boolean): Boolean = try {
        action()
    } catch (failure: Exception) {
        logger.warn("Instrument Full session state unavailable: ${failure.javaClass.simpleName}")
        false
    }

    private class RuntimeIo(private val context: Context) : Io {
        override fun readOwner(): Owner? = loadOwner()

        override fun writeOwner(owner: Owner?): Boolean = UnifiedConfigManager.updateValues(
            "projection", mapOf(MARKER to (owner?.encode() ?: "")),
        ).also { saved ->
            if (!saved) logger.warn("Instrument Full journal write failed: owner=${owner?.encode() ?: "clear"}")
        }

        override fun readMode(): Int = readDaemonSavedMode()

        override fun accOn(): Boolean = AccMonitor.isAccStateAuthoritative() && AccMonitor.isAccOn()

        override fun dispatch(owner: Owner): Reply =
            ClusterCommDispatcher.dispatch(context, null, REQUEST_MS, owner.encode()).also {
                if (!it.ok() && it.code != RESULT_INSTRUMENT_ALREADY_FULL &&
                    it.code != RESULT_INSTRUMENT_MODE_CHANGED
                ) logger.warn("Instrument Full ${if (owner.closing) "restore" else "open"}: ${it.name()}")
            }
    }

    private fun loadOwner(): Owner? {
        val projection = UnifiedConfigManager.readDurableConfigStrict().optJSONObject("projection")
        val value = projection?.opt(MARKER) ?: return null
        check(value is String) { "Invalid Instrument Full owner" }
        if (value.isEmpty()) return null
        return checkNotNull(Owner.decode(value)) { "Invalid Instrument Full owner" }
    }

    /** The shell tool can read this setting from the daemon's app_process identity. */
    internal fun readDaemonSavedMode(
        startGeneration: Long? = null,
        run: (String, Long) -> String? = { command, deadlineMs ->
            val result = BoundedShell.run(command, deadlineMs)
            if (startGeneration != null) {
                val raw = result.output.takeIf { it == "null" || it.toIntOrNull() != null } ?: "<unavailable>"
                logger.info("Sealion 7 instrument mode at start generation=$startGeneration raw=$raw " +
                    "exit=${result.exitCode} timeout=${result.timedOut}")
            }
            result.takeIf { it.ok() }?.output
        },
    ): Int = run(
        "exec /system/bin/settings get global ${ClusterCommContract.INSTRUMENT_MODE_SETTING}",
        BoundedShell.DEFAULT_DEADLINE_MS,
    )?.toIntOrNull() ?: UNKNOWN

    private fun readAppSavedMode(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, ClusterCommContract.INSTRUMENT_MODE_SETTING, UNKNOWN)
    } catch (_: RuntimeException) {
        UNKNOWN
    }

    /** Validate the journal and driver selection in the app process just before dispatch. */
    @JvmStatic
    fun beforeWire(context: Context, owner: Owner): Int? = try {
        if (!Sealion7HdmiProjection.isTestedFirmware() || owner.closeNotSent || loadOwner() != owner ||
            !owner.closing && (owner.legacyMapFocus || !Sealion7HdmiProjection.isEnabled(context))
        ) {
            RESULT_INSTRUMENT_GUARD_FAILED
        } else {
            savedModeDisposition(readAppSavedMode(context), owner)
        }
    } catch (_: Exception) {
        RESULT_INSTRUMENT_GUARD_FAILED
    }

    internal fun savedModeDisposition(mode: Int, owner: Owner): Int? = when {
        mode <= 0 -> RESULT_INSTRUMENT_GUARD_FAILED
        owner.rebootRestore -> if (mode == FULL) null else RESULT_INSTRUMENT_MODE_CHANGED
        mode == OFF -> null
        !owner.closing && mode == FULL -> RESULT_INSTRUMENT_ALREADY_FULL
        else -> RESULT_INSTRUMENT_MODE_CHANGED
    }

    @JvmStatic
    fun parseOwner(raw: String): Owner? = Owner.decode(raw)

    /** The QNX display-mode messages leave the saved Android setting untouched. */
    @JvmStatic
    fun commands(owner: Owner): List<Bundle> = commandBuilders(owner).map { it() }

    private fun commandBuilders(owner: Owner): List<() -> Bundle> = if (owner.closing) {
        listOfNotNull(
            { presentation(1) },
            { naviState(1) },
            if (owner.legacyMapFocus) ::abandonMapFocus else null,
            { mapFocusDirect(0) },
            { displayModeDirect(OFF) },
        )
    } else {
        listOf({ displayModeDirect(FULL) }, { presentation(6) }, { naviState(0) }, { mapFocusDirect(1) })
    }
}
