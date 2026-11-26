package com.overdrive.app.ui.timeline.view

import com.overdrive.app.ui.view.ZoomableVideoView.Quadrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ViewportRegionReuseTest {
    @Test fun fixedLayoutRectanglesAreReused() {
        for (layout in TileLayout.values()) {
            for (q in Quadrant.values()) {
                assertSame(CameraRegions.regionFor(layout, q), CameraRegions.regionFor(layout, q))
            }
        }
        assertEquals(Region(0.5f, 0f, 0.5f, 0.5f), CameraRegions.regionFor(TileLayout.STANDARD, Quadrant.RIGHT))
        assertEquals(Region(0f, 0f, 1f, 0.70f), CameraRegions.regionFor(TileLayout.DASHCAM, Quadrant.FRONT))
    }

    @Test fun stableCropIsSharedAcrossPlacementQueries() {
        val state = ViewportState()
        state.setViewSize(1280f, 720f)
        state.setVideoSize(1920, 1080)
        val p = Placement()
        val initial = state.region()
        repeat(240) { i ->
            state.update(i * 16_666_667L)
            assertSame(initial, state.place(p))
            assertSame(initial, state.place(p))
        }
        state.doubleTap(640f, 360f, false, 4_000_000_000L)
        assertSame(initial, state.place(p)) // zoom changes placement, not the crop
        assertEquals(2560f, p.width, 0f)
    }

    @Test fun animationSnapshotsStayImmutableAndRefreshOnCoordinateChanges() {
        val state = ViewportState()
        val p = Placement()
        val initial = state.region()
        state.setCamera(Quadrant.RIGHT, true, 0L)
        state.update(100_000_000L)
        val moving = state.place(p)
        assertNotSame(initial, moving)
        assertSame(moving, state.region())
        val snapshot = moving.copy()
        state.update(2_000_000_000L)
        val settled = state.place(p)
        assertNotSame(moving, settled)
        assertEquals(snapshot, moving)
        assertEquals(CameraRegions.regionFor(TileLayout.STANDARD, Quadrant.RIGHT), settled)
        assertSame(settled, state.region())
        state.applyLayout(TileLayout.DASHCAM)
        assertEquals(CameraRegions.regionFor(TileLayout.DASHCAM, Quadrant.RIGHT), state.region())
        state.applyLayout(TileLayout.SINGLE)
        assertEquals(CameraRegions.FULL, state.region())
        assertEquals(Quadrant.ALL, state.camera)
        assertEquals(Region(0f, 0f, 1f, 1f), initial)
    }
}
