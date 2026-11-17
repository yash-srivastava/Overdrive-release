package com.overdrive.app.voice;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.overdrive.app.audio.AppAudioCaptureController;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.genai.GenAiConfig;
import com.overdrive.app.util.DaemonHttpClient;
import com.overdrive.app.voice.local.LocalCommandSpotter;
import com.overdrive.app.voice.wake.WakeWordDetector;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-process entry point, reconciled from the status overlay's poll tick. Disabled costs a
 * single config lookup; ACC off or an unreachable daemon tears the engine down.
 */
public final class VoiceAssistantRuntime {

    private static final String TAG = "VoiceAssistant";
    private static final long ACTIVITY_REPORT_MIN_MS = 2000;
    private static final long HEARTBEAT_MS = 5 * 60_000L;
    // One poll can misread ACC (field missing, mid-transition); a real ACC off is still off next poll.
    private static final long STOP_DEBOUNCE_MS = 5000;
    private static long stopPendingSince;

    private static final Object LOCK = new Object();
    private static volatile boolean enabledSeen;
    // Written under LOCK; engine callbacks read it without the lock so they never wait on close().
    private static volatile VoiceEngine engine;
    private static Context appContext;
    private static volatile Boolean wakeModelPresent;
    private static volatile Boolean localEnginePresent;
    // Trip Briefing: what the poll tick last saw (read on the main thread without a config read).
    private static volatile boolean accOnSeen;
    private static volatile boolean aiReadySeen;
    private static final AtomicReference<BriefCall> PENDING_BRIEF = new AtomicReference<>();
    // Past the engine's own bounds; only a dead engine leaves an ask unanswered this long.
    private static final long BRIEF_WATCHDOG_SLACK_MS = 15_000;
    private static volatile Handler mainHandler;

    private static final Object REPORT_LOCK = new Object();
    private static ExecutorService reporter;
    private static String reportedState;
    private static String currentState = "off";
    private static String currentMessage;
    private static long lastActivityReportAt;
    private static boolean activityPending;
    private static long lastReportAt;

    private VoiceAssistantRuntime() {
    }

    /** Last enabled flag seen by the poll tick. */
    public static boolean isEnabled() {
        return enabledSeen;
    }

    public static void reconcile(Context context, boolean daemonReachable, boolean accOn) {
        JSONObject root;
        try {
            root = UnifiedConfigManager.loadConfig();
        } catch (Throwable t) {
            return;
        }
        boolean enabled = VoiceAssistantConfig.isEnabled(root);
        enabledSeen = enabled;
        accOnSeen = daemonReachable && accOn;
        // The engine's own stop is debounced; a briefing turn ends on the first poll without ACC.
        if (!accOnSeen) endPendingBrief();
        if (!enabled && engine == null && reportedState == null) return;
        aiReadySeen = enabled && aiReady(root);
        synchronized (LOCK) {
            if (!enabled) {
                stopLocked("off");
                return;
            }
            if (!daemonReachable || !accOn) {
                long now = SystemClock.elapsedRealtime();
                if (engine != null) {
                    if (stopPendingSince == 0) stopPendingSince = now;
                    if (now - stopPendingSince < STOP_DEBOUNCE_MS) return;
                }
                stopPendingSince = 0;
                stopLocked("waiting");
                return;
            }
            stopPendingSince = 0;
            VoiceAssistantConfig cfg = VoiceAssistantConfig.fromRoot(root);
            if (engine == null) {
                appContext = context.getApplicationContext();
                if (wakeModelPresent == null) {
                    wakeModelPresent = WakeWordDetector.isModelPresent(
                            appContext, WakeWordDetector.DEFAULT_ASSET_BASE);
                    localEnginePresent = LocalCommandSpotter.isAvailable(appContext);
                }
                engine = new VoiceEngine(appContext, cfg, HOST);
                synchronized (REPORT_LOCK) {
                    lastReportAt = SystemClock.elapsedRealtime();
                }
                engine.start();
                Log.i(TAG, "Voice assistant started (mode=" + cfg.mode + ")");
            } else {
                engine.updateConfig(cfg);
            }
            engine.ensureCapture();
            flushReports(engine);
        }
    }

