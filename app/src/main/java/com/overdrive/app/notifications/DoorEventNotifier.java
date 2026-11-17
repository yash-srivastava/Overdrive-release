package com.overdrive.app.notifications;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.bodywork.BodyworkConstants;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;

/**
 * Publishes vehicle.security.door.opened / .closed notifications when the
 * BYD bodywork HAL reports a door state change while the car is parked
 * (ACC OFF). Driving-state edges are ignored to avoid spam every time the
 * driver enters or exits.
 */
public final class DoorEventNotifier {

    // BYD bodywork area constants. The SDK publishes
    // BODYWORK_CMD_DOOR_LEFT_FRONT=1 / RIGHT_FRONT=2 / LEFT_REAR=3 / RIGHT_REAR=4
    // but field-tested telemetry on Sealion/Atto/Seal swaps L↔R on the FRONT
    // axis only — area 1 is the right front door in real life on RHD trims,
    // area 2 is the left front. The REAR axis matches the SDK declaration
    // as-is (LR=3, RR=4).
    //
    // The L↔R swap is also drive-side dependent: on LHD trims the front axis
    // matches the SDK declaration (area 1 = LF, area 2 = RF). The user-set
    // driveSide config field decides which mapping applies. Default "rhd"
    // since the legacy hardcoded swap was calibrated against RHD vehicles.
    // Rear-axis is symmetric and not affected by drive side.
    // Area ids are the shared bodywork constants (front axis is by seat, mapped to
    // physical L/R by drive side; see BodyworkConstants).

    private static final DaemonLogger logger = DaemonLogger.getInstance("DoorEventNotifier");

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
            logger.debug("door area " + area + " ignored: non-open/close state " + state);
            return;
        }

        // Gate out only when actually DRIVING (powerLevel ON/OK, i.e. >= ON),
        // matching AccMonitor's definition (isAccOn = level >= POWER_LEVEL_ON).
        // The bug this fixes: the old gate was `powerLevel != POWER_LEVEL_OFF`,
        // which also rejected level ACC(1). Opening a door on a parked car
        // (level OFF) wakes the cabin to ACC, so the door OPEN edge fires while
        // still at OFF (passes) but the CLOSE edge that follows a moment later
        // reads ACC → was dropped. That's the open-notifies-close-doesn't
        // asymmetry. Treating ACC as parked (a car at ACC is not being driven)
        // lets the close through. Doors that never wake the cabin (stay OFF)
        // were unaffected either way.
        //
        // Gate BEFORE touching lastState: a gated edge must not advance the
        // per-area dedup, or the next genuine edge would be deduped against a
        // state that was never published and silently eaten.
        //
        // If the snapshot has no powerLevel yet (very early boot), skip — we'd
        // rather miss a transient edge than fire while driving.
        BydVehicleData snap = BydDataCollector.getInstance().getData();
        if (snap == null) {
            logger.debug("door area " + area + " state " + state
                    + " skipped: no snapshot yet");
            return;
        }
        if (snap.powerLevel >= BodyworkConstants.POWER_LEVEL_ON) {
            logger.info("door area " + area + " state " + state
                    + " skipped: powerLevel="
                    + BodyworkConstants.powerLevelToString(snap.powerLevel)
                    + " (driving) — dedup state left untouched");
            return;
        }

        Integer prev = lastState.put(area, state);
        if (prev != null && prev == state) {
            logger.debug("door area " + area + " state " + state
                    + " skipped: duplicate edge");
            return;
        }
        // Legacy cars publish each area's first polled state after a restart; a close with no
        // observed open is that baseline, not an event.
        if (prev == null && state == BodyworkConstants.STATE_CLOSED
                && !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            logger.info("door area " + area + " closed at first reading: baseline, not published");
            return;
        }

        boolean opened = state == BodyworkConstants.STATE_OPEN;
        String category = opened
                ? "vehicle.security.door.opened"
                : "vehicle.security.door.closed";
        String areaLabel = areaLabel(area);

        // Append the user-defined safe-zone name when available so a buzz on
        // the phone reads "Driver front door opened — While parked at Home"
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
            // door.opened = WARN (security-relevant while parked → reaches
            // Telegram via TelegramSink). door.closed = INFO, matching the
            // category registry (vehicle.security.door.closed severity "info")
            // and TelegramSink's documented INFO/Web-Push-only contract — a
            // routine close shouldn't buzz Telegram, only the web push.
            NotificationBus.get().publish(new NotificationEvent(
                    category,
                    opened ? NotificationEvent.Severity.WARN : NotificationEvent.Severity.INFO,
                    opened
                            ? Messages.get("notifications.door_opened", areaLabel)
                            : Messages.get("notifications.door_closed", areaLabel),
                    body,
                    "door-" + area + "-" + (opened ? "open" : "close"),
                    null,
                    data));
            logger.info("published " + category + " (area " + area + ", "
                    + (opened ? "WARN" : "INFO") + ")");
        } catch (Throwable t) {
            logger.warn("publish failed for area " + area + " state " + state
                    + ": " + t.getMessage());
        }
    }

    private static String areaLabel(int area) {
        switch (area) {
            case BodyworkConstants.AREA_FRONT_DRIVER: return Messages.get(isRhd()
                    ? "notifications.area_front_right"
                    : "notifications.area_front_left");
            case BodyworkConstants.AREA_FRONT_PASSENGER: return Messages.get(isRhd()
                    ? "notifications.area_front_left"
                    : "notifications.area_front_right");
            case BodyworkConstants.AREA_REAR_LEFT: return Messages.get("notifications.area_rear_left");
            case BodyworkConstants.AREA_REAR_RIGHT: return Messages.get("notifications.area_rear_right");
            case BodyworkConstants.AREA_HOOD: return Messages.get("notifications.area_hood");
            case BodyworkConstants.AREA_TRUNK: return Messages.get("notifications.area_trunk");
            case BodyworkConstants.AREA_FUEL_CAP: return Messages.get("notifications.area_fuel_cap");
            default: return Messages.get("notifications.area_door_n", area);
        }
    }

    /**
     * Per-event read of the user's drive-side preference. The notifier runs
     * inside the camera daemon process while the picker writes from the app
     * UID; UnifiedConfigManager.forceReload() bypasses the per-process JSON
     * cache so a flip on the web page takes effect on the very next door
     * edge without a daemon restart. Default "rhd" matches getVehicle().
     */
    private static boolean isRhd() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
        } catch (Throwable ignored) {}
        try {
            String side = com.overdrive.app.config.UnifiedConfigManager
                    .getVehicle().optString("driveSide", "rhd");
            return !"lhd".equalsIgnoreCase(side);
        } catch (Throwable t) {
            return true;
        }
    }
}
