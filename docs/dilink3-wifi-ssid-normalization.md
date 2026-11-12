# Preserve real Wi-Fi SSIDs containing "unknown"

## Problem

The daemon's Wi-Fi monitor previously checked whether the raw SSID contained the
substring `unknown`, rather than checking whether Android had returned a real
redaction marker. A legitimate, user-chosen network name such as
`Unknown-Guest` was therefore shown as the generic `WiFi` placeholder. The
problem affected both the `WifiManager.getConnectionInfo()` path and the
`cmd wifi status` parser, including fallback readings on shell-UID daemons.

An arbitrary substring match cannot distinguish a withheld name from a real
SSID. It also loses the original network identity for status displays and
network-name-based consumers of `NetworkMonitor`.

## Change

- Normalize names in one shared helper across the Android framework, Binder,
  `cmd wifi status`, and `wpa_cli` fallback paths.
- Strip surrounding quotes, then reject only exact, case-insensitive missing
  values: empty, `<none>`, `<unknown ssid>`, `<unknown>`, `unknown ssid`, and
  `unknown`.
- Preserve names that merely *contain* the word `unknown` (for example,
  `Unknown-Guest` or `My unknown network`).
- Expand the existing parser regression-test source to cover both redacted
  markers and genuine SSIDs; the test suite was not executed while preparing
  this change.

The SSID redaction/placeholder policy is not relaxed for known framework
markers; this change does not bypass Android permission checks, query any new
system service, or start additional processes. Existing Wi-Fi metered-hint and
signal readings are unchanged. Standard Java APIs used here are compatible
with the target DiLink 3.0 Android 10 / API 29 head unit.

## Manual verification (parked device)

1. Connect to a test Wi-Fi network named `Unknown-Guest`. Confirm OverDrive
   reports the actual network name rather than `WiFi`.
2. Repeat with a typical SSID (for example `HomeNet`) and confirm no change.
3. Simulate or observe Android reporting `<unknown ssid>` or `<none>` to a
   restricted process and confirm the generic placeholder remains in use.
4. Verify that the Wi-Fi metered indicator and signal strength continue to
   come from their previous sources.
5. If using a head unit whose daemon falls back to shell commands, confirm
   the same visible SSID behavior and that no additional shell polling occurs.

## Verification status and limitation

The patch can be checked syntactically with `git apply --check --verbose` and
reviewed with `git diff`. No build, tests, benchmarks, or vehicle verification
were performed. The bare SSID `unknown` remains a conservative missing-value
marker because it cannot be distinguished from the same exact placeholder
without further source metadata.
