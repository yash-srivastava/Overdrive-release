# Sentry detection pipeline — invariants

Contract for the surveillance/sentry detection path. Any change to this package is
checked against these invariants, not just against its local intent. Rationale for
each lives in the referenced code comments; this file is the index.

**Prime directive:** the system's false-positive rejection is GOOD and protected.
Only misses (false negatives) are defects. A change is shippable only if it is
FP-neutral or FP-reducing. "Catches more" is not, by itself, a justification.

## The four evidence channels

A recording is justified by ONE of four independent kinds of evidence. Any change
must be checked against all four, because a stage that serves one can starve another:

1. **Class evidence** — YOLO resolved a person/vehicle (`sequenceConfirmed`).
2. **Dwell evidence** — a TRUSTED HIGH loiter (coherent translation or an in-zone
   person tracker holds it).
3. **Salience evidence** — the motion geometry itself is object-grade: large,
   compact, sustained, photometrically stable, rigidly translating. Opt-in
   (`motionSalienceEnabled`, default OFF).
4. **Context evidence (post-park vigilance)** — motion adjacent to a FRESH PARKER:
   a confirmed vehicle baseline entry that was WATCHED arriving by a live event
   (`Entry.fromLiveEvent`, never the sentry-start seed or a lighting refresh)
   within the last 10 min. `postParkVigilanceEnabled`, default ON.

Channel 3 exists because 1 and 2 both fail on the same real subject: YOLO gets
~2-4 windows on a dark fisheye crop and returns nothing, and a subject that keeps
moving never reads as a loiter. Its own inversion hazard is documented below.

Channel 4 exists because the parking event itself DISARMS the other channels for
its own aftermath: the parked car is baseline-suppressed (so its boxes no longer
confirm anything), the occupant's exit is short and fragmented (never a loiter,
resets the sequence on >2 s pauses), the person is door/self-occluded (YOLO-blind),
walks AWAY (reads passing), and a car parked beyond NEAR/MID keeps
`peakCloseZoneDuringSequence` false so the far-unconfirmed gate kills the timeout
fallback. Vigilance grants: a 1 s bar, a far-gate exemption, and the fast AI
cadence — it does NOT clear the AI-confirm gate (YOLO always gets its 2 s timeout
window). Brakes: 10 s min-gap after any stop + a hard budget of 3 assisted
(unconfirmed) triggers per rolling 10 min + TTL'd latch (10 s) + quadrant scoping
+ brightness/incoherence discriminators at every consumer.

**With YOLO loaded, channels 2-4 cannot record on their own:** the final gate after
the overrides (`if (aiAvailable && !aiRecentlyConfirmed) shouldSuppress = true`, v44.2)
re-suppresses them, so only class evidence (1) authorizes a recording. Their
"override … recording" log lines therefore don't mean a recording started.

**Light-change check (feeds channel 1).** Early AI only dispatches cameras whose
motion survived, and the `>25%` flash filter discards a whole camera — so a close car
whose headlights fill that camera was never classified. A flash-killed camera
(`isLightChangeKill`) gets whole-view mosaic YOLO looks in a chain (first look on the
kill, follow-ups ≥1 s apart, ≤3 looks / 4.5 s). Detections must pass the zone rows
and the drawn area (`quadrantRoiMirror`; the camera has no motion mask) and, for a
person, the 0.50 bar. Only a HIT counts and only hits flow downstream: a person seen
again on a follow-up (not at a spot that already recorded within 5 min), or a vehicle
that MOVED — a same-size box off every still chain position, paired with a chain
position now empty. One look can't tell a passing car from an unlearned parked car
the light just lit, and box size flickers with light, so neither one look nor size
change (approach/recede) counts. A hit records directly (outside the lifecycle
lock); a still chain promotes its cars into the baseline. Brakes: per-camera 3 s gap,
30 looks / 10 min per camera, takes turns with motion AI, skipped while recording or
after our deterrent. Accepted residual: two same-size unlearned parked cars lit one
after the other read as a move (implies a moving light source).

