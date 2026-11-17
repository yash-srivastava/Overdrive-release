package com.overdrive.app.monitor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.overdrive.app.daemon.CameraDaemon;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only snapshot of Bluetooth connection state for the automation Bluetooth
 * trigger. Mirrors {@link NetworkMonitor}'s "Android API first, shell fallback"
 * resilience so it works both when the daemon holds a usable app context and when
 * it is running as UID 2000 where the framework proxies may be unavailable.
 *
 * <p>"Connected" here means an <b>audio/HID</b> profile (A2DP / Headset) is
 * connected — i.e. the phone the driver actually paired, not merely a bonded-but-
 * absent device. The connected device's friendly name feeds the "connect to
 * &lt;name&gt;" style condition, exactly like {@link NetworkMonitor}'s SSID.
 *
 * <p>All reads are best-effort: any failure leaves the last-known values untouched
 * and never throws, so the poll loop that drives it (BluetoothEvent) is never
 * disrupted.
 */
public final class BluetoothMonitor {

    // Whether an audio/HID profile is currently connected, and the connected
    // device's friendly name ("" when nothing is connected). volatile: written by
    // the poll thread, read by the automation-state publisher on the same thread
    // today but kept volatile for safety if that ever changes.
    private static volatile boolean connected = false;
    private static volatile String deviceName = "";
    private static volatile boolean shellFallbackLogged = false;

    private BluetoothMonitor() {}

    public static boolean isConnected() { return connected; }

    /** The connected device's friendly name, or "" when nothing is connected. */
    public static String getDeviceName() { return deviceName == null ? "" : deviceName; }

    /**
     * Refresh the cached state. Tries the Android BluetoothAdapter profile proxies
     * first, falling back to a shell {@code dumpsys} parse when they are unavailable
     * (common under UID 2000). Never throws.
     */
    public static void refresh() {
        if (tryAndroidApis()) return;
        tryShellFallback();
    }

    // ==================== ANDROID API APPROACH ====================

    private static boolean tryAndroidApis() {
        try {
            Context ctx = CameraDaemon.getAppContext();
            BluetoothAdapter adapter = null;
            if (ctx != null) {
                BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null) adapter = bm.getAdapter();
            }
            if (adapter == null) adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                // Adapter off (or unreachable) → definitively disconnected. This IS a
                // valid Android-API result, so return true (don't fall through to shell).
                connected = false;
                deviceName = "";
                return true;
            }

