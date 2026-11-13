package com.overdrive.app.remoteaccess;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Optional action gate using live GPS, or the parked position when live GPS fails its checks,
 * and explicitly selected zone geometry. Surveillance switches and cached geofence state are
 * intentionally not inputs.
 */
public final class ProximitySafeZonePolicy {
    public static final long MAX_FIX_AGE_MS = 30_000;
    public static final double MAX_ACCURACY_METERS = 50.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private ProximitySafeZonePolicy() {}

    /** One immutable GPS publication; age must retain the original live fix time. */
    public static final class Fix {
        public final double latitude, longitude, accuracyMeters;
        public final long ageMs;

        public Fix(double latitude, double longitude, double accuracyMeters, long ageMs) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyMeters = accuracyMeters;
            this.ageMs = ageMs;
        }
    }

    /** Immutable geometry, independent of the zone's surveillance-enabled flag. */
    public static final class Zone {
        public final String id, name;
        public final double latitude, longitude;
        public final int radiusMeters;
        public final boolean geometryValid;

        public Zone(String id, String name, double latitude, double longitude, int radiusMeters) {
            this(id, name, latitude, longitude, radiusMeters, true);
        }

        public Zone(String id, String name, double latitude, double longitude, int radiusMeters, boolean geometryValid) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.radiusMeters = radiusMeters;
            this.geometryValid = geometryValid;
        }
    }

    /** Retains the selected geometry and rules while a native command is queued. */
    public static final class Lease {
        private final String action, geometryScope;

        private Lease(String action, String geometryScope) {
            this.action = action;
            this.geometryScope = geometryScope;
        }
    }

    public static final class Result {
        public final boolean enabled, lockRestricted, unlockRestricted;
        public final String state, reason;
        public final long fixAgeMs;
        public final double accuracyMeters;
        /** "live", "parked", or null when no position was supplied. */
        public final String position;
        private final List<String> selectedIds;
        private final List<Zone> selectedZones;
        private final String geometryScope;

        private Result(boolean enabled, boolean lockRestricted, boolean unlockRestricted,
                       String state, String reason, Fix fix, boolean parked, List<String> selectedIds,
                       List<Zone> selectedZones, String geometryScope) {
            this.enabled = enabled;
            this.lockRestricted = lockRestricted;
            this.unlockRestricted = unlockRestricted;
            this.state = state;
            this.reason = reason;
            this.fixAgeMs = fix == null ? -1 : fix.ageMs;
            this.accuracyMeters = fix == null ? Double.NaN : fix.accuracyMeters;
            this.position = fix == null ? null : parked ? "parked" : "live";
            this.selectedIds = Collections.unmodifiableList(new ArrayList<>(selectedIds));
            this.selectedZones = Collections.unmodifiableList(new ArrayList<>(selectedZones));
            this.geometryScope = geometryScope;
        }

        public boolean allowed(String action) {
            if (!"lock".equals(action) && !"unlock".equals(action)) return false;
            if ("DISABLED".equals(state)) return false;
            boolean restricted = "lock".equals(action) ? lockRestricted : unlockRestricted;
            return !restricted || "OUTSIDE".equals(state);
        }

        public String actionReason(String action) {
            if ("DISABLED".equals(state)) return "disabled";
            if (!"lock".equals(action) && !"unlock".equals(action)) return "invalid_action";
            return ("lock".equals(action) ? lockRestricted : unlockRestricted) ? reason : "not_restricted";
        }

        /**
         * Parked Bluetooth suppression reuses the existing Unlock-only restriction.
         * Only a confident INSIDE result authorizes suppression; boundary and unknown
         * positions deliberately keep the radio available.
         */
        public boolean shouldDisableBluetooth() {
            return enabled && unlockRestricted && "INSIDE".equals(state);
        }

        public Lease lease(String action) {
            return allowed(action) ? new Lease(action, geometryScope) : null;
        }

        /**
         * A new eligible Outside fix may refresh the position. Changed selection,
         * geometry, rules, unreliable position or entry into a zone revoke a lease.
         */
        public boolean permits(Lease lease) {
            return lease != null && allowed(lease.action)
                    && geometryScope.equals(lease.geometryScope);
        }

        public JSONObject toJson() throws Exception {
            JSONArray selections = new JSONArray();
            for (int i = 0; i < selectedIds.size(); i++) {
                Zone zone = selectedZones.get(i);
                selections.put(new JSONObject().put("id", selectedIds.get(i))
                        .put("name", zone == null ? "" : zone.name)
                        .put("missing", zone == null));
            }
            return new JSONObject().put("enabled", enabled).put("state", state).put("reason", reason)
                    .put("position", position == null ? JSONObject.NULL : position)
                    .put("fixAgeMs", fixAgeMs < 0 ? JSONObject.NULL : fixAgeMs)
                    .put("accuracyMeters", finite(accuracyMeters) && accuracyMeters > 0
                            ? accuracyMeters : JSONObject.NULL)
                    .put("maxFixAgeMs", MAX_FIX_AGE_MS).put("maxAccuracyMeters", MAX_ACCURACY_METERS)
                    .put("selectedZones", selections)
                    .put("lock", new JSONObject().put("restricted", lockRestricted)
                            .put("allowed", allowed("lock")).put("reason", actionReason("lock")))
                    .put("unlock", new JSONObject().put("restricted", unlockRestricted)
                            .put("allowed", allowed("unlock")).put("reason", actionReason("unlock")))
                    .put("bluetoothDisableEligible", shouldDisableBluetooth());
        }
    }

    /**
     * No timers or history are needed. The caller bypasses GPS entirely when
     * the master or both restrictions are off, passing a null Fix.
     */
    public static Result evaluate(boolean masterEnabled, boolean restrictLock, boolean restrictUnlock,
                                  List<String> selectedIds, List<Zone> availableZones, Fix fix) {
        return evaluate(masterEnabled, restrictLock, restrictUnlock, selectedIds, availableZones, fix, null);
    }

    /** {@code parked} stands in only when {@code fix} fails; it has no age limit, only the accuracy one. */
    public static Result evaluate(boolean masterEnabled, boolean restrictLock, boolean restrictUnlock,
                                  List<String> selectedIds, List<Zone> availableZones, Fix fix, Fix parked) {
        List<String> ids = selectedIds == null ? Collections.emptyList() : new ArrayList<>(selectedIds);
        List<Zone> selected = new ArrayList<>();
        TreeMap<String, Zone> geometry = new TreeMap<>();
        HashSet<String> duplicateZones = new HashSet<>();
        if (availableZones != null) for (Zone zone : availableZones) {
            if (zone != null && zone.id != null) {
                if (geometry.containsKey(zone.id)) duplicateZones.add(zone.id);
                geometry.put(zone.id, zone);
            }
        }
        for (String id : ids) selected.add(id == null ? null : geometry.get(id));
        String scope = geometryScope(masterEnabled, restrictLock, restrictUnlock, ids, geometry);
        if (!masterEnabled)
            return result(false, restrictLock, restrictUnlock, "DISABLED", "disabled", null, ids, selected, scope);
        if (!restrictLock && !restrictUnlock)
            return result(false, false, false, "UNRESTRICTED", "not_restricted", null, ids, selected, scope);
        if (ids.isEmpty())
            return result(true, restrictLock, restrictUnlock, "UNKNOWN", "no_safe_zones_selected",
                    fix, ids, selected, scope);
        HashSet<String> uniqueIds = new HashSet<>();
        if (ids.size() > 10)
            return result(true, restrictLock, restrictUnlock, "UNKNOWN", "safe_zone_invalid", fix, ids, selected, scope);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Zone zone = selected.get(i);
            if (id == null || id.isEmpty() || !uniqueIds.add(id) || duplicateZones.contains(id))
                return result(true, restrictLock, restrictUnlock, "UNKNOWN", "safe_zone_invalid",
                        fix, ids, selected, scope);
            if (zone == null)
                return result(true, restrictLock, restrictUnlock, "UNKNOWN", "safe_zone_missing",
                        fix, ids, selected, scope);
            if (!zone.geometryValid || !validCoordinate(zone.latitude, zone.longitude)
                    || zone.radiusMeters < 15 || zone.radiusMeters > 500)
                return result(true, restrictLock, restrictUnlock, "UNKNOWN", "safe_zone_invalid",
                        fix, ids, selected, scope);
        }
        String fixFailure = fixFailure(fix, false);
        boolean usingParked = fixFailure != null && fixFailure(parked, true) == null;
        if (usingParked) fix = parked;
        else if (fixFailure != null)
            return result(true, restrictLock, restrictUnlock, "UNKNOWN", fixFailure, fix, ids, selected, scope);
        double uncertainty = Math.max(10.0, fix.accuracyMeters * 2.0);
        boolean boundary = false;
        for (Zone zone : selected) {
            double distance = distanceMeters(fix.latitude, fix.longitude, zone.latitude, zone.longitude);
            if (distance + uncertainty <= zone.radiusMeters)
                return new Result(true, restrictLock, restrictUnlock, "INSIDE", "inside_safe_zone",
                        fix, usingParked, ids, selected, scope);
            if (distance - uncertainty <= zone.radiusMeters) boundary = true;
        }
        return new Result(true, restrictLock, restrictUnlock, boundary ? "UNKNOWN" : "OUTSIDE",
                boundary ? "safe_zone_boundary" : "outside_safe_zones", fix, usingParked, ids, selected, scope);
    }

    private static Result result(boolean enabled, boolean restrictLock, boolean restrictUnlock,
                                 String state, String reason, Fix fix, List<String> ids,
                                 List<Zone> selected, String scope) {
        return new Result(enabled, restrictLock, restrictUnlock, state, reason, fix, false, ids, selected, scope);
    }

    private static String fixFailure(Fix fix, boolean parked) {
        if (fix == null || !validCoordinate(fix.latitude, fix.longitude))
            return "gps_unavailable";
        if (fix.ageMs < 0) return "gps_time_unknown";
        if (!parked && fix.ageMs > MAX_FIX_AGE_MS) return "gps_fix_stale";
        if (!finite(fix.accuracyMeters) || fix.accuracyMeters <= 0) return "gps_accuracy_unavailable";
        return fix.accuracyMeters > MAX_ACCURACY_METERS ? "gps_accuracy_poor" : null;
    }

    private static boolean validCoordinate(double latitude, double longitude) {
        return finite(latitude) && finite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180
                && (latitude != 0 || longitude != 0);
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }

    private static double distanceMeters(double latitude, double longitude, double otherLatitude, double otherLongitude) {
        double dLat = Math.toRadians(otherLatitude - latitude);
        double dLng = Math.toRadians(otherLongitude - longitude);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(otherLatitude))
                * Math.pow(Math.sin(dLng / 2), 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(Math.min(1, Math.max(0, a))),
                Math.sqrt(Math.max(0, 1 - a)));
    }

    private static String geometryScope(boolean master, boolean lock, boolean unlock,
                                        List<String> ids, TreeMap<String, Zone> zones) {
        StringBuilder value = new StringBuilder().append(master).append('/').append(lock).append('/').append(unlock);
        ArrayList<String> sorted = new ArrayList<>();
        for (String id : ids) sorted.add(id == null ? "" : id);
        Collections.sort(sorted);
        for (String id : sorted) {
            value.append('/').append(id.length()).append(':').append(id);
            Zone zone = zones.get(id);
            if (zone == null) { value.append("/missing"); continue; }
            value.append('/').append(zone.name.length()).append(':').append(zone.name)
                    .append('/').append(Double.doubleToLongBits(zone.latitude))
                    .append('/').append(Double.doubleToLongBits(zone.longitude))
                    .append('/').append(zone.radiusMeters).append('/').append(zone.geometryValid);
        }
        return value.toString();
    }
}
