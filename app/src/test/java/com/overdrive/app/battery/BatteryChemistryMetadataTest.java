package com.overdrive.app.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BatteryChemistryMetadataTest {
    private static final Set<String> EXPECTED_MODELS = new HashSet<>(Arrays.asList(
            "seal", "seal-u", "seal-u-dmi", "dolphin", "atto3",
            "han", "tang", "m6", "seagull", "destroyer"));
    private static final Map<String, String> EXPECTED_MODEL_SOURCES = new HashMap<>();

    static {
        EXPECTED_MODEL_SOURCES.put("seal", "https://media.byd.com/byd-seal-arrives-in-europe-setting-the-standard-in-breakthrough-technology-and-stunning-design/?lang=eng");
        EXPECTED_MODEL_SOURCES.put("seal-u", "https://www.byd.com/content/dam/byd-site/hu/pdfs/seal-u/BYD_SEAL_U_arlista_20250101.pdf");
        EXPECTED_MODEL_SOURCES.put("seal-u-dmi", "https://www.byd.com/material/byd-site/si/pdfs/2026-04/Seal_U_Dmi-0226-BPS-SLO.pdf");
        EXPECTED_MODEL_SOURCES.put("dolphin", "https://www.byd.com/content/dam/byd-site/pl/pdfs/dolphin/Dolphin-0524-BPS-PL-V1-web.pdf");
        EXPECTED_MODEL_SOURCES.put("atto3", "https://www.byd.com/content/dam/byd-site/eu/product/atto3/BYD%20ATTO%203%20Leaflet.pdf");
        EXPECTED_MODEL_SOURCES.put("han", "https://www.byd.com/content/dam/byd-site/de/product/han/BYD%20HAN%20.pdf");
        EXPECTED_MODEL_SOURCES.put("tang", "https://media.byd.com/all-new-pure-electric-suv-byd-tang-advances-sustainable-goals-at-uefa-euro-2024tm/?lang=eng");
        EXPECTED_MODEL_SOURCES.put("m6", "https://www.byd.com/material/byd-site/sg/2025-m6/BYD-M6-SPEC-SHEET.pdf");
        EXPECTED_MODEL_SOURCES.put("seagull", "https://www.byd.com/content/dam/byd-site/za/product/dolphin-surf/Dolphin%20Surf%20Spec.pdf");
        EXPECTED_MODEL_SOURCES.put("destroyer", "https://media.byd.com/byd-launches-seal-5-dm-i-offering-long-range-super-dm-hybrid-tech-and-low-running-costs/?lang=eng");
    }

    @Test
    public void everySupportedConfigurationHasManufacturerEvidenceAndResolvesLfp() throws Exception {
        JSONObject manifest = manifest();
        assertEquals(9, manifest.getInt("version"));

        JSONArray models = manifest.getJSONArray("models");
        Set<String> actual = new HashSet<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.getJSONObject(i);
            actual.add(model.getString("id"));
            assertEquals(model.getString("id"), "lfp",
                    BatteryChemistryMetadata.resolve(manifest, model));

            JSONObject evidence = model.getJSONObject("batteryChemistryEvidence");
            assertEquals("BYD", evidence.getString("authority"));
            assertTrue(BatteryChemistryMetadata.isOfficialBydUrl(evidence.getString("sourceUrl")));
            assertEquals(model.getString("id"), EXPECTED_MODEL_SOURCES.get(model.getString("id")),
                    evidence.getString("sourceUrl"));
            assertTrue(evidence.getString("configuration").length() > 8);
        }
        assertEquals(EXPECTED_MODELS, actual);
    }

    @Test
    public void bareOrUntrustedOrWrongConfigurationLabelsFailClosed() throws Exception {
        JSONObject manifest = manifest();
        JSONObject original = manifest.getJSONArray("models").getJSONObject(0);

        JSONObject bare = new JSONObject(original.toString());
        bare.remove("batteryChemistryEvidence");
        assertEquals("unknown", BatteryChemistryMetadata.resolve(manifest, bare));

        JSONObject untrusted = new JSONObject(original.toString());
        untrusted.getJSONObject("batteryChemistryEvidence")
                .put("sourceUrl", "https://example.com/specification.pdf");
        assertEquals("unknown", BatteryChemistryMetadata.resolve(manifest, untrusted));

        JSONObject wrongCapacity = new JSONObject(original.toString());
        wrongCapacity.getJSONObject("batteryChemistryEvidence").put("nominalKwh", 60.48);
        assertEquals("unknown", BatteryChemistryMetadata.resolve(manifest, wrongCapacity));

        JSONObject missingScope = new JSONObject(original.toString());
        missingScope.getJSONObject("batteryChemistryEvidence").remove("configuration");
        assertEquals("unknown", BatteryChemistryMetadata.resolve(manifest, missingScope));

        JSONObject missingDefinition = new JSONObject(manifest.toString());
        missingDefinition.remove("batteryChemistryDefinitions");
        assertEquals("unknown", BatteryChemistryMetadata.resolve(missingDefinition, original));
    }

    private static JSONObject manifest() throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String relative = "app/src/main/assets/web/shared/models/manifest.json";
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new JSONObject(new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8));
            }
            Path fromModule = current.resolve(relative.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new JSONObject(new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8));
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relative);
    }
}
