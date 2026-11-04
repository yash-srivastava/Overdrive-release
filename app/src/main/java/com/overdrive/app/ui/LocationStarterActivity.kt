package com.overdrive.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.overdrive.app.services.LocationSidecarService

/**
 * Transparent activity that starts LocationSidecarService and immediately finishes.
 * Used by SentryDaemon to restart location service without bringing app to foreground.
 * 
 * This activity:
 * - Has no UI (transparent theme)
 * - Excluded from recents
 * - Starts location sidecar service
 * - Finishes immediately
 */
class LocationStarterActivity : Activity() {
    
    companion object {
        private const val TAG = "LocationStarterActivity"
        const val ACTION_START_LOCATION = "com.overdrive.app.START_LOCATION_SILENT"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "Location starter activity launched")

        // SECURITY (run-2 fix): only the shell daemon / system / ourselves may
        // trigger the location sidecar through this activity (the manifest also
        // requires DUMP, which third-party apps cannot hold).
        val callingUid = android.os.Binder.getCallingUid()
        if (callingUid != android.os.Process.myUid()
                && callingUid != 1000 && callingUid != 2000) {
            Log.w(TAG, "rejected START_LOCATION_SILENT from uid $callingUid")
            finish()
            return
        }

        // Start location sidecar service
        try {
            val serviceIntent = Intent(this, LocationSidecarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "LocationSidecarService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocationSidecarService: ${e.message}")
        }
        
        // Finish immediately - no UI shown
        finish()
    }
    
    override fun onPause() {
        super.onPause()
        // Ensure we don't show any transition animation
        overridePendingTransition(0, 0)
    }
}
