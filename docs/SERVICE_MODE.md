# Service Mode

Service Mode is a temporary, device-local mode intended for workshop or dealer visits.

## User-facing behavior

- Start Service Mode immediately and choose a restoration date in the head unit's local time zone.
- Optionally hide selected third-party launchable apps. OverDrive remains visible and available.
- Optionally pause Automations and Key Mapping without deleting saved rules or bindings.
- End the mode early from the Service Mode page, or let the daemon restore the saved state at 23:59:59.999 on the selected date in the head unit's local time zone. During the same boot, expiry uses Android's monotonic elapsed-realtime clock and the persisted boot counter, so moving the wall clock backwards cannot extend the schedule. After a reboot, the wall deadline is used because elapsed realtime resets. A 15-second reconciliation worker runs only while Service Mode is active or restoring, and restores it on the first check after the deadline.
- If restoration cannot complete, the mode remains marked as restoring and the daemon retries. An independent app-process recovery attempt also runs on boot or activity launch if the daemon is unavailable, using authenticated ADB when available.

The page is available from **Android Settings → Service Mode** and from the WebUI sidebar.

## Package boundaries

Service Mode never hides, disables, or changes the OverDrive launcher entry. The standard `MainActivity` launcher entry remains the single OverDrive launcher entry throughout the mode. The shell-UID daemon may disable selected third-party packages only.

The shell-UID daemon may disable selected third-party packages with `pm disable-user`. The app picker uses PackageManager and fails closed if it cannot classify a package. System and updated-system apps, OverDrive, home launchers, and enabled input methods are excluded. The daemon repeats the user-installed/launchable/system-app validation immediately before every package-disable operation, including periodic reconciliation. A package that is reclassified as a system app is not disabled again.

Only packages that were enabled when Service Mode first changed them are recorded for restoration. Packages that were already disabled are left disabled. If a package is uninstalled before restoration, it is skipped. A failed restore remains in the state and is retried.

### Independent uninstall recovery and limitations

Before hiding any packages, Service Mode writes an external recovery script at
`/sdcard/Download/OverDrive-ServiceMode-restore.sh` and starts a separate shell guardian
in `/data/local/tmp`. If it cannot stage that recovery script, activation fails
**without hiding any app**. The guardian checks for OverDrive uninstallation every
30 seconds and restores captured package states if it finds the app absent. On a
normal End, the daemon removes the rescue script and stops the guardian.

The app also attempts expiry/End restoration at boot and at native activity startup
through its authenticated ADB bridge, independently of a running camera daemon.
Only the daemon finalizes and clears `serviceMode` in the shared config.

**Limitation:** Android does not guarantee that a removed package can run uninstall
cleanup. An ADB restart, device reboot, or process kill may terminate the detached
guardian, especially when OverDrive has already been uninstalled. In that case the
shell-owned recovery script remains at `/data/local/tmp/overdrive-service-mode-rescue.sh`:
from a trusted ADB shell, run
`sh /data/local/tmp/overdrive-service-mode-rescue.sh` to restore the original
enabled states. Do **not** execute the `/sdcard/Download` copy: that location is
externally writable. Do not delete the rescue copy while hidden apps remain. This is a
best-effort recovery measure, **not a guarantee of automatic restoration after
uninstall + reboot**, and must be evaluated on the target head unit before use.

## Authentication and persistence

The Service Mode page and `/api/service-mode/*` routes require a valid JWT; the loopback authentication fallback is not accepted.

The `serviceMode` section is private runtime state. It is excluded from config backup export/import and rejected by both the generic HTTP settings writer and the app-process cross-UID `UPDATE_SECTION` / `UPDATE_VALUES` IPC writer. It is managed only by `ServiceModeManager`, so a settings payload or restored backup cannot silently reactivate an old schedule.

## Feature gates

The Automations gate is **fire-only**: telemetry continues updating its snapshots and a paused signal is treated as consumed, so an unchanged snapshot does not run after End. It blocks automatic and explicit runs, including action groups called from keys and quick controls, while leaving saved definitions intact. The Key Mapping gate rejects physical-key action dispatch while leaving stored bindings intact. Ending Service Mode reopens the gates.

## Device compatibility

Package state changes depend on the head unit's Android PackageManager and shell package manager behavior. Validate on each supported firmware family before release; the feature does not claim to have been built or exercised on every BYD head unit.

## Pre-release car acceptance test (not yet performed)

Use an expendable, third-party app (never a vehicle-control or diagnostic app) on a parked, controlled test vehicle.

1. Record its original PackageManager enabled state. Start Service Mode with the app plus both optional feature pauses. Verify the app is hidden and action-group/key/automation requests are rejected rather than reported successful. Verify an otherwise eligible app is still excluded if it is acting as the home launcher or an enabled keyboard.
2. End manually. Verify the original app state is restored exactly, the original launcher remains available, and rules/bindings still exist. Verify both the guardian marker and the recovery copies are cleaned up.
3. Start again, reboot in the middle of the visit, and verify paused features and hidden apps remain paused/hidden until End or the selected deadline. Verify a paused boot event is not fired or replayed on End.
4. Start with an expendable app and verify exactly one guardian is running across daemon restarts. Uninstall OverDrive while Service Mode is active and the guardian is running; verify the previously enabled app is restored. Confirm the trusted `/data/local/tmp` recovery command works if automatic recovery is interrupted; never execute the Downloads copy.
5. Switch between English and Hebrew after the page has loaded. Verify checkbox/label spacing, status translations, and cache behavior in the head unit's WebView.

**Status:** Host-only checks passed; the car acceptance test remains required and unverified.
