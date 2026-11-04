package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression test for run-2 finding
 * {@code exported-mainactivity-start-location-covert-gps}: the location-start
 * entrypoints must be gated (DUMP permission + calling-UID check) so any app
 * cannot silently start 1 Hz GPS collection.
 */
public class ExportedLocationComponentsContractTest {

    @Test
    public void locationComponentsRequireDumpPermission() throws Exception {
        String m = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        int act = m.indexOf("com.overdrive.app.ui.LocationStarterActivity");
        int svc = m.indexOf("com.overdrive.app.services.LocationSidecarService");
        assertTrue(act > 0 && svc > 0);
        String actBlock = m.substring(act, m.indexOf("</activity>", act));
        String svcBlock = m.substring(svc, m.indexOf("/>", svc));
        assertTrue("LocationStarterActivity must require DUMP",
                actBlock.contains("android:permission=\"android.permission.DUMP\""));
        assertTrue("LocationSidecarService must require DUMP",
                svcBlock.contains("android:permission=\"android.permission.DUMP\""));
    }

    @Test
    public void locationStartActionsCheckCallingUid() throws Exception {
        String main = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/ui/MainActivity.kt")), StandardCharsets.UTF_8);
        String starter = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/ui/LocationStarterActivity.kt")), StandardCharsets.UTF_8);
        assertTrue(main.contains("Binder.getCallingUid()") && main.contains("rejected START_LOCATION"));
        assertTrue(starter.contains("Binder.getCallingUid()") && starter.contains("rejected START_LOCATION_SILENT"));
    }
}
