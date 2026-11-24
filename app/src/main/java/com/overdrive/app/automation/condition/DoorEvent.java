package com.overdrive.app.automation.condition;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.logging.DaemonLogger;

import java.util.Map;

/**
 * Publishes door open/close edges into the automation state so a "when the driver door
 * opens" trigger / "if boot is open" condition can be evaluated.
 *
 * <p>Event-driven, NOT polled: subscribes to {@link BydDataCollector.DoorStateListener}
 * (the same raw bodywork open/close edge {@link com.overdrive.app.notifications.DoorEventNotifier}
 * uses) and republishes each edge as a {@code doorState} event keyed by area. Unlike
 * the notifier, this does NOT gate on parked/driving — an automation may legitimately
 * want to react to a door opening while driving (e.g. a chime), so every genuine edge
 * is published; the user narrows it with their own conditions.
 *
 * <p>Areas mirror the notifier's mapping, including the drive-side L↔R front-axis swap,
 * so "driver" / "passenger" line up with the physical door on both RHD and LHD trims.
 * A convenience {@code any} area is also published ("any door/lid open?") so a user can
 * trigger on "any door opened" without wiring one condition per door.
 */
public final class DoorEvent {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Automations");

    // Raw bodywork areas (see DoorEventNotifier for the field-tested mapping notes).
    private static final int AREA_1 = 1;      // front, drive-side-dependent
    private static final int AREA_2 = 2;      // front, drive-side-dependent
    private static final int AREA_LR = 3;
    private static final int AREA_RR = 4;
    private static final int AREA_HOOD = 5;
    private static final int AREA_TRUNK = 6;
    private static final int AREA_FUEL_CAP = 7;

    // Event keys — one per physical opening, plus an "any" convenience. Static so they
    // are created once and match what the DoorCondition schema produces.
    static final EventData DOOR_DRIVER = door("driver");
    static final EventData DOOR_PASSENGER = door("passenger");
    static final EventData DOOR_REAR_LEFT = door("rearLeft");
    static final EventData DOOR_REAR_RIGHT = door("rearRight");
    static final EventData DOOR_HOOD = door("hood");
    static final EventData DOOR_TRUNK = door("trunk");
    static final EventData DOOR_FUEL_CAP = door("fuelCap");
    static final EventData DOOR_ANY = door("any");

    static final String DOOR_TYPE = "doorState";

    private static volatile boolean started = false;
    private static final long POLL_MS = 1000L;
    private static final ConditionalPoller poller = new ConditionalPoller(
            "door fallback",
            POLL_MS,
            DoorEvent::shouldPoll,
            () -> BydDataCollector.getInstance().pollDoorStatesNow());

    // Track each opening's last published state so the "any" aggregate can be recomputed
    // correctly (any-open = at least one opening currently open). The callback and parked
    // getter-poll threads can both publish, so onDoorStateChanged synchronizes the update and
    // aggregate calculation.
    private static final Map<Integer, Integer> areaState = new java.util.concurrent.ConcurrentHashMap<>();

    private DoorEvent() {}

    static EventData door(String area) {
        return new EventData(DOOR_TYPE, Map.of("area", area));
    }

    /** Subscribe to raw door edges. Idempotent; call once at startup. */
    public static synchronized void start() {
        if (started) return;
        BydDataCollector.getInstance().addDoorStateListener(DoorEvent::onDoorStateChanged);
        started = true;
        logger.info("DoorEvent: subscribed to door state edges");
    }

