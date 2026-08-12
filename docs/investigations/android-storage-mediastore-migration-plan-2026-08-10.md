# Android Storage and MediaStore Migration Plan

Date: 2026-08-10
Status: Proposed, measurement-gated
Scope: Android 10+ volume discovery, media publication, permissions, and target-SDK preparation
Production behavior changed by this document: None

## Executive decision

Do not replace the existing BYD storage path, shell, property, mount, or permission behavior in one change.

The safe migration is:

1. Establish a measured baseline on real BYD hardware.
2. Put framework and BYD volume discovery behind one internal contract.
3. Run framework discovery in shadow mode and compare its answer with the current production selection.
4. Promote framework discovery only on device/firmware combinations where the comparison gates pass.
5. Prove MediaStore publication with a non-critical probe before changing any recorder output path.
6. Introduce a modern-target validation lane before raising the production target SDK.
7. Remove legacy permissions or fallbacks only after field evidence proves they are unnecessary.

The first implementation milestone should therefore be observability plus a read-only framework discovery backend. It must not change the selected recording root.

## Why this is a separate project

The current implementation is deliberately coupled to privileged BYD behavior:

- `app/build.gradle.kts` compiles with SDK 36 but pins `targetSdk` to 25 to preserve `app_process` and background behavior.
- `app/src/main/AndroidManifest.xml` requests legacy external-storage permissions and `MANAGE_EXTERNAL_STORAGE`, and still opts into legacy external storage.
- `StorageManager` combines Android APIs with vendor properties, `sm`, `/proc`, `/sys`, direct paths, and shell fallbacks.
- The daemon writes and indexes recordings across internal, SD, USB, and historical roots.
- The H2 database is derived state and is intentionally outside the media library.
- App and daemon processes can operate under different identities, so an app-only storage assumption is unsafe.

Android 10+ modernization changes storage ownership, URI/path semantics, permissions, process access, background behavior, and publication. It has a larger failure domain than the completed scan and index optimizations and must not be hidden inside those PRs.

## Preconditions

The migration should start after the following storage/index changes are integrated or equivalently preserved during rebases:

- bounded storage subprocesses;
- reduced periodic and per-save scans;
- coalesced index reconciliation;
- one canonical recording-root registry;
- changed-only metadata reconciliation;
- volume-aware recording identity;
- offline-row preservation after unavailable or incomplete scans;
- exact ID media and mutation routes.

Relevant implementation PRs at the time of writing are #211, #213, #216, #217, and #218. PR #218 is stacked on #217.

## Non-negotiable invariants

Every milestone must preserve these behaviors:

1. Recording start, active encoding, and finalization must not wait for a volume inventory or MediaStore query.
2. A failed, timed-out, partial, or unavailable volume scan must never be interpreted as an empty volume.
3. Detaching a volume must preserve its indexed metadata as unavailable, not delete it.
4. The same physical recording must keep the same recording ID across public and `/mnt/media_rw` path aliases.
5. Equal filenames on different volumes must remain independently playable and deletable.
6. Legacy recordings must remain readable throughout the migration.
7. A partially written clip must never become visible as a complete shared-media item.
8. Retention must delete the exact recording and its sidecars without crossing volume boundaries.
9. The daemon must continue to work when the UI process is absent.
10. Rollback must not require deleting user recordings or the derived H2 index manually.

## Target architecture

### 1. Volume discovery contract

Introduce one internal contract that returns immutable volume snapshots. The contract should describe evidence; it should not perform recording, cleanup, indexing, or publication.

Each snapshot should include at least:

- stable volume identity;
- framework volume name and UUID when available;
- selected root and normalized root aliases;
- primary, removable, and emulated flags;
- mounted/readable/writable state;
- total and available bytes when safely measurable;
- supported capabilities, such as direct-path write and MediaStore publication;
- discovery backend and evidence used;
- monotonic observation time;
- completeness/confidence state rather than a simple present/absent boolean.

The current canonical recording-root registry remains the source for historical recording locations. Volume discovery answers which physical volumes are currently usable; it does not erase legacy roots from lookup or reconciliation.

### 2. Framework backend

The framework-first backend should use public APIs where the platform exposes the required data:

- API 24+: `android.os.storage.StorageManager.getStorageVolumes()`;
- `StorageVolume.getUuid()`, `isPrimary()`, `isRemovable()`, and `isEmulated()`;
- volume state and `Environment.getExternalStorageState(file)` where a file path is available;
- API 29+: `MediaStore.getExternalVolumeNames()` for MediaStore collection names, without assuming that a name maps to a specific physical `StorageVolume`;
- API 30+: `StorageVolume.getDirectory()` and `getMediaStoreVolumeName()`;
- API 30+: `StorageManager.registerStorageVolumeCallback()` for invalidation.

The backend must tolerate null UUIDs, inaccessible directories, missing media-store names, delayed callbacks, and firmware-specific API results. It must return an explicit unsupported or incomplete result instead of manufacturing an empty volume set.

On API 29, public framework discovery is limited to the `StorageVolume` list, identity/state signals, and the separate set of MediaStore volume names. Public per-volume directory lookup and direct `StorageVolume` to MediaStore-name mapping are API 30+ capabilities. API 29 publication must therefore remain probe-gated and may retain the legacy direct-path publisher on supported BYD firmware.

### 3. BYD compatibility backend

Keep the existing vendor properties, `sm`, `/proc`, `/sys`, and mount/remount behavior as a compatibility backend.

Its responsibilities should narrow over time to:

- recover information absent or incorrect in framework discovery;
- perform privileged mount/remount operations that have no public equivalent;
- support known BYD firmware where framework state is stale or incomplete;
- provide diagnostic evidence for every fallback decision.

All child processes must remain bounded, serialized where necessary, interruptible, and excluded from recording hot paths.

### 4. Backend selector

The selector should be deterministic and observable:

1. Read a framework snapshot.
2. Read compatibility evidence only when shadow comparison is enabled or framework evidence is incomplete.
3. Compare stable identity, root, state, writability, and capacity.
4. Select the configured primary backend only if its result is complete.
5. Fall back without converting uncertainty into absence.
6. Emit a reason code and discrepancy counters.

Selection must be capability-specific. Framework discovery may be authoritative while a BYD-only remount action remains necessary.

### 5. Media publication contract

Separate file creation from publication. A publication request should carry recording identity, logical category, MIME type, display name, target volume, timestamps, and the finalized source/output handle.

Provide two implementations during migration:

- `LegacyDirectPublisher`: current direct-path finalization and compatibility publication behavior;
- `MediaStorePublisher`: framework publication on a specific MediaStore volume.

The H2 index remains the app's query-optimized derived metadata store. MediaStore is not a replacement for event sidecars, storage policy, offline-row state, or app-specific metadata.

## MediaStore publication model

For user-visible shared video, the modern path should:

1. Select the MediaStore volume associated with the chosen physical volume.
2. Insert a row with `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH`, and `IS_PENDING=1`.
3. Write through the `ContentResolver` file descriptor or another proven descriptor-compatible recorder path.
4. Flush, close, and validate the completed media.
5. Publish atomically by setting `IS_PENDING=0`.
6. Store the resulting content URI alongside the recording identity when useful, without making URI text the recording primary key.
7. On failure, remove the pending row and retain enough direct-path information for deterministic recovery.

Do not assume the current recorder can write directly to a `ParcelFileDescriptor`. First prove descriptor compatibility for every active encoder/muxer implementation. If any implementation requires a filesystem path, evaluate these options in order:

1. add descriptor support without copying;
2. retain direct recording and perform publication after finalization outside the hot path;
3. keep the legacy publisher for that category/device if copying would violate latency, wear, or free-space budgets.

An after-the-fact full MP4 copy is not an acceptable default for continuous recording without measured I/O and storage-headroom evidence.

## Data ownership policy

Classify data before selecting an API:

| Data | Intended owner | Preferred long-term location/API |
| --- | --- | --- |
| User-visible completed video | Shared media | MediaStore video collection on the selected volume |
| Pending/in-progress video | Recorder only | Pending MediaStore item or private staging location |
| Event JSON and internal metadata | Overdrive | App/daemon-owned storage, referenced by recording ID |
| H2 derived index | Overdrive daemon | Explicitly owned internal/daemon directory when SELinux and cross-process access permit |
| Thumbnail shared with other apps | Shared media only when required | MediaStore or generated through the daemon API |
| Internal thumbnail/cache | Overdrive | Cache directory keyed by recording ID |
| Diagnostics/configuration | Overdrive | Internal protected storage |

If app and daemon identities cannot both access a modern private location, preserve the current location until a permission-protected provider or daemon API has been validated. Do not replace a working cross-UID contract with world-readable file modes.