    /** Poll-tick flush: a rate-limited activity report, or a heartbeat carrying the mic level. */
    private static void flushReports(VoiceEngine e) {
        long now = SystemClock.elapsedRealtime();
        synchronized (REPORT_LOCK) {
            boolean pending = activityPending && now - lastActivityReportAt >= ACTIVITY_REPORT_MIN_MS;
            if (!pending && now - lastReportAt < HEARTBEAT_MS) return;
            activityPending = false;
            lastActivityReportAt = now;
        }
        report(e, false);
    }

    /** Push-to-talk from a mapped key; ignored when the assistant is not running. */
    public static void pushToTalk() {
        VoiceEngine e;
        synchronized (LOCK) {
            e = engine;
        }
        if (e != null) e.pushToTalk();
    }

    /**
     * Asks a Trip Briefing question by voice, following the assistant mode. Call on the main thread;
     * [cb] gets exactly one answer, ANSWER_NONE with VIA_BUSY when the assistant can't ask now
     * (disabled, ACC off, a call, or another voice turn or briefing running).
     */
    public static TripBriefingAsk.Handle askTripBriefing(TripBriefingAsk ask, TripBriefingAsk.Callback cb) {
        BriefCall call = new BriefCall(cb);
        VoiceEngine e = engine;
        boolean ready = ask != null && cb != null && enabledSeen && accOnSeen && e != null && e.alive()
                && !callActive(appContext) && PENDING_BRIEF.compareAndSet(null, call);
        if (!ready) {
            call.finish(TripBriefingAsk.ANSWER_NONE, null, TripBriefingAsk.VIA_BUSY);
            return call;
        }
        call.engine = e;
        call.arm(ask.timeoutMs);
        e.askBriefing(ask, aiReadySeen, call);
        return call;
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (engine != null || reportedState != null) stopLocked("off");
        }
    }

    /** ACC off or the assistant stopping: a briefing turn still running ends with no answer. */
    private static void endPendingBrief() {
        BriefCall call = PENDING_BRIEF.get();
        if (call != null) call.abort();
    }

    /** A phone call (or its ringing) owns the cabin audio. */
    private static boolean callActive(Context context) {
        if (context == null) return false;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_RINGTONE) {
                return true;
            }
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            return tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * GenAI realtime voice looks set up: on, a realtime provider and model, a stored key. The daemon
     * still decides (it decrypts the key); a session that is not live in time falls back to Local.
     */
    private static boolean aiReady(JSONObject root) {
        JSONObject genAi = root == null ? null : root.optJSONObject(GenAiConfig.SECTION);
        if (genAi == null || !genAi.optBoolean("enabled", false)) return false;
        String provider = genAi.optString("provider", GenAiConfig.PROVIDER_OPENAI);
        return (GenAiConfig.PROVIDER_OPENAI.equals(provider) || GenAiConfig.PROVIDER_GEMINI.equals(provider))
                && !genAi.optString("realtimeModel", "").trim().isEmpty()
                && !genAi.optString("apiKey", "").isEmpty();
    }

    private static Handler main() {
        Handler h = mainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            mainHandler = h;
        }
        return h;
    }

    /** One briefing ask: at most one answer, on the main thread, and none once cancel() returned. */
    private static final class BriefCall implements TripBriefingAsk.Handle, VoiceEngine.BriefSink {
        private final TripBriefingAsk.Callback cb;
        private final AtomicBoolean answered = new AtomicBoolean();
        private final Runnable watchdog = this::abort;
        private volatile boolean cancelled;
        volatile VoiceEngine engine;

        BriefCall(TripBriefingAsk.Callback cb) {
            this.cb = cb;
        }

        @Override
        public void onBriefAnswer(String answer, String app, String via) {
            finish(answer, app, via);
        }

        /** Delivers once; false when an answer or a cancel came first. */
        boolean finish(String answer, String app, String via) {
            if (!answered.compareAndSet(false, true)) return false;
            PENDING_BRIEF.compareAndSet(this, null);
            Handler h = main();
            h.removeCallbacks(watchdog);
            Runnable deliver = () -> {
                if (cancelled || cb == null) return;
                try {
                    cb.onAnswer(answer, app, via);
                } catch (Throwable t) {
                    Log.w(TAG, "briefing callback failed: " + t.getMessage());
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) deliver.run();
            else h.post(deliver);
            return true;
        }

        /** Ends a turn still running with no answer, and stops it. */
        void abort() {
            if (finish(TripBriefingAsk.ANSWER_NONE, null, TripBriefingAsk.VIA_VOICE)) stopEngineTurn();
        }

        void arm(long timeoutMs) {
            long t = timeoutMs > 0 ? Math.min(timeoutMs, 60_000L) : 20_000L;
            main().postDelayed(watchdog, t + BRIEF_WATCHDOG_SLACK_MS);
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (!answered.compareAndSet(false, true)) return;
            PENDING_BRIEF.compareAndSet(this, null);
            main().removeCallbacks(watchdog);
            stopEngineTurn();
        }

        private void stopEngineTurn() {
            VoiceEngine e = engine;
            if (e != null) e.cancelBriefing(this);
        }
    }

    private static void stopLocked(String state) {
        endPendingBrief();
        VoiceEngine e = engine;
        engine = null;
        if (e != null) {
            e.close();
            Log.i(TAG, "Voice assistant stopped (" + state + ")");
        }
        setState(state, null, e);
    }

    private static final VoiceEngine.Host HOST = new VoiceEngine.Host() {
        @Override
        public void onStatus(String state, String message) {
            VoiceEngine e = engine;
            if (e != null) setState(state, message, e);
        }

        @Override
        public void onActivity() {
            VoiceEngine e = engine;
            if (e == null) return;
            long now = SystemClock.elapsedRealtime();
            synchronized (REPORT_LOCK) {
                if (now - lastActivityReportAt < ACTIVITY_REPORT_MIN_MS) {
                    activityPending = true;
                    return;
                }
                lastActivityReportAt = now;
                activityPending = false;
            }
            report(e, false);
        }
    };

    private static void setState(String state, String message, VoiceEngine source) {
        boolean finalState = "off".equals(state) || "waiting".equals(state);
        synchronized (REPORT_LOCK) {
            // A callback from an engine already torn down must not undo the final report.
            if (!finalState && source != engine) return;
            if (state.equals(currentState) && state.equals(reportedState)
                    && (message == null ? currentMessage == null : message.equals(currentMessage))) {
                return;
            }
            currentState = state;
            currentMessage = message;
        }
        report(source, finalState);
    }

    private static void report(VoiceEngine source, boolean finalReport) {
        final JSONObject body = new JSONObject();
        final String state;
        synchronized (REPORT_LOCK) {
            // Late activity from a stopped engine would start a reporter nobody shuts down.
            if (!finalReport && source != engine) return;
            state = currentState;
            try {
                body.put("state", state);
                if (currentMessage != null) body.put("message", currentMessage);
                if (wakeModelPresent != null) body.put("wakeModelPresent", wakeModelPresent);
                if (localEnginePresent != null) body.put("localEnginePresent", localEnginePresent);
                // What the shared capture actually opened with (plain mic after the head unit
                // refused the configured input, flagged), and the echo-canceller probe.
                body.put("micSource", AppAudioCaptureController.activeMicSource());
                if (AppAudioCaptureController.micSourceFailed()) body.put("micSourceFailed", true);
                Boolean aec = AppAudioCaptureController.echoCancelerAvailable();
                if (aec != null) body.put("aecAvailable", aec);
                Boolean aecOn = AppAudioCaptureController.echoCancelerEnabled();
                if (aecOn != null) body.put("aecEnabled", aecOn);
                int[] channels = AppAudioCaptureController.carArrayChannelsDb();
                if (channels != null) body.put("arrayChannelsDb", new org.json.JSONArray(channels));
                if (source != null) {
                    JSONObject snap = source.snapshot();
                    body.put("counters", snap.optJSONObject("counters"));
                    if (snap.has("lastWakeAt")) body.put("lastWakeAt", snap.optLong("lastWakeAt"));
                    for (String key : new String[] {"lastVerify", "lastTurn", "mic", "checks", "turns",
                            "checksDropped", "nearMisses", "stage1", "enroll"}) {
                        Object value = snap.opt(key);
                        if (value != null) body.put(key, value);
                    }
                }
            } catch (Throwable ignored) {
            }
            reportedState = state;
            lastReportAt = SystemClock.elapsedRealtime();
            if (reporter == null) {
                reporter = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "VoiceReport");
                    t.setDaemon(true);
                    return t;
                });
            }
            reporter.execute(() -> post(body));
            if (finalReport) {
                reporter.shutdown();
                reporter = null;
                if ("off".equals(state)) reportedState = null;
            }
        }
    }

    private static void post(JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = DaemonHttpClient.open("/api/voice/runtime", "POST", 1500, 3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Throwable t) {
            Log.d(TAG, "runtime report failed: " + t.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
