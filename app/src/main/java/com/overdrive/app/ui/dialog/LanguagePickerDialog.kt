package com.overdrive.app.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.overdrive.app.R
import com.overdrive.app.server.LocaleManager

/**
 * Language picker rendered as a real M3 [BottomSheetDialog].
 *
 *   - "Auto (follow system)" pinned at the top
 *   - Every supported language in native script with BCP-47 tag on the right
 *   - Current pick gets a check mark on the trailing side
 *   - Selection persists via [LocaleManager] so the WebView and the native UI
 *     come back in the same language on the very next launch
 *
 * Public API is unchanged: [show] takes a context + optional callback.
 */
object LanguagePickerDialog {

    /**
     * Native-script display names for every supported tag. Missing entries
     * fall back to [java.util.Locale.getDisplayName] so adding a new tag to
     * [LocaleManager.SUPPORTED] keeps working without a code change here.
     */
    private val NATIVE_NAMES = mapOf(
        "en" to "English",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "pt-BR" to "Português (Brasil)",
        "es" to "Español",
        "de" to "Deutsch",
        "fr" to "Français",
        "it" to "Italiano",
        "nb" to "Norsk bokmål",
        "nl" to "Nederlands",
        "ja" to "日本語",
        "ko" to "한국어",
        "th" to "ไทย",
        "vi" to "Tiếng Việt",
        "hi" to "हिन्दी",
        "tr" to "Türkçe",
        "ru" to "Русский",
        "ar" to "العربية",
        "cs" to "Čeština",
        "he" to "עברית"
    )

