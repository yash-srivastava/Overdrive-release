package com.overdrive.app.daemon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.IAccModeManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.overdrive.app.daemon.proxy.Safe;
import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sentry Daemon - runs as system user (UID 1000) via privileged shell.
 * 
 * RESPONSIBILITIES:
 * 1. ACQUIRE ACC LOCK - This is the CRITICAL one that prevents force_suspend!
 * 2. Acquire WakeLock to prevent CPU sleep
 * 3. Whitelist UIDs (1000, 2000, app UID) for network access
 * 4. Whitelist app package via accmodemanager
 * 5. Keep WiFi enabled
 * 
 * UID 1000 (system) has android.permission.DEVICE_ACC which is required for ACC Lock!
 */
public class SentryDaemon {
    
    private static final String TAG = "SentryDaemon";
    private static DaemonLogger logger;
    private static PowerManager.WakeLock wakeLock;

    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** com.overdrive.app */
    private static String APP_PACKAGE_NAME() { return Safe.s("3Is1Ze/xWL6dkFvd9bF+deUGK/HqnInkSi6jinpc6s8="); }
    /** accmodemanager */
    private static String SERVICE_ACCMODE() { return Safe.s("tr877WU3+MV4zFtCjanWUw=="); }
    /** byd_datacached */
    private static String SERVICE_BYD_DATACACHE() { return Safe.s("JQiIxMJxYlF8spk2fIi8Sg=="); }
    /** bg_datacache */
    private static String SERVICE_BG_DATACACHE() { return Safe.s("m84QJmAGTQpH+XP36MaDpA=="); }
    /** /data/local/tmp */
    private static String PATH_DATA_LOCAL_TMP() { return Safe.s("vuaMjrmBGBFh07qqnUuL8w=="); }
    /** /data/data/com.android.providers.settings */
    private static String PATH_DATA_SYSTEM_SETTINGS() { return Safe.s("4FWGV7tPhe9614nkUCor4bnqFPfssDPoiHYPJxgenGAPG3xCP+0Cb2Hm04LZxNNJ"); }
    /** /data/local/tmp/sentry_daemon.pid */
    private static String PATH_SENTRY_PID() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzy1lsQShZtcRseW7dNE1si25na89IOT5cRwBuRuJBcXS"); }
    /** svc wifi enable */
    private static String CMD_WIFI_ENABLE() { return Safe.s("GzzLDvODRsKARkPOXEZeIA=="); }
    /** cmd wifi set-wifi-enabled enabled */
    private static String CMD_WIFI_ENABLE_ALT() { return Safe.s("OHt1ORBfaA6jti9DhL+LSDghCI3qSNr9WYGyb82Ov2DsCnMgXaYKKKOzpoICOnGX"); }
    
    // ACC Lock - COMMENTED OUT (using whitelistAppPackageOld instead)
    // private static Object accLockObject = null;
    private static Context appContext = null;
    
