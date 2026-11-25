package com.overdrive.app.services;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Source contracts for the daemon-started proximity keep-alive service. */
public class ProximityCollectorServiceContractTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void exportedServiceRequiresTheShellPermission() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        int service = manifest.indexOf(
                "android:name=\"com.overdrive.app.services.ProximityCollectorService\"");
        int end = manifest.indexOf("/>", service);
        assertTrue(service >= 0);
        assertTrue(end > service);
        String declaration = manifest.substring(service, end);
        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains("android:permission=\"android.permission.DUMP\""));
        assertTrue(declaration.contains("android:foregroundServiceType=\"dataSync\""));
    }

    @Test
    public void everyStartIsAnsweredWithStartForegroundBeforeAnyWork() throws Exception {
        String service = read("src/main/java/com/overdrive/app/services/ProximityCollectorService.kt");
        int onStart = service.indexOf("override fun onStartCommand(");
        int foreground = service.indexOf("startForegroundCompat()", onStart);
        int resume = service.indexOf("resumeCollectors()", onStart);
        assertTrue(onStart >= 0);
        assertTrue(foreground > onStart);
        assertTrue(resume > foreground);
        assertTrue(service.contains("worker.execute { resumeCollectors() }"));
        assertTrue(service.contains("return START_NOT_STICKY"));
    }

    @Test
    public void watchdogTargetsTheDeclaredComponentAsTheDriversUser() throws Exception {
        String watchdog = read(
                "src/main/java/com/overdrive/app/remoteaccess/ProximityCollectorWatchdog.java");
        assertTrue(watchdog.contains(
                "\"com.overdrive.app/com.overdrive.app.services.ProximityCollectorService\""));
        assertTrue(watchdog.contains("\"am start-foreground-service \" + com.overdrive.app.util.ShellUser.flag()"));
        assertTrue(watchdog.contains("\"am stopservice \" + com.overdrive.app.util.ShellUser.flag()"));
        assertTrue(read("src/main/java/com/overdrive/app/daemon/CameraDaemon.java")
                .contains("com.overdrive.app.remoteaccess.ProximityCollectorWatchdog.attach();"));
    }
}
