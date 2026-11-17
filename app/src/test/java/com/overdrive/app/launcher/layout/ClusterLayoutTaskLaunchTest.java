package com.overdrive.app.launcher.layout;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ClusterLayoutTaskLaunchTest {
    @Test public void successfulLaunchAppliesFreeformAgainAfterMove() {
        Fake ops = new Fake();
        assertEquals(42, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertEquals(Arrays.asList("clean", "stop", "start:freeform:component", "poll",
                "single", "resizeable", "freeform", "move", "freeform", "resize", "focus"), ops.calls);
    }

    @Test public void missingComponentUsesMainLaunch() {
        Fake ops = new Fake();
        assertEquals(42, ClusterLayoutTaskLaunch.launch(ops, false, true));
        assertTrue(ops.calls.contains("start:freeform:main"));
        assertFalse(ops.calls.contains("start:freeform:component"));
    }

    @Test public void rejectedComponentCanUseMainWithoutPlainRetry() {
        Fake ops = new Fake();
        ops.starts.add("Error: Activity class does not exist");
        ops.starts.add("Starting: Intent");
        assertEquals(42, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertTrue(ops.calls.contains("start:freeform:main"));
        assertEquals(1, count(ops.calls, "clean"));
    }

    @Test public void poisonedFreeformStackIsCleanedBeforePlainLaunch() {
        Fake ops = new Fake();
        ops.starts.add("Exception occurred while executing: NullPointerException "
                + "ActivityStack.onConfigurationChanged ActivityStack.getBounds()");
        ops.starts.add("Starting: Intent");
        assertEquals(42, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertFalse(ops.calls.contains("start:freeform:main"));
        assertEquals(Arrays.asList("clean", "stop", "start:freeform:component", "clean",
                "start:plain:component", "poll"), ops.calls.subList(0, 6));
    }

    @Test public void apparentlySuccessfulStartWithNoTaskGetsCleanPlainRetry() {
        Fake ops = new Fake();
        ops.tasks.add(ClusterLayoutTaskLaunch.Task.absent());
        ops.tasks.add(new ClusterLayoutTaskLaunch.Task(true, 42, 7));
        assertEquals(42, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertEquals(Arrays.asList("clean", "stop", "start:freeform:component", "poll",
                "clean", "start:plain:component", "poll"), ops.calls.subList(0, 7));
    }

    @Test public void noTaskAfterPlainRetryCannotPublishSuccess() {
        Fake ops = new Fake();
        ops.tasks.add(ClusterLayoutTaskLaunch.Task.absent());
        ops.tasks.add(ClusterLayoutTaskLaunch.Task.absent());
        assertEquals(-1, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertFalse(ops.calls.contains("single"));
        assertFalse(ops.calls.contains("move"));
    }

    @Test public void unreadableTaskQueryDoesNotAuthorizePlacement() {
        Fake ops = new Fake();
        ops.tasks.add(ClusterLayoutTaskLaunch.Task.unknown());
        ops.tasks.add(ClusterLayoutTaskLaunch.Task.unknown());
        assertEquals(-1, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertFalse(ops.calls.contains("resizeable"));
    }

    @Test public void cancelledGenerationIssuesNoCommand() {
        Fake ops = new Fake();
        ops.active = false;
        assertEquals(-1, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertTrue(ops.calls.isEmpty());
    }

    @Test public void cancellationDuringTaskQueryCannotBeFollowedByPlacement() {
        Fake ops = new Fake();
        ops.cancelAt = "poll";
        assertEquals(-1, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertFalse(ops.calls.contains("single"));
        assertFalse(ops.calls.contains("freeform"));
    }

    @Test public void cancellationDuringFreeformCannotBeFollowedByMoveOrResize() {
        Fake ops = new Fake();
        ops.cancelAt = "freeform";
        assertEquals(-1, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertFalse(ops.calls.contains("move"));
        assertFalse(ops.calls.contains("resize"));
        assertFalse(ops.calls.contains("focus"));
    }

    @Test public void cancellationDuringCleanupDoesNotLaunchOrStopAnApp() {
        Fake ops = new Fake();
        ops.cancelAt = "clean";
        assertEquals(-1, ClusterLayoutTaskLaunch.launch(ops, true, true));
        assertEquals(Arrays.asList("clean"), ops.calls);
    }

    @Test public void isolatedMapDoesNotFallBackToMainActivity() {
        Fake ops = new Fake();
        ops.starts.add("Error: freeform refused");
        assertEquals(42, ClusterLayoutTaskLaunch.launch(ops, true, false));
        assertFalse(ops.calls.contains("start:freeform:main"));
        assertTrue(ops.calls.contains("start:plain:component"));
    }

    @Test public void watchdogCompletesAfterInitialGuardOnExpectedDisplay() {
        ClusterLayoutTaskLaunch.Watchdog guard = new ClusterLayoutTaskLaunch.Watchdog();
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.WAIT,
                guard.onPoll(59, ClusterLayoutTaskLaunch.Match.EXPECTED));
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.COMPLETE,
                guard.onPoll(60, ClusterLayoutTaskLaunch.Match.EXPECTED));
    }

    @Test public void escapingTaskExtendsGuardAfterMove() {
        ClusterLayoutTaskLaunch.Watchdog guard = new ClusterLayoutTaskLaunch.Watchdog();
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.REANCHOR,
                guard.onPoll(59, ClusterLayoutTaskLaunch.Match.OTHER));
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.WAIT,
                guard.onPoll(88, ClusterLayoutTaskLaunch.Match.EXPECTED));
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.COMPLETE,
                guard.onPoll(89, ClusterLayoutTaskLaunch.Match.EXPECTED));
    }

    @Test public void missingAndUnreadableTasksWaitWithoutBeingMoved() {
        ClusterLayoutTaskLaunch.Watchdog guard = new ClusterLayoutTaskLaunch.Watchdog();
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.WAIT,
                guard.onPoll(60, ClusterLayoutTaskLaunch.Match.UNKNOWN));
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.WAIT,
                guard.onPoll(179, ClusterLayoutTaskLaunch.Match.ABSENT));
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.COMPLETE,
                guard.onPoll(180, ClusterLayoutTaskLaunch.Match.UNKNOWN));
    }

    @Test public void watchdogExtensionNeverExceedsMaximum() {
        ClusterLayoutTaskLaunch.Watchdog guard = new ClusterLayoutTaskLaunch.Watchdog();
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.REANCHOR,
                guard.onPoll(179, ClusterLayoutTaskLaunch.Match.OTHER));
        assertEquals(ClusterLayoutTaskLaunch.Watchdog.Action.COMPLETE,
                guard.onPoll(180, ClusterLayoutTaskLaunch.Match.EXPECTED));
        assertTrue(ClusterLayoutTaskLaunch.Watchdog.logMove(5));
        assertFalse(ClusterLayoutTaskLaunch.Watchdog.logMove(6));
        assertTrue(ClusterLayoutTaskLaunch.Watchdog.logMove(20));
    }

    @Test public void taskMatchingDistinguishesAbsenceFromUnknownDisplay() {
        assertEquals(ClusterLayoutTaskLaunch.Match.ABSENT, ClusterLayoutTaskLaunch.Task.absent().match(7));
        assertEquals(ClusterLayoutTaskLaunch.Match.UNKNOWN, ClusterLayoutTaskLaunch.Task.unknown().match(7));
        assertEquals(ClusterLayoutTaskLaunch.Match.UNKNOWN,
                new ClusterLayoutTaskLaunch.Task(true, 42, -1).match(7));
        assertEquals(ClusterLayoutTaskLaunch.Match.OTHER,
                new ClusterLayoutTaskLaunch.Task(true, 42, 0).match(7));
    }

    @Test public void cleanupRefusesLiveHomeFullscreenAndUnidentifiedStacks() {
        assertTrue(ClusterLayoutTaskLaunch.removableStack(7, 7, 3, 1, new int[0]));
        assertFalse(ClusterLayoutTaskLaunch.removableStack(7, 0, 3, 1, new int[0]));
        assertFalse(ClusterLayoutTaskLaunch.removableStack(0, 0, 3, 1, new int[0]));
        assertFalse(ClusterLayoutTaskLaunch.removableStack(7, 7, 3, 1, new int[]{42}));
        assertFalse(ClusterLayoutTaskLaunch.removableStack(7, 7, 3, 1, null));
        assertFalse(ClusterLayoutTaskLaunch.removableStack(7, 7, 1, 1, new int[0]));
        assertFalse(ClusterLayoutTaskLaunch.removableStack(7, 7, 3, 2, new int[0]));
        assertFalse(ClusterLayoutTaskLaunch.removableStack(7, 7, -1, -1, new int[0]));
    }

    @Test public void roadsenseOnlyMatchesItsLayoutComponent() {
        String pkg = "com.overdrive.app";
        assertFalse(ClusterLayoutTaskLaunch.matchesTask(pkg, pkg,
                "com.overdrive.app.ui.MainActivity", pkg, "com.overdrive.app.ui.MainActivity"));
        assertFalse(ClusterLayoutTaskLaunch.matchesTask(pkg, pkg,
                "com.overdrive.app.navmap.RoadSenseClusterMapActivity", null, null));
        assertTrue(ClusterLayoutTaskLaunch.matchesTask(pkg, pkg,
                "com.overdrive.app.navmap.RoadSenseLayoutMapActivity", null, null));
        assertTrue(ClusterLayoutTaskLaunch.matchesTask(pkg, "other.app", "other.app.Popup",
                pkg, "com.overdrive.app.navmap.RoadSenseLayoutMapActivity"));
    }

    private static int count(List<String> list, String value) {
        int count = 0;
        for (String entry : list) if (entry.equals(value)) count++;
        return count;
    }

    private static final class Fake implements ClusterLayoutTaskLaunch.Operations {
        boolean active = true;
        String cancelAt = "";
        final List<String> calls = new ArrayList<>();
        final ArrayDeque<String> starts = new ArrayDeque<>();
        final ArrayDeque<ClusterLayoutTaskLaunch.Task> tasks = new ArrayDeque<>();
        private String call(String step) {
            calls.add(step);
            if (cancelAt.equals(step)) active = false;
            return "OK";
        }
        @Override public boolean current() { return active; }
        @Override public String cleanDisplay() { return call("clean"); }
        @Override public String stopPackage() { return call("stop"); }
        @Override public String start(boolean freeform, boolean component) {
            call("start:" + (freeform ? "freeform" : "plain") + ":" + (component ? "component" : "main"));
            return starts.isEmpty() ? "Starting: Intent" : starts.remove();
        }
        @Override public ClusterLayoutTaskLaunch.Task pollTask() {
            call("poll");
            return tasks.isEmpty() ? new ClusterLayoutTaskLaunch.Task(true, 42, 7) : tasks.remove();
        }
        @Override public String singleTask() { return call("single"); }
        @Override public String resizeable(int task) { return call("resizeable"); }
        @Override public String freeform(int task) { return call("freeform"); }
        @Override public String move(int task) { return call("move"); }
        @Override public String resize(int task) { return call("resize"); }
        @Override public String focus(int task) { return call("focus"); }
        @Override public void record(String step, String result) {}
    }
}
