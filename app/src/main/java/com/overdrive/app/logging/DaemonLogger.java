package com.overdrive.app.logging;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified daemon logger for all daemon processes.
 * 
 * Features:
 * - Thread-safe file logging
 * - Automatic log rotation by size
 * - Configurable retention and cleanup
 * - Per-daemon log files
 * - Dual logging (console + file)
 * - Works in both app context and daemon context (app_process)
 * 
 * Usage:
 * ```java
 * // Get logger for a specific daemon
 * DaemonLogger logger = DaemonLogger.getInstance("CameraDaemon");
 * logger.info("Camera started");
 * logger.error("Failed to start", exception);
 * 
 * // Or use static methods with tag
 * DaemonLogger.log("CameraDaemon", "Camera started");
 * ```
 */
public class DaemonLogger {
    
    // ==================== CONFIGURATION ====================
    
    /**
     * Log configuration.
     */
    public static class Config {
        public String logDir = "/data/local/tmp";
        public int retentionHours = 24;
        public int maxFileSizeMB = 10;
        public int rotationCount = 3;
        public boolean enableConsoleLog = true;
        public boolean enableFileLog = true;
        public boolean enableStdoutLog = false;  // Only enable for daemon processes (app_process)
        
        public static Config defaults() {
            return new Config();
        }
        
        public Config withLogDir(String dir) {
            this.logDir = dir;
            return this;
        }
        
        public Config withMaxFileSizeMB(int size) {
            this.maxFileSizeMB = size;
            return this;
        }
        
        public Config withRotationCount(int count) {
            this.rotationCount = count;
            return this;
        }
        
        public Config withConsoleLog(boolean enable) {
            this.enableConsoleLog = enable;
            return this;
        }
        
        public Config withFileLog(boolean enable) {
            this.enableFileLog = enable;
            return this;
        }
        
        public Config withStdoutLog(boolean enable) {
            this.enableStdoutLog = enable;
            return this;
        }
    }
    
