# Service Mode

Service Mode is intended for temporary dealer visits and other situations where the head unit should expose fewer apps and OverDrive features.

## User workflow

1. Open **Settings → Service Mode** in the Android app, or **Service Mode** in the WebUI sidebar.
2. Choose the date on which Service Mode should end.
3. Choose whether to hide the OverDrive launcher entry.
4. Select any user-installed, launchable apps to disable temporarily.
5. Select the OverDrive features to pause. The initial feature gates are **Automations** and **Key Mapping**.
6. Select **Start now**. Service Mode starts immediately and expires at 23:59:59 in the head unit's local time on the selected end date. The date is interpreted by the head unit, not by the browser's timezone, so a remote WebUI session uses the vehicle's local calendar day. The daemon checks every 15 seconds, so restoration happens on the first check after that deadline.
7. Select **End now** in the WebUI to restore early.

The local WebUI remains available while Service Mode is active. The OverDrive package and daemon are not disabled: only the dedicated launcher alias is disabled. This lets the daemon keep the expiry worker running and lets the WebUI end the mode.

## App visibility and restoration

The app picker lists third-party launchable apps only. Android/BYD system packages and OverDrive itself are excluded. Selected apps are disabled for Android user 0 with `pm disable-user`.

Before changing a package, the daemon stores its previous package-manager enabled state. On expiry or **End now**, it restores the previous state (`default-state` or `enable`). Apps that were already disabled before Service Mode are not changed and are not enabled during restoration.

The OverDrive launcher alias is tracked and restored separately. The app itself, daemon, boot receiver, and HTTP server stay installed/enabled.

## Feature gates

Feature gates are transient and do not rewrite saved user configuration:

- **Automations** pauses automatic event delivery, queued automation execution, manual runs, and action-group execution.
- **Key Mapping** stops the app-process key dispatcher from treating physical keys as OverDrive bindings and rejects the keymap fire endpoint.

The saved automation definitions and key bindings remain intact and become available again when Service Mode ends. More feature gates can be added as additional OverDrive subsystems are wired into the same policy.

## Persistence and recovery

Service Mode state is stored in the shared unified configuration so both app and daemon processes see the same selection. The shell-UID daemon reconciles the package state every 15 seconds, restores on expiry, and retries restoration failures. On daemon startup, an active or expired Service Mode is reconciled again, so a daemon restart does not lose the end date or the original package states.

If a selected app cannot be restored immediately, the status page reports the error and the daemon retries. The mode remains in a restoring state until all recorded package states and the OverDrive launcher alias have been restored.

## Operational caution

Disabling an app is stronger than hiding only its launcher icon. Do not select an alternate launcher, keyboard/input method, or an app required to reach vehicle controls. The picker intentionally excludes system packages, but the user is responsible for the third-party apps they select.
