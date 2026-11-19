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
*same* public volume ID, with an unchanged physical-media identity (`cid` for MMC
or `diskseq` where exposed). When neither strong media identifier is exposed,
formatting is **refused** rather than reporting a swapped SD card as formatted.
The volume ID parser is shared with StorageManager and accepts AOSP
`public:M,N` / `public:M:N` as well as Shark 6's bare `M:N` IDs.
Even on `sm format` timeout/failure, the worker always attempts `sm mount` and
polls, since vold may already have unmounted the card.
The API reports success only if it observes the new mounted UUID *and* unchanged
media identity; a nonzero `sm` exit alone cannot prove vold succeeded or failed.
The native command wait is now 15 minutes and the mount poll up to 5 minutes;
the browser has no arbitrary elapsed-time cutoff. The UI retries failed and
non-JSON status reads and continues checking the same job; a missing or replaced
job ID is **unverified**, not a success. The UI exposes localized refusal/error
codes in English and Hebrew, while retaining diagnostic messages in API replies.

## Shell-UID safety limitation

The daemon runs under shell UID 2000 and cannot read all other processes' files
under `/proc/<pid>/fd`. It therefore **does not pretend to perform an exhaustive
open-file scan**. Instead, `ps -A` (shell-accessible on supported builds) must
work and show **no `com.byd.cdr` nor `com.byd.cdr:*` process** before *and after*
pausing the cleaner. A `ps -A` listing that contains no processes belonging to
non-shell UIDs is treated as untrustworthy and blocked (the old check failed
open on shell-only process lists). A running factory recorder blocks a normal
format, including when it is idle. The form also offers a separate, unchecked consent to **temporarily** disable
`com.byd.cdr` with the same `pm disable-user --user 0` action used by the OEM
Dashcam native-DVR toggle (a toggle hidden on DI5 / DiLink 100). The formatter
then verifies `pm list packages -d` *and* an updated process listing. If these
checks fail, formatting does not start. The formatter runs `pm enable` in
`finally`, without persisting the user's native-DVR preference. This does
interrupt active OEM recording, so explicit user consent and backed-up media
are required. Package restoration does not prove that recording resumes; that
needs vehicle verification. A best-effort scan of shell-accessible processes checks
file descriptors, working directories, and memory maps under `/storage`,
`/mnt/media_rw`, `/mnt/user`, `/mnt/pass_through`, and `/mnt/runtime` aliases.
Before any package change or vold format, the daemon writes a durable journal
in the selected shell scratch root. It records the public-volume ID, previous
filesystem UUID, and whether temporary DVR restoration is owed. After a normal
job it attempts remount and package restoration; if recovery cannot be verified
it leaves the journal. A daemon boot checks that journal and independently
retries `sm mount` (never `sm format`), and restores the package, even when
all Overdrive categories are set to internal storage. An interrupted job's
format result remains **unverified** after recovery, and a journal that could
not be cleared blocks a new job. Since the journal lives in shell storage,
external deletion of that journal or daemon never restarting still requires
manual intervention.

Inaccessible other-UID files cannot be certified idle: **other apps can still
be using the card**. The CDR process check is a conservative OEM-specific gate,
not an atomic filesystem lock. If factory CDR uses a different process name on a
firmware, it must be added after device discovery before enabling formatting.
Back up the card and stop all recording apps before use.

## Device acceptance — NOT YET PERFORMED

On both DiLink 3 and DiLink 5, with expendable/backed-up cards, record logs for:

- Successful format: preview capacity/usage, request, stable media CID or diskseq, `sm` outputs, new UUID,
  Android/vold filesystem (check exFAT when above 32 GiB), and subsequent CDR
  recording on the formatted card.
- Rejected format: no card, changed target UUID, Overdrive targeting SD, foreign
  Origin, and *running CDR*. Confirm no `sm format` invocation or false success.
- Failed/unverified format: `sm` timeout or vold error, subsequent mount attempt,
  status `unverified` when no changed mounted UUID on the same physical medium
  could be established; prove it never falsely reports `succeeded`.
- CDR recording in progress: verify default process gate refuses without interrupting
  recording; then explicitly select temporary disable, confirm that it stops,
  restores the package afterwards, and can record onto the newly formatted card.
  Test DI5 / DiLink 100 independently because their native-DVR toggle is hidden.
- Mid-job daemon kill: confirm the durable journal and boot-time mount recovery
  work even when no Overdrive storage category uses SD; verify no extra format
  and no unearned UUID learn or success report.
- Native bridge without Origin, browser with same/foreign Origin, and repeated
  POSTs while a job is running; capture the responses and confirm no 10s timeout.

**Status: static review only. No car was formatted and no device evidence is claimed.**
