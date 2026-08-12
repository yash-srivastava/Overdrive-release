package com.overdrive.app.storage;

import android.os.StatFs;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExternalStorageCleaner - Aggressive cleanup of external app recordings (BYD CDR/Dashcam)
 * 
 * SOTA: Ensures Overdrive always has reserved space on SD card by
 * cleaning up oldest files from BYD CDR (built-in dashcam) when needed.
 * 
 * Features:
 * - Auto-discovery of SD card and CDR recording directories
 * - Configurable reserved space (default 2GB)
 * - Oldest-first deletion strategy
 * - Protected files (last N hours) option
 * - Minimum file retention (always keep N newest files)
 * - Detailed logging of all deletions
 * - Periodic monitoring (every 60 seconds when active)
 * - Thread-safe operations
 */
public class ExternalStorageCleaner {
    private static final String TAG = "ExternalStorageCleaner";
    
    // Hybrid logger
    private static boolean useDaemonLogger = false;
    private static com.overdrive.app.logging.DaemonLogger daemonLogger = null;
    
    public static void enableDaemonLogging() {
        useDaemonLogger = true;
        daemonLogger = com.overdrive.app.logging.DaemonLogger.getInstance(TAG);
    }
    
    private static void logInfo(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.info(msg);
        } else {
            Log.i(TAG, msg);
        }
    }
    
    private static void logWarn(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.warn(msg);
        } else {
            Log.w(TAG, msg);
        }
    }
    
    private static void logError(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.error(msg);
        } else {
            Log.e(TAG, msg);
        }
    }
    
    private static void logDebug(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.debug(msg);
        } else {
            Log.d(TAG, msg);
        }
    }

    private static final int PROCESS_WAIT_FAILED = -1;
    private static final long GETPROP_TIMEOUT_MS = 1_000L;
    private static final long DELETE_TIMEOUT_MS = 4_000L;

    static int waitForBounded(Process process, long timeoutMs) {
        try {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
            process.destroyForcibly();
            try {
                process.waitFor(500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PROCESS_WAIT_FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { process.destroyForcibly(); } catch (Throwable ignored) {}
            return PROCESS_WAIT_FAILED;
        }
    }

    // ==================== Constants ====================
    
    // Known BYD CDR (dashcam) recording directories
    private static final String[] CDR_SUBDIRS = {
        "Recorder/Normal",    // BYD built-in dashcam - normal recordings
        "Recorder",           // BYD built-in dashcam - root
        "Recorder/Video",
        "DCIM",
        "DCIM/Camera",
        "DCIM/100MEDIA",
        "DVR",
        "CDR",
        "行车记录仪",
        "Video",
        "Video/DVR",
        "Movies",
        "Movies/DVR",
        "Record",
        "CarRecord",
        "DashCam",
        "Camera"
    };
    
    // Video file extensions to clean
    private static final String[] VIDEO_EXTENSIONS = {
        ".mp4", ".MP4", ".avi", ".AVI", ".ts", ".TS", ".mov", ".MOV"
    };
    
    // Config file location (shared with StorageManager)
    private static final String CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    
    // Default configuration
    private static final long DEFAULT_RESERVED_SPACE_MB = 2048;  // 2GB
    private static final int DEFAULT_PROTECTED_HOURS = 24;       // 24 hours
    private static final int DEFAULT_MIN_FILES_KEEP = 10;        // Always keep 10 newest
    private static final long MONITOR_INTERVAL_SECONDS = 60;     // Check every minute
    
    // ==================== State ====================
    
    private boolean enabled = false;
    private long reservedSpaceMb = DEFAULT_RESERVED_SPACE_MB;
    private int protectedHours = DEFAULT_PROTECTED_HOURS;
    private int minFilesKeep = DEFAULT_MIN_FILES_KEEP;
    
    private String sdCardPath = null;
    private String cdrPath = null;
    private boolean sdCardAvailable = false;
    
    // Background monitor
    private ScheduledExecutorService monitorScheduler;
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);
    private final Object cleanupLock = new Object();
    
    // Statistics
    private long totalBytesFreed = 0;
    private int totalFilesDeleted = 0;
    private long lastCleanupTime = 0;
    
    // Singleton
    private static ExternalStorageCleaner instance;
    
    private ExternalStorageCleaner() {
        discoverPaths();
        loadConfig();

        // Restore monitoring across daemon restarts. Without this, the user has
        // to re-toggle the UI switch after every reboot to resume background
        // OEM-dashcam cleanup, even though `enabled=true` is persisted to disk.
        //
        // Gate ONLY on `enabled`, not sdCardAvailable. On the BYD head-unit vold
        // mounts the SD seconds AFTER the daemon starts (same boot-race as the
        // ~70s SD-unmount window), so the ctor snapshot routinely sees
        // sdCardAvailable=false. The old `&& sdCardAvailable` guard then left the
        // monitor un-armed for the entire power cycle — refresh() (the only
        // re-discover+re-arm path) is invoked only when the user opens the
        // external-storage web page. The monitor tick now self-discovers (re-runs
        // discoverPaths() while sdCardPath==null), so arming it before the SD is
        // mounted is safe: it idles until vold mounts the card, then converges.
        if (enabled) {
            startMonitoring();
        }
    }
    
    public static synchronized ExternalStorageCleaner getInstance() {
        if (instance == null) {
            instance = new ExternalStorageCleaner();
        }
        return instance;
    }
    
    // ==================== Path Discovery ====================
    
    /**
     * Discover SD card path and CDR recording directory.
     * SOTA: Uses sm list-volumes to find mounted SD cards.
     */
    public void discoverPaths() {
        sdCardPath = null;
        cdrPath = null;
        sdCardAvailable = false;

        // SOTA: Delegate SD discovery to StorageManager so we share the
        // type-aware classifier (mmcblk* = SD, sd* = USB) and never latch
        // onto a USB stick. The CDR/dashcam files are inherently SD-bound,
        // so a USB drive must NOT be considered here even if it's mounted.
        try {
            com.overdrive.app.storage.StorageManager sm =
                com.overdrive.app.storage.StorageManager.getInstance();
            if (sm.isSdCardAvailable()) {
                sdCardPath = sm.getSdCardPath();
                logInfo("Using SD card from StorageManager: " + sdCardPath);
            }
        } catch (Exception e) {
            logDebug("StorageManager lookup failed: " + e.getMessage());
        }

        // Fallback to BYD prop directly (SD-specific).
        if (sdCardPath == null) {
            String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
            String sdExists = getSystemProperty("sys.byd.isSDExist");
            if ("true".equalsIgnoreCase(sdExists) && !sdUuid.isEmpty()) {
                String uuidPath = "/storage/" + sdUuid;
                File uuidDir = new File(uuidPath);
                if (uuidDir.exists() && uuidDir.isDirectory()) {
                    sdCardPath = uuidPath;
                    logInfo("Found SD card via UUID: " + sdCardPath);
                }
            }
        }

        if (sdCardPath == null) {
            logWarn("No SD card found");
            return;
        }
        
        sdCardAvailable = true;
        
        // Find CDR directory
        for (String subdir : CDR_SUBDIRS) {
            String fullPath = sdCardPath + "/" + subdir;
            File cdrDir = new File(fullPath);
            if (cdrDir.exists() && cdrDir.isDirectory() && containsVideoFiles(cdrDir)) {
                cdrPath = fullPath;
                logInfo("Found CDR directory: " + cdrPath);
                break;
            }
        }
        
        if (cdrPath == null) {
            logInfo("No CDR directory found on SD card");
            // Try to find any directory with video files
            cdrPath = findLargestVideoDirectory(new File(sdCardPath));
            if (cdrPath != null) {
                logInfo("Found video directory via scan: " + cdrPath);
            }
        }
    }
    
    /**
     * Scan SD card root to find the directory with the most video files.
     * Fallback when known CDR paths don't exist.
     */
    private String findLargestVideoDirectory(File root) {
        if (root == null || !root.exists()) return null;
        
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return null;
        
        String bestPath = null;
        int maxFiles = 0;
        
        for (File dir : dirs) {
            // Skip Android system directories
            String name = dir.getName();
            if (name.equals("Android") || name.equals(".") || name.equals("..") || 
                name.startsWith(".") || name.equals("Overdrive")) {
                continue;
            }
            
            int videoCount = countVideoFiles(dir);
            if (videoCount > maxFiles) {
                maxFiles = videoCount;
                bestPath = dir.getAbsolutePath();
            }
        }
        
        // Only return if we found at least 5 video files
        return maxFiles >= 5 ? bestPath : null;
    }
    
    /**
     * Count video files in a directory (non-recursive, just top level).
     */
    private int countVideoFiles(File dir) {
        if (dir == null || !dir.exists()) return 0;
        
        File[] files = dir.listFiles(file -> {
            if (!file.isFile()) return false;
            return isVideoFile(file);
        });
        
        return files != null ? files.length : 0;
    }
    
    /**
     * Get Android system property via reflection or shell.
     */
    private String getSystemProperty(String key) {
        try {
            // Try reflection first
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Exception e) {
            // Fall back to shell
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
                int exitCode = waitForBounded(p, GETPROP_TIMEOUT_MS);
                if (exitCode != 0) {
                    if (exitCode == PROCESS_WAIT_FAILED) {
                        logWarn("getprop " + key + " timed out or was interrupted");
                    }
                    return "";
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line = reader.readLine();
                    return line != null ? line.trim() : "";
                }
            } catch (Exception e2) {
                return "";
            }
        }
    }
    
    /**
     * Check if directory contains video files.
     */
    private boolean containsVideoFiles(File dir) {
        if (dir == null || !dir.exists()) return false;
        
        File[] files = dir.listFiles(file -> {
            if (!file.isFile()) return false;
            String name = file.getName().toLowerCase();
            for (String ext : VIDEO_EXTENSIONS) {
                if (name.endsWith(ext.toLowerCase())) return true;
            }
            return false;
        });
        
        return files != null && files.length > 0;
    }

    // ==================== Configuration ====================
    
    /**
     * Load configuration from config file.
     */
    public void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (!configFile.exists()) return;
            
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            JSONObject config = new JSONObject(sb.toString());
            JSONObject extCleanup = config.optJSONObject("externalCleanup");
            
            if (extCleanup != null) {
                enabled = extCleanup.optBoolean("enabled", false);
                reservedSpaceMb = extCleanup.optLong("reservedSpaceMb", DEFAULT_RESERVED_SPACE_MB);
                protectedHours = extCleanup.optInt("protectedHours", DEFAULT_PROTECTED_HOURS);
                minFilesKeep = extCleanup.optInt("minFilesKeep", DEFAULT_MIN_FILES_KEEP);
                
                // Override discovered CDR path if configured
                String configuredCdrPath = extCleanup.optString("cdrPath", "");
                if (!configuredCdrPath.isEmpty()) {
                    File cdrDir = new File(configuredCdrPath);
                    if (cdrDir.exists() && cdrDir.isDirectory()) {
                        cdrPath = configuredCdrPath;
                    }
                }
                
                logInfo("Loaded external cleanup config: enabled=" + enabled + 
                    ", reserved=" + reservedSpaceMb + "MB, protected=" + protectedHours + "h");
            }
        } catch (Exception e) {
            logWarn("Could not load external cleanup config: " + e.getMessage());
        }
    }
    
    /**
     * Save configuration to config file.
     *
     * <p>Routes the {@code externalCleanup} section persist through
     * UnifiedConfigManager so there is ONE lock domain + ONE atomic writer over
     * /data/local/tmp/overdrive_config.json. The previous body did its own
     * read-modify-write (FileReader read + bare truncating FileWriter) guarded
     * only by {@code synchronized(this)} — a disjoint mutex from UCM's
     * cross-process FileLock + tmp+rename. The two schemes did not exclude each
     * other, so a concurrent UCM updateSection on the same file (e.g.
     * StorageManager writing the {@code storage} section's limits) could
     * lost-update either side, torn-read a half-truncated file, or — worst — a
     * SIGKILL between the truncate and flush could wipe the ENTIRE config.
     * updateSection does loadConfigFresh + FileLock + atomic tmp+rename + .bak
     * self-heal and refreshes the cache, so the old explicit forceReload() is
     * now redundant. updateSection merges into the existing section, so omitting
     * {@code cdrPath} when null preserves a previously-persisted value.
     * Stay {@code synchronized(this)} so the in-memory fields are read
     * consistently while assembling the JSON.
     */
    public synchronized void saveConfig() {
        try {
            JSONObject extCleanup = new JSONObject();
            extCleanup.put("enabled", enabled);
            extCleanup.put("reservedSpaceMb", reservedSpaceMb);
            extCleanup.put("protectedHours", protectedHours);
            extCleanup.put("minFilesKeep", minFilesKeep);
            if (cdrPath != null) {
                extCleanup.put("cdrPath", cdrPath);
            }

            boolean ok = com.overdrive.app.config.UnifiedConfigManager
                    .updateSection("externalCleanup", extCleanup);
            if (!ok) {
                logError("Could not save external cleanup config: "
                    + "UnifiedConfigManager.updateSection(\"externalCleanup\") failed");
                return;
            }

            logInfo("Saved external cleanup config");
        } catch (Exception e) {
            logError("Could not save external cleanup config: " + e.getMessage());
        }
    }
    
    // ==================== Getters/Setters ====================
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) {
        setEnabledNoSave(enabled);
        saveConfig();
        applyMonitoringState();
    }

    /** Set the enabled flag WITHOUT persisting or touching monitoring; the
     *  caller is responsible for {@link #saveConfig()} and
     *  {@link #applyMonitoringState()} (used to batch a multi-field POST into
     *  one persist + one monitoring transition). */
    public void setEnabledNoSave(boolean enabled) {
        this.enabled = enabled;
    }

    /** Start/stop the monitor to match the current {@link #enabled} flag.
     *  Decoupled from the persist so a batched config update can save once and
     *  apply the monitoring side-effect once. */
    public void applyMonitoringState() {
        if (enabled) {
            startMonitoring();
        } else {
            stopMonitoring();
        }
    }

    public long getReservedSpaceMb() { return reservedSpaceMb; }
    public void setReservedSpaceMb(long mb) {
        setReservedSpaceMbNoSave(mb);
        saveConfig();
    }
    public void setReservedSpaceMbNoSave(long mb) {
        this.reservedSpaceMb = Math.max(100, Math.min(20000, mb));
    }

    public int getProtectedHours() { return protectedHours; }
    public void setProtectedHours(int hours) {
        setProtectedHoursNoSave(hours);
        saveConfig();
    }
    public void setProtectedHoursNoSave(int hours) {
        this.protectedHours = Math.max(0, Math.min(168, hours)); // 0-7 days
    }

    public int getMinFilesKeep() { return minFilesKeep; }
    public void setMinFilesKeep(int count) {
        setMinFilesKeepNoSave(count);
        saveConfig();
    }
    public void setMinFilesKeepNoSave(int count) {
        this.minFilesKeep = Math.max(0, Math.min(100, count));
    }
    
    public boolean isSdCardAvailable() { return sdCardAvailable; }
    public String getSdCardPath() { return sdCardPath; }
    public String getCdrPath() { return cdrPath; }
    
    public long getTotalBytesFreed() { return totalBytesFreed; }
    public int getTotalFilesDeleted() { return totalFilesDeleted; }
    public long getLastCleanupTime() { return lastCleanupTime; }

    // ==================== Storage Stats ====================
    
    /**
     * Get available space on SD card in bytes.
     */
    public long getSdCardFreeSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get SD card free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on SD card in bytes.
     */
    public long getSdCardTotalSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    // ===================== Walk-result cache =====================
    //
    // /api/storage/external used to fire FOUR independent recursive walks of
    // the BYD CDR (dashcam) tree on every call: getCdrUsage, getCdrFileCount,
    // getProtectedSize, getDeletableSize. On a populated SD with tens of GB of
    // dashcam clips that's seconds of FUSE binder traffic per call. The web
    // UI polls every ~3 s and fires this endpoint multiple times after each
    // settings save, saturating the SD's mount and starving the StorageManager
    // watchdog probe (which falsely declares the card unmounted).
    //
    // We collapse the four accessors into a single recursive walk per
    // WALK_TTL_MS window. Cleanup paths must invalidate the cache (set
    // walkAt = 0) so freshly-deleted files don't show up as "still there".
    private static final long WALK_TTL_MS = 10_000L;
    private volatile long walkAt = 0L;
    private volatile long cachedUsage = 0L;
    private volatile int  cachedCount = 0;
    private volatile long cachedProtected = 0L;
    private volatile long cachedDeletable = 0L;

    /** Refresh the cached snapshot if older than the TTL. Synchronized so a
     * burst of /api/storage/external calls collapses to a single walk; the
     * second caller observes the cache populated by the first. */
    private synchronized void refreshWalkIfStale() {
        if (cdrPath == null) {
            cachedUsage = 0; cachedCount = 0;
            cachedProtected = 0; cachedDeletable = 0;
            walkAt = System.currentTimeMillis();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - walkAt < WALK_TTL_MS) return;

        List<File> files = getCdrVideoFiles();          // ONE walk
        long protCutoff = now - (protectedHours * 3600L * 1000L);

        long usage = 0, prot = 0, del = 0;
        int n = files.size();
        // Sort newest-first for the deletable computation.
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < n; i++) {
            File f = files.get(i);
            long sz = f.length();
            long m = f.lastModified();
            usage += sz;
            if (m > protCutoff) {
                prot += sz;
            } else if (i >= minFilesKeep) {
                del += sz;
            }
        }
        cachedUsage = usage;
        cachedCount = n;
        cachedProtected = prot;
        cachedDeletable = del;
        walkAt = now;
    }

    /** Force the next get* call to re-walk. Called from cleanup paths after
     * file deletions so the cached numbers don't lie. */
    void invalidateWalkCache() {
        walkAt = 0;
    }

    /** Get total size of CDR recordings in bytes (cached, ≤ {@value #WALK_TTL_MS} ms stale). */
    public long getCdrUsage() {
        if (cdrPath == null) return 0;
        refreshWalkIfStale();
        return cachedUsage;
    }

    /** Get count of CDR video files (cached). */
    public int getCdrFileCount() {
        if (cdrPath == null) return 0;
        refreshWalkIfStale();
        return cachedCount;
    }

    /** Get size of protected files (within protection window). Cached. */
    public long getProtectedSize() {
        if (cdrPath == null) return 0;
        refreshWalkIfStale();
        return cachedProtected;
    }

    /** Get size of deletable files (outside protection window, excluding min keep). Cached. */
    public long getDeletableSize() {
        if (cdrPath == null) return 0;
        refreshWalkIfStale();
        return cachedDeletable;
    }
    
    private long getDirectorySize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getDirectorySize(file);
                }
            }
        }
        return size;
    }
    
    /**
     * Get all CDR video files, including subdirectories.
     */
    private List<File> getCdrVideoFiles() {
        List<File> videoFiles = new ArrayList<>();
        if (cdrPath == null) return videoFiles;
        
        collectVideoFiles(new File(cdrPath), videoFiles);
        return videoFiles;
    }
    
    private void collectVideoFiles(File dir, List<File> result) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles();
        if (files == null) {
            // Try shell fallback for permission issues
            files = listFilesViaShell(dir);
        }
        
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    collectVideoFiles(file, result);
                } else if (isVideoFile(file)) {
                    result.add(file);
                }
            }
        }
    }
    
    private boolean isVideoFile(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : VIDEO_EXTENSIONS) {
            if (name.endsWith(ext.toLowerCase())) return true;
        }
        return false;
    }
    
    /**
     * Shell {@code ls} fallback for FUSE-mounted dirs where
     * {@code File.listFiles()} returns null under the daemon UID.
     *
     * <p>Hard-bounded, deliberately. This used to be a bare {@code readLine()}
     * loop plus an unbounded {@code p.waitFor()} — and it is called from inside
     * {@link #refreshWalkIfStale()}, which holds the instance monitor and runs
     * on the HTTP request thread serving /api/storage/external. An `ls` that
     * hangs mid-write on a sick SD card (a real fault on these head-units)
     * therefore wedged that request thread AND every concurrent
     * saveConfig()/cleanup caller behind the monitor, permanently. Mirrors the
     * bounded drain in StorageManager.listFilesViaShell: read on a daemon
     * thread with a deadline, destroyForcibly() on timeout, and return whatever
     * was drained. Under-counting self-corrects on the next walk — and since
     * callers only ever REPORT these numbers (deletion decisions re-walk), a
     * short list can't cause a wrong deletion.
     */
    private File[] listFilesViaShell(File dir) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"ls", "-1", dir.getAbsolutePath()});
            final Process proc = p;
            final List<File> files = java.util.Collections.synchronizedList(new ArrayList<File>());

            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String name = line.trim();
                        if (!name.isEmpty()) files.add(new File(dir, name));
                    }
                } catch (Exception ignored) {
                    // Stream closed by destroyForcibly on timeout, or read error
                    // — return whatever we have.
                }
            }, "cdrListViaShell-drain");
            drain.setDaemon(true);
            drain.start();
            drain.join(4_000);
            if (drain.isAlive()) {
                p.destroyForcibly();
                // Let the kernel close the stream so the drain thread stops
                // touching `files` before we snapshot it.
                drain.join(500);
            }

            synchronized (files) {
                return files.toArray(new File[0]);
            }
        } catch (Exception e) {
            return new File[0];
        } finally {
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
    }

    // ==================== Cleanup Logic ====================
    
    /**
     * Result of a cleanup operation.
     */
    public static class CleanupResult {
        public final long bytesFreed;
        public final int filesDeleted;
        public final List<String> deletedFiles;
        public final String error;
        
        public CleanupResult(long bytesFreed, int filesDeleted, List<String> deletedFiles) {
            this.bytesFreed = bytesFreed;
            this.filesDeleted = filesDeleted;
            this.deletedFiles = deletedFiles;
            this.error = null;
        }
        
        public CleanupResult(String error) {
            this.bytesFreed = 0;
            this.filesDeleted = 0;
            this.deletedFiles = new ArrayList<>();
            this.error = error;
        }
        
        public boolean isSuccess() { return error == null; }
    }
    
    /**
     * Ensure reserved space is available on SD card.
     * Deletes oldest CDR files if needed.
     * 
     * @return CleanupResult with details of what was deleted
     */
    public CleanupResult ensureReservedSpace() {
        synchronized (cleanupLock) {
            if (!enabled) {
                return new CleanupResult("Cleanup not enabled");
            }
            
            if (sdCardPath == null || cdrPath == null) {
                discoverPaths();
                if (sdCardPath == null) {
                    return new CleanupResult("No SD card found");
                }
                if (cdrPath == null) {
                    return new CleanupResult("No CDR directory found");
                }
            }
            
            long freeSpace = getSdCardFreeSpace();
            long reservedBytes = reservedSpaceMb * 1024L * 1024L;
            
            if (freeSpace >= reservedBytes) {
                logDebug("SD card has sufficient space: " + formatSize(freeSpace) + 
                    " free, " + formatSize(reservedBytes) + " reserved");
                return new CleanupResult(0, 0, new ArrayList<>());
            }
            
            long toFree = reservedBytes - freeSpace;
            logInfo("Need to free " + formatSize(toFree) + " on SD card");
            
            return performCleanup(toFree);
        }
    }
    
    /**
     * Force cleanup to free specified amount of space.
     *
     * Gated on the {@code enabled} flag. This was previously documented as
     * "ignores the enabled flag" — that turned the manual-cleanup path into a
     * footgun where any caller (UI tap, HTTP POST, Telegram bot) could delete
     * OEM dashcam files even when the user had explicitly disabled the
     * feature in config. Honoring the flag here is the single source of
     * truth; callers should surface the rejection to the user.
     *
     * @param bytesToFree Minimum bytes to free
     * @return CleanupResult with details, or an error result if disabled
     */
    public CleanupResult forceCleanup(long bytesToFree) {
        synchronized (cleanupLock) {
            if (!enabled) {
                return new CleanupResult("External storage cleanup is disabled");
            }

            if (sdCardPath == null || cdrPath == null) {
                discoverPaths();
                if (cdrPath == null) {
                    return new CleanupResult("No CDR directory found");
                }
            }

            return performCleanup(bytesToFree);
        }
    }
    
    /**
     * Perform the actual cleanup operation.
     */
    private CleanupResult performCleanup(long bytesToFree) {
        List<File> files = getCdrVideoFiles();
        
        if (files.isEmpty()) {
            return new CleanupResult("No CDR files found");
        }
        
        // Sort oldest first
        files.sort(Comparator.comparingLong(File::lastModified));
        
        // Calculate protection cutoff
        long protectionCutoff = System.currentTimeMillis() - (protectedHours * 3600L * 1000L);
        
        // Determine how many files we must keep (newest N)
        int deletableCount = Math.max(0, files.size() - minFilesKeep);
        
        long freed = 0;
        int deleted = 0;
        List<String> deletedFiles = new ArrayList<>();
        
        for (int i = 0; i < deletableCount && freed < bytesToFree; i++) {
            File file = files.get(i);
            
            // Skip protected files
            if (file.lastModified() > protectionCutoff) {
                logDebug("Skipping protected file: " + file.getName() + 
                    " (age: " + getFileAge(file) + ")");
                continue;
            }
            
            long fileSize = file.length();
            String fileName = file.getName();
            
            if (deleteFile(file)) {
                freed += fileSize;
                deleted++;
                deletedFiles.add(fileName);
                logInfo("Deleted CDR file: " + fileName + " (" + formatSize(fileSize) + ")");
            } else {
                logWarn("Failed to delete: " + fileName);
            }
        }
        
        // Update statistics
        totalBytesFreed += freed;
        totalFilesDeleted += deleted;
        lastCleanupTime = System.currentTimeMillis();

        // Invalidate the walk cache so the next get* call sees the post-cleanup
        // state (avoids the UI showing stale "deletable size" right after a
        // cleanup completes).
        invalidateWalkCache();

        logInfo("CDR cleanup complete: freed " + formatSize(freed) +
            " (" + deleted + " files)");

        return new CleanupResult(freed, deleted, deletedFiles);
    }
    
    /**
     * Delete a file, trying Java API first then shell fallback.
     */
    private boolean deleteFile(File file) {
        // Try Java delete
        if (file.delete()) {
            return true;
        }
        
        // Try shell rm
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rm", "-f", file.getAbsolutePath()});
            int exitCode = waitForBounded(p, DELETE_TIMEOUT_MS);
            if (exitCode == PROCESS_WAIT_FAILED) {
                logWarn("Shell delete timed out or was interrupted: " + file.getAbsolutePath());
            }
            return exitCode == 0 && !file.exists();
        } catch (Exception e) {
            logWarn("Shell delete failed: " + e.getMessage());
            return false;
        }
    }
    
    private String getFileAge(File file) {
        long ageMs = System.currentTimeMillis() - file.lastModified();
        long hours = ageMs / (3600 * 1000);
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }

    // ==================== Background Monitoring ====================
    
    /**
     * Start periodic monitoring of SD card space.
     * Automatically triggers cleanup when space runs low.
     */
    public void startMonitoring() {
        // Gate only on `enabled` — the tick self-discovers the SD card, so the
        // monitor may be armed before vold mounts it (boot-race) and will
        // converge once the card appears. Requiring sdCardAvailable here would
        // re-introduce the never-arms-after-late-mount bug.
        if (!enabled) return;

        if (monitorScheduler != null && !monitorScheduler.isShutdown()) {
            return; // Already running
        }
        
        monitoringActive.set(true);
        
        monitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ExternalStorageMonitor");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                // Self-heal: if the SD wasn't mounted when the monitor armed
                // (boot-race), re-run discovery here so the monitor re-arms
                // automatically once vold mounts the card — at
                // MONITOR_INTERVAL_SECONDS granularity, with no dependency on
                // the user opening the external-storage web page.
                if (sdCardPath == null) {
                    discoverPaths();
                }
                if (enabled && sdCardAvailable) {
                    long freeSpace = getSdCardFreeSpace();
                    long reservedBytes = reservedSpaceMb * 1024L * 1024L;
                    
                    // Trigger cleanup at 90% of reserved threshold
                    if (freeSpace < reservedBytes * 0.9) {
                        logInfo("SD card space low (" + formatSize(freeSpace) + 
                            "), triggering cleanup");
                        ensureReservedSpace();
                    }
                }
            } catch (Exception e) {
                logWarn("Monitor error: " + e.getMessage());
            }
        }, MONITOR_INTERVAL_SECONDS, MONITOR_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        logInfo("Started external storage monitoring (interval=" + MONITOR_INTERVAL_SECONDS + "s)");
    }
    
    /**
     * Stop periodic monitoring.
     */
    public void stopMonitoring() {
        monitoringActive.set(false);
        
        if (monitorScheduler != null) {
            monitorScheduler.shutdown();
            try {
                if (!monitorScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitorScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitorScheduler.shutdownNow();
            }
            monitorScheduler = null;
            logInfo("Stopped external storage monitoring");
        }
    }
    
    /**
     * Check if monitoring is active.
     */
    public boolean isMonitoringActive() {
        return monitoringActive.get();
    }
    
    // ==================== Preview/Dry Run ====================
    
    /**
     * Preview what would be deleted without actually deleting.
     * Useful for UI to show user what will happen.
     * 
     * @param bytesToFree Target bytes to free
     * @return List of files that would be deleted with their sizes
     */
    public List<FileInfo> previewCleanup(long bytesToFree) {
        List<FileInfo> preview = new ArrayList<>();
        
        if (cdrPath == null) {
            discoverPaths();
            if (cdrPath == null) return preview;
        }
        
        List<File> files = getCdrVideoFiles();
        files.sort(Comparator.comparingLong(File::lastModified));
        
        long protectionCutoff = System.currentTimeMillis() - (protectedHours * 3600L * 1000L);
        int deletableCount = Math.max(0, files.size() - minFilesKeep);
        
        long wouldFree = 0;
        
        for (int i = 0; i < deletableCount && wouldFree < bytesToFree; i++) {
            File file = files.get(i);
            
            if (file.lastModified() > protectionCutoff) {
                continue; // Would be skipped
            }
            
            preview.add(new FileInfo(
                file.getName(),
                file.length(),
                file.lastModified(),
                file.getAbsolutePath()
            ));
            wouldFree += file.length();
        }
        
        return preview;
    }
    
    /**
     * File info for preview.
     */
    public static class FileInfo {
        public final String name;
        public final long size;
        public final long lastModified;
        public final String path;
        
        public FileInfo(String name, long size, long lastModified, String path) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.path = path;
        }
    }
    
    // ==================== Utility ====================
    
    public static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.1f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.1f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format("%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }
    
    /**
     * Refresh paths (call when SD card is mounted/unmounted).
     */
    public void refresh() {
        discoverPaths();
        if (enabled && sdCardAvailable && !monitoringActive.get()) {
            startMonitoring();
        }
    }
    
    /**
     * Shutdown cleaner and release resources.
     */
    public void shutdown() {
        stopMonitoring();
        logInfo("ExternalStorageCleaner shutdown complete");
    }
}
