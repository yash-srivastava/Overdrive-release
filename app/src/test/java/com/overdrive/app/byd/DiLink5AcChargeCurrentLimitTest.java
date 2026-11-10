package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.bydauto.TsManagerImpl;
import android.os.IBinder;
import android.os.IInterface;

import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.ts.lib.caradapter.CarAdapterManager;
import com.ts.lib.caradapter.body.ChargingAdapterManager;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

public class DiLink5AcChargeCurrentLimitTest {

    @After
    public void tearDown() {
        TsManagerImpl.testInstance = null;
        CarAdapterManager.testInstance = null;
    }

    private static Context stubContext() {
        return new ContextWrapper(null) {
            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
    }

    private static IInterface binderBackedService(AtomicBoolean alive) {
        IBinder binder = (IBinder) Proxy.newProxyInstance(
                IBinder.class.getClassLoader(),
                new Class<?>[] {IBinder.class},
                (proxy, method, args) -> {
                    if ("isBinderAlive".equals(method.getName())) {
                        return alive.get();
                    }
                    if ("pingBinder".equals(method.getName())) {
                        return alive.get();
                    }
                    return null;
                });
        return (IInterface) Proxy.newProxyInstance(
                IInterface.class.getClassLoader(),
                new Class<?>[] {IInterface.class},
                (proxy, method, args) -> "asBinder".equals(method.getName()) ? binder : null);
    }

    private static final class StaleOtaChargingDevice {
        int deviceLimit = 0;
        int deviceRc = -1;
        int setCalls = 0;

        public int getACChargingLimit() {
            return deviceLimit;
        }

        public int setACChargingLimit(int state) {
            setCalls++;
            if (deviceRc >= 0) {
                deviceLimit = state;
            }
            return deviceRc;
        }
    }

    @Test
    public void acChargeCurrentLimitCommandExemptFromAccSafetyLeaseTruncation() {
        assertFalse(VehicleActuatorBridge.usesDiLink5AccLease(
                new VehicleCommandRouter.AcChargeCurrentLimitCommand(4)));
        assertFalse(VehicleActuatorBridge.usesDiLink5AccLease(
                new VehicleCommandRouter.AmbientBrightnessCommand(3)));
        assertTrue(VehicleActuatorBridge.usesDiLink5AccLease(
                new VehicleCommandRouter.LockCommand()));
    }

    @Test
    public void carAdapterManagerFallbackResolvesChargingAdapterWhenTsManagerFieldIsNull() {
        AtomicBoolean binderAlive = new AtomicBoolean(true);
        ChargingAdapterManager charging =
                new ChargingAdapterManager(binderBackedService(binderAlive));

        // Simulate byd.ota.master.jar where TsManagerImpl.mChargingManager is still null.
        TsManagerImpl ts = new TsManagerImpl(null);
        ts.carServiceConnected = true;
        ts.replaceCharging(null);
        TsManagerImpl.testInstance = ts;

        CarAdapterManager carMgr = new CarAdapterManager();
        carMgr.bound = false;
        carMgr.putAdapter("charging", charging);
        carMgr.onConnect = () -> carMgr.bound = true;
        CarAdapterManager.testInstance = carMgr;

        // First probe triggers connect() while unbound and returns null so caller can retry.
        assertNull(BydDeviceHelper.acquireDiLink5CarAdapter(
                stubContext(), null, "getChargingAdapterManager", "charging"));
        assertEquals(1, carMgr.connectCalls);

        // Next probe sees bound=true and live IBinder, returning the ChargingAdapterManager.
        Object resolved = BydDeviceHelper.awaitDiLink5CarAdapter(
                stubContext(), null, "getChargingAdapterManager", "charging", 3, 0L);
        assertSame(charging, resolved);

        // Dead binder is rejected rather than returning a disconnected adapter.
        binderAlive.set(false);
        assertNull(BydDeviceHelper.acquireDiLink5CarAdapter(
                stubContext(), null, "getChargingAdapterManager", "charging"));
    }

    @Test
    public void writeAndReadFallbackToChargingAdapterWhenOtaDeviceReturnsMinusOne() {
        AtomicBoolean binderAlive = new AtomicBoolean(true);
        ChargingAdapterManager chargingAdapter =
                new ChargingAdapterManager(binderBackedService(binderAlive));
        chargingAdapter.acChargingLimit = 2; // 8A
        chargingAdapter.setReturnCode = 0;

        StaleOtaChargingDevice staleDevice = new StaleOtaChargingDevice();
        staleDevice.deviceLimit = 0;
        staleDevice.deviceRc = -1;

        assertEquals(2, BydDataCollector.readDiLink5AcChargingLimitFromHandles(
                staleDevice, chargingAdapter));

        String[] via = new String[1];
        Object rc = BydDataCollector.writeDiLink5AcChargingLimitToHandles(
                staleDevice, chargingAdapter, 4, via);
        assertNotNull(rc);
        assertEquals(0, ((Number) rc).intValue());
        assertEquals(1, staleDevice.setCalls);
        assertEquals(1, chargingAdapter.writeCalls);
        assertEquals("ChargingAdapterManager(afterDeviceRc=-1)", via[0]);
        assertEquals(4, BydDataCollector.readDiLink5AcChargingLimitFromHandles(
                staleDevice, chargingAdapter));
    }

    @Test
    public void studyConfigResolvesSupportedAndUnsupportedTrimsAccurately() {
        // Unpopulated study property (0) and unplugged OBC readback (0): transiently supported
        // without latching so a subsequent configState=1 still wins.
        BydDataCollector.AcChargingCurrentLimitStatus unknown =
                BydDataCollector.evaluateDiLink5AcChargingCurrentLimitStatus(0, 0, null);
        assertEquals(Boolean.TRUE, unknown.supported);
        assertTrue(unknown.available);
        assertEquals(BydVehicleData.UNAVAILABLE, unknown.configState);
        assertEquals(BydVehicleData.UNAVAILABLE, unknown.state);

        // Explicit OEM NOT_SUPPORT (configState=1) with no 1..5 readback -> unsupported.
        BydDataCollector.AcChargingCurrentLimitStatus unsupported =
                BydDataCollector.evaluateDiLink5AcChargingCurrentLimitStatus(1, 0, null);
        assertEquals(Boolean.FALSE, unsupported.supported);
        assertTrue(unsupported.available);
        assertEquals(1, unsupported.configState);
        assertEquals(BydVehicleData.UNAVAILABLE, unsupported.state);

        // Explicit OEM SUPPORT (configState=2) even while unplugged (state=0) -> supported & available.
        BydDataCollector.AcChargingCurrentLimitStatus supportedUnplugged =
                BydDataCollector.evaluateDiLink5AcChargingCurrentLimitStatus(2, 0, null);
        assertEquals(Boolean.TRUE, supportedUnplugged.supported);
        assertTrue(supportedUnplugged.available);
        assertEquals(2, supportedUnplugged.configState);
        assertEquals(BydVehicleData.UNAVAILABLE, supportedUnplugged.state);

        // A valid 1..5 readback proves physical capability regardless of config byte.
        BydDataCollector.AcChargingCurrentLimitStatus provenByState =
                BydDataCollector.evaluateDiLink5AcChargingCurrentLimitStatus(1, 5, Boolean.FALSE);
        assertEquals(Boolean.TRUE, provenByState.supported);
        assertTrue(provenByState.available);
        assertEquals(5, provenByState.state);
    }
}
