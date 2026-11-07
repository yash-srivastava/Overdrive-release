package com.overdrive.app.telegram.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** V60-07 contract: pair PIN verification is hash-based; raw PIN is never comparable from storage. */
public class PairPinHashContractTest {
    @Test
    public void hashIsDeterministicAndPinSpecific() {
        String h1 = UnifiedTelegramConfig.pairPinHash("123456");
        String h2 = UnifiedTelegramConfig.pairPinHash("123456");
        String h3 = UnifiedTelegramConfig.pairPinHash("654321");
        assertEquals(h1, h2);
        assertNotEquals(h1, h3);
        assertNotEquals("123456", h1);
        assertEquals(64, h1.length()); // sha-256 hex
    }
}
