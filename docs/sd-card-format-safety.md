# Formatting an SD card (experimental; vehicle validation required)

The Storage page offers a destructive format of the **uniquely detected SD** public
volume, using the Android `sm format` and `sm mount` commands. It is separate from
CDR cleanup. Android/vold selects the filesystem (usually exFAT over 32 GiB);
Overdrive never runs `mkfs` or supplies a filesystem choice.

The signed-in preview reads the live UUID, capacity and used bytes for confirmation.
POST carries a confirmation and expected UUID, not a vold volume ID. The daemon
reidentifies that card under StorageManager's short mount lock and refuses if Overdrive
recordings, surveillance or trips target SD. Requests require a JWT, including
native requests with **no Origin**. An explicit foreign Origin is rejected with
JSON 403, as with session writes and range calibration; preflight is rejected.

The POST returns **202** and a job ID immediately. The UI polls
`GET /api/storage/external/format/status`; a duplicate POST cannot queue another
job while one is running. The worker stops ExternalStorageCleaner's periodic
scheduler (including active cleanup) before modifying the volume, prevents
manual cleanup during the job, then refreshes/restarts it in `finally`.
It persists the new UUID only after observing a *different* UUID mounted on the
*same* public volume ID, with an unchanged physical-media identity (a 128-bit MMC `device/cid`
reported for an SD-type device). The Linux `diskseq` is **not** sufficient:
a USB stick may have one even when Shark 6 incorrectly labels it SD. When
an MMC SD CID is not exposed,
formatting is **refused** rather than reporting a swapped SD card as formatted.
The volume ID parser is shared with StorageManager and retains its existing
discovery behavior for AOSP `public:M,N` and Shark 6's bare `M:N` IDs.
Even on `sm format` timeout/failure, the worker always attempts `sm mount` and
polls, since vold may already have unmounted the card.
While `sm format` and remount polling run, the shared mount lock is released.
The formatter reserves the SD operation so normal SD remounts and attempts to
switch a storage category onto SD refuse; USB mounts and moves to internal/USB
remain available. The API reports success only if it observes the new mounted UUID *and* unchanged
media identity; a nonzero `sm` exit alone cannot prove vold succeeded or failed.
The native command wait is now 15 minutes and the mount poll up to 5 minutes;
the browser has no arbitrary elapsed-time cutoff. The UI retries HTTP errors (including JSON 401/500) and
non-JSON status reads and continues checking the same job; a missing or replaced
job ID is **unverified**, not a success. The UI exposes localized refusal/error
codes in English and Hebrew, while retaining diagnostic messages in API replies.

## DiLink 100 / BYD100F — explicitly unsupported

On BYD100F the SD and CDR processes belong to the driver Android user, while
this daemon runs as shell under user 0. A raw `StatFs(/storage/<uuid>)` fails,
`ps -A` and an open-file scan cannot reliably inspect the driver user, and
`pm disable-user --user 0` does not stop/restore that user's CDR. Thus **both
preview and POST refuse** with `dilink100_unsupported`; no `sm` format/partial
recovery work, user-0 CDR toggling, or `StatFs` probe is attempted. This uses
`DiLink100Platform.isByd100f()`, the same platform predicate used by
`RemovableStorageFs.isActive()` in the DiLink 100 MediaStore change (the adapter
is not present in this upstream branch). The format card may be visible based
on storage discovery; refusal is deliberate, not a false "no card" result.

## Real SD media verification (Shark 6)

StorageManager's SD label is a discovery hint, **not** permission to erase.
The formatter additionally requires a kernel MMC `device/cid` of exactly 32
hex digits and `device/type` equal to `SD`; it checks the identity again before
format and after remount. A SCSI/USB device has no such proof and is refused,
even if `sys.byd.isSDExist` or volume classification reports "SD". No
`diskseq`/major-number/USB fallback is permitted. This deliberately refuses
cards on USB bridges where a trustworthy MMC CID cannot be read.

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
job it attempts remount and package restoration (`pm list packages -d` followed by
`pm path com.byd.cdr`, without unsupported `-a`); if recovery cannot be verified
it leaves the journal and reports the job as **unverified**, never a success
while the DVR may still be disabled. A daemon boot checks that journal and independently
restores the DVR package **before** retrying `sm mount` (never `sm format`).
If another boot-time mount pass holds the mount lock for longer than five seconds,
recovery retries acquiring it within the same ten-minute mount recovery budget,
so a transient lock timeout does not strand the card. This happens
even when all Overdrive categories are set to internal storage. An interrupted job's
format result remains **unverified** after recovery, and a journal that could
not be cleared blocks a new job. Since the journal lives in shell storage,
external deletion of that journal or daemon never restarting still requires
manual intervention.

Inaccessible other-UID files cannot be certified idle: **other apps can still
be using the card**. The CDR process check is a conservative OEM-specific gate,
not an atomic filesystem lock. If factory CDR uses a different process name on a
firmware, it must be added after device discovery before enabling formatting.
Back up the card and stop all recording apps before use.

## Behavioural regressions

The pure Java tests in `SdCardFormatterBehaviorTest` exercise volume parsing,
older-firmware DVR restore checks, and refusal mapping. The Node VM harness in
`tools/tests/sd-format-poll/run.cjs` exercises real `storage.js` polling logic
with mock HTTP 401/500, malformed replies, mismatched successful job IDs and
long-running jobs. These are device-independent checks, not vehicle proof.

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
The supplied device diagnostic archive is a snapshot, not a live SD format,
CDR recording, or refusal trace; it cannot substitute for the DiLink 3/5
acceptance evidence requested above.