    static synchronized void onDoorStateChanged(int area, int state) {
        // Only open/close are meaningful; ignore other raw states (e.g. -1 unknown).
        if (state != BodyworkConstants.STATE_OPEN && state != BodyworkConstants.STATE_CLOSED) return;
        if (!shouldPoll() && !Automations.editorSeedActive()) return;

        boolean open = state == BodyworkConstants.STATE_OPEN;
        EventData key = keyForArea(area);

        // Feed the driver presence lifecycle even when no door automation is configured.
        // An open door is only a possible departure; BydEvent waits for lost positive
        // evidence before invalidating the old occupied value.
        if (area == AREA_1) BydEvent.observeDriverDoorState(state);

        // Recompute the "any opening open" aggregate from the tracked per-area states.
        areaState.put(area, state);
        boolean anyOpen = false;
        for (Integer s : areaState.values()) {
            if (s != null && s == BodyworkConstants.STATE_OPEN) { anyOpen = true; break; }
        }
        final boolean anyOpenNow = anyOpen;

        // One physical edge, two keys. Publish them as ONE observation so a rule that
        // triggers on either key and conditions on the other reads the post-edge value of
        // both. Publishing them back-to-back evaluated the per-door triggers while the
        // aggregate still held its pre-edge value: "when the driver door changes, if any
        // door == closed" was enqueued on OPEN and cancelled on CLOSE, so it never fired
        // (2026-09-23 field log). See Automations.publishTogether for why a plain reorder
        // would only move the staleness to the mirror rule.
        Automations.publishTogether(() -> {
            if (key != null) {
                Automations.update(key, open ? "open" : "closed", true);
            }
            Automations.update(DOOR_ANY, anyOpenNow ? "open" : "closed", true);
        });
    }

    /** Seed current state without routing the sample through notification listeners. */
    public static void acceptSample(int area, int state) {
        onDoorStateChanged(area, state);
    }

    /** Whether a door getter poll is currently useful. */
    public static boolean shouldPoll() {
        return hasDoorReferences() || Automations.isEventReferenced(BydEvent.OCCUPANT_DRIVER);
    }

    /** Non-door references can request the driver door alone; avoid six unneeded HAL reads. */
    public static boolean hasDoorReferences() {
        return Automations.isEventReferenced(DOOR_DRIVER)
                || Automations.isEventReferenced(DOOR_PASSENGER)
                || Automations.isEventReferenced(DOOR_REAR_LEFT)
                || Automations.isEventReferenced(DOOR_REAR_RIGHT)
                || Automations.isEventReferenced(DOOR_HOOD)
                || Automations.isEventReferenced(DOOR_TRUNK)
                || Automations.isEventReferenced(DOOR_FUEL_CAP)
                || Automations.isEventReferenced(DOOR_ANY);
    }

    public static void refresh() {
        poller.refresh();
    }

    public static void seedForEditor() {
        try {
            BydDataCollector.getInstance().pollDoorStatesNow();
        } catch (Throwable t) {
            logger.warn("DoorEvent editor seed failed: " + t.getMessage());
        }
    }

    /**
     * Map a raw bodywork area to its published event as DRIVER / PASSENGER semantics.
     *
     * <p>Front-axis area→physical-side is itself drive-side dependent (per
     * DoorEventNotifier's field-tested mapping): on RHD, area 1 = physical front-RIGHT
     * and area 2 = front-LEFT; on LHD, area 1 = front-LEFT and area 2 = front-RIGHT.
     * The DRIVER also swaps side with drive-side (right on RHD, left on LHD). Those two
     * swaps CANCEL: on RHD area 1 is front-right = the driver; on LHD area 1 is
     * front-left = still the driver. So <b>area 1 is always the driver and area 2 always
     * the passenger, regardless of drive side</b> — no isRhd() branch needed here, and
     * this stays consistent with the notifier (whose RHD area_1→front_right label is
     * also the driver's door on RHD). Rear axis is symmetric (LR=3, RR=4).
     */
    private static EventData keyForArea(int area) {
        switch (area) {
            case AREA_1:  return DOOR_DRIVER;     // front, driver's side on both RHD & LHD
            case AREA_2:  return DOOR_PASSENGER;  // front, passenger's side on both
            case AREA_LR: return DOOR_REAR_LEFT;
            case AREA_RR: return DOOR_REAR_RIGHT;
            case AREA_HOOD: return DOOR_HOOD;
            case AREA_TRUNK: return DOOR_TRUNK;
            case AREA_FUEL_CAP: return DOOR_FUEL_CAP;
            default: return null;
        }
    }
}
