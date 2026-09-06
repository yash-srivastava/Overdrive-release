package com.overdrive.app.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.SocHistoryDatabase;
import com.overdrive.app.trips.TripAnalyticsManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ChargingApiHandlerTest {

    @Test
    public void physicalGunOutOverridesStaleBmsConnectionInference()
            throws Exception {
        assertFalse(ChargingApiHandler.resolvePlugged(
                false, ChargingStateData.ChargingStatus.FINISHED, 1, false));
        assertFalse(ChargingApiHandler.resolvePlugged(
                false, ChargingStateData.ChargingStatus.READY, 1, false));
    }

    @Test
    public void v2lOverridesStaleBmsConnectionInference()
            throws Exception {
        assertFalse(ChargingApiHandler.resolvePlugged(
                false, ChargingStateData.ChargingStatus.FINISHED, 5, true));
        assertFalse(ChargingApiHandler.resolvePlugged(
                true, ChargingStateData.ChargingStatus.CHARGING, 2, true));
    }

    @Test
    public void exportClearsAllPositiveChargingFlags()
            throws Exception {
        ChargingApiHandler.LiveStateFlags v2l =
                ChargingApiHandler.normalizeLiveState(
                        true,
                        ChargingStateData.ChargingStatus.FINISHED,
                        true,
                        5,
                        true);
        assertFalse(v2l.charging);
        assertFalse(v2l.plugged);
        assertFalse(v2l.full);

        ChargingApiHandler.LiveStateFlags discharge =
                ChargingApiHandler.normalizeLiveState(
                        true,
                        ChargingStateData.ChargingStatus.DISCHARGING,
                        false,
                        2,
                        false);
        assertFalse(discharge.charging);
        assertFalse(discharge.plugged);
        assertFalse(discharge.full);
    }

    @Test
    public void physicalGunOutClearsRacedChargingAndFinishedFlags()
            throws Exception {
        ChargingApiHandler.LiveStateFlags flags =
                ChargingApiHandler.normalizeLiveState(
                        true,
                        ChargingStateData.ChargingStatus.FINISHED,
                        false,
                        1,
                        false);

        assertFalse(flags.charging);
        assertFalse(flags.plugged);
        assertFalse(flags.full);
    }

    @Test
    public void finishedTaperRemainsChargingButIsNotFull()
            throws Exception {
        ChargingApiHandler.LiveStateFlags flags =
                ChargingApiHandler.normalizeLiveState(
                        false,
                        ChargingStateData.ChargingStatus.FINISHED,
                        true,
                        2,
                        false);

        assertTrue(flags.charging);
        assertTrue(flags.plugged);
        assertFalse(flags.full);
    }

    @Test
    public void terminalStateCannotBeOverriddenByRacedFusedPositive() {
        ChargingApiHandler.LiveStateFlags finished =
                ChargingApiHandler.normalizeLiveState(
                        true,
                        ChargingStateData.ChargingStatus.FINISHED,
                        false,
                        2,
                        false);
        assertFalse(finished.charging);
        assertTrue(finished.plugged);
        assertTrue(finished.full);

        for (ChargingStateData.ChargingStatus status :
                new ChargingStateData.ChargingStatus[] {
                        ChargingStateData.ChargingStatus.READY,
                        ChargingStateData.ChargingStatus.TERMINATED,
                        ChargingStateData.ChargingStatus.TIMEOUT,
                        ChargingStateData.ChargingStatus.ERROR
                }) {
            ChargingApiHandler.LiveStateFlags flags =
                    ChargingApiHandler.normalizeLiveState(
                            true, status, false, 2, false);
            assertFalse(status.name(), flags.charging);
        }
    }

    @Test
    public void powerPublicationPreservesActiveProvenanceAndClearsStoppedValue() {
        ChargingStateData state = new ChargingStateData(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        state.updateChargingPower(
                3.2, "cluster", 4567L,
                ChargingStateData.PowerQuality.MEASURED, 0.9);

        ChargingApiHandler.PowerPublication active =
                ChargingApiHandler.normalizePowerPublication(true, state);
        assertEquals(3.2, active.powerKw, 0.0);
        assertEquals("cluster", active.source);
        assertEquals(4567L, active.observedAtMs);
        assertEquals("MEASURED", active.quality);
        assertEquals(0.9, active.confidence, 0.0);

        ChargingApiHandler.PowerPublication stopped =
                ChargingApiHandler.normalizePowerPublication(false, state);
        assertEquals(0.0, stopped.powerKw, 0.0);
        assertFalse(stopped.isEstimated);
        assertEquals("none", stopped.source);
        assertEquals(0L, stopped.observedAtMs);
        assertEquals("UNKNOWN", stopped.quality);
        assertEquals(0.0, stopped.confidence, 0.0);
    }

    @Test
    public void powerPublicationRejectsOutOfDomainStateEvenIfFieldWasMutated() {
        ChargingStateData state = new ChargingStateData(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        state.chargingPowerKW = 500.01;
        state.powerSource = "chargingDevice";
        state.powerQuality = ChargingStateData.PowerQuality.MEASURED;
        state.powerConfidence = 1.0;

        ChargingApiHandler.PowerPublication publication =
                ChargingApiHandler.normalizePowerPublication(true, state);
        assertEquals(0.0, publication.powerKw, 0.0);
        assertEquals("none", publication.source);
        assertEquals("UNKNOWN", publication.quality);
    }

    @Test
    public void livePublicationPreservesSessionEnergyQualityOnBothSurfaces()
            throws Exception {
        ChargingStateData state = new ChargingStateData(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        state.updateChargingPower(
                6.1, "chargingDevice", 1234L,
                ChargingStateData.PowerQuality.MEASURED, 1.0);
        ChargingApiHandler.PowerPublication power =
                ChargingApiHandler.normalizePowerPublication(true, state);

        Constructor<ChargingApiHandler.LivePublication> constructor =
                ChargingApiHandler.LivePublication.class.getDeclaredConstructor(
                        ChargingStateData.class,
                        boolean.class, boolean.class,
                        boolean.class, boolean.class,
                        double.class, double.class,
                        boolean.class, boolean.class, String.class,
                        int.class,
                        ChargingApiHandler.PowerPublication.class,
                        ChargingDetector.StateSnapshot.class);
        constructor.setAccessible(true);
        ChargingApiHandler.LivePublication publication =
                constructor.newInstance(
                        state,
                        true, true, false, false,
                        52.0, 1.4,
                        true, false,
                        SessionEnergyResolver.SRC_INTEGRATED,
                        45,
                        power,
                        null);

        JSONObject liveJson = publication.toLiveJson();
        assertTrue(liveJson.has("rangeKm"));
        assertTrue(liveJson.has("sohPercent"));
        for (JSONObject json : new JSONObject[] {
                liveJson, publication.toStatusJson()
        }) {
            assertEquals(1.4, json.getDouble("sessionKwh"), 0.0);
            assertTrue(json.getBoolean("sessionEnergyIncomplete"));
            assertTrue(json.getBoolean("sessionEnergyEstimated"));
            assertEquals(SessionEnergyResolver.SRC_INTEGRATED,
                    json.getString("sessionEnergySource"));
        }

        ChargingApiHandler.LivePublication stateLess =
                constructor.newInstance(
                        null,
                        true, true, false, false,
                        52.0, 2.2,
                        false, false,
                        SessionEnergyResolver.SRC_METERED,
                        30,
                        ChargingApiHandler.normalizePowerPublication(
                                true, null),
                        null);
        JSONObject stateLessStatus = stateLess.toStatusJson();
        assertTrue(stateLessStatus.getBoolean("charging"));
        assertTrue(stateLessStatus.getBoolean("plugged"));
        assertEquals(2.2,
                stateLessStatus.getDouble("sessionKwh"), 0.0);
        assertEquals(0.0,
                stateLessStatus.getDouble("powerKw"), 0.0);
        assertEquals("Unavailable",
                stateLessStatus.getString("stateName"));
    }

    @Test
    public void positiveGunAndUnavailableFallbackRemainSupported()
            throws Exception {
        assertTrue(ChargingApiHandler.resolvePlugged(
                false, ChargingStateData.ChargingStatus.IDLE, 2, false));
        assertFalse(ChargingApiHandler.resolvePlugged(
                false, ChargingStateData.ChargingStatus.READY,
                BydVehicleData.UNAVAILABLE, false));
        assertTrue(ChargingApiHandler.resolvePlugged(
                false, ChargingStateData.ChargingStatus.FINISHED,
                BydVehicleData.UNAVAILABLE, false));
        assertTrue(ChargingApiHandler.resolvePlugged(
                false, ChargingStateData.ChargingStatus.SCHEDULED,
                BydVehicleData.UNAVAILABLE, false));
    }

    @Test
    public void currencyValidationMatchesDatabaseColumnBeforeEitherApiMutates()
            throws Exception {
        assertNull(ChargingApiHandler.validateCurrency(
                new JSONObject().put("currency", "12345678")));
        assertEquals("Currency must be at most 8 characters",
                ChargingApiHandler.validateCurrency(
                        new JSONObject().put("currency", "123456789")));

        TrackingManager manager = new TrackingManager(true);
        ChargingApiHandler handler = new ChargingApiHandler(manager);

        JSONObject global = handler.handleRequest(
                "/api/charging/config", "POST", null,
                new JSONObject().put("currency", "123456789").toString());
        assertEquals(400, global.optInt("_status"));
        assertEquals("USD", manager.config.getCurrency());
        assertEquals(0, manager.notifications);

        JSONObject tariff = handler.handleRequest(
                "/api/charging/tariffs", "POST", null,
                new JSONObject()
                        .put("acRate", 1)
                        .put("currency", "123456789")
                        .toString());
        assertEquals(400, tariff.optInt("_status"));
        assertEquals(0, manager.notifications);
    }

    @Test
    public void failedConfigSaveLeavesLiveValuesAndNotificationUntouched()
            throws Exception {
        TrackingManager manager = new TrackingManager(false);
        ChargingApiHandler handler = new ChargingApiHandler(manager);

        JSONObject response = handler.handleRequest(
                "/api/charging/config", "POST", null,
                new JSONObject()
                        .put("enabled", true)
                        .put("electricityRate", 9.5)
                        .put("currency", "EUR")
                        .toString());

        assertEquals(500, response.optInt("_status"));
        assertFalse(manager.config.isEnabled());
        assertEquals(2.5, manager.config.getElectricityRate(), 0);
        assertEquals("USD", manager.config.getCurrency());
        assertEquals(0, manager.notifications);
    }

    @Test
    public void durableConfigSavePublishesThenNotifiesExactlyOnce()
            throws Exception {
        TrackingManager manager = new TrackingManager(true);
        ChargingApiHandler handler = new ChargingApiHandler(manager);

        JSONObject response = handler.handleRequest(
                "/api/charging/config", "POST", null,
                new JSONObject()
                        .put("enabled", true)
                        .put("electricityRate", 9.5)
                        .put("currency", "EUR")
                        .toString());

        assertTrue(response.optBoolean("success"));
        assertTrue(manager.durableConfig.isEnabled());
        assertEquals(9.5, manager.durableConfig.getElectricityRate(), 0);
        assertEquals("EUR", manager.durableConfig.getCurrency());
        assertEquals(1, manager.notifications);
    }

    @Test
    public void chargingPricingPostPublishesToTripsRuntimeCache()
            throws Exception {
        TrackingManager manager = new TrackingManager(true);
        TrackingTripManager trips = new TrackingTripManager();
        ChargingApiHandler handler =
                new ChargingApiHandler(manager, trips);

        JSONObject response = handler.handleRequest(
                "/api/charging/config", "POST", null,
                new JSONObject()
                        .put("electricityRate", 9.5)
                        .put("currency", "EUR")
                        .toString());

        assertTrue(response.optBoolean("success"));
        assertEquals(9.5, trips.publishedRate(), 0);
        assertEquals("EUR", trips.publishedCurrency());
    }

    @Test
    public void chargingPricingResolvesTripManagerAtCommitTime()
            throws Exception {
        TrackingManager manager = new TrackingManager(true);
        AtomicReference<TripAnalyticsManager> availableManager =
                new AtomicReference<>();
        ChargingApiHandler handler =
                new ChargingApiHandler(
                        manager, availableManager::get);
        TrackingTripManager trips = new TrackingTripManager();

        // The handler exists while Trip Analytics is still starting.
        availableManager.set(trips);
        JSONObject response = handler.handleRequest(
                "/api/charging/config", "POST", null,
                new JSONObject()
                        .put("electricityRate", 8.75)
                        .put("currency", "GBP")
                        .toString());

        assertTrue(response.optBoolean("success"));
        assertEquals(8.75, trips.publishedRate(), 0);
        assertEquals("GBP", trips.publishedCurrency());
    }

    @Test
    public void bootstrapPeriodsDefaultToActiveControlsAndPreserveCustomRange() {
        Map<String, String> defaults =
                ChargingApiHandler.bootstrapPeriodParams(
                        new HashMap<>());
        assertEquals("7", defaults.get("days"));

        Map<String, String> requested = new HashMap<>();
        requested.put("days", "30");
        assertEquals("30",
                ChargingApiHandler.bootstrapPeriodParams(
                        requested).get("days"));

        Map<String, String> custom = new HashMap<>();
        custom.put("from", "100");
        custom.put("to", "200");
        Map<String, String> customPeriod =
                ChargingApiHandler.bootstrapPeriodParams(custom);
        assertEquals("100", customPeriod.get("from"));
        assertEquals("200", customPeriod.get("to"));
        assertFalse(customPeriod.containsKey("days"));
        assertEquals("168", ChargingApiHandler.bootstrapParam(
                new HashMap<>(), "hours", "168"));
    }

    @Test
    public void bootstrapFinalStoppedPublicationOverridesEarlierLiveSections()
            throws Exception {
        JSONObject staleLive = new JSONObject()
                .put("charging", true)
                .put("powerKw", 3.2)
                .put("sessionKwh", 1.4);
        JSONArray sessions = new JSONArray()
                .put(new JSONObject()
                        .put("id", 1)
                        .put("inProgress", true)
                        .put("chargingNow", true)
                        .put("livePowerKw", 3.2)
                        .put("timeToFullMin", 40)
                        .put("isEstimated", true))
                .put(new JSONObject()
                        .put("id", 2)
                        .put("inProgress", false)
                        .put("chargingNow", true)
                        .put("timeToFullMin", 15));
        JSONObject bootstrap = new JSONObject()
                .put("summary", new JSONObject()
                        .put("summary", new JSONObject()
                                .put("periodEnergyKwh", 9.5)
                                .put("live", staleLive)))
                .put("sessions", new JSONObject()
                        .put("sessions", sessions));
        JSONObject finalLive = new JSONObject()
                .put("charging", false)
                .put("plugged", false)
                .put("powerKw", 0)
                .put("sessionKwh", JSONObject.NULL);

        ChargingApiHandler.overwriteBootstrapLiveState(
                bootstrap, finalLive);

        JSONObject summary = bootstrap.getJSONObject("summary")
                .getJSONObject("summary");
        assertEquals(9.5, summary.getDouble("periodEnergyKwh"), 0);
        assertFalse(summary.getJSONObject("live")
                .getBoolean("charging"));
        assertEquals(0.0, summary.getJSONObject("live")
                .getDouble("powerKw"), 0);
        assertFalse(sessions.getJSONObject(0)
                .getBoolean("chargingNow"));
        assertTrue(sessions.getJSONObject(0)
                .isNull("livePowerKw"));
        assertTrue(sessions.getJSONObject(0)
                .isNull("timeToFullMin"));
        assertFalse(sessions.getJSONObject(0)
                .getBoolean("isEstimated"));
        assertFalse(sessions.getJSONObject(1)
                .getBoolean("chargingNow"));
        assertEquals(15, sessions.getJSONObject(1)
                .getInt("timeToFullMin"));
    }

    @Test
    public void bootstrapFinalActivePublicationKeepsCurrentSessionMarker()
            throws Exception {
        JSONArray sessions = new JSONArray()
                .put(new JSONObject()
                        .put("id", 1)
                        .put("inProgress", true)
                        .put("chargingNow", true));
        JSONObject bootstrap = new JSONObject()
                .put("summary", new JSONObject()
                        .put("summary", new JSONObject()
                                .put("live", new JSONObject()
                                        .put("charging", false))))
                .put("sessions", new JSONObject()
                        .put("sessions", sessions));
        JSONObject finalLive = new JSONObject()
                .put("charging", true)
                .put("powerKw", 3.0)
                .put("timeToFullMin", 25)
                .put("isEstimated", true);

        ChargingApiHandler.overwriteBootstrapLiveState(
                bootstrap, finalLive);

        assertTrue(bootstrap.getJSONObject("summary")
                .getJSONObject("summary")
                .getJSONObject("live")
                .getBoolean("charging"));
        assertTrue(sessions.getJSONObject(0)
                .getBoolean("chargingNow"));
        assertEquals(3.0, sessions.getJSONObject(0)
                .getDouble("livePowerKw"), 0);
        assertEquals(25, sessions.getJSONObject(0)
                .getInt("timeToFullMin"));
        assertTrue(sessions.getJSONObject(0)
                .getBoolean("isEstimated"));
    }

    @Test
    public void zeroRepriceReturnIsConfirmedComplete()
            throws Exception {
        ChargingApiHandler.RepriceOutcome outcome =
                ChargingApiHandler.completedReprice(0);
        JSONObject response = new JSONObject()
                .put("success", true);

        ChargingApiHandler.appendRepricing(response, outcome);

        assertTrue(response.getBoolean("success"));
        assertEquals(0, response.getInt("repriced"));
        assertEquals("complete",
                response.getString("repricingStatus"));
        assertTrue(response.getBoolean("repricingConfirmed"));
        assertTrue(response.getBoolean("repricingDurable"));
    }

    @Test
    public void durableRetryIsReportedPendingWithoutZeroCount()
            throws Exception {
        JSONObject response = new JSONObject()
                .put("success", true);
        ChargingApiHandler.appendRepricing(
                response,
                ChargingApiHandler.classifyRepriceFailure(
                        new IllegalStateException(
                                "Tariff repricing is pending durable replay")));

        assertTrue(response.getBoolean("success"));
        assertEquals("pending",
                response.getString("repricingStatus"));
        assertTrue(response.isNull("repriced"));
        assertFalse(response.getBoolean("repricingConfirmed"));
        assertTrue(response.getBoolean("repricingDurable"));
        assertTrue(response.getBoolean("repricingPending"));
    }

    @Test
    public void undurableRetryIntentReturnsPartialCommitServerError()
            throws Exception {
        JSONObject response = new JSONObject()
                .put("success", true);
        ChargingApiHandler.appendRepricing(
                response,
                ChargingApiHandler.classifyRepriceFailure(
                        new IllegalStateException(
                                "tariff repricing intent was not durable")));

        assertFalse(response.getBoolean("success"));
        assertTrue(response.getBoolean("tariffSaved"));
        assertEquals(500, response.getInt("_status"));
        assertEquals("failed",
                response.getString("repricingStatus"));
        assertTrue(response.isNull("repriced"));
        assertFalse(response.getBoolean("repricingDurable"));
    }

    @Test
    public void summaryAndSocStorageFailuresReturnServerErrors()
            throws Exception {
        TrackingManager manager = new TrackingManager(true);
        manager.socDbOverride = closedDatabase();
        ChargingApiHandler handler = new ChargingApiHandler(manager);

        JSONObject summary = handler.handleRequest(
                "/api/charging/summary", "GET", null, null);
        JSONObject soc = handler.handleRequest(
                "/api/charging/soc", "GET", null, null);
        JSONObject overview = handler.handleRequest(
                "/api/charging/overview", "GET", null, null);

        assertEquals(500, summary.optInt("_status"));
        assertFalse(summary.optBoolean("success"));
        assertEquals(500, soc.optInt("_status"));
        assertFalse(soc.optBoolean("success"));
        assertEquals(500, overview.optInt("_status"));
        assertFalse(overview.optBoolean("success"));
    }

    @Test
    public void configReadUsesDetachedDurableSnapshot()
            throws Exception {
        TrackingManager manager = new TrackingManager(true);
        manager.durableConfig.setElectricityRate(3.5);
        manager.durableConfig.setCurrency("EUR");
        ChargingApiHandler handler = new ChargingApiHandler(manager);

        JSONObject configResponse = handler.handleRequest(
                "/api/charging/config", "GET", null, null);
        JSONObject returned = configResponse.getJSONObject("config");
        assertEquals(3.5, returned.getDouble("electricityRate"), 0);
        assertEquals("EUR", returned.getString("currency"));
        assertEquals(2.5, manager.config.getElectricityRate(), 0);
        assertEquals("USD", manager.config.getCurrency());
    }

    @Test
    public void chargingPartialUpdateStartsFreshAndPreservesSharedPricing()
            throws Exception {
        TrackingManager manager = new TrackingManager(true);
        manager.durableConfig.setElectricityRate(3.5);
        manager.durableConfig.setCurrency("EUR");
        ChargingApiHandler handler = new ChargingApiHandler(manager);

        JSONObject response = handler.handleRequest(
                "/api/charging/config", "POST", null,
                new JSONObject().put("enabled", true).toString());

        assertTrue(response.optBoolean("success"));
        assertTrue(manager.durableConfig.isEnabled());
        assertEquals(3.5, manager.durableConfig.getElectricityRate(), 0);
        assertEquals("EUR", manager.durableConfig.getCurrency());
        assertFalse(manager.lastWriteElectricityRate);
        assertFalse(manager.lastWriteCurrency);
    }

    @Test
    public void atomicMergeUpdatesBothSectionsAndPreservesSiblingKeys()
            throws Exception {
        ChargingConfig config = configThatSaves(true);
        config.setEnabled(true);
        config.setDcRate(7.5);
        config.setElectricityRate(3.25);
        config.setCurrency("INR");

        JSONObject root = new JSONObject()
                .put("chargingAnalytics", new JSONObject()
                        .put("tariffs", "keep")
                        .put("defaultTariffId", "home"))
                .put("tripAnalytics", new JSONObject()
                        .put("distanceUnit", "km"))
                .put("unrelated", new JSONObject().put("keep", true));
        config.mergeIntoRoot(root);

        JSONObject charging = root.getJSONObject("chargingAnalytics");
        JSONObject trips = root.getJSONObject("tripAnalytics");
        assertEquals("keep", charging.getString("tariffs"));
        assertEquals("home", charging.getString("defaultTariffId"));
        assertFalse(charging.has("electricityRate"));
        assertFalse(charging.has("currency"));
        assertEquals("km", trips.getString("distanceUnit"));
        assertEquals(3.25, trips.getDouble("electricityRate"), 0);
        assertEquals("INR", trips.getString("currency"));
        assertTrue(root.getJSONObject("unrelated").getBoolean("keep"));
    }

    @Test
    public void partialMergePreservesConcurrentTripPricing()
            throws Exception {
        ChargingConfig config = configThatSaves(true);
        config.setEnabled(true);
        JSONObject root = new JSONObject()
                .put("chargingAnalytics", new JSONObject())
                .put("tripAnalytics", new JSONObject()
                        .put("electricityRate", 3.5)
                        .put("currency", "EUR"));

        config.mergeIntoRoot(root, false, false);

        JSONObject charging = root.getJSONObject("chargingAnalytics");
        JSONObject trips = root.getJSONObject("tripAnalytics");
        assertEquals(3.5, trips.getDouble("electricityRate"), 0);
        assertEquals("EUR", trips.getString("currency"));
        assertFalse(charging.has("electricityRate"));
        assertFalse(charging.has("currency"));
    }

    @Test
    public void partialMergePreservesConcurrentChargingFields()
            throws Exception {
        ChargingConfig config = configThatSaves(true);
        config.setEnabled(true);
        JSONObject root = new JSONObject()
                .put("chargingAnalytics", new JSONObject()
                        .put("enabled", false)
                        .put("dcRate", 7.5)
                        .put("fastSampleSec", 24))
                .put("tripAnalytics", new JSONObject());

        config.mergeIntoRoot(
                root,
                true, false, false,
                false, false);

        JSONObject charging = root.getJSONObject("chargingAnalytics");
        assertTrue(charging.getBoolean("enabled"));
        assertEquals(7.5, charging.getDouble("dcRate"), 0);
        assertEquals(24, charging.getInt("fastSampleSec"));
    }

    @Test
    public void legacyChargingPricingMigratesIntoMissingTripKeys()
            throws Exception {
        JSONObject root = new JSONObject()
                .put("chargingAnalytics", new JSONObject()
                        .put("enabled", true)
                        .put("electricityRate", 6.75)
                        .put("currency", "INR")
                        .put("tariffs", "keep"))
                .put("tripAnalytics", new JSONObject()
                        .put("distanceUnit", "km"));

        assertTrue(ChargingConfig.pricingMirrorNeedsReconciliation(root));
        assertTrue(ChargingConfig.reconcilePricingMirror(root));

        JSONObject charging = root.getJSONObject("chargingAnalytics");
        JSONObject trips = root.getJSONObject("tripAnalytics");
        assertEquals(6.75, trips.getDouble("electricityRate"), 0);
        assertEquals("INR", trips.getString("currency"));
        assertEquals("km", trips.getString("distanceUnit"));
        assertEquals("keep", charging.getString("tariffs"));
        assertFalse(charging.has("electricityRate"));
        assertFalse(charging.has("currency"));
        assertFalse(ChargingConfig.pricingMirrorNeedsReconciliation(root));
        assertFalse(ChargingConfig.reconcilePricingMirror(root));
    }

    @Test
    public void authoritativeTripPricingRepairsStaleChargingMirror()
            throws Exception {
        JSONObject root = new JSONObject()
                .put("chargingAnalytics", new JSONObject()
                        .put("electricityRate", 2.0)
                        .put("currency", "USD"))
                .put("tripAnalytics", new JSONObject()
                        .put("electricityRate", 4.25)
                        .put("currency", "EUR"));

        assertTrue(ChargingConfig.reconcilePricingMirror(root));

        JSONObject charging = root.getJSONObject("chargingAnalytics");
        JSONObject trips = root.getJSONObject("tripAnalytics");
        assertFalse(charging.has("electricityRate"));
        assertFalse(charging.has("currency"));
        assertEquals(4.25, trips.getDouble("electricityRate"), 0);
        assertEquals("EUR", trips.getString("currency"));
    }

    @Test
    public void partialMergePromotesLegacyMirrorWithoutOverwritingIt()
            throws Exception {
        ChargingConfig config = configThatSaves(true);
        JSONObject root = new JSONObject()
                .put("chargingAnalytics", new JSONObject()
                        .put("electricityRate", 5.5)
                        .put("currency", "GBP"))
                .put("tripAnalytics", new JSONObject()
                        .put("distanceUnit", "mi"));

        config.mergeIntoRoot(root, false, false);

        JSONObject charging = root.getJSONObject("chargingAnalytics");
        JSONObject trips = root.getJSONObject("tripAnalytics");
        assertFalse(charging.has("electricityRate"));
        assertFalse(charging.has("currency"));
        assertEquals(5.5, trips.getDouble("electricityRate"), 0);
        assertEquals("GBP", trips.getString("currency"));
        assertEquals("mi", trips.getString("distanceUnit"));
    }

    @Test
    public void invalidLegacyCurrencyMigratesAsEmptyCanonicalValue()
            throws Exception {
        JSONObject root = new JSONObject()
                .put("chargingAnalytics", new JSONObject()
                        .put("electricityRate", 3.5)
                        .put("currency", "123456789"))
                .put("tripAnalytics", new JSONObject());

        assertTrue(ChargingConfig.reconcilePricingMirror(root));
        assertFalse(root.getJSONObject("chargingAnalytics").has("currency"));
        assertEquals("", root.getJSONObject("tripAnalytics")
                .getString("currency"));
    }

    @Test
    public void invalidLegacyCurrencyCannotRemainInChargingConfig()
            throws Exception {
        ChargingConfig config = configThatSaves(true);
        config.setCurrency("123456789");
        assertEquals("", config.getCurrency());
    }

    @Test
    public void clearHistoryFailureMapsToServerError()
            throws Exception {
        JSONObject response =
                ChargingApiHandler.clearHistoryResponse(-1);
        assertFalse(response.optBoolean("success"));
        assertEquals(500, response.optInt("_status"));
    }

    @Test
    public void updateSessionCostRejectsMalformedPayloads()
            throws Exception {
        ChargingApiHandler handler =
                new ChargingApiHandler(new TrackingManager(true));

        assertEquals(400, handler.handleRequest(
                "/api/charging/42/cost", "POST", null, "not json")
                .optInt("_status"));
        assertEquals(400, handler.handleRequest(
                "/api/charging/42/cost", "POST", null, "{}")
                .optInt("_status"));
        assertEquals(400, handler.handleRequest(
                "/api/charging/42/cost", "POST", null,
                "{\"cost\":\"not a number\"}")
                .optInt("_status"));
        assertEquals(400, handler.handleRequest(
                "/api/charging/42/cost", "POST", null,
                "{\"cost\":\"12.50\"}")
                .optInt("_status"));
    }

    @Test
    public void updateSessionCostRejectsNonFiniteAndUnexpectedNegativeValues()
            throws Exception {
        ChargingApiHandler handler =
                new ChargingApiHandler(new TrackingManager(true));

        for (String body : new String[] {
                "{\"cost\":NaN}",
                "{\"cost\":1e400}",
                "{\"cost\":1e100}",
                "{\"cost\":-2}"
        }) {
            JSONObject response = handler.handleRequest(
                    "/api/charging/42/cost", "POST", null, body);
            assertEquals(body, 400, response.optInt("_status"));
        }
    }

    private static ChargingConfig configThatSaves(boolean succeeds) {
        ChargingConfig config = new ChargingConfig(ignored -> succeeds);
        config.setElectricityRate(2.5);
        config.setCurrency("USD");
        return config;
    }

    private static SocHistoryDatabase closedDatabase()
            throws Exception {
        Constructor<SocHistoryDatabase> constructor =
                SocHistoryDatabase.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SocHistoryDatabase database = constructor.newInstance();

        Field initialized =
                SocHistoryDatabase.class.getDeclaredField(
                        "isInitialized");
        initialized.setAccessible(true);
        initialized.setBoolean(database, true);

        Connection closed = (Connection) Proxy.newProxyInstance(
                ChargingApiHandlerTest.class.getClassLoader(),
                new Class<?>[]{ Connection.class },
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) return true;
                    if ("close".equals(method.getName())) return null;
                    throw new UnsupportedOperationException(
                            method.getName());
                });
        Field connection =
                SocHistoryDatabase.class.getDeclaredField(
                        "connection");
        connection.setAccessible(true);
        connection.set(database, closed);
        return database;
    }

    @Test
    public void updateSessionCostReturns400ForInvalidJson() throws Exception {
        TrackingManager manager = new TrackingManager(true);
        ChargingApiHandler handler = new ChargingApiHandler(manager);
        JSONObject response = handler.handleRequest(
                "/api/charging/42/cost", "POST", null, "invalid json");
        assertEquals(400, response.optInt("_status"));
        assertEquals("Invalid JSON payload", response.optString("error"));
    }

    @Test
    public void updateSessionCostReturns400WhenCostMissing() throws Exception {
        TrackingManager manager = new TrackingManager(true);
        ChargingApiHandler handler = new ChargingApiHandler(manager);
        JSONObject response = handler.handleRequest(
                "/api/charging/42/cost", "POST", null, "{}");
        assertEquals(400, response.optInt("_status"));
        assertEquals("Missing 'cost' parameter", response.optString("error"));
    }

    // The 200/500 wiring of POST /api/charging/{id}/cost is not covered here:
    // SocHistoryDatabase's constructors are not accessible from this package, so the
    // return value of updateChargingSessionCost cannot be stubbed. Its semantics are
    // pinned by SocHistoryDatabaseManualCostTest instead.

    /**
     * Captures the tariff the handler mirrors into the trips config, which is
     * how a saved charging rate reaches trip costing.
     */
    private static final class TrackingTripManager
            extends TripAnalyticsManager {
        private final com.overdrive.app.trips.TripConfig tripConfig =
                new com.overdrive.app.trips.TripConfig();

        @Override
        public com.overdrive.app.trips.TripConfig getConfig() {
            return tripConfig;
        }

        double publishedRate() {
            return tripConfig.getElectricityRate();
        }

        String publishedCurrency() {
            return tripConfig.getCurrency();
        }
    }

    private static final class TrackingManager extends ChargingSessionManager {
        final ChargingConfig config;
        final boolean saveSucceeds;
        ChargingConfig durableConfig;
        int notifications;
        boolean lastWriteElectricityRate;
        boolean lastWriteCurrency;
        SocHistoryDatabase socDbOverride;

        TrackingManager(boolean saveSucceeds) {
            this.saveSucceeds = saveSucceeds;
            this.config = new ChargingConfig(
                    new ChargingConfig.Persistence() {
                        @Override
                        public boolean save(ChargingConfig candidate) {
                            return save(candidate, true, true);
                        }

                        @Override
                        public boolean save(
                                ChargingConfig candidate,
                                boolean writeElectricityRate,
                                boolean writeCurrency) {
                            lastWriteElectricityRate =
                                    writeElectricityRate;
                            lastWriteCurrency = writeCurrency;
                            if (!TrackingManager.this.saveSucceeds) {
                                return false;
                            }
                            ChargingConfig next = candidate.copy();
                            if (durableConfig != null) {
                                if (!writeElectricityRate) {
                                    next.setElectricityRate(
                                            durableConfig
                                                    .getElectricityRate());
                                }
                                if (!writeCurrency) {
                                    next.setCurrency(
                                            durableConfig.getCurrency());
                                }
                            }
                            durableConfig = next;
                            return true;
                        }

                        @Override
                        public ChargingConfig loadSnapshot() {
                            return durableConfig != null
                                    ? durableConfig.copy() : null;
                        }
                    });
            config.setElectricityRate(2.5);
            config.setCurrency("USD");
            durableConfig = config.copy();
        }

        @Override
        public ChargingConfig getConfig() {
            return config;
        }

        @Override
        public SocHistoryDatabase getSocDb() {
            return socDbOverride != null
                    ? socDbOverride : super.getSocDb();
        }

        @Override
        public synchronized void onConfigChanged() {
            notifications++;
        }
    }
}
