package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClusterTransportTest {

    @Test
    public void sealionAndroid11FirmwareUsesTheProductAndSdkEvenWithRegionalDeviceOrMissingFingerprint() {
        assertEquals(ClusterTransport.AAOS_HDMI,
                ClusterTransport.resolve("auto", false, 30, "DX_BYD_AUTO", "regional_device",
                        "BYD/DX_BYD_AUTO/regional_device:11/RQ3A.211001.001/build:user/test-keys").transport);
        assertEquals(ClusterTransport.AAOS_HDMI,
                ClusterTransport.resolve("legacy", true, 30, "DX_BYD_AUTO", null, null).transport);
        assertFalse(ClusterTransport.isAaosHdmiFirmware("DiLink5.0", 30));
        assertFalse(ClusterTransport.isAaosHdmiFirmware("DX_BYD_AUTO_EXTRA", 30));
        assertFalse(ClusterTransport.isAaosHdmiFirmware("DX_BYD_AUTO", 33));
    }

    private static final String DILINK5_PRODUCT = "DiLink5.0";
    private static final String DILINK5_FINGERPRINT =
            "BYD-AUTO/DiLink5.0/DiLink5.0:12/SKQ1.230128.001/"
                    + "eng.build.20260729.110326:user/release-keys";
    private static final String LEGACY_PRODUCT = "byd_ivi";
    private static final String LEGACY_FINGERPRINT =
            "BYD-AUTO/byd_ivi/byd_ivi:9/PKQ1/release-keys";

    @Test
    public void diLink5CameraModeAlwaysWinsOverConfigAndFirmware() {
        ClusterTransport.Resolution mode = ClusterTransport.resolve(
                "legacy", true, 28, LEGACY_PRODUCT, null, LEGACY_FINGERPRINT);
        assertEquals(ClusterTransport.DILINK5, mode.transport);
        assertEquals(ClusterTransport.SOURCE_MODE, mode.source);
        assertTrue(mode.isDiLink5());
    }

    @Test
    public void explicitConfigOverrideBeatsFirmwareDetection() {
        ClusterTransport.Resolution forcedLegacy = ClusterTransport.resolve(
                "legacy", false, 31,
                DILINK5_PRODUCT, DILINK5_PRODUCT, DILINK5_FINGERPRINT);
        assertEquals(ClusterTransport.LEGACY, forcedLegacy.transport);
        assertEquals(ClusterTransport.SOURCE_CONFIG, forcedLegacy.source);
        assertFalse(forcedLegacy.isDiLink5());

        ClusterTransport.Resolution forcedDiLink5 = ClusterTransport.resolve(
                "dilink5", false, 28,
                LEGACY_PRODUCT, LEGACY_PRODUCT, LEGACY_FINGERPRINT);
        assertEquals(ClusterTransport.DILINK5, forcedDiLink5.transport);
        assertEquals(ClusterTransport.SOURCE_CONFIG, forcedDiLink5.source);
    }

    @Test
    public void autoSelectsDiLink5OnDiLink5FirmwareIdentity() {
        // The 2026-09-23 field unit: DiLink 5.0 head unit, default camera
        // mode, no QCarCam library — its AutoContainer rejects the legacy
        // protocol, so auto must route the cluster to the DI5 transport.
        ClusterTransport.Resolution auto = ClusterTransport.resolve(
                "auto", false, 31,
                DILINK5_PRODUCT, DILINK5_PRODUCT, DILINK5_FINGERPRINT);
        assertEquals(ClusterTransport.DILINK5, auto.transport);
        assertEquals(ClusterTransport.SOURCE_FIRMWARE, auto.source);

        ClusterTransport.Resolution absentKey = ClusterTransport.resolve(
                null, false, 31, null, null, DILINK5_FINGERPRINT);
        assertEquals(ClusterTransport.DILINK5, absentKey.transport);

        ClusterTransport.Resolution trinket = ClusterTransport.resolve(
                null, false, 33, "trinket", "trinket",
                "BYD-AUTO/trinket/trinket:13/build");
        assertEquals(ClusterTransport.DILINK5, trinket.transport);

        ClusterTransport.Resolution d50f = ClusterTransport.resolve(
                null, false, 33, "D50F_LC", null, null);
        assertEquals(ClusterTransport.DILINK5, d50f.transport);
    }

    @Test
    public void autoKeepsLegacyTransportOffDiLink5Firmware() {
        ClusterTransport.Resolution legacy = ClusterTransport.resolve(
                "auto", false, 28,
                LEGACY_PRODUCT, LEGACY_PRODUCT, LEGACY_FINGERPRINT);
        assertEquals(ClusterTransport.LEGACY, legacy.transport);
        assertEquals(ClusterTransport.SOURCE_DEFAULT, legacy.source);

        ClusterTransport.Resolution unknownIdentity = ClusterTransport.resolve(
                null, false, 28, null, null, null);
        assertEquals(ClusterTransport.LEGACY, unknownIdentity.transport);

        // Unrecognized config values fall back to auto, never to a forced
        // transport.
        ClusterTransport.Resolution garbage = ClusterTransport.resolve(
                "bogus", false, 28,
                LEGACY_PRODUCT, LEGACY_PRODUCT, LEGACY_FINGERPRINT);
        assertEquals(ClusterTransport.LEGACY, garbage.transport);
        assertEquals(ClusterTransport.SOURCE_DEFAULT, garbage.source);
    }

    @Test
    public void configuredValueNormalizationIsStrict() {
        assertEquals(ClusterTransport.AUTO,
                ClusterTransport.normalizeConfigured(" AUTO "));
        assertEquals(ClusterTransport.LEGACY,
                ClusterTransport.normalizeConfigured("Legacy"));
        assertEquals(ClusterTransport.DILINK5,
                ClusterTransport.normalizeConfigured("DILINK5"));
        assertNull(ClusterTransport.normalizeConfigured(null));
        assertNull(ClusterTransport.normalizeConfigured(""));
        assertNull(ClusterTransport.normalizeConfigured("dilink4"));
        assertNull(ClusterTransport.normalizeConfigured("default"));
    }

    @Test
    public void firmwareDetectionMatchesTheDiLink5FamilyOnly() {
        assertTrue(ClusterTransport.isDiLink5Firmware(
                DILINK5_PRODUCT, null, null));
        assertTrue(ClusterTransport.isDiLink5Firmware(
                null, null, DILINK5_FINGERPRINT));
        assertTrue(ClusterTransport.isDiLink5Firmware(
                "trinket", null, null));
        assertTrue(ClusterTransport.isDiLink5Firmware(
                null, "D50F_LC", null));
        assertFalse(ClusterTransport.isDiLink5Firmware(
                LEGACY_PRODUCT, "sa8155p", LEGACY_FINGERPRINT));
        assertFalse(ClusterTransport.isDiLink5Firmware(null, null, null));
    }
}
