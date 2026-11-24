package com.overdrive.app.automation;

/**
 * Tiny Service Mode boot gate. ServiceModeManager sets this before Automations
 * initializes so boot rules cannot fire in the gap between class loading and
 * applying the saved pause. Keeping the holder separate also preserves lazy
 * Automations initialization for ordinary daemon starts.
 */
public final class ServiceModeAutomationGate {
    private static volatile boolean paused;

    private ServiceModeAutomationGate() {}

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean value) {
        paused = value;
    }
}
