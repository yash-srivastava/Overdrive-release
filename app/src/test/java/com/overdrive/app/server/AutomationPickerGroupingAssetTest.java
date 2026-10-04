package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins category-local sorting and BEV filtering in the two schema-driven pickers. */
public class AutomationPickerGroupingAssetTest {

    @Test
    public void groupedOptionsAreSortedByLocalizedLabel() throws IOException {
        String automations = readRepositoryFile(
                "app/src/main/assets/web/shared/automations.js");
        String keyMapping = readRepositoryFile(
                "app/src/main/assets/web/shared/key-mapping.js");
        String automationsPage = readRepositoryFile(
                "app/src/main/assets/web/local/automations.html");
        String keyMappingPage = readRepositoryFile(
                "app/src/main/assets/web/local/key-mapping.html");

        assertTrue(automations.contains(
                "const items = buckets.get(cat).slice().sort"));
        assertTrue(automations.contains("this._collator().compare("));
        assertTrue(keyMapping.contains(
                "var items = buckets[cats[i]].slice().sort"));
        assertTrue(keyMapping.contains("String(tr(a.i18n)).localeCompare("));
        assertTrue(keyMapping.contains("BYD.i18n.onChange(refreshLocale)"));
        assertTrue(keyMapping.contains(
                "action.textContent = describeAction(b.action) || b.label || '';"));
        assertTrue(automationsPage.contains("automations.js?v=av70"));
        assertTrue(keyMappingPage.contains("key-mapping.js?v=36"));
    }

    @Test
    public void operatingModeUsesPersistentHelpGuideInsteadOfTooltip() throws IOException {
        String automations = readRepositoryFile(
                "app/src/main/assets/web/shared/automations.js");
        String english = readRepositoryFile(
                "app/src/main/assets/web/i18n/en.json");

        assertTrue(automations.contains(
                "if (selected.description && value !== 'operatingMode')"));
        assertTrue(automations.contains(
                "'automation.operating_mode_help_intro'"));
        assertTrue(automations.contains(
                "'automation.operating_mode_help_on_only'"));
        assertTrue(automations.contains(
                "'automation.operating_mode_help_on_and_off'"));
        assertTrue(english.contains("entire OverDrive daemon stack"));
        assertTrue(english.contains("all OverDrive daemons shut down"));
        assertTrue(english.contains("uses more parked battery"));
    }

    @Test
    public void keyMappingFiltersHybridOnlyActionsOnConfirmedBev() throws IOException {
        String keyMapping = readRepositoryFile(
                "app/src/main/assets/web/shared/key-mapping.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/KeymapApiHandler.java");

        assertTrue(keyMapping.contains("var HYBRID_ONLY_CURATED = {"));
        assertTrue(keyMapping.contains(
                "state.fuelCapableHybrid === false"));
        assertTrue(keyMapping.contains("HYBRID_ONLY_CURATED[CURATED[i].id]"));
        assertTrue(api.contains(
                "resp.put(\"fuelCapableHybrid\", collector.isFuelCapableHybridPublic());"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
