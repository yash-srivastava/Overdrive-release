package com.overdrive.app.roadsense.warn

import com.overdrive.app.byd.AudioPlaybackController
import com.overdrive.app.roadsense.config.RoadSenseChimeLevels
import com.overdrive.app.roadsense.detect.Severity

fun interface BridgedChimeDispatcher {
    fun dispatch(resourceName: String, channel: String, volumePercent: Int): Boolean
}

/**
 * [WarningCoordinator.AudioCue] that plays the approach chime through the APP-PROCESS
 * media player, so it can ride a chosen audio channel — in particular the OEM-extended
 * navigation stream (`STREAM_NAVI`, 14), where the head unit's own guidance prompts play.
 *
 * Daemon-side SoundPool and ToneGenerator could only reach usage-routed or public
 * streams, not stream 14.
 * `AudioAttributes.setLegacyStreamType` alone doesn't reach the OEM-extended streams;
 * only `MediaPlayer.setAudioStreamType` does, and that needs a MediaPlayer that can
 * actually prepare, which the daemon has none of (prepare fails `0x80000000`).
 *
 * So the chime goes through the same proven app-process bridge and channel-routing recipe
 * as "Play Audio", but in `RoadSenseChimePlaybackService`. The separate player prevents
 * a warning from replacing looping Automation Audio, or an automation from cancelling a
 * chime while it prepares. See [AudioPlaybackController.playRawResource].
 *
 * ## Cost
 * One `am` exec per chime, on the daemon warn-tick thread, fire-and-forget. Chimes are
 * globally rate-limited by `WarningCoordinator.AUDIO_COOLOFF_MS` (12 s) and fire on a
 * ~4 s approach lead, so neither the latency nor the service churn matters.
 *
 * [channelSupplier] and [volumeSupplier] are read per chime so settings changes take
 * effect without a restart.
 */
class BridgedAudioCue(
    private val channelSupplier: () -> String,
    private val volumeSupplier: () -> Int,
    private val dispatcher: BridgedChimeDispatcher = BridgedChimeDispatcher {
            resourceName, channel, volumePercent ->
        AudioPlaybackController.playRawResource(resourceName, channel, volumePercent)
    },
) : WarningCoordinator.AudioCue {

    override fun chime(severity: Severity) {
        val res = when (severity) {
            Severity.MINOR -> "roadsense_chime_minor"
            Severity.MODERATE -> "roadsense_chime_moderate"
            Severity.SEVERE -> "roadsense_chime_severe"
        }
        val masterVolume = try {
            volumeSupplier()
        } catch (_: Throwable) {
            RoadSenseChimeLevels.DEFAULT_MASTER_PERCENT
        }
        val vol = RoadSenseChimeLevels.effectivePercent(masterVolume, severity.level)
        val channel = try { channelSupplier() } catch (_: Throwable) { "navigation" }
        // Never let a chime take down the tick thread.
        try {
            dispatcher.dispatch(res, channel, vol)
        } catch (_: Throwable) {
        }
    }

    /** Nothing to release — the app-process service owns the player and self-stops. */
    override fun release() {}
}
