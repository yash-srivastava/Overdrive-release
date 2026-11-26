package com.overdrive.app.server;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AssetCatalogCacheTest {
    private static AssetCatalogCache.Opener bytes(byte[] data) {
        return () -> new ByteArrayInputStream(data);
    }

    private static AssetCatalogCache.Opener unexpected() {
        return () -> { throw new AssertionError("Unexpected asset read"); };
    }

    @Test
    public void hitReusesBytesAndValidatorUntilAssetOwnerChanges() throws Exception {
        AssetCatalogCache cache = new AssetCatalogCache();
        Object first = new Object();
        byte[] a = "one".getBytes(StandardCharsets.UTF_8);
        byte[] b = "two".getBytes(StandardCharsets.UTF_8);
        AssetCatalogCache.Entry entry = cache.get(first, "en", bytes(a));
        assertSame(entry, cache.get(first, "en", unexpected()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        entry.writeTo(out);
        assertArrayEquals(a, out.toByteArray());
        assertEquals(a.length, entry.length());
        AssetCatalogCache.Entry updated = cache.get(new Object(), "en", bytes(b));
        assertNotEquals(entry.etag, updated.etag);
        out.reset();
        updated.writeTo(out);
        assertArrayEquals(b, out.toByteArray());
    }

    @Test
    public void byteBudgetEvictsLeastRecentlyUsedAndOversizedEntriesAreNotCached() throws Exception {
        AssetCatalogCache cache = new AssetCatalogCache();
        Object owner = new Object();
        byte[] half = new byte[AssetCatalogCache.MAX_BYTES / 2];
        AssetCatalogCache.Entry a = cache.get(owner, "a", bytes(half));
        AssetCatalogCache.Entry b = cache.get(owner, "b", bytes(half));
        assertSame(a, cache.get(owner, "a", unexpected()));
        AssetCatalogCache.Entry c = cache.get(owner, "c", bytes(half));
        assertSame(a, cache.get(owner, "a", unexpected()));
        assertSame(c, cache.get(owner, "c", unexpected()));
        byte[] oversized = new byte[AssetCatalogCache.MAX_BYTES + 1];
        AssetCatalogCache.Entry big = cache.get(owner, "large", bytes(oversized));
        assertEquals(oversized.length, big.length());
        assertNotSame(big, cache.get(owner, "large", bytes(oversized)));
        assertSame(a, cache.get(owner, "a", unexpected()));
        assertNotSame(b, cache.get(owner, "b", bytes(half)));
    }

    @Test
    public void entryBudgetAlsoAppliesToSmallCatalogs() throws Exception {
        AssetCatalogCache cache = new AssetCatalogCache();
        Object owner = new Object();
        byte[] tiny = {1};
        AssetCatalogCache.Entry first = cache.get(owner, "first", bytes(tiny));
        for (int i = 0; i < 4; i++) cache.get(owner, "other" + i, bytes(tiny));
        assertNotSame(first, cache.get(owner, "first", bytes(tiny)));
    }

    @Test
    public void failedReadsAreNotCached() throws Exception {
        AssetCatalogCache cache = new AssetCatalogCache();
        Object owner = new Object();
        try {
            cache.get(owner, "en", () -> new java.io.InputStream() {
                @Override public int read() throws IOException { throw new IOException("read failed"); }
            });
            fail("Expected read failure");
        } catch (IOException expected) {
            assertEquals("read failed", expected.getMessage());
        }
        AssetCatalogCache.Entry entry = cache.get(owner, "en", bytes(new byte[]{1, 2}));
        assertSame(entry, cache.get(owner, "en", unexpected()));
    }

    @Test
    public void concurrentRequestsOnlyOpenAssetOnce() throws Exception {
        AssetCatalogCache cache = new AssetCatalogCache();
        Object owner = new Object();
        AtomicInteger opens = new AtomicInteger();
        AssetCatalogCache.Opener opener = () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(new byte[8192]);
        };
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<AssetCatalogCache.Entry>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 32; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get(owner, "en", opener);
                }));
            }
            start.countDown();
            AssetCatalogCache.Entry first = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<AssetCatalogCache.Entry> future : futures) {
                assertSame(first, future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, opens.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void validatorMatchesOriginalFormatterExactly() throws Exception {
        Random random = new Random(21);
        for (int i = 0; i < 1000; i++) {
            byte[] data = new byte[random.nextInt(4096)];
            random.nextBytes(data);
            StringBuilder expected = new StringBuilder("\"");
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
                expected.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            assertEquals(expected.append('"').toString(), AssetCatalogCache.contentEtag(data));
        }
    }
}
