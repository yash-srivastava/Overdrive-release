# Avoid redundant telemetry poller restarts

OverDrive's legacy BYD telemetry collector is shared by the panorama recorder,
OEM dashcam and other consumers. It polls vehicle hardware at 2 Hz when the
recording overlay is active and at 1 Hz otherwise. These intervals are not
changed by this optimization.

Previously, `stopPolling()` restarted the entire scheduled executor whenever
one consumer released a reference but another consumer remained and
`overlayRecordingActive` was already false. Releasing a reference does not
change the interval, so the restart stopped and recreated the worker thread
without providing any new telemetry behavior. During recorder/overlay
transitions this could cause unnecessary scheduling, thread creation and a
new immediate telemetry hardware poll.

The collector now preserves its current executor if other consumers remain.
`setOverlayRecordingActive()` remains responsible for changing between 2 Hz
and 1 Hz, and the last `stopPolling()` still shuts down the executor. This
also preserves the generation guard used to reject callbacks from retired
pollers. No camera frame processing, encoding, recording, vehicle-control,
or sampling intervals are changed.

## Manual verification on a parked DiLink 3 head unit

1. Start a recording with telemetry overlay enabled. Confirm speed/gear
   telemetry and the 2 Hz refresh behavior continue to work.
2. Disable the overlay while a separate telemetry consumer remains active.
   Confirm the 1 Hz poller is retained when that consumer releases its own
   reference; no extra `TelemetryPoller` restart should occur solely for
   the reference release.
3. Start/stop multiple consumers in different orders. Confirm polling only
   stops when the final consumer releases its reference.
4. Re-enable the overlay and verify that `setOverlayRecordingActive(true)`
   still switches back to 2 Hz. Verify ACC, camera and recording state changes.
5. Compare telemetry-poller thread creation, daemon CPU spikes and
   recording frame times during repeated overlay transitions against a
   baseline. A system-wide responsiveness improvement is not established.

No builds, automated tests, benchmarks or device tests were run for this PR.
