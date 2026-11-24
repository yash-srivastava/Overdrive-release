// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.daemon.projection

object ClusterActivityLauncher {
    const val COMPONENT = "com.overdrive.app/.customlayouts.cluster.CustomLayoutsClusterActivity"
    @JvmStatic fun extras(mode: String, width: Int, height: Int, session: Long, y: Int = 0): List<String> = emptyList()
    @JvmStatic fun requestFinish(session: Long): Boolean = false
}
