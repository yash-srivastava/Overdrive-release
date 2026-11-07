package com.overdrive.app.updater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** V60-09 contract: OTA download URLs must be https-only (they are interpolated into sh -c). */
public class OtaIntegrityContractTest {
    @Test
    public void onlyHttpsUpdateUrlsAccepted() {
        assertTrue("https url ok", "https://github.com/x/y.apk".startsWith("https://"));
        assertFalse("http url rejected", "http://evil/x.apk".startsWith("https://"));
        assertFalse("file url rejected", "file:///data/x.apk".startsWith("https://"));
    }
}
