package com.overdrive.app.monitor;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;

import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.regex.Pattern;

/**
 * Network Monitor - provides WiFi SSID, IP address, or Mobile Data status
 * for the HTTP status API sidebar display.
 *
 * Strategy:
 * 1. Try standard Android APIs (ConnectivityManager/WifiManager) first
 * 2. Fall back to ip addr for the link, and for the network name to a direct Wi-Fi service
 *    read ({@link WifiConnectionInfoReader}) ahead of bounded shell probes
 *
 * Every shell fallback runs through {@link BoundedShell} (3 s deadline, output cap, kill on
 * timeout), so no calling thread — HTTP handler, automation poller, SafeKeep — can block on
 * a wedged system service. The Wi-Fi service's dump path is never used: on some firmware
 * each dump forks log-capture children inside system_server that never exit, and they
 * accumulate for as long as the head unit stays awake. The SSID shell probe is additionally
 * rate-limited (see {@link #shellGetWifiSsid()}).
 *
 * Periodic consumers must read through {@link #getNetworkInfo()} (10 s cache) and never call
 * {@link #refresh()} on their own cadence: refresh() forks shell commands whenever the
 * Android APIs are unavailable (uid 2000) and is meant for change-triggered reads only.
 *
 * The shell fallback spawns {@code sh} directly (same pattern as AccSentryDaemon,
 * SentryDaemon, etc.) — NOT AdbShellExecutor which requires ADB over TCP.
 */
public class NetworkMonitor {

    /**
     * Placeholder published as the SSID while the unit is on Wi-Fi but the network name is
     * hidden or unreadable. Shared contract: NetworkEvent publishes it unchanged into the
     * automation state and {@code Policy.ssidKnown} treats it (together with "" and any
     * value containing "unknown") as "name not known". There is no second placeholder.
     */
    public static final String SSID_UNKNOWN_PLACEHOLDER = "WiFi";

    /** Per-command deadline for every shell fallback in this class. */
    static final long SHELL_DEADLINE_MS = BoundedShell.DEFAULT_DEADLINE_MS;
    /** Minimum spacing between SSID shell probes for an unchanged interface/address. */
    static final long SHELL_SSID_MIN_INTERVAL_MS = 10_000L;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private static volatile String networkType = "none";
    private static volatile String wifiSsid = "";
    private static volatile boolean wifiMeteredHint;
    private static volatile boolean wifiMeteredHintKnown;
    private static volatile String ipAddress = "";
    private static volatile int signalPercent = -1;
    private static volatile long lastUpdate = 0;
    private static volatile Context appContext;
    private static volatile boolean shellFallbackLogged = false;
    private static volatile boolean wifiServiceReadLogged = false;

    // Rate-limit state of the SSID shell probe; see shellGetWifiSsid().
    private static volatile long lastShellSsidAttemptAtElapsed = -1L;
    private static volatile String lastShellSsidKey = "";
    private static volatile String lastShellSsid = null;
    private static volatile boolean lastShellMeteredHint;
    private static volatile boolean lastShellMeteredHintKnown;
    private static volatile Integer lastShellRssi = null;

    public static void init(Context context) {
        appContext = context;
        CameraDaemon.log("NetworkMonitor: init with context=" +
                (context != null ? context.getClass().getSimpleName() : "null"));
        refresh();
    }

    /**
     * Refresh network state. Tries Android APIs first, falls back to shell.
     *
     * <p>Change-triggered reads only (a new address, an explicit user action). Anything that
     * runs on a timer must go through {@link #getNetworkInfo()}, which refreshes at most once
     * per 10 s. Bounded: every shell command returns within {@link #SHELL_DEADLINE_MS}.
     */
    public static void refresh() {
        // Try Android APIs first
        if (appContext != null && tryAndroidApis()) {
            return;
        }
        // Fallback: shell commands
        tryShellFallback();
    }

    // ==================== ANDROID API APPROACH ====================

