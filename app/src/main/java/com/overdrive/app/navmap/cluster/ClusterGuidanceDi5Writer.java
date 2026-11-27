package com.overdrive.app.navmap.cluster;

import static com.overdrive.app.navmap.cluster.ClusterCommContract.abandonMapFocus;
import static com.overdrive.app.navmap.cluster.ClusterCommContract.displayMode;
import static com.overdrive.app.navmap.cluster.ClusterCommContract.displayModeDirect;
import static com.overdrive.app.navmap.cluster.ClusterCommContract.mapFocusDirect;
import static com.overdrive.app.navmap.cluster.ClusterCommContract.message;
import static com.overdrive.app.navmap.cluster.ClusterCommContract.naviState;
import static com.overdrive.app.navmap.cluster.ClusterCommContract.presentation;
import static com.overdrive.app.navmap.cluster.ClusterCommContract.requestMapFocus;

import android.os.Bundle;
import android.os.SystemClock;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.navmap.cluster.ClusterCommDispatcher.Reply;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * DI5 guidance through the OEM cluster communication service in its native "simple" TBT mode.
 * Runs only on the daemon's guidance worker. A durable marker records what is still owed (the
 * original display-mode settings, a pending deactivation) before anything is changed, so a
 * restart or a failed clear never loses it; settings are restored compare-and-swap.
 */
