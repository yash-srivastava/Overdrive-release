package com.overdrive.app.ui.fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Bounded streaming writer for the native WebView download bridge. */
final class BridgeDownloadFile {
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final String PART_PREFIX = ".od-export-";
    private static final String PART_SUFFIX = ".part";
    private static final long STALE_PART_AGE_MS = TimeUnit.HOURS.toMillis(24);

    // Also guards the sweep and creation/registration of new parts. Never held during I/O
    // streaming, so overlapping exports can still make progress independently.
    private static final Set<Path> ACTIVE_PARTS = new HashSet<>();

    private BridgeDownloadFile() {}

    /**
     * Writes to a unique temporary file in the destination directory and publishes it only
     * after EOF, cap validation and sync. The caller owns the input stream. On failure the
     * previous destination is not deleted, and the temporary file is removed if possible.
     */
    static boolean write(InputStream input, File target, long cap) throws IOException {
        File tmp = createPart(target.getAbsoluteFile().getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long total = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (n > cap - total) return false;
                    out.write(buffer, 0, n);
                    total += n;
                }
                out.getFD().sync();
            }
            // Never delete an existing completed export to force a retry. Some Android
            // filesystems reject ATOMIC_MOVE but still support a same-directory rename.
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (AtomicMoveNotSupportedException unsupported) {
                return tmp.renameTo(target);
            }
        } finally {
            try {
                if (tmp.exists()) tmp.delete();
            } finally {
                synchronized (ACTIVE_PARTS) {
                    ACTIVE_PARTS.remove(tmp.toPath());
                }
            }
        }
    }

    private static File createPart(File dir) throws IOException {
        // Canonicalize the directory so aliases share the same active-file identity.
        File canonicalDir = dir.getCanonicalFile();
        synchronized (ACTIVE_PARTS) {
            removeStaleParts(canonicalDir.toPath());
            File tmp = File.createTempFile(PART_PREFIX, PART_SUFFIX, canonicalDir);
            ACTIVE_PARTS.add(tmp.toPath());
            return tmp;
        }
    }

    /** Called under ACTIVE_PARTS before creating a new part; cleanup is best effort. */
    private static void removeStaleParts(Path dir) {
        long cutoff = System.currentTimeMillis() - STALE_PART_AGE_MS;
        // The bridge strips leading dots from completed export names. Only its reserved
        // staging namespace is eligible, and never recurse or follow symbolic links.
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(
                dir, PART_PREFIX + "?*" + PART_SUFFIX)) {
            for (Path part : parts) {
                if (ACTIVE_PARTS.contains(part)) continue;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(
                            part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isRegularFile() && attrs.lastModifiedTime().toMillis() <= cutoff) {
                        Files.deleteIfExists(part);
                    }
                } catch (IOException | SecurityException ignored) {
                    // A missing or inaccessible part must not block the new export.
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException ignored) {
            // Public storage may not allow enumeration; still attempt the requested write.
        }
    }
}
