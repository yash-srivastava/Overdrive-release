// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.daemon

import java.io.OutputStream

object DashboardApi {
    const val LOCAL = "/api/custom-layouts/dashboards"
    const val COMMUNITY = "/api/community/dashboards"
    @JvmStatic fun matches(path: String): Boolean = false
    @JvmStatic fun handle(method: String, path: String, body: String?, out: OutputStream): Boolean = false
}
