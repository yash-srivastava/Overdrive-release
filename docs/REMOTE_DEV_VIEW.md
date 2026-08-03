# Remote Overdrive Dev View

Remote Overdrive Dev View captures and controls Overdrive's own Android window.
Its primary use is development while a BYD is ACC-off and the OEM's opaque
`AccAnimation` layer makes whole-display capture tools return black.

Open the authenticated web dashboard, choose **Diagnostics → Remote Dev View**,
read the warning, and explicitly start a session. Starting a session launches
Overdrive normally. It does not bypass the PIN screen and does not turn on,
uncover, or otherwise alter the physical head-unit display.

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
- The shell daemon reaches the Android app process through an exported service
  protected by the signature-level `android.permission.DUMP` permission. The
  service also requires a daemon-generated in-memory secret and listens only on
  `127.0.0.1:19881`.
- Input is dispatched only to Overdrive-owned view roots in the app process.
  There is no `adb input`, accessibility injection, system-window capture, or
  interaction with `AccAnimation`, SurfaceFlinger, or BYD power services.

## Capture and input behavior

The active Overdrive Activity window is captured with
`PixelCopy.request(activity.window, ...)`. App-owned dialog and popup roots are
then drawn over that frame on a best-effort basis. Pointer coordinates are
normalized to the captured window, and the page also exposes a small allowlist
of navigation keys plus focused-field text input.

This gives the remote session the same practical reach as a person interacting
with Overdrive locally, including screens that can issue vehicle commands.
Only use it while the vehicle is safely parked. It cannot capture or control
unrelated Android apps, OEM dialogs, system overlays, or the black ACC-off
cover itself.

The current JPEG polling transport is intentionally simple and bounded to one
in-flight frame (about three frames per second at up to 1280 pixels wide). A
future transport can replace it with WebSocket/WebRTC without changing the
app-process capture and input boundary.