## Delivery phases and gates

### Phase 0 - Baseline instrumentation

Add trace sections and counters before selecting a new backend.

Required trace sections:

- `storage.volumeDiscovery.framework`
- `storage.volumeDiscovery.byd`
- `storage.volumeSelection`
- `storage.mediaStore.insert`
- `storage.mediaStore.write`
- `storage.mediaStore.publish`
- existing recording finalization, inventory, reconcile, and cleanup sections

Required counters/histograms:

- discovery duration by backend;
- framework/BYD volume count and identity mismatch;
- root, state, writability, capacity, and media-store-name mismatch;
- callback-to-observed-state latency;
- selected backend and reason code;
- fallback and timeout count;
- recording finalize duration and failure stage;
- pending-row cleanup count;
- bytes copied after finalization, which should normally be zero;
- dropped/late encoder frames during storage operations;
- reconcile duration and rows added, refreshed, preserved offline, and removed.

Logs must avoid raw user filenames where an opaque recording or volume ID is sufficient.

**Gate 0:** baseline traces exist for every device scenario in the measurement matrix, and the capture itself causes no recording regression.

### Phase 1 - Extract contracts without changing behavior

- Add immutable volume snapshot and backend interfaces.
- Adapt the current BYD implementation behind the compatibility backend.
- Keep existing selection and recording paths authoritative.
- Add fake backends for deterministic unit tests.

**Gate 1:** production root selection is byte-for-byte or semantically identical in the device matrix, with no new subprocesses or full scans in steady state.

### Phase 2 - Framework discovery in shadow mode

- Implement the framework backend.
- Collect both snapshots on startup, callback, remount, and storage-type change.
- Compare results asynchronously.
- Never let the shadow result select a path, remount a volume, delete a row, or trigger retention.
- Coalesce callback bursts through the existing reconcile coordinator.

**Gate 2:** on every supported BYD firmware/storage combination:

- all production-selected writable volumes have a matching framework identity or a documented exception;
- no detached volume is reported writable;
- identity remains stable across reboot, detach/remount, and public/media_rw aliases;
- framework callback or bounded refresh observes state changes within 5 seconds at p95;
- no recording finalization, dropped-frame, CPU, or I/O regression exceeds the agreed baseline budget;
- every mismatch has enough evidence to diagnose without reproducing it interactively.

Any unexplained false-positive writable state blocks promotion.

### Phase 3 - Framework-first discovery with fallback

- Enable framework selection only for device/firmware combinations that passed Gate 2.
- Retain an immediate compatibility fallback for incomplete framework evidence.
- Keep BYD mount/remount operations available as an independent capability.
- Roll out by explicit local/build flag before making it a default.

**Gate 3:** at least one release candidate cycle completes with zero lost recordings, zero cross-volume deletions, zero false-empty reconciles, and no sustained fallback-rate increase.

### Phase 4 - MediaStore capability probe

Build an instrumentation-only or diagnostics-only probe. It should create a small valid media item on each candidate volume, write through a descriptor, publish it, query it, load a thumbnail where supported, delete it, and verify that no pending row remains.

The probe must also test:

- detach during pending write;
- read-only and nearly full media;
- duplicate display names;
- reboot with a pending item;
- daemon/UI cross-process access;
- delete from Overdrive and externally through MediaProvider;
- relationship between framework volume identity and MediaStore volume name.

**Gate 4:** all probe lifecycle operations are deterministic on supported hardware, leave no leaked rows, and do not require `_data` mutation or shell `content insert`.

### Phase 5 - Non-critical publication pilot

Migrate one explicitly selected, non-continuous recording workflow first. Do not begin with the continuous dashcam ring or emergency recording path.

- Keep stable recording IDs independent of content URIs.
- Keep legacy direct-path lookup for pre-migration recordings.
- Exercise playback, thumbnail, download, timeline, retention, and delete by exact ID.
- Measure publication and any copy cost under concurrent recording load.

**Gate 5:** the pilot passes the complete functional matrix and its p95 finalization latency stays within 10 percent or 50 ms of baseline, whichever budget is larger. It creates no second full-size copy during normal operation.

### Phase 6 - Recorder-category rollout

Move categories one at a time. Recommended order:

1. explicit/manual completed clips;
2. sentry or proximity clips after their lifecycle is proven;
3. continuous dashcam recordings last.

