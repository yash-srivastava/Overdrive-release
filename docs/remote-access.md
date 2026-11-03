# Proximity access: setup and troubleshooting

This guide describes the proximity lock and unlock implementation shipped in
this repository. It is an operator reference, not a certification of a vehicle
or firmware combination. Use a parked, supervised vehicle for hardware
acceptance; source and emulator tests cannot establish the behavior of its
Bluetooth, key-search, bodywork or lock APIs.

## Start with observation

Open the **Proximity** page in the local dashboard. Monitoring, automatic unlock
and automatic lock are disabled by default. **Learning mode** (`dryRun`) is on
by default: decisions are recorded without sending a vehicle command. Keep it
on while checking the selected phones, their Near/Away thresholds, the key fob
observations and the vehicle's local power, door and lock state. Do not infer
that an unavailable state means a closed door or a locked vehicle.

Phone detection requires the configured Bluetooth keep-alive path and fresh
collector observations. Key fob detection and key fob locking are separate
settings; enabling fob locking requires fob detection. Adding a bonded phone
to the roster does not, by itself, enable automatic commands.

Before supervised actuation, check the intended lock/unlock switches, roster,
safe-zone restrictions and effective settings. A safe-zone restriction needs
at least one selected zone. The handler revalidates the selected geometry and
settings before a command; changing settings can invalidate an existing
decision. Keep an independent way to access the vehicle during acceptance.

## What a decision and its result mean

The phone, fob-event and fob-presence evaluators produce different observations.
They share the final dispatch gate, not a single interchangeable signal. The
gate checks that the action is still enabled, the settings revision is current,
Learning mode is off, the observation is fresh, the vehicle is confirmed off,
and the direction remains supported. A phone decision also requires its
Bluetooth keep-alive path. Lock commands require the applicable closure and
multi-source consensus checks. A fresh key approach or Present result can
veto a phone lock even when the optional wait for a key search is off.

**Confirmation.** A command being accepted by an SDK or a cloud endpoint is
different from a local lock-state readback confirming its target. The DiLink 5
gate and confirmation use the LEFT-front door lock evidence; this does not
prove that every passenger-door lock followed the command. Lock safety checks
also examine doors, hood and trunk. For example, the implementation can report
`Close all doors, hood and trunk: trunk open` or `front-right door unknown`.
Check the named closure or missing observation rather than treating an unknown
value as closed.

The event list distinguishes these outcomes:

| Outcome | Meaning |
| --- | --- |
| `dry_run` | Learning mode recorded the decision; no vehicle command was sent. |
| `blocked` | A guard prevented the command, or a command in that direction was already in progress. |
| `accepted` | A command was accepted and confirmation is pending. |
| `sent` | The SDK accepted the command with vehicle confirmation disabled. |
| `confirmed` | Local readback confirmed the target under the selected confirmation policy. |
| `unconfirmed` | Acceptance did not produce the required local confirmation. |
| `cloud_acknowledged` | The cloud acknowledged the command, but local readback did not confirm it. |
| `failed` | The attempted command/confirmation sequence failed; inspect its detail. |

**Cloud fallback actuator** (17 Oct).

`cloudFallback` defaults to on; saving it as `false` disables this additional
actuator. When a local proximity lock/unlock is refused or remains unconfirmed,
the handler may send the same direction once through
`VehicleCommandRouter.executeCloudOnly`: `OPENDOOR` for unlock and `LOCKDOOR`
for lock. The DiLink 5 typed-none case can send that cloud leg immediately after
the local write, because that typed rail cannot read back its own write. This
is an actuator fallback: cloud state does not supply the proximity gate or its
confirmation. Cloud credentials and an available command route are still
required. The local route can use `RemoteKeyControl`; availability varies by
platform and configuration.

The cloud leg continues the same dispatched decision inside its 30 s budget,
reserving time for a remaining local confirmation window. Before sending it,
the handler checks the current settings revision, master and fallback switches,
Learning mode, decision revocation, local power, local before-state and, for a
lock, the closures. Revalidation failure skips the cloud leg. A local state
already at the target suppresses a duplicate command. A single bounded helper
performs the cloud request; interruption or budget expiry cancels that request
without a retry by this helper.

The original observation's age limit applies to the first local dispatch.
The continuation uses `decisionStillCurrent` and the phone guard's
`allowsIgnoringObservationAge` to check revocation without expiring solely
because that original observation has aged. This does not discard source,
generation, direction, latest-signal or safe-zone checks. Fob presence still
requires healthy, current absence evidence. A new conflicting signal can
invalidate the continuation.

A cloud acknowledgement without local readback is `cloud_acknowledged`, delivered
as an *unconfirmed* notification. It is never evidence that the vehicle is
locked. When confirmation is disabled in Advanced mode, the proximity cloud
fallback is disabled too: without the readback it cannot determine that a
fallback is needed.

