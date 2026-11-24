package com.overdrive.app.server.timeline;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** Rate-limits filesystem mtime writes for the timeline JPEG disk cache. */
final class CacheFileTouch {
    static final long MIN_TOUCH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    private CacheFileTouch() {}

    static boolean shouldTouch(long nowMs, long modifiedMs) {
        return modifiedMs <= 0 || nowMs - modifiedMs >= MIN_TOUCH_INTERVAL_MS;
    }

    /** Keep approximate LRU order without rewriting file metadata on every cache hit. */
    static void onHit(File file, long nowMs) {
        // MediaStore cannot set mtimes, so a card file would pay the stat on every hit.
        if (RemovableStorageFs.isRouted(file)) return;
        if (shouldTouch(nowMs, RemovableStorageFs.lastModified(file))) {
            RemovableStorageFs.setLastModified(file, nowMs);
        }
    }
}