            // getProfileConnectionState() is the PUBLIC synchronous adapter-level read
            // (no async profile-proxy bind needed). getConnectionState() is hidden/
            // non-SDK and does not compile on this target, so probe the audio/HID
            // profiles (A2DP / Headset) — "connected" for our purposes means the phone
            // the driver actually paired for audio, matching this class's doc.
            int a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP);
            int headset = adapter.getProfileConnectionState(BluetoothProfile.HEADSET);
            boolean anyConnected = a2dp == BluetoothProfile.STATE_CONNECTED
                    || headset == BluetoothProfile.STATE_CONNECTED;
            if (!anyConnected) {
                connected = false;
                deviceName = "";
                return true;
            }

            String name = firstConnectedName(adapter);
            if (name != null) {
                connected = true;
                deviceName = name;
                return true;
            }
            // Adapter says connected but no profile name resolvable synchronously —
            // report connected with an empty name rather than a false "off".
            connected = true;
            deviceName = "";
            return true;
        } catch (Throwable t) {
            // SecurityException (missing perm under UID 2000) or any framework NPE:
            // let the shell fallback try.
            return false;
        }
    }

    /**
     * Resolve the friendly name of a device currently connected on the common audio/
     * HID profiles by cross-referencing the adapter's bonded set against
     * {@code getConnectionState}. Uses only the synchronous adapter APIs (no async
     * {@code getProfileProxy}) so this returns within the poll tick. Returns null if
     * no connected bonded device can be identified.
     */
    private static String firstConnectedName(BluetoothAdapter adapter) {
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return null;
            for (BluetoothDevice d : bonded) {
                try {
                    // isConnected() is a hidden method on BluetoothDevice; probe by
                    // reflection so we don't hard-depend on a non-SDK API. When
                    // present it tells us which bonded device is actually connected.
                    Object r = BluetoothDevice.class.getMethod("isConnected").invoke(d);
                    if (Boolean.TRUE.equals(r)) {
                        String n = d.getName();
                        if (n != null && !n.isEmpty()) return n;
                    }
                } catch (Throwable ignored) {
                    // isConnected() unavailable — fall through to the null return so
                    // the caller reports connected-with-empty-name.
                }
            }
        } catch (Throwable ignored) {
            // getBondedDevices may throw SecurityException under UID 2000.
        }
        return null;
    }

    // ==================== SHELL FALLBACK ====================

    /**
     * Parse {@code dumpsys bluetooth_manager} for the enabled state and any connected
     * device. This is the UID-2000 path where the framework proxies are unavailable.
     * The dump lists connected devices as "XX:XX:XX:XX:XX:XX Name" lines under a
     * "Connected" / "mConnectionState: STATE_CONNECTED" section; we extract the first
     * such name. Best-effort: leaves state untouched on any failure.
     */
    private static void tryShellFallback() {
        try {
            String dump = exec("dumpsys bluetooth_manager");
            if (dump == null || dump.isEmpty()) {
                if (!shellFallbackLogged) {
                    CameraDaemon.log("BluetoothMonitor: shell fallback produced no output");
                    shellFallbackLogged = true;
                }
                return;
            }
            // "enabled: true" / "state: ON" indicates the radio is up. If clearly off,
            // report disconnected.
            String lower = dump.toLowerCase();
            boolean radioOff = lower.contains("enabled: false")
                    || lower.contains("state: off")
                    || lower.contains("state: turning_off");
            if (radioOff) {
                connected = false;
                deviceName = "";
                return;
            }
            String name = parseConnectedNameFromDump(dump);
            if (name != null) {
                connected = true;
                deviceName = name;
            } else {
                // Radio on but no connected device found in the dump.
                connected = false;
                deviceName = "";
            }
        } catch (Throwable t) {
            // Never disrupt the caller.
        }
    }

    // A "AA:BB:CC:DD:EE:FF Friendly Name" line — capture the trailing name.
    private static final Pattern BT_DEVICE_LINE =
            Pattern.compile("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\s+(.+?)\\s*$");

    /**
     * Extract the first connected device's name from a bluetooth_manager dump. Scans
     * for a "connected" context and returns the first MAC-prefixed device name near
     * it. Conservative: returns null unless a device line is found in a connected
     * section, so a mere bonded-but-absent device never reads as connected.
     */
    private static String parseConnectedNameFromDump(String dump) {
        String[] lines = dump.split("\n");
        boolean inConnected = false;
        for (String line : lines) {
            String l = line.trim();
            String ll = l.toLowerCase();
            // Enter a "connected devices" region on the common section headers.
            // CRITICAL: "disconnected".contains("connected") is TRUE, so a naive
            // contains("connected") check would treat a "Disconnected devices:" header
            // (and the bonded-but-absent devices under it) as connected → false-positive
            // connection. Strip any "disconnected"/"unconnected" occurrences first, then
            // test, so only a genuine "connected" header opens the region.
            String stripped = ll.replace("disconnected", "").replace("unconnected", "");
            if (stripped.contains("connected")
                    && (stripped.contains("device") || stripped.endsWith("connected:") || stripped.contains("state_connected"))) {
                inConnected = true;
            } else if (ll.contains("disconnected") || ll.contains("unconnected")) {
                // An explicit disconnected/unbonded section header ends any connected
                // region we were in, so its device lines are never mis-read as connected.
                inConnected = false;
            }
            if (inConnected) {
                Matcher m = BT_DEVICE_LINE.matcher(l);
                if (m.find()) {
                    String name = m.group(1).trim();
                    // Skip lines that are just addresses / bookkeeping.
                    if (!name.isEmpty() && !name.matches("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) {
                        return name;
                    }
                }
                // A blank line ends the connected section.
                if (l.isEmpty()) inConnected = false;
            }
        }
        return null;
    }

    /**
     * Share the network monitor's bounded shell runner instead of creating a
     * reader thread on every Bluetooth poll. A failed or timed-out dumpsys must
     * not publish a partial snapshot as a disconnected device.
     */
    private static String exec(String cmd) {
        BoundedShell.Result result = BoundedShell.run(cmd, 4_000L);
        return result.ok() ? result.output : null;
    }
}
