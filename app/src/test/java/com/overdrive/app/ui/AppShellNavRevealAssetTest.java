package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Contracts for sidebar scroll behaviour that keeps About / update actions reachable. */
public class AppShellNavRevealAssetTest {

    @Test
    public void mobileSidebarRevealKeepsAboutActionsVisible() throws IOException {
        String shell = read("app/src/main/assets/web/shared/app-shell.js");
        assertTrue(shell.contains("function revealPersistentAboutActions(aside"));
        assertTrue(shell.contains("revealPersistentAboutActions(aside);"));
        assertTrue(shell.contains("#navUpdateLink") || shell.contains(".nav-link-update"));
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(repositoryPath(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.exists(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository path: " + relativePath);
    }
}
