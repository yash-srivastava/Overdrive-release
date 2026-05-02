package com.overdrive.app.storage;

import android.os.StatFs;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StorageManager - SOTA Storage Management for Overdrive
 * 
 * Manages recording and surveillance storage with:
 * - Dedicated directories under /storage/emulated/0/Overdrive/ (internal) or SD card
 * - Storage type selection: INTERNAL or SD_CARD for both recordings and surveillance
 * - Configurable size limits (100MB - 10000MB for SD card)
 * - Automatic cleanup of oldest files when limit is reached
 * - Event-driven cleanup (after each file save)
 * - Periodic background cleanup during long recordings
 * - Thread-safe operations
 * - SD card detection and availability monitoring
 * 
 * SOTA Cleanup Strategy:
 * 1. Pre-recording check - Reserve space before starting
 * 2. Post-file cleanup - Run after each file is closed/saved
 * 3. Periodic cleanup - Background task every 30 seconds during active recording
 * 
 * Storage Selection:
 * - Each storage type (recordings, surveillance) can independently use internal or SD card
 * - SD card paths are auto-discovered via BYD system properties or known mount points
 * - Graceful fallback to internal storage if SD card becomes unavailable
 */
public class StorageManager {
    private static final String TAG = "StorageManager";
    
    // Storage type enum
    public enum StorageType {
        INTERNAL,
        SD_CARD
    }
    
    // Hybrid logger - uses DaemonLogger when running as daemon, android.util.Log otherwise
    private static boolean useDaemonLogger = false;
    private static com.overdrive.app.logging.DaemonLogger daemonLogger = null;
    
    /**
     * Enable daemon logging mode (call from daemon process).
     */
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
    
    // Base directories for Overdrive files
    private static final String INTERNAL_BASE_DIR = "/storage/emulated/0/Overdrive";
    
    // Known SD card mount paths (BYD and common Android paths)
    private static final String[] SD_CARD_PATHS = {
        "/storage/external_sd",
        "/storage/sdcard1",
        "/storage/sdcard0",
        "/mnt/external_sd",
        "/mnt/sdcard/external_sd",
        "/mnt/media_rw",
        "/mnt/sdcard",
    };
    
    // Subdirectories
    public static final String RECORDINGS_SUBDIR = "recordings";
    public static final String SURVEILLANCE_SUBDIR = "surveillance";
    public static final String PROXIMITY_SUBDIR = "proximity";
    public static final String TRIPS_SUBDIR = "trips";
    
    // Config file location
    private static final String CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    
    // Default limits (in bytes)
    private static final long DEFAULT_RECORDINGS_LIMIT_MB = 500;
    private static final long DEFAULT_SURVEILLANCE_LIMIT_MB = 500;
    private static final long DEFAULT_PROXIMITY_LIMIT_MB = 500;
    private static final long DEFAULT_TRIPS_LIMIT_MB = 500;
    private static final long MIN_LIMIT_MB = 100;
    private static final long MAX_LIMIT_MB_INTERNAL = 100000;  // 100GB max for internal
    private static final long MAX_LIMIT_MB_SD_CARD = 100000;  // 100GB max for SD card
    
    // Periodic cleanup interval (30 seconds)
    private static final long CLEANUP_INTERVAL_SECONDS = 30;
    
    // Current limits
    private static long recordingsLimitMb = DEFAULT_RECORDINGS_LIMIT_MB;
    private static long surveillanceLimitMb = DEFAULT_SURVEILLANCE_LIMIT_MB;
    private static long proximityLimitMb = DEFAULT_PROXIMITY_LIMIT_MB;
    private long tripsLimitMb = DEFAULT_TRIPS_LIMIT_MB;
    
    // Storage type selection (SOTA: independent selection for recordings and surveillance)
    private StorageType recordingsStorageType = StorageType.INTERNAL;
    private StorageType surveillanceStorageType = StorageType.INTERNAL;
    private StorageType tripsStorageType = StorageType.INTERNAL;
    
    // SD card state
    private String sdCardPath = null;
    private boolean sdCardAvailable = false;
    
    // Singleton instance
    private static StorageManager instance;
    
    // Internal storage directories (always available)
    private File internalRecordingsDir;
    private File internalSurveillanceDir;
    private File internalProximityDir;
    private File internalTripsDir;
    
    // SD card directories (may be null if SD card not available)
    private File sdCardRecordingsDir;
    private File sdCardSurveillanceDir;
    private File sdCardProximityDir;
    private File sdCardTripsDir;
    
    // Active directories (based on storage type selection)
    private File recordingsDir;
    private File surveillanceDir;
    private File proximityDir;
    private File tripsDir;
    
    // Background cleanup scheduler
    private ScheduledExecutorService cleanupScheduler;
    private final AtomicBoolean recordingActive = new AtomicBoolean(false);
    private final AtomicBoolean surveillanceActive = new AtomicBoolean(false);
    
