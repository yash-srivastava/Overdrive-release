package com.overdrive.app.recording;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.RecordingsIndex;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.surveillance.GpuMosaicRecorder;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual-only instant replay coordinator used by physical key mappings.
 *
 * <p>The request path only snapshots the trigger PTS and queues work. Once the
 * configured post-roll has arrived, the encoder remuxes the requested range
 * from its encoded pre-record ring into a second MP4. The primary continuous
 * recording muxer is never stopped or rotated.
 */
public final class ManualClipService {
    private static final DaemonLogger logger =
            DaemonLogger.getInstance("ManualClip");
    private static final long ENCODER_STALE_MS = 3_000L;
    // MAX H.264 can produce ~113 MB for 60 seconds. Require enough room for
    // that replay plus continued writes by the primary dashcam so an optional
    // export cannot consume the recorder's last free blocks.
    private static final long OUTPUT_RESERVE_BYTES = 200L * 1024L * 1024L;
    private static final long HISTORY_TOLERANCE_US = 250_000L;
    // Floor for a truncated export: below this the clip is a couple of
    // frames — refuse (NO_HISTORY) rather than save a useless file.
    private static final long MIN_EXPORT_SPAN_US = 1_000_000L;

    public enum Status {
        ACCEPTED,
        BUSY,
        PIPELINE_NOT_READY,
        RESTART_REQUIRED,
        NO_HISTORY,
        INVALID_WINDOW
    }

    public static final class RequestResult {
        public final Status status;
        public final String message;
        public final int availableBeforeSeconds;

        private RequestResult(Status status, String message, int availableBeforeSeconds) {
            this.status = status;
            this.message = message;
            this.availableBeforeSeconds = Math.max(0, availableBeforeSeconds);
        }

        public boolean isAccepted() {
            return status == Status.ACCEPTED;
        }
    }

    private static final ManualClipService INSTANCE = new ManualClipService();

    /**
     * Broadcast action consumed by the app-process status overlay. The
     * constant lives on the receiver ({@code StatusOverlayService}), same as
     * the camview pattern — mirrored here only for readability.
     */
    private static final String ACTION_REPLAY_STATE =
            com.overdrive.app.overlay.StatusOverlayService.ACTION_REPLAY_STATE;

