package com.overdrive.app.trips;

import org.json.JSONObject;

/** Applies the same recorded charge tariff and fuel pricing to display snapshots and saved trips. */
final class TripPricing {
    private TripPricing() {}

    static void apply(TripRecord trip, double energyKwh, TripConfig config, JSONObject charge) {
        double fuelPrice = trip.isPhev ? config.getFuelPricePerL() : 0;
        if (trip.isPhev) {
            trip.litresUsed = litresUsed(trip, config.getTankCapacityL());
            trip.fuelPricePerL = fuelPrice;
        }
        trip.fuelCost = trip.isPhev && fuelPrice > 0 ? trip.litresUsed * fuelPrice : 0;

        double rate = charge != null ? charge.optDouble("rate", 0) : 0;
        String currency = charge != null ? charge.optString("currency", "") : "";
        // Petrol is priced in the configured currency. A mixed-currency total would be invalid.
        boolean sameCurrency = trip.fuelCost <= 0 || currency.isEmpty() || currency.equals(config.getCurrency());
        boolean useCharge = rate > 0 && sameCurrency;
        trip.rateSource = useCharge ? "charge" : "config";
        trip.rateLabel = useCharge ? charge.optString("tariffLabel", "") : "";
        trip.electricityRate = useCharge ? rate : config.getElectricityRate();
        trip.currency = useCharge && !currency.isEmpty() ? currency : config.getCurrency();
        trip.electricCost = energyKwh > 0 && trip.electricityRate > 0 ? energyKwh * trip.electricityRate : 0;
        trip.tripCost = trip.electricCost + trip.fuelCost;
    }

    static double litresUsed(TripRecord trip, double tankLitres) {
        if (Double.isFinite(trip.fuelConStart) && Double.isFinite(trip.fuelConEnd)
                && trip.fuelConStart >= 0 && trip.fuelConEnd >= trip.fuelConStart) {
            return trip.fuelConEnd - trip.fuelConStart;
        }
        double drop = trip.fuelPctStart - trip.fuelPctEnd;
        if (trip.fuelPctStart >= 0 && trip.fuelPctStart <= 100
                && trip.fuelPctEnd >= 0 && trip.fuelPctEnd <= 100
                && drop >= 1.0 && Double.isFinite(tankLitres) && tankLitres > 0) {
            return drop / 100.0 * tankLitres;
        }
        return 0;
    }
}
