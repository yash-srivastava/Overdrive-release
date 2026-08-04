# Remote Overdrive Dev View

Remote Overdrive Dev View captures and controls Overdrive's own Android window.
Its primary use is development while a BYD is ACC-off and the OEM's opaque
`AccAnimation` layer makes whole-display capture tools return black.

Open the authenticated web dashboard, choose **Remote Dev View**, acknowledge
the compact parked-use notice, and start a session. Starting a session launches
Overdrive normally. It does not bypass the PIN screen and does not turn on,
uncover, or otherwise alter the physical head-unit display. The viewer can be
expanded to browser fullscreen; Back, Refresh, End, and Exit remain available
in a small overlay while direct touch control continues on the frame.

## Security boundary

- The page and every `/api/dev-view/*` request pass the normal dashboard JWT
  middleware.
- Session creation additionally requires explicit confirmation and returns a
  random, single-client capability kept only in browser memory.
- Capabilities expire after five minutes without activity and after eight hours
  regardless of activity. Starting another session invalidates the first.
- Frames use authenticated `POST` requests so the capability is never placed in
  a URL. Responses are marked `no-store`; frames are held in memory and are not
  written to app, shared, or daemon storage.
- The bridge service is private (`exported=false`) and started by Overdrive
  itself. The BYD firmware blocks shell-to-app Unix sockets with SELinux, so the
  daemon reaches it on a loopback-only TCP port. Every request is signed with
  HMAC-SHA256 using Overdrive's existing device authentication secret and is
  rejected when its timestamp is stale or its random nonce has already been
  used. The bridge is not reachable from another device or network interface,
  and it creates no additional on-disk secret.
- Input is dispatched only to Overdrive-owned view roots in the app process.
  There is no `adb input`, accessibility injection, system-window capture, or
  interaction with `AccAnimation`, SurfaceFlinger, or BYD power services.

## Capture and input behavior

The last interactive Overdrive Activity window is captured with
`PixelCopy.request(activity.window, ...)`. App-owned dialog and popup roots are
then drawn over that frame on a best-effort basis. Pointer coordinates are
normalized to the captured window, and the page also exposes a small allowlist
of navigation keys plus focused-field text input.

The browser requests a 960-pixel-wide, quality-55 JPEG profile and starts the
next request 60 ms after a successful frame. Capture remains single-flight so
the car cannot accumulate stale work. The app retries short-lived PixelCopy
source/window races internally, while the browser keeps the last good frame
and only surfaces an error after repeated failures.

`DeterrentActivity` is intentionally not selected as the remote target. It can
continue running in the foreground to block physical input while its warning is
active, while Remote Dev View keeps capturing and dispatching directly to the
underlying interactive Overdrive Activity. The deterrent, its daemon-owned
surface, its deadline, and its power behavior are not disabled or modified.

This gives the remote session the same practical reach as a person interacting
with Overdrive locally, including screens that can issue vehicle commands.
Only use it while the vehicle is safely parked. It cannot capture or control
unrelated Android apps, OEM dialogs, system overlays, or the black ACC-off
cover itself.

The current JPEG polling transport is intentionally simple and bounded to one
in-flight frame (about three frames per second at up to 1280 pixels wide). A
future transport can replace it with WebSocket/WebRTC without changing the
app-process capture and input boundary.
