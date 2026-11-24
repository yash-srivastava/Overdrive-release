// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.launcher.dilink5.cluster

import android.content.Context

internal object AaosNavigationPresentation {
    const val PACKAGE = "com.neusoft.na.navigation"
    fun isOpen(context: Context, displayId: Int): Boolean = false
    @JvmStatic fun isOpenOnCluster(context: Context?): Boolean = false
}
