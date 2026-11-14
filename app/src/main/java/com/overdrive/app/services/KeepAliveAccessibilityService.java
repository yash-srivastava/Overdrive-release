package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.overdrive.app.ui.daemon.DaemonStartupManager;
import com.overdrive.app.util.DaemonHttpClient;

import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal AccessibilityService that keeps the app process alive indefinitely.
 *
 * Android's OOM killer and OEM process killers (including BYD's DiLink firmware)
 * are hardcoded to never kill a process hosting an active AccessibilityService.
 * This gives our app the highest possible process priority — same tier as the
 * keyboard or phone call — preventing the 24-hour kill cycle on newer BYD firmware.
 *
 * Accessibility events are used only to cache the active package for conditional
 * key mappings; no window content is inspected. The foreground notification
 * provides user visibility.
 *
 * On DiLink 3.0 this service can only bind while the app process is already
 * running: BYD's start gate silently refuses the system's bind otherwise, leaving
 * the service in "Binding services" indefinitely. It binds reliably here because
 * the daemon keeps the process alive. See BYD_ACCESSIBILITY_BIND_GATE_DISCOVERY.md.
 *
 * Enable via ADB (one-time):
 *   settings put secure enabled_accessibility_services com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService
 *   settings put secure accessibility_enabled 1
 */
public class KeepAliveAccessibilityService extends AccessibilityService {

    private static final String TAG = "KeepAliveA11y";

    // volatile: written on the main thread in onServiceConnected/onUnbind/onDestroy,
    // read from callers on other threads (the setup wizard's autostart button).
    private static volatile KeepAliveAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** The bound service, or null when the a11y service isn't enabled or connected. */
    public static KeepAliveAccessibilityService getInstance() {
        return instance;
    }

    /**
     * Result of a setup-guide autostart-enable run. Delivered on the
     * main thread so UI callers can update views directly.
     */
    public interface Callback {
        void onResult(boolean success, AutoStartEnabler.Result result);
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        Log.i(TAG, "AccessibilityService connected — process is now protected");

        // Start the app-process signal monitors FIRST — BEFORE the setServiceInfo() block
        // below, which early-returns if the info-set throws. These monitors are independent
        // of key-filtering (they relay phone-call + Bluetooth state to the daemon, which
        // can't read them from UID 2000); gating them behind a successful setServiceInfo
        // meant a key-filter wiring failure ALSO silently killed call/BT automations. Both
        // are idempotent + self-guarding, so starting them early is safe.
        try {
            CallStateMonitor.start(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "CallStateMonitor start failed: " + t.getMessage());
        }
        try {
            BluetoothStateMonitor.start(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "BluetoothStateMonitor start failed: " + t.getMessage());
        }

        // Config must match the WORKING shape proven on DiLink firmware (verified
        // against a known-good OEM app): a service that subscribes to ZERO event
        // types (eventTypes=0) is treated as inert by this firmware's
        // AccessibilityManager — it binds, but onKeyEvent is NEVER dispatched to
        // it (observed live: service bound, capabilities=8, yet keycode 302 hit
        // WindowManager with FLAG_PASS_TO_USER and never reached onKeyEvent). The
        // fix is to subscribe to a real event type (typeWindowStateChanged) and
        // enable window-content retrieval, which fully wires the service into the
        // input path so FLAG_REQUEST_FILTER_KEY_EVENTS actually delivers keys.
        //
        // We MODIFY the info returned by getServiceInfo() (which already carries
        // the manifest XML config) rather than rebuilding it, and only assert the
        // fields that matter — never zero out eventTypes.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        // Mirror the proven OEM config EXACTLY (no extra flags): subscribe to a
        // real event type + report-view-ids + filter-key-events. Adding more than
        // the known-good set risks a different firmware quirk, so match it 1:1.
        // One addition on top of that shape: seat-position capture subscribes to
        // TYPE_VIEW_LONG_CLICKED so onAccessibilityEvent sees the user long-pressing
        // BYD's seat-position buttons. No extra flags — the handler reads the event
        // source's resource-id, which flagReportViewIds already covers. eventTypes
        // changes have regressed key delivery on this firmware before, so a build
        // carrying this should re-verify that a mapped hardware key still fires.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        // Wrap the info-set and publish instance=this ONLY on success. Previously
        // `instance` was set before setServiceInfo(): if the info-set threw, the key
        // filter wasn't wired yet in-proc isRunning() reported a false "healthy" while
        // keys were dead. Setting instance only after the wiring is actually in force
        // makes the liveness signal honest (a partial connect reads as not-live, so the
        // watchdog rebinds rather than trusting a half-connected service).
        try {
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.e(TAG, "setServiceInfo failed — key filtering NOT wired; leaving instance null "
                    + "so the watchdog rebinds: " + t.getMessage());
            return;
        }
        instance = this;

