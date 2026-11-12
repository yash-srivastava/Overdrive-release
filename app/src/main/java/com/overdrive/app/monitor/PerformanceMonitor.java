package com.overdrive.app.monitor;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SOTA Performance Monitor - Tracks CPU, Memory, GPU, and app-specific metrics.
 * 
 * Collects metrics at configurable intervals and maintains history for charting.
 * Optimized for minimal overhead while providing comprehensive instrumentation.
 * 
 * ON-DEMAND ARCHITECTURE:
 * - CPU/GPU/Memory polling only runs when clients are actively viewing the performance page
 * - Uses reference counting to track active clients
 * - Auto-starts when first client connects, auto-stops when last client disconnects
 * - Clients must send heartbeats to maintain connection (timeout: 10 seconds)
 */
public class PerformanceMonitor {
    
    private static final String TAG = "PerformanceMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Singleton
    private static PerformanceMonitor instance;
    private static final Object lock = new Object();
    
    // Configuration
    // HISTORY_SIZE × SAMPLE_INTERVAL_MS = retained window. At 2s × 60 that's a
    // 2-minute chart, plenty for a live perf page.
    private static final int HISTORY_SIZE = 60;
    // 2s, not 1s. A full collectMetrics() does blocking sysfs/proc I/O — the
    // thermal sweep alone reads dozens of /sys/class/thermal zones. On the BYD
    // head-unit (48 thermal zones, slow sysfs) a 1s sampler couldn't keep up and
    // pegged its own thread; 2s halves that load and is still real-time enough
    // for a human-watched dashboard.
    private static final long SAMPLE_INTERVAL_MS = 2000;
    private static final long CLIENT_TIMEOUT_MS = 10_000;  // 10 seconds heartbeat timeout
    private static final long CLEANUP_INTERVAL_MS = 5_000;  // Check for stale clients every 5s
    
    // Context
    private Context context;
    private ActivityManager activityManager;
    private int pid;
    private int uid;
    
    // Metrics history (circular buffers)
    private final LinkedList<PerformanceSnapshot> history = new LinkedList<>();
    private final AtomicReference<PerformanceSnapshot> latestSnapshot = new AtomicReference<>();
    
    // CPU tracking
    private long lastCpuTime = 0;
    private long lastAppCpuTime = 0;
    private long lastCpuTimeForApp = 0;  // Tracks CPU time baseline for app CPU calculation
    private long lastIdleTime = 0;

    // GPU busy tracking — kgsl exposes "busy total" cumulative ticks on most
    // MSM kernels (filename varies: gpu_busy / gpubusy / gpu_busy_time). Usage
    // must be computed as a delta between consecutive samples.
    // -1 sentinel means "no prior sample yet, can't compute delta".
    private long lastGpuBusy = -1;
    private long lastGpuTotal = -1;
    // Set by readGpuMaxFrequency() on each call: true if a real sysfs max-freq
    // path resolved, false if it fell through to the hardcoded 650 default.
    // Lets the freq-ratio fallback tag its estimate honestly (issue #173).
    // Sampler-thread-confined (collectMetrics runs on a single scheduler task).
    private boolean lastGpuMaxFreqWasReal = false;
    // Cache the resolved busy-counter path, including a confirmed missing path.
    // The kernel's sysfs topology is stable for the daemon's lifetime.
    private java.io.File resolvedGpuBusyFile = null;
    private boolean gpuBusyPathAbsent = false;
    private boolean gpuBusyResolutionLogged = false;
    // Resolve the GPU devfreq directory once instead of enumerating sysfs on
    // every dashboard sample. null = unresolved, sentinel = no matching GPU.
    private java.io.File[] gpuDevfreqDevices = null;

    // Cache the resolved CPU/GPU thermal-zone /temp paths. The original code
    // re-scanned up to 30 thermal_zone* dirs — opening + reading the /type file
    // of each — on EVERY sample to rediscover which zone is the CPU/GPU. On the
    // BYD head-unit (48 zones, slow sysfs) that sweep dominated the sampler's
    // CPU. The zone topology never changes at runtime, so resolve the matching
    // /temp file once and just read that single file thereafter. Sentinels:
    // null = not yet resolved; NOT_FOUND = resolved-but-absent (don't re-scan).
    private static final java.io.File TEMP_PATH_NOT_FOUND = new java.io.File("");
    private java.io.File resolvedCpuTempFile = null;
    private java.io.File resolvedGpuTempFile = null;
    
    // Scheduler
    private ScheduledExecutorService scheduler;
    private volatile boolean isRunning = false;
    
    // SOTA: Client connection tracking for on-demand polling
    private final java.util.concurrent.ConcurrentHashMap<String, Long> activeClients = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledExecutorService clientCleanupScheduler;
    
    private PerformanceMonitor() {}
    
