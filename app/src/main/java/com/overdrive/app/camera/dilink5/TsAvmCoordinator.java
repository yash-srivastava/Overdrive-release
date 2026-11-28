package com.overdrive.app.camera.dilink5;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;
import com.ts.avm.IAvmServiceInterface;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Keeps the DiLink 5 AVM service awake for the QCarCam capture process. */
public final class TsAvmCoordinator {

    public static final String ACTION_START_AVM =
            "com.overdrive.app.action.DILINK5_START_AVM";
    public static final String ACTION_STOP_AVM =
            "com.overdrive.app.action.DILINK5_STOP_AVM";
    public static final String ACTION_PROBE_AVM =
            "com.overdrive.app.action.DILINK5_PROBE_AVM";
    public static final String EXTRA_ACK_PORT =
            "com.overdrive.app.extra.DILINK5_AVM_ACK_PORT";
    public static final String EXTRA_ACK_TOKEN =
            "com.overdrive.app.extra.DILINK5_AVM_ACK_TOKEN";
    private static final String APP_SERVICE =
            "com.overdrive.app/.services.DaemonKeepaliveService";
    private static final String AVM_STATE_PREFS = "dilink5_avm_state";
    private static final String AVM_STATE_BOOT = "boot";
    private static final String AVM_STATE_VALUE = "state";
    private static final String AVM_STATE_LEGACY_OWNED = "owned";
    static final int AVM_OWNERSHIP_NONE = 0;
    static final int AVM_OWNERSHIP_STARTING = 1;
    static final int AVM_OWNERSHIP_OWNED = 2;
    static final long AVM_REQUEST_TIMEOUT_MS = 5_000L;
    static final long AVM_RECOVERY_REQUEST_TIMEOUT_MS = 10_000L;
    static final long AVM_PENDING_START_GUARD_MS = 5_000L;
    static final long AVM_APP_REQUEST_TIMEOUT_MS = 18_000L;
    static final long AVM_PREFLIGHT_APP_REQUEST_TIMEOUT_MS = 7_000L;
    static final long AVM_SERVICE_PROBE_TIMEOUT_MS = 3_000L;
    private static final long AVM_BIND_RETRY_MS = 250L;
    private static final long AVM_STATUS_POLL_MS = 75L;
    private static final int AVM_DISPATCH_ATTEMPTS = 2;
    static final int AVM_PREFLIGHT_DISPATCH_ATTEMPTS = 3;
    private static final long AVM_PREFLIGHT_RETRY_DELAY_MS = 250L;
    private static final long AVM_REQUEST_CANCEL_POLL_MS = 200L;
    private static final int AVM_STATUS_GETTER_IDLE = -1;
    private static final int AVM_STATUS_IDLE = 0;
    private static final int AVM_STATUS_FOREGROUND = 2;
    private static final int AVM_STATUS_BACKGROUND = 3;
    private static final int AVM_STATUS_DISABLED = 4;
    private static final int AVM_STATUS_ERROR = 5;
    static final int BINDER_LANE_NONE = 0;
    static final int BINDER_LANE_PRIMARY = 1;
    static final int BINDER_LANE_RECOVERY = 2;
    private static final DaemonLogger logger =
            DaemonLogger.getInstance("TsAvmCoordinator");
    private static volatile TsAvmCoordinator instance;

    private final Context context;
    private final String bootId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BinderCallLane binderCalls = new BinderCallLane();
    private final AtomicBoolean bindRequested = new AtomicBoolean(false);
    private final Set<VendorCall> physicalCalls = new HashSet<>();
    private volatile IAvmServiceInterface avmService;
    private volatile boolean desiredStart;
    private ServiceConnection connection;
    private long connectionEpoch;
    private long desiredGeneration;
    private Boolean appliedStart;
    private AvmRequest activeRequest;
    private VendorCall activeCall;
    private AvmRequest pendingRequest;
    private Runnable stopCompletion;
    private volatile int avmOwnershipState;
    private volatile boolean avmForegroundConfirmed;

    public static TsAvmCoordinator getInstance(Context context) {
        TsAvmCoordinator current = instance;
        if (current != null) return current;
        synchronized (TsAvmCoordinator.class) {
            if (instance == null) {
                instance = new TsAvmCoordinator(context.getApplicationContext());
            }
            return instance;
        }
    }

    private TsAvmCoordinator(Context context) {
        this.context = context;
        bootId = readBootId();
        avmOwnershipState = readOwnershipState(context, bootId);
    }

    public static boolean requestStartInAppProcess() {
        return requestStartInAppProcess(null);
    }

    static boolean requestStartInAppProcess(
            BooleanSupplier shouldContinue) {
        if (!DiLink5Platform.isEnabled()) return false;
        return requestInAppProcess(
                ACTION_START_AVM, "start", shouldContinue);
    }

    public static boolean requestStopInAppProcess() {
        return requestStopInAppProcess(null);
    }

    static boolean requestStopInAppProcess(
            BooleanSupplier shouldContinue) {
        return requestInAppProcess(
                ACTION_STOP_AVM, "stop", shouldContinue);
    }

    public static boolean requestProbeInAppProcess() {
        return Boolean.TRUE.equals(requestProbeResultInAppProcess());
    }

    /**
     * @return true when the app confirmed AVM, false when it explicitly
     * rejected the probe or the local request thread was interrupted, or null
     * when only the acknowledgement transport timed out.
     */
    public static Boolean requestProbeResultInAppProcess() {
        return requestProbeResultInAppProcess(null);
    }

