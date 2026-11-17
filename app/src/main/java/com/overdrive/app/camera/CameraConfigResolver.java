package com.overdrive.app.camera;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;

/**
 * Resolves persisted + inferred camera settings into a concrete runtime config.
 *
 * Per-role mappings (windshield / cabin / 360-front/right/rear/left) are stored
 * under {@code unified.camera.roleMappings} keyed by role; the resolver merges
 * profile defaults with persisted overrides at runtime. Profile selection
 * ({@code cameraProfile}) tracks vehicle-class geometry,
 * and probe results ({@code probedCameraId}, {@code probedSurfaceMode},
 * {@code probedWidth}, {@code probedHeight}) record the actual stream the GL
 * pipeline locked onto.
 */
public final class CameraConfigResolver {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraConfigResolver");

    private CameraConfigResolver() {
    }

    public static ResolvedCameraConfig resolve() {
        return resolve(readVehicleModel());
    }

    public static ResolvedCameraConfig resolve(String vehicleModel) {
        if (com.overdrive.app.camera.camera2.Camera2Platform.isSelected()) {
            return resolveCamera2();
        }
        JSONObject camera = getCameraSection();
        boolean dilink5 = DiLink5Platform.isSelected();
        boolean dilink4 = !dilink5 && DiLink5Platform.isDiLink4Selected();
        String selectedProfileId = profileForMode(
                camera.optString("cameraProfile", CameraProfiles.PROFILE_AUTO),
                dilink5);
        boolean requestedAutoProfile = selectedProfileId.isEmpty()
                || CameraProfiles.PROFILE_AUTO.equalsIgnoreCase(selectedProfileId);
        boolean autoProfile = requestedAutoProfile && !dilink5;
        if (autoProfile && !dilink4 && DiLink100Platform.isAvmPathActive()) {
            return resolveDiLink100();
        }
        String vehicleModelHint = preferSelectedVehicleModel(
                readSelectedVehicleModel(), vehicleModel);
        CameraProfile profile = dilink5
                ? CameraProfiles.get(CameraProfiles.PROFILE_DILINK5_SEALION7)
                : autoProfile
                ? CameraProfiles.infer(vehicleModelHint)
                : CameraProfiles.get(selectedProfileId);

        int persistedPanoCameraId = dilink5 ? profile.getPanoCameraId()
                : optNonNegative(camera, "probedCameraId", profile.getPanoCameraId());
        int panoCameraId = persistedPanoCameraId;
        int panoSurfaceMode = dilink5 ? profile.getPanoSurfaceMode()
                : optNonNegative(camera, "probedSurfaceMode", profile.getPanoSurfaceMode());
        // Saved dimensions may have been seeded from a profile rather than observed. A partial,
        // zero, odd or implausibly large saved pair is never geometry; valid pairs are kept.
        int savedWidth = camera.optInt("probedWidth", -1);
        int savedHeight = camera.optInt("probedHeight", -1);
        boolean savedGeometryUsable = isUsablePanoramicGeometry(savedWidth, savedHeight);
        if (!dilink5 && !savedGeometryUsable && (savedWidth >= 0 || savedHeight >= 0)) {
            logger.warn("Ignoring invalid saved panoramic geometry " + savedWidth + "x" + savedHeight
                    + "; using profile dimensions");
        }
        int panoWidth = !dilink5 && savedGeometryUsable ? savedWidth : profile.getPanoWidth();
        int panoHeight = !dilink5 && savedGeometryUsable ? savedHeight : profile.getPanoHeight();
        boolean manual = !dilink5 && camera.optBoolean("manualOverride", false);
        boolean validated = dilink5 || camera.optBoolean("probedAndValidated", false);
        boolean fallback = !dilink5 && camera.optBoolean("fallbackFromProbe", false);

        if (dilink4) {
            // DIPlus panoramic recording does not use a vehicle-profile pin
            // or a stale saved probe. It resolves the physical source from
            // BmmCameraInfo every process and keeps that one AVMCamera open.
            // pano_h/pano_l use preview index 0; APA fallback uses index 1.
            AvmCameraHelper.PanoCameraSelection halSelection =
                    AvmCameraHelper.discoverDi4PanoCameraSelection();
            if (halSelection != null) {
                if (panoCameraId != halSelection.getCameraId()
                        || panoSurfaceMode
                            != halSelection.getPreviewIndex()) {
                    logger.warn("DiLink 4 HAL camera mapping overrides stored "
                            + "selection: id=" + panoCameraId
                            + "/preview=" + panoSurfaceMode
                            + " -> " + halSelection.getTag()
                            + " id=" + halSelection.getCameraId()
                            + "/preview="
                            + halSelection.getPreviewIndex());
                }
                panoCameraId = halSelection.getCameraId();
                panoSurfaceMode = halSelection.getPreviewIndex();
            }
            // Effective DI4 selection is HAL-owned, not a manual/profile pin.
            manual = false;
            if (halSelection != null) {
                validated = true;
                fallback = false;
            }
        }

        EnumMap<CameraRole, CameraSourceRef> roleMappings = profile.getDefaultRoleMappings();
        JSONObject mappingsJson = camera.optJSONObject("roleMappings");
        // The native DiLink 5 hook emits one fixed canonical 2x2 order.
        // Do not let stale legacy role overrides desynchronize AI/preview geometry.
        if (!dilink5 && mappingsJson != null) {
            for (CameraRole role : CameraRole.values()) {
                JSONObject item = mappingsJson.optJSONObject(role.getKey());
                CameraSourceRef sourceRef = CameraSourceRef.fromJson(item);
                if (sourceRef != null) {
                    roleMappings.put(role, sourceRef);
                }
            }
        }

        // Field-verified Tang layout: camera 2 is the 360 panoramic strip and
        // camera 0 is the windshield/front camera; both stream concurrently.
        // Older installs can have cameraProfile=auto/legacy but a validated
        // probedCameraId=2 manual override, so expose WINDSHIELD even when the
        // profile defaults don't include it.
        if (!roleMappings.containsKey(CameraRole.WINDSHIELD) && panoCameraId == 2) {
            roleMappings.put(CameraRole.WINDSHIELD, CameraSourceRef.direct(0));
        }

        return new ResolvedCameraConfig(
                profile,
                autoProfile ? CameraProfiles.PROFILE_AUTO : profile.getId(),
                autoProfile,
                panoCameraId,
                panoWidth,
                panoHeight,
                panoSurfaceMode,
                manual,
                validated,
                fallback,
                roleMappings);
    }

