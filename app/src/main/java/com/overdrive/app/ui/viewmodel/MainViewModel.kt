package com.overdrive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.ui.util.PreferencesManager

/**
 * ViewModel for main app state shared across screens.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _currentUrl = MutableLiveData<String?>()
    val currentUrl: LiveData<String?> = _currentUrl

    private val _tunnelUrl = MutableLiveData<String?>()
    val tunnelUrl: LiveData<String?> = _tunnelUrl

    init {
        _tunnelUrl.value = PreferencesManager.getLastTunnelUrl()
        _currentUrl.value = _tunnelUrl.value
    }

    fun setTunnelUrl(url: String?) {
        _tunnelUrl.value = url
        if (url != null) {
            PreferencesManager.setLastTunnelUrl(url)
        }
        _currentUrl.value = url
    }

    fun setCurrentUrl(url: String?) {
        _currentUrl.value = url
    }
}
