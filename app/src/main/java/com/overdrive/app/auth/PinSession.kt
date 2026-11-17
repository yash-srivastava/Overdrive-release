package com.overdrive.app.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log

/**
 * Process-singleton holding the in-memory unlock state for the OverDrive
 * UI. Companion to [PinManager], which owns persisted PIN material.
 *
 * Design points:
 *  - **In-memory only.** A process restart re-locks the UI. That's the
 *    correct behavior — if a daemon supervisor force-stops + relaunches
 *    the app, the user should re-authenticate before regaining UI
 *    access.
 *  - **Single source of truth.** MainActivity is the sole gate. The
 *    surveillance / deterrent / blocker / location-starter activities
 *    never call into this object.
 *  - **Idle timeout.** Tracked via [pausedRealtime]; on resume, if
 *    `now - pausedRealtime > autoLockMs`, the session is locked. Uses
 *    [SystemClock.elapsedRealtime] (monotonic) so a wall-clock change
 *    doesn't undo the lock.
 *  - **Screen off auto-lock.** Receiver registered from MainActivity
 *    locks the session the moment the panel turns off, regardless of
 *    the configured timeout. Keeps a momentarily-locked-then-immediately-
 *    unlocked window from existing.
 */
object PinSession {

    private const val TAG = "PinSession"

    /** -1 → never auto-lock based on time (still locks on screen-off). */
    const val AUTOLOCK_NEVER: Long = -1L

    @Volatile private var unlockedRealtime: Long = 0L
    @Volatile private var pausedRealtime: Long = 0L
    @Volatile private var returnGraceUntil: Long = 0L

    private var screenReceiver: BroadcastReceiver? = null

    /** True iff the user has unlocked AND the idle timeout hasn't elapsed. */
    @Synchronized
    fun isUnlocked(): Boolean = unlockedRealtime > 0L

    /** Mark the session as unlocked (called from PinLockActivity on success). */
    @Synchronized
    fun markUnlocked() {
        unlockedRealtime = SystemClock.elapsedRealtime()
        pausedRealtime = 0L
        Log.i(TAG, "Session unlocked")
    }

    /** Force-lock. Called from screen-off receiver and from explicit user actions. */
    @Synchronized
    fun lock() {
        if (unlockedRealtime > 0L) Log.i(TAG, "Session locked")
        unlockedRealtime = 0L
        pausedRealtime = 0L
        returnGraceUntil = 0L
    }

    /**
     * OverDrive itself is opening another app's screen (the setup guide's BYD settings): the first
     * return within [windowMs] keeps an unlocked session. Screen-off and a locked session still gate.
     */
    @Synchronized
    fun expectReturn(windowMs: Long) {
        if (unlockedRealtime <= 0L) return
        returnGraceUntil = SystemClock.elapsedRealtime() + windowMs
    }

    /**
     * Record the pause moment so onResume can decide whether to re-lock.
     *
     * Includes a grace window after the most recent unlock — otherwise
     * the brief MainActivity.onPause that fires when PinLockActivity is
     * launching ON TOP of MainActivity would record pausedRealtime
     * AFTER markUnlocked() cleared it, then on the next onResume the
     * autoLockMs=0 ("Lock immediately") branch would re-lock instantly,
     * looping the user.
     *
     * 750ms is generous; the activity-transition window is normally
     * <100ms, but a YOLO/encoder burst on the BYD head unit can stall
     * system_server >300ms. The grace value only suppresses a phantom
     * pause record — it doesn't extend the unlock duration.
     */
    @Synchronized
    fun notePaused() {
        if (unlockedRealtime <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - unlockedRealtime < UNLOCK_GRACE_MS) return
        pausedRealtime = now
    }

    private const val UNLOCK_GRACE_MS: Long = 750L

    /**
     * Returns true if MainActivity should redirect to PinLockActivity.
     * Called from MainActivity.onResume / onNewIntent.
     *
     * Decision tree:
     *  1. PIN not enabled → never gate.
     *  2. Never unlocked this process → gate.
     *  3. autoLockMs == 0 → always gate after a pause (lock-immediately).
     *  4. autoLockMs == AUTOLOCK_NEVER → only screen-off can re-lock; if
     *     still unlocked here, don't gate.
     *  5. Otherwise gate iff (now - pausedRealtime) > autoLockMs.
     */
    @Synchronized
    fun shouldGate(autoLockMs: Long): Boolean {
        if (!PinManager.isEnabled()) return false
        if (unlockedRealtime <= 0L) return true
        if (autoLockMs == AUTOLOCK_NEVER) return false

        val grace = returnGraceUntil
        if (grace > 0L) {
            returnGraceUntil = 0L
            if (SystemClock.elapsedRealtime() <= grace) {
                pausedRealtime = 0L
                return false
            }
        }

        // No pause recorded yet (unlock just happened, no onPause/onResume cycle) → don't gate.
        if (pausedRealtime <= 0L) return false

        if (autoLockMs <= 0L) {
            // Lock immediately on any pause.
            lock()
            return true
        }

        val now = SystemClock.elapsedRealtime()
        val idle = now - pausedRealtime
        if (idle >= autoLockMs) {
            lock()
            return true
        }
        return false
    }

    /**
     * Register the global ACTION_SCREEN_OFF receiver. Idempotent — calling
     * twice from MainActivity.onCreate after a config-change recreate is
     * a no-op. The receiver lives for the application process lifetime;
     * we deliberately do NOT unregister on Activity destroy because the
     * process survives Activity destruction and we want to keep listening
     * (a backgrounded app whose screen turns off should re-lock).
     */
    fun ensureScreenOffReceiverRegistered(appContext: Context) {
        synchronized(this) {
            if (screenReceiver != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) lock()
                }
            }
            try {
                appContext.applicationContext.registerReceiver(
                    r,
                    IntentFilter(Intent.ACTION_SCREEN_OFF)
                )
                screenReceiver = r
                Log.i(TAG, "Screen-off receiver registered")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register screen-off receiver: ${t.message}")
            }
        }
    }
}
