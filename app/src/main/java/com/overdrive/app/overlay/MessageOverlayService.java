package com.overdrive.app.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.overdrive.app.logging.DaemonLogger;

import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * App-process on-screen message overlay for the "Show Toast" / "Show Dialog"
 * automation + key-mapping actions.
 *
 * <p><b>Why an app-process overlay service.</b> The daemon (UID 2000, {@code app_process})
 * has no usable UI surface — it can't post a Toast or inflate a dialog. This service runs
 * in the REAL app process where a {@link WindowManager} {@code TYPE_APPLICATION_OVERLAY}
 * window works, the same proven mechanism {@link StatusOverlayService} and the RoadSense
 * overlay use. The daemon launches it via {@code am start-foreground-service} (see
 * {@code MessageOverlayController}), exactly like the Play Audio / Speak bridge.
 *
 * <p><b>No focus steal.</b> Both surfaces use {@code FLAG_NOT_FOCUSABLE}, so they float
 * over whatever app is foreground (maps, music) WITHOUT interrupting it or its audio —
 * the deliberate choice for a moving car. A toast is fully non-touchable and auto-dismisses;
 * a dialog is touchable ONLY on its card (OK button + scrim tap) but still doesn't take
 * input focus from the app beneath.
 *
 * <p>Intent extras: {@code kind}=toast|dialog, {@code message}, {@code title} (dialog),
 * {@code button} (dialog OK label), {@code duration}=short|long (toast),
 * {@code position}=top|center|bottom (toast), {@code severity}=info|warning|alert,
 * {@code timeoutSec} (dialog auto-dismiss, 0 = none).
 */
public final class MessageOverlayService extends Service {

    private static final String TAG = "MessageOverlay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CHANNEL_ID = "message_overlay";
    private static final int NOTIFICATION_ID = 9101;

    /** Broadcast (same family as the media stop) that dismisses any showing message. */
    public static final String ACTION_DISMISS = "com.overdrive.app.action.DISMISS_MESSAGE";

    private static final long TOAST_SHORT_MS = 2500;
    private static final long TOAST_LONG_MS = 5000;

    private WindowManager windowManager;
    private View overlayView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoDismiss;
    private boolean dismissReceiverRegistered;

