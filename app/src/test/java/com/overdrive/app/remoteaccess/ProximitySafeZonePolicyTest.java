package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Geographic command authorization, including queued-command revocation. */
public class ProximitySafeZonePolicyTest {
    private static final ProximitySafeZonePolicy.Zone HOME =
            new ProximitySafeZonePolicy.Zone("home", "Home", 12.0, 77.0, 100);
    private static final ProximitySafeZonePolicy.Zone OFFICE =
            new ProximitySafeZonePolicy.Zone("office", "Office", 13.0, 77.0, 150);
    private static final ProximitySafeZonePolicy.Fix INSIDE =
            new ProximitySafeZonePolicy.Fix(12.0, 77.0, 5.0, 0);
    private static final ProximitySafeZonePolicy.Fix OUTSIDE =
            new ProximitySafeZonePolicy.Fix(12.01, 77.0, 5.0, 0);

    private static ProximitySafeZonePolicy.Result both(ProximitySafeZonePolicy.Fix fix) {
        return ProximitySafeZonePolicy.evaluate(true, true, true,
                Collections.singletonList("home"), Collections.singletonList(HOME), fix);
    }

    @Test public void legacyDefaultDoesNotRequireGpsOrZones() {
        ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                true, false, false, Collections.emptyList(), Collections.emptyList(), null);
        assertEquals("UNRESTRICTED", result.state);
        assertFalse(result.enabled);
        assertTrue(result.allowed("lock"));
        assertTrue(result.allowed("unlock"));
        assertEquals(-1L, result.fixAgeMs);
    }

    @Test public void masterOffBlocksEveryActionWithoutLocationInput() {
        ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                false, true, true, Collections.singletonList("home"), Collections.singletonList(HOME), OUTSIDE);
        assertEquals("DISABLED", result.state);
        assertFalse(result.enabled);
        assertFalse(result.allowed("lock"));
        assertFalse(result.allowed("unlock"));
        assertEquals(-1L, result.fixAgeMs);
        assertNull(result.lease("unlock"));
    }

    @Test public void eachActionCanBeRestrictedIndependently() {
        for (boolean lock : new boolean[] {false, true}) {
            ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                    true, lock, !lock, Collections.singletonList("home"), Collections.singletonList(HOME), INSIDE);
            assertEquals("INSIDE", result.state);
            assertEquals(!lock, result.allowed("lock"));
            assertEquals(lock, result.allowed("unlock"));
            assertEquals("not_restricted", result.actionReason(lock ? "unlock" : "lock"));
        }
    }

    @Test public void onlyReliableOutsidePositionPermitsRestrictedActions() {
        ProximitySafeZonePolicy.Result inside = both(INSIDE), outside = both(OUTSIDE);
        assertEquals("INSIDE", inside.state);
        assertFalse(inside.allowed("lock"));
        assertFalse(inside.allowed("unlock"));
        assertEquals("OUTSIDE", outside.state);
        assertTrue(outside.allowed("lock"));
        assertTrue(outside.allowed("unlock"));
    }

    @Test public void parkedBluetoothRequiresAnUnlockRestrictionAndConfidentInsideFix() {
        assertTrue(both(INSIDE).shouldDisableBluetooth());
        assertTrue(ProximitySafeZonePolicy.evaluate(true, false, true,
                Collections.singletonList("home"), Collections.singletonList(HOME), INSIDE)
                .shouldDisableBluetooth());
        assertFalse(both(OUTSIDE).shouldDisableBluetooth());
        assertFalse(both(null).shouldDisableBluetooth());
        assertFalse(ProximitySafeZonePolicy.evaluate(true, true, false,
                Collections.singletonList("home"), Collections.singletonList(HOME), INSIDE)
                .shouldDisableBluetooth());
        assertFalse(ProximitySafeZonePolicy.evaluate(false, false, true,
                Collections.singletonList("home"), Collections.singletonList(HOME), INSIDE)
                .shouldDisableBluetooth());
    }

    @Test public void boundaryAndAccuracyOverlapRemainUnknown() {
        // One degree latitude is about 111 km: this is approximately 100 m.
        ProximitySafeZonePolicy.Result onBoundary = both(
                new ProximitySafeZonePolicy.Fix(12.0009, 77.0, 5.0, 0));
        assertEquals("UNKNOWN", onBoundary.state);
        assertEquals("safe_zone_boundary", onBoundary.reason);
        assertFalse(onBoundary.allowed("unlock"));
        ProximitySafeZonePolicy.Result uncertainOutside = both(
                new ProximitySafeZonePolicy.Fix(12.0013, 77.0, 30.0, 0));
        assertEquals("UNKNOWN", uncertainOutside.state);
        assertEquals("safe_zone_boundary", uncertainOutside.reason);
        assertFalse(uncertainOutside.allowed("lock"));
    }

    @Test public void anUncertainFixAtTheCenterOfSmallZoneDoesNotBecomeOutside() {
        ProximitySafeZonePolicy.Zone small =
                new ProximitySafeZonePolicy.Zone("home", "Small", 12.0, 77.0, 15);
        ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.singletonList("home"), Collections.singletonList(small),
                new ProximitySafeZonePolicy.Fix(12.0, 77.0, 10.0, 0));
        assertEquals("UNKNOWN", result.state);
        assertFalse(result.allowed("lock"));
        assertFalse(result.allowed("unlock"));
    }

    @Test public void outsideRequiresClearanceFromEverySelectedZone() {
        ProximitySafeZonePolicy.Result nearOffice = ProximitySafeZonePolicy.evaluate(
                true, true, true, Arrays.asList("home", "office"), Arrays.asList(HOME, OFFICE),
                new ProximitySafeZonePolicy.Fix(13.0, 77.0, 5.0, 0));
        assertEquals("INSIDE", nearOffice.state);
        assertFalse(nearOffice.allowed("unlock"));
        ProximitySafeZonePolicy.Result between = ProximitySafeZonePolicy.evaluate(
                true, true, true, Arrays.asList("home", "office"), Arrays.asList(HOME, OFFICE),
                new ProximitySafeZonePolicy.Fix(12.5, 77.0, 5.0, 0));
        assertEquals("OUTSIDE", between.state);
        assertTrue(between.allowed("lock"));
    }

    @Test public void unselectedZonesDoNotSuppressActions() {
        ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.singletonList("office"), Arrays.asList(HOME, OFFICE), INSIDE);
        assertEquals("OUTSIDE", result.state);
        assertTrue(result.allowed("unlock"));
    }

    @Test public void missingRemovedSelectionNeverBecomesOutside() throws Exception {
        ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                true, true, true, Arrays.asList("home", "removed"), Collections.singletonList(HOME), OUTSIDE);
        assertEquals("UNKNOWN", result.state);
        assertEquals("safe_zone_missing", result.reason);
        assertFalse(result.allowed("lock"));
        assertFalse(result.allowed("unlock"));
        JSONObject missing = result.toJson().getJSONArray("selectedZones").getJSONObject(1);
        assertEquals("removed", missing.getString("id"));
        assertTrue(missing.getBoolean("missing"));
    }

    @Test public void emptyNullDuplicateAndOversizedSelectionsFailClosed() {
        assertEquals("no_safe_zones_selected", ProximitySafeZonePolicy.evaluate(
                true, true, true, null, Collections.singletonList(HOME), OUTSIDE).reason);
        assertEquals("no_safe_zones_selected", ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.emptyList(), Collections.singletonList(HOME), OUTSIDE).reason);
        assertEquals("safe_zone_invalid", ProximitySafeZonePolicy.evaluate(
                true, true, true, Arrays.asList("home", "home"), Collections.singletonList(HOME), OUTSIDE).reason);
        assertEquals("safe_zone_invalid", ProximitySafeZonePolicy.evaluate(
                true, true, true, Arrays.asList("home", null), Collections.singletonList(HOME), OUTSIDE).reason);
        assertFalse(ProximitySafeZonePolicy.evaluate(true, true, true, Collections.nCopies(11, "home"),
                Collections.singletonList(HOME), OUTSIDE).allowed("unlock"));
    }

    @Test public void invalidOrDuplicateSelectedGeometryFailsClosed() {
        for (ProximitySafeZonePolicy.Zone invalid : new ProximitySafeZonePolicy.Zone[] {
                new ProximitySafeZonePolicy.Zone("home", "Missing coordinates", 0, 0, 100),
                new ProximitySafeZonePolicy.Zone("home", "Bad", Double.NaN, 77, 100),
                new ProximitySafeZonePolicy.Zone("home", "Bad", 91, 77, 100),
                new ProximitySafeZonePolicy.Zone("home", "Bad", 12, 181, 100),
                new ProximitySafeZonePolicy.Zone("home", "Bad", 12, 77, 14),
                new ProximitySafeZonePolicy.Zone("home", "Bad", 12, 77, 501)}) {
            assertEquals("safe_zone_invalid", ProximitySafeZonePolicy.evaluate(
                    true, true, true, Collections.singletonList("home"), Collections.singletonList(invalid), OUTSIDE).reason);
        }
        assertEquals("safe_zone_invalid", ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.singletonList("home"), Arrays.asList(HOME, HOME), OUTSIDE).reason);
    }

    @Test public void invalidCoordinateProvenanceBlocksEvenPlausibleNumericDefaults() {
        ProximitySafeZonePolicy.Zone missingLatitude =
                new ProximitySafeZonePolicy.Zone("home", "Incomplete", 0, 77, 100, false);
        ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.singletonList("home"),
                Collections.singletonList(missingLatitude), OUTSIDE);
        assertEquals("safe_zone_invalid", result.reason);
        assertFalse(result.allowed("lock"));
        assertFalse(result.allowed("unlock"));
        assertFalse(result.permits(both(OUTSIDE).lease("lock")));
    }

    @Test public void cachedUnstampedOrFutureAgeCannotAuthorizeAnAction() {
        ProximitySafeZonePolicy.Result missing = both(null);
        assertEquals("gps_unavailable", missing.reason);
        for (long age : new long[] {-1, Long.MIN_VALUE}) {
            ProximitySafeZonePolicy.Result unknown = both(
                    new ProximitySafeZonePolicy.Fix(12.01, 77, 5, age));
            assertEquals("gps_time_unknown", unknown.reason);
            assertFalse(unknown.allowed("unlock"));
            assertNull(unknown.lease("lock"));
        }
    }

    @Test public void gpsFreshnessBoundaryIsInclusiveWithoutWallTimeRestamping() {
        assertTrue(both(new ProximitySafeZonePolicy.Fix(12.01, 77, 5, 30_000)).allowed("unlock"));
        for (long age : new long[] {30_001, Long.MAX_VALUE}) {
            ProximitySafeZonePolicy.Result stale = both(
                    new ProximitySafeZonePolicy.Fix(12.01, 77, 5, age));
            assertEquals("gps_fix_stale", stale.reason);
            assertFalse(stale.allowed("lock"));
        }
    }

    @Test public void missingPoorOrNonfiniteAccuracyFailsClosed() {
        for (double accuracy : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 50.001}) {
            ProximitySafeZonePolicy.Result result = both(
                    new ProximitySafeZonePolicy.Fix(12.01, 77, accuracy, 0));
            assertEquals("UNKNOWN", result.state);
            assertFalse(result.allowed("lock"));
            assertFalse(result.allowed("unlock"));
        }
        assertTrue(both(new ProximitySafeZonePolicy.Fix(12.01, 77, 50, 0)).allowed("unlock"));
    }

    @Test public void invalidCoordinatesFailClosed() {
        for (double[] point : new double[][] {
                {0, 0}, {Double.NaN, 77}, {12, Double.NEGATIVE_INFINITY}, {-91, 77}, {12, -181}}) {
            assertEquals("gps_unavailable", both(
                    new ProximitySafeZonePolicy.Fix(point[0], point[1], 5, 0)).reason);
        }
    }

    @Test public void absentSerializedZoneCoordinatesDoNotAuthorizeOutside() throws Exception {
        JSONObject incomplete = new JSONObject().put("id", "home").put("name", "Incomplete").put("radiusM", 100);
        ProximitySafeZonePolicy.Zone malformed = new ProximitySafeZonePolicy.Zone(
                incomplete.getString("id"), incomplete.getString("name"),
                incomplete.optDouble("lat", 0), incomplete.optDouble("lng", 0), incomplete.getInt("radiusM"));
        ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.singletonList("home"), Collections.singletonList(malformed), OUTSIDE);
        assertEquals("safe_zone_invalid", result.reason);
        assertFalse(result.allowed("lock"));
        assertFalse(result.allowed("unlock"));
    }

    @Test public void aSingleZeroCoordinateRemainsValid() {
        for (double[] coordinate : new double[][] {{0, 77}, {12, 0}}) {
            ProximitySafeZonePolicy.Zone zone = new ProximitySafeZonePolicy.Zone(
                    "home", "Valid", coordinate[0], coordinate[1], 100);
            ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                    true, true, true, Collections.singletonList("home"), Collections.singletonList(zone),
                    new ProximitySafeZonePolicy.Fix(coordinate[0], coordinate[1], 5, 0));
            assertEquals("INSIDE", result.state);
        }
    }

    @Test public void queuedLeaseIsRevokedByInsideBoundaryUnknownAndExpiry() {
        ProximitySafeZonePolicy.Lease lease = both(OUTSIDE).lease("unlock");
        assertNotNull(lease);
        assertTrue(both(OUTSIDE).permits(lease));
        assertFalse(both(INSIDE).permits(lease));
        assertFalse(both(new ProximitySafeZonePolicy.Fix(12.0009, 77, 5, 0)).permits(lease));
        assertFalse(both(null).permits(lease));
        assertFalse(both(new ProximitySafeZonePolicy.Fix(12.01, 77, 5, 30_001)).permits(lease));
        assertFalse(both(OUTSIDE).permits(null));
    }

    @Test public void refreshedEligibleOutsideFixDoesNotDiscardAQueuedLease() {
        ProximitySafeZonePolicy.Lease lease = both(OUTSIDE).lease("lock");
        assertTrue(both(new ProximitySafeZonePolicy.Fix(12.02, 77, 8, 100)).permits(lease));
    }

    @Test public void selectionRemovalGeometryAndRuleEditsRevokeQueuedLease() {
        ProximitySafeZonePolicy.Lease lease = both(OUTSIDE).lease("lock");
        for (ProximitySafeZonePolicy.Zone edited : new ProximitySafeZonePolicy.Zone[] {
                new ProximitySafeZonePolicy.Zone("home", "Renamed", 12, 77, 100),
                new ProximitySafeZonePolicy.Zone("home", "Home", 12.0001, 77, 100),
                new ProximitySafeZonePolicy.Zone("home", "Home", 12, 77.0001, 100),
                new ProximitySafeZonePolicy.Zone("home", "Home", 12, 77, 110)}) {
            ProximitySafeZonePolicy.Result current = ProximitySafeZonePolicy.evaluate(
                    true, true, true, Collections.singletonList("home"), Collections.singletonList(edited), OUTSIDE);
            assertTrue(current.allowed("lock"));
            assertFalse(current.permits(lease));
        }
        assertFalse(ProximitySafeZonePolicy.evaluate(true, true, true, Collections.singletonList("office"),
                Collections.singletonList(OFFICE), OUTSIDE).permits(lease));
        assertFalse(ProximitySafeZonePolicy.evaluate(true, true, true, Collections.singletonList("home"),
                Collections.emptyList(), OUTSIDE).permits(lease));
        assertFalse(ProximitySafeZonePolicy.evaluate(false, true, true, Collections.singletonList("home"),
                Collections.singletonList(HOME), OUTSIDE).permits(lease));
        assertFalse(ProximitySafeZonePolicy.evaluate(true, false, true, Collections.singletonList("home"),
                Collections.singletonList(HOME), OUTSIDE).permits(lease));
    }

    @Test public void selectedOrderAndUnselectedEditsDoNotChangeTheLease() {
        ProximitySafeZonePolicy.Result initial = ProximitySafeZonePolicy.evaluate(
                true, true, true, Arrays.asList("home", "office"), Arrays.asList(HOME, OFFICE), OUTSIDE);
        ProximitySafeZonePolicy.Result reordered = ProximitySafeZonePolicy.evaluate(
                true, true, true, Arrays.asList("office", "home"), Arrays.asList(OFFICE, HOME), OUTSIDE);
        assertTrue(reordered.permits(initial.lease("lock")));
        ProximitySafeZonePolicy.Zone irrelevant =
                new ProximitySafeZonePolicy.Zone("other", "Other", 12.01, 77, 100);
        ProximitySafeZonePolicy.Result unselected = ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.singletonList("home"), Arrays.asList(HOME, irrelevant), OUTSIDE);
        assertTrue(unselected.permits(both(OUTSIDE).lease("unlock")));
    }

    @Test public void statusIncludesNamesAndActionGatesWithoutCoordinates() throws Exception {
        JSONObject result = both(OUTSIDE).toJson();
        assertEquals("OUTSIDE", result.getString("state"));
        assertEquals(30_000L, result.getLong("maxFixAgeMs"));
        assertEquals(50, result.getDouble("maxAccuracyMeters"), 0);
        assertEquals("Home", result.getJSONArray("selectedZones").getJSONObject(0).getString("name"));
        assertTrue(result.getJSONObject("unlock").getBoolean("restricted"));
        assertTrue(result.getJSONObject("lock").getBoolean("allowed"));
        assertFalse(result.has("latitude"));
        assertFalse(result.has("longitude"));
        assertFalse(result.getJSONArray("selectedZones").getJSONObject(0).has("latitude"));
        assertTrue(both(null).toJson().isNull("fixAgeMs"));
        assertTrue(both(null).toJson().isNull("accuracyMeters"));
    }

    @Test public void antimeridianAndPolarGeometryUseGreatCircleDistance() {
        ProximitySafeZonePolicy.Zone dateLine =
                new ProximitySafeZonePolicy.Zone("edge", "Date line", 0, 179.9999, 100);
        ProximitySafeZonePolicy.Result nearby = ProximitySafeZonePolicy.evaluate(
                true, true, true, Collections.singletonList("edge"), Collections.singletonList(dateLine),
                new ProximitySafeZonePolicy.Fix(0, -179.9999, 5, 0));
        assertEquals("INSIDE", nearby.state);
        ProximitySafeZonePolicy.Zone polar =
                new ProximitySafeZonePolicy.Zone("pole", "Pole", 90, 0, 100);
        assertEquals("INSIDE", ProximitySafeZonePolicy.evaluate(true, true, true,
                Collections.singletonList("pole"), Collections.singletonList(polar),
                new ProximitySafeZonePolicy.Fix(90, 120, 5, 0)).state);
    }

    @Test public void arbitraryActionNeverObtainsALease() {
        assertFalse(both(OUTSIDE).allowed("start"));
        assertNull(both(OUTSIDE).lease("start"));
        assertFalse(both(OUTSIDE).allowed(null));
    }
}
