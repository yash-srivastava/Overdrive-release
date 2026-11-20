# Daemon startup, ADB reconnects, and optional proxy readiness

The UI and shell-side (boot) daemon managers share one staged-start policy. The
first startup cycle is anchored to `SystemClock.elapsedRealtime()`, not the most
recent ADB authorization. When an Activity takes over a boot manager's pending
cycle it inherits the earliest deadlines, along with stages actually delivered
to a launcher. An `onAuthGranted` callback on a later ADB connection does not
reschedule phases or cancel an active health check.

A timer firing, or `AdbShellExecutor.isConnected()` becoming true, does not
prove the guarded start was dispatched. In particular, DI5 replays still queued
with short staggering, DI3 retry probes, and shell process checks may outlive a
handoff. The old manager records delivery only after the sentinel allows a
start and the launcher request is made (or an existing process is found).
Before the +90 s health check has started, a new manager re-arms remaining
stages at the original elapsed deadlines instead of awaiting the +120 s tick.
After health supervision begins, an Activity teardown resumes **only** its
non-destructive liveness check: stage probes must not erase app-written machine
stop markers. Disabled stages remain gated by preferences, durable manual
stops, and the parked marker. The optional Singbox wait retains its +90 s cap.

| After the original cycle begins | Stage |
| --- | --- |
| +30 s | Tailscale lead only without Singbox; with Singbox enabled it waits for the optional phase |
| +45 s | Camera |
| +50 s | Sentry (5 s after camera) |
| +55 s | ACC Sentry (10 s after camera) |
| +60 s | Other optional daemons; independent tunnels wait up to 30 s for Singbox |
| +75 s | Telegram start, retained across boot/Activity handoffs |
| +90 s | Start the 30 s periodic health check |

The reconnect listener is registered as soon as the cycle begins. An adbd
restart (connection generation greater than one) starts a recovery snapshot
restricted to phases whose deadlines already fired
*without* delaying any of those stages or replacing the periodic loop. Failed
reconnect snapshots receive a limited number of 5 s retries; a failed relaunch
sentinel probe also retries twice. Every retry still honors the user-stop
sentinel, parked-shutdown marker, enabled preferences, and lifecycle teardown.

Singbox is optional even when its enabled preference is still true. A Telegram
stop writes a durable `singbox.disabled` sentinel without changing the pref;
the gate treats that as **off** and starts other enabled tunnels directly. An
unreachable or never-ready port 8119 also releases the tunnels after at most
six 5 s polls. A failed ADB sentinel probe is unknown, **not** a stop: it
retains the same 30 s wait while retrying the gate, so DI3 ADB arriving at
+70 s does not send tunnels direct prematurely. If the probe only succeeds
after the timeout, Singbox still gets a guarded standalone start. An unfinished
optional stage is re-armed at its original deadline on either manager handoff;
the Singbox wait retains the original +90 s cap rather than restarting a new
30 s window. The timer firing alone does not mark dependent tunnels dispatched.

Singbox startup retries only after a terminal failure callback,
never while the previous attempt is still active, and is capped at two
attempts per startup. A process-wide launch reservation shared by boot, Activity,
and Activity health recovery also covers the gap before `pidof` can observe a
new sing-box; it holds through the callback and a short settling interval.
Port checks run off the main looper to satisfy Android StrictMode. A failed
Singbox sentinel probe retains the tunnel wait and bounded retries for
Singbox itself. Port checks determine whether the tunnel launcher uses
Singbox or a direct connection when it actually launches.

The health check's boot-path fallbacks for Singbox and Tailscale are **not**
added here. Those launchers have broader process-restart effects and belong to
a separate change. On the boot path the existing camera, sentry, zrok,
cloudflared and WireGuard revival behavior is retained.

## Review scenarios (device testing still required)

- Start ADB at 51 s, then reconnect: existing +45/+50/+55 deadlines stay put;
  they do not become +96/+101/+106 relative to the latest ADB connection.
- Restart adbd after supervision starts: one immediate health snapshot is
  requested and transient transport failures are retried on a bounded horizon.
- Stop Singbox from Telegram with its preference still enabled: enabled
  tunnels start without Singbox and do not wait forever for port 8119.
- Fail Singbox startup or leave port 8119 closed: the two-attempt cap and the
  30 s proxy-wait bound allow direct tunnel launch. Delay ADB until +70 s and
  ensure a failed probe does not release the tunnels at that time.
- Destroy MainActivity at +65 s during a boot-start proxy wait: the new boot
  manager replays the unfinished optional stage and starts Tailscale without
  waiting for the +120 s health tick. Open the Activity immediately after a boot
  sing-box launch starts: only one launch may be in flight.
- On DI5, open ADB at +51 s, then open or close the Activity during
  +52..56 s while 5 s replay tasks are still queued. The camera must not wait
  until +120 s for the first health tick.
- On DI3, authenticate at +70 s and close the Activity at +72 s; the
  unfinished Tailscale lead must replay. Opening at +71 s must not drop a
  camera start still waiting for its sentinel/process probe.
- After +90 s, close the Activity with undelivered starts: resume only the health
  check; never replay stage probes that remove machine stop markers written by
  update reset, stopAllDaemons, or killDaemon.
- On DI5, connect ADB at +70 s after the Activity closes/opens at +44..62 s;
  prioritize Tailscale ahead of Cloudflared and Zrok so it can launch before
  health check takes over. The overdue sentries use a short replay interval.
- Before +90 s, pending handoff stages replay while confirmed starts do not.
- Relaunch the camera after changing camera settings, including after +90 s:
  the default sentinel-probe retry policy permits the first successful probe to
  restart it without resetting the startup cycle or waiting for another tick.
- Stop a tunnel while its proxy wait is pending, or park the vehicle: the
  final launcher gate must not resurrect it.
- Apply an in-app update: the updater still invokes the regular startup
  entry point, but a duplicate auth callback cannot extend the stage timers.
