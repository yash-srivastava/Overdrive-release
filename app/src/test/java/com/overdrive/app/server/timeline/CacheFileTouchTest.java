package com.overdrive.app.server.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Tests the disk-cache mtime rate limit without starting the camera daemon. */
public class CacheFileTouchTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void recentlyUsedFilesKeepTheirMtime() throws Exception {
        File cached = folder.newFile("frame.jpg");
        long now = System.currentTimeMillis();
        assertTrue(cached.setLastModified(now - 5_000));
        long original = cached.lastModified();

        CacheFileTouch.onHit(cached, now);

        assertEquals(original, cached.lastModified());
    }

    @Test
    public void staleFilesAreRefreshedOnTheNextHit() throws Exception {
        File cached = folder.newFile("frame.jpg");
        long now = System.currentTimeMillis();
        assertTrue(cached.setLastModified(now - 2 * CacheFileTouch.MIN_TOUCH_INTERVAL_MS));
        long original = cached.lastModified();

        CacheFileTouch.onHit(cached, now);

        assertTrue(cached.lastModified() > original);
        assertFalse(CacheFileTouch.shouldTouch(now, cached.lastModified()));
    }

    @Test
    public void boundaryUnknownAndFutureTimestamps() {
        long now = 1_000_000L;
        long interval = CacheFileTouch.MIN_TOUCH_INTERVAL_MS;
        assertFalse(CacheFileTouch.shouldTouch(now, now));
        assertFalse(CacheFileTouch.shouldTouch(now, now - interval + 1));
        assertTrue(CacheFileTouch.shouldTouch(now, now - interval));
        assertTrue(CacheFileTouch.shouldTouch(now, 0L));
        assertFalse(CacheFileTouch.shouldTouch(now, now + interval));
    }
}
