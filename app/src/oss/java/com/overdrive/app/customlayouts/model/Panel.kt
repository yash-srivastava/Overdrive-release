// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.model

data class Panel(val mode: String, val w: Int, val h: Int, val y: Int = 0) {
    val band get() = mode == "band"
}
