# Trusted-phone proximity automation events

The **Trusted Phone Proximity** trigger/condition (`proximityPresence`) uses the Bluetooth
**Proximity** settings already configured under Remote Access. It publishes `near`, `away`
and `unknown`. It is independent of the automatic door-lock feature and **never** issues
vehicle commands. In particular, it can publish an “away” transition while the car is on.

- **Near**: at least one enabled trusted phone is confirmed near by the same RSSI
  threshold, dwell, and freshness rules used for proximity access.
- **Away**: after a confirmed near visit, **every** enabled trusted phone meets the
  existing multi-phone away evidence rules (stable low RSSI or qualifying confirmed
  native disconnection). A detected physical key vetoes away unless key-inside consent
  is enabled. The optional **wait for key absence** applies only while the car is off:
  its underlying `allowsLock` result requires power 0, so it is intentionally skipped
  **for this read-only event** while the car is on. This never changes the lock rail.
  One phone leaving while another is near or unknown is not away.
- **Unknown**: evidence is missing, stale, stabilizing, or otherwise insufficient.
  Stale evidence **forgets the Near visit**: a collector restart, dead phone or
  Bluetooth restart cannot report a delayed Away unless a new Near is confirmed.
  With the car on, the shared `DrivingSafetyGuard` movement gate must confirm a
  stationary/Park state. This gate can use fresh GPS speed on models without a
  hardware speed reader; an unconfirmed driving state cannot authorize Away.
  **While moving**, the last published proximity state is *held* (not replaced
  with Unknown), but the visit and phone evidence are cleared. The next Park stop
  cannot re-fire an unchanged Near. A bounded post-drive requalification window
  holds the last state while fresh parked observations establish Near; otherwise
  normal Unknown publication resumes after the configured evidence timeout.
  A subsequent Away needs that new parked Near visit. Stable states never re-fire rules.

Enable the main Proximity setting, Bluetooth monitoring, and at least one trusted phone;
keep the app's proximity collector alive. The existing 5-second maintenance tick is
skipped entirely while Proximity is disabled. When enabled, it publishes Unknown
once observations expire even without new relays; a status poll never renews them.
Settings saves keep the current Near visit for retained phones and do not emit a
spurious Unknown/Near pair if the resulting state is unchanged. Roster changes
invalidate departure history without forcing a redundant publication.
This is a **Bluetooth phone** signal, not the camera-based Proximity Guard or the
key-fob-only lock rail. The original proximity lock/
unlock command logic still requires the vehicle to be off; this event does not weaken it.

**Do not treat Away as proof the driver left the car.** A phone running out of
battery, losing Bluetooth or being switched off can appear Away while the driver
is still seated, **including with the vehicle powered on and in Park**. Gear = Park
and Speed = 0 alone do **not** make an unattended Power Off automation safe.
Prefer read-only notifications or require separate positive driver-absence
verification and an explicit, safe confirmation before any power-changing action.
Never switch vehicle power off while driving. The original guarded lock rail is
unchanged by this event.

UTF-8 source proof: “away” / proximité / קרבת טלפון מהימן
