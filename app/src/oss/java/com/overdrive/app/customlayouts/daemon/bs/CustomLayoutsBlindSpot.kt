// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.daemon.bs

import com.overdrive.app.surveillance.GpuSurveillancePipeline

object CustomLayoutsBlindSpot {
    @JvmStatic fun attachLane(pipeline: GpuSurveillancePipeline?, layer: Any?, scaler: Any?, tick: Runnable?) {}
    @JvmStatic fun ownsPlacement(): Boolean = false
}
