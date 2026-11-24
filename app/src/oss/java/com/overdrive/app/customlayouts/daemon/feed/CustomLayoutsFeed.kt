// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.daemon.feed

import com.overdrive.app.navmap.cluster.ClusterGuidanceFrame
import org.json.JSONObject

object CustomLayoutsFeed {
    @JvmStatic fun onGuidance(frame: ClusterGuidanceFrame, sequence: Long, body: JSONObject?) {}
    @JvmStatic fun onGuidanceClear(source: String, sequence: Long) {}
}
