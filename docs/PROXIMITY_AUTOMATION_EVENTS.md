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
- **Unknown**: evidence is missing, stale, stabilizing, or otherwise insufficient,
  **or the vehicle is moving** (speed above zero or a known gear other than P).
  While powered on, a fresh measured speed of zero AND a positive Park reading are
  required; unknown speed/gear fails closed. With the car off, unavailable motion
  sensors are tolerated but an explicit moving reading still vetoes proximity.
  Initial unknown/away readings do not produce an away trigger without a witnessed
  near visit. Repeated stable states do not retrigger the automation.

Enable the main Proximity setting, Bluetooth monitoring, and at least one trusted phone;
keep the app's proximity collector alive. A maintenance tick publishes Unknown when
observations expire even if no new relay arrives; a status poll never renews this lease.
Settings saves that retain the same trusted-phone roster keep the current Near visit.
This is a **Bluetooth phone** signal, not the camera-based Proximity Guard or the
key-fob-only lock rail. The original proximity lock/
unlock command logic still requires the vehicle to be off; this event does not weaken it.

To make a walk-away switch-off rule, choose **Trusted Phone Proximity → Away** as the
trigger and add conditions such as **Gear = Park** and **Speed = 0** before any
**Power Off** action. **The signal is not a substitute for independent vehicle
safety checks**: conditions should be checked again before any power-changing
command. Confirm your vehicle supports the selected action safely while stationary; do not switch vehicle power off while driving. You can add a separate lock
action after power-off if your vehicle and chosen actuator support it.
