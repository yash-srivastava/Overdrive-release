package com.overdrive.app.ui.fragment

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.navigation.fragment.findNavController
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.BuildConfig
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.overlay.StatusOverlayUiWriter
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.overlay.RoadSenseOverlayService
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.updater.AppUpdater
import com.overdrive.app.ui.dialog.LanguagePickerDialog
import com.overdrive.app.ui.fragment.settings.SettingsAppearanceFragment
import com.overdrive.app.ui.fragment.settings.SettingsDaemonsFragment
import com.overdrive.app.ui.fragment.settings.SettingsOverlayFragment
import com.overdrive.app.ui.fragment.settings.SettingsPrivacyFragment
import com.overdrive.app.ui.fragment.settings.RemoteCommunicationSettingsBinder
import com.overdrive.app.ui.fragment.settings.SettingsRecordingFragment
import com.overdrive.app.ui.fragment.settings.SettingsSecurityFragment
import com.overdrive.app.ui.fragment.settings.SettingsSurveillanceFragment
import com.overdrive.app.ui.util.PreferencesManager
import com.overdrive.app.ui.util.navigateDrillDown
import java.util.Locale

/**
 * Settings entry point.
 *
 * Two layout variants drive completely different UX:
 *  - **Portrait** (`layout/fragment_settings.xml`) — SOTA hub with hero
 *    header, two quick-toggle tiles (Theme / Language), a Preferences
 *    section list (Recording / Surveillance / Daemons), an About & data
 *    section (About row + destructive Reset row), and a build footer.
 *  - **Landscape** (`layout-land/fragment_settings.xml`) — two-pane
 *    sub-rail layout (~280dp left rail of section names + detail pane on
 *    the right). Sections are dynamically inflated from the [Section]
 *    enum and click-swap a child fragment into `R.id.settingsContent`.
 *
 * Orientation detection happens in [onViewCreated] via
 * [resources.configuration.orientation]. Each branch wires a disjoint
 * set of views — the union of IDs would not exist in any single layout.
 */
class SettingsFragment : Fragment() {
    private var portraitRoadSenseSwitch: SwitchMaterial? = null
    private var remoteCommunicationBinder: RemoteCommunicationSettingsBinder? = null
    private var applyingPortraitRoadSenseConfig = false

    // Guards the camera/trip pill listeners while a failed write reverts its
    // switch (setChecked fires the listener programmatically; an unguarded
    // revert would re-enter the write with the stale value).
    private var applyingStatusOverlayConfig = false

    /**
     * Sub-rail sections. The order here is the visual order in the rail
     * (top → bottom). [navigates] = true marks rows that traditionally
     * leave the Settings page (Recording / Surveillance) — kept for the
     * trailing chevron affordance even though we now host them inline.
     */
    // About was removed from this sub-rail — it now lives only on the
    // main navigation rail so there's a single entry point.
    private enum class Section(
        val labelRes: Int,
        val iconRes: Int,
        val navigates: Boolean = false
    ) {
        APPEARANCE(R.string.settings_section_appearance, R.drawable.ic_dashboard),
        RECORDING(R.string.settings_section_recording, R.drawable.ic_recording, navigates = true),
        SURVEILLANCE(R.string.settings_section_surveillance, R.drawable.ic_sentry, navigates = true),
        OVERLAY(R.string.settings_section_overlay, R.drawable.ic_overlay_rec_active),
        SECURITY(R.string.settings_section_security, R.drawable.ic_security_lock),
        DAEMONS(R.string.settings_section_daemons, R.drawable.ic_services),
        PRIVACY(R.string.settings_section_privacy, R.drawable.ic_delete),
    }

    private var currentSection: Section = Section.APPEARANCE

    /** Map from Section → its row view in the sub-rail (landscape only). */
    private val rowViews = mutableMapOf<Section, View>()

    /** Semantic anchor targets the onboarding Expert tour can request, orientation-agnostic. */
    enum class TourTarget { SURVEILLANCE, RESET, SECURITY, DAEMONS }

