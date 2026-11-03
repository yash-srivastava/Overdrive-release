package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.overdrive.app.R;
import com.overdrive.app.roadsense.config.RoadSenseAudioChannels;

/**
 * Isolated app-process player for short RoadSense warning chimes.
 *
 * <p>The normal {@link MediaPlaybackService} is intentionally replace-on-play for
 * Automation Audio. Sharing that player made a warning cancel a looping automation,
 * while an automation arriving during chime preparation could suppress the warning.
 * This service owns a separate MediaPlayer but uses the exact same OEM channel-routing
 * recipe, allowing the two audio classes to overlap without replacing each other.
 */
public final class RoadSenseChimePlaybackService extends Service {

    private static final String TAG = "RoadSenseChime";
    private static final String CHANNEL_ID = "overdrive_media_playback";
    private static final int NOTIFICATION_ID = 9972;

    private MediaPlayer player;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean focusHeld;
    private boolean automationDucked;
    private int latestStartId;

    @Override public void onCreate() {
        super.onCreate();
        try {
            createChannel();
        } catch (Throwable t) {
            Log.w(TAG, "createChannel failed: " + t.getMessage());
        }
        startForegroundCompat();
        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "AudioManager unavailable: " + t.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        StartRequest request = readStartRequest(intent);
        if (request == null) {
            Log.w(TAG, "rejected malformed chime start");
            stopIfIdle();
            return START_NOT_STICKY;
        }

        int resourceId = rawResourceId(request.resourceName);
        if (resourceId == 0) {
            Log.w(TAG, "unsupported raw resource: " + request.resourceName);
            stopIfIdle();
            return START_NOT_STICKY;
        }

        String channel = request.channel;
        int volumePercent = request.volumePercent;

        beginAutomationDuck();
        releasePlayer();
        abandonFocus();
        requestFocus(channel);

        MediaPlayer next;
        try {
            next = new MediaPlayer();
        } catch (Throwable t) {
            Log.w(TAG, "MediaPlayer construction failed: " + t.getMessage());
            abandonFocus();
            endAutomationDuck();
            stopIfIdle();
            return START_NOT_STICKY;
        }
        player = next;

        try {
            MediaPlaybackService.applyChannelRouting(next, channel);
            Uri uri = Uri.parse("android.resource://" + getPackageName() + "/" + resourceId);
            next.setDataSource(this, uri);
            next.setLooping(false);
            float volume = volumePercent / 100f;
            next.setVolume(volume, volume);
            next.setOnPreparedListener(prepared -> {
                if (player != prepared) return;
                try {
                    prepared.start();
                    Log.i(TAG, "playback started (channel=" + channel
                            + " volume=" + volumePercent + "%)");
                } catch (Throwable t) {
                    Log.w(TAG, "start failed: " + t.getMessage());
                    finish(prepared);
                }
            });
            next.setOnCompletionListener(this::finish);
            next.setOnErrorListener((failed, what, extra) -> {
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                finish(failed);
                return true;
            });
            next.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "setup failed: " + t.getMessage());
            finish(next);
        }
        return START_NOT_STICKY;
    }

    private StartRequest readStartRequest(Intent intent) {
        if (intent == null) return null;
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return null;
            Object rawResource = extras.get("resName");
            if (!(rawResource instanceof String)) return null;
            String resourceName = ((String) rawResource).trim();
            if (resourceName.isEmpty()) return null;

            boolean hasChannel = extras.containsKey("channel");
            String channel = parseChannel(extras.get("channel"), hasChannel);
            if (channel == null) return null;

            boolean hasVolume = extras.containsKey("volumePercent");
            Integer volumePercent =
                    parseVolumePercent(extras.get("volumePercent"), hasVolume);
            if (volumePercent == null) return null;
            return new StartRequest(resourceName, channel, volumePercent);
        } catch (Throwable t) {
            // This component is exported to the shell UID. BadParcelableException and
            // adversarial extra types must reject the start, never escape into the app.
            Log.w(TAG, "could not parse chime extras: " + t.getClass().getSimpleName());
            return null;
        }
    }

    static String parseChannel(Object raw, boolean present) {
        if (!present) return RoadSenseAudioChannels.DEFAULT;
        if (!(raw instanceof String)) return null;
        String value = ((String) raw).trim();
        if (value.isEmpty()) return RoadSenseAudioChannels.DEFAULT;
        return RoadSenseAudioChannels.isSupported(value)
                ? RoadSenseAudioChannels.normalize(value) : null;
    }

    static Integer parseVolumePercent(Object raw, boolean present) {
        if (!present) return 100;
        final int value;
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) return null;
            try {
                value = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if (raw instanceof Integer) {
            value = (Integer) raw;
        } else {
            return null;
        }
        return value >= 1 && value <= 100 ? value : null;
    }

    static int rawResourceId(String name) {
        if ("roadsense_chime_minor".equals(name)) return R.raw.roadsense_chime_minor;
        if ("roadsense_chime_moderate".equals(name)) return R.raw.roadsense_chime_moderate;
        if ("roadsense_chime_severe".equals(name)) return R.raw.roadsense_chime_severe;
        return 0;
    }

    private void requestFocus(String channel) {
        if (audioManager == null) return;
        try {
            focusListener = change -> { /* brief warning; never pause on focus loss */ };
            audioManager.requestAudioFocus(
                    focusListener,
                    MediaPlaybackService.streamForChannel(channel),
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            focusHeld = true;
        } catch (Throwable t) {
            Log.w(TAG, "requestAudioFocus failed: " + t.getMessage());
        }
    }

    private void abandonFocus() {
        if (audioManager == null || !focusHeld) return;
        try {
            audioManager.abandonAudioFocus(focusListener);
        } catch (Throwable ignored) {
        } finally {
            focusHeld = false;
            focusListener = null;
        }
    }

    private void finish(MediaPlayer expected) {
        if (player != expected) return;
        releasePlayer();
        abandonFocus();
        endAutomationDuck();
        stopIfIdle();
    }

    private void releasePlayer() {
        MediaPlayer current = player;
        player = null;
        if (current == null) return;
        try {
            if (current.isPlaying()) current.stop();
        } catch (Throwable ignored) {
        }
        try {
            current.release();
        } catch (Throwable ignored) {
        }
    }

    private void beginAutomationDuck() {
        if (automationDucked) return;
        automationDucked = true;
        MediaPlaybackService.ROAD_SENSE_DUCK.begin();
    }

    private void endAutomationDuck() {
        if (!automationDucked) return;
        automationDucked = false;
        MediaPlaybackService.ROAD_SENSE_DUCK.end();
    }

    private void stopIfIdle() {
        if (player == null) stopSelfResult(latestStartId);
    }

    @Override public void onDestroy() {
        releasePlayer();
        abandonFocus();
        endAutomationDuck();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class StartRequest {
        final String resourceName;
        final String channel;
        final int volumePercent;

        StartRequest(String resourceName, String channel, int volumePercent) {
            this.resourceName = resourceName;
            this.channel = channel;
            this.volumePercent = volumePercent;
        }
    }

    private void startForegroundCompat() {
        Notification notification;
        try {
            notification = buildNotification();
        } catch (Throwable t) {
            Log.w(TAG, "buildNotification failed: " + t.getMessage());
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable first) {
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Throwable second) {
                Log.w(TAG, "startForeground failed: " + second.getMessage());
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.notif_channel_media_playback), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.notif_roadsense_chime_title))
                .setContentText(com.overdrive.app.util.AppLocaleContext.INSTANCE.of(this).getString(com.overdrive.app.R.string.notif_roadsense_chime_text))
                .setSmallIcon(R.drawable.ic_play_circle)
                .setOngoing(true)
                .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }
}
