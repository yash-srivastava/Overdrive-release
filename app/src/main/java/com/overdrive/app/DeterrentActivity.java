package com.overdrive.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

/**
 * Touch-capture companion to the daemon-side ScreenDeterrent.
 *
 * Why this exists: while ACC is off, BYD's vendor compositor excludes every
 * Window from HWC composition except its own AccAnimation layer at z=2^30.
 * That means an Activity launched in the app process is INVISIBLE during
 * ACC off — no matter what flags or content it sets. The daemon-side
 * SurfaceControl render at z=Integer.MAX_VALUE is what the user sees.
 *
 * What this Activity owns: input. Even though it's not composited, its
 * Window is the foreground task per WindowManager and its InputChannel sits
 * at the top of the input-dispatch stack. Tap-through-to-launcher is
 * suppressed because the dispatcher delivers events to this Activity first
 * — and we consume them all.
 *
 * Lifetime: launched by `am start` from byd_cam_daemon when motion is
 * confirmed. Finishes after the daemon closes the authenticated session
 * socket or clears screenDeterrentActiveUntilMs following visual teardown,
 * with an absolute 60-second safety bound.
 *
 * Single-instance: re-launching while already running routes through
 * onNewIntent, which re-anchors the hard ceiling and replaces the daemon
 * session token without recreating the Window.
 */
public class DeterrentActivity extends Activity {