    @JvmOverloads
    @JvmStatic
    fun show(context: Context, onPicked: java.util.function.Consumer<String>? = null) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_language_picker, null) as LinearLayout

        val rv = view.findViewById<RecyclerView>(R.id.rvLanguages)
        rv.layoutManager = LinearLayoutManager(context)

        val dialog = BottomSheetDialog(context, R.style.Theme_Overdrive_M3_BottomSheet).apply {
            setContentView(view)
            setCancelable(true)
        }

        // Open the picker already expanded to a tall, centered state
        // instead of the default collapsed peek at the bottom edge of the
        // screen. The sheet still respects its own max-width / max-height
        // so on very tall displays it won't fill 100% of the viewport.
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            // Tall peek so the sheet visually centers near the middle of
            // the screen rather than peeking from the bottom on tall
            // displays (e.g. BYD Seal portrait 1080×1920).
            val displayMetrics = context.resources.displayMetrics
            behavior.peekHeight = (displayMetrics.heightPixels * 0.7f).toInt()
        }

        val rawCurrent = LocaleManager.getRaw()  // null/auto/<tag>
        val effective = LocaleManager.get()       // resolved tag

        val rows = buildRows(context, rawCurrent, effective)
        rv.adapter = LanguageAdapter(rows) { row ->
            applySelection(context, row.tag)
            onPicked?.accept(row.tag)
            dialog.dismiss()
        }

        // Subtitle: "<N> languages available" — count includes Auto.
        view.findViewById<TextView>(R.id.tvLangPickerSubtitle)?.text =
            context.getString(R.string.language_picker_subtitle_fmt, LocaleManager.SUPPORTED.size)

        view.findViewById<View>(R.id.btnLangPickerClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Apply a chosen language tag (or [LocaleManager.AUTO_TAG]) to both the
     * cross-process file and AppCompat. The activity is recreated by AppCompat
     * automatically so all visible Fragments rehydrate in the new language.
     */
    @JvmStatic
    fun applySelection(context: Context, tag: String) {
        if (tag == LocaleManager.AUTO_TAG) {
            LocaleManager.setAuto()
            broadcastLocaleToWebViews(context, LocaleManager.get())
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            val resolved = LocaleManager.set(tag)
            broadcastLocaleToWebViews(context, resolved)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(resolved))
        }
        // Activities recreate automatically, but plain overlay Services do not receive
        // AppCompat's per-app locale. Re-inflate both overlays against the new locale.
        com.overdrive.app.overlay.StatusOverlayService.refreshTheme(context)
        com.overdrive.app.roadsense.overlay.RoadSenseOverlayService.relocalizeIfRunning()
        com.overdrive.app.overlay.GlassWidgetsOverlayService.relocalizeIfRunning()
    }

    /**
     * Walk the activity's fragment tree and apply the new locale to every
     * attached WebViewFragment. Recurses through child fragment managers
     * so fragments hosted under NavHostFragment are reached too. Same
     * pattern SettingsAppearanceFragment uses for theme broadcasts.
     */
    private fun broadcastLocaleToWebViews(context: Context, lang: String) {
        if (lang.isBlank()) return
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return
        val fm = activity.supportFragmentManager
        collectWebViewFragments(fm).forEach { it.applyLocale(lang) }
    }

    private fun collectWebViewFragments(
        fm: androidx.fragment.app.FragmentManager
    ): List<com.overdrive.app.ui.fragment.WebViewFragment> {
        val out = mutableListOf<com.overdrive.app.ui.fragment.WebViewFragment>()
        for (f in fm.fragments) {
            if (f is com.overdrive.app.ui.fragment.WebViewFragment) out.add(f)
            if (f.isAdded) out.addAll(collectWebViewFragments(f.childFragmentManager))
        }
        return out
    }

    /**
     * Friendly label for the current selection — used by the drawer footer
     * subtitle so users can see at a glance what's active.
     * Format: "<native name>" or "<native name> · Auto" when on auto.
     */
    @JvmStatic
    fun currentLabel(context: Context): String {
        val raw = LocaleManager.getRaw()
        val effective = LocaleManager.get()
        val name = NATIVE_NAMES[effective] ?: effective
        return if (raw == null || raw == LocaleManager.AUTO_TAG) {
            context.getString(R.string.language_label_auto_fmt, name)
        } else {
            name
        }
    }

    private data class Row(
        val tag: String,         // "auto" | "en" | "zh-CN" | …
        val name: String,        // native script
        val subtitle: String?,   // "Auto" for the auto row, null otherwise
        val displayTag: String,  // shown on the right; empty for the auto row
        val isCurrent: Boolean
    )

    private fun buildRows(context: Context, rawCurrent: String?, effective: String): List<Row> {
        val isAuto = rawCurrent == null || rawCurrent == LocaleManager.AUTO_TAG
        val rows = ArrayList<Row>(LocaleManager.SUPPORTED.size + 1)
        rows.add(
            Row(
                tag = LocaleManager.AUTO_TAG,
                name = context.getString(R.string.language_auto_title),
                subtitle = context.getString(R.string.language_auto_subtitle, NATIVE_NAMES[effective] ?: effective),
                displayTag = "",
                isCurrent = isAuto
            )
        )
        for (tag in LocaleManager.SUPPORTED) {
            rows.add(
                Row(
                    tag = tag,
                    name = NATIVE_NAMES[tag] ?: tag,
                    subtitle = null,
                    displayTag = tag,
                    isCurrent = !isAuto && tag == effective
                )
            )
        }
        return rows
    }

    private class LanguageAdapter(
        private val rows: List<Row>,
        private val onClick: (Row) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_language_row, parent, false)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(rows[position], onClick)
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name = itemView.findViewById<TextView>(R.id.tvLangName)
            private val subtitle = itemView.findViewById<TextView>(R.id.tvLangSubtitle)
            private val tag = itemView.findViewById<TextView>(R.id.tvLangTag)
            private val check = itemView.findViewById<ImageView>(R.id.ivLangCheck)

            fun bind(row: Row, onClick: (Row) -> Unit) {
                name.text = row.name
                if (row.subtitle.isNullOrEmpty()) {
                    subtitle.visibility = View.GONE
                } else {
                    subtitle.visibility = View.VISIBLE
                    subtitle.text = row.subtitle
                }
                tag.text = row.displayTag
                tag.visibility = if (row.displayTag.isEmpty()) View.GONE else View.VISIBLE
                check.visibility = if (row.isCurrent) View.VISIBLE else View.INVISIBLE
                itemView.setOnClickListener { onClick(row) }
            }
        }
    }
}
