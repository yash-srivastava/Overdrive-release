// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.daemon.projection

object ClusterBeat {
    const val CUSTOM_LAYOUTS = "customlayouts"
    const val MAP = "map"
    const val CAST = "cast"
    @JvmStatic @JvmOverloads fun hold(owner: String, ttlMs: Long, drawsReadouts: Boolean = false) {}
    @JvmStatic fun release(owner: String) {}
    @JvmStatic fun releaseAll() {}
}
