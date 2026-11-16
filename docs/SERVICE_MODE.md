# Service Mode

Service Mode is a temporary, device-local mode intended for workshop or dealer visits.

## User-facing behavior

- Start Service Mode immediately and choose a restoration date in the head unit's local time zone.
- Optionally hide selected third-party launchable apps. OverDrive remains visible and available.
- Optionally pause Automations and Key Mapping without deleting saved rules or bindings.
- End the mode early from the Service Mode page, or let the daemon restore the saved state at 23:59:59.999 on the selected date in the head unit's local time zone. During the same boot, expiry uses Android's monotonic elapsed-realtime clock and the persisted boot counter, so moving the wall clock backwards cannot extend the schedule. After a reboot, the wall deadline is used because elapsed realtime resets. A 15-second reconciliation worker runs only while Service Mode is active or restoring, and restores it on the first check after the deadline.
- If restoration cannot complete, the mode remains marked as restoring and the daemon retries.

The page is available from **Android Settings → Service Mode** and from the WebUI sidebar.

## Package boundaries

Service Mode never hides, disables, or changes the OverDrive launcher entry. The standard `MainActivity` launcher entry remains the single OverDrive launcher entry throughout the mode. The shell-UID daemon may disable selected third-party packages only.

The shell-UID daemon may disable selected third-party packages with `pm disable-user`. The app picker uses PackageManager and fails closed if it cannot classify a package. System and updated-system apps, OverDrive, home launchers, and enabled input methods are excluded. The daemon repeats the user-installed/launchable/system-app validation immediately before every package-disable operation, including periodic reconciliation. A package that is reclassified as a system app is not disabled again.

Only packages that were enabled when Service Mode first changed them are recorded for restoration. Packages that were already disabled are left disabled. If a package is uninstalled before restoration, it is skipped. A failed restore remains in the state and is retried.

## Authentication and persistence

The Service Mode page and `/api/service-mode/*` routes require a valid JWT; the loopback authentication fallback is not accepted.

The `serviceMode` section is private runtime state. It is excluded from config backup export/import and rejected by both the generic HTTP settings writer and the app-process cross-UID `UPDATE_SECTION` / `UPDATE_VALUES` IPC writer. It is managed only by `ServiceModeManager`, so a settings payload or restored backup cannot silently reactivate an old schedule.

## Feature gates

The Automations gate blocks automatic and explicit automation execution while leaving saved definitions intact. The Key Mapping gate rejects physical-key action dispatch while leaving stored bindings intact. Ending Service Mode reopens the gates.

## Device compatibility

Package state changes depend on the head unit's Android PackageManager and shell package manager behavior. Validate on each supported firmware family before release; the feature does not claim to have been built or exercised on every BYD head unit.
