package com.overdrive.app.daemon;

import android.content.Context;
import android.os.Looper;

import com.overdrive.app.daemon.proxy.Safe;
import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Sentry daemon: keeps Wi-Fi enabled (unless suppressed), monitors the location
 * sidecar, and handles lifecycle control. It is launched from the ADB shell.
 */
public class SentryDaemon {
    
    private static final String TAG = "SentryDaemon";
    private static DaemonLogger logger;

    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** com.overdrive.app */
    private static String APP_PACKAGE_NAME() { return Safe.s("3Is1Ze/xWL6dkFvd9bF+deUGK/HqnInkSi6jinpc6s8="); }
    /** /data/local/tmp */
    private static String PATH_DATA_LOCAL_TMP() { return com.overdrive.app.util.ScratchRoot.path(Safe.s("vuaMjrmBGBFh07qqnUuL8w==")); }
    /** /data/data/com.android.providers.settings */
    private static String PATH_DATA_SYSTEM_SETTINGS() { return Safe.s("4FWGV7tPhe9614nkUCor4bnqFPfssDPoiHYPJxgenGAPG3xCP+0Cb2Hm04LZxNNJ"); }
    /** /data/local/tmp/sentry_daemon.pid */
    private static String PATH_SENTRY_PID() { return com.overdrive.app.util.ScratchRoot.path(Safe.s("ZHx6IP38aGV/Q7iMCCcxzy1lsQShZtcRseW7dNE1si25na89IOT5cRwBuRuJBcXS")); }
    /** svc wifi enable */
    private static String CMD_WIFI_ENABLE() { return Safe.s("GzzLDvODRsKARkPOXEZeIA=="); }
    /** cmd wifi set-wifi-enabled enabled */
    private static String CMD_WIFI_ENABLE_ALT() { return Safe.s("OHt1ORBfaA6jti9DhL+LSDghCI3qSNr9WYGyb82Ov2DsCnMgXaYKKKOzpoICOnGX"); }
    
    private static Context appContext = null;
    
