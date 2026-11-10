# Dashboard: skip identical vehicle status repaints

The UI dashboard polls the daemon's `/status` endpoint while visible. Previously,
every response called `renderVehicleState()`, even if the parsed vehicle state
was identical to the previous response. Rendering repeatedly assigns multiple
text and visibility properties, formats charging and range labels, and invokes
the SOC gauge's animated-progress API. On a resource-constrained DiLink 3 head
unit this is avoidable main-thread work during steady-state vehicle readings.

After applying the existing `DashboardStateReducer.status()` transition, the
fragment now compares the previous and current vehicle state. Since the state
and its nested snapshots are Kotlin data classes/sealed values, equality is a
value comparison, not a reference-identity check. It repaints the vehicle card
only when the state actually changes. Initial loading and unavailable-to-ready
transitions still repaint, and SOC/range/charging changes still update.
Skeleton completion and the next poll are deliberately not conditional on a
state change. The network polling frequency and daemon behavior are unchanged.

This change is confined to the Android UI process and is not restricted in
code to any head-unit generation. It is motivated by the supplied DiLink 3.0,
Android 10 (API 29), firmware 2606 hardware, but is expected to be safe for
other compatible devices. It does not change camera recording, telemetry
collection, safety functions, or vehicle controls.

## Suggested parked-device verification

1. Open the dashboard with a working daemon and stable battery/charging data.
   Observe that the display stays populated across several `/status` polls.
2. Change a displayed value (for example by connecting an appropriate charger
   while parked). Verify the dashboard updates at the next normal poll.
3. Stop and restart the daemon. Confirm unavailable and recovered states paint.
4. Leave/reopen the dashboard and rotate the screen if supported; verify that
   the first view render works even when a previous state was retained.
5. Compare baseline vs patched main-thread frame time, dropped frames, and
   `renderVehicleState()` invocation counts during stable readings. Also check
   battery/charging transitions for stale values.

No build, automated tests, runtime benchmarks, or device checks were run.