## The funnel

A real event is missed iff any stage wrongly kills the signal:

| Stage | What it is | Where |
|---|---|---|
| S1 | Native block-motion (~8.7 Hz, 10×7×32 px grid on 320×240 quadrant) | `cpp/surveillance/motion_pipeline_v2.*` |
| S2 | Trigger decision (duration bars, fast paths, suppressive latches) | `SurveillanceEngineGpu.processFrameV2` |
| S3 | AI dispatch (cooldowns, lane latch, foveated/mosaic crop) | `SurveillanceEngineGpu.runAiOnQuadrant` |
| S4 | Detection filtering (class → motion-overlap → baseline → threat gate) | aiTask lambda |
| S5 | Identity (CQT trackId → ActorTracker actor/severity/static) | `CrossQuadrantTracker`, `ActorTracker` |
| S6 | Event lifecycle (stop clocks, extension, discard) | start/stop/discard paths |
| S7 | Surfaces (push, Telegram, hero, timeline, automations) | publish*/send*/ThumbnailBuffer |

## Invariants

**I1 — Fail open on missing/invalid data.** A test that cannot run must PASS the
signal, never drop it. Existing instances: `fovMapValid=false` keeps the detection;
`hasAffine()` rejects NaN; degenerate-bbox and unmapped-bottom-band fail-opens (the
grid covers 320×224 of a 320×240 quadrant — the bottom 16 px can never report
motion); null hero mask → "live"; `trackerInZone` throws → in-zone.

**I2 — No suppressive latch without per-tick decay and session reset.** Every latch
that can block a trigger must decay at tick rate (never inside a `frameCount % N`
block — that nesting made one headlight sweep deafen the close-range fast paths for
minutes) and be reset in `enable()`. Current latches: `suppressionWasActive[]`
(per-tick decay + reset), `cachedHighIsTrusted`/`cachedIncoherentLoiter` (reset with
each sequence), deterrent/NO_AI windows (deliberate, bounded, documented FP guards).

**I3 — Suppression is evidence-scoped.** A suppressor kills only the signal class it
has positive evidence against: brightness → lighting-artifact paths only, and the
live per-quadrant flag still applies during a real sweep; baseline → that region +
canonical class, confirmed entries only, persons exempt; scenery verdict → that
actorId only, persons exempt.

**I4 — Identity errors degrade toward FRAGMENTATION, never absorption.** A duplicate
actorId costs MIN_ESCALATION_FRAMES (~1 s of NOTICE pin) — bounded. An absorbed
identity inherits `everMoved=false`/`historyCount`, can stamp `isStaticForTimeline`
on a real mover's first frame, suppress its counts, and feed the discard — unbounded.
Hence: stale same-quadrant CQT match uses a flat jitter radius
(`STALE_MATCH_RADIUS_PX`), the ActorTracker hint path demands IoU>0 after a >1.5 s
same-quadrant gap, and a rejected hint is never re-stamped (`hintRejected`).
Known bounded residuals, accepted deliberately (do NOT patch without a full re-derivation):
same-batch double-match of one CQT track degrades toward `everMoved=true` (more
alerting); a rejected-hint object crossing quadrants within the old track's TTL can
take ≤2 frames of wrong static before the anchor re-seed latches `everMoved`.

**I5 — Coordinates travel with their space.** A bbox crossing a module boundary
carries its space (`qW/qH` into ActorTracker/ThumbnailBuffer; the foveated affine
rides the PBO ring WITH the pixels; CQT input is mapped to 320×240 via that affine,
ax=0.25 + origin + flip — never a bare 0.5 scale). No consumer assumes.

**I6 — Stop/discard may only remove what it can prove is nothing.** Discard requires
positive FP evidence and hard-KEEPs on person/moving-object/approach/coherence
latches. Stop clocks are seeded inside `startRecording()` itself so no caller can
create an unstoppable clip. Extension liveness reads unfiltered native counts (the
per-quadrant override recount must not deflate them on the pass path).

