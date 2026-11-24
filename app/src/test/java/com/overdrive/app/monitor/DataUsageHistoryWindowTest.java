package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Calendar;
import java.util.TimeZone;

/** Exercise the production history query and totals against an isolated H2 store. */
public class DataUsageHistoryWindowTest {

    private static final TimeZone ZURICH = TimeZone.getTimeZone("Europe/Zurich");

    @Test
    public void twoDayWindowExcludesOlderAndFutureRowsWithoutDeletingThem() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:");
             Statement statement = connection.createStatement()) {
            seedHistory(statement);
            JSONObject usage = new JSONObject();
            DataUsageMonitor.readUsageWindow(connection, usage, now(), 2, ZURICH);

            JSONArray days = usage.getJSONArray("days");
            assertEquals(2, days.length());
            assertEquals("2026-03-29", days.getJSONObject(0).getString("date"));
            assertEquals("2026-03-30", days.getJSONObject(1).getString("date"));
            assertEquals(30L, usage.getLong("totalWifi"));
            assertEquals(12L, usage.getLong("totalMobile"));
            assertEquals(3L, usage.getLong("totalOther"));
            assertEquals(27L, usage.getLong("totalApp"));
            assertEquals(18L, usage.getLong("totalSystem"));
            assertEquals(45L, usage.getLong("total"));
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM data_usage_daily")) {
                assertTrue(rows.next());
                assertEquals(4, rows.getInt(1));
            }
        }
    }

    @Test
    public void oneDayWindowContainsOnlyToday() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:");
             Statement statement = connection.createStatement()) {
            seedHistory(statement);
            JSONObject usage = new JSONObject();
            DataUsageMonitor.readUsageWindow(connection, usage, now(), 1, ZURICH);
            JSONArray days = usage.getJSONArray("days");
            assertEquals(1, days.length());
            assertEquals("2026-03-30", days.getJSONObject(0).getString("date"));
            assertEquals(30L, usage.getLong("total"));
        }
    }

    private static void seedHistory(Statement statement) throws Exception {
        statement.execute("CREATE TABLE data_usage_daily (day_key VARCHAR(10) PRIMARY KEY, "
                + "wifi_bytes BIGINT, mobile_bytes BIGINT, other_bytes BIGINT, "
                + "app_bytes BIGINT, system_bytes BIGINT)");
        statement.execute("INSERT INTO data_usage_daily VALUES "
                + "('2026-03-28', 100, 40, 10, 90, 60), "
                + "('2026-03-29', 10, 4, 1, 9, 6), "
                + "('2026-03-30', 20, 8, 2, 18, 12), "
                + "('2026-03-31', 200, 80, 20, 180, 120)");
    }

    private static long now() {
        // Just after the spring DST change; previous local day is only 23 hours long.
        Calendar calendar = Calendar.getInstance(ZURICH);
        calendar.clear();
        calendar.set(2026, Calendar.MARCH, 30, 0, 30, 0);
        return calendar.getTimeInMillis();
    }
}
