// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.daemon

import android.content.Context

object CustomLayoutsController {
    @JvmStatic fun init(context: Context?) {}
    @JvmStatic fun onAccEdge(accOn: Boolean) {}
    @JvmStatic fun ownsCluster(): Boolean = false
    @JvmStatic fun claimsAutomaticProjection(): Boolean = false
    @JvmStatic fun vetoesClusterStart(): Boolean = false
    @JvmStatic fun onOtherOwnerStarted(owner: String) {}
    @JvmStatic fun onLegacyProjectionForceClosed(closeReason: String?) {}
    @JvmStatic fun onDi5SessionStopping(generation: Long) {}
    @JvmStatic fun onDi5SessionEnded(generation: Long, endReason: String) {}
    @JvmStatic fun onClusterRecoverySettled() {}
    fun wantsDi5Generation(owner: Long, previous: Long): Boolean = false
    fun claimDi5Generation(owner: Long, previous: Long, generation: Long): Boolean = false
}
