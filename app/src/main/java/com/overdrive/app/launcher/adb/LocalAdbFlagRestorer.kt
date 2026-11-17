package com.overdrive.app.launcher.adb

/**
 * Basic local recovery: read adb_enabled, require the existing
 * Settings permission, write 1, and read it back. This does not prove ADB authentication.
 */
internal class LocalAdbFlagRestorer(private val gateway: Gateway) {
    interface Gateway {
        fun readAdbEnabled(): Int?
        fun hasWriteSecureSettings(): Boolean
        fun writeAdbEnabled(): Boolean
    }

    enum class Outcome {
        ALREADY_ENABLED,
        ENABLED,
        MISSING_PERMISSION,
        READ_FAILED,
        WRITE_REJECTED,
        READBACK_FAILED,
        CANCELLED,
    }

    data class Result(
        val outcome: Outcome,
        val settingEnabled: Boolean = false,
        val writeAttempted: Boolean = false,
    )

    fun restore(isCurrent: () -> Boolean): Result {
        fun current(): Boolean = try { isCurrent() } catch (_: Exception) { false }
        if (!current()) return Result(Outcome.CANCELLED)
        val prior = try { gateway.readAdbEnabled() } catch (_: Exception) { null }
            ?: return Result(Outcome.READ_FAILED)
        if (!current()) return Result(Outcome.CANCELLED)
        // Match the reference's early return: an enabled flag needs neither a write nor
        // a Settings permission. The caller performs transport verification separately.
        if (prior == 1) return Result(Outcome.ALREADY_ENABLED, settingEnabled = true)
        val permitted = try { gateway.hasWriteSecureSettings() } catch (_: Exception) { false }
        if (!permitted) return Result(Outcome.MISSING_PERMISSION)
        if (!current()) return Result(Outcome.CANCELLED)
        val accepted = try { gateway.writeAdbEnabled() } catch (_: Exception) { false }
        val observed = try { gateway.readAdbEnabled() } catch (_: Exception) { null }
        if (!current()) {
            return Result(Outcome.CANCELLED, settingEnabled = observed == 1, writeAttempted = true)
        }
        return when {
            !accepted -> Result(Outcome.WRITE_REJECTED, observed == 1, writeAttempted = true)
            observed != 1 -> Result(Outcome.READBACK_FAILED, writeAttempted = true)
            else -> Result(Outcome.ENABLED, settingEnabled = true, writeAttempted = true)
        }
    }
}
