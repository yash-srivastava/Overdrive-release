package com.overdrive.app.daemon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell-based permission granter for daemon processes.
 * 
 * Grants all manifest-declared permissions via `pm grant` shell commands.
 * Belt-and-suspenders approach alongside PermissionBypassContext:
 * - PermissionBypassContext fakes PERMISSION_GRANTED for our own process
 * - PermissionGranter actually grants permissions at the OS level via shell
 * 
 * This handles cases where BYD HAL native code checks permissions outside
 * our context wrapper (e.g., deep in the system_server or HAL layer).
 * 
 * pm grant works from UID 2000 (shell) which is what our daemons run as.
 * Install-time / signature permissions will be silently skipped.
 */
public final class PermissionGranter {

    private static final String TAG = "PermissionGranter";
    private static boolean hasRun = false;
    private static Thread grantThread;
    
    /** Delay between individual pm grant calls to avoid flooding PackageManagerService. */
    private static final long GRANT_THROTTLE_MS = 50;

    /**
     * All permissions declared in our AndroidManifest that we attempt to grant.
     * BYD HAL permissions are custom permissions defined by the BYD system image.
     * pm grant works for normal/dangerous permissions; install-time ones are skipped.
     */
    private static final String[] ALL_PERMISSIONS = {
        // --- Android standard ---
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_LOGS",
        "android.permission.WRITE_SETTINGS",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.DEVICE_ACC",
        "android.permission.DEVICE_POWER",
        "android.permission.VIBRATE",

        // --- BYD HAL: core vehicle subsystems ---
        "android.permission.BYDAUTO_AC_COMMON",
        "android.permission.BYDAUTO_AC_GET",
        "android.permission.BYDAUTO_AC_SET",
        "android.permission.BYDAUTO_BODYWORK_COMMON",
        "android.permission.BYDAUTO_BODYWORK_GET",
        "android.permission.BYDAUTO_BODYWORK_SET",
        "android.permission.BYDAUTO_INSTRUMENT_COMMON",
        "android.permission.BYDAUTO_INSTRUMENT_GET",
        "android.permission.BYDAUTO_INSTRUMENT_SET",
        "android.permission.BYDAUTO_ENGINE_COMMON",
        "android.permission.BYDAUTO_ENGINE_GET",
        "android.permission.BYDAUTO_ENGINE_SET",
        "android.permission.BYDAUTO_CHARGING_COMMON",
        "android.permission.BYDAUTO_CHARGING_GET",
        "android.permission.BYDAUTO_CHARGING_SET",
        "android.permission.BYDAUTO_STATISTIC_COMMON",
        "android.permission.BYDAUTO_STATISTIC_GET",
        "android.permission.BYDAUTO_STATISTIC_SET",
        "android.permission.BYDAUTO_SPEED_COMMON",
        "android.permission.BYDAUTO_SPEED_GET",
        "android.permission.BYDAUTO_SPEED_SET",
        "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_GEARBOX_GET",
        "android.permission.BYDAUTO_LIGHT_COMMON",
        "android.permission.BYDAUTO_LIGHT_GET",
        "android.permission.BYDAUTO_LIGHT_SET",
        "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_ENERGY_GET",
        "android.permission.BYDAUTO_ENERGY_SET",
        "android.permission.BYDAUTO_TYRE_COMMON",
        "android.permission.BYDAUTO_TYRE_GET",
        "android.permission.BYDAUTO_TYRE_SET",
        "android.permission.BYDAUTO_RADAR_COMMON",
        "android.permission.BYDAUTO_RADAR_GET",
        "android.permission.BYDAUTO_RADAR_SET",
        "android.permission.BYDAUTO_SETTING_COMMON",
        "android.permission.BYDAUTO_SETTING_GET",
        "android.permission.BYDAUTO_SETTING_SET",
        "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
        "android.permission.BYDAUTO_DOOR_LOCK_GET",
        "android.permission.BYDAUTO_DOOR_LOCK_SET",
        "android.permission.BYDAUTO_SAFETY_BELT_COMMON",
        "android.permission.BYDAUTO_SAFETY_BELT_GET",
        "android.permission.BYDAUTO_SAFETY_BELT_SET",
        "android.permission.BYDAUTO_SEAT_COMMON",
        "android.permission.BYDAUTO_SEAT_GET",
        "android.permission.BYDAUTO_SEAT_SET",
        "android.permission.BYDAUTO_SENSOR_GET",
        "android.permission.BYDAUTO_SENSOR_SET",
        "android.permission.BYDAUTO_PM2P5_COMMON",
        "android.permission.BYDAUTO_PM2P5_GET",
        "android.permission.BYDAUTO_PM2P5_SET",
        "android.permission.BYDAUTO_MULTIMEDIA_COMMON",
        "android.permission.BYDAUTO_MULTIMEDIA_GET",
        "android.permission.BYDAUTO_MULTIMEDIA_SET",
        "android.permission.BYDAUTO_AUDIO_COMMON",
        "android.permission.BYDAUTO_AUDIO_GET",
        "android.permission.BYDAUTO_AUDIO_SET",
        "android.permission.BYDAUTO_PANORAMA_COMMON",
        "android.permission.BYDAUTO_PANORAMA_GET",
        "android.permission.BYDAUTO_PANORAMA_SET",
        "android.permission.BYDAUTO_TIME_COMMON",
        "android.permission.BYDAUTO_TIME_GET",
        "android.permission.BYDAUTO_TIME_SET",
        "android.permission.BYDAUTO_OTA_GET",
        "android.permission.BYDAUTO_OTA_SET",
        "android.permission.BYDAUTO_POWER_GET",
        "android.permission.BYDAUTO_POWER_SET",
        "android.permission.BYDAUTO_ADAS_GET",
        "android.permission.BYDAUTO_ADAS_SET",
        "android.permission.BYDAUTO_WIPER_GET",
        "android.permission.BYDAUTO_WIPER_SET",
        "android.permission.BYDAUTO_REAR_VIEW_MIRROR_GET",
        "android.permission.BYDAUTO_REAR_VIEW_MIRROR_SET",
        "android.permission.BYDAUTO_VEHICLE_DATA_GET",
        "android.permission.BYDAUTO_VEHICLE_DATA_SET",
        "android.permission.BYDAUTO_SRS_COMMON",
        "android.permission.BYDAUTO_SRS_GET",
        "android.permission.BYDAUTO_SRS_SET",

        // --- BYD HAL: extended ---
        "android.permission.BYDAUTO_SECURITY_GET",
        "android.permission.BYDAUTO_COLLISION_GET",
        "android.permission.BYDAUTO_COLLISION_SET",
        "android.permission.BYDAUTO_LOCATION_GET",
        "android.permission.BYDAUTO_LOCATION_SET",
        "android.permission.BYDAUTO_VIDEO_GET",
        "android.permission.BYDAUTO_VIDEO_SET",
        "android.permission.BYDAUTO_AUX_GET",
        "android.permission.BYDAUTO_AUX_SET",
        "android.permission.BYDAUTO_BLUETOOTH_GET",
        "android.permission.BYDAUTO_BLUETOOTH_SET",
        "android.permission.BYDAUTO_RADIO_GET",
        "android.permission.BYDAUTO_RADIO_SET",
        "android.permission.BYDAUTO_SPECIAL_GET",
        "android.permission.BYDAUTO_SPECIAL_SET",
        "android.permission.BYDAUTO_REMINDER_GET",
        "android.permission.BYDAUTO_REMINDER_SET",
        "android.permission.BYDAUTO_VERSION_GET",
        "android.permission.BYDAUTO_VERSION_SET",
        "android.permission.BYDAUTO_FUNCNOTICE_GET",
        "android.permission.BYDAUTO_FUNCNOTICE_SET",
        "android.permission.BYDAUTO_PHONE_GET",
        "android.permission.BYDAUTO_PHONE_SET",
        "android.permission.BYDAUTO_MOTOR_GET",
        "android.permission.BYDAUTO_MOTOR_SET",
        "android.permission.BYDAUTO_CPUTEMPRATURE_SET",
        "android.permission.BYDAUTO_QCFS_GET",
        "android.permission.BYDAUTO_QCFS_SET",
        "android.permission.BYDAUTO_SIGNAL_SET",
        "android.permission.BYDAUTO_RESCUE_GET",
        "android.permission.BYDAUTO_RESCUE_SET",
        "android.permission.BYDAUTO_TEST_GET",
        "android.permission.BYDAUTO_TEST_SET",
        "android.permission.BYDAUTO_DTC_GET",
        "android.permission.BYDAUTO_DTC_SET",
        "android.permission.BYDAUTO_BIGDATA_GET",
        "android.permission.BYDAUTO_YUN_GET",
        "android.permission.BYDAUTO_GB_GET",
        "android.permission.BYDAUTO_RSE_GET",
        "android.permission.BYDAUTO_RSE_SET",
        "android.permission.BYDAUTO_MQTT_GET",
        "android.permission.BYDAUTO_MQTT_SET",

        // --- BYD non-HAL ---
        "android.permission.BYD_CAMERA",
        "android.permission.BYDACQUISITION_SEND_BUFFER",
        "android.permission.BYDACQUISITION_SEND_FILE",
        "android.permission.BYDDIAGNOSTIC_SEND_BUFFER",
    };

