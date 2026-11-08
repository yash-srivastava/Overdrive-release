# Evidence for V60-11b (exported-component holes left open in braveheart v60.1)

Recorded live on the BYD Seal 2023 bench (emulator-5638, `overdrive-car-sim`, official
release APK, Android 14 x86_64 KVM).

- `exploit-official-release-bench.mp4` — screen video of a foreign-UID attacker app sending
  `com.overdrive.app.START_LOCATION_ACTIVITY` to the exported launcher MainActivity and a
  forged `com.byd.action.ACC_ON` broadcast to the exported BootReceiver. Companion logcat
  evidence: `BootReceiver: Starting daemons (trigger: com.byd.action.ACC_ON)`.
- `exploit-after.png` — screenshot after the exploit run on the official release.
- `fixed-blocked-bench.mp4` — same attack against the fixed build **versioned 60.1 (versionCode 204, built with -PoverdriveVersionName=60.1 -PoverdriveVersionCode=204)** on the same bench:
  BootReceiver no longer reacts (DUMP gate blocks delivery), no LocationSidecarService
  process is started (callingPackage guard).
- `fixed-after.png`, `fixed-probe-results.txt` — probe results on the fixed build.

REA cross-check: `aapt2 dump xmltree` on `overdrive-release-braveheart-v60.1.apk`
(versionCode 204) shows MainActivity exported with the custom action filter and no
permission, and BootReceiver exported with com.byd.action.* filters and no permission;
the five services are DUMP-gated in v60.1 (this PR deliberately does not touch them).

## Re-recorded (v60.1 bench, explicit on-screen verdict, 2026-11-08)

Attacker app = `com.example.attacker`, a foreign-UID app with NO `android.permission.DUMP`.
It (1) starts `com.overdrive.app/.ui.MainActivity` with action `START_LOCATION_ACTIVITY`,
(2) sends an explicit forged `com.byd.action.ACC_ON` broadcast to `BootReceiver`, then
(3) scans `/proc` every 500 ms for `byd_cam_daemon` and prints a big on-screen verdict:
red `== EXPLOIT WORKS ==` (daemon pid shown) or green `== BLOCKED ==`.

- `exploit-official-release-bench.mp4` (3.49 MB, 29.3 s) — **official** braveheart-v60.1
  (sha256 `0192402c...`, versionCode 204) on emulator-5638. Fresh logcat after the forged
  broadcast: `BootReceiver: Received broadcast: com.byd.action.ACC_ON` ->
  `Starting daemons (trigger: com.byd.action.ACC_ON)` -> `Daemon startup initiated
  successfully` (1 reaction), and `byd_cam_daemon` is caught running in `/proc`
  (visible as the red EXPLOIT WORKS verdict on screen).
- `fixed-blocked-bench.mp4` (2.86 MB, 29.2 s) — the **same attack on this fixed build
  versioned 60.1 (versionCode 204, sha256 e7fcad3a... verified on device)**. Fresh logcat:
  **ZERO** `BootReceiver: Starting daemons` (delivery blocked by the
  `android.permission.DUMP` gate), **ZERO** `byd_cam_daemon` in `/proc` for the whole
  14 s scan -> green `== BLOCKED ==` verdict on screen.
- `exploit-after.png` / `fixed-after.png` — final screens of each run.

Note on the "built for an older version of Android" dialog sometimes visible at the start
of the exploit video: that is Android's `UnsupportedTargetSdkDialog` (the app targets
SDK 25, on both the official release and this branch — pre-existing, unrelated to this
fix). It is dismissed during capture so the attacker verdict stays on screen.

Legitimate senders are NOT affected (verified live on the fixed build): `com.android.shell`
(UID 2000 — the UID the daemons run as) and `system_server` (UID 1000) both hold
`android.permission.DUMP`, so shell/system-sent `com.byd.action.ACC_ON` still reaches
`BootReceiver` and starts all three daemons, and the `callingPackage` guard explicitly
allows `com.android.shell` for the launcher location-start path.