    public static void main(String[] args) {
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
        
        log("=== Sentry Daemon Starting ===");
        log("UID: " + myUid + " (" + uidToName(myUid) + ")");
        log("PID: " + android.os.Process.myPid());
        
        if (myUid == 1000) {
            log("*** RUNNING AS SYSTEM - CAN ACQUIRE ACC LOCK! ***");
        }
        
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
                
                // ACC whitelist and protection DISABLED - causes BYD default dashcam
                // to lose video signal when running as privileged (UID 1000).
                // The setPkg2AccWhiteList call elevates our app's camera priority
                // above the BYD dashcam, stealing its AVMCamera feed.
                // whitelistAppPackageOld();
                // protectDaemon(context);
                
                // Keep WiFi enabled
                enableWifi();
            } else {
                log("WARNING: Running without context - using shell fallbacks");
                writePidFile();
                startControlSocket();
                // protectDaemonViaShell(); // DISABLED - same reason as above
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
    
    // ==================== ACC LOCK (COMMENTED OUT) ====================
    
    /*
     * Acquire ACC Lock - COMMENTED OUT, replaced by whitelistAppPackageOld.
     *
     * UID 1000 (system) has android.permission.DEVICE_ACC which is required.
     * This sets mDirty bit 4 (acclock) in AccModeManagerService.
     */
    /*
    @SuppressLint("WrongConstant")
    private static void acquireAccLock(Context context) {
        log("=== ACQUIRING ACC LOCK (UID 1000) ===");
        
        try {
            Object accModeManager = context.getSystemService(SERVICE_ACCMODE());
            if (accModeManager == null) {
                log("  AccModeManager is null!");
                return;
            }
            
            log("  Got AccModeManager: " + accModeManager.getClass().getName());
            
            // Find newAccLock method
            Method newAccLockMethod = null;
            for (Method m : accModeManager.getClass().getMethods()) {
                if (m.getName().equals("newAccLock")) {
                    newAccLockMethod = m;
                    log("  Found newAccLock: " + m);
                    break;
                }
            }
            
            if (newAccLockMethod == null) {
                log("  newAccLock method not found!");
                return;
            }
            
            // Create ACC Lock with level 1 (PARTIAL)
            // Level 1 is the only valid level based on previous testing
            Object accLock = newAccLockMethod.invoke(accModeManager, 1, "SentryDaemon:AccLock");
            
            if (accLock == null) {
                log("  newAccLock returned null!");
                return;
            }
            
            log("  Got AccLock object: " + accLock.getClass().getName());
            
            // List available methods
            log("  AccLock methods:");
            for (Method m : accLock.getClass().getMethods()) {
                if (m.getDeclaringClass() != Object.class) {
                    log("    " + m.getName());
                }
            }
            
            // Call acquire()
            Method acquireMethod = accLock.getClass().getMethod("acquire");
            acquireMethod.invoke(accLock);
            
            accLockObject = accLock;
            log("  >>> ACC LOCK ACQUIRED! <<<");
            
            // Verify it's held
            try {
                Method isHeldMethod = accLock.getClass().getMethod("isHeld");
                boolean isHeld = (Boolean) isHeldMethod.invoke(accLock);
                log("  isHeld: " + isHeld);
            } catch (Exception e) {
                // Ignore
            }
            
        } catch (Exception e) {
            String msg = e.getMessage();
            if (e.getCause() != null) {
                msg = e.getCause().getMessage();
            }
            log("  ACC Lock error: " + msg);
            e.printStackTrace();
            
            // Try alternative approach via mService
            tryAccLockViaMService(context);
        }
    }
    */
    
    /*
     * Alternative: Try to acquire ACC Lock via direct mService access.
     * COMMENTED OUT - using whitelistAppPackageOld instead.
     */
    /*
    @SuppressLint("WrongConstant")
    private static void tryAccLockViaMService(Context context) {
        log("  Trying ACC Lock via mService...");
        
        try {
            Object accModeManager = context.getSystemService(SERVICE_ACCMODE());
            if (accModeManager == null) return;
            
            Field mServiceField = accModeManager.getClass().getDeclaredField("mService");
            mServiceField.setAccessible(true);
            Object mService = mServiceField.get(accModeManager);
            
            if (mService == null) {
                log("    mService is null");
                return;
            }
            
            log("    Got mService: " + mService.getClass().getName());
            
            // List all methods
            log("    mService methods:");
            for (Method m : mService.getClass().getMethods()) {
                if (m.getDeclaringClass() != Object.class) {
                    StringBuilder params = new StringBuilder();
                    for (Class<?> p : m.getParameterTypes()) {
                        if (params.length() > 0) params.append(", ");
                        params.append(p.getSimpleName());
                    }
                    log("      " + m.getName() + "(" + params + ")");
                }
            }
            
            // Try acquireAccLock directly
            for (Method m : mService.getClass().getMethods()) {
                if (m.getName().equals("acquireAccLock")) {
                    log("    Found acquireAccLock: " + m);
                    Class<?>[] params = m.getParameterTypes();
                    
                    // acquireAccLock(IBinder, int, String, String, WorkSource, String)
                    if (params.length >= 2) {
                        try {
                            IBinder token = new android.os.Binder();
                            if (params.length == 6) {
                                m.invoke(mService, token, 1, "SentryDaemon", "AccLock", null, APP_PACKAGE_NAME());
                            } else if (params.length == 2) {
                                m.invoke(mService, token, 1);
                            }
                            log("    >>> acquireAccLock via mService: SUCCESS! <<<");
                            return;
                        } catch (Exception e) {
                            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                            log("    acquireAccLock error: " + msg);
                        }
                    }
                }
            }
            
            // Try requestSuspending(0) to block suspend
            for (Method m : mService.getClass().getMethods()) {
                if (m.getName().equals("requestSuspending")) {
                    try {
                        m.invoke(mService, 0);  // 0 = don't suspend?
                        log("    requestSuspending(0): called");
                    } catch (Exception e) {
                        log("    requestSuspending error: " + e.getMessage());
                    }
                }
            }
            
        } catch (Exception e) {
            log("    mService error: " + e.getMessage());
        }
    }
    */
    
    /*
     * Release ACC Lock (call when exiting).
     * COMMENTED OUT - using whitelistAppPackageOld instead.
     */
    /*
    public static void releaseAccLock() {
        if (accLockObject == null) return;
        
        try {
            Method releaseMethod = accLockObject.getClass().getMethod("release");
            releaseMethod.invoke(accLockObject);
            log("ACC Lock released");
        } catch (Exception e) {
            log("ACC Lock release error: " + e.getMessage());
        }
        
        accLockObject = null;
    }
    */
    
    // ==================== WHITELIST APP PACKAGE (from AccSentryDaemon) ====================
    
    /*
     * DISABLED: Whitelist our app package from ACC power management killing.
     * This causes BYD default dashcam to show "no signal" when SentryDaemon
     * runs as privileged (UID 1000). The setPkg2AccWhiteList binder call
     * elevates our app's priority in the BYD ACC system, which causes the
     * camera HAL to give our app priority over the default dashcam for
     * AVMCamera access.
     *
     * private static void whitelistAppPackageOld() {
     *     String pkg = APP_PACKAGE_NAME();
     *     log("Whitelisting package " + pkg + " via accmodemanager...");
     *     boolean success = false;
     *     try {
     *         Class<?> serviceManager = Class.forName("android.os.ServiceManager");
     *         Method getService = serviceManager.getMethod("getService", String.class);
     *         IBinder binder = (IBinder) getService.invoke(null, SERVICE_ACCMODE());
     *         if (binder != null) {
     *             IAccModeManager service = IAccModeManager.Stub.asInterface(binder);
     *             if (service != null) {
     *                 service.setPkg2AccWhiteList(pkg);
     *                 success = true;
     *             }
     *         }
     *     } catch (Exception e) { }
     * }
     */
    
    // ==================== DAEMON PROTECTION ====================
    
    private static void protectDaemon(Context context) {
        int myUid = android.os.Process.myUid();
        boolean isSystem = (myUid == 1000);
        
        log("=== PROTECTING DAEMON ===");
        
        // 1. Acquire WakeLock
        acquireWakeLock(context);
        
        // 2. Whitelist UIDs for network access
        int[] uidsToWhitelist = isSystem ? new int[]{1000, 2000} : new int[]{myUid};
        for (int uid : uidsToWhitelist) {
            whitelistUidForNetwork(context, uid);
        }
        
        // 3. Whitelist app package
        whitelistAppPackage(context);
        
        // 4. Whitelist app UID if running as system
        if (isSystem) {
            whitelistAppUid(context);
        }
        
        log("=== DAEMON PROTECTION COMPLETE ===");
    }
    
    private static void protectDaemonViaShell() {
        log("=== PROTECTING DAEMON (shell fallback) ===");
        
        String pkg = APP_PACKAGE_NAME();
        
        // Whitelist UIDs
        for (int uid : new int[]{1000, 2000}) {
            String uidStr = String.valueOf(uid);
            for (int code = 1; code <= 3; code++) {
                execShell("service call " + SERVICE_BYD_DATACACHE() + " " + code + " s16 '" + uidStr + "' i32 0 2>/dev/null");
                execShell("service call " + SERVICE_BG_DATACACHE() + " " + code + " s16 '" + uidStr + "' i32 0 2>/dev/null");
            }
        }
        
        // Whitelist package
        for (int code = 1; code <= 5; code++) {
            execShell("service call " + SERVICE_ACCMODE() + " " + code + " s16 '" + pkg + "' 2>/dev/null");
        }
        
        log("=== SHELL FALLBACK COMPLETE ===");
    }
    
    private static void acquireWakeLock(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SentryDaemon::Lock");
                wakeLock.acquire();
                log("WakeLock acquired");
            }
        } catch (Exception e) {
            log("WARN: Failed to acquire WakeLock: " + e.getMessage());
        }
    }
    
    private static void whitelistUidForNetwork(Context context, int uid) {
        String uidStr = String.valueOf(uid);
        log("Whitelisting UID " + uid + "...");
        
        // Try byd_datacached
        try {
            @SuppressLint("WrongConstant")
            Object service = context.getSystemService(SERVICE_BYD_DATACACHE());
            if (service != null) {
                Method method = service.getClass().getMethod("setAppStartupData", String.class, Integer.TYPE);
                method.invoke(service, uidStr, 0);
                log("  byd_datacached: OK");
                return;
            }
        } catch (Exception e) { }
        
        // Try bg_datacache
        try {
            @SuppressLint("WrongConstant")
            Object service = context.getSystemService(SERVICE_BG_DATACACHE());
            if (service != null) {
                Method method = service.getClass().getMethod("setAppOpsData", String.class, Integer.TYPE);
                method.invoke(service, uidStr, 0);
                log("  bg_datacache: OK");
                return;
            }
        } catch (Exception e) { }
        
        // Shell fallback
        for (int code = 1; code <= 3; code++) {
            execShell("service call " + SERVICE_BYD_DATACACHE() + " " + code + " s16 '" + uidStr + "' i32 0 2>/dev/null");
            execShell("service call " + SERVICE_BG_DATACACHE() + " " + code + " s16 '" + uidStr + "' i32 0 2>/dev/null");
        }
        log("  shell fallback: done");
    }
    
    private static void whitelistAppPackage(Context context) {
        String pkg = APP_PACKAGE_NAME();
        log("Whitelisting package " + pkg + "...");
        
        try {
            @SuppressLint("WrongConstant")
            Object accManager = context.getSystemService(SERVICE_ACCMODE());
            if (accManager != null) {
                java.lang.reflect.Field mServiceField = accManager.getClass().getDeclaredField("mService");
                mServiceField.setAccessible(true);
                Object iAccService = mServiceField.get(accManager);
                
                if (iAccService != null) {
                    Method whitelistMethod = iAccService.getClass().getDeclaredMethod("setPkg2AccWhiteList", String.class);
                    whitelistMethod.setAccessible(true);
                    whitelistMethod.invoke(iAccService, pkg);
                    log("  accmodemanager: OK");
                    return;
                }
            }
        } catch (Exception e) { }
        
        // Shell fallback
        for (int code = 1; code <= 5; code++) {
            execShell("service call " + SERVICE_ACCMODE() + " " + code + " s16 '" + pkg + "' 2>/dev/null");
        }
        log("  shell fallback: done");
    }
    
    private static void whitelistAppUid(Context context) {
        String pkg = APP_PACKAGE_NAME();
        try {
            int appUid = context.getPackageManager().getApplicationInfo(pkg, 0).uid;
            log("App UID: " + appUid);
            whitelistUidForNetwork(context, appUid);
        } catch (Exception e) {
            log("Could not get app UID: " + e.getMessage());
        }
    }
    
    private static void enableWifi() {
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
            try { return super.getApplicationContext(); } catch (NullPointerException e) { return this; }
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
    
    private static String execShell(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    // ==================== DAEMON CONTROL (KILL HANDLING) ====================
    
    private static final int CONTROL_PORT = 19879;  // SentryDaemon control port (19876=CameraDaemon, 19878=TelegramBot)
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
        
        // Release ACC Lock
        //releaseAccLock();
        
        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                log("WakeLock released");
            } catch (Exception e) {
                // Ignore
            }
        }
        
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
                    
                    // Check if Location service is running
                    String result = execShell("dumpsys activity services " + LOCATION_SERVICE_NAME + " 2>/dev/null");
                    
                    boolean isRunning = result.contains("ServiceRecord") && 
                                       result.contains("app=ProcessRecord") &&
                                       !result.contains("app=null");
                    
                    if (!isRunning) {
                        log("Location Monitor: Service not running, restarting...");
                        restartLocationService();
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
    
    /**
     * Setup location permissions using shell commands.
     */
    private static void setupLocationPermissions() {
        log("Setting up Location permissions...");
        
        // Grant runtime permissions via pm grant (requires shell/root)
        execShell("pm grant " + APP_PKG() + " android.permission.ACCESS_FINE_LOCATION");
        execShell("pm grant " + APP_PKG() + " android.permission.ACCESS_COARSE_LOCATION");
        execShell("pm grant " + APP_PKG() + " android.permission.ACCESS_BACKGROUND_LOCATION");
        
        // Allow background location via appops
        execShell("appops set " + APP_PKG() + " ACCESS_FINE_LOCATION allow");
        execShell("appops set " + APP_PKG() + " ACCESS_COARSE_LOCATION allow");
        execShell("appops set " + APP_PKG() + " ACCESS_BACKGROUND_LOCATION allow");
        
        // Allow background operation
        execShell("appops set " + APP_PKG() + " RUN_IN_BACKGROUND allow");
        execShell("appops set " + APP_PKG() + " RUN_ANY_IN_BACKGROUND allow");
        
        // Whitelist from battery optimization
        execShell("dumpsys deviceidle whitelist +" + APP_PKG());
        
        // Apply power settings
        execShell("settings put global wifi_sleep_policy 2");
        execShell("settings put global stay_on_while_plugged_in 7");
        
        log("Location permissions configured");
    }
    
    /**
     * Restart the Location Sidecar service by launching the silent starter activity.
     */
    private static void restartLocationService() {
        log("Location Monitor: Restarting Location service via silent activity...");
        
        // Method 1: Launch silent Location starter activity (preferred - no UI shown)
        String result = execShell("am start -n " + APP_PKG() + "/.ui.LocationStarterActivity " +
            "-a " + APP_PKG() + ".START_LOCATION_SILENT " +
            "-f 0x10000000 " +  // FLAG_ACTIVITY_NEW_TASK
            "-f 0x00080000 " +  // FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS  
            "2>&1");
        log("Location restart (silent activity): " + result);
        
        // Method 2: Start foreground service directly (backup)
        execShell("am start-foreground-service -n " + APP_PKG() + "/.services.LocationSidecarService 2>&1");
        
        // Method 3: Send broadcast to boot receiver (backup)
        execShell("am broadcast -a android.intent.action.BOOT_COMPLETED " +
            "-n " + APP_PKG() + "/.receiver.LocationBootReceiver 2>&1");
        
        // Wait and verify
        try { Thread.sleep(3000); } catch (Exception e) {}
        
        String verify = execShell("dumpsys activity services " + LOCATION_SERVICE_NAME + " 2>/dev/null");
        if (verify.contains("ServiceRecord") && !verify.contains("app=null")) {
            log("Location Monitor: Location service restarted successfully!");
        } else {
            log("Location Monitor: Location service restart pending");
        }
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
