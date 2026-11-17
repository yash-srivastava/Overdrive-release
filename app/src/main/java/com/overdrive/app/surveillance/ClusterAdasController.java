package com.overdrive.app.surveillance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.overdrive.app.camera.dilink5.ClusterTransport;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.launcher.DiLink5ClusterCast;
import com.overdrive.app.util.ScratchRoot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static com.overdrive.app.surveillance.ClusterAdasLifecycle.Result;

/** Daemon-owned, optional cluster overlay controller for every physical projection source. */
public final class ClusterAdasController {
    private static final String PACKAGE = "com.byd.clusterdebug";
    private static final String COMPONENT = PACKAGE + "/.ClusterDebugService";
    private static final String ACTION = "com.byd.cluster.spi";
    private static final String EXTRA = "wholeFrame";
    private static final long RECEIVER_EXEMPTION_MS = 180_000L;
    /** {@code surveillance.clusterAdasHideEnabled}; absent = off, so the OEM box stays visible. */
    public static final String CONFIG_KEY = "clusterAdasHideEnabled";
    private static final long CONFIG_TTL_MS = 1_000L;
    private static volatile boolean hideEnabled;
    private static volatile long hideEnabledReadAt = -CONFIG_TTL_MS;
    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile ScheduledExecutorService worker;
    private static ClusterAdasLifecycle lifecycle; // Accessed only on worker.

    private ClusterAdasController() { }

