package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level contract tests for the camera-view ownership / auto-hide
 * concurrency fixes (audit round: "ownership TOCTOU" + "auto-hide captures the
 * new session"). Same idiom as {@code CameraDaemonRestartSafetyContractTest}:
 * the invariants are ORDERINGS and atomicity disciplines that a pure-JVM test
 * cannot exercise directly (the pipeline needs Android), so we pin the shapes
 * that make them true. If one of these fails, the concurrency reasoning in the
 * fix has been edited — re-derive it before relaxing the assertion.
 */
public class CamViewOwnershipContractTest {

    private static final String PIPELINE =
            "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java";
    private static final String HANDLER =
            "app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java";

    @Test
    public void hideVerdictAndHideAreOneAtomicStepInThePipeline() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        // The ownership check must live INSIDE disableCamViewInternal's locked
        // region — an unlocked pre-check in the handler is exactly the TOCTOU
        // this contract exists to prevent.
        int internal = source.indexOf("private int disableCamViewInternal(");
        int lock = source.indexOf("bsLifecycleLock.lock();", internal);
        int ownerCheck = source.indexOf("camViewHideAllowedFor(token)", lock);
        int notOwner = source.indexOf("return CAMVIEW_HIDE_NOT_OWNER;", ownerCheck);
        assertTrue(internal >= 0);
        assertTrue(lock > internal);
        assertTrue(ownerCheck > lock);
        assertTrue(notOwner > ownerCheck);
    }

    @Test
    public void showRequestNoteIsSerializedUnderTheLaneLock() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        int note = source.indexOf("public long noteCamViewShowRequest(");
        int lock = source.indexOf("bsLifecycleLock.lock();", note);
        int bump = source.indexOf("camViewSessionId = camViewSessionSeq.incrementAndGet();", lock);
        int nextMethod = source.indexOf("private long tryClaimCamViewAutoHideLocked(", note);
        assertTrue(note >= 0);
        assertTrue(lock > note && lock < nextMethod);
        assertTrue(bump > lock && bump < nextMethod);
    }

    @Test
    public void autoHideClaimIsAtomicAndReturnsTheArmingSession() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        // The claim helper must return the session BOUND AT ARM TIME
        // (camViewHideSessionId), never the live camViewSessionId — a claim
        // landing between a newer show's note and its enable re-arm would
        // otherwise capture the new session and hide the view it is putting up.
        int claim = source.indexOf("private long tryClaimCamViewAutoHideLocked(");
        int claimEnd = source.indexOf("}", source.indexOf("return camViewHideSessionId;", claim));
        assertTrue(claim >= 0);
        assertTrue(claimEnd > claim);
        String claimBody = source.substring(claim, claimEnd);
        assertFalse("claim must not read the LIVE session id",
                claimBody.contains("return camViewSessionId;"));
        // Both dispatch sites must route through the locked claim — the old
        // unlocked CAS-then-read pattern must not come back.
        assertTrue(count(source, "tryClaimCamViewAutoHideLocked(") >= 3); // def + 2 sites
        assertFalse("unlocked auto-hide dispatch pattern has returned",
                source.contains("new Thread(this::disableCamView, \"CamViewAutoHide\")"));
        // enableCamView must bind the deadline to its session under the lock.
        int enable = source.indexOf("public boolean enableCamView(");
        int armBind = source.indexOf("camViewHideSessionId = camViewSessionId;", enable);
        assertTrue(enable >= 0);
        assertTrue(armBind > enable);
    }

    @Test
    public void handlerRestoresGlobalHideAndV40GeometryFlow() throws IOException {
        String source = readRepositoryFile(HANDLER);
        int hide = source.indexOf("if (clean.equals(\"/api/camview/hide\"))");
        int show = source.indexOf("if (clean.equals(\"/api/camview/show\"))", hide);
        String hideBody = source.substring(hide, show);
        // The hide route must use the SESSION-INVALIDATING hide, not the lenient
        // lifecycle disableCamView: only the session bump stops a straight-through
        // show still in flight outside the mutation lock from arming AFTER the hide
        // and reopening the camera behind the success this route reports.
        assertTrue(hideBody.contains("pipeline.hideCamView();"));
        assertFalse("the lenient lifecycle hide must not carry the API hide",
                hideBody.contains("pipeline.disableCamView();"));
        assertTrue(hideBody.contains("singletonMap(\"enabled\", false)"));
        // Only an automation run's hide is ownership-checked; a superseded one returns
        // before the enabled=false persist and leaves the newer view up.
        int tokened = hideBody.indexOf("pipeline.hideCamViewIfAllowed(hideOwner)");
        int notOwner = hideBody.indexOf("GpuSurveillancePipeline.CAMVIEW_HIDE_NOT_OWNER", tokened);
        int persistOff = hideBody.indexOf("singletonMap(\"enabled\", false)");
        assertTrue(hideBody.indexOf("if (hideOwner == null)") >= 0);
        assertTrue(tokened >= 0);
        assertTrue(notOwner > tokened && notOwner < persistOff);

        int persist = source.indexOf("persistCamViewGeometry(p, target);", show);
        // Fail-open contract: a FAILED geometry persist installs the requested
        // geometry as the session override; only a successful persist clears it.
        // An unconditional clear rendered the previous (stale) position while the
        // API reported success.
        int failOpen = source.indexOf(
                "pipeline.setCamViewGeometryOverride(target, failedGeo);", persist);
        int clearOverride = source.indexOf(
                "pipeline.clearCamViewGeometryOverride();", persist);
        int enable = source.indexOf(
                "pipeline.enableCamView(mode, target, autoHide, showSession)", clearOverride);
        assertTrue(show >= 0);
        assertTrue(persist > show);
        assertTrue(failOpen > persist);
        assertTrue(clearOverride > persist);
        assertTrue(enable > clearOverride);
    }

    @Test
    public void showRetainsV40AutoHidePersistenceContract() throws IOException {
        String source = readRepositoryFile(HANDLER);
        assertTrue(source.contains(
                "if (autoHide > 0) cvVals.put(\"autoHideSec\", autoHide);"));
    }

    @Test
    public void passiveApaCameraSelectionRoutesThroughTheOemViewCommand() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        // In passive APA mode the HAL feed is the firmware's own composed output and
        // the shader passes it through FULL-FRAME (uApaMode > 0.5) — there are no
        // per-camera quadrants to slice, so front/rear/left/right selection can only
        // happen at the firmware, via the OEM AUTO_VIDEO_BUTTON view command. Per the
        // selector's own documented contract (BydDataCollector.setNativeCameraView),
        // the broadcast "never opens the panorama application" — no second camera
        // pane appears; only the composed feed OverDrive already displays changes
        // camera. A previous revision removed this on the mistaken premise that it
        // opens the OEM window; do not re-remove it without re-reading that contract.
        int helper = source.indexOf("private void requestPassiveNativeView(int mode)");
        assertTrue("passive-APA OEM view selection must exist", helper >= 0);
        // Gated to passive APA mode only — legacy/mosaic paths must never fire it.
        int gate = source.indexOf("isPassiveApaModeEnabled()) return;", helper);
        int broadcast = source.indexOf("AUTO_VIDEO_BUTTON", helper);
        assertTrue(gate > helper);
        assertTrue("the passive gate must precede the broadcast", gate < broadcast);
        // Dispatched on the camview program transition, DEFERRED past the lane-lock
        // release (a detached exec must never hold bsLifecycleLock).
        int transition = source.indexOf("passiveSelectMode = camViewMode;");
        int unlock = source.indexOf("bsLifecycleLock.unlock();", transition);
        int dispatch = source.indexOf(
                "if (passiveSelectMode >= 0) requestPassiveNativeView(passiveSelectMode);",
                transition);
        assertTrue(transition >= 0);
        assertTrue(unlock > transition);
        assertTrue("dispatch must follow the unlock", dispatch > unlock);
        // The app-process instance route stays out of the daemon pipeline (needs an
        // app Context); the daemon sends the equivalent broadcast via `am` itself.
        assertFalse(source.contains(".setNativeCameraView("));
    }

    @Test
    public void handlerMintsOneSessionAndUsesTheSessionValidatedEntryPoint() throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        // The self-minting convenience wrapper must NOT exist: a wrapper that calls
        // noteCamViewShowRequest() itself mints a session NEWER than any hide that
        // just landed, so the arm-side session check passes and the dismissed view
        // re-arms — the hide race. (A retry pass racing a hide was exactly this.)
        assertFalse(pipeline.contains(
                "public void enableCamView(int mode, String target, int autoHideSec)"));

        String handler = readRepositoryFile(HANDLER);
        // The show route mints ONE session per USER request...
        int mint = handler.indexOf(
                "showSession = pipeline.noteCamViewShowRequest(showOwner);");
        assertTrue(mint >= 0);
        // ...at ENTRY, before any config I/O. The persist calls below the mint can
        // block on the cross-process config file lock for hundreds of ms; a mint
        // BELOW them post-dated a hide completing in that window, so the older show
        // minted newer authority and reopened the dismissed view.
        int showRoute = handler.indexOf("if (clean.equals(\"/api/camview/show\"))");
        int firstConfigIo = handler.indexOf("getCamViewAutoHideSec()", showRoute);
        int geomPersist = handler.indexOf("persistCamViewGeometry(p, target);", showRoute);
        assertTrue(showRoute >= 0 && firstConfigIo > showRoute && geomPersist > showRoute);
        assertTrue("session mint must precede all config I/O in the show route",
                mint > showRoute && mint < firstConfigIo && mint < geomPersist);
        // The whole mutation transaction (mint → autoHide read → geometry persist →
        // intent write → override install) is ONE synchronized unit: without the
        // monitor, an OLDER show's handler resuming after a newer show completed
        // overwrote the config/override the newer session's transition tick
        // resolves — session order and config-write order TORE.
        int monitor = handler.indexOf("synchronized (camViewShowMutationLock) {", showRoute);
        int overrideInstall = handler.indexOf(
                "pipeline.setCamViewGeometryOverride(target, failedGeo);", showRoute);
        assertTrue(monitor > showRoute && monitor < mint);
        assertTrue("override install must be inside the transaction ordering",
                overrideInstall > geomPersist);
        // ...and threads it through the straight-through arm AND both retry starts.
        assertTrue(handler.indexOf(
                "pipeline.enableCamView(mode, target, autoHide, showSession)", mint) > mint);
        assertTrue(count(handler,
                "startCamViewArmRetry(mode, target, autoHide, showSession)") >= 2);
        // The retry loop must never re-mint (exactly ONE mint site in the handler)
        // or fall back to a session-less arm.
        assertTrue(count(handler, "noteCamViewShowRequest(") == 1);
        assertFalse(handler.contains("p.enableCamView(mode, target, autoHide);"));
    }

    @Test
    public void allowedHideInvalidatesTheSessionEvenWhenNotArmed() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        // An allowed hide must bump the session BEFORE the !camViewActive
        // early-out: a hide of a request still deferred behind a cold start is an
        // explicit cancel, and only the session bump stops that request's
        // straight-through enable (already past its mutation transaction) from
        // arming after the hide.
        int ownerBranch = source.indexOf("if (!camViewHideAllowedFor(token))");
        int bump = source.indexOf(
                "camViewSessionId = camViewSessionSeq.incrementAndGet();", ownerBranch);
        int earlyOut = source.indexOf(
                "if (!camViewActive) return CAMVIEW_HIDE_ALREADY_HIDDEN;", ownerBranch);
        assertTrue(ownerBranch >= 0);
        assertTrue(bump > ownerBranch);
        assertTrue(earlyOut > bump);
        // The GLOBAL API hide must take the same not-yet-armed invalidation
        // (invalidateSession=true). The lenient lifecycle disableCamView must NOT —
        // pipeline.stop() runs it on routine warmup restarts, where killing a
        // pending deferred arm drops the key press the retry exists to rescue.
        int apiHide = source.indexOf("public int hideCamView()");
        assertTrue(apiHide >= 0);
        assertTrue(source.indexOf(
                "disableCamViewInternal(null, null, false, true);", apiHide) > apiHide);
        int lenient = source.indexOf("public void disableCamView()");
        assertTrue(lenient >= 0);
        assertTrue(source.indexOf(
                "disableCamViewInternal(null, null, false, false);", lenient) > lenient);
        // The real-teardown path must invalidate too (auto-hide, stop, takeover),
        // via the SHARED session-ending helper — whose bump is load-bearing.
        int internal = source.indexOf("private int disableCamViewInternal(");
        int internalEnd = source.indexOf("private boolean isCamViewClusterTarget()", internal);
        assertTrue(source.substring(internal, internalEnd)
                .contains("endCamViewSessionLocked();"));
        int helper = source.indexOf("private void endCamViewSessionLocked()");
        int helperBump = source.indexOf(
                "camViewSessionId = camViewSessionSeq.incrementAndGet();", helper);
        assertTrue(helper >= 0);
        assertTrue(helperBump > helper);
    }

    @Test
    public void blindSpotTakeoverClearsThePersistedCameraViewRequest() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        int takeoverLog = source.indexOf("took lane ownership from camera-view");
        int takeover = source.lastIndexOf("if (camViewActive)", takeoverLog);
        int takeoverEnd = source.indexOf("blindSpotEnabled = true;", takeover);
        String takeoverBody = source.substring(takeover, takeoverEnd);
        assertTrue(takeoverBody.contains("endCamViewSessionLocked();"));
        assertTrue(takeoverBody.contains("off.put(\"enabled\", false);"));
        assertTrue(takeoverBody.contains("setCamViewValues(off);"));
        assertFalse(source.contains("persistCamViewDisabledIfSessionStill("));
    }

    @Test
    public void enableRevalidatesTheSessionAfterTheLaneBuildReleasesTheLock() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        // buildSharedLaneLocked releases bsLifecycleLock around its GL-init wait;
        // a hide (or newer show) in that window invalidates the session, so the
        // entry check alone is insufficient — the session must be re-validated
        // after the builder returns and BEFORE camViewActive is published.
        int build = source.indexOf("buildSharedLaneLocked();",
                source.indexOf("public boolean enableCamView("));
        int recheck = source.indexOf("if (showSession != camViewSessionId)", build);
        int publish = source.indexOf("camViewActive = true;       // armed only on successful build");
        assertTrue(build >= 0);
        assertTrue(recheck > build);
        assertTrue(publish > recheck);
    }

    @Test
    public void laneRectReportsTheCommittedCamViewGeometry() throws IOException {
        String source = readRepositoryFile(PIPELINE);
        // Between enableCamView and the arbiter's transition tick (≤250ms),
        // bsGeomRect still holds the PREVIOUS program's geometry — a /status
        // response built in that window fed the ✕ a stale rect that passed the
        // overlay's fetched-after-the-edge freshness gate (the daemon itself was
        // the stale source). getLaneGeomRect must therefore prefer the camview
        // rect while a camera view holds an unmasked claim, and must never leak a
        // cluster-space rect to the head-unit ✕.
        int method = source.indexOf("public int[] getLaneGeomRect()");
        // The claim must require PROG_CAMVIEW, or PROG_NONE with blind-spot
        // DISABLED. PROG_NONE alone is NOT camview ownership of the visible lane:
        // enableCamView sets it while a blind-spot card may still be SHOWING, and
        // claiming the camview rect there moved the visible blind-spot ✕ to the
        // camera position.
        int claim = source.indexOf("laneProgram == PROG_CAMVIEW", method);
        int noneGuard = source.indexOf(
                "laneProgram == PROG_NONE && !blindSpotEnabled", method);
        // The cluster gate lives INSIDE the getter, keyed to the claim-appropriate
        // target — bsTarget lags camViewTarget in the transition window, so an
        // external pre-gate on bsTarget suppressed the rect during a
        // cluster→head-unit camview show (overlay adopted null, ✕ jumped).
        int clusterGuard = source.indexOf(
                "camViewClaim ? isCamViewClusterTarget() : \"cluster\".equals(bsTarget)",
                method);
        int select = source.indexOf(
                "camViewClaim ? camViewGeomRect : bsGeomRect", method);
        int methodEnd = source.indexOf("public", source.indexOf("return new int[]", method));
        assertTrue(method >= 0);
        assertTrue(claim > method && claim < methodEnd);
        assertTrue(noneGuard > method && noneGuard < methodEnd);
        assertTrue(clusterGuard > method && clusterGuard < methodEnd);
        assertTrue("cluster guard must precede the rect selection", clusterGuard < select);
        // HttpServer must rely on that internal gate (no stale bsTarget pre-gate).
        String http = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        assertFalse(http.contains(
                "boolean laneOnCluster = \"cluster\".equals(pipeline.getBsTargetString());"));
    }

    @Test
    public void retryCarriesTheRequestSessionThroughEveryArmPass() throws IOException {
        String source = readRepositoryFile(HANDLER);
        // The retry serves a PAST user request: it must carry that request's session
        // into every arm pass so a hide's session bump cancels it authoritatively.
        assertTrue(source.contains(
                "private static void startCamViewArmRetry(int mode, String target, int autoHide,"));
        assertTrue(source.contains("long showSession) {"));
        int loop = source.indexOf("private static void startCamViewArmRetry(");
        int arm = source.indexOf(
                "p.enableCamView(mode, target, autoHide, showSession)", loop);
        assertTrue(arm > loop);
        // A false return means superseded — the loop must treat it as terminal
        // (return), never as retryable.
        int superseded = source.indexOf("superseded by a hide or", arm);
        assertTrue(superseded > arm);
    }

    @Test
    public void overlayPollDefersToCloseEdgesDuringTheTransitionWindow() throws IOException {
        String overlay = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        // The daemon cannot label WHICH program's content is on screen during its
        // ≤250ms program transition (bsCardShowing keys on laneProgram, which
        // enableCamView wipes to PROG_NONE while the blind-spot card may still be
        // visible). A poll built in that window reported bsCardShowing=false +
        // camViewActive=true and the overlay swapped in the camera ✕ over the
        // visible blind-spot card — its tap fired the wrong hide. Both receivers
        // stamp the edge instant; both poll adoptions defer past it.
        assertTrue(count(overlay,
                "closeEdgeAtMs = android.os.SystemClock.elapsedRealtime();") >= 2);
        int settle = overlay.indexOf(
                "fetchStartElapsedMs > closeEdgeAtMs + CLOSE_EDGE_SETTLE_MS");
        assertTrue(settle >= 0);
        // The settle timestamp alone LOSES when the poll beats the detached edge
        // broadcast: the daemon's own "labels unreliable" signal must gate the
        // same adoptions, independent of broadcast timing.
        assertTrue(overlay.indexOf(
                "!recStatus.optBoolean(\"laneTransitioning\", false)", settle) > settle);
        String pipeline = readRepositoryFile(PIPELINE);
        int transitioning = pipeline.indexOf("public boolean isLaneProgramTransitioning()");
        assertTrue(transitioning >= 0);
        assertTrue(pipeline.indexOf(
                "bsLayerVisible && laneProgram == PROG_NONE", transitioning) > transitioning);
        // The /status pair must come from ONE laneProgram read (getCloseLabels):
        // two separate getter calls can straddle an arbiter program change and
        // emit false/false with the blind-spot card still visible — labels that
        // claim coherence while lying. Volatile reads are visible, not atomic.
        int snapshot = pipeline.indexOf("public CloseLabels getCloseLabels()");
        assertTrue(snapshot >= 0);
        // Seqlock discipline over the WHOLE tuple: a program-only bracket is not
        // preemption-safe (the HTTP thread can be descheduled across multiple
        // 250ms ticks, so BS → CAMVIEW → BS passes an equal program check while
        // the companions came from the camview era). Both passes must read all
        // three fields; ANY mismatch is reported as transitioning. A full-tuple
        // ABA computes a label correct for the end-of-snapshot state anyway.
        int p1 = pipeline.indexOf("final int p1 = laneProgram;", snapshot);
        int v1 = pipeline.indexOf("final boolean v1 = bsLayerVisible;", snapshot);
        int e1 = pipeline.indexOf("final boolean e1 = blindSpotEnabled;", snapshot);
        int p2 = pipeline.indexOf("final int p2 = laneProgram;", snapshot);
        int v2 = pipeline.indexOf("final boolean v2 = bsLayerVisible;", snapshot);
        int e2 = pipeline.indexOf("final boolean e2 = blindSpotEnabled;", snapshot);
        int cmp = pipeline.indexOf("if (p1 != p2 || v1 != v2 || e1 != e2)", snapshot);
        int mismatch = pipeline.indexOf("return new CloseLabels(false, true);", snapshot);
        assertTrue(p1 > snapshot && v1 > p1 && e1 > v1);
        assertTrue("second pass must re-read the full tuple", p2 > e1 && v2 > p2 && e2 > v2);
        assertTrue("any field mismatch must report transitioning",
                cmp > e2 && mismatch > cmp);
        String http = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        assertTrue(http.contains("pipeline.getCloseLabels();"));
        assertTrue(http.contains("recordingStatus.put(\"bsCardShowing\", closeLabels.bsCardShowing);"));
        assertTrue(http.contains("recordingStatus.put(\"laneTransitioning\", closeLabels.laneTransitioning);"));
        assertFalse("the torn two-call emission must not return",
                http.contains("recordingStatus.put(\"bsCardShowing\", pipeline.isBlindSpotCardShowing());"));
        int camAdopt = overlay.indexOf(
                "if (recStatus.has(\"camViewActive\") && nowMs >= camCloseReconcileAfterMs");
        int bsAdopt = overlay.indexOf(
                "if (recStatus.has(\"bsCardShowing\") && nowMs >= bsCloseReconcileAfterMs");
        assertTrue(camAdopt > settle);
        assertTrue(bsAdopt > camAdopt);
        assertTrue(overlay.indexOf("&& pastEdgeSettle", camAdopt) < bsAdopt);
        assertTrue(overlay.indexOf("&& pastEdgeSettle", bsAdopt) > bsAdopt);
    }

    @Test
    public void closeButtonMatchesTheOperatorSpec() throws IOException {
        String overlay = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        // Operator spec (audit round 22): 32dp button, 4dp outside the card's
        // top-right edge. OUTSIDE is a hard constraint — the card's SurfaceControl
        // layer composites above every app window, so an inside ✕ is occluded
        // (tappable but invisible).
        assertTrue(overlay.contains("private static final int CLOSE_BTN_GAP_DP = 4;"));
        assertTrue(overlay.contains("private static final int CLOSE_BTN_SIZE_DP = 32;"));
        // Every sizing site derives from the constant — no stray hard-coded 40dp.
        assertFalse(overlay.contains("camDp(40)"));
        assertTrue(count(overlay, "camDp(CLOSE_BTN_SIZE_DP)") >= 3); // position + 2 builders
    }

    @Test
    public void retryValidityIsSessionScopedWithNoCompetingCancellationToken()
            throws IOException {
        String source = readRepositoryFile(HANDLER);
        // The SESSION is the single cancellation token. The old global generation
        // recorded CALL order while the session records INTENT order: a stale
        // show's late startCamViewArmRetry bumped the generation and killed the
        // NEWER show's valid loop — the stale loop then failed session validation
        // and NEITHER armed. No generation (or any second token) may return.
        assertFalse(source.contains("camViewArmGen"));
        assertFalse(source.contains("cancelCamViewArmRetry"));
        // The still-wanted gate is keyed to the loop's session...
        int gate = source.indexOf(
                "private static boolean camViewArmStillWanted(GpuSurveillancePipeline p,");
        int sessionCheck = source.indexOf(
                "if (p.getCamViewSessionId() != showSession) return false;", gate);
        assertTrue(gate >= 0);
        assertTrue(sessionCheck > gate);
        // ...and must NOT depend on the persisted camview.enabled: the show route's
        // intent write can fail (false return), and a config-gated loop then died
        // on pass 1 — silently dropping the deferred show. Every real cancel bumps
        // the session, so the session gate alone covers user intent.
        int gateEnd = source.indexOf("private static void startCamViewArmRetry(", gate);
        String gateBody = source.substring(gate, gateEnd);
        assertFalse("retry gate must not depend on persisted camview.enabled",
                gateBody.contains("isCamViewEnabled()"));
        String pipeline = readRepositoryFile(PIPELINE);
        assertFalse(pipeline.contains("cancelCamViewArmRetry"));
    }

    @Test
    public void wedgedDrainerFailureIsStickyAndBlocksRestartablePipelines() throws IOException {
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        // stopDrainerForCameraClose must drop the drainer reference only on a
        // VERIFIED exit: nulled up front, a SECOND stop saw no drainer, answered
        // "safe", and the caller closed the camera over the still-running thread.
        int method = recorder.indexOf("public boolean stopDrainerForCameraClose()");
        int fail = recorder.indexOf("return false;", method);
        int drop = recorder.indexOf("drainerThread = null;", method);
        assertTrue(method >= 0);
        assertTrue(fail > method);
        assertTrue("reference must be dropped only after the failure return path",
                drop > fail);

        String pipeline = readRepositoryFile(PIPELINE);
        // And a pipeline start while the trip-safe restart coordinator is still
        // checkpointing must refuse — a fresh camera/EGL stack over the wedged one
        // is the state the restart exists to escape.
        assertTrue(pipeline.contains("CameraDaemon.isProcessRestartPending()"));

        // ── Sibling stop/restart path (recording close) ──────────────────────
        // closeEventRecording → stopDrainerThread → startDrainerThread used to
        // bypass all of the above: an unverified stop nulled the reference, a
        // REPLACEMENT drainer re-raised the shared drainerRunning flag (which
        // reactivates the wedged original when its native call returns — two
        // drainers on one codec), and the camera-close guard then saw only the
        // healthy replacement and declared the close safe.
        // 1. stopDrainerThread must be sticky: reference dropped only on a
        //    verified exit, honest boolean result.
        int sibling = recorder.indexOf("private boolean stopDrainerThread(");
        int siblingExitBranch = recorder.indexOf("if (exited) {", sibling);
        int siblingDrop = recorder.indexOf("drainerThread = null;", siblingExitBranch);
        assertTrue(sibling >= 0);
        assertTrue(siblingExitBranch > sibling);
        assertTrue(siblingDrop > siblingExitBranch);
        // 2. The recording-close path must consume the result and bail out —
        //    no synchronous drain, no muxer stop, no drainer restart, and an
        //    escalation to the trip-safe restart.
        int close = recorder.indexOf("if (!stopDrainerThread(");
        int closeEscalate = recorder.indexOf(
                "requestProcessRestartPreservingTrip", close);
        assertTrue(close >= 0);
        assertTrue(closeEscalate > close);
        // 3. startDrainerThread must refuse while a wedged drainer is still
        //    referenced — a replacement would duplicate the dequeue loop.
        int start = recorder.indexOf("private void startDrainerThread()");
        int refuse = recorder.indexOf(
                "if (drainerThread != null && drainerThread.isAlive())", start);
        int firstSpawn = recorder.indexOf("drainerThread = new Thread(", start);
        assertTrue(start >= 0);
        assertTrue(refuse > start);
        assertTrue("the refusal must precede the spawn", refuse < firstSpawn);
    }

    @Test
    public void diskWriterTeardownIsStickyAndCombinedIntoTheVerdict() throws IOException {
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        // Same lost-reference bug class as the drainer: sticky stop, honest
        // boolean, refusal to replace a still-alive writer (shared
        // diskWriterRunning flag would reactivate it), and the verdict COMBINED
        // into stopDrainerThread's result — a writer wedged inside
        // writeSampleData makes muxer.stop() exactly as unsafe.
        int stop = recorder.indexOf("private boolean stopDiskWriterThread(");
        int fail = recorder.indexOf("return false;", stop);
        int drop = recorder.indexOf("diskWriterThread = null;", stop);
        assertTrue(stop >= 0);
        assertTrue(fail > stop);
        assertTrue("reference dropped only after the failure return path", drop > fail);
        assertTrue(recorder.contains("return exited && writerExited;"));
        int wStart = recorder.indexOf("private void startDiskWriterThread()");
        int wRefuse = recorder.indexOf(
                "if (diskWriterThread != null && diskWriterThread.isAlive())", wStart);
        int wSpawn = recorder.indexOf("diskWriterThread = new Thread(", wStart);
        assertTrue(wStart >= 0);
        assertTrue(wRefuse > wStart);
        assertTrue(wRefuse < wSpawn);
    }

    @Test
    public void wedgedTeardownIsTerminalAndTheBailPathTouchesNoLocks() throws IOException {
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        // The terminal latch must be set by every verified-stop failure and
        // checked at the trigger, so a new muxer can never be built over an
        // un-stopped one with no healthy workers behind it.
        assertTrue(count(recorder, "teardownWedged = true;") >= 3);
        int trigger = recorder.indexOf("public boolean triggerEventRecording(");
        int latchCheck = recorder.indexOf("if (teardownWedged)", trigger);
        int formatBarrier = recorder.indexOf("if (savedFormat == null)", trigger);
        assertTrue(trigger >= 0);
        assertTrue(latchCheck > trigger);
        assertTrue("terminal check must be the first refusal", latchCheck < formatBarrier);
        // The close-path bail must escalate WITHOUT acquiring muxerLock: a
        // writer wedged inside writeSampleData HOLDS that lock, and the old
        // flag-gating under it blocked the close thread forever BEFORE the
        // restart request could fire.
        int bail = recorder.indexOf("if (!stopDrainerThread(");
        int bailEnd = recorder.indexOf("return;", bail);
        String bailBody = recorder.substring(bail, bailEnd);
        assertFalse("bail path must not acquire muxerLock",
                bailBody.contains("synchronized (muxerLock)"));
        assertTrue(bailBody.contains("requestProcessRestartPreservingTrip"));
    }

    @Test
    public void releaseReportsWedgeEscalatesAndReplacementCreationAborts() throws IOException {
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        // release() can be the FIRST discovery of a wedge (nothing recording, so
        // the close-path escalation never ran): it must report honestly and
        // request the restart itself, and init() must reject a terminal instance.
        assertTrue(recorder.contains("public boolean release()"));
        int rel = recorder.indexOf("public boolean release()");
        int relEscalate = recorder.indexOf("requestProcessRestartPreservingTrip", rel);
        int relReturn = recorder.indexOf("return releaseClean;", rel);
        assertTrue(relEscalate > rel);
        assertTrue(relReturn > relEscalate);
        int init = recorder.indexOf("public void init() throws Exception {");
        int initReject = recorder.indexOf("if (teardownWedged)", init);
        assertTrue(init >= 0);
        assertTrue(initReject > init && initReject < recorder.indexOf("logger.info", init));
        // Both recovery callers must consume the verdict and abort replacement
        // creation — a fresh codec's healthy workers would hide the wedged
        // original from every close guard.
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        assertTrue(camera.contains("if (!localEncoder.release())"));
        String pipeline = readRepositoryFile(PIPELINE);
        assertTrue(pipeline.contains("if (!encoder.release())"));
    }

    @Test
    public void terminalLatchIsRecheckedUnderTheLockThatTeardownNowShares() throws IOException {
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        // The unlocked latch check is a fast path only: a camera-close teardown
        // can set the latch between it and the lock acquisition. The re-check is
        // sound only because stopDrainerForCameraClose serializes on the SAME
        // startStopLock (order startStopLock → drainerLock, matching
        // closeEventRecording's existing nesting).
        int trigger = recorder.indexOf("public boolean triggerEventRecording(");
        int triggerLock = recorder.indexOf("synchronized (startStopLock)", trigger);
        int recheck = recorder.indexOf("if (teardownWedged)", triggerLock);
        int writingCheck = recorder.indexOf("if (isWritingToFile)", triggerLock);
        assertTrue(triggerLock > trigger);
        assertTrue(recheck > triggerLock);
        assertTrue("locked re-check must precede the isWritingToFile branch",
                recheck < writingCheck);
        int close = recorder.indexOf("public boolean stopDrainerForCameraClose()");
        int closeOuter = recorder.indexOf("synchronized (startStopLock)", close);
        int closeInner = recorder.indexOf("synchronized (drainerLock)", close);
        assertTrue(close >= 0);
        assertTrue(closeOuter > close);
        assertTrue("startStopLock must be acquired before drainerLock",
                closeOuter < closeInner);
    }

    @Test
    public void startRollbackConsumesTheStopVerdictAndPipelineWedgeIsLocallySticky()
            throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        // The rollback must gate its recorder/encoder releases on the camera
        // stop verdict, and both failure sites must trip the INSTANCE-level
        // latch — the global restart-pending flag self-clears if the
        // coordinator fails, and only the local latch then stops a later
        // start() from building a second stack over the wedged one.
        int rollback = pipeline.indexOf("rollbackCameraClean = camera.stop();");
        int rollbackLatch = pipeline.indexOf("pipelineTeardownWedged = true;", rollback);
        int rollbackGate = pipeline.indexOf("if (rollbackCameraClean)", rollback);
        assertTrue(rollback >= 0);
        assertTrue(rollbackLatch > rollback);
        assertTrue(rollbackGate > rollback);
        // ≥2: the camera-stop failure sites (normal stop + rollback). Later
        // rounds added encoder-release and init discovery sites on top.
        assertTrue(count(pipeline, "pipelineTeardownWedged = true;") >= 2);
        int start = pipeline.indexOf("public void start(boolean autoStartRecording)");
        int startGate = pipeline.indexOf("if (pipelineTeardownWedged)", start);
        int startClaim = pipeline.indexOf("starting = true;", start);
        assertTrue(startGate > start);
        assertTrue("terminal gate must run before the start claim", startGate < startClaim);
    }

    @Test
    public void abortedCameraStopPropagatesAndGatesPipelineReleases() throws IOException {
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        // stop() must report an aborted/degraded teardown instead of returning
        // void-as-success — the restart coordinator is asynchronous, so the
        // pipeline would otherwise release recorder GL state and the encoder's
        // codec/input surface over still-live wedged native state.
        assertTrue(camera.contains("public boolean stop()"));
        String pipeline = readRepositoryFile(PIPELINE);
        int verdict = pipeline.indexOf("cameraStopClean = camera.stop();");
        int gate = pipeline.indexOf("if (cameraStopClean)", verdict);
        int release = pipeline.indexOf("recorder.release();", gate);
        assertTrue(verdict >= 0);
        assertTrue(gate > verdict);
        assertTrue("releases must be inside the clean-stop gate", release > gate);
    }

    @Test
    public void drainerGuardTakesSnapshotsNotFieldReads() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        // clearStreamingComponents() only nulls streamEncoder — it does not stop
        // its drainer. The yield/restart paths detach BEFORE the guard runs, so
        // the guard must receive snapshots taken before the detach or it silently
        // verifies only the main encoder.
        assertTrue(source.contains("private boolean stopEncoderDrainersBeforeCameraClose(String where,"));
        assertTrue(source.contains("stopEncoderDrainersBeforeCameraClose(\"yield\", encoder, yieldStreamEnc)"));
        assertTrue(source.contains("stopEncoderDrainersBeforeCameraClose(\"restart\", encoder, restartStreamEnc)"));
        // The snapshots must be taken before the corresponding detach.
        int yieldSnap = source.indexOf("yieldStreamEnc = streamEncoder;");
        int yieldClear = source.indexOf("clearStreamingComponents();", yieldSnap);
        assertTrue(yieldSnap >= 0);
        assertTrue(yieldClear > yieldSnap);
        int restartSnap = source.indexOf("restartStreamEnc = streamEncoder;");
        int restartClear = source.indexOf("clearStreamingComponents();", restartSnap);
        assertTrue(restartSnap >= 0);
        assertTrue(restartClear > restartSnap);
    }

    @Test
    public void wedgedDrainerAbortsCameraCloseAtEveryCloseSite() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        // Every closeCameraForPath must be preceded by a CHECKED drainer stop:
        // one helper definition plus one guarded call per close site (stop,
        // yield, restart, DI4 HAL-death release, DI4 half-open close).
        // Proceeding past a wedged drainer into the close is the FORTIFY
        // destroyed-mutex abort — it kills the process before the async
        // trip-safe restart coordinator can checkpoint the trip.
        assertTrue(count(source, "if (!stopEncoderDrainersBeforeCameraClose(") == 5);
        // The unchecked fire-and-forget pattern must not come back outside the
        // helper itself (2 = the helper's own two calls).
        assertTrue(count(source, ".stopDrainerForCameraClose()") == 2);
    }

    @Test
    public void retiringStreamEncoderIsVerifiedBeforeCameraCloseAndGatesReEnable()
            throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        // disableStreaming nulls the encoder fields and queues release()
        // asynchronously, so the camera-close guard can never see the retiring
        // encoder — whose drainer keeps dequeuing against the camera until the
        // release completes. stop() must verify the retirement BEFORE
        // camera.stop(), and enableStreaming must refuse a replacement while it
        // is pending or failed.
        int verify = pipeline.indexOf(
                "verifyRetiringStreamEncoderRelease(8000, \"stop\")");
        int close = pipeline.indexOf("cameraStopClean = camera.stop();");
        assertTrue(verify >= 0);
        assertTrue("verification must precede the camera close", verify < close);
        int enable = pipeline.indexOf("public void enableStreaming(");
        int reject = pipeline.indexOf("stream re-enable refused", enable);
        int internal = pipeline.indexOf("enableStreamingInternal(", enable);
        assertTrue(reject > enable);
        assertTrue("re-enable rejection must precede the install", reject < internal);
    }

    @Test
    public void retirementPlaceholderIsPublishedSynchronouslyAtDetach() throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        // Publication CONTRACT (the first revision published the executor future
        // from inside the GL-posted runnable — a slow runnable left the guard
        // reading null past the caller's 1s wait, silently bypassing it; and an
        // older future could overwrite a newer one): a placeholder is published
        // synchronously at detach, the eventual release only COMPLETES it, and
        // verification CAS-clears only the exact future it verified.
        // Both detach sites publish BEFORE any GL post:
        int disableSnap = pipeline.indexOf(
                "final HardwareEventRecorderGpu encoderRef = streamEncoder;");
        int disablePublish = pipeline.indexOf(
                "publishRetiringStreamEncoderRelease()", disableSnap);
        int disablePost = pipeline.indexOf("glHandler.post(", disableSnap);
        assertTrue(disableSnap >= 0);
        assertTrue(disablePublish > disableSnap);
        assertTrue("publication must precede the GL post", disablePublish < disablePost);
        int failSnap = pipeline.indexOf(
                "final HardwareEventRecorderGpu encLocal = streamEncoder;");
        int failPublish = pipeline.indexOf(
                "publishRetiringStreamEncoderRelease()", failSnap);
        int failPost = pipeline.indexOf("glH.post(", failSnap);
        assertTrue(failSnap >= 0);
        assertTrue(failPublish > failSnap && failPublish < failPost);
        // The runnable-side call only completes the placeholder — the old
        // publish-from-inside-the-runnable pattern must not come back.
        assertFalse(pipeline.contains("noteRetiringStreamEncoderRelease"));
        // Verification clears with CAS on the exact verified future, and a CAS
        // FAILURE loops to verify the newer future — returning true on a failed
        // CAS verified only the OLD future and let the camera close over the
        // unverified new encoder's drainer.
        int verifyDef = pipeline.indexOf("private boolean verifyRetiringStreamEncoderRelease(");
        int verifyLoop = pipeline.indexOf("while (true)", verifyDef);
        int cas = pipeline.indexOf(
                "if (retiringStreamEncoderRelease.compareAndSet(f, null))", verifyDef);
        assertTrue(verifyLoop > verifyDef);
        assertTrue(cas > verifyLoop);
        // Clean may only be answered when the verified future was still current.
        int casReturn = pipeline.indexOf("return true;", cas);
        assertTrue(casReturn > cas);
    }

    @Test
    public void streamEnableCannotReopenTheStopBarrier() throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        // stop() sets stopping=true and runs its unconditional disable barrier;
        // an enable admitted after that barrier would build a live
        // encoder+drainer that the camera close never re-checks. Two gates:
        // reject at entry while stopping, and re-check running && !stopping
        // after the auto-start (start(false) refuses by returning NORMALLY, so
        // its outcome must be re-checked, not assumed).
        int enable = pipeline.indexOf("public void enableStreaming(");
        int entryGate = pipeline.indexOf("if (stopping)", enable);
        int autoStart = pipeline.indexOf("start(false);", enable);
        int postGate = pipeline.indexOf("if (!running || stopping)", autoStart);
        int glCheck = pipeline.indexOf("Camera GL thread not initialized", autoStart);
        int internal = pipeline.indexOf("enableStreamingInternal(", enable);
        assertTrue(entryGate > enable && entryGate < autoStart);
        assertTrue(postGate > autoStart);
        assertTrue("post-start gate must precede the GL check and the install",
                postGate < glCheck && postGate < internal);
    }

    @Test
    public void inProgressStreamEnableIsVisibleToStop() throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        // The bounded GL-init wait must run HOLDING streamLifecycleLock (the
        // encoder + drainer are already live while streamingEnabled is still
        // false), and stop() must call disableStreaming unconditionally so it
        // serializes on that lock instead of skipping teardown in the window.
        assertFalse("the unlock-around-wait pattern must not come back",
                pipeline.contains("if (lockHeld) streamLifecycleLock.unlock();"));
        int stopStreaming = pipeline.indexOf(
                "// Disable streaming — stream encoder/scaler hold EGL surfaces");
        int stopCall = pipeline.indexOf("disableStreaming();", stopStreaming);
        String between = pipeline.substring(stopStreaming, stopCall);
        assertFalse("stop's disableStreaming must be unconditional",
                between.contains("if (streamingEnabled)"));
    }

    @Test
    public void everyPipelineEncoderReleaseConsumesTheVerdict() throws IOException {
        String pipeline = readRepositoryFile(PIPELINE);
        // A disk-writer wedge can be FIRST discovered by release(); every caller
        // must latch the pipeline terminal (or abort replacement creation) on
        // false, or the instance stays locally reusable when the global restart
        // flag self-clears. Sites: init() (throws), normal stop (latch),
        // rollback (latch), encoder-recreate (throws). No bare fire-and-forget
        // `encoder.release();` statement may remain.
        assertTrue(pipeline.contains(
                "prevReleaseClean = encoder.release();"));
        assertTrue(pipeline.contains(
                "if (encoder != null && !encoder.release())"));
        assertTrue(count(pipeline, "pipelineTeardownWedged = true;") >= 4);
        assertFalse("bare unconsumed encoder.release() has returned",
                pipeline.contains("{ encoder.release(); }"));
        // Exception paths latch too (a throw means teardown state is UNKNOWN),
        // including the public release(), which is reachable with a live
        // encoder when stop() early-returns.
        assertTrue(count(pipeline, "encoder.release threw") >= 3);
    }

    @Test
    public void panoramicStopVerdictIsStickyAndTheWarmupDoubleStopIsGone()
            throws IOException {
        String camera = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");
        // A per-call verdict could be erased by a second stop (a rejected
        // releaseGl post nulls an exited GL thread on call one; call two skips
        // the GL block and reports clean). The latch makes the first dirty
        // verdict permanent.
        assertTrue(camera.contains("stopVerdictWedged = true;"));
        assertTrue(camera.contains("return verdict;"));
        String pipeline = readRepositoryFile(PIPELINE);
        // The warmup-verify branch must not stop the camera itself — the catch
        // performs the one rollback stop and consumes its verdict once.
        int warmup = pipeline.indexOf(
                "Camera failed to reach running state within warmup window");
        int warmupBranch = pipeline.lastIndexOf("camera.isRunning() false", warmup);
        String branch = pipeline.substring(warmupBranch, warmup);
        // Match the CALL (statement with semicolon) — the branch's explanatory
        // comment legitimately names the method.
        assertFalse("warmup branch must not call camera.stop()",
                branch.contains("camera.stop();"));
    }

    private static int count(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("Could not locate " + relativePath);
    }
}
