package com.overdrive.app.remoteaccess;

/** Decisions for the shared radio lease, independent of Android or sampling resources. */
final class ParkedBluetoothKeepAlivePolicy {
    enum RadioState { OFF, TURNING_ON, ON, TURNING_OFF, UNKNOWN }
    enum Action { NONE, ENABLE, DISABLE, RELEASE_OWNERSHIP }

    private ParkedBluetoothKeepAlivePolicy() {}

    static Action decide(boolean configured, boolean onOnly, boolean vehicleOff,
                         boolean vehicleOn, boolean owned, boolean forceDisable,
                         RadioState state) {
        return decide(configured, onOnly, vehicleOff, vehicleOn, owned, forceDisable,
                false, false, state);
    }

    /**
     * A daemon-confirmed safe-zone directive may suppress parked keep-alive only when
     * OverDrive already owns the radio. An adapter that was enabled independently by
     * the owner is never claimed or switched off by this location-based override.
     */
    static Action decide(boolean configured, boolean onOnly, boolean vehicleOff,
                         boolean vehicleOn, boolean owned, boolean forceDisable,
                         boolean safeZoneDisable, boolean safeZoneOwned, RadioState state) {
        if (safeZoneDisable && configured && !onOnly && vehicleOff && !vehicleOn) {
            if (!owned) return Action.NONE;
            // Keep ownership while OFF so the controller can restore it when the
            // daemon withdraws the directive or the vehicle starts.
            return state == RadioState.ON || state == RadioState.TURNING_ON
                    ? Action.DISABLE : Action.NONE;
        }
        if (safeZoneOwned) {
            // The daemon has withdrawn the inside-zone decision, location is no
            // longer trustworthy, or the vehicle has started. Return control to
            // the ordinary parked preference / vehicle Bluetooth stack.
            if ((configured && !onOnly) || (vehicleOn && !vehicleOff)) {
                if (state == RadioState.OFF || state == RadioState.TURNING_OFF)
                    return Action.ENABLE;
                return state == RadioState.ON ? Action.RELEASE_OWNERSHIP : Action.NONE;
            }
            if (vehicleOff && !vehicleOn && (state == RadioState.ON || state == RadioState.TURNING_ON))
                return owned ? Action.DISABLE : Action.NONE;
            return state == RadioState.OFF ? Action.RELEASE_OWNERSHIP : Action.NONE;
        }
        if (vehicleOn && !vehicleOff) {
            // Normal driving Bluetooth takes over without turning the adapter off.
            return owned ? Action.RELEASE_OWNERSHIP : Action.NONE;
        }
        if (state == RadioState.OFF && owned && (!configured || onOnly)) {
            return Action.RELEASE_OWNERSHIP;
        }
        if (!vehicleOff || vehicleOn) return Action.NONE;
        if (configured && !onOnly) {
            return state == RadioState.OFF ? Action.ENABLE : Action.NONE;
        }
        if (!owned && !forceDisable) return Action.NONE;
        if (state == RadioState.OFF) {
            return owned ? Action.RELEASE_OWNERSHIP : Action.NONE;
        }
        return state == RadioState.ON || state == RadioState.TURNING_ON
                ? Action.DISABLE : Action.NONE;
    }
}