    /** No probing, file IO, broadcasts or waits on the daemon startup thread. */
    public static void start() {
        if (!started.compareAndSet(false, true)) return;
        try {
            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cluster-overlay");
                t.setDaemon(true);
                return t;
            });
            worker.scheduleWithFixedDelay(ClusterAdasController::poll,
                    0L, 50L, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            log("Optional cluster overlay worker unavailable: " + failure.getMessage());
        }
    }

    /** Best effort wake after core shutdown. It never forces a live source idle or waits. */
    public static void requestRestoreIfIdle() {
        ScheduledExecutorService w = worker;
        if (w == null) return;
        try { w.execute(ClusterAdasController::poll); }
        catch (Throwable failure) { log("Optional cluster overlay restore wake skipped"); }
    }

    private static void poll() {
        try {
            if (lifecycle == null) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                lifecycle = new ClusterAdasLifecycle(new Backend());
            }
            lifecycle.tick(SystemClock.elapsedRealtime());
        } catch (Throwable failure) {
            // Optional work cannot terminate the scheduler or affect projection.
            log("Optional cluster overlay poll skipped: " + failure.getClass().getSimpleName());
        }
    }

    private static void log(String message) {
        try { CameraDaemon.log(message); } catch (Throwable ignored) { }
    }

    /** User opt-in, re-read at most once a second; any thread. Off blocks hide work, never restore. */
    static boolean hideEnabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - hideEnabledReadAt < CONFIG_TTL_MS) return hideEnabled;
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
            hideEnabled = s != null && s.optBoolean(CONFIG_KEY, false);
        } catch (Throwable keepLastValue) { }
        hideEnabledReadAt = now;
        return hideEnabled;
    }

    private static final class Backend implements ClusterAdasLifecycle.Backend {
        private final ClusterAdasOwnershipMarker marker = new ClusterAdasOwnershipMarker(
                new File(ScratchRoot.path("overdrive_cluster_overlay.owner")));
        private final String bootId = readBootId();
        private String alias;
        private volatile boolean capable;
        private long probeAfter;
        private Handler broadcastHandler;
        private boolean shellOnly;
        private String lastTransport;
        private final AtomicBoolean propertyStarted = new AtomicBoolean();
        private final NativeClusterProperties nativeProperties = new NativeClusterProperties();
        private final ClusterAdasPropertySampler properties = new ClusterAdasPropertySampler(
                new ClusterAdasPropertySampler.Backend() {
                    @Override public ClusterAdasLifecycle.Snapshot snapshot() {
                        return Backend.this.snapshot();
                    }
                    @Override public boolean canRead() {
                        return capable && Backend.this.canHide();
                    }
                    @Override public long nowMs() { return Backend.this.nowMs(); }
                    @Override public Integer readPower() {
                        return nativeProperties.read(0x12D0002A);
                    }
                    @Override public Integer readPlatform() {
                        return nativeProperties.read(0x40D00010);
                    }
                    @Override public void log(String message) { Backend.this.log(message); }
                });

        @Override public ClusterAdasLifecycle.Snapshot snapshot() {
            return new ClusterAdasLifecycle.Snapshot(
                    ClusterProjectionController.clusterAdasState(),
                    DiLink5ClusterCast.clusterAdasState());
        }
        @Override public ClusterAdasLifecycle.Ownership ownership() {
            return marker.ownership(bootId);
        }
        @Override public boolean canHide() {
            return com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                    && com.overdrive.app.monitor.AccMonitor.isAccOn()
                    && !ClusterProjectionController.isLegacyRestoreInFlight()
                    && !ClusterProjectionController.isLegacyProjectionAdmissionBlocked()
                    && hideEnabled();
        }
        @Override public long nowMs() { return SystemClock.elapsedRealtime(); }
        @Override public boolean acquireOwnership() { return marker.acquire(bootId); }
        @Override public boolean beginCommand() { return marker.begin(bootId); }
        @Override public boolean completeCommand() { return marker.complete(bootId); }
        @Override public boolean clearOwnership() { return marker.clear(); }
        @Override public void log(String message) { ClusterAdasController.log(message); }

        @Override public ClusterAdasLifecycle.ClusterState clusterState() {
            ClusterAdasLifecycle.Snapshot session = snapshot();
            if (!capable || !session.active() || !canHide()) {
                return ClusterAdasLifecycle.ClusterState.UNKNOWN;
            }
            if (propertyStarted.compareAndSet(false, true)) {
                try {
                    ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(() -> {
                            android.os.Process.setThreadPriority(
                                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
                            r.run();
                        }, "cluster-overlay-properties");
                        t.setDaemon(true);
                        return t;
                    });
                    reader.scheduleWithFixedDelay(properties::tick, 0L,
                            ClusterAdasPropertySampler.POWER_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (Throwable unavailable) {
                    log("Optional cluster overlay: property worker unavailable; "
                            + "initial hide and restoration remain available");
                }
            }
            return properties.state(session, nowMs());
        }

        @Override public boolean supported() {
            long now = SystemClock.elapsedRealtime();
            if (now < probeAfter) return capable;
            probeAfter = now + 60_000L;
            capable = false;
            alias = null;
            int uid = android.os.Process.myUid();
            if (uid != 2000 && uid != 0) return false;
            ShellResult pkg = run(2_000L, "/system/bin/pm", "path", PACKAGE);
            if (pkg.timedOut || !ClusterProjectionController.isPackagePathResult(
                    pkg.exitCode, pkg.output)) return false;
            // Read-only service discovery chooses one alias before any mutation.
            for (String name : new String[] {"AutoContainer", "auto_container"}) {
                ShellResult service = run(1_000L, "/system/bin/service", "check", name);
                if (service.exitCode == 0 && !service.timedOut
                        && service.output.trim().equals("Service " + name + ": found")) {
                    alias = name;
                    capable = true;
                    break;
                }
            }
            return capable;
        }

        @Override public Result prepare(BooleanSupplier guard) {
            return prepareReceiver(guard, new ReceiverCommands() {
                @Override public ShellResult startService() {
                    return run(2_000L, "/system/bin/am", "startservice", "-n", COMPONENT);
                }
                @Override public ShellResult temporaryExemption() {
                    // Only after an explicit background-start denial. This expires automatically
                    // and is long enough for the bounded initial frame sequence.
                    return run(2_000L, "/system/bin/cmd", "deviceidle", "tempwhitelist",
                            "-d", Long.toString(RECEIVER_EXEMPTION_MS), PACKAGE);
                }
                @Override public void log(String message) {
                    ClusterAdasController.log(message);
                }
            });
        }

        @Override public Result container(int opcode) {
            if (!ClusterAdasLifecycle.isOverlayOpcode(opcode)) return Result.REJECTED;
            if (alias == null) return Result.UNAVAILABLE;
            ShellResult r = run(2_000L, "/system/bin/service", "call", alias, "2",
                    "i32", "1000", "i32", Integer.toString(opcode), "s16", "");
            Result result = classifyContainer(r.exitCode, r.timedOut, r.started, r.output,
                    alias, ClusterTransport.isDiLink5());
            if (result != Result.COMPLETED) {
                log(shellFailure(alias + " channel=1000 opcode=" + opcode, result, r));
            }
            return result;
        }

        @Override public Result frames(String frame, int count, BooleanSupplier guard) {
            if (!ClusterAdasLifecycle.isFrame(frame) || count < 1 || count > 10) {
                return Result.REJECTED;
            }
            if (!guard.getAsBoolean()) return Result.CANCELLED;
            Context context = CameraDaemon.getAppContext(); // Cached; never creates a Context.
            if (context != null && !shellOnly) {
                try {
                    if (broadcastHandler == null) {
                        HandlerThread thread = new HandlerThread("cluster-overlay-broadcast",
                                android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        thread.start();
                        broadcastHandler = new Handler(thread.getLooper());
                    }
                } catch (Throwable unavailable) {
                    shellOnly = true;
                    log("Optional cluster overlay: ordered broadcast handler unavailable; "
                            + "using shell transport (" + unavailable.getClass().getSimpleName() + ")");
                    return shellFrames(frame, count, guard);
                }
                logTransport("ordered");
                OrderedBatch batch = new OrderedBatch(context, broadcastHandler, frame, count, guard);
                Result result = batch.run();
                // Permission denial before the FIRST dispatch permits a shell fallback.
                // An ambiguous or partial batch never repeats through another transport.
                if (result != Result.UNAVAILABLE || batch.sent != 0) {
                    if (result != Result.COMPLETED && result != Result.CANCELLED) {
                        log("Optional cluster overlay: ordered frame batch " + result
                                + " (frame=" + frame + ", sent=" + batch.sent
                                + ", requested=" + count + ")");
                    }
                    return result;
                }
                shellOnly = true;
                log("Optional cluster overlay: ordered dispatch unavailable before first packet; "
                        + "using shell transport (" + batch.failure + ")");
            }
            return shellFrames(frame, count, guard);
        }

        private Result shellFrames(String frame, int count, BooleanSupplier guard) {
            logTransport("shell");
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            for (int n = 0; n < count; n++) {
                if (!guard.getAsBoolean()) return Result.CANCELLED;
                long budget = deadline - SystemClock.elapsedRealtime();
                if (budget <= 0L) return Result.REJECTED; // No command remains in flight.
                ShellResult r = run(Math.min(2_000L, budget), "/system/bin/am", "broadcast",
                        "-a", ACTION, "-p", PACKAGE, "-f",
                        Integer.toString(Intent.FLAG_RECEIVER_FOREGROUND), "--es", EXTRA, frame);
                Result result = classifyBroadcast(r.exitCode, r.timedOut, r.started, r.output);
                if (result != Result.COMPLETED) {
                    log(shellFailure("frame broadcast " + frame, result, r));
                    return result;
                }
            }
            return Result.COMPLETED;
        }

        private void logTransport(String transport) {
            if (transport.equals(lastTransport)) return;
            lastTransport = transport;
            log("Optional cluster overlay: broadcast transport=" + transport
                    + ", receiver flag=0x10000000");
        }
    }

    /** Exact read-only API recovered from the property helper; no setters or device activation. */
    private static final class NativeClusterProperties {
        private Object manager; // Accessed only on the property worker.
        private java.lang.reflect.Method getInt;
        private long probeAfter;

        Integer read(int property) {
            if (property != 0x12D0002A && property != 0x40D00010) return null;
            if (SystemClock.elapsedRealtime() < probeAfter) return null;
            Context context = CameraDaemon.getAppContext();
            if (context == null) return null;
            try {
                if (manager == null) {
                    Class<?> type = Class.forName("android.hardware.BYDAutoManager");
                    java.lang.reflect.Method method = type.getMethod(
                            "getInt", int.class, int.class);
                    manager = type.getConstructor(Context.class).newInstance(context);
                    getInt = method;
                    log("Optional cluster overlay: read-only cluster property API available");
                }
                Object value = getInt.invoke(manager, 1000, property);
                return value instanceof Integer ? (Integer) value : null;
            } catch (Throwable unavailable) {
                manager = null;
                getInt = null;
                probeAfter = SystemClock.elapsedRealtime() + 60_000L;
                log("Optional cluster overlay: cluster property read unavailable ("
                        + unavailable.getClass().getSimpleName()
                        + "); initial hide and restoration remain available");
                return null;
            }
        }
    }

    private static final class OrderedBatch {
        private final Context context;
        private final Handler handler;
        private final String frame;
        private final BooleanSupplier guard;
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile boolean terminal;
        private volatile Result result = Result.UNKNOWN;
        private volatile int sent;
        private volatile String failure = "handler rejected dispatch";
        private int remaining;

        OrderedBatch(Context context, Handler handler, String frame, int count,
                BooleanSupplier guard) {
            this.context = context;
            this.handler = handler;
            this.frame = frame;
            this.remaining = count;
            this.guard = guard;
        }

        Result run() {
            try {
                if (!handler.post(this::next)) return Result.UNAVAILABLE;
                if (!completion.await(5_000L, TimeUnit.MILLISECONDS)) {
                    terminal = true; // A late callback may finish, but cannot send another packet.
                    return Result.UNKNOWN;
                }
                return result;
            } catch (InterruptedException interrupted) {
                terminal = true;
                Thread.currentThread().interrupt();
                return Result.UNKNOWN;
            }
        }

        private void next() {
            if (terminal) return;
            try {
                if (!guard.getAsBoolean()) {
                    finish(Result.CANCELLED);
                    return;
                }
                if (remaining == 0) {
                    finish(Result.COMPLETED);
                    return;
                }
                Intent intent = new Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA, frame)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent response) {
                        if (terminal) return;
                        remaining--;
                        next();
                    }
                }, handler, 0, null, null);
                sent++;
            } catch (SecurityException denied) {
                failure = "SecurityException";
                finish(sent == 0 ? Result.UNAVAILABLE : Result.REJECTED);
            } catch (Throwable failure) {
                finish(Result.UNKNOWN);
            }
        }

        private void finish(Result value) {
            result = value;
            terminal = true;
            completion.countDown();
        }
    }

    static Result classifyContainer(int exitCode, boolean timedOut, boolean started,
            String output, String alias, boolean diLink5) {
        if (!started) return Result.UNAVAILABLE;
        if (timedOut) return Result.UNKNOWN;
        if (ClusterProjectionController.isDiLink5ServiceUnavailableOutput(output, alias)) {
            return Result.UNAVAILABLE;
        }
        if (exitCode != 0) return Result.UNKNOWN;
        Integer status = ClusterProjectionController.parseDiLink5ServiceCallStatus(output);
        if (status == null) return Result.UNKNOWN;
        if (status != 0) return Result.REJECTED;
        Integer nativeResult = ClusterProjectionController.parseDiLink5ServiceCallResult(output);
        if (nativeResult == null) return diLink5 ? Result.UNKNOWN : Result.COMPLETED;
        return nativeResult >= 0 ? Result.COMPLETED : Result.REJECTED;
    }

    static Result classifyBroadcast(int exitCode, boolean timedOut, boolean started, String output) {
        if (!started) return Result.UNAVAILABLE;
        if (timedOut) return Result.UNKNOWN;
        if (exitCode == 0 && output != null) {
            for (String line : output.split("\\r?\\n")) {
                if (line.trim().matches("Broadcast completed: result=-?\\d+.*")) {
                    return Result.COMPLETED;
                }
            }
        }
        if (output != null && (output.contains("Permission Denial")
                || output.contains("SecurityException"))) return Result.REJECTED;
        return Result.UNKNOWN;
    }

    private static String readBootId() {
        try {
            byte[] data = Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath());
            return data.length <= 128 ? new String(data, StandardCharsets.UTF_8).trim() : "";
        } catch (Throwable unavailable) { return ""; }
    }

    interface ReceiverCommands {
        ShellResult startService();
        ShellResult temporaryExemption();
        void log(String message);
    }

    /** A rejected start is retried once only for Android's explicit background restriction. */
    static Result prepareReceiver(BooleanSupplier guard, ReceiverCommands commands) {
        if (!guard.getAsBoolean()) return Result.CANCELLED;
        ShellResult started = commands.startService();
        Result result = classifyReceiverStart(started);
        if (result == Result.REJECTED && isBackgroundStartDenied(started.output)) {
            commands.log(shellFailure("receiver start", result, started)
                    + "; attempting temporary background exemption");
            if (!guard.getAsBoolean()) return Result.CANCELLED;
            ShellResult exemption = commands.temporaryExemption();
            Result exemptionResult = classifyTemporaryExemption(exemption);
            if (exemptionResult != Result.COMPLETED) {
                commands.log(shellFailure("temporary receiver exemption", exemptionResult,
                        exemption));
                return exemptionResult;
            }
            if (!guard.getAsBoolean()) return Result.CANCELLED;
            started = commands.startService();
            result = classifyReceiverStart(started);
        }
        if (result != Result.COMPLETED) {
            commands.log(shellFailure("receiver start", result, started));
        }
        return result;
    }

    private static Result classifyReceiverStart(ShellResult r) {
        if (!r.started) return Result.UNAVAILABLE;
        if (r.timedOut) return Result.UNKNOWN;
        return ClusterProjectionController.isSuccessfulServiceStartResult(
                r.exitCode, false, r.output) ? Result.COMPLETED : Result.REJECTED;
    }

    private static boolean isBackgroundStartDenied(String output) {
        if (output == null) return false;
        for (String line : output.split("\\r?\\n")) {
            if (line.trim().startsWith("Error: app is in background uid ")) return true;
        }
        return false;
    }

    private static Result classifyTemporaryExemption(ShellResult r) {
        if (!r.started) return Result.UNAVAILABLE;
        if (r.timedOut) return Result.UNKNOWN;
        if (r.exitCode != 0) return Result.REJECTED;
        // The supported cmd deviceidle mutation has no success output. Anything else is
        // unverified; do not start the receiver after an ambiguous exemption result.
        return r.output != null && r.output.trim().isEmpty()
                ? Result.COMPLETED : Result.UNKNOWN;
    }

    private static String shellFailure(String operation, Result result, ShellResult r) {
        String output = r.output == null ? "" : r.output.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (output.length() > 640) output = output.substring(0, 640) + "...";
        return "Optional cluster overlay: " + operation + " " + result
                + " (exit=" + r.exitCode + ", started=" + r.started
                + ", timeout=" + r.timedOut + ", output=" + output + ")";
    }

    static final class ShellResult {
        final int exitCode;
        final boolean timedOut;
        final boolean started;
        final String output;
        ShellResult(int exitCode, boolean timedOut, boolean started, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.started = started;
            this.output = output;
        }
    }

    /** Explicit argv, bounded output/drain and deadlines; timeout is an unknown mutation. */
    private static ShellResult run(long timeoutMs, String... command) {
        Process process = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final InputStream stream = process.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buffer = new byte[1024];
                try {
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        synchronized (output) {
                            int keep = Math.min(count, 4096 - output.size());
                            if (keep > 0) output.write(buffer, 0, keep);
                        }
                    }
                } catch (Throwable ignored) { }
            }, "cluster-overlay-output");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ShellResult(-1, true, true, "");
            }
            drain.join(100L);
            String text;
            synchronized (output) { text = new String(output.toByteArray(), StandardCharsets.UTF_8); }
            return new ShellResult(process.exitValue(), false, true, text);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ShellResult(-1, true, process != null, "");
        } catch (Throwable failure) {
            return new ShellResult(-1, false, process != null, failure.getClass().getSimpleName());
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (Throwable ignored) { }
                try { process.destroyForcibly(); } catch (Throwable ignored) { }
            }
        }
    }
}
