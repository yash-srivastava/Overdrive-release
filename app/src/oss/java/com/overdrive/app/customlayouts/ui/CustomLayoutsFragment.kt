// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.overdrive.app.R

class CustomLayoutsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        TextView(inflater.context).apply {
            gravity = Gravity.CENTER
            setText(R.string.custom_layouts_oss_unavailable)
        }
}
