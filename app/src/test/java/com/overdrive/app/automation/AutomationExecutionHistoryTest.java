package com.overdrive.app.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

public class AutomationExecutionHistoryTest {

    @Test
    public void historyIsNewestFirstAndBounded() throws Exception {
        AutomationExecutionHistory.clearForTest();
        for (int i = 0; i < 105; i++) {
            AutomationExecutionHistory.record("rule-" + i, "trigger", "queued", i,
                    "signal observed");
        }

        JSONArray history = AutomationExecutionHistory.snapshot(200);
        assertEquals(100, history.length());
        assertEquals("rule-104", history.getJSONObject(0).getString("automationId"));
        assertEquals("rule-5", history.getJSONObject(99).getString("automationId"));
        assertTrue(history.getJSONObject(0).getLong("timestamp") > 0L);
        assertTrue(Automations.diagnosticContext("automation health")
                .getJSONArray("recentExecutions").length() > 0);
    }

    @Test
    public void detailIsBoundedAndOptional() throws Exception {
        AutomationExecutionHistory.clearForTest();
        AutomationExecutionHistory.record("rule", "execution", "failed", 12,
                repeat('x', 400));
        String detail = AutomationExecutionHistory.snapshot(1)
                .getJSONObject(0).getString("detail");
        assertEquals(240, detail.length());
    }


    @Test
    public void failedRuleEvaluationDoesNotPreventFollowingRules() throws Exception {
        AutomationExecutionHistory.clearForTest();
        final boolean[] laterRuleRan = {false};
        Automations.runIsolatedAutomationEvaluation("broken-rule", () -> {
            throw new IllegalStateException("broken condition");
        });
        Automations.runIsolatedAutomationEvaluation("healthy-rule", () ->
                laterRuleRan[0] = true);

        assertTrue(laterRuleRan[0]);
        JSONArray history = AutomationExecutionHistory.snapshot(2);
        assertEquals(1, history.length());
        assertEquals("broken-rule", history.getJSONObject(0).getString("automationId"));
        assertEquals("failed", history.getJSONObject(0).getString("status"));
    }


    @Test
    public void pollerDiagnosticsRecordFailureTypeWithoutExceptionMessage() {
        AutomationExecutionHistory.clearForTest();
        Automations.recordPollerHealth("gear", "failed", 0L,
                new IllegalStateException("sensitive service payload"));
        String json = AutomationExecutionHistory.snapshot(1).toString();
        assertTrue(json.contains("IllegalStateException"));
        org.junit.Assert.assertFalse(json.contains("sensitive service payload"));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
