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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
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
    private static volatile PerformanceMonitor instance;
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
    private final CpuFrequencySampler cpuFrequencySampler = new CpuFrequencySampler();
    // Reused output of parseAggregateCpuTimes(): {total jiffies, idle jiffies}.
    // Only touched inside collectMetrics(), under samplingLock.
    private final long[] cpuTimesScratch = new long[2];

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
    // Frequency-table maximum or the not-found result: 0 = unresolved, -1 = none readable.
    // max_gpuclk and devfreq max_freq follow thermal/policy caps, so they are read every sample.
    private double cachedGpuMaxFreqMhz = 0;
    // Cache the resolved busy-counter path, including a confirmed missing path.
    // The kernel's sysfs topology is stable for the daemon's lifetime.
    private java.io.File resolvedGpuBusyFile = null;
    private boolean gpuBusyPathAbsent = false;
    private boolean gpuBusyResolutionLogged = false;
    // Resolve the GPU devfreq directory once instead of enumerating sysfs on
    // every dashboard sample. null = unresolved (or the last listing failed).
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
    // CPU and GPU temperature discovery share the same thermal-zone topology.
    // Enumerate the actual thermal-zone directory once. BYD firmware exposes
    // 48 zones (including GPU/CPU low-frequency and step zones), so fixed
    // 30/40-entry scans silently omitted part of the device topology.
    private int[] thermalZoneIndices = null;
    private final Map<Integer, String> cachedThermalZoneTypes = new HashMap<>();

    // Scheduler
    private ScheduledExecutorService scheduler;
    private volatile boolean isRunning = false;
    // shutdownNow() interrupts the old scheduler but does not wait for an
    // in-flight procfs/sysfs sample to finish. Epoch checks stop that old
    // task from publishing after disconnect (or racing a new session).
    private volatile long monitoringEpoch = 0;
    private long sampledEpoch = -1;
    private final Object samplingLock = new Object();
    
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
    
    public synchronized void start() {
        if (isRunning) return;
        // A new session starts with an empty chart; counters reset on its first sample.
        synchronized (history) {
            history.clear();
        }
        latestSnapshot.set(null);
        isRunning = true;
        final long epoch = ++monitoringEpoch;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerfMonitor");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        // Fixed delay: a sweep slower than the interval must not run the next one back-to-back.
        scheduler.scheduleWithFixedDelay(() -> collectMetrics(epoch),
                0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        logger.info("Performance monitoring started");
    }
    
    public synchronized void stop() {
        isRunning = false;
        ++monitoringEpoch;
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
    public synchronized void clientConnected(String clientId) {
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
    public synchronized void clientHeartbeat(String clientId) {
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
    public synchronized void clientDisconnected(String clientId) {
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
    private synchronized void startClientCleanup() {
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
    private synchronized void stopClientCleanup() {
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
            // A heartbeat may have refreshed this client after the scan.
            // Compare-and-remove under the same lock as connect/disconnect so
            // an old cleanup pass cannot evict a live dashboard session.
            synchronized (this) {
                Long lastSeen = activeClients.get(clientId);
                if (lastSeen != null && now - lastSeen > CLIENT_TIMEOUT_MS) {
                    logger.debug("Removing stale client: " + clientId);
                    clientDisconnected(clientId);
                }
            }
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
    
    private void collectMetrics(long epoch) {
        // An interrupted sampler can finish sysfs reads after shutdownNow().
        // Serialize old/new instances and discard their work after disconnect.
        synchronized (samplingLock) {
            if (!isRunning || monitoringEpoch != epoch) return;
            if (sampledEpoch != epoch) {
                // Jiffy deltas are only meaningful within a continuous sampling
                // session. Reconnects must not average across parked idle time.
                lastCpuTime = 0;
                lastIdleTime = 0;
                lastAppCpuTime = 0;
                lastCpuTimeForApp = 0;
                lastGpuBusy = -1;
                lastGpuTotal = -1;
                sampledEpoch = epoch;
            }
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
            
                // stop()/start() hold this monitor, so guard the final epoch
                // check AND publication with the same lock. Otherwise stop()
                // could race between the check and latestSnapshot.set().
                synchronized (this) {
                    if (!isRunning || monitoringEpoch != epoch) return;
                    synchronized (history) {
                        history.addLast(snapshot);
                        while (history.size() > HISTORY_SIZE) {
                            history.removeFirst();
                        }
                    }
                    latestSnapshot.set(snapshot);
                }
            } catch (Exception e) {
                logger.error("Failed to collect metrics", e);
            }
        }
    }
    
    private void collectCpuMetrics(PerformanceSnapshot snapshot) {
        try {
            // Read /proc/stat for system CPU. Only the aggregate first line is
            // needed; use a small first-line read rather than a full text reader.
            String line = KernelFirstLine.read(new java.io.File("/proc/stat"));

            // A malformed aggregate line skips this tick's system and app CPU
            // deltas (their baselines must advance together); freq/temp still run.
            boolean systemSampled = parseAggregateCpuTimes(line, cpuTimesScratch);
            if (systemSampled) {
                long totalCpu = cpuTimesScratch[0];
                long totalIdle = cpuTimesScratch[1];
                
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
            String appLine = KernelFirstLine.read(new java.io.File("/proc/" + pid + "/stat"));

            if (systemSampled && appLine != null) {
                // /proc/<pid>/stat field 2 (comm) is parenthesized and may
                // itself contain spaces or ')'. Splitting the whole line both
                // allocates an array of every field and misindexes CPU times
                // when the process name contains whitespace. Read only fields
                // 14 (utime) and 15 (stime), anchored after the final ')'.
                long utime = readProcStatField(appLine, 14);
                long stime = readProcStatField(appLine, 15);
                if (utime >= 0 && stime >= 0) {
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

    
    /**
     * Aggregate "cpu " line of /proc/stat: out = {user+nice+system+idle+iowait+irq+softirq, idle+iowait}
     * (steal and later columns excluded). Returns false, out untouched, for any other or malformed line.
     */
    static boolean parseAggregateCpuTimes(String line, long[] out) {
        if (line == null || out == null || out.length < 2 || !line.startsWith("cpu ")) {
            return false;
        }
        final int length = line.length();
        int cursor = 3;
        long total = 0;
        long idle = 0;
        for (int field = 1; field <= 7; field++) {
            while (cursor < length && Character.isWhitespace(line.charAt(cursor))) {
                cursor++;
            }
            int start = cursor;
            long value = 0;
            while (cursor < length && !Character.isWhitespace(line.charAt(cursor))) {
                char c = line.charAt(cursor);
                if (c < '0' || c > '9' || cursor - start >= 18) {
                    return false;
                }
                value = value * 10 + (c - '0');
                cursor++;
            }
            if (cursor == start) {
                return false;
            }
            total += value;
            if (field == 4 || field == 5) {
                idle += value;
            }
        }
        out[0] = total;
        out[1] = idle;
        return true;
    }

    /**
     * Read one numeric field from /proc/<pid>/stat without splitting the line.
     * Linux field 2 (comm) is enclosed in parentheses but can contain spaces
     * and closing parentheses, so field numbering must begin after its final
     * closing parenthesis. Returns -1 when the requested field is absent or
     * malformed; CPU time fields are non-negative.
     */
    private static long readProcStatField(String statLine, int targetField) {
        if (statLine == null || targetField < 3) return -1;
        int commandEnd = statLine.lastIndexOf(')');
        if (commandEnd < 0) return -1;

        int cursor = commandEnd + 1;
        int field = 3;
        while (field <= targetField) {
            while (cursor < statLine.length()
                    && Character.isWhitespace(statLine.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= statLine.length()) return -1;

            int start = cursor;
            while (cursor < statLine.length()
                    && !Character.isWhitespace(statLine.charAt(cursor))) {
                cursor++;
            }
            if (field == targetField) {
                try {
                    return Long.parseLong(statLine.substring(start, cursor));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            field++;
        }
        return -1;
    }

    private void collectMemoryMetrics(PerformanceSnapshot snapshot) {
        try {
            // Only MemTotal and MemAvailable contribute to the dashboard's
            // memory metrics. Avoid regex splitting and parsing unrelated
            // /proc/meminfo values on every sample.
            long memTotal = -1;
            long memAvailable = -1;
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("MemTotal:")) {
                        memTotal = parseMemInfoValueKb(line);
                    } else if (line.startsWith("MemAvailable:")) {
                        memAvailable = parseMemInfoValueKb(line);
                    }
                    if (memTotal > 0 && memAvailable >= 0) break;
                }
            }

            // Some vendor /proc mounts deny or truncate meminfo. A missing
            // MemTotal previously divided by zero (NaN/Infinity in chart JSON),
            // while a missing MemAvailable looked like 100% RAM pressure.
            if (memTotal > 0) {
                snapshot.memTotalMb = memTotal / 1024.0;
                if (memAvailable >= 0) {
                    long usedKb = memTotal - Math.min(memAvailable, memTotal);
                    snapshot.memUsedMb = usedKb / 1024.0;
                    snapshot.memUsagePercent = 100.0 * usedKb / memTotal;
                }
            }
            
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
    
    /** Parse the numeric kB value after a /proc/meminfo field label. */
    private static long parseMemInfoValueKb(String line) {
        int index = line.indexOf(':') + 1;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        int valueStart = index;
        while (index < line.length() && line.charAt(index) >= '0' && line.charAt(index) <= '9') {
            index++;
        }
        if (valueStart == index) return 0;
        try {
            return Long.parseLong(line.substring(valueStart, index));
        } catch (NumberFormatException ignored) {
            return 0;
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
                // Cache the directory listing; frequency and load are still
                // read live so thermal/governor changes remain visible. A null
                // listing (transient I/O or SELinux denial) is retried next sample.
                if (discovered != null) gpuDevfreqDevices = discovered;
                devices = discovered != null ? discovered : new java.io.File[0];
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
                    try {
                        String line = KernelFirstLine.read(gpuBusyFile);
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
        if (cachedGpuMaxFreqMhz != 0) {
            lastGpuMaxFreqWasReal = cachedGpuMaxFreqMhz > 0;
            return cachedGpuMaxFreqMhz > 0 ? cachedGpuMaxFreqMhz : 650.0;
        }
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
            try {
                String line = KernelFirstLine.read(new java.io.File(path));
                if (line == null || line.trim().isEmpty()) continue;
                if (path.contains("available_frequencies")) {
                    long maxRaw = 0;
                    for (String f : line.trim().split("\\s+")) {
                        try {
                            long v = Long.parseLong(f.trim());
                            if (v > maxRaw) maxRaw = v;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (maxRaw > 0) {
                        lastGpuMaxFreqWasReal = true;
                        cachedGpuMaxFreqMhz = normalizeFrequency(maxRaw);
                        return cachedGpuMaxFreqMhz;
                    }
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
        cachedGpuMaxFreqMhz = -1;
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
            try {
                String line = KernelFirstLine.read(f);
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
        try {
            String line = KernelFirstLine.read(file);
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
    
    // Report the fastest live clock and the highest static ceiling across
    // cpufreq policies. cpu0-only sampling is wrong on the Dolphin's 4+4 SoC:
    // the silver cluster tops out at 1804 MHz, the gold cluster at 2016 MHz.
    private int readCpuFrequency() {
        return cpuFrequencySampler.currentPeakMhz();
    }

    private int readCpuMaxFrequency() {
        return cpuFrequencySampler.hardwarePeakMhz();
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

        for (int i : getThermalZoneIndices(30)) {
            String type = getThermalZoneType(i);
            if (type == null) continue;
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
     * Return the numeric thermal-zone IDs actually present on this kernel.
     * Do not assume IDs are contiguous or impose a fixed upper bound: the BYD
     * Dolphin firmware exports 48 zones, while other Qualcomm builds differ.
     * The directory topology is stable for the daemon lifetime, so enumerate
     * once and reuse the sorted result for CPU/GPU discovery and diagnostics.
     */
    private synchronized int[] getThermalZoneIndices(int legacyScanLimit) {
        if (thermalZoneIndices == null) {
            int[] discovered = discoverThermalZoneIndices(
                    new java.io.File("/sys/class/thermal"));
            if (discovered.length > 0) thermalZoneIndices = discovered;
        }
        if (thermalZoneIndices != null) return thermalZoneIndices;
        // Listing denied or empty: probe the fixed id range, uncached.
        int[] legacy = new int[legacyScanLimit];
        for (int i = 0; i < legacyScanLimit; i++) legacy[i] = i;
        return legacy;
    }

    static int[] discoverThermalZoneIndices(java.io.File zonesDirectory) {
        java.io.File[] entries = zonesDirectory.listFiles();
        if (entries == null || entries.length == 0) return new int[0];

        int[] indices = new int[entries.length];
        int count = 0;
        for (java.io.File entry : entries) {
            String name = entry.getName();
            if (!entry.isDirectory() || !name.startsWith("thermal_zone")) continue;
            String suffix = name.substring("thermal_zone".length());
            if (suffix.isEmpty()) continue;
            boolean digitsOnly = true;
            for (int i = 0; i < suffix.length(); i++) {
                if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') {
                    digitsOnly = false;
                    break;
                }
            }
            if (!digitsOnly) continue;
            try {
                int index = Integer.parseInt(suffix);
                indices[count] = index;
                count++; // Only count successfully parsed, valid zone IDs.
            } catch (NumberFormatException ignored) {
                // Skip numeric suffixes outside the signed-int range.
            }
        }
        indices = Arrays.copyOf(indices, count);
        Arrays.sort(indices);
        return indices;
    }

    /**
     * Read each zone type at most once across CPU and GPU discovery.
     * Missing/unreadable entries are cached too, avoiding repeated sysfs I/O.
     */
    private synchronized String getThermalZoneType(int index) {
        if (cachedThermalZoneTypes.containsKey(index)) {
            return cachedThermalZoneTypes.get(index);
        }

        String type = null;
        try {
            type = KernelFirstLine.read(new java.io.File("/sys/class/thermal/thermal_zone" + index + "/type"));
        } catch (Exception ignored) {
            // A missing/unreadable zone is cached as null.
        }
        cachedThermalZoneTypes.put(index, type);
        return type;
    }

    /**
     * Read one thermal /temp file and normalize to °C (handles millidegrees).
     * Returns null if the file is unreadable/empty, or — when {@code sanityCheck}
     * is set — if the value is outside a plausible 10–120 °C band (used while
     * resolving which zone is the right one). Once a zone is cached, reads are a
     * single open+parse with no directory scan.
     */
    private static Double readTempFile(java.io.File f, boolean sanityCheck) {
        try {
            String line = KernelFirstLine.read(f);
            if (line == null || line.trim().isEmpty()) return null;
            double temp = Double.parseDouble(line.trim());
            double result = temp > 1000 ? temp / 1000.0 : temp;
            if (sanityCheck && (result < 10 || result > 120)) return null;
            return result;
        } catch (Exception e) {
            return null;
        }
    }
    
    static JSONArray discoverThermalZones(java.io.File zonesDirectory, int[] indices) {
        JSONArray zones = new JSONArray();
        for (int index : indices) {
            java.io.File directory = new java.io.File(zonesDirectory, "thermal_zone" + index);
            try {
                String type = KernelFirstLine.read(new java.io.File(directory, "type"));
                if (type == null || type.trim().isEmpty()) continue;

                JSONObject zone = new JSONObject();
                zone.put("zone", index);
                zone.put("type", type.trim());
                Double temp = readTempFile(new java.io.File(directory, "temp"), false);
                // An identified zone is still useful for diagnostics without a reading.
                // JSON null distinguishes unavailable readings from an actual zero.
                if (temp == null || !Double.isFinite(temp)) {
                    zone.put("temp", JSONObject.NULL);
                } else {
                    zone.put("temp", temp);
                }
                zones.put(zone);
            } catch (Exception ignored) {
                // No readable type: skip this unidentified zone.
            }
        }
        return zones;
    }

    /**
     * Diagnostic method to discover available thermal zones and GPU paths.
     * Call this to identify what's available on the device.
     */
    public JSONObject discoverSystemPaths() {
        JSONObject discovery = new JSONObject();
        
        try {
            // Keep identified zones even when their live temperature is unavailable.
            discovery.put("thermalZones", discoverThermalZones(
                    new java.io.File("/sys/class/thermal"), getThermalZoneIndices(40)));

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

        for (int i : getThermalZoneIndices(30)) {
            String type = getThermalZoneType(i);
            if (type == null) continue;
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