Historical references named by the regression contracts are `log_NFKBZ3KU`,
`log_YSP3SY3Y` and `log_W5CFRJFW`. Those log artifacts are not distributed in this
public checkout. The behavior above is derived from the versioned implementation;
these identifiers are not independent test evidence or a claim that a specific
vehicle has been validated.

**Response time and confirmations** (18 Oct).

Normal-mode timing uses `nearWindowMs` 2 s, `nearDwellMs` 1 s, `windowMs` 6 s,
`dwellMs` 2 s, `cooldownMs` 10 s, `relockGraceMs` 10 s,
`postLockQuietMs` 10 s and `confirmWithVehicle` on. The evaluator's
`MIN_CONFIRMING_SAMPLES = 2` requires at least two Near readings for arrival and
two Away readings for the ordinary signal-based departure decision. The arrival
filter uses the last three readings within its configured arrival window;
the two-reading floor remains even when the configured dwell is short.

Confirmation runs on a single-use daemon thread named `proximity-confirm`.
The dispatch monitor is held for the gate and dispatch, not the full readback
or cloud wait, so confirmation no longer delays the next decision. A second
same-direction command is rejected with `Command already in progress` while
the previous confirmation is pending. An opposite-direction dispatch
supersedes the job by interrupting it: an "arrive, immediately leave" sequence must not
continue a stale unlock fallback after the newer lock decision. Inspect the
`Proximity: confirm action=` diagnostic for direction, result, path and timing.

Advanced mode exposes **Confirm with the car's lock state**. Turning it off
records `sent` after SDK acceptance and disables the readback, legacy unlock
re-send and proximity cloud fallback. "Command sent; vehicle confirmation off"
is not a confirmed lock-state result. The **Relock grace after unlock** can
delay relocking; observing a door opening releases that grace early. The
**Keyfob quiet period after lock** suppresses a lone fob detection immediately
after locking. These controls change response behavior; preserve sufficient
time for real observations on the target firmware.

Floors: the unlock window is at least 2 s, departure window at least 4 s,
confirmation dwell at least 1 s and cooldown at least 5 s. Relock grace and fob
quiet period are 3–30 s. The timing ceilings are unchanged: arrival and departure
windows are at most 60 s, dwell at most 30 s and cooldown at most 300 s. Key fob
locking uses a 5–300 s window in 5 s steps. These settings do not remove the
minimum confirming readings or the freshness guards.

*Migration of saved timing.* A deliberate save marks the five evaluator timing
keys with `timingExplicit`. Unmarked saved values equal to the earlier defaults
`15000/4000/4000/2000/30000` are replaced by the current defaults on read.
Unmarked values that differ from those defaults are retained as a prior custom
choice. Setting `timingExplicit` to false resets all five timing keys on the
next read. The page retains Advanced choices while normal mode uses its
effective defaults; reopening Advanced can restore those saved choices.

**Snappy defaults** (20 Oct).

Normal mode uses `confirmWithVehicle` on, `keyFobUnlockTrigger`
`near` (19 Oct), the timing defaults above and the following independent rails:

| Rail | Default | Advanced option | What the option protects against |
| --- | --- | --- | --- |
| Phone unlock | `unlockTrigger` `near` | Away → Near | A phone left inside the car unlocking it on signal fluctuation; the Away → Near option requires a departure before a return. |
| Phone departure by disconnection | 2 not-connected native reads at least 2 s apart | The configured departure dwell can require a longer span. | A single missed native connection read being treated as departure. |
| Phone lock and the key search | `lockWaitsForKeyAbsence` off | **Phone locking waits for the key search** on | A key left in the car: waiting requires confirmed absence rather than relying only on phones, while a fresh key approach/Present veto remains in either mode. |
| Absent roster phones | An eligible absent phone counts Away after 15 s when it never connected in this monitor generation and a fresh native read says the radio is on. | `phoneVetoesLock` continues to determine whether that phone participates in lock consensus. | An absent roster entry indefinitely vetoing another phone's confirmed departure; unknown or stale evidence still does not establish absence. |
| Fob unlock | `keyFobUnlockTrigger` `near` | **Unlock as soon as the key is detected** off selects Away → Near. | A detection without a preceding absence being treated as a return. |
| Fob lock | `keyFobLockSamples` 1 and `keyFobLockWindowMs` 5000 | **Absence results required** 2–3 and a longer **Keyfob locking window** | A single missed read locking a key left inside. |

For phone unlock choose **Near**,
the default since 20 Oct, to permit a stable first connection or reconnection.
Use Away → Near if that arrival behavior is inappropriate. Phone and fob
triggers are independent.

