# Incident Report: Infotainment System Freeze & Watchdog Reboot During OTA Update (v60.3)

**Target Vehicle**: BYD Sealion 7 (DiLink 100 / Android 11, Qualcomm Snapdragon SA8155P)  
**Incident Timestamp**: 2026-11-10 ~16:47:00 – 16:50:00 CET  
**Affected Version**: Overdrive v60.3 (in-app APK update from v60.x)  
**Severity**: **Critical** (Complete IHU freeze, unresponsive touchscreen, Android Watchdog hard reboot)  
**Bugreport Artifacts**: `crash_logs/incident_20261110_1650/` (`bugreport.zip`, `anr/`, `cam_daemon.log`, `acc_sentry_daemon.log`)

---

## 1. Executive Summary

During an in-app update to Overdrive v60.3 on a BYD Sealion 7, the central infotainment unit (IHU) completely locked up, froze on user input, and triggered an automatic kernel/watchdog hardware reboot after approximately 2 minutes.

Post-mortem investigation of the Android `bugreportz` dump and system traces revealed that **this was not a transient glitch of package installation**, but a **catastrophic IPC starvation deadlock**:

1. In-app update (`pm install`) replaced the APK, killing or restarting `com.overdrive.app`.
2. The background shell daemon (`acc_sentry_daemon`, running as UID 2000) survived the update and continued polling via `am broadcast -W`.
3. Because `-W` waits synchronously for the broadcast to finish, each shell broadcast invocation tied up an Android `system_server` Binder worker thread in `IntentReceiver.waitForFinish()`.
4. The daemon's local timeout (10s) caused it to abort locally and retry continuously, while `system_server` retained the blocked threads until system-level timeouts.
5. **All 32+ Binder threads in `system_server` were 100% starved**, freezing `SystemUI`, `MediaProvider`, and vehicle adapters (`CarAdapterService`), which forced the Android Watchdog to reboot the entire infotainment hardware.

---

## 2. Root Cause Analysis

### A. The Primary Culprit: `AccSentryDaemon.java` Synchronous Broadcasts (`-W`)

In `app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java` (lines 6544–6567):

```java
private int sendParkedLinkBroadcast(long generation, String command) {
    String shellCommand =
            "am broadcast -W --receiver-foreground"
                    + " --include-stopped-packages"
                    + " -a " + com.overdrive.app.power.ParkedLinkContract.ACTION
                    + " -n com.overdrive.app/com.overdrive.app.power.ParkedLinkReceiver"
                    + " --es command " + command
                    + " --el generation " + generation;
    ShellResult result = execShellResult(shellCommand, 10_000L, null);
    ...
}
```

#### What went wrong:
- When the APK is being installed or the app process is restarting, `ParkedLinkReceiver` cannot immediately consume or acknowledge the broadcast.
- The `-W` flag in `am broadcast` instructs `ActivityManagerShellCommand.runSendBroadcast()` to block synchronously on `IntentReceiver.waitForFinish()`.
- In Android `system_server`, this consumes one thread from the limited global Binder thread pool (default max 31 + 1 = 32 threads).
- `AccSentryDaemon` times out after 10,000ms (`execShellResult`), kills its local shell sub-process, and loops to try again.
- However, **in `system_server`, the Binder thread remains blocked** waiting for Android's broadcast queue timeout.
- Within 1–2 minutes, every single Binder worker thread (`Binder:818_1` through `Binder:818_21`) in `system_server` was deadlocked waiting for the same broadcast:

```text
"Binder:818_1" prio=5 tid=9 Waiting
  at java.lang.Object.wait(Native method)
  - waiting on <0x0a953046> (a com.android.server.am.ActivityManagerShellCommand$IntentReceiver)
  at com.android.server.am.ActivityManagerShellCommand$IntentReceiver.waitForFinish(ActivityManagerShellCommand.java:725)
  - locked <0x0a953046> (a com.android.server.am.ActivityManagerShellCommand$IntentReceiver)
  at com.android.server.am.ActivityManagerShellCommand.runSendBroadcast(ActivityManagerShellCommand.java:749)
  at com.android.server.am.ActivityManagerShellCommand.onCommand(ActivityManagerShellCommand.java:198)
  at com.android.server.am.ActivityManagerService.onShellCommand(ActivityManagerService.java:10870)
  at android.os.Binder.shellCommand(Binder.java:929)
  at android.os.Binder.onTransact(Binder.java:813)
  at android.app.IActivityManager$Stub.onTransact(IActivityManager.java:5053)
  at com.android.server.am.ActivityManagerService.onTransact(ActivityManagerService.java:2940)

... [Repeated across ALL 32 Binder threads in system_server: Binder:818_1 to Binder:818_21]
```

