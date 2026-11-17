package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;

/**
 * Read-only counterparts to the existing ESP and iTAC vehicle-control actions.
 * Only polls while an enabled rule references one of these states; each HAL
 * getter is individually gated so an ESP-only rule never probes iTAC.
 *
 * <p>The first reading is silently seeded by {@link ConditionalPoller}. Unknown
 * getter values expire the previous observation rather than become off.
 * These are observed states, not the last value sent by an automation action.
 */
public final class AdasStateEvent {
    static final long POLL_MS = 2_000L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "ADAS state", POLL_MS, AdasStateEvent::referenced, AdasStateEvent::poll);

    private AdasStateEvent() {}

    private static boolean referenced() {
        return Automations.isEventReferenced(BydEvent.ESP_CONTROL)
                || Automations.isEventReferenced(BydEvent.ITAC);
    }

    private static void poll() {
        BydDataCollector collector = BydDataCollector.getInstance();
        if (Automations.isEventReferenced(BydEvent.ESP_CONTROL)) {
            publishReadback(BydEvent.ESP_CONTROL, espWord(collector.getEspState()));
        }
        if (Automations.isEventReferenced(BydEvent.ITAC)) {
            publishReadback(BydEvent.ITAC, itacWord(collector.getItacState()));
        }
    }

    /**
     * Hide an old observation when a getter stops answering. Leaving the last
     * valid value in the shared state would let an unrelated WHEN trigger pass
     * an IF gate based on a stale safety-control reading.
     */
    private static void publishReadback(EventData event, String state) {
        if (state == null) Automations.expireState(event);
        else Automations.update(event, state);
    }

    // OEM getESPState has INVERTED polarity (0=enabled, 1=disabled).
    static String espWord(int raw) {
        if (raw == 0) return "on";
        if (raw == 1) return "off";
        return null;
    }

    // Follow the same confirmed mapping as GET /api/vehicle/adas: 1=on, 0=off.
    // The iTAC SET command uses 2 for off, but an unverified GET value of 2
    // must not be advertised as off until the OEM readback is validated on-car.
    static String itacWord(int raw) {
        if (raw == 1) return "on";
        if (raw == 0) return "off";
        return null;
    }

    public static void refresh() {
        poller.refresh();
    }

    static boolean isScheduledForTest() {
        return poller.isScheduledForTest();
    }
}
