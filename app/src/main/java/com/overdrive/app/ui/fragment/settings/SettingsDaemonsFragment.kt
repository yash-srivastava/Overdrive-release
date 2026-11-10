package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R
import com.overdrive.app.camera.dilink5.DiLink5Platform
import com.overdrive.app.launcher.DiLink5AdbPermissionBootstrap
import com.overdrive.app.ui.util.PreferencesManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.overdrive.app.ui.fragment.DaemonsFragment

/**
 * Settings → Daemons pane.
 *
 * Embeds the existing [DaemonsFragment] inside the sub-rail's detail
 * pane. We don't reimplement the daemons UI; we just host the canonical
 * fragment via the child fragment manager so all its existing wiring
 * (start/stop, logs, dialogs) keeps working unchanged.
 */
class SettingsDaemonsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val host = FrameLayout(requireContext()).apply {
            id = HOST_ID
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        if (!DiLink5Platform.isSelected()) return host
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            addView(SwitchMaterial(context).apply {
                setText(R.string.di5_adb_recovery_setup_label)
                isChecked = PreferencesManager.isDiLink5AdbRecoverySetupEnabled()
                setPadding(pad, pad, pad, pad / 2)
                setOnCheckedChangeListener { button, enabled ->
                    if (!PreferencesManager.setDiLink5AdbRecoverySetupEnabled(enabled)) {
                        button.isChecked = !enabled
                    } else if (enabled) {
                        DiLink5AdbPermissionBootstrap.startIfEnabled(context.applicationContext)
                    }
                }
            })
            addView(TextView(context).apply {
                setText(R.string.di5_adb_recovery_setup_description)
                setPadding(pad, 0, pad, pad)
            })
            addView(host, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(HOST_ID) != null) return
        childFragmentManager.commitNow {
            replace(HOST_ID, DaemonsFragment(), TAG_DAEMONS)
        }
    }

    companion object {
        private const val HOST_ID = 0x0F100003
        private const val TAG_DAEMONS = "settings_daemons_inner"
    }
}
