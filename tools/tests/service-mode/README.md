# Service Mode review regression checks

These host-only checks cover two of the review's regressions without requiring a head unit:

- `node tools/tests/service-mode/run.cjs` exercises state rendering when the translation catalog loads after the UI, and again on a language switch. It also checks that the recovery hint is shown while package hiding is active.
- `python3 tools/tests/service-mode/guardian_test.py` extracts the *actual guardian shell template* from `ServiceModeManager.java` and tests that a second launcher exits when a live guardian owns its PID directory, cleanup releases the directory, and a stale PID directory can be recovered. It uses a fake `pm` and local temporary paths; it never changes installed packages.

Also validate JSON catalogs, the `app-shell.js?v=24` cache tag across pages, the `core.js?v=27` reference on `service-mode.html`, and the absence of `overflow-wrap: anywhere` in the Chrome 58 Service Mode page.

These checks **do not** verify Android daemon lifecycle, API authentication, actual package enable/disable behavior, or recovery across a real reboot or uninstall. Those still require on-car testing before release (see `docs/SERVICE_MODE.md`).
