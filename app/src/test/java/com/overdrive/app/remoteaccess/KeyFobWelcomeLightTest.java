package com.overdrive.app.remoteaccess;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeyFobWelcomeLightTest {
    public static final class SettingDevice {
        public Object feature = 1, state = 2;
        public int result, probes, reads, writes;
        public Runnable onRead = () -> {};

        public Object hasFeature(String name) {
            assertEquals("SmartWelcomeLightState", name);
            probes++;
            return feature;
        }

        public Object getSmartWelcomeLightState() {
            reads++;
            onRead.run();
            return state;
        }

        public int setSmartWelcomeLightState(int value) {
            assertEquals(1, value);
            writes++;
            return result;
        }
    }

    @Test public void requiresLiveFobActionsInEitherDirection() throws Exception {
        JSONObject settings = new JSONObject().put("enabled", true).put("keyFobEnabled", true)
                .put("unlockEnabled", true);
        assertFalse(KeyFobWelcomeLight.enabled(settings)); // Missing dryRun defaults to learning mode.
        assertFalse(KeyFobWelcomeLight.enabled(settings.put("dryRun", true)));
        assertTrue(KeyFobWelcomeLight.enabled(settings.put("dryRun", false)));
        assertFalse(KeyFobWelcomeLight.enabled(settings.put("unlockEnabled", false).put("lockEnabled", true)));
        assertTrue(KeyFobWelcomeLight.enabled(settings.put("keyFobLockEnabled", true)));
        assertFalse(KeyFobWelcomeLight.enabled(settings.put("lockEnabled", false)));
        settings.put("lockEnabled", true);
        assertFalse(KeyFobWelcomeLight.enabled(settings.put("enabled", false)));
        assertFalse(KeyFobWelcomeLight.enabled(settings.put("enabled", true).put("keyFobEnabled", false)));
    }

    @Test public void startupParkedAndLaterPowerOffEachScheduleOneCheck() {
        SettingDevice device = new SettingDevice();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        KeyFobWelcomeLight light = new KeyFobWelcomeLight(tasks::add, () -> device, () -> true);
        light.observe(-1, true);
        light.observe(0, false);
        assertTrue(tasks.isEmpty());
        light.observe(0, true);
        light.observe(0, true);
        assertEquals(1, tasks.size());
        assertEquals(0, device.probes);
        tasks.remove().run();
        assertEquals(1, device.writes);
        light.observe(-1, true);
        light.observe(0, true);
        assertTrue(tasks.isEmpty());
        light.observe(1, true);
        light.observe(0, true);
        device.state = 1;
        tasks.remove().run();
        assertEquals(2, device.reads);
        assertEquals(1, device.writes);
    }

    @Test public void canceledParkingAndDisabledRulesDiscardQueuedChecks() {
        SettingDevice device = new SettingDevice();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        KeyFobWelcomeLight light = new KeyFobWelcomeLight(tasks::add, () -> device, () -> true);
        light.observe(0, true);
        light.observe(2, true);
        light.observe(0, true);
        light.observe(0, false);
        light.observe(0, true);
        tasks.remove().run();
        tasks.remove().run();
        assertEquals(0, device.probes);
        tasks.remove().run();
        assertEquals(1, device.writes);
    }

    @Test public void freshPowerAndConsentAreRecheckedAfterTheGetter() {
        SettingDevice device = new SettingDevice();
        AtomicBoolean allowed = new AtomicBoolean(true);
        device.onRead = () -> allowed.set(false);
        KeyFobWelcomeLight light = new KeyFobWelcomeLight(Runnable::run, () -> device, allowed::get);
        light.observe(0, true);
        assertEquals(1, device.reads);
        assertEquals(0, device.writes);
    }

    @Test public void unsupportedOrUnknownStateNeverWritesAndSdkFailureIsIsolated() {
        SettingDevice device = new SettingDevice();
        KeyFobWelcomeLight light = new KeyFobWelcomeLight(Runnable::run, () -> device, () -> true);
        for (Object feature : new Object[]{0, -1, null, true}) {
            device.feature = feature;
            light.observe(1, true);
            light.observe(0, true);
        }
        assertEquals(0, device.reads);
        device.feature = 1L;
        for (Object state : new Object[]{1, 0, -1, null, "2", 2.5}) {
            device.state = state;
            light.observe(1, true);
            light.observe(0, true);
        }
        assertEquals(0, device.writes);
        device.state = (short) 2;
        device.onRead = () -> { throw new IllegalStateException("service unavailable"); };
        light.observe(1, true);
        light.observe(0, true);
        assertEquals(0, device.writes);
        device.onRead = () -> {};
        device.result = -1;
        light.observe(1, true);
        light.observe(0, true);
        light.observe(0, true);
        assertEquals(1, device.writes);
    }
}