    /**
     * Camera2 360: fixed super-frame geometry, slice order from the HAL. Probe
     * results, manual ids and legacy role overrides belong to other modes.
     */
    private static ResolvedCameraConfig resolveCamera2() {
        CameraProfile profile = CameraProfiles.camera2Avm(
                com.overdrive.app.camera.camera2.Camera2AvmBackend.cachedSliceRoles());
        return new ResolvedCameraConfig(
                profile,
                profile.getId(),
                false,
                profile.getPanoCameraId(),
                profile.getPanoWidth(),
                profile.getPanoHeight(),
                profile.getPanoSurfaceMode(),
                false,
                true,
                false,
                profile.getDefaultRoleMappings());
    }

    /**
     * BYD100F DiLink 100, Default mode with the Auto profile: the head unit fixes the AVMCamera
     * geometry. Probe results, manual ids and role overrides from other modes do not apply.
     */
    private static ResolvedCameraConfig resolveDiLink100() {
        CameraProfile profile = CameraProfiles.dilink100Avm();
        return new ResolvedCameraConfig(
                profile,
                CameraProfiles.PROFILE_AUTO,
                true,
                profile.getPanoCameraId(),
                profile.getPanoWidth(),
                profile.getPanoHeight(),
                profile.getPanoSurfaceMode(),
                false,
                true,
                false,
                profile.getDefaultRoleMappings());
    }

    /** Validate a manual camera ID without overriding DI4's HAL tag mapping. */
    public static boolean isManualPanoCameraIdAllowed(int cameraId) {
        if (cameraId < 0 || cameraId > PanoCameraFallbackOrder.MAX_CAMERA_ID) {
            return false;
        }
        boolean dilink4 = !DiLink5Platform.isSelected()
                && DiLink5Platform.isDiLink4Selected();
        if (!dilink4) {
            return true;
        }
        AvmCameraHelper.PanoCameraSelection halSelection =
                AvmCameraHelper.discoverDi4PanoCameraSelection();
        return halSelection == null
                || cameraId == halSelection.getCameraId();
    }

