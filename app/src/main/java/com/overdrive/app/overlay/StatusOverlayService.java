package com.overdrive.app.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.overdrive.app.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating status overlay service.
 *
 * Shows a small draggable pill on top of all apps indicating whether
 * configured features are actually running or not.
 *
 * Rules:
 * - Only shows items that are CONFIGURED (recording mode != NONE, trip analytics enabled)
 * - Each item shows a tinted active/inactive icon next to its label
 * - Tapping a not-running item restarts it (it's configured, so it should be running)
 * - Hides entirely if nothing is configured
 * - Gracefully handles missing SYSTEM_ALERT_WINDOW — just stops itself
 */
public class StatusOverlayService extends Service {

    private static final String TAG = "StatusOverlay";
    private static final String CHANNEL_ID = "status_overlay";
    private static final int NOTIFICATION_ID = 9001;
    private static final long POLL_INTERVAL_MS = 3000;
    private static final long POLL_INTERVAL_ACC_OFF_MS = 10000; // Slower polling when ACC is off

    // Persisted overlay position
    private static final String PREFS_NAME = "status_overlay_prefs";
    private static final String PREF_POS_X = "pos_x";
    private static final String PREF_POS_Y = "pos_y";
    // Last non-NONE mode the user picked from the overlay's action bar.
    // Used so a long-press quick-toggle from OFF returns to whatever the
    // user was previously in (CONT/DRIVE/PROX) instead of always defaulting
    // to CONTINUOUS. Stored in app-side prefs only — this is a UX
    // shortcut, the daemon's authoritative mode lives in the unified
    // config and gets set via setRecordingMode TCP.
    private static final String PREF_LAST_NON_NONE_MODE = "last_non_none_mode";
    private static final int DEFAULT_POS_X = 20;
    private static final int DEFAULT_POS_Y = 100;
    // Auto-collapse the expanded action bar after this much idle time.
    // Long enough for an unhurried tap on the desired chip while parked
    // and short enough that the pill returns to its glance footprint
    // before the user looks back at the road.
    private static final long EXPAND_AUTOCOLLAPSE_MS = 5000;

    private WindowManager windowManager;
    private View overlayView;
    // Last rendered state signature — the poll re-runs updateUI every 3s (ACC-on)
    // and used to re-set the pill icon/label/color + emit a 12-field Log.d EVERY
    // tick even when nothing changed. We skip the per-tick verbose log when the
    // signature is unchanged (the view writes are idempotent + cheap, but the log
    // concat fired at the poll cadence and mirrored the pillContainer redraw the
    // user saw in logcat). Reset to null on (re)create so a fresh pill still logs.
    private String lastUiLogSig = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Views
    private LinearLayout recContainer;
    private LinearLayout tripContainer;
    private LinearLayout micContainer;
    private LinearLayout actionBar;
    private ImageView ivRecIcon;
    private ImageView ivTripIcon;
    private ImageView ivMicIcon;
    private ImageView btnModeOff;
    private ImageView btnModeContinuous;
    private ImageView btnModeDrive;
    private ImageView btnModeProximity;
    private TextView tvRecLabel;
    private TextView tvTripLabel;
    private TextView tvMicLabel;

    // Tap-to-expand state. The action bar shows mode quick-actions and
    // auto-collapses after EXPAND_AUTOCOLLAPSE_MS. Touched only from the
    // main thread so a plain boolean is sufficient.
    private boolean actionBarExpanded = false;
    private final Runnable autocollapseRunnable = () -> setActionBarExpanded(false);

    // State
    private volatile String configuredMode = "NONE";
    private volatile boolean isRecording = false;
    // Compound recording truth exported by the daemon alongside isRecording
    // (HttpServer recordingStatus.modeActive / .pipelineRunning). isRecording
    // is the raw recorder.isRecording() boolean, which is momentarily FALSE
    // during the deferred-record window (cold start / ACC-on / hardReset:
    // pendingRecordingPrefix set, recordingMode=true, but recorder hasn't
    // latched yet) and on a transient writer-abort. modeActive stays TRUE
    // across that window (RecordingModeManager keeps it true while
    // pipeline.isRunning() && (isRecording || pendingRecordingPrefix!=null)),
    // so the pill can show "active" instead of a false red REC for the
    // multi-second window a poll tick would otherwise sample. See
    // RecordingModeManager.java:487-488.
    private volatile boolean modeActive = false;
    private volatile boolean pipelineRunning = false;
    // Daemon-reported wedge flag: modeActive is true but the encoder is
    // structurally stuck (nothing being written). When set, the deferred-record
    // window must NOT paint green — the pill falls through to the red fault
    // branch so a stuck CONTINUOUS/DRIVE activation stays visible rather than
    // being masked by modeActive. Defaults false (healthy / older daemon).
    private volatile boolean recordingWedged = false;
    private volatile boolean tripEnabled = false;
    private volatile boolean tripActive = false;
    private volatile boolean daemonReachable = false;
    private volatile String currentGear = "P";
    private volatile boolean accOn = false;
    // User-controlled audio toggle (recording.audioEnabled in unified config)
    private volatile boolean audioEnabledConfig = false;

    // Single shared Runnable for the poll-loop reschedule. Used so we
    // can call `handler.removeCallbacks(pollRunnable)` to drop the
    // pending poll without nuking unrelated main-thread runnables (the
    // rejection Toast, autocollapse, in-flight updateUI posts).
    // Method-references like `this::pollStatus` allocate a new lambda
    // instance per call, so removeCallbacks(method-ref) wouldn't match;
    // the field gives a stable identity.
    private final Runnable pollRunnable = this::pollStatus;

    // Generation counter. Bumped every time we re-issue pollStatus
    // from outside the executor (applyMode, onStartCommand re-entry).
    // The executor's tail-reschedule reads this counter when its tick
    // started; if it's been bumped since, the in-flight tick skips
    // its own postDelayed so we never spawn parallel poll chains.
    private final java.util.concurrent.atomic.AtomicInteger pollGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // Edge-trigger fast-poll: when ACC flips OFF→ON, run the poll loop at 1s
    // for 30s so we minimize the audio-capture-start latency at trip start.
    // Without this, ACC turning on can take up to POLL_INTERVAL_ACC_OFF_MS
    // (10s) to detect, and a single mic-claim retry then adds another 5s of
    // back-off — together that's a 13s gap of silent audio at the very
    // moment the user begins driving.
    private volatile boolean previousAccOn = false;
    private volatile long fastPollUntilElapsedMs = 0;
    private static final long FAST_POLL_INTERVAL_MS = 1000;
    private static final long FAST_POLL_WINDOW_MS = 30_000;

    // Fully-idle slow poll. The service can't just stopSelf() when the overlay
    // is disabled — it also drives cabin-audio capture reconcile (see
    // reconcileAudioCapture) and ACC-edge detection. But when BOTH the overlay
    // has nothing to show AND audio capture isn't configured, the 3s (ACC-on) /
    // 10s (ACC-off) /status poll + JSON parse + reconcile is pure wasted CPU +
    // wakeups on the shared little cores, running 24/7 for a window that's never
    // drawn. In that case poll at this slow cadence instead. Set by updateUI
    // (which knows anythingToShow + audioEnabledConfig), read at the reschedule.
    // An ACC OFF→ON edge still re-arms fast-poll on the next slow tick; the user
    // has disabled the overlay, so ~30s reaction latency there is acceptable.
    private volatile boolean overlayFullyIdle = false;
    private static final long IDLE_POLL_INTERVAL_MS = 30_000;

    // Optimistic-mode guard. When the user taps a mode chip, we flip
    // configuredMode locally before the daemon has confirmed. An
    // already-in-flight pollStatus tick (already past fetchStatus, about
    // to enter parseStatus) would otherwise overwrite our optimistic
    // value with the pre-change daemon state and the chip selection
    // would visibly bounce. Stamp the moment we flipped; parseStatus
    // ignores its own configuredMode read while still inside the
    // window so the user-driven value sticks.
    private volatile long optimisticModeUntilElapsedMs = 0;
    private static final long OPTIMISTIC_MODE_WINDOW_MS = 1500;

    // Coalesce rapid-fire chip taps. Mashing the same chip would
    // otherwise queue N TCP jobs serially on the polling executor,
    // and a wedged daemon could starve actual status polls for
    // seconds. We drop duplicate setRecordingMode calls within this
    // window if the requested mode is the same as the in-flight
    // request; different-mode taps still go through (user is
    // changing their mind).
    private volatile String inflightModeRequest = null;
    private volatile long inflightModeRequestMs = 0;
    private static final long MODE_REQUEST_DEDUP_WINDOW_MS = 500;

    // Track the last AppAudioCaptureController.start() that returned false so
    // the MIC pill can paint RED with a "mic claimed / unavailable" hint.
    // start() back-off lasts ~5s; we hold the hint for up to 30s so the user
    // sees an explanation rather than a silent failure.
    private volatile long lastAudioStartFailureMs = 0;
    private static final long AUDIO_FAILURE_HINT_WINDOW_MS = 30_000;

    // Grace period: don't flicker the overlay on transient poll failures.
    // The daemon may be restarting, the HTTP server may be briefly busy, etc.
    // Only treat the daemon as truly gone after UNREACHABLE_THRESHOLD consecutive failures.
    private volatile int consecutivePollFailures = 0;
    private static final int UNREACHABLE_THRESHOLD = 3; // ~9 seconds at 3s poll interval
    // Track whether we ever had something to show (so we keep the window during blips)
    private volatile boolean hadContentBefore = false;

    // Drag support
    private float initialTouchX, initialTouchY;
    private int initialX, initialY;
    private boolean isDragging = false;
    private static final int DRAG_THRESHOLD = 10;
    private WindowManager.LayoutParams layoutParams;

    // ── Camera-view CLOSE button (folded in here so it shares THIS service's
    // foreground notification — no second notification — and this service's
    // reliable lifecycle: it's started from MainActivity AND DaemonKeepaliveService,
    // so its receiver is live whenever a camera view can be opened, even from a
    // keymap/automation with the UI never launched).
    //
    // The camera view renders into a daemon-owned SurfaceControl layer with NO input
    // channel, so a tappable close must live in an app overlay window. The daemon
    // broadcasts an open/close edge (event-driven, zero poll/GPU) and we attach/detach
    // a small ✕ window. Separate window + flag from the status pill so the two never
    // interfere. ──
    public static final String ACTION_CAMVIEW_STATE = "com.overdrive.app.action.CAMVIEW_STATE";

    // ── Blind-spot close button ────────────────────────────────────────────
    // The blind-spot card renders into the SAME daemon-owned SurfaceControl lane as the
    // camera view (no input channel), so its ✕ is a second app overlay window driven the
    // same way: a daemon open/close edge broadcast + a /status poll reconcile. Kept fully
    // separate from the camview ✕ (own window, flag, receiver) because the two views can
    // never be on screen together but their lifecycles are independent. Tapping it POSTs
    // /api/bs/hide, which DISMISSES the current card WITHOUT disabling the feature — the
    // next turn signal re-shows it. Head-unit only (same cluster limit as camview).
    public static final String ACTION_BS_STATE = "com.overdrive.app.action.BS_STATE";

    // ── Instant-replay clip segment ───────────────────────────────────────
    // Edge signal from the daemon's ManualClipService (`am broadcast`, same
    // shell/UID-2000 → app pattern as ACTION_CAMVIEW_STATE). The /status
    // poll's `replay` block is the catch-up truth for missed broadcasts.
    public static final String ACTION_REPLAY_STATE = "com.overdrive.app.action.REPLAY_STATE";
    /** How long the terminal saved/failed color holds before reverting to idle gray. */
    private static final long REPLAY_RESULT_HOLD_MS = 5000;
    /** Stale-guard for a "recording" with no terminal signal on either channel:
     *  max window is 62s + export headroom, so past this it reads as idle. */
    private static final long REPLAY_RECORDING_STALE_MS = 120_000;
    private LinearLayout replayContainer;
    private ImageView ivReplayIcon;
    private TextView tvReplayLabel;
    // State pair mirrors the daemon's (state, elapsedRealtime-of-transition).
    // Written by the broadcast receiver (main thread) and parseStatus (poll
    // executor); consumed inside updateUI() so the idempotent repaint always
    // derives color from state+age rather than a one-shot view write.
    private volatile String replayState = "idle";     // idle|recording|saved|failed
    private volatile long replayEventAtMs = 0;        // SystemClock.elapsedRealtime
    private volatile boolean replayConfigured = false;
    // Repaint tick for the 5s revert boundary; state itself decays via age.
    private final Runnable replayRevertRunnable = () -> updateUI();
    private boolean replayReceiverRegistered = false;
    private final android.content.BroadcastReceiver replayStateReceiver =
            new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_REPLAY_STATE.equals(intent.getAction())) return;
            String state = intent.getStringExtra("state");
            if (state == null || state.isEmpty()) return;
            adoptReplayState(state, android.os.SystemClock.elapsedRealtime(), 0);
            // A replay lifecycle is in motion — fast-poll so the poll channel
            // confirms the next transition within ~1s even if its broadcast
            // is dropped.
            fastPollUntilElapsedMs =
                    android.os.SystemClock.elapsedRealtime() + FAST_POLL_WINDOW_MS;
            handler.post(() -> updateUI());
        }
    };
    private android.view.View camCloseButton;
    private WindowManager.LayoutParams camCloseParams;
    private boolean camCloseAttached = false;
    // Suppresses the poll-driven ✕ reconcile briefly after a user tap, so a poll landing
    // between the tap and the daemon actually clearing camViewActive can't flicker the
    // button back. The tap's own failure path re-shows it deliberately if the hide didn't
    // land, and the reconcile resumes after this window either way.
    private volatile long camCloseReconcileAfterMs = 0L;
    // Longer than POLL_INTERVAL_MS (3000): the reconcile gate is evaluated when a payload
    // is PARSED, not when it was fetched, so a request already in flight at tap time can
    // deliver a pre-tap snapshot (camViewActive=true) after the window lapses and flicker
    // the button back. Covering a full poll period plus the request's own latency means
    // any snapshot parsed after the window was necessarily fetched after the tap.
    private static final long CAM_CLOSE_TAP_SETTLE_MS = 4500L;
    private boolean camCloseReceiverRegistered = false;
    private final android.content.BroadcastReceiver camCloseReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_CAMVIEW_STATE.equals(intent.getAction())) return;
            boolean active = intent.getBooleanExtra("active", false);
            // Head-unit only — the cluster is a separate display we can't overlay.
            String target = intent.getStringExtra("target");
            boolean headUnit = target == null || !"cluster".equals(target);
            camViewWantsClose = active && headUnit;
            closeEdgeAtMs = android.os.SystemClock.elapsedRealtime();
            // Adopt the rect carried on the show edge BEFORE reconciling, so the ✕ is
            // placed clear of the card on its very first frame rather than waiting for
            // the next /status poll to correct it.
            adoptLaneRectFromIntent(intent);
            reconcileCloseButtons();
        }
    };

    // Blind-spot ✕ — parallel to the camview one above. See ACTION_BS_STATE.
    private android.view.View bsCloseButton;
    private WindowManager.LayoutParams bsCloseParams;
    private boolean bsCloseAttached = false;
    // Suppresses the poll reconcile briefly after a tap (same rationale + duration as
    // CAM_CLOSE_TAP_SETTLE_MS): a /status snapshot fetched before the tap but parsed
    // after it would otherwise flicker the ✕ back.
    private volatile long bsCloseReconcileAfterMs = 0L;
    private boolean bsCloseReceiverRegistered = false;
    private final android.content.BroadcastReceiver bsCloseReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_BS_STATE.equals(intent.getAction())) return;
            boolean active = intent.getBooleanExtra("active", false);
            // Head-unit only — the cluster is a separate display we can't overlay.
            String target = intent.getStringExtra("target");
            boolean headUnit = target == null || !"cluster".equals(target);
            bsCardWantsClose = active && headUnit;
            closeEdgeAtMs = android.os.SystemClock.elapsedRealtime();
            // See camCloseReceiver: adopt the show-edge rect before reconciling.
            adoptLaneRectFromIntent(intent);
            reconcileCloseButtons();
        }
    };

    // ── Single-slot arbitration for the two ✕ buttons ──────────────────────
    // The camera-view ✕ and blind-spot ✕ share the SAME top-right slot, and the two
    // programs share ONE SurfaceControl lane — so at most ONE view is ever on screen,
    // and at most one ✕ may be attached. These remember the latest head-unit-adjusted
    // truth from each channel (the two receivers + the /status poll); reconcileClose-
    // Buttons() turns them into a single visible button under blind-spot priority.
    // When blind-spot is DISABLED, bsCardWantsClose is always false, so the camera-view
    // ✕ behaves byte-identically to before this arbitration existed.
    private volatile boolean camViewWantsClose = false;
    private volatile boolean bsCardWantsClose = false;

    // ── Card rect, so the ✕ is never composited UNDER the card ─────────────
    // The card renders on a daemon-owned SurfaceControl layer at Integer.MAX_VALUE - 1,
    // which is ABOVE every app window — including a TYPE_APPLICATION_OVERLAY. So a ✕ that
    // OVERLAPS the card is drawn behind it and simply cannot be seen (it is still
    // tappable: the SC layer has no input channel, so touches fall through). The fixed
    // top-right slot sat fully INSIDE the blind-spot card's default rect (corner "tr",
    // 24px inset, 40% width), which is why the blind-spot ✕ appeared to be missing
    // entirely. The camera view escaped it only because its default corner is "center".
    // These hold the daemon's live lane rect in PANEL PX (null = unknown → fixed corner).
    private volatile int[] laneRectPx = null;
    // Monotonic (elapsedRealtime) instant of the last SHOW-EDGE rect adoption
    // (adoptLaneRectFromIntent). The /status poll may only overwrite the rect from a
    // response FETCHED AFTER this instant PLUS a settle window: a response fetched
    // before the edge carries the PREVIOUS program's rect, and one fetched shortly
    // AFTER the edge can still carry stale daemon truth — the daemon's shared rect
    // and target fields only converge on the arbiter's transition tick (≤250ms), so
    // an in-window response could jostle the ✕ or NULL the rect (a lagging cluster
    // target suppresses laneRect entirely). Real rect changes always ride their own
    // edge broadcast, and the poll is only the catch-up for DROPPED edges (3s/30s
    // cadence), so deferring poll adoption 1.5s past a RECEIVED edge costs nothing.
    // 0 = no edge rect yet (poll adopts freely). Same stale-snapshot discipline as
    // the tap-settle windows above.
    private volatile long laneRectEdgeAdoptedAtMs = 0L;
    private static final long LANE_RECT_EDGE_SETTLE_MS = 1500L;
    // Monotonic instant of the last ✕ show/hide EDGE broadcast (either channel).
    // The /status poll may only overwrite the per-channel wants-close flags from a
    // response FETCHED after this instant plus the settle window. The daemon's
    // bsCardShowing keys on laneProgram, which enableCamView wipes to PROG_NONE
    // while the BLIND-SPOT card may still be on screen (its own javadoc documents
    // that the shared layer's visibility flag cannot distinguish the programs) —
    // a poll built in that ≤250ms window reports bsCardShowing=false +
    // camViewActive=true, and adopting it swapped the visible blind-spot ✕ for
    // the camera ✕ until the NEXT poll (3s/30s) corrected it; tapping that ✕
    // hid the camview request instead of the card the user was looking at. The
    // edges are authoritative and immediate; the poll is only the catch-up for
    // DROPPED edges, so deferring its adoption 1.5s past a received edge is free.
    private volatile long closeEdgeAtMs = 0L;
    private static final long CLOSE_EDGE_SETTLE_MS = 1500L;
    // ✕ spec (operator, audit round 22): 32dp button, 4dp outside the card's
    // top-right edge. OUTSIDE is a hard constraint, not a preference — the card's
    // SurfaceControl layer composites above every app window, so an inside ✕ is
    // occluded (tappable but invisible). positionCloseParams' preference cascade
    // already anchors at the top-right-outside corner and only falls back
    // (left/below/inner) when the card is flush against screen edges.
    /** Gap between the card edge and the ✕, in dp. */
    private static final int CLOSE_BTN_GAP_DP = 4;
    /** ✕ button diameter, in dp. Every sizing site derives from this. */
    private static final int CLOSE_BTN_SIZE_DP = 32;

    /**
     * Place the ✕ params just OUTSIDE the card's rect so the SurfaceControl layer can't
     * occlude it, preferring the card's top-right-outside corner and falling back through
     * left / below when the card is flush with a screen edge. Params use TOP|END gravity,
     * so x grows leftward from the right edge and y downward from the top.
     *
     * <p>No rect (or a card that fills the panel) keeps the pre-existing fixed inset.
     */
    private void positionCloseParams(WindowManager.LayoutParams p) {
        if (p == null) return;
        final int gap = camDp(CLOSE_BTN_GAP_DP);
        final int size = camDp(CLOSE_BTN_SIZE_DP);
        final int inset = camDp(16);
        int[] r = laneRectPx;
        if (r == null) { p.x = inset; p.y = inset; return; }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int panelW = dm.widthPixels, panelH = dm.heightPixels;
        int cardL = r[0], cardT = r[1], cardR = r[0] + r[2], cardB = r[1] + r[3];
        // TOP|END x is measured from the RIGHT edge, so a right-anchored card needs the
        // button pushed further left than the card's own right margin.
        int xFromRight = Math.max(0, panelW - cardR);
        // A card can legitimately extend PAST a panel edge: presetRect derives the height
        // from the width at a fixed 4:3, so a large sizePct on a 16:9 panel overflows
        // vertically (90% → 1296px tall on a 1080px panel, cardT negative). Aligning the
        // ✕ to a negative cardT put it OFF SCREEN — worse than the occlusion this fixes.
        // So clamp both axes to the panel at the end, whichever branch was taken.
        if (cardT >= size + gap) {                       // room ABOVE the card
            p.x = xFromRight;
            p.y = cardT - size - gap;
        } else if (panelW - cardR >= size + gap) {       // room RIGHT of the card
            p.x = xFromRight - size - gap;
            p.y = cardT;
        } else if (cardL >= size + gap) {                // room LEFT of the card
            p.x = (panelW - cardL) + gap;
            p.y = cardT;
        } else if (panelH - cardB >= size + gap) {        // room BELOW the card
            p.x = xFromRight;
            p.y = cardB + gap;
        } else {
            // Card effectively fills the panel: overlap is unavoidable, so sit at the
            // card's inner top-right. Still under the layer, but this is the degenerate
            // near-full-screen case rather than the default one.
            p.x = xFromRight + gap;
            p.y = cardT + gap;
        }
        // Keep the whole button on screen (x is inset from the RIGHT edge, y from the top).
        p.x = Math.max(0, Math.min(p.x, panelW - size));
        p.y = Math.max(0, Math.min(p.y, panelH - size));
    }

    /**
     * Adopt the card rect carried on a show-edge broadcast. Absent extras (an older daemon,
     * or a hide edge) leave the remembered rect untouched — the /status poll remains the
     * catch-up channel, and a null rect just means "use the fixed corner".
     */
    private void adoptLaneRectFromIntent(Intent intent) {
        if (intent == null || !intent.hasExtra("rectW")) return;
        int w = intent.getIntExtra("rectW", 0), h = intent.getIntExtra("rectH", 0);
        if (w <= 0 || h <= 0) return;   // degenerate: keep whatever we had
        laneRectPx = new int[]{ intent.getIntExtra("rectX", 0),
                                intent.getIntExtra("rectY", 0), w, h };
        // Stamp the adoption so an older in-flight /status response (fetched before
        // this edge, parsed after) cannot overwrite the fresh rect with a stale one.
        laneRectEdgeAdoptedAtMs = android.os.SystemClock.elapsedRealtime();
    }

    /** Re-apply {@link #positionCloseParams} to whichever ✕ is attached (main thread). */
    private void repositionAttachedCloseButtons() {
        handler.post(() -> {
            if (windowManager == null) return;
            try {
                if (bsCloseAttached && bsCloseButton != null && bsCloseParams != null) {
                    positionCloseParams(bsCloseParams);
                    windowManager.updateViewLayout(bsCloseButton, bsCloseParams);
                }
                if (camCloseAttached && camCloseButton != null && camCloseParams != null) {
                    positionCloseParams(camCloseParams);
                    windowManager.updateViewLayout(camCloseButton, camCloseParams);
                }
            } catch (Exception e) {
                Log.w(TAG, "reposition ✕ failed: " + e.getMessage());
            }
        });
    }

    /**
     * Resolve which single ✕ (if any) is attached, from the remembered per-channel
     * state, under BLIND-SPOT PRIORITY: when the BS card owns the head-unit lane its ✕
     * takes the slot and the camera-view ✕ (masked, not rendering) is suppressed. The
     * two {@code setXCloseVisible} calls are idempotent + attach-state-guarded, so
     * calling this on every edge/poll costs nothing when nothing changed. Mirrors the
     * daemon arbiter, where BS priority means only one program drives the lane at a time.
     */
    private void reconcileCloseButtons() {
        // Bail once the service is torn down. Both tap handlers re-drive this from the
        // poll executor (handler.post) on a failed hide POST — a call that can outlive
        // the service, landing after onDestroy() ran removeCallbacksAndMessages(null) +
        // detached the windows. Re-adding a view to a dead service would leak/throw. The
        // executor+HTTP callers are off-main, so read the flag here (it's the single
        // chokepoint) rather than only in onDestroy's window teardown. The receivers and
        // the in-tick poll path run on main, where running is already false post-destroy.
        if (!running.get()) return;
        boolean bsVisible = bsCardWantsClose;
        boolean camVisible = camViewWantsClose && !bsCardWantsClose;
        setBsCloseVisible(bsVisible);
        setCamCloseVisible(camVisible);
    }


    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startOverlayForeground();

        // "Vehicle ON only" parked-shutdown gate: the status overlay polls /status every
        // 10s against the now-dead daemon and re-arms itself via AlarmManager on task
        // removal. If the stack was terminated for the parked window, stop and stay down
        // until ACC-on recovery clears the marker. onOnly-guarded (fail-open) so onAndOff
        // is unaffected; recoveryInProgress guard avoids self-stopping on the recovery edge.
        try {
            if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()
                    && new java.io.File(com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists()
                    && !com.overdrive.app.ui.daemon.DaemonStartupManager.getRecoveryInProgress()) {
                Log.w(TAG, "onOnly + parked-shutdown marker present — stopping status overlay");
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Throwable t) {
            Log.w(TAG, "parked-marker gate failed (" + t.getMessage() + ") — proceeding");
        }

        if (!OverlayPermissionChecker.isGranted(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Don't create overlay window yet — wait for first poll to confirm
        // there's something to show. This avoids adding an empty overlay window
        // that can interfere with GPU rendering on BYD head units.
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        // Arm the camera-view close-button receiver once (idempotent across restarts).
        registerCamCloseReceiver();
        // Arm the blind-spot close-button receiver once (idempotent across restarts).
        registerBsCloseReceiver();
        // Arm the instant-replay state receiver once (idempotent across restarts).
        registerReplayStateReceiver();

        // Theme refresh — caller flipped the app's day/night setting.
        // Rebuild the overlay against the new uiMode. rebuildOverlay() also
        // re-fires pollStatus() so the pill repaints; return early so the
        // standard start path doesn't double-poll. If the service was
        // freshly created by this same start (running == false) we
        // still need to arm the poll loop — without it, every poll
        // would short-circuit at running.get() and the pill would
        // never appear. startPolling() is idempotent against the same
        // service instance because it sets running=true; rebuildOverlay
        // already kicked rescheduleImmediatePoll, so its postDelayed
        // covers the actual cadence.
        if (intent != null && ACTION_REFRESH_THEME.equals(intent.getAction())) {
            Log.i(TAG, "ACTION_REFRESH_THEME — rebuilding overlay");
            if (!running.get()) running.set(true);
            rebuildOverlay();
            return START_STICKY;
        }
        if (!running.get()) {
            startPolling();
        } else {
            // Re-entry while we're already running: MainActivity is asking
            // us to refresh. Drop the pending pollStatus reschedule and
            // post a fresh one. rescheduleImmediatePoll bumps the
            // generation counter so an in-flight executor tick skips
            // its own tail-reschedule rather than spawning a parallel
            // poll chain. Pre-fix this used removeCallbacksAndMessages(null)
            // which collaterally wiped the autocollapse runnable and any
            // queued Toast / updateUI posts on the same handler.
            rescheduleImmediatePoll();
        }

        return START_STICKY;
    }

    /**
     * Enter the foreground with an explicit service type so the platform
     * treats us as a long-running special-use service. Without passing the
     * type on Android 14+, the system can terminate the process along with
     * the Activity task, which is what makes the pill disappear on app close.
     */
    private void startOverlayForeground() {
        Notification notification = buildNotification();
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground with type failed, falling back: " + e.getMessage());
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Camera-view close button ──────────────────────────────────────────

    /**
     * Adopt a replay transition from either channel. The broadcast is the
     * low-latency edge (eventAt = now, fetchStartMs = 0); the poll
     * reconstructs the event time from the daemon's {@code stateAgeMs}
     * (±HTTP jitter) and passes the elapsedRealtime at which the /status
     * fetch STARTED.
     *
     * <p>Two rules resolve ordering:
     * <ol>
     *   <li><b>Authoritative snapshot:</b> a poll whose fetch began after our
     *   newest local event (+jitter) read the daemon's state pair strictly
     *   later than anything we know — adopt it unconditionally. This is the
     *   self-heal for every missed/late/out-of-order broadcast (a dropped
     *   "saved" on a fast clip, a stalled "recording" delivered after its
     *   terminal event, a re-press right after a rejection): within one poll
     *   tick the display converges on daemon truth.
     *   <li><b>Edge guards:</b> samples that are NOT authoritative (broadcast
     *   racing a poll sampled before it) go through staleness guards. The
     *   lifecycle is monotonic per clip (recording → saved|failed), so a
     *   "recording" whose start predates the terminal event we already know
     *   is stale, while a genuinely NEW clip (started after that terminal
     *   event) still passes — this keeps a sub-500ms accept→export cycle
     *   from losing its blue.
     * </ol>
     * Synchronized: called from the main thread (receiver) and the poll
     * executor; the guards are check-then-act over the state pair.
     */
    private synchronized void adoptReplayState(String state, long eventAtMs, long fetchStartMs) {
        final long JITTER_MS = 500;
        boolean authoritative = fetchStartMs > 0
                && fetchStartMs > replayEventAtMs + JITTER_MS;
        if (!authoritative) {
            String cur = replayState;
            if (state.equals(cur)) {
                // Same state: only a meaningfully newer event refreshes the
                // timestamp (a repeated failed press extends the red hold; a
                // poll re-sampling the same transition does not).
                if (eventAtMs <= replayEventAtMs + JITTER_MS) return;
            } else if ("recording".equals(state)
                    && ("saved".equals(cur) || "failed".equals(cur))
                    && eventAtMs <= replayEventAtMs + JITTER_MS) {
                // Stale pre-terminal sample (or an out-of-order broadcast
                // pair): this recording phase started before the terminal
                // event we already know about — not a new clip.
                return;
            } else if (eventAtMs < replayEventAtMs - JITTER_MS) {
                // Cross-state sample older than what we already display.
                return;
            }
        }
        replayState = state;
        replayEventAtMs = eventAtMs;
        // Arm the repaint at the hold boundary for terminal states so the
        // 5s revert lands on time instead of on the next poll tick.
        handler.removeCallbacks(replayRevertRunnable);
        if ("saved".equals(state) || "failed".equals(state)) {
            long remaining = REPLAY_RESULT_HOLD_MS
                    - (android.os.SystemClock.elapsedRealtime() - eventAtMs);
            handler.postDelayed(replayRevertRunnable, Math.max(0, remaining) + 100);
        }
    }

    /**
     * Derive what the clip segment shows RIGHT NOW from the state pair.
     * Terminal states decay to idle after {@link #REPLAY_RESULT_HOLD_MS};
     * a "recording" with no terminal signal on either channel decays via
     * {@link #REPLAY_RECORDING_STALE_MS} so a lost daemon can't pin the
     * segment green forever.
     */
    private String computeReplayDisplay() {
        String state = replayState;
        long age = android.os.SystemClock.elapsedRealtime() - replayEventAtMs;
        if ("recording".equals(state)) {
            return age <= REPLAY_RECORDING_STALE_MS ? state : "idle";
        }
        if (("saved".equals(state) || "failed".equals(state))
                && age <= REPLAY_RESULT_HOLD_MS) {
            return state;
        }
        return "idle";
    }

    /** Register the replay-state broadcast receiver once — same exported
     *  contract as {@link #registerCamCloseReceiver()} (daemon sender is
     *  shell/UID-2000). */
    private void registerReplayStateReceiver() {
        if (replayReceiverRegistered) return;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_REPLAY_STATE);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(replayStateReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(replayStateReceiver, f);
            }
            replayReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "replayState receiver register failed: " + t.getMessage());
        }
    }

    /** Register the camview-state broadcast receiver once. The sender is the daemon
     *  (shell/UID-2000) via `am broadcast`, so on API 33+ the receiver must be exported;
     *  on the API-29 head unit the plain register path is used. */
    private void registerCamCloseReceiver() {
        if (camCloseReceiverRegistered) return;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_CAMVIEW_STATE);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(camCloseReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(camCloseReceiver, f);
            }
            camCloseReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "camClose receiver register failed: " + t.getMessage());
        }
    }

    /** Lazily build the ✕ button + its layout params (once). */
    private void buildCamCloseButton() {
        if (camCloseButton != null) return;
        TextView tv = new TextView(this);
        tv.setText("✕"); // ✕
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setTextSize(16);   // scaled with the 32dp button (was 20 at 40dp)
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(android.graphics.Color.parseColor("#CC000000"));
        bg.setStroke(2, android.graphics.Color.parseColor("#80FFFFFF"));
        tv.setBackground(bg);
        int pad = camDp(5);   // scaled with the 32dp button (was 6 at 40dp)
        tv.setPadding(pad, pad, pad, pad);
        tv.setOnClickListener(v -> onCamCloseTapped());
        camCloseButton = tv;

        int size = camDp(CLOSE_BTN_SIZE_DP);
        camCloseParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        camCloseParams.gravity = Gravity.TOP | Gravity.END;
        camCloseParams.x = camDp(16);
        camCloseParams.y = camDp(16);
    }

    /** Attach/detach the ✕ window. Always runs on the main thread (WindowManager
     *  add/removeView requirement); the broadcast receiver already runs on main. */
    private void setCamCloseVisible(boolean visible) {
        handler.post(() -> {
            if (visible && !camCloseAttached
                    && !OverlayPermissionChecker.isGranted(this)) return;
            buildCamCloseButton();
            if (camCloseButton == null || windowManager == null) return;
            try {
                if (visible && !camCloseAttached) {
                    // See setBsCloseVisible: position before attach so the ✕ never lands
                    // under the card's SurfaceControl layer.
                    positionCloseParams(camCloseParams);
                    windowManager.addView(camCloseButton, camCloseParams);
                    camCloseAttached = true;
                } else if (!visible && camCloseAttached) {
                    windowManager.removeView(camCloseButton);
                    camCloseAttached = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "setCamCloseVisible(" + visible + ") failed: " + e.getMessage());
            }
        });
    }

    private void onCamCloseTapped() {
        // Hold off the poll reconcile while the hide is in flight (see the field's note).
        camCloseReconcileAfterMs =
            android.os.SystemClock.elapsedRealtime() + CAM_CLOSE_TAP_SETTLE_MS;
        camViewWantsClose = false;          // optimistic; daemon confirms via broadcast/poll
        reconcileCloseButtons();            // immediate feedback
        executor.execute(() -> {
            java.net.HttpURLConnection conn = null;
            boolean ok = false;
            try {
                conn = com.overdrive.app.util.DaemonHttpClient.open("/api/camview/hide", "POST", 1500, 3000);
                int code = conn.getResponseCode();
                ok = (code >= 200 && code < 300);
            } catch (Exception e) {
                Log.w(TAG, "camview hide failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            // RESTORE the ✕ if the hide did not land. We remove it optimistically for
            // instant feedback, but a refused/timed-out POST (daemon busy holding its
            // lane lock, or mid-restart) leaves the view still rendering — and the daemon
            // only broadcasts camview state on EDGES, so nothing would ever bring the
            // button back. That stranded the view on screen with no way to dismiss it.
            if (!ok) {
                // Lift the suppression first so the next poll is free to correct us in
                // EITHER direction: if the view really did go away despite the error, the
                // reconcile removes the button again rather than leaving it orphaned.
                camCloseReconcileAfterMs = 0L;
                camViewWantsClose = true;
                handler.post(this::reconcileCloseButtons);
                Log.w(TAG, "camview hide did not confirm — restoring ✕ so the view stays dismissable");
            }
        });
    }

    private int camDp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── Blind-spot close button (parallel to the camera-view one above) ─────

    /** Register the blind-spot-state broadcast receiver once. Same exported contract as
     *  {@link #registerCamCloseReceiver()} (daemon sender is shell/UID-2000). */
    private void registerBsCloseReceiver() {
        if (bsCloseReceiverRegistered) return;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_BS_STATE);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(bsCloseReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(bsCloseReceiver, f);
            }
            bsCloseReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "bsClose receiver register failed: " + t.getMessage());
        }
    }

    /** Lazily build the blind-spot ✕ button + its params (once). Identical styling to
     *  the camview ✕ so the two feel like one control; separate instance so they never
     *  share attach state. */
    private void buildBsCloseButton() {
        if (bsCloseButton != null) return;
        TextView tv = new TextView(this);
        tv.setText("✕");
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setTextSize(16);   // scaled with the 32dp button (was 20 at 40dp)
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(android.graphics.Color.parseColor("#CC000000"));
        bg.setStroke(2, android.graphics.Color.parseColor("#80FFFFFF"));
        tv.setBackground(bg);
        int pad = camDp(5);   // scaled with the 32dp button (was 6 at 40dp)
        tv.setPadding(pad, pad, pad, pad);
        tv.setOnClickListener(v -> onBsCloseTapped());
        bsCloseButton = tv;

        int size = camDp(CLOSE_BTN_SIZE_DP);
        bsCloseParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bsCloseParams.gravity = Gravity.TOP | Gravity.END;
        bsCloseParams.x = camDp(16);
        bsCloseParams.y = camDp(16);
    }

    /** Attach/detach the blind-spot ✕ window (main thread; WindowManager requirement). */
    private void setBsCloseVisible(boolean visible) {
        handler.post(() -> {
            if (visible && !bsCloseAttached
                    && !OverlayPermissionChecker.isGranted(this)) return;
            buildBsCloseButton();
            if (bsCloseButton == null || windowManager == null) return;
            try {
                if (visible && !bsCloseAttached) {
                    // Place it clear of the card BEFORE attaching, so it is never drawn
                    // (even for one frame) underneath the SurfaceControl layer.
                    positionCloseParams(bsCloseParams);
                    windowManager.addView(bsCloseButton, bsCloseParams);
                    bsCloseAttached = true;
                } else if (!visible && bsCloseAttached) {
                    windowManager.removeView(bsCloseButton);
                    bsCloseAttached = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "setBsCloseVisible(" + visible + ") failed: " + e.getMessage());
            }
        });
    }

    private void onBsCloseTapped() {
        // Hold off the poll reconcile while the dismiss is in flight (see the field note).
        bsCloseReconcileAfterMs =
            android.os.SystemClock.elapsedRealtime() + CAM_CLOSE_TAP_SETTLE_MS;
        bsCardWantsClose = false;           // optimistic; daemon confirms via broadcast/poll
        reconcileCloseButtons();            // immediate feedback
        executor.execute(() -> {
            java.net.HttpURLConnection conn = null;
            boolean ok = false;
            try {
                conn = com.overdrive.app.util.DaemonHttpClient.open("/api/bs/hide", "POST", 1500, 3000);
                int code = conn.getResponseCode();
                ok = (code >= 200 && code < 300);
                // NOTE: the {dismissed} field in the reply is intentionally not consulted.
                // dismissed=false means the card had already auto-hidden in the tap→POST
                // gap — in which case the daemon's own edge/poll already drives the button
                // away, and we must NOT restore it. Only a transport failure (ok=false)
                // needs the restore below, so a 2xx of either dismissed value is success.
            } catch (Exception e) {
                Log.w(TAG, "bs hide failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            // Restore the ✕ ONLY on a refused/timed-out POST (ok=false): the dismiss never
            // landed, so the card is still rendering and needs its button back. On success
            // the daemon's broadcast/poll owns the button state. Lift the suppression first
            // so the next poll is free to correct us in either direction.
            if (!ok) {
                bsCloseReconcileAfterMs = 0L;
                bsCardWantsClose = true;
                handler.post(this::reconcileCloseButtons);
                Log.w(TAG, "bs hide did not confirm — restoring ✕ so the card stays dismissable");
            }
        });
    }

    @Override
    public void onDestroy() {
        // Order matters here:
        //  1. Flip running BEFORE we touch the executor or the controller.
        //     Any in-flight reconcile that's already on the executor reads
        //     running.get() (well, indirectly — its outer pollStatus did);
        //     more importantly, the next reschedule sees false and stops.
        //  2. Cancel any pending Handler callbacks so we don't enqueue
        //     another pollStatus after we've torn down.
        //  3. shutdownNow() interrupts the executor — but the executor may
        //     be MID-RECONCILE on the audio controller. AppAudioCaptureController
        //     is independently synchronized, and our onDestroy stop() below
        //     races with reconcile's stop() through that lock; whichever
        //     loses the race gets the early-return path inside stop().
        //  4. Stop the controller from the main thread. We can't ship this
        //     work to the executor because shutdownNow() drained it.
        //     stop() is documented (in AppAudioCaptureController) as fast —
        //     thread joins use a bounded wait inside cleanup().
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        com.overdrive.app.audio.AppAudioCaptureController
                .setRecordingDemand(false);
        // Tear down the camera-view close button + its receiver.
        if (camCloseReceiverRegistered) {
            try { unregisterReceiver(camCloseReceiver); } catch (Throwable ignored) {}
            camCloseReceiverRegistered = false;
        }
        // Tear down the blind-spot close button + its receiver.
        if (bsCloseReceiverRegistered) {
            try { unregisterReceiver(bsCloseReceiver); } catch (Throwable ignored) {}
            bsCloseReceiverRegistered = false;
        }
        if (replayReceiverRegistered) {
            try { unregisterReceiver(replayStateReceiver); } catch (Throwable ignored) {}
            replayReceiverRegistered = false;
        }
        if (camCloseAttached && camCloseButton != null && windowManager != null) {
            try { windowManager.removeView(camCloseButton); } catch (Throwable ignored) {}
            camCloseAttached = false;
        }
        if (bsCloseAttached && bsCloseButton != null && windowManager != null) {
            try { windowManager.removeView(bsCloseButton); } catch (Throwable ignored) {}
            bsCloseAttached = false;
        }
        removeOverlay();
        super.onDestroy();
    }

    /**
     * Called when the user swipes the app away from Recents.
     *
     * On many Android builds (including BYD head units running AOSP forks)
     * this triggers the service to be torn down alongside the activity task,
     * which makes the floating overlay disappear. Re-schedule ourselves so
     * the service (and the overlay window) survives the task being cleared.
     *
     * The re-launch uses an AlarmManager one-shot because Android restricts
     * starting foreground services directly from inside onTaskRemoved on
     * newer platform versions.
     */
    /**
     * Re-inflate the overlay when the device configuration changes (light ↔
     * dark, locale, font scale). Without this, the user toggling the app
     * theme leaves the overlay stuck on whatever palette it was created with
     * because the View tree was inflated once and is never re-resolved.
     *
     * We blow the view away and let the next pollStatus()/updateOverlay()
     * tick rebuild it; that path also re-binds icon tints so the active /
     * inactive states pick up the new status color tokens.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlayView == null) return;
        Log.i(TAG, "Configuration changed — rebuilding overlay so theme tokens reapply");
        rebuildOverlay();
    }

    /**
     * Tear down + recreate the overlay so a theme change reaches the
     * resolved drawables and color tokens. Persists current position so
     * the new pill lands where the user last dragged it.
     */
    private void rebuildOverlay() {
        try {
            if (layoutParams != null) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(PREF_POS_X, layoutParams.x)
                        .putInt(PREF_POS_Y, layoutParams.y)
                        .apply();
            }
        } catch (Exception ignored) {}
        removeOverlay();
        // Targeted re-issue: same generation-counter pattern as
        // applyMode / onStartCommand re-entry. Wholesale-wipe would
        // also drop autocollapse and any pending updateUI/Toast posts
        // unrelated to the poll cadence.
        rescheduleImmediatePoll();
    }

    /**
     * Build a context whose resources honor the app's day/night override.
     *
     * Plain Service contexts read uiMode straight from the system config,
     * so AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO) doesn't reach
     * the overlay — the pill stays dark on a light-themed system. Mapping
     * the AppCompat mode onto Configuration.UI_MODE_NIGHT_* and creating a
     * configuration-context with that override fixes it.
     */
    private Context themedContext() {
        int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        int uiNight;
        if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            uiNight = android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } else if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
            uiNight = android.content.res.Configuration.UI_MODE_NIGHT_NO;
        } else {
            // Follow-system / unspecified — leave the system's value alone.
            return this;
        }
        android.content.res.Configuration cfg = new android.content.res.Configuration(
                getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | uiNight;
        return createConfigurationContext(cfg);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved — scheduling overlay service restart");
        try {
            Intent restart = new Intent(getApplicationContext(), StatusOverlayService.class);
            restart.setPackage(getPackageName());
            int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getForegroundService(
                    getApplicationContext(), 1, restart, flags);
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && pi != null) {
                // 1s out so the current task-removal flow unwinds first.
                am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                        android.os.SystemClock.elapsedRealtime() + 1000,
                        pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to schedule overlay restart: " + e.getMessage());
        }
        super.onTaskRemoved(rootIntent);
    }

    // ==================== OVERLAY ====================

    private void createOverlay() {
        if (overlayView != null) return; // Already created

        // Inflate against a context whose configuration honors the app's
        // chosen day/night setting. A bare Service runs against the system
        // configuration, so AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO)
        // wouldn't reach the overlay — the pill would stay dark on a
        // light-themed system because the Service never saw the override.
        // Wrapping with createConfigurationContext gives us a context whose
        // resources resolve light/dark drawables according to the user's
        // explicit choice.
        overlayView = LayoutInflater.from(themedContext()).inflate(
                R.layout.overlay_status, null);

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        // Restore last user-placed position (falls back to defaults on first run)
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        layoutParams.x = prefs.getInt(PREF_POS_X, DEFAULT_POS_X);
        layoutParams.y = prefs.getInt(PREF_POS_Y, DEFAULT_POS_Y);

        bindViews();
        setupDrag();

        try {
            windowManager.addView(overlayView, layoutParams);
            Log.i(TAG, "Overlay window added");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay: " + e.getMessage());
            overlayView = null;
        }
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
        // Cancel any pending auto-collapse and reset the expanded flag —
        // the View references it tracked are gone, and a stale "true"
        // would force the next createOverlay() into the wrong state.
        actionBarExpanded = false;
        handler.removeCallbacks(autocollapseRunnable);
    }

    private void bindViews() {
        recContainer = overlayView.findViewById(R.id.recContainer);
        tripContainer = overlayView.findViewById(R.id.tripContainer);
        micContainer = overlayView.findViewById(R.id.micContainer);
        replayContainer = overlayView.findViewById(R.id.replayContainer);
        ivReplayIcon = overlayView.findViewById(R.id.ivReplayIcon);
        tvReplayLabel = overlayView.findViewById(R.id.tvReplayLabel);
        actionBar = overlayView.findViewById(R.id.actionBar);
        ivRecIcon = overlayView.findViewById(R.id.ivRecIcon);
        ivTripIcon = overlayView.findViewById(R.id.ivTripIcon);
        ivMicIcon = overlayView.findViewById(R.id.ivMicIcon);
        btnModeOff = overlayView.findViewById(R.id.btnModeOff);
        btnModeContinuous = overlayView.findViewById(R.id.btnModeContinuous);
        btnModeDrive = overlayView.findViewById(R.id.btnModeDrive);
        btnModeProximity = overlayView.findViewById(R.id.btnModeProximity);
        tvRecLabel = overlayView.findViewById(R.id.tvRecLabel);
        tvTripLabel = overlayView.findViewById(R.id.tvTripLabel);
        tvMicLabel = overlayView.findViewById(R.id.tvMicLabel);

        // Tap on REC chip → toggle the expanded action bar with mode
        // quick-actions.
        //
        // The action bar is the ONLY entry point to the mode chips, so the
        // tap MUST always open/close it — regardless of recording state.
        // The previous implementation early-returned after restartRecording()
        // whenever (!isRecording && shouldRecordingBeActive()), which is the
        // normal post-arm state for CONTINUOUS / DRIVE_MODE / PROXIMITY_GUARD
        // before the first clip starts. That shadowed setActionBarExpanded()
        // and made the chips unreachable in exactly those modes — the user
        // saw "the pill isn't clickable" for everything except the GREEN
        // (isRecording=true) state.
        //
        // We keep the legacy "kick a should-be-running-but-isn't daemon"
        // repair gesture, but fire it only on the OPENING edge so that
        // (a) re-tapping to collapse the bar doesn't queue redundant restart
        // sockets, and (b) the bar stays collapsible by re-tap.
        recContainer.setOnClickListener(v -> {
            boolean willExpand = !actionBarExpanded;
            if (willExpand && !isRecording && shouldRecordingBeActive()) {
                restartRecording();
            }
            setActionBarExpanded(willExpand);
        });

        // Long-press on REC → fast quick-toggle for the muscle-memory
        // case: "I want to stop recording right now" / "resume what I
        // had before". Bypasses the expanded UI entirely so the user
        // doesn't have to aim at a chip.
        recContainer.setOnLongClickListener(v -> {
            quickToggleRecording();
            return true;
        });

        // Action chip taps. Each fires setRecordingMode over the
        // daemon's TCP command channel and arms a fast-poll window so
        // the UI repaints within ~1s instead of waiting for the next
        // 3s tick.
        if (btnModeOff != null) {
            btnModeOff.setOnClickListener(v -> applyMode("NONE"));
        }
        if (btnModeContinuous != null) {
            btnModeContinuous.setOnClickListener(v -> applyMode("CONTINUOUS"));
        }
        if (btnModeDrive != null) {
            btnModeDrive.setOnClickListener(v -> applyMode("DRIVE_MODE"));
        }
        if (btnModeProximity != null) {
            btnModeProximity.setOnClickListener(v -> applyMode("PROXIMITY_GUARD"));
        }

        // Tap on trip item → restart trip detection if not running
        tripContainer.setOnClickListener(v -> {
            if (tripEnabled && !tripActive) {
                restartTripDetection();
            }
        });
    }

    /**
     * Show or hide the expanded action bar.
     *
     * Expanding (re)arms the auto-collapse timer; collapsing cancels
     * it. Also refreshes the selection state so the chip representing
     * the active mode is highlighted as soon as the bar appears — saves
     * a poll-tick of latency when the user wants to confirm what they
     * just picked.
     */
    private void setActionBarExpanded(boolean expanded) {
        actionBarExpanded = expanded;
        if (actionBar == null) return;
        actionBar.setVisibility(expanded ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(autocollapseRunnable);
        if (expanded) {
            refreshActionBarSelection();
            handler.postDelayed(autocollapseRunnable, EXPAND_AUTOCOLLAPSE_MS);
        }
    }

    /**
     * Mark the chip matching {@link #configuredMode} as selected so the
     * user sees which mode is active at a glance. Called whenever the
     * action bar is shown and on every UI refresh while it's open so a
     * mode change applied from the web UI also reflects here.
     */
    private void refreshActionBarSelection() {
        if (btnModeOff == null) return;
        btnModeOff.setSelected("NONE".equals(configuredMode));
        btnModeContinuous.setSelected("CONTINUOUS".equals(configuredMode));
        btnModeDrive.setSelected("DRIVE_MODE".equals(configuredMode));
        btnModeProximity.setSelected("PROXIMITY_GUARD".equals(configuredMode));
    }

    /**
     * Quick-toggle: if recording, stop. If stopped, resume the user's
     * last non-NONE mode (or CONTINUOUS as a first-run fallback). Used
     * by the REC long-press shortcut.
     */
    private void quickToggleRecording() {
        // Bail if we don't have a confident view of the daemon's mode.
        // An empty configuredMode (JSON-fallback path on a daemon
        // hiccup) used to be treated as "off" and would clobber the
        // user's actual setting with their last-resume mode. Better
        // to do nothing — the user can re-tap once the next status
        // tick has reconciled.
        if (!daemonReachable || configuredMode == null || configuredMode.isEmpty()) {
            return;
        }
        boolean off = "NONE".equals(configuredMode);
        if (off) {
            String resume = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_LAST_NON_NONE_MODE, "CONTINUOUS");
            applyMode(resume);
        } else {
            applyMode("NONE");
        }
    }

    /**
     * Send {@code mode} to the daemon's TCP command channel, persist it
     * locally as the last user-picked non-NONE mode, and arm fast-poll
     * so the chip state repaints quickly. Optimistically updates the
     * local {@code configuredMode} so the selected-state highlight
     * moves to the new chip in the same frame instead of waiting for
     * the next status poll.
     */
    private void applyMode(String mode) {
        if (mode == null || mode.isEmpty()) return;
        // Coalesce duplicate taps. A user mashing the same chip 5×
        // would otherwise queue 5× TCP jobs on the polling executor,
        // and a wedged daemon (1.5s connect timeout each) could
        // starve actual status polls for several seconds. Different-
        // mode taps still go through — the user is changing their
        // mind and we want to send the latest pick.
        long now = android.os.SystemClock.elapsedRealtime();
        if (mode.equals(inflightModeRequest)
                && (now - inflightModeRequestMs) < MODE_REQUEST_DEDUP_WINDOW_MS) {
            return;
        }
        inflightModeRequest = mode;
        inflightModeRequestMs = now;
        configuredMode = mode;
        // Stamp the optimistic window. parseStatus respects this for
        // OPTIMISTIC_MODE_WINDOW_MS so an in-flight tick can't roll us
        // back to the pre-change daemon value before the daemon has
        // had a chance to actually apply our setRecordingMode. Once the
        // daemon catches up (typically <500ms over the local TCP socket)
        // its echoed configuredMode matches ours and the guard becomes
        // a no-op.
        optimisticModeUntilElapsedMs =
            android.os.SystemClock.elapsedRealtime() + OPTIMISTIC_MODE_WINDOW_MS;
        if (!"NONE".equals(mode)) {
            try {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(PREF_LAST_NON_NONE_MODE, mode)
                        .apply();
            } catch (Exception ignored) {}
        }
        refreshActionBarSelection();
        sendSetRecordingMode(mode);
        // Arm a fast-poll window so isRecording / shouldBeRecording
        // catch up within ~1s instead of waiting for the next 3s tick.
        // rescheduleImmediatePoll() bumps the generation counter so
        // any in-flight executor tick will skip its tail-reschedule
        // (rather than racing with our newly-posted one and producing
        // two parallel chains).
        fastPollUntilElapsedMs =
            android.os.SystemClock.elapsedRealtime() + FAST_POLL_WINDOW_MS;
        // Re-arm the auto-collapse timer — the user just interacted, so
        // give them another full window to pick another chip if they
        // want to switch again. Targeted removeCallbacks so we don't
        // wipe unrelated runnables (rejection Toast, executor's
        // queued updateUI post) on the same handler.
        if (actionBarExpanded) {
            handler.removeCallbacks(autocollapseRunnable);
            handler.postDelayed(autocollapseRunnable, EXPAND_AUTOCOLLAPSE_MS);
        }
        rescheduleImmediatePoll();
        // Repaint immediately so the selected-chip highlight moves to
        // the new mode in this UI frame instead of waiting for the
        // 1s fast-poll tick.
        updateUI();
    }

    /**
     * TCP command to the in-process daemon. Same wire format as
     * {@link #restartRecording()}; factored out so {@link #applyMode}
     * can pass an arbitrary mode string instead of always re-sending
     * the current one.
     */
    private void sendSetRecordingMode(String mode) {
        executor.execute(() -> {
            java.net.Socket socket = null;
            boolean accepted = false;
            String errorMessage = null;
            try {
                // Bounded connect timeout. The default Socket(host,port)
                // constructor has NO connect timeout, so a wedged daemon
                // (e.g. mid-shutdown not yet listening) would block this
                // executor thread indefinitely and starve status polls.
                socket = new java.net.Socket();
                socket.connect(
                    new java.net.InetSocketAddress("127.0.0.1", 19876), 1500);
                socket.setSoTimeout(3000);
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "setRecordingMode");
                cmd.put("mode", mode);
                java.io.OutputStream os = socket.getOutputStream();
                os.write((cmd.toString() + "\n").getBytes());
                os.flush();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String response = reader.readLine();
                Log.i(TAG, "applyMode(" + mode + "): " + response);
                if (response != null) {
                    try {
                        JSONObject json = new JSONObject(response);
                        accepted = "ok".equals(json.optString("status"));
                        if (!accepted) {
                            errorMessage = json.optString("message", "rejected");
                        }
                    } catch (Exception parseErr) {
                        // Daemon returned non-JSON or partial — treat as
                        // rejected so we don't silently leave an
                        // optimistic value the daemon never honored.
                        errorMessage = "bad daemon response";
                    }
                } else {
                    errorMessage = "no daemon response";
                }
            } catch (Exception e) {
                Log.e(TAG, "applyMode(" + mode + ") failed: " + e.getMessage());
                errorMessage = e.getMessage();
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
            // Surface a rejection to the user so the chip selection
            // doesn't silently revert 1.5s later with no explanation.
            // Also clear the optimistic window early so the next poll
            // immediately reflects the actual daemon mode.
            if (!accepted) {
                final String hint = errorMessage;
                optimisticModeUntilElapsedMs = 0;
                handler.post(() -> {
                    // Service may have been torn down between when
                    // the executor task posted this Runnable and when
                    // the main looper services it. Showing a Toast
                    // against a destroyed Service / re-arming the
                    // poll loop on a stopped service is just stale-
                    // state UX noise.
                    if (!running.get()) return;
                    try {
                        android.widget.Toast.makeText(
                            StatusOverlayService.this,
                            hint != null
                                ? getString(R.string.overlay_recording_mode_failed_fmt, hint)
                                : getString(R.string.overlay_recording_mode_failed),
                            android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                    // Kick an immediate poll so the chip reverts to
                    // the daemon's actual mode in the same UI frame
                    // as the Toast — without this, the user sees the
                    // error message but the chip stays highlighted on
                    // the rejected pick for up to one fast-poll tick.
                    rescheduleImmediatePoll();
                });
            }
        });
    }

    /**
     * Drop any pending pollStatus reschedule and kick a fresh poll
     * tick. Bumps {@link #pollGeneration} so the in-flight executor
     * task (if any) skips its own tail-reschedule when it sees the
     * generation has advanced — without this we'd end up with two
     * parallel poll chains feeding the UI.
     *
     * Safe to call from main thread or executor.
     */
    private void rescheduleImmediatePoll() {
        pollGeneration.incrementAndGet();
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    private void setupDrag() {
        View pill = overlayView.findViewById(R.id.pillContainer);
        pill.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    initialX = layoutParams.x;
                    initialY = layoutParams.y;
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        layoutParams.x = initialX + (int) dx;
                        layoutParams.y = initialY + (int) dy;
                        try {
                            windowManager.updateViewLayout(overlayView, layoutParams);
                        } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        // Let child click handlers fire
                        return false;
                    }
                    // Persist the new position so it survives overlay recreation,
                    // service restarts, and reboots
                    try {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putInt(PREF_POS_X, layoutParams.x)
                                .putInt(PREF_POS_Y, layoutParams.y)
                                .apply();
                    } catch (Exception ignored) {}
                    return true;
            }
            return false;
        });
    }

    // ==================== POLLING ====================

    private void startPolling() {
        running.set(true);
        pollStatus();
    }

    private void pollStatus() {
        if (!running.get()) return;

        // Snapshot the generation we entered with. If anything bumps it
        // before our tail-reschedule (an external rescheduleImmediatePoll
        // from applyMode / onStartCommand re-entry), we'll skip our own
        // postDelayed so we don't spawn a parallel poll chain. Without
        // this, every external re-issue could leave the in-flight tick
        // about to fire its own postDelayed AFTER the re-issuer has
        // already removed callbacks — producing two interleaved chains.
        final int genAtEntry = pollGeneration.get();

        executor.execute(() -> {
            // FIX M4: a single forceReload at the top of the tick replaces
            // four separate forceReload() calls scattered through
            // refreshAudioConfig / parseStatus / updateUI. Each forceReload
            // re-reads /data/local/tmp/overdrive_config.json and re-parses
            // the JSON; doing it 4× per 3 s tick was ~12 disk reads + 4
            // JSON parses for the same file mtime. The cache is now
            // consistent across the four downstream reads in this tick;
            // they each call loadConfig()/getOemDashcam()/etc. and hit the
            // freshly-warmed cache without re-doing the I/O.
            //
            // ACC-ON COST FIX: this used to be forceReload(), which NULLS the
            // cache and forces a full readText() + JSONObject parse + the
            // ~515-line applyDefaults() migration walk on EVERY tick — i.e.
            // every 3s for the entire drive, on the shared /data/local/tmp
            // config under a lock the UID-2000 daemon also takes. loadConfig()
            // achieves the same "warm the cache once for this tick's four
            // downstream reads" goal, but is mtime-gated: it reparses only when
            // the file actually changed on disk (which is the correct cross-UID
            // freshness signal, since the daemon's writes bump mtime) and is
            // nearly free otherwise. The only behavioural delta is a daemon
            // write landing in the SAME wall-clock second as this read, which
            // isCacheFresh() deliberately treats as stale-enough-to-reparse on
            // the next call — invisible for a 3s status pill.
            try {
                com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            } catch (Throwable t) {
                // Tolerate transient I/O — the downstream reads will fall
                // back to the prior cached snapshot.
            }
            try {
                // Stamp BEFORE the fetch: any state the daemon reports was
                // current at-or-after this instant, which is what lets
                // adoptReplayState treat the sample as an authoritative
                // snapshot relative to locally-known broadcast events.
                long fetchStartElapsedMs = android.os.SystemClock.elapsedRealtime();
                JSONObject status = fetchStatus();
                if (status != null) {
                    daemonReachable = true;
                    consecutivePollFailures = 0;
                    parseStatus(status, fetchStartElapsedMs);
                } else {
                    consecutivePollFailures++;
                    if (consecutivePollFailures >= UNREACHABLE_THRESHOLD) {
                        daemonReachable = false;
                    }
                    // else: keep daemonReachable as-is (grace period)
                }
                // Detect ACC OFF→ON edge AFTER parseStatus() — it's the most
                // recent point at which we trust accOn. Arm fast-poll for 30s
                // so the audio controller restart (if it back-offs after a
                // mic claim) and the daemon's first-segment kickoff happen
                // within ~1s rather than ~10s of the user turning the key.
                boolean prevAcc = previousAccOn;
                previousAccOn = accOn;
                if (accOn && !prevAcc) {
                    fastPollUntilElapsedMs =
                        android.os.SystemClock.elapsedRealtime() + FAST_POLL_WINDOW_MS;
                    Log.i(TAG, "ACC OFF→ON edge — fast-polling for "
                            + FAST_POLL_WINDOW_MS + "ms");
                }
                refreshAudioConfig();
                reconcileAudioCapture();
                handler.post(this::updateUI);
            } catch (Exception e) {
                consecutivePollFailures++;
                if (consecutivePollFailures >= UNREACHABLE_THRESHOLD) {
                    daemonReachable = false;
                }
                // Even on poll failure, run reconcile so a stale capture
                // gets torn down when the daemon goes away.
                reconcileAudioCapture();
                handler.post(this::updateUI);
            }

            if (running.get()) {
                // Always reschedule. Detect ACC by VALUE on each poll, not by
                // edge — single-shot SCREEN_ON suspension was racy because
                // the ACC-on signal propagation (AccSentryDaemon → IPC →
                // RecordingModeManager) lags SCREEN_ON, so the first poll
                // after wake saw accOn=false and stranded us. Slow-poll
                // loopback to the in-process daemon HTTP is negligible.
                //
                // Fast-poll window: when armed by an ACC edge, run at 1s for
                // FAST_POLL_WINDOW_MS to catch the daemon-startup race tight.
                long now = android.os.SystemClock.elapsedRealtime();
                final long interval;
                if (fastPollUntilElapsedMs > now) {
                    interval = FAST_POLL_INTERVAL_MS;
                } else if (overlayFullyIdle) {
                    // Overlay disabled + no audio to reconcile: slow way down.
                    // A fresh ACC OFF→ON edge re-arms fast-poll on the next tick.
                    interval = IDLE_POLL_INTERVAL_MS;
                } else {
                    interval = accOn ? POLL_INTERVAL_MS : POLL_INTERVAL_ACC_OFF_MS;
                }
                // Marshal the generation-check + postDelayed onto the
                // main thread so they serialize against rescheduleImmediatePoll
                // (which also runs on main). Doing the check on the
                // executor and the post on the Handler is racy: an
                // executor-side `pollGeneration.get() == genAtEntry`
                // check could pass, then main races in between the
                // check and the postDelayed (incrementing gen, calling
                // removeCallbacks against an empty queue), and the
                // executor's subsequent postDelayed enqueues a stale
                // pollRunnable that no removeCallbacks will ever clear
                // — producing a parallel poll chain that doubles disk
                // I/O, /status traffic, and audio reconcile calls
                // forever after.
                final int gen = genAtEntry;
                handler.post(() -> {
                    if (running.get() && pollGeneration.get() == gen) {
                        handler.postDelayed(pollRunnable, interval);
                    }
                });
            }
        });
    }

    private JSONObject fetchStatus() {
        HttpURLConnection conn = null;
        try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/status", "GET", 2000, 2000);
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                return new JSONObject(sb.toString());
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Decide whether audio capture should be running right now and start /
     * stop AppAudioCaptureController accordingly.
     *
     * Capture is gated to ACC-on recording modes only:
     *   - audioEnabledConfig must be true
     *   - daemonReachable, accOn must be true
     *   - configuredMode in {CONTINUOUS, DRIVE_MODE, PROXIMITY_GUARD}
     *   - shouldRecordingBeActive() must be true (ie current gear matches)
     *
     * Surveillance mode (ACC OFF, sentry) deliberately does NOT enable
     * audio capture. That's a privacy-significant separation: audio
     * recording in the cabin while parked + driver gone is a much spicier
     * legal posture than audio while driving.
     *
     * Run on the polling executor (IO thread) — start() opens AudioRecord +
     * MediaCodec + a TCP socket which together can take 30-100 ms.
     */
    /**
     * Refresh audioEnabledConfig from UnifiedConfigManager. Read fresh on
     * every poll so the user toggling the recording.html switch reflects
     * within ~3s without a service restart.
     *
     * Defaults to false on read failure — better to silently NOT capture
     * audio than to silently DO capture audio when we can't confirm consent.
     */
    private void refreshAudioConfig() {
        try {
            // FIX M4: pollStatus() does ONE forceReload at the top of the
            // tick to defeat the daemon-cross-UID stale cache. From here
            // we use loadConfig() which is mtime-gated and free when the
            // tick's earlier forceReload already refreshed.
            org.json.JSONObject recCfg =
                com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("recording");
            audioEnabledConfig = recCfg != null
                && recCfg.optBoolean("audioEnabled", false);
        } catch (Exception e) {
            audioEnabledConfig = false;
        }
    }

    private void reconcileAudioCapture() {
        // "Trip is in progress" semantics — we keep the audio controller
        // alive across P↔D gear changes within a single ACC-on session.
        // Tearing down on each gear change (which the old shouldRecordingBeActive()
        // gate did) caused ~5s of silent audio at every D resume in city
        // traffic, because each restart hits AudioRecord open + a TCP
        // reconnect and the BYD voice asst can grab the mic during the gap.
        //
        // The daemon decides whether incoming AAC frames are muxed into the
        // current segment (it drops them when isWritingToFile == false), so
        // capturing while not-recording costs only the loopback TCP traffic
        // (~8 KB/s of AAC the daemon discards). This is what makes the
        // pre-record buffer feature work — the controller MUST be live so
        // there's audio history available when the daemon decides to record.
        boolean shouldCapture = audioEnabledConfig
            && daemonReachable
            && accOn
            && (configuredMode.equals("CONTINUOUS")
                || configuredMode.equals("DRIVE_MODE")
                || configuredMode.equals("PROXIMITY_GUARD"));

        boolean wasCapturing =
                com.overdrive.app.audio.AppAudioCaptureController
                        .isRecordingCaptureRunning();
        if (shouldCapture && !running.get()) return;

        boolean ok = com.overdrive.app.audio.AppAudioCaptureController
                .setRecordingDemand(shouldCapture);
        if (!running.get()) {
            com.overdrive.app.audio.AppAudioCaptureController
                    .setRecordingDemand(false);
            return;
        }

        boolean isCapturing =
                com.overdrive.app.audio.AppAudioCaptureController
                        .isRecordingCaptureRunning();
        if (shouldCapture && isCapturing) {
            if (!wasCapturing) {
                Log.i(TAG, "Audio capture enabled (mode=" + configuredMode + ")");
            }
            lastAudioStartFailureMs = 0;
        } else if (shouldCapture && !ok) {
            lastAudioStartFailureMs =
                    android.os.SystemClock.elapsedRealtime();
            Log.w(TAG, "Audio capture start failed — will retry on next poll");
        } else if (!shouldCapture && wasCapturing) {
            Log.i(TAG, "Audio capture disabled");
        }
    }

    private void parseStatus(JSONObject status, long fetchStartElapsedMs) {
        try {
            // Suppress configuredMode overwrites while the user's
            // optimistic pick is still settling. Without this, an
            // in-flight tick that started before applyMode() ran would
            // clobber the user-chosen mode with the daemon's pre-change
            // value, and the action-chip selection would visibly bounce.
            boolean honorOptimisticMode =
                android.os.SystemClock.elapsedRealtime() < optimisticModeUntilElapsedMs;
            // New fields (from updated daemon)
            JSONObject recStatus = status.optJSONObject("recordingStatus");
            if (recStatus != null) {
                if (!honorOptimisticMode) {
                    configuredMode = recStatus.optString("configuredMode", "NONE");
                }
                isRecording = recStatus.optBoolean("isRecording", false);
                // modeActive defaults to isRecording so an older daemon that
                // emits recordingStatus but not modeActive/pipelineRunning
                // still behaves exactly as before (no false "active").
                modeActive = recStatus.optBoolean("modeActive", isRecording);
                pipelineRunning = recStatus.optBoolean("pipelineRunning", isRecording);
                // Older daemon without the wedge flag → defaults false, so the
                // deferred-active path behaves as before (no wedge masking).
                recordingWedged = recStatus.optBoolean("wedged", false);
                currentGear = recStatus.optString("gear", "P");
                accOn = recStatus.optBoolean("accOn", false);
                // Reconcile the floating ✕ from the poll. The daemon broadcasts camview
                // state on EDGES only, and an `am broadcast` is simply dropped if this
                // service wasn't running / lacked overlay permission at that moment — so a
                // missed edge used to strand a rendering view with no way to close it (or
                // leave an orphaned ✕ over nothing). Gated on has() so an older daemon
                // without these fields keeps the pure edge-driven behaviour unchanged.
                // setCamCloseVisible is idempotent + attach-state guarded, so re-asserting
                // every poll costs nothing.
                // Refresh the remembered per-channel truth from the poll, each gated on
                // its own tap-settle window so an in-flight pre-tap snapshot can't flicker
                // the button back, then arbitrate ONCE. Each field is has()-gated so an
                // older daemon lacking it keeps the pre-existing edge-driven behaviour.
                boolean reconcile = false;
                long nowMs = android.os.SystemClock.elapsedRealtime();
                // EDGE SETTLE (see closeEdgeAtMs): a response built inside the daemon's
                // ≤250ms program-transition window mislabels WHICH program's content is
                // on screen (bsCardShowing=false + camViewActive=true while the
                // blind-spot card is still visible) — adopting it swaps in the wrong ✕
                // whose tap fires the wrong hide. Only a response FETCHED comfortably
                // after the last edge may overwrite the edge-driven truth.
                //
                // laneTransitioning is the daemon's OWN "labels unreliable" signal for
                // that window, and it is the authoritative half of this guard: the
                // show-edge broadcast rides a detached `am` exec, so a transitional
                // poll can BEAT the edge's timestamp — the settle gate alone then
                // cleared bsCardWantsClose, and the late edge (camview state only)
                // never restored it, leaving the wrong ✕ until the next poll. A
                // response that admits incoherent labels is simply not adopted;
                // truth converges within one daemon tick, well before the next poll.
                // has()-free read: absent on an older daemon → false → gate inert.
                boolean pastEdgeSettle =
                        fetchStartElapsedMs > closeEdgeAtMs + CLOSE_EDGE_SETTLE_MS
                        && !recStatus.optBoolean("laneTransitioning", false);
                if (recStatus.has("camViewActive") && nowMs >= camCloseReconcileAfterMs
                        && pastEdgeSettle) {
                    boolean cvActive = recStatus.optBoolean("camViewActive", false);
                    String cvTarget = recStatus.optString("camViewTarget", "head_unit");
                    // Head-unit only: the cluster is a separate display we can't overlay.
                    camViewWantsClose = cvActive && !"cluster".equals(cvTarget);
                    reconcile = true;
                }
                if (recStatus.has("bsCardShowing") && nowMs >= bsCloseReconcileAfterMs
                        && pastEdgeSettle) {
                    boolean bsShowing = recStatus.optBoolean("bsCardShowing", false);
                    String bsTarget = recStatus.optString("bsCardTarget", "head_unit");
                    bsCardWantsClose = bsShowing && !"cluster".equals(bsTarget);
                    reconcile = true;
                }
                // Track the card's on-screen rect so the ✕ stays clear of it after a
                // resize / corner change / side flip. Absent on an older daemon → null,
                // which positionCloseParams treats as "use the fixed corner" (the
                // pre-existing behaviour). Reposition only on a real change, and only
                // when a button is actually attached.
                org.json.JSONObject lrJson = recStatus.optJSONObject("laneRect");
                int[] newLaneRect = (lrJson != null)
                        ? new int[]{ lrJson.optInt("x"), lrJson.optInt("y"),
                                     lrJson.optInt("w"), lrJson.optInt("h") }
                        : null;
                // FRESHNESS GATE: only adopt from a response FETCHED after the last
                // show-edge rect plus its settle window (see laneRectEdgeAdoptedAtMs).
                // Covers both an older in-flight response (previous program's rect)
                // and a post-edge response built before the daemon's transition tick
                // converged (stale rect, or a null from a lagging cluster target).
                if (fetchStartElapsedMs > laneRectEdgeAdoptedAtMs + LANE_RECT_EDGE_SETTLE_MS
                        && !java.util.Arrays.equals(newLaneRect, laneRectPx)) {
                    laneRectPx = newLaneRect;
                    repositionAttachedCloseButtons();
                }
                if (reconcile) reconcileCloseButtons();
            } else {
                // Fallback: old daemon without recordingStatus field
                // Use existing "recording" array (non-empty = recording) and "acc" field
                // We can't know the configured mode, so read it from the config file directly
                org.json.JSONArray recArray = status.optJSONArray("recording");
                isRecording = recArray != null && recArray.length() > 0;
                // Old daemon has no separate modeActive/pipelineRunning signal —
                // mirror isRecording so the compound gate below collapses to the
                // pre-existing bare-isRecording behavior on that path.
                modeActive = isRecording;
                pipelineRunning = isRecording;
                recordingWedged = false;
                accOn = status.optBoolean("acc", false);

                // Read configured mode from UnifiedConfigManager.
                // FIX M4: pollStatus() forceReloads once at the top of the
                // tick; the cache is hot here so loadConfig() is free.
                if (!honorOptimisticMode) {
                    try {
                        JSONObject recording =
                            com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                                .optJSONObject("recording");
                        if (recording != null) {
                            configuredMode = recording.optString("mode", "NONE");
                        }
                    } catch (Exception configErr) {
                        Log.w(TAG, "Config read fallback failed: " + configErr.getMessage());
                    }
                }

                // Gear: not available from old daemon status, default to non-P
                // if ACC is on (assume driving since we can't know)
                currentGear = accOn ? "D" : "P";
            }

            // Instant-replay block — poll catch-up for missed REPLAY_STATE
            // broadcasts. The daemon reports (state, stateAgeMs); reconstruct
            // the absolute transition time on OUR monotonic clock and let
            // adoptReplayState decide whether the sample is newer than what
            // the broadcast channel already delivered. Old daemons without
            // the block simply leave the segment hidden (configured=false).
            JSONObject replayStatus = status.optJSONObject("replay");
            if (replayStatus != null) {
                replayConfigured = replayStatus.optBoolean("configured", false);
                String state = replayStatus.optString("state", "");
                long ageMs = replayStatus.optLong("stateAgeMs", -1);
                if (!state.isEmpty() && ageMs >= 0) {
                    adoptReplayState(state,
                            android.os.SystemClock.elapsedRealtime() - ageMs,
                            fetchStartElapsedMs);
                }
            } else {
                replayConfigured = false;
            }

            JSONObject tripStatus = status.optJSONObject("tripStatus");
            if (tripStatus != null) {
                tripEnabled = tripStatus.optBoolean("enabled", false);
                tripActive = tripStatus.optBoolean("tripActive", false);
            } else {
                // Fallback: read trip config from UnifiedConfigManager.
                // FIX M4: pollStatus() forceReloads once at the top of the
                // tick; the cache is hot here so loadConfig() is free.
                try {
                    JSONObject tripCfg =
                        com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                            .optJSONObject("tripAnalytics");
                    if (tripCfg != null) {
                        tripEnabled = tripCfg.optBoolean("enabled", false);
                    }
                } catch (Exception configErr) {
                    Log.w(TAG, "Trip config read fallback failed: " + configErr.getMessage());
                }
                // Can't determine tripActive without daemon support — assume false
                tripActive = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Parse error: " + e.getMessage());
        }
    }

    // ==================== UI ====================

    private void updateUI() {
        // Bail if the service is being torn down. A poll tick still
        // mid-flight on the executor when onDestroy lands can race
        // past the wholesale handler wipe and post(this::updateUI)
        // afterwards; without this guard updateUI would reach
        // createOverlay() and leak a TYPE_APPLICATION_OVERLAY surface
        // into WindowManager that no live service owns — the user
        // sees a ghost pill they can't dismiss without killing the
        // app process.
        if (!running.get()) return;
        // User-facing visibility toggles. Stored in the unified config file
        // (/data/local/tmp/overdrive_config.json) rather than SharedPreferences
        // because both the app UID and the shell/daemon UID need to see the
        // same values. Read fresh on every poll so a flip in Settings reflects
        // without a service restart. Defaults to true so existing installs
        // (where the section doesn't exist yet) keep current behavior.
        boolean cameraOverlayEnabled = true;
        boolean tripOverlayEnabled = true;
        boolean replayOverlayEnabled = true;
        try {
            // FIX M4: pollStatus() forceReloads once at the top of the
            // tick; the cache is hot here so loadConfig() is free.
            JSONObject statusOverlayCfg =
                com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("statusOverlay");
            if (statusOverlayCfg != null) {
                cameraOverlayEnabled = statusOverlayCfg.optBoolean("cameraVisible", true);
                tripOverlayEnabled = statusOverlayCfg.optBoolean("tripVisible", true);
                replayOverlayEnabled = statusOverlayCfg.optBoolean("replayVisible", true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read statusOverlay prefs: " + e.getMessage());
        }

        boolean recConfigured = !"NONE".equals(configuredMode) && !"UNKNOWN".equals(configuredMode);
        // While the user is interacting with the action bar we keep the
        // pill alive even when the resolved mode is NONE — otherwise
        // tapping the OFF chip would tear the pill down before the
        // 5s auto-collapse window expires, leaving no way to change
        // their mind. The action bar is implicitly anchored to the
        // camera-overlay segment, so we only force-show when the user
        // hasn't disabled that segment in Settings.
        boolean keepAliveForActionBar = actionBarExpanded && cameraOverlayEnabled;
        // The camera segment, when enabled, always warrants the overlay —
        // even with recording stopped (mode NONE) — because the REC pill
        // is the ONLY entry point to the action bar's mode chips. Without
        // a persistent anchor the user has no way to START recording from
        // the overlay once it's off. (recConfigured no longer gates this.)
        boolean anythingToShow = cameraOverlayEnabled
                || (tripEnabled && tripOverlayEnabled)
                || keepAliveForActionBar;

        // Only emit the verbose state line when something actually changed — it
        // previously fired every poll tick (mirroring the pill redraw in logcat).
        String uiLogSig = configuredMode + "|" + isRecording + "|" + modeActive
                + "|" + pipelineRunning + "|" + recordingWedged + "|" + currentGear
                + "|" + accOn + "|" + tripEnabled + "|" + tripActive + "|"
                + recConfigured + "|" + consecutivePollFailures;
        if (!uiLogSig.equals(lastUiLogSig)) {
            lastUiLogSig = uiLogSig;
            Log.d(TAG, "updateUI: mode=" + configuredMode + " isRec=" + isRecording
                    + " modeActive=" + modeActive + " pipelineRunning=" + pipelineRunning
                    + " wedged=" + recordingWedged
                    + " gear=" + currentGear + " acc=" + accOn
                    + " tripEnabled=" + tripEnabled + " tripActive=" + tripActive
                    + " recConfigured=" + recConfigured + " shouldRec=" + (recConfigured && shouldRecordingBeActive())
                    + " pollFails=" + consecutivePollFailures);
        }

        // During the grace period (daemon briefly unreachable), keep the overlay
        // visible with last-known state. This prevents the pill from flickering
        // every time the daemon restarts or a single HTTP poll times out.
        if (!daemonReachable) {
            if (hadContentBefore && consecutivePollFailures < UNREACHABLE_THRESHOLD * 2) {
                // Still in grace window — keep overlay as-is, don't touch it.
                // The stale data is better than a disappearing/reappearing pill.
                return;
            }
            // Sustained unreachability — hide (but don't destroy) the overlay.
            // Force-collapse the action bar so the next reappearance
            // doesn't briefly show stale expanded state. setActionBarExpanded(false)
            // is null-safe via the actionBar guard inside it and clears
            // the autocollapse runnable too.
            Log.d(TAG, "updateUI: daemon unreachable for " + consecutivePollFailures + " polls — hiding overlay");
            setActionBarExpanded(false);
            if (overlayView != null) overlayView.setVisibility(View.GONE);
            return;
        }

        if (!anythingToShow) {
            // If the user disabled both segments via Settings, fully tear
            // down the overlay window so we don't keep a hidden View
            // attached to WindowManager. A hidden TYPE_APPLICATION_OVERLAY
            // still consumes a surface on BYD head units.
            Log.d(TAG, "updateUI: nothing to show — removing overlay");
            removeOverlay();
            hadContentBefore = false;
            // If audio capture also isn't configured, nothing here needs the
            // fast poll — drop to the idle cadence (the poll can't stop
            // entirely because it still drives audio reconcile + ACC-edge
            // detection, but with audio off there's nothing to reconcile).
            overlayFullyIdle = !audioEnabledConfig;
            return;
        }
        // Something is shown (or audio is active) — keep the normal poll cadence.
        overlayFullyIdle = false;

        // Hide overlay when ACC is off — car is parked, no need to show status.
        // We keep polling (at a slower rate) so we can show it again when ACC turns on.
        if (!accOn) {
            // Same rationale as the daemon-unreachable branch: clear the
            // action bar state so it doesn't pop up half-rendered when
            // ACC returns.
            setActionBarExpanded(false);
            if (overlayView != null) overlayView.setVisibility(View.GONE);
            return;
        }
        
        // Determine what's visible before creating the window.
        // Proximity guard should stay visible even when idle/armed (waiting for
        // a radar trigger) — hiding it would make users think the feature is off.
        boolean isProximityMode = "PROXIMITY_GUARD".equals(configuredMode);
        // Show the REC pill whenever the camera segment is enabled — it's
        // the tappable anchor for the mode action bar, so it must remain
        // present even when recording is stopped (mode NONE) or in a
        // standby state (drive mode parked). The pill's color/label below
        // communicates the actual state (off / armed / recording).
        boolean shouldShowRec = cameraOverlayEnabled || keepAliveForActionBar;
        boolean shouldShowTrip = tripEnabled && tripOverlayEnabled;
        // Mic visibility piggybacks on REC visibility. Show whenever audio
        // is armed (configured + recording mode set) so the "armed/idle"
        // amber state remains visible during P-gear standby — the user
        // wants to know audio capture is poised to fire even when REC
        // isn't currently recording. When mic is armed but REC is in
        // standby, we keep the overlay visible specifically for the mic.
        boolean shouldShowMic = audioEnabledConfig && recConfigured && cameraOverlayEnabled;

        if (!shouldShowRec && !shouldShowTrip && !shouldShowMic) {
            // Configured but conditions don't require display (e.g., drive mode in P).
            // Fully DETACH the window rather than leaving an empty GONE shell
            // attached: a GONE view inside an attached TYPE_APPLICATION_OVERLAY
            // still costs the system compositor a surface to blend every frame,
            // which on the shared Adreno 610 competes with the native IVI. The
            // poll's createOverlay() re-attaches at the saved position the moment
            // real content returns (idempotent, restores PREF_POS_X/Y). Mirrors
            // the "fully remove rather than leave an empty shell" pattern used by
            // the both-segments-off branch just below.
            removeOverlay();
            return;
        }

        // After config-merge gating, double-check: if BOTH user segments are
        // toggled off, fully remove the window rather than leaving an empty
        // shell attached. (anythingToShow guarded the entry, but reaching
        // here with both flags off means a partial config state — be safe.)
        if (!cameraOverlayEnabled && !tripOverlayEnabled) {
            removeOverlay();
            return;
        }

        // Latch hadContentBefore only on real, persistent content. The
        // transient action-bar keepalive must NOT mark "we had real
        // stuff to show" — otherwise a daemon blip 5s after the bar
        // collapses would freeze us into the grace window staring at
        // a stale expanded bar for up to 18s. Real content = a
        // configured recording mode or trip detection.
        boolean hasRealContent = (recConfigured && cameraOverlayEnabled)
                || (tripEnabled && tripOverlayEnabled);
        if (hasRealContent) {
            hadContentBefore = true;
        }
        
        // We have something to show — create overlay window if not yet created
        createOverlay();
        if (overlayView == null) return;
        
        overlayView.setVisibility(View.VISIBLE);

        // Recording pill. The pill is the ONLY entry point to the mode
        // action bar (tap → expand → OFF/CONT/DRIVE/PROX chips), so while
        // the camera segment is enabled we keep it visible in EVERY state
        // — including stopped (mode NONE) and standby (drive-mode parked).
        // Previously those states set the pill GONE, which made it
        // impossible to start recording from the overlay once it was off.
        // The icon/label/color below still communicate the real state.
        if (cameraOverlayEnabled || keepAliveForActionBar) {
            recContainer.setVisibility(View.VISIBLE);

            boolean shouldBeRecording = shouldRecordingBeActive();
            boolean isProximity = "PROXIMITY_GUARD".equals(configuredMode);

            // Compound "recording is live right now" truth. The daemon's raw
            // isRecording (recorder.isRecording()) reads FALSE for the
            // multi-second deferred-record window at cold start / ACC-on /
            // hardReset (encoder format not yet published — pendingRecordingPrefix
            // set, recordingMode=true) and on a transient writer-abort, even
            // though a clip is genuinely being set up and frames land moments
            // later. A 1-3s poll tick samples that window and used to paint a
            // false red "REC" / "not recording". modeActive stays TRUE across it
            // (RecordingModeManager continuousHealthy), so OR-ing (modeActive &&
            // pipelineRunning) suppresses that false negative.
            //
            // BUT modeActive is ALSO re-affirmed true on every wedge-retry for a
            // structurally stuck encoder (pending prefix that never resolves), so
            // modeActive alone would mask a genuine fault as GREEN forever. We
            // therefore also require !recordingWedged — the daemon publishes that
            // flag from the SAME wedge detection that drives its retry, so a stuck
            // activation falls through to the red shouldBeRecording branch below
            // instead of showing a false "recording". PROXIMITY_GUARD is excluded
            // (its not-recording state is the normal armed/idle state, handled by
            // its own amber branch below).
            boolean deferredActive = !isProximity && modeActive && pipelineRunning
                    && !recordingWedged;
            // FIX (false-GREEN): the daemon now reports wedged=true when a muxer
            // is open (isRecording()==true) but no video sample has reached disk
            // for >8s (SD unmount / ENOSPC / write failures). Previously the bare
            // `isRecording` term sat OUTSIDE the !wedged guard, so the pill stayed
            // GREEN over a dead writer. Gate isRecording on !recordingWedged too
            // so a confirmed disk-write stall falls through to the red
            // shouldBeRecording fault branch instead of a false "recording".
            boolean recordingLive = (isRecording && !recordingWedged) || deferredActive;

            if (!recConfigured) {
                // Recording is OFF. Muted anchor so the user can tap to
                // open the mode chips and turn it on. This is the state
                // the previous code never rendered (pill was GONE).
                ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_inactive);
                tvRecLabel.setText(R.string.overlay_mode_off_label);
                tvRecLabel.setTextColor(getColor(R.color.status_stopped));
            } else if (recordingLive) {
                // All good — recording as expected. Green for every mode,
                // including PROXIMITY_GUARD: when a radar trigger has actually
                // started a clip, the pill goes green so the user can tell at a
                // glance that recording is live RIGHT NOW. The "PROX" label
                // still distinguishes radar-triggered recording from
                // continuous/drive recording; armed-but-idle proximity stays
                // amber (see the isProximity branch below).
                ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_active);
                tvRecLabel.setText(isProximity ? "PROX" : "REC");
                tvRecLabel.setTextColor(getColor(R.color.status_success));
            } else if (isProximity) {
                // Proximity guard is armed but not currently recording (no radar
                // trigger). This is the NORMAL state for most of a drive — radar
                // mode records only on triggers, so "not recording" is not a
                // fault. Paint amber (armed/watching), NOT red. Checked BEFORE
                // shouldBeRecording because shouldRecordingBeActive() returns
                // true for proximity in any non-P gear, which would otherwise
                // route the normal armed state into the red "problem" branch
                // below and light the pill red for the whole drive.
                ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_inactive);
                tvRecLabel.setText("PROX");
                tvRecLabel.setTextColor(getColor(R.color.status_warning));
            } else if (shouldBeRecording) {
                // Problem — a continuous/drive mode should be recording but
                // isn't. (Proximity is handled above: its not-recording state
                // is armed/normal, not a fault.)
                ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_inactive);
                tvRecLabel.setText("REC");
                tvRecLabel.setTextColor(getColor(R.color.status_danger));
            } else {
                // Configured (e.g. drive mode) but standby — not recording is
                // expected here (drive mode in P gear). Keep the pill visible
                // as a muted standby anchor instead of hiding it, so the user
                // can still open the action bar to switch modes while parked.
                ivRecIcon.setImageResource(R.drawable.ic_overlay_rec_inactive);
                tvRecLabel.setText(R.string.overlay_rec_inactive_label);
                tvRecLabel.setTextColor(getColor(R.color.status_stopped));
            }
        } else {
            recContainer.setVisibility(View.GONE);
        }

        // Mic: show only when audio recording is configured AND a recording
        // mode that consumes audio is configured. Visible together with the
        // REC pill so the user can tell at a glance "video AND audio are
        // being captured" vs "video only". Tri-state color logic uses
        // BOTH the live audio capture state and the daemon's isRecording
        // flag so users distinguish capturing-but-not-muxing from off:
        //   - status_success (green): audio is being captured AND the
        //     daemon is currently muxing it into a segment.
        //   - status_warning (amber): audio is being captured but the
        //     daemon isn't muxing yet — pre-record buffer is filling /
        //     PROXIMITY_GUARD is armed waiting for a trigger / segment
        //     rotation in flight. Privacy-significant: this is the state
        //     where the cabin mic is open but no clip is being saved.
        //   - status_danger (red): we WANT to be capturing but the
        //     controller failed (mic claimed by BT/voice asst, etc).
        //     Held for AUDIO_FAILURE_HINT_WINDOW_MS so the user sees the
        //     reason their clip is silent rather than a flickering RED.
        boolean micVisibleByConfig = audioEnabledConfig && recConfigured && cameraOverlayEnabled;
        if (micVisibleByConfig) {
            micContainer.setVisibility(View.VISIBLE);
            boolean isCapturing =
                    com.overdrive.app.audio.AppAudioCaptureController
                            .isRecordingCaptureRunning();
            // Mode says we should be capturing right now (see reconcileAudioCapture).
            boolean wantCapture = audioEnabledConfig && daemonReachable && accOn
                && (configuredMode.equals("CONTINUOUS")
                    || configuredMode.equals("DRIVE_MODE")
                    || configuredMode.equals("PROXIMITY_GUARD"));
            long now = android.os.SystemClock.elapsedRealtime();
            boolean recentFailure = lastAudioStartFailureMs > 0
                && (now - lastAudioStartFailureMs) < AUDIO_FAILURE_HINT_WINDOW_MS;
            if (isCapturing && isRecording && !recordingWedged) {
                // FIX (false-GREEN): require !recordingWedged so the mic icon
                // can't stay green while the daemon reports the writer is
                // wedged (muxer open but nothing reaching disk). Otherwise the
                // cabin mic would read as "audio is being saved" while no clip
                // is actually being written — falls through to the amber
                // "capturing but not muxing" branch below instead.
                ivMicIcon.setImageResource(R.drawable.ic_overlay_mic_active);
                tvMicLabel.setText(R.string.overlay_mic_inactive_label);
                tvMicLabel.setTextColor(getColor(R.color.status_success));
            } else if (wantCapture && !isCapturing && recentFailure) {
                // Mic claimed / capture failure recently. RED so the user
                // knows their clip is silent and the app didn't just
                // forget to record.
                ivMicIcon.setImageResource(R.drawable.ic_overlay_mic_inactive);
                tvMicLabel.setText(R.string.overlay_mic_inactive_label);
                tvMicLabel.setTextColor(getColor(R.color.status_danger));
            } else {
                // Capturing but daemon isn't muxing yet, OR not capturing
                // because mode/conditions don't require it (PROX armed in
                // P, fresh ACC-on, segment rotation). Amber for "armed".
                ivMicIcon.setImageResource(R.drawable.ic_overlay_mic_inactive);
                tvMicLabel.setText(R.string.overlay_mic_inactive_label);
                tvMicLabel.setTextColor(getColor(R.color.status_warning));
            }
        } else {
            micContainer.setVisibility(View.GONE);
        }

        // Instant-replay clip segment. Visible only when a replay binding or
        // automation actually exists (daemon-reported `configured`) so the
        // majority of installs — which never set one up — see no new pill
        // segment. Piggybacks on the camera segment toggle like MIC, plus
        // its own Settings switch. Color states:
        //   - GREEN (status_success): a replay was accepted and is collecting
        //     its post-roll / exporting right now.
        //   - BLUE (status_info): the replay_*.mp4 finalized — held for 5s
        //     (REPLAY_RESULT_HOLD_MS) so the driver gets a positive "saved"
        //     confirmation, then decays to the idle gray.
        //   - RED (status_danger): the press did NOT produce a clip (rejected
        //     — no history / busy / restart required — or the accepted export
        //     failed). Held 5s. This used to be log-only, i.e. invisible.
        //   - GRAY (status_stopped): armed and idle.
        boolean replayVisibleByConfig = replayConfigured
                && cameraOverlayEnabled && replayOverlayEnabled;
        if (replayVisibleByConfig && replayContainer != null) {
            replayContainer.setVisibility(View.VISIBLE);
            String replayDisplay = computeReplayDisplay();
            if ("recording".equals(replayDisplay)) {
                ivReplayIcon.setImageResource(R.drawable.ic_overlay_replay_active);
                tvReplayLabel.setText("CLIP");
                tvReplayLabel.setTextColor(getColor(R.color.status_success));
            } else if ("saved".equals(replayDisplay)) {
                ivReplayIcon.setImageResource(R.drawable.ic_overlay_replay_saved);
                tvReplayLabel.setText("CLIP");
                tvReplayLabel.setTextColor(getColor(R.color.status_info));
            } else if ("failed".equals(replayDisplay)) {
                ivReplayIcon.setImageResource(R.drawable.ic_overlay_replay_inactive);
                tvReplayLabel.setText("CLIP");
                tvReplayLabel.setTextColor(getColor(R.color.status_danger));
            } else {
                ivReplayIcon.setImageResource(R.drawable.ic_overlay_replay_inactive);
                tvReplayLabel.setText("CLIP");
                tvReplayLabel.setTextColor(getColor(R.color.status_stopped));
            }
        } else if (replayContainer != null) {
            replayContainer.setVisibility(View.GONE);
        }

        // Trip: show only if enabled in config AND user hasn't toggled the
        // trip segment off in Settings → Status overlay.
        if (tripEnabled && tripOverlayEnabled) {
            tripContainer.setVisibility(View.VISIBLE);
            if (tripActive) {
                ivTripIcon.setImageResource(R.drawable.ic_overlay_trip_active);
                tvTripLabel.setText("TRIP");
                tvTripLabel.setTextColor(getColor(R.color.status_success));
            } else {
                ivTripIcon.setImageResource(R.drawable.ic_overlay_trip_inactive);
                tvTripLabel.setText("TRIP");
                tvTripLabel.setTextColor(getColor(R.color.status_danger));
            }
        } else {
            tripContainer.setVisibility(View.GONE);
        }

        // Show/hide the three separators based on which segments are visible.
        // Layout order: REC | sep1 | MIC | sep2 | CLIP | sep3 | TRIP. A
        // separator is visible iff there is at least one visible segment on
        // each side of it.
        View separatorRecMic = overlayView.findViewById(R.id.separatorRecMic);
        View separatorMicReplay = overlayView.findViewById(R.id.separatorMicReplay);
        View separator = overlayView.findViewById(R.id.separator);
        boolean recVisible = recContainer.getVisibility() == View.VISIBLE;
        boolean micVisible = micContainer.getVisibility() == View.VISIBLE;
        boolean replayVisible = replayContainer != null
                && replayContainer.getVisibility() == View.VISIBLE;
        boolean tripVisible = tripContainer.getVisibility() == View.VISIBLE;
        if (separatorRecMic != null) {
            // sep1 sits between REC and MIC — visible only when both sides
            // have something to show.
            separatorRecMic.setVisibility(
                recVisible && micVisible ? View.VISIBLE : View.GONE);
        }
        if (separatorMicReplay != null) {
            // sep2 sits between (REC|MIC) and CLIP.
            separatorMicReplay.setVisibility(
                (recVisible || micVisible) && replayVisible ? View.VISIBLE : View.GONE);
        }
        if (separator != null) {
            // sep3 sits between (REC|MIC|CLIP) and TRIP — visible iff trip is
            // visible AND anything to its left is visible.
            boolean leftSideVisible = recVisible || micVisible || replayVisible;
            separator.setVisibility(
                leftSideVisible && tripVisible ? View.VISIBLE : View.GONE);
        }

        // Sync expanded action bar visibility + selection. The expanded
        // state is owned by the user-input layer (tap on REC chip) but
        // we still re-read configuredMode here so the highlighted chip
        // tracks any mode change applied from the web UI while the bar
        // is open.
        if (actionBar != null) {
            actionBar.setVisibility(actionBarExpanded ? View.VISIBLE : View.GONE);
            if (actionBarExpanded) refreshActionBarSelection();
        }
    }

    // ==================== RESTART ACTIONS ====================

    /**
     * Determine if recording SHOULD be active right now based on mode, gear, and ACC state.
     * 
     * Rules (from RecordingModeManager):
     * - CONTINUOUS: should record whenever ACC is ON
     * - DRIVE_MODE: should record in driving gears (D, R, S, M) when ACC is ON
     * - PROXIMITY_GUARD: should be active in all gears except P when ACC is ON
     */
    private boolean shouldRecordingBeActive() {
        if (!accOn) return false;
        
        switch (configuredMode) {
            case "CONTINUOUS":
                return true;
            case "DRIVE_MODE":
                return isDrivingGear(currentGear);
            case "PROXIMITY_GUARD":
                return !"P".equals(currentGear);
            default:
                return false;
        }
    }
    
    /**
     * Check if gear is a driving gear (D, R, S, M, N — not P).
     * N is included because BYD Auto Hold reports N while stopped at traffic lights.
     */
    private static boolean isDrivingGear(String gear) {
        return "D".equals(gear) || "R".equals(gear) || "S".equals(gear) || "M".equals(gear) || "N".equals(gear);
    }

    /**
     * Restart recording by re-sending the configured mode via TCP.
     * This just re-triggers what's already configured — no config change.
     */
    private void restartRecording() {
        executor.execute(() -> {
            java.net.Socket socket = null;
            try {
                // Bounded connect timeout — same rationale as
                // sendSetRecordingMode: a wedged daemon (mid-shutdown
                // not yet listening) would otherwise block this single-
                // thread executor indefinitely and starve status polls
                // and any subsequent chip taps for the OS-level TCP
                // connect timeout (minutes).
                socket = new java.net.Socket();
                socket.connect(
                    new java.net.InetSocketAddress("127.0.0.1", 19876), 1500);
                socket.setSoTimeout(3000);

                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "setRecordingMode");
                cmd.put("mode", configuredMode);

                java.io.OutputStream os = socket.getOutputStream();
                os.write((cmd.toString() + "\n").getBytes());
                os.flush();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String response = reader.readLine();
                Log.i(TAG, "Restart recording (" + configuredMode + "): " + response);
            } catch (Exception e) {
                Log.e(TAG, "Restart recording failed: " + e.getMessage());
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * Restart trip detection by toggling the config off then on.
     * This re-initializes the TripDetector without changing user settings.
     */
    private void restartTripDetection() {
        executor.execute(() -> {
            try {
                // Toggle off then on to force re-init
                postTripConfig(false);
                Thread.sleep(500);
                postTripConfig(true);
                Log.i(TAG, "Restart trip detection: toggled");
            } catch (Exception e) {
                Log.e(TAG, "Restart trip failed: " + e.getMessage());
            }
        });
    }

    private void postTripConfig(boolean enabled) {
        HttpURLConnection conn = null;
        try {
            conn = com.overdrive.app.util.DaemonHttpClient.open(
                "/api/trips/config", "POST", 2000, 2000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            JSONObject body = new JSONObject();
            body.put("enabled", enabled);
            conn.getOutputStream().write(body.toString().getBytes());
            conn.getOutputStream().flush();
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== NOTIFICATION ====================

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Status Overlay", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Recording and trip status overlay");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tag with the shared Overdrive group key so DaemonKeepaliveService's
        // group-summary collapses this entry under a single shade row.
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.status_overlay_notif_title))
                .setContentText(getString(R.string.status_overlay_notif_text))
                .setSmallIcon(R.drawable.ic_recording)
                .setContentIntent(pi)
                .setOngoing(true)
                .setGroup(com.overdrive.app.services.DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }

    // ==================== STATIC HELPERS ====================

    public static boolean hasOverlayPermission(Context context) {
        boolean has = OverlayPermissionChecker.isGranted(context);
        Log.i(TAG, "hasOverlayPermission: " + has);
        return has;
    }

    public static boolean startIfPermitted(Context context) {
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "startIfPermitted: NO overlay permission — service not started");
            return false;
        }
        Log.i(TAG, "startIfPermitted: permission OK — starting service");
        Intent intent = new Intent(context, StatusOverlayService.class);
        context.startForegroundService(intent);
        return true;
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, StatusOverlayService.class));
    }

    /**
     * Trigger a re-inflation of the overlay so a freshly-changed theme
     * takes effect immediately. AppCompatDelegate.setDefaultNightMode()
     * fires onConfigurationChanged for foreground Activities but NOT for
     * plain Services, so the overlay would otherwise stay on its old
     * palette until the system config changed for unrelated reasons.
     *
     * No-op when overlay permission is missing or the service isn't
     * running — startForegroundService would just respawn an unwanted
     * pill in that case.
     */
    public static void refreshTheme(Context context) {
        if (!hasOverlayPermission(context)) return;
        Intent intent = new Intent(context, StatusOverlayService.class);
        intent.setAction(ACTION_REFRESH_THEME);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "refreshTheme failed: " + e.getMessage());
        }
    }

    public static final String ACTION_REFRESH_THEME =
            "com.overdrive.app.overlay.REFRESH_THEME";
}
