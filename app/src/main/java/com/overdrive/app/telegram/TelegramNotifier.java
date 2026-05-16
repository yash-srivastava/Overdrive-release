package com.overdrive.app.telegram;

import android.util.Log;

import com.overdrive.app.server.Messages;
import com.overdrive.app.telegram.event.CriticalEvent;
import com.overdrive.app.telegram.event.MotionEvent;
import com.overdrive.app.telegram.event.TelegramEventBus;
import com.overdrive.app.telegram.event.TunnelEvent;
import com.overdrive.app.telegram.event.VideoEvent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Static helper for emitting Telegram events from anywhere in the app.
 * 
 * Sends notifications via IPC to TelegramBotDaemon (port 19877).
 * Also publishes to TelegramEventBus for in-app listeners.
 * 
 * Usage:
 *   TelegramNotifier.notifyVideoRecorded("/path/to/video.mp4", "person", 30);
 *   TelegramNotifier.notifyTunnelUrl("https://xxx.trycloudflare.com", true);
 *   TelegramNotifier.notifyMotion("person", 0.95f);
 *   TelegramNotifier.notifyCritical(CriticalEvent.CriticalType.LOW_BATTERY, "12%");
 */
public class TelegramNotifier {
    
    private static final String TAG = "TelegramNotifier";
    private static final int IPC_PORT = 19880;  // Telegram daemon IPC (moved from 19878 to free up that port for BydEventDaemon)
    
