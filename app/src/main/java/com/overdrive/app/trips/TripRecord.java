package com.overdrive.app.trips;

import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Mutable record representing a trip from start to finalization.
 * Contains all trip summary fields, Driving DNA scores, and references
 * to micro-moments JSON and telemetry file.
 */
public class TripRecord {

    public long id;                    // Auto-increment PK
    public long startTime;             // Epoch ms
    public long endTime;               // Epoch ms
    public double distanceKm;          // Odometer delta
    // Raw vehicle odometer (km) snapshotted at trip start/end. distanceKm is
    // their delta; these are the absolute readings for display. 0 = the
    // odometer was unavailable at that edge (recovered trips, or a HAL that
    // didn't report it) — the UI treats 0 as "--".
    public double odometerStartKm;
    public double odometerEndKm;
    public int durationSeconds;
    public double avgSpeedKmh;
    public int maxSpeedKmh;
    public double socStart;            // %
    public double socEnd;              // %
    public double kwhStart;            // Remaining kWh at trip start (from BMS)
    public double kwhEnd;              // Remaining kWh at trip end (from BMS)
    // Cumulative HAL electricity-consumption accumulator (kWh) snapshotted at
    // trip start/end — the electric twin of fuelConStart/End. The delta
    // (end-start) is the vehicle's own metered kWh drawn, which is what makes
    // SHORT trips measurable: remaining-energy is derived from a 1%-resolution
    // SoC (~0.6 kWh on a 60 kWh pack ≈ 4 km of driving), so any trip shorter
    // than that reads a flat 0. This counter advances continuously and is
    // independent of both SoC quantisation and the pack-capacity estimate.
    // -1 = unavailable (trim without the accumulator) → fall back to the
    // remaining-kWh delta, then to the SoC estimate.
    public double elecConStart = -1;
    public double elecConEnd = -1;
    public double efficiencySocPerKm;  // SoC% / km (legacy)
    public double energyPerKm;         // kWh / km (from BMS kWh readings)
    public double electricityRate;     // Cost per kWh at time of trip
    public String currency;            // Currency symbol (₹, $, €, £)
    public double tripCost;            // Total trip cost (electric leg + fuel leg)
    // Where electricityRate came from. The energy a trip burns was bought at the
    // LAST CHARGE's tariff, so that is what we price it at (see
    // TripAnalyticsManager). "" = the configured global rate (also every trip
    // recorded before this existed, so old rows read exactly as before).
    //   "charge"  → the most recent completed charging session's rate
    //   "config"  → the global electricityRate from settings
    public String rateSource = "";
    // Human label of the tariff behind a "charge" rate ("Home", "Office"), so the
    // trip card can show WHY it was priced that way. Empty when the charge was
    // priced by the global rate, or when unknown.
    public String rateLabel = "";

    // ── PHEV / hybrid bookkeeping (BEV trips leave these at sentinel) ────
    // For BEVs: isPhev=false, all fuel* fields are 0/NaN, behavior is
    // identical to pre-PHEV builds.
    public boolean isPhev;             // Drivetrain at trip end
    public double fuelPctStart = -1;   // 0-100, -1 = unavailable
    public double fuelPctEnd = -1;     // 0-100, -1 = unavailable
    // Cumulative HAL fuel-consumption accumulator (litres) snapshotted at
    // trip start/end. The delta (end-start) is the vehicle's own metered
    // litres burned — preferred over the lossy fuelPct×tank estimate. -1 =
    // unavailable (BEV, or trim without the accumulator) → fall back to pct.
    public double fuelConStart = -1;
    public double fuelConEnd = -1;
    public double litresUsed;          // Computed litres burned this trip
    public double fuelPricePerL;       // Price snapshot at trip end
    public double fuelCost;            // litresUsed × fuelPricePerL
    public double electricCost;        // energyUsed × electricityRate
    // Cross-thread: incremented on the TripDetector scheduler thread (1Hz),
    // read on the gear/ACC thread when the trip finalises. AtomicInteger
    // gives us a published snapshot without reaching for a synchronized read.
    public final AtomicInteger iceSecondsAtomic = new AtomicInteger(0);
    /** Convenience accessor — most call sites only need a plain int. */
    public int iceSeconds() { return iceSecondsAtomic.get(); }
    public String kinematicState;      // HEAVY_GRIDLOCK, URBAN_FLOW, HIGHWAY_CRUISING
    public String gradientProfile;     // FLAT, HILLY, MOUNTAIN (terrain classification)
    public double elevationGainM;      // Cumulative meters gained (uphill)
    public double elevationLossM;      // Cumulative meters lost (downhill)
    public double avgGradientPercent;  // Average gradient over the trip
    public double startLat, startLon;
    public double endLat, endLon;
    public int extTempC;

