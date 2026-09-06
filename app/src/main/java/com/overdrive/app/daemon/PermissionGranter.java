package com.overdrive.app.daemon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // Standard Bluetooth read access for the automation Bluetooth trigger. On
        // targetSdk 25 these are normal (install-time) perms auto-granted at install,
        // so pm grant typically reports "not a changeable permission" (→ skipped,
        // harmless); listed here so the grant is attempted anyway and intent is clear.
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",

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
        "android.permission.BYDAUTO_BMS_COMMON",
        "android.permission.BYDAUTO_BMS_GET",
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
            int alreadyHeld = 0;
            List<String> failures = new ArrayList<>();

            // One dumpsys read instead of speculative grants for permissions
            // the app already holds. Every grant we can skip is a `sh` fork, a
            // `cmd package` fork, a PMS binder call and a GRANT_THROTTLE_MS
            // sleep that no longer lands on top of the camera HAL's
            // first-frame window during daemon start.
            //
            // Scope honestly stated: on the measured settled device this skips
            // the ~33 grantable permissions dumpsys reports as held (19.1s ->
            // 15.1s). The ~112 signature-level entries are never reported
            // granted, so they still pay fork + binder + throttle every start;
            // eliminating those would need a device-fingerprint-keyed cache
            // whose stale-skip failure mode is worse than the waste (see
            // issue #178). Reading live state (rather than persisting a
            // "done" marker) keeps this self-correcting if a permission is
            // ever revoked.
            Set<String> alreadyGranted = readGrantedPermissions(packageName);
            // Intersect with our grant list before logging so the count here
            // matches the "already held" counter in the Done line — dumpsys
            // also reports grants we never attempt (auto-granted install-time
            // and vendor permissions outside ALL_PERMISSIONS).
            alreadyGranted.retainAll(java.util.Arrays.asList(ALL_PERMISSIONS));
            if (!alreadyGranted.isEmpty()) {
                log("dumpsys: " + alreadyGranted.size() + " of " + ALL_PERMISSIONS.length
                    + " grant-list permissions already held — skipping those");
            }

            // SOTA / OEM Fix: SYSTEM_ALERT_WINDOW requires both the standard permission grant AND
            // the corresponding appop mode. On Android/OEM builds, pm grant alone does not
            // reliably flip the appop mode from default to allow, and dumpsys package reports
            // SYSTEM_ALERT_WINDOW as granted under install permissions (causing alreadyGranted
            // to skip issuing any command). Enforce the appop mode explicitly as its own step.
            int appOpResult = execAppOp(packageName, "SYSTEM_ALERT_WINDOW", "allow");
            if (appOpResult == 0) {
                log("appops: SYSTEM_ALERT_WINDOW set to allow");
            } else {
                log("WARN: failed to set appops SYSTEM_ALERT_WINDOW (code=" + appOpResult + ")");
            }

            for (String permission : ALL_PERMISSIONS) {
                // Check if daemon is shutting down — stop spawning new processes
                if (Thread.currentThread().isInterrupted()) {
                    log("Interrupted — aborting remaining grants");
                    break;
                }

                if (alreadyGranted.contains(permission)) {
                    alreadyHeld++;
                    continue;   // no fork, no binder call, no throttle sleep
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
                // 50ms per grant ISSUED — already-held permissions skip the
                // loop body above. Measured on a settled device: ~112
                // signature-level entries still reach here (dumpsys never
                // reports them granted), so this sleeps ~5.6s per start, down
                // from ~7.25s. Unthrottled, this was observed at 199s when PMS
                // was overloaded by rapid restarts.
                try { Thread.sleep(GRANT_THROTTLE_MS); } catch (InterruptedException e) {
                    log("Interrupted during throttle — aborting remaining grants");
                    break;
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log("Done in " + elapsed + "ms: " + alreadyHeld + " already held, "
                + granted + " granted, " + skipped + " skipped, " + failed + " failed");
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

    /**
     * Explicitly set an appop mode for the target package.
     * Handles permissions like SYSTEM_ALERT_WINDOW where the Android framework
     * requires both the permission grant and the appop mode to be 'allow'.
     *
     * @return 0 = success, -1 = failed
     */
    static int execAppOp(String packageName, String op, String mode) {
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", "appops set " + packageName + " " + op + " " + mode + " 2>&1"});
            return process.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Permissions {@code dumpsys package <pkg>} reports as already granted.
     *
     * <p>Parses both blocks the platform emits — {@code install permissions:}
     * (bare {@code name: granted=true}) and the per-user
     * {@code runtime permissions:} (which appends {@code , flags=[...]}). The
     * {@code requested permissions:} block is deliberately NOT counted: it lists
     * bare names with no grant marker, and treating those as granted would skip
     * exactly the grants we still need to issue.
     *
     * <p>A permission reported {@code granted=false} ANYWHERE in the dump is
     * excluded even if another line reports it {@code granted=true}. DiLink
     * head units are single-user, but dumpsys emits one runtime block per
     * Android user and {@code pm grant} (no {@code --user}) only fixes user 0 —
     * so if a multi-user image ever appears, a permission revoked for user 0
     * but held by another user must be re-granted, not skipped. Denied-wins is
     * the safe direction: under-collecting merely re-issues a no-op grant.
     *
     * <p>Returns an empty set for null / empty / unrecognised output. That is
     * the safe direction: an unreadable dumpsys degrades to "grant everything"
     * (the old behaviour), never to "skip everything".
     */
    static Set<String> parseGrantedPermissions(String dumpsysOutput) {
        Set<String> granted = new HashSet<>();
        if (dumpsysOutput == null || dumpsysOutput.isEmpty()) return granted;

        Set<String> denied = new HashSet<>();
        for (String rawLine : dumpsysOutput.split("\n")) {
            String line = rawLine.trim();
            int marker = line.indexOf(": granted=");
            if (marker <= 0) continue;

            String name = line.substring(0, marker).trim();
            if (name.isEmpty()) continue;

            // Value runs to the next comma (runtime entries append flags=[...]).
            String value = line.substring(marker + ": granted=".length());
            int comma = value.indexOf(',');
            if (comma >= 0) value = value.substring(0, comma);

            if ("true".equals(value.trim())) {
                granted.add(name);
            } else {
                denied.add(name);
            }
        }
        granted.removeAll(denied);
        return granted;
    }

    /**
     * Run {@code dumpsys package <pkg>} and return the permissions it reports as
     * granted. Returns an empty set on any failure so the caller falls back to
     * attempting every grant — the pre-existing behaviour.
     */
    private static Set<String> readGrantedPermissions(String packageName) {
        // The old code's first observable action was its interrupt check; keep
        // that ordering — don't spawn a probe the shutdown path can't unblock
        // (a parked pipe read does not respond to Thread.interrupt()).
        if (Thread.currentThread().isInterrupted()) {
            return new HashSet<>();
        }
        try {
            // -t 5: bound the dump at 5s (supported since Android 8.1; on an
            // exotic build that rejects the flag, dumpsys errors out, we parse
            // nothing, and the loop safely falls back to attempting every
            // grant). Android's dumpsys also self-bounds per service (~10s),
            // so this is belt and suspenders, not the only limit.
            Process process = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", "dumpsys -t 5 package " + packageName + " 2>/dev/null"});

            StringBuilder output = new StringBuilder(16384);
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            Set<String> parsed = parseGrantedPermissions(output.toString());
            // A silent zero-result probe would be indistinguishable from a
            // fresh install in the Done line; name the cause for field logs.
            if (exitCode != 0 || parsed.isEmpty()) {
                log("dumpsys probe yielded nothing (exit=" + exitCode
                    + ", bytes=" + output.length() + ") — will attempt every grant");
            }
            return parsed;
        } catch (InterruptedException e) {
            // Preserve the interrupt so the grant loop's own check still fires.
            Thread.currentThread().interrupt();
            return new HashSet<>();
        } catch (Exception e) {
            log("dumpsys probe failed (" + e.getMessage() + ") — will attempt every grant");
            return new HashSet<>();
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
        sb.append("adb shell appops set ").append(packageName).append(" SYSTEM_ALERT_WINDOW allow\n");
        sb.append("\n# Verify:\n");
        sb.append("adb shell dumpsys package ").append(packageName).append(" | grep granted=true\n");
        sb.append("adb shell appops get ").append(packageName).append(" SYSTEM_ALERT_WINDOW\n");
        return sb.toString();
    }

    private static void log(String msg) {
        System.out.println(TAG + ": " + msg);
    }
}
