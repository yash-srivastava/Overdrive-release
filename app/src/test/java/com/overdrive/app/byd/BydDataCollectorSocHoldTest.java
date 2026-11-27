package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** SOC hold must not treat a missing getSocSaveSwitch readback as a refusal. */
public class BydDataCollectorSocHoldTest {

    private DaemonLogger.Config originalLoggerConfig;

    @Before
    public void setUp() {
        originalLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void tearDown() {
        DaemonLogger.configure(originalLoggerConfig);
    }

    @Test
    public void holdWritesTargetWhenFirmwareHasNoSwitchGetter() throws Exception {
        WriteOnlyChargingDevice charging = new WriteOnlyChargingDevice();
        FakeSettingDevice setting = new FakeSettingDevice();
        BydDataCollector collector = collectorWith(charging, setting);

        assertTrue(collector.applySocHoldAtFloor());
        assertEquals(Collections.singletonList("switch:1"), charging.operations);
        assertEquals(Collections.singletonList("target:15"), setting.operations);
    }

    @Test
    public void holdLeavesTargetUntouchedWhenSwitchReadsBackDifferently() throws Exception {
        ReadableChargingDevice charging = new ReadableChargingDevice();
        charging.ignoreWrites = true;
        FakeSettingDevice setting = new FakeSettingDevice();
        BydDataCollector collector = collectorWith(charging, setting);

        assertFalse(collector.applySocHoldAtFloor());
        assertEquals(Collections.singletonList("switch:1"), charging.operations);
        assertTrue(setting.operations.isEmpty());
        assertEquals(40, setting.socTarget);
    }

    @Test
    public void holdWritesTargetWhenSwitchReadbackMatches() throws Exception {
        ReadableChargingDevice charging = new ReadableChargingDevice();
        FakeSettingDevice setting = new FakeSettingDevice();
        BydDataCollector collector = collectorWith(charging, setting);

        assertTrue(collector.applySocHoldAtFloor());
        assertEquals(1, charging.socSaveSwitch);
        assertEquals(Collections.singletonList("target:15"), setting.operations);
    }

    @Test
    public void modeOnlyPathsAcceptAnUnreadableSwitch() throws Exception {
        WriteOnlyChargingDevice charging = new WriteOnlyChargingDevice();
        FakeSettingDevice setting = new FakeSettingDevice();
        BydDataCollector collector = collectorWith(charging, setting);

        assertTrue(collector.enableSocHoldAtSavedTarget());
        assertTrue(collector.clearSocHold());
        assertEquals(Arrays.asList("switch:2", "switch:0"), charging.operations);
        assertTrue(setting.operations.isEmpty());
    }

    private static BydDataCollector collectorWith(Object charging, Object setting) throws Exception {
        Constructor<BydDataCollector> constructor = BydDataCollector.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BydDataCollector collector = constructor.newInstance();
        set(collector, "chargingDevice", charging);
        set(collector, "settingDevice", setting);
        return collector;
    }

    private static void set(BydDataCollector collector, String name, Object value) throws Exception {
        Field field = BydDataCollector.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(collector, value);
    }

    /** The common trim: the switch can be written but has no getter. */
    public static class WriteOnlyChargingDevice {
        final List<String> operations = new ArrayList<>();

        public int setSocSaveSwitch(int mode) {
            operations.add("switch:" + mode);
            return 0;
        }
    }

    public static final class ReadableChargingDevice extends WriteOnlyChargingDevice {
        boolean ignoreWrites;
        int socSaveSwitch;

        @Override
        public int setSocSaveSwitch(int mode) {
            super.setSocSaveSwitch(mode);
            if (!ignoreWrites) socSaveSwitch = mode;
            return 0;
        }

        public int getSocSaveSwitch() {
            return socSaveSwitch;
        }
    }

    public static final class FakeSettingDevice {
        final List<String> operations = new ArrayList<>();
        int socTarget = 40;

        public int getSOCConfig() {
            return 1;
        }

        public int setSOCTarget(int percent) {
            operations.add("target:" + percent);
            socTarget = percent;
            return 0;
        }

        public int getSOCTarget() {
            return socTarget;
        }
    }
}
