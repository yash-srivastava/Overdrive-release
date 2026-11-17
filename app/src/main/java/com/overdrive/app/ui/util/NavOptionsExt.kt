package com.overdrive.app.ui.util

import androidx.navigation.NavController
import androidx.navigation.NavOptions
import com.overdrive.app.R

/**
 * Shared NavOptions presets so every navigation uses the app's motion vocabulary.
 *  - [m3SharedAxisZ]: drill-down (parent → child), a directional push from the trailing edge.
 *  - [m3FadeThrough]: peer destinations, a plain crossfade (the rail uses the same anims).
 */
object NavOptionsExt {

    /** Drill-down: the child slides in from the trailing edge, the parent eases back; pop reverses. */
    fun m3SharedAxisZ(): NavOptions = NavOptions.Builder()
        .setLaunchSingleTop(true)
        .setEnterAnim(R.anim.m3_shared_z_enter)
        .setExitAnim(R.anim.m3_shared_z_exit)
        .setPopEnterAnim(R.anim.m3_shared_z_pop_enter)
        .setPopExitAnim(R.anim.m3_shared_z_pop_exit)
        .build()

    /** Peer destinations: a short crossfade with no scale. */
    fun m3FadeThrough(): NavOptions = NavOptions.Builder()
        .setLaunchSingleTop(true)
        .setEnterAnim(R.anim.m3_fade_through_enter)
        .setExitAnim(R.anim.m3_fade_through_exit)
        .setPopEnterAnim(R.anim.m3_fade_through_enter)
        .setPopExitAnim(R.anim.m3_fade_through_exit)
        .build()
}

/** Convenience: navigate(destinationId) with the drill-down anims. */
fun NavController.navigateDrillDown(destinationId: Int, args: android.os.Bundle? = null) {
    try {
        navigate(destinationId, args, NavOptionsExt.m3SharedAxisZ())
    } catch (_: IllegalArgumentException) {
        // Destination not in current graph. Defensive only — callers should
        // resolve before invoking.
    }
}