    static Boolean requestProbeResultInAppProcess(
            BooleanSupplier shouldContinue) {
        if (!DiLink5Platform.isEnabled()) return Boolean.FALSE;
        boolean explicitlyRejected = false;
        for (int attempt = 1;
                attempt <= AVM_PREFLIGHT_DISPATCH_ATTEMPTS;
                attempt++) {
            if (!shouldContinue(shouldContinue)) return Boolean.FALSE;
            long deadline = SystemClock.elapsedRealtime()
                    + AVM_PREFLIGHT_APP_REQUEST_TIMEOUT_MS;
            Boolean result = dispatchAndAwaitResult(
                    ACTION_PROBE_AVM,
                    "probe",
                    deadline,
                    shouldContinue);
            if (!shouldContinue(shouldContinue)) return Boolean.FALSE;
            if (Boolean.TRUE.equals(result)) return true;
            if (Boolean.FALSE.equals(result)) {
                explicitlyRejected = true;
            }
            logger.warn("AVM preflight probe attempt " + attempt + "/"
                    + AVM_PREFLIGHT_DISPATCH_ATTEMPTS
                    + (result == null
                        ? " was not acknowledged"
                        : " was rejected"));
            if (!shouldRetryPreflightProbe(result, attempt)) break;
            try {
                if (!sleepWhileAllowed(
                        AVM_PREFLIGHT_RETRY_DELAY_MS,
                        shouldContinue)) {
                    return Boolean.FALSE;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Boolean.FALSE;
            }
        }
        return explicitlyRejected ? Boolean.FALSE : null;
    }

    static boolean shouldRetryPreflightProbe(
            Boolean result, int completedAttempts) {
        return !Boolean.TRUE.equals(result)
                && completedAttempts < AVM_PREFLIGHT_DISPATCH_ATTEMPTS;
    }

    private static boolean requestInAppProcess(
            String action,
            String operation,
            BooleanSupplier shouldContinue) {
        for (int attempt = 1; attempt <= AVM_DISPATCH_ATTEMPTS; attempt++) {
            if (!shouldContinue(shouldContinue)) return false;
            long deadline = appRequestDeadline(
                    SystemClock.elapsedRealtime());
            Boolean result = dispatchAndAwaitResult(
                    action, operation, deadline, shouldContinue);
            if (!shouldContinue(shouldContinue)) return false;
            if (result != null) return result;
        }
        return false;
    }

    private static Boolean dispatchAndAwaitResult(
            String action,
            String operation,
            long deadline,
            BooleanSupplier shouldContinue) {
        if (!shouldContinue(shouldContinue)) return Boolean.FALSE;
        try (java.net.ServerSocket server = new java.net.ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), 0), 1);
            String token = java.util.UUID.randomUUID()
                    .toString().replace("-", "");
            Process request = new ProcessBuilder(
                    "/system/bin/am",
                    "start-foreground-service",
                    "-n", APP_SERVICE,
                    "-a", action,
                    "--es", EXTRA_ACK_PORT,
                    Integer.toString(server.getLocalPort()),
                    "--es", EXTRA_ACK_TOKEN, token)
                    .redirectErrorStream(true)
                    .start();
            long launchBudget = Math.min(
                    2_000L, deadline - SystemClock.elapsedRealtime());
            long launchDeadline =
                    SystemClock.elapsedRealtime() + Math.max(0L, launchBudget);
            while (request.isAlive()
                    && SystemClock.elapsedRealtime() < launchDeadline
                    && shouldContinue(shouldContinue)) {
                long remaining =
                        launchDeadline - SystemClock.elapsedRealtime();
                request.waitFor(
                        Math.max(1L, Math.min(
                                AVM_REQUEST_CANCEL_POLL_MS, remaining)),
                        TimeUnit.MILLISECONDS);
            }
            if (!shouldContinue(shouldContinue)) {
                request.destroyForcibly();
                return Boolean.FALSE;
            }
            if (launchBudget <= 0L || request.isAlive()) {
                request.destroyForcibly();
                try {
                    request.waitFor(250L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                String timeoutOutput = request.isAlive()
                        ? "" : readBoundedProcessOutput(request);
                logger.warn("Timed out dispatching app-process AVM " + operation
                        + formatDispatchOutput(timeoutOutput));
                return null;
            }
            String dispatchOutput = readBoundedProcessOutput(request);
            if (!shouldContinue(shouldContinue)) return Boolean.FALSE;
            if (request.exitValue() != 0) {
                logger.warn("App-process AVM " + operation
                        + " dispatch exited with " + request.exitValue()
                        + formatDispatchOutput(dispatchOutput));
                return null;
            }

            while (SystemClock.elapsedRealtime() < deadline) {
                if (!shouldContinue(shouldContinue)) return Boolean.FALSE;
                long remaining = deadline - SystemClock.elapsedRealtime();
                server.setSoTimeout((int) Math.max(
                        1L, Math.min(
                                AVM_REQUEST_CANCEL_POLL_MS, remaining)));
                try (java.net.Socket client = server.accept()) {
                    if (!shouldContinue(shouldContinue)) return Boolean.FALSE;
                    client.setSoTimeout((int) AVM_REQUEST_CANCEL_POLL_MS);
                    java.io.DataInputStream input =
                            new java.io.DataInputStream(client.getInputStream());
                    if (!token.equals(input.readUTF())) continue;
                    boolean success = "ok".equals(input.readUTF());
                    String reason = input.readUTF();
                    if (success) {
                        logger.info("Confirmed AVM " + operation
                                + " in the app process.");
                    } else {
                        logger.warn("App-process AVM " + operation
                                + " failed"
                                + (reason.isEmpty() ? "" : ": " + reason));
                    }
                    return success;
                } catch (java.net.SocketTimeoutException timeout) {
                    // Poll the caller's generation rather than blocking for
                    // the full multi-second acknowledgement deadline.
                }
            }
            logger.warn("Timed out waiting for app-process AVM "
                    + operation + " acknowledgement"
                    + formatDispatchOutput(dispatchOutput));
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable failure) {
            logger.warn("Failed to request app-process AVM " + operation
                    + ": " + failure.getMessage());
        }
        return null;
    }