### B. The Secondary Factor: OpenGL Thread Stall in Camera Daemon

In `cam_daemon.log` at timestamp 16:47:57:
```text
CRITICAL: GL thread blocked for 25591ms - forcing process restart
```
Recent changes introducing OpenGL fence sync without fallback on Qualcomm Adreno 640 (Snapdragon SA8155P) caused a 25.6-second stall in the camera render thread. When the app's native graphics and main loops stalled, incoming broadcast delivery ground to a halt, directly accelerating the Binder exhaustion above.

### C. Lifecycle Gap in Update Flow

In `AppUpdater.java` and `UpdateLifecycle.java`:
- The daemon sweep and hard reset (`stopAllDaemons`, `UpdateLifecycle.hardResetDaemons`) currently execute *after* or during the UI update launch, but **not as a guaranteed synchronous prerequisite before triggering `pm install`**.
- As a result, when `pm install` begins swapping the APK, the shell daemons (`acc_sentry_daemon`, `cam_daemon`) continue executing at full speed in the background, firing commands at a dying/restarting app.

---

## 3. Failure Cascade Diagram

```text
+--------------------------------------------------------------------+
| 1. User taps "Update" -> AppUpdater downloads v60.3 & runs pm install|
+--------------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------------+
| 2. Android kills com.overdrive.app to replace package binaries     |
|    (Shell daemon acc_sentry_daemon is NOT killed and keeps running) |
+--------------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------------+
| 3. acc_sentry_daemon repeatedly runs:                              |
|    am broadcast -W ... PARKED_LINK                                 |
+--------------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------------+
| 4. system_server allocates Binder worker thread for -W             |
|    Thread blocks in IntentReceiver.waitForFinish() waiting for app |
+--------------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------------+
| 5. acc_sentry_daemon 10s timeout triggers -> kills local shell     |
|    and retries -> allocates ANOTHER Binder thread in system_server |
+--------------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------------+
| 6. 32/32 Binder threads in system_server become DEADLOCKED         |
|    - SystemUI freezes (cannot check permissions)                   |
|    - MediaProvider freezes (JobScheduler blocked)                  |
|    - CarAdapterService freezes                                     |
+--------------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------------+
| 7. Android Watchdog triggers after 60s -> System Crash & Reboot    |
+--------------------------------------------------------------------+
```

---

## 4. Required Fixes / Action Plan

To ensure vehicle stability and prevent future update crashes, the following changes are required:

### Fix 1: Pre-Install Daemon Shutdown in `AppUpdater.java`
Before executing the `pm install` or session install command:
1. Write `.disabled` sentinels for all daemons (`acc_sentry_daemon.disabled`, `camera_daemon.disabled`, `telegram_bot_daemon.disabled`).
2. Execute a synchronous kill:
   ```bash
   pkill -9 -f acc_sentry_daemon; pkill -9 -f cam_daemon; pkill -9 -f sentry_daemon
   ```
3. Only proceed with `pm install` once background shell processes have terminated.

### Fix 2: Remove Synchronous `-W` and Add Backoff in `AccSentryDaemon.java`
In `AccSentryDaemon.sendParkedLinkBroadcast()`:
1. **Remove `-W` from `am broadcast`**:
   Using an asynchronous broadcast prevents `system_server` from locking a Binder thread inside `waitForFinish()`.
2. **Process Liveness Verification**:
   Before dispatching broadcasts, verify that `com.overdrive.app` is actually alive (e.g. check `pidof com.overdrive.app` or verify an IPC ping).
3. **Exponential Backoff on Failure**:
   If the broadcast fails or returns unknown, back off (e.g., 5s, 15s, 30s, 60s) instead of aggressively retrying every 10 seconds.

### Fix 3: Adreno 640 Fence Sync Guard in `cam_daemon` / `EncoderLane`
Add a hardware-safe fallback for `glClientWaitSync` with bounded timeouts to prevent the camera thread from locking for 25+ seconds on Qualcomm SA8155P.
