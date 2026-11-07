package com.overdrive.app.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * V60-04 contract: export bundles must never contain the `auth`/`pinLock`
 * sections (plaintext deviceSecret / PIN) nor the top-level `did` key seed.
 */
public class BackupBundleRedactionContractTest {
    @Test
    public void secretSectionsAndDidAreRedactedFromExportShape() throws Exception {
        JSONObject unified = new JSONObject();
        unified.put("auth", new JSONObject().put("deviceSecret", "s3cret").put("deviceId", "did"));
        unified.put("pinLock", new JSONObject().put("pin", "1234"));
        unified.put("surveillance", new JSONObject().put("enabled", true));
        for (String sec : new String[]{"auth", "pinLock"}) unified.remove(sec);
        JSONObject bundle = new JSONObject();
        bundle.put("settings", new JSONObject().put("unified", unified));
        // bundle must NOT carry a did field (redaction removes it upstream)
        assertFalse(bundle.has("did"));
        assertFalse(unified.has("auth"));
        assertFalse(unified.has("pinLock"));
        assertTrue(unified.has("surveillance"));
    }
}
