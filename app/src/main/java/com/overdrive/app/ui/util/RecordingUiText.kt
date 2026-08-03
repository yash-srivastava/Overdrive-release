package com.overdrive.app.ui.util

import android.content.Context
import com.overdrive.app.R
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.ui.model.RecordingPresentation
import com.overdrive.app.ui.model.RecordingPresentationResolver

/** Localized, event-first text shared by the landscape list and inline player. */
object RecordingUiText {
    fun headline(context: Context, recording: RecordingFile): String {
        val presentation = RecordingPresentationResolver.resolve(recording)
        val count = presentation.detectedCount.coerceAtLeast(1)
        return when (presentation.headline) {
            RecordingPresentation.Headline.PERSON -> context.resources.getQuantityString(
                R.plurals.recording_headline_person,
                count,
                count
            )
            RecordingPresentation.Headline.VEHICLE -> context.resources.getQuantityString(
                R.plurals.recording_headline_vehicle,
                count,
                count
            )
            RecordingPresentation.Headline.BIKE -> context.resources.getQuantityString(
                R.plurals.recording_headline_bike,
                count,
                count
            )
            RecordingPresentation.Headline.ANIMAL -> context.resources.getQuantityString(
                R.plurals.recording_headline_animal,
                count,
                count
            )
            RecordingPresentation.Headline.ACTIVITY ->
                context.getString(R.string.recording_headline_activity)
            RecordingPresentation.Headline.MOTION ->
                context.getString(R.string.recording_headline_motion)
            RecordingPresentation.Headline.DASHCAM ->
                context.getString(R.string.recording_headline_dashcam)
            RecordingPresentation.Headline.FORWARD_CAMERA ->
                context.getString(R.string.recording_headline_forward_camera)
            RecordingPresentation.Headline.INSTANT_REPLAY ->
                context.getString(R.string.recording_headline_replay)
        }
    }

    fun actorAndDistance(context: Context, recording: RecordingFile): String? {
        val parts = mutableListOf<String>()
        if (recording.personCount > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.recording_actor_person,
                recording.personCount,
                recording.personCount
            )
        }
        if (recording.vehicleCount > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.recording_actor_vehicle,
                recording.vehicleCount,
                recording.vehicleCount
            )
        }
        if (recording.bikeCount > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.recording_actor_bike,
                recording.bikeCount,
                recording.bikeCount
            )
        }
        if (recording.animalCount > 0) {
            parts += context.resources.getQuantityString(
                R.plurals.recording_actor_animal,
                recording.animalCount,
                recording.animalCount
            )
        }

        when (RecordingPresentationResolver.resolve(recording).distance) {
            RecordingPresentation.Distance.VERY_CLOSE ->
                parts += context.getString(R.string.recording_distance_very_close)
            RecordingPresentation.Distance.CLOSE ->
                parts += context.getString(R.string.recording_distance_close)
            RecordingPresentation.Distance.MID ->
                parts += context.getString(R.string.recording_distance_mid)
            RecordingPresentation.Distance.FAR ->
                parts += context.getString(R.string.recording_distance_far)
            null -> Unit
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00B7 ")
    }
}
