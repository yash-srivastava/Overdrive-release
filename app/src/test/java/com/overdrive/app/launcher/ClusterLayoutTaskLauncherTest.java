package com.overdrive.app.launcher;

import static org.junit.Assert.*;

import com.overdrive.app.launcher.layout.ClusterLayoutTaskLaunch;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ClusterLayoutTaskLauncherTest {
    @Test public void ordinaryAppUsesDashcastFreeformWaitAndForceStopFlags() {
        List<String> args = ClusterLayoutTaskLauncher.launchCommand("com.waze",
                "com.waze/.MainActivity", 7, 123, true, true);
        assertTrue(args.contains("-S"));
        assertTrue(args.contains("-W"));
        assertEquals("5", args.get(args.indexOf("--windowingMode") + 1));
        assertEquals("7", args.get(args.indexOf("--display") + 1));
        assertEquals("com.waze/.MainActivity", args.get(args.indexOf("-n") + 1));
    }

    @Test public void plainRetryOmitsWindowingModeInsteadOfRepeatingBrokenStackCreation() {
        List<String> args = ClusterLayoutTaskLauncher.launchCommand("com.waze",
                "com.waze/.MainActivity", 7, 123, false, true);
        assertFalse(args.contains("--windowingMode"));
        assertTrue(args.contains("-S"));
        assertTrue(args.contains("--activity-no-animation"));
    }

    @Test public void unresolvedComponentUsesMainAndLauncherCategory() {
        List<String> args = ClusterLayoutTaskLauncher.launchCommand("com.waze",
                null, 7, 123, true, false);
        assertFalse(args.contains("-n"));
        assertTrue(args.contains("android.intent.action.MAIN"));
        assertTrue(args.contains("android.intent.category.LAUNCHER"));
        assertEquals("com.waze", args.get(args.indexOf("-p") + 1));
    }

    @Test public void roadsenseNeverStopsItsPackageAndPreservesGenerationExtras() {
        List<String> args = ClusterLayoutTaskLauncher.launchCommand("com.overdrive.app",
                "com.overdrive.app/.navmap.RoadSenseLayoutMapActivity", 7, 123, true, true);
        assertFalse(args.contains("-S"));
        assertTrue(args.contains("-W"));
        assertTrue(args.contains("layoutGeneration"));
        assertTrue(args.contains("123"));
        assertTrue(args.contains("layoutDisplayId"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotLaunchOnHeadUnit() {
        ClusterLayoutTaskLauncher.launchCommand("com.waze", "com.waze/.MainActivity", 0, 123, true, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotLaunchDifferentPackagesComponent() {
        ClusterLayoutTaskLauncher.launchCommand("com.waze", "other.app/.MainActivity", 7, 123, true, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotUseHeadUnitRoadsenseComponentForPane() {
        ClusterLayoutTaskLauncher.launchCommand("com.overdrive.app",
                "com.overdrive.app/.navmap.RoadSenseMapActivity", 7, 123, true, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void roadsenseCannotFallBackToPackageLauncher() {
        ClusterLayoutTaskLauncher.launchCommand("com.overdrive.app", null, 7, 123, true, false);
    }

    @Test public void sharedOrAmbiguousRootsCannotBeMovedOrHaveModeChanged() {
        Stack only = new Stack(7, 5, 1, new int[]{42});
        assertSame(only, ClusterLayoutTaskLauncher.exclusiveRoot(Arrays.asList(only), 42));
        assertNull(ClusterLayoutTaskLauncher.exclusiveRoot(
                Arrays.asList(new Stack(0, 1, 1, new int[]{42, 90})), 42));
        assertNull(ClusterLayoutTaskLauncher.exclusiveRoot(Arrays.asList(only, only), 42));
        assertNull(ClusterLayoutTaskLauncher.exclusiveRoot(
                Arrays.asList(new Stack(7, 5, 1, null)), 42));
    }

    @Test public void minimumWindowHeightAndOffsetCanClipAValidSlot() {
        assertTrue(ClusterLayoutTaskLauncher.overflowsSlot(0, 0, 900, 440, 900, 380));
        assertTrue(ClusterLayoutTaskLauncher.overflowsSlot(0, 24, 900, 404, 900, 380));
        assertTrue(ClusterLayoutTaskLauncher.overflowsSlot(-2, 0, 898, 380, 900, 380));
        assertFalse(ClusterLayoutTaskLauncher.overflowsSlot(0, 0, 900, 380, 900, 380));
        assertFalse(ClusterLayoutTaskLauncher.overflowsSlot(10, 10, 890, 370, 900, 380));
        assertFalse(ClusterLayoutTaskLauncher.overflowsSlot(0, 0, 0, 0, 900, 380));
    }

    @Test public void slotFullscreenFallbackRequiresExclusiveRootOnOwnedDisplay() {
        List<Stack> owned = Collections.singletonList(new Stack(7, 5, 1, new int[]{42}));
        assertTrue(ClusterLayoutTaskLauncher.exclusiveSlotRoot(owned, 42, 7));
        assertFalse(ClusterLayoutTaskLauncher.exclusiveSlotRoot(owned, 42, 9));
        assertFalse(ClusterLayoutTaskLauncher.exclusiveSlotRoot(owned, 45, 7));
        assertFalse(ClusterLayoutTaskLauncher.exclusiveSlotRoot(
                Collections.singletonList(new Stack(7, 5, 1, new int[]{42, 43})), 42, 7));
        assertFalse(ClusterLayoutTaskLauncher.exclusiveSlotRoot(
                Collections.singletonList(new Stack(0, 5, 1, new int[]{42})), 42, 0));
    }

    @Test public void emptySplitEscalationRefusesAnyLiveOrUnreadableSplitStack() {
        Stack empty = new Stack(7, 3, 1, new int[0]);
        assertTrue(ClusterLayoutTaskLauncher.allSplitStacksEmpty(Arrays.asList(empty)));
        assertFalse(ClusterLayoutTaskLauncher.allSplitStacksEmpty(
                Arrays.asList(empty, new Stack(0, 4, 1, new int[]{90}))));
        assertFalse(ClusterLayoutTaskLauncher.allSplitStacksEmpty(
                Arrays.asList(empty, new Stack(0, 4, 1, null))));
        assertFalse(ClusterLayoutTaskLauncher.allSplitStacksEmpty(Arrays.asList(empty, new Object())));
        assertFalse(ClusterLayoutTaskLauncher.allSplitStacksEmpty(Collections.emptyList()));
    }

    @Test public void readsWindowConfigurationInsteadOfOnlyNonexistentLegacyFields() {
        Stack split = new Stack(7, 3, 1, new int[0]);
        assertEquals(3, ClusterLayoutTaskLauncher.windowingMode(split));
        assertEquals(1, ClusterLayoutTaskLauncher.activityType(split));
        assertEquals(4, ClusterLayoutTaskLauncher.windowingMode(new TextStack()));
        assertEquals(2, ClusterLayoutTaskLauncher.activityType(new TextStack()));
    }

    @Test public void watchdogSelectsEscapingTaskAheadOfTaskStillInPane() {
        ClusterLayoutTaskLaunch.Task expected = new ClusterLayoutTaskLaunch.Task(true, 42, 7);
        ClusterLayoutTaskLaunch.Task escaping = new ClusterLayoutTaskLaunch.Task(true, 90, 0);
        assertSame(escaping, ClusterLayoutTaskLauncher.selectTask(Arrays.asList(expected, escaping), 7));
        assertEquals(ClusterLayoutTaskLaunch.Match.UNKNOWN,
                ClusterLayoutTaskLauncher.selectTask(Arrays.asList(expected,
                        ClusterLayoutTaskLaunch.Task.unknown()), 7).match(7));
        assertEquals(ClusterLayoutTaskLaunch.Match.ABSENT,
                ClusterLayoutTaskLauncher.selectTask(Collections.emptyList(), 7).match(7));
    }

    public static final class Stack {
        public int stackId = 10;
        public int displayId;
        public int[] taskIds;
        public Configuration configuration;
        Stack(int display, int mode, int type, int[] tasks) {
            displayId = display; taskIds = tasks; configuration = new Configuration(mode, type);
        }
    }

    public static final class Configuration {
        public Window windowConfiguration;
        Configuration(int mode, int type) { windowConfiguration = new Window(mode, type); }
    }

    public static final class Window {
        private final int mode, type;
        Window(int mode, int type) { this.mode = mode; this.type = type; }
        public int getWindowingMode() { return mode; }
        public int getActivityType() { return type; }
    }

    public static final class TextStack {
        public TextConfiguration configuration = new TextConfiguration();
    }

    public static final class TextConfiguration {
        @Override public String toString() {
            return "{mWindowingMode=split-screen-secondary mActivityType=home}";
        }
    }
}
