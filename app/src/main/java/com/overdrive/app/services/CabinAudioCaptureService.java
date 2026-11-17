package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.overdrive.app.R;
import com.overdrive.app.audio.AppAudioCaptureController;
import com.overdrive.app.communication.RemoteCommunicationSettings;
import com.overdrive.app.ui.MainActivity;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * On-demand foreground owner for remote cabin listening. The shared capture
 * controller keeps recording and listener demand on one AudioRecord session.
 */
public final class CabinAudioCaptureService extends Service {

    private static final String TAG = "CabinAudioService";
    private static final String CHANNEL_ID = "cabin_audio_listener";
    private static final int NOTIFICATION_ID = 9121;
    private static final long HEALTH_CHECK_MS = 2_000L;
    private static final long MAX_UNAVAILABLE_MS = 10_000L;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "CabinAudioCapture");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean active;
    private volatile long generation;
    private volatile long unavailableSince;
    private String activeToken = "";
    private ScheduledFuture<?> healthFuture;

    public static void stopNow(Context context) {
        try {
            context.stopService(new Intent(context, CabinAudioCaptureService.class));
        } catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getStringExtra("action");
        String token = intent.getStringExtra("token");
        if ("stop".equals(action)) {
            if (!active || (token != null && token.equals(activeToken))) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (!"start".equals(action) || token == null || token.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        RemoteCommunicationSettings.Snapshot settings =
                RemoteCommunicationSettings.load();
        if (settings.emergencyDisabled || !settings.listenerEnabled) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean diPlusCompatibility =
                RemoteCommunicationSettings.AUDIO_CHANNEL_NAVIGATION.equals(
                        settings.audioChannel);
        active = true;
        activeToken = token;
        unavailableSince = 0L;
        long session = ++generation;
        if (healthFuture != null) healthFuture.cancel(false);
        healthFuture = worker.scheduleWithFixedDelay(() -> {
            if (!active || generation != session) return;
            boolean available =
                    AppAudioCaptureController.setListenerDemand(
                            true, diPlusCompatibility);
            if (available) {
                unavailableSince = 0L;
            } else {
                Log.w(TAG, "Cabin microphone unavailable; retrying");
                long now = SystemClock.elapsedRealtime();
                if (unavailableSince == 0L) unavailableSince = now;
                if (now - unavailableSince >= MAX_UNAVAILABLE_MS) {
                    Log.w(TAG, "Cabin listener session unavailable; stopping");
                    stopSelf();
                    return;
                }
            }
            if (!active || generation != session) {
                AppAudioCaptureController.setListenerDemand(false);
            }
        }, 0L, HEALTH_CHECK_MS, TimeUnit.MILLISECONDS);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        active = false;
        generation++;
        activeToken = "";
        unavailableSince = 0L;
        if (healthFuture != null) healthFuture.cancel(true);
        worker.shutdownNow();
        AppAudioCaptureController.setListenerDemand(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.cabin_audio_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) launch = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.cabin_audio_notification_title))
                .setContentText(com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.cabin_audio_notification_text))
                .setSmallIcon(R.drawable.ic_overlay_mic_active)
                .setContentIntent(content)
                .setOngoing(true)
                .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }
}
