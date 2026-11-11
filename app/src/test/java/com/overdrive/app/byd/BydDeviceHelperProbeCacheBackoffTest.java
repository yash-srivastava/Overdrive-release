package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BydDeviceHelperProbeCacheBackoffTest {

    public static final class CountingProbeDevice {
        final List<Class<?>> seen = new ArrayList<>();
        Class<?> acceptedWidth;
        Object currentValue;

        CountingProbeDevice(Class<?> acceptedWidth, Object currentValue) {
            this.acceptedWidth = acceptedWidth;
            this.currentValue = currentValue;
        }

        public Object get(int[] ids, Class<?> type) {
            seen.add(type);
            if (type == acceptedWidth) {
                return currentValue;
            }
            throw new IllegalArgumentException("unsupported width " + type);
        }
    }

    @Test
    public void callGetProbingCachesWinningWidthOnSubsequentPolls() {
        CountingProbeDevice dev = new CountingProbeDevice(Integer.TYPE, 73);

        Object first = BydDeviceHelper.callGetProbing(dev, 0x32300018, false);
        assertNotNull(first);
        assertEquals(73, BydDeviceHelper.getIntValue(first));
        // First call walks Double -> Float -> Integer (3 calls).
        assertEquals(3, dev.seen.size());

        dev.seen.clear();
        Object second = BydDeviceHelper.callGetProbing(dev, 0x32300018, false);
        assertNotNull(second);
        assertEquals(73, BydDeviceHelper.getIntValue(second));
        // Subsequent poll hits cached Integer.TYPE directly (1 call instead of 3).
        assertEquals(1, dev.seen.size());
        assertEquals(Integer.TYPE, dev.seen.get(0));
    }

    @Test
    public void callGetProbingBacksOffAfterRepeatedFullLadderMissesAndRecoversOnReset() {
        CountingProbeDevice dev = new CountingProbeDevice(Short.TYPE, null);

        // Poll 1: tries all 4 widths, misses #1.
        assertNull(BydDeviceHelper.callGetProbing(dev, 0x44409999, false));
        assertEquals(4, dev.seen.size());

        // Poll 2: tries all 4 widths, misses #2 -> enters backoff.
        assertNull(BydDeviceHelper.callGetProbing(dev, 0x44409999, false));
        assertEquals(8, dev.seen.size());

        // Poll 3 during backoff window: zero HAL calls emitted.
        assertNull(BydDeviceHelper.callGetProbing(dev, 0x44409999, false));
        assertEquals(8, dev.seen.size());

        // Simulate ACC state transition reset and ECU coming online.
        dev.acceptedWidth = Integer.TYPE;
        dev.currentValue = 99;
        BydDeviceHelper.clearFeatureProbeBackoff();

        Object recovered = BydDeviceHelper.callGetProbing(dev, 0x44409999, true);
        assertNotNull(recovered);
        assertEquals(99, BydDeviceHelper.getIntValue(recovered));
    }

    @Test
    public void callGetBacksOffAfterRepeatedNullReadsOnSameDevice() {
        CountingProbeDevice dev = new CountingProbeDevice(Integer.TYPE, null);

        // First 3 misses reach the device; 3rd miss arms backoff.
        assertNull(BydDeviceHelper.callGet(dev, 679, Integer.class));
        assertNull(BydDeviceHelper.callGet(dev, 679, Integer.class));
        assertNull(BydDeviceHelper.callGet(dev, 679, Integer.class));
        assertEquals(3, dev.seen.size());

        // 4th call within backoff window skips binder/SDK invocation.
        assertNull(BydDeviceHelper.callGet(dev, 679, Integer.class));
        assertEquals(3, dev.seen.size());

        // Resetting backoff (e.g. on ACC transition) immediately allows fresh read.
        dev.currentValue = 1;
        BydDeviceHelper.clearFeatureProbeBackoff();
        Object r = BydDeviceHelper.callGet(dev, 679, Integer.class);
        assertNotNull(r);
        assertEquals(1, BydDeviceHelper.getIntValue(r));
        assertEquals(4, dev.seen.size());
    }
}
