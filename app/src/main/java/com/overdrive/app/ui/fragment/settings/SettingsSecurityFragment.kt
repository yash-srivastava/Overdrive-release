package com.overdrive.app.ui.fragment.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.overdrive.app.R
import com.overdrive.app.auth.PinManager
import com.overdrive.app.byd.dilink5.NativeVehicleAction
import com.overdrive.app.byd.dilink5.NativeVehicleProfileAvailability
import com.overdrive.app.byd.dilink5.NativeVehicleWriteClient
import com.overdrive.app.byd.routing.VehicleCommandRouter
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.config.UnifiedConfigManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Settings → Security pane.
 *
 * Hosts the PIN-lock controls. The lock guards [com.overdrive.app.ui.MainActivity]
 * only — daemons, surveillance, recording, Telegram alerts, the status
 * overlay service, and the Deterrent / Blocker activities are intentionally
 * outside the gate.
 *
 * The fragment is stateless: everything reads from / writes to
 * [PinManager], which is backed by `UnifiedConfigManager`'s `pinLock`
 * section so app + daemon UIDs stay consistent (the daemon never reads
 * this section, but writes from app-UID still need to be cross-UID safe
 * because some flows read it back from the daemon process indirectly).
 */
class SettingsSecurityFragment : Fragment() {

    private lateinit var rowToggle: View
    private lateinit var rowChange: View
    private lateinit var rowAutoLock: View
    private lateinit var dividerRows: View
    private lateinit var dividerAutoLock: View
    private lateinit var swToggle: MaterialSwitch
    private lateinit var tvToggleSubtitle: TextView
    private lateinit var tvAutoLockValue: TextView
    private lateinit var groupNativeVehicleProfile: View
    private lateinit var rowNativeVehicleProfile: View
    private lateinit var swNativeVehicleProfile: MaterialSwitch
    private lateinit var tvNativeVehicleProfileSubtitle: TextView
    private lateinit var btnNativeVehicleActions: View
    /**
     * Single-thread executor for PinManager calls. PBKDF2 is ~150ms on the
     * BYD CPU and UnifiedConfigManager.updateSection is a full JSON
     * read/rewrite — both must stay off the looper or the dialog ANRs
     * on Set / Confirm / Change-PIN button presses (per memory
     * feedback_no_unified_writes_on_ui_thread).
     */
    private var pinExecutor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings_security, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rowToggle = view.findViewById(R.id.rowSecurityToggle)
        rowChange = view.findViewById(R.id.rowSecurityChange)
        rowAutoLock = view.findViewById(R.id.rowSecurityAutoLock)
        dividerRows = view.findViewById(R.id.dividerSecurityRows)
        dividerAutoLock = view.findViewById(R.id.dividerAutoLock)
        swToggle = view.findViewById(R.id.swSecurityToggle)
        tvToggleSubtitle = view.findViewById(R.id.tvSecurityToggleSubtitle)
        tvAutoLockValue = view.findViewById(R.id.tvSecurityAutoLockValue)
        groupNativeVehicleProfile = view.findViewById(R.id.groupNativeVehicleProfile)
        rowNativeVehicleProfile = view.findViewById(R.id.rowNativeVehicleProfile)
        swNativeVehicleProfile = view.findViewById(R.id.swNativeVehicleProfile)
        tvNativeVehicleProfileSubtitle = view.findViewById(R.id.tvNativeVehicleProfileSubtitle)
        btnNativeVehicleActions = view.findViewById(R.id.btnNativeVehicleActions)
        rowToggle.setOnClickListener { onToggleRowTapped() }
        rowChange.setOnClickListener { onChangePinTapped() }
        rowAutoLock.setOnClickListener { onAutoLockTapped() }
        rowNativeVehicleProfile.setOnClickListener { onNativeVehicleProfileTapped() }
        btnNativeVehicleActions.setOnClickListener { showNativeVehicleActions() }
        view.findViewById<View>(R.id.btnSecurityCountryCode).setOnClickListener { confirmCountryCode() }

        // The layout carries the legacy-root command as its static text; bind
        // the live one so a head unit on the alternate scratch root shows the
        // folder its shell can actually touch.
        view.findViewById<android.widget.TextView?>(R.id.tvSecurityRecoveryCommand)?.text =
            com.overdrive.app.auth.PinManager.recoveryCommand()

