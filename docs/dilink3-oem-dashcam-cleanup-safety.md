# OEM dashcam cleanup: verified paths and explicit opt-in

## Problem and impact

`StorageManager.setRecordingsStorageType(SD_CARD)` and
`setSurveillanceStorageType(SD_CARD)` previously auto-enabled the
`ExternalStorageCleaner`. The cleaner scanned an SD card for common video
folders, then **guessed** that the folder containing the most videos was an
OEM recorder directory if it found five or more clips. It also accepted a
persisted `externalCleanup.cdrPath` without checking that it belonged to the
current SD card. A busy SD card containing personal videos could therefore
become a deletion target without a deliberate cleanup opt-in.

The scanner also recursed into symlinked directories and, when Java directory
listing failed, could return a partial list from a bounded `ls` subprocess.
Deletion decisions using an incomplete scan cannot reliably honor the
configured minimum-retained-file count.

## Behavior after this change

* Selecting SD storage never enables OEM deletion on its own. The existing
  `/api/storage/external` recommendation and manual enable control remain.
  Previously persisted `cleanupEnabled=true` continues to work **only** when
  a trusted recorder directory is found; this change does not silently reset
  a user's existing setting.
* Discovery accepts dedicated recorder locations only: `Recorder/Normal`,
  `Recorder/Video`, `Recorder`, `DVR`, `CDR`, `行车记录仪`, `Video/DVR`,
  `Movies/DVR`, `CarRecord`, `DashCam`.
  Generic `DCIM`, `Camera`, `Movies`, `Video`, `Record`, and the
  largest-video-folder fallback are deliberately **not deletion targets**.
* The configured recorder path is accepted only when it resolves to a known
  recorder location **under the current SD root**. Root aliases are compared
  by canonical path. A `CDR` symlink to `DCIM` is not trusted.
* Cleanup rediscovers the SD and recorder before acting. Before any deletion,
  it requires a complete Java directory walk, verifies that all traversed
  entries resolve inside the recorder tree, avoids symlink cycles, and checks
  each target again before unlink. If the full tree cannot be enumerated,
  nothing is deleted. The preview also refuses incomplete scans rather than
  claiming a misleading complete list.
* Lightweight status reporting keeps the bounded shell-list fallback, with
  its existing possible undercount, but those counts are never used as the
  authoritative deletion list. This change does not introduce background
  polling, extra permissions, database migrations, or new storage formats.

## Deliberate compatibility tradeoffs

Some older BYD firmwares may store dashcam video in an otherwise generic
`DCIM` or `Movies` directory, or in an unlisted folder. On those head units,
**automatic cleanup will be unavailable** until an OEM-specific recorder
location is established and explicitly supported. We prefer failing closed
over deleting personal media by guessing ownership. The existing config
field is not a bypass for the verified path rule. A future opt-in custom path
UI should provide a separate explicit folder-selection and ownership review.

Android 10 (API 29) supports the canonical-file APIs used here. Mount aliases,
permissions, FUSE behavior, and SD unmount races still require device testing.
A path-level check before unlink is not a substitute for an atomic
openat/unlinkat implementation if untrusted actors can race changes to
parent symlinks. This patch does not claim to solve that stronger threat model.

## How to verify (proposed, not executed)

1. With the vehicle parked and a backed-up SD card, select SD storage for
   recordings. Confirm cleanup remains disabled unless it was already enabled;
   the existing UI should recommend enabling it.
2. With old MP4 files in `DCIM`, `Movies` or an unrelated folder, verify
   `cdrPath` is absent and neither periodic nor forced cleanup deletes them.
   Check preview behavior when no verified recorder path exists.
3. With old MP4 files under `Recorder/Normal` or `CDR`, explicitly enable
   cleanup, preview the intended files, and confirm the protection window
   and minimum-retained-file count still apply.
4. Remove/reinsert the SD, change the stored `cdrPath` to an unrelated path,
   and simulate an unreadable nested directory; cleanup must refuse rather
   than use stale paths or a partial file list.
5. Verify status polling still works if Java listing fails, but preview and
   destructive cleanup decline incomplete scans.
6. Review the new `CdrCleanupSafetyTest` (tests are supplied but **not run**)
   for trusted paths, generic-folder rejection, volume escapes, and symlinks.

## Preparation

Patch prepared from the supplied repository snapshot rather than from the
upstream clone used by the publishing script. No build, tests, benchmarks,
Windows PowerShell execution, or vehicle testing were performed.
