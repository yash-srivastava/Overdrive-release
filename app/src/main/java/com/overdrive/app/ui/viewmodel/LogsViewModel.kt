package com.overdrive.app.ui.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.overdrive.app.ui.model.LogEntry
import com.overdrive.app.ui.model.LogLevel
import java.util.ArrayDeque

/**
 * ViewModel for logs panel state. The history is always collected, but list
 * snapshots are only built while a lifecycle observer is active.
 */
class LogsViewModel : ViewModel() {

    companion object {
        private const val MAX_LOGS = 500
        private const val PUBLISH_INTERVAL_MS = 250L
    }

    private val allLogs = ArrayDeque<LogEntry>(MAX_LOGS)
    private val handler = Handler(Looper.getMainLooper())
    // Guarded by allLogs, including scheduling/cancellation of the single task.
    private var activeSnapshots = 0
    private var pendingPublish: Runnable? = null
    private var cleared = false

    private val _filter = MutableLiveData<String?>(null)
    val filter: LiveData<String?> = _filter

    private val _logs = SnapshotLiveData(filtered = false)
    val logs: LiveData<List<LogEntry>> = _logs

    private val _filteredLogs = SnapshotLiveData(filtered = true)
    val filteredLogs: LiveData<List<LogEntry>> = _filteredLogs

    // LiveData calls these hooks on the main thread. Refresh before delivering
    // the first value, and don't retain an obsolete second history offscreen.
    private inner class SnapshotLiveData(private val filtered: Boolean) :
        MutableLiveData<List<LogEntry>>(emptyList()) {
        override fun onActive() {
            val snapshot = synchronized(allLogs) {
                activeSnapshots++
                allLogs.toList()
            }
            value = if (filtered) applyFilter(snapshot) else snapshot
        }

        override fun onInactive() {
            synchronized(allLogs) {
                activeSnapshots--
                if (activeSnapshots == 0) {
                    cancelPublishLocked()
                }
            }
            value = emptyList()
        }
    }

    fun addLog(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            level = level
        )
        synchronized(allLogs) {
            if (cleared) return
            if (allLogs.size == MAX_LOGS) allLogs.removeFirst()
            allLogs.addLast(entry)
            schedulePublishLocked(PUBLISH_INTERVAL_MS)
        }
    }

    fun debug(tag: String, message: String) = addLog(tag, message, LogLevel.DEBUG)
    fun info(tag: String, message: String) = addLog(tag, message, LogLevel.INFO)
    fun warn(tag: String, message: String) = addLog(tag, message, LogLevel.WARN)
    fun error(tag: String, message: String) = addLog(tag, message, LogLevel.ERROR)

    fun clearLogs() {
        synchronized(allLogs) {
            allLogs.clear()
            schedulePublishLocked(0L)
        }
    }

    fun setFilter(component: String?) {
        _filter.value = component
        synchronized(allLogs) {
            schedulePublishLocked(0L)
        }
    }

    private fun schedulePublishLocked(delayMs: Long) {
        if (cleared || activeSnapshots == 0) return
        if (pendingPublish != null) {
            if (delayMs != 0L) return
            // Explicit clear/filter actions should not wait for the next batch.
            cancelPublishLocked()
        }
        val task = object : Runnable {
            override fun run() = publishLogs(this)
        }
        pendingPublish = if (handler.postDelayed(task, delayMs)) task else null
    }

    private fun cancelPublishLocked() {
        pendingPublish?.let { handler.removeCallbacks(it) }
        pendingPublish = null
    }

    private fun publishLogs(task: Runnable) {
        val snapshot = synchronized(allLogs) {
            // A producer may cancel/repost after the looper dequeues a task
            // but before it acquires this lock. Never consume the newer task.
            if (pendingPublish !== task) return
            pendingPublish = null
            if (cleared || activeSnapshots == 0) return
            allLogs.toList()
        }
        // Only the main thread reads the filter and publishes the LiveData.
        // Keep callbacks outside the history lock; producers can keep logging.
        if (_logs.hasActiveObservers()) _logs.value = snapshot
        if (_filteredLogs.hasActiveObservers()) _filteredLogs.value = applyFilter(snapshot)
    }

    private fun applyFilter(snapshot: List<LogEntry>): List<LogEntry> {
        val currentFilter = _filter.value
        return if (currentFilter.isNullOrBlank()) snapshot else
            snapshot.filter { it.tag.contains(currentFilter, ignoreCase = true) }
    }

    /** Get all unique tags for filter dropdown, even when no list is observed. */
    fun getAvailableTags(): List<String> = synchronized(allLogs) {
        allLogs.map { it.tag }.distinct().sorted()
    }

    override fun onCleared() {
        synchronized(allLogs) {
            cleared = true
            cancelPublishLocked()
            allLogs.clear()
        }
        super.onCleared()
    }
}
