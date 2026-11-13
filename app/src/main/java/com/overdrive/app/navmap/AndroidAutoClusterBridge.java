package com.overdrive.app.navmap;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;

import com.overdrive.app.camera.dilink5.ClusterTransport;
import com.overdrive.app.launcher.ClusterCast;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.BsNativeLayer;
import com.overdrive.app.surveillance.ClusterProjectionController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Receives the independent Android Auto CLUSTER H.264 stream from an optional
 * receiver app over loopback RTP/UDP and renders it on the BYD fission display.
 *
 * <p>This is deliberately an input-only bridge. It does not issue CAN/UDS
 * commands, touch vehicle configuration, or alter the factory Android Auto
 * receiver. The external receiver must be configured to announce a CLUSTER
 * display and push its second H.264 stream to 127.0.0.1:5004.
 *
 * <p>For the 2024 Dolphin / DiLink 3.0 profile the phone is asked for 1920x1080;
 * the 1920x720 instrument-cluster picture occupies the top of that frame. The
 * lower margin is cropped before the frame is composited onto the cluster.
 *
 * <p>The projection is lazy: the OEM cluster is opened only after a complete
 * IDR access unit arrives. If the stream stops for five seconds, the decoder,
 * layer and sustained projection hold are released so the native gauges return.
 */
public final class AndroidAutoClusterBridge {

    private static final String TAG = "AndroidAutoCluster";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final int RTP_PORT = 5004;
    private static final String PROJECTION_TOKEN = "android-auto-cluster";
    // Application ID of the optional compatible receiver. It is not bundled
    // with OverDrive and must be installed separately with second-screen support.
    private static final String RECEIVER_PACKAGE = "com.andrerinas.headunitrevived";

    // The receiver's CLUSTER profile for the Dolphin asks the phone for 1920x1080.
    private static final int VIDEO_WIDTH = 1920;
    private static final int VIDEO_HEIGHT = 1080;
    private static final int PANEL_WIDTH = 1920;
    private static final int PANEL_HEIGHT = 720;

    private static final int SOCKET_TIMEOUT_MS = 500;
    private static final long STREAM_TIMEOUT_MS = 5000L;
    private static final long PROJECTION_READY_TIMEOUT_MS = 8000L;
    private static final long PROJECTION_POLL_MS = 200L;
    private static final int QUEUE_CAPACITY = 6;
    private static final int LAYER_Z = Integer.MAX_VALUE - 2;

    private static final Object STATE_LOCK = new Object();
    private static final Object PIPELINE_LOCK = new Object();

    private static volatile boolean active;
    private static volatile boolean receiving;
    private static volatile boolean projecting;
    private static volatile String lastError = "";

    private static volatile DatagramSocket socket;
    private static volatile Thread receiverThread;
    private static volatile Thread decoderThread;
    private static volatile BlockingQueue<RtpH264Depacketizer.AccessUnit> queue;
    private static volatile RtpH264Depacketizer depacketizer;

    private static volatile long lastPacketAtMs;
    private static volatile boolean projectionHeld;
    private static volatile MediaCodec decoder;
    private static volatile BsNativeLayer videoLayer;
    private static long firstRtpTimestamp = -1L;
    private static long lastPresentationTimeUs = -1L;
    private static volatile boolean decoderNeedsKeyframe = true;
    private static boolean firstFrameShown;

    private AndroidAutoClusterBridge() {}

    /** Start the local RTP listener. The cluster remains untouched until video arrives. */
    public static boolean start() {
        synchronized (STATE_LOCK) {
            if (active) return true;
            if (ClusterTransport.isDiLink5()) {
                lastError = "The current cluster transport is not supported by this bridge yet.";
                return false;
            }
            if (ClusterProjectionController.isLegacyProjectionAdmissionBlocked()) {
                lastError = "Cluster projection recovery is still pending. Try again after it settles.";
                return false;
            }
            if (ClusterMapProjector.isActive() || ClusterCast.isActive()) {
                lastError = "Stop the current cluster map or app projection before starting Android Auto.";
                return false;
            }
            DatagramSocket created = null;
            try {
                created = new DatagramSocket(null);
                created.setReuseAddress(false);
                created.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), RTP_PORT));
                created.setSoTimeout(SOCKET_TIMEOUT_MS);

                socket = created;
                queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
                depacketizer = new RtpH264Depacketizer();
                lastPacketAtMs = 0L;
                receiving = false;
                projecting = false;
                lastError = "";
                decoderNeedsKeyframe = true;
                firstFrameShown = false;
                firstRtpTimestamp = -1L;
                lastPresentationTimeUs = -1L;
                active = true;

