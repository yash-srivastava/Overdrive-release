# Remote Overdrive Dev View

Remote Overdrive Dev View captures and controls Overdrive's own Android window.
Its primary use is development while a BYD is ACC-off and the OEM's opaque
`AccAnimation` layer makes whole-display capture tools return black.

Open the authenticated web dashboard, choose **Remote Dev View**, and press
**Start session** beside the compact parked-use notice. Starting a session
launches Overdrive normally. It does not bypass the PIN screen and does not
turn on, uncover, or otherwise alter the physical head-unit display. The
viewer can be expanded and scaled to browser fullscreen; Back, Keyboard,
Screenshot, Refresh, End, and Exit remain available in a small overlay while
direct touch control continues on the scaled frame.

## Security boundary

- The page and every `/api/dev-view/*` request pass the normal dashboard JWT
  middleware.
- Session creation additionally requires explicit confirmation and returns a
  random, single-client capability kept only in browser memory.
- Capabilities expire after five minutes without activity and after eight hours
  regardless of activity. Starting another session invalidates the first.
- Live frames use an authenticated WebSocket. The normal dashboard JWT
  authenticates the upgrade; the developer capability is carried as a
  WebSocket subprotocol so it is never placed in a URL, browser history, or
  proxy request log. The authenticated `POST` frame endpoint remains as a
  `no-store` compatibility fallback. Frames stay in memory and are not written
  to app, shared, or daemon storage.
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
normalized to the captured window. The page forwards Escape as Android Back,
Backspace as Delete, Enter, Tab, arrow-key navigation, and printable keyboard
text. A compact Keyboard button focuses a hidden input so a phone or tablet can
open its soft keyboard without keeping a separate input-tools sidebar.

The app continuously produces a 960-pixel-wide, quality-55 JPEG stream while a
visible WebSocket client is attached. A capacity-one queue on both sides keeps
only the latest completed frame, so a slow tunnel or browser cannot accumulate
stale work. Capture remains single-flight, but input is handled by independent
bridge workers instead of waiting behind PixelCopy. The app retries short-lived
PixelCopy source/window races internally, while the browser keeps the last good
frame through transient failures instead of blanking it or moving the page. If
the stream has produced a good frame, later transient failure metadata is also
discarded so the successful dimensions and PixelCopy state stay visible. If
WebSocket setup fails, the browser falls back to sequential authenticated POST
requests. The Screenshot button performs a separate native-size, lossless PNG
capture and downloads it locally; it does not reduce the streaming frame rate
or persist a copy on the vehicle.

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

The transport intentionally streams JPEG rather than the composed Android
display: PixelCopy is still performed against Overdrive's Window before BYD's
opaque ACC-off layer is applied. WebSocket transport changes latency and frame
delivery only; it does not broaden the capture or input boundary.