For a phone departure based on disconnection, `DISCONNECTION_CONFIRM_READS`
and `DISCONNECTION_CONFIRM_SPAN_MS` require at least two distinct fresh native reads spanning the
greater of two seconds and the configured departure dwell. An absent roster
phone follows the separate `ABSENT_PHONE_GRACE_MS` rule: a fresh radio-on read,
no Near/Transition or connected evidence and the stated monitor-generation
conditions are required. It does not replace the departing phone's own
confirmed departure.

The relock grace and keyfob quiet period apply alongside these rail defaults.
**Absence results required** (`keyFobLockSamples`, 1–3, default 1, 20 Oct)
counts completed clean Absent results, not arbitrary repeated display polls.
Unknown results, timeouts, new Present evidence and stale collectors must not
be interpreted as proof that a key is outside.

For saved-choice migration, `unlockTriggerExplicit` distinguishes a deliberately
saved phone trigger from the old default echoed by a generic save.
`keyFobLockExplicit` does the same for the fob locking window/result count.
Unmarked old defaults migrate, while deliberate saved choices remain available
in Advanced mode. Returning to normal mode applies the effective defaults.

**Fob unlock trigger** (`keyFobUnlockTrigger`, 19 Oct).

The normal-mode default is `near`: **Unlock as soon as the key is detected**.
For an eligible locked, off vehicle the first
fresh detection frame (mask `8`) can produce an arrival, with
no prior absence frame (`4`) required. The `postLockQuietMs` guard still
suppresses a lone detection in the post-lock quiet period.

The Advanced-only `away_to_near` option requires an absence frame or the
post-lock arming seed before a new detection. After an install, restart,
settings reset or remote power cycle with an already locked car, the first
detection can therefore report `reason=unarmed_detection` instead of unlocking.
The source comments cite a field observation at **19 Oct 21:18:38** for this
diagnostic. Its original log is not supplied here; this is context for the
reason code, not a new hardware reproduction.

Do not assume either mode can distinguish a key locked inside the
car from every externally detected key. That guarantee is unverified for a
particular vehicle/firmware combination. The 17 Oct note on cloud fallback
changes the actuator continuation, not the fob trigger or physical-key sensing.

The page displays old values when an Advanced custom choice is retained for
later, while normal mode runs the effective defaults. Check both the mode and
the saved/effective settings before diagnosing a trigger as ignored.

**Changing the rail set**

DiLink 5 `di5LockRails` is a validated comma-separated selection from
`typed-cabin`, `ota` and `carsvc`. The current default is
`typed-cabin,carsvc`; do not enable a rail merely because its name is accepted.
The collector must expose usable local evidence on the target vehicle.
`di5CarSvcLockMaxEventAgeSec` bounds the age of the retained car-service lock
event (60–86400 s, default 86400 s). This is separate from the freshness of the
dump/collector observation and must not be mistaken for permission to use a
stale collector.

Changing either rail setting is a deliberate choice marked by
`di5LockSettingsExplicit`. Inspect rejected/fallback setting diagnostics after
an update. Preserve Learning mode while comparing each available rail's raw
state, observation time and local door-state behavior. Do not bypass guards
or infer a successful lock from a cloud acknowledgement to compensate for a
missing rail.

## Source references and validation limits

The behavior in this guide is implemented by:

- [`RemoteAccessSettings`](../app/src/main/java/com/overdrive/app/remoteaccess/RemoteAccessSettings.java): defaults, bounds, Advanced behavior and migration markers.
- [`RemoteAccessApiHandler`](../app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java): dispatch gates, confirmation jobs and cloud continuation.
- [`ProximityEvaluator`](../app/src/main/java/com/overdrive/app/remoteaccess/ProximityEvaluator.java) and [`MultiPhoneProximityPolicy`](../app/src/main/java/com/overdrive/app/remoteaccess/MultiPhoneProximityPolicy.java): confirming samples, disconnection and roster consensus.
- [`KeyFobEventEvaluator`](../app/src/main/java/com/overdrive/app/remoteaccess/KeyFobEventEvaluator.java) and [`KeyFobPresenceEvaluator`](../app/src/main/java/com/overdrive/app/remoteaccess/KeyFobPresenceEvaluator.java): fob events, absence results, freshness and revocation.
- [`DiLink5GateActuationPolicy`](../app/src/main/java/com/overdrive/app/remoteaccess/DiLink5GateActuationPolicy.java): local readback and bounded fallback policies.

Repository contract tests check that the guide stays aligned with selected
defaults and safety distinctions. They cannot validate actual locks, prevent
every radio false positive or replace supervised hardware acceptance. Do not
run commands or disclose credentials merely to diagnose a documentation test.