Each category requires an independent rollout flag and rollback path. Promotion requires soak testing on internal, SD, and USB storage, including full and failing media.

### Phase 7 - Modern target-SDK lane

Create a separate build/test lane targeting at least API 29, then API 30+, before changing the production BYD target.

The lane must inventory behavior changes beyond storage, including:

- background execution and service starts;
- foreground-service types and permissions;
- package visibility;
- notification runtime permission;
- exact alarms;
- Bluetooth and location permissions;
- exported components and pending-intent mutability;
- all-files access policy and user grant flow;
- `app_process` startup, daemon survival, and cross-UID access.

Run the modern lane in CI and on hardware while the production flavor remains pinned. A target-SDK increase is a product release decision, not a storage refactor side effect.

**Gate 7:** all daemon startup/survival, vehicle integration, storage, and permission scenarios pass on every supported firmware, with a documented Play/distribution policy if applicable.

### Phase 8 - Permission and fallback retirement

Remove permissions and compatibility paths individually, with telemetry proving no supported device uses them.

The current `MEDIA_SCANNER_SCAN_FILE` broadcast and shell `content insert`/`_data` path are also BYD Android 10 cross-UID FUSE visibility workarounds. Do not retire them merely because MediaStore publication succeeds in one process. Hardware evidence must first prove equivalent daemon/UI discovery, exact-ID playback and deletion, and legacy direct-path fallback behavior on both SD and USB mounts.

Candidate order:

1. stop shell-based media publication only after the cross-UID visibility gate passes;
2. remove `_data` mutation only with its coupled BYD visibility fallback;
3. narrow raw shared-storage access by category;
4. remove `WRITE_EXTERNAL_STORAGE` where ineffective/unneeded;
5. remove `READ_EXTERNAL_STORAGE` only after API-specific media permissions and legacy access are covered;
6. remove `MANAGE_EXTERNAL_STORAGE` only after all daemon and legacy-library workflows pass without it;
7. retire individual BYD discovery fallbacks only after a full supported-firmware release cycle reports zero use.

No permission should be removed merely because it is deprecated; remove it when runtime evidence and tests prove the corresponding capability has migrated.

## Measurement matrix

Run on real BYD tablets, not only an emulator.

### Device dimensions

- every supported BYD model and firmware family;
- production app/daemon identity and signing configuration;
- cold boot, warm boot, and daemon-only startup;
- internal storage, at least two SD card models/speeds, and supported USB media;
- empty, 100, 1,000, 5,000, and 20,000-recording libraries;
- healthy, read-only, nearly full, slow, corrupt, detached, and remounted media.

### Required scenarios

1. Ten minutes idle with a 5,000-file library.
2. Continuous recording during periodic cleanup and index reconciliation.
3. Finalization while dashboard and recording library are active.
4. Rapid thumbnail scrolling with legacy clips.
5. SD and USB detach/remount while idle and while recording.
6. Storage-type switch with equal filenames on two volumes.
7. Reboot with mounted, absent, and read-only selected storage.
8. MediaStore probe during low-space and detach faults.
9. Retention delete of both legacy-path and MediaStore-published rows.
10. Rollback from every enabled phase without clearing recordings or H2 manually.

### Perfetto capture

Capture scheduling, Binder, process/thread activity, disk/block I/O, filesystem/FUSE events where available, memory pressure, and app trace sections. Keep the same capture configuration and workload for baseline and candidate builds.

At minimum compare:

- recording finalization p50/p95/p99;
- encoder late/dropped frames;
- discovery and callback latency;
- bytes read/written and I/O queue pressure;
- CPU time and runnable latency on recorder, storage, index, and MediaProvider threads;
- reconcile and retention duration;
- binder calls and MediaProvider time;
- full-size copy bytes;
- battery/thermal state for long runs.

## Test strategy

### JVM tests

- backend selection from complete, incomplete, contradictory, and timed-out snapshots;
- stable framework/BYD identity normalization;
- no deletion decision from unknown or partial state;
- callback burst coalescing;
- exact recording-ID mapping independent of URI/path changes;
- pending publication state-machine recovery;
- deterministic fallback reason codes;
- feature-flag rollback.

### Instrumentation tests

- enumerate framework volumes and validate states;
- MediaStore insert/write/publish/query/delete on each available volume;
- no visible item before `IS_PENDING=0`;
- cleanup after write/finalize failure;
- content-URI playback and thumbnail loading;
- permission behavior at API 29, 30, 33, and the current compile SDK;
- process restart and reboot recovery.

