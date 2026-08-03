package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class RemoteDevViewSessionManagerTest {

    @Test
    public void startingNewSessionInvalidatesPreviousCapability() {
        MutableClock clock = new MutableClock();
        CountingRandom random = new CountingRandom();
        RemoteDevViewSessionManager manager = new RemoteDevViewSessionManager(clock, random);

        RemoteDevViewSessionManager.Session first = manager.start();
        RemoteDevViewSessionManager.Session second = manager.start();

        assertNotEquals(first.token, second.token);
        assertNull(manager.validateAndTouch(first.token));
        assertNotNull(manager.validateAndTouch(second.token));
    }

    @Test
    public void inactivityExpiresSessionAndClearsActiveState() {
        MutableClock clock = new MutableClock();
        RemoteDevViewSessionManager manager =
            new RemoteDevViewSessionManager(clock, new CountingRandom());
        String token = manager.start().token;

        clock.now = RemoteDevViewSessionManager.IDLE_TIMEOUT_MS + 1;

        assertNull(manager.validateAndTouch(token));
        assertFalse(manager.hasActiveSession());
    }

    @Test
    public void activityExtendsIdleButNeverMaximumLifetime() {
        MutableClock clock = new MutableClock();
        RemoteDevViewSessionManager manager =
            new RemoteDevViewSessionManager(clock, new CountingRandom());
        String token = manager.start().token;

        while (clock.now < RemoteDevViewSessionManager.MAX_LIFETIME_MS) {
            clock.now += RemoteDevViewSessionManager.IDLE_TIMEOUT_MS - 1;
            if (clock.now > RemoteDevViewSessionManager.MAX_LIFETIME_MS) break;
            assertNotNull(manager.validateAndTouch(token));
        }
        clock.now = RemoteDevViewSessionManager.MAX_LIFETIME_MS + 1;

        assertNull(manager.validateAndTouch(token));
    }

    @Test
    public void endingRequiresExactCapability() {
        RemoteDevViewSessionManager manager = new RemoteDevViewSessionManager();
        String token = manager.start().token;

        assertFalse(manager.end(token + "0"));
        assertTrue(manager.hasActiveSession());
        assertTrue(manager.end(token));
        assertFalse(manager.hasActiveSession());
    }

    private static final class MutableClock implements RemoteDevViewSessionManager.Clock {
        long now;
        @Override public long now() { return now; }
    }

    private static final class CountingRandom implements RemoteDevViewSessionManager.RandomBytes {
        private byte value = 1;
        @Override public void nextBytes(byte[] target) {
            Arrays.fill(target, value++);
        }
    }
}
