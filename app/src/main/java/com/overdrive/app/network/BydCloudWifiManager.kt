package com.overdrive.app.network

import android.content.Context
import com.overdrive.app.launcher.AdbShellExecutor

/**
 * Best-effort compatibility switch for keeping Android cellular data available
 * while Wi-Fi is connected.
 *
 * This deliberately does NOT change the mobile-data master switch, APN settings,
 * vendor properties, modem state, or any BYD vehicle setting. Some BYD firmware
 * explicitly tears down its separate function APN when Wi-Fi connects; the
 * Android `mobile_data_always_on` flag cannot override that vendor policy.
 * APN3 properties are read-only diagnostics, not a stable Android API.
 *
 * The original Android setting is saved before the first write and restored
 * exactly when the user turns this feature off. No background polling or
 * automatic network switching is performed.
 */
class BydCloudWifiManager(context: Context) {

    data class Snapshot(
        val mobileDataAlwaysOn: String,
        val mobileData: String,
        val apn3State: String,
        val apn3Interface: String,
        val managedByOverdrive: Boolean,
        val error: String? = null
    )

    data class OperationResult(val success: Boolean, val message: String)

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val adb = AdbShellExecutor(appContext)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_MANAGED, false)

    /** Read-only snapshot. ADB failures are returned as a visible error state. */
    fun snapshot(): Snapshot {
        return try {
            val values = parseStatusOutput(runShell(STATUS_COMMAND))
            Snapshot(
                mobileDataAlwaysOn = values["mobile_data_always_on"] ?: "unknown",
                mobileData = values["mobile_data"] ?: "unknown",
                apn3State = values["apn3_state"]?.lowercase() ?: "unknown",
                apn3Interface = values["apn3_iface"] ?: "",
                managedByOverdrive = isEnabled()
            )
        } catch (t: Throwable) {
            Snapshot("unknown", "unknown", "unknown", "", isEnabled(), t.message ?: "ADB unavailable")
        }
    }

    /** Enable Android's standard keep-mobile-data-active flag, preserving its prior value. */
    @Synchronized
    fun enable(): OperationResult {
        val before = try {
            parseStatusOutput(runShell(STATUS_COMMAND))
        } catch (t: Throwable) {
            return OperationResult(false, "ADB is unavailable: ${t.message ?: "connection failed"}")
        }

        if (!preferences.getBoolean(KEY_SNAPSHOT_SAVED, false)) {
            val original = before["mobile_data_always_on"] ?: "unknown"
            if (original !in VALID_SETTING_VALUES) {
                return OperationResult(false, "Could not read the original Android setting; nothing was changed.")
            }
            val saved = preferences.edit()
                .putString(KEY_ORIGINAL_VALUE, original)
                .putBoolean(KEY_SNAPSHOT_SAVED, true)
                .commit()
            if (!saved) {
                return OperationResult(false, "Could not save the original setting; nothing was changed.")
            }
        }

        return try {
            writeSetting("1")
            val persisted = preferences.edit().putBoolean(KEY_MANAGED, true).commit()
            if (!persisted) {
                val rollback = rollbackAndClearSnapshot()
                OperationResult(
                    false,
                    if (rollback == null) {
                        "The Android setting was applied, but its state could not be saved. The previous value was restored."
                    } else {
                        "The Android setting was applied, but its state could not be saved. Automatic restoration failed: $rollback. Turn the switch off again after ADB reconnects."
                    }
                )
            } else {
                OperationResult(true, "Android will keep mobile data available while Wi-Fi is connected, where the firmware permits it.")
            }
        } catch (t: Throwable) {
            val rollback = rollbackAndClearSnapshot()
            OperationResult(
                false,
                "Could not enable the Android setting: ${t.message ?: "unknown error"}" +
                    (if (rollback == null) " The previous value was restored." else " Automatic restoration failed: $rollback. Turn the switch off again after ADB reconnects.")
            )
        }
    }

    /** Restore exactly the value that was present before OverDrive enabled the feature. */
    @Synchronized
    fun disable(): OperationResult {
        if (!preferences.getBoolean(KEY_SNAPSHOT_SAVED, false)) {
            if (isEnabled()) {
                return OperationResult(false, "The original Android setting is missing. The current system value was left unchanged.")
            }
            return OperationResult(true, "No saved Android setting needed restoration.")
        }
        return try {
            restoreSavedSetting()
            val cleared = preferences.edit()
                .remove(KEY_ORIGINAL_VALUE)
                .remove(KEY_SNAPSHOT_SAVED)
                .putBoolean(KEY_MANAGED, false)
                .commit()
            if (!cleared) {
                OperationResult(false, "The previous Android setting was restored, but OverDrive could not clear its saved state. Retry once ADB is available.")
            } else {
                OperationResult(true, "The previous Android mobile-data setting was restored.")
            }
        } catch (t: Throwable) {
            OperationResult(false, "Could not restore the previous Android setting: ${t.message ?: "unknown error"}")
        }
    }

    /** Best-effort rollback after a failed enable; retain the snapshot if restoration fails. */
    private fun rollbackAndClearSnapshot(): String? {
        return try {
            restoreSavedSetting()
            val cleared = preferences.edit()
                .remove(KEY_ORIGINAL_VALUE)
                .remove(KEY_SNAPSHOT_SAVED)
                .putBoolean(KEY_MANAGED, false)
                .commit()
            if (cleared) null else {
                preferences.edit().putBoolean(KEY_MANAGED, true).commit()
                "could not clear the saved state"
            }
        } catch (t: Throwable) {
            // Keep the switch retryable if ADB disappeared during rollback.
            preferences.edit().putBoolean(KEY_MANAGED, true).commit()
            t.message ?: "restoration failed"
        }
    }

    private fun restoreSavedSetting() {
        val original = preferences.getString(KEY_ORIGINAL_VALUE, null)
            ?: throw IllegalStateException("Saved original setting is missing; current value was left unchanged.")
        if (original !in VALID_SETTING_VALUES) {
            throw IllegalStateException("Saved original setting is invalid; current value was left unchanged.")
        }
        writeSetting(original)
    }

    private fun writeSetting(value: String) {
        require(value == "0" || value == "1" || value == "null")
        val command = if (value == "null") {
            "settings delete global mobile_data_always_on >/dev/null 2>&1; " +
                "[ \"\$(settings get global mobile_data_always_on 2>/dev/null)\" = \"null\" ]"
        } else {
            "settings put global mobile_data_always_on $value && " +
                "[ \"\$(settings get global mobile_data_always_on 2>/dev/null)\" = \"$value\" ]"
        }
        val result = adb.executeSync(command, COMMAND_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw IllegalStateException("ADB setting command failed (exit ${result.exitCode}): ${result.output.trim()}")
        }
    }

    private fun runShell(command: String): String {
        val result = adb.executeSync(command, COMMAND_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw IllegalStateException("ADB status command failed (exit ${result.exitCode}): ${result.output.trim()}")
        }
        return result.output
    }

    companion object {
        private const val PREFS_NAME = "byd_cloud_wifi"
        private const val KEY_MANAGED = "managed_by_overdrive"
        private const val KEY_SNAPSHOT_SAVED = "original_setting_saved"
        private const val KEY_ORIGINAL_VALUE = "original_mobile_data_always_on"
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private val VALID_SETTING_VALUES = setOf("0", "1", "null")

        private const val STATUS_COMMAND =
            "echo mobile_data_always_on=\$(settings get global mobile_data_always_on 2>/dev/null); " +
                "echo mobile_data=\$(settings get global mobile_data 2>/dev/null); " +
                "echo apn3_state=\$(getprop net.lte.apn3.state 2>/dev/null); " +
                "echo apn3_iface=\$(getprop net.lte.apn3.iface 2>/dev/null)"

        /** Pure parser kept visible to unit tests; values are emitted by the fixed status command. */
        internal fun parseStatusOutput(output: String): Map<String, String> {
            val parsed = LinkedHashMap<String, String>()
            output.lineSequence().forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (key.isNotEmpty()) parsed[key] = value.ifEmpty { "unknown" }
            }
            return parsed
        }
    }
}
