package com.overdrive.app.proximity;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.telegram.TelegramNotifier;
import com.overdrive.app.telegram.TelegramMessages;
import com.overdrive.app.server.LocaleManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Proximity Recording Handler
 * 
 * Manages the recording lifecycle for Proximity Guard events:
 * - Pre-buffer capture
 * - Recording start/stop
 * - File naming with proximity_ prefix
 * - Storage management
 * - Telegram notifications
 */
public class ProximityRecordingHandler {
    private static final DaemonLogger logger = DaemonLogger.getInstance("ProximityRecordingHandler");
    
    private final GpuSurveillancePipeline pipeline;
    private final StorageManager storageManager;
    
    private File outputDir;
    private String currentTriggerLevel;
    private boolean isRecording = false;
    // Filename captured when the start-stage push was published. Reused on
    // stop so the matching final-stage push uses the same tag (Web Push
    // tag-replace semantics: a later push with the same tag swaps the
    // banner) and points at the now-finalised .mp4 + sibling hero JPEG.
    private String activeRecordingFile;
    
    public ProximityRecordingHandler(GpuSurveillancePipeline pipeline) {
        this.pipeline = pipeline;
        this.storageManager = StorageManager.getInstance();
        this.outputDir = storageManager.getProximityDir();
    }
    
    /**
     * Start proximity recording.
     * 
     * @param triggerLevel The trigger level that caused recording ("YELLOW" or "RED")
     */
    public void startRecording(String triggerLevel) {
        if (isRecording) {
            logger.warn("Already recording, ignoring start request");
            return;
        }
        
        currentTriggerLevel = triggerLevel;
        
        try {
            // Per-trigger SD/USB mount sanity-check, mirroring the surveillance
            // engine's trigger-time check. Proximity follows the RECORDINGS
            // (ACC-ON) storage type, so probe THAT volume. A boot/intermittent
            // SD unmount could have dropped the card; attempt a remount so this
            // clip lands on the configured external instead of the internal
            // fallback. No-op for INTERNAL config / already-mounted.
            try {
                StorageManager.StorageType type = storageManager.getRecordingsStorageType();
                if (type == StorageManager.StorageType.SD_CARD && !storageManager.isSdCardMounted()) {
                    logger.warn("SD card unmounted before proximity recording — attempting remount");
                    storageManager.ensureSdCardMounted(true);
                } else if (type == StorageManager.StorageType.USB && !storageManager.isUsbMounted()) {
                    logger.warn("USB unmounted before proximity recording — attempting remount");
                    storageManager.ensureUsbMounted(true);
                }
            } catch (Exception e) {
                logger.warn("Proximity storage mount check failed: " + e.getMessage());
            }

            // Ensure space available (reserve 50MB)
            boolean spaceAvailable = storageManager.ensureProximitySpace(50 * 1024 * 1024);
            if (!spaceAvailable) {
                logger.error("Insufficient space for proximity recording");
                return;
            }

            // Resolve the LIVE proximity dir (getLiveProximityDir, not the frozen
            // getProximityDir snapshot): proximity follows the recordings/ACC-ON
            // volume, and a mount that landed after boot — or the ensure*Mounted
            // attempt just above — must be picked up so the clip lands on SD, not
            // the stale internal fallback. Null-guard back to the snapshot.
            File live = storageManager.getLiveProximityDir();
            outputDir = (live != null) ? live : storageManager.getProximityDir();

            // Start recording with proximity directory and prefix
            pipeline.startRecording(outputDir, "proximity");
            isRecording = true;
            
            logger.info("Proximity recording started: trigger=" + triggerLevel + " dir=" + outputDir.getAbsolutePath());
            
            // Send Telegram notification
            sendTelegramNotification(triggerLevel);

            // Publish to NotificationBus → push notifications
            publishProximityNotification(triggerLevel);

        } catch (Exception e) {
            logger.error("Failed to start proximity recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Publish a proximity notification onto the cross-cutting NotificationBus.
     *
     * <p>RED trigger ({@code <0.5m}) is treated as critical so it overrides
     * the user's quiet hours. YELLOW ({@code 0.5–0.8m}) is warn.
     */
    private void publishProximityNotification(String triggerLevel) {
        try {
            boolean red = "RED".equals(triggerLevel);
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("triggerLevel", triggerLevel);

            // The pipeline's encoder was started above with the proximity_
            // prefix. Pull the active output path so the push deep-links to
            // the exact clip being recorded and renders its thumbnail.
            String filename = activeRecordingFilename();
            activeRecordingFile = filename;
            String url;
            if (filename != null) {
                String enc = java.net.URLEncoder.encode(filename, "UTF-8");
                data.put("filename", filename);
                // Mark as the start-stage event. The hero JPEG is only
                // written when the segment finalises in stopRecording, so
                // pointing the SW at a still-live .mp4 right now would only
                // hit MMR's 202-while-generating window. Carrying stage so
                // the SW skips the snapshot fetch on this event. The
                // matching final-stage push fires from stopRecording with
                // the same tag and a real signed snapshot URL.
                data.put("stage", "start");
                url = "/events.html?filter=proximity&file=" + enc;
            } else {
                url = "/events.html?filter=proximity";
            }

            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            "surveillance.proximity",
                            red
                                    ? com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL
                                    : com.overdrive.app.notifications.NotificationEvent.Severity.WARN,
                            red ? "Object very close" : "Object nearby",
                            red ? "Within 0.5 m" : "Within 0.8 m",
                            "proximity-" + triggerLevel,
                            url,
                            data));
        } catch (Throwable t) {
            logger.debug("publishProximityNotification failed: " + t.getMessage());
        }
    }

    private String activeRecordingFilename() {
        try {
            if (pipeline == null) return null;
            com.overdrive.app.surveillance.HardwareEventRecorderGpu enc = pipeline.getEncoder();
            if (enc == null) return null;
            String path = enc.getCurrentOutputPath();
            if (path == null || path.isEmpty()) return null;
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Throwable t) {
            return null;
        }
    }
    
    /**
     * Stop proximity recording.
     */
    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        String triggerLevelAtStop = currentTriggerLevel;
        String videoFile = activeRecordingFile;

        try {
            // Stop recording
            pipeline.stopRecording();
            isRecording = false;

            logger.info("Proximity recording stopped");

            // Final-stage push with the now-finalised hero JPEG. The start
            // push deliberately skipped the snapshot URL because the hero
            // JPEG is only written when stopRecording finalises the segment.
            // Reusing the same notification tag ("proximity-<level>") so
            // Web Push tag-replace semantics swap the banner image in
            // place rather than stacking a second card.
            publishProximityFinal(triggerLevelAtStop, videoFile);

        } catch (Exception e) {
            logger.error("Failed to stop proximity recording: " + e.getMessage());
            e.printStackTrace();
        } finally {
            activeRecordingFile = null;
        }
    }

