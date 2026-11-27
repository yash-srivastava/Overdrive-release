package com.overdrive.app.voice.genai;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Headless app-process client for the daemon's {@code /ws/genai} realtime voice bridge:
 * 16 kHz mic PCM up, reply PCM down to {@link ReplyPlayer}, proposals and context requests out.
 */
public final class VoiceSessionClient {

    public interface Listener {
        void onConnected(String provider);            // daemon named the provider ("openai" | "gemini")
        void onLive();                                // provider ready; buffered pre-roll flushed
        void onUserSpeechStopped();                   // provider's own turn detection ended the user's speech
        void onResponding();                          // provider heard the turn and started answering
        void onSpeakingChanged(boolean speaking);     // reply audio audible / drained (drives half-duplex)
        void onTurnComplete();                        // model finished its response
        void onActionProposal(JSONObject proposal);
        void onContextRequest(JSONObject request);
        void onEnded(String reason);                  // "done" | "busy" | "unavailable" | "failed" | "connect_failed" | "setup_failed" | "cancelled"

        /** Trip Briefing sessions only: the model called trip_briefing_answer ("yes" | "no", app or null). */
        default void onBriefingAnswer(String answer, String app) {
        }
    }

    private static final String TAG = "VoiceSession";
    private static final String KIND_CLIMATE = "climate_temperature";
    private static final String KIND_SUNSHADE = "sunshade";
    private static final String KIND_AUTOMATION = "run_automation";
    private static final String KIND_UNKNOWN = "unknown";
    private static final String STOP_REASON = "Voice turn ended";
    private static final long LIVE_TIMEOUT_MS = 8_000;
    private static final long FAILED_CLOSE_GRACE_MS = 2_000;
    private static final long PREVIOUS_SOCKET_WAIT_MS = 2_500;
    private static final long PROMPT_HOLD_MS = 15_000;
    private static final long MAX_QUEUED_BYTES = 1L << 20;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int OUTPUT_RATE = 24_000;
    private static final int CLOSE_NORMAL = 1000;
    private static final int CLOSE_GOING_AWAY = 1001;
    private static final int CLOSE_POLICY = 1008;
    private static final int CLOSE_SERVER_ERROR = 1011;
    private static final int CLOSE_BUSY = 1013;

    // The daemon frees its single session slot only once our previous socket finishes closing.
    private static final AtomicReference<CompletableFuture<Void>> PREVIOUS_SOCKET =
            new AtomicReference<>();

    private final Context app;
    private final String language;
    private final String audioChannel;
    /** Trip Briefing context (JSON) for the daemon, or null for an ordinary session. */
    private final String brief;
    private final Listener listener;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean connectStarted = new AtomicBoolean();
    private final CompletableFuture<Void> socketDone = new CompletableFuture<>();
    private final ScheduledThreadPoolExecutor events;
    private final UplinkPump pump;
    private final Object stateLock = new Object();

    private final ActionQueueGate gate = new ActionQueueGate();

    private volatile WebSocket socket;
    private volatile boolean live;
    private volatile ReplyPlayer player;
    private volatile HttpURLConnection inFlight;
    private boolean endDelivered;

    // Guarded by stateLock.
    private OkHttpClient http;
    private ThreadPoolExecutor actions;
    private String provider = "";
    private int inputRate;
    private int outputRate = OUTPUT_RATE;
    private boolean sawFailed;
    /** Daemon's failed{code}; lets a connect- or setup-phase failure surface as connect_failed / setup_failed. */
    private String failureCode = "";
    private ScheduledFuture<?> liveTimer;
    /** The daemon's single direct-fallback retry earns exactly one extra live window. */
    private boolean liveTimerRearmed;
    private boolean sawStopped;
    private boolean pendingClear;
    private boolean providerBusy;
    private boolean turnCompleteOwed;
    private String currentItemId = "";
    private int currentContentIndex;
    private String truncatableItemId = "";
    private int truncatableContentIndex;
    private String mutedItemId = "";
    private boolean mutedTurn;
    private int resultsInFlight;
    private final ArrayDeque<Control> deferred = new ArrayDeque<>();
    private final Map<JSONObject, Long> openProposals = new IdentityHashMap<>();
    private final Map<String, Long> openContexts = new HashMap<>();
    private final Set<JSONObject> resolvedProposals =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> answeredTokens = new HashSet<>();
    private final Set<String> confirmedTokens = new HashSet<>();

