package com.overdrive.app.byd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Test;

public class AcVentControlTest {

    @Test
    public void anglesAreSentAs128PlusDegrees() {
        assertEquals(128, AcVentControl.encodeAngle(0));
        assertEquals(110, AcVentControl.encodeAngle(-18));
        assertEquals(148, AcVentControl.encodeAngle(20));
        assertEquals(Integer.valueOf(-18), AcVentControl.decodeAngle(110));
    }

    @Test
    public void rawThirtyEightIsNotAPosition() {
        // Raw 38 means the vent is not open.
        assertNull(AcVentControl.decodeAngle(38));
        assertNull(AcVentControl.decodeAngle(Integer.MIN_VALUE));
    }

    @Test
    public void leftHandDriveUsesTheOemLhdTable() {
        assertArrayEquals(new int[] {-18, 20, -30, 5}, AcVentControl.angleRange(0, false));
        assertArrayEquals(new int[] {-23, 3, -40, 15}, AcVentControl.angleRange(1, false));
        assertArrayEquals(new int[] {-10, 18, -40, 10}, AcVentControl.angleRange(2, false));
        assertArrayEquals(new int[] {-20, 18, -30, 10}, AcVentControl.angleRange(3, false));
    }

    @Test
    public void rightHandDriveUsesTheOemRhdTable() {
        assertArrayEquals(new int[] {-10, 18, -40, 10}, AcVentControl.angleRange(0, true));
        assertArrayEquals(new int[] {-20, 18, -30, 10}, AcVentControl.angleRange(1, true));
        assertArrayEquals(new int[] {-18, 20, -30, 5}, AcVentControl.angleRange(2, true));
        assertArrayEquals(new int[] {-23, 3, -40, 15}, AcVentControl.angleRange(3, true));
    }

    @Test
    public void modeReadbackFollowsOemPriority() {
        assertEquals("focus", AcVentControl.modeFromFlags(1, 1, 1, 1));
        assertEquals("avoid", AcVentControl.modeFromFlags(1, 2, 1, 1));
        assertEquals("swing", AcVentControl.modeFromFlags(1, 2, 2, 1));
        assertEquals("free", AcVentControl.modeFromFlags(1, 2, 2, 2));
        assertEquals("off", AcVentControl.modeFromFlags(2, 2, 2, 2));
        assertNull(AcVentControl.modeFromFlags(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE));
    }

    @Test
    public void windModeNamesMatchOemValues() {
        assertEquals("face", AcVentControl.windModeName(1));
        assertEquals("face_foot", AcVentControl.windModeName(2));
        assertEquals("foot", AcVentControl.windModeName(3));
        assertEquals("foot_defrost", AcVentControl.windModeName(4));
        assertEquals("defrost", AcVentControl.windModeName(5));
        assertEquals("face_foot_defrost", AcVentControl.windModeName(6));
        assertEquals("face_defrost", AcVentControl.windModeName(7));
        assertNull(AcVentControl.windModeName(0));
        assertEquals(4, AcVentControl.windModeFromName("foot_defrost"));
        assertEquals(-1, AcVentControl.windModeFromName("feet"));
    }

    @Test
    public void fiveModeCarsRejectTheCombinedDefrostModes() {
        assertTrue(AcVentControl.isValidWindMode(5, 5));
        assertFalse(AcVentControl.isValidWindMode(6, 5));
        assertTrue(AcVentControl.isValidWindMode(7, 7));
        assertTrue(AcVentControl.isValidWindMode(7, BydVehicleData.UNAVAILABLE));
        assertFalse(AcVentControl.isValidWindMode(0, 7));
        assertFalse(AcVentControl.isValidWindMode(8, 7));
    }

    @Test
    public void electricVentMarker() {
        assertFalse(AcVentControl.isElectricVentMarker(0));
        assertFalse(AcVentControl.isElectricVentMarker(1));
        assertTrue(AcVentControl.isElectricVentMarker(2));
        assertTrue(AcVentControl.isElectricVentMarker(4));
        assertFalse(AcVentControl.isElectricVentMarker(5));
    }

    @Test
    public void inputValidation() {
        assertTrue(AcVentControl.isValidMode("swing"));
        assertFalse(AcVentControl.isValidMode("auto"));
        assertFalse(AcVentControl.isValidMode(null));
        assertEquals(1, AcVentControl.ventIndex("driver_right"));
        assertEquals(-1, AcVentControl.ventIndex("rear"));
        assertFalse(AcVentControl.isValidSide(0));
    }

    /**
     * The test classpath's BYDAutoFeatureIds stub does not publish the vent ids, so they stay
     * UNRESOLVED: the API must then report unsupported and refuse every write, never send an id.
     */
    @Test
    public void unresolvedIdsMeanUnsupportedAndNoWrites() throws Exception {
        FakeHal hal = new FakeHal();
        if (BydFeatureIds.isResolved(BydFeatureIds.AC_FRONT_ELECTRIC_TUYERE_SELF_STUDY_MARKER)) {
            return; // A stub that publishes the ids is covered by the resolved-path tests.
        }
        assertFalse(AcVentControl.isSupported(hal));
        assertFalse(AcVentControl.setMode(hal, AcVentControl.SIDE_DRIVER, "focus"));
        assertNull(AcVentControl.setDirection(hal, 0, 0, 0, false));
        JSONObject state = AcVentControl.readState(hal, false);
        assertFalse(state.getBoolean("supported"));
        assertFalse(state.has("mode"));
        assertTrue(hal.writes.isEmpty());
    }

    @Test
    public void directionIsOnePairedWriteClampedToTheVentRange() {
        FakeHal hal = new FakeHal();
        // LHD driver-right vent: h -23..3, v -40..15.
        int[] sent = AcVentControl.setDirection(hal, new int[] {11, 12}, 1, 30, -60, false);
        assertArrayEquals(new int[] {3, -40}, sent);
        assertEquals(1, hal.writes.size());
        assertEquals("11,12=131,88", hal.writes.get(0));
    }

    @Test
    public void refusedDirectionWriteReportsNothingSent() {
        FakeHal hal = new FakeHal();
        hal.result = -1;
        assertNull(AcVentControl.setDirection(hal, new int[] {11, 12}, 0, 0, 0, false));
    }

    @Test
    public void directionNeverWritesAnUnresolvedId() {
        FakeHal hal = new FakeHal();
        assertNull(AcVentControl.setDirection(hal,
                new int[] {11, BydFeatureIds.UNRESOLVED_ID}, 0, 0, 0, false));
        assertTrue(hal.writes.isEmpty());
    }

    private static final class FakeHal implements AcVentControl.Hal {
        int result = 0;
        final Map<Integer, Integer> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();

        @Override public int get(int featureId) {
            Integer v = values.get(featureId);
            return v == null ? Integer.MIN_VALUE : v;
        }

        @Override public int set(int featureId, int value) {
            writes.add(featureId + "=" + value);
            return result;
        }

        @Override public int setPair(int[] featureIds, int[] values) {
            writes.add(featureIds[0] + "," + featureIds[1] + "=" + values[0] + "," + values[1]);
            return result;
        }
    }
}
