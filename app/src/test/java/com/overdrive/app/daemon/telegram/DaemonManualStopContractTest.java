package com.overdrive.app.daemon.telegram;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class DaemonManualStopContractTest {

    @Test
    public void telegramStopIsIdempotentAndWritesTheSharedSentinel() {
        FakeContext context = new FakeContext();

        new DaemonCommandHandler().handle(
                1L, new String[]{"/daemon", "sentry", "stop"}, context);

        assertTrue(context.commands.stream().anyMatch(command ->
                command.contains("disabled by telegram")
                        && command.contains("sentry_daemon.disabled")));
        assertTrue(context.commands.stream()
                .filter(command -> command.contains("sentry_daemon"))
                .filter(command -> command.contains("ps -A"))
                .allMatch(command -> command.contains("grep -v acc_sentry_daemon")));
        assertTrue(context.commands.stream().anyMatch(command ->
                command.contains("sentry_daemon.pid")
                        && command.contains("127.0.0.1 19879")));
        assertTrue(context.messages.contains("tr:daemon.stopped"));
    }

    @Test
    public void telegramSingboxStopMatchesUiCleanup() {
        FakeContext context = new FakeContext();

        new DaemonCommandHandler().handle(
                1L, new String[]{"/daemon", "singbox", "stop"}, context);

        assertTrue(context.commands.stream().anyMatch(command ->
                command.contains("sentry_proxy")
                        && command.contains("global_http_proxy_host")
                        && command.contains("global_http_proxy_port")));
        assertTrue(context.commands.stream().anyMatch(command ->
                command.contains("disabled by telegram")
                        && command.contains("singbox.disabled")));
        assertTrue(context.messages.contains("tr:daemon.stopped"));
    }

    @Test
    public void automaticStartupNeverClearsManualStopIntent() throws IOException {
        String startup = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");
        String hotspot = read(
                "app/src/main/java/com/overdrive/app/network/HotspotManager.kt");

        assertTrue(startup.contains("'disabled by ui'*|'disabled by telegram'*"));
        assertTrue(startup.contains(
                "vm.startDaemon(DaemonType.CAMERA_DAEMON, userInitiated = false)"));
        assertTrue(startup.contains(
                "vm.startDaemon(DaemonType.SENTRY_DAEMON, userInitiated = false)"));
        assertTrue(startup.contains(
                "vm.startDaemon(DaemonType.ACC_SENTRY_DAEMON, userInitiated = false)"));
        assertTrue(count(startup,
                "ifNotUserStopped(DaemonType.CAMERA_DAEMON)") >= 2);
        assertTrue(count(startup,
                "ifNotUserStopped(DaemonType.SENTRY_DAEMON)") >= 2);
        assertTrue(count(startup,
                "ifNotUserStopped(DaemonType.ACC_SENTRY_DAEMON)") >= 2);
        assertTrue(startup.contains("leaving it stopped this tick"));

        int clear = startup.indexOf("fun clearStaleSentinels()");
        int next = startup.indexOf("fun stopHealthCheckThread()", clear);
        assertTrue(clear >= 0 && next > clear);
        assertFalse(startup.substring(clear, next).contains("rm -f"));
        assertTrue(count(hotspot,
                "ScratchPaths.path(\"singbox.disabled\")") >= 2);
    }

    @Test
    public void tunnelSwitchAndMachineTransitionsPreserveManualIntent() throws IOException {
        String handler = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        String system = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/SystemCommandHandler.java");
        String surveillance = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/SurveillanceCommandHandler.java");
        String lifecycle = read(
                "app/src/main/java/com/overdrive/app/updater/UpdateLifecycle.java");
        String updater = read(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt");
        String acc = read(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        assertTrue(handler.contains("stopDaemon(\"zrok\", ctx);"));
        assertTrue(handler.contains("stopDaemon(\"cloudflared\", ctx);"));
        assertFalse(handler.contains("writeSentinel"));
        assertTrue(handler.contains("startDaemon(daemon[2], processName, ctx)"));
        assertFalse(handler.contains("className.toLowerCase()"));
        assertTrue(handler.contains("|| \"tailscaled\".equals(processName)"));
        assertTrue(system.contains(
                "DaemonCommandHandler.processMatcher(processName)"));
        assertTrue(surveillance.contains(
                "DaemonCommandHandler.processMatcher(processName)"));
        assertTrue(handler.indexOf("stopDaemon(\"zrok\", ctx);")
                < handler.indexOf("if (isRunning)"));
        assertTrue(lifecycle.contains(
                "ScratchPaths.path(\"camera_daemon.disabled\")"));
        assertTrue(updater.contains(
                "ScratchPaths.path(\"acc_sentry_daemon.disabled\")"));
        assertTrue(updater.contains(
                "'disabled by stopAllDaemons sweep'*|"));
        assertTrue(launcher.contains(
                "ScratchPaths.path(\"acc_sentry_daemon.disabled\")"));
        int privilegedKill = launcher.indexOf(
                "private fun killDaemonViaPrivilegedShell");
        int adbKill = launcher.indexOf(
                "private fun killDaemonViaAdb", privilegedKill);
        int bothKill = launcher.indexOf(
                "private fun killDaemonViaBothShells", adbKill);
        assertTrue(privilegedKill >= 0 && adbKill > privilegedKill
                && bothKill > adbKill);
        assertFalse(launcher.substring(privilegedKill, adbKill)
                .contains("disabled by ui"));
        assertFalse(launcher.substring(bothKill)
                .contains("disabled by ui"));
        assertTrue(acc.contains(
                "'disabled by ui'*|'disabled by telegram'*|'disabled by user'*"));
    }

    @Test
    public void telegramZrokStartRequiresAHealthyShareAndReusesTheWatchdog()
            throws IOException {
        String handler = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");

        assertTrue(handler.contains(
                "if (\"zrok\".equals(processName)) return isZrokShareRunning(ctx);"));
        assertTrue(handler.contains("isZrokWatchdogRunning(ctx)"));
        assertTrue(handler.contains(
                "Zrok watchdog is already recovering; not launching a duplicate"));
        assertTrue(handler.contains("waitForHealthyZrok"));
        assertTrue(handler.contains("ZrokRuntimeProbe.probeStatus(url)"));
        assertTrue(handler.contains("ZrokRuntimeProbe.isHealthyStatus(status)"));
        assertFalse(handler.contains("Don't return false - zrok might still be starting"));

        assertTrue(DaemonCommandHandler.isLatestZrokAttemptRateLimited(
                "Starting zrok share...\nSERVER_TOO_MANY_REQUESTS"));
        assertTrue(DaemonCommandHandler.isLatestZrokAttemptRateLimited(
                "Starting zrok share...\nToo many requests to alter state"));
        assertFalse(DaemonCommandHandler.isLatestZrokAttemptRateLimited(
                "SERVER_TOO_MANY_REQUESTS\nStarting zrok share...\nconnected"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }

    private static int count(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static final class FakeContext implements CommandContext {
        final List<String> commands = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public String tr(String key, Object... args) {
            return "tr:" + key;
        }

        @Override
        public boolean sendMessage(long chatId, String text) {
            messages.add(text);
            return true;
        }

        @Override
        public boolean sendMessageWithButtons(
                long chatId, String text, String[][][] buttons) {
            return true;
        }

        @Override
        public boolean sendVideo(long chatId, String videoPath, String caption) {
            return true;
        }

        @Override
        public JSONObject sendIpcCommand(int port, JSONObject command) {
            return null;
        }

        @Override
        public String execShell(String command) {
            commands.add(command);
            return "";
        }

        @Override
        public void log(String message) {}
    }
}
