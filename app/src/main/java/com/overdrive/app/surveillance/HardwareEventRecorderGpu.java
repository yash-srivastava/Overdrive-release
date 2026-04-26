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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    
    // Configuration
    private final int width;
    private final int height;
    private int fps;
    private int bitrate;
    private String codecMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;  // Default H.264
    
    // Encoder
    private MediaCodec encoder;
    private Surface inputSurface;
    
    // Muxer
    private MediaMuxer muxer;
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private MediaFormat savedFormat = null;  // Save format for reuse
    
    // Circular buffer for pre-record
    // SOTA: Static buffer shared across encoder instances to avoid 23MB reallocation on reinit
    private static H264CircularBuffer sharedPreRecordBuffer;
    private static final Object bufferLock = new Object();
    private H264CircularBuffer preRecordBuffer;  // Reference to shared buffer
    private boolean isWritingToFile = false;
    private long postRecordStopTime = 0;
    
    // SOTA: Async pre-record flush queue (eliminates blocking on motion trigger)
    // Packets are queued here and written by drainEncoder() on the GL thread
    private final ConcurrentLinkedQueue<H264CircularBuffer.Packet> pendingFlushQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean flushInProgress = false;
    private volatile long actualPreRecordDurationMs = 0;  // Actual duration of flushed pre-record buffer
    
    // SOTA: Muxer write queue — decouples encoder dequeue from SD card I/O.
    // The encoder dequeue loop copies frame data and releases the encoder buffer
    // immediately, then pushes to this queue. A dedicated disk writer thread
    // polls the queue and writes to the muxer. This prevents SD card I/O stalls
    // (which can be 50-100ms during garbage collection) from blocking the encoder,
    // which would cause the GPU to stall and drop camera frames.
    private static class MuxerPacket {
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;
        MuxerPacket(ByteBuffer src, MediaCodec.BufferInfo srcInfo) {
            // Deep copy — the encoder buffer is released immediately after
            data = ByteBuffer.allocateDirect(srcInfo.size);
            src.position(srcInfo.offset);
            src.limit(srcInfo.offset + srcInfo.size);
            data.put(src);
            data.flip();
            info = new MediaCodec.BufferInfo();
            info.set(0, srcInfo.size, srcInfo.presentationTimeUs, srcInfo.flags);
        }
    }
    private final ConcurrentLinkedQueue<MuxerPacket> muxerWriteQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean diskWriterRunning = false;
    private Thread diskWriterThread;
    
    // SOTA: Background drainer thread (moves SD card I/O off GL thread)
    private volatile boolean drainerRunning = false;
    private Thread drainerThread;
    private static final int DRAIN_INTERVAL_MS = 16;  // ~60Hz cadence, matches frame arrival rate
    
    // SOTA: Flag to disable pre-record buffer for stream-only encoders
    private boolean usePreRecordBuffer = true;
    
    // Pre-allocated BufferInfo — reused every drain cycle to avoid per-frame allocation
    private final MediaCodec.BufferInfo reusableBufferInfo = new MediaCodec.BufferInfo();
    
    // Callback for when file is closed
    private Runnable fileClosedCallback;
    
    // Streaming
    private StreamCallback streamCallback;
    private boolean streamHeadersSent = false;
    
    // Recording state
    private boolean recording = false;
    private String outputPath;
    private File tempFile;
    private int recordedFrames = 0;
    private long firstFramePtsUs = -1;   // PTS of first frame written to muxer
    private long lastFramePtsUs = -1;    // PTS of last frame written to muxer
    
    // Segment rotation
    private long segmentStartTime = 0;
    private static final long SEGMENT_DURATION_MS = 2 * 60 * 1000;  // 2 minutes
    private int segmentNumber = 0;
    private String segmentBasePath = null;  // Base path for segment rotation (without .mp4)
    
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
     * Sets the codec MIME type before initialization.
     * Must be called before init().
     * 
     * @param mimeType MIMETYPE_VIDEO_AVC (H.264) or MIMETYPE_VIDEO_HEVC (H.265)
     */
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
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);  // I-frame every 2 seconds
        
        // Set max input size to prevent Qualcomm crashes
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);
        
        // Low latency hints (optional)
        try {
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        } catch (Exception e) {
            // Ignore if not supported
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
        logger.info("MediaCodec encoder created");
        
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
        
        // SOTA: Reuse shared buffer across encoder instances (avoids 23MB allocation on reinit)
        // Only allocate for encoders that use pre-record (not stream-only encoders)
        if (usePreRecordBuffer) {
            synchronized (bufferLock) {
                if (sharedPreRecordBuffer == null) {
                    logger.info("Allocating NEW pre-record buffer (23MB)...");
                    sharedPreRecordBuffer = new H264CircularBuffer(5);
                } else {
                    logger.info("Reusing EXISTING pre-record buffer (Zero-Allocation)");
                    sharedPreRecordBuffer.clear();  // Clear old data but keep allocated memory
                }
                preRecordBuffer = sharedPreRecordBuffer;
            }
        } else {
            logger.info("Pre-record buffer disabled (stream-only mode)");
            preRecordBuffer = null;
        }
        
        // SOTA: Start background drainer thread (moves SD card I/O off GL thread)
        startDrainerThread();
        
        logger.info( "Encoder initialized successfully" + (usePreRecordBuffer ? " (pre-record: 5 sec)" : " (stream-only)"));
    }
    
    /**
     * Updates the pre-record buffer size.
     * 
     * SOTA: Reuses existing buffer if same duration to avoid 23MB allocation.
     * Only recreates if duration actually changed.
     * 
     * @param durationSeconds New buffer duration in seconds
     */
    public void setPreRecordDuration(int durationSeconds) {
        synchronized (bufferLock) {
            if (sharedPreRecordBuffer != null) {
                // SOTA: Check if duration actually changed before reallocating
                long currentMaxDurationUs = durationSeconds * 1_000_000L;
                // Only recreate if duration is different (avoid 23MB allocation on every settings change)
                if (sharedPreRecordBuffer.getMaxDurationUs() != currentMaxDurationUs) {
                    logger.info("Pre-record duration changed, recreating buffer: " + durationSeconds + " seconds");
                    sharedPreRecordBuffer = new H264CircularBuffer(durationSeconds);
                    preRecordBuffer = sharedPreRecordBuffer;
                } else {
                    logger.info("Pre-record buffer already at " + durationSeconds + " seconds, clearing only");
                    sharedPreRecordBuffer.clear();
                }
            }
        }
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
        if (isWritingToFile) {
            // Already recording, extend post-record duration
            postRecordStopTime = System.currentTimeMillis() + postRecordDurationMs;
            logger.info("Event extended - post-record timer reset to " + postRecordDurationMs + "ms");
            return true;
        }
        
        try {
            this.outputPath = outputPath;
            
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
            
            // Create muxer
            muxer = new MediaMuxer(tempFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            // If we have a saved format, use it immediately
            if (savedFormat != null) {
                trackIndex = muxer.addTrack(savedFormat);
                muxer.start();
                muxerStarted = true;
                logger.info("Muxer started with saved format (track=" + trackIndex + ")");
                
                // SOTA: Queue pre-record packets for async flush (NON-BLOCKING!)
                // drainEncoder() will write these on the GL thread
                List<H264CircularBuffer.Packet> preRecordPackets = preRecordBuffer.getPacketsForFlush();
                double preRecordDuration = preRecordPackets.isEmpty() ? 0 : 
                    (preRecordPackets.get(preRecordPackets.size()-1).info.presentationTimeUs - 
                     preRecordPackets.get(0).info.presentationTimeUs) / 1_000_000.0;
                
                // Store actual pre-record duration for timeline alignment
                actualPreRecordDurationMs = (long)(preRecordDuration * 1000);
                
                // Add all packets to the flush queue (instant, no I/O)
                pendingFlushQueue.addAll(preRecordPackets);
                flushInProgress = true;
                
                logger.info(String.format("Queued %d pre-record packets (%.1f sec) for async flush",
                        preRecordPackets.size(), preRecordDuration));
            }
            
            // Reset state
            startTimeNs = System.nanoTime();
            segmentStartTime = System.currentTimeMillis();  // Enable segment rotation for long events
            segmentNumber = 0;
            segmentBasePath = outputPath.replaceAll("\\.mp4$", "");  // Store base path for segment rotation
            postRecordStopTime = System.currentTimeMillis() + postRecordDurationMs;
            
            isWritingToFile = true;
            recording = true;  // Keep for compatibility
            
            logger.info(String.format("Event recording started: %s (codec=%s, bitrate=%d Mbps, post-record=%dms)",
                tempFile.getName(),
                codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                bitrate / 1_000_000,
                postRecordDurationMs));
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to trigger event recording", e);
            return false;
        }
    }
    
    /**
     * Legacy method for compatibility - redirects to triggerEventRecording.
     */
    public boolean startRecording(String outputPath) {
        return triggerEventRecording(outputPath, 5000);  // Default 5 sec post-record
    }
    
    /**
     * Stops recording immediately or schedules post-record stop.
     * 
     * @param immediate If true, stops immediately. If false, does nothing (timeout handled by caller)
     * @param postRecordDurationMs Post-record duration (ignored, kept for API compatibility)
     */
    public void stopEventRecording(boolean immediate, long postRecordDurationMs) {
        if (!isWritingToFile) {
            return;
        }
        
        if (immediate) {
            closeEventRecording();
        }
        // Note: Post-record timeout is now handled by SurveillanceEngineGpu
        // The encoder just writes frames until explicitly told to stop
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
        //   1. Stop drainer thread (waits for current drain cycle to finish)
        //   2. Do one final synchronous drain WITH isWritingToFile still true
        //   3. THEN set isWritingToFile=false and close the muxer
        
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
        
        // Step 3: Flush the muxer write queue synchronously.
        // The disk writer thread is already stopped, but drainEncoderInternal()
        // pushed frames to the queue. Write them directly now.
        {
            MuxerPacket packet;
            int flushed = 0;
            while ((packet = muxerWriteQueue.poll()) != null) {
                if (muxerStarted && muxer != null) {
                    muxer.writeSampleData(trackIndex, packet.data, packet.info);
                    if (firstFramePtsUs < 0) firstFramePtsUs = packet.info.presentationTimeUs;
                    lastFramePtsUs = packet.info.presentationTimeUs;
                    recordedFrames++;
                    flushed++;
                }
            }
            if (flushed > 0) {
                logger.info("Final muxer queue flush: " + flushed + " frames written");
            }
        }
        
        // Step 4: NOW it's safe to flip the flag — no more writers.
        isWritingToFile = false;
        
        // Stop muxer (may throw if no frames were written)
        try {
            if (muxerStarted) {
                muxer.stop();
                muxerStarted = false;
            }
        } catch (Exception e) {
            logger.warn("Muxer stop error (may have had no frames): " + e.getMessage());
            muxerStarted = false;
        }
        
        try {
            if (muxer != null) {
                muxer.release();
                muxer = null;
            }
        } catch (Exception e) {
            logger.warn("Muxer release error: " + e.getMessage());
            muxer = null;
        }
        
        // Rename temp to final, or delete if too small (corrupt/empty)
        if (tempFile != null && tempFile.exists()) {
            if (recordedFrames > 0 && tempFile.length() > 1024) {
                File finalFile = new File(outputPath);
                if (tempFile.renameTo(finalFile)) {
                    // Use actual PTS range for accurate duration (not recordedFrames/fps
                    // which is misleading when pre-record frames are included)
                    float durationSec = (firstFramePtsUs >= 0 && lastFramePtsUs > firstFramePtsUs)
                            ? (lastFramePtsUs - firstFramePtsUs) / 1_000_000.0f
                            : recordedFrames / (float) fps;
                    logger.info(String.format("Event saved: %s (segment %d, %d frames, %.1f sec, %d KB, codec=%s, bitrate=%d Mbps)",
                            finalFile.getName(), segmentNumber, recordedFrames, durationSec, finalFile.length() / 1024,
                            codecMimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "H.265" : "H.264",
                            bitrate / 1_000_000));
                    
                    try {
                        TelegramNotifier.notifyVideoRecorded(
                                finalFile.getAbsolutePath(), null, (int) durationSec);
                    } catch (Exception e) {
                        logger.warn("Failed to emit video notification: " + e.getMessage());
                    }
                } else {
                    logger.error("Failed to rename temp file — deleting orphan");
                    tempFile.delete();
                }
            } else {
                // Empty or corrupt recording — delete the tmp file
                logger.warn("Deleting empty/corrupt temp file: " + tempFile.getName() + 
                        " (frames=" + recordedFrames + ", size=" + tempFile.length() + ")");
                tempFile.delete();
            }
        }
        
        // Reset state
        recordedFrames = 0;
        firstFramePtsUs = -1;
        lastFramePtsUs = -1;
        postRecordStopTime = 0;
        segmentStartTime = 0;
        segmentNumber = 0;
        segmentBasePath = null;
        
        // Restart drainer thread — encoder is still alive, just not writing to file.
        // Pre-record buffer and streaming still need draining.
        startDrainerThread();
        
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
     * Changes the encoder bitrate dynamically.
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
                logger.info( "Bitrate changed to: " + (newBitrate / 1_000_000) + " Mbps");
            } catch (Exception e) {
                logger.error( "Failed to change bitrate", e);
            }
        }
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
        // SOTA: Stop drainer thread first
        stopDrainerThread();
        
        if (recording) {
            stopRecording();
        }
        
        if (encoder != null) {
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
        
        logger.info( "Released");
    }
    
    // ==================== SOTA: Background Drainer Thread ====================
    
    /**
     * Starts the background drainer thread.
     * This moves SD card I/O off the GL thread to prevent freezes.
     */
    private void startDrainerThread() {
        if (drainerRunning) {
            logger.warn("Drainer thread already running");
            return;
        }
        
        drainerRunning = true;
        drainerThread = new Thread(() -> {
            logger.info("Encoder drainer thread started");
            while (drainerRunning) {
                try {
                    // Drain the encoder (SD card I/O happens here, not on GL thread)
                    drainEncoderInternal();
                    
                    // Don't burn CPU - wait a tiny bit for new frames
                    Thread.sleep(DRAIN_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Drainer error: " + e.getMessage());
                }
            }
            logger.info("Encoder drainer thread stopped");
        }, "GpuEncoderDrainer");
        
        drainerThread.setPriority(Thread.NORM_PRIORITY);
        drainerThread.start();
        
        // Start disk writer thread (handles muxer I/O separately from encoder dequeue)
        startDiskWriterThread();
    }
    
    /**
     * Stops the background drainer thread.
     */
    private void stopDrainerThread() {
        drainerRunning = false;
        if (drainerThread != null) {
            try {
                drainerThread.interrupt();
                drainerThread.join(500);
            } catch (InterruptedException e) {
                // Ignore
            }
            drainerThread = null;
        }
        
        // Stop disk writer after drainer (drainer may still be pushing to the queue)
        stopDiskWriterThread();
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
        diskWriterThread = new Thread(() -> {
            logger.info("Disk writer thread started");
            while (diskWriterRunning || !muxerWriteQueue.isEmpty()) {
                try {
                    MuxerPacket packet = muxerWriteQueue.poll();
                    if (packet != null) {
                        if (muxerStarted && muxer != null) {
                            muxer.writeSampleData(trackIndex, packet.data, packet.info);
                            if (firstFramePtsUs < 0) firstFramePtsUs = packet.info.presentationTimeUs;
                            lastFramePtsUs = packet.info.presentationTimeUs;
                            recordedFrames++;
                        }
                    } else {
                        // Queue empty — sleep briefly to avoid busy-waiting
                        Thread.sleep(4);
                    }
                } catch (InterruptedException e) {
                    // Drain remaining packets before exiting
                    MuxerPacket remaining;
                    while ((remaining = muxerWriteQueue.poll()) != null) {
                        try {
                            if (muxerStarted && muxer != null) {
                                muxer.writeSampleData(trackIndex, remaining.data, remaining.info);
                                if (firstFramePtsUs < 0) firstFramePtsUs = remaining.info.presentationTimeUs;
                                lastFramePtsUs = remaining.info.presentationTimeUs;
                                recordedFrames++;
                            }
                        } catch (Exception ex) {
                            logger.warn("Disk writer flush error: " + ex.getMessage());
                        }
                    }
                    break;
                } catch (Exception e) {
                    logger.error("Disk writer error: " + e.getMessage());
                }
            }
            logger.info("Disk writer thread stopped");
        }, "GpuDiskWriter");
        
        // Lower priority than drainer — SD card I/O should never preempt encoder dequeue
        diskWriterThread.setPriority(Thread.MIN_PRIORITY + 1);
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
    
    /**
     * Internal drain method called by background thread.
     * Handles all encoder output and SD card I/O.
     */
    private void drainEncoderInternal() {
        if (encoder == null) {
            return;
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
        if (flushInProgress && muxerStarted) {
            int flushedCount = 0;
            H264CircularBuffer.Packet queuedPacket;
            while ((queuedPacket = pendingFlushQueue.poll()) != null) {
                // Push pre-record packets to the muxer write queue (same path as live frames)
                muxerWriteQueue.add(new MuxerPacket(queuedPacket.data, queuedPacket.info));
                flushedCount++;
            }
            if (flushedCount > 0) {
                logger.info("Async flush complete: " + flushedCount + " pre-record frames queued for disk write");
            }
            flushInProgress = false;
        }
        
        // Check if segment rotation needed (only when actively writing to file)
        if (isWritingToFile && segmentStartTime > 0) {
            long elapsed = System.currentTimeMillis() - segmentStartTime;
            if (elapsed >= SEGMENT_DURATION_MS) {
                logger.info("Segment duration reached (" + (elapsed / 1000) + "s), rotating to new file...");
                rotateSegment();
            }
        }
        
        MediaCodec.BufferInfo bufferInfo = reusableBufferInfo;
        
        while (true) {
            int outputBufferIndex;
            try {
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
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
                }
                
                if (recording && !muxerStarted) {
                    trackIndex = muxer.addTrack(format);
                    muxer.start();
                    muxerStarted = true;
                    logger.info( "Muxer started (track=" + trackIndex + ")");
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
                
                if (outputBuffer != null && bufferInfo.size > 0) {
                    // ALWAYS add to circular buffer (for pre-record) - unless stream-only mode
                    if (usePreRecordBuffer && preRecordBuffer != null) {
                        preRecordBuffer.add(outputBuffer, bufferInfo);
                    }
                    
                    // PATH A: Write to disk (if event recording active)
                    // SOTA: Don't write to muxer directly — push to the muxer write queue.
                    // The disk writer thread handles the actual SD card I/O, preventing
                    // I/O stalls from blocking the encoder dequeue loop.
                    if (isWritingToFile && muxerStarted && !flushInProgress) {
                        muxerWriteQueue.add(new MuxerPacket(outputBuffer, bufferInfo));
                    }
                    
                    // PATH B: Send to network (if streaming)
                    if (streamCallback != null && streamHeadersSent) {
                        try {
                            // Duplicate buffer to avoid interfering with muxer
                            ByteBuffer streamBuffer = outputBuffer.duplicate();
                            streamBuffer.position(bufferInfo.offset);
                            streamBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            streamCallback.onH264Packet(streamBuffer, bufferInfo);
                        } catch (Exception e) {
                            logger.error("Stream callback error", e);
                        }
                    }
                }
                
                // Release output buffer
                encoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
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
        
        logger.info("Rotating segment " + segmentNumber + " - closing current file");
        
        // Close current muxer
        try {
            if (muxerStarted) {
                muxer.stop();
                muxerStarted = false;
            }
        } catch (Exception e) {
            logger.warn("Muxer stop error during rotation: " + e.getMessage());
            muxerStarted = false;
        }
        
        try {
            if (muxer != null) {
                muxer.release();
                muxer = null;
            }
        } catch (Exception e) {
            logger.warn("Muxer release error during rotation: " + e.getMessage());
            muxer = null;
        }
        
        // Rename temp to final, or delete if empty
        if (tempFile != null && tempFile.exists()) {
            if (recordedFrames > 0 && tempFile.length() > 1024) {
                File finalFile = new File(outputPath);
                if (tempFile.renameTo(finalFile)) {
                    float durationSec = recordedFrames / (float) fps;
                    logger.info(String.format("Segment %d saved: %s (%d frames, %.1f sec, %d KB)",
                            segmentNumber, finalFile.getName(), recordedFrames, durationSec, finalFile.length() / 1024));
                } else {
                    logger.error("Failed to rename segment " + segmentNumber + " — deleting orphan");
                    tempFile.delete();
                }
            } else {
                logger.warn("Deleting empty segment " + segmentNumber + " tmp file");
                tempFile.delete();
            }
        }
        
        // Start new segment
        segmentNumber++;
        String newPath = segmentBasePath + "_" + segmentNumber + ".mp4";
        
        try {
            this.outputPath = newPath;
            tempFile = new File(newPath + ".tmp");
            
            muxer = new MediaMuxer(tempFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            
            if (savedFormat != null) {
                trackIndex = muxer.addTrack(savedFormat);
                muxer.start();
                muxerStarted = true;
            }
            
            recordedFrames = 0;
            segmentStartTime = System.currentTimeMillis();
            
            logger.info("Segment " + segmentNumber + " started: " + tempFile.getName());
            
        } catch (Exception e) {
            logger.error("Failed to start new segment — stopping recording", e);
            // Clean up the failed tmp file
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            isWritingToFile = false;
            recording = false;
        }
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
     * Clean up orphaned .tmp files that were left behind by crashed recordings.
     * Deletes .tmp files older than 5 minutes.
     */
    public static void cleanupOrphanedTmpFiles(File directory) {
        if (!directory.exists() || !directory.isDirectory()) return;
        
        File[] tmpFiles = directory.listFiles((dir, name) -> name.endsWith(".tmp"));
        if (tmpFiles == null) return;
        
        long now = System.currentTimeMillis();
        for (File tmp : tmpFiles) {
            long age = now - tmp.lastModified();
            if (age > 5 * 60 * 1000) { // Older than 5 minutes
                long size = tmp.length();
                if (tmp.delete()) {
                    logger.info("Cleaned orphaned tmp: " + tmp.getName() + " (" + (size / 1024) + " KB, age=" + (age / 1000) + "s)");
                }
            }
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
                    logger.info("Deleted old segment: " + file.getName() + 
                            " (" + (fileSize / 1024) + " KB)");
                } else {
                    logger.warn("Failed to delete file: " + file.getName());
                }
            }
        }
    }
}