**I8 — Mass is not evidence of a lighting event.** Motion mass grows with object
SIZE and CLOSENESS as well as with illumination change, so any test of the form
"too many blocks ⇒ artifact" inverts on the closest, largest — most threatening —
subjects. The native `>25%` global-flash filter is exactly this shape: a person at
~1.5 m is ~18/70 blocks, and `tierFromMotion` calls ≥20 blocks NEAR, so the events
with the MOST motion evidence were the ones whose `peakCloseZoneDuringSequence`
never latched, closing every YOLO-blind escape path at once. Mass-based
suppression must therefore be a PROBE (measure shape + coherence, then decide),
never an early return. A failed probe must restore state and reproduce the original
suppression byte-for-byte.

**I10 — A trigger channel with no external brake needs an explicit one.** YOLO
confirmation is what rate-limits the class and dwell channels in the field: a real
object has to be re-classified to re-fire. Salience has no such dependency, and its
per-quadrant run counters deliberately survive a sequence boundary, so a scene that
keeps qualifying would re-latch on the next sequence's first tick and re-fire every
`SALIENCE_TRIGGER_MS` — a muxer-init/pre-record-flush storm that leaks MediaCodec
slots until SIGABRT (the failure mode `NO_AI_MIN_GAP_MS` already exists to stop).
Hence `SALIENCE_MIN_GAP_MS` gates salience-ONLY triggers; the other two channels are
never delayed by it.

**I11 — An evidence latch may not outlive its evidence.** Sequence-scoped latches are
only safe when a sequence is short. A sentry sequence ends on a >2 s motion gap, so on
a busy scene ANY quadrant refreshing `lastMotionTime` keeps it open indefinitely — a
600 ms burst in one quadrant would then keep vouching for an unrelated subject in
another minutes later. Hence `salienceConfirmedDuringSequence` carries
`salienceConfirmedAtMs` and expires after `SALIENCE_LATCH_TTL_MS`, re-stamped by each
qualifying tick. Cross-quadrant is the specific trap: `peakCloseZoneDuringSequence` is
latched off the best-threat quadrant while salience is per-quadrant, so the two can
describe different subjects.

**I9 — A channel that only ADDS triggers fails CLOSED.** I1's fail-open applies to
tests that can DROP a signal. The salience channel can only add one, so absent
evidence (`flowCoherence == -1`, `componentCount == 0`, native library without the
salience fields) must mean "do not trigger" — never "assume qualifying". Inverting
this would turn every unmeasurable frame into a recording.

**I7 — Every dropped signal is observable.** Each kill site logs or counts
(`noteAiSkip`, OVERRIDE_DEMOTED, suppression logs, funnel stats) so a field miss can
be attributed to a stage without a rebuild.

## Audit status

