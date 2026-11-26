package com.overdrive.app.server;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Shared PCM16 level reporting for microphone diagnostics; does not capture audio. */
final class MicProbeLevels {
    private MicProbeLevels() {}

    // Below this floor, silence and policy-muted capture cannot be distinguished.
    static boolean isUsable(double rmsDb) {
        return !Double.isInfinite(rmsDb) && rmsDb > -70.0;
    }

    /** Append statistics for the samples actually read; callers supply a positive count. */
    static double append(short[] samples, int count, JSONObject attempt, boolean includeNumericDb)
            throws JSONException {
        double sumSq = 0.0;
        int peak = 0;
        int nonZero = 0;
        for (int s = 0; s < count; s++) {
            int v = samples[s];
            if (v != 0) nonZero++;
            int abs = v < 0 ? -v : v;
            if (abs > peak) peak = abs;
            sumSq += (double) v * v;
        }
        double rms = Math.sqrt(sumSq / count);
        double rmsDb = rms > 0 ? 20.0 * Math.log10(rms / 32768.0) : Double.NEGATIVE_INFINITY;
        attempt.put("nonZeroSamples", nonZero);
        attempt.put("peakAmplitude", peak);
        attempt.put("rms", rms);
        attempt.put("rmsDb", Double.isInfinite(rmsDb) ? "-inf"
                : String.format(Locale.US, "%.2f", rmsDb));
        if (includeNumericDb) {
            attempt.put("rmsDbValue", Double.isInfinite(rmsDb) ? -999.0 : rmsDb);
        }
        attempt.put("usable", isUsable(rmsDb));
        return rmsDb;
    }
}
