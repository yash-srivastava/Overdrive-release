package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * T6 (F4): the Proximity page's master line must not read "Monitoring approved phones…" while the
 * app-process phone collector is not running. With approved phones configured and
 * {@code runtime.active === false} the page shows the collector reason text (the existing
 * {@code waiting_for_collector} / {@code collector_stale} texts) even when the keyfob rail is
 * active. The script version is bumped so cached pages pick the change up.
 */
public class RemoteAccessCollectorStateAssetTest {
    private static final String SCRIPT = "app/src/main/assets/web/shared/remote-access.js";
    private static final String PAGE = "app/src/main/assets/web/local/remote-access.html";

    @Test
    public void masterDetailShowsTheCollectorStateInsteadOfMonitoringApprovedPhones() throws IOException {
        String script = readRepositoryFile(SCRIPT);
        int master = script.indexOf("text('raMasterDetail', !state.enabled ?");
        assertTrue(master > 0);
        String detail = script.substring(master, script.indexOf("text('raRadioNote',", master));
        String collectorBranch =
                "phonesActive && !runtime.active ? tr('enabled_detail', 'Enabled · {detail}', { detail: reasonText(runtime.reason || 'waiting_for_collector') }) :";
        String monitoringBranch = "runtime.active || fob.active ? supports('keyFob') && state.config && state.config.keyFobEnabled === true ?";
        int collector = detail.indexOf(collectorBranch);
        int monitoring = detail.indexOf(monitoringBranch);
        // The collector branch is evaluated before any "Monitoring approved phones" text can win,
        // and after the master-off texts (which are unchanged).
        assertTrue(collector > 0 && monitoring > collector);
        assertTrue(detail.indexOf("'Proximity actions and sampling are stopped. The shared parked Bluetooth setting remains independent.') :") < collector);
        assertEquals(1, count(detail, "Monitoring approved phones and keyfob events."));
        assertEquals(1, count(detail, "'Monitoring approved phones. Automatic actions require confirmed vehicle power off.'"));
        // The existing inactive text and the keyfob-only / paused texts are untouched.
        assertTrue(detail.contains("fobOnly ? tr('enabled_detail', 'Enabled · {detail}', { detail: fob.detail }) :"));
        assertTrue(detail.contains("bluetoothPaused ? tr('phone_proximity_bluetooth_off', 'Parked Bluetooth is disabled. Phone proximity actions are paused.') :"));
        assertTrue(detail.contains("tr('enabled_detail', 'Enabled · {detail}', { detail: reasonText(runtime.reason || signal.reason) }));"));
        // The reason texts the branch relies on exist in reasonText(), and the daemon emits them.
        assertTrue(script.contains("waiting_for_collector: 'Waiting for the car app to start monitoring',"));
        assertTrue(script.contains("collector_stale: 'Updates from the car app stopped · automatic actions disarmed',"));
        String handler = readRepositoryFile("app/src/main/java/com/overdrive/app/server/RemoteAccessApiHandler.java");
        assertTrue(handler.contains(": latestObservation < 0 ? \"waiting_for_collector\" : \"collector_stale\");"));
        // phonesActive already requires the shared switch to be on, so the paused text keeps priority.
        assertTrue(script.contains("function hasActivePhones(phones, keepBluetoothOn) {\n"
                + "        return keepBluetoothOn === true && supports('bluetooth') &&"));
        assertTrue(script.contains("var phonesActive = state.config && hasActivePhones(profilesFromConfig(state.config), state.config.keepBluetoothOn);"));
        // The alias save itself is unchanged on the page: a standalone POST, never part of Save.
        assertTrue(script.contains("api('/config', { keepBluetoothOn: requested })"));
        assertFalse(script.contains("keepBluetoothOn: el('raKeepBluetoothOn').checked,"));
    }

    @Test
    public void pageLoadsTheBumpedScriptVersion() throws IOException {
        String html = readRepositoryFile(PAGE);
        // 18 Oct snappy-proximity run bumped the script (new Advanced fields and the sent outcome label);
        // 19 Oct bumped it again for the keyfob unlock-trigger switch; 20 Oct for the snappy-locks controls.
        assertTrue(html.contains("<script src=\"../shared/remote-access.js?v=28\"></script>"));
        assertFalse(html.contains("remote-access.js?v=23"));
        assertFalse(html.contains("remote-access.js?v=24"));
        assertFalse(html.contains("remote-access.js?v=25"));
        assertFalse(html.contains("remote-access.js?v=27")); // 2 Nov: Hebrew i18n wiring
        assertEquals(1, count(html, "remote-access.js?v="));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
