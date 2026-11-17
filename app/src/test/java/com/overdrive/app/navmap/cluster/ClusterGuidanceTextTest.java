package com.overdrive.app.navmap.cluster;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ClusterGuidanceTextTest {
    @Test public void structuredRoadNameTakesPriorityOverTheInstructionAndAliases() {
        assertEquals("Church Road", ClusterGuidanceText.roadLabel(
                Arrays.asList("", " Church Road ", "A123"), "Turn left onto Church Road", 15));
    }

    @Test public void blankUnicodeAliasesDoNotHideThePrimaryRoad() {
        assertEquals("רחוב העצמאות", ClusterGuidanceText.roadLabel(
                Arrays.asList("\u00a0\u2003", "\u00a0רחוב העצמאות\u00a0"), "Turn left", 15));
    }

    @Test public void roadNamesContainingTurnWordsAreNotRewritten() {
        assertEquals("Turn Left", ClusterGuidanceText.roadLabel(
                Collections.singletonList("Turn Left"), "Turn left", 15));
    }

    @Test public void bareTurnsKeepTheCompleteDirectionInsteadOfTheRedundantVerb() {
        assertEquals("Left", ClusterGuidanceText.roadLabel(null, "Turn left", 15));
        assertEquals("Right", ClusterGuidanceText.roadLabel(null, "TURN RIGHT.", 10));
        assertEquals("Left", ClusterGuidanceText.roadLabel(null, " Turn\u00a0left! ", 15));
    }

    @Test public void clearOntoInstructionsRetainTheRoadNameIncludingNonLatinText() {
        assertEquals("Church Road", ClusterGuidanceText.roadLabel(
                null, "Turn left onto Church Road", 15));
        assertEquals("улица Ленина", ClusterGuidanceText.roadLabel(
                null, "Turn right onto улица Ленина", 10));
    }

    @Test public void directionConflictsAndDifferentManeuversRetainTheOriginalText() {
        assertEquals("Turn left", ClusterGuidanceText.roadLabel(null, "Turn left", 10));
        assertEquals("Turn right", ClusterGuidanceText.roadLabel(null, "Turn right", 12));
        assertEquals("Turn left onto Church Road",
                ClusterGuidanceText.roadLabel(null, "Turn left onto Church Road", 26));
    }

    @Test public void translatedAndQualifiedInstructionsAreNotGuessedOrShortened() {
        for (String text : new String[]{
                "Tournez à gauche sur Rue Victor Hugo",
                "פנה שמאלה",
                "Turn left to stay on Church Road",
                "Turn left, then turn right",
                "Turn Left Avenue",
                "Take the third exit"}) {
            assertEquals(text, ClusterGuidanceText.roadLabel(null, text, 15));
        }
    }

    @Test public void missingTextRemainsEmpty() {
        assertEquals("", ClusterGuidanceText.roadLabel(null, null, 15));
        assertEquals("", ClusterGuidanceText.roadLabel(Collections.emptyList(), " \u00a0 ", 15));
    }

    @Test public void namedRoadsKeepTheirFullTextOnBothWirePaths() {
        String name = "רחוב העצמאות / Church Road 🚗";
        String label = ClusterGuidanceText.roadLabel(Collections.singletonList(name), "Turn left", 15);
        ClusterGuidanceFrame frame = new ClusterGuidanceFrame("roadsense", 15, 300, label, 1200, 60, 0);
        assertEquals(name, frame.roadName);
        assertEquals(name, new String(ClusterGuidanceEncoding.streetBytes(frame.roadName),
                StandardCharsets.UTF_16LE));
        assertTrue(new String(ClusterGuidanceEncoding.active(frame),
                StandardCharsets.UTF_8).contains(name));
    }

    @Test public void mapsAndWazeKeepArrowDistanceAndEtaWhenLabelsBecomeCompact() {
        for (String source : new String[]{"maps", "waze"}) {
            ClusterGuidanceFrame frame = ClusterNavigationParser.parse(
                    source, "300 m", "Turn left", "12 km • 1 h 5 min", "ic_maneuver_turn_left");
            assertNotNull(frame);
            assertEquals("Left", frame.roadName);
            assertEquals(1, frame.canIcon());
            assertEquals(300, frame.distanceMeters);
            assertEquals(12000, frame.remainingDistanceMeters);
            assertEquals(3900, frame.remainingTimeSeconds);
        }
    }
}
