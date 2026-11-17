package com.overdrive.app.ui.view

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.overdrive.app.R
import com.overdrive.app.util.DaemonHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/** Opt-in Layout management; all daemon requests and package queries run off the UI thread. */
object ProjectionLayoutTools {
    private data class App(val label: String, val pkg: String, val available: Boolean = true) {
        override fun toString(): String = label
    }

    fun open(fragment: Fragment) {
        val owner = viewOwner(fragment) ?: return
        val appContext = fragment.context?.applicationContext ?: return
        owner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { request("/api/cluster/layouts") }
            if (!currentView(fragment, owner)) return@launch
            if (status?.optBoolean("success", false) != true) {
                toast(fragment, R.string.projection_tools_daemon_unavailable)
                return@launch
            }
            val labels = withContext(Dispatchers.IO) {
                val result = HashMap<String, String>()
                result[appContext.packageName] = appContext.getString(R.string.cluster_layouts_roadsense)
                val presets = status.optJSONArray("presets") ?: JSONArray()
                for (i in 0 until presets.length()) {
                    val slots = presets.optJSONObject(i)?.optJSONArray("slots") ?: continue
                    for (j in 0 until slots.length()) {
                        val pkg = slots.optJSONObject(j)?.optString("package").orEmpty()
                        if (pkg.isBlank() || result.containsKey(pkg)) continue
                        result[pkg] = runCatching {
                            val app = appContext.packageManager.getApplicationInfo(pkg, 0)
                            appContext.packageManager.getApplicationLabel(app).toString()
                        }.getOrDefault(pkg)
                    }
                }
                result
            }
            if (!currentView(fragment, owner)) return@launch
            manager(fragment, status, labels)
        }
    }

    private fun manager(fragment: Fragment, status: JSONObject, labels: Map<String, String>) {
        val owner = viewOwner(fragment) ?: return
        val builder = MaterialAlertDialogBuilder(fragment.requireContext())
        val ctx = builder.context
        val presets = status.optJSONArray("presets") ?: JSONArray()
        var current = status
        var refresh: Job? = null
        var reportedFailure = ""
        val body = ProjectionToolsUi.body(ctx)
        append(body, ProjectionToolsUi.text(ctx, ctx.getString(R.string.cluster_layouts_intro)), 0)
        val statusCard = ProjectionToolsUi.card(ctx)
        val statusTitle = ProjectionToolsUi.text(ctx, "", title = true)
        val statusDetail = ProjectionToolsUi.text(ctx, "")
        val statusHelp = ProjectionToolsUi.text(ctx, ctx.getString(R.string.cluster_layouts_touch_help))
        val stop = ProjectionToolsUi.button(ctx, R.string.cluster_layouts_stop)
        append(statusCard, statusTitle, 0)
        append(statusCard, statusDetail, 4)
        append(statusCard, statusHelp, 8)
        append(statusCard, stop, 12)
        ProjectionToolsUi.addSection(body, statusCard)
        val newLayout = ProjectionToolsUi.button(ctx, R.string.cluster_layouts_new, primary = true)
        append(body, newLayout, 12)
        append(body, ProjectionToolsUi.text(ctx,
            ctx.getString(R.string.cluster_layouts_saved_presets), title = true), 20)
        val presetControls = ArrayList<PresetControls>()
        if (presets.length() == 0) {
            val empty = ProjectionToolsUi.card(ctx)
            append(empty, ProjectionToolsUi.text(ctx,
                ctx.getString(R.string.cluster_layouts_empty_title), title = true), 0)
            append(empty, ProjectionToolsUi.text(ctx, ctx.getString(R.string.cluster_layouts_empty)), 8)
            ProjectionToolsUi.addSection(body, empty)
        }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        fun runAction(preset: JSONObject, action: Int) {
            if (!currentView(fragment, owner)) return
            freshStatus(fragment, owner) { fresh ->
                if (!dialog.isShowing) return@freshStatus
                current = fresh
                val id = preset.optString("id")
                if (action == 0) {
                    if (!fresh.optBoolean("supported", false)) {
                        Toast.makeText(ctx, readableReason(fragment, fresh)
                            .ifBlank { ctx.getString(R.string.cluster_layouts_unavailable) },
                            Toast.LENGTH_LONG).show()
                    } else if (fresh.optBoolean("busy", false)) {
                        Toast.makeText(ctx, ctx.getString(R.string.cluster_layouts_busy,
                            fresh.optString("phase")), Toast.LENGTH_LONG).show()
                    } else {
                        dialog.dismiss()
                        change(fragment, "/api/cluster/layouts/start", JSONObject().put("presetId", id))
                    }
                } else if (fresh.optBoolean("busy", false) ||
                    (fresh.optBoolean("active", false) && fresh.optString("presetId") == id)) {
                    toast(fragment, R.string.cluster_layouts_stop_to_edit)
                } else {
                    dialog.dismiss()
                    if (action == 1) edit(fragment, JSONObject(preset.toString()))
                    else confirmDelete(fragment, preset)
                }
            }
        }
        for (i in 0 until presets.length()) {
            val preset = presets.optJSONObject(i) ?: continue
            val card = ProjectionToolsUi.card(ctx)
            val title = ProjectionToolsUi.text(ctx,
                preset.optString("name", preset.optString("id")), title = true)
            val badge = ProjectionToolsUi.text(ctx, "")
            append(card, title, 0)
            append(card, badge, 4)
            append(card, preview(ctx, preset) { pkg -> labels[pkg] ?: pkg }, 12)
            val slots = preset.optJSONArray("slots") ?: JSONArray()
            val summary = (0 until slots.length()).mapNotNull { index ->
                slots.optJSONObject(index)?.let {
                    val pkg = it.optString("package")
                    ctx.getString(R.string.cluster_layouts_pane_app, index + 1, labels[pkg] ?: pkg)
                }
            }.joinToString("\n")
            append(card, ProjectionToolsUi.text(ctx, summary), 8)
            val actions = ProjectionActionRow(ctx)
            val start = ProjectionToolsUi.button(ctx, R.string.cluster_layouts_start, primary = true)
            val edit = ProjectionToolsUi.button(ctx, R.string.cluster_layouts_edit)
            val delete = ProjectionToolsUi.button(ctx, R.string.cluster_layouts_delete)
            start.setOnClickListener { runAction(preset, 0) }
            edit.setOnClickListener { runAction(preset, 1) }
            delete.setOnClickListener { runAction(preset, 2) }
            actions.addView(start)
            actions.addView(edit)
            actions.addView(delete)
            append(card, actions, 12)
            val explanation = ProjectionToolsUi.text(ctx, "")
            append(card, explanation, 8)
            presetControls += PresetControls(preset.optString("id"), badge, start, edit,
                delete, explanation)
            ProjectionToolsUi.addSection(body, card)
        }
        dialog = builder.setTitle(R.string.cluster_layouts_title)
            .setView(ProjectionToolsUi.scroll(body))
            .setPositiveButton(android.R.string.ok, null)
            .create()
        newLayout.setOnClickListener {
            if (!currentView(fragment, owner)) return@setOnClickListener
            dialog.dismiss()
            chooseTemplate(fragment)
        }
        stop.setOnClickListener {
            if (!currentView(fragment, owner)) return@setOnClickListener
            dialog.dismiss()
            change(fragment, "/api/cluster/layouts/stop", JSONObject())
        }
        fun render(snapshot: JSONObject) {
            current = snapshot
            val activeId = snapshot.optString("presetId", "")
            val busy = snapshot.optBoolean("busy")
            val active = snapshot.optBoolean("active")
            val supported = snapshot.optBoolean("supported", false)
            val startupFailure = snapshot.optString("failure")
            val state = when {
                startupFailure.isNotBlank() && !active ->
                    ctx.getString(R.string.cluster_layouts_failed)
                busy ->
                    ctx.getString(R.string.cluster_layouts_busy, snapshot.optString("phase"))
                active -> {
                    val preset = (0 until presets.length()).mapNotNull { presets.optJSONObject(it) }
                        .firstOrNull { it.optString("id") == activeId }
                    ctx.getString(R.string.cluster_layouts_active, preset?.optString("name") ?: activeId)
                }
                else -> ctx.getString(R.string.cluster_layouts_idle)
            }
            val details = listOf(snapshot.optString("platform", ""), readableReason(fragment, snapshot))
                .filter { it.isNotBlank() }.joinToString(" · ")
            statusTitle.text = state
            statusDetail.text = details
            statusDetail.visibility = if (details.isBlank()) View.GONE else View.VISIBLE
            statusHelp.visibility = if (active || busy) View.VISIBLE else View.GONE
            stop.visibility = if (active || busy) View.VISIBLE else View.GONE
            if (startupFailure.isNotBlank() && !active) {
                val key = "${snapshot.optLong("generation")}:$startupFailure"
                if (reportedFailure != key) {
                    reportedFailure = key
                    failure(fragment, snapshot)
                }
            }
            presetControls.forEach { controls ->
                val running = active && activeId == controls.id
                controls.badge.text = when {
                    running -> ctx.getString(R.string.cluster_layouts_running)
                    busy -> ctx.getString(R.string.cluster_layouts_wait)
                    !supported -> ctx.getString(R.string.cluster_layouts_unavailable)
                    else -> ctx.getString(R.string.cluster_layouts_ready)
                }
                controls.start.isEnabled = supported && !busy && !running
                controls.edit.isEnabled = !busy && !running
                controls.delete.isEnabled = !busy && !running
                controls.explanation.text = when {
                    busy -> ctx.getString(R.string.cluster_layouts_wait)
                    running -> ctx.getString(R.string.cluster_layouts_stop_to_edit)
                    !supported -> readableReason(fragment, snapshot)
                        .ifBlank { ctx.getString(R.string.cluster_layouts_unavailable) }
                    else -> ""
                }
                controls.explanation.visibility =
                    if (controls.explanation.text.isBlank()) View.GONE else View.VISIBLE
            }
        }
        if (!showOwned(fragment, owner, dialog) { refresh?.cancel() }) return
        render(current)
        refresh = owner.lifecycleScope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
            while (currentView(fragment, owner) && dialog.isShowing
                && android.os.SystemClock.elapsedRealtime() < deadline) {
                delay(if (current.optBoolean("busy")) 1_000 else 3_000)
                val updated = withContext(Dispatchers.IO) { request("/api/cluster/layouts/status") }
                if (!currentView(fragment, owner) || !dialog.isShowing) return@launch
                if (updated?.optBoolean("success") == true) render(updated)
            }
        }
    }

    private data class PresetControls(
        val id: String,
        val badge: android.widget.TextView,
        val start: MaterialButton,
        val edit: MaterialButton,
        val delete: MaterialButton,
        val explanation: android.widget.TextView
    )

    private fun freshStatus(fragment: Fragment, owner: LifecycleOwner, action: (JSONObject) -> Unit) {
        owner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { request("/api/cluster/layouts/status") }
            if (!currentView(fragment, owner)) return@launch
            if (status?.optBoolean("success", false) == true) action(status)
            else failure(fragment, status)
        }
    }

    private fun confirmDelete(fragment: Fragment, preset: JSONObject) {
        val owner = viewOwner(fragment) ?: return
        val ctx = fragment.requireContext()
        val id = preset.optString("id")
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.cluster_layouts_delete)
            .setMessage(ctx.getString(R.string.cluster_layouts_delete_confirm, preset.optString("name")))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cluster_layouts_delete) { _, _ ->
                if (!currentView(fragment, owner)) return@setPositiveButton
                freshStatus(fragment, owner) { status ->
                    if (status.optBoolean("busy") ||
                        (status.optBoolean("active") && status.optString("presetId") == id)) {
                        toast(fragment, R.string.cluster_layouts_stop_to_edit)
                        open(fragment)
                    } else change(fragment, "/api/cluster/layouts/presets?id=" +
                        URLEncoder.encode(id, "UTF-8"), null, "DELETE")
                }
            }.create()
        showOwned(fragment, owner, dialog)
    }

    private fun chooseTemplate(fragment: Fragment) {
        val owner = viewOwner(fragment) ?: return
        val builder = MaterialAlertDialogBuilder(fragment.requireContext())
        val ctx = builder.context
        val body = ProjectionToolsUi.body(ctx)
        append(body, ProjectionToolsUi.text(ctx, ctx.getString(R.string.cluster_layouts_template_intro)), 0)
        val templates = ProjectionActionRow(ctx, ProjectionToolsUi.dp(ctx, 200))
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val titles = intArrayOf(R.string.cluster_layouts_two_columns,
            R.string.cluster_layouts_three_columns, R.string.cluster_layouts_main_two)
        for (choice in titles.indices) {
            val preset = template(choice)
            val card = ProjectionToolsUi.card(ctx)
            append(card, ProjectionToolsUi.text(ctx, ctx.getString(titles[choice]), title = true), 0)
            val panePreview = preview(ctx, preset).apply {
                val slots = preset.optJSONArray("slots") ?: JSONArray()
                setPanes((0 until slots.length()).mapNotNull { index ->
                    slots.optJSONObject(index)?.let {
                        ClusterLayoutPreviewView.Pane(it.optInt("x"), it.optInt("y"),
                            it.optInt("width"), it.optInt("height"),
                            String.format(resources.configuration.locales[0], "%d", index + 1))
                    }
                })
                contentDescription = ctx.getString(R.string.cluster_layouts_preview_description,
                    (0 until slots.length()).joinToString(" · ") {
                        ctx.getString(R.string.cluster_layouts_slot, it + 1)
                    })
            }
            append(card, panePreview, 12)
            val use = ProjectionToolsUi.button(ctx, R.string.cluster_layouts_use_template, primary = true)
            use.setOnClickListener {
                if (!currentView(fragment, owner)) return@setOnClickListener
                dialog.dismiss()
                edit(fragment, preset)
            }
            append(card, use, 12)
            templates.addView(card)
        }
        ProjectionToolsUi.addSection(body, templates)
        dialog = builder.setTitle(R.string.cluster_layouts_new)
            .setView(ProjectionToolsUi.scroll(body))
            .setNegativeButton(android.R.string.cancel, null).create()
        showOwned(fragment, owner, dialog)
    }

    private fun template(choice: Int): JSONObject {
        val slots = JSONArray()
        if (choice == 2) {
            slots.put(slot(0, 0, 0, 6500, 10000))
            slots.put(slot(1, 6500, 0, 3500, 5000))
            slots.put(slot(2, 6500, 5000, 3500, 5000))
        } else {
            val count = if (choice == 0) 2 else 3
            for (i in 0 until count) {
                val left = i * 10000 / count
                val right = (i + 1) * 10000 / count
                slots.put(slot(i, left, 0, right - left, 10000))
            }
        }
        return JSONObject().put("id", UUID.randomUUID().toString())
            .put("name", "").put("slots", slots)
    }

    private fun preview(ctx: Context, preset: JSONObject,
                        label: (String) -> String = { "" }): ClusterLayoutPreviewView =
        ClusterLayoutPreviewView(ctx).apply {
            setPanes(previewPanes(ctx, preset, label))
        }

    private fun previewPanes(ctx: Context, preset: JSONObject,
                             label: (String) -> String): List<ClusterLayoutPreviewView.Pane> {
        val slots = preset.optJSONArray("slots") ?: return emptyList()
        return (0 until slots.length()).mapNotNull { index ->
            slots.optJSONObject(index)?.let {
                val app = label(it.optString("package"))
                ClusterLayoutPreviewView.Pane(it.optInt("x"), it.optInt("y"),
                    it.optInt("width"), it.optInt("height"),
                    if (app.isBlank()) ctx.getString(R.string.cluster_layouts_slot, index + 1)
                    else ctx.getString(R.string.cluster_layouts_preview_pane, index + 1, app))
            }
        }
    }

    private fun append(parent: LinearLayout, child: View, top: Int) {
        parent.addView(child, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (top * parent.resources.displayMetrics.density).toInt()
        })
    }

    private fun slot(index: Int, x: Int, y: Int, width: Int, height: Int): JSONObject =
        JSONObject().put("id", "pane${index + 1}").put("package", "")
            .put("x", x).put("y", y).put("width", width).put("height", height).put("dpi", 160)

    private fun edit(fragment: Fragment, preset: JSONObject) {
        val owner = viewOwner(fragment) ?: return
        owner.lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) { request("/api/vehicle/cluster-apps") }
            val appContext = fragment.context?.applicationContext ?: return@launch
            val available = withContext(Dispatchers.IO) {
                val result = ArrayList<App>()
                val array = response?.optJSONArray("apps") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val app = array.optJSONObject(i) ?: continue
                    val pkg = app.optString("package")
                    if (pkg.isNotBlank() && pkg != appContext.packageName) {
                        result.add(App(app.optString("label", pkg), pkg))
                    }
                }
                result
            }
            if (!currentView(fragment, owner)) return@launch
            if (available.isEmpty()) toast(fragment, R.string.cluster_layouts_no_apps)
            available.add(0, App(fragment.getString(R.string.cluster_layouts_roadsense), appContext.packageName))
            editorDialog(fragment, owner, preset, available)
        }
    }

    private fun editorDialog(fragment: Fragment, owner: LifecycleOwner, preset: JSONObject, apps: List<App>) {
        if (!currentView(fragment, owner)) return
        val builder = MaterialAlertDialogBuilder(fragment.requireContext())
        val ctx = builder.context
        val slots = preset.optJSONArray("slots") ?: return
        val column = ProjectionToolsUi.body(ctx)
        append(column, ProjectionToolsUi.text(ctx, ctx.getString(R.string.cluster_layouts_editor_intro)), 0)
        val nameBox = inputBox(ctx, R.string.cluster_layouts_name)
        val name = TextInputEditText(nameBox.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(preset.optString("name", ""))
            maxLines = 1
        }
        nameBox.addView(name, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        name.doAfterTextChanged { nameBox.error = null }
        append(column, nameBox, 16)
        val previewCard = ProjectionToolsUi.card(ctx)
        append(previewCard, ProjectionToolsUi.text(ctx,
            ctx.getString(R.string.cluster_layouts_preview_title), title = true), 0)
        val panePreview = preview(ctx, preset)
        append(previewCard, panePreview, 8)
        ProjectionToolsUi.addSection(column, previewCard)
        val picks = ArrayList<PaneEditor>()
        fun updatePreview() {
            val panes = (0 until slots.length()).mapNotNull { index ->
                slots.optJSONObject(index)?.let { current ->
                    val chosen = picks.getOrNull(index)?.selectedApp()
                    val label = if (chosen == null || chosen.pkg.isBlank())
                        ctx.getString(R.string.cluster_layouts_slot, index + 1)
                    else ctx.getString(R.string.cluster_layouts_preview_pane, index + 1, chosen.label)
                    ClusterLayoutPreviewView.Pane(current.optInt("x"), current.optInt("y"),
                        current.optInt("width"), current.optInt("height"), label)
                }
            }
            panePreview.setPanes(panes)
        }
        for (i in 0 until slots.length()) {
            val current = slots.optJSONObject(i) ?: continue
            val card = ProjectionToolsUi.card(ctx)
            append(card, ProjectionToolsUi.text(ctx,
                ctx.getString(R.string.cluster_layouts_slot, i + 1), title = true), 0)
            val pkg = current.optString("package", "")
            val choices = mutableListOf(App(ctx.getString(R.string.cluster_layouts_choose_app), "", false))
            choices.addAll(apps)
            if (pkg.isNotBlank() && choices.none { it.pkg == pkg }) {
                choices.add(App(ctx.getString(R.string.cluster_layouts_missing_app, pkg), pkg, false))
            }
            val appBox = inputBox(ctx, R.string.cluster_layouts_app_label).apply {
                endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            }
            val app = MaterialAutoCompleteTextView(appBox.context).apply {
                inputType = InputType.TYPE_NULL
                keyListener = null
                maxLines = 2
                setAdapter(ArrayAdapter(ctx, R.layout.item_cluster_layout_app, choices))
            }
            appBox.addView(app, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val dpiBox = inputBox(ctx, R.string.cluster_layouts_dpi).apply {
                helperText = ctx.getString(R.string.cluster_layouts_dpi_help)
            }
            val dpi = TextInputEditText(dpiBox.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(current.optInt("dpi", 160).toString())
                maxLines = 1
            }
            dpiBox.addView(dpi, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val pane = PaneEditor(card, appBox, app, choices, dpiBox, dpi,
                choices.indexOfFirst { it.pkg == pkg }.coerceAtLeast(0))
            app.setText(choices[pane.selected].label, false)
            app.setOnItemClickListener { _, _, position, _ ->
                if (!currentView(fragment, owner)) return@setOnItemClickListener
                pane.selected = position
                appBox.error = null
                panePreview.setSelectedPane(i)
                updatePreview()
            }
            val appFocusListener = app.onFocusChangeListener
            app.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                appFocusListener?.onFocusChange(view, focused)
                if (focused) panePreview.setSelectedPane(i)
            }
            val dpiFocusListener = dpi.onFocusChangeListener
            dpi.onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                dpiFocusListener?.onFocusChange(view, focused)
                if (focused) panePreview.setSelectedPane(i)
            }
            dpi.doAfterTextChanged { dpiBox.error = null }
            append(card, appBox, 12)
            append(card, dpiBox, 12)
            picks += pane
            ProjectionToolsUi.addSection(column, card)
        }
        updatePreview()
        val scroll = ProjectionToolsUi.scroll(column)
        panePreview.onPaneSelected = { index ->
            if (currentView(fragment, owner)) {
                picks.getOrNull(index)?.let { pane ->
                    scroll.scrollTo(0, pane.card.top + column.top)
                    pane.app.requestFocus()
                    pane.app.post {
                        if (currentView(fragment, owner) && pane.app.isAttachedToWindow)
                            pane.app.showDropDown()
                    }
                }
            }
        }
        val dialog = builder
            .setTitle(R.string.cluster_layouts_title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.projection_tools_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener save@{
                if (!currentView(fragment, owner)) return@save
                val title = name.text.toString().trim()
                if (title.isBlank() || title.length > 80) {
                    nameBox.error = ctx.getString(R.string.cluster_layouts_name_required)
                    name.requestFocus()
                    scroll.smoothScrollTo(0, nameBox.top)
                    return@save
                }
                picks.forEach { it.appBox.error = null; it.dpiBox.error = null }
                val chosen = picks.map { it.selectedApp() }
                if (picks.size != slots.length()) {
                    toast(fragment, R.string.cluster_layouts_assign_apps)
                    return@save
                }
                if (chosen.any { it == null || !it.available }) {
                    val invalid = chosen.indexOfFirst { it == null || !it.available }
                    chosen.forEachIndexed { index, app ->
                        if (app == null || !app.available) {
                            picks[index].appBox.error = ctx.getString(if (app?.pkg.isNullOrBlank())
                                R.string.cluster_layouts_choose_app else R.string.cluster_layouts_app_unavailable)
                        }
                    }
                    focusPane(scroll, picks[invalid])
                    return@save
                }
                if (chosen.map { it!!.pkg }.distinct().size != slots.length()) {
                    val duplicates = chosen.groupingBy { it!!.pkg }.eachCount()
                        .filterValues { it > 1 }.keys
                    picks.forEachIndexed { index, pane ->
                        if (chosen[index]!!.pkg in duplicates) {
                            pane.appBox.error = ctx.getString(R.string.cluster_layouts_assign_apps)
                        }
                    }
                    val first = chosen.indexOfFirst { it!!.pkg in duplicates }
                    focusPane(scroll, picks[first])
                    return@save
                }
                val dpis = picks.map { it.dpi.text.toString().toIntOrNull() }
                val invalid = dpis.indexOfFirst { it == null || it !in 96..480 }
                if (invalid >= 0) {
                    picks[invalid].dpiBox.error = ctx.getString(R.string.cluster_layouts_dpi_range)
                    scroll.smoothScrollTo(0, picks[invalid].card.top)
                    picks[invalid].dpi.requestFocus()
                    return@save
                }
                preset.put("name", title)
                for (i in chosen.indices) {
                    slots.getJSONObject(i).put("package", chosen[i]!!.pkg).put("dpi", dpis[i])
                }
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                owner.lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        request("/api/cluster/layouts/presets", preset, "POST")
                    }
                    if (!currentView(fragment, owner) || !dialog.isShowing) return@launch
                    if (saved?.optBoolean("success", false) == true) {
                        dialog.dismiss()
                        toast(fragment, R.string.cluster_layouts_saved)
                        open(fragment)
                    } else {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        failure(fragment, saved)
                    }
                }
            }
        }
        showOwned(fragment, owner, dialog)
    }

    private data class PaneEditor(
        val card: LinearLayout,
        val appBox: TextInputLayout,
        val app: MaterialAutoCompleteTextView,
        val choices: List<App>,
        val dpiBox: TextInputLayout,
        val dpi: TextInputEditText,
        var selected: Int
    ) {
        fun selectedApp(): App? = choices.getOrNull(selected)
    }

    private fun inputBox(ctx: Context, label: Int): TextInputLayout =
        TextInputLayout(ctx).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = ctx.getString(label)
        }

    private fun focusPane(scroll: android.widget.ScrollView, pane: PaneEditor) {
        scroll.smoothScrollTo(0, pane.card.top)
        pane.app.requestFocus()
    }

    private fun change(fragment: Fragment, path: String, payload: JSONObject?, method: String = "POST") {
        val owner = viewOwner(fragment) ?: return
        owner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { request(path, payload, method) }
            if (!currentView(fragment, owner)) return@launch
            if (result?.optBoolean("success", false) != true) failure(fragment, result)
            open(fragment)
        }
    }

    private fun failure(fragment: Fragment, result: JSONObject?) {
        val ctx = fragment.context ?: return
        val reason = result?.let { readableReason(fragment, it) }.orEmpty()
            .ifBlank { result?.optString("error").orEmpty() }
            .ifBlank { ctx.getString(R.string.projection_tools_daemon_unavailable) }
        Toast.makeText(ctx, ctx.getString(R.string.cluster_layouts_request_failed, reason),
            Toast.LENGTH_LONG).show()
    }

    private fun readableReason(fragment: Fragment, status: JSONObject): String {
        val reason = status.optString("failure").ifBlank { status.optString("reason", "") }
        if (reason.startsWith("di5_layouts_unavailable")) {
            return fragment.getString(R.string.cluster_layouts_di5_unavailable)
        }
        if (reason == "stop_current_cluster_projection_before_starting_layout") {
            return fragment.getString(R.string.cluster_layouts_stop_projection_first)
        }
        return reason.replace('_', ' ')
    }

    private fun toast(fragment: Fragment, text: Int) {
        fragment.context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
    }

    private fun viewOwner(fragment: Fragment): LifecycleOwner? =
        if (!fragment.isAdded || fragment.view == null
            || fragment.activity?.let { it.isFinishing || it.isDestroyed } != false) null
        else runCatching { fragment.viewLifecycleOwner }.getOrNull()
            ?.takeIf { it.lifecycle.currentState != Lifecycle.State.DESTROYED }

    private fun currentView(fragment: Fragment, owner: LifecycleOwner): Boolean =
        viewOwner(fragment) === owner

    private fun showOwned(fragment: Fragment, owner: LifecycleOwner, dialog: android.app.Dialog,
                          onDismiss: () -> Unit = {}): Boolean {
        if (!currentView(fragment, owner)) return false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        dialog.setOnDismissListener {
            owner.lifecycle.removeObserver(observer)
            onDismiss()
        }
        owner.lifecycle.addObserver(observer)
        try {
            dialog.show()
            ProjectionToolsUi.fit(dialog)
        }
        catch (_: android.view.WindowManager.BadTokenException) {
            owner.lifecycle.removeObserver(observer)
            onDismiss()
            return false
        }
        return true
    }

    private fun request(path: String, payload: JSONObject? = null,
                        method: String = if (payload == null) "GET" else "POST"): JSONObject? {
        val connection = try { DaemonHttpClient.open(path, method, 2_000, 10_000) }
            catch (_: Exception) { return null }
        return try {
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { JSONObject(it.readText()) }
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }
}
