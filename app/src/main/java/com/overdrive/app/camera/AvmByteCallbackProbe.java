package com.overdrive.app.camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Byte-callback probe — opens AVMCamera and sweeps (cameraId, viewIndex)
 * combinations using setPreviewCallback + enablePreviewCallback (the path
 * the secondary reference app uses on units where SurfaceTexture attachment yields no frames).
 *
 * Per combo: wait up to PER_COMBO_FIRST_FRAME_TIMEOUT_MS for the first
 * frame; on arrival, record for PER_COMBO_RECORD_MS. Frames encode to MP4
 * when MediaCodec accepts the dims/format, otherwise dump as raw NV21.
 *
 * Self-contained — does not touch PanoramicCameraGpu or any pipeline class.
 *
 * Threading model:
 *   - Main probe thread: drives the sweep, blocks waiting for frames or
 *     timeouts, never touches MediaCodec/MediaMuxer directly.
 *   - HAL callback thread: invokes IPreviewCallback proxy → enqueues frames.
 *   - Per-combo worker thread: SOLE owner of MediaCodec/MediaMuxer/raw
 *     output. Reads queue, encodes/dumps, finalizes on EOS sentinel.
 *
 * Failure isolation: every cross-thread surface is wrapped in
 * try/catch(Throwable). A combo crash cannot kill the probe; a probe
 * crash cannot kill the daemon.
 */
public final class AvmByteCallbackProbe {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AvmByteCallbackProbe");

    private static final int CAMERA_ID_MIN = 0;
    private static final int CAMERA_ID_MAX = 7;
    private static final int VIEW_INDEX_MIN = 0;
    private static final int VIEW_INDEX_MAX = 4;

    /** Hard cap on combo wall-clock time. Safety net even if everything below misbehaves. */
    private static final long PER_COMBO_HARD_CAP_MS = 12_000L;
    /** Window from open to first frame. If 0 frames in this window, combo is dead. */
    private static final long PER_COMBO_FIRST_FRAME_TIMEOUT_MS = 5_000L;
    /** Recording window measured from first-frame moment. */
    private static final long PER_COMBO_RECORD_MS = 5_000L;
    /** Time between combos for HAL to settle after camera close. */
    private static final long INTER_COMBO_SETTLE_MS = 2_500L;
    /** How long finish() waits for the worker to drain + flush. */
    private static final long WORKER_FINISH_JOIN_MS = 5_000L;
    /** How long finishAndJoin waits to enqueue the EOS sentinel before giving up. */
    private static final long EOS_OFFER_TIMEOUT_MS = 1_000L;

    private static final int ENCODER_BIT_RATE = 2_000_000;
    private static final int ENCODER_FRAME_RATE = 15;
    private static final int ENCODER_IFRAME_INTERVAL = 1;
    /** Frames buffered between HAL callback and worker. Dropping oldest is preferred over stalling the HAL. */
    private static final int FRAME_QUEUE_DEPTH = 3;
    /** Hard cap on raw NV21 frames per combo to bound disk usage.
     *  At 5120×960 NV21 = 7.4 MB/frame → 60 frames = 444 MB max per combo. */
    private static final int MAX_RAW_FRAMES_PER_COMBO = 60;
    /** Hard cap on encoded frames per combo (matches PER_COMBO_RECORD_MS @ 15fps). */
    private static final int MAX_ENCODED_FRAMES_PER_COMBO = 75;

    /** Sentinel queue entry signaling end-of-stream to the worker. */
    private static final byte[] EOS_SENTINEL = new byte[0];

    private final File probeDir;

    public AvmByteCallbackProbe(File probeDir) {
        this.probeDir = probeDir;
    }

    /** Run the full sweep. NEVER throws; catches and logs everything. */
    public void run() {
        try {
            runUnsafe();
        } catch (Throwable t) {
            logger.error("Probe top-level failure: " + t.getClass().getSimpleName()
                + ": " + t.getMessage(), t);
        }
    }