        // Seed the conditional-keymap foreground cache in case the active window
        // was already open before this service connected. Future window changes
        // update the same cache from onAccessibilityEvent().
        AccessibilityNodeInfo root = null;
        String seededPkg = null;
        try {
            root = getRootInActiveWindow();
            seededPkg = root != null ? stringValue(root.getPackageName()) : null;
            KeyMapDispatcher.INSTANCE.onForegroundPackageChanged(seededPkg);
        } catch (Throwable t) {
            KeyMapDispatcher.INSTANCE.onForegroundPackageChanged(null);
            Log.w(TAG, "Unable to seed active package: " + t.getMessage());
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        // The widgets' reading, in its own try so it never touches the one above. There is no class here,
        // and a window of ours, the system UI's or the keyboard's says nothing about the app on top.
        try {
            if (seededPkg == null || !(seededPkg.equals(getPackageName()) || seededPkg.equals("com.android.systemui")
                    || seededPkg.equals(imePackage()))) {
                KeyMapDispatcher.INSTANCE.onTopAppChanged(seededPkg, null);
            }
        } catch (Throwable t) {
            KeyMapDispatcher.INSTANCE.onTopAppChanged(null, null);
        }

        // Prime the key-mapping snapshot off-thread so the first hardware key
        // press already has its bindings (onKeyEvent never reads disk itself).
        try {
            KeyMapDispatcher.INSTANCE.warmUp();
        } catch (Throwable t) {
            Log.w(TAG, "KeyMapDispatcher warmUp failed: " + t.getMessage());
        }

        // No foreground notification needed — DaemonKeepaliveService already has one.
        // The AccessibilityService binding alone is enough to protect the process.

        // Ensure daemons are running (respawn if killed)
        try {
            DaemonStartupManager.Companion.startOnBoot(getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "Daemon startup from A11y service: " + e.getMessage());
        }
    }

    /**
     * BYD exposes the SAME three profile positions from two different apps, and a long-press
     * saves in both — so both have to be watched or capture silently misses half the ways the
     * user actually saves a position.
     *
     * <p>{@code com.byd.carsettings} is the Settings "Sjåfør" dialog, ids {@code location1..3}.
     *
     * <p>{@code com.byd.diLinkAccount} is a floating widget that appears OVER whatever app is
     * open, whenever the seat or mirrors are moved with the physical controls while the car is
     * on and stationary. Confirmed as the window owner on the car (2026-08-11). Its layout is
     * {@code window_glb_driver_pos} and its own prompt string reads "Long press the widget to
     * save the seat/steering wheel/rearview mirror position, and tap the widget to apply the
     * position" — i.e. the same save gesture, on the same three slots.
     */
    private static final String SEAT_POS_PKG = "com.byd.carsettings";
    private static final String SEAT_POS_WIDGET_PKG = "com.byd.diLinkAccount";

