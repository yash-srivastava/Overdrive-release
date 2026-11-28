package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Calendar;
import java.util.Locale;

import static org.junit.Assert.*;

public class TripPersistenceCompatibilityTest {
    private static final double EPS = 0.0001;
    private DaemonLogger.Config previousLogging;
    private Connection connection;
    private TripDatabase database;

    @Before
    public void openInMemoryStore() throws Exception {
        previousLogging = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false).withFileLog(false).withStdoutLog(false));
        connection = DriverManager.getConnection("jdbc:h2:mem:", "sa", "");
        database = new TripDatabase();
        // Exercise the actual schema without starting the daemon's disk-backed store.
        Field field = TripDatabase.class.getDeclaredField("connection");
        field.setAccessible(true);
        field.set(database, connection);
        Method createTables = TripDatabase.class.getDeclaredMethod("createTables");
        createTables.setAccessible(true);
        createTables.invoke(database);
    }

    @After
    public void closeInMemoryStore() throws Exception {
        try {
            if (connection != null) connection.close();
        } finally {
            if (previousLogging != null) DaemonLogger.configure(previousLogging);
        }
    }

    @Test
    public void resetStillClearsLegacyElectricAndFuelBuckets() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO consumption_buckets " +
                    "(bucket_key, sample_count, sum_kwh_per_km, sum_squared_kwh_per_km) " +
                    "VALUES ('old_ev', 2, 0.6, 0.2)");
            stmt.executeUpdate("INSERT INTO fuel_consumption_buckets " +
                    "(bucket_key, sample_count, sum_litres_per_km, sum_squared_litres_per_km) " +
                    "VALUES ('old_fuel', 2, 0.12, 0.0074)");
        }
        assertEquals(2L, database.resetAll());
        try (Statement stmt = connection.createStatement()) {
            for (String table : new String[]{"consumption_buckets", "fuel_consumption_buckets"}) {
                try (ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    public void rollupsRetainDistinctPeriodKeysAndAllSharedTotals() throws Exception {
        Calendar date = Calendar.getInstance(Locale.US);
        date.clear();
        date.set(2025, Calendar.FEBRUARY, 12, 12, 0);
        long start = date.getTimeInMillis();
        TripRecord first = trip(start, 10, 2, 3, 10);
        TripRecord second = trip(start + 600_000, 20, 6, 5, 21);
        // Insert scored rows too, so the DiLink 5 scored-only path sees the same two trips.
        for (TripRecord trip : new TripRecord[]{first, second}) {
            assertTrue(database.insertTrip(trip) > 0);
            database.updateWeeklyRollup(trip);
            database.updateMonthlyRollup(trip);
        }
        date.setFirstDayOfWeek(Calendar.MONDAY);
        date.setMinimalDaysInFirstWeek(4);
        int week = date.get(Calendar.WEEK_OF_YEAR);
        WeeklyRollup weekly = database.getWeeklyRollup(2025, week);
        MonthlyRollup monthly = database.getRecentMonthlyRollups(1).get(0);
        JSONObject weekJson = weekly.toJson();
        JSONObject monthJson = monthly.toJson();
        assertEquals(week, weekJson.getInt("weekNumber"));
        assertEquals(2, monthJson.getInt("month"));
        assertFalse(weekJson.has("month"));
        assertFalse(monthJson.has("weekNumber"));
        for (JSONObject json : new JSONObject[]{weekJson, monthJson}) {
            assertEquals(14, json.length());
            assertEquals(2025, json.getInt("year"));
            assertEquals(2, json.getInt("tripCount"));
            assertEquals(30, json.getDouble("totalDistanceKm"), EPS);
            assertEquals(120, json.getInt("totalDurationSeconds"));
            assertEquals(0.25, json.getDouble("avgEnergyPerKm"), EPS);
            assertEquals(8, json.getDouble("totalEnergyKwh"), EPS);
            assertEquals(8, json.getDouble("totalCost"), EPS);
            assertEquals(1.5, json.getDouble("avgEfficiency"), EPS);
            assertEquals(16, json.getInt("avgAnticipation"));
            assertEquals(17, json.getInt("avgSmoothness"));
            assertEquals(18, json.getInt("avgSpeedDiscipline"));
            assertEquals(19, json.getInt("avgEfficiencyScore"));
            assertEquals(20, json.getInt("avgConsistency"));
        }
        // Descending year/period order and limit remain part of the public queries.
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO weekly_rollups (\"year\", week_number) VALUES (2024, 53), (2025, 1)");
            stmt.execute("INSERT INTO monthly_rollups (\"year\", month_number) VALUES (2024, 12), (2025, 1)");
        }
        assertEquals(week, database.getRecentWeeklyRollups(2).get(0).weekNumber);
        assertEquals(1, database.getRecentWeeklyRollups(2).get(1).weekNumber);
        assertEquals(2, database.getRecentMonthlyRollups(2).get(0).month);
        assertEquals(1, database.getRecentMonthlyRollups(2).get(1).month);
        assertEquals(1, database.getRecentWeeklyRollups(1).size());
        assertEquals(1, database.getRecentMonthlyRollups(1).size());
        weekly.totalDistanceKm = Double.NaN;
        monthly.totalDistanceKm = Double.NaN;
        assertEquals(3, weekly.toJson().length());
        assertEquals(3, monthly.toJson().length());
    }

    @Test
    public void insertUpdateAndSummaryReadsPreserveTheTripColumnContract() {
        long now = System.currentTimeMillis();
        TripRecord older = trip(now - 300_000, 10, 2, 3, 10);
        TripRecord newer = trip(now - 180_000, 20, 6, 5, 21);
        TripRecord active = trip(now - 30_000, 1, 0, 0, 1);
        active.endTime = 0;
        assertTrue(database.insertTrip(older) > 0);
        assertTrue(database.insertTrip(newer) > 0);
        assertTrue(database.insertTrip(active) > 0);
        newer.routeId = 42;
        newer.isPhev = true;
        newer.fuelConStart = 8;
        newer.fuelConEnd = 9;
        newer.elecConStart = 10;
        newer.elecConEnd = 16;
        newer.sizeBytes = 1234;
        newer.sidecarSizeBytes = 567;
        newer.odometerStartKm = 100;
        newer.odometerEndKm = 120;
        newer.rateSource = "charge";
        newer.rateLabel = "Home";
        assertTrue(database.updateTrip(newer));
        TripRecord full = database.getTrip(newer.id);
        TripRecord summary = database.getTripWithoutMoments(newer.id);
        assertEquals("[]", full.microMomentsJson);
        assertNull(summary.microMomentsJson);
        assertEquals(42, summary.routeId);
        assertTrue(summary.isPhev);
        assertEquals(8, summary.fuelConStart, EPS);
        assertEquals(9, summary.fuelConEnd, EPS);
        assertEquals(10, summary.elecConStart, EPS);
        assertEquals(16, summary.elecConEnd, EPS);
        assertEquals(1234, summary.sizeBytes);
        assertEquals(567, summary.sidecarSizeBytes);
        assertEquals(100, summary.odometerStartKm, EPS);
        assertEquals(120, summary.odometerEndKm, EPS);
        assertEquals("charge", summary.rateSource);
        assertEquals("Home", summary.rateLabel);
        assertEquals(newer.id, database.getTrips(1, 1, -1).get(0).id);
        assertEquals(older.id, database.getTripsWithoutMoments(1, 1, 1).get(0).id);
        assertEquals(2, database.getTrips(1, 10, 0).size());
        assertEquals(2, database.getTripsWithoutMoments(1, 10, 0).size());
        assertEquals(older.id, database.getTripsBetween(now, older.startTime, 1, 1).get(0).id);
        assertEquals(2, database.getTripsBetweenWithoutMoments(older.startTime, 0, 10).size());
        assertNull(database.getTripsBetweenWithoutMoments(older.startTime, now, 1).get(0).microMomentsJson);
    }

    private static TripRecord trip(long start, double distance, double energy, double cost, int score) {
        TripRecord trip = new TripRecord();
        trip.startTime = start;
        trip.endTime = start + 60_000;
        trip.durationSeconds = 60;
        trip.distanceKm = distance;
        trip.kwhStart = 20;
        trip.kwhEnd = 20 - energy;
        trip.energyPerKm = energy / distance;
        trip.efficiencySocPerKm = distance / 10;
        trip.tripCost = cost;
        trip.anticipationScore = score;
        trip.smoothnessScore = score + 1;
        trip.speedDisciplineScore = score + 2;
        trip.efficiencyScore = score + 3;
        trip.consistencyScore = score + 4;
        trip.microMomentsJson = "[]";
        return trip;
    }

}