    private PermissionGranter() {}

    /**
     * Grant all manifest permissions via shell.
     * Runs on a background thread to avoid blocking daemon startup.
     * Safe to call multiple times — only runs once.
     * 
     * SOTA: Throttled to avoid flooding PackageManagerService with concurrent
     * binder calls. Each `pm grant` spawns a shell process that calls into PMS.
     * Without throttling, 141 concurrent shell processes overwhelm the system
     * server, causing binder timeouts that break createPackageContext() and
     * other PMS-dependent operations running in parallel.
     *
     * pm grant requires UID 0 (root) or UID 2000 (shell). Our daemons run
     * as UID 2000 so this works. Permissions that are install-time only
     * (signature/privileged) will fail silently and get skipped.
     *
     * @param packageName the app package name
     */
    public static void grantAllPermissions(String packageName) {
        if (hasRun) return;
        hasRun = true;

        grantThread = new Thread(() -> {
            log("Granting permissions for " + packageName 
                + " (UID " + android.os.Process.myUid() + ", " + ALL_PERMISSIONS.length + " total)");
            long start = System.currentTimeMillis();
            int granted = 0;
            int failed = 0;
            int skipped = 0;
            List<String> failures = new ArrayList<>();

            for (String permission : ALL_PERMISSIONS) {
                // Check if daemon is shutting down — stop spawning new processes
                if (Thread.currentThread().isInterrupted()) {
                    log("Interrupted — aborting remaining grants");
                    break;
                }
                
                try {
                    int result = execGrant(packageName, permission);
                    if (result == 0) {
                        granted++;
                    } else if (result == -2) {
                        skipped++;
                    } else {
                        failed++;
                        failures.add(shortName(permission));
                    }
                } catch (Exception e) {
                    failed++;
                    failures.add(shortName(permission));
                }
                
                // Throttle: yield between grants to avoid flooding PMS.
                // 50ms × 141 permissions = ~7s total, vs the unthrottled 199s
                // observed in logs when PMS was overloaded from rapid restarts.
                try { Thread.sleep(GRANT_THROTTLE_MS); } catch (InterruptedException e) {
                    log("Interrupted during throttle — aborting remaining grants");
                    break;
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log("Done in " + elapsed + "ms: " + granted + " granted, " 
                + skipped + " skipped, " + failed + " failed");
            if (!failures.isEmpty() && failures.size() <= 15) {
                log("Failed: " + String.join(", ", failures));
            } else if (!failures.isEmpty()) {
                log("Failed: " + failures.size() + " permissions");
            }
        }, "PermissionGranter");
        grantThread.setDaemon(true);
        grantThread.start();
    }
    
    /**
     * Stop the permission granter thread if it's still running.
     * Called from the shutdown hook to prevent orphaned pm grant processes
     * from continuing to hammer PMS after the daemon exits.
     */
    public static void cancel() {
        if (grantThread != null && grantThread.isAlive()) {
            grantThread.interrupt();
            log("Cancelled — no more pm grant processes will be spawned");
        }
    }

    /**
     * Execute a single pm grant command.
     * @return 0 = success, -1 = failed, -2 = not grantable (skip)
     */
    private static int execGrant(String packageName, String permission) {
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", "pm grant " + packageName + " " + permission + " 2>&1"});
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            reader.close();
            
            int exitCode = process.waitFor();
            String out = output.toString().trim();
            
            if (exitCode == 0) {
                return 0;
            }
            
            // These are expected — permission is install-time only, doesn't exist, etc.
            if (out.contains("not a changeable permission")
                || out.contains("Unknown permission")
                || out.contains("has not requested permission")
                || out.contains("is not a")) {
                return -2;
            }
            
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Strip the android.permission. prefix for shorter log output */
    private static String shortName(String permission) {
        if (permission.startsWith("android.permission.")) {
            return permission.substring(19);
        }
        return permission;
    }

    /**
     * Generate ADB commands for manually granting all permissions.
     * Useful for debugging when shell granting fails.
     */
    public static String getAdbCommands(String packageName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Grant all permissions for ").append(packageName).append(":\n");
        for (String perm : ALL_PERMISSIONS) {
            sb.append("adb shell pm grant ").append(packageName).append(" ").append(perm).append("\n");
        }
        sb.append("\n# Verify:\n");
        sb.append("adb shell dumpsys package ").append(packageName).append(" | grep granted=true\n");
        return sb.toString();
    }

    private static void log(String msg) {
        System.out.println(TAG + ": " + msg);
    }
}