    /**
     * Resolve the best on-screen anchor for an Expert-tour step in EITHER orientation.
     * Portrait: the section card on the hub. Landscape: the sub-rail row (also selects
     * that section so its pane is shown). Returns null if not resolvable → caller centers.
     */
    fun tourAnchorFor(target: TourTarget): View? {
        val root = view ?: return null
        val landscape = root.findViewById<View>(R.id.settingsContent) != null
        return if (landscape) {
            val section = when (target) {
                TourTarget.SURVEILLANCE -> Section.SURVEILLANCE
                TourTarget.RESET -> Section.PRIVACY
                TourTarget.SECURITY -> Section.SECURITY
                TourTarget.DAEMONS -> Section.DAEMONS
            }
            selectSection(section, animate = true)
            rowViews[section]
        } else {
            val id = when (target) {
                TourTarget.SURVEILLANCE -> R.id.cardSectionSurveillance
                TourTarget.RESET -> R.id.cardResetData
                TourTarget.SECURITY -> R.id.cardSectionSecurity
                TourTarget.DAEMONS -> R.id.cardSectionDaemons
            }
            root.findViewById(id)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val isLandscape = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape && view.findViewById<View>(R.id.settingsContent) != null) {
            setupLandscapeSubrail(view, savedInstanceState)
        } else {
            // Portrait: SOTA hub. Hero header is layout-driven; wire up the
            // quick toggles, section shortcuts, the destructive Reset row,
            // and footer. About now lives only on the main rail.
            setupQuickThemeTile(view)
            setupLanguagePicker(view)
            setupSectionShortcuts(view)
            setupOverlayToggles(view)
            remoteCommunicationBinder = RemoteCommunicationSettingsBinder(view)
            setupResetRow(view)
            setupFooter(view)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPortraitRoadSenseSwitch(forceReload = true)
        remoteCommunicationBinder?.refresh()
    }

    override fun onDestroyView() {
        remoteCommunicationBinder?.destroy()
        remoteCommunicationBinder = null
        portraitRoadSenseSwitch = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, currentSection.name)
    }

    // ============================================================
    // Landscape sub-rail
    // ============================================================

    private fun setupLandscapeSubrail(root: View, savedInstanceState: Bundle?) {
        val rowsContainer =
            root.findViewById<LinearLayout>(R.id.subrailRowsContainer) ?: return

        val savedName = savedInstanceState?.getString(KEY_SECTION)
            ?: arguments?.getString(KEY_SECTION)
        currentSection = savedName
            ?.let { name -> Section.values().firstOrNull { it.name == name } }
            ?: Section.APPEARANCE

        // Build one row per Section. Tag the row with the section so the
        // shared click listener can recover it.
        Section.values().forEach { section ->
            val row = layoutInflater.inflate(
                R.layout.item_settings_subrail_row,
                rowsContainer,
                /* attachToRoot = */ false
            )
            row.tag = section
            row.findViewById<ImageView>(R.id.subrailRowIcon).setImageResource(section.iconRes)
            row.findViewById<TextView>(R.id.subrailRowLabel).setText(section.labelRes)
            row.findViewById<ImageView>(R.id.subrailRowChevron).visibility =
                if (section.navigates) View.VISIBLE else View.GONE

            row.setOnClickListener { selectSection(section, animate = true) }

            rowsContainer.addView(row)
            rowViews[section] = row
        }

        // Initial selection. First-time attach: skip the swap animation
        // so the page paints in one frame; rotation: also skip (saved
        // section is already "current" from the user's perspective).
        selectSection(currentSection, animate = false, force = true)
    }

    /**
     * Make [section] the active sub-rail row and host its content
     * fragment in `settingsContent`.
     *
     * @param animate when true, applies a M3-style cross-fade to the
     *                detail pane swap and a delayed transition to the
     *                rail (for the active-pill move).
     * @param force   when true, performs the swap even if [section]
     *                already equals [currentSection] (used for the
     *                initial render).
     */
    private fun selectSection(
        section: Section,
        animate: Boolean,
        force: Boolean = false
    ) {
        if (!force && section == currentSection && childFragmentManager
                .findFragmentById(R.id.settingsContent) != null
        ) return

        currentSection = section

        // Update rail visual state. TransitionManager drives the active
        // pill's apparent "move" — the underlying View doesn't move, but
        // the selected-state background fades in/out which the eye reads
        // as a slide.
        val rail = view?.findViewById<LinearLayout>(R.id.subrailRowsContainer)
        if (rail != null && animate) {
            TransitionManager.beginDelayedTransition(rail, AutoTransition().apply {
                duration = 220L
            })
        }
        rowViews.forEach { (s, v) -> v.isSelected = (s == section) }

        // Swap detail content. Use a fade if animation is requested and
        // the project's anim resources are available.
        val fragment = fragmentForSection(section)
        childFragmentManager.commit {
            if (animate) {
                setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            }
            replace(R.id.settingsContent, fragment, "settings_section_${section.name}")
        }
    }

