# Formatting an SD card (experimental; vehicle validation required)

The Storage page offers a destructive format of the **uniquely detected SD** public
volume, using the Android `sm format` and `sm mount` commands. It is separate from
CDR cleanup. Android/vold selects the filesystem (usually exFAT over 32 GiB);
Overdrive never runs `mkfs` or supplies a filesystem choice.

The signed-in preview reads the live UUID, capacity and used bytes for confirmation.
POST carries a confirmation and expected UUID, not a vold volume ID. The daemon
reidentifies that card under StorageManager's mount lock and refuses if Overdrive
recordings, surveillance or trips target SD. Requests require a JWT, including
native requests with **no Origin**. An explicit foreign Origin is rejected with
JSON 403, as with session writes and range calibration; preflight is rejected.

The POST returns **202** and a job ID immediately. The UI polls
`GET /api/storage/external/format/status`; a duplicate POST cannot queue another
job while one is running. The worker stops ExternalStorageCleaner's periodic
scheduler (including active cleanup) before modifying the volume, prevents
manual cleanup during the job, then refreshes/restarts it in `finally`.
It persists the new UUID only after observing a *different* UUID mounted on the
*same* public volume ID. Even on `sm format` timeout/failure, the worker always
attempts `sm mount` and polls, since vold may already have unmounted the card.
The API reports success only if it observes the new mounted UUID; a nonzero `sm`
exit alone cannot prove vold succeeded or failed.

## Shell-UID safety limitation

The daemon runs under shell UID 2000 and cannot read all other processes' files
under `/proc/<pid>/fd`. It therefore **does not pretend to perform an exhaustive
open-file scan**. Instead, `ps -A` (shell-accessible on supported builds) must
work and show **no `com.byd.cdr` nor `com.byd.cdr:*` process** before *and after*
pausing the cleaner. A running factory recorder blocks formatting, including
when it is idle; the user must stop it in the car first. Overdrive already
provides a Native DVR disable control via the OEM Dashcam settings API
(`POST /api/oem-dashcam/native-dvr/disable`); its behavior on each firmware
still requires device confirmation. No process is forcibly stopped by this
format action. A best-effort scan of shell-accessible processes checks
file descriptors, working directories, and memory maps under `/storage`,
`/mnt/media_rw`, `/mnt/user`, `/mnt/pass_through`, and `/mnt/runtime` aliases.
Inaccessible other-UID files cannot be certified idle: **other apps can still
be using the card**. The CDR process check is a conservative OEM-specific gate,
not an atomic filesystem lock. If factory CDR uses a different process name on a
firmware, it must be added after device discovery before enabling formatting.
Back up the card and stop all recording apps before use.

## Device acceptance — NOT YET PERFORMED

On both DiLink 3 and DiLink 5, with expendable/backed-up cards, record logs for:

- Successful format: preview capacity/usage, request, `sm` outputs, new UUID,
  Android/vold filesystem (check exFAT when above 32 GiB), and subsequent CDR
  recording on the formatted card.
- Rejected format: no card, changed target UUID, Overdrive targeting SD, foreign
  Origin, and *running CDR*. Confirm no `sm format` invocation or false success.
- Failed format: `sm` timeout or vold error, subsequent mount attempt, status
  `failed` unless the same public volume has a changed mounted UUID.
- CDR recording in progress: verify the process gate refuses without interrupting
  recording; stop recording and repeat. Confirm the CDR can record afterwards.
- Native bridge without Origin, browser with same/foreign Origin, and repeated
  POSTs while a job is running; capture the responses and confirm no 10s timeout.

**Status: static review only. No car was formatted and no device evidence is claimed.**