    /**
     * Log levels.
     */
    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }
    
    // ==================== STATIC MEMBERS ====================
    
    private static final String META_TAG = "DaemonLogger";
    private static Config globalConfig = Config.defaults();
    private static final ConcurrentHashMap<String, DaemonLogger> instances = new ConcurrentHashMap<>();
    private static final Object globalLock = new Object();
    
    // ==================== INSTANCE MEMBERS ====================
    
    private final String tag;
    private final String logFilePath;
    private PrintWriter writer;
    private final Object writeLock = new Object();
    private final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private long currentFileSize = 0;
    private volatile boolean writerInitialized = false;
    
    // ==================== CONSTRUCTORS ====================
    
    private DaemonLogger(String tag) {
        this.tag = tag;
        this.logFilePath = globalConfig.logDir + "/" + tag.toLowerCase() + ".log";
        // Lazy init - don't create file until first write
    }
    
    private DaemonLogger(String tag, String logDir) {
        this.tag = tag;
        this.logFilePath = logDir + "/" + tag.toLowerCase() + ".log";
        // Lazy init - don't create file until first write
    }
    
    // ==================== STATIC FACTORY METHODS ====================
    
    /**
     * Get or create a logger instance for the given tag.
     */
    public static DaemonLogger getInstance(String tag) {
        return instances.computeIfAbsent(tag, DaemonLogger::new);
    }
    
    /**
     * Get a logger with custom log directory.
     */
    public static DaemonLogger getInstance(String tag, String logDir) {
        String key = tag + "@" + logDir;
        return instances.computeIfAbsent(key, k -> new DaemonLogger(tag, logDir));
    }
    
    /**
     * Configure global settings (call before getting instances).
     */
    public static void configure(Config config) {
        synchronized (globalLock) {
            globalConfig = config;
        }
    }
    
    /**
     * Get current global configuration.
     */
    public static Config getConfig() {
        return globalConfig;
    }

    
    // ==================== STATIC LOGGING METHODS ====================
    
    /**
     * Static log method with tag (convenience for quick logging).
     */
    public static void log(String tag, String message) {
        getInstance(tag).info(message);
    }
    
    /**
     * Static log method with tag and level.
     */
    public static void log(String tag, String message, Level level) {
        getInstance(tag).log(level, message);
    }
    
    /**
     * Static error log with exception.
     */
    public static void logError(String tag, String message, Throwable throwable) {
        getInstance(tag).error(message, throwable);
    }
    
    // ==================== INSTANCE LOGGING METHODS ====================
    
    /**
     * Log a message with specified level.
     */
    public void log(Level level, String message) {
        String timestamp = timestampFormat.format(new Date());
        String logLine = "[" + timestamp + "] [" + level.name() + "] [" + tag + "] " + message;
        
        // Console log if enabled (standard Android Log for app context)
        if (globalConfig.enableConsoleLog) {
            switch (level) {
                case DEBUG:
                    Log.d(tag, message);
                    break;
                case INFO:
                    Log.i(tag, message);
                    break;
                case WARN:
                    Log.w(tag, message);
                    break;
                case ERROR:
                    Log.e(tag, message);
                    break;
            }
        }
        
        // Print to stdout for daemon processes running via app_process.
        // Include timestamp so the shell wrapper's log file has timestamps
        // on every line, not just the wrapper's own echo statements.
        if (globalConfig.enableStdoutLog) {
            System.out.println(tag + ": [" + timestamp + "] " + message);
        }
        
        // File log if enabled globally AND for this specific tag
        if (globalConfig.enableFileLog && DaemonLogConfig.isFileLoggingEnabled(tag)) {
            writeToFile(logLine);
        }
    }
    
    /**
     * Log a debug message.
     */
    public void debug(String message) {
        log(Level.DEBUG, message);
    }
    
    /**
     * Log an info message.
     */
    public void info(String message) {
        log(Level.INFO, message);
    }
    
    /**
     * Log a warning message.
     */
    public void warn(String message) {
        log(Level.WARN, message);
    }
    
    /**
     * Log an error message.
     */
    public void error(String message) {
        log(Level.ERROR, message);
    }
    
    /**
     * Log an error message with exception.
     */
    public void error(String message, Throwable throwable) {
        String fullMessage = message;
        if (throwable != null) {
            fullMessage = message + ": " + throwable.getMessage();
        }
        log(Level.ERROR, fullMessage);
        
        // Also log stack trace to file
        if (throwable != null && globalConfig.enableFileLog && DaemonLogConfig.isFileLoggingEnabled(tag)) {
            synchronized (writeLock) {
                if (writer != null) {
                    throwable.printStackTrace(writer);
                    writer.flush();
                }
            }
        }
    }
    
    // ==================== FILE OPERATIONS ====================
    
    private void initWriter() {
        if (!globalConfig.enableFileLog) return;
        if (!DaemonLogConfig.isFileLoggingEnabled(tag)) return;
        if (writerInitialized) return;
        
        try {
            File logFile = new File(logFilePath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            // Check if rotation needed before opening
            if (logFile.exists()) {
                currentFileSize = logFile.length();
                checkAndRotateIfNeeded();
            }
            
            writer = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(logFile, true), "UTF-8"), true);
            writerInitialized = true;
                
        } catch (Exception e) {
            // Silently skip file logging if directory is not writable (e.g. app process can't write to /data/local/tmp/)
            // Falls back to logcat only — this is expected when AuthManager is called from app UI
        }
    }
    
    private void writeToFile(String logLine) {
        synchronized (writeLock) {
            if (writer == null) {
                initWriter();
            }
            
            if (writer != null) {
                try {
                    writer.println(logLine);
                    writer.flush();
                    
                    // Track file size for rotation
                    currentFileSize += logLine.length() + 1;
                    checkAndRotateIfNeeded();
                    
                } catch (Exception e) {
                    Log.e(META_TAG, "Failed to write log: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Check if log file needs rotation and rotate if necessary.
     */
    private void checkAndRotateIfNeeded() {
        long maxSizeBytes = globalConfig.maxFileSizeMB * 1024L * 1024L;
        if (currentFileSize >= maxSizeBytes) {
            rotateLogFile();
        }
    }
    
    /**
     * Rotate log file by renaming to .1, .2, .3, etc.
     * Oldest files beyond rotationCount are deleted.
     */
    private void rotateLogFile() {
        try {
            // Close existing writer
            if (writer != null) {
                writer.close();
                writer = null;
                writerInitialized = false;
            }
            
            File logFile = new File(logFilePath);
            File parentDir = logFile.getParentFile();
            String baseName = logFile.getName();
            
            // Shift existing rotated files
            for (int i = globalConfig.rotationCount; i >= 1; i--) {
                File oldFile = new File(parentDir, baseName + "." + i);
                if (i == globalConfig.rotationCount) {
                    // Delete oldest
                    if (oldFile.exists()) {
                        oldFile.delete();
                    }
                } else {
                    // Rename to next number
                    if (oldFile.exists()) {
                        File newFile = new File(parentDir, baseName + "." + (i + 1));
                        oldFile.renameTo(newFile);
                    }
                }
            }
            
            // Rename current log to .1
            if (logFile.exists()) {
                File rotatedFile = new File(parentDir, baseName + ".1");
                logFile.renameTo(rotatedFile);
            }
            
            // Reset file size counter
            currentFileSize = 0;
            
            // Reinitialize writer on next write (lazy)
            
            Log.i(META_TAG, "Rotated log file: " + baseName);
            
        } catch (Exception e) {
            Log.e(META_TAG, "Failed to rotate log file: " + e.getMessage());
        }
    }

    
    // ==================== CLEANUP OPERATIONS ====================
    
    /**
     * Cleanup old log files based on retention policy.
     * Call this periodically (e.g., on daemon startup or via scheduler).
     */
    public static CleanupStats cleanupOldLogs() {
        int filesDeleted = 0;
        long spaceFreeBytes = 0;
        
        try {
            File logDir = new File(globalConfig.logDir);
            if (!logDir.exists() || !logDir.isDirectory()) {
                return new CleanupStats(System.currentTimeMillis(), 0, 0);
            }
            
            long retentionMs = globalConfig.retentionHours * 60L * 60L * 1000L;
            long cutoffTime = System.currentTimeMillis() - retentionMs;
            
            File[] files = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.contains(".log."));
            if (files != null) {
                for (File file : files) {
                    if (file.lastModified() < cutoffTime) {
                        long fileSize = file.length();
                        if (file.delete()) {
                            filesDeleted++;
                            spaceFreeBytes += fileSize;
                            Log.i(META_TAG, "Deleted old log: " + file.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(META_TAG, "Cleanup error: " + e.getMessage());
        }
        
        return new CleanupStats(System.currentTimeMillis(), filesDeleted, spaceFreeBytes / 1024);
    }
    
    /**
     * Cleanup statistics.
     */
    public static class CleanupStats {
        public final long lastCleanupTime;
        public final int filesDeleted;
        public final long spaceFreeKB;
        
        public CleanupStats(long lastCleanupTime, int filesDeleted, long spaceFreeKB) {
            this.lastCleanupTime = lastCleanupTime;
            this.filesDeleted = filesDeleted;
            this.spaceFreeKB = spaceFreeKB;
        }
        
        @Override
        public String toString() {
            return "CleanupStats{deleted=" + filesDeleted + ", freedKB=" + spaceFreeKB + "}";
        }
    }
    
    // ==================== LIFECYCLE ====================
    
    /**
     * Close the logger and release resources.
     */
    public void close() {
        synchronized (writeLock) {
            if (writer != null) {
                writer.close();
                writer = null;
                writerInitialized = false;
            }
        }
        instances.remove(tag);
    }
    
    /**
     * Close all logger instances.
     */
    public static void closeAll() {
        synchronized (globalLock) {
            for (DaemonLogger logger : instances.values()) {
                synchronized (logger.writeLock) {
                    if (logger.writer != null) {
                        logger.writer.close();
                        logger.writer = null;
                        logger.writerInitialized = false;
                    }
                }
            }
            instances.clear();
        }
        Log.i(META_TAG, "All loggers closed");
    }
    
    // ==================== GETTERS ====================
    
    /**
     * Get the log file path for this logger.
     */
    public String getLogFilePath() {
        return logFilePath;
    }
    
    /**
     * Get the tag for this logger.
     */
    public String getTag() {
        return tag;
    }
    
    /**
     * Get current file size in bytes.
     */
    public long getCurrentFileSize() {
        return currentFileSize;
    }
}
