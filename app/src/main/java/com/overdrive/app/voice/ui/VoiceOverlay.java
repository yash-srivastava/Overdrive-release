package com.overdrive.app.voice.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.overdrive.app.R;
import com.overdrive.app.overlay.OverlayPermissionChecker;

/** Bottom-centre listening pill: animated orb plus one line of status text. */
public final class VoiceOverlay {

    private static final String TAG = "VoiceOverlay";

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideNow;
    private Context themed;
    private WindowManager wm;
    private LinearLayout root;
    private VoiceOrbView orb;
    private TextView title;
    private boolean attached;
    private volatile Runnable onTap;

    /** The reverse camera (or a gear not yet known) owns the screen: every pill steps aside meanwhile. */
    private static volatile boolean suppressed;
    private static volatile java.lang.ref.WeakReference<VoiceOverlay> current;

    /** Main thread or any; applies to the pill on screen and any shown later. */
    public static void setSuppressed(boolean on) {
        if (suppressed == on) return;
        suppressed = on;
        java.lang.ref.WeakReference<VoiceOverlay> ref = current;
        VoiceOverlay o = ref == null ? null : ref.get();
        if (o != null) o.main.post(o::applySuppressed);
    }

    private void applySuppressed() {
        if (root != null) root.setVisibility(suppressed ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    public VoiceOverlay(Context context) {
        app = context.getApplicationContext();
    }

    /** Localised string resolved against the app's selected language. */
    public String text(int resId) {
        return themedContext().getString(resId);
    }

    public String text(int resId, Object... args) {
        return themedContext().getString(resId, args);
    }

    public void setOnTap(Runnable tap) {
        onTap = tap;
    }

    public void show(int orbState, CharSequence text) {
        main.post(() -> {
            main.removeCallbacks(hideRunnable);
            if (!ensureAttached()) return;
            orb.setState(orbState);
            title.setText(text);
            applySuppressed();
        });
    }

    /** Shows a terminal state and hides after {@code holdMs}. */
    public void finish(int orbState, CharSequence text, long holdMs) {
        main.post(() -> {
            main.removeCallbacks(hideRunnable);
            if (!ensureAttached()) return;
            orb.setState(orbState);
            title.setText(text);
            applySuppressed();
            main.postDelayed(hideRunnable, holdMs);
        });
    }

    public void setLevel(float level) {
        VoiceOrbView o = orb;
        if (o != null) o.setLevel(level);
    }

    public void hide() {
        main.post(hideRunnable);
    }

    public void release() {
        main.post(() -> {
            hideNow();
            root = null;
            orb = null;
            title = null;
            themed = null;
        });
    }

    private boolean ensureAttached() {
        if (attached) return true;
        if (!OverlayPermissionChecker.isGranted(app)) return false;
        try {
            if (root == null) build();
            if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(28);
            wm.addView(root, lp);
            attached = true;
            current = new java.lang.ref.WeakReference<>(this);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attach failed: " + t.getMessage());
            return false;
        }
    }

    private void hideNow() {
        main.removeCallbacks(hideRunnable);
        if (attached && wm != null && root != null) {
            try {
                wm.removeView(root);
            } catch (Throwable t) {
                Log.w(TAG, "detach failed: " + t.getMessage());
            }
        }
        attached = false;
    }

    private void build() {
        Context ctx = themedContext();
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundResource(R.drawable.overlay_pill_background);
        root.setPadding(dp(8), dp(6), dp(18), dp(6));
        root.setElevation(dp(8));

        orb = new VoiceOrbView(ctx);
        root.addView(orb, new LinearLayout.LayoutParams(dp(48), dp(48)));

        title = new TextView(ctx);
        title.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxWidth(dp(360));
        title.setSingleLine(true);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = dp(10);
        root.addView(title, textLp);

        root.setOnClickListener(v -> {
            Runnable tap = onTap;
            if (tap != null) tap.run();
        });
    }

    private Context themedContext() {
        if (themed != null) return themed;
        Configuration cfg = new Configuration(app.getResources().getConfiguration());
        boolean overridden = false;
        int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | Configuration.UI_MODE_NIGHT_YES;
            overridden = true;
        } else if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | Configuration.UI_MODE_NIGHT_NO;
            overridden = true;
        }
        androidx.core.os.LocaleListCompat locales =
                androidx.appcompat.app.AppCompatDelegate.getApplicationLocales();
        if (!locales.isEmpty() && locales.get(0) != null) {
            cfg.setLocales(new android.os.LocaleList(locales.get(0)));
            overridden = true;
        }
        themed = overridden ? app.createConfigurationContext(cfg) : app;
        return themed;
    }

    private int dp(int v) {
        return Math.round(v * app.getResources().getDisplayMetrics().density);
    }
}
