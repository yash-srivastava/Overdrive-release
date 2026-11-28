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

    init {
        _currentUrl.value = PreferencesManager.getLastTunnelUrl()
    }

    fun setTunnelUrl(url: String?) {
        if (url != null) {
            PreferencesManager.setLastTunnelUrl(url)
        }
        _currentUrl.value = url
    }

    fun setCurrentUrl(url: String?) {
        _currentUrl.value = url
    }
}