    /**
     * Publish the final-stage push for a proximity recording. Carries a
     * signed snapshot URL pointing at the sibling hero JPEG ({@code base.jpg}
     * written by HardwareEventRecorderGpu on segment finalisation). When
     * the JPEG is missing (rare — encoder error), falls back to the .mp4
     * which the server resolves via MMR.
     */
    private void publishProximityFinal(String triggerLevel, String videoFile) {
        if (videoFile == null || videoFile.isEmpty()) return;
        try {
            boolean red = "RED".equals(triggerLevel);
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("triggerLevel", triggerLevel);
            data.put("filename", videoFile);
            data.put("stage", "final");

            String heroName = videoFile.endsWith(".mp4")
                    ? videoFile.substring(0, videoFile.length() - 4) + ".jpg"
                    : videoFile + ".jpg";
            File heroFile = new File(outputDir, heroName);
            String snapshotName = heroFile.exists() ? heroName : videoFile;
            String encSnap = java.net.URLEncoder.encode(snapshotName, "UTF-8");
            String thumbTok = com.overdrive.app.auth.AuthManager
                    .signThumbToken(snapshotName, 600L);
            String snapUrl = "/thumb/" + encSnap;
            if (thumbTok != null) snapUrl += "?t=" + thumbTok;
            data.put("snapshot", snapUrl);

            String enc = java.net.URLEncoder.encode(videoFile, "UTF-8");
            String url = "/events.html?filter=proximity&file=" + enc;

            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            "surveillance.proximity",
                            red
                                    ? com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL
                                    : com.overdrive.app.notifications.NotificationEvent.Severity.WARN,
                            red ? "Object very close" : "Object nearby",
                            red ? "Within 0.5 m" : "Within 0.8 m",
                            "proximity-" + triggerLevel,
                            url,
                            data));
        } catch (Throwable t) {
            logger.debug("publishProximityFinal failed: " + t.getMessage());
        }
    }
    
    /**
     * Extend current recording (new trigger while already recording).
     * Does not create a new file - just logs the extension.
     * The 2-minute segment handling is done by the encoder.
     * 
     * @param triggerLevel The new trigger level
     */
    public void extendRecording(String triggerLevel) {
        if (!isRecording) {
            logger.warn("Cannot extend - not recording");
            return;
        }
        
        logger.info("Extending proximity recording: new trigger=" + triggerLevel + 
                   " (previous=" + currentTriggerLevel + ")");
        
        // Update trigger level if higher priority
        if (triggerLevel.equals("RED") && !currentTriggerLevel.equals("RED")) {
            currentTriggerLevel = triggerLevel;
            logger.info("Upgraded trigger level to RED");
        }
    }
    
    /**
     * Check if currently recording.
     */
    public boolean isRecording() {
        return isRecording;
    }
    
    /**
     * Get current trigger level.
     */
    public String getCurrentTriggerLevel() {
        return currentTriggerLevel;
    }
    
    // ==================== PRIVATE METHODS ====================
    
    /**
     * Send Telegram notification for proximity alert.
     */
    private void sendTelegramNotification(String triggerLevel) {
        try {
            String language = LocaleManager.get();
            Locale locale = Locale.forLanguageTag(language);
            String datePattern = language.startsWith("pt")
                    ? "dd/MM/yyyy HH:mm:ss" : "yyyy-MM-dd HH:mm:ss";
            SimpleDateFormat sdf = new SimpleDateFormat(datePattern, locale);
            String timestamp = sdf.format(new Date());
            
            String distance = triggerLevel.equals("RED") ? "0-0.5m" : "0-0.8m";
            String triggerKey = "RED".equals(triggerLevel)
                    ? "proximity.trigger.red" : "proximity.trigger.yellow";
            String triggerLabel = TelegramMessages.get(triggerKey);
            String message = TelegramMessages.get("proximity_alert",
                    timestamp, triggerLabel, distance);
            
            TelegramNotifier.sendMessage(message);
            logger.info("Telegram notification sent: " + triggerLevel);
            
        } catch (Exception e) {
            logger.error("Failed to send Telegram notification: " + e.getMessage());
        }
    }
}
