package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WifiDebuggingDialogControllerTest {
    @Test
    public void matchingIsRestrictedToTheSystemDialogAndEventWindow() {
        assertTrue(WifiDebuggingDialogController.matches(
                "com.android.systemui", "com.android.systemui.WifiDebuggingActivity", 7));
        assertFalse(WifiDebuggingDialogController.matches(
                "other.package", "com.android.systemui.WifiDebuggingActivity", 7));
        assertFalse(WifiDebuggingDialogController.matches(
                "com.android.systemui", "OtherActivity", 7));
        assertFalse(WifiDebuggingDialogController.matches(
                "com.android.systemui", "WifiDebuggingActivity", -1));
    }

    @Test
    public void repeatedEventsScheduleOneLookupAndClickEachNodeOnce() {
        Fixture fixture = new Fixture();
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        assertEquals(1, fixture.scheduler.tasks.size());
        fixture.scheduler.runAll();
        assertEquals(1, fixture.checkbox.clicks);
        assertEquals(1, fixture.button.clicks);
        assertEquals(1, fixture.environment.actions);
        assertTrue(fixture.checkbox.closed);
        assertTrue(fixture.button.closed);
        assertTrue(fixture.window.closed);
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        assertEquals(1, fixture.scheduler.tasks.size());
    }

    @Test
    public void delayedOptOutOrOwnerCancellationPreventsWindowLookup() {
        Fixture fixture = new Fixture();
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.environment.eligible = false;
        fixture.scheduler.runAll();
        assertEquals(0, fixture.environment.windowLookups);
        assertEquals(0, fixture.environment.actions);
    }

    @Test
    public void missingNodeClosesAcquiredResourcesAndDoesNotClick() {
        Fixture fixture = new Fixture();
        fixture.window.button = null;
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.scheduler.runAll();
        assertTrue(fixture.checkbox.closed);
        assertTrue(fixture.window.closed);
        assertEquals(0, fixture.checkbox.clicks);
        assertEquals(0, fixture.environment.actions);
    }

    @Test
    public void clickFailureStopsBeforeConfirmation() {
        Fixture fixture = new Fixture();
        fixture.checkbox.clickResult = false;
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.scheduler.runAll();
        assertEquals(1, fixture.checkbox.clicks);
        assertEquals(0, fixture.button.clicks);
        assertEquals(0, fixture.environment.actions);
    }

    @Test
    public void ownerInvalidationCancelsQueuedLookup() {
        Fixture fixture = new Fixture();
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.environment.invalidateOwner();
        fixture.scheduler.runAll();
        assertEquals(0, fixture.environment.windowLookups);
        assertEquals(0, fixture.environment.actions);
    }

    @Test
    public void teardownCancelsQueuedLookup() {
        Fixture fixture = new Fixture();
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.controller.close();
        fixture.scheduler.runAll();
        assertEquals(0, fixture.environment.windowLookups);
        assertEquals(0, fixture.environment.actions);
    }

    @Test
    public void closeDuringLookupPreventsEitherClick() {
        Fixture fixture = new Fixture();
        fixture.checkbox.onIsChecked = fixture.controller::close;
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.scheduler.runAll();
        assertEquals(0, fixture.checkbox.clicks);
        assertEquals(0, fixture.button.clicks);
        assertEquals(0, fixture.environment.actions);
    }

    @Test
    public void ownerInvalidatedDuringTheFinalEligibilityReadCannotClickConfirmation() {
        Fixture fixture = new Fixture();
        fixture.environment.onEligibilityRead = () -> {
            if (fixture.environment.eligibilityReads == 5) fixture.environment.invalidateOwner();
        };
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.scheduler.runAll();
        assertEquals(1, fixture.checkbox.clicks);
        assertEquals(0, fixture.button.clicks);
        assertEquals(0, fixture.environment.actions);
        assertTrue(fixture.checkbox.closed);
        assertTrue(fixture.button.closed);
        assertTrue(fixture.window.closed);
    }

    @Test
    public void closeDuringExternalEligibilityCannotClickEitherNode() {
        Fixture fixture = new Fixture();
        fixture.environment.onEligibilityRead = () -> {
            if (fixture.environment.eligibilityReads == 3) fixture.controller.close();
        };
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.scheduler.runAll();
        assertEquals(0, fixture.checkbox.clicks);
        assertEquals(0, fixture.button.clicks);
        assertEquals(0, fixture.environment.actions);
        assertTrue(fixture.window.closed);
    }

    @Test
    public void lifecycleDefersConstructionAndUnbindCancelsInflightWork() {
        Fixture fixture = new Fixture();
        final int[] factoryCalls = {0};
        WifiDebuggingDialogController.Lifecycle lifecycle =
                new WifiDebuggingDialogController.Lifecycle(() -> {
                    factoryCalls[0]++;
                    return fixture.controller;
                });
        assertEquals(0, factoryCalls[0]);
        lifecycle.attach();
        lifecycle.attach();
        assertEquals(1, factoryCalls[0]);

        fixture.checkbox.onIsChecked = lifecycle::unbind;
        fixture.controller.onDialogWindow("com.android.systemui", "WifiDebuggingActivity", 7);
        fixture.scheduler.runAll();
        assertEquals(0, fixture.checkbox.clicks);
        assertEquals(0, fixture.button.clicks);
        assertEquals(0, fixture.environment.actions);
        lifecycle.destroy();
    }

    private static final class Fixture {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeNode checkbox = new FakeNode(false);
        final FakeNode button = new FakeNode(false);
        final FakeWindow window = new FakeWindow(checkbox, button);
        final FakeEnvironment environment = new FakeEnvironment(window);
        final WifiDebuggingDialogController controller =
                new WifiDebuggingDialogController(scheduler, environment);
    }

    private static final class FakeScheduler implements WifiDebuggingDialogController.Scheduler {
        final List<Task> tasks = new ArrayList<>();

        @Override
        public WifiDebuggingDialogController.Cancellable postDelayed(long delayMs, Runnable action) {
            assertEquals(WifiDebuggingDialogController.LOOKUP_DELAY_MS, delayMs);
            Task task = new Task(action);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        void runAll() {
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.cancelled) task.action.run();
            }
        }
    }

    private static final class Task {
        final Runnable action;
        boolean cancelled;
        Task(Runnable action) { this.action = action; }
    }

    private static final class FakeEnvironment implements WifiDebuggingDialogController.Environment {
        final WifiDebuggingDialogController.SessionKey session =
                new WifiDebuggingDialogController.SessionKey(11L, "wifi");
        final FakeWindow window;
        boolean eligible = true;
        int windowLookups;
        int actions;
        int eligibilityReads;
        Runnable onEligibilityRead;
        WifiDebuggingDialogController.OwnerInvalidationListener invalidationListener;

        FakeEnvironment(FakeWindow window) { this.window = window; }

        @Override
        public WifiDebuggingDialogController.SessionKey eligibleSession() {
            return eligible ? session : null;
        }

        @Override
        public boolean isEligible(WifiDebuggingDialogController.SessionKey session) {
            boolean result = eligible && this.session.equals(session);
            eligibilityReads++;
            if (onEligibilityRead != null) onEligibilityRead.run();
            return result;
        }

        @Override
        public WifiDebuggingDialogController.DialogWindow findWindow(int windowId) {
            windowLookups++;
            return windowId == 7 ? window : null;
        }

        @Override
        public void onDialogActionAttempted(long generation) {
            assertEquals(session.getGeneration(), generation);
            actions++;
        }

        @Override
        public void setOwnerInvalidationListener(
                WifiDebuggingDialogController.OwnerInvalidationListener listener) {
            invalidationListener = listener;
        }

        void invalidateOwner() {
            eligible = false;
            if (invalidationListener != null) {
                invalidationListener.onInvalidated(session.getGeneration());
            }
        }
    }

    private static final class FakeWindow implements WifiDebuggingDialogController.DialogWindow {
        FakeNode checkbox;
        FakeNode button;
        boolean closed;

        FakeWindow(FakeNode checkbox, FakeNode button) {
            this.checkbox = checkbox;
            this.button = button;
        }

        @Override
        public WifiDebuggingDialogController.Node findNode(String viewId) {
            if (WifiDebuggingDialogController.CHECKBOX_ID.equals(viewId)) return checkbox;
            if (WifiDebuggingDialogController.BUTTON_ID.equals(viewId)) return button;
            return null;
        }

        @Override public void close() { closed = true; }
    }

    private static final class FakeNode implements WifiDebuggingDialogController.Node {
        final boolean checked;
        boolean clickResult = true;
        boolean closed;
        int clicks;
        Runnable onIsChecked;

        FakeNode(boolean checked) { this.checked = checked; }
        @Override public boolean isChecked() {
            if (onIsChecked != null) onIsChecked.run();
            return checked;
        }
        @Override public boolean click() { clicks++; return clickResult; }
        @Override public void close() { closed = true; }
    }
}
