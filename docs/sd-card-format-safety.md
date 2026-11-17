# SD card formatting (experimental; device validation required)

The Storage page provides a separate, destructive SD-card format action via Android's
`sm format`/`sm mount` public-volume commands. This is **not** the BYD CDR cleanup
feature: it erases the entire selected removable card, not just old recordings.

The user must back up the card, move Overdrive recordings/surveillance/trips away
from SD, and stop the factory BYD CDR/dashcam before confirming. The dialog names
the exact `/storage/<UUID>` volume from a JWT-protected preview; the POST requires
a valid JWT, a nonempty same-origin `Origin`, an explicit confirmation flag, and
the matching UUID. The daemon selects a uniquely identified mounted `public:`
volume under StorageManager's mount lock. The browser cannot supply a vold ID.

**CDR/process safety:** vold can terminate processes with files open on the card,
including the factory dashcam mid-recording. The formatter does not kill or
restart the OEM recorder. It checks `/proc/*/fd` for open SD-card file handles
and refuses to proceed if a handle is present, any process cannot be inspected,
or inspection times out. On head units that restrict `/proc` access, formatting
will therefore be unavailable. This scan is a snapshot and **cannot guarantee**
that the OEM recorder will not reopen the card after the check; operators must
stop BYD CDR first. Do not weaken the fail-closed behavior to make it pass.

**Result verification:** a `sm format` exit status of zero is not proof of a
successful format: some Android StorageManagerService implementations log and
swallow vold errors. The formatter attempts to mount the public volume and polls
`sm list-volumes all` for a different, valid UUID on the *same public volume ID*
in `mounted` state. It reports success only after that observation. A timeout,
unknown status, or command failure is reported as *unverified*, since the card
may already have been erased. Inspect it before attempting another format.

## Device acceptance still required

No device run has been performed. Before merging, repeat on **DiLink 3 and
DiLink 5** with a backed-up disposable SD card, with the factory recorder stopped:

- Preview identifies only the inserted SD card; swapping the card before POST
  fails without formatting a replacement.
- No card, SD-selected Overdrive storage, a busy recorder/open file, or restricted
  `/proc` inspection blocks the command safely.
- Reject requests with no JWT or an absent, opaque, foreign, or repeated Origin;
  a legitimate same-origin signed-in request reaches the confirmation flow.
- Refused/failed vold format, unchanged UUID, and mount timeout do not report
  success. A changed UUID followed by confirmed mounted state does.
- Confirm BYD CDR continues operating after *restarting recording intentionally*
  on the formatted card; ensure no recording is interrupted by the formatter.

The implementation is reviewed statically only; vehicle behavior and vendor
permission details remain unverified.
