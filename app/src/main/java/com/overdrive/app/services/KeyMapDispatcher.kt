package com.overdrive.app.services

import android.os.FileObserver
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.overdrive.app.util.ScratchPaths

/**
 * App-process brain for physical-key mappings.
 *
 * [KeepAliveAccessibilityService.onKeyEvent] hands us every hardware KeyEvent it
 * is allowed to filter. We look the keycode up in the user's bindings and, if one
 * matches, fire its action against the daemon and tell the service to CONSUME the
 * key (so the OEM default doesn't also run). Unmapped keys — or every key when the
 * feature is disabled — are ignored here and pass through untouched.
 *
 * ── Never block the input thread ──────────────────────────────────────────────
 * [onKey] runs on the platform input-dispatch thread; blocking it risks a
 * system-UI ANR. So it touches NO disk and NO network: it reads an in-memory
 * [Snapshot] (a volatile ref) and returns. The snapshot is refreshed on a
 * background thread — eagerly at [warmUp] and then throttled from onKey — so a
 * binding edited on the phone takes effect within a couple of seconds without any
 * per-key I/O. If the snapshot hasn't loaded yet, keys simply pass through.
 *
 * Actuation is also off-thread: BYD SDK writes only clear the signature-permission
 * wall from the daemon (UID 2000), so the bound action is POSTed to
 * {@code /api/keymap/fire} via [DaemonHttpClient] (JWT-authenticated) on a pooled
 * executor and {@link com.overdrive.app.server.KeymapApiHandler} runs it.
 *
 * ── Press-type matching ───────────────────────────────────────────────────────
 *  - "single": fires on a DOWN we decide is not part of a double-press.
 *  - "double": two DOWNs of the same keycode within [DOUBLE_WINDOW_MS].
 *  - "long":   a DOWN whose repeat count crosses the platform long-press.
 *
 * A keycode may carry a "single" alongside a "double" and/or a "long" binding.
 * Whenever the single co-exists with either of those we defer it (via the
 * [scheduler], NOT a sleep, so no thread is held) so a second press can upgrade it
 * to the double action, or a held-past-timeout repeat can upgrade it to the long
 * action — whichever fires first cancels the pending single. We wait
 * [DOUBLE_WINDOW_MS] when only a double co-exists, but [LONG_WINDOW_MS] (longer
 * than the platform long-press timeout) when a long co-exists, so the long-press
 * repeat reliably cancels the single before it fires. When only a single binding
 * exists there is nothing to disambiguate, so it fires immediately.
 */
object KeyMapDispatcher {

    private const val TAG = "KeyMapDispatcher"
    private const val NATIVE_CAMERA_PACKAGE = "com.byd.avc"
    private const val NATIVE_CAMERA_ACTION_KEY = "native_camera_view"
    // Disambiguation window for single-vs-double, measured from the FIRST DOWN of the
    // first tap. A deliberate hardware double-tap on a steering-wheel/dash button spans
    // longer than a touchscreen double-tap (Android's 300ms default): each press is a
    // firmware BURST delivered through a laggy a11y input path, so tap 1's burst + hold
    // already consumes part of the window before the user releases. At 300ms a second tap
    // whose DOWN landed just past 300ms was missed — the parked single had already fired,
    // so "double often did the single instead" (field report). 450ms comfortably covers a
    // real inter-tap interval measured from the first DOWN. Trade-off: a single tap on a
    // key that ALSO has a double mapped waits this long before firing (we can't fire the
    // single early and un-fire it for a vehicle action) — acceptable, since the whole
    // point of mapping a double is that it must win. This is the DEFAULT; the live value
    // is user-tunable (keymap.doubleTapWindowMs, clamped 250..1500ms) and carried on the
    // snapshot as doubleWindowMs, so a user whose buttons need a slower double can raise it
    // (e.g. 1000ms) from Settings without a rebuild.
    private const val DOUBLE_WINDOW_MS = 450L
    // Debounce for a fired action, per keycode. Many BYD wheel/dash buttons emit
    // a BURST of discrete down/up pairs (each repeatCount==0) for a single human
    // press rather than one down + auto-repeat — so firing on every fresh DOWN
    // spams the action (and floods the daemon so none completes cleanly). After a
    // fire we ignore further DOWNs of that keycode for this window. 400ms is long
    // enough to swallow a mechanical bounce/burst but short enough that a genuine
    // deliberate re-press still registers.
    private const val FIRE_DEBOUNCE_MS = 400L
    // Burst-coalesce window, per keycode. A single BYD wheel/dash press arrives as
    // a BURST of discrete repeatCount==0 DOWNs (firmware fact), NOT one down +
    // auto-repeat. Two DOWNs of the same keycode closer together than this are the
    // same physical press's burst, NOT a deliberate double-tap, so the second must
    // be swallowed rather than promoted to the "double" action. This MUST stay well
    // under DOUBLE_WINDOW_MS (450ms) so a genuinely deliberate double-press — which
    // by definition lands within DOUBLE_WINDOW_MS but is spaced by human reaction
    // time (>>100ms) — still promotes. ~90ms comfortably clears the mechanical burst
    // span yet sits far below a real inter-tap gap. Distinct from FIRE_DEBOUNCE_MS
    // (400ms), which cannot be reused here: 400ms > 300ms would swallow real doubles.
    private const val BURST_COALESCE_MS = 90L
    // When a "long" binding co-exists with a "single" on the same keycode we must
    // defer the single until we know the hold didn't cross the platform long-press
    // timeout (~400-500 ms, longer than DOUBLE_WINDOW_MS). We park the single for
    // this longer window so the long-press repeat — which cancels the pending
    // single — reliably arrives first and wins the race.
    private const val LONG_WINDOW_MS = 600L
    // How long an inject-guard entry stays valid (see injectGuard). The injected native
    // tap round-trips through onKeyEvent within a few ms; 1s is a generous ceiling after
    // which a never-delivered injection is treated as absent so the button isn't left dead.
    private const val INJECT_GUARD_TTL_MS = 1000L
    // Don't re-read config from disk more often than this, regardless of key rate.
    private const val REFRESH_THROTTLE_MS = 2000L
    // Much coarser poll used ONLY while the feature is disabled: it exists solely
    // to recover the OFF→ON edit on platforms where the FileObserver is unreliable,
    // so it can be rare. A disabled dispatcher therefore does at most one config
    // reparse per this interval (and only while hardware buttons are being pressed)
    // instead of one per REFRESH_THROTTLE_MS — the zero-overhead-when-disabled bar.
    private const val REENABLE_POLL_MS = 30_000L
    // Unified config file — watched for instant propagation of settings edits.
    private val CONFIG_PATH = ScratchPaths.path("overdrive_config.json")

