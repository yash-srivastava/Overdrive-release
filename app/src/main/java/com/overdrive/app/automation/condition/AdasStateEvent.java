package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Read-only ESP / iTAC automation signals. Never infer state from a write action.
 *
 * <p>DiLink 5 supplies fake/stub ADAS getter values (ESP always returns 0),
 * and neither getter should be queried with ACC off. Both cases invalidate
 * previously observed states so IF gates cannot match stale values.
 */
public final class AdasStateEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");
    static final long POLL_MS = 2_000L;
    // No DiLink 5 freshness bridge is needed: these reads are not supported there.
    private static final ConditionalPoller poller = new ConditionalPoller(
            "ADAS state", POLL_MS, AdasStateEvent::referenced, AdasStateEvent::poll, false);

    private AdasStateEvent() {}

    private static void expireBoth() {
        Automations.expireState(BydEvent.ESP_CONTROL);
        Automations.expireState(BydEvent.ITAC);
    }

    private static boolean referenced() {
        if (DiLink5Platform.isSelected()) {
            expireBoth();
            return false;
        }
        return Automations.isEventReferenced(BydEvent.ESP_CONTROL)
                || Automations.isEventReferenced(BydEvent.ITAC);
    }

    /** Never consult an OEM ADAS getter on DiLink 5 or with ACC off. */
    static boolean canRead(boolean diLink5, boolean accOn) {
        return !diLink5 && accOn;
    }

    private static void poll() {
        sample(false);
    }

    private static void sample(boolean forEditor) {
        // The ACC check must happen BEFORE either getter, even during an editor seed.
        if (DiLink5Platform.isSelected()) {
            expireBoth();
            return;
        }
        BydDataCollector collector = BydDataCollector.getInstance();
        if (!canRead(false, collector.isAccOn())) {
            expireBoth();
            return;
        }
        if (forEditor || Automations.isEventReferenced(BydEvent.ESP_CONTROL)) {
            publishReadback(BydEvent.ESP_CONTROL, espWord(collector.getEspState()));
        } else {
            Automations.expireState(BydEvent.ESP_CONTROL);
        }
        if (forEditor || Automations.isEventReferenced(BydEvent.ITAC)) {
            publishReadback(BydEvent.ITAC, itacWord(collector.getItacState()));
        } else {
            Automations.expireState(BydEvent.ITAC);
        }
    }

    /**
     * Seed live readings for the automation editor even when no rule references
     * these keys. Suppress trigger delivery even when other rules are enabled.
     */
    public static void seedForEditor() {
        if (!Automations.editorSeedActive()) return;
        try {
            Automations.runSilentSeed(() -> sample(true));
        } catch (Throwable t) {
            expireBoth();
            logger.warn("ADAS editor seed failed: " + t.getMessage());
        }
    }

    private static void publishReadback(EventData event, String state) {
        if (state == null) Automations.expireState(event);
        else Automations.update(event, state);
    }

    // DiLink 3/4 ESP HAL reports inverted values: 0=enabled, 1=disabled.
    static String espWord(int raw) {
        if (raw == 0) return "on";
        if (raw == 1) return "off";
        return null;
    }

    // PROVISIONAL: other tools decode SETTING_ITAC_STATE as 0=on, 1/2=off;
    // the car's raw values still need to be captured before/after a menu toggle.
    // An unknown value MUST remain unknown, never default to "off".
    static String itacWord(int raw) {
        if (raw == 0) return "on";
        if (raw == 1 || raw == 2) return "off";
        return null;
    }

    public static void refresh() {
        if (!referenced()) expireBoth();
        poller.refresh();
    }

    static boolean isScheduledForTest() {
        return poller.isScheduledForTest();
    }
}