    /** Off-thread HTTP to the daemon so the a11y callback never blocks. */
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Two consumers now share this callback, so dispatch on event type rather than
        // assuming one. Upstream tracks the foreground package from window-state changes for
        // conditional key mappings; seat capture watches for a long-press on BYD's own
        // position buttons. Neither is interested in the other's event.
        if (event == null) return;
        final int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                KeyMapDispatcher.INSTANCE.onForegroundPackageChanged(
                        stringValue(event.getPackageName()));
            } catch (Throwable t) {
                // Foreground detection is advisory. Unknown must fail open so a
                // conditional mapping never suppresses the vehicle's default key action.
                KeyMapDispatcher.INSTANCE.onForegroundPackageChanged(null);
            }
            // The widgets' reading is kept apart, so it never changes what key mappings see.
            try {
                trackTopApp(event);
            } catch (Throwable t) {
                KeyMapDispatcher.INSTANCE.onTopAppChanged(null, null);
            }
            return;
        }

        // Only interested in the user long-pressing a BYD seat-position button (the
        // "save current position" gesture). Everything else is ignored cheaply.
        if (type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        if (!SEAT_POS_PKG.contentEquals(pkg) && !SEAT_POS_WIDGET_PKG.contentEquals(pkg)) return;
        int slot = seatSlotFromEvent(event);
        if (slot < 1) return;
        Log.i(TAG, "seat-position long-press: slot " + slot + " — capturing geometry");
        captureSeatPosition(slot);
    }

    private static String stringValue(CharSequence value) {
        return value != null ? value.toString() : null;
    }

    /**
     * For the home-screen widgets: every window change tells them to ask the daemon what is on top, and an
     * activity becomes their stand-in reading. Overlays (ours too), popups, dialogs, the shade and the
     * keyboard send this event as well, and nothing announces it when they close, so they never become it.
     */
    private void trackTopApp(AccessibilityEvent event) {
        // a second screen (the cluster) has its own top app
        if (android.os.Build.VERSION.SDK_INT >= 33 && event.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) return;
        KeyMapDispatcher.INSTANCE.onWindowEvent();
        String pkg = stringValue(event.getPackageName());
        String cls = stringValue(event.getClassName());
        if (pkg != null && !isActivityWindow(pkg, cls)) return;
        KeyMapDispatcher.INSTANCE.onTopAppChanged(pkg, cls);
    }

    /** Package of the current keyboard, or null. */
    private String imePackage() {
        String id = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        if (id == null) return null;
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : id;
    }

    // Window class → "is an activity of that package", looked up once each (events arrive on the main thread).
    private final java.util.HashMap<String, Boolean> activityClasses = new java.util.HashMap<>();

    /** True when {@code cls} is an activity of {@code pkg}. A missing class name counts as one, as before. */
    private boolean isActivityWindow(String pkg, String cls) {
        if (cls == null || cls.isEmpty()) return true;
        String key = pkg + "/" + cls;
        Boolean known = activityClasses.get(key);
        if (known != null) return known;
        boolean activity;
        try {
            getPackageManager().getActivityInfo(new android.content.ComponentName(pkg, cls), 0);
            activity = true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            activity = false;
        }
        if (activityClasses.size() >= 256) activityClasses.clear();
        activityClasses.put(key, activity);
        return activity;
    }

    /**
     * Resolve which native slot (1..3) was long-pressed, from the source view's resource-id.
     * Resource-id is language-independent, unlike the button text ("Posisjon N").
     *
     * <p>Two id schemes, because the same three slots are presented by two apps:
     * <ul>
     *   <li>Settings dialog: {@code com.byd.carsettings:id/location1|2|3} — the number IS the slot.
     *   <li>Floating widget: {@code com.byd.diLinkAccount:id/(iv|tv)_(drive|rest|standby)} —
     *       named, not numbered. Both the image and the label carry an id, and which one the
     *       long-press reports depends on where the finger lands, so accept either.
     * </ul>
     *
     * <p>The widget's drive/rest/standby → 1/2/3 mapping is INFERRED from the order the three
     * groups appear in {@code window_glb_driver_pos} matching the app's own "Position 1/2/3"
     * strings. It is not proven. If it were wrong the mistake would be loud rather than silent:
     * the daemon names a captured entry from {@code driverPos_N}, so pressing the top slot would
     * visibly store it as "Posisjon 2". Worth a glance on the first widget capture.
     *
     * <p>Returns -1 if the long-press wasn't on a seat-position slot.
     */
    private int seatSlotFromEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo src = null;
        try {
            src = event.getSource();
            if (src == null) return -1;
            CharSequence rid = src.getViewIdResourceName();
            if (rid == null) return -1;
            String s = rid.toString();

            // Settings dialog — numbered ids.
            String prefix = SEAT_POS_PKG + ":id/location";
            if (s.startsWith(prefix) && s.length() == prefix.length() + 1) {
                char c = s.charAt(s.length() - 1);
                if (c >= '1' && c <= '3') return c - '0';
            }

            // Floating widget — named ids, either the icon (iv_) or the label (tv_).
            String widgetPrefix = SEAT_POS_WIDGET_PKG + ":id/";
            if (s.startsWith(widgetPrefix)) {
                String name = s.substring(widgetPrefix.length());
                if (name.startsWith("iv_") || name.startsWith("tv_")) {
                    String slot = name.substring(3);
                    if ("drive".equals(slot)) return 1;
                    if ("rest".equals(slot)) return 2;
                    if ("standby".equals(slot)) return 3;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "seatSlotFromEvent: " + t.getMessage());
        } finally {
            if (src != null) { try { src.recycle(); } catch (Throwable ignored) {} }
        }
        return -1;
    }

    /**
     * Ask the daemon to read the live seat+mirror geometry and store it as the
     * captured entry for this native slot. The a11y service runs in the app UI
     * process (uid = app), which cannot read BYD geometry directly (signature-perm
     * gated to uid 2000); the daemon on :8080 can, so we POST there — same pattern
     * as KeyMapDispatcher.fire(). Runs off the a11y callback thread.
     */
    private void captureSeatPosition(int slot) {
        captureExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = DaemonHttpClient.open("/api/positions/capture?slot=" + slot, "POST", 3000, 8000);
                int code = conn.getResponseCode();
                Log.i(TAG, "capture slot " + slot + " -> HTTP " + code);
            } catch (Throwable t) {
                Log.w(TAG, "capture slot " + slot + " failed: " + t.getMessage());
            } finally {
                if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
            }
        });
    }

    /**
     * Hardware key filter. With FLAG_REQUEST_FILTER_KEY_EVENTS this is invoked
     * for physical KeyEvents (steering-wheel / dash buttons) BEFORE the OEM
     * handler. Returning true CONSUMES the event so the default action does not
     * also run; false lets it pass through untouched.
     *
     * Delegates to {@link KeyMapDispatcher}, which is fast (a config lookup) and
     * punts any actual actuation to a background thread — this callback runs on
     * the platform input-dispatch path and must never block, or the whole system
     * UI would ANR. If the feature is off or the key is unmapped, the dispatcher
     * returns false and the key behaves exactly as stock.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            // Bring-up diagnostic for "does this firmware dispatch hardware keys
            // to our filter at all". Now gated behind isLoggable: this method runs
            // on the PLATFORM INPUT-DISPATCH path, so an unconditional Log.i +
            // string concat here taxes every hardware key press system-wide (and
            // fires continuously while a key is held down, via getRepeatCount).
            // Key mapping is field-confirmed, so the log stays available on demand
            // (`setprop log.tag.KeepAliveA11y DEBUG`) without paying for it on
            // every keystroke.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onKeyEvent keyCode=" + event.getKeyCode()
                        + " down=" + down + " repeat=" + event.getRepeatCount());
            }
            return KeyMapDispatcher.INSTANCE.onKey(
                    event.getKeyCode(), down, event.getRepeatCount());
        } catch (Throwable t) {
            // Never let a mapping bug swallow keys or crash input dispatch —
            // fail open (pass the key through) on any error.
            Log.w(TAG, "onKeyEvent error, passing through: " + t.getMessage());
            return false;
        }
    }

    /**
     * Drive {@link AutoStartEnabler} once, off the main thread, reporting the outcome back
     * on it.
     *
     * <p>Triggered by the visible post-update setup guide or its manual retry button. It is
     * intentionally not called from onServiceConnected: that callback can recur during
     * ordinary service churn and repeatedly pop BYD's dialog. Single-flight is enforced
     * inside the enabler.
     */
    public void runAutoStartEnabler(final Callback callback) {
        try {
            final AutoStartEnabler enabler = new AutoStartEnabler(this);
            Thread worker = new Thread(() -> {
                AutoStartEnabler.Result result = null;
                try {
                    result = enabler.run();
                } catch (Throwable t) {
                    Log.w(TAG, "runAutoStartEnabler worker threw: " + t);
                }
                final AutoStartEnabler.Result fResult = result;
                final boolean success = result == AutoStartEnabler.Result.SUCCESS
                        || result == AutoStartEnabler.Result.ALREADY_OFF;
                mainHandler.post(() -> {
                    if (callback == null) return;
                    try {
                        callback.onResult(success, fResult);
                    } catch (Throwable t) {
                        Log.w(TAG, "AutoStartEnabler callback threw: " + t);
                    }
                });
            }, "autostart-enabler");
            worker.start();
        } catch (Throwable t) {
            Log.w(TAG, "runAutoStartEnabler failed to start worker: " + t);
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, null));
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "AccessibilityService unbound — clearing instance");
        // Only if it is still OURS: a reconnect can construct the new instance before the old
        // one unbinds, and an unguarded null would erase the live service's registration.
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
        // No-op
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "AccessibilityService destroyed — attempting restart");
        if (instance == this) instance = null;

        // Non-daemon single-thread pool with no core timeout: every disable/re-enable cycle
        // that captured a position would otherwise strand one more idle thread for the life
        // of the process.
        try {
            captureExecutor.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "captureExecutor shutdown failed: " + t.getMessage());
        }

        // Clear transient key-gesture state so a gesture torn down mid-flight
        // (service destroyed between a promoting DOWN and its UP) can't strand a
        // suppressLongUntilUp entry and swallow the next long-press after re-enable.
        // Honors KeyMapDispatcher.teardown()'s documented contract; the FileObserver
        // is intentionally left running (warmUp()'s watcher != null guard keeps a
        // reconnect from leaking a second observer).
        try {
            KeyMapDispatcher.INSTANCE.teardown();
        } catch (Throwable t) {
            Log.w(TAG, "KeyMapDispatcher teardown failed: " + t.getMessage());
        }

        // Self-restart: send broadcast to trigger re-enable
        try {
            Intent restartIntent = new Intent("com.overdrive.app.RESTART_ACCESSIBILITY");
            sendBroadcast(restartIntent);
        } catch (Exception e) {
            Log.e(TAG, "Restart broadcast failed: " + e.getMessage());
        }

        super.onDestroy();
    }
}