| Stage | Last audited | Result |
|---|---|---|
| S2 brightness-event scoping (I3 alignment) | 2026-08-11 (re-audited same day; correlated-lighting gap found and closed) | The trigger path's lighting-artifact discriminator was ONE scene-wide any-quadrant OR (`brightnessEventDuringSequence`); a headlight sweep on any camera closed the close-zone fast-path + override and stretched the 2 s AI-confirm timeout to 20 s for every quadrant. Replaced with per-consumer evidence scoping (`brightnessEventInQuadrant` + three scope flags): close-zone consumers test {best-threat quadrant, `peakCloseZoneQuadrant` (new, latched with the close-zone latch, reset with it)}, salience consumers {best-threat, `salienceQuadrant`}, vigilance consumers {best-threat, `vigilanceQuadrant`}. AUDIT FINDING (fixed): one physical lighting event can suppress camera A while its light POOL reads as ordinary coherent MEDIUM+ motion in camera B — per-quadrant scoping alone re-opened the fast paths for B's pool. Closed by the CORRELATED-LIGHTING GUARD: any quadrant's suppression ONSET within ±1 s (`LIGHTING_CORRELATION_MS`) of the sequence's `firstMotionTime` marks the WHOLE sequence lighting-correlated for all three scopes (the old OR's protection, restored exactly where the correlation exists); onset stamps are edge-detected every tick unconditionally (`suppressionOnsetMs[]`/`brightnessPrevTick[]`, reset in enable()). A sweep starting well after a real subject's sequence began stays quadrant-scoped — the cross-camera FN fix is preserved. Checked against I2/I3/I6/I7 as before; the "AI gate holding" line logs any-quadrant AND scoped values. The `shouldDiscardEvent` night path is UNTOUCHED — its own scene-wide-OR coupling was already neutralized by `eventSawUncharacterizedMotion` (verified: the native suppressed path early-returns before component formation, so "FP evidence and unexplained blob on the same tick in the same quadrant" is unreachable). |
| Stationary-subject revival channel (MOG2 persistent foreground) | 2026-08-11 (re-audited same day by 4 adversarial reviewers; all findings fixed) | Fifth immunity mechanism (after brightness-immunity and standing-person immunity), covering the YOLO-blind stationary subject: frame differencing loses a stopped subject in ~300ms and the >2s gap resets the sequence before any bar is reached. The dormant OpenCV MOG2 in native_motion.cpp is now fed the full 640×480 mosaic at 2 FPS via new JNI (`computeMOG2Quadrants` — ONE apply() per call, per-quadrant fractions out; `resetMOG2` for session reset; both mutex-guarded and exception-guarded at the JNI boundary — audit found the unguarded cv::Ptr reassignment racing a straggler apply() on fast disable→enable, and that an escaped cv::Exception would abort the daemon). REVIVAL-ONLY by construction: revives `anyMotion` for an already-running, NOT-YET-RECORDING sequence exactly like the standing-person immunity; can NEVER start a sequence (revive-only guard `firstMotionTime != 0`), never runs while recording (audit: a recording-time revival fed the extension branch every tick and made the 3× postRecord hard ceiling unreachable), never lowers a bar, never touches the AI-confirm/far gates, NOT a discard KEEP. PRE-TRIGGER SCOPE means the recording decision (bars + AI gate + far gate + no-AI rate limits) is unchanged; the acknowledged FP residual — a sequence started by real motion whose quadrant retains OTHER static foreground — is bounded by the DEPARTURE BRAKE: the current fraction must hold ≥80% of the baseline captured at the last qualifying-motion tick (`sequenceMog2BaselineFrac`; missing baseline = revival disarmed, I9). Further brakes: quadrant-scoped to the exact quadrant that produced the qualifying signal (`sequenceMotionQuadrant` — audit: captured from the qualifying loop / tracked-person quadrant itself, NOT getHighestThreatQuadrant, which any LOW(pass) quadrant can poison; I11), brightness-discriminated on that quadrant (I3), fail-closed on ANY missing evidence — OpenCV absent, warmup (arms after the 21st sample ≈ 10s), stale sample (>1.6s), native error (I9), 60s per-stretch budget (I10) atop the model's natural ~50s static-scene absorption. ALL session state + the native model reset in enable() BEFORE the volatile `active` publication (audit: post-publication resets both raced the native apply() — UAF — and could be lost to the engine thread — a skipped lr=1.0 reseed + pre-satisfied warmup). Config kill switch `stationaryRevivalEnabled` (default true), read once per session. DEVICE-UNVERIFIED — `MOG2_REVIVE_MIN_FRAC` (0.03), `MOG2_NO_DROP_FRAC` (0.8) and the warmup/budget values are reasoned, not measured. |
| S3 side-cam detector-input dewarp (`FisheyeDewarp`) | 2026-08-11 | Mirror cams (Q1/Q3) get their DETECTOR INPUT dewarped with the same division model the recorder/blind-spot card ship (k1=0.15, k2=0.05 ⇔ strength 0.5 — chosen by an offline sweep of the shipped yolo26n-DRQ over 30 real recorded frames: left cam +54% summed confidence / +71% detections at 0.5, collapse at ≥0.75). FIXED in-code constants, deliberately NOT the user's display slider (a display preference must not move detection accuracy, and its default 0 would keep the fix off). Scope: full-tile 320×240 mosaic crops only — a foveated window is not lens-centered, so the tile-radial model would be wrong there; the close-subject wide-crop gate already forces NEAR subjects onto the mosaic path. I5: boxes are mapped back to warped source space immediately after detect() (same forward transform, corners+edge midpoints), so every downstream consumer (motion-overlap, baseline, ActorTracker, ThumbnailBuffer, tracker seeds) sees the exact space it always has — verified by an 11-test JVM suite incl. a pixel↔box round-trip (`FisheyeDewarpTest`). I1: any error/ineligibility → null → original crop + identity boxes (add-only stage). Front/rear at 0 (no subjects in test footage — enable only with evidence). DEVICE-UNVERIFIED for the right cam specifically (constant inherited from the left's identical mirror-camera hardware). |
| S3/S4 detector head auto-detection (END2END support) | 2026-08-11 | `YoloDetector.kt` now probes output tensor 0's shape in `init()`: `[1,4+C,N]` → legacy raw-anchor parser + host NMS (the shipped yolo11n path, byte-identical — dims were hardcoded 84/8400 and are now read from the model, same values); `[1,D,6]` → new END2END parser for NMS-free exports (YOLO26 via `dev/export_yolo26_int8.py`). Input size likewise probed (square-enforced). UNKNOWN layouts now FAIL `init()` loudly instead of parsing garbage — a mis-parsed head would read downstream as "YOLO saw nothing" on every real event, the silent-FN mode I7 exists to prevent. The END2END parser mirrors every legacy gate (class mask, implausible-class floor, size gates, ghost cap TRUNCATE-never-clear, raw-funnel diagnostics) in the same order; comments in both parsers require mirrored edits. Coordinate units decided ONCE PER TENSOR from conf-passing rows (audit: the earlier per-row probe could misroute a confident sub-2px-corner pixel-unit box into a large phantom that SURVIVES the size gate — the opposite of its comment's claim; per-tensor decision removes the path, and the reverse misroute is impossible since normalized coords are ≤1.0). init() failure paths close+null the interpreter (audit: allocateTensors/probe throws formerly leaked the live native interpreter). The shipped END2END artifact (yolo26n-DRQ) was validated offline against its FP32 reference on real device frames (10/12 matched, misses threshold-edge); ON-DEVICE latency still unverified. Legacy path regression risk: nil (same constants, same code path; rollback asset loads via the candidate list). |
| Post-park vigilance channel (S2 wiring + DetectionBaseline anchors) | 2026-08-09 | Added; pure Java, no native change. Checked against I2 (TTL latch, per-tick stamp, enable() reset), I3 (anchor-scoped: quadrant + 0.30-norm foot-point radius + 10 min age), I9 (confirmed live-event vehicle entries only; probe errors → no anchor), I10 (10 s min-gap + 3-assists/10 min rolling budget), I11 (10 s TTL re-stamped per qualifying tick; consumers require best-threat quadrant == latch quadrant). Deliberately NOT a `shouldDiscardEvent` KEEP (mirrors salience: the no-actor discard is the precision partner). DEVICE-UNVERIFIED — the adjacency radius (0.30), TTL (10 s) and window/budget (10 min / 3) are reasoned, not measured. |
| Salience channel (native probe + S2 wiring) | 2026-07-31 | Added; see I8/I9. Compiles; native `.so` verified rebuilt. DEVICE-UNVERIFIED — the five per-tick terms' thresholds (12 blocks, 0.60 dominance, ≤3 blobs, 6 ticks, 12 luma) are reasoned, not measured. |
| S1 native motion | 2026-07-25 | No actionable MISS findings. Verified: `-1` coherence sentinel survives `memset`; `suppressionCountdown` decrements every frame AND fast-adapts baseline during suppression (α=0.2) so post-flash drift can't re-trigger forever; global-flash sync checks only NEW shifts, never existing countdowns (self-reinforcing-loop guard); exposure hysteresis has an 85/95 deadband + 15-frame ISP blindfold; Stage 3 increment/decay mutually exclusive; `edgeChangedCount>=2` exempts a block from shadow suppression (self-erasing-person guard); all block loops bounded by `V2_GRID_ROWS` with an extra `gy >= HEIGHT` guard. |
| S2-S7 Java | 2026-07-25 | See git history for the fix set; residuals listed under I4. |

