package com.overdrive.app.ui.fragment;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class BridgeDownloadFileTest {
    private static final long CAP = 32L * 1024 * 1024;

    @Test
    public void shortReadsProduceIdenticalBytesAndReplaceExistingFile() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.csv");
            Files.write(target.toPath(), new byte[]{42});
            byte[] expected = new byte[100_003];
            new Random(42).nextBytes(expected);
            InputStream shortReads = new ByteArrayInputStream(expected) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    return super.read(b, off, Math.min(len, 17));
                }
            };
            assertTrue(BridgeDownloadFile.write(shortReads, target, CAP));
            assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
            assertOnlyTarget(dir, target);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void acceptsExactLimitWithBoundedReadBuffer() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.gpx");
            assertTrue(BridgeDownloadFile.write(generated(CAP), target, CAP));
            assertEquals(CAP, target.length());
            assertOnlyTarget(dir, target);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void oversizedUnknownLengthPreservesExistingExport() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.kml");
            byte[] old = {1, 2, 3};
            Files.write(target.toPath(), old);
            assertFalse(BridgeDownloadFile.write(generated(CAP + 1), target, CAP));
            assertArrayEquals(old, Files.readAllBytes(target.toPath()));
            assertOnlyTarget(dir, target);
            assertTrue(target.delete());
            assertFalse(BridgeDownloadFile.write(generated(1025), target, 1024));
            assertFalse(target.exists());
            assertEquals(0, dir.list().length);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void readFailurePreservesExistingExportAndRemovesTemporaryFile() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.csv");
            byte[] old = {4, 5, 6};
            Files.write(target.toPath(), old);
            InputStream broken = new InputStream() {
                private boolean first = true;
                @Override public int read() throws IOException { throw new IOException("broken"); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (!first) throw new IOException("broken");
                    first = false;
                    Arrays.fill(b, off, off + len, (byte) 7);
                    return len;
                }
            };
            try {
                BridgeDownloadFile.write(broken, target, CAP);
                fail("Read failure must propagate");
            } catch (IOException expected) {
                assertEquals("broken", expected.getMessage());
            }
            assertArrayEquals(old, Files.readAllBytes(target.toPath()));
            assertOnlyTarget(dir, target);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void failedRenameLeavesDestinationAndCleansTemporaryFile() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.csv");
            assertTrue(target.mkdir());
            File child = new File(target, "existing");
            Files.write(child.toPath(), new byte[]{9});
            try {
                assertFalse(BridgeDownloadFile.write(generated(100), target, CAP));
            } catch (IOException expected) {
                // Atomic move reports a conflicting directory as an I/O failure.
            }
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(child.toPath()));
            assertOnlyTarget(dir, target);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void newExportRemovesOnlyOldReservedRegularParts() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.csv");
            File stale = File.createTempFile(".od-export-", ".part", dir);
            File fresh = File.createTempFile(".od-export-", ".part", dir);
            File future = File.createTempFile(".od-export-", ".part", dir);
            File completed = new File(dir, "previous.gpx");
            File unrelated = new File(dir, ".other-123.part");
            File wrongSuffix = new File(dir, ".od-export-123.txt");
            File emptyName = new File(dir, ".od-export-.part");
            File matchingDirectory = new File(dir, ".od-export-directory.part");
            assertTrue(matchingDirectory.mkdir());
            FileTime old = oldPartTime();
            for (File file : new File[]{stale, completed, unrelated, wrongSuffix, emptyName}) {
                Files.write(file.toPath(), new byte[]{7});
                Files.setLastModifiedTime(file.toPath(), old);
            }
            Files.setLastModifiedTime(matchingDirectory.toPath(), old);
            Files.setLastModifiedTime(future.toPath(),
                    FileTime.fromMillis(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)));

            assertTrue(BridgeDownloadFile.write(generated(1), target, CAP));
            assertFalse(stale.exists());
            for (File file : new File[]{fresh, future, completed, unrelated, wrongSuffix, emptyName}) {
                assertTrue(file.getName(), file.isFile());
            }
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(completed.toPath()));
            assertTrue(matchingDirectory.isDirectory());
            assertEquals(1L, target.length());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void sweepDoesNotFollowSymbolicLinks() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File completed = new File(dir, "previous.csv");
            Files.write(completed.toPath(), new byte[]{7});
            Files.setLastModifiedTime(completed.toPath(), oldPartTime());
            File link = new File(dir, ".od-export-link.part");
            try {
                Files.createSymbolicLink(link.toPath(), completed.toPath());
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                org.junit.Assume.assumeNoException(unavailable);
            }

            assertTrue(BridgeDownloadFile.write(generated(1), new File(dir, "trip.csv"), CAP));
            assertTrue(Files.isSymbolicLink(link.toPath()));
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(completed.toPath()));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void overlappingWritesUseSeparateTemporaryFiles() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.csv");
            Files.write(target.toPath(), new byte[]{1});
            InputStream outer = new InputStream() {
                private int reads;
                @Override public int read() { throw new AssertionError("Expected bulk read"); }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (reads++ == 0) {
                        b[off] = 2;
                        return 1;
                    }
                    if (reads == 2) {
                        assertArrayEquals(new byte[]{1}, Files.readAllBytes(target.toPath()));
                        File[] parts = dir.listFiles((d, name) -> name.startsWith(".od-export-"));
                        assertNotNull(parts);
                        assertEquals(1, parts.length);
                        Files.setLastModifiedTime(parts[0].toPath(), oldPartTime());
                        assertTrue(BridgeDownloadFile.write(
                                new ByteArrayInputStream(new byte[]{3, 4}), target, CAP));
                        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(target.toPath()));
                        assertTrue("Active part must survive the sweep", parts[0].isFile());
                        b[off] = 5;
                        return 1;
                    }
                    return -1;
                }
            };
            assertTrue(BridgeDownloadFile.write(outer, target, CAP));
            assertArrayEquals(new byte[]{2, 5}, Files.readAllBytes(target.toPath()));
            assertOnlyTarget(dir, target);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void emptyExportIsAccepted() throws Exception {
        File dir = Files.createTempDirectory("bridge-download").toFile();
        try {
            File target = new File(dir, "trip.csv");
            assertTrue(BridgeDownloadFile.write(generated(0), target, CAP));
            assertEquals(0L, target.length());
            assertOnlyTarget(dir, target);
        } finally {
            deleteTree(dir);
        }
    }

    private static FileTime oldPartTime() {
        return FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));
    }

    private static InputStream generated(long size) {
        return new InputStream() {
            private long remaining = size;
            @Override public int read() {
                if (remaining == 0) return -1;
                remaining--;
                return 0;
            }
            @Override public int read(byte[] b, int off, int len) {
                assertTrue("read buffer exceeds 16 KiB", b.length <= 16 * 1024);
                if (remaining == 0) return -1;
                int n = (int) Math.min(remaining, len);
                Arrays.fill(b, off, off + n, (byte) 0);
                remaining -= n;
                return n;
            }
        };
    }

    private static void assertOnlyTarget(File dir, File target) {
        assertArrayEquals(new String[]{target.getName()}, dir.list());
    }

    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        Files.deleteIfExists(file.toPath());
    }
}
