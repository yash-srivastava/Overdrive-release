package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.overdrive.app.R
import com.overdrive.app.config.ConfigManager
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.logging.LogLevel
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.util.RecordingScanner
import com.overdrive.app.ui.util.RecordingsApiClient
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Settings → Privacy & data pane.
 *
 * Hosts the on-device privacy stance, a live local-storage summary
 * (clip count + total size), the log-verbosity picker, and the
 * destructive reset action.
 *
 * The reset button delegates to [MainActivity.invokeResetDataDialog],
 * preserving the exact behaviour of the legacy portrait "Reset data"
 * card.
 *
 * Storage values are populated by querying [RecordingScanner] (which is
 * already cached for ~5 seconds and dedupes across SD/internal). On any
 * exception the labels gracefully fall back to "Unavailable" so a
 * scanner regression never blanks the page.
 */
class SettingsPrivacyFragment : Fragment() {

    /**
     * Single-thread executor for the storage scan. Off-main is mandatory:
     * StorageManager's first-init in the UI process forks shell processes
     * (`sm list-volumes`, /proc/mounts probes, sleep loops to await SD-card
     * mount). On the main thread that's an ANR — which the user perceives
     * as a crash when they tap the Privacy & data row.
     */
    private var scanExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_privacy, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.btnResetData).setOnClickListener {
            (activity as? MainActivity)?.invokeResetDataDialog()
        }
        setupLogLevel(view)
        setupScreenshotPrivacyMode(view)
        populateStorage(view)
    }

    override fun onResume() {
        super.onResume()
        // Re-populate on resume: the user may have deleted clips on the
        // recordings page and come back here expecting the totals to
        // reflect that.
        view?.let { populateStorage(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanExecutor?.shutdownNow()
        scanExecutor = null
    }

    /**
     * Wire the log-verbosity picker. Writes through [ConfigManager.updateLoggingConfig],
     * whose listener pushes the new config into the running LogManager, so the change
     * takes effect without an app restart.
     *
     * NOTE: release builds strip all LogManager calls via proguard-rules-strip-logs.pro,
     * so this control only has an observable effect on the braveheart channel.
     */
    private fun setupLogLevel(root: View) {
        val group = root.findViewById<MaterialButtonToggleGroup>(R.id.toggleLogLevel) ?: return
        val desc = root.findViewById<TextView>(R.id.tvLogLevelDesc) ?: return
        val note = root.findViewById<TextView>(R.id.tvLogLevelNote) ?: return
        val ctx = context?.applicationContext ?: return

        fun applyCopy(level: LogLevel) {
            desc.setText(
                when (level) {
                    LogLevel.DEBUG -> R.string.settings_privacy_log_level_debug_desc
                    LogLevel.INFO -> R.string.settings_privacy_log_level_info_desc
                    LogLevel.WARN -> R.string.settings_privacy_log_level_warn_desc
                    LogLevel.ERROR -> R.string.settings_privacy_log_level_error_desc
                }
            )
            // Advisory at both ends: verbose costs retained history, near-silent costs the
            // ability to diagnose anything later. INFO is the only quiet-and-safe choice.
            when (level) {
                LogLevel.DEBUG -> {
                    note.setText(R.string.settings_privacy_log_level_note_verbose)
                    note.visibility = View.VISIBLE
                }
                LogLevel.WARN, LogLevel.ERROR -> {
                    note.setText(R.string.settings_privacy_log_level_note_quiet)
                    note.visibility = View.VISIBLE
                }
                LogLevel.INFO -> note.visibility = View.GONE
            }
        }

        val current = ConfigManager.getInstance(ctx).getLoggingConfig().minLevel

        // Seed BEFORE registering the listener: addOnButtonCheckedListener fires on a
        // programmatic check() too, which would re-persist the value and re-schedule the
        // LogCleaner worker on every visit to the page.
        group.check(buttonIdFor(current))
        applyCopy(current)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            // Deselection of the outgoing button also fires; only act on the new selection.
            if (!isChecked) return@addOnButtonCheckedListener
            val chosen = levelForButtonId(checkedId) ?: return@addOnButtonCheckedListener
            val cfg = ConfigManager.getInstance(ctx)
            val existing = cfg.getLoggingConfig()
            applyCopy(chosen)
            if (existing.minLevel == chosen) return@addOnButtonCheckedListener
            // Logged before the write, at WARN, so the transition is recorded under the OLD
            // gate — afterwards the new stricter gate would drop its own audit line.
            LogManager.getInstance().warn(TAG, "Log level changed: ${existing.minLevel} → $chosen")
            cfg.updateLoggingConfig(existing.copy(minLevel = chosen))
            // Daemons take the level from the unified config. That write is a blocking IPC call,
            // so it runs off the UI thread; a single writer keeps rapid taps in order.
            LOG_LEVEL_WRITER.execute { UnifiedConfigManager.setLogLevel(chosen.name) }
        }
    }

    private fun buttonIdFor(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> R.id.btnLogLevelDebug
        LogLevel.INFO -> R.id.btnLogLevelInfo
        LogLevel.WARN -> R.id.btnLogLevelWarn
        LogLevel.ERROR -> R.id.btnLogLevelError
    }

    private fun levelForButtonId(id: Int): LogLevel? = when (id) {
        R.id.btnLogLevelDebug -> LogLevel.DEBUG
        R.id.btnLogLevelInfo -> LogLevel.INFO
        R.id.btnLogLevelWarn -> LogLevel.WARN
        R.id.btnLogLevelError -> LogLevel.ERROR
        else -> null
    }

    private fun setupScreenshotPrivacyMode(root: View) {
        val toggle = root.findViewById<MaterialSwitch>(R.id.switchScreenshotPrivacy) ?: return
        toggle.isChecked = UnifiedConfigManager.isScreenshotPrivacyModeEnabled()

        var reverting = false
        toggle.setOnCheckedChangeListener { _, enabled ->
            if (reverting) return@setOnCheckedChangeListener
            if (UnifiedConfigManager.setScreenshotPrivacyModeEnabled(enabled)) {
                (activity as? MainActivity)?.applyScreenshotPrivacyMode(enabled)
                LogManager.getInstance().info(
                    TAG,
                    "Screenshot privacy mode ${if (enabled) "enabled" else "disabled"}"
                )
            } else {
                reverting = true
                toggle.isChecked = !enabled
                reverting = false
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.settings_screenshot_privacy_save_failed,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun populateStorage(root: View) {
        val tvClips = root.findViewById<TextView>(R.id.tvPrivacyClipsValue) ?: return
        val tvSize = root.findViewById<TextView>(R.id.tvPrivacySizeValue) ?: return
        val ctx = context?.applicationContext ?: return

        val executor = scanExecutor
            ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "PrivacyStorageScan").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }.also { scanExecutor = it }

        executor.execute {
            val result: Pair<Int, Long>? = try {
                // Ask the daemon first so we can tell "index down" (counts
                // unknown) apart from "genuinely no clips". scanRecordings()
                // returns an empty list in BOTH cases, which would render an
                // authoritative "0 clips · 0 B" while recordings sit on disk.
                if (RecordingsApiClient.fetchStats()?.indexUnavailable == true) {
                    null
                } else {
                    val all = RecordingScanner.scanRecordings(ctx)
                    all.size to all.sumOf { it.sizeBytes }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Storage scan failed: ${t.message}")
                null
            }
            mainHandler.post {
                if (!isAdded || view == null) return@post
                if (result == null) {
                    tvClips.setText(R.string.settings_privacy_storage_unavailable)
                    tvSize.setText(R.string.settings_privacy_storage_unavailable)
                } else {
                    val (count, totalBytes) = result
                    tvClips.text = formatClipCount(count)
                    tvSize.text = formatSize(totalBytes)
                }
            }
        }
    }

    private fun formatClipCount(count: Int): String {
        val res = if (count == 1) {
            R.string.settings_privacy_storage_count_format
        } else {
            R.string.settings_privacy_storage_count_format_plural
        }
        return getString(res, count)
    }

    /**
     * Compact "B / KB / MB / GB / TB" formatter — same conventions used
     * elsewhere in the app (storage screen, recording library footer).
     * Intentionally locale-agnostic for the unit suffix; the number gets
     * locale-formatted via [String.format] with the default locale.
     */
    private fun formatSize(bytes: Long): String {
        if (bytes < 0L) return getString(R.string.settings_privacy_storage_unavailable)
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        if (gb < 1024.0) return String.format(Locale.getDefault(), "%.2f GB", gb)
        val tb = gb / 1024.0
        return String.format(Locale.getDefault(), "%.2f TB", tb)
    }

    private companion object {
        const val TAG = "SettingsPrivacy"
        val LOG_LEVEL_WRITER: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "LogLevelPersist").apply { isDaemon = true }
        }
    }
}
