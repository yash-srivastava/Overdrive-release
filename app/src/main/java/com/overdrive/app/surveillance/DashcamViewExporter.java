package com.overdrive.app.surveillance;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.camera.EGLCore;
import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.removable.RemovableStorageFs;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Offline "Dashcam view" render: decodes the cam_* grid clips (leader) and the dvr_* windshield clips
 * (follower) to OES textures, composites them in one GL pass (dashcam layout of the recorder, issue
 * #1414 contract section 2) and encodes one H.264 MP4 timed by the leader. Owns its own EGL context,
 * decoders and encoder; never touches a recording session. Call {@link #run} on a background thread.
 */
public final class DashcamViewExporter {
    private static final DaemonLogger logger = DaemonLogger.getInstance("DashcamViewExport");

    public static final int MAX_OUTPUT_WIDTH = 1920;
    private static final long CODEC_CREATE_TIMEOUT_MS = 5_000L;
    // Frame-available wait; re-checks the stop flag every slice.
    private static final long FRAME_WAIT_MS = 500L;
    // Wait slice: a stop request (recording / ACC ON) is seen within one slice.
    private static final long POLL_SLICE_MS = 20L;
    // Leader stall (no frame and no EOS) before the job fails.
    private static final long LEADER_STALL_MS = 10_000L;
    // Longest a leader frame waits for the follower to catch up; the top band holds meanwhile.
    private static final long FOLLOWER_BUDGET_MS = 500L;
    private static final int AUDIO_BUFFER_BYTES = 256 * 1024;

    /** Failure with a short machine code for the API ("cancelled", "acc_on", "codec", ...). */
    public static final class ExportException extends Exception {
        public final String code;
        /** True for a stop request (cancel, ACC ON, recording, ...), as opposed to a media failure. */
        public final boolean stop;

        public ExportException(String code, String message) {
            this(code, message, false);
        }

        ExportException(String code, String message, boolean stop) {
            super(message);
            this.code = code;
            this.stop = stop;
        }
    }

    /**
     * Stops a running export from any thread without waiting for it: the codecs are stopped and
     * released on a short-lived releaser thread at once, the worker unwinds on its next slice.
     * Never blocks the caller (safe from the recorder's start path).
     */
    public static final class Abort {
        private volatile String reason;
        private Session session;

        public String reason() {
            return reason;
        }

        public void abort(String why) {
            if (reason == null) reason = why != null ? why : "cancelled";
            final Session s;
            synchronized (this) {
                s = session;
            }
            if (s == null || !s.markReleasing()) return;
            Thread t = new Thread(s::releaseCodecs, "DvExportRelease");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
        }

        synchronized void attach(Session s) {
            session = s;
        }
    }

    /** Grid geometry of one leader clip (sidecar "grid" block, already validated). */
    public static final class Grid {
        public int width;
        public int height;
        public float aspect;
        public boolean raw;
        public int strip;
        public float split;
        /** Tile index (0 TL, 1 TR, 2 BL, 3 BR) shown in each bottom third, left to right. */
        public final int[] cellTiles = new int[3];
    }

    /** One clip played over the wall range [fromWall, toWall). */
    public static final class Item {
        public final File file;
        public final long clipStartWall;
        public final long fromWall;
        public final long toWall;
        public final Grid grid;

        public Item(File file, long clipStartWall, long fromWall, long toWall, Grid grid) {
            this.file = file;
            this.clipStartWall = clipStartWall;
            this.fromWall = fromWall;
            this.toWall = toWall;
            this.grid = grid;
        }
    }

    /** Job callbacks; called on the export thread. */
    public interface Listener {
        /** Null to continue, else a stop code (cancel, ACC on, recording, storage). Called every slice; keep cheap. */
        String stopReason();

        /** True while the export should hold (cameras busy); polled between frames. */
        boolean shouldPause();

        /** Minimum wall time per exported frame (self-imposed frame rate cap), ms. */
        long minFrameIntervalMs();

        void onProgress(float fraction);
    }

    public static final class Result {
        public int width;
        public int height;
        public int frames;
        public boolean audio;
    }

    private DashcamViewExporter() {}

    /** Renders {@code leaders} (sorted, non-overlapping) with {@code followers} into {@code output}. */
    /**
     * @param encodersInUse / decodersInUse codec instances other work in this process already holds;
     *        the export refuses ("codecs_busy") unless the platform limits leave room for its own three.
     */
    public static Result run(List<Item> leaders, List<Item> followers, long fromWall, long toWall,
                             float strength, File output, int encodersInUse, int decodersInUse,
                             Abort abort, Listener listener) throws ExportException {
        if (leaders.isEmpty()) throw new ExportException("unavailable", "No grid clips in range");
        Session session = new Session(leaders, followers, fromWall, toWall, strength, output, listener);
        session.encodersInUse = encodersInUse;
        session.decodersInUse = decodersInUse;
        session.abort = abort != null ? abort : new Abort();
        session.abort.attach(session);
        try {
            if (session.abort.reason() != null) session.markReleasing();
            return session.render();
        } finally {
            session.abort.attach(null);
        }
    }

    /** Largest instance count any codec for {@code mime} reports (0 when none / unknown). */
    static int maxInstances(String mime, boolean encoder) {
        int best = 0;
        try {
            for (MediaCodecInfo info : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                    .getCodecInfos()) {
                if (info.isEncoder() != encoder) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mime)) continue;
                    best = Math.max(best, info.getCapabilitiesForType(type).getMaxSupportedInstances());
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        return best;
    }

    private static String videoMime(File file) {
        MediaExtractor ex = new MediaExtractor();
        try {
            RemovableStorageFs.setDataSource(ex, file);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) return mime;
            }
        } catch (Throwable ignored) {
        } finally {
            ex.release();
        }
        return null;
    }

    // ------------------------------------------------------------------ GL program

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTex = aTexCoord;\n" +
            "}\n";

    // Picture space p is y down; each OES input has its own st matrix (texture space, y up).
    // rectifyTile is the recorder's / players' division model (GpuMosaicRecorder, PlaybackGl).
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "uniform samplerExternalOES uGrid;\n" +
            "uniform samplerExternalOES uDvr;\n" +
            "uniform mat4 uStGrid;\n" +
            "uniform mat4 uStDvr;\n" +
            "uniform vec2 uK;\n" +
            "uniform float uAspect;\n" +
            "uniform vec2 uHalfTexel;\n" +
            "uniform float uStrip;\n" +
            "uniform float uSplit;\n" +
            "uniform float uCropY;\n" +
            "uniform float uDvrReady;\n" +
            "uniform vec2 uCell0;\n" +
            "uniform vec2 uCell1;\n" +
            "uniform vec2 uCell2;\n" +
            "varying vec2 vTex;\n" +
            "vec4 gridAt(vec2 q) { return texture2D(uGrid, (uStGrid * vec4(q.x, 1.0 - q.y, 0.0, 1.0)).xy); }\n" +
            "vec2 rectifyTile(vec2 t) {\n" +
            "    if (uK.x == 0.0 && uK.y == 0.0) return t;\n" +
            "    vec2 nxy = t * 2.0 - 1.0;\n" +
            "    vec2 nxyAspect = vec2(nxy.x, nxy.y * uAspect);\n" +
            "    float r2 = dot(nxyAspect, nxyAspect);\n" +
            "    float invDenom = 1.0 / (1.0 + uK.x * r2 + uK.y * r2 * r2);\n" +
            "    float aspect2 = uAspect * uAspect;\n" +
            "    float zoom = 1.0 + uK.x * aspect2 + uK.y * aspect2 * aspect2;\n" +
            "    vec2 srcAspect = nxyAspect * invDenom * zoom;\n" +
            "    vec2 camUV = vec2(srcAspect.x, srcAspect.y / uAspect) * 0.5 + 0.5;\n" +
            "    return clamp(camUV, vec2(0.0), vec2(1.0));\n" +
            "}\n" +
            "void main() {\n" +
            "    vec2 p = vec2(vTex.x, 1.0 - vTex.y);\n" +
            "    if (p.y < uStrip) { gl_FragColor = gridAt(p); return; }\n" +
            "    if (p.y < uSplit) {\n" +
            "        if (uDvrReady < 0.5) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }\n" +
            "        vec2 q = vec2(p.x, uCropY + (p.y / uSplit) * (1.0 - 2.0 * uCropY));\n" +
            "        gl_FragColor = texture2D(uDvr, (uStDvr * vec4(q.x, 1.0 - q.y, 0.0, 1.0)).xy);\n" +
            "        return;\n" +
            "    }\n" +
            "    float by = (p.y - uSplit) / (1.0 - uSplit);\n" +
            "    float bx = p.x * 3.0;\n" +
            "    float cell = clamp(floor(bx), 0.0, 2.0);\n" +
            "    vec2 o = cell < 0.5 ? uCell0 : (cell < 1.5 ? uCell1 : uCell2);\n" +
            "    vec2 t = rectifyTile(vec2(bx - cell, by));\n" +
            "    vec2 src = clamp(o + t * 0.5, o + uHalfTexel, o + 0.5 - uHalfTexel);\n" +
            "    if (uStrip > 0.0 && o.y < 0.25) src.y = max(src.y, uStrip + uHalfTexel.y);\n" +
            "    gl_FragColor = gridAt(src);\n" +
            "}\n";

    private static final float[] QUAD = {
            // x, y, u, v
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
    };

    /** dashcam windshield crop for a srcAspect image in the top band (GpuMosaicRecorder.windshieldCropY). */
    static float cropY(int gridW, int gridH, float split, int dvrW, int dvrH) {
        if (gridW <= 0 || gridH <= 0 || dvrW <= 0 || dvrH <= 0 || split <= 0f) return 0f;
        float bandAspect = ((float) gridW / gridH) / split;
        float srcAspect = (float) dvrW / dvrH;
        if (bandAspect <= srcAspect) return 0f;
        return Math.max(0f, Math.min(0.45f, (1f - srcAspect / bandAspect) * 0.5f));
    }

    /** Output size: the grid frame's aspect, at most {@link #MAX_OUTPUT_WIDTH} wide, even dimensions. */
    static int[] outputSize(int gridW, int gridH, int maxWidth) {
        int w = Math.min(gridW, maxWidth) & ~15;
        int h = Math.round((float) w * gridH / gridW) & ~1;
        return new int[]{w, h};
    }

    /** Bits per second for the share copy: about 0.12 bpp, 2..8 Mbps. */
    public static int bitrateFor(int w, int h, int fps) {
        long bps = Math.round(w * (double) h * Math.max(1, fps) * 0.12);
        return (int) Math.max(2_000_000L, Math.min(8_000_000L, bps));
    }

    // ------------------------------------------------------------------ session

    private static final class FrameSignal implements SurfaceTexture.OnFrameAvailableListener {
        private boolean available;

        @Override
        public synchronized void onFrameAvailable(SurfaceTexture st) {
            available = true;
            notifyAll();
        }

        synchronized boolean await(long timeoutMs, Session stop) throws ExportException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (!available) {
                stop.checkStop();
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) return false;
                try {
                    wait(Math.min(left, POLL_SLICE_MS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            available = false;
            return true;
        }
    }

    /** One decoder rendering to an OES texture. */
    private static final class VideoIn {
        static final int FRAME = 1, EOS = 0, TIMEOUT = -1;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaExtractor extractor;
        MediaCodec codec;
        int width;
        int height;
        int fps;
        boolean inputDone;
        boolean outputDone;
        int pending = -1;
        long pendingUs;

        Session session;

        void open(Session owner, File file, Surface surface, long seekUs) throws Exception {
            session = owner;
            inputDone = false;
            outputDone = false;
            pending = -1;
            extractor = new MediaExtractor();
            RemovableStorageFs.setDataSource(extractor, file);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new ExportException("source", "No video track in " + file.getName());
            extractor.selectTrack(track);
            width = format.getInteger(MediaFormat.KEY_WIDTH);
            height = format.getInteger(MediaFormat.KEY_HEIGHT);
            fps = format.containsKey(MediaFormat.KEY_FRAME_RATE) ? safeInt(format, MediaFormat.KEY_FRAME_RATE) : 0;
            if (seekUs > 0) extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            try {
                format.setInteger(MediaFormat.KEY_PRIORITY, 1);
            } catch (Throwable ignored) {
            }
            codec = session.createCodec(format.getString(MediaFormat.KEY_MIME), false);
            codec.configure(format, surface, null, 0);
            codec.start();
        }

        /** FRAME when an output frame is pending, EOS, or TIMEOUT after {@code timeoutMs}. */
        int poll(long timeoutMs) {
            if (pending >= 0) return FRAME;
            if (outputDone) return EOS;
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(0);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = buf != null ? extractor.readSampleData(buf, 0) : -1;
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 5_000L);
                if (out >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (eos) outputDone = true;
                    if (info.size > 0 || !eos) {
                        pending = out;
                        pendingUs = info.presentationTimeUs;
                        return FRAME;
                    }
                    codec.releaseOutputBuffer(out, false);
                    return EOS;
                }
                if (System.nanoTime() >= deadline) return TIMEOUT;
            }
        }

        void release(boolean render) {
            if (pending < 0) return;
            int idx = pending;
            pending = -1;
            codec.releaseOutputBuffer(idx, render);
        }

        void close() {
            if (codec != null) {
                if (session == null || session.unregister(codec)) {
                    try { codec.stop(); } catch (Throwable ignored) {}
                    try { codec.release(); } catch (Throwable ignored) {}
                }
                codec = null;
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) {}
                extractor = null;
            }
            pending = -1;
        }
    }

    private static int safeInt(MediaFormat f, String key) {
        try {
            return f.getInteger(key);
        } catch (Throwable t) {
            try {
                return Math.round(f.getFloat(key));
            } catch (Throwable t2) {
                return 0;
            }
        }
    }

    private static final class Session {
        final List<Item> leaders;
        final List<Item> followers;
        final long fromWall;
        final long toWall;
        final float strength;
        final File output;
        final Listener listener;
        final Result result = new Result();
        Abort abort;
        private final Object codecLock = new Object();
        private final java.util.ArrayList<MediaCodec> liveCodecs = new java.util.ArrayList<>(3);
        private boolean releasing;
        int encodersInUse;
        int decodersInUse;

        HandlerThread frameThread;
        EGLCore egl;
        EGLSurface eglSurface;
        MediaCodec encoder;
        Surface encoderSurface;
        MediaMuxer muxer;
        int program;
        int texGrid;
        int texDvr;
        SurfaceTexture stGrid;
        SurfaceTexture stDvr;
        Surface gridSurface;
        Surface dvrSurface;
        final FrameSignal gridSignal = new FrameSignal();
        final FrameSignal dvrSignal = new FrameSignal();
        final float[] mGrid = new float[16];
        final float[] mDvr = new float[16];
        FloatBuffer quad;
        int aPosition, aTexCoord, uGridTex, uDvrTex, uStGrid, uStDvr, uK, uAspect, uHalfTexel, uStrip, uSplit, uCropY,
                uDvrReady, uCell0, uCell1, uCell2;

        final VideoIn leader = new VideoIn();
        final VideoIn follower = new VideoIn();
        int followerIndex = -1;
        boolean dvrReady;
        float dvrCropY;

        MediaFormat audioFormat;
        MediaExtractor audio;
        ByteBuffer audioBuffer;
        final MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        boolean audioDone;
        long audioClipStartUs;
        long audioFromUs;
        long audioToUs;
        int videoTrack = -1;
        int audioTrack = -1;
        boolean muxerStarted;
        long lastVideoPtsUs = -1;
        long lastAudioPtsUs = -1;
        int outW;
        int outH;

        Session(List<Item> leaders, List<Item> followers, long fromWall, long toWall, float strength,
                File output, Listener listener) {
            this.leaders = leaders;
            this.followers = followers;
            this.fromWall = fromWall;
            this.toWall = toWall;
            this.strength = Math.max(0f, Math.min(1f, strength));
            this.output = output;
            this.listener = listener;
        }

        Result render() throws ExportException {
            boolean ok = false;
            try {
                setUp();
                for (Item item : leaders) {
                    renderLeader(item);
                }
                finishEncoding();
                if (result.frames == 0 || !muxerStarted) throw new ExportException("empty", "No frames were exported");
                muxer.stop();
                ok = true;
                result.width = outW;
                result.height = outH;
                result.audio = audioTrack >= 0;
                return result;
            } catch (ExportException e) {
                throw e;
            } catch (Throwable t) {
                String reason = abort != null ? abort.reason() : null;
                if (reason != null) throw new ExportException(reason, "Stopped: " + reason, true);
                logger.warn("Export failed: " + t);
                throw new ExportException("failed", t.getMessage() != null ? t.getMessage() : t.toString());
            } finally {
                tearDown(ok);
            }
        }

        void checkStop() throws ExportException {
            String reason = abort != null ? abort.reason() : null;
            if (reason == null && Thread.currentThread().isInterrupted()) reason = "cancelled";
            if (reason == null && listener != null) reason = listener.stopReason();
            if (reason != null) {
                if (abort != null) abort.abort(reason);
                throw new ExportException(reason, "Stopped: " + reason, true);
            }
        }

        /** False when already releasing. */
        boolean markReleasing() {
            synchronized (codecLock) {
                if (releasing) return false;
                releasing = true;
                return true;
            }
        }

        /** Releaser thread: stop + release every live codec now; the worker's later close skips them. */
        void releaseCodecs() {
            long t0 = System.nanoTime();
            java.util.ArrayList<MediaCodec> list;
            synchronized (codecLock) {
                releasing = true;
                list = new java.util.ArrayList<>(liveCodecs);
                liveCodecs.clear();
            }
            for (MediaCodec c : list) {
                try { c.stop(); } catch (Throwable ignored) {}
                try { c.release(); } catch (Throwable ignored) {}
            }
            CameraLoadProbe.noteBackgroundCodecs(false);
            logger.info("Export codecs released in " + (System.nanoTime() - t0) / 1_000_000 + "ms ("
                    + list.size() + ")");
        }

        /** True when the caller still owns {@code c} (not taken by the releaser). */
        boolean unregister(MediaCodec c) {
            synchronized (codecLock) {
                boolean owned = liveCodecs.remove(c);
                if (liveCodecs.isEmpty()) CameraLoadProbe.noteBackgroundCodecs(false);
                return owned;
            }
        }

        /** Creates a codec on a helper thread, waited for in slices; refuses while a stop is pending. */
        MediaCodec createCodec(String mime, boolean encoder) throws Exception {
            checkStop();
            final LateResourceHandoff<MediaCodec> handoff = new LateResourceHandoff<>();
            final Exception[] error = {null};
            Thread t = new Thread(() -> {
                try {
                    MediaCodec c = encoder ? MediaCodec.createEncoderByType(mime) : MediaCodec.createDecoderByType(mime);
                    if (!handoff.publish(c) && c != null) c.release();
                } catch (Exception e) {
                    error[0] = e;
                }
            }, "DvExportCodecCreate");
            t.setDaemon(true);
            t.start();
            long deadline = System.nanoTime() + CODEC_CREATE_TIMEOUT_MS * 1_000_000L;
            while (t.isAlive() && System.nanoTime() < deadline) {
                t.join(POLL_SLICE_MS);
                if (t.isAlive() && abort.reason() != null) break;
            }
            if (t.isAlive()) {
                MediaCodec late = handoff.abandon();
                if (late != null) {
                    try { late.release(); } catch (Throwable ignored) {}
                }
                checkStop();
                throw new ExportException("codec", "Codec create timed out (" + mime + ")");
            }
            if (error[0] != null) throw error[0];
            MediaCodec c = handoff.take();
            if (c == null) throw new ExportException("codec", "No codec for " + mime);
            synchronized (codecLock) {
                if (!releasing) {
                    liveCodecs.add(c);
                    CameraLoadProbe.noteBackgroundCodecs(true);
                    return c;
                }
            }
            try { c.release(); } catch (Throwable ignored) {}
            checkStop();
            throw new ExportException("cancelled", "Cancelled", true);
        }

        private void setUp() throws Exception {
            Grid g0 = leaders.get(0).grid;
            // Frame rate and audio format come from the first leader clip.
            int fps = 15;
            MediaExtractor probe = new MediaExtractor();
            try {
                RemovableStorageFs.setDataSource(probe, leaders.get(0).file);
                for (int i = 0; i < probe.getTrackCount(); i++) {
                    MediaFormat f = probe.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        int r = safeInt(f, MediaFormat.KEY_FRAME_RATE);
                        if (r >= 1 && r <= 60) fps = r;
                    } else if (mime.startsWith("audio/") && audioFormat == null) {
                        audioFormat = f;
                    }
                }
            } finally {
                probe.release();
            }

            checkCodecRoom();
            encoder = createCodec(MediaFormat.MIMETYPE_VIDEO_AVC, true);
            int[] size = outputSize(g0.width, g0.height, MAX_OUTPUT_WIDTH);
            if (!sizeSupported(encoder, size[0], size[1])) size = outputSize(g0.width, g0.height, 1280);
            if (!sizeSupported(encoder, size[0], size[1])) {
                throw new ExportException("codec", "Encoder cannot encode " + size[0] + "x" + size[1]);
            }
            outW = size[0];
            outH = size[1];
            configureEncoder(fps);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            egl = new EGLCore();
            eglSurface = egl.createWindowSurface(encoderSurface);
            egl.makeCurrent(eglSurface);
            program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) throw new ExportException("gl", "Shader build failed");
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uGridTex = GLES20.glGetUniformLocation(program, "uGrid");
            uDvrTex = GLES20.glGetUniformLocation(program, "uDvr");
            uStGrid = GLES20.glGetUniformLocation(program, "uStGrid");
            uStDvr = GLES20.glGetUniformLocation(program, "uStDvr");
            uK = GLES20.glGetUniformLocation(program, "uK");
            uAspect = GLES20.glGetUniformLocation(program, "uAspect");
            uHalfTexel = GLES20.glGetUniformLocation(program, "uHalfTexel");
            uStrip = GLES20.glGetUniformLocation(program, "uStrip");
            uSplit = GLES20.glGetUniformLocation(program, "uSplit");
            uCropY = GLES20.glGetUniformLocation(program, "uCropY");
            uDvrReady = GLES20.glGetUniformLocation(program, "uDvrReady");
            uCell0 = GLES20.glGetUniformLocation(program, "uCell0");
            uCell1 = GLES20.glGetUniformLocation(program, "uCell1");
            uCell2 = GLES20.glGetUniformLocation(program, "uCell2");
            quad = GlUtil.createFloatBuffer(QUAD);

            frameThread = new HandlerThread("DvExportFrames", android.os.Process.THREAD_PRIORITY_LOWEST);
            frameThread.start();
            Handler handler = new Handler(frameThread.getLooper());
            texGrid = GlUtil.createExternalTexture();
            texDvr = GlUtil.createExternalTexture();
            stGrid = new SurfaceTexture(texGrid);
            stGrid.setOnFrameAvailableListener(gridSignal, handler);
            stDvr = new SurfaceTexture(texDvr);
            stDvr.setOnFrameAvailableListener(dvrSignal, handler);
            gridSurface = new Surface(stGrid);
            dvrSurface = new Surface(stDvr);
            android.opengl.Matrix.setIdentityM(mGrid, 0);
            android.opengl.Matrix.setIdentityM(mDvr, 0);

            muxer = RemovableStorageFs.newMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (audioFormat != null) audioBuffer = ByteBuffer.allocateDirect(AUDIO_BUFFER_BYTES);
        }

        /** Refuses before creating anything when the platform codec limits leave no room for the export. */
        private void checkCodecRoom() throws ExportException {
            String gridMime = videoMime(leaders.get(0).file);
            String dvrMime = followers.isEmpty() ? null : videoMime(followers.get(0).file);
            if (gridMime == null) throw new ExportException("source", "Unreadable grid clip");
            int enc = maxInstances(MediaFormat.MIMETYPE_VIDEO_AVC, true);
            boolean sameDecoder = dvrMime == null || dvrMime.equalsIgnoreCase(gridMime);
            int decGrid = maxInstances(gridMime, false);
            int decDvr = dvrMime != null ? maxInstances(dvrMime, false) : decGrid;
            boolean ok = enc >= encodersInUse + 1
                    && (sameDecoder ? decGrid >= decodersInUse + 2
                                    : decGrid >= decodersInUse + 1 && decDvr >= decodersInUse + 1);
            if (!ok) {
                throw new ExportException("codecs_busy", "Codec limits: enc=" + enc + " decGrid=" + decGrid
                        + " decDvr=" + decDvr + " inUse=" + encodersInUse + "/" + decodersInUse);
            }
        }

        private boolean sizeSupported(MediaCodec codec, int w, int h) {
            if (w < 16 || h < 16) return false;
            try {
                MediaCodecInfo.VideoCapabilities caps = codec.getCodecInfo()
                        .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
                return caps == null || caps.isSizeSupported(w, h);
            } catch (Throwable t) {
                return true;
            }
        }

        private void configureEncoder(int fps) throws ExportException {
            int bitrate = bitrateFor(outW, outH, fps);
            for (int attempt = 0; attempt < 2; attempt++) {
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                f.setInteger(MediaFormat.KEY_PRIORITY, 1);
                // Baseline like the recorder (plays on every phone, iOS included).
                if (attempt == 0) f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                try {
                    encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    logger.info("Encoder " + encoder.getName() + " " + outW + "x" + outH + " @" + fps
                            + "fps " + bitrate + "bps");
                    return;
                } catch (Throwable t) {
                    logger.warn("Encoder configure failed (attempt " + attempt + "): " + t.getMessage());
                    try { encoder.reset(); } catch (Throwable ignored) {}
                }
            }
            throw new ExportException("codec", "Encoder configure failed");
        }

        private void renderLeader(Item item) throws Exception {
            checkStop();
            leader.open(this, item.file, gridSurface, Math.max(0L, (item.fromWall - item.clipStartWall) * 1000L));
            openAudio(item);
            long lastFrameNs = System.nanoTime();
            try {
                while (true) {
                    checkStop();
                    if (holdForCameras()) lastFrameNs = System.nanoTime();
                    int r = leader.poll(POLL_SLICE_MS);
                    if (r == VideoIn.EOS) break;
                    if (r == VideoIn.TIMEOUT) {
                        // Some decoders never flag EOS after the last input: treat a drained tail as the end.
                        if (leader.inputDone && System.nanoTime() - lastFrameNs > 2_000_000_000L) break;
                        if (System.nanoTime() - lastFrameNs > LEADER_STALL_MS * 1_000_000L) {
                            throw new ExportException("decoder", "Grid decoder stalled on " + item.file.getName());
                        }
                        drainEncoder(false);
                        continue;
                    }
                    lastFrameNs = System.nanoTime();
                    long wallUs = item.clipStartWall * 1000L + leader.pendingUs;
                    if (wallUs < item.fromWall * 1000L) {
                        leader.release(false);
                        continue;
                    }
                    if (wallUs >= item.toWall * 1000L) {
                        leader.release(false);
                        break;
                    }
                    leader.release(true);
                    if (!gridSignal.await(FRAME_WAIT_MS, this)) continue;
                    stGrid.updateTexImage();
                    stGrid.getTransformMatrix(mGrid);
                    advanceFollower(wallUs);
                    long ptsUs = wallUs - fromWall * 1000L;
                    if (ptsUs <= lastVideoPtsUs) continue;
                    draw(item.grid);
                    egl.swapBuffersWithTimestamp(eglSurface, ptsUs * 1000L);
                    lastVideoPtsUs = ptsUs;
                    paceFrame();
                    result.frames++;
                    drainEncoder(false);
                    pumpAudio(wallUs);
                    if (listener != null) {
                        listener.onProgress((float) (wallUs / 1000L - fromWall) / Math.max(1L, toWall - fromWall));
                    }
                }
            } finally {
                leader.close();
                closeAudio();
            }
        }

        private long nextFrameNs;
        private int followerFailed = -1;

        /** An unreadable dvr clip leaves the top band black for its span (no retry per frame). */
        private void followerUnreadable(Item f, int index, Throwable t) {
            logger.warn("Windshield clip " + f.file.getName() + " unreadable: " + t.getMessage());
            follower.close();
            followerFailed = index;
            dvrReady = false;
        }

        /** Self-imposed frame rate cap: may run slower than real time. */
        private void paceFrame() throws ExportException {
            long interval = listener != null ? listener.minFrameIntervalMs() : 0L;
            if (interval <= 0) return;
            long now = System.nanoTime();
            if (nextFrameNs == 0L || nextFrameNs < now - interval * 1_000_000L) nextFrameNs = now;
            nextFrameNs += interval * 1_000_000L;
            while (System.nanoTime() < nextFrameNs) {
                checkStop();
                sleepSlice();
            }
        }

        /** Holds (codecs idle, no GPU work) while the listener asks to pause; true when it held. */
        private boolean holdForCameras() throws ExportException {
            if (listener == null || !listener.shouldPause()) return false;
            while (listener.shouldPause()) {
                checkStop();
                sleepSlice();
            }
            nextFrameNs = 0L;
            return true;
        }

        private void sleepSlice() throws ExportException {
            try {
                Thread.sleep(POLL_SLICE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExportException("cancelled", "Cancelled");
            }
        }

        private void advanceFollower(long wallUs) throws Exception {
            int want = -1;
            for (int i = Math.max(0, followerIndex); i < followers.size(); i++) {
                Item f = followers.get(i);
                if (wallUs < f.fromWall * 1000L) break;
                if (wallUs < f.toWall * 1000L) {
                    want = i;
                    break;
                }
            }
            if (want < 0) {
                // Gap: black top band; drop a finished clip's decoder.
                if (follower.codec != null && followerIndex >= 0
                        && wallUs >= followers.get(followerIndex).toWall * 1000L) {
                    follower.close();
                }
                dvrReady = false;
                return;
            }
            Item f = followers.get(want);
            if (want != followerIndex || follower.codec == null) {
                follower.close();
                followerIndex = want;
                dvrReady = false;
                if (want == followerFailed) return;
                try {
                    follower.open(this, f.file, dvrSurface, Math.max(0L, wallUs - f.clipStartWall * 1000L));
                } catch (ExportException e) {
                    if (e.stop || "codec".equals(e.code)) throw e;
                    followerUnreadable(f, want, e);
                    return;
                } catch (Throwable t) {
                    checkStop();
                    followerUnreadable(f, want, t);
                    return;
                }
                Grid g = leaders.get(0).grid;
                dvrCropY = cropY(g.width, g.height, g.split, follower.width, follower.height);
            }
            long deadline = System.nanoTime() + FOLLOWER_BUDGET_MS * 1_000_000L;
            while (System.nanoTime() < deadline) {
                checkStop();
                int r = follower.poll(POLL_SLICE_MS);
                if (r == VideoIn.EOS) break;
                if (r == VideoIn.TIMEOUT) continue;
                long fWallUs = f.clipStartWall * 1000L + follower.pendingUs;
                if (fWallUs > wallUs) break;
                follower.release(true);
                if (dvrSignal.await(FRAME_WAIT_MS, this)) {
                    stDvr.updateTexImage();
                    stDvr.getTransformMatrix(mDvr);
                    dvrReady = true;
                }
            }
        }

        private void draw(Grid g) {
            GLES20.glViewport(0, 0, outW, outH);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texGrid);
            GLES20.glUniform1i(uGridTex, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texDvr);
            GLES20.glUniform1i(uDvrTex, 1);
            GLES20.glUniformMatrix4fv(uStGrid, 1, false, mGrid, 0);
            GLES20.glUniformMatrix4fv(uStDvr, 1, false, mDvr, 0);
            float k = g.raw ? strength : 0f;
            GLES20.glUniform2f(uK, 0.30f * k, 0.10f * k);
            GLES20.glUniform1f(uAspect, g.aspect);
            GLES20.glUniform2f(uHalfTexel, 0.5f / Math.max(1, g.width), 0.5f / Math.max(1, g.height));
            float strip = (g.strip > 0 && g.strip * 2 < g.height) ? (float) g.strip / g.height : 0f;
            GLES20.glUniform1f(uStrip, strip);
            GLES20.glUniform1f(uSplit, g.split);
            GLES20.glUniform1f(uCropY, dvrCropY);
            GLES20.glUniform1f(uDvrReady, dvrReady ? 1f : 0f);
            setCell(uCell0, g.cellTiles[0]);
            setCell(uCell1, g.cellTiles[1]);
            setCell(uCell2, g.cellTiles[2]);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPosition);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private static void setCell(int loc, int tile) {
            GLES20.glUniform2f(loc, (tile % 2) * 0.5f, (tile / 2) * 0.5f);
        }

        private void drainEncoder(boolean endOfStream) throws ExportException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (true) {
                if (endOfStream) checkStop();
                int idx = encoder.dequeueOutputBuffer(encInfo, endOfStream ? 10_000L : 0L);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return;
                    checkStop();
                    if (System.nanoTime() > deadline) {
                        logger.warn("Encoder EOS timed out");
                        return;
                    }
                    continue;
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new ExportException("codec", "Encoder format changed twice");
                    videoTrack = muxer.addTrack(encoder.getOutputFormat());
                    if (audioFormat != null) {
                        try {
                            audioTrack = muxer.addTrack(audioFormat);
                        } catch (Throwable t) {
                            logger.warn("Audio track refused, exporting without sound: " + t.getMessage());
                            audioTrack = -1;
                        }
                    }
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer data = encoder.getOutputBuffer(idx);
                boolean config = (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && encInfo.size > 0 && data != null && muxerStarted) {
                    data.position(encInfo.offset);
                    data.limit(encInfo.offset + encInfo.size);
                    muxer.writeSampleData(videoTrack, data, encInfo);
                }
                encoder.releaseOutputBuffer(idx, false);
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        private void finishEncoding() throws ExportException {
            encoder.signalEndOfInputStream();
            drainEncoder(true);
        }

        private void openAudio(Item item) {
            audioDone = true;
            if (audioFormat == null || audioBuffer == null) return;
            MediaExtractor ex = new MediaExtractor();
            try {
                RemovableStorageFs.setDataSource(ex, item.file);
                int track = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    if (sameAudio(audioFormat, f)) {
                        track = i;
                        break;
                    }
                }
                if (track < 0) {
                    ex.release();
                    return;
                }
                ex.selectTrack(track);
                ex.seekTo(Math.max(0L, (item.fromWall - item.clipStartWall) * 1000L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                audio = ex;
                audioDone = false;
                audioClipStartUs = item.clipStartWall * 1000L;
                audioFromUs = item.fromWall * 1000L;
                audioToUs = item.toWall * 1000L;
            } catch (Throwable t) {
                ex.release();
            }
        }

        private static boolean sameAudio(MediaFormat a, MediaFormat b) {
            try {
                String ma = a.getString(MediaFormat.KEY_MIME), mb = b.getString(MediaFormat.KEY_MIME);
                return ma != null && ma.equals(mb)
                        && a.getInteger(MediaFormat.KEY_SAMPLE_RATE) == b.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        && a.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == b.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } catch (Throwable t) {
                return false;
            }
        }

        /** Copies the clip's audio up to the last video frame's wall time (keeps the file interleaved). */
        private void pumpAudio(long upToWallUs) {
            if (audioDone || audio == null || !muxerStarted || audioTrack < 0) return;
            try {
                while (true) {
                    long t = audio.getSampleTime();
                    if (t < 0) {
                        audioDone = true;
                        return;
                    }
                    long wallUs = audioClipStartUs + t;
                    if (wallUs > upToWallUs) return;
                    if (wallUs >= audioToUs) {
                        audioDone = true;
                        return;
                    }
                    if (wallUs >= audioFromUs) {
                        int size = audio.readSampleData(audioBuffer, 0);
                        long pts = wallUs - fromWall * 1000L;
                        if (size > 0 && pts > lastAudioPtsUs) {
                            audioInfo.set(0, size, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                            muxer.writeSampleData(audioTrack, audioBuffer, audioInfo);
                            lastAudioPtsUs = pts;
                        }
                    }
                    if (!audio.advance()) {
                        audioDone = true;
                        return;
                    }
                }
            } catch (Throwable t) {
                logger.warn("Audio copy stopped: " + t.getMessage());
                audioDone = true;
            }
        }

        private void closeAudio() {
            if (audio != null) {
                try { audio.release(); } catch (Throwable ignored) {}
                audio = null;
            }
            audioDone = true;
        }

        private void tearDown(boolean ok) {
            // Hardware codecs first, so a recording that is starting gets them back at once.
            leader.close();
            follower.close();
            if (encoder != null) {
                if (unregister(encoder)) {
                    try { encoder.stop(); } catch (Throwable ignored) {}
                    try { encoder.release(); } catch (Throwable ignored) {}
                }
                encoder = null;
            }
            closeAudio();
            if (muxer != null) {
                // A failed file is deleted by the caller; release() alone is enough.
                try { muxer.release(); } catch (Throwable ignored) {}
                muxer = null;
            }
            if (gridSurface != null) gridSurface.release();
            if (dvrSurface != null) dvrSurface.release();
            if (stGrid != null) stGrid.release();
            if (stDvr != null) stDvr.release();
            if (egl != null) {
                try {
                    if (program != 0) GlUtil.deleteProgram(program);
                    if (texGrid != 0) GlUtil.deleteTexture(texGrid);
                    if (texDvr != 0) GlUtil.deleteTexture(texDvr);
                } catch (Throwable ignored) {
                }
                try { egl.destroySurface(eglSurface); } catch (Throwable ignored) {}
                try { egl.release(); } catch (Throwable ignored) {}
                egl = null;
            }
            if (encoderSurface != null) encoderSurface.release();
            if (frameThread != null) frameThread.quitSafely();
        }
    }
}
