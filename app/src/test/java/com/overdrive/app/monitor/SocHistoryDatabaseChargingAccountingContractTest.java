package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.BeforeClass;

/** Source-level guards for JDBC lifecycle paths that local JVM tests cannot execute. */
public class SocHistoryDatabaseChargingAccountingContractTest {

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false)
                .withStdoutLog(false));
    }

    @Test
    public void lifecycleWritesExposeCommitFailure() throws IOException {
        String source = databaseSource();

        assertTrue(source.contains(
                "private boolean trackChargingSession(boolean isCharging"));
        assertTrue(source.contains(
                "public synchronized boolean onChargingEdge(boolean isCharging)"));
        assertTrue(source.contains(
                "public synchronized boolean finalizeStaleOpenSessions(boolean forceRecent)"));
        assertTrue(source.contains("private boolean persistCounterProgress()"));
        assertTrue(source.contains("if (p.executeUpdate() != 1)"));
    }

    @Test
    public void sessionAverageRequiresRecordedPowerPoints()
            throws IOException {
        String source = databaseSource();
        String resolver = between(source,
                "private double resolveAveragePowerKw",
                "/** Arithmetic mean of measured positive samples");
        String sampleQuery = between(source,
                "private double averageSamplePowerKw",
                "/** Max measured power");

        assertTrue(resolver.contains("averageSamplePowerKw(sessionStartTime)"));
        assertTrue(resolver.contains("coarsePowerSum / coarsePowerCount"));
        assertTrue(resolver.contains("return -1"));
        assertFalse(source.contains("timeWeightedAvgKw("));
        assertTrue(sampleQuery.contains("SELECT AVG(power_kw)"));
        assertTrue(sampleQuery.contains("power_kw > 0"));
        assertEquals(3, occurrences(source, "double avgPower = resolveAveragePowerKw("));
    }

    @Test
    public void maintenanceRejectsNonFiniteEnergyAndCost() throws Exception {
        String source = databaseSource();
        String reprice = between(source,
                "private int repriceSessionsForTariffNow",
                "private boolean reconcileRepriceCommit");
        String range = between(source,
                "private int rangeGainedFromEnergy",
                "/** Current GPS");
        String dailyCost = between(source,
                "private void adjustDailyCost",
                "// ==================== CHARGING SESSION HELPERS");

        assertTrue(reprice.contains(
                "if (!Double.isFinite(energy) || energy <= 0) continue;"));
        assertTrue(range.contains(
                "if (!Double.isFinite(energyKwh) || energyKwh <= 0) return -1;"));
        assertTrue(dailyCost.contains("if (!Double.isFinite(delta))"));
    }

    @Test
    public void sessionAverageIsArithmeticMeanOfPositiveRecordedPoints()
            throws Exception {
        Path journal = Files.createTempFile("charging-average-points", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:charging-average-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE charging_power_samples ("
                    + "session_start_time BIGINT NOT NULL, power_kw REAL)");
            statement.execute("INSERT INTO charging_power_samples"
                    + " (session_start_time, power_kw)"
                    + " VALUES (1000, 2), (1000, 4), (1000, 8), (1000, 0),"
                    + " (1000, 600),"
                    + " (2000, 100)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);

            double average = ((Number) invoke(database,
                    "resolveAveragePowerKw",
                    new Class<?>[] {long.class, double.class, int.class},
                    1000L, 0.0, 0)).doubleValue();

            assertEquals((2.0 + 4.0 + 8.0) / 3.0, average, 0.0001);
            assertEquals(8.0, ((Number) invoke(database,
                    "peakSampleKw",
                    new Class<?>[] {long.class},
                    1000L)).doubleValue(), 0.0);
            assertEquals(6.0, ((Number) invoke(database,
                    "resolveAveragePowerKw",
                    new Class<?>[] {long.class, double.class, int.class},
                    3000L, 12.0, 2)).doubleValue(), 0.0001);
            assertEquals(-1.0, ((Number) invoke(database,
                    "resolveAveragePowerKw",
                    new Class<?>[] {long.class, double.class, int.class},
                    3000L, 0.0, 0)).doubleValue(), 0.0);
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void recentChargeAndDailyRollupsPreserveEnergyQuality()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-energy-quality", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database =
                new SocHistoryDatabase(journal.toFile());
        long now = System.currentTimeMillis();
        long start = now - 26 * 60 * 60_000L;
        long end = now - 30 * 60_000L;
        try (Connection connection =
                     DriverManager.getConnection(
                             "jdbc:h2:mem:charging-energy-quality-"
                                     + System.nanoTime()
                                     + ";DB_CLOSE_DELAY=-1",
                             "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE charging_sessions ("
                            + "start_time BIGINT,"
                            + "end_time BIGINT,"
                            + "start_soc REAL,"
                            + "end_soc REAL,"
                            + "energy_added_kwh REAL,"
                            + "energy_source VARCHAR,"
                            + "energy_incomplete INTEGER)");
            statement.execute(
                    "INSERT INTO charging_sessions VALUES ("
                            + start + ", " + end
                            + ", 40, 60, 3.6, 'integrated_rate', 0)");
            statement.execute(
                    "INSERT INTO charging_sessions VALUES ("
                            + (start - 1_000L) + ", "
                            + (end - 1_000L)
                            + ", 20, 30, 1.2, 'metered_counter', 0)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);

            JSONObject recent =
                    database.getMostRecentCompletedChargingSession(24);
            assertEquals(3.6,
                    recent.getDouble("energyAddedKwh"), 0.0);
            assertEquals("integrated_rate",
                    recent.getString("energySource"));
            assertTrue(recent.getBoolean("energyEstimated"));
            assertFalse(recent.getBoolean("energyIncomplete"));

            @SuppressWarnings("unchecked")
            java.util.Map<Long, Integer> byDay =
                    (java.util.Map<Long, Integer>) invoke(
                            database,
                            "estimatedEnergySessionsByDay",
                            new Class<?>[] {
                                    Connection.class,
                                    long.class,
                                    long.class
                            },
                            connection,
                            0L,
                            Long.MAX_VALUE);
            long day = (end / 86_400_000L)
                    * 86_400_000L;
            assertEquals(Integer.valueOf(1),
                    byDay.get(Long.valueOf(day)));

            statement.execute(
                    "INSERT INTO charging_sessions VALUES ("
                            + (start + 1_000L) + ", "
                            + (end - 2_000L)
                            + ", 30, 35, 0.8, 'none', 0)");
            byDay = (java.util.Map<Long, Integer>) invoke(
                    database,
                    "estimatedEnergySessionsByDay",
                    new Class<?>[] {
                            Connection.class,
                            long.class,
                            long.class
                    },
                    connection,
                    0L,
                    Long.MAX_VALUE);
            assertEquals(Integer.valueOf(2),
                    byDay.get(Long.valueOf(day)));
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(
                    Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void legacySessionFeedPreservesUnknownAndNullQuality()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-legacy-quality", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database =
                new SocHistoryDatabase(journal.toFile());
        long now = System.currentTimeMillis();
        long start = now - 90 * 60_000L;
        long end = now - 30 * 60_000L;
        try (Connection connection =
                     DriverManager.getConnection(
                             "jdbc:h2:mem:charging-legacy-quality-"
                                     + System.nanoTime()
                                     + ";DB_CLOSE_DELAY=-1",
                             "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE charging_sessions ("
                            + "start_time BIGINT,"
                            + "end_time BIGINT,"
                            + "start_soc REAL,"
                            + "end_soc REAL,"
                            + "energy_added_kwh REAL,"
                            + "peak_power_kw REAL,"
                            + "avg_power_kw REAL,"
                            + "gun_state INTEGER,"
                            + "is_dc INTEGER,"
                            + "energy_source VARCHAR,"
                            + "energy_incomplete INTEGER)");
            statement.execute(
                    "INSERT INTO charging_sessions VALUES ("
                            + start + ", " + end
                            + ", NULL, 120, 3.6, 7.2, 6.1, 4,"
                            + " NULL, 'none', 0)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);

            JSONArray sessions = database.getChargingSessions(1);
            assertEquals(1, sessions.length());
            JSONObject session = sessions.getJSONObject(0);
            assertTrue(session.isNull("startSoc"));
            assertTrue(session.isNull("endSoc"));
            assertTrue(session.isNull("isDc"));
            assertEquals("none", session.getString("energySource"));
            assertTrue(session.getBoolean("energyEstimated"));
            assertFalse(session.getBoolean("energyIncomplete"));
            assertEquals(3.6, session.getDouble("energyAdded"), 0.0001);
            assertEquals(6.1, session.getDouble("avgPower"), 0.0001);

            JSONObject recent =
                    database.getMostRecentCompletedChargingSession(24);
            assertTrue(recent.isNull("startSoc"));
            assertTrue(recent.isNull("endSoc"));
            assertTrue(recent.getBoolean("energyEstimated"));
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(
                    Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void enrichedSessionFeedPreservesSqlNullsAndUnknownChargerType()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-v2-null-quality", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database =
                new SocHistoryDatabase(journal.toFile());
        long start = System.currentTimeMillis() - 60_000L;
        long end = start + 30_000L;
        try (Connection connection =
                     DriverManager.getConnection(
                             "jdbc:h2:mem:charging-v2-null-quality-"
                                     + System.nanoTime()
                                     + ";DB_CLOSE_DELAY=-1",
                             "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE charging_sessions ("
                            + "id BIGINT PRIMARY KEY,"
                            + "start_time BIGINT,"
                            + "end_time BIGINT,"
                            + "start_soc REAL,"
                            + "end_soc REAL,"
                            + "energy_added_kwh REAL,"
                            + "peak_power_kw REAL,"
                            + "avg_power_kw REAL,"
                            + "range_gained_km INTEGER,"
                            + "gun_state INTEGER,"
                            + "is_dc INTEGER,"
                            + "electricity_rate REAL,"
                            + "currency VARCHAR,"
                            + "session_cost REAL,"
                            + "time_to_full_min INTEGER,"
                            + "hv_temp_high REAL,"
                            + "hv_temp_low REAL,"
                            + "hv_temp_avg REAL,"
                            + "start_lat DOUBLE,"
                            + "start_lng DOUBLE,"
                            + "place_label VARCHAR,"
                            + "start_odometer_km INTEGER,"
                            + "tariff_id VARCHAR,"
                            + "tariff_label VARCHAR,"
                            + "energy_source VARCHAR,"
                            + "energy_soc_kwh DOUBLE,"
                            + "energy_incomplete INTEGER,"
                            + "counter_energy_kwh DOUBLE)");
            statement.execute(
                    "INSERT INTO charging_sessions VALUES ("
                            + "1, " + start + ", " + end + ","
                            + "NULL, NULL, 3.6, NULL, NULL, NULL, 4, NULL,"
                            + "NULL, '$', NULL, NULL, NULL, NULL, NULL,"
                            + "NULL, NULL, NULL, NULL, NULL, NULL,"
                            + "'none', NULL, 0, NULL)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);

            JSONArray sessions = database.getChargingSessionsV2RangeStrict(
                    0L, Long.MAX_VALUE, 20, 0);
            assertEquals(1, sessions.length());
            JSONObject session = sessions.getJSONObject(0);
            assertTrue(session.isNull("startSoc"));
            assertTrue(session.isNull("endSoc"));
            assertTrue(session.isNull("peakPower"));
            assertTrue(session.isNull("avgPower"));
            assertTrue(session.isNull("rangeGained"));
            assertTrue(session.isNull("isDc"));
            assertTrue(session.isNull("electricityRate"));
            assertTrue(session.isNull("cost"));
            assertTrue(session.isNull("timeToFullMin"));
            assertTrue(session.isNull("tempHigh"));
            assertTrue(session.isNull("tempLow"));
            assertTrue(session.isNull("tempAvg"));
            assertTrue(session.isNull("lat"));
            assertTrue(session.isNull("lng"));
            assertTrue(session.isNull("startOdometerKm"));
            assertTrue(session.isNull("energySocKwh"));
            assertTrue(session.isNull("energyCounterKwh"));
            assertTrue(session.isNull("isDc"));
            assertTrue(session.getBoolean("energyEstimated"));
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(
                    Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void lastTariffRatePreservesUnknownChargerType()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-last-rate-type", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database =
                new SocHistoryDatabase(journal.toFile());
        long now = System.currentTimeMillis();
        try (Connection connection =
                     DriverManager.getConnection(
                             "jdbc:h2:mem:charging-last-rate-type-"
                                     + System.nanoTime()
                                     + ";DB_CLOSE_DELAY=-1",
                             "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE charging_sessions ("
                            + "start_time BIGINT,"
                            + "end_time BIGINT,"
                            + "electricity_rate REAL,"
                            + "currency VARCHAR,"
                            + "tariff_id VARCHAR,"
                            + "tariff_label VARCHAR,"
                            + "is_dc INTEGER)");
            statement.execute(
                    "INSERT INTO charging_sessions VALUES ("
                            + (now - 60_000L) + ", " + now
                            + ", 0.25, '$', 'home', 'Home', NULL)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);

            JSONObject rate = database.getLastChargeRateStrict(1);
            assertEquals(0.25, rate.getDouble("rate"), 0.0001);
            assertTrue(rate.isNull("isDc"));
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(
                    Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void chargingSamplePowerAdmissionIsFiniteAndBounded()
            throws Exception {
        SocHistoryDatabase database = new SocHistoryDatabase(null);
        Class<?>[] signature = new Class<?>[] {double.class};
        assertEquals(Boolean.TRUE, invoke(database,
                "isValidChargingSamplePower", signature, 0.0));
        assertEquals(Boolean.TRUE, invoke(database,
                "isValidChargingSamplePower", signature, 500.0));
        assertEquals(Boolean.TRUE, invoke(database,
                "isValidChargingSamplePower", signature,
                SocHistoryDatabase.STOP_BOUNDARY_POWER_KW));
        assertEquals(Boolean.TRUE, invoke(database,
                "isValidChargingSamplePower", signature,
                SocHistoryDatabase.MISSING_RATE_BOUNDARY_POWER_KW));
        assertEquals(Boolean.TRUE, invoke(database,
                "isValidChargingSamplePower", signature,
                SocHistoryDatabase.AUXILIARY_SAMPLE_POWER_KW));
        assertEquals(Boolean.FALSE, invoke(database,
                "isValidChargingSamplePower", signature,
                Double.POSITIVE_INFINITY));
        assertEquals(Boolean.FALSE, invoke(database,
                "isValidChargingSamplePower", signature, 500.01));
        assertEquals(Boolean.FALSE, invoke(database,
                "isValidChargingSamplePower", signature, -4.0));
    }

    @Test
    public void fullReportUsesAtLeastOneChargingHistoryDay()
            throws IOException {
        String report = between(databaseSource(),
                "public synchronized JSONObject getFullReport",
                "public synchronized void setSohEstimator");
        assertTrue(report.contains(
                "Math.max(\n                    1, (int) Math.ceil(hoursBack / 24.0))"));
        assertTrue(report.contains(
                "getChargingSessions(reportDays)"));
    }

    @Test
    public void liveSessionClassificationUsesThePeakServedToTheCard()
            throws IOException {
        String live = between(databaseSource(),
                "// ---- Live enrichment for the OPEN (in-progress) session ----",
                "return o;");

        int resolvedPeak = live.indexOf(
                "double livePeak = resolvePeakKw(start, chargingPeakPower)");
        int verdict = live.indexOf(
                "int liveIsDc = currentChargingTypeVerdict()");
        int publishedPeak = live.indexOf(
                "o.put(\"peakPower\", livePeak)");

        assertTrue(resolvedPeak >= 0);
        assertTrue(verdict > resolvedPeak);
        assertTrue(publishedPeak > verdict);
        assertTrue(live.contains(
                "PricingDecision livePd = priceSession(\n"
                        + "                            liveIsDc,"));
        assertFalse(live.contains(
                "deriveIsDc(chargingGunState, chargingPeakPower)"));
        assertTrue(live.contains(
                "double liveAverage = resolveAveragePowerKw("));
        assertTrue(live.contains(
                "if (liveAverage > 0) o.put(\"avgPower\", liveAverage)"));
    }

    @Test
    public void chargerTypeUsesGunVerdictBeforePowerOnlyFallback() {
        assertEquals(0, SocHistoryDatabase.deriveIsDc(2, 359.4));
        assertEquals(1, SocHistoryDatabase.deriveIsDc(3, 15.0));
        assertEquals(-1, SocHistoryDatabase.deriveIsDc(3, 14.9));
        assertEquals(1, SocHistoryDatabase.deriveIsDc(-1, 25.0));
        assertEquals(-1, SocHistoryDatabase.deriveIsDc(-1, 24.9));
        assertEquals(1, SocHistoryDatabase.deriveIsDc(4, 25.0));
        assertEquals(-1, SocHistoryDatabase.deriveIsDc(1, 100.0));
        assertEquals(-1, SocHistoryDatabase.deriveIsDc(5, 100.0));
    }

    @Test
    public void openSessionChargerVerdictRetainsProvenPeakDuringTaper()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-open-type", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database =
                new SocHistoryDatabase(journal.toFile());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:charging-open-type-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE charging_power_samples ("
                    + "id IDENTITY PRIMARY KEY,"
                    + "session_start_time BIGINT NOT NULL,"
                    + "t BIGINT NOT NULL,"
                    + "power_kw REAL,"
                    + "soc REAL,"
                    + "temp REAL,"
                    + "temp_high REAL,"
                    + "temp_low REAL)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);
            setField(database, "chargingAnalyticsEnabled", true);
            setField(database, "wasCharging", true);
            setField(database, "chargingStartTime", 1000L);
            setField(database, "lastAllocatedChargingStartMs", 1000L);
            setField(database, "chargingGunState", 3);
            setField(database, "chargingPeakPower", 8.0);

            assertEquals(ChargingTypeClassifier.UNKNOWN,
                    database.getOpenChargingSessionTypeVerdict());
            assertTrue(database.observeEstimatedChargingTypeEvidence(82.0));
            assertEquals(ChargingTypeClassifier.DC,
                    database.getOpenChargingSessionTypeVerdict());
            assertEquals(8.0,
                    ((Number) getField(database, "chargingPeakPower")).doubleValue(),
                    0.0);

            // Keep the measured-peak path independently covered below.
            setField(database, "chargingTypeVerdict",
                    ChargingTypeClassifier.UNKNOWN);
            assertTrue(database.recordChargingSample(
                    1000L, 2000L, 40.0, 50.0, 30.0, 32.0, 28.0));
            statement.execute("INSERT INTO charging_power_samples"
                    + " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low)"
                    + " VALUES (1000, 2100, 600, 50, 30, 32, 28)");

            assertEquals(ChargingTypeClassifier.DC,
                    database.getOpenChargingSessionTypeVerdict());
            assertEquals(40.0,
                    ((Number) getField(database, "chargingPeakPower")).doubleValue(),
                    0.0);

            // A restart can restore an older coarse peak from the lifecycle journal while the
            // fine sample is already durable. Startup reconciliation must rebuild the cache once.
            setField(database, "chargingPeakPower", 8.0);
            invoke(database, "reconcileRecoveredActivePowerStatsWithDatabase",
                    new Class<?>[0]);
            assertEquals(40.0,
                    ((Number) getField(database, "chargingPeakPower")).doubleValue(),
                    0.0);
            assertEquals(ChargingTypeClassifier.DC,
                    database.getOpenChargingSessionTypeVerdict());

            String verdictMethod = between(databaseSource(),
                    "public synchronized int getOpenChargingSessionTypeVerdict",
                    "/** Durable H2 truth");
            assertFalse(verdictMethod.contains("resolvePeakKw("));
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void chargingSamplesSerializePartialThermalDataWithoutInvalidJson()
            throws IOException {
        String samples = between(databaseSource(),
                "public synchronized JSONArray getChargingSamplesStrict(long id)",
                "private Connection requireChargingHistoryReadConnection");

        assertTrue(samples.contains("power_kw = ? OR power_kw = ?"));
        assertTrue(samples.contains("MISSING_RATE_BOUNDARY_POWER_KW"));
        assertTrue(samples.contains("AUXILIARY_SAMPLE_POWER_KW"));
        assertTrue(samples.contains("double sampleTemp = rs.getDouble(\"temp\")"));
        assertTrue(samples.contains("!isFinite(sampleSoc)"));
        assertTrue(samples.contains("sampleSoc < 0 || sampleSoc > 100"));
            assertTrue(samples.contains("samplePower > 0"));
            assertTrue(samples.contains("samplePower <= 500"));
        assertTrue(samples.contains("isFinite(sampleTemp)"));
        assertTrue(samples.contains(
                "double sampleTempHigh = rs.getDouble(\"temp_high\")"));
        assertTrue(samples.contains("isFinite(sampleTempHigh)"));
        assertTrue(samples.contains(
                "double sampleTempLow = rs.getDouble(\"temp_low\")"));
        assertTrue(samples.contains("isFinite(sampleTempLow)"));
    }

    @Test
    public void missingRateRowsExposeThermalDataButNeverExposeSentinelPower()
            throws Exception {
        Path journal = Files.createTempFile("charging-missing-rate-thermal", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:charging-missing-rate-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE charging_sessions (id BIGINT PRIMARY KEY,"
                    + " start_time BIGINT NOT NULL)");
            statement.execute("CREATE TABLE charging_power_samples ("
                    + "id IDENTITY PRIMARY KEY, session_start_time BIGINT NOT NULL,"
                    + "t BIGINT NOT NULL, power_kw REAL, soc REAL, temp REAL,"
                    + "temp_high REAL, temp_low REAL)");
            statement.execute("INSERT INTO charging_sessions (id, start_time) VALUES (1, 1000)");
            statement.execute("INSERT INTO charging_power_samples"
                    + " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low)"
                    + " VALUES (1000, 1100, 6.2, 40, 33, 35, 32),"
                    + " (1000, 1200, -2, 41, 33.5, 35.5, 32.5),"
                    + " (1000, 1300, -3, 42, 34, 36, 33),"
                    + " (1000, 1400, 0, 140, 34.5, 36.5, 33.5),"
                    + " (1000, 1450, 600, 43, 34.5, 36.5, 33.5),"
                    + " (1000, 1500, -1, -999, -999, -999, -999)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);

            JSONArray samples = database.getChargingSamplesStrict(1L);

            assertEquals(5, samples.length());
            assertEquals(6.2, samples.getJSONObject(0).getDouble("power"), 0.001);
            assertTrue(samples.getJSONObject(1).isNull("power"));
            assertEquals(33.5, samples.getJSONObject(1).getDouble("temp"), 0.001);
            assertEquals(35.5, samples.getJSONObject(1).getDouble("tempHigh"), 0.001);
            assertEquals(32.5, samples.getJSONObject(1).getDouble("tempLow"), 0.001);
            assertTrue(samples.getJSONObject(2).isNull("power"));
            assertEquals(42.0, samples.getJSONObject(2).getDouble("soc"), 0.001);
            assertTrue(samples.getJSONObject(3).isNull("power"));
            assertTrue(samples.getJSONObject(3).isNull("soc"));
            assertTrue(samples.getJSONObject(4).isNull("power"));
            assertEquals(43.0, samples.getJSONObject(4).getDouble("soc"), 0.001);
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void invalidStoredPowerBreaksEnergyIntegrationInsteadOfInflatingIt()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-invalid-power-gap", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database =
                new SocHistoryDatabase(journal.toFile());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:charging-invalid-power-gap-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE charging_sessions ("
                    + "start_time BIGINT PRIMARY KEY,"
                    + "integration_truncated INT DEFAULT 0)");
            statement.execute("CREATE TABLE charging_power_samples ("
                    + "id IDENTITY PRIMARY KEY,"
                    + "session_start_time BIGINT NOT NULL,"
                    + "t BIGINT NOT NULL,"
                    + "power_kw REAL)");
            statement.execute("INSERT INTO charging_sessions"
                    + " (start_time, integration_truncated) VALUES (1000, 0)");
            statement.execute("INSERT INTO charging_power_samples"
                    + " (session_start_time, t, power_kw) VALUES"
                    + " (1000, 1000, 6),"
                    + " (1000, 61000, 6),"
                    + " (1000, 121000, 600),"
                    + " (1000, 181000, 6),"
                    + " (1000, 241000, 6)");
            setField(database, "connection", connection);
            setField(database, "isInitialized", true);

            double energy = ((Number) invoke(database,
                    "integrateSessionEnergyKwh",
                    new Class<?>[] {long.class},
                    1000L)).doubleValue();

            assertEquals(0.2, energy, 0.0001);
            assertEquals(Boolean.TRUE,
                    getField(database, "lastIntegrationTruncated"));
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void lifecycleHoldQueryRequiresEnabledOpenRow() throws IOException {
        String query = between(databaseSource(),
                "public synchronized boolean isChargingLifecycleHoldActive()",
                "Clean up old remaining_kwh");

        assertTrue(query.contains("chargingLifecycleHold"));
        assertTrue(query.contains("chargingAnalyticsEnabled"));
        assertTrue(query.contains("wasCharging"));
        assertTrue(query.contains("chargingStartTime > 0"));

        String reset = between(databaseSource(),
                "private void resetLiveChargingState",
                "SoC% at the start");
        assertTrue(reset.contains("chargingLifecycleHold = false"));
    }

    @Test
    public void continuationClaimIsAdjacentAtomicAndConsumesMissingCounter() throws IOException {
        String source = databaseSource();
        String lookup = between(source,
                "private ContinuationOffer findImmediateContinuationOffer",
                "/** Claim the offer");
        assertTrue(lookup.contains("WHERE start_time < ?"));
        assertTrue(lookup.contains("ORDER BY start_time DESC LIMIT 1"));
        assertFalse(lookup.contains("counter_last_kwh IS NOT NULL"));
        assertTrue(lookup.contains("endpoint = Double.NaN"));

        String insert = between(source,
                "final boolean offerAppliedAtStart",
                "activateClaimedContinuationOffer");
        int transaction = insert.indexOf("runInTransaction(() ->");
        int rowInsert = insert.indexOf("pstmt.executeUpdate()", transaction);
        int claim = insert.indexOf("claimContinuationOffer(continuationOffer)", rowInsert);
        assertTrue(transaction >= 0);
        assertTrue(rowInsert > transaction);
        assertTrue(claim > rowInsert);
    }

    @Test
    public void resumeMutationAndDailyRebuildShareTransaction() throws IOException {
        String resume = between(databaseSource(),
                "private boolean tryResumeChargingSession",
                "Close any STALE open session");

        int restoreRead = resume.indexOf("readCounterRestoreState(canonStart)");
        int transaction = resume.indexOf("runInTransaction(() ->");
        int rekey = resume.indexOf("SET session_start_time = ?", transaction);
        int reopen = resume.indexOf("SET end_time = NULL", rekey);
        int rebuild = resume.indexOf("rebuildChargingDailyDay(day)", reopen);
        int publish = resume.indexOf("publishResumedSession(resumeAttempt)", rebuild);
        assertTrue(restoreRead >= 0 && restoreRead < transaction);
        assertTrue(rekey > transaction);
        assertTrue(reopen > rekey);
        assertTrue(rebuild > reopen);
        assertTrue(publish > rebuild);
        assertTrue(resume.contains("Resumed session location backfill failed"));
    }

    @Test
    public void staleSweepHonorsOptOutAndResetsForcedLiveRow() throws IOException {
        String source = databaseSource();
        String sweep = between(source,
                "public synchronized boolean finalizeStaleOpenSessions(boolean forceRecent)",
                "private static long dayEpoch");

        assertTrue(sweep.contains(
                "accountingBound = Math.min(accountingBound, analyticsDisabledSinceMs)"));
        assertTrue(sweep.contains("if (committed && matchesInMemory)"));
        assertTrue(sweep.contains("resetLiveChargingState(true)"));
    }

    @Test
    public void staleCloseConsumesThermalOnlyRowsAndChecksEachNullableColumn()
            throws IOException {
        String stale = between(databaseSource(),
                "private boolean finalizeOneStaleSession",
                "private static long dayEpoch");

        assertTrue(stale.contains(
                "SELECT t, power_kw, soc, temp, temp_high, temp_low"));
        assertTrue(stale.contains("MISSING_RATE_BOUNDARY_POWER_KW"));
        assertTrue(stale.contains("AUXILIARY_SAMPLE_POWER_KW"));
        assertTrue(stale.contains("boolean socMissing = rs.wasNull()"));
        assertTrue(stale.contains("boolean tempMissing = rs.wasNull()"));
        assertTrue(stale.contains("boolean tempHighMissing = rs.wasNull()"));
        assertTrue(stale.contains("boolean tempLowMissing = rs.wasNull()"));
        assertTrue(stale.contains("s >= 0 && s <= 100"));
        assertTrue(stale.contains(
                "hv_temp_high = ?, hv_temp_low = ?, hv_temp_avg = ?"));
    }

    @Test
    public void chargingDeletesAndResetAreAtomicAndLiveSafe() throws IOException {
        String source = databaseSource();
        String clear = between(source,
                "public synchronized long clearChargingHistory()",
                "public synchronized boolean deleteChargingSession");
        String delete = between(source,
                "public synchronized boolean deleteChargingSession",
                "public synchronized JSONObject getSocStats");
        String reset = between(source,
                "public synchronized long resetAll()",
                "private synchronized void cleanupOldData()");

        assertTrue(clear.contains("runInTransaction(() ->"));
        assertTrue(clear.indexOf("beginChargingMaintenance(\"clearChargingHistory\")")
                < clear.indexOf("runInTransaction(() ->"));
        assertTrue(clear.indexOf("publishCommittedChargingMaintenance(intent, replacement)")
                > clear.indexOf("runInTransaction(() ->"));

        assertTrue(delete.contains(
                "if (!closed && wasCharging && chargingStartTime == startTime)"));
        assertTrue(delete.contains("runInTransaction(() ->"));
        assertTrue(delete.contains("rebuildChargingDailyDay(dayEpoch(sessionEnd))"));
        assertFalse(delete.contains("GREATEST(session_count - 1"));

        assertTrue(reset.contains("runInTransaction(() ->"));
        assertTrue(reset.indexOf("beginChargingMaintenance(\"resetAll\")")
                < reset.indexOf("runInTransaction(() ->"));
        assertTrue(reset.indexOf("publishCommittedChargingMaintenance(intent, replacement)")
                > reset.indexOf("runInTransaction(() ->"));
    }

    @Test
    public void optOutCloseCreatesDurableResumeBarrier() throws IOException {
        String source = databaseSource();
        String schema = between(source, "String[] newChargingCols", "for (String col");
        String disabledClose = between(source,
                "if (!chargingAnalyticsEnabled)", "try {\n            if (isCharging");
        String resume = between(source,
                "private boolean tryResumeChargingSession", "Close any STALE open session");

        assertTrue(schema.contains("\"resume_blocked INTEGER DEFAULT 0\""));
        assertTrue(disabledClose.contains("resume_blocked = 1"));
        assertTrue(resume.contains("resumeBlocked.add"));
        assertTrue(resume.contains("if (resumeBlocked.get(0)) return false"));
        assertTrue(resume.contains("if (resumeBlocked.get(i)) break"));
    }

    @Test
    public void gracefulRestartCannotHideTruncatedIntegral() throws IOException {
        String source = databaseSource();
        String stop = between(source,
                "public synchronized void stop()", "private synchronized void reconnect()");
        String resume = between(source,
                "private boolean tryResumeChargingSession", "Close any STALE open session");
        String integration = between(source,
                "private boolean hasPersistedIntegrationTruncation",
                "Paginated v2 session list");

        assertTrue(stop.contains("SET integration_truncated = 1"));
        assertTrue(resume.contains("chainHasTruncatedIntegration"));
        assertTrue(resume.contains("integration_truncated = ?"));
        assertTrue(integration.contains(
                "lastIntegrationTruncated = hasPersistedIntegrationTruncation"));
        assertTrue(integration.contains("return true;"));
    }

    @Test
    public void maintenanceWritersShareConnectionMonitor() throws IOException {
        String source = databaseSource();

        assertTrue(source.contains(
                "public synchronized void fixStaleRemainingKwh(double nominalCapacityKwh)"));
        assertTrue(source.contains("private synchronized void cleanupOldData()"));
    }

    @Test
    public void repricingRollsBackWhenDailyAdjustmentFails() throws IOException {
        String source = databaseSource();
        String adjust = between(source,
                "private void adjustDailyCost", "CHARGING SESSION HELPERS");

        assertTrue(adjust.contains("throws Exception"));
        assertTrue(adjust.contains("if (upd.executeUpdate() != 1)"));
        assertTrue(adjust.contains("throw new java.sql.SQLException"));
        assertFalse(adjust.contains("catch (Exception"));
    }

    @Test
    public void lateContinuationIsMarkedGapReconstructed() throws IOException {
        String continuation = between(databaseSource(),
                "private boolean applyContinuationOffer", "private boolean tryLateContinuation");

        int seed = continuation.indexOf("chargingCounter.restore(");
        int clearPending = continuation.indexOf("chargingCounter.observe(counterNow", seed);
        int mark = continuation.indexOf("chargingCounter.markReconstructedGap()", clearPending);
        assertTrue(seed >= 0);
        assertTrue(clearPending > seed);
        assertTrue(mark > clearPending);
    }

    @Test
    public void activeClearAndResetInsertReplacementBeforePublishing() throws IOException {
        String source = databaseSource();
        String clear = between(source,
                "public synchronized long clearChargingHistory()",
                "public synchronized boolean deleteChargingSession");
        String reset = between(source,
                "public synchronized long resetAll()",
                "private synchronized void cleanupOldData()");

        assertTrue(clear.contains("beginChargingMaintenance(\"clearChargingHistory\")"));
        assertTrue(clear.indexOf("insertActiveChargingReplacement(replacement)")
                > clear.indexOf("runInTransaction(() ->"));
        assertTrue(clear.indexOf("publishCommittedChargingMaintenance(intent, replacement)")
                > clear.indexOf("insertActiveChargingReplacement(replacement)"));

        assertTrue(reset.contains("beginChargingMaintenance(\"resetAll\")"));
        assertTrue(reset.indexOf("insertActiveChargingReplacement(replacement)")
                > reset.indexOf("runInTransaction(() ->"));
        assertTrue(reset.indexOf("publishCommittedChargingMaintenance(intent, replacement)")
                > reset.indexOf("insertActiveChargingReplacement(replacement)"));
    }

    @Test
    public void uncertainCloseReconcilesExactDurableRow() throws IOException {
        String source = databaseSource();
        String tracking = between(source,
                "private boolean trackChargingSession", "Exact-row reconciliation");
        String reconcile = between(source,
                "private boolean isSessionDurablyClosed", "Latest {@code soc_history}");

        assertTrue(tracking.contains("isSessionDurablyClosed(attemptedStart)"));
        assertTrue(tracking.contains("resetLiveChargingState(true)"));
        assertTrue(reconcile.contains(
                "SELECT end_time FROM \" + TABLE_CHARGING + \" WHERE start_time = ?"));
        assertTrue(reconcile.contains("!rs.wasNull() && end > 0"));
    }

    @Test
    public void failedOptOutCloseRemainsFencedAcrossReEnable() throws IOException {
        String source = databaseSource();
        String tracking = between(source,
                "private boolean trackChargingSession", "Exact-row reconciliation");
        String toggle = between(source,
                "public synchronized boolean setChargingAnalyticsEnabled",
                "/** Hold/release manager ownership");
        String counter = between(source,
                "public synchronized void onChargeCounterObserved",
                "// synchronized: an EXTERNAL entry point");
        String samples = between(source,
                "public synchronized boolean recordChargingSample",
                "/** Start time of the currently-open charging session");

        int retryFence = tracking.indexOf(
                "if (chargingAnalyticsEnabled && optOutClosePending && wasCharging)");
        int normalGate = tracking.indexOf("if (!chargingAnalyticsEnabled)");
        assertTrue(retryFence >= 0 && retryFence < normalGate);
        assertTrue(tracking.contains("optOutBoundarySoc"));
        assertTrue(tracking.contains("optOutBoundaryMs"));
        assertTrue(tracking.contains("analyticsDisabledSinceMs = 0L"));
        assertTrue(toggle.contains("else if (optOutClosePending && wasCharging)"));
        assertTrue(toggle.contains("trackChargingSession(false"));
        assertTrue(counter.contains(
                "if (!chargingAnalyticsEnabled || optOutClosePending) return;"));
        assertTrue(samples.contains(
                "if (!chargingAnalyticsEnabled || optOutClosePending) return false;"));
    }

    @Test
    public void immediateSchedulerTickWaitsForExplicitLifecycleOwnership()
            throws IOException {
        String source = databaseSource();
        String recording = between(source,
                "private synchronized void recordCurrentSoc()",
                "private boolean trackChargingSession");

        int readiness = recording.indexOf("if (chargingLifecycleOwnerReady)");
        int tracking = recording.indexOf(
                "trackChargingSession(sessionCharging, soc, chargingPower, now)");
        assertTrue(source.contains(
                "private volatile boolean chargingLifecycleOwnerReady = false"));
        assertTrue(readiness >= 0);
        assertTrue(tracking > readiness);
        assertTrue(recording.substring(readiness, tracking).contains("{"));
        assertTrue(source.contains(
                "public synchronized void setChargingLifecycleOwnerReady(boolean ready)"));
    }

    @Test
    public void zeroPercentStartSocRemainsEligibleForSocFallback() throws IOException {
        String fallback = between(databaseSource(),
                "private double socEstimateForOpenSession", "private CounterRestoreState");

        assertTrue(fallback.contains("chargingStartSoc < 0"));
        assertFalse(fallback.contains("chargingStartSoc <= 0"));
        assertTrue(fallback.contains("endSoc - chargingStartSoc"));
    }

    @Test
    public void everySharedConnectionEntryPointUsesOneMonitor() throws IOException {
        String source = databaseSource();
        String[] synchronizedEntries = {
                "public synchronized void init()",
                "public synchronized void start()",
                "public synchronized JSONObject getLastChargeRate",
                "public synchronized JSONArray getSocHistory",
                "public synchronized JSONArray getChargingSessions(",
                "public synchronized JSONArray getChargingSessionsV2(",
                "public synchronized JSONArray getChargingSessionsV2Range",
                "public synchronized JSONObject getChargingSessionById",
                "public synchronized JSONArray getChargingSamples",
                "public synchronized JSONObject getChargingSummary(",
                "public synchronized JSONObject getChargingSummaryRange",
                "public synchronized JSONObject getSocStats",
                "public synchronized JSONObject getFullReport",
                "public synchronized JSONArray getBatteryVoltageHistory",
                "public synchronized JSONArray getThermalHistory",
                "public synchronized JSONObject getBatteryHealthReport",
                "public synchronized int getRecordCount",
                "public synchronized JSONObject getLastParkingDelta",
                "public synchronized JSONObject getMostRecentCompletedChargingSession",
                "public synchronized double getSocChangeRatePerHour"
        };
        for (String signature : synchronizedEntries) {
            assertTrue("Missing shared-connection monitor on " + signature,
                    source.contains(signature));
        }

        String callback = between(source,
                "private void resolvePlaceLabelAsync",
                "private synchronized void updateSessionPlaceLabel");
        String writer = between(source,
                "private synchronized void updateSessionPlaceLabel",
                "Charging gun state");
        assertTrue(callback.contains("updateSessionPlaceLabel(sessionStart, label)"));
        assertFalse(callback.contains("connection.prepareStatement"));
        assertTrue(writer.contains("connection.prepareStatement"));
    }

    @Test
    public void uncertainStartReconcilesRowAndContinuationClaimBeforeCleanup() throws IOException {
        String source = databaseSource();
        String tracking = between(source,
                "private boolean trackChargingSession", "Exact-row reconciliation");
        String reconcile = between(source,
                "private boolean reconcileDurableSessionStart",
                "Latest {@code soc_history}");

        assertTrue(tracking.contains("new SessionStartAttempt(now, continuationOffer"));
        int firstReconcile = tracking.indexOf("reconcileDurableSessionStart(startAttempt)");
        int claimCleanup = tracking.lastIndexOf("clearClaimedContinuationOffer()");
        assertTrue(firstReconcile >= 0 && claimCleanup > firstReconcile);
        assertTrue(reconcile.contains(
                "SELECT end_time FROM \" + TABLE_CHARGING + \" WHERE start_time = ?"));
        assertTrue(reconcile.contains("SELECT continuation_claimed FROM"));
        assertTrue(reconcile.contains("rs.getInt(1) != 1"));
        assertTrue(reconcile.contains("activateClaimedContinuationOffer"));
        assertTrue(reconcile.contains("wasCharging = true"));
    }

    @Test
    public void uncertainClearAndResetPublishOnlyReloadedDurableReplacement() throws IOException {
        String source = databaseSource();
        String clear = between(source,
                "public synchronized long clearChargingHistory()",
                "public synchronized boolean deleteChargingSession");
        String reset = between(source,
                "public synchronized long resetAll()",
                "private synchronized void cleanupOldData()");
        String reconcile = between(source,
                "private ActiveChargingReplacement readDurableActiveChargingReplacement",
                "private void publishActiveChargingReplacement");

        assertTrue(clear.contains("reconcileChargingMaintenanceOutcome(e)"));
        assertTrue(reset.contains("reconcileChargingMaintenanceOutcome(e)"));
        assertTrue(reconcile.contains("WHERE start_time = ? AND end_time IS NULL"));
        assertTrue(reconcile.contains("durable.startTime = rs.getLong(\"start_time\")"));
        assertTrue(reconcile.contains("readDurableActiveChargingReplacement(expected)"));
        assertTrue(reconcile.contains(
                "publishCommittedChargingMaintenance(intent, durable)"));
    }

    @Test
    public void uncertainReplacementFencesIdentityReadsAndGenerationAdvances()
            throws IOException {
        String source = databaseSource();
        String openIdentity = between(source,
                "public synchronized long getOpenChargingSessionStart()",
                "/** Durable H2 truth");
        String durableOpen = between(source,
                "public synchronized boolean hasOpenChargingSessionRow()",
                "/** Exact row frozen");
        String closeTarget = between(source,
                "public synchronized long getChargingCloseTargetStart()",
                "private long resolveChargingCloseTargetStart");
        String closeSatisfied = between(source,
                "public synchronized boolean isChargingSessionCloseSatisfied",
                "/** Latched estimated");
        String deferredStart = between(source,
                "public synchronized boolean deferPhysicalChargingStart()",
                "/** Capture the endpoint");
        String deferredStop = between(source,
                "public synchronized boolean deferPhysicalChargingStop()",
                "private boolean endDeferredPhysicalGeneration");
        String staleSweep = between(source,
                "public synchronized boolean finalizeStaleOpenSessions(boolean forceRecent)",
                "private static long dayEpoch");

        assertTrue(openIdentity.contains(
                "if (!reconcilePendingActiveChargingReplacement()) return -1L"));
        assertTrue(durableOpen.contains(
                "if (!reconcilePendingActiveChargingReplacement())"));
        assertTrue(durableOpen.contains("throw new java.sql.SQLException"));
        assertEquals(2, occurrences(closeTarget,
                "if (!reconcilePendingActiveChargingReplacement())"));
        assertTrue(closeSatisfied.contains(
                "if (!reconcilePendingActiveChargingReplacement()) return false"));
        assertTrue(closeSatisfied.contains(
                "sessionStart = resolveChargingCloseTargetStart(sessionStart)"));
        assertTrue(deferredStart.contains(
                "if (!reconcilePendingActiveChargingReplacement()) return false"));
        assertTrue(deferredStop.contains(
                "if (!reconcilePendingActiveChargingReplacement()) return false"));
        assertTrue(staleSweep.contains(
                "if (!reconcilePendingActiveChargingReplacement()) return false"));
    }

    @Test
    public void distinctPhysicalSessionIsBufferedAwayFromFailedPriorClose() throws IOException {
        String source = databaseSource();
        String counter = between(source,
                "public synchronized void onChargeCounterObserved",
                "// synchronized: an EXTERNAL entry point");
        String materialize = between(source,
                "private boolean materializeDeferredGenerationsForOpenEdge",
                "/** Persist every enabled deferred interval");

        assertTrue(source.contains("public synchronized boolean deferPhysicalChargingStart()"));
        assertTrue(source.contains("public synchronized boolean deferPhysicalChargingStop()"));
        assertTrue(source.contains(
                "private final java.util.ArrayDeque<DeferredChargingGeneration>"));
        assertTrue(source.contains("deferredPhysicalGenerations.addLast(generation)"));
        assertTrue(counter.contains("currentDeferredPhysicalGeneration(), source, counterKwh"));
        assertTrue(counter.contains("return;"));
        assertTrue(materialize.contains("deferredPhysicalGenerations.peekFirst()"));
        assertTrue(materialize.contains("if (ended)"));
        assertFalse(materialize.contains(
                "ended && !deferredPhysicalGenerations.isEmpty()"));
        assertTrue(materialize.contains("trackChargingSession(false"));
        assertTrue(source.contains("deferredPhysicalGenerations.pollFirst()"));
    }

    @Test
    public void continuationUsesWrapCandidatesAndPriorSocFrame() throws IOException {
        String source = databaseSource();
        String offer = between(source,
                "private static final class ContinuationOffer",
                "private static final class SessionStartAttempt");
        String apply = between(source,
                "private boolean applyContinuationOffer",
                "private boolean tryLateContinuation");
        String late = between(source,
                "private boolean tryLateContinuation", "Persist counter endpoints");

        assertTrue(offer.contains("final double startSoc"));
        assertTrue(offer.contains("final double fullScaleKwh"));
        assertTrue(apply.contains("continuationCounterEnergyKwh"));
        assertTrue(apply.contains("chargingStartSoc = continuationStartSoc"));
        assertTrue(apply.contains("SET start_soc = ?"));
        assertTrue(source.contains("final double persistedStartSoc = chargingStartSoc;"));
        assertFalse(late.contains("counterNow < prevLast"));
    }

    @Test
    public void resumeCommitIsReconciledAndAlwaysBreaksOutageIntegral() throws IOException {
        String resume = between(databaseSource(),
                "private boolean tryResumeChargingSession", "Close any STALE open session");

        assertTrue(resume.contains("VALUES (?, ?, -1, 0, -999, -999, -999)"));
        assertTrue(resume.contains("final boolean resumedIntegrationTruncated = true"));
        assertTrue(resume.contains("reconcileDurableResume(resumeAttempt)"));
        assertTrue(resume.contains("integration_truncated FROM"));
        assertTrue(resume.contains("power_kw <= 0"));
        assertTrue(resume.contains("publishResumedSession(resumeAttempt)"));
    }

    @Test
    public void uncertainClearResetRemainFencedUntilOldOrReplacementRowIsProven()
            throws IOException {
        String source = databaseSource();
        String reconcile = between(source,
                "private ActiveChargingReplacement reconcileActiveChargingReplacement",
                "private void publishActiveChargingReplacement");
        String samples = between(source,
                "public synchronized boolean recordChargingSample",
                "/** Start time of the currently-open charging session");

        assertTrue(reconcile.contains("pendingActiveReplacement = expected"));
        assertTrue(reconcile.contains("isDurablyOpenSession(previousStart)"));
        assertTrue(reconcile.contains("private boolean reconcilePendingActiveChargingReplacement()"));
        assertTrue(reconcile.contains("publishActiveChargingReplacement(durable)"));
        assertTrue(samples.contains("if (!reconcilePendingActiveChargingReplacement()) return false"));
    }

    @Test
    public void transactionRestoreFailureQuarantinesSharedConnection() throws IOException {
        String transaction = between(databaseSource(),
                "private void runInTransaction", "Upsert one completed session");

        assertTrue(transaction.contains("if (!priorAutoCommit)"));
        assertTrue(transaction.contains("discardTransactionConnection(c)"));
        assertTrue(transaction.contains("primaryFailure.addSuppressed(restoreFailure)"));
        assertFalse(transaction.contains("setAutoCommit(true); } catch (Exception ignored)"));
    }

    @Test
    public void staleFinalizeReconcilesExactClosedRow() throws IOException {
        String finalize = between(databaseSource(),
                "private boolean finalizeOneStaleSession", "private static long dayEpoch");

        assertTrue(finalize.contains("if (isSessionDurablyClosed(start))"));
        assertTrue(finalize.contains("reconciled exact row"));
        assertTrue(finalize.contains("reconciled after reconnect"));
    }

    @Test
    public void optOutCloseUsesExactCapturedBoundary() throws IOException {
        String disabledClose = between(databaseSource(),
                "if (!chargingAnalyticsEnabled)", "try {\n            if (isCharging");

        assertTrue(disabledClose.contains(
                "long closeTime = strictlyAfterChargingStart("));
        assertTrue(disabledClose.contains(
                "chargingStartTime, optOutBoundaryMs)"));
        assertFalse(disabledClose.contains("Math.min(lastSampleT, optOutBoundaryMs)"));
    }

    @Test
    public void terminalCounterFenceIncludesFaultTimeoutAndExportStates() {
        int[] terminal = {0, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12};
        for (int state : terminal) {
            assertTrue("Expected terminal state " + state,
                    SocHistoryDatabase.isTerminalChargingStateCode(state));
        }
        assertFalse(SocHistoryDatabase.isTerminalChargingStateCode(1));
        assertFalse(SocHistoryDatabase.isTerminalChargingStateCode(9));
        assertFalse(SocHistoryDatabase.isTerminalChargingStateCode(15));
    }

    @Test
    public void endedDeferredCounterFinalizesEarliestCandidateAndLatestRise()
            throws IOException {
        String source = databaseSource();
        String observe = between(source,
                "private void observeDeferredPhysicalCounter",
                "private void finalizeDeferredCounterBaseline");
        String finalize = between(source,
                "private void finalizeDeferredCounterBaseline",
                "public synchronized boolean recordDeferredChargingSample");
        String stop = between(source,
                "private boolean endDeferredPhysicalGeneration",
                "private void observeDeferredPhysicalCounter");

        assertTrue(observe.contains("generation.counterLatestKwh = counterKwh"));
        assertTrue(observe.contains("generation.counterCandidateKwh = counterKwh"));
        assertTrue(stop.contains("finalizeDeferredCounterBaseline(generation)"));
        int baseline = finalize.indexOf("generation.counter.observe(baseline");
        int latest = finalize.indexOf("generation.counter.observe(latest");
        assertTrue(baseline >= 0);
        assertTrue(latest > baseline);
    }

    @Test
    public void analyticsDisableRetainsDeferredEnabledIntervalsAsResumeBlocked()
            throws IOException {
        String source = databaseSource();
        String toggle = between(source,
                "public synchronized boolean setChargingAnalyticsEnabled",
                "/** Hold/release manager ownership");
        String materialize = between(source,
                "private boolean materializeDeferredGenerationsAtOptOut",
                "public synchronized boolean onChargingEdge");

        assertTrue(toggle.contains("endDeferredPhysicalGeneration("));
        assertTrue(toggle.contains("generation.resumeBlocked = true"));
        assertFalse(toggle.contains("discardDeferredPhysicalSessions()"));
        assertTrue(materialize.contains("trackWithAnalyticsTemporarilyEnabled("));
        assertTrue(source.contains("pendingCloseResumeBlocked ? 1 : 0"));
    }

    @Test
    public void activeClearRebasesBufferedReplacementAtClearBoundary() throws IOException {
        String source = databaseSource();
        String snapshot = between(source,
                "private ActiveChargingReplacement snapshotActiveChargingReplacement",
                "private void insertActiveChargingReplacement");
        String publish = between(source,
                "private void publishActiveChargingReplacement",
                "private void resetLiveChargingState");

        assertTrue(snapshot.contains(
                "state.startTime = allocateMonotonicChargingStart"));
        assertTrue(snapshot.contains("for (DeferredChargingGeneration generation"));
        assertTrue(snapshot.contains(
                "rebaseDeferredGenerationAtMaintenanceBoundary(generation, state)"));
        assertTrue(snapshot.contains(
                "rebased.startMs = allocateMonotonicChargingStart(proposedStart)"));
        assertTrue(publish.contains("deferredPhysicalGenerations.clear()"));
        assertTrue(publish.contains(
                "deferredPhysicalGenerations.addAll(state.deferredGenerations)"));
    }

    @Test
    public void deferredGenerationPreservesCurveAndPhysicalStartMetadata() throws IOException {
        String source = databaseSource();
        String generation = between(source,
                "private static final class DeferredChargingGeneration",
                "/**\n     * Clear/reset transaction");
        String sample = between(source,
                "public synchronized boolean recordDeferredChargingSample",
                "private void insertDeferredChargingSamples");
        String insert = between(source,
                "private void insertDeferredChargingSamples",
                "private void consumeDeferredPhysicalSessionAfterStart");
        String start = between(source,
                "if (isCharging && !wasCharging)", "} else if (isCharging && wasCharging)");

        assertTrue(generation.contains("int startRange"));
        assertTrue(generation.contains("int startOdometer"));
        assertTrue(generation.contains("int gun"));
        assertTrue(generation.contains("int timeToFull"));
        assertTrue(generation.contains("double lat"));
        assertTrue(generation.contains("double peakPower"));
        assertTrue(generation.contains("ArrayList<DeferredChargingSample> samples"));
        assertTrue(sample.contains("generation.samples.add"));
        assertTrue(sample.contains("generation.peakPower"));
        assertTrue(insert.contains("p.addBatch()"));
        assertTrue(start.contains("chargingGunState = deferredGeneration.gun"));
        assertTrue(start.contains("time_to_full_min"));
        assertTrue(start.contains("insertDeferredChargingSamples("));
    }

    @Test
    public void uncertainChargingSampleInsertReconcilesExactIdentity() throws IOException {
        String samples = between(databaseSource(),
                "public synchronized boolean recordChargingSample",
                "/** Start time of the currently-open charging session");

        int failure = samples.indexOf("catch (Exception e)");
        int reconcile = samples.indexOf(
                "isChargingSampleDurable(", failure);
        int query = samples.indexOf(
                "WHERE session_start_time = ? AND t = ? ORDER BY id ASC");
        assertTrue(failure >= 0);
        assertTrue(reconcile > failure);
        assertTrue(query > reconcile);
        assertTrue(samples.contains("noteWriteOk();"));
    }

    @Test
    public void closeRetriesReusePhysicalBoundaryPricing() throws IOException {
        String source = databaseSource();
        String capture = between(source,
                "public synchronized boolean capturePendingChargingClose",
                "private long allocateMonotonicChargingStart");
        String close = between(source,
                "} else if (!isCharging && wasCharging)", "wasCharging = isCharging");
        String deferredEnd = between(source,
                "private boolean endDeferredPhysicalGeneration",
                "private void observeDeferredPhysicalCounter");

        assertTrue(capture.contains("pendingClosePricing = priceSessionForClose("));
        assertTrue(capture.contains(
                "pendingCloseIsDc = currentChargingTypeVerdict()"));
        assertTrue(close.contains("PricingDecision pd = pendingClosePricing;"));
        assertTrue(deferredEnd.contains(
                "generation.closePricing = priceSessionForClose("));
        assertTrue(source.contains("pendingClosePricing = generation.closePricing"));
    }

    @Test
    public void admittedTaperAdvancesExactCloseBoundaryWithoutBridgingIdleGap()
            throws IOException {
        String source = databaseSource();
        String boundary = between(source,
                "private boolean advancePendingCloseForAdmittedTaper",
                "public synchronized boolean recordChargingSample");
        String sample = between(source,
                "public synchronized boolean recordChargingSample",
                "/** Reconcile an INSERT");
        String deferred = between(source,
                "public synchronized boolean recordDeferredChargingSample",
                "private void insertDeferredChargingSamples");
        String midSession = between(source,
                "} else if (isCharging && wasCharging)",
                "} else if (!isCharging && wasCharging)");

        assertTrue(boundary.contains("isAdmittedTaperTail()"));
        assertTrue(boundary.contains(
                "pendingCloseSessionStart != sessionStartTime"));
        assertTrue(boundary.contains(
                "chargingStartTime != sessionStartTime"));
        assertTrue(boundary.contains(
                "pendingCloseAtMs = advancedAt"));
        assertTrue(boundary.contains("pendingCloseSoc = soc"));
        assertTrue(boundary.contains("persistChargingLifecycleJournal()"));

        int positiveOnly = sample.indexOf("if (powerKw > 0");
        int advance = sample.indexOf(
                "advancePendingCloseForAdmittedTaper(", positiveOnly);
        int transaction = sample.indexOf("runInTransaction(() ->", advance);
        assertTrue(positiveOnly >= 0);
        assertTrue(advance > positiveOnly);
        assertTrue(transaction > advance);

        assertTrue(deferred.contains(
                "powerKw > 0 && generation.isEnded() && isAdmittedTaperTail()"));
        assertTrue(deferred.contains(
                "generation.endMs = Math.max("));
        assertTrue(deferred.contains(
                "sampleAt = Math.min(sampleAt, generation.endMs)"));
        assertTrue(midSession.contains(
                "advancePendingCloseForAdmittedTaper("));
    }

    @Test
    public void strictChargingReadsSeparateStorageFailureFromEmptyResults() throws IOException {
        String source = databaseSource();
        assertTrue(source.contains(
                "public synchronized JSONArray getChargingSessionsV2Strict("));
        assertTrue(source.contains(
                "public synchronized JSONArray getChargingSessionsV2RangeStrict("));
        assertTrue(source.contains(
                "public synchronized JSONObject getChargingSessionByIdStrict(long id)"));
        assertTrue(source.contains(
                "public synchronized JSONArray getChargingSamplesStrict(long id)"));
        assertTrue(source.contains(
                "throw new java.sql.SQLException(\"charging history storage is unavailable\")"));

        String byId = between(source,
                "public synchronized JSONObject getChargingSessionByIdStrict",
                "/** Per-session fine-grained");
        assertTrue(byId.contains("rs.next() ? chargingRowToJson(rs) : null"));
        assertTrue(byId.contains("throw chargingHistoryReadException"));

        String lenient = between(source,
                "public synchronized JSONObject getChargingSessionById(long id)",
                "/** Strict API-facing lookup");
        assertTrue(lenient.contains("return getChargingSessionByIdStrict(id)"));
        assertTrue(lenient.contains("return null"));
    }

    @Test
    public void uncertainMaintenanceCommitsReconcileDurablePostconditions() throws IOException {
        String source = databaseSource();
        String reprice = between(source,
                "public synchronized int repriceSessionsForTariff",
                "The per-kWh rate the most recent completed charge");
        String clear = between(source,
                "public synchronized long clearChargingHistory()",
                "public synchronized boolean deleteChargingSession");
        String delete = between(source,
                "public synchronized boolean deleteChargingSession",
                "public synchronized JSONObject getSocStats");
        String reset = between(source,
                "public synchronized long resetAll()",
                "private synchronized void cleanupOldData()");
        String reconciliation = between(source,
                "private Boolean areTablesDurablyEmpty",
                "Upsert one completed session");

        assertTrue(reprice.contains("commitAttempted = true"));
        assertTrue(reprice.contains("reconcileRepriceCommit(expectedUpdates, e)"));
        assertTrue(reprice.contains("countCostChanged(expectedUpdates)"));
        assertTrue(clear.contains("reconcileChargingMaintenanceOutcome(e)"));
        assertTrue(delete.contains("reconcileDeletedChargingSession(id, e)"));
        assertTrue(reset.contains("reconcileChargingMaintenanceOutcome(e)"));
        assertTrue(reconciliation.contains("SELECT COUNT(*) FROM"));
        assertTrue(reconciliation.contains("SELECT 1 FROM"));
        assertTrue(reconciliation.contains("try { reconnect(); }"));
    }

    @Test
    public void counterCallbacksAndNormalStartsAreJournaledBeforeJdbcAvailability()
            throws IOException {
        String source = databaseSource();
        String callback = between(source,
                "public synchronized void onChargeCounterObserved",
                "// synchronized: an EXTERNAL entry point");
        String start = between(source,
                "final DeferredChargingGeneration journaledStartGeneration",
                "activateClaimedContinuationOffer");

        int deferredRoute = callback.indexOf(
                "observeDeferredPhysicalCounter(");
        int availability = callback.indexOf(
                "boolean databaseAvailable = isInitialized && connection != null");
        int journalFallback = callback.indexOf(
                "else persistChargingLifecycleJournal()");
        int journal = start.indexOf(
                "journalCurrentChargingStart(continuationOffer)");
        int transaction = start.indexOf("runInTransaction(() ->");

        assertTrue(deferredRoute >= 0);
        assertTrue(availability > deferredRoute);
        assertTrue(journalFallback > availability);
        assertTrue(journal >= 0);
        assertTrue(transaction > journal);
        assertTrue(source.contains(
                "generation.continuationOffer = continuationOffer"));
        assertTrue(source.contains(
                "\"continuation\", continuation"));
    }

    @Test
    public void deferredGenerationsAndExactCounterStateAreJournaledAcrossRestart()
            throws IOException {
        String source = databaseSource();
        String encode = between(source,
                "private JSONObject deferredGenerationToJson",
                "private static JSONObject counterStateToJson");
        String decode = between(source,
                "private DeferredChargingGeneration deferredGenerationFromJson",
                "private static String emptyToNull");
        String materialize = between(source,
                "if (isCharging && !wasCharging)",
                "} else if (isCharging && wasCharging)");

        assertTrue(source.contains("\"deferred\", deferred"));
        assertTrue(encode.contains("counterStateToJson(generation.counter.snapshotState())"));
        assertTrue(encode.contains("\"provisionalExternal\""));
        assertTrue(encode.contains("\"counterCandidate\""));
        assertTrue(encode.contains("\"samples\""));
        assertTrue(decode.contains("generation.counter.restoreState("));
        assertTrue(materialize.contains("chargingCounter.restoreState("));
        assertTrue(materialize.contains(
                "provisionalExternalKwh =\n                            deferredGeneration.provisionalExternalKwh"));
        assertFalse(materialize.contains("chargingCounter.restore(\n"
                + "                            deferredGeneration.counter.baselineKwh()"));
    }

    @Test
    public void chargingIdentityReservationSurvivesRollbackAndClockRegression()
            throws IOException {
        String source = databaseSource();
        String schema = between(source,
                "repairDuplicateChargingStartTimes();",
                "// Per-session power/SoC/temp samples");
        String allocator = between(source,
                "private long allocateMonotonicChargingStart",
                "static long nextMonotonicChargingStart");
        String deferred = between(source,
                "public synchronized boolean deferPhysicalChargingStart",
                "/** Capture the endpoint of the newest deferred charge");
        String edge = between(source,
                "public synchronized boolean onChargingEdge",
                "/**\n     * Offer a freshly-observed charged-energy counter");

        assertTrue(schema.contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_charging_start"));
        assertTrue(schema.contains("TABLE_CHARGING_IDENTITY"));
        assertTrue(allocator.contains("SELECT MAX(start_time)"));
        assertTrue(allocator.contains("UPDATE \" + TABLE_CHARGING_IDENTITY"));
        assertTrue(allocator.contains(
                "if (databaseAvailable && !databaseDurable)"));
        assertTrue(allocator.contains("lastAllocatedChargingStartMs = allocated"));
        assertTrue(allocator.contains("persistChargingLifecycleJournal()"));
        int enqueue = deferred.indexOf("deferredPhysicalGenerations.addLast(generation)");
        int journal = deferred.indexOf("persistChargingLifecycleJournal()", enqueue);
        int reserve = deferred.indexOf("reserveChargingStartIdentity", journal);
        assertTrue(enqueue >= 0);
        assertTrue(journal > enqueue);
        assertTrue(reserve > journal);
        assertTrue(edge.contains(
                "if (chargingLifecycleJournalDirty && !persistChargingLifecycleJournal())"));
        assertTrue(edge.contains("if (!reserveDeferredChargingIdentities()) return false;"));
        assertEquals(101L,
                SocHistoryDatabase.nextMonotonicChargingStart(50L, 100L));
        assertEquals(101L,
                SocHistoryDatabase.nextMonotonicChargingStart(101L, 100L));
    }

    @Test
    public void missingRateMarkerAndIncompleteFlagCommitAtomically()
            throws IOException {
        String samples = between(databaseSource(),
                "public synchronized boolean recordChargingSample",
                "/** Start time of the currently-open charging session");
        String integration = between(databaseSource(),
                "private double integrateSessionEnergyKwh",
                "Paginated v2 session list");

        int transaction = samples.indexOf("runInTransaction(() ->");
        int insert = samples.indexOf("charging sample INSERT", transaction);
        int marker = samples.indexOf("SET integration_truncated = 1", insert);
        assertTrue(transaction >= 0);
        assertTrue(insert > transaction);
        assertTrue(marker > insert);
        assertTrue(samples.contains(
                "powerKw == MISSING_RATE_BOUNDARY_POWER_KW"));
        assertTrue(integration.contains(
                "ORDER BY t ASC,"
                + "\"\n                + \" CASE WHEN power_kw <= 0 THEN 1 ELSE 0 END ASC, id ASC"));
    }

    @Test
    public void optOutRetryPersistsBoundaryCounterAndSuccessfulPricingBeforeH2()
            throws IOException {
        String disabled = between(databaseSource(),
                "if (!chargingAnalyticsEnabled)",
                "try {\n            if (isCharging");

        int boundary = disabled.indexOf("optOutCounterCaptured = true;");
        int boundaryJournal = disabled.indexOf(
                "\"opt-out close boundary was not durable\"", boundary);
        int pricing = disabled.indexOf(
                "optOutClosePricing = priceSessionForClose", boundaryJournal);
        int pricingJournal = disabled.indexOf(
                "\"opt-out close snapshot was not durable\"", pricing);
        int transaction = disabled.indexOf("runInTransaction(() ->", pricingJournal);
        assertTrue(boundary >= 0);
        assertTrue(boundaryJournal > boundary);
        assertTrue(pricing > boundaryJournal);
        assertTrue(pricingJournal > pricing);
        assertTrue(transaction > pricingJournal);
    }

    @Test
    public void provisionalUnknownCounterConvertsItsCapturedUnitFrameAndSurvivesDeferral()
            throws IOException {
        String source = databaseSource();
        String deferred = between(source,
                "private void observeDeferredPhysicalCounter",
                "private void finalizeDeferredCounterBaseline");
        String active = between(source,
                "public synchronized void onChargeCounterObserved",
                "// synchronized: an EXTERNAL entry point");

        assertTrue(deferred.contains("convertCounterUnitFrame("));
        assertTrue(active.contains("convertCounterUnitFrame("));
        assertTrue(active.contains(
                "persistChargingLifecycleJournal();\n            }\n            return;"));
        assertTrue(source.contains(
                "generation.provisionalExternalKwh = finiteOrNaN"));
        assertTrue(source.contains(
                "generation.provisionalExternalUnitDivisor = validCounterUnitDivisor"));
        assertTrue(source.contains(
                "provisionalExternalKwh = generation.provisionalExternalKwh"));
        assertEquals(6.5,
                SocHistoryDatabase.convertCounterUnitFrame(650.0, 1.0, 100.0),
                0.0);
        assertEquals(6.5,
                SocHistoryDatabase.convertCounterUnitFrame(6.5, 100.0, 100.0),
                0.0);
    }

    @Test
    public void strictTariffResolutionRevalidatesDurableConfigOnEveryClose()
            throws IOException {
        String tariff = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/TariffManager.java");
        String strict = between(tariff,
                "public synchronized TariffProfile resolveStrict",
                "/**\n     * Strict geofence match");
        String load = between(tariff,
                "public boolean load()",
                "private boolean ensureLoaded()");

        String strictCircle = between(tariff,
                "public synchronized TariffProfile resolveInCircleStrict",
                "private void requireFreshPricingConfig()");
        String freshConfig = between(tariff,
                "private void requireFreshPricingConfig()",
                "private TariffProfile resolveInCircleLoaded");

        assertTrue(strict.contains("requireFreshPricingConfig();"));
        assertTrue(strictCircle.contains("requireFreshPricingConfig();"));
        assertTrue(freshConfig.contains("loadStrict();"));
        assertTrue(freshConfig.contains("loaded = false;"));
        assertTrue(freshConfig.contains("throw new IllegalStateException("));
        assertTrue(load.contains("private synchronized void loadStrict()"));
        assertTrue(load.contains("loadVerifiedConfig()"));
        assertTrue(load.contains("publishImage(image)"));
        assertTrue(load.contains("synchronized (this)"));
        assertTrue(load.contains("replayPendingReprices();"));
        assertTrue(load.indexOf("replayPendingReprices();")
                > load.lastIndexOf("synchronized (this)"));
    }

    @Test
    public void sampleCommitReconciliationRequiresFullStoredPayload()
            throws IOException {
        String samples = between(databaseSource(),
                "public synchronized boolean recordChargingSample",
                "/** Start time of the currently-open charging session");

        assertTrue(samples.contains(
                "SELECT power_kw, soc, temp, temp_high, temp_low"));
        assertEquals(5, occurrences(samples, "realStorageEquivalent(rs.getDouble("));
        assertTrue(samples.contains(
                "SELECT integration_truncated FROM"));
        assertFalse(samples.contains(
                "SELECT 1 FROM \" + TABLE_CPS\n"
                        + "                        + \" WHERE session_start_time = ? AND t = ? LIMIT 1"));
    }

    @Test
    public void realPostconditionsCompareTheSinglePrecisionStoredImage()
            throws IOException {
        String source = databaseSource();
        assertTrue(source.contains(
                "Float.floatToIntBits((float) actual)"));
        assertFalse(source.contains(
                "Math.abs(rs.getDouble(\"electricity_rate\") - row.rate) > 1e-9"));
        assertTrue(SocHistoryDatabase.realStorageEquivalent(
                (double) (float) 0.1, 0.1));
        assertTrue(SocHistoryDatabase.realStorageEquivalent(
                Double.NaN, Float.NaN));
        assertFalse(SocHistoryDatabase.realStorageEquivalent(
                0.1, 0.1002));
    }

    @Test
    public void lastChargeStrictReadSeparatesAbsentDataFromStorageFailure()
            throws IOException {
        String source = databaseSource();
        String strict = between(source,
                "public synchronized JSONObject getLastChargeRateStrict",
                "/**\n     * Shift one day's rollup cost");

        assertTrue(strict.contains("requireChargingHistoryReadConnection()"));
        assertTrue(strict.contains("if (!rs.next())"));
        assertTrue(strict.contains("return null;"));
        assertTrue(strict.contains(
                "throw chargingHistoryReadException(\"get last charge rate\", e)"));
        assertTrue(source.contains(
                "return getLastChargeRateStrict(maxAgeDays);"));
    }

    @Test
    public void shortContinuousCounterClosesFromEarliestCandidateToFinalValue()
            throws IOException {
        String close = between(databaseSource(),
                "private void observeFinalCounterForClose",
                "/** True when the BMS currently reports");

        int remember = close.indexOf("rememberCounterBaselineCandidate");
        int earliest = close.indexOf(
                "double baseline = counterBaselineCandidateKwh", remember);
        int baselineObserve = close.indexOf(
                "chargingCounter.observe(\n                            baseline", earliest);
        int finalObserve = close.indexOf(
                "chargingCounter.observe(counterKwh, observedAt)", baselineObserve);
        assertTrue(remember >= 0);
        assertTrue(earliest > remember);
        assertTrue(baselineObserve > earliest);
        assertTrue(finalObserve > baselineObserve);
    }

    @Test
    public void reconciledCloseReplaysIdempotentTariffAndSohMetadata()
            throws IOException {
        String source = databaseSource();
        String replay = between(source,
                "public synchronized void replayPendingChargingPostCommitMetadata",
                "private String getCurrencySymbol");
        String tracking = between(source,
                "private boolean trackChargingSession", "Exact-row reconciliation");
        String estimator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/abrp/SohEstimator.java");

        assertTrue(source.contains("\"post_commit_tariff_applied INTEGER DEFAULT 0\""));
        assertTrue(source.contains("\"post_commit_soh_applied INTEGER DEFAULT 0\""));
        assertTrue(source.contains("soh_calibration_energy_kwh = ?"));
        assertTrue(replay.contains("SELECT COUNT(*), MAX(end_time)"));
        assertTrue(replay.contains("tariffManager.reconcileUsage("));
        assertTrue(replay.contains(
                "estimator.applyCalibrationReplayWithOutcome("));
        assertTrue(replay.contains("calibration was not durably accepted"));
        assertTrue(estimator.contains("public boolean applyCalibrationReplay("));
        assertTrue(estimator.contains(
                "public CalibrationReplayOutcome applyCalibrationReplayWithOutcome("));
        assertTrue(estimator.contains("if (calibrationTimestampMs == calibrationAtMs)"));
        assertTrue(estimator.contains(
                "sameCalibration(calibrationSoh, calibratedSoh)"));
        assertTrue(estimator.contains(
                "? CalibrationReplayOutcome.APPLIED"));
        assertTrue(estimator.contains(
                "persistenceOutcome == PersistenceOutcome.FAILED"));
        assertTrue(estimator.contains(
                "PersistenceOutcome.COMMITTED_DURABILITY_UNCERTAIN"));
        assertTrue(replay.contains("SET post_commit_soh_applied = 1"));
        assertTrue(tracking.contains(
                "reconciled exact closed row\");\n"
                        + "                replayPendingChargingPostCommitMetadata();"));
        assertTrue(source.contains(
                "public synchronized void setSohEstimator"));
    }

    @Test
    public void pendingSohReplayCarriesAndValidatesItsCalibrationFrame()
            throws IOException {
        String source = databaseSource();
        String init = between(source,
                "public synchronized void init()",
                "Clean up stale lock files");
        String replay = between(source,
                "public synchronized void replayPendingChargingPostCommitMetadata",
                "private String getCurrencySymbol");

        assertTrue(source.contains(
                "\"soh_calibration_nominal_identity VARCHAR(192) DEFAULT NULL\""));
        assertTrue(source.contains(
                "\"soh_calibration_estimator_generation BIGINT DEFAULT NULL\""));
        assertTrue(source.contains(
                "\"soh_calibration_reset_model_epoch BIGINT DEFAULT NULL\""));
        assertTrue(source.contains(
                "\"soh_calibration_prior_at_ms BIGINT DEFAULT NULL\""));
        assertTrue(source.contains(
                "pstmt.setLong(next++, stagedSohFrame.resetModelEpoch)"));
        assertTrue(source.contains(
                "before.getResetModelEpoch()"));
        assertTrue(source.contains(
                "after.getResetModelEpoch()"));
        assertTrue(source.contains(
                "before.getResetModelEpoch() <= 0"));
        assertTrue(source.contains(
                "nominal.getResetModelEpoch()"));
        assertTrue(source.contains(
                "sohNominalIdentity("));
        assertTrue(source.contains(
                "selectedVehicleModelIdentity()"));
        assertTrue(source.contains(
                "!estimator.isInitializationReady()"));
        assertTrue(source.contains(
                "getSelectedVehicleModelIdStrict()"));
        assertTrue(replay.contains(
                "!row.nominalIdentity.equals("));
        assertTrue(replay.contains(
                "rs.getLong(\"soh_calibration_reset_model_epoch\")"));
        assertTrue(replay.contains(
                "if (!row.hasResetModelEpoch || row.resetModelEpoch <= 0)"));
        assertTrue(replay.contains(
                "row.resetModelEpoch != current.resetModelEpoch"));
        assertFalse(replay.contains(
                "current.estimatorGeneration\n"
                        + "                                    != row.estimatorGeneration"));
        assertTrue(replay.contains(
                "estimator.runWithEstimatorGenerationGuard("));
        assertTrue(replay.contains(
                "current.estimatorGeneration"));
        assertTrue(replay.contains(
                "current calibration frame is temporarily unavailable"));
        assertTrue(replay.contains(
                "CalibrationReplayOutcome.PERMANENTLY_REJECTED"));
        assertFalse(replay.contains(
                "row.packTempC = 25.0"));
        assertTrue(replay.contains(
                "soh_calibration_rejected = ?"));
        assertTrue(source.contains(
                "socDelta >= 25.0"));
        assertTrue(source.contains(
                "tAvg >= 15.0"));
        assertTrue(source.contains(
                "tAvg <= 35.0"));

        int initialized = init.indexOf("isInitialized = true");
        int lifecycleReconcile = init.indexOf(
                "reconcileChargingLifecycleJournalWithDatabase()", initialized);
        int metadataReplay = init.indexOf(
                "replayPendingChargingPostCommitMetadata()", lifecycleReconcile);
        assertTrue(initialized >= 0);
        assertTrue(lifecycleReconcile > initialized);
        assertTrue(metadataReplay > lifecycleReconcile);
    }

    @Test
    public void staleCloseRetriesAreFencedToTheirCapturedSessionIdentity()
            throws IOException {
        String database = databaseSource();
        String manager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingSessionManager.java");
        String edge = between(database,
                "public synchronized boolean onChargingEdge(\n"
                        + "            boolean isCharging, long expectedCloseStart",
                "/**\n     * Offer a freshly-observed charged-energy counter");
        String apply = between(manager,
                "boolean applyDatabaseEdgeAndVerify",
                "static boolean databaseEdgeReachedDesiredState");

        int identityFence = edge.indexOf(
                "chargingStartTime != expectedCloseStart");
        int journalMutation = edge.indexOf(
                "if (chargingLifecycleJournalDirty");
        int identityReservation = edge.indexOf(
                "if (!reserveDeferredChargingIdentities())");
        assertTrue(identityFence >= 0);
        assertTrue(journalMutation >= 0);
        assertTrue(journalMutation < identityFence);
        assertTrue(identityReservation > identityFence);
        assertTrue(edge.contains(
                "return isChargingSessionCloseSatisfied(expectedCloseStart)"));
        assertTrue(apply.contains("getChargingCloseTargetStart()"));
        assertTrue(apply.contains(
                "false, Math.max(0L, expectedCloseStart)"));
        assertTrue(apply.contains(
                "socDb.isChargingSessionCloseSatisfied(expectedCloseStart)"));
    }

    @Test
    public void activeJournalRestoreMergesH2ProgressAndStartsOutageGap()
            throws IOException {
        String source = databaseSource();
        String reconcile = between(source,
                "private void reconcileChargingLifecycleJournalWithDatabase",
                "private double outageGapEstimate");
        String merge = between(source,
                "private void mergeRecoveredActiveCounterWithDatabase",
                "private double outageGapEstimate");

        assertTrue(reconcile.contains(
                "mergeRecoveredActiveCounterWithDatabase();"));
        assertTrue(merge.contains(
                "ChargeCounterAccumulator.newestCompleteState("));
        assertTrue(merge.contains(
                "ChargeCounterAccumulator.preferSecondCompleteState("));
        assertFalse(merge.contains("mergeNonRegressing("));
        assertTrue(merge.contains(
                "chargingCounter.beginGapReconciliation("));
    }

    @Test
    public void lifecycleJournalSyncsDirectoryAndRetainsPreOpenUnknownCounter()
            throws IOException {
        String source = databaseSource();
        String persist = between(source,
                "private boolean persistChargingLifecycleJournal",
                "private JSONObject activeChargingLifecycleToJson");
        String load = between(source,
                "private void loadChargingLifecycleJournal",
                "private void restoreActiveChargingLifecycle");
        String start = between(source,
                "if (isCharging && !wasCharging)",
                "} else if (isCharging && wasCharging)");

        assertTrue(persist.contains(
                "if (removed) syncDirectoryMetadata(file.getParentFile())"));
        int move = persist.indexOf("java.nio.file.Files.move(");
        int directorySync = persist.indexOf(
                "syncDirectoryMetadata(parent)", move);
        assertTrue(move >= 0);
        assertTrue(directorySync > move);
        assertTrue(source.contains("channel.force(true);"));
        assertTrue(source.contains("\"preOpenExternal\", preOpen"));
        assertTrue(load.contains(
                "root.optJSONObject(\"preOpenExternal\")"));
        assertTrue(start.contains("freshPreOpenExternal"));
        assertTrue(start.contains(
                "provisionalExternalKwh =\n"
                        + "                            preSessionProvisionalExternalRaw"));
    }

    @Test
    public void tariffUsageReconciliationCoversMaintenanceAndZeroCountProfiles()
            throws IOException {
        String source = databaseSource();
        String replay = between(source,
                "public synchronized void replayPendingChargingPostCommitMetadata",
                "private String getCurrencySymbol");
        String reprice = between(source,
                "public synchronized int repriceSessionsForTariff",
                "The per-kWh rate the most recent completed charge");
        String clear = between(source,
                "public synchronized long clearChargingHistory()",
                "public synchronized boolean deleteChargingSession");
        String delete = between(source,
                "public synchronized boolean deleteChargingSession",
                "public synchronized JSONObject getSocStats");
        String reset = between(source,
                "public synchronized long resetAll()",
                "private synchronized void cleanupOldData()");

        assertTrue(replay.contains(
                "for (com.overdrive.app.charging.TariffProfile profile"));
        assertTrue(replay.contains("tariffIds.add(profile.getId())"));
        assertTrue(replay.contains("SELECT COUNT(*), MAX(end_time)"));
        assertTrue(replay.contains(
                "tariffManager.reconcileUsage("));
        assertTrue(reprice.contains(
                "replayPendingChargingPostCommitMetadata();"));
        assertTrue(clear.contains(
                "replayPendingChargingPostCommitMetadata();"));
        assertTrue(delete.contains(
                "replayPendingChargingPostCommitMetadata();"));
        assertEquals(2, occurrences(reset,
                "replayPendingChargingPostCommitMetadata();"));
    }

    @Test
    public void tariffMetadataReplayLoadsProfilesBeforeDatabaseRepricing()
            throws IOException {
        String replay = between(databaseSource(),
                "public synchronized void replayPendingChargingPostCommitMetadata",
                "private String getCurrencySymbol");

        int tariffManager = replay.indexOf(
                "TariffManager tariffManager =");
        int profileSnapshot = replay.indexOf(
                "configuredTariffs =\n"
                        + "                tariffManager.getProfiles()",
                tariffManager);
        int databaseReprice = replay.indexOf(
                "replayPendingTariffReprices()", profileSnapshot);

        assertTrue(tariffManager >= 0);
        assertTrue(profileSnapshot > tariffManager);
        assertTrue(databaseReprice > profileSnapshot);
    }

    @Test
    public void tariffRepricingIntentIsWriteAheadDurableAndReplayed()
            throws IOException {
        String source = databaseSource();
        String publicReprice = between(source,
                "public synchronized int repriceSessionsForTariff",
                "private int repriceSessionsForTariffNow");
        String queue = between(source,
                "private String queuePendingTariffReprice",
                "Remove an intent only after");
        String replay = between(source,
                "public synchronized void replayPendingChargingPostCommitMetadata",
                "private String getCurrencySymbol");

        int queueCall = publicReprice.indexOf(
                "queuePendingTariffReprice(tariffId)");
        int h2Call = publicReprice.indexOf(
                "repriceSessionsForTariffNow(");
        int completion = publicReprice.indexOf(
                "completePendingTariffReprice(queuedKey)", h2Call);
        assertTrue(queueCall >= 0);
        assertTrue(h2Call > queueCall);
        assertTrue(completion > h2Call);
        assertTrue(publicReprice.contains(
                "throw new IllegalStateException("));
        assertTrue(queue.contains(
                "pendingTariffReprices.add"));
        assertTrue(queue.contains(
                "persistChargingLifecycleJournal()"));
        assertTrue(source.contains(
                "\"pendingTariffReprices\", reprices"));
        assertTrue(source.contains(
                "root.optJSONArray(\"pendingTariffReprices\")"));
        assertTrue(replay.indexOf("replayPendingTariffReprices()")
                < replay.indexOf("tariffManager.reconcileUsage("));
        assertEquals("*",
                SocHistoryDatabase.normalizeTariffRepriceKey(null));
        assertEquals("*",
                SocHistoryDatabase.normalizeTariffRepriceKey(""));
        assertEquals("home",
                SocHistoryDatabase.normalizeTariffRepriceKey("home"));
    }

    @Test
    public void counterImagesCarryLogicalGenerationInJournalH2AndReplacement()
            throws IOException {
        String source = databaseSource();
        String schema = between(source, "String[] newChargingCols", "for (String col");
        String json = between(source,
                "private static JSONObject counterStateToJson",
                "private static JSONObject pricingToJson");
        String restore = between(source,
                "private void mergeRecoveredActiveCounterWithDatabase",
                "private double outageGapEstimate");
        String replacement = between(source,
                "private void insertActiveChargingReplacement",
                "private Boolean isDurablyOpenSession");

        assertTrue(schema.contains(
                "\"counter_observation_generation BIGINT DEFAULT 0\""));
        assertTrue(json.contains("\"observationGeneration\""));
        assertTrue(json.contains("state.observationGeneration"));
        assertTrue(json.contains(
                "source.optLong(\"observationGeneration\", 0L)"));
        assertTrue(json.contains(
                "statement.setLong(first++, Math.max(0L, state.observationGeneration))"));
        assertTrue(source.contains(
                "exact.observationGeneration = Math.max(0L, rs.getLong(8))"));
        assertTrue(restore.contains("newestCompleteState("));
        assertTrue(restore.contains("preferSecondCompleteState("));
        assertTrue(replacement.contains("counter_observation_generation"));
        assertTrue(replacement.contains("bindCounterState(p, 15, counter)"));
    }

    @Test
    public void closeTimestampsStayStrictlyAfterTheirSessionIdentity()
            throws IOException {
        String source = databaseSource();
        String capture = between(source,
                "public synchronized boolean capturePendingChargingClose",
                "private long allocateMonotonicChargingStart");
        String deferred = between(source,
                "private boolean endDeferredPhysicalGeneration",
                "private void observeDeferredPhysicalCounter");
        String close = between(source,
                "} else if (!isCharging && wasCharging)", "wasCharging = isCharging");
        String stale = between(source,
                "private boolean finalizeOneStaleSession", "private static long dayEpoch");
        String replacement = between(source,
                "private DeferredChargingGeneration rebaseDeferredGenerationAtMaintenanceBoundary",
                "private void insertActiveChargingReplacement");

        assertEquals(1_001L,
                SocHistoryDatabase.strictlyAfterChargingStart(1_000L, 900L));
        assertEquals(1_001L,
                SocHistoryDatabase.strictlyAfterChargingStart(1_000L, 1_000L));
        assertEquals(1_500L,
                SocHistoryDatabase.strictlyAfterChargingStart(1_000L, 1_500L));
        assertTrue(capture.contains("strictlyAfterChargingStart("));
        assertTrue(deferred.contains("strictlyAfterChargingStart("));
        assertTrue(close.contains(
                "now = strictlyAfterChargingStart(chargingStartTime, now)"));
        assertTrue(stale.contains("strictlyAfterChargingStart("));
        assertTrue(replacement.contains("rebased.endMs = strictlyAfterChargingStart("));
    }

    @Test
    public void pendingCloseRemapsOnlyItsMaintenanceReplacementAndKeepsWholeCounterImage()
            throws IOException {
        String source = databaseSource();
        String snapshot = between(source,
                "private ActiveChargingReplacement snapshotActiveChargingReplacement",
                "private void insertActiveChargingReplacement");
        String publish = between(source,
                "private void publishActiveChargingReplacement",
                "private void resetLiveChargingState");
        String edge = between(source,
                "public synchronized boolean onChargingEdge(\n"
                        + "            boolean isCharging, long expectedCloseStart",
                "/**\n     * Offer a freshly-observed charged-energy counter");

        assertTrue(snapshot.contains(
                "pendingCloseSessionStart == state.previousStartTime"));
        assertTrue(snapshot.contains(
                "state.counterState = replacementCounter.snapshotState()"));
        assertTrue(publish.contains("if (state.pendingClose"));
        assertTrue(publish.contains("chargingCloseTargetRemaps.put("));
        assertTrue(publish.contains("chargingCounter.restoreState(state.counterState)"));
        assertTrue(publish.contains(
                "pendingCloseSessionStart = state.pendingClose ? state.startTime : 0L"));
        assertTrue(edge.contains(
                "expectedCloseStart = resolveChargingCloseTargetStart(expectedCloseStart)"));
        assertTrue(source.contains("forgetChargingCloseTargetAliases(closedStart)"));
    }

    @Test
    public void sessionJsonSeparatesPersistentOpenRowFromPhysicalChargingTruth()
            throws IOException {
        String source = databaseSource();
        String json = between(source,
                "private JSONObject chargingRowToJson", "private double resolvePeakKw");

        assertTrue(json.contains("boolean inProgress = rs.wasNull()"));
        assertTrue(json.contains("o.put(\"inProgress\", inProgress)"));
        assertTrue(json.contains("o.put(\"chargingNow\", inProgress"));
        assertTrue(json.contains("physicalChargingStateKnown && physicalChargingNow"));
        assertTrue(json.contains("boolean isOpen = inProgress"));
        assertTrue(json.contains(
                "isOpen && wasCharging && start == chargingStartTime\n"
                        + "                && chargingLiveEnrichmentAllowed"));
        assertTrue(source.contains(
                "public synchronized void setPhysicalChargingNow(boolean chargingNow)"));
        assertTrue(source.contains(
                "public synchronized void setChargingLiveEnrichmentAllowed(boolean allowed)"));
    }

    @Test
    public void liveEnergyKeepsValueAndQualityInOneSnapshot()
            throws IOException {
        String source = databaseSource();
        String accessor = between(source,
                "public synchronized OpenChargingSessionEnergy getOpenChargingSessionEnergy()",
                "/** Compatibility accessor");
        String json = between(source,
                "private JSONObject chargingRowToJson",
                "private double resolvePeakKw");

        assertTrue(accessor.contains("r.energyKwh"));
        assertTrue(accessor.contains("r.source"));
        assertTrue(accessor.contains("r.incomplete"));
        assertTrue(accessor.contains(
                "isSessionEnergyEstimated(r.source, r.incomplete)"));
        assertTrue(json.contains(
                "OpenChargingSessionEnergy liveEnergy ="));
        assertTrue(json.contains(
                "o.put(\"energySource\", liveEnergy.source)"));
        assertTrue(json.contains(
                "o.put(\"energyIncomplete\", liveEnergy.incomplete)"));
        assertTrue(json.contains(
                "o.put(\"energyEstimated\", liveEnergy.estimated)"));
        assertTrue(source.contains(
                "out.put(\"periodEstimatedSessions\""));
        assertTrue(source.contains(
                "out.put(\"lifetimeEstimatedSessions\""));
        assertTrue(source.contains(
                "private int countEstimatedEnergySessions("));
    }

    @Test
    public void maintenanceBoundaryIsWriteAheadAndRebasesDeferredOnlyState()
            throws IOException {
        String source = databaseSource();
        String snapshot = between(source,
                "private ChargingMaintenanceIntent snapshotChargingMaintenanceIntent",
                "private ActiveChargingReplacement snapshotActiveChargingReplacement");
        String begin = between(source,
                "private ChargingMaintenanceIntent beginChargingMaintenance",
                "private ActiveChargingReplacement snapshotActiveChargingReplacement");
        String publish = between(source,
                "private void publishCommittedChargingMaintenance",
                "private void cancelRolledBackChargingMaintenance");
        String persist = between(source,
                "private boolean persistChargingLifecycleJournal",
                "static void syncDirectoryMetadata");
        String reconcile = between(source,
                "private ChargingMaintenanceOutcome reconcileChargingMaintenanceOutcome",
                "private String[] chargingMaintenanceTables");

        assertTrue(snapshot.contains(
                "intent.replacement == null && !deferredPhysicalGenerations.isEmpty()"));
        assertTrue(snapshot.contains(
                "rebaseDeferredGenerationAtMaintenanceBoundary("));
        assertTrue(begin.indexOf("pendingChargingMaintenanceIntent = intent")
                < begin.indexOf("persistChargingLifecycleJournal()"));
        assertTrue(persist.contains(
                "\"maintenanceIntent\""));
        assertTrue(publish.contains("resetLiveChargingState(false, false)"));
        assertTrue(publish.contains("deferredPhysicalGenerations.clear()"));
        assertTrue(publish.contains(
                "deferredPhysicalGenerations.addAll(intent.deferredGenerations)"));
        assertTrue(reconcile.contains("readDurableActiveChargingReplacement"));
        assertTrue(reconcile.contains("areTablesDurablyEmpty("));
    }

    @Test
    public void unreadableLifecycleJournalCannotBeReconciledOrOverwritten()
            throws IOException {
        String source = databaseSource();
        String load = between(source,
                "private void loadChargingLifecycleJournal",
                "private void restoreActiveChargingLifecycle");
        String persist = between(source,
                "private boolean persistChargingLifecycleJournal",
                "static void syncDirectoryMetadata");
        String reconcile = between(source,
                "private void reconcileChargingLifecycleJournalWithDatabase",
                "private void mergeRecoveredActiveCounterWithDatabase");

        assertTrue(load.contains("chargingLifecycleJournalReadFailed = true"));
        assertTrue(persist.contains("if (chargingLifecycleJournalReadFailed)"));
        assertTrue(persist.contains(
                "Refusing to overwrite an unreadable charging lifecycle journal"));
        assertTrue(reconcile.contains("chargingLifecycleJournalReadFailed"));
    }

    @Test
    public void unreadableLifecycleJournalBytesRemainUntouched()
            throws Exception {
        Path journal = Files.createTempFile("charging-lifecycle-corrupt", ".json");
        byte[] malformed = "{truncated".getBytes(StandardCharsets.UTF_8);
        Files.write(journal, malformed);
        try {
            SocHistoryDatabase database =
                    new SocHistoryDatabase(journal.toFile());
            invoke(database, "loadChargingLifecycleJournal",
                    new Class<?>[0]);
            Object persisted = invoke(database,
                    "persistChargingLifecycleJournal", new Class<?>[0]);
            assertEquals(Boolean.TRUE, persisted);
            assertTrue(java.util.Arrays.equals(
                    malformed, Files.readAllBytes(setAsideJournal(journal))));
        } finally {
            deleteJournalFiles(journal);
        }
    }

    @Test
    public void jdbcOutageJournalsFirstStartAndActiveCounterProgress()
            throws IOException {
        String source = databaseSource();
        String init = between(source,
                "public synchronized void init()",
                "public synchronized void stop()");
        String edge = between(source,
                "public synchronized boolean onChargingEdge(\n"
                        + "            boolean isCharging, long expectedCloseStart",
                "/**\n     * Offer a freshly-observed charged-energy counter");
        String outageStart = between(source,
                "private boolean journalPhysicalChargingStartWithoutDatabase",
                "/** Capture the endpoint of the newest deferred charge");
        String callback = between(source,
                "public synchronized void onChargeCounterObserved",
                "// synchronized: an EXTERNAL entry point");

        assertTrue(edge.contains("journalPhysicalChargingStartWithoutDatabase()"));
        assertTrue(init.indexOf("loadChargingLifecycleJournal()")
                < init.indexOf("DriverManager.getConnection("));
        assertTrue(outageStart.contains(
                "deferredPhysicalGenerations.addLast(generation)"));
        assertTrue(outageStart.contains("persistChargingLifecycleJournal()"));
        assertFalse(callback.contains(
                "if (!isInitialized || connection == null) return"));
        assertTrue(callback.contains(
                "boolean databaseAvailable = isInitialized && connection != null"));
        assertTrue(callback.contains("else persistChargingLifecycleJournal()"));
    }

    @Test
    public void authoritativeAndForcedStopsPersistResumeBarriers()
            throws IOException {
        String source = databaseSource();
        String capture = between(source,
                "public synchronized boolean capturePendingChargingClose(boolean resumeBlocked)",
                "private long allocateMonotonicChargingStart");
        String stale = between(source,
                "private boolean finalizeOneStaleSession",
                "private static long dayEpoch");

        assertTrue(capture.contains(
                "if (resumeBlocked && !pendingCloseResumeBlocked)"));
        assertTrue(capture.contains(
                "pendingCloseResumeBlocked = resumeBlocked"));
        assertTrue(stale.contains("resume_blocked = ?"));
        assertTrue(stale.contains("upd.setInt(20, forceRecent ? 1 : 0)"));
    }

    @Test
    public void restoredActiveSessionWritesDurablePowerGapBeforeSampling()
            throws IOException {
        String source = databaseSource();
        String reconcile = between(source,
                "private void reconcileChargingLifecycleJournalWithDatabase",
                "private void mergeRecoveredActiveCounterWithDatabase");
        String gap = between(source,
                "private void reconcileRecoveredActivePowerGap",
                "private boolean isRecoveredActivePowerGapDurable");

        assertTrue(reconcile.contains("reconcileRecoveredActivePowerGap()"));
        int intent = gap.indexOf("persistChargingLifecycleJournal()");
        int transaction = gap.indexOf("runInTransaction(() ->");
        assertTrue(intent >= 0);
        assertTrue(transaction > intent);
        assertTrue(gap.contains("STOP_BOUNDARY_POWER_KW"));
        assertTrue(gap.contains("SET integration_truncated = 1"));
    }

    @Test
    public void recoveredPowerGapSqlIsAtomicAndReplayIdempotent()
            throws Exception {
        Path journal = Files.createTempFile("charging-lifecycle-gap", ".json");
        Files.deleteIfExists(journal);
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:charging-gap-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE charging_sessions ("
                    + "start_time BIGINT PRIMARY KEY, end_time BIGINT,"
                    + "integration_truncated INT DEFAULT 0)");
            statement.execute("CREATE TABLE charging_power_samples ("
                    + "id IDENTITY PRIMARY KEY, session_start_time BIGINT NOT NULL,"
                    + "t BIGINT NOT NULL, power_kw REAL, soc REAL, temp REAL,"
                    + "temp_high REAL, temp_low REAL)");
            statement.execute("INSERT INTO charging_sessions"
                    + " (start_time, end_time, integration_truncated)"
                    + " VALUES (1000, NULL, 0)");

            setField(database, "connection", connection);
            setField(database, "wasCharging", true);
            setField(database, "chargingStartTime", 1000L);
            invoke(database, "reconcileRecoveredActivePowerGap",
                    new Class<?>[0]);

            long boundaryAt;
            try (ResultSet rs = statement.executeQuery(
                    "SELECT t FROM charging_power_samples"
                            + " WHERE session_start_time = 1000 AND power_kw = -1")) {
                assertTrue(rs.next());
                boundaryAt = rs.getLong(1);
                assertFalse(rs.next());
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT integration_truncated FROM charging_sessions"
                            + " WHERE start_time = 1000")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            setField(database, "recoveredActivePowerGapAtMs", boundaryAt);
            invoke(database, "reconcileRecoveredActivePowerGap",
                    new Class<?>[0]);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM charging_power_samples"
                            + " WHERE session_start_time = 1000 AND power_kw = -1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void malformedPersistedSohPayloadIsRejectedAndReplayContinues()
            throws IOException {
        String replay = between(databaseSource(),
                "public synchronized void replayPendingChargingPostCommitMetadata",
                "private void markSohCalibrationResolved");

        assertFalse(replay.contains(
                "AND soh_calibration_energy_kwh IS NOT NULL"));
        assertTrue(replay.contains("boolean startSocMissing = rs.wasNull()"));
        assertTrue(replay.contains("boolean endSocMissing = rs.wasNull()"));
        assertTrue(replay.contains("boolean energyMissing = rs.wasNull()"));
        assertTrue(replay.contains("!isFinite(startSoc)"));
        assertTrue(replay.contains("!isFinite(row.energyKwh)"));
        assertTrue(replay.contains("if (!row.persistedPayloadValid)"));
        assertTrue(replay.contains(
                "markSohCalibrationResolved(row.sessionStart, true)"));
        assertTrue(replay.contains("continue;"));
    }

    @Test
    public void jdbcOutageDeferredStartAdoptsConfirmedPreSessionCounter()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-lifecycle-pre-session-counter", ".json");
        Files.deleteIfExists(journal);
        SocHistoryDatabase database = new SocHistoryDatabase(journal.toFile());
        long observedAt = System.currentTimeMillis() - 100L;
        try {
            setField(database, "chargingAnalyticsEnabled", true);
            setField(database, "chargingLifecycleJournalLoaded", true);
            setField(database, "preSessionCounterLowKwh", 0.25);
            setField(database, "preSessionCounterAtMs", observedAt);
            setField(database, "preSessionCounterSource", "chargingCapacity");
            setField(database, "lastRecordedSoc", 50.0);

            assertEquals(Boolean.TRUE, invoke(database,
                    "persistChargingLifecycleJournal", new Class<?>[0]));
            String beforeOn = new String(
                    Files.readAllBytes(journal), StandardCharsets.UTF_8);
            assertTrue(beforeOn.contains("\"preSessionCounter\""));
            assertTrue(beforeOn.contains("\"value\":0.25"));

            SocHistoryDatabase recoveredBeforeOn =
                    new SocHistoryDatabase(journal.toFile());
            invoke(recoveredBeforeOn, "loadChargingLifecycleJournal",
                    new Class<?>[0]);
            assertEquals(0.25,
                    ((Number) getField(
                            recoveredBeforeOn, "preSessionCounterLowKwh"))
                            .doubleValue(),
                    0.000001);
            assertEquals(observedAt,
                    ((Number) getField(
                            recoveredBeforeOn, "preSessionCounterAtMs"))
                            .longValue());
            assertEquals("chargingCapacity",
                    getField(recoveredBeforeOn, "preSessionCounterSource"));
            setField(recoveredBeforeOn, "chargingAnalyticsEnabled", true);
            setField(recoveredBeforeOn, "lastRecordedSoc", 50.0);
            database = recoveredBeforeOn;

            Object result = invoke(database,
                    "journalPhysicalChargingStartWithoutDatabase",
                    new Class<?>[0]);
            assertEquals(Boolean.TRUE, result);

            @SuppressWarnings("unchecked")
            ArrayDeque<Object> generations =
                    (ArrayDeque<Object>) getField(
                            database, "deferredPhysicalGenerations");
            assertEquals(1, generations.size());
            Object generation = generations.peekFirst();
            assertEquals("chargingCapacity",
                    getField(generation, "counterOwner"));
            assertEquals(Boolean.FALSE,
                    getField(generation, "counterBaselinePending"));
            assertEquals(0.25,
                    ((Number) getField(generation, "counterLatestKwh"))
                            .doubleValue(),
                    0.000001);
            assertEquals(observedAt,
                    ((Number) getField(generation, "counterLatestAtMs"))
                            .longValue());

            Object counter = getField(generation, "counter");
            Method baseline = counter.getClass().getMethod("baselineKwh");
            assertEquals(0.25,
                    ((Number) baseline.invoke(counter)).doubleValue(),
                    0.000001);
            assertTrue(Files.isRegularFile(journal));
            String persisted = new String(
                    Files.readAllBytes(journal), StandardCharsets.UTF_8);
            assertTrue(persisted.contains("\"counterOwner\":\"chargingCapacity\""));
            assertTrue(persisted.contains("\"baseline\":0.25"));
            assertFalse(persisted.contains("\"preSessionCounter\""));
            assertTrue(Double.isNaN(
                    ((Number) getField(
                            database, "preSessionCounterLowKwh"))
                            .doubleValue()));
            assertEquals(0L,
                    ((Number) getField(
                            database, "preSessionCounterAtMs"))
                            .longValue());
            assertEquals(null,
                    getField(database, "preSessionCounterSource"));

            SocHistoryDatabase restored =
                    new SocHistoryDatabase(journal.toFile());
            invoke(restored, "loadChargingLifecycleJournal",
                    new Class<?>[0]);
            assertEquals(Boolean.FALSE,
                    getField(restored, "chargingLifecycleJournalReadFailed"));
            @SuppressWarnings("unchecked")
            ArrayDeque<Object> restoredGenerations =
                    (ArrayDeque<Object>) getField(
                            restored, "deferredPhysicalGenerations");
            assertEquals(1, restoredGenerations.size());
            Object restoredCounter =
                    getField(restoredGenerations.peekFirst(), "counter");
            Method restoredBaseline =
                    restoredCounter.getClass().getMethod("baselineKwh");
            assertEquals(0.25,
                    ((Number) restoredBaseline.invoke(restoredCounter))
                            .doubleValue(),
                    0.000001);
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void preSessionEvidenceMovesAtomicallyIntoItsDeferredOwner()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-lifecycle-pre-session-provisional", ".json");
        Files.deleteIfExists(journal);
        long observedAt = System.currentTimeMillis() - 100L;
        try {
            SocHistoryDatabase database =
                    new SocHistoryDatabase(journal.toFile());
            setField(database, "chargingAnalyticsEnabled", true);
            setField(database, "chargingLifecycleJournalLoaded", true);
            setField(database, "preSessionProvisionalExternalRaw", 25.0);
            setField(database, "preSessionProvisionalExternalAtMs", observedAt);
            setField(database, "preSessionProvisionalExternalUnitDivisor", 100.0);
            setField(database, "lastRecordedSoc", 50.0);

            assertEquals(Boolean.TRUE, invoke(database,
                    "journalPhysicalChargingStartWithoutDatabase",
                    new Class<?>[0]));
            String persisted = new String(
                    Files.readAllBytes(journal), StandardCharsets.UTF_8);
            assertFalse(persisted.contains("\"preOpenExternal\""));
            assertTrue(Double.isNaN(
                    ((Number) getField(
                            database, "preSessionProvisionalExternalRaw"))
                            .doubleValue()));
            assertEquals(0L,
                    ((Number) getField(
                            database, "preSessionProvisionalExternalAtMs"))
                            .longValue());
            assertEquals(1.0,
                    ((Number) getField(
                            database, "preSessionProvisionalExternalUnitDivisor"))
                            .doubleValue(),
                    0.000001);

            @SuppressWarnings("unchecked")
            ArrayDeque<Object> generations =
                    (ArrayDeque<Object>) getField(
                            database, "deferredPhysicalGenerations");
            Object generation = generations.peekFirst();
            assertEquals(0.25,
                    ((Number) getField(
                            generation, "provisionalExternalKwh"))
                            .doubleValue(),
                    0.000001);
            assertEquals(observedAt,
                    ((Number) getField(
                            generation, "provisionalExternalAtMs"))
                            .longValue());
            assertEquals(100.0,
                    ((Number) getField(
                            generation, "provisionalExternalUnitDivisor"))
                            .doubleValue(),
                    0.000001);

            SocHistoryDatabase restored =
                    new SocHistoryDatabase(journal.toFile());
            invoke(restored, "loadChargingLifecycleJournal",
                    new Class<?>[0]);
            assertTrue(Double.isNaN(
                    ((Number) getField(
                            restored, "preSessionProvisionalExternalRaw"))
                            .doubleValue()));
            @SuppressWarnings("unchecked")
            ArrayDeque<Object> restoredGenerations =
                    (ArrayDeque<Object>) getField(
                            restored, "deferredPhysicalGenerations");
            assertEquals(0.25,
                    ((Number) getField(
                            restoredGenerations.peekFirst(),
                            "provisionalExternalKwh"))
                            .doubleValue(),
                    0.000001);

            SocHistoryDatabase failed = new SocHistoryDatabase(null);
            setField(failed, "chargingAnalyticsEnabled", true);
            setField(failed, "chargingLifecycleJournalLoaded", true);
            setField(failed, "preSessionProvisionalExternalRaw", 25.0);
            setField(failed, "preSessionProvisionalExternalAtMs", observedAt);
            setField(failed, "preSessionProvisionalExternalUnitDivisor", 100.0);
            setField(failed, "lastRecordedSoc", 50.0);
            assertEquals(Boolean.FALSE, invoke(failed,
                    "journalPhysicalChargingStartWithoutDatabase",
                    new Class<?>[0]));
            assertEquals(25.0,
                    ((Number) getField(
                            failed, "preSessionProvisionalExternalRaw"))
                            .doubleValue(),
                    0.000001);
            assertEquals(observedAt,
                    ((Number) getField(
                            failed, "preSessionProvisionalExternalAtMs"))
                            .longValue());
            assertEquals(100.0,
                    ((Number) getField(
                            failed, "preSessionProvisionalExternalUnitDivisor"))
                            .doubleValue(),
                    0.000001);
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    @Test
    public void activeAndDeferredEvidenceConsumptionPrecedesJournalPublication()
            throws IOException {
        String source = databaseSource();
        String writeAhead = between(source,
                "private DeferredChargingGeneration journalCurrentChargingStart",
                "/** Fence the old row");
        String outage = between(source,
                "private boolean journalPhysicalChargingStartWithoutDatabase",
                "/** Capture the endpoint");
        String adoption = between(source,
                "private void consumeDeferredPhysicalSessionAfterStart",
                "private void discardDeferredPhysicalSessions");

        assertTrue(writeAhead.indexOf("preSessionCounterAtMs = 0L")
                < writeAhead.indexOf("persistChargingLifecycleJournal()"));
        assertTrue(writeAhead.indexOf("clearPreSessionProvisionalExternal()")
                < writeAhead.indexOf("persistChargingLifecycleJournal()"));
        assertTrue(writeAhead.lastIndexOf(
                        "preSessionProvisionalExternalRaw =")
                > writeAhead.indexOf("persistChargingLifecycleJournal()"));
        assertTrue(outage.indexOf("preSessionCounterAtMs = 0L")
                < outage.lastIndexOf("persistChargingLifecycleJournal()"));
        assertTrue(outage.indexOf("clearPreSessionProvisionalExternal()")
                < outage.lastIndexOf("persistChargingLifecycleJournal()"));
        assertTrue(adoption.indexOf("preSessionCounterAtMs = 0L")
                < adoption.indexOf("persistChargingLifecycleJournal()"));
        assertTrue(adoption.indexOf("clearPreSessionProvisionalExternal()")
                < adoption.indexOf("persistChargingLifecycleJournal()"));
    }

    @Test
    public void unavailableLiveStartTransfersConfirmedCounterIntoActiveJournal()
            throws Exception {
        long observedAt = System.currentTimeMillis() - 100L;
        SocHistoryDatabase database = new SocHistoryDatabase(null);
        invoke(database,
                "adoptConfirmedPreSessionCounterAsActiveBaseline",
                new Class<?>[] { double.class, long.class, String.class },
                0.25, observedAt, "chargingCapacity");

        com.overdrive.app.charging.ChargeCounterAccumulator counter =
                (com.overdrive.app.charging.ChargeCounterAccumulator)
                        getField(database, "chargingCounter");
        com.overdrive.app.charging.ChargeCounterAccumulator.State state =
                counter.snapshotState();
        assertEquals(0.25, state.baseline, 0.000001);
        assertEquals(0.25, state.last, 0.000001);
        assertEquals(observedAt, state.lastAtMs);
        assertEquals("chargingCapacity", getField(database, "counterOwner"));
        assertEquals(Boolean.FALSE,
                getField(database, "counterBaselinePending"));

        String source = databaseSource();
        String start = between(source,
                "double liveNow = preSrc.equals(counterOwner)",
                "// CONTINUATION CHECK");
        assertTrue(start.indexOf("Double.isNaN(liveNow)")
                < start.indexOf(
                        "adoptConfirmedPreSessionCounterAsActiveBaseline"));
        String writeAhead = between(source,
                "private DeferredChargingGeneration journalCurrentChargingStart",
                "/** Fence the old row");
        assertTrue(writeAhead.indexOf("generation.counter.restoreState")
                < writeAhead.indexOf("preSessionCounterLowKwh = Double.NaN"));
        assertTrue(writeAhead.indexOf("preSessionCounterLowKwh = Double.NaN")
                < writeAhead.indexOf("persistChargingLifecycleJournal()"));
    }

    @Test
    public void parseableInvalidLifecycleRecordsFenceWritesAndPreserveBytes()
            throws Exception {
        assertParseableJournalRejected(
                "{\"version\":\"1\",\"lastAllocatedStart\":0,"
                        + "\"deferred\":[]}");
        assertParseableJournalRejected(
                "{\"version\":1,\"lastAllocatedStart\":42,"
                        + "\"active\":{},\"deferred\":[]}");
        assertParseableJournalRejected(
                "{\"version\":1,\"lastAllocatedStart\":42,"
                        + "\"deferred\":[{\"start\":-1}]}");
        assertParseableJournalRejected(
                "{\"version\":1,\"lastAllocatedStart\":42,"
                        + "\"deferred\":[],\"maintenanceIntent\":{"
                        + "\"operation\":\"clearChargingHistory\","
                        + "\"previousStart\":0,\"replacement\":{\"start\":0},"
                        + "\"deferred\":[]}}");
    }

    @Test
    public void parseableJournalDomainViolationPublishesNoPartialState()
            throws Exception {
        String counter = "{\"baseline\":null,\"last\":null,\"lastAt\":0,"
                + "\"observationGeneration\":0,\"energy\":0,\"wraps\":0,"
                + "\"resets\":0,\"ceilingStreak\":0,\"saturated\":false,"
                + "\"abandoned\":0,\"unattributedGaps\":0,"
                + "\"awaitingGap\":false,\"gapReconstructed\":false,"
                + "\"gapEstimate\":null,\"recentRate\":null,"
                + "\"fullScale\":65.534}";
        assertParseableJournalRejected(
                "{\"version\":1,\"lastAllocatedStart\":1000,"
                        + "\"active\":{\"start\":1000,\"startSoc\":150,"
                        + "\"counter\":" + counter + ","
                        + "\"pendingClose\":{\"sessionStart\":0,\"at\":0,"
                        + "\"soc\":null,\"counterCaptured\":false,"
                        + "\"isDc\":-2,\"resumeBlocked\":false},"
                        + "\"optOut\":{\"pending\":false,\"at\":0,"
                        + "\"soc\":null,\"counterCaptured\":false,"
                        + "\"isDc\":-2}},\"deferred\":[]}");
    }

    @Test
    public void malformedEmittedLifecycleFieldsFenceWritesAndPreserveBytes()
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-lifecycle-strict-fields", ".json");
        Files.deleteIfExists(journal);
        try {
            SocHistoryDatabase database =
                    new SocHistoryDatabase(journal.toFile());
            long observedAt = System.currentTimeMillis() - 100L;
            setField(database, "chargingAnalyticsEnabled", true);
            setField(database, "chargingLifecycleJournalLoaded", true);
            setField(database, "preSessionCounterLowKwh", 0.25);
            setField(database, "preSessionCounterAtMs", observedAt);
            setField(database, "preSessionCounterSource", "chargingCapacity");
            setField(database, "lastRecordedSoc", 50.0);
            assertEquals(Boolean.TRUE, invoke(database,
                    "journalPhysicalChargingStartWithoutDatabase",
                    new Class<?>[0]));

            String valid = new String(
                    Files.readAllBytes(journal), StandardCharsets.UTF_8);
            assertParseableJournalRejected(valid.replace(
                    "\"counterOwner\":\"chargingCapacity\"",
                    "\"counterOwner\":null"));
            assertParseableJournalRejected(valid.replace(
                    "\"baselinePending\":false",
                    "\"baselinePending\":\"false\""));
            assertParseableJournalRejected(valid.replace(
                    "\"counterLatestAt\":" + observedAt,
                    "\"counterLatestAt\":0"));
            assertParseableJournalRejected(valid.replace(
                    "\"saturated\":false", "\"saturated\":null"));
            String withoutCounterLineage = valid
                    .replace("\"baseline\":0.25", "\"baseline\":null")
                    .replace("\"last\":0.25", "\"last\":null")
                    .replace("\"lastAt\":" + observedAt, "\"lastAt\":0")
                    .replace("\"observationGeneration\":1",
                            "\"observationGeneration\":0");
            assertParseableJournalRejected(withoutCounterLineage.replace(
                    "\"awaitingGap\":false", "\"awaitingGap\":true"));
            assertParseableJournalRejected(withoutCounterLineage.replace(
                    "\"gapReconstructed\":false",
                    "\"gapReconstructed\":true"));
            assertParseableJournalRejected(valid.replace(
                    "\"samples\":[]",
                    "\"continuation\":{\"rowStart\":1,\"endpointKwh\":0,"
                            + "\"source\":null,\"startSoc\":50,"
                            + "\"fullScaleKwh\":65.534},\"samples\":[]"));
            assertParseableJournalRejected(valid.replace(
                    "\"deferred\":[", "\"active\":null,\"deferred\":["));
            assertParseableJournalRejected(valid.replace(
                    "\"deferred\":[",
                    "\"maintenanceIntent\":null,\"deferred\":["));
        } finally {
            Files.deleteIfExists(journal);
            Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
        }
    }

    private static void assertParseableJournalRejected(String encoded)
            throws Exception {
        Path journal = Files.createTempFile(
                "charging-lifecycle-parseable-invalid", ".json");
        byte[] original = encoded.getBytes(StandardCharsets.UTF_8);
        Files.write(journal, original);
        try {
            SocHistoryDatabase database =
                    new SocHistoryDatabase(journal.toFile());
            invoke(database, "loadChargingLifecycleJournal",
                    new Class<?>[0]);
            assertEquals(Boolean.FALSE,
                    getField(database, "chargingLifecycleJournalReadFailed"));
            assertEquals(Boolean.FALSE, getField(database, "wasCharging"));
            assertEquals(0L,
                    ((Number) getField(
                            database, "lastAllocatedChargingStartMs"))
                            .longValue());
            assertTrue(java.util.Arrays.equals(
                    original, Files.readAllBytes(setAsideJournal(journal))));
            Object persisted = invoke(database,
                    "persistChargingLifecycleJournal", new Class<?>[0]);
            assertEquals(Boolean.TRUE, persisted);
        } finally {
            deleteJournalFiles(journal);
        }
    }

    private static Path setAsideJournal(Path journal) throws IOException {
        String prefix = journal.getFileName() + ".unreadable-";
        try (java.util.stream.Stream<Path> siblings = Files.list(journal.getParent())) {
            return siblings.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("journal was not set aside"));
        }
    }

    private static void deleteJournalFiles(Path journal) throws IOException {
        String prefix = journal.getFileName() + ".unreadable-";
        try (java.util.stream.Stream<Path> siblings = Files.list(journal.getParent())) {
            for (Path sibling : (Iterable<Path>) siblings::iterator) {
                if (sibling.getFileName().toString().startsWith(prefix)) {
                    Files.deleteIfExists(sibling);
                }
            }
        }
        Files.deleteIfExists(journal);
        Files.deleteIfExists(Paths.get(journal.toString() + ".tmp"));
    }

    private static String databaseSource() throws IOException {
        return readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/SocHistoryDatabase.java");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0) {
            throw new AssertionError("Could not locate source markers");
        }
        return source.substring(start, end);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
