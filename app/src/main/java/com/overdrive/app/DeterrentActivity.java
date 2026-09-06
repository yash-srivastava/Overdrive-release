package com.overdrive.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.surveillance.ScreenDeterrentAsset;
import com.overdrive.app.surveillance.ScreenDeterrentVideo;

import org.json.JSONObject;

import java.io.FileInputStream;

/**
 * Touch-capture companion to the daemon-side ScreenDeterrent.
 *
 * Why this exists: while ACC is off, some BYD vendor compositors exclude
 * every Window from HWC except AccAnimation at z=2^30. On those units the
 * daemon SurfaceControl layer at z=Integer.MAX_VALUE is the picture. On
 * DiLink 5 this Activity IS composited, so it also paints a still / GIF /
 * default fallback in case that layer never appears. Video stays on the
 * daemon layer; this Activity does not start a second MP4 decoder.
 *
 * What this Activity always owns: input. Its Window is the foreground task
 * per WindowManager and its InputChannel sits at the top of the
 * input-dispatch stack. Tap-through-to-launcher is suppressed because the
 * dispatcher delivers events here first — and we consume them all.
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
    private volatile boolean authenticatedInputCaptureSeen = false;
    private Bitmap fallbackStill;
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
        try {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        } catch (Throwable ignored) {}
        super.onCreate(savedInstanceState);
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()
                && !previewSessionActive()) {
            finish();
            return;
        }
        createdAtElapsedMs = SystemClock.elapsedRealtime();
        deterrentStartedAtElapsedMs = createdAtElapsedMs;
        inputCaptureToken = inputToken(getIntent());

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        applyImmersive();

        // Opaque fallback visual. The daemon still owns the z=MAX layer;
        // if SurfaceFlinger never shows that layer (DiLink 5 stills/GIF),
        // this Window is what the user sees. Touches stay consumed here.
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.BLACK);
        root.setOnTouchListener((v, event) -> true);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        attachFallbackVisual(root);
        setContentView(root);

        mainHandler.postDelayed(deadlinePoll, POLL_INTERVAL_MS);
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
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()
                && !previewSessionActive()) return true;
        try {
            com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
            if (collector != null) {
                com.overdrive.app.byd.BydVehicleData vd = collector.getData();
                if (vd != null) {
                    if (vd.speedKmh > 0 && vd.speedKmh != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) return true;
                    if (vd.gearMode > com.overdrive.app.monitor.GearMonitor.GEAR_P && vd.gearMode <= com.overdrive.app.monitor.GearMonitor.GEAR_S) return true;
                }
            }
        } catch (Throwable ignored) {}
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

    private static boolean previewSessionActive() {
        try {
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            return s != null && s.optBoolean("screenDeterrentPreviewActive", false);
        } catch (Throwable ignored) {
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
        if (fallbackStill != null) {
            try { fallbackStill.recycle(); } catch (Throwable ignored) {}
            fallbackStill = null;
        }
        super.onDestroy();
    }

    private void attachFallbackVisual(FrameLayout root) {
        String path = fallbackAssetPath();
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int dispW = Math.max(1, metrics.widthPixels);
        int dispH = Math.max(1, metrics.heightPixels);

        if (path != null && isGifAsset(path)) {
            Movie movie = decodeGif(path);
            if (movie != null && movie.duration() > 0) {
                root.addView(new GifFallbackView(this, movie, dispW, dispH),
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                return;
            }
        }

        if (path != null) {
            fallbackStill = decodeStill(path, dispW, dispH);
            if (fallbackStill != null) {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackgroundColor(Color.BLACK);
                image.setImageBitmap(fallbackStill);
                root.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                return;
            }
        }

        root.setBackgroundColor(0xFFB00020);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.addView(fallbackText("OVERDRIVE", 22f, true, 0.20f));
        column.addView(fallbackText("YOU ARE ON CAMERA", 42f, true, 0f));
        column.addView(fallbackText("Surveillance recording in progress", 18f, false, 0f));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
    }

    private TextView fallbackText(String text, float sp, boolean bold, float tracking) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        if (tracking != 0f) view.setLetterSpacing(tracking);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        view.setLayoutParams(lp);
        return view;
    }

    private static String fallbackAssetPath() {
        try {
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            if (s == null) return null;
            String path = s.optString("screenDeterrentImagePath", "");
            if (!ScreenDeterrentAsset.isAllowedPath(path)) return null;
            // Video stays on the daemon BsNativeLayer. Do not start a second
            // decoder in the app process.
            if (ScreenDeterrentVideo.isMp4File(path)) return null;
            return path;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isGifAsset(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] hdr = new byte[6];
            int n = fis.read(hdr);
            return n >= 6 && hdr[0] == 'G' && hdr[1] == 'I' && hdr[2] == 'F'
                    && hdr[3] == '8' && (hdr[4] == '7' || hdr[4] == '9') && hdr[5] == 'a';
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Movie decodeGif(String path) {
        try {
            return Movie.decodeFile(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap decodeStill(String path, int dispW, int dispH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while ((bounds.outWidth / sample) > dispW * 2
                    || (bounds.outHeight / sample) > dispH * 2) {
                sample *= 2;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, decode);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class GifFallbackView extends View {
        private final Movie movie;
        private final float scale;
        private final int dx;
        private final int dy;
        private long startedAtMs;

        GifFallbackView(Activity activity, Movie movie, int dispW, int dispH) {
            super(activity);
            this.movie = movie;
            int movieW = Math.max(1, movie.width());
            int movieH = Math.max(1, movie.height());
            this.scale = Math.min((float) dispW / movieW, (float) dispH / movieH);
            int dw = (int) (movieW * scale);
            int dh = (int) (movieH * scale);
            this.dx = (dispW - dw) / 2;
            this.dy = (dispH - dh) / 2;
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            startedAtMs = -1;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (startedAtMs < 0 || !isAttachedToWindow()) return;
            if (startedAtMs == 0) startedAtMs = SystemClock.uptimeMillis();
            int duration = Math.max(1, movie.duration());
            int progress = (int) ((SystemClock.uptimeMillis() - startedAtMs) % duration);
            movie.setTime(progress);
            canvas.drawColor(Color.BLACK);
            canvas.save();
            canvas.translate(dx, dy);
            canvas.scale(scale, scale);
            movie.draw(canvas, 0, 0);
            canvas.restore();
            postInvalidateDelayed(40);
        }
    }
}
