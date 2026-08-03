package com.overdrive.app.ui.model

import java.util.Locale

/**
 * Glanceable presentation semantics for a recording row and player title.
 *
 * This stays independent from Android resources so the actor-priority rules can
 * be unit tested without inflating a view. [RecordingUiText] owns the localized
 * wording shown to the user.
 */
data class RecordingPresentation(
    val headline: Headline,
    val detectedCount: Int,
    val distance: Distance?
) {
    enum class Headline {
        PERSON,
        VEHICLE,
        BIKE,
        ANIMAL,
        ACTIVITY,
        MOTION,
        DASHCAM,
        FORWARD_CAMERA,
        INSTANT_REPLAY
    }

    enum class Distance { VERY_CLOSE, CLOSE, MID, FAR }
}

object RecordingPresentationResolver {
    fun resolve(recording: RecordingFile): RecordingPresentation {
        val actorClasses = recording.actorClasses
            .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val presentActors = buildSet {
            if (recording.personCount > 0 || "person" in actorClasses) add("person")
            if (recording.vehicleCount > 0 || "vehicle" in actorClasses) add("vehicle")
            if (recording.bikeCount > 0 || "bike" in actorClasses) add("bike")
            if (recording.animalCount > 0 || "animal" in actorClasses) add("animal")
        }
        val detectedCount = recording.personCount + recording.vehicleCount +
            recording.bikeCount + recording.animalCount

        val headline = when {
            presentActors.size > 1 -> RecordingPresentation.Headline.ACTIVITY
            "person" in presentActors -> RecordingPresentation.Headline.PERSON
            "vehicle" in presentActors -> RecordingPresentation.Headline.VEHICLE
            "bike" in presentActors -> RecordingPresentation.Headline.BIKE
            "animal" in presentActors -> RecordingPresentation.Headline.ANIMAL
            recording.type == RecordingFile.RecordingType.SENTRY ||
                recording.type == RecordingFile.RecordingType.PROXIMITY ->
                RecordingPresentation.Headline.MOTION
            recording.type == RecordingFile.RecordingType.OEM_DASHCAM ->
                RecordingPresentation.Headline.FORWARD_CAMERA
            recording.type == RecordingFile.RecordingType.REPLAY ->
                RecordingPresentation.Headline.INSTANT_REPLAY
            else -> RecordingPresentation.Headline.DASHCAM
        }

        val distance = when (recording.peakProximity?.uppercase()) {
            "VERY_CLOSE" -> RecordingPresentation.Distance.VERY_CLOSE
            "CLOSE" -> RecordingPresentation.Distance.CLOSE
            "MID" -> RecordingPresentation.Distance.MID
            "FAR" -> RecordingPresentation.Distance.FAR
            else -> null
        }

        return RecordingPresentation(headline, detectedCount, distance)
    }
}
