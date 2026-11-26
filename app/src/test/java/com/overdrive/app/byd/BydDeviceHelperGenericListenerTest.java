package com.overdrive.app.byd;

import android.hardware.IBYDAutoListener;

import org.junit.Test;

import static org.junit.Assert.*;

public class BydDeviceHelperGenericListenerTest {
    @Test
    public void unfilteredRegistrationPreservesProxyIdentityMethods() {
        UnfilteredDevice device = new UnfilteredDevice();
        assertTrue(BydDeviceHelper.registerListener(device, (name, args) -> fail(name)));
        IBYDAutoListener first = device.listener;
        assertNotNull(first);
        assertEquals("BydListener", first.toString());
        assertEquals(System.identityHashCode(first), first.hashCode());
        assertTrue(first.equals(first));
        assertFalse(first.equals(null));

        assertTrue(BydDeviceHelper.registerListener(device, (name, args) -> fail(name)));
        assertNotSame(first, device.listener);
        assertFalse(first.equals(device.listener));
    }

    @Test
    public void filteredRegistrationPrefersIdsOverUnfilteredOverload() {
        FilteredDevice device = new FilteredDevice();
        int[] ids = {1, 2};
        assertTrue(BydDeviceHelper.registerListener(device, ids, (name, args) -> fail(name)));
        assertSame(ids, device.ids);
        assertNotNull(device.filteredListener);
        assertNull(device.listener);
    }

    @Test
    public void missingFilteredOverloadFallsBackToUnfilteredRegistration() {
        UnfilteredDevice device = new UnfilteredDevice();
        assertTrue(BydDeviceHelper.registerListener(device, new int[]{1}, (name, args) -> fail(name)));
        assertNotNull(device.listener);
    }

    public static class UnfilteredDevice {
        IBYDAutoListener listener;

        public void registerListener(IBYDAutoListener listener) {
            this.listener = listener;
        }
    }

    public static final class FilteredDevice extends UnfilteredDevice {
        IBYDAutoListener filteredListener;
        int[] ids;

        public void registerListener(IBYDAutoListener listener, int[] ids) {
            this.filteredListener = listener;
            this.ids = ids;
        }
    }
}
