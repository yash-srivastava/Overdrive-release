package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ParkedLinkWiringTest {
    @Test
    public void exportedReceiverRetainsTheShellPermissionBoundary() throws Exception {
        Path manifest = source("app/src/main/AndroidManifest.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList receivers = factory.newDocumentBuilder().parse(manifest.toFile())
                .getElementsByTagName("receiver");
        String android = "http://schemas.android.com/apk/res/android";
        Element adapter = null;
        for (int i = 0; i < receivers.getLength(); ++i) {
            Element element = (Element) receivers.item(i);
            if ("com.overdrive.app.power.ParkedLinkReceiver"
                    .equals(element.getAttributeNS(android, "name"))) adapter = element;
        }
        assertNotNull(adapter);
        assertEquals("true", adapter.getAttributeNS(android, "exported"));
        assertEquals("true", adapter.getAttributeNS(android, "enabled"));
        assertEquals("android.permission.DUMP", adapter.getAttributeNS(android, "permission"));
    }

    @Test
    public void daemonRetainsTheOrderedBroadcastAndGenerationFence() throws Exception {
        String daemon = new String(Files.readAllBytes(source(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java")),
                StandardCharsets.UTF_8);
        int start = daemon.indexOf("public int writeParkedLink(");
        int end = daemon.indexOf("public int detachParkedLink(", start);
        assertTrue(start >= 0 && end > start);
        String assertion = daemon.substring(start, end);
        assertTrue(assertion.contains("isSentryTransitionCurrent(generation, true)"));
        assertTrue(assertion.contains("RESULT_STALE_GENERATION"));
        assertTrue(daemon.contains("am broadcast -W --receiver-foreground"));
        assertTrue(daemon.contains("--include-stopped-packages"));
        assertTrue(daemon.contains("ParkedLinkContract.ACTION"));
        assertTrue(daemon.contains("execShellResult(shellCommand, 10_000L, null)"));
        assertTrue(assertion.contains("tryQnxVehicleNetworkHold(generation, start)"));
        assertTrue(daemon.contains("QnxMessageBus.get()"));
        assertTrue(daemon.contains("CarSvcTelemetry.INSTANCE.batteryVoltage12v()"));
    }

    private static Path source(String relative) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path file = current.resolve(relative);
            if (Files.isRegularFile(file)) return file;
            current = current.getParent();
        }
        throw new AssertionError("Missing " + relative);
    }
}