    // Pooled I/O executor for daemon POSTs and config refreshes. Cached (not
    // single-thread) so one slow/hung POST never blocks either the timing
    // scheduler or a subsequent refresh. Daemon threads so we never hold up exit.
    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "KeyMapIO").apply { isDaemon = true }
    }
    // Dedicated scheduler for the deferred single/double disambiguation —
    // schedule(), never sleep(), so waiting out the window holds no thread and
    // cannot delay other keys' actuation.
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "KeyMapSched").apply { isDaemon = true }
    }

    /** Immutable in-memory view of the keymap config, read on the input thread.
     *  doubleWindowMs is the user-tunable single-vs-double disambiguation window
     *  (falls back to DOUBLE_WINDOW_MS when unset), captured per-snapshot so a live
     *  edit propagates on the next refresh without a restart. */
    private class Snapshot(val enabled: Boolean, val bindings: List<JSONObject>, val doubleWindowMs: Long)

    @Volatile private var cache: Snapshot? = null
    @Volatile private var foregroundPackage: String? = null
    @Volatile private var lastRefreshKickMs = 0L
    // Separate timestamp for the coarse re-enable poll (see maybeRefreshSlow), so
    // the disabled-state backstop throttles independently of the enabled hot path.
    @Volatile private var lastReenableKickMs = 0L

    // Pending single-press actions awaiting the disambiguation window, keyed by
    // keycode. Guarded by `this`. A scheduled task fires the single unless it is
    // removed first — either by a second DOWN (promoted to double) or by a
    // long-press repeat (promoted to long).
    private val pending = HashMap<Int, JSONObject>()
    // A monotonically-increasing epoch per arming, keyed by keycode. Guarded by
    // `this`. Every time we park a keycode we bump [epochSeq] and stamp its value
    // here; the scheduled task captures its own epoch and only acts if this map
    // still holds it. The binding JSONObject itself is a reference-stable slot in
    // the cached snapshot, so it CANNOT be used as the pending token: a stale timer
    // left over from an already-resolved gesture would match a later re-arming of
    // the SAME binding object and early-fire/misattribute it. The epoch is unique
    // per arming, so any re-arm bumps it and orphaned timers correctly no-op.
    private val pendingEpoch = HashMap<Int, Long>()
    private var epochSeq = 0L

    // A keycode whose "double" fired on the second DOWN, so its "long" must NOT
    // also fire if that same second press is then held past the long-press timeout:
    // one gesture must actuate exactly one command. Guarded by `this`; cleared on
    // the trailing UP so a fresh gesture re-enables the long.
    private val suppressLongUntilUp = HashSet<Int>()

    // Per-keycode timestamp of the last action fired, for FIRE_DEBOUNCE_MS. Guards
    // against a single human press that the firmware delivers as a burst of
    // discrete DOWNs firing the action repeatedly. Guarded by `this`.
    private val lastFiredAtMs = HashMap<Int, Long>()

    // Per-keycode timestamp of the last fresh (repeatCount==0) DOWN, for
    // BURST_COALESCE_MS. Lets the double-promote distinguish a same-press burst
    // DOWN (arriving a few ms after the prior DOWN of the same keycode) from a
    // deliberate second tap: only the latter promotes to "double". Guarded by
    // `this`; cleared alongside pending/pendingEpoch in refresh()/teardown() so a
    // stale gap can't leak across gestures or survive a config swap.
    private val lastDownAtMs = HashMap<Int, Long>()

    // Per-keycode flag: an UP for this keycode has been observed since the DOWN
    // that last parked a single/double. A genuine double-tap is two COMPLETE
    // down-up cycles, so a later DOWN may only promote to "double" if an
    // intervening UP has been seen — a firmware burst delivers back-to-back DOWNs
    // with no completed UP between them and so can never be promoted, regardless of
    // how laggy the input path stretches the inter-DOWN gap. Set in the UP handler
    // at the top of onKey; cleared when a fresh DOWN parks the single (or the
    // double-only) so the NEXT tap must itself complete a cycle. Guarded by `this`;
    // cleared alongside pending/pendingEpoch/lastDownAtMs in refresh()/teardown().
    private val upSeenSinceDown = HashSet<Int>()

    // Per-keycode flag: the most recent DOWN for this keycode was PASSED THROUGH to the
    // OEM (not consumed) — used so the trailing UP is handled symmetrically. When a
    // keycode has ONLY a double/long binding (no "single"), a plain single tap must fall
    // through to the OEM's own function for that button (e.g. the 360-camera button whose
    // double-tap is remapped but whose single tap should still open the 360 view). We let
    // the DOWN pass; this flag then lets the matching UP pass too, so the OEM sees a
    // complete down-up pair rather than a dangling UP. Cleared when the DOWN is consumed.
    // Guarded by `this`; cleared alongside the other transient maps in refresh()/teardown().
    private val downPassedThrough = HashSet<Int>()

    // A camera-view mapping that was ineligible because the native panorama app
    // was not active must let the complete firmware key burst reach the OEM. The
    // first passed-through DOWN may itself open that app; without this short grace
    // period, a follow-on DOWN from the SAME physical press could see the newly
    // active package and fire the mapped view. Guarded by `this`.
    private val nativeCameraPassThroughUntilMs = HashMap<Int, Long>()
    private val nativeCameraDefaultGesture = HashSet<Int>()

    // Keycodes for which we injected a synthetic native tap (the "block native single"
    // replay — see the double-only branch) and are waiting for that injected event to
    // round-trip back through onKeyEvent. FLAG_REQUEST_FILTER_KEY_EVENTS re-delivers
    // `input keyevent`-injected keys to our filter, so without this guard the replay
    // would be re-captured, re-armed as a double, and re-injected — an infinite loop.
    // A keycode here makes the NEXT down+up pair pass straight through to the OEM
    // (consuming the guard), so exactly one injected tap reaches the OEM and is not
    // re-processed. Guarded by `this`; time-boxed by injectGuardUntilMs so a mispaired
    // event (injection that never round-trips) can't strand the guard and swallow a
    // later real gesture. Cleared in refresh()/teardown() with the other transient maps.
    private val injectGuard = HashSet<Int>()
    private val injectGuardUntilMs = HashMap<Int, Long>()

    // Instant-propagation watcher. The keymap config is written by the DAEMON
    // (UID 2000) when the settings page POSTs /api/keymap/config, but this
    // dispatcher lives in the APP process — so the in-process ConfigChangeListener
    // never fires here. A FileObserver on the config file's directory is the
    // cross-process signal: the moment the daemon's atomic rename lands, we
    // refresh immediately instead of waiting out the throttled poll. The poll in
    // [maybeRefresh] stays as a backstop for platforms where FileObserver on
    // /data/local/tmp is unreliable.
    @Volatile private var watcher: FileObserver? = null

    /**
     * Prime the in-memory snapshot off the input thread. Call once when the
     * accessibility service connects.
     *
     * We do NOT register the instant-propagation FileObserver here: doing so
     * unconditionally would put an inotify watch on the busy /data/local/tmp dir
     * whose observer thread is woken by the ~44 unrelated subsystems that rewrite
     * overdrive_config.json at heartbeat rate — even on the majority of installs
     * where keymap.enabled=false. Instead the watcher is started lazily from
     * [refresh] the first time the snapshot is observed enabled (and stopped when
     * it flips back to disabled), so a disabled feature holds no watch descriptor
     * and causes no observer-thread wakes. The eager submit below reaches
     * [refresh] on connect, so an already-enabled feature starts watching at once;
     * an OFF→ON edit made while the service is already connected is recovered by
     * the coarse [maybeRefreshSlow] poll (kicked from onKey while disabled), which
     * flips the snapshot enabled and thereby starts the watcher.
     */
    fun warmUp() {
        io.submit { refresh() }
    }

    /**
     * Update the package owning the active accessibility window. This is called
     * from TYPE_WINDOW_STATE_CHANGED events and is deliberately just a volatile
     * assignment so key filtering never performs an ActivityManager/Binder query.
     */
    fun onForegroundPackageChanged(packageName: String?) {
        foregroundPackage = packageName?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Camera-view actions are eligible only while the OEM panorama application is
     * active. Sequences inherit the restriction so wrapping the action cannot make
     * an otherwise conditional binding consume the vehicle key.
     */
    @JvmStatic
    fun isBindingEligibleForForeground(
        binding: JSONObject,
        activePackage: String?
    ): Boolean {
        val action = binding.optJSONObject("action") ?: return true
        return !containsNativeCameraView(action) || activePackage == NATIVE_CAMERA_PACKAGE
    }

    private fun containsNativeCameraView(action: JSONObject): Boolean {
        if (action.optString("kind") == "catalog" &&
            action.optString("key") == NATIVE_CAMERA_ACTION_KEY) {
            return true
        }
        if (action.optString("kind") != "sequence") return false
        val steps: JSONArray = action.optJSONArray("steps") ?: return false
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (containsNativeCameraView(step)) return true
        }
        return false
    }

    /** Register a FileObserver on the config dir so a daemon write refreshes us
     *  at once. Called lazily from [refresh] when the snapshot is first observed
     *  enabled, so a disabled feature registers no inotify watch at all.
     *  Idempotent and safe to call on every enabled refresh: the check-and-publish
     *  of the `watcher` slot is done under the `this` monitor (same as [refresh]'s
     *  own critical section), so two overlapping io-pool refreshes can't both build
     *  and leak a second observer. Failures fall back to the throttled poll. */
    private fun startWatching() {
        // Fast idempotency check outside the monitor; the authoritative check is
        // the guarded claim below (an observer is only ever built once).
        if (watcher != null) return
        try {
            val cfg = File(CONFIG_PATH)
            val dir = cfg.parentFile ?: return
            val name = cfg.name
            val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
            // Directory-scoped observer: an atomic rename replaces the inode, so
            // watching the file directly would go deaf after the first write.
            // Watch the dir and filter by filename. @Suppress: the deprecated
            // String ctor is the only form on API 29 (our runtime floor).
            @Suppress("DEPRECATION")
            val obs = object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path != name) return
                    // Zero-overhead-when-disabled gate. overdrive_config.json is
                    // rewritten by ~44 unrelated subsystems (RoadSense sync, camera
                    // lease heartbeat, overlay heartbeat, …), so an event here does
                    // NOT imply a keymap change. If our last-known snapshot says the
                    // feature is off, skip the forceReload (whole-file read + JSON
                    // parse + migration walk that also transiently nulls the shared
                    // process-wide config cache) and re-learn nothing. Cheap volatile
                    // read, no allocation — matches RoadSenseController's enabled-flip
                    // gate. Gate ONLY on a KNOWN-disabled snapshot: a not-yet-loaded
                    // (null) snapshot must still refresh once so first load works, and
                    // the OFF→ON edit is recovered by the coarse maybeRefreshSlow()
                    // poll (kicked from onKey while disabled).
                    if (cache?.enabled == false) return
                    // Never do work on the observer thread, and never reparse per
                    // event: overdrive_config.json is rewritten at heartbeat rate by
                    // the ~44 unrelated subsystems named above, so an unthrottled
                    // refresh() here would forceReload() (whole-file read + JSON parse
                    // + migration walk that transiently nulls the shared process-wide
                    // config cache) once per heartbeat write even when the keymap
                    // section never changed. Route through the SAME coalescing as the
                    // onKey poll so an event burst collapses to at most one reparse per
                    // REFRESH_THROTTLE_MS. maybeRefresh() itself submits to the io pool,
                    // so this stays off the observer thread.
                    maybeRefresh()
                }
            }
            obs.startWatching()
            // Publish the started observer into the slot under the monitor so a
            // concurrent refresh that also passed the fast check above can't leave a
            // second live observer behind. If we lost the race (another thread
            // already published one), stop the duplicate we just started so its
            // inotify watch is released rather than leaked.
            val won = synchronized(this) {
                if (watcher != null) false else { watcher = obs; true }
            }
            if (!won) {
                try { obs.stopWatching() } catch (ignored: Throwable) {}
                return
            }
            Log.i(TAG, "keymap config watcher started on ${dir.absolutePath}")
        } catch (t: Throwable) {
            // Non-fatal: the throttled poll still delivers changes within ~2s.
            Log.w(TAG, "keymap config watcher failed (falling back to poll): ${t.message}")
        }
    }

    /** Tear down the FileObserver so a disabled feature holds no inotify watch and
     *  its observer thread stops being woken by unrelated /data/local/tmp writes.
     *  Called from [refresh] on the enabled→disabled edge; idempotent (no-op when
     *  already stopped). Once stopped, the coarse [maybeRefreshSlow] poll remains
     *  the OFF→ON recovery path, and re-enabling calls [startWatching] again. */
    private fun stopWatching() {
        // Take the slot atomically under the same monitor [startWatching] publishes
        // under, so a concurrent enable/disable pair can't null a watcher another
        // thread is mid-publish of. Stop outside the lock (no allocation held).
        val obs = synchronized(this) {
            val w = watcher
            watcher = null
            w
        } ?: return
        try {
            obs.stopWatching()
        } catch (t: Throwable) {
            Log.w(TAG, "keymap config watcher stop failed: ${t.message}")
        }
    }

    /**
     * Returns true if the key was mapped and should be CONSUMED (not passed to
     * the OEM handler). Called from the a11y input-filter thread — reads only the
     * volatile snapshot and schedules work elsewhere; never blocks.
     *
     * @param keyCode     the KeyEvent keycode
     * @param isDown      true for ACTION_DOWN, false for ACTION_UP
     * @param repeatCount KeyEvent.getRepeatCount() (>0 ⇒ held → long-press)
     */
    fun onKey(keyCode: Int, isDown: Boolean, repeatCount: Int): Boolean {
        // Capture mode: the Key Mapping "press a button to capture it" box (in the
        // WebView) can't see hardware keys — they arrive HERE, not as WebView DOM
        // keydown. While the page has armed capture, forward the keycode into the
        // page and CONSUME the event (both down and up) so arming a capture never
        // also fires the OEM action or a stale mapping. One forward per DOWN.
        try {
            if (com.overdrive.app.ui.fragment.WebViewFragment.captureArmed) {
                if (isDown && repeatCount == 0) {
                    com.overdrive.app.ui.fragment.WebViewFragment.onCapturedKey(keyCode)
                }
                return true
            }
        } catch (_: Throwable) { /* capture bridge unavailable — fall through */ }

        // Clear this keycode's long-suppression on ANY UP, BEFORE the enabled/owned
        // gates below can short-circuit. suppressLongUntilUp is set on a double-
        // promote (when a long co-exists) and is otherwise removed only on the
        // owned+enabled UP — but if the config is rewritten to disable keymap or
        // remove this keycode (feature-A FileObserver push or the poll), or the a11y
        // service is torn down, BETWEEN the promoting DOWN and its UP, that removal
        // would be skipped and the stale entry would silently no-op the NEXT genuine
        // long-press. Clearing here (idempotent, cheap) makes the set impossible to
        // strand by a mid-gesture config swap. The consume-vs-passthrough decision
        // is still made below from `owned`.
        if (!isDown) synchronized(this) { suppressLongUntilUp.remove(keyCode); upSeenSinceDown.add(keyCode) }

        // Inject-guard: if we just injected a synthetic native tap for this keycode
        // (the "block native single" replay), let its round-tripped event pass STRAIGHT
        // through to the OEM without any mapping logic — otherwise FLAG_REQUEST_FILTER_
        // KEY_EVENTS would re-deliver our own injection, we'd re-arm the double, and
        // re-inject forever. One guarded down+up pair is consumed per injection (the UP
        // clears the guard so both halves pass, then the guard is gone for the next real
        // gesture). Time-boxed so an injection that never round-trips can't
        // strand the guard. Checked BEFORE the enabled/owned gates because the replay must
        // pass even while the feature is enabled and owns the keycode.
        run {
            val passThrough = synchronized(this) {
                if (injectGuard.contains(keyCode)) {
                    val until = injectGuardUntilMs[keyCode] ?: 0L
                    if (System.currentTimeMillis() <= until) {
                        // Pass the whole injected down+up pair. Clear on the UP (not the
                        // DOWN) so BOTH halves pass — clearing on DOWN would leave the
                        // paired UP to be consumed as an owned key, handing the OEM a
                        // dangling DOWN. After the UP the guard is gone for the next gesture.
                        if (!isDown) { injectGuard.remove(keyCode); injectGuardUntilMs.remove(keyCode) }
                        true
                    } else {
                        // Expired (injection never came back) — drop it and process normally.
                        injectGuard.remove(keyCode); injectGuardUntilMs.remove(keyCode)
                        false
                    }
                } else false
            }
            if (passThrough) return false
        }

        val snap = cache
        // Not loaded yet → prime once (this is the FIRST-load path, so the observer
        // gate that skips known-disabled snapshots cannot help here) and pass through.
        if (snap == null) { maybeRefresh(); return false }
        // Feature disabled → do NO full reparse from key activity. A disabled
        // dispatcher must be zero-overhead: instead of the 2s maybeRefresh() poll
        // (which forceReloads + nulls the process-wide config cache every ~2s under
        // key spam), kick only the coarse re-enable backstop, which the FileObserver
        // normally beats to the OFF→ON edit anyway. Pass through untouched. We do
        // NOT scan bindings here, but we DID already clear suppressLongUntilUp above,
        // so a disable landing mid-gesture cannot strand it.
        if (!snap.enabled) { maybeRefreshSlow(); return false }
        // Enabled hot path — keep the ~2s freshness so a live binding edit lands fast.
        maybeRefresh() // async, throttled — never touches disk on this thread

        // If an ineligible camera binding already passed a DOWN to the OEM, keep
        // passing the remainder of that firmware burst even if the first DOWN
        // opened the camera app and changed the foreground package meanwhile.
        val nowMs = System.currentTimeMillis()
        val nativeCameraBurstPassThrough = synchronized(this) {
            if (nativeCameraDefaultGesture.contains(keyCode)) {
                if (!isDown) {
                    nativeCameraDefaultGesture.remove(keyCode)
                    downPassedThrough.remove(keyCode)
                }
                return@synchronized true
            }
            val until = nativeCameraPassThroughUntilMs[keyCode]
            if (until != null && nowMs <= until) {
                if (isDown) {
                    nativeCameraDefaultGesture.add(keyCode)
                    downPassedThrough.add(keyCode)
                } else {
                    downPassedThrough.remove(keyCode)
                }
                true
            } else {
                if (until != null) nativeCameraPassThroughUntilMs.remove(keyCode)
                false
            }
        }
        if (nativeCameraBurstPassThrough) return false

        // Cheap in-memory filter — no I/O.
        var singleB: JSONObject? = null
        var doubleB: JSONObject? = null
        var longB: JSONObject? = null
        var owned = false
        var conditionalCameraBinding = false
        val activePackage = foregroundPackage
        for (b in snap.bindings) {
            if (b.optInt("keycode", -1) != keyCode) continue
            if (!b.optBoolean("enabled", true)) continue
            if (!isBindingEligibleForForeground(b, activePackage)) {
                conditionalCameraBinding = true
                continue
            }
            owned = true
            when (b.optString("pressType")) {
                "double" -> doubleB = b
                "long" -> longB = b
                else -> singleB = b
            }
        }
        if (!owned) {
            if (!conditionalCameraBinding) return false
            if (!isDown) {
                // If the matching DOWN was consumed while the camera was active,
                // consume its UP too. If it was passed through, preserve the pair.
                val passed = synchronized(this) { downPassedThrough.remove(keyCode) }
                return !passed
            }
            if (repeatCount > 0) {
                // Preserve the decision made for the initial DOWN of a held key.
                val passed = synchronized(this) { downPassedThrough.contains(keyCode) }
                return !passed
            }
            synchronized(this) {
                nativeCameraDefaultGesture.add(keyCode)
                downPassedThrough.add(keyCode)
                nativeCameraPassThroughUntilMs[keyCode] =
                    System.currentTimeMillis() + FIRE_DEBOUNCE_MS
            }
            return false
        }

        // Trailing UP handling. Normally we consume the UP for an owned key so the OEM
        // never sees a dangling UP for a DOWN we swallowed. BUT if the matching DOWN was
        // PASSED THROUGH (a single tap on a double/long-only binding — see the branches
        // below), the OEM already got that DOWN and must get the UP too, or its button
        // handler sees a stuck key. So pass the UP through iff we passed its DOWN through.
        // (The UP's suppressLongUntilUp clear already ran unconditionally at the top.)
        if (!isDown) {
            val passed = synchronized(this) { downPassedThrough.remove(keyCode) }
            return !passed
        }

        // Long-press: platform sets repeatCount>0 once held past the timeout.
        // Fire once on the first repeat and swallow the rest of the stream.
        if (repeatCount > 0) {
            if (longB != null) {
                // Cancel any single we parked for this keycode: the hold crossed
                // the long-press timeout, so this gesture is a long-press, not a
                // tap. Clearing the arming (both `pending` and its epoch) before
                // firing means the deferred single (see below) sees its epoch gone
                // and no-ops — one gesture, one command.
                val suppressed = synchronized(this) {
                    pending.remove(keyCode); pendingEpoch.remove(keyCode)
                    suppressLongUntilUp.contains(keyCode)   // double already consumed this held press
                }
                if (repeatCount == 1 && !suppressed) fire(longB)
                return true   // still consume the whole held stream
            }
            // Held key with no long binding but we own single/double: swallow
            // repeats so the OEM doesn't get a partial stream.
            return singleB != null || doubleB != null
        }

        // Fresh DOWN (repeatCount == 0).
        synchronized(this) {
            // Distinguish a same-press burst DOWN from a deliberate second tap BEFORE
            // any single/double decision. A single BYD press arrives as a burst of
            // discrete repeatCount==0 DOWNs (firmware fact), so a DOWN arriving within
            // BURST_COALESCE_MS of the previous DOWN of THIS keycode is a burst repeat,
            // not a second press. We record the current DOWN time and remember whether
            // this one is inside the burst window; a burst must NOT promote a parked
            // single to double (that would fire the double action on a single tap and
            // eat the single) — it is swallowed with the arming left intact so the
            // parked single still resolves normally.
            val now = System.currentTimeMillis()
            val prevDown = lastDownAtMs[keyCode]
            val burst = prevDown != null && (now - prevDown) < BURST_COALESCE_MS

            if (burst) {
                // Mechanical burst DOWN for the same physical press. Do NOT touch the
                // parked single/epoch, do NOT promote to double, do NOT re-fire, and
                // — critically — do NOT advance lastDownAtMs: leaving the reference on
                // the FIRST DOWN of this cluster means the burst window measures the
                // gap from the START of the press, so a genuine deliberate double-tap
                // landing just past the burst's tail is measured from the first tap
                // (>>90ms, matching the "much later than the FIRST tap" invariant) and
                // still promotes, instead of being swallowed because a mid-burst DOWN
                // slid the window forward. Just consume it so the OEM never sees a
                // partial stream. The original arming's timer (if any) still resolves;
                // an immediate single (double-less key) already fired on the first
                // DOWN and stays debounced.
                return true
            }
            // Real cluster-starting (or post-window) DOWN — re-anchor the burst
            // reference here. Burst follow-on DOWNs above never reach this, so the
            // reference stays pinned to the first DOWN of the current press cluster.
            lastDownAtMs[keyCode] = now

            // Consume any prior arming for this keycode: whatever we do below is a
            // fresh decision, so the previous arming's timer must no-op. Clearing
            // its epoch (not just `pending`) is what makes the orphaned timer bail —
            // the timer matches on the epoch, and re-arming below bumps epochSeq.
            val waiting = pending.remove(keyCode)
            pendingEpoch.remove(keyCode)
            // Promote to double ONLY on a genuine second tap: a completed press CYCLE,
            // proven by an intervening UP (upSeenSinceDown) between the parking DOWN
            // and this one. This replaces the correctness dependence on the exact
            // BURST_COALESCE_MS gap (a laggy input path can stretch a mechanical
            // burst's inter-DOWN gap past 90ms, which would otherwise be misread as a
            // deliberate double and fire the double on a single tap). A firmware burst
            // delivers back-to-back DOWNs with no completed UP between them, so it can
            // never satisfy this gate regardless of spacing; a real double-tap (two
            // full down-up cycles inside DOUBLE_WINDOW_MS) still does. When the gate is
            // NOT met (no UP seen), we do NOT fire the double and do NOT leave the
            // arming dropped — we fall through to re-park the single (or, for a
            // double-only key, re-arm the double) below, so the intended action still
            // resolves; the 90ms burst-coalesce above stays as the fast path.
            if (waiting != null && doubleB != null && upSeenSinceDown.contains(keyCode)) {
                // Genuine second press: a full down-up cycle completed and this DOWN
                // landed inside the window (burst gap >= BURST_COALESCE_MS, checked
                // above) → promote to double, cancel single. If this DOWN is then held
                // into a long-press, suppress that long: one gesture must actuate
                // exactly one command. Cleared on the trailing UP.
                if (longB != null) suppressLongUntilUp.add(keyCode)
                // We CONSUME this promoting DOWN, so its trailing UP must also be consumed
                // — clear any passed-through flag left by the first (passed-to-OEM) DOWN of
                // this double, or the promote's UP would wrongly leak to the OEM.
                downPassedThrough.remove(keyCode)
                // Burst DOWNs were already swallowed above (BURST_COALESCE_MS), so this is a
                // genuine deliberate second press — fire it even if it lands inside the
                // single-press debounce window. Still stamp lastFiredAtMs so any co-existing
                // deferred single sees the double and no-ops.
                lastFiredAtMs[keyCode] = now
                fire(doubleB)
                return true
            }

            if (singleB != null && (doubleB != null || longB != null)) {
                // Ambiguous: the single shares this keycode with a double and/or a
                // long, so we can't fire it on this DOWN. Park it and let either a
                // second press (→ double, above) or a held-past-timeout repeat
                // (→ long, cleared in the repeat>0 branch) consume it first. If the
                // window elapses with neither, the scheduled task fires the single.
                //
                // With a long binding present we wait at least LONG_WINDOW_MS (> the
                // platform long-press timeout) so the long-press repeat reliably
                // clears `pending` before this fires; otherwise the (user-tunable)
                // double window is enough to disambiguate a double. Never park the
                // single for LESS than the double window even when a long co-exists —
                // a custom double window larger than LONG_WINDOW_MS must still let the
                // second tap land before the single resolves.
                val s = singleB
                val dbl = snap.doubleWindowMs
                val window = if (longB != null) maxOf(LONG_WINDOW_MS, dbl) else dbl
                val myEpoch = ++epochSeq
                pending[keyCode] = s
                pendingEpoch[keyCode] = myEpoch
                // Fresh parking DOWN: reset the completed-cycle flag so the NEXT DOWN
                // must itself see an intervening UP before it can promote to double.
                upSeenSinceDown.remove(keyCode)
                scheduler.schedule({
                    val fireIt: JSONObject?
                    synchronized(this) {
                        // Only fire if THIS arming is still pending (a second press
                        // would have removed it and fired double; a long-press
                        // repeat would have removed it and fired long). We match on
                        // the per-arming epoch, not the binding reference, so a stale
                        // timer from a resolved gesture can't fire a later re-arm of
                        // the same binding object.
                        if (pendingEpoch[keyCode] == myEpoch) {
                            pending.remove(keyCode); pendingEpoch.remove(keyCode)
                            // Re-gate against the LIVE snapshot: refresh() can swap
                            // `cache` during the disambiguation window without touching
                            // pending/pendingEpoch, and the daemon fire path does not
                            // re-check enabled for catalog/vehicle kinds. So only fire
                            // if the feature is still enabled AND a matching enabled
                            // single binding for this keycode still exists — otherwise
                            // a disabled/deleted binding would still actuate.
                            val snapNow = cache
                            val stillValid = snapNow != null && snapNow.enabled &&
                                snapNow.bindings.any {
                                    it.optInt("keycode", -1) == keyCode &&
                                        it.optBoolean("enabled", true) &&
                                        it.optString("pressType") != "double" &&
                                        it.optString("pressType") != "long" &&
                                        isBindingEligibleForForeground(it, foregroundPackage)
                                }
                            // Honor FIRE_DEBOUNCE_MS exactly like every other fire site
                            // (the two immediate branches above). Belt-and-suspenders:
                            // even though the burst-coalesce short-circuit now stops a
                            // same-press burst from promoting to double and re-parking
                            // the single, this guard guarantees the deferred single can
                            // NEVER double-actuate — if a double already fired for this
                            // keycode within the window (which stamped lastFiredAtMs),
                            // the re-armed single sees the recent stamp and no-ops. The
                            // stamp-and-decide is kept atomic under `this`, so the fire
                            // outside the monitor can't race a concurrent fire site.
                            val fireNow = System.currentTimeMillis()
                            val last = lastFiredAtMs[keyCode]
                            fireIt = if (stillValid &&
                                (last == null || fireNow - last >= FIRE_DEBOUNCE_MS)) {
                                lastFiredAtMs[keyCode] = fireNow
                                s
                            } else null
                        } else {
                            fireIt = null
                        }
                    }
                    if (fireIt != null) fire(fireIt)
                }, window, TimeUnit.MILLISECONDS)
                return true
            }

            if (doubleB != null) {
                // Double-only binding (no "single" on this keycode). Two modes, per the
                // binding's "blockNativeSingle" flag:
                //
                //  • DEFAULT (flag off): the FIRST tap FALLS THROUGH to the OEM's own
                //    function for this button — otherwise a button whose double-tap is
                //    remapped (e.g. 360-camera → windows) would have its single-tap OEM
                //    action (open 360) silently eaten. We arm the double-window but PASS
                //    THIS DOWN THROUGH (return false); the trailing UP passes too. The
                //    trade-off: the native single fires ~immediately, THEN the confirmed
                //    second press promotes to the double action (GitHub #156).
                //
                //  • BLOCK NATIVE (flag on): the user wants the native single SUPPRESSED
                //    on a double. We CONSUME the first tap (return true) so the OEM never
                //    sees it during the window; if no second tap arrives, the timer REPLAYS
                //    a synthetic native tap (daemon `input keyevent`) so a lone tap still
                //    works — just deferred by the double window (~300ms). The injected tap
                //    round-trips through onKeyEvent and is let through by injectGuard.
                val blockNative = doubleB.optBoolean("blockNativeSingle", false)
                val myEpoch = ++epochSeq
                pending[keyCode] = doubleB
                pendingEpoch[keyCode] = myEpoch
                // Fresh parking DOWN: reset the completed-cycle flag so the NEXT DOWN
                // must itself see an intervening UP before it can promote to double.
                upSeenSinceDown.remove(keyCode)
                if (!blockNative) downPassedThrough.add(keyCode)   // let the matching UP through too
                else downPassedThrough.remove(keyCode)             // consumed → no passthrough for the UP
                scheduler.schedule({
                    // No second press arrived within the window. Drop the arming; if the
                    // binding blocks the native single, replay the native tap now so the
                    // button's own function still happens (just deferred). Match on the
                    // per-arming epoch so a stale timer can't evict a later re-arm.
                    val replay = synchronized(this) {
                        if (pendingEpoch[keyCode] == myEpoch) {
                            pending.remove(keyCode); pendingEpoch.remove(keyCode)
                            if (blockNative) {
                                // Arm the guard BEFORE injecting so the round-tripped event
                                // (which may arrive before the injecting call even returns)
                                // is recognised and passed through, not re-processed.
                                injectGuard.add(keyCode)
                                injectGuardUntilMs[keyCode] = System.currentTimeMillis() + INJECT_GUARD_TTL_MS
                                true
                            } else false
                        } else false
                    }
                    if (replay) injectNativeKey(keyCode)
                }, snap.doubleWindowMs, TimeUnit.MILLISECONDS)
                // block-native CONSUMES the first tap (return true); default PASSES it
                // through to the OEM (return false).
                return blockNative
            }

            if (singleB != null) {
                // No double to disambiguate against → fire immediately, but
                // DEBOUNCE: a firmware that delivers one human press as a burst of
                // discrete DOWNs (repeatCount==0 each) would otherwise fire the
                // action on every one. The burst-coalesce short-circuit above already
                // swallows same-press follow-on DOWNs; this debounce (reusing the
                // outer `now`) additionally covers any DOWN just outside the burst
                // window but inside FIRE_DEBOUNCE_MS. Swallow (still consume) those;
                // only fire the first.
                val last = lastFiredAtMs[keyCode]
                if (last == null || now - last >= FIRE_DEBOUNCE_MS) {
                    lastFiredAtMs[keyCode] = now
                    fire(singleB)
                }
                return true
            }
        }
        // Owned only by a long binding, and this was a tap (no repeat, no single/double
        // binding): pass it through to the OEM. A long-only mapping should not eat the
        // button's normal single-tap function (same principle as the double-only branch
        // above). The long action still fires from the repeatCount>0 branch. We pass the
        // DOWN through and flag it so the trailing UP passes too.
        if (longB != null) {
            synchronized(this) { downPassedThrough.add(keyCode) }
        }
        return false
    }

    /** Kick an async refresh at most once per [REFRESH_THROTTLE_MS]. Never blocks.
     *  Called on the enabled hot path (see onKey) and from the FileObserver push, so
     *  a disabled feature never drives this 2s config reparse. The throttle also
     *  covers the pre-first-load window (cache == null): keys arriving before the
     *  first load — e.g. a hardware key held at onServiceConnected — must NOT each
     *  submit a fresh forceReload(). First-load promptness is preserved because
     *  [lastRefreshKickMs] starts at 0L, so the very first call passes the throttle
     *  immediately; a held-key burst then collapses to one reparse per 2s until the
     *  snapshot lands. warmUp()'s own eager submit() is separate and unaffected. */
    private fun maybeRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshKickMs < REFRESH_THROTTLE_MS) return
        lastRefreshKickMs = now
        io.submit { refresh() }
    }

    /** Coarse re-enable backstop, kicked from onKey ONLY while the feature is
     *  disabled. The FileObserver push is the primary OFF→ON signal; this exists
     *  solely so a disabled dispatcher can still learn it was enabled on platforms
     *  where FileObserver on /data/local/tmp is unreliable — hence the much longer
     *  [REENABLE_POLL_MS] throttle, so key spam while disabled costs at most one
     *  reparse per 30s rather than one per 2s. Never blocks. */
    private fun maybeRefreshSlow() {
        val now = System.currentTimeMillis()
        if (now - lastReenableKickMs < REENABLE_POLL_MS) return
        lastReenableKickMs = now
        io.submit { refresh() }
    }

    /** Reload the keymap config into the in-memory snapshot. Runs off the input
     *  thread only. forceReload picks up a cross-UID write (the web/daemon saved
     *  the section under a different UID than this app process). */
    private fun refresh() {
        try {
            UnifiedConfigManager.forceReload()
            val enabled = UnifiedConfigManager.isKeymapEnabled()
            val arr = UnifiedConfigManager.getKeymapBindings()
            val list = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(it) }
            }
            cache = Snapshot(enabled, list, UnifiedConfigManager.getKeymapDoubleTapWindowMs())
            // Lazily toggle the instant-propagation watcher off this refresh so a
            // disabled feature holds no inotify watch (zero observer-thread wakes)
            // while an enabled one still gets the moment-the-daemon-writes push.
            // startWatching() is idempotent (watcher != null guard) so calling it
            // on every enabled refresh is cheap; stopWatching() no-ops when already
            // down. refresh() runs off the input thread, so registering/unhooking
            // the observer here never touches the input path.
            if (enabled) startWatching() else stopWatching()
            // Self-clean transient gesture state against the NEW snapshot. If a
            // config edit disabled keymap or removed/disabled the "long" for a
            // keycode mid-gesture, its suppressLongUntilUp entry would otherwise be
            // stranded (removed only on an owned+enabled UP) and silently no-op the
            // next long-press; likewise a parked single/double whose binding is gone
            // should not still fire. Compute the set of keycodes that still have an
            // enabled long, then drop any tracked entry outside it (all of them when
            // the feature is now disabled). Guarded by `this`, same monitor as onKey.
            synchronized(this) {
                if (!enabled) {
                    pending.clear(); pendingEpoch.clear(); suppressLongUntilUp.clear()
                    // Drop DOWN timestamps and the completed-cycle flags too: a
                    // disabled feature must carry no stale burst-coalesce gap or
                    // half-seen cycle into a later re-enable.
                    lastDownAtMs.clear(); upSeenSinceDown.clear(); downPassedThrough.clear()
                    nativeCameraPassThroughUntilMs.clear()
                    nativeCameraDefaultGesture.clear()
                    injectGuard.clear(); injectGuardUntilMs.clear()
                } else {
                    val liveLongKeys = HashSet<Int>()
                    for (b in list) {
                        if (b.optBoolean("enabled", true) && b.optString("pressType") == "long") {
                            liveLongKeys.add(b.optInt("keycode", -1))
                        }
                    }
                    suppressLongUntilUp.retainAll(liveLongKeys)
                    // A parked single/double whose keycode no longer owns any enabled
                    // binding cannot resolve to a live action; drop its arming so an
                    // orphaned timer no-ops (it matches on epoch, now removed).
                    val liveKeys = HashSet<Int>()
                    for (b in list) {
                        if (b.optBoolean("enabled", true)) liveKeys.add(b.optInt("keycode", -1))
                    }
                    val gone = pendingEpoch.keys.filter { it !in liveKeys }
                    for (k in gone) { pending.remove(k); pendingEpoch.remove(k) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "keymap refresh failed: ${t.message}")
        }
    }

    /**
     * Release transient gesture state. The dispatcher is a process-lifetime
     * singleton, so its `pending` / `pendingEpoch` / `suppressLongUntilUp` maps
     * survive an accessibility-service disable→re-enable within the SAME process.
     * If a gesture is torn down mid-flight (service destroyed between a promoting
     * DOWN and its UP), those maps would keep a stale entry that could no-op the
     * next long-press after re-enable. Call from
     * [KeepAliveAccessibilityService.onDestroy] to guarantee a clean slate.
     * (The stop of the FileObserver is intentionally NOT done here: an existing
     * watcher only exists because the feature is enabled, and it is re-used on the
     * next reconnect via the `watcher != null` guard in [startWatching] — which
     * [refresh] calls while enabled — so it survives a reconnect without leaking a
     * second observer. The watcher is instead stopped in [refresh] on the
     * enabled→disabled edge, which is where it was started.)
     */
    fun teardown() {
        synchronized(this) {
            pending.clear(); pendingEpoch.clear(); suppressLongUntilUp.clear()
            // Also drop per-keycode DOWN timestamps and completed-cycle flags so a
            // burst-coalesce gap or half-seen cycle measured before teardown can't
            // misclassify the first DOWN after re-enable.
            lastDownAtMs.clear(); upSeenSinceDown.clear(); downPassedThrough.clear()
            nativeCameraPassThroughUntilMs.clear()
            nativeCameraDefaultGesture.clear()
            injectGuard.clear(); injectGuardUntilMs.clear()
        }
        foregroundPackage = null
    }

    /**
     * Replay a synthetic NATIVE key tap for [keyCode] via the daemon (UID 2000 runs
     * `input keyevent <code>` — the app process can't inject system keys). Used by the
     * "block native single" double-binding: we consumed the user's first tap during the
     * double window, and no second tap came, so the button's own OEM function must still
     * happen — deferred by the window. The injected event round-trips through
     * onKeyEvent; injectGuard (armed by the caller BEFORE this runs) lets it pass to the
     * OEM without re-processing. Off-thread, tight timeouts — mirrors [fire].
     */
    private fun injectNativeKey(keyCode: Int) {
        io.submit {
            try {
                val payload = JSONObject().put("keycode", keyCode)
                val conn = DaemonHttpClient.open("/api/keymap/inject", "POST", 2000, 3000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                Log.i(TAG, "keymap inject native keycode=$keyCode -> HTTP $code")
                conn.disconnect()
            } catch (t: Throwable) {
                // Injection failed — clear the guard we optimistically armed so a later
                // real press of this key isn't swallowed waiting for an event that will
                // never arrive. (The TTL would also clear it, but do it promptly.)
                synchronized(this) { injectGuard.remove(keyCode); injectGuardUntilMs.remove(keyCode) }
                Log.w(TAG, "keymap inject native failed keycode=$keyCode: ${t.message}")
            }
        }
    }

    /** Punt the bound action's daemon POST to the pooled I/O executor. */
    private fun fire(binding: JSONObject) {
        val action = binding.optJSONObject("action") ?: return
        io.submit {
            try {
                // Local loopback to the in-process daemon — keep timeouts tight so
                // a wedged daemon can't tie up a pool thread for long.
                val conn = DaemonHttpClient.open("/api/keymap/fire", "POST", 2000, 3000)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(action.toString().toByteArray()) }
                val code = conn.responseCode
                // Read the body so the log shows the daemon's actual outcome
                // (success/outcome/message), not just the HTTP code — this is what
                // makes "the action didn't perform" diagnosable from logcat.
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = try {
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) { "" }
                if (code in 200..299) {
                    Log.i(TAG, "keymap fire ok kind=${action.optString("kind")} resp=$body")
                } else {
                    Log.w(TAG, "keymap fire HTTP $code kind=${action.optString("kind")} resp=$body")
                }
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "keymap fire failed: ${t.message}")
            }
        }
    }
}
