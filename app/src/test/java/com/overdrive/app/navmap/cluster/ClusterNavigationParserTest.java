package com.overdrive.app.navmap.cluster;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterNavigationParserTest {
    @Test public void measuresMetricImperialDecimalAndLocalizedDigits() {
        assertEquals(1500, ClusterNavigationParser.distance("1,5 km"));
        assertEquals(1609, ClusterNavigationParser.distance("1 mi"));
        assertEquals(152, ClusterNavigationParser.distance("500 ft"));
        assertEquals(300, ClusterNavigationParser.distance("٣٠٠ m"));
        assertEquals(-1, ClusterNavigationParser.distance("arrive at 18:21"));
    }
    @Test public void iconResourcesWinOverAmbiguousRoadText() {
        ClusterGuidanceFrame frame = ClusterNavigationParser.parse(
                "maps", "300 m", "Left Bank Road", "12 km • 1 h 5 min", "ic_maneuver_turn_right");
        assertNotNull(frame);
        assertEquals(10, frame.maneuverType);
        assertEquals(3900, frame.remainingTimeSeconds);
        assertEquals(12000, frame.remainingDistanceMeters);
    }
    @Test public void sharpAndUturnAreParsedBeforeGenericTurn() {
        assertEquals(13, ClusterNavigationParser.typeFromText("make a u-turn left"));
        assertEquals(11, ClusterNavigationParser.typeFromText("sharp right in 300 m"));
        assertEquals(16, ClusterNavigationParser.typeFromText("keep left in 200 m"));
    }
    @Test public void unrecognizedOrDistanceLessNotificationsDoNotInventGuidance() {
        assertNull(ClusterNavigationParser.parse("waze", "Find parking", "", "", ""));
        assertNull(ClusterNavigationParser.parse("maps", "Turn right", "", "", ""));
        assertNull(ClusterNavigationParser.parse("maps", "300 m", "Traffic ahead", "", ""));
    }
    @Test public void roadNamesContainingDirectionLettersDoNotInventTurns() {
        assertEquals(8, ClusterNavigationParser.typeFromText("continue 600 m on brighton road"));
        assertEquals(-1, ClusterNavigationParser.typeFromText("600 m brighton road"));
        assertEquals(8, ClusterNavigationParser.typeFromText("continue 600 m on left bank road"));
    }
    @Test public void rejectedRoadPhrasesCannotReappearInFallbackParsing() {
        assertNull(ClusterNavigationParser.parse("maps", "600 m", "on Roundabout Road", "", ""));
        assertEquals(-1, ClusterNavigationParser.typeFromText("600 m on U-Turn Avenue"));
        assertEquals(-1, ClusterNavigationParser.typeFromText("600 m on Sharp Right Street"));
        assertEquals(-1, ClusterNavigationParser.typeFromText("600 m onto Straight Road"));
        assertEquals(8, ClusterNavigationParser.typeFromText("Continue 600 m on Roundabout Road"));
        assertEquals(10, ClusterNavigationParser.typeFromText("On Roundabout Road, turn right in 600 m"));
    }
    @Test public void sourcePhrasesSupportArabicGermanItalianAndTurkish() {
        assertEquals(10, ClusterNavigationParser.typeFromText("اتجه يمينًا"));
        assertEquals(15, ClusterNavigationParser.typeFromText("links abbiegen"));
        assertEquals(15, ClusterNavigationParser.typeFromText("svolta a sinistra"));
        assertEquals(10, ClusterNavigationParser.typeFromText("sağa dönün"));
        assertEquals(15, ClusterNavigationParser.typeFromText("פנה שמאלה"));
        assertEquals(1500, ClusterNavigationParser.distance("١,٥ كم"));
        assertEquals(300, ClusterNavigationParser.distance("300\u00a0mt"));
        assertEquals(2700, ClusterNavigationParser.duration("٤٥ دقيقة"));
    }
    @Test public void directionalPhraseWinsOverNamedDestinationRoad() {
        ClusterGuidanceFrame frame = ClusterNavigationParser.parse(
                "maps", "Turn right in 300 m", "onto Destination Avenue", "", "");
        assertNotNull(frame);
        assertEquals(2, frame.canIcon());
        assertEquals(10, frame.maneuverType);
    }
    @Test public void roundaboutExitAndDirectionAreSeparateFromExitRamp() {
        ClusterGuidanceFrame frame = ClusterNavigationParser.parse(
                "waze", "At the roundabout, take the 3rd exit in 300 m", "", "", "roundabout_ccw");
        assertNotNull(frame);
        assertEquals(27, frame.canIcon());
        assertEquals(17, frame.clusterIcon());
        assertEquals(3, frame.roundaboutExit);
    }
}