    // Driving DNA scores (0-100)
    public int anticipationScore;
    public int smoothnessScore;
    public int speedDisciplineScore;
    public int efficiencyScore;
    public int consistencyScore;

    public String microMomentsJson;    // JSON blob
    public String telemetryFilePath;   // Path to .jsonl.gz
    public long routeId = -1;          // Route cluster ID for O(1) similar-trip lookups

    // ── Storage accounting (server-internal) ─────────────────────────────
    // Byte size of the .jsonl.gz telemetry file at finalize time. Stored
    // so StorageManager.getTripsSize() can answer via SUM(size_bytes)
    // instead of walking every trips dir + stat()ing every file via FUSE
    // (which took 10-20 minutes on full storage). 0 = legacy row not yet
    // backfilled; the size-backfill thread fills these on first run.
    // sidecarSizeBytes is reserved for future trip sidecar files (gps trace
    // etc.); current builds have no sidecars and leave it at 0.
    public long sizeBytes;
    public long sidecarSizeBytes;

    /**
     * Compute the overall Driving DNA score as the average of all 5 axis scores.
     */
    public int getOverallScore() {
        return (int) Math.round((anticipationScore + smoothnessScore + speedDisciplineScore
                + efficiencyScore + consistencyScore) / 5.0);
    }

    /**
     * Metered electricity drawn this trip (kWh) from the HAL's cumulative
     * consumption accumulator, or 0 when unavailable.
     *
     * <p>This is the MOST accurate electric-energy source we have and the only
     * one with usable resolution on short trips — see {@link #elecConStart}.
     * Mirrors the metered-litres path used for the fuel leg, including its
     * reset/rollover guard: a counter that went BACKWARDS (firmware reset, or a
     * unit rollover) would yield a negative volume, so we require
     * {@code end >= start} and otherwise report 0 so callers fall through to the
     * next tier rather than booking a bogus figure.
     *
     * <p>A flat counter legitimately returns 0 (e.g. a PHEV leg driven entirely
     * on the engine), which is a true reading, not a missing one — callers
     * distinguish the two via {@link #hasMeteredEnergy()}.
     */
    public double getMeteredEnergyKwh() {
        return hasMeteredEnergy() ? elecConEnd - elecConStart : 0;
    }

    /**
     * Whether both ends of the cumulative electricity counter were captured and
     * are self-consistent — i.e. {@link #getMeteredEnergyKwh()} is a real
     * measurement (possibly a true 0) rather than "no data". Callers use this to
     * avoid falling through to a coarser tier when the meter legitimately says
     * the pack supplied nothing.
     */
    public boolean hasMeteredEnergy() {
        return Double.isFinite(elecConStart) && Double.isFinite(elecConEnd)
                && elecConStart >= 0 && elecConEnd >= elecConStart;
    }

    /** Both remaining-energy snapshots are usable for a net delta. */
    boolean hasRemainingEnergy() {
        return Double.isFinite(kwhStart) && Double.isFinite(kwhEnd)
                && kwhStart > 0 && kwhEnd > 0;
    }

