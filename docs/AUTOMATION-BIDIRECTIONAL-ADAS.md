# Bidirectional automation coverage: ESP and iTAC

The automation editor uses the same IDs for two ADAS features on both sides of
an automation rule. `ESP State` and `iTAC State` can be selected as **When**
triggers or **If** conditions; the existing `Set ESP State` and `Set iTAC State`
remain available as **Then/Else** actions.

| Feature | When / If signal | Then / Else action | Readback |
| --- | --- | --- | --- |
| ESP (stability control) | `esp_control`: `on` / `off` | `esp_control`: `on` / `off` | `getEspState`: 0 = on, 1 = off |
| iTAC (torque control) | `itac`: `on` / `off` | `itac`: `on` / `off` | `getItacState`: provisionally 0 = on, 1/2 = off (requires on-car confirmation) |

These conditions are available only on **DiLink 3/4**. On DiLink 5,
`AdasManagerImpl.getESPState()` returns a hardcoded 0 (not a measurement) and
the iTAC getter is a stub; the editor does not offer these conditions and the
automation poller never reads either getter.

Unlike command acknowledgement, conditions use **observed** OEM state. While
an enabled rule references ESP or iTAC, the sampler runs every 2 seconds and
reads only referenced getters. With ACC off it makes **no ADAS getter calls**
and expires both states, preventing a stale IF condition from matching.
Unknown return values also expire the relevant state, rather than defaulting
to `off`. A first reading is silently seeded. Opening the automation editor
seeds both current values without requiring an enabled rule and without firing
automations; editor reads are also skipped with ACC off or on DiLink 5.

**Hardware validation outstanding:** the ESP mapping (0=on, 1=off) should be
checked on a DiLink 3/4 car. The iTAC mapping above follows one existing tool
interpretation, but it **conflicts with the previous API mapping** (1=on,
0=off). It is not confirmed by a real toggle yet. Before treating iTAC
automations as dependable, toggle ESP and iTAC separately in BYD's own menu
and record the `esp.raw` and `itac.raw` values from `GET /api/vehicle/adas`
with each control on and off, including model and DiLink version. Do not
describe this mapping as field-validated until those readings are available.

ESP may be re-enabled by the vehicle at startup. Turning it off reduces safety;
existing write-route restrictions and warnings remain in force. Both actions
may be unavailable depending on vehicle generation/firmware. No ability to
change safety-critical actuators is added by the condition support.

This change does **not** claim parity for every vehicle control. A device
must expose a trustworthy readable state to offer a useful condition; set-only
commands (e.g. folding a mirror) are intentionally not advertised as if they
had a confirmed state. Verify the getter encoding and the real state changes
on the target vehicle before relying on these rules.
