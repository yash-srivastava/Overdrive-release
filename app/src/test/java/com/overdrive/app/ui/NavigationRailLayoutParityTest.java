package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class NavigationRailLayoutParityTest {
    private static final Pattern DESTINATION_ID =
            Pattern.compile("android:id=\"@\\+id/(railDest\\w+)\"");
    private static final Pattern SECTION_ID =
            Pattern.compile("android:id=\"@\\+id/(railSection\\w+)\"");
    private static final Pattern ROW_ID =
            Pattern.compile("android:id=\"@\\+id/(rail(?:Dest|Section)\\w+)\"");

    @Test
    public void portraitAndLandscapeExposeTheSameDestinationRows() throws Exception {
        Set<String> portrait = destinationIds(
                readProjectFile("src/main/res/layout/activity_main_new.xml"));
        Set<String> landscape = destinationIds(
                readProjectFile("src/main/res/layout-land/activity_main_new.xml"));

        assertEquals(portrait, landscape);
        assertTrue(portrait.containsAll(Arrays.asList(
                "railDestDashboard",
                "railDestAssistant",
                "railDestLive",
                "railDestRecordings",
                "railDestVehicle",
                "railDestSeatPositions",
                "railDestProjection",
                "railDestTrips",
                "railDestCharging",
                "railDestAutomations",
                "railDestKeyMapping",
                "railDestIntegrations",
                "railDestRoadSense",
                "railDestMap",
                "railDestDiagnostics",
                "railDestSettings",
                "railDestAbout"
        )));
    }

    /**
     * Header order is what files each row under a category, so the two
     * orientations have to interleave them identically, not merely declare the
     * same set. MainActivity.railSections tracks this order.
     */
    @Test
    public void portraitAndLandscapeGroupRowsUnderTheSameHeaders() throws Exception {
        String portrait = readProjectFile("src/main/res/layout/activity_main_new.xml");
        String landscape = readProjectFile("src/main/res/layout-land/activity_main_new.xml");

        assertEquals(railRowOrder(portrait), railRowOrder(landscape));
        assertEquals(Arrays.asList(
                "railSectionCameras",
                "railSectionControls",
                "railSectionDriving",
                "railSectionAutomation",
                "railSectionSystem"
        ), new ArrayList<>(matches(SECTION_ID, portrait)));
    }

    private static Set<String> destinationIds(String xml) {
        return matches(DESTINATION_ID, xml);
    }

    /** Destination and header ids in declaration order. */
    private static List<String> railRowOrder(String xml) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = ROW_ID.matcher(xml);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private static Set<String> matches(Pattern pattern, String xml) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(xml);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve(relativePath);
        Path file = Files.exists(fromModule)
                ? fromModule
                : current.resolve("app").resolve(relativePath);
        if (!Files.exists(file)) {
            throw new AssertionError("Could not locate project file: " + relativePath);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
