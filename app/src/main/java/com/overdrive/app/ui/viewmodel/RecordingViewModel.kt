package com.overdrive.app.ui.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.client.CameraDaemonClient
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.util.PreferencesManager
import com.overdrive.app.util.RecordingSizeFormatter
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ViewModel for recording screen state.
 */
class RecordingViewModel(app: Application) : AndroidViewModel(app) {
    
    companion object {
        private const val TAG = "Recording"
    }
    
    private val log = LogManager.getInstance()
    private val daemonClient = CameraDaemonClient()
    private val storageExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RecordingStorageInfo").apply { isDaemon = true }
    }
    
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording
    
    private val _selectedCameras = MutableLiveData<Set<Int>>()
    val selectedCameras: LiveData<Set<Int>> = _selectedCameras
    
    private val _duration = MutableLiveData(0L)
    val duration: LiveData<Long> = _duration
    
    private val _storageInfo = MutableLiveData<StorageInfo>()
    val storageInfo: LiveData<StorageInfo> = _storageInfo
    
    private val _recordingCameras = MutableLiveData<Set<Int>>(emptySet())
    val recordingCameras: LiveData<Set<Int>> = _recordingCameras
    
    private var recordingStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private val durationUpdater = object : Runnable {
        override fun run() {
            if (_isRecording.value == true) {
                _duration.value = (System.currentTimeMillis() - recordingStartTime) / 1000
                handler.postDelayed(this, 1000)
            }
        }
    }
    
    init {
        _selectedCameras.value = PreferencesManager.getSelectedCameras()
        updateStorageInfo()
        // Check initial recording status
        refreshRecordingStatus()
    }
    
    fun toggleCamera(cameraId: Int) {
        val newSelection = PreferencesManager.toggleCamera(cameraId)
        _selectedCameras.value = newSelection
    }
    
    fun isCameraSelected(cameraId: Int): Boolean {
        return cameraId in (_selectedCameras.value ?: emptySet())
    }
    
    fun startRecording() {
        if (_isRecording.value == true) return
        
        val cameras = _selectedCameras.value ?: setOf(1)
        if (cameras.isEmpty()) {
            log.warn(TAG, "No cameras selected for recording")
            return
        }
        
        log.info(TAG, "Starting recording for cameras: $cameras")
        
        daemonClient.startRecording(cameras, false, object : CameraDaemonClient.ResponseCallback {
            override fun onResponse(response: JSONObject) {
                val status = response.optString("status")
                if (status == "ok") {
                    handler.post {
                        _isRecording.value = true
                        recordingStartTime = System.currentTimeMillis()
                        _duration.value = 0
                        handler.post(durationUpdater)
                        
                        val recording = CameraDaemonClient.parseRecordingCameras(response)
                        _recordingCameras.value = recording
                        log.info(TAG, "Recording started: $recording")
                    }
                } else {
                    val error = response.optString("message", "Unknown error")
                    log.error(TAG, "Failed to start recording: $error")
                }
            }
            
            override fun onError(error: String) {
                log.error(TAG, "Recording start error: $error")
            }
        })
        
        updateStorageInfo()
    }
    
    fun stopRecording() {
        if (_isRecording.value != true) return
        
        log.info(TAG, "Stopping recording...")
        
        daemonClient.stopRecording(null, object : CameraDaemonClient.ResponseCallback {
            override fun onResponse(response: JSONObject) {
                handler.post {
                    _isRecording.value = false
                    handler.removeCallbacks(durationUpdater)
                    _duration.value = 0  // Reset timer to 0
                    _recordingCameras.value = emptySet()
                    log.info(TAG, "Recording stopped")
                }
            }
            
            override fun onError(error: String) {
                log.error(TAG, "Recording stop error: $error")
            }
        })
        
        updateStorageInfo()
    }
    
    fun refreshRecordingStatus() {
        daemonClient.getStatus(object : CameraDaemonClient.ResponseCallback {
            override fun onResponse(response: JSONObject) {
                handler.post {
                    val recording = CameraDaemonClient.parseRecordingCameras(response)
                    _recordingCameras.value = recording
                    _isRecording.value = recording.isNotEmpty()
                    
                    if (recording.isNotEmpty() && recordingStartTime == 0L) {
                        // Recording was already in progress
                        recordingStartTime = System.currentTimeMillis()
                        handler.post(durationUpdater)
                    }
                }
            }
            
            override fun onError(error: String) {
                // Daemon not running - that's OK
            }
        })
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
        handler.removeCallbacks(durationUpdater)
        storageExecutor.shutdownNow()
        daemonClient.destroy()
    }
    
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%02d:%02d", minutes, secs)
        }
    }
    
    data class StorageInfo(
        val usedBytes: Long,
        val availableBytes: Long,
        val totalBytes: Long
    ) {
        val usedFormatted: String get() = RecordingSizeFormatter.format(usedBytes)
        val availableFormatted: String get() = RecordingSizeFormatter.format(availableBytes)
        val totalFormatted: String get() = RecordingSizeFormatter.format(totalBytes)
        val usagePercent: Int get() = if (totalBytes > 0) ((usedBytes * 100) / totalBytes).toInt() else 0
    }
}
