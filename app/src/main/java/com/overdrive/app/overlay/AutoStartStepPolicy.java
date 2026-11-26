package com.overdrive.app.overlay;

/**
 * Whether the setup guide's "Disable Auto-Start Restriction" step applies to this head unit.
 *
 * <p>BYD100F (DiLink 100) firmware ships without BYD's auto-start manager and does not hold
 * OverDrive back from starting in the background, so the step has nothing to open there.
 * Other firmware keeps the step: some ROMs without the manager still put an auto-start
 * switch on the app-info page.
 */
final class AutoStartStepPolicy {

    private AutoStartStepPolicy() {
    }

    /**
     * @param daemonPathSelected DiLink 5 mode, where the daemon applies background access
     * @param byd100f            the head unit is a BYD100F build
     * @param managerPresent     BYD's auto-start manager can be opened on this firmware
     */
    static boolean applies(boolean daemonPathSelected, boolean byd100f, boolean managerPresent) {
        return daemonPathSelected || !byd100f || managerPresent;
    }
}
