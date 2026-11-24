package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.overdrive.app.R;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.overlay.SetupGuideDialog;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Re-enables OverDrive's autostart by driving the BYD "Deaktiver Autostart" dialog.
 *
 * <p>BYD's DiLink firmware blocks any /data app from autostarting at boot unless
 * its per-app value in {@code com.byd.appstartmanagement} ("Deaktiver Autostart")
 * is toggled OFF. That value is RESET (app re-blocked) on every install/update but
 * PERSISTS across ordinary reboots.
 *
 * <p>This runs when the user taps the autostart button in the setup wizard
 * ({@link com.overdrive.app.overlay.SetupGuideDialog}), or, when the user opted in,
 * from {@link AutoStartReapply}: once per install, screen on and parked, capped at a
 * few attempts. The earlier unconditional onServiceConnected trigger was removed
 * because it re-fired on every a11y reconnect / app churn and repeatedly popped the
 * BYD dialog.
 *
 * <p>The dialog is a {@code mShowToOwnerOnly} window above the launcher, so
 * {@code getRootInActiveWindow()} returns the launcher, not the dialog. Only
 * {@link AccessibilityService#getWindows()} (with flagRetrieveInteractiveWindows)
 * reaches its nodes — hence the verbose logging.
 *
 * <p>Driven from {@link KeepAliveAccessibilityService#runAutoStartEnabler}, which
 * calls {@link #run()} on a background thread and posts the {@link Result} back on
 * the main thread. Single-flight guarded; never throws to the caller.
 */
public class AutoStartEnabler {

    private static final String TAG = "AutostartEnabler";

    // BYD dialog package (the row-and-switch screen).
    private static final String BYD_APPSTART_PKG = SetupGuideDialog.BYD_APPSTART_PKG;

    // Timing / bounds.
    private static final long DIALOG_WAIT_MS = 5000L;
    private static final long POLL_INTERVAL_MS = 250L;
    private static final int MAX_SCROLLS = 20;
    private static final long CLICK_CONFIRM_DELAY_MS = 600L;
    private static final long GESTURE_TIMEOUT_MS = 2000L;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_ANCESTOR_CLIMB = 6;

    // Single-flight across the whole process — only ever one run in flight.
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final AccessibilityService service;

    /** True once BYD's dialog has been seen, so closeDialog() knows a BACK belongs to it. */
    private boolean dialogSeen;

    /** True once a BACK has already been sent for it, so the final cleanup doesn't send another. */
    private boolean dialogClosed;

    public AutoStartEnabler(AccessibilityService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------------
    // Public entry point
    // ---------------------------------------------------------------------

    /**
     * Drive the enabler once, synchronously, on the CALLER's thread — must NOT be
     * the main thread (it blocks on getWindows() polling). Callers should invoke
     * this from {@link KeepAliveAccessibilityService#runAutoStartEnabler}, which
     * provides the background worker and posts the result back on the main thread.
     *
     * <p>Single-flight guarded so a double-tap can't double-run. Returns the final
     * {@link Result}, or {@code null} if another run was already in flight.
     */
    public Result run() {
        if (!RUNNING.compareAndSet(false, true)) {
            log("another enabler run is already in flight — aborting this one");
            return null;
        }
        Result last = Result.NO_DIALOG;
        try {
            // Log the ACC state for diagnostics; do NOT gate on it. This is a
            // deliberate, user-initiated tap in the setup wizard (display on,
            // user present), so a brief dialog flash is expected and fine.
            log("UX context: accOn=" + AccMonitor.isAccOn()
                    + " inSentry=" + AccMonitor.isInSentryMode()
                    + " (button-triggered; proceeding)");

            boolean success = false;
            int attempts = 0;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS && !success; attempt++) {
                attempts = attempt;
                log("attempt " + attempt + "/" + MAX_ATTEMPTS);
                last = attemptOnce();
                log("attempt " + attempt + " result=" + last);
                if (last == Result.SUCCESS || last == Result.ALREADY_OFF) {
                    success = true;
                } else if (last == Result.NOT_THIS_FIRMWARE) {
                    // No appstartmanagement component — never going to work here.
                    log("appstartmanagement not present on this firmware — no retry");
                    break;
                } else if (last == Result.NO_ROW) {
                    // The search already resets BYD's retained list position and scans to
                    // the end, so repeating it can only produce another dialog flash.
                    log("row not present after a full scroll — no retry");
                    break;
                }
                // Unwind this attempt before retrying so the next launch starts from the
                // known row-and-switch screen instead of reusing a stale task/window.
                if (!success && attempt < MAX_ATTEMPTS && dialogSeen) {
                    closeDialog();
                    dialogClosed = true;
                }
            }

            log(success
                    ? "SUCCESS — autostart switch is OFF (result=" + last + ")"
                    : "FAILED after " + attempts + " attempt(s) (result=" + last
                            + ") — caller falls back to manual settings");
            return last;
        } catch (Throwable t) {
            log("run threw: " + t);
            return last;
        } finally {
            // In the finally, not the try: a stale-node throw from attemptOnce would otherwise
            // leave BYD's dialog on screen while the caller relaunches that same activity.
            // Only when one actually surfaced — an unconditional BACK would land on whatever
            // IS foreground, i.e. the setup wizard that launched us.
            if (dialogSeen && !dialogClosed) {
                closeDialog();
            } else if (!dialogSeen) {
                log("no dialog ever surfaced — skipping BACK so the caller's UI is left alone");
            }
            RUNNING.set(false);
        }
    }

    public enum Result {
        SUCCESS,          // switch flipped ON->OFF and confirmed
        ALREADY_OFF,      // switch was already OFF (idempotent no-op)
        NOT_THIS_FIRMWARE,// appstartmanagement activity missing -> abort, no retry
        NO_DIALOG,        // dialog window never surfaced
        NO_ROW,           // OverDrive row / switch not found
        FLIP_UNCONFIRMED  // clicked but state didn't confirm OFF
    }

    public Result attemptOnce() {
        // (a) Guard: service must be connected (getWindows returns [] otherwise).
        List<AccessibilityWindowInfo> pre = service.getWindows();
        if (pre == null) {
            log("getWindows() returned null — service not connected? aborting attempt");
            return Result.NO_DIALOG;
        }

        // (b) Launch the BYD dialog (reuse OverDrive's canonical intent).
        try {
            Intent intent = SetupGuideDialog.buildAppStartManagementIntent();
            service.startActivity(intent);
            log("launched " + BYD_APPSTART_PKG + " dialog intent");
        } catch (ActivityNotFoundException anfe) {
            log("ActivityNotFoundException launching appstartmanagement: " + anfe.getMessage());
            return Result.NOT_THIS_FIRMWARE;
        } catch (Throwable t) {
            log("failed launching appstartmanagement dialog: " + t);
            return Result.NO_DIALOG;
        }

        // (c)+(d) Wait for + locate the dialog window by polling getWindows().
        AccessibilityNodeInfo dialogRoot = waitForDialogRoot();
        if (dialogRoot == null) {
            log("dialog window for " + BYD_APPSTART_PKG + " NOT found within "
                    + DIALOG_WAIT_MS + "ms");
            return Result.NO_DIALOG;
        }
        dialogSeen = true;
        dialogClosed = false;   // a fresh dialog needs its own BACK
        log("dialog root acquired: pkg=" + dialogRoot.getPackageName());

        // (e) Find OverDrive's row + its Switch (scrolling if needed).
        AccessibilityNodeInfo overdriveSwitch = findOverDriveSwitch(dialogRoot);
        if (overdriveSwitch == null) {
            log("OverDrive switch NOT found in dialog (after scroll search)");
            return Result.NO_ROW;
        }

        // (f) Read + flip.
        boolean checkedBefore = overdriveSwitch.isChecked();
        log("OverDrive switch found: isChecked(before)=" + checkedBefore
                + " (ON=blocked, OFF=autostart-allowed)");
        if (!checkedBefore) {
            log("switch already OFF — autostart already allowed, no-op");
            return Result.ALREADY_OFF;
        }

        boolean clicked = clickSwitch(overdriveSwitch);
        log("switch click issued (nodeClick+gesture)=" + clicked);

        // (f cont.) Confirm the flip. Prefer AccessibilityNodeInfo.refresh() on the SAME
        // node: it re-reads that node's live state in place, without a fresh getWindows()
        // scan. The old re-scan approach was flaky in the moments right after a click and
        // produced false FLIP_UNCONFIRMED results even though the switch had visibly flipped
        // (observed 2026-07-07: switch flipped correctly but the button reported failure).
        sleep(CLICK_CONFIRM_DELAY_MS);
        if (overdriveSwitch.refresh() && !overdriveSwitch.isChecked()) {
            log("confirmed via refresh(): switch now OFF");
            return Result.SUCCESS;
        }
        // Fallback: best-effort re-scan of the dialog.
        AccessibilityNodeInfo freshRoot = findDialogRoot();
        AccessibilityNodeInfo freshSwitch = (freshRoot != null) ? findOverDriveSwitch(freshRoot) : null;
        if (freshSwitch != null) {
            boolean checkedAfter = freshSwitch.isChecked();
            log("OverDrive switch isChecked(after re-scan)=" + checkedAfter);
            if (!checkedAfter) {
                return Result.SUCCESS;
            }
            // Positively still ON after a good re-read -> the click genuinely didn't take.
            return Result.FLIP_UNCONFIRMED;
        }
        // A dispatched click is not evidence of a flip. Claiming success here would show a
        // permanent green check while autostart may still be blocked, so require confirmation.
        log("flip not positively confirmable (refresh+re-scan failed); reporting FLIP_UNCONFIRMED"
                + " (clickDispatched=" + clicked + ")");
        return Result.FLIP_UNCONFIRMED;
    }

    // ---------------------------------------------------------------------
    // Window / node helpers
    // ---------------------------------------------------------------------

    private AccessibilityNodeInfo waitForDialogRoot() {
        long deadline = SystemClock.elapsedRealtime() + DIALOG_WAIT_MS;
        int iteration = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            iteration++;
            AccessibilityNodeInfo root = findDialogRoot();
            if (root != null) {
                log("dialog root found on poll iteration " + iteration);
                return root;
            }
            sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * Iterate getWindows() and return the root whose package is the BYD dialog.
     * Logs the FULL window enumeration each call — this is the V1 make-or-break
     * evidence that getWindows() reaches the dialog window.
     */
    private AccessibilityNodeInfo findDialogRoot() {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null || windows.isEmpty()) {
            log("getWindows() empty/null");
            return null;
        }
        AccessibilityNodeInfo match = null;
        StringBuilder dump = new StringBuilder("getWindows() enumeration (")
                .append(windows.size()).append(" windows):");
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo w = windows.get(i);
            AccessibilityNodeInfo root = (w != null) ? w.getRoot() : null;
            CharSequence pkg = (root != null) ? root.getPackageName() : null;
            CharSequence title = null;
            int type = -1;
            if (w != null) {
                type = w.getType();
                title = w.getTitle();
            }
            dump.append("\n  [").append(i).append("] type=").append(type)
                    .append(" pkg=").append(pkg)
                    .append(" root!=null=").append(root != null)
                    .append(" title=").append(title);
            if (root != null && BYD_APPSTART_PKG.contentEquals(pkg == null ? "" : pkg)) {
                match = root; // keep scanning so the full dump is logged
            }
        }
        dump.append("\n  -> appstartmanagement window found=").append(match != null);
        log(dump.toString());
        return match;
    }

    /**
     * Find OverDrive's row Switch. Searches for the app-label text node, climbs to
     * its row container and looks for a Switch descendant. If the row isn't
     * realized (RecyclerView virtualization), scrolls forward and retries until
     * found or the list can't advance.
     */
    private AccessibilityNodeInfo findOverDriveSwitch(AccessibilityNodeInfo root) {
        final String label = overDriveLabel();
        AccessibilityNodeInfo visible = locateSwitchForLabel(root, label);
        if (visible != null) return visible;

        AccessibilityNodeInfo current = rewindToStart(root);
        if (current == null) return null;
        for (int scroll = 0; scroll <= MAX_SCROLLS; scroll++) {
            if (scroll > 0) current = findDialogRoot();
            if (current == null) {
                log("dialog root vanished mid-scroll-search");
                return null;
            }
            AccessibilityNodeInfo sw = locateSwitchForLabel(current, label);
            if (sw != null) {
                if (scroll > 0) log("OverDrive switch located after " + scroll + " scroll(s)");
                return sw;
            }
            // Not visible yet — try to scroll the list forward.
            AccessibilityNodeInfo scrollable = findScrollable(current);
            if (scrollable == null) {
                log("no scrollable container found and OverDrive row not visible");
                return null;
            }
            boolean advanced = scrollable.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            log("scroll forward (" + (scroll + 1) + ") advanced=" + advanced);
            if (!advanced) {
                log("scroll could not advance further — OverDrive row not present");
                return null;
            }
            sleep(400L);
        }
        log("exhausted MAX_SCROLLS (" + MAX_SCROLLS + ") without finding OverDrive row");
        return null;
    }

    /** Reset BYD's retained RecyclerView position before the forward search. */
    private AccessibilityNodeInfo rewindToStart(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo current = root;
        for (int scroll = 0; scroll < MAX_SCROLLS; scroll++) {
            AccessibilityNodeInfo scrollable = findScrollable(current);
            if (scrollable == null) return current;
            boolean moved = scrollable.performAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            log("scroll backward (" + (scroll + 1) + ") moved=" + moved);
            if (!moved) return current;
            sleep(400L);
            current = findDialogRoot();
            if (current == null) {
                log("dialog root vanished while resetting list position");
                return null;
            }
        }
        log("reached MAX_SCROLLS while resetting list position; searching from there");
        return current;
    }

    /** From every "OverDrive" label node, climb ancestors and find a Switch. */
    private AccessibilityNodeInfo locateSwitchForLabel(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> labels = root.findAccessibilityNodeInfosByText(label);
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        log("found " + labels.size() + " node(s) matching label '" + label + "'");
        int inexact = 0;
        for (AccessibilityNodeInfo labelNode : labels) {
            // findAccessibilityNodeInfosByText is a case-insensitive SUBSTRING match over text
            // and contentDescription, so require the node to actually BE the app name before
            // trusting it as our row.
            if (!isExactLabel(labelNode, label)) { inexact++; continue; }
            AccessibilityNodeInfo ancestor = labelNode;
            for (int climb = 0; climb < MAX_ANCESTOR_CLIMB && ancestor != null; climb++) {
                // Stop before the list container: past the row, a descendant search returns
                // the FIRST switch in the whole list, i.e. some other app's autostart toggle.
                if (ancestor.isScrollable()) break;
                AccessibilityNodeInfo sw = findSwitchDescendant(ancestor);
                if (sw != null) {
                    return sw;
                }
                ancestor = ancestor.getParent();
            }
        }
        // Every candidate carried the name as a substring but none matched exactly. Distinguish
        // that in the log from "the row isn't on screen": the caller reads a null as
        // not-scrolled-far-enough, scrolls to the end and gives up, so an exact-match rule that
        // is too strict looks identical to an absent row.
        if (inexact > 0) {
            log("all " + inexact + " candidate label node(s) were inexact matches for '" + label
                    + "' — treating the row as not found; check for padding or a suffix");
        }
        return null;
    }

    /**
     * True when this node's own text or contentDescription IS the app name. Normalized rather
     * than trimmed: trim() only strips up to U+0020, so a label padded with NBSP would fail an
     * exact compare and the row would never be found on that firmware.
     */
    private boolean isExactLabel(AccessibilityNodeInfo node, String label) {
        if (node == null) return false;
        return normalized(node.getText()).equalsIgnoreCase(normalized(label))
                || normalized(node.getContentDescription()).equalsIgnoreCase(normalized(label));
    }

    /** Strip every kind of whitespace, including NBSP and other Unicode spaces. */
    private String normalized(CharSequence cs) {
        if (cs == null) return "";
        StringBuilder b = new StringBuilder();
        String s = cs.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != ' ' && !Character.isSpaceChar(c)) b.append(c);
        }
        return b.toString();
    }

    /** Depth-first search for an android.widget.Switch descendant. */
    private AccessibilityNodeInfo findSwitchDescendant(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("Switch")) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findSwitchDescendant(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** DFS for the first scrollable node. */
    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findScrollable(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Click / gesture
    // ---------------------------------------------------------------------

    /**
     * Try ACTION_CLICK on the switch, then a gesture at the switch's center.
     * Never click a parent row: BYD treats that as "open app details", not toggle.
     */
    private boolean clickSwitch(AccessibilityNodeInfo sw) {
        if (sw.isClickable() && sw.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("node ACTION_CLICK on Switch succeeded");
            return true;
        }
        log("Switch ACTION_CLICK failed/!clickable — trying dispatchGesture tap fallback");
        return tapCenter(sw);
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            log("dispatchGesture fallback aborted — empty bounds");
            return false;
        }
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        log("dispatchGesture tap at (" + cx + "," + cy + ") bounds=" + bounds.toShortString());
        Path path = new Path();
        path.moveTo(cx, cy);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] done = {false};
        boolean dispatched = service.dispatchGesture(gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        done[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription g) {
                        done[0] = false;
                        latch.countDown();
                    }
                }, null);
        if (!dispatched) {
            log("dispatchGesture returned false (not dispatched)");
            return false;
        }
        try {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        log("dispatchGesture completed=" + done[0]);
        return done[0];
    }

    private void closeDialog() {
        try {
            boolean back = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            log("closeDialog: GLOBAL_ACTION_BACK=" + back);
        } catch (Throwable t) {
            log("closeDialog failed: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------

    private String overDriveLabel() {
        try {
            String s = service.getString(R.string.app_name);
            if (s != null && !s.trim().isEmpty()) {
                return s;
            }
        } catch (Throwable ignored) {
        }
        return "OverDrive";
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String msg) {
        // android.util.Log directly: the Kotlin LogManager's getInstance takes a
        // LogConfig whose factory is LogConfig.default(), and `default` is a Java
        // reserved word so it's uncallable from Java. logcat is what we capture for
        // the V1 verification anyway; file-logging via LogManager can be added later
        // through a Kotlin bridge that doesn't expose the `default` name to Java.
        android.util.Log.i(TAG, msg);
    }
}
