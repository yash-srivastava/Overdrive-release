package com.overdrive.app.ui.fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Bounded streaming writer for the native WebView download bridge. */
final class BridgeDownloadFile {
    private static final int BUFFER_SIZE = 16 * 1024;

    private BridgeDownloadFile() {}

    /**
     * Writes to a unique temporary file in the destination directory and publishes it only
     * after EOF, cap validation and sync. The caller owns the input stream. On failure the
     * previous destination is not deleted, and the temporary file is removed if possible.
     */
    static boolean write(InputStream input, File target, long cap) throws IOException {
        File tmp = File.createTempFile(".od-export-", ".part", target.getParentFile());
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
            if (tmp.exists()) tmp.delete();
        }
    }
}
