package com.overdrive.app.camera.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.camera.CameraProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.Test;

public class DiLink5PlatformTest {
    @Test
    public void selectionIsExplicitAndLegacyModesStayDisabled() {
        assertFalse(DiLink5Platform.isSelected());
        assertFalse(DiLink5Platform.isSelected("", ""));
        assertFalse(DiLink5Platform.isSelected("default", "auto"));
        assertFalse(DiLink5Platform.isSelected("dilink4", "auto"));
        assertTrue(DiLink5Platform.isSelected("dilink5", "auto"));
        assertFalse(DiLink5Platform.isSelected(
                "default", CameraProfiles.PROFILE_DILINK5_SEALION7));
    }

    @Test
    public void pendingSelectionRequiresMatchingStagedAndDurableDiLink5Modes() {
        assertTrue(DiLink5Platform.isPendingDiLink5Selection("dilink5", "dilink5"));
        assertTrue(DiLink5Platform.isPendingDiLink5Selection(" DILINK5 ", "DiLink5"));
        assertFalse(DiLink5Platform.isPendingDiLink5Selection("dilink5", "default"));
        assertFalse(DiLink5Platform.isPendingDiLink5Selection("dilink4", "dilink5"));
        assertFalse(DiLink5Platform.isPendingDiLink5Selection(null, "dilink5"));
    }

    @Test
    public void configuredModeNormalizationNeverDefaultsInvalidInput() {
        assertEquals("default",
                DiLink5Platform.normalizeConfiguredMode(" DEFAULT "));
        assertEquals("dilink4",
                DiLink5Platform.normalizeConfiguredMode("DiLink4"));
        assertEquals("dilink5",
                DiLink5Platform.normalizeConfiguredMode("DILINK5"));
        assertNull(DiLink5Platform.normalizeConfiguredMode(null));
        assertNull(DiLink5Platform.normalizeConfiguredMode(""));
        assertNull(DiLink5Platform.normalizeConfiguredMode("legacy"));
    }

    @Test
    public void panelControlUsesOnlyRuntimeFencedModes() {
        assertFalse(DiLink5Platform.isPanelControlMode(null));
        assertFalse(DiLink5Platform.isPanelControlMode("default"));
        assertTrue(DiLink5Platform.isPanelControlMode("dilink4"));
        assertTrue(DiLink5Platform.isPanelControlMode("dilink5"));
    }

    @Test
    public void stagedActiveModeWinsOnlyWithMatchingPendingTransition() {
        assertTrue(DiLink5Platform.isSameMode("DILINK4", "dilink4"));
        assertFalse(DiLink5Platform.isSameMode("default", "dilink4"));
        assertFalse(DiLink5Platform.isSameMode(null, "default"));
        assertTrue(DiLink5Platform.isActiveMode("default"));
        assertEquals("default",
                DiLink5Platform.effectiveMode("default", "dilink5"));
        assertEquals("dilink5",
                DiLink5Platform.effectiveMode("dilink5", "default"));
        assertEquals("dilink5",
                DiLink5Platform.effectiveMode("invalid", "dilink5"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "dilink5", null, "default"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "dilink5", "dilink5", "default"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", "dilink5", "dilink5"));
        assertEquals("dilink5",
                DiLink5Platform.effectiveMode(
                        "dilink5", "default", "default"));
        String abortedDiLink5Transition = DiLink5Platform.effectiveMode(
                "dilink4", "dilink5", "dilink5");
        assertEquals("dilink4", abortedDiLink5Transition);
        assertTrue(DiLink5Platform.isDiLink4Mode(
                abortedDiLink5Transition));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", "dilink4", "dilink4"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", "dilink4", "default"));
        assertEquals("dilink4",
                DiLink5Platform.effectiveMode(
                        "dilink4", null, "dilink4"));
        assertEquals("dilink4",
                DiLink5Platform.effectiveMode(
                        "dilink4", "default", "default"));
        assertEquals("dilink4",
                DiLink5Platform.effectiveMode(
                        "dilink4", "default", "dilink4"));
        assertEquals("default",
                DiLink5Platform.effectiveMode(
                        "default", null, "default"));
    }

