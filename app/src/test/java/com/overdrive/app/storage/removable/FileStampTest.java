package com.overdrive.app.storage.removable;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.assertEquals;
import static com.overdrive.app.storage.removable.RemovableStorageFs.FileMatch.*;

public class FileStampTest {
    @Test public void localFingerprintIsFreshAndDistinguishesGoneFromStale() throws Exception {
        Path dir = Files.createTempDirectory("file-stamp");
        Path path = dir.resolve("clip.mp4");
        File file = path.toFile();
        try {
            assertEquals(GONE, RemovableStorageFs.matchReadableFile(file, 0, 0));
            assertEquals(GONE, RemovableStorageFs.matchReadableFile(dir.toFile(), 0, 0));
            RemovableStorageFs.FileStamp missing = RemovableStorageFs.fileStamp(file);
            assertEquals(0L, missing.length);
            assertEquals(0L, missing.mtime);

            Files.write(path, new byte[]{1, 2, 3});
            Files.setLastModifiedTime(path, FileTime.fromMillis(10_000));
            RemovableStorageFs.FileStamp first = RemovableStorageFs.fileStamp(file);
            assertEquals(file.length(), first.length);
            assertEquals(file.lastModified(), first.mtime);
            assertEquals(MATCH, RemovableStorageFs.matchReadableFile(file, first.length, first.mtime));
            assertEquals(STALE, RemovableStorageFs.matchReadableFile(file, first.length + 1, first.mtime));
            assertEquals(STALE, RemovableStorageFs.matchReadableFile(file, first.length, first.mtime + 1));

            Files.write(path, new byte[]{1, 2, 3, 4});
            Files.setLastModifiedTime(path, FileTime.fromMillis(20_000));
            RemovableStorageFs.FileStamp second = RemovableStorageFs.fileStamp(file);
            assertEquals(4L, second.length);
            assertEquals(file.lastModified(), second.mtime);
            assertEquals(STALE, RemovableStorageFs.matchReadableFile(file, first.length, first.mtime));
            assertEquals(MATCH, RemovableStorageFs.matchReadableFile(file, second.length, second.mtime));
            assertEquals(3L, first.length);
            Files.delete(path);
            assertEquals(GONE, RemovableStorageFs.matchReadableFile(file, second.length, second.mtime));
        } finally {
            Files.deleteIfExists(path);
            Files.deleteIfExists(dir);
        }
    }

    @Test public void localValidationPreservesShortCircuitAndCacheDoesNotReadPermissions() {
        CountingFile file = new CountingFile();
        file.readable = false;
        assertEquals(GONE, RemovableStorageFs.matchReadableFile(file, 4, 10));
        assertEquals(0, file.sizeReads);
        assertEquals(0, file.timeReads);
        assertEquals(1, file.readabilityReads);
        assertEquals(1, file.typeReads);

        file.readable = true;
        assertEquals(STALE, RemovableStorageFs.matchReadableFile(file, 3, 10));
        assertEquals(1, file.sizeReads);
        assertEquals(0, file.timeReads);
        assertEquals(MATCH, RemovableStorageFs.matchReadableFile(file, 4, 10));
        assertEquals(2, file.sizeReads);
        assertEquals(1, file.timeReads);
        RemovableStorageFs.FileStamp stamp = RemovableStorageFs.fileStamp(file);
        assertEquals(4L, stamp.length);
        assertEquals(10L, stamp.mtime);
        assertEquals(3, file.sizeReads);
        assertEquals(2, file.timeReads);
        assertEquals(3, file.readabilityReads);
        assertEquals(3, file.typeReads);

        assertEquals(10L, RemovableStorageFs.readableLastModified(file));
        file.readable = false;
        assertEquals(0L, RemovableStorageFs.readableLastModified(file));
        assertEquals(3, file.timeReads);
        assertEquals(5, file.readabilityReads);
    }

    private static final class CountingFile extends File {
        boolean readable;
        int sizeReads, timeReads, readabilityReads, typeReads;

        CountingFile() { super("local-clip.mp4"); }
        @Override public boolean isFile() { typeReads++; return true; }
        @Override public boolean canRead() { readabilityReads++; return readable; }
        @Override public long length() { sizeReads++; return 4; }
        @Override public long lastModified() { timeReads++; return 10; }
    }
}
