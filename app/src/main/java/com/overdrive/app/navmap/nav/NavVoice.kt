package com.overdrive.app.navmap.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thin Android [TextToSpeech] wrapper for spoken turn-by-turn guidance.
 *
 * <p>This is the only navigation-engine file (besides needing a [Context]) that
 * touches the Android framework. TTS initialization is asynchronous; until the
 * engine reports `SUCCESS`, [speak] calls are no-ops (we do NOT queue across
 * init — by the time TTS is ready the queued instruction would be stale, so we
 * simply drop pre-init speech). Once ready, each [speak] uses `QUEUE_FLUSH` so
 * the latest instruction replaces any still-playing one.
 *
 * <p>All calls are guarded so a TTS error never propagates to the caller.
 * Construct on the main thread (TextToSpeech requires a Looper); call
 * [shutdown] from the Activity's onDestroy.
 *
 * @param context any Context (application context is fine — held only for TTS
 *   construction; not retained beyond the TextToSpeech instance)
 */
class NavVoice(context: Context) {

    companion object {
        private const val TAG = "NavVoice"
        private const val UTTERANCE_ID = "navvoice"
    }

    @Volatile
    private var ready = false

    @Volatile
    private var muted = false

    private var tts: TextToSpeech? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        // Speak in the user's chosen app language (matches the
                        // language the Valhalla maneuver instructions come back in).
                        // Fall back to the system default when the TTS engine has
                        // no voice for that locale, so guidance is never silenced.
                        val want = Locale.forLanguageTag(
                            com.overdrive.app.server.LocaleManager.get())
                        val avail = tts?.isLanguageAvailable(want)
                        tts?.language =
                            if (avail == TextToSpeech.LANG_AVAILABLE ||
                                avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                                avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) want
                            else Locale.getDefault()
                    } catch (t: Throwable) {
                        Log.w(TAG, "set language failed: ${t.message}")
                    }
                    // Only flip ready when the engine actually exists — guards a
                    // speak() racing in before construction returned (or after a
                    // shutdown nulled tts), so speak() stays a safe no-op.
                    ready = tts != null
                    Log.i(TAG, "TTS ready=$ready")
                } else {
                    Log.w(TAG, "TTS init failed: status=$status")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TTS construction failed: ${t.message}")
            tts = null
        }
    }

    /** Whether the TTS engine has finished initializing and can speak. */
    fun isReady(): Boolean = ready

    /**
     * Mute or unmute spoken guidance. While muted, [speak] is a no-op and any
     * in-progress utterance is stopped.
     *
     * @param mute true to silence guidance, false to re-enable it
     */
    fun isMuted(): Boolean = muted

    fun setMuted(mute: Boolean) {
        muted = mute
        if (mute) {
            try { tts?.stop() } catch (_: Throwable) {}
        }
    }

    /**
     * Speak [text] now, flushing any utterance already playing
     * (`QUEUE_FLUSH`). No-op (and never throws) if TTS is not yet ready, is
     * muted, or [text] is blank.
     *
     * @param text the instruction to speak (e.g. "In 200 meters, turn left")
     */
    fun speak(text: String) {
        if (!ready || muted) return
        val msg = text.trim()
        if (msg.isEmpty()) return
        try {
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed: ${t.message}")
        }
    }

    /** Speak [text] after whatever is playing (a turn instruction isn't cut off for it). */
    fun speakQueued(text: String) {
        if (!ready || muted) return
        val msg = text.trim()
        if (msg.isEmpty()) return
        try {
            tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed: ${t.message}")
        }
    }

    /**
     * Stop any in-progress utterance and release the TTS engine. Idempotent;
     * after this the instance can no longer speak. Call from the Activity's
     * onDestroy.
     */
    fun shutdown() {
        ready = false
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
    }
}
