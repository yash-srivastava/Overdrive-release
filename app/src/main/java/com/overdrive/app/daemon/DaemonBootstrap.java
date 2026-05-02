package com.overdrive.app.daemon;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Bootstrap utility for standalone daemons running via app_process.
 * 
 * This class provides a bootstrap sequence that:
 * 1. Creates an app context using a hardcoded package name (chicken-egg problem)
 * 2. Provides permission bypass for accessing BYD hardware services
 * 
 * Note: Native library loading was removed - encryption now uses pure Java (Safe.java)
 * which is 100% stable across all Android versions when running via app_process.
 * 
 * USAGE (at the start of every daemon's main()):
 *   Context ctx = DaemonBootstrap.init();
 *   // Now Safe.s() / S.d() / Enc.* works correctly
 */
public final class DaemonBootstrap {
    
    private static final String TAG = "DaemonBootstrap";
    
    // Hardcoded - this is the ONLY place we need to hardcode the package name
    // It's required to break the chicken-egg problem (can't decrypt package name without context)
    private static final String BOOTSTRAP_PACKAGE = "com.overdrive.app";
    
    private static Context appContext = null;
    private static boolean initialized = false;
    
    private DaemonBootstrap() {} // No instantiation
    
    /**
     * Initialize the daemon environment and return an app context.
     * Safe to call multiple times - will return cached context after first init.
     * 
     * @return App context with permission bypass, or null if init failed
     */
    public static synchronized Context init() {
        if (initialized) {
            return appContext;
        }
        
        log("=== DaemonBootstrap Starting ===");
        
        try {
            // Step 1: Create app context using hardcoded package name
            appContext = createAppContext();
            if (appContext == null) {
                log("ERROR: Failed to create app context");
                return null;
            }
            log("App context created: " + appContext.getPackageName());
            
            // Step 2: Grant all manifest permissions via shell
            // PermissionBypassContext fakes PERMISSION_GRANTED locally, but pm grant
            // ensures the OS-level permission state is correct for cases where the
            // BYD HAL native layer checks permissions outside our context wrapper.
            PermissionGranter.grantAllPermissions(BOOTSTRAP_PACKAGE);
            
            // Step 3: Verify Safe.s() decryption works (pure Java, no native libs needed)
            if (verifySafeWorking()) {
                log("Safe.s() verification PASSED");
            } else {
                log("WARNING: Safe.s() verification FAILED - strings may be encrypted");
            }
            
            initialized = true;
            log("=== DaemonBootstrap Complete ===");
            return appContext;
            
        } catch (Exception e) {
            log("FATAL: Bootstrap failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Get the cached app context (must call init() first).
     */
    public static Context getContext() {
        return appContext;
    }
    
    /**
     * Check if bootstrap has completed successfully.
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get the hardcoded package name (for use before decryption is verified).
     */
    public static String getPackageName() {
        return BOOTSTRAP_PACKAGE;
    }
    
    // ==================== INTERNAL HELPERS ====================
    
    private static Context createAppContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = null;
            
            // Strategy 1: existing thread
            try {
                Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                activityThread = currentActivityThread.invoke(null);
            } catch (Exception ignored) {}
            
            // Strategy 2: systemMain with timeout
            if (activityThread == null) {
                final Object[] result = new Object[1];
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
                    log("createAppContext: systemMain timed out (10s)");
                    t.interrupt();
                    try {
                        Method cur = activityThreadClass.getMethod("currentActivityThread");
                        activityThread = cur.invoke(null);
                    } catch (Exception ignored) {}
                } else {
                    activityThread = result[0];
                }
            }
            
            // Strategy 3: manual creation
            if (activityThread == null) {
                try {
                    try { android.os.Looper.prepareMainLooper(); } catch (Exception ignored) {}
                    java.lang.reflect.Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    activityThread = ctor.newInstance();
                    try {
                        java.lang.reflect.Field f = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                        f.setAccessible(true);
                        f.set(null, activityThread);
                    } catch (Exception ignored) {}
                    log("createAppContext: manual ActivityThread creation succeeded");
                } catch (Exception e) {
                    log("createAppContext: manual creation failed: " + e.getMessage());
                }
            }
            
            if (activityThread == null) {
                log("Failed to get ActivityThread, using null-safe fallback");
                return new PermissionBypassContext(null);
            }
            
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            Context systemContext = (Context) getSystemContext.invoke(activityThread);
            if (systemContext == null) return new PermissionBypassContext(null);
            
            Context packageContext = systemContext.createPackageContext(
                BOOTSTRAP_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            
            return new PermissionBypassContext(packageContext);
            
        } catch (Exception e) {
            log("createAppContext failed: " + e.getMessage());
            return new PermissionBypassContext(null);
        }
    }
    
    /**
     * Verify that Safe.s() decryption is working by testing a known value.
     */
    private static boolean verifySafeWorking() {
        try {
            Class<?> encClass = Class.forName("com.overdrive.app.daemon.proxy.Enc");
            Field appPackageField = encClass.getDeclaredField("APP_PACKAGE");
            String decrypted = (String) appPackageField.get(null);
            
            // If decryption works, it should return "com.overdrive.app"
            // If it fails, it returns "ERR" or the encrypted base64 string
            boolean works = BOOTSTRAP_PACKAGE.equals(decrypted);
            log("Enc.APP_PACKAGE = " + decrypted + " (expected: " + BOOTSTRAP_PACKAGE + ")");
            return works;
            
        } catch (Exception e) {
            log("verifySafeWorking failed: " + e.getMessage());
            return false;
        }
    }
    
    private static void log(String msg) {
        System.out.println("DaemonBootstrap: " + msg);
    }
    
    /**
     * Context wrapper that bypasses permission checks.
     * Required for accessing BYD hardware services without signature permissions.
     */
    public static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) { 
            super(base); 
        }
        
        @Override 
        public void enforceCallingOrSelfPermission(String permission, String message) {}
        
        @Override 
        public void enforcePermission(String permission, int pid, int uid, String message) {}
        
        @Override 
        public void enforceCallingPermission(String permission, String message) {}
        
        @Override 
        public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        
        @Override 
        public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        
        @Override 
        public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        
        // Null-safe overrides for fallback mode
        @Override public Context getApplicationContext() {
            try { return super.getApplicationContext(); } catch (NullPointerException e) { return this; }
        }
        @Override public String getPackageName() {
            try { return super.getPackageName(); } catch (NullPointerException e) { return BOOTSTRAP_PACKAGE; }
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
}
