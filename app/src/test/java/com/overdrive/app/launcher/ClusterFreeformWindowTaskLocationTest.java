package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import org.junit.Test;

public class ClusterFreeformWindowTaskLocationTest {

    public static class TaskSnapshot {
        private final int mode;
        TaskSnapshot(int mode) { this.mode = mode; }
        public int getWindowingMode() { return mode; }
    }

    @Test
    public void selectedTaskCarriesItsOwnWindowingModeWithoutASecondTaskRead() {
        ClusterFreeformWindow.TaskLocation onTarget =
                ClusterFreeformWindow.taskLocationFromSnapshot(new TaskSnapshot(5), 11, 8);
        ClusterFreeformWindow.TaskLocation fullscreenReset =
                ClusterFreeformWindow.taskLocationFromSnapshot(new TaskSnapshot(1), 11, 0);
        ClusterFreeformWindow.TaskLocation selected = ClusterFreeformWindow.selectTaskLocation(
                Arrays.asList(onTarget, fullscreenReset), 8);
        assertSame(fullscreenReset, selected);
        assertEquals(Integer.valueOf(1), selected.windowingMode);
        assertEquals(Integer.valueOf(5), onTarget.windowingMode);
        assertNull(ClusterFreeformWindow.taskLocationFromSnapshot(new Object(), 11, 0).windowingMode);
        assertNull(new ClusterFreeformWindow.TaskLocation(true, 11, 0).windowingMode);
    }

    @Test
    public void wrongDisplayTaskWinsOverAnExistingTargetTask() {
        ClusterFreeformWindow.TaskLocation selected =
                ClusterFreeformWindow.selectTaskLocation(
                        Arrays.asList(
                                new ClusterFreeformWindow.TaskLocation(true, 11, 3),
                                new ClusterFreeformWindow.TaskLocation(true, 22, 0)),
                        3);

        assertTrue(selected.known);
        assertEquals(22, selected.taskId);
        assertEquals(0, selected.displayId);
    }

    @Test
    public void unknownDisplayIsConservativeAndAbsenceIsKnown() {
        ClusterFreeformWindow.TaskLocation unknownDisplay =
                ClusterFreeformWindow.selectTaskLocation(
                        Arrays.asList(
                                new ClusterFreeformWindow.TaskLocation(true, 11, 3),
                                new ClusterFreeformWindow.TaskLocation(true, 22, -1)),
                        3);
        assertEquals(22, unknownDisplay.taskId);
        assertEquals(-1, unknownDisplay.displayId);

        ClusterFreeformWindow.TaskLocation absent =
                ClusterFreeformWindow.selectTaskLocation(
                        Collections.emptyList(), 3);
        assertTrue(absent.known);
        assertEquals(-1, absent.taskId);

        ClusterFreeformWindow.TaskLocation unavailable =
                ClusterFreeformWindow.selectTaskLocation(null, 3);
        assertFalse(unavailable.known);
    }

    public static class OneArgumentTaskService {
        final List<?> tasks;
        int requestedLimit;
        OneArgumentTaskService(List<?> tasks) { this.tasks = tasks; }
        public List<?> getTasks(int maximum) {
            requestedLimit = maximum;
            return tasks;
        }
    }

    public static class FourArgumentTaskService {
        final List<?> tasks;
        int requestedLimit;
        int requestedDisplay;
        FourArgumentTaskService(List<?> tasks) { this.tasks = tasks; }
        public List<?> getTasks(int maximum, boolean visible, boolean extras, int displayId) {
            requestedLimit = maximum;
            requestedDisplay = displayId;
            return tasks;
        }
    }

    @Test
    public void saturatedSl7QueryKeepsLivePositiveTaskAndRefusesAbsence() throws Exception {
        List<ClusterFreeformWindow.TaskLocation> tasks = new ArrayList<>();
        for (int i = 1; i <= 65; i++) {
            tasks.add(new ClusterFreeformWindow.TaskLocation(true, i, i == 65 ? 8 : 0));
        }
        OneArgumentTaskService service = new OneArgumentTaskService(tasks);
        ClusterFreeformWindow.RunningTaskInventory inventory =
                ClusterFreeformWindow.queryRunningTasks(service);
        assertEquals(65, service.requestedLimit);
        assertSame(tasks, inventory.tasks);
        assertFalse(inventory.complete);
        ClusterFreeformWindow.TaskLocation positive =
                ClusterFreeformWindow.selectTaskLocation(
                        Collections.singletonList(tasks.get(64)), 8, inventory.complete);
        assertTrue(positive.known);
        assertEquals(65, positive.taskId);
        assertEquals(Boolean.FALSE, ClusterFreeformWindow.allTasksReclaimedFromDisplay(
                Collections.singletonList(tasks.get(64)), 8, inventory.complete));
        assertNull(ClusterFreeformWindow.allTasksReclaimedFromDisplay(
                Collections.singletonList(tasks.get(0)), 8, inventory.complete));
        assertFalse(ClusterFreeformWindow.selectTaskLocation(
                Collections.emptyList(), 8, inventory.complete).known);
    }

    @Test
    public void modernQueryDoesNotAccidentallyFilterToTheHeadUnit() throws Exception {
        FourArgumentTaskService service = new FourArgumentTaskService(
                Collections.singletonList(new ClusterFreeformWindow.TaskLocation(true, 11, 8)));
        ClusterFreeformWindow.RunningTaskInventory inventory =
                ClusterFreeformWindow.queryRunningTasks(service);
        assertEquals(65, service.requestedLimit);
        assertEquals(-1, service.requestedDisplay);
        assertTrue(inventory.complete);
        assertSame(service.tasks, inventory.tasks);
    }

    @Test
    public void unknownRowsAndEmptyQueriesCannotAuthorizeDisplayWideRecovery() throws Exception {
        assertNull(ClusterFreeformWindow.allTasksReclaimedFromDisplay(
                Arrays.asList(new ClusterFreeformWindow.TaskLocation(true, 11, 0),
                        ClusterFreeformWindow.TaskLocation.unknown()), 8, true));
        assertFalse(ClusterFreeformWindow.selectTaskLocation(
                Collections.singletonList(ClusterFreeformWindow.TaskLocation.unknown()),
                8, true).known);
        ClusterFreeformWindow.RunningTaskInventory empty =
                ClusterFreeformWindow.queryRunningTasks(
                        new OneArgumentTaskService(Collections.emptyList()));
        assertFalse(empty.complete);
        assertNull(ClusterFreeformWindow.allTasksReclaimedFromDisplay(
                Collections.emptyList(), 8, empty.complete));
    }
}
