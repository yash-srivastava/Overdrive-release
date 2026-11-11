package com.overdrive.app.telegram;

import android.util.Log;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.server.LocaleManager;
import com.overdrive.app.telegram.config.UnifiedTelegramConfig;
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
    /** Bounds the connect() so a backlogged daemon can't park the executor past the read timeout. */
    private static final int CONNECT_TIMEOUT_MS = 1500;
    
    // Background executor for the text/photo IPC calls (notifyMotion,
    // notifyMotionFinalized, notifyTunnelUrl). Single-thread, FIFO.
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TelegramNotifierIPC");
        t.setDaemon(true);
        return t;
    });

    // Dedicated lane for VIDEO uploads. A surveillance clip is the slowest send
    // in the system (multi-MB multipart + up to a 60s 429 sleep daemon-side),
    // and its IPC response is discarded. Keeping it OUT of `executor` means a
    // slow clip never sits in front of the NEXT event's motion text / hero
    // photo — the head-of-line block that made notifications late & stale when
    // video sending is on. Single-thread so clips stay FIFO among themselves.
    private static final ExecutorService videoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TelegramNotifierVideo");
        t.setDaemon(true);
        return t;
    });

    // Dedicated lane for time-critical alerts (CRITICAL category: proximity,
    // low-battery, manual). A burst of motion notifications on the main
    // `executor` (each blocking up to the connect+read timeout when the daemon
    // is slow) must not delay a collision/critical alert behind it — head-of-
    // line isolation. Single-thread so CRITICAL alerts stay mutually ordered.
    private static final ExecutorService criticalExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TelegramNotifierCrit");
        t.setDaemon(true);
        return t;
    });

    /**
     * Notification category — chosen at the emit site so the gate can run
     * before IPC, even when the daemon isn't alive (TelegramBotDaemon only
     * runs during ACC OFF). Aligned with the toggles in
     * Notifications → Telegram and the daemon-side gate in
     * TelegramBotDaemon.processIpcCommand.
     */
    public enum Category { CRITICAL, CONNECTIVITY, MOTION, VIDEO, PARKING }

    /**
     * Read the matching pref directly from the unified config so a toggle
     * the user just flipped from the web UI is honored on the very next
     * event — independent of whether TelegramBotDaemon is alive. Without
     * this we'd be relying on the daemon-side gate, which is moot when the
     * daemon isn't running and the IPC call would have been dropped on the
     * floor regardless.
     *
     * forceReload() bypasses the per-process mtime cache so a write made
     * by the daemon UID (2000) is visible from the app UID immediately.
     * Called on the IPC executor thread, never on the UI thread.
     */
    private static boolean isEnabled(Category category) {
        try {
            UnifiedConfigManager.forceReload();
            switch (category) {
                case CRITICAL:     return UnifiedTelegramConfig.isCriticalAlerts();
                case CONNECTIVITY: return UnifiedTelegramConfig.isConnectivity();
                case MOTION:       return UnifiedTelegramConfig.isMotionText();
                case VIDEO:        return UnifiedTelegramConfig.isVideoUploads();
                case PARKING:      return UnifiedTelegramConfig.isParkingMessages();
                default:           return true;
            }
        } catch (Exception e) {
            // Fail-open: if the config read blips, prefer delivering the
            // message over silently dropping it. The daemon-side gate is
            // the belt-and-suspenders backstop.
            Log.w(TAG, "isEnabled read error: " + e.getMessage());
            return true;
        }
    }
    
    /**
     * Notify that a video recording was finalized.
     * 
     * @param filePath Path to the video file
     * @param aiDetection AI detection label (e.g., "person", "car") or null
     * @param durationSeconds Duration in seconds
     */
    public static void notifyVideoRecorded(String filePath, String aiDetection, int durationSeconds) {
        notifyVideoRecorded(filePath, aiDetection, durationSeconds, "");
    }

    /** Video notification with optional confirmed deterrent execution details. */
    public static void notifyVideoRecorded(String filePath, String aiDetection, int durationSeconds,
                                           String extraCaption) {
        // Publish to in-app event bus (independent of Telegram delivery)
        TelegramEventBus.getInstance().publish(
                new VideoEvent(filePath, aiDetection, durationSeconds)
        );

        // Send via IPC to daemon on the dedicated VIDEO lane so a slow clip
        // upload can't delay the next event's motion text / hero photo.
        videoExecutor.execute(() -> {
            try {
                if (!isEnabled(Category.VIDEO)) {
                    Log.d(TAG, "notifyVideoRecorded skipped — videoUploads disabled");
                    return;
                }
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "sendVideo");
                cmd.put("path", filePath);
                String caption = (aiDetection != null)
                        ? TelegramMessages.get("recording_caption",
                                localizedDetection(aiDetection), durationSeconds)
                        : TelegramMessages.get("recording_caption_no_label", durationSeconds);
                if (extraCaption != null && !extraCaption.isEmpty()) {
                    caption += "\n" + extraCaption;
                }
                cmd.put("caption", caption);
                // Fire-and-forget: the response is unused, and the upload can
                // take tens of seconds — don't tie up even this lane waiting on
                // a reply we'd discard.
                sendIpcFireAndForget(cmd);
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
                if (!isEnabled(Category.CONNECTIVITY)) {
                    Log.d(TAG, "notifyTunnelUrl skipped — connectivity updates disabled");
                    return;
                }
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

        // Capture the detection time NOW (call moment), not inside the lambda
        // which runs later once the executor drains — so a delayed send still
        // reports when the event actually happened.
        final long eventTimeMs = System.currentTimeMillis();

        // Send via IPC to daemon (legacy + new fields)
        executor.execute(() -> {
            try {
                if (!isEnabled(Category.MOTION)) {
                    Log.d(TAG, "notifyMotion skipped — motion text alerts disabled");
                    return;
                }
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyMotion");
                cmd.put("detection", aiDetection != null ? aiDetection : "motion");
                cmd.put("confidence", confidence);
                cmd.put("eventTimeMs", eventTimeMs);
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
        // Capture the finalization time at the call moment (see notifyMotion).
        final long eventTimeMs = System.currentTimeMillis();
        executor.execute(() -> {
            try {
                if (!isEnabled(Category.MOTION)) {
                    Log.d(TAG, "notifyMotionFinalized skipped — motion text alerts disabled");
                    return;
                }
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyMotionFinalized");
                cmd.put("eventTimeMs", eventTimeMs);
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
                // Spool on daemon-down (the ACC-on teardown race: stopRecording
                // finalizes after AccSentryDaemon already SIGKILL'd the bot).
                // The hero JPEG persists on disk and the daemon re-checks
                // exists() on replay, so a late delivery still ships the photo
                // (or falls back to text). A connect-refused returns null, so
                // the legacy fallback below correctly does NOT fire for it.
                JSONObject resp = sendIpc(cmd, /*spoolOnDaemonDown=*/true);
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
                    legacy.put("eventTimeMs", eventTimeMs);
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

    /**
     * Parking Intelligence message ("Parked · place …" / "Back at car …").
     * Sent as a photo when {@code photoPath} exists (the four-camera arrived
     * composite), else as text; {@code buttonsJson} is a JSON array of
     * {@code {"text","url"}} URL buttons (e.g. "Walk me back" → maps link).
     * Gated on {@code telegram.parkingMessages}; spooled on daemon-down so a
     * "Parked" fired in the ACC-off bot start-up window is still delivered.
     *
     * @param photoPath   absolute JPEG path readable by the bot UID, or null
     * @param buttonsJson serialized JSON array of {text,url}, or null
     */
    public static void notifyParking(String title, String body, String photoPath, String buttonsJson) {
        final long eventTimeMs = System.currentTimeMillis();
        executor.execute(() -> {
            try {
                if (!isEnabled(Category.PARKING)) {
                    Log.d(TAG, "notifyParking skipped — parking messages disabled");
                    return;
                }
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyParking");
                cmd.put("eventTimeMs", eventTimeMs);
                cmd.put("title", title == null ? "" : title);
                cmd.put("body", body == null ? "" : body);
                if (photoPath != null && !photoPath.isEmpty()) cmd.put("photoPath", photoPath);
                if (buttonsJson != null && !buttonsJson.isEmpty()) {
                    try { cmd.put("buttons", new org.json.JSONArray(buttonsJson)); } catch (Exception ignored) {}
                }
                sendIpc(cmd, /*spoolOnDaemonDown=*/true);
            } catch (Exception e) {
                Log.e(TAG, "notifyParking IPC error", e);
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

    private static String localizedDetection(String detection) {
        if (detection == null || detection.isEmpty()) return "";
        switch (detection.toLowerCase(java.util.Locale.ROOT)) {
            case "person": return TelegramMessages.get("recording_label.person");
            case "bike":
            case "bicycle": return TelegramMessages.get("recording_label.bike");
            case "car":
            case "vehicle": return TelegramMessages.get("recording_label.vehicle");
            case "animal": return TelegramMessages.get("recording_label.animal");
            case "motion": return TelegramMessages.get("recording_label.motion");
            default: return detection;
        }
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

        // Send via IPC to daemon — on the dedicated critical lane so a motion/
        // video burst can't delay it.
        criticalExecutor.execute(() -> {
            try {
                if (!isEnabled(Category.CRITICAL)) {
                    Log.d(TAG, "notifyCritical skipped — critical alerts disabled");
                    return;
                }
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "notifyCritical");
                cmd.put("type", type.name());
                cmd.put("details", details);
                // Critical alerts are worth delivering late: spool if the
                // daemon is down (ACC ON). Replay re-applies the criticalAlerts
                // + owner gate on the daemon side.
                sendIpc(cmd, /*spoolOnDaemonDown=*/true);
            } catch (Exception e) {
                Log.e(TAG, "notifyCritical IPC error", e);
            }
        });
    }
    
    /**
     * Send a custom text message via the daemon. Defaults to the
     * {@code CRITICAL} category so a user who turns off "critical alerts"
     * silences these too. Use {@link #sendMessage(String, String)} to
     * declare a different category.
     */
    public static void sendMessage(String text) {
        sendMessage(text, "CRITICAL");
    }

    /**
     * Send a custom text message in a specific notification category.
     * The daemon checks the matching toggle (criticalAlerts /
     * connectivity / motionText / videoUploads) and drops the message
     * with status="skipped" when disabled.
     *
     * @param text     message body (Telegram-Markdown allowed)
     * @param category one of "CRITICAL", "MOTION", "CONNECTIVITY", "VIDEO";
     *                 unknown values fall back to CRITICAL
     */
    public static void sendMessage(String text, String category) {
        // Resolve category up front so we can pick the lane: CRITICAL gets the
        // dedicated criticalExecutor so it can't queue behind a motion/video
        // burst on the shared executor.
        final Category cat;
        Category parsed;
        try { parsed = (category != null) ? Category.valueOf(category) : Category.CRITICAL; }
        catch (IllegalArgumentException iae) { parsed = Category.CRITICAL; }
        cat = parsed;
        ExecutorService lane = (cat == Category.CRITICAL) ? criticalExecutor : executor;
        lane.execute(() -> {
            try {
                if (!isEnabled(cat)) {
                    Log.d(TAG, "sendMessage skipped — " + cat + " disabled");
                    return;
                }
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "sendMessage");
                cmd.put("text", text);
                cmd.put("category", cat.name());
                // CRITICAL-category messages (proximity collision alert,
                // install-failure) are worth delivering late if the daemon was
                // down (ACC ON). MOTION/CONNECTIVITY/VIDEO ad-hoc text is not
                // spooled — it's either low-value-if-late or rotates.
                sendIpc(cmd, /*spoolOnDaemonDown=*/cat == Category.CRITICAL);
            } catch (Exception e) {
                Log.e(TAG, "sendMessage IPC error", e);
            }
        });
    }

    /**
     * Explicitly opted-in proximity access events use the existing priority
     * lane. Keep their revision metadata through IPC/spool for delivery checks.
     */
    public static void sendRemoteAccess(String text, JSONObject eventData) {
        if (eventData == null) return;
        final JSONObject data;
        try { data = new JSONObject(eventData.toString()); }
        catch (Exception invalid) { return; }
        criticalExecutor.execute(() -> {
            try {
                if (!com.overdrive.app.remoteaccess.RemoteAccessNotifications
                        .canDeliver(data, "telegram")
                        || !UnifiedTelegramConfig.shouldStartOnAccOff()
                        || !UnifiedTelegramConfig.hasOwner()) return;
                JSONObject cmd = new JSONObject()
                        .put("cmd", "sendMessage").put("text", text)
                        .put("category",
                                com.overdrive.app.remoteaccess.RemoteAccessNotifications.TELEGRAM_CATEGORY)
                        .put("remoteAccess", data);
                sendIpc(cmd, /*spoolOnDaemonDown=*/true);
            } catch (Exception unavailable) {
                Log.w(TAG, "Proximity access notification failed: " + unavailable.getMessage());
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
        // Critical lane — a collision warning must not queue behind a motion
        // burst.
        criticalExecutor.execute(() -> {
            try {
                if (!isEnabled(Category.CRITICAL)) {
                    Log.d(TAG, "sendProximityAlert skipped — critical alerts disabled");
                    return;
                }
                String language = LocaleManager.get();
                String pattern = language.startsWith("pt")
                        ? "dd/MM/yyyy HH:mm:ss" : "yyyy-MM-dd HH:mm:ss";
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        pattern, java.util.Locale.forLanguageTag(language));
                String timeStr = sdf.format(new java.util.Date(timestamp));

                String distance = triggerLevel.equals("RED") ? "0-0.5m" : "0-0.8m";
                String triggerKey = "RED".equals(triggerLevel)
                        ? "proximity.trigger.red" : "proximity.trigger.yellow";

                String message = TelegramMessages.get("proximity_alert",
                        timeStr, TelegramMessages.get(triggerKey), distance);

                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "sendMessage");
                cmd.put("text", message);
                cmd.put("category", "CRITICAL");
                // Proximity Guard is an ACC-ON feature, so the daemon is
                // usually DOWN when this fires — spool so the collision alert
                // is delivered when the car next parks instead of vanishing.
                sendIpc(cmd, /*spoolOnDaemonDown=*/true);

                Log.i(TAG, "Proximity alert sent: " + triggerLevel);
            } catch (Exception e) {
                Log.e(TAG, "sendProximityAlert IPC error", e);
            }
        });
    }
    
    /** Default send: no spooling (response-bearing / non-spoolable callers). */
    private static JSONObject sendIpc(JSONObject command) {
        return sendIpc(command, /*spoolOnDaemonDown=*/false);
    }

    /**
     * Send an IPC command to TelegramBotDaemon.
     *
     * <p>The connect is bounded independently of the read so a backlogged
     * daemon (accept queue full) can't park the calling executor past the
     * read timeout: {@code connect()} gets {@value #CONNECT_TIMEOUT_MS}ms,
     * {@code read} gets 5s via soTimeout.
     *
     * @param spoolOnDaemonDown when true, a definitive "daemon not listening"
     *        (connection refused) persists the command to {@link TelegramSpool}
     *        for replay on the daemon's next startup. Only set this for
     *        commands that are safe to deliver late and where a refused connect
     *        proves nothing was sent (so no duplicate) — e.g. proximity /
     *        critical / finalized-motion. A read timeout is NOT spooled: the
     *        daemon may have already processed the send.
     */
    private static JSONObject sendIpc(JSONObject command, boolean spoolOnDaemonDown) {
        Socket socket = null;
        boolean connected = false;
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", IPC_PORT),
                    CONNECT_TIMEOUT_MS);
            connected = true;  // past this point a failure is AFTER the request may have been sent
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
            // Daemon not listening — it only runs during ACC OFF, so this is the
            // ACC-ON / teardown-race drop. A refused connect means nothing was
            // sent (no dup risk), so spool when the caller said it's safe.
            spoolOrLog(command, spoolOnDaemonDown);
            return null;
        } catch (java.net.SocketTimeoutException e) {
            // A connect-phase timeout (daemon alive but accept backlog full) is
            // ALSO pre-write — nothing was sent — so it's dup-safe to spool just
            // like a refused connect. A POST-connect read timeout is NOT spooled
            // (the daemon may already have processed the command).
            if (!connected) {
                spoolOrLog(command, spoolOnDaemonDown);
            } else {
                Log.w(TAG, "IPC read timeout (not spooling — may have been processed): "
                        + command.optString("cmd", "?"));
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "IPC error: " + e.getMessage());
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Fire-and-forget IPC send: deliver the command, do NOT block on the
     * response. Used for sendVideo, whose response is discarded and whose
     * upload can take tens of seconds daemon-side.
     *
     * <p>The daemon reads exactly one line then dispatches the work to a worker
     * pool and performs the upload independently of whether the client reads the
     * reply — so writing the line + a clean half-close is sufficient to trigger
     * the send. We {@code shutdownOutput()} (flush + FIN) so the daemon's
     * {@code readLine()} returns the full command promptly, then return without
     * waiting. A refused connect is logged (video is intentionally NOT spooled —
     * a stale multi-MB clip replayed after a drive is undesirable).
     */
    private static void sendIpcFireAndForget(JSONObject command) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", IPC_PORT),
                    CONNECT_TIMEOUT_MS);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            writer.println(command.toString());
            writer.flush();
            // Half-close the write side: flushes the bytes and sends a FIN so the
            // daemon's readLine() sees a clean end-of-request. We never read the
            // reply — the daemon uploads regardless, and a later daemon write to
            // our closed socket fails harmlessly on its side.
            try { socket.shutdownOutput(); } catch (Exception ignored) {}
        } catch (java.net.ConnectException e) {
            Log.d(TAG, "Telegram daemon not running (video send dropped)");
        } catch (Exception e) {
            Log.w(TAG, "IPC fire-and-forget error: " + e.getMessage());
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** Spool the command if the caller opted in, else just log the daemon-down drop. */
    private static void spoolOrLog(JSONObject command, boolean spoolOnDaemonDown) {
        if (spoolOnDaemonDown) {
            boolean ok = TelegramSpool.offer(command);
            Log.w(TAG, "Telegram daemon unreachable; "
                    + (ok ? "spooled for later delivery: " : "spool failed, dropped: ")
                    + command.optString("cmd", "?"));
        } else {
            Log.d(TAG, "Telegram daemon not running");
        }
    }
}
