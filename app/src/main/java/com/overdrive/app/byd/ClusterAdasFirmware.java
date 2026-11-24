package com.overdrive.app.byd;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/** Read-only display decoders from the Seal's BusinessUi1. Other software can use different enums at the same IDs. */
final class ClusterAdasFirmware {
    private static final String RENDERER_SHA256 = "1bda5155149d5131af36a8ee2c5e1b4f944738a10b5b7bbafcf4a3945d6ff91d";
    private static volatile Boolean rendererMatches;

    static boolean isKnownNonmatch() {
        return Boolean.FALSE.equals(rendererMatches);
    }

    static boolean matchesInstalledRenderer() {
        Boolean cached = rendererMatches;
        if (cached != null) return cached;
        synchronized (ClusterAdasFirmware.class) {
            if (rendererMatches == null) {
                try (FileInputStream in = new FileInputStream("/system/lib64/libBydDataSource.so")) {
                    MessageDigest sha = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[16384];
                    int count;
                    while ((count = in.read(buffer)) != -1) sha.update(buffer, 0, count);
                    rendererMatches = MessageDigest.isEqual(sha.digest(), hexBytes(RENDERER_SHA256));
                } catch (IOException | SecurityException | NoSuchAlgorithmException ignored) {
                    rendererMatches = false;
                }
            }
            return rendererMatches;
        }
    }

