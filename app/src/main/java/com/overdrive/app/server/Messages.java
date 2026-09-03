package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.util.MessageFormatSafe;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Locale;
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
    private static String catalogDir() {
        return com.overdrive.app.util.ScratchPaths.path("web/server-i18n");
    }
    // Catalogs read from the running APK's assets, the fallback when the extracted copy on
    // disk is stale or absent. Separate cache so neither source can shadow the other.
    private static final Map<String, JSONObject> ASSET_CATALOGS = new HashMap<>();
    private static final java.util.Set<String> ASSET_MISSES = new java.util.HashSet<>();
    // Some HTTP surfaces have their own browser-local locale (the external tunnel) and must
    // not mutate the app/daemon locale just to serialize one response. Thread-local keeps that
    // request override isolated from concurrent API calls and from background daemon work.
    private static final ThreadLocal<String> REQUEST_LOCALE = new ThreadLocal<>();

    private Messages() {}

    public static String get(String key) { return get(key, (Object[]) null); }

    public static String get(String key, Object... args) {
        String lang = REQUEST_LOCALE.get();
        if (lang == null) lang = LocaleManager.get();
        String raw = lookup(lang, key);
        if (raw == null && !lang.equals("en")) raw = lookup("en", key);
        // Last resort: the catalog COMPILED INTO the running APK. The lookups above read the
        // extracted copy under /data/local/tmp/web/server-i18n, which HttpServer.extractWebAssets
        // only refreshes when it could resolve the APK from $CLASSPATH — if that failed, the copy
        // on disk is whatever an older build left behind. Keys shipped since then resolved to
        // nothing and were returned verbatim, so automation dropdowns rendered raw ids like
        // "automation.wireless_charging_left" while older keys still translated. The in-APK asset
        // always matches the running code, so consult it before giving up.
        if (raw == null) raw = lookupAsset(lang, key);
        if (raw == null && !lang.equals("en")) raw = lookupAsset("en", key);
        if (raw == null) return key; // dev-visible miss
        if (args == null || args.length == 0) return raw;
        // Escapes lone apostrophes before formatting. Crowdin translators write
        // natural elision (fr "l'", uk "з'", nl "{0}'s"), which raw MessageFormat
        // reads as a quoted literal — dropping the apostrophe and, when it comes
        // before a placeholder, suppressing the substitution entirely.
        return MessageFormatSafe.format(raw, Locale.forLanguageTag(lang), args);
    }

    /**
     * Run one serialization using {@code lang} without changing the persisted app locale.
     *
     * <p>This is intended for request-scoped responses whose client owns a separate locale,
     * such as the external web tunnel. Nested calls restore the previous override, and the
     * thread-local is always cleared in {@code finally} so pooled HTTP threads cannot leak a
     * language into the next request.
     */
    public static <T> T withLocale(String lang, java.util.function.Supplier<T> operation) {
        if (!LocaleManager.isSupported(lang)) return operation.get();
        String previous = REQUEST_LOCALE.get();
        REQUEST_LOCALE.set(lang);
        try {
            return operation.get();
        } finally {
            if (previous == null) REQUEST_LOCALE.remove();
            else REQUEST_LOCALE.set(previous);
        }
    }

    private static synchronized String lookup(String lang, String key) {
        JSONObject cat = CATALOGS.get(lang);
        if (cat == null) {
            cat = load(lang);
            if (cat != null) CATALOGS.put(lang, cat);
        }
        if (cat == null) return null;
        return walk(cat, key);
    }

    /** Walk a dotted path: "errors.bydcloud_not_configured". */
    private static String walk(JSONObject cat, String key) {
        String[] parts = key.split("\\.");
        Object cur = cat;
        for (String p : parts) {
            if (!(cur instanceof JSONObject)) return null;
            cur = ((JSONObject) cur).opt(p);
            if (cur == null) return null;
        }
        return cur instanceof String ? (String) cur : null;
    }

    /**
     * Catalog lookup against the copy COMPILED INTO the running APK
     * ({@code assets/server-i18n/<lang>.json}), used only when the extracted copy on disk
     * lacks the key (stale or never written — see {@link #get}). Cached separately from the
     * on-disk catalogs so a stale disk file can't shadow it and vice versa.
     */
    private static synchronized String lookupAsset(String lang, String key) {
        JSONObject cat = ASSET_CATALOGS.get(lang);
        if (cat == null) {
            if (ASSET_MISSES.contains(lang)) return null; // don't re-open a known-absent asset
            // Only a real Context can tell us the asset is absent. Before DaemonBootstrap has
            // one, return without recording a miss — latching here would disable this fallback
            // for the whole process, which is what let raw keys leak in the first place.
            android.content.Context ctx = com.overdrive.app.daemon.DaemonBootstrap.getContext();
            if (ctx == null || ctx.getAssets() == null) return null;
            cat = loadAsset(lang);
            if (cat == null) {
                ASSET_MISSES.add(lang);
                return null;
            }
            ASSET_CATALOGS.put(lang, cat);
        }
        return walk(cat, key);
    }

    private static JSONObject loadAsset(String lang) {
        try {
            android.content.Context ctx = com.overdrive.app.daemon.DaemonBootstrap.getContext();
            if (ctx == null) return null;
            android.content.res.AssetManager am = ctx.getAssets();
            if (am == null) return null;
            try (java.io.InputStream in = am.open("server-i18n/" + lang + ".json")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            }
        } catch (Throwable t) {
            // Absent asset / no usable Context (unit tests, early boot) — the caller falls
            // back to returning the key, exactly as before.
            return null;
        }
    }

    private static JSONObject load(String lang) {
        try {
            File f = new File(catalogDir() + "/" + lang + ".json");
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
    public static synchronized void invalidate() {
        CATALOGS.clear();
        ASSET_CATALOGS.clear();
        // Clear the negative cache too: assets become readable once a real Context exists, so a
        // miss recorded during early boot must not persist for the process lifetime.
        ASSET_MISSES.clear();
    }
}
