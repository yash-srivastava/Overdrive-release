package com.overdrive.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class StorageManagerMp4ListingTest {

    @Test
    public void missingRootIsUnavailableAndIncomplete() throws Exception {
        StorageManager manager = allocateWithoutConstructor();
        File missing = new File(Files.createTempDirectory("missing-root").toFile(), "gone");

        StorageManager.Mp4Listing listing = manager.listMp4FilesWithStatus(missing);

        assertFalse(listing.available);
        assertFalse(listing.complete);
        assertEquals(0, listing.files.length);
    }

    @Test
    public void directListingIsAvailableCompleteAndFiltered() throws Exception {
        StorageManager manager = allocateWithoutConstructor();
        Path directory = Files.createTempDirectory("complete-root");
        Files.write(directory.resolve("cam.mp4"), "video".getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("cam.json"), "{}".getBytes(StandardCharsets.UTF_8));

        StorageManager.Mp4Listing listing =
                manager.listMp4FilesWithStatus(directory.toFile());

        assertTrue(listing.available);
        assertTrue(listing.complete);
        assertEquals(1, listing.files.length);
        assertEquals("cam.mp4", listing.files[0].getName());
    }

    @Test
    public void stuckDirectListingReturnsAtDeadline() {
        File blocking = new File("/blocking") {
            private final CountDownLatch never = new CountDownLatch(1);

            @Override
            public File[] listFiles(java.io.FileFilter filter) {
                try {
                    never.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
        };
        long started = System.nanoTime();

        StorageManager.FileListResult result = StorageManager.listFilesDirectWithStatus(
                blocking, file -> true, 25L);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertFalse(result.complete);
        assertEquals(0, result.files.length);
        assertTrue("bounded listing took " + elapsedMs + "ms", elapsedMs < 1_000L);
    }

    private static StorageManager allocateWithoutConstructor() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        return (StorageManager) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, StorageManager.class);
    }
}