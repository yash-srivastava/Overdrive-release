package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Pre-aggregated weekly summary of trip data.
 * Keyed by ISO year and week number.
 */
public class WeeklyRollup extends TripRollup {

    public int weekNumber; // ISO week

    /**
     * Serialize to JSON for API responses.
     */
    public JSONObject toJson() {
        return toJson("weekNumber", weekNumber);
    }
}
