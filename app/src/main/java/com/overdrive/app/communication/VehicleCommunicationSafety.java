package com.overdrive.app.communication;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

/** Motion and overlay-surface gates used by remote communication. */
public final class VehicleCommunicationSafety {

    private static final long ROAD_SENSE_STATE_FRESH_MS = 5_000L;

    private VehicleCommunicationSafety() {}

    /** True only after the daemon has received an authoritative ACC reading. */
    public static boolean isCarPowerStateKnown() {
        try {
            return AccMonitor.isAccStateAuthoritative();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Unknown is deliberately not treated as off during daemon startup. */
    public static boolean isCarKnownOff() {
        try {
            return AccMonitor.isAccStateAuthoritative() && !AccMonitor.isAccOn();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Dialogs are permitted only with a positive, current Park signal. */
    public static boolean isParked() {
        Integer gear = readGear();
        return gear != null && gear == GearMonitor.GEAR_P;
    }

    /**
     * The compact voice overlay yields to reverse, blind-spot, and active
     * RoadSense hazard/confirmation surfaces. Audio continues underneath.
     */
    public static boolean isRemoteVoiceOverlaySafe() {
        Integer gear = readGear();
        if (DiLink5Platform.isSelected() && gear == null) return false;
        if (gear != null && gear == GearMonitor.GEAR_R) return false;

        try {
            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.isBlindSpotCardShowing()) return false;
        } catch (Throwable ignored) {}

        try {
            com.overdrive.app.roadsense.warn.OverlayState state =
                    com.overdrive.app.roadsense.warn.RoadSenseOverlayChannel.INSTANCE.current();
            if (state != null) {
                long age = System.currentTimeMillis() - state.getUpdatedMs();
                if (age >= 0L && age <= ROAD_SENSE_STATE_FRESH_MS
                        && (state.getHazardAhead()
                        || state.getPendingConfirm() != null)) {
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private static Integer readGear() {
        if (DiLink5Platform.isSelected()) {
            try {
                int gear = BydDataCollector.getInstance().readGearNow();
                return gear >= GearMonitor.GEAR_P && gear <= GearMonitor.GEAR_S
                        ? gear : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
        try {
            GearMonitor monitor = GearMonitor.getInstance();
            if (monitor != null && monitor.isRunning()) {
                return monitor.getCurrentGear();
            }
        } catch (Throwable ignored) {}
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            BydVehicleData data = collector == null ? null : collector.getData();
            if (data != null && data.gearMode != BydVehicleData.UNAVAILABLE) {
                return data.gearMode;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
