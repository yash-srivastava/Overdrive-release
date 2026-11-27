// Public-build stub. The official build replaces this file with the private Custom Layouts implementation.
// Every member is inert: Custom Layouts never owns the cluster, claims a session, or handles a request.
package com.overdrive.app.customlayouts.daemon.road

import com.overdrive.app.surveillance.CpuFrameSource
import com.overdrive.app.surveillance.GpuDownscaler
import java.nio.ByteBuffer

object RoadFrameTap : GpuDownscaler.RoadFrameSink {
    @JvmField @Volatile var active = false
    @JvmStatic fun noteFrame(stageNs: Long, supersededTotal: Long, detectionLane: Boolean) {}
    @JvmStatic fun noteUnsupported() {}
    @JvmStatic fun noteSource(strip: Boolean, cpuReader: Boolean, gpuView: Boolean, tileW: Int, tileH: Int) {}
    @JvmStatic fun noteDvrFrame(workNs: Long, droppedTotal: Long) {}
    @JvmStatic fun wanted(): Boolean = false
    @JvmStatic fun gpuSpec(): FloatArray? = null
    @JvmStatic fun claim(): Boolean = false
    @JvmStatic fun offerCpuFrame(source: CpuFrameSource, frontX0: Float, stripW: Int, stripH: Int) {}
    override fun onRoadFrame(rgba: ByteBuffer, width: Int, height: Int, rowStride: Int) {}
}
