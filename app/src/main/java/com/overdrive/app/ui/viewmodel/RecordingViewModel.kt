package com.overdrive.app.ui.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.client.CameraDaemonClient
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ViewModel for dashboard recording status and storage information.
 */
class RecordingViewModel(app: Application) : AndroidViewModel(app) {
    
    private val daemonClient = CameraDaemonClient()
    private val storageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RecordingStorageInfo").apply { isDaemon = true }
    }
    
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording
    
    private val _storageInfo = MutableLiveData<StorageInfo>()
    val storageInfo: LiveData<StorageInfo> = _storageInfo
    
    private val handler = Handler(Looper.getMainLooper())
    init {
        updateStorageInfo()
        // Check initial recording status
        refreshRecordingStatus()
    }
    
    fun refreshRecordingStatus() {
        daemonClient.getStatus(object : CameraDaemonClient.ResponseCallback {
            override fun onResponse(response: JSONObject) {
                handler.post {
                    val recording = CameraDaemonClient.parseRecordingCameras(response)
                    _isRecording.value = recording.isNotEmpty()
                }
            }
            
            override fun onError(error: String) {
                // Daemon not running - that's OK
            }
        })
    }
    
    /**
     * The state above is read once when the view model is created, often before the
     * daemon starts recording. Re-read it when the dashboard's status poll disagrees.
     */
    fun reconcileWithStatus(activeRecordingCameras: Int?) {
        if (activeRecordingCameras == null) return
        if ((activeRecordingCameras > 0) != (_isRecording.value == true)) {
            refreshRecordingStatus()
        }
    }

    fun updateStorageInfo() {
        if (storageExecutor.isShutdown) return
        storageExecutor.execute {
            val info = try {
                val storageManager = com.overdrive.app.storage.StorageManager.getInstance()
                val used = storageManager.recordingsSize
                val stat = StatFs(storageManager.recordingsPath)
                StorageInfo(
                    usedBytes = used,
                    availableBytes = stat.availableBytes,
                    totalBytes = stat.totalBytes,
                )
            } catch (_: Exception) {
                fallbackStorageInfo()
            }
            _storageInfo.postValue(info)
        }
    }

    private fun fallbackStorageInfo(): StorageInfo {
        return try {
            val path = getApplication<Application>().getExternalFilesDir(null)?.absolutePath
                ?: "/sdcard"
            val stat = StatFs(path)
            val available = stat.availableBytes
            val total = stat.totalBytes
            StorageInfo(
                usedBytes = total - available,
                availableBytes = available,
                totalBytes = total,
            )
        } catch (_: Exception) {
            StorageInfo(0, 0, 0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        storageExecutor.shutdownNow()
        daemonClient.destroy()
    }
    
    data class StorageInfo(
        val usedBytes: Long,
        val availableBytes: Long,
        val totalBytes: Long
    )
}