### Hardware fault tests

- detach during write, finalization, publication, query, and delete;
- filesystem becomes read-only;
- ENOSPC before and during finalization;
- delayed/hung compatibility command;
- MediaProvider unavailable or slow;
- framework callback omitted or duplicated;
- UI process killed while daemon continues recording;
- daemon restarted while pending publication exists.

## Rollout and rollback

Use independently controlled flags for:

- framework shadow discovery;
- framework-primary volume selection;
- MediaStore capability probe;
- MediaStore publication by recording category;
- modern target-SDK test lane.

Defaults must remain conservative. Disabling a flag must restore the prior backend on restart without moving or deleting existing media.

Automatic rollback triggers should include:

- any cross-volume identity mismatch;
- a false writable or false mounted result;
- pending-item leak above zero after recovery;
- unexplained recording-finalization failure increase;
- dropped-frame regression beyond budget;
- fallback or timeout rate above the approved threshold;
- any index deletion caused by an incomplete snapshot.

MediaStore-published and legacy recordings must coexist indefinitely. Rollback cannot depend on converting content URIs back into raw paths.

## Proposed PR sequence

Keep tests in the same commit as each behavior and use detailed English commit bodies.

1. **Observability:** add trace sections, counters, and a repeatable Perfetto/device runbook; no backend change.
2. **Contract extraction:** introduce immutable snapshots and compatibility backend adapters; preserve current selection.
3. **Framework shadow:** add public-API discovery, callbacks, comparison, and discrepancy diagnostics; no authority.
4. **Framework promotion:** enable framework-first selection behind a flag for approved device profiles.
5. **MediaStore probe:** add diagnostics/instrumentation capability tests; no recorder integration.
6. **Publication pilot:** migrate one non-critical completed-clip workflow behind a separate flag.
7. **Category rollout:** migrate one recorder category per PR, continuous dashcam last.
8. **Modern target lane:** add CI and hardware validation without changing the production target.
9. **Permission cleanup:** remove one proven-unused permission/fallback per PR.
10. **Private-state ownership:** move H2/configuration only after daemon UID and SELinux access are proven.

Do not combine target-SDK changes, permission removal, volume authority changes, and recorder-output changes in one PR.

## Open questions that require device evidence

1. Does every supported BYD firmware expose the selected SD/USB volume through `getStorageVolumes()` with stable UUID and state?
2. Do framework callbacks fire reliably for vendor remounts, or is a bounded refresh still required?
3. Can every recorder/muxer write to a file descriptor without path-based reopen or rename?
4. Which process identity owns MediaStore rows when publication is initiated by the daemon path?
5. Can both daemon and UI reopen the resulting URI after process death and reboot?
6. Does MediaProvider publication introduce measurable encoder contention on slower SD cards?
7. Are event sidecars private, shared, or required by external consumers?
8. Which legacy roots must remain discoverable after permission changes?
9. Can the H2 database move to a protected internal directory without breaking daemon startup or recovery?
10. Which non-storage target-SDK behavior first blocks the modern lane on each firmware?

These questions are release gates, not implementation assumptions.

## Definition of done

The modernization project is complete only when:

- framework discovery is authoritative on all supported profiles or every retained BYD fallback has a documented, observed reason;
- no supported workflow uses shell-based MediaStore insertion or `_data` mutation;
- completed shared recordings use a proven publication path with no visible partial files;
- volume detach/remount preserves identity and offline index rows;
- production recording performance meets the baseline budgets;
- modern-target tests cover storage and non-storage behavior changes;
- each removed permission has device evidence and a rollback record;
- legacy and new recordings coexist without filename ambiguity;
- the runbook, metrics, and device matrix are reproducible by another developer.

## Official references

- Shared media and MediaStore: <https://developer.android.com/training/data-storage/shared/media>
- Android 11 storage changes: <https://developer.android.com/about/versions/11/privacy/storage>
- App-specific external storage: <https://developer.android.com/training/data-storage/app-specific>
- `StorageManager`: <https://developer.android.com/reference/android/os/storage/StorageManager>
- `StorageVolume`: <https://developer.android.com/reference/android/os/storage/StorageVolume>
- AOSP scoped-storage and FUSE performance: <https://source.android.com/docs/core/storage/scoped>
- Perfetto/system tracing: <https://developer.android.com/topic/performance/tracing>