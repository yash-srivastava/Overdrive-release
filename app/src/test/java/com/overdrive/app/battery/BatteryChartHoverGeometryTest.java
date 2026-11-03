package com.overdrive.app.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Pins the render/hover geometry contract for the canvas charts on the
 * performance page. The hover handler maps touch/mouse x-positions to data
 * indices with hard-coded padding and its own choice of data array; the render
 * functions draw with theirs. Whenever the two disagree the crosshair lands on
 * the wrong point — the 12V voltage chart bug was hover indexing the full
 * unfiltered history with 40/10 padding while render drew a
 * batteryTimeRange-filtered array with 50/20 padding, so the tooltip tracked a
 * point up to hours away from the finger.
 */
public class BatteryChartHoverGeometryTest {

    @Test
    public void performanceScriptShipsOnce() throws Exception {
        // Source consumers keep the documented compatibility symlink. The
        // packaging task excludes that alias, not its canonical target.
        Path root = repositoryRoot();
        Path shared = root.resolve("app/src/main/assets/web/shared");
        Path alias = root.resolve("app/src/main/assets/web/local/shared");
        assertTrue(Files.isSymbolicLink(alias));
        assertEquals(Paths.get("../shared"), Files.readSymbolicLink(alias));
        assertTrue(Files.isSameFile(shared, alias));
        assertTrue(readRepositoryFile("app/src/main/assets/web/shared/performance.js").length() > 0);
        String packagedDirectory = System.getProperty("overdrive.packagedMainAssetsDir");
        assertNotNull("Run through Gradle so the asset producer is staged", packagedDirectory);
        Path packaged = Paths.get(packagedDirectory);
        assertFalse(Files.exists(packaged.resolve("web/local/shared"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(Files.readAllBytes(shared.resolve("performance.js")),
                Files.readAllBytes(packaged.resolve("web/shared/performance.js")));
    }

    @Test
    public void voltageAndThermalHoverShareTheRenderFilterAndPadding() throws Exception {
        String js = readRepositoryFile("app/src/main/assets/web/shared/performance.js");

        // Hover filters the same time window the render draws...
        assertTrue(js.contains("p.t >= Date.now() - this.batteryTimeRange * 3600 * 1000"));
        assertTrue(js.contains("p.t >= Date.now() - this.healthTimeRange * 3600 * 1000"));
        // ...and maps x with the render padding (left:50/right:20).
        assertTrue(js.contains("vPadLeft = 50, vPadRight = 20"));
        assertTrue(js.contains("tPadLeft = 50, tPadRight = 20"));
        // The renders still use that padding; if these are ever retuned the
        // hover constants above must follow.
        assertTrue(js.contains("padding = { top: 20, right: 20, bottom: 30, left: 50 }"));
        // The old bug: hover indexing the raw unfiltered arrays.
        assertFalse(js.contains("history = d.voltageHistory;"));
        assertFalse(js.contains("history = d.thermalHistory;"));
    }

    @Test
    public void socHoverUsesTheSocRenderPadding() throws Exception {
        String js = readRepositoryFile("app/src/main/assets/web/shared/performance.js");

        assertTrue(js.contains("socPadLeft = 45, socPadRight = 15"));
        assertTrue(js.contains("padding = { top: 15, right: 15, bottom: 30, left: 45 }"));
    }

    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("app/build.gradle.kts"))) return current;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository root");
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
