package com.overdrive.app.surveillance;

import android.media.MediaCodec;
import com.overdrive.app.logging.DaemonLogger;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.view.Surface;

import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * HardwareEventRecorderGpu - MediaCodec encoder with Surface input for GPU pipeline.
 *
 * This encoder receives frames directly from GPU via Surface, enabling
 * zero-copy recording. Configured for 2560x1920 @ 15 FPS with adaptive bitrate.
 *
 * Key features:
 * - COLOR_FormatSurface input (GPU → Encoder)
 * - Sync frame request on event detection
 * - Adaptive bitrate (3-8 Mbps)
 * - File rotation and corruption protection
 * - Stream splitting (H.264 output → Disk + Network simultaneously)
 *
 * <h3>Lock ordering (read this before adding any new lock or call site)</h3>
 * Three locks are used by this class plus its sibling {@code GpuMosaicRecorder}.
 * Always acquire them in this order; releasing in reverse is fine but never
 * acquire a higher-numbered lock while already holding a lower-numbered one
 * in reverse:
 * <ol>
 *   <li><b>{@code GpuMosaicRecorder.recordingLock}</b> — outermost. Wraps the
 *       wrapper-level {@code recording} flag and the inner call to
 *       {@code triggerEventRecording}.</li>
 *   <li><b>{@code startStopLock}</b> — encoder-level start/stop. Wraps
 *       {@link #triggerEventRecording} and the public stop entry points
 *       (so a start cannot interleave with a stop on a different thread).
 *       The drainer/disk-writer threads do NOT take this lock.</li>
 *   <li><b>{@code muxerLock}</b> — innermost. Serializes muxer field access
 *       (writeSampleData, addTrack, start, stop, release, reassign).</li>
 * </ol>
 * Violating the order risks a deadlock if any path ever tries to acquire
 * {@code startStopLock} while already holding {@code muxerLock}, or
 * {@code recordingLock} while already holding {@code startStopLock}. Today
 * no path does, and the lock-ordering invariant exists to keep it that way.
 *
 * <p>Background threads (drainer at {@link #drainerThread}, disk writer at
 * {@link #diskWriterThread}, segment-rotator running on the drainer) only
 * touch {@code muxerLock}. They observe state changes to the volatile
 * {@code isWritingToFile} / {@code muxerStarted} flags written by the
 * start/stop paths, and never try to acquire the higher-level locks.
 */
public class HardwareEventRecorderGpu {
    private static final String TAG = "HWEncoderGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    /**
     * Callback interface for streaming H.264 packets.
     * Enables zero-overhead streaming by reusing encoder output.
     */
    public interface StreamCallback {
        /**
         * Called when SPS/PPS headers are available (codec config).
         * Must be sent to clients before any video frames.
         */
        void onSpsPps(ByteBuffer sps, ByteBuffer pps);
        
        /**
         * Called for each encoded H.264 frame.
         * 
         * @param h264Data Encoded frame data
         * @param info Buffer info (size, offset, timestamp, flags)
         */
        void onH264Packet(ByteBuffer h264Data, MediaCodec.BufferInfo info);
    }

    /** Resolves replay storage only after the encoded range is strongly pinned. */
    public interface ManualClipOutputProvider {
        File createOutputFile();
    }
    
    // Configuration
    private final int width;
    private final int height;
    private int fps;
    private int bitrate;
    private String codecMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;  // Default H.264

    // ── A/B TEST TOGGLE ──────────────────────────────────────────────────
    // KEY_OPERATING_RATE pin master switch. Currently FALSE to address the
    // "recorded video smooth but whole head unit laggy" symptom: when pinned,
    // the encoder holds the Venus / GPU clock at full frequency for the entire
    // recording (no DVFS-down between frames), which raises sustained SoC
    // temperature and can make the thermal governor throttle the cores the BYD
    // UI runs on — our pinned encode stays smooth while the un-pinned OEM UI
    // loses the clock lottery.
    //   false = (current) let Venus DVFS down between frames — cooler SoC, less
    //           thermal throttling of the OEM UI. Risk: may reintroduce the
    //           100-200ms eglSwap stalls in OUR recording the pin was added to
    //           prevent (v18.1). Watch recorded clips for freeze-and-skip; if
    //           it returns, flip back to true.
    //   true  = pin at fps (legacy behaviour, added v18.1) — smoother OUR video,
    //           hotter SoC.
    // Affects the PRIMARY recorder only; secondary encoders (OEM dashcam, live
    // stream) already force this off via setPinOperatingRate(false).
    private static final boolean PIN_OPERATING_RATE = false;

    // KEY_OPERATING_RATE pin policy. Initialised from the PIN_OPERATING_RATE
    // master switch above. When two encoders run concurrently on the single
    // SDM665 Venus H.264 block, both pinning at fps over-subscribes the
    // firmware's frequency budget and produces the exact stalls the pin was
    // meant to prevent. Secondary encoders (e.g. OEM dashcam alongside pano)
    // call setPinOperatingRate(false) before init() so only the primary
    // encoder claims the frequency lock.
    private boolean pinOperatingRate = PIN_OPERATING_RATE;
    
    // Encoder
    // Volatile because release() (lifecycle thread) nulls this while the
    // trigger thread reads it post-format-barrier and the drainer thread
    // calls dequeueOutputBuffer on it. Same cross-thread-visibility class
    // as savedFormat.
    private volatile MediaCodec encoder;
    private Surface inputSurface;
    
    // Muxer
    // SOTA: All muxer operations (writeSampleData, addTrack, start, stop, release,
    // and reassignment of the `muxer` reference) MUST be performed while holding
    // muxerLock. This makes muxer access fully serial across the drainer thread,
    // disk writer thread, rotator (drainer), and the close caller. Without this
    // lock, a concurrent writeSampleData against a stopping muxer corrupts the
    // moov atom and leaves a sized-but-unplayable .mp4 on disk — exactly the
    // failure mode that triggered this rewrite.
    private final Object muxerLock = new Object();
    private volatile MediaMuxer muxer;
    private volatile int trackIndex = -1;
    private volatile int audioTrackIndex = -1;
    private volatile boolean muxerStarted = false;

    // Audio muxing: enabled at recording-start time when (a) the user has
    // turned audioEnabled on in UnifiedConfigManager, and (b) the app
    // process has connected to AacIngestServer and uploaded its CSD-0.
    // Once a recording is in flight the audio track is fixed for the
    // lifetime of the muxer (MediaMuxer cannot addTrack post-start) — flips
    // of the user toggle apply at the next segment rotation or next event.
    //
    // The four AAC parameters (csd0, sampleRate, channelCount, bitrate) are
    // bundled into a single immutable {@link AudioConfig} reference, swapped
    // atomically via the volatile {@link #audioConfig} field. This eliminates
    // the torn-read race where a concurrent setAudioConfig between reading
    // (e.g.) audioCsd0 and audioSampleRate could produce a malformed muxer
    // format. Readers snapshot the volatile once, then use only the locals.
    private static final class AudioConfig {
        final byte[] csd0;          // never null, never empty
        final int sampleRate;
        final int channelCount;
        final int bitrate;
        AudioConfig(byte[] csd, int sr, int ch, int br) {
            this.csd0 = csd;
            this.sampleRate = sr;
            this.channelCount = ch;
            this.bitrate = br;
        }
    }
    private volatile AudioConfig audioConfig;  // null = audio muxing disabled

    // Confidence counter: number of audio packets received via
    // pushAudioPacket since the current audioConfig was set. Used by
    // maybeAddAudioTrack to decide whether to add an audio track to a
    // new muxer. If we add a track but never write any samples to it,
    // some Android versions reject muxer.stop() (sees an empty track)
    // and the segment ends up quarantined as .broken — turning a benign
    // "audio not flowing yet" into a lost video clip.
    //
    // Reset to 0 on every setAudioConfig() call (including disable) so
    // the next "is audio actually live?" decision uses fresh evidence.
    // The first segment after audio is enabled may open video-only if
    // packets haven't arrived yet by the time the muxer starts; the
    // next segment rotation picks up the audio track. Subsequent
    // segments are guaranteed to have audio so long as the app keeps
    // pushing packets.
    private volatile long audioPacketCountSinceConfigSet = 0;
    // Audio PTS rebasing. Audio packets share the muxer's monotonic timeline
    // with video — both are rebased against the SAME ptsOriginUs so A/V
    // remain time-aligned in the output mp4. Audio packets that arrive
    // before the first video packet seed the origin themselves; the
    // existing rebase guard (clamp negative → 0) keeps later video packets
    // from injecting negative-rebased PTSs.
    // Set true by the disk writer when it gives up after repeated I/O failures
    // (typically SD card unmount). The current segment's mdat is broken at that
    // point — the close/rotate paths consult this flag and refuse to rename
    // tempFile -> outputPath, so the user never sees a half-written .mp4 with the
    // final extension. Reset whenever a new disk writer instance starts.
    private volatile boolean writerAbortedCorrupt = false;
    /** Latest disk-write error message captured by the disk-writer abort path.
     *  Surfaces to UI status APIs so the user sees something more specific
     *  than a stuck "Recording" badge. Cleared on the next successful start. */
    private volatile String writerAbortedErrorMessage = null;

    /** Optional callback invoked once when the disk writer aborts due to
     *  consecutive write failures (typically SD-card unmount or a full
     *  volume). Owners (OEM pipeline, sentry engine) wire this so they can
     *  flip their {@code recording} flag and write a UCM {@code lastWriteError}
     *  WITHOUT polling — the previous design left the pipeline reporting
     *  "Recording" indefinitely while the muxer was already dead. */
    public interface WriterAbortListener {
        void onWriterAborted(String reason);
    }
    private volatile WriterAbortListener writerAbortListener = null;
    public void setWriterAbortListener(WriterAbortListener listener) {
        this.writerAbortListener = listener;
    }
    public boolean isWriterAborted() { return writerAbortedCorrupt; }
    public String getWriterAbortedErrorMessage() { return writerAbortedErrorMessage; }
    // Log throttle for the "encoder hasn't published format" spin path.
    // The drainer's 16 ms cadence would spam this same line ~70 Hz on a
    // wedged encoder; throttle to once per 30 s so the log captures the
    // condition without burying everything else.
    private volatile long lastNoFormatRotationLogMs = 0;
    // Volatile because the drainer thread (writer at INFO_OUTPUT_FORMAT_CHANGED)
    // and the trigger thread (reader in waitForFormat / triggerEventRecording's
    // savedFormat barrier, plus isFormatAvailable's external pollers) live on
    // different threads. Thread.sleep(50) is NOT a documented memory barrier;
    // on weak-memory ARM cores the trigger could spin the full 2 s on a stale
    // null even after the drainer published the format.
    private volatile MediaFormat savedFormat = null;  // Save format for reuse

    // FIX (audit R5): timestamp of last successful encoded-output dequeue.
    // RMM's wedge ticker reads this via getLastEncodedFrameMs() to detect
    // encoder hangs that don't surface through isRunning()/isRecording()
    // (e.g. MediaCodec drainer alive but no frames coming out). Updated
    // only on real coded frames (outputBufferIndex >= 0 with bufferInfo.size > 0
    // and not CODEC_CONFIG); INFO_TRY_AGAIN_LATER and INFO_OUTPUT_FORMAT_CHANGED
    // do not update it.
    private volatile long lastEncodedFrameMs = 0L;

    // Monotonic sibling of lastEncodedFrameMs (SystemClock.elapsedRealtime).
    // ManualClipService's encoder-freshness gate compares against this one so
    // a GPS/NTP wall-clock step mid post-roll can't make a healthy encoder
    // read as stale and cancel an accepted replay. The wall-clock field stays
    // untouched for the RMM wedge ticker's existing consumers.
    private volatile long lastEncodedFrameElapsedMs = 0L;

    // FIX (false-GREEN: "REC/MIC green but no video file"): timestamp of the
    // last VIDEO sample actually written to the muxer (disk). Distinct from
    // lastEncodedFrameMs, which is stamped on every coded frame dequeued from
    // the encoder BEFORE the disk-write step — and the encoder always runs to
    // feed the pre-record ring, so lastEncodedFrameMs advances even when
    // NOTHING is being muxed to a file. The wedge ticker therefore could not
    // tell "muxer open and frames landing on disk" from "muxer open but every
    // write is failing / dropped / the segment will be discarded." This is the
    // true "bytes are reaching disk" signal: updated ONLY inside the disk
    // writer's successful writeRebased (video track) and seeded at segment
    // open / rotation so a fresh segment isn't mistaken for a stall. RMM reads
    // it via getLastDiskWrittenMs(). 0 = no signal yet (skip the check).
    private volatile long lastDiskWrittenMs = 0L;

    // Pre-record ring buffer.
    // SOTA: byte-ring (single contiguous direct ByteBuffer) shared across encoder
    // instances. Replaces the per-packet slot-pool (H264CircularBuffer) which
    // padded every slot to 1 MB regardless of frame size — 80% memory waste +
    // OOM at MAX/30fps. Byte ring packs bytes tightly; same 64 MB budget that
    // held 5s of MAX H.265 in the slot pool now holds ~50s.
    //
    // Static so it survives encoder reinit. Its arena is fixed for the daemon
    // lifetime; a larger saved replay window takes effect on the next cold start.
    private static H264ByteRingBuffer sharedPreRecordBuffer;
    private static int sharedPreRecordBudgetBytes = 0;  // actual size of allocated ring, 0 if none
    private static final Object bufferLock = new Object();

    // Audio pre-record ring — small in-memory deque of recent AAC frames
    // captured continuously while the user has audio enabled. At event-trigger
    // time the ring is drained alongside the video pre-record flush so the
    // first ~5 s of every event clip have audio instead of silence.
    //
    // Sized for 62 s × 64 kbps × 1.5 overhead ≈ 744 KB — negligible vs. the
    // video ring. Static so it survives encoder reinit (codec/bitrate
    // changes don't drop the audio capture window). The ring captures
    // continuously regardless of whether the daemon is currently writing a
    // file — that's the entire point of pre-record. Its content is gated by
    // the volatile audioConfig holder inside pushAudioPacket: when audio is
    // disabled (audioConfig == null) we skip the ring add to avoid wasted
    // byte copies. The ring is cleared by setAudioConfig(null) /
    // disableAudioMuxing() so a later re-enable doesn't inherit stale (and
    // almost certainly out-of-window) packets from the prior session.
    /** Keep enough audio for the longest manual replay window. Event-trigger
     *  drains are still filtered to their own configured pre-roll. */
    private static final int AUDIO_PRE_RECORD_SECONDS = 62;
    /** Bitrate the audio ring is sized for. AppAudioCaptureController encodes
     *  AAC-LC at 64 kbps; sizing the ring to match keeps the byte budget
     *  realistic regardless of the per-segment audioBitrate the muxer ends
     *  up announcing (those two values are not always equal — the muxer's
     *  KEY_BIT_RATE is informational, the actual encoder bitrate lives in
     *  the app process). */
    private static final int AUDIO_PRE_RECORD_BITRATE_BPS = 64_000;
    private static final AacCircularBuffer aacRing =
        new AacCircularBuffer(AUDIO_PRE_RECORD_SECONDS, AUDIO_PRE_RECORD_BITRATE_BPS);
    // The dense byte ring replaces the old hundreds-of-slots pool that could
    // retain ~187 MB at MAX/30fps. A 128 MiB ceiling holds 62 seconds at the
    // measured MAX H.264 rate while remaining materially below that legacy
    // footprint. The long arena is allocated only on a cold/shared-ring init;
    // runtime config changes never double-allocate the old and new arenas.
    private static final int PRE_RECORD_BUDGET_CEILING_BYTES = 128 * 1024 * 1024;
    // Keep normal event and per-camera OEM encoders at the existing small
    // allocation. Only a configured long replay window requests a larger arena.
    private static final int PRE_RECORD_BUDGET_FLOOR_BYTES = 8 * 1024 * 1024;
    // If the full long-replay arena cannot be allocated, retain a useful history
    // without making 64 MiB the floor for every encoder instance.
    private static final int LONG_REPLAY_FALLBACK_BYTES = 64 * 1024 * 1024;
    // Long-run allowance for aggregate VBR/IDR overshoot. The prior 1.4 factor
    // described a one-second peak and over-provisioned the whole 60-second
    // window; measured MAX output is ~103% of target, so 10% is conservative.
    private static final double PRE_RECORD_BITRATE_OVERHEAD = 1.10;
    // A requested window must begin on the preceding IDR. Retain one complete
    // two-second GOP beyond the user-visible duration so exact 30/60-second
    // requests do not intermittently start at the next keyframe and fail.
    private static final int MANUAL_CLIP_GOP_HEADROOM_SECONDS = 2;

    // Floor for a start-truncated manual-clip export (see the
    // allowStartTruncation overload of exportManualClip): if adopting the
    // first decodable keyframe would leave less than this before the
    // requested end, refuse instead of emitting a useless sliver.
    private static final long MANUAL_CLIP_MIN_TRUNCATED_SPAN_US = 1_000_000L;
    private volatile H264ByteRingBuffer preRecordBuffer;  // Reference to shared buffer

    // Per-instance pre-record arena. When {@code useInstancePreRecordBuffer}
    // is true, init() allocates a private {@link H264ByteRingBuffer} owned
    // by THIS encoder rather than wiring up to {@link #sharedPreRecordBuffer}.
    //
    // The shared static ring is single-producer by design (see
    // {@link H264ByteRingBuffer}'s class javadoc — pano + OEM both writing
    // would interleave SPS/PPS from two bitstreams and corrupt the flush).
    // Pano keeps the static shared ring (cheaper memory peak across reinit
    // cycles); OEM opts into a per-instance ring so it can have its own
    // pre-roll without colliding with pano. The instance ring is freed on
    // {@link #release} — cost is one direct allocation per OEM start, paid
    // once per ACC cycle. */
    private boolean useInstancePreRecordBuffer = false;
    /** Tracks whether {@link #preRecordBuffer} on THIS instance is the
     * exclusive owner of the byte arena (true) or just a borrowed reference
     * to the static shared ring (false). Drives the release path: instance
     * arenas get nulled (the JVM Cleaner reclaims the direct memory on next
     * GC); shared references just get unhooked, leaving the static buffer
     * alive for the next encoder. */
    private boolean preRecordBufferIsInstance = false;
    // Volatile + accessed only under startStopLock for read-modify-write safety.
    // Concurrent triggerEventRecording calls (e.g., RecordingModeManager + the
    // deferred-format listener thread firing in the same window) used to both
    // pass the `if (isWritingToFile)` check and build two muxers, leaving two
    // .mp4.tmp files on disk with timestamps milliseconds apart. The lock
    // closes that window.
    private volatile boolean isWritingToFile = false;
    private final Object startStopLock = new Object();
    
    // SOTA: Pre-record flush is now a streaming Cursor over the byte ring.
    // The previous design deep-copied every pre-record packet onto the
    // trigger thread (~50-180KB × N packets of fresh allocateDirect calls,
    // 5-50ms native heap stalls during the burst) and queued them on
    // {@code pendingFlushQueue}. The byte ring eliminates that copy: the
    // drainer thread iterates the cursor and writes packets directly into
    // {@code muxerWriteQueue} via the existing pooled MuxerPacket path.
    // Cursor is set at trigger time, drained by drainEncoderInternal, and
    // closed (releases the pin) when exhausted or aborted.
    private volatile H264ByteRingBuffer.Cursor pendingFlushCursor = null;
    /** Historical AAC staged by the trigger thread and queued only after the
     * matching video cursor, preserving per-file origin and FIFO ordering. */
    private volatile java.util.List<AacCircularBuffer.Packet> pendingAudioPreRecord = null;
    private volatile boolean flushInProgress = false;
    /** Manual replay reservation, held from the physical-key trigger through
     * post-roll collection and remux. It deliberately does not hold
     * startStopLock across that interval, so camera lifecycle and live event
     * recording remain available while event pre-roll yields the shared ring. */
    private volatile boolean manualClipExportInProgress = false;
    /** Live-only event starts wait for the requested IDR before their own muxer
     * accepts video or audio. The encoder, history ring, stream, and continuous
     * dashcam remain live while this gate is armed. */
    private volatile boolean awaitLiveMuxerKeyframe = false;
    private volatile long actualPreRecordDurationMs = 0;  // Actual duration of flushed pre-record buffer
    /** Reusable BufferInfo for cursor reads. drainEncoderInternal is the
     * sole consumer thread, so this can be reused without locking. */
    private final MediaCodec.BufferInfo flushCursorInfo = new MediaCodec.BufferInfo();

    // SOTA: Muxer write queue — decouples encoder dequeue from SD card I/O.
    // The encoder dequeue loop copies frame data and releases the encoder buffer
    // immediately, then pushes to this queue. A dedicated disk writer thread
    // polls the queue and writes to the muxer. This prevents SD card I/O stalls
    // (which can be 50-100ms during garbage collection) from blocking the encoder,
    // which would cause the GPU to stall and drop camera frames.
    //
    // Capacity reasoning (post-RC7): doubled from 300 to 600 because the
    // original ceiling produced eglSwap backpressure when an SD-card delete
    // burst (e.g. cleanup of 19 files / 118 MB observed in field logs) ran
    // alongside the encoder writes. With RC5/RC8 we now defer cleanup during
    // recording, but a real-world segment rotation (~50-200 ms muxer-stop
    // pause) plus periodic GC pauses can still produce 5-15 frame backlogs
    // at 30 fps. 600 entries × ~256 KB worst-case ≈ 150 MB ceiling — paid
    // only under sustained backpressure that the drop-policy will reduce
    // anyway. Memory cost is bounded by the pool's actual usage, not the
    // capacity, so steady-state RAM is unchanged.
    private static final int MUXER_WRITE_QUEUE_CAPACITY = 600;

    /**
     * Pooled muxer packet. Direct ByteBuffer allocation is the JNI hop that
     * stalls the drainer on Adreno+lowmem hardware (5–50 ms native heap walk
     * during a 76-packet pre-record flush). The pool reuses fixed-capacity
     * direct buffers and only grows when a packet exceeds every existing
     * pool slot — which is rare in steady state.
     *
     * Pool ownership: the drainer thread acquires (or grows) a packet via
     * {@link #acquireMuxerPacket}, copies encoded bytes into it, and pushes
     * to {@link #muxerWriteQueue}. The disk writer thread pulls, calls
     * {@link MediaMuxer#writeSampleData}, then returns the packet via
     * {@link #releaseMuxerPacket}. The drop-policy in offerMuxerPacket
     * also returns evicted packets to the pool.
     */
    private static final class MuxerPacket {
        ByteBuffer data;             // direct buffer, capacity == pool slot size
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int payloadSize;             // valid byte count inside data
        // Track this packet belongs to. -1 = video track (default for back-compat
        // with the existing video-only callers). Audio packets pushed via
        // pushAudioPacket() set this to AUDIO_TRACK_MARKER, which the disk-writer
        // remaps to the live audioTrackIndex at write time. We can't resolve to
        // the real index at enqueue time because the audio track isn't added
        // until the muxer starts.
        int trackKind = TRACK_KIND_VIDEO;

        boolean isKeyFrame() {
            return (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        }

        /** Reset position/limit to expose the payload to MediaMuxer. */
        void rewindForWrite() {
            data.position(0);
            data.limit(payloadSize);
        }
    }

    // Track-kind markers stored in MuxerPacket.trackKind. The disk writer
    // resolves them to the live trackIndex/audioTrackIndex at write time.
    private static final int TRACK_KIND_VIDEO = 0;
    private static final int TRACK_KIND_AUDIO = 1;

    /**
     * Rebase a packet's PTS to be relative to the muxer's origin (first
     * packet's PTS) and write it. On the first call after a muxer start
     * (ptsOriginUs == -1), capture the packet's PTS as origin and write
     * with PTS=0; subsequent calls subtract origin.
     *
     * <p>Why: encoder PTSs are absolute (from process start). Pre-record
     * packets have PTSs from seconds ago; live packets are current.
     * MediaMuxer mostly handles non-zero origins, but the first muxer
     * instance after `savedFormat` capture has been observed to write a
     * mp4 whose declared duration disagrees with the actual bitstream
     * span — playback freezes at the declared duration mark on the very
     * first recording. Rebasing to 0 eliminates the ambiguity for ALL
     * muxer instances.
     *
     * <p>Caller must hold {@code muxerLock}. Sets {@code firstFramePtsUs}
     * + {@code lastFramePtsUs} to the REBASED values so the duration
     * computation in the close path uses them directly without a second
     * subtraction.
     *
     * @return true if write succeeded; false if MediaMuxer threw (caller
     *         decides whether to abort the recording).
     */
    private boolean writeRebased(android.media.MediaMuxer mux, int trackIdx,
                                 java.nio.ByteBuffer data,
                                 android.media.MediaCodec.BufferInfo info) {
        if (ptsOriginUs < 0) {
            ptsOriginUs = info.presentationTimeUs;
        }
        // Clock-domain jump guard. The encoder surface is stamped (via
        // eglPresentationTimeANDROID) with a PTS sourced from either the BYD
        // HAL sensor clock (a stuck, ~uptime-epoch counter) or System.nanoTime
        // (CLOCK_MONOTONIC). When the camera pipeline transitions between those
        // domains — most often after a camera/encoder restart triggered by an
        // SD-card unmount, the GL watchdog, or an ACC bounce, where the stuck-
        // clock latch re-evaluates while a muxer's origin is already seeded —
        // two consecutive frames land in different domains and differ by
        // billions of µs. Subtracting the old origin then records that gap as
        // literal playback time: a 2-min clip's moov declares 55 min – 1 hr
        // (the exact field symptom). MediaMuxer also drops every "future"
        // sample after the jump, leaving the file both mis-timed AND near-empty.
        //
        // Detect a jump from the previous source PTS larger than any real
        // inter-frame gap can be, and RE-ANCHOR: shift ptsOriginUs so this
        // frame continues one nominal frame-interval after the last written
        // one. The resulting clip has a small (sub-frame-interval) seam at the
        // transition instead of a 55-min cliff, and stays fully playable.
        if (lastSourcePtsUs >= 0) {
            long sourceGap = info.presentationTimeUs - lastSourcePtsUs;
            if (sourceGap < 0 || sourceGap > MAX_PLAUSIBLE_INTERFRAME_GAP_US) {
                long frameIntervalUs = fps > 0 ? (1_000_000L / fps) : 33_333L;
                // New origin places info.presentationTimeUs at
                // (lastRebased + frameInterval): rebasedPts below becomes that
                // value, preserving a monotonic, plausibly-spaced timeline.
                long targetRebased = (lastFramePtsUs >= 0 ? lastFramePtsUs : 0) + frameIntervalUs;
                ptsOriginUs = info.presentationTimeUs - targetRebased;
                long n = ptsReanchorCount.incrementAndGet();
                if (n % 50 == 1) {
                    logger.warn("PTS clock-domain jump #" + n + " (source gap "
                        + sourceGap + "us > " + MAX_PLAUSIBLE_INTERFRAME_GAP_US
                        + "us) — re-anchored origin to keep moov duration honest");
                }
            }
        }
        lastSourcePtsUs = info.presentationTimeUs;
        long rebasedPts = info.presentationTimeUs - ptsOriginUs;
        // Defensive: a packet with a PTS earlier than origin would produce
        // a negative rebased PTS, which MediaMuxer rejects with
        // IllegalArgumentException. Clamp to 0 — that packet's PTS gets
        // collapsed to the origin frame, which is what the user sees as
        // "the recording starts at frame 0". This can only happen if a
        // pre-record cursor packet whose PTS is older than the first
        // written packet arrives — the cursor flush enqueues in PTS order
        // so it shouldn't, but the defense costs nothing.
        if (rebasedPts < 0) rebasedPts = 0;
        // PER-TRACK MONOTONICITY GUARD (video). Mirrors the audio guard below
        // (writeAudioRebased). MediaMuxer requires each track's samples to be
        // strictly PTS-increasing; MediaCodec's HEVC bitstream likewise needs
        // monotonic DTS or the decoder's reference-picture-set breaks
        // ("Could not find ref with POC N / First slice in a frame missing" →
        // visible corruption from the offending frame onward). Two ways this
        // bites the VIDEO track specifically at the pre-record splice:
        //   1. The <0 clamp above collapses several early pre-record packets
        //      onto rebasedPts==0 — duplicates.
        //   2. The pre-record cursor flush interleaves with live capture in the
        //      disk-writer queue (enqueue order, NOT PTS order), so an out-of-
        //      order older pre-record frame can arrive after a newer one AND
        //      re-trigger the clock-domain re-anchor above (sourceGap<0), which
        //      shifts ptsOriginUs and produces colliding/backward rebased PTS.
        //      (Field-observed: event_20260701_172035 — ffprobe showed
        //      "non monotonically increasing dts 7>=7, 15>=15…" then HEVC RPS
        //      errors; corruption began right after the ~7s pre-record region.
        //      The very next clip, which did NOT re-anchor, was clean.)
        // NUDGE the offending packet (never drop it). This encoder emits a
        // no-B, reference-P (IPPP) stream — HEVCProfileMain with KEY_MAX_B_FRAMES
        // unset / KEY_LATENCY=0, or AVCProfileBaseline which forbids B-frames.
        // In such a stream every kept P references the most-recent coded picture
        // in decode order, so DROPPING a colliding P-frame does NOT fix the
        // corruption — it MOVES it from the muxer-DTS layer to the decoder-RPS
        // layer: the next kept P (the resumed live frame, still a P since
        // triggerEventRecording forces no IDR) references a reconstructed
        // picture now absent from the decoder DPB ("Could not find ref with
        // POC N / First slice in a frame missing"), corrupting every frame
        // until the next IDR (~2s). Container PTS is independent of the HEVC
        // slice POC, so a 1µs nudge keeps the frame AND its reference chain,
        // giving strictly-increasing DTS with NO RPS break — strictly safer
        // than DROP for this stream. The keyframe path always nudged for the
        // same monotonicity reason; the P-frame path now matches it.
        // Consecutive collisions stay strictly increasing because each nudge
        // advances lastFramePtsUs by 1 (a burst of N collisions rebases to
        // last+1, last+2, … last+N). firstFramePtsUs<0 (first frame of a
        // segment) skips the guard, so the leading frame is never mangled.
        if (firstFramePtsUs >= 0 && rebasedPts <= lastFramePtsUs) {
            boolean isKeyframe =
                (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            // NUDGE PTS one microsecond past the last written frame so it stays
            // strictly monotonic and playable. A 1µs shift on a ~66ms frame
            // interval is imperceptible and keeps the moov duration honest.
            // Applies to keyframes (never drop an IDR) AND P-frames (dropping a
            // reference-P strands the next P's RPS to the next IDR).
            rebasedPts = lastFramePtsUs + 1;
            long n = videoNonMonotonicNudgeCount.incrementAndGet();
            if (n % 50 == 1) {
                logger.warn("Video PTS not monotonic (rebased " + rebasedPts
                    + "us <= last " + lastFramePtsUs + "us) — nudged +1µs, #" + n
                    + " (isKey=" + isKeyframe + "); pre-record splice / re-anchor collision");
            }
        }
        // Mutate the BufferInfo for the muxer call. After write, restore
        // the absolute PTS so any caller that read info.presentationTimeUs
        // for stats/PTS-tracking sees the original encoder timestamp.
        long absolutePts = info.presentationTimeUs;
        info.presentationTimeUs = rebasedPts;
        try {
            mux.writeSampleData(trackIdx, data, info);
        } catch (Exception e) {
            info.presentationTimeUs = absolutePts;
            throw e instanceof RuntimeException
                ? (RuntimeException) e : new RuntimeException(e);
        }
        info.presentationTimeUs = absolutePts;
        // Track REBASED PTS for duration computation. The close path uses
        // (lastFramePtsUs - firstFramePtsUs) which on a rebased timeline
        // is just lastFramePtsUs (since firstFramePtsUs == 0).
        if (firstFramePtsUs < 0) firstFramePtsUs = rebasedPts;
        lastFramePtsUs = rebasedPts;
        return true;
    }

    /**
     * Audio counterpart of {@link #writeRebased}. Audio shares ptsOriginUs
     * with video so the muxer's two tracks land on a single monotonic
     * timeline. Audio packets do NOT contribute to firstFramePtsUs/lastFramePtsUs
     * — those track recorded video duration only (used by the close path
     * to compute clip duration). Audio is purely passenger on the segment.
     *
     * <p>Caller must hold {@code muxerLock}.
     *
     * <p>Audio NEVER seeds {@code ptsOriginUs}. If an audio packet arrives
     * while origin is still -1, it is dropped — the next video frame
     * (which writeRebased seeds origin from unconditionally) is what sets
     * the segment's PTS=0 anchor. Letting audio seed origin would back-
     * date the timeline by up to the pre-record window (~5 s) and produce
     * a clip whose tkhd declares audio-led duration with silent video at
     * the head; players freeze at the head for the offset duration.
     *
     * <p>Negative-rebased PTSs are NOT clamped to 0 here (unlike the video
     * path). Multiple audio packets clamped to PTS=0 would collide on the
     * muxer's audio track and produce out-of-order samples that some
     * players reject. Instead, an offending audio packet is dropped
     * silently and counted in {@link #audioWriteFailureCount} — a tiny
     * audio gap is preferable to a corrupt audio track. Video rebase
     * keeps its clamp because video's first-frame clamping is the
     * documented "recording starts at frame 0" behaviour.
     *
     * @return true if write succeeded; false if MediaMuxer threw, the
     *         packet had a negative rebased PTS, or the video origin
     *         hasn't been seeded yet (the audio gap is logged but the
     *         video recording continues).
     */
    private boolean writeRebasedAudio(android.media.MediaMuxer mux, int trackIdx,
                                      java.nio.ByteBuffer data,
                                      android.media.MediaCodec.BufferInfo info) {
        if (ptsOriginUs < 0) {
            // Wait for video to seed the origin. Pre-record audio drain
            // often enqueues audio packets BEFORE the first live video
            // frame; if we seeded ptsOriginUs from audio's (old) PTS
            // here, subsequent video frames would rebase to a multi-
            // second positive offset and the segment's tkhd would
            // declare audio-led duration with silent video at the head
            // — players freeze for ~5 s (the pre-record window) at the
            // start of the clip.
            //
            // Drop this audio packet instead. Bounded: writeRebased
            // (video) seeds ptsOriginUs unconditionally on its first
            // call, so the window where audio is dropped is at most
            // one pre-record cursor flush + the first video frame's
            // latency — typically <100 ms of audio. The next live
            // audio frame after the video seed will rebase positively
            // and write normally.
            long n = audioWriteFailureCount.incrementAndGet();
            if (n % 200 == 1) {
                logger.debug("Audio packet dropped (no video origin yet, #" + n + ")");
            }
            return false;
        }
        long rebasedPts = info.presentationTimeUs - ptsOriginUs;
        if (rebasedPts < 0) {
            // Drop instead of clamping. See javadoc.
            long n = audioWriteFailureCount.incrementAndGet();
            if (n % 100 == 1) {
                logger.warn("Audio packet dropped (negative rebased PTS, #" + n + ")");
            }
            return false;
        }
        // Per-track monotonicity guard. MediaMuxer rejects writeSampleData
        // when a packet's PTS is ≤ the previous packet's PTS on the SAME
        // track. This bites us because pre-record audio is drained AFTER
        // muxer start (so the first pre-record packet's PTS is small)
        // while live capture packets arrive with current PTSs and may
        // interleave with the pre-record drain in muxerWriteQueue. The
        // disk writer serializes by enqueue order, not PTS order — so a
        // live packet (T) can land before a pre-record packet (T-5s),
        // causing every subsequent pre-record packet to fail.
        //
        // Drop any audio packet whose rebased PTS is not strictly greater
        // than the last successfully-written audio PTS. The dropped
        // packets show up as a silent gap, NOT as a corrupt audio track.
        // Both video and audio are rebased against the same ptsOriginUs
        // so the timeline stays aligned.
        if (rebasedPts <= lastAudioPtsUs) {
            long n = audioWriteFailureCount.incrementAndGet();
            if (n % 200 == 1) {
                logger.debug("Audio packet dropped (PTS not monotonic: " + rebasedPts
                    + "us <= last " + lastAudioPtsUs + "us, #" + n + ")");
            }
            return false;
        }
        long absolutePts = info.presentationTimeUs;
        info.presentationTimeUs = rebasedPts;
        try {
            mux.writeSampleData(trackIdx, data, info);
            lastAudioPtsUs = rebasedPts;
        } catch (Exception e) {
            info.presentationTimeUs = absolutePts;
            // Don't propagate — an audio write failure should never abort
            // a recording. Log once per 100 to keep field debugging
            // tractable without flooding.
            long n = audioWriteFailureCount.incrementAndGet();
            if (n % 100 == 1) {
                logger.warn("Audio writeSampleData failed (#" + n + "): " + e.getMessage());
            }
            return false;
        }
        info.presentationTimeUs = absolutePts;
        return true;
    }

    private final java.util.concurrent.atomic.AtomicLong audioWriteFailureCount =
        new java.util.concurrent.atomic.AtomicLong(0);
    // Count of video packets NUDGED (+1µs) by the per-track monotonicity guard
    // in writeRebased (pre-record splice / clock-domain re-anchor collisions).
    // These frames are kept, not dropped — dropping a reference-P in this no-B
    // IPPP stream would strand the next P's RPS until the next IDR. A handful
    // per event at the splice is expected and harmless; a flood would indicate
    // a deeper PTS problem worth investigating.
    private final java.util.concurrent.atomic.AtomicLong videoNonMonotonicNudgeCount =
        new java.util.concurrent.atomic.AtomicLong(0);
    // Last successfully-written audio PTS (rebased, microseconds). Used
    // to enforce per-track monotonicity in writeRebasedAudio. Reset on
    // every recording start and segment rotation so the new segment's
    // first audio packet has nothing to compare against.
    private long lastAudioPtsUs = -1L;

    /**
     * Build the AAC audio MediaFormat from the user-supplied CSD-0 and
     * sample/channel parameters, and add it to the given muxer. Called
     * from inside muxerLock at every muxer-start (initial event start,
     * format-changed deferred start, and segment rotation).
     *
     * @return the new audio track index, or -1 if audio is not provisioned
     *         for this segment (toggle off, or app process hasn't sent
     *         a CSD-0 yet). A -1 return is silent — the muxer continues
     *         video-only.
     */
    private int maybeAddAudioTrack(MediaMuxer mux) {
        // Snapshot the volatile holder once. Any concurrent setAudioConfig /
        // disableAudioMuxing only swaps the reference — our locals stay
        // consistent for the duration of the addTrack call.
        AudioConfig cfg = audioConfig;
        if (cfg == null) return -1;
        // Empty-track quarantine guard. If we add an audio track to the
        // muxer but no packet ever reaches writeRebasedAudio in this
        // segment (cold-start race: app's AAC encoder is up and CSD has
        // landed, but the first frame hasn't been pushed by the time the
        // muxer.start() call here lands), some Android versions throw
        // from muxer.stop() because the audio track has zero samples.
        // The whole segment then gets quarantined as .broken — a
        // disproportionately bad outcome for "audio was a few ms late".
        //
        // Gate on packet count: only add the audio track if at least
        // one packet has already flowed under the current audioConfig.
        // The first segment after enabling audio may open video-only;
        // every subsequent segment rotation re-evaluates and picks up
        // audio once packets are confirmed live.
        if (audioPacketCountSinceConfigSet < 1) {
            logger.info("Audio config set but no packets yet — segment opens video-only "
                + "(next rotation will pick up audio)");
            return -1;
        }
        try {
            MediaFormat audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                cfg.sampleRate,
                cfg.channelCount);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate);
            // Hand-crafted CSD-0 (AudioSpecificConfig). MediaMuxer requires
            // this for AAC tracks; we sidestep waiting for the encoder's
            // INFO_OUTPUT_FORMAT_CHANGED by supplying it from the app's
            // upload. For 48kHz mono AAC-LC the canonical bytes are
            // {0x11, 0x88}; we trust whatever the app sends so other
            // sample rates / channels keep working without a daemon
            // change.
            audioFormat.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(cfg.csd0));
            int idx = mux.addTrack(audioFormat);
            logger.info(String.format(
                "Audio track added (track=%d, %dHz %dch %dkbps, csd0=%d bytes)",
                idx, cfg.sampleRate, cfg.channelCount,
                cfg.bitrate / 1000, cfg.csd0.length));
            return idx;
        } catch (Exception e) {
            logger.warn("Failed to add audio track: " + e.getMessage()
                + " — recording continues video-only");
            return -1;
        }
    }

    // Pooled packets for the muxer write path. Per-packet buffer ceiling
    // mirrors the H264CircularBuffer's per-bitrate sizing — 1 MB hard cap
    // covers worst-case 10 Mbps H.265 IDRs at 2560×1920.
    //
    // Pool size is bounded. The previous version had no upper bound, so a
    // single sustained SD-card backpressure burst could push 600 packets
    // through the queue, each producing a 1 MB direct ByteBuffer that then
    // sat in the pool forever (DirectByteBuffer's Cleaner doesn't fire
    // until GC sees the wrapper unreachable, which never happens for a
    // pool reference). On a 4 GB DiLink head unit this was a slow OOM-kill
    // time bomb for a long-uptime daemon.
    //
    // Cap = MUXER_WRITE_QUEUE_CAPACITY + small headroom for "in flight
    // between dequeue and recycle" packets. Steady-state need is tiny
    // (~10 packets); the cap exists as a defensive ceiling, not a working
    // set target. On overflow we drop the released packet and let GC
    // reclaim the direct buffer.
    private static final int MUXER_PACKET_CEILING = 1024 * 1024;
    // Three-tier pool. The original single-pool design had a single retain
    // ceiling at 256 KB, which forced a fresh allocateDirect(~1 MB) on every
    // IDR at MAX H.264 / MAX-PREMIUM H.265 (IDRs ~700KB-1MB, GOP=fps → once
    // every ~2 s); the resulting 5-50 ms native-heap stall on the drainer
    // thread is exactly what the pool was meant to prevent. Size segregation:
    //   - micro (≤4 KB): AAC frames (~256 B at 64 kbps × 20 ms). Without
    //     this tier audio reuses small-pool 5-30 KB P-frame slots and
    //     chronically wastes ~1.2 MB. Audio runs at ~50 pps so the
    //     working set is bigger than P-frames'; hence the 64-slot cap.
    //   - small (≤256 KB): P-frames.
    //   - large (≤1 MB): IDRs. Tighter cap because in-flight IDRs are rare.
    // Acquire walks the matching tier first, then falls through to a
    // larger tier only if the smaller request can borrow a bigger slot.
    // A request never borrows from a tier whose slot is too small.
    // Release routes by capacity.
    private static final int MUXER_PACKET_MICRO_CEILING = 4 * 1024;
    private static final int MUXER_PACKET_SMALL_CEILING = 256 * 1024;
    // Audio at ~50 pps × 20 ms × queue capacity 600 ⇒ working set of
    // ~30-60 packets in worst-case SD backpressure. 64 is a comfortable
    // ceiling (256 KB total native footprint at 4 KB cap each).
    private static final int MUXER_PACKET_MICRO_POOL_CAP = 64;
    // Drainer's working set is ~10 packets steady-state. Retaining all 600
    // backpressure packets could pin ~154 MiB after the queue recovered;
    // 64 still covers more than two seconds at 30 fps and lets GC reclaim the
    // one-off burst before a large replay arena is allocated at a later boot.
    private static final int MUXER_PACKET_SMALL_POOL_CAP = 64;
    // IDRs land roughly once per GOP (~2 s at 30 fps). The drainer keeps
    // them moving; even under SD backpressure the in-flight count rarely
    // exceeds 4-5. 16 is generous headroom — at 1 MB each that's a 16 MB
    // ceiling on this pool's footprint, well within the daemon's envelope.
    private static final int MUXER_PACKET_LARGE_POOL_CAP = 16;
    private final java.util.concurrent.ConcurrentLinkedDeque<MuxerPacket> muxerPacketPoolMicro =
        new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<MuxerPacket> muxerPacketPoolSmall =
        new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<MuxerPacket> muxerPacketPoolLarge =
        new java.util.concurrent.ConcurrentLinkedDeque<>();
    // ConcurrentLinkedDeque.size() is O(n); cheap atomic counters sized
    // separately for each pool. Approximate accuracy under contention is
    // fine — the cap check is defensive, not load-bearing.
    private final java.util.concurrent.atomic.AtomicInteger muxerPacketPoolMicroSize =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger muxerPacketPoolSmallSize =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger muxerPacketPoolLargeSize =
        new java.util.concurrent.atomic.AtomicInteger(0);

    private MuxerPacket acquireMuxerPacket(int requiredSize) {
        MuxerPacket p;
        // Walk from the smallest tier that natively fits the request,
        // falling through to larger tiers if the natural tier is empty.
        // A request never borrows from a tier whose slot is too small.
        if (requiredSize <= MUXER_PACKET_MICRO_CEILING) {
            p = takeFromPool(muxerPacketPoolMicro, muxerPacketPoolMicroSize, requiredSize);
            if (p != null) { p.trackKind = TRACK_KIND_VIDEO; return p; }
            p = takeFromPool(muxerPacketPoolSmall, muxerPacketPoolSmallSize, requiredSize);
            if (p != null) { p.trackKind = TRACK_KIND_VIDEO; return p; }
            p = takeFromPool(muxerPacketPoolLarge, muxerPacketPoolLargeSize, requiredSize);
            if (p != null) { p.trackKind = TRACK_KIND_VIDEO; return p; }
        } else if (requiredSize <= MUXER_PACKET_SMALL_CEILING) {
            p = takeFromPool(muxerPacketPoolSmall, muxerPacketPoolSmallSize, requiredSize);
            if (p != null) { p.trackKind = TRACK_KIND_VIDEO; return p; }
            p = takeFromPool(muxerPacketPoolLarge, muxerPacketPoolLargeSize, requiredSize);
            if (p != null) { p.trackKind = TRACK_KIND_VIDEO; return p; }
        } else {
            p = takeFromPool(muxerPacketPoolLarge, muxerPacketPoolLargeSize, requiredSize);
            if (p != null) { p.trackKind = TRACK_KIND_VIDEO; return p; }
        }
        // None fit — allocate a fresh packet. Size to a power-of-two-ish
        // headroom but cap at MUXER_PACKET_CEILING (1 MB) so a corrupt
        // bufferInfo can't push a multi-MB buffer into the pool.
        // trackKind is reset on the pooled path above and on the fresh
        // path here — defense in depth: a future caller that forgets to
        // set trackKind before offer cannot accidentally route a video
        // frame to audio.
        MuxerPacket fresh = new MuxerPacket();
        int cap = Math.max(requiredSize, Math.min(MUXER_PACKET_CEILING, requiredSize * 2));
        fresh.data = ByteBuffer.allocateDirect(cap);
        fresh.trackKind = TRACK_KIND_VIDEO;
        return fresh;
    }

    private MuxerPacket takeFromPool(java.util.concurrent.ConcurrentLinkedDeque<MuxerPacket> pool,
                                     java.util.concurrent.atomic.AtomicInteger size,
                                     int requiredSize) {
        java.util.Iterator<MuxerPacket> it = pool.iterator();
        while (it.hasNext()) {
            MuxerPacket p = it.next();
            if (p.data != null && p.data.capacity() >= requiredSize) {
                if (pool.remove(p)) {
                    size.decrementAndGet();
                    return p;
                }
            }
        }
        return null;
    }

    private void releaseMuxerPacket(MuxerPacket p) {
        if (p == null || p.data == null) return;
        p.data.clear();
        p.payloadSize = 0;
        p.info.set(0, 0, 0, 0);
        // Reset trackKind so a recycled audio packet doesn't accidentally
        // route to the audio track when reused for a video frame.
        p.trackKind = TRACK_KIND_VIDEO;
        int capBytes = p.data.capacity();
        // Drop pathologically oversized buffers (>1 MB) — they're either
        // a bug or a corrupt encoder packet. Let the Cleaner reclaim them.
        if (capBytes > MUXER_PACKET_CEILING) {
            return;
        }
        // Route by capacity. Tiered: micro (≤4KB, audio AAC frames),
        // small (≤256KB, P-frames), large (≤1MB, IDRs). Each tier has
        // its own cap so audio working-set churn cannot evict the
        // P-frame pool, and IDR slots stay tightly bounded.
        if (capBytes > MUXER_PACKET_SMALL_CEILING) {
            if (muxerPacketPoolLargeSize.get() >= MUXER_PACKET_LARGE_POOL_CAP) {
                return;
            }
            muxerPacketPoolLarge.offer(p);
            muxerPacketPoolLargeSize.incrementAndGet();
        } else if (capBytes > MUXER_PACKET_MICRO_CEILING) {
            if (muxerPacketPoolSmallSize.get() >= MUXER_PACKET_SMALL_POOL_CAP) {
                return;
            }
            muxerPacketPoolSmall.offer(p);
            muxerPacketPoolSmallSize.incrementAndGet();
        } else {
            if (muxerPacketPoolMicroSize.get() >= MUXER_PACKET_MICRO_POOL_CAP) {
                return;
            }
            muxerPacketPoolMicro.offer(p);
            muxerPacketPoolMicroSize.incrementAndGet();
        }
    }

    private void fillMuxerPacket(MuxerPacket dst, ByteBuffer src, MediaCodec.BufferInfo srcInfo) {
        dst.data.clear();
        src.position(srcInfo.offset);
        src.limit(srcInfo.offset + srcInfo.size);
        dst.data.put(src);
        dst.data.flip();
        dst.payloadSize = srcInfo.size;
        dst.info.set(0, srcInfo.size, srcInfo.presentationTimeUs, srcInfo.flags);
    }

    // Use Deque for drop-oldest semantics. Bounded capacity prevents unbounded
    // growth under SD-card backpressure. take() in the disk writer wakes
    // immediately on push — no 4 ms poll-loop latency.
    private final java.util.concurrent.LinkedBlockingDeque<MuxerPacket> muxerWriteQueue =
        new java.util.concurrent.LinkedBlockingDeque<>(MUXER_WRITE_QUEUE_CAPACITY);
    // Separate drop counters per track-kind. Video drops are visible
    // playback hiccups (lost P-frames or skipped IDRs); audio drops are
    // tiny gaps in a continuous stream. Logging them apart helps field
    // diagnostics distinguish "SD card stalled" from "audio producer
    // outpaced consumer".
    private final java.util.concurrent.atomic.AtomicLong muxerDropCount =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong audioDropCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * In-flight finalizer count. Bumped at the head of finalizeOldSegmentAsync,
     * decremented in its finally block. closeEventRecording / release() drain
     * this to zero (with timeout) so the caller can be sure no background
     * thread is still holding a stale muxer or about to fire onFileSaved on
     * a torn-down pipeline.
     *
     * Without this guard, a stop+restart cycle within ~150 ms of a rotation
     * tick can race the finalizer's rename → onFileSaved into the new
     * encoder's lifecycle (RC-audit Finding R1).
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlightFinalizers =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final Object finalizerJoinLock = new Object();

    /**
     * Add a packet to the muxer write queue. If the queue is full, drop the
     * oldest non-keyframe packet to make room. If the queue is full and
     * everything in it is a keyframe (extreme stall), drop the new packet
     * unless it's also a keyframe (in which case drop the oldest keyframe).
     *
     * <p>Drop preference: among non-keyframes, prefer evicting VIDEO
     * P-frames over audio frames. Both are non-keyframe but a P-frame
     * loss is a single visual hiccup whereas under sustained SD stall
     * audio (50 pps) dominates the queue versus video (30 pps), so a
     * naive head-evict would drop audio first. Walk the queue once and
     * track BOTH the oldest non-keyframe AND the oldest video
     * non-keyframe; if any video non-keyframe exists, drop that first.
     * Only fall through to audio drops if no video P-frame is in the
     * queue.
     *
     * <p>Drop counts are split per track-kind ({@link #muxerDropCount}
     * for video, {@link #audioDropCount} for audio) so chronic SD stall
     * vs audio-producer-outpaces-consumer can be distinguished in logs.
     * Logged every 30 drops per kind.
     */
    private void offerMuxerPacket(MuxerPacket packet) {
        if (muxerWriteQueue.offer(packet)) {
            return;
        }
        // Queue full. Walk once from the head, tracking:
        //   - oldestNonKeyframe (any track) — fallback eviction target
        //   - oldestVideoNonKeyframe — preferred eviction target
        // Dropped packets are returned to the pool so their direct
        // buffer is reused immediately by the packet trying to enter
        // the queue.
        java.util.Iterator<MuxerPacket> it = muxerWriteQueue.iterator();
        MuxerPacket oldestNonKf = null;
        MuxerPacket oldestVideoNonKf = null;
        while (it.hasNext()) {
            MuxerPacket head = it.next();
            if (!head.isKeyFrame()) {
                if (oldestNonKf == null) oldestNonKf = head;
                if (head.trackKind == TRACK_KIND_VIDEO && oldestVideoNonKf == null) {
                    oldestVideoNonKf = head;
                    // Found our preferred target — but keep going only
                    // until we have both anchors. Once oldestVideoNonKf
                    // is set we have everything we need; the oldestNonKf
                    // anchor was already captured (it was set before or
                    // is this same packet).
                    break;
                }
            }
        }
        MuxerPacket evicted = (oldestVideoNonKf != null) ? oldestVideoNonKf : oldestNonKf;
        if (evicted != null) {
            muxerWriteQueue.remove(evicted);
        } else {
            // All entries are keyframes — drop the oldest. This only happens
            // under multi-second SD stalls; the recording will have a gap
            // but the daemon stays alive.
            evicted = muxerWriteQueue.pollFirst();
        }
        if (evicted != null) {
            // Increment the per-kind counter for the evicted packet. Log
            // every 30 to keep field debugging tractable.
            if (evicted.trackKind == TRACK_KIND_AUDIO) {
                long n = audioDropCount.incrementAndGet();
                if (n % 30 == 1) {
                    logger.warn("Audio drop count " + n
                        + " — audio producer outpacing muxer queue (video healthy).");
                }
            } else {
                long n = muxerDropCount.incrementAndGet();
                if (n % 30 == 1) {
                    logger.warn("Video drop count " + n
                        + " — muxer write queue saturated, SD card likely stalled.");
                }
            }
            releaseMuxerPacket(evicted);
        }
        // Now there's space.
        muxerWriteQueue.offer(packet);
    }
    private volatile boolean diskWriterRunning = false;
    private Thread diskWriterThread;
    
    // SOTA: Background drainer thread (moves SD card I/O off GL thread)
    private volatile boolean drainerRunning = false;
    private Thread drainerThread;
    private static final int DRAIN_INTERVAL_MS = 16;  // ~60Hz cadence, matches frame arrival rate
    // Set by release() before its final stopDrainerThread() so any nested
    // close path (closeEventRecording → startDrainerThread) skips the
    // restart. Without this, release() and closeEventRecording fight: close
    // restarts the drainer to keep the GL thread responsive during rename,
    // then release() stops it again — but in the window between, the drainer
    // races encoder.release(), throwing transient IllegalStateExceptions.
    private volatile boolean drainerRestartSuppressed = false;

    // Guards ALL drainer lifecycle transitions (start/stop/restart). The
    // bounded pre-yield pattern (PanoramicCameraGpu yieldCameraInternal /
    // restartCameraAfterError) lets onPreYield() keep running on a
    // "PreYield-*" worker after its 8s timeout while the GL thread proceeds
    // to stopDrainerForCameraClose() + closeCameraForPath(). The worker is
    // concurrently inside closeEventRecording()'s stopDrainerThread()/
    // startDrainerThread(). Without this lock, the plain check-then-act on
    // `drainerThread` (`if (drainerThread != null) drainerThread.interrupt()`)
    // races: one thread nulls the field between the other's null-check and
    // interrupt() → NPE that either kills the GL handler thread (escapes
    // yieldCameraInternal) or aborts closeEventRecording before muxer.stop()
    // (leaked muxer + stranded .tmp clip). Lock hold times are bounded (the
    // longest is stopDrainerThread's join(2000)); the drainer thread itself
    // never takes this lock, so joining while holding it cannot deadlock.
    private final Object drainerLock = new Object();

    // Set by stopDrainerForCameraClose(), cleared by
    // restartDrainerAfterCameraClose(). Blocks a late startDrainerThread()
    // from a timed-out pre-yield worker (closeEventRecording's post-rename
    // restart) landing while the GL thread is inside closeCameraForPath() —
    // restarting the MediaCodec drainer against a camera being destroyed is
    // the exact FORTIFY "pthread_mutex_lock called on a destroyed mutex"
    // abort the stop-before-close ordering exists to prevent. Same shape as
    // drainerRestartSuppressed (release()'s permanent-stop latch).
    private volatile boolean drainerSuppressedForCameraClose = false;
    
    // SOTA: Flag to disable pre-record buffer for stream-only encoders
    private boolean usePreRecordBuffer = true;
    // Set true when init()'s byte-ring allocation throws OOM. Distinct from
    // setUsePreRecordBuffer(false) which is the deliberate stream-only mode.
    // /api/status surfaces this so the UI can warn about a degraded session.
    private volatile boolean preRecordAllocFailed = false;

    // Initial pre-record buffer duration. Settable BEFORE init() so the
    // first allocation honours the user's saved value instead of the hardcoded 5s.
    // Runtime edits update the time window without replacing the direct arena.
    //
    // volatile: written by the control-plane (setPreRecordDuration called
    // from HTTP/IPC threads) under bufferLock, read by init() also under
    // bufferLock — but ALSO read at line 738 outside the lock for logging,
    // and by setPreRecordDuration's caller pattern. Lock-paired access
    // would be safe but volatile makes the field uniformly visible across
    // all reader paths without lock-protocol fragility.
    private volatile int preRecordDurationSeconds = 5;
    // Total retention required by enabled manual-clip key bindings. This is separate
    // from preRecordDurationSeconds: surveillance events must still flush only
    // their configured window while the ring keeps enough history for replay.
    private volatile int manualClipRetentionSeconds = 0;
    
    // Pre-allocated BufferInfo — reused every drain cycle to avoid per-frame allocation
    private final MediaCodec.BufferInfo reusableBufferInfo = new MediaCodec.BufferInfo();
    
    // Callback for when file is closed
    private Runnable fileClosedCallback;
    
    // Streaming
    // Volatile: setStreamCallback / clearStreamCallback run on the HTTP
    // worker thread; the drainer thread reads `streamCallback != null &&
    // streamHeadersSent` on every output buffer. Without volatile, a fresh
    // callback set just after CSD publish can be invisible to the drainer
    // (no SPS/PPS sent → late client gets a corrupt stream until the next
    // IDR), and a `streamHeadersSent=false` reset can be missed (drainer
    // keeps thinking headers were sent → never re-sends them).
    private volatile StreamCallback streamCallback;
    private volatile boolean streamHeadersSent = false;
    
    // Recording state
    // volatile: read by isRecording() from RecordingModeManager,
    // GpuSurveillancePipeline, and QualitySettingsApiHandler on threads
    // distinct from the writer (start/stop and drainer paths). Without
    // volatile, weak-memory-model devices may publish stale values to
    // these readers across thread boundaries.
    private volatile boolean recording = false;
    private String outputPath;
    private File tempFile;
    /**
     * Controls who may initiate the Telegram upload when a clip is finalized.
     * Most recorder clients retain the historical automatic behaviour.
     * Surveillance-owned clips are emitted later by SurveillanceEngineGpu,
     * after the event's Notice/Alert/Critical tier has been evaluated.
     */
    public enum VideoUploadPolicy {
        AUTOMATIC,
        SURVEILLANCE_GATED;

        /** Pure policy check kept Android-runtime independent for local tests. */
        boolean shouldAutoUpload(String fileName) {
            if (this != AUTOMATIC) return false;
            if (fileName == null) return true;
            String name = fileName;
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0 && slash + 1 < name.length()) {
                name = name.substring(slash + 1);
            }
            return !name.startsWith("event_");
        }
    }
    private VideoUploadPolicy videoUploadPolicy = VideoUploadPolicy.AUTOMATIC;
    private int recordedFrames = 0;
    private long firstFramePtsUs = -1;   // PTS of first frame written to muxer
    private long lastFramePtsUs = -1;    // PTS of last frame written to muxer
    // Last ABSOLUTE (un-rebased, source-domain) video PTS handed to
    // writeRebased. Used by the clock-domain jump guard to detect a
    // discontinuity (HW→nanoTime transition or an origin re-seeded in one
    // clock domain that then receives a frame in the other) and re-anchor the
    // origin instead of recording a multi-billion-µs gap as literal playback
    // time. Reset to -1 wherever ptsOriginUs is reset; (re)seeded on the
    // first write of each segment. See writeRebased + MAX_PLAUSIBLE_INTERFRAME_GAP_US.
    private long lastSourcePtsUs = -1;
    // Count of clock-domain re-anchors performed by writeRebased. Surfaced
    // for field diagnostics; logged every 50 to keep the log tractable.
    private final java.util.concurrent.atomic.AtomicLong ptsReanchorCount =
        new java.util.concurrent.atomic.AtomicLong(0);
    // Largest plausible inter-frame gap for a real recording. Clips rotate
    // every segmentDurationMs (2/5/10 min) and the GL watchdog force-restarts
    // the pipeline after a 3 s frame stall, so no legitimate gap between two
    // consecutive written video frames approaches this value even at the low
    // fps floor. A larger gap is a clock-domain jump (the BYD DiLink HAL
    // timestamp is a stuck, different-epoch uptime counter; transitioning to
    // System.nanoTime mid-clip yields a gap of billions of µs). Written
    // verbatim, that gap is what makes a 2-min clip's moov declare a
    // 55-min-to-1-hr duration. 10 s is comfortably above any real gap and far
    // below the spurious one, so the guard never trips in normal operation.
    private static final long MAX_PLAUSIBLE_INTERFRAME_GAP_US = 10_000_000L;
    // Duration (seconds) of the most recently finalized clip, captured at
    // rename time before the PTS bookkeeping is reset for the next segment.
    // Read by SurveillanceEngineGpu to caption its gated Telegram video send.
    private volatile int lastFinalizedDurationSec = 0;
    // PTS rebase origin: subtracted from every packet's PTS before
    // muxer.writeSampleData. Captured from the FIRST packet written to a
    // given muxer instance — so the muxer always sees a timeline starting
    // at 0, regardless of where the encoder's clock happened to be.
    //
    // Why this matters: the encoder's presentationTimeUs counts up from
    // process start (or first input frame), so a recording triggered 60s
    // into the daemon's life sees PTSs ~60_000_000us. The pre-record buffer
    // packets carry absolute encoder PTSs ~53s; the first live packet is
    // ~60s. MediaMuxer mostly handles non-zero origins, but the FIRST
    // muxer instance after savedFormat is captured has a quirk where the
    // duration field in the moov atom can be miscalculated, producing an
    // mp4 whose declared duration is shorter than the bitstream span (e.g.
    // declared 10s, actual 16s) — playback freezes at the declared
    // duration mark, exactly the "video breaks at 6s" symptom on the very
    // first recording. Subsequent recordings work because savedFormat is
    // already stable by then. Rebase to 0 eliminates the ambiguity.
    //
    // Rotation: each new muxer instance re-captures origin from its own
    // first packet (firstFramePtsUs reset on rotation), so segment N+1
    // also starts at 0 in its own muxer.
    private long ptsOriginUs = -1;
    
    // Segment rotation
    private long segmentStartTime = 0;
    // Live, per-instance clip segment length. Seeded from the shared default
    // (2 min) and overridden via setSegmentDurationMs() — both recording axes
    // read recording.segmentDurationMinutes and push it here at encoder init,
    // and the API handler pushes live changes. volatile so the API thread's
    // write is visible to the drainer thread's rotation check without a lock.
    private volatile long segmentDurationMs = com.overdrive.app.util.Constants.SEGMENT_DURATION_MS;
    // Debounce window for forceSegmentRotation: if the current segment was
    // started less than this many ms ago, a force-rotation is treated as a
    // no-op. Prevents the natural-rotation path (drainer thread, no
    // startStopLock) from interleaving with a force-rotation (API thread,
    // holds startStopLock) and producing a near-empty middle segment with
    // bad PTS bookkeeping (firstFramePtsUs == -1 fallback).
    private static final long ROTATE_DEBOUNCE_MS = 1000L;
    // Max wall-clock the segment rotation will spend writing the queued backlog
    // into the OLD (about-to-be-finalized) muxer before giving up and dropping
    // the remainder. This drain (in rotateSegmentLocked) runs on the DRAINER
    // thread under muxerLock and does blocking writeSampleData calls; if a
    // stalled USB/SD write makes them block, the drainer stops dequeuing the
    // encoder, the encoder input Surface fills, the GL thread blocks in
    // eglSwapBuffers, and the 3s GL watchdog (PanoramicCameraGpu
    // GL_THREAD_TIMEOUT_MS) force-restarts the process — truncating the clip
    // and leaving a .broken stub (the field-observed "records then stops after
    // a few seconds while driving"). Capping the drain far below the 3s
    // watchdog turns a storage stall into a sub-second gap at the 2-minute
    // segment seam instead of a process kill. Healthy rotations drain a handful
    // of frames in well under 1 ms, so this budget is never reached in normal
    // operation.
    private static final long ROTATE_DRAIN_BUDGET_MS = 200L;
    // CAS gate shared by every rotateSegment() caller (natural drainer tick +
    // forceSegmentRotation HTTP path). Whoever flips false→true does the
    // rotation; concurrent callers observe true and bail. Reset in finally
    // inside rotateSegment so a thrown exception doesn't permanently lock
    // future rotations. Closes the window where a force fires within ~50ms
    // of a natural tick and produces an empty middle segment.
    private final java.util.concurrent.atomic.AtomicBoolean rotationInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private int segmentNumber = 0;
    private String segmentBasePath = null;  // Base path for segment rotation (without .mp4)

    // ---- Geo-tagging snapshot (for v3 sidecar geo block) -----------------
    // Captured at triggerEventRecording start, before MediaMuxer.start().
    // Re-read on every rotation so each segment carries its own startLocation
    // matching the time it actually began. Sentinel values (Double.NaN) mean
    // "no GPS fix at trigger time" — the JSON writer skips emission rather
    // than writing 0.0, 0.0 which would point at the Atlantic Ocean off
    // West Africa and break "show on map" UX.
    //
    // `volatile` is correct here on Android: ART's memory model has always
    // guaranteed atomic 64-bit reads/writes for `volatile long` and
    // `volatile double`, even on 32-bit ARM. (JLS §17.7 only relaxes
    // atomicity for non-volatile longs/doubles.)
    // Max age for a GPS fix to be tag-worthy at capture time. Mirrors the 5-minute
    // fallback window already enforced in EventTimelineCollector / LocationSidecarWriter,
    // applied here at the SOURCE so the primary recorder-captured snapshot is gated too
    // (not just the cold-start fallback). A fix older than this — or one still loaded
    // from the persisted cache — is rejected, leaving startGeo* at NaN (no tag).
    private static final long GEO_FIX_MAX_AGE_MS = 5L * 60L * 1000L;

    private volatile double startGeoLat = Double.NaN;
    private volatile double startGeoLng = Double.NaN;
    private volatile float  startGeoAccuracy = 0f;
    private volatile long   startGeoAgeMs = -1L;
    private volatile long   startGeoCapturedAtMs = 0L;

    // Snapshot of the JUST-CLOSED segment's start-geo, captured inside
    // rotateSegmentLocked() before the active fields above are overwritten
    // for the new segment. The engine's segment listener reads these via
    // getClosedStartGeo*() so the closed segment's sidecar carries the GPS
    // fix from the time IT began, not the time the next segment begins.
    // Without this split, every rotated segment's geo.start would
    // misattribute to the rotation moment instead of the segment-start
    // moment — visible on a 30-min trip as segment 1 having segment 2's
    // location.
    private volatile double closedStartGeoLat = Double.NaN;
    private volatile double closedStartGeoLng = Double.NaN;
    private volatile float  closedStartGeoAccuracy = 0f;
    private volatile long   closedStartGeoAgeMs = -1L;
    private volatile long   closedStartGeoCapturedAtMs = 0L;
    
    // Timing
    private long startTimeNs = 0;
    
    /**
     * Creates a GPU-compatible hardware encoder.
     * 
     * @param width Video width (typically 2560)
     * @param height Video height (typically 1920)
     * @param fps Frame rate (typically 15)
     * @param bitrate Bitrate in bps (typically 6-8 Mbps)
     */
    public HardwareEventRecorderGpu(int width, int height, int fps, int bitrate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
    }
    
    /**
     * Creates a GPU-compatible hardware encoder with codec selection.
     * 
     * @param width Video width (typically 2560)
     * @param height Video height (typically 1920)
     * @param fps Frame rate (typically 15)
     * @param bitrate Bitrate in bps (typically 2-6 Mbps)
     * @param codecMimeType MIME type (MIMETYPE_VIDEO_AVC for H.264, MIMETYPE_VIDEO_HEVC for H.265)
     */
    public HardwareEventRecorderGpu(int width, int height, int fps, int bitrate, String codecMimeType) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.codecMimeType = codecMimeType;
    }
    
    /**
     * Returns the configured frame rate (KEY_FRAME_RATE on the encoder format).
     * Used by the pipeline to detect FPS config drift.
     */
    public int getFps() {
        return fps;
    }

    /**
     * Duration (seconds, rounded) of the most recently finalized clip. Captured
     * at rename time before per-segment PTS state resets. Used by
     * SurveillanceEngineGpu to caption its tier-gated Telegram video send.
     * Returns 0 if nothing has finalized yet.
     */
    public int getLastFinalizedDurationSec() {
        return lastFinalizedDurationSec;
    }

    /**
     * Sets the codec MIME type before initialization.
     * Must be called before init().
     *
     * @param mimeType MIMETYPE_VIDEO_AVC (H.264) or MIMETYPE_VIDEO_HEVC (H.265)
     */
    /**
     * Skip KEY_OPERATING_RATE on this encoder. Call before {@link #init()}.
     * Secondary encoders running concurrently with a primary one (e.g. OEM
     * dashcam alongside pano) should disable this so the SDM665 Venus
     * firmware doesn't over-subscribe the encoder block.
     */
    public void setPinOperatingRate(boolean pin) {
        if (encoder != null) {
            logger.warn("setPinOperatingRate after init — has no effect");
            return;
        }
        this.pinOperatingRate = pin;
    }

    public void setCodecMimeType(String mimeType) {
        if (encoder != null) {
            logger.warn("Cannot change codec after initialization - restart required");
            return;
        }
        this.codecMimeType = mimeType;
        logger.info("Codec set to: " + (mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265/HEVC" : "H.264/AVC"));
    }
    
    /**
     * Gets the current codec MIME type.
     */
    public String getCodecMimeType() {
        return codecMimeType;
    }
    
    /**
     * Checks if using H.265/HEVC codec.
     */
    public boolean isHevcCodec() {
        return MediaFormat.MIMETYPE_VIDEO_HEVC.equals(codecMimeType);
    }
    
    /**
     * Initializes the encoder with Surface input.
     * 
     * @throws Exception if initialization fails
     */
    public void init() throws Exception {
        logger.info( String.format("Initializing: %dx%d @ %dfps, %d Mbps, codec=%s",
                width, height, fps, bitrate / 1_000_000,
                codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264"));
        
        // Create format with Surface input - use configured codec
        MediaFormat format = MediaFormat.createVideoFormat(codecMimeType, width, height);
        
        // CRITICAL: Use COLOR_FormatSurface for GPU input
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        // I-frame cadence. Recording keeps its historical 2s. LIVE-VIEW STREAMING
        // uses 1s, matching the OEM app (sl/g.java:238 sets "i-frame-interval" to 1
        // for its AVM encoder).
        //
        // Why it matters here specifically: the byd_apa HAL emits at its own fixed
        // low rate (~4.5 fps observed) and cannot be retimed — setCameraFps returns
        // false for every value, and both the OEM app and DiPlus discard that
        // return. At 4.5 fps a 2-second interval is ~9 frames between keyframes, so
        // a viewer that joins late, drops a packet, or sees a run of near-empty
        // P-frames has no recovery point for two seconds and the picture appears
        // stuck. A 1-second cadence bounds that. Cost is a modest bitrate increase
        // on a 2 Mbps stream — cheap next to an unusable preview.
        //
        // Scoped to stream-only encoders (usePreRecordBuffer == false, set before
        // init() by GpuSurveillancePipeline:4037 and OemDashcamPipeline), so
        // recordings on EVERY vehicle — dilink4 or legacy — keep byte-identical
        // encoder settings.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, usePreRecordBuffer ? 2 : 1);

        // Bitrate mode left to encoder default (typically VBR). CBR was tried
        // but caused recordings to freeze 5-6s in on the BYD DiLink 5.0 H.265
        // encoder — the platform encoder doesn't honor the explicit
        // BITRATE_MODE_CBR cleanly and produces malformed bitstream that
        // stalls subsequent frames. Reverted.

        // Set max input size to prevent Qualcomm crashes
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);
        
        // Low latency hints (optional)
        try {
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        } catch (Exception e) {
            // Ignore if not supported
        }

        // KEY_OPERATING_RATE pins the platform encoder's processing rate to
        // the configured fps. Without this, the SoC governor can briefly
        // downclock the encoder/GPU between frames, causing periodic 100-200ms
        // output gaps that propagate as eglSwap stalls (the encoder's input
        // pool fills behind a transiently-slowed encode pipeline).
        //
        // Setting this to fps ≥ KEY_FRAME_RATE tells the encoder "commit to
        // sustaining at least this throughput" — Qualcomm/Snapdragon platforms
        // honor this by holding the encoder hardware at full frequency for
        // the duration of the recording. Cost: marginally higher power; that
        // tradeoff is correct here because we already have the encoder
        // running continuously for the pre-record buffer.
        //
        // Available since API 23 (we're targeting min 28). Wrapped in try
        // so non-Qualcomm or older platforms gracefully ignore it.
        if (pinOperatingRate) {
            try {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
                logger.info("Encoder OPERATING_RATE pinned at " + fps);
            } catch (Throwable t) {
                logger.warn("Could not set OPERATING_RATE: " + t.getMessage());
            }
        } else {
            logger.info("Encoder OPERATING_RATE pin skipped (secondary encoder)");
        }
        
        // H.265 specific optimizations for Snapdragon 665
        if (codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            try {
                // Use Main profile for better compatibility
                format.setInteger(MediaFormat.KEY_PROFILE, 
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4);
                logger.info("H.265 profile set to Main/Level 4");
            } catch (Exception e) {
                logger.warn("Could not set H.265 profile: " + e.getMessage());
            }
        } else {
            // H.264: Use Baseline Profile for iOS Safari compatibility
            try {
                format.setInteger(MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                format.setInteger(MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                logger.info("H.264 profile set to Baseline/Level 3.1 (iOS compatible)");
            } catch (Exception e) {
                logger.warn("Could not set H.264 profile: " + e.getMessage());
            }
        }
        
        // CRITICAL: All MediaCodec operations can block if hardware encoder is stuck
        // Wrap each operation with a timeout to prevent daemon freeze
        final MediaFormat finalFormat = format;
        final String finalCodecMimeType = codecMimeType;
        
        // Create encoder with timeout
        logger.info("Creating MediaCodec encoder...");
        final MediaCodec[] encoderResult = {null};
        final Exception[] createError = {null};
        Thread createThread = new Thread(() -> {
            try {
                encoderResult[0] = MediaCodec.createEncoderByType(finalCodecMimeType);
            } catch (Exception e) {
                createError[0] = e;
            }
        }, "EncoderCreate");
        createThread.start();
        try {
            createThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Encoder create interrupted");
        }
        if (createThread.isAlive()) {
            logger.error("MediaCodec.createEncoderByType TIMEOUT - hardware encoder stuck");
            createThread.interrupt();
            throw new RuntimeException("Encoder create timeout - try restarting mediaserver");
        }
        if (createError[0] != null) {
            throw createError[0];
        }
        encoder = encoderResult[0];
        // Confirm the negotiated codec name on this device, not just our intent.
        // If the device-side codec selection silently downgraded HEVC→AVC (rare,
        // but possible if the platform encoder list rejects HEVC for our params),
        // this log line surfaces it instead of leaving the user to guess from
        // file sizes.
        try {
            String negotiatedName = encoder.getName();
            logger.info("MediaCodec encoder created (codec=" + finalCodecMimeType
                    + ", impl=" + negotiatedName + ")");
        } catch (Exception ignored) {
            logger.info("MediaCodec encoder created");
        }
        
        // Configure encoder with timeout
        logger.info("Configuring encoder...");
        final boolean[] configDone = {false};
        final Exception[] configError = {null};
        Thread configThread = new Thread(() -> {
            try {
                encoder.configure(finalFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                configDone[0] = true;
            } catch (Exception e) {
                configError[0] = e;
            }
        }, "EncoderConfig");
        configThread.start();
        try {
            configThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Encoder config interrupted");
        }
        if (!configDone[0]) {
            if (configThread.isAlive()) {
                logger.error("encoder.configure TIMEOUT - hardware encoder stuck");
                configThread.interrupt();
                try { encoder.release(); } catch (Exception e) {}
                encoder = null;
                throw new RuntimeException("Encoder configure timeout");
            }
            if (configError[0] != null) {
                throw configError[0];
            }
        }
        logger.info("Encoder configured");
        
        // Create input surface with timeout
        logger.info("Creating input surface...");
        final Surface[] surfaceResult = {null};
        final Exception[] surfaceError = {null};
        Thread surfaceThread = new Thread(() -> {
            try {
                surfaceResult[0] = encoder.createInputSurface();
            } catch (Exception e) {
                surfaceError[0] = e;
            }
        }, "EncoderSurface");
        surfaceThread.start();
        try {
            surfaceThread.join(10000);
        } catch (InterruptedException e) {
            logger.warn("Surface create interrupted");
        }
        if (surfaceResult[0] == null) {
            if (surfaceThread.isAlive()) {
                logger.error("createInputSurface TIMEOUT - hardware encoder stuck");
                surfaceThread.interrupt();
                try { encoder.release(); } catch (Exception e) {}
                encoder = null;
                throw new RuntimeException("Surface create timeout");
            }
            if (surfaceError[0] != null) {
                throw surfaceError[0];
            }
        }
        inputSurface = surfaceResult[0];
        logger.info("Input surface created");
        
        // Start encoder with timeout
        logger.info("Starting encoder...");
        final Exception[] startError = {null};
        final boolean[] startDone = {false};
        
        Thread startThread = new Thread(() -> {
            try {
                encoder.start();
                startDone[0] = true;
            } catch (Exception e) {
                startError[0] = e;
            }
        }, "EncoderStart");
        
        startThread.start();
        try {
            startThread.join(10000); // 10 second timeout
        } catch (InterruptedException e) {
            logger.warn("Encoder start interrupted");
        }
        
        if (!startDone[0]) {
            if (startThread.isAlive()) {
                logger.error("Encoder start TIMEOUT after 10s - hardware encoder may be stuck");
                startThread.interrupt();
                // Try to release the encoder
                try {
                    encoder.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoder = null;
                inputSurface = null;
                throw new RuntimeException("Encoder start timeout - hardware encoder busy or stuck");
            }
            if (startError[0] != null) {
                throw startError[0];
            }
        }
        logger.info("Encoder started");
        
        // SOTA: byte-ring is allocated once (lazy) and shared across encoder
        // instances. The ring's byte arena is bitrate- and fps-agnostic, so
        // codec/bitrate/fps changes never recreate it — only the user's
        // duration setting can require a window adjustment, which is a
        // cheap field write. This eliminates the four-axis triplet reuse
        // logic the slot pool needed.
        if (usePreRecordBuffer) {
            int desiredSec = effectivePreRecordRetentionSeconds();
            int desiredBudget = computePreRecordBudgetBytes(desiredSec, bitrate);
            if (useInstancePreRecordBuffer) {
                // Per-instance arena. Skip the static shared ring entirely so
                // OEM and pano never share a producer — see field doc on
                // useInstancePreRecordBuffer for the corruption-by-interleave
                // motivation. Allocation happens once per encoder start and
                // is reclaimed on release(); steady-state memory cost is
                // bounded by the configured budget (8–128 MB).
                preRecordBuffer = allocatePreRecordBufferWithFallback(
                        desiredBudget, desiredSec, "per-instance");
                if (preRecordBuffer != null) {
                    preRecordBufferIsInstance = true;
                    logger.info("Allocated per-instance pre-record byte ring: budget="
                        + (preRecordBuffer.getBudgetBytes() / 1024 / 1024) + "MB, duration="
                        + (preRecordBuffer.getMaxDurationUs() / 1_000_000L)
                        + "s, bitrate=" + (bitrate / 1_000_000) + "Mbps");
                } else {
                    logger.error("Pre-record fallback allocation failed — running "
                            + "without pre-record. Live recording unaffected.");
                    preRecordBufferIsInstance = false;
                    usePreRecordBuffer = false;
                    preRecordAllocFailed = true;
                }
            } else synchronized (bufferLock) {
                if (sharedPreRecordBuffer == null) {
                    // Allocate the configured arena only when no shared direct
                    // buffer exists. Replacing a live smaller ring with 128 MiB
                    // would retain both until the Cleaner runs and recreate the
                    // native-memory spike that the byte-ring design removed.
                    logger.info("Allocating pre-record byte ring: budget="
                        + (desiredBudget / 1024 / 1024) + "MB, duration="
                        + desiredSec + "s, bitrate=" + (bitrate / 1_000_000) + "Mbps");
                    sharedPreRecordBuffer = allocatePreRecordBufferWithFallback(
                            desiredBudget, desiredSec, "shared");
                    sharedPreRecordBudgetBytes = sharedPreRecordBuffer != null
                            ? sharedPreRecordBuffer.getBudgetBytes() : 0;
                    if (sharedPreRecordBuffer == null) {
                        logger.error("Pre-record fallback allocation failed — running "
                                + "without pre-record. Live recording unaffected.");
                        usePreRecordBuffer = false;
                        preRecordAllocFailed = true;
                    }
                } else {
                    // Same-process encoder reinit: reuse the existing arena.
                    // A larger saved replay window takes full effect on the
                    // next daemon cold start, when the old direct buffer is no
                    // longer resident and the correct size is allocated once.
                    sharedPreRecordBuffer.clear();
                    long desiredUs = desiredSec * 1_000_000L;
                    sharedPreRecordBuffer.setMaxDurationUs(desiredUs);
                    if (desiredBudget > sharedPreRecordBudgetBytes) {
                        logger.warn("Saved replay window needs "
                                + (desiredBudget / 1024 / 1024) + "MB; reusing "
                                + (sharedPreRecordBudgetBytes / 1024 / 1024)
                                + "MB until camera-daemon cold start");
                    } else {
                        logger.info("Reusing pre-record byte ring ("
                            + (sharedPreRecordBudgetBytes / 1024 / 1024) + "MB): " + desiredSec + "s");
                    }
                }
                preRecordBuffer = sharedPreRecordBuffer;
            }
        } else {
            logger.info("Pre-record buffer disabled (stream-only mode)");
            preRecordBuffer = null;
        }

        // SOTA: Start background drainer thread (moves SD card I/O off GL thread)
        startDrainerThread();

        logger.info("Encoder initialized successfully"
                + (usePreRecordBuffer ? " (event pre-record="
                    + Math.max(1, preRecordDurationSeconds) + "s, retained="
                    + effectivePreRecordRetentionSeconds() + "s)" : " (stream-only)"));
    }
    
    /**
     * Updates the pre-record buffer size.
     * 
     * @param durationSeconds New buffer duration in seconds
     */
    public void setPreRecordDuration(int durationSeconds) {
        int clamped = Math.max(1, Math.min(30, durationSeconds));
        // Always remember the desired duration so a later init() (e.g. after
        // pipeline reinit) starts at the correct window even if the byte ring
        // has been freed.
        this.preRecordDurationSeconds = clamped;
        applyPreRecordRetentionWindow(effectivePreRecordRetentionSeconds());
    }

    /**
     * Keep enough encoded history for enabled manual replay bindings without
     * changing the shorter window used by surveillance/proximity event flushes.
     * A live edit changes the retention clock immediately, but a larger native
     * arena is intentionally deferred to a camera-daemon cold start.
     */
    public void setManualClipRetentionDuration(int durationSeconds) {
        manualClipRetentionSeconds = Math.max(0, Math.min(60, durationSeconds));
        applyPreRecordRetentionWindow(effectivePreRecordRetentionSeconds());
    }

    /**
     * True when the active arena cannot guarantee the requested manual window.
     * This diagnoses only replay capacity; an unrelated event pre-roll setting
     * must not make the Key Mapping page claim that its binding needs restart.
     */
    public boolean requiresCameraDaemonRestartForManualClip(int durationSeconds) {
        int manualSeconds = Math.max(0, Math.min(60, durationSeconds));
        if (manualSeconds == 0) return false;
        int retainedSeconds = Math.min(62,
                manualSeconds + MANUAL_CLIP_GOP_HEADROOM_SECONDS);
        H264ByteRingBuffer ring = preRecordBuffer;
        return ring != null
                && (computePreRecordBudgetBytes(retainedSeconds, bitrate) > ring.getBudgetBytes()
                    || ring.getMaxDurationUs() < retainedSeconds * 1_000_000L);
    }

    /** Whether the current arena and duration policy can retain a full request. */
    public boolean canRetainManualClip(int durationSeconds) {
        int manualSeconds = Math.max(0, Math.min(60, durationSeconds));
        if (manualSeconds == 0) return false;
        int retainedSeconds = Math.min(62,
                manualSeconds + MANUAL_CLIP_GOP_HEADROOM_SECONDS);
        H264ByteRingBuffer ring = preRecordBuffer;
        return ring != null
                && computePreRecordBudgetBytes(retainedSeconds, bitrate) <= ring.getBudgetBytes()
                && ring.getMaxDurationUs() >= retainedSeconds * 1_000_000L;
    }

    private int effectivePreRecordRetentionSeconds() {
        int manualRetention = manualClipRetentionSeconds > 0
                ? manualClipRetentionSeconds + MANUAL_CLIP_GOP_HEADROOM_SECONDS
                : 0;
        return Math.max(1, Math.min(62,
                Math.max(preRecordDurationSeconds, manualRetention)));
    }

    private void applyPreRecordRetentionWindow(int retentionSeconds) {
        final int clamped = Math.max(1, Math.min(62, retentionSeconds));
        final long desiredUs = clamped * 1_000_000L;
        final int desiredBudget = computePreRecordBudgetBytes(clamped, bitrate);

        // Never replace a direct arena at runtime. The old ByteBuffer may stay
        // resident until its Cleaner runs, so a 64→128 MiB edit could create a
        // dangerous transient peak even though the primary dashcam itself is
        // healthy. The larger size is allocated once on a future cold start.
        if (preRecordBufferIsInstance && preRecordBuffer != null) {
            H264ByteRingBuffer current = preRecordBuffer;
            current.setMaxDurationUs(desiredUs);
            if (desiredBudget > current.getBudgetBytes()) {
                logger.warn("Pre-record ring needs " + (desiredBudget / 1024 / 1024)
                        + "MB for " + clamped + "s; keeping "
                        + (current.getBudgetBytes() / 1024 / 1024)
                        + "MB until the next cold encoder start");
            }
            return;
        }

        // A per-instance encoder can receive its duration settings before
        // init() allocates the private ring. Its desired fields are already
        // updated above; it must not resize another encoder's shared ring in
        // this pre-init state. Once initialized, the branch above owns updates.
        if (useInstancePreRecordBuffer && preRecordBuffer == null) {
            return;
        }

        synchronized (bufferLock) {
            if (sharedPreRecordBuffer != null) {
                sharedPreRecordBuffer.setMaxDurationUs(desiredUs);
                if (desiredBudget > sharedPreRecordBudgetBytes) {
                    logger.warn("Pre-record ring needs " + (desiredBudget / 1024 / 1024)
                            + "MB for " + clamped + "s; keeping "
                            + (sharedPreRecordBudgetBytes / 1024 / 1024)
                            + "MB until the next camera-daemon cold start");
                } else {
                    logger.info("Pre-record retention window updated to " + clamped + "s");
                }
            }
        }
    }

    /**
     * Sets the live clip segment length in milliseconds. Both recording axes
     * push the shared recording.segmentDurationMinutes value here at encoder
     * init, and the quality API pushes live edits. Safe to call at any time:
     * volatile field, read by the drainer thread's rotation check. A change
     * takes effect on the NEXT rotation — the in-progress segment keeps its
     * original length (no mid-segment retiming, no muxer disturbance).
     *
     * Ignores non-positive values defensively so a corrupt config can never
     * disable rotation (which would let a single .mp4.tmp grow unbounded and
     * stay unfinalized/unplayable).
     */
    public void setSegmentDurationMs(long durationMs) {
        if (durationMs <= 0) {
            logger.warn("Ignoring non-positive segmentDurationMs=" + durationMs
                + " (keeping " + segmentDurationMs + "ms)");
            return;
        }
        if (durationMs != segmentDurationMs) {
            segmentDurationMs = durationMs;
            logger.info("Clip segment duration set to " + (durationMs / 1000) + "s "
                + "(applies on next rotation)");
        }
    }

    /** Current live clip segment length in milliseconds. */
    public long getSegmentDurationMs() {
        return segmentDurationMs;
    }

    /**
     * Allocate the desired history arena, then degrade through unique smaller
     * budgets. A replay allocation failure must not remove the safety event's
     * existing pre-roll; the final 8 MiB attempt preserves at least a partial
     * event window on memory-constrained units.
     */
    private H264ByteRingBuffer allocatePreRecordBufferWithFallback(
            int desiredBudget, int desiredSeconds, String owner) {
        int eventSeconds = Math.max(1, Math.min(30, preRecordDurationSeconds));
        int eventBudget = computePreRecordBudgetBytes(eventSeconds, bitrate);
        int[] budgets = new int[] {
                desiredBudget,
                desiredBudget > LONG_REPLAY_FALLBACK_BYTES
                        ? LONG_REPLAY_FALLBACK_BYTES : 0,
                eventBudget,
                PRE_RECORD_BUDGET_FLOOR_BYTES
        };
        int[] durations = new int[] {
                desiredSeconds,
                desiredSeconds,
                eventSeconds,
                eventSeconds
        };

        for (int i = 0; i < budgets.length; i++) {
            int candidateBudget = budgets[i];
            if (candidateBudget <= 0) continue;
            boolean duplicate = false;
            for (int prior = 0; prior < i; prior++) {
                if (budgets[prior] == candidateBudget) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;

            try {
                H264ByteRingBuffer ring = new H264ByteRingBuffer(
                        candidateBudget, durations[i]);
                if (i > 0) {
                    logger.warn("Using " + (candidateBudget / 1024 / 1024)
                            + "MB " + owner + " pre-record fallback; manual replay "
                            + "history may require a camera-daemon cold restart");
                }
                return ring;
            } catch (OutOfMemoryError | RuntimeException allocationError) {
                logger.warn(owner + " " + (candidateBudget / 1024 / 1024)
                        + "MB pre-record allocation failed: "
                        + allocationError.getMessage());
            }
        }
        return null;
    }

    /**
     * Sizes the pre-record byte arena from the configured retention window and
     * current encoder bitrate. The 8 MiB floor preserves normal event-recorder
     * behavior; the 128 MiB ceiling covers a 60-second MAX H.264 replay plus
     * the preceding GOP.
     */
    private static int computePreRecordBudgetBytes(int durationSeconds, int bitrateBps) {
        // bytes = (bps × s ÷ 8) × overhead
        long ideal = (long) ((bitrateBps / 8.0) * durationSeconds
                * PRE_RECORD_BITRATE_OVERHEAD);
        long bytes = ideal;
        if (bytes < PRE_RECORD_BUDGET_FLOOR_BYTES) bytes = PRE_RECORD_BUDGET_FLOOR_BYTES;
        if (bytes > PRE_RECORD_BUDGET_CEILING_BYTES) bytes = PRE_RECORD_BUDGET_CEILING_BYTES;
        if (ideal > PRE_RECORD_BUDGET_CEILING_BYTES) {
            // User configured more pre-roll than the 128 MiB ceiling can hold at
            // this bitrate. The ring will evict older packets to stay within
            // the byte arena, so the effective window will be < durationSeconds.
            // Log so the user can correlate observed pre-roll with their
            // settings; this matches the legacy slot-pool's behavior at the
            // same ceiling.
            long achievableSeconds = (long)
                ((PRE_RECORD_BUDGET_CEILING_BYTES * 8.0)
                    / (bitrateBps * PRE_RECORD_BITRATE_OVERHEAD));
            logger.warn("Pre-record budget capped at "
                + (PRE_RECORD_BUDGET_CEILING_BYTES / 1024 / 1024)
                + "MB; requested " + durationSeconds + "s × "
                + (bitrateBps / 1_000_000) + "Mbps needs "
                + (ideal / 1024 / 1024) + "MB. Effective window ≈ "
                + achievableSeconds + "s.");
        }
        return (int) bytes;
    }
    
    /**
     * Sets whether this encoder uses the pre-record buffer.
     * Should be set to false for stream-only encoders.
     * 
     * @param useBuffer true to use pre-record buffer, false for stream-only mode
     */
    public void setUsePreRecordBuffer(boolean useBuffer) {
        this.usePreRecordBuffer = useBuffer;
        if (!useBuffer) {
            logger.info("Pre-record buffer disabled (stream-only mode)");
        }
    }

    /**
     * Opt this encoder into a per-instance pre-record byte ring instead of
     * the static shared one. Required when more than one encoder instance
     * is alive simultaneously (e.g. pano + OEM dashcam) — the static ring
     * is single-producer by contract, so two writers would interleave
     * SPS/PPS bytes and corrupt every flush. Pano keeps the shared ring;
     * OEM calls this with {@code true} before {@link #init()}.
     *
     * <p>Cost: one direct allocation (8–128 MB depending on bitrate × pre-roll)
     * per encoder lifetime, freed on {@link #release()}. Setting this AFTER
     * init() is a no-op for the current session — the next reinit will
     * pick it up.
     */
    public void setUseInstancePreRecordBuffer(boolean instanceOwned) {
        this.useInstancePreRecordBuffer = instanceOwned;
        if (instanceOwned) {
            logger.info("Pre-record buffer marked per-instance (no static-shared sharing)");
        }
    }


    /**
     * Sets the streaming callback for H.264 packet distribution.
     * 
     * If the encoder has already output its format (SPS/PPS), the callback
     * will receive them immediately. This handles the case where a new
     * client connects after the encoder has already started.
     * 
     * @param callback Callback to receive H.264 packets
     */
    public void setStreamCallback(StreamCallback callback) {
        this.streamCallback = callback;
        this.streamHeadersSent = false;
        
        // If format already available, send SPS/PPS immediately
        // This handles late-joining clients after encoder has started
        if (callback != null && savedFormat != null) {
            try {
                ByteBuffer sps = savedFormat.getByteBuffer("csd-0");
                ByteBuffer pps = savedFormat.getByteBuffer("csd-1");
                if (sps != null && pps != null) {
                    callback.onSpsPps(sps.duplicate(), pps.duplicate());
                    streamHeadersSent = true;
                    logger.info("SPS/PPS sent immediately to new callback (late join)");
                }
            } catch (Exception e) {
                logger.error("Failed to send SPS/PPS to new callback", e);
            }
        }
        
        logger.info("Stream callback registered");
    }
    
    /**
     * Checks if the encoder format (SPS/PPS) is available.
     *
     * @return true if format is available, false otherwise
     */
    public boolean isFormatAvailable() {
        return savedFormat != null;
    }

    // ==================== AUDIO MUXING API ====================
    //
    // Called by AacIngestServer (daemon side) when the app process connects
    // and announces its AAC encoder parameters. The handshake is:
    //   1. App connects, sends one CONFIG packet with CSD-0 + sampleRate
    //      + channelCount + bitrate.
    //   2. Daemon calls setAudioConfig(...). This DOES NOT cause anything
    //      to happen to a running muxer — it just primes the next event /
    //      segment rotation so its addTrack(audioFormat) call has data.
    //   3. App sends DATA packets (AAC frames + PTS in microseconds since
    //      capture started).
    //   4. Daemon calls pushAudioPacket(...) per frame. The packet rides
    //      the existing muxerWriteQueue and the disk writer routes it to
    //      audioTrackIndex.
    //
    // Lifecycle: the app side stops capture on ACC OFF, mode change, or
    // toggle off; the daemon clears audioMuxingEnabled when it sees the
    // ingest socket close. Stale audioCsd0 is fine — the next connect just
    // overwrites it.

    /**
     * Set the AAC encoder configuration that the muxer will use for its
     * audio track. Safe to call from any thread; takes effect at the next
     * recording start or segment rotation. Setting csd0=null disables
     * audio muxing for subsequent segments.
     */
    public void setAudioConfig(byte[] csd0, int sampleRate, int channelCount, int bitrate) {
        if (csd0 == null || csd0.length == 0) {
            this.audioConfig = null;
            // Reset the confidence counter: a later re-enable starts
            // from "no packets yet" so the first post-enable muxer
            // opens video-only until packets actually flow, avoiding
            // the empty-audio-track quarantine.
            this.audioPacketCountSinceConfigSet = 0;
            // Clear the pre-record ring: its packets reference an audio
            // session that's no longer active, and a later re-enable would
            // start a fresh capture stream whose PTSs no longer align with
            // the stale packets in the ring.
            aacRing.clear();
            logger.info("Audio muxing disabled (csd0=null)");
            return;
        }
        // Single volatile write — no torn-read possible. Defensive clone of
        // the byte[] so a caller mutating their original array later cannot
        // corrupt our snapshot.
        this.audioConfig = new AudioConfig(csd0.clone(), sampleRate, channelCount, bitrate);
        // Reset confidence counter on every config swap so a stale
        // "audio was flowing under the previous config" doesn't bleed
        // into the new config's track-add decision.
        this.audioPacketCountSinceConfigSet = 0;
        logger.info(String.format(
            "Audio config set: %d Hz %d ch, %d kbps, csd0=%d bytes",
            sampleRate, channelCount, bitrate / 1000, csd0.length));
    }

    /**
     * Disable audio muxing. Used when the app's audio capture stops
     * (toggle off, ACC off, app process died). Already-queued audio
     * packets in muxerWriteQueue are dropped by the writer when it sees
     * audioTrackIndex == -1; the active recording closes out as
     * video-only, which is exactly what should happen.
     */
    public void disableAudioMuxing() {
        if (audioConfig != null) {
            logger.info("Audio muxing disabled by caller");
        }
        this.audioConfig = null;
        // Mirror setAudioConfig(null): reset confidence so a subsequent
        // enable doesn't inherit "packets flowing" state from the prior
        // session.
        this.audioPacketCountSinceConfigSet = 0;
        // Drop any pre-record packets we'd captured during the previous
        // audio-enabled session. Same rationale as setAudioConfig(null):
        // the next enable starts a fresh capture stream and the stale
        // packets would no longer line up with the new PTS origin.
        aacRing.clear();
    }

    /**
     * Returns true if audio muxing is enabled and the muxer has an audio
     * track wired up. Used by AacIngestServer to drop incoming packets
     * cheaply when no recording is in flight.
     */
    public boolean isAudioMuxingActive() {
        return audioConfig != null && audioTrackIndex >= 0 && isWritingToFile;
    }

    /**
     * Returns true iff this encoder instance has received and stored the
     * AAC AudioSpecificConfig. Used by AacIngestServer to detect "encoder
     * was recreated under us" (e.g. recording mode switch tears down the
     * pipeline + encoder; a fresh instance starts with audioConfig=null
     * even though the long-lived AAC TCP client is still streaming
     * packets). On false, the ingest server replays its cached CONFIG
     * payload so the new encoder picks up the muxer track on its next
     * recording start.
     */
    public boolean hasAudioConfig() {
        return audioConfig != null;
    }

    /**
     * Push one AAC frame into the muxer write queue. Frame data must be a
     * raw AAC access unit (NO ADTS header — MediaMuxer wants raw AU).
     *
     * @param data    AAC AU bytes
     * @param length  Valid byte count in data
     * @param ptsUs   Presentation timestamp in microseconds, monotonic
     *                with the video PTSs (same wall clock origin)
     * @return true if accepted, false if dropped (no recording, no audio
     *         track, or queue under SD-card backpressure)
     */
    public boolean pushAudioPacket(byte[] data, int length, long ptsUs) {
        return pushAudioPacket(data, 0, length, ptsUs);
    }

    /**
     * Offset variant of {@link #pushAudioPacket(byte[], int, long)} — copies
     * directly from {@code data} at {@code offset}, eliminating a
     * per-frame heap copy in callers that already have a buffered AAC
     * stream. The intended caller (AacIngestServer) reads frames from a
     * SocketChannel into a recycled scratch buffer and forwards
     * {@code (scratch, frameOffset, frameLength, pts)} without a
     * temporary {@code byte[]} per frame.
     *
     * @param data    AAC AU bytes
     * @param offset  Starting byte index into data
     * @param length  Valid byte count starting at offset
     * @param ptsUs   Presentation timestamp in microseconds
     */
    public boolean pushAudioPacket(byte[] data, int offset, int length, long ptsUs) {
        // Cheap pre-checks outside any lock — fast-fail path for the common
        // "no recording in flight" case.
        if (data == null || length <= 0 || offset < 0
                || offset > data.length - length) {
            return false;
        }
        // Pre-record capture is independent of isWritingToFile: the whole
        // point of the audio ring is to hold the seconds BEFORE a recording
        // starts so the resulting clip has audio at frame 0 instead of 5 s
        // of silent video. Gate only on audioConfig — when the user has
        // audio disabled there's no point copying bytes into a ring whose
        // drain on event-trigger is also gated on the same field. The ring
        // owns its own deque + atomic byte counter; no lock taken here.
        if (audioConfig != null) {
            aacRing.add(data, offset, length, ptsUs);
            // Bump the confidence counter every time we receive a valid
            // audio packet against the current config. This is what
            // maybeAddAudioTrack consults at muxer-start / rotation time
            // to decide whether audio is actually flowing — if no packets
            // have arrived yet we open video-only to avoid the empty-track
            // quarantine, and pick up audio at the next rotation.
            // Volatile write; no lock — the read side (maybeAddAudioTrack)
            // tolerates a transient stale read since the next rotation
            // recovers.
            audioPacketCountSinceConfigSet++;
        }
        if (!isWritingToFile || audioConfig == null || audioTrackIndex < 0
                || awaitLiveMuxerKeyframe) {
            return false;
        }
        // AAC frames are tiny (~256 B at 64 kbps × 20 ms). acquireMuxerPacket
        // walks the micro pool first so we never waste a P-frame or IDR slot
        // on audio. (See MUXER_PACKET_MICRO_CEILING.)
        MuxerPacket pkt = acquireMuxerPacket(length);
        if (pkt == null) return false;
        pkt.data.clear();
        pkt.data.put(data, offset, length);
        pkt.data.flip();
        pkt.payloadSize = length;
        // No flags — AAC frames have no BUFFER_FLAG_KEY_FRAME concept the
        // muxer cares about, and crucially we want them to be eligible for
        // drop-oldest-non-keyframe under SD backpressure (audio gap is
        // tolerable; video gap is not).
        pkt.info.set(0, length, ptsUs, 0);
        pkt.trackKind = TRACK_KIND_AUDIO;
        // Re-check the gate under muxerLock and only offer if still valid.
        // Otherwise: between the gate read above and offerMuxerPacket, a
        // concurrent closeEventRecording() could flip isWritingToFile /
        // tear down the muxer. The dropped audio is fine, but if a NEW
        // recording starts before the queue drains, this stale packet
        // (with OLD-recording PTS) would land in the NEW muxer and
        // produce out-of-order PTS errors. Tight critical section: a
        // single state check + one bounded queue offer.
        synchronized (muxerLock) {
            if (!isWritingToFile || audioConfig == null || audioTrackIndex < 0
                    || awaitLiveMuxerKeyframe) {
                releaseMuxerPacket(pkt);
                return false;
            }
            offerMuxerPacket(pkt);
        }
        return true;
    }

    /**
     * Force-rotate the active segment NOW, wrapping the current .mp4 and
     * starting a new one. Used by the API endpoint when audioEnabled flips
     * on so the user's next clip actually has audio (rather than waiting
     * up to the natural 2-minute rotation tick for the new segment to pick
     * up the audio track).
     *
     * <p>No-op if no recording is in flight. Holds {@link #startStopLock}
     * to serialize against start/stop entry points; the inner
     * {@link #rotateSegment()} acquires {@link #muxerLock} per the
     * documented lock ordering.
     */
    public void forceSegmentRotation() {
        synchronized (startStopLock) {
            if (!isWritingToFile) {
                return;
            }
            // segmentBasePath is set by triggerEventRecording. If null,
            // rotateSegment would NPE; defensive check matches the
            // structure of stopEventRecording's outer-volatile / inner-lock
            // pattern.
            if (segmentBasePath == null) {
                logger.warn("forceSegmentRotation skipped — no segmentBasePath (recording mid-init)");
                return;
            }
            // Debounce against the natural-rotation path. The drainer's
            // 2-minute tick calls rotateSegment() WITHOUT startStopLock,
            // so a force here can interleave: both paths pre-construct a
            // new MediaMuxer off-lock and then swap in sequence, producing
            // an empty/near-empty middle segment with PTS bookkeeping in
            // its initial state (firstFramePtsUs == -1).
            //
            // Briefly take muxerLock just to read segmentStartTime
            // atomically against rotateSegment's swap (which writes
            // segmentStartTime under muxerLock). Release before calling
            // rotateSegment(), which acquires muxerLock itself per the
            // documented lock ordering — we don't want to hold muxerLock
            // across the rotateSegment call (rotateSegment's body assumes
            // it can pre-construct the new muxer OFF the lock and then
            // briefly enter the lock; nesting would defeat that).
            //
            // Lock ordering: recordingLock → startStopLock → muxerLock.
            // We're already holding startStopLock; taking muxerLock here
            // is consistent.
            final long sinceLastRotate;
            synchronized (muxerLock) {
                sinceLastRotate = (segmentStartTime > 0)
                        ? (System.currentTimeMillis() - segmentStartTime)
                        : Long.MAX_VALUE;
            }
            if (sinceLastRotate < ROTATE_DEBOUNCE_MS) {
                logger.info("forceSegmentRotation debounced — last rotation "
                        + sinceLastRotate + "ms ago (< " + ROTATE_DEBOUNCE_MS
                        + "ms window); skipping to avoid empty middle segment");
                return;
            }
            logger.info("forceSegmentRotation: wrapping current segment so the next one carries audio");
            rotateSegment();

            // After rotation, verify the new segment actually got the audio
            // track. If not (CAS lost to a concurrent natural rotation that
            // pre-constructed its muxer while audioConfig was briefly null,
            // or the audio config is in a stale window mid-reconfigure),
            // schedule one follow-up rotation 1.5s later. By then either
            // (a) the next AAC DATA packet has triggered the
            // identity-changed replay path in AacIngestServer and audio is
            // wired up, or (b) audio is genuinely gone again (config null)
            // and the follow-up is a no-op.
            //
            // Daemon thread so JVM shutdown isn't blocked on the sleep.
            // Single-shot — no spin if the second attempt also misses.
            if (audioTrackIndex < 0 && hasAudioConfig()) {
                logger.info("forceSegmentRotation: new segment has no audio track — scheduling 1.5s follow-up");
                Thread followup = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        synchronized (startStopLock) {
                            if (!isWritingToFile) return;
                            // Already wired up by the next natural rotation
                            // tick or by another forceSegmentRotation call.
                            if (audioTrackIndex >= 0) return;
                            // Audio gone again — nothing to gain by rotating.
                            if (!hasAudioConfig()) return;
                            rotateSegment();
                            logger.info("forceSegmentRotation follow-up fired");
                        }
                    }
                }, "ForceRotateFollowup");
                followup.setDaemon(true);
                followup.start();
            }
        }
    }

    /**
     * One-shot listener invoked the first time the encoder publishes its
     * output format (SPS/PPS available). Set by GpuSurveillancePipeline so
     * deferred recordings can start as soon as the format is ready, without
     * waiting for the camera-probe callback that doesn't fire when probe
     * is disabled (validated camera config path on cold start).
     */
    public interface FormatAvailableListener {
        void onFormatAvailable();
    }

    private volatile FormatAvailableListener formatAvailableListener = null;

    /**
     * Listener fired when {@link #rotateSegment()} finalises an old segment
     * before opening a new one. Lets the surveillance engine flush hero
     * thumbnails + JSON sidecar against the segment's actual filename
     * (otherwise long events split across multiple .mp4 files would attach
     * all metadata to the FIRST segment, leaving subsequent segments as
     * unbadged plain MP4s in the recordings list).
     *
     * Fired AFTER the old segment is renamed from .tmp to its final .mp4
     * name. Safe to read the file from inside {@code onSegmentClosed}.
     * Fires on the encoder drainer thread; consumers should not block.
     */
    public interface SegmentListener {
        /**
         * @param closedSegment   the .mp4 file just renamed from .tmp,
         *                        or {@code null} if the rotation produced
         *                        no playable file (broken segment quarantined).
         * @param newSegment      the new .mp4 path (still pre-finalize, pending
         *                        bytes), so the engine knows what filename the
         *                        next stop / next rotation will land on.
         */
        void onSegmentClosed(java.io.File closedSegment, java.io.File newSegment);
    }

    private volatile SegmentListener segmentListener = null;

    public void setSegmentListener(SegmentListener listener) {
        this.segmentListener = listener;
    }

    public void setFormatAvailableListener(FormatAvailableListener listener) {
        this.formatAvailableListener = listener;
        // If format is already available when the listener is registered,
        // fire immediately so callers don't miss the edge.
        if (listener != null && savedFormat != null) {
            try { listener.onFormatAvailable(); }
            catch (Exception e) { logger.warn("FormatAvailableListener error: " + e.getMessage()); }
            this.formatAvailableListener = null;
        }
    }
    
    /**
     * Waits for the encoder format to become available.
     * 
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if format became available, false if timeout
     */
    public boolean waitForFormat(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (savedFormat == null) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // Preserve interrupt status so outer callers (lifecycle
                // executor, stop coordinators) observe the cancellation
                // signal instead of seeing only a `false` return.
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
    
    /**
     * Removes the streaming callback.
     */
    public void clearStreamCallback() {
        this.streamCallback = null;
        this.streamHeadersSent = false;
        logger.info("Stream callback cleared");
    }
    
    /**
     * Gets the input surface for GPU rendering.
     * 
     * @return Surface that GPU should render to
     */
    public Surface getInputSurface() {
        return inputSurface;
    }
    
    /**
     * Triggers event recording with pre-record buffer flush.
     * 
     * SOTA: Non-blocking implementation. Pre-record packets are queued
     * and written by drainEncoder() on the GL thread, eliminating the
     * blocking I/O that caused video stutter on motion detection.
     * 
     * @param outputPath Path for the output MP4 file
     * @param postRecordDurationMs Post-record duration in milliseconds
     * @return true if started successfully, false otherwise
     */
    public boolean triggerEventRecording(String outputPath, long postRecordDurationMs) {
        return triggerEventRecording(outputPath, postRecordDurationMs,
                VideoUploadPolicy.AUTOMATIC);
    }

    /**
     * Trigger event recording with an explicit Telegram upload owner.
     *
     * <p>{@link VideoUploadPolicy#SURVEILLANCE_GATED} suppresses this low-level
     * recorder's automatic upload. The surveillance engine must then emit the
     * finalized clip only after its severity-tier gate passes. This is required
     * for OEM {@code dvr_*} mirrors of parked events: their filename otherwise
     * looks like an ordinary driving clip and bypasses the parent event gate.
     */
    public boolean triggerEventRecording(String outputPath, long postRecordDurationMs,
            VideoUploadPolicy uploadPolicy) {
        // Format barrier (LOCK-FREE): refuse to build a muxer until the encoder
        // has published its OUTPUT_FORMAT_CHANGED. Run BEFORE startStopLock
        // entry so a 2-s busy poll doesn't block concurrent stopEventRecording
        // / forceSegmentRotation / OemDashcamApiHandler lifecycle work. Worst
        // case: two concurrent callers both pass this check, then serialize on
        // startStopLock and the second observes isWritingToFile == true and
        // no-ops — same outcome as the pre-fix lock-only design.
        //
        // Without this barrier, a release build can race ahead (R8-inlined
        // isFormatAvailable + stripped logger.info on the success path) and
        // construct a MediaMuxer with no addTrack call. The deferred fallback
        // in drainEncoderInternal occasionally rescues it, but if a stop
        // arrives first (segment rotation, ACC bounce, lifecycle teardown)
        // the muxer closes with trackIndex=-1 and zero samples — a ~168 KB
        // file with no mvhd duration that players extrapolate as multi-minute
        // garbage.
        if (savedFormat == null) {
            if (!waitForFormat(2000) || savedFormat == null) {
                logger.error("triggerEventRecording: encoder hasn't published format "
                    + "after 2s wait — refusing to build empty muxer (would produce "
                    + "0-track .mp4 with corrupted duration)");
                return false;
            }
        }

        // Hold startStopLock across the entire start path so two concurrent
        // callers can't both observe isWritingToFile == false and race ahead
        // to build two muxers. The work inside is dominated by a few mkdirs
        // and a MediaMuxer ctor (sub-100ms typically), so blocking another
        // start request for that long is acceptable — the alternative is the
        // duplicate-files-on-disk bug.
        synchronized (startStopLock) {
            if (isWritingToFile) {
                // Already recording — caller (proximity controller / sentry
                // engine) owns the actual stop schedule. We just no-op here;
                // the previous "extend the post-record timer" path wrote a
                // field nothing read.
                logger.info("Event already in progress — second trigger ignored (extend handled by caller)");
                return true;
            }

            // A manual replay may already own the ring's single range cursor.
            // Do not cancel it: the event muxer still starts immediately and
            // records live frames, while beginFlushRange below simply skips
            // event pre-roll if the replay has not released its pin yet.
            if (manualClipExportInProgress) {
                logger.warn("Manual replay owns pre-record cursor; event recording "
                        + "will start live and may omit its pre-trigger window");
            }

        try {
            this.outputPath = outputPath;
            this.videoUploadPolicy = uploadPolicy != null
                    ? uploadPolicy : VideoUploadPolicy.AUTOMATIC;
            
            // Write to temp file during recording
            tempFile = new File(outputPath + ".tmp");
            
            // Ensure parent directory exists
            File parentDir = tempFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created && !parentDir.exists()) {
                    // Retry once after short delay (SD card may need time to be accessible)
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    created = parentDir.mkdirs();
                }
                if (created) {
                    logger.info("Created parent directory: " + parentDir.getAbsolutePath());
                    parentDir.setReadable(true, false);
                    parentDir.setWritable(true, false);
                    parentDir.setExecutable(true, false);
                } else if (!parentDir.exists()) {
                    logger.error("Failed to create parent directory: " + parentDir.getAbsolutePath());
                    return false;
                }
                // Directory exists (either created or already existed) - continue
            }
            
            // SOTA: clear any stale per-segment state from the previous
            // recording before the new muxer goes live. Without this, leftover
            // PTS/frame counters from a prior run would mislead the duration
            // computation in closeEventRecording.
            recordedFrames = 0;
            firstFramePtsUs = -1;
            lastFramePtsUs = -1;
            actualPreRecordDurationMs = 0L;
            ptsOriginUs = -1;
            lastSourcePtsUs = -1;
            lastAudioPtsUs = -1L;
            awaitLiveMuxerKeyframe = false;
            pendingAudioPreRecord = null;
            // Seed the disk-write clock at segment open so the wedge ticker's
            // grace window is measured from "muxer just opened," not a stale
            // prior-session value — a fresh segment must never be judged a
            // disk-stall before its first sample lands.
            lastDiskWrittenMs = System.currentTimeMillis();
            writerAbortedCorrupt = false;
            writerAbortedErrorMessage = null;
            // Reset per-recording audio failure counter. Without this, the
            // every-100 log threshold would be a lifetime-of-object counter
            // and field logs would confuse a chronic-bad-recording symptom
            // with a one-time burst inside a single event.
            audioWriteFailureCount.set(0);

            // Snapshot current GPS into volatile fields so the segment's
            // sidecar writer can include startLocation. Captured BEFORE the
            // muxer ctor so MediaMuxer.setLocation() can also use it.
            captureStartLocationSnapshot();

            // savedFormat is guaranteed non-null here by the lock-free
            // pre-barrier above. A concurrent stop between the barrier and
            // here can't null savedFormat (it's only ever assigned, never
            // cleared) — encoder release leaves the field pointing at the
            // last-published format, harmless to addTrack against. encoder
            // torn down during the wait is the real concern; check it.
            if (encoder == null) {
                logger.warn("triggerEventRecording: encoder torn down during format wait");
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                tempFile = null;
                return false;
            }

            // Create muxer. Hold muxerLock so the disk writer never observes a
            // half-constructed muxer (e.g., started but trackIndex still -1).
            boolean muxerOk = false;
            synchronized (muxerLock) {
                try {
                    muxer = new MediaMuxer(tempFile.getAbsolutePath(),
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                    // ISO 6709 location box in the moov atom. Apple Photos,
                    // GoPro Quik, VLC and most map-aware libraries surface
                    // it as the recording's geotag. Wrapped: a malformed
                    // (NaN/0) coordinate must not break the recording.
                    try {
                        if (!Double.isNaN(startGeoLat) && !Double.isNaN(startGeoLng)) {
                            // MediaMuxer requires |lat| <= 90 and |lng| <= 180.
                            float lat = (float) Math.max(-90.0, Math.min(90.0, startGeoLat));
                            float lng = (float) Math.max(-180.0, Math.min(180.0, startGeoLng));
                            muxer.setLocation(lat, lng);
                        }
                    } catch (Throwable geoErr) {
                        logger.warn("MediaMuxer.setLocation failed: " + geoErr.getMessage());
                    }

                    // savedFormat is guaranteed non-null by the barrier above —
                    // unconditional addTrack + start(). Add audio track BEFORE
                    // muxer.start(); MediaMuxer rejects addTrack post-start.
                    // If audio is enabled but the app's CSD-0 hasn't arrived
                    // yet, fall through video-only; the muxer is fixed for
                    // the life of this segment. The next rotation picks up
                    // the audio track if the CSD has landed by then.
                    trackIndex = muxer.addTrack(savedFormat);
                    audioTrackIndex = maybeAddAudioTrack(muxer);
                    muxer.start();
                    muxerStarted = true;
                    logger.info("Muxer started with saved format (videoTrack="
                        + trackIndex + ", audioTrack=" + audioTrackIndex + ")");
                    muxerOk = true;
                } catch (Exception e) {
                    logger.error("MediaMuxer setup failed", e);
                    if (muxer != null) {
                        try { muxer.release(); } catch (Exception ignored) {}
                        muxer = null;
                    }
                    muxerStarted = false;
                    trackIndex = -1;
                    audioTrackIndex = -1;
                }
            }
            if (!muxerOk) {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                tempFile = null;
                return false;
            }

            boolean videoPreRecordCursorAcquired = false;
            if (!manualClipExportInProgress
                    && savedFormat != null && preRecordBuffer != null) {
                // SOTA: streaming flush. beginFlush() takes a seqlock-validated
                // snapshot and pins the byte-arena read frontier. The drainer
                // thread iterates the cursor and writes packets directly into
                // muxerWriteQueue — no deep-copy, no per-packet allocateDirect
                // burst on the trigger thread.
                //
                // Pre-record duration is computed approximately from the first
                // and last packet's PTS (can't be exact because we'd have to
                // walk the cursor twice; close enough for log + timeline).
                int flushBytes = preRecordBuffer.peekFlushBytes();
                long latestPtsUs = preRecordBuffer.getLatestPtsUs();
                long requestedStartPtsUs = latestPtsUs == Long.MIN_VALUE
                        ? Long.MIN_VALUE
                        : latestPtsUs - Math.max(1, preRecordDurationSeconds) * 1_000_000L;
                H264ByteRingBuffer.Cursor cursor = latestPtsUs == Long.MIN_VALUE
                        ? null
                        : preRecordBuffer.beginFlushRange(requestedStartPtsUs, latestPtsUs);
                if (cursor != null) {
                    videoPreRecordCursorAcquired = true;
                    double preRecordDuration = Math.max(0L,
                            cursor.getEndPtsUs() - cursor.getStartPtsUs()) / 1_000_000.0;
                    actualPreRecordDurationMs = (long) (preRecordDuration * 1000);
                    pendingFlushCursor = cursor;
                    logger.info(String.format(
                        "Pre-record flush armed: %d packets (%.1f sec, %.1f MB) — streaming via cursor",
                        cursor.remaining(), preRecordDuration, flushBytes / 1024.0 / 1024.0));
                } else {
                    logger.warn("Pre-record flush skipped — no keyframe in buffer");
                    pendingFlushCursor = null;
                }
            }

            // Stage historical AAC, but do not queue it here. The drainer must
            // enqueue every selected video packet first so writeRebased seeds
            // the shared PTS origin before writeRebasedAudio sees old AAC. The
            // youngest AAC PTS anchors filtering without assuming clock parity
            // between the app and daemon processes.
            pendingAudioPreRecord = null;
            if (audioConfig != null && manualClipExportInProgress
                    && !videoPreRecordCursorAcquired) {
                // The accepted replay still needs the historical AAC packets.
                // Leave them in the non-destructive ring; this event starts
                // with live video and live audio instead.
                logger.info("Audio pre-record retained for active manual replay; "
                        + "event audio starts live");
            } else if (audioConfig != null) {
                java.util.List<AacCircularBuffer.Packet> audioPackets =
                    aacRing.drainAll();
                if (!videoPreRecordCursorAcquired && !audioPackets.isEmpty()) {
                    // Another consumer (notably a manual replay) may own the
                    // video ring. In that case the event starts from live
                    // video, so carrying old AAC into it would create an
                    // audio-only pre-roll and could delay the first picture.
                    logger.info("Audio pre-record flush skipped: no matching video cursor "
                        + "(" + audioPackets.size() + " ring packets discarded)");
                } else if (audioTrackIndex >= 0 && !audioPackets.isEmpty()) {
                    // Use the youngest ring packet's PTS as the time anchor
                    // for the pre-record window. This avoids the
                    // cross-process clock-domain assumption (daemon's
                    // System.nanoTime() vs app's System.nanoTime() — same
                    // kernel CLOCK_MONOTONIC backing on Android, but no
                    // contractual guarantee).
                    //
                    // Ring is FIFO insertion order; pushAudioPacket sends
                    // PTSs monotonically, so the last packet is the
                    // youngest.
                    long anchorPtsUs = audioPackets.get(audioPackets.size() - 1).ptsUs;
                    long minPtsUs = anchorPtsUs
                        - Math.max(1, preRecordDurationSeconds) * 1_000_000L;
                    java.util.List<AacCircularBuffer.Packet> filteredPackets =
                            new java.util.ArrayList<>(audioPackets.size());
                    int filtered = 0;
                    for (AacCircularBuffer.Packet ap : audioPackets) {
                        if (ap.ptsUs < minPtsUs) {
                            filtered++;
                            continue;
                        }
                        filteredPackets.add(ap);
                    }
                    pendingAudioPreRecord = filteredPackets;
                    logger.info("Audio pre-record staged: " + filteredPackets.size()
                        + " packets, " + filtered + " filtered as out-of-window"
                        + " (window=" + preRecordDurationSeconds + "s, anchor="
                        + anchorPtsUs + "us)");
                } else if (!audioPackets.isEmpty()) {
                    // Audio enabled but no muxer track — packets discarded.
                    // Next recording will pick up fresh audio from a clean
                    // ring (drainAll already emptied it).
                    logger.info("Audio pre-record flush skipped: no audio track on muxer "
                        + "(" + audioPackets.size() + " ring packets discarded)");
                }
            }

            // Publish the video cursor before the writer gate. The drainer
            // always queues it, then pendingAudioPreRecord, then live output.
            flushInProgress = pendingFlushCursor != null;
            startTimeNs = System.nanoTime();
            segmentStartTime = System.currentTimeMillis();
            segmentNumber = 0;
            segmentBasePath = outputPath.replaceAll("\\.mp4$", "");
            awaitLiveMuxerKeyframe = true;
            isWritingToFile = true;
            recording = true;

            // Post-record duration is enforced by the caller (sentry engine /
            // proximity controller / RecordingModeManager) — this encoder
            // does not own the stop schedule.

            // SPLICE IDR: force the encoder to emit a keyframe on the next LIVE
            // frame, so the first packet after the pre-record flush is a
            // self-contained IDR. The pre-record ring holds already-encoded
            // H.265 packets whose bitstream POC references pictures from the
            // PRE-TRIGGER clock domain; when live capture resumes at the splice,
            // those references are gone and the decoder throws "Could not find
            // ref with POC N / Error constructing frame RPS / First slice
            // missing", FREEZING the last pre-record frame until the encoder's
            // natural ~2s I-frame interval (observed: a ~0.68s stall at the
            // pre-record→live boundary). writeRebased's PTS re-anchor + nudge
            // fix the CONTAINER timeline but cannot repair the bitstream RPS —
            // only a fresh IDR at the resume point restarts the reference chain
            // cleanly. Same rationale as the segment-rotation requestSyncFrame()
            // (which the "triggerEventRecording forces no IDR" comment in
            // writeRebased flagged as the gap). Cost: one extra keyframe
            // (~tens of KB) per event — negligible vs. a visible freeze.
            requestSyncFrame();

            logger.info(String.format("Event recording started: %s (codec=%s, bitrate=%d Mbps, post-record=%dms)",
                tempFile.getName(),
                codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                bitrate / 1_000_000,
                postRecordDurationMs));
            return true;

        } catch (Exception e) {
            logger.error("Failed to trigger event recording", e);
            awaitLiveMuxerKeyframe = false;
            H264ByteRingBuffer.Cursor failedCursor = pendingFlushCursor;
            pendingFlushCursor = null;
            pendingAudioPreRecord = null;
            flushInProgress = false;
            if (failedCursor != null) failedCursor.close();
            // Best-effort cleanup so a partial init doesn't leave a muxer alive
            // referencing a now-orphaned tmp file.
            synchronized (muxerLock) {
                if (muxer != null) {
                    try { muxer.release(); } catch (Exception ignored) {}
                    muxer = null;
                }
                muxerStarted = false;
                trackIndex = -1;
                audioTrackIndex = -1;
            }
            if (tempFile != null && tempFile.exists()) tempFile.delete();
            tempFile = null;
            isWritingToFile = false;
            recording = false;
            return false;
        }
        } // end synchronized (startStopLock)
    }

    /**
     * Legacy method for compatibility - redirects to triggerEventRecording.
     */
    public boolean startRecording(String outputPath) {
        return triggerEventRecording(outputPath, 5000);  // Default 5 sec post-record
    }

    /**
     * Snapshot the current GPS fix into the {@code startGeo*} fields. Called
     * once per recording (and once per rotated segment via the rotation
     * path). Wrapped in a wide catch — GPS lookup must never break recording.
     *
     * <p>Skipped entirely when geocoding is disabled for the relevant flow
     * (recording vs surveillance — derived from the output filename prefix).
     * The unified config check is cheap (cached map lookup) so we read it
     * on every start, which means a mid-recording toggle takes effect at
     * the next rotation boundary without any explicit invalidation.
     */
    private void captureStartLocationSnapshot() {
        startGeoLat = Double.NaN;
        startGeoLng = Double.NaN;
        startGeoAccuracy = 0f;
        startGeoAgeMs = -1L;
        startGeoCapturedAtMs = 0L;
        try {
            String flow = inferGeocodingFlow(outputPath);
            if (!com.overdrive.app.config.UnifiedConfigManager
                    .isGeocodingEnabledForFlow(flow)) {
                return;
            }
            com.overdrive.app.monitor.GpsMonitor gps =
                com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (!gps.hasLocation()) return;
            // FRESHNESS GATE — the single source of truth for "is this fix tag-worthy".
            // GpsMonitor.hasLocation() is true for ANY non-(0,0) fix, INCLUDING a
            // cache-loaded one from a previous drive/boot (loadedFromCache) and one
            // whose live updates have simply gone stale. Surveillance (event_*) fires
            // ACC-OFF / parked, exactly when the GPS sidecar is least likely to be
            // feeding fresh fixes — so without this gate a parked sentry clip would be
            // tagged with the last drive's destination (e.g. yesterday's home address).
            // Reject both vectors here at the SOURCE so hasStartGeo() is honest and every
            // downstream consumer (surveillance segments, continuous/rotated segments,
            // recording sidecars) inherits the guarantee — leaving the fields NaN means
            // the sidecar writer omits the geo block entirely (no wrong pin), and
            // EventTimelineCollector's own fallback re-poll then governs the cold-start case.
            // AGE against the MONOTONIC since-boot fix timestamp vs the daemon's own
            // elapsedRealtime() — NOT getLastUpdate() (= send-time, refreshed by the
            // sidecar's 4s keep-alive even when the fix is unchanged, so a parked
            // car's stale fix read age≈0 and tagged the last drive's destination).
            // Same device-wide monotonic clock on both sides → skew-immune, so the
            // device RTC being wrong at cold boot can't drop a fresh fix's tag.
            // Fallback when no monotonic basis (older sidecar / cache-loaded): age
            // send-time vs currentTimeMillis() = prior behavior, never worse.
            long nowMs = System.currentTimeMillis();
            long fixElapsed = gps.getFixElapsedMs();
            long nowElapsed = android.os.SystemClock.elapsedRealtime();
            long ageMs;
            // Future-dated fixElapsed = cross-boot/incomparable basis (prior-boot
            // last-known seed) → fall back to send-time aging, NOT clamp-to-fresh
            // (which would tag a stale fix). Same fix as GeoSnapshot.capture.
            if (fixElapsed > 0L && fixElapsed <= nowElapsed) {
                ageMs = nowElapsed - fixElapsed;
            } else {
                long lu = gps.getLastUpdate();
                ageMs = lu > 0 ? Math.max(0L, nowMs - lu) : -1L;
            }
            boolean fresh = !gps.isLoadedFromCache()
                    && ageMs >= 0L
                    && ageMs <= GEO_FIX_MAX_AGE_MS;
            if (!fresh) {
                // Leave startGeo* at the NaN sentinel set above → no tag.
                return;
            }
            startGeoLat = gps.getLatitude();
            startGeoLng = gps.getLongitude();
            startGeoAccuracy = gps.getAccuracy();
            startGeoAgeMs = ageMs;
            startGeoCapturedAtMs = nowMs;
        } catch (Throwable t) {
            // Reset defensively so a partial snapshot never lands in a sidecar.
            startGeoLat = Double.NaN;
            startGeoLng = Double.NaN;
            logger.warn("captureStartLocationSnapshot failed: " + t.getMessage());
        }
    }

    /**
     * Map a recording's output path to the geocoding config flow that
     * gates it.
     * <ul>
     *   <li>{@code event_*.mp4} (sentry / surveillance pipeline) →
     *       {@code "surveillance"}</li>
     *   <li>{@code cam_*.mp4}, {@code proximity_*.mp4} or anything else
     *       (dashcam, proximity guard, manual) → {@code "recording"}</li>
     * </ul>
     *
     * <p>Filename-based dispatch is deliberate: this class is mode-agnostic
     * and its callers (RecordingModeManager, GpuSurveillancePipeline,
     * SurveillanceEngineGpu) all encode the flow into the path they pass
     * us. Threading a separate enum would force every call site to be
     * touched; the prefix is already an authoritative classifier.
     */
    private static String inferGeocodingFlow(String outPath) {
        if (outPath == null) return "recording";
        String name = outPath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
        return name.startsWith("event_") ? "surveillance" : "recording";
    }

    // ---- Public geo accessors (used by SurveillanceEngineGpu when it
    //      writes the segment metadata sidecar). All return sentinel values
    //      / -1 / 0 when no fix was captured; the writer omits the geo block
    //      in that case rather than emitting (0,0). -----------------------

    public double getStartGeoLat() { return startGeoLat; }
    public double getStartGeoLng() { return startGeoLng; }
    public float  getStartGeoAccuracy() { return startGeoAccuracy; }
    public long   getStartGeoAgeMs() { return startGeoAgeMs; }
    public long   getStartGeoCapturedAtMs() { return startGeoCapturedAtMs; }
    public boolean hasStartGeo() {
        return !Double.isNaN(startGeoLat) && !Double.isNaN(startGeoLng);
    }

    // Closed-segment geo (set by rotateSegmentLocked just before the active
    // fields are refreshed). The engine's segment listener reads these to
    // populate the OUTGOING segment's sidecar.
    public double getClosedStartGeoLat() { return closedStartGeoLat; }
    public double getClosedStartGeoLng() { return closedStartGeoLng; }
    public float  getClosedStartGeoAccuracy() { return closedStartGeoAccuracy; }
    public long   getClosedStartGeoAgeMs() { return closedStartGeoAgeMs; }
    public long   getClosedStartGeoCapturedAtMs() { return closedStartGeoCapturedAtMs; }
    public boolean hasClosedStartGeo() {
        return !Double.isNaN(closedStartGeoLat) && !Double.isNaN(closedStartGeoLng);
    }
    
    /**
     * Stops recording immediately or schedules post-record stop.
     *
     * <p>Held under {@code startStopLock} for symmetry with
     * {@link #triggerEventRecording}: a start cannot race a stop. The check
     * outside the lock is a cheap volatile read so callers don't pay the lock
     * cost when there's nothing to stop. The check inside the lock is the
     * authoritative one.
     *
     * @param immediate If true, stops immediately. If false, does nothing (timeout handled by caller)
     * @param postRecordDurationMs Post-record duration (ignored, kept for API compatibility)
     */
    public void stopEventRecording(boolean immediate, long postRecordDurationMs) {
        if (!isWritingToFile) {
            return;
        }
        synchronized (startStopLock) {
            if (!isWritingToFile) {
                // Another thread already finalised between the volatile read
                // above and our acquisition of the lock — nothing to do.
                return;
            }
            if (immediate) {
                closeEventRecording();
            }
            // Note: Post-record timeout is now handled by SurveillanceEngineGpu
            // The encoder just writes frames until explicitly told to stop
        }
    }
    
    /**
     * Closes the current event recording and finalizes the file.
     */
    private void closeEventRecording() {
        // CRITICAL FIX: Do NOT set isWritingToFile=false yet!
        // The drainer thread checks isWritingToFile to decide whether to write
        // frames to the muxer. Setting it false first causes the drainer to
        // dequeue frames from the encoder but SKIP writing them — losing the
        // last segment's frames on shutdown.
        //
        // Correct order:
        //   1. Wait for any in-flight rotation finalizers (audit Finding R1).
        //   2. Stop drainer thread (waits for current drain cycle to finish)
        //   3. Do one final synchronous drain WITH isWritingToFile still true
        //   4. THEN set isWritingToFile=false and close the muxer
        //
        // Step 1 prevents a finalizer from racing this close path: a rapid
        // stop within ~150 ms of a rotation tick used to fire onFileSaved
        // for the previous segment AFTER the close path had already torn
        // down the active recording, leaving cleanup in the wrong state.
        // 2-second budget is generous; finalizer should complete in <500 ms.
        if (!waitForFinalizers(2_000)) {
            logger.warn("closeEventRecording proceeding with finalizers still in flight");
        }

        recording = false;
        
        // Step 1: Stop drainer thread BEFORE touching the muxer.
        // The drainer may be in the middle of muxer.writeSampleData() — 
        // calling muxer.stop() concurrently corrupts the MP4 (broken moov atom).
        stopDrainerThread();
        
        // Step 2: Final synchronous drain — flush any frames still queued in
        // the encoder's output buffer. isWritingToFile is still true so these
        // frames WILL be written to the muxer.
        // FIX: Drain in a loop until the encoder is truly empty. A single call
        // to drainEncoderInternal() may not get all frames if the encoder is still
        // processing the last few input buffers. Loop with a short sleep to give
        // the hardware encoder time to finish encoding in-flight frames.
        try {
            for (int drainPass = 0; drainPass < 5; drainPass++) {
                int framesBefore = recordedFrames;
                drainEncoderInternal();
                int framesWritten = recordedFrames - framesBefore;
                if (framesWritten == 0 && drainPass > 0) {
                    break;  // Encoder is empty
                }
                if (framesWritten > 0 && drainPass < 4) {
                    // More frames were available — give encoder a moment to finish any in-flight
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            logger.warn("Final drain before close failed: " + e.getMessage());
        }
        
        // Step 3 + 4: under muxerLock, flush remaining queued packets into the
        // still-live muxer, then stop+release. Tracking stopOk lets us refuse
        // to rename a file whose moov was never written — that file would be
        // sized, named .mp4, and unplayable.
        boolean stopOk = false;
        synchronized (muxerLock) {
            MuxerPacket packet;
            int flushed = 0;
            while ((packet = muxerWriteQueue.poll()) != null) {
                if (muxerStarted && muxer != null) {
                    try {
                        packet.rewindForWrite();
                        if (packet.trackKind == TRACK_KIND_AUDIO) {
                            if (audioTrackIndex >= 0) {
                                writeRebasedAudio(muxer, audioTrackIndex,
                                    packet.data, packet.info);
                            }
                        } else {
                            writeRebased(muxer, trackIndex, packet.data, packet.info);
                            // firstFramePtsUs/lastFramePtsUs are tracked inside
                            // writeRebased on the rebased timeline.
                            recordedFrames++;
                            lastDiskWrittenMs = System.currentTimeMillis();
                        }
                        flushed++;
                    } catch (Exception e) {
                        logger.warn("Final flush write error: " + e.getMessage());
                        writerAbortedCorrupt = true;
                        releaseMuxerPacket(packet);
                        break;
                    }
                }
                releaseMuxerPacket(packet);
            }
            if (flushed > 0) {
                logger.info("Final muxer queue flush: " + flushed + " frames written");
            }

            // No more writers can race us now — flag the writer state OFF before
            // touching muxer.stop(). isWritingToFile is also cleared under the
            // lock so the upcoming format-change handler can't reopen the muxer.
            awaitLiveMuxerKeyframe = false;
            pendingAudioPreRecord = null;
            isWritingToFile = false;

            // Stop muxer (may throw if no frames were written, or if the
            // underlying file descriptor was severed by an SD-card unmount).
            try {
                if (muxerStarted && muxer != null) {
                    muxer.stop();
                    stopOk = true;
                }
            } catch (Exception e) {
                logger.warn("Muxer stop error (may have had no frames): " + e.getMessage());
            } finally {
                muxerStarted = false;
            }

            try {
                if (muxer != null) {
                    muxer.release();
                }
            } catch (Exception e) {
                logger.warn("Muxer release error: " + e.getMessage());
            } finally {
                muxer = null;
                trackIndex = -1;
                audioTrackIndex = -1;
            }
        }

        // SOTA: Restart the drainer NOW — before the synchronous rename /
        // onFileSaved / Telegram dispatch below. The encoder is still alive and
        // the GL thread is still calling eglSwapBuffers every frame; without a
        // live drainer, the encoder output queue fills, then the input queue
        // fills, then eglSwapBuffers blocks for the entire duration of the
        // post-stop housekeeping (observed: 76 ms = 255 ms mosaic+swap stage
        // spike on the GL thread).
        //
        // Safe ordering: muxer is fully stopped+released, isWritingToFile is
        // false under muxerLock, and writeSampleData paths gate on those, so
        // the freshly-started drainer can only feed the pre-record circular
        // buffer + streaming until the next event triggers a new muxer.
        startDrainerThread();

        // Rename temp to final, quarantine if broken, or delete if empty.
        // SOTA: never promote a tempFile to a final .mp4 unless the muxer
        // actually finalized — that's the single rule that prevents the
        // "60 MB file that won't play" symptom.
        boolean recordingBroken = !stopOk || writerAbortedCorrupt;
        if (tempFile != null && tempFile.exists()) {
            if (!recordingBroken && recordedFrames > 0 && tempFile.length() > 1024) {
                File finalFile = new File(outputPath);
                if (tempFile.renameTo(finalFile)) {
                    // Use actual PTS range for accurate duration (not recordedFrames/fps
                    // which is misleading when pre-record frames are included)
                    float durationSec = (firstFramePtsUs >= 0 && lastFramePtsUs > firstFramePtsUs)
                            ? (lastFramePtsUs - firstFramePtsUs) / 1_000_000.0f
                            : recordedFrames / (float) fps;
                    lastFinalizedDurationSec = Math.max(0, Math.round(durationSec));
                    logger.info(String.format("Event saved: %s (segment %d, %d frames, %.1f sec, %d KB, codec=%s, bitrate=%d Mbps)",
                            finalFile.getName(), segmentNumber, recordedFrames, durationSec, finalFile.length() / 1024,
                            codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                            bitrate / 1_000_000));

                    // Make file visible to events page and UI app
                    try {
                        com.overdrive.app.storage.StorageManager.getInstance().onFileSaved(finalFile);
                    } catch (Exception e) {
                        logger.warn("onFileSaved error: " + e.getMessage());
                    }

                    // Eagerly seed the H2 index so a /api/recordings call
                    // immediately after stop sees the new row instead of
                    // waiting on FileObserver (which can drop on FUSE-mounted
                    // SD cards). upsert is idempotent — sidecar write below
                    // races with this and the later sidecar-write hook will
                    // re-upsert with full metadata.
                    try {
                        com.overdrive.app.server.RecordingsIndex.getInstance().upsert(finalFile);
                    } catch (Throwable e) {
                        logger.warn("Index upsert failed for " + finalFile.getName() + ": " + e.getMessage());
                    }

                    // Telegram auto video-upload. Surveillance (event_*.mp4)
                    // and explicitly SURVEILLANCE_GATED OEM mirrors are
                    // DELIBERATELY excluded here: those clips are sent from
                    // SurveillanceEngineGpu.sendFinalTelegramNotification, which
                    // is the only place that knows the event's peak severity and
                    // therefore the only place that can honour the per-tier
                    // Telegram toggles (NOTICE/ALERT/CRITICAL). Sending from here
                    // too would bypass that gate — the "NOTICE muted but video
                    // still arrives" bug — and double-send. Ordinary dashcam
                    // (cam_*/dvr_*) and proximity clips have no severity concept,
                    // so they keep the simple videoUploads-only auto-send.
                    if (videoUploadPolicy.shouldAutoUpload(finalFile.getName())) {
                        try {
                            TelegramNotifier.notifyVideoRecorded(
                                    finalFile.getAbsolutePath(), null, (int) durationSec);
                        } catch (Exception e) {
                            logger.warn("Failed to emit video notification: " + e.getMessage());
                        }
                    }

                    // Geo sidecar for non-sentry flows (cam_*, proximity_*).
                    // Sentry events (event_*.mp4) use the richer path
                    // through SurveillanceEngineGpu.scheduleSegmentMetadataFlush
                    // which produces the v3 sidecar with actors/hero +
                    // geo. Dashcam + proximity recordings have no actor
                    // tracking, so they get a lighter sidecar covering
                    // only the geo block + SRT location prefix. Same
                    // submission discipline (off-thread executor inside
                    // LocationSidecarWriter), so this never blocks the
                    // recorder hot path.
                    try {
                        String flow = inferGeocodingFlow(finalFile.getName());
                        if (!"surveillance".equals(flow)) {
                            com.overdrive.app.geo.GeoSnapshot startGeo;
                            if (hasStartGeo()) {
                                startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                        startGeoLat, startGeoLng,
                                        startGeoAccuracy, startGeoAgeMs,
                                        startGeoCapturedAtMs, 0L);
                            } else {
                                startGeo = com.overdrive.app.geo.GeoSnapshot.empty();
                            }
                            com.overdrive.app.geo.LocationSidecarWriter
                                    .getInstance()
                                    .submit(finalFile, flow, startGeo);
                        }
                    } catch (Throwable e) {
                        logger.warn("Geo sidecar submit failed: " + e.getMessage());
                    }
                } else {
                    logger.error("Failed to rename temp file — deleting orphan");
                    tempFile.delete();
                }
            } else if (recordingBroken) {
                // Quarantine: keep evidence under a sidecar extension so the
                // recordings UI's *.mp4 listing doesn't pick it up. An
                // operator can still find it on disk for diagnostics.
                File broken = new File(outputPath + ".broken");
                if (!tempFile.renameTo(broken)) {
                    logger.warn("Quarantine rename failed; deleting broken tmp: " + tempFile.getName());
                    tempFile.delete();
                } else {
                    logger.warn("Quarantined broken recording (stopOk=" + stopOk
                            + ", writerAborted=" + writerAbortedCorrupt
                            + ", " + (broken.length() / 1024) + " KB): " + broken.getName());
                }
            } else {
                // Empty / sub-1KB recording — drop it silently.
                logger.warn("Deleting empty/corrupt temp file: " + tempFile.getName() +
                        " (frames=" + recordedFrames + ", size=" + tempFile.length() + ")");
                tempFile.delete();
            }

            // Every FAILURE branch above unlinks (or renames to .broken) a
            // *.mp4.tmp that StorageManager counts toward the category's reported
            // size (partialExtensionsForCategory). Only the SUCCESS branch reaches
            // onFileSaved, which is what normally invalidates the reporting cache —
            // so without this an aborted segment's bytes (tens to hundreds of MB)
            // stayed in the storage card's figure until some unrelated mutation.
            // Cheap and idempotent, so it runs for the success path too.
            try {
                com.overdrive.app.storage.StorageManager.getInstance()
                        .invalidateCategorySizeCache(null);
            } catch (Throwable ignored) {
                // StorageManager may not be initialised in every process.
            }
        }

        // Reset state
        recordedFrames = 0;
        firstFramePtsUs = -1;
        lastFramePtsUs = -1;
        ptsOriginUs = -1;
        lastSourcePtsUs = -1;
        lastAudioPtsUs = -1L;
        segmentStartTime = 0;
        segmentNumber = 0;
        segmentBasePath = null;

        // Drainer was already restarted above (right after muxer release) so
        // the GL thread saw zero post-stop backpressure. No-op call here would
        // log "Drainer thread already running" — just rely on the early start.

        if (fileClosedCallback != null) {
            fileClosedCallback.run();
        }
    }
    
    /**
     * Legacy method for compatibility.
     */
    public void stopRecording() {
        stopEventRecording(true, 0);
    }
    
    /**
     * Sets callback for when file is closed.
     * 
     * @param callback Callback to run when file closes
     */
    public void setFileClosedCallback(Runnable callback) {
        this.fileClosedCallback = callback;
    }
    
    /**
     * Requests a sync frame (I-frame) immediately.
     * 
     * Used when an event is detected to ensure clean playback start.
     */
    public void requestSyncFrame() {
        if (encoder != null) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(params);
                logger.debug( "Sync frame requested");
            } catch (Exception e) {
                logger.error( "Failed to request sync frame", e);
            }
        }
    }
    
    /**
     * Change the encoder bitrate at runtime via MediaCodec.setParameters.
     *
     * <p>The byte-ring format is bitrate-agnostic, but its fixed arena still
     * needs enough bytes for the configured time window. An inline bitrate
     * increase therefore reapplies the retention budget and may replace an
     * undersized arena.
     *
     * @param newBitrate New bitrate in bps
     */
    public void setBitrate(int newBitrate) {
        if (encoder != null && newBitrate != bitrate) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate);
                encoder.setParameters(params);
                this.bitrate = newBitrate;
                applyPreRecordRetentionWindow(effectivePreRecordRetentionSeconds());
                logger.info("Bitrate changed to: " + (newBitrate / 1_000_000) + " Mbps");
            } catch (Exception e) {
                logger.error("Failed to change bitrate", e);
            }
        }
    }

    /**
     * Update the encoder's tracked FPS.
     *
     * <p>Android {@code MediaCodec} does NOT support changing
     * {@code KEY_FRAME_RATE} at runtime — it's a configure-time hint for
     * rate control. This method just updates the cached value so internal
     * code reading {@link #getFps} sees the new target. Rate control
     * recalibrates to the actual surface delivery rate over a few seconds.
     *
     * <p>The byte-ring pre-record buffer is fps-agnostic, so no buffer
     * change is needed.
     */
    public void setTargetFps(int newFps) {
        if (newFps == this.fps) return;
        int oldFps = this.fps;
        this.fps = newFps;
        logger.info("Encoder FPS tracking updated to: " + newFps
            + " (was " + oldFps + "; KEY_FRAME_RATE remains unchanged — Android MediaCodec limitation)");
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }
    
    /**
     * Checks if currently writing to file.
     * 
     * @return true if actively writing to file, false otherwise
     */
    public boolean isWritingToFile() {
        return isWritingToFile;
    }

    /**
     * FIX (audit R5): RMM's wedge ticker calls this through
     * GpuSurveillancePipeline.getLastEncodedFrameMs() to spot encoder hangs
     * that don't surface in isRunning()/isRecording(). Returns 0 if no
     * coded frame has been dequeued yet (e.g. before format-available);
     * callers must treat 0 as "no signal yet" and skip the wedge check.
     */
    public long getLastEncodedFrameMs() {
        return lastEncodedFrameMs;
    }

    /**
     * Monotonic (elapsedRealtime) variant of {@link #getLastEncodedFrameMs()}
     * for freshness checks that must survive wall-clock steps. 0 = no coded
     * frame dequeued yet.
     */
    public long getLastEncodedFrameElapsedMs() {
        return lastEncodedFrameElapsedMs;
    }

    /**
     * @return wall-clock ms (System.currentTimeMillis) of the last VIDEO
     *         sample actually written to the muxer (disk). Seeded at segment
     *         open/rotation. 0 = never written yet (no muxer has opened).
     *         RMM's wedge ticker reads this via
     *         GpuSurveillancePipeline.getLastDiskWrittenMs() to detect the
     *         "muxer open / encoder alive but nothing landing on disk" state
     *         that getLastEncodedFrameMs() structurally cannot see (the
     *         encoder always runs to feed the pre-record ring, so its
     *         timestamp advances even when no file is being written).
     */
    public long getLastDiskWrittenMs() {
        return lastDiskWrittenMs;
    }

    /**
     * @return true if the pre-record byte ring is allocated and active for
     *         this encoder. False if the encoder is stream-only or if the
     *         byte-ring allocation failed (OOM at boot — see init()'s
     *         try/catch around {@code new H264ByteRingBuffer}). Surfaced
     *         via /api/status so the UI can warn about a degraded session.
     */
    public boolean isPreRecordEnabled() {
        return usePreRecordBuffer && preRecordBuffer != null;
    }

    /**
     * @return true if init()'s byte-ring allocation threw OOM and pre-record
     *         is disabled for this session. Distinct from a stream-only
     *         encoder which deliberately skips pre-record.
     */
    public boolean isPreRecordAllocFailed() {
        return preRecordAllocFailed;
    }

    /**
     * Diagnostic accessor for the pre-record buffer. Returns null when
     * pre-record is disabled or the buffer wasn't allocated. Consumers
     * MUST treat this as read-only stats — the buffer's lifecycle is
     * owned by the encoder.
     */
    public H264ByteRingBuffer getPreRecordBuffer() {
        return preRecordBuffer;
    }

    /** Latest encoded PTS retained for instant replay, or Long.MIN_VALUE. */
    public long getLatestPreRecordPtsUs() {
        H264ByteRingBuffer ring = preRecordBuffer;
        return ring != null ? ring.getLatestPtsUs() : Long.MIN_VALUE;
    }

    /** Oldest encoded PTS retained for instant replay, or Long.MIN_VALUE. */
    public long getOldestPreRecordPtsUs() {
        H264ByteRingBuffer ring = preRecordBuffer;
        return ring != null ? ring.getOldestPtsUs() : Long.MIN_VALUE;
    }

    /** True while either event recording or manual replay owns the ring cursor. */
    public boolean isPreRecordFlushInProgress() {
        return flushInProgress || manualClipExportInProgress;
    }

    /**
     * Reserve the single encoded-history consumer as soon as a manual replay
     * key fires. Holding this lightweight flag during post-roll prevents a
     * later safety event from taking the range cursor; that event still starts
     * immediately from live frames.
     */
    public boolean tryReserveManualClip() {
        synchronized (startStopLock) {
            if (flushInProgress || manualClipExportInProgress) return false;
            manualClipExportInProgress = true;
            return true;
        }
    }

    /** Release a pending manual replay that never reached (or finished) export. */
    public void releaseManualClipReservation() {
        synchronized (startStopLock) {
            manualClipExportInProgress = false;
        }
    }

    /**
     * Remux a bounded interval from the encoded history into an independent MP4.
     * The active recording muxer is untouched. startStopLock is held only while
     * claiming the ring cursor; slow removable-storage I/O runs outside it so
     * lifecycle operations cannot be stalled for the duration of a 60s remux.
     */
    public boolean exportManualClip(File outputFile, long startPtsUs, long endPtsUs) {
        if (outputFile == null) return false;
        return exportManualClip(() -> outputFile, startPtsUs, endPtsUs);
    }

    public boolean exportManualClip(ManualClipOutputProvider outputProvider,
                                    long startPtsUs, long endPtsUs) {
        return exportManualClip(outputProvider, startPtsUs, endPtsUs, false);
    }

    /**
     * @param allowStartTruncation the caller already truncated the requested
     *        start to the ring's oldest PACKET ("save whatever is buffered").
     *        After a PTS-discontinuity wipe that oldest packet is usually a
     *        P-frame, so the decodable start (next IDR) can sit up to one GOP
     *        later — with this flag the export adopts the cursor's decodable
     *        start instead of refusing on the start-side coverage check. The
     *        end-side check still applies, and a clip whose decodable region
     *        would shrink below {@link #MANUAL_CLIP_MIN_TRUNCATED_SPAN_US} is
     *        still refused rather than emitted as a sliver.
     */
    public boolean exportManualClip(ManualClipOutputProvider outputProvider,
                                    long startPtsUs, long endPtsUs,
                                    boolean allowStartTruncation) {
        if (outputProvider == null || startPtsUs > endPtsUs) return false;

        final H264ByteRingBuffer.Cursor cursor;
        final MediaFormat videoFormat;
        boolean reservationAcquiredHere = false;
        synchronized (startStopLock) {
            if (flushInProgress) {
                logger.warn("Manual clip refused: event pre-record consumer is active");
                return false;
            }

            // requestClip normally reserves at key-down time so an event cannot
            // steal the cursor during post-roll. Keep direct callers safe by
            // acquiring the same reservation here when none exists.
            if (!manualClipExportInProgress) {
                manualClipExportInProgress = true;
                reservationAcquiredHere = true;
            }

            H264ByteRingBuffer ring = preRecordBuffer;
            videoFormat = savedFormat;
            if (ring == null || videoFormat == null || encoder == null) {
                if (reservationAcquiredHere) manualClipExportInProgress = false;
                return false;
            }

            cursor = ring.beginStrongFlushRange(startPtsUs, endPtsUs);
            if (cursor == null) {
                logger.warn("Manual clip refused: requested range has no decodable keyframe");
                if (reservationAcquiredHere) manualClipExportInProgress = false;
                return false;
            }

            // Never silently turn a requested 30/60-second replay into a
            // warmed-up or memory-truncated fragment. The start may be earlier
            // than requested because decoding must begin at the preceding IDR;
            // it must not be materially later. The end allows a few frame
            // intervals because the requested timestamp need not land exactly
            // on an encoded sample.
            final long coverageToleranceUs = Math.max(250_000L,
                    fps > 0 ? (3_000_000L / fps) : 250_000L);
            boolean startCovered =
                    cursor.getStartPtsUs() <= startPtsUs + coverageToleranceUs;
            if (!startCovered && allowStartTruncation) {
                // Truncated request: the caller pinned its start to the oldest
                // ring packet, which may be undecodable (P-frame head after a
                // discontinuity wipe). Accept the cursor's decodable start as
                // the effective start as long as a meaningful window remains.
                startCovered = cursor.getStartPtsUs()
                        <= endPtsUs - MANUAL_CLIP_MIN_TRUNCATED_SPAN_US;
                if (startCovered) {
                    logger.info("Manual clip start truncated to first decodable"
                            + " keyframe: " + cursor.getStartPtsUs()
                            + " (requested " + startPtsUs + ")");
                }
            }
            if (!startCovered || cursor.getEndPtsUs() < endPtsUs - coverageToleranceUs) {
                logger.warn("Manual clip refused: retained range does not cover request"
                        + " (have=" + cursor.getStartPtsUs() + ".." + cursor.getEndPtsUs()
                        + ", need=" + startPtsUs + ".." + endPtsUs + ")");
                cursor.close();
                if (reservationAcquiredHere) manualClipExportInProgress = false;
                return false;
            }

            manualClipExportInProgress = true;
        }

        try {
            // Snapshot immutable AAC packet references immediately after the
            // video pin. Slow storage resolution must not evict the first audio
            // seconds of a full 60-second replay from its independent ring.
            java.util.List<AacCircularBuffer.Packet> audioPackets =
                    aacRing.snapshotRange(cursor.getStartPtsUs(), cursor.getEndPtsUs());

            // Potentially slow StatFs/reaper/removable-storage work happens only
            // after the selected bytes are protected by the strong cursor.
            File outputFile;
            try {
                outputFile = outputProvider.createOutputFile();
            } catch (Throwable t) {
                logger.warn("Manual clip output resolution failed: " + t.getMessage());
                return false;
            }
            if (outputFile == null) return false;

            File temp = new File(outputFile.getAbsolutePath() + ".tmp");
            MediaMuxer clipMuxer = null;
            boolean muxerStartedLocal = false;
            boolean tempOwned = false;
            boolean stopOk = false;
            int videoFrames = 0;
            long firstVideoPtsUs = -1L;
            long lastVideoPtsUs = -1L;
            long lastSourceVideoPtsUs = -1L;
            long lastMuxedVideoPtsUs = -1L;

            try {
                File parent = temp.getParentFile();
                if (parent != null && !parent.exists()
                        && !parent.mkdirs() && !parent.exists()) {
                    return false;
                }
                // Never delete an unknown .tmp. Another writer may have won a
                // path race; replay_* normally makes that impossible, but the
                // refusal keeps this method non-destructive by construction.
                if (temp.exists() || outputFile.exists()) return false;

                clipMuxer = new MediaMuxer(temp.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                tempOwned = true;
                int videoTrack = clipMuxer.addTrack(videoFormat);
                int clipAudioTrack = -1;
                boolean audioOverlapsVideoRange = false;
                for (AacCircularBuffer.Packet packet : audioPackets) {
                    if (packet.ptsUs >= cursor.getStartPtsUs()
                            && packet.ptsUs <= cursor.getEndPtsUs()) {
                        audioOverlapsVideoRange = true;
                        break;
                    }
                }
                if (audioOverlapsVideoRange) {
                    clipAudioTrack = maybeAddAudioTrack(clipMuxer);
                }
                clipMuxer.start();
                muxerStartedLocal = true;

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                ByteBuffer packetBuffer = null;
                while (cursor.remaining() > 0) {
                    int nextSize = cursor.peekSize();
                    if (nextSize <= 0) break;
                    if (packetBuffer == null || packetBuffer.capacity() < nextSize) {
                        packetBuffer = ByteBuffer.allocateDirect(nextSize);
                    }
                    packetBuffer.clear();
                    if (!cursor.next(packetBuffer, info)) break;
                    packetBuffer.flip();

                    long sourcePtsUs = info.presentationTimeUs;
                    if (firstVideoPtsUs < 0L) firstVideoPtsUs = sourcePtsUs;
                    lastVideoPtsUs = sourcePtsUs;
                    long muxedPtsUs;
                    if (lastSourceVideoPtsUs < 0L) {
                        muxedPtsUs = 0L;
                    } else {
                        long sourceGapUs = sourcePtsUs - lastSourceVideoPtsUs;
                        if (sourceGapUs <= 0L
                                || sourceGapUs > MAX_PLAUSIBLE_INTERFRAME_GAP_US) {
                            long nominalFrameUs = fps > 0 ? 1_000_000L / fps : 33_333L;
                            muxedPtsUs = lastMuxedVideoPtsUs + nominalFrameUs;
                        } else {
                            muxedPtsUs = lastMuxedVideoPtsUs + sourceGapUs;
                        }
                    }
                    info.offset = 0;
                    info.presentationTimeUs = muxedPtsUs;
                    clipMuxer.writeSampleData(videoTrack, packetBuffer, info);
                    lastSourceVideoPtsUs = sourcePtsUs;
                    lastMuxedVideoPtsUs = muxedPtsUs;
                    videoFrames++;
                }

                boolean complete = !cursor.aborted() && cursor.remaining() == 0;
                if (complete && clipAudioTrack >= 0 && firstVideoPtsUs >= 0L) {
                    MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                    long lastWrittenAudioPtsUs = -1L;
                    for (AacCircularBuffer.Packet packet : audioPackets) {
                        if (packet.ptsUs < firstVideoPtsUs || packet.ptsUs > lastVideoPtsUs) continue;
                        long rebasedPtsUs = packet.ptsUs - firstVideoPtsUs;
                        if (rebasedPtsUs <= lastWrittenAudioPtsUs) {
                            rebasedPtsUs = lastWrittenAudioPtsUs + 1L;
                        }
                        ByteBuffer audioData = ByteBuffer.wrap(packet.data);
                        audioInfo.set(0, packet.data.length, rebasedPtsUs, 0);
                        clipMuxer.writeSampleData(clipAudioTrack, audioData, audioInfo);
                        lastWrittenAudioPtsUs = rebasedPtsUs;
                    }
                }

                if (complete && videoFrames > 0) {
                    clipMuxer.stop();
                    muxerStartedLocal = false;
                    stopOk = true;
                } else if (cursor.aborted()) {
                    logger.warn("Manual clip discarded: ring cursor was preempted");
                }
            } catch (Throwable t) {
                logger.warn("Manual clip mux failed: " + t.getMessage());
            } finally {
                if (clipMuxer != null) {
                    if (muxerStartedLocal) {
                        try { clipMuxer.stop(); } catch (Throwable ignored) {}
                    }
                    try { clipMuxer.release(); } catch (Throwable ignored) {}
                }
            }

            if (!stopOk || videoFrames <= 0 || !temp.exists() || temp.length() <= 1024L) {
                if (tempOwned && temp.exists() && !temp.delete()) {
                    logger.warn("Could not delete failed manual clip temp: " + temp.getName());
                }
                return false;
            }
            if (outputFile.exists()) {
                logger.warn("Could not finalize manual clip: destination appeared during export");
                if (tempOwned) temp.delete();
                return false;
            }
            if (!temp.renameTo(outputFile)) {
                logger.warn("Could not finalize manual clip: " + outputFile.getAbsolutePath());
                if (tempOwned) temp.delete();
                return false;
            }

            double durationSec = lastMuxedVideoPtsUs > 0L
                    ? lastMuxedVideoPtsUs / 1_000_000.0 : 0.0;
            logger.info(String.format(Locale.US,
                    "Manual clip exported: %s (%d frames, %.1fs, %.1fMB)",
                    outputFile.getName(), videoFrames, durationSec,
                    outputFile.length() / 1024.0 / 1024.0));
            return true;
        } finally {
            cursor.close();
            releaseManualClipReservation();
            // The three abort paths above each unlink a size-counted
            // <clip>.mp4.tmp; only the success path reaches onFileSaved (via
            // ManualClipService). Invalidating in the finally covers every exit
            // without duplicating the call at each return.
            try {
                com.overdrive.app.storage.StorageManager.getInstance()
                        .invalidateCategorySizeCache(null);
            } catch (Throwable ignored) {
                // StorageManager may not be initialised in every process.
            }
        }
    }

    /**
     * Gets the number of recorded frames.
     * 
     * @return Frame count
     */
    public int getRecordedFrames() {
        return recordedFrames;
    }
    
    /**
     * Get the actual duration of the pre-record buffer that was flushed.
     * This may be longer than the configured preRecordMs because the H.264
     * circular buffer starts from the nearest keyframe.
     */
    public long getActualPreRecordDurationMs() {
        return actualPreRecordDurationMs;
    }
    
    /**
     * Gets the current bitrate.
     * 
     * @return Bitrate in bps
     */
    public int getBitrate() {
        return bitrate;
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        // CRITICAL ORDERING: do NOT stop the drainer up-front. The previous
        // ordering (stopDrainer → stopRecording → encoder.release) was buggy:
        // closeEventRecording calls stopDrainerThread() AGAIN at line ~1157,
        // then RESTARTS the drainer at line ~1255 (so the encoder GL thread
        // doesn't backpressure on output-queue saturation during rename).
        // Coming back from that restart, release() then proceeds to call
        // encoder.stop() + encoder.release() — racing the freshly-started
        // drainer's dequeueOutputBuffer on a now-released codec, which logs
        // a transient ISE every shutdown.
        //
        // Correct ordering:
        //   1. Stop the active recording (drains the muxer cleanly,
        //      restarts drainer to keep GL thread responsive).
        //   2. Wait for finalizers.
        //   3. Stop the drainer permanently — set a "do not restart" flag
        //      first so any in-flight close path observes it.
        //   4. encoder.stop() + release().
        if (recording) {
            stopRecording();
        }
        // Suppress further drainer restarts — closeEventRecording already
        // restarted it; we want the FINAL stop to stick.
        drainerRestartSuppressed = true;
        stopDrainerThread();

        // Wait for any in-flight rotation finalizers AFTER stopRecording so
        // the close path's rename has already finished but rotation finalizers
        // (which run on independent threads) are joined. Otherwise the
        // pipeline tear-down can outpace a rename + onFileSaved that's still
        // in flight, leaving the StorageManager probe with a dangling encoder
        // ref. 3-second budget covers worst-case stop+release+rename.
        if (!waitForFinalizers(3_000)) {
            logger.warn("release() proceeding with finalizers still in flight");
        }
        
        if (encoder != null) {
            // Note: by the time we reach release(), stopRecording() above
            // has already finalized any active recording (drained and stopped
            // the muxer). Final-frame-loss is handled there. We just stop
            // and release the codec.
            try {
                encoder.stop();
            } catch (Exception e) {
                logger.error( "Error stopping encoder", e);
            }

            try {
                encoder.release();
            } catch (Exception e) {
                logger.error( "Error releasing encoder", e);
            }

            encoder = null;
        }
        
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        // Drop our reference to the byte ring. Two paths:
        //
        //   (a) shared (default, pano case). We deliberately do NOT clear()
        //       — the next encoder's init() will clear() the shared buffer
        //       at the right moment (under bufferLock, with the new encoder's
        //       parameters known). Clearing here on every release had two
        //       harmful effects: bitrate-only reinit wiped the still-valid
        //       pre-record window, and shutdown mid-flush left an orphaned
        //       cursor pin. The init reuse path is the canonical boundary.
        //
        //   (b) per-instance (OEM case). The arena is owned by THIS encoder
        //       — no other consumer references it. Drop the reference so
        //       the JVM Cleaner can reclaim the direct ByteBuffer at the
        //       next GC. clear() isn't needed because nothing else is going
        //       to read from it.
        preRecordBuffer = null;
        preRecordBufferIsInstance = false;

        // Drain the per-instance muxer packet pools. Without this drain,
        // a bitrate-only reinit (release → new encoder) leaves the old
        // pools holding their direct ByteBuffers until the JVM Cleaner
        // reclaims them at next GC, while the new encoder's pools grow
        // in parallel. Steady-state native footprint is unchanged; this
        // just reclaims the peak-memory blip during the reinit window.
        // Setting buffer fields to null lets the Cleaner reclaim each
        // direct ByteBuffer at the next GC instead of waiting for the
        // entire encoder instance to become unreachable.
        drainPool(muxerPacketPoolMicro, muxerPacketPoolMicroSize);
        drainPool(muxerPacketPoolSmall, muxerPacketPoolSmallSize);
        drainPool(muxerPacketPoolLarge, muxerPacketPoolLargeSize);

        logger.info( "Released");
    }

    private static void drainPool(java.util.concurrent.ConcurrentLinkedDeque<MuxerPacket> pool,
                                  java.util.concurrent.atomic.AtomicInteger size) {
        MuxerPacket p;
        while ((p = pool.poll()) != null) {
            p.data = null;
        }
        size.set(0);
    }
    
    // ==================== SOTA: Background Drainer Thread ====================
    
    /**
     * Starts the background drainer thread.
     * This moves SD card I/O off the GL thread to prevent freezes.
     */
    private void startDrainerThread() {
        synchronized (drainerLock) {
        if (drainerRunning) {
            logger.warn("Drainer thread already running");
            return;
        }
        if (drainerRestartSuppressed) {
            logger.info("Drainer restart suppressed (release in progress)");
            return;
        }
        if (drainerSuppressedForCameraClose) {
            logger.info("Drainer restart suppressed (camera close in progress)");
            return;
        }

        drainerRunning = true;
        drainerThread = new Thread(() -> {
            // Audit-driven: drainer is on the realtime-critical path. If it
            // gets scheduled out for >50ms, the encoder's output pool fills
            // and eglSwap on the GL thread backpressures. Bump Linux nice
            // priority to match the disk writer (FOREGROUND, -2). Without
            // this, drainer ran at default nice 0 and could be preempted by
            // any other normal-priority work.
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_FOREGROUND);
                logger.debug("Drainer thread nice set to FOREGROUND");
            } catch (Throwable t) {
                logger.warn("Drainer thread priority bump failed: " + t.getMessage());
            }
            logger.info("Encoder drainer thread started");
            // SOTA Tier-A: replace poll+sleep with the encoder's native
            // blocking dequeue. Previously we did dequeueOutputBuffer(timeout=0)
            // + Thread.sleep(DRAIN_INTERVAL_MS=16) which added up to 16 ms of
            // post-encode latency between every drain tick. At 15 fps the
            // per-frame budget is 66 ms, so 16 ms idle was ~24% of budget; on
            // bursty pre-record flushes the encoder's output queue saturated
            // before the next drain woke up, back-pressuring the input
            // surface and producing the 207ms "mosaic+swap" outliers
            // observed in field logs. The blocking dequeue inside
            // drainEncoderInternal() now wakes us the instant a packet is
            // ready — no idle time, no polling.
            //
            // FIX H1: adaptive empty-drain backoff. The 10 ms blocking
            // dequeue inside drainEncoderInternal() ALREADY paces idle
            // ticks; an unconditional 4 ms sleep on top of it just stacks
            // wakeups (~250 wakeups/s when the encoder is idle, e.g. when
            // recording is paused but the pipeline is still running). When
            // frames flow we want zero added sleep so the next iteration's
            // 10 ms blocking dequeue is the only pacing knob. When no
            // frames came out we add an exponentially backed off sleep up
            // to 16 ms — at idle we converge to ~50 wakeups/s instead of
            // ~250, halving CPU at idle while preserving sub-frame
            // responsiveness when the encoder restarts producing.
            long emptyDrainSleepMs = 4L;
            final long minEmptySleepMs = 4L;
            final long maxEmptySleepMs = 16L;
            while (drainerRunning) {
                try {
                    int drained = drainEncoderInternal();
                    if (drained > 0) {
                        // Real work flowed — reset backoff and skip sleep.
                        // Next iteration's 10 ms blocking dequeue paces us.
                        emptyDrainSleepMs = minEmptySleepMs;
                    } else {
                        // No frames — back off. The 10 ms blocking dequeue
                        // already absorbed up to 10 ms idle, so adding
                        // (4..16) ms here keeps the upper-bound responsiveness
                        // at ≈26 ms — well under one frame at 30 fps.
                        Thread.sleep(emptyDrainSleepMs);
                        emptyDrainSleepMs = Math.min(maxEmptySleepMs,
                                emptyDrainSleepMs * 2L);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    // Catch Throwable, NOT just Exception. The flush block
                    // re-throws Errors after logging+cleanup; if we only
                    // catch Exception, an OOMError silently kills this
                    // thread — drainerRunning stays true, encoder output
                    // queue saturates, eglSwapBuffers stalls, the GL thread
                    // freezes, and there is no daemon-level watchdog to
                    // notice. We'd rather burn a log line and keep
                    // draining: the next iteration picks up where we left
                    // off, the encoder pipeline keeps moving, and the
                    // (recoverable) recording continues. If the Error is
                    // genuinely fatal (e.g. native crash), the JVM will
                    // tear down regardless — we lose nothing by trying.
                    logger.error("Drainer error (caught Throwable): " + t.getMessage());
                    if (t instanceof Error) {
                        // Log a stack trace for post-mortem.
                        try {
                            java.io.StringWriter sw = new java.io.StringWriter();
                            t.printStackTrace(new java.io.PrintWriter(sw));
                            logger.error("Drainer Error trace: " + sw.toString());
                        } catch (Throwable ignored) {}
                    }
                    // Brief backoff so a hot loop of repeated Errors doesn't
                    // pin the CPU. 50ms is a few frames at worst.
                    try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                }
            }
            logger.info("Encoder drainer thread stopped");
        }, "GpuEncoderDrainer");

        drainerThread.setPriority(Thread.NORM_PRIORITY);
        drainerThread.start();

        // Start disk writer thread (handles muxer I/O separately from encoder dequeue)
        startDiskWriterThread();
        } // end synchronized (drainerLock)
    }

    /**
     * Stops the background drainer thread.
     */
    private void stopDrainerThread() {
        synchronized (drainerLock) {
            drainerRunning = false;
            if (drainerThread != null) {
                try {
                    drainerThread.interrupt();
                    // SOTA: 2 s join matches the disk writer's join. The drainer can
                    // be inside a single drainEncoderInternal() pass that takes
                    // 100+ ms under SD-card pressure; the old 500 ms ceiling let
                    // the close path move on while the drainer was still pushing
                    // packets to the queue, racing the muxer.stop() call.
                    drainerThread.join(2000);
                } catch (InterruptedException e) {
                    // Ignore
                }
                drainerThread = null;
            }

            // Stop disk writer after drainer (drainer may still be pushing to the queue)
            stopDiskWriterThread();
        }
    }
    
    /**
     * FORTIFY FIX: Stops the drainer thread before camera close.
     * 
     * The drainer thread calls MediaCodec.dequeueOutputBuffer() which internally
     * accesses the camera's SurfaceTexture buffer queue. If the camera is closed
     * (destroying the native mutex) while the drainer is mid-dequeue, we get:
     *   FORTIFY: pthread_mutex_lock called on a destroyed mutex
     * 
     * This method stops the drainer and waits for it to fully exit before returning,
     * making it safe to close the camera afterwards.
     * 
     * Call restartDrainerAfterCameraClose() after the camera is reopened.
     */
    public void stopDrainerForCameraClose() {
        synchronized (drainerLock) {
            logger.info("Stopping drainer for camera close...");
            drainerSuppressedForCameraClose = true;
            drainerRunning = false;
            if (drainerThread != null) {
                try {
                    drainerThread.interrupt();
                    drainerThread.join(1000);  // Wait up to 1 second for clean exit
                    if (drainerThread.isAlive()) {
                        logger.warn("Drainer thread still alive after 1s — proceeding anyway");
                    }
                } catch (InterruptedException e) {
                    // Ignore
                }
                drainerThread = null;
            }
            logger.info("Drainer stopped for camera close");
        }
    }
    
    /**
     * Restarts the drainer thread after camera has been reopened.
     * Call this after startCamera() succeeds.
     */
    public void restartDrainerAfterCameraClose() {
        // Defensive: a prior release() may have left drainerRestartSuppressed
        // true. The camera-close-then-reopen path is a normal-lifecycle event
        // that must NOT be silently no-op'd. Only release() ↔ a new encoder
        // instance is supposed to permanently stop the drainer.
        drainerRestartSuppressed = false;
        drainerSuppressedForCameraClose = false;
        if (!drainerRunning) {
            startDrainerThread();
            logger.info("Drainer restarted after camera reopen");
        }
    }
    
    // ==================== SOTA: Disk Writer Thread ====================
    
    /**
     * Starts the disk writer thread that polls the muxer write queue
     * and writes to the SD card. This decouples SD card I/O from the
     * encoder dequeue loop, preventing I/O stalls from dropping frames.
     */
    private void startDiskWriterThread() {
        if (diskWriterRunning) return;

        diskWriterRunning = true;
        // Each disk writer instance starts with a clean abort flag. The flag is
        // only set when this writer hits the unrecoverable failure threshold; the
        // close/rotate paths read it to decide whether to keep or quarantine the
        // current tempFile.
        writerAbortedCorrupt = false;
        writerAbortedErrorMessage = null;
        // SD-unmount detection: if writes start failing repeatedly, the underlying
        // file descriptor is dead (typical when BYD/Android unmounts the SD card
        // mid-recording). The MP4's moov atom is written only on stopRecording, so
        // continuing to drain into a broken FD produces an unrecoverable corrupt
        // file. Track consecutive write failures and abort the recording cleanly
        // once we cross a threshold — at least the MP4 prefix on disk has the
        // partial frames already written, and the user gets a clear log instead
        // of a silent corruption.
        final int[] consecutiveWriteFailures = {0};
        final int writeFailureAbortThreshold = 5;
        diskWriterThread = new Thread(() -> {
            // Disk writer is on the realtime-critical path. If it falls
            // behind, the muxer write queue saturates and eglSwap stalls
            // the GL thread → freeze+skip in the encoded MP4.
            //
            // Audit P2: THREAD_PRIORITY_DISPLAY (-4) is @hide and SecurityException
            // for non-system apps on most Android builds. THREAD_PRIORITY_FOREGROUND
            // (-2) is the public, non-restricted equivalent that nudges the
            // scheduler in our favor without requiring system-app status. The
            // achieved gap vs. cleanup threads (background, +10) is still ~12
            // nice points, which is what actually matters.
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_FOREGROUND);
                logger.debug("Disk writer thread nice set to FOREGROUND");
            } catch (Throwable t) {
                logger.warn("Disk writer thread priority bump failed: " + t.getMessage()
                    + " (continuing at default)");
            }
            logger.info("Disk writer thread started");
            // SOTA: take(50ms) instead of poll()+sleep(4ms). The blocking take
            // wakes the writer the instant the drainer pushes a packet, so the
            // queue stays shallow and the encoder's input surface never fills.
            // The 50 ms timeout is just a periodic liveness check so the loop
            // can observe diskWriterRunning=false during teardown.
            while (diskWriterRunning || !muxerWriteQueue.isEmpty()) {
                MuxerPacket packet = null;
                try {
                    packet = muxerWriteQueue.pollFirst(50,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (packet == null) continue;
                    // SOTA: serialize against rotateSegment / closeEventRecording.
                    // Without this lock, a concurrent muxer.stop() corrupts the
                    // moov atom and produces a sized-but-unplayable .mp4.
                    synchronized (muxerLock) {
                        if (muxerStarted && muxer != null) {
                            // Route by track-kind. Video uses the canonical
                            // writeRebased path so its PTS tracking and
                            // duration computation stay intact. Audio shares
                            // ptsOriginUs with video so A/V remain aligned;
                            // we route via writeRebasedAudio which seeds the
                            // origin if the very first packet of the segment
                            // happens to be audio (rare but possible).
                            packet.rewindForWrite();
                            if (packet.trackKind == TRACK_KIND_AUDIO) {
                                if (audioTrackIndex >= 0) {
                                    writeRebasedAudio(muxer, audioTrackIndex,
                                        packet.data, packet.info);
                                }
                                // else: audio not provisioned for this segment
                                // (toggle off, CSD missing) — packet dropped
                                // silently. The encoder upstream is allowed
                                // to keep producing frames; we just don't
                                // mux them.
                            } else {
                                writeRebased(muxer, trackIndex,
                                    packet.data, packet.info);
                                // PTS tracking handled inside writeRebased.
                                recordedFrames++;
                                // FIX (false-GREEN): a VIDEO sample actually
                                // reached the muxer (disk). This is the only
                                // honest "bytes are landing" signal — see the
                                // field doc. RMM's wedge ticker reads it to
                                // catch "muxer open but nothing written."
                                lastDiskWrittenMs = System.currentTimeMillis();
                            }
                            consecutiveWriteFailures[0] = 0;
                        }
                    }
                    releaseMuxerPacket(packet);
                    packet = null;
                } catch (InterruptedException e) {
                    // Drain remaining packets before exiting. We deliberately do
                    // NOT write here: by the time the writer is interrupted, the
                    // close/rotate path is about to (or has already) called
                    // muxer.stop(), so any further writeSampleData would corrupt
                    // the moov. The close path drains the queue itself under the
                    // lock before stopping the muxer.
                    if (packet != null) releaseMuxerPacket(packet);
                    break;
                } catch (Exception e) {
                    if (packet != null) releaseMuxerPacket(packet);
                    consecutiveWriteFailures[0]++;
                    logger.error("Disk writer error (#" + consecutiveWriteFailures[0]
                        + "): " + e.getMessage());
                    if (consecutiveWriteFailures[0] >= writeFailureAbortThreshold) {
                        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        logger.error("Aborting recording: " + writeFailureAbortThreshold
                            + " consecutive write failures (likely SD card unmounted). "
                            + "Partial file at " + (tempFile != null ? tempFile.getAbsolutePath() : "unknown")
                            + " will not be playable.");
                        // Mark the current segment corrupt so the close/rotate
                        // path quarantines it rather than promoting tempFile to
                        // outputPath. The user must never see a final .mp4
                        // filename for a file whose moov was never written.
                        writerAbortedCorrupt = true;
                        writerAbortedErrorMessage = reason;
                        // Notify any registered listener — OEM uses this to flip
                        // its `recording` flag and surface lastWriteError into
                        // UCM so the UI status badge transitions from "recording"
                        // to "errored" immediately, instead of lying until the
                        // next user action.
                        try {
                            WriterAbortListener cb = writerAbortListener;
                            if (cb != null) cb.onWriterAborted(reason);
                        } catch (Throwable cbErr) {
                            logger.warn("WriterAbortListener threw: " + cbErr.getMessage());
                        }
                        // Drain queue and recycle so the writer loop exits
                        // promptly without leaking pooled buffers.
                        MuxerPacket drained;
                        while ((drained = muxerWriteQueue.poll()) != null) {
                            releaseMuxerPacket(drained);
                        }
                        diskWriterRunning = false;
                        // Don't call stopRecording() from here — that's a heavyweight
                        // operation that touches state owned by other threads. Just
                        // exit the writer; the main pipeline's existing watchdog or
                        // the next user action will trigger cleanup.
                        break;
                    }
                }
            }
            logger.info("Disk writer thread stopped");
        }, "GpuDiskWriter");
        
        // Java-level priority: NORM so the JVM thread scheduler doesn't deprioritize
        // the writer relative to other normal-priority threads. The Linux-level
        // nice value (set inside the Runnable above via Process.setThreadPriority)
        // is what actually controls I/O scheduling on Android — the Java priority
        // is mostly advisory.
        diskWriterThread.setPriority(Thread.NORM_PRIORITY);
        diskWriterThread.start();
    }
    
    /**
     * Stops the disk writer thread, flushing any remaining packets.
     */
    private void stopDiskWriterThread() {
        diskWriterRunning = false;
        if (diskWriterThread != null) {
            try {
                diskWriterThread.interrupt();
                diskWriterThread.join(2000);  // Allow up to 2s for final flush
            } catch (InterruptedException e) {
                // Ignore
            }
            diskWriterThread = null;
        }
    }
    
    /**
     * Public drainEncoder() - now just a no-op since draining happens on background thread.
     * Kept for API compatibility with existing code that calls it.
     */
    public void drainEncoder() {
        // SOTA: Draining now happens on background thread, not GL thread
        // This method is kept for API compatibility but does nothing
    }

    /** Queue staged AAC only after every matching historical video packet. */
    private int enqueueStagedPreRecordAudio(
            java.util.List<AacCircularBuffer.Packet> packets) {
        int enqueued = 0;
        for (AacCircularBuffer.Packet packet : packets) {
            MuxerPacket mp = acquireMuxerPacket(packet.data.length);
            if (mp == null) continue;
            mp.data.clear();
            mp.data.put(packet.data);
            mp.data.flip();
            mp.payloadSize = packet.data.length;
            mp.info.set(0, packet.data.length, packet.ptsUs, 0);
            mp.trackKind = TRACK_KIND_AUDIO;
            synchronized (muxerLock) {
                // The drainer can finish the video flush after the muxer is
                // ready but just before triggerEvent publishes isWritingToFile.
                // Muxer readiness is the authoritative gate for this staged
                // history; startStopLock keeps closeEventRecording out until
                // trigger publication completes.
                if (!muxerStarted || muxer == null
                        || audioTrackIndex < 0 || audioConfig == null) {
                    releaseMuxerPacket(mp);
                    break;
                }
                offerMuxerPacket(mp);
                enqueued++;
            }
        }
        return enqueued;
    }
    
    /**
     * Internal drain method called by background thread.
     * Handles all encoder output and SD card I/O.
     *
     * @return number of encoded video/audio frames drained from the encoder
     *         on this call. Used by the drainer loop's adaptive backoff
     *         (Fix H1): zero means we can sleep before the next call;
     *         non-zero means another packet may be immediately available
     *         and we should re-enter without sleeping. Pre-record flush
     *         packets and CODEC_CONFIG packets are NOT counted (they're
     *         not new frames produced by the encoder this tick).
     */
    private int drainEncoderInternal() {
        if (encoder == null) {
            return 0;
        }
        
        // SOTA: Process queued pre-record packets first (flush all at once).
        // These packets are written to the SD card via the muxer, which does NOT
        // block the encoder's input surface. The original chunking was added to prevent
        // MediaCodec backpressure, but that was only an issue when flushing on the GL
        // thread. Now that draining happens on a background thread, writing all packets
        // in one pass is safe and ensures no PTS gap between pre-record and live frames.
        //
        // CRITICAL: Live frames must NOT be written until this flush completes.
        // The pre-record packets have older PTS values. If live frames (with current PTS)
        // are interleaved, the muxer sees non-monotonic timestamps and the MP4 is corrupt.
        // Pre-record packets are drained in one pass into muxerWriteQueue.
        // The earlier "staggered flush" experiment (FLUSH_PACKETS_PER_TICK)
        // had a real bug: while flushInProgress=true for ~150ms, live frames
        // produced by the encoder hit the `!flushInProgress` gate below
        // (line ~1853) and were silently dropped — manifesting as recordings
        // freezing 5-6s in. Reverted. The single-pass drain is safe because
        // muxerWriteQueue.offerMuxerPacket() handles backpressure via its
        // drop-non-keyframe policy, AND the disk writer is now FOREGROUND
        // priority so it drains the burst quickly.
        if (flushInProgress && muxerStarted) {
            H264ByteRingBuffer.Cursor cursor = pendingFlushCursor;
            int flushedCount = 0;
            boolean videoFlushComplete = false;
            // Outer try/finally guarantees flushInProgress is cleared even if
            // the inner loop throws. Without this, a thrown exception escapes
            // up through the drainer's catch — pendingFlushCursor is nulled
            // by the inner finally but flushInProgress stays true, so the
            // `!flushInProgress` gate at line ~2018 keeps live frames from
            // entering the muxer queue until something else flips it false.
            // Recoverable in practice (next tick takes the cursor==null path
            // and clears the flag), but the symptom is a silently-truncated
            // pre-record window. Make the invariant explicit.
            try {
                if (cursor != null) {
                    try {
                        while (true) {
                            int sz = cursor.peekSize();
                            if (sz <= 0) break;
                            // Acquire-and-fill must be exception-safe: if next()
                            // throws (e.g., outDst.put surfaces a transient
                            // BufferOverflowException because the encoder produced
                            // a frame larger than the muxer pool's slot ceiling),
                            // we must NOT leak the packet back to GC. The pool is
                            // bounded; a leaked direct buffer waits for the
                            // Cleaner and starves later acquires.
                            MuxerPacket mp = acquireMuxerPacket(sz);
                            boolean handed = false;
                            try {
                                mp.data.position(0);
                                mp.data.limit(mp.data.capacity());
                                if (!cursor.next(mp.data, flushCursorInfo)) {
                                    // Aborted (pin broken) or exhausted.
                                    break;
                                }
                                mp.payloadSize = flushCursorInfo.size;
                                mp.info.set(0, flushCursorInfo.size,
                                    flushCursorInfo.presentationTimeUs, flushCursorInfo.flags);
                                offerMuxerPacket(mp);
                                handed = true;
                                flushedCount++;
                            } finally {
                                if (!handed) {
                                    releaseMuxerPacket(mp);
                                }
                            }
                        }
                        if (cursor.aborted()) {
                            logger.warn("Pre-record flush aborted by concurrent keyframe (pin broken) — partial flush of "
                                + flushedCount + " packets");
                        }
                        videoFlushComplete = !cursor.aborted()
                                && cursor.remaining() == 0 && flushedCount > 0;
                    } catch (Throwable t) {
                        // Catch Throwable, not Exception. An OOMError (or any
                        // VirtualMachineError) inside cursor.next()/put() would
                        // otherwise escape with pendingFlushCursor still set
                        // and the pin stuck on the orphaned cursor's
                        // pinReadFloor. The producer would then refuse to
                        // evict P-frames until the next encoder reinit calls
                        // clear() — silently collapsing the pre-record window
                        // to keyframes-only (≈1 frame per 2s GOP, ≈3 frames
                        // for a 5s window).
                        //
                        // We deliberately do NOT re-throw Errors: the cursor
                        // is closed in the inner finally, flushInProgress is
                        // cleared in the outer finally, and the drainer's
                        // outer Throwable catch would just log a duplicate.
                        // Recording continues with a truncated pre-record
                        // window — exactly the right degraded behaviour.
                        logger.error("Pre-record flush failed at packet "
                            + flushedCount + " — partial flush, continuing recording: "
                            + t.getMessage());
                    } finally {
                        cursor.close();
                        pendingFlushCursor = null;
                    }
                }
                java.util.List<AacCircularBuffer.Packet> stagedAudio = pendingAudioPreRecord;
                pendingAudioPreRecord = null;
                if (stagedAudio != null && !stagedAudio.isEmpty()) {
                    if (videoFlushComplete) {
                        int audioCount = enqueueStagedPreRecordAudio(stagedAudio);
                        logger.info("Async audio pre-record flush complete: "
                                + audioCount + " packets queued after video");
                    } else {
                        logger.warn("Audio pre-record discarded: matching video flush incomplete");
                    }
                }
                if (flushedCount > 0) {
                    logger.info("Async flush complete: " + flushedCount + " pre-record frames queued for disk write");
                }
            } finally {
                pendingAudioPreRecord = null;
                flushInProgress = false;
            }
        }
        
        // Check if segment rotation needed (only when actively writing to file).
        // SOTA: rotation requires a live disk writer + drainer; if either is
        // shutting down (e.g., we're inside the synchronous final drain in
        // closeEventRecording) the rotation logic would deadlock or produce a
        // stranded muxer. Skip in that case.
        // Don't rotate if the disk writer has aborted on SD-card death. The
        // hot-swap path would build a new muxer with no consumer (writer
        // already exited), and queued packets would pile up in
        // muxerWriteQueue forever. Stop the recording cleanly instead.
        // (Audit Finding R3.)
        if (writerAbortedCorrupt && isWritingToFile) {
            logger.warn("Writer aborted — stopping recording, no rotation");
            awaitLiveMuxerKeyframe = false;
            pendingAudioPreRecord = null;
            isWritingToFile = false;
            recording = false;
            // FIX (audit R2): also propagate the abort up to the wrapper so
            // GpuMosaicRecorder.recording flips false and StorageManager's
            // recordingActive sentinel clears. Without this, RMM's wedge
            // detector continues to read pipeline.isRecording()==true (from
            // the wrapper) even though the encoder side has already given up,
            // and the SD-watchdog's pendingOutputDirOverride never gets
            // consumed because activateMode short-circuits on
            // !shouldRetryActivation.
            try {
                WriterAbortListener cb = writerAbortListener;
                if (cb != null) {
                    String reason = writerAbortedErrorMessage != null
                        ? writerAbortedErrorMessage
                        : "rotation aborted (writerAbortedCorrupt latched)";
                    cb.onWriterAborted(reason);
                }
            } catch (Throwable cbErr) {
                logger.warn("WriterAbortListener (rotation path) threw: " + cbErr.getMessage());
            }
            return 0;
        }
        if (isWritingToFile && segmentStartTime > 0 && drainerRunning && diskWriterRunning
                && !writerAbortedCorrupt) {
            long elapsed = System.currentTimeMillis() - segmentStartTime;
            long cachedDuration = segmentDurationMs;
            if (elapsed >= cachedDuration) {
                if (savedFormat == null) {
                    // Encoder hasn't published its format yet — no frames have
                    // been encoded since segment start. Rotation would bail
                    // inside rotateSegmentLocked, but it doesn't update
                    // segmentStartTime on the bail-out path, so the drainer
                    // would re-enter this branch on every loop iteration
                    // (~16 ms cadence) and spam the log. Push the timer
                    // forward by a small slice so we re-check in 5 s instead
                    // of spinning. Real recovery is the rest of the audit:
                    // figure out why frames aren't flowing.
                    long now = System.currentTimeMillis();
                    if (now - lastNoFormatRotationLogMs > 30_000) {
                        logger.error("Segment duration reached (" + (elapsed / 1000)
                            + "s) but encoder has not published format — frames are not flowing");
                        lastNoFormatRotationLogMs = now;
                    }
                    segmentStartTime = now - cachedDuration + 5_000;
                } else {
                    logger.info("Segment duration reached (" + (elapsed / 1000) + "s), rotating to new file...");
                    rotateSegment();
                }
            }
        }
        
        MediaCodec.BufferInfo bufferInfo = reusableBufferInfo;

        // FIX H1: track frames produced so the drainer's adaptive backoff
        // can decide whether to skip the empty-tick sleep. We count any
        // outputBufferIndex >= 0 with bufferInfo.size > 0 that is NOT a
        // CODEC_CONFIG (i.e. real coded video/audio). Format-changes and
        // CODEC_CONFIG packets don't count — they don't represent the
        // encoder making forward progress on a frame queue we can drain.
        int framesDrained = 0;

        // SOTA Tier-A: first dequeue uses a short blocking timeout so the
        // drainer wakes the moment the encoder produces a packet (no 16 ms
        // poll-sleep gap between encoder-finish and drain). Subsequent
        // dequeues in the same tick stay non-blocking (timeout=0) so we
        // drain every available packet before yielding to the outer
        // sleep — this is what handles pre-record flush bursts and HEVC
        // I-frame catch-up without back-pressuring the input surface.
        boolean firstDequeue = true;
        while (true) {
            int outputBufferIndex;
            try {
                long dequeueTimeoutUs = firstDequeue ? 10_000L : 0L;
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, dequeueTimeoutUs);
                firstDequeue = false;
            } catch (Exception e) {
                // Encoder may have been released
                break;
            }

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;  // No more output available
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Format changed - add track to muxer and send SPS/PPS to stream
                MediaFormat format = encoder.getOutputFormat();
                
                // Save format for reuse in subsequent recordings
                if (savedFormat == null) {
                    savedFormat = format;
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    logger.info("Saved encoder format for reuse (codec=" +
                        (mime != null && mime.contains("hevc") ? "H.265" : "H.264") + ")");

                    // Notify any waiter (one-shot). This is the canonical
                    // moment isFormatAvailable() flips false→true.
                    FormatAvailableListener l = formatAvailableListener;
                    if (l != null) {
                        formatAvailableListener = null;
                        try { l.onFormatAvailable(); }
                        catch (Exception e) { logger.warn("FormatAvailableListener error: " + e.getMessage()); }
                    }
                }
                
                if (recording && !muxerStarted) {
                    synchronized (muxerLock) {
                        if (muxer != null && !muxerStarted) {
                            trackIndex = muxer.addTrack(format);
                            audioTrackIndex = maybeAddAudioTrack(muxer);
                            muxer.start();
                            muxerStarted = true;
                            logger.info("Muxer started (videoTrack=" + trackIndex
                                + ", audioTrack=" + audioTrackIndex + ")");
                        }
                    }
                }
                
                // Send SPS/PPS to streaming callback
                if (streamCallback != null && !streamHeadersSent) {
                    try {
                        ByteBuffer sps = format.getByteBuffer("csd-0");
                        ByteBuffer pps = format.getByteBuffer("csd-1");
                        if (sps != null && pps != null) {
                            streamCallback.onSpsPps(sps.duplicate(), pps.duplicate());
                            streamHeadersSent = true;
                            logger.info("SPS/PPS sent to stream");
                        }
                    } catch (Exception e) {
                        logger.error("Failed to send SPS/PPS", e);
                    }
                }
                
            } else if (outputBufferIndex >= 0) {
                // Got encoded data
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);

                // CODEC_CONFIG filter. HEVC encoders (notably Adreno 610) can
                // emit a BUFFER_FLAG_CODEC_CONFIG packet at outputBufferIndex
                // >= 0 with bufferInfo.size > 0 and presentationTimeUs = 0
                // — typically right after a format renegotiation, dynamic
                // IDR request, or a camera close-then-reopen. SPS/PPS for
                // the muxer is taken from the saved MediaFormat at trigger
                // time, so a CODEC_CONFIG packet at this site is redundant
                // for the muxer AND has a stale PTS=0 that would inject an
                // out-of-order sample into the queue (corrupting playback
                // the same way the flush-window bug did). Drop it cleanly,
                // release the buffer, and continue.
                //
                // Also drop from the pre-record ring: the ring stores
                // already-decoded-by-format packets, and a stale CODEC_CONFIG
                // with PTS=0 in the ring would fail the cursor's monotonic
                // PTS chain on flush.
                if (outputBuffer != null
                        && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    encoder.releaseOutputBuffer(outputBufferIndex, false);
                    continue;
                }

                if (outputBuffer != null && bufferInfo.size > 0) {
                    // FIX H1: a real coded frame — count it for adaptive backoff.
                    framesDrained++;
                    // FIX (audit R5): stamp last-encoded timestamp for the
                    // wedge ticker. Real coded frames only (CODEC_CONFIG
                    // already filtered above).
                    lastEncodedFrameMs = System.currentTimeMillis();
                    lastEncodedFrameElapsedMs = android.os.SystemClock.elapsedRealtime();
                    // ALWAYS add to circular buffer (for pre-record) - unless stream-only mode
                    if (usePreRecordBuffer && preRecordBuffer != null) {
                        preRecordBuffer.add(outputBuffer, bufferInfo);
                    }
                    
                    // PATH A: Write to disk (if event recording active).
                    //
                    // SOTA: Don't write to muxer directly — push to the muxer
                    // write queue. The disk writer thread handles the actual
                    // SD card I/O, preventing I/O stalls from blocking the
                    // encoder dequeue loop. Pooled packet avoids per-frame
                    // ByteBuffer.allocateDirect on the drainer thread (5–50
                    // ms native heap stalls observed during pre-record flush
                    // bursts).
                    //
                    // CRITICAL: do NOT gate on `!flushInProgress`. The previous
                    // version gated live frames behind the flush window, so the
                    // ~30ms of live-encoder output produced WHILE the flush was
                    // streaming the cursor was silently dropped (released back
                    // to the encoder, never enqueued). At 15 fps that's
                    // ~10–30 H.265 frames missing right at the pre-record→live
                    // boundary; the decoder runs out of reference frames and
                    // the playback corrupts at exactly that moment (≈the
                    // pre-record duration, e.g. 6s for the 6.7s pre-record
                    // window we observed).
                    //
                    // Why it's safe to enqueue during flush: the disk writer
                    // is a single thread that drains muxerWriteQueue in FIFO
                    // order. The flush enqueues pre-record packets first; the
                    // drainer at this site enqueues live packets after them.
                    // PTS is monotonic across the boundary (encoder's clock
                    // is the source for pre-record stored PTSs AND live PTSs).
                    // The muxer sees one continuous, ordered stream.
                    if (isWritingToFile && muxerStarted) {
                        boolean isKeyFrame = (bufferInfo.flags
                                & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (!awaitLiveMuxerKeyframe || isKeyFrame) {
                            MuxerPacket mp = acquireMuxerPacket(bufferInfo.size);
                            fillMuxerPacket(mp, outputBuffer, bufferInfo);
                            if (awaitLiveMuxerKeyframe) {
                                // Serialize the first IDR with audio's final gate
                                // check so AAC cannot enter this event queue first.
                                synchronized (muxerLock) {
                                    offerMuxerPacket(mp);
                                    awaitLiveMuxerKeyframe = false;
                                }
                            } else {
                                offerMuxerPacket(mp);
                            }
                        }
                    }
                    
                    // PATH B: Send to network (if streaming).
                    // Save+restore position/limit on the original buffer
                    // instead of allocating a fresh ByteBuffer.duplicate()
                    // per packet. Path A (muxer enqueue) finished above; the
                    // callback runs synchronously on this drainer thread and
                    // returns before encoder.releaseOutputBuffer() at the end
                    // of this iteration, so outputBuffer's mutation here is
                    // confined to the current thread and bounded to the
                    // stream-callback duration.
                    if (streamCallback != null && streamHeadersSent) {
                        int savedPos = outputBuffer.position();
                        int savedLim = outputBuffer.limit();
                        try {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            streamCallback.onH264Packet(outputBuffer, bufferInfo);
                        } catch (Exception e) {
                            logger.error("Stream callback error", e);
                        } finally {
                            outputBuffer.limit(savedLim);
                            outputBuffer.position(savedPos);
                        }
                    }
                }
                
                // Release output buffer
                encoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
        return framesDrained;
    }

    /**
     * Rotates to a new segment file.
     * 
     * Closes current file and starts new segment WITHOUT flushing pre-record buffer
     * (since we're continuing the same event, not starting a new one).
     */
    private void rotateSegment() {
        if (!isWritingToFile) {
            return;
        }
        // Gate concurrent rotation attempts. The drainer tick and
        // forceSegmentRotation can both reach here without sharing a lock;
        // without this CAS, both pre-construct a new MediaMuxer off-lock,
        // both enter the swap window, and the SECOND one's "old muxer"
        // is the FIRST one's brand-new (empty) muxer → empty middle .mp4.
        if (!rotationInFlight.compareAndSet(false, true)) {
            logger.info("rotateSegment skipped — another rotation in flight");
            return;
        }
        try {
            rotateSegmentLocked();
        } finally {
            rotationInFlight.set(false);
        }
    }

    private void rotateSegmentLocked() {

        // SOTA RC6: hot-swap segment rotation.
        //
        // Old design: stop the disk writer, drain queue, muxer.stop() (slow,
        // 50-200ms), open new muxer, restart writer. Total lock hold ≥100ms
        // during which no packet writes flow → muxerWriteQueue grows →
        // encoder eglSwap stalls → freeze+skip in the encoded MP4.
        //
        // New design: keep the disk writer running across the rotation. We:
        //   1. Compute the new path / tempFile and pre-construct the new
        //      MediaMuxer + addTrack on the SAME thread (the drainer thread
        //      that calls rotateSegment) but BEFORE acquiring muxerLock. The
        //      MediaMuxer constructor opens an fd; addTrack is metadata-only.
        //      Both are O(1) and don't block on existing writes.
        //   2. Briefly take muxerLock. Drain remaining queue into OLD muxer.
        //      Stash old muxer reference into a local. Atomically replace
        //      this.muxer with the new muxer; call new.start() inside the
        //      lock so the writer's next iteration sees a started muxer.
        //   3. Release muxerLock. Disk writer resumes immediately on the
        //      new muxer.
        //   4. Hand the old muxer + identity to a background worker thread
        //      that runs stop+release+rename. The 50-200ms cost happens
        //      OFF the disk-writer's critical path.
        //
        // Lock window: ~5-30ms (drain only) instead of 100-300ms. The disk
        // writer never stops/restarts. Encoder backpressure window collapses.

        logger.info("Rotating segment " + segmentNumber + " - hot-swap to new file");

        // Capture old segment identity for the background finalizer.
        final File oldTemp = tempFile;
        final String oldOutputPath = outputPath;
        final int oldSegmentNumber = segmentNumber;

        // === Step 1: pre-construct the new muxer OFF the lock ===
        segmentNumber++;
        String newPath = nextSegmentPath(segmentBasePath);
        File newTempFile = new File(newPath + ".tmp");
        MediaMuxer newMuxer;
        int newTrackIndex = -1;
        int newAudioTrackIndex = -1;

        // Stash the OUTGOING segment's start-geo before we overwrite the
        // active fields with the new segment's GPS. The engine's segment
        // listener (which fires AFTER this method returns, on the same
        // finalizer thread) will read these via getClosedStartGeo*() to
        // populate the closed segment's sidecar. Without this stash, the
        // closed segment's geo.start would carry the new segment's GPS,
        // misattributing location on every multi-segment recording.
        closedStartGeoLat         = startGeoLat;
        closedStartGeoLng         = startGeoLng;
        closedStartGeoAccuracy    = startGeoAccuracy;
        closedStartGeoAgeMs       = startGeoAgeMs;
        closedStartGeoCapturedAtMs = startGeoCapturedAtMs;

        // Refresh the start-location snapshot for the new segment so each
        // rotated .mp4 carries a geo block matching the time it actually
        // began (a 30-min trip can move several km between segment 1 and
        // segment 15). Failures are non-fatal — the segment just won't have
        // a geo block.
        captureStartLocationSnapshot();

        try {
            newMuxer = new MediaMuxer(newTempFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // ISO 6709 geotag for the new segment (separate moov from the
            // outgoing one). Same coordinate-clamping discipline as the
            // start path — never let a malformed coord break rotation.
            try {
                if (!Double.isNaN(startGeoLat) && !Double.isNaN(startGeoLng)) {
                    float lat = (float) Math.max(-90.0, Math.min(90.0, startGeoLat));
                    float lng = (float) Math.max(-180.0, Math.min(180.0, startGeoLng));
                    newMuxer.setLocation(lat, lng);
                }
            } catch (Throwable geoErr) {
                logger.warn("Rotation MediaMuxer.setLocation failed: " + geoErr.getMessage());
            }
            if (savedFormat != null) {
                newTrackIndex = newMuxer.addTrack(savedFormat);
                // Re-evaluate audio for the new segment so a mid-recording
                // toggle flip OR a fresh CSD upload from the app takes
                // effect at the rotation boundary.
                newAudioTrackIndex = maybeAddAudioTrack(newMuxer);
            } else {
                logger.error("Cannot rotate: savedFormat is null (encoder hasn't published format)");
                newMuxer.release();
                segmentNumber--;  // back out the bump
                return;
            }
        } catch (Exception e) {
            logger.error("Failed to pre-construct new segment muxer: " + e.getMessage(), e);
            if (newTempFile.exists()) newTempFile.delete();
            segmentNumber--;  // back out the bump
            return;
        }

        // === Step 2: brief lock window — drain, swap, start ===
        final MediaMuxer oldMuxer;
        final int oldTrackIndex;
        final int oldRecordedFrames;
        final long oldFirstPtsUs;
        final long oldLastPtsUs;
        boolean rotationOk = false;
        synchronized (muxerLock) {
            // Drain remaining queue into the OLD muxer. These packets have
            // PTS values that belong to the old segment; writing them to the
            // new muxer would break PTS monotonicity, so the queue MUST be
            // empty before the hot-swap below.
            //
            // BOUNDED: writing the backlog is blocking disk I/O on the drainer
            // thread, and under storage backpressure the queue can hold up to
            // MUXER_WRITE_QUEUE_CAPACITY packets, each writeSampleData stalling
            // on the slow drive. Draining all of it synchronously here starves
            // the encoder drain → GL eglSwapBuffers blocks → the 3s GL watchdog
            // kills the process (see ROTATE_DRAIN_BUDGET_MS). So we write only
            // until the time budget is spent, then DROP (recycle without
            // writing) the remaining old-segment packets. Dropping — rather
            // than leaving them queued — is mandatory: any packet left in the
            // queue would be picked up by the disk writer AFTER the swap and
            // written, with its old PTS, into the NEW muxer, corrupting that
            // segment. A sub-second seam gap beats a process restart.
            final long drainDeadlineNs =
                System.nanoTime() + ROTATE_DRAIN_BUDGET_MS * 1_000_000L;
            MuxerPacket pkt;
            int drained = 0;
            int dropped = 0;
            boolean stopWriting = false;  // latches once budget spent or a write fails
            while ((pkt = muxerWriteQueue.poll()) != null) {
                if (!stopWriting && muxerStarted && muxer != null
                        && System.nanoTime() < drainDeadlineNs) {
                    try {
                        pkt.rewindForWrite();
                        if (pkt.trackKind == TRACK_KIND_AUDIO) {
                            if (audioTrackIndex >= 0) {
                                writeRebasedAudio(muxer, audioTrackIndex,
                                    pkt.data, pkt.info);
                            }
                        } else {
                            writeRebased(muxer, trackIndex, pkt.data, pkt.info);
                            // PTS tracking handled inside writeRebased; uses
                            // the OLD segment's origin since muxer/ptsOriginUs
                            // haven't been swapped yet.
                            recordedFrames++;
                            lastDiskWrittenMs = System.currentTimeMillis();
                        }
                        drained++;
                    } catch (Exception e) {
                        logger.warn("Rotation drain error: " + e.getMessage());
                        writerAbortedCorrupt = true;
                        // Drop (don't leave queued) the rest, so nothing leaks
                        // into the new muxer after the swap.
                        stopWriting = true;
                        dropped++;
                        releaseMuxerPacket(pkt);
                        continue;
                    }
                } else {
                    // Budget exhausted (or muxer gone): latch and drop the
                    // remaining backlog instead of stalling the drainer.
                    stopWriting = true;
                    dropped++;
                }
                releaseMuxerPacket(pkt);
            }
            if (drained > 0 || dropped > 0) {
                if (dropped > 0) {
                    logger.warn("Rotation drained " + drained + " queued frames into old"
                        + " segment, DROPPED " + dropped + " (drain budget "
                        + ROTATE_DRAIN_BUDGET_MS + "ms exceeded — storage backpressure;"
                        + " dropping seam frames to avoid a GL watchdog restart)");
                } else {
                    logger.debug("Rotation drained " + drained + " queued frames into old segment");
                }
            }

            // Stash the old muxer + its stats so the background finalizer
            // owns them. Anything we update on `this.*` from here on belongs
            // to the new segment.
            oldMuxer = muxer;
            oldTrackIndex = trackIndex;
            oldRecordedFrames = recordedFrames;
            oldFirstPtsUs = firstFramePtsUs;
            oldLastPtsUs = lastFramePtsUs;

            // Hot-swap: this.muxer now points at the new muxer. The disk
            // writer's next iteration will write to it.
            try {
                newMuxer.start();
                muxer = newMuxer;
                trackIndex = newTrackIndex;
                audioTrackIndex = newAudioTrackIndex;
                muxerStarted = true;
                tempFile = newTempFile;
                outputPath = newPath;
                // Reset per-segment counters AFTER capturing oldFirstPtsUs etc.
                // for the finalizer. Reset ptsOriginUs too so the new
                // segment's muxer captures its own origin from its first
                // packet — without this the new muxer would inherit the old
                // segment's origin and produce out-of-range PTSs.
                recordedFrames = 0;
                firstFramePtsUs = -1;
                lastFramePtsUs = -1;
                ptsOriginUs = -1;
                lastSourcePtsUs = -1;
                lastAudioPtsUs = -1L;
                // Re-seed the disk-write clock on rotation so the new segment
                // gets a fresh grace window (mirrors the trigger-open seed).
                lastDiskWrittenMs = System.currentTimeMillis();
                segmentStartTime = System.currentTimeMillis();
                rotationOk = true;
            } catch (Exception e) {
                logger.error("Failed to start new segment muxer: " + e.getMessage(), e);
                // Restore the old muxer as the active one so the writer keeps
                // writing to a still-valid target. The old segment continues
                // until the next rotation attempt.
                try { newMuxer.release(); } catch (Exception ignored) {}
                if (newTempFile.exists()) newTempFile.delete();
                segmentNumber--;  // back out the bump
            }
        }

        if (!rotationOk) {
            return;
        }

        // === Step 3: writer is already on the new muxer. Request keyframe. ===
        // Without this, the new segment would start with P-frames referencing
        // an I-frame that lives in the old (now-stopped) file. Players would
        // render garbage until the next ~2-second I-frame interval.
        requestSyncFrame();
        logger.info("Segment " + segmentNumber + " started: " + newTempFile.getName());

        // === Step 4: hand the old muxer to a background finalizer ===
        // stop() takes 50-200ms (writes the moov atom). rename() is fast but
        // blocks on metadata. Both happen off the realtime-critical path.
        //
        // Snapshot writerAbortedCorrupt at rotation time. The live volatile
        // is shared across all in-flight finalizers; without snapshotting,
        // a transient SD-card hiccup that flips the flag during finalizer N's
        // stop() would also poison finalizer N+1's perfectly-fine segment
        // because both check the same live flag (audit Finding R2).
        finalizeOldSegmentAsync(oldMuxer, oldTemp, oldOutputPath,
                oldSegmentNumber, oldRecordedFrames, oldFirstPtsUs, oldLastPtsUs,
                writerAbortedCorrupt, new File(newPath));
    }

    /**
     * Background worker that runs muxer.stop() + release() + rename for the
     * old segment after the hot-swap. Idempotent rename failure handling and
     * SegmentListener notification mirror the synchronous path's logic.
     *
     * Exceptions inside this lambda are logged and swallowed — the rotation
     * has already succeeded for the new segment by the time we get here, so
     * a finalizer failure shouldn't crash the process.
     */
    private void finalizeOldSegmentAsync(final MediaMuxer oldMuxer,
                                         final File oldTemp, final String oldOutputPath,
                                         final int oldSegmentNumber, final int oldRecordedFrames,
                                         final long oldFirstPtsUs, final long oldLastPtsUs,
                                         final boolean wasAbortedAtRotation,
                                         final File newSegmentFile) {
        // Increment BEFORE constructing the thread so a close() that arrives
        // immediately after this method returns sees the in-flight count.
        inFlightFinalizers.incrementAndGet();
        Thread t = new Thread(() -> {
          try {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}

            boolean stopOk = false;
            try {
                if (oldMuxer != null) {
                    oldMuxer.stop();
                    stopOk = true;
                }
            } catch (Exception e) {
                logger.warn("Old-segment muxer.stop error: " + e.getMessage());
            }
            try {
                if (oldMuxer != null) oldMuxer.release();
            } catch (Exception e) {
                logger.warn("Old-segment muxer.release error: " + e.getMessage());
            }

            // Use the snapshot taken at rotation time, NOT the live volatile.
            // Otherwise a transient SD-card hiccup that flips the live flag
            // during this finalizer's stop() would also poison the next
            // finalizer's perfectly-fine segment. (Audit Finding R2.)
            boolean segmentBroken = !stopOk || wasAbortedAtRotation;
            File finalisedSegment = null;
            if (oldTemp != null && oldTemp.exists()) {
                if (!segmentBroken && oldRecordedFrames > 0 && oldTemp.length() > 1024) {
                    File finalFile = new File(oldOutputPath);
                    if (oldTemp.renameTo(finalFile)) {
                        finalisedSegment = finalFile;
                        float durationSec = (oldFirstPtsUs >= 0 && oldLastPtsUs > oldFirstPtsUs)
                                ? (oldLastPtsUs - oldFirstPtsUs) / 1_000_000.0f
                                : oldRecordedFrames / (float) fps;
                        logger.info(String.format("Segment %d saved: %s (%d frames, %.1f sec, %d KB)",
                                oldSegmentNumber, finalFile.getName(), oldRecordedFrames,
                                durationSec, finalFile.length() / 1024));
                        try {
                            com.overdrive.app.storage.StorageManager.getInstance().onFileSaved(finalFile);
                        } catch (Exception e) {
                            logger.warn("onFileSaved error: " + e.getMessage());
                        }

                        // Same eager-seed pattern as the synchronous close
                        // path — rotation produces a finalised .mp4 that
                        // should appear in /api/recordings without waiting
                        // on the FileObserver.
                        try {
                            com.overdrive.app.server.RecordingsIndex.getInstance().upsert(finalFile);
                        } catch (Throwable e) {
                            logger.warn("Index upsert failed for " + finalFile.getName() + ": " + e.getMessage());
                        }

                        // Geo sidecar for non-sentry rotated segments
                        // (cam_*, proximity_*). Sentry segments go via
                        // SurveillanceEngineGpu's listener which writes
                        // the richer v3 sidecar. The CLOSED-segment geo
                        // is in closedStartGeo* (stashed at the top of
                        // rotateSegmentLocked before we refreshed the
                        // active fields for the new segment). Off-thread
                        // executor inside LocationSidecarWriter.
                        try {
                            String flow = inferGeocodingFlow(finalFile.getName());
                            if (!"surveillance".equals(flow)) {
                                com.overdrive.app.geo.GeoSnapshot startGeo;
                                if (hasClosedStartGeo()) {
                                    startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                            closedStartGeoLat, closedStartGeoLng,
                                            closedStartGeoAccuracy, closedStartGeoAgeMs,
                                            closedStartGeoCapturedAtMs, 0L);
                                } else {
                                    startGeo = com.overdrive.app.geo.GeoSnapshot.empty();
                                }
                                com.overdrive.app.geo.LocationSidecarWriter
                                        .getInstance()
                                        .submit(finalFile, flow, startGeo);
                            }
                        } catch (Throwable e) {
                            logger.warn("Rotation geo sidecar submit failed: "
                                    + e.getMessage());
                        }
                    } else {
                        logger.error("Failed to rename segment " + oldSegmentNumber + " — deleting orphan");
                        oldTemp.delete();
                    }
                } else if (segmentBroken) {
                    File broken = new File(oldOutputPath + ".broken");
                    if (!oldTemp.renameTo(broken)) {
                        logger.warn("Quarantine rename failed; deleting broken tmp: " + oldTemp.getName());
                        oldTemp.delete();
                    } else {
                        logger.warn("Quarantined broken segment " + oldSegmentNumber
                                + " (stopOk=" + stopOk + ", writerAborted=" + wasAbortedAtRotation
                                + ", " + (broken.length() / 1024) + " KB): " + broken.getName());
                    }
                } else {
                    logger.warn("Deleting empty segment " + oldSegmentNumber + " tmp file");
                    oldTemp.delete();
                }

                // Same reason as closeEventRecording: the failure branches unlink a
                // size-counted *.mp4.tmp for a COMPLETE rotated segment, and only
                // the success branch reaches onFileSaved. Without this the reported
                // size keeps counting a segment that is already gone.
                try {
                    com.overdrive.app.storage.StorageManager.getInstance()
                            .invalidateCategorySizeCache(null);
                } catch (Throwable ignored) {
                    // StorageManager may not be initialised in every process.
                }
            }

            // Notify the engine after the rename so consumers can read the
            // finalised file. Same listener as the synchronous path.
            SegmentListener listener = segmentListener;
            if (listener != null) {
                try {
                    listener.onSegmentClosed(finalisedSegment, newSegmentFile);
                } catch (Throwable th) {
                    logger.warn("SegmentListener error: " + th.getMessage());
                }
            }
          } finally {
            // Decrement and notify any close()/release() waiter. Must be in
            // finally so an exception inside the body still releases the
            // join. Without this, a buggy SegmentListener could lock the
            // pipeline shutdown forever.
            int remaining = inFlightFinalizers.decrementAndGet();
            if (remaining == 0) {
                synchronized (finalizerJoinLock) {
                    finalizerJoinLock.notifyAll();
                }
            }
          }
        }, "GpuSegmentFinalizer-" + oldSegmentNumber);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /**
     * Wait for any in-flight segment finalizers to complete. Bounded by
     * timeoutMs (returns false on timeout — caller must decide whether to
     * proceed anyway). Called from closeEventRecording and release() so a
     * rapid stop+restart can't race a still-running rename + onFileSaved.
     */
    private boolean waitForFinalizers(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (finalizerJoinLock) {
            while (inFlightFinalizers.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    logger.warn("waitForFinalizers timed out with "
                        + inFlightFinalizers.get() + " still in flight");
                    return false;
                }
                try {
                    finalizerJoinLock.wait(remaining);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Flushes and closes muxer immediately.
     * 
     * Used when ACC state changes during recording to ensure
     * file is properly closed before shutdown.
     */
    public void flushAndClose() {
        if (recording) {
            logger.info( "Flushing and closing muxer (ACC state change)");
            stopRecording();
        }
    }
    
    // Track the current output file path for cleanup protection
    private static volatile String currentlyWritingPath = null;
    
    /**
     * Gets the path of the file currently being written to.
     * Used by cleanup to avoid deleting active files.
     */
    public String getCurrentOutputPath() {
        return outputPath;
    }

    /**
     * Build the path for the next rotated segment.
     *
     * <p>Input is the base path of the original recording (no extension), e.g.
     * {@code /sdcard/.../cam_20260513_140523}. The original filename is
     * {@code <prefix>_yyyyMMdd_HHmmss}; we drop the trailing timestamp and
     * append a fresh one so each segment is a self-describing
     * {@code <prefix>_yyyyMMdd_HHmmss.mp4}, never {@code _1}, {@code _2}, etc.
     *
     * <p>If the basename can't be parsed (unexpected format), falls back to
     * the legacy {@code <base>_<n>.mp4} naming so a single bad recording
     * doesn't lose its rotation.
     */
    private String nextSegmentPath(String basePath) {
        String fresh = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        try {
            int slash = basePath.lastIndexOf('/');
            String dir = slash >= 0 ? basePath.substring(0, slash + 1) : "";
            String name = slash >= 0 ? basePath.substring(slash + 1) : basePath;
            // Strip the original _yyyyMMdd_HHmmss suffix (last two underscore
            // segments — date and time). Anything else is the prefix.
            int lastUnderscore = name.lastIndexOf('_');
            if (lastUnderscore > 0) {
                int prevUnderscore = name.lastIndexOf('_', lastUnderscore - 1);
                if (prevUnderscore > 0) {
                    String prefix = name.substring(0, prevUnderscore);
                    String candidate = dir + prefix + "_" + fresh + ".mp4";
                    // Same-second rotation (or pre-existing file) — disambiguate
                    // with a short suffix rather than overwriting.
                    if (new java.io.File(candidate).exists()
                            || candidate.equals(outputPath)) {
                        // Underscore (not dash) so the UI regexes in
                        // RecordingsApiHandler — CAM_PATTERN / EVENT_PATTERN /
                        // PROXIMITY_PATTERN, all `(?:_\d+)?` — accept the
                        // disambiguated filename. A dash made the segment
                        // invisible to the web UI, calendar, and storage stats.
                        candidate = dir + prefix + "_" + fresh + "_" + segmentNumber + ".mp4";
                    }
                    return candidate;
                }
            }
        } catch (Exception ignored) {}
        return basePath + "_" + segmentNumber + ".mp4";
    }
    
    /**
     * Implements loop recording by deleting oldest segments when storage is low.
     * 
     * CRITICAL: Protects files that are currently being written to prevent corruption.
     * 
     * @param directory Directory containing recordings
     * @param maxSizeBytes Maximum total size in bytes
     */
    public static void cleanupOldSegments(File directory, long maxSizeBytes) {
        cleanupOldSegments(directory, maxSizeBytes, null);
    }

    /**
     * Clean up orphaned .tmp files that were left behind by crashed recordings,
     * and reap *.broken quarantine sidecars produced by the close/rotate paths
     * when a muxer.stop() failed or the disk writer aborted. Files older than
     * 5 minutes are removed.
     */
    public static void cleanupOrphanedTmpFiles(File directory) {
        if (!directory.exists() || !directory.isDirectory()) return;

        File[] orphans = directory.listFiles((dir, name) ->
                name.endsWith(".tmp") || name.endsWith(".broken"));
        if (orphans == null) {
            // FUSE-bridged SD/USB returns null under daemon UID 2000. Without this
            // fallback the external dir is skipped and .tmp/.broken partials pile up
            // on the card (counted by StorageManager's size gate but unreapable),
            // parking the folder over its limit. Use StorageManager's shell-ls
            // fallback for each suffix and merge.
            try {
                com.overdrive.app.storage.StorageManager sm =
                        com.overdrive.app.storage.StorageManager.getInstance();
                java.util.List<File> merged = new java.util.ArrayList<>();
                java.util.Collections.addAll(merged, sm.listFilesWithFallback(directory, ".tmp"));
                java.util.Collections.addAll(merged, sm.listFilesWithFallback(directory, ".broken"));
                orphans = merged.toArray(new File[0]);
            } catch (Throwable t) {
                logger.warn("cleanupOrphanedTmpFiles: shell fallback failed for "
                        + directory.getAbsolutePath() + ": " + t.getMessage());
                return;
            }
        }
        if (orphans == null || orphans.length == 0) return;

        long now = System.currentTimeMillis();
        boolean anyDeleted = false;
        for (File f : orphans) {
            long age = now - f.lastModified();
            if (age > 5 * 60 * 1000) { // Older than 5 minutes
                long size = f.length();
                boolean ok = f.delete();
                if (!ok) {
                    // Java delete fails on the SD FUSE mount under UID 2000 the same
                    // way listFiles() does — fall back to a shell rm so the partial
                    // is actually freed.
                    ok = deleteViaShell(f);
                }
                if (ok) {
                    anyDeleted = true;
                    logger.info("Cleaned orphan: " + f.getName() + " (" + (size / 1024)
                            + " KB, age=" + (age / 1000) + "s)");
                }
            }
        }

        // These .tmp/.broken partials ARE counted by StorageManager's reported size
        // (partialExtensionsForCategory), and an aborted segment partial is a full
        // clip — hundreds of MB. This sweep is separate from cleanupOldSegments and
        // from StorageManager.sweepOrphanTempFiles, so nothing else invalidates the
        // reporting cache for it; without this the storage card keeps counting bytes
        // that are already freed.
        if (anyDeleted) {
            try {
                com.overdrive.app.storage.StorageManager.getInstance()
                        .invalidateCategorySizeCache(null);
            } catch (Throwable ignored) {
                // StorageManager may not be initialised in every process.
            }
        }
    }

    /** Shell {@code rm} fallback for files Java {@link File#delete()} can't remove
     *  (SD/USB FUSE mount owned by a different UID). Bounded so a stuck FUSE volume
     *  can't pin the sweep. Returns true on exit code 0. */
    private static boolean deleteViaShell(File file) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"rm", file.getAbsolutePath()});
            boolean exited = p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return false;
        }
    }
    
    /**
     * Implements loop recording by deleting oldest segments when storage is low.
     * 
     * CRITICAL: Protects files that are currently being written to prevent corruption.
     * 
     * @param directory Directory containing recordings
     * @param maxSizeBytes Maximum total size in bytes
     * @param activeRecorder Optional recorder to check for active file (null = no protection)
     */
    public static void cleanupOldSegments(File directory, long maxSizeBytes, HardwareEventRecorderGpu activeRecorder) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) {
            return;
        }
        
        // Get the currently active file path (if any)
        String activeFilePath = null;
        String activeTempPath = null;
        if (activeRecorder != null && activeRecorder.isWritingToFile()) {
            activeFilePath = activeRecorder.outputPath;
            if (activeRecorder.tempFile != null) {
                activeTempPath = activeRecorder.tempFile.getAbsolutePath();
            }
        }
        
        // Calculate total size (excluding active files)
        long totalSize = 0;
        for (File file : files) {
            // Skip files currently being written
            String filePath = file.getAbsolutePath();
            if (filePath.equals(activeFilePath) || filePath.equals(activeTempPath)) {
                logger.debug("Skipping active file in size calculation: " + file.getName());
                continue;
            }
            // Skip temp files (*.tmp) - they're being written
            if (file.getName().endsWith(".tmp")) {
                logger.debug("Skipping temp file in size calculation: " + file.getName());
                continue;
            }
            totalSize += file.length();
        }
        
        // Delete oldest files if over limit
        boolean anyDeleted = false;
        if (totalSize > maxSizeBytes) {
            // Sort by last modified (oldest first)
            java.util.Arrays.sort(files, (f1, f2) -> 
                Long.compare(f1.lastModified(), f2.lastModified()));
            
            for (File file : files) {
                if (totalSize <= maxSizeBytes) {
                    break;
                }
                
                String filePath = file.getAbsolutePath();
                
                // CRITICAL: Never delete the file currently being written
                if (filePath.equals(activeFilePath) || filePath.equals(activeTempPath)) {
                    logger.warn("Skipping deletion of active file: " + file.getName());
                    continue;
                }
                
                // Skip temp files - they're being written
                if (file.getName().endsWith(".tmp")) {
                    logger.warn("Skipping deletion of temp file: " + file.getName());
                    continue;
                }
                
                // Skip very recent files (less than 5 seconds old) - may still be finalizing
                long fileAge = System.currentTimeMillis() - file.lastModified();
                if (fileAge < 5000) {
                    logger.warn("Skipping deletion of recent file (age=" + fileAge + "ms): " + file.getName());
                    continue;
                }
                
                long fileSize = file.length();
                if (file.delete()) {
                    totalSize -= fileSize;
                    anyDeleted = true;
                    long sidecarBytes = deleteSegmentSidecars(file);
                    logger.info("Deleted old segment: " + file.getName() +
                            " (" + (fileSize / 1024) + " KB"
                            + (sidecarBytes > 0 ? ", +" + (sidecarBytes / 1024) + " KB sidecars" : "")
                            + ")");
                } else {
                    logger.warn("Failed to delete file: " + file.getName());
                }
            }
        }

        // This rotation frees size-counted .mp4s AND their .json/.jpg/thumb_
        // sidecars, but it is the recorder's OWN loop-rotation — it does not go
        // through StorageManager's reap, so nothing else invalidates the reporting
        // size/count cache. Without this the storage card keeps reporting the
        // rotated-away bytes until some other mutation happens to invalidate.
        if (anyDeleted) {
            try {
                com.overdrive.app.storage.StorageManager.getInstance()
                        .invalidateCategorySizeCache(null);
            } catch (Throwable ignored) {
                // StorageManager may not be initialised in every process.
            }
        }
    }

    /**
     * Removes the sidecar files that accompany an .mp4 segment: JSON event
     * timeline, v3 hero JPEG, and per-actor thumbnails {@code thumb_<base>_a*.jpg}.
     *
     * Without this, the loop-rotation deletion only frees the .mp4's bytes —
     * sidecars accumulate as orphans because future passes continue to skip
     * non-.mp4 files. Returns the freed bytes.
     */
    private static long deleteSegmentSidecars(File mp4File) {
        // Drop the API-handler cache entry for this segment so /api/recordings
        // doesn't keep returning a phantom row for a file that's been rotated.
        try {
            com.overdrive.app.server.RecordingsApiHandler.invalidateRecordingCache(
                    mp4File.getAbsolutePath());
        } catch (Throwable ignored) {}

        File parent = mp4File.getParentFile();
        if (parent == null) return 0L;
        String mp4Name = mp4File.getName();
        if (!mp4Name.endsWith(".mp4")) return 0L;
        String base = mp4Name.substring(0, mp4Name.length() - 4);
        long freed = 0L;

        File jsonFile = new File(parent, base + ".json");
        if (jsonFile.exists()) {
            long s = jsonFile.length();
            if (jsonFile.delete()) freed += s;
        }
        File heroFile = new File(parent, base + ".jpg");
        if (heroFile.exists()) {
            long s = heroFile.length();
            if (heroFile.delete()) freed += s;
        }
        // Anchor with "_a" so sibling segment thumbs (e.g. <base>_2's actor
        // thumbs at "thumb_<base>_2_a*.jpg") aren't swept when this segment
        // is rotated out. ThumbnailBuffer always writes "thumb_<base>_a<id>...".
        final String perActorPrefix = "thumb_" + base + "_a";
        File[] perActor = parent.listFiles((d, name) ->
                name.startsWith(perActorPrefix) && name.endsWith(".jpg"));
        if (perActor != null) {
            for (File f : perActor) {
                long s = f.length();
                if (f.delete()) freed += s;
            }
        }
        return freed;
    }
}
