package com.overdrive.app.daemon;

/**
 * Java-heap limit and GC counters for app_process daemons.
 *
 * <p>app_process inherits dalvik.vm.heapgrowthlimit, the per-app cap meant for
 * ordinary apps. Some Android 13 units set it to 96 MB while the daemons'
 * buffers are sized for 256 MB, so the camera daemon ran out of heap while
 * parked. Lifting the growth limit is the largeHeap path (cap becomes
 * dalvik.vm.heapsize); it reserves nothing and GC sizing still follows live data.
 */
public final class DaemonHeap {

    // Only the small per-app class is lifted; units already at 256/512 MB keep their cap.
    private static final long SMALL_GROWTH_LIMIT_BYTES = 128L * 1024 * 1024;

    private DaemonHeap() {}

    /** Lifts a small growth limit once at daemon start. Returns a log line, or null if untouched. */
    public static String liftSmallGrowthLimit() {
        long before = Runtime.getRuntime().maxMemory();
        if (before <= 0 || before > SMALL_GROWTH_LIMIT_BYTES) return null;
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getMethod("getRuntime").invoke(null);
            vmRuntime.getMethod("clearGrowthLimit").invoke(runtime);
        } catch (Throwable t) {
            return "Heap growth limit " + mb(before) + " MB kept (clearGrowthLimit unavailable: "
                    + t.getClass().getSimpleName() + ")";
        }
        return "Heap growth limit lifted: " + mb(before) + " -> "
                + mb(Runtime.getRuntime().maxMemory()) + " MB";
    }

    /** Cumulative ART GC counters for the MEM line; empty when the runtime does not report them. */
    public static String gcStats() {
        try {
            String count = android.os.Debug.getRuntimeStat("art.gc.gc-count");
            String time = android.os.Debug.getRuntimeStat("art.gc.gc-time");
            String blockingCount = android.os.Debug.getRuntimeStat("art.gc.blocking-gc-count");
            String blockingTime = android.os.Debug.getRuntimeStat("art.gc.blocking-gc-time");
            if (count == null && blockingCount == null) return "";
            return ", gc=" + count + "/" + time + "ms, blockingGc=" + blockingCount + "/" + blockingTime + "ms";
        } catch (Throwable t) {
            return "";
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }
}
