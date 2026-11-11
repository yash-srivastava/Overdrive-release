# Incident Report: Infotainment System Freeze & Watchdog Reboot During OTA Update

**Target Vehicle**: BYD Sealion 7 (DiLink 100 / Android 11, Qualcomm Snapdragon SA8155P, Adreno 640)  
**Incident Timestamp**: 2026-11-10 ~16:47:00 – 16:50:00 CET  
**Affected Versions**:
- **Latent vulnerability**: `braveheart >= v57.0` (introduced in `263e7df0`, Oct 23, 2026; `alpha-v55.2` stable channel is **unaffected**)
- **Acute regression / trigger**: `braveheart v60.0–v60.3` (triggered after GPU fence/barrier changes in PR #571, PR #599, PR #600 on Nov 10, 2026)  
**Severity**: **Critical** (Complete IHU freeze, unresponsive touchscreen, Android Watchdog hard reboot)  
**Bugreport Artifacts**: `crash_logs/incident_20261110_1650/` (`bugreport.zip`, `anr/`, `cam_daemon.log`, `acc_sentry_daemon.log`)

---

## 1. Executive Summary

During an in-app update to Overdrive `braveheart-v60.3` on a BYD Sealion 7, the central infotainment unit (IHU) completely locked up, froze on user input, and triggered an automatic kernel/watchdog hardware reboot after approximately 2 minutes.

Post-mortem investigation of the Android `bugreportz` dump and system traces revealed that **this was not a transient glitch of package installation**, but a **catastrophic IPC starvation deadlock** caused by the interaction of a **latent update/broadcast flaw (present since `v57.0`)** and an **acute GPU render-thread stall introduced in `v60.x`**:

1. `AppUpdater` wrote `acc_sentry_daemon.disabled` and `camera_daemon.disabled`, killed the daemons, and then **prematurely deleted both `.disabled` sentinels immediately before executing `pm install`**.
2. During the 30–60s `pm install` window, the shell watchdogs (`start_acc_sentry.sh`, `start_cam.sh`) immediately resurrected `acc_sentry_daemon` and `cam_daemon` (UID 2000) while `com.overdrive.app` was dead or mid-replacement.
3. In `v60.x`, newly merged GLES3 fence/barrier optimizations (**PR #571**, **PR #599**, **PR #600**) stalled the Qualcomm Adreno 640 GL thread for **25,591 ms** (`PanoramicCameraGpu`), triggering a `CameraDaemon` kill/restart loop (`exit 137`) and repeated 155-permission `dumpsys`/`pm grant` storms against `system_server`.
4. Meanwhile, `acc_sentry_daemon` repeatedly polled `ParkedLinkReceiver` via synchronous `am broadcast -W`. Because `com.overdrive.app` and the system broadcast queue were stalled during installation/recovery, each `am broadcast -W` call tied up an Android `system_server` Binder worker thread inside `IntentReceiver.waitForFinish()`.
5. `acc_sentry_daemon`'s local timeout (10s) killed only its local subshell and retried continuously, while `system_server` retained the blocked Binder threads until Android's broadcast timeout.
6. **All 32 Binder threads in `system_server` were 100% starved**, freezing `SystemUI`, `MediaProvider`, and vehicle adapters (`CarAdapterService`), forcing the Android Watchdog to hard-reboot the infotainment hardware.

---

## 2. Regression Timeline: Why Latent in v57.0 vs. Acute in v60.x

| Layer | Commit / PR | Version | Role in the Incident |
| :--- | :--- | :--- | :--- |
| **Premature `.disabled` cleanup** | `1f3ee725` → extended in `263e7df0` | `v44.x` / `v57.0` | `AppUpdater.java` sweeps `'disabled for update'` markers *before* `pm install`, allowing shell watchdogs to respawn daemons mid-install. |
| **Synchronous `PARKED_LINK` polling** | `263e7df0` | `braveheart-v57.0` | Introduces `Di5ParkedPowerHold` + `AccSentryDaemon.sendParkedLinkBroadcast()` using `am broadcast -W` every 10s with no update-in-progress gate or timeout backoff. *(Not present in `alpha-v55.2`.)* |
| **Between `v57.0` and `v59.x`** | — | `v57.0–v59.x` | **Latent**: on normal `ACC ON` updates without GPU stalls, the restarted app drained broadcasts in `7–18 ms` before 32 concurrent Binder threads could accumulate. |
| **GLES3 fence & AI barrier changes (Trigger)** | PR #571 (`18d0956c`), PR #599 (`ad93972b`), PR #600 (`caa0880d`) | `braveheart-v60.0–v60.3` | **Detonator**: replaces `glFinish()` with `glFenceSync`/`glClientWaitSync` in `EncoderLane.java` (5ms retry loop) and skips idle `gpuSampleBarrier()` in `AiLaneGl.java`. Stalls Adreno 640 GL thread for **25.6s** right after daemon respawn, wedging broadcast processing and saturating all 32 Binder threads. |

---

## 3. Root Cause Analysis

### A. Lifecycle Gap: Premature Sentinel Removal in `AppUpdater.java`

In `app/src/main/java/com/overdrive/app/updater/AppUpdater.java` (lines 2248–2258 prior to fix):
- During update script generation, `AppUpdater` writes `acc_sentry_daemon.disabled` and `camera_daemon.disabled` (`"disabled for update"`) and terminates running daemons.
- However, just two lines before `pm install -r -d`, the generated script swept and deleted both `.disabled` files.
- Because `pm install` takes 30–60 seconds on DiLink 100, the background shell supervisors (`start_acc_sentry.sh`, `start_cam.sh`) observed the missing sentinels and immediately relaunched the daemons while `com.overdrive.app` was dead or being replaced.

### B. Primary IPC Starvation Mechanism: `AccSentryDaemon.java` Synchronous Broadcasts (`-W`)

In `app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java` (`sendParkedLinkBroadcast`):

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

- When the APK is being installed or the app/broadcast queue is stalled, `ParkedLinkReceiver` cannot immediately consume or acknowledge the ordered broadcast.
- The `-W` flag instructs `ActivityManagerShellCommand.runSendBroadcast()` in `system_server` to block synchronously on `IntentReceiver.waitForFinish()`, consuming one thread from `system_server`'s global Binder pool (32 threads max).
- `AccSentryDaemon` times out after `10,000 ms` (`execShellResult`), kills its local shell client, and retries on the next 10s tick — **while the Binder thread inside `system_server` remains blocked** until Android's internal broadcast timeout.
- Within 1–2 minutes, all 32 Binder threads (`Binder:818_1` through `Binder:818_21`, 32 blocked threads captured in `anr_2026-11-10-16-48-04-896`) deadlocked on `waitForFinish`:

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

... [Repeated across ALL 32 Binder threads in system_server]
```

### C. The `v60.x` Catalyst: Adreno 640 OpenGL Thread Stall & Daemon Restart Storm

In `cam_daemon.log` during the `v60.3` installation window (`16:46:03` – `16:48:05`):
```text
[Tue Nov 10 16:46:03 CET 2026] Starting CameraDaemon...
PermissionGranter: Granting permissions for com.overdrive.app (UID 2000, 155 total)
DiLink5QCarCam: [2026-11-10 16:47:57.698] fast_cam_capture exited unexpectedly with code 0
PanoramicCameraGpu: [2026-11-10 16:47:57.693] CRITICAL: GL thread blocked for 25591ms - forcing process restart
[Tue Nov 10 16:48:02 CET 2026] Daemon exited with code 137 after 119s (attempt 1), retrying in 3s...
```
PR #571 (`EncoderLane` nonblocking `glFenceSync` / `glClientWaitSync` with 5ms `drainOnce` retries) combined with PR #599/#600 (`AiLaneGl` idle `gpuSampleBarrier()` skip) caused a **25.6-second GL stall** on Qualcomm Adreno 640 (Snapdragon SA8155P), followed by repeated `SIGKILL` (`137`) restarts and heavy `PermissionGranter` Binder traffic (`dumpsys` + 155 permissions) right as `pm install` was replacing the package. This stalled broadcast dispatch long enough for `sendParkedLinkBroadcast` retries to exhaust the entire `system_server` Binder pool.

---

## 4. Failure Cascade Diagram

```text
+------------------------------------------------------------------------+
| 1. User taps "Update" -> AppUpdater writes .disabled & kills daemons   |
|    BUG (since v57.0): script deletes .disabled BEFORE running pm install|
+------------------------------------------------------------------------+
                                   |
                                   v
+------------------------------------------------------------------------+
| 2. pm install replaces com.overdrive.app (30-60s)                      |
|    Shell watchdogs immediately respawn acc_sentry_daemon & cam_daemon  |
+------------------------------------------------------------------------+
                                   |
                                   v
+------------------------------------------------------------------------+
| 3. v60.x Catalyst (PR #571/#600): Adreno 640 GL thread stalls 25.6s    |
|    cam_daemon enters kill-restart storm + 155-perm dumpsys/grant load  |
+------------------------------------------------------------------------+
                                   |
                                   v
+------------------------------------------------------------------------+
| 4. acc_sentry_daemon polls every 10s (since v57.0):                    |
|    am broadcast -W ... ParkedLinkReceiver                              |
|    system_server blocks a Binder thread in IntentReceiver.waitForFinish|
+------------------------------------------------------------------------+
                                   |
                                   v
+------------------------------------------------------------------------+
| 5. Daemon 10s local timeout kills subshell & retries -> allocates      |
|    ANOTHER Binder thread while previous threads stay hung in OS        |
+------------------------------------------------------------------------+
                                   |
                                   v
+------------------------------------------------------------------------+
| 6. 32/32 Binder threads in system_server DEADLOCKED                    |
|    SystemUI, MediaProvider & CarAdapterService freeze (ANRs @ 16:47-48)|
+------------------------------------------------------------------------+
                                   |
                                   v
+------------------------------------------------------------------------+
| 7. Android Watchdog triggers -> Full IHU Crash & Hardware Reboot       |
+------------------------------------------------------------------------+
```

---

## 5. Fixes Implemented in PR #602 & Follow-Up Recommendations

### Shipped in PR #602
1. **Preserve `.disabled` Sentinels Across `pm install` (`AppUpdater.java`)**:
   - Removed the pre-install sweep of `camera_daemon.disabled` and `acc_sentry_daemon.disabled`.
   - Sentinels remain intact throughout `pm install` so shell watchdogs cannot resurrect daemons mid-installation; `UpdateLifecycle.hardResetDaemons()` already cleans up `'disabled for update'` markers post-update once the new APK has booted cleanly.
2. **Update-In-Progress Gate & 30s Timeout Backoff (`AccSentryDaemon.java`)**:
   - `sendParkedLinkBroadcast()` skips dispatching `am broadcast -W` whenever `UPDATE_IN_PROGRESS_FILE` or `acc_sentry_daemon.disabled` exists.
   - Added a `30,000 ms` backoff (`PARKED_LINK_TIMEOUT_BACKOFF_MS`) whenever `sendParkedLinkBroadcast()` times out or receives no ordered broadcast result, mathematically preventing rapid saturation of `system_server`'s 32 Binder threads even if the app process hangs outside of an update.

### Recommended Follow-Up (Camera / GPU Pipeline)
3. **Adreno 640 Fence Sync Guard (`EncoderLane.java` / `AiLaneGl.java`)**:
   - Investigate and guard `glFenceSync` / `glClientWaitSync` slot-pinning (PR #571) and skipped `gpuSampleBarrier()` paths (PR #600) on Qualcomm SA8155P / Adreno 640 to eliminate the 25.6s GL thread stall in `CameraDaemon`.