Known non-defects in S1, deliberately left: `stage5_behaviorClassification`'s `maxDrift`
compares each history entry against the NEWEST centroid rather than pairwise, so "loiter"
means "stayed within radius of the current position" — intended, and the permissive
direction. `prevModes` (motion_pipeline_v2.cpp) is `static`, i.e. process-global rather
than per-quadrant-instance; affects only a log line.

## Asymmetries that look inconsistent but are load-bearing

- The S4 motion-overlap filter is dilated by one block; `ThumbnailBuffer`'s copy is
  NOT. Opposite penalties: drop-the-detection vs cosmetic scenery flag. Do not unify.
- Timeline sidecars may relabel a single-object PERSON span as ANIMAL **only**
  when the same camera/time window has a confirmed moving animal with at least
  the span's person confidence, and no confirmed person. This is cosmetic:
  YOLO trigger evidence, ActorTracker class/severity, and hero selection are
  unchanged. Without animal-class evidence (for example, Animal detection off
  or a model that labels every dog frame PERSON), the marker stays PERSON.
  Multi-camera/multi-object spans fail open to PERSON.
- `beatsAsThreat` demotes an all-scenery headline, but the count loops are untouched
  and `pushWorthy` latches before the demotion. Cosmetic surfaces may narrow;
  gates may not.
