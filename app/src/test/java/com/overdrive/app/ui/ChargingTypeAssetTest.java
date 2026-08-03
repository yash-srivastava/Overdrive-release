package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards connector-authoritative charging-type classification in the served web app. */
public class ChargingTypeAssetTest {

    @Test
    public void explicitAcVerdictPrecedesPowerOnlyDcInference() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/charging.js");
        String classifier = functionBlock(script, "_typeKind: function (s)");

        int dcFlag = classifier.indexOf("if (s.isDc === true) return 'dc';");
        int acFlag = classifier.indexOf("if (s.isDc === false)");
        int inferredDc = classifier.indexOf("if (peak >= this.DC_KW) return 'dc';");

        assertTrue(dcFlag >= 0);
        assertTrue(acFlag > dcFlag);
        assertTrue("power-only DC inference must come after explicit AC", inferredDc > acFlag);
        assertFalse(classifier.contains("s.isDc === true || peak >= this.DC_KW"));
        assertTrue("the server poison verdict must select live power",
                classifier.contains("var acPower = s.powerDataQuality === 'poisoned' ? live : peak;"));
        assertTrue(classifier.contains("return acPower >= this.AC_FAST_KW ? 'fast' : 'slow';"));
    }

    @Test
    public void explicitAcPowerDisplayRejectsDcSizedStoredPeak() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/charging.js");
        String displayPeak = functionBlock(script, "_displayPeakKw: function (s)");

        assertTrue(displayPeak.contains("s.powerDataQuality === 'poisoned'"));
        assertTrue(displayPeak.contains("return 0;"));
        assertFalse("the UI must not duplicate the poison threshold",
                displayPeak.contains("s.isDc === false && peak >= this.DC_KW"));
        assertFalse(displayPeak.contains("livePowerKw"));
        assertFalse(displayPeak.contains("avgPower"));
        assertFalse(displayPeak.contains("summaryCache"));
        assertTrue(script.contains("var chipKw = self._displayPeakKw(s);"));
        assertTrue(script.contains("var displayedPeak = this._displayPeakKw(s);"));
    }

    private static String functionBlock(String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0) return "";
        int end = source.indexOf("\n    },", start);
        return end < 0 ? source.substring(start) : source.substring(start, end + 7);
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
