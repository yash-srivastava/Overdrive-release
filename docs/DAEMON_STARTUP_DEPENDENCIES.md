# Daemon startup and recovery dependencies

`DaemonStartupManager` owns the initial schedule and the 30-second recovery
check. The boot-scoped manager and an Activity-scoped manager must not supervise
at the same time; a destroyed Activity hands supervision back to a boot-scoped
manager without killing running daemons.

Each `initializeOnBoot()` / `initializeOnAppLaunch()` call starts a **new cycle**.
The origin is `SystemClock.elapsedRealtime()` sampled at that initialization,
not device boot uptime. The app may launch well after `BOOT_COMPLETED` (including
on DI3 where the kernel can already have been up for roughly 45 seconds).
The cycle uses these minimum stabilization windows, measured from its origin:

| Deadline | Startup branch |
| --- | --- |
| +45 seconds | Camera daemon; Sentry at +5 s, ACC Sentry at +10 s |
| +60 seconds | Enabled optional daemons and tunnels |
| +90 seconds | Start the existing 30-second health-check loop |

`getprop sys.boot_completed=1` and a working ADB shell must **both** be true
before scheduling the branches. A late ADB connection only consumes time that
has actually elapsed **within that cycle**. Each pending callback carries its
cycle generation so a later initialization cannot release an earlier cycle.
A repeated initialization on the same manager also pauses its prior health-check
loop until the new +90 s deadline; an old loop cannot bypass the settle period.

A tunnel checks the local Singbox port **once at launch**. Thus, if Singbox is
enabled, Cloudflared, Zrok, Tailscale and WireGuard must wait for **both** a
real `pidof sing-box` process and the listening `127.0.0.1:8119` port. When
Singbox is disabled, the four tunnel branches can start independently and use
their existing network-selection behavior. Readiness is not inferred from a
`pgrep -f` match of the invoking shell, nor from GlobalProxyDaemon's callback.

The Singbox dependency has a bounded retry budget; its launch is single-flight
with the 30-second health check. A live Singbox process is never restarted
merely because its parent process is absent or port 8119 is still binding.
After the bounded initial retry period, the health check owns further recovery
and will release missing tunnels only when the dependency is ready. A missing
WireGuard config, a Cloudflared rate-limit backoff, a manual-stop `.disabled`
sentinel, or a parked-shutdown marker still vetoes an automatic launch.

## Suggested device verification

- Cold boot on DI3 and check the +45/+60/+90 windows and 0/5/10 core stagger.
- Open the app after more than 90 seconds uptime, and repeat after an app
  update, camera-settings restart, ADB authorization and park-end rebuild.
- With Singbox enabled, block direct internet access and confirm Tailscale and
  WireGuard do not start until port 8119 is reachable; repeat with Singbox off.
- Force an ADB failure, a manual Singbox stop, and a missing listener while the
  health check is running. Confirm no destructive duplicate Singbox launch.
- Verify Cloudflared/Zrok process readiness, stop-sentinel persistence, and
  the existing startup/recovery contract tests.
