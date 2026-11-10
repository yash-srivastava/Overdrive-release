package com.overdrive.app.onboarding

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.overdrive.app.R
import com.overdrive.app.launcher.AdbDaemonLauncher

private typealias SetupStep = OnboardingConfigPolicy.Step

/**
 * Drives the two-track onboarding guide: owns the [OnboardingOverlayView] lifecycle, the
 * parked-only [OnboardingGate], a cached encoder-busy signal, and the ordered novice
 * spine. The wizards (camera / vehicle / dashboard) are delegated to dedicated coaches
 * that the host hands the overlay to.
 *
 * SEQUENCING: launched from MainActivity AFTER the PIN gate and AFTER SetupGuideDialog,
 * only when [OnboardingState.shouldAutoRunNovice] and the parked gate allow. The overlay
 * attaches to the Activity content root; the camera/vehicle wizards re-parent the overlay
 * onto the relevant dialog window so coachmarks sit ABOVE the MaterialAlertDialog.
 *
 * Step 0 (daemon auth) persists in app-private prefs and is the only hard gate. Everything
 * else is skippable or resumable; nothing here can fire while driving (ACC-ON dismiss).
 */
class OnboardingHost(
    private val activity: Activity,
    private val adbLauncher: AdbDaemonLauncher,
) {
    private val state = OnboardingState.get(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: OnboardingOverlayView? = null
    private var gate: OnboardingGate? = null
    private var started = false
    private var activeCameraCoach: CameraWizardCoach? = null
    private var replayMode = false   // replay re-runs novice basics even if complete
    private var expertMode = false   // launch straight into the Expert track

    // Cached "is the camera daemon (encoder) running?" — refreshed off the main thread
    // every ENCODER_POLL_MS and read synchronously by the overlay's emphasis gate so a
    // decorative pulse never competes with the H.265 encoder on the shared bus.
    @Volatile private var encoderBusyCached = false
    // Wait for each ADB probe before scheduling another: slow probes must not pile up.
    private var encoderPollGeneration = 0
    private val encoderPoll = object : Runnable {
        override fun run() {
            val generation = encoderPollGeneration
            try {
                adbLauncher.isDaemonRunning { running ->
                    mainHandler.post {
                        // A dismissed/restarted tour must not accept an old result.
                        if (!started || generation != encoderPollGeneration) return@post
                        encoderBusyCached = running
                        mainHandler.postDelayed(this, ENCODER_POLL_MS)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "encoder poll failed: ${t.message}")
                if (started && generation == encoderPollGeneration) {
                    mainHandler.postDelayed(this, ENCODER_POLL_MS)
                }
            }
        }
    }

    // ---- public lifecycle ------------------------------------------------------------

    /**
     * Start the novice track if it should run and we're parked. Idempotent — safe to call
     * from onResume. Returns true if the overlay was shown.
     */
    fun startIfNeeded(): Boolean {
        if (started) return false
        if (!state.shouldAutoRunNovice()) return false
        return start()
    }

    /** Force-start (replay "?" affordance). Ignores onboardingComplete, still parked-gated. */
    fun startReplay() {
        if (started) dismiss()
        replayMode = true
        start()
    }

    /** Launch the Expert track directly (from the novice "Explore Setup" CTA). */
    fun startExpertTour() {
        if (started) dismiss()
        expertMode = true
        start()
    }

    private fun start(): Boolean {
        val gate = OnboardingGate(activity) { dismiss() }
        if (!gate.canShow()) return false
        this.gate = gate
        gate.register()

        val ov = OnboardingOverlayView(activity, encoderBusy = { encoderBusyCached })
        ov.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        contentRoot().addView(ov)
        overlay = ov
        started = true

        encoderPollGeneration++
        encoderBusyCached = false
        mainHandler.post(encoderPoll)
        Log.i(TAG, "Onboarding started (expert=$expertMode replay=$replayMode)")
        if (expertMode) launchExpertTrack() else routeFirstStep()
        return true
    }

    private fun launchExpertTrack() {
        val ov = overlay ?: return
        ExpertTourCoach(activity, ov, state) {
            dismiss()
        }.begin()
    }

    /** Tear everything down (ACC-ON, completion, or replay restart). */
    fun dismiss() {
        encoderPollGeneration++
        mainHandler.removeCallbacks(encoderPoll)
        // Cancel the camera coach FIRST so its ~10s applying animator can't keep running
        // against a detached overlay (transient Activity leak) after we remove the view.
        activeCameraCoach?.cancel(); activeCameraCoach = null
        gate?.unregister(); gate = null
        overlay?.let { ov -> (ov.parent as? ViewGroup)?.removeView(ov) }
        overlay = null
        started = false
        expertMode = false
        replayMode = false
    }

    /** Called from MainActivity.onAuthGranted — advances Step 0 if we're waiting on it. */
    fun onDaemonAuthGranted() {
        state.daemonAuthorized = true
        if (started && currentStep == SetupStep.AUTHORIZE) {
            mainHandler.post { advanceFromAuthorize() }
        }
    }

    /**
     * Forwarded from MainActivity.onConfigurationChanged (the Activity uses configChanges,
     * so it doesn't recreate on rotation). The overlay recomputes its responsive width +
     * re-resolves the live anchor's new bounds; the card width and the spotlight cutout
     * are otherwise frozen to the launch orientation.
     */
    fun onConfigChanged() {
        if (!started) return
        overlay?.onConfigChanged()
    }

    // ---- step machine ----------------------------------------------------------------

    private var currentStep = SetupStep.AUTHORIZE

    private fun routeFirstStep() {
        // If the daemon is ALREADY running (already-installed device / auth granted on a
        // prior launch / the OS remembered "Always allow"), the system ADB popup will
        // never re-appear — so Step 0 must NOT wait for an edge that already passed.
        // Detect it up front and skip straight past AUTHORIZE.
        adbLauncher.isDaemonRunning { running ->
            mainHandler.post {
                if (!started) return@post
                if (running) state.daemonAuthorized = true
                reconcilePersistedSetupState()
                currentStep = OnboardingConfigPolicy.nextStep(
                    state.daemonAuthorized,
                    state.modeChosen,
                    state.vehicleStepDone,
                    state.cameraStep == OnboardingState.CameraStep.SAVED_OK,
                    state.dashboardTourDone,
                )
                renderCurrent()
            }
        }
    }

    private fun renderCurrent() {
        val ov = overlay ?: return
        when (currentStep) {
            SetupStep.AUTHORIZE -> renderAuthorize(ov)
            SetupStep.MODE -> renderModeChoice(ov)
            SetupStep.VEHICLE -> launchVehicleChapter()
            SetupStep.CAMERA -> launchCameraChapter()
            SetupStep.DASHBOARD -> launchDashboardChapter()
            SetupStep.DONE -> renderDone(ov)
        }
    }

    /**
     * App-private onboarding flags are not part of config backups. Reconcile
     * them with durable setup state so reinstall+restore does not ask the
     * owner to reconfigure a validated camera or re-select a known vehicle.
     */
    private fun reconcilePersistedSetupState() {
        try {
            val vehicle = com.overdrive.app.config.UnifiedConfigManager.getVehicle()
            // A half-applied vehicle setup already wrote the model; its step stays open so the
            // guide offers to finish it.
            if (OnboardingConfigPolicy.hasConfiguredVehicle(
                    vehicle.optString("modelId", ""),
                    vehicle.optString("modelSource", ""),
                ) && !com.overdrive.app.setup.VehicleSetupJournal.isPending(activity)) {
                state.vehicleStepDone = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "vehicle setup reconciliation failed: ${t.message}")
        }

        try {
            val camera = com.overdrive.app.camera.CameraConfigResolver.getCameraSection()
            if (OnboardingConfigPolicy.hasConfiguredCamera(
                    camera.optInt("probedCameraId", -1),
                    camera.optBoolean("manualOverride", false),
                    camera.optBoolean("probedAndValidated", false),
                )) {
                state.cameraStep = OnboardingState.CameraStep.SAVED_OK
            }
        } catch (t: Throwable) {
            Log.w(TAG, "camera setup reconciliation failed: ${t.message}")
        }
    }

    // ---- Step 0: authorize daemon ----------------------------------------------------

    private fun renderAuthorize(ov: OnboardingOverlayView) {
        ov.showCentered()
        if (state.daemonAuthorized) { advanceFromAuthorize(); return }

        ov.bindStep(
            title = activity.getString(R.string.onboarding_authorize_title),
            body = activity.getString(R.string.onboarding_authorize_body),
            primaryText = activity.getString(R.string.onboarding_authorize_button),
            onPrimary = {
                // The system ADB popup is OS-owned (we can't draw over it). Daemon
                // startup already triggers it; here we just narrate + wait for the
                // onAuthGranted callback (routed via MainActivity → onDaemonAuthGranted).
                ov.bindStep(
                    title = activity.getString(R.string.onboarding_authorize_waiting_title),
                    body = activity.getString(R.string.onboarding_authorize_waiting_body),
                    primaryText = null, onPrimary = null,
                    // ALWAYS-AVAILABLE ESCAPE: the user must never be locked behind the
                    // waiting state (e.g. already-installed device where no popup appears).
                    secondaryText = activity.getString(R.string.onboarding_dismiss),
                    onSecondary = { dismiss() },
                )
                // Failsafe: if the grant never arrives (popup dismissed / already authed),
                // re-offer with a Try-again + Dismiss instead of hanging.
                mainHandler.postDelayed({
                    if (started && currentStep == SetupStep.AUTHORIZE && !state.daemonAuthorized) {
                        renderAuthorizeRetry(ov)
                    }
                }, AUTH_WAIT_MS)
            },
            // Escape on the very first auth card too.
            secondaryText = activity.getString(R.string.onboarding_dismiss),
            onSecondary = { dismiss() },
        )
    }

    private fun renderAuthorizeRetry(ov: OnboardingOverlayView) {
        ov.bindStep(
            title = activity.getString(R.string.onboarding_authorize_retry_title),
            body = activity.getString(R.string.onboarding_authorize_retry_body),
            primaryText = activity.getString(R.string.onboarding_authorize_retry_button),
            onPrimary = { renderAuthorize(ov) },
            secondaryText = activity.getString(R.string.onboarding_dismiss),
            onSecondary = { dismiss() },
        )
    }

    private fun advanceFromAuthorize() {
        reconcilePersistedSetupState()
        currentStep = SetupStep.MODE
        renderCurrent()
    }

    // ---- Step 1: choose operating mode -----------------------------------------------

    /**
     * First-run operating-mode choice. Presented AFTER daemon authorization so the
     * daemon's HTTP server is up and [persistOperatingMode] can POST the value into
     * UnifiedConfig (the app UID can't write the daemon's config file directly).
     *
     * Two mutually-exclusive options mapped to the card's primary/secondary buttons:
     *   Primary   = "Vehicle ON + OFF" (recommended, default) → operatingMode="onAndOff"
     *   Secondary = "Vehicle ON only"                          → operatingMode="onOnly"
     * Never a hard gate: either pick (or a dismiss) advances to CAMERA, and the config
     * default is already "onAndOff" so no write is strictly required for the default.
     */
    private fun renderModeChoice(ov: OnboardingOverlayView) {
        ov.showCentered()
        ov.bindStep(
            title = activity.getString(R.string.onboarding_mode_title),
            body = activity.getString(R.string.onboarding_mode_body),
            primaryText = activity.getString(R.string.onboarding_mode_on_off_button),
            onPrimary = { chooseModeAndAdvance("onAndOff") },
            secondaryText = activity.getString(R.string.onboarding_mode_on_only_button),
            onSecondary = { chooseModeAndAdvance("onOnly") },
        )
    }

    private fun chooseModeAndAdvance(mode: String) {
        // "onAndOff" is the config DEFAULT — no write is required for it to take effect,
        // so mark the step done immediately (a failed/absent write still yields onAndOff)
        // and clear any stale pending non-default mode.
        // "onOnly" is only real once durably persisted: record it as pendingOperatingMode
        // (survives process death + onboarding completion) and defer marking modeChosen
        // until the POST confirms, so a daemon-not-ready race re-asks / flushes later
        // instead of silently dropping the choice. Never a hard gate — always advance now.
        if (mode == "onAndOff") {
            state.pendingOperatingMode = null
            state.modeChosen = true
        } else {
            state.pendingOperatingMode = mode
        }
        persistOperatingMode(mode)
        reconcilePersistedSetupState()
        currentStep = OnboardingConfigPolicy.nextStep(
            true,
            true,
            state.vehicleStepDone,
            state.cameraStep == OnboardingState.CameraStep.SAVED_OK,
            state.dashboardTourDone,
        )
        renderCurrent()
    }

    /**
     * Persist the chosen operating mode into UnifiedConfig by POSTing to the daemon's
     * local HTTP server (127.0.0.1:8080 — CameraDaemon.HTTP_PORT). Off the main thread.
     * On a fresh install this can fire before the daemon has bound :8080 (it's launched
     * ~500ms after auth and takes a few more seconds), so we RETRY with backoff. Only on
     * a confirmed 2xx for "onOnly" do we set modeChosen=true — otherwise the flag stays
     * false and the MODE step re-shows next launch (routeFirstStep), so a non-default
     * choice is never silently lost. Forces a direct connection (Proxy.NO_PROXY) so a
     * live sing-box global proxy doesn't swallow the loopback call — same guard
     * MainActivity uses for its daemon POSTs.
     */
    private fun persistOperatingMode(mode: String) {
        Thread {
            val ok = postOperatingModeWithRetry(mode)
            if (ok) {
                // Durably written — record the step done + clear the pending marker
                // (covers the onOnly case chooseModeAndAdvance deliberately left unmarked).
                state.modeChosen = true
                state.pendingOperatingMode = null
            } else {
                // Not confirmed within the window. modeChosen stays false and (for a
                // non-default pick) pendingOperatingMode stays set, so it is flushed later
                // by flushPendingOperatingMode() when the daemon is confirmed up — this
                // survives onboarding completion, unlike the MODE step re-ask.
                Log.w(TAG, "persistOperatingMode($mode) never confirmed — left pending for daemon-ready flush")
            }
        }.start()
    }

    // ---- chapter launchers (delegated to coaches) ------------------------------------

    private fun launchCameraChapter() {
        val ov = overlay ?: return
        val coach = CameraWizardCoach(activity, ov, state, adbLauncher) { _ ->
            // Vehicle selection has already run (or was restored), so camera
            // completion now advances directly to the dashboard tour.
            activeCameraCoach = null
            currentStep = SetupStep.DASHBOARD
            renderCurrent()
        }
        activeCameraCoach = coach
        coach.begin()
    }

    private fun launchVehicleChapter() {
        val ov = overlay ?: return
        val afterVehicle = {
            overlay?.visibility = View.VISIBLE
            reconcilePersistedSetupState()
            currentStep = if (state.cameraStep == OnboardingState.CameraStep.SAVED_OK) {
                SetupStep.DASHBOARD
            } else {
                SetupStep.CAMERA
            }
            renderCurrent()
        }
        // The setup assistant covers model, camera system and storage; the profile coach is
        // the fallback when it cannot open.
        if (!state.vehicleStepDone) {
            ov.visibility = View.GONE
            val opened = (activity as? com.overdrive.app.ui.MainActivity)
                ?.startVehicleSetup(fromOnboarding = true) { _ -> if (started) afterVehicle() } == true
            if (opened) return
            ov.visibility = View.VISIBLE
        }
        VehicleWizardCoach(activity, ov, state) { afterVehicle() }.begin()
    }

    private fun launchDashboardChapter() {
        val ov = overlay ?: return
        DashboardTourCoach(activity, ov, state) {
            currentStep = SetupStep.DONE
            renderCurrent()
        }.begin()
    }

    // ---- completion ------------------------------------------------------------------

    private fun renderDone(ov: OnboardingOverlayView) {
        ov.showCentered()
        ov.pulseAttention()
        ov.bindStep(
            title = activity.getString(R.string.onboarding_done_title),
            body = activity.getString(R.string.onboarding_done_body),
            primaryText = activity.getString(R.string.onboarding_done_primary),
            onPrimary = {
                state.onboardingComplete = true
                state.expertTourEntry = EXPERT_ENTRY_CAMERA_ADVANCED
                dismiss()
            },
            secondaryText = activity.getString(R.string.onboarding_done_secondary),
            onSecondary = {
                // Hand straight into the Expert track (starts at its first chapter).
                state.onboardingComplete = true
                startExpertTour()
            },
        )
    }

    // ---- helpers ---------------------------------------------------------------------

    private fun contentRoot(): ViewGroup =
        activity.findViewById(android.R.id.content)

    companion object {
        private const val TAG = "OnboardingHost"
        private const val ENCODER_POLL_MS = 3000L
        private const val AUTH_WAIT_MS = 20000L
        private const val PERSIST_MAX_ATTEMPTS = 5
        private const val EXPERT_ENTRY_CAMERA_ADVANCED = "cameraAdvanced"

        /**
         * Blocking POST of operatingMode to the daemon with retry+backoff, trusting only
         * a confirmed {success:true} body — HTTP 200 alone is NOT enough, since the
         * daemon's sendJsonError returns 200 {success:false} on a persist failure (mirror
         * MainActivity.postSurveillanceConfig). MUST be called off the main thread.
         * Returns true only on a durable write.
         */
        private fun postOperatingModeWithRetry(mode: String): Boolean {
            // ~2s, 4s, 6s, 8s, 10s backoff ≈ 30s total — comfortably covers daemon bring-up.
            for (attempt in 0 until PERSIST_MAX_ATTEMPTS) {
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL("http://127.0.0.1:8080/api/surveillance/config")
                        .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write("{\"operatingMode\":\"$mode\"}".toByteArray()) }
                    val code = conn.responseCode
                    Log.i(TAG, "postOperatingMode($mode) attempt ${attempt + 1} → HTTP $code")
                    if (code in 200..299) {
                        // A false 200 keeps retrying (transient lock contention); a
                        // non-JSON body assumes success (legacy endpoint).
                        val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                        val success = try {
                            org.json.JSONObject(bodyText).optBoolean("success", true)
                        } catch (_: Throwable) { true }
                        if (success) return true
                        Log.w(TAG, "postOperatingMode($mode) attempt ${attempt + 1}: HTTP 200 but success=false — retrying")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "postOperatingMode($mode) attempt ${attempt + 1} failed: ${t.message}")
                } finally {
                    conn?.disconnect()
                }
                try { Thread.sleep((attempt + 1) * 2000L) } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
            }
            return false
        }

        /**
         * Daemon-ready flush of a pending non-default operating-mode choice. Call from
         * MainActivity once the daemon is confirmed up (onDaemonAuthGranted / daemon-ready
         * path). If the first-run POST retries all expired before the daemon bound :8080
         * and the user then completed onboarding (which disables the MODE step re-ask via
         * onboardingComplete), the choice is still recorded in
         * OnboardingState.pendingOperatingMode and gets written here — decoupled from the
         * onboarding step lifecycle. No-op when nothing is pending. Runs its own worker
         * thread; safe to call repeatedly (idempotent).
         */
        @JvmStatic
        fun flushPendingOperatingMode(context: android.content.Context) {
            val state = OnboardingState.get(context)
            val pending = state.pendingOperatingMode ?: return
            Thread {
                // Reconcile FIRST: if the user has since explicitly set the operating mode
                // (via Settings — the daemon marks operatingModeSetByUser=true on any
                // operatingMode POST), a stale onboarding pending value must NOT clobber
                // that later choice. Drop the pending marker instead of re-asserting it.
                // Only when the daemon reports no explicit user choice yet (untouched
                // default) do we write the pending onboarding pick.
                when (readOperatingModeSetByUser()) {
                    true -> {
                        state.pendingOperatingMode = null
                        state.modeChosen = true
                        Log.i(TAG, "flushPendingOperatingMode: mode already set by user — dropping stale pending=$pending")
                        return@Thread
                    }
                    null -> {
                        // Daemon unreachable — can't reconcile safely. Leave pending for a
                        // later flush rather than risk clobbering; do NOT POST blindly.
                        Log.w(TAG, "flushPendingOperatingMode: daemon unreachable for reconcile — leaving pending=$pending")
                        return@Thread
                    }
                    false -> { /* untouched default — safe to write the pending pick below */ }
                }
                if (postOperatingModeWithRetry(pending)) {
                    state.pendingOperatingMode = null
                    state.modeChosen = true
                    Log.i(TAG, "flushPendingOperatingMode: persisted pending mode=$pending")
                } else {
                    Log.w(TAG, "flushPendingOperatingMode: daemon still unreachable — leaving pending=$pending")
                }
            }.start()
        }

        /**
         * Clear the daemon-side operatingModeSetByUser marker (POST
         * {operatingModeSetByUser:false}). Called from onboarding reset/replay so a wiped
         * session doesn't inherit a PRIOR session's "user chose a mode" flag — without
         * this, flushPendingOperatingMode would GET a stale true and wrongly drop a
         * legitimate NEW replay pick. Runs its own worker thread with retry+backoff; the
         * body-success check means a false 200 keeps retrying. Best-effort: if the daemon
         * is unreachable the marker stays set, but the immediate persistOperatingMode POST
         * from the replay pick (which does NOT consult the marker) still normally lands the
         * new choice — this clear only closes the narrow "immediate POST also failed" window.
         */
        @JvmStatic
        fun clearOperatingModeUserFlag() {
            Thread {
                for (attempt in 0 until PERSIST_MAX_ATTEMPTS) {
                    var conn: java.net.HttpURLConnection? = null
                    try {
                        conn = java.net.URL("http://127.0.0.1:8080/api/surveillance/config")
                            .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write("{\"operatingModeSetByUser\":false}".toByteArray()) }
                        if (conn.responseCode in 200..299) {
                            val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                            val success = try {
                                org.json.JSONObject(bodyText).optBoolean("success", true)
                            } catch (_: Throwable) { true }
                            if (success) {
                                Log.i(TAG, "clearOperatingModeUserFlag: cleared daemon marker")
                                return@Thread
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "clearOperatingModeUserFlag attempt ${attempt + 1} failed: ${t.message}")
                    } finally {
                        conn?.disconnect()
                    }
                    try { Thread.sleep((attempt + 1) * 2000L) } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt(); break
                    }
                }
                Log.w(TAG, "clearOperatingModeUserFlag: never confirmed (daemon unreachable)")
            }.start()
        }

        /**
         * GET the daemon's surveillance config and return operatingModeSetByUser.
         * true  = user has explicitly chosen a mode (onboarding or Settings) — don't clobber.
         * false = untouched default — safe to write a pending onboarding pick.
         * null  = daemon unreachable / unparseable — caller must not write blindly.
         * MUST be called off the main thread.
         */
        private fun readOperatingModeSetByUser(): Boolean? {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL("http://127.0.0.1:8080/api/surveillance/config")
                    .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                if (conn.responseCode !in 200..299) return null
                val bodyText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(bodyText)
                // GET wraps the payload under "config" (sendConfig); tolerate either shape.
                val cfg = json.optJSONObject("config") ?: json
                return cfg.optBoolean("operatingModeSetByUser", false)
            } catch (t: Throwable) {
                Log.w(TAG, "readOperatingModeSetByUser failed: ${t.message}")
                return null
            } finally {
                conn?.disconnect()
            }
        }
    }
}