    private static byte[] hexBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }

    // Inputs used by BusinessUi1::updateCarDistance and updateAdasCarInfo3R1V respectively.
    private static final int ACC_ONLINE = 0x32d00000;
    private static final int ACC_MODE = 0x32d00034;
    private static final int ACC_TIME_GAP = 0x32d00012;
    private static final int ACC_EQUIPPED = 0x43200008;
    private static final int INSTRUMENT_FRAME_ONLINE = 0x23500000;
    private static final int DRIVING_FRAME_ONLINE = 0x26f00000;
    private static final int REAR_RADAR_ONLINE = 0x41800000;
    // updateRearRadarFunctionEquipment sets its equipment flag if any of these five equals one.
    private static final int[] REAR_RADAR_CONFIG = {0x45300008, 0x45300009, 0x4530000a, 0x4530000b, 0x4530000c};
    private static final int DOOR_WARNING_SWITCH = 0x4180001e;
    private static final int DOOR_WARNING_LEFT = 0x41800020;
    private static final int DOOR_WARNING_RIGHT = 0x41800022;
    private static final int CROSS_TRAFFIC_SWITCH = 0x4180001a;
    private static final int CROSS_TRAFFIC_LEFT = 0x41800014;
    private static final int CROSS_TRAFFIC_RIGHT = 0x41800015;

    /** Look up the published DeviceFeaturesMap before requesting an optional frame register. */
    private static final class AdasFeatures {
        static final Set<?> published = load();
        static Set<?> load() {
            try {
                Class<?> api = Class.forName("android.hardware.bydauto.BYDAutoDeviceFeaturesMap");
                Object ids = api.getMethod("getFeatureIdsFromDevice", int.class).invoke(null, 1038);
                if (ids instanceof Set<?>) return (Set<?>) ids;
            } catch (ReflectiveOperationException | SecurityException | LinkageError unavailable) {}
            return Collections.emptySet();
        }
    }

    static int readGap(Object adas, Object bodywork, Object instrument, IntSupplier power) {
        if (!matchesInstalledRenderer()) return 0;
        if (adas != null && AdasFeatures.published.contains(ACC_ONLINE)
                && AdasFeatures.published.contains(ACC_TIME_GAP)) {
            int gap = readDrivingGap(id -> BydDeviceHelper.readPublicIntFeature(adas, id));
            if (gap > 0) return gap;
        }
        if (bodywork == null || instrument == null) return 0;
        return readInstrumentGap(id -> BydDeviceHelper.readPublicIntFeature(bodywork, id), power,
                () -> typedInt(instrument, "get2in1AccWorkInterface"),
                () -> typedInt(instrument, "get2in1AccTimeDistance"));
    }

    static int readDrivingGap(IntUnaryOperator adas) {
        if (adas.applyAsInt(ACC_ONLINE) != 1 || adas.applyAsInt(ACC_EQUIPPED) != 1) return 0;
        int mode = adas.applyAsInt(ACC_MODE);
        return mode >= 3 && mode <= 7 ? gapLevel(adas.applyAsInt(ACC_TIME_GAP)) : 0;
    }

    static int readInstrumentGap(IntUnaryOperator bodywork, IntSupplier power,
                                 IntSupplier workInterface, IntSupplier selectedLevel) {
        if (workInterface.getAsInt() != 1 || power.getAsInt() != 3
                || bodywork.applyAsInt(INSTRUMENT_FRAME_ONLINE) != 1
                || bodywork.applyAsInt(DRIVING_FRAME_ONLINE) != 1) return 0;
        return gapLevel(selectedLevel.getAsInt());
    }

    private static int gapLevel(int value) {
        return value >= 1 && value <= 4 ? value : 0;
    }

    private static int typedInt(Object device, String name) {
        Object value = BydDeviceHelper.callGetter(device, name);
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    static int readSideWarnings(Object device, boolean reverse, boolean stopped, IntSupplier power,
                                IntUnaryOperator existingCrossTraffic) {
        if ((!reverse && !stopped) || device == null || !matchesInstalledRenderer()) return 0;
        return readSideWarnings(id -> {
            if (id == CROSS_TRAFFIC_LEFT || id == CROSS_TRAFFIC_RIGHT) {
                return existingCrossTraffic.applyAsInt(id);
            }
            return BydDeviceHelper.readPublicIntFeature(device, id);
        }, reverse, stopped, power);
    }

    static int readSideWarnings(IntUnaryOperator signal, boolean reverse, boolean stopped, IntSupplier power) {
        if ((!reverse && !stopped) || signal.applyAsInt(REAR_RADAR_ONLINE) != 1) return 0;
        int sides = 0;
        if (reverse && signal.applyAsInt(CROSS_TRAFFIC_SWITCH) == 2) {
            if (signal.applyAsInt(CROSS_TRAFFIC_LEFT) == 1) sides |= BydDataCollector.RCTA_LEFT_BIT;
            if (signal.applyAsInt(CROSS_TRAFFIC_RIGHT) == 1) sides |= BydDataCollector.RCTA_RIGHT_BIT;
        }
        if (stopped && signal.applyAsInt(DOOR_WARNING_SWITCH) == 2 && power.getAsInt() == 1) {
            if (signal.applyAsInt(DOOR_WARNING_LEFT) == 2) sides |= BydDataCollector.DOW_LEFT_BIT;
            if (signal.applyAsInt(DOOR_WARNING_RIGHT) == 2) sides |= BydDataCollector.DOW_RIGHT_BIT;
        }
        if (sides != 0) {
            for (int config : REAR_RADAR_CONFIG) {
                if (signal.applyAsInt(config) == 1) return sides;
            }
        }
        return 0;
    }

    // BusinessUi1::updateAdasWarningInfo0x4 and updateAebFunctionEquipment on this renderer.
    private static final int PCW_PRE_WARNING = 0x32d00018;
    private static final int PCW_STATE = 0x32d0001a;
    private static final int ACUTE_WARNING_STATE = 0x32d0002b;
    private static final int AEB_STATE = 0x32d0002c;
    private static final int AEB_EQUIPPED = 0x43200009;

    static int readForwardWarning(Object device) {
        if (device == null || !matchesInstalledRenderer()) return 0;
        return readForwardWarning(id -> BydDeviceHelper.readPublicIntFeature(device, id),
                AdasFeatures.published.contains(ACC_ONLINE));
    }

    static int readForwardWarning(IntUnaryOperator signal, boolean frameDeclared) {
        int pre = signal.applyAsInt(PCW_PRE_WARNING);
        int pcw = signal.applyAsInt(PCW_STATE);
        int online = frameDeclared ? signal.applyAsInt(ACC_ONLINE) : Integer.MIN_VALUE;
        if (online == 1) {
            boolean triggered = (pre == 1 && pcw == 1)
                    || (signal.applyAsInt(ACUTE_WARNING_STATE) == 1 && signal.applyAsInt(AEB_STATE) == 1);
            return triggered && signal.applyAsInt(AEB_EQUIPPED) == 1 ? BydDataCollector.FORWARD_COLLISION_BIT : 0;
        }
        // A published validity register reporting offline/invalid must suppress the observation.
        // On the Seal SDK this register isn't exposed; use only fresh changes of the dedicated PCW
        // signal. ClusterWarningMonitor validates the edge and continuous fresh observations.
        if (frameDeclared || pcw != 1) return 0;
        if (pre == 0) return ClusterWarningMonitor.PCW_IDLE;
        if (pre != 1) return 0;
        int equipped = signal.applyAsInt(AEB_EQUIPPED);
        if (equipped != 1 && equipped >= 0 && equipped < 255) return 0;
        return ClusterWarningMonitor.PCW_ASSERTED;
    }

    private ClusterAdasFirmware() {}
}
