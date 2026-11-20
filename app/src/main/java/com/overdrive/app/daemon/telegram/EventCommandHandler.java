package com.overdrive.app.daemon.telegram;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.server.LocaleManager;
import com.overdrive.app.storage.StorageManager;

import java.io.File;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Handles sentry event commands: /events, /download
 * 
 * Commands:
 * - /events [hours] - List events from last N hours (default 24)
 * - /download <filename> - Download a specific event video
 */
public class EventCommandHandler implements TelegramCommandHandler {
    
    private static final long MAX_VIDEO_SIZE_BYTES = 50 * 1024 * 1024; // 50MB Telegram limit
    
    // Fallback path if StorageManager is not available
    private static final String FALLBACK_SURVEILLANCE_DIR = "/storage/emulated/0/Overdrive/surveillance";
    
    /**
     * Get the event directory.
     * SOTA: Uses StorageManager to get the correct surveillance directory
     * based on storage type selection (internal or SD card).
     */
    private String getEventDir() {
        try {
            StorageManager storage = StorageManager.getInstance();
            String path = storage.getSurveillancePath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
        } catch (Exception e) {
            // StorageManager not available, use fallback
        }
        return FALLBACK_SURVEILLANCE_DIR;
    }
    
    @Override
    public boolean canHandle(String command) {
        return "/events".equals(command) || "/download".equals(command);
    }
    
    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String cmd = args[0].toLowerCase(Locale.ROOT);
        
