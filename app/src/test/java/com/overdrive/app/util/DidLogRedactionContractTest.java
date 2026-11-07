package com.overdrive.app.util;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** V60-05 contract: the device ID (credential-cipher key seed) must never appear raw in logs. */
public class DidLogRedactionContractTest {
    @Test
    public void redactionNeverContainsFullDid() {
        String did = "abcd1234-ef56-7890-abcd-ef0123456789";
        String redacted = did.substring(0, 4) + "…" + did.substring(did.length() - 4) + " (redacted)";
        assertFalse(redacted.contains(did));
        assertFalse(redacted.contains("ef56-7890"));
    }
}
