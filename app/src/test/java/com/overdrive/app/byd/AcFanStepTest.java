package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class AcFanStepTest {

    @Test
    public void stepMovesOneLevelFromTheCurrentFan() {
        assertEquals(4, BydDataCollector.nextAcFanLevel(3, 1));
        assertEquals(2, BydDataCollector.nextAcFanLevel(3, -1));
    }

    @Test
    public void slowerAtOneStaysAtOneInsteadOfStoppingTheFan() {
        assertEquals(1, BydDataCollector.nextAcFanLevel(1, -1));
        assertEquals(1, BydDataCollector.nextAcFanLevel(2, -6));
    }

    @Test
    public void fasterAtSevenStaysAtSeven() {
        assertEquals(7, BydDataCollector.nextAcFanLevel(7, 1));
        assertEquals(7, BydDataCollector.nextAcFanLevel(6, 6));
    }

    @Test
    public void stoppedOrUnreadableFanRefusesTheStep() {
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.nextAcFanLevel(0, 1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.nextAcFanLevel(BydVehicleData.UNAVAILABLE, 1));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.nextAcFanLevel(8, -1));
    }

    @Test
    public void stepRefusesWhileTheAcIsOffAndReusesTheFanSetter() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        int start = source.indexOf("public int stepAcFanLevel(int delta)");
        int end = source.indexOf("public boolean setAcWindMode(", start);
        assertTrue(start >= 0 && end > start);
        String step = source.substring(start, end);
        assertTrue(step.contains("int power = readAcPowerNow();"));
        assertTrue(step.contains("if (power != 1)"));
        assertTrue(step.contains("int current = readAcFanLevelNow();"));
        assertTrue(step.contains("if (target == current)"));
        assertTrue(step.contains("setAcFanLevel(target)"));
    }

    @Test
    public void climateEndpointAndKeyMappingExposeTheStep() throws Exception {
        String handler = read("app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        assertTrue(handler.contains("case \"step_fan\": {"));
        assertTrue(handler.contains("new VehicleCommandRouter.ClimateStepFanCommand(delta)"));

        String router = read("app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java");
        assertTrue(router.contains("public static final class ClimateStepFanCommand extends VehicleCommand"));
        assertTrue(router.contains("c.stepAcFanLevel(delta)"));

        String keymap = read("app/src/main/assets/web/shared/key-mapping.js");
        assertTrue(keymap.contains("id: 'ac_fan_step',     i18n: 'keymap.act_ac_fan_step'"));
        assertTrue(keymap.contains("body: '{\"action\":\"step_fan\",\"delta\":${v}}'"));
        assertTrue(keymap.contains("{ v: '1', i18n: 'keymap.fan_faster' }, { v: '-1', i18n: 'keymap.fan_slower' }"));
        assertTrue(keymap.contains("ac_fan_step: 'climate'"));

        String en = read("app/src/main/assets/web/i18n/en.json");
        assertTrue(en.contains("\"act_ac_fan_step\": "));
        assertTrue(en.contains("\"fan_faster\": "));
        assertTrue(en.contains("\"fan_slower\": "));
    }

    @Test
    public void automationOffersTheStepInTheClimateCategory() throws Exception {
        String actions = read("app/src/main/java/com/overdrive/app/automation/action/Actions.java");
        int start = actions.indexOf("new Label(\"stepAcFan\", \"automation.step_ac_fan\")");
        assertTrue(start >= 0);
        String step = actions.substring(start, actions.indexOf("addAction(", start));
        assertTrue(step.contains("\"/api/vehicle/climate\""));
        assertTrue(step.contains("\"{\\\"action\\\":\\\"step_fan\\\",\\\"delta\\\":${delta}}\""));
        assertTrue(step.contains("new Label(\"1\", \"automation.step_faster\")"));
        assertTrue(step.contains("new Label(\"-1\", \"automation.step_slower\")"));

        String categories = read("app/src/main/java/com/overdrive/app/automation/AutomationCategories.java");
        assertTrue(categories.contains("\"setAcFan\", \"stepAcFan\""));

        String en = read("app/src/main/assets/server-i18n/en.json");
        assertTrue(en.contains("\"step_ac_fan\": "));
        assertTrue(en.contains("\"step_ac_fan_description\": "));
        assertTrue(en.contains("\"step_faster\": "));
        assertTrue(en.contains("\"step_slower\": "));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
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
