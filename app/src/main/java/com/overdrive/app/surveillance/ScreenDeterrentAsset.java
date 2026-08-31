package com.overdrive.app.surveillance;

import java.io.File;
import java.util.Locale;
import com.overdrive.app.util.ScratchPaths;

/** Shared trust boundary for uploaded screen-deterrent assets. */
public final class ScreenDeterrentAsset {

    public static final String DIRECTORY = ScratchPaths.path(".overdrive");
    public static final String PREFIX = "screen_deterrent_asset.";

    private ScreenDeterrentAsset() {}

    public static boolean isAllowedPath(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File file = new File(path).getCanonicalFile();
            File parent = file.getParentFile();
            File expected = new File(DIRECTORY).getCanonicalFile();
            String fileName = file.getName();
            String name = fileName.toLowerCase(Locale.ROOT);
            return parent != null
                    && parent.equals(expected)
                    && fileName.startsWith(PREFIX)
                    && (name.endsWith(".png")
                        || name.endsWith(".jpg")
                        || name.endsWith(".jpeg")
                        || name.endsWith(".webp")
                        || name.endsWith(".gif")
                        || name.endsWith(".mp4"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
