package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.overdrive.app.R;
import com.overdrive.app.ui.ClusterTextInputActivity;
import com.overdrive.app.util.DaemonHttpClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional DI5 text relay, separate from the hardware-key accessibility service.
 * Every edit is tied to one live cast generation and one focused cluster node.
 */
public final class ClusterTextInputService extends AccessibilityService {
    private static final String PREFS = "cluster_text_input";
    private static final String AUTO_PROMPT = "auto_prompt";
    private static volatile ClusterTextInputService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ClusterTextInput");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scanPending = new AtomicBoolean(false);
    // Worker-owned. Retaining the node identity lets an edit refuse a replacement field.
    private Target target;

    public interface Callback {
        void onResult(FocusTarget focus, String error);
    }

    public interface EditCallback {
        void onResult(boolean success, String error);
    }

    /** Text stays in app memory and is never sent to the daemon or persisted. */
    public static final class FocusTarget {
        public final long id;
        public final String packageName;
        public final String text;
        public final boolean supportsSubmit;

        private FocusTarget(Target target) {
            id = target.id;
            packageName = target.session.pkg;
            text = target.node.getText() == null ? "" : target.node.getText().toString();
            supportsSubmit = target.node.getActionList().contains(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER);
        }
    }

    private static final class Session {
        final int displayId;
        final String pkg;
        final long generation;

        Session(JSONObject json) {
            displayId = json.optInt("displayId", -1);
            pkg = json.optString("package", "");
            generation = json.optLong("generation", -1);
        }

        boolean same(Session other) {
            return other != null && displayId == other.displayId
                    && generation == other.generation && pkg.equals(other.pkg);
        }
    }

    private static final class Target {
        final long id;
        final ClusterTextTargetToken token;
        final Session session;
        final AccessibilityNodeInfo node;
        final int windowId;

        Target(Object owner, Session session, AccessibilityNodeInfo node) {
            this.token = new ClusterTextTargetToken(owner);
            this.id = token.id;
            this.session = session;
            this.node = node;
            this.windowId = node.getWindowId();
        }
    }

    public static ClusterTextInputService getInstance() {
        return instance;
    }