    /** Tears down any showing message on the ACTION_DISMISS broadcast — the broadcast
     *  half of {@link com.overdrive.app.byd.MessageOverlayController#dismiss()} (the
     *  am stopservice half also works; this makes an explicit dismiss instant and lets a
     *  dismiss reach the service even if a stopservice race leaves it briefly alive). */
    private final android.content.BroadcastReceiver dismissReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dismissAndStop(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startMessageForeground();
        try {
            registerReceiver(dismissReceiver, new android.content.IntentFilter(ACTION_DISMISS));
            dismissReceiverRegistered = true;
        } catch (Throwable t) {
            logger.warn("Dismiss receiver register failed: " + t.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String kind = orDefault(intent.getStringExtra("kind"), "toast");
        boolean dialog = "dialog".equalsIgnoreCase(kind);
        // A running-app can't render an overlay without the permission; a shell `am`
        // launch bypasses the in-app grant flow, so guard here and cleanly no-op.
        if (!OverlayPermissionChecker.isGranted(this)) {
            logger.warn("SYSTEM_ALERT_WINDOW not granted; cannot show message overlay");
            // A toast still has a way to the screen: the platform toast queue needs no
            // overlay permission. Use our own view and gravity so the requested severity
            // accent and top/center/bottom position survive that fallback. A dialog is a
            // deliberate hold-until-OK prompt; a 2-5 s toast is not the same thing, so
            // dialogs stay strict.
            if (!dialog && showStyledToastFallback(intent)) {
                sendAcknowledgement(intent, true,
                        FALLBACK_PREFIX + PERMISSION_REASON);
            } else {
                sendAcknowledgement(intent, false, PERMISSION_REASON);
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            boolean displayed;
            String reason = "";
            if (dialog) {
                displayed = showDialog(intent);
            } else {
                displayed = showToast(intent);
                // The pre-check passed but WindowManager still refused the overlay window.
                // Preserve presentation through the toast queue and keep the reason visible
                // to the daemon.
                if (!displayed && hasMessage(intent) && showStyledToastFallback(intent)) {
                    displayed = true;
                    reason = FALLBACK_PREFIX + "the car refused the overlay window";
                }
            }
            sendAcknowledgement(intent, displayed,
                    displayed ? reason : "The car could not add the message overlay");
        } catch (Throwable t) {
            logger.warn("Show failed: " + t.getMessage());
            sendAcknowledgement(intent, false,
                    t.getMessage() == null ? "Message display failed" : t.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Same wording as RemoteCommunicationAvailability.overlayPermission() so every surface
     *  (daemon log, remote-message API, settings guidance) names the gap identically. */
    private static final String PERMISSION_REASON =
            "Display-over-other-apps permission is not granted in the car";
    private static final String FALLBACK_PREFIX =
            "Shown through the Android toast queue: ";

    private static boolean hasMessage(Intent intent) {
        return !orDefault(intent.getStringExtra("message"), "").isEmpty();
    }

    /**
     * Last-resort rendering for {@code kind=toast}. A custom {@link android.widget.Toast}
     * view needs no SYSTEM_ALERT_WINDOW grant and, because this app targets API 25, remains
     * app-rendered on newer Android releases. Reusing the overlay view and requested gravity
     * keeps severity color and position intact instead of falling back to the native text
     * toast's theme and fixed bottom placement. Runs on the main thread (onStartCommand).
     *
     * @return true when the styled toast was handed to the platform toast queue
     */
    @SuppressWarnings("deprecation")
    private boolean showStyledToastFallback(Intent intent) {
        String message = orDefault(intent.getStringExtra("message"), "");
        if (message.isEmpty()) return false;
        String position = orDefault(intent.getStringExtra("position"), "bottom");
        boolean longToast = "long".equalsIgnoreCase(
                orDefault(intent.getStringExtra("duration"), "short"));
        try {
            Context ctx = themedContext();
            android.widget.Toast toast =
                    new android.widget.Toast(getApplicationContext());
            toast.setView(buildToastView(
                    ctx, message, accentFor(intent.getStringExtra("severity"))));
            toast.setDuration(longToast
                    ? android.widget.Toast.LENGTH_LONG
                    : android.widget.Toast.LENGTH_SHORT);
            toast.setGravity(
                    gravityFor(position),
                    0,
                    "center".equalsIgnoreCase(position.trim()) ? 0 : dp(ctx, 40));
            toast.show();
            logger.warn("Overlay unavailable; message shown through styled toast fallback"
                    + " at " + position.trim().toLowerCase());
            return true;
        } catch (Throwable t) {
            logger.warn("Styled toast fallback failed: " + t.getMessage());
            return false;
        }
    }

    // ── Toast ────────────────────────────────────────────────────────────────

    private boolean showToast(Intent intent) {
        String message = orDefault(intent.getStringExtra("message"), "");
        if (message.isEmpty()) { stopSelf(); return false; }
        String position = orDefault(intent.getStringExtra("position"), "bottom");
        long dur = "long".equalsIgnoreCase(orDefault(intent.getStringExtra("duration"), "short"))
                ? TOAST_LONG_MS : TOAST_SHORT_MS;
        int accent = accentFor(intent.getStringExtra("severity"));

        Context ctx = themedContext();
        TextView tv = buildToastView(ctx, message, accent);

        if (!replaceOverlay(tv, toastLayoutParams(gravityFor(position)))) {
            return false;
        }
        scheduleDismiss(dur);
        return true;
    }

    /** One presentation for both overlay-window and platform-toast delivery paths. */
    private static TextView buildToastView(Context ctx, String message, int accent) {
        int pad = dp(ctx, 16);
        TextView tv = new TextView(ctx);
        tv.setText(message);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setMaxLines(4);
        tv.setPadding(pad, dp(ctx, 12), pad, dp(ctx, 12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202124);                 // near-opaque charcoal
        bg.setCornerRadius(dp(ctx, 8));
        bg.setStroke(dp(ctx, 2), accent);        // severity edge
        tv.setBackground(bg);
        return tv;
    }

    // ── Dialog ───────────────────────────────────────────────────────────────

    private boolean showDialog(Intent intent) {
        String title = orDefault(intent.getStringExtra("title"), "");
        String message = orDefault(intent.getStringExtra("message"), "");
        String buttonLabel = orDefault(intent.getStringExtra("button"), "OK");
        int accent = accentFor(intent.getStringExtra("severity"));
        int timeoutSec = intent.getIntExtra("timeoutSec", 0);
        if (title.isEmpty() && message.isEmpty()) { stopSelf(); return false; }

        Context ctx = themedContext();
        int pad = dp(ctx, 20);

        // Dimmed full-screen scrim; tap outside the card dismisses.
        FrameLayout scrim = new FrameLayout(ctx);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> dismissAndStop());

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, dp(ctx, 12));
        // Swallow taps on the card so they don't fall through to the scrim.
        card.setOnClickListener(v -> {});
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF202124);
        cardBg.setCornerRadius(dp(ctx, 18));
        cardBg.setStroke(dp(ctx, 3), accent);
        card.setBackground(cardBg);

        if (!title.isEmpty()) {
            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(title);
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(tvTitle);
        }
        if (!message.isEmpty()) {
            TextView tvBody = new TextView(ctx);
            tvBody.setText(message);
            tvBody.setTextColor(0xFFE3E3E3);
            tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(ctx, 12);
            card.addView(tvBody, blp);
        }

        Button ok = new Button(ctx);
        ok.setText(buttonLabel);
        ok.setAllCaps(false);
        ok.setTextColor(Color.WHITE);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(accent);
        okBg.setCornerRadius(dp(ctx, 12));
        ok.setBackground(okBg);
        ok.setOnClickListener(v -> dismissAndStop());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(ctx, 20);
        olp.gravity = Gravity.END;
        card.addView(ok, olp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(ctx, 460), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        scrim.addView(card, clp);

        if (!replaceOverlay(scrim, dialogLayoutParams())) return false;
        // A dialog holds until OK/scrim tap, unless the user set an auto-dismiss.
        cancelDismiss();
        if (timeoutSec > 0) scheduleDismiss(timeoutSec * 1000L);
        return true;
    }

    // ── Overlay plumbing ───────────────────────────────────────────────────────

    private boolean replaceOverlay(View view, WindowManager.LayoutParams lp) {
        removeOverlay();
        overlayView = view;
        try {
            windowManager.addView(overlayView, lp);
            return true;
        } catch (Throwable t) {
            logger.error("addView failed: " + t.getMessage());
            overlayView = null;
            stopSelf();
            return false;
        }
    }

    /**
     * Report the real addView outcome to the daemon over a one-use loopback
     * callback. Legacy automation calls omit these extras and remain
     * fire-and-forget.
     */
    private void sendAcknowledgement(Intent intent, boolean displayed, String reason) {
        String portValue = intent.getStringExtra("ackPort");
        String token = intent.getStringExtra("ackToken");
        if (portValue == null || token == null || token.isEmpty()) return;
        final int port;
        try {
            port = Integer.parseInt(portValue);
        } catch (NumberFormatException invalid) {
            return;
        }
        if (port <= 0 || port > 65535) return;

        Thread callback = new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                DataOutputStream output =
                        new DataOutputStream(socket.getOutputStream());
                output.writeUTF(token);
                output.writeUTF(displayed ? "DISPLAYED" : "FAILED");
                output.writeUTF(reason == null ? "" : reason);
                output.flush();
            } catch (Throwable error) {
                logger.warn(
                        "Acknowledgement callback failed: "
                                + error.getMessage());
            }
        }, "MessageOverlayAck");
        callback.setDaemon(true);
        callback.start();
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
            overlayView = null;
        }
    }

    /** Toast window: non-focusable AND non-touchable so it never intercepts input. */
    private WindowManager.LayoutParams toastLayoutParams(int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        int m = dp(this, 40);
        lp.y = m;
        return lp;
    }

    /** Dialog window: non-focusable (no focus steal) but touchable (OK / scrim tap). */
    private WindowManager.LayoutParams dialogLayoutParams() {
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
    }

    private static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    private void scheduleDismiss(long ms) {
        cancelDismiss();
        autoDismiss = this::dismissAndStop;
        handler.postDelayed(autoDismiss, ms);
    }

    private void cancelDismiss() {
        if (autoDismiss != null) { handler.removeCallbacks(autoDismiss); autoDismiss = null; }
    }

    private void dismissAndStop() {
        cancelDismiss();
        removeOverlay();
        stopSelf();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static int gravityFor(String position) {
        if (position == null) return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        switch (position.trim().toLowerCase()) {
            case "top":    return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case "center": return Gravity.CENTER;
            case "bottom":
            default:       return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        }
    }

    /** Severity → accent color, using the app's status tokens. Default info. */
    private int accentFor(String severity) {
        String s = severity == null ? "info" : severity.trim().toLowerCase();
        switch (s) {
            case "warning": return 0xFFA6601C; // status_warning
            case "alert":
            case "danger":  return 0xFFBA1A1A; // status_danger
            case "info":
            default:        return 0xFF0B57D0; // status_info
        }
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v;
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * A context whose configuration honors the app's day/night choice (a bare Service
     * reads the system uiMode). Mirrors StatusOverlayService.themedContext().
     */
    private Context themedContext() {
        try {
            int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            int uiNight;
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                uiNight = android.content.res.Configuration.UI_MODE_NIGHT_YES;
            } else if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
                uiNight = android.content.res.Configuration.UI_MODE_NIGHT_NO;
            } else {
                return this;
            }
            android.content.res.Configuration cfg = new android.content.res.Configuration(
                    getResources().getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | uiNight;
            return createConfigurationContext(cfg);
        } catch (Throwable t) {
            return this;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.notif_channel_onscreen_messages), NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void startMessageForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable e) {
            logger.warn(
                    "startForeground failed, falling back: "
                            + e.getMessage());
            try { startForeground(NOTIFICATION_ID, n); } catch (Throwable ignored) {}
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.notif_onscreen_message_title))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    @Override public void onDestroy() {
        cancelDismiss();
        removeOverlay();
        if (dismissReceiverRegistered) {
            try { unregisterReceiver(dismissReceiver); } catch (Throwable ignored) {}
            dismissReceiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
