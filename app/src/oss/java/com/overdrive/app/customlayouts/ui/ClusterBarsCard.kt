// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.ui

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.overdrive.app.ui.widget.AppToast

internal class ClusterBarsCard(private val fragment: Fragment, private val toast: () -> AppToast?) {
    fun attach(parent: ViewGroup, index: Int) {}
    fun start() {}
    fun stop() {}
    fun destroy() {}
}
