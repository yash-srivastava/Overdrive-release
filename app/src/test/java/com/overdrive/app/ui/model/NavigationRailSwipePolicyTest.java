package com.overdrive.app.ui.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavigationRailSwipePolicyTest {

    @Test
    public void rightSwipe_expands() {
        assertEquals(
                NavigationRailSwipePolicy.Action.EXPAND,
                NavigationRailSwipePolicy.resolve(80f, 8f, 48f)
        );
    }

    @Test
    public void leftSwipe_collapses() {
        assertEquals(
                NavigationRailSwipePolicy.Action.COLLAPSE,
                NavigationRailSwipePolicy.resolve(-80f, 8f, 48f)
        );
    }

    @Test
    public void verticalScroll_doesNothing() {
        assertEquals(
                NavigationRailSwipePolicy.Action.NONE,
                NavigationRailSwipePolicy.resolve(20f, 100f, 48f)
        );
    }

    @Test
    public void shortHorizontalMovement_doesNothing() {
        assertEquals(
                NavigationRailSwipePolicy.Action.NONE,
                NavigationRailSwipePolicy.resolve(30f, 0f, 48f)
        );
    }

    @Test
    public void diagonalMovement_doesNothing() {
        assertEquals(
                NavigationRailSwipePolicy.Action.NONE,
                NavigationRailSwipePolicy.resolve(80f, 70f, 48f)
        );
    }
}
