package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side i18n message catalog.
 *
 * Loads JSON catalogs lazily per locale from /data/local/tmp/web/server-i18n/&lt;lang&gt;.json,
 * falls back to en for missing keys, and {0}/{1} interpolates positional args.
 *
 * Catalogs are keyed by the same dotted-path scheme as the web-side runtime so
 * both layers stay aligned (e.g. errors.bydcloud_not_configured).
 */
public final class Messages {

    private static final Map<String, JSONObject> CATALOGS = new HashMap<>();
    private static final String DIR = "/data/local/tmp/web/server-i18n";

    private Messages() {}

    public static String get(String key) { return get(key, (Object[]) null); }

    public static String get(String key, Object... args) {
        String lang = LocaleManager.get();
        String raw = lookup(lang, key);
        if (raw == null && !lang.equals("en")) raw = lookup("en", key);
        if (raw == null) return key; // dev-visible miss
        if (args == null || args.length == 0) return raw;
        try {
            return MessageFormat.format(raw, args);
        } catch (Exception e) {
            return raw;
        }
    }

    private static synchronized String lookup(String lang, String key) {
        JSONObject cat = CATALOGS.get(lang);
        if (cat == null) {
            cat = load(lang);
            if (cat != null) CATALOGS.put(lang, cat);
        }
        if (cat == null) return null;
        // Walk dotted path: "errors.bydcloud_not_configured"
        String[] parts = key.split("\\.");
        Object cur = cat;
        for (String p : parts) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(p);
            if (cur == null) return null;
        }
        return cur instanceof String ? (String) cur : null;
    }

    private static JSONObject load(String lang) {
        try {
            File f = new File(DIR + "/" + lang + ".json");
            if (!f.exists() || !f.canRead()) return null;
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int read = 0;
                while (read < buf.length) {
                    int n = fis.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(buf, 0, read, "UTF-8"));
            }
        } catch (Exception e) {
            CameraDaemon.log("Messages.load(" + lang + "): " + e.getMessage());
            return null;
        }
    }

    /** Hot-reload for the picker switch. */
    public static synchronized void invalidate() { CATALOGS.clear(); }
}
