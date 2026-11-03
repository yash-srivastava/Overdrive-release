import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks the companion launcher contracts against a real, clean Git checkout.
 * This source-file launcher uses only the JDK and performs no network requests.
 */
public final class LauncherLocaleContract {
    private static final String[] INPUTS = {
        "src/main/java/com/overdrive/launcher/i18n/LocaleController.kt",
        "src/main/res/values/strings.xml",
        "src/main/res/values-cs/strings.xml",
        "src/main/res/values-he/strings.xml",
        "src/main/res/xml/locales_config.xml",
        "build.gradle.kts"
    };

    private LauncherLocaleContract() {}

    /** Requires the path to the actual companion module, never a dummy fixture. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Usage: java LauncherLocaleContract.java /path/to/actual/launcher-module");
        }
        Path module = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(module)) {
            throw new IllegalArgumentException("Companion launcher checkout is unavailable: " + module);
        }
        for (String input : INPUTS) {
            require(Files.isRegularFile(module.resolve(input)),
                    "Missing real companion input: " + input);
        }
        Path repository = Path.of(git(module, "rev-parse", "--show-toplevel")).toRealPath();
        module = module.toRealPath();
        require(module.startsWith(repository), "Companion module is outside its Git checkout");
        String revision = git(module, "rev-parse", "--verify", "HEAD");
        String relative = repository.relativize(module).toString();
        for (String input : INPUTS) {
            String tracked = relative.isEmpty() ? input : relative + "/" + input;
            git(repository, "ls-files", "--error-unmatch", "--", tracked);
        }
        // Resource parity scans every XML string file, so require the whole module
        // to match its recorded revision, including absence of untracked inputs.
        String status = git(repository, "status", "--porcelain", "--untracked-files=all", "--",
                relative.isEmpty() ? "." : relative);
        require(status.isEmpty(), "Companion module must be clean at the recorded Git revision");

        czechIsAvailable(module);
        hebrewBundleHasCompleteStringKeyParity(module);
        hebrewRuntimeAndResourceAliasesAreCanonical(module);
        System.out.println("PASS: 3 companion locale contracts at Git revision " + revision);
    }

    private static void czechIsAvailable(Path module) throws Exception {
        String launcherController = read(module,
                "src/main/java/com/overdrive/launcher/i18n/LocaleController.kt");
        String launcherStrings = read(module, "src/main/res/values-cs/strings.xml");
        require(launcherController.contains("\"cs\""), "Czech locale is selectable");
        require(launcherStrings.contains("name=\"settings_language\">Jazyk</string>"),
                "Czech language label is translated");
    }

    private static void hebrewBundleHasCompleteStringKeyParity(Path module) throws Exception {
        Set<String> launcherEnglish = translatableStringKeys(module.resolve("src/main/res/values"));
        Set<String> launcherHebrew = translatableStringKeys(module.resolve("src/main/res/values-he"));
        require(launcherEnglish.equals(launcherHebrew), "Companion Hebrew/English string key parity");
        String launcherStrings = read(module, "src/main/res/values-he/strings.xml");
        require(launcherStrings.contains("name=\"settings_language\">שפה</string>"),
                "Hebrew language label is translated");
        require(launcherStrings.contains("name=\"pip_zone_active_count\">%1$d פעילות</string>"),
                "Hebrew active-count placeholder is preserved");
    }

    private static void hebrewRuntimeAndResourceAliasesAreCanonical(Path module) throws Exception {
        String launcherController = read(module,
                "src/main/java/com/overdrive/launcher/i18n/LocaleController.kt");
        String launcherLocaleConfig = read(module, "src/main/res/xml/locales_config.xml");
        require(launcherController.contains("\"he\""), "Canonical Hebrew locale is selectable");
        require(launcherController.contains("\"iw\" to \"he\""), "Legacy iw resolves to Hebrew");
        require(launcherLocaleConfig.contains("android:name=\"he\""), "Canonical Hebrew picker row");
        require(!launcherLocaleConfig.contains("android:name=\"iw\""), "No duplicate legacy picker row");
        String launcherBuild = read(module, "build.gradle.kts");
        require(launcherBuild.contains("tasks.registering(Sync::class)"),
                "Companion Hebrew resource alias uses Sync");
    }

    private static Set<String> translatableStringKeys(Path directory) throws Exception {
        Set<String> keys = new HashSet<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files
                    .filter(path -> path.toString().endsWith(".xml"))::iterator) {
                NodeList strings = factory.newDocumentBuilder().parse(file.toFile())
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

    private static String read(Path module, String relative) throws Exception {
        return Files.readString(module.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String git(Path directory, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.fsmonitor=false");
        command.add("-C");
        command.add(directory.toString());
        java.util.Collections.addAll(command, arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Git diagnostic timed out");
        }
        byte[] output = process.getInputStream().readNBytes(65537);
        if (output.length > 65536) {
            throw new IllegalStateException("Git diagnostic output exceeded 64 KiB");
        }
        String text = new String(output, StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalArgumentException("A versioned companion checkout is required: " + text);
        }
        return text;
    }
}
