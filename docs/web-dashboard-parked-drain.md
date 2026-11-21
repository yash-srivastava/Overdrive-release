# Web Dashboard: parked battery use

The Web Dashboard now shows **Used 0.5% while parked (≈0.1 kWh)** below
its heading when a recent, measurable parked battery loss is available.
The message is hidden when readings are missing, invalid, or indicate charging,
rather than displaying an estimated zero.

- **While parked:** If Parking Intelligence is running and an open session has
  reliable live energy data, the Dashboard reads `/api/parking/current`.
  The session must be at least 10 minutes old and its SOC must have fallen
  by more than 0.5 percentage points. kWh is displayed only for a negative
  measured or estimated energy delta.
- **After returning:** In the absence of an open parking session, the Dashboard
  uses `/api/performance/parking-delta?maxAgeHours=72`, the same ACC-event
  source as the native Dashboard insight. It requires a completed cycle with
  a valid return timestamp and a SOC loss exceeding 0.5 percentage points.
- When Parking Intelligence is off, live parked-session readings are unavailable;
  completed cycles can still be shown through the performance API. If the
  daemon is stopped while parked, the remote Dashboard cannot fetch a new value.

The Dashboard refreshes this optional reading every 60 seconds while visible,
refreshes on tab return, and re-renders the text when the selected language
changes. Its unavailable state does not interfere with the rest of the page.
The English translation strings are maintained in `app/src/main/assets/web/i18n/en.json`;
other languages use the existing English fallback until translations are generated.

## Suggested manual verification

1. Open the Web Dashboard with no qualifying parking record; verify the
   additional line is absent.
2. With Parking Intelligence enabled, leave the car parked for at least 10
   minutes until a live SOC loss exceeds 0.5 percentage points. Verify the
   line appears and shows kWh only when a negative delta is available.
3. Return to the car after a measured SOC loss. Check that the completed-cycle
   value appears, including when Parking Intelligence is not enabled.
4. Check charging, missing SOC, failed API requests, tab hide/reopen, and a
   language change; no stale or fabricated drain should be shown.

No build, automated tests, or device verification were run.