    // Background executor for IPC calls
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TelegramNotifierIPC");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Notify that a video recording was finalized.
     * 
     * @param filePath Path to the video file
     * @param aiDetection AI detection label (e.g., "person", "car") or null
     * @param durationSeconds Duration in seconds
     */
    public static void notifyVideoRecorded(String filePath, String aiDetection, int durationSeconds) {
        // Publish to in-app event bus
        TelegramEventBus.getInstance().publish(
                new VideoEvent(filePath, aiDetection, durationSeconds)
        );
        
        // Send via IPC to daemon
        executor.execute(() -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "sendVideo");
                cmd.put("path", filePath);
                String caption = (aiDetection != null)
                        ? Messages.get("telegram.recording_caption", aiDetection, durationSeconds)
                        : Messages.get("telegram.recording_caption_no_label", durationSeconds);
                cmd.put("caption", caption);
                sendIpc(cmd);
            } catch (Exception e) {
                Log.e(TAG, "notifyVideoRecorded IPC error", e);
            }
        });
    }
    
    /**
     * Notify that tunnel URL was created or changed.
     * 
     * @param url The tunnel URL
     * @param isNew true if new tunnel, false if URL changed
     */
    public static void notifyTunnelUrl(String url, boolean isNew) {
        Log.i(TAG, "notifyTunnelUrl called: url=" + url + ", isNew=" + isNew);
        
        // Publish to in-app event bus
        TelegramEventBus.getInstance().publish(
                new TunnelEvent(url, isNew)
        );
        
        // Send via IPC to daemon
        executor.execute(() -> {
            try {
                Log.i(TAG, "Sending tunnel URL via IPC to port " + IPC_PORT);
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyTunnel");
                cmd.put("url", url);
                cmd.put("isNew", isNew);
                JSONObject response = sendIpc(cmd);
                Log.i(TAG, "IPC response: " + (response != null ? response.toString() : "null"));
            } catch (Exception e) {
                Log.e(TAG, "notifyTunnelUrl IPC error", e);
            }
        });
    }
    
    /**
     * Notify motion detection.
     * 
     * @param aiDetection AI detection label or null for generic motion
     * @param confidence Detection confidence (0-1)
     */
    public static void notifyMotion(String aiDetection, float confidence) {
        notifyMotion(aiDetection, confidence, null);
    }
    
    /**
     * Notify motion detection with video filename.
     * 
     * @param aiDetection AI detection label or null for generic motion
     * @param confidence Detection confidence (0-1)
     * @param videoFilename The event video filename (e.g., "event_20260113_143022.mp4")
     */
    public static void notifyMotion(String aiDetection, float confidence, String videoFilename) {
        notifyMotion(aiDetection, confidence, videoFilename, null, 0, 0, 0, 0, null, null);
    }

    /**
     * Notify motion with full Actor metadata (item 3 redesign). Backwards-compat
     * helper — daemons that don't understand the new fields can still parse the
     * legacy fields. New fields are additive.
     *
     * @param severity         "NOTICE" / "ALERT" / "CRITICAL" or null
     * @param personCount      number of person Actors in the snapshot
     * @param vehicleCount     number of vehicle Actors
     * @param bikeCount        number of bike Actors
     * @param animalCount      number of animal Actors
     * @param closestProximity "VERY_CLOSE" / "CLOSE" / "MID" / "FAR" or null
     * @param camera           camera hint ("front"/"right"/"rear"/"left") or null
     */
    public static void notifyMotion(String aiDetection, float confidence, String videoFilename,
                                    String severity,
                                    int personCount, int vehicleCount, int bikeCount, int animalCount,
                                    String closestProximity, String camera) {
        // Publish to in-app event bus (legacy MotionEvent shape preserved)
        TelegramEventBus.getInstance().publish(
                new MotionEvent(aiDetection, confidence)
        );

        // Send via IPC to daemon (legacy + new fields)
        executor.execute(() -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyMotion");
                cmd.put("detection", aiDetection != null ? aiDetection : "motion");
                cmd.put("confidence", confidence);
                if (videoFilename != null && !videoFilename.isEmpty()) {
                    cmd.put("videoFilename", videoFilename);
                }
                // v3 additions — daemon ignores unknown fields, so older daemons keep working
                if (severity != null) cmd.put("severity", severity);
                if (personCount > 0)  cmd.put("personCount", personCount);
                if (vehicleCount > 0) cmd.put("vehicleCount", vehicleCount);
                if (bikeCount > 0)    cmd.put("bikeCount", bikeCount);
                if (animalCount > 0)  cmd.put("animalCount", animalCount);
                if (closestProximity != null) cmd.put("closestProximity", closestProximity);
                if (camera != null)   cmd.put("camera", camera);
                sendIpc(cmd);
            } catch (Exception e) {
                Log.e(TAG, "notifyMotion IPC error", e);
            }
        });
    }

    /**
     * Finalized motion notification: fired AFTER the recording closes and the
     * hero JPEG has been written. Daemon will send a Telegram photo (rather
     * than text only) using {@code heroPhotoPath} as the image. If the photo
     * path is missing or sendPhoto fails, daemon falls back to the rich
     * text-only message — never silently drops.
     *
     * @param heroPhotoPath  ABSOLUTE filesystem path to the hero JPEG, or null
     */
    public static void notifyMotionFinalized(String videoFilename, String heroPhotoPath,
                                             String severity,
                                             int personCount, int vehicleCount, int bikeCount, int animalCount,
                                             String closestProximity, String camera) {
        executor.execute(() -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyMotionFinalized");
                if (videoFilename != null && !videoFilename.isEmpty()) {
                    cmd.put("videoFilename", videoFilename);
                }
                if (heroPhotoPath != null && !heroPhotoPath.isEmpty()) {
                    cmd.put("heroPhotoPath", heroPhotoPath);
                }
                if (severity != null) cmd.put("severity", severity);
                if (personCount > 0)  cmd.put("personCount", personCount);
                if (vehicleCount > 0) cmd.put("vehicleCount", vehicleCount);
                if (bikeCount > 0)    cmd.put("bikeCount", bikeCount);
                if (animalCount > 0)  cmd.put("animalCount", animalCount);
                if (closestProximity != null) cmd.put("closestProximity", closestProximity);
                if (camera != null)   cmd.put("camera", camera);
                JSONObject resp = sendIpc(cmd);
                // FIX (C3): if the daemon predates this command (returns
                // "error" with "Unknown command"), retry once via the legacy
                // notifyMotion path so a stale daemon still ships a Telegram
                // message instead of dropping the event silently. Old daemons
                // can't send a photo, but text-only is better than nothing.
                if (resp != null
                        && "error".equals(resp.optString("status", ""))
                        && resp.optString("message", "").contains("Unknown command")) {
                    Log.w(TAG, "Daemon doesn't know notifyMotionFinalized; falling back to legacy notifyMotion");
                    JSONObject legacy = new JSONObject();
                    legacy.put("cmd", "notifyMotion");
                    legacy.put("detection", choosePrimaryDetection(personCount, vehicleCount, bikeCount, animalCount));
                    legacy.put("confidence", 1.0f);
                    if (videoFilename != null && !videoFilename.isEmpty()) {
                        legacy.put("videoFilename", videoFilename);
                    }
                    if (severity != null) legacy.put("severity", severity);
                    if (personCount > 0)  legacy.put("personCount", personCount);
                    if (vehicleCount > 0) legacy.put("vehicleCount", vehicleCount);
                    if (bikeCount > 0)    legacy.put("bikeCount", bikeCount);
                    if (animalCount > 0)  legacy.put("animalCount", animalCount);
                    if (closestProximity != null) legacy.put("closestProximity", closestProximity);
                    if (camera != null)   legacy.put("camera", camera);
                    sendIpc(legacy);
                }
            } catch (Exception e) {
                Log.e(TAG, "notifyMotionFinalized IPC error", e);
            }
        });
    }

    /** Engine-side mirror of TelegramBotDaemon.chooseTelegramPrimary for the
     *  legacy-fallback path. Class rank: PERSON > BIKE > VEHICLE > ANIMAL. */
    private static String choosePrimaryDetection(int p, int v, int b, int a) {
        if (p > 0) return "person";
        if (b > 0) return "bike";
        if (v > 0) return "vehicle";
        if (a > 0) return "animal";
        return "motion";
    }
    
    /**
     * Notify critical system event.
     * 
     * @param type Critical event type
     * @param details Additional details
     */
    public static void notifyCritical(CriticalEvent.CriticalType type, String details) {
        // Publish to in-app event bus
        TelegramEventBus.getInstance().publish(
                new CriticalEvent(type, details)
        );
        
        // Send via IPC to daemon
        executor.execute(() -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyCritical");
                cmd.put("type", type.name());
                cmd.put("details", details);
                sendIpc(cmd);
            } catch (Exception e) {
                Log.e(TAG, "notifyCritical IPC error", e);
            }
        });
    }
    
    /**
     * Send a custom text message via the daemon.
     */
    public static void sendMessage(String text) {
        executor.execute(() -> {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "sendMessage");
                cmd.put("text", text);
                sendIpc(cmd);
            } catch (Exception e) {
                Log.e(TAG, "sendMessage IPC error", e);
            }
        });
    }
    
    /**
     * Notify proximity alert (Proximity Guard recording started).
     * 
     * @param timestamp Event timestamp in milliseconds
     * @param triggerLevel Trigger level ("RED" or "YELLOW")
     */
    public static void sendProximityAlert(long timestamp, String triggerLevel) {
        executor.execute(() -> {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                String timeStr = sdf.format(new java.util.Date(timestamp));
                
                String distance = triggerLevel.equals("RED") ? "0-0.5m" : "0-0.8m";

                String message = Messages.get("telegram.proximity_alert",
                        timeStr, triggerLevel, distance);
                
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "sendMessage");
                cmd.put("text", message);
                sendIpc(cmd);
                
                Log.i(TAG, "Proximity alert sent: " + triggerLevel);
            } catch (Exception e) {
                Log.e(TAG, "sendProximityAlert IPC error", e);
            }
        });
    }
    
    /**
     * Send IPC command to TelegramBotDaemon.
     */
    private static JSONObject sendIpc(JSONObject command) {
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", IPC_PORT);
            socket.setSoTimeout(5000);
            
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            writer.println(command.toString());
            String response = reader.readLine();
            
            if (response != null) {
                JSONObject json = new JSONObject(response);
                String status = json.optString("status", "");
                if (!"ok".equals(status)) {
                    Log.w(TAG, "IPC response: " + response);
                }
                return json;
            }
            return null;
        } catch (java.net.ConnectException e) {
            // Daemon not running - this is expected if telegram is disabled
            Log.d(TAG, "Telegram daemon not running");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "IPC error: " + e.getMessage());
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}
