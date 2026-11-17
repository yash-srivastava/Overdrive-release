package com.overdrive.app.ui.view

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.overdrive.app.R
import com.overdrive.app.ui.ClusterTextInputActivity
import com.overdrive.app.util.DaemonHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Optional app tools kept outside the projection and mirror lifecycle implementation. */
object ProjectionAppTools {
    fun attach(fragment: Fragment, root: View, selectedPackage: () -> String?) {
        val dock = root.findViewById<android.view.ViewGroup>(R.id.projectionDock) ?: return
        val original = dock.getChildAt(0) as? LinearLayout ?: return
        val ctx = root.context
        // The landscape dock starts as a horizontal action row. Preserve its own
        // children and parents (spinner/key-menu setup depends on them), then add
        // tools on a second row instead of squeezing them beside Cast/Stop/Adjust.
        val column = if (original.orientation == LinearLayout.HORIZONTAL) {
            val outer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(original.paddingLeft, original.paddingTop,
                    original.paddingRight, original.paddingBottom)
            }
            val dockParams = original.layoutParams
            dock.removeView(original)
            original.setPadding(0, 0, 0, 0)
            outer.addView(original, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            dock.addView(outer, dockParams)
            outer
        } else original
        val row = ProjectionActionRow(ctx)
        fun button(label: Int, icon: Int, action: () -> Unit) {
            row.addView(ProjectionToolsUi.button(ctx, label, icon = icon).apply {
                setOnClickListener { action() }
            }, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button(R.string.projection_tools_title, R.drawable.ic_settings) { toolsDialog(fragment, selectedPackage) }
        button(R.string.cluster_layouts_title, R.drawable.ic_dashboard) { ProjectionLayoutTools.open(fragment) }
        column.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * ctx.resources.displayMetrics.density).toInt()
        })
    }

    private fun toolsDialog(fragment: Fragment, selectedPackage: () -> String?) {
        val owner = viewOwner(fragment) ?: return
        val ctx = fragment.requireContext()
        val body = ProjectionToolsUi.body(ctx)
        ProjectionToolsUi.addSection(body, ProjectionToolsUi.text(ctx,
            ctx.getString(R.string.projection_tools_description)), 0)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.projection_tools_dialog_title)
            .setView(ProjectionToolsUi.scroll(body))
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun option(title: Int, summary: Int, icon: Int, action: () -> Unit) {
            val card = MaterialCardView(ctx).apply {
                radius = resources.getDimension(R.dimen.card_radius_standard)
                cardElevation = 0f
                setCardBackgroundColor(MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorSurfaceContainer))
                strokeWidth = ProjectionToolsUi.dp(ctx, 1)
                strokeColor = MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorOutlineVariant)
                isClickable = true
                isFocusable = true
                contentDescription = ctx.getString(title) + ". " + ctx.getString(summary)
                setOnClickListener {
                    if (currentView(fragment, owner)) action()
                }
            }
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val inset = resources.getDimensionPixelSize(R.dimen.card_padding_standard)
                setPadding(inset, inset, inset, inset)
                minimumHeight = resources.getDimensionPixelSize(R.dimen.dashboard_modern_touch_target)
            }
            content.addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ProjectionToolsUi.dp(ctx, 24), ProjectionToolsUi.dp(ctx, 24)).apply {
                marginEnd = ProjectionToolsUi.dp(ctx, 16)
            })
            val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(ProjectionToolsUi.text(ctx, ctx.getString(title), true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            ProjectionToolsUi.addSection(labels, ProjectionToolsUi.text(ctx, ctx.getString(summary)).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, 4)
            content.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ProjectionToolsUi.dp(ctx, 24), ProjectionToolsUi.dp(ctx, 24)).apply {
                marginStart = ProjectionToolsUi.dp(ctx, 12)
            })
            card.addView(content)
            ProjectionToolsUi.addSection(body, card)
        }
        option(R.string.projection_tools_navigation, R.string.projection_tools_navigation_description,
            R.drawable.ic_route) {
            dialog.dismiss()
            guidanceDialog(fragment)
        }
        option(R.string.projection_tools_density, R.string.projection_tools_density_description,
            R.drawable.ic_crop_free) {
            val pkg = selectedPackage()
            if (pkg.isNullOrEmpty()) {
                Toast.makeText(ctx, R.string.projection_tools_select_app, Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                densityDialog(fragment, pkg)
            }
        }
        option(R.string.projection_app_keyboard, R.string.projection_tools_keyboard_description,
            R.drawable.ic_keyboard) {
            dialog.dismiss()
            ClusterTextInputActivity.open(ctx)
        }
        showOwned(fragment, owner, dialog)
    }

    private fun densityDialog(fragment: Fragment, pkg: String) {
        val owner = viewOwner(fragment) ?: return
        val ctx = fragment.requireContext()
        owner.lifecycleScope.launch {
            val (response, label) = withContext(Dispatchers.IO) {
                val response = request("/api/cluster/apps/density?package=" + URLEncoder.encode(pkg, "UTF-8"))
                val label = runCatching {
                    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                response to label
            }
            if (!currentView(fragment, owner)) return@launch
            if (response?.optBoolean("success") != true) {
                Toast.makeText(ctx, R.string.projection_tools_daemon_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            val body = ProjectionToolsUi.body(ctx)
            ProjectionToolsUi.addSection(body, ProjectionToolsUi.text(ctx,
                ctx.getString(R.string.projection_density_description, label)), 0)
            val application = response.optJSONObject("density")?.optJSONObject("lastApplication")
            if (application != null && application.optString("package") == pkg
                && application.optInt("requestedDpi") == response.optInt("dpi")
                && application.optString("state") == "failed") {
                ProjectionToolsUi.addSection(body, ProjectionToolsUi.text(ctx,
                    ctx.getString(R.string.projection_density_not_applied, application.optString("reason"))))
            }
            val field = TextInputLayout(ctx).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                hint = ctx.getString(R.string.projection_tools_density_value)
                helperText = ctx.getString(R.string.projection_tools_density_default)
            }
            val input = TextInputEditText(field.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(response.optInt("dpi", 0).toString())
                setSelectAllOnFocus(true)
                setSingleLine()
                minimumHeight = resources.getDimensionPixelSize(R.dimen.dashboard_modern_touch_target)
            }
            field.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            ProjectionToolsUi.addSection(body, field)
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.projection_tools_density)
                .setView(ProjectionToolsUi.scroll(body))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.projection_tools_save, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!currentView(fragment, owner)) return@setOnClickListener
                    val dpi = input.text.toString().toIntOrNull()
                    if (dpi == null || (dpi != 0 && dpi !in 96..480)) {
                        field.error = ctx.getString(R.string.projection_density_range)
                        return@setOnClickListener
                    }
                    field.error = null
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    owner.lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            request("/api/cluster/apps/density",
                                JSONObject().put("package", pkg).put("dpi", dpi))
                        }
                        if (!currentView(fragment, owner) || !dialog.isShowing) return@launch
                        if (saved?.optBoolean("success") == true) {
                            dialog.dismiss()
                            Toast.makeText(ctx, R.string.projection_density_saved, Toast.LENGTH_LONG).show()
                        } else {
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            field.error = ctx.getString(R.string.projection_tools_save_failed)
                        }
                    }
                }
            }
            showOwned(fragment, owner, dialog)
        }
    }

    private fun guidanceDialog(fragment: Fragment) {
        val owner = viewOwner(fragment) ?: return
        val ctx = fragment.requireContext()
        owner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { request("/api/cluster/guidance") }
            if (!currentView(fragment, owner)) return@launch
            if (status?.optBoolean("success") != true) {
                Toast.makeText(ctx, R.string.projection_tools_daemon_unavailable, Toast.LENGTH_LONG).show()
                return@launch
            }
            val values = booleanArrayOf(status.optBoolean("enabled", false),
                status.optBoolean("di5StripEnabled", false))
            val body = ProjectionToolsUi.body(ctx)
            fun setting(index: Int, title: Int, description: Int, supported: Boolean, mode: Int) {
                val card = ProjectionToolsUi.card(ctx)
                val toggle = MaterialSwitch(ctx).apply {
                    setText(title)
                    isChecked = values[index]
                    isEnabled = supported
                    minimumHeight = resources.getDimensionPixelSize(R.dimen.dashboard_modern_touch_target)
                    setOnCheckedChangeListener { _, checked ->
                        if (currentView(fragment, owner)) values[index] = checked
                    }
                }
                card.addView(toggle, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val detail = ctx.getString(description) +
                    if (supported) "" else "\n" + ctx.getString(mode)
                ProjectionToolsUi.addSection(card, ProjectionToolsUi.text(ctx, detail), 4)
                ProjectionToolsUi.addSection(body, card)
            }
            setting(0, R.string.projection_tools_di3_title, R.string.projection_tools_di3_description,
                status.optBoolean("windshieldHudSupported", false), R.string.projection_tools_di3_only)
            setting(1, R.string.projection_tools_di5_title, R.string.projection_tools_di5_description,
                status.optBoolean("di5StripExperimental", false), R.string.projection_tools_di5_only)
            val setup = ProjectionToolsUi.card(ctx).apply {
                addView(ProjectionToolsUi.text(ctx, ctx.getString(R.string.projection_tools_setup), true))
                ProjectionToolsUi.addSection(this, ProjectionToolsUi.text(ctx,
                    ctx.getString(if (!status.optBoolean("windshieldHudSupported", false) &&
                    status.optBoolean("di5StripExperimental", false)) {
                    R.string.projection_guidance_setup_di5
                } else R.string.projection_guidance_setup)), 8)
            }
            ProjectionToolsUi.addSection(body, setup)
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.projection_tools_navigation)
                .setView(ProjectionToolsUi.scroll(body))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.projection_tools_save, null)
                .create()
            ProjectionToolsUi.addSection(body, ProjectionToolsUi.button(ctx,
                R.string.projection_guidance_notifications).apply {
                setOnClickListener {
                    if (currentView(fragment, owner)) {
                        dialog.dismiss()
                        fragment.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }
            })
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!currentView(fragment, owner)) return@setOnClickListener
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    owner.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            request("/api/cluster/guidance/settings", JSONObject()
                                .put("enabled", values[0]).put("di5StripEnabled", values[1]))
                        }
                        if (!currentView(fragment, owner) || !dialog.isShowing) return@launch
                        if (result?.optBoolean("success", false) == true) {
                            dialog.dismiss()
                            Toast.makeText(ctx, R.string.projection_guidance_saved, Toast.LENGTH_LONG).show()
                        } else {
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            Toast.makeText(ctx, R.string.projection_tools_save_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            showOwned(fragment, owner, dialog)
        }
    }

    private fun viewOwner(fragment: Fragment): LifecycleOwner? =
        if (!fragment.isAdded || fragment.view == null ||
            fragment.activity?.let { it.isFinishing || it.isDestroyed } != false) null
        else runCatching { fragment.viewLifecycleOwner }.getOrNull()
            ?.takeIf { it.lifecycle.currentState != Lifecycle.State.DESTROYED }

    private fun currentView(fragment: Fragment, owner: LifecycleOwner) = viewOwner(fragment) === owner

    private fun showOwned(fragment: Fragment, owner: LifecycleOwner, dialog: android.app.Dialog) {
        if (!currentView(fragment, owner)) return
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        owner.lifecycle.addObserver(observer)
        try {
            dialog.show()
            ProjectionToolsUi.fit(dialog)
        } catch (_: android.view.WindowManager.BadTokenException) {
            owner.lifecycle.removeObserver(observer)
        }
    }

    private fun request(path: String, payload: JSONObject? = null): JSONObject? {
        val connection = try {
            DaemonHttpClient.open(path, if (payload == null) "GET" else "POST", 2_000, 5_000)
        } catch (_: Exception) { return null }
        return try {
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }
}
