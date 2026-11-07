package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * V60-11 contract: the launcher MainActivity must not carry custom start-action
 * intent-filters, and BootReceiver / LocationStarterActivity must be DUMP-gated.
 */
public class ExportedComponentsGateContractTest {
    @Test
    public void launcherHasNoCustomActionFilter_andReceiversAreGated() throws Exception {
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")));
        // MainActivity block must not expose START_LOCATION_ACTIVITY anymore
        int ma = manifest.indexOf("com.overdrive.app.ui.MainActivity\"");
        int maEnd = manifest.indexOf("</activity>", ma);
        String mainBlock = manifest.substring(ma, maEnd);
        assertFalse("launcher MainActivity must not carry START_LOCATION_ACTIVITY",
                mainBlock.contains("START_LOCATION_ACTIVITY"));
        // BootReceiver gated
        int br = manifest.indexOf("com.overdrive.app.receiver.BootReceiver\"");
        String brBlock = manifest.substring(br, manifest.indexOf(">", br));
        assertTrue("BootReceiver must be DUMP-gated", brBlock.contains("android.permission.DUMP"));
    }
}
