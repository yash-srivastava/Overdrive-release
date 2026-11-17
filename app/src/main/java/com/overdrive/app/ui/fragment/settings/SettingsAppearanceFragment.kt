package com.overdrive.app.ui.fragment.settings

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.overdrive.app.R
import com.overdrive.app.server.LocaleManager
import com.overdrive.app.ui.MainActivity
import com.overdrive.app.ui.dialog.LanguagePickerDialog
import com.overdrive.app.ui.fragment.WebViewFragment
import com.overdrive.app.ui.navigation.NavigationRailCatalog
import com.overdrive.app.ui.util.PreferencesManager
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

/**
 * Settings → Appearance pane.
 *
 * Hosts the SOTA tile-style theme picker (Auto / Light / Dark visual
 * previews) and the language picker. Behaviour is identical to the legacy
 * MaterialButtonToggleGroup picker — a tap persists the night-mode and
 * triggers an activity recreate via [PreferencesManager.setThemeMode].
 *
 * The three theme tiles share the same R.id.themeAuto / themeLight /
 * themeDark IDs the older MaterialButtonToggleGroup used; we just rewired
 * them as plain click targets and drive selection state by toggling each
 * tile's `isSelected` flag (which the set_theme_tile_bg selector
 * picks up automatically).
 */
class SettingsAppearanceFragment : Fragment() {

