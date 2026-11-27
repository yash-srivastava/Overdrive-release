package com.overdrive.app.monitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Small UTF-8 first-line reader for procfs/sysfs attributes (Android's default charset). */
final class KernelFirstLine {
    private static final int CHUNK_SIZE = 512;

    private KernelFirstLine() {}

    static String read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return read(input);
        }
    }

    // Never reopen a partially read attribute: some GPU counters reset on read.
    static String read(InputStream input) throws IOException {
        byte[] chunk = new byte[CHUNK_SIZE];
        ByteArrayOutputStream prefix = null;
        int count;
        while ((count = input.read(chunk)) != -1) {
            int end = 0;
            while (end < count && chunk[end] != '\n' && chunk[end] != '\r') end++;
            if (end < count) {
                if (prefix == null) return new String(chunk, 0, end, StandardCharsets.UTF_8);
                prefix.write(chunk, 0, end);
                break;
            }
            if (prefix == null) prefix = new ByteArrayOutputStream(CHUNK_SIZE);
            prefix.write(chunk, 0, count);
        }
        return prefix == null ? null : new String(prefix.toByteArray(), StandardCharsets.UTF_8);
    }
}
