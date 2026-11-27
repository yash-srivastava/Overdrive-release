package com.overdrive.app.safekeep;

import com.overdrive.app.trips.TripRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TripExporterCsvEnergyTest {
    @Test
    public void netBatteryDrawTakesPriorityOverGrossMeteredDraw() {
        TripRecord trip = tripWithReadings(40, 38, 100, 103);
        assertEnergyColumn("2.00", trip);
    }

    @Test
    public void flatOrMissingBatteryReadingUsesMeteredConsumption() {
        assertEnergyColumn("0.25", tripWithReadings(40, 40, 100, 100.25));
        assertEnergyColumn("1.50", tripWithReadings(0, 0, 100, 101.5));
    }

    @Test
    public void estimatedEnergyMatchesConsumptionRateAndDistance() {
        TripRecord trip = tripWithReadings(0, 0, -1, -1);
        trip.distanceKm = 12.5;
        trip.energyPerKm = 0.2;
        trip.tripCost = 0.75;

        String[] row = assertEnergyColumn("2.50", trip);
        assertEquals("12.50", row[4]);
        assertEquals("20.00", row[10]);
        assertEquals("0.75", row[11]);
    }

    @Test
    public void netRegenerationAndGenuineZeroRemainZero() {
        assertEnergyColumn("0.00", tripWithReadings(38, 40, 100, 101));
        assertEnergyColumn("0.00", tripWithReadings(40, 40, 100, 100));
        assertEnergyColumn("0.00", tripWithReadings(0, 0, -1, -1));
    }

    private static String[] assertEnergyColumn(String expected, TripRecord trip) {
        String[] header = TripExporter.csvHeader().trim().split(",", -1);
        String[] row = TripExporter.csvRow(trip).trim().split(",", -1);
        assertEquals("kwh_used", header[9]);
        assertEquals(header.length, row.length);
        assertEquals(expected, row[9]);
        return row;
    }

    private static TripRecord tripWithReadings(double batteryStart, double batteryEnd,
                                               double meterStart, double meterEnd) {
        TripRecord trip = new TripRecord();
        trip.kwhStart = batteryStart;
        trip.kwhEnd = batteryEnd;
        trip.elecConStart = meterStart;
        trip.elecConEnd = meterEnd;
        trip.currency = "CHF";
        return trip;
    }
}