    private var themeAuto: View? = null
    private var themeLight: View? = null
    private var themeDark: View? = null
    private var tvCaption: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_appearance, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupThemePicker(view)
        setupLanguagePicker(view)
        setupNavigationPicker(view)
    }

    private fun setupThemePicker(view: View) {
        themeAuto = view.findViewById(R.id.themeAuto)
        themeLight = view.findViewById(R.id.themeLight)
        themeDark = view.findViewById(R.id.themeDark)
        tvCaption = view.findViewById(R.id.tvThemeActiveCaption)

        // Initial selection mirrors persisted mode.
        applyTileSelection(PreferencesManager.getThemeMode())

        themeAuto?.setOnClickListener { selectMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        themeLight?.setOnClickListener { selectMode(AppCompatDelegate.MODE_NIGHT_NO) }
        themeDark?.setOnClickListener { selectMode(AppCompatDelegate.MODE_NIGHT_YES) }
    }

    private fun selectMode(mode: Int) {
        // Reflect locally first so the tile flips before the activity
        // recreate kicks in (PreferencesManager.setThemeMode triggers
        // AppCompatDelegate.setDefaultNightMode which restarts the
        // process on this head unit).
        applyTileSelection(mode)
        // Cross-fade every visible WebView's theme BEFORE the recreate so the
        // user sees an instant `data-theme` swap (CSS-variable cascade)
        // instead of a blanked-out screen mid-reload. The recreate that
        // follows reuses the same theme, so the new fragment's
        // onPageFinished -> buildThemeInjectJs() is a no-op visually.
        broadcastThemeToWebViews(modeToTheme(mode))
        PreferencesManager.setThemeMode(mode)
    }

    /**
     * Resolve a NIGHT_* mode to the literal theme key the WebView CSS expects.
     * For NIGHT_FOLLOW_SYSTEM we read the activity's current uiMode so the
     * WebView reflects whatever AppCompat has already chosen.
     */
    private fun modeToTheme(mode: Int): String = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> "light"
        AppCompatDelegate.MODE_NIGHT_YES -> "dark"
        else -> {
            val night = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (night) "dark" else "light"
        }
    }

    /**
     * Walk the activity's fragment tree and apply the new theme key to every
     * attached WebViewFragment. Recurses through child fragment managers so
     * fragments hosted under NavHostFragment (which is how the rail-driven
     * pages are mounted) are reached too.
     */
    private fun broadcastThemeToWebViews(theme: String) {
        val fm = activity?.supportFragmentManager ?: return
        collectWebViewFragments(fm).forEach { it.applyTheme(theme) }
    }

    private fun collectWebViewFragments(fm: FragmentManager): List<WebViewFragment> {
        val out = mutableListOf<WebViewFragment>()
        for (f in fm.fragments) {
            if (f is WebViewFragment) out.add(f)
            // Only recurse into added fragments — childFragmentManager on a
            // detached fragment can throw IllegalStateException.
            if (f.isAdded) {
                out.addAll(collectWebViewFragments(f.childFragmentManager))
            }
        }
        return out
    }

    private fun applyTileSelection(mode: Int) {
        themeAuto?.isSelected = (mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        themeLight?.isSelected = (mode == AppCompatDelegate.MODE_NIGHT_NO)
        themeDark?.isSelected = (mode == AppCompatDelegate.MODE_NIGHT_YES)
        tvCaption?.setText(
            when (mode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.string.settings_theme_active_light_caption
                AppCompatDelegate.MODE_NIGHT_YES -> R.string.settings_theme_active_dark_caption
                else -> R.string.settings_theme_active_auto_caption
            }
        )
    }

    private fun setupLanguagePicker(view: View) {
        val tvValue = view.findViewById<TextView>(R.id.tvLanguageValue)
        val tvCount = view.findViewById<TextView>(R.id.tvLanguageCount)
        tvValue.text = currentLocaleDisplay()
        tvCount?.text = getString(
            R.string.settings_language_count_format,
            LocaleManager.SUPPORTED.size,
            LocaleManager.SUPPORTED.size
        )
        view.findViewById<View>(R.id.cardLanguage).setOnClickListener {
            val activity = activity ?: return@setOnClickListener
            LanguagePickerDialog.show(activity) {
                activity.recreate()
            }
        }
    }

    private fun currentLocaleDisplay(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val tag = if (!locales.isEmpty) locales[0]?.toLanguageTag() else null
        val displayLocale = resources.configuration.locales[0]
        return if (tag.isNullOrEmpty()) {
            getString(R.string.settings_theme_auto).substringBefore('(').trim()
                .ifEmpty { displayLocale.displayLanguage }
        } else {
            Locale.forLanguageTag(tag).getDisplayName(displayLocale)
                .replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(displayLocale) else it.toString()
                }
        }
    }

    private fun setupNavigationPicker(root: View) {
        renderNavigationOptions(root)
        root.findViewById<View>(R.id.navigationResetButton).setOnClickListener {
            PreferencesManager.resetNavigationVisibility()
            renderNavigationOptions(root)
            (activity as? MainActivity)?.refreshNavigationRailVisibility()
            Toast.makeText(
                requireContext(),
                R.string.settings_navigation_reset_done,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun renderNavigationOptions(root: View) {
        val container = root.findViewById<GridLayout>(R.id.navigationOptionsContainer)
        container.removeAllViews()
        val visibleKeys = PreferencesManager.getVisibleNavigationKeys(
            NavigationRailCatalog.customizableKeys
        )
        // Column count follows the window width (@integer/set_nav_columns).
        val columns = container.columnCount.coerceAtLeast(1)
        val halfGap = resources.getDimensionPixelSize(R.dimen.set_nav_column_gap) / 2

        NavigationRailCatalog.customizableOptions.forEachIndexed { index, option ->
            val row = layoutInflater.inflate(
                R.layout.item_navigation_toggle,
                container,
                false,
            )
            row.layoutParams = gridCellParams(row.layoutParams.height, index % columns, columns, halfGap)
            row.findViewById<ImageView>(R.id.navigationOptionIcon)
                .setImageResource(option.iconRes)
            row.findViewById<TextView>(R.id.navigationOptionLabel)
                .setText(option.labelRes)
            val toggle = row.findViewById<MaterialSwitch>(R.id.navigationOptionSwitch)
            toggle.isChecked = option.key in visibleKeys

            fun updateAccessibility(checked: Boolean) {
                row.contentDescription = getString(
                    R.string.settings_navigation_option_state,
                    getString(option.labelRes),
                    getString(
                        if (checked) R.string.settings_navigation_shown
                        else R.string.settings_navigation_hidden
                    ),
                )
            }

            fun persist(checked: Boolean) {
                if (toggle.isChecked != checked) toggle.isChecked = checked
                updateAccessibility(checked)
                PreferencesManager.setNavigationItemVisible(
                    option.key,
                    checked,
                    NavigationRailCatalog.customizableKeys,
                )
                (activity as? MainActivity)?.refreshNavigationRailVisibility()
            }

            updateAccessibility(toggle.isChecked)
            toggle.setOnCheckedChangeListener { _, checked -> persist(checked) }
            row.setOnClickListener { toggle.toggle() }
            container.addView(row)
        }
    }

    // Equal weighted columns; the column gap is split between neighbouring cells.
    private fun gridCellParams(height: Int, column: Int, columns: Int, halfGap: Int) =
        GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL),
            GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL, 1f),
        ).apply {
            width = 0
            this.height = height
            marginStart = if (column > 0) halfGap else 0
            marginEnd = if (column < columns - 1) halfGap else 0
        }
}