                receiverThread = new Thread(AndroidAutoClusterBridge::receiveLoop,
                        "AndroidAutoClusterRtp");
                receiverThread.setDaemon(true);
                decoderThread = new Thread(AndroidAutoClusterBridge::decodeLoop,
                        "AndroidAutoClusterDecode");
                decoderThread.setDaemon(true);
                receiverThread.start();
                decoderThread.start();
                logger.info("Android Auto cluster bridge listening on 127.0.0.1:" + RTP_PORT);
                return true;
            } catch (Exception e) {
                closeSocketLocked();
                if (created != null) {
                    try { created.close(); } catch (Throwable ignored) {}
                }
                active = false;
                lastError = "Could not bind the local RTP port " + RTP_PORT + ": "
                        + e.getClass().getSimpleName();
                logger.warn(lastError + " (" + e.getMessage() + ")");
                return false;
            }
        }
    }

    /** Stop the listener and restore the native cluster gauges. */
    public static void stop() {
        stopInternal(true, "");
    }

    /**
     * Called by ClusterProjectionController before any forced close or daemon
     * shutdown. The controller itself owns gauge restoration, so this callback
     * must not release the sustained token back into the controller.
     */
    public static void onProjectionForceClosed(String reason) {
        stopInternal(false, reason == null ? "Cluster projection was closed." : reason);
    }

    /**
     * Open the optional compatible receiver. The user still pairs/configures it
     * once; OverDrive only launches the installed app and owns the cluster bridge.
     */
    public static boolean launchReceiver() {
        if (!active && !start()) return false;
        try {
            String installed = runCommand("pm", "path", RECEIVER_PACKAGE);
            if (installed == null || !installed.contains("package:")) {
                lastError = "The compatible Android Auto receiver is not installed. Install a build with CLUSTER and RTP-push support.";
                return false;
            }
            String output = runCommand("am", "start",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER",
                    "-p", RECEIVER_PACKAGE);
            if (output == null || output.toLowerCase(java.util.Locale.US).contains("error")) {
                lastError = "Could not launch the compatible Android Auto receiver.";
                return false;
            }
            lastError = "";
            logger.info("launched optional Android Auto receiver " + RECEIVER_PACKAGE);
            return true;
        } catch (Throwable t) {
            lastError = "Receiver launch failed: " + t.getClass().getSimpleName();
            logger.warn(lastError + " " + t.getMessage());
            return false;
        }
    }

    private static String runCommand(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (output.length() > 4096) break;
            output.append(line).append('\n');
        }
        reader.close();
        int exit = process.waitFor();
        if (exit != 0) return "command_exit_" + exit + ": " + output;
        return output.toString();
    }

    /** True while the receiver bridge is armed or receiving. */
    public static boolean isActive() {
        return active;
    }

    /** JSON-safe state for the RoadSense settings page. */
    public static org.json.JSONObject status() {
        org.json.JSONObject result = new org.json.JSONObject();
        try {
            result.put("success", true);
            result.put("active", active);
            result.put("receiving", receiving);
            result.put("projecting", projecting);
            result.put("port", RTP_PORT);
            result.put("lastError", lastError);
            result.put("waitingForReceiver", active && !receiving);
        } catch (Exception ignored) {
        }
        return result;
    }

    private static void receiveLoop() {
        byte[] packetBytes = new byte[2048];
        while (active) {
            DatagramSocket current = socket;
            if (current == null || current.isClosed()) break;
            DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length);
            try {
                current.receive(packet);
                lastPacketAtMs = SystemClock.elapsedRealtime();
                RtpH264Depacketizer parser = depacketizer;
                RtpH264Depacketizer.AccessUnit unit =
                        parser == null ? null : parser.accept(packetBytes, packet.getLength());
                if (unit == null) continue;
                if (!receiving) lastError = "";
                receiving = true;
                BlockingQueue<RtpH264Depacketizer.AccessUnit> target = queue;
                if (target == null) continue;
                if (!target.offer(unit)) {
                    // This is a live display, not a recorder: discard backlog rather
                    // than increasing navigation latency. Resume at the next IDR.
                    target.poll();
                    decoderNeedsKeyframe = true;
                    target.offer(unit);
                }
            } catch (SocketTimeoutException ignored) {
                // The decoder loop owns the stream timeout and teardown.
            } catch (SocketException e) {
                if (active) {
                    lastError = "The local RTP socket closed unexpectedly.";
                    logger.warn(lastError + " " + e.getMessage());
                }
                break;
            } catch (Throwable t) {
                if (active) {
                    lastError = "RTP receive failed: " + t.getClass().getSimpleName();
                    logger.warn(lastError + " " + t.getMessage());
                }
            }
        }
    }

    private static void decodeLoop() {
        while (active) {
            try {
                BlockingQueue<RtpH264Depacketizer.AccessUnit> source = queue;
                RtpH264Depacketizer.AccessUnit unit =
                        source == null ? null : source.poll(500, TimeUnit.MILLISECONDS);
                if (!active) break;
                if (unit == null) {
                    if (receiving && lastPacketAtMs > 0L
                            && SystemClock.elapsedRealtime() - lastPacketAtMs > STREAM_TIMEOUT_MS) {
                        logger.warn("Android Auto cluster stream timed out; restoring native gauges");
                        stopInternal(true, "Android Auto video stream timed out. Start the bridge again when the receiver reconnects.");
                        break;
                    }
                    continue;
                }
                if (unit.discontinuity) decoderNeedsKeyframe = true;
                if (decoder == null) {
                    if (!unit.keyframe || unit.sps == null || unit.pps == null) continue;
                    if (!openProjectionAndDecoder(unit)) continue;
                }
                if (decoderNeedsKeyframe) {
                    if (!unit.keyframe) continue;
                    synchronized (PIPELINE_LOCK) {
                        if (decoder == null || !active) continue;
                        try {
                            decoder.flush();
                            firstRtpTimestamp = -1L;
                            lastPresentationTimeUs = -1L;
                            decoderNeedsKeyframe = false;
                        } catch (Throwable t) {
                            logger.warn("decoder flush failed: " + t.getMessage());
                            releaseVideoPipeline(true);
                            decoderNeedsKeyframe = true;
                            continue;
                        }
                    }
                }
                queueAccessUnit(unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                if (active) {
                    lastError = "Cluster video decoding failed: " + t.getClass().getSimpleName();
                    logger.warn(lastError + " " + t.getMessage());
                    stopInternal(true, lastError);
                }
                break;
            }
        }
    }

    private static boolean openProjectionAndDecoder(RtpH264Depacketizer.AccessUnit firstUnit) {
        synchronized (PIPELINE_LOCK) {
            if (!active) return false;
            if (decoder != null) return true;
            if (ClusterProjectionController.isLegacyProjectionAdmissionBlocked()) {
                lastError = "Cluster projection recovery is pending.";
                return false;
            }
            if (!ClusterProjectionController.getInstance()
                    .acquireSustained(PROJECTION_TOKEN)) {
                lastError = "The BYD cluster projection could not be opened.";
                logger.warn(lastError);
                return false;
            }
            projectionHeld = true;
            if (!waitForFissionDisplay()) {
                if (active) {
                    lastError = "The BYD cluster display did not become available.";
                    logger.warn(lastError);
                    releaseVideoPipeline(true);
                }
                // If another safety/stop path already cleared active, that path
                // owns projection teardown and token release.
                return false;
            }

            BsNativeLayer layer = new BsNativeLayer(
                    VIDEO_WIDTH, VIDEO_HEIGHT, "AndroidAutoClusterVideo", LAYER_Z);
            layer.setLayerStack(1);
            if (!layer.create()) {
                lastError = "Could not create the cluster video surface.";
                releaseVideoPipeline(true);
                return false;
            }
            layer.setGeometryHidden(
                    new Rect(0, 0, PANEL_WIDTH, PANEL_HEIGHT),
                    0, 0, PANEL_WIDTH, PANEL_HEIGHT);
            videoLayer = layer;

            MediaCodec created = null;
            try {
                MediaFormat format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
                format.setByteBuffer("csd-0", ByteBuffer.wrap(firstUnit.sps));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(firstUnit.pps));
                Surface output = layer.getSurface();
                if (output == null || !output.isValid()) {
                    throw new IllegalStateException("cluster output surface is not valid");
                }
                created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                created.configure(format, output, null, 0);
                created.start();
                decoder = created;
                decoderNeedsKeyframe = false;
                firstRtpTimestamp = -1L;
                lastPresentationTimeUs = -1L;
                projecting = true;
                lastError = "";
                logger.info("Android Auto cluster decoder started (1920x1080 stream, 1920x720 panel)");
                return true;
            } catch (Throwable t) {
                if (created != null) {
                    try { created.stop(); } catch (Throwable ignored) {}
                    try { created.release(); } catch (Throwable ignored) {}
                }
                decoder = null;
                lastError = "The head unit could not initialize an H.264 cluster decoder: "
                        + t.getClass().getSimpleName();
                logger.warn(lastError + " " + t.getMessage());
                releaseVideoPipeline(true);
                return false;
            }
        }
    }

    private static void queueAccessUnit(RtpH264Depacketizer.AccessUnit unit) {
        synchronized (PIPELINE_LOCK) {
            MediaCodec current = decoder;
            if (!active || current == null) return;
            try {
                if (firstRtpTimestamp < 0L) firstRtpTimestamp = unit.timestamp90k;
                long delta90k = (unit.timestamp90k - firstRtpTimestamp) & 0xffffffffL;
                long ptsUs = (delta90k * 1000000L) / 90000L;
                if (ptsUs <= lastPresentationTimeUs) {
                    ptsUs = lastPresentationTimeUs + 1L;
                }
                lastPresentationTimeUs = ptsUs;

                int inputIndex = current.dequeueInputBuffer(10_000);
                if (inputIndex < 0) {
                    decoderNeedsKeyframe = true;
                    return;
                }
                ByteBuffer input = current.getInputBuffer(inputIndex);
                if (input == null || unit.annexB.length > input.capacity()) {
                    current.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
                    decoderNeedsKeyframe = true;
                    return;
                }
                input.clear();
                input.put(unit.annexB);
                current.queueInputBuffer(inputIndex, 0, unit.annexB.length, ptsUs, 0);

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outputIndex;
                while ((outputIndex = current.dequeueOutputBuffer(info, 0)) >= 0) {
                    current.releaseOutputBuffer(outputIndex, true);
                    if (!firstFrameShown && videoLayer != null) {
                        videoLayer.show();
                        firstFrameShown = true;
                    }
                }
            } catch (Throwable t) {
                logger.warn("queueAccessUnit failed: " + t.getMessage());
                decoderNeedsKeyframe = true;
            }
        }
    }

    /** Wait for the OEM fission display to materialise after the projection open. */
    private static boolean waitForFissionDisplay() {
        long deadline = SystemClock.elapsedRealtime() + PROJECTION_READY_TIMEOUT_MS;
        while (active && SystemClock.elapsedRealtime() < deadline) {
            if (hasFissionDisplay()) return true;
            try {
                Thread.sleep(PROJECTION_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean hasFissionDisplay() {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"dumpsys", "display"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(java.util.Locale.US);
                if (!lower.contains("fission")) continue;
                int index = lower.indexOf("displayid");
                if (index < 0) continue;
                int cursor = index + "displayid".length();
                while (cursor < line.length()
                        && (line.charAt(cursor) == ' ' || line.charAt(cursor) == '=')) cursor++;
                int start = cursor;
                while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) cursor++;
                if (cursor > start) {
                    try {
                        if (Integer.parseInt(line.substring(start, cursor)) > 0) return true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("fission display probe failed: " + t.getMessage());
        } finally {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static void stopInternal(boolean releaseProjection, String reason) {
        Thread rx;
        Thread decode;
        synchronized (STATE_LOCK) {
            if (!active && socket == null && decoder == null && videoLayer == null
                    && !projectionHeld) return;
            active = false;
            receiving = false;
            rx = receiverThread;
            decode = decoderThread;
            closeSocketLocked();
            if (queue != null) queue.clear();
            if (rx != null) rx.interrupt();
            if (decode != null) decode.interrupt();
            receiverThread = null;
            decoderThread = null;
            queue = null;
            depacketizer = null;
            if (reason != null && !reason.isEmpty()) lastError = reason;
        }
        releaseVideoPipeline(releaseProjection);
        if (reason == null || reason.isEmpty()) lastError = "";
        logger.info("Android Auto cluster bridge stopped"
                + (reason == null || reason.isEmpty() ? "" : ": " + reason));
    }

    private static void closeSocketLocked() {
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            try { current.close(); } catch (Throwable ignored) {}
        }
    }

    private static void releaseVideoPipeline(boolean releaseProjection) {
        synchronized (PIPELINE_LOCK) {
            MediaCodec currentDecoder = decoder;
            decoder = null;
            if (currentDecoder != null) {
                try { currentDecoder.stop(); } catch (Throwable ignored) {}
                try { currentDecoder.release(); } catch (Throwable ignored) {}
            }
            BsNativeLayer currentLayer = videoLayer;
            videoLayer = null;
            if (currentLayer != null) {
                try { currentLayer.release(); } catch (Throwable ignored) {}
            }
            projecting = false;
            firstFrameShown = false;
            firstRtpTimestamp = -1L;
            lastPresentationTimeUs = -1L;
            decoderNeedsKeyframe = true;
            boolean held = projectionHeld;
            projectionHeld = false;
            if (releaseProjection && held) {
                try {
                    ClusterProjectionController.getInstance()
                            .releaseSustained(PROJECTION_TOKEN);
                } catch (Throwable t) {
                    logger.warn("release sustained projection failed: " + t.getMessage());
                }
            }
        }
    }
}
