package com.overdrive.app.ui.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPresentationResolverTest {
    @Test
    fun `single actor produces an event-first headline`() {
        val result = RecordingPresentationResolver.resolve(
            recording(
                type = RecordingFile.RecordingType.SENTRY,
                personCount = 1,
                proximity = "VERY_CLOSE"
            )
        )

        assertEquals(RecordingPresentation.Headline.PERSON, result.headline)
        assertEquals(1, result.detectedCount)
        assertEquals(RecordingPresentation.Distance.VERY_CLOSE, result.distance)
    }

    @Test
    fun `mixed actors use the neutral activity headline`() {
        val result = RecordingPresentationResolver.resolve(
            recording(
                type = RecordingFile.RecordingType.SENTRY,
                personCount = 1,
                vehicleCount = 1
            )
        )

        assertEquals(RecordingPresentation.Headline.ACTIVITY, result.headline)
        assertEquals(2, result.detectedCount)
    }

    @Test
    fun `actor classes still drive legacy sidecars with zero counts`() {
        val result = RecordingPresentationResolver.resolve(
            recording(
                type = RecordingFile.RecordingType.SENTRY,
                actorClasses = listOf("VEHICLE")
            )
        )

        assertEquals(RecordingPresentation.Headline.VEHICLE, result.headline)
    }

    @Test
    fun `recording type provides a useful fallback headline`() {
        assertEquals(
            RecordingPresentation.Headline.DASHCAM,
            RecordingPresentationResolver.resolve(
                recording(RecordingFile.RecordingType.NORMAL)
            ).headline
        )
        assertEquals(
            RecordingPresentation.Headline.FORWARD_CAMERA,
            RecordingPresentationResolver.resolve(
                recording(RecordingFile.RecordingType.OEM_DASHCAM)
            ).headline
        )
        assertEquals(
            RecordingPresentation.Headline.INSTANT_REPLAY,
            RecordingPresentationResolver.resolve(
                recording(RecordingFile.RecordingType.REPLAY)
            ).headline
        )
        assertEquals(
            RecordingPresentation.Headline.MOTION,
            RecordingPresentationResolver.resolve(
                recording(RecordingFile.RecordingType.PROXIMITY)
            ).headline
        )
    }

    private fun recording(
        type: RecordingFile.RecordingType,
        personCount: Int = 0,
        vehicleCount: Int = 0,
        actorClasses: List<String> = emptyList(),
        proximity: String? = null
    ) = RecordingFile(
        file = File("sample.mp4"),
        cameraId = 0,
        timestamp = 0L,
        durationMs = 10_000L,
        sizeBytes = 6_000_000L,
        type = type,
        personCount = personCount,
        vehicleCount = vehicleCount,
        actorClasses = actorClasses,
        peakProximity = proximity
    )
}
