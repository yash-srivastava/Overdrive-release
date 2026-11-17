package com.overdrive.app.onboarding

import android.app.Activity
import android.util.Log
import android.view.View
import com.overdrive.app.R

/**
 * The EXPERT track: an opt-in, chaptered walkthrough of the deeper native config
 * surfaces. Unlike the novice wizards (which stay on one screen / dialog), the expert
 * coach NAVIGATES across fragments via MainActivity.navigateForOnboarding, waits for each
 * destination to lay out, then spotlights the REAL controls.
 *
 * SAFETY: every footgun (camera remap, traffic monitor pm-disable+reboot, core-daemon
 * stop = .disabled sentinel, Reset Data) is taught as a CONSUME-the-touch "what is this?"
 * warning — the spotlight explains the stakes but tapping it never fires the action
 * (overlay.consumeCutoutTouch = true). Benign navigation/explanation steps pass through.
 *
 * Entry: either the chapter menu (from the toolbar "?" replay) or resumed at a specific
 * chapter (from the novice "Explore Setup" CTA via OnboardingState.expertTourEntry).
 */
class ExpertTourCoach(
    private val activity: Activity,
    private val overlay: OnboardingOverlayView,
    private val state: OnboardingState,
    private val onFinished: () -> Unit,
) {
    private val ma get() = activity as? com.overdrive.app.ui.MainActivity

    /**
     * The chapters, IN TOUR ORDER (enum order == walk order; nextAfter() relies on it).
     * Chapter 1 (Dashboard orientation) lives in the novice track. The walk roughly
     * follows the nav rail top-to-bottom: view loop → integrations → road sensing →
     * config/diagnostics → services → security.
     */
    enum class Chapter(val key: String) {
        VEHICLE("vehicle"),
        RECORDINGS("recordings"),
        LIVE("live"),
        TRIPS("trips"),
        INTEGRATIONS("integrations"),
        ROADSENSE("roadsense"),   // includes Blind Spot as a 2nd step (same screen/tab)
        MAPS("maps"),
        SURVEILLANCE("surveillance"),
        DIAGNOSTICS("diagnostics"),
        DAEMONS("daemons"),
        SECURITY("security");

        companion object {
            fun fromKey(k: String?): Chapter? = values().firstOrNull { it.key == k }
        }
    }

    fun begin() {
        // Resume directly at a chapter if the novice CTA set one; else show the menu.
        val entry = Chapter.fromKey(state.expertTourEntry)
        if (entry != null) { state.expertTourEntry = null; runChapter(entry) }
        else showMenu()
    }

    // ---- chapter menu ----------------------------------------------------------------

    private fun showMenu() {
        overlay.consumeCutoutTouch = false
        overlay.showCentered()
        // A compact menu: explain, then step through chapters with Next. (We keep the
        // overlay's two-button card; "Start tour" walks all chapters in order, "Done"
        // exits. Per-chapter granular launch is via the resume entry from elsewhere.)
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_expert_menu_title),
            body = activity.getString(R.string.onboarding_expert_menu_body),
            primaryText = activity.getString(R.string.onboarding_expert_menu_start),
            onPrimary = { runChapter(Chapter.VEHICLE) },
            secondaryText = activity.getString(R.string.onboarding_exit),
            onSecondary = { finish() },
        )
    }

    private fun nextAfter(ch: Chapter): Chapter? {
        val all = Chapter.values()
        val idx = all.indexOf(ch)
        return all.getOrNull(idx + 1)
    }

    private fun advance(from: Chapter) {
        val next = nextAfter(from)
        if (next != null) runChapter(next) else finishOnComplete()
    }

    private fun runChapter(ch: Chapter) {
        when (ch) {
            Chapter.VEHICLE -> chapterVehicle()
            Chapter.RECORDINGS -> chapterRecordings()
            Chapter.LIVE -> chapterLive()
            Chapter.TRIPS -> chapterTrips()
            Chapter.INTEGRATIONS -> chapterIntegrations()
            Chapter.ROADSENSE -> chapterRoadSense()
            Chapter.MAPS -> chapterMaps()
            Chapter.SURVEILLANCE -> chapterSurveillance()
            Chapter.DIAGNOSTICS -> chapterDiagnostics()
            Chapter.DAEMONS -> chapterDaemons()
            Chapter.SECURITY -> chapterSecurity()
        }
    }

    /**
     * Navigate to a destination, wait for the transaction to COMMIT + lay out, then run
     * [onReady] with the target fragment's root (or null → caller centers the card). The
     * commit-aware wait lives in MainActivity (navigate is async).
     */
    private fun navigateThen(destId: Int, onReady: (View?) -> Unit) {
        val m = ma ?: run { onReady(null); return }
        m.navigateForOnboardingThen(destId, onReady)
    }

    // ---- Chapter 2: Vehicle ----------------------------------------------------------

    private fun chapterVehicle() {
        navigateThen(R.id.dashboardFragment) { root ->
            val tile = root?.findViewById<View>(R.id.metricVehicle)
            spotlightOrCenter(tile)
            overlay.consumeCutoutTouch = false
            card(
                Chapter.VEHICLE,
                R.string.onboarding_expert_vehicle_title,
                R.string.onboarding_expert_vehicle_body,
            )
        }
    }

    // ---- Recordings (native — segmented Dashcam/Surveillance + library) --------------

    private fun chapterRecordings() {
        navigateThen(R.id.recordingsFragment) { root ->
            // Anchor on the steady-state segmented toggle (heroHeader/filterStrip can be
            // GONE during the landscape fullscreen player; segmentedSource is reliable).
            val seg = root?.findViewById<View>(R.id.segmentedSource)
            // In Timeline view the toggle is hidden: point at (and describe) the List | Timeline switch instead.
            val timelineView = seg?.isShown != true && root?.findViewById<View>(R.id.recViewToggle)?.isShown == true
            spotlightOrCenter(if (timelineView) root?.findViewById<View>(R.id.recViewToggle) else seg)
            overlay.consumeCutoutTouch = false
            card(
                Chapter.RECORDINGS,
                R.string.onboarding_expert_recordings_title,
                if (timelineView) R.string.onboarding_expert_recordings_timeline_body else R.string.onboarding_expert_recordings_body,
            )
        }
    }

    // ---- Live (web page — point at the rail entry / container) -----------------------

    private fun chapterLive() {
        // Prefer the rail row as the anchor (always laid out); fall back to navigating.
        val railRow = ma?.railRowView(R.id.railDestLive)
        if (railRow != null && railRow.width > 0) {
            overlay.spotlight(railRow)
            overlay.consumeCutoutTouch = true   // don't navigate away mid-tour on a tap
            card(Chapter.LIVE, R.string.onboarding_expert_live_title, R.string.onboarding_expert_live_body)
        } else {
            navigateThen(R.id.liveViewFragment) { root ->
                spotlightOrCenter(root)
                overlay.consumeCutoutTouch = false
                card(Chapter.LIVE, R.string.onboarding_expert_live_title, R.string.onboarding_expert_live_body)
            }
        }
    }

    // ---- Trips (web page — point at the rail entry / container) ----------------------

    private fun chapterTrips() {
        val railRow = ma?.railRowView(R.id.railDestTrips)
        if (railRow != null && railRow.width > 0) {
            overlay.spotlight(railRow)
            overlay.consumeCutoutTouch = true
            card(Chapter.TRIPS, R.string.onboarding_expert_trips_title, R.string.onboarding_expert_trips_body)
        } else {
            navigateThen(R.id.tripsFragment) { root ->
                spotlightOrCenter(root)
                overlay.consumeCutoutTouch = false
                card(Chapter.TRIPS, R.string.onboarding_expert_trips_title, R.string.onboarding_expert_trips_body)
            }
        }
    }

    // ---- RoadSense (+ Blind Spot 2nd step — both live on the same /road-sense screen) -

    private fun chapterRoadSense() {
        navigateThen(R.id.roadSenseFragment) { root ->
            // RoadSenseFragment is a bare FrameLayout host; only the container is
            // anchorable (tabs are web DOM). Spotlight the pane and explain the feature,
            // then a 2nd step for Blind Spot (a tab on THIS same screen) — so we don't
            // navigate to the identical-looking screen twice with no visible difference.
            spotlightOrCenter(root)
            overlay.consumeCutoutTouch = false
            overlay.bindStep(
                title = activity.getString(R.string.onboarding_expert_roadsense_title),
                body = activity.getString(R.string.onboarding_expert_roadsense_body),
                primaryText = activity.getString(R.string.onboarding_next),
                onPrimary = { roadSenseBlindSpotStep() },
                secondaryText = activity.getString(R.string.onboarding_exit),
                onSecondary = { finish() },
                stepLabel = chapterLabel(Chapter.ROADSENSE),
            )
        }
    }

    private fun roadSenseBlindSpotStep() {
        // Blind Spot is the 'Blind Spot' tab on the RoadSense page; the overlay itself is
        // a background SurfaceControl shown on the turn signal. Pure explainer, same pane.
        overlay.consumeCutoutTouch = false
        card(
            Chapter.ROADSENSE,
            R.string.onboarding_expert_blindspot_title,
            R.string.onboarding_expert_blindspot_body,
        )
    }

    // ---- Maps (hazard map + cluster — separate Activity, spotlight the rail entry) ---

    private fun chapterMaps() {
        // The Hazard Map is a separate MapLibre Activity (rail row R.id.railDestMap,
        // destinationId 0) — NOT a nav destination, and the rail row exists only in
        // landscape. Spotlight the launcher row (consume the touch so reading the card
        // can't launch the Activity mid-tour); in portrait degrade to a centered card.
        val railRow = ma?.railRowView(R.id.railDestMap)
        spotlightOrCenter(railRow)
        overlay.consumeCutoutTouch = true
        card(
            Chapter.MAPS,
            R.string.onboarding_expert_maps_title,
            R.string.onboarding_expert_maps_body,
        )
    }

    // ---- Chapter: Integrations -------------------------------------------------------

    private fun chapterIntegrations() {
        navigateThen(R.id.integrationsFragment) { root ->
            val pill = root?.findViewById<View>(R.id.heroStatusPill)
            spotlightOrCenter(pill)
            overlay.consumeCutoutTouch = false
            overlay.bindStep(
                title = activity.getString(R.string.onboarding_expert_integrations_title),
                body = activity.getString(R.string.onboarding_expert_integrations_body),
                primaryText = activity.getString(R.string.onboarding_next),
                onPrimary = { integrationsBydCloudStep(root) },
                secondaryText = activity.getString(R.string.onboarding_exit),
                onSecondary = { finish() },
                stepLabel = chapterLabel(Chapter.INTEGRATIONS),
            )
        }
    }

    private fun integrationsBydCloudStep(root: View?) {
        val target = root?.findViewById<View>(R.id.cardBydCloud)
        // The card sits on the second grid row in landscape; bring it on screen before spotlighting.
        target?.requestRectangleOnScreen(android.graphics.Rect(0, 0, target.width, target.height), true)
        val show = {
            spotlightOrCenter(target)
            // BYD Cloud unlocks remote lock/flash/horn — flag the stakes (informational, not
            // a footgun fire). Touch passes through so the user can open it if they want.
            overlay.consumeCutoutTouch = false
            card(
                Chapter.INTEGRATIONS,
                R.string.onboarding_expert_bydcloud_title,
                R.string.onboarding_expert_bydcloud_body,
            )
        }
        if (target != null) target.post { show() } else show()
    }

    // ---- Chapter 4: Surveillance & recording -----------------------------------------

    private fun chapterSurveillance() {
        // Reach the Settings hub and point at the surveillance section (the config itself
        // is a WebView form — we explain the handoff, not the form). The anchor resolver
        // handles BOTH orientations (portrait card vs landscape sub-rail row).
        navigateThen(R.id.settingsFragment) { _ ->
            val anchor = ma?.settingsTourAnchor(
                com.overdrive.app.ui.fragment.SettingsFragment.TourTarget.SURVEILLANCE,
            )
            spotlightOrCenter(anchor)
            overlay.consumeCutoutTouch = false
            card(
                Chapter.SURVEILLANCE,
                R.string.onboarding_expert_surveillance_title,
                R.string.onboarding_expert_surveillance_body,
            )
        }
    }

    // ---- Chapter 5: Diagnostics tools (FOOTGUNS) -------------------------------------

    private fun chapterDiagnostics() {
        navigateThen(R.id.diagnosticsFragment) { root ->
            val probe = root?.findViewById<View>(R.id.cardCameraProbe)
            spotlightOrCenter(probe)
            // FOOTGUN: camera probe remaps cameras + restarts the daemon. Consume the
            // touch so reading the warning can't trigger it.
            overlay.consumeCutoutTouch = true
            overlay.bindStep(
                title = activity.getString(R.string.onboarding_expert_camprobe_title),
                body = activity.getString(R.string.onboarding_expert_camprobe_body),
                primaryText = activity.getString(R.string.onboarding_next),
                onPrimary = { diagnosticsTrafficStep(root) },
                secondaryText = activity.getString(R.string.onboarding_exit),
                onSecondary = { finish() },
                stepLabel = chapterLabel(Chapter.DIAGNOSTICS),
            )
        }
    }

    private fun diagnosticsTrafficStep(root: View?) {
        val traffic = root?.findViewById<View>(R.id.cardTraffic)
        spotlightOrCenter(traffic)
        // FOOTGUN: traffic monitor runs pm disable on a stock BYD app + forces a reboot.
        overlay.consumeCutoutTouch = true
        card(
            Chapter.DIAGNOSTICS,
            R.string.onboarding_expert_traffic_title,
            R.string.onboarding_expert_traffic_body,
        )
    }

    // ---- Chapter 6: Daemons & remote access (FOOTGUN) --------------------------------

    private fun chapterDaemons() {
        navigateThen(R.id.daemonsFragment) { root ->
            // The daemon switches live in a RecyclerView; the core trio (Camera/Sentry/
            // ACC) are the first rows. We spotlight the list and warn rather than reach
            // into a specific ViewHolder (row views are recycled / may not be bound yet).
            val list = root?.findViewById<View>(R.id.recyclerDaemons)
            spotlightOrCenter(list)
            overlay.consumeCutoutTouch = true   // do not let a tap toggle a core daemon
            card(
                Chapter.DAEMONS,
                R.string.onboarding_expert_daemons_title,
                R.string.onboarding_expert_daemons_body,
            )
        }
    }

    // ---- Chapter 7: Security & privacy (FOOTGUN: reset) ------------------------------

    private fun chapterSecurity() {
        navigateThen(R.id.settingsSecurityFragment) { root ->
            val row = root?.findViewById<View>(R.id.rowSecurityToggle)
            spotlightOrCenter(row)
            overlay.consumeCutoutTouch = false   // PIN enable is benign
            overlay.bindStep(
                title = activity.getString(R.string.onboarding_expert_security_title),
                body = activity.getString(R.string.onboarding_expert_security_body),
                primaryText = activity.getString(R.string.onboarding_next),
                onPrimary = { securityResetStep() },
                secondaryText = activity.getString(R.string.onboarding_exit),
                onSecondary = { finish() },
                stepLabel = chapterLabel(Chapter.SECURITY),
            )
        }
    }

    private fun securityResetStep() {
        // Reset Data is destructive. Resolve its anchor in BOTH orientations (portrait
        // cardResetData vs landscape Privacy sub-rail row) — this is the most
        // safety-relevant step, so it must point at the real control on the head unit's
        // primary landscape layout, not silently center.
        navigateThen(R.id.settingsFragment) { _ ->
            val reset = ma?.settingsTourAnchor(
                com.overdrive.app.ui.fragment.SettingsFragment.TourTarget.RESET,
            )
            spotlightOrCenter(reset)
            overlay.consumeCutoutTouch = true   // never fire the wipe from the tour
            overlay.bindStep(
                title = activity.getString(R.string.onboarding_expert_reset_title),
                body = activity.getString(R.string.onboarding_expert_reset_body),
                primaryText = activity.getString(R.string.onboarding_expert_finish),
                onPrimary = { finishOnComplete() },
            )
        }
    }

    // ---- shared card helpers ---------------------------------------------------------

    /**
     * A standard chapter card. PRIMARY = "Next" (advance to the next chapter); SECONDARY =
     * "Exit" (end the whole tour). These must do DIFFERENT things — earlier both advanced,
     * so Next/Skip were indistinguishable and there was no way out mid-tour. "Exit" is the
     * always-available dismiss the user asked for. The last chapter shows "Done" instead
     * of "Next" (handled by advance → finishOnComplete).
     */
    private fun card(ch: Chapter, titleRes: Int, bodyRes: Int) {
        val isLast = nextAfter(ch) == null
        overlay.bindStep(
            title = activity.getString(titleRes),
            body = activity.getString(bodyRes),
            primaryText = activity.getString(
                if (isLast) R.string.onboarding_done_short else R.string.onboarding_next,
            ),
            onPrimary = { advance(ch) },
            secondaryText = activity.getString(R.string.onboarding_exit),
            onSecondary = { finish() },
            stepLabel = chapterLabel(ch),
        )
    }

    private fun spotlightOrCenter(anchor: View?) {
        if (anchor != null && anchor.width > 0) overlay.spotlight(anchor)
        else overlay.showCentered()
    }

    private fun chapterLabel(ch: Chapter): CharSequence =
        // +2 because Chapter 1 (Dashboard orientation) lives in the novice track; total is
        // novice chapter 1 + all Expert chapters. Derived, never hardcoded.
        activity.getString(
            R.string.onboarding_expert_chapter_label,
            ch.ordinal + 2, Chapter.values().size + 1,
        )

    private fun finishOnComplete() {
        overlay.consumeCutoutTouch = false
        overlay.showCentered()
        overlay.pulseAttention()
        overlay.bindStep(
            title = activity.getString(R.string.onboarding_expert_done_title),
            body = activity.getString(R.string.onboarding_expert_done_body),
            primaryText = activity.getString(R.string.onboarding_done_short),
            onPrimary = { finish() },
        )
    }

    private fun finish() {
        Log.i(TAG, "Expert tour finished")
        onFinished()
    }

    private companion object { const val TAG = "ExpertTourCoach" }
}