    private static boolean shouldContinue(BooleanSupplier predicate) {
        if (predicate == null) return true;
        try {
            return predicate.getAsBoolean();
        } catch (Throwable failure) {
            logger.warn("AVM request cancellation predicate failed: "
                    + failure.getMessage());
            return false;
        }
    }

    private static boolean sleepWhileAllowed(
            long delayMs, BooleanSupplier predicate)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime()
                + Math.max(0L, delayMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!shouldContinue(predicate)) return false;
            long remaining = deadline - SystemClock.elapsedRealtime();
            Thread.sleep(Math.max(
                    1L, Math.min(
                            AVM_REQUEST_CANCEL_POLL_MS, remaining)));
        }
        return shouldContinue(predicate);
    }

    private static String readBoundedProcessOutput(Process process) {
        if (process == null) return "";
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            char[] buffer = new char[512];
            int count;
            while ((count = reader.read(buffer)) > 0 && output.length() < 4096) {
                output.append(
                        buffer, 0,
                        Math.min(count, 4096 - output.length()));
            }
        } catch (Throwable ignored) {
        }
        return output.toString();
    }

    private static String formatDispatchOutput(String output) {
        if (output == null) return "";
        String compact = output.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.isEmpty() ? "" : " (am: " + compact + ")";
    }

    /**
     * Confirms that the app process can bind the vendor AVM service and obtain
     * a status without changing AVM ownership or foreground state.
     */
    public static void probeAvmService(
            Context context, RequestCompletion completion) {
        if (context == null) {
            completeProbe(completion, false);
            return;
        }
        new AvmServiceProbe(
                context.getApplicationContext(), completion).start();
    }

    static boolean isResponsiveAvmStatus(int status) {
        return status >= AVM_STATUS_GETTER_IDLE
                && status <= AVM_STATUS_DISABLED;
    }

    private static void completeProbe(
            RequestCompletion completion, boolean success) {
        if (completion == null) return;
        try {
            completion.complete(success);
        } catch (Throwable failure) {
            logger.warn("AVM probe completion failed: "
                    + failure.getMessage());
        }
    }

    private static Handler createAvmProbeHandler() {
        HandlerThread thread =
                new HandlerThread("DiLink5AvmProbe");
        thread.start();
        return new Handler(thread.getLooper());
    }

    private static Handler avmProbeHandler() {
        return AvmProbeHandlerHolder.HANDLER;
    }

    private static final class AvmProbeHandlerHolder {
        static final Handler HANDLER = createAvmProbeHandler();
    }

    private static Handler createAvmAckHandler() {
        HandlerThread thread =
                new HandlerThread("DiLink5AvmAck");
        thread.start();
        return new Handler(thread.getLooper());
    }

    private static Handler avmAckHandler() {
        return AvmAckHandlerHolder.HANDLER;
    }

    private static final class AvmAckHandlerHolder {
        static final Handler HANDLER = createAvmAckHandler();
    }

    private static final class AvmServiceProbe {
        private final Context context;
        private final RequestCompletion completion;
        private final Handler mainHandler =
                new Handler(Looper.getMainLooper());
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private boolean bound;

        private final Runnable timeout = () -> finish(false);

        private final ServiceConnection connection =
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(
                            ComponentName name, IBinder binder) {
                        IAvmServiceInterface service =
                                IAvmServiceInterface.Stub.asInterface(binder);
                        avmProbeHandler().post(() -> {
                            boolean success = false;
                            try {
                                success = service != null
                                        && isResponsiveAvmStatus(
                                                service.getAvmStatus());
                            } catch (Throwable failure) {
                                logger.warn("AVM Binder preflight failed: "
                                        + failure.getMessage());
                            }
                            boolean result = success;
                            mainHandler.post(() -> finish(result));
                        });
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        finish(false);
                    }

                    @Override
                    public void onBindingDied(ComponentName name) {
                        finish(false);
                    }
                };

        AvmServiceProbe(
                Context context, RequestCompletion completion) {
            this.context = context;
            this.completion = completion;
        }

        void start() {
            mainHandler.post(() -> {
                try {
                    Intent intent = new Intent().setComponent(
                            new ComponentName(
                                    "com.ts.avm",
                                    "com.ts.avm.AvmAndroidService"));
                    bound = context.bindService(
                            intent,
                            connection,
                            Context.BIND_AUTO_CREATE);
                    if (!bound) {
                        finish(false);
                        return;
                    }
                    mainHandler.postDelayed(
                            timeout, AVM_SERVICE_PROBE_TIMEOUT_MS);
                } catch (Throwable failure) {
                    logger.warn("Unable to bind AVM for preflight: "
                            + failure.getMessage());
                    finish(false);
                }
            });
        }

        private void finish(boolean success) {
            if (!finished.compareAndSet(false, true)) return;
            mainHandler.removeCallbacks(timeout);
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (Throwable ignored) {
                }
                bound = false;
            }
            completeProbe(completion, success);
        }
    }

    /**
     * Queues an AVM acknowledgement on the dedicated worker thread.
     *
     * <p>Service lifecycle and AVM callbacks are delivered on Android's main
     * thread. Opening even a loopback socket there can throw
     * {@code NetworkOnMainThreadException} and strand the daemon until timeout.
     * Parse and validate the immutable reply target synchronously, then perform
     * all socket I/O off-main.
     *
     * @return true when a validated acknowledgement was queued
     */
    public static boolean sendAppProcessResult(
            Intent request, boolean success, String reason) {
        if (request == null) return false;
        String portValue = request.getStringExtra(EXTRA_ACK_PORT);
        String token = request.getStringExtra(EXTRA_ACK_TOKEN);
        if (portValue == null || token == null
                || !token.matches("[0-9a-f]{32}")) {
            return false;
        }
        try {
            int port = Integer.parseInt(portValue);
            if (port <= 0 || port > 65_535) return false;
            String detail = reason == null ? "" : reason;
            boolean queued = avmAckHandler().post(
                    () -> sendAppProcessResultNow(
                            port, token, success, detail));
            if (!queued) {
                logger.warn("Unable to queue app-process AVM result.");
            }
            return queued;
        } catch (Throwable failure) {
            logger.warn("Unable to queue app-process AVM result: "
                    + failure.getMessage());
            return false;
        }
    }

    private static void sendAppProcessResultNow(
            int port, String token, boolean success, String reason) {
        try {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(
                        "127.0.0.1", port), 1_000);
                java.io.DataOutputStream output =
                        new java.io.DataOutputStream(socket.getOutputStream());
                output.writeUTF(token);
                output.writeUTF(success ? "ok" : "failed");
                output.writeUTF(reason);
                output.flush();
            }
        } catch (Throwable failure) {
            logger.warn("Unable to return app-process AVM result: "
                    + failure.getMessage());
        }
    }

    private ServiceConnection createConnection(long epoch) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!isCurrentConnection(epoch, this)) return;
                avmService = IAvmServiceInterface.Stub.asInterface(binder);
                appliedStart = null;
                logger.info("Connected to com.ts.avm.AvmAndroidService");
                dispatchPendingRequest();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (!isCurrentConnection(epoch, this)) return;
                logger.info("Disconnected from com.ts.avm.AvmAndroidService");
                handleServiceLoss(epoch);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                if (!isCurrentConnection(epoch, this)) return;
                handleServiceLoss(epoch);
            }
        };
    }

    private boolean isCurrentConnection(
            long epoch, ServiceConnection candidate) {
        return epoch == connectionEpoch && connection == candidate;
    }

    public void bind() {
        if (!bindRequested.compareAndSet(false, true)) return;
        long epoch = ++connectionEpoch;
        ServiceConnection nextConnection = createConnection(epoch);
        connection = nextConnection;
        try {
            Intent intent = new Intent().setComponent(new ComponentName(
                    "com.ts.avm", "com.ts.avm.AvmAndroidService"));
            boolean bound = context.bindService(
                    intent, nextConnection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                abandonBind(epoch, nextConnection);
                retryPendingBind();
            }
            logger.info("bindService com.ts.avm returned " + bound);
        } catch (Exception e) {
            abandonBind(epoch, nextConnection);
            logger.warn("Failed to bind com.ts.avm: " + e.getMessage());
            retryPendingBind();
        }
    }

    private void abandonBind(long epoch, ServiceConnection failedConnection) {
        if (isCurrentConnection(epoch, failedConnection)) {
            connection = null;
            connectionEpoch++;
        }
        bindRequested.set(false);
        avmService = null;
        appliedStart = null;
    }

    @FunctionalInterface
    public interface RequestCompletion {
        void complete(boolean success);
    }

    public void startAvm() {
        startAvm(null);
    }

    public void startAvm(RequestCompletion completion) {
        mainHandler.post(() -> submitRequest(true, completion, null));
    }

    public void stopAvm() {
        stopAvm(null);
    }

    public void stopAvm(RequestCompletion completion) {
        stopAvm(completion, null);
    }

    public void stopAvm(
            RequestCompletion completion, Runnable whenStopped) {
        mainHandler.post(() -> submitRequest(
                false, completion, whenStopped));
    }

    private void submitRequest(
            boolean start,
            RequestCompletion completion,
            Runnable whenStopped) {
        long generation = ++desiredGeneration;
        desiredStart = start;
        stopCompletion = start ? null
                : whenStopped != null ? whenStopped : stopCompletion;
        AvmRequest request = new AvmRequest(
                start,
                SystemClock.elapsedRealtime() + AVM_REQUEST_TIMEOUT_MS,
                generation,
                completion);
        if (activeRequest != null && activeRequest.start != start) {
            AvmRequest supersededActive = activeRequest;
            VendorCall supersededCall = activeCall;
            activeRequest = null;
            activeCall = null;
            if (supersededCall != null) {
                binderCalls.abandon(supersededCall);
                appliedStart = null;
            }
            failRequest(supersededActive);
        }
        if (!start) request.unresolvedStart = newestUnresolvedStartCall();
        request.suppressInternalReconcile =
                shouldSuppressInternalReconcile(
                        request.start,
                        avmOwnershipState,
                        avmForegroundConfirmed,
                        request.unresolvedStart != null);
        AvmRequest superseded = pendingRequest;
        if (superseded != null) failRequest(superseded);
        pendingRequest = request;
        scheduleExpiry(request);
        settleAppliedRequest();
        if (pendingRequest == null) return;
        bind();
        dispatchPendingRequest();
    }

    private void expireRequest(AvmRequest request) {
        if (request.acknowledged) return;
        long remaining = request.deadlineElapsedMs
                - SystemClock.elapsedRealtime();
        if (remaining > 0L) {
            mainHandler.postDelayed(() -> expireRequest(request), remaining);
            return;
        }
        boolean current = false;
        VendorCall expiredCall = null;
        if (activeRequest == request) {
            expiredCall = activeCall;
            activeRequest = null;
            activeCall = null;
            if (expiredCall != null) binderCalls.abandon(expiredCall);
            current = true;
        }
        if (pendingRequest == request) {
            pendingRequest = null;
            current = true;
        }
        if (!current) return;
        failRequest(request);
        boolean reconcile = shouldReconcileExpiredRequest(
                request.start,
                request.generation,
                desiredStart,
                desiredGeneration,
                request.suppressInternalReconcile);
        if (reconcile) {
            queueDesiredState();
        } else if (request.suppressInternalReconcile) {
            unbind();
        }
        finishTerminalStopCompletionIfCurrent(request);
        if (pendingRequest != null) {
            bind();
            dispatchPendingRequest();
        }
    }

    private void scheduleExpiry(AvmRequest request) {
        long delay = Math.max(
                1L, request.deadlineElapsedMs - SystemClock.elapsedRealtime());
        mainHandler.postDelayed(() -> expireRequest(request), delay);
    }

    static boolean shouldReconcileExpiredRequest(
            boolean requestStart,
            long requestGeneration,
            boolean currentDesiredStart,
            long currentGeneration,
            boolean suppressInternalReconcile) {
        return !suppressInternalReconcile
                && requestStart == currentDesiredStart
                && requestGeneration == currentGeneration;
    }

    static boolean shouldSuppressInternalReconcile(
            boolean start,
            int ownershipState,
            boolean foregroundConfirmed,
            boolean unresolvedStartPresent) {
        return !start
                && ownershipState == AVM_OWNERSHIP_STARTING
                && !foregroundConfirmed
                && !unresolvedStartPresent;
    }

    private void handleServiceLoss(long epoch) {
        if (epoch != connectionEpoch) return;
        appliedStart = null;
        AvmRequest request = activeRequest;
        VendorCall call = activeCall;
        boolean requeued = false;
        activeRequest = null;
        activeCall = null;
        if (call != null) {
            call.invalidated = true;
            binderCalls.abandon(call);
        }
        invalidatePhysicalCalls(epoch);
        if (request != null && !request.acknowledged
                && request.start == desiredStart
                && request.generation == desiredGeneration
                && SystemClock.elapsedRealtime() < request.deadlineElapsedMs) {
            if (pendingRequest != null && pendingRequest != request) {
                failRequest(pendingRequest);
            }
            pendingRequest = request;
            requeued = true;
        } else if (request != null) {
            failRequest(request);
            if (shouldReconcileExpiredRequest(
                    request.start,
                    request.generation,
                    desiredStart,
                    desiredGeneration,
                    request.suppressInternalReconcile)) {
                queueDesiredState();
            }
        }
        unbind();
        if (!requeued) {
            finishTerminalStopCompletionIfCurrent(request);
        }
        retryPendingBind();
    }

    private void retryPendingBind() {
        AvmRequest request = pendingRequest;
        if (request == null || activeRequest != null) return;
        mainHandler.postDelayed(() -> {
            if (pendingRequest == request && activeRequest == null
                    && avmService == null) {
                bind();
            }
        }, AVM_BIND_RETRY_MS);
    }

    private void dispatchPendingRequest() {
        IAvmServiceInterface service = avmService;
        AvmRequest request = pendingRequest;
        if (service == null || request == null || activeRequest != null) return;
        long epoch = connectionEpoch;
        if (!request.acknowledged
                && SystemClock.elapsedRealtime() >= request.deadlineElapsedMs) {
            expireRequest(request);
            request = pendingRequest;
            if (request == null) return;
        }
        pendingRequest = null;
        activeRequest = request;
        request.deadlineElapsedMs = deadlineAfterDispatch(
                request.deadlineElapsedMs,
                !request.start && hasAvmResponsibility(),
                SystemClock.elapsedRealtime());
        AvmRequest dispatched = request;
        VendorCall call = new VendorCall(dispatched, epoch);
        activeCall = call;
        boolean posted = binderCalls.post(call, () -> {
            Throwable failure = null;
            try {
                invokeAndConfirm(service, call);
            } catch (Throwable error) {
                failure = error;
            } finally {
                call.completed = true;
                binderCalls.complete(call);
            }
            Throwable result = failure;
            mainHandler.post(() -> completeVendorCall(call, result));
        });
        if (posted) {
            physicalCalls.add(call);
            appliedStart = null;
        } else {
            activeRequest = null;
            activeCall = null;
            pendingRequest = dispatched;
            mainHandler.postDelayed(() -> {
                if (pendingRequest == dispatched && activeRequest == null) {
                    dispatchPendingRequest();
                }
            }, AVM_BIND_RETRY_MS);
        }
    }

    private void invokeAndConfirm(
            IAvmServiceInterface service, VendorCall call)
            throws Exception {
        AvmRequest request = call.request;
        boolean invoked = false;
        int stopAttempts = 0;
        boolean leftForegroundAfterStop = false;
        boolean guardedRestoredOwnership =
                !request.start
                        && hasAvmResponsibility()
                        && !avmForegroundConfirmed
                        && request.unresolvedStart == null;
        long recoveryQuietUntil = 0L;
        boolean unresolvedCommandStarted =
                request.unresolvedStart != null
                        && request.unresolvedStart.commandStarted;
        if (!request.start
                && shouldIssuePhysicalStop(
                        avmOwnershipState == AVM_OWNERSHIP_OWNED,
                        avmForegroundConfirmed,
                        unresolvedCommandStarted)) {
            service.stopAvm();
            stopAttempts = 1;
            leftForegroundAfterStop = true;
        }
        while (SystemClock.elapsedRealtime() < request.deadlineElapsedMs) {
            int status = service.getAvmStatus();
            long statusObservedAt = SystemClock.elapsedRealtime();
            VendorCall unresolvedCall = request.unresolvedStart;
            if (guardedRestoredOwnership
                    && isDesiredAvmStatus(false, status)
                    && recoveryQuietUntil == 0L) {
                recoveryQuietUntil =
                        statusObservedAt + AVM_PENDING_START_GUARD_MS;
            } else if (guardedRestoredOwnership
                    && status == AVM_STATUS_FOREGROUND
                    && recoveryQuietUntil > 0L) {
                recoveryQuietUntil = 0L;
            }
            if (!request.start
                    && status == AVM_STATUS_FOREGROUND
                    && unresolvedCall != null
                    && unresolvedCall.commandStarted) {
                avmForegroundConfirmed = true;
                unresolvedCall.startObserved = true;
            }
            boolean unresolvedStart = isUnresolvedStart(unresolvedCall);
            boolean physicalStopAllowed = shouldIssuePhysicalStop(
                    avmOwnershipState == AVM_OWNERSHIP_OWNED,
                    avmForegroundConfirmed,
                    unresolvedCall != null
                            && unresolvedCall.commandStarted);
            if (!request.start
                    && status == AVM_STATUS_FOREGROUND
                    && !physicalStopAllowed
                    && !unresolvedStart
                    && !guardedRestoredOwnership) {
                return;
            }
            if (isDesiredAvmStatus(request.start, status)
                    && (request.start || !unresolvedStart)
                    && canConfirmRecoveredStop(
                            guardedRestoredOwnership,
                            statusObservedAt,
                            recoveryQuietUntil)) {
                if (request.start
                        && desiredStart
                        && avmOwnershipState == AVM_OWNERSHIP_STARTING
                        && !persistOwnershipState(AVM_OWNERSHIP_OWNED)) {
                    throw new IllegalStateException(
                            "Unable to persist confirmed AVM ownership");
                }
                if (!request.start
                        && hasAvmResponsibility()
                        && !persistOwnershipState(AVM_OWNERSHIP_NONE)) {
                    throw new IllegalStateException(
                            "Unable to clear stopped AVM ownership");
                }
                return;
            }
            if (isFailureAvmStatus(status)) {
                throw new IllegalStateException(
                        "AVM entered failure state " + status);
            }
            if (request.start) {
                if (!invoked && isStartCommandStatus(status)) {
                    if (avmOwnershipState == AVM_OWNERSHIP_NONE
                            && !persistOwnershipState(
                                    AVM_OWNERSHIP_STARTING)) {
                        throw new IllegalStateException(
                                "Unable to persist pending AVM ownership");
                    }
                    call.commandStarted = true;
                    service.startAvm();
                    if (desiredStart
                            && avmOwnershipState != AVM_OWNERSHIP_OWNED
                            && !persistOwnershipState(
                                    AVM_OWNERSHIP_OWNED)) {
                        throw new IllegalStateException(
                                "Unable to persist AVM ownership");
                    }
                    invoked = true;
                }
            } else {
                if (status != AVM_STATUS_FOREGROUND && stopAttempts > 0) {
                    leftForegroundAfterStop = true;
                }
                if (status == AVM_STATUS_FOREGROUND
                        && physicalStopAllowed
                        && (stopAttempts == 0
                                || leftForegroundAfterStop)) {
                    service.stopAvm();
                    stopAttempts++;
                    leftForegroundAfterStop = false;
                }
            }
            long remaining = request.deadlineElapsedMs
                    - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            SystemClock.sleep(Math.min(AVM_STATUS_POLL_MS, remaining));
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("AVM Binder call interrupted");
            }
        }
        throw new java.util.concurrent.TimeoutException(
                "AVM state transition was not confirmed");
    }

    static boolean shouldIssuePhysicalStop(
            boolean owned,
            boolean foregroundConfirmed,
            boolean startCommandIssued) {
        return owned || foregroundConfirmed || startCommandIssued;
    }

    static boolean canConfirmRecoveredStop(
            boolean guardedRestoredOwnership,
            long nowElapsedMs,
            long quietUntilElapsedMs) {
        return !guardedRestoredOwnership
                || (quietUntilElapsedMs > 0L
                        && nowElapsedMs >= quietUntilElapsedMs);
    }

    static long deadlineAfterDispatch(
            long currentDeadlineElapsedMs,
            boolean restoredOwnershipRecovery,
            long nowElapsedMs) {
        return restoredOwnershipRecovery
                ? Math.max(
                        currentDeadlineElapsedMs,
                        nowElapsedMs + AVM_RECOVERY_REQUEST_TIMEOUT_MS)
                : currentDeadlineElapsedMs;
    }

    static long appRequestDeadline(long nowElapsedMs) {
        return nowElapsedMs + AVM_APP_REQUEST_TIMEOUT_MS;
    }

    static int ownershipStateAfterCommit(
            int currentState, int requestedState, boolean persisted) {
        return persisted ? requestedState : currentState;
    }

    private static boolean isUnresolvedStart(VendorCall call) {
        return call != null && isUnresolvedStartState(
                call.retired,
                call.startObserved,
                call.completed,
                call.commandStarted);
    }

    static boolean isUnresolvedStartState(
            boolean retired,
            boolean startObserved,
            boolean completed,
            boolean commandStarted) {
        return !retired
                && !startObserved
                && (!completed || commandStarted);
    }

    static boolean isDesiredAvmStatus(boolean start, int status) {
        return start
                ? status == AVM_STATUS_FOREGROUND
                : status == AVM_STATUS_GETTER_IDLE
                        || status == AVM_STATUS_IDLE
                        || status == AVM_STATUS_BACKGROUND
                        || status == AVM_STATUS_DISABLED;
    }

    static boolean isFailureAvmStatus(int status) {
        return status == AVM_STATUS_ERROR;
    }

    static boolean isStartCommandStatus(int status) {
        return status == AVM_STATUS_BACKGROUND;
    }

    private void completeVendorCall(VendorCall call, Throwable failure) {
        physicalCalls.remove(call);
        call.retired = true;
        if (!acceptsCompletion(
                call.connectionEpoch, connectionEpoch, call.invalidated)
                || activeCall != call
                || activeRequest != call.request) {
            if (activeCall == call) {
                activeCall = null;
                activeRequest = null;
            }
            settleAppliedRequest();
            if (!call.request.suppressInternalReconcile) {
                queueDesiredState();
            }
            dispatchPendingRequest();
            finishStoppedBarrier();
            return;
        }
        AvmRequest request = call.request;
        if (failure != null) {
            logger.warn("AVM call failed: " + failure.getMessage());
            if (SystemClock.elapsedRealtime() >= request.deadlineElapsedMs) {
                expireRequest(request);
                return;
            }
            activeRequest = null;
            activeCall = null;
            if (request.start == desiredStart
                    && request.generation == desiredGeneration
                    && pendingRequest == null) {
                pendingRequest = request;
            } else {
                failRequest(request);
                queueDesiredState();
            }
            unbind();
            retryPendingBind();
            return;
        }
        activeRequest = null;
        activeCall = null;
        logger.info((request.start ? "AVM foreground" : "AVM stopped")
                + " state confirmed");
        appliedStart = request.start;
        avmForegroundConfirmed =
                request.start && hasAvmResponsibility();
        if (request.start == desiredStart) {
            succeedRequest(request);
        } else {
            failRequest(request);
        }
        settleAppliedRequest();
        queueDesiredState();
        dispatchPendingRequest();
        finishStoppedBarrier();
    }

    static boolean acceptsCompletion(
            long callEpoch, long currentEpoch, boolean invalidated) {
        return !invalidated && callEpoch == currentEpoch;
    }

    private void settleAppliedRequest() {
        AvmRequest request = pendingRequest;
        if (activeRequest != null || request == null
                || !canSettleAppliedState(
                        appliedStart,
                        request.start,
                        hasUnresolvedOppositeCall(request.start))) {
            return;
        }
        pendingRequest = null;
        succeedRequest(request);
        finishStoppedBarrier();
    }

    static boolean canSettleAppliedState(
            Boolean applied, boolean requestedStart, boolean unresolvedOpposite) {
        return applied != null
                && applied == requestedStart
                && !unresolvedOpposite;
    }

    private void queueDesiredState() {
        if (activeRequest != null && activeRequest.start == desiredStart) return;
        if (pendingRequest != null && pendingRequest.start == desiredStart) return;
        if (activeRequest == null && canSettleAppliedState(
                appliedStart,
                desiredStart,
                hasUnresolvedOppositeCall(desiredStart))) {
            if (pendingRequest != null) {
                failRequest(pendingRequest);
                pendingRequest = null;
            }
            finishStoppedBarrier();
            return;
        }
        if (pendingRequest != null) failRequest(pendingRequest);
        pendingRequest = new AvmRequest(
                desiredStart,
                SystemClock.elapsedRealtime() + AVM_REQUEST_TIMEOUT_MS,
                desiredGeneration,
                null);
        if (!desiredStart) {
            pendingRequest.unresolvedStart = newestUnresolvedStartCall();
        }
        pendingRequest.suppressInternalReconcile =
                shouldSuppressInternalReconcile(
                        pendingRequest.start,
                        avmOwnershipState,
                        avmForegroundConfirmed,
                        pendingRequest.unresolvedStart != null);
        scheduleExpiry(pendingRequest);
    }

    private void finishStoppedBarrier() {
        if (desiredStart || !Boolean.FALSE.equals(appliedStart)
                || activeRequest != null || pendingRequest != null
                || hasUnresolvedPhysicalCall()) {
            return;
        }
        Runnable completion = stopCompletion;
        stopCompletion = null;
        unbind();
        runStopCompletion(completion);
    }

    private void runStopCompletion() {
        Runnable completion = stopCompletion;
        stopCompletion = null;
        runStopCompletion(completion);
    }

    private void finishTerminalStopCompletionIfCurrent(
            AvmRequest request) {
        if (request != null
                && !request.start
                && !desiredStart
                && request.generation == desiredGeneration) {
            runStopCompletion();
        }
    }

    private static void runStopCompletion(Runnable completion) {
        if (completion != null) {
            try {
                completion.run();
            } catch (Throwable t) {
                logger.warn("AVM stop completion failed: " + t.getMessage());
            }
        }
    }

    private boolean hasUnresolvedOppositeCall(boolean start) {
        for (VendorCall call : physicalCalls) {
            if (blocksAppliedState(call.request.start, start)) {
                return true;
            }
        }
        return false;
    }

    static boolean blocksAppliedState(
            boolean physicalStart, boolean requestedStart) {
        return physicalStart != requestedStart;
    }

    private boolean hasUnresolvedPhysicalCall() {
        return !physicalCalls.isEmpty();
    }

    private VendorCall newestUnresolvedStartCall() {
        VendorCall newest = null;
        for (VendorCall call : physicalCalls) {
            if (!call.request.start || !isUnresolvedStart(call)) {
                continue;
            }
            if (newest == null
                    || call.request.generation > newest.request.generation) {
                newest = call;
            }
        }
        return newest;
    }

    private void invalidatePhysicalCalls(long epoch) {
        java.util.Iterator<VendorCall> iterator = physicalCalls.iterator();
        while (iterator.hasNext()) {
            VendorCall call = iterator.next();
            if (call.connectionEpoch == epoch) {
                call.invalidated = true;
            }
        }
    }

    private static void acknowledge(
            AvmRequest request, boolean success) {
        if (request == null || request.acknowledged) return;
        request.acknowledged = true;
        if (request.completion != null) {
            try {
                request.completion.complete(success);
            } catch (Throwable failure) {
                logger.warn("AVM request completion failed: "
                        + failure.getMessage());
            }
        }
    }

    private void succeedRequest(AvmRequest request) {
        acknowledge(request, true);
    }

    private void failRequest(AvmRequest request) {
        acknowledge(request, false);
    }

    private boolean hasAvmResponsibility() {
        return avmOwnershipState != AVM_OWNERSHIP_NONE;
    }

    private synchronized boolean persistOwnershipState(int state) {
        if (state < AVM_OWNERSHIP_NONE
                || state > AVM_OWNERSHIP_OWNED
                || bootId == null) {
            logger.warn("Unable to persist AVM ownership without a valid boot ID");
            return false;
        }
        try {
            boolean persisted = context.getSharedPreferences(
                    AVM_STATE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(AVM_STATE_BOOT, bootId)
                    .putInt(AVM_STATE_VALUE, state)
                    .remove(AVM_STATE_LEGACY_OWNED)
                    .commit();
            if (!persisted) {
                logger.warn("Unable to persist AVM ownership state");
            }
            avmOwnershipState = ownershipStateAfterCommit(
                    avmOwnershipState, state, persisted);
            if (persisted) {
                avmForegroundConfirmed = false;
            }
            return persisted;
        } catch (Throwable failure) {
            logger.warn("Unable to persist AVM ownership state: "
                    + failure.getMessage());
            return false;
        }
    }

    private static int readOwnershipState(
            Context context, String currentBootId) {
        if (context == null || currentBootId == null) {
            return AVM_OWNERSHIP_NONE;
        }
        try {
            android.content.SharedPreferences state =
                    context.getSharedPreferences(
                            AVM_STATE_PREFS, Context.MODE_PRIVATE);
            if (!currentBootId.equals(
                    state.getString(AVM_STATE_BOOT, null))) {
                if (!state.edit().clear().commit()) {
                    logger.warn("Unable to clear stale AVM ownership state");
                }
                return AVM_OWNERSHIP_NONE;
            }
            int value = state.contains(AVM_STATE_VALUE)
                    ? state.getInt(
                            AVM_STATE_VALUE, AVM_OWNERSHIP_NONE)
                    : state.getBoolean(
                            AVM_STATE_LEGACY_OWNED, false)
                                    ? AVM_OWNERSHIP_OWNED
                                    : AVM_OWNERSHIP_NONE;
            if (value < AVM_OWNERSHIP_NONE
                    || value > AVM_OWNERSHIP_OWNED) {
                logger.warn("Ignoring invalid AVM ownership state " + value);
                return AVM_OWNERSHIP_NONE;
            }
            return value;
        } catch (Throwable failure) {
            logger.warn("Unable to read AVM ownership state: "
                    + failure.getMessage());
            return AVM_OWNERSHIP_NONE;
        }
    }

    private static String readBootId() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(
                        "/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            if (value != null
                    && value.matches("[0-9a-fA-F-]{16,64}")) {
                return value.toLowerCase(java.util.Locale.ROOT);
            }
        } catch (Throwable failure) {
            logger.warn("Unable to read kernel boot ID: "
                    + failure.getMessage());
        }
        return null;
    }

    public boolean isConnected() {
        return bindRequested.get() && avmService != null;
    }

    public void unbind() {
        ServiceConnection boundConnection = connection;
        long epoch = connectionEpoch;
        connection = null;
        connectionEpoch++;
        if (bindRequested.getAndSet(false)) {
            try {
                if (boundConnection != null) {
                    context.unbindService(boundConnection);
                }
            } catch (Exception ignored) {
            }
        }
        avmService = null;
        appliedStart = null;
        invalidatePhysicalCalls(epoch);
    }

    static int selectBinderLane(
            boolean start,
            boolean primaryOccupied,
            boolean primaryAbandoned,
            boolean recoveryOccupied) {
        if (!primaryOccupied && !recoveryOccupied) {
            return BINDER_LANE_PRIMARY;
        }
        if (primaryOccupied
                && primaryAbandoned
                && !recoveryOccupied
                && !start) {
            return BINDER_LANE_RECOVERY;
        }
        return BINDER_LANE_NONE;
    }

    private static final class BinderCallLane {
        private final Object lock = new Object();
        private final HandlerThread primaryThread =
                new HandlerThread("DiLink5AvmSerial");
        private final HandlerThread recoveryThread =
                new HandlerThread("DiLink5AvmRecovery");
        private final Handler primaryHandler;
        private final Handler recoveryHandler;
        private VendorCall primary;
        private VendorCall recovery;
        private boolean primaryAbandoned;

        BinderCallLane() {
            primaryThread.start();
            recoveryThread.start();
            primaryHandler = new Handler(primaryThread.getLooper());
            recoveryHandler = new Handler(recoveryThread.getLooper());
        }

        boolean post(VendorCall call, Runnable task) {
            Handler handler;
            synchronized (lock) {
                int lane = selectBinderLane(
                        call.request.start,
                        primary != null,
                        primaryAbandoned,
                        recovery != null);
                if (lane == BINDER_LANE_PRIMARY) {
                    primary = call;
                    primaryAbandoned = false;
                    handler = primaryHandler;
                } else if (lane == BINDER_LANE_RECOVERY) {
                    recovery = call;
                    handler = recoveryHandler;
                } else {
                    return false;
                }
            }
            if (handler.post(task)) return true;
            complete(call);
            return false;
        }

        void abandon(VendorCall call) {
            synchronized (lock) {
                if (primary == call) primaryAbandoned = true;
            }
        }

        void complete(VendorCall call) {
            synchronized (lock) {
                if (primary == call) {
                    primary = null;
                    primaryAbandoned = false;
                }
                if (recovery == call) recovery = null;
            }
        }
    }

    private static final class VendorCall {
        final AvmRequest request;
        final long connectionEpoch;
        volatile boolean completed;
        volatile boolean invalidated;
        volatile boolean retired;
        volatile boolean commandStarted;
        volatile boolean startObserved;

        VendorCall(AvmRequest request, long connectionEpoch) {
            this.request = request;
            this.connectionEpoch = connectionEpoch;
        }
    }

    private static final class AvmRequest {
        final boolean start;
        volatile long deadlineElapsedMs;
        final long generation;
        final RequestCompletion completion;
        VendorCall unresolvedStart;
        boolean acknowledged;
        volatile boolean suppressInternalReconcile;

        AvmRequest(
                boolean start,
                long deadlineElapsedMs,
                long generation,
                RequestCompletion completion) {
            this.start = start;
            this.deadlineElapsedMs = deadlineElapsedMs;
            this.generation = generation;
            this.completion = completion;
        }
    }
}
