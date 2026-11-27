package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DnaScoresTest {
    @Test public void everyAxisContributesEqually() {
        assertEquals(20, scores(100, 0, 0, 0, 0).getOverall());
        assertEquals(20, scores(0, 100, 0, 0, 0).getOverall());
        assertEquals(20, scores(0, 0, 100, 0, 0).getOverall());
        assertEquals(20, scores(0, 0, 0, 100, 0).getOverall());
        assertEquals(20, scores(0, 0, 0, 0, 100).getOverall());
    }

    @Test public void overallUsesIntegerAverage() {
        assertEquals(70, scores(90, 80, 70, 60, 50).getOverall());
        // 354 / 5 = 70.8; the existing API truncates, rather than rounds.
        assertEquals(70, scores(90, 80, 70, 60, 54).getOverall());
    }

    @Test public void defaultAndMaximumScoresRemainNumeric() throws Exception {
        assertJsonScores(new DnaScores(), 0, 0, 0, 0, 0, 0);
        assertJsonScores(scores(100, 100, 100, 100, 100), 100, 100, 100, 100, 100, 100);
    }

    @Test public void jsonPreservesAxisNamesAndRecalculatesOverall() throws Exception {
        DnaScores scores = scores(90, 80, 70, 60, 50);
        assertJsonScores(scores, 90, 80, 70, 60, 50, 70);
        scores.consistency = 100;
        assertJsonScores(scores, 90, 80, 70, 60, 100, 80);
    }

    private static void assertJsonScores(DnaScores scores, int anticipation, int smoothness,
                                         int speedDiscipline, int efficiency, int consistency,
                                         int overall) throws Exception {
        JSONObject json = scores.toJson();
        // Compare the raw values as well as their keys, not getInt's coercion of strings.
        assertEquals(anticipation, json.get("anticipation"));
        assertEquals(smoothness, json.get("smoothness"));
        assertEquals(speedDiscipline, json.get("speedDiscipline"));
        assertEquals(efficiency, json.get("efficiency"));
        assertEquals(consistency, json.get("consistency"));
        assertEquals(overall, json.get("overall"));
        assertEquals(overall, scores.getOverall());
    }

    private static DnaScores scores(int anticipation, int smoothness, int speedDiscipline,
                                    int efficiency, int consistency) {
        DnaScores scores = new DnaScores();
        scores.anticipation = anticipation;
        scores.smoothness = smoothness;
        scores.speedDiscipline = speedDiscipline;
        scores.efficiency = efficiency;
        scores.consistency = consistency;
        return scores;
    }
}
