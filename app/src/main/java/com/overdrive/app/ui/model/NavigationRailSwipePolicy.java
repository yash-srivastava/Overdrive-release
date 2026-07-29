package com.overdrive.app.ui.model;

/**
 * Android-free gesture classifier for the collapsible navigation rail.
 *
 * A gesture must travel far enough and be clearly more horizontal than vertical,
 * which prevents ordinary rail scrolling from accidentally expanding it.
 */
public final class NavigationRailSwipePolicy {
    private NavigationRailSwipePolicy() {}

    public enum Action {
        NONE,
        EXPAND,
        COLLAPSE
    }

    public static Action resolve(
            float deltaX,
            float deltaY,
            float minimumDistance
    ) {
        float horizontal = Math.abs(deltaX);
        float vertical = Math.abs(deltaY);
        if (horizontal < minimumDistance || horizontal <= vertical * 1.25f) {
            return Action.NONE;
        }
        return deltaX > 0f ? Action.EXPAND : Action.COLLAPSE;
    }
}
