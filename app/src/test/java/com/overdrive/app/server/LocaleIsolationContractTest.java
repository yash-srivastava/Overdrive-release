package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level guards for app-locale authority across native, daemon, and WebView code. */
public class LocaleIsolationContractTest {

    @Test
    public void daemonLocaleWritesInvalidateCachesAndPendingWritesReplayOffMainThread()
            throws Exception {
        String manager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/LocaleManager.java");
        String ipc = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        String application = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/OverdriveApplication.kt");

        assertTrue(manager.contains("public static void invalidateCaches()"));
        assertTrue(manager.contains("public static void replayPendingWriteAsync()"));
        assertTrue(manager.contains("pendingReplayRunning.compareAndSet(false, true)"));
        assertTrue(manager.contains("replay.setDaemon(true)"));
        assertTrue(manager.contains("if (!saved) replayPendingWriteAsync()"));
        assertTrue(manager.contains("clearPendingLocalWriteIfCurrent(tag)"));

        assertTrue(ipc.contains(
                "ok && \"nativeShell\".equals(s) && data.has(\"locale\")"));
        assertTrue(ipc.contains("LocaleManager.invalidateCaches()"));
        assertTrue(application.contains("LocaleManager.replayPendingWriteAsync()"));
    }

    @Test
    public void serverAndNativeLocaleUpdatesCannotEchoOrPublishStaleCatalogs()
            throws Exception {
        String core = readRepositoryFile(
                "app/src/main/assets/web/shared/core.js");
        String webView = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt");

        assertTrue(core.contains("function setLang(lang, options)"));
        assertTrue(core.contains("options.persist !== false"));
        assertTrue(core.contains("loadRevision"));
        assertTrue(core.contains("revision !== state.loadRevision"));
        assertTrue(core.contains("function shouldFollowServerLocale(serverLang)"));
        assertTrue(core.contains("window.AndroidBridge.getAppLocale()"));
        assertTrue(core.contains(
                "BYD.i18n.setLang(status.locale, { persist: false })"));
        assertTrue(webView.contains(
                "BYD.i18n.setLang('$safe', { persist: false });"));
    }

    @Test
    public void nativeSurfacesResolveStringsAgainstTheAppLocaleWithoutGlobalMutation()
            throws Exception {
        String roadSense = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/roadsense/overlay/"
                        + "RoadSenseOverlayService.kt");
        String settings = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/SettingsFragment.kt");
        String appearance = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/settings/"
                        + "SettingsAppearanceFragment.kt");

        String languageLabel = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/util/SettingsLanguageLabel.kt");

        assertTrue(roadSense.contains(
                "private fun localizedString(resId: Int, vararg formatArgs: Any)"));
        assertTrue(roadSense.contains(
                "private fun severityLabel(sev: Int): String = localizedString("));
        assertFalse(roadSense.contains("LocaleList.setDefault("));

        for (String screen : new String[] {settings, appearance}) {
            assertTrue(screen.contains("import com.overdrive.app.ui.util.currentLocaleDisplay"));
            assertTrue(screen.contains("tvValue.text = currentLocaleDisplay()"));
            assertFalse(screen.contains("private fun currentLocaleDisplay("));
            assertFalse(screen.contains("getDisplayName(Locale.getDefault())"));
            assertFalse(screen.contains("LocaleList.setDefault("));
        }
        assertTrue(languageLabel.contains("internal fun Fragment.currentLocaleDisplay()"));
        assertTrue(languageLabel.contains("AppCompatDelegate.getApplicationLocales()"));
        assertTrue(languageLabel.contains(
                "val displayLocale = resources.configuration.locales[0]"));
        assertTrue(languageLabel.contains("getDisplayName(displayLocale)"));
        assertTrue(languageLabel.contains("it.titlecase(displayLocale)"));
        assertFalse(languageLabel.contains("Locale.getDefault()"));
        assertFalse(languageLabel.contains("LocaleList.setDefault("));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
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