    private void runUnsafe() {
        long sweepStartMs = System.currentTimeMillis();
        if (!probeDir.exists() && !probeDir.mkdirs()) {
            logger.error("Could not create probe dir: " + probeDir.getAbsolutePath());
            return;
        }
        logger.info("=== BYTE-CALLBACK PROBE START ===");
        logger.info("Output dir: " + probeDir.getAbsolutePath());
        logEnvironment();

        Class<?> avmClass = loadAvmCameraClass();
        if (avmClass == null) {
            logger.error("AVMCamera class unavailable on this device — aborting probe");
            return;
        }
        logger.info("AVMCamera class loaded: " + avmClass.getName()
            + " classLoader=" + avmClass.getClassLoader());

        Class<?> previewCallbackIface = findInnerInterface(avmClass, "IPreviewCallback");
        if (previewCallbackIface == null) {
            logger.error("IPreviewCallback inner interface not found — aborting probe");
            return;
        }
        logger.info("IPreviewCallback iface loaded: " + previewCallbackIface.getName());

        int totalCombos = (CAMERA_ID_MAX - CAMERA_ID_MIN + 1)
                        * (VIEW_INDEX_MAX - VIEW_INDEX_MIN + 1);
        logger.info("Sweeping " + totalCombos + " combos (cameraId "
            + CAMERA_ID_MIN + ".." + CAMERA_ID_MAX + " × viewIndex "
            + VIEW_INDEX_MIN + ".." + VIEW_INDEX_MAX + ")");

        List<ComboResult> results = new ArrayList<>();
        int comboNum = 0;

        for (int camId = CAMERA_ID_MIN; camId <= CAMERA_ID_MAX; camId++) {
            for (int viewIndex = VIEW_INDEX_MIN; viewIndex <= VIEW_INDEX_MAX; viewIndex++) {
                comboNum++;
                long comboStartMs = System.currentTimeMillis();
                logger.info(String.format(
                    "── COMBO %d/%d START ── cam=%d view=%d (sweep elapsed=%ds)",
                    comboNum, totalCombos, camId, viewIndex,
                    (comboStartMs - sweepStartMs) / 1000));
                ComboResult r = new ComboResult(camId, viewIndex);
                try {
                    probeCombo(avmClass, previewCallbackIface, r);
                } catch (Throwable t) {
                    r.error = "Combo crashed: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                    logger.error("Combo cam=" + camId + " view=" + viewIndex
                        + " threw " + r.error, t);
                }
                long comboElapsedMs = System.currentTimeMillis() - comboStartMs;
                results.add(r);
                logger.info(String.format(
                    "── COMBO %d/%d END ── cam=%d view=%d open=%s frames=%d "
                    + "dims=%s out=%s elapsed=%dms%s",
                    comboNum, totalCombos, r.cameraId, r.viewIndex,
                    r.opened ? "OK" : "FAIL", r.frameCount,
                    r.width > 0 ? (r.width + "x" + r.height) : "n/a",
                    r.outputPath != null ? r.outputPath : "(none)",
                    comboElapsedMs,
                    r.error != null ? "  err=" + r.error : ""));
                try { Thread.sleep(INTER_COMBO_SETTLE_MS); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("Probe interrupted — emitting partial summary");
                    break;
                }
            }
            if (Thread.currentThread().isInterrupted()) break;
        }