    /**
     * Get the actual energy consumed in kWh from direct measurement.
     * Returns 0 if no measured source is available (caller should use SoC-based
     * estimation). Always non-negative — used for cost and total-energy accounting.
     *
     * <p><b>The remaining-energy delta wins whenever it can answer.</b> It is NET
     * of regeneration, which is the quantity cost and efficiency must be based on:
     * you only buy back the energy the pack actually ended up short. The metered
     * counter is GROSS draw, so preferring it would inflate the cost of a
     * regen-heavy trip and put stored history on two different axes depending on
     * which channels a trim happens to expose.
     *
     * <p>The metered counter is therefore used only where the net delta CANNOT
     * answer — which is precisely the case this whole tier exists for. Remaining
     * energy is derived from a 1%-resolution SoC (~0.6 kWh, several km of
     * driving), so on a short trip it reports a flat 0; the accumulator still
     * advances. Trading a little regen accuracy for a real number beats reporting
     * zero, and on a trip that short the regen component is negligible anyway.
     */
    public double getEnergyUsedKwh() {
        boolean haveNet = hasRemainingEnergy();
        // Tier 1 — net remaining-energy delta (regen-inclusive).
        if (haveNet && kwhStart > kwhEnd) {
            return kwhStart - kwhEnd;
        }
        // A pack that ended strictly FULLER than it started regenerated more than it
        // drew, so on balance it consumed nothing. Report 0 rather than falling
        // through to the gross counter: billing energy that was put back would charge
        // for a trip that cost nothing, and it would leave this trip's cost and its
        // efficiency score on opposite signs. The signed figure lives in
        // getSignedEnergyKwh; this accessor is the consumption figure.
        if (haveNet && kwhEnd > kwhStart) {
            return 0;
        }
        // Tier 2 — metered gross draw. Reached when the net delta cannot resolve the
        // trip: either there is no remaining-energy channel at all, or (the important
        // case) the two readings are EQUAL. Equal is not "consumed nothing" — it is
        // "below the resolution of this channel", because remaining energy is derived
        // from an integer SoC whose smallest step is several km of driving. That is
        // precisely the short trip this tier exists to measure, so it must not be
        // mistaken for a measured zero.
        if (hasMeteredEnergy()) {
            return getMeteredEnergyKwh();
        }
        return 0;
    }

    /**
     * Get the trip's resolved energy use in kWh for ENERGY ACCOUNTING (rollup
     * totals). Prefers the direct BMS measurement; when that's absent, falls back
     * to {@code energyPerKm × distanceKm} — the same SoC-estimated figure that
     * {@link #energyPerKm} and the trip cost were computed from upstream. This
     * keeps a rollup's total_energy_kwh consistent with its avg_energy_per_km and
     * total_cost on SoC-only trims (where {@link #getEnergyUsedKwh()} returns 0).
     * Always non-negative.
     */
    public double getResolvedEnergyKwh() {
        double measured = getEnergyUsedKwh();
        if (measured > 0) return measured;
        // energyPerKm is resolved upstream from whichever tier actually answered,
        // so honouring it here keeps a rollup total consistent with the per-trip
        // figure the UI and the cost were computed from. When the meter measured a
        // true zero, energyPerKm is 0 too, so this correctly yields 0 rather than
        // resurrecting a value.
        if (energyPerKm > 0 && distanceKm > 0) return energyPerKm * distanceKm;
        return 0;
    }

    /**
     * Get the SIGNED net energy in kWh from BMS readings: positive when the pack
     * drained (normal driving), negative when it gained (regen-dominant descent).
     * Returns 0 only when BMS kWh readings aren't available at all.
     *
     * <p>Used by the efficiency score so a long downhill that nets battery gain
     * scores as excellent rather than neutral. Only the BMS-kWh path is signed;
     * the SoC-delta fallback stays consumption-only because 1%-resolution SoC is
     * too noisy to distinguish genuine regen from sensor jitter.
     */
    public double getSignedEnergyKwh() {
        // A strict inequality either way is a real net movement, signed.
        if (hasRemainingEnergy() && kwhStart != kwhEnd) {
            return kwhStart - kwhEnd; // negative when kwhEnd > kwhStart (net regen)
        }
        // Equal readings are NOT "zero energy" — they mean the trip was below this
        // channel's resolution (it is derived from an integer SoC). Falling through
        // keeps the efficiency score on the same footing as the cost figure, which
        // resolves the same case from the meter; otherwise a short trip would be
        // costed from measured energy but scored as if none had been measured.
        //
        // The metered counter is monotonic (gross draw, regen not subtracted), so it
        // is always >= 0 and cannot express net regen — but a trip too short for the
        // net channel to see is also too short for regen to matter.
        return getMeteredEnergyKwh();
    }

