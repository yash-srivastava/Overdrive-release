package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Every reworked native page reports through the shared toast stack, so a user sees the same
 * chip on Dashboard, Recordings and Projection as the WebView pages give them.
 */
public class AppToastConsistencyTest {

    private static final String[] PAGE_LAYOUTS = {
            "app/src/main/res/layout/fragment_dashboard.xml",
            "app/src/main/res/layout-land/fragment_dashboard.xml",
            "app/src/main/res/layout/fragment_recordings.xml",
            "app/src/main/res/layout-land/fragment_recordings.xml",
            "app/src/main/res/layout/fragment_projection.xml",
            "app/src/main/res/layout-land/fragment_projection.xml",
    };

    private static final String[] PAGE_FRAGMENTS = {
            "app/src/main/java/com/overdrive/app/ui/fragment/DashboardFragment.kt",
            "app/src/main/java/com/overdrive/app/ui/fragment/RecordingLibraryFragment.kt",
            "app/src/main/java/com/overdrive/app/ui/fragment/ProjectionFragment.kt",
    };

    @Test
    public void everyReworkedPageHostsTheToastStack() throws IOException {
        for (String path : PAGE_LAYOUTS) {
            assertTrue(path, readRepositoryFile(path).contains("layout=\"@layout/app_toast\""));
        }
    }

    @Test
    public void reworkedFragmentsUseTheStackInsteadOfPlatformToasts() throws IOException {
        for (String path : PAGE_FRAGMENTS) {
            String src = readRepositoryFile(path);
            assertTrue(path, src.contains("AppToast(view)"));
            assertTrue(path, src.contains("appToast?.cancel()"));
            assertFalse(path, src.contains("Toast.makeText"));
        }
    }

    /**
     * The recordings library is only ever embedded, and a fragment view is not in its container
     * yet during onViewCreated — so the stack has to be looked up per show, not cached at build.
     */
    @Test
    public void embeddedFragmentsResolveTheHostPageStack() throws IOException {
        String src = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/widget/AppToast.kt");
        assertTrue(src.contains("private fun stack()"));
        assertTrue(src.contains("host.rootView?.findViewById(R.id.appToastStack)"));
        assertTrue(src.contains("val stack = stack() ?: return"));
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