    /** Replay lifecycle states surfaced to the overlay / status API. */
    public static final String STATE_IDLE = "idle";
    public static final String STATE_RECORDING = "recording";
    public static final String STATE_SAVED = "saved";
    public static final String STATE_FAILED = "failed";

    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ManualClipExport");
        thread.setDaemon(true);
        return thread;
    });

    // Overlay-facing lifecycle state. Written on the request/export paths,
    // read by /status (poll catch-up) — the broadcast is the low-latency
    // edge signal, this pair is the durable truth a missed broadcast
    // reconciles against. Monotonic clock: /status reports age, never an
    // absolute wall time, so a mid-export NTP/GPS resync can't skew it.
    private volatile String replayState = STATE_IDLE;
    private volatile long replayStateChangedAtMs = android.os.SystemClock.elapsedRealtime();

    // "Any replay binding/automation exists" — gates the overlay segment so
    // installs that never configured a replay see no new pill segment.
    // getConfiguredRetentionSeconds() walks keymap + automation config, so
    // cache it and refresh at most once per CONFIGURED_TTL_MS from the
    // /status path; config edits refresh it eagerly via onKeymapConfigChanged
    // / reapplyLiveRetention.
    private static final long CONFIGURED_TTL_MS = 5_000L;
    private volatile boolean configuredCache;
    private volatile long configuredCacheAtMs;

    private ManualClipService() {}

    public static ManualClipService getInstance() {
        return INSTANCE;
    }

    /**
     * Largest enabled manual-clip total window persisted anywhere that can fire
     * a replay: Key Mapping bindings AND automation actions. The pre-record ring
     * is sized to this so a clip configured only in an automation (never in Key
     * Mapping) still has enough encoded history to export.
     */
    public static int getConfiguredRetentionSeconds() {
        int keymapSeconds;
        try {
            keymapSeconds = getConfiguredRetentionSeconds(
                    com.overdrive.app.config.UnifiedConfigManager.getKeymap());
        } catch (Throwable t) {
            keymapSeconds = 0;
        }
        int automationSeconds;
        try {
            automationSeconds =
                    com.overdrive.app.automation.Automations.getMaxManualClipRetentionSeconds();
        } catch (Throwable t) {
            automationSeconds = 0;
        }
        return Math.min(ManualClipWindow.MAX_SECONDS,
                Math.max(keymapSeconds, automationSeconds));
    }

    /** Package-visible for validation tests and config-save propagation. */
    static int getConfiguredRetentionSeconds(JSONObject keymap) {
        if (keymap == null || !keymap.optBoolean("enabled", false)) return 0;
        JSONArray bindings = keymap.optJSONArray("bindings");
        if (bindings == null) return 0;

        int max = 0;
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.optJSONObject(i);
            if (binding == null || !binding.optBoolean("enabled", true)) continue;
            max = Math.max(max, maxRetentionSeconds(binding.optJSONObject("action")));
        }
        return Math.min(ManualClipWindow.MAX_SECONDS, max);
    }

    private static int maxRetentionSeconds(JSONObject action) {
        if (action == null) return 0;
        String kind = action.optString("kind", "");
        if ("manualClip".equals(kind)) {
            // The requested start must remain in the ring while post-roll is
            // collected. Retention is therefore before + after, not just the
            // pre-trigger portion (30/30 needs the full 60-second ring).
            return Math.max(0, action.optInt("beforeSeconds", 0))
                    + Math.max(0, action.optInt("afterSeconds", 0));
        }
        if (!"sequence".equals(kind)) return 0;

        JSONArray steps = action.optJSONArray("steps");
        if (steps == null) return 0;
        int max = 0;
        for (int i = 0; i < steps.length(); i++) {
            max = Math.max(max, maxRetentionSeconds(steps.optJSONObject(i)));
        }
        return max;
    }

    /**
     * Apply a saved binding edit to the live encoder before the next key press.
     * Returns true when the fixed native arena is too small and needs a manual
     * camera-daemon cold restart; this method never performs that restart.
     *
     * <p>The retention APPLIED is always the combined Key-Mapping + automation
     * maximum ({@link #getConfiguredRetentionSeconds()}), not the keymap-only
     * value — otherwise saving a keymap edit would shrink the ring below what an
     * automation-only replay still needs. The restart flag is likewise computed
     * from the combined requirement so the banner reflects the real arena need.
     */
    public boolean onKeymapConfigChanged(JSONObject keymap) {
        int seconds = getConfiguredRetentionSeconds();
        refreshConfiguredCache(seconds);
        try {
            HardwareEventRecorderGpu encoder = currentEncoder();
            if (encoder == null) return false;
            encoder.setManualClipRetentionDuration(seconds);
            return encoder.requiresCameraDaemonRestartForManualClip(seconds);
        } catch (Throwable t) {
            logger.warn("Could not apply live replay retention: " + t.getMessage());
            return false;
        }
    }

    /**
     * Re-derive retention from ALL replay sources (Key Mapping + automations)
     * and push it to the live encoder. Called after an automation mutation so a
     * newly-saved replay action widens the pre-record window immediately.
     * Unlike {@link #onKeymapConfigChanged}, it does not read a single section —
     * it uses the combined {@link #getConfiguredRetentionSeconds()} so an
     * automation edit never shrinks the ring a Key Mapping binding still needs.
     */
    public void reapplyLiveRetention() {
        try {
            int seconds = getConfiguredRetentionSeconds();
            refreshConfiguredCache(seconds);
            HardwareEventRecorderGpu encoder = currentEncoder();
            if (encoder == null) return;
            encoder.setManualClipRetentionDuration(seconds);
        } catch (Throwable t) {
            logger.warn("Could not reapply live replay retention: " + t.getMessage());
        }
    }

    private void refreshConfiguredCache(int retentionSeconds) {
        configuredCache = retentionSeconds > 0;
        configuredCacheAtMs = android.os.SystemClock.elapsedRealtime();
    }

    /**
     * Read-only replay block for the daemon's /status payload. The overlay
     * polls this every 1-3s as the catch-up channel for missed
     * {@link #ACTION_REPLAY_STATE} broadcasts, so it must stay cheap: the
     * config walk behind {@code configured} is cached for
     * {@link #CONFIGURED_TTL_MS} and only the volatile state pair is read
     * per call. Never throws.
     */
    public JSONObject statusJson() {
        JSONObject replay = new JSONObject();
        try {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - configuredCacheAtMs > CONFIGURED_TTL_MS) {
                refreshConfiguredCache(getConfiguredRetentionSeconds());
            }
            replay.put("configured", configuredCache);
            replay.put("state", replayState);
            replay.put("stateAgeMs", Math.max(0L, now - replayStateChangedAtMs));
        } catch (Throwable t) {
            logger.debug("Replay status block failed: " + t.getMessage());
        }
        return replay;
    }

    /**
     * Publish a lifecycle transition: stamp the volatile pair for /status
     * poll catch-up, then fire the detached {@code am broadcast} edge signal
     * the overlay listens for (same shell/UID-2000 → app pattern as
     * {@code GpuSurveillancePipeline.emitCamViewState}). {@code detail} is
     * a short machine word for logcat correlation ("BUSY", "NO_HISTORY",
     * "export", ...), not user-facing copy. Never throws, never blocks.
     */
    private void publishState(String state, String detail) {
        replayState = state;
        replayStateChangedAtMs = android.os.SystemClock.elapsedRealtime();
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "am", "broadcast",
                    "-a", ACTION_REPLAY_STATE,
                    "-p", "com.overdrive.app",
                    "--es", "state", state));
            if (detail != null) { cmd.add("--es"); cmd.add("detail"); cmd.add(detail); }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[256];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, "replay-state-broadcast");
            drain.setDaemon(true);
            drain.start();
        } catch (Throwable t) {
            logger.debug("Replay state broadcast failed: " + t.getMessage());
        }
    }

    /**
     * A rejected key press must flash the overlay's failure state — that
     * rejection was previously log-only, which is exactly the "pressed the
     * bind and nothing happened" report. One exception: BUSY while a replay
     * this service accepted is still pending. The pending clip is recording
     * fine, so flipping the pill from green to red would tell the user the
     * OPPOSITE of the truth; the press was redundant, not failed.
     */
    private void publishRejection(Status status) {
        // Also suppress while requestInFlight still holds: a BUSY racing the
        // worker's SAVED publish (press landing in the instant between the
        // state flip and the flag drop) must not flash red over a clip that
        // actually saved.
        if (status == Status.BUSY
                && (STATE_RECORDING.equals(replayState) || requestInFlight.get())) {
            return;
        }
        publishState(STATE_FAILED, status.name());
    }

    /**
     * Read-only status for the Key Mapping page's persistent restart notice.
     * Uses the combined Key-Mapping + automation requirement: the pre-record
     * arena is shared, so if an automation needs a larger ring than is currently
     * allocated, the Key Mapping page's own replay bindings are truncated too —
     * the banner must reflect that. The {@code keymap} argument is accepted for
     * call-site symmetry but retention is always derived from all sources.
     */
    public boolean isCameraDaemonRestartRequired(JSONObject keymap) {
        int seconds = getConfiguredRetentionSeconds();
        if (seconds <= 0) return false;
        try {
            HardwareEventRecorderGpu encoder = currentEncoder();
            return encoder != null
                    && encoder.requiresCameraDaemonRestartForManualClip(seconds);
        } catch (Throwable t) {
            return false;
        }
    }

    public RequestResult requestClip(int beforeSeconds, int afterSeconds) {
        final ManualClipWindow window;
        try {
            window = ManualClipWindow.create(beforeSeconds, afterSeconds);
        } catch (IllegalArgumentException e) {
            return reject(Status.INVALID_WINDOW, e.getMessage(), 0);
        }

        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null || !pipeline.isRunning()) {
            return reject(Status.PIPELINE_NOT_READY, "Camera pipeline is not running", 0);
        }
        if (isProtectedRecordingMode(pipeline)) {
            return reject(Status.BUSY, "Surveillance or proximity recording owns the camera", 0);
        }

        HardwareEventRecorderGpu encoder = currentEncoder();
        if (!isEncoderReady(encoder)) {
            return reject(Status.PIPELINE_NOT_READY, "Encoder history is not available", 0);
        }
        if (!encoder.canRetainManualClip(window.getTotalSeconds())) {
            // The shared history arena is regrown at the next encoder (re)init
            // (pipeline restart on an ACC cycle, or a camera-daemon restart) —
            // see HardwareEventRecorderGpu.regrowSharedRingLocked. Until then the
            // ring physically cannot hold before+after at the current bitrate.
            return reject(Status.RESTART_REQUIRED,
                    "Camera history buffer is smaller than this replay window; "
                            + "it is resized at the next camera restart "
                            + "(ACC cycle or camera-daemon restart)", 0);
        }
        if (encoder.isPreRecordFlushInProgress()) {
            return reject(Status.BUSY, "Another pre-record export is in progress", 0);
        }

        long triggerPtsUs = encoder.getLatestPreRecordPtsUs();
        long oldestPtsUs = encoder.getOldestPreRecordPtsUs();
        if (triggerPtsUs == Long.MIN_VALUE || oldestPtsUs == Long.MIN_VALUE) {
            return reject(Status.NO_HISTORY, "No encoded history is available yet", 0);
        }
        int availableBefore = (int) Math.max(0L,
                (triggerPtsUs - oldestPtsUs) / 1_000_000L);
        long requestedStartPtsUs = triggerPtsUs
                - window.getBeforeSeconds() * 1_000_000L;
        // The ring may not reach back to the requested start — cold ring
        // after boot/daemon restart, a PTS clock-domain wipe, or a rebuild
        // after another replay's strong cursor released. That used to reject
        // the press outright (the single most common "pressed the bind and
        // nothing recorded" case). Truncate to what IS buffered instead: an
        // instant-replay key should always save what it can. Only a window
        // that would collapse below MIN_EXPORT_SPAN_US (no usable history
        // AND no post-roll to wait for) is still refused.
        long startPtsUs = requestedStartPtsUs;
        boolean truncated = false;
        if (window.getBeforeSeconds() > 0
                && oldestPtsUs > requestedStartPtsUs + HISTORY_TOLERANCE_US) {
            startPtsUs = oldestPtsUs;
            truncated = true;
        }
        long endPtsUs = triggerPtsUs + window.getAfterSeconds() * 1_000_000L;
        if (endPtsUs - startPtsUs < MIN_EXPORT_SPAN_US) {
            return reject(Status.NO_HISTORY,
                    "No encoded history is available yet", availableBefore);
        }
        if (!requestInFlight.compareAndSet(false, true)) {
            return reject(Status.BUSY, "An instant replay request is already pending", availableBefore);
        }

        // Reserve at trigger time, before collecting post-roll. Otherwise an
        // automatic event could claim the ring cursor during that wait and the
        // already-accepted manual replay would be lost.
        if (!encoder.tryReserveManualClip()) {
            requestInFlight.set(false);
            return reject(Status.BUSY, "Another pre-record export is in progress", availableBefore);
        }

        // Ring eviction between the availability snapshot above and the
        // reservation is unpinned — re-clamp against the post-reserve oldest
        // so the export-side coverage check can't refuse a range whose head
        // was evicted in that window. Only ever moves the start FORWARD.
        long oldestAfterReserveUs = encoder.getOldestPreRecordPtsUs();
        if (oldestAfterReserveUs != Long.MIN_VALUE && oldestAfterReserveUs > startPtsUs) {
            startPtsUs = oldestAfterReserveUs;
            truncated = truncated || window.getBeforeSeconds() > 0;
            if (endPtsUs - startPtsUs < MIN_EXPORT_SPAN_US) {
                encoder.releaseManualClipReservation();
                requestInFlight.set(false);
                return reject(Status.NO_HISTORY,
                        "No encoded history is available yet", availableBefore);
            }
        }

        final long exportStartPtsUs = startPtsUs;
        // Publish BEFORE queueing the worker. With afterSeconds=0 the worker
        // can reach a terminal FAILED publish within microseconds (coverage
        // refusal, cursor null); publishing RECORDING after execute() could
        // overwrite that terminal state and pin /status + the overlay on
        // "recording" forever (no further transition comes once
        // requestInFlight has already dropped).
        publishState(STATE_RECORDING, truncated ? "truncated:" + availableBefore : null);
        try {
            exportExecutor.execute(() -> exportWhenReady(
                    encoder, window, exportStartPtsUs, endPtsUs));
        } catch (RuntimeException e) {
            encoder.releaseManualClipReservation();
            requestInFlight.set(false);
            logger.warn("Could not queue instant replay worker: " + e.getMessage());
            // Direct FAILED publish — publishRejection would suppress BUSY
            // here because the state was just set to RECORDING above.
            publishState(STATE_FAILED, "WORKER_QUEUE");
            return result(Status.BUSY, "Instant replay worker is unavailable", availableBefore);
        }

        logger.info("Instant replay accepted: before=" + window.getBeforeSeconds()
                + "s after=" + window.getAfterSeconds() + "s availableBefore="
                + availableBefore + "s" + (truncated ? " (history truncated)" : ""));
        return result(Status.ACCEPTED,
                truncated
                        ? "Instant replay queued (pre-record truncated to "
                                + availableBefore + "s of buffered history)"
                        : "Instant replay queued",
                availableBefore);
    }

    private RequestResult reject(Status status, String message, int availableBefore) {
        publishRejection(status);
        return result(status, message, availableBefore);
    }

    private void exportWhenReady(HardwareEventRecorderGpu requestedEncoder,
                                 ManualClipWindow window,
                                 long startPtsUs,
                                 long endPtsUs) {
        try {
            if (!awaitPostRoll(requestedEncoder, endPtsUs, window.getAfterSeconds())) {
                logger.warn("Instant replay cancelled: encoder changed or post-roll did not arrive");
                publishState(STATE_FAILED, "POST_ROLL");
                return;
            }

            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline == null || !pipeline.isRunning()
                    || currentEncoder() != requestedEncoder) {
                logger.warn("Instant replay cancelled: camera ownership changed while pending");
                publishState(STATE_FAILED, "OWNERSHIP");
                return;
            }

            AtomicReference<File> outputRef = new AtomicReference<>();
            // A byte-full ring keeps evicting its head during post-roll; save what is retained.
            boolean exported = requestedEncoder.exportManualClip(() -> {
                File output = prepareOutputFile();
                outputRef.set(output);
                return output;
            }, startPtsUs, endPtsUs, true);
            File output = outputRef.get();
            if (!exported || output == null) {
                // output == null covers both "refused before the provider ran"
                // (cursor/coverage) and "no writable recordings directory" —
                // the encoder/service logs above carry the real cause.
                logger.warn(output == null
                        ? "Instant replay cancelled before or during output resolution"
                        : "Instant replay export failed for " + output.getName());
                publishState(STATE_FAILED, "EXPORT");
                return;
            }

            try {
                StorageManager.getInstance().onFileSaved(output);
            } catch (Throwable t) {
                logger.warn("Instant replay storage accounting failed: " + t.getMessage());
            }
            try {
                RecordingsIndex.getInstance().upsert(output);
            } catch (Throwable t) {
                logger.warn("Instant replay index update failed: " + t.getMessage());
            }
            logger.info("Instant replay saved: " + output.getAbsolutePath());
            publishState(STATE_SAVED, output.getName());
        } catch (Throwable t) {
            logger.warn("Instant replay worker failed: " + t.getMessage());
            publishState(STATE_FAILED, "WORKER");
        } finally {
            requestedEncoder.releaseManualClipReservation();
            requestInFlight.set(false);
        }
    }

    private boolean awaitPostRoll(HardwareEventRecorderGpu encoder,
                                  long endPtsUs,
                                  int afterSeconds) {
        if (afterSeconds <= 0) return true;
        // Monotonic deadline. The previous System.currentTimeMillis() bound
        // expired mid-wait whenever the head unit's GPS/NTP resync jumped the
        // wall clock forward during the post-roll — an accepted replay then
        // vanished with only a log line. nanoTime is immune to clock steps.
        long deadlineNs = System.nanoTime()
                + (afterSeconds * 1_000L + 2_000L) * 1_000_000L;
        while (System.nanoTime() < deadlineNs) {
            if (currentEncoder() != encoder || !isEncoderReady(encoder)) return false;
            if (encoder.getLatestPreRecordPtsUs() >= endPtsUs) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return encoder.getLatestPreRecordPtsUs() >= endPtsUs;
    }

    private static boolean isProtectedRecordingMode(GpuSurveillancePipeline pipeline) {
        if (pipeline.isSurveillanceMode()) return true;
        RecordingModeManager manager = CameraDaemon.getRecordingModeManager();
        return manager != null
                && manager.getCurrentMode() == RecordingModeManager.Mode.PROXIMITY_GUARD;
    }

    private static HardwareEventRecorderGpu currentEncoder() {
        GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline == null) return null;
        GpuMosaicRecorder recorder = pipeline.getRecorder();
        return recorder != null ? recorder.getEncoder() : null;
    }

    private static boolean isEncoderReady(HardwareEventRecorderGpu encoder) {
        if (encoder == null || !encoder.isFormatAvailable() || !encoder.isPreRecordEnabled()) {
            return false;
        }
        // Monotonic freshness: a GPS/NTP wall-clock step mid post-roll made
        // the currentTimeMillis comparison read a healthy encoder as stale
        // and awaitPostRoll cancelled the accepted replay.
        long lastFrameMs = encoder.getLastEncodedFrameElapsedMs();
        return lastFrameMs > 0
                && android.os.SystemClock.elapsedRealtime() - lastFrameMs <= ENCODER_STALE_MS;
    }

    private static File prepareOutputFile() {
        try {
            StorageManager storage = StorageManager.getInstance();
            storage.ensureStorageReady(false);
            // Do not use the cached recordingsDir here: it is intentionally
            // frozen while the primary recorder is active and can still point
            // at the internal fallback even after the configured SD card has
            // mounted. A replay is an independent writer, so resolve the
            // user's configured destination live for this export.
            File dir = storage.getLiveRecordingsDirIfMounted();
            // Replay is a secondary writer. Only the primary cam_* writer may
            // mutate the global fallback state used by UI and cleanup routing.
            dir = storage.resolveTargetWithEnospcFallback(dir, OUTPUT_RESERVE_BYTES, false);
            if (dir == null) return null;
            if (!RemovableStorageFs.exists(dir) && !RemovableStorageFs.mkdirs(dir) && !RemovableStorageFs.exists(dir)) return null;
            if (!storage.ensureRecordingsSpaceForRecorder(OUTPUT_RESERVE_BYTES, dir)) return null;

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            for (int suffix = 0; suffix < 1_000; suffix++) {
                // A dedicated prefix prevents a replay remux from ever sharing
                // a final or .tmp path with the continuous recorder's cam_*
                // segment rotation. RecordingsIndex classifies replay_* as
                // type 'replay' (its own Replays tab); StorageManager still
                // cleans it under the 'recordings' category like cam_*.
                String name = "replay_" + timestamp
                        + (suffix == 0 ? "" : "_" + suffix) + ".mp4";
                File candidate = new File(dir, name);
                if (!RemovableStorageFs.exists(candidate) && !RemovableStorageFs.exists(new File(candidate.getAbsolutePath() + ".tmp"))) {
                    return candidate;
                }
            }
        } catch (Throwable t) {
            logger.warn("Instant replay output preparation failed: " + t.getMessage());
        }
        return null;
    }

    private static RequestResult result(Status status, String message, int availableBefore) {
        return new RequestResult(status, message, availableBefore);
    }
}
