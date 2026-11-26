package com.overdrive.app.services;

import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.overdrive.app.util.DaemonHttpClient;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-process phone-call-state watcher. The daemon (UID 2000, synthetic Context) can't
 * hold a {@link TelephonyManager} listener, so this runs in the real app process
 * (started from {@link KeepAliveAccessibilityService}, which is always alive) and
 * RELAYS each state transition to the daemon via {@link DaemonHttpClient} →
 * {@code POST /api/automations/event}. The daemon publishes it as the whitelisted
 * {@code callState} automation event, enabling rules like "mute media on incoming call".
 *
 * <p>Detection only — no call control (answer/hangup would need ANSWER_PHONE_CALLS and
 * is a separate, heavier feature). Requires the {@code READ_PHONE_STATE} runtime
 * permission; if it isn't granted the monitor cleanly no-ops (never crashes).
 *
 * <p>Singleton + idempotent {@link #start}: the a11y service can re-bind and call
 * start() repeatedly; a second call is a no-op while already listening.
 */
public final class CallStateMonitor {

    private static final String TAG = "CallStateMonitor";
    private static CallStateMonitor instance;

    private final Context appContext;
    private TelephonyManager telephonyManager;
    private PhoneStateListener listener;
    private volatile String lastRelayed; // dedupe identical consecutive states
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "call-state-relay");
        t.setDaemon(true);
        return t;
    });

    private CallStateMonitor(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** Start the monitor (idempotent). Safe to call from onServiceConnected each bind. */
    public static synchronized void start(Context ctx) {
        if (instance != null) return;
        if (ctx == null) return;
        CallStateMonitor m = new CallStateMonitor(ctx);
        if (m.begin()) {
            instance = m;
        }
    }

    private boolean begin() {
        // Permission gate: without READ_PHONE_STATE, listen() delivers nothing (and on
        // some builds throws), so no-op cleanly rather than half-starting.
        if (appContext.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_PHONE_STATE not granted — call-state automations disabled");
            return false;
        }
        telephonyManager = (TelephonyManager) appContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) {
            Log.w(TAG, "no TelephonyManager");
            return false;
        }
        listener = new PhoneStateListener() {
            @Override public void onCallStateChanged(int state, String phoneNumber) {
                relay(mapState(state));
            }
        };
        try {
            // LISTEN_CALL_STATE. TelephonyCallback is the API-31 replacement, but this
            // head unit is API 29 where listen() is the correct, available API.
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            Log.i(TAG, "call-state monitor started");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "listen() failed: " + t.getMessage());
            return false;
        }
    }

    private static String mapState(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:  return "ringing";
            case TelephonyManager.CALL_STATE_OFFHOOK:  return "offhook";
            case TelephonyManager.CALL_STATE_IDLE:
            default:                                   return "idle";
        }
    }

    /** Relay a state to the daemon, deduping identical consecutive values. */
    private void relay(String state) {
        if (state == null || state.equals(lastRelayed)) return;
        lastRelayed = state;
        final String body = "{\"event\":\"callState\",\"value\":\"" + state + "\"}";
        io.submit(() -> {
            HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/automations/event", "POST", 2000, 3000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes()); }
                int code = conn.getResponseCode();
                Log.i(TAG, "relayed callState=" + state + " -> HTTP " + code);
            } catch (Throwable t) {
                Log.w(TAG, "relay failed: " + t.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
