package com.overdrive.app.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.InputFilter
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.overdrive.app.R
import com.overdrive.app.services.ClusterTextInputService

/** Head-unit keyboard for one verified editable field in a DI5 cast app. */
class ClusterTextInputActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var send: Button
    private lateinit var submit: Button
    private var targetId = 0L
    private var preparedService: ClusterTextInputService? = null
    private var requestGeneration = 0L

    companion object {
        private const val EXTRA_TARGET = "cluster_text_target"

        @JvmStatic fun open(context: Context) = launch(context, 0L)

        @JvmStatic fun openAutomatic(context: Context, targetId: Long) = launch(context, targetId)

        private fun launch(context: Context, targetId: Long) {
            val intent = Intent(context, ClusterTextInputActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TARGET, targetId)
            // Accessibility callbacks belong to the cluster; the keyboard belongs to the head unit.
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            try {
                context.startActivity(intent, options.toBundle())
            } catch (_: RuntimeException) {
                Toast.makeText(context, R.string.cluster_text_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.cluster_text_title)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        status = TextView(this)
        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = getString(R.string.cluster_text_hint)
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(8192))
            isEnabled = false
        }
        val auto = CheckBox(this).apply {
            setText(R.string.cluster_text_auto_prompt)
            isChecked = ClusterTextInputService.isAutoPromptEnabled(this@ClusterTextInputActivity)
            setOnCheckedChangeListener { _, enabled ->
                ClusterTextInputService.setAutoPromptEnabled(this@ClusterTextInputActivity, enabled)
            }
        }
        send = Button(this).apply {
            setText(R.string.cluster_text_send)
            isEnabled = false
            setOnClickListener { sendText(false) }
        }
        submit = Button(this).apply {
            setText(R.string.cluster_text_send_submit)
            visibility = View.GONE
            setOnClickListener { sendText(true) }
        }
        val refresh = Button(this).apply {
            setText(R.string.cluster_text_retry)
            setOnClickListener { prepare(0L) }
        }
        val settings = Button(this).apply {
            setText(R.string.cluster_text_enable_service)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val cancel = Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        for (view in listOf(status, editor, auto, send, submit, refresh, settings, cancel))
            layout.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.65).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        prepare(intent.getLongExtra(EXTRA_TARGET, 0L))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prepare(intent.getLongExtra(EXTRA_TARGET, 0L))
    }

    private fun prepare(expectedTarget: Long) {
        val request = ++requestGeneration
        targetId = 0L
        preparedService = null
        editor.isEnabled = false
        send.isEnabled = false
        submit.visibility = View.GONE
        if (Build.VERSION.SDK_INT < 30) {
            status.setText(R.string.cluster_text_requires_android11)
            return
        }
        val service = ClusterTextInputService.getInstance()
        if (service == null) {
            status.setText(R.string.cluster_text_enable_hint)
            return
        }
        status.setText(R.string.cluster_text_checking)
        service.prepare(expectedTarget) callback@{ focus, error ->
            if (isFinishing || isDestroyed || request != requestGeneration) return@callback
            if (ClusterTextInputService.getInstance() !== service) {
                status.setText(R.string.cluster_text_unavailable)
            } else if (focus == null) {
                status.text = error ?: getString(R.string.cluster_text_unavailable)
            } else {
                targetId = focus.id
                preparedService = service
                status.text = getString(R.string.cluster_text_target, focus.packageName)
                editor.setText(focus.text)
                editor.isEnabled = true
                send.isEnabled = true
                submit.isEnabled = true
                submit.visibility = if (focus.supportsSubmit) View.VISIBLE else View.GONE
                editor.requestFocus()
                editor.post {
                    if (!isFinishing && !isDestroyed && targetId == focus.id) {
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
    }

    private fun sendText(shouldSubmit: Boolean) {
        val service = ClusterTextInputService.getInstance()
        if (service == null || targetId <= 0) {
            status.setText(R.string.cluster_text_enable_hint)
            return
        }
        if (service !== preparedService) {
            targetId = 0L
            preparedService = null
            editor.isEnabled = false
            send.isEnabled = false
            submit.visibility = View.GONE
            status.setText(R.string.cluster_text_field_changed)
            return
        }
        val request = ++requestGeneration
        send.isEnabled = false
        submit.isEnabled = false
        service.send(targetId, editor.text.toString(), shouldSubmit) { success, error ->
            if (isFinishing || isDestroyed || request != requestGeneration) return@send
            if (ClusterTextInputService.getInstance() !== service) {
                targetId = 0L
                preparedService = null
                editor.isEnabled = false
                submit.visibility = View.GONE
                submit.isEnabled = true
                status.setText(R.string.cluster_text_unavailable)
                return@send
            }
            if (success) {
                finish()
            } else {
                targetId = 0L
                preparedService = null
                status.text = error ?: getString(R.string.cluster_text_unavailable)
                editor.isEnabled = false
            }
            submit.isEnabled = true
        }
    }

    override fun onDestroy() {
        requestGeneration++
        super.onDestroy()
    }
}
