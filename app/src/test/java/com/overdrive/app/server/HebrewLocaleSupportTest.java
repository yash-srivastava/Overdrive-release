package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Regression coverage for canonical Hebrew, RTL, and legacy iw wiring. */
public class HebrewLocaleSupportTest {

    @Test
    public void hebrewAliasesResolveToOneCanonicalLocale() {
        assertTrue(LocaleManager.isSupported("he"));
        assertFalse(LocaleManager.isSupported("iw"));
        assertEquals("he", LocaleManager.resolve("he-IL"));
        assertEquals("he", LocaleManager.resolve("iw-IL"));
        assertEquals("he", LocaleManager.fromAcceptLanguage(
                "yi;q=1.0,iw-IL;q=0.9,en;q=0.8"));
        assertEquals(null, LocaleManager.resolveOrNull("../en"));
    }

    @Test
    public void catalogsCoverEveryEnglishKeyAndPreservePlaceholders()
            throws Exception {
        assertCatalogCoversEnglish(
                "app/src/main/assets/web/i18n/en.json",
                "app/src/main/assets/web/i18n/he.json");
        assertCatalogCoversEnglish(
                "app/src/main/assets/server-i18n/en.json",
                "app/src/main/assets/server-i18n/he.json");

        JSONObject server = readJson("app/src/main/assets/server-i18n/he.json");
        JSONObject automation = server.getJSONObject("automation");
        assertEquals("חימום גלגל ההגה", automation.getString("steering_heat"));
        assertFalse(automation.getString("ivi_reboot").isEmpty());
    }

    @Test
    public void nativeAppBundleHasCompleteStringKeyParity()
            throws Exception {
        Set<String> nativeEnglish = translatableStringKeys(
                repositoryPath("app/src/main/res/values"));
        Set<String> nativeHebrew = translatableStringKeys(
                repositoryPath("app/src/main/res/values-he"));
        assertTrue(missingMessage(nativeEnglish, nativeHebrew),
                nativeHebrew.containsAll(nativeEnglish));

    }

    @Test
    public void rtlRuntimeAndResourceAliasesAreWiredWithoutDuplicatePickerRows()
            throws Exception {
        String core = read("app/src/main/assets/web/shared/core.js");
        assertTrue(core.contains("'he': true"));
        assertTrue(core.contains("'he':    'עברית'"));
        assertTrue(core.contains("lower === 'iw'"));
        assertTrue(core.contains("AndroidBridge.getI18nCatalog"));

        String appLocaleConfig = read("app/src/main/res/xml/locales_config.xml");
        assertTrue(appLocaleConfig.contains("android:name=\"cs\""));
        assertTrue(appLocaleConfig.contains("android:name=\"he\""));
        assertFalse(appLocaleConfig.contains("android:name=\"iw\""));

        String appBuild = read("app/build.gradle.kts");
        assertTrue(appBuild.contains("tasks.registering(Sync::class)"));
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:supportsRtl=\"true\""));
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""));
        // The companion's original assertions are retained as an explicit,
        // separately invoked contract; see tools/tests/companion-launcher-locales.
    }

    @Test
    public void generatedHebrewAliasUsesSyncForItsDedicatedInputsAndDestination()
            throws Exception {
        assertTrue("The Hebrew alias must mirror its own source directory",
                hebrewAliasUsesSync(read("app/build.gradle.kts")));
    }

    @Test
    public void unrelatedSyncTaskCannotMaskARegressedHebrewAlias()
            throws Exception {
        String build = read("app/build.gradle.kts");
        String registration = "val syncHebrewIwResources by tasks.registering(Sync::class)";
        String regressed = build.replace(registration,
                "val syncHebrewIwResources by tasks.registering(Copy::class)")
                + "\nval unrelatedResourceSync by tasks.registering(Sync::class) {}\n";
        assertTrue(regressed.contains("tasks.registering(Sync::class)"));
        assertFalse("An unrelated Sync must not satisfy the Hebrew alias contract",
                hebrewAliasUsesSync(regressed));
        assertFalse(hebrewAliasUsesSync(build.replace("from(\"src/main/res/values-he\")",
                "from(\"src/main/res/values-en\")")));
        assertFalse(hebrewAliasUsesSync(build.replace("it.dir(\"values-iw\")",
                "it.dir(\"values-fr\")")));
    }

    private static boolean hebrewAliasUsesSync(String build) {
        String registration = "val syncHebrewIwResources by tasks.registering(Sync::class) {";
        int start = build.indexOf(registration);
        if (start < 0) return false;
        int end = build.indexOf("\n}", start);
        if (end < 0) return false;
        String task = build.substring(start, end);
        return build.contains("val hebrewIwResDir = layout.buildDirectory.dir(\"generated/res/iwAlias\")")
                && task.contains("from(\"src/main/res/values-he\")")
                && task.contains("into(hebrewIwResDir.map { it.dir(\"values-iw\") })");
    }

    private static void assertCatalogCoversEnglish(String englishPath, String hebrewPath)
            throws Exception {
        JSONObject english = readJson(englishPath);
        JSONObject hebrew = readJson(hebrewPath);
        Set<String> englishKeys = new HashSet<>();
        Set<String> hebrewKeys = new HashSet<>();
        collectKeys(english, "", englishKeys);
        collectKeys(hebrew, "", hebrewKeys);
        assertTrue(missingMessage(englishKeys, hebrewKeys),
                hebrewKeys.containsAll(englishKeys));

        for (String key : englishKeys) {
            Object englishValue = valueAt(english, key);
            Object hebrewValue = valueAt(hebrew, key);
            if (englishValue instanceof String && hebrewValue instanceof String) {
                assertEquals("placeholder mismatch at " + key,
                        placeholders((String) englishValue),
                        placeholders((String) hebrewValue));
            }
        }
    }

    private static void collectKeys(Object value, String prefix, Set<String> out) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                collectKeys(object.opt(key),
                        prefix.isEmpty() ? key : prefix + "." + key, out);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                collectKeys(array.opt(i), prefix + "[" + i + "]", out);
            }
        } else {
            out.add(prefix);
        }
    }

    private static Object valueAt(JSONObject root, String dotted) {
        Object current = root;
        String[] parts = dotted.split("\\.");
        for (String part : parts) {
            if (!(current instanceof JSONObject)) return null;
            current = ((JSONObject) current).opt(part);
        }
        return current;
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new HashSet<>();
        Matcher matcher = Pattern.compile(
                "\\$?\\{[^{}]+\\}|%\\d+\\$[a-zA-Z]").matcher(value);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    private static Set<String> translatableStringKeys(Path directory)
            throws Exception {
        Set<String> keys = new HashSet<>();
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.toString().endsWith(".xml"))::iterator) {
                NodeList strings = DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().parse(file.toFile())
                        .getElementsByTagName("string");
                for (int i = 0; i < strings.getLength(); i++) {
                    Node node = strings.item(i);
                    if (!(node instanceof Element)) continue;
                    Element element = (Element) node;
                    if ("false".equals(element.getAttribute("translatable"))) continue;
                    String name = element.getAttribute("name");
                    if (!name.isEmpty()) keys.add(name);
                }
            }
        }
        return keys;
    }

    private static String missingMessage(Set<String> expected, Set<String> actual) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return "Missing translated keys: " + missing;
    }

    private static JSONObject readJson(String relativePath) throws Exception {
        return new JSONObject(read(relativePath));
    }

    private static String read(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(repositoryPath(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static Path repositoryPath(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.exists(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository path: " + relativePath);
    }
}
