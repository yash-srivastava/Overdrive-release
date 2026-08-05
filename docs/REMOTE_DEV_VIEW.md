# Remote Overdrive Dev View

Remote Overdrive Dev View renders and controls a second Overdrive Android
activity on an app-owned private virtual display. Its primary use is
development while a BYD is ACC-off and the OEM's opaque `AccAnimation` layer
makes whole-display capture tools return black.

Open the authenticated web dashboard, choose **Remote Dev View**, and press
**Start session** beside the compact parked-use notice. Starting a session
launches a dedicated `RemoteMainActivity` instance with the real MainActivity
implementation and shared app data. It does not bypass the PIN screen and does
not turn on, uncover, or otherwise alter the physical head-unit display. The
viewer can be expanded and scaled to browser fullscreen; Back, Keyboard,
Screenshot, Refresh, End, and Exit remain available in a small overlay while
direct touch control continues on the scaled frame. Normal view presents the
window inside a responsive in-dash tablet bezel. The decorative bezel is
removed in fullscreen so the live window remains edge-to-edge.

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
- The remote Activity is placed on a private, own-content-only virtual display.
  Android restricts that display to Overdrive's UID; it is not public,
  auto-mirroring, secure-content capable, or visible to unrelated apps.
- The bridge service is private (`exported=false`) and started by Overdrive
  itself. The BYD firmware blocks shell-to-app Unix sockets with SELinux, so the
  daemon reaches it on a loopback-only TCP port. Every request is signed with
  HMAC-SHA256 using Overdrive's existing device authentication secret and is
  rejected when its timestamp is stale or its random nonce has already been
  used. The bridge is not reachable from another device or network interface,
  and it creates no additional on-disk secret.
- Input is dispatched only to Overdrive-owned view roots on the private display.
  There is no `adb input`, accessibility injection, display-stack capture, or
  interaction with `AccAnimation`, SurfaceFlinger services, or BYD power
  services.

## Capture and input behavior

The virtual display has the same 1920 x 1080 logical size and 240 dpi as the
head unit, while SurfaceFlinger scales its output directly into a 960 x 540
ImageReader surface. A bounded app-process encoder keeps the newest JPEG at a
10 fps target. Web clients read that cached frame; they do not initiate a
window screenshot or wait behind the UI thread. A successful input requests
the next available virtual-display frame immediately.

Pointer coordinates are normalized to the virtual Activity window. The page
forwards Escape as Android Back, Backspace as Delete, Enter, Tab, arrow-key
navigation, and printable keyboard text. A compact Keyboard button focuses a
hidden input so a phone or tablet can open its soft keyboard without keeping a
separate input-tools sidebar.

The capacity-one WebSocket queue keeps only the latest newly encoded frame, so
a slow tunnel or browser cannot accumulate stale work. The daemon polls the
app's cached frame at a bounded cadence and ignores duplicate sequence numbers.
The browser draws decoded JPEGs into a persistent canvas, leaving the last
frame intact throughout decoding and reconnects. A near-uniform black frame is
discarded after a visible frame unless the active remote Window is deliberately
`FLAG_SECURE`; secure state therefore never exposes an older unlocked image.
If WebSocket setup fails, the browser falls back to sequential authenticated
POST requests.

The Screenshot button is the only path that still uses `PixelCopy`: it performs
a separate native-size, lossless PNG capture of the virtual Activity Window and
downloads it locally without persisting a copy on the vehicle.

`DeterrentActivity` and `AccAnimation` remain on physical display stack 0. They
are not selected as remote targets and are not present on the private display.
Their surfaces, deadlines, input blocking, and power behavior are not disabled
or modified.

This gives the remote session the same practical reach as a person interacting
with Overdrive locally, including screens that can issue vehicle commands.
Only use it while the vehicle is safely parked. It cannot capture or control
unrelated Android apps, OEM dialogs, system overlays, or the black ACC-off
cover itself.

Ending or expiring a developer-view session sends an authenticated stop command
to the private app bridge, releases the ImageReader and virtual display, and
causes Android to remove its remote task. The physical MainActivity task and all
Overdrive configuration and app data remain untouched.
