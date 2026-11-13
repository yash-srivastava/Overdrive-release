# Module Faults — first-stage diagnostics

## Scope

This change adds a **read-only, partial vehicle-warning overview** to the native Android Diagnostics screen and the WebUI. The first version reports only vehicle indicators that OverDrive already collects and understands:

- **Tyre pressure monitoring (TPMS):** per-wheel pressure, BYD pressure-state, air-leak and sensor-signal indicators. The existing user-configured front/rear pressure thresholds are used when a pressure value is present.
- **12V auxiliary battery:** the BYD bodywork/battery LOW or NORMAL level, plus the measured voltage when a valid 9–16 V value is already available. The existing OverDrive thresholds classify below 11.5 V as a warning and below 10.5 V as critical.
- **Charging system:** the current charging-state classification and its known breakdown/timeout state.

The API is `GET /api/diagnostics/module-faults`. It reads a stable in-memory `BydVehicleData` / `ChargingStateData` snapshot and returns explicit `warning`, `clear`, `partial`, `unavailable`, or `not_scanned` states. It does not initiate a new vehicle communication session.

## Important limitations

This is **not yet an ECU diagnostic trouble-code (DTC) scanner**. In particular:

- It does not read stored DTC memory or display manufacturer DTC identifiers.
- Brakes/ABS, steering, airbags/SRS, ADAS, traction-battery/BMS and body-control ECUs are explicitly shown as **Not scanned**. They are never presented as healthy merely because no data is available.
- A `clear` status means only that the specific supported telemetry source currently reports no warning. It does not certify the corresponding ECU as fault-free.
- Full multi-module DTC coverage still requires a verified BYD diagnostic API contract, including module discovery, request/response formats, model/firmware compatibility and safe read semantics.

The manifest and permission granter already mention BYD DTC permissions, but the checked public compile-time SDK stubs do not expose a verified DTC reader class/method or response schema. A permission declaration alone is not evidence that a usable API is available. This implementation intentionally does not guess a class name, CAN identifier, UDS request, checksum or response layout.

## Safety properties

- The endpoint accepts **GET only**.
- No CAN or UDS frames are sent by this feature.
- No ECU state is written, reset, coded or cleared.
- No `BYDAUTO_DTC_SET` permission is used by the feature.
- Missing or partial readings remain visibly unavailable/partial; unknown values are not converted to healthy states.
- The UI calls the result a partial telemetry overview and directs users to a BYD-capable workshop tool for complete safety-system diagnostics.

## UI locations

- Android app: **Diagnostics → Module faults**.
- WebUI: **Diagnostics → Module Faults**.

Both surfaces use the same WebUI page and API response. The page refresh button simply re-reads the current telemetry snapshot; it is not an ECU scan trigger. The JSON report can be copied to the clipboard and attached to a support request.

## Manual verification

1. Install on a BYD head unit where OverDrive vehicle telemetry is already working.
2. Open Diagnostics → Module faults (or the WebUI sidebar entry).
3. Confirm the page identifies its limited coverage and explicitly says that DTC retrieval is not available.
4. Confirm TPMS wheel rows show available pressure/state values, or show unavailable/partial when the vehicle has not supplied them.
5. Confirm the 12V and charging modules display their known telemetry state, or remain unavailable when no source value exists.
6. Confirm brakes, steering, airbags, ADAS, traction battery and body control remain **Not scanned**.
7. Use Refresh readings and Copy report. Verify the clipboard JSON contains the same read-only snapshot.
8. Confirm no vehicle-control behavior changes and no fault-clearing action is offered.

Builds and automated tests were intentionally not run for this contribution preparation. Hardware verification remains required before treating any status as workshop-grade diagnostics.
