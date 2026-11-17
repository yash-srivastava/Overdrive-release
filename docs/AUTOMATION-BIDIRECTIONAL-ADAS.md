# Bidirectional automation coverage: ESP and iTAC

The automation editor uses the same IDs for two ADAS features on both sides of
an automation rule. `ESP State` and `iTAC State` can be selected as **When**
triggers or **If** conditions; the existing `Set ESP State` and `Set iTAC State`
remain available as **Then/Else** actions.

| Feature | When / If signal | Then / Else action | Readback |
| --- | --- | --- | --- |
| ESP (stability control) | `esp_control`: `on` / `off` | `esp_control`: `on` / `off` | `getEspState`: 0 = on, 1 = off |
| iTAC (torque control) | `itac`: `on` / `off` | `itac`: `on` / `off` | `getItacState`: 1 = on, 0 = off |

Unlike command acknowledgement, these conditions represent **observed** OEM
state. They are sampled only while referenced by an enabled automation, at
2-second intervals. The conditional poller silently seeds the first reading,
then publishes changes. An unsupported getter or unknown return value never
becomes a synthetic `off` event; an old reading is expired so stale state cannot
satisfy an unrelated **If** gate. The iTAC **write** encoding `2 = off` is
not assumed to be a valid **read** encoding; the existing vehicle-control API
also interprets only 0 and 1 on readback.

ESP may be re-enabled by the vehicle at startup. Turning it off reduces safety;
existing write-route restrictions and warnings remain in force. Both actions
may be unavailable depending on vehicle generation/firmware. No ability to
change safety-critical actuators is added by the condition support.

This change does **not** claim parity for every vehicle control. A device
must expose a trustworthy readable state to offer a useful condition; set-only
commands (e.g. folding a mirror) are intentionally not advertised as if they
had a confirmed state. Verify the getter encoding and the real state changes
on the target vehicle before relying on these rules.
