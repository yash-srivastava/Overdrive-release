package com.overdrive.app.briefing.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Display
import com.overdrive.app.navmap.NavTripStore
import com.overdrive.app.navmap.RoadSenseMapActivity
import com.overdrive.app.navmap.nav.SearchResult
import com.overdrive.app.telenav.TelenavActions
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.voice.TripBriefingAsk
import java.util.Locale

/** Starts directions in the app the driver picked (dev/trip-briefing/00-DESIGN.md §6). */
object TripBriefingLauncher {

    private const val TAG = "TripBriefing"
    private const val WAZE_PKG = "com.waze"
    private const val GMAPS_PKG = "com.google.android.apps.maps"

    /** Installed navigation apps, [preferred] first; OverDrive first when [preferred] is missing. */
    @JvmStatic
    fun installedApps(ctx: Context, preferred: String?): List<String> {
        val all = ArrayList<String>(4)
        all.add(TripBriefingAsk.APP_OVERDRIVE)
        if (resolves(ctx, wazeIntent(0.0, 0.0))) all.add(TripBriefingAsk.APP_WAZE)
        if (resolves(ctx, gmapsIntent(0.0, 0.0))) all.add(TripBriefingAsk.APP_GMAPS)
        if (runCatching { TelenavActions.isAvailable(ctx) }.getOrDefault(false)) all.add(TripBriefingAsk.APP_CAR)
        val first = if (preferred != null && preferred in all) preferred else TripBriefingAsk.APP_OVERDRIVE
        return listOf(first) + all.filter { it != first }
    }

    /** Main thread. False when nothing could be started. */
    @JvmStatic
    fun start(ctx: Context, app: String, label: String, lat: Double, lng: Double): Boolean {
        val c = ctx.applicationContext ?: ctx
        return try {
            when (app) {
                TripBriefingAsk.APP_WAZE -> launch(c, wazeIntent(lat, lng)) || startOverDrive(c, label, lat, lng)
                TripBriefingAsk.APP_GMAPS -> launch(c, gmapsIntent(lat, lng)) || startOverDrive(c, label, lat, lng)
                TripBriefingAsk.APP_CAR -> {
                    // blocking: foregrounds Telenav and waits on its service
                    Thread({
                        try {
                            TelenavActions.navigate(c, label, lat, lng, true)
                        } catch (t: Throwable) {
                            Log.w(TAG, "car navigation failed: ${t.message}")
                        }
                    }, "TripBriefingCarNav").start()
                    true
                }
                else -> startOverDrive(c, label, lat, lng)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start $app failed: ${t.message}")
            false
        }
    }

    /** Opens the Assistant page on its Trip Briefing tab. */
    @JvmStatic
    fun openSettings(ctx: Context) {
        val c = ctx.applicationContext ?: ctx
        val i = Intent(c, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_NAVIGATE_TO, "assistant_briefing")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            c.startActivity(i, mainDisplay())
        } catch (t: Throwable) {
            Log.w(TAG, "open settings failed: ${t.message}")
        }
    }

    private fun startOverDrive(c: Context, label: String, lat: Double, lng: Double): Boolean {
        // A plan with a one-shot start: a map opened later shows it but doesn't start guidance again.
        NavTripStore.save(c, listOf(SearchResult(label, lat, lng)), false, startRequest = true)
        // An open map gets onNewIntent and restores the trip there.
        val map = Intent(c, RoadSenseMapActivity::class.java)
            .putExtra(NavTripStore.EXTRA_RESTORE_TRIP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return try {
            c.startActivity(map, mainDisplay())
            true
        } catch (t: Throwable) {
            Log.w(TAG, "map start failed: ${t.message}")
            false
        }
    }

    private fun wazeIntent(lat: Double, lng: Double) =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=${coords(lat, lng)}&navigate=yes")).setPackage(WAZE_PKG)

    private fun gmapsIntent(lat: Double, lng: Double) =
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${coords(lat, lng)}")).setPackage(GMAPS_PKG)

    private fun coords(lat: Double, lng: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lng)

    private fun resolves(c: Context, i: Intent): Boolean = try {
        c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
    } catch (_: Throwable) {
        false
    }

    private fun launch(c: Context, i: Intent): Boolean = try {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), mainDisplay())
        true
    } catch (t: Throwable) {
        Log.w(TAG, "launch ${i.`package`} failed: ${t.javaClass.simpleName}")  // the message holds the coordinates
        false
    }

    // the head unit's own screen, never the cluster
    private fun mainDisplay(): Bundle? = try {
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
    } catch (_: Throwable) {
        null
    }
}