    @Test
    public void validPendingTransitionCannotBeReplacedBeforeRestart() {
        assertTrue(DiLink5Platform.canStageMode(
                "default", "dilink4", "dilink4", "dilink4"));
        assertTrue(DiLink5Platform.canStageMode(
                "default", "dilink4", "dilink4", "default"));
        assertFalse(DiLink5Platform.canStageMode(
                "default", "dilink4", "dilink4", "dilink5"));
        assertTrue(DiLink5Platform.canStageMode(
                "default", null, "default", "dilink5"));
        assertTrue(DiLink5Platform.canStageMode(
                "default", "dilink4", "default", "dilink5"));
        assertFalse(DiLink5Platform.canStageMode(
                "default", null, "default", "invalid"));
    }

    @Test
    public void crossProcessGenerationFailsClosedForMissingOrCorruptMarkers()
            throws Exception {
        Path missing = Files.createTempDirectory("dilink5-mode")
                .resolve("missing");
        assertNull(DiLink5Platform.readModeGeneration(missing.toString()));

        Path marker = Files.createTempFile("dilink5-mode", ".txt");
        Files.write(marker, "dilink5\nnot-a-uuid\n".getBytes(StandardCharsets.UTF_8));
        assertNull(DiLink5Platform.readModeGeneration(marker.toString()));

        String generation = UUID.randomUUID().toString();
        Files.write(
                marker,
                ("dilink5\n" + generation + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(
                generation,
                DiLink5Platform.readModeGeneration(marker.toString()));
        assertTrue(DiLink5Platform.matchesModeGeneration(generation, generation));
        assertFalse(DiLink5Platform.matchesModeGeneration(
                generation, UUID.randomUUID().toString()));
        assertFalse(DiLink5Platform.matchesModeGeneration(generation, null));
        assertFalse(DiLink5Platform.matchesModeGeneration(null, generation));
    }

    @Test
    public void committedSnapshotKeepsModeAndGenerationCoupled() {
        String generation = UUID.randomUUID().toString();
        for (String mode : new String[]{"default", "dilink4", "camera2", "dilink5"}) {
            DiLink5Platform.ActiveModeSnapshot value = DiLink5Platform.parseActiveModeMarker(
                    (mode + "\n" + generation + "\n").getBytes(StandardCharsets.UTF_8));
            assertEquals(mode, value.mode);
            assertEquals(generation, value.generation);
            assertEquals(!"dilink5".equals(mode), value.supportsLegacyVehicleApi());
            assertTrue(value.matches(DiLink5Platform.parseActiveModeMarker(
                    (mode + "\n" + generation + "\n").getBytes(StandardCharsets.UTF_8))));
            assertFalse(value.matches(DiLink5Platform.parseActiveModeMarker(
                    (mode + "\n" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8))));
            assertFalse(value.matches(null));
        }
    }

    @Test
    public void committedSnapshotNeverDefaultsMissingOrMalformedMarker() {
        assertNull(DiLink5Platform.parseActiveModeMarker(null));
        String generation = UUID.randomUUID().toString();
        for (String malformed : new String[]{"", "default\n", "invalid\n" + generation + "\n",
                "default\nnot-a-uuid\n", "default\n1-1-1-1-1\n",
                "default\n" + generation + "\nextra-data\n"}) {
            assertNull(DiLink5Platform.parseActiveModeMarker(
                    malformed.getBytes(StandardCharsets.UTF_8)));
        }
        assertNull(DiLink5Platform.parseActiveModeMarker(new byte[1_025]));
        DiLink5Platform.ActiveModeSnapshot legacy = DiLink5Platform.parseActiveModeMarker(
                ("default\n" + generation + "\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(legacy.matches(DiLink5Platform.parseActiveModeMarker(
                ("dilink5\n" + generation + "\n").getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void manualSetupDistinguishesAbsentMarkersFromInvalidPresentMarkers() {
        DiLink5Platform.ManualAdbModeSnapshot fresh =
                DiLink5Platform.parseManualAdbModeSnapshot(null, null, "default");
        assertNotNull(fresh);
        assertNull(fresh.active);
        assertNull(fresh.pending);
        assertEquals("default", fresh.configuredMode);

        for (byte[] corrupt : new byte[][]{
                new byte[0],
                "dilink5\n".getBytes(StandardCharsets.UTF_8),
                "default\nnot-a-uuid\n".getBytes(StandardCharsets.UTF_8),
                new byte[1_025]}) {
            assertNull(DiLink5Platform.parseManualAdbModeSnapshot(corrupt, null, "default"));
            assertNull(DiLink5Platform.parseManualAdbModeSnapshot(null, corrupt, "default"));
        }
        assertNull(DiLink5Platform.parseManualAdbModeSnapshot(null, null, "unknown"));
        assertNull(DiLink5Platform.parseManualAdbModeSnapshot(null, null, null));
    }

    @Test
    public void manualScopeSupportsEveryCommittedModeWithoutSelectingABackend() {
        boolean selectedBefore = DiLink5Platform.isSelected();
        String generation = UUID.randomUUID().toString();
        for (String mode : new String[]{"default", "dilink4", "camera2", "dilink5"}) {
            DiLink5Platform.ManualAdbModeSnapshot scope =
                    DiLink5Platform.parseManualAdbModeSnapshot(
                            marker(mode, generation), null, mode);
            assertNotNull(scope);
            assertEquals(mode, scope.active.mode);
            assertEquals(generation, scope.active.generation);
            assertNull(scope.pending);
            assertEquals(mode, scope.configuredMode);
        }
        assertEquals(selectedBefore, DiLink5Platform.isSelected());
    }

    @Test
    public void manualScopeIncludesPendingTransitionsForEveryMode() {
        byte[] active = marker("default", UUID.randomUUID().toString());
        String generation = UUID.randomUUID().toString();
        for (String mode : new String[]{"default", "dilink4", "camera2", "dilink5"}) {
            DiLink5Platform.ManualAdbModeSnapshot scope =
                    DiLink5Platform.parseManualAdbModeSnapshot(
                            active, marker(mode, generation), mode);
            assertNotNull(scope);
            assertEquals("default", scope.active.mode);
            assertEquals(mode, scope.pending.mode);
            assertEquals(generation, scope.pending.generation);
        }
    }

    @Test
    public void manualAuthorityCannotSurviveARealModeOrMarkerTransition() {
        byte[] active = marker("default", UUID.randomUUID().toString());
        byte[] pending = marker("dilink4", UUID.randomUUID().toString());
        DiLink5Platform.ManualAdbModeSnapshot captured =
                DiLink5Platform.parseManualAdbModeSnapshot(active, null, "default");
        assertTrue(captured.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(active, null, "default")));
        assertFalse(captured.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(
                        marker("default", UUID.randomUUID().toString()), null, "default")));
        assertFalse(captured.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(active, pending, "dilink4")));
        assertFalse(captured.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(active, null, "dilink4")));
        assertFalse(captured.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(null, null, "default")));
        assertFalse(captured.matches(null));

        DiLink5Platform.ManualAdbModeSnapshot staged =
                DiLink5Platform.parseManualAdbModeSnapshot(active, pending, "dilink4");
        assertTrue(staged.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(active, pending, "dilink4")));
        assertFalse(staged.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(
                        active, marker("dilink4", UUID.randomUUID().toString()), "dilink4")));
        assertFalse(staged.matches(
                DiLink5Platform.parseManualAdbModeSnapshot(active, null, "dilink4")));
    }

    private static byte[] marker(String mode, String generation) {
        return (mode + "\n" + generation + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