    private VoiceSessionClient(Context app, String language, String audioChannel, int frameMs,
                               String brief, Listener listener) {
        this.app = app;
        this.language = language == null ? "" : language.trim();
        this.audioChannel = audioChannel == null ? "media" : audioChannel;
        this.brief = brief;
        this.listener = listener;
        this.events = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "VoiceSessionEvents");
            t.setDaemon(true);
            return t;
        });
        this.events.setRemoveOnCancelPolicy(true);
        this.events.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.pump = new UplinkPump(new PumpSink(), frameMs);
    }

    /** {@code frameMs} sizes uplink frames per provider rate; 0 keeps the legacy 2048-sample frame. */
    public static VoiceSessionClient open(Context ctx, String language, String audioChannel,
                                          int frameMs, Listener listener) {
        return open(ctx, language, audioChannel, frameMs, null, listener);
    }

    /** {@code brief} (Trip Briefing context JSON) makes the daemon run a briefing session. */
    public static VoiceSessionClient open(Context ctx, String language, String audioChannel,
                                          int frameMs, String brief, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is required");
        Context app = ctx == null ? null
                : (ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx);
        VoiceSessionClient client = new VoiceSessionClient(app, language, audioChannel, frameMs,
                brief, listener);
        client.start();
        return client;
    }

    /** Thread-safe; buffered (10 s, oldest dropped) until live, dropped while a reply plays. */
    public void sendPcm16k(short[] pcm, int offset, int length) {
        if (pcm == null || length <= 0 || offset < 0 || offset > pcm.length - length
                || ended.get()) {
            return;
        }
        ReplyPlayer p = player;
        if (p != null && p.isActive()) {
            pump.markGap();
            return;
        }
        pump.write(pcm, offset, length);
    }

    public static String proposalKind(JSONObject proposal) {
        JSONObject action = actionOf(proposal);
        String type = action == null ? "" : action.optString("type", "");
        if (KIND_CLIMATE.equals(type) || KIND_SUNSHADE.equals(type)
                || KIND_AUTOMATION.equals(type)) {
            return type;
        }
        return KIND_UNKNOWN;
    }

    /** Runs the execute POST off-thread, then reports action_result once the model is idle. */
    public void approveProposal(JSONObject proposal) {
        if (proposal == null) return;
        String kind = proposalKind(proposal);
        JSONObject action = actionOf(proposal);
        String token = proposal.optString("token", "");
        synchronized (stateLock) {
            if (!resolvedProposals.add(proposal)) return;
            openProposals.remove(proposal);
            if (ended.get()) return;
            if (action == null || KIND_UNKNOWN.equals(kind)) {
                settleTurnLocked();
                return;
            }
            // The daemon only mints tokens for far-field sessions; without one the execute
            // route would treat us as the Assistant page, so decline rather than fall back.
            if (token.isEmpty()) {
                Log.w(TAG, "Voice proposal " + kind + " carries no token; declined");
                settleTurnLocked();
                return;
            }
            ActionQueueGate.Ticket ticket = gate.take();
            resultsInFlight++;
            try {
                actionsLocked().execute(() -> runAction(kind, action, token, ticket));
            } catch (RejectedExecutionException e) {
                resultsInFlight--;
                settleTurnLocked();
            }
        }
    }

    /**
     * The bridge has no decline message and action_result always reports a confirmed action,
     * so a decline stays local; the model only learns of it from the user's own speech.
     */
    public void declineProposal(JSONObject proposal) {
        if (proposal == null) return;
        synchronized (stateLock) {
            if (!resolvedProposals.add(proposal)) return;
            openProposals.remove(proposal);
            if (!ended.get()) settleTurnLocked();
        }
    }

    public void answerContext(JSONObject request, boolean allow) {
        String token = request == null ? "" : request.optString("token", "");
        if (token.isEmpty()) return;
        synchronized (stateLock) {
            if (!answeredTokens.add(token)) return;
            openContexts.remove(token);
            if (ended.get()) return;
            if (allow) confirmedTokens.add(token);
            enqueueControlLocked(contextAnswerMessage(token, allow), true);
            settleTurnLocked();
        }
    }

    /** Local barge-in: flush reply audio, truncate the current item, mute what is still due. */
    public void stopPlayback() {
        synchronized (stateLock) {
            if (ended.get()) return;
            boolean generating = providerBusy;
            flushPlaybackLocked(false);
            if (!currentItemId.isEmpty()) mutedItemId = currentItemId;
            if (generating) mutedTurn = true;
        }
    }

    public void close() {
        end("cancelled", true);
    }

    private void start() {
        try {
            synchronized (stateLock) {
                liveTimer = events.schedule(this::liveTimeout, LIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            CompletableFuture<Void> previous = PREVIOUS_SOCKET.getAndSet(socketDone);
            if (previous == null || previous.isDone()) {
                events.execute(this::connectOnce);
            } else {
                previous.whenComplete((ignored, error) -> runOnEvents(this::connectOnce));
                events.schedule(this::connectOnce, PREVIOUS_SOCKET_WAIT_MS,
                        TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException e) {
            end("failed", false);
        }
    }

    private void liveTimeout() {
        if (!live) {
            Log.w(TAG, "Voice session not live within " + LIVE_TIMEOUT_MS + " ms");
            end("unavailable", true);
        }
    }

    /**
     * The daemon is dialling the provider again over another route; give that second dial the
     * same live window the first one had, but only once per session.
     */
    private void rearmLiveTimerLocked(String route) {
        if (live || liveTimerRearmed) return;
        ScheduledFuture<?> t = liveTimer;
        if (t == null || !t.cancel(false)) return;
        liveTimerRearmed = true;
        try {
            liveTimer = events.schedule(this::liveTimeout, LIVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Log.i(TAG, "Voice bridge retrying via " + route + "; waiting another " + LIVE_TIMEOUT_MS + " ms");
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void connectOnce() {
        if (!connectStarted.compareAndSet(false, true)) return;
        if (ended.get()) {
            socketFinished();
            return;
        }
        try {
            String cookie = DaemonHttpClient.sessionCookie();
            if (cookie == null) {
                Log.w(TAG, "No daemon session token; voice session unavailable");
                socketFinished();
                end("unavailable", false);
                return;
            }
            // A cabin microphone is far-field; the bridge's default noise reduction is for headsets.
            String path = "/ws/genai?mic=far";
            if (!language.isEmpty()) path += "&lang=" + URLEncoder.encode(language, "UTF-8");
            if (brief != null) path += "&brief=" + URLEncoder.encode(brief, "UTF-8");
            Request request = new Request.Builder()
                    .url(DaemonHttpClient.webSocketUrl(path))
                    .header("Cookie", cookie)
                    .build();
            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(Proxy.NO_PROXY)
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();
            synchronized (stateLock) {
                if (ended.get()) {
                    socketFinished();
                    return;
                }
                http = client;
                socket = client.newWebSocket(request, new SocketListener());
            }
        } catch (Throwable e) {
            Log.w(TAG, "Voice socket could not start: " + e.getMessage());
            socketFinished();
            end("failed", false);
        }
    }

    private void socketFinished() {
        socketDone.complete(null);
        PREVIOUS_SOCKET.compareAndSet(socketDone, null);
    }

    private void end(String reason, boolean local) {
        if (!ended.compareAndSet(false, true)) return;
        Log.i(TAG, "Voice session ended: " + reason);
        pump.close();
        // Nothing confirmed in this session may still reach the daemon after it ended.
        gate.close();
        WebSocket s;
        ReplyPlayer p;
        ThreadPoolExecutor a;
        OkHttpClient h;
        synchronized (stateLock) {
            s = socket;
            p = player;
            a = actions;
            h = http;
            deferred.clear();
            openProposals.clear();
            openContexts.clear();
        }
        if (p != null) p.close();
        if (s == null) {
            socketFinished();
        } else {
            try {
                if (local) s.send(stopMessage());
                s.close(CLOSE_NORMAL, local ? STOP_REASON : null);
            } catch (Throwable ignored) {
            }
        }
        try {
            events.execute(() -> {
                if (endDelivered) return;
                endDelivered = true;
                try {
                    listener.onEnded(reason);
                } catch (Throwable e) {
                    Log.w(TAG, "Voice listener failed in onEnded", e);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
        events.shutdown();
        if (a != null) a.shutdownNow();
        HttpURLConnection c = inFlight;
        if (c != null) c.disconnect();
        if (h != null) h.dispatcher().executorService().shutdown();
    }

    private void post(Runnable callback) {
        runOnEvents(() -> {
            if (endDelivered) return;
            try {
                callback.run();
            } catch (Throwable e) {
                Log.w(TAG, "Voice listener failed", e);
            }
        });
    }

    private void runOnEvents(Runnable task) {
        try {
            events.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void schedulePromptExpiry() {
        try {
            events.schedule(() -> {
                synchronized (stateLock) {
                    if (!ended.get()) settleTurnLocked();
                }
            }, PROMPT_HOLD_MS + 50, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    // ---- Downlink (OkHttp reader thread) ----

    private void onText(String text) {
        JSONObject m;
        try {
            m = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        String type = m.optString("type", "");
        synchronized (stateLock) {
            if (ended.get()) return;
            boolean afterClear = pendingClear;
            pendingClear = false;
            switch (type) {
                case "connected":
                    provider = m.optString("provider", "");
                    inputRate = saneRate(m.optInt("inputSampleRate", 0), defaultInputRate());
                    outputRate = saneRate(m.optInt("outputSampleRate", OUTPUT_RATE), OUTPUT_RATE);
                    final String named = provider;
                    post(() -> listener.onConnected(named));
                    break;
                case "live":
                    onLiveLocked(m);
                    break;
                case "state":
                    onStateLocked(m.optString("state", ""), m.optString("reason", ""), afterClear);
                    break;
                case "audio_item":
                    onAudioItemLocked(m);
                    break;
                case "clear":
                    onServerClearLocked();
                    pendingClear = true;
                    break;
                case "action_proposal":
                    onProposalLocked(m);
                    break;
                case "context_request":
                    onContextRequestLocked(m);
                    break;
                case "context_resolved":
                    onContextResolvedLocked(m);
                    break;
                case "retrying":
                    rearmLiveTimerLocked(m.optString("route", ""));
                    break;
                case "briefing_answer": {
                    final String answer = m.optString("answer", "");
                    final String chosen = m.isNull("app") ? null : m.optString("app", null);
                    post(() -> listener.onBriefingAnswer(answer, chosen));
                    break;
                }
                case "failed":
                    onFailedLocked(m);
                    break;
                case "stopped":
                    sawStopped = true;
                    end(sawFailed ? failureReasonLocked() : "done", false);
                    break;
                default:
                    break;
            }
        }
    }

    private void onLiveLocked(JSONObject m) {
        if (live) return;
        int fallback = inputRate > 0 ? inputRate : defaultInputRate();
        inputRate = saneRate(m.optInt("inputSampleRate", fallback), fallback);
        outputRate = saneRate(m.optInt("outputSampleRate", outputRate), outputRate);
        live = true;
        pump.setLive(inputRate);
    }

    private void onStateLocked(String state, String reason, boolean afterClear) {
        if ("thinking".equals(state) && "speech_stopped".equals(reason)) {
            // The provider only heard the user stop; it has not started answering, so the
            // engine's response deadline starts here. It is about to open a response, though,
            // so deferred controls keep waiting exactly as before.
            providerBusy = true;
            post(listener::onUserSpeechStopped);
            return;
        }
        if ("thinking".equals(state) || "speaking".equals(state)) {
            providerBusy = true;
            post(listener::onResponding);
            return;
        }
        if (!"listening".equals(state)) return;
        // OpenAI speech_started arrives as clear + listening; that is the user's turn, not ours.
        if (afterClear && !isGemini()) return;
        providerBusy = false;
        mutedTurn = false;
        turnCompleteOwed = true;
        settleTurnLocked();
    }

    private void onAudioItemLocked(JSONObject m) {
        String id = m.optString("itemId", "");
        int index = Math.max(0, m.optInt("contentIndex", 0));
        currentItemId = id;
        currentContentIndex = index;
        truncatableItemId = id;
        truncatableContentIndex = index;
        if (!id.equals(mutedItemId)) mutedItemId = "";
    }

    private void onServerClearLocked() {
        flushPlaybackLocked(true);
        if (!currentItemId.isEmpty()) mutedItemId = currentItemId;
        mutedTurn = false;
        providerBusy = true;
    }

    private void onProposalLocked(JSONObject m) {
        if (m.optJSONObject("actionProposal") == null) return;
        providerBusy = true;
        post(listener::onResponding);
        openProposals.put(m, SystemClock.elapsedRealtime());
        schedulePromptExpiry();
        post(() -> listener.onActionProposal(m));
    }

    private void onContextRequestLocked(JSONObject m) {
        String token = m.optString("token", "");
        if (token.isEmpty()) return;
        providerBusy = true;
        post(listener::onResponding);
        openContexts.put(token, SystemClock.elapsedRealtime());
        schedulePromptExpiry();
        post(() -> listener.onContextRequest(m));
    }

    private void onContextResolvedLocked(JSONObject m) {
        String token = m.optString("token", "");
        if (!confirmedTokens.remove(token) || m.optBoolean("approved", false)) return;
        // The daemon refused our confirmation (expired), so no answer response follows.
        providerBusy = false;
        turnCompleteOwed = true;
        settleTurnLocked();
    }

    private void onFailedLocked(JSONObject m) {
        if (sawFailed) return;
        sawFailed = true;
        failureCode = m.optString("code", "");
        Log.w(TAG, "Voice bridge failed: " + m.optString("reason", ""));
        // The close code that follows distinguishes busy (1013) from other failures.
        try {
            events.schedule(() -> {
                String reason;
                synchronized (stateLock) {
                    reason = failureReasonLocked();
                }
                end(reason, true);
            }, FAILED_CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void onAudio(ByteString bytes) {
        synchronized (stateLock) {
            if (ended.get() || !live) return;
            if (mutedTurn || replySuppressedLocked()) return;
            if (!mutedItemId.isEmpty() && mutedItemId.equals(currentItemId)) return;
            ReplyPlayer p = player;
            if (p == null) {
                p = new ReplyPlayer(app, audioChannel, outputRate, new PlayerEvents());
                player = p;
            }
            p.enqueue(itemKey(currentItemId, currentContentIndex), bytes.toByteArray());
        }
    }

    private void onServerClose(int code) {
        String reason;
        synchronized (stateLock) {
            if (ended.get()) return;
            if (code == CLOSE_BUSY) {
                reason = "busy";
            } else if (sawStopped && !sawFailed) {
                reason = "done";
            } else if (sawFailed || code == CLOSE_POLICY || code == CLOSE_SERVER_ERROR) {
                reason = failureReasonLocked();
            } else if (code == CLOSE_NORMAL || code == CLOSE_GOING_AWAY) {
                reason = live ? "done" : "unavailable";
            } else {
                reason = "failed";
            }
        }
        end(reason, false);
    }

    private void onSocketFailure(Throwable error, Response response) {
        String reason;
        synchronized (stateLock) {
            if (ended.get()) return;
            if (sawStopped && !sawFailed) {
                reason = "done";
            } else if (sawFailed) {
                reason = failureReasonLocked();
            } else if (response != null
                    && (response.code() == 404 || response.code() == 503)) {
                reason = "unavailable";
            } else {
                reason = "failed";
            }
        }
        Log.w(TAG, "Voice socket failed: " + (response != null
                ? "HTTP " + response.code()
                : (error == null ? "unknown" : error.getClass().getSimpleName()
                        + ": " + error.getMessage())));
        end(reason, false);
    }

    // ---- Turn and control bookkeeping (stateLock held) ----

    /**
     * Control messages that make the daemon issue a provider response wait for an idle
     * provider: OpenAI rejects a second active response and the daemon ends on any error.
     */
    private void enqueueControlLocked(String message, boolean startsResponse) {
        deferred.addLast(new Control(message, startsResponse));
        flushDeferredLocked();
    }

    private boolean flushDeferredLocked() {
        if (providerBusy) return false;
        while (!deferred.isEmpty()) {
            Control control = deferred.pollFirst();
            sendTextLocked(control.message);
            if (control.startsResponse) {
                providerBusy = true;
                mutedTurn = false;
                turnCompleteOwed = false;
                return true;
            }
        }
        return false;
    }

    private void settleTurnLocked() {
        if (flushDeferredLocked()) return;
        if (!turnCompleteOwed || providerBusy || resultsInFlight > 0
                || hasOpenPromptsLocked()) {
            return;
        }
        turnCompleteOwed = false;
        post(listener::onTurnComplete);
    }

    /** The confirmation prompt the model speaks after a proposal is moot without a card. */
    private boolean replySuppressedLocked() {
        if (resultsInFlight > 0) return true;
        for (Control control : deferred) {
            if (control.startsResponse) return true;
        }
        return hasOpenPromptsLocked();
    }

    private boolean hasOpenPromptsLocked() {
        long now = SystemClock.elapsedRealtime();
        expire(openProposals.values(), now);
        expire(openContexts.values(), now);
        return !openProposals.isEmpty() || !openContexts.isEmpty();
    }

    private static void expire(Collection<Long> receivedAt, long now) {
        for (Iterator<Long> it = receivedAt.iterator(); it.hasNext(); ) {
            if (now - it.next() >= PROMPT_HOLD_MS) it.remove();
        }
    }

    private void flushPlaybackLocked(boolean truncateNow) {
        ReplyPlayer p = player;
        int playedMs = p == null
                ? 0 : p.flush(itemKey(truncatableItemId, truncatableContentIndex));
        if (truncatableItemId.isEmpty()) return;
        String truncate = truncateMessage(truncatableItemId, truncatableContentIndex, playedMs);
        truncatableItemId = "";
        if (truncateNow) {
            sendTextLocked(truncate);
        } else {
            enqueueControlLocked(truncate, false);
        }
    }

    private void sendTextLocked(String message) {
        WebSocket s = socket;
        if (s != null && message != null) s.send(message);
    }

    private ThreadPoolExecutor actionsLocked() {
        if (actions == null) {
            actions = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), r -> {
                        Thread t = new Thread(r, "VoiceSessionAction");
                        t.setDaemon(true);
                        return t;
                    });
            actions.allowCoreThreadTimeOut(true);
        }
        return actions;
    }

    private void runAction(String kind, JSONObject action, String token,
                           ActionQueueGate.Ticket ticket) {
        try {
            // The user may have cancelled while this sat in the queue: never POST then.
            if (!gate.valid(ticket)) {
                Log.i(TAG, "Voice action " + kind + " dropped before execution");
                return;
            }
            JSONObject response = executeAction(action, token);
            boolean ok = response != null && response.optBoolean("success", true);
            String status = response == null ? "failed"
                    : response.optString("status", ok ? "completed" : "failed");
            Log.i(TAG, "Voice action " + kind + " " + status);
            synchronized (stateLock) {
                if (ended.get() || !gate.valid(ticket)) return;
                enqueueControlLocked(actionResultMessage(kind, ok, status, token), true);
            }
        } finally {
            // Every exit path (drop, failure, success) hands the turn back.
            synchronized (stateLock) {
                resultsInFlight = Math.max(0, resultsInFlight - 1);
                if (!ended.get()) settleTurnLocked();
            }
        }
    }

    private boolean isGemini() {
        return "gemini".equals(provider);
    }

    private int defaultInputRate() {
        return isGemini() ? 16_000 : 24_000;
    }

    private String failureReasonLocked() {
        // A dial that never got a provider answer is a connect failure, not a dead session.
        if (!live && ("connect_timeout".equals(failureCode) || "connect_failed".equals(failureCode))) {
            return "connect_failed";
        }
        // The provider socket opened but never went ready: the setup was rejected or stalled.
        if (!live && ("setup_timeout".equals(failureCode) || "setup_rejected".equals(failureCode)
                || "setup_failed".equals(failureCode))) {
            return "setup_failed";
        }
        return live ? "failed" : "unavailable";
    }

    // ---- Helpers ----

    /**
     * Same route as genai.js executeAction, POSTing {action, token}. Returns the daemon's JSON
     * reply with {@code success} forced false on an HTTP error, or null when the request itself
     * failed. The connection is published so end() can cut a slow daemon short.
     */
    private JSONObject executeAction(JSONObject action, String token) {
        HttpURLConnection conn = null;
        try {
            byte[] body = new JSONObject().put("action", action).put("token", token)
                    .toString().getBytes(StandardCharsets.UTF_8);
            conn = DaemonHttpClient.open("/api/genai/action/execute", "POST", 3_000, 10_000);
            inFlight = conn;
            if (ended.get()) return null;
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            JSONObject response = new JSONObject(readCapped(in));
            if (code < 200 || code >= 300) response.put("success", false);
            if (!response.optBoolean("success", true)) {
                Log.w(TAG, "Voice action rejected (HTTP " + code + "): "
                        + response.optString("error", ""));
            }
            return response;
        } catch (Throwable e) {
            Log.w(TAG, "Voice action request failed: " + e.getMessage());
            return null;
        } finally {
            inFlight = null;
            if (conn != null) conn.disconnect();
        }
    }

    private static String readCapped(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = input.read(buf)) > 0) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("response too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject actionOf(JSONObject proposal) {
        if (proposal == null) return null;
        JSONObject nested = proposal.optJSONObject("actionProposal");
        if (nested != null) return nested;
        return "action_proposal".equals(proposal.optString("type", "")) ? null : proposal;
    }

    private static String itemKey(String itemId, int contentIndex) {
        return itemId.isEmpty() ? "" : itemId + "#" + contentIndex;
    }

    private static int saneRate(int rate, int fallback) {
        return rate >= 8_000 && rate <= 48_000 ? rate : fallback;
    }

    private static String stopMessage() {
        try {
            return new JSONObject().put("type", "stop").put("reason", STOP_REASON).toString();
        } catch (Exception e) {
            return "{\"type\":\"stop\"}";
        }
    }

    private static String truncateMessage(String itemId, int contentIndex, int audioEndMs) {
        try {
            return new JSONObject()
                    .put("type", "truncate")
                    .put("itemId", itemId)
                    .put("contentIndex", contentIndex)
                    .put("audioEndMs", Math.max(0, audioEndMs))
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code status} is the daemon's verdict (completed/accepted/blocked/refused/failed). */
    private static String actionResultMessage(String kind, boolean success, String status,
                                              String actionId) {
        try {
            return new JSONObject()
                    .put("type", "action_result")
                    .put("actionType", kind)
                    .put("success", success)
                    .put("status", status)
                    .put("actionId", actionId)
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String contextAnswerMessage(String token, boolean allow) {
        try {
            return new JSONObject()
                    .put("type", allow ? "confirm_context" : "deny_context")
                    .put("token", token)
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Control {
        final String message;
        final boolean startsResponse;

        Control(String message, boolean startsResponse) {
            this.message = message;
            this.startsResponse = startsResponse;
        }
    }

    private final class PumpSink implements UplinkPump.Sink {
        @Override
        public boolean sendFrame(byte[] frame, int length) {
            WebSocket s = socket;
            if (s == null || ended.get()) return false;
            if (!s.send(ByteString.of(frame, 0, length))) return false;
            if (s.queueSize() > MAX_QUEUED_BYTES) {
                Log.w(TAG, "Voice uplink backlog " + s.queueSize() + " bytes; ending turn");
                end("failed", true);
                return false;
            }
            return true;
        }

        @Override
        public void onPrerollFlushed() {
            post(listener::onLive);
        }
    }

    private final class PlayerEvents implements ReplyPlayer.Callback {
        @Override
        public void onSpeakingChanged(boolean speaking) {
            post(() -> listener.onSpeakingChanged(speaking));
        }

        @Override
        public void onPlaybackFailed() {
            runOnEvents(() -> end("failed", true));
        }
    }

    private final class SocketListener extends WebSocketListener {
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            onText(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            onAudio(bytes);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            onServerClose(code);
            try {
                webSocket.close(CLOSE_NORMAL, null);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            socketFinished();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable error, Response response) {
            onSocketFailure(error, response);
            socketFinished();
        }
    }
}
