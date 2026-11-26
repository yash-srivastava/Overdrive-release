package com.overdrive.app.genai;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Shared JSON store reader; callers retain their own file-size policy and fallback. */
final class GenAiStoreReader {
    private GenAiStoreReader() {}

    static JSONObject read(File file, int maxBytes) {
        if (!file.isFile() || file.length() <= 0
                || file.length() > maxBytes) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream(
                     (int) file.length())) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) out.write(buffer, 0, count);
            }
            return new JSONObject(new String(
                    out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }
}
