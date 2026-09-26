package com.overdrive.app.camera.dilink5;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Explicit opt-in boundary for every DiLink 5-only runtime path. */
public final class DiLink5Platform {
    private static final String MODE_DILINK5 = "dilink5";
    private static final String MODE_DEFAULT = "default";
    private static final String MODE_DILINK4 = "dilink4";
    private static String activeModePath() {
        return ScratchPaths.path("overdrive_active_vehicle_mode");
    }

    private static String pendingModePath() {
        return ScratchPaths.path("overdrive_pending_vehicle_mode");
    }
    private static final long MODE_MARKER_MAX_BYTES = 1_024L;
    private static final String CAMERA_UTILITY_PATH =
            "/system/lib64/libais_test_util.so";
    private static final boolean CAMERA_UTILITY_PRESENT =
            new java.io.File(CAMERA_UTILITY_PATH).isFile();
    private static volatile String processMode =
            effectiveMode(readActiveMode(), MODE_DEFAULT);

    private DiLink5Platform() {}

    public static boolean isSelected() {
        return MODE_DILINK5.equals(processMode);
    }

    /** The mode currently fenced into this process. */
    public static String currentActiveMode() {
        return processMode;
    }

    /** True only on firmware exposing the DiLink 5 QCarCam utility. */
    public static boolean hasDiLink5CameraHardware() {
        return CAMERA_UTILITY_PRESENT;
    }

    /** Normalize a persisted/user-provided mode without silently defaulting it. */
    public static String normalizeConfiguredMode(String mode) {
        return normalizeMode(mode);
    }

    public static boolean isDiLink4Selected() {
        return isDiLink4Mode(processMode);
    }

    static boolean isDiLink4Mode(String mode) {
        return MODE_DILINK4.equals(mode);
    }

    /** Runtime-fenced panel-control gate for DiLink 4 and DiLink 5. */
    public static boolean isPanelControlModeSelected() {
        return isDiLink4Selected() || isSelected();
    }

    static boolean isPanelControlMode(String mode) {
        return MODE_DILINK4.equals(mode) || MODE_DILINK5.equals(mode);
    }

    public static boolean isEnabled() {
        // Camera-only capability gate. Vehicle telemetry and controls must use
        // isSelected(): they do not depend on the QCarCam client library.
        return isSelected() && CAMERA_UTILITY_PRESENT;
    }

    public static boolean isSelected(String cameraMode, String ignoredCameraProfile) {
        // The diagnostics mode selector is the single opt-in switch. A stale or manually chosen
        // camera profile must never move vehicle telemetry/controls onto the DiLink 5 backend.
        return MODE_DILINK5.equalsIgnoreCase(cameraMode);
    }

