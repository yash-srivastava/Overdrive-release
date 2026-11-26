package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Pre-aggregated monthly summary of trip data.
 * Keyed by year and month (1-12).
 */
public class MonthlyRollup extends TripRollup {

    public int month; // 1-12

    /**
     * Serialize to JSON for API responses.
     */
    public JSONObject toJson() {
        return toJson("month", month);
    }
}