        renderState()
        renderNativeVehicleProfile()
    }

    override fun onStart() {
        super.onStart()
        if (isNativeVehicleProfileAvailable()) {
            NativeVehicleWriteClient.requestBinder(requireContext())
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
        renderNativeVehicleProfile()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pinExecutor?.shutdownNow()
        pinExecutor = null
    }

    /** Open one of the head unit's built-in BYD configuration screens. */
    private fun launchBydScreen(packageName: String, activityName: String, verify: Boolean): Boolean {
        val intent = Intent().setComponent(ComponentName(packageName, activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (verify) intent.putExtra("Development.verify.requestCode", true)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            android.util.Log.d("SettingsSecurity", "BYD screen unavailable: $activityName", e)
            false
        }
    }

    private fun confirmCountryCode() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_security_country_code_title)
            .setMessage(R.string.settings_security_country_code_warning)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_security_country_code_open) { _, _ ->
                if (!launchBydScreen("com.byd.countrycodetool",
                        "com.byd.countrycodetool.MainActivity", false)) {
                    Toast.makeText(ctx, R.string.settings_security_country_code_unavailable,
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun isNativeVehicleProfileAvailable(): Boolean {
        return try {
            DiLink5Platform.refreshActiveMode()
            NativeVehicleProfileAvailability.visible(DiLink5Platform.isSelected())
        } catch (_: Throwable) {
            false
        }
    }

    private fun renderNativeVehicleProfile() {
        val available = isNativeVehicleProfileAvailable()
        groupNativeVehicleProfile.visibility = if (available) View.VISIBLE else View.GONE
        rowNativeVehicleProfile.isEnabled = available
        if (!available) {
            swNativeVehicleProfile.isChecked = false
            btnNativeVehicleActions.visibility = View.GONE
            return
        }
        executor().execute {
            val enabled = try {
                UnifiedConfigManager.isNativeVehicleManualProfileEnabled()
            } catch (_: Throwable) {
                false
            }
            mainHandler.post {
                if (!isAdded || view == null) return@post
                val stillAvailable = isNativeVehicleProfileAvailable()
                groupNativeVehicleProfile.visibility =
                    if (stillAvailable) View.VISIBLE else View.GONE
                rowNativeVehicleProfile.isEnabled = stillAvailable
                swNativeVehicleProfile.isChecked = stillAvailable && enabled
                tvNativeVehicleProfileSubtitle.setText(
                    if (stillAvailable && enabled) R.string.settings_native_vehicle_profile_on
                    else R.string.settings_native_vehicle_profile_off
                )
                btnNativeVehicleActions.visibility =
                    if (stillAvailable && enabled) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onNativeVehicleProfileTapped() {
        if (!isNativeVehicleProfileAvailable()) return
        executor().execute {
            val enabled = try {
                UnifiedConfigManager.isNativeVehicleManualProfileEnabled()
            } catch (_: Throwable) {
                false
            }
            mainHandler.post {
                if (!isAdded) return@post
                if (enabled) {
                    persistNativeVehicleProfile(false)
                } else {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_native_vehicle_enable_title)
                        .setMessage(R.string.settings_native_vehicle_enable_body)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.settings_native_vehicle_enable_action) { _, _ ->
                            persistNativeVehicleProfile(true)
                        }
                        .show()
                }
            }
        }
    }

    private fun persistNativeVehicleProfile(enabled: Boolean) {
        executor().execute {
            val saved = try {
                !enabled || isNativeVehicleProfileAvailable()
            } catch (_: Throwable) {
                false
            } && try {
                UnifiedConfigManager.setNativeVehicleManualProfileEnabled(enabled)
            } catch (_: Throwable) {
                false
            }
            mainHandler.post {
                if (!isAdded) return@post
                if (!saved) {
                    Toast.makeText(requireContext(),
                        R.string.settings_native_vehicle_save_failed, Toast.LENGTH_LONG).show()
                }
                renderNativeVehicleProfile()
            }
        }
    }

    private fun showNativeVehicleActions() {
        if (!isNativeVehicleProfileAvailable()) return
        val labels = NATIVE_ACTIONS.map { getString(it.second) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_native_vehicle_actions_title)
            .setItems(labels) { _, which -> confirmNativeVehicleAction(NATIVE_ACTIONS[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmNativeVehicleAction(action: Pair<NativeVehicleAction, Int>) {
        if (!isNativeVehicleProfileAvailable()) return
        val label = getString(action.second)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_native_vehicle_confirm_title)
            .setMessage(getString(R.string.settings_native_vehicle_confirm_body, label))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.settings_native_vehicle_send_action) { _, _ ->
                executeNativeVehicleAction(action.first)
            }
            .show()
    }

    private fun executeNativeVehicleAction(action: NativeVehicleAction) {
        if (!isNativeVehicleProfileAvailable()) return
        executor().execute {
            if (!isNativeVehicleProfileAvailable()) return@execute
            val result = VehicleCommandRouter.getInstance().executeManualNative(action)
            val message = when (result.outcome) {
                VehicleCommandRouter.Outcome.SUCCESS ->
                    R.string.settings_native_vehicle_result_completed
                VehicleCommandRouter.Outcome.ACCEPTED_UNVERIFIED ->
                    R.string.settings_native_vehicle_result_accepted
                VehicleCommandRouter.Outcome.REJECTED,
                VehicleCommandRouter.Outcome.BLOCKED_DRIVING ->
                    R.string.settings_native_vehicle_result_rejected
                else -> R.string.settings_native_vehicle_result_unknown
            }
            mainHandler.post {
                if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun executor(): ExecutorService =
        pinExecutor ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "SettingsPinIO").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }.also { pinExecutor = it }

    /**
     * Refresh the visible state from PinManager. Reads can hit the
     * UnifiedConfigManager cache (steady state) or trigger a disk read
     * after a daemon-side write — keep them off the UI thread either
     * way and post the resulting view updates back to the main handler.
     */
    private fun renderState() {
        executor().execute {
            val enabled = try { PinManager.isEnabled() } catch (t: Throwable) {
                android.util.Log.w("SettingsSecurity", "isEnabled threw: ${t.message}"); false
            }
            val autoLockMs = try { PinManager.getAutoLockMs() } catch (t: Throwable) {
                PinManager.DEFAULT_AUTOLOCK_MS
            }
            mainHandler.post {
                if (!isAdded || view == null) return@post
                swToggle.isChecked = enabled
                tvToggleSubtitle.setText(
                    if (enabled) R.string.settings_security_toggle_subtitle_on
                    else R.string.settings_security_toggle_subtitle_off
                )
                val visibleIfOn = if (enabled) View.VISIBLE else View.GONE
                rowChange.visibility = visibleIfOn
                rowAutoLock.visibility = visibleIfOn
                dividerRows.visibility = visibleIfOn
                dividerAutoLock.visibility = visibleIfOn
                tvAutoLockValue.text = autoLockLabel(autoLockMs)
            }
        }
    }

    private fun onToggleRowTapped() {
        // Don't block the UI thread for the cache-or-disk read — and if
        // the read hits during a pending UCM write we'd serialize on the
        // class lock. Resolve off-thread, dispatch the right dialog from
        // the main handler.
        executor().execute {
            val enabled = try { PinManager.isEnabled() } catch (t: Throwable) { false }
            mainHandler.post {
                if (!isAdded) return@post
                if (enabled) confirmDisable() else promptSetPin()
            }
        }
    }

    private fun promptSetPin() {
        val ctx = context ?: return
        val (dialogView, etPin, etConfirm, til1, til2) = buildDoublePinView(ctx)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_security_dialog_set_title)
            .setMessage(R.string.settings_security_dialog_set_body)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_security_action_set, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also { dlg ->
                dlg.setOnShowListener {
                    val positive = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    positive.setOnClickListener {
                        val pin = etPin.text?.toString().orEmpty()
                        val confirm = etConfirm.text?.toString().orEmpty()
                        val err = validatePinPair(pin, confirm)
                        if (err != null) {
                            til1.error = if (err.first) getString(err.second) else null
                            til2.error = if (!err.first) getString(err.second) else null
                            return@setOnClickListener
                        }
                        positive.isEnabled = false
                        executor().execute {
                            val result = PinManager.setPin(pin)
                            mainHandler.post {
                                if (!isAdded) return@post
                                positive.isEnabled = true
                                when (result) {
                                    PinManager.SetResult.OK -> {
                                        Toast.makeText(ctx, R.string.settings_security_toast_set_ok, Toast.LENGTH_SHORT).show()
                                        dlg.dismiss()
                                        renderState()
                                    }
                                    else -> til1.error = getString(R.string.settings_security_error_persist)
                                }
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDisable() {
        val ctx = context ?: return
        val (dialogView, etPin, _, til, _) = buildSinglePinView(ctx)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_security_dialog_disable_title)
            .setMessage(R.string.settings_security_dialog_disable_body)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_security_action_disable, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also { dlg ->
                dlg.setOnShowListener {
                    val positive = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    positive.setOnClickListener {
                        val pin = etPin.text?.toString().orEmpty()
                        positive.isEnabled = false
                        executor().execute {
                            val verify = PinManager.verify(pin)
                            val disabled = if (verify == PinManager.VerifyResult.OK) PinManager.disable() else false
                            mainHandler.post {
                                if (!isAdded) return@post
                                positive.isEnabled = true
                                when (verify) {
                                    PinManager.VerifyResult.OK -> {
                                        if (disabled) {
                                            Toast.makeText(ctx, R.string.settings_security_toast_disable_ok, Toast.LENGTH_SHORT).show()
                                            dlg.dismiss()
                                            renderState()
                                        } else {
                                            til.error = getString(R.string.settings_security_error_persist)
                                        }
                                    }
                                    PinManager.VerifyResult.WRONG ->
                                        til.error = getString(R.string.settings_security_error_wrong)
                                    PinManager.VerifyResult.LOCKED_OUT ->
                                        til.error = getString(R.string.settings_security_error_locked_out)
                                    PinManager.VerifyResult.NOT_ENABLED -> dlg.dismiss()
                                }
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun onChangePinTapped() {
        val ctx = context ?: return
        // Step 1: verify current PIN, then in success → step 2 set new PIN.
        val (dialogView, etPin, _, til, _) = buildSinglePinView(ctx)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_security_dialog_change_step1_title)
            .setMessage(R.string.settings_security_dialog_change_step1_body)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_security_action_continue, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also { dlg ->
                dlg.setOnShowListener {
                    val positive = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    positive.setOnClickListener {
                        val pin = etPin.text?.toString().orEmpty()
                        positive.isEnabled = false
                        executor().execute {
                            val result = PinManager.verify(pin)
                            mainHandler.post {
                                if (!isAdded) return@post
                                positive.isEnabled = true
                                when (result) {
                                    PinManager.VerifyResult.OK -> {
                                        dlg.dismiss()
                                        promptSetPin() // reuse for new PIN
                                    }
                                    PinManager.VerifyResult.WRONG ->
                                        til.error = getString(R.string.settings_security_error_wrong)
                                    PinManager.VerifyResult.LOCKED_OUT ->
                                        til.error = getString(R.string.settings_security_error_locked_out)
                                    PinManager.VerifyResult.NOT_ENABLED -> dlg.dismiss()
                                }
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun onAutoLockTapped() {
        val ctx = context ?: return
        // Pull current value off-thread; stash a snapshot for the dialog.
        executor().execute {
            val current = try { PinManager.getAutoLockMs() } catch (t: Throwable) {
                PinManager.DEFAULT_AUTOLOCK_MS
            }
            mainHandler.post {
                if (!isAdded) return@post
                showAutoLockDialog(ctx, current)
            }
        }
    }

    private fun showAutoLockDialog(ctx: android.content.Context, current: Long) {
        val labels = AUTO_LOCK_OPTIONS.map { (_, labelRes) -> getString(labelRes) }.toTypedArray()
        val checkedIndex = AUTO_LOCK_OPTIONS.indexOfFirst { it.first == current }
            .let { if (it >= 0) it else 2 } // default to 5 min

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.settings_security_autolock_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val (ms, _) = AUTO_LOCK_OPTIONS[which]
                // setAutoLockMs writes the unified config — keep it off the looper
                // to avoid the same ANR pattern as setPin/verify. UI updates
                // optimistically; if the write fails the next renderState will
                // pick up the prior value on resume.
                executor().execute { PinManager.setAutoLockMs(ms) }
                tvAutoLockValue.text = autoLockLabel(ms)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun autoLockLabel(ms: Long): String {
        val match = AUTO_LOCK_OPTIONS.firstOrNull { it.first == ms }
        return getString(match?.second ?: R.string.settings_security_autolock_5min)
    }

    private fun validatePinPair(pin: String, confirm: String): Pair<Boolean, Int>? {
        if (pin.length < PinManager.MIN_PIN_LEN || pin.length > PinManager.MAX_PIN_LEN) {
            return true to R.string.settings_security_error_length
        }
        if (!pin.all(Char::isDigit)) {
            return true to R.string.settings_security_error_numeric
        }
        if (pin != confirm) {
            return false to R.string.settings_security_error_mismatch
        }
        return null
    }

    private data class PinDialogViews(
        val view: View,
        val etPin: TextInputEditText,
        val etConfirm: TextInputEditText,
        val tilPin: TextInputLayout,
        val tilConfirm: TextInputLayout
    )

    private fun buildSinglePinView(ctx: android.content.Context): PinDialogViews =
        buildPinView(ctx, includeConfirm = false)

    private fun buildDoublePinView(ctx: android.content.Context): PinDialogViews =
        buildPinView(ctx, includeConfirm = true)

    private fun buildPinView(ctx: android.content.Context, includeConfirm: Boolean): PinDialogViews {
        val padding = (24 * ctx.resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        val tilPin = makeTil(ctx, R.string.settings_security_pin_hint)
        val etPin = makeEt(ctx, tilPin)
        tilPin.addView(etPin)

        val tilConfirm = makeTil(ctx, R.string.settings_security_pin_confirm_hint)
        val etConfirm = makeEt(ctx, tilConfirm)
        tilConfirm.addView(etConfirm)

        // Clear errors on edit (keeps the dialog from looking stuck-red).
        etPin.doAfterTextChanged { tilPin.error = null }
        etConfirm.doAfterTextChanged { tilConfirm.error = null }

        container.addView(tilPin)
        if (includeConfirm) {
            (tilConfirm.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                (12 * ctx.resources.displayMetrics.density).toInt()
            container.addView(tilConfirm)
        }

        return PinDialogViews(container, etPin, etConfirm, tilPin, tilConfirm)
    }

    private fun makeTil(ctx: android.content.Context, hintRes: Int): TextInputLayout {
        val til = TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            hint = getString(hintRes)
            setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE)
        }
        return til
    }

    private fun makeEt(ctx: android.content.Context, til: TextInputLayout): TextInputEditText {
        return TextInputEditText(til.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(PinManager.MAX_PIN_LEN))
        }
    }

    private companion object {
        private val NATIVE_ACTIONS = listOf(
            NativeVehicleAction.TAILGATE_OPEN to R.string.settings_native_vehicle_tailgate_open,
            NativeVehicleAction.TAILGATE_CLOSE to R.string.settings_native_vehicle_tailgate_close,
            NativeVehicleAction.AC_ON to R.string.settings_native_vehicle_ac_on,
            NativeVehicleAction.AC_OFF to R.string.settings_native_vehicle_ac_off,
            NativeVehicleAction.SUNROOF_OPEN to R.string.settings_native_vehicle_sunroof_open,
            NativeVehicleAction.SUNROOF_CLOSE to R.string.settings_native_vehicle_sunroof_close,
            NativeVehicleAction.SUNROOF_TILT to R.string.settings_native_vehicle_sunroof_tilt,
            NativeVehicleAction.SUNROOF_STOP to R.string.settings_native_vehicle_sunroof_stop,
            NativeVehicleAction.SUNROOF_UPDIP to R.string.settings_native_vehicle_sunroof_updip,
            NativeVehicleAction.SUNROOF_COMFORT to R.string.settings_native_vehicle_sunroof_comfort,
            NativeVehicleAction.SUNSHADE_OPEN to R.string.settings_native_vehicle_sunshade_open,
            NativeVehicleAction.SUNSHADE_CLOSE to R.string.settings_native_vehicle_sunshade_close,
            NativeVehicleAction.HAZARDS_ON to R.string.settings_native_vehicle_hazards_on,
            NativeVehicleAction.HAZARDS_OFF to R.string.settings_native_vehicle_hazards_off,
        )

        // (autoLockMs, labelRes). Order = display order.
        private val AUTO_LOCK_OPTIONS = listOf(
            0L to R.string.settings_security_autolock_immediate,
            (60_000L) to R.string.settings_security_autolock_1min,
            (5L * 60_000L) to R.string.settings_security_autolock_5min,
            (15L * 60_000L) to R.string.settings_security_autolock_15min,
            (-1L) to R.string.settings_security_autolock_never,
        )
    }
}