    /**
     * Returns the camera section, or an empty JSONObject if absent.
     */
    public static JSONObject getCameraSection() {
        JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject("camera");
        return section != null ? section : new JSONObject();
    }

    private static boolean isDiLink5RuntimeSelected() {
        DiLink5Platform.refreshActiveMode();
        return DiLink5Platform.isSelected();
    }

    /**
     * Camera2 fixes the source layout in the platform, so legacy camera writes are refused.
     * Deliberately the free process-mode read: re-reading the marker here would add a second
     * config-lock acquisition to every legacy save. A stale allow is harmless because the
     * Camera2 resolver reads none of these keys.
     */
    private static boolean isCamera2RuntimeSelected() {
        return com.overdrive.app.camera.camera2.Camera2Platform.isSelected();
    }

    /** True only for the opt-in DiLink 4 passive APA compatibility path. */
    public static boolean isPassiveApaModeEnabled() {
        if (!DiLink5Platform.isDiLink4Selected()) return false;
        JSONObject camera = getCameraSection();
        return Di4AvcViewpointPolicy.isPassiveApaModeEnabled(
                "dilink4",
                camera.optBoolean("dilink4PassiveApaMode", false));
    }

    /**
     * Persist a single role → source mapping. Accepts both {@code DIRECT} and
     * {@code PANORAMIC_SLICE} kinds so the diagnostics dialog can map any
     * discoverable preview candidate to a logical role. Multi-claim safety
     * for direct-camera live previews is enforced at preview time
     * ({@link CameraPreviewHelper}), not at config write time — the config
     * is just durable user intent.
     */
    public static boolean saveRoleMapping(CameraRole role, CameraSourceRef sourceRef) {
        if (isCamera2RuntimeSelected()) return false;
        if (role == null || sourceRef == null || isDiLink5RuntimeSelected()) return false;
        // Build a fresh JSONObject from the cached section's serialized form.
        // UnifiedConfigManager.loadConfig returns the cached config by
        // reference, so mutating the inner roleMappings JSONObject directly
        // would corrupt the in-memory cache if updateSection's saveConfig
        // fails (the cache then diverges from disk, and readers see a
        // phantom mapping that disappears on next file-mtime reload).
        JSONObject camera = getCameraSection();
        JSONObject existing = camera.optJSONObject("roleMappings");
        JSONObject mappings = (existing != null)
            ? cloneShallow(existing)
            : new JSONObject();
        putSafely(mappings, role.getKey(), sourceRef.toJson());

        JSONObject update = new JSONObject();
        putSafely(update, "roleMappings", mappings);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /**
     * Remove a single role mapping so it falls back to the profile default.
     */
    public static boolean clearRoleMapping(CameraRole role) {
        if (isCamera2RuntimeSelected()) return false;
        if (role == null || isDiLink5RuntimeSelected()) return false;
        JSONObject camera = getCameraSection();
        JSONObject existing = camera.optJSONObject("roleMappings");
        if (existing == null || !existing.has(role.getKey())) return true;
        // Same defensive clone as saveRoleMapping — never mutate the cache
        // before a successful disk write.
        JSONObject mappings = cloneShallow(existing);
        mappings.remove(role.getKey());

        JSONObject update = new JSONObject();
        putSafely(update, "roleMappings", mappings);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /** Persist legacy OEM-camera controls without exposing them to DiLink 5. */
    public static boolean saveLegacyOemCameraSettings(
            boolean manualOverride,
            int cameraId,
            boolean concurrentProbeEnabled) {
        if (isCamera2RuntimeSelected()) return false;
        if (isDiLink5RuntimeSelected()
                || (manualOverride && (cameraId < 0 || cameraId > 5))) {
            return false;
        }
        JSONObject update = new JSONObject();
        putSafely(update, "oemDashcamManualOverride", manualOverride);
        putSafely(update, "oemDashcamCameraId",
                manualOverride ? cameraId : -1);
        putSafely(update, "concurrentAvmProbeEnabled",
                concurrentProbeEnabled);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /**
     * Persist the decoupled-encoder-lane opt-in
     * (camera.decoupledEncoderLane). Write-through only — the flag is read
     * once at pipeline construction (PanoramicCameraGpu USE_* selector
     * pattern), so a change goes live on the next camera restart, never
     * hot-swapped. The pipeline additionally hard-gates the flag off on the
     * DiLink 4/5 paths, so persisting it there is inert by construction.
     */
    public static boolean saveDecoupledEncoderLane(boolean enabled) {
        JSONObject update = new JSONObject();
        putSafely(update, "decoupledEncoderLane", enabled);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /**
     * Persist the recording engine (camera.recordingEngine = "cpu" / "gpu").
     * Read when the pipeline starts, so callers restart the camera service.
     */
    public static boolean saveRecordingEngine(boolean cpu) {
        return saveRecordingEngineMode(cpu ? com.overdrive.app.surveillance.RecordingEngine.CPU
            : com.overdrive.app.surveillance.RecordingEngine.GPU);
    }

    /** "cpu", "gpu", or anything else for the platform default. */
    public static boolean saveRecordingEngineMode(String mode) {
        JSONObject update = new JSONObject();
        putSafely(update, com.overdrive.app.surveillance.RecordingEngine.CONFIG_KEY,
            com.overdrive.app.surveillance.RecordingEngine.normalize(mode));
        // A deliberate choice retries the CPU even after a stored failure.
        putSafely(update, com.overdrive.app.surveillance.RecordingEngine.BLOCK_KEY, "");
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /** Remember a CPU-engine failure for this app build (see RecordingEngine.BLOCK_KEY). */
    public static boolean saveRecordingEngineBlock(String why) {
        JSONObject update = new JSONObject();
        putSafely(update, com.overdrive.app.surveillance.RecordingEngine.BLOCK_KEY,
            com.overdrive.app.surveillance.RecordingEngine.blockEntry(why));
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /** Shallow copy of a JSONObject — sufficient because role mappings are
     *  flat (string key → small JSONObject value). The values are themselves
     *  JSONObjects but we only ever overwrite or remove whole entries, never
     *  edit nested fields, so a shallow copy is safe. */
    private static JSONObject cloneShallow(JSONObject src) {
        JSONObject out = new JSONObject();
        java.util.Iterator<String> keys = src.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            putSafely(out, k, src.opt(k));
        }
        return out;
    }

    /**
     * Persist the user-selected camera profile (vehicle class). Empty / "auto"
     * resets to inferred-from-{@code ro.product.model}; any other id must
     * exist in {@link CameraProfiles}. Profile defaults are resolved at runtime,
     * never written as though they were observed probe dimensions.
     */
    public static boolean saveCameraProfile(String profileId) {
        if (isCamera2RuntimeSelected()) return false;
        if (isDiLink5RuntimeSelected()) return false;
        JSONObject update = new JSONObject();
        if (profileId == null || profileId.isEmpty()
                || CameraProfiles.PROFILE_AUTO.equalsIgnoreCase(profileId)) {
            putSafely(update, "cameraProfile", CameraProfiles.PROFILE_AUTO);
        } else if (CameraProfiles.isKnownProfile(profileId)) {
            if (CameraProfiles.PROFILE_DILINK5_SEALION7
                    .equalsIgnoreCase(profileId)) {
                logger.warn("The DiLink 5 camera profile is selected by camera mode");
                return false;
            }
            putSafely(update, "cameraProfile", profileId);
        } else {
            logger.warn("Ignoring unknown camera profile: " + profileId);
            return false;
        }
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /** Bounds only reject corrupt saved dimensions; they do not certify a HAL stream. */
    static boolean isUsablePanoramicGeometry(int width, int height) {
        return width >= 2 && height >= 2
                && width <= 8192 && height <= 8192
                && (width & 1) == 0 && (height & 1) == 0;
    }

    static String profileForMode(String profileId, boolean dilink5) {
        if (!dilink5 && CameraProfiles.PROFILE_DILINK5_SEALION7
                .equalsIgnoreCase(profileId)) {
            return CameraProfiles.PROFILE_AUTO;
        }
        return profileId == null ? CameraProfiles.PROFILE_AUTO : profileId;
    }

    /**
     * Persist the outcome of a panoramic camera probe.
     *
     * <p><b>This method no longer writes {@code probedWidth}/{@code probedHeight}
     * at all.</b> Two reasons, and the second is why the obvious "write the
     * observed dims instead" fix was rejected:
     *
     * <p>1. Every caller used to pass the CONFIGURED {@code width, height}
     * straight through, which made these keys a laundered copy of the profile
     * default — {@link #resolve} reads them back in preference to the profile, so
     * the values re-confirmed themselves forever and a car whose HAL emits
     * something else could never be detected. Writing a value that is guaranteed
     * to equal the default is pure noise.
     *
     * <p>2. Writing the OBSERVED dims instead would silently change geometry for
     * the LEGACY fleet. On a non-DiLink4 car the frame-50 validation runs on first
     * boot, and a unit whose HAL overrides the ImageReader geometry (exactly the
     * case the "HAL emitted WxH but pipeline configured WxH" warning exists for,
     * e.g. a Tang trim emitting 5120x720 into a 960-tall reader) would persist
     * 720 and come up NEXT boot with encoderHeight 1440 instead of 1920, a
     * differently-sized ImageReader, and every mosaic/foveated offset shifted.
     * Today such a car records at the configured geometry forever. Changing that
     * on the strength of a first-boot observation is not a safe trade for a
     * diagnostic gain.
     *
     * <p>Omitting the keys is exactly equivalent to writing the profile default:
     * {@code optNonNegative(camera, "probedWidth", profile.getPanoWidth())}
     * returns the profile value when the key is absent, and both registered
     * profiles have positive dims. So the resolved config is byte-identical to
     * before while the self-confirming write is gone. The genuinely observed
     * producer size is still available for diagnostics via
     * {@code PanoramicCameraGpu.getObservedProducerWidth/Height()} and is
     * surfaced in the logs; it just no longer feeds pipeline geometry.
     *
     * <p>The {@code width}/{@code height} parameters are retained for call-site
     * compatibility and logging only.
     */
    public static boolean persistPanoramicProbe(int cameraId, int surfaceMode, int width, int height,
                                                boolean validated, boolean fallback) {
        if (isCamera2RuntimeSelected()) return false;
        if (isDiLink5RuntimeSelected()) return false;
        JSONObject update = new JSONObject();
        putSafely(update, "probedCameraId", cameraId);
        putSafely(update, "probedSurfaceMode", surfaceMode);
        // Deliberately NOT writing probedWidth/probedHeight — see javadoc.
        if (width > 0 && height > 0) {
            logger.info("persistPanoramicProbe: observed producer " + width + "x" + height
                + " (diagnostic only — pipeline geometry stays profile-driven)");
        }
        putSafely(update, "probedAndValidated", validated);
        putSafely(update, "fallbackFromProbe", fallback);
        return UnifiedConfigManager.updateSection("camera", update);
    }

    /** Role catalog for the diagnostics camera-mapping dialog. */
    public static JSONArray roleOptionsJson() {
        JSONArray out = new JSONArray();
        for (CameraRole role : CameraRole.values()) {
            out.put(role.toJson());
        }
        return out;
    }

    /**
     * Build the candidate list shown in the dialog's Prev/Next navigator:
     * legacy modes expose direct cameras 0–5 plus panoramic slices; DiLink 5
     * exposes only the fixed QCarCam mosaic slices.
     */
    public static JSONArray buildPreviewCandidates(ResolvedCameraConfig resolved) {
        if (com.overdrive.app.camera.camera2.Camera2Platform.isSelected()) {
            return camera2PreviewCandidates(resolved);
        }
        JSONArray out = new JSONArray();
        boolean dilink5 = DiLink5Platform.isSelected();
        if (dilink5
                && !com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            return out;
        }
        boolean passiveApa = isPassiveApaModeEnabled();
        if (!dilink5) {
            for (int cameraId = 0; cameraId <= 5; cameraId++) {
                JSONObject item = CameraSourceRef.direct(cameraId).toJson();
                boolean passivePano = passiveApa && cameraId == resolved.getPanoCameraId();
                putSafely(item, "previewWidth", passivePano
                        ? PassiveApaGeometry.WIDTH
                        : resolved.getProfile().getDirectPreviewWidth());
                putSafely(item, "previewHeight", passivePano
                        ? PassiveApaGeometry.HEIGHT
                        : resolved.getProfile().getDirectPreviewHeight());
                out.put(item);
            }
        }
        for (PanoramicSlice slice : PanoramicSlice.values()) {
            JSONObject item = CameraSourceRef.panoramicSlice(slice).toJson();
            putSafely(item, "previewWidth", dilink5
                    ? resolved.getPanoWidth() / 2
                    : passiveApa
                            ? PassiveApaGeometry.WIDTH : resolved.getPanoWidth() / 4);
            putSafely(item, "previewHeight", dilink5
                    ? resolved.getPanoHeight() / 2
                    : passiveApa
                            ? PassiveApaGeometry.HEIGHT : resolved.getPanoHeight());
            out.put(item);
        }
        return out;
    }

    /** Camera2 exposes only the four super-frame slices; it never opens another camera. */
    private static JSONArray camera2PreviewCandidates(ResolvedCameraConfig resolved) {
        JSONArray out = new JSONArray();
        for (PanoramicSlice slice : PanoramicSlice.values()) {
            JSONObject item = CameraSourceRef.panoramicSlice(slice).toJson();
            putSafely(item, "previewWidth", resolved.getPanoWidth() / 4);
            putSafely(item, "previewHeight", resolved.getPanoHeight());
            out.put(item);
        }
        return out;
    }

    /**
     * Resolved-config summary merged into {@code GET /api/surveillance/config}
     * so the diagnostics dialog can render in a single round-trip.
     */
    public static JSONObject resolvedSummaryJson(ResolvedCameraConfig resolved) {
        JSONObject out = new JSONObject();
        putSafely(out, "cameraProfile", resolved.getSelectedProfileId());
        putSafely(out, "resolvedCameraProfile", resolved.getProfile().getId());
        putSafely(out, "resolvedCameraProfileLabel", resolved.getProfile().getDisplayName());
        putSafely(out, "panoCameraId", resolved.getPanoCameraId());
        putSafely(out, "panoSurfaceMode", resolved.getPanoSurfaceMode());
        putSafely(out, "panoWidth", resolved.getPanoWidth());
        putSafely(out, "panoHeight", resolved.getPanoHeight());
        boolean passiveApa = isPassiveApaModeEnabled();
        putSafely(out, "encoderWidth", passiveApa
                ? PassiveApaGeometry.WIDTH : resolved.getProfile().getEncoderWidth());
        putSafely(out, "encoderHeight", passiveApa
                ? PassiveApaGeometry.HEIGHT : resolved.getProfile().getEncoderHeight());
        putSafely(out, "cameraManualOverride", resolved.isManualPanoOverride());
        putSafely(out, "cameraValidated", resolved.isValidated());
        putSafely(out, "cameraFallbackFromProbe", resolved.isFallbackFromProbe());
        putSafely(out, "cameraProfiles", CameraProfiles.toJsonArray());
        putSafely(out, "cameraRoleOptions", roleOptionsJson());
        putSafely(out, "cameraRoleMappings", resolved.roleMappingsToJson());
        putSafely(out, "cameraPanoramicSlices", resolved.panoramicSlicesToJson());
        putSafely(out, "cameraPreviewCandidates", buildPreviewCandidates(resolved));
        return out;
    }

    private static int optNonNegative(JSONObject obj, String key, int defaultValue) {
        int value = obj.optInt(key, defaultValue);
        return value >= 0 ? value : defaultValue;
    }

    private static void putSafely(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to write JSON field '" + key + "'", e);
        }
    }

    /**
     * Prefer the model explicitly selected in OverDrive over the often-generic
     * Android product string ("BYD AUTO"). A non-auto camera profile and any
     * persisted probe/manual camera ID still take precedence later in resolve().
     */
    static String preferSelectedVehicleModel(String selectedModelId, String systemModel) {
        if (selectedModelId != null && !selectedModelId.trim().isEmpty()) {
            return selectedModelId.trim();
        }
        if (systemModel != null && !systemModel.trim().isEmpty()) {
            return systemModel.trim();
        }
        return "unknown";
    }

    private static String readSelectedVehicleModel() {
        try {
            String selected = UnifiedConfigManager.getSelectedVehicleModelId();
            return selected != null ? selected : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String readVehicleModel() {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.product.model", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