        switch (cmd) {
            case "/events":
                int hours = 24;
                int page = 1;
                if (args.length > 1) {
                    try {
                        hours = Integer.parseInt(args[1]);
                        if (hours < 1) hours = 1;
                        if (hours > 168) hours = 168; // Max 7 days
                    } catch (NumberFormatException e) {
                        ctx.sendMessage(chatId, ctx.tr("events.invalid_hours"));
                        return;
                    }
                }
                if (args.length > 2) {
                    try {
                        page = Integer.parseInt(args[2]);
                        if (page < 1) page = 1;
                    } catch (NumberFormatException e) {
                        // Ignore invalid page, use default
                    }
                }
                handleEvents(chatId, hours, page, ctx);
                break;
                
            case "/download":
                if (args.length < 2) {
                    ctx.sendMessage(chatId, ctx.tr("events.download_usage"));
                    return;
                }
                handleDownload(chatId, args[1], ctx);
                break;
        }
    }
    
    private static final int EVENTS_PER_PAGE = 10;  // Reduced for button layout
    
    private void handleEvents(long chatId, int hours, int page, CommandContext ctx) {
        File eventDir = new File(getEventDir());
        
        if (!RemovableStorageFs.exists(eventDir) || !RemovableStorageFs.isDirectory(eventDir)) {
            ctx.sendMessage(chatId, ctx.tr("events.directory_missing"));
            return;
        }
        
        File[] files = RemovableStorageFs.listFiles(eventDir, (dir, name) -> 
            name.startsWith("event_") && name.endsWith(".mp4"));
        
        if (files == null || files.length == 0) {
            ctx.sendMessage(chatId, ctx.tr("events.none_found"));
            return;
        }
        
        // Filter by time and sort newest first
        long cutoffTime = System.currentTimeMillis() - (hours * 60 * 60 * 1000L);
        List<File> recentFiles = new ArrayList<>();
        
        for (File f : files) {
            if (RemovableStorageFs.lastModified(f) >= cutoffTime) {
                recentFiles.add(f);
            }
        }
        
        if (recentFiles.isEmpty()) {
            ctx.sendMessage(chatId, ctx.tr("events.none_recent", formatInteger(hours)));
            return;
        }
        
        // Sort by modification time (newest first)
        recentFiles.sort((a, b) -> Long.compare(RemovableStorageFs.lastModified(b), RemovableStorageFs.lastModified(a)));
        
        // Pagination
        int totalEvents = recentFiles.size();
        int totalPages = (totalEvents + EVENTS_PER_PAGE - 1) / EVENTS_PER_PAGE;
        if (page > totalPages) page = totalPages;
        
        int startIdx = (page - 1) * EVENTS_PER_PAGE;
        int endIdx = Math.min(startIdx + EVENTS_PER_PAGE, totalEvents);
        
        // Build header
        StringBuilder sb = new StringBuilder(totalPages > 1
                ? ctx.tr("events.header_paged",
                        formatInteger(hours), formatInteger(page), formatInteger(totalPages))
                : ctx.tr("events.header", formatInteger(hours)));
        sb.append("\n\n");
        
        // Build buttons - one row per event with download button
        List<String[][]> buttonRows = new ArrayList<>();
        Locale locale = displayLocale();
        SimpleDateFormat sdf = new SimpleDateFormat(eventListDatePattern(locale), locale);
        
        for (int i = startIdx; i < endIdx; i++) {
            File f = recentFiles.get(i);
            String dateStr = sdf.format(new Date(RemovableStorageFs.lastModified(f)));
            String sizeStr = formatFileSize(RemovableStorageFs.length(f), ctx);
            
            // Status indicator
            String status;
            if (isFileStillWriting(f)) {
                status = "⏺️";
            } else if (RemovableStorageFs.length(f) > MAX_VIDEO_SIZE_BYTES) {
                status = "⚠️";
            } else {
                status = "📥";
            }
            
            // Button text: "📥 Jan 13 14:30 (7.1MB)"
            String buttonText = ctx.tr("events.download_button", status, dateStr, sizeStr);
            String callbackData = "dl:" + f.getName();
            
            buttonRows.add(new String[][]{{buttonText, callbackData}});
        }
        
        // Add pagination buttons if needed
        if (totalPages > 1) {
            List<String[]> navButtons = new ArrayList<>();
            if (page > 1) {
                navButtons.add(new String[]{ctx.tr("events.previous_button"), "ev:" + hours + ":" + (page - 1)});
            }
            if (page < totalPages) {
                navButtons.add(new String[]{ctx.tr("events.next_button"), "ev:" + hours + ":" + (page + 1)});
            }
            if (!navButtons.isEmpty()) {
                buttonRows.add(navButtons.toArray(new String[0][]));
            }
        }
        
        sb.append(ctx.tr("events.legend"));
        
        // Convert to array
        String[][][] buttons = buttonRows.toArray(new String[0][][]);
        
        ctx.sendMessageWithButtons(chatId, sb.toString(), buttons);
    }
    
    private void handleDownload(long chatId, String filename, CommandContext ctx) {
        // Sanitize filename to prevent path traversal
        filename = new File(filename).getName();
        
        // Ensure it's an event file
        if (!filename.startsWith("event_") || !filename.endsWith(".mp4")) {
            ctx.sendMessage(chatId, ctx.tr("events.invalid_filename"));
            return;
        }
        
        File videoFile = new File(getEventDir(), filename);
        
        if (!RemovableStorageFs.exists(videoFile)) {
            ctx.sendMessage(chatId, ctx.tr("events.file_not_found", filename));
            return;
        }
        
        // Check if file is still being written (recording in progress)
        if (isFileStillWriting(videoFile)) {
            ctx.sendMessage(chatId, ctx.tr(
                    "events.recording_in_progress",
                    filename,
                    formatFileSize(RemovableStorageFs.length(videoFile), ctx)));
            return;
        }
        
        long fileSize = RemovableStorageFs.length(videoFile);
        
        // Check file size
        if (fileSize > MAX_VIDEO_SIZE_BYTES) {
            String sizeStr = formatFileSize(fileSize, ctx);
            ctx.sendMessage(chatId, ctx.tr("events.file_too_large", filename, sizeStr));
            return;
        }
        
        // Send uploading message
        ctx.sendMessage(chatId, ctx.tr("events.uploading", filename, formatFileSize(fileSize, ctx)));
        
        // Extract timestamp from filename for caption
        String caption = ctx.tr("events.video_caption", extractEventInfo(filename));
        
        boolean success = ctx.sendVideo(chatId, videoFile.getAbsolutePath(), caption);
        
        if (!success) {
            ctx.sendMessage(chatId, ctx.tr("events.upload_failed"));
        }
    }
    
    /**
     * Check if a file is still being written (recording in progress).
     * Uses two methods:
     * 1. File modification time is within last 3 seconds
     * 2. File size is changing (compare over 500ms interval)
     */
    private boolean isFileStillWriting(File file) {
        // Method 1: Check if file was modified very recently (within 3 seconds)
        long lastModified = RemovableStorageFs.lastModified(file);
        long now = System.currentTimeMillis();
        if (now - lastModified < 3000) {
            return true;
        }
        
        // Method 2: Check if file size is changing
        long size1 = RemovableStorageFs.length(file);
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}
        long size2 = RemovableStorageFs.length(file);
        
        return size2 != size1;
    }
    
    /**
     * Extract human-readable info from event filename.
     * event_20260113_143022.mp4 -> "Jan 13, 14:30:22"
     */
    private String extractEventInfo(String filename) {
        try {
            // event_YYYYMMDD_HHmmss.mp4
            String timestamp = filename.replace("event_", "").replace(".mp4", "");
            String[] parts = timestamp.split("_");
            if (parts.length == 2) {
                String date = parts[0]; // YYYYMMDD
                String time = parts[1]; // HHmmss
                
                int year = Integer.parseInt(date.substring(0, 4));
                int month = Integer.parseInt(date.substring(4, 6));
                int day = Integer.parseInt(date.substring(6, 8));
                int hour = Integer.parseInt(time.substring(0, 2));
                int min = Integer.parseInt(time.substring(2, 4));
                int sec = Integer.parseInt(time.substring(4, 6));
                
                Locale locale = displayLocale();
                Calendar cal = Calendar.getInstance(locale);
                cal.set(year, month - 1, day, hour, min, sec);
                
                SimpleDateFormat sdf = new SimpleDateFormat(eventCaptionDatePattern(locale), locale);
                return sdf.format(cal.getTime());
            }
        } catch (Exception e) {
            // Fall back to filename
        }
        return filename;
    }
    
    private String formatFileSize(long bytes, CommandContext ctx) {
        Locale locale = displayLocale();
        if (bytes < 1024) {
            return ctx.tr("events.size_bytes", NumberFormat.getIntegerInstance(locale).format(bytes));
        }

        NumberFormat decimal = NumberFormat.getNumberInstance(locale);
        decimal.setGroupingUsed(false);
        decimal.setMinimumFractionDigits(1);
        decimal.setMaximumFractionDigits(1);
        if (bytes < 1024 * 1024) {
            return ctx.tr("events.size_kb", decimal.format(bytes / 1024.0));
        }
        return ctx.tr("events.size_mb", decimal.format(bytes / (1024.0 * 1024.0)));
    }

    private static String formatInteger(long value) {
        return NumberFormat.getIntegerInstance(displayLocale()).format(value);
    }

    private static Locale displayLocale() {
        try {
            String tag = LocaleManager.get();
            if (tag != null && !tag.trim().isEmpty()) {
                if ("en".equalsIgnoreCase(tag)) return Locale.ENGLISH;
                Locale locale = Locale.forLanguageTag(tag);
                if (!locale.getLanguage().isEmpty()) return locale;
            }
        } catch (Exception ignored) {}
        return Locale.ENGLISH;
    }

    private static String eventListDatePattern(Locale locale) {
        return "pt".equals(locale.getLanguage()) ? "dd MMM HH:mm" : "MMM dd HH:mm";
    }

    private static String eventCaptionDatePattern(Locale locale) {
        return "pt".equals(locale.getLanguage()) ? "dd MMM, HH:mm:ss" : "MMM dd, HH:mm:ss";
    }
}