    private static final long POLL_INTERVAL_MS = 500;
    private static final long CAPTURE_LOSS_GRACE_MS = 1_000;
    /** Hard ceiling — even with a stuck deadline we never display longer. */
    private static final long ABSOLUTE_MAX_MS = 60_000;
    private static final String EXTRA_INPUT_TOKEN = "deterrentInputToken";
    private static final int DAEMON_IPC_PORT = 19877;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** When THIS instance was created. Fixed for the instance's lifetime; used only for the
     *  startup grace window (see shouldFinishNow), which must not be re-openable. */
    private long createdAtElapsedMs = 0;
    /** Anchor for {@link #ABSOLUTE_MAX_MS}. Re-anchored on each daemon re-launch
     *  ({@link #onNewIntent}) so the ceiling means "60s per deterrent" rather than "60s per
     *  Activity instance" — a surviving instance would otherwise carry its elapsed time into
     *  the next fire and self-finish seconds in, dropping touch capture while the daemon's
     *  layer stayed up. Separate from {@link #createdAtElapsedMs} on purpose. */
    private long deterrentStartedAtElapsedMs = 0;
    private boolean finishing = false;
    private boolean teardownFinishScheduled = false;
    /** True if we're finishing after the daemon cleared its visual-session
     *  gate. False if we're finishing for any other reason (orientation
     *  change, system kill, swipe-from-recents).
     *  Drives whether onDestroy signals the daemon to tear down. */
    private boolean orderlyFinish = false;
    private volatile boolean dismissRequested = false;
    private final Object inputCaptureLock = new Object();
    private int inputCaptureGeneration = 0;
    private java.net.Socket inputCaptureSocket;
    private java.io.PrintWriter inputCaptureWriter;
    private String inputCaptureToken = "";
    private volatile boolean inputWindowFocused = false;
    private boolean di5FocusReported = false;
    private volatile boolean authenticatedInputCaptureSeen = false;
    private final java.util.concurrent.ExecutorService gateWriter =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DeterrentGateWriter");
                t.setDaemon(true);
                return t;
            });

    private final Runnable deadlinePoll = new Runnable() {
        @Override public void run() {
            if (finishing) return;
            if (SystemClock.elapsedRealtime() - deterrentStartedAtElapsedMs
                    > ABSOLUTE_MAX_MS) {
                finishAfterTeardownGrace();
                return;
            }
            if (shouldFinishNow()) {
                finishCleanly();
                return;
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                && com.overdrive.app.monitor.AccMonitor.isVehicleActive()) {
            finish();
            return;
        }
        createdAtElapsedMs = SystemClock.elapsedRealtime();
        deterrentStartedAtElapsedMs = createdAtElapsedMs;
        inputCaptureToken = inputToken(getIntent());

        // Best-effort flags. Most of these are noise during ACC-off because
        // BYD's compositor ignores us anyway, but they cost nothing and help
        // the rare case where ACC flips on mid-deterrent and our Window
        // briefly becomes visible before exitSentryMode finishes us.
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        applyImmersive();

        // Transparent root that blocks all touches. Visual content (image /
        // GIF / default red) is rendered by the daemon's SurfaceControl
        // layer at z=Integer.MAX_VALUE, ABOVE this Window in the
        // SurfaceFlinger stack. We're invisible by design.
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0x00000000);
        root.setOnTouchListener((v, event) -> true);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        mainHandler.postDelayed(deadlinePoll, POLL_INTERVAL_MS);
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            // DI5 Standby keeps the panel dark and the focus on BYD's power window; ask it to light.
            // A car BYD reports as on or out of Standby means the owner is here: never show then.
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String awake = com.overdrive.app.byd.dilink5.Dilink5PanelPower.carAwakeReason(app);
                if (awake != null) {
                    com.overdrive.app.byd.dilink5.Dilink5PanelPower.report(
                            "deterrent-skipped", "car awake: " + awake);
                    writeGate(java.util.Collections.singletonMap(
                            "screenDeterrentCarAwakeElapsedMs", SystemClock.elapsedRealtime()));
                    mainHandler.post(this::finishCleanly);
                    return;
                }
                com.overdrive.app.byd.dilink5.Dilink5PanelPower.report("deterrent-panel",
                        com.overdrive.app.byd.dilink5.Dilink5PanelPower.lightPanel(app));
            }, "Di5PanelOn").start();
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // Re-launched by the daemon during sustained motion. The deadline poll already keeps us
        // up, so there is nothing to restart — but re-anchor the ABSOLUTE_MAX_MS bound so it
        // means "60s per deterrent", not "60s per Activity instance". Without this a surviving
        // instance carried its elapsed time into the next fire and self-finished seconds in,
        // dropping touch capture while the daemon's layer stayed up (screen covered, taps
        // passing through). This does not weaken the ceiling: it guards a stuck deadline with a
        // dead/wedged daemon, and a dead daemon issues no re-launch, so nothing re-anchors.
        // createdAtElapsedMs is deliberately NOT touched — the startup grace window must stay closed
        // (publishGate has already written a real deadline by now). Main thread, same as the
        // poll, so no race.
        deterrentStartedAtElapsedMs = SystemClock.elapsedRealtime();
        if (teardownFinishScheduled) {
            teardownFinishScheduled = false;
            mainHandler.removeCallbacksAndMessages(null);
            mainHandler.postDelayed(deadlinePoll, POLL_INTERVAL_MS);
        }
        dismissRequested = false;
        authenticatedInputCaptureSeen = false;
        setIntent(intent);
        inputCaptureToken = inputToken(intent);
        if (inputWindowFocused) restartInputCapture();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        inputWindowFocused = hasFocus;
        if (hasFocus && !di5FocusReported
                && com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            di5FocusReported = true;
            com.overdrive.app.byd.dilink5.Dilink5PanelPower.report("deterrent-focus", "gained");
        }
        if (hasFocus) {
            applyImmersive();
            restartInputCapture();
        } else {
            int generation = closeInputCapture();
            if (authenticatedInputCaptureSeen) {
                scheduleFinishAfterCaptureLoss(generation);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
    }

    @Override public void onBackPressed() { /* swallow */ }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return true; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return true; }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return true; }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && !dismissRequested) {
            dismissRequested = true;
            // Keep the socket alive until daemon cleanup closes it AFTER the
            // visual layer is released. The UCM write remains a compatibility
            // fallback if the authenticated connection is still starting.
            sendInputDismiss();
            writeGate(java.util.Collections.singletonMap(
                    "screenDeterrentUserDismissed", true));
        }
        // Keep consuming input until the daemon removes its visual layer and
        // clears the shared deadline. Finishing here would create a short
        // touch-through window while the z=MAX surface was still visible.
        return true;
    }

    private boolean shouldFinishNow() {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                && com.overdrive.app.monitor.AccMonitor.isVehicleActive()) {
            return true;
        }
        // Once the daemon authenticated this session, its socket closure is
        // the only trustworthy visual-teardown acknowledgement. Other
        // processes clear the persisted deadline during ACC transitions, so
        // treating that zero as teardown could release input while the z=MAX
        // layer is still being hidden.
        if (authenticatedInputCaptureSeen) return false;
        long nowElapsed = SystemClock.elapsedRealtime();
        try {
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            if (s == null) return false;
            long deadline = s.optLong("screenDeterrentActiveUntilMs", 0L);
            // Grace period: if the gate hasn't been written yet (first 1s
            // after launch the daemon may still be on its publishGate path)
            // hold off the zero-gate check. Without this, a slow
            // daemon-side fire() would let us self-destruct at +500ms.
            if (deadline == 0 && (nowElapsed - createdAtElapsedMs) < 1500) return false;
            // The daemon clears the deadline only AFTER releasing its z=MAX
            // visual layer. Waiting for that explicit acknowledgement keeps
            // this Activity's InputChannel alive through the entire teardown.
            return deadline == 0;
        } catch (Throwable t) {
            // Fail closed: retaining touch capture is safer than exposing the
            // controls beneath a still-visible deterrent layer. The absolute
            // 60-second ceiling remains the final escape hatch.
            return false;
        }
    }

    private static String inputToken(android.content.Intent intent) {
        return intent == null ? "" : intent.getStringExtra(EXTRA_INPUT_TOKEN);
    }

    private void restartInputCapture() {
        closeInputCapture();
        final String token = inputCaptureToken;
        if (finishing || token == null || token.isEmpty()) return;

        final int generation;
        synchronized (inputCaptureLock) {
            generation = ++inputCaptureGeneration;
        }
        Thread connector = new Thread(() -> {
            java.net.Socket socket = new java.net.Socket();
            try {
                socket.connect(new java.net.InetSocketAddress(
                        "127.0.0.1", DAEMON_IPC_PORT), 1_000);
                socket.setSoTimeout(5_000);
                synchronized (inputCaptureLock) {
                    if (generation != inputCaptureGeneration
                            || finishing || !inputWindowFocused) {
                        return;
                    }
                    inputCaptureSocket = socket;
                }

                java.io.PrintWriter writer = new java.io.PrintWriter(
                        new java.io.OutputStreamWriter(
                                socket.getOutputStream(),
                                java.nio.charset.StandardCharsets.UTF_8),
                        true);
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                socket.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8));
                JSONObject request = new JSONObject();
                request.put("command", "DETERRENT_INPUT_CAPTURE");
                request.put("token", token);
                writer.println(request.toString());
                if (writer.checkError()) return;
                String ack = reader.readLine();
                if (ack == null || !new JSONObject(ack)
                        .optBoolean("success", false)) return;

                boolean dismissAlreadyRequested;
                synchronized (inputCaptureLock) {
                    if (generation != inputCaptureGeneration
                            || inputCaptureSocket != socket
                            || finishing || !inputWindowFocused) {
                        return;
                    }
                    inputCaptureWriter = writer;
                    authenticatedInputCaptureSeen = true;
                    dismissAlreadyRequested = dismissRequested;
                }
                if (dismissAlreadyRequested) writer.println("DISMISS");

                // The daemon owns readiness while this authenticated socket is
                // alive. Focus loss, Activity teardown, or process death closes
                // it and immediately invalidates the daemon-side session.
                socket.setSoTimeout(0);
                while (reader.readLine() != null) {
                    // No follow-up messages are expected.
                }
            } catch (Throwable ignored) {
            } finally {
                boolean connectionStillCurrent;
                synchronized (inputCaptureLock) {
                    if (inputCaptureSocket == socket) {
                        inputCaptureSocket = null;
                        inputCaptureWriter = null;
                    }
                    connectionStillCurrent =
                            generation == inputCaptureGeneration
                                    && !finishing
                                    && inputWindowFocused;
                }
                try { socket.close(); } catch (Throwable ignored) {}
                if (connectionStillCurrent) {
                    scheduleFinishAfterCaptureLoss(generation);
                }
            }
        }, "DeterrentInputCapture");
        connector.setDaemon(true);
        connector.start();
    }

    private void scheduleFinishAfterCaptureLoss(int generation) {
        mainHandler.postDelayed(() -> {
            boolean stillLost;
            synchronized (inputCaptureLock) {
                stillLost = generation == inputCaptureGeneration
                        && inputCaptureSocket == null
                        && !finishing;
            }
            // If the daemon died, its SurfaceControl layer died with it. If
            // only this IPC handler failed, 1s still covers the daemon's
            // <=200ms stop poll and visual teardown before input is released.
            if (stillLost) finishCleanly();
        }, CAPTURE_LOSS_GRACE_MS);
    }

    private void sendInputDismiss() {
        synchronized (inputCaptureLock) {
            if (inputCaptureWriter != null) {
                inputCaptureWriter.println("DISMISS");
            }
        }
    }

    private void finishAfterTeardownGrace() {
        if (finishing || teardownFinishScheduled) return;
        teardownFinishScheduled = true;
        mainHandler.removeCallbacks(deadlinePoll);
        closeInputCapture();
        mainHandler.postDelayed(this::finishCleanly, CAPTURE_LOSS_GRACE_MS);
    }

    private int closeInputCapture() {
        java.net.Socket socket;
        int generation;
        synchronized (inputCaptureLock) {
            generation = ++inputCaptureGeneration;
            socket = inputCaptureSocket;
            inputCaptureSocket = null;
            inputCaptureWriter = null;
        }
        if (socket != null) {
            try { socket.close(); } catch (Throwable ignored) {}
        }
        return generation;
    }

    private void writeGate(java.util.Map<String, ?> values) {
        java.util.Map<String, Object> copy = new java.util.HashMap<>();
        copy.putAll(values);
        try {
            gateWriter.execute(() -> {
                try {
                    UnifiedConfigManager.updateValues("surveillance", copy);
                } catch (Throwable ignored) {}
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void finishCleanly() {
        if (finishing) return;
        finishing = true;
        inputWindowFocused = false;
        orderlyFinish = true;
        mainHandler.removeCallbacksAndMessages(null);
        try { finish(); } catch (Throwable ignored) {}
        overridePendingTransition(0, 0);
    }

    /**
     * If we're being destroyed for any reason OTHER than an orderly finish
     * (orientation change recreated us, system killed our task, user swiped
     * from recents) the daemon-side render is still running. Without a
     * signal it would keep the surface up and the panel awake until its
     * deadline elapses. Closing the tokened input socket is the load-bearing
     * stop signal; the dismissal write is a best-effort compatibility fallback.
     */
    @Override
    protected void onDestroy() {
        finishing = true;
        mainHandler.removeCallbacksAndMessages(null);
        closeInputCapture();
        java.util.Map<String, Object> stop = new java.util.HashMap<>();
        if (!orderlyFinish) {
            // Best-effort signal to the daemon that the activity died
            // unexpectedly (low-mem kill, swipe from recents, etc.) so it
            // tears down its SurfaceControl + backlight rather than holding
            // them for the full deadline. Run on the serialized gate writer
            // because UCM.updateValues does file I/O — per the
            // user-memory rule we never write UCM on the UI thread, even
            // during onDestroy (the looper may be killed mid-write but the
            // process itself is dying anyway, no functional difference).
            stop.put("screenDeterrentUserDismissed", true);
        }
        if (!stop.isEmpty()) writeGate(stop);
        gateWriter.shutdown();
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            final android.content.Context app = getApplicationContext();
            new Thread(() -> com.overdrive.app.byd.dilink5.Dilink5PanelPower.report(
                    "deterrent-panel-restore",
                    com.overdrive.app.byd.dilink5.Dilink5PanelPower.restorePanel(app)),
                    "Di5PanelRestore").start();
        }
        super.onDestroy();
    }
}
