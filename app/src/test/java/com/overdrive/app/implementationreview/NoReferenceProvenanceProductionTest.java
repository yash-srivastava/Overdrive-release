package com.overdrive.app.implementationreview;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

public class NoReferenceProvenanceProductionTest {
    private static final List<String> EXACT_PROHIBITED = Arrays.asList(
            "BYDMate", "DiLink5-ADB-ANAS", "com.anas", "AndyShaman", "PolyForm");
    private static final Pattern STANDALONE_ANAS = Pattern.compile(
            "(?<![A-Za-z0-9_])ANAS(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE);

    interface Reader {
        String read(Path path) throws IOException;
    }

    @Test
    public void productionAndUserFacingSourcesContainNoPrivateReferenceProvenance()
            throws Exception {
        Path root = repositoryRoot();
        Path production = root.resolve("app/src/main");
        assertTrue("production source root is missing", Files.isDirectory(production));
        scanTree(production, NoReferenceProvenanceProductionTest::readUtf8);
        for (String relative : Arrays.asList("README.md", "RELEASE_NOTES.md")) {
            Path path = root.resolve(relative);
            assertTrue("production-facing file is missing: " + relative, Files.isRegularFile(path));
            assertClean(path, readUtf8(path));
        }
    }

    @Test
    public void completeProhibitedListIsRejectedWithoutMatchingContainingWords() {
        for (String prohibited : Arrays.asList(
                "BYDMate", "ANAS", "DiLink5-ADB-ANAS", "com.anas", "AndyShaman", "PolyForm")) {
            boolean rejected = false;
            try {
                assertClean(Paths.get("production.txt"), "prefix " + prohibited + " suffix");
            } catch (AssertionError expected) {
                rejected = true;
            }
            assertTrue("expected prohibited provenance to be rejected", rejected);
        }
        assertClean(Paths.get("production.txt"), "bananas Anastasia analysis");
    }

    @Test
    public void unreadableProductionFileFailsTheScan() throws Exception {
        Path root = Files.createTempDirectory("provenance-scan");
        Path file = Files.write(root.resolve("production.txt"), new byte[] {1});
        try {
            scanTree(root, path -> {
                if (path.equals(file)) throw new IOException("unreadable");
                return readUtf8(path);
            });
            fail("expected unreadable production file to fail the scan");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unreadable"));
        }
    }

    static void scanTree(Path root, Reader reader) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                assertClean(path, reader.read(path));
            }
        }
    }

    static void assertClean(Path path, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String prohibited : EXACT_PROHIBITED) {
            assertFalse(path + " contains private reference provenance",
                    lower.contains(prohibited.toLowerCase(Locale.ROOT)));
        }
        assertFalse(path + " contains private reference provenance",
                STANDALONE_ANAS.matcher(text).find());
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("app/src/main"))) return current;
            current = current.getParent();
        }
        throw new AssertionError("repository root not found");
    }
}
