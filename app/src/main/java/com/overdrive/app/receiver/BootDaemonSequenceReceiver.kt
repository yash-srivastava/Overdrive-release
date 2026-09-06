package com.overdrive.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.overdrive.app.ui.daemon.DaemonStartupManager

/**
 * Out-of-process carrier for the staggered boot-daemon sequence.
 *
 * DaemonStartupManager.initializeOnBoot() used to stagger the camera/sentry/
 * acc-sentry launches and the health-check start with plain
 * `Handler(Looper.getMainLooper()).postDelayed(..., 45000/60000/90000)`. Those
 * callbacks are tied to the calling app process's main Looper: if the process
 * dies or is restarted anywhere in that 45-90s window — a crash, an OS kill,
 * or the kind of transient system_server instability this device is known to
 * hit right after boot — every pending callback simply evaporates with it.
 * Nothing logs the loss and nothing retries; the daemons just never start,
 * even though the app process itself may look perfectly healthy afterward.
 *
 * This receiver moves that staggering onto AlarmManager instead, mirroring
 * ProcessRevivalReceiver's already-proven pattern: an alarm survives the
 * originating process dying (Android resurrects the process to deliver it),
 * so each stage fires on schedule regardless of what happened to the process
 * that originally requested it.
 *
 * Each stage builds its own throwaway DaemonStartupManager instance rather
 * than reaching into the original one (which may no longer exist) — safe
 * because every stage method is itself idempotent (kill-then-relaunch, or a
 * health-check tick), so firing it against an already-healthy stack is a
 * harmless no-op/restart, not a correctness issue.
 */
class BootDaemonSequenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val stage = intent.getIntExtra(EXTRA_STAGE, -1)
        Log.i(TAG, "Boot daemon sequence alarm fired: stage=$stage")

        val manager = DaemonStartupManager(appContext, null)
        try {
            when (stage) {
                STAGE_CORE -> manager.startCoreDaemonsViaAdb()
                STAGE_OPTIONAL -> manager.startOptionalDaemonsViaAdb()
                STAGE_HEALTHCHECK -> manager.startDaemonHealthCheck()
                else -> Log.w(TAG, "Unknown boot sequence stage: $stage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot sequence stage $stage failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootDaemonSequence"
        private const val ACTION = "com.overdrive.app.action.BOOT_DAEMON_SEQUENCE"
        private const val EXTRA_STAGE = "stage"

        const val STAGE_CORE = 1
        const val STAGE_OPTIONAL = 2
        const val STAGE_HEALTHCHECK = 3

        private const val REQUEST_CODE_CORE = 0xD101
        private const val REQUEST_CODE_OPTIONAL = 0xD102
        private const val REQUEST_CODE_HEALTHCHECK = 0xD103

        private fun buildPendingIntent(context: Context, stage: Int, requestCode: Int): PendingIntent? {
            val intent = Intent(context, BootDaemonSequenceReceiver::class.java).apply {
                action = ACTION
                putExtra(EXTRA_STAGE, stage)
                // Distinct data URIs so the three stage PendingIntents don't
                // collide under PendingIntent equality rules (same action/extras
                // shape otherwise).
                data = Uri.parse("overdrive://boot-sequence/$stage")
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun scheduleStage(context: Context, alarmManager: AlarmManager, stage: Int, requestCode: Int, delayMs: Long) {
            val pi = buildPendingIntent(context, stage, requestCode) ?: return
            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (e: SecurityException) {
                // API 31+ without SCHEDULE_EXACT_ALARM — fall back to inexact rather
                // than dropping the stage entirely.
                Log.w(TAG, "Exact alarm denied for stage $stage, falling back to inexact: ${e.message}")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }

        /**
         * Schedule all three boot-sequence stages via AlarmManager instead of an
         * in-process Handler chain. Delays match the original postDelayed values
         * (45s / 60s / 90s) so boot timing/system-stabilization behavior is
         * unchanged — only the delivery mechanism is more durable.
         */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager == null) {
                Log.w(TAG, "AlarmManager unavailable — boot daemon sequence cannot be scheduled")
                return
            }
            scheduleStage(context, alarmManager, STAGE_CORE, REQUEST_CODE_CORE, 45_000L)
            scheduleStage(context, alarmManager, STAGE_OPTIONAL, REQUEST_CODE_OPTIONAL, 60_000L)
            scheduleStage(context, alarmManager, STAGE_HEALTHCHECK, REQUEST_CODE_HEALTHCHECK, 90_000L)
        }
    }
}