    public static boolean isAutoPromptEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(AUTO_PROMPT, false);
    }

    public static void setAutoPromptEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(AUTO_PROMPT, enabled).apply();
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (instance != this || worker.isShutdown() || Build.VERSION.SDK_INT < 30
                || event.getEventType() != AccessibilityEvent.TYPE_VIEW_FOCUSED
                || !isAutoPromptEnabled(this)
                || event.getPackageName() == null
                || event.getWindowId() < 0
                || getPackageName().contentEquals(event.getPackageName())
                || !scanPending.compareAndSet(false, true)) return;
        String eventPackage = event.getPackageName().toString();
        int eventWindowId = event.getWindowId();
        if (!schedule(() -> {
            try {
                Session session = readSession();
                if (session == null || !session.pkg.equals(eventPackage)) return;
                AccessibilityNodeInfo focused = focusedNode(session);
                if (focused == null) {
                    clearTarget();
                    return;
                }
                if (focused.getWindowId() != eventWindowId) {
                    focused.recycle();
                    return;
                }
                if (target != null && target.session.same(session)
                        && target.windowId == focused.getWindowId() && target.node.equals(focused)) {
                    focused.recycle();
                    return;
                }
                replaceTarget(session, focused);
                long id = target.id;
                main.post(() -> {
                    if (instance == this && isAutoPromptEnabled(this))
                        ClusterTextInputActivity.openAutomatic(this, id);
                });
            } catch (Exception ignored) {
                clearTarget();
            } finally {
                scanPending.set(false);
            }
        })) scanPending.set(false);
    }

    /** Resolve the current focused field off-thread, including a fresh daemon session check. */
    public void prepare(long expectedTargetId, Callback callback) {
        if (!schedule(() -> {
            FocusTarget result = null;
            String error = null;
            try {
                if (Build.VERSION.SDK_INT < 30) {
                    error = getString(R.string.cluster_text_requires_android11);
                } else {
                    Session session = readSession();
                    if (session == null) {
                        clearTarget();
                        error = getString(R.string.cluster_text_requires_cast);
                    } else {
                        AccessibilityNodeInfo focused = focusedNode(session);
                        if (focused == null) {
                            clearTarget();
                            error = getString(R.string.cluster_text_no_field);
                        } else if (expectedTargetId > 0 && (target == null
                                || target.id != expectedTargetId || !matchesTarget(session, focused))) {
                            focused.recycle();
                            clearTarget();
                            error = getString(R.string.cluster_text_field_changed);
                        } else {
                            if (target == null || !matchesTarget(session, focused))
                                replaceTarget(session, focused);
                            else focused.recycle();
                            result = new FocusTarget(target);
                        }
                    }
                }
            } catch (Exception ignored) {
                clearTarget();
                error = getString(R.string.cluster_text_unavailable);
            }
            FocusTarget finalResult = result;
            String finalError = error;
            main.post(() -> {
                if (instance != this) {
                    callback.onResult(null, getString(R.string.cluster_text_unavailable));
                } else {
                    callback.onResult(finalResult, finalError);
                }
            });
        })) main.post(() -> callback.onResult(null, getString(R.string.cluster_text_unavailable)));
    }

    public void send(long targetId, String text, boolean submit, EditCallback callback) {
        if (!schedule(() -> {
            boolean success = false;
            String error = getString(R.string.cluster_text_field_changed);
            AccessibilityNodeInfo focused = null;
            try {
                if (target != null && target.token.matches(this, targetId)
                        && text != null && text.length() <= 8192) {
                    Session session = readSession();
                    focused = session == null ? null : focusedNode(session);
                    if (instance == this && focused != null && matchesTarget(session, focused) && focused.refresh()
                            && focused.isFocused() && focused.isEnabled()
                            && focused.isEditable() && !focused.isPassword()) {
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        boolean accepted = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                        boolean confirmed = accepted && focused.refresh()
                                && text.contentEquals(focused.getText() == null ? "" : focused.getText())
                                && target.session.same(readSession());
                        if (!confirmed) {
                            error = getString(R.string.cluster_text_rejected);
                        } else if (submit) {
                            focused.recycle();
                            focused = null;
                            // The daemon request can block while the user changes fields.
                            // Resolve focus only after it completes, then refresh and validate
                            // that exact node immediately before dispatching the IME action.
                            Session submitSession = readSession();
                            if (target.session.same(submitSession))
                                focused = focusedNode(submitSession);
                            if (instance == this && focused != null && matchesTarget(submitSession, focused)
                                    && focused.refresh() && focused.isFocused() && focused.isEnabled()
                                    && focused.isEditable() && !focused.isPassword()
                                    && focused.getActionList().contains(
                                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER)) {
                                success = focused.performAction(
                                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                                error = success ? null : getString(R.string.cluster_text_submit_rejected);
                            } else {
                                error = getString(R.string.cluster_text_submit_rejected);
                            }
                        } else {
                            success = true;
                            error = null;
                        }
                    }
                }
            } catch (Exception ignored) {
                error = getString(R.string.cluster_text_unavailable);
            } finally {
                if (focused != null) focused.recycle();
                if (!success) clearTarget();
            }
            boolean finalSuccess = success;
            String finalError = error;
            main.post(() -> callback.onResult(finalSuccess, finalError));
        })) main.post(() -> callback.onResult(false, getString(R.string.cluster_text_unavailable)));
    }

    private boolean matchesTarget(Session session, AccessibilityNodeInfo node) {
        return target != null && target.session.same(session)
                && target.windowId == node.getWindowId() && target.node.equals(node);
    }

    private void replaceTarget(Session session, AccessibilityNodeInfo focused) {
        clearTarget();
        target = new Target(this, session, focused);
    }

    private void clearTarget() {
        if (target != null) {
            target.node.recycle();
            target = null;
        }
    }

    private boolean schedule(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException unavailable) {
            return false;
        }
    }

    private Session readSession() throws Exception {
        if (instance != this) return null;
        HttpURLConnection connection = DaemonHttpClient.open("/api/cluster/apps/session", "GET", 1500, 2000);
        try {
            if (connection.getResponseCode() != 200) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 16_384) return null;
                }
            }
            JSONObject json = new JSONObject(body.toString());
            Session session = new Session(json);
            return instance == this && json.optBoolean("success") && json.optBoolean("di5") && json.optBoolean("casting")
                    && session.displayId > 0 && session.generation > 0 && !session.pkg.isEmpty()
                    && !session.pkg.equals(getPackageName()) ? session : null;
        } finally {
            connection.disconnect();
        }
    }

    private AccessibilityNodeInfo focusedNode(Session session) {
        if (instance != this || Build.VERSION.SDK_INT < 30) return null;
        SparseArray<List<AccessibilityWindowInfo>> displays = getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows = displays.get(session.displayId);
        AccessibilityNodeInfo selected = null;
        try {
            if (windows == null) return null;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getDisplayId() != session.displayId) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused == null) continue;
                    boolean valid = focused.getWindowId() == window.getId()
                            && focused.getPackageName() != null
                            && session.pkg.contentEquals(focused.getPackageName())
                            && focused.isFocused() && focused.isEditable() && focused.isEnabled()
                            && !focused.isPassword()
                            && focused.getActionList().contains(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
                    if (!valid) {
                        focused.recycle();
                    } else if (selected == null) {
                        selected = focused;
                    } else {
                        // Multiple focused windows are ambiguous: never choose arbitrarily.
                        selected.recycle();
                        focused.recycle();
                        selected = null;
                        return null;
                    }
                } finally {
                    root.recycle();
                }
            }
            return selected;
        } catch (RuntimeException error) {
            if (selected != null) selected.recycle();
            throw error;
        } finally {
            for (int i = 0; i < displays.size(); i++) {
                List<AccessibilityWindowInfo> list = displays.valueAt(i);
                if (list != null) for (AccessibilityWindowInfo window : list) window.recycle();
            }
        }
    }

    @Override public void onInterrupt() {
        schedule(this::clearTarget);
    }

    @Override public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        schedule(this::clearTarget);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        schedule(this::clearTarget);
        worker.shutdown();
        super.onDestroy();
    }
}
