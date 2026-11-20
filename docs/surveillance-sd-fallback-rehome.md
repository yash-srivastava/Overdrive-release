# Surveillance SD fallback recovery

When Surveillance is configured for an SD card but an ACC transition temporarily
removes that volume, finalized `event_*.mp4` segments may be written to internal
storage. After the encoder closes and metadata/timeline writes drain, a background
worker tries to publish each segment and its sidecars back to SD. It never moves
an open recording. The copy is throttled and synced; each SD output is renamed
into place before its original modification time is restored.

This is intentionally **disabled on DiLink 100 / BYD100F**. Its removable media
uses MediaStore rather than directly writable file paths; MediaStore does not
provide the rename and modification-time guarantees this implementation needs.

## Persistence and cleanup

- Internal `.surv-retained-event_*.mp4.marker` files record `pending` or
  `published`, the source byte count, and its exact internal modification time.
  A marker is written before a publish; `published` is recorded only after the
  SD copy has been read back successfully. Pending work survives daemon restarts
  within a two-hour deadline. A missing marker must **never** be inferred from
  the existence of an SD file with a similar name.
- Matching SD copies require the same byte length and modification times within
  **2,000 milliseconds**. FAT32's two-second timestamp granularity and exFAT's
  ten-millisecond granularity make equality unreliable. A failed verification
  leaves the internal recording intact.
- After successful publish, the SD copy is indexed and the internal row is
  hidden. The internal clip and its companions remain available to in-flight
  uploads and photo generation for **30 minutes** from publication. The marker
  preserves this deadline over an ACC/daemon restart.
- If a user or SD retention removes the published copy during that window,
  the internal original is deleted only after the SD volume is confirmed
  available and writable. A missing file on an unmounted card is **not** proof
  of deletion. Failed/dropped attempts clear their pending marker rather than
  silently hiding otherwise valid internal footage.
- The physical-space emergency reaper only defers an internal twin during
  its 30-minute grace. It otherwise preserves the global oldest-first order:
  SafeKeep emergency locks are reaped last, while ordinary pins cannot prevent
  the recorder from freeing a full volume.

## Manual verification on an appropriate test vehicle/card

1. Configure Surveillance for SD, interrupt the card during an ACC-off event,
   and confirm a finalized internal event remains readable without blocking
   recording or notifications.
2. Restore SD, verify the segment/sidecars appear once in Events, and inspect
   their bytes and read-back timestamps on FAT32 and exFAT cards.
3. Restart the daemon with a pending marker, then with a published marker;
   verify recovery resumes without scanning every internal MP4 or duplicating
   copies, and that the internal original expires after the grace period.
4. Delete the published SD MP4 within the grace period. After confirming the
   mount is writable, verify the internal original and marker are removed too.
   Repeat with SD *unmounted* and verify no internal data is deleted.
5. Repeat with SafeKeep-locked and pinned events, a full physical volume, and
   on BYD100F. The rehome worker must not touch DiLink 100 card files.

This documentation describes intended behavior, not results of device testing.
