# Automatic updates (opt-in)

OverDrive **never enables unattended installs by default**. Manual installs and
Alpha catalog selection remain unchanged. The Settings → About switch and web
update dialog both persist `updates.autoInstall=false` by default; the channel
setter uses the existing merging `updateSection` implementation.

Only the running **Braveheart** channel is eligible. Both the installed BuildConfig
label and the remote release label must be parseable Braveheart versions, and the
remote must be **strictly newer**. A same-version re-upload, a modified build,
unknown versions, a cross-channel install and downgrades are never automatic.

The camera daemon checks after 5 minutes and then every 6 hours **while running**;
this is not a guaranteed wall-clock schedule. Metadata failures use a persistent
3/6/12/24-hour exponential backoff. Once an asset is selected for automatic
installation, its GitHub SHA-256 digest is atomically reserved on disk *before*
any daemon is stopped. The reservation survives daemon death and even a failed
`pm install`. The same digest is never attempted again automatically, regardless
of restart count; only a different asset digest permits another attempt. Manual
updates are always available to recover a failed release. An unreadable state
or a failed durable reservation prevents unattended installation.

**Admission checks** (fail closed) require an explicit opt-in; Braveheart;
non-public mode; unmetered network (`ConnectivityManager` metering and network
capabilities); Sentry configured and actually off; all camera recorders idle;
ACC admitted OFF for at least 30 minutes on the monotonic clock. On DiLink 5,
read a **fresh, positively confirmed OFF** observation from the ACC transition
probe rather than trusting the parked/P cached state. Checks happen before
network access, during streaming download, after download, after DiLink 5 camera
preparation, and again before committing metadata and reserving the attempt.

For automatic updates only, the downloaded APK's SHA-256 must match GitHub's
`sha256:` digest, and the size must still be at least 1 MB, before any camera
teardown. The automatic `pm install -r` does **not** allow downgrades (`-d`).
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
Record timestamps, logcat/daemon log IDs, packages and actual vehicle build
versions, not merely a simulated result.
