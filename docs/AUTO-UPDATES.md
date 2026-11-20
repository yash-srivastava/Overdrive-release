# Automatic updates (opt-in)

OverDrive **never enables unattended installs by default**. Manual installs and
Alpha catalog selection remain unchanged. The Settings → About switch and web
update dialog both persist `updates.autoInstall=false` by default; the channel
setter uses the existing merging `updateSection` implementation.

Only the running **Braveheart** channel is eligible. Both the installed BuildConfig
label and the remote release label must be parseable Braveheart versions, and the
remote must be **strictly newer**. The staged APK must also have the same
package name and signing certificates as the installed APK, and a strictly
higher `versionCode`. A same-label re-upload, an APK with an older/equal
`versionCode`, a differently signed build, an unknown version or a cross-channel
install is never automatic. **A locally patched build with our signing key and
an older version label/code can be replaced**; this switch is not a custom-build
protection feature.

The camera daemon checks release metadata on startup, including in
**Vehicle-ON-only** mode, then at most once every 6 hours after a no-update
response. While an update is available but temporarily unsafe to install,
checks repeat after 30 minutes; a five-minute in-process poll ensures the
confirmed-ACC-OFF gate can be re-evaluated. An ON-only daemon can check for
releases but **cannot automatically install**: the daemon shuts down at ACC OFF
before the 30-minute safety window can elapse. Disable Vehicle-ON-only mode
(and leave the daemon running while parked) to use automatic installs.
This is not a guaranteed wall-clock schedule. Metadata failures use a persistent
3/6/12/24-hour exponential backoff. A verified eligible asset's GitHub SHA-256 digest is atomically reserved
*immediately before* the daemon-stopping handoff, not on a transport failure. The reservation survives daemon death and even a failed
`pm install`. The same digest is never attempted again automatically, regardless
of restart count; only a different asset digest permits another attempt. Manual
updates are always available to recover a failed release. An unreadable state
or a failed durable reservation prevents unattended installation.

**Admission checks** (fail closed) require an explicit opt-in; Braveheart;
non-public mode; unmetered default network (`ConnectivityManager.isActiveNetworkMetered()`;
no UID-2000-incompatible `getNetworkCapabilities()` or `getActiveNetwork()` gate;
no network is conservatively metered); Sentry configured and actually off; all camera recorders idle;
ACC admitted OFF for at least 30 minutes on the monotonic clock. On DiLink 5,
read a **fresh, positively confirmed OFF** observation from the ACC transition
probe rather than trusting the parked/P cached state. Checks happen before
network access, during streaming download, after download, after DiLink 5 camera
preparation, and again before committing metadata and reserving the attempt.

For automatic updates only, the downloaded APK's SHA-256 must match GitHub's
`sha256:` digest, and the size must still be at least 1 MB. The staged APK's
identity, signing certificates, and `versionCode` are checked **before camera
preparation or metadata changes**. On Android 10 the archive requires
`GET_SIGNATURES` in addition to `GET_SIGNING_CERTIFICATES` and may return only
legacy `signatures`. Incompatible APKs are rejected without restarting daemons;
failed downloads and preflight refusals receive persistent backoff, not a
digest reservation. The automatic `pm install -r` does **not** allow downgrades (`-d`).
Unattended update handoff is not atomic with an ACC transition or a network
metering change; do not treat this as a guarantee while the driver is nearby.

## Car acceptance evidence required before merging

Actual DiLink 3 and DiLink 5 vehicle testing is **not included** in this patch.
On **each** generation, with a verified signed newer Braveheart asset and Sentry
and recording OFF: turn ACC OFF; prove no install at 29 minutes, allow after 30;
exercise ACC ON, recording, Sentry ON, metered/unknown network, tampered APK,
old/equal/unknown versions; verify every refusal leaves the daemons running.
After a successful install, capture fresh `CameraDaemon`, `AccSentryDaemon`,
watchdog, HTTP server, recording and Telegram (if enabled) readiness after the
relaunch and an ACC cycle. Reproduce persistent `pm install` failure (e.g.
signature mismatch) and confirm the next daemon startups do **not** retry or
kill camera processes; publish changed asset bytes to confirm one new attempt.
On DiLink 5, verify a stale OFF while physically sitting in P refuses install.
Record timestamps, exported **camera daemon logs** (the scheduler writes via
`DaemonLogger`), packages, active network metering state and actual vehicle
build versions, not merely a simulated result. On both DiLink 3 and DiLink 5,
confirm the daemon (UID 2000) can query `ConnectivityManager.isActiveNetworkMetered()`
without the `getNetworkCapabilities()` attribution exception, on unmetered Wi-Fi
and on metered cellular, and capture a metered change during download. Run at
least one successful signed install and one forced signature/pm failure on each
vehicle; include evidence that the camera, ACC sentry, HTTP server, watchdog,
and any enabled Telegram daemon recover. No such vehicle proof is available
in the source ZIP or supplied diagnostic snapshot; it is a merge prerequisite.