    /**
     * Freeze the current runtime mode before any vehicle-mode config change.
     * The new selection becomes active only when {@link #activateConfiguredMode()} runs in the
     * replacement camera daemon.
     */
    public static boolean stageConfiguredMode(String requestedMode) {
        try {
            return UnifiedConfigManager.runUnderConfigLock(() ->
                    stageConfiguredMode(requestedMode, readDurableConfiguredMode()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized boolean stageConfiguredMode(
            String requestedMode, String configuredMode) {
        String requested = normalizeMode(requestedMode);
        String pending = readPendingMode();
        String configured = normalizeMode(configuredMode);
        if (requested == null || configured == null) return false;
        String active = effectiveMode(readActiveMode(), pending, configured);
        processMode = active;
        if (!canStageMode(
                active, pending, configured, requested)) {
            return false;
        }
        if (requested.equals(active)
                || (requested.equals(pending) && requested.equals(configured))) {
            return true;
        }
        ModeMarkerSnapshot before = snapshotModeMarkersLocked();
        if (before == null) return false;
        if (writeMode(pendingModePath(), requested)
                && writeMode(activeModePath(), active)) {
            return true;
        }
        restoreModeMarkersLocked(before);
        return false;
    }

    static boolean canStageMode(
            String activeMode,
            String pendingMode,
            String configuredMode,
            String requestedMode) {
        String requested = normalizeMode(requestedMode);
        if (requested == null) return false;
        String active = normalizeMode(activeMode);
        if (requested.equals(active)) return true;
        String pending = normalizeMode(pendingMode);
        String configured = normalizeMode(configuredMode);
        return pending == null
                || !pending.equals(configured)
                || requested.equals(pending);
    }

    public static boolean isSameMode(String first, String second) {
        String normalized = normalizeMode(first);
        return normalized != null && normalized.equals(normalizeMode(second));
    }

    public static boolean isActiveMode(String mode) {
        return isSameMode(processMode, mode);
    }

    /**
     * Commit the configured mode at camera-daemon startup.
     *
     * @return the committed transition, or null when the active marker could not be updated
     */
    public static ModeActivation activateConfiguredMode() {
        try {
            return UnifiedConfigManager.runUnderConfigLock(() -> {
                String configured = readDurableConfiguredMode();
                synchronized (DiLink5Platform.class) {
                    String active = readActiveMode();
                    String pending = readPendingMode();
                    String previous = effectiveMode(active, pending, configured);
                    ModeMarkerSnapshot before = snapshotModeMarkersLocked();
                    if (before == null) {
                        processMode = previous;
                        return null;
                    }
                    if (!deletePendingMode()
                            || !writeMode(activeModePath(), configured)) {
                        processMode = restoreModeMarkersLocked(before)
                                ? previous
                                : effectiveMode(
                                        readActiveMode(),
                                        readPendingMode(),
                                        configured);
                        return null;
                    }
                    processMode = configured;
                    return new ModeActivation(previous, configured);
                }
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static final class ModeActivation {
        public final String previousMode;
        public final String activeMode;

        private ModeActivation(String previousMode, String activeMode) {
            this.previousMode = previousMode;
            this.activeMode = activeMode;
        }
    }

    /** Refresh a long-lived app process after the camera daemon commits a mode transition. */
    public static void refreshActiveMode() {
        try {
            UnifiedConfigManager.runUnderConfigLock(() -> {
                String configured = readDurableConfiguredMode();
                synchronized (DiLink5Platform.class) {
                    processMode = effectiveMode(
                            readActiveMode(),
                            readPendingMode(),
                            configured);
                }
                return null;
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Refresh from the marker already committed by the camera daemon without
     * entering the unified-config lock. Android service control intents use
     * this path on the app main thread so AVM acknowledgements cannot be held
     * behind an unrelated config writer.
     */
    public static void refreshActiveModeFromCommittedMarker() {
        String active = normalizeMode(readActiveMode());
        if (active == null) return;
        synchronized (DiLink5Platform.class) {
            processMode = active;
        }
    }

    /** Cross-process fence for app-process vehicle requests. */
    public static String currentActiveModeGeneration() {
        return readModeGeneration(activeModePath());
    }

    public static boolean matchesActiveModeGeneration(String expected) {
        return matchesModeGeneration(expected, currentActiveModeGeneration());
    }

    static boolean matchesModeGeneration(String expected, String current) {
        return expected != null && current != null && expected.equals(current);
    }

    static String effectiveMode(String activeMode, String configuredMode) {
        String active = normalizeMode(activeMode);
        if (active != null) return active;
        String configured = normalizeMode(configuredMode);
        return configured != null ? configured : MODE_DEFAULT;
    }

    static String effectiveMode(
            String activeMode, String pendingMode, String configuredMode) {
        String configured = effectiveMode(null, configuredMode);
        String pending = normalizeMode(pendingMode);
        return configured.equals(pending)
                ? effectiveMode(activeMode, configured)
                : configured;
    }

    private static String readDurableConfiguredMode() {
        JSONObject root = UnifiedConfigManager.readDurableConfigForRestore();
        JSONObject camera = root.optJSONObject("camera");
        String configured = null;
        if (camera != null
                && camera.has("cameraMode")
                && !camera.isNull("cameraMode")) {
            configured = normalizeMode(camera.optString("cameraMode", ""));
        }
        if (configured != null) return configured;

        // Configs written before cameraMode existed (and recovery/default roots)
        // must not silently opt a running DI5 vehicle back into the legacy path.
        // Preserve the committed marker; a fresh install has no marker and
        // therefore still resolves to the safe default mode.
        return effectiveMode(readActiveMode(), processMode);
    }

    private static String readActiveMode() {
        return readMode(activeModePath());
    }

    private static String readPendingMode() {
        return readMode(pendingModePath());
    }

    private static String readMode(String path) {
        File file = new File(path);
        if (!file.isFile()
                || file.length() <= 0L
                || file.length() > MODE_MARKER_MAX_BYTES) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            return normalizeMode(reader.readLine());
        } catch (Exception ignored) {
            return null;
        }
    }

    static String readModeGeneration(String path) {
        File file = new File(path);
        if (!file.isFile()
                || file.length() <= 0L
                || file.length() > MODE_MARKER_MAX_BYTES) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            if (normalizeMode(reader.readLine()) == null) return null;
            String generation = reader.readLine();
            if (generation == null) return null;
            generation = generation.trim();
            if (generation.isEmpty() || generation.length() > 128) return null;
            java.util.UUID.fromString(generation);
            return generation;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static final class ModeMarkerSnapshot {
        private final byte[] active;
        private final byte[] pending;

        private ModeMarkerSnapshot(byte[] active, byte[] pending) {
            this.active = active != null ? active.clone() : null;
            this.pending = pending != null ? pending.clone() : null;
        }
    }

    public static synchronized ModeMarkerSnapshot snapshotModeMarkers() {
        return snapshotModeMarkersLocked();
    }

    public static synchronized boolean restoreModeMarkers(
            ModeMarkerSnapshot snapshot) {
        return restoreModeMarkersLocked(snapshot);
    }

    private static ModeMarkerSnapshot snapshotModeMarkersLocked() {
        try {
            return new ModeMarkerSnapshot(
                    readMarkerBytes(activeModePath()),
                    readMarkerBytes(pendingModePath()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean restoreModeMarkersLocked(
            ModeMarkerSnapshot snapshot) {
        if (snapshot == null) return false;
        boolean pending = restoreMarkerBytes(pendingModePath(), snapshot.pending);
        boolean active = restoreMarkerBytes(activeModePath(), snapshot.active);
        return pending && active;
    }

    private static byte[] readMarkerBytes(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) return null;
        if (!file.isFile() || file.length() > MODE_MARKER_MAX_BYTES) {
            throw new IllegalStateException("invalid mode marker");
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean writeMode(String path, String mode) {
        String normalized = normalizeMode(mode);
        if (normalized == null) return false;
        byte[] bytes = (normalized + "\n"
                + java.util.UUID.randomUUID() + "\n")
                .getBytes(StandardCharsets.UTF_8);
        return restoreMarkerBytes(path, bytes);
    }

    private static boolean restoreMarkerBytes(String path, byte[] bytes) {
        File target = new File(path);
        File temp = new File(path + ".tmp." + android.os.Process.myPid());
        if (bytes == null) {
            temp.delete();
            return !target.exists() || target.delete();
        }
        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(bytes);
            output.getFD().sync();
        } catch (Exception ignored) {
            temp.delete();
            return false;
        }
        temp.setReadable(true, false);
        if (!temp.renameTo(target)) {
            temp.delete();
            return false;
        }
        target.setReadable(true, false);
        return true;
    }

    private static boolean deletePendingMode() {
        return restoreMarkerBytes(pendingModePath(), null);
    }

    private static String normalizeMode(String mode) {
        if (mode == null) return null;
        String normalized = mode.trim().toLowerCase(java.util.Locale.US);
        if (MODE_DEFAULT.equals(normalized)
                || MODE_DILINK4.equals(normalized)
                || MODE_DILINK5.equals(normalized)) {
            return normalized;
        }
        return null;
    }
}
