package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@code cmd wifi status} output shapes seen on Android 11, plus the ways they degrade. */
public class WifiStatusParserTest {

    private static final String INFO_PREFIX = "WifiInfo: SSID: ";
    private static final String INFO_MIDDLE =
            ", BSSID: aa:bb:cc:dd:ee:ff, MAC: 02:00:00:00:00:00, Supplicant state: COMPLETED, "
            + "Wi-Fi standard: 5, RSSI: -55, Link speed: 390Mbps, Tx Link speed: 390Mbps, "
            + "Max Supported Tx Link speed: 866Mbps, Rx Link speed: 390Mbps, "
            + "Max Supported Rx Link speed: 866Mbps, Frequency: 5180MHz, Net ID: 3, Metered hint: ";

    private static String connected(String quotedSsid, boolean metered) {
        return "Wifi is enabled\n"
                + "Wifi scanning is always available\n"
                + "Wifi is connected to " + quotedSsid + "\n"
                + INFO_PREFIX + quotedSsid + INFO_MIDDLE + metered + ", score: 60\n"
                + "NetworkCapabilities: [ Transports: WIFI Capabilities: NOT_METERED&INTERNET ]\n";
    }

    @Test
    public void ssidAndUnmeteredHint() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(connected("\"HomeNet\"", false));
        assertTrue(p.connected);
        assertEquals("HomeNet", p.ssid);
        assertEquals(Boolean.FALSE, p.meteredHint);
    }

    @Test
    public void ssidAndMeteredHint() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(connected("\"Pixel 7\"", true));
        assertTrue(p.connected);
        assertEquals("Pixel 7", p.ssid);
        assertEquals(Boolean.TRUE, p.meteredHint);
    }

    @Test
    public void redactedSsidIsUnknownButHintSurvives() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(connected("<unknown ssid>", true));
        assertTrue(p.connected);
        assertNull(p.ssid);
        assertEquals(Boolean.TRUE, p.meteredHint);
        assertNull(WifiStatusParser.parse(connected("<none>", false)).ssid);
        assertNull(WifiStatusParser.parse(connected("\"\"", false)).ssid);
    }

    @Test
    public void notConnectedAndDisabledAreUnknown() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(
                "Wifi is enabled\nWifi scanning is always available\nWifi is not connected\n");
        assertFalse(p.connected);
        assertNull(p.ssid);
        assertNull(p.meteredHint);
        WifiStatusParser.Parsed d = WifiStatusParser.parse(
                "Wifi is disabled\nWifi scanning is only available when wifi is enabled\n");
        assertFalse(d.connected);
        assertNull(d.ssid);
        assertNull(d.meteredHint);
    }

    @Test
    public void emptyAndGarbageAreUnknown() {
        for (String s : new String[]{null, "", "   \n", "Stub!", "Error: unknown command 'status'",
                "sh: cmd: inaccessible or not found", "SSID: \"x\" without the status lines"}) {
            WifiStatusParser.Parsed p = WifiStatusParser.parse(s);
            assertFalse(String.valueOf(s), p.connected);
            assertNull(String.valueOf(s), p.ssid);
            assertNull(String.valueOf(s), p.meteredHint);
        }
    }

    @Test
    public void ssidWithCommaAndQuotesIsTakenWhole() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(connected("\"Cafe, \"the\" one\"", false));
        assertEquals("Cafe, \"the\" one", p.ssid);
        assertEquals(Boolean.FALSE, p.meteredHint);
    }

    @Test
    public void wifiInfoLineAloneStillYieldsSsidAndHint() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(
                INFO_PREFIX + "\"Only, Info\"" + INFO_MIDDLE + "true, score: 10");
        assertTrue(p.connected);
        assertEquals("Only, Info", p.ssid);
        assertEquals(Boolean.TRUE, p.meteredHint);
    }

    @Test
    public void connectedLineWithoutWifiInfoLeavesHintUnknown() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(
                "Wifi is enabled\r\nWifi is connected to \"CRLF Net\"\r\n");
        assertTrue(p.connected);
        assertEquals("CRLF Net", p.ssid);
        assertNull(p.meteredHint);
    }

    @Test
    public void exactUnknownMarkersAreRedactedButLegitimateNamesArePreserved() {
        assertNull(WifiStatusParser.normalizeSsid("<unknown ssid>"));
        assertNull(WifiStatusParser.normalizeSsid("\"<UNKNOWN SSID>\""));
        assertNull(WifiStatusParser.normalizeSsid("<none>"));
        assertNull(WifiStatusParser.normalizeSsid("<None>"));
        assertNull(WifiStatusParser.normalizeSsid("unknown"));
        assertNull(WifiStatusParser.normalizeSsid("unknown ssid"));
        assertEquals("Unknown-Guest", WifiStatusParser.normalizeSsid("\"Unknown-Guest\""));
        assertEquals("My unknown network", WifiStatusParser.normalizeSsid("My unknown network"));
        assertEquals("Known-Guest", WifiStatusParser.normalizeSsid("\"Known-Guest\""));
        assertEquals("0a1b2c", WifiStatusParser.normalizeSsid("0a1b2c"));
    }

    @Test
    public void connectedUnknownGuestPreservesSsidAndMeteredHint() {
        WifiStatusParser.Parsed p = WifiStatusParser.parse(connected("\"Unknown-Guest\"", true));
        assertTrue(p.connected);
        assertEquals("Unknown-Guest", p.ssid);
        assertEquals(Boolean.TRUE, p.meteredHint);
    }
}
