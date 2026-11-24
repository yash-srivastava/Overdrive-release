package com.overdrive.app.setup;

/**
 * What the vehicle setup assistant changes for a picked car. Pure: inputs are the pick,
 * the hardware detection and the current state; nothing here touches config.
 */
public final class VehicleSetupPlan {

    public static final String MODE_DEFAULT = "default";

    /** Manifest model id whose firmware locks the legacy scratch root. */
    public static final String SHARK_MODEL_ID = "shark";

    public enum Storage {
        /** Leave the scratch root alone. */
        NONE,
        /** Probe the legacy root over ADB at apply time; switch only if the shell cannot create there. */
        CHECK,
        /** App on legacy while the alternate marker exists: move the app to where the daemons run. */
        REPAIR
    }

    /** Why {@link #cameraMode} was chosen; drives the review wording only. */
    public enum ModeSource { OVERRIDE, DETECTED, UNSUPPORTED, MODEL, CURRENT, DEFAULT }

    /** Camera mode to apply. */
    public final String cameraMode;
    public final ModeSource modeSource;
    public final Storage storage;
    public final boolean changeModel;
    public final boolean changeCameraMode;

    private VehicleSetupPlan(String cameraMode, ModeSource modeSource, Storage storage,
            boolean changeModel, boolean changeCameraMode) {
        this.cameraMode = cameraMode;
        this.modeSource = modeSource;
        this.storage = storage;
        this.changeModel = changeModel;
        this.changeCameraMode = changeCameraMode;
    }

    /** A DiLink 3/4 head unit by its core version or type, which no Shark is. */
    private static boolean knownLegacyCore(VehicleSetupDetector.Detection detection) {
        return detection.platform == VehicleSetupDetector.Platform.LEGACY
                && detection.confidence == VehicleSetupDetector.Confidence.HIGH;
    }

    /** The platform a model ships with, where every known unit agrees; else null. */
    public static String modelDefaultMode(String modelId) {
        if (modelId == null) return null;
        switch (modelId) {
            case SHARK_MODEL_ID:
            case "sealion7":
                return VehicleSetupDetector.MODE_DILINK5;
            // No Atto 3 EVO entry: its BYD100F head unit refuses Camera2 clients (#741).
            default:
                return null;
        }
    }

    /**
     * @param modelId picked manifest model id; null when the car is not listed (model left as is)
     * @param di5CameraLibrary the DiLink 5 camera library is present (dilink5 cannot run without it)
     * @param currentMode camera mode the next camera-service start would use
     * @param currentModelId resolved configured model, null when unset
     * @param alternateStorageActive the app already runs on the alternate scratch root
     * @param storageSplit the app is on legacy while the alternate marker exists
     * @param serviceRunning the camera service answered, so the current root evidently works
     * @param overrideMode the owner's explicit pick on the review screen, or null
     */
    public static VehicleSetupPlan build(String modelId,
            VehicleSetupDetector.Detection detection, boolean di5CameraLibrary,
            String currentMode, String currentModelId, boolean alternateStorageActive,
            boolean storageSplit, boolean serviceRunning, String overrideMode) {
        boolean sharkEvidence = SHARK_MODEL_ID.equals(modelId) || detection.sharedFirmwareFamily
                || SHARK_MODEL_ID.equals(detection.modelHint);
        Storage storage = Storage.NONE;
        if (storageSplit) {
            // Moving the app to where its daemons already run is consistent on any unit.
            if (sharkEvidence) storage = Storage.REPAIR;
        } else if (!alternateStorageActive && !serviceRunning
                && detection.platform != VehicleSetupDetector.Platform.DILINK100
                && !knownLegacyCore(detection) && sharkEvidence) {
            // The ADB probe at apply time decides; a pick alone never switches a working root.
            // The DI5 camera library is not required: the legacy root fails on a Shark either way.
            storage = Storage.CHECK;
        }

        String current = currentMode != null ? currentMode : MODE_DEFAULT;
        String mode;
        ModeSource source;
        if (overrideMode != null) {
            mode = overrideMode;
            source = ModeSource.OVERRIDE;
        } else if (detection.recommendedCameraMode != null) {
            mode = detection.recommendedCameraMode;
            source = ModeSource.DETECTED;
        } else if (!di5CameraLibrary && VehicleSetupDetector.MODE_DILINK5.equals(current)) {
            mode = MODE_DEFAULT;
            source = ModeSource.UNSUPPORTED;
        } else if (modelId == null) {
            // Not listed: default settings unless the hardware itself named a camera system.
            mode = MODE_DEFAULT;
            source = ModeSource.DEFAULT;
        } else if (di5CameraLibrary && MODE_DEFAULT.equals(current)
                && modelDefaultMode(modelId) != null) {
            // A model hint never replaces a mode someone chose on purpose.
            mode = modelDefaultMode(modelId);
            source = ModeSource.MODEL;
        } else {
            mode = current;
            source = ModeSource.CURRENT;
        }

        // A new root starts empty, so nothing on it is "already set".
        boolean freshRoot = storage != Storage.NONE;
        String baselineModel = freshRoot ? null : currentModelId;
        String baselineMode = freshRoot ? MODE_DEFAULT : current;
        return new VehicleSetupPlan(mode, source, storage,
                modelId != null && !modelId.equals(baselineModel), !mode.equals(baselineMode));
    }
}
