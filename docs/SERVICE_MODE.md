# Service Mode

Service Mode is a temporary, device-local mode intended for workshop or dealer visits.

## User-facing behavior

- Start Service Mode immediately and choose a restoration date in the head unit's local time zone.
- Optionally hide the OverDrive launcher entry and selected third-party launchable apps.
- Optionally pause Automations and Key Mapping without deleting saved rules or bindings.
- End the mode early from the Service Mode page, or let the daemon restore the saved state at 23:59:59.999 on the selected date in the head unit's local time zone. The 15-second reconciliation loop restores it on the first check after the deadline.
- If restoration cannot complete, the mode remains marked as restoring and the daemon retries.

The page is available from **Android Settings → Service Mode** and from the WebUI sidebar.

## Package and launcher boundaries

OverDrive is never disabled as a package. Its launcher entry is a dedicated `activity-alias` targeting `MainActivity`. The authenticated app-process bridge changes only that alias with `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)`. The bridge service is DUMP-protected so the shell daemon can restart it if Android reclaims the non-sticky app process; its socket commands still require the existing HMAC authentication. The daemon, boot receivers, and local HTTP server remain available to restore it.

The shell-UID daemon may disable selected third-party packages with `pm disable-user`. The app picker uses PackageManager and fails closed if it cannot classify a package. System and updated-system apps, OverDrive, home launchers, and enabled input methods are excluded. The daemon repeats the user-installed/launchable/system-app validation immediately before every package-disable operation, including periodic reconciliation. A package that is reclassified as a system app is not disabled again.

Only packages that were enabled when Service Mode first changed them are recorded for restoration. Packages that were already disabled are left disabled. If a package is uninstalled before restoration, it is skipped. A failed restore remains in the state and is retried.

## Authentication and persistence

The Service Mode page and `/api/service-mode/*` routes require a valid JWT; the loopback authentication fallback is not accepted. The app-process launcher operation uses the existing authenticated bridge.

The `serviceMode` section is private runtime state. It is excluded from config backup export/import and rejected by both the generic HTTP settings writer and the app-process cross-UID `UPDATE_SECTION` / `UPDATE_VALUES` IPC writer. It is managed only by `ServiceModeManager`, so a settings payload or restored backup cannot silently reactivate an old schedule.

## Feature gates

The Automations gate blocks automatic and explicit automation execution while leaving saved definitions intact. The Key Mapping gate rejects physical-key action dispatch while leaving stored bindings intact. Ending Service Mode reopens the gates.

## Device compatibility

Package state changes depend on the head unit's Android PackageManager and shell package manager behavior. The launcher alias operation uses the app process and is intentionally separate from package-level shell commands. Validate on each supported firmware family before release; the feature does not claim to have been built or exercised on every BYD head unit.
