package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class MicProbeLevelsTest {
    @Test
    public void silenceKeepsLegacyStringAndNumericSentinel() throws Exception {
        JSONObject attempt = new JSONObject();
        double db = MicProbeLevels.append(new short[]{0, 0}, 2, attempt, true);
        assertEquals(Double.NEGATIVE_INFINITY, db, 0.0);
        assertEquals(0, attempt.getInt("nonZeroSamples"));
        assertEquals(0, attempt.getInt("peakAmplitude"));
        assertEquals(0.0, attempt.getDouble("rms"), 0.0);
        assertEquals("-inf", attempt.getString("rmsDb"));
        assertEquals(-999.0, attempt.getDouble("rmsDbValue"), 0.0);
        assertFalse(attempt.getBoolean("usable"));
    }

    @Test
    public void partialReadIgnoresUnusedBufferAndHandlesNegativeFullScale() throws Exception {
        JSONObject attempt = new JSONObject();
        double db = MicProbeLevels.append(new short[]{Short.MIN_VALUE, 0, 30000}, 2, attempt, false);
        assertEquals(1, attempt.getInt("nonZeroSamples"));
        assertEquals(32768, attempt.getInt("peakAmplitude"));
        assertEquals(32768.0 / Math.sqrt(2.0), attempt.getDouble("rms"), 1e-9);
        assertEquals(-3.01029995664, db, 1e-9);
        assertEquals("-3.01", attempt.getString("rmsDb"));
        assertFalse(attempt.has("rmsDbValue"));
        assertTrue(attempt.getBoolean("usable"));
    }

    @Test
    public void numericDbRemainsUnroundedAndUsabilityIsStrict() throws Exception {
        JSONObject attempt = new JSONObject();
        double db = MicProbeLevels.append(new short[]{16384}, 1, attempt, true);
        assertEquals(-6.02059991328, db, 1e-9);
        assertEquals(db, attempt.getDouble("rmsDbValue"), 0.0);
        assertEquals("-6.02", attempt.getString("rmsDb"));
        assertFalse(MicProbeLevels.isUsable(-70.0));
        assertTrue(MicProbeLevels.isUsable(-69.99));
        assertFalse(MicProbeLevels.isUsable(-70.01));
        assertFalse(MicProbeLevels.isUsable(Double.NEGATIVE_INFINITY));
    }
}