    /**
     * Serialize all fields to JSON (full detail, including micro-moments).
     */
    public JSONObject toJson() {
        return serialize(true);
    }

    /**
     * Serialize to summary JSON (excludes microMomentsJson and telemetryFilePath).
     */
    public JSONObject toSummaryJson() {
        return serialize(false);
    }

    private JSONObject serialize(boolean includeDetails) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("startTime", startTime);
            json.put("endTime", endTime);
            json.put("distanceKm", distanceKm);
            json.put("odometerStartKm", odometerStartKm);
            json.put("odometerEndKm", odometerEndKm);
            json.put("durationSeconds", durationSeconds);
            json.put("avgSpeedKmh", avgSpeedKmh);
            json.put("maxSpeedKmh", maxSpeedKmh);
            json.put("socStart", socStart);
            json.put("socEnd", socEnd);
            // Preserve the existing missing-reading representation instead of
            // letting a non-finite snapshot abort the rest of the JSON object.
            json.put("kwhStart", Double.isFinite(kwhStart) ? kwhStart : 0);
            json.put("kwhEnd", Double.isFinite(kwhEnd) ? kwhEnd : 0);
            if (Double.isFinite(elecConStart) && elecConStart >= 0) json.put("elecConStart", elecConStart);
            if (Double.isFinite(elecConEnd) && elecConEnd >= 0) json.put("elecConEnd", elecConEnd);
            json.put("energyUsedKwh", getEnergyUsedKwh());
            // Signed twin of the above: negative on a regen-dominant trip where
            // the pack ended fuller than it started. energyUsedKwh clamps that to
            // 0 because it feeds cost and rollup totals, which must never go
            // negative — but the UI needs the sign so the Energy tile agrees with
            // the SoC Used tile instead of reading 0 next to a negative delta.
            json.put("signedEnergyKwh", getSignedEnergyKwh());
            // Lets the UI say "energy was measured, and it was ~0" (a genuinely
            // tiny trip) instead of "no data" — the two look identical otherwise.
            json.put("energyMetered", hasMeteredEnergy());
            json.put("efficiencySocPerKm", efficiencySocPerKm);
            json.put("energyPerKm", energyPerKm);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency != null ? currency : "");
            json.put("rateSource", rateSource != null ? rateSource : "");
            json.put("rateLabel", rateLabel != null ? rateLabel : "");
            json.put("tripCost", tripCost);
            json.put("kinematicState", kinematicState != null ? kinematicState : "");
            json.put("gradientProfile", gradientProfile != null ? gradientProfile : "");
            json.put("elevationGainM", elevationGainM);
            json.put("elevationLossM", elevationLossM);
            json.put("avgGradientPercent", avgGradientPercent);
            json.put("startLat", startLat);
            json.put("startLon", startLon);
            json.put("endLat", endLat);
            json.put("endLon", endLon);
            json.put("extTempC", extTempC);
            json.put("anticipationScore", anticipationScore);
            json.put("smoothnessScore", smoothnessScore);
            json.put("speedDisciplineScore", speedDisciplineScore);
            json.put("efficiencyScore", efficiencyScore);
            json.put("consistencyScore", consistencyScore);
            json.put("overallScore", getOverallScore());
            json.put("isPhev", isPhev);
            if (fuelPctStart >= 0) json.put("fuelPctStart", fuelPctStart);
            if (fuelPctEnd >= 0) json.put("fuelPctEnd", fuelPctEnd);
            if (fuelConStart >= 0) json.put("fuelConStart", fuelConStart);
            if (fuelConEnd >= 0) json.put("fuelConEnd", fuelConEnd);
            json.put("litresUsed", litresUsed);
            json.put("fuelPricePerL", fuelPricePerL);
            json.put("fuelCost", fuelCost);
            json.put("electricCost", electricCost);
            json.put("iceSeconds", iceSeconds());
            if (includeDetails) {
                json.put("microMomentsJson", microMomentsJson != null ? microMomentsJson : "");
                json.put("telemetryFilePath", telemetryFilePath != null ? telemetryFilePath : "");
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }
}