    public static PerformanceMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new PerformanceMonitor();
                }
            }
        }
        return instance;
    }
    
    // ==================== LIFECYCLE ====================
    
    public void init(Context context) {
        this.context = context;
        if (context != null) {
            this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        }
        this.pid = Process.myPid();
        this.uid = Process.myUid();
        logger.info("PerformanceMonitor initialized (PID: " + pid + ", UID: " + uid + ")");
    }
    
    public void start() {
        if (isRunning) return;
        isRunning = true;
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerfMonitor");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        // A sysfs/proc sweep can exceed the sample interval on slower BYD head units.
        // Fixed-rate scheduling immediately runs missed samples back-to-back,
        // keeping the sampler busy instead of giving the UI/camera time to run.
        // Fixed-delay guarantees a full idle interval after each sweep.
        scheduler.scheduleWithFixedDelay(this::collectMetrics, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("Performance monitoring started");
    }
    
    public void stop() {
        isRunning = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        logger.info("Performance monitoring stopped");
    }
    
    // ==================== SOTA: ON-DEMAND CLIENT MANAGEMENT ====================
    
    /**
     * Register a client connection. Starts monitoring if this is the first client.
     * @param clientId Unique identifier for the client (e.g., session ID or IP:port)
     */
    public void clientConnected(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            clientId = "anonymous-" + System.currentTimeMillis();
        }
        
        boolean wasEmpty = activeClients.isEmpty();
        activeClients.put(clientId, System.currentTimeMillis());
        
        logger.debug("Client connected: " + clientId + " (total: " + activeClients.size() + ")");
        
        // Start monitoring if this is the first client
        if (wasEmpty) {
            logger.info("First client connected - starting performance monitoring");
            start();
            startClientCleanup();
        }
    }
    
    /**
     * Update client heartbeat timestamp.
     * @param clientId Client identifier
     */
    public void clientHeartbeat(String clientId) {
        if (clientId != null && activeClients.containsKey(clientId)) {
            activeClients.put(clientId, System.currentTimeMillis());
        } else if (clientId != null) {
            // New client via heartbeat - register it
            clientConnected(clientId);
        }
    }
    
    /**
     * Unregister a client connection. Stops monitoring if this was the last client.
     * @param clientId Client identifier
     */
    public void clientDisconnected(String clientId) {
        if (clientId == null) return;
        
        Long removed = activeClients.remove(clientId);
        if (removed != null) {
            logger.debug("Client disconnected: " + clientId + " (remaining: " + activeClients.size() + ")");
            
            // Stop monitoring if no clients remain
            if (activeClients.isEmpty()) {
                logger.info("Last client disconnected - stopping performance monitoring");
                stop();
                stopClientCleanup();
            }
        }
    }
    
    /**
     * Start the client cleanup scheduler to remove stale clients.
     */
    private void startClientCleanup() {
        if (clientCleanupScheduler != null) return;
        
        clientCleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerfClientCleanup");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        clientCleanupScheduler.scheduleAtFixedRate(this::cleanupStaleClients, 
            CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Stop the client cleanup scheduler.
     */
    private void stopClientCleanup() {
        if (clientCleanupScheduler != null) {
            clientCleanupScheduler.shutdownNow();
            clientCleanupScheduler = null;
        }
    }
    
    /**
     * Remove clients that haven't sent a heartbeat within the timeout period.
     */
    private void cleanupStaleClients() {
        long now = System.currentTimeMillis();
        java.util.List<String> staleClients = new java.util.ArrayList<>();
        
        for (java.util.Map.Entry<String, Long> entry : activeClients.entrySet()) {
            if (now - entry.getValue() > CLIENT_TIMEOUT_MS) {
                staleClients.add(entry.getKey());
            }
        }
        
        for (String clientId : staleClients) {
            logger.debug("Removing stale client: " + clientId);
            clientDisconnected(clientId);
        }
    }
    
    /**
     * Get the number of active clients.
     */
    public int getActiveClientCount() {
        return activeClients.size();
    }
    
    /**
     * Check if any clients are connected.
     */
    public boolean hasActiveClients() {
        return !activeClients.isEmpty();
    }
    
    // ==================== METRICS COLLECTION ====================
    
    private void collectMetrics() {
        try {
            PerformanceSnapshot snapshot = new PerformanceSnapshot();
            snapshot.timestamp = System.currentTimeMillis();
            
            // CPU metrics
            collectCpuMetrics(snapshot);
            
            // Memory metrics
            collectMemoryMetrics(snapshot);
            
            // GPU metrics (estimated from thermal/frequency)
            collectGpuMetrics(snapshot);
            
            // App-specific metrics
            collectAppMetrics(snapshot);
            
            // Store in history
            synchronized (history) {
                history.addLast(snapshot);
                while (history.size() > HISTORY_SIZE) {
                    history.removeFirst();
                }
            }
            latestSnapshot.set(snapshot);
            
        } catch (Exception e) {
            logger.error("Failed to collect metrics", e);
        }
    }
    
    private void collectCpuMetrics(PerformanceSnapshot snapshot) {
        try {
            // Read /proc/stat for system CPU
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String line = reader.readLine();
            reader.close();
            
            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.split("\\s+");
                long user = Long.parseLong(parts[1]);
                long nice = Long.parseLong(parts[2]);
                long system = Long.parseLong(parts[3]);
                long idle = Long.parseLong(parts[4]);
                long iowait = Long.parseLong(parts[5]);
                long irq = Long.parseLong(parts[6]);
                long softirq = Long.parseLong(parts[7]);
                
                long totalCpu = user + nice + system + idle + iowait + irq + softirq;
                long totalIdle = idle + iowait;
                
                if (lastCpuTime > 0) {
                    long cpuDelta = totalCpu - lastCpuTime;
                    long idleDelta = totalIdle - lastIdleTime;
                    
                    if (cpuDelta > 0) {
                        snapshot.cpuUsagePercent = 100.0 * (cpuDelta - idleDelta) / cpuDelta;
                    }
                }
                
                lastCpuTime = totalCpu;
                lastIdleTime = totalIdle;
            }
            
            // Read /proc/[pid]/stat for app CPU
            RandomAccessFile appReader = new RandomAccessFile("/proc/" + pid + "/stat", "r");
            String appLine = appReader.readLine();
            appReader.close();
            
            if (appLine != null) {
                String[] parts = appLine.split("\\s+");
                if (parts.length > 14) {
                    long utime = Long.parseLong(parts[13]);
                    long stime = Long.parseLong(parts[14]);
                    long appCpuTime = utime + stime;
                    
                    if (lastAppCpuTime > 0 && lastCpuTime > 0) {
                        long appDelta = appCpuTime - lastAppCpuTime;
                        long cpuDelta = lastCpuTime - lastCpuTimeForApp;

                        if (cpuDelta > 0) {
                            // TOP-STYLE app CPU %: 100% = one fully-busy core,
                            // ceiling = numCores × 100 (e.g. 800% on 8 cores).
                            // This matches what `top`/Android profilers report
                            // in their per-process %CPU column, so the dashboard
                            // and a live `top` agree.
                            //
                            // cpuDelta is the AGGREGATE /proc/stat jiffy delta
                            // (summed across all cores), so appDelta/cpuDelta is
                            // the WHOLE-DEVICE fraction (0..1). Multiplying by
                            // numCores rescales it to per-core units.
                            //
                            // THE ORIGINAL BUG was NOT this multiply — it was the
                            // clamp below comparing this per-core number against
                            // cpuUsagePercent (a whole-device 0..100 value).
                            // 37%-per-core clamped against an 80%-whole-device
                            // system reading collapsed to ≈system%. The fix is to
                            // clamp in MATCHING units: app-per-core must be ≤
                            // system-per-core (= cpuUsagePercent × numCores), the
                            // honest sanity bound. It only corrects sampling skew
                            // between the two separate /proc reads.
                            int numCores = Runtime.getRuntime().availableProcessors();
                            snapshot.appCpuUsagePercent = 100.0 * appDelta / cpuDelta * numCores;
                            double systemPerCore = snapshot.cpuUsagePercent * numCores;
                            snapshot.appCpuUsagePercent = Math.min(systemPerCore,
                                Math.max(0.0, snapshot.appCpuUsagePercent));
                        }
                    }
                    lastAppCpuTime = appCpuTime;
                    lastCpuTimeForApp = lastCpuTime;
                }
            }
            
            // CPU frequency (current + static hardware max, for throttle detection)
            snapshot.cpuFreqMhz = readCpuFrequency();
            snapshot.cpuMaxFreqMhz = readCpuMaxFrequency();

            // CPU temperature
            snapshot.cpuTempCelsius = readCpuTemperature();
            
        } catch (Exception e) {
            logger.debug("CPU metrics error: " + e.getMessage());
        }
    }

    
    private void collectMemoryMetrics(PerformanceSnapshot snapshot) {
        try {
            // System memory from /proc/meminfo
            BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            long memTotal = 0, memFree = 0, memAvailable = 0, buffers = 0, cached = 0;
            
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    long value = Long.parseLong(parts[1]); // in KB
                    switch (parts[0]) {
                        case "MemTotal:": memTotal = value; break;
                        case "MemFree:": memFree = value; break;
                        case "MemAvailable:": memAvailable = value; break;
                        case "Buffers:": buffers = value; break;
                        case "Cached:": cached = value; break;
                    }
                }
            }
            reader.close();
            
            snapshot.memTotalMb = memTotal / 1024.0;
            snapshot.memUsedMb = (memTotal - memAvailable) / 1024.0;
            snapshot.memUsagePercent = 100.0 * (memTotal - memAvailable) / memTotal;
            
            // App memory via Debug API
            Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memInfo);
            
            snapshot.appMemoryMb = memInfo.getTotalPss() / 1024.0;  // PSS in KB -> MB
            snapshot.appNativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0);
            snapshot.appJavaHeapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0);
            
        } catch (Exception e) {
            logger.debug("Memory metrics error: " + e.getMessage());
        }
    }
    
    private void collectGpuMetrics(PerformanceSnapshot snapshot) {
        try {
            // Auto-detect GPU via /sys/class/devfreq/.
            // Modern kernels expose GPU as a generic 'devfreq' device — works
            // on MediaTek, Snapdragon, Exynos.
            java.io.File[] devices = gpuDevfreqDevices;
            if (devices == null) {
                java.io.File[] discovered = new java.io.File("/sys/class/devfreq/").listFiles();
                if (discovered == null) discovered = new java.io.File[0];
                // Cache the directory listing; frequency and load are still
                // read live so thermal/governor changes remain visible.
                gpuDevfreqDevices = discovered;
                devices = discovered;
            }
            if (devices.length > 0) {
                    for (java.io.File device : devices) {
                        String name = device.getName().toLowerCase();
                        if (name.contains("kgsl") ||   // Adreno (Qualcomm)
                            name.contains("mali") ||   // Mali (ARM)
                            name.contains("gpu")  ||   // Generic
                            name.contains("g3d")) {    // PowerVR/Other

                            long freq = readLongFromFile(new java.io.File(device, "cur_freq"));
                            if (freq > 0) {
                                snapshot.gpuFreqMhz = normalizeFrequency(freq);
                            }

                            long load = readLongFromFile(new java.io.File(device, "load"));
                            if (load >= 0 && load <= 100) {
                                snapshot.gpuUsagePercent = load;
                                snapshot.gpuUsageSource = "devfreq_load";
                                break;
                            }

                            long busyPercent = readLongFromFile(new java.io.File(device, "gpu_busy_percentage"));
                            if (busyPercent >= 0 && busyPercent <= 100) {
                                snapshot.gpuUsagePercent = busyPercent;
                                snapshot.gpuUsageSource = "devfreq_load";
                                break;
                            }

                            if (snapshot.gpuFreqMhz > 0) break;
                        }
                    }
            }

            // Legacy kgsl freq path if devfreq didn't yield one.
            if (snapshot.gpuFreqMhz == 0) {
                long freq = readLongFromFile(new java.io.File("/sys/class/kgsl/kgsl-3d0/gpuclk"));
                if (freq > 0) {
                    snapshot.gpuFreqMhz = normalizeFrequency(freq);
                }
            }

            // kgsl "busy total" counters. Two semantics in the wild:
            //  (a) cumulative-since-boot — values monotonically grow; usage is
            //      delta(busy)/delta(total) between samples.
            //  (b) reset-on-read — each read returns busy/total for the
            //      interval since the previous read; usage is busy/total of
            //      THIS sample directly.
            // Adreno 610 on the BYD head-unit is (b); pinning to (a) silently
            // failed the monotonicity check and bled through to the freq-ratio
            // fallback, which inflates at idle (governor parks at ~257/650 MHz
            // → ~40% baseline) and pegs near 100% on any burst.
            // A measured 0% means the GPU is idle, not that the reading failed.
            // Only probe the next sysfs source when the prior source is unavailable.
            if ("unavailable".equals(snapshot.gpuUsageSource)) {
                java.io.File gpuBusyFile = resolveGpuBusyFile();
                if (gpuBusyFile != null) {
                    try (BufferedReader br = new BufferedReader(new FileReader(gpuBusyFile))) {
                        String line = br.readLine();
                        if (line != null) {
                            String[] parts = line.trim().split("\\s+");
                            if (parts.length >= 2) {
                                long busy = Long.parseLong(parts[0]);
                                long total = Long.parseLong(parts[1]);
                                if (total > 0) {
                                    boolean haveLast = lastGpuBusy >= 0 && lastGpuTotal >= 0;
                                    boolean cumulative = haveLast
                                            && busy >= lastGpuBusy
                                            && total >= lastGpuTotal
                                            && total > lastGpuTotal;
                                    double usage = -1;
                                    if (cumulative) {
                                        long dBusy = busy - lastGpuBusy;
                                        long dTotal = total - lastGpuTotal;
                                        if (dTotal > 0) {
                                            usage = (dBusy * 100.0) / dTotal;
                                        }
                                    } else {
                                        // Reset-on-read driver, or first sample.
                                        // Using the raw ratio is correct for (b)
                                        // and for (a)'s very first sample is at
                                        // worst a one-tick boot average — better
                                        // than falling through to freq-ratio.
                                        usage = (busy * 100.0) / total;
                                    }
                                    if (usage >= 0) {
                                        if (usage > 100) usage = 100;
                                        snapshot.gpuUsagePercent = usage;
                                        snapshot.gpuUsageSource = "busy_counter";
                                    }
                                    lastGpuBusy = busy;
                                    lastGpuTotal = total;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            // If still no usage, estimate from frequency ratio. This is what
            // the original code did and what the user is used to seeing —
            // governor's clock ratio, not real busy time, but always populated.
            if ("unavailable".equals(snapshot.gpuUsageSource) && snapshot.gpuFreqMhz > 0) {
                double maxFreq = readGpuMaxFrequency();
                if (maxFreq > 0) {
                    snapshot.gpuUsagePercent = Math.min(100, (snapshot.gpuFreqMhz / maxFreq) * 100);
                    // Tag provenance (issue #173): a real max-freq read is a true
                    // ratio-of-max; the 650 default makes gpuclk/650 a fabricated
                    // constant (e.g. 600/650 = 92.3% forever on 2602 firmware).
                    snapshot.gpuUsageSource = lastGpuMaxFreqWasReal
                            ? "freq_ratio_estimate"
                            : "freq_ratio_hardcoded";
                }
            }

            // GPU temperature is independent of usage.
            snapshot.gpuTempCelsius = readGpuTemperature();

        } catch (Exception e) {
            logger.debug("GPU metrics error: " + e.getMessage());
        }
    }
    
    /**
     * Read GPU max frequency for load estimation. Returns 650 MHz as a
     * conservative Adreno default if no sysfs path resolves.
     */
    private double readGpuMaxFrequency() {
        lastGpuMaxFreqWasReal = false;
        String[] paths = {
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
            "/sys/class/devfreq/kgsl-3d0/max_freq",
            "/sys/devices/platform/mali.0/max_clock",
            "/sys/class/misc/mali0/device/max_clock",
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies"
        };
        for (String path : paths) {
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String line = br.readLine();
                if (line == null || line.trim().isEmpty()) continue;
                if (path.contains("available_frequencies")) {
                    long maxRaw = 0;
                    for (String f : line.trim().split("\\s+")) {
                        try {
                            long v = Long.parseLong(f.trim());
                            if (v > maxRaw) maxRaw = v;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (maxRaw > 0) { lastGpuMaxFreqWasReal = true; return normalizeFrequency(maxRaw); }
                } else {
                    try {
                        long v = Long.parseLong(line.trim());
                        if (v > 0) { lastGpuMaxFreqWasReal = true; return normalizeFrequency(v); }
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception ignored) {}
        }
        // Conservative estimate for Adreno GPUs — matches the original behavior
        // before the multi-sample rewrite. lastGpuMaxFreqWasReal stays false so
        // the caller can tag this as a hardcoded (not measured) ratio.
        return 650.0;
    }

    /**
     * Probe for the sysfs file that exposes kgsl "busy total" counters and
     * cache the winner. Different msm_kgsl kernel forks publish under
     * different filenames; without this we'd pin to the wrong one and silently
     * fall through to "no GPU usage available".
     *
     * Validates by reading the file and confirming it parses as two whitespace-
     * separated longs — bare existence isn't enough because some kernels expose
     * an empty placeholder. Returns null if nothing usable is found.
     */
    private java.io.File resolveGpuBusyFile() {
        if (resolvedGpuBusyFile != null) return resolvedGpuBusyFile;
        if (gpuBusyPathAbsent) return null;

        // Single canonical kgsl path — matches pre-session behavior. Probing
        // alternates risked promoting an unrelated counter (e.g. one that
        // resets per-read) over the freq-ratio fallback the user is used to.
        java.io.File f = new java.io.File("/sys/class/kgsl/kgsl-3d0/gpu_busy");
        if (!f.exists()) {
            gpuBusyPathAbsent = true;
        } else if (f.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line = br.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        Long.parseLong(parts[0]);
                        Long.parseLong(parts[1]);
                        resolvedGpuBusyFile = f;
                        if (!gpuBusyResolutionLogged) {
                            logger.info("GPU busy counter resolved: /sys/class/kgsl/kgsl-3d0/gpu_busy");
                            gpuBusyResolutionLogged = true;
                        }
                        return f;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (!gpuBusyResolutionLogged) {
            logger.info("GPU busy counter not found at /sys/class/kgsl/kgsl-3d0/gpu_busy; using freq-ratio");
            gpuBusyResolutionLogged = true;
        }
        return null;
    }

    /**
     * Normalize frequency to MHz - handles Hz, KHz, MHz inputs
     */
    private double normalizeFrequency(long val) {
        if (val > 1_000_000) return val / 1_000_000.0;  // Hz -> MHz
        if (val > 1_000) return val / 1_000.0;          // KHz -> MHz
        return val;                                      // Already MHz
    }
    
    /**
     * Read the leading non-negative integer from a sysfs value, ignoring
     * optional suffixes such as "%" or "@frequency".
     */
    private long readLongFromFile(java.io.File file) {
        if (!file.exists() || !file.canRead()) return -1;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            if (line == null) return -1;
            // Read the leading number only. Concatenating every digit would
            // misread "45@1000" as 451000; a negative value is not valid data.
            String value = line.trim();
            int end = 0;
            while (end < value.length()) {
                char c = value.charAt(end);
                if (c < '0' || c > '9') break;
                end++;
            }
            if (end == 0) return -1;
            return Long.parseLong(value.substring(0, end));
        } catch (Exception e) {
            return -1;
        }
    }
    
    private void collectAppMetrics(PerformanceSnapshot snapshot) {
        try {
            // Thread count
            snapshot.threadCount = Thread.activeCount();
            
            // GC stats
            Runtime runtime = Runtime.getRuntime();
            snapshot.gcCount = 0;  // Would need to track via GC callbacks
            
            // File descriptors
            try {
                java.io.File fdDir = new java.io.File("/proc/" + pid + "/fd");
                String[] fds = fdDir.list();
                snapshot.openFileDescriptors = fds != null ? fds.length : 0;
            } catch (Exception ignored) {}
            
        } catch (Exception e) {
            logger.debug("App metrics error: " + e.getMessage());
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    private int readCpuFrequency() {
        String[] paths = {
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_cur_freq"
        };
        
        for (String path : paths) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(path));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    return Integer.parseInt(line.trim()) / 1000;  // KHz to MHz
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }

    // Cached CPU max-freq (KHz→MHz). The ceiling is static per boot, so resolve
    // it once. 0 = not yet resolved; -1 = resolved-absent (don't retry).
    private int cachedCpuMaxFreqMhz = 0;

    /**
     * Reads the CPU's maximum scaling frequency. Paired with {@link
     * #readCpuFrequency()} this makes thermal throttling self-evident on the
     * Performance page WITHOUT adb: when cpuFreqMhz sits well below
     * cpuMaxFreqMhz under load (and CPU temp is high), the SoC is throttling —
     * which on this shared SDM665 drags the whole head-unit UI, not just
     * OverDrive. cpuinfo_max_freq is the hardware ceiling; scaling_max_freq is
     * the governor's current cap (can itself be lowered by thermal HAL) — we
     * prefer cpuinfo_max_freq so a thermally-capped governor still compares
     * against the true hardware max.
     */
    private int readCpuMaxFrequency() {
        if (cachedCpuMaxFreqMhz != 0) {
            return cachedCpuMaxFreqMhz > 0 ? cachedCpuMaxFreqMhz : 0;
        }
        String[] paths = {
            "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
        };
        for (String path : paths) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(path));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    int mhz = Integer.parseInt(line.trim()) / 1000;  // KHz to MHz
                    if (mhz > 0) {
                        cachedCpuMaxFreqMhz = mhz;
                        return mhz;
                    }
                }
            } catch (Exception ignored) {}
        }
        cachedCpuMaxFreqMhz = -1;  // resolved-absent; stop retrying
        return 0;
    }

    private double readCpuTemperature() {
        // Fast path: read the already-resolved zone. Resolved once, then reused
        // for the life of the process — the thermal topology is static.
        if (resolvedCpuTempFile != null) {
            if (resolvedCpuTempFile == TEMP_PATH_NOT_FOUND) return 0;
            Double t = readTempFile(resolvedCpuTempFile, true);
            return t != null ? t : 0;
        }

        // Slow path: resolve the CPU thermal zone ONCE.
        String[] cpuThermalKeywords = {
            "cpu", "soc", "core", "cluster", "little", "big", "prime",
            "cpu-thermal", "cpu_thermal", "cpuss", "cpuss-0", "cpuss-1",
            "cpu-0-0", "cpu-0-1", "cpu-1-0", "cpu-1-1", "tsens_tz_sensor"
        };

        for (int i = 0; i < 30; i++) {
            try {
                String typePath = "/sys/class/thermal/thermal_zone" + i + "/type";
                BufferedReader typeReader = new BufferedReader(new FileReader(typePath));
                String type = typeReader.readLine();
                typeReader.close();

                if (type != null) {
                    String typeLower = type.toLowerCase().trim();
                    for (String keyword : cpuThermalKeywords) {
                        if (typeLower.contains(keyword)) {
                            java.io.File tempFile = new java.io.File(
                                "/sys/class/thermal/thermal_zone" + i + "/temp");
                            Double result = readTempFile(tempFile, true);
                            // Only accept a zone that yields a sane temperature,
                            // then cache it so subsequent samples skip the scan.
                            if (result != null) {
                                resolvedCpuTempFile = tempFile;
                                return result;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback to direct paths.
        String[] paths = {
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
            "/sys/kernel/cpu/temp"
        };
        for (String path : paths) {
            java.io.File f = new java.io.File(path);
            Double t = readTempFile(f, false);
            if (t != null) {
                resolvedCpuTempFile = f;
                return t;
            }
        }

        // Nothing usable on this device — remember that so we never re-scan.
        resolvedCpuTempFile = TEMP_PATH_NOT_FOUND;
        return 0;
    }

    /**
     * Read one thermal /temp file and normalize to °C (handles millidegrees).
     * Returns null if the file is unreadable/empty, or — when {@code sanityCheck}
     * is set — if the value is outside a plausible 10–120 °C band (used while
     * resolving which zone is the right one). Once a zone is cached, reads are a
     * single open+parse with no directory scan.
     */
    private Double readTempFile(java.io.File f, boolean sanityCheck) {
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty()) return null;
            double temp = Double.parseDouble(line.trim());
            double result = temp > 1000 ? temp / 1000.0 : temp;
            if (sanityCheck && (result < 10 || result > 120)) return null;
            return result;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Diagnostic method to discover available thermal zones and GPU paths.
     * Call this to identify what's available on the device.
     */
    public JSONObject discoverSystemPaths() {
        JSONObject discovery = new JSONObject();
        
        try {
            // Discover thermal zones
            JSONArray thermalZones = new JSONArray();
            for (int i = 0; i < 40; i++) {
                try {
                    String typePath = "/sys/class/thermal/thermal_zone" + i + "/type";
                    BufferedReader typeReader = new BufferedReader(new FileReader(typePath));
                    String type = typeReader.readLine();
                    typeReader.close();
                    
                    String tempPath = "/sys/class/thermal/thermal_zone" + i + "/temp";
                    BufferedReader tempReader = new BufferedReader(new FileReader(tempPath));
                    String tempLine = tempReader.readLine();
                    tempReader.close();
                    
                    if (type != null && tempLine != null) {
                        JSONObject zone = new JSONObject();
                        zone.put("zone", i);
                        zone.put("type", type.trim());
                        double temp = Double.parseDouble(tempLine.trim());
                        zone.put("temp", temp > 1000 ? temp / 1000.0 : temp);
                        thermalZones.put(zone);
                    }
                } catch (Exception ignored) {}
            }
            discovery.put("thermalZones", thermalZones);
            
            // Check GPU paths
            JSONArray gpuPaths = new JSONArray();
            String[] allGpuPaths = {
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/devices/platform/mali.0/clock",
                "/sys/class/misc/mali0/device/clock",
                "/sys/class/devfreq/mali/cur_freq",
                "/sys/kernel/gpu/gpu_clock",
                "/sys/class/devfreq/gpu/cur_freq"
            };
            
            for (String path : allGpuPaths) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(path));
                    String line = reader.readLine();
                    reader.close();
                    if (line != null) {
                        JSONObject pathInfo = new JSONObject();
                        pathInfo.put("path", path);
                        pathInfo.put("value", line.trim());
                        gpuPaths.put(pathInfo);
                    }
                } catch (Exception ignored) {}
            }
            discovery.put("gpuPaths", gpuPaths);
            
            // Check devfreq devices
            JSONArray devfreqDevices = new JSONArray();
            java.io.File devfreqDir = new java.io.File("/sys/class/devfreq");
            if (devfreqDir.exists() && devfreqDir.isDirectory()) {
                String[] devices = devfreqDir.list();
                if (devices != null) {
                    for (String device : devices) {
                        try {
                            String freqPath = "/sys/class/devfreq/" + device + "/cur_freq";
                            BufferedReader reader = new BufferedReader(new FileReader(freqPath));
                            String freq = reader.readLine();
                            reader.close();
                            
                            JSONObject devInfo = new JSONObject();
                            devInfo.put("device", device);
                            devInfo.put("cur_freq", freq != null ? freq.trim() : "N/A");
                            devfreqDevices.put(devInfo);
                        } catch (Exception ignored) {}
                    }
                }
            }
            discovery.put("devfreqDevices", devfreqDevices);
            
            logger.info("System discovery: " + discovery.toString());
            
        } catch (Exception e) {
            logger.error("Discovery failed", e);
        }
        
        return discovery;
    }
    
    private double readGpuTemperature() {
        // Fast path: read the already-resolved zone (topology is static).
        if (resolvedGpuTempFile != null) {
            if (resolvedGpuTempFile == TEMP_PATH_NOT_FOUND) return 0;
            Double t = readTempFile(resolvedGpuTempFile, false);
            return t != null ? t : 0;
        }

        // Slow path: resolve the GPU thermal zone ONCE.
        String[] gpuThermalKeywords = {
            "gpu", "adreno", "mali", "g3d", "graphics", "pvr", "vivante",
            "gpu-thermal", "gpu_thermal", "gpu-usr", "gpuss", "gpuss-0",
            "gpuss-1", "gpuss-max", "gpu-step"
        };

        for (int i = 0; i < 30; i++) {
            try {
                String typePath = "/sys/class/thermal/thermal_zone" + i + "/type";
                BufferedReader typeReader = new BufferedReader(new FileReader(typePath));
                String type = typeReader.readLine();
                typeReader.close();

                if (type != null) {
                    String typeLower = type.toLowerCase().trim();
                    for (String keyword : gpuThermalKeywords) {
                        if (typeLower.contains(keyword)) {
                            java.io.File tempFile = new java.io.File(
                                "/sys/class/thermal/thermal_zone" + i + "/temp");
                            Double result = readTempFile(tempFile, false);
                            if (result != null) {
                                resolvedGpuTempFile = tempFile;
                                return result;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback: try direct GPU thermal paths.
        String[] directGpuTempPaths = {
            "/sys/class/kgsl/kgsl-3d0/temp",
            "/sys/devices/platform/mali.0/temp",
            "/sys/kernel/gpu/gpu_temp",
            "/sys/devices/virtual/thermal/gpu/temp"
        };
        for (String path : directGpuTempPaths) {
            java.io.File f = new java.io.File(path);
            Double t = readTempFile(f, false);
            if (t != null) {
                resolvedGpuTempFile = f;
                return t;
            }
        }

        // Nothing usable — remember it so we never re-scan.
        resolvedGpuTempFile = TEMP_PATH_NOT_FOUND;
        return 0;
    }

    
    // ==================== DATA ACCESS ====================
    
    public PerformanceSnapshot getLatestSnapshot() {
        return latestSnapshot.get();
    }
    
    public JSONObject getLatestAsJson() {
        PerformanceSnapshot snapshot = latestSnapshot.get();
        if (snapshot == null) {
            return new JSONObject();
        }
        return snapshot.toJson();
    }
    
    public JSONArray getHistoryAsJson() {
        JSONArray array = new JSONArray();
        synchronized (history) {
            for (PerformanceSnapshot snapshot : history) {
                array.put(snapshot.toJson());
            }
        }
        return array;
    }
    
    public JSONObject getFullReport() {
        try {
            JSONObject report = new JSONObject();
            report.put("current", getLatestAsJson());
            report.put("history", getHistoryAsJson());
            report.put("historySize", HISTORY_SIZE);
            report.put("sampleIntervalMs", SAMPLE_INTERVAL_MS);
            report.put("pid", pid);
            report.put("uid", uid);
            return report;
        } catch (Exception e) {
            logger.error("Failed to create full report", e);
            return new JSONObject();
        }
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    // ==================== SNAPSHOT DATA CLASS ====================
    
    public static class PerformanceSnapshot {
        public long timestamp;
        
        // CPU
        public double cpuUsagePercent;
        public double appCpuUsagePercent;
        public int cpuFreqMhz;
        public int cpuMaxFreqMhz;
        public double cpuTempCelsius;
        
        // Memory
        public double memTotalMb;
        public double memUsedMb;
        public double memUsagePercent;
        public double appMemoryMb;
        public double appNativeHeapMb;
        public double appJavaHeapMb;
        
        // GPU
        public double gpuUsagePercent;
        public double gpuFreqMhz;
        public double gpuTempCelsius;
        // Provenance of gpuUsagePercent so the UI can distinguish a real
        // measurement from the freq-ratio estimate (issue #173): a device with
        // no busy counter AND no readable max-freq publishes gpuclk/650 — a
        // fabricated constant that looks like telemetry. The number is kept
        // (per product decision) but tagged: "busy_counter" | "devfreq_load" |
        // "freq_ratio_estimate" (real max_freq read) | "freq_ratio_hardcoded"
        // (650 default) | "unavailable".
        public String gpuUsageSource = "unavailable";
        
        // App
        public int threadCount;
        public int gcCount;
        public int openFileDescriptors;
        
        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("timestamp", timestamp);
                
                // CPU
                JSONObject cpu = new JSONObject();
                cpu.put("system", round(cpuUsagePercent));
                cpu.put("app", round(appCpuUsagePercent));
                // Core count so the web layer can scale the per-core "app" value
                // (top-style, ceiling = cores × 100) onto a 0-100 bar/graph.
                // Auto-detected, never hardcoded — same source the app% math
                // uses in collectCpuMetrics().
                cpu.put("cores", Runtime.getRuntime().availableProcessors());
                cpu.put("freqMhz", cpuFreqMhz);
                cpu.put("maxFreqMhz", cpuMaxFreqMhz);
                cpu.put("tempC", round(cpuTempCelsius));
                json.put("cpu", cpu);
                
                // Memory
                JSONObject mem = new JSONObject();
                mem.put("totalMb", round(memTotalMb));
                mem.put("usedMb", round(memUsedMb));
                mem.put("usagePercent", round(memUsagePercent));
                mem.put("appTotalMb", round(appMemoryMb));
                mem.put("appNativeMb", round(appNativeHeapMb));
                mem.put("appJavaMb", round(appJavaHeapMb));
                json.put("memory", mem);
                
                // GPU
                JSONObject gpu = new JSONObject();
                gpu.put("usage", round(gpuUsagePercent));
                gpu.put("usageSource", gpuUsageSource);
                gpu.put("freqMhz", round(gpuFreqMhz));
                gpu.put("tempC", round(gpuTempCelsius));
                json.put("gpu", gpu);
                
                // App
                JSONObject app = new JSONObject();
                app.put("threads", threadCount);
                app.put("gcCount", gcCount);
                app.put("openFds", openFileDescriptors);
                json.put("app", app);
                
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
        
        private double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }
    }
}