    public static void main(String[] args) {
        com.overdrive.app.util.ScratchRoot.bootstrapFromEnv();
        int myUid = android.os.Process.myUid();
        
        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)
            .withFileLog(true)
            .withConsoleLog(true));
        
        // Initialize logger based on UID
        String logDir = (myUid == 1000) 
            ? PATH_DATA_SYSTEM_SETTINGS() 
            : PATH_DATA_LOCAL_TMP();
        logger = DaemonLogger.getInstance(TAG, logDir);
        
        // CRITICAL: Check if another instance is already running BEFORE doing anything else
        if (isDaemonRunning()) {
            log("ERROR: Another SentryDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }

        // "Vehicle ON only" parked gate. While the parked-shutdown marker exists the
        // stack is intentionally terminated for the parked window; this daemon would
        // otherwise switch Wi-Fi on and run a `dumpsys` every 15 s on a car that is
        // meant to sleep. The marker is erased by acc_sentry_daemon (the ACC judge) on
        // the real ACC-on or by a recovery trigger, after which the normal launch path
        // starts us again. An explicit user Start erases it before launching, so the
        // manual override still works. Inert in onAndOff (marker never exists).
        if (isParkedShutdownMarkerPresent()) {
            log("Parked-shutdown marker present — SentryDaemon must not run while parked. Exiting.");
            System.exit(0);
            return;
        }

        log("=== Sentry Daemon Starting ===");
        log("UID: " + myUid + " (" + uidToName(myUid) + ")");
        log("PID: " + android.os.Process.myPid());
        
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        
        try {
            Context context = createAppContext();
            if (context == null) {
                log("createAppContext failed, trying getSystemContext...");
                context = getSystemContext();
            }
            
            if (context != null) {
                log("Got context: " + context);
                appContext = context;
                
                // Write PID file for external kill
                writePidFile();
                
                // Start control socket for clean shutdown
                startControlSocket();
                
                // Do not acquire ACC protection here: raising camera priority can
                // deprive the vehicle's factory dashcam of its AVM feed.
                // Keep WiFi enabled
                enableWifi();
            } else {
                log("WARNING: Running without context - using shell fallbacks");
                writePidFile();
                startControlSocket();
                enableWifi();
            }
            
            log("=== Setup complete, daemon running ===");
            
            // Start Location Sidecar monitor to keep GPS service alive when app is killed
            startLocationMonitor();
            
            // Keep daemon alive
            Looper.loop();
            
        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String uidToName(int uid) {
        switch (uid) {
            case 0: return "root";
            case 1000: return "system";
            case 2000: return "shell";
            default: return "uid=" + uid;
        }
    }
    
    private static void log(String msg) {
        if (logger != null) {
            logger.info(msg);
        }
        // Note: System.out.println is now handled by DaemonLogger when enableStdoutLog is true
    }
    
    private static void enableWifi() {
        // Honor an explicit user "WiFi off" automation / key-mapping (radio action set
        // the suppression flag). Without such a rule the flag is false (default) and
        // WiFi is enabled as before. Fail-open: a read error returns false → we enable.
        if (com.overdrive.app.config.UnifiedConfigManager.isWifiKeepAliveSuppressed()) {
            log("WiFi enable skipped — user radio rule keeps WiFi off");
            return;
        }
        log("Enabling WiFi...");
        execShell(CMD_WIFI_ENABLE());
        execShell(CMD_WIFI_ENABLE_ALT());
        log("WiFi enabled");
    }
    
    // ==================== CONTEXT HELPERS ====================
    
    private static Context getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = resolveActivityThread(activityThreadClass);
            if (activityThread == null) return null;
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Exception e) {
            log("getSystemContext failed: " + e.getMessage());
            return null;
        }
    }
    
    private static Context createAppContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = resolveActivityThread(activityThreadClass);
            
            if (activityThread == null) {
                log("createAppContext: all strategies failed, using null-safe fallback");
                return new PermissionBypassContext(null);
            }
            
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            Context systemContext = (Context) getSystemContext.invoke(activityThread);
            if (systemContext == null) return new PermissionBypassContext(null);
            
            String packageName = APP_PACKAGE_NAME();
            Context appContext = systemContext.createPackageContext(packageName, 
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            
            return new PermissionBypassContext(appContext);
            
        } catch (Exception e) {
            log("createAppContext failed: " + e.getMessage());
            return new PermissionBypassContext(null);
        }
    }
    
    /**
     * Resolve ActivityThread using 3 strategies:
     * 1. currentActivityThread() — fastest, works when app process is running
     * 2. systemMain() with timeout — works on some boot conditions, can deadlock
     * 3. Manual constructor + Looper.prepareMainLooper — reliable fallback
     */
    private static Object resolveActivityThread(Class<?> activityThreadClass) {
        // Strategy 1: existing thread
        try {
            Method cur = activityThreadClass.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at != null) return at;
        } catch (Exception ignored) {}
        
        // Strategy 2: systemMain with timeout
        final Object[] result = new Object[1];
        try {
            Thread t = new Thread(() -> {
                try {
                    Method systemMain = activityThreadClass.getMethod("systemMain");
                    result[0] = systemMain.invoke(null);
                } catch (Exception ignored) {}
            }, "SystemMainInit");
            t.setDaemon(true);
            t.start();
            t.join(10_000);
            if (t.isAlive()) {
                log("resolveActivityThread: systemMain timed out");
                t.interrupt();
                // Check if it partially initialized
                try {
                    Method cur = activityThreadClass.getMethod("currentActivityThread");
                    Object at = cur.invoke(null);
                    if (at != null) return at;
                } catch (Exception ignored) {}
            } else if (result[0] != null) {
                return result[0];
            }
        } catch (Exception ignored) {}
        
        // Strategy 3: manual creation
        try {
            try { android.os.Looper.prepareMainLooper(); } catch (Exception ignored) {}
            java.lang.reflect.Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object at = ctor.newInstance();
            try {
                java.lang.reflect.Field f = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                f.setAccessible(true);
                f.set(null, at);
            } catch (Exception ignored) {}
            log("resolveActivityThread: manual creation succeeded");
            return at;
        } catch (Exception e) {
            log("resolveActivityThread: manual creation failed: " + e.getMessage());
        }
        
        return null;
    }
    
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) { super(base); }
        
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        // Null-safe overrides for fallback mode (base=null)
        @Override public Context getApplicationContext() {
            try { Context c = super.getApplicationContext(); return c != null ? c : this; } catch (NullPointerException e) { return this; }
        }
        @Override public String getPackageName() {
            try { return super.getPackageName(); } catch (NullPointerException e) { return APP_PACKAGE_NAME(); }
        }
        @Override public Object getSystemService(String name) {
            try { return super.getSystemService(name); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            try { return super.getApplicationInfo(); } catch (NullPointerException e) { return new android.content.pm.ApplicationInfo(); }
        }
        @Override public android.content.ContentResolver getContentResolver() {
            try { return super.getContentResolver(); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.res.Resources getResources() {
            try { return super.getResources(); } catch (NullPointerException e) { return null; }
        }
        @Override public Context createPackageContext(String packageName, int flags) {
            try { return super.createPackageContext(packageName, flags); } catch (Exception e) { return this; }
        }
    }
    
    // ==================== SHELL EXECUTION ====================

    private static final long EXEC_SHELL_TIMEOUT_MS = 15_000L;

    interface ProcessStarter {
        Process start(String[] command) throws java.io.IOException;
    }

    private static final ProcessStarter DEFAULT_PROCESS_STARTER =
            command -> new ProcessBuilder(command).redirectErrorStream(true).start();

    private static String execShell(String cmd) {
        return execShell(DEFAULT_PROCESS_STARTER, cmd, EXEC_SHELL_TIMEOUT_MS);
    }

    static String execShell(ProcessStarter starter, String cmd, long timeoutMs) {
        if (starter == null || cmd == null) return "ERROR: invalid command";
        Process process = null;
        Thread drain = null;
        try {
            process = starter.start(
                    new String[]{"sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)});
            if (process == null) return "ERROR: failed to start process";
            try {
                java.io.OutputStream stdin = process.getOutputStream();
                if (stdin != null) stdin.close();
            } catch (Exception ignored) {}
            final Process proc = process;
            final StringBuilder output = new StringBuilder();
            // Drain stdout/stderr concurrently while waiting so neither a full pipe buffer
            // nor a wedged system_server Binder call can block this daemon indefinitely.
            drain = new Thread(() -> {
                readStreamInto(proc.getInputStream(), output);
                readStreamInto(proc.getErrorStream(), output);
            }, "SentryExecDrain");
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                log("execShell timed out after " + timeoutMs + "ms — killing child: " + cmd);
                process.destroyForcibly();
                try { process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) {}
                try { drain.join(250); } catch (InterruptedException ignored) {}
                return "ERROR: command timed out after " + timeoutMs + "ms";
            }
            try { drain.join(1_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            synchronized (output) {
                return output.toString().trim();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (process != null) {
                try { process.destroyForcibly(); } catch (Exception ignored) {}
            }
            return "ERROR: interrupted";
        } catch (Exception e) {
            if (process != null) {
                try { process.destroyForcibly(); } catch (Exception ignored) {}
            }
            return "ERROR: " + e.getMessage();
        }
    }

    private static void readStreamInto(java.io.InputStream stream, StringBuilder target) {
        if (stream == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (target) {
                    target.append(line).append("\n");
                }
            }
        } catch (Exception ignored) {
        }
    }
    
    // ==================== DAEMON CONTROL (KILL HANDLING) ====================
    
    private static final int CONTROL_PORT = 19879;  // SentryDaemon control (19876=CameraDaemon, 19877=Surveillance IPC, 19878=BydEventDaemon, 19880=TelegramBot)
    private static String PID_FILE() { return PATH_SENTRY_PID(); }
    private static volatile boolean running = true;
    private static java.net.ServerSocket controlSocket;
    
    /**
     * Start control socket for clean shutdown.
     * Listens on localhost:19876 for "STOP" command.
     */
    private static void startControlSocket() {
        new Thread(() -> {
            try {
                controlSocket = new java.net.ServerSocket(CONTROL_PORT, 1, 
                    java.net.InetAddress.getByName("127.0.0.1"));
                log("Control socket listening on port " + CONTROL_PORT);
                
                while (running) {
                    try {
                        java.net.Socket client = controlSocket.accept();
                        client.setSoTimeout(5000);
                        
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream()));
                        java.io.PrintWriter writer = new java.io.PrintWriter(
                            client.getOutputStream(), true);
                        
                        String command = reader.readLine();
                        if (command != null) {
                            command = command.trim().toUpperCase();
                            log("Control command: " + command);
                            
                            switch (command) {
                                case "STOP":
                                case "KILL":
                                case "EXIT":
                                    writer.println("OK:STOPPING");
                                    client.close();
                                    shutdown();
                                    break;
                                case "STATUS":
                                    writer.println("OK:RUNNING:PID=" + android.os.Process.myPid() + 
                                        ":LOCATION_MONITOR=" + (locationMonitorEnabled ? "ON" : "OFF"));
                                    break;
                                case "PING":
                                    writer.println("OK:PONG");
                                    break;
                                case "LOCATION_MONITOR_ON":
                                    startLocationMonitor();
                                    writer.println("OK:LOCATION_MONITOR_STARTED");
                                    break;
                                case "LOCATION_MONITOR_OFF":
                                    stopLocationMonitor();
                                    writer.println("OK:LOCATION_MONITOR_STOPPED");
                                    break;
                                case "LOCATION_RESTART":
                                    restartLocationService();
                                    writer.println("OK:LOCATION_RESTART_TRIGGERED");
                                    break;
                                default:
                                    writer.println("ERROR:UNKNOWN_COMMAND");
                            }
                        }
                        
                        client.close();
                    } catch (java.net.SocketTimeoutException e) {
                        // Ignore timeout
                    } catch (Exception e) {
                        if (running) {
                            log("Control socket error: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log("Failed to start control socket: " + e.getMessage());
            }
        }, "ControlSocket").start();
    }
    
    /**
     * Write PID file for external kill scripts.
     */
    private static void writePidFile() {
        try {
            int pid = android.os.Process.myPid();
            java.io.FileWriter fw = new java.io.FileWriter(PID_FILE());
            fw.write(String.valueOf(pid));
            fw.close();
            log("PID file written: " + PID_FILE() + " (PID=" + pid + ")");
        } catch (Exception e) {
            log("Failed to write PID file: " + e.getMessage());
        }
    }
    
    /**
     * Delete PID file on exit.
     */
    private static void deletePidFile() {
        try {
            new java.io.File(PID_FILE()).delete();
        } catch (Exception e) {
            // Ignore
        }
    }
    
    /**
     * Clean shutdown - release resources and exit.
     */
    private static void shutdown() {
        log("=== SHUTTING DOWN ===");
        running = false;
        
        // Close control socket
        if (controlSocket != null) {
            try {
                controlSocket.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        
        // Delete PID file
        deletePidFile();
        
        log("Goodbye!");
        System.exit(0);
    }
    
    // ==================== LOCATION SIDECAR SERVICE MONITOR ====================
    
    private static final String LOCATION_SERVICE_NAME = "com.overdrive.app/.services.LocationSidecarService";
    private static String APP_PKG() { return APP_PACKAGE_NAME(); }
    private static final long LOCATION_CHECK_INTERVAL_MS = 15000; // 15 seconds
    private static volatile boolean locationMonitorEnabled = false;
    
    /**
     * Start Location Sidecar monitoring thread.
     * Checks every 15 seconds if LocationSidecarService is running and restarts it if needed.
     */
    public static void startLocationMonitor() {
        if (locationMonitorEnabled) {
            log("Location Monitor already running");
            return;
        }
        
        locationMonitorEnabled = true;
        
        new Thread(() -> {
            log("=== LOCATION MONITOR STARTED ===");
            log("Checking every " + (LOCATION_CHECK_INTERVAL_MS / 1000) + "s");
            
            // Setup location permissions first
            setupLocationPermissions();
            
            boolean firstCheck = true;
            
            while (running && locationMonitorEnabled) {
                try {
                    if (!firstCheck) {
                        Thread.sleep(LOCATION_CHECK_INTERVAL_MS);
                    }
                    firstCheck = false;

                    // "Vehicle ON only" park began while we were running: the reaper
                    // terminates us, but self-exit here too so a missed kill cannot
                    // leave this loop probing the system every 15 s all night. One
                    // stat per tick; inert in onAndOff.
                    if (isParkedShutdownMarkerPresent()) {
                        log("Parked-shutdown marker appeared — vehicle parked in ON-only mode; shutting down");
                        shutdown();
                        return;
                    }

                    // Check if Location service is running
                    String result = execShell("dumpsys activity services " + LOCATION_SERVICE_NAME + " 2>/dev/null");
                    
                    boolean isRunning = result.contains("ServiceRecord") &&
                                       result.contains("app=ProcessRecord") &&
                                       !result.contains("app=null");

                    if (!isRunning) {
                        // GATE (G6): in "Vehicle ON only" mode, don't re-exec shell +
                        // restart the location sidecar while the car is OFF — that keeps
                        // the parked system busy every 15s. Only suppress the RESTART
                        // side-effect (not the loop cadence), so location resumes the
                        // instant ACC returns or the mode flips back. Mode is read LIVE
                        // (mtime-gated, cheap) so a runtime flip is honoured without a
                        // daemon restart. ACC state uses probeAccState() — a HAL-direct
                        // read that works in THIS UID-1000 process; AccMonitor.isAccOn()
                        // would NOT (its cache is only updated inside AccSentryDaemon's
                        // UID-2000 process and would read false here forever, over-
                        // suppressing location even while driving). probeAccState returns
                        // true only when ACC is confirmed OFF → on ON/unknown we restart
                        // as usual (fail-open, current behaviour preserved).
                        boolean suppressWhileParked =
                            com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()
                            && com.overdrive.app.monitor.AccMonitor.probeAccState(appContext);
                        if (suppressWhileParked) {
                            log("Location Monitor: onOnly + ACC OFF — skipping restart so system can sleep");
                        } else {
                            log("Location Monitor: Service not running, restarting...");
                            restartLocationService();
                        }
                    }
                    
                } catch (InterruptedException e) {
                    log("Location Monitor interrupted");
                    break;
                } catch (Exception e) {
                    log("Location Monitor error: " + e.getMessage());
                }
            }
            
            log("=== LOCATION MONITOR STOPPED ===");
        }, "LocationMonitor").start();
    }
    
    /**
     * Stop Location monitoring.
     */
    public static void stopLocationMonitor() {
        locationMonitorEnabled = false;
    }

    /** The "Vehicle ON only" parked-shutdown marker (chmod 666, readable from any UID). */
    private static boolean isParkedShutdownMarkerPresent() {
        try {
            return new java.io.File(
                    com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).isFile();
        } catch (Throwable t) {
            return false;
        }
    }
    
    /**
     * Setup location permissions using shell commands.
     */
    private static void setupLocationPermissions() {
        log("Setting up Location permissions...");
        
        // Grant runtime permissions via pm grant (requires shell/root)
        execShell("pm grant " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " android.permission.ACCESS_FINE_LOCATION");
        execShell("pm grant " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " android.permission.ACCESS_COARSE_LOCATION");
        execShell("pm grant " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " android.permission.ACCESS_BACKGROUND_LOCATION");
        
        // Allow background location via appops (DiLink 5: appops rejects these names, skip them).
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            execShell("appops set " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " ACCESS_FINE_LOCATION allow");
            execShell("appops set " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " ACCESS_COARSE_LOCATION allow");
            execShell("appops set " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " ACCESS_BACKGROUND_LOCATION allow");
        }
        
        // Allow background operation
        execShell("appops set " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " RUN_IN_BACKGROUND allow");
        execShell("appops set " + com.overdrive.app.util.ShellUser.flag() + " " + APP_PKG() + " RUN_ANY_IN_BACKGROUND allow");
        
        // Whitelist from battery optimization
        execShell("dumpsys deviceidle whitelist +" + APP_PKG());
        
        // Legacy firmware relies on these persistent settings. DiLink 5 uses scoped locks.
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            execShell("settings put global wifi_sleep_policy 2");
            execShell("settings put global stay_on_while_plugged_in 7");
        }
        
        log("Location permissions configured");
    }
    
    /**
     * Restart the Location Sidecar service by launching the silent starter activity.
     */
    private static void restartLocationService() {
        log("Location Monitor: Restarting Location service via silent activity...");
        
        // Method 1: Launch silent Location starter activity (preferred - no UI shown).
        // The flags MUST be a single OR-ed `-f` value: `am`'s -f handler uses Intent.setFlags
        // (NOT addFlags), so two `-f` options silently DISCARD the first — the previous form
        // passed NEW_TASK then a second flag and ended up without the NEW_TASK the launch needs.
        // 0x10000000 FLAG_ACTIVITY_NEW_TASK | 0x00800000 FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS.
        // (The old code's second flag was 0x00080000, which is CLEAR_WHEN_TASK_RESET, not
        // EXCLUDE_FROM_RECENTS — an easy digit slip. Recents exclusion is also declared on the
        // activity in the manifest, so this flag is belt-and-braces.)
        // Fallback step: try primary launchers first, check between steps, and only
        // send the BOOT_COMPLETED broadcast if starting directly did not bring up
        // the service. Avoid stacking multiple 15s timeouts if system_server is wedged.
        String result = execShell("am start -n " + APP_PKG() + "/.ui.LocationStarterActivity " +
            "-a " + APP_PKG() + ".START_LOCATION_SILENT " +
            "-f 0x10800000 " +
            "2>&1");
        log("Location restart (silent activity): " + result);
        if (isExecTimeout(result)) {
            log("Location Monitor: skipping fallback launches because system_server timed out");
            return;
        }

        try { Thread.sleep(1000); } catch (Exception e) {}
        if (isLocationServiceRunning()) {
            log("Location Monitor: Location service restarted successfully!");
            return;
        }

        // Method 2: Start foreground service directly (backup)
        String fgResult = execShell("am start-foreground-service -n " + APP_PKG() + "/.services.LocationSidecarService 2>&1");
        if (isExecTimeout(fgResult)) {
            log("Location Monitor: skipping broadcast fallback because foreground start timed out");
            return;
        }

        try { Thread.sleep(1000); } catch (Exception e) {}
        if (isLocationServiceRunning()) {
            log("Location Monitor: Location service restarted successfully!");
            return;
        }

        // Method 3: Send broadcast to boot receiver (last-resort backup)
        execShell("am broadcast -a android.intent.action.BOOT_COMPLETED " +
            "-n " + APP_PKG() + "/.receiver.LocationBootReceiver 2>&1");

        // Wait and verify
        try { Thread.sleep(1500); } catch (Exception e) {}

        if (isLocationServiceRunning()) {
            log("Location Monitor: Location service restarted successfully!");
        } else {
            log("Location Monitor: Location service restart pending");
        }
    }

    private static boolean isLocationServiceRunning() {
        String verify = execShell("dumpsys activity services " + LOCATION_SERVICE_NAME + " 2>/dev/null");
        return verify.contains("ServiceRecord") && !verify.contains("app=null");
    }

    static boolean isExecTimeout(String output) {
        return output != null && output.startsWith("ERROR: command timed out");
    }
    
    /**
     * Send stop command to running daemon.
     * Call this from app to kill the daemon cleanly.
     * 
     * @return true if daemon was stopped, false if not running or error
     */
    public static boolean sendStopCommand() {
        try {
            java.net.Socket socket = new java.net.Socket("127.0.0.1", CONTROL_PORT);
            socket.setSoTimeout(5000);
            
            java.io.PrintWriter writer = new java.io.PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            writer.println("STOP");
            String response = reader.readLine();
            
            socket.close();
            return response != null && response.startsWith("OK");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if daemon is running.
     * 
     * @return true if daemon is running
     */
    public static boolean isDaemonRunning() {
        try {
            java.net.Socket socket = new java.net.Socket("127.0.0.1", CONTROL_PORT);
            socket.setSoTimeout(2000);
            
            java.io.PrintWriter writer = new java.io.PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            writer.println("PING");
            String response = reader.readLine();
            
            socket.close();
            return response != null && response.contains("PONG");
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Kill daemon by PID file (fallback if socket doesn't work).
     */
    public static void killByPidFile() {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(PID_FILE()));
            String pidStr = br.readLine();
            br.close();
            
            if (pidStr != null && !pidStr.isEmpty()) {
                int pid = Integer.parseInt(pidStr.trim());
                Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)});
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}