- Train/boat/airplane are retained in the detector's vehicle mask but dropped by the
  engine class filter. Deliberate: re-admitting them reopens a documented phantom-FP
  channel. Leave.
- Salience is a KEEP override for nothing. Every other trigger-evidence latch
  (person / moving object / lateral mass / AI-blind timeout) hard-KEEPs its clip in
  `shouldDiscardEvent`, but `eventTriggerWasSalience` deliberately does NOT: the
  no-actor discard is the intended precision partner of the salience recall, so a
  salience clip with no actor is exactly what the user asked to be filtered when
  they enable that toggle. It is logged in both discard/keep lines for attribution.
- The salience per-quadrant run counters (`salienceRunTicks`) reset on any
  non-qualifying tick and in `enable()`, but NOT at motion-sequence start. They
  describe the scene, not the sequence, and a sequence boundary is itself >2 s of
  non-qualifying ticks.
- Post-park vigilance, like salience, is NOT a `shouldDiscardEvent` KEEP
  (`eventTriggerWasVigilance` is attribution-only). And unlike every sequence
  latch, its ANCHORS live in `DetectionBaseline` and survive both sequence
  boundaries and recording stops on purpose — the anchor describes the scene
  (a car arrived here recently), not the sequence. It is bounded by `addedAtMs`
  age, cleared by `baseline.reset()` on disable, and can never be created by the
  sentry-start seed (`fromLiveEvent` stays false there), so a re-arm cannot
  inherit a zone it didn't watch form.

### Salience: accepted residual (audited 2026-07-31)

A **coherently translating illumination front** — a swept headlight beam on a
textured wall, or a wiper blade — satisfies the native probe's three geometric
tests (mass, single dominant blob, rigid translation). It is bounded, not
eliminated, by four independent existing mechanisms, which is why it is accepted
rather than patched: Stage 1's mean-luma shift and the PASS-1 global sync usually
suppress a beam that large before the probe runs; Stage 2's chroma + edge gate
kills soft shadow fronts; `stage5` caps a >15%-area result at THREAT_MEDIUM, so
Java still demands YOLO or salience confirmation; and the Java channel additionally
requires 6 consecutive ticks of luma stability (`salienceMaxLumaDelta`), which a
sweep breaks. Do not add a fourth geometric test here without re-deriving all four.
