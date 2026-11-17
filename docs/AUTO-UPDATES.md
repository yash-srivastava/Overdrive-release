# Automatic updates (opt-in)

OverDrive deliberately does **not** install new releases automatically on
existing installations. Silent installs could overwrite a locally patched APK,
restart the camera daemon, or interrupt recording. Manual updates remain
available from Settings → About and the WebUI's update dialog.

**Install updates automatically** is an explicit opt-in in Settings → About and
in the WebUI update dialog. Both controls write `updates.autoInstall` to the
same daemon-backed unified configuration; the default is `false`. Changing the
release channel preserves this flag. **Alpha** is a selectable version archive,
so automatic installs apply only when the current channel is **Braveheart**
(the rolling release), not Alpha.

The running camera daemon checks after five minutes, then every six hours.
A check is skipped unless ACC is **positively admitted off**, surveillance is
both configured off and not armed, and panoramic/OEM recording is idle. Unknown
ACC state, an initializing camera, or pending ACC transitions fail closed. The
scheduler never keeps the daemon alive: in the on-only operating mode it may
stop at ACC off, so no automatic update occurs until there is another eligible
window while the daemon is running. Network failures retry on the next tick.

Before downloading, after downloading, and immediately before the irreversible
installer handoff, an independent guard rechecks ACC, recording activity, the
user's opt-in, channel and public-mode status. An ACC-on transition, a new
recording, switching to Alpha, disabling the switch or entering public mode
cancels/defer the automatic install. Existing manual installs, including Alpha
selection, are unaffected. The updater's shared install gate prevents automatic
and manual installs from racing.

**Risks:** An automatic install restarts OverDrive and replaces locally modified
builds. Only enable on a device where this is acceptable. The existing APK
update verification/signature path is reused; this change does not establish
reproducible release provenance or guarantee hardware behavior. The last
admission check cannot make an ACC state transition and a shell installer
handoff mathematically atomic. Test on a stationary vehicle before enabling.