        long sweepElapsedMs = System.currentTimeMillis() - sweepStartMs;
        logger.info("=== BYTE-CALLBACK PROBE SUMMARY (" + results.size()
            + " combos in " + (sweepElapsedMs / 1000) + "s) ===");
        int comboWithFrames = 0;
        int comboOpened = 0;
        for (ComboResult r : results) {
            if (r.opened) comboOpened++;
            if (r.frameCount > 0) comboWithFrames++;
            logger.info(String.format(
                "  cam=%d view=%d  %s  frames=%d  %s",
                r.cameraId, r.viewIndex,
                r.opened ? "OPENED" : "OPEN_FAIL",
                r.frameCount,
                r.frameCount > 0
                    ? (r.width + "x" + r.height + " → " + r.outputPath
                       + (r.encoderUsed ? "" : "  [RAW NV21 — encoder unavailable]"))
                    : "(no frames)"));
        }
        logger.info("Totals: " + comboOpened + "/" + results.size()
            + " opened, " + comboWithFrames + "/" + results.size()
            + " produced frames, output=" + probeDir.getAbsolutePath());
        logger.info("=== PROBE COMPLETE — daemon idle ===");
    }

    /** Log device fingerprint + camera-mapping property so support can correlate
     *  probe output with vehicle/firmware. Mirrors AvmCameraHelper's startup dump. */
    private static void logEnvironment() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            String model = (String) get.invoke(null, "ro.product.model");
            String build = (String) get.invoke(null, "ro.build.fingerprint");
            String camSort = (String) get.invoke(null, "vehicle.config.cam_sort");
            logger.info("Device: model='" + model + "' fingerprint='" + build + "'");
            logger.info("vehicle.config.cam_sort='"
                + (camSort != null && !camSort.isEmpty() ? camSort : "(empty/null)") + "'");
        } catch (Throwable t) {
            logger.warn("Environment dump failed: " + t.getMessage());
        }
        try {
            Class<?> bmm = Class.forName("android.hardware.BmmCameraInfo");
            Method getCameraId = bmm.getDeclaredMethod("getCameraId", String.class);
            getCameraId.setAccessible(true);
            String[] tags = {"front", "rear", "rvs", "rf", "dms", "face",
                "pano_h", "pano_l", "apa", "byd_apa", "d954_h_m", "d954_h_s",
                "d954_l_m", "d954_l_s"};
            StringBuilder sb = new StringBuilder("BmmCameraInfo IDs:");
            boolean any = false;
            for (String tag : tags) {
                try {
                    int id = (Integer) getCameraId.invoke(null, tag);
                    if (id >= 0) { sb.append(" ").append(tag).append("=").append(id); any = true; }
                } catch (Throwable ignored) {}
            }
            logger.info(any ? sb.toString() : "BmmCameraInfo: no tags resolve to a camera ID");
        } catch (Throwable t) {
            logger.warn("BmmCameraInfo dump failed: " + t.getMessage());
        }
    }

    /** Try standard then secondary-reference-style addDexPath fallback for bmmcamera.jar. */
    private static Class<?> loadAvmCameraClass() {
        try {
            return Class.forName("android.hardware.AVMCamera");
        } catch (ClassNotFoundException ignored) {}
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            return sys.loadClass("android.hardware.AVMCamera");
        } catch (Throwable ignored) {}
        try {
            ClassLoader cl = AvmByteCallbackProbe.class.getClassLoader();
            try {
                Method addDexPath = cl.getClass().getMethod("addDexPath", String.class);
                addDexPath.invoke(cl, "/system/framework/bmmcamera.jar");
            } catch (Throwable t) {
                logger.warn("addDexPath unavailable: " + t.getMessage());
            }
            return Class.forName("android.hardware.AVMCamera");
        } catch (Throwable t) {
            logger.warn("Final AVMCamera load attempt failed: " + t.getMessage());
            return null;
        }
    }

    private static Class<?> findInnerInterface(Class<?> outer, String simpleName) {
        try {
            return Class.forName(outer.getName() + "$" + simpleName);
        } catch (ClassNotFoundException ignored) {}
        for (Class<?> c : outer.getDeclaredClasses()) {
            if (simpleName.equals(c.getSimpleName())) return c;
        }
        return null;
    }

    private void probeCombo(Class<?> avmClass, Class<?> previewCallbackIface,
                            ComboResult result) throws Throwable {
        Object cameraObj = null;
        FrameCollector collector = new FrameCollector(result.cameraId, result.viewIndex, probeDir);

        try {
            long t0 = System.currentTimeMillis();
            Constructor<?> ctor = avmClass.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            cameraObj = ctor.newInstance(result.cameraId);
            logger.info("  ctor(cam=" + result.cameraId + ") done in "
                + (System.currentTimeMillis() - t0) + "ms");

            long t1 = System.currentTimeMillis();
            Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            Object openResult = mOpen.invoke(cameraObj);
            long openMs = System.currentTimeMillis() - t1;
            if (openResult instanceof Boolean && !(Boolean) openResult) {
                result.error = "AVMCamera.open() returned false";
                logger.warn("  open(cam=" + result.cameraId + ") returned false in " + openMs + "ms");
                return;
            }
            result.opened = true;
            logger.info("  open(cam=" + result.cameraId + ") returned " + openResult
                + " in " + openMs + "ms");

            Object proxy = Proxy.newProxyInstance(
                previewCallbackIface.getClassLoader(),
                new Class<?>[]{previewCallbackIface},
                collector);
            Method mSetPreviewCallback = avmClass.getDeclaredMethod(
                "setPreviewCallback", previewCallbackIface);
            mSetPreviewCallback.setAccessible(true);
            mSetPreviewCallback.invoke(cameraObj, proxy);
            logger.info("  setPreviewCallback installed (proxy)");

            // Order matters on this HAL: startPreview BEFORE enablePreviewCallback,
            // matching the secondary reference app AbstractC4100i.mo4507i.
            long t2 = System.currentTimeMillis();
            Method mStart = avmClass.getDeclaredMethod("startPreview");
            mStart.setAccessible(true);
            Object startResult = mStart.invoke(cameraObj);
            logger.info("  startPreview returned " + startResult + " in "
                + (System.currentTimeMillis() - t2) + "ms");

            long t3 = System.currentTimeMillis();
            Method mEnable = avmClass.getDeclaredMethod("enablePreviewCallback", int.class);
            mEnable.setAccessible(true);
            Object enabled = mEnable.invoke(cameraObj, result.viewIndex);
            long enableMs = System.currentTimeMillis() - t3;
            logger.info("  enablePreviewCallback(view=" + result.viewIndex
                + ") returned " + enabled + " in " + enableMs + "ms");
            if (enabled instanceof Boolean && !(Boolean) enabled) {
                result.error = "enablePreviewCallback(" + result.viewIndex + ") returned false";
                // Continue — some HALs return false but still deliver frames.
            }

            collector.startWorker();
            logger.info("  worker thread started — waiting up to "
                + (PER_COMBO_FIRST_FRAME_TIMEOUT_MS / 1000) + "s for first frame");

            // Two-phase wait. Phase A: PER_COMBO_FIRST_FRAME_TIMEOUT_MS for any frame.
            // Phase B: PER_COMBO_RECORD_MS of recording from first-frame moment.
            // PER_COMBO_HARD_CAP_MS is the absolute backstop.
            long startMs = System.currentTimeMillis();
            long hardDeadline = startMs + PER_COMBO_HARD_CAP_MS;
            long firstFrameDeadline = startMs + PER_COMBO_FIRST_FRAME_TIMEOUT_MS;
            long recordingDeadline = -1;
            long lastProgressLog = startMs;
            int lastFrameCountLogged = 0;
            while (System.currentTimeMillis() < hardDeadline) {
                long now = System.currentTimeMillis();
                if (collector.frameCount > 0 && recordingDeadline < 0) {
                    recordingDeadline = now + PER_COMBO_RECORD_MS;
                    logger.info("  FIRST FRAME at +" + (now - startMs)
                        + "ms — recording for " + (PER_COMBO_RECORD_MS / 1000) + "s");
                }
                // Progress log every 1.5s so the operator sees liveness even
                // on stuck combos. Don't spam: skip if nothing changed.
                if (now - lastProgressLog >= 1_500) {
                    int cur = collector.frameCount;
                    if (cur != lastFrameCountLogged) {
                        logger.info("  progress: frames=" + cur
                            + " elapsed=" + (now - startMs) + "ms"
                            + (recordingDeadline > 0
                                ? " recRemaining=" + (recordingDeadline - now) + "ms"
                                : " awaitingFirstFrame"));
                        lastFrameCountLogged = cur;
                    } else {
                        logger.info("  progress: frames=" + cur + " (no change) elapsed="
                            + (now - startMs) + "ms");
                    }
                    lastProgressLog = now;
                }
                if (recordingDeadline > 0 && now >= recordingDeadline) {
                    logger.info("  recording window done at +" + (now - startMs) + "ms");
                    break;
                }
                if (recordingDeadline < 0 && now >= firstFrameDeadline) {
                    logger.warn("  no frames in " + (now - startMs)
                        + "ms — declaring combo dead");
                    break;
                }
                Thread.sleep(50);
                if (Thread.currentThread().isInterrupted()) break;
            }
            if (System.currentTimeMillis() >= hardDeadline) {
                logger.warn("  HIT HARD DEADLINE (" + PER_COMBO_HARD_CAP_MS + "ms)");
            }
        } finally {
            // Stop frames first so no more land in the queue, then drain & finalize
            // worker, then close camera. Order matters: worker is the SOLE owner
            // of MediaCodec/Muxer, so we wait for it before any encoder access.
            if (cameraObj != null) {
                tryInvoke(avmClass, cameraObj, "disablePreviewCallback",
                    new Class<?>[]{int.class}, new Object[]{result.viewIndex});
                tryInvoke(avmClass, cameraObj, "stopPreview",
                    new Class<?>[0], new Object[0]);
                tryInvoke(avmClass, cameraObj, "setPreviewCallback",
                    new Class<?>[]{previewCallbackIface}, new Object[]{null});
            }
            collector.finishAndJoin();
            if (cameraObj != null) {
                tryInvoke(avmClass, cameraObj, "close",
                    new Class<?>[0], new Object[0]);
            }
            result.frameCount = collector.frameCount;
            result.width = collector.width;
            result.height = collector.height;
            result.outputPath = collector.outputPath;
            result.encoderUsed = collector.encoderUsed;
        }
    }

    private static void tryInvoke(Class<?> cls, Object instance, String name,
                                  Class<?>[] paramTypes, Object[] args) {
        try {
            Method m = cls.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            m.invoke(instance, args);
        } catch (Throwable ignored) {}
    }

    private static final class ComboResult {
        final int cameraId;
        final int viewIndex;
        boolean opened;
        boolean encoderUsed;
        int frameCount;
        int width = -1;
        int height = -1;
        String outputPath;
        String error;
        ComboResult(int cameraId, int viewIndex) {
            this.cameraId = cameraId;
            this.viewIndex = viewIndex;
        }
    }

    /**
     * Frame collector — receives byte[] frames via dynamic-proxy IPreviewCallback,
     * enqueues them, and a single worker thread drains the queue into either
     * MediaCodec→MediaMuxer (MP4) or a FileOutputStream (raw NV21 fallback).
     *
     * MediaCodec / MediaMuxer / FileOutputStream are accessed ONLY by the worker
     * thread. The HAL callback thread only enqueues. The probe driver thread
     * only inspects frameCount and signals shutdown.
     */
    private static final class FrameCollector implements InvocationHandler {
        private final int cameraId;
        private final int viewIndex;
        private final File probeDir;

        // Cross-thread visible counters/state
        volatile int frameCount;
        volatile int width = -1;
        volatile int height = -1;
        volatile String outputPath;
        volatile boolean encoderUsed;
        volatile boolean encoderInitDecided;

        // Worker-only state
        private MediaCodec encoder;
        private MediaMuxer muxer;
        private int trackIndex = -1;
        private boolean muxerStarted;
        private long firstPtsUs = -1;
        private File mp4Tmp;
        private File mp4Final;
        private FileOutputStream rawOut;
        private File rawTmp;
        private File rawFinal;
        private boolean encoderInitAttempted;

        private final LinkedBlockingQueue<byte[]> frameQueue =
            new LinkedBlockingQueue<>(FRAME_QUEUE_DEPTH);
        // Bounded reuse: queued frames + worker frame + one callback in flight.
        // A frame is returned only after its consumer has finished reading it.
        private static final int FRAME_POOL_CAPACITY = FRAME_QUEUE_DEPTH + 2;
        private final ArrayBlockingQueue<byte[]> freeFrames =
            new ArrayBlockingQueue<>(FRAME_POOL_CAPACITY);
        private int allocatedFrames;
        private final Object framePoolLock = new Object();

        private byte[] acquireFrame(int length) {
            synchronized (framePoolLock) {
                byte[] frame;
                while ((frame = freeFrames.poll()) != null) {
                    if (frame.length == length) return frame;
                    allocatedFrames--; // Dimensions changed: discard old allocation.
                }
                if (allocatedFrames >= FRAME_POOL_CAPACITY) return null;
                allocatedFrames++;
            }
            try {
                return new byte[length];
            } catch (OutOfMemoryError error) {
                synchronized (framePoolLock) { allocatedFrames--; }
                throw error;
            }
        }

        private void recycleFrame(byte[] frame) {
            if (frame == null || frame == EOS_SENTINEL) return;
            synchronized (framePoolLock) {
                if (!freeFrames.offer(frame)) allocatedFrames--;
            }
        }
        private Thread worker;

        FrameCollector(int cameraId, int viewIndex, File probeDir) {
            this.cameraId = cameraId;
            this.viewIndex = viewIndex;
            this.probeDir = probeDir;
        }

        void startWorker() {
            worker = new Thread(this::workerLoop,
                "AvmProbe-cam" + cameraId + "-view" + viewIndex);
            worker.setDaemon(true);
            worker.start();
        }

        /** Signal end-of-stream and wait for worker to finalize. NEVER throws. */
        void finishAndJoin() {
            if (worker == null) return;  // worker never started → nothing to drain

            // Drain queued frames so EOS lands at the front. Use bounded offer
            // with timeout — never block forever, even if the worker died.
            boolean delivered = false;
            try {
                delivered = frameQueue.offer(EOS_SENTINEL, EOS_OFFER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (!delivered) {
                // Queue still full and worker isn't draining → drop oldest, retry once.
                recycleFrame(frameQueue.poll());
                delivered = frameQueue.offer(EOS_SENTINEL);
            }
            if (!delivered) {
                logger.warn("Could not enqueue EOS for cam=" + cameraId + " view="
                    + viewIndex + " — interrupting worker");
                worker.interrupt();
            }

            try { worker.join(WORKER_FINISH_JOIN_MS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (worker.isAlive()) {
                logger.warn("Worker did not exit in " + WORKER_FINISH_JOIN_MS
                    + "ms cam=" + cameraId + " view=" + viewIndex + " — interrupting");
                worker.interrupt();
                try { worker.join(1000); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }

        private void workerLoop() {
            try {
                while (true) {
                    byte[] nv21 = frameQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (nv21 == EOS_SENTINEL) break;
                    if (Thread.currentThread().isInterrupted()) {
                        recycleFrame(nv21);
                        break;
                    }
                    if (nv21 == null) continue;
                    try {
                    if (!encoderInitAttempted && width > 0 && height > 0) {
                        encoderInitAttempted = true;
                        if (!tryInitEncoder()) {
                            initRawDump();
                        }
                        encoderInitDecided = true;
                    }
                    if (encoder != null) {
                        try {
                            byte[] nv12 = nv21ToNv12(nv21, width, height);
                            feedEncoder(nv12);
                            drainEncoder(false);
                        } catch (Throwable t) {
                            logger.warn("Encoder runtime error cam=" + cameraId
                                + " view=" + viewIndex + ": " + t.getMessage());
                            // Switch to raw on the fly. Future frames go to raw.
                            closeEncoderFailFast();
                            encoderUsed = false;
                            initRawDump();
                            writeRaw(nv21);
                        }
                    } else if (rawOut != null) {
                        writeRaw(nv21);
                    }
                    } finally {
                        recycleFrame(nv21);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                logger.warn("Worker error cam=" + cameraId + " view="
                    + viewIndex + ": " + t.getMessage());
            } finally {
                // Worker-thread-only finalization. encoder/muxer/raw are owned by us.
                finalizeOutputs();
                byte[] pending;
                while ((pending = frameQueue.poll()) != null) recycleFrame(pending);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            // Handle Object methods proxies receive (equals/hashCode/toString)
            // — returning null from a primitive-returning method causes NPE.
            String name = method.getName();
            if ("equals".equals(name) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return "AvmProbeCallback(cam=" + cameraId + ",view=" + viewIndex + ")";
            }
            try {
                if (!"onPreview".equals(name) || args == null || args.length < 8) {
                    return null;
                }
                Object payload = args[1];
                if (!(payload instanceof byte[])) return null;
                byte[] data = (byte[]) payload;
                int w = (Integer) args[2];
                int h = (Integer) args[3];
                int bufSize = (Integer) args[5];
                if (w <= 0 || h <= 0 || bufSize <= 0 || data.length == 0) return null;
                if (width < 0) {
                    width = w;
                    height = h;
                    // Dump every arg of the first frame so we can verify the
                    // signature matches our parsing on this firmware.
                    logger.info("First frame cam=" + cameraId + " view=" + viewIndex
                        + " args[2..7]=" + w + "," + h + "," + args[4] + "," + args[5]
                        + "," + args[6] + "," + args[7] + " data.length=" + data.length);
                }
                // Cap frames so disk doesn't fill. Until the worker decides
                // encoder vs raw we use the encoder cap (75); once raw fallback
                // is committed we use the raw cap (60). Past the cap we just
                // increment the counter for stats.
                int seen = frameCount + 1;
                int cap = encoderInitDecided && !encoderUsed
                    ? MAX_RAW_FRAMES_PER_COMBO
                    : MAX_ENCODED_FRAMES_PER_COMBO;
                if (seen > cap) {
                    frameCount = seen;
                    return null;
                }
                int copyLen = Math.min(data.length, bufSize);
                // Prefer a recycled frame; if all buffers are in flight, drop
                // rather than allocate without bound or stall the HAL callback.
                byte[] copy = acquireFrame(copyLen);
                frameCount = seen;
                if (copy == null) return null;
                System.arraycopy(data, 0, copy, 0, copyLen);
                if (!frameQueue.offer(copy)) {
                    // Queue full — drop oldest, push newest.
                    recycleFrame(frameQueue.poll());
                    if (!frameQueue.offer(copy)) recycleFrame(copy);
                }
            } catch (Throwable t) {
                logger.warn("invoke error cam=" + cameraId + " view="
                    + viewIndex + ": " + t.getMessage());
            }
            return null;
        }

        // ============================================================
        // WORKER-ONLY METHODS (encoder / muxer / raw output)
        // ============================================================

        private boolean tryInitEncoder() {
            if ((width & 0xF) != 0 || (height & 0xF) != 0) {
                logger.warn("Skipping encoder for cam=" + cameraId + " view=" + viewIndex
                    + " — dims " + width + "x" + height + " not 16-aligned, raw dump only");
                return false;
            }
            String mime = "video/avc";
            int colorFormat = pickSupportedColorFormat(mime);
            if (colorFormat < 0) {
                logger.warn("No NV12-class color format supported for " + mime + " — raw dump only");
                return false;
            }
            try {
                MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, ENCODER_BIT_RATE);
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, ENCODER_FRAME_RATE);
                fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENCODER_IFRAME_INTERVAL);
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2);

                encoder = MediaCodec.createEncoderByType(mime);
                encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();

                mp4Final = new File(probeDir, String.format(
                    "probe_cam%d_view%d_%dx%d.mp4",
                    cameraId, viewIndex, width, height));
                mp4Tmp = new File(probeDir, mp4Final.getName() + ".tmp");
                // Stale .tmp from a previous run will trip MediaMuxer constructor — wipe it.
                if (mp4Tmp.exists()) {
                    try { mp4Tmp.delete(); } catch (Throwable ignored) {}
                }
                muxer = new MediaMuxer(mp4Tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

                outputPath = mp4Final.getAbsolutePath();
                encoderUsed = true;
                logger.info("Encoder ready cam=" + cameraId + " view=" + viewIndex
                    + " mime=" + mime + " color=0x" + Integer.toHexString(colorFormat)
                    + " dims=" + width + "x" + height
                    + " → " + mp4Final.getName());
                return true;
            } catch (Throwable t) {
                logger.warn("Encoder init failed cam=" + cameraId + " view=" + viewIndex
                    + " mime=" + mime + " color=" + colorFormat + " dims=" + width + "x" + height
                    + ": " + t.getMessage());
                closeEncoderFailFast();
                return false;
            }
        }

        private static int pickSupportedColorFormat(String mime) {
            try {
                MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                for (MediaCodecInfo info : list.getCodecInfos()) {
                    if (!info.isEncoder()) continue;
                    boolean handles = false;
                    for (String t : info.getSupportedTypes()) {
                        if (t.equalsIgnoreCase(mime)) { handles = true; break; }
                    }
                    if (!handles) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                    for (int cf : caps.colorFormats) {
                        if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return cf;
                    }
                    for (int cf : caps.colorFormats) {
                        if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return cf;
                    }
                }
            } catch (Throwable t) {
                logger.warn("Codec capabilities probe failed: " + t.getMessage());
            }
            return -1;
        }

        private void closeEncoderFailFast() {
            if (encoder != null) {
                try { encoder.stop(); } catch (Throwable ignored) {}
                try { encoder.release(); } catch (Throwable ignored) {}
                encoder = null;
            }
            if (muxer != null) {
                try { muxer.release(); } catch (Throwable ignored) {}
                muxer = null;
            }
            muxerStarted = false;
            if (mp4Tmp != null && mp4Tmp.exists()) {
                try { mp4Tmp.delete(); } catch (Throwable ignored) {}
            }
            mp4Tmp = null;
            mp4Final = null;
        }

        private void initRawDump() {
            try {
                rawFinal = new File(probeDir, String.format(
                    "probe_cam%d_view%d_%dx%d.nv21",
                    cameraId, viewIndex, width, height));
                rawTmp = new File(probeDir, rawFinal.getName() + ".tmp");
                if (rawTmp.exists()) {
                    try { rawTmp.delete(); } catch (Throwable ignored) {}
                }
                rawOut = new FileOutputStream(rawTmp);
                outputPath = rawFinal.getAbsolutePath();
                encoderUsed = false;
                logger.info("Raw NV21 dump: " + outputPath);
            } catch (Throwable t) {
                logger.warn("Raw dump init failed cam=" + cameraId + " view=" + viewIndex
                    + ": " + t.getMessage());
                rawOut = null;
            }
        }

        private void writeRaw(byte[] frame) {
            if (rawOut == null) return;
            try {
                rawOut.write(frame);
            } catch (Throwable t) {
                logger.warn("Raw write failed: " + t.getMessage());
                try { rawOut.close(); } catch (Throwable ignored) {}
                rawOut = null;
            }
        }

        private void feedEncoder(byte[] nv12) {
            int idx = encoder.dequeueInputBuffer(50_000);
            if (idx < 0) return;
            ByteBuffer buf = encoder.getInputBuffer(idx);
            if (buf == null) {
                encoder.queueInputBuffer(idx, 0, 0, 0, 0);
                return;
            }
            buf.clear();
            int writable = Math.min(nv12.length, buf.capacity());
            buf.put(nv12, 0, writable);
            long nowUs = System.nanoTime() / 1_000L;
            if (firstPtsUs < 0) firstPtsUs = nowUs;
            encoder.queueInputBuffer(idx, 0, writable, nowUs - firstPtsUs, 0);
        }

        private void drainEncoder(boolean endOfStream) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int outIdx = encoder.dequeueOutputBuffer(info, endOfStream ? 50_000 : 0);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) continue;
                    try {
                        trackIndex = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    } catch (Throwable t) {
                        logger.warn("Muxer start failed: " + t.getMessage());
                        return;
                    }
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        try {
                            muxer.writeSampleData(trackIndex, outBuf, info);
                        } catch (Throwable t) {
                            logger.warn("Muxer write failed: " + t.getMessage());
                        }
                    }
                    try { encoder.releaseOutputBuffer(outIdx, false); }
                    catch (Throwable ignored) {}
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
                // INFO_OUTPUT_BUFFERS_CHANGED (deprecated, possible) falls through.
            }
        }

        /** Worker-thread finalization. Called from worker's finally block on EOS or error. */
        private void finalizeOutputs() {
            boolean muxOk = false;
            if (encoder != null) {
                try {
                    int idx = encoder.dequeueInputBuffer(50_000);
                    if (idx >= 0) {
                        encoder.queueInputBuffer(idx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    drainEncoder(true);
                } catch (Throwable t) {
                    logger.warn("Encoder EOS drain failed cam=" + cameraId + " view="
                        + viewIndex + ": " + t.getMessage());
                }
                try { encoder.stop(); } catch (Throwable ignored) {}
                try { encoder.release(); } catch (Throwable ignored) {}
                encoder = null;
            }
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                        muxOk = true;
                    }
                } catch (Throwable t) {
                    logger.warn("Muxer stop failed cam=" + cameraId + " view=" + viewIndex
                        + ": " + t.getMessage());
                }
                try { muxer.release(); } catch (Throwable ignored) {}
                muxer = null;
            }
            // Promote .tmp → final, or delete if empty/unwritten.
            if (mp4Tmp != null) {
                long sz = mp4Tmp.exists() ? mp4Tmp.length() : 0;
                if (frameCount > 0 && muxOk && sz > 0) {
                    promoteFile(mp4Tmp, mp4Final);
                    logger.info("MP4 saved cam=" + cameraId + " view=" + viewIndex
                        + " size=" + (sz / 1024) + "KB frames=" + frameCount
                        + " path=" + (mp4Final != null ? mp4Final.getAbsolutePath() : "?"));
                } else {
                    try { mp4Tmp.delete(); } catch (Throwable ignored) {}
                    logger.warn("MP4 discarded cam=" + cameraId + " view=" + viewIndex
                        + " frames=" + frameCount + " muxOk=" + muxOk + " size=" + sz);
                    if (frameCount == 0) outputPath = null;
                }
                mp4Tmp = null;
            }
            if (rawOut != null) {
                try { rawOut.flush(); } catch (Throwable ignored) {}
                try { rawOut.close(); } catch (Throwable ignored) {}
                rawOut = null;
            }
            if (rawTmp != null) {
                long sz = rawTmp.exists() ? rawTmp.length() : 0;
                if (frameCount > 0 && sz > 0) {
                    promoteFile(rawTmp, rawFinal);
                    logger.info("Raw saved cam=" + cameraId + " view=" + viewIndex
                        + " size=" + (sz / 1024) + "KB frames=" + frameCount
                        + " path=" + (rawFinal != null ? rawFinal.getAbsolutePath() : "?"));
                } else {
                    try { rawTmp.delete(); } catch (Throwable ignored) {}
                    logger.warn("Raw discarded cam=" + cameraId + " view=" + viewIndex
                        + " frames=" + frameCount + " size=" + sz);
                    if (frameCount == 0) outputPath = null;
                }
                rawTmp = null;
            }
        }

        /** rename tmp→final, deleting any stale final first.
         *  File.renameTo behavior across same-filesystem rename is reliable on
         *  Android; refusing to overwrite is the typical failure mode. */
        private void promoteFile(File tmp, File finalFile) {
            if (finalFile == null) return;
            if (finalFile.exists()) {
                try { finalFile.delete(); } catch (Throwable ignored) {}
            }
            if (!tmp.renameTo(finalFile)) {
                logger.warn("Could not rename " + tmp + " → " + finalFile
                    + " — keeping .tmp visible");
                outputPath = tmp.getAbsolutePath();
            }
        }
    }

    private static byte[] nv21ToNv12(byte[] nv21, int width, int height) {
        int ySize = width * height;
        int uvSize = ySize / 2;
        if (nv21.length < ySize + uvSize) return nv21;
        byte[] nv12 = new byte[ySize + uvSize];
        System.arraycopy(nv21, 0, nv12, 0, ySize);
        for (int i = 0; i < uvSize; i += 2) {
            nv12[ySize + i] = nv21[ySize + i + 1];     // U
            nv12[ySize + i + 1] = nv21[ySize + i];     // V
        }
        return nv12;
    }
}
