package com.overdrive.app.notifications;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;

/**
 * Publishes vehicle.security.door.opened / .closed notifications when the
 * BYD bodywork HAL reports a door state change while the car is parked
 * (ACC OFF). Driving-state edges are ignored to avoid spam every time the
 * driver enters or exits.
 */
public final class DoorEventNotifier {

    // BYD bodywork area constants — see BYDAutoBodyworkDevice.BODYWORK_CMD_DOOR_*
    private static final int AREA_LF = 1;
    private static final int AREA_RF = 2;
    private static final int AREA_LR = 3;
    private static final int AREA_RR = 4;
    private static final int AREA_HOOD = 5;
    private static final int AREA_TRUNK = 6;
    private static final int AREA_FUEL_CAP = 7;

    private static volatile DoorEventNotifier instance;

    private final BydDataCollector.DoorStateListener listener =
            (area, state) -> onDoorStateChanged(area, state);

    // Last published state per area; -1 means "no event yet" so the first
    // edge after subscription doesn't double-fire on a stale snapshot.
    private final java.util.Map<Integer, Integer> lastState =
            new java.util.concurrent.ConcurrentHashMap<>();

    private DoorEventNotifier() {}

    public static synchronized void start() {
        if (instance != null) return;
        DoorEventNotifier n = new DoorEventNotifier();
        BydDataCollector.getInstance().addDoorStateListener(n.listener);
        instance = n;
    }

    private void onDoorStateChanged(int area, int state) {
        if (state != BodyworkConstants.STATE_OPEN
                && state != BodyworkConstants.STATE_CLOSED) {
            return;
        }
        Integer prev = lastState.put(area, state);
        if (prev != null && prev == state) return;

        // Gate on ACC OFF. If the snapshot doesn't yet have a powerLevel
        // (very early boot), skip — we'd rather miss a transient edge than
        // fire while driving.
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        if (snap == null) return;
        if (snap.powerLevel != BodyworkConstants.POWER_LEVEL_OFF) return;

        boolean opened = state == BodyworkConstants.STATE_OPEN;
        String category = opened
                ? "vehicle.security.door.opened"
                : "vehicle.security.door.closed";
        String areaLabel = areaLabel(area);

        // Append the user-defined safe-zone name when available so a buzz on
        // the phone reads "Front-left door opened — While parked at Home"
        // instead of an anonymous "While parked". When the car is outside any
        // configured zone, the "at <zone>" suffix is omitted entirely; we
        // never make up a location.
        String body = Messages.get("notifications.while_parked");
        try {
            String zone = com.overdrive.app.surveillance.SafeLocationManager
                    .getInstance().getCurrentZoneName();
            if (zone != null && !zone.isEmpty()) {
                body = Messages.get("notifications.while_parked_at", zone);
            }
        } catch (Throwable ignored) { /* fall back to plain body */ }

        JSONObject data = new JSONObject();
        try {
            data.put("area", area);
            data.put("areaLabel", areaLabel);
            data.put("state", state);
        } catch (Exception ignored) {}

        try {
            NotificationBus.get().publish(new NotificationEvent(
                    category,
                    NotificationEvent.Severity.WARN,
                    opened
                            ? Messages.get("notifications.door_opened", areaLabel)
                            : Messages.get("notifications.door_closed", areaLabel),
                    body,
                    "door-" + area + "-" + (opened ? "open" : "close"),
                    null,
                    data));
        } catch (Throwable ignored) {}
    }

    private static String areaLabel(int area) {
        switch (area) {
            case AREA_LF: return Messages.get("notifications.area_front_left");
            case AREA_RF: return Messages.get("notifications.area_front_right");
            case AREA_LR: return Messages.get("notifications.area_rear_left");
            case AREA_RR: return Messages.get("notifications.area_rear_right");
            case AREA_HOOD: return Messages.get("notifications.area_hood");
            case AREA_TRUNK: return Messages.get("notifications.area_trunk");
            case AREA_FUEL_CAP: return Messages.get("notifications.area_fuel_cap");
            default: return Messages.get("notifications.area_door_n", area);
        }
    }
}
