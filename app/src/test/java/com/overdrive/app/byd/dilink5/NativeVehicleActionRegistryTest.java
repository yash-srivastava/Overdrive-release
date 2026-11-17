package com.overdrive.app.byd.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeVehicleActionRegistryTest {
    @Test
    public void everyNamedActionRoundTripsThroughExactTuple() {
        for (NativeVehicleAction action : NativeVehicleAction.values()) {
            assertSame(action, NativeVehicleAction.fromTuple(
                    action.device, action.featureId, action.value));
            assertSame(action, NativeVehicleAction.fromId(action.id));
        }
        assertEquals(14, NativeVehicleAction.values().length);
    }

    @Test
    public void unknownAndOutOfRangeWritesAreRejected() {
        assertNull(NativeVehicleAction.fromTuple(1001, 1125122080, 2));
        assertNull(NativeVehicleAction.fromTuple(1001, 1125122056, 0));
        assertNull(NativeVehicleAction.fromTuple(1001, 1125122056, 7));
        assertNull(NativeVehicleAction.fromTuple(1000, 501219364, 2));
        assertNull(NativeVehicleAction.fromTuple(9999, 1, 1));
    }

    @Test
    public void device1004AdmitsOnlyHazardException() {
        assertSame(NativeVehicleAction.HAZARDS_ON,
                NativeVehicleAction.fromTuple(1004, 871366669, 1));
        assertSame(NativeVehicleAction.HAZARDS_OFF,
                NativeVehicleAction.fromTuple(1004, 871366669, 0));
        assertNull(NativeVehicleAction.fromTuple(1004, 871366667, 1));
        assertNull(NativeVehicleAction.fromTuple(1004, 871366669, 2));
        assertNull(NativeVehicleAction.fromTuple(1004, 1, 1));
    }

    @Test
    public void sdkStopAndNativeTiltCannotAlias() {
        assertEquals(3, NativeVehicleAction.SUNROOF_TILT.value);
        assertEquals(4, NativeVehicleAction.SUNROOF_STOP.value);
        assertEquals(6, NativeVehicleAction.SUNROOF_COMFORT.value);
        assertTrue(NativeVehicleAction.SUNROOF_TILT.opensSunroof());
        assertFalse(NativeVehicleAction.SUNROOF_STOP.opensSunroof());
        assertFalse(NativeVehicleAction.SUNROOF_CLOSE.opensSunroof());
    }
}