    private static boolean tryAndroidApis() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                CameraDaemon.log("NetworkMonitor: ConnectivityManager is null");
                return false;
            }

            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) {
                // Silent fallback — common when running as UID 2000 (shell daemon)
                return false;
            }

            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) {
                return false;
            }

            // Get IP from LinkProperties
            String ip = "";
            try {
                LinkProperties lp = cm.getLinkProperties(activeNetwork);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            ip = addr.getHostAddress();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // LinkProperties may fail under UID 2000 — not critical, IP from shell
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                networkType = "wifi";
                ipAddress = ip;
                readWifiDetailsAndroid();
                lastUpdate = System.currentTimeMillis();
                return true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                networkType = "cellular";
                ipAddress = ip;
                clearWifiDetails();
                lastUpdate = System.currentTimeMillis();
                return true;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                networkType = "ethernet";
                ipAddress = ip;
                clearWifiDetails();
                lastUpdate = System.currentTimeMillis();
                return true;
            }

            return false;
        } catch (SecurityException e) {
            // UID 2000 (shell) cannot access package manager APIs on some devices.
            // "Package android does not belong to 2000" — expected on DiLink 5.
            // Silent fallback to shell commands which work fine under UID 2000.
            if (!shellFallbackLogged) {
                CameraDaemon.log("NetworkMonitor: Android APIs unavailable (UID 2000), using shell fallback");
                shellFallbackLogged = true;
            }
            return false;
        } catch (Exception e) {
            // Other unexpected errors — log once then go silent
            if (!shellFallbackLogged) {
                CameraDaemon.log("NetworkMonitor: Android API error: " + e.getMessage() + " — using shell fallback");
                shellFallbackLogged = true;
            }
            return false;
        }
    }

    private static void readWifiDetailsAndroid() {
        wifiMeteredHint = false; // only the shell probe below can read it
        wifiMeteredHintKnown = false;
        try {
            WifiManager wm = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                CameraDaemon.log("NetworkMonitor: WifiManager is null");
                wifiSsid = SSID_UNKNOWN_PLACEHOLDER;
                return;
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                wifiSsid = SSID_UNKNOWN_PLACEHOLDER;
                return;
            }
            // Same exact-marker normalization as the shell and Binder paths.
            // SSIDs are arbitrary user strings; "Unknown-Guest" is a valid name.
            String ssid = WifiStatusParser.normalizeSsid(info.getSSID());
            if (ssid == null) {
                // SSID hidden by Android — try shell fallback for SSID only
                String shellSsid = shellGetWifiSsid();
                wifiSsid = (shellSsid != null && !shellSsid.isEmpty()) ? shellSsid : SSID_UNKNOWN_PLACEHOLDER;
            } else {
                wifiSsid = ssid;
            }
            int rssi = info.getRssi();
            signalPercent = Math.max(0, Math.min(100, (rssi + 90) * 100 / 60));
        } catch (Exception e) {
            CameraDaemon.log("NetworkMonitor: WifiInfo error: " + e.getMessage());
            wifiSsid = SSID_UNKNOWN_PLACEHOLDER;
        }
    }

    /** Off Wi-Fi: no name, no signal, and the metered hint is not known. */
    private static void clearWifiDetails() {
        wifiSsid = "";
        signalPercent = -1;
        wifiMeteredHint = false;
        wifiMeteredHintKnown = false;
    }

    // ==================== SHELL FALLBACK ====================

    private static void tryShellFallback() {
        try {
            // One bounded shell invocation replaces up to six independent
            // `ip addr show <iface>` subprocesses on API-restricted head units.
            // Parse the same interface priority order as before.
            java.util.Map<String, String> addresses = shellInterfaceIpv4Snapshot();
            String wlanIp = addresses.get("wlan0");
            if (wlanIp != null) {
                networkType = "wifi";
                ipAddress = wlanIp;
                String ssid = shellGetWifiSsid();
                wifiSsid = (ssid != null && !ssid.isEmpty()) ? ssid : SSID_UNKNOWN_PLACEHOLDER;
                shellGetWifiSignal();
                lastUpdate = System.currentTimeMillis();
                return;
            }

            String[] rmnetIfaces = {"rmnet_data0", "rmnet_data1", "rmnet_data2", "rmnet_data3"};
            for (String iface : rmnetIfaces) {
                String rmnetIp = addresses.get(iface);
                if (rmnetIp != null) {
                    networkType = "cellular";
                    ipAddress = rmnetIp;
                    clearWifiDetails();
                    lastUpdate = System.currentTimeMillis();
                    return;
                }
            }

            String ethIp = addresses.get("eth0");
            if (ethIp != null) {
                networkType = "ethernet";
                ipAddress = ethIp;
                clearWifiDetails();
                lastUpdate = System.currentTimeMillis();
                return;
            }

            // No network
            networkType = "none";
            ipAddress = "";
            clearWifiDetails();
            lastUpdate = System.currentTimeMillis();
        } catch (Exception e) {
            CameraDaemon.log("NetworkMonitor: shell fallback error: " + e.getMessage());
        }
    }

    /**
     * Capture interface IPv4 addresses in one bounded subprocess. Android's
     * `ip addr show` prints an interface header followed by indented `inet`
     * entries; preserve the first valid IPv4 address for each interface.
     */
    private static java.util.Map<String, String> shellInterfaceIpv4Snapshot() {
        String output = BoundedShell.run("ip addr show", SHELL_DEADLINE_MS).output;
        java.util.Map<String, String> addresses = new java.util.HashMap<>();
        String currentIface = null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                // Header format: `2: wlan0: <BROADCAST,...>`.
                int firstColon = line.indexOf(':');
                int secondColon = firstColon < 0 ? -1 : line.indexOf(':', firstColon + 1);
                currentIface = null;
                if (secondColon > firstColon) {
                    String iface = line.substring(firstColon + 1, secondColon).trim();
                    // Strip optional @peer suffix from virtual interfaces.
                    int peer = iface.indexOf('@');
                    currentIface = peer >= 0 ? iface.substring(0, peer) : iface;
                }
            } else if (currentIface != null && trimmed.startsWith("inet ")
                    && !addresses.containsKey(currentIface)) {
                String addressWithMask = trimmed.substring(5).split("\\s+", 2)[0];
                int slash = addressWithMask.indexOf('/');
                String address = slash >= 0
                        ? addressWithMask.substring(0, slash) : addressWithMask;
                if (IPV4.matcher(address).matches()) {
                    addresses.put(currentIface, address);
                }
            }
        }
        return addresses;
    }

    /**
     * SSID source for the hidden-SSID and uid-2000 cases.
     *
     * <p>Order: a direct Wi-Fi service read ({@link WifiConnectionInfoReader}, no fork); on
     * SDK 29 the connectivity service's network list; {@code cmd wifi status} (non-privileged
     * from Android 11, see {@link WifiStatusParser}); then {@code wpa_cli status}. Every shell
     * step runs through {@link BoundedShell}. The metered hint and RSSI come from the first
     * source that reports them.
     *
     * <p>Rate limit: attempts for an unchanged {@code type|address} key are at least
     * {@link #SHELL_SSID_MIN_INTERVAL_MS} apart; a changed key (new interface or address)
     * probes immediately. Between attempts the previous result is returned, metered
     * hint included, so callers never probe more than once per interval however often they
     * refresh. Returns null when no source produced a usable name.
     */
    private static String shellGetWifiSsid() {
        String key = networkType + "|" + ipAddress;
        long now = SystemClock.elapsedRealtime();
        boolean due = !key.equals(lastShellSsidKey)
                || lastShellSsidAttemptAtElapsed < 0L
                || now - lastShellSsidAttemptAtElapsed >= SHELL_SSID_MIN_INTERVAL_MS;
        if (!due) {
            wifiMeteredHint = lastShellMeteredHint;
            wifiMeteredHintKnown = lastShellMeteredHintKnown;
            return lastShellSsid;
        }
        lastShellSsidAttemptAtElapsed = now;
        lastShellSsidKey = key;

        String ssid = null;
        boolean metered = false;
        boolean meteredKnown = false;
        Integer rssi = null;
        WifiConnectionInfoReader.Info info = readWifiService();
        if (info != null) {
            ssid = info.ssid;
            rssi = info.rssi;
            if (info.meteredHint != null) {
                metered = info.meteredHint;
                meteredKnown = true;
            }
        }
        if (ssid == null && Build.VERSION.SDK_INT <= 29) {
            ssid = WifiConnectionInfoReader.ssidFromConnectivity(SHELL_DEADLINE_MS);
        }
        if (ssid == null) {
            WifiStatusParser.Parsed parsed = WifiStatusParser.parse(
                    BoundedShell.run("cmd wifi status", SHELL_DEADLINE_MS).output);
            if (!meteredKnown && parsed.meteredHint != null) {
                metered = parsed.meteredHint;
                meteredKnown = true;
            }
            ssid = parsed.ssid;
        }
        if (ssid == null) {
            // Fallback: wpa_cli (needs the control socket; often unavailable to uid 2000)
            String wpa = firstLine(BoundedShell.run(
                    "wpa_cli -i wlan0 status 2>/dev/null | grep '^ssid='", SHELL_DEADLINE_MS).output);
            if (wpa.startsWith("ssid=")) {
                ssid = WifiStatusParser.normalizeSsid(wpa.substring(5));
            }
        }
        lastShellSsid = ssid;
        lastShellRssi = rssi;
        lastShellMeteredHint = metered;
        lastShellMeteredHintKnown = meteredKnown;
        wifiMeteredHint = metered;
        wifiMeteredHintKnown = meteredKnown;
        return ssid;
    }

    /** Direct Wi-Fi service read; only the shell uid holds the grant it relies on. */
    private static WifiConnectionInfoReader.Info readWifiService() {
        if (android.os.Process.myUid() != 2000) {
            return null;
        }
        WifiConnectionInfoReader.Info info = WifiConnectionInfoReader.read(SHELL_DEADLINE_MS);
        if (info == null && !wifiServiceReadLogged) {
            wifiServiceReadLogged = true;
            CameraDaemon.log("NetworkMonitor: Wi-Fi service read unavailable ("
                    + WifiConnectionInfoReader.lastFailure() + "), using shell fallback");
        }
        return info;
    }

    /** RSSI from the last SSID probe when it had one, else {@code wpa_cli signal_poll}. */
    private static void shellGetWifiSignal() {
        Integer probed = lastShellRssi;
        if (probed != null) {
            signalPercent = Math.max(0, Math.min(100, (probed + 90) * 100 / 60));
            return;
        }
        String wpa = firstLine(BoundedShell.run(
                "wpa_cli -i wlan0 signal_poll 2>/dev/null | grep '^RSSI='", SHELL_DEADLINE_MS).output);
        if (wpa.startsWith("RSSI=")) {
            try {
                int rssi = Integer.parseInt(wpa.substring(5).trim());
                signalPercent = Math.max(0, Math.min(100, (rssi + 90) * 100 / 60));
                return;
            } catch (NumberFormatException ignored) {}
        }
        signalPercent = -1;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).trim();
    }

    // ==================== STATUS API ====================

    /**
     * Get network info as JSON for the /status endpoint.
     * Auto-refreshes if data is older than 10 seconds.
     *
     * <p>Fields: {@code type, ssid, ip, signal} (unchanged) plus the additive
     * {@code meteredHint} (the access point marked itself metered) and
     * {@code meteredHintKnown} (whether that flag could be read at all).
     */
    public static JSONObject getNetworkInfo() {
        if (System.currentTimeMillis() - lastUpdate > 10000) {
            refresh();
        }
        JSONObject net = new JSONObject();
        try {
            net.put("type", networkType);
            net.put("ssid", wifiSsid);
            net.put("ip", ipAddress);
            net.put("signal", signalPercent);
            net.put("meteredHint", wifiMeteredHint);
            net.put("meteredHintKnown", wifiMeteredHintKnown);
        } catch (Exception ignored) {}
        return net;
    }

    /**
     * Current network type ("wifi", "cellular", "ethernet", "none"). Reads the last
     * cached value without forcing a refresh. Callers must not call {@link #refresh()} on
     * a tick of their own: the cache-bounded entry point is {@link #getNetworkInfo()}
     * (refreshes at most once per 10 s); call that, then read the accessors.
     */
    public static String getNetworkType() { return networkType; }

    /** True when the last known network type is WiFi. */
    public static boolean isWifiConnected() { return "wifi".equals(networkType); }

    /**
     * Current WiFi SSID: "" when not on WiFi, {@link #SSID_UNKNOWN_PLACEHOLDER} when on WiFi
     * with an unreadable name.
     */
    public static String getWifiSsid() { return wifiSsid; }

    /**
     * The access point marked itself metered (phone hotspot); refreshed with the SSID.
     * Meaningful only while {@link #isWifiMeteredHintKnown()} is true.
     */
    public static boolean isWifiMeteredHint() { return wifiMeteredHint; }

    /** True when the last refresh could read the access point's metered flag at all. */
    public static boolean isWifiMeteredHintKnown() { return wifiMeteredHintKnown; }
}