final class ClusterGuidanceDi5Writer {
    static final String MODE_SETTING = ClusterCommContract.INSTRUMENT_MODE_SETTING;
    static final String FOCUS_SETTING = "moet_extension_navi_focus_map";
    static final String SIMPLE_MODE_VALUE = "2", MAP_FOCUS_VALUE = "1";
    private static final String MARKER = "clusterGuidanceDi5Restore";
    private static final int MODE_SIMPLE = 2, MODE_STANDARD = 1;
    private static final int NAVI_START = 0, NAVI_STOP = 1;
    private static final int STATE_CLOSE = 1, STATE_SIMPLE_OPEN = 7;
    static final int UNIT_KM = 1, UNIT_M = 3;
    private static final long FIRST_REQUEST_MS = 4_000L, REQUEST_MS = 2_000L;
    static final long TRANSIENT_RETRY_MS = 10_000L, UNAVAILABLE_RETRY_MS = 60_000L;
    static final int CLEAR_ATTEMPTS = 8;
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,9}");
    static final JSONObject UNREADABLE = new JSONObject();

    /** Platform side effects, separable so the state machine can be exercised offline. */
    interface Io {
        Reply dispatch(List<Bundle> commands, long budgetMs);
        /** Shell settings tool: trimmed stdout, or null on failure. */
        String settings(String verb, String key, String value);
        /** Null when absent, {@link #UNREADABLE} when the store cannot be read. */
        JSONObject readMarker();
        boolean writeMarker(JSONObject markerOrNull);
        boolean accOn();
        boolean projectionOwnsCluster();
        void log(String message);
    }

    private final Io io;
    private volatile boolean active, deactivationOwed, settingsDirty;
    private volatile String last = "idle";
    private boolean handshakeOwed, markerKnown;
    /** Values from before this writer's first change; mirrored in the marker while dirty. */
    private JSONObject originals;
    private String markerOnDisk = "";
    private String token = "";
    private long retryAtMs, commandRetryAtMs, settingsRetryAtMs, markerRetryAtMs;
    private long lastHiccupMs = Long.MIN_VALUE / 2;
    private int commandFailures, settingsFailures, markerFailures, activationFailures;

    ClusterGuidanceDi5Writer() { this(new DaemonIo()); }

    ClusterGuidanceDi5Writer(Io io) { this.io = io; }

    ClusterGuidanceVendorWriter.Result write(ClusterGuidanceFrame frame, BooleanSupplier current) {
        ClusterGuidanceVendorWriter.Result result = new ClusterGuidanceVendorWriter.Result();
        if (!allowed(current, result)) return result;
        if (!active) return activate(frame, current, result);
        List<Bundle> batch = new ArrayList<>();
        if (handshakeOwed) batch.addAll(handshake());
        batch.addAll(tbt(frame));
        Reply reply = io.dispatch(batch, REQUEST_MS);
        note(result, "frame", reply);
        if (!reply.ok()) {
            active = false;
            // One hiccup a minute re-establishes at once; a link that keeps failing backs off.
            long failedAt = SystemClock.elapsedRealtime();
            boolean hiccup = activationFailures == 0 && failedAt - lastHiccupMs > 60_000L;
            if (hiccup) lastHiccupMs = failedAt;
            retryAtMs = hiccup ? 0 : failedAt + activationBackoff(Math.max(1, activationFailures),
                    reply.definitive());
            activationFailures++;
            return result;
        }
        handshakeOwed = false;
        if (token.equals(reply.token)) activationFailures = 0;
        // A new binder means the service restarted or our app process did; re-establish the mode.
        if (!token.equals(reply.token)) {
            active = false;
            return activate(frame, current, result);
        }
        return result;
    }

    ClusterGuidanceVendorWriter.Result clear() {
        ClusterGuidanceVendorWriter.Result result = new ClusterGuidanceVendorWriter.Result();
        // Whatever ends here, the next frame must establish the mode again.
        active = false;
        handshakeOwed = false;
        if (io.projectionOwnsCluster()) {
            result.stripClearDeferred = true;
            result.cluster = "suspended_for_active_projection";
            return result;
        }
        long now = SystemClock.elapsedRealtime();
        boolean accOn = io.accOn();
        if (!markerKnown && now >= markerRetryAtMs && !knowMarker(result))
            markerRetryAtMs = SystemClock.elapsedRealtime() + backoff(++markerFailures);
        boolean closed = false;
        if (deactivationOwed && commandFailures < CLEAR_ATTEMPTS && now >= commandRetryAtMs) {
            if (!accOn) {
                result.registers.put("deactivate", "deferred_until_acc_on");
            } else {
                Reply close = io.dispatch(Arrays.asList(presentation(STATE_CLOSE), naviState(NAVI_STOP),
                        abandonMapFocus(), mapFocusDirect(0), displayMode(MODE_STANDARD),
                        displayModeDirect(MODE_STANDARD)), FIRST_REQUEST_MS);
                note(result, "deactivate", close);
                closed = close.ok();
                if (!closed) commandRetryAtMs = SystemClock.elapsedRealtime() + backoff(++commandFailures);
            }
        }
        if (settingsDirty && settingsFailures < CLEAR_ATTEMPTS && now >= settingsRetryAtMs) {
            if (restoreSettings(result)) settingsDirty = false;
            else settingsRetryAtMs = SystemClock.elapsedRealtime() + backoff(++settingsFailures);
        }
        if (closed) {
            note(result, "stop", io.dispatch(Collections.singletonList(naviState(NAVI_STOP)), REQUEST_MS));
            deactivationOwed = false;
            commandFailures = 0;
            // The link just worked: the next session must not wait out an old backoff.
            activationFailures = 0;
            retryAtMs = 0;
        }
        if (!settingsDirty) {
            originals = null;
            settingsFailures = 0;
        }
        if (markerKnown && markerFailures < CLEAR_ATTEMPTS && now >= markerRetryAtMs
                && !recordMarker(result)) {
            markerRetryAtMs = SystemClock.elapsedRealtime() + backoff(++markerFailures);
        }
        boolean owed = deactivationOwed || settingsDirty || !markerInSync();
        boolean pending = deactivationOwed && commandFailures < CLEAR_ATTEMPTS
                || settingsDirty && settingsFailures < CLEAR_ATTEMPTS
                || (!markerKnown || !markerInSync()) && owed && markerFailures < CLEAR_ATTEMPTS;
        result.stripClearDeferred = pending;
        boolean noFailure = "not_attempted".equals(result.cluster)
                || "accepted_rendering_unverified".equals(result.cluster);
        if (!owed) {
            result.cluster = "cleared";
        } else if (!pending) {
            // Retried at the next daemon start through the marker.
            io.log("DI5 cluster guidance clear gave up for now");
            result.cluster = "clear_abandoned";
        } else if (noFailure) {
            result.cluster = "clear_pending";
        }
        last = "clear:" + result.cluster;
        return result;
    }

    /** On every ACC-off edge: the next drive starts without this drive's activation backoff. */
    void newDrive() {
        activationFailures = 0;
        retryAtMs = 0;
        lastHiccupMs = Long.MIN_VALUE / 2;
    }

    /** At daemon start: 1 adopted owed state, 0 nothing owed, -1 marker unreadable (retry). */
    int adoptPendingRestore() {
        if (markerKnown) return 0;
        if (!knowMarker(new ClusterGuidanceVendorWriter.Result())) return -1;
        if (!deactivationOwed && !settingsDirty) return 0;
        io.log("DI5 cluster guidance: restoring state left by a previous daemon");
        return 1;
    }

    JSONObject status() throws JSONException {
        return new JSONObject().put("active", active).put("deactivationOwed", deactivationOwed)
                .put("settingsChanged", settingsDirty).put("last", last);
    }

    private ClusterGuidanceVendorWriter.Result activate(ClusterGuidanceFrame frame,
            BooleanSupplier current, ClusterGuidanceVendorWriter.Result result) {
        long now = SystemClock.elapsedRealtime();
        if (now < retryAtMs) {
            result.cluster = "retry_pending";
            return result;
        }
        boolean known = knowMarker(result);
        boolean owedBefore = deactivationOwed, dirtyBefore = settingsDirty;
        // Capture only values this writer has not changed; recorded originals are reused.
        if (known && !settingsDirty) originals = captureSettings(result);
        // Durable first: the marker holds what this activation may change before it changes.
        deactivationOwed = true;
        settingsDirty = known && originals != null;
        boolean durable = recordMarker(result);
        if (!clusterFree(result)) {
            // Nothing was sent; the pre-written marker is a harmless superset clear() reconciles.
            deactivationOwed = owedBefore;
            settingsDirty = dirtyBefore;
            return result;
        }
        Reply mode = io.dispatch(Arrays.asList(displayMode(MODE_SIMPLE), displayModeDirect(MODE_SIMPLE)),
                FIRST_REQUEST_MS);
        note(result, "activate", mode);
        if (!mode.ok()) {
            deactivationOwed = owedBefore || mode.maybeDelivered();
            if (mode.maybeDelivered()) {
                // A possibly-new owed close gets a fresh budget, even after an earlier give-up.
                commandFailures = 0;
                commandRetryAtMs = 0;
            }
            settingsDirty = dirtyBefore;
            // A transient failure keeps the marker's superset of what is owed (the close is
            // idempotent, the restore compare-and-swap); rewriting it on every retry is churn.
            if (mode.definitive()) recordMarker(result);
            retryAtMs = SystemClock.elapsedRealtime() + activationBackoff(++activationFailures, mode.definitive());
            return result;
        }
        commandFailures = settingsFailures = markerFailures = 0;
        commandRetryAtMs = settingsRetryAtMs = markerRetryAtMs = 0;
        if (!durable) durable = recordMarker(result);
        // Settings change only once their originals are on disk.
        if (settingsDirty && durable) writeSettings(result);
        else settingsDirty = dirtyBefore;
        // A newer frame or a clear runs right after this activation, in order; aborting here
        // on every newer frame would starve activation behind slow settings forks.
        if (!clusterFree(result)) return result;
        List<Bundle> open = new ArrayList<>(Arrays.asList(requestMapFocus(), mapFocusDirect(1)));
        open.addAll(handshake());
        open.add(naviState(NAVI_START));
        open.addAll(tbt(frame));
        Reply opened = io.dispatch(open, REQUEST_MS);
        note(result, "frame", opened);
        if (!opened.ok()) {
            retryAtMs = SystemClock.elapsedRealtime() + activationBackoff(++activationFailures, opened.definitive());
            return result;
        }
        token = opened.token;
        active = opened.token.equals(mode.token);
        handshakeOwed = false;
        retryAtMs = active ? 0 : SystemClock.elapsedRealtime() + activationBackoff(++activationFailures, false);
        if (!active) return result;
        // The OEM handshake is lossy right after a mode switch; repeat it once, then the turn.
        if (!pause(220L, current, result)) {
            handshakeOwed = true;
            return result;
        }
        List<Bundle> repeat = new ArrayList<>(handshake());
        repeat.addAll(tbt(frame));
        if (!stillActive(io.dispatch(repeat, REQUEST_MS), "handshake", result)) return result;
        if (!pause(480L, current, result)) return result;
        stillActive(io.dispatch(tbt(frame), REQUEST_MS), "repeat", result);
        return result;
    }

    private boolean stillActive(Reply reply, String step, ClusterGuidanceVendorWriter.Result result) {
        note(result, step, reply);
        if (reply.ok() && token.equals(reply.token)) return true;
        active = false;
        retryAtMs = SystemClock.elapsedRealtime()
                + activationBackoff(++activationFailures, reply.definitive());
        return false;
    }

    private boolean allowed(BooleanSupplier current, ClusterGuidanceVendorWriter.Result result) {
        if (!current.getAsBoolean()) {
            result.cluster = "superseded";
            return false;
        }
        return clusterFree(result);
    }

    private boolean clusterFree(ClusterGuidanceVendorWriter.Result result) {
        if (io.projectionOwnsCluster()) {
            // A projection may change the cluster mode; establish it again once the cluster is free.
            active = false;
            result.cluster = "suspended_for_active_projection";
            return false;
        }
        return true;
    }

    private boolean pause(long millis, BooleanSupplier current,
            ClusterGuidanceVendorWriter.Result result) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return allowed(current, result);
    }

    private void note(ClusterGuidanceVendorWriter.Result result, String step, Reply reply) {
        String outcome = reply.ok() ? "accepted_rendering_unverified" : reply.name();
        result.registers.put(step, reply.name());
        result.cluster = outcome;
        String next = step + ":" + outcome;
        if (!next.equals(last) && !reply.ok()) io.log("DI5 cluster " + step + ": " + reply.name());
        last = next;
    }

    private static long backoff(int failures) {
        return Math.min(160_000L, 5_000L << Math.min(5, Math.max(0, failures - 1)));
    }

    static long activationBackoff(int failures, boolean definitive) {
        int shift = Math.min(4, Math.max(0, failures - 1));
        return definitive ? Math.min(600_000L, UNAVAILABLE_RETRY_MS << shift)
                : Math.min(160_000L, TRANSIENT_RETRY_MS << shift);
    }

    // --- Durable marker and OEM display-mode settings ----------------------------------------

    /** Reads the marker once per daemon. A marker that cannot be read is never overwritten. */
    private boolean knowMarker(ClusterGuidanceVendorWriter.Result result) {
        if (markerKnown) return true;
        JSONObject marker = io.readMarker();
        if (marker == UNREADABLE) {
            result.registers.put("marker", "unreadable");
            return false;
        }
        markerKnown = true;
        markerFailures = 0;
        markerRetryAtMs = 0;
        markerOnDisk = marker == null ? "" : marker.toString();
        if (marker == null) return true;
        deactivationOwed |= marker.optBoolean("owed", true);
        String mode = marker.optString("mode", null), focus = marker.optString("focus", null);
        if (!settingsDirty && marker.optBoolean("settings", false) && restorable(mode) && restorable(focus)) {
            try {
                originals = new JSONObject().put("mode", mode).put("focus", focus);
                settingsDirty = true;
            } catch (JSONException impossible) {
                originals = null;
            }
        }
        return true;
    }

    private JSONObject desiredMarker() {
        if (!deactivationOwed && !settingsDirty) return null;
        try {
            JSONObject marker = new JSONObject().put("owed", deactivationOwed)
                    .put("settings", settingsDirty && originals != null);
            if (settingsDirty && originals != null) {
                marker.put("mode", originals.optString("mode", "")).put("focus", originals.optString("focus", ""));
            }
            return marker;
        } catch (JSONException impossible) {
            return null;
        }
    }

    private boolean markerInSync() {
        JSONObject desired = desiredMarker();
        return !markerKnown || (desired == null ? "" : desired.toString()).equals(markerOnDisk);
    }

    /** True when the marker on disk records exactly what is owed now. */
    private boolean recordMarker(ClusterGuidanceVendorWriter.Result result) {
        if (!markerKnown) return false;
        JSONObject desired = desiredMarker();
        String text = desired == null ? "" : desired.toString();
        if (text.equals(markerOnDisk)) return true;
        if (!io.writeMarker(desired)) {
            result.registers.put("marker", "unwritable");
            return false;
        }
        markerOnDisk = text;
        markerFailures = 0;
        markerRetryAtMs = 0;
        return true;
    }

    private JSONObject captureSettings(ClusterGuidanceVendorWriter.Result result) {
        String mode = io.settings("get", MODE_SETTING, null);
        String focus = io.settings("get", FOCUS_SETTING, null);
        if (restorable(mode) && restorable(focus)) {
            try { return new JSONObject().put("mode", mode).put("focus", focus); }
            catch (JSONException impossible) { return null; }
        }
        result.registers.put("settings", "unreadable_skipped");
        return null;
    }

    private void writeSettings(ClusterGuidanceVendorWriter.Result result) {
        boolean mode = io.settings("put", MODE_SETTING, SIMPLE_MODE_VALUE) != null;
        boolean focus = io.settings("put", FOCUS_SETTING, MAP_FOCUS_VALUE) != null;
        result.registers.put("settings", mode && focus ? "applied" : "failed");
    }

    private boolean restoreSettings(ClusterGuidanceVendorWriter.Result result) {
        if (originals == null) return true;
        boolean ok = restore(MODE_SETTING, SIMPLE_MODE_VALUE, originals.optString("mode", ""))
                & restore(FOCUS_SETTING, MAP_FOCUS_VALUE, originals.optString("focus", ""));
        result.registers.put("restore", ok ? "restored" : "failed");
        return ok;
    }

    /** Leaves the value alone unless it still holds what activation wrote. */
    private boolean restore(String key, String written, String original) {
        if (!restorable(original)) return true;
        String current = io.settings("get", key, null);
        if (current == null) return false;
        if (!written.equals(current) || written.equals(original)) return true;
        return "null".equals(original) ? io.settings("delete", key, null) != null
                : io.settings("put", key, original) != null;
    }

    private static boolean restorable(String value) {
        return value != null && ("null".equals(value) || INTEGER.matcher(value).matches());
    }

    private static JSONObject commandsOnlyMarker() {
        try { return new JSONObject().put("owed", true).put("settings", false); }
        catch (JSONException impossible) { return new JSONObject(); }
    }

    // --- OEM message encoding ----------------------------------------------------------------

    private static List<Bundle> handshake() {
        return Arrays.asList(naviState(NAVI_START), presentation(STATE_SIMPLE_OPEN));
    }

    static List<Bundle> tbt(ClusterGuidanceFrame frame) {
        Tbt fields = Tbt.of(frame, System.currentTimeMillis(), TimeZone.getDefault());
        return Arrays.asList(fields.navigation(), fields.strings());
    }

    /** Values for the two OEM turn messages; unknown remaining values stay empty, never invented. */
    static final class Tbt {
        final int icon, nextUnit, destUnit, remainingSeconds, days, arriveHour, arriveMinute;
        final float nextDistance, destDistance;
        final String road, nextText, nextUnitText, destText, destUnitText, remainingText, arriveText;

        private Tbt(ClusterGuidanceFrame frame, long nowMillis, TimeZone zone) {
            icon = frame.clusterIcon();
            road = frame.roadName;
            int next = frame.distanceMeters;
            nextDistance = distance(next);
            nextUnit = next >= 1000 ? UNIT_KM : UNIT_M;
            nextText = distanceText(next);
            nextUnitText = unitText(next);
            int dest = frame.remainingDistanceMeters;
            destDistance = distance(dest);
            destUnit = dest >= 1000 ? UNIT_KM : UNIT_M;
            destText = distanceText(dest);
            destUnitText = unitText(dest);
            int seconds = frame.remainingTimeSeconds;
            if (seconds >= 0) {
                int minutes = Math.max(1, seconds / 60);
                Calendar arrival = Calendar.getInstance(zone, Locale.US);
                arrival.setTimeInMillis(nowMillis + seconds * 1000L);
                remainingSeconds = seconds;
                days = minutes / 1440;
                arriveHour = arrival.get(Calendar.HOUR_OF_DAY);
                arriveMinute = arrival.get(Calendar.MINUTE);
                remainingText = minutes + " min";
                arriveText = String.format(Locale.US, "%02d:%02d", arriveHour, arriveMinute);
            } else {
                remainingSeconds = 0;
                days = 0;
                arriveHour = 0;
                arriveMinute = 0;
                remainingText = "";
                arriveText = "";
            }
        }

        static Tbt of(ClusterGuidanceFrame frame, long nowMillis, TimeZone zone) {
            return new Tbt(frame, nowMillis, zone);
        }

        private static float distance(int meters) {
            return meters < 0 ? 0f : meters >= 1000 ? meters / 1000f : meters;
        }

        private static String distanceText(int meters) {
            if (meters < 0) return "";
            return meters >= 1000 ? String.format(Locale.US, "%.1f", meters / 1000f)
                    : Integer.toString(meters);
        }

        private static String unitText(int meters) {
            return meters < 0 ? "" : meters >= 1000 ? "km" : "m";
        }

        Bundle navigation() {
            Bundle bundle = message(32, 7);
            bundle.putInt("mapType", 1);
            bundle.putInt("nextTbtIcon", icon);
            bundle.putString("nextRoadName", road);
            bundle.putFloat("nextRoadDist", nextDistance);
            bundle.putInt("nextDistUnit", nextUnit);
            bundle.putInt("nextNextTbtIcon", 0);
            bundle.putFloat("nextNextRoadDist", 0f);
            bundle.putInt("nextNextDistUnit", UNIT_M);
            bundle.putFloat("distToDest", destDistance);
            bundle.putInt("distToDestUnit", destUnit);
            bundle.putInt("toDestTime", remainingSeconds);
            bundle.putInt("toDestDays", days);
            // The OEM maps these two onto the arrival clock, not the remaining duration.
            bundle.putInt("toDestHours", arriveHour);
            bundle.putInt("toDestMinutes", arriveMinute);
            return bundle;
        }

        Bundle strings() {
            Bundle bundle = message(37, 20);
            bundle.putInt("mapType", 1);
            bundle.putInt("nextTbtIcon", icon);
            bundle.putString("nextRoadName", road);
            bundle.putString("nextRoadDistData", nextText);
            bundle.putString("nextRoadDistUnit", nextUnitText);
            bundle.putInt("nextNextTbtIcon", 0);
            bundle.putString("nextNextDistData", "");
            bundle.putString("nextNextDistAndUnit", "");
            bundle.putString("distToDestDisData", destText);
            bundle.putString("distToDestDisUnit", destUnitText);
            bundle.putString("distToDestTime", remainingText);
            bundle.putString("arriveToDestTimeDay", "");
            bundle.putString("arriveToDestTime", arriveText);
            bundle.putString("multipleText", "");
            bundle.putString("multipleText1", "");
            bundle.putString("multipleText2", "");
            return bundle;
        }
    }

    // --- App-process transport ---------------------------------------------------------------

    private static final class DaemonIo implements Io {
        @Override public Reply dispatch(List<Bundle> commands, long budgetMs) {
            return ClusterCommDispatcher.dispatch(CameraDaemon.getAppContext(), commands, budgetMs, null);
        }

        @Override public String settings(String verb, String key, String value) {
            List<String> command = new ArrayList<>(Arrays.asList("/system/bin/settings", verb, "global", key));
            if (value != null) command.add(value);
            Process process = null;
            try {
                process = new ProcessBuilder(command)
                        .redirectError(ProcessBuilder.Redirect.to(new File("/dev/null"))).start();
                process.getOutputStream().close();
                if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
                InputStream in = process.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[256];
                int n;
                while (out.size() < 256 && (n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Throwable failure) {
                return null;
            } finally {
                if (process != null) process.destroy();
            }
        }

        @Override public JSONObject readMarker() {
            try {
                JSONObject projection = UnifiedConfigManager.readDurableConfigStrict()
                        .optJSONObject("projection");
                String value = projection == null ? "" : projection.optString(MARKER, "");
                if (value.isEmpty()) return null;
                try { return new JSONObject(value); }
                catch (JSONException corrupt) { return commandsOnlyMarker(); }
            } catch (Throwable unreadable) {
                return UNREADABLE;
            }
        }

        @Override public boolean writeMarker(JSONObject markerOrNull) {
            try {
                return UnifiedConfigManager.updateValues("projection", Collections.singletonMap(
                        MARKER, markerOrNull == null ? "" : markerOrNull.toString()));
            } catch (Throwable failure) {
                return false;
            }
        }

        @Override public boolean accOn() {
            try { return AccMonitor.isAccStateAuthoritative() && AccMonitor.isAccOn(); }
            catch (Throwable unavailable) { return false; }
        }

        /** Unknown ownership counts as owned: guidance must never write over a projection. */
        @Override public boolean projectionOwnsCluster() {
            try { return ClusterGuidanceVendorWriter.projectionOwnsCluster(); }
            catch (Throwable unavailable) { return true; }
        }

        @Override public void log(String message) {
            DaemonLogger.getInstance("ClusterGuidanceDi5").warn(message);
        }
    }
}
