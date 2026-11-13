package com.overdrive.app.storage;

import java.io.File;
import java.io.IOException;

/**
 * Fail-closed boundaries for the destructive OEM dashcam SD-card cleaner.
 * Recognition is intentionally narrower than generic Android media folders.
 * All comparisons use canonical paths and a directory separator boundary.
 */
public final class CdrCleanupSafety {
    static final String[] RECORDER_SUBDIRS = {
            "Recorder/Normal", "Recorder/Video", "Recorder",
            "DVR", "CDR", "行车记录仪", "Video/DVR", "Movies/DVR",
            "CarRecord", "DashCam"
    };

    private CdrCleanupSafety() {}

    /**
     * True only for a dedicated recorder directory physically inside this SD
     * root. A symlink at a recognized name to another folder does not count.
     */
    public static boolean isTrustedRecorderRoot(File sdRoot, File recorder) {
        if (sdRoot == null || recorder == null || !sdRoot.isDirectory()
                || !recorder.isDirectory()) return false;
        try {
            String sdCanonical = sdRoot.getCanonicalPath();
            String recorderCanonical = recorder.getCanonicalPath();
            if (!isChildPath(sdCanonical, recorderCanonical)) return false;
            for (String relative : RECORDER_SUBDIRS) {
                // Use the canonical SD root plus the *literal* known subpath;
                // canonicalizing the expected subpath would trust a symlink
                // named CDR that actually redirects into DCIM or elsewhere.
                if (recorderCanonical.equals(sdCanonical + File.separator
                        + relative.replace('/', File.separatorChar))) {
                    return true;
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Storage may be absent, inaccessible, or mid-unmount.
        }
        return false;
    }

    /** A directory or file must resolve to the root or a descendant of it. */
    public static boolean isSameOrWithinRoot(File root, File candidate)
            throws IOException {
        if (root == null || candidate == null) return false;
        String base = root.getCanonicalPath();
        String path = candidate.getCanonicalPath();
        return base.equals(path) || isChildPath(base, path);
    }

    /** Recheck a regular file immediately before deletion. */
    public static boolean isRegularFileInRecorder(File recorderRoot, File file) {
        if (recorderRoot == null || file == null || !file.isFile()) return false;
        try {
            return isChildPath(recorderRoot.getCanonicalPath(), file.getCanonicalPath());
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean isChildPath(String root, String path) {
        return path.startsWith(root.endsWith(File.separator)
                ? root : root + File.separator)
                && !path.equals(root);
    }
}