    private fun fragmentForSection(section: Section): Fragment = when (section) {
        Section.APPEARANCE -> SettingsAppearanceFragment()
        Section.RECORDING -> SettingsRecordingFragment()
        Section.SURVEILLANCE -> SettingsSurveillanceFragment()
        Section.OVERLAY -> SettingsOverlayFragment()
        Section.SECURITY -> SettingsSecurityFragment()
        Section.DAEMONS -> SettingsDaemonsFragment()
        Section.PRIVACY -> SettingsPrivacyFragment()
    }

    // ============================================================
    // Portrait (original monolithic page) — unchanged behavior
    // ============================================================

    /**
     * Portrait quick-tile theme picker. The tile shows the active mode as
     * its big-line value; tapping it opens a 3-option Material picker. The
     * SOTA tile-style picker still lives on the Appearance pane (for
     * landscape) so we don't duplicate the visual previews here.
     */
    private fun setupQuickThemeTile(view: View) {
        val tile = view.findViewById<View>(R.id.quickTileTheme) ?: return
        val tvValue = view.findViewById<TextView>(R.id.tvQuickThemeValue) ?: return
        tvValue.text = themeModeLabel(PreferencesManager.getThemeMode())

        tile.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val labels = arrayOf(
                getString(R.string.settings_theme_auto),
                getString(R.string.settings_theme_light),
                getString(R.string.settings_theme_dark),
            )
            val current = PreferencesManager.getThemeMode()
            val checked = when (current) {
                AppCompatDelegate.MODE_NIGHT_NO -> 1
                AppCompatDelegate.MODE_NIGHT_YES -> 2
                else -> 0
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.settings_theme_label)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    val mode = when (which) {
                        1 -> AppCompatDelegate.MODE_NIGHT_NO
                        2 -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    PreferencesManager.setThemeMode(mode)
                    tvValue.text = themeModeLabel(mode)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun themeModeLabel(mode: Int): String = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.settings_theme_light)
        AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.settings_theme_dark)
        else -> getString(R.string.settings_theme_auto)
    }

    private fun setupLanguagePicker(view: View) {
        val tvValue = view.findViewById<TextView>(R.id.tvLanguageValue) ?: return
        tvValue.text = currentLocaleDisplay()
        view.findViewById<View>(R.id.cardLanguage)?.setOnClickListener {
            val activity = activity ?: return@setOnClickListener
            LanguagePickerDialog.show(activity) { activity.recreate() }
        }
    }

    private fun currentLocaleDisplay(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val tag = if (!locales.isEmpty) locales[0]?.toLanguageTag() else null
        return if (tag.isNullOrEmpty()) {
            getString(R.string.settings_theme_auto).substringBefore('(').trim()
                .ifEmpty { Locale.getDefault().displayLanguage }
        } else {
            Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault())
                .replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
        }
    }

    private fun setupSectionShortcuts(view: View) {
        view.findViewById<View>(R.id.cardSectionRecording)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.recordingSettingsWebFragment)
        }
        view.findViewById<View>(R.id.cardSectionSurveillance)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.surveillanceSettingsWebFragment)
        }
        view.findViewById<View>(R.id.cardSectionSecurity)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.settingsSecurityFragment)
        }
        view.findViewById<View>(R.id.cardSectionDaemons)?.setOnClickListener {
            findNavController().navigateDrillDown(R.id.daemonsFragment)
        }
    }

    /**
     * Portrait status-overlay toggles. The two switches gate the floating
     * pill segments via [UnifiedConfigManager]'s `statusOverlay` section
     * (file-backed so the daemon UID can read the same value). The service
     * polls this on every tick — no restart needed.
     */
    private fun setupOverlayToggles(view: View) {
        val swCamera = view.findViewById<SwitchMaterial>(R.id.swOverlayCamera) ?: return
        val swTrip = view.findViewById<SwitchMaterial>(R.id.swOverlayTrip) ?: return
        val swRoadSense =
            view.findViewById<SwitchMaterial>(R.id.swOverlayRoadSense) ?: return
        portraitRoadSenseSwitch = swRoadSense
        val rowCamera = view.findViewById<View>(R.id.rowOverlayCamera)
        val rowTrip = view.findViewById<View>(R.id.rowOverlayTrip)
        val rowRoadSense = view.findViewById<View>(R.id.rowOverlayRoadSense)

        val cfg = UnifiedConfigManager.getStatusOverlay()
        swCamera.isChecked = cfg.optBoolean("cameraVisible", true)
        swTrip.isChecked = cfg.optBoolean("tripVisible", true)
        refreshPortraitRoadSenseSwitch(forceReload = true)

        rowCamera?.setOnClickListener { swCamera.isChecked = !swCamera.isChecked }
        rowTrip?.setOnClickListener { swTrip.isChecked = !swTrip.isChecked }
        rowRoadSense?.setOnClickListener {
            swRoadSense.isChecked = !swRoadSense.isChecked
        }

        // Write off the UI thread (updateSection is off-looper-only: blocking
        // IPC + full JSON rewrite) and revert the switch if the write did not
        // commit — same contract as the RoadSense switch below.
        swCamera.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) {
                persistOverlayFlag("cameraVisible", checked, button)
            }
        }
        swTrip.setOnCheckedChangeListener { button, checked ->
            if (!applyingStatusOverlayConfig) {
                persistOverlayFlag("tripVisible", checked, button)
            }
        }
        // Same off-looper + retry + revert-on-failure path as the two switches above.
        // This used to call setOverlayVisible() inline on the looper with no retry, so an
        // app-UID write deferred until the daemon provisions the stable .lock inode just
        // sprang the switch back — RoadSense could not be toggled at all right after boot.
        swRoadSense.setOnCheckedChangeListener { button, checked ->
            if (applyingPortraitRoadSenseConfig) return@setOnCheckedChangeListener
            StatusOverlayUiWriter.writeWith(
                "roadSense.overlayVisible",
                { ok ->
                    if (ok) {
                        context?.let { RoadSenseOverlayService.syncWithConfig(it) }
                    } else if (view != null) {  // fragment view still alive
                        applyingPortraitRoadSenseConfig = true
                        button.isChecked = !checked
                        applyingPortraitRoadSenseConfig = false
                    }
                }
            ) { RoadSenseConfig.setOverlayVisible(checked) }
        }
    }

    private fun persistOverlayFlag(
        key: String,
        value: Boolean,
        button: android.widget.CompoundButton
    ) {
        StatusOverlayUiWriter.write(key, value) { ok ->
            if (ok) {
                kickOverlayRefresh()
            } else if (view != null) {  // fragment view still alive
                applyingStatusOverlayConfig = true
                button.isChecked = !value
                applyingStatusOverlayConfig = false
            }
        }
    }

    private fun refreshPortraitRoadSenseSwitch(forceReload: Boolean) {
        val toggle = portraitRoadSenseSwitch ?: return
        val visible = try {
            RoadSenseConfig.snapshot(forceReload).overlayVisible
        } catch (_: Throwable) {
            return
        }
        applyingPortraitRoadSenseConfig = true
        toggle.isChecked = visible
        applyingPortraitRoadSenseConfig = false
    }

    /**
     * Nudge the overlay service to re-poll immediately so a toggle flip
     * feels instant instead of waiting up to 3s for the next scheduled
     * poll (or 10s when ACC is off). The service's onStartCommand
     * cancels its in-flight delayed poll and fires one straight away
     * when re-entered, which is exactly what we need here.
     */
    private fun kickOverlayRefresh() {
        val ctx = context ?: return
        com.overdrive.app.overlay.StatusOverlayService.startIfPermitted(ctx)
    }

    private fun setupResetRow(view: View) {
        view.findViewById<View>(R.id.cardResetData)?.setOnClickListener {
            (activity as? MainActivity)?.invokeResetDataDialog()
        }
    }

    /**
     * Footer line at the bottom of the portrait hub. Read-only. Shows
     * "OverDrive vX.Y · com.overdrive.app" so power users can confirm
     * the running version + package id without diving into About.
     */
    private fun setupFooter(view: View) {
        val tv = view.findViewById<TextView>(R.id.tvSettingsFooter) ?: return
        val pkg = BuildConfig.APPLICATION_ID
        // Paint the in-memory BuildConfig identity instantly (no I/O), then
        // resolve the authoritative file-backed label OFF the main thread and
        // post it back — so the footer matches the file-first label every other
        // surface shows (incl. same-tag GitHub republishes where versionName
        // wasn't bumped), without doing the /data/local/tmp read on the looper.
        // Mirrors SettingsAboutFragment's paint-then-replace pattern.
        tv.text = getString(R.string.settings_footer_format, AppUpdater.getInstalledVersion(), pkg)
        val appCtx = requireContext().applicationContext
        Thread {
            val resolved = AppUpdater.getDisplayVersion(appCtx)
            // View.post hops to the main thread and is a no-op if the view is
            // detached — no leak, no isAdded race.
            tv.post { tv.text = getString(R.string.settings_footer_format, resolved, pkg) }
        }.start()
    }

    companion object {
        const val KEY_SECTION = "settings_subrail_section"
        const val KEY_FROM_RECORDINGS = "settings_from_recordings"
        const val SECTION_RECORDING = "RECORDING"
    }
}