    // Async cleanup executor (single thread to avoid concurrent cleanup)
    private final java.util.concurrent.ExecutorService asyncCleanupExecutor = 
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StorageCleanupAsync");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);  // Low priority to not interfere with recording
            return t;
        });
    
    // Cleanup lock to prevent concurrent cleanup operations
    private final Object cleanupLock = new Object();
    
    // SD card mount watchdog (keeps SD card mounted during sentry mode)
    private ScheduledExecutorService sdCardWatchdog;
    private static final long SD_WATCHDOG_INTERVAL_SECONDS = 15;
    private int sdWatchdogConsecutiveFailures = 0;
    private static final int SD_WATCHDOG_MAX_VERBOSE_FAILURES = 5;  // Log verbosely for first 5 failures
    private static final int SD_WATCHDOG_QUIET_LOG_INTERVAL = 20;   // Then log every 20th attempt (~5 min)
    
    private StorageManager() {
        discoverSdCard();
        initDirectories();
        loadConfig();
        
        // SOTA: If config says SD card but it's not available, try to mount it
        // This happens when daemon starts and SD card is unmounted
        if (!sdCardAvailable && 
            (surveillanceStorageType == StorageType.SD_CARD || 
             recordingsStorageType == StorageType.SD_CARD ||
             tripsStorageType == StorageType.SD_CARD)) {
            logInfo("SD card configured but not available - attempting mount...");
            ensureSdCardMounted(true);
        }
        
        updateActiveDirectories();
    }
    
    public static synchronized StorageManager getInstance() {
        if (instance == null) {
            instance = new StorageManager();
        }
        return instance;
    }
    
    // ==================== SD Card Discovery ====================
    
    /**
     * SOTA: Mount SD card if unmounted.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     * 
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted() {
        return ensureSdCardMounted(false);
    }
    
    /**
     * SOTA: Mount SD card, optionally forcing a remount.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     * 
     * @param force If true, always attempt to mount even if already mounted
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted(boolean force) {
        // Quick check: if path is already accessible, no work needed
        if (sdCardAvailable && sdCardPath != null) {
            File sdDir = new File(sdCardPath);
            if (sdDir.exists() && sdDir.canWrite()) {
                logDebug("SD card already mounted at: " + sdCardPath);
                return true;
            }
        }
        
        logDebug("Mounting SD card...");
        
        try {
            // Step 1: Find the Volume ID using 'sm list-volumes all'
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;
            String volumeId = null;      // e.g., "public:8,97"
            String volumeUuid = null;    // e.g., "3661-3064"
            
            while ((line = reader.readLine()) != null) {
                // Parse lines like: "public:8,97 unmounted 3661-3064" or "public:8,97 mounted 3661-3064"
                line = line.trim();
                logDebug("sm list-volumes: " + line);
                
                if (line.startsWith("public:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        volumeId = parts[0];           // e.g., "public:8,97"
                        String state = parts[1];       // e.g., "unmounted" or "mounted"
                        volumeUuid = parts[2];         // e.g., "3661-3064"
                        
                        // If already mounted, check if path is actually accessible
                        if ("mounted".equals(state)) {
                            String mountPath = "/storage/" + volumeUuid;
                            File mountDir = new File(mountPath);
                            if (mountDir.exists() && mountDir.canWrite()) {
                                sdCardPath = mountPath;
                                sdCardAvailable = true;
                                logInfo("SD card already mounted at: " + sdCardPath);
                                reader.close();
                                listProcess.waitFor();
                                initSdCardDirectories();
                                updateActiveDirectories();
                                return true;
                            }
                            // Mounted but path not accessible — stale mount, will remount below
                            logWarn("SD card volume " + volumeId + " reports mounted but path " + mountPath + 
                                " not accessible — will force remount");
                        }
                        
                        break;  // Found the public volume
                    }
                }
            }
            reader.close();
            listProcess.waitFor();
            
            // Step 2: Always attempt mount if we found a volume
            // sm mount is safe to call even if already mounted (no-op if healthy)
            // For stale mounts, this forces the system to re-establish the FUSE path
            if (volumeId != null) {
                
                Process mountProcess = Runtime.getRuntime().exec(new String[]{"sm", "mount", volumeId});
                
                // Capture output for debugging
                BufferedReader outReader = new BufferedReader(new InputStreamReader(mountProcess.getInputStream()));
                BufferedReader errReader = new BufferedReader(new InputStreamReader(mountProcess.getErrorStream()));
                StringBuilder output = new StringBuilder();
                String outLine;
                while ((outLine = outReader.readLine()) != null) {
                    output.append(outLine).append("\n");
                }
                while ((outLine = errReader.readLine()) != null) {
                    output.append("ERR: ").append(outLine).append("\n");
                }
                outReader.close();
                errReader.close();
                
                int exitCode = mountProcess.waitFor();
                logInfo("sm mount exit code: " + exitCode + 
                    (output.length() > 0 ? ", output: " + output.toString().trim() : ""));
                
                if (exitCode == 0 && volumeUuid != null) {
                    // Wait for mount to complete
                    String mountPath = "/storage/" + volumeUuid;
                    File mountDir = new File(mountPath);
                    
                    // Wait up to 5 seconds for mount to appear
                    for (int i = 0; i < 10; i++) {
                        Thread.sleep(500);
                        if (mountDir.exists() && mountDir.canWrite()) {
                            sdCardPath = mountPath;
                            sdCardAvailable = true;
                            logInfo("SD card mounted successfully at: " + sdCardPath);
                            initSdCardDirectories();
                            updateActiveDirectories();
                            return true;
                        }
                        logDebug("Waiting for mount... attempt " + (i+1) + "/10");
                    }
                    logWarn("SD card mount path not accessible after mount: " + mountPath);
                } else {
                    logWarn("sm mount command failed with exit code: " + exitCode);
                }
            } else {
                logDebug("No public SD card volume found");
            }
            
        } catch (Exception e) {
            logError("Error mounting SD card: " + e.getMessage());
        }
        
        // Re-run discovery in case mount succeeded but we missed it
        discoverSdCard();
        return sdCardAvailable;
    }
    
    /**
     * Check if SD card is currently mounted (without attempting to mount).
     * Simply checks if the path exists and is writable.
     * 
     * @return true if SD card is mounted
     */
    public boolean isSdCardMounted() {
        if (sdCardPath == null) {
            return false;
        }
        
        File sdDir = new File(sdCardPath);
        return sdDir.exists() && sdDir.isDirectory() && sdDir.canWrite();
    }
    
    /**
     * Ensure SD card is ready for use.
     * If SD card storage is selected but not mounted, attempts to mount it.
     * If mount fails, falls back to internal storage.
     * 
     * @param forSurveillance true if checking for surveillance, false for recordings
     * @return true if storage is ready (either SD card mounted or fallback to internal)
     */
    public boolean ensureStorageReady(boolean forSurveillance) {
        StorageType selectedType = forSurveillance ? surveillanceStorageType : recordingsStorageType;

        if (selectedType == StorageType.INTERNAL) {
            // Internal storage is always ready
            return true;
        }

        // CRITICAL: Don't switch storage location while recording is active
        // This prevents files from being split across SD card and internal storage
        if (!forSurveillance && recordingActive.get()) {
            logDebug("Recording active - not switching storage location");
            return true;
        }
        if (forSurveillance && surveillanceActive.get()) {
            logDebug("Surveillance active - not switching storage location");
            return true;
        }

        // SD card selected - ensure it's mounted
        if (!isSdCardMounted()) {
            logInfo("SD card not mounted, attempting to mount for " + 
                (forSurveillance ? "surveillance" : "recordings"));

            if (!ensureSdCardMounted()) {
                logWarn("Failed to mount SD card, falling back to internal storage");

                // Temporary fallback: point active directory to internal storage
                // but do NOT change the storage type preference — user still wants SD card.
                // When SD card comes back (watchdog remount or next ensureStorageReady call),
                // updateActiveDirectories() will restore the SD card path.
                if (forSurveillance) {
                    surveillanceDir = internalSurveillanceDir;
                    proximityDir = internalProximityDir;
                } else {
                    recordingsDir = internalRecordingsDir;
                }

                return true;  // Internal storage is ready
            }
        }

        // SD card is mounted, ensure directories exist
        initSdCardDirectories();
        updateActiveDirectories();

        // Pre-reserve space on SD card by cleaning BYD dashcam files if needed
        try {
            ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
            if (cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                cleaner.ensureReservedSpace();
            }
        } catch (Exception e) {
            logWarn("Pre-recording CDR cleanup failed: " + e.getMessage());
        }

        return true;
    }
    
    /**
     * Discover SD card path using sm list-volumes, BYD system properties, or known mount points.
     */
    public void discoverSdCard() {
        sdCardPath = null;
        sdCardAvailable = false;
        
        // Method 1: Check using 'sm list-volumes all' for mounted public volumes
        try {
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;
            
            while ((line = reader.readLine()) != null) {
                // Parse lines like: "public:8,97 mounted 3661-3064"
                line = line.trim();
                if (line.startsWith("public:") && line.contains("mounted")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        String volumeUuid = parts[2];  // e.g., "3661-3064"
                        String mountPath = "/storage/" + volumeUuid;
                        File mountDir = new File(mountPath);
                        
                        if (mountDir.exists() && mountDir.isDirectory() && mountDir.canWrite()) {
                            sdCardPath = mountPath;
                            sdCardAvailable = true;
                            logInfo("Found SD card via sm list-volumes: " + sdCardPath);
                            reader.close();
                            listProcess.waitFor();
                            return;
                        }
                    }
                }
            }
            reader.close();
            listProcess.waitFor();
        } catch (Exception e) {
            logDebug("Could not check sm list-volumes: " + e.getMessage());
        }
        
        // Method 2: Check BYD system property for SD card UUID
        String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
        if (sdUuid != null && !sdUuid.isEmpty()) {
            String uuidPath = "/storage/" + sdUuid;
            File uuidDir = new File(uuidPath);
            if (uuidDir.exists() && uuidDir.isDirectory() && uuidDir.canWrite()) {
                sdCardPath = uuidPath;
                sdCardAvailable = true;
                logInfo("Found SD card via BYD UUID: " + sdCardPath);
                return;
            }
        }
        
        // Method 3: Scan /storage/ for mounted volumes
        try {
            File storageDir = new File("/storage");
            if (storageDir.exists() && storageDir.isDirectory()) {
                File[] volumes = storageDir.listFiles();
                if (volumes != null) {
                    for (File vol : volumes) {
                        // Skip known non-SD entries
                        String name = vol.getName();
                        if (name.equals("emulated") || name.equals("self") || name.startsWith(".")) {
                            continue;
                        }
                        
                        // Any writable directory under /storage/ that isn't emulated/self could be an SD card
                        if (vol.isDirectory() && vol.canWrite()) {
                            sdCardPath = vol.getAbsolutePath();
                            sdCardAvailable = true;
                            logInfo("Found SD card via /storage scan: " + sdCardPath);
                            return;
                        }
                        
                        // Some mounts are readable but not writable via Java — try shell test
                        if (vol.isDirectory() && vol.canRead()) {
                            try {
                                Process p = Runtime.getRuntime().exec(new String[]{
                                    "sh", "-c", "touch " + vol.getAbsolutePath() + "/.overdrive_probe && rm " + vol.getAbsolutePath() + "/.overdrive_probe"
                                });
                                int exitCode = p.waitFor();
                                if (exitCode == 0) {
                                    sdCardPath = vol.getAbsolutePath();
                                    sdCardAvailable = true;
                                    logInfo("Found SD card via /storage scan (shell write test): " + sdCardPath);
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            logDebug("Could not scan /storage: " + e.getMessage());
        }
        
        // Method 4: Parse /proc/mounts for vfat/exfat filesystems (SD card signatures)
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                // SD cards are typically vfat, exfat, or sdcardfs
                if (line.contains("vfat") || line.contains("exfat")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String mountPoint = parts[1];
                        // Skip internal partitions
                        if (mountPoint.startsWith("/mnt/vendor") || mountPoint.startsWith("/firmware") ||
                            mountPoint.equals("/boot") || mountPoint.startsWith("/cache")) {
                            continue;
                        }
                        File mountDir = new File(mountPoint);
                        if (mountDir.exists() && mountDir.isDirectory() && mountDir.canRead()) {
                            // Verify writable via shell
                            try {
                                Process p = Runtime.getRuntime().exec(new String[]{
                                    "sh", "-c", "touch " + mountPoint + "/.overdrive_probe && rm " + mountPoint + "/.overdrive_probe"
                                });
                                int exitCode = p.waitFor();
                                if (exitCode == 0) {
                                    sdCardPath = mountPoint;
                                    sdCardAvailable = true;
                                    logInfo("Found SD card via /proc/mounts: " + sdCardPath + " (filesystem: " + 
                                        (line.contains("exfat") ? "exfat" : "vfat") + ")");
                                    reader.close();
                                    return;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            logDebug("Could not parse /proc/mounts: " + e.getMessage());
        }
        
        // Method 4: Check known paths
        for (String path : SD_CARD_PATHS) {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory() && dir.canWrite()) {
                sdCardPath = path;
                sdCardAvailable = true;
                logInfo("Found SD card at: " + sdCardPath);
                return;
            }
        }
        
        logDebug("No writable SD card found");
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
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                reader.close();
                p.waitFor();
                return line != null ? line.trim() : "";
            } catch (Exception e2) {
                return "";
            }
        }
    }
    
    /**
     * Initialize storage directories.
     * IMPORTANT: Sets world-readable permissions so the UI app can access recordings.
     */
    private void initDirectories() {
        // Initialize internal storage directories (always available)
        File internalBaseDir = new File(INTERNAL_BASE_DIR);
        if (!internalBaseDir.exists()) {
            boolean created = internalBaseDir.mkdirs();
            logInfo("Created internal base directory: " + INTERNAL_BASE_DIR + " (success=" + created + ")");
        }
        internalBaseDir.setReadable(true, false);
        internalBaseDir.setExecutable(true, false);
        
        internalRecordingsDir = new File(internalBaseDir, RECORDINGS_SUBDIR);
        if (!internalRecordingsDir.exists()) {
            boolean created = internalRecordingsDir.mkdirs();
            logInfo("Created internal recordings directory: " + internalRecordingsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalRecordingsDir.setReadable(true, false);
        internalRecordingsDir.setExecutable(true, false);
        
        internalSurveillanceDir = new File(internalBaseDir, SURVEILLANCE_SUBDIR);
        if (!internalSurveillanceDir.exists()) {
            boolean created = internalSurveillanceDir.mkdirs();
            logInfo("Created internal surveillance directory: " + internalSurveillanceDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalSurveillanceDir.setReadable(true, false);
        internalSurveillanceDir.setExecutable(true, false);
        
        internalProximityDir = new File(internalBaseDir, PROXIMITY_SUBDIR);
        if (!internalProximityDir.exists()) {
            boolean created = internalProximityDir.mkdirs();
            logInfo("Created internal proximity directory: " + internalProximityDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalProximityDir.setReadable(true, false);
        internalProximityDir.setExecutable(true, false);
        
        internalTripsDir = new File(internalBaseDir, TRIPS_SUBDIR);
        if (!internalTripsDir.exists()) {
            boolean created = internalTripsDir.mkdirs();
            logInfo("Created internal trips directory: " + internalTripsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalTripsDir.setReadable(true, false);
        internalTripsDir.setExecutable(true, false);
        
        // Initialize SD card directories if available
        initSdCardDirectories();
    }
    
    /**
     * Initialize SD card directories if SD card is available.
     */
    private void initSdCardDirectories() {
        if (!sdCardAvailable || sdCardPath == null) {
            sdCardRecordingsDir = null;
            sdCardSurveillanceDir = null;
            sdCardProximityDir = null;
            sdCardTripsDir = null;
            return;
        }
        
        File sdBaseDir = new File(sdCardPath, "Overdrive");
        
        // Always try to create directories (mkdirs is idempotent)
        // This handles the case where SD card was remounted
        boolean baseCreated = sdBaseDir.mkdirs();
        if (!sdBaseDir.exists()) {
            logError("Failed to create SD card base directory: " + sdBaseDir.getAbsolutePath());
            return;
        }
        if (baseCreated) {
            logInfo("Created SD card base directory: " + sdBaseDir.getAbsolutePath());
        }
        sdBaseDir.setReadable(true, false);
        sdBaseDir.setWritable(true, false);
        sdBaseDir.setExecutable(true, false);
        
        sdCardRecordingsDir = new File(sdBaseDir, RECORDINGS_SUBDIR);
        boolean recCreated = sdCardRecordingsDir.mkdirs();
        if (!sdCardRecordingsDir.exists()) {
            logError("Failed to create SD card recordings directory: " + sdCardRecordingsDir.getAbsolutePath());
        } else {
            if (recCreated) {
                logInfo("Created SD card recordings directory: " + sdCardRecordingsDir.getAbsolutePath());
            }
            sdCardRecordingsDir.setReadable(true, false);
            sdCardRecordingsDir.setWritable(true, false);
            sdCardRecordingsDir.setExecutable(true, false);
        }
        
        sdCardSurveillanceDir = new File(sdBaseDir, SURVEILLANCE_SUBDIR);
        boolean survCreated = sdCardSurveillanceDir.mkdirs();
        if (!sdCardSurveillanceDir.exists()) {
            logError("Failed to create SD card surveillance directory: " + sdCardSurveillanceDir.getAbsolutePath());
        } else {
            if (survCreated) {
                logInfo("Created SD card surveillance directory: " + sdCardSurveillanceDir.getAbsolutePath());
            }
            sdCardSurveillanceDir.setReadable(true, false);
            sdCardSurveillanceDir.setWritable(true, false);
            sdCardSurveillanceDir.setExecutable(true, false);
        }
        
        sdCardProximityDir = new File(sdBaseDir, PROXIMITY_SUBDIR);
        boolean proxCreated = sdCardProximityDir.mkdirs();
        if (!sdCardProximityDir.exists()) {
            logError("Failed to create SD card proximity directory: " + sdCardProximityDir.getAbsolutePath());
        } else {
            if (proxCreated) {
                logInfo("Created SD card proximity directory: " + sdCardProximityDir.getAbsolutePath());
            }
            sdCardProximityDir.setReadable(true, false);
            sdCardProximityDir.setWritable(true, false);
            sdCardProximityDir.setExecutable(true, false);
        }
        
        sdCardTripsDir = new File(sdBaseDir, TRIPS_SUBDIR);
        boolean tripsCreated = sdCardTripsDir.mkdirs();
        if (!sdCardTripsDir.exists()) {
            logError("Failed to create SD card trips directory: " + sdCardTripsDir.getAbsolutePath());
        } else {
            if (tripsCreated) {
                logInfo("Created SD card trips directory: " + sdCardTripsDir.getAbsolutePath());
            }
            sdCardTripsDir.setReadable(true, false);
            sdCardTripsDir.setWritable(true, false);
            sdCardTripsDir.setExecutable(true, false);
        }
        
        // Verify directories are actually writable
        if (sdCardSurveillanceDir != null && sdCardSurveillanceDir.exists()) {
            if (!sdCardSurveillanceDir.canWrite()) {
                logError("SD card surveillance directory exists but is not writable: " + sdCardSurveillanceDir.getAbsolutePath());
            } else {
                logInfo("SD card surveillance directory verified writable: " + sdCardSurveillanceDir.getAbsolutePath());
            }
        }
    }
    
    /**
     * Update active directories based on storage type selection.
     * Falls back to internal storage if SD card is not available.
     */
    private void updateActiveDirectories() {
        // Recordings directory
        // CRITICAL: Don't switch while recording is active to prevent split files
        if (recordingActive.get()) {
            logDebug("Recording active - skipping recordings directory update");
        } else if (recordingsStorageType == StorageType.SD_CARD && sdCardAvailable && sdCardRecordingsDir != null) {
            recordingsDir = sdCardRecordingsDir;
            logInfo("Recordings using SD card: " + recordingsDir.getAbsolutePath());
        } else {
            recordingsDir = internalRecordingsDir;
            if (recordingsStorageType == StorageType.SD_CARD) {
                logWarn("SD card not available for recordings, falling back to internal storage");
            }
        }
        
        // Surveillance directory
        // CRITICAL: Don't switch while surveillance is active to prevent split files
        if (surveillanceActive.get()) {
            logDebug("Surveillance active - skipping surveillance directory update");
        } else if (surveillanceStorageType == StorageType.SD_CARD && sdCardAvailable && sdCardSurveillanceDir != null) {
            surveillanceDir = sdCardSurveillanceDir;
            logInfo("Surveillance using SD card: " + surveillanceDir.getAbsolutePath());
        } else {
            surveillanceDir = internalSurveillanceDir;
            if (surveillanceStorageType == StorageType.SD_CARD) {
                logWarn("SD card not available for surveillance, falling back to internal storage");
            }
        }
        
        // Proximity always uses same storage as surveillance
        if (!surveillanceActive.get()) {
            if (surveillanceStorageType == StorageType.SD_CARD && sdCardAvailable && sdCardProximityDir != null) {
                proximityDir = sdCardProximityDir;
            } else {
                proximityDir = internalProximityDir;
            }
        }
        
        // Trips directory
        // Trip telemetry files are small — no recording-active check needed
        if (tripsStorageType == StorageType.SD_CARD && sdCardAvailable && sdCardTripsDir != null) {
            tripsDir = sdCardTripsDir;
            logInfo("Trips using SD card: " + tripsDir.getAbsolutePath());
        } else {
            tripsDir = internalTripsDir;
            if (tripsStorageType == StorageType.SD_CARD) {
                logWarn("SD card not available for trips, falling back to internal storage");
            }
        }
    }
    
    /**
     * Load storage limits and storage type from config file.
     */
    private void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(sb.toString());
                JSONObject storage = config.optJSONObject("storage");
                if (storage != null) {
                    recordingsLimitMb = storage.optLong("recordingsLimitMb", DEFAULT_RECORDINGS_LIMIT_MB);
                    surveillanceLimitMb = storage.optLong("surveillanceLimitMb", DEFAULT_SURVEILLANCE_LIMIT_MB);
                    proximityLimitMb = storage.optLong("proximityLimitMb", DEFAULT_PROXIMITY_LIMIT_MB);
                    tripsLimitMb = storage.optLong("tripsLimitMb", DEFAULT_TRIPS_LIMIT_MB);
                    
                    // Load storage type selection
                    String recStorageType = storage.optString("recordingsStorageType", "INTERNAL");
                    String survStorageType = storage.optString("surveillanceStorageType", "INTERNAL");
                    String tripsStorageTypeStr = storage.optString("tripsStorageType", "INTERNAL");
                    
                    recordingsStorageType = "SD_CARD".equals(recStorageType) ? StorageType.SD_CARD : StorageType.INTERNAL;
                    surveillanceStorageType = "SD_CARD".equals(survStorageType) ? StorageType.SD_CARD : StorageType.INTERNAL;
                    tripsStorageType = "SD_CARD".equals(tripsStorageTypeStr) ? StorageType.SD_CARD : StorageType.INTERNAL;
                    
                    // Clamp to valid range based on storage type
                    long maxRecLimit = recordingsStorageType == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
                    long maxSurvLimit = surveillanceStorageType == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
                    long maxTripsLimit = tripsStorageType == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
                    
                    recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxRecLimit, recordingsLimitMb));
                    surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxSurvLimit, surveillanceLimitMb));
                    proximityLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxSurvLimit, proximityLimitMb));
                    tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxTripsLimit, tripsLimitMb));
                    
                    logInfo("Loaded storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType + 
                        "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType + 
                        "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
                }
            }
        } catch (Exception e) {
            logWarn("Could not load storage config: " + e.getMessage());
        }
    }
    
    /**
     * Save storage limits and storage type to config file.
     */
    public void saveConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            JSONObject config;
            
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                config = new JSONObject(sb.toString());
            } else {
                config = new JSONObject();
                config.put("version", 1);
            }
            
            JSONObject storage = config.optJSONObject("storage");
            if (storage == null) {
                storage = new JSONObject();
            }
            storage.put("recordingsLimitMb", recordingsLimitMb);
            storage.put("surveillanceLimitMb", surveillanceLimitMb);
            storage.put("proximityLimitMb", proximityLimitMb);
            storage.put("tripsLimitMb", tripsLimitMb);
            storage.put("recordingsStorageType", recordingsStorageType.name());
            storage.put("surveillanceStorageType", surveillanceStorageType.name());
            storage.put("tripsStorageType", tripsStorageType.name());
            config.put("storage", storage);
            config.put("lastModified", System.currentTimeMillis());
            
            FileWriter writer = new FileWriter(configFile);
            writer.write(config.toString(2));
            writer.close();
            
            configFile.setReadable(true, false);
            configFile.setWritable(true, false);
            
            logInfo("Saved storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType + 
                "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType + 
                "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
        } catch (Exception e) {
            logError("Could not save storage config: " + e.getMessage());
        }
    }
    
    // ==================== Directory Getters ====================
    
    public File getRecordingsDir() {
        return recordingsDir;
    }
    
    public File getSurveillanceDir() {
        return surveillanceDir;
    }
    
    public File getProximityDir() {
        return proximityDir;
    }
    
    public File getTripsDir() {
        return tripsDir;
    }
    
    public String getRecordingsPath() {
        return recordingsDir.getAbsolutePath();
    }
    
    public String getSurveillancePath() {
        return surveillanceDir.getAbsolutePath();
    }
    
    public String getProximityPath() {
        return proximityDir.getAbsolutePath();
    }
    
    public String getTripsPath() {
        return tripsDir.getAbsolutePath();
    }
    
    /**
     * Fix permissions on all storage directories and files.
     * Call this from daemon startup to ensure UI app can read recordings.
     * Note: chmod doesn't work on FUSE - rely on MediaScanner broadcast for cross-UID visibility.
     */
    public void fixAllPermissions() {
        // Fix directory permissions synchronously (fast, no I/O contention)
        File baseDir = new File(INTERNAL_BASE_DIR);
        if (baseDir.exists()) {
            baseDir.setReadable(true, false);
            baseDir.setExecutable(true, false);
        }
        fixDirectoryPermissions(recordingsDir);
        fixDirectoryPermissions(surveillanceDir);
        fixDirectoryPermissions(proximityDir);
        fixDirectoryPermissions(tripsDir);
        
        // Make all existing files world-readable (chmod 666).
        // Required for: (1) UI app (different UID) to read files directly,
        // (2) FUSE layer on BYD Android to allow File.listFiles() to see them.
        // This is fast (no shell processes) — just Java File.setReadable() calls.
        makeFilesReadable(recordingsDir);
        makeFilesReadable(surveillanceDir);
        makeFilesReadable(proximityDir);
        makeFilesReadable(tripsDir);
        
        // SOTA: Incremental MediaScanner broadcast — only broadcast files created
        // since the last successful broadcast. Uses a marker file to track the
        // timestamp of the last full scan. On first run (no marker), broadcasts
        // everything once, then subsequent startups only broadcast new files.
        //
        // Additionally, broadcasts are throttled (50ms between each shell exec)
        // to avoid saturating the I/O bus during camera pipeline startup.
        // The old approach spawned 2 shell processes per file × hundreds of files
        // = hundreds of concurrent process forks competing with the GPU pipeline.
        new Thread(() -> {
            long lastScanTimestamp = loadLastBroadcastTimestamp();
            long scanStartTime = System.currentTimeMillis();
            
            int count = 0;
            count += broadcastFilesSince(recordingsDir, lastScanTimestamp);
            count += broadcastFilesSince(surveillanceDir, lastScanTimestamp);
            count += broadcastFilesSince(proximityDir, lastScanTimestamp);
            
            saveLastBroadcastTimestamp(scanStartTime);
            
            if (count > 0) {
                logInfo("MediaScanner broadcast complete: " + count + " new files indexed");
            } else {
                logDebug("MediaScanner: no new files to broadcast since last scan");
            }
        }, "MediaScannerBroadcast").start();
    }
    
    /** Marker file that stores the epoch millis of the last successful broadcast scan. */
    private static final String BROADCAST_MARKER_FILE = "/data/local/tmp/overdrive_last_mediascan";
    
    /** Throttle delay between individual file broadcasts (ms). */
    private static final long BROADCAST_THROTTLE_MS = 50;
    
    /**
     * Load the timestamp of the last successful MediaScanner broadcast.
     * Returns 0 if no marker exists (first run — will broadcast everything).
     */
    private long loadLastBroadcastTimestamp() {
        try {
            File marker = new File(BROADCAST_MARKER_FILE);
            if (marker.exists()) {
                String content = new java.util.Scanner(marker).useDelimiter("\\A").next().trim();
                return Long.parseLong(content);
            }
        } catch (Exception e) {
            logDebug("No broadcast marker found, will do full scan");
        }
        return 0;
    }
    
    /**
     * Save the timestamp of the current broadcast scan.
     */
    private void saveLastBroadcastTimestamp(long timestamp) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(BROADCAST_MARKER_FILE);
            fw.write(String.valueOf(timestamp));
            fw.close();
        } catch (Exception e) {
            logWarn("Failed to save broadcast marker: " + e.getMessage());
        }
    }
    
    /**
     * Broadcast only files modified after the given timestamp.
     * Throttled to avoid I/O contention with the GPU pipeline.
     * @return number of files broadcast
     */
    private int broadcastFilesSince(File dir, long sinceTimestamp) {
        if (dir == null || !dir.exists()) return 0;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) return 0;
        
        int count = 0;
        for (File f : files) {
            if (f.lastModified() > sinceTimestamp) {
                broadcastFile(f);
                count++;
                
                // Throttle: yield between broadcasts to avoid saturating I/O
                if (count % 5 == 0) {
                    try { Thread.sleep(BROADCAST_THROTTLE_MS); } catch (InterruptedException e) { break; }
                }
            }
        }
        return count;
    }
    
    // ==================== Limit Getters/Setters ====================
    
    public long getRecordingsLimitMb() {
        return recordingsLimitMb;
    }
    
    public long getSurveillanceLimitMb() {
        return surveillanceLimitMb;
    }
    
    public long getProximityLimitMb() {
        return proximityLimitMb;
    }
    
    public long getTripsLimitMb() {
        return tripsLimitMb;
    }
    
    public void setRecordingsLimitMb(long limitMb) {
        long maxLimit = recordingsStorageType == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
        recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxLimit, limitMb));
        saveConfig();
    }
    
    public void setSurveillanceLimitMb(long limitMb) {
        long maxLimit = surveillanceStorageType == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
        surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxLimit, limitMb));
        saveConfig();
    }
    
    public void setProximityLimitMb(long limitMb) {
        long maxLimit = surveillanceStorageType == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
        proximityLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxLimit, limitMb));
        saveConfig();
    }
    
    public void setTripsLimitMb(long limitMb) {
        long maxLimit = tripsStorageType == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
        tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(maxLimit, limitMb));
        saveConfig();
    }
    
    // ==================== Storage Type Getters/Setters ====================
    
    public StorageType getRecordingsStorageType() {
        return recordingsStorageType;
    }
    
    public StorageType getSurveillanceStorageType() {
        return surveillanceStorageType;
    }
    
    public StorageType getTripsStorageType() {
        return tripsStorageType;
    }
    
    /**
     * Set recordings storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setRecordingsStorageType(StorageType type) {
        if (type == StorageType.SD_CARD) {
            // Try to mount SD card if not available (same as surveillance)
            if (!sdCardAvailable) {
                logInfo("SD card not available, attempting to mount...");
                if (!ensureSdCardMounted(true)) {  // Force mount
                    logWarn("Cannot set recordings to SD card - SD card mount failed");
                    return false;
                }
            }
        }
        
        recordingsStorageType = type;
        updateActiveDirectories();
        saveConfig();
        logInfo("Recordings storage type set to: " + type);
        
        // SOTA: Auto-enable CDR cleanup when using SD card
        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }
        
        return true;
    }
    
    /**
     * Set surveillance storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setSurveillanceStorageType(StorageType type) {
        if (type == StorageType.SD_CARD) {
            // Try to mount SD card if not available
            if (!sdCardAvailable) {
                logInfo("SD card not available, attempting to mount...");
                if (!ensureSdCardMounted(true)) {  // Force mount
                    logWarn("Cannot set surveillance to SD card - SD card mount failed");
                    return false;
                }
            }
        }
        
        surveillanceStorageType = type;
        updateActiveDirectories();
        saveConfig();
        logInfo("Surveillance storage type set to: " + type);
        
        // SOTA: Auto-enable CDR cleanup when using SD card
        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }
        
        return true;
    }
    
    /**
     * Set trips storage type (INTERNAL or SD_CARD).
     * Does NOT call autoEnableCdrCleanup() — trip files are small and don't compete with BYD dashcam space.
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setTripsStorageType(StorageType type) {
        if (type == StorageType.SD_CARD) {
            // Try to mount SD card if not available
            if (!sdCardAvailable) {
                logInfo("SD card not available, attempting to mount...");
                if (!ensureSdCardMounted(true)) {  // Force mount
                    logWarn("Cannot set trips to SD card - SD card mount failed");
                    return false;
                }
            }
        }
        
        tripsStorageType = type;
        updateActiveDirectories();
        saveConfig();
        logInfo("Trips storage type set to: " + type);
        
        return true;
    }
    
    /**
     * SOTA: Auto-enable CDR (BYD dashcam) cleanup when Overdrive uses SD card.
     * This ensures Overdrive always has space by cleaning up old dashcam files.
     */
    private void autoEnableCdrCleanup() {
        try {
            ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
            if (!cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                // Calculate recommended reserved space based on our limits
                long totalNeeded = 0;
                if (recordingsStorageType == StorageType.SD_CARD) {
                    totalNeeded += recordingsLimitMb;
                }
                if (surveillanceStorageType == StorageType.SD_CARD) {
                    totalNeeded += surveillanceLimitMb;
                }
                // Add 20% buffer
                long reservedMb = Math.max(2048, (long)(totalNeeded * 1.2));
                
                cleaner.setReservedSpaceMb(reservedMb);
                cleaner.setEnabled(true);
                logInfo("Auto-enabled CDR cleanup with " + reservedMb + "MB reserved for Overdrive");
            }
        } catch (Exception e) {
            logWarn("Could not auto-enable CDR cleanup: " + e.getMessage());
        }
    }
    
    // ==================== SD Card Info ====================
    
    public boolean isSdCardAvailable() {
        return sdCardAvailable;
    }
    
    public String getSdCardPath() {
        return sdCardPath;
    }
    
    // ==================== All Storage Locations (for scanning) ====================
    
    /**
     * Get ALL directories that may contain recordings of a given type.
     * Returns the active (configured) directory first, then any alternate locations
     * where files may exist (e.g., internal when SD card is active, or vice versa).
     * 
     * This is the single source of truth for multi-location scanning.
     * Callers should iterate all returned directories to find all files.
     */
    public List<File> getAllRecordingsDirs() {
        return getAllDirsForType(recordingsDir, internalRecordingsDir, sdCardRecordingsDir);
    }
    
    public List<File> getAllSurveillanceDirs() {
        return getAllDirsForType(surveillanceDir, internalSurveillanceDir, sdCardSurveillanceDir);
    }
    
    public List<File> getAllProximityDirs() {
        return getAllDirsForType(proximityDir, internalProximityDir, sdCardProximityDir);
    }
    
    public List<File> getAllTripsDirs() {
        return getAllDirsForType(tripsDir, internalTripsDir, sdCardTripsDir);
    }
    
    /**
     * Build a deduplicated list of directories: active first, then alternates.
     * Skips null entries and directories that match the active one.
     */
    private List<File> getAllDirsForType(File activeDir, File internalDir, File sdCardDir) {
        List<File> dirs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        // Active directory first (always included)
        if (activeDir != null) {
            dirs.add(activeDir);
            seen.add(activeDir.getAbsolutePath());
        }
        
        // Internal directory (if different from active)
        if (internalDir != null && !seen.contains(internalDir.getAbsolutePath())) {
            dirs.add(internalDir);
            seen.add(internalDir.getAbsolutePath());
        }
        
        // SD card directory (if different from active)
        if (sdCardDir != null && !seen.contains(sdCardDir.getAbsolutePath())) {
            dirs.add(sdCardDir);
            seen.add(sdCardDir.getAbsolutePath());
        }
        
        return dirs;
    }
    
    /**
     * Get available space on SD card in bytes.
     */
    public long getSdCardFreeSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                logDebug("SD card path not accessible: " + sdCardPath);
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
    
    /**
     * Get available space on internal storage in bytes.
     */
    public long getInternalFreeSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get internal free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on internal storage in bytes.
     */
    public long getInternalTotalSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Refresh SD card detection and update directories.
     * Call this when SD card may have been inserted/removed.
     */
    public void refreshSdCard() {
        discoverSdCard();
        initSdCardDirectories();
        updateActiveDirectories();
        logInfo("SD card refresh complete. Available: " + sdCardAvailable);
    }
    
    /**
     * Get max limit based on storage type.
     */
    public long getMaxLimitMb(StorageType type) {
        return type == StorageType.SD_CARD ? MAX_LIMIT_MB_SD_CARD : MAX_LIMIT_MB_INTERNAL;
    }
    
    // ==================== Storage Stats ====================
    
    /**
     * Get current size of recordings directory in bytes.
     */
    public long getRecordingsSize() {
        return getDirectorySize(recordingsDir);
    }
    
    /**
     * Get current size of surveillance directory in bytes.
     */
    public long getSurveillanceSize() {
        return getDirectorySize(surveillanceDir);
    }
    
    /**
     * Get current size of proximity directory in bytes.
     */
    public long getProximitySize() {
        return getDirectorySize(proximityDir);
    }
    
    /**
     * Get recordings count.
     */
    public int getRecordingsCount() {
        return getFileCount(recordingsDir);
    }
    
    /**
     * Get surveillance events count.
     */
    public int getSurveillanceCount() {
        return getFileCount(surveillanceDir);
    }
    
    /**
     * Get proximity events count.
     */
    public int getProximityCount() {
        return getFileCount(proximityDir);
    }
    
    /**
     * Get current size of trips directory in bytes.
     */
    public long getTripsSize() {
        return getDirectorySize(tripsDir);
    }
    
    /**
     * Get trips file count.
     */
    public int getTripsCount() {
        return getFileCount(tripsDir);
    }
    
    private long getDirectorySize(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return 0;
        
        long size = 0;
        // Count ALL files in the directory (mp4, json sidecars, tmp, etc.)
        File[] files = dir.listFiles();
        
        if (files == null) {
            // Directory might be owned by UI app - use shell to list
            files = listFilesViaShell(dir);
        }
        
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    size += f.length();
                }
            }
        }
        return size;
    }
    
    private int getFileCount(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return 0;
        
        // SOTA: Try direct listFiles first, fall back to shell if null
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        
        if (files == null) {
            // Directory might be owned by UI app - use shell to list
            files = listFilesViaShell(dir);
        }
        
        return files != null ? files.length : 0;
    }
    
    /**
     * SOTA: List files via shell command when direct access fails.
     * This handles the case where UI app owns the directory but daemon needs to list files.
     */
    private File[] listFilesViaShell(File dir) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"ls", dir.getAbsolutePath()});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            
            java.util.List<File> files = new java.util.ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.endsWith(".mp4")) {
                    files.add(new File(dir, line));
                }
            }
            reader.close();
            p.waitFor();
            
            logDebug("listFilesViaShell: found " + files.size() + " files in " + dir.getName());
            return files.toArray(new File[0]);
        } catch (Exception e) {
            logWarn("listFilesViaShell failed: " + e.getMessage());
            return new File[0];
        }
    }
    
    // ==================== Cleanup Logic ====================
    
    /**
     * Ensure recordings directory is within size limit.
     * Deletes oldest files until under limit.
     * 
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureRecordingsSpace(long reserveBytes) {
        return ensureSpace(recordingsDir, recordingsLimitMb * 1024 * 1024, reserveBytes);
    }
    
    /**
     * Ensure surveillance directory is within size limit.
     * Deletes oldest files until under limit.
     * 
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureSurveillanceSpace(long reserveBytes) {
        return ensureSpace(surveillanceDir, surveillanceLimitMb * 1024 * 1024, reserveBytes);
    }
    
    /**
     * Ensure proximity directory is within size limit.
     * Deletes oldest files until under limit.
     * 
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureProximitySpace(long reserveBytes) {
        return ensureSpace(proximityDir, proximityLimitMb * 1024 * 1024, reserveBytes);
    }
    
    /**
     * Ensure trips directory is within size limit.
     * Deletes oldest files until under limit.
     * 
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureTripsSpace(long reserveBytes) {
        return ensureSpace(tripsDir, tripsLimitMb * 1024 * 1024, reserveBytes);
    }
    
    /**
     * Generic cleanup method for any directory.
     * SOTA: Uses shell fallback for listing/deleting when directory is owned by UI app.
     */
    private boolean ensureSpace(File dir, long limitBytes, long reserveBytes) {
        if (!dir.exists() || !dir.isDirectory()) {
            dir.mkdirs();
            return true;
        }
        
        long targetSize = limitBytes - reserveBytes;
        if (targetSize < 0) targetSize = 0;
        
        long currentSize = getDirectorySize(dir);
        
        if (currentSize <= targetSize) {
            return true;  // Already within limit
        }
        
        // Get all video files sorted by modification time (oldest first)
        // SOTA: Try direct listFiles first, fall back to shell if null
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        
        if (files == null) {
            // Directory might be owned by UI app - use shell to list
            files = listFilesViaShell(dir);
        }
        
        if (files == null || files.length == 0) {
            return true;
        }
        
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        
        int deletedCount = 0;
        long deletedSize = 0;
        
        for (File file : files) {
            if (currentSize <= targetSize) {
                break;  // We're under the limit now
            }
            
            long fileSize = file.length();
            
            // SOTA: Try Java delete first, fall back to shell rm
            boolean deleted = file.delete();
            if (!deleted) {
                deleted = deleteFileViaShell(file);
            }
            
            if (deleted) {
                currentSize -= fileSize;
                deletedCount++;
                deletedSize += fileSize;
                logInfo("Deleted old file: " + file.getName() + " (" + formatSize(fileSize) + ")");
                
                // SOTA: Also delete JSON sidecar (event timeline) if it exists
                String jsonName = file.getName().replace(".mp4", ".json");
                File jsonSidecar = new File(dir, jsonName);
                if (jsonSidecar.exists()) {
                    if (!jsonSidecar.delete()) {
                        deleteFileViaShell(jsonSidecar);
                    }
                }
            } else {
                logWarn("Failed to delete: " + file.getName());
            }
        }
        
        if (deletedCount > 0) {
            logInfo("Cleanup complete: deleted " + deletedCount + " files (" + formatSize(deletedSize) + ")");
        }
        
        // If still over limit and directory is on SD card, try freeing space via CDR cleanup
        if (currentSize > targetSize && sdCardAvailable && dir.getAbsolutePath().startsWith(sdCardPath)) {
            try {
                ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
                if (cleaner.isEnabled()) {
                    logInfo("Overdrive cleanup insufficient on SD card — triggering CDR cleanup");
                    cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                logWarn("CDR fallback cleanup failed: " + e.getMessage());
            }
        }
        
        return currentSize <= targetSize;
    }
    
    /**
     * SOTA: Delete file via shell command when Java delete fails.
     */
    private boolean deleteFileViaShell(File file) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rm", file.getAbsolutePath()});
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            logWarn("deleteFileViaShell failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Run cleanup on both directories.
     */
    public void runCleanup() {
        ensureRecordingsSpace(0);
        ensureSurveillanceSpace(0);
        ensureProximitySpace(0);
        ensureTripsSpace(0);
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
    
    public static long getMinLimitMb() {
        return MIN_LIMIT_MB;
    }
    
    public static long getMaxLimitMb() {
        return MAX_LIMIT_MB_INTERNAL;
    }
    
    public static long getMaxLimitMbInternal() {
        return MAX_LIMIT_MB_INTERNAL;
    }
    
    public static long getMaxLimitMbSdCard() {
        return MAX_LIMIT_MB_SD_CARD;
    }
    
    // ==================== Event-Driven Cleanup (SOTA) ====================
    
    /**
     * Called after a recording file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * This is the SOTA approach - cleanup after each file save rather than
     * only at recording start, preventing storage overflow during long sessions.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onRecordingFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(recordingsDir);
        
        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()
        
        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(recordingsDir);
                    
                    long currentSize = getRecordingsSize();
                    long limitBytes = recordingsLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Recording file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureRecordingsSpace(0);
                    } else {
                        logDebug("Recording file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async recording cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a surveillance event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onSurveillanceFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(surveillanceDir);
        
        // FIX: Removed broadcastRecentFiles() call that re-scanned ALL files modified
        // in the last 60 seconds. This caused duplicate MediaScanner broadcasts —
        // if two events saved 20 seconds apart, the second save re-broadcast the first.
        // Over days of parking, this list grows to hundreds of files, causing massive
        // CPU spikes on every new event. The specific file is already broadcast by
        // onFileSaved() → broadcastFile(file) before this method is called.
        
        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(surveillanceDir);
                    
                    long currentSize = getSurveillanceSize();
                    long limitBytes = surveillanceLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Surveillance file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSurveillanceSpace(0);
                    } else {
                        logDebug("Surveillance file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async surveillance cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a proximity event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onProximityFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(proximityDir);
        
        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()
        
        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(proximityDir);
                    
                    long currentSize = getProximitySize();
                    long limitBytes = proximityLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Proximity file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureProximitySpace(0);
                    } else {
                        logDebug("Proximity file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async proximity cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a trip telemetry file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the telemetry recording thread.
     */
    public void onTripFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(tripsDir);
        
        asyncCleanupExecutor.execute(() -> {
            synchronized (cleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(tripsDir);
                    
                    long currentSize = getTripsSize();
                    long limitBytes = tripsLimitMb * 1024 * 1024;
                    
                    if (currentSize > limitBytes) {
                        logInfo("Trip file saved - triggering cleanup (current=" + 
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureTripsSpace(0);
                    } else {
                        logDebug("Trip file saved - within limits (" + 
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async trips cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Fix directory permissions so UI app can read files.
     * Note: chmod doesn't work on Android FUSE filesystem, but we keep Java API calls.
     */
    private void fixDirectoryPermissions(File dir) {
        if (dir != null && dir.exists()) {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        }
    }
    
    /**
     * Make all .mp4 files in directory readable by all.
     * Note: chmod doesn't work on Android FUSE filesystem - rely on MediaStore instead.
     */
    private void makeFilesReadable(File dir) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) {
            files = listFilesViaShell(dir);
        }
        
        if (files != null) {
            for (File f : files) {
                f.setReadable(true, false);
            }
        }
    }
    
    /**
     * Make a single file readable by all users.
     * Note: chmod doesn't work on Android FUSE - rely on MediaStore for cross-UID access.
     */
    public void makeFileReadable(File file) {
        if (file == null || !file.exists()) return;
        file.setReadable(true, false);
    }
    
    /**
     * Force Android MediaScanner to index a file so it appears in MediaStore
     * and becomes visible to standard apps with READ_EXTERNAL_STORAGE.
     * 
     * CRITICAL: Both methods are required on BYD's Android 10:
     * - `am broadcast MEDIA_SCANNER_SCAN_FILE` refreshes the FUSE permission cache
     *   so that File.listFiles() on SD card paths can see the file. Without this,
     *   the RecordingsApiHandler's scanDirectory() gets incomplete file listings.
     * - `content insert` directly inserts into MediaStore for cross-UID visibility
     *   (needed for the UI app running as a different UID).
     */
    private void broadcastFile(File file) {
        if (file == null || !file.exists()) return;
        
        String path = file.getAbsolutePath();
        
        try {
            // Method 1: FUSE cache refresh via MediaScanner intent
            // Required for File.listFiles() to work on SD card FUSE paths
            Runtime.getRuntime().exec(new String[]{
                "am", "broadcast",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", "file://" + path
            });
            
            // Method 2: Direct MediaStore insert for cross-UID visibility
            Runtime.getRuntime().exec(new String[]{
                "content", "insert",
                "--uri", "content://media/external/video/media",
                "--bind", "_data:s:" + path
            });
            
            logDebug("Broadcast file to MediaScanner: " + file.getName());
        } catch (Exception e) {
            logWarn("Failed to broadcast file: " + e.getMessage());
        }
    }
    
    /**
     * SOTA: Fix permissions and broadcast a single file after it's saved.
     * Call this immediately after closing a video file.
     * @param file The video file that was just saved
     */
    public void onFileSaved(File file) {
        if (file == null || !file.exists()) {
            logWarn("onFileSaved: file is null or doesn't exist");
            return;
        }
        
        logInfo("Processing saved file: " + file.getName() + " (" + formatSize(file.length()) + ")");
        
        // 1. Make file readable by all (chmod 666)
        makeFileReadable(file);
        
        // 2. Broadcast to MediaScanner
        broadcastFile(file);
        
        // 3. Trigger appropriate cleanup based on directory
        String path = file.getAbsolutePath();
        if (path.contains(RECORDINGS_SUBDIR)) {
            onRecordingFileSaved();
        } else if (path.contains(SURVEILLANCE_SUBDIR)) {
            onSurveillanceFileSaved();
        } else if (path.contains(PROXIMITY_SUBDIR)) {
            onProximityFileSaved();
        } else if (path.contains(TRIPS_SUBDIR)) {
            onTripFileSaved();
        }
    }
    
    /**
     * Broadcast all recent files in a directory to MediaScanner.
     * @param dir Directory to scan
     * @param maxAgeMs Only broadcast files modified within this time (ms)
     */
    private void broadcastRecentFiles(File dir, long maxAgeMs) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files != null) {
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (now - f.lastModified() < maxAgeMs) {
                    broadcastFile(f);
                }
            }
        }
    }
    
    // ==================== Periodic Background Cleanup ====================
    
    /**
     * Start periodic cleanup for long recording sessions.
     * Runs every 30 seconds while recording is active.
     */
    public void startPeriodicCleanup() {
        if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
            return;  // Already running
        }
        
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "StorageCleanup");
            t.setDaemon(true);
            return t;
        });
        
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                if (recordingActive.get()) {
                    synchronized (cleanupLock) {
                        long currentSize = getRecordingsSize();
                        long limitBytes = recordingsLimitMb * 1024 * 1024;
                        if (currentSize > limitBytes * 0.9) {  // 90% threshold
                            logInfo("Periodic cleanup: recordings at " + 
                                formatSize(currentSize) + "/" + formatSize(limitBytes));
                            ensureRecordingsSpace(50 * 1024 * 1024);  // Reserve 50MB
                        }
                    }
                }
                
                if (surveillanceActive.get()) {
                    synchronized (cleanupLock) {
                        long currentSize = getSurveillanceSize();
                        long limitBytes = surveillanceLimitMb * 1024 * 1024;
                        if (currentSize > limitBytes * 0.9) {  // 90% threshold
                            logInfo("Periodic cleanup: surveillance at " + 
                                formatSize(currentSize) + "/" + formatSize(limitBytes));
                            ensureSurveillanceSpace(50 * 1024 * 1024);  // Reserve 50MB
                        }
                    }
                }
            } catch (Exception e) {
                logWarn("Periodic cleanup error: " + e.getMessage());
            }
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        logInfo("Started periodic storage cleanup (interval=" + CLEANUP_INTERVAL_SECONDS + "s)");
    }
    
    /**
     * Stop periodic cleanup.
     */
    public void stopPeriodicCleanup() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
            }
            cleanupScheduler = null;
            logInfo("Stopped periodic storage cleanup");
        }
    }

    /**
     * Start SD card mount watchdog for sentry mode.
     * Periodically checks if the SD card is still mounted and re-mounts it if the
     * system unmounted it (BYD/Android tends to unmount SD when ACC is off).
     *
     * Call this when entering sentry mode with SD card storage selected.
     */
    public void startSdCardWatchdog() {
        // Start watchdog if ANY storage type uses SD card (not just surveillance).
        // The watchdog keeps the SD card mounted so recordings, events, and trips
        // remain accessible via the HTTP server even when surveillance is suppressed.
        boolean anyOnSd = surveillanceStorageType == StorageType.SD_CARD ||
                          recordingsStorageType == StorageType.SD_CARD ||
                          tripsStorageType == StorageType.SD_CARD;
        if (!anyOnSd) {
            logDebug("SD watchdog not needed - no storage type uses SD card");
            return;
        }

        stopSdCardWatchdog();  // Stop any existing watchdog first

        sdCardWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SdCardWatchdog");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);  // Normal priority - mount is critical
            return t;
        });

        sdCardWatchdog.scheduleAtFixedRate(() -> {
            try {
                if (!isSdCardMounted()) {
                    sdWatchdogConsecutiveFailures++;
                    
                    // Only log verbosely for the first few failures, then quiet down
                    boolean shouldLog = sdWatchdogConsecutiveFailures <= SD_WATCHDOG_MAX_VERBOSE_FAILURES ||
                                        sdWatchdogConsecutiveFailures % SD_WATCHDOG_QUIET_LOG_INTERVAL == 0;
                    
                    if (shouldLog) {
                        logWarn("SD card watchdog: card unmounted, attempting remount... (attempt #" + 
                            sdWatchdogConsecutiveFailures + ")");
                    }
                    
                    if (ensureSdCardMounted(true)) {
                        logInfo("SD card watchdog: remounted successfully after " + 
                            sdWatchdogConsecutiveFailures + " attempts");
                        sdWatchdogConsecutiveFailures = 0;

                        // Restore SD card directories now that card is back
                        initSdCardDirectories();
                        updateActiveDirectories();

                        // Update running sentry engine's output directory
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getSentry() != null) {
                                pipeline.getSentry().setEventOutputDir(getSurveillanceDir());
                                logInfo("SD card watchdog: updated sentry output dir to " + 
                                    getSurveillanceDir().getAbsolutePath());
                            }
                        } catch (Exception e) {
                            logWarn("SD card watchdog: could not update sentry dir: " + e.getMessage());
                        }
                    } else if (shouldLog) {
                        logError("SD card watchdog: remount FAILED - surveillance may use internal fallback");
                    }
                } else {
                    // Card is mounted — reset failure counter
                    if (sdWatchdogConsecutiveFailures > 0) {
                        logInfo("SD card watchdog: card is mounted again");
                        sdWatchdogConsecutiveFailures = 0;
                    }
                }
            } catch (Exception e) {
                logWarn("SD card watchdog error: " + e.getMessage());
            }
        }, SD_WATCHDOG_INTERVAL_SECONDS, SD_WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);

        logInfo("Started SD card mount watchdog (interval=" + SD_WATCHDOG_INTERVAL_SECONDS + "s)");
    }

    /**
     * Stop SD card mount watchdog (call when exiting sentry mode or ACC comes back on).
     */
    public void stopSdCardWatchdog() {
        if (sdCardWatchdog != null) {
            sdCardWatchdog.shutdown();
            try {
                if (!sdCardWatchdog.awaitTermination(3, TimeUnit.SECONDS)) {
                    sdCardWatchdog.shutdownNow();
                }
            } catch (InterruptedException e) {
                sdCardWatchdog.shutdownNow();
            }
            sdCardWatchdog = null;
            logInfo("Stopped SD card mount watchdog");
        }
    }
    
    /**
     * Set recording active state (for periodic cleanup).
     */
    public void setRecordingActive(boolean active) {
        boolean wasActive = recordingActive.getAndSet(active);
        if (active && !wasActive) {
            startPeriodicCleanup();
        } else if (!active && wasActive && !surveillanceActive.get()) {
            stopPeriodicCleanup();
        }
    }
    
    /**
     * Set surveillance active state (for periodic cleanup).
     */
    public void setSurveillanceActive(boolean active) {
        boolean wasActive = surveillanceActive.getAndSet(active);
        if (active && !wasActive) {
            startPeriodicCleanup();
        } else if (!active && wasActive && !recordingActive.get()) {
            stopPeriodicCleanup();
        }
    }
    
    /**
     * Check if recording is active.
     */
    public boolean isRecordingActive() {
        return recordingActive.get();
    }
    
    /**
     * Check if surveillance is active.
     */
    public boolean isSurveillanceActive() {
        return surveillanceActive.get();
    }
    
    /**
     * Shutdown all background threads.
     * Call this when the app is terminating.
     */
    public void shutdown() {
        stopPeriodicCleanup();
        stopSdCardWatchdog();
        
        asyncCleanupExecutor.shutdown();
        try {
            if (!asyncCleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                asyncCleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncCleanupExecutor.shutdownNow();
        }
        
        logInfo("StorageManager shutdown complete");
    }
}
