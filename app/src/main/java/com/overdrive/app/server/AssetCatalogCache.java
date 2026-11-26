package com.overdrive.app.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;

/** Small, server-local cache of immutable APK translation catalogs and their validators. */
final class AssetCatalogCache {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 4;
    private static final String HEX = "0123456789abcdef";

    interface Opener {
        InputStream open() throws IOException;
    }

    static final class Entry {
        private final byte[] data;
        final String etag;

        private Entry(byte[] data) {
            this.data = data;
            this.etag = contentEtag(data);
        }

        int length() { return data.length; }

        void writeTo(OutputStream out) throws IOException {
            out.write(data);
        }
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(4, 0.75f, true);
    private Object owner;
    private int retainedBytes;

    /**
     * The owner is the AssetManager for the loaded APK, not the requested language or
     * extracted file. A new manager invalidates every cached catalog. Serialize misses
     * to avoid duplicate decompression on simultaneous requests; no socket writes here.
     */
    synchronized Entry get(Object assetOwner, String language, Opener opener) throws IOException {
        if (owner != assetOwner) {
            entries.clear();
            retainedBytes = 0;
            owner = assetOwner;
        }
        Entry hit = entries.get(language);
        if (hit != null) return hit;

        Entry loaded;
        try (InputStream in = opener.open()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            loaded = new Entry(bytes.toByteArray());
        }
        // An oversized catalog is still served, but never retained by this cache.
        if (loaded.length() > MAX_BYTES) return loaded;
        Iterator<Entry> oldest = entries.values().iterator();
        while (retainedBytes > MAX_BYTES - loaded.length() || entries.size() >= MAX_ENTRIES) {
            retainedBytes -= oldest.next().length();
            oldest.remove();
        }
        entries.put(language, loaded);
        retainedBytes += loaded.length();
        return loaded;
    }

    static String contentEtag(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            char[] text = new char[digest.length * 2 + 2];
            text[0] = '"';
            text[text.length - 1] = '"';
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                text[2 * i + 1] = HEX.charAt(value >>> 4);
                text[2 * i + 2] = HEX.charAt(value & 15);
            }
            return new String(text);
        } catch (Exception impossible) {
            return "\"" + Integer.toHexString(java.util.Arrays.hashCode(data)) + "\"";
        }
    }
}
