package com.overdrive.app.receiver;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression test for run-2 finding
 * {@code exported-bootreceiver-forgeable-byd-actions}: forgeable vendor
 * actions (com.byd.*/com.htc.*) delivered to the exported BootReceiver must
 * be rejected unless the broadcaster is system (1000), shell (2000) or the
 * app itself.
 */
public class BootReceiverVendorActionContractTest {

    @Test
    public void vendorActionsAreSenderGated() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/receiver/BootReceiver.kt")), StandardCharsets.UTF_8);
        assertTrue(s.contains("action.startsWith(\"com.byd.\")"));
        assertTrue(s.contains("Binder.getCallingUid()"));
        assertTrue(s.contains("rejected forged vendor action"));
    }
}
